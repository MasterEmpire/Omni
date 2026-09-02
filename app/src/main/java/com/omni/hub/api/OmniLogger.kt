package com.omni.hub.api

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

object OmniLogger {
    private val logBuffer = Collections.synchronizedList(mutableListOf<String>())
    private const val MAX_LOGS = 800
    private var logFile: File? = null
    private var blackBoxFile: File? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext

        try {
            logFile = File(context.filesDir, "omni_system_trace.txt")
            blackBoxFile = File(context.filesDir, "omni_blackbox.log")

            // 1. Recover Black Box historical trace from previous session (post-crash resurrection)
            if (blackBoxFile?.exists() == true && blackBoxFile!!.length() > 0) {
                val previousTrace = blackBoxFile!!.readLines().takeLast(300)
                synchronized(logBuffer) {
                    logBuffer.clear()
                    logBuffer.add(0, "=== [BLACK BOX RECOVERY: PREVIOUS SESSION AUDIT TRAIL] ===")
                    logBuffer.addAll(previousTrace.reversed())
                    logBuffer.add(0, "=== [CURRENT SESSION BOOT RECORD] ===")
                }
            } else if (logFile?.exists() == true) {
                val lines = logFile!!.readLines().takeLast(200)
                synchronized(logBuffer) {
                    logBuffer.clear()
                    logBuffer.addAll(lines.reversed())
                }
            }
        } catch (_: Exception) {}
    }

    fun log(tag: String, message: String, forceSync: Boolean = false) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = "[$timestamp] [$tag] $message"
        android.util.Log.d("OmniHub", entry)

        val isCritical = forceSync || tag.contains("ERR") || tag.contains("FATAL") || tag.contains("CRASH")

        try {
            if (isCritical) {
                // Immediate unbuffered flush with disk sync
                blackBoxFile?.let { f ->
                    java.io.FileOutputStream(f, true).use { fos ->
                        fos.write("$entry\n".toByteArray(Charsets.UTF_8))
                        fos.flush()
                        fos.fd.sync()
                    }
                }
            } else {
                blackBoxFile?.appendText("$entry\n")
            }
            logFile?.appendText("$entry\n")
        } catch (_: Exception) {}

        synchronized(logBuffer) {
            logBuffer.add(0, entry)
            if (logBuffer.size > MAX_LOGS) {
                logBuffer.removeAt(logBuffer.lastIndex)
                if (logBuffer.size % 80 == 0) {
                    trimDiskFile()
                }
            }
        }
    }

    fun logTelemetry(tag: String, details: String = "") {
        val ctx = appContext ?: return
        try {
            val actManager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            val availMb = memInfo.availMem / (1024 * 1024)
            val totalMb = memInfo.totalMem / (1024 * 1024)
            val isLow = memInfo.lowMemory
            val telemetryMsg = "TELEMETRY -> AvailRAM: ${availMb}MB/${totalMb}MB | LowMem: $isLow | $details"
            log(tag, telemetryMsg, forceSync = true)
        } catch (_: Exception) {}
    }

    fun flushSync() {
        try {
            blackBoxFile?.let { f ->
                java.io.FileOutputStream(f, true).use { fos ->
                    fos.flush()
                    fos.fd.sync()
                }
            }
        } catch (_: Exception) {}
    }

    private fun trimDiskFile() {
        Thread {
            try {
                val lines = logFile?.readLines()?.takeLast(300) ?: return@Thread
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
            blackBoxFile?.delete()
        } catch (_: Exception) {}
        synchronized(logBuffer) {
            logBuffer.clear()
        }
    }
}