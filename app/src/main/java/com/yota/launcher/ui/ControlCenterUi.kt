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
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
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
import java.util.Date
import java.util.Locale

@SuppressLint("NewApi", "MissingPermission", "SetTextI18n")
class ControlCenterUi(
    private val activity: Activity,
    private val onDismiss: (animateToHome: Boolean) -> Unit
) {

    companion object {
        // 【核心修复1】：将记录键改为 "PID_具体组件名"（String 类型）
        // 彻底打破 SplashActivity 陷阱，且绝不破坏按界面独立配置的功能！
        private val processStartModes = mutableMapOf<String, Int>()
    }

    data class NotificationItem(
        var key: String,
        val pkg: String,
        var title: String,
        var messages: MutableList<String>,
        var time: Long,
        var contentIntent: PendingIntent?,
        var isExpanded: Boolean = false
    )

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
    }

    fun refreshAll() {
        pullCurrentState()
        pullEpdState()
    }

    fun refreshNotifications() {
        refreshNotifUI()
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
    }

    private fun bindControlButtons() {
        val wifiManager = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

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

            Thread {
                if (!RootUtil.isRootAvailable()) {
                    mainHandler.post {
                        updateAirplaneModeUI()
                        activity.startActivity(
                            Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        onDismiss(false)
                    }
                    return@Thread
                }

                val newState = if (targetOn) 1 else 0
                Shell.cmd("settings put global airplane_mode_on $newState").exec()
                Shell.cmd("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state ${newState == 1}").exec()

                mainHandler.post {
                    updateAirplaneModeUI()
                    updateTogglesUI(wifiManager, btAdapter)
                }
            }.start()
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

            Thread {
                Shell.cmd("am force-stop $pkg").exec()
                Thread.sleep(300)
                if (launchIntent != null) {
                    mainHandler.post {
                        activity.startActivity(launchIntent)
                    }
                }
            }.start()
        }
    }

    private fun pullEpdState() {
        if (!YotaSdkAdapter.isYotaDevice()) return
        if (Build.VERSION.SDK_INT < 21) return

        // 【新增】：读取开关配置，如果在桌面 或者 开关被关闭，直接隐藏 EPD 卡片并退出逻辑
        val config = LauncherConfigStore(activity).load()
        if (!config.controlCenterEpd || activity.javaClass.simpleName == "LauncherActivity") {
            rootView.findViewById<View>(R.id.epd_card)?.visibility = View.GONE
            return
        }

        if (activity.javaClass.simpleName == "LauncherActivity") {
            rootView.findViewById<View>(R.id.epd_card)?.visibility = View.GONE
            return
        }

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

        if (targetPackage.isNullOrEmpty()) {
            rootView.findViewById<View>(R.id.epd_card)?.visibility = View.GONE
            return
        }

        val pkg = targetPackage
        val component = "ComponentInfo{$pkg/$targetActivity}"

        // 【核心修复2】：UI 优先渲染！在主线程立刻显示卡片占位，杜绝 300ms+ 的视觉卡顿
        currentEpdPackage = pkg
        rootView.findViewById<View>(R.id.epd_card)?.visibility = View.VISIBLE
        rootView.findViewById<TextView>(R.id.tv_epd_app_name)?.text = "获取中..."
        isCheckingPid = true
        updateEpdUiState()

        Thread {
            try {
                val pool = java.util.concurrent.Executors.newFixedThreadPool(3)

                val nameFuture = pool.submit(java.util.concurrent.Callable {
                    val pm = activity.packageManager
                    runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg)
                })

                val dbFuture = pool.submit(java.util.concurrent.Callable {
                    val entries = EpdParamsStore.load(activity)
                    entries.find { it.activity == component } ?: EpdEntry(
                        activity = component, mode = 0, contrast = 10, sharping = 2, blackStretch = 70, whiteStretch = 255, bright = 0
                    )
                })

                val pidFuture = pool.submit(java.util.concurrent.Callable {
                    var pid = -1
                    val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    val proc = am.runningAppProcesses?.find { it.processName == pkg }
                    if (proc != null) {
                        pid = proc.pid
                    } else {
                        // 【核心修复3】：回归极速的 ps 方案（耗时<20ms）
                        // 利用精准的行尾或正则匹配，既避开了 dumpsys 的沉重，又防范了 :push 子进程干扰
                        val out = Shell.cmd("ps").exec().out
                        val targetLine = out.find { it.trim().endsWith(" $pkg") }
                            ?: out.find { it.contains(" $pkg") }

                        if (targetLine != null) {
                            val parts = targetLine.trim().split(Regex("\\s+"))
                            pid = parts.getOrNull(1)?.toIntOrNull()
                                ?: parts.firstOrNull { it.matches(Regex("\\d+")) }?.toIntOrNull()
                                        ?: -1
                        } else {
                            // 兜底：如果安卓的 ps 确实发生截断，退化尝试 pidof
                            val outPidof = Shell.cmd("pidof $pkg").exec().out
                            if (outPidof.isNotEmpty()) {
                                pid = outPidof.joinToString(" ").split(Regex("\\s+")).firstOrNull { it.isNotBlank() }?.toIntOrNull() ?: -1
                            }
                        }
                    }
                    pid
                })

                val appLabel = nameFuture.get()
                val foundEntry = dbFuture.get()
                val pid = pidFuture.get()

                pool.shutdown()

                mainHandler.post {
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
                e.printStackTrace()
                mainHandler.post {
                    isCheckingPid = false
                    updateEpdUiState()
                }
            }
        }.start()
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

        if (entry.mode == 2) {
            if (isCheckingPid) {
                hint2Container?.visibility = View.VISIBLE
                hint2Msg?.text = "正在匹配底层进程状态..."
                btnRestart?.visibility = View.GONE
            } else {
                val stateKey = "${currentEpdPid}_${currentEpdEntry?.activity}"
                val startMode = processStartModes[stateKey] ?: 0

                if (startMode == 2) {
                    // 当底层接管动画已经生效时，彻底隐藏提示框，不再显示冗余文字
                    hint2Container?.visibility = View.GONE
                } else {
                    // 未生效时，依然保留重启提示
                    hint2Container?.visibility = View.VISIBLE
                    hint2Msg?.text = "首次切换动画模式需重启生效"
                    btnRestart?.visibility = View.VISIBLE
                }
            }
        } else {
            hint2Container?.visibility = View.GONE
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
        Thread {
            val entries = EpdParamsStore.load(activity).toMutableList()
            entries.removeAll { it.activity == entry.activity }
            entries.add(entry)
            EpdParamsStore.save(activity, entries)

            if (entry.mode == 2) {
                EpdParamsStore.softDeleteInProvider(activity, entry.activity)
            } else {
                EpdParamsStore.applyToProvider(activity, entry)
            }
        }.start()
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

    private fun updateBatteryUI(batteryStatus: Intent?) {
        val batLevel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 0
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val levelPct = if (scale > 0) (batLevel * 100) / scale else 0

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        rootView.findViewById<TextView>(R.id.tv_battery_percent)?.text = "$levelPct%"
        rootView.findViewById<View>(R.id.tv_battery_charging)?.visibility = if (isCharging) View.VISIBLE else View.GONE

        val fillView = rootView.findViewById<View>(R.id.view_battery_fill)
        val lp = fillView?.layoutParams as? LinearLayout.LayoutParams
        if (lp != null) {
            lp.weight = levelPct.toFloat().coerceIn(0f, 100f)
            fillView.layoutParams = lp
        }
    }

    private fun pullCurrentState() {
        val now = Date()
        rootView.findViewById<TextView>(R.id.tv_time)?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        rootView.findViewById<TextView>(R.id.tv_date)?.text = SimpleDateFormat("MM月dd日 E", Locale.CHINA).format(now)

        updateAirplaneModeUI()

        val batteryStatus = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
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

    private fun isNotificationAccessGranted(): Boolean {
        val listeners = Settings.Secure.getString(activity.contentResolver, "enabled_notification_listeners")
        return listeners?.contains(activity.packageName) == true
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
        Thread {
            try {
                val result = Shell.cmd("settings put secure enabled_notification_listeners \"$newValue\"").exec()
                mainHandler.post {
                    if (result.isSuccess) refreshNotifUI() else openNotificationSettings()
                }
            } catch (_: Exception) {
                mainHandler.post { openNotificationSettings() }
            }
        }.start()
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

    private fun refreshNotifUI() {
        if (rootView.visibility != View.VISIBLE) return
        val container = rootView.findViewById<LinearLayout>(R.id.notification_container) ?: return
        val clearBtn = rootView.findViewById<View>(R.id.btn_clear_notif)

        container.removeAllViews()

        if (!isNotificationAccessGranted()) {
            clearBtn?.visibility = View.GONE
            val authBtn = TextView(activity).apply {
                text = "⚠ 点击开启「通知读取」权限\n才能显示消息和音乐卡片"
                textSize = 14f
                setTextColor(Color.parseColor("#E53935"))
                setPadding(0, dp(20), 0, dp(20))
                gravity = Gravity.CENTER
                setOnClickListener {
                    simulateClickFeedback(this)
                    if (RootUtil.isRootAvailable()) grantNotificationPermissionViaRoot() else openNotificationSettings()
                }
            }
            container.addView(authBtn)
            disableSystemHaptic(container)
            return
        }

        if (activeNotifications.isEmpty()) {
            clearBtn?.visibility = View.GONE
            val emptyTv = TextView(activity).apply {
                text = "暂无通知"
                textSize = 14f
                setTextColor(Color.GRAY)
                setPadding(0, dp(20), 0, dp(20))
                gravity = Gravity.CENTER
            }
            container.addView(emptyTv)
        } else {
            clearBtn?.visibility = View.VISIBLE
            for (item in activeNotifications) {
                val notifView = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.bg_btn_off)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(8)
                    }
                    setPadding(dp(12), dp(10), dp(12), dp(10))

                    val header = LinearLayout(this@ControlCenterUi.activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL

                        val iconView = ImageView(this@ControlCenterUi.activity).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(8) }
                            try {
                                setImageDrawable(activity.packageManager.getApplicationIcon(item.pkg))
                            } catch(_: Exception) {
                                setImageResource(android.R.drawable.sym_def_app_icon)
                            }
                            val matrix = ColorMatrix().apply { setSaturation(0f) }
                            colorFilter = ColorMatrixColorFilter(matrix)
                        }
                        addView(iconView)

                        val titleView = TextView(this@ControlCenterUi.activity).apply {
                            this.text = item.title
                            textSize = 15f
                            setTextColor(Color.BLACK)
                            setTypeface(null, Typeface.BOLD)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        addView(titleView)

                        if (item.messages.size > 1) {
                            val expandIcon = TextView(this@ControlCenterUi.activity).apply {
                                text = if (item.isExpanded) " ▴ " else " ▾ "
                                textSize = 16f
                                setTextColor(Color.DKGRAY)
                                setPadding(dp(8), 0, dp(8), 0)
                            }
                            addView(expandIcon)
                        }

                        val timeView = TextView(this@ControlCenterUi.activity).apply {
                            this.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.time))
                            textSize = 12f
                            setTextColor(Color.GRAY)
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                rightMargin = dp(12)
                            }
                        }
                        addView(timeView)

                        val closeXBtn = TextView(this@ControlCenterUi.activity).apply {
                            text = "✕"
                            textSize = 14f
                            setTextColor(Color.GRAY)
                            setPadding(dp(8), dp(2), dp(4), dp(2))
                            setOnClickListener {
                                simulateClickFeedback(this, changeColor = true)
                                activity.sendBroadcast(Intent("com.yota.CANCEL_NOTIF").putExtra("key", item.key))
                                activeNotifications.remove(item)
                                refreshNotifUI()
                            }
                        }
                        addView(closeXBtn)

                        setOnClickListener {
                            if (item.messages.size > 1) {
                                simulateClickFeedback(this)
                                item.isExpanded = !item.isExpanded
                                refreshNotifUI()
                            }
                        }
                    }
                    addView(header)

                    val bodyContainer = LinearLayout(this@ControlCenterUi.activity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(4)
                            leftMargin = dp(26)
                        }
                    }

                    if (item.messages.isNotEmpty()) {
                        if (item.isExpanded) {
                            item.messages.forEach { msg ->
                                bodyContainer.addView(TextView(this@ControlCenterUi.activity).apply {
                                    text = "• $msg"
                                    textSize = 13f
                                    setTextColor(Color.DKGRAY)
                                    setPadding(0, dp(2), 0, dp(2))
                                })
                            }
                        } else {
                            bodyContainer.addView(TextView(this@ControlCenterUi.activity).apply {
                                val displayMsg = if (item.messages.size > 1) "[${item.messages.size}条消息] ${item.messages.last()}" else item.messages.last()
                                text = displayMsg
                                textSize = 13f
                                setTextColor(Color.DKGRAY)
                                maxLines = 2
                                ellipsize = TextUtils.TruncateAt.END
                            })
                        }
                    }
                    addView(bodyContainer)

                    bodyContainer.setOnClickListener {
                        simulateClickFeedback(this)
                        item.contentIntent?.let {
                            runCatching { it.send() }
                            onDismiss(false)
                        }
                    }
                }
                container.addView(notifView)
            }
            disableSystemHaptic(container)
        }
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
}