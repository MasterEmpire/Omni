package com.omni.plugin.browser.ui.dialogs

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.hub.api.HostBridge
import com.omni.plugin.browser.models.SmartNote
import com.omni.plugin.browser.models.SmartNoteFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val COLOR_ACCENTS = listOf(
    "red" to 0xFFF85149L,
    "orange" to 0xFFFB8C00L,
    "yellow" to 0xFFE5A93CL,
    "green" to 0xFF3FB950L,
    "blue" to 0xFF58A6FFL,
    "purple" to 0xFFBC8CFFL,
    "pink" to 0xFFF778BAL,
    "slate" to 0xFF8B949EL,
    "pearl" to 0xFFCBD5E1L
)

private fun accentColorFor(colorId: String): Color {
    val hex = COLOR_ACCENTS.find { it.first.equals(colorId, ignoreCase = true) }?.second ?: 0xFFE5A93CL
    return Color(hex)
}

private fun formatNoteTextForAction(note: SmartNote): String {
    return if (note.type == "CHECKLIST") {
        note.checklist.joinToString("\n") {
            "${if (it.isChecked) "[x]" else "[ ]"} ${it.text}"
        }
    } else {
        note.content
    }
}

private object SmartNoteDates {
    private val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)
    fun format(ms: Long): String = synchronized(fmt) { fmt.format(Date(ms)) }
}

@Composable
fun SmartNotesDialog(
    bridge: HostBridge,
    notes: List<SmartNote>,
    folders: List<SmartNoteFolder> = emptyList(),
    unlockedNoteIds: List<String> = emptyList(),
    currentPageUrl: String = "about:blank",
    currentPageTitle: String = "",
    onUnlockNote: (note: SmartNote, pin: String) -> Boolean = { _, _ -> true },
    onSaveNote: (title: String, content: String, existingId: String?, colorId: String, folderId: String?) -> Unit,
    onDeleteNote: (id: String) -> Unit,
    onInjectToPage: (text: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isEditing by remember { mutableStateOf(false) }
    var editingNoteId by remember { mutableStateOf<String?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editContent by remember { mutableStateOf("") }
    var editColorId by remember { mutableStateOf("yellow") }
    var editFolderId by remember { mutableStateOf<String?>(null) }

    var viewingNote by remember { mutableStateOf<SmartNote?>(null) }

    var searchQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    var selectedFolderFilter by remember { mutableStateOf<String?>(null) }

    var pendingUnlockNote by remember { mutableStateOf<SmartNote?>(null) }
    var pinInput by remember { mutableStateOf("") }
    var pinErr by remember { mutableStateOf<String?>(null) }

    val folderMap = remember(folders) {
        folders.associateBy { it.id }
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            debouncedQuery = ""
        } else {
            delay(120L)
            debouncedQuery = searchQuery.trim()
        }
    }

    var filteredNotes by remember { mutableStateOf(notes) }
    LaunchedEffect(notes, debouncedQuery, selectedFolderFilter, unlockedNoteIds.size) {
        filteredNotes = withContext(Dispatchers.Default) {
            val unlockedSnap = unlockedNoteIds.toSet()
            notes.filter { note ->
                if (selectedFolderFilter != null && note.folderId != selectedFolderFilter) {
                    return@filter false
                }
                if (debouncedQuery.isEmpty()) return@filter true
                val canSearchBody = !note.isLocked || unlockedSnap.contains(note.id)
                note.title.contains(debouncedQuery, ignoreCase = true) ||
                    (canSearchBody && note.content.contains(debouncedQuery, ignoreCase = true)) ||
                    (canSearchBody && note.checklist.any { it.text.contains(debouncedQuery, ignoreCase = true) })
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF282C34),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📝", fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            when {
                                isEditing -> if (editingNoteId != null) "Edit Note" else "New Note"
                                viewingNote != null -> "View Note"
                                else -> "Omni Brain Notes"
                            },
                            color = Color(0xFFE8EAED),
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        if (!isEditing && viewingNote == null) {
                            Text(
                                "${filteredNotes.size} synced notes",
                                color = Color(0xFF8B949E),
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                if (!isEditing && viewingNote == null) {
                    Button(
                        onClick = {
                            editingNoteId = null
                            editTitle = ""
                            editContent = ""
                            editColorId = "yellow"
                            editFolderId = selectedFolderFilter
                            isEditing = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        text = {
            if (viewingNote != null) {
                val note = viewingNote!!
                val isUnlocked = !note.isLocked || unlockedNoteIds.contains(note.id)
                val accent = accentColorFor(note.colorId)
                val folder = note.folderId?.let { folderMap[it] }
                val dateStr = SmartNoteDates.format(note.updatedAt)

                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(accent))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = note.title.ifBlank { if (note.type == "CHECKLIST") "Checklist" else "Untitled Note" },
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = if (folder != null) "${folder.icon} ${folder.name} • $dateStr" else dateStr,
                            color = Color(0xFF8B949E),
                            fontSize = 10.sp
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF16181D),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    ) {
                        Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    item {
                                        Text(
                                            text = formatNoteTextForAction(note).ifBlank { "(Empty note)" },
                                            color = Color(0xFFE8EAED),
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val actionPayload = formatNoteTextForAction(note)
                            Button(
                                onClick = { onInjectToPage(actionPayload) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("⚡ Inject", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    bridge.copyToClipboard(actionPayload)
                                    bridge.showToast("Copied to clipboard")
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF8AB4F8)),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("📋 Copy", fontSize = 11.sp)
                            }
                        }

                        Button(
                            onClick = {
                                editingNoteId = note.id
                                editTitle = note.title
                                editContent = formatNoteTextForAction(note)
                                editColorId = note.colorId
                                editFolderId = note.folderId
                                viewingNote = null
                                isEditing = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("✏️ Edit", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (isEditing) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
                ) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Title (Optional)") },
                        placeholder = { Text("Auto-derived from content if blank", color = Color(0xFF5F6368), fontSize = 11.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFE8EAED),
                            unfocusedTextColor = Color(0xFFE8EAED),
                            focusedBorderColor = accentColorFor(editColorId),
                            unfocusedBorderColor = Color(0xFF3C4043)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        label = { Text("Content") },
                        placeholder = { Text("Draft prompts, paste snippets, or clip current URL...", color = Color(0xFF5F6368), fontSize = 11.sp) },
                        minLines = 6,
                        maxLines = 12,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFE8EAED),
                            unfocusedTextColor = Color(0xFFE8EAED),
                            focusedBorderColor = accentColorFor(editColorId),
                            unfocusedBorderColor = Color(0xFF3C4043)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Quick Action Row: Paste + Clip Current URL
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                    if (clipText.isNotEmpty()) {
                                        editContent = if (editContent.isEmpty()) clipText else "$editContent\n$clipText"
                                        bridge.showToast("Pasted from clipboard")
                                    }
                                } catch (_: Exception) {}
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("📋 Paste", fontSize = 10.sp, color = Color(0xFF8AB4F8))
                        }

                        if (currentPageUrl.isNotBlank() && currentPageUrl != "about:blank") {
                            OutlinedButton(
                                onClick = {
                                    if (editTitle.isBlank() && currentPageTitle.isNotBlank()) {
                                        editTitle = currentPageTitle
                                    }
                                    editContent = if (editContent.isBlank()) currentPageUrl else "$editContent\n$currentPageUrl"
                                    bridge.showToast("🔗 Clipped current URL")
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFF3FB950).copy(alpha = 0.7f)),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("🔗 Clip Current URL", fontSize = 10.sp, color = Color(0xFF3FB950), fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Color Dots Row
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        COLOR_ACCENTS.forEach { (cId, cHex) ->
                            val isSel = editColorId == cId
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Color(cHex))
                                    .border(if (isSel) 2.dp else 0.dp, Color.White, CircleShape)
                                    .clickable { editColorId = cId }
                            )
                        }
                    }

                    // Optional Folder Chips Row
                    if (folders.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (editFolderId == null) Color(0xFF58A6FF).copy(alpha = 0.25f) else Color(0xFF1F2227),
                                border = BorderStroke(1.dp, if (editFolderId == null) Color(0xFF58A6FF) else Color(0xFF3C4043)),
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { editFolderId = null }
                            ) {
                                Text("Unfiled", color = Color(0xFFE8EAED), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                            folders.forEach { fld ->
                                val isSel = editFolderId == fld.id
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSel) accentColorFor(fld.colorId).copy(alpha = 0.25f) else Color(0xFF1F2227),
                                    border = BorderStroke(1.dp, if (isSel) accentColorFor(fld.colorId) else Color(0xFF3C4043)),
                                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { editFolderId = fld.id }
                                ) {
                                    Text("${fld.icon} ${fld.name}", color = Color(0xFFE8EAED), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                }
                            }
                        }
                    }
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search all Omni Notes...", color = Color(0xFF9AA0A6), fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF8AB4F8), modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color(0xFF9AA0A6), modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color.White,
                            fontSize = 13.sp,
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF1F2227),
                            unfocusedContainerColor = Color(0xFF1F2227),
                            focusedBorderColor = Color(0xFF8AB4F8),
                            unfocusedBorderColor = Color(0xFF3C4043),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (folders.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (selectedFolderFilter == null) Color(0xFFE5A93C) else Color(0xFF1F2227),
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { selectedFolderFilter = null }
                            ) {
                                Text(
                                    "All",
                                    color = if (selectedFolderFilter == null) Color.Black else Color(0xFFE8EAED),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                            folders.forEach { fld ->
                                val isSel = selectedFolderFilter == fld.id
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSel) accentColorFor(fld.colorId) else Color(0xFF1F2227),
                                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                                        selectedFolderFilter = if (isSel) null else fld.id
                                    }
                                ) {
                                    Text(
                                        "${fld.icon} ${fld.name}",
                                        color = if (isSel) Color.Black else Color(0xFFE8EAED),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (filteredNotes.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("📄", fontSize = 28.sp)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    if (searchQuery.isNotBlank()) "No notes match your search" else "No notes available",
                                    color = Color(0xFF9AA0A6),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(
                                items = filteredNotes,
                                key = { it.id }
                            ) { note ->
                                val isUnlocked = !note.isLocked || unlockedNoteIds.contains(note.id)
                                val accent = accentColorFor(note.colorId)
                                val dateStr = remember(note.updatedAt) { SmartNoteDates.format(note.updatedAt) }
                                val folder = note.folderId?.let { folderMap[it] }
                                val previewText = remember(note.id, note.content, note.type, note.checklist.size, isUnlocked) {
                                    when {
                                        !isUnlocked -> "🔒 Locked note — tap Unlock to view or inject"
                                        note.type == "CHECKLIST" -> {
                                            val done = note.checklist.count { it.isChecked }
                                            val itemsPreview = note.checklist.take(3).joinToString("\n") {
                                                "${if (it.isChecked) "☑" else "☐"} ${it.text}"
                                            }
                                            "($done/${note.checklist.size}) $itemsPreview"
                                        }
                                        else -> note.content.take(220)
                                    }
                                }

                                Card(
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2227)),
                                    border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            if (!isUnlocked) {
                                                pinInput = ""
                                                pinErr = null
                                                pendingUnlockNote = note
                                            } else {
                                                viewingNote = note
                                            }
                                        }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .drawBehind {
                                                drawRect(
                                                    color = accent,
                                                    topLeft = Offset.Zero,
                                                    size = Size(5.dp.toPx(), size.height)
                                                )
                                            }
                                            .padding(start = 13.dp, end = 10.dp, top = 10.dp, bottom = 8.dp)
                                    ) {
                                        Column {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    if (note.isPinned) {
                                                        Text("📌 ", fontSize = 11.sp)
                                                    }
                                                    if (!isUnlocked) {
                                                        Text("🔒 ", fontSize = 11.sp)
                                                    }
                                                    Text(
                                                        text = note.title.ifBlank { if (note.type == "CHECKLIST") "Checklist" else "Untitled" },
                                                        color = Color(0xFFE8EAED),
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = if (folder != null) "${folder.icon} ${folder.name} • $dateStr" else dateStr,
                                                    color = Color(0xFF8B949E),
                                                    fontSize = 10.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            Spacer(Modifier.height(4.dp))

                                            Text(
                                                text = previewText.ifBlank { "(Empty)" },
                                                color = Color(0xFF9AA0A6),
                                                fontSize = 11.sp,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis,
                                                lineHeight = 15.sp
                                            )

                                            Spacer(Modifier.height(6.dp))

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (!isUnlocked) {
                                                    TextButton(
                                                        onClick = {
                                                            pinInput = ""
                                                            pinErr = null
                                                            pendingUnlockNote = note
                                                        },
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                        modifier = Modifier.height(28.dp)
                                                    ) {
                                                        Text("🔓 Unlock", fontSize = 11.sp, color = Color(0xFFE5A93C), fontWeight = FontWeight.Bold)
                                                    }
                                                } else {
                                                    val actionPayload = formatNoteTextForAction(note)
                                                    TextButton(
                                                        onClick = { onInjectToPage(actionPayload) },
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                        modifier = Modifier.height(28.dp)
                                                    ) {
                                                        Text("⚡ Inject", fontSize = 11.sp, color = Color(0xFF81C995), fontWeight = FontWeight.Bold)
                                                    }

                                                    TextButton(
                                                        onClick = {
                                                            bridge.copyToClipboard(actionPayload)
                                                            bridge.showToast("Copied to clipboard")
                                                        },
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                        modifier = Modifier.height(28.dp)
                                                    ) {
                                                        Text("📋 Copy", fontSize = 11.sp, color = Color(0xFF8AB4F8))
                                                    }

                                                    IconButton(
                                                        onClick = {
                                                            if (!isUnlocked) {
                                                                pinInput = ""
                                                                pinErr = null
                                                                pendingUnlockNote = note
                                                            } else {
                                                                editingNoteId = note.id
                                                                editTitle = note.title
                                                                editContent = formatNoteTextForAction(note)
                                                                editColorId = note.colorId
                                                                editFolderId = note.folderId
                                                                isEditing = true
                                                            }
                                                        },
                                                        modifier = Modifier.size(28.dp)
                                                    ) {
                                                        Text("✏️", fontSize = 12.sp)
                                                    }
                                                }

                                                IconButton(
                                                    onClick = { onDeleteNote(note.id) },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(Icons.Default.Delete, contentDescription = "Move to Trash", tint = Color(0xFFF28B82), modifier = Modifier.size(15.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                isEditing -> {
                    Button(
                        onClick = {
                            onSaveNote(editTitle, editContent, editingNoteId, editColorId, editFolderId)
                            isEditing = false
                        },
                        enabled = editContent.trim().isNotEmpty() || editTitle.trim().isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636))
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }
                viewingNote != null -> {
                    Button(
                        onClick = { viewingNote = null },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                    ) {
                        Text("Back to Notes")
                    }
                }
                else -> {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3C4043))
                    ) {
                        Text("Close")
                    }
                }
            }
        },
        dismissButton = {
            if (isEditing) {
                TextButton(onClick = { isEditing = false }) {
                    Text("Cancel", color = Color(0xFF9AA0A6))
                }
            }
        }
    )

    pendingUnlockNote?.let { locked ->
        AlertDialog(
            onDismissRequest = { pendingUnlockNote = null },
            containerColor = Color(0xFF21262D),
            title = { Text("🔒 Unlock Note", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(locked.title.ifBlank { "Protected Note" }, color = Color(0xFFE5A93C), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { pinInput = it.filter { c -> c.isDigit() }.take(6); pinErr = null },
                        label = { Text("Enter Master PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    if (pinErr != null) {
                        Text(pinErr!!, color = Color(0xFFF85149), fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (onUnlockNote(locked, pinInput)) {
                            pendingUnlockNote = null
                        } else {
                            pinErr = "Incorrect PIN"
                            bridge.vibrate(100L)
                        }
                    }
                ) {
                    Text("Unlock")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnlockNote = null }) {
                    Text("Cancel", color = Color(0xFF8B949E))
                }
            }
        )
    }
}