package com.omni.plugin.browser.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.plugin.browser.models.BrowserProfile
import com.omni.plugin.browser.models.BrowserTab

@Composable
fun TabSwitcherScreen(
    tabs: List<BrowserTab>,
    activeTabId: String,
    profiles: List<BrowserProfile>,
    selectedProfileId: String,
    onSelectProfile: (String) -> Unit,
    onAddProfile: () -> Unit,
    onRenameProfile: (BrowserProfile) -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: (String) -> Unit,
    onCloseAll: () -> Unit,
    onCloseSwitcher: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showTabMenu by remember { mutableStateOf(false) }
    var profileMenuExpanded by remember { mutableStateOf(false) }
    val currentProfile = profiles.find { it.id == selectedProfileId } ?: profiles.firstOrNull() ?: BrowserProfile("default", "Account 1", 0xFF2979FF)

    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

    LaunchedEffect(tabs.size, activeTabId) {
        val activeIndex = tabs.indexOfFirst { it.id == activeTabId }
        val targetIndex = if (activeIndex >= 0) activeIndex else (tabs.size - 1).coerceAtLeast(0)
        if (targetIndex >= 0 && tabs.isNotEmpty()) {
            gridState.scrollToItem(targetIndex)
        }
    }

    Column(
        modifier = modifier
            .background(Color(0xFF1F2227))
            .statusBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(Color(0xFF1F2227))
                .padding(horizontal = 8.dp)
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
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = currentProfile.name.take(14),
                                    color = Color(currentProfile.colorValue),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Select Profile",
                                    tint = Color(currentProfile.colorValue),
                                    modifier = Modifier.size(16.dp)
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
                                            IconButton(
                                                onClick = {
                                                    profileMenuExpanded = false
                                                    onRenameProfile(prof)
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Edit,
                                                    contentDescription = "Rename",
                                                    tint = Color(0xFF9AA0A6),
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        onSelectProfile(prof.id)
                                        profileMenuExpanded = false
                                    }
                                )
                            }

                            HorizontalDivider(color = Color(0xFF3C4043))

                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF8AB4F8), modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("+ Add Account Profile", color = Color(0xFF8AB4F8), fontWeight = FontWeight.Bold, fontSize = 13.sp)
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

                Text(
                    text = "${tabs.size} open ${if (tabs.size == 1) "tab" else "tabs"}",
                    color = Color(0xFFE8EAED),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCloseSwitcher) {
                        Text("Done", color = Color(0xFF8AB4F8), fontWeight = FontWeight.Bold, fontSize = 14.sp)
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

        HorizontalDivider(color = Color(0xFF282C34))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(tabs, key = { it.id }) { tab ->
                val isActive = tab.id == activeTabId
                val tabProfile = profiles.find { it.id == tab.profileId } ?: profiles.firstOrNull() ?: BrowserProfile("default", "Default", 0xFF2979FF)

                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF282C34)),
                    border = if (isActive) BorderStroke(2.dp, Color(tabProfile.colorValue)) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clickable { onSelectTab(tab.id) }
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isActive) Color(0xFF333842) else Color(0xFF21252B))
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

                            IconButton(
                                onClick = { onCloseTab(tab.id) },
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

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(Color(0xFF16181D)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (tab.thumbnail != null && tab.url != "about:blank") {
                                Image(
                                    bitmap = tab.thumbnail.asImageBitmap(),
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
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        listOf(0xFF4285F4, 0xFFEA4335, 0xFF34A853, 0xFF58A6FF).forEach { col ->
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(RoundedCornerShape(2.dp))
                                                    .background(Color(col).copy(alpha = 0.8f))
                                            )
                                        }
                                    }
                                }
                            } else {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Text(
                                        text = "📄",
                                        fontSize = 24.sp
                                    )
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
                        }
                    }
                }
            }
        }
    }
}