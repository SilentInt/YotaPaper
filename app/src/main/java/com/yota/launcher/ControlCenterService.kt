package com.yota.launcher

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.yota.launcher.ui.ControlCenterActivity
import java.io.DataInputStream
import java.util.concurrent.Executors
import kotlin.math.abs

class ControlCenterService : Service() {

    companion object {
        @Volatile
        var isPanelActive = false

        /** LauncherActivity 是否在前台：前台走“主页窗口内浮层”（动画连贯），否则退回独立 Activity。 */
        @Volatile
        var launcherTop = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val eventReaderExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var eventReaderRunning = false

    // 【核心修复1】新增生死标记：明确标记服务当前是否应该存活
    @Volatile
    private var isServiceActive = false

    private var eventProcess: Process? = null

    private val screenHeight: Int by lazy {
        resources.displayMetrics.heightPixels
    }

    /** 下拉起手区：屏幕顶部 10%（EPD 1280px 高 → 顶部 128px 内起手才算“从顶部下拉”）。 */
    private val topThreshold by lazy { (screenHeight * 0.10f).toInt() }
    private val swipeThreshold = 45

    private val panelStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.yota.PANEL_OPENED" -> isPanelActive = true
                "com.yota.PANEL_CLOSED" -> isPanelActive = false
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    stopEventReader()
                    isPanelActive = false
                }
                Intent.ACTION_SCREEN_ON -> if (isServiceActive) startEventReader()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceActive = true // 服务启动，标记为存活

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, screenFilter)
        }

        val stateFilter = IntentFilter().apply {
            addAction("com.yota.PANEL_OPENED")
            addAction("com.yota.PANEL_CLOSED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(panelStateReceiver, stateFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(panelStateReceiver, stateFilter)
        }

        mainHandler.postDelayed({
            if (isServiceActive) startEventReader()
        }, 2000)
    }

    private fun startEventReader() {
        if (!isServiceActive || eventReaderRunning) return
        eventReaderRunning = true
        eventReaderExecutor.execute {
            try {
                Log.d("ControlCenter", "Starting pure event reader...")
                eventProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /dev/input/event2"))
                val inputStream = DataInputStream(eventProcess!!.inputStream)
                val buffer = ByteArray(24)

                var touchStartY = 0f
                var lastY = 0f
                var touching = false
                var pendingSwipe = false
                var justTouched = false

                while (eventReaderRunning && isServiceActive) {
                    inputStream.readFully(buffer)
                    val type = (buffer[16].toInt() and 0xFF) or ((buffer[17].toInt() and 0xFF) shl 8)
                    val code = (buffer[18].toInt() and 0xFF) or ((buffer[19].toInt() and 0xFF) shl 8)
                    val value = (buffer[20].toInt() and 0xFF) or
                            ((buffer[21].toInt() and 0xFF) shl 8) or
                            ((buffer[22].toInt() and 0xFF) shl 16) or
                            ((buffer[23].toInt() and 0xFF) shl 24)

                    when (type) {
                        3 -> {
                            if (code == 0x36) {
                                lastY = value.toFloat()
                            }
                        }
                        1 -> {
                            if (code == 0x14a) {
                                if (value == 1) {
                                    touching = true
                                    justTouched = true
                                    pendingSwipe = false
                                } else if (value == 0) {
                                    touching = false
                                    pendingSwipe = false
                                }
                            }
                        }
                        0 -> {
                            if (code == 0 && value == 0) {
                                if (touching) {
                                    if (justTouched) {
                                        touchStartY = lastY
                                        justTouched = false
                                    } else if (!pendingSwipe) {
                                        val delta = lastY - touchStartY
                                        val absDelta = abs(delta)

                                        if (absDelta > swipeThreshold) {
                                            val startY = touchStartY.toInt()

                                            when {
                                                !isPanelActive && startY < topThreshold && delta > 0 -> {
                                                    pendingSwipe = true
                                                    mainHandler.post {
                                                        if (launcherTop) {
                                                            // LauncherActivity 前台：并入主页窗口的浮层，动画与主页同机制
                                                            sendBroadcast(Intent("com.yota.OPEN_PANEL"))
                                                        } else {
                                                            // 其它应用前台：退回独立窗口（保留“任何界面都能唤出”能力）
                                                            val intent = Intent(this@ControlCenterService, ControlCenterActivity::class.java)
                                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                                            startActivity(intent)
                                                        }
                                                    }
                                                }
                                                isPanelActive && delta < 0 -> {
                                                    pendingSwipe = true
                                                    mainHandler.post {
                                                        sendBroadcast(Intent("com.yota.ACTION_CLOSE_PANEL"))
                                                        mainHandler.postDelayed({ pendingSwipe = false }, 200)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ControlCenter", "Event reader error: ${e.message}")
            } finally {
                eventReaderRunning = false
                eventProcess?.destroy()
                eventProcess = null

                // 【核心修复2】出错中断后，只有在服务依然存活的情况下，才允许 3 秒后重试！
                if (isServiceActive) {
                    mainHandler.postDelayed({
                        if (isServiceActive) startEventReader()
                    }, 3000)
                }
            }
        }
    }

    private fun stopEventReader() {
        Log.d("ControlCenter", "Stopping event reader and hunting zombie processes...")
        eventReaderRunning = false

        runCatching { eventProcess?.inputStream?.close() }
        runCatching { eventProcess?.errorStream?.close() }
        runCatching { eventProcess?.outputStream?.close() }

        eventProcess?.destroy()
        eventProcess = null

        Thread {
            runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f 'cat /dev/input/event2'"))
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("ControlCenter", "Service onDestroy called.")

        // 【核心修复3】立刻打上死亡标记，并清空 Handler 里所有排队等待的“复活”任务
        isServiceActive = false
        mainHandler.removeCallbacksAndMessages(null)

        stopEventReader()
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { unregisterReceiver(panelStateReceiver) }

        isPanelActive = false
    }
}