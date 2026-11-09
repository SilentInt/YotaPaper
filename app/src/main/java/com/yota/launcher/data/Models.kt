package com.yota.launcher.data

data class LauncherConfig(
    // 0 = 自动（按屏幕大小与行列数计算），>0 = 用户手动指定
    val columns: Int = 0,
    val rows: Int = 0,
    val homeColumns: Int = 0,
    val homeRows: Int = 0,
    val showHomeDividers: Boolean = true,   // home grid only
    val showAppDividers: Boolean = true,    // app drawer grid only
    val homeTopStyle: Int = 0,              // 主页顶部风格：0=经典居中, 1=左右分栏
    val homeTopIconStyle: Int = 1,          // 顶部状态行图标风格：0=线条, 1=实心（默认实心）
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
    // 0.7 起图标大小交给 GridSizing 按格子反算，不再有 iconSize 字段；
    // rootClear 默认值跟随上游改成 false（更安全的默认），老用户的偏好不受影响
    val rootClear: Boolean = false,          // 是否使用 Root 清理后台（默认关）
    val controlCenterEnabled: Boolean = true,   // 控制中心总开关
    val controlCenterAnimation: Boolean = true, // 控制中心翻页动画
    val controlCenterVibration: Boolean = false,  // 控制中心触摸震动
    val controlCenterEpd: Boolean = true,         // 控制中心EPD刷新模块
    val controlCenterBatteryStyle: Int = 1        // 控制中心电池图标：0=线条, 1=实心（默认实心）
) {
    // 图标大小档位已随上游 0.7 一并移除：尺寸由 GridSizing 按实际格子反算，
    // 手动档位（40/50/60/70）不再存在
}