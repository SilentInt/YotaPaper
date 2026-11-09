package com.yota.launcher.xposed;

import android.os.SystemClock;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * system_server 侧注入（包名 "android"）。
 *
 * 为什么需要它：高画质档（updateType != 4）下，框架在
 * EpdUpdateService.getEpdUpdateParameters 里会把 mCustomAnimation 清掉，
 * App 进程里写的东西到不了 HAL。只有在 system_server 的最后一站把动画帧
 * 重新塞回去，HAL 才会跑 drawAnimation。
 *
 * 黑条带档不走这里：MainHook 按波形把 serverMode 关掉，改由目标进程内写
 * updateType=4（原项目做法），那一档帧不会被系统清掉。
 *
 * 安全：system_server 里任何异常都可能让系统重启，所以每个钩子整体 try/catch，
 * 出错只打日志，绝不向外抛。
 */
final class SystemServerHook {

    private static final String TAG = "YotaPageTurn";

    private static final String MGR = "com.android.server.wm.EpdUpdateParamsManager";
    private static final String SVC = "com.android.server.wm.EpdUpdateParamsManager$EpdUpdateService";
    private static final String WIN = "com.android.server.wm.WindowState";
    private static final String PARAMS = "com.yotadevices.framework.EpdUpdateParams";

    /** epdUpdateId -> 窗口名，用于在 getEpdUpdateParameters 里过滤。 */
    private static final ConcurrentHashMap<Integer, String> ID_WIN = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> PENDING_WIN = new ThreadLocal<String>();

    private static volatile Set<String> targets;
    private static volatile int style = EpdPageTurnHook.STYLE_STOCK_STRIP;
    private static volatile int frameCount = 15;
    private static volatile int wave = EpdPageTurnHook.WAVE_DU;
    private static volatile boolean enabled = true;
    /** 是否由本进程真写参数。false 时钩子照样装着，但一律放过。 */
    private static volatile boolean serverWrite = false;
    /**
     * 本次点击已经调用 generateEpdUpdateId 的次数（1=第一帧，2=第二帧）。
     * 用 AtomicInteger 而不是 ThreadLocal —— 实测 gen 多线程调度，before 写的
     * ThreadLocal 到 after 读不到。这里是跨调用累计的状态，arm() 归零。
     *
     * 关键发现：框架**只有在该帧 params 带 mCustomAnimation 时才走 id 参数路径**，
     * 不带动画的帧直接走 waveform=2 普通刷新、generateEpdUpdateId 返回 -1、
     * 也不会进 getEpdUpdateParameters。所以第二帧必须在 gen 就挂上动画拿到有效 id。
     */
    private static final AtomicInteger sGenIdx = new AtomicInteger(0);
    /** 本次点击是否已经挂过动画（防止重复挂）。 arm 归零。 */
    private static volatile boolean sAnimDone;
    /**
     * 上一次点击是否产生过第 2 帧（idx 到过 2）。用来区分应用是一次点击发两帧
     * （legado：第2帧才是新页内容，动画要挂第2帧）还是只发一帧（微信读书：
     * 唯一帧就是内容帧，动画必须挂它）。arm() 里根据上一次计数更新。
     */
    private static volatile boolean sExpect2;
    /** 最近一次 gen 的窗口/activity（arm 时据此更新该应用的双帧记忆）。 */
    private static volatile String sCurActivity;
    /** 每个 activity 连续「有第 2 帧」的次数；连续 >=2 才认定为双帧应用，避免偶发一次误判。 */
    private static final ConcurrentHashMap<String, Integer> sStreak = new ConcurrentHashMap<>();
    private static boolean debugLog;
    private static volatile boolean installed;
    private static int injected;

    private SystemServerHook() {
    }

    static void install(XC_LoadPackage.LoadPackageParam lp, Set<String> targetActivities,
                        int animStyle, int animFrameCount, int animWave,
                        boolean debug) {
        if (installed) return;
        installed = true;
        targets = targetActivities;
        style = animStyle;
        frameCount = animFrameCount;
        wave = animWave;
        debugLog = debug;
        final ClassLoader cl = lp.classLoader;

        log("SS install targets=" + targets + " style=" + EpdPageTurnHook.styleName(style)
                + " wave=" + EpdPageTurnHook.waveName(wave));

        hook(cl, MGR, "generateEpdUpdateId", new Object[]{WIN, PARAMS, genIdHook(cl)});
        hook(cl, SVC, "getEpdUpdateParameters", new Object[]{int.class, getParamsHook()});
        startPoller();
    }

    // ------------------------------------------------------------------ 点击信号

    private static volatile int sDir = SwipeTracker.DIR_LEFT;
    private static volatile long sArmSeq;
    private static volatile long sArmedAt;
    private static volatile boolean sPollerRun;
    /** 上一次在热路径上重读配置的时间（限频，避免每次 EPD 更新都去 stat 文件）。 */
    private static volatile long sLastPrefsAt;
    /** 上一次 arm 的时间，用来判断「是否在快速连翻」。 */
    private static volatile long sPrevArmAt;
    /**
     * 本击实际生效的条带段数。快速连翻（相邻两次点击间隔 < 900ms）时自动减到
     * 约一半（15→7），缩短单帧刷新时间、减少 EPD 队列堆积和丢帧；正常阅读
     * （间隔 > 900ms）用满 frameCount（15 段）保证画质。0 表示未定，沿用 frameCount。
     */
    private static volatile int sCurBands;

    /**
     * 只在「刚点过屏幕」这段时间里允许注入。
     *
     * 这是修复「界面卡住」的关键。之前只有「距上次注入 400ms 内跳过」的去重，
     * 但那个窗口会自己过期：阅读界面静止不动时，每过 400ms 去重就失效一次，
     * 于是每一次普通重绘（时钟、进度条、任何小刷新）都会被挂上 15 段条带，
     * 循环往复 —— 屏上就是一直在闪、像卡死。
     *
     * 加这道闸门的顾虑是「第一下点击没动画」：臂信号由轮询送达，可能比 App
     * 发起的 EPD 更新还晚。解决办法是 EpdPageTurnHook 在 **ACTION_DOWN** 就
     * 把信号发出去（比抬手翻页早几百毫秒），不再抢那十几毫秒。
     */
    private static boolean armFresh() {
        long t = sArmedAt;
        return t > 0L && SystemClock.elapsedRealtime() - t < 1500L;
    }

    /**
     * 记录一次点击。两条通道（广播 / Provider 轮询）会带**同一个 seq**到达，
     * 用「序号必须递增」去重，避免同一次翻页被算成两下、把去重闸门顶掉。
     */
    private static void arm(long seq, int dir) {
        if (seq <= sArmSeq) return;
        sArmSeq = seq;
        sDir = dir;
        long now = SystemClock.elapsedRealtime();
        // 快速连翻自动减段：相邻两次点击间隔 < 900ms 视为连翻，段数减半
        // （15→7）。单帧刷得更快、EPD 队列不堆积，减少连翻时的丢帧；正常
        // 阅读（间隔 > 900ms）恢复满段保画质。
        long dt = sPrevArmAt > 0L ? now - sPrevArmAt : Long.MAX_VALUE;
        sPrevArmAt = now;
        int full = Math.max(2, Math.min(60, frameCount));
        sCurBands = (dt < 900L) ? Math.max(4, (full + 1) / 2) : full;
        sArmedAt = now;
        // 按应用统计「连续几次点击有第 2 帧」：连续 >=2 次才认定为双帧应用
        // （legado 稳定双帧→很快达标；微信读书偶发一次第2帧不会达标，不会被误跳过）。
        int cnt = sGenIdx.get();
        String act = sCurActivity;
        if (act != null) {
            int st = sStreak.containsKey(act) ? sStreak.get(act) : 0;
            st = (cnt >= 2) ? st + 1 : 0;
            sStreak.put(act, st);
            sExpect2 = st >= 2;
        } else {
            sExpect2 = false;
        }
        sGenIdx.set(0);
        sAnimDone = false;
        log("SS arm#" + sArmSeq + " dir=" + sDir + " bands=" + sCurBands
                + (sExpect2 ? " 双帧" : " 单帧") + (dt < 900L ? "(连翻)" : ""));
    }

    /** 重新读一遍 Launcher 的配置（波形 / 帧数 / 开关变了要能热生效）。 */
    private static void refreshPrefs() {
        try {
            MainHook.reloadPrefs();
            int nw = MainHook.getWave();
            int nf = MainHook.getFrameCount();
            boolean ne = MainHook.isEnabled();
            boolean ns = MainHook.isServerMode();
            Set<String> nt = MainHook.getTargets();
            boolean tChanged = nt != null && !nt.isEmpty() && !nt.equals(targets);
            if (nw == wave && nf == frameCount && ne == enabled && ns == serverWrite
                    && !tChanged) {
                return;
            }
            wave = nw;
            frameCount = nf;
            enabled = ne;
            serverWrite = ns;
            if (tChanged) targets = nt;
            log("SS prefs -> enable=" + enabled
                    + " serverWrite=" + serverWrite
                    + " wave=" + EpdPageTurnHook.waveName(wave)
                    + " bands=" + frameCount
                    + " targets=" + (targets == null ? 0 : targets.size()));
        } catch (Throwable t) {
            log("SS refreshPrefs failed: " + t);
        }
    }

    private static void startPoller() {
        if (sPollerRun) return;
        sPollerRun = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                android.content.Context ctx = systemContext();
                if (ctx == null) {
                    log("SS poller: no system context");
                    return;
                }
                android.content.ContentResolver cr = ctx.getContentResolver();
                // 兜底通道：YotaPaper 后台被冻结时 Provider 会整段查不到
                // （实测连续 15 秒 "Failed to find provider info"），臂信号全丢，
                // 于是「连着几次翻页都没动画」。广播只经过 AMS，不依赖 Launcher 进程。
                try {
                    android.content.IntentFilter f = new android.content.IntentFilter(
                            TurnBus.ACTION_ARM);
                    ctx.registerReceiver(new android.content.BroadcastReceiver() {
                        @Override
                        public void onReceive(android.content.Context c,
                                              android.content.Intent in) {
                            try {
                                if (in == null) return;
                                long seq = in.getLongExtra("seq", 0L);
                                if (seq <= 0L) return;
                                arm(seq, in.getIntExtra("dir", sDir));
                            } catch (Throwable ignored) {
                            }
                        }
                    }, f);
                    log("SS arm receiver registered " + TurnBus.ACTION_ARM);
                } catch (Throwable t) {
                    log("SS arm receiver FAILED: " + t);
                }
                log("SS poller started " + TurnBus.uri());
                long lastSeq = -1L;
                long lastPrefCheck = 0L;
                while (true) {
                    // 按时间而不是按循环次数刷新配置。
                    //   serverWrite=false（黑条带档）时循环每 250ms 才醒一次，
                    //   旧的「每 12 次循环刷新」等于 3 秒才看一眼配置 ——
                    //   从黑条带切回高画质后，头几下点击 system_server 还以为
                    //   自己不该写参数，动画整个不来。改成固定 250ms 刷新，
                    //   两个方向的切换延迟都压到 1/4 秒内。
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastPrefCheck >= 250L) {
                        lastPrefCheck = now;
                        refreshPrefs();
                    }
                    try {
                        // 两种波形都读臂信号：黑条带档不写参数，但切回高画质时
                        // 不能让轮询线程还停在 250ms 的长睡眠上，否则切回后的
                        // 第一下点击system_server 还没醒，动画就丢了。
                        android.os.Bundle b = cr.call(TurnBus.uri(), "state", null, null);
                        if (b != null) {
                            long seq = b.getLong("seq", 0L);
                            int dir = b.getInt("dir", sDir);
                            if (seq > 0L && seq != lastSeq) {
                                lastSeq = seq;
                                arm(seq, dir);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        // 臂信号在 ACTION_DOWN 就发出，比抬手翻页早几百毫秒，
                        // 20ms 够用（这是跑在 system_server 里的线程）。
                        Thread.sleep(20L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "epd-arm-poll");
        t.setDaemon(true);
        t.start();
    }

    private static android.content.Context systemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getDeclaredMethod("currentActivityThread").invoke(null);
            return (android.content.Context) at
                    .getDeclaredMethod("getSystemContext").invoke(thread);
        } catch (Throwable t) {
            log("SS systemContext failed: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------ hooks

    private static XC_MethodHook genIdHook(final ClassLoader cl) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    String win = nameOf(p.args.length > 0 ? p.args[0] : null);
                    PENDING_WIN.set(win);
                    // 波形在控制中心/设置里改了之后，轮询线程最多要 250ms 才发现。
                    // 这里按 100ms 限频再补一次读：切回高画质后的第一下翻页就能生效，
                    // 不用等下一次定时器醒来。refreshPrefs 只做一次文件 mtime 比较，
                    // 放在 EPD 热路径上也只有 10 次/秒。
                    long now = SystemClock.elapsedRealtime();
                    if (now - sLastPrefsAt >= 100L) {
                        sLastPrefsAt = now;
                        refreshPrefs();
                    }
                    if (!enabled || !serverWrite || !matches(win)) return;
                    // 记下当前窗口；换了应用就重置判定（重新学习），
                    // 避免「刚从一个双帧应用切到单帧应用」时第一下被误跳过。
                    if (!win.equals(sCurActivity)) {
                        sCurActivity = win;
                        sExpect2 = false;
                    }
                    // 混合波形：这个窗口如果是黑条带，参数由目标 App 进程自己写，
                    // system_server 绝不能插手 —— 它会把 updateType=4 改回 3，
                    // 或再补一遍动画帧，导致黑条带不出 / 播两遍。
                    if (MainHook.waveForWindow(win) != EpdPageTurnHook.WAVE_FOLLOW) {
                        if (debugLog) {
                            log("SS skip: 黑条带窗口由 App 进程自己写 win=" + win);
                        }
                        return;
                    }
                    // 只在「刚点过屏幕」的 1.5s 窗口内工作；一次点击只播一次由
                    // sGenIdx（第 2 帧）与 armFresh 共同保证，不再用一次性 arm 闸门。
                    if (!armFresh()) {
                        if (debugLog) {
                            log("SS skip: 非点击帧 win=" + win + " fresh=false");
                        }
                        return;
                    }
                    // ---- 唯一路径：gen 阶段只给「本次点击的第二帧」挂动画。
                    // 第 1 帧不建 params → generateEpdUpdateId 返回 -1 → HAL 走
                    // waveform=2 普通快刷（快速刷掉旧内容/中间态，不占动画队列）。
                    // 第 2 帧（承载新页内容）建 params 并挂动画 → 拿到有效 id →
                    // 进入 getEpdUpdateParameters，由那里最终补动画。
                    // 为什么必须这么分：框架只有 params 带 mCustomAnimation（动画）
                    // 时才走 id 参数路径；不带动画的帧直接返回 -1、进不了 getParams，
                    // 动画永远到不了 HAL。
                    int idx = sGenIdx.incrementAndGet();
                    Object params = p.args.length > 1 ? p.args[1] : null;
                    // idx=1：旧内容残留帧，双帧应用 skip、单帧应用挂动画。
                    // idx=2：主内容帧，始终挂动画。
                    // idx>=3：Legado 快速连翻时的额外帧，**不 skip** 也挂动画，
                    //   因为快速连翻时第 3+ 帧才可能是真正的新页内容，之前会被
                    //   无条件 skip 导致「翻页没动画」。代价是可能多播一次动画，
                    //   但比完全丢动画好得多。
                    if (idx == 1 && sExpect2) {
                        // 双帧应用的第 1 帧：确认是旧内容残留，skip。
                        if (params == null) {
                            params = newParams(cl);
                            if (params == null) return;
                            p.args[1] = params;
                        }
                        try {
                            setField(params, "mIsRegionalUpdate", Boolean.TRUE);
                        } catch (Throwable ignored) {
                        }
                        setSkipField(params);
                        if (debugLog) {
                            log("SS gen: idx=1 skip(旧残留) win=" + win);
                        }
                    } else {
                        // idx=2 或 idx>=3（额外帧），或 idx=1 但单帧应用 → 都挂动画
                        if (params == null) {
                            params = newParams(cl);
                            if (params == null) return;
                            p.args[1] = params;
                        }
                        try {
                            setField(params, "mIsRegionalUpdate", Boolean.TRUE);
                        } catch (Throwable ignored) {
                        }
                        inject(params, win, "generateEpdUpdateId#idx" + idx, sDir);
                        if (debugLog) {
                            String tag = (idx == 2) ? "2nd 挂动画"
                                    : (idx == 1 ? "1st(单帧) 挂动画"
                                            : idx + "额外帧 挂动画");
                            log("SS gen: idx=" + idx + " " + tag + " win=" + win);
                        }
                    }
                } catch (Throwable t) {
                    log("SS genId error: " + t);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Object id = p.getResult();
                    String win = PENDING_WIN.get();
                    PENDING_WIN.remove();
                    // 每个 id 都登记窗口名。getEpdUpdateParameters 只拿到一个 id，
                    // 靠这张表反查窗口，才能按窗口各自的波形决定要不要补帧。
                    // 被抑制的重复帧返回 -1，不要登记进窗口表。
                    if (id instanceof Integer && (Integer) id >= 0 && win != null) {
                        ID_WIN.put((Integer) id, win);
                        // 只淘汰最老的 id，**不要整表 clear**：getEpdUpdateParameters
                        // 拿到的 id 可能比 gen 刚生成的旧一拍，整表清空会让它查不到
                        // 窗口名，补帧被静默跳过（又是一次随机丢动画）。
                        if (ID_WIN.size() > 1024) {
                            Integer oldest = null;
                            for (Integer k : ID_WIN.keySet()) {
                                if (oldest == null || k < oldest) oldest = k;
                            }
                            if (oldest != null) ID_WIN.remove(oldest);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        };
    }

    private static XC_MethodHook getParamsHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Object result = p.getResult();
                    if (result == null) return;
                    if (!enabled || !serverWrite) return;
                    int id = (p.args.length > 0 && p.args[0] instanceof Integer)
                            ? (Integer) p.args[0] : -1;
                    String win = ID_WIN.get(id);
                    if (win == null || !matches(win)) return;

                    // 混合波形下不能只看「是不是目标窗口」。黑条带窗口的参数由
                    // 目标 App 进程自己写（updateType=4 + dither=1）；system_server
                    // 在这里插手会把波形改回 3、或者再补一遍动画帧，正好把用户
                    // 反馈的「A 软件设高画质、B 软件设黑条带，A 也变黑条带」复现出来。
                    if (MainHook.waveForWindow(win) != EpdPageTurnHook.WAVE_FOLLOW) {
                        if (debugLog) {
                            log("SS skip get: 黑条带窗口由 App 进程自己写 win=" + win);
                        }
                        return;
                    }

                    // 关键修复：这里**不能**要求 id 等于 genId 刚生成的那个 id。
                    // App 一次翻页会连发两轮 setEpdParamsForDrawing，system_server
                    // 里 gen 与 HAL 读参数在不同线程；实测 MISS 帧里 gen 注入的是
                    // 1325/1331/1336，HAL 却只读 1326/1332/1337（后生成的那一帧），
                    // 旧写法 `id != sArmedId` 就静默 return，HAL 打出
                    // animation:disabled —— 这正是「翻几次就随机丢一次动画」的根因。
                    //
                    // 改成按**点击序号 sArmSeq**去重：只要这次点击还没在离 HAL
                    // 最近的这一站补过帧，无论 HAL 拿的是哪个 id 都补上。
                    if (!armFresh()) return;

                    // 被 gen 标记为 skip 的帧（双帧应用的第 1 帧）：整帧不刷、不挂动画。
                    // 由第 2 帧（新页内容）带动画来刷屏。单帧应用不会走到这里
                    // （gen 里单帧是直接挂动画的）。
                    if (getSkipField(result)) {
                        setField(result, "mCustomAnimation", null);
                        if (debugLog) {
                            log("SS get: skip 第1帧(双帧) id=" + id);
                        }
                        return;
                    }

                    // 承载新页内容的动画帧：系统会清掉 mCustomAnimation，这里补回来。
                    // 不再用 sAnimDone 限制一次点击只挂一次动画 —— Legado 快速连翻时
                    // 同一 arm 内会发 3-4 次 EPD 更新，idx=2 可能是中间态、idx>=3 才
                    // 是真正的新页内容。限制只挂一次会导致「翻了但没动画」。现在让
                    // armFresh 窗口内的所有内容帧都挂动画，可能多播一次但不再丢。
                    if (getIntField(result, "mEpdViewUpdateType") == 3) {
                        setField(result, "mEpdViewDithering", 2);
                    }
                    inject(result, win, "getEpdUpdateParameters#" + id, sDir);
                    if (debugLog) {
                        log("SS get: 动画帧 id=" + id);
                    }
                } catch (Throwable t) {
                    log("SS getParams error: " + t);
                }
            }
        };
    }

    // ------------------------------------------------------------------ 注入

    /** 把动画数组写进参数（唯一注入入口，gen 与 getParams 都调用它）。 */
    private static void inject(Object params, String win, String where, int dir) {
        try {
            // 按窗口查各自的波形，不能用全局 wave（不同 App 可以一高一黑）。
            final int w = MainHook.waveForWindow(win);
            int[] frames = frames(dir);
            setField(params, "mCustomAnimation", frames);
            if (w != EpdPageTurnHook.WAVE_FOLLOW) {
                if (w == EpdPageTurnHook.WAVE_DU) {
                    setField(params, "mEpdViewUpdateType", 0);
                } else {
                    // 与原版一致：updateType 4 + dithering 1。
                    // 黑条带档实际走进程内注入，这里只在切换瞬间兜底。
                    setField(params, "mEpdViewUpdateType", 4);
                    setField(params, "mEpdViewDithering", 1);
                }
            }
            setField(params, "mIsRegionalUpdate", Boolean.TRUE);
            injected++;
            log("SS inject#" + injected + " via=" + where
                    + " win=" + win
                    + " bands=" + (frames.length / 4)
                    + " head={" + frames[0] + "," + frames[1] + ","
                    + frames[2] + "," + frames[3] + "}"
                    + " dir=" + dir
                    + " type=" + getIntField(params, "mEpdViewUpdateType")
                    + " dither=" + getIntField(params, "mEpdViewDithering")
                    + " wave=" + EpdPageTurnHook.waveName(w)
                    + " t=" + SystemClock.elapsedRealtime());
        } catch (Throwable t) {
            log("SS inject failed: " + t);
        }
    }

    /** 条带；格式 {left, top, right, bottom}，面板 720x1280。段数随连翻动态变化。 */
    private static int[] frames(int dir) {
        final int w = 720;
        final int h = 1280;
        int bands;
        if (sCurBands >= 2) {
            // 本击由 arm() 根据是否连翻算好的段数（连翻减半、正常满段）。
            bands = Math.min(60, sCurBands);
        } else {
            bands = style == EpdPageTurnHook.STYLE_CUSTOM
                    ? Math.max(2, Math.min(60, frameCount)) : 15;
        }
        boolean rightToLeft = dir == SwipeTracker.DIR_LEFT;
        int[] f = new int[bands * 4];
        for (int i = 0; i < bands; i++) {
            int idx = rightToLeft ? (bands - 1 - i) : i;
            int l = EpdPageTurnHook.bandEdge(idx, w, bands);
            int r = EpdPageTurnHook.bandEdge(idx + 1, w, bands);
            f[i * 4] = l;
            f[i * 4 + 1] = 0;
            f[i * 4 + 2] = r;
            f[i * 4 + 3] = h;
        }
        return f;
    }

    // ------------------------------------------------------------------ 工具

    private static Object newParams(ClassLoader cl) {
        try {
            Class<?> c = Class.forName(PARAMS, false, cl);
            return c.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            log("SS newParams failed: " + t);
            return null;
        }
    }

    private static void setField(Object obj, String name, Object value) throws Exception {
        Field f = findField(obj.getClass(), name);
        f.setAccessible(true);
        f.set(obj, value);
    }

    /** 置 mSkip=true：让 HAL 整帧不刷（用于正常节奏下「跳过第 1 帧的整页刷新」）。 */
    private static void setSkipField(Object params) {
        try {
            Field f = findField(params.getClass(), "mSkip");
            f.setAccessible(true);
            Class<?> t = f.getType();
            if (t == boolean.class || t == Boolean.class) {
                f.setBoolean(params, true);
            } else if (t == int.class || t == Integer.class) {
                f.setInt(params, 1);
            } else {
                f.set(params, Boolean.TRUE);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 读 mSkip；读不到当 false。 */
    private static boolean getSkipField(Object params) {
        try {
            Field f = findField(params.getClass(), "mSkip");
            f.setAccessible(true);
            Class<?> t = f.getType();
            if (t == boolean.class || t == Boolean.class) return f.getBoolean(params);
            if (t == int.class || t == Integer.class) return f.getInt(params) != 0;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 清除 mSkip（单帧应用：这一帧要正常刷并挂动画，不能跳过）。 */
    private static void clearSkipField(Object params) {
        try {
            Field f = findField(params.getClass(), "mSkip");
            f.setAccessible(true);
            Class<?> t = f.getType();
            if (t == boolean.class || t == Boolean.class) {
                f.setBoolean(params, false);
            } else if (t == int.class || t == Integer.class) {
                f.setInt(params, 0);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 读 int 字段；读不到返回 -1（用于判断系统给的 updateType）。 */
    private static int getIntField(Object obj, String name) {
        try {
            Field f = findField(obj.getClass(), name);
            f.setAccessible(true);
            return f.getInt(obj);
        } catch (Throwable t) {
            return -1;
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

    private static boolean matches(String win) {
        if (win == null || targets == null || targets.isEmpty()) return false;
        for (String cls : targets) {
            if (cls != null && cls.length() > 0 && win.contains(cls)) return true;
        }
        return false;
    }

    private static String nameOf(Object win) {
        if (win == null) return null;
        String s = String.valueOf(win);
        return s.length() > 160 ? s.substring(0, 160) : s;
    }

    private static void hook(ClassLoader cl, String cls, String method, Object[] typesAndCallback) {
        try {
            XposedHelpers.findAndHookMethod(cls, cl, method, typesAndCallback);
            log("SS hook ok " + cls + "." + method);
        } catch (Throwable t) {
            log("SS hook FAILED " + cls + "." + method + " : " + t);
        }
    }

    private static void log(String msg) {
        if (debugLog) {
            android.util.Log.i(TAG, msg);
        }
    }
}
