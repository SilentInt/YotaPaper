package com.yota.launcher.xposed;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * 一次点击 → system_server 的带外通道。
 *
 * 为什么需要：注入最终落在 system_server 的 `generateEpdUpdateId` 里（HAL 一定吃得到），
 * 但那里看不到用户点了屏幕的哪一边，也就不知道「上一页 / 下一页」。
 * 目标 App 进程里的 hook 能看到点击，于是把「方向 + 时间戳」写进本 Provider
 * （跑在 YotaPaper 自己的进程里），system_server 侧每 40ms 读一次。
 *
 * 为什么不直接在系统进程里做 IPC：`generateEpdUpdateId` / `getEpdUpdateParameters`
 * 都在 HAL 参数热路径上，任何活都只能读内存；所以读动作放在自己的轮询线程里。
 */
public class TurnBus extends ContentProvider {

    public static final String AUTHORITY = "com.yota.launcher.turn";
    /** 不依赖本进程 Provider 的带外通道，system_server 直接注册接收器。 */
    public static final String ACTION_ARM = "com.yota.launcher.action.TURN_ARM";
    private static final Uri URI = Uri.parse("content://" + AUTHORITY);

    /** Provider 进程内的状态：最后一次方向与时间戳。 */
    private static volatile long sSeq;
    private static volatile int sDir = SwipeTracker.DIR_LEFT;

    /** 发送侧去重，避免同一次点击重复写。 */
    private static volatile long sLastPub;

    // ------------------------------------------------------------ provider 侧

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("arm".equals(method)) {
            try {
                sDir = Integer.parseInt(arg);
            } catch (Throwable ignored) {
            }
            // seq 由发送侧统一生成：Provider 轮询和广播兜底两条通道必须带
            // **同一个序号**，否则 system_server 会把它当成两次点击，去重失效。
            sSeq = extras != null
                    ? extras.getLong("seq", SystemClock.elapsedRealtime())
                    : SystemClock.elapsedRealtime();
            Bundle out = new Bundle();
            out.putLong("seq", sSeq);
            out.putInt("dir", sDir);
            return out;
        }
        Bundle out = new Bundle();
        out.putLong("seq", sSeq);
        out.putInt("dir", sDir);
        return out;
    }

    @Override
    public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }

    // ------------------------------------------------------------ 客户端侧

    /**
     * 目标 App 进程里调用：告诉系统「刚点了一下，方向是 dir」。
     * 走一条后台线程，绝不阻塞输入分发。
     *
     * 两条通道一起发，序号相同：
     *   1) 广播 → system_server 里注册的接收器（AMS 一定活着，最可靠）；
     *   2) Provider → 原来的轮询通道（保留兼容）。
     * 只靠 Provider 有个实测到的坑：YotaPaper 自己在后台被冻结时，
     * system_server 会连续打出 "Failed to find provider info for
     * com.yota.launcher.turn"，臂信号整段丢失，表现就是「连着几次翻页
     * 都没有动画」。广播不依赖 Launcher 进程，能把这段补上。
     */
    static void publishArm(final int dir) {
        final long now = SystemClock.elapsedRealtime();
        if (now - sLastPub < 60L) return;      // 同一次点击的多余事件
        sLastPub = now;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Context ctx = ctx();
                    if (ctx == null) return;
                    Intent i = new Intent(ACTION_ARM);
                    i.putExtra("seq", now);
                    i.putExtra("dir", dir);
                    ctx.sendBroadcast(i);
                } catch (Throwable ignored) {
                }
                try {
                    Context ctx = ctx();
                    if (ctx == null) return;
                    Bundle b = new Bundle();
                    b.putInt("dir", dir);
                    b.putLong("seq", now);
                    ctx.getContentResolver().call(URI, "arm", String.valueOf(dir), b);
                } catch (Throwable ignored) {
                }
            }
        }, "epd-arm");
        t.setDaemon(true);
        t.start();
    }

    private static Context ctx() {
        try {
            Class<?> h = Class.forName("android.app.AndroidAppHelper");
            Object app = h.getDeclaredMethod("currentApplication").invoke(null);
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getDeclaredMethod("currentActivityThread").invoke(null);
            Object app = at.getDeclaredMethod("getApplication").invoke(thread);
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------ 订阅侧（system_server）

    static Uri uri() {
        return URI;
    }
}
