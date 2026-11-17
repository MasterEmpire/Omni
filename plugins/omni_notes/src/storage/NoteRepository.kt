package com.omni.plugin.notes.storage

import android.os.Environment
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.omni.hub.api.HostBridge
import com.omni.plugin.notes.model.ChecklistEntry
import com.omni.plugin.notes.model.NoteFolder
import com.omni.plugin.notes.model.NoteItem
import com.omni.plugin.notes.model.NoteSecurity
import com.omni.plugin.notes.model.NoteSortOrder
import com.omni.plugin.notes.model.NoteStatus
import com.omni.plugin.notes.model.NoteThemeMode
import com.omni.plugin.notes.model.NoteType
import com.omni.plugin.notes.model.NoteViewMode
import com.omni.plugin.notes.model.NotesSettings
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class ColorNoteImportResult(
    val importedCount: Int,
    val skippedSystemCount: Int,
    val decryptedCount: Int,
    val lockedSkippedOrFailedCount: Int
)

object NoteRepository {
    private const val NOTES_FILE = "notes_db.json"
    private const val FOLDERS_FILE = "notes_folders.json"
    private const val SETTINGS_FILE = "notes_settings.json"
    private const val VAULT_BACKUP_NAME = "omni_notes_backup.json"
    private const val VAULT_FOLDERS_BACKUP_NAME = "omni_notes_folders_backup.json"

    private val dbLock = Any()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    val notes = mutableStateListOf<NoteItem>()
    val folders = mutableStateListOf<NoteFolder>()
    var settings by mutableStateOf(NotesSettings())
    var dataRevision by mutableIntStateOf(0)
    @Volatile var isLoaded = false
    @Volatile private var lastKnownDbModifiedMs = 0L
    @Volatile private var lastKnownFoldersModifiedMs = 0L

    fun snapshotNotes(): List<NoteItem> = synchronized(dbLock) { notes.toList() }
    fun snapshotFolders(): List<NoteFolder> = synchronized(dbLock) { folders.toList() }

    fun load(bridge: HostBridge, forceReload: Boolean = false) {
        if (isLoaded && !forceReload) return
        synchronized(dbLock) {
            if (isLoaded && !forceReload) return
            loadSettingsInternal(bridge)
            loadFoldersInternal(bridge)
            loadNotesInternal(bridge)
            isLoaded = true
        }
    }

    fun syncIfModifiedOnDisk(bridge: HostBridge) {
        val dbFile = File(bridge.getPluginDir(), NOTES_FILE)
        val fldFile = File(bridge.getPluginDir(), FOLDERS_FILE)
        val dbNewer = dbFile.exists() && dbFile.lastModified() > lastKnownDbModifiedMs
        val fldNewer = fldFile.exists() && fldFile.lastModified() > lastKnownFoldersModifiedMs
        if (!dbNewer && !fldNewer) return

        synchronized(dbLock) {
            syncFromDiskIfNewerLocked(bridge)
        }
    }

    private fun syncFromDiskIfNewerLocked(bridge: HostBridge) {
        val fldFile = File(bridge.getPluginDir(), FOLDERS_FILE)
        if (fldFile.exists() && fldFile.lastModified() > lastKnownFoldersModifiedMs) {
            loadFoldersInternal(bridge)
        }
        val dbFile = File(bridge.getPluginDir(), NOTES_FILE)
        if (dbFile.exists() && dbFile.lastModified() > lastKnownDbModifiedMs) {
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
            val rawTheme = obj.optString("themeMode", "DARK")
            val themeMode = if (rawTheme == "PAPER") {
                NoteThemeMode.LIGHT
            } else {
                runCatching { NoteThemeMode.valueOf(rawTheme) }.getOrDefault(NoteThemeMode.DARK)
            }
            val isPaperMode = obj.optBoolean("isPaperMode", rawTheme == "PAPER")
            val masterPinHash = obj.optString("masterPinHash", "").takeIf { it.isNotBlank() && !it.equals("null", true) }
            settings = NotesSettings(viewMode, sortOrder, themeMode, isPaperMode, masterPinHash)
        } catch (_: Exception) {}
    }

    private fun loadNotesInternal(bridge: HostBridge) {
        try {
            val file = File(bridge.getPluginDir(), NOTES_FILE)
            if (!file.exists() || file.length() == 0L) return
            val parsed = readNotesStreaming(file)
            lastKnownDbModifiedMs = file.lastModified()
            notes.clear()
            notes.addAll(parsed)
            dataRevision++
        } catch (e: Exception) {
            bridge.log("OMNI_NOTES_ERR", "Streaming load failed: ${e.message}")
        }
    }

    fun updateSettings(bridge: HostBridge, newSettings: NotesSettings) {
        synchronized(dbLock) {
            settings = newSettings
            saveSettingsInternal(bridge, newSettings)
        }
    }

    private fun saveSettingsInternal(bridge: HostBridge, s: NotesSettings) {
        try {
            val obj = JSONObject().apply {
                put("viewMode", s.viewMode.name)
                put("sortOrder", s.sortOrder.name)
                put("themeMode", s.themeMode.name)
                put("isPaperMode", s.isPaperMode)
                if (!s.masterPinHash.isNullOrBlank()) put("masterPinHash", s.masterPinHash)
            }
            bridge.saveFile(SETTINGS_FILE, obj.toString(2).toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {}
    }

    fun upsertNote(bridge: HostBridge, note: NoteItem) {
        synchronized(dbLock) {
            syncFromDiskIfNewerLocked(bridge)
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
            syncFromDiskIfNewerLocked(bridge)
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
            syncFromDiskIfNewerLocked(bridge)
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
            syncFromDiskIfNewerLocked(bridge)
            notes.removeAll { it.id == noteId }
            saveNotesInternal(bridge)
        }
    }

    fun emptyTrash(bridge: HostBridge) {
        synchronized(dbLock) {
            syncFromDiskIfNewerLocked(bridge)
            notes.removeAll { it.status == NoteStatus.TRASH }
            saveNotesInternal(bridge)
        }
    }

    fun applyCustomOrder(bridge: HostBridge, orderedIds: List<String>) {
        synchronized(dbLock) {
            syncFromDiskIfNewerLocked(bridge)
            val indexMap = orderedIds.withIndex().associate { it.value to it.index }
            var fallbackIdx = orderedIds.size
            for (i in notes.indices) {
                val n = notes[i]
                val newIdx = indexMap[n.id] ?: fallbackIdx++
                notes[i] = n.copy(sortIndex = newIdx)
            }
            val updatedSettings = settings.copy(sortOrder = NoteSortOrder.CUSTOM)
            settings = updatedSettings
            saveSettingsInternal(bridge, updatedSettings)
            saveNotesInternal(bridge)
        }
    }

    fun batchSetStatus(bridge: HostBridge, ids: Set<String>, status: NoteStatus) {
        synchronized(dbLock) {
            val now = System.currentTimeMillis()
            for (i in notes.indices) {
                val n = notes[i]
                if (ids.contains(n.id)) {
                    notes[i] = n.copy(
                        status = status,
                        isPinned = if (status == NoteStatus.TRASH) false else n.isPinned,
                        updatedAtMs = now
                    )
                }
            }
            saveNotesInternal(bridge)
        }
    }

    fun batchSetPinned(bridge: HostBridge, ids: Set<String>, pinned: Boolean) {
        synchronized(dbLock) {
            val now = System.currentTimeMillis()
            for (i in notes.indices) {
                val n = notes[i]
                if (ids.contains(n.id)) {
                    notes[i] = n.copy(isPinned = pinned, updatedAtMs = now)
                }
            }
            saveNotesInternal(bridge)
        }
    }

    fun batchSetColor(bridge: HostBridge, ids: Set<String>, colorId: String) {
        synchronized(dbLock) {
            val now = System.currentTimeMillis()
            for (i in notes.indices) {
                val n = notes[i]
                if (ids.contains(n.id)) {
                    notes[i] = n.copy(colorId = colorId, updatedAtMs = now)
                }
            }
            saveNotesInternal(bridge)
        }
    }

    fun batchSetLocked(bridge: HostBridge, ids: Set<String>, locked: Boolean) {
        synchronized(dbLock) {
            val now = System.currentTimeMillis()
            for (i in notes.indices) {
                val n = notes[i]
                if (ids.contains(n.id)) {
                    notes[i] = n.copy(
                        isLocked = locked,
                        pinHash = if (!locked) null else n.pinHash,
                        updatedAtMs = now
                    )
                }
            }
            saveNotesInternal(bridge)
        }
    }

    fun batchDeleteForever(bridge: HostBridge, ids: Set<String>) {
        synchronized(dbLock) {
            notes.removeAll { ids.contains(it.id) }
            saveNotesInternal(bridge)
        }
    }

    fun upsertFolder(bridge: HostBridge, folder: NoteFolder) {
        synchronized(dbLock) {
            val idx = folders.indexOfFirst { it.id == folder.id }
            if (idx >= 0) {
                folders[idx] = folder
            } else {
                folders.add(0, folder)
            }
            saveFoldersInternal(bridge)
        }
    }

    fun touchFolderUsed(bridge: HostBridge, folderId: String?) {
        if (folderId.isNullOrBlank()) return
        synchronized(dbLock) {
            val idx = folders.indexOfFirst { it.id == folderId }
            if (idx >= 0) {
                folders[idx] = folders[idx].copy(lastUsedAtMs = System.currentTimeMillis())
                saveFoldersInternal(bridge)
            }
        }
    }

    fun deleteFolder(bridge: HostBridge, folderId: String) {
        synchronized(dbLock) {
            folders.removeAll { it.id == folderId }
            var notesTouched = false
            for (i in notes.indices) {
                if (notes[i].folderId == folderId) {
                    notes[i] = notes[i].copy(folderId = null)
                    notesTouched = true
                }
            }
            saveFoldersInternal(bridge)
            if (notesTouched) saveNotesInternal(bridge)
        }
    }

    fun batchMoveToFolder(bridge: HostBridge, ids: Set<String>, folderId: String?) {
        synchronized(dbLock) {
            val now = System.currentTimeMillis()
            for (i in notes.indices) {
                val n = notes[i]
                if (ids.contains(n.id)) {
                    notes[i] = n.copy(folderId = folderId, updatedAtMs = now)
                }
            }
            if (!folderId.isNullOrBlank()) {
                val fIdx = folders.indexOfFirst { it.id == folderId }
                if (fIdx >= 0) {
                    folders[fIdx] = folders[fIdx].copy(lastUsedAtMs = now)
                    saveFoldersInternal(bridge)
                }
            }
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
                put("isLocked", n.isLocked)
                put("sortIndex", n.sortIndex)
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
            val isLocked = obj.optBoolean("isLocked", false) || !pinHash.isNullOrBlank()
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
                    isLocked = isLocked,
                    sortIndex = obj.optInt("sortIndex", i),
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
        isLoaded = true
        dataRevision++
        val snapshot = notes.toList()
        val targetFile = File(bridge.getPluginDir(), NOTES_FILE)
        ioExecutor.execute {
            writeNotesStreaming(targetFile, snapshot)
        }
    }

    private fun writeNotesStreaming(targetFile: File, items: List<NoteItem>) {
        try {
            targetFile.parentFile?.mkdirs()
            val tmpFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
            JsonWriter(BufferedWriter(OutputStreamWriter(BufferedOutputStream(FileOutputStream(tmpFile)), Charsets.UTF_8), 16384)).use { writer ->
                writer.beginArray()
                for (n in items) {
                    writer.beginObject()
                    writer.name("id").value(n.id)
                    writer.name("title").value(n.title)
                    writer.name("content").value(n.content)
                    writer.name("type").value(n.type.name)
                    writer.name("checklist")
                    writer.beginArray()
                    for (c in n.checklist) {
                        writer.beginObject()
                        writer.name("id").value(c.id)
                        writer.name("text").value(c.text)
                        writer.name("isChecked").value(c.isChecked)
                        writer.endObject()
                    }
                    writer.endArray()
                    writer.name("colorId").value(n.colorId)
                    writer.name("isPinned").value(n.isPinned)
                    writer.name("isLocked").value(n.isLocked)
                    writer.name("sortIndex").value(n.sortIndex.toLong())
                    if (!n.folderId.isNullOrBlank()) writer.name("folderId").value(n.folderId)
                    writer.name("status").value(n.status.name)
                    if (!n.pinHash.isNullOrBlank()) writer.name("pinHash").value(n.pinHash)
                    if (n.reminderAtMs != null) writer.name("reminderAtMs").value(n.reminderAtMs)
                    writer.name("reminderTriggered").value(n.reminderTriggered)
                    writer.name("createdAtMs").value(n.createdAtMs)
                    writer.name("updatedAtMs").value(n.updatedAtMs)
                    writer.endObject()
                }
                writer.endArray()
                writer.flush()
            }
            if (targetFile.exists()) targetFile.delete()
            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }
            if (targetFile.name == NOTES_FILE) {
                lastKnownDbModifiedMs = targetFile.lastModified()
            }
        } catch (_: Exception) {}
    }

    private fun readNotesStreaming(sourceFile: File): List<NoteItem> {
        val result = mutableListOf<NoteItem>()
        JsonReader(InputStreamReader(BufferedInputStream(FileInputStream(sourceFile), 16384), Charsets.UTF_8)).use { reader ->
            reader.beginArray()
            var idx = 0
            while (reader.hasNext()) {
                var id = ""
                var title = ""
                var content = ""
                var type = NoteType.TEXT
                val checklist = mutableListOf<ChecklistEntry>()
                var colorId = "yellow"
                var isPinned = false
                var isLockedFlag = false
                var sortIndex = idx
                var folderId: String? = null
                var status = NoteStatus.ACTIVE
                var pinHash: String? = null
                var reminderAtMs: Long? = null
                var reminderTriggered = false
                var createdAtMs = System.currentTimeMillis()
                var updatedAtMs = createdAtMs

                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (reader.peek() == JsonToken.NULL) {
                        reader.skipValue()
                        continue
                    }
                    when (name) {
                        "id" -> id = reader.nextString()
                        "title" -> title = reader.nextString()
                        "content" -> content = reader.nextString()
                        "type" -> type = runCatching { NoteType.valueOf(reader.nextString()) }.getOrDefault(NoteType.TEXT)
                        "checklist" -> {
                            reader.beginArray()
                            var cIdx = 0
                            while (reader.hasNext()) {
                                var cId = "chk_${idx}_$cIdx"
                                var cText = ""
                                var cChecked = false
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    val cName = reader.nextName()
                                    if (reader.peek() == JsonToken.NULL) {
                                        reader.skipValue()
                                        continue
                                    }
                                    when (cName) {
                                        "id" -> cId = reader.nextString()
                                        "text" -> cText = reader.nextString()
                                        "isChecked" -> cChecked = reader.nextBoolean()
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                                checklist.add(ChecklistEntry(cId, cText, cChecked))
                                cIdx++
                            }
                            reader.endArray()
                        }
                        "colorId" -> colorId = reader.nextString()
                        "isPinned" -> isPinned = reader.nextBoolean()
                        "isLocked" -> isLockedFlag = reader.nextBoolean()
                        "sortIndex" -> sortIndex = reader.nextInt()
                        "folderId" -> folderId = reader.nextString().takeIf { it.isNotBlank() && !it.equals("null", true) }
                        "status" -> status = runCatching { NoteStatus.valueOf(reader.nextString()) }.getOrDefault(NoteStatus.ACTIVE)
                        "pinHash" -> pinHash = reader.nextString().takeIf { it.isNotBlank() && !it.equals("null", true) }
                        "reminderAtMs" -> reminderAtMs = reader.nextLong()
                        "reminderTriggered" -> reminderTriggered = reader.nextBoolean()
                        "createdAtMs" -> createdAtMs = reader.nextLong()
                        "updatedAtMs" -> updatedAtMs = reader.nextLong()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()

                if (id.isNotBlank()) {
                    result.add(
                        NoteItem(
                            id = id,
                            title = title,
                            content = content,
                            type = type,
                            checklist = checklist,
                            colorId = colorId,
                            isPinned = isPinned,
                            isLocked = isLockedFlag || !pinHash.isNullOrBlank(),
                            sortIndex = sortIndex,
                            folderId = folderId,
                            status = status,
                            pinHash = pinHash,
                            reminderAtMs = reminderAtMs,
                            reminderTriggered = reminderTriggered,
                            createdAtMs = createdAtMs,
                            updatedAtMs = updatedAtMs
                        )
                    )
                }
                idx++
            }
            reader.endArray()
        }
        return result
    }

    private fun loadFoldersInternal(bridge: HostBridge) {
        try {
            val file = File(bridge.getPluginDir(), FOLDERS_FILE)
            if (!file.exists() || file.length() == 0L) return
            val parsed = readFoldersStreaming(file)
            lastKnownFoldersModifiedMs = file.lastModified()
            folders.clear()
            folders.addAll(parsed)
            dataRevision++
        } catch (_: Exception) {}
    }

    private fun saveFoldersInternal(bridge: HostBridge) {
        dataRevision++
        val snapshot = folders.toList()
        val targetFile = File(bridge.getPluginDir(), FOLDERS_FILE)
        ioExecutor.execute {
            writeFoldersStreaming(targetFile, snapshot)
        }
    }

    private fun writeFoldersStreaming(targetFile: File, items: List<NoteFolder>) {
        try {
            targetFile.parentFile?.mkdirs()
            val tmpFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
            JsonWriter(BufferedWriter(OutputStreamWriter(BufferedOutputStream(FileOutputStream(tmpFile)), Charsets.UTF_8), 4096)).use { writer ->
                writer.beginArray()
                for (f in items) {
                    writer.beginObject()
                    writer.name("id").value(f.id)
                    writer.name("name").value(f.name)
                    writer.name("colorId").value(f.colorId)
                    writer.name("icon").value(f.icon)
                    writer.name("lastUsedAtMs").value(f.lastUsedAtMs)
                    writer.name("createdAtMs").value(f.createdAtMs)
                    writer.endObject()
                }
                writer.endArray()
                writer.flush()
            }
            if (targetFile.exists()) targetFile.delete()
            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }
            if (targetFile.name == FOLDERS_FILE) {
                lastKnownFoldersModifiedMs = targetFile.lastModified()
            }
        } catch (_: Exception) {}
    }

    private fun readFoldersStreaming(sourceFile: File): List<NoteFolder> {
        val result = mutableListOf<NoteFolder>()
        JsonReader(InputStreamReader(BufferedInputStream(FileInputStream(sourceFile), 4096), Charsets.UTF_8)).use { reader ->
            reader.beginArray()
            while (reader.hasNext()) {
                var id = ""
                var name = ""
                var colorId = "blue"
                var icon = "📁"
                var lastUsedAtMs = System.currentTimeMillis()
                var createdAtMs = lastUsedAtMs
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    if (reader.peek() == JsonToken.NULL) {
                        reader.skipValue()
                        continue
                    }
                    when (key) {
                        "id" -> id = reader.nextString()
                        "name" -> name = reader.nextString()
                        "colorId" -> colorId = reader.nextString()
                        "icon" -> icon = reader.nextString()
                        "lastUsedAtMs" -> lastUsedAtMs = reader.nextLong()
                        "createdAtMs" -> createdAtMs = reader.nextLong()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                if (id.isNotBlank() && name.isNotBlank()) {
                    result.add(NoteFolder(id, name, colorId, icon, lastUsedAtMs, createdAtMs))
                }
            }
            reader.endArray()
        }
        return result
    }

    private fun getVaultBackupFile(): File {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dir = File(File(docsDir, ".omni_runtime"), "notes_backup")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, VAULT_BACKUP_NAME)
    }

    private fun getVaultFoldersBackupFile(): File {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dir = File(File(docsDir, ".omni_runtime"), "notes_backup")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, VAULT_FOLDERS_BACKUP_NAME)
    }

    fun exportVaultBackup(bridge: HostBridge): String? {
        return try {
            val snapshot = snapshotNotes()
            val folderSnap = snapshotFolders()
            val file = getVaultBackupFile()
            writeNotesStreaming(file, snapshot)
            writeFoldersStreaming(getVaultFoldersBackupFile(), folderSnap)
            bridge.log("OMNI_NOTES", "Exported ${snapshot.size} notes & ${folderSnap.size} folders to vault: ${file.absolutePath}")
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
            val imported = readNotesStreaming(file)
            val foldersFile = getVaultFoldersBackupFile()
            val importedFolders = if (foldersFile.exists() && foldersFile.length() > 0L) readFoldersStreaming(foldersFile) else emptyList()
            synchronized(dbLock) {
                if (importedFolders.isNotEmpty()) {
                    val existingFolders = folders.associateBy { it.id }.toMutableMap()
                    importedFolders.forEach { f ->
                        val cur = existingFolders[f.id]
                        if (cur == null || f.lastUsedAtMs > cur.lastUsedAtMs) existingFolders[f.id] = f
                    }
                    folders.clear()
                    folders.addAll(existingFolders.values)
                    saveFoldersInternal(bridge)
                }
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

    fun countEncryptedInColorNoteJson(rawJson: String): Int {
        return try {
            val root = JSONObject(rawJson)
            val arr = root.optJSONArray("notes") ?: return 0
            var count = 0
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val state = obj.optInt("active_state", 0)
                if (state != 256 && state != 32 && obj.optInt("encrypted", 0) != 0) {
                    count++
                }
            }
            count
        } catch (_: Exception) {
            0
        }
    }

    private fun decryptColorNoteText(base64Cipher: String, password: String): String? {
        if (password.isEmpty() || base64Cipher.isBlank()) return null
        val cipherBytes = try {
            Base64.decode(base64Cipher.trim(), Base64.DEFAULT)
        } catch (_: Exception) {
            return null
        }
        if (cipherBytes.isEmpty() || cipherBytes.size % 16 != 0) return null

        val pwBytes = password.toByteArray(Charsets.UTF_8)
        val salt = "ColorNote Fixed Salt".toByteArray(Charsets.UTF_8)
        val md5 = MessageDigest.getInstance("MD5")
        md5.update(pwBytes)
        md5.update(salt)
        val d0 = md5.digest()

        md5.reset()
        md5.update(d0)
        md5.update(pwBytes)
        md5.update(salt)
        val d1 = md5.digest()

        for ((keyBytes, ivBytes) in listOf(Pair(d0, d1), Pair(d0, ByteArray(16)))) {
            try {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
                val plain = String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
                if (!plain.contains('\uFFFD')) return plain
            } catch (_: Exception) {}
        }
        return null
    }

    private fun mapColorNoteColor(colorIndex: Int): String {
        return when (colorIndex) {
            1 -> "red"
            2 -> "orange"
            3 -> "yellow"
            4 -> "green"
            5 -> "blue"
            6 -> "purple"
            7, 8 -> "slate"
            9 -> "pearl"
            else -> "yellow"
        }
    }

    private fun parseColorNoteChecklist(noteId: String, rawBody: String): List<ChecklistEntry> {
        return rawBody.lines()
            .filter { it.isNotBlank() }
            .mapIndexed { idx, line ->
                val trimmed = line.trimStart()
                val checked = trimmed.startsWith("[V] ", ignoreCase = true) ||
                    trimmed.startsWith("[x] ", ignoreCase = true) ||
                    trimmed == "[V]" || trimmed.equals("[x]", ignoreCase = true)
                val cleanText = when {
                    trimmed.startsWith("[ ] ") -> trimmed.drop(4)
                    trimmed.startsWith("[V] ", ignoreCase = true) -> trimmed.drop(4)
                    trimmed.startsWith("[x] ", ignoreCase = true) -> trimmed.drop(4)
                    trimmed.startsWith("[] ") -> trimmed.drop(3)
                    else -> trimmed
                }
                ChecklistEntry(
                    id = "chk_${noteId}_$idx",
                    text = cleanText,
                    isChecked = checked
                )
            }
    }

    fun importColorNoteJson(
        bridge: HostBridge,
        rawJson: String,
        decryptPassword: String?
    ): ColorNoteImportResult {
        val root = JSONObject(rawJson)
        val arr = root.getJSONArray("notes")

        var skippedSystem = 0
        var decryptedOk = 0
        var lockedFailed = 0
        val deduped = LinkedHashMap<String, JSONObject>()

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val activeState = obj.optInt("active_state", 0)
            if (activeState == 256 || activeState == 32) {
                skippedSystem++
                continue
            }
            val uuid = obj.optString("uuid", "").ifBlank { "row_${obj.optLong("_id", i.toLong())}" }
            val existing = deduped[uuid]
            if (existing == null || obj.optLong("modified_date", 0L) >= existing.optLong("modified_date", 0L)) {
                deduped[uuid] = obj
            }
        }

        val parsedItems = mutableListOf<NoteItem>()
        for ((uuid, obj) in deduped) {
            val id = "cn_$uuid"
            val activeState = obj.optInt("active_state", 0)
            val space = obj.optInt("space", 0)
            val status = when {
                activeState == 16 -> NoteStatus.TRASH
                activeState == 0 && space == 16 -> NoteStatus.ARCHIVED
                else -> NoteStatus.ACTIVE
            }

            val isEncrypted = obj.optInt("encrypted", 0) != 0
            val rawNoteBody = obj.optString("note", "")
            val resolvedBody = if (isEncrypted) {
                val decoded = if (!decryptPassword.isNullOrEmpty()) decryptColorNoteText(rawNoteBody, decryptPassword) else null
                if (decoded != null) {
                    decryptedOk++
                    decoded
                } else {
                    lockedFailed++
                    continue
                }
            } else {
                rawNoteBody
            }

            val isChecklist = obj.optInt("type", 0) == 16
            val checklist = if (isChecklist) parseColorNoteChecklist(id, resolvedBody) else emptyList()
            val textContent = if (isChecklist) "" else resolvedBody
            val createdMs = obj.optLong("created_date", System.currentTimeMillis()).takeIf { it > 0L } ?: System.currentTimeMillis()
            val modifiedMs = obj.optLong("modified_date", createdMs).takeIf { it > 0L } ?: createdMs

            parsedItems.add(
                NoteItem(
                    id = id,
                    title = obj.optString("title", ""),
                    content = textContent,
                    type = if (isChecklist) NoteType.CHECKLIST else NoteType.TEXT,
                    checklist = checklist,
                    colorId = mapColorNoteColor(obj.optInt("color_index", 3)),
                    isPinned = obj.optInt("importance", 0) > 0,
                    isLocked = isEncrypted,
                    status = status,
                    createdAtMs = createdMs,
                    updatedAtMs = modifiedMs
                )
            )
        }

        synchronized(dbLock) {
            val existingById = notes.associateBy { it.id }.toMutableMap()
            var importedCount = 0
            parsedItems.forEach { item ->
                val current = existingById[item.id]
                if (current == null || item.updatedAtMs >= current.updatedAtMs) {
                    existingById[item.id] = if (current != null) item.copy(sortIndex = current.sortIndex) else item
                    importedCount++
                }
            }
            if (decryptedOk > 0 && settings.masterPinHash.isNullOrBlank() && !decryptPassword.isNullOrEmpty()) {
                val updatedSettings = settings.copy(masterPinHash = NoteSecurity.hashPin(decryptPassword))
                settings = updatedSettings
                saveSettingsInternal(bridge, updatedSettings)
            }
            notes.clear()
            notes.addAll(existingById.values.sortedByDescending { it.updatedAtMs })
            saveNotesInternal(bridge)
            return ColorNoteImportResult(
                importedCount = importedCount,
                skippedSystemCount = skippedSystem,
                decryptedCount = decryptedOk,
                lockedSkippedOrFailedCount = lockedFailed
            )
        }
    }
}