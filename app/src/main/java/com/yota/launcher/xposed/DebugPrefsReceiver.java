package com.yota.launcher.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 本地调试开关：用 adb 广播直接改 Xposed 模块读的那份配置，
 * 免得每次都要在界面上点。
 *
 *   adb shell am broadcast -n com.yota.launcher/.xposed.DebugPrefsReceiver \
 *       --ei debug 1 --ei frames 15 --ei window_ms 1200 \
 *       --es wave_for 'ComponentInfo{pkg/cls}' --ei wave 3
 *
 * 只写 SharedPreferences（yota_paper_epd_params），Xposed 侧按 mtime 热加载。
 * 已下线的调试开关：style / 全局 wave / always_arm / dup_skip；
 * anim_style / anim_always_arm / anim_dup_skip / anim_server 也不再写入配置表。
 */
public class DebugPrefsReceiver extends BroadcastReceiver {

    private static final String PREFS = "yota_paper_epd_params";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();

        String activity = intent.getStringExtra("activity");
        if (activity != null && activity.length() > 0) {
            // 条目必须写成 ComponentInfo{包名/类名}：MainHook.classOf() 是按
            // '/' 和 '}' 切的，只给类名会被原样当成类名，永远匹配不上窗口。
            ed.putString("items", markAnimMode(sp.getString("items", ""), activity));
        }

        // -1 = 不改。全局 anim_wave 只是「从未单独设过的条目」的默认值，
        // 不再提供 adb 入口改它，否则会像早期那样把所有 App 的波形一起带跑。
        int wave = intent.getIntExtra("wave", -1);
        String waveFor = intent.getStringExtra("wave_for");
        int windowMs = intent.getIntExtra("window_ms", -1);
        if (windowMs > 0) ed.putInt("anim_window_ms", windowMs);
        int frames = intent.getIntExtra("frames", -1);
        if (frames > 0) ed.putInt("anim_frame_count", frames);
        int enable = intent.getIntExtra("enable", -1);
        if (enable >= 0) ed.putBoolean("anim_enable", enable != 0);
        int debug = intent.getIntExtra("debug", -1);
        if (debug >= 0) ed.putBoolean("debug_log", debug != 0);
        // 旧版本遗留的死键：新代码不再读写，任何一次 adb 广播顺手清掉。
        ed.remove("anim_style");
        ed.remove("anim_always_arm");
        ed.remove("anim_dup_skip");
        ed.remove("anim_server");
        // 用 commit()（同步写盘）而不是 apply()（异步）：apply() 是「写临时文件
        // 再 rename」，紧接着的 chmod 会作用在**旧文件**上，最终落盘的新文件
        // 仍是 0600/0660 —— system_server（uid system）就读不到，XSharedPreferences
        // 一直返回空/默认值。commit() 同步完成写入+rename，chmod 才能命中最终文件。
        ed.commit();

        // 逐条目波形调试：--es wave_for 'ComponentInfo{pkg/cls}' --ei wave 3
        // 只改这一个条目，不会波及其它 App。
        if (waveFor != null && waveFor.length() > 0 && wave >= 0) {
            com.yota.launcher.epd.EpdParamsStore.INSTANCE.setAnimWave(context, waveFor, wave);
            com.yota.launcher.epd.EpdParamsStore.INSTANCE.syncAnimProviderRows(context);
        }
        makeWorldReadable(context);

        StringBuilder sb = new StringBuilder("DebugPrefs:");
        sb.append(" debug=").append(sp.getBoolean("debug_log", false));
        sb.append(" enable=").append(sp.getBoolean("anim_enable", true));
        sb.append(" frames=").append(sp.getInt("anim_frame_count", 15));
        sb.append(" windowMs=").append(sp.getInt("anim_window_ms", 1200));
        sb.append(" items=").append(countAnimMode(sp.getString("items", "")));
        android.util.Log.i("YotaPageTurn", sb.toString());
    }

    /**
     * Xposed 的 XSharedPreferences 是在**别人的进程**里读这一份 XML 的。
     * SharedPreferences 每次 apply() 都是「写临时文件再 rename」，新文件的权限是
     * 0600/0660，别的 App 就再也读不到了 —— 表现为模块在所有目标 App 里静默失效。
     * 所以每次写完必须把文件重新放开到 world-readable。
     */
    private static void makeWorldReadable(Context context) {
        try {
            java.io.File f = new java.io.File(
                    context.getApplicationInfo().dataDir + "/shared_prefs/" + PREFS + ".xml");
            if (!f.exists()) return;
            f.setReadable(true, false);
            f.setExecutable(false, false);
            // chmod 不改 mtime，而 XSharedPreferences 靠 mtime 判断是否重读。
            // chmod 后把 mtime 往前推，强制 system_server 重读这份可读文件
            // （否则它在「写盘 0600 → chmod 0664」之间读到空 map 后会永久卡死）。
            f.setLastModified(System.currentTimeMillis());
            android.util.Log.i("YotaPageTurn", "prefs mode fixed: " + f.getAbsolutePath()
                    + " r=" + f.canRead() + " len=" + f.length());
        } catch (Throwable t) {
            android.util.Log.w("YotaPageTurn", "prefs chmod failed: " + t);
        }
    }

    /** 把指定 Activity 的 mode 置为 2（动画），其它条目原样保留。 */
    private static String markAnimMode(String itemsJson, String activityClass) {
        JSONArray out = new JSONArray();
        boolean hit = false;
        try {
            if (itemsJson != null && itemsJson.trim().length() > 0) {
                JSONArray arr = new JSONArray(itemsJson);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String a = o.optString("activity", "");
                    if (a.contains(activityClass)) {
                        o.put("mode", 2);
                        hit = true;
                    }
                    out.put(o);
                }
            }
            if (!hit) {
                JSONObject o = new JSONObject();
                o.put("activity", "ComponentInfo{" + activityClass + "}");
                o.put("mode", 2);
                out.put(o);
            }
        } catch (Throwable ignored) {
        }
        return out.toString();
    }
    private static int countAnimMode(String itemsJson) {
        int n = 0;
        try {
            JSONArray arr = new JSONArray(itemsJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && o.optInt("mode", 0) == 2) n++;
            }
        } catch (Throwable ignored) {
        }
        return n;
    }
}
