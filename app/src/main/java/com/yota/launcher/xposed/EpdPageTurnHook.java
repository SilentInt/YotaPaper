package com.yota.launcher.xposed;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.Display;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 在每个目标 app 进程里 hook android.view.ViewRootImpl。
 *
 * - enqueueInputEvent(InputEvent)：检测左右侧点击，记录方向。
 * - setView(View, LayoutParams, View)：解析窗口所属 Activity，只对
 *   Launcher EPD 参数管理中 mode=2（动画）的 Activity 打标。
 * - requestEpdUpdate(EpdUpdateParams) / setEpdParams(...)：在 EPD 更新参数
 *   提交前把整屏横翻动画帧塞进 mCustomAnimation，让未适配墨水屏的应用
 *   也获得翻页动画。
 *
 * 动画帧按当前窗口尺寸动态生成，格式与 Yota SDK 一致：
 * 每 4 个 int 为一段 (x1,y1,x2,y2)，全屏高、从左到右（或反向）扫过。
 */
public final class EpdPageTurnHook {

    private static final String TAG = "YotaPageTurn";
    private static final String VRI = "android.view.ViewRootImpl";
    private static final String EPD_PARAMS = "com.yotadevices.framework.EpdUpdateParams";

    private static final Map<Object, SwipeTracker> TRACKERS = new WeakHashMap<>();

    private static boolean enable = true;
    private static int animWindowMs = 1200;
    private static int animFrameCount = 15;
    // install() 一定会覆盖这个默认值；保持 false，避免将来新增调用点漏传参时静默变成常开
    private static boolean debugLog = false;

    private static Set<String> targetActivities = new HashSet<>();

    private EpdPageTurnHook() {
    }

    public static void install(ClassLoader cl, String packageName,
                               Set<String> targetActivities,
                               boolean enable,
                               int animWindowMs,
                               int animFrameCount,
                               boolean debugLog) {
        EpdPageTurnHook.targetActivities = targetActivities != null ? targetActivities : new HashSet<String>();
        EpdPageTurnHook.enable = enable;
        EpdPageTurnHook.animWindowMs = animWindowMs;
        EpdPageTurnHook.animFrameCount = animFrameCount;
        EpdPageTurnHook.debugLog = debugLog;

        if (!EpdPageTurnHook.enable) {
            log("disabled, skip");
            return;
        }

        log("prefs: enable=" + EpdPageTurnHook.enable
                + " windowMs=" + EpdPageTurnHook.animWindowMs
                + " frameCount=" + EpdPageTurnHook.animFrameCount
                + " debugLog=" + EpdPageTurnHook.debugLog
                + " targetActivities=" + EpdPageTurnHook.targetActivities);

        hookTapDetection(cl);
        hookWindowFilter(cl);
        hookEpdInjection(cl);
        log("installed for " + packageName + " classloader " + cl);
    }

    // ---------------------------------------------------------------- hooks

    private static void hookTapDetection(ClassLoader cl) {
        XC_MethodHook inputHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length == 0) {
                    return;
                }
                Object arg = param.args[0];
                if (!(arg instanceof MotionEvent)) {
                    if (debugLog) {
                        log("input: non-motion event " + (arg == null ? "null" : arg.getClass().getName())
                                + " args=" + param.args.length);
                    }
                    return;
                }

                MotionEvent ev = (MotionEvent) arg;
                SwipeTracker tracker = trackerFor(param.thisObject);
                if (!isTargetWindow(tracker, param.thisObject)) {
                    if (debugLog) {
                        log("input: ignored, window not target activity=" + tracker.activityName
                                + " action=" + ev.getActionMasked());
                    }
                    return;
                }

                int action = ev.getActionMasked();
                String vriHex = Integer.toHexString(System.identityHashCode(param.thisObject));

                if (action == MotionEvent.ACTION_DOWN) {
                    int vw = viewWidth(param.thisObject);
                    tracker.onMotionEvent(ev, vw);
                    if (debugLog) {
                        log("input DOWN rawX=" + ev.getRawX() + " rawY=" + ev.getRawY()
                                + " viewW=" + vw
                                + " vri=" + vriHex + " activity=" + tracker.activityName);
                    }
                    return;
                }

                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    int vw = viewWidth(param.thisObject);
                    boolean tap = tracker.onMotionEvent(ev, vw);
                    // 日志参数拼接是急切求值，必须整块包起来，否则关掉开关也照样建字符串
                    if (debugLog) {
                        float totalX = ev.getRawX() - tracker.downX();
                        float totalY = ev.getRawY() - tracker.downY();
                        String side = ev.getRawX() < (vw > 0 ? vw / 2f : 540f) ? "LEFT" : "RIGHT";
                        log("input UP/CANCEL action=" + action
                                + " rawX=" + ev.getRawX() + " rawY=" + ev.getRawY()
                                + " totalX=" + totalX + " totalY=" + totalY
                                + " side=" + side
                                + " vri=" + vriHex);
                        if (tap) {
                            log("tap DETECTED side=" + side
                                    + " dir=" + tracker.direction()
                                    + " vri=" + vriHex + " pending=" + tracker.pending());
                        } else {
                            log("tap NOT detected: |totalX|=" + Math.abs(totalX)
                                    + " |totalY|=" + Math.abs(totalY)
                                    + " slop=" + SwipeTracker.TAP_SLOP_PX);
                        }
                    }
                }
            }
        };

        // Android 7.x 常见单参重载
        try {
            XposedHelpers.findAndHookMethod(VRI, cl, "enqueueInputEvent",
                    InputEvent.class, inputHook);
            log("hook enqueueInputEvent(InputEvent) ok");
        } catch (Throwable t) {
            log("hook enqueueInputEvent(InputEvent) FAILED: " + t);
        }

        // Android 7.x 实际输入管线常走四参重载。
        try {
            XposedHelpers.findAndHookMethod(VRI, cl, "enqueueInputEvent",
                    InputEvent.class, "android.view.InputEventReceiver", int.class, boolean.class, inputHook);
            log("hook enqueueInputEvent(InputEvent, InputEventReceiver, int, boolean) ok");
        } catch (Throwable t) {
            log("hook enqueueInputEvent(4args) FAILED: " + t);
        }
    }

    private static int viewWidth(Object vri) {
        View view = viewOf(vri);
        if (view != null && view.getWidth() > 0) {
            return view.getWidth();
        }
        return 1080;
    }

    /**
     * 固定为指定 Activity 过滤：在窗口创建时解析 Activity 并打标。
     */
    private static void hookWindowFilter(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(VRI, cl, "setView",
                    View.class, WindowManager.LayoutParams.class, View.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            View view = null;
                            if (param.args != null && param.args.length > 0
                                    && param.args[0] instanceof View) {
                                view = (View) param.args[0];
                            } else {
                                view = viewOf(param.thisObject);
                            }
                            SwipeTracker tracker = trackerFor(param.thisObject);
                            markWindow(tracker, view);
                            log("setView: activity=" + tracker.activityName
                                    + " target=" + tracker.targetWindow
                                    + " vri=" + Integer.toHexString(System.identityHashCode(param.thisObject)));
                        }
                    });
            log("hook setView(View, LayoutParams, View) ok");
        } catch (Throwable t) {
            log("hook setView FAILED: " + t);
        }
    }

    private static void hookEpdInjection(ClassLoader cl) {
        Class<?> epdParams = epdParamsClass(cl);
        if (epdParams == null) {
            log("EpdUpdateParams class not found, injection disabled");
            return;
        }
        log("EpdUpdateParams class = " + epdParams);

        // requestEpdUpdate(EpdUpdateParams)
        try {
            XposedHelpers.findAndHookMethod(VRI, cl, "requestEpdUpdate",
                    epdParams, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args != null && param.args.length > 0) {
                                inject(param.thisObject, param.args[0], "requestEpdUpdate");
                            }
                        }
                    });
            log("hook requestEpdUpdate(EpdUpdateParams) ok");
        } catch (Throwable t) {
            log("hook requestEpdUpdate FAILED: " + t);
        }

        // setEpdParams(EpdUpdateParams, Rect, boolean)
        try {
            XposedHelpers.findAndHookMethod(VRI, cl, "setEpdParams",
                    epdParams, Rect.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args != null && param.args.length > 0) {
                                inject(param.thisObject, param.args[0], "setEpdParams");
                            }
                        }
                    });
            log("hook setEpdParams(EpdUpdateParams, Rect, boolean) ok");
        } catch (Throwable t) {
            log("hook setEpdParams FAILED: " + t);
        }

        // setEpdParamsForDrawing(EpdUpdateParams, Rect, boolean, boolean)
        try {
            XposedHelpers.findAndHookMethod(VRI, cl, "setEpdParamsForDrawing",
                    epdParams, Rect.class, boolean.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args != null && param.args.length > 0) {
                                inject(param.thisObject, param.args[0], "setEpdParamsForDrawing");
                            }
                        }
                    });
            log("hook setEpdParamsForDrawing(EpdUpdateParams, Rect, boolean, boolean) ok");
        } catch (Throwable t) {
            log("hook setEpdParamsForDrawing FAILED: " + t);
        }
    }

    // ---------------------------------------------------------------- 注入

    private static void inject(Object vri, Object params, String source) {
        if (params == null) {
            log("inject: params is null source=" + source);
            return;
        }

        SwipeTracker tracker = trackerFor(vri);
        int epdCall = ++tracker.epdCallCount;
        boolean trace = epdCall <= 5 || tracker.pending();

        int origType = -1;
        try {
            origType = getIntField(params, "mEpdViewUpdateType");
        } catch (Throwable ignored) {
        }

        if (trace) {
            log("inject call #" + epdCall
                    + " source=" + source
                    + " origType=" + origType
                    + " pending=" + tracker.pending()
                    + " activity=" + tracker.activityName
                    + " vri=" + Integer.toHexString(System.identityHashCode(vri)));
        }

        if (!isTargetWindow(tracker, vri)) {
            if (trace) {
                log("inject SKIP: window not target, activity=" + tracker.activityName);
            }
            return;
        }

        // 点击触发：直接注入点击后第一个 EPD 更新（Legado 点击翻页只有一组更新）。
        if (!tracker.consume(animWindowMs)) {
            if (trace) {
                log("inject SKIP: no pending tap (windowMs=" + animWindowMs + ")");
            }
            return;
        }

        try {
            View view = viewOf(vri);
            int w = 0;
            int h = 0;
            int displayId = -1;

            // 关键：帧坐标必须是墨水屏物理分辨率（如 720x1280），
            // 而不是 View 的布局尺寸。Legado 在 EPD 上以兼容模式
            // 1080x1920 布局再缩放到 720x1280，View.getWidth() 是 1080。
            if (view != null) {
                try {
                    Display display = view.getDisplay();
                    if (display != null) {
                        Point real = new Point();
                        display.getRealSize(real);
                        displayId = display.getDisplayId();
                        if (real.x > 0 && real.y > 0) {
                            w = real.x;
                            h = real.y;
                        }
                    }
                } catch (Throwable t) {
                    log("getDisplay size failed: " + t);
                }
            }
            if (w <= 0 || h <= 0) {
                w = view != null && view.getWidth() > 0 ? view.getWidth() : 720;
                h = view != null && view.getHeight() > 0 ? view.getHeight() : 1280;
            }

            int[] frames = buildHorizontalFrames(w, h, tracker.direction(), animFrameCount);

            setIntArrayField(params, "mCustomAnimation", frames);
            setIntField(params, "mEpdViewUpdateType", 4);   // 自定义动画
            setIntField(params, "mEpdViewDithering", 1);

            log("inject OK source=" + source
                    + " dir=" + tracker.direction()
                    + " frames=" + frames.length
                    + " panel=" + w + "x" + h
                    + " displayId=" + displayId
                    + " viewSize=" + (view == null ? "null" : view.getWidth() + "x" + view.getHeight())
                    + " view=" + (view == null ? "null" : view.getClass().getName())
                    + " epdCall=" + epdCall);
        } catch (Throwable t) {
            log("inject FAILED: " + t);
        }
    }

    // ---------------------------------------------------------------- 动画帧

    static int[] buildHorizontalFrames(int w, int h, int direction, int frameCount) {
        int count = Math.max(2, Math.min(60, frameCount));
        int step = Math.max(1, w / count);
        int segments = (w + step - 1) / step;
        int[] frames = new int[segments * 4];

        for (int i = 0; i < segments; i++) {
            int x1;
            int x2;
            if (direction == SwipeTracker.DIR_LEFT) {
                // 点击右半屏：从右边缘( w )扫到左边缘( 0 )。每段必须 x1 < x2。
                x1 = w - (i + 1) * step;
                x2 = w - i * step;
                if (x1 < 0) {
                    x1 = 0;
                }
            } else {
                // 点击左半屏：从左边缘( 0 )扫到右边缘( w )。
                x1 = i * step;
                x2 = Math.min(w, x1 + step);
            }
            frames[i * 4] = x1;
            frames[i * 4 + 1] = 0;
            frames[i * 4 + 2] = x2;
            frames[i * 4 + 3] = h;
        }
        return frames;
    }

    // ---------------------------------------------------------------- 窗口过滤

    private static boolean isTargetWindow(SwipeTracker tracker, Object vri) {
        if (!tracker.targetChecked) {
            View view = viewOf(vri);
            if (view != null) {
                markWindow(tracker, view);
            } else {
                // 窗口尚未 setView，先视为非目标，下次事件再尝试解析。
                tracker.targetWindow = false;
                tracker.targetChecked = false;
                tracker.activityName = null;
            }
        }
        return tracker.targetWindow;
    }

    private static void markWindow(SwipeTracker tracker, View view) {
        String activityName = null;
        boolean target = false;
        if (view != null) {
            Activity activity = activityOf(view.getContext());
            if (activity != null) {
                activityName = activity.getClass().getName();
                // 只注入目标 Activity 自己的窗口；Dialog / PopupWindow 等
                // 子窗口的 context 也能解出 Activity，但其 DecorView 与
                // Activity 的 DecorView 不同，必须排除，否则点对话框也会翻页。
                if (isActivityDecorView(activity, view)) {
                    // 只按 Activity 全类名过滤，不再按包名/组件字符串匹配。
                    target = targetActivities.contains(activityName);
                }
            }
        }
        tracker.markWindow(target, activityName);
        log("window filter: activity=" + activityName + " target=" + target);
    }

    private static boolean isActivityDecorView(Activity activity, View view) {
        try {
            if (view == activity.getWindow().getDecorView()) {
                return true;
            }
            return view.getRootView() == activity.getWindow().getDecorView();
        } catch (Throwable t) {
            return false;
        }
    }

    private static Activity activityOf(Context context) {
        Context c = context;
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) {
                return (Activity) c;
            }
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    // ---------------------------------------------------------------- 工具

    private static SwipeTracker trackerFor(Object vri) {
        synchronized (TRACKERS) {
            SwipeTracker t = TRACKERS.get(vri);
            if (t == null) {
                t = new SwipeTracker();
                TRACKERS.put(vri, t);
            }
            return t;
        }
    }

    private static View viewOf(Object vri) {
        try {
            Object v = XposedHelpers.getObjectField(vri, "mView");
            return (v instanceof View) ? (View) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setIntField(Object obj, String name, int value) throws Exception {
        Field f = findField(obj.getClass(), name);
        f.setAccessible(true);
        f.setInt(obj, value);
    }

    private static int getIntField(Object obj, String name) throws Exception {
        Field f = findField(obj.getClass(), name);
        f.setAccessible(true);
        return f.getInt(obj);
    }

    private static void setIntArrayField(Object obj, String name, int[] value) throws Exception {
        Field f = findField(obj.getClass(), name);
        f.setAccessible(true);
        f.set(obj, value);
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> cur = clazz;
        while (cur != null) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Class<?> epdParamsClass(ClassLoader cl) {
        try {
            return Class.forName(EPD_PARAMS, false, cl);
        } catch (Throwable ignored) {
        }
        try {
            return Class.forName(EPD_PARAMS);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void log(String msg) {
        if (debugLog) {
            XposedBridge.log(TAG + ": " + msg);
        }
    }
}
