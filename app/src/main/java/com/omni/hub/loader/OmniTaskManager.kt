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

    fun launchOrResume(
        context: Context,
        pluginId: String,
        pluginName: String,
        entryClass: String
    ): AppTaskSession {
        val existing = activeSessions.find { it.pluginId == pluginId }
        if (existing != null) {
            OmniLogger.log("TASK_MANAGER", "Resuming existing session for [$pluginName]")
            currentForegroundSession = existing
            return existing
        }

        OmniLogger.log("TASK_MANAGER", "Instantiating new session for [$pluginName] ($entryClass)")
        val loaded = PluginLoader.loadFromDir(context, pluginId, entryClass)

        val bridge = HostBridgeImpl(context, loaded.baseDir) {
            suspendCurrent()
        }

        val pluginView = loaded.instance.onCreateView(context, bridge, loaded.baseDir.absolutePath)
        val session = AppTaskSession(
            taskId = "task_${System.currentTimeMillis()}",
            pluginId = pluginId,
            pluginName = pluginName,
            entryClass = entryClass,
            pluginView = pluginView,
            bridge = bridge,
            loadedPlugin = loaded
        )

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

    fun suspendCurrent() {
        val current = currentForegroundSession ?: return
        OmniLogger.log("TASK_MANAGER", "Suspending [${current.pluginName}] to Recents")
        captureSnapshot(current)
        currentForegroundSession = null
    }

    fun resumeSession(session: AppTaskSession) {
        OmniLogger.log("TASK_MANAGER", "Resuming [${session.pluginName}] from Recents")
        activeSessions.remove(session)
        activeSessions.add(0, session)
        currentForegroundSession = session
    }

    fun killTask(context: Context, taskId: String) {
        val target = activeSessions.find { it.taskId == taskId } ?: return
        OmniLogger.log("TASK_MANAGER", "Killing task [${target.pluginName}]")
        try {
            target.loadedPlugin.instance.onStop(context)
        } catch (e: Exception) {
            OmniLogger.log("TASK_MANAGER_ERR", "Error onStop for [${target.pluginName}]: ${e.message}")
        }
        if (currentForegroundSession?.taskId == taskId) {
            currentForegroundSession = null
        }
        activeSessions.remove(target)
    }

    fun killAllTasks(context: Context) {
        OmniLogger.log("TASK_MANAGER", "Clearing all ${activeSessions.size} active sessions")
        activeSessions.forEach { session ->
            try {
                session.loadedPlugin.instance.onStop(context)
            } catch (_: Exception) {}
        }
        currentForegroundSession = null
        activeSessions.clear()
    }
}