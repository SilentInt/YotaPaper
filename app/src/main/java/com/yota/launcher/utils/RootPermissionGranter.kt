package com.yota.launcher.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.topjohnwu.superuser.Shell
import com.yota.launcher.NotificationReaderService

/**
 * root 设备上的权限自检与补授。
 *
 * 检测走 PackageManager / AppOpsManager，纯内存开销；只有确实缺的项才 fork su，
 * 全部到位时不产生任何 shell 调用，所以可以放心每次冷启动都跑一遍（幂等）。
 *
 * 补不了的：Xposed 模块的激活开关存在各框架 Manager 的数据目录里（LSPosed / EdXposed
 * 位置互不兼容、且随版本变），只能用户在 Manager 里勾选。
 *
 * 涉及的 AppOps API 高于 minSdk 17，整体抑制 NewApi；
 * 实际调用点都有 gate（checkSelfPermission 等），不存在低版本直接跑的情况。
 */
@SuppressLint("NewApi")
object RootPermissionGranter {

    /** @param granted 本次补上的，@param failed 补完仍缺的（一般不会有） */
    data class Result(val granted: List<String>, val failed: List<String>)

    // 写成字面量而不是 AppOpsManager 常量：minSdk 17 下引用 API 23 常量会触发 NewApi lint，
    // 值本身是固定的
    private const val OPSTR_SYSTEM_ALERT_WINDOW = "android:system_alert_window"
    private const val OPSTR_GET_USAGE_STATS = "android:get_usage_stats"
    private const val KEY_NOTIF_LISTENERS = "enabled_notification_listeners"

    // pm grant 只能授予 manifest 里已声明的权限，给没声明的会报 Unknown permission
    private val RUNTIME_PERMS: List<String> = run {
        val list = mutableListOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.KILL_BACKGROUND_PROCESSES,
            "com.yotadevices.permission.ACCESS_BACK_SCREEN"
        )
        if (Build.VERSION.SDK_INT >= 26) {
            list.add(Manifest.permission.REQUEST_DELETE_PACKAGES)
        }
        list
    }

    /**
     * 自检并补授权限。必须在后台线程调用（内部会等 su）。
     * @param context 用 applicationContext，不要在 Caller 已经 finish 后还在跑 Activity。
     */
    fun ensureGranted(context: Context): Result {
        if (!RootUtil.isRootAvailable()) {
            val missingNow = missing(context).map { it.first }
            return Result(emptyList(), missingNow)
        }

        val before = missing(context)
        if (before.isEmpty()) return Result(emptyList(), emptyList())

        Shell.cmd(*before.map { it.second }.toTypedArray()).exec()

        // 逐项回读校验，命令 echo 成功不代表真的落进去了
        val after = missing(context).map { it.first }
        return Result(
            granted = before.map { it.first }.filter { it !in after },
            failed = after
        )
    }

    /** 返回仍未到位的项：<显示名, 补授命令> */
    private fun missing(context: Context): List<Pair<String, String>> {
        val pkg = context.packageName
        val out = ArrayList<Pair<String, String>>()

        for (perm in RUNTIME_PERMS) {
            if (context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) continue
            out.add(perm.substringAfterLast('.') to "pm grant $pkg $perm")
        }

        // 下面两项由 AppOps 管，pm grant 够不着，必须走 appops
        if (!appOpAllowed(context, OPSTR_GET_USAGE_STATS)) {
            out.add("使用情况访问" to "appops set $pkg $OPSTR_GET_USAGE_STATS allow")
        }
        if (!appOpAllowed(context, OPSTR_SYSTEM_ALERT_WINDOW)) {
            out.add("悬浮窗" to "appops set $pkg $OPSTR_SYSTEM_ALERT_WINDOW allow")
        }

        // 设备管理器不在这里补授：激活态的 DeviceAdmin 会被系统禁止卸载
        // （只能先 ACTION_ADD_DEVICE_ADMIN 界面取消），自己装机调试很麻烦。
        // 且它只服务非 Yota 设备的 DevicePolicyManager.lockNow()，Yota3 走 EPD 接口用不上。
        // 需要时由 LauncherActivity.deviceManagerLock() 走原来的系统界面引导。

        val listener = ComponentName(context, NotificationReaderService::class.java)
        if (!notifListenerEnabled(context, listener)) {
            // Secure 里是冒号分隔的组件列表，要拼接进去，不能直接覆盖别人的
            val current = Settings.Secure.getString(context.contentResolver, KEY_NOTIF_LISTENERS).orEmpty()
            val merged = if (current.isBlank()) listener.flattenToString()
            else "$current:${listener.flattenToString()}"
            out.add("通知读取" to "settings put secure $KEY_NOTIF_LISTENERS \"$merged\"")
        }

        return out
    }

    private fun appOpAllowed(context: Context, op: String): Boolean {
        val aom = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return aom.checkOpNoThrow(op, Process.myUid(), context.packageName) ==
                AppOpsManager.MODE_ALLOWED
    }

    private fun notifListenerEnabled(context: Context, listener: ComponentName): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, KEY_NOTIF_LISTENERS)
            ?: return false
        val mine = listener.flattenToString()
        return flat.split(":").any { it.equals(mine, ignoreCase = true) }
    }
}
