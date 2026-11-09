package com.yota.launcher

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

@SuppressLint("NewApi")
class NotificationReaderService : NotificationListenerService() {

    companion object {
        var instance: NotificationReaderService? = null
        var isMediaAppActive = false
        var cachedPkg = "" // 保存媒体的底层包名用于强制跳转
        var cachedAppName = ""
        var cachedTitle = ""
        var cachedArtist = ""
        var cachedIsPlaying = false
        var cachedIntent: PendingIntent? = null
    }

    private var mediaSessionManager: MediaSessionManager? = null
    private var activeMediaController: MediaController? = null

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.yota.CANCEL_NOTIF") {
                val key = intent.getStringExtra("key")
                if (key != null) {
                    cancelNotification(key)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

        val filter = IntentFilter("com.yota.CANCEL_NOTIF")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(commandReceiver, filter)
        }

        setupMediaListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        runCatching { unregisterReceiver(commandReceiver) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val template = extras.getString("android.template") ?: ""
        val pkg = sbn.packageName

        // 自己的通知不显示给自己
        if (pkg == packageName) return

        // 常驻通知（音乐、下载、前台服务）不会自己消失，会一直占着卡片把新消息挤出去；
        // 媒体卡片走 MediaSession 那条路，不受这里影响
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        // 拦截幽灵通知与重复的媒体卡片通知
        if (template == "android.app.Notification\$MediaStyle" || (title.trim().isEmpty() && text.trim().isEmpty())) {
            return
        }

        // 提取精确时间戳、包名等核心信息，打包发给控制面板
        val intent = Intent("com.yota.NEW_NOTIF").apply {
            putExtra("key", sbn.key)
            putExtra("pkg", pkg)
            putExtra("title", title)
            putExtra("text", text)

            // 如果系统给的时间是 0，就兜底使用当前真实的系统时间
            val realTime = if (notification.`when` > 0L) notification.`when` else System.currentTimeMillis()
            putExtra("time", realTime)

            putExtra("contentIntent", notification.contentIntent)
        }
        sendBroadcast(intent.setPackage(packageName))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // 发送普通通知移除的广播
        val intent = Intent("com.yota.NOTIF_REMOVED").apply {
            putExtra("key", sbn.key)
        }
        sendBroadcast(intent.setPackage(packageName))

        // 【核心修复】：如果被划掉（或被强杀）的通知刚好是当前在播的媒体软件，立刻清空缓存防诈尸！
        if (sbn.packageName == cachedPkg) {
            clearMediaCacheAndHide()
        }
    }

    // 【针对 Android 8.0+ 的增强保底】：捕捉应用被系统强杀或用户强退的精确原因
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        // 不调 super：默认实现会再回调两参版本，导致 NOTIF_REMOVED 发两遍
        val intent = Intent("com.yota.NOTIF_REMOVED").apply {
            putExtra("key", sbn.key)
        }
        sendBroadcast(intent.setPackage(packageName))

        if (sbn.packageName == cachedPkg) {
            clearMediaCacheAndHide()
        }
    }

    // 【新增】：统一的内存大清洗方法
    private fun clearMediaCacheAndHide() {
        isMediaAppActive = false
        cachedPkg = ""
        cachedAppName = ""
        cachedTitle = ""
        cachedArtist = ""
        cachedIsPlaying = false
        cachedIntent = null

        // 通知 UI 立刻隐藏卡片
        sendBroadcast(Intent("com.yota.MEDIA_CLEAR").setPackage(packageName))
    }

    private fun setupMediaListener() {
        try {
            val component = ComponentName(this, NotificationReaderService::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener({ controllers ->
                updateActiveMediaController(controllers)
            }, component)
            updateActiveMediaController(mediaSessionManager?.getActiveSessions(component))
        } catch (e: SecurityException) {
            Log.e("NotifReader", "Missing permission to listen to media", e)
        }
    }

    private fun updateActiveMediaController(controllers: List<MediaController>?) {
        val controller = controllers?.firstOrNull { it.playbackState != null }
        if (controller != activeMediaController) {
            activeMediaController?.unregisterCallback(mediaCallback)
            activeMediaController = controller
            activeMediaController?.registerCallback(mediaCallback)
        }
        updateMediaState(controller)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateMediaState(activeMediaController)
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMediaState(activeMediaController)
        }

        // 【核心修复】：当底层的媒体会话被系统彻底销毁时（比如杀掉番茄小说），强制清洗！
        override fun onSessionDestroyed() {
            super.onSessionDestroyed()
            clearMediaCacheAndHide()
        }
    }

    private fun updateMediaState(controller: MediaController?) {
        if (controller == null) {
            // 直接复用统一清洗方法
            clearMediaCacheAndHide()
            return
        }
        val metadata = controller.metadata
        val state = controller.playbackState
        if (metadata == null || state == null) return

        isMediaAppActive = true
        cachedTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "未知歌曲"
        cachedArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "未知歌手"

        val pkg = controller.packageName
        cachedPkg = pkg // 永远精准锁定当前正在播放的底层包名

        val pm = packageManager
        cachedAppName = try {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            pkg
        }

        cachedIsPlaying = state.state == PlaybackState.STATE_PLAYING
        cachedIntent = controller.sessionActivity

        val intent = Intent("com.yota.MEDIA_UPDATE").apply {
            putExtra("appName", cachedAppName)
            putExtra("title", cachedTitle)
            putExtra("artist", cachedArtist)
            putExtra("isPlaying", cachedIsPlaying)
        }
        sendBroadcast(intent.setPackage(packageName))
    }
}