package com.yota.launcher.data

data class LauncherConfig(
    val columns: Int = 4,
    val rows: Int = 5,
    val homeColumns: Int = 4,
    val homeRows: Int = 3,
    val showHomeDividers: Boolean = true,   // home grid only
    val showAppDividers: Boolean = true,    // app drawer grid only
    val recentWindowMs: Long = 30 * 60_000L, // recent tasks: 0 = all, else time window
    val recentCancelBackToApp: Boolean = true, // recent tasks cancel action
    val iconPack: String = "",              // empty = system default icons
    val autoLineIcons: Boolean = true,     // auto-draw line-style icons（默认开）
    val refreshMode: Int = 0,               // 0=高画质, 1=流畅, 2=自适应 (Epd.setUpdateMode)
    val pageAnimation: Int = 1,             // 0=关,1=左右翻页,2=水平展开,3=水平闭合,4=上下翻页,5=垂直展开,6=垂直闭合
    val screenOnAnimation: Boolean = true,  // 开屏动画开关（默认开）
    val screenOnAnimationStyle: Int = 2,    // 开屏动画样式（默认水平展开）
    val screenOffAnimation: Boolean = true, // 息屏动画开关（默认开）
    val screenOffAnimationStyle: Int = 3,   // 息屏动画样式（默认水平闭合）
    val autoApplyEpdParams: Boolean = true, // EPD 参数自动同步/应用总开关（默认开）
    val iconSize: Int = 44,                 // 图标大小（单位 dp，默认 44）
    val rootClear: Boolean = false,          // 是否使用 Root 清理后台（默认关）
    val controlCenterEnabled: Boolean = true,   // 控制中心总开关
    val controlCenterAnimation: Boolean = true, // 控制中心翻页动画
    val controlCenterVibration: Boolean = true,  // 控制中心触摸震动
    val controlCenterEpd: Boolean = true         // 控制中心EPD刷新模块
)