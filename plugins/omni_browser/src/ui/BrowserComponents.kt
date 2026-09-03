package com.omni.plugin.browser.ui

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.omni.plugin.browser.models.BrowserProfile
import com.omni.plugin.browser.models.ShortcutItem
import com.omni.plugin.browser.utils.extractDomain

@Composable
fun OmniBrowserTopBar(
    currentUrl: String,
    urlInputText: String,
    onUrlTextChange: (String) -> Unit,
    onNavigate: (String) -> Unit,
    profColor: Color,
    tabCount: Int,
    isHomeOverlayOpen: Boolean,
    onHomeClick: () -> Unit,
    onOpenIdeNeighbor: () -> Unit,
    showIdePickerMenu: Boolean = false,
    onDismissIdePicker: () -> Unit = {},
    localShortcuts: List<ShortcutItem> = emptyList(),
    onSelectIdeShortcut: (ShortcutItem) -> Unit = {},
    onTabSwitcherClick: () -> Unit,
    showMenu: Boolean,
    onMenuToggle: () -> Unit
) {
    var isSearchFocused by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .graphicsLayer()
            .background(Color(0xFF16181D))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedVisibility(
                visible = !isSearchFocused,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally()
            ) {
                IconButton(
                    onClick = onHomeClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Home,
                        contentDescription = "Home",
                        tint = if (isHomeOverlayOpen || currentUrl == "about:blank") profColor else Color(0xFF9AA0A6)
                    )
                }
            }

            if (isSearchFocused) {
                IconButton(
                    onClick = {
                        isSearchFocused = false
                        focusManager.clearFocus()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Collapse Search",
                        tint = profColor
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(profColor.copy(alpha = 0.15f))
                    .border(1.5.dp, profColor, RoundedCornerShape(20.dp))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(profColor)
                    )

                    Spacer(Modifier.width(6.dp))

                    Icon(
                        imageVector = if (currentUrl.startsWith("https://")) Icons.Default.Check else if (currentUrl.startsWith("http://")) Icons.Default.Info else Icons.Default.Search,
                        contentDescription = null,
                        tint = if (currentUrl.startsWith("https://")) Color(0xFF81C995) else if (currentUrl.startsWith("http://")) Color(0xFFFDD663) else profColor,
                        modifier = Modifier.size(16.dp)
                    )

                    Spacer(Modifier.width(6.dp))

                    BasicTextField(
                        value = urlInputText,
                        onValueChange = onUrlTextChange,
                        singleLine = true,
                        maxLines = 1,
                        cursorBrush = SolidColor(profColor),
                        textStyle = TextStyle(
                            color = Color(0xFFE8EAED),
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            platformStyle = PlatformTextStyle(
                                includeFontPadding = false
                            )
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                isSearchFocused = false
                                focusManager.clearFocus()
                                onNavigate(urlInputText)
                            }
                        ),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (urlInputText.isEmpty()) {
                                    Text(
                                        text = "Search or type URL",
                                        color = Color(0xFF9AA0A6),
                                        fontSize = 13.sp,
                                        style = TextStyle(
                                            platformStyle = PlatformTextStyle(
                                                includeFontPadding = false
                                            )
                                        )
                                    )
                                }
                                innerTextField()
                            }
                        },
                                                        modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .wrapContentHeight(Alignment.CenterVertically)
                                    .onFocusChanged {
                                        isSearchFocused = it.isFocused
                                    }
                    )

                    if (urlInputText.isNotEmpty()) {
                        IconButton(
                            onClick = { onUrlTextChange("") },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color(0xFF9AA0A6), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = !isSearchFocused,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(4.dp))

                    Box {
                        IconButton(
                            onClick = onOpenIdeNeighbor,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text("💻", fontSize = 16.sp)
                        }

                        DropdownMenu(
                            expanded = showIdePickerMenu,
                            onDismissRequest = onDismissIdePicker,
                            modifier = Modifier.background(Color(0xFF282C34))
                        ) {
                            Text(
                                "Select Workspace / IDE",
                                color = Color(0xFF8AB4F8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                            HorizontalDivider(color = Color(0xFF3C4043))
                            if (localShortcuts.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Default IDE", color = Color(0xFFE8EAED), fontSize = 12.sp) },
                                    onClick = {
                                        onDismissIdePicker()
                                        onSelectIdeShortcut(ShortcutItem(title = "Local IDE", url = "ide/index.html", iconText = "💻"))
                                    }
                                )
                            } else {
                                localShortcuts.forEach { item ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(item.iconText.ifEmpty { "💻" }, fontSize = 13.sp)
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    item.title,
                                                    color = if (item.isDefault) Color(0xFF8AB4F8) else Color(0xFFE8EAED),
                                                    fontWeight = if (item.isDefault) FontWeight.Bold else FontWeight.Normal,
                                                    fontSize = 12.sp
                                                )
                                                if (item.isDefault) {
                                                    Spacer(Modifier.width(6.dp))
                                                    Text("⭐", fontSize = 10.sp)
                                                }
                                            }
                                        },
                                        onClick = {
                                            onDismissIdePicker()
                                            onSelectIdeShortcut(item)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.width(2.dp))

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(profColor.copy(alpha = 0.2f))
                            .border(1.5.dp, profColor, RoundedCornerShape(6.dp))
                            .clickable { onTabSwitcherClick() }
                    ) {
                        Text(
                            "$tabCount",
                            color = profColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Spacer(Modifier.width(2.dp))

                    IconButton(
                        onClick = onMenuToggle,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Menu",
                            tint = if (showMenu) profColor else Color(0xFF9AA0A6)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpeedDialView(
    currentUrl: String,
    isHomeOverlayOpen: Boolean,
    activeProfile: BrowserProfile,
    profColor: Color,
    shortcuts: List<ShortcutItem>,
    faviconCache: Map<String, Bitmap>,
    onFetchFavicon: (String) -> Unit,
    onReturnToLivePage: () -> Unit,
    onShortcutClick: (ShortcutItem) -> Unit,
    onShortcutLongClick: (ShortcutItem) -> Unit,
    onAddShortcutClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1F2227))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isHomeOverlayOpen && currentUrl != "about:blank") {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF282C34),
                border = BorderStroke(1.dp, Color(0xFF8AB4F8).copy(alpha = 0.5f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onReturnToLivePage() }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color(0xFF8AB4F8), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Return to Live Page", color = Color(0xFF8AB4F8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(16.dp))
        } else {
            Spacer(Modifier.height(40.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = profColor,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("🌐", fontSize = 20.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "Omni Chrome",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE8EAED)
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = profColor.copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, profColor.copy(alpha = 0.7f))
                ) {
                    Text(
                        "Profile: ${activeProfile.name}",
                        color = profColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Text(
            if (isHomeOverlayOpen && currentUrl != "about:blank") "Live page suspended safely in background" else "Fast, Stealthy, Dynamic Browsing",
            fontSize = 13.sp,
            color = Color(0xFF9AA0A6),
            modifier = Modifier.padding(top = 6.dp, bottom = 32.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(shortcuts, key = { it.id }) { item ->
                val domain = remember(item.url) { extractDomain(item.url) }
                LaunchedEffect(domain) {
                    onFetchFavicon(domain)
                }
                val iconBmp = faviconCache[domain]

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(
                            onClick = { onShortcutClick(item) },
                            onLongClick = { onShortcutLongClick(item) }
                        )
                        .padding(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF282C34),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (iconBmp != null) {
                                Image(
                                    bitmap = iconBmp.asImageBitmap(),
                                    contentDescription = item.title,
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Text(
                                    text = item.iconText.ifEmpty { item.title.take(1).uppercase() },
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(item.colorValue)
                                )
                            }
                            if (item.isDefault) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(2.dp),
                                    contentAlignment = Alignment.TopEnd
                                ) {
                                    Text("⭐", fontSize = 10.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        item.title,
                        fontSize = 11.sp,
                        color = Color(0xFFE8EAED),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }

            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onAddShortcutClick() }
                        .padding(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF282C34),
                        border = BorderStroke(1.dp, Color(0xFF5F6368)),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Add Shortcut",
                                tint = Color(0xFF8AB4F8),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Add",
                        fontSize = 11.sp,
                        color = Color(0xFF8AB4F8),
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun BrowserMenuOverlay(
    showMenu: Boolean,
    onDismiss: () -> Unit,
    canGoBack: Boolean,
    canGoForward: Boolean,
    currentUrl: String,
    onBackClick: () -> Unit,
    onForwardClick: () -> Unit,
    onReloadClick: () -> Unit,
    onOpenSmartNotes: () -> Unit,
    onOpenAutomation: () -> Unit,
    onOpenLocalIde: () -> Unit,
    activeDownloadsCount: Int,
    onOpenDownloads: () -> Unit,
    onNewTab: () -> Unit,
    onCloseTab: () -> Unit,
    isDesktopMode: Boolean,
    onToggleDesktopMode: () -> Unit,
    onCopyCleanUrl: () -> Unit,
    onInjectEruda: () -> Unit,
    onCaptureDomSnapshot: () -> Unit,
    onOpenSettings: () -> Unit,
    onExitBrowser: () -> Unit
) {
    if (!showMenu) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(20f)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onDismiss() })
            }
            .statusBarsPadding()
            .padding(top = 52.dp, end = 8.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF282C34),
            tonalElevation = 8.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, Color(0xFF3C4043)),
            modifier = Modifier
                .width(250.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { /* Consume taps */ })
                }
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            onDismiss()
                            onBackClick()
                        },
                        enabled = canGoBack || currentUrl != "about:blank"
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = if (canGoBack || currentUrl != "about:blank") Color(0xFFE8EAED) else Color(0xFF5F6368)
                        )
                    }

                    IconButton(
                        onClick = {
                            onDismiss()
                            onForwardClick()
                        },
                        enabled = canGoForward
                    ) {
                        Icon(
                            Icons.Default.ArrowForward,
                            contentDescription = "Forward",
                            tint = if (canGoForward) Color(0xFFE8EAED) else Color(0xFF5F6368)
                        )
                    }

                    IconButton(
                        onClick = {
                            onDismiss()
                            onReloadClick()
                        }
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Reload",
                            tint = Color(0xFFE8EAED)
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFF3C4043), modifier = Modifier.padding(vertical = 4.dp))

                @Composable
                fun InLayoutMenuItem(
                    title: String,
                    color: Color = Color(0xFFE8EAED),
                    isBold: Boolean = false,
                    onClick: () -> Unit
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                onDismiss()
                                onClick()
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            color = color,
                            fontSize = 13.sp,
                            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                InLayoutMenuItem("📝 Smart Notes", color = Color(0xFF8AB4F8), isBold = true) {
                    onOpenSmartNotes()
                }

                InLayoutMenuItem("🤖 AI Studio Automator", color = Color(0xFF8AB4F8), isBold = true) {
                    onOpenAutomation()
                }

                InLayoutMenuItem("💻 Open Local IDE", color = Color(0xFF58A6FF), isBold = true) {
                    onOpenLocalIde()
                }

                InLayoutMenuItem(
                    title = if (activeDownloadsCount > 0) "📥 Downloads ($activeDownloadsCount)" else "📥 Downloads",
                    color = if (activeDownloadsCount > 0) Color(0xFF81C995) else Color(0xFF8AB4F8),
                    isBold = true
                ) {
                    onOpenDownloads()
                }

                InLayoutMenuItem("+ New Tab", color = Color(0xFF8AB4F8), isBold = true) {
                    onNewTab()
                }

                InLayoutMenuItem("Close Tab") {
                    onCloseTab()
                }

                InLayoutMenuItem(if (isDesktopMode) "✓ Desktop Site" else "Desktop Site") {
                    onToggleDesktopMode()
                }

                InLayoutMenuItem("Copy Clean URL") {
                    onCopyCleanUrl()
                }

                InLayoutMenuItem("🛠️ Eruda DevTools (Console)", color = Color(0xFF8AB4F8), isBold = true) {
                    onInjectEruda()
                }

                InLayoutMenuItem("Capture DOM Snapshot", color = Color(0xFF8AB4F8)) {
                    onCaptureDomSnapshot()
                }

                InLayoutMenuItem("⚙️ Settings & Backup", color = Color(0xFF8AB4F8), isBold = true) {
                    onOpenSettings()
                }

                HorizontalDivider(color = Color(0xFF3C4043), modifier = Modifier.padding(vertical = 4.dp))

                InLayoutMenuItem("Exit Omni Chrome", color = Color(0xFFF28B82), isBold = true) {
                    onExitBrowser()
                }
            }
        }
    }
}

@Composable
fun UndoBanner(
    visible: Boolean,
    message: String,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF21262D),
            border = BorderStroke(1.dp, Color(0xFF30363D)),
            shadowElevation = 8.dp,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .wrapContentWidth()
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = message,
                    color = Color(0xFFE8EAED),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )

                TextButton(
                    onClick = onUndo,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        "UNDO",
                        color = Color(0xFF58A6FF),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = Color(0xFF8B949E),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}