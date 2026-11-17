package com.omni.plugin.browser.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.omni.hub.api.HostBridge
import com.omni.plugin.browser.models.SmartNote
import com.omni.plugin.browser.models.SmartNoteChecklistItem
import com.omni.plugin.browser.models.SmartNoteFolder
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

data class UnifiedNotesSnapshot(
    val activeNotes: List<SmartNote>,
    val folders: List<SmartNoteFolder>,
    val masterPinHash: String?,
    val didRefresh: Boolean
)

/**
 * Shared streaming repository connecting Omni Browser's Smart Notes directly to
 * Omni Notes' database (plugins_data/omni_notes/notes_db.json) as one unified brain.
 */
class UnifiedNotesStore(
    private val context: Context,
    private val bridge: HostBridge
) {
    private val dbLock = Any()
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val omniNotesDataDir: File
        get() = File(context.getDir("plugins_data", Context.MODE_PRIVATE), "omni_notes")

    private val notesDbFile: File get() = File(omniNotesDataDir, "notes_db.json")
    private val foldersFile: File get() = File(omniNotesDataDir, "notes_folders.json")
    private val settingsFile: File get() = File(omniNotesDataDir, "notes_settings.json")

    @Volatile private var lastLoadedDbModifiedMs: Long = 0L
    @Volatile private var lastLoadedFoldersModifiedMs: Long = 0L
    @Volatile private var cachedAllNotes: MutableList<SmartNote> = mutableListOf()
    @Volatile private var cachedFolders: List<SmartNoteFolder> = emptyList()
    @Volatile private var cachedMasterPinHash: String? = null
    @Volatile private var migrationChecked: Boolean = false

    fun loadIfNeeded(force: Boolean = false): UnifiedNotesSnapshot {
        synchronized(dbLock) {
            val dbExists = notesDbFile.exists() && notesDbFile.length() > 0L
            val dbChanged = dbExists && notesDbFile.lastModified() > lastLoadedDbModifiedMs
            val fldExists = foldersFile.exists() && foldersFile.length() > 0L
            val fldChanged = fldExists && foldersFile.lastModified() > lastLoadedFoldersModifiedMs

            val shouldReadDb = force || dbChanged || (cachedAllNotes.isEmpty() && dbExists)
            val shouldReadFolders = force || fldChanged || (cachedFolders.isEmpty() && fldExists)

            if (shouldReadDb) {
                cachedAllNotes = readNotesStreaming(notesDbFile).toMutableList()
                lastLoadedDbModifiedMs = notesDbFile.lastModified()
            }

            if (!migrationChecked) {
                migrationChecked = true
                if (migrateLegacySmartNotesLocked()) {
                    writeNotesStreaming(notesDbFile, cachedAllNotes)
                    lastLoadedDbModifiedMs = notesDbFile.lastModified()
                }
            }

            if (shouldReadFolders) {
                cachedFolders = readFoldersStreaming(foldersFile)
                lastLoadedFoldersModifiedMs = foldersFile.lastModified()
            }

            cachedMasterPinHash = readMasterPinHash()

            val active = cachedAllNotes
                .filter { it.status == "ACTIVE" }
                .sortedWith(compareByDescending<SmartNote> { it.isPinned }.thenByDescending { it.updatedAt })

            return UnifiedNotesSnapshot(
                activeNotes = active,
                folders = cachedFolders.sortedBy { it.name.lowercase() },
                masterPinHash = cachedMasterPinHash,
                didRefresh = shouldReadDb || shouldReadFolders || force
            )
        }
    }

    fun upsertNote(
        title: String,
        content: String,
        existingId: String?,
        colorId: String,
        folderId: String?
    ): List<SmartNote> {
        synchronized(dbLock) {
            syncLatestBeforeWriteLocked()
            val now = System.currentTimeMillis()
            val idx = if (existingId != null) cachedAllNotes.indexOfFirst { it.id == existingId } else -1

            if (idx >= 0) {
                val existing = cachedAllNotes[idx]
                val isChecklist = existing.type == "CHECKLIST"
                val parsedChecklist = if (isChecklist) parseChecklistLines(existing.id, content) else existing.checklist
                cachedAllNotes[idx] = existing.copy(
                    title = title,
                    content = if (isChecklist) "" else content,
                    checklist = parsedChecklist,
                    colorId = colorId,
                    folderId = folderId,
                    updatedAt = now
                )
            } else {
                val newNote = SmartNote(
                    id = "note_$now",
                    title = title,
                    content = content,
                    type = "TEXT",
                    colorId = colorId,
                    folderId = folderId,
                    status = "ACTIVE",
                    createdAt = now,
                    updatedAt = now
                )
                cachedAllNotes.add(0, newNote)
            }

            val snapshotToWrite = cachedAllNotes.toList()
            val target = notesDbFile
            ioExecutor.execute {
                writeNotesStreaming(target, snapshotToWrite)
                lastLoadedDbModifiedMs = target.lastModified()
                mirrorNotesToExternalVault(snapshotToWrite)
            }

            return cachedAllNotes
                .filter { it.status == "ACTIVE" }
                .sortedWith(compareByDescending<SmartNote> { it.isPinned }.thenByDescending { it.updatedAt })
        }
    }

    fun moveToTrash(noteId: String): List<SmartNote> {
        synchronized(dbLock) {
            syncLatestBeforeWriteLocked()
            val idx = cachedAllNotes.indexOfFirst { it.id == noteId }
            if (idx >= 0) {
                cachedAllNotes[idx] = cachedAllNotes[idx].copy(
                    status = "TRASH",
                    isPinned = false,
                    updatedAt = System.currentTimeMillis()
                )
                val snapshotToWrite = cachedAllNotes.toList()
                val target = notesDbFile
                ioExecutor.execute {
                    writeNotesStreaming(target, snapshotToWrite)
                    lastLoadedDbModifiedMs = target.lastModified()
                    mirrorNotesToExternalVault(snapshotToWrite)
                }
            }
            return cachedAllNotes
                .filter { it.status == "ACTIVE" }
                .sortedWith(compareByDescending<SmartNote> { it.isPinned }.thenByDescending { it.updatedAt })
        }
    }

    fun verifyPin(pin: String, note: SmartNote): Boolean {
        val expected = cachedMasterPinHash ?: readMasterPinHash() ?: note.pinHash ?: return true
        return hashPin(pin) == expected
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun syncLatestBeforeWriteLocked() {
        if (notesDbFile.exists() && notesDbFile.lastModified() > lastLoadedDbModifiedMs) {
            cachedAllNotes = readNotesStreaming(notesDbFile).toMutableList()
            lastLoadedDbModifiedMs = notesDbFile.lastModified()
        }
    }

    private fun parseChecklistLines(noteId: String, raw: String): List<SmartNoteChecklistItem> {
        return raw.lines()
            .filter { it.isNotBlank() }
            .mapIndexed { idx, line ->
                val trimmed = line.trimStart()
                val checked = trimmed.startsWith("[x] ", ignoreCase = true) ||
                    trimmed.startsWith("[v] ", ignoreCase = true) ||
                    trimmed.startsWith("☑ ")
                val cleanText = when {
                    trimmed.startsWith("[ ] ") || trimmed.startsWith("[x] ", true) || trimmed.startsWith("[v] ", true) -> trimmed.drop(4)
                    trimmed.startsWith("☑ ") || trimmed.startsWith("☐ ") -> trimmed.drop(2)
                    else -> trimmed
                }
                SmartNoteChecklistItem(
                    id = "chk_${noteId}_$idx",
                    text = cleanText,
                    isChecked = checked
                )
            }
    }

    private fun migrateLegacySmartNotesLocked(): Boolean {
        val flagFile = File(bridge.getPluginDir(), "config/smart_notes_migrated.flag")
        if (flagFile.exists()) return false

        var migratedAny = false
        try {
            val legacyBytes = bridge.readFile("config/smart_notes.json")
            if (legacyBytes != null && legacyBytes.isNotEmpty()) {
                val arr = JSONArray(String(legacyBytes, Charsets.UTF_8))
                val existingIds = cachedAllNotes.map { it.id }.toMutableSet()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val rawId = obj.optString("id", "").ifBlank { "sn_${System.currentTimeMillis()}_$i" }
                    val targetId = if (existingIds.contains(rawId)) rawId else "sn_$rawId"
                    if (!existingIds.contains(targetId) && !existingIds.contains(rawId)) {
                        cachedAllNotes.add(
                            0,
                            SmartNote(
                                id = targetId,
                                title = obj.optString("title", ""),
                                content = obj.optString("content", ""),
                                type = "TEXT",
                                colorId = "blue",
                                status = "ACTIVE",
                                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                                updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                            )
                        )
                        existingIds.add(targetId)
                        migratedAny = true
                    }
                }
            }
            flagFile.parentFile?.mkdirs()
            flagFile.writeText("1", Charsets.UTF_8)
        } catch (_: Exception) {}
        return migratedAny
    }

    private fun readMasterPinHash(): String? {
        return try {
            if (!settingsFile.exists() || settingsFile.length() == 0L) return null
            val obj = JSONObject(settingsFile.readText(Charsets.UTF_8))
            obj.optString("masterPinHash", "").takeIf { it.isNotBlank() && !it.equals("null", true) }
        } catch (_: Exception) {
            null
        }
    }

    private fun readFoldersStreaming(file: File): List<SmartNoteFolder> {
        if (!file.exists() || file.length() == 0L) return emptyList()
        val result = mutableListOf<SmartNoteFolder>()
        try {
            JsonReader(InputStreamReader(BufferedInputStream(FileInputStream(file), 4096), Charsets.UTF_8)).use { reader ->
                reader.beginArray()
                while (reader.hasNext()) {
                    var id = ""
                    var name = ""
                    var colorId = "blue"
                    var icon = "📁"
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
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    if (id.isNotBlank() && name.isNotBlank()) {
                        result.add(SmartNoteFolder(id, name, colorId, icon))
                    }
                }
                reader.endArray()
            }
        } catch (_: Exception) {}
        return result
    }

    private fun readNotesStreaming(file: File): List<SmartNote> {
        if (!file.exists() || file.length() == 0L) return emptyList()
        val result = mutableListOf<SmartNote>()
        try {
            JsonReader(InputStreamReader(BufferedInputStream(FileInputStream(file), 16384), Charsets.UTF_8)).use { reader ->
                reader.beginArray()
                var idx = 0
                while (reader.hasNext()) {
                    var id = ""
                    var title = ""
                    var content = ""
                    var marginContent = ""
                    var type = "TEXT"
                    val checklist = mutableListOf<SmartNoteChecklistItem>()
                    var colorId = "yellow"
                    var isPinned = false
                    var isLockedFlag = false
                    var sortIndex = idx
                    var folderId: String? = null
                    var status = "ACTIVE"
                    var pinHash: String? = null
                    var reminderAtMs: Long? = null
                    var reminderTriggered = false
                    var createdAt = System.currentTimeMillis()
                    var updatedAt = createdAt

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
                            "marginContent" -> marginContent = reader.nextString()
                            "type" -> type = reader.nextString()
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
                                    checklist.add(SmartNoteChecklistItem(cId, cText, cChecked))
                                    cIdx++
                                }
                                reader.endArray()
                            }
                            "colorId" -> colorId = reader.nextString()
                            "isPinned" -> isPinned = reader.nextBoolean()
                            "isLocked" -> isLockedFlag = reader.nextBoolean()
                            "sortIndex" -> sortIndex = reader.nextInt()
                            "folderId" -> folderId = reader.nextString().takeIf { it.isNotBlank() && !it.equals("null", true) }
                            "status" -> status = reader.nextString()
                            "pinHash" -> pinHash = reader.nextString().takeIf { it.isNotBlank() && !it.equals("null", true) }
                            "reminderAtMs" -> reminderAtMs = reader.nextLong()
                            "reminderTriggered" -> reminderTriggered = reader.nextBoolean()
                            "createdAtMs" -> createdAt = reader.nextLong()
                            "updatedAtMs" -> updatedAt = reader.nextLong()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()

                    if (id.isNotBlank()) {
                        result.add(
                            SmartNote(
                                id = id,
                                title = title,
                                content = content,
                                marginContent = marginContent,
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
                                createdAt = createdAt,
                                updatedAt = updatedAt
                            )
                        )
                    }
                    idx++
                }
                reader.endArray()
            }
        } catch (e: Exception) {
            bridge.log("UNIFIED_NOTES_ERR", "Streaming read failed: ${e.message}")
        }
        return result
    }

    private fun writeNotesStreaming(targetFile: File, items: List<SmartNote>) {
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
                    if (n.marginContent.isNotEmpty()) writer.name("marginContent").value(n.marginContent)
                    writer.name("type").value(n.type)
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
                    writer.name("status").value(n.status)
                    if (!n.pinHash.isNullOrBlank()) writer.name("pinHash").value(n.pinHash)
                    if (n.reminderAtMs != null) writer.name("reminderAtMs").value(n.reminderAtMs)
                    writer.name("reminderTriggered").value(n.reminderTriggered)
                    writer.name("createdAtMs").value(n.createdAt)
                    writer.name("updatedAtMs").value(n.updatedAt)
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
        } catch (e: Exception) {
            bridge.log("UNIFIED_NOTES_ERR", "Streaming write failed: ${e.message}")
        }
    }

    private fun mirrorNotesToExternalVault(items: List<SmartNote>) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) return
            val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val dir = File(File(docsDir, ".omni_runtime"), "notes_backup")
            if (!dir.exists()) dir.mkdirs()
            val backupFile = File(dir, "omni_notes_backup.json")
            if (backupFile.exists() && backupFile.length() > 0L) {
                backupFile.copyTo(File(dir, "omni_notes_backup.prev.json"), overwrite = true)
            }
            writeNotesStreaming(backupFile, items)
        } catch (_: Exception) {}
    }
}