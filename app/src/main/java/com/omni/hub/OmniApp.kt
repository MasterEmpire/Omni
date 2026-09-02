package com.omni.hub

import android.app.Application
import com.omni.hub.api.OmniLogger

class OmniApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OmniLogger.init(this)
        OmniLogger.log("APP_INIT", "Omni Hub Application process booted")

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTrace = throwable.stackTraceToString()
            OmniLogger.logTelemetry("CRASH_PANIC", "Hardware/Memory state at crash moment")
            OmniLogger.log("CRASH_FATAL", "💥 UNCAUGHT EXCEPTION on [${thread.name}]: ${throwable.message}\n$stackTrace", forceSync = true)
            OmniLogger.flushSync()
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}