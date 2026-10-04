package com.omni.hub.loader

import android.content.Context
import com.omni.hub.api.HostBridgeImpl
import com.omni.hub.api.OmniLogger
import com.omni.hub.api.PluginEntry
import com.omni.hub.services.OmniForegroundService
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object PluginTaskEngine {

    private val crashHandler = CoroutineExceptionHandler { _, throwable ->
        OmniLogger.log("TASK_ENGINE_CRASH", "Trapped unhandled coroutine crash in task engine: ${throwable.message}")
    }
    private val supervisorScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + crashHandler)
    private val runningTasks = ConcurrentHashMap<String, Job>()
    private val executionLock = Any()
    private val activeInstances = ConcurrentHashMap<String, Pair<PluginEntry, HostBridgeImpl>>()
    private val retryAttempts = ConcurrentHashMap<String, Int>()
    private val scheduledRetries = ConcurrentHashMap<String, Job>()
    private const val PREFS_NAME = "omni_daemon_registry"
    private const val KEY_DAEMONS = "active_daemons"

    fun isTaskRunning(pluginId: String): Boolean = runningTasks[pluginId]?.isActive == true

    fun isDaemonEnabled(context: Context, pluginId: String): Boolean {
        return getRegisteredDaemons(context).containsKey(pluginId)
    }

    fun getRegisteredDaemons(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_DAEMONS, "{}") ?: "{}"
        val map = mutableMapOf<String, String>()
        try {
            val json = JSONObject(jsonStr)
            json.keys().forEach { k ->
                map[k] = json.getString(k)
            }
        } catch (_: Exception) {}
        return map
    }

    fun setDaemonEnabled(
        context: Context,
        pluginId: String,
        entryClass: String,
        enabled: Boolean
    ) {
        if (pluginId == "scroll_lock" && !enabled) {
            OmniLogger.log("TASK_ENGINE", "🛡️ ScrollLock has God-Mode immunity. Deactivation blocked.")
            return
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getRegisteredDaemons(context).toMutableMap()
        if (enabled) {
            current[pluginId] = entryClass
            executeHeadless(context, pluginId, entryClass)
            OmniForegroundService.start(
                context,
                "Omni Hub",
                "Background engine active"
            )
        } else {
            current.remove(pluginId)
            stopTask(context, pluginId)
            if (current.isEmpty()) {
                val keepAlivePrefs = context.getSharedPreferences("omni_hub_prefs", Context.MODE_PRIVATE)
                val userKeepAlive = keepAlivePrefs.getBoolean("key_keep_alive", false)
                if (!userKeepAlive) {
                    OmniForegroundService.stop(context)
                }
            }
        }
        val json = JSONObject(current as Map<*, *>)
        prefs.edit().putString(KEY_DAEMONS, json.toString()).apply()
        OmniLogger.log("TASK_ENGINE", "Daemon registry updated: [$pluginId] enabled=$enabled (Total active: ${current.size})")
    }

    fun resurrectDaemons(context: Context) {
        val daemons = getRegisteredDaemons(context)
        if (daemons.isEmpty()) return

        OmniForegroundService.start(
            context,
            "Omni Hub",
            "Background engine active"
        )

        daemons.forEach { (pluginId, entryClass) ->
            if (!isTaskRunning(pluginId)) {
                OmniLogger.log("TASK_ENGINE", "🔄 Auto-starting daemon [$pluginId] ($entryClass)")
                executeHeadless(context, pluginId, entryClass)
            }
        }
    }

    fun executeHeadless(
        context: Context,
        pluginId: String,
        entryClass: String,
        timeoutMins: Long = 0L
    ) {
        synchronized(executionLock) {
            if (isTaskRunning(pluginId)) {
                OmniLogger.log("TASK_ENGINE", "Daemon [$pluginId] already actively running. Skipping duplicate spawn.")
                return
            }

            scheduledRetries.remove(pluginId)?.cancel()

            val job = supervisorScope.launch {
            try {
                val loadedPlugin = PluginLoader.loadFromDir(context, pluginId, entryClass)
                val bridge = HostBridgeImpl(context, loadedPlugin.dataDir) {
                    stopTask(context, pluginId)
                }

                activeInstances[pluginId] = Pair(loadedPlugin.instance, bridge)
                bridge.log("TASK_ENGINE", "Starting headless execution for [$pluginId]")

                loadedPlugin.instance.onStart(context, bridge, loadedPlugin.baseDir.absolutePath)

                // If running smoothly for 30 seconds, reset retry attempts
                delay(30_000L)
                retryAttempts.remove(pluginId)

                if (timeoutMins > 0) {
                    delay((timeoutMins * 60 * 1000L) - 30_000L)
                    bridge.log("TASK_ENGINE", "Task [$pluginId] reached timeout of ${timeoutMins}m. Stopping.")
                    stopTask(context, pluginId)
                } else {
                    // Perpetual daemon execution: keep job suspended and active indefinitely
                    awaitCancellation()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                OmniLogger.log("TASK_ENGINE_ERR", "💥 Exception in headless task [$pluginId]: ${t.message}\n${t.stackTraceToString()}")
                cleanupInstance(context, pluginId)

                // Self-Healing Watchdog: Auto-restart daemon with backoff
                val attempts = (retryAttempts[pluginId] ?: 0) + 1
                retryAttempts[pluginId] = attempts

                val isDaemon = isDaemonEnabled(context, pluginId)
                if (isDaemon) {
                    val backoffMs = (attempts.coerceAtMost(6) * 5_000L)
                    OmniLogger.log("TASK_ENGINE", "🔄 [SELF-HEAL] Daemon [$pluginId] will auto-resurrect in ${backoffMs / 1000}s (Attempt #$attempts)")
                    val retryJob = supervisorScope.launch {
                        delay(backoffMs)
                        if (isDaemonEnabled(context, pluginId) && !isTaskRunning(pluginId)) {
                            executeHeadless(context, pluginId, entryClass, timeoutMins)
                        }
                    }
                    scheduledRetries[pluginId] = retryJob
                }
            }
        }

            runningTasks[pluginId] = job
        }
    }

    private fun cleanupInstance(context: Context, pluginId: String) {
        runningTasks.remove(pluginId)?.cancel()
        activeInstances.remove(pluginId)?.let { (instance, bridge) ->
            try {
                bridge.dismissAllOverlays()
                bridge.log("TASK_ENGINE", "Invoking onStop() for [$pluginId]")
                instance.onStop(context)
            } catch (t: Throwable) {
                android.util.Log.e("PluginTaskEngine", "Error stopping task [$pluginId]", t)
            }
        }
    }

    fun stopTask(context: Context, pluginId: String) {
        if (pluginId == "scroll_lock") {
            OmniLogger.log("TASK_ENGINE", "🛡️ ScrollLock has God-Mode immunity. stopTask rejected.")
            return
        }
        scheduledRetries.remove(pluginId)?.cancel()
        retryAttempts.remove(pluginId)
        cleanupInstance(context, pluginId)
    }
}