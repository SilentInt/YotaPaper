package com.yota.launcher.xposed;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed 入口。配置源为 Launcher 的 EPD 参数管理
 * （SharedPreferences: yota_paper_epd_params）：
 * mode == 2（动画）的条目 = 需要注入翻页动画的 Activity。
 *
 * 过滤只按 Activity 全类名匹配，不再按包名过滤：
 * 目标包进程照常安装 hook，具体窗口是否注入由窗口所属 Activity 决定。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String LAUNCHER_PACKAGE = "com.yota.launcher";
    private static final String LAUNCHER_PREFS = "yota_paper_epd_params";

    private static Set<String> targetActivities = new HashSet<>();
    /**
     * Activity 全类名 → 各自的刷新波形。
     *
     * 波形原先是全局一个 anim_wave，给微信读书选黑条带会把「阅读」一起改掉。
     * 现在 EpdParamsStore 把它存成 anim_wave_map（按条目），这里解析成按类名
     * 索引的表，App 进程和 system_server 都用它来决定「这个窗口该谁写参数」。
     * 原地 clear+putAll，保证已加载的 hook 拿到的一直是同一个对象。
     */
    private static final Map<String, Integer> waveByClass = new HashMap<>();
    /**
     * 缓存的配置读取器。system_server 里的 SystemServerHook 会按秒级频率调
     * {@link #reloadPrefs()}，每次都 new 一个 XSharedPreferences 会把 10KB 的
     * XML 重新解析一遍，这里复用实例靠 XSharedPreferences.reload() 的 mtime 判断。
     */
    private static XSharedPreferences sPrefs;
    /** sPrefs 是否至少成功读过一次（决定要不要重新解析 items JSON）。 */
    private static boolean sLoaded;
    /** 配置文件的 mtime，用来判断「设置有没有变」。 */
    private static long sLastModified = -1L;
    private static final java.io.File sPrefsFile = new java.io.File(
            "/data/data/" + LAUNCHER_PACKAGE + "/shared_prefs/" + LAUNCHER_PREFS + ".xml");
    private static boolean enable = true;
    private static int animWindowMs = 1200;
    private static int animFrameCount = 15;
    // 条带几何统一走「自定义」：条带数 = 动画帧数（15 段时与系统阅读器同款 48px 竖条）
    private static final int animStyle = EpdPageTurnHook.STYLE_CUSTOM;
    // 0=DU 1=FULL 2=FULL+抖动 3=跟随（默认）
    // 界面上现在只有两档，都走灰度波形，差别在 dithering：
    //   FOLLOW（高画质 / dither 2）与 FULL_DITHER（黑条带 / dither 0）。
    // 0/1 是历史值，读取时会被迁移掉，见 EpdParamsStore.loadAnimWave。
    private static int animWave = EpdPageTurnHook.WAVE_FOLLOW;
    // 谁来写参数：按窗口波形动态决定，见 reloadPrefs() 里的 hasFollowTarget()。
    private static boolean animServer = true;
    // 跑在被 hook 的第三方进程里，日志算在对方启动耗时上，默认关闭
    private static boolean debugLog = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (LAUNCHER_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        if ("android".equals(lpparam.packageName)) {
            // system_server：走「epdUpdateId 生成前最后一站」的注入路径。
            // 钩子无条件装上（装一次），具体某一帧归谁写由两个 hook 按窗口波形再查表。
            reloadPrefs();
            android.util.Log.i("YotaPageTurn", "load android(server) targets="
                    + targetActivities.size() + " enable=" + enable
                    + " server=" + animServer);
            if (!targetActivities.isEmpty()) {
                SystemServerHook.install(lpparam, targetActivities,
                        animStyle, animFrameCount, animWave, debugLog);
            }
            return;
        }

        // 每个进程一行，必须无条件打：配置读失败时 debugLog 也是读不到的，
        // 全靠这一行区分「模块没加载」和「加载了但没读到配置」。
        android.util.Log.i("YotaPageTurn","load " + lpparam.packageName
                + " process=" + lpparam.processName);

        reloadPrefs();

        // 功能关闭时直接返回，不写日志：这里是别人的进程，多一行 I/O 都算在对方启动耗时里
        if (!enable) {
            return;
        }

        if (targetActivities.isEmpty()) {
            return;
        }

        if (debugLog) {
            android.util.Log.i("YotaPageTurn","loadPackage " + lpparam.packageName
                    + " process=" + lpparam.processName
                    + " activities=" + targetActivities.size());
        }

        try {
            EpdPageTurnHook.install(lpparam.classLoader, lpparam.packageName,
                    targetActivities, enable, animWindowMs, animFrameCount,
                    animStyle, animWave, animServer, debugLog);
        } catch (Throwable t) {
            android.util.Log.w("YotaPageTurn","install failed for "
                    + lpparam.packageName + " : " + t);
        }
    }

    static void reloadPrefs() {
        try {
            XSharedPreferences lp = sPrefs;
            if (lp == null) {
                lp = new XSharedPreferences(LAUNCHER_PACKAGE, LAUNCHER_PREFS);
                sPrefs = lp;
            }
            // reload() 是 void，拿不到「有没有变」；自己按 mtime 判断，
            // 文件没变时跳过下面的 items JSON 解析 —— 这个函数会在每次抬手时
            // 被调一次，解析 10KB JSON 不能放在那条路上。
            long mt = sPrefsFile.lastModified();
            boolean changed = !sLoaded || mt != sLastModified;
            if (changed) {
                lp.reload();
                String items = lp.getString("items", "");
                // 先解析到临时集合，确认 JSON 合法才整体提交。
                // SharedPreferences 的 apply() 是「写临时文件再 rename」，别的进程
                // 恰好在 rename 那一瞬会读到空串或半个 JSON。早期这里无条件
                // targetActivities.clear() + addAll(空集合)，并且已经把
                // sLastModified 推进掉 —— 目标集合被永久清空，之后 mtime 不再变
                // 也不会重读，表现就是「热切一个 adb 开关后，所有点击都被判
                // window not target，直到重启目标 App」。
                HashSet<String> fresh = new HashSet<>();
                boolean itemsValid = false;
                if (items != null && !items.trim().isEmpty()) {
                    try {
                        JSONArray arr = new JSONArray(items);
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.optJSONObject(i);
                            if (o == null) continue;
                            if (o.optInt("mode", 0) != 2) continue;
                            String activity = o.optString("activity", "");
                            String cls = classOf(activity);
                            if (!cls.isEmpty()) fresh.add(cls);
                        }
                        itemsValid = true;
                    } catch (Throwable ignored) {
                        itemsValid = false;
                    }
                }
                if (!itemsValid) {
                    // 保留旧集合；sLoaded 维持 false，下次 reloadPrefs() 会再试一次。
                    // 注意：items 合法但解析出 0 个动画条目（用户主动全关）不算异常，
                    // 那种情况 itemsValid=true，会正常清空目标集合。
                    android.util.Log.w("YotaPageTurn",
                            "reloadPrefs: items 不可读，保留旧目标集合 targets="
                                    + targetActivities.size());
                } else {
                    sLoaded = true;
                    sLastModified = mt;
                    // 原地 clear+addAll，**不要** new 一个 Set 整体替换：
                    // EpdPageTurnHook 在 install() 时拿的是这个 Set 的引用，而 install
                    // 每个进程只跑一次。
                    targetActivities.clear();
                    targetActivities.addAll(fresh);

                    // 逐条目波形表。键是 items 里的完整 activity 串，这里统一转成类名，
                    // 和 targetActivities 的口径保持一致。没有单独记录的条目回退到
                    // 全局默认值（全局值只作为老数据/新条目的默认，不再被逐条选择污染）。
                    JSONObject wm = null;
                    String waveMapJson = lp.getString("anim_wave_map", "");
                    if (waveMapJson != null && !waveMapJson.trim().isEmpty()) {
                        try {
                            wm = new JSONObject(waveMapJson);
                        } catch (Throwable ignored) {
                        }
                    }
                    int fallback = normalizeWave(
                            lp.getInt("anim_wave", EpdPageTurnHook.WAVE_FOLLOW));
                    HashMap<String, Integer> waves = new HashMap<>();
                    for (String cls : fresh) {
                        int w = fallback;
                        if (wm != null) {
                            Iterator<String> it = wm.keys();
                            while (it.hasNext()) {
                                String k = it.next();
                                if (cls.equals(classOf(k))) {
                                    w = normalizeWave(wm.optInt(k, fallback));
                                    break;
                                }
                            }
                        }
                        waves.put(cls, w);
                    }
                    waveByClass.clear();
                    waveByClass.putAll(waves);
                }
            }

            enable = lp.getBoolean("anim_enable", true);
            animWindowMs = lp.getInt("anim_window_ms", 1200);
            animFrameCount = lp.getInt("anim_frame_count", 15);
            animWave = lp.getInt("anim_wave", EpdPageTurnHook.WAVE_FOLLOW);
            // 谁来写参数：**按窗口各自的波形**决定，见 waveFor()/waveForWindow()。
            //   高画质（FOLLOW）→ system_server：系统自己把 dithering 清成 0，
            //       必须在 getEpdUpdateParameters 之后补回来，进程内做不到；
            //   黑条带（type 4）→ 进程内：原版就是这么写的，能播出整屏重刷动画。
            // 这里这个总开关只表示「至少有一个窗口需要 system_server 接管」，
            // 具体某一帧归谁写，由两个 hook 按窗口名再查一次表。
            animServer = hasFollowTarget();

            // debugLog 要在打日志之前读完，否则判断用的是上一次的值
            debugLog = lp.getBoolean("debug_log", false);
            if (debugLog && changed) {
                android.util.Log.i("YotaPageTurn","prefs loaded from launcher"
                        + " activities=" + targetActivities.size()
                        + " windowMs=" + animWindowMs
                        + " frameCount=" + animFrameCount
                        + " wave=" + EpdPageTurnHook.waveName(animWave)
                        + " server=" + animServer);
            }
        } catch (Throwable t) {
            if (debugLog) {
                android.util.Log.w("YotaPageTurn","launcher prefs read failed: " + t);
            }
        }
    }

    private static String classOf(String activity) {
        if (activity == null) return "";
        String s = activity.trim();
        // 配置里存的是 ComponentInfo{pkg/cls} 形式
        if (s.startsWith("ComponentInfo{")) s = s.substring("ComponentInfo{".length());
        int brace = s.indexOf('}');
        if (brace >= 0) s = s.substring(0, brace);
        int idx = s.indexOf('/');
        return idx >= 0 ? s.substring(idx + 1) : s;
    }

    /** 从配置条目里取包名；取不到返回空串。 */
    // ------------------------------------------------------------ 给 system_server 侧读

    static int getWave() {
        return animWave;
    }

    static int getFrameCount() {
        return animFrameCount;
    }

    static boolean isEnabled() {
        return enable;
    }

    /** true = 参数由 system_server 写；false = 目标进程内自己写。 */
    static boolean isServerMode() {
        return animServer;
    }

    static Set<String> getTargets() {
        return targetActivities;
    }

    /** 老波形值（DU / FULL 无抖动）统一归到 FOLLOW 或 FULL_DITHER。 */
    private static int normalizeWave(int v) {
        return (v == EpdPageTurnHook.WAVE_FULL_DITHER
                || v == EpdPageTurnHook.WAVE_FULL_NO_DITHER)
                ? EpdPageTurnHook.WAVE_FULL_DITHER
                : EpdPageTurnHook.WAVE_FOLLOW;
    }

    /** 某个窗口（Activity 全类名）该用哪个波形；查不到就高画质。 */
    static int waveFor(String activityClass) {
        if (activityClass == null) return EpdPageTurnHook.WAVE_FOLLOW;
        Integer w = waveByClass.get(activityClass);
        return w == null ? EpdPageTurnHook.WAVE_FOLLOW : normalizeWave(w);
    }

    /**
     * system_server 只有 WindowState 的字符串可用：找出它匹配上的目标类名，
     * 再查那个条目的波形。matches() 用的也是同一套 contains 规则。
     */
    static int waveForWindow(String win) {
        if (win == null) return EpdPageTurnHook.WAVE_FOLLOW;
        for (Map.Entry<String, Integer> e : waveByClass.entrySet()) {
            String cls = e.getKey();
            if (cls != null && cls.length() > 0 && win.contains(cls)) {
                return normalizeWave(e.getValue());
            }
        }
        return EpdPageTurnHook.WAVE_FOLLOW;
    }

    /** 只要有一个目标用高画质，system_server 那套钩子就得开着。 */
    static boolean hasFollowTarget() {
        for (Integer w : waveByClass.values()) {
            if (normalizeWave(w) == EpdPageTurnHook.WAVE_FOLLOW) return true;
        }
        return false;
    }
}
