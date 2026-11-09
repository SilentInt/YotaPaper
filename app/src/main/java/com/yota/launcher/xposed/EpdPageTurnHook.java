package com.yota.launcher.xposed;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.Display;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
 * 每 4 个 int 为一段 (left,top,right,bottom)，全屏高、从左到右（或反向）扫过。
 *
 * 风格可切换（[animStyle]）：
 *   STYLE_STOCK_STRIP  原厂细条：15 段等宽竖条整屏高，沿水平方向推开
 *   STYLE_STOCK_BAND   原厂宽条：15 段等高横带整屏宽，沿垂直方向推开
 *   STYLE_CUSTOM       自定义：条带数由 animFrameCount 决定（帧数越少越粗）
 *
 * STOCK 两种的几何直接来自固件：开机日志 EInkSdk.getAnimationFrames
 * （len=60 = 15 段 × 4 int）记下的预设 1/2（48px 竖条沿 x）与预设 5/6
 * （85px 横带沿 y，1280 被 15 除不尽，末段吸收余量）。所以它们跟系统
 * 阅读器看到的是同一套条带，而不是我们自己凑出来的近似值。
 */
public final class EpdPageTurnHook {

    private static final String TAG = "YotaPageTurn";
    private static final String VRI = "android.view.ViewRootImpl";
    private static final String EPD_PARAMS = "com.yotadevices.framework.EpdUpdateParams";
    private static final String EPD_ANIMATION = "com.yotadevices.framework.EpdAnimation";

    public static final int STYLE_STOCK_STRIP = 0;
    public static final int STYLE_STOCK_BAND = 1;
    public static final int STYLE_CUSTOM = 2;

    private static final Map<Object, SwipeTracker> TRACKERS = new WeakHashMap<>();

    private static boolean enable = true;
    private static int animWindowMs = 1200;
    private static int animFrameCount = 15;
    private static int animStyle = STYLE_STOCK_STRIP;
    /**
     * 波形档位。
     *
     * EpdUpdateParams 的全部字段只有这些（实测 EPD_FIELD dump）：
     *   mEpdViewUpdateType / mEpdViewDithering / mIsRegionalUpdate /
     *   mCustomAnimation / mBrightness / mContrast / mBlackStretch /
     *   mWhiteStretch / mSharpening / mUpdateModeAdaptiveTimeout / mSkip /
     *   mColorInversion
     * **没有独立的 waveform 字段** —— 波形完全由 updateType 决定：
     *   updateType = 0 → HAL 日志 "Update with waveform = 8"（DU，无黑闪）
     *   updateType = 4 → HAL 走 FULL/GC，整屏先刷黑再刷白
     *
     * 所以「黑色条带」不是抖动造成的，是 FULL/GC 波形本身的刷屏过程。
     * 要既动又不黑，只能让动画跑在 DU 上：type = 0。
     */
    public static final int WAVE_DU = 0;               // type 0（DU，默认，无黑闪）
    public static final int WAVE_FULL_NO_DITHER = 1;   // type 4 + dither 0（FULL，会黑）
    public static final int WAVE_FULL_DITHER = 2;      // type 4 + dither 1（FULL + 灰度抖动）
    /** 不写 updateType，跟随 App 当前显示模式（可用于高画质）。 */
    public static final int WAVE_FOLLOW = 3;

    private static int animWave = WAVE_DU;
    // install() 一定会覆盖这个默认值；保持 false，避免将来新增调用点漏传参时静默变成常开
    private static boolean debugLog = false;
    /**
     * system_server 侧是否也在写参数。为 true 时本进程只做「点击方向采集 + 上报」，
     * 不再碰 EpdUpdateParams：两边同时写同一份参数，dithering / regional 会互相
     * 覆盖，最后到底是什么波形取决于调用顺序，观感不可控。
     */
    private static boolean serverMode = true;

    private static Set<String> targetActivities = new HashSet<>();

    private EpdPageTurnHook() {
    }

    public static void install(ClassLoader cl, String packageName,
                               Set<String> targetActivities,
                               boolean enable,
                               int animWindowMs,
                               int animFrameCount,
                               int animStyle,
                               int animWave,
                               boolean serverMode,
                               boolean debugLog) {
        EpdPageTurnHook.targetActivities = targetActivities != null ? targetActivities : new HashSet<String>();
        EpdPageTurnHook.enable = enable;
        EpdPageTurnHook.animWindowMs = animWindowMs;
        EpdPageTurnHook.animFrameCount = animFrameCount;
        EpdPageTurnHook.animStyle = animStyle;
        EpdPageTurnHook.animWave = animWave;
        EpdPageTurnHook.serverMode = serverMode;
        EpdPageTurnHook.debugLog = debugLog;

        if (!EpdPageTurnHook.enable) {
            log("disabled, skip");
            return;
        }

        log("prefs: enable=" + EpdPageTurnHook.enable
                + " windowMs=" + EpdPageTurnHook.animWindowMs
                + " frameCount=" + EpdPageTurnHook.animFrameCount
                + " style=" + styleName(EpdPageTurnHook.animStyle)
                + " wave=" + waveName(EpdPageTurnHook.animWave)
                + " serverMode=" + EpdPageTurnHook.serverMode
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
                if (arg instanceof KeyEvent) {
                    // 实体音量键翻页：这类翻页完全不经过触摸，方向只能由按键决定。
                    // 音量+ = 上一页 = 从左到右；音量- = 下一页 = 从右到左。
                    // 不处理的话方向会一直是上一次触摸留下的值。
                    KeyEvent ke = (KeyEvent) arg;
                    if (ke.getAction() == KeyEvent.ACTION_DOWN) {
                        int code = ke.getKeyCode();
                        if (code == KeyEvent.KEYCODE_VOLUME_UP
                                || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                            SwipeTracker tracker = trackerFor(param.thisObject);
                            // 同触摸那一支：先同步配置，再判目标
                            syncConfig();
                            if (isTargetWindow(tracker, param.thisObject)) {
                                int dir = code == KeyEvent.KEYCODE_VOLUME_UP
                                        ? SwipeTracker.DIR_RIGHT : SwipeTracker.DIR_LEFT;
                                tracker.setDirection(dir);
                                tracker.arm();
                                // 高画质档是 system_server 写参数，它只能靠轮询拿到
                                // 「刚翻了一页 + 方向」。音量键不经过触摸，必须在这里发。
                                // 黑条带档由本进程写，不需要发。
                                boolean follow = waveFor(tracker) == WAVE_FOLLOW;
                                if (follow) {
                                    TurnBus.publishArm(dir);
                                }
                                log("input VOLUME key=" + code + " dir=" + dir
                                        + " arm=" + follow);
                            }
                        }
                    }
                    return;
                }
                if (!(arg instanceof MotionEvent)) {
                    if (debugLog) {
                        log("input: non-motion event " + (arg == null ? "null" : arg.getClass().getName())
                                + " args=" + param.args.length);
                    }
                    return;
                }

                MotionEvent ev = (MotionEvent) arg;
                int action = ev.getActionMasked();
                SwipeTracker tracker = trackerFor(param.thisObject);

                // 同步配置必须发生在「判目标窗口」之前。
                //   这道判断用的是 targetActivities，而集合是靠 syncConfig() 刷新的。
                //   早先 syncConfig() 只在判成目标之后才调（ACTION_UP 那一支），
                //   于是一个被判成非目标的窗口永远读不到新配置，也就永远翻不了身 ——
                //   表现正是「在控制中心把某个 App 设成动画、重启后一点反应都没有，
                //   必须靠彻底重启进程才行」。只在 DOWN 时同步一次，MOVE 不碰，
                //   避免拖动时每个事件都去 stat 配置文件。
                if (action == MotionEvent.ACTION_DOWN) {
                    syncConfig();
                }

                if (!isTargetWindow(tracker, param.thisObject)) {
                    if (debugLog) {
                        log("input: ignored, window not target activity=" + tracker.activityName
                                + " action=" + action);
                    }
                    return;
                }

                String vriHex = Integer.toHexString(System.identityHashCode(param.thisObject));

                if (action == MotionEvent.ACTION_DOWN) {
                    int vw = viewWidth(param.thisObject);
                    tracker.onMotionEvent(ev, vw);
                    // 高画质：system_server 的臂信号靠 20ms 轮询送达。等到抬手才发，
                    // 很可能比 App 发起 EPD 更新还晚，于是第一下点击没有动画。
                    // 按下的位置已经能定方向（左右半屏），这里就先发一次；
                    // 滑动翻页会在 ACTION_UP 用最终方向再补一次。
                    if (waveFor(tracker) == WAVE_FOLLOW) {
                        int dir = ev.getRawX() < (vw > 0 ? vw / 2f : 540f)
                                ? SwipeTracker.DIR_RIGHT : SwipeTracker.DIR_LEFT;
                        tracker.setDirection(dir);
                        TurnBus.publishArm(dir);
                    }
                    if (debugLog) {
                        log("input DOWN rawX=" + ev.getRawX() + " rawY=" + ev.getRawY()
                                + " viewW=" + vw
                                + " vri=" + vriHex + " activity=" + tracker.activityName);
                    }
                    return;
                }

                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    int vw = viewWidth(param.thisObject);
                    // 抬手这一刻就把最新配置同步进来：既决定这次由谁写参数，
                    // 也让「刚在设置里切了波形」立刻作用于这一下翻页。
                    syncConfig();
                    boolean turned = tracker.onMotionEvent(ev, vw);
                    // 滑动翻页：按下时用「按在哪半屏」猜的方向大概率是错的，
                    // 抬手时按位移重新定一次并补发臂信号（TurnBus 的 60ms 去重
                    // 对滑动几乎不会触发，拖动通常远超 60ms）。点按不补发，
                    // 避免同一次翻页产生两个 seq、system_server 播两遍动画。
                    if (turned && !tracker.tap() && waveFor(tracker) == WAVE_FOLLOW) {
                        TurnBus.publishArm(tracker.direction());
                    }
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
                        if (turned) {
                            log("turn DETECTED side=" + side
                                    + " tap=" + tracker.tap()
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
                                inject(param.thisObject, param.args[0], param.args, -1, "requestEpdUpdate");
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
                                inject(param.thisObject, param.args[0], param.args, 1, "setEpdParams/before");
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
                                inject(param.thisObject, param.args[0], param.args, 1, "forDrawing/before");
                            }
                        }
                    });
            log("hook setEpdParamsForDrawing(EpdUpdateParams, Rect, boolean, boolean) ok");
        } catch (Throwable t) {
            log("hook setEpdParamsForDrawing FAILED: " + t);
        }
    }

    // ---------------------------------------------------------------- 注入

    /** 把设置界面里的最新配置同步进来，不必重启目标 App。 */
    private static void syncConfig() {
        try {
            // reloadPrefs() 同时刷新「逐条目波形表」waveByClass。
            MainHook.reloadPrefs();
            int f = MainHook.getFrameCount();
            boolean sv = MainHook.isServerMode();
            if (f != animFrameCount || sv != serverMode) {
                log("config sync -> frameCount=" + f + " hasFollow=" + sv);
                animFrameCount = f;
                serverMode = sv;
            }
        } catch (Throwable t) {
            // 读不到就沿用当前值，绝不能让翻页因为配置读取失败而中断
        }
    }

    /** 当前窗口各自的波形：不同 App 可以一个高画质、一个黑条带，互不干扰。 */
    private static int waveFor(SwipeTracker tracker) {
        return MainHook.waveFor(tracker.activityName);
    }

    private static void inject(Object vri, Object params, Object[] args, int rectIdx, String source) {
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

        // 按**这个窗口自己**的波形分工，不能用一个全局布尔值：
        // 阅读可以是高画质，同时微信读书是黑条带。
        final int curWave = waveFor(tracker);

        // 高画质档（WAVE_FOLLOW）：这里一个字段都不写，参数完全交给
        // system_server 的 SystemServerHook 在最后一站写。
        //
        // 之前试过「App 进程挂 mCustomAnimation，system_server 只补波形」，
        // 实测框架跨进程重建 EpdUpdateParams 时把动画帧丢了：日志只有
        // inject FRAMES，system_server 侧没有 SS inject，HAL 也没有
        // animation:enabled —— 高画质动画整个消失（用户反馈「翻页动画没了」）。
        // 所以本进程在高画质档只负责发 TurnBus 臂信号（在输入钩子里做），
        // EPD 参数一律不碰，避免两边互相覆盖。
        if (curWave == WAVE_FOLLOW) {
            if (trace) {
                log("inject SKIP: FOLLOW, 参数交给 system_server");
            }
            return;
        }

        // 一次点击 = 一次动画（原项目语义）：消费掉点击令牌，之后的普通重绘
        // 一律不注入。黑条带档走进程内注入，这道闸门就是「何时播」的唯一依据。
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

            // 几何回到原项目那一份：按「动画帧数」把屏宽等分，每段竖条整屏高，
            // 方向由点击位置决定。不再走固件预设数组 —— 那条路是后来加的，
            // 15 段时算出来虽然一样，但它把「条带数」和「动画帧数」解耦了，
            // 跟原项目的手感对不上。
            int[] frames = buildHorizontalFrames(w, h, tracker.direction(), animFrameCount);
            String src = "local";

            // 黑条带：严格照「未改动副本 (11)」—— 只写三个字段：
            //   mCustomAnimation / mEpdViewUpdateType=4 / mEpdViewDithering=1
            // 原版不碰 mIsRegionalUpdate，多加字段反而会让整屏刷新分支走偏。
            // 注意：只有 Provider 里没有这个 Activity 的 mode 行时 4 才能存活，
            // 否则 EpdUpdateParamsManager.optimizeApp 会按行强制改回 3
            //（见 EpdParamsStore.syncAnimProviderRows）。
            setIntArrayField(params, "mCustomAnimation", frames);
            if (curWave == WAVE_DU) {
                // 历史档位兼容；设置界面上已不提供这一档。
                setIntField(params, "mEpdViewUpdateType", 0);
            } else {
                setIntField(params, "mEpdViewUpdateType", 4);
                setIntField(params, "mEpdViewDithering", 1);
            }

            tracker.lastInjectAt = System.currentTimeMillis();

            log("inject OK source=" + source
                    + " framesFrom=" + src
                    + " style=" + styleName(animStyle)
                    + " wave=" + waveName(curWave)
                    + " dir=" + tracker.direction()
                    + " frames=" + frames.length
                    + " head={" + frames[0] + "," + frames[1] + ","
                    + frames[2] + "," + frames[3] + "}"
                    + " panel=" + w + "x" + h
                    + " displayId=" + displayId
                    + " viewSize=" + (view == null ? "null" : view.getWidth() + "x" + view.getHeight())
                    + " epdCall=" + epdCall);
        } catch (Throwable t) {
            log("inject FAILED: " + t);
        }
    }

    // ---------------------------------------------------------------- 动画帧

    /** 固件自带的预设数组缓存：Horizontal{Left,Right} / Vertical{Top,Bottom}。 */
    private static final Map<String, Method> PRESETS =
            new java.util.HashMap<String, Method>();

    /**
     * 直接调用固件的 com.yotadevices.framework.EpdAnimation 取预设数组。
     *
     * 实测（Y3 / 7.1.1）返回 60 个 int = 15 段 × 4 int，段格式是
     * {left, top, right, bottom}，坐标系是墨水屏 720x1280：
     *   HorizontalLeft   0,0,48,1280 → 48,0,96,1280 → … → 672,0,720,1280
     *   HorizontalRight  672,0,720,1280 → … → 0,0,48,1280
     *   VerticalTop      0,0,720,85 → 0,85,720,171 → … → 0,1195,720,1280
     *   VerticalBottom   0,1195,720,1280 → … → 0,0,720,85
     *
     * Horizontal* 是 48 宽的竖条整屏高 → 横向扫；Vertical* 是整屏宽的横带
     * 85 高 → 纵向扫。用户要的是横向，所以 STYLE_STOCK_STRIP 取 Horizontal*。
     *
     * @return null 表示这台机器上没有该方法，调用方回退到本地拼几何。
     */
    private static int[] stockFrames(View view, int style, int w, int h, int direction) {
        if (view == null || style == STYLE_CUSTOM) return null;
        boolean next = direction == SwipeTracker.DIR_LEFT;
        String name;
        if (style == STYLE_STOCK_BAND) {
            name = next ? "getAnimationVerticalBottom" : "getAnimationVerticalTop";
        } else {
            name = next ? "getAnimationHorizontalRight" : "getAnimationHorizontalLeft";
        }
        try {
            Method m = PRESETS.get(name);
            if (m == null) {
                Class<?> c = epdAnimationClass(view.getClass().getClassLoader());
                if (c == null) return null;
                for (Method cand : c.getDeclaredMethods()) {
                    if (cand.getName().equals(name)) {
                        cand.setAccessible(true);
                        m = cand;
                        break;
                    }
                }
                if (m == null) return null;
                PRESETS.put(name, m);
            }
            Object[] args = m.getParameterTypes().length == 1
                    ? new Object[]{view.getContext()} : new Object[0];
            int[] frames = (int[]) m.invoke(null, args);
            if (frames == null || frames.length < 8) return null;
            // 标签页给的预设是按 720x1280 写死的；分辨率不同就自己拼，
            // 否则整组矩形会越界。
            if (w > 0 && h > 0 && (w != 720 || h != 1280)) return null;
            return frames;
        } catch (Throwable t) {
            log("stockFrames " + name + " failed: " + t);
            return null;
        }
    }

    private static Class<?> epdAnimationClass(ClassLoader cl) {
        try {
            return Class.forName(EPD_ANIMATION, false, cl);
        } catch (Throwable ignored) {
        }
        try {
            return Class.forName(EPD_ANIMATION);
        } catch (Throwable ignored) {
        }
        return null;
    }

    static int[] framesFor(int style, int w, int h, int direction) {
        if (style == STYLE_STOCK_BAND) return buildStockBands(w, h, direction);
        if (style == STYLE_CUSTOM) return buildHorizontalFrames(w, h, direction, animFrameCount);
        return buildStockStrips(w, h, direction);
    }

    static String waveName(int wave) {
        if (wave == WAVE_FOLLOW) return "FOLLOW(不改波形)";
        if (wave == WAVE_FULL_DITHER) return "FULL+dither(type4/dither1)";
        if (wave == WAVE_FULL_NO_DITHER) return "FULL(type4)";
        return "DU(type0)";
    }

    static String styleName(int style) {
        if (style == STYLE_STOCK_BAND) return "stock-band(15x85 上下)";
        if (style == STYLE_CUSTOM) return "custom(" + animFrameCount + " 段)";
        return "stock-strip(15x48 左右)";
    }

    /**
     * 原厂预设 2/1：15 段等宽竖条，整屏高，沿水平方向依次推进。
     * 点右半屏（下一页）= 从右边缘推到左边缘，点左半屏反之。
     * 720/15 = 48，整除，没有余量需要吸收。
     */
    static int[] buildStockStrips(int w, int h, int direction) {
        final int bands = 15;
        int[] frames = new int[bands * 4];
        boolean rightToLeft = direction == SwipeTracker.DIR_LEFT;
        for (int i = 0; i < bands; i++) {
            int idx = rightToLeft ? (bands - 1 - i) : i;
            int x1 = bandEdge(idx, w, bands);
            int x2 = bandEdge(idx + 1, w, bands);
            frames[i * 4] = x1;
            frames[i * 4 + 1] = 0;
            frames[i * 4 + 2] = x2;
            frames[i * 4 + 3] = h;
        }
        return frames;
    }

    /**
     * 原厂预设 6/5：15 段等高横带，整屏宽，沿垂直方向依次推进。
     */
    static int[] buildStockBands(int w, int h, int direction) {
        final int bands = 15;
        int[] frames = new int[bands * 4];
        boolean bottomUp = direction == SwipeTracker.DIR_LEFT;   // 下一页：自下而上
        for (int i = 0; i < bands; i++) {
            int idx = bottomUp ? (bands - 1 - i) : i;
            int y1 = bandEdge(idx, h, bands);
            int y2 = bandEdge(idx + 1, h, bands);
            frames[i * 4] = 0;
            frames[i * 4 + 1] = y1;
            frames[i * 4 + 2] = w;
            frames[i * 4 + 3] = y2;
        }
        return frames;
    }

    /**
     * 第 i 条分割线的位置。固件是四舍五入而不是整除：1280/15 = 85.33，
     * 开机日志里的分割线是 0,85,171,342,…,1195,1280，所以这里也用 round，
     * 否则最后一带会比原厂少 1px，扫过时能看出末段略窄。
     */
    static int bandEdge(int i, int total, int bands) {
        if (i <= 0) return 0;
        if (i >= bands) return total;
        return (int) (((long) i * 2L * total + bands) / (2L * bands));
    }
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
        // 先按「当前配置」复核一次，再看缓存。
        //   下面的 targetChecked 是一次性闩锁：裁定为 true 之后永不重算。
        //   于是用户在设置里把某个 App 从「动画」改回「高画质 / 流畅」后，
        //   这个已经在跑的进程还会一直认为自己该播动画 —— 这正是
        //   「切回高画质、甚至杀掉重开都还有翻页动画」的一半原因。
        //   MainHook.reloadPrefs() 现在原地更新同一个 Set，所以这里直接和
        //   实时集合比一下就能立刻失效，不需要重启目标 App。
        if (tracker.activityName != null
                && !targetActivities.contains(tracker.activityName)) {
            tracker.targetWindow = false;
            tracker.targetChecked = true;
            return false;
        }
        // 非目标时不要一次性判死：setView 时机太早、Activity 还没挂上很常见。
        // 每次重新解析一次（只有 DOWN/UP 会走到这里，开销可忽略）。
        if (!tracker.targetChecked || !tracker.targetWindow) {
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
                //
                // 但有 app（如 Legado）的根 View 不是 getDecorView() 返回的那个
                // 实例，严格比较会永远判成非目标、之后再也不重试。所以补一条：
                // 根 View（getParent()==null）且属于目标 Activity，也算目标窗口。
                boolean decor = isActivityDecorView(activity, view);
                boolean root = view.getParent() == null;
                if (targetActivities.contains(activityName) && (decor || root)) {
                    target = true;
                }
            }
        }
        // 解析不出 Activity 时不要把 targetChecked 定死，下次事件再试一次。
        tracker.markWindow(target, activityName);
        if (activityName == null) {
            tracker.targetChecked = false;
        }
        // activity 解析不出来的是状态栏 / 壁纸之类的窗口，每次触摸都打一行会把
        // logcat 刷满，只在真解析到 Activity 时记录。
        if (activityName != null) {
            log("window filter: activity=" + activityName + " target=" + target
                    + " decor=" + (view != null) + " root=" + (view != null && view.getParent() == null));
        }
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

    /** 字段存在与否取决于固件版本，缺了不算错。 */
    private static void setIntFieldQuiet(Object obj, String name, Object value) {
        try {
            Field f = findField(obj.getClass(), name);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Throwable t) {
            log("field " + name + " skipped: " + t);
        }
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
            // XposedBridge.log 在这台 ROM 上只写安装器的 error.log，logcat 里看不到；
            // android.util.Log 才能被 adb logcat -s YotaPageTurn 直接抓到。
            android.util.Log.i(TAG, msg);
        }
    }
}
