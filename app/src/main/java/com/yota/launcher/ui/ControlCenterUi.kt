package com.yota.launcher.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.topjohnwu.superuser.Shell
import com.yota.launcher.ControlCenterService
import com.yota.launcher.LockAdminReceiver
import com.yota.launcher.NotificationReaderService
import com.yota.launcher.R
import com.yota.launcher.data.LauncherConfigStore
import com.yota.launcher.epd.EpdEntry
import com.yota.launcher.epd.EpdParamsStore
import com.yota.launcher.utils.RootUtil
import com.yota.launcher.yota.EInkSdk
import com.yota.launcher.yota.YotaSdkAdapter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.yota.launcher.utils.LauncherExecutors

@SuppressLint("NewApi", "MissingPermission", "SetTextI18n")
class ControlCenterUi(
    private val activity: Activity,
    private val onDismiss: (animateToHome: Boolean) -> Unit
) {

    companion object {
        // 【核心修复1】：将记录键改为 "PID_具体组件名"（String 类型）
        // 彻底打破 SplashActivity 陷阱，且绝不破坏按界面独立配置的功能！
        private val processStartModes = mutableMapOf<String, Int>()

        // 单个 app 最多保留多少条历史消息，防止群聊刷屏把列表撑爆
        private const val MAX_MESSAGES_PER_APP = 20

        // 通知刷新防抖窗口：连发消息时合并成一次刷新，避免墨水屏连续闪
        private const val NOTIF_REFRESH_DEBOUNCE_MS = 300L

        // 通知读取权限的缓存有效期，免得每次刷新都去读 Settings.Secure
        private const val NOTIF_ACCESS_CACHE_MS = 3000L

        // 空态/未授权时塞进通知容器的占位提示专用 tag；
        // 用独立对象而非字符串，避免和通知卡片的 bodySignature tag 撞上
        private val PLACEHOLDER_TAG = Any()

        private const val TAG = "ControlCenterUi"
    }

    data class NotificationItem(
        var key: String,
        val pkg: String,
        var title: String,
        var messages: MutableList<String>,
        var time: Long,
        var contentIntent: PendingIntent?,
        var isExpanded: Boolean = false,
        // getApplicationIcon 是跨进程调用，取一次就存下来，刷新时不再重复问系统
        var icon: Drawable? = null
    )

    /** 卡片复用池：key 用包名（按 app 聚合，包名是稳定标识），避免整块重建导致的墨水屏闪烁。 */
    private val notifCards = HashMap<String, View>()


    private val activeNotifications = mutableListOf<NotificationItem>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRefresh: Runnable? = null

    private lateinit var rootView: View
    private var btAdapter: BluetoothAdapter? = null
    private var isRedirecting = false

    private var currentEpdEntry: EpdEntry? = null
    private var currentEpdPackage: String? = null

    private var isCheckingPid = false
    private var currentEpdPid = -1

    // EPD 前台应用查询的请求代号；queryEvents 移到后台后，回调可能乱序或晚于面板关闭
    private var epdQueryToken = 0

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_TIME_TICK -> {
                    val now = Date()
                    rootView.findViewById<TextView>(R.id.tv_time)?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
                    rootView.findViewById<TextView>(R.id.tv_date)?.text = SimpleDateFormat("MM月dd日 E", Locale.CHINA).format(now)
                }
                WifiManager.WIFI_STATE_CHANGED_ACTION,
                WifiManager.NETWORK_STATE_CHANGED_ACTION,
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val wifiManager = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    updateTogglesUI(wifiManager, btAdapter)
                }

                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    updateAirplaneModeUI()
                    val wifiManager = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    updateTogglesUI(wifiManager, btAdapter)
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    updateBatteryUI(intent)
                }
                "com.yota.MEDIA_UPDATE" -> {
                    val appName = intent.getStringExtra("appName") ?: NotificationReaderService.cachedAppName
                    val title = intent.getStringExtra("title") ?: NotificationReaderService.cachedTitle
                    val artist = intent.getStringExtra("artist") ?: NotificationReaderService.cachedArtist
                    val isPlaying = intent.getBooleanExtra("isPlaying", NotificationReaderService.cachedIsPlaying)
                    updateMediaUI(appName, title, artist, isPlaying)
                }
                "com.yota.MEDIA_CLEAR" -> clearMediaUI()

                "com.yota.NEW_NOTIF" -> {
                    val key = intent.getStringExtra("key") ?: return
                    val pkg = intent.getStringExtra("pkg") ?: ""
                    val title = intent.getStringExtra("title") ?: ""
                    val text = intent.getStringExtra("text") ?: ""
                    val time = intent.getLongExtra("time", System.currentTimeMillis())
                    @Suppress("DEPRECATION")
                    val contentIntent = intent.getParcelableExtra<PendingIntent>("contentIntent")

                    val existingIndex = activeNotifications.indexOfFirst { it.pkg == pkg }
                    if (existingIndex != -1) {
                        val item = activeNotifications[existingIndex]
                        item.key = key
                        item.title = title
                        item.time = time
                        item.contentIntent = contentIntent

                        if (text.isNotBlank() && item.messages.lastOrNull() != text) {
                            item.messages.add(text)
                            // 群聊刷屏时 messages 会一直涨，超出上限丢掉最早的
                            while (item.messages.size > MAX_MESSAGES_PER_APP) {
                                item.messages.removeAt(0)
                            }
                        }
                        activeNotifications.removeAt(existingIndex)
                        activeNotifications.add(0, item)
                    } else {
                        val initialMessages = if (text.isNotBlank()) mutableListOf(text) else mutableListOf()
                        activeNotifications.add(0, NotificationItem(key, pkg, title, initialMessages, time, contentIntent))
                    }

                    if (activeNotifications.size > 10) activeNotifications.removeAt(activeNotifications.lastIndex)
                    refreshNotifications()
                }
                "com.yota.NOTIF_REMOVED" -> {
                    val key = intent.getStringExtra("key") ?: return
                    activeNotifications.removeAll { it.key == key }
                    refreshNotifications()
                }
            }
        }
    }

    fun bind(root: View) {
        rootView = root
        disableSystemHaptic(rootView)
        btAdapter = BluetoothAdapter.getDefaultAdapter()
        bindControlButtons()
        bindEpdCard()
    }

    private fun disableSystemHaptic(view: View) {
        view.isHapticFeedbackEnabled = false
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                disableSystemHaptic(view.getChildAt(i))
            }
        }
    }

    fun attachReceivers() {
        val filter = IntentFilter().apply {
            addAction("com.yota.MEDIA_UPDATE")
            addAction("com.yota.MEDIA_CLEAR")
            addAction("com.yota.NEW_NOTIF")
            addAction("com.yota.NOTIF_REMOVED")
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(dataReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(dataReceiver, filter)
        }
    }

    fun detachReceivers() {
        runCatching { activity.unregisterReceiver(dataReceiver) }
        // 面板已销毁，作废在途的 EPD 查询，避免结果回填到已死的界面上
        epdQueryToken++
    }

    fun refreshAll() {
        // 每次打开面板都重新确认一次授权状态（用户可能刚在设置里开过）
        invalidateNotifAccessCache()
        pullCurrentState()
        pullEpdState()
    }

    /**
     * 通知刷新入口。走防抖而不是直接刷：
     * 连发消息（微信一口气来五条）时只刷最后一次，避免整块重建把墨水屏连闪几下。
     */
    fun refreshNotifications() {
        scheduleNotificationRefresh(NOTIF_REFRESH_DEBOUNCE_MS)
    }

    fun scheduleNotificationRefresh(delayMs: Long) {
        pendingRefresh?.let { rootView.removeCallbacks(it) }
        val runnable = Runnable { refreshNotifUI() }
        pendingRefresh = runnable
        rootView.postDelayed(runnable, delayMs)
    }

    fun cancelScheduledRefresh() {
        pendingRefresh?.let { rootView.removeCallbacks(it) }
        pendingRefresh = null
        // 面板正在收起，作废在途的 EPD 查询
        epdQueryToken++
    }

    private fun bindControlButtons() {
        val wifiManager = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
// ====== 新增：电源控制 (并排宽矮版 - 去除阴影、位置上移) ======
        val btnPower = rootView.findViewById<ImageView>(R.id.btn_power_top)
        btnPower?.setOnClickListener {
            simulateClickFeedback(it, changeColor = false)

            val dialog = android.app.Dialog(activity)
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

            dialog.window?.apply {
                clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setWindowAnimations(0)
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            }

            val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }

            // 1. 去除阴影，只保留简洁的黑框白底背景
            val dialogBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                setStroke(dp(2), android.graphics.Color.BLACK)
                cornerRadius = dp(12).toFloat()
            }

            // 2. 弹窗根布局
            val root = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                background = dialogBg
                // 高度仍由内容自然撑开（不设 minimumHeight）；只把顶部留白从 20dp 加到 25dp，
                // 弹窗居中后上边缘随之抬 2.5dp，盖住音量滑块那颗从上方露头的圆。
                // 左右保持 20dp 不动，避免重启/关机按钮（weight 各半）被撑宽
                setPadding(dp(20), dp(25), dp(20), dp(20))
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            // 3. 弹窗标题
            root.addView(android.widget.TextView(activity).apply {
                text = "电源选项"
                textSize = 20f
                setTextColor(android.graphics.Color.BLACK)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                letterSpacing = 0.1f
                setPadding(0, 0, 0, dp(16))
            })

            // 4. 创建横向排列的按钮容器
            val actionRow = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            root.addView(actionRow)

            // 5. 左右并排按钮生成器
            fun addSideBySideBtn(textStr: String, iconResId: Int, isLeft: Boolean, onClick: () -> Unit) {
                val btnContainer = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, dp(14), 0, dp(14))

                    val lp = android.widget.LinearLayout.LayoutParams(
                        0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    ).apply {
                        if (isLeft) rightMargin = dp(12)
                    }
                    layoutParams = lp

                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.WHITE)
                        setStroke(dp(2), android.graphics.Color.BLACK)
                        cornerRadius = dp(8).toFloat()
                    }

                    val iconView = ImageView(activity).apply {
                        setImageResource(iconResId)
                        setColorFilter(android.graphics.Color.BLACK)
                        layoutParams = android.widget.LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                            rightMargin = dp(6)
                        }
                    }
                    addView(iconView)

                    val tv = TextView(activity).apply {
                        text = textStr
                        textSize = 17f
                        setTextColor(android.graphics.Color.BLACK)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                    addView(tv)

                    // 瞬间反色交互反馈
                    setOnClickListener {
                        val bg = background as android.graphics.drawable.GradientDrawable
                        bg.setColor(android.graphics.Color.BLACK)
                        iconView.setColorFilter(android.graphics.Color.WHITE)
                        tv.setTextColor(android.graphics.Color.WHITE)

                        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)

                        postDelayed({
                            dialog.dismiss()
                            onClick()
                        }, 100)
                    }
                }
                actionRow.addView(btnContainer)
            }

                // 添加左右两个并排按钮
            addSideBySideBtn("重启", R.drawable.ic_reboot, true) {
                onDismiss(false)
                // 使用 svc 调用 Android 框架层的优雅重启，避免硬断电
                LauncherExecutors.io { Shell.cmd("svc power reboot").exec() }
            }

            addSideBySideBtn("关机", R.drawable.ic_power_off, false) {
                onDismiss(false)
                // 使用 svc 调用 Android 框架层的优雅关机，让墨水屏有时间清理屏幕
                LauncherExecutors.io { Shell.cmd("svc power shutdown").exec() }
            }

            // 6. 底部取消按钮
            val cancelBtn = android.widget.TextView(activity).apply {
                text = "取消"
                textSize = 15f
                setTextColor(android.graphics.Color.parseColor("#666666"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                letterSpacing = 0.1f
                setPadding(dp(40), dp(10), dp(40), dp(6))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(12)
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                }

                setOnClickListener {
                    simulateClickFeedback(this, changeColor = true)
                    dialog.dismiss()
                }
            }
            root.addView(cancelBtn)

            dialog.setContentView(root)
            dialog.show()

            // 7. 布局控制：强行重写物理宽度并控制 Y 轴偏移上移
            dialog.window?.let { win ->
                win.setLayout(
                    (activity.resources.displayMetrics.widthPixels * 0.92).toInt(),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )

                val params = win.attributes
                // 弹窗默认在屏幕正中央 (Gravity.CENTER)。负值表示往屏幕上方偏移。
                params.y = -dp(10)
                win.attributes = params
            }
        }

        // ====== 背屏单开（纯图标） ======
        val btnEpdOnly = rootView.findViewById<ImageView>(R.id.btn_epd_only)
        btnEpdOnly?.setOnClickListener {
            simulateClickFeedback(it, changeColor = false)

            // 翻转当前状态
            val isCurrentlyOn = Settings.System.getInt(activity.contentResolver, "pure_reading_mode", 0) == 1
            val newState = if (isCurrentlyOn) 0 else 1

            // 【修改点1】：删除了 onDismiss(false)，点击不再退出控制中心

            // 【修改点2】：点击瞬间，立刻切换图标，给用户即时的视觉反馈
            btnEpdOnly.setImageResource(
                if (newState == 1) R.drawable.ic_epd_only_on else R.drawable.ic_epd_only_off
            )

            // 后台执行原生指令
            LauncherExecutors.io {
                runCatching {
                    com.topjohnwu.superuser.Shell.cmd(
                        "settings put system pure_reading_mode $newState",
                        "settings put global system_pure_lock $newState",
                        "setprop sys.pure.reading.mode $newState"
                    ).exec()
                }

                // 【修改点3】：底层命令执行完毕后，在主线程再同步一次最终状态，确保万无一失
                mainHandler.post {
                    updateEpdOnlyUI()
                }
            }
        }
        val btnAirplane = rootView.findViewById<ImageView>(R.id.btn_airplane_mode)
        btnAirplane?.setOnClickListener {
            simulateClickFeedback(it, changeColor = false)

            val currentOn = Settings.Global.getInt(
                activity.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0
            ) == 1
            val targetOn = !currentOn
            btnAirplane.setImageResource(
                if (targetOn) R.drawable.ic_airplane_mode_on
                else R.drawable.ic_airplane_mode_off
            )

            LauncherExecutors.io {
                if (!RootUtil.isRootAvailable()) {
                    mainHandler.post {
                        updateAirplaneModeUI()
                        activity.startActivity(
                            Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        onDismiss(false)
                    }
                    return@io
                }

                val newState = if (targetOn) 1 else 0
                Shell.cmd("settings put global airplane_mode_on $newState").exec()
                Shell.cmd("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state ${newState == 1}").exec()

                mainHandler.post {
                    updateAirplaneModeUI()
                    updateTogglesUI(wifiManager, btAdapter)
                }
            }
        }

        btnAirplane?.setOnLongClickListener {
            simulateClickFeedback(it, changeColor = false)
            activity.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onDismiss(false)
            true
        }

        val btnWifi = rootView.findViewById<View>(R.id.btn_wifi)
        val btnBluetooth = rootView.findViewById<View>(R.id.btn_bluetooth)

        btnWifi?.setOnClickListener {
            simulateClickFeedback(it, changeColor = false)
            val currentState = wifiManager.isWifiEnabled
            wifiManager.isWifiEnabled = !currentState
        }
        btnWifi?.setOnLongClickListener {
            simulateClickFeedback(it, changeColor = false)
            activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onDismiss(false)
            true
        }

        btnBluetooth?.setOnClickListener {
            simulateClickFeedback(it, changeColor = false)
            val currentState = btAdapter?.isEnabled == true
            if (currentState) btAdapter?.disable() else btAdapter?.enable()
        }
        btnBluetooth?.setOnLongClickListener {
            simulateClickFeedback(it, changeColor = false)
            activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onDismiss(false)
            true
        }

        rootView.findViewById<View>(R.id.btn_settings_top)?.setOnClickListener {
            simulateClickFeedback(it, changeColor = false)
            activity.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onDismiss(false)
        }

        rootView.findViewById<View>(R.id.btn_refresh)?.setOnClickListener {
            simulateClickFeedback(it, changeColor = false)
            EInkSdk.manualFullRefresh(rootView)
            mainHandler.postDelayed({
                onDismiss(false)
            }, 150)
        }

        rootView.findViewById<View>(R.id.btn_lock)?.setOnClickListener {
            simulateClickFeedback(it, changeColor = false)
            lockScreen()
        }

        val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volumeBar = rootView.findViewById<SeekBar>(R.id.volume_slider)
        val tvVolumePercent = rootView.findViewById<TextView>(R.id.tv_volume_percent)

        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeBar?.max = maxVol
        volumeBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                    tvVolumePercent?.text = "${(progress * 100) / maxVol}%"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        rootView.findViewById<View>(R.id.btn_media_prev)?.setOnClickListener {
            sendMediaCommand(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            simulateClickFeedback(it, changeColor = true)
        }
        rootView.findViewById<TextView>(R.id.btn_media_play)?.setOnClickListener {
            sendMediaCommand(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            simulateClickFeedback(it, changeColor = true)
        }
        rootView.findViewById<View>(R.id.btn_media_next)?.setOnClickListener {
            sendMediaCommand(KeyEvent.KEYCODE_MEDIA_NEXT)
            simulateClickFeedback(it, changeColor = true)
        }

        rootView.findViewById<View>(R.id.card_media)?.setOnClickListener {
            simulateClickFeedback(it, changeColor = false)
            runCatching {
                val pendingIntent = NotificationReaderService.cachedIntent
                if (pendingIntent != null) {
                    pendingIntent.send()
                } else {
                    val pkg = NotificationReaderService.cachedPkg
                    if (pkg.isNotEmpty()) {
                        val launchIntent = activity.packageManager.getLaunchIntentForPackage(pkg)
                        if (launchIntent != null) {
                            activity.startActivity(launchIntent)
                        }
                    }
                }
                onDismiss(false)
            }
        }

        rootView.findViewById<View>(R.id.btn_clear_notif)?.setOnClickListener {
            simulateClickFeedback(it, changeColor = false)
            NotificationReaderService.instance?.cancelAllNotifications()
            activeNotifications.clear()
            refreshNotifUI()
        }
    }

    private fun bindEpdCard() {
        val contrast = rootView.findViewById<SeekBar>(R.id.epd_slider_contrast)
        val sharp = rootView.findViewById<SeekBar>(R.id.epd_slider_sharp)
        val black = rootView.findViewById<SeekBar>(R.id.epd_slider_black)
        val white = rootView.findViewById<SeekBar>(R.id.epd_slider_white)
        val bright = rootView.findViewById<SeekBar>(R.id.epd_slider_bright)

        val tvContrast = rootView.findViewById<TextView>(R.id.tv_epd_contrast)
        val tvSharp = rootView.findViewById<TextView>(R.id.tv_epd_sharp)
        val tvBlack = rootView.findViewById<TextView>(R.id.tv_epd_black)
        val tvWhite = rootView.findViewById<TextView>(R.id.tv_epd_white)
        val tvBright = rootView.findViewById<TextView>(R.id.tv_epd_bright)

        fun attach(seekBar: SeekBar?, tv: TextView?, paramUpdater: (Int) -> Unit) {
            seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                    tv?.text = progress.toString()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    s?.let {
                        paramUpdater(it.progress)
                        saveAndApplyEpdEntry()
                    }
                }
            })
        }

        attach(contrast, tvContrast) { currentEpdEntry?.contrast = it }
        attach(sharp, tvSharp) { currentEpdEntry?.sharping = it }
        attach(black, tvBlack) { currentEpdEntry?.blackStretch = it }
        attach(white, tvWhite) { currentEpdEntry?.whiteStretch = it }
        attach(bright, tvBright) { currentEpdEntry?.bright = it }

        rootView.findViewById<View>(R.id.btn_epd_mode_0)?.setOnClickListener {
            simulateClickFeedback(it)
            currentEpdEntry?.mode = 0
            saveAndApplyEpdEntry()
            updateEpdUiState()
        }
        rootView.findViewById<View>(R.id.btn_epd_mode_1)?.setOnClickListener {
            simulateClickFeedback(it)
            currentEpdEntry?.mode = 1
            saveAndApplyEpdEntry()
            updateEpdUiState()
        }
        rootView.findViewById<View>(R.id.btn_epd_mode_2)?.setOnClickListener {
            simulateClickFeedback(it)
            currentEpdEntry?.mode = 2
            saveAndApplyEpdEntry()
            updateEpdUiState()
        }

        rootView.findViewById<View>(R.id.btn_epd_restart_app)?.setOnClickListener {
            simulateClickFeedback(it, changeColor = true)

            val pkg = currentEpdPackage ?: return@setOnClickListener
            val launchIntent = activity.packageManager.getLaunchIntentForPackage(pkg)

            onDismiss(false)

            LauncherExecutors.io {
                Shell.cmd("am force-stop $pkg").exec()
                Thread.sleep(300)
                if (launchIntent != null) {
                    mainHandler.post {
                        activity.startActivity(launchIntent)
                    }
                }
            }
        }

        // 翻页动画的画面质量。只写本地动画配置，不碰系统 Provider 的刷新档位，
        // 所以「高画质」和「动画」可以同时成立。
        rootView.findViewById<View>(R.id.btn_epd_wave_hi)?.setOnClickListener {
            simulateClickFeedback(it)
            saveAnimWave(EpdParamsStore.ANIM_WAVE_FOLLOW)
        }
        rootView.findViewById<View>(R.id.btn_epd_wave_full)?.setOnClickListener {
            simulateClickFeedback(it)
            saveAnimWave(EpdParamsStore.ANIM_WAVE_FULL_DITHER)
        }
    }

    /** 只改**当前这个前台 Activity**的波形，保留其它 App 各自的设置。 */
    private fun saveAnimWave(wave: Int) {
        val target = currentEpdEntry?.activity ?: return
        LauncherExecutors.io {
            // 早先这里写的是全局 anim_wave：给微信读书选黑条带，阅读也跟着变。
            // 卡片针对的是单个前台 Activity，波形也必须只落在这一条上。
            EpdParamsStore.setAnimWave(activity, target, wave)
            // 逐条按自己的波形刷新 Provider 行：高画质要有，黑条带必须没有。
            EpdParamsStore.syncAnimProviderRows(activity)
            mainHandler.post { updateEpdUiState() }
        }
    }

    private fun pullEpdState() {
        if (!YotaSdkAdapter.isYotaDevice()) return
        if (Build.VERSION.SDK_INT < 21) return

        // 【原有逻辑】：读取开关配置，如果在桌面 或者 开关被关闭，直接隐藏 EPD 卡片并退出逻辑
        val config = LauncherConfigStore(activity).load()
        if (!config.controlCenterEpd || activity.javaClass.simpleName == "LauncherActivity") {
            rootView.findViewById<View>(R.id.epd_card)?.visibility = View.GONE
            return
        }

        // queryEvents 要遍历一整天的 MOVE_TO_FOREGROUND，是跨进程调用；放主线程会让下拉
        // 手势在面板出现后空等几百毫秒。整段挪到后台，卡片先占位再回填。
        val token = ++epdQueryToken
        LauncherExecutors.io {
            val foreground = runCatching { queryForegroundComponent() }.getOrNull()
            LauncherExecutors.main {
                // 面板已关闭、或期间又发起过查询：丢弃这次结果，避免显示过期前台应用
                if (token != epdQueryToken) return@main
                val pkg = foreground?.first
                val cls = foreground?.second
                // 查询失败与查不到前台一样处理：收起卡片，不要把上一轮的"获取中…"留在屏上
                if (pkg.isNullOrEmpty()) {
                    isCheckingPid = false
                    currentEpdEntry = null
                    currentEpdPackage = null
                    rootView.findViewById<View>(R.id.epd_card)?.visibility = View.GONE
                    return@main
                }
                showEpdCard(pkg, "ComponentInfo{$pkg/$cls}", token)
            }
        }
    }

    /** 最近一次进入前台、且不是本应用/系统 UI 的应用；查不到返回 null。 */
    private fun queryForegroundComponent(): Pair<String, String>? {
        val usageStatsManager = activity.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - 24 * 3600 * 1000L, now)

        var targetPackage: String? = null
        var targetActivity: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (event.packageName != activity.packageName && event.packageName != "com.android.systemui") {
                    targetPackage = event.packageName
                    targetActivity = event.className
                }
            }
        }
        return targetPackage?.let { it to (targetActivity ?: "") }
    }

    /** 卡片先出占位（名字、pid 未到），慢查询都在后台依次跑。 */
    private fun showEpdCard(pkg: String, component: String, token: Int) {
        currentEpdPackage = pkg
        rootView.findViewById<View>(R.id.epd_card)?.visibility = View.VISIBLE
        rootView.findViewById<TextView>(R.id.tv_epd_app_name)?.text = "获取中..."
        isCheckingPid = true
        updateEpdUiState()

        LauncherExecutors.io {
            try {
                // 串行跑即可：应用名和本地配置各约 1ms，瓶颈是 ps（20~100ms），
                // 并发收益可忽略；现场建线程池反而要担异常路径漏 shutdown 的风险
                val pm = activity.packageManager
                val appLabel = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)

                val foundEntry = EpdParamsStore.load(activity).find { it.activity == component }
                    ?: EpdEntry(
                        activity = component, mode = 0, contrast = 10, sharping = 2,
                        blackStretch = 70, whiteStretch = 255, bright = 0
                    )

                // pid 只是附带信息：root 不可用或 ps 超时时退回 -1，别让整张卡片失败
                val pid = runCatching { resolvePid(pkg) }.getOrDefault(-1)

                mainHandler.post {
                    if (token != epdQueryToken) return@post
                    currentEpdPackage = pkg
                    currentEpdEntry = foundEntry
                    rootView.findViewById<TextView>(R.id.tv_epd_app_name)?.text = appLabel

                    isCheckingPid = false
                    currentEpdPid = pid

                    if (pid != -1) {
                        if (processStartModes.size > 500) processStartModes.clear()

                        // 【核心修复1接力】：将状态标记存入专属的 "PID_组件名" 钥匙
                        val stateKey = "${pid}_$component"
                        if (!processStartModes.containsKey(stateKey)) {
                            processStartModes[stateKey] = currentEpdEntry?.mode ?: 0
                        }
                    }
                    updateEpdUiState()
                }
            } catch (e: Exception) {
                Log.w(TAG, "showEpdCard failed for $pkg", e)
                mainHandler.post {
                    if (token != epdQueryToken) return@post
                    isCheckingPid = false
                    updateEpdUiState()
                }
            }
        }
    }

    /**
     * 查 [pkg] 的进程号，查不到返回 -1。
     *
     * 先试 getRunningAppProcesses（API 21+ 对三方应用只返回自身，这里只当快速命中）；
     * 不中再走 root `ps`：进程名是完整包名，一次 su 调用，避开 dumpsys 的重量，
     * 也不会被 `:push` 之类的子进程误匹配。最后用 pidof 兜底，防 ps 输出被截断。
     */
    private fun resolvePid(pkg: String): Int {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.runningAppProcesses?.find { it.processName == pkg }?.let { return it.pid }

        val out = Shell.cmd("ps").exec().out
        val targetLine = out.find { it.trim().endsWith(" $pkg") }
            ?: out.find { it.contains(" $pkg") }

        if (targetLine != null) {
            val parts = targetLine.trim().split(Regex("\\s+"))
            return parts.getOrNull(1)?.toIntOrNull()
                ?: parts.firstOrNull { it.matches(Regex("\\d+")) }?.toIntOrNull()
                        ?: -1
        }

        val outPidof = Shell.cmd("pidof $pkg").exec().out
        if (outPidof.isNotEmpty()) {
            return outPidof.joinToString(" ").split(Regex("\\s+"))
                .firstOrNull { it.isNotBlank() }?.toIntOrNull() ?: -1
        }
        return -1
    }

    private fun updateEpdUiState() {
        val entry = currentEpdEntry ?: return
        val m0 = rootView.findViewById<TextView>(R.id.btn_epd_mode_0)
        val m1 = rootView.findViewById<TextView>(R.id.btn_epd_mode_1)
        val m2 = rootView.findViewById<TextView>(R.id.btn_epd_mode_2)

        fun setActive(tv: TextView?, active: Boolean) {
            tv?.setBackgroundResource(if (active) R.drawable.bg_btn_on else 0)
            tv?.setTextColor(if (active) Color.WHITE else Color.BLACK)
        }

        setActive(m0, entry.mode == 0)
        setActive(m1, entry.mode == 1)
        setActive(m2, entry.mode == 2)

        val hint2Container = rootView.findViewById<View>(R.id.hint_epd_2)
        val hint2Msg = rootView.findViewById<TextView>(R.id.tv_epd_2_msg)
        val btnRestart = rootView.findViewById<View>(R.id.btn_epd_restart_app)
        val waveContainer = rootView.findViewById<View>(R.id.epd_wave_container)
        val waveHi = rootView.findViewById<TextView>(R.id.btn_epd_wave_hi)
        val waveFull = rootView.findViewById<TextView>(R.id.btn_epd_wave_full)
        // App 名徽标与「需重启」提示共用标题行右侧，二选一显示。
        val appName = rootView.findViewById<View>(R.id.tv_epd_app_name)

        if (entry.mode == 2) {
            if (isCheckingPid) {
                hint2Container?.visibility = View.VISIBLE
                appName?.visibility = View.GONE
                hint2Msg?.text = "匹配进程中…"
                btnRestart?.visibility = View.GONE
                // 进程状态还没确定，波形区先不出现，避免卡片跳来跳去
                waveContainer?.visibility = View.GONE
            } else {
                val stateKey = "${currentEpdPid}_${currentEpdEntry?.activity}"
                val startMode = processStartModes[stateKey] ?: 0

                if (startMode == 2) {
                    // 动画已生效：隐藏重启提示、恢复 App 名徽标，保留风格切换。
                    hint2Container?.visibility = View.GONE
                    appName?.visibility = View.VISIBLE
                    waveContainer?.visibility = View.VISIBLE
                    val isFull = EpdParamsStore.animWaveFor(activity, entry.activity) ==
                            EpdParamsStore.ANIM_WAVE_FULL_DITHER
                    setActive(waveHi, !isFull)
                    setActive(waveFull, isFull)
                } else {
                    // 未生效：标题行右侧显示「首次切换需重启 + 立即重启」，顶替 App 名。
                    hint2Container?.visibility = View.VISIBLE
                    appName?.visibility = View.GONE
                    hint2Msg?.text = "首次切换需重启"
                    btnRestart?.visibility = View.VISIBLE

                    // 反正要重启一次，正好在这里把画面质量定下来，不用再进设置页
                    waveContainer?.visibility = View.VISIBLE
                    val isFull = EpdParamsStore.animWaveFor(activity, entry.activity) ==
                            EpdParamsStore.ANIM_WAVE_FULL_DITHER
                    setActive(waveHi, !isFull)
                    setActive(waveFull, isFull)
                }
            }
        } else {
            hint2Container?.visibility = View.GONE
            appName?.visibility = View.VISIBLE
            waveContainer?.visibility = View.GONE
        }

        rootView.findViewById<View>(R.id.epd_params_container)?.visibility = if (entry.mode == 1) View.VISIBLE else View.GONE

        rootView.findViewById<SeekBar>(R.id.epd_slider_contrast)?.progress = entry.contrast
        rootView.findViewById<TextView>(R.id.tv_epd_contrast)?.text = entry.contrast.toString()

        rootView.findViewById<SeekBar>(R.id.epd_slider_sharp)?.progress = entry.sharping
        rootView.findViewById<TextView>(R.id.tv_epd_sharp)?.text = entry.sharping.toString()

        rootView.findViewById<SeekBar>(R.id.epd_slider_black)?.progress = entry.blackStretch
        rootView.findViewById<TextView>(R.id.tv_epd_black)?.text = entry.blackStretch.toString()

        rootView.findViewById<SeekBar>(R.id.epd_slider_white)?.progress = entry.whiteStretch
        rootView.findViewById<TextView>(R.id.tv_epd_white)?.text = entry.whiteStretch.toString()

        rootView.findViewById<SeekBar>(R.id.epd_slider_bright)?.progress = entry.bright
        rootView.findViewById<TextView>(R.id.tv_epd_bright)?.text = entry.bright.toString()
    }

    private fun saveAndApplyEpdEntry() {
        val entry = currentEpdEntry ?: return
        LauncherExecutors.io {
            // 走统一入口：本地条目 + Provider 档位 + 动画参数一次写完。
            // 早先这里只写条目本身、不写动画参数（anim_enable / 帧数 / 波形），
            // 于是「在控制中心选动画 → 重启」什么都不会发生，而设置页里做同样的
            // 事却正常 —— 两条 UI 各写一半配置导致的。
            EpdParamsStore.setMode(activity, entry)
        }
    }

    private fun lockScreen() {
        try {
            ControlCenterService.isPanelActive = false
            activity.sendBroadcast(Intent("com.yota.PANEL_CLOSED"))
        } catch (_: Exception) { }
        if (YotaSdkAdapter.isYotaDevice()) {
            val config = LauncherConfigStore(activity).load()

            val isDesktop = activity.javaClass.simpleName == "LauncherActivity"

            if (config.screenOffAnimation && config.refreshMode == 0 && isDesktop) {
                val anim = when (config.screenOffAnimationStyle) {
                    2 -> EInkSdk.ANIM_HORIZONTAL_OPEN
                    3 -> EInkSdk.ANIM_HORIZONTAL_CLOSE
                    4 -> EInkSdk.ANIM_VERTICAL_TOP
                    5 -> EInkSdk.ANIM_VERTICAL_OPEN
                    6 -> EInkSdk.ANIM_VERTICAL_CLOSE
                    0 -> EInkSdk.ANIM_OFF
                    else -> EInkSdk.ANIM_HORIZONTAL_LEFT
                }
                if (anim != EInkSdk.ANIM_OFF) {
                    rootView.visibility = View.INVISIBLE
                    rootView.postDelayed({
                        EInkSdk.applyScreenAnimation(activity.window.decorView, anim)
                        rootView.postDelayed({
                            YotaSdkAdapter.lockEpd()
                            onDismiss(false)
                        }, 700L)
                    }, 50L)
                    return
                }
            }
            YotaSdkAdapter.lockEpd()
            onDismiss(false)
        } else {
            val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(activity, LockAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                runCatching { dpm.lockNow() }
            } else {
                runCatching {
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                        .putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            activity.getString(R.string.lock_admin_description)
                        )
                    activity.startActivity(intent)
                }
            }
            onDismiss(false)
        }
    }

    private fun simulateClickFeedback(view: View, changeColor: Boolean = false) {
        val config = LauncherConfigStore(activity).load()
        if (config.controlCenterVibration) {
            view.performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        }

        if (changeColor && view is TextView) {
            val originalColor = view.currentTextColor
            view.setTextColor(Color.GRAY)
            view.postDelayed({ view.setTextColor(originalColor) }, 200)
        }
    }

    private fun sendMediaCommand(keyCode: Int) {
        val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun updateAirplaneModeUI() {
        val btnAirplane = rootView.findViewById<ImageView>(R.id.btn_airplane_mode) ?: return

        val isAirplaneMode = Settings.Global.getInt(activity.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

        if (isAirplaneMode) {
            btnAirplane.setImageResource(R.drawable.ic_airplane_mode_on)
        } else {
            btnAirplane.setImageResource(R.drawable.ic_airplane_mode_off)
        }
        btnAirplane.setBackgroundResource(0)
        btnAirplane.clearColorFilter()
    }
    private fun updateEpdOnlyUI() {
        val btnEpdOnly = rootView.findViewById<ImageView>(R.id.btn_epd_only) ?: return

        // 读取系统设置中纯净模式的值 (1为开，0为关)
        val isPureModeOn = Settings.System.getInt(activity.contentResolver, "pure_reading_mode", 0) == 1

        if (isPureModeOn) {
            btnEpdOnly.setImageResource(R.drawable.ic_epd_only_on) // 纯黑实心书本
        } else {
            btnEpdOnly.setImageResource(R.drawable.ic_epd_only_off) // 白底黑框书本
        }
    }
    /**
     * 电池图标风格：0 = 线条（EinkWallpaper 同款）, 1 = 实心（改造前的方框电池）。
     *
     * 放在 pullCurrentState 里而不是 bind 里：面板是常驻的（LauncherActivity 只
     * inflate 一次），bind 只在启动时跑一次，用户在设置里改完风格再打开面板就看不到变化。
     * 每次打开面板 refreshAll -> pullCurrentState 重读一次配置，改完立即生效，
     * 也不用在配置变更时去通知一个可能根本没显示的浮层。
     * setBlockStyle 内部有等值早退，没改风格时不会触发重绘。
     */
    private fun applyBatteryStyle() {
        val solid = LauncherConfigStore(activity).load().controlCenterBatteryStyle == 1
        rootView.findViewById<BatteryIconView>(R.id.view_battery_icon)?.setBlockStyle(solid)
    }

    private fun updateBatteryUI(batteryStatus: Intent?) {
        val batLevel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 0
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val levelPct = if (scale > 0) (batLevel * 100) / scale else 0

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        rootView.findViewById<TextView>(R.id.tv_battery_percent)?.text = "$levelPct%"
        rootView.findViewById<View>(R.id.tv_battery_charging)?.visibility = if (isCharging) View.VISIBLE else View.GONE

        // 四格分段电量，25% 一档向上取整 —— 精确值在旁边那串百分比里
        rootView.findViewById<BatteryIconView>(R.id.view_battery_icon)?.setPercent(levelPct)
    }

    private fun pullCurrentState() {
        val now = Date()
        rootView.findViewById<TextView>(R.id.tv_time)?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        rootView.findViewById<TextView>(R.id.tv_date)?.text = SimpleDateFormat("MM月dd日 E", Locale.CHINA).format(now)

        updateAirplaneModeUI()
        updateEpdOnlyUI()

        val batteryStatus = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        applyBatteryStyle()
        updateBatteryUI(batteryStatus)

        val wifiManager = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        updateTogglesUI(wifiManager, btAdapter)

        val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        rootView.findViewById<SeekBar>(R.id.volume_slider)?.progress = curVol
        rootView.findViewById<TextView>(R.id.tv_volume_percent)?.text = "${(curVol * 100) / maxVol}%"

        val cachedTitle = NotificationReaderService.cachedTitle
        if (NotificationReaderService.isMediaAppActive || !cachedTitle.isNullOrEmpty()) {
            val appName = NotificationReaderService.cachedAppName.ifEmpty { "正在播放" }
            val artist = NotificationReaderService.cachedArtist ?: ""
            val isPlaying = NotificationReaderService.cachedIsPlaying
            updateMediaUI(appName, cachedTitle, artist, isPlaying)
        } else {
            clearMediaUI()
        }

        refreshNotifUI()
    }

    @Suppress("DEPRECATION")
    private fun updateTogglesUI(wifiManager: WifiManager, btAdapter: BluetoothAdapter?) {
        val btnWifi = rootView.findViewById<View>(R.id.btn_wifi)
        val tvWifiTitle = rootView.findViewById<TextView>(R.id.tv_wifi_title)
        val tvWifiSub = rootView.findViewById<TextView>(R.id.tv_wifi_sub)

        val btnBt = rootView.findViewById<View>(R.id.btn_bluetooth)
        val tvBtTitle = rootView.findViewById<TextView>(R.id.tv_bt_title)
        val tvBtSub = rootView.findViewById<TextView>(R.id.tv_bt_sub)

        if (wifiManager.isWifiEnabled) {
            btnWifi?.setBackgroundResource(R.drawable.bg_btn_on)
            tvWifiTitle?.setTextColor(Color.WHITE)
            tvWifiSub?.setTextColor(Color.WHITE)
            val info = wifiManager.connectionInfo
            if (info != null && info.networkId != -1 && !info.ssid.isNullOrEmpty() && info.ssid != "<unknown ssid>") {
                tvWifiSub?.text = info.ssid.replace("\"", "")
                tvWifiSub?.visibility = View.VISIBLE
            } else {
                tvWifiSub?.visibility = View.GONE
            }
        } else {
            btnWifi?.setBackgroundResource(R.drawable.bg_btn_off)
            tvWifiTitle?.setTextColor(Color.BLACK)
            tvWifiSub?.visibility = View.GONE
        }

        if (btAdapter?.isEnabled == true) {
            btnBt?.setBackgroundResource(R.drawable.bg_btn_on)
            tvBtTitle?.setTextColor(Color.WHITE)
            tvBtSub?.visibility = View.GONE
        } else {
            btnBt?.setBackgroundResource(R.drawable.bg_btn_off)
            tvBtTitle?.setTextColor(Color.BLACK)
            tvBtSub?.visibility = View.GONE
        }
    }

    private fun updateMediaUI(appName: String, title: String, artist: String, isPlaying: Boolean = false) {
        rootView.findViewById<View>(R.id.card_media)?.visibility = View.VISIBLE
        rootView.findViewById<TextView>(R.id.tv_media_app_name)?.text = appName
        rootView.findViewById<TextView>(R.id.tv_media_title)?.text = title
        rootView.findViewById<TextView>(R.id.tv_media_artist)?.text = artist
        rootView.findViewById<TextView>(R.id.btn_media_play)?.text = if (isPlaying) "||" else "▷"
    }

    private fun clearMediaUI() {
        rootView.findViewById<View>(R.id.card_media)?.visibility = View.GONE
    }

    /** 读 Settings.Secure 是跨进程调用，加个短缓存；授权后由 [invalidateNotifAccessCache] 清掉。 */
    private var notifAccessCached: Boolean? = null
    private var notifAccessCachedAt = 0L

    private fun isNotificationAccessGranted(): Boolean {
        val now = System.currentTimeMillis()
        notifAccessCached?.let {
            if (now - notifAccessCachedAt < NOTIF_ACCESS_CACHE_MS) return it
        }
        val listeners = Settings.Secure.getString(activity.contentResolver, "enabled_notification_listeners")
        val granted = listeners?.contains(activity.packageName) == true
        notifAccessCached = granted
        notifAccessCachedAt = now
        return granted
    }

    private fun invalidateNotifAccessCache() {
        notifAccessCached = null
    }

    private fun grantNotificationPermissionViaRoot() {
        if (!RootUtil.isRootAvailable()) {
            openNotificationSettings()
            return
        }
        val component = "${activity.packageName}/${NotificationReaderService::class.java.name}"
        val current = Settings.Secure.getString(activity.contentResolver, "enabled_notification_listeners") ?: ""
        if (current.contains(component)) {
            refreshNotifUI()
            return
        }
        val newValue = if (current.isEmpty()) component else "$current:$component"
        LauncherExecutors.io {
            try {
                val result = Shell.cmd("settings put secure enabled_notification_listeners \"$newValue\"").exec()
                mainHandler.post {
                    // 刚写完授权，缓存里的「未授权」已经过期
                    invalidateNotifAccessCache()
                    if (result.isSuccess) refreshNotifUI() else openNotificationSettings()
                }
            } catch (_: Exception) {
                mainHandler.post { openNotificationSettings() }
            }
        }
    }

    private fun openNotificationSettings() {
        if (isRedirecting) return
        if (isNotificationAccessGranted()) {
            refreshNotifUI()
            return
        }
        isRedirecting = true
        try {
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            rootView.postDelayed({ isRedirecting = false }, 1500)
        } catch (_: Exception) {
            isRedirecting = false
        }
    }

    /**
     * 通知区刷新。
     *
     * 不做 removeAllViews 全量重建：那样每来一条通知整块都要重画，墨水屏会闪一下，
     * 连发消息就是连闪好几下。改成卡片按包名复用，只有位置和文字变化时局部更新。
     */
    private fun refreshNotifUI() {
        if (rootView.visibility != View.VISIBLE) return
        val container = rootView.findViewById<LinearLayout>(R.id.notification_container) ?: return
        val clearBtn = rootView.findViewById<View>(R.id.btn_clear_notif)

        // 未授权 / 空列表：内容整体替换，直接重建（低频，做差量没意义）
        if (!isNotificationAccessGranted()) {
            notifCards.clear()
            container.removeAllViews()
            clearBtn?.visibility = View.GONE
            container.addView(TextView(activity).apply {
                text = "⚠ 点击开启「通知读取」权限\n才能显示消息和音乐卡片"
                textSize = 14f
                setTextColor(Color.parseColor("#E53935"))
                setPadding(0, dp(20), 0, dp(20))
                gravity = Gravity.CENTER
                tag = PLACEHOLDER_TAG
                setOnClickListener {
                    simulateClickFeedback(this)
                    if (RootUtil.isRootAvailable()) grantNotificationPermissionViaRoot() else openNotificationSettings()
                }
            })
            disableSystemHaptic(container)
            return
        }

        if (activeNotifications.isEmpty()) {
            notifCards.clear()
            container.removeAllViews()
            clearBtn?.visibility = View.GONE
            container.addView(TextView(activity).apply {
                text = "暂无通知"
                textSize = 14f
                setTextColor(Color.GRAY)
                setPadding(0, dp(20), 0, dp(20))
                gravity = Gravity.CENTER
                tag = PLACEHOLDER_TAG
            })
            disableSystemHaptic(container)
            return
        }

        clearBtn?.visibility = View.VISIBLE

        // 空态/未授权分支塞进去的占位提示不是通知卡片，这里不摘掉就会一直挂在卡片下面
        container.findViewWithTag<View>(PLACEHOLDER_TAG)?.let { container.removeView(it) }

        // 1) 摘掉已经没有对应通知的卡片
        val alive = HashSet<String>(activeNotifications.size)
        for (item in activeNotifications) alive.add(item.pkg)
        val iter = notifCards.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key !in alive) {
                container.removeView(entry.value)
                iter.remove()
            }
        }

        // 2) 逐条：已有卡片只更新内容，没有才新建
        for (index in activeNotifications.indices) {
            val item = activeNotifications[index]
            val existing = notifCards[item.pkg]
            val card = if (existing != null) {
                updateNotifCard(existing, item)
                existing
            } else {
                buildNotifCard(item).also { notifCards[item.pkg] = it }
            }
            // 新通知是插到头部的，位置不对时才动这张卡片
            if (container.getChildAt(index) !== card) {
                container.removeView(card)
                container.addView(card, index)
            }
        }
        disableSystemHaptic(container)
    }

    /** 一条通知卡片的子 View 引用，存进 root.tag 供更新时复用。 */
    private class NotifCardHolder(
        val root: View,
        val icon: ImageView,
        val title: TextView,
        val expand: TextView,
        val time: TextView,
        val close: TextView,
        val body: LinearLayout
    )

    private fun buildNotifCard(item: NotificationItem): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_btn_off)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val icon = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(8) }
            val matrix = ColorMatrix().apply { setSaturation(0f) }
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        val title = TextView(activity).apply {
            textSize = 15f
            setTextColor(Color.BLACK)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val expand = TextView(activity).apply {
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(dp(8), 0, dp(8), 0)
        }
        val time = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(12) }
        }
        val close = TextView(activity).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(dp(8), dp(2), dp(4), dp(2))
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon)
            addView(title)
            addView(expand)
            addView(time)
            addView(close)
            setOnClickListener {
                if (item.messages.size > 1) {
                    simulateClickFeedback(this)
                    item.isExpanded = !item.isExpanded
                    refreshNotifUI()
                }
            }
        }

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                leftMargin = dp(26)
            }
        }

        close.setOnClickListener {
            simulateClickFeedback(it, changeColor = true)
            activity.sendBroadcast(
                Intent("com.yota.CANCEL_NOTIF")
                    .putExtra("key", item.key)
                    .setPackage(activity.packageName)
            )
            activeNotifications.remove(item)
            refreshNotifUI()
        }

        body.setOnClickListener {
            simulateClickFeedback(it)
            item.contentIntent?.let { runCatching { it.send() } }
            // 点开看过了就把系统里的通知一并清掉，符合通知的常规行为
            activity.sendBroadcast(
                Intent("com.yota.CANCEL_NOTIF")
                    .putExtra("key", item.key)
                    .setPackage(activity.packageName)
            )
            onDismiss(false)
        }

        root.addView(header)
        root.addView(body)
        root.tag = NotifCardHolder(root, icon, title, expand, time, close, body)
        updateNotifCard(root, item)
        return root
    }

    private fun updateNotifCard(card: View, item: NotificationItem) {
        val holder = card.tag as? NotifCardHolder ?: return

        val iconDrawable = appIcon(item)
        if (iconDrawable != null) holder.icon.setImageDrawable(iconDrawable)
        else holder.icon.setImageResource(android.R.drawable.sym_def_app_icon)

        if (holder.title.text != item.title) holder.title.text = item.title

        val timeText = formatNotifTime(item.time)
        if (holder.time.text != timeText) holder.time.text = timeText

        val hasMultiple = item.messages.size > 1
        holder.expand.visibility = if (hasMultiple) View.VISIBLE else View.GONE
        if (hasMultiple) {
            val arrow = if (item.isExpanded) " ▴ " else " ▾ "
            if (holder.expand.text != arrow) holder.expand.text = arrow
        }

        // 正文内容（条数 / 展开状态 / 最后一条）没变就不重建，省掉一次重绘
        val last = item.messages.lastOrNull()
        val bodySignature = "${item.messages.size}|${item.isExpanded}|$last"
        if (holder.body.tag == bodySignature) return
        holder.body.tag = bodySignature

        holder.body.removeAllViews()
        if (last != null) {
            if (item.isExpanded) {
                for (msg in item.messages) {
                    holder.body.addView(TextView(activity).apply {
                        text = "• $msg"
                        textSize = 13f
                        setTextColor(Color.DKGRAY)
                        setPadding(0, dp(2), 0, dp(2))
                    })
                }
            } else {
                holder.body.addView(TextView(activity).apply {
                    text = if (item.messages.size > 1) "[${item.messages.size}条消息] $last" else last
                    textSize = 13f
                    setTextColor(Color.DKGRAY)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
        }
    }

    private fun appIcon(item: NotificationItem): Drawable? {
        if (item.icon == null) {
            item.icon = runCatching { activity.packageManager.getApplicationIcon(item.pkg) }.getOrNull()
        }
        return item.icon
    }

    /** 今天显示 HH:mm，昨天显示「昨天 HH:mm」，更早显示日期。 */
    private fun formatNotifTime(timeMs: Long): String {
        val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMs))
        val target = Calendar.getInstance().apply { timeInMillis = timeMs }
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            isSameDay(target, today) -> hm
            isSameDay(target, yesterday) -> "昨天 $hm"
            else -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(timeMs))
        }
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
}