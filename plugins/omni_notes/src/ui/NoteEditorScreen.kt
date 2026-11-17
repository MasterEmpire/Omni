package com.omni.plugin.notes.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.hub.api.HostBridge
import com.omni.plugin.notes.OmniNotesPlugin
import com.omni.plugin.notes.model.ChecklistEntry
import com.omni.plugin.notes.model.NoteColor
import com.omni.plugin.notes.model.NoteItem
import com.omni.plugin.notes.model.NoteSecurity
import com.omni.plugin.notes.model.NoteStatus
import com.omni.plugin.notes.model.NoteThemeMode
import com.omni.plugin.notes.model.NoteType
import com.omni.plugin.notes.storage.NoteRepository
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    context: Context,
    bridge: HostBridge,
    existingNote: NoteItem?,
    initialType: NoteType,
    initialColorId: String,
    initialFolderId: String? = null,
    registerBackInterceptor: ((() -> Boolean)?) -> Unit,
    onExitEditor: () -> Unit
) {
    val isDark = NoteRepository.settings.themeMode == NoteThemeMode.DARK
    val isPaperMode = NoteRepository.settings.isPaperMode
    val noteId = remember(existingNote?.id) { existingNote?.id ?: "note_${System.currentTimeMillis()}" }
    val noteType = existingNote?.type ?: initialType
    val createdAt = existingNote?.createdAtMs ?: System.currentTimeMillis()

    var title by remember(noteId) { mutableStateOf(existingNote?.title ?: "") }
    var content by remember(noteId) { mutableStateOf(existingNote?.content ?: "") }
    var checklist by remember(noteId) { mutableStateOf(existingNote?.checklist ?: emptyList()) }
    var colorId by remember(noteId) { mutableStateOf(existingNote?.colorId ?: initialColorId) }
    var folderId by remember(noteId) { mutableStateOf(existingNote?.folderId ?: initialFolderId) }
    var isPinned by remember(noteId) { mutableStateOf(existingNote?.isPinned ?: false) }
    var isLocked by remember(noteId) { mutableStateOf(existingNote?.isLocked ?: false) }
    var status by remember(noteId) { mutableStateOf(existingNote?.status ?: NoteStatus.ACTIVE) }
    var reminderAtMs by remember(noteId) { mutableStateOf(existingNote?.reminderAtMs) }
    var reminderTriggered by remember(noteId) { mutableStateOf(existingNote?.reminderTriggered ?: false) }
    var isDirty by remember(noteId) { mutableStateOf(false) }

    // Text notes open existing content in Read mode; new notes open directly in Edit mode
    var isEditMode by remember(noteId) { mutableStateOf(existingNote == null) }

    // Undo / Redo Stacks for Text notes
    val undoStack = remember(noteId) { mutableStateListOf<String>() }
    val redoStack = remember(noteId) { mutableStateListOf<String>() }

    var newItemText by remember { mutableStateOf("") }
    var editingCheckItem by remember { mutableStateOf<ChecklistEntry?>(null) }
    var showColorDialog by remember { mutableStateOf(false) }
    var showFolderPickerDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showReminderDialog by remember { mutableStateOf(false) }

    val nc = NoteColor.fromId(colorId)
    val accent = Color(nc.accentHex)
    val screenBg = Color(if (isDark) nc.darkBgHex else nc.paperBgHex)
    val topBarBg = if (isDark) Color(0xFF161B22) else Color.White.copy(alpha = 0.92f)
    val primaryText = if (isDark) Color(0xFFF0F6FC) else Color(0xFF1F2328)
    val mutedText = if (isDark) Color(0xFF8B949E) else Color(0xFF4B5563)

    fun buildNoteSnapshot(): NoteItem {
        return NoteItem(
            id = noteId,
            title = title.trim(),
            content = content,
            type = noteType,
            checklist = checklist,
            colorId = colorId,
            isPinned = isPinned,
            isLocked = isLocked,
            sortIndex = existingNote?.sortIndex ?: 0,
            folderId = folderId,
            status = status,
            reminderAtMs = reminderAtMs,
            reminderTriggered = reminderTriggered,
            createdAtMs = createdAt,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    fun persistIfNonEmpty(): Boolean {
        val hasData = title.isNotBlank() ||
            content.isNotBlank() ||
            checklist.isNotEmpty() ||
            existingNote != null
        if (hasData) {
            val saved = buildNoteSnapshot()
            NoteRepository.upsertNote(bridge, saved)
            if (OmniNotesPlugin.isStickyMounted(saved.id)) {
                OmniNotesPlugin.mountStickyOverlay(context, bridge, saved, isReminderAlert = false)
            }
            return true
        }
        return false
    }

    fun saveAndExit() {
        if (isDirty && persistIfNonEmpty()) {
            isDirty = false
            bridge.showToast("Note saved")
        }
        onExitEditor()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && isDirty) {
                if (persistIfNonEmpty()) {
                    isDirty = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(Unit) {
        registerBackInterceptor {
            saveAndExit()
            true
        }
        onDispose { }
    }

    Scaffold(
        containerColor = screenBg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = topBarBg),
                navigationIcon = {
                    IconButton(onClick = { saveAndExit() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Save & Back", tint = primaryText)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (noteType == NoteType.CHECKLIST) "☑️ Checklist" else "📄 Note",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryText
                        )
                        if (isPinned) {
                            Spacer(Modifier.width(6.dp))
                            Text("📌", fontSize = 13.sp)
                        }
                        if (isLocked) {
                            Spacer(Modifier.width(4.dp))
                            Text("🔒", fontSize = 13.sp)
                        }
                    }
                },
                actions = {
                    // Undo / Redo for Text Edit Mode
                    if (noteType == NoteType.TEXT && isEditMode) {
                        TextButton(
                            onClick = {
                                if (undoStack.isNotEmpty()) {
                                    redoStack.add(content)
                                    content = undoStack.removeAt(undoStack.lastIndex)
                                }
                            },
                            enabled = undoStack.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Text("↩", fontSize = 17.sp, color = if (undoStack.isNotEmpty()) primaryText else mutedText.copy(alpha = 0.4f))
                        }
                        TextButton(
                            onClick = {
                                if (redoStack.isNotEmpty()) {
                                    undoStack.add(content)
                                    content = redoStack.removeAt(redoStack.lastIndex)
                                }
                            },
                            enabled = redoStack.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Text("↪", fontSize = 17.sp, color = if (redoStack.isNotEmpty()) primaryText else mutedText.copy(alpha = 0.4f))
                        }
                    }

                    // Color Dot Picker
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(accent)
                            .border(1.5.dp, primaryText, CircleShape)
                            .clickable { showColorDialog = true }
                    )

                    // Toggle Read/Edit on Text Notes
                    if (noteType == NoteType.TEXT) {
                        IconButton(onClick = {
                            if (isEditMode) persistIfNonEmpty()
                            isEditMode = !isEditMode
                        }) {
                            Icon(
                                if (isEditMode) Icons.Default.Check else Icons.Default.Edit,
                                contentDescription = if (isEditMode) "Done Editing" else "Edit Note",
                                tint = if (isEditMode) Color(0xFF3FB950) else primaryText
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            persistIfNonEmpty()
                            bridge.showToast("Saved")
                        }) {
                            Icon(Icons.Default.Check, contentDescription = "Save", tint = Color(0xFF3FB950))
                        }
                    }

                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = primaryText)
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            modifier = Modifier.background(if (isDark) Color(0xFF161B22) else Color.White)
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (isPinned) "📌 Unpin from Top" else "📌 Pin to Top", color = primaryText, fontSize = 13.sp) },
                                onClick = {
                                    showMoreMenu = false
                                    isPinned = !isPinned
                                    persistIfNonEmpty()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    val mounted = OmniNotesPlugin.isStickyMounted(noteId)
                                    Text(
                                        if (mounted) "🪟 Unpin Floating Note" else "🪟 Pin as Floating Sticky",
                                        color = primaryText,
                                        fontSize = 13.sp
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    persistIfNonEmpty()
                                    OmniNotesPlugin.toggleStickyOverlay(context, bridge, buildNoteSnapshot())
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("📁 Folder", color = primaryText, fontSize = 13.sp) },
                                onClick = {
                                    showMoreMenu = false
                                    showFolderPickerDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("⏰ Reminder", color = primaryText, fontSize = 13.sp) },
                                onClick = {
                                    showMoreMenu = false
                                    showReminderDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isLocked) "🔓 Unlock Note" else "🔒 Lock Note",
                                        color = primaryText,
                                        fontSize = 13.sp
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    if (isLocked) {
                                        isLocked = false
                                        isDirty = true
                                        persistIfNonEmpty()
                                        bridge.showToast("Note unlocked")
                                    } else if (!NoteRepository.settings.masterPinHash.isNullOrBlank()) {
                                        isLocked = true
                                        isDirty = true
                                        OmniNotesPlugin.dismissStickyOverlay(bridge, noteId)
                                        persistIfNonEmpty()
                                        bridge.showToast("🔒 Locked with Master PIN")
                                    } else {
                                        showPinDialog = true
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("📋 Copy Note Text", color = primaryText, fontSize = 13.sp) },
                                onClick = {
                                    showMoreMenu = false
                                    val formatted = if (noteType == NoteType.CHECKLIST) {
                                        buildString {
                                            if (title.isNotBlank()) appendLine(title)
                                            checklist.forEach { appendLine("${if (it.isChecked) "[x]" else "[ ]"} ${it.text}") }
                                        }
                                    } else {
                                        if (title.isNotBlank()) "$title\n\n$content" else content
                                    }
                                    bridge.copyToClipboard(formatted.trim())
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (status == NoteStatus.ARCHIVED) "📤 Unarchive" else "📦 Archive",
                                        color = primaryText,
                                        fontSize = 13.sp
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    status = if (status == NoteStatus.ARCHIVED) NoteStatus.ACTIVE else NoteStatus.ARCHIVED
                                    saveAndExit()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("🗑️ Move to Trash", color = Color(0xFFF85149), fontSize = 13.sp) },
                                onClick = {
                                    showMoreMenu = false
                                    status = NoteStatus.TRASH
                                    isPinned = false
                                    OmniNotesPlugin.dismissStickyOverlay(bridge, noteId)
                                    saveAndExit()
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(screenBg)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(accent)
            )

            // Title Input
            BasicTextField(
                value = title,
                onValueChange = {
                    title = it
                    isDirty = true
                },
                enabled = noteType == NoteType.CHECKLIST || isEditMode,
                textStyle = TextStyle(
                    color = primaryText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                ),
                cursorBrush = SolidColor(accent),
                singleLine = true,
                decorationBox = { inner ->
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        if (title.isEmpty()) {
                            Text("Title", color = mutedText.copy(alpha = 0.6f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            // Active Folder Pill
            val currentFolder = folderId?.let { fId -> NoteRepository.folders.find { it.id == fId } }
            if (currentFolder != null) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.copy(alpha = 0.14f))
                        .clickable { showFolderPickerDialog = true }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${currentFolder.icon} ${currentFolder.name}", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Active Reminder Pill
            if (reminderAtMs != null) {
                val label = remember(reminderAtMs, reminderTriggered) {
                    val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(reminderAtMs!!))
                    if (reminderTriggered) "⏰ Reminder fired ($fmt)" else "⏰ Reminds $fmt"
                }
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.copy(alpha = 0.16f))
                        .clickable { showReminderDialog = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(color = accent.copy(alpha = 0.25f))

            if (noteType == NoteType.TEXT) {
                val textScrollState = rememberScrollState()
                val density = LocalDensity.current
                val noteLineHeight = if (isPaperMode) 26.sp else 22.sp
                val lineStepPx = with(density) { noteLineHeight.toPx() }
                val topPadPx = with(density) { 16.dp.toPx() }
                val marginXPx = with(density) { 38.dp.toPx() }
                val ruleColor = if (isDark) Color.White.copy(alpha = 0.09f) else Color(0xFF60A5FA).copy(alpha = 0.35f)
                val marginColor = Color(0xFFF85149).copy(alpha = if (isDark) 0.34f else 0.5f)
                val paperModifier = if (isPaperMode) {
                    Modifier.drawBehind {
                        drawLine(
                            color = marginColor,
                            start = Offset(marginXPx, 0f),
                            end = Offset(marginXPx, size.height),
                            strokeWidth = 2f
                        )
                        val offset = textScrollState.value % lineStepPx
                        var y = topPadPx + lineStepPx - offset
                        while (y < size.height) {
                            if (y >= 0f) {
                                drawLine(
                                    color = ruleColor,
                                    start = Offset(0f, y),
                                    end = Offset(size.width, y),
                                    strokeWidth = 1.5f
                                )
                            }
                            y += lineStepPx
                        }
                    }
                } else {
                    Modifier
                }
                val startPadding = if (isPaperMode) 48.dp else 16.dp

                if (isEditMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .then(paperModifier)
                            .verticalScroll(textScrollState)
                    ) {
                        BasicTextField(
                            value = content,
                            onValueChange = { next ->
                                if (next != content) {
                                    if (undoStack.size > 40) undoStack.removeAt(0)
                                    undoStack.add(content)
                                    redoStack.clear()
                                    content = next
                                    isDirty = true
                                }
                            },
                            textStyle = TextStyle(
                                color = primaryText,
                                fontSize = 15.sp,
                                lineHeight = noteLineHeight
                            ),
                            cursorBrush = SolidColor(accent),
                            decorationBox = { inner ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 420.dp)
                                        .padding(start = startPadding, end = 16.dp, top = 16.dp, bottom = 32.dp)
                                ) {
                                    if (content.isEmpty()) {
                                        Text("Start typing your note...", color = mutedText.copy(alpha = 0.6f), fontSize = 15.sp)
                                    }
                                    inner()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .then(paperModifier)
                            .verticalScroll(textScrollState)
                            .clickable { isEditMode = true }
                            .padding(start = startPadding, end = 16.dp, top = 16.dp, bottom = 32.dp)
                    ) {
                        Text(
                            text = content.ifBlank { "Tap anywhere to start writing..." },
                            color = if (content.isBlank()) mutedText else primaryText,
                            fontSize = 15.sp,
                            lineHeight = noteLineHeight
                        )
                    }
                }

                // Footer Metadata Bar
                val wordCount = remember(content) {
                    content.trim().split(Regex("\\s+")).count { it.isNotBlank() }
                }
                val dateFmt = remember(existingNote?.updatedAtMs) {
                    SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(existingNote?.updatedAtMs ?: createdAt))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(topBarBg.copy(alpha = 0.55f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("$wordCount words • ${content.length} chars", color = mutedText, fontSize = 11.sp)
                    Text(if (isEditMode) "Editing" else "Read mode • $dateFmt", color = mutedText, fontSize = 11.sp)
                }
            } else {
                // Checklist Mode with Drag-to-Reorder
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newItemText,
                        onValueChange = { newItemText = it },
                        placeholder = { Text("Add checklist item...", fontSize = 13.sp, color = mutedText) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (newItemText.isNotBlank()) {
                                    checklist = checklist + ChecklistEntry(
                                        id = "chk_${System.currentTimeMillis()}_${checklist.size}",
                                        text = newItemText.trim()
                                    )
                                    newItemText = ""
                                    persistIfNonEmpty()
                                }
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = primaryText,
                            unfocusedTextColor = primaryText,
                            focusedBorderColor = accent,
                            unfocusedBorderColor = accent.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    Button(
                        onClick = {
                            if (newItemText.isNotBlank()) {
                                checklist = checklist + ChecklistEntry(
                                    id = "chk_${System.currentTimeMillis()}_${checklist.size}",
                                    text = newItemText.trim()
                                )
                                newItemText = ""
                                persistIfNonEmpty()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.Black)
                    }
                }

                // Quick Checklist Toolbar
                if (checklist.isNotEmpty()) {
                    val doneCount = checklist.count { it.isChecked }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("$doneCount / ${checklist.size} done", color = mutedText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "Completed ↓",
                                color = accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    checklist = checklist.sortedBy { it.isChecked }
                                    persistIfNonEmpty()
                                }
                            )
                            Text(
                                if (doneCount == checklist.size) "Uncheck All" else "Check All",
                                color = accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    val targetState = doneCount != checklist.size
                                    checklist = checklist.map { it.copy(isChecked = targetState) }
                                    persistIfNonEmpty()
                                }
                            )
                        }
                    }
                }

                val latestChecklist by rememberUpdatedState(checklist)
                val lazyListState = rememberLazyListState()
                val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
                    val list = latestChecklist.toMutableList()
                    if (from.index in list.indices && to.index in list.indices) {
                        list.add(to.index, list.removeAt(from.index))
                        checklist = list
                        bridge.vibrate(15L)
                    }
                }

                LazyColumn(
                    state = lazyListState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(checklist, key = { it.id }) { entry ->
                        ReorderableItem(reorderableState, key = entry.id) { isDragging ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isDragging) accent.copy(alpha = 0.25f) else topBarBg.copy(alpha = 0.72f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isDragging) accent else accent.copy(alpha = 0.2f)
                                ),
                                shadowElevation = if (isDragging) 6.dp else 0.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "⋮⋮",
                                        color = mutedText,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .draggableHandle(
                                                onDragStarted = { bridge.vibrate(25L) },
                                                onDragStopped = { persistIfNonEmpty() }
                                            )
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                    )

                                    Checkbox(
                                        checked = entry.isChecked,
                                        onCheckedChange = { checked ->
                                            checklist = checklist.map {
                                                if (it.id == entry.id) it.copy(isChecked = checked) else it
                                            }
                                            bridge.vibrate(15L)
                                            persistIfNonEmpty()
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = accent,
                                            checkmarkColor = Color.Black,
                                            uncheckedColor = mutedText
                                        )
                                    )

                                    Text(
                                        text = entry.text,
                                        color = if (entry.isChecked) mutedText else primaryText,
                                        fontSize = 14.sp,
                                        textDecoration = if (entry.isChecked) TextDecoration.LineThrough else TextDecoration.None,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { editingCheckItem = entry }
                                            .padding(horizontal = 4.dp, vertical = 6.dp)
                                    )

                                    IconButton(
                                        onClick = {
                                            checklist = checklist.filterNot { it.id == entry.id }
                                            persistIfNonEmpty()
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove Item",
                                            tint = mutedText,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Inline Edit Checklist Item Dialog
    editingCheckItem?.let { target ->
        var editedText by remember(target.id) { mutableStateOf(target.text) }
        AlertDialog(
            onDismissRequest = { editingCheckItem = null },
            containerColor = topBarBg,
            title = { Text("Edit Item", color = primaryText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = primaryText, unfocusedTextColor = primaryText)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editedText.isNotBlank()) {
                            checklist = checklist.map {
                                if (it.id == target.id) it.copy(text = editedText.trim()) else it
                            }
                            persistIfNonEmpty()
                        }
                        editingCheckItem = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingCheckItem = null }) {
                    Text("Cancel", color = mutedText)
                }
            }
        )
    }

    if (showFolderPickerDialog) {
        val allFolders = remember(NoteRepository.dataRevision) {
            NoteRepository.folders.sortedByDescending { it.lastUsedAtMs }
        }
        AlertDialog(
            onDismissRequest = { showFolderPickerDialog = false },
            containerColor = topBarBg,
            title = { Text("📁 Move to Folder", color = primaryText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (folderId == null) accent.copy(alpha = 0.2f) else Color.Transparent)
                            .clickable {
                                folderId = null
                                isDirty = true
                                persistIfNonEmpty()
                                showFolderPickerDialog = false
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                    ) {
                        Text("📄 Unfiled (No Folder)", color = primaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    allFolders.forEach { folder ->
                        val isSel = folderId == folder.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) accent.copy(alpha = 0.24f) else Color.Transparent)
                                .clickable {
                                    folderId = folder.id
                                    isDirty = true
                                    NoteRepository.touchFolderUsed(bridge, folder.id)
                                    persistIfNonEmpty()
                                    showFolderPickerDialog = false
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp)
                        ) {
                            Text("${folder.icon} ${folder.name}", color = primaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFolderPickerDialog = false }) {
                    Text("Close", color = mutedText)
                }
            }
        )
    }

    // Color Palette Picker Dialog
    if (showColorDialog) {
        AlertDialog(
            onDismissRequest = { showColorDialog = false },
            containerColor = topBarBg,
            title = { Text("Note Color", color = primaryText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NoteColor.entries.chunked(4).forEach { rowColors ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            rowColors.forEach { option ->
                                val isSel = colorId == option.id
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(Color(option.accentHex))
                                            .border(if (isSel) 3.dp else 0.dp, primaryText, CircleShape)
                                            .clickable {
                                                colorId = option.id
                                                showColorDialog = false
                                                persistIfNonEmpty()
                                            }
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(option.label, color = mutedText, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showColorDialog = false }) {
                    Text("Close", color = mutedText)
                }
            }
        )
    }

    // PIN Setup Dialog
    if (showPinDialog) {
        var newPin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        var pinErr by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            containerColor = topBarBg,
            title = { Text("🔒 Set Global Master PIN", color = primaryText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = {
                            newPin = it.filter { c -> c.isDigit() }.take(6)
                            pinErr = null
                        },
                        label = { Text("New PIN (4-6 digits)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = primaryText, unfocusedTextColor = primaryText)
                    )
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = {
                            confirmPin = it.filter { c -> c.isDigit() }.take(6)
                            pinErr = null
                        },
                        label = { Text("Confirm PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = primaryText, unfocusedTextColor = primaryText)
                    )
                    if (pinErr != null) {
                        Text(pinErr!!, color = Color(0xFFF85149), fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        when {
                            newPin.length < 4 -> pinErr = "Use at least 4 digits"
                            newPin != confirmPin -> pinErr = "PINs do not match"
                            else -> {
                                NoteRepository.updateSettings(
                                    bridge,
                                    NoteRepository.settings.copy(masterPinHash = NoteSecurity.hashPin(newPin))
                                )
                                isLocked = true
                                isDirty = true
                                OmniNotesPlugin.dismissStickyOverlay(bridge, noteId)
                                persistIfNonEmpty()
                                showPinDialog = false
                                bridge.showToast("🔒 Global Master PIN set & note locked")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text("Lock", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) {
                    Text("Cancel", color = mutedText)
                }
            }
        )
    }

    // Reminder Scheduler Dialog
    if (showReminderDialog) {
        val defaultCal = remember(reminderAtMs) {
            Calendar.getInstance().apply {
                if (reminderAtMs != null && reminderAtMs!! > System.currentTimeMillis()) {
                    timeInMillis = reminderAtMs!!
                } else {
                    add(Calendar.MINUTE, 30)
                }
            }
        }
        var daysOffsetText by remember { mutableStateOf("0") }
        var hourText by remember { mutableStateOf(String.format(Locale.US, "%02d", defaultCal.get(Calendar.HOUR_OF_DAY))) }
        var minText by remember { mutableStateOf(String.format(Locale.US, "%02d", defaultCal.get(Calendar.MINUTE))) }

        AlertDialog(
            onDismissRequest = { showReminderDialog = false },
            containerColor = topBarBg,
            title = { Text("⏰ Schedule Reminder", color = primaryText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Quick Presets", color = mutedText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "15m" to 15L * 60_000L,
                            "1h" to 60L * 60_000L,
                            "3h" to 180L * 60_000L
                        ).forEach { (label, delta) ->
                            OutlinedButton(
                                onClick = {
                                    reminderAtMs = System.currentTimeMillis() + delta
                                    reminderTriggered = false
                                    persistIfNonEmpty()
                                    showReminderDialog = false
                                    bridge.showToast("⏰ Reminder set for +$label")
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("+$label", fontSize = 12.sp)
                            }
                        }
                    }

                    HorizontalDivider(color = mutedText.copy(alpha = 0.2f))
                    Text("Custom Time (24h)", color = mutedText, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = daysOffsetText,
                            onValueChange = { daysOffsetText = it.filter { c -> c.isDigit() }.take(3) },
                            label = { Text("+Days") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = primaryText, unfocusedTextColor = primaryText),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = hourText,
                            onValueChange = { hourText = it.filter { c -> c.isDigit() }.take(2) },
                            label = { Text("Hr (0-23)") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = primaryText, unfocusedTextColor = primaryText),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = minText,
                            onValueChange = { minText = it.filter { c -> c.isDigit() }.take(2) },
                            label = { Text("Min") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = primaryText, unfocusedTextColor = primaryText),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (reminderAtMs != null) {
                        TextButton(
                            onClick = {
                                reminderAtMs = null
                                reminderTriggered = false
                                persistIfNonEmpty()
                                showReminderDialog = false
                                bridge.showToast("Reminder cleared")
                            }
                        ) {
                            Text("Clear Existing Reminder", color = Color(0xFFF85149), fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val days = daysOffsetText.toIntOrNull() ?: 0
                        val hr = (hourText.toIntOrNull() ?: 9).coerceIn(0, 23)
                        val min = (minText.toIntOrNull() ?: 0).coerceIn(0, 59)
                        val target = Calendar.getInstance().apply {
                            add(Calendar.DAY_OF_YEAR, days)
                            set(Calendar.HOUR_OF_DAY, hr)
                            set(Calendar.MINUTE, min)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        reminderAtMs = target.timeInMillis
                        reminderTriggered = false
                        persistIfNonEmpty()
                        showReminderDialog = false
                        val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(target.time)
                        bridge.showToast("⏰ Scheduled for $fmt")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text("Set", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReminderDialog = false }) {
                    Text("Cancel", color = mutedText)
                }
            }
        )
    }
}