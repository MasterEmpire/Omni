package com.omni.hub.api

import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

object OmniLogger {
    private val logBuffer = Collections.synchronizedList(mutableListOf<String>())
    private const val MAX_LOGS = 500

    fun log(tag: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = "[$timestamp] [$tag] $message"
        android.util.Log.d("OmniHub", entry)
        
        synchronized(logBuffer) {
            logBuffer.add(0, entry)
            if (logBuffer.size > MAX_LOGS) {
                logBuffer.removeAt(logBuffer.lastIndex)
            }
        }
    }

    fun getLogs(): String {
        return synchronized(logBuffer) {
            logBuffer.joinToString("\n")
        }
    }

    fun clear() {
        synchronized(logBuffer) {
            logBuffer.clear()
        }
    }
}