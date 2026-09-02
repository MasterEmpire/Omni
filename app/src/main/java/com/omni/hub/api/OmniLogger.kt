package com.omni.hub.api

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

object OmniLogger {
    private val logBuffer = Collections.synchronizedList(mutableListOf<String>())
    private const val MAX_LOGS = 500
    private var logFile: File? = null

    fun init(context: Context) {
        if (logFile != null) return
        try {
            logFile = File(context.filesDir, "omni_system_trace.txt")
            if (logFile?.exists() == true) {
                val lines = logFile!!.readLines().takeLast(200)
                synchronized(logBuffer) {
                    logBuffer.clear()
                    logBuffer.addAll(lines.reversed())
                }
            }
        } catch (_: Exception) {}
    }

    fun log(tag: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = "[$timestamp] [$tag] $message"
        android.util.Log.d("OmniHub", entry)
        
        try {
            logFile?.appendText("$entry\n")
        } catch (_: Exception) {}

        synchronized(logBuffer) {
            logBuffer.add(0, entry)
            if (logBuffer.size > MAX_LOGS) {
                logBuffer.removeAt(logBuffer.lastIndex)
                if (logBuffer.size % 50 == 0) {
                    trimDiskFile()
                }
            }
        }
    }

    private fun trimDiskFile() {
        Thread {
            try {
                val lines = logFile?.readLines()?.takeLast(200) ?: return@Thread
                logFile?.writeText(lines.joinToString("\n") + "\n")
            } catch (_: Exception) {}
        }.start()
    }

    fun getLogs(): String {
        return synchronized(logBuffer) {
            logBuffer.joinToString("\n")
        }
    }

    fun clear() {
        try {
            logFile?.delete()
        } catch (_: Exception) {}
        synchronized(logBuffer) {
            logBuffer.clear()
        }
    }
}