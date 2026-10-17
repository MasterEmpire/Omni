package com.omni.plugin.keyboard.engine

import org.json.JSONArray
import java.io.File

object ClipHistoryRepo {
    private const val MAX_CLIPS = 20

    fun getHistory(dataDir: File): List<String> {
        val file = File(dataDir, "clip_history.json")
        if (!file.exists()) return emptyList()
        val list = mutableListOf<String>()
        try {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
        } catch (_: Exception) {}
        return list
    }

    fun addClip(dataDir: File, text: String) {
        if (text.isBlank()) return
        val list = getHistory(dataDir).toMutableList()
        list.remove(text)
        list.add(0, text)
        if (list.size > MAX_CLIPS) {
            list.removeAt(list.size - 1)
        }
        saveHistory(dataDir, list)
    }

    fun clear(dataDir: File) {
        saveHistory(dataDir, emptyList())
    }

    private fun saveHistory(dataDir: File, list: List<String>) {
        try {
            val file = File(dataDir, "clip_history.json")
            file.parentFile?.mkdirs()
            val array = JSONArray()
            list.forEach { array.put(it) }
            file.writeText(array.toString(), Charsets.UTF_8)
        } catch (_: Exception) {}
    }
}