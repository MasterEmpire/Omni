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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val runningTasks = ConcurrentHashMap<String, Job>()
    private val activeInstances = ConcurrentHashMap<String, Pair<PluginEntry, HostBridgeImpl>>()
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

        OmniLogger.log("TASK_ENGINE", "⚡ Resurrecting ${daemons.size} persistent daemon(s)...")
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
        if (isTaskRunning(pluginId)) return

        val job = scope.launch {
            try {
                val loadedPlugin = PluginLoader.loadFromDir(context, pluginId, entryClass)
                val bridge = HostBridgeImpl(context, loadedPlugin.dataDir) {
                    stopTask(context, pluginId)
                }

                activeInstances[pluginId] = Pair(loadedPlugin.instance, bridge)
                bridge.log("TASK_ENGINE", "Starting headless execution for [$pluginId]")

                loadedPlugin.instance.onStart(context, bridge, loadedPlugin.baseDir.absolutePath)

                if (timeoutMins > 0) {
                    delay(timeoutMins * 60 * 1000L)
                    bridge.log("TASK_ENGINE", "Task [$pluginId] reached timeout of ${timeoutMins}m. Stopping.")
                    stopTask(context, pluginId)
                }
            } catch (e: Exception) {
                OmniLogger.log("TASK_ENGINE_ERR", "Error in headless task [$pluginId]: ${e.message}\n${e.stackTraceToString()}")
                stopTask(context, pluginId)
            }
        }

        runningTasks[pluginId] = job
    }

    fun stopTask(context: Context, pluginId: String) {
        if (pluginId == "scroll_lock") {
            OmniLogger.log("TASK_ENGINE", "🛡️ ScrollLock has God-Mode immunity. stopTask rejected.")
            return
        }
        runningTasks.remove(pluginId)?.cancel()
        activeInstances.remove(pluginId)?.let { (instance, bridge) ->
            try {
                bridge.log("TASK_ENGINE", "Invoking onStop() for [$pluginId]")
                instance.onStop(context)
            } catch (e: Exception) {
                android.util.Log.e("PluginTaskEngine", "Error stopping task [$pluginId]", e)
            }
        }
    }
}