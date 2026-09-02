package com.omni.hub.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.omni.hub.api.HostBridgeImpl
import com.omni.hub.api.OmniLogger
import com.omni.hub.loader.PluginLoader

class OmniSolveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != "com.omni.hub.action.SOLVE_EXAM") {
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = buildNotification("Omni Hub Exam Solver", "Processing exam images headlessly...")
        startForeground(NOTIFICATION_ID, notification)

        val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra("extra_image_uris", Uri::class.java) ?: arrayListOf()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>("extra_image_uris") ?: arrayListOf()
        }

        val replyAction = intent.getStringExtra("extra_reply_action") ?: "com.universal.app.ACTION_OMNI_RESULT"
        val statusAction = intent.getStringExtra("extra_status_action") ?: "com.universal.app.ACTION_OMNI_STATUS"
        val presetTitle = intent.getStringExtra("extra_preset_title") ?: ""
        val userPrompt = intent.getStringExtra("extra_user_prompt") ?: ""
        val requestId = intent.getStringExtra("extra_request_id") ?: "req_${System.currentTimeMillis()}"

        OmniLogger.log("OMNI_SOLVER", "Received exam solve request [$requestId] with ${uris.size} image URI(s).")

        try {
            val loadedPlugin = PluginLoader.loadFromDir(this, "omni_browser")
            val hostBridge = HostBridgeImpl(this, loadedPlugin.dataDir) {
                stopSelf()
            }

            val payload = mapOf(
                "context" to this,
                "bridge" to hostBridge,
                "uris" to uris,
                "reply_action" to replyAction,
                "status_action" to statusAction,
                "preset_title" to presetTitle,
                "user_prompt" to userPrompt,
                "request_id" to requestId
            )

            loadedPlugin.instance.onSystemEvent("SOLVE_EXAM", payload)

        } catch (e: Exception) {
            OmniLogger.log("OMNI_SOLVER_ERR", "Failed initializing omni_browser plugin: ${e.message}")
            sendResultBroadcast(this, replyAction, success = false, json = null, error = "Omni Hub Plugin Error: ${e.message}")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Omni Hub Solver Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Headless AI Studio solver background notification"
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

    private fun sendResultBroadcast(context: Context, action: String, success: Boolean, json: String?, error: String?) {
        val intent = Intent(action).apply {
            putExtra("extra_success", success)
            putExtra("extra_solution_json", json ?: "")
            putExtra("extra_error", error ?: "")
            setPackage("com.universal.app")
        }
        context.sendBroadcast(intent)
    }

    companion object {
        private const val CHANNEL_ID = "omni_solver_channel"
        private const val NOTIFICATION_ID = 8845
    }
}