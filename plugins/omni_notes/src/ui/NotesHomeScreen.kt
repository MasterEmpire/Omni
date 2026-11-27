package com.omni.plugin.notes.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import com.omni.plugin.notes.model.DefaultCreateAction
import com.omni.plugin.notes.model.NoteColor
import com.omni.plugin.notes.model.NoteFolder
import com.omni.plugin.notes.model.NoteItem
import com.omni.plugin.notes.model.formatNoteByteSize
import com.omni.plugin.notes.model.NoteSection
import com.omni.plugin.notes.model.NoteSecurity
import com.omni.plugin.notes.model.NoteSortOrder
import com.omni.plugin.notes.model.NoteStatus
import com.omni.plugin.notes.model.NoteThemeMode
import com.omni.plugin.notes.model.NoteType
import com.omni.plugin.notes.model.NoteViewMode
import com.omni.plugin.notes.storage.NoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotesHomeScreen(
    context: Context,
    bridge: HostBridge,
    lazyGridState: LazyGridState,
    lazyListState: LazyListState,
    cachedNotes: List<NoteItem>,
    onCacheNotesChanged: (List<NoteItem>) -> Unit,
    onOpenSettings: () -> Unit,
    registerBackInterceptor: ((() -> Boolean)?) -> Unit,
    onOpenNote: (NoteItem) -> Unit,
    onCreateNote: (type: NoteType, colorId: String, folderId: String?) -> Unit
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
    var activeFolderId by remember { mutableStateOf<String?>(null) }
    var unfiledOnly by remember { mutableStateOf(false) }
    var selectedColorFilter by remember { mutableStateOf<String?>(null) }
    var isSearchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSortMenu by remember { mutableStateOf(false) }
    var showFabMenu by remember { mutableStateOf(false) }

    var selectedNoteIds by remember { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedNoteIds.isNotEmpty()
    var showBatchColorDialog by remember { mutableStateOf(false) }
    var showBatchMoveFolderDialog by remember { mutableStateOf(false) }
    var showFolderEditorDialog by remember { mutableStateOf(false) }
    var editingFolder by remember { mutableStateOf<NoteFolder?>(null) }
    var showMasterPinDialog by remember { mutableStateOf(false) }
    var pendingBatchLockIds by remember { mutableStateOf<Set<String>?>(null) }
    var showBatchShareDialog by remember { mutableStateOf(false) }

    DisposableEffect(drawerState.isOpen, isSearchOpen, showFabMenu, selectedColorFilter, isSelectionMode, activeFolderId, unfiledOnly, selectedSection) {
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
                activeFolderId != null -> {
                    activeFolderId = null
                    true
                }
                unfiledOnly -> {
                    unfiledOnly = false
                    true
                }
                selectedColorFilter != null -> {
                    selectedColorFilter = null
                    true
                }
                selectedSection != NoteSection.ALL -> {
                    selectedSection = NoteSection.ALL
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

    var displayNotes by remember { mutableStateOf(cachedNotes) }
    var displayFolders by remember { mutableStateOf<List<NoteFolder>>(emptyList()) }
    var folderCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var folderLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(
        NoteRepository.dataRevision,
        selectedSection,
        activeFolderId,
        unfiledOnly,
        selectedColorFilter,
        debouncedQuery,
        settings.sortOrder
    ) {
        val (computedNotes, computedFolders, countsMap, labelsMap) = withContext(Dispatchers.Default) {
            val snapshot = NoteRepository.snapshotNotes()
            val foldersSnap = NoteRepository.snapshotFolders()

            val activeCounts = HashMap<String, Int>()
            snapshot.forEach { n ->
                if (n.status == NoteStatus.ACTIVE && !n.folderId.isNullOrBlank()) {
                    activeCounts[n.folderId] = (activeCounts[n.folderId] ?: 0) + 1
                }
            }
            val labels = foldersSnap.associate { it.id to "${it.icon} ${it.name}" }

            val filteredFolders = foldersSnap
                .filter { f ->
                    val cOk = selectedColorFilter == null || f.colorId == selectedColorFilter
                    val qOk = debouncedQuery.isEmpty() || f.name.contains(debouncedQuery, ignoreCase = true)
                    cOk && qOk
                }
                .sortedByDescending { it.lastUsedAtMs }

            val base = if (selectedSection == NoteSection.FOLDERS) {
                emptyList()
            } else {
                snapshot.filter { note ->
                    val sectionOk = when (selectedSection) {
                        NoteSection.ALL -> {
                            note.status == NoteStatus.ACTIVE && when {
                                activeFolderId != null -> note.folderId == activeFolderId
                                unfiledOnly -> note.folderId == null
                                else -> true
                            }
                        }
                        NoteSection.FOLDERS -> false
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
            }

            val comparator = when (settings.sortOrder) {
                NoteSortOrder.CUSTOM -> compareByDescending<NoteItem> { it.isPinned }.thenBy { it.sortIndex }.thenByDescending { it.updatedAtMs }
                NoteSortOrder.MODIFIED_DESC -> compareByDescending<NoteItem> { it.isPinned }.thenByDescending { it.updatedAtMs }
                NoteSortOrder.CREATED_DESC -> compareByDescending<NoteItem> { it.isPinned }.thenByDescending { it.createdAtMs }
                NoteSortOrder.TITLE_ASC -> compareByDescending<NoteItem> { it.isPinned }.thenBy { it.title.lowercase(Locale.US) }
                NoteSortOrder.COLOR -> compareByDescending<NoteItem> { it.isPinned }.thenBy { it.color.ordinal }.thenByDescending { it.updatedAtMs }
            }

            Tuple4(base.sortedWith(comparator), filteredFolders, activeCounts, labels)
        }
        displayNotes = computedNotes
        onCacheNotesChanged(computedNotes)
        displayFolders = computedFolders
        folderCounts = countsMap
        folderLabels = labelsMap
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

                    val recentFolders = remember(NoteRepository.dataRevision) {
                        NoteRepository.folders.sortedByDescending { it.lastUsedAtMs }.take(5)
                    }
                    NoteSection.entries.forEach { section ->
                        val count = when (section) {
                            NoteSection.ALL -> NoteRepository.notes.count { it.status == NoteStatus.ACTIVE }
                            NoteSection.FOLDERS -> NoteRepository.folders.size
                            NoteSection.PINNED -> NoteRepository.notes.count { it.status == NoteStatus.ACTIVE && it.isPinned }
                            NoteSection.REMINDERS -> NoteRepository.notes.count { it.status != NoteStatus.TRASH && it.reminderAtMs != null }
                            NoteSection.ARCHIVE -> NoteRepository.notes.count { it.status == NoteStatus.ARCHIVED }
                            NoteSection.TRASH -> NoteRepository.notes.count { it.status == NoteStatus.TRASH }
                        }
                        val isSectionSelected = selectedSection == section && (section != NoteSection.ALL || activeFolderId == null)
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
                            selected = isSectionSelected,
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = Color(0xFFE5A93C).copy(alpha = 0.18f),
                                selectedTextColor = if (isDark) Color(0xFFE5A93C) else Color(0xFFB45309),
                                unselectedTextColor = primaryText
                            ),
                            onClick = {
                                selectedSection = section
                                activeFolderId = null
                                unfiledOnly = false
                                selectedNoteIds = emptySet()
                                scope.launch { drawerState.close() }
                            }
                        )

                        if (section == NoteSection.FOLDERS && recentFolders.isNotEmpty()) {
                            recentFolders.forEach { rf ->
                                val isRfSel = selectedSection == NoteSection.ALL && activeFolderId == rf.id
                                val rfCount = folderCounts[rf.id] ?: 0
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 24.dp, end = 6.dp, top = 2.dp, bottom = 2.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isRfSel) Color(rf.color.accentHex).copy(alpha = 0.2f) else Color.Transparent)
                                        .clickable {
                                            selectedSection = NoteSection.ALL
                                            activeFolderId = rf.id
                                            unfiledOnly = false
                                            selectedNoteIds = emptySet()
                                            NoteRepository.touchFolderUsed(bridge, rf.id)
                                            scope.launch { drawerState.close() }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(Color(rf.color.accentHex))
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "${rf.icon} ${rf.name}",
                                            color = if (isRfSel) Color(rf.color.accentHex) else primaryText,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text("$rfCount", fontSize = 11.sp, color = mutedText)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = mutedText.copy(alpha = 0.2f))
                    Spacer(Modifier.height(12.dp))

                    Spacer(Modifier.weight(1f))
                    HorizontalDivider(color = mutedText.copy(alpha = 0.2f))
                    Spacer(Modifier.height(8.dp))

                    val vaultOk = remember { NoteRepository.hasExternalVaultPermission() }
                    NavigationDrawerItem(
                        icon = { Text("⚙️", fontSize = 17.sp) },
                        label = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Settings", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("Theme, Paper, Markdown, Backup", fontSize = 10.sp, color = mutedText)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (vaultOk) Color(0xFF3FB950) else Color(0xFFF85149))
                                )
                            }
                        },
                        selected = false,
                        colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = primaryText),
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenSettings()
                        }
                    )
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
                            val activeFolder = activeFolderId?.let { id -> NoteRepository.folders.find { it.id == id } }
                            val titleText = when {
                                activeFolder != null -> "${activeFolder.icon} ${activeFolder.name}"
                                unfiledOnly && selectedSection == NoteSection.ALL -> "📄 Unfiled Notes"
                                else -> "${selectedSection.icon} ${selectedSection.label}"
                            }
                            Text(
                                titleText,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = primaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                        AnimatedVisibility(visible = showFabMenu && selectedSection != NoteSection.FOLDERS) {
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ExtendedFloatingActionButton(
                                    onClick = {
                                        showFabMenu = false
                                        onCreateNote(NoteType.CHECKLIST, selectedColorFilter ?: NoteColor.BLUE.id, activeFolderId)
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
                                        onCreateNote(NoteType.TEXT, selectedColorFilter ?: NoteColor.YELLOW.id, activeFolderId)
                                    },
                                    containerColor = surfaceColor,
                                    contentColor = primaryText,
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text("📄  Text Note", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }

                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFE5A93C),
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    onClick = {
                                        if (selectedSection == NoteSection.FOLDERS) {
                                            editingFolder = null
                                            showFolderEditorDialog = true
                                        } else {
                                            when (settings.defaultCreateAction) {
                                                DefaultCreateAction.TEXT -> onCreateNote(NoteType.TEXT, selectedColorFilter ?: NoteColor.YELLOW.id, activeFolderId)
                                                DefaultCreateAction.CHECKLIST -> onCreateNote(NoteType.CHECKLIST, selectedColorFilter ?: NoteColor.BLUE.id, activeFolderId)
                                                DefaultCreateAction.ASK -> showFabMenu = !showFabMenu
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (selectedSection != NoteSection.FOLDERS) {
                                            bridge.vibrate(25L)
                                            showFabMenu = !showFabMenu
                                        }
                                    }
                                )
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (showFabMenu && selectedSection != NoteSection.FOLDERS) Icons.Default.Close else Icons.Default.Add,
                                    contentDescription = if (selectedSection == NoteSection.FOLDERS) "New Folder" else "New Note",
                                    tint = Color.Black
                                )
                            }
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
                                        onClick = { showBatchMoveFolderDialog = true },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("📁 Move", fontSize = 12.sp)
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
                                            val unlocked = selectedNotes.filter { !it.isLocked }
                                            if (unlocked.isEmpty()) {
                                                bridge.showToast("🔒 Locked notes cannot be batch-shared")
                                            } else {
                                                showBatchShareDialog = true
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("📤 Share", fontSize = 12.sp)
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
                // Color & Folder Quick Filter Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedSection == NoteSection.ALL) {
                        if (activeFolderId != null) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFF58A6FF).copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, Color(0xFF58A6FF)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { activeFolderId = null }
                            ) {
                                Text(
                                    "← All Notes",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF58A6FF),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (unfiledOnly) Color(0xFF58A6FF) else surfaceColor,
                                border = BorderStroke(1.dp, mutedText.copy(alpha = 0.25f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { unfiledOnly = !unfiledOnly }
                            ) {
                                Text(
                                    "Unfiled",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (unfiledOnly) Color.White else primaryText,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (selectedColorFilter == null) Color(0xFFE5A93C) else surfaceColor,
                        border = BorderStroke(1.dp, mutedText.copy(alpha = 0.25f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { selectedColorFilter = null }
                    ) {
                        Text(
                            "All Colors",
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

                if (selectedSection == NoteSection.FOLDERS) {
                    if (displayFolders.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("📁", fontSize = 40.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    if (searchQuery.isNotBlank()) "No matching folders" else "No folders yet",
                                    color = primaryText,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("Tap + to create your first folder", color = mutedText, fontSize = 12.sp)
                            }
                        }
                    } else if (settings.viewMode == NoteViewMode.GRID) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(displayFolders, key = { it.id }) { folder ->
                                FolderGridCard(
                                    folder = folder,
                                    noteCount = folderCounts[folder.id] ?: 0,
                                    isDark = isDark,
                                    onClick = {
                                        selectedSection = NoteSection.ALL
                                        activeFolderId = folder.id
                                        unfiledOnly = false
                                        NoteRepository.touchFolderUsed(bridge, folder.id)
                                    },
                                    onEdit = {
                                        editingFolder = folder
                                        showFolderEditorDialog = true
                                    }
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(displayFolders, key = { it.id }) { folder ->
                                FolderRowCard(
                                    folder = folder,
                                    noteCount = folderCounts[folder.id] ?: 0,
                                    isDark = isDark,
                                    onClick = {
                                        selectedSection = NoteSection.ALL
                                        activeFolderId = folder.id
                                        unfiledOnly = false
                                        NoteRepository.touchFolderUsed(bridge, folder.id)
                                    },
                                    onEdit = {
                                        editingFolder = folder
                                        showFolderEditorDialog = true
                                    }
                                )
                            }
                        }
                    }
                } else if (displayNotes.isEmpty()) {
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
                                                folderLabel = if (activeFolderId == null) note.folderId?.let { folderLabels[it] } else null,
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
                                    isListScrolling = lazyGridState.isScrollInProgress,
                                    accentColor = Color(0xFFE5A93C),
                                    surfaceColor = surfaceColor,
                                    textColor = primaryText,
                                    getPreviewLabel = { idx ->
                                        val item = displayNotes.getOrNull(idx)
                                        if (item != null) "${NoteDateFormats.formatShort(item.updatedAtMs)} • ${idx + 1}/${displayNotes.size}" else "${idx + 1}"
                                    },
                                    onScrollToIndex = { idx ->
                                        lazyGridState.scrollToItem(idx)
                                    },
                                    modifier = Modifier.align(Alignment.CenterEnd)
                                )
                            }
                        }
                        NoteViewMode.LIST, NoteViewMode.DETAILS -> {
                            val showDetails = settings.viewMode == NoteViewMode.DETAILS
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
                                                folderLabel = if (activeFolderId == null) note.folderId?.let { folderLabels[it] } else null,
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
                                    isListScrolling = lazyListState.isScrollInProgress,
                                    accentColor = Color(0xFFE5A93C),
                                    surfaceColor = surfaceColor,
                                    textColor = primaryText,
                                    getPreviewLabel = { idx ->
                                        val item = displayNotes.getOrNull(idx)
                                        if (item != null) "${NoteDateFormats.formatShort(item.updatedAtMs)} • ${idx + 1}/${displayNotes.size}" else "${idx + 1}"
                                    },
                                    onScrollToIndex = { idx ->
                                        lazyListState.scrollToItem(idx)
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



    if (showBatchMoveFolderDialog) {
        val allFolders = remember(NoteRepository.dataRevision) {
            NoteRepository.folders.sortedByDescending { it.lastUsedAtMs }
        }
        AlertDialog(
            onDismissRequest = { showBatchMoveFolderDialog = false },
            containerColor = surfaceColor,
            title = { Text("📁 Move to Folder", color = primaryText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                NoteRepository.batchMoveToFolder(bridge, selectedNoteIds, null)
                                selectedNoteIds = emptySet()
                                showBatchMoveFolderDialog = false
                                bridge.showToast("Moved to Unfiled")
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📄 Unfiled (No Folder)", color = primaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    allFolders.forEach { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(folder.color.accentHex).copy(alpha = 0.14f))
                                .clickable {
                                    NoteRepository.batchMoveToFolder(bridge, selectedNoteIds, folder.id)
                                    selectedNoteIds = emptySet()
                                    showBatchMoveFolderDialog = false
                                    bridge.showToast("Moved to ${folder.name}")
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${folder.icon} ${folder.name}", color = primaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("${folderCounts[folder.id] ?: 0}", color = mutedText, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatchMoveFolderDialog = false
                    editingFolder = null
                    showFolderEditorDialog = true
                }) {
                    Text("+ New Folder", color = Color(0xFFE5A93C), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchMoveFolderDialog = false }) {
                    Text("Cancel", color = mutedText)
                }
            }
        )
    }

    if (showFolderEditorDialog) {
        val targetFolder = editingFolder
        var folderName by remember(targetFolder?.id) { mutableStateOf(targetFolder?.name ?: "") }
        var folderColorId by remember(targetFolder?.id) { mutableStateOf(targetFolder?.colorId ?: NoteColor.BLUE.id) }
        var folderIcon by remember(targetFolder?.id) { mutableStateOf(targetFolder?.icon ?: "📁") }
        val iconOptions = listOf("📁", "📚", "💼", "💡", "🏠", "🛒", "🎯", "❤️")

        AlertDialog(
            onDismissRequest = { showFolderEditorDialog = false },
            containerColor = surfaceColor,
            title = {
                Text(
                    if (targetFolder == null) "New Folder" else "Edit Folder",
                    color = primaryText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        label = { Text("Folder Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = primaryText, unfocusedTextColor = primaryText)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        iconOptions.forEach { ic ->
                            val isSel = folderIcon == ic
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) Color(0xFFE5A93C).copy(alpha = 0.25f) else Color.Transparent,
                                border = BorderStroke(1.dp, if (isSel) Color(0xFFE5A93C) else mutedText.copy(alpha = 0.25f)),
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { folderIcon = ic }
                            ) {
                                Text(ic, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NoteColor.entries.forEach { nc ->
                            val isSel = folderColorId == nc.id
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color(nc.accentHex))
                                    .border(if (isSel) 2.5.dp else 0.dp, primaryText, CircleShape)
                                    .clickable { folderColorId = nc.id }
                            )
                        }
                    }
                    if (targetFolder != null) {
                        TextButton(
                            onClick = {
                                if (activeFolderId == targetFolder.id) activeFolderId = null
                                NoteRepository.deleteFolder(bridge, targetFolder.id)
                                showFolderEditorDialog = false
                                bridge.showToast("Folder deleted (notes kept in All Notes)")
                            }
                        ) {
                            Text("🗑️ Delete Folder", color = Color(0xFFF85149), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = folderName.trim()
                        if (trimmed.isNotEmpty()) {
                            val newId = targetFolder?.id ?: "fld_${System.currentTimeMillis()}"
                            val savedFolder = NoteFolder(
                                id = newId,
                                name = trimmed,
                                colorId = folderColorId,
                                icon = folderIcon,
                                lastUsedAtMs = System.currentTimeMillis(),
                                createdAtMs = targetFolder?.createdAtMs ?: System.currentTimeMillis()
                            )
                            NoteRepository.upsertFolder(bridge, savedFolder)
                            if (selectedNoteIds.isNotEmpty() && targetFolder == null) {
                                NoteRepository.batchMoveToFolder(bridge, selectedNoteIds, savedFolder.id)
                                selectedNoteIds = emptySet()
                            }
                            showFolderEditorDialog = false
                        }
                    },
                    enabled = folderName.isNotBlank()
                ) {
                    Text(if (targetFolder == null) "Create" else "Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFolderEditorDialog = false }) {
                    Text("Cancel", color = mutedText)
                }
            }
        )
    }

    if (showBatchShareDialog) {
        val selectedList = displayNotes.filter { selectedNoteIds.contains(it.id) }
        val unlockedToShare = selectedList.filter { !it.isLocked }
        val lockedSkipped = selectedList.size - unlockedToShare.size

        fun exportAndShare(asZip: Boolean) {
            showBatchShareDialog = false
            selectedNoteIds = emptySet()
            scope.launch {
                try {
                    val (outFile, mimeType) = withContext(Dispatchers.IO) {
                        val shareDir = File(context.cacheDir, "shared_notes").apply { mkdirs() }
                        val cutoff = System.currentTimeMillis() - 3_600_000L
                        shareDir.listFiles()?.forEach { old ->
                            if (old.isFile && old.lastModified() < cutoff) old.delete()
                        }

                        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

                        fun formatNoteForFile(n: NoteItem): String {
                            val mainBody = if (n.type == NoteType.CHECKLIST) {
                                n.checklist.joinToString("\n") { "${if (it.isChecked) "[x]" else "[ ]"} ${it.text}" }
                            } else if (n.marginContent.isNotBlank()) {
                                "${n.content}\n\n[Margin Notes]\n${n.marginContent}"
                            } else {
                                n.content
                            }
                            return if (n.title.isNotBlank()) "${n.title}\n\n${mainBody.trim()}" else mainBody.trim()
                        }

                        if (asZip) {
                            val zipFile = File(shareDir, "OmniNotes_${unlockedToShare.size}notes_$ts.zip")
                            val usedNames = mutableSetOf<String>()
                            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                                unlockedToShare.forEachIndexed { idx, note ->
                                    val baseRaw = note.title.trim()
                                        .ifBlank { "Note_${idx + 1}" }
                                        .replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_")
                                        .take(48)
                                        .trimEnd('.', ' ')
                                        .ifBlank { "Note_${idx + 1}" }
                                    var entryName = "$baseRaw.txt"
                                    var dup = 2
                                    while (!usedNames.add(entryName.lowercase(Locale.US))) {
                                        entryName = "${baseRaw}_$dup.txt"
                                        dup++
                                    }
                                    zos.putNextEntry(ZipEntry(entryName))
                                    zos.write(formatNoteForFile(note).toByteArray(Charsets.UTF_8))
                                    zos.closeEntry()
                                }
                            }
                            Pair(zipFile, "application/zip")
                        } else {
                            val txtFile = File(shareDir, "OmniNotes_Dump_$ts.txt")
                            val combined = buildString {
                                unlockedToShare.forEachIndexed { idx, note ->
                                    val heading = note.title.trim().ifBlank { "Untitled ${idx + 1}" }
                                    appendLine("==========================================")
                                    appendLine("[${idx + 1}/${unlockedToShare.size}] $heading")
                                    appendLine("==========================================")
                                    val body = if (note.type == NoteType.CHECKLIST) {
                                        note.checklist.joinToString("\n") { "${if (it.isChecked) "[x]" else "[ ]"} ${it.text}" }
                                    } else if (note.marginContent.isNotBlank()) {
                                        "${note.content}\n\n[Margin Notes]\n${note.marginContent}"
                                    } else {
                                        note.content
                                    }
                                    appendLine(body.trim())
                                    if (idx < unlockedToShare.lastIndex) {
                                        appendLine()
                                        appendLine()
                                    }
                                }
                            }
                            txtFile.writeText(combined, Charsets.UTF_8)
                            Pair(txtFile, "text/plain")
                        }
                    }

                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, outFile.name)
                        clipData = android.content.ClipData.newRawUri(outFile.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(sendIntent, "Share ${unlockedToShare.size} Notes").apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(chooser)
                } catch (e: Exception) {
                    bridge.showToast("Share failed: ${e.message}")
                }
            }
        }

        AlertDialog(
            onDismissRequest = { showBatchShareDialog = false },
            containerColor = surfaceColor,
            title = {
                Text(
                    "📤 Share ${unlockedToShare.size} Note${if (unlockedToShare.size == 1) "" else "s"}",
                    color = primaryText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (lockedSkipped > 0) {
                        Text(
                            "🔒 $lockedSkipped locked note(s) will be excluded from export.",
                            color = Color(0xFFE5A93C),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF58A6FF).copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, Color(0xFF58A6FF).copy(alpha = 0.55f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { exportAndShare(asZip = true) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("📦 Share as ZIP Archive (.zip)", color = Color(0xFF58A6FF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Packs each note as its own .txt file inside a single ZIP",
                                color = mutedText,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF3FB950).copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, Color(0xFF3FB950).copy(alpha = 0.55f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { exportAndShare(asZip = false) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("📄 Dump All to Single Text File (.txt)", color = Color(0xFF3FB950), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Combines all selected notes into one continuous .txt document",
                                color = mutedText,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBatchShareDialog = false }) {
                    Text("Cancel", color = mutedText)
                }
            }
        )
    }
}

private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
private fun FolderGridCard(
    folder: NoteFolder,
    noteCount: Int,
    isDark: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit
) {
    val nc = folder.color
    val cardBg = Color(if (isDark) nc.darkBgHex else nc.paperBgHex)
    val accent = Color(nc.accentHex)
    val textColor = if (isDark) Color(0xFFF0F6FC) else Color(0xFF1F2328)
    val subColor = if (isDark) Color(0xFF9EA7B3) else Color(0xFF4B5563)

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, accent.copy(alpha = if (isDark) 0.5f else 0.75f)),
        modifier = Modifier
            .fillMaxWidth()
            .height(156.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(accent)
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(folder.icon, fontSize = 26.sp)
                    Text(
                        "Edit",
                        color = accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onEdit() }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    text = folder.name,
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "$noteCount ${if (noteCount == 1) "note" else "notes"}",
                        color = accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        NoteDateFormats.formatShort(folder.lastUsedAtMs),
                        color = subColor,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderRowCard(
    folder: NoteFolder,
    noteCount: Int,
    isDark: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit
) {
    val nc = folder.color
    val cardBg = Color(if (isDark) nc.darkBgHex else nc.paperBgHex)
    val accent = Color(nc.accentHex)
    val textColor = if (isDark) Color(0xFFF0F6FC) else Color(0xFF1F2328)
    val subColor = if (isDark) Color(0xFF9EA7B3) else Color(0xFF4B5563)

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, accent.copy(alpha = if (isDark) 0.4f else 0.7f)),
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        color = accent,
                        topLeft = Offset.Zero,
                        size = Size(7.dp.toPx(), size.height)
                    )
                }
                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(folder.icon, fontSize = 24.sp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = folder.name,
                            color = textColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "$noteCount ${if (noteCount == 1) "note" else "notes"} • ${NoteDateFormats.formatShort(folder.lastUsedAtMs)}",
                            color = subColor,
                            fontSize = 11.sp
                        )
                    }
                }
                Text(
                    "Edit",
                    color = accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onEdit() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
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
    isListScrolling: Boolean,
    accentColor: Color,
    surfaceColor: Color,
    textColor: Color,
    getPreviewLabel: (Int) -> String,
    onScrollToIndex: suspend (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (totalItems <= visibleItems || totalItems <= 12) return

    val density = LocalDensity.current
    var trackHeightPx by remember { mutableIntStateOf(1) }
    var isDragging by remember { mutableStateOf(false) }
    var showBubble by remember { mutableStateOf(false) }
    var showThumb by remember { mutableStateOf(true) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var previewIndex by remember { mutableIntStateOf(0) }

    val scrollChannel = remember { Channel<Int>(Channel.CONFLATED) }
    LaunchedEffect(scrollChannel) {
        var lastIdx = -1
        for (targetIdx in scrollChannel) {
            if (targetIdx != lastIdx) {
                lastIdx = targetIdx
                onScrollToIndex(targetIdx)
            }
            delay(16L)
        }
    }

    LaunchedEffect(isListScrolling, isDragging) {
        if (isListScrolling && !isDragging) {
            showBubble = false
            showThumb = true
        }
    }

    LaunchedEffect(isDragging, isListScrolling, firstVisibleIndex) {
        if (isDragging) {
            showThumb = true
            showBubble = true
        } else {
            if (showBubble) {
                delay(750L)
                showBubble = false
            }
            if (!isListScrolling && showThumb) {
                delay(1400L)
                showThumb = false
            }
        }
    }

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
        if (showBubble) {
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
                .width(26.dp)
                .fillMaxHeight()
                .pointerInput(totalItems, maxFirstIndex, usableHeightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        isDragging = true
                        showThumb = true
                        showBubble = true
                        try {
                            var frac = ((down.position.y - thumbHeightPx / 2f) / usableHeightPx).coerceIn(0f, 1f)
                            dragFraction = frac
                            var target = (frac * maxFirstIndex).roundToInt().coerceIn(0, totalItems - 1)
                            previewIndex = target
                            scrollChannel.trySend(target)

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                frac = ((change.position.y - thumbHeightPx / 2f) / usableHeightPx).coerceIn(0f, 1f)
                                dragFraction = frac
                                target = (frac * maxFirstIndex).roundToInt().coerceIn(0, totalItems - 1)
                                previewIndex = target
                                scrollChannel.trySend(target)
                            }
                        } finally {
                            isDragging = false
                        }
                    }
                }
        ) {
            if (showThumb || isDragging) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 3.dp)
                        .offset { IntOffset(0, thumbOffsetY) }
                        .width(if (isDragging) 8.dp else 5.dp)
                        .height(46.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isDragging) accentColor else accentColor.copy(alpha = 0.65f))
                )
            }
        }
    }
}

@Composable
private fun NoteGridCard(
    note: NoteItem,
    folderLabel: String? = null,
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
                        val sizeStr = remember(note.id, note.updatedAtMs) { formatNoteByteSize(note) }
                        Text(
                            if (folderLabel != null) "$dateStr • $sizeStr • $folderLabel" else "$dateStr • $sizeStr",
                            color = subColor,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

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
    folderLabel: String? = null,
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
                        val sizeStr = remember(note.id, note.updatedAtMs) { formatNoteByteSize(note) }
                        Text(
                            if (folderLabel != null) "$folderLabel • $dateStr • $sizeStr" else "$dateStr • $sizeStr",
                            color = subColor,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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