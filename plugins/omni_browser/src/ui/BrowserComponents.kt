package com.omni.plugin.browser.ui

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import kotlinx.coroutines.launch
import com.omni.plugin.browser.models.ShortcutItem
import com.omni.plugin.browser.utils.extractDomain

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OmniBrowserTopBar(
    currentUrl: String,
    urlInputText: String,
    onUrlTextChange: (String) -> Unit,
    onNavigate: (String) -> Unit,
    profColor: Color,
    tabCount: Int,
    topLeftItems: List<String>,
    topBarItems: List<String>,
    isHomeOverlayOpen: Boolean,
    isSearchFocused: Boolean,
    onSearchFocusChange: (Boolean) -> Unit,
    onActionClick: (String) -> Unit,
    onActionLongClick: (String) -> Unit = {},
    isAutoPilotActive: Boolean = false,
    isDataSaverActive: Boolean = false,
    forceDarkWebPages: Boolean = false,
    isBrowserImmersiveActive: Boolean = false,
    dataSaverMode: String = "MEDIUM",
    isCurrentTabFavorite: Boolean = false,
    isFocusLockActive: Boolean = false,
    activeDownloadsCount: Int = 0,
    canGoBack: Boolean = false,
    canGoForward: Boolean = false,
    onTopBarDragStart: () -> Unit = {},
    onTopBarDrag: (Float) -> Unit = {},
    onTopBarDragEnd: () -> Unit = {},
    onTopBarDragCancel: () -> Unit = {}
) {
    var hasGainedFocus by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var textFieldValue by remember {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                text = urlInputText,
                selection = androidx.compose.ui.text.TextRange(urlInputText.length)
            )
        )
    }

    LaunchedEffect(urlInputText) {
        if (textFieldValue.text != urlInputText) {
            textFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                text = urlInputText,
                selection = androidx.compose.ui.text.TextRange(urlInputText.length)
            )
        }
    }

    LaunchedEffect(isSearchFocused) {
        if (isSearchFocused) {
            if (urlInputText.isEmpty() && currentUrl != "about:blank") {
                onUrlTextChange(currentUrl)
            }
            kotlinx.coroutines.delay(40)
            focusRequester.requestFocus()
            textFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                text = urlInputText,
                selection = androidx.compose.ui.text.TextRange(urlInputText.length)
            )
            keyboardController?.show()
        } else {
            hasGainedFocus = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .graphicsLayer()
            .background(Color(0xFF16181D))
            .pointerInput(isSearchFocused) {
                if (!isSearchFocused) {
                    detectHorizontalDragGestures(
                        onDragStart = { onTopBarDragStart() },
                        onDragEnd = { onTopBarDragEnd() },
                        onDragCancel = { onTopBarDragCancel() },
                        onHorizontalDrag = { _, dragAmount ->
                            onTopBarDrag(dragAmount)
                        }
                    )
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSearchFocused) {
                IconButton(
                    onClick = {
                        onSearchFocusChange(false)
                        hasGainedFocus = false
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        BrowserIcons.ArrowBack,
                        contentDescription = "Collapse Search",
                        tint = profColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
            } else if (topLeftItems.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    topLeftItems.forEach { slotId ->
                        NavSlotGlyph(
                            slotId = slotId,
                            profColor = profColor,
                            tabCount = tabCount,
                            currentUrl = currentUrl,
                            canGoBack = canGoBack,
                            canGoForward = canGoForward,
                            isHomeOverlayOpen = isHomeOverlayOpen,
                            isAutoPilotActive = isAutoPilotActive,
                            forceDarkWebPages = forceDarkWebPages,
                            isBrowserImmersiveActive = isBrowserImmersiveActive,
                    isBrowserImmersiveActive = isBrowserImmersiveActive,
                            dataSaverMode = dataSaverMode,
                            isCurrentTabFavorite = isCurrentTabFavorite,
                            isFocusLockActive = isFocusLockActive,
                            activeDownloadsCount = activeDownloadsCount,
                            sizeDp = 30,
                            onClick = { onActionClick(slotId) },
                            onLongClick = { onActionLongClick(slotId) }
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(profColor.copy(alpha = 0.14f))
                    .border(1.2.dp, profColor, RoundedCornerShape(17.dp))
                    .then(
                        if (!isSearchFocused) {
                            Modifier.clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) {
                                onSearchFocusChange(true)
                            }
                        } else Modifier
                    )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(profColor)
                    )

                    Spacer(Modifier.width(6.dp))

                    if (isDataSaverActive) {
                        Icon(
                            BrowserIcons.Shield,
                            contentDescription = "Data Saver",
                            tint = Color(0xFF58A6FF),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Icon(
                        imageVector = if (currentUrl.startsWith("https://")) BrowserIcons.ShieldCheck else if (currentUrl.startsWith("http://")) Icons.Default.Info else BrowserIcons.Search,
                        contentDescription = null,
                        tint = if (currentUrl.startsWith("https://")) Color(0xFF81C995) else if (currentUrl.startsWith("http://")) Color(0xFFFDD663) else profColor,
                        modifier = Modifier.size(14.dp)
                    )

                    Spacer(Modifier.width(6.dp))

                    if (isSearchFocused) {
                        BasicTextField(
                            value = textFieldValue,
                            onValueChange = { newValue ->
                                textFieldValue = newValue
                                if (newValue.text != urlInputText) {
                                    onUrlTextChange(newValue.text)
                                }
                            },
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
                                    onSearchFocusChange(false)
                                    hasGainedFocus = false
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    onNavigate(textFieldValue.text)
                                }
                            ),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (textFieldValue.text.isEmpty()) {
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
                                .focusRequester(focusRequester)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        hasGainedFocus = true
                                    } else if (hasGainedFocus) {
                                        onSearchFocusChange(false)
                                        hasGainedFocus = false
                                    }
                                }
                        )

                        if (textFieldValue.text.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    textFieldValue = androidx.compose.ui.text.input.TextFieldValue("")
                                    onUrlTextChange("")
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(BrowserIcons.Close, contentDescription = "Clear", tint = Color(0xFF9AA0A6), modifier = Modifier.size(15.dp))
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .wrapContentHeight(Alignment.CenterVertically),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = if (urlInputText.isEmpty() || currentUrl == "about:blank") "Search or type URL" else urlInputText,
                                color = if (urlInputText.isEmpty() || currentUrl == "about:blank") Color(0xFF9AA0A6) else Color(0xFFE8EAED),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(
                                    platformStyle = PlatformTextStyle(
                                        includeFontPadding = false
                                    )
                                )
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = !isSearchFocused && topBarItems.isNotEmpty(),
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(start = 10.dp, end = 2.dp)
                ) {
                    topBarItems.forEach { slotId ->
                        NavSlotGlyph(
                            slotId = slotId,
                            profColor = profColor,
                            tabCount = tabCount,
                            currentUrl = currentUrl,
                            canGoBack = canGoBack,
                            canGoForward = canGoForward,
                            isHomeOverlayOpen = isHomeOverlayOpen,
                            isAutoPilotActive = isAutoPilotActive,
                            forceDarkWebPages = forceDarkWebPages,
                            dataSaverMode = dataSaverMode,
                        isBrowserImmersiveActive = isBrowserImmersiveActive,
                            isCurrentTabFavorite = isCurrentTabFavorite,
                            isFocusLockActive = isFocusLockActive,
                            activeDownloadsCount = activeDownloadsCount,
                            sizeDp = 30,
                            onClick = { onActionClick(slotId) },
                            onLongClick = { onActionLongClick(slotId) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NavSlotGlyph(
    slotId: String,
    profColor: Color,
    tabCount: Int,
    currentUrl: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    isHomeOverlayOpen: Boolean,
    isAutoPilotActive: Boolean,
    forceDarkWebPages: Boolean,
    isBrowserImmersiveActive: Boolean = false,
    dataSaverMode: String = "MEDIUM",
    isCurrentTabFavorite: Boolean = false,
    isFocusLockActive: Boolean = false,
    activeDownloadsCount: Int = 0,
    showMenuActiveTint: Boolean = false,
    sizeDp: Int = 32,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val isEnabled = when (slotId) {
        "back" -> canGoBack || currentUrl != "about:blank"
        "forward" -> canGoForward
        "quick_toggle" -> tabCount > 1
        else -> true
    }

    val tintColor = when {
        !isEnabled -> Color(0xFF484F58)
        slotId == "home" && (isHomeOverlayOpen || currentUrl == "about:blank") -> profColor
        slotId == "menu" && showMenuActiveTint -> profColor
        slotId == "autopilot" && isAutoPilotActive -> Color(0xFF00E5FF)
        slotId == "force_dark" && forceDarkWebPages -> Color(0xFFFDD663)
        slotId == "immersive_mode" && isBrowserImmersiveActive -> Color(0xFF00E5FF)
        slotId == "favorite_tab" && isCurrentTabFavorite -> Color(0xFFFFD600)
        slotId == "focus_lock" && isFocusLockActive -> Color(0xFFF85149)
        slotId == "data_saver" && dataSaverMode == "EXTREME" -> Color(0xFF3FB950)
        slotId == "data_saver" && dataSaverMode == "MEDIUM" -> Color(0xFF58A6FF)
        slotId == "new_tab" -> profColor
        slotId == "exit" -> Color(0xFFF28B82)
        else -> Color(0xFFE8EAED)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                enabled = isEnabled,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        when (slotId) {
            "tabs" -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(profColor.copy(alpha = 0.16f))
                        .border(1.5.dp, profColor, RoundedCornerShape(6.dp))
                ) {
                    Text(
                        "$tabCount",
                        color = profColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
            "menu" -> {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        BrowserIcons.Menu,
                        contentDescription = "Menu",
                        tint = tintColor,
                        modifier = Modifier.size(20.dp)
                    )
                    if (isAutoPilotActive) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .align(Alignment.TopEnd)
                                .offset(x = 2.dp, y = (-2).dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E5FF))
                        )
                    }
                }
            }
            "downloads" -> {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        BrowserIcons.Download,
                        contentDescription = "Downloads",
                        tint = tintColor,
                        modifier = Modifier.size(20.dp)
                    )
                    if (activeDownloadsCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 5.dp, y = (-3).dp)
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF3FB950)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("$activeDownloadsCount", color = Color.Black, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            else -> {
                Icon(
                    imageVector = getBrowserIconForSlot(
                        id = slotId,
                        forceDark = forceDarkWebPages,
                        dataSaverMode = dataSaverMode,
                        isFav = isCurrentTabFavorite
                    ),
                    contentDescription = slotId,
                    tint = tintColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun OmniBottomBar(
    bottomBarItems: List<String>,
    profColor: Color,
    tabCount: Int,
    currentUrl: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    isHomeOverlayOpen: Boolean,
    isAutoPilotActive: Boolean,
    forceDarkWebPages: Boolean,
    dataSaverMode: String = "MEDIUM",
    isBrowserImmersiveActive: Boolean = false,
    isCurrentTabFavorite: Boolean = false,
    isFocusLockActive: Boolean = false,
    activeDownloadsCount: Int,
    showMenu: Boolean,
    onItemClick: (String) -> Unit,
    onItemLongClick: (String) -> Unit
) {
    Surface(
        color = Color(0xFF16181D),
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Column {
            HorizontalDivider(color = Color(0xFF282C34), thickness = 0.8.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                bottomBarItems.forEach { slotId ->
                    NavSlotGlyph(
                        slotId = slotId,
                        profColor = profColor,
                        tabCount = tabCount,
                        currentUrl = currentUrl,
                        canGoBack = canGoBack,
                        canGoForward = canGoForward,
                        isHomeOverlayOpen = isHomeOverlayOpen,
                        isAutoPilotActive = isAutoPilotActive,
                        forceDarkWebPages = forceDarkWebPages,
                        dataSaverMode = dataSaverMode,
                        isCurrentTabFavorite = isCurrentTabFavorite,
                        isFocusLockActive = isFocusLockActive,
                        activeDownloadsCount = activeDownloadsCount,
                        showMenuActiveTint = showMenu,
                        sizeDp = 36,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = { onItemClick(slotId) },
                        onLongClick = { onItemLongClick(slotId) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BottomBarExpandFab(
    visible: Boolean,
    profColor: Color,
    isAutoPilotActive: Boolean,
    onExpand: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.7f),
        exit = fadeOut() + scaleOut(targetScale = 0.7f),
        modifier = modifier.offset {
            IntOffset(dragOffsetX.roundToInt(), dragOffsetY.roundToInt())
        }
    ) {
        Surface(
            shape = CircleShape,
            color = Color(0xFF161B22).copy(alpha = 0.90f),
            border = BorderStroke(1.2.dp, profColor.copy(alpha = 0.75f)),
            shadowElevation = 10.dp,
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        dragOffsetX += dragAmount.x
                        dragOffsetY += dragAmount.y
                    }
                }
                .combinedClickable(
                    onClick = onExpand,
                    onLongClick = onOpenMenu
                )
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    BrowserIcons.Menu,
                    contentDescription = "Expand Bar",
                    tint = Color(0xFFE8EAED),
                    modifier = Modifier.size(19.dp)
                )
                if (isAutoPilotActive) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E5FF))
                    )
                }
            }
        }
    }
}
@Composable
fun ImmersiveFloatingControlsPill(
    visible: Boolean,
    onRotateClick: () -> Unit,
    onExitClick: () -> Unit,
    draggable: Boolean = true,
    modifier: Modifier = Modifier
) {
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = modifier.offset {
            IntOffset(dragOffsetX.roundToInt(), dragOffsetY.roundToInt())
        }
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color.Black.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
            shadowElevation = 6.dp,
            modifier = if (draggable) {
                Modifier.pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        dragOffsetX += dragAmount.x
                        dragOffsetY += dragAmount.y
                    }
                }
            } else Modifier
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                IconButton(
                    onClick = onRotateClick,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        BrowserIcons.Reload,
                        contentDescription = "Rotate Screen",
                        tint = Color.White.copy(alpha = 0.88f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = onExitClick,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        BrowserIcons.FullscreenExit,
                        contentDescription = "Exit Fullscreen",
                        tint = Color.White.copy(alpha = 0.88f),
                        modifier = Modifier.size(16.dp)
                    )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowserMenuOverlay(
    showMenu: Boolean,
    onDismiss: () -> Unit,
    canGoBack: Boolean,
    canGoForward: Boolean,
    currentUrl: String,
    dataSaverMode: String = "MEDIUM",
    onToggleDataSaver: () -> Unit = {},
    currentZoomPercent: Int = 100,
    onOpenZoom: () -> Unit = {},
    isSiteCached: Boolean = false,
    siteCacheSizeText: String = "0 B",
    onToggleSiteCache: () -> Unit = {},
    onClearSiteCache: () -> Unit = {},
    isCurrentTabFavorite: Boolean = false,
    onToggleFavoriteTab: () -> Unit = {},
    forceDarkWebPages: Boolean = false,
    onToggleForceDark: () -> Unit = {},
    isBrowserImmersiveActive: Boolean = false,
    onToggleImmersiveMode: () -> Unit = {},
    isFocusLockActive: Boolean = false,
    onToggleFocusLock: () -> Unit = {},
    isLiveAutoPilotEnabled: Boolean = false,
    onToggleLiveAutoPilot: () -> Unit = {},
    isAutoPilotConfirmEnabled: Boolean = false,
    onToggleAutoPilotConfirm: () -> Unit = {},
    activeMenuItems: List<String>,
    onBackClick: () -> Unit,
    onForwardClick: () -> Unit,
    onReloadClick: () -> Unit,
    onNewTab: () -> Unit,
    onOpenTabSwitcher: () -> Unit = {},
    onQuickToggleTab: () -> Unit = {},
    onHomeClick: () -> Unit = {},
    onCopyCleanUrl: () -> Unit,
    onCaptureDomSnapshot: () -> Unit,
    onCaptureFullSource: () -> Unit = {},
    onOpenLocalIde: () -> Unit,
    onOpenAutomation: () -> Unit,
    onOpenSmartNotes: () -> Unit,
    onOpenStudioLab: () -> Unit = {},
    activeDownloadsCount: Int,
    onOpenDownloads: () -> Unit,
    onShowRecents: () -> Unit = {},
    onShowDiagnostics: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onCustomizeLayout: () -> Unit = {},
    onExitBrowser: () -> Unit
) {
    if (!showMenu) return

    val gridItems = remember(activeMenuItems) {
        activeMenuItems.filter { it != "menu" }
    }
    val pages = remember(gridItems) {
        val chunked = gridItems.chunked(10)
        if (chunked.isEmpty()) listOf(emptyList()) else chunked
    }

    val scrollState = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scope = rememberCoroutineScope()
    var pageWidthPx by remember { mutableFloatStateOf(0f) }

    val currentPage by remember {
        derivedStateOf {
            if (pageWidthPx <= 1f) 0
            else kotlin.math.round(scrollState.value / pageWidthPx).toInt().coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(scrollState.isScrollInProgress, pageWidthPx) {
        if (!scrollState.isScrollInProgress && pageWidthPx > 1f) {
            val targetPage = kotlin.math.round(scrollState.value / pageWidthPx).toInt().coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            val targetScroll = kotlin.math.round(targetPage * pageWidthPx).toInt()
            if (kotlin.math.abs(scrollState.value - targetScroll) > 2) {
                scrollState.animateScrollTo(targetScroll)
            }
        }
    }

    fun getGridTileColor(id: String): Color? = when (id) {
        "force_dark" -> if (forceDarkWebPages) Color(0xFFFDD663) else null
        "immersive_mode" -> if (isBrowserImmersiveActive) Color(0xFF00E5FF) else null
        "data_saver" -> when (dataSaverMode) {
            "EXTREME" -> Color(0xFF3FB950)
            "MEDIUM" -> Color(0xFF58A6FF)
            else -> null
        }
        "site_cache" -> if (isSiteCached) Color(0xFF3FB950) else null
        "page_zoom" -> if (currentZoomPercent != 100) Color(0xFF58A6FF) else null
        "favorite_tab" -> if (isCurrentTabFavorite) Color(0xFFFFD600) else null
        "focus_lock" -> if (isFocusLockActive) Color(0xFFF85149) else null
        "full_source" -> Color(0xFF81C995)
        "local_ide" -> Color(0xFF58A6FF)
        "autopilot" -> if (isLiveAutoPilotEnabled) Color(0xFF00E5FF) else null
        "studio_lab" -> Color(0xFF00E5FF)
        "ai_automator" -> Color(0xFF8AB4F8)
        "smart_notes" -> Color(0xFF8AB4F8)
        "downloads" -> if (activeDownloadsCount > 0) Color(0xFF3FB950) else null
        "recents" -> Color(0xFF58A6FF)
        "diagnostics" -> Color(0xFF58A6FF)
        "settings" -> Color(0xFF8AB4F8)
        "exit" -> Color(0xFFF28B82)
        "new_tab" -> Color(0xFF8AB4F8)
        "tabs" -> Color(0xFF8AB4F8)
        else -> null
    }

    fun getGridTileLabel(id: String): String = when (id) {
        "force_dark" -> if (forceDarkWebPages) "Light Mode" else "Night Mode"
        "immersive_mode" -> if (isBrowserImmersiveActive) "Exit Full" else "Immersive"
        "data_saver" -> when (dataSaverMode) {
            "EXTREME" -> "Data: Ext"
            "MEDIUM" -> "Data: Med"
            else -> "Data Saver"
        }
        "site_cache" -> if (isSiteCached && siteCacheSizeText != "0 B") siteCacheSizeText else "Cache Site"
        "page_zoom" -> "Zoom $currentZoomPercent%"
        "favorite_tab" -> if (isCurrentTabFavorite) "Favorited" else "Fav Tab"
        "focus_lock" -> if (isFocusLockActive) "Locked" else "Focus Lock"
        "copy_url" -> "Copy URL"
        "dom_snapshot" -> "DOM Dump"
        "full_source" -> "Web Source"
        "local_ide" -> "Local IDE"
        "autopilot" -> if (isLiveAutoPilotEnabled) "Auto-Pilot ON" else "Auto-Pilot"
        "studio_lab" -> "Studio Lab"
        "ai_automator" -> "Automator"
        "smart_notes" -> "Smart Notes"
        "downloads" -> if (activeDownloadsCount > 0) "DL ($activeDownloadsCount)" else "Downloads"
        "recents" -> "Recents"
        "diagnostics" -> "Diagnostics"
        "settings" -> "Settings"
        "exit" -> "Exit"
        "back" -> "Back"
        "forward" -> "Forward"
        "reload" -> "Reload"
        "new_tab" -> "New Tab"
        "home" -> "Home"
        "tabs" -> "Tabs"
        "quick_toggle" -> "Prev Tab"
        else -> id
    }

    fun triggerTileAction(id: String) {
        onDismiss()
        when (id) {
            "force_dark" -> onToggleForceDark()
            "immersive_mode" -> onToggleImmersiveMode()
            "data_saver" -> onToggleDataSaver()
            "site_cache" -> onToggleSiteCache()
            "page_zoom" -> onOpenZoom()
            "favorite_tab" -> onToggleFavoriteTab()
            "focus_lock" -> onToggleFocusLock()
            "copy_url" -> onCopyCleanUrl()
            "dom_snapshot" -> onCaptureDomSnapshot()
            "full_source" -> onCaptureFullSource()
            "local_ide" -> onOpenLocalIde()
            "autopilot" -> onToggleLiveAutoPilot()
            "studio_lab" -> onOpenStudioLab()
            "ai_automator" -> onOpenAutomation()
            "smart_notes" -> onOpenSmartNotes()
            "downloads" -> onOpenDownloads()
            "recents" -> onShowRecents()
            "diagnostics" -> onShowDiagnostics()
            "settings" -> onOpenSettings()
            "exit" -> onExitBrowser()
            "back" -> onBackClick()
            "forward" -> onForwardClick()
            "reload" -> onReloadClick()
            "new_tab" -> onNewTab()
            "home" -> onHomeClick()
            "tabs" -> onOpenTabSwitcher()
            "quick_toggle" -> onQuickToggleTab()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(20f)
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onDismiss() })
            }
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = Color(0xFF1E222A),
            tonalElevation = 10.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(1.dp, Color(0xFF30363D)),
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { /* Consume taps */ })
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 8.dp)
            ) {
                // Top quick strip: Back, Forward, Reload, New Tab (+ Auto-Pilot Confirm toggle when armed)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = { onDismiss(); onBackClick() },
                            enabled = canGoBack || currentUrl != "about:blank",
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                BrowserIcons.ArrowBack,
                                contentDescription = "Back",
                                tint = if (canGoBack || currentUrl != "about:blank") Color(0xFFE8EAED) else Color(0xFF5F6368),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        IconButton(
                            onClick = { onDismiss(); onForwardClick() },
                            enabled = canGoForward,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                BrowserIcons.ArrowForward,
                                contentDescription = "Forward",
                                tint = if (canGoForward) Color(0xFFE8EAED) else Color(0xFF5F6368),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        IconButton(
                            onClick = { onDismiss(); onReloadClick() },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(BrowserIcons.Reload, contentDescription = "Reload", tint = Color(0xFFE8EAED), modifier = Modifier.size(19.dp))
                        }
                        IconButton(
                            onClick = { onDismiss(); onNewTab() },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(BrowserIcons.Plus, contentDescription = "New Tab", tint = Color(0xFF8AB4F8), modifier = Modifier.size(20.dp))
                        }
                    }

                    if (isLiveAutoPilotEnabled) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isAutoPilotConfirmEnabled) Color(0xFF00E5FF).copy(alpha = 0.18f) else Color(0xFF282C34),
                            border = BorderStroke(1.dp, if (isAutoPilotConfirmEnabled) Color(0xFF00E5FF) else Color(0xFF3C4043)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onToggleAutoPilotConfirm() }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    if (isAutoPilotConfirmEnabled) BrowserIcons.ShieldCheck else BrowserIcons.Zap,
                                    contentDescription = null,
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = if (isAutoPilotConfirmEnabled) "Confirm: ON" else "Auto: ON",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF2C313A), modifier = Modifier.padding(vertical = 6.dp))

                // Horizontally scrollable 5x2 grid pages
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val widthDp = maxWidth
                    val widthPx = with(density) { widthDp.toPx() }
                    LaunchedEffect(widthPx) { pageWidthPx = widthPx }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(scrollState)
                    ) {
                        pages.forEach { pageItems ->
                            val row1 = pageItems.take(5)
                            val row2 = pageItems.drop(5).take(5)
                            Column(
                                modifier = Modifier
                                    .width(widthDp)
                                    .padding(horizontal = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(row1, row2).forEach { rowItems ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        for (colIdx in 0 until 5) {
                                            val itemId = rowItems.getOrNull(colIdx)
                                            if (itemId == null) {
                                                Spacer(modifier = Modifier.weight(1f).height(64.dp))
                                            } else {
                                                val accent = getGridTileColor(itemId)
                                                val label = getGridTileLabel(itemId)
                                                val iconVec = getBrowserIconForSlot(
                                                    id = itemId,
                                                    forceDark = forceDarkWebPages,
                                                    dataSaverMode = dataSaverMode,
                                                    isFav = isCurrentTabFavorite
                                                )
                                                val isActiveHighlight = accent != null
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.Center,
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(12.dp))
                                                        .combinedClickable(
                                                            onClick = { triggerTileAction(itemId) },
                                                            onLongClick = {
                                                                if (itemId == "site_cache") {
                                                                    onDismiss()
                                                                    onClearSiteCache()
                                                                }
                                                            }
                                                        )
                                                        .padding(vertical = 6.dp, horizontal = 2.dp)
                                                ) {
                                                    Box(
                                                        contentAlignment = Alignment.Center,
                                                        modifier = Modifier
                                                            .size(36.dp)
                                                            .clip(CircleShape)
                                                            .background(
                                                                if (isActiveHighlight) accent!!.copy(alpha = 0.16f)
                                                                else Color(0xFF282C34)
                                                            )
                                                    ) {
                                                        Icon(
                                                            imageVector = iconVec,
                                                            contentDescription = label,
                                                            tint = accent ?: Color(0xFFE8EAED),
                                                            modifier = Modifier.size(19.dp)
                                                        )
                                                    }
                                                    Spacer(Modifier.height(4.dp))
                                                    Text(
                                                        text = label,
                                                        color = accent ?: Color(0xFFE8EAED),
                                                        fontSize = 10.sp,
                                                        fontWeight = if (isActiveHighlight) FontWeight.Bold else FontWeight.Medium,
                                                        maxLines = 1,
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

                // Page Indicator Dots
                if (pages.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        pages.indices.forEach { idx ->
                            val isSel = idx == currentPage
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .size(width = if (isSel) 14.dp else 6.dp, height = 6.dp)
                                    .clip(CircleShape)
                                    .background(if (isSel) Color(0xFF8AB4F8) else Color(0xFF484F58))
                                    .clickable {
                                        if (pageWidthPx > 1f) {
                                            scope.launch {
                                                scrollState.animateScrollTo(kotlin.math.round(idx * pageWidthPx).toInt())
                                            }
                                        }
                                    }
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF2C313A), modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                // Bottom Sheet Footer (Customize Layout on left, Exit & Collapse on right)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onCustomizeLayout()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Icon(BrowserIcons.LayoutGrid, contentDescription = null, tint = Color(0xFF8AB4F8), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Edit Layout", color = Color(0xFF8AB4F8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconButton(
                            onClick = {
                                onDismiss()
                                onExitBrowser()
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(BrowserIcons.Power, contentDescription = "Exit", tint = Color(0xFFF28B82), modifier = Modifier.size(19.dp))
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(BrowserIcons.ChevronDown, contentDescription = "Close", tint = Color(0xFF9AA0A6), modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CxpIngestionPillBanner(
    visible: Boolean,
    status: String?,
    message: String,
    onOpenIde: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible && status != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier
    ) {
        val (borderColor, icon, titleText) = when (status) {
            "SUCCESS" -> Triple(Color(0xFF238636), "✅", "Success")
            "PARTIAL" -> Triple(Color(0xFFD29922), "✨", "AI Healed")
            "PARTIAL_PARSE_BLOCKED" -> Triple(Color(0xFFFF9800), "⚠️", "Parse Blocked")
            "DUPLICATE" -> Triple(Color(0xFFFF9800), "🔁", "Duplicate Blocked")
            "NETWORK_ERROR" -> Triple(Color(0xFFDA3633), "📡", "Network Error")
            "FAILED" -> Triple(Color(0xFFDA3633), "❌", "Failed")
            else -> Triple(Color(0xFF58A6FF), "⏳", "Committing")
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF161B22),
            border = BorderStroke(1.2.dp, borderColor),
            shadowElevation = 12.dp,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .wrapContentWidth()
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(icon, fontSize = 16.sp)

                Column(modifier = Modifier.widthIn(max = 210.dp)) {
                    Text(
                        text = titleText,
                        color = borderColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = message,
                        color = Color(0xFFE8EAED),
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = onOpenIde,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text("Open IDE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
@Composable
fun ReadAloudPillBanner(
    visible: Boolean,
    playbackState: String,
    chunkProgress: String,
    autoReadAloud: Boolean,
    onReadOrStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier.offset {
            IntOffset(dragOffsetX.roundToInt(), dragOffsetY.roundToInt())
        }
    ) {
        val isIdle = playbackState == "IDLE"
        val isConnecting = playbackState == "CONNECTING"
        val borderColor = when (playbackState) {
            "SPEAKING" -> Color(0xFF00E5FF)
            "CONNECTING" -> Color(0xFFD29922)
            else -> Color(0xFF58A6FF).copy(alpha = 0.65f)
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF161B22).copy(alpha = 0.94f),
            border = BorderStroke(1.2.dp, borderColor),
            shadowElevation = 10.dp,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        dragOffsetX += dragAmount.x
                        dragOffsetY += dragAmount.y
                    }
                }
                .clickable { onReadOrStop() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = when {
                        isConnecting -> BrowserIcons.Reload
                        !isIdle -> BrowserIcons.Stop
                        else -> BrowserIcons.VolumeUp
                    },
                    contentDescription = null,
                    tint = when {
                        isConnecting -> Color(0xFFD29922)
                        !isIdle -> Color(0xFFF85149)
                        else -> Color(0xFF8AB4F8)
                    },
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = when {
                        isConnecting -> "Connecting..."
                        !isIdle -> if (chunkProgress.isNotEmpty()) "Stop ($chunkProgress)" else "Stop"
                        autoReadAloud -> "Read Last • Auto"
                        else -> "Read Last"
                    },
                    color = when {
                        isConnecting -> Color(0xFFD29922)
                        !isIdle -> Color(0xFFF85149)
                        else -> Color(0xFFE8EAED)
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun DownloadCompletedPillBanner(
    visible: Boolean,
    file: java.io.File?,
    onOpen: (java.io.File) -> Unit,
    onAttach: (java.io.File) -> Unit = {},
    onDelete: (java.io.File) -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible && file != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier
    ) {
        if (file == null) return@AnimatedVisibility
        val emoji = remember(file.name) { com.omni.plugin.browser.utils.getFileEmoji(file.name) }
        val sizeText = remember(file, file.length()) {
            if (file.exists() && file.length() > 0) {
                com.omni.plugin.browser.utils.formatFileSize(file.length())
            } else ""
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF161B22),
            border = BorderStroke(1.2.dp, Color(0xFF58A6FF).copy(alpha = 0.8f)),
            shadowElevation = 12.dp,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .wrapContentWidth()
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(emoji, fontSize = 16.sp)

                Column(modifier = Modifier.widthIn(max = 130.dp)) {
                    Text(
                        text = file.name,
                        color = Color(0xFFE8EAED),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (sizeText.isNotEmpty()) "Downloaded • $sizeText" else "Downloaded",
                        color = Color(0xFF3FB950),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Button(
                    onClick = {
                        onDismiss()
                        onAttach(file)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("📎 Attach", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        onDismiss()
                        onOpen(file)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Open", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                IconButton(
                    onClick = {
                        onDismiss()
                        onDelete(file)
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = Color(0xFFF85149),
                        modifier = Modifier.size(15.dp)
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = Color(0xFF8B949E),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun OmniboxSuggestionOverlay(
    suggestions: List<com.omni.plugin.browser.models.SearchSuggestion>,
    onSelectSuggestion: (com.omni.plugin.browser.models.SearchSuggestion) -> Unit,
    onFillSuggestion: (com.omni.plugin.browser.models.SearchSuggestion) -> Unit,
    onDeleteSuggestion: (com.omni.plugin.browser.models.SearchSuggestion) -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (suggestions.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
        color = Color(0xFF16181D),
        border = BorderStroke(1.dp, Color(0xFF30363D)),
        shadowElevation = 14.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
    ) {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .padding(vertical = 4.dp)
        ) {
            items(
                count = suggestions.size,
                key = { idx -> suggestions[idx].id }
            ) { idx ->
                val sug = suggestions[idx]
                val icon = when (sug.type) {
                    com.omni.plugin.browser.models.SuggestionType.HISTORY -> "🕒"
                    com.omni.plugin.browser.models.SuggestionType.OPEN_TAB -> "📱"
                    com.omni.plugin.browser.models.SuggestionType.SHORTCUT -> "⭐"
                    com.omni.plugin.browser.models.SuggestionType.SEARCH_QUERY -> "🔍"
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelectSuggestion(sug) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(icon, fontSize = 15.sp)
                    Spacer(Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = sug.text,
                            color = Color(0xFFE8EAED),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!sug.subtitle.isNullOrEmpty()) {
                            Text(
                                text = sug.subtitle,
                                color = if (sug.type == com.omni.plugin.browser.models.SuggestionType.OPEN_TAB) Color(0xFF58A6FF) else Color(0xFF8B949E),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (sug.type == com.omni.plugin.browser.models.SuggestionType.HISTORY) {
                            IconButton(
                                onClick = { onDeleteSuggestion(sug) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Delete from history",
                                    tint = Color(0xFFF28B82).copy(alpha = 0.8f),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = { onFillSuggestion(sug) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Text("⬈", fontSize = 16.sp, color = Color(0xFF8B949E), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PageZoomBar(
    visible: Boolean,
    currentDomain: String,
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        val zoomPercent = Math.round(zoom * 100f)

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF161B22),
            border = BorderStroke(1.2.dp, Color(0xFF58A6FF).copy(alpha = 0.7f)),
            shadowElevation = 14.dp,
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔍", fontSize = 14.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Page Zoom",
                            color = Color(0xFFE8EAED),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (currentDomain.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF21262D)
                            ) {
                                Text(
                                    text = currentDomain,
                                    color = Color(0xFF58A6FF),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (zoomPercent != 100) {
                            TextButton(
                                onClick = onReset,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text("Reset (100%)", color = Color(0xFF58A6FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color(0xFF8B949E),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { onZoomChange((zoom - 0.05f).coerceAtLeast(0.5f)) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("−", color = Color(0xFF58A6FF), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    Slider(
                        value = zoom,
                        onValueChange = { onZoomChange(it) },
                        valueRange = 0.5f..2.5f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF58A6FF),
                            activeTrackColor = Color(0xFF58A6FF),
                            inactiveTrackColor = Color(0xFF30363D)
                        ),
                        modifier = Modifier.weight(1f).height(24.dp)
                    )

                    IconButton(
                        onClick = { onZoomChange((zoom + 0.05f).coerceAtMost(2.5f)) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("+", color = Color(0xFF58A6FF), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF21262D),
                        border = BorderStroke(1.dp, Color(0xFF30363D)),
                        modifier = Modifier.width(52.dp)
                    ) {
                        Text(
                            text = "${zoomPercent}%",
                            color = Color(0xFFE8EAED),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
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