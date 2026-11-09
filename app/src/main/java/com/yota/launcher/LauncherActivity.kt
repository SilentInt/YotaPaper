package com.yota.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewStub
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import com.yota.launcher.data.AppEntry
import com.yota.launcher.data.AppRepository
import com.yota.launcher.epd.EpdManagerActivity
import com.yota.launcher.epd.EpdParamsStore
import com.yota.launcher.data.LauncherConfig
import com.yota.launcher.data.LauncherConfigStore
import com.yota.launcher.data.grid.GridSizing
import com.yota.launcher.ui.ControlCenterPanel
import com.yota.launcher.ui.EInkLauncherView
import com.yota.launcher.ui.IconLoader
import com.yota.launcher.ui.SettingsController
import com.yota.launcher.ui.StatusBarController
import com.yota.launcher.utils.RootPermissionGranter
import com.yota.launcher.utils.RootUtil
import com.yota.launcher.utils.TaskScope
import com.yota.launcher.yota.EInkSdk
import com.yota.launcher.yota.YotaSdkAdapter
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

object StartupTracer {
    private const val TAG = "StartupTrace"
    private var last = 0L

    fun start() {
        last = android.os.SystemClock.elapsedRealtime()
    }

    fun stage(name: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "[$name] +${now - last}ms")
        last = now
    }
}

class LauncherActivity : Activity() {

    private lateinit var store: LauncherConfigStore
    private lateinit var repository: AppRepository
    private lateinit var config: LauncherConfig

    // Activity 作用域任务，[TaskScope.cancel] 后不再回调
    private val tasks = TaskScope()

    // 上一次渲染过的主页条目，切换回来时先顶上，避免等后台查询
    private var lastHomeEntries: List<AppEntry> = emptyList()

    // 异步结果可能乱序，用递增代号保证只有最后一次查询的结果能贴上去
    private var homeRefreshToken = 0
    private var appsRefreshToken = 0
    private var recentRefreshToken = 0

    private lateinit var statusController: StatusBarController
    private lateinit var settingsController: SettingsController

    // Pages
    private lateinit var pageHome: View
    private lateinit var pageApps: View
    private lateinit var pageSettings: View

    // Home
    private lateinit var textTime: TextView
    private lateinit var textDate: TextView
    private lateinit var gridHome: EInkLauncherView

    // 两套顶部布局（经典居中 / 左右分栏），同一份 page_home 里各一份，靠 GONE 切换
    private lateinit var homeTopClassic: View
    private lateinit var homeTopSplit: View
    private lateinit var textTimeSplit: TextView
    private lateinit var textDateSplit: TextView

    // 状态栏与网格之间的横线。跟 showHomeDividers 一起开关，否则关掉网格分割线
    // 这条还在，界面上会出现一条关不掉的线
    private lateinit var homeTopDivider: View

    // Apps
    private lateinit var gridApps: EInkLauncherView
    private lateinit var textPage: TextView
    private lateinit var manageToggle: TextView
    private var appsAll: List<AppEntry> = emptyList()
    private var appsPageIndex = 0
    private var appsPageCount = 0
    private var currentPage = -1

    private var yotaDevice = false
    private val epdObserverHandler = Handler(Looper.getMainLooper())
    private val epdObserverRunnable = Runnable { syncEpdParamsFromProvider() }
    private val epdProviderObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            epdObserverHandler.removeCallbacks(epdObserverRunnable)
            epdObserverHandler.postDelayed(epdObserverRunnable, 150)
        }
    }
    private var manageMode = false
    private val manageSelected = mutableSetOf<String>()
    private val uninstallQueue = mutableListOf<String>()

    // Bottom bar
    private lateinit var bottomBar: View
    private lateinit var infoTime: TextView
    private lateinit var tabHome: View
    private lateinit var tabApps: View
    private lateinit var tabSettings: View
    private lateinit var tabHomeLabel: TextView
    private lateinit var tabAppsLabel: TextView
    private lateinit var tabSettingsLabel: TextView
    private lateinit var dotHome: View
    private lateinit var dotApps: View
    private lateinit var dotSettings: View
    private lateinit var btnLock: View

    // Manage bar
    private lateinit var manageBar: View
    private lateinit var manageHide: TextView
    private lateinit var manageShow: TextView
    private lateinit var manageUninstall: TextView
    private lateinit var manageDone: TextView

    // Action sheet
    private lateinit var actionOverlay: View
    private lateinit var actionTitle: TextView
    private lateinit var actionPin: TextView
    private lateinit var actionHide: TextView
    private lateinit var actionAppInfo: TextView
    private var selectedApp: AppEntry? = null

    // Recent tasks overlay
    private lateinit var recentOverlay: View
    private lateinit var recentPanel: View
    private lateinit var recentMessage: TextView
    private lateinit var recentGrid: EInkLauncherView
    private lateinit var recentCancel: TextView
    private lateinit var recentClear: TextView
    private var previousApp: AppEntry? = null
    // 最近一次查到的最近任务；面板一律用内存里的这份渲染，查询全走后台
    private var recentApps: List<AppEntry> = emptyList()
    // 上一应用：取全量结果里的第一个，不受「只显示最近 N 分钟」限制。
    // 取消时要回退到它，即使它因为时间窗没有出现在面板列表里。
    private var recentPrevious: AppEntry? = null
    // 是否已经查到过一次：没查过时不渲染，免得异步期间误报「暂无最近任务」
    private var recentLoaded = false
    private var lastHomeIntentTime = 0L
    private var lastHomeClickTime = 0L

    // Info overlay
    private lateinit var infoOverlay: View
    private lateinit var infoTitle: TextView
    private lateinit var infoText: TextView
    private lateinit var infoClose: View

    // Selection overlay
    private lateinit var selectOverlay: View
    private lateinit var selectTitle: TextView
    private lateinit var selectList: LinearLayout
    private var selectCallback: ((Int) -> Unit)? = null

    // WiFi overlay
    private lateinit var wifiOverlay: View
    private lateinit var wifiQr: ImageView
    private lateinit var wifiUrlText: TextView
    private lateinit var wifiHintText: TextView
    private var wifiServer: WifiTransferServer? = null
    // 二维码改后台生成后可能乱序，关掉面板时递增作废在途结果
    private var wifiQrToken = 0

    // First-use guide overlay
    private lateinit var guideOverlay: View
    private lateinit var guideStart: View

    // Donation QR
    private lateinit var donationQr: ImageView
    private lateinit var guideDonationQr: ImageView
    private lateinit var donationPromptQr: ImageView
    private var donationQrReady = false

    private var settingsInflated = false
    private var appsInflated = false
    private var appsLoaded = false
    private var coldStartResume = true

    // 最近一次实际使用的自适应行列（屏幕尺寸变化时用于判断是否需要重绑）
    private var currentHomeGrid = 0 to 0
    private var currentAppGrid = 0 to 0

    // 开屏动画防抖锁
    private var lastScreenOnAnimTime = 0L

    // Donation prompt
    private lateinit var donationOverlay: View
    private lateinit var donationClose: TextView
    private var donationCloseEnabled = false

    // About-page switch
    private lateinit var rowDonationPrompt: View
    private lateinit var settingsDonationPromptValue: TextView

    private val donationCloseRunnable = Runnable {
        donationCloseEnabled = true
        donationClose.text = getString(R.string.info_close)
        donationClose.setTextColor(color(R.color.ink))
        donationClose.setOnClickListener { donationOverlay.visibility = View.GONE }
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateClock()
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val pkg = intent?.data?.schemeSpecificPart ?: return
            Log.i(TAG, "package changed: $pkg")
            repository.invalidatePackage(pkg)
            IconLoader.invalidatePackage(pkg)
            refreshHome()
            if (appsLoaded) refreshApps()
        }
    }

    // ============ 控制中心（并入主页窗口的浮层；桌面场景动画连贯） ============
    private var controlPanel: ControlCenterPanel? = null

    private val controlPanelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.yota.OPEN_PANEL" -> controlPanel?.openPanel()
                "com.yota.ACTION_CLOSE_PANEL" -> controlPanel?.closePanel(animateToHome = true)
            }
        }
    }
    // ========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        StartupTracer.start()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)
        StartupTracer.stage("setContentView")
        installGridLayoutHook()

        store = LauncherConfigStore(this)
        repository = AppRepository(this)
        config = store.load()
        yotaDevice = YotaSdkAdapter.isYotaDevice()
        StartupTracer.stage("store/repo/config")

        if (yotaDevice) {
            YotaSdkAdapter.optOutOfSystemEpdParamsAsync(this) {
                if (!isFinishing) applyRefreshMode(config)
            }
            EpdParamsStore.ensurePrefsReadable(this)
            startupSyncEpdParams()
            contentResolver.registerContentObserver(
                Uri.parse(EpdParamsStore.URI), true, epdProviderObserver
            )
        }
        StartupTracer.stage("epd-observe/optout")

        IconLoader.initialize(applicationContext)
        IconLoader.configure(config)
        StartupTracer.stage("iconloader-init")

        bindViews()
        StartupTracer.stage("bindViews")

        window.decorView.post { ensureDonationQr() }
        StartupTracer.stage("postDonationQr")

        applyRefreshMode(config)
        StartupTracer.stage("applyRefreshMode")

        setupControllers()
        StartupTracer.stage("setupControllers")

        setupTabs()
        StartupTracer.stage("setupTabs")

        setupLock()
        StartupTracer.stage("setupLock")

        setupActionSheet()
        StartupTracer.stage("setupActionSheet")

        setupRecentOverlay()
        StartupTracer.stage("setupRecentOverlay")

        setupInfoOverlay()
        StartupTracer.stage("setupInfoOverlay")

        setupSelectOverlay()
        StartupTracer.stage("setupSelectOverlay")

        setupWifiTransfer()
        StartupTracer.stage("setupWifiTransfer")

        setupManage()
        StartupTracer.stage("setupManage")

        setupGrids()
        StartupTracer.stage("setupGrids")

        refreshHome()
        StartupTracer.stage("refreshHome")

        epdObserverHandler.postDelayed({
            if (!appsLoaded) refreshApps()
        }, 500)
        StartupTracer.stage("postRefreshApps")

        selectPage(PAGE_HOME, animate = false)
        StartupTracer.stage("selectPage")

        registerTimeReceiver()
        registerPackageReceiver()
        setupControlPanel()
        updateClock()
        statusController.update()
        StartupTracer.stage("clock/status")

        lastHomeIntentTime = System.currentTimeMillis()

        maybeShowGuide()
        StartupTracer.stage("guide")

        // ================= 预热 Root Shell =================
        // 用到 Root 的功能（彻底清理后台 / 控制中心）没开时不预热：
        // Shell.getShell() 会唤起 Magisk 授权弹窗，每次冷启动都跑一遍属于打扰
        if (config.rootClear || config.controlCenterEnabled) {
            tasks.io {
                RootUtil.isRootAvailable()
                RootPermissionGranter.ensureGranted(applicationContext)
            }
        }
        // ==================================================

        // 预热最近任务列表：面板打开时先用这份缓存渲染，不必等 usage 查询，
        // 也避免首帧先闪一下「暂无最近任务」再重画（墨水屏上这个闪很明显）。
        // 延后到冷启动批次之后，不跟主页/应用列表的查询抢 usage 服务。
        epdObserverHandler.postDelayed({ refreshRecentApps() }, 1200)

        // ================= 控制中心服务启动（根据配置） =================
        if (config.controlCenterEnabled) {
            startControlCenterService()
        }
        // =============================================================
    }

    // ===================== 控制中心服务管理 =====================
    private fun startControlCenterService() {
        startService(Intent(this, ControlCenterService::class.java))
    }

    private fun stopControlCenterService() {
        stopService(Intent(this, ControlCenterService::class.java))
    }

    // ============ 控制中心浮层装配 ============

    private fun setupControlPanel() {
        // 必须在 onCreate 里同步建好：浮层的视图树要和桌面首帧一起完成布局，
        // 否则下拉那一刻才 addView，窗口首次布局会和 EPD 动画挤在同一帧，
        // 动画被武装在还没 layout 的树上，表现为第一次下拉看不到面板
        controlPanel = ControlCenterPanel(this)
        controlPanel?.attach()
        val filter = IntentFilter().apply {
            addAction("com.yota.OPEN_PANEL")
            addAction("com.yota.ACTION_CLOSE_PANEL")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlPanelReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(controlPanelReceiver, filter)
        }
    }

    // -------------------- 原有方法 --------------------
    private fun maybeShowGuide() {
        if (store.isGuideShown()) return
        guideOverlay.visibility = View.VISIBLE
        guideStart.setOnClickListener {
            guideOverlay.visibility = View.GONE
            store.setGuideShown()
        }
    }

    override fun onDestroy() {
        // 销毁后不再接受后台回调
        tasks.cancel()
        if (yotaDevice) {
            contentResolver.unregisterContentObserver(epdProviderObserver)
            epdObserverHandler.removeCallbacks(epdObserverRunnable)
        }
        super.onDestroy()
        window.decorView.removeCallbacks(donationCloseRunnable)
        statusController.destroy()
        runCatching { unregisterReceiver(timeReceiver) }
        runCatching { unregisterReceiver(packageReceiver) }
        runCatching { unregisterReceiver(controlPanelReceiver) }
        controlPanel?.detach()
        controlPanel = null
        wifiServer?.stop()
        wifiServer = null
    }

    // 传 applicationContext 而非 Activity，避免后台任务持有 Activity 引用
    private fun syncEpdParamsFromProvider() {
        if (!yotaDevice || !store.autoApplyEpdParamsEnabled()) return
        val appContext = applicationContext
        val launcherComponent = ComponentName(appContext, LauncherActivity::class.java).toString()
        tasks.io { EpdParamsStore.mergeFromProvider(appContext, launcherComponent) }
    }

    private fun startupSyncEpdParams() {
        if (!yotaDevice || !store.autoApplyEpdParamsEnabled()) return
        val appContext = applicationContext
        val launcherComponent = ComponentName(appContext, LauncherActivity::class.java).toString()
        epdObserverHandler.postDelayed({
            tasks.io { EpdParamsStore.syncAndApply(appContext, launcherComponent) }
        }, 300)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleHomeIntent(intent)
    }

    private fun handleHomeIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_MAIN) return
        val isHome = intent.categories?.contains(Intent.CATEGORY_HOME) == true ||
                intent.categories?.contains(EPD_HOME_CATEGORY) == true
        if (!isHome) return

        if (donationOverlay.visibility == View.VISIBLE) return

        val now = System.currentTimeMillis()
        if (recentOverlay.visibility == View.VISIBLE) {
            hideRecentTasks()
            lastHomeIntentTime = 0L
            return
        }
        if (now - lastHomeIntentTime in 1..799) {
            lastHomeIntentTime = 0L
            showRecentTasks()
        } else {
            lastHomeIntentTime = now
        }

        maybeShowDonationPrompt()
    }

    private fun maybeShowDonationPrompt() {
        if (!store.isDonationPromptEnabled()) return
        val count = store.incrementHomePressCount()
        Log.d(TAG, "home press count=$count")
        if (count >= 50) {
            store.resetHomePressCount()
            showDonationPrompt()
        }
    }

    private fun showDonationPrompt() {
        ensureDonationQr()
        donationCloseEnabled = false
        donationClose.text = getString(R.string.donation_close_wait)
        donationClose.setTextColor(color(R.color.gray))
        donationClose.setOnClickListener(null)
        donationOverlay.visibility = View.VISIBLE
        window.decorView.postDelayed(donationCloseRunnable, 5000L)
    }

    override fun onResume() {
        StartupTracer.stage("onResume-enter")
        super.onResume()
        ControlCenterService.launcherTop = true
        if (yotaDevice) {
            YotaSdkAdapter.optOutOfSystemEpdParamsAsync(this) {
                if (!isFinishing) applyRefreshMode(config)
            }
        }
        val newConfig = store.load()
        val configChanged = newConfig != config
        if (configChanged) {
            config = newConfig
            settingsController.updateValues(config)
            applyRefreshMode(config)
            refreshHome()
            refreshApps()
        } else {
            applyRefreshMode(config)
            if (!coldStartResume) refreshHome()
        }
        // 回到桌面这一刻正是「上一个应用」的判定时刻：顺手刷一次最近任务缓存，
        // 面板打开时用它填 previousApp 才是对的（冷启动交给 onCreate 的预热）
        if (!coldStartResume) refreshRecentApps()
        updateClock()
        statusController.update()
        coldStartResume = false
        StartupTracer.stage("onResume-before-screenAnim")
        maybePlayScreenOnAnimation()
        StartupTracer.stage("onResume-after-screenAnim")

        // 从系统设置页（通知读取授权等）返回时补刷浮层
        controlPanel?.onHostResume()

        // 确保控制中心服务状态与配置一致
        if (!config.controlCenterEnabled) {
            stopControlCenterService()
        }
    }

    override fun onPause() {
        super.onPause()
        ControlCenterService.launcherTop = false
    }

    private fun maybePlayScreenOnAnimation() {
        val now = System.currentTimeMillis()
        if (now - lastScreenOnAnimTime < 1000L) return

        if (!yotaDevice) {
            Log.d(TAG, "screenOnAnimation: skipped (not a Yota device)")
            return
        }
        if (!config.screenOnAnimation) return
        if (config.refreshMode != 0) {
            Log.d(TAG, "screenOnAnimation: skipped (refreshMode=${config.refreshMode})")
            return
        }
        val anim = animationForStyle(config.screenOnAnimationStyle)
        if (anim == EInkSdk.ANIM_OFF) return

        Log.d(TAG, "screenOnAnimation: style=${config.screenOnAnimationStyle} anim=$anim")
        lastScreenOnAnimTime = now
        EInkSdk.applyScreenAnimation(window.decorView, anim)
    }

    private fun animationForStyle(style: Int): Int = when (style) {
        2 -> EInkSdk.ANIM_HORIZONTAL_OPEN
        3 -> EInkSdk.ANIM_HORIZONTAL_CLOSE
        4 -> EInkSdk.ANIM_VERTICAL_TOP
        5 -> EInkSdk.ANIM_VERTICAL_OPEN
        6 -> EInkSdk.ANIM_VERTICAL_CLOSE
        else -> EInkSdk.ANIM_HORIZONTAL_LEFT
    }

    // ------------------------------------------------------------------ Setup

    private fun bindViews() {
        pageHome = findViewById(R.id.pageHome)

        textTime = findViewById(R.id.textTime)
        textDate = findViewById(R.id.textDate)
        gridHome = findViewById(R.id.gridHome)
        homeTopDivider = findViewById(R.id.homeTopDivider)
        homeTopClassic = findViewById(R.id.homeTopClassic)
        homeTopSplit = findViewById(R.id.homeTopSplit)
        textTimeSplit = findViewById(R.id.textTimeSplit)
        textDateSplit = findViewById(R.id.textDateSplit)

        bottomBar = findViewById(R.id.bottomBar)
        infoTime = findViewById(R.id.infoTime)
        tabHome = findViewById(R.id.tabHome)
        tabApps = findViewById(R.id.tabApps)
        tabSettings = findViewById(R.id.tabSettings)
        tabHomeLabel = findViewById(R.id.tabHomeLabel)
        tabAppsLabel = findViewById(R.id.tabAppsLabel)
        tabSettingsLabel = findViewById(R.id.tabSettingsLabel)
        dotHome = findViewById(R.id.dotHome)
        dotApps = findViewById(R.id.dotApps)
        dotSettings = findViewById(R.id.dotSettings)
        btnLock = findViewById(R.id.btnLock)

        manageBar = findViewById(R.id.manageBar)
        manageHide = findViewById(R.id.manageHide)
        manageShow = findViewById(R.id.manageShow)
        manageUninstall = findViewById(R.id.manageUninstall)
        manageDone = findViewById(R.id.manageDone)

        actionOverlay = findViewById(R.id.actionOverlay)
        actionTitle = findViewById(R.id.actionTitle)
        actionPin = findViewById(R.id.actionPin)
        actionHide = findViewById(R.id.actionHide)
        actionAppInfo = findViewById(R.id.actionAppInfo)

        recentOverlay = findViewById(R.id.recentOverlay)
        recentPanel = findViewById(R.id.recentPanel)
        recentMessage = findViewById(R.id.recentMessage)
        recentGrid = findViewById(R.id.recentGrid)
        recentCancel = findViewById(R.id.recentCancel)
        recentClear = findViewById(R.id.recentClear)

        infoOverlay = findViewById(R.id.infoOverlay)
        infoTitle = findViewById(R.id.infoTitle)
        infoText = findViewById(R.id.infoText)
        infoClose = findViewById(R.id.infoClose)

        selectOverlay = findViewById(R.id.selectOverlay)
        selectTitle = findViewById(R.id.selectTitle)
        selectList = findViewById(R.id.selectList)

        wifiOverlay = findViewById(R.id.wifiOverlay)
        wifiQr = findViewById(R.id.wifiQr)
        wifiUrlText = findViewById(R.id.wifiUrlText)
        wifiHintText = findViewById(R.id.wifiHintText)

        guideOverlay = findViewById(R.id.guideOverlay)
        guideStart = findViewById(R.id.guideStart)

        donationQr = findViewById(R.id.donationQr)
        guideDonationQr = findViewById(R.id.guideDonationQr)

        donationOverlay = findViewById(R.id.donationOverlay)
        donationPromptQr = findViewById(R.id.donationPromptQr)
        donationClose = findViewById(R.id.donationClose)
    }

    private fun ensureDonationQr() {
        if (donationQrReady) return
        val size = dp(240)
        tasks.worker(
            work = { QrCodes.generate(DONATION_WECHAT_URI, size) },
            after = { qr ->
                donationQr.setImageBitmap(qr)
                guideDonationQr.setImageBitmap(qr)
                donationPromptQr.setImageBitmap(qr)
                donationQrReady = true
            }
        )
    }

    private fun setupControllers() {
        statusController = StatusBarController(this).also {
            it.bind()
            it.setup()
            it.setIconStyle(config.homeTopIconStyle)
        }
    }

    private fun ensureAppsPage() {
        if (appsInflated) return
        findViewById<ViewStub>(R.id.pageAppsStub).inflate()
        pageApps = findViewById(R.id.pageApps)
        gridApps = findViewById(R.id.gridApps)
        textPage = findViewById(R.id.textPage)
        manageToggle = findViewById(R.id.manageToggle)
        appsInflated = true

        manageToggle.setOnClickListener {
            if (manageMode) exitManageMode() else enterManageMode()
        }
        gridApps.setOnItemInteractionListener(object : EInkLauncherView.OnItemInteractionListener {
            override fun onItemClick(entry: AppEntry) {
                if (manageMode) toggleManageSelect(entry) else launchApp(entry)
            }

            override fun onItemLongClick(entry: AppEntry) {
                if (!manageMode) showActionSheet(entry)
            }
        })

        gridApps.setOnPageChangeListener(object : EInkLauncherView.OnPageChangeListener {
            override fun onNextPage() {
                if (appsPageIndex < appsPageCount - 1) showAppsPage(appsPageIndex + 1)
                else selectPage(PAGE_SETTINGS, animate = true)
            }
            override fun onPrevPage() {
                if (appsPageIndex > 0) showAppsPage(appsPageIndex - 1)
                else selectPage(PAGE_HOME, animate = true)
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureSettingsPage() {
        if (settingsInflated) return
        findViewById<ViewStub>(R.id.pageSettingsStub).inflate()
        pageSettings = findViewById(R.id.pageSettings)
        rowDonationPrompt = findViewById(R.id.rowDonationPrompt)
        settingsDonationPromptValue = findViewById(R.id.settingsDonationPromptValue)
        settingsInflated = true

        settingsController = SettingsController(
            this,
            { config },
            ::onConfigChanged,
            ::showSelectOverlay,
            showYotaSettings = yotaDevice
        ).also {
            it.bind()
            it.setup(config)
        }

        if (yotaDevice) {
            findViewById<View>(R.id.rowEpdManager).setOnClickListener {
                startActivity(Intent(this, EpdManagerActivity::class.java))
            }
        } else {
            findViewById<View>(R.id.rowEpdManager).visibility = View.GONE
        }

        findViewById<View>(R.id.rowHelp).setOnClickListener {
            rowDonationPrompt.visibility = View.GONE
            showInfo(getString(R.string.settings_help), getString(R.string.help_text))
        }
        findViewById<View>(R.id.rowAbout).setOnClickListener {
            rowDonationPrompt.visibility = View.VISIBLE
            updateDonationPromptValue()
            showInfo(getString(R.string.settings_about), getString(R.string.about_text))
        }
        rowDonationPrompt.setOnClickListener {
            store.setDonationPromptEnabled(!store.isDonationPromptEnabled())
            updateDonationPromptValue()
        }

        val gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 80
            private val SWIPE_VELOCITY_THRESHOLD = 100
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) {
                        selectPage(PAGE_APPS, animate = true)
                        return true
                    }
                }
                return false
            }
        })
        pageSettings.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    private fun onConfigChanged(newConfig: LauncherConfig, affected: Int) {
        val oldConfig = config
        config = newConfig
        store.save(config)
        settingsController.updateValues(config)

        if (affected and SettingsController.AFFECT_ICONS != 0) {
            IconLoader.configure(config)
            refreshHome()
            refreshApps()
        }
        if (affected and SettingsController.AFFECT_HOME != 0) refreshHome()
        if (affected and SettingsController.AFFECT_APPS != 0) refreshApps()
        if (affected and SettingsController.AFFECT_REFRESH != 0) applyRefreshMode(config)

        // 控制中心状态变化
        if (oldConfig.controlCenterEnabled != newConfig.controlCenterEnabled) {
            if (newConfig.controlCenterEnabled) {
                startControlCenterService()
            } else {
                stopControlCenterService()
            }
        }
    }

    private fun applyRefreshMode(cfg: LauncherConfig = config) {
        if (!yotaDevice) {
            Log.d(TAG, "applyRefreshMode: skipped (not a Yota device)")
            return
        }
        val mode = cfg.refreshMode.coerceIn(0, 2)
        Log.d(TAG, "applyRefreshMode: mode=$mode")
        EInkSdk.setUpdateMode(window.decorView, mode)
    }

    // ------------------------------------------------------------------ Home / Apps grids

    private fun setupGrids() {
        gridHome.setOnItemInteractionListener(object : EInkLauncherView.OnItemInteractionListener {
            override fun onItemClick(entry: AppEntry) = launchApp(entry)
            override fun onItemLongClick(entry: AppEntry) = showActionSheet(entry)
        })
        gridHome.setOnPageChangeListener(object : EInkLauncherView.OnPageChangeListener {
            override fun onNextPage() = selectPage(PAGE_APPS, animate = true)
            override fun onPrevPage() = Unit
        })
    }

    /**
     * Resolved home grid for the current screen. A stored value of 0 means "auto":
     * the launcher picks the most suitable grid for the measured panel
     * (see [GridSizing]) and keeps it until the user picks a specific value.
     */
    private fun resolvedHomeGrid(): Pair<Int, Int> {
        val widthDp = gridWidthDp(gridHome, 52f)
        val heightDp = gridHeightDp(gridHome, contentHeightDp() - 240f)
        val cols = GridSizing.resolveColumns(config.homeColumns, widthDp, GridSizing.columnCap(widthDp))
        // 主页行数上限按网格实际高度计算，避免选了过多行导致每行过窄。
        val homeRowCap = GridSizing.homeRowCap(heightDp)
        val rows = GridSizing.resolveHomeRows(config.homeRows, heightDp, cap = homeRowCap)
        return cols to rows
    }

    private fun resolvedAppGrid(): Pair<Int, Int> {
        val widthDp = gridWidthDp(gridApps, 48f)
        val heightDp = gridHeightDp(gridApps, contentHeightDp() - 40f)
        val cols = GridSizing.resolveColumns(config.columns, widthDp, GridSizing.columnCap(widthDp))
        val rows = GridSizing.resolveRows(config.rows, heightDp, cap = GridSizing.rowCap(heightDp))
        return cols to rows
    }

    /** 优先用网格自身的已测量尺寸，退化时用屏幕宽度减去布局边距。 */
    private fun gridWidthDp(grid: View, horizontalMarginsDp: Float): Float {
        val w = grid.width
        if (w > 0) return pxToDp(w)
        return (displayWidthDp() - horizontalMarginsDp).coerceAtLeast(160f)
    }

    /** 优先用网格自身的已测量高度；尚未布局时（冷启动）用估算值。 */
    private fun gridHeightDp(grid: View, fallbackDp: Float): Float {
        val h = grid.height
        if (h > 0) return pxToDp(h)
        return fallbackDp.coerceAtLeast(120f)
    }

    private fun displayWidthDp(): Float {
        val w = resources.displayMetrics.widthPixels
        return if (w > 0) pxToDp(w) else 360f
    }

    private fun contentHeightDp(): Float {
        val h = resources.displayMetrics.heightPixels
        return if (h > 0) pxToDp(h) else 480f
    }

    private fun displayHeightDp(): Float = contentHeightDp()

    /** 屏幕尺寸或方向变化后，重算自适应行列 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::config.isInitialized) return
        // 布局尺寸在配置变化后才更新，等一帧再算
        window.decorView.post {
            if (isFinishing) return@post
            // 视图可能尚未绑定（初始化早期收到配置变化），此时直接跳过。
            if (!::gridHome.isInitialized || !::gridApps.isInitialized) return@post
            val homeGrid = resolvedHomeGrid()
            if (homeGrid.first != currentHomeGrid.first || homeGrid.second != currentHomeGrid.second) {
                currentHomeGrid = homeGrid
                refreshHome()
            }
            val appGrid = resolvedAppGrid()
            if (appsLoaded && (appGrid.first != currentAppGrid.first || appGrid.second != currentAppGrid.second)) {
                currentAppGrid = appGrid
                refreshApps()
            }
            if (::settingsController.isInitialized) settingsController.updateValues(config)
        }
    }

    /**
     * Cold start is measured before the grids exist, so the first grid uses a
     * conservative estimate. Once the window has been laid out, resolve the real
     * panel size and re-apply the automatic grid if it changed.
     */
    private fun installGridLayoutHook() {
        val target = window.decorView
        target.viewTreeObserver.addOnGlobalLayoutListener(object :
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!::config.isInitialized) return
                if (target.viewTreeObserver.isAlive) {
                    target.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
                window.decorView.post {
                    if (isFinishing) return@post
                    // 冷启动时该回调可能早于 setupControllers() 执行，
                    // 控制器未创建前不要触碰它（lateinit 未初始化会崩溃）。
                    if (!::settingsController.isInitialized) return@post
                    val homeGrid = resolvedHomeGrid()
                    if (homeGrid != currentHomeGrid) {
                        currentHomeGrid = homeGrid
                        refreshHome()
                    }
                    if (appsLoaded) {
                        val appGrid = resolvedAppGrid()
                        if (appGrid != currentAppGrid) {
                            currentAppGrid = appGrid
                            refreshApps()
                        }
                    }
                    // 网格尺寸确定后，设置页显示的实际行列数才准确。
                    settingsController.updateValues(config)
                }
            }
        })
    }

    private fun pxToDp(px: Int): Float = px / resources.displayMetrics.density

    private fun refreshHome() {
        val (columns, rows) = resolvedHomeGrid()
        currentHomeGrid = columns to rows
        gridHome.configure(columns, rows)
        // 横线跟着主页分割线开关走。用 INVISIBLE 而不是 GONE：GONE 的子 View 在
        // LinearLayout 里会被跳过 measure/layout，32dp 边距跟着失效，网格会整体上移
        homeTopDivider.visibility =
            if (config.showHomeDividers) View.VISIBLE else View.INVISIBLE
        applyHomeTopStyle()
        // 图标风格不算「版式」，但同在顶部这一块，跟着一起刷最省事；
        // setIconStyle 内部有等值早退，没改风格时什么都不会发生
        if (::statusController.isInitialized) statusController.setIconStyle(config.homeTopIconStyle)
        StartupTracer.stage("refreshHome-config")

        val limit = columns * rows
        val token = ++homeRefreshToken

        // 先把上一次的结果顶上：后台查询要几十~几百毫秒，
        // 不先画的话切换页面时格子会先空着、或留着上一个应用的图标
        if (lastHomeEntries.isNotEmpty()) {
            gridHome.setApps(
                pageApps = lastHomeEntries,
                selection = emptySet(),
                manageMode = false,
                showDividers = config.showHomeDividers,
                hidden = emptySet()
            )
        }

        // loadApps 要全量 queryIntentActivities，冷启动最重的一步：放到后台，
        // 网格先摆好布局，数据回来再回填，避免首帧被查询阻塞
        tasks.worker(
            work = { repository.sortHomeApps(repository.loadApps(), limit) },
            after = { entries ->
                // 已有更新的刷新在跑，本次结果作废
                if (token == homeRefreshToken) {
                    lastHomeEntries = entries
                    gridHome.setApps(
                        pageApps = entries,
                        selection = emptySet(),
                        manageMode = false,
                        showDividers = config.showHomeDividers,
                        hidden = emptySet()
                    )
                    StartupTracer.stage("refreshHome-setApps")
                }
            }
        )
    }

    // 切换顶部风格：GONE 的那一套不参与 measure/layout，剩下的那套自然顶到顶部
    private fun applyHomeTopStyle() {
        if (!::homeTopSplit.isInitialized) return
        val split = config.homeTopStyle == 1
        homeTopClassic.visibility = if (split) View.GONE else View.VISIBLE
        homeTopSplit.visibility = if (split) View.VISIBLE else View.GONE

        // 分割线上方留白 = 顶部块上方留白，顶部块就在「屏幕顶 → 分割线」之间居中，
        // 与块高无关，所以时间字号改了也不用重算
        val lp = homeTopDivider.layoutParams as? LinearLayout.LayoutParams ?: return
        val gap = resources.getDimensionPixelSize(
            if (split) R.dimen.home_top_gap_split else R.dimen.home_top_gap_classic
        )
        if (lp.topMargin != gap) {
            lp.topMargin = gap
            homeTopDivider.layoutParams = lp
        }
    }

    // 同 refreshHome，查询走后台
    private fun refreshApps() {
        ensureAppsPage()
        appsLoaded = true

        val includeHidden = manageMode
        val (columns, rows) = resolvedAppGrid()
        currentAppGrid = columns to rows
        gridApps.configure(columns, rows)
        StartupTracer.stage("refreshApps-load")

        // 手上已有列表就先按它画一次，抽屉不会在异步查询期间空白。
        // 页码跟异步完成后的 showAppsPage(0) 保持一致，避免中间多跳一次
        val pageSize = columns * rows
        if (appsAll.isNotEmpty()) {
            appsPageCount = ceil(appsAll.size.toDouble() / pageSize).toInt()
            showAppsPage(0)
        }

        val token = ++appsRefreshToken
        tasks.worker(
            work = { if (includeHidden) repository.loadAllApps() else repository.loadApps() },
            after = { list ->
                if (token == appsRefreshToken) {
                    appsAll = list
                    appsPageCount =
                        if (list.isEmpty()) 0
                        else ceil(list.size.toDouble() / pageSize).toInt()
                    showAppsPage(0)
                    StartupTracer.stage("refreshApps-showPage")
                }
            }
        )
    }

    private fun showAppsPage(index: Int) {
        val (columns, rows) = resolvedAppGrid()
        val pageSize = columns * rows
        val clamped = index.coerceIn(0, (appsPageCount - 1).coerceAtLeast(0))
        val previous = appsPageIndex
        appsPageIndex = clamped
        val start = clamped * pageSize
        val end = minOf(start + pageSize, appsAll.size)
        val page = ArrayList<AppEntry?>(pageSize)
        if (start < end) page.addAll(appsAll.subList(start, end))
        while (page.size < pageSize) page.add(null)

        gridApps.setApps(
            pageApps = page,
            selection = manageSelected,
            manageMode = manageMode,
            showDividers = config.showAppDividers,
            hidden = repository.hiddenPackages()
        )
        val pageText = if (appsPageCount > 0) "${clamped + 1}/$appsPageCount" else "0/0"
        if (textPage.text != pageText) textPage.text = pageText

        if (previous != clamped) {
            val delta = clamped - previous
            val anim = when (config.pageAnimation) {
                2 -> EInkSdk.ANIM_HORIZONTAL_OPEN
                3 -> EInkSdk.ANIM_HORIZONTAL_CLOSE
                4 -> if (delta > 0) EInkSdk.ANIM_VERTICAL_BOTTOM else EInkSdk.ANIM_VERTICAL_TOP
                5 -> EInkSdk.ANIM_VERTICAL_OPEN
                6 -> EInkSdk.ANIM_VERTICAL_CLOSE
                0 -> EInkSdk.ANIM_OFF
                else -> if (delta > 0) EInkSdk.ANIM_HORIZONTAL_RIGHT else EInkSdk.ANIM_HORIZONTAL_LEFT
            }
            if (yotaDevice && config.refreshMode == 0) {
                Log.d(TAG, "showAppsPage: pageChanged $previous -> $clamped delta=$delta type=${config.pageAnimation} anim=$anim")
                EInkSdk.applyPageTurn(gridApps, anim)
            }
        }
    }

    // ------------------------------------------------------------------ Tabs

    private fun setupTabs() {
        tabHome.setOnClickListener { onHomeClick() }
        tabApps.setOnClickListener { selectPage(PAGE_APPS, animate = true) }
        tabSettings.setOnClickListener { selectPage(PAGE_SETTINGS, animate = true) }
    }

    private fun onHomeClick() {
        if (recentOverlay.visibility == View.VISIBLE) {
            hideRecentTasks()
            lastHomeClickTime = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastHomeClickTime in 1..799) {
            lastHomeClickTime = 0L
            showRecentTasks()
        } else {
            lastHomeClickTime = now
            selectPage(PAGE_HOME, animate = true)
        }
    }

    private fun selectPage(page: Int, animate: Boolean = false) {
        if (page == currentPage) return

        val previous = currentPage
        currentPage = page

        if (page == PAGE_APPS) {
            ensureAppsPage()
            if (!appsLoaded) refreshApps()
        }
        if (page == PAGE_SETTINGS) {
            ensureSettingsPage()
        }

        if (animate && previous != -1 && yotaDevice && config.refreshMode == 0) {
            val delta = page - previous
            val anim = when (config.pageAnimation) {
                2 -> EInkSdk.ANIM_HORIZONTAL_OPEN
                3 -> EInkSdk.ANIM_HORIZONTAL_CLOSE
                4 -> if (delta > 0) EInkSdk.ANIM_VERTICAL_BOTTOM else EInkSdk.ANIM_VERTICAL_TOP
                5 -> EInkSdk.ANIM_VERTICAL_OPEN
                6 -> EInkSdk.ANIM_VERTICAL_CLOSE
                0 -> EInkSdk.ANIM_OFF
                else -> if (delta > 0) EInkSdk.ANIM_HORIZONTAL_RIGHT else EInkSdk.ANIM_HORIZONTAL_LEFT
            }
            EInkSdk.applyPageTurn(window.decorView, anim)
        }

        pageHome.visibility = if (page == PAGE_HOME) View.VISIBLE else View.GONE
        if (::pageApps.isInitialized) {
            pageApps.visibility = if (page == PAGE_APPS) View.VISIBLE else View.GONE
        }
        if (::pageSettings.isInitialized) {
            pageSettings.visibility = if (page == PAGE_SETTINGS) View.VISIBLE else View.GONE
        }

        tabHomeLabel.setTextColor(color(if (page == PAGE_HOME) R.color.ink else R.color.gray))
        tabAppsLabel.setTextColor(color(if (page == PAGE_APPS) R.color.ink else R.color.gray))
        tabSettingsLabel.setTextColor(color(if (page == PAGE_SETTINGS) R.color.ink else R.color.gray))

        dotHome.visibility = if (page == PAGE_HOME) View.VISIBLE else View.INVISIBLE
        dotApps.visibility = if (page == PAGE_APPS) View.VISIBLE else View.INVISIBLE
        dotSettings.visibility = if (page == PAGE_SETTINGS) View.VISIBLE else View.INVISIBLE

        if (::manageToggle.isInitialized) {
            manageToggle.visibility = if (page == PAGE_APPS) View.VISIBLE else View.GONE
        }
    }

    // ------------------------------------------------------------------ Lock

    private fun setupLock() {
        btnLock.setOnClickListener { lockScreen() }
        infoTime.setOnClickListener { EInkSdk.manualFullRefresh(infoTime) }
    }

    // ------------------------------------------------------------------ Manage mode

    private fun setupManage() {
        manageDone.setOnClickListener { exitManageMode() }
        manageHide.setOnClickListener {
            if (manageSelected.isEmpty()) {
                toast("请先选择应用")
            } else {
                repository.batchSetHidden(manageSelected, true)
                toast("已隐藏 ${manageSelected.size} 个应用")
                manageSelected.clear()
                refreshApps()
            }
        }
        manageShow.setOnClickListener {
            if (manageSelected.isEmpty()) {
                toast("请先选择应用")
            } else {
                repository.batchSetHidden(manageSelected, false)
                toast("已显示 ${manageSelected.size} 个应用")
                manageSelected.clear()
                refreshApps()
            }
        }
        manageUninstall.setOnClickListener {
            if (manageSelected.isEmpty()) {
                toast("请先选择应用")
            } else {
                uninstallQueue.clear()
                uninstallQueue.addAll(manageSelected)
                manageSelected.clear()
                startNextUninstall()
            }
        }
    }

    private fun enterManageMode() {
        manageMode = true
        manageSelected.clear()
        manageToggle.text = getString(R.string.manage_done)
        bottomBar.visibility = View.GONE
        manageBar.visibility = View.VISIBLE
        refreshApps()
    }

    private fun exitManageMode() {
        manageMode = false
        manageSelected.clear()
        manageToggle.text = getString(R.string.manage)
        bottomBar.visibility = View.VISIBLE
        manageBar.visibility = View.GONE
        refreshApps()
    }

    private fun toggleManageSelect(entry: AppEntry) {
        val pkg = entry.packageName
        if (manageSelected.contains(pkg)) manageSelected.remove(pkg) else manageSelected.add(pkg)
        gridApps.updateSelection(manageSelected)
    }

    private fun startNextUninstall() {
        if (uninstallQueue.isEmpty()) {
            exitManageMode()
            refreshApps()
            toast("卸载流程结束")
            return
        }
        val pkg = uninstallQueue.removeAt(0)
        runCatching {
            startActivityForResult(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")),
                REQ_UNINSTALL
            )
        }.onFailure { e ->
            Log.w(TAG, "start uninstall failed for $pkg: ${e.message}")
            toast("无法卸载 $pkg：${e.message}")
            startNextUninstall()
        }
    }

    // ------------------------------------------------------------------ Action sheet

    private fun setupActionSheet() {
        findViewById<View>(R.id.actionDim).setOnClickListener { hideActionSheet() }
        findViewById<View>(R.id.actionClose).setOnClickListener { hideActionSheet() }
        actionPin.setOnClickListener {
            selectedApp?.let { togglePin(it) }
            hideActionSheet()
        }
        actionHide.setOnClickListener {
            selectedApp?.let { hideApp(it) }
            hideActionSheet()
        }
        actionAppInfo.setOnClickListener {
            selectedApp?.let { showAppInfo(it) }
            hideActionSheet()
        }
    }

    private fun showActionSheet(entry: AppEntry) {
        selectedApp = entry
        actionTitle.text = entry.label
        actionPin.text = getString(
            if (repository.isPinned(entry.packageName)) R.string.action_unpin
            else R.string.action_pin
        )
        actionHide.text = getString(R.string.action_hide)
        actionAppInfo.text = getString(R.string.action_app_info)
        actionOverlay.visibility = View.VISIBLE
    }

    private fun hideActionSheet() {
        actionOverlay.visibility = View.GONE
        selectedApp = null
    }

    // ------------------------------------------------------------------ Recent tasks

    private fun lockScreen() {
        if (yotaDevice) {
            if (config.screenOffAnimation && config.refreshMode == 0) {
                val anim = animationForStyle(config.screenOffAnimationStyle)
                if (anim != EInkSdk.ANIM_OFF) {
                    Log.d(TAG, "screenOffAnimation: style=${config.screenOffAnimationStyle} anim=$anim")
                    EInkSdk.applyScreenAnimation(window.decorView, anim)
                    window.decorView.postDelayed({
                        YotaSdkAdapter.lockEpd()
                    }, 700L)
                    return
                }
            }
            YotaSdkAdapter.lockEpd()
        } else {
            deviceManagerLock()
        }
    }

    private fun deviceManagerLock() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, LockAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            runCatching { dpm.lockNow() }
        } else {
            runCatching {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        getString(R.string.lock_admin_description)
                    )
                startActivity(intent)
            }
        }
    }

    private fun setupRecentOverlay() {
        findViewById<View>(R.id.recentDim).setOnClickListener { hideRecentTasks() }
        recentClear.setOnClickListener {
            // 面板上显示的就是 recentApps，直接拿它去清理，不再重查一遍
            val appsToKill = recentApps
            store.markRecentCleared()
            previousApp = null
            recentPrevious = null
            // 必须同步清空内存列表：populateRecentGrid 现在只读 recentApps，
            // 不清的话网格会继续显示旧内容
            recentApps = emptyList()
            populateRecentGrid()
            killBackgroundApps(appsToKill)
        }
        recentCancel.setOnClickListener {
            hideRecentTasks()
            if (config.recentCancelBackToApp) {
                previousApp?.let { launchApp(it) }
            }
        }

        recentGrid.setOnItemInteractionListener(object : EInkLauncherView.OnItemInteractionListener {
            override fun onItemClick(entry: AppEntry) {
                hideRecentTasks()
                launchApp(entry)
            }

            @SuppressLint("SetTextI18n")
            override fun onItemLongClick(entry: AppEntry) {
                val pkg = entry.packageName
                if (pkg == packageName) return
                val label = entry.label

                if (config.rootClear) {
                    tasks.worker(
                        work = { RootUtil.forceStopPackage(pkg) },
                        after = { success ->
                            // 【修复 2】检查面板是否已关闭或 Activity 是否销毁
                            if (!isFinishing && recentOverlay.visibility == View.VISIBLE) {
                                if (success) {
                                    toast("已停止: $label")
                                    removeAppFromRecentUI(pkg)
                                } else {
                                    toast("停止 $label 失败，请检查 Root 权限")
                                }
                            }
                        }
                    )
                } else {
                    val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    runCatching {
                        am.killBackgroundProcesses(pkg)
                        toast("已请求清理: $label")
                        removeAppFromRecentUI(pkg)
                    }
                }
            }
        })
        recentGrid.setOnPageChangeListener(null)
    }

    private fun removeAppFromRecentUI(pkg: String) {
        // 【修复 1】如果被杀掉的应用刚好是“上一应用”，立刻置空，防止取消时诈尸
        if (previousApp?.packageName == pkg) {
            previousApp = null
        }
        // 缓存里那份也要清，否则下次打开面板又会从缓存把它填回 previousApp
        if (recentPrevious?.packageName == pkg) {
            recentPrevious = null
        }

        store.markAppCleared(pkg)
        // 直接从内存列表里摘掉：原来这里会重跑一遍 usage 查询，纯属浪费
        recentApps = recentApps.filterNot { it.packageName == pkg }
        if (recentApps.isEmpty()) {
            hideRecentTasks()
        } else {
            populateRecentGrid()
        }
    }

    // ------------------------------------------------------------------ Info overlay

    private fun setupSelectOverlay() {
        findViewById<View>(R.id.selectDim).setOnClickListener { hideSelectOverlay() }
        findViewById<View>(R.id.selectCancel).setOnClickListener { hideSelectOverlay() }
    }

    private fun showSelectOverlay(
        title: String,
        options: List<String>,
        selectedIndex: Int,
        onPicked: (Int) -> Unit
    ) {
        selectTitle.text = title
        selectCallback = onPicked
        selectList.removeAllViews()

        options.forEachIndexed { index, label ->
            val row = TextView(this).apply {
                text = if (index == selectedIndex) "● $label" else "○ $label"
                textSize = 16f
                setTextColor(color(if (index == selectedIndex) R.color.ink else R.color.gray))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), 0, dp(24), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(52)
                )
                setOnClickListener {
                    hideSelectOverlay()
                    onPicked(index)
                }
            }
            selectList.addView(row)

            if (index < options.size - 1) {
                selectList.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(1)
                    )
                    setBackgroundColor(color(R.color.hairline))
                })
            }
        }

        selectOverlay.visibility = View.VISIBLE
    }

    private fun hideSelectOverlay() {
        selectOverlay.visibility = View.GONE
        selectCallback = null
    }

    // ------------------------------------------------------------------ WiFi transfer

    private fun setupWifiTransfer() {
        textTime.setOnClickListener { startWifiTransfer() }
        textTimeSplit.setOnClickListener { startWifiTransfer() }
        findViewById<View>(R.id.wifiClose).setOnClickListener { closeWifiTransfer() }
    }

    private fun startWifiTransfer() {
        if (wifiOverlay.visibility == View.VISIBLE) return

        if (Build.VERSION.SDK_INT >= 23 &&
            Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_WRITE_STORAGE)
            return
        }
        openWifiTransferOverlay()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_UNINSTALL) {
            startNextUninstall()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_WRITE_STORAGE) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            openWifiTransferOverlay()
        } else {
            toast("需要存储权限才能保存上传的文件")
        }
    }

    private fun openWifiTransferOverlay() {
        val ip = getWifiIpAddress()
        if (ip == null) {
            toast(getString(R.string.wifi_transfer_no_wifi))
            return
        }

        val dir = File(Environment.getExternalStorageDirectory(), "WIFI_transfer")
        val server = WifiTransferServer(WIFI_TRANSFER_PORT, dir)
        if (!server.start()) {
            toast(getString(R.string.wifi_transfer_start_failed))
            return
        }

        wifiServer?.stop()
        wifiServer = server

        val url = "http://$ip:$WIFI_TRANSFER_PORT"
        wifiUrlText.text = url
        wifiHintText.text = getString(R.string.wifi_transfer_url_hint, url) + "\n" +
                getString(R.string.wifi_transfer_folder_hint)
        // 面板先亮，二维码丢后台画：600px 的码要填 36 万像素，主线程会顿一下
        wifiOverlay.visibility = View.VISIBLE
        val token = ++wifiQrToken
        val sizePx = dp(300)
        tasks.worker(
            work = { QrCodes.generate(url, sizePx) },
            after = { bitmap ->
                if (token == wifiQrToken) wifiQr.setImageBitmap(bitmap)
            }
        )
    }

    private fun closeWifiTransfer() {
        wifiServer?.stop()
        wifiServer = null
        wifiOverlay.visibility = View.GONE
        // 作废在途的二维码，免得关掉面板后又被回填
        wifiQrToken++
    }

    private fun getWifiIpAddress(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    val addresses = interfaces.nextElement().inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                            return addr.hostAddress
                        }
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun setupInfoOverlay() {
        infoClose.setOnClickListener { hideInfo() }
    }

    private fun updateDonationPromptValue() {
        settingsDonationPromptValue.text =
            getString(if (store.isDonationPromptEnabled()) R.string.divider_on else R.string.divider_off)
    }

    private fun showInfo(title: String, text: String) {
        infoTitle.text = title
        infoText.text = text
        infoOverlay.visibility = View.VISIBLE
    }

    private fun hideInfo() {
        infoOverlay.visibility = View.GONE
    }

    private fun showRecentTasks() {
        if (actionOverlay.visibility == View.VISIBLE) hideActionSheet()
        if (manageMode) exitManageMode()
        recentOverlay.visibility = View.VISIBLE
        // 先用缓存顶上（onResume 回到桌面时已刷过一轮），再后台重查校准
        previousApp = recentPrevious
        // 还没查到过就先不渲染，否则会闪一屏「暂无最近任务」再重画
        if (recentLoaded) populateRecentGrid()
        refreshRecentApps()
    }

    /**
     * 后台重查最近任务。一次 queryUsageStats 同时拿到两个视图：面板要显示的列表
     * （按时间窗过滤）和「上一应用」（全量第一个，不受时间窗限制）。
     *
     * 原来这两样在 UI 线程上各查一遍，打开面板必然卡一下；现在面板先用缓存立即
     * 渲染，结果回来再覆盖。
     */
    private fun refreshRecentApps() {
        if (Build.VERSION.SDK_INT < 21) return
        val token = ++recentRefreshToken
        val windowMs = config.recentWindowMs
        tasks.worker(
            work = { queryRecentApps(windowMs) },
            after = { r ->
                // 已有更新的查询在跑，本次结果作废
                if (token == recentRefreshToken) {
                    recentLoaded = true
                    recentPrevious = r.previous
                    recentApps = r.visible
                    // 必须用最新结果覆盖，不能只在 null 时才填：
                    // 打开面板时先填的是缓存里的旧值，只补空会让上一次的
                    //「上一应用」一直错下去，取消时跳到错误的 App
                    previousApp = r.previous
                    if (recentOverlay.visibility == View.VISIBLE) populateRecentGrid()
                }
            }
        )
    }

    @SuppressLint("SetTextI18n")
    private fun populateRecentGrid() {
        if (Build.VERSION.SDK_INT < 21) {
            recentMessage.text = "最近任务需要 Android 5.0 及以上"
            recentMessage.setOnClickListener(null)
            recentMessage.visibility = View.VISIBLE
            recentGrid.visibility = View.GONE
            return
        }

        if (!usageAccessGranted()) {
            recentMessage.text = "需要开启「使用情况访问」权限才能显示后台应用，点此去开启"
            recentMessage.setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }
            recentMessage.visibility = View.VISIBLE
            recentGrid.visibility = View.GONE
            return
        }

        val apps = recentApps

        if (apps.isEmpty()) {
            recentMessage.text =
                if (config.recentWindowMs > 0L) "暂无近期使用" else "暂无最近任务"
            recentMessage.setOnClickListener(null)
            recentMessage.visibility = View.VISIBLE
            recentGrid.visibility = View.GONE
            return
        }

        recentMessage.visibility = View.GONE
        recentGrid.visibility = View.VISIBLE
        // 最近任务是一个底部浮层：列数按屏幕宽度自适应（手机 4 列，宽屏最多 5 列），
        // 行数由应用数量决定，行高按图标大小与文字行高推算。
        val density = resources.displayMetrics.density
        val columns = GridSizing.recentColumnCap(displayWidthDp())

        // 行高先按单格估算，再用实际格宽算出图标大小，最后按图标大小定行高。
        val estimatedRowPx = dp(60).coerceAtLeast(dp(48))
        val cellWidthPx = (resources.displayMetrics.widthPixels - dp(72)).coerceAtLeast(dp(120)) / columns
        val iconPx = recentGrid.iconPxFor(cellWidthPx, estimatedRowPx, columns, 1)
        var rows = (apps.size + columns - 1) / columns
        rows = rows.coerceIn(1, GridSizing.recentRowCap(displayHeightDp() * 0.4f))
        recentGrid.configure(columns, rows)
        // 行数随数量浮动，若让池子跟着 rows 走，每次刷新跨过列数的倍数都会整池
        // removeAllViews + 重新 inflate。按上限（queryRecentApps 最多 12 个）固定，
        // 多出的槽位在 rebind 里走 GONE
        recentGrid.reservePool(RECENT_MAX_ITEMS)

        val rowHeight = GridSizing.rowHeightDp(iconPx, density)
        recentGrid.layoutParams = recentGrid.layoutParams.apply {
            height = dp(rows * rowHeight)
        }

        recentGrid.setApps(
            pageApps = apps,
            selection = emptySet(),
            manageMode = false,
            showDividers = true,
            hidden = emptySet()
        )
    }

    @SuppressLint("SetTextI18n")
    private fun killBackgroundApps(apps: List<AppEntry>) {
        if (apps.isEmpty()) return

        // 先取出 selfPkg，避免后台 lambda 捕获 Activity
        val selfPkg = packageName

        if (config.rootClear) {
            tasks.worker(
                work = {
                    val pkgs = apps.map { it.packageName }.filter { it != selfPkg }
                    if (pkgs.isEmpty()) {
                        ToastResult(emptyList(), emptyList(), true)
                    } else {
                        // D 方案：只对“仍有进程存活”的应用下 root force-stop（root 枚举，
                        // 见 RootUtil.runningProcessPackages；不能用 getRunningAppProcesses，
                        // API21+ 对三方应用只返回自身进程）。空闲项没有进程可杀，跳过即可。
                        val running = RootUtil.runningProcessPackages(pkgs)
                        val idle = pkgs.filter { it !in running }

                        // B 方案：libsu 单壳是串行的，运行中的包改用独立 su 进程并发强停
                        val success = if (running.isNotEmpty()) {
                            RootUtil.forceStopPackagesParallel(running)
                        } else true
                        ToastResult(running, idle, success)
                    }
                },
                after = { r ->
                    if (r.success) {
                        val msg = when {
                            r.running.isEmpty() -> "已清理 ${r.idle.size} 个后台任务"
                            r.idle.isEmpty() -> "已彻底清理 ${r.running.size} 个应用"
                            else -> "已清理 ${r.running.size} 个运行中应用、${r.idle.size} 个空闲任务"
                        }
                        toast(msg)
                    } else {
                        toast("清理完成，或未授予 Root 权限")
                    }
                }
            )
        } else {
            if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.KILL_BACKGROUND_PROCESSES) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "KILL_BACKGROUND_PROCESSES not granted")
                toast("缺少后台清理权限")
                return
            }
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            var killed = 0
            for (entry in apps) {
                val pkg = entry.packageName
                if (pkg == selfPkg) continue
                runCatching {
                    am.killBackgroundProcesses(pkg)
                    killed++
                }.onFailure { e ->
                    Log.w(TAG, "killBackgroundProcesses failed for $pkg: ${e.message}")
                }
            }
            if (killed > 0) {
                Log.i(TAG, "killBackgroundProcesses requested for $killed packages")
                toast("已清理 $killed 个后台应用")
            }
        }
    }

    /** 后台清理结果，带回主线程拼 toast */
    private class ToastResult(
        val running: List<String>,
        val idle: List<String>,
        val success: Boolean
    )

    /**
     * 最近任务查询结果。一次 queryUsageStats 同时给出两个视图：
     * - [previous]：上一应用，不受「只显示最近 N 分钟」限制，取消时回退用；
     * - [visible]：面板要显示的列表，按时间窗过滤。
     */
    private class RecentQueryResult(val previous: AppEntry?, val visible: List<AppEntry>)

    @SuppressLint("NewApi")
    private fun queryRecentApps(windowMs: Long): RecentQueryResult {
        if (Build.VERSION.SDK_INT < 21) return RecentQueryResult(null, emptyList())
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val begin = now - 7L * 24 * 60 * 60 * 1000
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, begin, now)
        }.getOrDefault(emptyList())

        // picked 是「不带时间窗」的全量结果，[first] = 上一应用。
        // 时间窗只用来决定面板显示什么，不影响取消时回退到哪个 App。
        val picked = ArrayList<Pair<AppEntry, Long>>()
        val clearedAt = store.recentClearedAt()
        val seen = HashSet<String>()
        for (stat in stats.sortedByDescending { it.lastTimeUsed }) {
            if (stat.lastTimeUsed <= 0) continue
            if (stat.lastTimeUsed <= clearedAt) continue

            val pkg = stat.packageName
            if (pkg == packageName) continue
            // INTERVAL_BEST 下同一个包会返回多条分桶记录，先去重再查：
            // 否则会对同一个包反复 getLaunchIntentForPackage / resolveActivity
            if (!seen.add(pkg)) continue

            // 核心修复：根据独立的单个 App 时间戳过滤历史记录
            if (stat.lastTimeUsed <= store.appClearedAt(pkg)) continue

            val launchIntent = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            val info = runCatching { packageManager.resolveActivity(launchIntent, 0) }.getOrNull()
                ?: continue
            // 复用 AppRepository 的 label 缓存
            picked.add(repository.entryFor(info) to stat.lastTimeUsed)
            if (picked.size >= RECENT_MAX_ITEMS) break
        }

        // 列表已按 lastTimeUsed 降序，窗口内的一定排在最前面，
        // 所以「先截 12 再过滤」与「先过滤再截 12」结果一致
        val visible = if (windowMs > 0L) {
            val since = now - windowMs
            picked.filter { it.second >= since }.map { it.first }
        } else {
            picked.map { it.first }
        }
        return RecentQueryResult(picked.firstOrNull()?.first, visible)
    }

    @SuppressLint("NewApi")
    private fun usageAccessGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 21) return false
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    private fun hideRecentTasks() {
        recentOverlay.visibility = View.GONE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun togglePin(entry: AppEntry) {
        val pkg = entry.packageName
        val pinned = repository.isPinned(pkg)
        repository.setPinned(pkg, !pinned)
        toast(if (!pinned) "已固定到主页" else "已取消固定")
        refreshHome()
    }

    private fun hideApp(entry: AppEntry) {
        repository.setHidden(entry.packageName, true)
        toast("已隐藏")
        refreshHome()
        refreshApps()
    }

    private fun showAppInfo(entry: AppEntry) {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${entry.packageName}")
                }
            )
        }
    }

    // ------------------------------------------------------------------ Common

    private fun launchApp(entry: AppEntry) {
        repository.recordUsage(entry.packageName)
        repository.launch(entry)
    }

    private fun registerTimeReceiver() {
        val filter = IntentFilter(Intent.ACTION_TIME_TICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timeReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(timeReceiver, filter)
        }
    }

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(packageReceiver, filter)
        }
    }

    private fun updateClock() {
        if (!::textTime.isInitialized) return
        val now = Date()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val date = SimpleDateFormat("MM月dd日 · E", Locale.CHINA).format(now)
        textTime.text = time
        textDate.text = date
        textTimeSplit.text = time
        textDateSplit.text = date
        infoTime.text = time
    }

    @Suppress("DEPRECATION")
    private fun color(res: Int): Int = resources.getColor(res)

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                when {
                    controlPanel?.isVisible == true -> controlPanel?.closePanel(animateToHome = true)
                    infoOverlay.visibility == View.VISIBLE -> hideInfo()
                    selectOverlay.visibility == View.VISIBLE -> hideSelectOverlay()
                    wifiOverlay.visibility == View.VISIBLE -> closeWifiTransfer()
                    recentOverlay.visibility == View.VISIBLE -> hideRecentTasks()
                    actionOverlay.visibility == View.VISIBLE -> hideActionSheet()
                    manageMode -> exitManageMode()
                    ::pageSettings.isInitialized && pageSettings.visibility == View.VISIBLE -> selectPage(PAGE_HOME, animate = true)
                    ::pageApps.isInitialized && pageApps.visibility == View.VISIBLE -> selectPage(PAGE_HOME, animate = true)
                    else -> Unit
                }
                true
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }

    companion object {
        private const val TAG = "LauncherActivity"
        private const val PAGE_HOME = 0
        private const val PAGE_APPS = 1
        private const val PAGE_SETTINGS = 2
        private const val EPD_HOME_CATEGORY = "com.yotadevices.intent.category.EPD_HOME"
        private const val WIFI_TRANSFER_PORT = 25025
        private const val DONATION_WECHAT_URI =
            "wxp://f2f0KzDXCZc0SriqXOoY8fWKxxyFbxz9xhImNUL1VgMLLaQ"
        private const val REQ_WRITE_STORAGE = 7
        private const val REQ_UNINSTALL = 8

        // 最近任务最多显示多少个。既是 queryRecentApps 的截断上限，也是 recentGrid
        // 预留 holder 池的容量依据，两个数必须一致，所以放一起
        private const val RECENT_MAX_ITEMS = 12
    }
}