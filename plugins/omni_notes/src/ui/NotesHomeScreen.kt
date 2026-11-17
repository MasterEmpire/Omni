package com.omni.plugin.notes.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.hub.api.HostBridge
import com.omni.plugin.notes.OmniNotesPlugin
import com.omni.plugin.notes.model.NoteColor
import com.omni.plugin.notes.model.NoteItem
import com.omni.plugin.notes.model.NoteSection
import com.omni.plugin.notes.model.NoteSortOrder
import com.omni.plugin.notes.model.NoteStatus
import com.omni.plugin.notes.model.NoteThemeMode
import com.omni.plugin.notes.model.NoteType
import com.omni.plugin.notes.model.NoteViewMode
import com.omni.plugin.notes.storage.NoteRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    DisposableEffect(drawerState.isOpen, isSearchOpen, showFabMenu, selectedColorFilter) {
        registerBackInterceptor {
            when {
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

    val filteredNotes = remember(
        NoteRepository.notes.toList(),
        selectedSection,
        selectedColorFilter,
        searchQuery,
        settings.sortOrder
    ) {
        val base = NoteRepository.notes.filter { note ->
            val sectionOk = when (selectedSection) {
                NoteSection.ALL -> note.status == NoteStatus.ACTIVE
                NoteSection.PINNED -> note.status == NoteStatus.ACTIVE && note.isPinned
                NoteSection.REMINDERS -> note.status != NoteStatus.TRASH && note.reminderAtMs != null
                NoteSection.ARCHIVE -> note.status == NoteStatus.ARCHIVED
                NoteSection.TRASH -> note.status == NoteStatus.TRASH
            }
            val colorOk = selectedColorFilter == null || note.colorId == selectedColorFilter
            val queryOk = if (searchQuery.isBlank()) true else {
                val q = searchQuery.trim()
                note.title.contains(q, ignoreCase = true) ||
                    (!note.isLocked && note.content.contains(q, ignoreCase = true)) ||
                    (!note.isLocked && note.checklist.any { it.text.contains(q, ignoreCase = true) })
            }
            sectionOk && colorOk && queryOk
        }

        val comparator = when (settings.sortOrder) {
            NoteSortOrder.MODIFIED_DESC -> compareByDescending<NoteItem> { it.isPinned }.thenByDescending { it.updatedAtMs }
            NoteSortOrder.CREATED_DESC -> compareByDescending<NoteItem> { it.isPinned }.thenByDescending { it.createdAtMs }
            NoteSortOrder.TITLE_ASC -> compareByDescending<NoteItem> { it.isPinned }.thenBy { it.title.lowercase(Locale.US) }
            NoteSortOrder.COLOR -> compareByDescending<NoteItem> { it.isPinned }.thenBy { it.color.ordinal }.thenByDescending { it.updatedAtMs }
        }
        base.sortedWith(comparator)
    }

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

                    // Theme Mode Switch (Dark vs Paper)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                val next = if (isDark) NoteThemeMode.PAPER else NoteThemeMode.DARK
                                NoteRepository.updateSettings(bridge, settings.copy(themeMode = next))
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Classic Paper Mode", color = primaryText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(if (isDark) "Currently Dark Slate" else "Bright pastel cards", color = mutedText, fontSize = 10.sp)
                        }
                        Switch(
                            checked = !isDark,
                            onCheckedChange = { paper ->
                                val next = if (paper) NoteThemeMode.PAPER else NoteThemeMode.DARK
                                NoteRepository.updateSettings(bridge, settings.copy(themeMode = next))
                            }
                        )
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
                                val path = NoteRepository.exportVaultBackup(bridge)
                                bridge.showToast(if (path != null) "Backed up ${NoteRepository.notes.size} notes!" else "Backup failed")
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text("💾 Save", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                val count = NoteRepository.importVaultBackup(bridge)
                                val msg = when {
                                    count > 0 -> "Restored $count notes from Vault!"
                                    count == 0 -> "All Vault notes already up to date"
                                    count == -1 -> "No Vault backup found yet"
                                    else -> "Restore failed"
                                }
                                bridge.showToast(msg)
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text("📥 Restore", fontSize = 11.sp)
                        }
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
                if (selectedSection == NoteSection.TRASH && filteredNotes.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${filteredNotes.size} deleted notes",
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

                if (filteredNotes.isEmpty()) {
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
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                items(filteredNotes, key = { it.id }) { note ->
                                    NoteGridCard(
                                        note = note,
                                        isDark = isDark,
                                        isStickyMounted = OmniNotesPlugin.isStickyMounted(note.id),
                                        onClick = { onOpenNote(note) },
                                        onTogglePin = { NoteRepository.togglePin(bridge, note.id) },
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
                        NoteViewMode.LIST, NoteViewMode.DETAILS -> {
                            val showDetails = settings.viewMode == NoteViewMode.DETAILS
                            LazyColumn(
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                items(filteredNotes, key = { it.id }) { note ->
                                    NoteRowCard(
                                        note = note,
                                        isDark = isDark,
                                        showDetails = showDetails,
                                        isStickyMounted = OmniNotesPlugin.isStickyMounted(note.id),
                                        onClick = { onOpenNote(note) },
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
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteGridCard(
    note: NoteItem,
    isDark: Boolean,
    isStickyMounted: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleSticky: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit
) {
    val nc = note.color
    val cardBg = Color(if (isDark) nc.darkBgHex else nc.paperBgHex)
    val accent = Color(nc.accentHex)
    val textColor = if (isDark) Color(0xFFF0F6FC) else Color(0xFF1F2328)
    val subColor = if (isDark) Color(0xFF9EA7B3) else Color(0xFF4B5563)

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, accent.copy(alpha = if (isDark) 0.45f else 0.7f)),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 135.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(accent)
            )
            Column(modifier = Modifier.padding(10.dp)) {
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
                    note.checklist.take(4).forEach { entry ->
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
                    Text(
                        text = note.content.ifBlank { "No content" },
                        color = subColor,
                        fontSize = 12.sp,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val dateStr = remember(note.updatedAtMs) {
                        SimpleDateFormat("MMM d", Locale.US).format(Date(note.updatedAtMs))
                    }
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }
    }
}

@Composable
private fun NoteRowCard(
    note: NoteItem,
    isDark: Boolean,
    showDetails: Boolean,
    isStickyMounted: Boolean,
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

    val dateStr = remember(note.updatedAtMs, showDetails) {
        val pattern = if (showDetails) "MMM d, yyyy • h:mm a" else "MMM d"
        SimpleDateFormat(pattern, Locale.US).format(Date(note.updatedAtMs))
    }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, accent.copy(alpha = if (isDark) 0.35f else 0.65f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            Box(
                modifier = Modifier
                    .width(7.dp)
                    .fillMaxHeight()
                    .background(accent)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
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
                    }
                }

                if (showDetails || note.type == NoteType.CHECKLIST || note.content.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    val preview = when {
                        note.isLocked -> "🔒 Locked note"
                        note.type == NoteType.CHECKLIST -> {
                            val done = note.checklist.count { it.isChecked }
                            val itemsPreview = note.checklist.take(if (showDetails) 4 else 2)
                                .joinToString("  •  ") { (if (it.isChecked) "☑ " else "☐ ") + it.text }
                            "($done/${note.checklist.size}) $itemsPreview"
                        }
                        else -> note.content.replace("\n", " ")
                    }
                    Text(
                        text = preview,
                        color = subColor,
                        fontSize = 12.sp,
                        maxLines = if (showDetails) 3 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (note.status == NoteStatus.TRASH) {
                    Spacer(Modifier.height(6.dp))
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
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Restore to Active",
                        color = accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onRestore() }
                    )
                } else if (showDetails) {
                    Spacer(Modifier.height(6.dp))
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