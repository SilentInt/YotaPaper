package com.yota.launcher.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.yota.launcher.data.AppEntry
import com.yota.launcher.data.LauncherConfig
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

object IconLoader {

    data class IconPackInfo(val packageName: String, val label: String)

    private const val LINE_ART_CACHE_DIR = "eink_lineart_cache"
    private const val RAW_CACHE_DIR = "eink_lineart_raw"
    private const val RAW_HEADER_BYTES = 8
    private const val ICON_PX = 176

    private val cache = LruCache<String, Drawable>(256)
    private val cacheLock = Any()

    private fun cacheGet(key: String): Drawable? = synchronized(cacheLock) { cache.get(key) }
    private fun cachePut(key: String, drawable: Drawable) = synchronized(cacheLock) { cache.put(key, drawable) }
    private fun cacheEvictAll() = synchronized(cacheLock) { cache.evictAll() }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var iconPack: String = ""

    @Volatile
    private var autoLineIcons: Boolean = false

    // 线条图标的目标边长（像素）。
    // 合并上游 0.7 后没有「图标大小」设置了：显示尺寸由 EInkLauncherView 按格子反算
    // （iconPxFor），位图按 176px 生成、由 ImageView 缩放到目标尺寸 —— 这也是上游
    // 0.7 自己的做法（它连 iconPx 字段都没有，一律走 LineIconRenderer 的 176 默认值）。
    // 仍保留这个字段，是因为缓存 key 和磁盘文件名都要带上它，方便以后真要改成
    // 「按格子精确生成」时不用重写缓存逻辑。
    @Volatile
    private var iconPx: Int = ICON_PX

    private val mainHandler = Handler(Looper.getMainLooper())
    private val decodeExecutor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "icon-loader")
    }

    // Icon-pack parsing cache (single pack selection).
    private var loadedPack: String? = null
    private var loadedPackResources: Resources? = null
    private var loadedPackIconMap: Map<String, String> = emptyMap()

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun configure(config: LauncherConfig) {
        val autoLineChanged = config.autoLineIcons != autoLineIcons
        val changed = config.iconPack != iconPack || autoLineChanged
        iconPack = config.iconPack
        autoLineIcons = config.autoLineIcons
        iconPx = ICON_PX
        if (changed) {
            cacheEvictAll()
            if (autoLineChanged) clearLineArtDiskCache()
        }
    }

    // 尺寸必须进 key：否则改完图标大小仍命中旧的 176px 位图
    private fun cacheKey(entry: AppEntry): String =
        "${entry.packageName}|$iconPack|$autoLineIcons|$iconPx"

    fun load(context: Context, entry: AppEntry): Drawable? {
        val key = cacheKey(entry)
        cacheGet(key)?.let { return it }

        var icon: Drawable? = null

        // 1. 最高优先级：如果选择了第三方图标包，优先从中加载
        if (iconPack.isNotEmpty()) {
            icon = loadFromIconPack(context.packageManager, entry, iconPack)
        }

        // 2. 第二优先级：如果图标包中未找到（或未选择图标包），且开启了自动绘制，则生成线条图标
        if (icon == null && autoLineIcons) {
            icon = loadLineIcon(context, entry)
        }

        // 3. 兜底逻辑：如果都没有，则加载系统原始图标
        if (icon == null) {
            icon = runCatching { entry.info.loadIcon(context.packageManager) }.getOrNull()
        }

        if (icon != null) cachePut(key, icon)
        return icon
    }

    /**
     * 异步加载图标：内存缓存命中时回调立即同步返回；未命中则在后台线程解码/生成，
     * 完成后切回主线程回调。冷启动首帧不再为图标解码阻塞。
     */
    fun loadAsync(context: Context, entry: AppEntry, callback: (Drawable?) -> Unit) {
        val key = cacheKey(entry)
        cacheGet(key)?.let {
            callback(it)
            return
        }
        decodeExecutor.execute {
            val drawable = load(context, entry)
            mainHandler.post { callback(drawable) }
        }
    }

    /** Detect installed icon packs through common launcher theme actions. */
    fun findIconPacks(pm: PackageManager): List<IconPackInfo> {
        val packs = LinkedHashMap<String, String>()

        fun collect(intent: Intent) {
            val resolved = runCatching { pm.queryIntentActivities(intent, 0) }.getOrNull() ?: return
            for (ri in resolved) {
                val pkg = ri.activityInfo.packageName
                val label = runCatching {
                    val app = pm.getApplicationInfo(pkg, 0)
                    app.loadLabel(pm).toString()
                }.getOrDefault(pkg)
                if (!packs.containsKey(pkg)) packs[pkg] = label
            }
        }

        collect(Intent("org.adw.launcher.icons.ACTION_PICK_ICON"))
        collect(Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME"))
        collect(Intent(Intent.ACTION_MAIN).addCategory("com.novalauncher.THEME"))
        collect(Intent(Intent.ACTION_MAIN).addCategory("com.teslacoilsw.launcher.THEME"))
        collect(Intent(Intent.ACTION_MAIN).addCategory("com.fede.launcher.THEME"))

        return packs.map { IconPackInfo(it.key, it.value) }
    }

    // ---- line icons -----------------------------------------------------

    private fun loadLineIcon(context: Context, entry: AppEntry): Drawable? {
        val packageName = entry.packageName
        val className = entry.className
        val safeName = "${packageName}_${className}".replace("[^a-zA-Z0-9_.]".toRegex(), "_")
        // 文件名带边长：换图标大小后不会读到另一种尺寸的旧图
        val rawFile = rawCacheDir(context)?.let { File(it, "${safeName}_${iconPx}px.raw") }

        // 1. 最快的磁盘缓存：裸 ARGB 像素（无 PNG 解码开销）。
        if (rawFile != null && rawFile.exists()) {
            val bitmap = readRawBitmap(rawFile)
            if (bitmap != null) return BitmapDrawable(context.resources, bitmap)
        }

        // 2. 旧版 PNG 缓存迁移：旧文件是写死边长生成的，只有和当前尺寸对得上才沿用。
        val legacyPng = diskCacheDir(context)?.let { File(it, "$safeName.png") }
        if (legacyPng != null && legacyPng.exists()) {
            val bitmap = runCatching {
                android.graphics.BitmapFactory.decodeFile(legacyPng.absolutePath)
            }.getOrNull()
            if (bitmap != null && bitmap.width == iconPx && rawFile != null) {
                runCatching { saveRawBitmap(bitmap, rawFile) }
                // 转存后删掉 png，否则两份缓存永久并存白占空间
                runCatching { legacyPng.delete() }
                return BitmapDrawable(context.resources, bitmap)
            }
            // 尺寸对不上（用户改过图标大小）就丢弃，往下走重新生成
            runCatching { legacyPng.delete() }
        }

        // 3. 从原图标生成。
        val original = runCatching { entry.info.loadIcon(context.packageManager) }.getOrNull()
        val generated = if (original != null) {
            LineIconRenderer.fromDrawable(original, sizePx = iconPx, resources = context.resources)
        } else {
            // 取不到图标时退化成画首字：名字直接用 AppEntry 带出的 label
            LineIconRenderer.draw(entry.label, sizePx = iconPx, resources = context.resources)
        }

        if (generated is BitmapDrawable && rawFile != null) {
            runCatching {
                rawFile.parentFile?.mkdirs()
                saveRawBitmap(generated.bitmap, rawFile)
            }
        }
        return generated
    }

    private fun saveRawBitmap(bitmap: Bitmap, file: File) {
        val pixelBytes = ByteBuffer.allocate(bitmap.width * bitmap.height * 4)
        bitmap.copyPixelsToBuffer(pixelBytes)
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeInt(bitmap.width)
            out.writeInt(bitmap.height)
            out.write(pixelBytes.array())
        }
    }

    private fun readRawBitmap(file: File): Bitmap? = runCatching {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            val w = input.readInt()
            val h = input.readInt()
            if (w <= 0 || h <= 0 || w * h > 1024 * 1024) return@use null
            val bytes = ByteArray(w * h * 4)
            input.readFully(bytes)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            }
        }
    }.getOrNull()

    private fun diskCacheDir(context: Context): File? =
        appContext?.cacheDir?.let { File(it, LINE_ART_CACHE_DIR) }
            ?: context.cacheDir?.let { File(it, LINE_ART_CACHE_DIR) }

    private fun rawCacheDir(context: Context): File? =
        appContext?.cacheDir?.let { File(it, RAW_CACHE_DIR) }
            ?: context.cacheDir?.let { File(it, RAW_CACHE_DIR) }

    private fun clearLineArtDiskCache() {
        val base = appContext?.cacheDir ?: return
        runCatching { base.listFiles()?.firstOrNull { it.name == LINE_ART_CACHE_DIR }?.deleteRecursively() }
        runCatching { base.listFiles()?.firstOrNull { it.name == RAW_CACHE_DIR }?.deleteRecursively() }
    }

    /** 包变更时调用：清掉该包的内存与磁盘线条图标缓存，下次重新生成。 */
    fun invalidatePackage(packageName: String) {
        cacheEvictAll()
        val base = appContext?.cacheDir ?: return
        val prefix = "${packageName}_"
        runCatching {
            File(base, RAW_CACHE_DIR).listFiles()
                ?.filter { it.name.startsWith(prefix) }
                ?.forEach { it.delete() }
        }
        runCatching {
            File(base, LINE_ART_CACHE_DIR).listFiles()
                ?.filter { it.name.startsWith(prefix) }
                ?.forEach { it.delete() }
        }
    }

    // ---- icon pack loading ---------------------------------------------

    private fun loadFromIconPack(pm: PackageManager, entry: AppEntry, pack: String): Drawable? {
        val resources = ensureIconPack(pm, pack) ?: return null
        val packageName = entry.packageName
        val activityName = entry.className
        val simpleName = activityName.substringAfterLast('.')

        // 1. Exact match through the standard appfilter.xml mapping.
        loadedPackIconMap["ComponentInfo{$packageName/$activityName}"]?.let { drawableName ->
            val resId = resources.getIdentifier(drawableName, "drawable", pack)
            if (resId != 0) {
                val drawable = loadDrawable(resources, pack, resId)
                if (drawable != null) return drawable
            }
        }

        // 2. Fall back to common naming conventions.
        val candidates = listOf(
            "$packageName/$activityName",
            "$packageName/$simpleName",
            packageName + "_" + activityName,
            packageName + "_" + simpleName,
            activityName,
            simpleName
        )
        for (candidate in candidates) {
            val resId = resources.getIdentifier(candidate, "drawable", pack)
            if (resId != 0) {
                val drawable = loadDrawable(resources, pack, resId)
                if (drawable != null) return drawable
            }
        }
        return null
    }

    /** Resources.getDrawable(id, theme) 是 API 21 才有的双参版本，4.2 用单参版本。 */
    @Suppress("DEPRECATION")
    private fun loadDrawable(resources: Resources, pack: String, resId: Int): Drawable? {
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                resources.getDrawable(resId, null)
            } else {
                resources.getDrawable(resId)
            }
        }.getOrNull()
    }

    private fun ensureIconPack(pm: PackageManager, pack: String): Resources? {
        if (loadedPack == pack && loadedPackResources != null) return loadedPackResources

        loadedPackResources = null
        loadedPackIconMap = emptyMap()
        loadedPack = null

        val resources = runCatching { pm.getResourcesForApplication(pack) }.getOrNull() ?: return null
        loadedPackResources = resources
        loadedPack = pack
        loadedPackIconMap = parseAppFilter(resources, pack)
        return resources
    }

    private fun parseAppFilter(resources: Resources, pack: String): Map<String, String> {
        val map = HashMap<String, String>()
        val appFilterId = resources.getIdentifier("appfilter", "xml", pack)
        val backupFilterId = resources.getIdentifier("icon_pack", "xml", pack)
        val finalId = if (appFilterId > 0) appFilterId else backupFilterId
        if (finalId <= 0) return map

        return runCatching {
            val xpp = resources.getXml(finalId)
            var eventType = xpp.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && xpp.name == "item") {
                    val component = xpp.getAttributeValue(null, "component")
                    val drawable = xpp.getAttributeValue(null, "drawable")
                    if (component != null && drawable != null) {
                        map[component] = drawable
                    }
                }
                eventType = xpp.next()
            }
            map
        }.getOrDefault(map)
    }
}