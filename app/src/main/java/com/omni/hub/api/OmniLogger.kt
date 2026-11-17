package com.omni.hub.api

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object OmniLogger {
    private val logBuffer = Collections.synchronizedList(mutableListOf<String>())
    private const val MAX_LOGS = 400
    private const val MAX_EXTERNAL_LOG_BYTES = 2 * 1024 * 1024L

    private var logFile: File? = null
    private var blackBoxFile: File? = null
    private var externalLogFile: File? = null
    private var appContext: Context? = null

    private val externalIoExecutor = Executors.newSingleThreadExecutor()
    private val timestampFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        }
    }

    private fun formatTimestamp(ms: Long = System.currentTimeMillis()): String {
        return timestampFormat.get()?.format(Date(ms)) ?: ms.toString()
    }

    private fun resolveExternalLogFile(): File? {
        return try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(downloads, "OmniLogs")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "omni_blackbox.log")
            if (file.exists() && file.length() > MAX_EXTERNAL_LOG_BYTES) {
                val prev = File(dir, "omni_blackbox.prev.log")
                if (prev.exists()) prev.delete()
                file.renameTo(prev)
            }
            file
        } catch (_: Exception) {
            null
        }
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext

        try {
            logFile = File(context.filesDir, "omni_system_trace.txt")
            blackBoxFile = File(context.filesDir, "omni_blackbox.log")
            externalLogFile = resolveExternalLogFile()

            // Recover Black Box historical trace from previous session (post-crash resurrection)
            if (blackBoxFile?.exists() == true && blackBoxFile!!.length() > 0) {
                val previousTrace = blackBoxFile!!.readLines().takeLast(200).map {
                    if (it.length > 800) it.take(800) + "... [TRUNCATED]" else it
                }
                synchronized(logBuffer) {
                    logBuffer.clear()
                    logBuffer.add(0, "=== [BLACK BOX RECOVERY: PREVIOUS SESSION AUDIT TRAIL] ===")
                    logBuffer.addAll(previousTrace.reversed())
                    logBuffer.add(0, "=== [CURRENT SESSION BOOT RECORD | ${formatTimestamp()} | PID=${Process.myPid()}] ===")
                }
            } else if (logFile?.exists() == true) {
                val lines = logFile!!.readLines().takeLast(150).map {
                    if (it.length > 800) it.take(800) + "... [TRUNCATED]" else it
                }
                synchronized(logBuffer) {
                    logBuffer.clear()
                    logBuffer.addAll(lines.reversed())
                }
            }

            appendExternalSync("\n=== [BOOT | ${formatTimestamp()} | PID=${Process.myPid()}] ===\n")
        } catch (_: Exception) {}
    }

    fun logProcessExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            if (reasons.isEmpty()) return

            val prefs = context.getSharedPreferences("omni_logger_prefs", Context.MODE_PRIVATE)
            val lastSeenTs = prefs.getLong("last_exit_ts", 0L)
            var newestTs = lastSeenTs

            reasons.reversed().forEach { info ->
                if (info.timestamp > lastSeenTs) {
                    if (info.timestamp > newestTs) newestTs = info.timestamp
                    val reasonName = when (info.reason) {
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (OS LMK Kill)"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (C++/WebView Signal)"
                        ApplicationExitInfo.REASON_CRASH -> "CRASH (Unhandled Java/Kotlin)"
                        ApplicationExitInfo.REASON_ANR -> "ANR (Main Thread Hang)"
                        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
                        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (OS Signal ${info.status})"
                        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (Swiped/Force Stopped)"
                        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
                        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
                        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
                        else -> "OTHER (${info.reason})"
                    }
                    val pssMb = info.pss / 1024L
                    val rssMb = info.rss / 1024L
                    val whenStr = formatTimestamp(info.timestamp)
                    val msg = "💀 PREVIOUS EXIT [pid=${info.pid} at $whenStr]: reason=$reasonName | status=${info.status} | importance=${info.importance} | PSS=${pssMb}MB | RSS=${rssMb}MB | desc='${info.description}'"
                    log("EXIT_REASON", msg, forceSync = true)
                }
            }
            if (newestTs > lastSeenTs) {
                prefs.edit().putLong("last_exit_ts", newestTs).apply()
            }
        } catch (e: Exception) {
            log("EXIT_REASON_ERR", "Could not query exit reasons: ${e.message}")
        }
    }

    private fun appendExternalSync(line: String) {
        try {
            val ext = externalLogFile ?: resolveExternalLogFile()?.also { externalLogFile = it } ?: return
            FileOutputStream(ext, true).use { fos ->
                fos.write(line.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
        } catch (_: Exception) {}
    }

    fun log(tag: String, message: String, forceSync: Boolean = false) {
        val timestamp = formatTimestamp()
        val safeMessage = if (message.length > 800) {
            message.take(800) + "... [TRUNCATED ${message.length - 800} chars]"
        } else message
        val entry = "[$timestamp] [$tag] $safeMessage"
        android.util.Log.d("OmniHub", entry)

        val isCritical = forceSync ||
            tag.contains("ERR") ||
            tag.contains("FATAL") ||
            tag.contains("CRASH") ||
            tag.contains("EXIT_REASON") ||
            tag.contains("MEM_") ||
            tag.contains("CONTAINER_LIFE")

        try {
            if (isCritical) {
                blackBoxFile?.let { f ->
                    FileOutputStream(f, true).use { fos ->
                        fos.write("$entry\n".toByteArray(Charsets.UTF_8))
                        fos.flush()
                        fos.fd.sync()
                    }
                }
                appendExternalSync("$entry\n")
            } else {
                blackBoxFile?.appendText("$entry\n")
                externalIoExecutor.execute {
                    try {
                        val ext = externalLogFile ?: resolveExternalLogFile()?.also { externalLogFile = it }
                        ext?.appendText("$entry\n")
                    } catch (_: Exception) {}
                }
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
            val actManager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            val availMb = memInfo.availMem / (1024 * 1024)
            val totalMb = memInfo.totalMem / (1024 * 1024)
            val rt = Runtime.getRuntime()
            val javaUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val javaMaxMb = rt.maxMemory() / (1024 * 1024)
            val telemetryMsg = "TELEMETRY -> JavaHeap: ${javaUsedMb}MB/${javaMaxMb}MB | SysAvailRAM: ${availMb}MB/${totalMb}MB | LowMem: ${memInfo.lowMemory} | $details"
            log(tag, telemetryMsg, forceSync = true)
        } catch (_: Exception) {}
    }

    fun flushSync() {
        try {
            blackBoxFile?.let { f ->
                FileOutputStream(f, true).use { fos ->
                    fos.flush()
                    fos.fd.sync()
                }
            }
            externalLogFile?.let { f ->
                FileOutputStream(f, true).use { fos ->
                    fos.flush()
                    fos.fd.sync()
                }
            }
        } catch (_: Exception) {}
    }

    private fun trimDiskFile() {
        Thread {
            try {
                fun trimFile(file: File?, maxLines: Int = 250) {
                    if (file?.exists() == true && file.length() > 64 * 1024L) {
                        val lines = file.readLines().takeLast(maxLines)
                        file.writeText(lines.joinToString("\n") + "\n")
                    }
                }
                trimFile(logFile)
                trimFile(blackBoxFile)
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
            appendExternalSync("\n=== [IN-APP CONSOLE CLEARED | ${formatTimestamp()}] ===\n")
        } catch (_: Exception) {}
        synchronized(logBuffer) {
            logBuffer.clear()
        }
    }
}