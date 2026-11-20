package com.omni.hub

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import com.omni.hub.api.OmniLogger
import com.omni.hub.loader.OmniTaskManager

class OmniApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OmniLogger.init(this)
        OmniLogger.logProcessExitReasons(this)
        OmniLogger.logTelemetry("APP_INIT", "Omni Hub Application process booted")
        com.omni.hub.api.PluginEventDispatcher.registerHandler { appCtx, sourcePluginId, targetPluginId, event, payload ->
            try {
                val loaded = com.omni.hub.loader.PluginLoader.loadFromDir(appCtx, targetPluginId)
                val targetBridge = com.omni.hub.api.HostBridgeImpl(appCtx, loaded.dataDir, targetPluginId) {}
                val enriched = java.util.HashMap<String, Any>(payload.size + 4)
                enriched.putAll(payload)
                if (!enriched.containsKey("context")) enriched["context"] = appCtx
                if (!enriched.containsKey("bridge")) enriched["bridge"] = targetBridge
                enriched["source_plugin_id"] = sourcePluginId
                enriched["target_plugin_id"] = targetPluginId
                loaded.instance.onSystemEvent(event, enriched)
                true
            } catch (e: Exception) {
                OmniLogger.log("PLUGIN_IPC_ERR", "dispatchPluginEvent($targetPluginId, $event) failed: ${e.message}")
                false
            }
        }
        com.omni.hub.api.PluginEventDispatcher.registerHandler { appCtx, sourcePluginId, targetPluginId, event, payload ->
            try {
                val loaded = com.omni.hub.loader.PluginLoader.loadFromDir(appCtx, targetPluginId)
                val targetBridge = com.omni.hub.api.HostBridgeImpl(appCtx, loaded.dataDir, targetPluginId) {}
                val enriched = java.util.HashMap<String, Any>(payload.size + 4)
                enriched.putAll(payload)
                if (!enriched.containsKey("context")) enriched["context"] = appCtx
                if (!enriched.containsKey("bridge")) enriched["bridge"] = targetBridge
                enriched["source_plugin_id"] = sourcePluginId
                enriched["target_plugin_id"] = targetPluginId
                loaded.instance.onSystemEvent(event, enriched)
                true
            } catch (e: Exception) {
                OmniLogger.log("PLUGIN_IPC_ERR", "dispatchPluginEvent($targetPluginId, $event) failed: ${e.message}")
                false
            }
        }

        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                val levelName = when (level) {
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
                    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
                    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
                    ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
                    ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
                    ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
                    else -> "LEVEL_$level"
                }
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                    OmniLogger.logTelemetry("MEM_TRIM", "OS onTrimMemory($levelName)")
                    OmniTaskManager.trimMemory(level)
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) {}

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                OmniLogger.logTelemetry("MEM_LOW", "OS onLowMemory() triggered!")
                OmniTaskManager.trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            }
        })

        // Resurrect all registered dynamic daemons asynchronously on process boot
        Thread {
            try {
                com.omni.hub.loader.PluginTaskEngine.resurrectDaemons(this)
            } catch (_: Exception) {}
        }.start()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTrace = throwable.stackTraceToString()
            OmniLogger.logTelemetry("CRASH_PANIC", "Hardware/Memory state at crash moment")
            OmniLogger.log("CRASH_FATAL", "💥 UNCAUGHT EXCEPTION on [${thread.name}]: ${throwable.message}\n$stackTrace", forceSync = true)
            OmniLogger.flushSync()

            val isMainThread = android.os.Looper.getMainLooper().thread == thread
            val isDynamicCode = stackTrace.contains("com.omni.plugin") ||
                stackTrace.contains("dalvik.system.DexClassLoader") ||
                thread.name.startsWith("omni-") ||
                thread.name.contains("coroutine", ignoreCase = true)

            if (isDynamicCode) {
                OmniLogger.log("CRASH_CONTAINED", "🛡️ Contained fatal crash on [${thread.name}] from dynamic code. Preserving host process.", forceSync = true)
                if (isMainThread) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            OmniTaskManager.finishForegroundOnDynamicCrash()
                            android.widget.Toast.makeText(this@OmniApp, "🛡️ Contained plugin crash. Returning safely.", android.widget.Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {}
                    }
                    while (true) {
                        try {
                            android.os.Looper.loop()
                            break
                        } catch (looperThrowable: Throwable) {
                            OmniLogger.log("LOOPER_CONTAINED", "🛡️ Suppressed secondary main looper exception: ${looperThrowable.message}", forceSync = true)
                        }
                    }
                }
                return@setDefaultUncaughtExceptionHandler
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}