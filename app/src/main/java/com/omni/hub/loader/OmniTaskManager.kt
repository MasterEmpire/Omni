package com.omni.hub.loader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.omni.hub.api.HostBridgeImpl
import com.omni.hub.api.OmniLogger

data class AppTaskSession(
    val taskId: String,
    val pluginId: String,
    val pluginName: String,
    val entryClass: String,
    val pluginView: View,
    val bridge: HostBridgeImpl,
    val loadedPlugin: LoadedPlugin,
    var thumbnail: Bitmap? = null,
    val startedAt: Long = System.currentTimeMillis()
)

object OmniTaskManager {
    val activeSessions = mutableStateListOf<AppTaskSession>()
    var currentForegroundSession by mutableStateOf<AppTaskSession?>(null)
    var isRecentsModalOpen by mutableStateOf(false)
    val sessionHistory = mutableListOf<String>()

    fun pushHistory(taskId: String) {
        sessionHistory.removeAll { it == taskId }
        sessionHistory.add(taskId)
    }

    fun openRecents() {
        currentForegroundSession?.let { captureSnapshot(it) }
        isRecentsModalOpen = true
    }

    fun closeRecents() {
        isRecentsModalOpen = false
    }

    fun launchOrResume(
        context: Context,
        pluginId: String,
        pluginName: String,
        entryClass: String
    ): AppTaskSession {
        val existing = activeSessions.find { it.pluginId == pluginId }
        if (existing != null) {
            val act = existing.pluginView.context as? android.app.Activity
            if (act != null && (act.isDestroyed || act.isFinishing)) {
                OmniLogger.log("TASK_MANAGER", "Existing session for [$pluginName] has dead activity context. Purging.")
                killTask(context, existing.taskId)
            } else {
                            OmniLogger.log("TASK_MANAGER", "Resuming existing session for [$pluginName]")
            if (currentForegroundSession != null && currentForegroundSession?.taskId != existing.taskId) {
                pushHistory(currentForegroundSession!!.taskId)
            }
            currentForegroundSession = existing
            return existing
            }
        }

        OmniLogger.log("TASK_MANAGER", "Instantiating new session for [$pluginName] ($entryClass)")
        val loaded = PluginLoader.loadFromDir(context, pluginId, entryClass)

        val bridge = HostBridgeImpl(context, loaded.dataDir) {
            suspendCurrent()
        }

        OmniLogger.log("BOOT_TRACE", "OmniTaskManager: invoking onCreateView for [$pluginName]...", forceSync = true)
        val pluginView = loaded.instance.onCreateView(context, bridge, loaded.baseDir.absolutePath)
        OmniLogger.log("BOOT_TRACE", "OmniTaskManager: onCreateView finished for [$pluginName]", forceSync = true)
        (pluginView as? androidx.compose.ui.platform.AbstractComposeView)?.apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
        val session = AppTaskSession(
            taskId = "task_${System.currentTimeMillis()}",
            pluginId = pluginId,
            pluginName = pluginName,
            entryClass = entryClass,
            pluginView = pluginView,
            bridge = bridge,
            loadedPlugin = loaded
        )

        if (currentForegroundSession != null) {
            pushHistory(currentForegroundSession!!.taskId)
        }
        activeSessions.add(0, session)
        currentForegroundSession = session
        return session
    }

    fun captureSnapshot(session: AppTaskSession) {
        val v = session.pluginView
        if (v.width > 0 && v.height > 0) {
            try {
                val scale = 0.35f
                val w = (v.width * scale).toInt().coerceAtLeast(1)
                val h = (v.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                val canvas = Canvas(bitmap)
                canvas.scale(scale, scale)
                v.draw(canvas)
                session.thumbnail = bitmap
            } catch (_: Exception) {}
        }
    }

    fun suspendCurrent(returnToDashboard: Boolean = false) {
        val current = currentForegroundSession ?: return
        OmniLogger.log("TASK_MANAGER", "Suspending [${current.pluginName}]")
        captureSnapshot(current)
        (current.pluginView.parent as? android.view.ViewGroup)?.removeView(current.pluginView)

        if (returnToDashboard) {
            sessionHistory.clear()
            currentForegroundSession = null
            return
        }

        var nextSession: AppTaskSession? = null
        while (sessionHistory.isNotEmpty()) {
            val prevTaskId = sessionHistory.removeAt(sessionHistory.lastIndex)
            val found = activeSessions.find { it.taskId == prevTaskId }
            if (found != null && found.taskId != current.taskId) {
                nextSession = found
                break
            }
        }

        if (nextSession != null) {
            OmniLogger.log("TASK_MANAGER", "Navigating back to previous session [${nextSession.pluginName}]")
            currentForegroundSession = nextSession
        } else {
            OmniLogger.log("TASK_MANAGER", "No previous session in stack. Returning to host dashboard.")
            currentForegroundSession = null
        }
    }

    fun resumeSession(session: AppTaskSession) {
        OmniLogger.log("TASK_MANAGER", "Resuming [${session.pluginName}] from Recents")
        if (currentForegroundSession != null && currentForegroundSession?.taskId != session.taskId) {
            pushHistory(currentForegroundSession!!.taskId)
        }
        activeSessions.remove(session)
        activeSessions.add(0, session)
        currentForegroundSession = session
    }

    fun killTask(context: Context, taskId: String) {
        val target = activeSessions.find { it.taskId == taskId } ?: return
        OmniLogger.log("TASK_MANAGER", "Killing task UI [${target.pluginName}]")
        sessionHistory.removeAll { it == taskId }

        val isDaemonActive = PluginTaskEngine.isDaemonEnabled(context, target.pluginId) || target.pluginId == "scroll_lock"
        if (!isDaemonActive) {
            try {
                target.bridge.dismissAllOverlays()
                target.loadedPlugin.instance.onStop(context)
            } catch (e: Exception) {
                OmniLogger.log("TASK_MANAGER_ERR", "Error onStop for [${target.pluginName}]: ${e.message}")
            }
        } else {
            OmniLogger.log("TASK_MANAGER", "🛡️ ${target.pluginName} closed from Recents: UI dismissed, but background daemon stays running.")
        }

        val isTargetForeground = currentForegroundSession?.taskId == taskId
        activeSessions.remove(target)

        if (isTargetForeground) {
            suspendCurrent()
        }
    }

    fun killAllTasks(context: Context) {
        OmniLogger.log("TASK_MANAGER", "Clearing all ${activeSessions.size} active sessions")
        sessionHistory.clear()
        activeSessions.forEach { session ->
            val isDaemonActive = PluginTaskEngine.isDaemonEnabled(context, session.pluginId) || session.pluginId == "scroll_lock"
            if (!isDaemonActive) {
                try {
                    session.bridge.dismissAllOverlays()
                    session.loadedPlugin.instance.onStop(context)
                } catch (_: Exception) {}
            } else {
                OmniLogger.log("TASK_MANAGER", "🛡️ ${session.pluginName} closed from Recents: UI dismissed, but background daemon stays running.")
            }
        }
        currentForegroundSession = null
        activeSessions.clear()
    }

    fun reloadPluginSession(
        context: Context,
        pluginId: String,
        pluginName: String,
        entryClass: String,
        reopenForeground: Boolean = true
    ): AppTaskSession {
        OmniLogger.log("TASK_MANAGER", "Hot-reloading mini app session for [$pluginName] ($pluginId)")
        val wasForeground = currentForegroundSession?.pluginId == pluginId
        
        val existing = activeSessions.find { it.pluginId == pluginId }
        if (existing != null) {
            killTask(context, existing.taskId)
        }

        val newSession = launchOrResume(context, pluginId, pluginName, entryClass)
        if (!wasForeground && !reopenForeground) {
            suspendCurrent(returnToDashboard = true)
        }
        return newSession
    }
}