package com.omni.plugin.notes.model

import java.security.MessageDigest

enum class NoteType {
    TEXT,
    CHECKLIST
}

enum class NoteStatus {
    ACTIVE,
    ARCHIVED,
    TRASH
}

enum class NoteViewMode(val label: String, val badge: String) {
    LIST("List", "☰"),
    GRID("Grid", "⊞"),
    DETAILS("Details", "▤")
}

enum class NoteSortOrder(val label: String) {
    CUSTOM("Custom Order"),
    MODIFIED_DESC("Modified Time"),
    CREATED_DESC("Created Time"),
    TITLE_ASC("Alphabetical"),
    COLOR("Color")
}

enum class NoteThemeMode(val label: String) {
    DARK("Dark"),
    LIGHT("Light")
}

enum class NoteSection(val label: String, val icon: String) {
    ALL("All Notes", "📝"),
    PINNED("Pinned", "📌"),
    REMINDERS("Reminders", "⏰"),
    ARCHIVE("Archive", "📦"),
    TRASH("Trash", "🗑️")
}

enum class NoteColor(
    val id: String,
    val label: String,
    val accentHex: Long,
    val darkBgHex: Long,
    val paperBgHex: Long
) {
    RED("red", "Coral", 0xFFF85149, 0xFF28181A, 0xFFFFCDD2),
    ORANGE("orange", "Sunset", 0xFFFB8C00, 0xFF291F14, 0xFFFFE0B2),
    YELLOW("yellow", "Amber", 0xFFE5A93C, 0xFF272213, 0xFFFFF9C4),
    GREEN("green", "Mint", 0xFF3FB950, 0xFF15251B, 0xFFC8E6C9),
    BLUE("blue", "Sky", 0xFF58A6FF, 0xFF15212E, 0xFFBBDEFB),
    PURPLE("purple", "Lavender", 0xFFBC8CFF, 0xFF21192E, 0xFFE1BEE7),
    PINK("pink", "Rose", 0xFFF778BA, 0xFF291724, 0xFFF8BBD0),
    SLATE("slate", "Graphite", 0xFF8B949E, 0xFF1C2128, 0xFFECEFF1),
    PEARL("pearl", "Pearl", 0xFFCBD5E1, 0xFF23272E, 0xFFFDFDFD);

    companion object {
        fun fromId(id: String?): NoteColor {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: YELLOW
        }
    }
}

data class ChecklistEntry(
    val id: String,
    val text: String,
    val isChecked: Boolean = false
)

data class NoteItem(
    val id: String,
    val title: String,
    val content: String = "",
    val type: NoteType = NoteType.TEXT,
    val checklist: List<ChecklistEntry> = emptyList(),
    val colorId: String = NoteColor.YELLOW.id,
    val isPinned: Boolean = false,
    val isLocked: Boolean = false,
    val sortIndex: Int = 0,
    val status: NoteStatus = NoteStatus.ACTIVE,
    val pinHash: String? = null,
    val reminderAtMs: Long? = null,
    val reminderTriggered: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    val color: NoteColor get() = NoteColor.fromId(colorId)
}

data class NotesSettings(
    val viewMode: NoteViewMode = NoteViewMode.GRID,
    val sortOrder: NoteSortOrder = NoteSortOrder.MODIFIED_DESC,
    val themeMode: NoteThemeMode = NoteThemeMode.DARK,
    val isPaperMode: Boolean = false,
    val masterPinHash: String? = null
)

object NoteSecurity {
    fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}