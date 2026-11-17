package com.omni.plugin.notes.storage

import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.omni.hub.api.HostBridge
import com.omni.plugin.notes.model.ChecklistEntry
import com.omni.plugin.notes.model.NoteItem
import com.omni.plugin.notes.model.NoteSortOrder
import com.omni.plugin.notes.model.NoteStatus
import com.omni.plugin.notes.model.NoteThemeMode
import com.omni.plugin.notes.model.NoteType
import com.omni.plugin.notes.model.NoteViewMode
import com.omni.plugin.notes.model.NotesSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object NoteRepository {
    private const val NOTES_FILE = "notes_db.json"
    private const val SETTINGS_FILE = "notes_settings.json"
    private const val VAULT_BACKUP_NAME = "omni_notes_backup.json"

    private val dbLock = Any()
    val notes = mutableStateListOf<NoteItem>()
    var settings by mutableStateOf(NotesSettings())

    fun load(bridge: HostBridge) {
        synchronized(dbLock) {
            loadSettingsInternal(bridge)
            loadNotesInternal(bridge)
        }
    }

    private fun loadSettingsInternal(bridge: HostBridge) {
        try {
            val bytes = bridge.readFile(SETTINGS_FILE) ?: return
            val obj = JSONObject(String(bytes, Charsets.UTF_8))
            val viewMode = runCatching { NoteViewMode.valueOf(obj.optString("viewMode", "GRID")) }
                .getOrDefault(NoteViewMode.GRID)
            val sortOrder = runCatching { NoteSortOrder.valueOf(obj.optString("sortOrder", "MODIFIED_DESC")) }
                .getOrDefault(NoteSortOrder.MODIFIED_DESC)
            val themeMode = runCatching { NoteThemeMode.valueOf(obj.optString("themeMode", "DARK")) }
                .getOrDefault(NoteThemeMode.DARK)
            settings = NotesSettings(viewMode, sortOrder, themeMode)
        } catch (_: Exception) {}
    }

    private fun loadNotesInternal(bridge: HostBridge) {
        try {
            val bytes = bridge.readFile(NOTES_FILE) ?: return
            val parsed = parseNotesJson(String(bytes, Charsets.UTF_8))
            notes.clear()
            notes.addAll(parsed)
        } catch (_: Exception) {}
    }

    fun updateSettings(bridge: HostBridge, newSettings: NotesSettings) {
        synchronized(dbLock) {
            settings = newSettings
            try {
                val obj = JSONObject().apply {
                    put("viewMode", newSettings.viewMode.name)
                    put("sortOrder", newSettings.sortOrder.name)
                    put("themeMode", newSettings.themeMode.name)
                }
                bridge.saveFile(SETTINGS_FILE, obj.toString(2).toByteArray(Charsets.UTF_8))
            } catch (_: Exception) {}
        }
    }

    fun upsertNote(bridge: HostBridge, note: NoteItem) {
        synchronized(dbLock) {
            val idx = notes.indexOfFirst { it.id == note.id }
            if (idx >= 0) {
                notes[idx] = note
            } else {
                notes.add(0, note)
            }
            saveNotesInternal(bridge)
        }
    }

    fun markReminderTriggered(bridge: HostBridge, noteId: String) {
        synchronized(dbLock) {
            val idx = notes.indexOfFirst { it.id == noteId }
            if (idx >= 0) {
                notes[idx] = notes[idx].copy(reminderTriggered = true)
                saveNotesInternal(bridge)
            }
        }
    }

    fun setStatus(bridge: HostBridge, noteId: String, status: NoteStatus) {
        synchronized(dbLock) {
            val idx = notes.indexOfFirst { it.id == noteId }
            if (idx >= 0) {
                val existing = notes[idx]
                notes[idx] = existing.copy(
                    status = status,
                    isPinned = if (status == NoteStatus.TRASH) false else existing.isPinned,
                    updatedAtMs = System.currentTimeMillis()
                )
                saveNotesInternal(bridge)
            }
        }
    }

    fun togglePin(bridge: HostBridge, noteId: String) {
        synchronized(dbLock) {
            val idx = notes.indexOfFirst { it.id == noteId }
            if (idx >= 0) {
                val existing = notes[idx]
                notes[idx] = existing.copy(
                    isPinned = !existing.isPinned,
                    updatedAtMs = System.currentTimeMillis()
                )
                saveNotesInternal(bridge)
            }
        }
    }

    fun deleteForever(bridge: HostBridge, noteId: String) {
        synchronized(dbLock) {
            notes.removeAll { it.id == noteId }
            saveNotesInternal(bridge)
        }
    }

    fun emptyTrash(bridge: HostBridge) {
        synchronized(dbLock) {
            notes.removeAll { it.status == NoteStatus.TRASH }
            saveNotesInternal(bridge)
        }
    }

    fun serializeNotesJson(): String {
        val arr = JSONArray()
        notes.forEach { n ->
            val checkArr = JSONArray()
            n.checklist.forEach { item ->
                checkArr.put(JSONObject().apply {
                    put("id", item.id)
                    put("text", item.text)
                    put("isChecked", item.isChecked)
                })
            }
            val obj = JSONObject().apply {
                put("id", n.id)
                put("title", n.title)
                put("content", n.content)
                put("type", n.type.name)
                put("checklist", checkArr)
                put("colorId", n.colorId)
                put("isPinned", n.isPinned)
                put("status", n.status.name)
                if (!n.pinHash.isNullOrBlank()) put("pinHash", n.pinHash)
                if (n.reminderAtMs != null) put("reminderAtMs", n.reminderAtMs)
                put("reminderTriggered", n.reminderTriggered)
                put("createdAtMs", n.createdAtMs)
                put("updatedAtMs", n.updatedAtMs)
            }
            arr.put(obj)
        }
        return arr.toString(2)
    }

    private fun parseNotesJson(rawJson: String): List<NoteItem> {
        val result = mutableListOf<NoteItem>()
        val arr = JSONArray(rawJson)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val checkArr = obj.optJSONArray("checklist") ?: JSONArray()
            val checklist = mutableListOf<ChecklistEntry>()
            for (j in 0 until checkArr.length()) {
                val c = checkArr.getJSONObject(j)
                checklist.add(
                    ChecklistEntry(
                        id = c.optString("id", "chk_${i}_$j"),
                        text = c.optString("text", ""),
                        isChecked = c.optBoolean("isChecked", false)
                    )
                )
            }
            val type = runCatching { NoteType.valueOf(obj.optString("type", "TEXT")) }.getOrDefault(NoteType.TEXT)
            val status = runCatching { NoteStatus.valueOf(obj.optString("status", "ACTIVE")) }.getOrDefault(NoteStatus.ACTIVE)
            val pinHash = obj.optString("pinHash", "").takeIf { it.isNotBlank() && !it.equals("null", true) }
            val reminderAt = if (obj.has("reminderAtMs") && !obj.isNull("reminderAtMs")) obj.getLong("reminderAtMs") else null

            result.add(
                NoteItem(
                    id = obj.getString("id"),
                    title = obj.optString("title", ""),
                    content = obj.optString("content", ""),
                    type = type,
                    checklist = checklist,
                    colorId = obj.optString("colorId", "yellow"),
                    isPinned = obj.optBoolean("isPinned", false),
                    status = status,
                    pinHash = pinHash,
                    reminderAtMs = reminderAt,
                    reminderTriggered = obj.optBoolean("reminderTriggered", false),
                    createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis()),
                    updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis())
                )
            )
        }
        return result
    }

    private fun saveNotesInternal(bridge: HostBridge) {
        try {
            bridge.saveFile(NOTES_FILE, serializeNotesJson().toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {}
    }

    private fun getVaultBackupFile(): File {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dir = File(File(docsDir, ".omni_runtime"), "notes_backup")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, VAULT_BACKUP_NAME)
    }

    fun exportVaultBackup(bridge: HostBridge): String? {
        return try {
            val json = synchronized(dbLock) { serializeNotesJson() }
            val file = getVaultBackupFile()
            file.writeText(json, Charsets.UTF_8)
            bridge.log("OMNI_NOTES", "Exported ${notes.size} notes to vault: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            bridge.log("OMNI_NOTES_ERR", "Vault export failed: ${e.message}")
            null
        }
    }

    fun importVaultBackup(bridge: HostBridge): Int {
        return try {
            val file = getVaultBackupFile()
            if (!file.exists() || file.length() == 0L) return -1
            val imported = parseNotesJson(file.readText(Charsets.UTF_8))
            synchronized(dbLock) {
                val existingById = notes.associateBy { it.id }.toMutableMap()
                var addedOrUpdated = 0
                imported.forEach { item ->
                    val current = existingById[item.id]
                    if (current == null || item.updatedAtMs > current.updatedAtMs) {
                        existingById[item.id] = item
                        addedOrUpdated++
                    }
                }
                notes.clear()
                notes.addAll(existingById.values.sortedByDescending { it.updatedAtMs })
                saveNotesInternal(bridge)
                addedOrUpdated
            }
        } catch (e: Exception) {
            bridge.log("OMNI_NOTES_ERR", "Vault import failed: ${e.message}")
            -2
        }
    }
}