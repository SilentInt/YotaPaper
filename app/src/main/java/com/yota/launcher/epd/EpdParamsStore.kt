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
            .apply()
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
            File(spDir, "$PREFS.xml").setReadable(true, false)
        }.onFailure { ex -> Log.w(TAG, "ensurePrefsReadable failed: ${ex.message}") }
    }

    fun loadAnimWindowMs(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_ANIM_WINDOW_MS, 1200)

    fun loadAnimFrameCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_ANIM_FRAME_COUNT, 15)

    fun saveAnimParams(context: Context, windowMs: Int, frameCount: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ANIM_WINDOW_MS, windowMs)
            .putInt(KEY_ANIM_FRAME_COUNT, frameCount)
            .putBoolean(KEY_ANIM_ENABLE, true)
            .apply()
        ensurePrefsReadable(context)
    }

    /**
     * 新增或按 activity 更新系统 Provider 中的一行。
     * 动画模式（mode=2）不写入系统 Provider：先占位符化逻辑删除，
     * 配置只保存在本地供 Xposed 模块读取。
     */
    fun applyToProvider(context: Context, e: EpdEntry): Boolean {
        if (e.activity.isBlank() || e.isNeutralized) return false
        if (e.mode == 2) {
            return softDeleteInProvider(context, e.activity)
        }
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
            return softDeleteInProvider(context, oldActivity)
        }
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
     *   值会被保留），本地独有的行继续保留（启动时还会再应用回去）。
     */
    fun mergeFromProvider(context: Context, skipActivity: String? = null) {
        val providerEntries = queryProvider(context).filter { e ->
            !e.isNeutralized && (skipActivity == null || e.activity != skipActivity)
        }
        if (providerEntries.isEmpty()) return
        val merged = load(context).associateBy { it.activity }.toMutableMap()
        for (e in providerEntries) merged[e.activity] = e
        save(context, merged.values.toList())
        Log.i(TAG, "mergeFromProvider: merged ${providerEntries.size} provider rows into local store")
    }

    /** 启动时把全部已保存条目同步到系统 Provider（动画条目只做占位符化删除，不写入）。 */
    fun applySavedToSystem(context: Context, skipActivity: String? = null) {
        val items = load(context)
        var ok = 0
        for (e in items) {
            if (skipActivity != null && e.activity == skipActivity) continue
            if (e.mode == 2) {
                if (softDeleteInProvider(context, e.activity)) ok++
            } else {
                if (applyToProvider(context, e)) ok++
            }
        }
        Log.i(TAG, "applySavedToSystem: ${ok}/${items.size} applied")
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
