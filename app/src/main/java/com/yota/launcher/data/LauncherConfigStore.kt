package com.yota.launcher.data

import android.content.Context

class LauncherConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("yota_paper_config", Context.MODE_PRIVATE)

    fun load(): LauncherConfig {
        return LauncherConfig(
            columns = prefs.getInt("columns", 4),
            rows = prefs.getInt("rows", 5),
            homeColumns = prefs.getInt("home_columns", 4),
            homeRows = prefs.getInt("home_rows", 3),
            showHomeDividers = prefs.getBoolean("show_home_dividers", true),
            showAppDividers = prefs.getBoolean("show_app_dividers", true),
            recentWindowMs = prefs.getLong("recent_window_ms", 30 * 60_000L),
            recentCancelBackToApp = prefs.getBoolean("recent_cancel_back_to_app", true),
            iconPack = prefs.getString("icon_pack", "") ?: "",
            autoLineIcons = prefs.getBoolean("auto_line_icons", true),
            refreshMode = prefs.getInt("refresh_mode", 0),
            pageAnimation = prefs.getInt("page_animation", 1),
            screenOnAnimation = prefs.getBoolean("screen_on_animation", true),
            screenOnAnimationStyle = prefs.getInt("screen_on_animation_style", 2),
            screenOffAnimation = prefs.getBoolean("screen_off_animation", true),
            screenOffAnimationStyle = prefs.getInt("screen_off_animation_style", 3),
            autoApplyEpdParams = prefs.getBoolean("auto_apply_epd_params", true),
            iconSize = prefs.getInt("icon_size", 44),
            rootClear = prefs.getBoolean("root_clear", false),
            // 新增控制中心配置
            controlCenterEnabled = prefs.getBoolean("control_center_enabled", true),
            controlCenterAnimation = prefs.getBoolean("control_center_animation", true),
            controlCenterVibration = prefs.getBoolean("control_center_vibration", true),
            controlCenterEpd = prefs.getBoolean("control_center_epd", true)
        )
    }

    fun isGuideShown(): Boolean = prefs.getBoolean("first_guide_shown", false)

    fun setGuideShown() {
        prefs.edit().putBoolean("first_guide_shown", true).apply()
    }

    fun incrementHomePressCount(): Int {
        val next = prefs.getInt("home_press_count", 0) + 1
        prefs.edit().putInt("home_press_count", next).apply()
        return next
    }

    fun resetHomePressCount() {
        prefs.edit().putInt("home_press_count", 0).apply()
    }

    fun isDonationPromptEnabled(): Boolean = prefs.getBoolean("donation_prompt_enabled", true)

    fun setDonationPromptEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("donation_prompt_enabled", enabled).apply()
    }

    fun autoApplyEpdParamsEnabled(): Boolean = prefs.getBoolean("auto_apply_epd_params", true)

    fun setAutoApplyEpdParamsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_apply_epd_params", enabled).apply()
    }

    fun save(config: LauncherConfig) {
        prefs.edit()
            .putInt("columns", config.columns)
            .putInt("rows", config.rows)
            .putInt("home_columns", config.homeColumns)
            .putInt("home_rows", config.homeRows)
            .putBoolean("show_home_dividers", config.showHomeDividers)
            .putBoolean("show_app_dividers", config.showAppDividers)
            .putLong("recent_window_ms", config.recentWindowMs)
            .putBoolean("recent_cancel_back_to_app", config.recentCancelBackToApp)
            .putString("icon_pack", config.iconPack)
            .putBoolean("auto_line_icons", config.autoLineIcons)
            .putInt("refresh_mode", config.refreshMode)
            .putInt("page_animation", config.pageAnimation)
            .putBoolean("screen_on_animation", config.screenOnAnimation)
            .putInt("screen_on_animation_style", config.screenOnAnimationStyle)
            .putBoolean("screen_off_animation", config.screenOffAnimation)
            .putInt("screen_off_animation_style", config.screenOffAnimationStyle)
            .putBoolean("auto_apply_epd_params", config.autoApplyEpdParams)
            .putInt("icon_size", config.iconSize)
            .putBoolean("root_clear", config.rootClear)
            .putBoolean("control_center_enabled", config.controlCenterEnabled)
            .putBoolean("control_center_animation", config.controlCenterAnimation)
            .putBoolean("control_center_vibration", config.controlCenterVibration)
            .putBoolean("control_center_epd", config.controlCenterEpd)
            .apply()
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    // --- 全局清理时间戳 ---
    fun markRecentCleared() {
        prefs.edit().putLong("recent_cleared_at", System.currentTimeMillis()).apply()
    }

    fun recentClearedAt(): Long = prefs.getLong("recent_cleared_at", 0L)

    // --- 新增：单个 App 的清理时间戳 ---
    fun markAppCleared(packageName: String) {
        prefs.edit().putLong("recent_cleared_$packageName", System.currentTimeMillis()).apply()
    }

    fun appClearedAt(packageName: String): Long {
        return prefs.getLong("recent_cleared_$packageName", 0L)
    }
}