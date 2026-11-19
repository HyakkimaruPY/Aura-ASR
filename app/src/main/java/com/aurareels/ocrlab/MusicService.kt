package com.aurareels.ocrlab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder

/**
 * Link between the music player (owned by MainActivity) and [MusicService].
 * The activity publishes [state] and installs the callbacks; the service only shows the notification and
 * forwards button presses. All callbacks run on the main thread.
 */
object MusicBridge {
    class State(val title: String, val artist: String, val playing: Boolean,
                val positionMs: Long, val durationMs: Long, val cover: Bitmap?)
    @Volatile var state: State? = null
    @Volatile var onPlayPause: (() -> Unit)? = null
    @Volatile var onNext: (() -> Unit)? = null
    @Volatile var onPrevious: (() -> Unit)? = null
    @Volatile var onStop: (() -> Unit)? = null
    @Volatile var onSeek: ((Long) -> Unit)? = null
    fun clearCallbacks() { onPlayPause = null; onNext = null; onPrevious = null; onStop = null; onSeek = null }
}

/**
 * Foreground service (type mediaPlayback) that keeps the process alive and shows the lock-screen /
 * notification-shade controls while music plays, like any music player. It does not own the ExoPlayer:
 * playback, lyrics and the queue stay in MainActivity, which survives Home and screen-off.
 */
class MusicService : Service() {
    private var session: MediaSession? = null

    companion object {
        private const val CHANNEL = "aura_music"
        private const val NOTIF_ID = 4107
        const val ACTION_TOGGLE = "com.aurareels.ocrlab.music.TOGGLE"
        const val ACTION_NEXT = "com.aurareels.ocrlab.music.NEXT"
        const val ACTION_PREV = "com.aurareels.ocrlab.music.PREV"
        const val ACTION_STOP = "com.aurareels.ocrlab.music.STOP"
        @Volatile var instance: MusicService? = null

        /** Starts the service once (from a visible activity). Later calls only refresh. */
        fun start(context: Context) {
            if (instance != null) { poke(); return }
            try { context.startForegroundService(Intent(context, MusicService::class.java)) } catch (_: Throwable) {}
        }
        fun stop(context: Context) {
            MusicBridge.state = null
            try { context.stopService(Intent(context, MusicService::class.java)) } catch (_: Throwable) {}
        }
        /** Call on the main thread after changing [MusicBridge.state]. */
        fun poke() { instance?.refresh() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reprodução de música", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        session = MediaSession(this, "AuraMusic").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { if (MusicBridge.state?.playing != true) MusicBridge.onPlayPause?.invoke() }
                override fun onPause() { if (MusicBridge.state?.playing == true) MusicBridge.onPlayPause?.invoke() }
                override fun onSkipToNext() { MusicBridge.onNext?.invoke() }
                override fun onSkipToPrevious() { MusicBridge.onPrevious?.invoke() }
                override fun onSeekTo(pos: Long) { MusicBridge.onSeek?.invoke(pos) }
                override fun onStop() { MusicBridge.onStop?.invoke() }
            })
            isActive = true
        }
        // Android requires startForeground within seconds of startForegroundService.
        show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> MusicBridge.onPlayPause?.invoke()
            ACTION_NEXT -> MusicBridge.onNext?.invoke()
            ACTION_PREV -> MusicBridge.onPrevious?.invoke()
            ACTION_STOP -> {
                val stopper = MusicBridge.onStop
                if (stopper != null) stopper.invoke()
                MusicBridge.state = null
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                return START_NOT_STICKY
            }
        }
        show()
        return START_NOT_STICKY   // the player lives in the activity: a restarted service would have nothing to control
    }

    fun refresh() = show()

    private fun show() {
        val s = MusicBridge.state
        session?.apply {
            setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s?.title ?: "Música")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s?.artist ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, s?.durationMs ?: 0L)
                .apply { s?.cover?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build())
            setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP)
                .setState(if (s?.playing == true) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    s?.positionMs ?: 0L, if (s?.playing == true) 1f else 0f)
                .build())
        }
        val notification = buildNotification(s)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, notification)
    }

    private fun action(icon: Int, title: String, name: String): Notification.Action {
        val pending = PendingIntent.getService(this, name.hashCode(),
            Intent(this, MusicService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pending).build()
    }

    private fun buildNotification(s: MusicBridge.State?): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val playing = s?.playing == true
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(s?.title ?: "Música")
            .setContentText(s?.artist ?: "")
            .setLargeIcon(s?.cover)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "Anterior", ACTION_PREV))
            .addAction(action(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pausar" else "Tocar", ACTION_TOGGLE))
            .addAction(action(android.R.drawable.ic_media_next, "Próxima", ACTION_NEXT))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Parar", ACTION_STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onDestroy() {
        instance = null
        session?.run { isActive = false; release() }
        session = null
        super.onDestroy()
    }
}
