package com.omni.hub.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class OmniForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Omni Hub Task"
        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: "Automation in progress..."

        createNotificationChannel()
        val notification = buildNotification(title, message)
        startForeground(NOTIFICATION_ID, notification)

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