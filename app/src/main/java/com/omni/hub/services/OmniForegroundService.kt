package com.omni.hub.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class OmniForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            val notification = buildNotification("Omni Hub Task", "Automation in progress...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_MEDIA_ACTION) {
            currentPlaying = !currentPlaying
            com.omni.hub.api.OmniLogger.log("MEDIA_SERVICE", "Interactive Notification button pressed. Toggling play to: $currentPlaying")
            com.omni.hub.api.MediaPlaybackDispatcher.onAction(currentPlaying)

            try {
                val notification = buildMediaNotification(currentTitle, currentArtist, currentPlaying)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_MEDIA_ID, notification)
            } catch (e: Exception) {
                com.omni.hub.api.OmniLogger.log("MEDIA_SERVICE_ERR", "Failed updating notification: " + e.message)
            }
            return START_STICKY
        }

        if (action == ACTION_STOP_MEDIA) {
            com.omni.hub.api.OmniLogger.log("MEDIA_SERVICE", "Interactive Notification STOP pressed. Halting media.")
            com.omni.hub.api.MediaPlaybackDispatcher.onAction(false)
            com.omni.hub.api.MediaPlaybackDispatcher.registerListener(null)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_START_MEDIA || action == ACTION_UPDATE_MEDIA) {
            currentTitle = intent?.getStringExtra(EXTRA_TITLE) ?: currentTitle
            currentArtist = intent?.getStringExtra(EXTRA_MESSAGE) ?: currentArtist
            currentPlaying = intent?.getBooleanExtra(EXTRA_IS_PLAYING, currentPlaying) ?: currentPlaying

            com.omni.hub.api.OmniLogger.log("MEDIA_SERVICE", "Service state update -> '$currentTitle' ($currentArtist), playing=$currentPlaying")

            try {
                createMediaNotificationChannel()
                val notification = buildMediaNotification(currentTitle, currentArtist, currentPlaying)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_MEDIA_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NOTIFICATION_MEDIA_ID, notification)
                }
            } catch (e: Exception) {
                com.omni.hub.api.OmniLogger.log("MEDIA_SERVICE_ERR", "startForeground error: " + e.message)
            }

            return START_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Omni Hub Task"
        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: "Automation in progress..."

        try {
            createNotificationChannel()
            val notification = buildNotification(title, message)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {}

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Omni Hub Background Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps dynamic plugins and automation alive in background"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createMediaNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_MEDIA_ID,
                "Omni Media Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls background audio playback with lockscreen media controls"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildMediaNotification(title: String, artist: String, isPlaying: Boolean): Notification {
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseText = if (isPlaying) "Pause" else "Play"

        val toggleIntent = Intent(this, OmniForegroundService::class.java).apply {
            action = ACTION_MEDIA_ACTION
            putExtra(EXTRA_DESIRED_PLAY, !isPlaying)
        }
        val togglePending = android.app.PendingIntent.getService(
            this,
            101,
            toggleIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, OmniForegroundService::class.java).apply {
            action = ACTION_STOP_MEDIA
        }
        val stopPending = android.app.PendingIntent.getService(
            this,
            102,
            stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_MEDIA_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(playPauseIcon, playPauseText, togglePending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", stopPending)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildNotification(title: String, message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_MEDIA_ID = "omni_media_channel"
        private const val NOTIFICATION_MEDIA_ID = 8846

        const val ACTION_START_MEDIA = "com.omni.hub.action.START_MEDIA"
        const val ACTION_UPDATE_MEDIA = "com.omni.hub.action.UPDATE_MEDIA"
        const val ACTION_STOP_MEDIA = "com.omni.hub.action.STOP_MEDIA"
        const val ACTION_MEDIA_ACTION = "com.omni.hub.action.MEDIA_ACTION"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
        const val EXTRA_DESIRED_PLAY = "extra_desired_play"

        @Volatile private var currentTitle: String = "Omni Audio"
        @Volatile private var currentArtist: String = "Playing in Background"
        @Volatile private var currentPlaying: Boolean = true

        fun startMedia(context: Context, title: String, artist: String, isPlaying: Boolean, onAction: (Boolean) -> Unit) {
            mediaActionListener = onAction
            val intent = Intent(context, OmniForegroundService::class.java).apply {
                action = ACTION_START_MEDIA
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MESSAGE, artist)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateMedia(context: Context, title: String, artist: String, isPlaying: Boolean) {
            val intent = Intent(context, OmniForegroundService::class.java).apply {
                action = ACTION_UPDATE_MEDIA
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MESSAGE, artist)
                putExtra(EXTRA_IS_PLAYING, isPlaying)
            }
            context.startService(intent)
        }

        fun stopMedia(context: Context) {
            mediaActionListener = null
            val intent = Intent(context, OmniForegroundService::class.java).apply {
                action = ACTION_STOP_MEDIA
            }
            context.startService(intent)
        }
        private const val CHANNEL_ID = "omni_automation_channel"
        private const val NOTIFICATION_ID = 8842
        const val ACTION_START = "com.omni.hub.action.START_FOREGROUND"
        const val ACTION_STOP = "com.omni.hub.action.STOP_FOREGROUND"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"

        fun start(context: Context, title: String, message: String) {
            val intent = Intent(context, OmniForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MESSAGE, message)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun update(context: Context, title: String, message: String) {
            start(context, title, message)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OmniForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}