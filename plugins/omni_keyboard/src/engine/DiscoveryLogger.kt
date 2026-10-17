package com.omni.plugin.keyboard.engine

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object DiscoveryLogger {
    private const val FOLDER_NAME = "G-Clone-Discovery"
    private const val FILE_NAME = "writing_habits.jsonl"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun log(context: Context, type: String, data: Map<String, Any>) {
        scope.launch {
            try {
                val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val discoveryDir = File(publicDir, FOLDER_NAME)
                
                if (!discoveryDir.exists()) {
                    val created = discoveryDir.mkdirs()
                    if (!created) {
                        logToInternal(context, type, data)
                        return@launch
                    }
                }
                
                val file = File(discoveryDir, FILE_NAME)
                val entry = formatEntry(type, data)

                FileOutputStream(file, true).use { fos ->
                    fos.write((entry + "\n").toByteArray())
                }
            } catch (_: Exception) {
                logToInternal(context, type, data)
            }
        }
    }

    private fun formatEntry(type: String, data: Map<String, Any>): String {
        val entry = JSONObject()
        entry.put("t", System.currentTimeMillis())
        entry.put("type", type)
        val payload = JSONObject()
        data.forEach { payload.put(it.key, it.value) }
        entry.put("data", payload)
        return entry.toString()
    }

    private fun logToInternal(context: Context, type: String, data: Map<String, Any>) {
        try {
            val file = File(context.filesDir, "fallback_habits.log")
            FileOutputStream(file, true).use { it.write((formatEntry(type, data) + "\n").toByteArray()) }
        } catch (_: Exception) {}
    }
}