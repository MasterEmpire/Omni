package com.omni.hub.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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

        if (action == "com.omni.hub.action.ABORT_SOLVE") {
            OmniLogger.log("OMNI_SOLVER", "🛑 Received explicit ABORT_SOLVE from client app. Terminating background solver.")
            try {
                val loadedPlugin = PluginLoader.loadFromDir(this, "omni_browser")
                val hostBridge = HostBridgeImpl(this, loadedPlugin.dataDir) {}
                loadedPlugin.instance.onSystemEvent("ABORT_SOLVE", mapOf("context" to this, "bridge" to hostBridge))
            } catch (e: Exception) {
                OmniLogger.log("OMNI_SOLVER_ERR", "Failed dispatching ABORT_SOLVE to plugin: ${e.message}")
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action != "com.omni.hub.action.SOLVE_EXAM") {
            return START_NOT_STICKY
        }

        var replyAction = "com.universal.app.ACTION_OMNI_RESULT"
        var statusAction = "com.universal.app.ACTION_OMNI_STATUS"

        try {
            createNotificationChannel()
            val notification = buildNotification("Omni Hub Exam Solver", "Processing exam images headlessly...")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            replyAction = intent.getStringExtra("extra_reply_action") ?: replyAction
            statusAction = intent.getStringExtra("extra_status_action") ?: statusAction
            val presetTitle = intent.getStringExtra("extra_preset_title") ?: "Exam Solver"
            val userPrompt = intent.getStringExtra("extra_user_prompt") ?: ""
            val requestId = intent.getStringExtra("extra_request_id") ?: "req_${System.currentTimeMillis()}"

            val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra("extra_image_uris", Uri::class.java) ?: arrayListOf()
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>("extra_image_uris") ?: arrayListOf()
            }

            OmniLogger.log("OMNI_SOLVER", "📥 Accepted solve request [$requestId] with ${uris.size} image URI(s). Preset: '$presetTitle'")

            val loadedPlugin = PluginLoader.loadFromDir(this, "omni_browser")
            val hostBridge = HostBridgeImpl(this, loadedPlugin.dataDir) {
                OmniLogger.log("OMNI_SOLVER", "Host bridge close requested for [$requestId]. Stopping service.")
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

        } catch (t: Throwable) {
            OmniLogger.log("OMNI_SOLVER_ERR", "💥 Fatal exception in OmniSolveService: ${t.message}\n${t.stackTraceToString()}")
            sendResultBroadcast(this, replyAction, success = false, json = null, error = "Omni Hub Service Error: ${t.message}")
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