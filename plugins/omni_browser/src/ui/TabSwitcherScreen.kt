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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.omni.plugin.browser.utils.getAnalyticsTodayKey
import com.omni.plugin.browser.utils.getLastNDaysKeys
import com.omni.plugin.browser.utils.getNextPacificMidnightMs
import com.omni.plugin.browser.utils.isQuotaCurrentlyActive
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
    onEnterSelectionMode: (String?) -> Unit = {},
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
    onDeleteProfile: (BrowserProfile) -> Unit = {},
    aiAnalytics: Map<String, com.omni.plugin.browser.models.ProfileAiAnalytics> = emptyMap(),
    onClearQuotaHit: (String) -> Unit = {},
    onClearVerificationLock: (String) -> Unit = {},
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: (String) -> Unit,
    onCloseAll: () -> Unit,
    onCloseSwitcher: () -> Unit,
    onToggleFavorite: (String) -> Unit = {},
    onFavoriteSelectedTabs: () -> Unit = {},
    onReorderTabs: (Int, Int) -> Unit = { _, _ -> },
    onSaveTabOrder: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var activeSegment by remember { mutableStateOf("tabs") }
    var showFavoritesOnly by remember { mutableStateOf(false) }
    var showTabMenu by remember { mutableStateOf(false) }
    var profileMenuExpanded by remember { mutableStateOf(false) }
    var profileToDelete by remember { mutableStateOf<BrowserProfile?>(null) }
    var showHealthDeckDialog by remember { mutableStateOf(false) }
    val currentProfile = profiles.find { it.id == selectedProfileId } ?: profiles.firstOrNull() ?: BrowserProfile("default", "Account 1", 0xFF2979FF)
    val favCount = remember(tabs) { tabs.count { it.isFavorite } }
    val displayedTabs = remember(tabs, showFavoritesOnly) {
        if (showFavoritesOnly) tabs.filter { it.isFavorite } else tabs
    }

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

    fun logTrace(tag: String, msg: String) {
        bridge?.log(tag, msg) ?: OmniLogger.log(tag, msg)
    }

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
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
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
                                    text = {
                                        Text(
                                            if (selectedTabIds.isNotEmpty()) "⭐ Favorite Selected (${selectedTabIds.size})" else "⭐ Favorite Selected",
                                            color = if (selectedTabIds.isNotEmpty()) Color(0xFFFFD600) else Color(0xFF5F6368),
                                            fontWeight = FontWeight.Bold
                                        )
                                    },
                                    enabled = selectedTabIds.isNotEmpty(),
                                    onClick = {
                                        showTabMenu = false
                                        onFavoriteSelectedTabs()
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
                                    .combinedClickable(
                                        onClick = { profileMenuExpanded = true },
                                        onLongClick = {
                                            bridge?.vibrate(30L)
                                            onRenameProfile(currentProfile)
                                        }
                                    )
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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Account Profiles", color = Color(0xFF9AA0A6), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    TextButton(
                        onClick = {
                            profileMenuExpanded = false
                            showHealthDeckDialog = true
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text("📊 Health Deck", color = Color(0xFF58A6FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
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
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        prof.name,
                                                        color = if (isSelected) Color(prof.colorValue) else Color(0xFFE8EAED),
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                        fontSize = 13.sp
                                                    )
                                                    val aData = aiAnalytics[prof.id]
                                                    val now = System.currentTimeMillis()
                                                    val today = getAnalyticsTodayKey(now)
                                                    val qTime = aData?.lastQuotaHitTimestamp ?: 0L
                                                    val isVerifyLocked = aData?.needsVerification ?: false
                                                    val isQuotaActive = isQuotaCurrentlyActive(qTime, now)
                                                    val todayCount = aData?.effectivePromptsToday(today) ?: 0
                                                    val totalCount = aData?.totalPromptsLifetime ?: 0
                                                    val healthText = when {
                                                        isVerifyLocked -> "🔒 Verify Required • $todayCount today ($totalCount total)"
                                                        isQuotaActive -> {
                                                            val diffH = ((now - qTime) / 3_600_000L).coerceAtLeast(0)
                                                            val agoStr = if (diffH == 0L) {
                                                                "${((now - qTime) / 60_000L).coerceAtLeast(1)}m"
                                                            } else {
                                                                "${diffH}h"
                                                            }
                                                            "🔴 Quota Hit ($agoStr ago) • $todayCount today ($totalCount total)"
                                                        }
                                                        todayCount >= 40 -> "🟡 Busy • $todayCount today ($totalCount total)"
                                                        todayCount > 0 -> "🟢 Fresh • $todayCount today ($totalCount total)"
                                                        else -> "🟢 Ready • 0 today" + if (totalCount > 0) " ($totalCount total)" else ""
                                                    }
                                                    val healthColor = when {
                                                        isVerifyLocked -> Color(0xFFFF6D00)
                                                        isQuotaActive -> Color(0xFFF85149)
                                                        todayCount >= 40 -> Color(0xFFD29922)
                                                        else -> Color(0xFF3FB950)
                                                    }
                                                    Text(
                                                        text = healthText,
                                                        color = healthColor,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            onSelectProfile(prof.id)
                                            profileMenuExpanded = false
                                        },
                                        trailingIcon = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                IconButton(
                                                    onClick = {
                                                        profileMenuExpanded = false
                                                        onRenameProfile(prof)
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Text("✏️", fontSize = 12.sp)
                                                }

                                                if (prof.id != "default" && profiles.size > 1) {
                                                    Spacer(Modifier.width(4.dp))
                                                    IconButton(
                                                        onClick = {
                                                            profileMenuExpanded = false
                                                            profileToDelete = prof
                                                        },
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Delete,
                                                            contentDescription = "Delete Profile",
                                                            tint = Color(0xFFF28B82),
                                                            modifier = Modifier.size(15.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }

                                HorizontalDivider(color = Color(0xFF3C4043))

                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF8AB4F8), modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("Add Profile", color = Color(0xFF8AB4F8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    onClick = {
                                        profileMenuExpanded = false
                                        onAddProfile()
                                    }
                                )
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
                                    .background(if (activeSegment == "tabs" && !showFavoritesOnly) Color(0xFF21262D) else Color.Transparent)
                                    .clickable {
                                        activeSegment = "tabs"
                                        showFavoritesOnly = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "Tabs (${tabs.size})",
                                    color = if (activeSegment == "tabs" && !showFavoritesOnly) Color.White else Color(0xFF8B949E),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(if (activeSegment == "groups") Color(0xFF1F6FEB).copy(alpha = 0.35f) else Color.Transparent)
                                    .clickable {
                                        activeSegment = "groups"
                                        showFavoritesOnly = false
                                    }
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
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (showFavoritesOnly) Color(0xFFFFD600).copy(alpha = 0.22f) else Color(0xFF161B22),
                            border = BorderStroke(
                                1.dp,
                                if (showFavoritesOnly) Color(0xFFFFD600) else Color(0xFF30363D)
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    activeSegment = "tabs"
                                    showFavoritesOnly = !showFavoritesOnly
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text("⭐", fontSize = 11.sp)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (favCount > 0) "Fav ($favCount)" else "Fav",
                                    color = if (showFavoritesOnly) Color(0xFFFFD600) else Color(0xFFE8EAED),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        val activeIsFav = tabs.find { it.id == activeTabId }?.isFavorite == true
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
                                    text = {
                                        Text(
                                            if (activeIsFav) "☆ Unfavorite Current Tab" else "⭐ Favorite Current Tab",
                                            color = Color(0xFFFFD600),
                                            fontWeight = FontWeight.Bold
                                        )
                                    },
                                    onClick = {
                                        showTabMenu = false
                                        onToggleFavorite(activeTabId)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Select Tabs", color = Color(0xFF58A6FF), fontWeight = FontWeight.Bold) },
                                    onClick = {
                                        showTabMenu = false
                                        onEnterSelectionMode(null)
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
                                    text = { Text("Done (Exit Switcher)", color = Color(0xFF8AB4F8)) },
                                    onClick = {
                                        showTabMenu = false
                                        onCloseSwitcher()
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
        } else if (showFavoritesOnly && displayedTabs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⭐", fontSize = 42.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("No Favorite Tabs Yet", color = Color(0xFFE8EAED), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tap the ★ icon on any tab card or use the ⋮ menu to pin favorite tabs here.",
                        color = Color(0xFF8B949E),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { showFavoritesOnly = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
                    ) {
                        Text("Show All Tabs", color = Color(0xFF58A6FF))
                    }
                }
            }
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
                items(displayedTabs, key = { it.id }) { tab ->
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
                            } else if (tab.isFavorite) {
                                BorderStroke(1.5.dp, Color(0xFFFFD600).copy(alpha = 0.75f))
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
                                    enabled = !isSelectionMode && !isClosing && !showFavoritesOnly,
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
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = { onToggleFavorite(tab.id) },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Text(
                                                    text = if (tab.isFavorite) "★" else "☆",
                                                    color = if (tab.isFavorite) Color(0xFFFFD600) else Color(0xFF8B949E),
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
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

                                    // Floating Ghost Profile Watermark
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color(0xFF161B22).copy(alpha = 0.84f),
                                        border = BorderStroke(1.dp, Color(tabProfile.colorValue).copy(alpha = 0.45f)),
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(6.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(5.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(tabProfile.colorValue))
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = tabProfile.name,
                                                color = Color(0xFFE8EAED),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1
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

    if (showHealthDeckDialog) {
        AiStudioHealthDashboardDialog(
            profiles = profiles,
            aiAnalytics = aiAnalytics,
            onClearQuotaHit = onClearQuotaHit,
            onClearVerificationLock = onClearVerificationLock,
            onDismiss = { showHealthDeckDialog = false }
        )
    }
    if (profileToDelete != null) {
        val target = profileToDelete!!
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            containerColor = Color(0xFF282C34),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color(target.colorValue))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Delete Profile?",
                        color = Color(0xFFE8EAED),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            },
            text = {
                Text(
                    "Are you sure you want to delete '${target.name}'? Any tabs currently using this profile will be moved to Default.",
                    color = Color(0xFF9AA0A6),
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteProfile(target)
                        profileToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDA3633))
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { profileToDelete = null }) {
                    Text("Cancel", color = Color(0xFF9AA0A6))
                }
            }
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
@Composable
fun AiStudioHealthDashboardDialog(
    profiles: List<BrowserProfile>,
    aiAnalytics: Map<String, com.omni.plugin.browser.models.ProfileAiAnalytics>,
    onClearQuotaHit: (String) -> Unit,
    onClearVerificationLock: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val now = remember { System.currentTimeMillis() }
    val today = remember(now) { getAnalyticsTodayKey(now) }
    val weekKeys = remember(now) { getLastNDaysKeys(7, now) }
    val nextResetMs = remember(now) { getNextPacificMidnightMs(now) }
    val resetDiffMs = (nextResetMs - now).coerceAtLeast(0L)
    val resetH = resetDiffMs / 3_600_000L
    val resetM = (resetDiffMs % 3_600_000L) / 60_000L

    var activeTab by remember { mutableStateOf("overview") }
    var selectedDayKey by remember { mutableStateOf(today) }
    var historyProfileFilter by remember { mutableStateOf<String?>(null) }

    val profileMap = remember(profiles) { profiles.associateBy { it.id } }

    // Aggregate KPI metrics across all profiles (including any archived profiles)
    val allAnalyticsList = remember(aiAnalytics, profiles) {
        val knownIds = profiles.map { it.id }.toSet()
        val extraIds = aiAnalytics.keys.filter { it !in knownIds }
        (profiles.map { it.id } + extraIds).map { id ->
            id to (aiAnalytics[id] ?: com.omni.plugin.browser.models.ProfileAiAnalytics(profileId = id))
        }
    }

    val totalTodayPrompts = remember(allAnalyticsList, today) {
        allAnalyticsList.sumOf { it.second.effectivePromptsToday(today) }
    }
    val totalTodayTabs = remember(allAnalyticsList, today) {
        allAnalyticsList.sumOf { it.second.effectiveTabsToday(today) }
    }
    val totalWeekPrompts = remember(allAnalyticsList, weekKeys) {
        allAnalyticsList.sumOf { it.second.promptsInDays(weekKeys) }
    }
    val totalWeekQuotaHits = remember(allAnalyticsList, weekKeys) {
        allAnalyticsList.sumOf { it.second.quotaHitsInDays(weekKeys) }
    }
    val totalLifetimePrompts = remember(allAnalyticsList) {
        allAnalyticsList.sumOf { it.second.totalPromptsLifetime }
    }
    val totalLifetimeQuotas = remember(allAnalyticsList) {
        allAnalyticsList.sumOf { it.second.totalQuotaHitsLifetime }
    }
    val readyAccountsCount = remember(profiles, aiAnalytics, now) {
        profiles.count { prof ->
            val a = aiAnalytics[prof.id]
            val qTime = a?.lastQuotaHitTimestamp ?: 0L
            !(a?.needsVerification ?: false) && !isQuotaCurrentlyActive(qTime, now)
        }
    }
    val verifyLockedCount = remember(profiles, aiAnalytics) {
        profiles.count { aiAnalytics[it.id]?.needsVerification ?: false }
    }

    // All historical days sorted newest -> oldest (never deleted)
    val allRecordedDays = remember(allAnalyticsList, today) {
        val set = sortedSetOf<String>(reverseOrder())
        set.add(today)
        weekKeys.forEach { set.add(it) }
        allAnalyticsList.forEach { (_, a) -> set.addAll(a.dailyHistory.keys) }
        set.toList()
    }

    // All quota hit events sorted newest -> oldest (never deleted)
    val allQuotaEvents = remember(allAnalyticsList) {
        allAnalyticsList.flatMap { (profId, a) ->
            a.quotaHistory.map { ev -> profId to ev }
        }.sortedByDescending { it.second.timestamp }
    }

    fun formatShortDay(dateKey: String): String {
        if (dateKey == today) return "Today"
        return try {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateKey)
            if (parsed != null) SimpleDateFormat("EEE\nM/d", Locale.US).format(parsed) else dateKey.takeLast(5)
        } catch (_: Exception) {
            dateKey.takeLast(5)
        }
    }

    fun formatFullDayLabel(dateKey: String): String {
        if (dateKey == today) return "Today ($dateKey)"
        return try {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateKey)
            if (parsed != null) SimpleDateFormat("EEE, MMM d, yyyy", Locale.US).format(parsed) else dateKey
        } catch (_: Exception) {
            dateKey
        }
    }

    fun formatTimeAgo(ts: Long): String {
        if (ts <= 0L) return "Never"
        val diff = (now - ts).coerceAtLeast(0L)
        val mins = diff / 60_000L
        val hrs = diff / 3_600_000L
        val days = diff / 86_400_000L
        return when {
            mins < 1L -> "Just now"
            hrs < 1L -> "${mins}m ago"
            days < 1L -> "${hrs}h ${mins % 60}m ago"
            else -> "${days}d ago"
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color(0xFF16181D),
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Surface(
                    color = Color(0xFF1B1F26),
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Close", tint = Color(0xFFE8EAED))
                            }
                            Column {
                                Text("AI Studio Health", color = Color(0xFFE8EAED), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Text("Permanent Analytics • Never Deleted", color = Color(0xFF8B949E), fontSize = 10.sp)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1F6FEB).copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, Color(0xFF58A6FF).copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = "PT Reset: ${resetH}h ${resetM}m",
                                color = Color(0xFF58A6FF),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // 4-Card KPI Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DashboardKpiCard(
                        label = "Today",
                        value = "$totalTodayPrompts",
                        sub = "$totalTodayTabs tabs",
                        accent = Color(0xFF58A6FF),
                        modifier = Modifier.weight(1f)
                    )
                    DashboardKpiCard(
                        label = "7 Days",
                        value = "$totalWeekPrompts",
                        sub = "${totalWeekPrompts / 7}/day avg",
                        accent = Color(0xFFBC8CFF),
                        modifier = Modifier.weight(1f)
                    )
                    DashboardKpiCard(
                        label = "All-Time",
                        value = "$totalLifetimePrompts",
                        sub = "$totalLifetimeQuotas hits",
                        accent = Color(0xFF3FB950),
                        modifier = Modifier.weight(1f)
                    )
                    DashboardKpiCard(
                        label = "Fleet",
                        value = "$readyAccountsCount/${profiles.size}",
                        sub = when {
                            readyAccountsCount == profiles.size -> "All Ready"
                            verifyLockedCount > 0 -> "$verifyLockedCount Verify Lock"
                            else -> "${profiles.size - readyAccountsCount} Exhausted"
                        },
                        accent = when {
                            readyAccountsCount == profiles.size -> Color(0xFF3FB950)
                            verifyLockedCount > 0 -> Color(0xFFFF6D00)
                            else -> Color(0xFFF85149)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Sub-Navigation Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1E222A))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        Triple("overview", "Overview", Color(0xFF58A6FF)),
                        Triple("history", "Daily Archive (${allRecordedDays.size})", Color(0xFF3FB950)),
                        Triple("quotas", "Quota Ledger (${allQuotaEvents.size})", Color(0xFFF85149))
                    ).forEach { (tabKey, tabTitle, tabCol) ->
                        val isSel = activeTab == tabKey
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) tabCol.copy(alpha = 0.2f) else Color.Transparent,
                            border = if (isSel) BorderStroke(1.dp, tabCol) else null,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { activeTab = tabKey }
                        ) {
                            Box(modifier = Modifier.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = tabTitle,
                                    color = if (isSel) tabCol else Color(0xFF8B949E),
                                    fontSize = 11.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                when (activeTab) {
                    "overview" -> {
                        val maxWeekDayPrompts = weekKeys.maxOfOrNull { k ->
                            allAnalyticsList.sumOf { it.second.dailyHistory[k]?.prompts ?: 0 }
                        }?.coerceAtLeast(1) ?: 1

                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        ) {
                            // Weekly Activity Chart Card
                            item(key = "weekly_chart") {
                                Card(
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E222A)),
                                    border = BorderStroke(1.dp, Color(0xFF2E333D)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Weekly Prompt Activity", color = Color(0xFFE8EAED), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            Text(
                                                "7d: $totalWeekPrompts prompts • $totalWeekQuotaHits quota hits",
                                                color = Color(0xFF8B949E),
                                                fontSize = 10.sp
                                            )
                                        }
                                        Spacer(Modifier.height(12.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth().height(110.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.Bottom
                                        ) {
                                            weekKeys.forEach { dKey ->
                                                val dayPrompts = allAnalyticsList.sumOf { it.second.dailyHistory[dKey]?.prompts ?: 0 }
                                                val dayQuotas = allAnalyticsList.sumOf { it.second.dailyHistory[dKey]?.quotaHits ?: 0 }
                                                val isSel = selectedDayKey == dKey
                                                val frac = (dayPrompts.toFloat() / maxWeekDayPrompts.toFloat()).coerceIn(0.05f, 1f)

                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.Bottom,
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .fillMaxHeight()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (isSel) Color(0xFF282E38) else Color.Transparent)
                                                        .clickable { selectedDayKey = dKey }
                                                        .padding(vertical = 4.dp, horizontal = 2.dp)
                                                ) {
                                                    if (dayQuotas > 0) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(6.dp)
                                                                .clip(CircleShape)
                                                                .background(Color(0xFFF85149))
                                                        )
                                                        Spacer(Modifier.height(2.dp))
                                                    }
                                                    Text(
                                                        text = "$dayPrompts",
                                                        color = if (isSel) Color.White else Color(0xFF9AA0A6),
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Spacer(Modifier.height(3.dp))
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth(0.55f)
                                                            .weight(1f, fill = false)
                                                            .height((56f * if (dayPrompts == 0) 0.06f else frac).dp)
                                                            .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                                            .background(
                                                                when {
                                                                    dayPrompts == 0 -> Color(0xFF2E333D)
                                                                    dayQuotas > 0 -> Color(0xFFF85149)
                                                                    isSel -> Color(0xFF58A6FF)
                                                                    else -> Color(0xFF388BFD).copy(alpha = 0.72f)
                                                                }
                                                            )
                                                    )
                                                    Spacer(Modifier.height(4.dp))
                                                    Text(
                                                        text = formatShortDay(dKey),
                                                        color = if (isSel) Color(0xFF58A6FF) else Color(0xFF8B949E),
                                                        fontSize = 8.sp,
                                                        lineHeight = 10.sp,
                                                        textAlign = TextAlign.Center
                                                    )
                                                }
                                            }
                                        }

                                        // Selected Day Per-Profile Breakdown
                                        val selectedDayTotal = allAnalyticsList.sumOf { it.second.dailyHistory[selectedDayKey]?.prompts ?: 0 }
                                        HorizontalDivider(color = Color(0xFF2B303A), modifier = Modifier.padding(vertical = 8.dp))
                                        Text(
                                            text = "${formatFullDayLabel(selectedDayKey)} • $selectedDayTotal total prompts",
                                            color = Color(0xFF58A6FF),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            allAnalyticsList.forEach { (id, a) ->
                                                val p = profileMap[id]
                                                val pColor = Color(p?.colorValue ?: 0xFF8B949E)
                                                val pName = p?.name ?: id
                                                val dStats = a.dailyHistory[selectedDayKey]
                                                val pPrompts = dStats?.prompts ?: 0
                                                val pHits = dStats?.quotaHits ?: 0
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = Color(0xFF16181D),
                                                    border = BorderStroke(1.dp, if (pHits > 0) Color(0xFFF85149).copy(alpha = 0.6f) else pColor.copy(alpha = 0.4f))
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                    ) {
                                                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(pColor))
                                                        Spacer(Modifier.width(5.dp))
                                                        Text(
                                                            text = "$pName: $pPrompts" + if (pHits > 0) " (🔴$pHits)" else "",
                                                            color = Color(0xFFE8EAED),
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Per-Profile Detailed Breakdown Cards
                            items(profiles, key = { it.id }) { prof ->
                                val a = aiAnalytics[prof.id] ?: com.omni.plugin.browser.models.ProfileAiAnalytics(profileId = prof.id)
                                val qTime = a.lastQuotaHitTimestamp
                                val isVerifyLocked = a.needsVerification
                                val isQuotaActive = isQuotaCurrentlyActive(qTime, now)
                                val todayPrompts = a.effectivePromptsToday(today)
                                val todayTabs = a.effectiveTabsToday(today)
                                val todayHeadless = a.dailyHistory[today]?.headlessPrompts ?: 0
                                val weekPrompts = a.promptsInDays(weekKeys)
                                val weekHits = a.quotaHitsInDays(weekKeys)
                                val maxProfDay = weekKeys.maxOfOrNull { a.dailyHistory[it]?.prompts ?: 0 }?.coerceAtLeast(1) ?: 1

                                Card(
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E222A)),
                                    border = BorderStroke(
                                        1.dp,
                                        when {
                                            isVerifyLocked -> Color(0xFFFF6D00).copy(alpha = 0.85f)
                                            isQuotaActive -> Color(0xFFF85149).copy(alpha = 0.65f)
                                            else -> Color(prof.colorValue).copy(alpha = 0.45f)
                                        }
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(prof.colorValue)))
                                                Spacer(Modifier.width(8.dp))
                                                Text(prof.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                if (isVerifyLocked) {
                                                    Surface(
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = Color(0xFF238636),
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .clickable { onClearVerificationLock(prof.id) }
                                                    ) {
                                                        Text(
                                                            "✅ Approve & Unlock",
                                                            color = Color.White,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                        )
                                                    }
                                                } else if (isQuotaActive) {
                                                    Surface(
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = Color(0xFF58A6FF).copy(alpha = 0.16f),
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .clickable { onClearQuotaHit(prof.id) }
                                                    ) {
                                                        Text(
                                                            "Reset Flag",
                                                            color = Color(0xFF58A6FF),
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                        )
                                                    }
                                                }
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = when {
                                                        isVerifyLocked -> Color(0xFFFF6D00).copy(alpha = 0.22f)
                                                        isQuotaActive -> Color(0xFFF85149).copy(alpha = 0.2f)
                                                        todayPrompts >= 40 -> Color(0xFFD29922).copy(alpha = 0.2f)
                                                        else -> Color(0xFF3FB950).copy(alpha = 0.2f)
                                                    }
                                                ) {
                                                    Text(
                                                        text = when {
                                                            isVerifyLocked -> "🔒 VERIFY LOCKED ($todayPrompts today)"
                                                            isQuotaActive -> "🔴 QUOTA HIT ($todayPrompts today)"
                                                            todayPrompts >= 40 -> "🟡 BUSY ($todayPrompts today)"
                                                            else -> "🟢 READY ($todayPrompts today)"
                                                        },
                                                        color = when {
                                                            isVerifyLocked -> Color(0xFFFF6D00)
                                                            isQuotaActive -> Color(0xFFF85149)
                                                            todayPrompts >= 40 -> Color(0xFFD29922)
                                                            else -> Color(0xFF3FB950)
                                                        },
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(Modifier.height(10.dp))

                                        // 4-Metric Row
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            ProfileStatPill("Today", "$todayPrompts", if (todayHeadless > 0) "${todayHeadless} auto" else "$todayTabs tabs", Color(0xFF58A6FF), Modifier.weight(1f))
                                            ProfileStatPill("7 Days", "$weekPrompts", "${weekHits} quota", Color(0xFFBC8CFF), Modifier.weight(1f))
                                            ProfileStatPill("All-Time", "${a.totalPromptsLifetime}", "${a.totalTabsLifetime} tabs", Color(0xFF3FB950), Modifier.weight(1f))
                                            ProfileStatPill("Quota Hits", "${a.totalQuotaHitsLifetime}", if (qTime > 0L) formatTimeAgo(qTime) else "Clean", if (a.totalQuotaHitsLifetime > 0) Color(0xFFF85149) else Color(0xFF8B949E), Modifier.weight(1f))
                                        }

                                        Spacer(Modifier.height(8.dp))

                                        // Mini 7-Day Spark Bars
                                        Row(
                                            modifier = Modifier.fillMaxWidth().height(22.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.Bottom
                                        ) {
                                            weekKeys.forEach { dKey ->
                                                val pCount = a.dailyHistory[dKey]?.prompts ?: 0
                                                val pHits = a.dailyHistory[dKey]?.quotaHits ?: 0
                                                val hFrac = (pCount.toFloat() / maxProfDay.toFloat()).coerceIn(0.15f, 1f)
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height((18f * if (pCount == 0) 0.15f else hFrac).dp)
                                                        .clip(RoundedCornerShape(3.dp))
                                                        .background(
                                                            when {
                                                                pCount == 0 -> Color(0xFF2B303A)
                                                                pHits > 0 -> Color(0xFFF85149)
                                                                else -> Color(prof.colorValue)
                                                            }
                                                        )
                                                )
                                            }
                                        }

                                        Spacer(Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                "Last prompt: ${formatTimeAgo(a.lastPromptTimestamp)}",
                                                color = Color(0xFF8B949E),
                                                fontSize = 10.sp
                                            )
                                            if (qTime > 0L) {
                                                Text(
                                                    if (isQuotaActive) "Resets in ${resetH}h ${resetM}m (PT)" else "Last hit ${formatTimeAgo(qTime)} (Reset)",
                                                    color = if (isQuotaActive) Color(0xFFF85149) else Color(0xFF8B949E),
                                                    fontSize = 10.sp,
                                                    fontWeight = if (isQuotaActive) FontWeight.SemiBold else FontWeight.Normal
                                                )
                                            }
                                        }

                                        if (isQuotaActive && a.lastQuotaReason.isNotBlank()) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                "Reason: ${a.lastQuotaReason}",
                                                color = Color(0xFFF28B82),
                                                fontSize = 10.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (isVerifyLocked) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                "🔒 Halted (${formatTimeAgo(a.verificationRequiredAt)}): ${a.verificationReason.ifBlank { "Google account verification required" }}. Verify in Chrome then tap Approve & Unlock.",
                                                color = Color(0xFFFFAB40),
                                                fontSize = 10.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    "history" -> {
                        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            // Profile Filter Chips
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (historyProfileFilter == null) Color(0xFF58A6FF) else Color(0xFF1E222A),
                                    border = BorderStroke(1.dp, if (historyProfileFilter == null) Color(0xFF58A6FF) else Color(0xFF2E333D)),
                                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { historyProfileFilter = null }
                                ) {
                                    Text(
                                        "All Accounts",
                                        color = if (historyProfileFilter == null) Color.Black else Color(0xFFE8EAED),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                                profiles.forEach { prof ->
                                    val isSel = historyProfileFilter == prof.id
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSel) Color(prof.colorValue).copy(alpha = 0.25f) else Color(0xFF1E222A),
                                        border = BorderStroke(1.dp, if (isSel) Color(prof.colorValue) else Color(0xFF2E333D)),
                                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                                            historyProfileFilter = if (isSel) null else prof.id
                                        }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        ) {
                                            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(Color(prof.colorValue)))
                                            Spacer(Modifier.width(5.dp))
                                            Text(prof.name, color = Color(0xFFE8EAED), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }

                            LazyColumn(
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            ) {
                                items(allRecordedDays, key = { it }) { dayKey ->
                                    val dayPairs = allAnalyticsList.mapNotNull { (id, a) ->
                                        if (historyProfileFilter != null && id != historyProfileFilter) return@mapNotNull null
                                        val stats = a.dailyHistory[dayKey] ?: return@mapNotNull null
                                        Triple(id, stats, profileMap[id])
                                    }
                                    val dayPrompts = dayPairs.sumOf { it.second.prompts }
                                    val dayHeadless = dayPairs.sumOf { it.second.headlessPrompts }
                                    val dayTabs = dayPairs.sumOf { it.second.tabsOpened }
                                    val dayQuotas = dayPairs.sumOf { it.second.quotaHits }

                                    Card(
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E222A)),
                                        border = BorderStroke(
                                            1.dp,
                                            if (dayQuotas > 0) Color(0xFFF85149).copy(alpha = 0.5f) else Color(0xFF2E333D)
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = formatFullDayLabel(dayKey),
                                                    color = if (dayKey == today) Color(0xFF58A6FF) else Color(0xFFE8EAED),
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "$dayPrompts prompts" + if (dayQuotas > 0) " • 🔴 $dayQuotas" else "",
                                                    color = if (dayQuotas > 0) Color(0xFFF85149) else Color(0xFF3FB950),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = "Tabs opened: $dayTabs • Automated/Headless: $dayHeadless • Interactive: ${(dayPrompts - dayHeadless).coerceAtLeast(0)}",
                                                color = Color(0xFF8B949E),
                                                fontSize = 10.sp
                                            )
                                            if (dayPairs.isNotEmpty()) {
                                                Spacer(Modifier.height(6.dp))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    dayPairs.forEach { (id, stats, prof) ->
                                                        val pCol = Color(prof?.colorValue ?: 0xFF8B949E)
                                                        Surface(
                                                            shape = RoundedCornerShape(8.dp),
                                                            color = Color(0xFF16181D),
                                                            border = BorderStroke(1.dp, pCol.copy(alpha = 0.45f))
                                                        ) {
                                                            Text(
                                                                text = "${prof?.name ?: id}: ${stats.prompts}p / ${stats.tabsOpened}t" + if (stats.quotaHits > 0) " / 🔴${stats.quotaHits}" else "",
                                                                color = Color(0xFFE8EAED),
                                                                fontSize = 10.sp,
                                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
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
                    }

                    else -> {
                        if (allQuotaEvents.isEmpty()) {
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("🟢", fontSize = 36.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Text("No Quota Hits Recorded Yet", color = Color(0xFFE8EAED), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    Text("Every 429 or resource-exhausted hit is archived here permanently.", color = Color(0xFF8B949E), fontSize = 11.sp)
                                }
                            }
                        } else {
                            val fmt = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.US) }
                            LazyColumn(
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            ) {
                                items(allQuotaEvents.size, key = { idx -> "${allQuotaEvents[idx].first}_${allQuotaEvents[idx].second.timestamp}_$idx" }) { idx ->
                                    val (profId, ev) = allQuotaEvents[idx]
                                    val prof = profileMap[profId]
                                    val pColor = Color(prof?.colorValue ?: 0xFFF85149)
                                    Card(
                                        shape = RoundedCornerShape(10.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E222A)),
                                        border = BorderStroke(1.dp, Color(0xFFF85149).copy(alpha = 0.45f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(pColor))
                                                    Spacer(Modifier.width(6.dp))
                                                    Text(prof?.name ?: profId, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                                Text(
                                                    text = fmt.format(Date(ev.timestamp)),
                                                    color = Color(0xFF8B949E),
                                                    fontSize = 10.sp
                                                )
                                            }
                                            if (ev.reason.isNotBlank()) {
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    text = ev.reason,
                                                    color = Color(0xFFF28B82),
                                                    fontSize = 11.sp,
                                                    maxLines = 3,
                                                    overflow = TextOverflow.Ellipsis
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
        }
    }
}

@Composable
private fun DashboardKpiCard(
    label: String,
    value: String,
    sub: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1E222A),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(label, color = Color(0xFF8B949E), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(value, color = accent, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Text(sub, color = Color(0xFFC9D1D9), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ProfileStatPill(
    label: String,
    value: String,
    sub: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF16181D),
        border = BorderStroke(1.dp, Color(0xFF2B303A)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
            Text(label, color = Color(0xFF8B949E), fontSize = 9.sp, maxLines = 1)
            Text(value, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(sub, color = Color(0xFF9AA0A6), fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}