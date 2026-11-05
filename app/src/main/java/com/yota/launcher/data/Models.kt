package com.yota.launcher.data

data class LauncherConfig(
    val columns: Int = 4,
    val rows: Int = 5,
    val homeColumns: Int = 4,
    val homeRows: Int = 3,
    val showHomeDividers: Boolean = true,   // home grid only
    val showAppDividers: Boolean = true,    // app drawer grid only
    val homeTopStyle: Int = 0,              // 主页顶部风格：0=经典居中, 1=左右分栏
    val homeTopIconStyle: Int = 0,          // 顶部状态行图标风格：0=线条, 1=实心
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
    val iconSize: Int = 60,                 // 图标大小（单位 dp，默认 60）
    val rootClear: Boolean = true,          // 是否使用 Root 清理后台（默认开）
    val controlCenterEnabled: Boolean = true,   // 控制中心总开关
    val controlCenterAnimation: Boolean = true, // 控制中心翻页动画
    val controlCenterVibration: Boolean = false,  // 控制中心触摸震动
    val controlCenterEpd: Boolean = true,         // 控制中心EPD刷新模块
    val controlCenterBatteryStyle: Int = 0        // 控制中心电池图标：0=线条, 1=实心
) {

    companion object {
        /**
         * 图标大小档位（dp）。上限定在 70 是网格反算出来的：
         * 720 − 左右 margin 24dp*2（=96px）= 624px，4 列每格 156px，
         * 再扣 item_app 内 LinearLayout 的 4dp*2 padding = 140px，正好 70dp。
         * 原先的 72dp(144px) 比这个宽 4px，已经压出 item 的内容区。
         */
        val ICON_SIZE_OPTIONS = intArrayOf(40, 50, 60, 70)

        /** 老配置里可能存着 42/72 这类已下架的值，读取时吸附到最近档位。 */
        fun nearestIconSize(dp: Int): Int {
            var best = ICON_SIZE_OPTIONS[0]
            var bestDiff = Int.MAX_VALUE
            for (option in ICON_SIZE_OPTIONS) {
                val diff = if (option > dp) option - dp else dp - option
                if (diff < bestDiff) {
                    bestDiff = diff
                    best = option
                }
            }
            return best
        }
    }
}