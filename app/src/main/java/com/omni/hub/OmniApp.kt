package com.omni.hub

import android.app.Application
import com.omni.hub.api.OmniLogger

class OmniApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OmniLogger.init(this)
        OmniLogger.log("APP_INIT", "Omni Hub Application process booted")

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

            // Isolate non-UI background worker crashes originating from dynamic code
            val isMainThread = android.os.Looper.getMainLooper().thread == thread
            val isDynamicCode = stackTrace.contains("com.omni.plugin") ||
                stackTrace.contains("dalvik.system.DexClassLoader") ||
                thread.name.startsWith("omni-") ||
                thread.name.contains("coroutine", ignoreCase = true)

            if (!isMainThread && isDynamicCode) {
                OmniLogger.log("CRASH_CONTAINED", "🛡️ Contained fatal crash on dynamic worker thread [${thread.name}]. Host process preserved.")
                return@setDefaultUncaughtExceptionHandler
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}