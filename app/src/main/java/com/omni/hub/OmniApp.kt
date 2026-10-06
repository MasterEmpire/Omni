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
                            com.omni.hub.loader.OmniTaskManager.suspendCurrent(returnToDashboard = true)
                            android.widget.Toast.makeText(this@OmniApp, "🛡️ Contained plugin crash. Returning to dashboard.", android.widget.Toast.LENGTH_SHORT).show()
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