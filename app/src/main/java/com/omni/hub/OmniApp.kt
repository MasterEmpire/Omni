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
            OmniLogger.log("CRASH_FATAL", "💥 UNCAUGHT CRASH on ${thread.name}: ${throwable.message}\n$stackTrace")
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}