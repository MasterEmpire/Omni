package com.omni.hub.loader

import android.content.Context
import com.omni.hub.api.HostBridgeImpl
import com.omni.hub.api.PluginEntry
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

object PluginTaskEngine {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val runningTasks = ConcurrentHashMap<String, Job>()
    private val activeInstances = ConcurrentHashMap<String, Pair<PluginEntry, HostBridgeImpl>>()

    fun isTaskRunning(pluginId: String): Boolean = runningTasks[pluginId]?.isActive == true

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
                val bridge = HostBridgeImpl(context, loadedPlugin.baseDir) {
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
                android.util.Log.e("PluginTaskEngine", "Error in headless task [$pluginId]", e)
                stopTask(context, pluginId)
            }
        }

        runningTasks[pluginId] = job
    }

    fun stopTask(context: Context, pluginId: String) {
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