package com.omni.plugin.browser.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.plugin.browser.models.BrowserProfile
import com.omni.plugin.browser.models.BrowserTab
import com.omni.plugin.browser.models.GroupedTabItem
import com.omni.plugin.browser.models.TabGroup
import com.omni.plugin.browser.models.PROFILE_PALETTE
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import com.omni.hub.api.HostBridge
import com.omni.hub.api.OmniLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabSwitcherScreen(
    bridge: HostBridge? = null,
    tabs: List<BrowserTab>,
    activeTabId: String,
    profiles: List<BrowserProfile>,
    selectedProfileId: String,
    tabGroups: List<TabGroup> = emptyList(),
    isSelectionMode: Boolean = false,
    selectedTabIds: Set<String> = emptySet(),
    showGroupNamingDialog: Boolean = false,
    onEnterSelectionMode: (String) -> Unit = {},
    onToggleTabSelection: (String) -> Unit = {},
    onSelectAllTabs: () -> Unit = {},
    onClearSelectionMode: () -> Unit = {},
    onPromptGroupSelection: () -> Unit = {},
    onConfirmCreateGroup: (String, Long) -> Unit = { _, _ -> },
    onDismissGroupNamingDialog: () -> Unit = {},
    onRestoreTabGroup: (String) -> Unit = {},
    onRestoreSingleGroupedTab: (String, GroupedTabItem) -> Unit = { _, _ -> },
    onDeleteTabGroup: (String) -> Unit = {},
    onSelectProfile: (String) -> Unit,
    onAddProfile: () -> Unit,
    onRenameProfile: (BrowserProfile) -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: (String) -> Unit,
    onCloseAll: () -> Unit,
    onCloseSwitcher: () -> Unit,
    onReorderTabs: (Int, Int) -> Unit = { _, _ -> },
    onSaveTabOrder: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var activeSegment by remember { mutableStateOf("tabs") }
    var showTabMenu by remember { mutableStateOf(false) }
    var profileMenuExpanded by remember { mutableStateOf(false) }
    val currentProfile = profiles.find { it.id == selectedProfileId } ?: profiles.firstOrNull() ?: BrowserProfile("default", "Account 1", 0xFF2979FF)

    val initialIndex = remember {
        val idx = tabs.indexOfFirst { it.id == activeTabId }
        if (idx >= 0) idx else (tabs.size - 1).coerceAtLeast(0)
    }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(
        initialFirstVisibleItemIndex = initialIndex
    )
    val reorderableLazyGridState = rememberReorderableLazyGridState(gridState) { from, to ->
        onReorderTabs(from.index, to.index)
    }
    val closingTabIds = remember { mutableStateListOf<String>() }

    // Focused Anomaly Sentry: Silent during normal scrolling; only logs if a swipe is truly swallowed!
    val tabsGridScrollConnection = remember(gridState) {
        object : NestedScrollConnection {
            var dragDeltaAccumulator = 0f
            var consumedDeltaAccumulator = 0f

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag) {
                    dragDeltaAccumulator += available.y
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag) {
                    consumedDeltaAccumulator += consumed.y
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // End of touch drag gesture: audit if user swiped significantly but grid refused to scroll
                if (Math.abs(dragDeltaAccumulator) > 40f && Math.abs(consumedDeltaAccumulator) < 2f) {
                    val canUp = gridState.canScrollBackward
                    val canDown = gridState.canScrollForward
                    val firstIdx = gridState.firstVisibleItemIndex
                    val firstOffset = gridState.firstVisibleItemScrollOffset
                    val tag = "TABS_SWALLOWED_DEBUG"
                    val msg = "🚨 [SWALLOWED_SWIPE] Dragged ${dragDeltaAccumulator.toInt()}px, consumed 0px! Pos: Item #$firstIdx (${firstOffset}px). Bounds: [canUp=$canUp, canDown=$canDown]"
                    bridge?.log(tag, msg) ?: OmniLogger.log(tag, msg)
                }
                dragDeltaAccumulator = 0f
                consumedDeltaAccumulator = 0f
                return Velocity.Zero
            }
        }
    }

    Column(
        modifier = modifier
            .background(Color(0xFF1F2227))
            .statusBarsPadding()
    ) {
        if (isSelectionMode) {
            Surface(
                color = Color(0xFF161B22),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClearSelectionMode) {
                            Icon(Icons.Default.Close, contentDescription = "Exit Selection", tint = Color.White)
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${selectedTabIds.size} Selected",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onSelectAllTabs) {
                            Text(
                                if (selectedTabIds.size == tabs.size) "Deselect" else "Select All",
                                color = Color(0xFF58A6FF),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        Box {
                            IconButton(onClick = { showTabMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Color.White)
                            }
                            DropdownMenu(
                                expanded = showTabMenu,
                                onDismissRequest = { showTabMenu = false },
                                modifier = Modifier.background(Color(0xFF282C34))
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (selectedTabIds.isNotEmpty()) "📦 Group Tabs (${selectedTabIds.size})" else "📦 Group Tabs",
                                            color = if (selectedTabIds.isNotEmpty()) Color(0xFF58A6FF) else Color(0xFF5F6368),
                                            fontWeight = FontWeight.Bold
                                        )
                                    },
                                    enabled = selectedTabIds.isNotEmpty(),
                                    onClick = {
                                        showTabMenu = false
                                        onPromptGroupSelection()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("New Tab", color = Color(0xFFE8EAED)) },
                                    onClick = {
                                        showTabMenu = false
                                        onNewTab(selectedProfileId)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Close All Tabs", color = Color(0xFFF28B82)) },
                                    onClick = {
                                        showTabMenu = false
                                        onCloseAll()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(Color(0xFF1F2227))
                    .padding(horizontal = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onNewTab(selectedProfileId) }) {
                            Icon(Icons.Default.Add, contentDescription = "New Tab", tint = Color(0xFFE8EAED))
                        }

                        Box {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(currentProfile.colorValue).copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, Color(currentProfile.colorValue).copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { profileMenuExpanded = true }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(currentProfile.colorValue))
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = currentProfile.name.take(10),
                                        color = Color(currentProfile.colorValue),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = profileMenuExpanded,
                                onDismissRequest = { profileMenuExpanded = false },
                                modifier = Modifier.background(Color(0xFF282C34))
                            ) {
                                Text(
                                    "Account Profiles",
                                    color = Color(0xFF9AA0A6),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                                HorizontalDivider(color = Color(0xFF3C4043))

                                profiles.forEach { prof ->
                                    val isSelected = prof.id == selectedProfileId
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(9.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(prof.colorValue))
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    prof.name,
                                                    color = if (isSelected) Color(prof.colorValue) else Color(0xFFE8EAED),
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    fontSize = 13.sp,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        },
                                        onClick = {
                                            onSelectProfile(prof.id)
                                            profileMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Segmented Tabs / Groups Switcher Pill
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFF161B22),
                        border = BorderStroke(1.dp, Color(0xFF30363D)),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(2.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(if (activeSegment == "tabs") Color(0xFF21262D) else Color.Transparent)
                                    .clickable { activeSegment = "tabs" }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "Tabs (${tabs.size})",
                                    color = if (activeSegment == "tabs") Color.White else Color(0xFF8B949E),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(if (activeSegment == "groups") Color(0xFF1F6FEB).copy(alpha = 0.35f) else Color.Transparent)
                                    .clickable { activeSegment = "groups" }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "📦 Groups (${tabGroups.size})",
                                    color = if (activeSegment == "groups") Color(0xFF58A6FF) else Color(0xFF8B949E),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onCloseSwitcher) {
                            Text("Done", color = Color(0xFF8AB4F8), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        Box {
                            IconButton(onClick = { showTabMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Tab Menu", tint = Color(0xFF9AA0A6))
                            }
                            DropdownMenu(
                                expanded = showTabMenu,
                                onDismissRequest = { showTabMenu = false },
                                modifier = Modifier.background(Color(0xFF282C34))
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Select Tabs", color = Color(0xFF58A6FF), fontWeight = FontWeight.Bold) },
                                    onClick = {
                                        showTabMenu = false
                                        val firstId = tabs.firstOrNull()?.id
                                        if (firstId != null) onEnterSelectionMode(firstId)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("New Tab", color = Color(0xFFE8EAED)) },
                                    onClick = {
                                        showTabMenu = false
                                        onNewTab(selectedProfileId)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Close All Tabs", color = Color(0xFFF28B82)) },
                                    onClick = {
                                        showTabMenu = false
                                        onCloseAll()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = Color(0xFF282C34))

        if (activeSegment == "groups") {
            TabGroupsListView(
                tabGroups = tabGroups,
                onRestoreGroup = onRestoreTabGroup,
                onRestoreSingleTab = onRestoreSingleGroupedTab,
                onDeleteGroup = onDeleteTabGroup,
                onSwitchToTabs = { activeSegment = "tabs" },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(tabsGridScrollConnection)
            ) {
                items(tabs, key = { it.id }) { tab ->
                    val isActive = tab.id == activeTabId
                    val isSelectedInMode = selectedTabIds.contains(tab.id)
                    val tabProfile = profiles.find { it.id == tab.profileId } ?: profiles.firstOrNull() ?: BrowserProfile("default", "Default", 0xFF2979FF)
                    val isClosing = closingTabIds.contains(tab.id)

                    val closeAlpha by animateFloatAsState(
                        targetValue = if (isClosing) 0f else 1f,
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        label = "closeAlpha"
                    )
                    val closeScale by animateFloatAsState(
                        targetValue = if (isClosing) 0.72f else 1f,
                        animationSpec = androidx.compose.animation.core.tween(durationMillis = 200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        label = "closeScale"
                    )

                    LaunchedEffect(isClosing) {
                        if (isClosing) {
                            kotlinx.coroutines.delay(200)
                            onCloseTab(tab.id)
                            closingTabIds.remove(tab.id)
                        }
                    }

                    ReorderableItem(reorderableLazyGridState, key = tab.id) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 16.dp else 0.dp)
                        val scale by animateFloatAsState(if (isDragging) 1.05f else 1f)

                        var wasDragging by remember { mutableStateOf(false) }
                        LaunchedEffect(isDragging) {
                            if (wasDragging && !isDragging) {
                                onSaveTabOrder()
                            }
                            wasDragging = isDragging
                        }

                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelectedInMode) Color(0xFF16253B) else Color(0xFF282C34)
                            ),
                            border = if (isSelectedInMode) {
                                BorderStroke(2.dp, Color(0xFF58A6FF))
                            } else if (isActive) {
                                BorderStroke(2.dp, Color(tabProfile.colorValue))
                            } else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .graphicsLayer {
                                    scaleX = scale * closeScale
                                    scaleY = scale * closeScale
                                    alpha = closeAlpha
                                    shadowElevation = elevation.toPx()
                                    shape = RoundedCornerShape(12.dp)
                                    clip = true
                                }
                                .longPressDraggableHandle(
                                    enabled = !isSelectionMode && !isClosing,
                                    onDragStarted = {
                                        bridge?.vibrate(25L)
                                    },
                                    onDragStopped = {
                                        onSaveTabOrder()
                                    }
                                )
                                .clickable(
                                    enabled = !isClosing,
                                    onClick = {
                                        logTrace("TABS_UI", "Card onClick for tab [${tab.id}] ('${tab.title}')")
                                        if (isSelectionMode) onToggleTabSelection(tab.id)
                                        else onSelectTab(tab.id)
                                    }
                                )
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (isSelectedInMode) Color(0xFF1F3A5F) else if (isActive) Color(0xFF333842) else Color(0xFF21252B))
                                        .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(tabProfile.colorValue))
                                    )
                                    Spacer(Modifier.width(6.dp))

                                    Text(
                                        text = if (tab.url == "about:blank" || tab.title == "about:blank") "New Tab" else tab.title.ifEmpty { "Web Page" },
                                        color = Color(0xFFE8EAED),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )

                                    if (!isSelectionMode) {
                                        IconButton(
                                            onClick = {
                                                if (!closingTabIds.contains(tab.id)) {
                                                    closingTabIds.add(tab.id)
                                                }
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Close Tab",
                                                tint = Color(0xFF9AA0A6),
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .background(Color(0xFF16181D)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val thumbBmp = remember(tab.thumbnail) {
                                        tab.thumbnail?.asImageBitmap()
                                    }
                                    if (thumbBmp != null && tab.url != "about:blank") {
                                        Image(
                                            bitmap = thumbBmp,
                                            contentDescription = tab.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else if (tab.url == "about:blank") {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color(0xFF1F2227))
                                                .padding(8.dp)
                                        ) {
                                            Surface(
                                                shape = CircleShape,
                                                color = Color(tabProfile.colorValue).copy(alpha = 0.2f),
                                                border = BorderStroke(1.dp, Color(tabProfile.colorValue).copy(alpha = 0.6f)),
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text("🌐", fontSize = 16.sp)
                                                }
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                text = "Speed Dial",
                                                color = Color(0xFFE8EAED),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    } else {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.padding(12.dp)
                                        ) {
                                            Text("📄", fontSize = 24.sp)
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                text = tab.url.replace("https://", "").replace("http://", "").take(25),
                                                color = Color(0xFF9AA0A6),
                                                fontSize = 10.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }

                                    // Selection Checkmark Badge
                                    if (isSelectionMode) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(if (isSelectedInMode) Color(0xFF1F6FEB).copy(alpha = 0.2f) else Color.Transparent)
                                                .padding(8.dp),
                                            contentAlignment = Alignment.TopEnd
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isSelectedInMode) Color(0xFF1F6FEB) else Color(0xFF161B22).copy(alpha = 0.8f))
                                                    .border(2.dp, if (isSelectedInMode) Color(0xFF58A6FF) else Color.White.copy(alpha = 0.6f), CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (isSelectedInMode) {
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
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
        }
    }

    if (showGroupNamingDialog) {
        GroupNamingDialog(
            tabCount = selectedTabIds.size,
            onConfirm = onConfirmCreateGroup,
            onDismiss = onDismissGroupNamingDialog
        )
    }
}

@Composable
fun GroupNamingDialog(
    tabCount: Int,
    onConfirm: (title: String, colorValue: Long) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(PROFILE_PALETTE[0]) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF282C34),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📦", fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Text("Group $tabCount Tabs", color = Color(0xFFE8EAED), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Tabs will be cryo-stashed into cold storage. WebViews are completely purged to free 100% of RAM.",
                    color = Color(0xFF9AA0A6),
                    fontSize = 11.sp
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Group Title (e.g. Work Research)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFE8EAED),
                        unfocusedTextColor = Color(0xFFE8EAED),
                        focusedBorderColor = Color(0xFF58A6FF),
                        unfocusedBorderColor = Color(0xFF3C4043)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Tag Color", color = Color(0xFF8AB4F8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    PROFILE_PALETTE.take(6).forEach { col ->
                        val isSel = col == selectedColor
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(col))
                                .border(if (isSel) 2.5.dp else 0.dp, Color.White, CircleShape)
                                .clickable { selectedColor = col }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(title, selectedColor) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB))
            ) {
                Text("Stash & Group", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF9AA0A6))
            }
        }
    )
}

@Composable
fun TabGroupsListView(
    tabGroups: List<TabGroup>,
    onRestoreGroup: (String) -> Unit,
    onRestoreSingleTab: (String, GroupedTabItem) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onSwitchToTabs: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (tabGroups.isEmpty()) {
        Box(
            modifier = modifier.padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📦", fontSize = 42.sp)
                Spacer(Modifier.height(12.dp))
                Text("No Stashed Tab Groups", color = Color(0xFFE8EAED), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Use the 3-dots menu and choose 'Select Tabs' to group them into 0-RAM cryostasis.",
                    color = Color(0xFF8B949E),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onSwitchToTabs,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                ) {
                    Text("Back to Tabs", color = Color(0xFF58A6FF))
                }
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tabGroups, key = { it.id }) { group ->
                var expanded by remember { mutableStateOf(false) }
                val dateStr = remember(group.createdAt) {
                    SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(group.createdAt))
                }

                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    border = BorderStroke(1.dp, Color(group.colorValue).copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f).clickable { expanded = !expanded }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(group.colorValue))
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            group.title,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = Color(group.colorValue).copy(alpha = 0.2f)
                                        ) {
                                            Text(
                                                "${group.tabs.size} tabs",
                                                color = Color(group.colorValue),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        "Stashed $dateStr • 0 RAM",
                                        color = Color(0xFF8B949E),
                                        fontSize = 10.sp
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = { onRestoreGroup(group.id) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("Restore All", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                IconButton(
                                    onClick = { onDeleteGroup(group.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete Group", tint = Color(0xFFF85149), modifier = Modifier.size(16.dp))
                                }

                                IconButton(
                                    onClick = { expanded = !expanded },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = Color(0xFF8B949E)
                                    )
                                }
                            }
                        }

                        if (expanded) {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = Color(0xFF21262D))
                            Spacer(Modifier.height(6.dp))

                            group.tabs.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            item.title,
                                            color = Color(0xFFC9D1D9),
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            item.url.take(45),
                                            color = Color(0xFF8B949E),
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    TextButton(
                                        onClick = { onRestoreSingleTab(group.id, item) },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        modifier = Modifier.height(26.dp)
                                    ) {
                                        Text("+ Restore", color = Color(0xFF58A6FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
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