package com.yota.launcher.data

import android.content.pm.ResolveInfo

/**
 * 应用条目：label 由 AppRepository 取好并写入持久缓存后一并带出，
 * 网格拿到就能直接画，不必再走 loadLabel 的跨进程调用。
 *
 * @param key 「包名/类名」，作为格子的稳定标识，用于判断 holder 有无换绑。
 */
data class AppEntry(
    val info: ResolveInfo,
    val label: String,
    val key: String,
    val packageName: String,
    val className: String
)
