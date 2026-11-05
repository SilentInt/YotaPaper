package com.yota.launcher.ui

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.app.Activity
import com.yota.launcher.R

/**
 * Owns the status line under the clock: battery, WiFi, cellular and
 * Bluetooth. Click toggles WiFi/BT, long-press opens the matching system
 * settings page.
 */
class StatusBarController(private val activity: Activity) {

    private companion object {
        // 0.86 实测中文缩得太狠；0.95 只是轻轻收一点，接近原来的大小
        const val CJK_SCALE = 0.95f
    }

    private lateinit var battery: TextView
    private lateinit var wifiItem: View
    private lateinit var wifiIcon: ImageView
    private lateinit var wifiText: TextView
    private lateinit var network: TextView
    private lateinit var btItem: View
    private lateinit var btIcon: ImageView
    private lateinit var btText: TextView

    // 左右分栏风格的三个图标：另外两个是纯 ImageView
    private lateinit var splitWifiItem: View
    private lateinit var splitBtItem: View
    private lateinit var splitWifiIcon: ImageView
    private lateinit var splitBtIcon: ImageView
    private lateinit var splitBatteryText: TextView
    private lateinit var splitWifiText: TextView
    private lateinit var splitBtText: TextView
    // 两套顶部的电池图标：view_battery.xml 被 include 两次，include 的 id 会盖掉
    // 根节点 id，所以直接按各自的 id 取，拿到的就是 BatteryIconView
    private lateinit var classicBatteryIcon: BatteryIconView
    private lateinit var splitBatteryIcon: BatteryIconView
    // 图标风格：0=线条（EinkWallpaper 电池同款笔法）, 1=实心（改造前那套）
    private var iconStyle = -1

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = update()
    }

    @Suppress("DEPRECATION")
    fun bind() {
        battery = activity.findViewById(R.id.statusBattery)
        wifiItem = activity.findViewById(R.id.statusWifiItem)
        wifiIcon = activity.findViewById(R.id.statusWifiIcon)
        wifiText = activity.findViewById(R.id.statusWifiText)
        network = activity.findViewById(R.id.statusNetwork)
        btItem = activity.findViewById(R.id.statusBtItem)
        btIcon = activity.findViewById(R.id.statusBtIcon)
        btText = activity.findViewById(R.id.statusBtText)

        classicBatteryIcon = activity.findViewById(R.id.statusBatteryIcon)
        splitBatteryIcon = activity.findViewById(R.id.splitBatteryIcon)
        splitBatteryText = activity.findViewById(R.id.splitBatteryText)
        splitWifiItem = activity.findViewById(R.id.splitWifiItem)
        splitBtItem = activity.findViewById(R.id.splitBtItem)
        splitWifiIcon = activity.findViewById(R.id.splitWifiIcon)
        splitBtIcon = activity.findViewById(R.id.splitBtIcon)
        splitWifiText = activity.findViewById(R.id.splitWifiText)
        splitBtText = activity.findViewById(R.id.splitBtText)

        // 电量百分比用墨色，和 WiFi / 蓝牙那几项同一层信息。原来固定浅灰，
        // 是把它当成了"次要读数"，可旁边那两项只有关闭时才是灰的 —— 结果一个常亮的
        // 关键数字反而比"已关闭"的状态字还淡。
        // 只在这里设一次：update() 挂在电量广播上，每次进去都调 setTextColor 会白白
        // invalidate 两个 TextView，文本没变也刷一条。
        val ink = activity.resources.getColor(R.color.ink)
        battery.setTextColor(ink)
        splitBatteryText.setTextColor(ink)
    }

    /**
     * 切换顶部状态行的图标风格：0 = 线条（EinkWallpaper 竖屏时钟电池同款笔法），
     * 1 = 实心（改造前那套：方框电池 + Material 字形）。
     *
     * setImageResource 换掉的是整个 Drawable，挂在它上面的 colorFilter 也跟着没了
     * （WiFi / 蓝牙关闭时是灰的），所以换完要重新 update() 上一次色，否则两个图标
     * 会一直是墨色。update() 内部都有等值早退，重复调用不会白刷。
     */
    fun setIconStyle(style: Int) {
        if (style == iconStyle) return
        if (!::classicBatteryIcon.isInitialized) return
        iconStyle = style
        val solid = style == 1
        classicBatteryIcon.setBlockStyle(solid)
        splitBatteryIcon.setBlockStyle(solid)
        val wifiRes = if (solid) R.drawable.ic_home_wifi_solid else R.drawable.ic_home_wifi
        val btRes = if (solid) R.drawable.ic_home_bt_solid else R.drawable.ic_home_bt
        wifiIcon.setImageResource(wifiRes)
        btIcon.setImageResource(btRes)
        splitWifiIcon.setImageResource(wifiRes)
        splitBtIcon.setImageResource(btRes)
        update()
    }

    fun setup() {
        wifiItem.setOnClickListener { toggleWifi() }
        wifiItem.setOnLongClickListener {
            runCatching { activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            true
        }
        btItem.setOnClickListener { toggleBluetooth() }
        btItem.setOnLongClickListener {
            runCatching { activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            true
        }
        // 分栏风格：整行（图标 + 状态文字）都可点，触控目标比 20dp 的图标大不少
        splitWifiItem.setOnClickListener { toggleWifi(splitWifiItem) }
        splitWifiItem.setOnLongClickListener {
            runCatching { activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            true
        }
        splitBtItem.setOnClickListener { toggleBluetooth(splitBtItem) }
        splitBtItem.setOnLongClickListener {
            runCatching { activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            true
        }
        registerReceivers()
    }

    fun destroy() {
        runCatching { activity.unregisterReceiver(statusReceiver) }
    }

    @Suppress("DEPRECATION")
    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(statusReceiver, filter)
        }
    }

    // TextView.setText 没有等值早退，无条件走 requestLayout + invalidate；
    // 这几个控件挂在网络/电量广播上，不判等就是让墨水屏白刷一条
    private fun TextView.setIfChanged(value: CharSequence?) {
        if (!TextUtils.equals(text, value)) text = value
    }

    @Suppress("DEPRECATION")
    fun update() {
        val ink = activity.resources.getColor(R.color.ink)
        val gray = activity.resources.getColor(R.color.gray)

        val level = batteryLevel()
        val pctText = if (level >= 0) "$level%" else ""
        battery.setIfChanged(pctText)
        splitBatteryText.setIfChanged(pctText)
        applyBattery(level)

        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiConnected = runCatching {
            cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.isConnected == true
        }.getOrDefault(false)
        val wifiEnabled = runCatching {
            activity.getSystemService(Context.WIFI_SERVICE).let { (it as WifiManager).isWifiEnabled }
        }.getOrDefault(false)

        wifiText.setIfChanged(when {
            wifiConnected -> {
                val wm = activity.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val ssid = wm.connectionInfo?.ssid?.trim()?.removeSurrounding("\"")
                if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") "WiFi" else ssid
            }
            wifiEnabled -> activity.getString(R.string.wifi_on)
            else -> activity.getString(R.string.wifi_off)
        })
        wifiText.setTextColor(if (wifiEnabled) ink else gray)
        wifiIcon.setColorFilter(if (wifiEnabled) ink else gray)
        splitWifiText.setIfChanged(
            fitCjk(activity.getString(if (wifiEnabled) R.string.wifi_on else R.string.wifi_off))
        )
        splitWifiText.setTextColor(if (wifiEnabled) ink else gray)

        val mobileConnected = runCatching {
            cm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE)?.isConnected == true
        }.getOrDefault(false)
        if (!wifiConnected && mobileConnected) {
            network.setIfChanged("蜂窝")
            network.visibility = View.VISIBLE
        } else {
            network.setIfChanged("")
            network.visibility = View.GONE
        }

        val btOn = runCatching {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled ?: false
        }.getOrDefault(false)
        btText.setIfChanged(activity.getString(if (btOn) R.string.bt_on else R.string.bt_off))
        btText.setTextColor(if (btOn) ink else gray)
        btIcon.setColorFilter(if (btOn) ink else gray)
        splitWifiIcon.setColorFilter(if (wifiEnabled) ink else gray)
        splitBtIcon.setColorFilter(if (btOn) ink else gray)
        splitBtText.setIfChanged(fitCjk(activity.getString(if (btOn) R.string.bt_on else R.string.bt_off)))
        splitBtText.setTextColor(if (btOn) ink else gray)
    }

    // 两套顶部的电池图标：四格电量按百分比换算，写法与控制中心一致
    private fun applyBattery(level: Int) {
        val pct = if (level in 0..100) level else -1
        classicBatteryIcon.setPercent(pct)
        splitBatteryIcon.setPercent(pct)
    }

    @Suppress("DEPRECATION")
    private fun toggleWifi(delayOn: View = wifiItem) {
        runCatching {
            val wifi = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi.setWifiEnabled(!wifi.isWifiEnabled)
        }
        // 开关是异步的，广播可能比状态切换早到，延迟再拉一次
        delayOn.postDelayed({ update() }, 600)
    }

    @Suppress("DEPRECATION")
    private fun toggleBluetooth(delayOn: View = btItem) {
        runCatching {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null) {
                if (adapter.isEnabled) adapter.disable() else adapter.enable()
            }
        }
        delayOn.postDelayed({ update() }, 600)
    }

    // 同一个 textSize 下汉字比拉丁字母高出约 15%（字形占满 em 框 vs 只占 0.7em），
    // 再叠上中文没有衬线字库、被退回黑体，一行里混排会明显不齐。
    // 这里只把汉字收一点点；真要拉平得靠换字族，光缩字号过头中文就太小了
    private fun fitCjk(text: String): CharSequence {
        val spannable = SpannableString(text)
        var i = 0
        while (i < text.length) {
            if (!isCjk(text[i])) {
                i++
                continue
            }
            val start = i
            while (i < text.length && isCjk(text[i])) i++
            spannable.setSpan(
                RelativeSizeSpan(CJK_SCALE),
                start,
                i,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    private fun isCjk(c: Char): Boolean =
        c in '\u4E00'..'\u9FFF' || c in '\u3000'..'\u303F' || c in '\uFF00'..'\uFFEF'

    private fun batteryLevel(): Int {
        return runCatching {
            val intent = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) level * 100 / scale else -1
        }.getOrDefault(-1)
    }
}
