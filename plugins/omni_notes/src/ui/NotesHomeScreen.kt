package com.omni.plugin.notes.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.hub.api.HostBridge
import com.omni.plugin.notes.OmniNotesPlugin
import com.omni.plugin.notes.model.NoteColor
import com.omni.plugin.notes.model.NoteItem
import com.omni.plugin.notes.model.NoteSection
import com.omni.plugin.notes.model.NoteSecurity
import com.omni.plugin.notes.model.NoteSortOrder
import com.omni.plugin.notes.model.NoteStatus
import com.omni.plugin.notes.model.NoteThemeMode
import com.omni.plugin.notes.model.NoteType
import com.omni.plugin.notes.model.NoteViewMode
import com.omni.plugin.notes.storage.NoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesHomeScreen(
    context: Context,
    bridge: HostBridge,
    registerBackInterceptor: ((() -> Boolean)?) -> Unit,
    onOpenNote: (NoteItem) -> Unit,
    onCreateNote: (type: NoteType, colorId: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val settings = NoteRepository.settings
    val isDark = settings.themeMode == NoteThemeMode.DARK
    val bgColor = if (isDark) Color(0xFF0D1117) else Color(0xFFF6F5F0)
    val surfaceColor = if (isDark) Color(0xFF161B22) else Color.White
    val primaryText = if (isDark) Color(0xFFF0F6FC) else Color(0xFF1F2328)
    val mutedText = if (isDark) Color(0xFF8B949E) else Color(0xFF57606A)

    var selectedSection by remember { mutableStateOf(NoteSection.ALL) }
    var selectedColorFilter by remember { mutableStateOf<String?>(null) }
    var isSearchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSortMenu by remember { mutableStateOf(false) }
    var showFabMenu by remember { mutableStateOf(false) }
    var standaloneWindowEnabled by remember { mutableStateOf(bridge.isStandaloneWindowEnabled()) }

    var selectedNoteIds by remember { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedNoteIds.isNotEmpty()
    var showBatchColorDialog by remember { mutableStateOf(false) }
    var showMasterPinDialog by remember { mutableStateOf(false) }
    var pendingBatchLockIds by remember { mutableStateOf<Set<String>?>(null) }

    var pendingImportJson by remember { mutableStateOf<String?>(null) }
    var pendingImportEncryptedCount by remember { mutableIntStateOf(0) }
    var showColorNoteDecryptDialog by remember { mutableStateOf(false) }

    DisposableEffect(drawerState.isOpen, isSearchOpen, showFabMenu, selectedColorFilter, isSelectionMode) {
        registerBackInterceptor {
            when {
                isSelectionMode -> {
                    selectedNoteIds = emptySet()
                    true
                }
                drawerState.isOpen -> {
                    scope.launch { drawerState.close() }
                    true
                }
                showFabMenu -> {
                    showFabMenu = false
                    true
                }
                isSearchOpen -> {
                    isSearchOpen = false
                    searchQuery = ""
                    true
                }
                selectedColorFilter != null -> {
                    selectedColorFilter = null
                    true
                }
                else -> false
            }
        }
        onDispose { }
    }

    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            debouncedQuery = ""
        } else {
            delay(120L)
            debouncedQuery = searchQuery.trim()
        }
    }

    var displayNotes by remember { mutableStateOf<List<NoteItem>>(emptyList()) }
    LaunchedEffect(
        NoteRepository.dataRevision,
        selectedSection,
        selectedColorFilter,
        debouncedQuery,
        settings.sortOrder
    ) {
        val computed = withContext(Dispatchers.Default) {
            val snapshot = NoteRepository.snapshotNotes()
            val base = snapshot.filter { note ->
                val sectionOk = when (selectedSection) {
                    NoteSection.ALL -> note.status == NoteStatus.ACTIVE
                    NoteSection.PINNED -> note.status == NoteStatus.ACTIVE && note.isPinned
                    NoteSection.REMINDERS -> note.status != NoteStatus.TRASH && note.reminderAtMs != null
                    NoteSection.ARCHIVE -> note.status == NoteStatus.ARCHIVED
                    NoteSection.TRASH -> note.status == NoteStatus.TRASH
                }
                if (!sectionOk) return@filter false
                if (selectedColorFilter != null && note.colorId != selectedColorFilter) return@filter false
                if (debouncedQuery.isEmpty()) return@filter true

                note.title.contains(debouncedQuery, ignoreCase = true) ||
                    (!note.isLocked && note.content.contains(debouncedQuery, ignoreCase = true)) ||
                    (!note.isLocked && note.checklist.any { it.text.contains(debouncedQuery, ignoreCase = true) })
            }

            val comparator = when (settings.sortOrder) {
                NoteSortOrder.CUSTOM -> compareByDescending<NoteItem> { it.isPinned }.thenBy { it.sortIndex }.thenByDescending { it.updatedAtMs }
                NoteSortOrder.MODIFIED_DESC -> compareByDescending<NoteItem> { it.isPinned }.thenByDescending { it.updatedAtMs }
                NoteSortOrder.CREATED_DESC -> compareByDescending<NoteItem> { it.isPinned }.thenByDescending { it.createdAtMs }
                NoteSortOrder.TITLE_ASC -> compareByDescending<NoteItem> { it.isPinned }.thenBy { it.title.lowercase(Locale.US) }
                NoteSortOrder.COLOR -> compareByDescending<NoteItem> { it.isPinned }.thenBy { it.color.ordinal }.thenByDescending { it.updatedAtMs }
            }
            base.sortedWith(comparator)
        }
        displayNotes = computed
    }

    var didDragReorder by remember { mutableStateOf(false) }
    var activeLongPressNoteId by remember { mutableStateOf<String?>(null) }
    var lastLongPressEndedNoteId by remember { mutableStateOf<String?>(null) }
    var lastLongPressEndMs by remember { mutableLongStateOf(0L) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = surfaceColor,
                modifier = Modifier.width(300.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📝", fontSize = 26.sp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Omni Notes", fontWeight = FontWeight.Bold, fontSize = 19.sp, color = primaryText)
                            Text(
                                "${NoteRepository.notes.count { it.status == NoteStatus.ACTIVE }} Active Notes",
                                fontSize = 11.sp,
                                color = mutedText
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = mutedText.copy(alpha = 0.2f))
                    Spacer(Modifier.height(12.dp))

                    NoteSection.entries.forEach { section ->
                        val count = when (section) {
                            NoteSection.ALL -> NoteRepository.notes.count { it.status == NoteStatus.ACTIVE }
                            NoteSection.PINNED -> NoteRepository.notes.count { it.status == NoteStatus.ACTIVE && it.isPinned }
                            NoteSection.REMINDERS -> NoteRepository.notes.count { it.status != NoteStatus.TRASH && it.reminderAtMs != null }
                            NoteSection.ARCHIVE -> NoteRepository.notes.count { it.status == NoteStatus.ARCHIVED }
                            NoteSection.TRASH -> NoteRepository.notes.count { it.status == NoteStatus.TRASH }
                        }
                        NavigationDrawerItem(
                            icon = { Text(section.icon, fontSize = 16.sp) },
                            label = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(section.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text("$count", fontSize = 12.sp, color = mutedText)
                                }
                            },
                            selected = selectedSection == section,
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = Color(0xFFE5A93C).copy(alpha = 0.18f),
                                selectedTextColor = if (isDark) Color(0xFFE5A93C) else Color(0xFFB45309),
                                unselectedTextColor = primaryText
                            ),
                            onClick = {
                                selectedSection = section
                                scope.launch { drawerState.close() }
                            }
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = mutedText.copy(alpha = 0.2f))
                    Spacer(Modifier.height(12.dp))

                    // Decoupled App Theme Switch (Dark vs Light)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                val next = if (isDark) NoteThemeMode.LIGHT else NoteThemeMode.DARK
                                NoteRepository.updateSettings(bridge, settings.copy(themeMode = next))
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Light Theme", color = primaryText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(if (isDark) "Dark slate UI" else "Bright pastel UI", color = mutedText, fontSize = 10.sp)
                        }
                        Switch(
                            checked = !isDark,
                            onCheckedChange = { light ->
                                val next = if (light) NoteThemeMode.LIGHT else NoteThemeMode.DARK
                                NoteRepository.updateSettings(bridge, settings.copy(themeMode = next))
                            }
                        )
                    }

                    // Independent Ruled Paper Mode Switch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                NoteRepository.updateSettings(bridge, settings.copy(isPaperMode = !settings.isPaperMode))
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Paper Mode (Ruled Lines)", color = primaryText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("Notebook lines inside editor", color = mutedText, fontSize = 10.sp)
                        }
                        Switch(
                            checked = settings.isPaperMode,
                            onCheckedChange = { enabled ->
                                NoteRepository.updateSettings(bridge, settings.copy(isPaperMode = enabled))
                            }
                        )
                    }

                    // Global Master Lock PIN Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                scope.launch { drawerState.close() }
                                showMasterPinDialog = true
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (settings.masterPinHash.isNullOrBlank()) "🔒 Set Master Lock PIN" else "🔐 Master Lock PIN Active",
                                color = primaryText,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            Text("One global password locks any note", color = mutedText, fontSize = 10.sp)
                        }
                    }

                    // Granular Standalone OS Window Toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Standalone OS Window", color = primaryText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("Show separately in phone Recents", color = mutedText, fontSize = 10.sp)
                        }
                        Switch(
                            checked = standaloneWindowEnabled,
                            onCheckedChange = { enabled ->
                                standaloneWindowEnabled = enabled
                                bridge.setStandaloneWindowEnabled(enabled)
                            }
                        )
                    }

                    Spacer(Modifier.weight(1f))
                    HorizontalDivider(color = mutedText.copy(alpha = 0.2f))
                    Spacer(Modifier.height(10.dp))

                    Text("Vault Backup", fontSize = 11.sp, color = mutedText, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val path = withContext(Dispatchers.IO) { NoteRepository.exportVaultBackup(bridge) }
                                    bridge.showToast(if (path != null) "Backed up ${NoteRepository.notes.size} notes!" else "Backup failed")
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text("💾 Save", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val count = withContext(Dispatchers.IO) { NoteRepository.importVaultBackup(bridge) }
                                    val msg = when {
                                        count > 0 -> "Restored $count notes from Vault!"
                                        count == 0 -> "All Vault notes already up to date"
                                        count == -1 -> "No Vault backup found yet"
                                        else -> "Restore failed"
                                    }
                                    bridge.showToast(msg)
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text("📥 Restore", fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch { drawerState.close() }
                            bridge.pickFiles("application/json", false) { uris ->
                                val uri = uris.firstOrNull() ?: return@pickFiles
                                scope.launch {
                                    try {
                                        val (raw, encCount) = withContext(Dispatchers.IO) {
                                            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                                            if (text.isNullOrBlank()) Pair(null, 0)
                                            else Pair(text, NoteRepository.countEncryptedInColorNoteJson(text))
                                        }
                                        if (raw.isNullOrBlank()) {
                                            bridge.showToast("Selected file is empty")
                                            return@launch
                                        }
                                        if (encCount > 0) {
                                            pendingImportJson = raw
                                            pendingImportEncryptedCount = encCount
                                            showColorNoteDecryptDialog = true
                                        } else {
                                            val res = withContext(Dispatchers.IO) { NoteRepository.importColorNoteJson(bridge, raw, null) }
                                            bridge.showToast("Imported ${res.importedCount} ColorNote notes!")
                                        }
                                    } catch (e: Exception) {
                                        bridge.showToast("Import error: ${e.message}")
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Text("📒 Import ColorNote JSON", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    ) {
        Scaffold(
            containerColor = bgColor,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = surfaceColor),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Drawer", tint = primaryText)
                        }
                    },
                    title = {
                        if (isSearchOpen) {
                            TextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search notes...", fontSize = 14.sp, color = mutedText) },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedTextColor = primaryText,
                                    unfocusedTextColor = primaryText,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                "${selectedSection.icon} ${selectedSection.label}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = primaryText
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (isSearchOpen) {
                                isSearchOpen = false
                                searchQuery = ""
                            } else {
                                isSearchOpen = true
                            }
                        }) {
                            Icon(
                                if (isSearchOpen) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = "Search",
                                tint = primaryText
                            )
                        }

                        // Cycle List -> Grid -> Details
                        TextButton(
                            onClick = {
                                val modes = NoteViewMode.entries
                                val nextMode = modes[(settings.viewMode.ordinal + 1) % modes.size]
                                NoteRepository.updateSettings(bridge, settings.copy(viewMode = nextMode))
                                bridge.showToast("View: ${nextMode.label}")
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(settings.viewMode.badge, fontSize = 18.sp, color = primaryText)
                        }

                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Sort", tint = primaryText)
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                                modifier = Modifier.background(surfaceColor)
                            ) {
                                NoteSortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                (if (settings.sortOrder == order) "✓ " else "   ") + order.label,
                                                color = primaryText,
                                                fontSize = 13.sp
                                            )
                                        },
                                        onClick = {
                                            showSortMenu = false
                                            NoteRepository.updateSettings(bridge, settings.copy(sortOrder = order))
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                if (selectedSection != NoteSection.TRASH && selectedSection != NoteSection.ARCHIVE) {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AnimatedVisibility(visible = showFabMenu) {
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ExtendedFloatingActionButton(
                                    onClick = {
                                        showFabMenu = false
                                        onCreateNote(NoteType.CHECKLIST, selectedColorFilter ?: NoteColor.BLUE.id)
                                    },
                                    containerColor = surfaceColor,
                                    contentColor = primaryText,
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("☑️  Checklist", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                ExtendedFloatingActionButton(
                                    onClick = {
                                        showFabMenu = false
                                        onCreateNote(NoteType.TEXT, selectedColorFilter ?: NoteColor.YELLOW.id)
                                    },
                                    containerColor = surfaceColor,
                                    contentColor = primaryText,
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("📄  Text Note", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }

                        FloatingActionButton(
                            onClick = { showFabMenu = !showFabMenu },
                            containerColor = Color(0xFFE5A93C),
                            contentColor = Color.Black,
                            shape = CircleShape
                        ) {
                            Icon(
                                if (showFabMenu) Icons.Default.Close else Icons.Default.Add,
                                contentDescription = "New Note"
                            )
                        }
                    }
                }
            },
            bottomBar = {
                if (isSelectionMode) {
                    val selectedNotes = displayNotes.filter { selectedNoteIds.contains(it.id) }
                    val allPinned = selectedNotes.isNotEmpty() && selectedNotes.all { it.isPinned }
                    val allLocked = selectedNotes.isNotEmpty() && selectedNotes.all { it.isLocked }
                    val inTrash = selectedSection == NoteSection.TRASH
                    val inArchive = selectedSection == NoteSection.ARCHIVE

                    Surface(
                        color = surfaceColor,
                        shadowElevation = 12.dp,
                        border = BorderStroke(1.dp, mutedText.copy(alpha = 0.25f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${selectedNoteIds.size} selected",
                                    color = primaryText,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                                TextButton(onClick = { selectedNoteIds = emptySet() }) {
                                    Text("Cancel", color = mutedText, fontSize = 12.sp)
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (!inTrash) {
                                    FilledTonalButton(
                                        onClick = {
                                            NoteRepository.batchSetPinned(bridge, selectedNoteIds, !allPinned)
                                            selectedNoteIds = emptySet()
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(if (allPinned) "📌 Unpin" else "📌 Pin", fontSize = 12.sp)
                                    }
                                    FilledTonalButton(
                                        onClick = { showBatchColorDialog = true },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("🎨 Color", fontSize = 12.sp)
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            if (allLocked) {
                                                NoteRepository.batchSetLocked(bridge, selectedNoteIds, false)
                                                selectedNoteIds = emptySet()
                                                bridge.showToast("Unlocked")
                                            } else if (!settings.masterPinHash.isNullOrBlank()) {
                                                selectedNoteIds.forEach { OmniNotesPlugin.dismissStickyOverlay(bridge, it) }
                                                NoteRepository.batchSetLocked(bridge, selectedNoteIds, true)
                                                selectedNoteIds = emptySet()
                                                bridge.showToast("🔒 Locked with Master PIN")
                                            } else {
                                                pendingBatchLockIds = selectedNoteIds
                                                showMasterPinDialog = true
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(if (allLocked) "🔓 Unlock" else "🔒 Lock", fontSize = 12.sp)
                                    }
                                    if (selectedNotes.size == 1) {
                                        val single = selectedNotes.first()
                                        FilledTonalButton(
                                            onClick = {
                                                OmniNotesPlugin.toggleStickyOverlay(context, bridge, single)
                                                selectedNoteIds = emptySet()
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text("🪟 Float", fontSize = 12.sp)
                                        }
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            val targetStatus = if (inArchive) NoteStatus.ACTIVE else NoteStatus.ARCHIVED
                                            NoteRepository.batchSetStatus(bridge, selectedNoteIds, targetStatus)
                                            selectedNoteIds = emptySet()
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(if (inArchive) "📤 Unarchive" else "📦 Archive", fontSize = 12.sp)
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            selectedNoteIds.forEach { OmniNotesPlugin.dismissStickyOverlay(bridge, it) }
                                            NoteRepository.batchSetStatus(bridge, selectedNoteIds, NoteStatus.TRASH)
                                            selectedNoteIds = emptySet()
                                            bridge.showToast("Moved to Trash")
                                        },
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = Color(0xFFF85149).copy(alpha = 0.2f),
                                            contentColor = Color(0xFFF85149)
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("🗑️ Delete", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    FilledTonalButton(
                                        onClick = {
                                            NoteRepository.batchSetStatus(bridge, selectedNoteIds, NoteStatus.ACTIVE)
                                            selectedNoteIds = emptySet()
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("♻️ Restore", fontSize = 12.sp)
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            selectedNoteIds.forEach { OmniNotesPlugin.dismissStickyOverlay(bridge, it) }
                                            NoteRepository.batchDeleteForever(bridge, selectedNoteIds)
                                            selectedNoteIds = emptySet()
                                        },
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = Color(0xFFF85149).copy(alpha = 0.2f),
                                            contentColor = Color(0xFFF85149)
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("🗑️ Delete Forever", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(bgColor)
            ) {
                // Color Filter Dot Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (selectedColorFilter == null) Color(0xFFE5A93C) else surfaceColor,
                        border = BorderStroke(1.dp, mutedText.copy(alpha = 0.25f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { selectedColorFilter = null }
                    ) {
                        Text(
                            "All",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedColorFilter == null) Color.Black else primaryText,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }

                    NoteColor.entries.forEach { nc ->
                        val isSel = selectedColorFilter == nc.id
                        Box(
                            modifier = Modifier
                                .size(if (isSel) 28.dp else 24.dp)
                                .clip(CircleShape)
                                .background(Color(nc.accentHex))
                                .border(
                                    width = if (isSel) 2.5.dp else 1.dp,
                                    color = if (isSel) primaryText else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable {
                                    selectedColorFilter = if (isSel) null else nc.id
                                }
                        )
                    }
                }

                // Trash Header Bar
                if (selectedSection == NoteSection.TRASH && displayNotes.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${displayNotes.size} deleted notes",
                            color = mutedText,
                            fontSize = 12.sp
                        )
                        TextButton(onClick = {
                            NoteRepository.emptyTrash(bridge)
                            bridge.showToast("Trash emptied")
                        }) {
                            Text("Empty Trash", color = Color(0xFFF85149), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }

                if (displayNotes.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(selectedSection.icon, fontSize = 36.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (searchQuery.isNotBlank()) "No matching notes" else "Nothing here yet",
                                color = primaryText,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Tap + to create a color note or checklist",
                                color = mutedText,
                                fontSize = 12.sp
                            )
                        }
                    }
                } else {
                    when (settings.viewMode) {
                        NoteViewMode.GRID -> {
                            val lazyGridState = rememberLazyGridState()
                            val reorderableGridState = rememberReorderableLazyGridState(lazyGridState) { from, to ->
                                if (from.index != to.index) {
                                    val list = displayNotes.toMutableList()
                                    if (from.index in list.indices && to.index in list.indices) {
                                        list.add(to.index, list.removeAt(from.index))
                                        displayNotes = list
                                        didDragReorder = true
                                        selectedNoteIds = emptySet()
                                        bridge.vibrate(12L)
                                    }
                                }
                            }
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                LazyVerticalGrid(
                                    state = lazyGridState,
                                    columns = GridCells.Fixed(2),
                                    contentPadding = PaddingValues(start = 12.dp, end = 18.dp, top = 12.dp, bottom = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(displayNotes, key = { it.id }, contentType = { it.type }) { note ->
                                        ReorderableItem(reorderableGridState, key = note.id) { isDragging ->
                                            val isSelected = selectedNoteIds.contains(note.id)
                                            NoteGridCard(
                                                note = note,
                                                isDark = isDark,
                                                isSelected = isSelected,
                                                isDimmed = isSelectionMode && !isSelected,
                                                isDragging = isDragging,
                                                isStickyMounted = OmniNotesPlugin.isStickyMounted(note.id),
                                                dragModifier = Modifier.longPressDraggableHandle(
                                                    onDragStarted = {
                                                        didDragReorder = false
                                                        activeLongPressNoteId = note.id
                                                        bridge.vibrate(25L)
                                                        selectedNoteIds = selectedNoteIds + note.id
                                                    },
                                                    onDragStopped = {
                                                        activeLongPressNoteId = null
                                                        lastLongPressEndedNoteId = note.id
                                                        lastLongPressEndMs = System.currentTimeMillis()
                                                        if (didDragReorder) {
                                                            NoteRepository.applyCustomOrder(bridge, displayNotes.map { it.id })
                                                            selectedNoteIds = emptySet()
                                                        }
                                                    }
                                                ),
                                                onClick = {
                                                    val justEndedThis = lastLongPressEndedNoteId == note.id &&
                                                        (System.currentTimeMillis() - lastLongPressEndMs) < 350L
                                                    if (activeLongPressNoteId == note.id || justEndedThis) {
                                                        return@NoteGridCard
                                                    }
                                                    if (isSelectionMode) {
                                                        selectedNoteIds = if (isSelected) selectedNoteIds - note.id else selectedNoteIds + note.id
                                                    } else {
                                                        onOpenNote(note)
                                                    }
                                                },
                                                onToggleSticky = { OmniNotesPlugin.toggleStickyOverlay(context, bridge, note) },
                                                onRestore = { NoteRepository.setStatus(bridge, note.id, NoteStatus.ACTIVE) },
                                                onDeleteForever = {
                                                    OmniNotesPlugin.dismissStickyOverlay(bridge, note.id)
                                                    NoteRepository.deleteForever(bridge, note.id)
                                                }
                                            )
                                        }
                                    }
                                }

                                val gridInfo by remember { derivedStateOf { lazyGridState.layoutInfo } }
                                EdgeFastScroller(
                                    totalItems = gridInfo.totalItemsCount,
                                    visibleItems = gridInfo.visibleItemsInfo.size,
                                    firstVisibleIndex = lazyGridState.firstVisibleItemIndex,
                                    accentColor = Color(0xFFE5A93C),
                                    surfaceColor = surfaceColor,
                                    textColor = primaryText,
                                    getPreviewLabel = { idx ->
                                        val item = displayNotes.getOrNull(idx)
                                        if (item != null) "${NoteDateFormats.formatShort(item.updatedAtMs)} • ${idx + 1}/${displayNotes.size}" else "${idx + 1}"
                                    },
                                    onScrollToIndex = { idx ->
                                        scope.launch { lazyGridState.scrollToItem(idx) }
                                    },
                                    modifier = Modifier.align(Alignment.CenterEnd)
                                )
                            }
                        }
                        NoteViewMode.LIST, NoteViewMode.DETAILS -> {
                            val showDetails = settings.viewMode == NoteViewMode.DETAILS
                            val lazyListState = rememberLazyListState()
                            val reorderableListState = rememberReorderableLazyListState(lazyListState) { from, to ->
                                if (from.index != to.index) {
                                    val list = displayNotes.toMutableList()
                                    if (from.index in list.indices && to.index in list.indices) {
                                        list.add(to.index, list.removeAt(from.index))
                                        displayNotes = list
                                        didDragReorder = true
                                        selectedNoteIds = emptySet()
                                        bridge.vibrate(12L)
                                    }
                                }
                            }
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                LazyColumn(
                                    state = lazyListState,
                                    contentPadding = PaddingValues(start = 12.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(displayNotes, key = { it.id }, contentType = { it.type }) { note ->
                                        ReorderableItem(reorderableListState, key = note.id) { isDragging ->
                                            val isSelected = selectedNoteIds.contains(note.id)
                                            NoteRowCard(
                                                note = note,
                                                isDark = isDark,
                                                showDetails = showDetails,
                                                isSelected = isSelected,
                                                isDimmed = isSelectionMode && !isSelected,
                                                isDragging = isDragging,
                                                isStickyMounted = OmniNotesPlugin.isStickyMounted(note.id),
                                                dragModifier = Modifier.longPressDraggableHandle(
                                                    onDragStarted = {
                                                        didDragReorder = false
                                                        activeLongPressNoteId = note.id
                                                        bridge.vibrate(25L)
                                                        selectedNoteIds = selectedNoteIds + note.id
                                                    },
                                                    onDragStopped = {
                                                        activeLongPressNoteId = null
                                                        lastLongPressEndedNoteId = note.id
                                                        lastLongPressEndMs = System.currentTimeMillis()
                                                        if (didDragReorder) {
                                                            NoteRepository.applyCustomOrder(bridge, displayNotes.map { it.id })
                                                            selectedNoteIds = emptySet()
                                                        }
                                                    }
                                                ),
                                                onClick = {
                                                    val justEndedThis = lastLongPressEndedNoteId == note.id &&
                                                        (System.currentTimeMillis() - lastLongPressEndMs) < 350L
                                                    if (activeLongPressNoteId == note.id || justEndedThis) {
                                                        return@NoteRowCard
                                                    }
                                                    if (isSelectionMode) {
                                                        selectedNoteIds = if (isSelected) selectedNoteIds - note.id else selectedNoteIds + note.id
                                                    } else {
                                                        onOpenNote(note)
                                                    }
                                                },
                                                onToggleSticky = { OmniNotesPlugin.toggleStickyOverlay(context, bridge, note) },
                                                onRestore = { NoteRepository.setStatus(bridge, note.id, NoteStatus.ACTIVE) },
                                                onDeleteForever = {
                                                    OmniNotesPlugin.dismissStickyOverlay(bridge, note.id)
                                                    NoteRepository.deleteForever(bridge, note.id)
                                                }
                                            )
                                        }
                                    }
                                }

                                val listInfo by remember { derivedStateOf { lazyListState.layoutInfo } }
                                EdgeFastScroller(
                                    totalItems = listInfo.totalItemsCount,
                                    visibleItems = listInfo.visibleItemsInfo.size,
                                    firstVisibleIndex = lazyListState.firstVisibleItemIndex,
                                    accentColor = Color(0xFFE5A93C),
                                    surfaceColor = surfaceColor,
                                    textColor = primaryText,
                                    getPreviewLabel = { idx ->
                                        val item = displayNotes.getOrNull(idx)
                                        if (item != null) "${NoteDateFormats.formatShort(item.updatedAtMs)} • ${idx + 1}/${displayNotes.size}" else "${idx + 1}"
                                    },
                                    onScrollToIndex = { idx ->
                                        scope.launch { lazyListState.scrollToItem(idx) }
                                    },
                                    modifier = Modifier.align(Alignment.CenterEnd)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showBatchColorDialog) {
        AlertDialog(
            onDismissRequest = { showBatchColorDialog = false },
            containerColor = surfaceColor,
            title = { Text("Change Color", color = primaryText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NoteColor.entries.chunked(5).forEach { rowColors ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            rowColors.forEach { option ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(Color(option.accentHex))
                                            .clickable {
                                                NoteRepository.batchSetColor(bridge, selectedNoteIds, option.id)
                                                showBatchColorDialog = false
                                                selectedNoteIds = emptySet()
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
                TextButton(onClick = { showBatchColorDialog = false }) {
                    Text("Close", color = mutedText)
                }
            }
        )
    }

    if (showMasterPinDialog) {
        val hasExisting = !settings.masterPinHash.isNullOrBlank()
        var currentPin by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = {
                showMasterPinDialog = false
                pendingBatchLockIds = null
            },
            containerColor = surfaceColor,
            title = {
                Text(
                    if (hasExisting) "🔐 Change Master PIN" else "🔒 Set Global Master PIN",
                    color = primaryText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasExisting) {
                        OutlinedTextField(
                            value = currentPin,
                            onValueChange = { currentPin = it.filter { c -> c.isDigit() }.take(6); err = null },
                            label = { Text("Current PIN") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = primaryText, unfocusedTextColor = primaryText)
                        )
                    }
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(6); err = null },
                        label = { Text("New PIN (4-6 digits)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = primaryText, unfocusedTextColor = primaryText)
                    )
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(6); err = null },
                        label = { Text("Confirm New PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = primaryText, unfocusedTextColor = primaryText)
                    )
                    if (err != null) {
                        Text(err!!, color = Color(0xFFF85149), fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (hasExisting && NoteSecurity.hashPin(currentPin) != settings.masterPinHash) {
                            err = "Incorrect current PIN"
                            return@Button
                        }
                        if (newPin.length < 4) {
                            err = "Use at least 4 digits"
                            return@Button
                        }
                        if (newPin != confirmPin) {
                            err = "PINs do not match"
                            return@Button
                        }
                        NoteRepository.updateSettings(bridge, settings.copy(masterPinHash = NoteSecurity.hashPin(newPin)))
                        pendingBatchLockIds?.let { ids ->
                            ids.forEach { OmniNotesPlugin.dismissStickyOverlay(bridge, it) }
                            NoteRepository.batchSetLocked(bridge, ids, true)
                            selectedNoteIds = emptySet()
                        }
                        pendingBatchLockIds = null
                        showMasterPinDialog = false
                        bridge.showToast("🔐 Master PIN saved")
                    }
                ) {
                    Text("Save PIN")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showMasterPinDialog = false
                    pendingBatchLockIds = null
                }) {
                    Text("Cancel", color = mutedText)
                }
            }
        )
    }

    if (showColorNoteDecryptDialog && pendingImportJson != null) {
        var passwordInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                showColorNoteDecryptDialog = false
                pendingImportJson = null
            },
            containerColor = surfaceColor,
            title = { Text("🔒 Locked Notes Detected", color = primaryText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Found $pendingImportEncryptedCount locked note(s) in this ColorNote export. Enter your password to decrypt them (or skip locked notes).",
                        color = mutedText,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("ColorNote Password / PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = primaryText, unfocusedTextColor = primaryText)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val raw = pendingImportJson!!
                        val pw = passwordInput
                        showColorNoteDecryptDialog = false
                        pendingImportJson = null
                        scope.launch {
                            val res = withContext(Dispatchers.IO) { NoteRepository.importColorNoteJson(bridge, raw, pw) }
                            bridge.showToast("Imported ${res.importedCount} notes (${res.decryptedCount} decrypted, ${res.lockedSkippedOrFailedCount} skipped)")
                        }
                    },
                    enabled = passwordInput.isNotEmpty()
                ) {
                    Text("Decrypt & Import")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val raw = pendingImportJson!!
                        showColorNoteDecryptDialog = false
                        pendingImportJson = null
                        scope.launch {
                            val res = withContext(Dispatchers.IO) { NoteRepository.importColorNoteJson(bridge, raw, null) }
                            bridge.showToast("Imported ${res.importedCount} unencrypted notes (${res.lockedSkippedOrFailedCount} locked skipped)")
                        }
                    }
                ) {
                    Text("Skip Locked", color = mutedText)
                }
            }
        )
    }
}

private object NoteDateFormats {
    private val shortFmt = SimpleDateFormat("MMM d", Locale.US)
    private val longFmt = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US)

    fun formatShort(ms: Long): String = synchronized(shortFmt) { shortFmt.format(Date(ms)) }
    fun formatLong(ms: Long): String = synchronized(longFmt) { longFmt.format(Date(ms)) }
}

@Composable
private fun EdgeFastScroller(
    totalItems: Int,
    visibleItems: Int,
    firstVisibleIndex: Int,
    accentColor: Color,
    surfaceColor: Color,
    textColor: Color,
    getPreviewLabel: (Int) -> String,
    onScrollToIndex: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (totalItems <= visibleItems || totalItems <= 12) return

    val density = LocalDensity.current
    var trackHeightPx by remember { mutableIntStateOf(1) }
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var previewIndex by remember { mutableIntStateOf(0) }

    val thumbHeightPx = with(density) { 46.dp.toPx() }
    val usableHeightPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
    val maxFirstIndex = (totalItems - visibleItems).coerceAtLeast(1)

    val activeFraction = if (isDragging) {
        dragFraction
    } else {
        (firstVisibleIndex.toFloat() / maxFirstIndex.toFloat()).coerceIn(0f, 1f)
    }
    val thumbOffsetY = (activeFraction * usableHeightPx).roundToInt()

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 8.dp)
            .onSizeChanged { trackHeightPx = it.height }
    ) {
        if (isDragging) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = surfaceColor,
                shadowElevation = 6.dp,
                border = BorderStroke(1.dp, accentColor),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(-with(density) { 36.dp.roundToPx() }, (thumbOffsetY - 4).coerceAtLeast(0)) }
            ) {
                Text(
                    text = getPreviewLabel(previewIndex),
                    color = textColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(24.dp)
                .fillMaxHeight()
                .pointerInput(totalItems, maxFirstIndex, usableHeightPx) {
                    detectTapGestures { offset ->
                        val frac = ((offset.y - thumbHeightPx / 2f) / usableHeightPx).coerceIn(0f, 1f)
                        val target = (frac * maxFirstIndex).roundToInt().coerceIn(0, totalItems - 1)
                        previewIndex = target
                        onScrollToIndex(target)
                    }
                }
                .pointerInput(totalItems, maxFirstIndex, usableHeightPx) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            val frac = ((offset.y - thumbHeightPx / 2f) / usableHeightPx).coerceIn(0f, 1f)
                            dragFraction = frac
                            val target = (frac * maxFirstIndex).roundToInt().coerceIn(0, totalItems - 1)
                            previewIndex = target
                            onScrollToIndex(target)
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            val frac = ((change.position.y - thumbHeightPx / 2f) / usableHeightPx).coerceIn(0f, 1f)
                            dragFraction = frac
                            val target = (frac * maxFirstIndex).roundToInt().coerceIn(0, totalItems - 1)
                            previewIndex = target
                            onScrollToIndex(target)
                        }
                    )
                }
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 3.dp)
                    .offset { IntOffset(0, thumbOffsetY) }
                    .width(if (isDragging) 8.dp else 5.dp)
                    .height(46.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isDragging) accentColor else accentColor.copy(alpha = 0.6f))
            )
        }
    }
}

@Composable
private fun NoteGridCard(
    note: NoteItem,
    isDark: Boolean,
    isSelected: Boolean = false,
    isDimmed: Boolean = false,
    isDragging: Boolean = false,
    isStickyMounted: Boolean,
    dragModifier: Modifier = Modifier,
    onClick: () -> Unit,
    onToggleSticky: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit
) {
    val nc = note.color
    val cardBg = Color(if (isDark) nc.darkBgHex else nc.paperBgHex)
    val accent = Color(nc.accentHex)
    val textColor = if (isDark) Color(0xFFF0F6FC) else Color(0xFF1F2328)
    val subColor = if (isDark) Color(0xFF9EA7B3) else Color(0xFF4B5563)
    val selectColor = if (isDark) Color(0xFF58A6FF) else Color(0xFF1D4ED8)

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 10.dp else 0.dp),
        border = BorderStroke(
            if (isSelected || isDragging) 2.5.dp else 1.dp,
            if (isSelected) selectColor else accent.copy(alpha = if (isDark) 0.45f else 0.7f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(156.dp)
            .alpha(if (isDimmed) 0.52f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .then(dragModifier)
            .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(if (isSelected) selectColor else accent)
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = note.title.ifBlank { if (note.type == NoteType.CHECKLIST) "Checklist" else "Untitled" },
                            color = textColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (note.isPinned) Text("📌", fontSize = 11.sp)
                            if (note.isLocked) Text("🔒", fontSize = 11.sp)
                            if (note.reminderAtMs != null && !note.reminderTriggered) Text("⏰", fontSize = 11.sp)
                            if (isStickyMounted) Text("🪟", fontSize = 11.sp)
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    if (note.isLocked) {
                        Text("Protected Note", color = subColor, fontSize = 12.sp)
                    } else if (note.type == NoteType.CHECKLIST) {
                        val done = note.checklist.count { it.isChecked }
                        val total = note.checklist.size
                        note.checklist.take(3).forEach { entry ->
                            Text(
                                text = (if (entry.isChecked) "☑ " else "☐ ") + entry.text,
                                color = if (entry.isChecked) subColor else textColor,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (total > 0) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { done.toFloat() / total.toFloat() },
                                color = accent,
                                trackColor = accent.copy(alpha = 0.2f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                            )
                        }
                    } else {
                        val snippet = remember(note.id, note.content) {
                            note.content.take(180).ifBlank { "No content" }
                        }
                        Text(
                            text = snippet,
                            color = subColor,
                            fontSize = 12.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 16.sp
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val dateStr = remember(note.updatedAtMs) { NoteDateFormats.formatShort(note.updatedAtMs) }
                        Text(dateStr, color = subColor, fontSize = 10.sp)

                        if (note.status == NoteStatus.TRASH) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "Restore",
                                    color = accent,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { onRestore() }
                                )
                                Text(
                                    "Delete",
                                    color = Color(0xFFF85149),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { onDeleteForever() }
                                )
                            }
                        } else if (note.status == NoteStatus.ARCHIVED) {
                            Text(
                                "Unarchive",
                                color = accent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { onRestore() }
                            )
                        } else {
                            Text(
                                if (isStickyMounted) "Unfloat" else "Float",
                                color = accent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { onToggleSticky() }
                            )
                        }
                    }
                }
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(selectColor)
                        .border(1.5.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun NoteRowCard(
    note: NoteItem,
    isDark: Boolean,
    showDetails: Boolean,
    isSelected: Boolean = false,
    isDimmed: Boolean = false,
    isDragging: Boolean = false,
    isStickyMounted: Boolean,
    dragModifier: Modifier = Modifier,
    onClick: () -> Unit,
    onToggleSticky: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit
) {
    val nc = note.color
    val cardBg = Color(if (isDark) nc.darkBgHex else nc.paperBgHex)
    val accent = Color(nc.accentHex)
    val textColor = if (isDark) Color(0xFFF0F6FC) else Color(0xFF1F2328)
    val subColor = if (isDark) Color(0xFF9EA7B3) else Color(0xFF4B5563)
    val selectColor = if (isDark) Color(0xFF58A6FF) else Color(0xFF1D4ED8)
    val stripeColor = if (isSelected) selectColor else accent

    val dateStr = remember(note.updatedAtMs, showDetails) {
        if (showDetails) NoteDateFormats.formatLong(note.updatedAtMs) else NoteDateFormats.formatShort(note.updatedAtMs)
    }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 10.dp else 0.dp),
        border = BorderStroke(
            if (isSelected || isDragging) 2.5.dp else 1.dp,
            if (isSelected) selectColor else accent.copy(alpha = if (isDark) 0.35f else 0.65f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(if (showDetails) 112.dp else 76.dp)
            .alpha(if (isDimmed) 0.52f else 1f)
            .clip(RoundedCornerShape(10.dp))
            .then(dragModifier)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        color = stripeColor,
                        topLeft = Offset.Zero,
                        size = Size(7.dp.toPx(), size.height)
                    )
                }
                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 8.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = note.title.ifBlank { if (note.type == NoteType.CHECKLIST) "Checklist" else "Untitled Note" },
                        color = textColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (note.isPinned) Text("📌", fontSize = 12.sp)
                        if (note.isLocked) Text("🔒", fontSize = 12.sp)
                        if (note.reminderAtMs != null && !note.reminderTriggered) Text("⏰", fontSize = 12.sp)
                        if (isStickyMounted) Text("🪟", fontSize = 12.sp)
                        Text(dateStr, color = subColor, fontSize = 11.sp)
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(selectColor),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                val preview = remember(note.id, note.content, note.isLocked, note.type, note.checklist.size, showDetails) {
                    when {
                        note.isLocked -> "🔒 Locked note"
                        note.type == NoteType.CHECKLIST -> {
                            val done = note.checklist.count { it.isChecked }
                            val itemsPreview = note.checklist.take(if (showDetails) 3 else 2)
                                .joinToString("  •  ") { (if (it.isChecked) "☑ " else "☐ ") + it.text }
                            "($done/${note.checklist.size}) $itemsPreview"
                        }
                        else -> note.content.take(180).replace('\n', ' ').ifBlank { "No content" }
                    }
                }

                Text(
                    text = preview,
                    color = subColor,
                    fontSize = 12.sp,
                    maxLines = if (showDetails) 2 else 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.weight(1f))

                if (note.status == NoteStatus.TRASH) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Restore",
                            color = accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { onRestore() }
                        )
                        Text(
                            "Delete Forever",
                            color = Color(0xFFF85149),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { onDeleteForever() }
                        )
                    }
                } else if (note.status == NoteStatus.ARCHIVED) {
                    Text(
                        "Restore to Active",
                        color = accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onRestore() }
                    )
                } else if (showDetails) {
                    Text(
                        if (isStickyMounted) "Unpin Floating Window" else "Pin Floating Sticky Note",
                        color = accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onToggleSticky() }
                    )
                }
            }
        }
    }
}