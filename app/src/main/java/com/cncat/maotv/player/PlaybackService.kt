package com.cncat.maotv.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import com.cncat.maotv.MainActivity
import com.cncat.maotv.MaoApp
import com.cncat.maotv.R
import com.cncat.maotv.net.Clock

/**
 * 前台服务：切到后台 / 锁屏时保持进程不被系统杀掉，让声音继续播放；
 * 通知栏显示频道名和 暂停 / 上一台 / 下一台 / 关闭 按钮，并接收耳机、蓝牙的媒体按键。
 */
class PlaybackService : Service() {
    private lateinit var session: MediaSession
    private val player: PlayerController get() = (application as MaoApp).player

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSession(this, "MaoTV")
        @Suppress("DEPRECATION")
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { if (player.isPaused) player.togglePause() }
            override fun onPause() { if (!player.isPaused) player.togglePause() }
            override fun onSkipToNext() { player.step(1) }
            override fun onSkipToPrevious() { player.step(-1) }
            override fun onStop() { stopAll() }
        })
        session.isActive = true
        player.onStateChanged = { refresh() }
        startForegroundCompat(buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> player.togglePause()
            ACTION_NEXT -> player.step(1)
            ACTION_PREV -> player.step(-1)
            ACTION_STOP -> { stopAll(); return START_NOT_STICKY }
        }
        refresh()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 用户把应用从最近任务里划掉：停止播放 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopAll()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        player.onStateChanged = null
        try { session.isActive = false; session.release() } catch (_: Throwable) { }
        super.onDestroy()
    }

    private fun stopAll() {
        player.shutdown()
        // 通知“关闭”后整个应用一起退出
        sendBroadcast(Intent(ACTION_QUIT).setPackage(packageName))
        stopForeground(true)
        stopSelf()
    }

    private fun refresh() {
        val p = player
        val paused = p.isPaused
        val state = if (p.session == null) PlaybackState.STATE_STOPPED
        else if (paused) PlaybackState.STATE_PAUSED
        else if (p.isBuffering) PlaybackState.STATE_BUFFERING
        else PlaybackState.STATE_PLAYING
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
                )
                .setState(state, 0L, 1f)
                .build()
        )
        val ch = p.current
        val model = (application as MaoApp).model
        val nowTitle = ch?.let { model.epg.nowNext(it, Clock.now()).first?.title }
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, ch?.name ?: "猫TV")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, nowTitle ?: "")
                .build()
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "播放", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun action(icon: Int, title: String, act: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, code, Intent(this, PlaybackService::class.java).setAction(act),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun buildNotification(): Notification {
        val p = player
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val ch = p.current
        val model = (application as MaoApp).model
        val nowTitle = ch?.let { model.epg.nowNext(it, Clock.now()).first?.title }
        b.setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ch?.let { "${it.number}  ${it.name}" } ?: "猫TV")
            .setContentText(nowTitle ?: if (p.isPaused) "已暂停" else "正在播放")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "上一台", ACTION_PREV, 1))
            .addAction(
                if (p.isPaused) action(android.R.drawable.ic_media_play, "播放", ACTION_TOGGLE, 2)
                else action(android.R.drawable.ic_media_pause, "暂停", ACTION_TOGGLE, 2)
            )
            .addAction(action(android.R.drawable.ic_media_next, "下一台", ACTION_NEXT, 3))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "关闭", ACTION_STOP, 4))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        return b.build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        const val CHANNEL_ID = "maotv_playback"
        const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE = "com.cncat.maotv.TOGGLE"
        const val ACTION_NEXT = "com.cncat.maotv.NEXT"
        const val ACTION_PREV = "com.cncat.maotv.PREV"
        const val ACTION_STOP = "com.cncat.maotv.STOP"
        const val ACTION_QUIT = "com.cncat.maotv.QUIT"
    }
}
