package com.yota.launcher.epd

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class EpdEntry(
    var activity: String,
    var mode: Int,
    var contrast: Int,
    var sharping: Int,
    var blackStretch: Int,
    var whiteStretch: Int,
    var bright: Int
) {
    val modeLabel: String get() = when (mode) {
        1 -> "流畅"
        2 -> "动画"
        else -> "高画质"
    }

    /** 占位符行不匹配任何真实窗口标题，也不应出现在本地保存列表中。 */
    val isNeutralized: Boolean
        get() = activity.startsWith("__") && activity.endsWith("__")

    fun toJson(): JSONObject = JSONObject().apply {
        put("activity", activity)
        put("mode", mode)
        put("contrast", contrast)
        put("sharping", sharping)
        put("black_stretch", blackStretch)
        put("white_stretch", whiteStretch)
        put("bright", bright)
    }

    companion object {
        fun fromJson(o: JSONObject): EpdEntry = EpdEntry(
            activity = o.optString("activity", ""),
            mode = o.optInt("mode", 0),
            contrast = o.optInt("contrast", 10),
            sharping = o.optInt("sharping", 2),
            blackStretch = o.optInt("black_stretch", 70),
            whiteStretch = o.optInt("white_stretch", 255),
            bright = o.optInt("bright", 0)
        )
    }
}

/**
 * Launcher 本地持久化的系统刷新参数表（数据源）。
 *
 * 数据保存在 SharedPreferences（yota_paper_epd_params）中，启动时由
 * LauncherActivity 调用 [applySavedToSystem] 把全部已保存条目写入系统
 * Provider（com.baoliyota.epdparams.paramsprovider），从而自动生效。
 *
 * 系统 Provider 的 delete 固定返回 0，所以「删除」在本地移除的同时，把
 * 系统 Provider 中对应行的 activity 改写为 [DELETED_PLACEHOLDER]，使系统
 * 不再匹配该窗口。
 */
object EpdParamsStore {

    private const val TAG = "EpdParamsStore"
    private const val PREFS = "yota_paper_epd_params"
    private const val KEY_ITEMS = "items"

    const val KEY_ANIM_ENABLE = "anim_enable"
    const val KEY_ANIM_WINDOW_MS = "anim_window_ms"
    const val KEY_ANIM_FRAME_COUNT = "anim_frame_count"
    // 「从未单独设过的条目」的默认波形。不再提供 adb 入口修改它。
    const val KEY_ANIM_WAVE = "anim_wave"
    /**
     * 「进入动画时，系统 Provider 里这个 activity 的档位」快照，JSON：{activity: mode}。
     *
     * 用来区分两种完全不同的 Provider 变化：
     *   - 档位和快照一样 → 是我们自己 ensureDisplayModeRow 写的/系统无操作，动画保留；
     *   - 档位和快照不一样 → 用户在系统自带刷新管理里改过这个 app 的档位，
     *     那就是「不要动画了」，直接把本地条目从 mode=2 换成 Provider 的档位。
     * 没有这份快照就只能在「永久保留动画」和「Provider 一同步就吃掉动画」之间二选一。
     */
    private const val KEY_ANIM_BASE = "anim_base_mode"

    /**
     * 每个动画条目各自的刷新波形，JSON：{ 完整 activity 串: wave }。
     *
     * 早先波形只有一个全局 [KEY_ANIM_WAVE]：给微信读书选「黑条带」会把「阅读」
     * 一起改掉。更糟的是 [syncAnimProviderRows] 按那个全局值把**所有**动画条目的
     * Provider 模式行一起软删除，于是阅读连高画质波形都丢了 —— 用户看到的正是
     * 「我只改了一个 App，另一个 App 也变成黑条带」。波形必须按条目独立保存，
     * Provider 行也必须逐条按自己的波形处理。
     *
     * 全局 [KEY_ANIM_WAVE] 只保留为「从未单独设过的条目」的默认值，
     * 不再提供 adb 入口修改它（否则会把所有 App 的波形一起带跑），也不再决定
     * 任何一个已有条目的实际波形。
     */
    private const val KEY_ANIM_WAVE_MAP = "anim_wave_map"

    /**
     * 注入的翻页动画风格。数值必须与 Xposed 侧
     * EpdPageTurnHook.STYLE_* 保持一致。
     */
    const val ANIM_STYLE_STOCK_STRIP = 0   // 原厂细条：15 段 48px 竖条，左右推开
    const val ANIM_STYLE_STOCK_BAND = 1    // 原厂宽条：15 段 85px 横带，上下推开
    const val ANIM_STYLE_CUSTOM = 2        // 自定义：条带数 = 动画帧数

    /**
     * 刷新档位。数值必须与 Xposed 侧 EpdPageTurnHook.WAVE_* 保持一致。
     *
     * EpdUpdateParams 没有独立的 waveform 字段，波形只由 updateType 决定：
     * 0 → DU（HAL waveform 8，无黑闪）；4 → FULL/GC（整屏刷黑）。
     */
    const val ANIM_WAVE_DU = 0             // type 0：DU，无黑闪（默认）
    const val ANIM_WAVE_FULL_NO_DITHER = 1 // type 4：FULL，会刷黑
    const val ANIM_WAVE_FULL_DITHER = 2    // type 4 + dither 1：FULL + 灰度抖动
    /** 不写 updateType / dithering，只补动画数组，让系统按当前显示模式选波形。 */
    const val ANIM_WAVE_FOLLOW = 3
    // 供 Xposed 模块读的调试日志开关，只该由用户手工写入；
    // 曾经在 saveAnimParams 里写死 true，导致开过一次动画模式就永久打开且无法关闭
    const val KEY_DEBUG_LOG = "debug_log"

    const val AUTHORITY = "com.baoliyota.epdparams.paramsprovider"
    const val URI = "content://$AUTHORITY/params"
    const val DELETED_PLACEHOLDER = "__deleted_entry__"

    fun load(context: Context): List<EpdEntry> {
        ensurePrefsReadable(context)
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(text)
            val out = ArrayList<EpdEntry>()
            for (i in 0 until arr.length()) {
                val e = EpdEntry.fromJson(arr.getJSONObject(i))
                if (e.activity.isNotBlank() && !e.isNeutralized) out.add(e)
            }
            out.distinctBy { it.activity }
        }.getOrElse { ex ->
            Log.w(TAG, "load failed: ${ex.message}")
            emptyList()
        }
    }

    fun save(context: Context, items: List<EpdEntry>) {
        val arr = JSONArray()
        items.filter { it.activity.isNotBlank() && !it.isNeutralized }
            .distinctBy { it.activity }
            .forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, arr.toString())
            .commit()
        ensurePrefsReadable(context)
    }

    fun ensurePrefsReadable(context: Context) {
        runCatching {
            val dataDir = context.filesDir?.parentFile ?: return
            dataDir.setExecutable(true, false)
            dataDir.setReadable(true, false)
            val spDir = File(dataDir, "shared_prefs")
            spDir.setExecutable(true, false)
            spDir.setReadable(true, false)
            val f = File(spDir, "$PREFS.xml")
            f.setReadable(true, false)
            // 关键：chmod 只改权限、**不改 mtime**，而 XSharedPreferences 是
            // 靠 mtime 判断要不要重读的。如果 system_server 恰好在「写盘 0600 →
            // chmod 0664」之间读过一次（读到空 map 并记住当时的 mtime），之后
            // chmod 不改 mtime，它就再也不会重读 —— targets 永久为空，表现为
            // 「重启后/偶尔翻页完全没动画」。所以 chmod 之后把 mtime 往前推一下，
            // 强制后续 reload 重读这份现在已经可读的文件。
            f.setLastModified(System.currentTimeMillis())
        }.onFailure { ex -> Log.w(TAG, "ensurePrefsReadable failed: ${ex.message}") }
    }

    // ------------------------------------------------------------ 动画档位快照

    /** 读「进入动画时 Provider 的档位」快照；没有记录返回 null。 */
    private fun readBases(context: Context): MutableMap<String, Int> {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ANIM_BASE, "") ?: ""
        val out = HashMap<String, Int>()
        if (text.isBlank()) return out
        runCatching {
            val o = JSONObject(text)
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                out[k] = o.optInt(k, -1)
            }
        }
        return out
    }

    /**
     * 记录/清除快照。mode 传 null 表示清除。
     *
     * 只在「打开动画」和「关闭动画」两个时机写，同步流程里不写 ——
     * 否则每次 sync 都把快照刷成当前值，用户改档位这件事就永远发现不了。
     */
    private fun writeBase(context: Context, activity: String, mode: Int?) {
        val bases = readBases(context)
        if (mode == null) {
            if (bases.remove(activity) == null) return
        } else {
            if (bases[activity] == mode) return
            bases[activity] = mode
        }
        val o = JSONObject()
        for ((k, v) in bases) o.put(k, v)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ANIM_BASE, o.toString())
            .commit()
        ensurePrefsReadable(context)
    }

    /** 供调试/日志用：当前记录的 Provider 档位快照。 */
    fun animBaseMode(context: Context, activity: String): Int? = readBases(context)[activity]

    fun loadAnimWindowMs(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_ANIM_WINDOW_MS, 1200)

    fun loadAnimFrameCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_ANIM_FRAME_COUNT, 15)

    /**
     * 刷新档位，与 Xposed 侧 EpdPageTurnHook.WAVE_* 一致：
     * 0 = DU 无黑闪（默认）1 = FULL（会刷黑）2 = FULL+灰度抖动
     */
    fun loadAnimWave(context: Context): Int = normalizeWave(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_ANIM_WAVE, ANIM_WAVE_FOLLOW)
    )

    /** 「无黑闪（DU）」档已下线，历史值（0/1）统一迁到 FOLLOW。 */
    private fun normalizeWave(v: Int): Int =
        if (v == ANIM_WAVE_FULL_DITHER || v == ANIM_WAVE_FULL_NO_DITHER) {
            ANIM_WAVE_FULL_DITHER
        } else {
            ANIM_WAVE_FOLLOW
        }

    // ------------------------------------------------------------ 逐条目波形

    /** 读「每个 activity 各自的波形」表；键与 items 里的 activity 串完全一致。 */
    private fun readWaveMap(context: Context): MutableMap<String, Int> {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ANIM_WAVE_MAP, "") ?: ""
        val out = HashMap<String, Int>()
        if (text.isBlank()) return out
        runCatching {
            val o = JSONObject(text)
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                out[k] = normalizeWave(o.optInt(k, ANIM_WAVE_FOLLOW))
            }
        }
        return out
    }

    /**
     * 某个条目当前该用哪个波形。
     * 老版本没有逐条目记录时回退到全局默认，保证升级后不会突然全变黑条带。
     */
    fun animWaveFor(context: Context, activity: String): Int {
        val v = readWaveMap(context)[activity]
        return if (v != null) normalizeWave(v) else loadAnimWave(context)
    }

    /**
     * 只改这一个条目的波形。全局键同时写成镜像（新条目的默认值 + adb 调试入口），
     * 但**不会**碰其它条目的记录，也不会动它们的 Provider 行。
     * Provider 行由调用方统一走 [syncAnimProviderRows]。
     */
    fun setAnimWave(context: Context, activity: String, wave: Int) {
        val w = normalizeWave(wave)
        val map = readWaveMap(context)
        if (activity.isBlank() || map[activity] == w) {
            // 也要保证动画总开关是开的：控制中心只点波形时不能让动画起不来。
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ANIM_ENABLE, true).commit()
            ensurePrefsReadable(context)
            return
        }
        map[activity] = w
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ANIM_WAVE_MAP, o.toString())
            // 注意：**不写**全局 KEY_ANIM_WAVE。它只作为「从未单独设过的条目」的
            // 默认值，一旦被上一次选择污染，其它 App 就会跟着变（正是这次的 bug）。
            .putBoolean(KEY_ANIM_ENABLE, true)
            .commit()
        ensurePrefsReadable(context)
        Log.i(TAG, "setAnimWave: $activity wave=$w (entries=${map.size})")
    }

    /** 编辑条目时改了 activity 串：把波形记录搬到新键，旧键删掉。 */
    private fun moveAnimWave(context: Context, oldActivity: String, newActivity: String) {
        if (oldActivity == newActivity || newActivity.isBlank()) return
        val map = readWaveMap(context)
        val old = map.remove(oldActivity) ?: return
        if (!map.containsKey(newActivity)) map[newActivity] = old
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ANIM_WAVE_MAP, o.toString()).commit()
        ensurePrefsReadable(context)
    }

    fun animWaveLabel(wave: Int): String = when (wave) {
        ANIM_WAVE_FULL_DITHER, ANIM_WAVE_FULL_NO_DITHER -> "黑闪翻页"
        else -> "平滑翻页"
    }

    /** 保留 style/wave/server 形参只为兼容既有调用点，它们不再写入配置表。 */
    @Suppress("UNUSED_PARAMETER")
    fun saveAnimParams(
        context: Context,
        windowMs: Int,
        frameCount: Int,
        style: Int = ANIM_STYLE_CUSTOM,
        wave: Int = ANIM_WAVE_FOLLOW,
        server: Boolean = true
    ) {
        // anim_style：Xposed 侧是 final STYLE_CUSTOM，从不读 prefs；
        // anim_server：Xposed 侧按窗口波形动态计算，从不读 prefs；
        // 两者都是历史死键，不再产生。波形按条目存在 anim_wave_map，
        // 全局 anim_wave 也不从这里写。
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ANIM_WINDOW_MS, windowMs)
            .putInt(KEY_ANIM_FRAME_COUNT, frameCount)
            .putBoolean(KEY_ANIM_ENABLE, true)
            .commit()
        ensurePrefsReadable(context)
    }

    /**
     * 统一的「把某个 activity 设为某个档位」入口：本地条目 + 系统 Provider +
     * 动画配置三件事一次做完。控制中心卡片和 EPD 参数设置页**都**必须走这里。
     *
     * 为什么必须收敛：设置页在 mode=2 时会额外调 [saveAnimParams]（写
     * anim_enable / 帧数 / 注入窗口 / 波形），控制中心那张卡片只写了条目本身。
     * 于是「在控制中心选动画 → 重启」拿不到那套动画参数，而同样操作在设置页里
     * 做却正常 —— 用户看到的就是「控制中心选了没用，设置页选了就行」。
     * 两条 UI 各写一半的配置，是这个 bug 反复出现的根源。
     */
    fun setMode(
        context: Context,
        entry: EpdEntry,
        oldActivity: String? = null,
        animWindowMs: Int = loadAnimWindowMs(context),
        animFrameCount: Int = loadAnimFrameCount(context),
        animStyle: Int = ANIM_STYLE_CUSTOM,
        // null = 保留这个条目原来的波形（控制中心卡片就是这种情况）。
        // 早先默认取全局 anim_wave，于是给微信读书切黑条带后，卡片里再保存一次
        // 「阅读」就会把它一起改成黑条带。
        animWave: Int? = null
    ): Boolean {
        if (entry.activity.isBlank() || entry.isNeutralized) return false

        // 编辑时改了 activity 串：把这个条目的波形记录一起搬过去，别丢配置。
        if (oldActivity != null && oldActivity != entry.activity) {
            moveAnimWave(context, oldActivity, entry.activity)
        }
        val wave = normalizeWave(animWave ?: animWaveFor(context, entry.activity))

        // 1. 系统 Provider：动画条目按**波形**决定要不要留模式行，这一条决定黑条带
        //    能不能出动画，见 syncAnimProviderRows 的注释。
        val ok = if (entry.mode == 2) {
            if (wave == ANIM_WAVE_FOLLOW) {
                // 高画质动画：强制 Provider 行是 mode=0（高画质）。
                // 不能用 ensureDisplayModeRow「保留原行」——若原来是流畅档，
                // 动画会跑错波形，表现为「第一次切高画质动画没动画」。
                forceHighQualityRow(context, entry.activity)
            } else {
                // 黑条带（updateType 4）：必须把这个 activity 的模式行拿掉。
                // 留着行 → EpdUpdateParamsManager.optimizeApp 会按行把 updateType
                // 强制改成 3，我们写的 4 被吃掉，整屏重刷动画就不出现。
                softDeleteInProvider(context, entry.activity)
                writeBase(context, entry.activity, null)
            }
            true
        } else {
            if (oldActivity != null && oldActivity != entry.activity) {
                softDeleteInProvider(context, oldActivity)
                writeBase(context, oldActivity, null)
            }
            applyToProvider(context, entry)
        }

        // 2. 本地条目表（Xposed 从这里读 mode=2 决定注入谁）。
        val list = load(context).toMutableList()
        if (oldActivity != null) list.removeAll { it.activity == oldActivity }
        list.removeAll { it.activity == entry.activity }
        list.add(entry)
        save(context, list)

        // 3. 动画参数：开启动画时写一套完整配置，其中 anim_enable 必须被显式置 true。
        //    早先只有设置页写这一段，所以只要这个开关曾经被关过（调试、旧版本），
        //    控制中心那条路就永远是「选了动画但一动不动」。
        if (entry.mode == 2) {
            // 波形记在**这个条目**名下，别动其它条目的记录。
            setAnimWave(context, entry.activity, wave)
            saveAnimParams(
                context,
                animWindowMs,
                animFrameCount,
                animStyle,
                wave,
                // 谁来写参数已经统一由 MainHook 按波形决定，这个布尔值不再参与路由，
                // 留 true 只是为了配置表里不出现误导性取值。
                true
            )
            // 每条按自己的波形刷新 Provider 行：高画质要有行、黑条带不能有行。
            // 不能再用一个全局波形一刀切，否则改一个 App 会把其它 App 的行删掉。
            syncAnimProviderRows(context)
        }
        // 两条 UI（控制中心卡片 / 设置页）都走这里，打一行便于从 logcat 直接确认
        // 「谁写了什么」—— 之前两张表各写一半的 bug 就是没有这行才查了这么久。
        Log.i(TAG, "setMode: ${entry.activity} mode=${entry.mode}"
                + " wave=${animWaveFor(context, entry.activity)}"
                + " old=$oldActivity result=$ok")
        return ok
    }

    /**
     * 按当前波形，统一刷新所有动画条目在系统 Provider 里的「模式行」。
     *
     * 两张表必须保持这个对应关系，否则黑条带永远不出动画：
     *
     *   高画质（WAVE_FOLLOW）：Provider 里**要有**一行 mode=0。
     *       系统据此给 updateType=3 + dithering=2，波形由系统定；
     *       我们只在 getEpdUpdateParameters 之后把 dithering 和动画帧补回去。
     *
     *   黑条带（updateType=4）：Provider 里**必须没有**这一行。
     *       EpdUpdateParamsManager.optimizeApp 会遍历系统表，按 activity 匹配窗口，
     *       mode != 1 时无条件 `setEpdViewUpdateType(3)`。只要行还在，我们写的 4
     *       就会被它改回 3，整屏重刷的动画帧随之被丢掉 —— 这就是「黑条带一直
     *       不生效」的原因。原版（未改动副本）对动画条目用的正是软删除，
     *       所以它那版的 4 能活下来。
     */
    fun syncAnimProviderRows(context: Context) {
        val animEntries = load(context).filter { it.mode == 2 }
        var hi = 0
        var full = 0
        for (e in animEntries) {
            // 逐条按自己的波形处理：早先这里用的是一个全局 wave，
            // 给某个 App 选黑条带会把所有动画条目的模式行一起删掉。
            if (animWaveFor(context, e.activity) == ANIM_WAVE_FOLLOW) {
                ensureDisplayModeRow(context, e.activity)
                hi++
            } else {
                softDeleteInProvider(context, e.activity)
                writeBase(context, e.activity, null)
                full++
            }
        }
        Log.i(TAG, "syncAnimProviderRows: hi=$hi full=$full entries=${animEntries.size}")
    }

    /**
     * 动画条目（mode=2）的「模式行守卫」。
     *
     * mode=2 的真实含义是「给这个 activity 补一段翻页动画」，它和刷新档位
     * （高画质 / 流畅）是两件互相独立的事：动画只写本地 prefs 供 Xposed 读取，
     * 波形档位仍然由系统 Provider 决定。
     *
     * 早先为了阻止动画条目写进 Provider，这里直接 softDeleteInProvider 把行
     * 占位符化删除，结果把用户原本设好的「高画质」一起抹掉了 —— 表现为
     * 「只要选了动画效果，默认就不是高画质」。现在改为：
     *
     *   - Provider 里已有该 activity 的模式行 → 原样保留（绝不覆盖）；
     *   - 没有 → 补一行默认高画质（mode=0），让「跟随当前显示模式」有波形可跟。
     *
     * 这样「高画质 + 动画」可以同时成立。
     */
    fun ensureDisplayModeRow(context: Context, activity: String): Boolean {
        if (activity.isBlank() || (activity.startsWith("__") && activity.endsWith("__"))) {
            return false
        }
        return runCatching {
            val resolver = context.contentResolver
            val uri = Uri.parse(URI)
            val existingMode = resolver.query(
                uri, arrayOf("mode"), "activity=?", arrayOf(activity), null
            )?.use { c ->
                if (c.moveToFirst() && c.getColumnIndex("mode") >= 0) c.getInt(0) else -1
            } ?: -1
            if (existingMode >= 0) {
                // 原样保留，并把「此刻的档位」记成基准：之后用户在系统刷新管理里
                // 把它改成别的档位，mergeFromProvider 就能看出「有人动过」。
                writeBase(context, activity, existingMode)
                Log.i(TAG, "ensureDisplayModeRow: keep mode=$existingMode for $activity")
                return true
            }
            val seeded = EpdEntry(
                activity = activity,
                mode = 0,           // 高画质
                contrast = 10,
                sharping = 2,
                blackStretch = 70,
                whiteStretch = 255,
                bright = 0
            )
            val ok = resolver.insert(uri, contentValues(seeded)) != null
            if (ok) writeBase(context, activity, 0)
            Log.i(TAG, "ensureDisplayModeRow: seeded mode=0 for $activity -> $ok")
            ok
        }.getOrElse { ex ->
            Log.w(TAG, "ensureDisplayModeRow failed: ${ex.message}")
            false
        }
    }

    /**
     * 新增或按 activity 更新系统 Provider 中的一行。
     * 动画模式（mode=2）不写波形参数进 Provider，但也**不再删除**已有的模式行，
     * 见 [ensureDisplayModeRow]。
     */
    /**
     * 切「高画质动画」时，把 Provider 里该 activity 的档位行**强制改成高画质
     * （mode=0）**，而不是像 [ensureDisplayModeRow] 那样原样保留。
     *
     * 这是修「切到高画质动画第一次没动画、必须先切黑条带再切回来才生效」的根因：
     * 高画质动画要求窗口走灰度（type=3）波形，而该波形来自 Provider 里的
     * mode=0 行。如果这个 App 之前是「流畅」(mode=1) 或别的档位，[ensureDisplayModeRow]
     * 会保留旧档位 → 动画就跑错波形；而黑条带会先软删这行，再切回高画质时
     * 重新种成 mode=0，于是「先切黑条带再切高画质」才正常。这里直接强制成 0。
     */
    private fun forceHighQualityRow(context: Context, activity: String): Boolean {
        if (activity.isBlank() || (activity.startsWith("__") && activity.endsWith("__"))) {
            return false
        }
        return runCatching {
            val resolver = context.contentResolver
            val uri = Uri.parse(URI)
            val existing = resolver.query(
                uri, arrayOf("mode"), "activity=?", arrayOf(activity), null
            )?.use { c ->
                if (c.moveToFirst() && c.getColumnIndex("mode") >= 0) c.getInt(0) else -1
            } ?: -1
            val seed = EpdEntry(
                activity = activity, mode = 0,
                contrast = 10, sharping = 2, blackStretch = 70, whiteStretch = 255, bright = 0
            )
            val ok = when {
                existing < 0 -> resolver.insert(uri, contentValues(seed)) != null
                existing == 0 -> true
                else -> resolver.update(uri, contentValues(seed), "activity=?", arrayOf(activity)) > 0
            }
            if (ok) writeBase(context, activity, 0)
            Log.i(TAG, "forceHighQualityRow: $activity existing=$existing -> $ok")
            ok
        }.getOrElse { ex ->
            Log.w(TAG, "forceHighQualityRow failed: ${ex.message}")
            false
        }
    }

    fun applyToProvider(context: Context, e: EpdEntry): Boolean {
        if (e.activity.isBlank() || e.isNeutralized) return false
        if (e.mode == 2) {
            return ensureDisplayModeRow(context, e.activity)
        }
        // 本地已改成普通档位（高画质 / 流畅）= 动画关闭，快照一起清掉。
        writeBase(context, e.activity, null)
        return runCatching {
            val resolver = context.contentResolver
            val uri = Uri.parse(URI)
            val values = contentValues(e)
            val exists = resolver.query(uri, arrayOf("activity"), "activity=?", arrayOf(e.activity), null)
                ?.use { it.moveToFirst() } == true
            if (exists) {
                resolver.update(uri, values, "activity=?", arrayOf(e.activity)) > 0
            } else {
                resolver.insert(uri, values) != null
            }
        }.getOrElse { ex ->
            Log.w(TAG, "applyToProvider failed: ${ex.message}")
            false
        }
    }

    /** 按旧 activity 定位更新（编辑时可能改动 activity）。 */
    fun updateInProvider(context: Context, oldActivity: String, e: EpdEntry): Boolean {
        if (e.activity.isBlank() || e.isNeutralized) return false
        if (e.mode == 2) {
            return ensureDisplayModeRow(context, e.activity)
        }
        writeBase(context, e.activity, null)
        if (oldActivity != e.activity) writeBase(context, oldActivity, null)
        return runCatching {
            context.contentResolver.update(
                Uri.parse(URI), contentValues(e), "activity=?", arrayOf(oldActivity)
            ) > 0
        }.getOrElse { ex ->
            Log.w(TAG, "updateInProvider failed: ${ex.message}")
            false
        }
    }

    /** 把系统 Provider 中的某行改写为占位符（等效删除，系统不支持硬删除）。 */
    fun softDeleteInProvider(context: Context, activity: String): Boolean {
        return runCatching {
            val values = ContentValues().apply { put("activity", DELETED_PLACEHOLDER) }
            context.contentResolver.update(
                Uri.parse(URI), values, "activity=?", arrayOf(activity)
            ) > 0
        }.getOrElse { ex ->
            Log.w(TAG, "softDeleteInProvider failed: ${ex.message}")
            false
        }
    }

    /** 查询系统 Provider 当前的全部条目。 */
    fun queryProvider(context: Context): List<EpdEntry> {
        val out = ArrayList<EpdEntry>()
        runCatching {
            context.contentResolver.query(Uri.parse(URI), null, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val e = readEntry(c)
                    if (e.activity.isNotBlank()) out.add(e)
                }
            }
        }.onFailure { ex -> Log.w(TAG, "queryProvider failed: ${ex.message}") }
        return out
    }

    /**
     * 把系统 Provider 当前内容合并进本地持久化：
     * - 忽略占位符行；
     * - 忽略 skipActivity（Launcher 自身行，需保持中和以保护翻页动画）；
     * - 系统 Provider 中已有的行覆盖本地同 activity 行（用户手动在系统设置里改的
     *   值会被保留），本地独有的行继续保留（启动时还会再应用回去）；
     * - **动画条目（mode=2）按 [KEY_ANIM_BASE] 快照判断**：
     *   Provider 档位和快照一致 → 是我们在 `ensureDisplayModeRow` 里补的行或系统
     *   无操作，动画保留（「高画质 + 动画」因此可以共存）；
     *   档位和快照不一致 → 用户在系统自带刷新管理里改了这个 app 的档位，
     *   那就是「不要动画」，本地条目换成 Provider 的档位，动画立即下线。
     */
    fun mergeFromProvider(context: Context, skipActivity: String? = null) {
        val providerEntries = queryProvider(context).filter { e ->
            !e.isNeutralized && (skipActivity == null || e.activity != skipActivity)
        }
        if (providerEntries.isEmpty()) return
        val merged = load(context).associateBy { it.activity }.toMutableMap()
        var kept = 0
        var animOff = 0
        for (e in providerEntries) {
            val cur = merged[e.activity]
            if (cur != null && cur.mode == 2) {
                val base = animBaseMode(context, e.activity)
                if (base == null) {
                    // 老数据（这一版之前装的动画条目）没有快照：把当前档位当作基准，
                    // 动画保留。下一次用户再改档位就能被下面的分支抓到。
                    writeBase(context, e.activity, e.mode)
                    kept++
                    continue
                }
                if (e.mode == base) {
                    kept++
                    continue
                }
                // 档位被改过 = 用户在系统刷新管理里选了别的模式（高画质 / 流畅）。
                // 档位以 Provider 为准，动画同时关掉。
                writeBase(context, e.activity, null)
                merged[e.activity] = e
                animOff++
                Log.i(TAG, "mergeFromProvider: anim off for ${e.activity}"
                        + " (provider mode ${base} -> ${e.mode})")
                continue
            }
            merged[e.activity] = e
        }
        save(context, merged.values.toList())
        Log.i(TAG, "mergeFromProvider: merged ${providerEntries.size} provider rows"
                + " into local store (kept $kept anim, turned off $animOff)")
    }

    /** 启动时把全部已保存条目同步到系统 Provider（动画条目按波形处理模式行）。 */
    fun applySavedToSystem(context: Context, skipActivity: String? = null) {
        val items = load(context)
        var ok = 0
        for (e in items) {
            if (skipActivity != null && e.activity == skipActivity) continue
            if (e.mode == 2) {
                // 每条按自己的波形：高画质要留模式行（系统据此选灰度波形）；
                // 黑条带必须把这行拿掉，否则 optimizeApp 会把 updateType 改回 3。
                val done = if (animWaveFor(context, e.activity) == ANIM_WAVE_FOLLOW) {
                    ensureDisplayModeRow(context, e.activity)
                } else {
                    softDeleteInProvider(context, e.activity)
                    writeBase(context, e.activity, null)
                    true
                }
                if (done) ok++
            } else {
                if (applyToProvider(context, e) ) ok++
            }
        }
        Log.i(TAG, "applySavedToSystem: ${ok}/${items.size} applied (per-entry wave)")
    }

    /** 合并系统 Provider 的手动改动，再应用本地保存的全部条目（总开关打开时调用）。 */
    fun syncAndApply(context: Context, skipActivity: String? = null) {
        mergeFromProvider(context, skipActivity)
        applySavedToSystem(context, skipActivity)
    }

    private fun readEntry(c: Cursor): EpdEntry {
        fun int(col: String): Int = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getInt(it) } ?: 0
        fun str(col: String): String = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getString(it) } ?: ""
        return EpdEntry(
            activity = str("activity"),
            mode = int("mode"),
            contrast = int("contrast"),
            sharping = int("sharping"),
            blackStretch = int("black_stretch"),
            whiteStretch = int("white_stretch"),
            bright = int("bright")
        )
    }

    private fun contentValues(e: EpdEntry): ContentValues = ContentValues().apply {
        put("activity", e.activity)
        put("mode", e.mode)
        put("contrast", e.contrast)
        put("sharping", e.sharping)
        put("black_stretch", e.blackStretch)
        put("white_stretch", e.whiteStretch)
        put("bright", e.bright)
    }
}
