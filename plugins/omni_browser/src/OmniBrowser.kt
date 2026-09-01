package com.omni.plugin.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.omni.hub.api.HostBridge
import com.omni.hub.api.PluginEntry
import com.omni.plugin.browser.models.*
import com.omni.plugin.browser.state.BrowserStateHolder
import com.omni.plugin.browser.ui.*
import com.omni.plugin.browser.ui.dialogs.*
import com.omni.plugin.browser.utils.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class OmniBrowser : PluginEntry() {

    override fun onCreateView(context: Context, bridge: HostBridge, baseDir: String): View {
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        background = Color(0xFF1F2227),
                        surface = Color(0xFF282C34),
                        primary = Color(0xFF8AB4F8),
                        onSurface = Color(0xFFE8EAED)
                    )
                ) {
                    ChromeBrowserScreen(bridge)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun ChromeBrowserScreen(bridge: HostBridge) {
        val context = LocalContext.current
        val focusManager = LocalFocusManager.current
        val coroutineScope = rememberCoroutineScope()

        val state = remember { BrowserStateHolder(context, bridge, coroutineScope) }

        // --- Activity Launchers ---
        var restoreCallback by remember { mutableStateOf<((android.net.Uri) -> Unit)?>(null) }
        val backupPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri -> if (uri != null) restoreCallback?.invoke(uri) }

        val fileChooserLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            state.activeFileChooserCallback?.onReceiveValue(uris)
            state.activeFileChooserCallback = null
        }

        // --- Lifecycle and Background Loops ---
        LaunchedEffect(Unit) {
            state.init()
        }

        LaunchedEffect(state.trackedDownloadIds.size, state.showDownloadsDialog) {
            while (state.trackedDownloadIds.isNotEmpty() || state.showDownloadsDialog) {
                state.pollDownloads()
                delay(1000)
            }
        }

        LaunchedEffect(state.isAutomating) {
            while (state.isAutomating) {
                delay(1000)
                state.automationElapsedSec++
            }
        }

        DisposableEffect(
            state.isTabSwitcherOpen, state.showSettingsDialog, state.showDownloadsDialog,
            state.showAutomationDialog, state.showAutomationResultDialog, state.editingProfile,
            state.editingShortcut, state.isAddingShortcut, state.isHomeOverlayOpen,
            state.canGoBack, state.currentUrl, state.currentWebView, state.showMenu
        ) {
            bridge.setOnBackPressedHandler { state.handleBackPressed() }
            onDispose { bridge.setOnBackPressedHandler(null) }
        }

        // --- UI Rendering ---
        val activeTab = state.tabs.find { it.id == state.activeTabId }
        val activeProf = state.profiles.find { it.id == activeTab?.profileId } ?: state.profiles.firstOrNull() ?: BrowserProfile("default", "Default", 0xFF8AB4F8)
        val profColor = remember(activeProf.colorValue) { Color(activeProf.colorValue) }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1F2227))
                    .statusBarsPadding()
                    .imePadding()
            ) {
                OmniBrowserTopBar(
                    currentUrl = state.currentUrl,
                    urlInputText = state.urlInputText,
                    onUrlTextChange = { state.urlInputText = it },
                    onNavigate = {
                        state.navigateTo(it)
                        focusManager.clearFocus()
                    },
                    profColor = profColor,
                    tabCount = state.tabs.size,
                    isHomeOverlayOpen = state.isHomeOverlayOpen,
                    onHomeClick = {
                        if (state.currentUrl != "about:blank") {
                            state.isHomeOverlayOpen = !state.isHomeOverlayOpen
                        }
                    },
                    onTabSwitcherClick = {
                        val thumb = state.currentWebView?.captureThumbnail()
                        val bundle = android.os.Bundle()
                        state.currentWebView?.saveState(bundle)
                        state.tabs = state.tabs.map { if (it.id == state.activeTabId) it.copy(stateBundle = bundle, thumbnail = thumb ?: it.thumbnail) else it }
                        CookieManager.getInstance().flush()
                        state.isTabSwitcherOpen = true
                    },
                    showMenu = state.showMenu,
                    onMenuToggle = { state.showMenu = !state.showMenu }
                )

                AnimatedVisibility(
                    visible = state.isLoading,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    LinearProgressIndicator(
                        progress = { state.loadProgress },
                        color = profColor,
                        trackColor = Color(0xFF282C34),
                        modifier = Modifier.fillMaxWidth().height(3.dp)
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                state.containerLayout = this
                                val initialWv = state.poolManager.pool.getOrPut(state.activeTabId) {
                                    state.poolManager.createConfiguredWebView(
                                        tabId = state.activeTabId,
                                        initialUrl = state.currentUrl,
                                        savedState = null,
                                        profileId = activeProf.id,
                                        isDesktop = state.isDesktopMode,
                                        listener = state
                                    )
                                }
                                state.currentWebView = initialWv
                                addView(initialWv)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (state.currentUrl == "about:blank" || state.isHomeOverlayOpen) {
                        SpeedDialView(
                            currentUrl = state.currentUrl,
                            isHomeOverlayOpen = state.isHomeOverlayOpen,
                            activeProfile = activeProf,
                            profColor = profColor,
                            shortcuts = state.shortcuts,
                            faviconCache = state.faviconCache,
                            onFetchFavicon = { state.fetchFavicon(it) },
                            onReturnToLivePage = { state.isHomeOverlayOpen = false },
                            onShortcutClick = { item ->
                                if (state.isHomeOverlayOpen && state.currentUrl != "about:blank") {
                                    state.isHomeOverlayOpen = false
                                    state.createNewTab(item.url)
                                } else {
                                    state.navigateTo(item.url)
                                }
                            },
                            onShortcutLongClick = { item ->
                                bridge.vibrate(40L)
                                state.editingShortcut = item
                            },
                            onAddShortcutClick = { state.isAddingShortcut = true }
                        )
                    }
                }
            }

            // --- Overlays & Screens ---
            BrowserMenuOverlay(
                showMenu = state.showMenu,
                onDismiss = { state.showMenu = false },
                canGoBack = state.canGoBack,
                canGoForward = state.canGoForward,
                currentUrl = state.currentUrl,
                onBackClick = {
                    if (state.canGoBack) state.currentWebView?.goBack()
                    else if (state.currentUrl != "about:blank") state.navigateTo("about:blank")
                },
                onForwardClick = { state.currentWebView?.goForward() },
                onReloadClick = {
                    if (state.currentWebView != null && state.currentUrl != "about:blank") {
                        val wvUrl = state.currentWebView?.url
                        if (wvUrl == null || wvUrl == "about:blank" || wvUrl.isEmpty()) {
                            state.currentWebView?.loadUrl(state.currentUrl)
                        } else {
                            state.currentWebView?.reload()
                        }
                    }
                },
                onOpenAutomation = { state.showAutomationDialog = true },
                onOpenLocalIde = {
                    val localIdeShortcuts = state.shortcuts.filter { it.localSourcePath != null || it.url.contains("/ide/") || it.title.contains("IDE", ignoreCase = true) }
                    val target = localIdeShortcuts.firstOrNull()
                    if (target != null) {
                        val src = target.localSourcePath ?: "/storage/emulated/0/Download/F/index.html"
                        val isolatedPath = "ide/vault_${target.id}/index.html"
                        val vFile = File(bridge.getPluginDir(), isolatedPath)
                        val targetUrl = if (vFile.exists() && vFile.length() > 0) {
                            "file://${vFile.absolutePath}"
                        } else {
                            val (success, resPath) = state.vaultManager.syncLocalFileToVault(src, isolatedPath)
                            if (success) {
                                bridge.showToast("✅ Synced ${target.title} from disk!")
                                resPath
                            } else target.url
                        }
                        state.navigateTo(targetUrl)
                    } else {
                        val defaultFile = File(bridge.getPluginDir(), "ide/index.html")
                        val targetUrl = if (defaultFile.exists() && defaultFile.length() > 0) {
                            "file://${defaultFile.absolutePath}"
                        } else {
                            val (success, resPath) = state.vaultManager.syncLocalFileToVault("/storage/emulated/0/Download/F/index.html", "ide/index.html")
                            if (success) resPath else state.ideInternalPath
                        }
                        state.navigateTo(targetUrl)
                    }
                },
                activeDownloadsCount = state.trackedDownloadIds.size,
                onOpenDownloads = {
                    state.refreshCompletedDownloads()
                    state.showDownloadsDialog = true
                },
                onNewTab = { state.createNewTab() },
                onCloseTab = { state.closeTab(state.activeTabId) },
                isDesktopMode = state.isDesktopMode,
                onToggleDesktopMode = {
                    state.isDesktopMode = !state.isDesktopMode
                    state.currentWebView?.settings?.userAgentString = if (state.isDesktopMode) DESKTOP_USER_AGENT else state.poolManager.mobileUA
                    state.currentWebView?.reload()
                    bridge.showToast(if (state.isDesktopMode) "Desktop Mode Enabled" else "Mobile Mode Enabled")
                },
                onCopyCleanUrl = {
                    if (state.currentUrl != "about:blank") {
                        val cleanUrl = sanitizeUrlForCopy(state.currentUrl)
                        bridge.copyToClipboard(cleanUrl)
                    }
                },
                onInjectEruda = {
                    if (state.currentUrl != "about:blank") {
                        bridge.showToast("🛠️ Initializing Eruda DevTools...")
                        state.currentWebView?.evaluateJavascript(ERUDA_DEVTOOLS_SCRIPT, null)
                    }
                },
                onCaptureDomSnapshot = {
                    state.currentWebView?.evaluateJavascript(DOM_SNAPSHOT_SCRIPT) { html ->
                        if (!html.isNullOrEmpty()) {
                            val rawHtml = if (html.startsWith("\"") && html.endsWith("\"")) {
                                try { org.json.JSONObject("{\"h\":$html}").getString("h") } catch (_: Exception) { html }
                            } else html
                            saveHtmlSnapshot(context, bridge, rawHtml, "DOM_Dump")
                        }
                    }
                },
                onOpenSettings = { state.showSettingsDialog = true },
                onExitBrowser = {
                    state.showMenu = false
                    coroutineScope.launch {
                        delay(400)
                        bridge.close()
                    }
                }
            )

            if (state.isTabSwitcherOpen) {
                TabSwitcherScreen(
                    tabs = state.tabs,
                    activeTabId = state.activeTabId,
                    profiles = state.profiles,
                    selectedProfileId = state.selectedProfileId,
                    onSelectProfile = {
                        state.selectedProfileId = it
                        state.vaultManager.saveProfiles(state.profiles)
                    },
                    onAddProfile = {
                        val nextIdx = state.profiles.size
                        val newId = "profile_${System.currentTimeMillis()}"
                        val newColor = PROFILE_PALETTE[nextIdx % PROFILE_PALETTE.size]
                        val newProf = BrowserProfile(newId, "Account ${nextIdx + 1}", newColor)
                        val updated = state.profiles + newProf
                        state.profiles = updated
                        state.selectedProfileId = newId
                        state.vaultManager.saveProfiles(updated)
                    },
                    onRenameProfile = { prof -> state.editingProfile = prof },
                    onSelectTab = { state.switchToTab(it) },
                    onCloseTab = { state.closeTab(it) },
                    onNewTab = { profId -> state.createNewTab(targetProfileId = profId) },
                    onCloseAll = { state.closeAllTabs() },
                    onCloseSwitcher = { state.isTabSwitcherOpen = false },
                    modifier = Modifier.fillMaxSize().zIndex(10f)
                )
            }

            // --- Modals & Dialogs ---
            if (state.showAutomationDialog) {
                AutomationOrderDialog(
                    profiles = state.profiles,
                    selectedProfileId = state.autoSelectedProfileId,
                    onSelectProfileId = { state.autoSelectedProfileId = it },
                    thinkingLevel = state.autoThinkingLevel,
                    onThinkingLevelChange = { state.autoThinkingLevel = it },
                    systemPresets = state.systemPresets,
                    systemPromptTitle = state.autoSystemPromptTitle,
                    onSystemPromptTitleChange = { state.autoSystemPromptTitle = it },
                    systemPrompt = state.autoSystemPrompt,
                    onSystemPromptChange = { state.autoSystemPrompt = it },
                    fallbackEnabled = state.autoFallbackToLocalPreset,
                    onFallbackEnabledChange = { state.autoFallbackToLocalPreset = it },
                    onSavePreset = { t, b -> state.saveCurrentSystemPreset(t, b) },
                    onDeletePreset = { id -> state.deleteSystemPreset(id) },
                    onSelectPreset = { preset -> state.selectSystemPreset(preset) },
                    userPrompt = state.autoUserPrompt,
                    onUserPromptChange = { state.autoUserPrompt = it },
                    onDismiss = { state.showAutomationDialog = false },
                    onRun = { state.startAutomation() }
                )
            }

            if (state.showAutomationResultDialog) {
                AutomationResultDialog(
                    isAutomating = state.isAutomating,
                    automationStatus = state.automationStatus,
                    automationThoughts = state.automationThoughts,
                    automationResult = state.automationResult,
                    automationError = state.automationError,
                    automationElapsedSec = state.automationElapsedSec,
                    automationWebView = state.automator.headlessWv,
                    onDumpDom = {
                        bridge.showToast("Dumping live DOM to Downloads/OmniSnapshots...")
                        state.automator.dumpDom { rawHtml ->
                            if (rawHtml != null) saveHtmlSnapshot(context, bridge, rawHtml, "Automator_DOM")
                        }
                    },
                    onCopyResult = {
                        bridge.copyToClipboard(state.automationResult)
                        bridge.showToast("Copied result to clipboard!")
                    },
                    onCloseOrStop = { state.stopAutomation() }
                )
            }

            if (state.showDownloadsDialog) {
                DownloadsManagerDialog(
                    bridge = bridge,
                    activeDownloadsList = state.activeDownloadsList,
                    completedFilesList = state.completedFilesList,
                    onCancelDownload = { id ->
                        (context.getSystemService(Context.DOWNLOAD_SERVICE) as? android.app.DownloadManager)?.remove(id)
                        state.trackedDownloadIds.remove(id)
                    },
                    onDeleteFile = { file ->
                        try {
                            file.delete()
                            state.refreshCompletedDownloads()
                            bridge.showToast("Deleted ${file.name}")
                        } catch (e: Exception) {
                            bridge.showToast("Delete failed: ${e.message}")
                        }
                    },
                    onDismiss = { state.showDownloadsDialog = false }
                )
            }

            if (state.showSettingsDialog) {
                SettingsBackupDialog(
                    apiKey = state.solverApiKey,
                    autoSolve = state.autoSolveEnabled,
                    onExportBackup = { state.exportBackup() },
                    onRestoreBackup = {
                        restoreCallback = { uri -> state.restoreBackup(uri) }
                        backupPickerLauncher.launch("application/zip")
                    },
                    onSolveNow = { state.solveCurrentCaptcha() },
                    onClearCookiesAndCache = {
                        CookieManager.getInstance().removeAllCookies(null)
                        state.currentWebView?.clearCache(true)
                        bridge.showToast("Cookies and Cache cleared.")
                    },
                    onSave = { key, auto ->
                        state.solverApiKey = key
                        state.autoSolveEnabled = auto
                        state.vaultManager.saveSolverConfig(key, auto)
                        bridge.showToast("Settings saved!")
                        state.showSettingsDialog = false
                    },
                    onDismiss = { state.showSettingsDialog = false }
                )
            }

            if (state.editingShortcut != null) {
                val targetItem = state.editingShortcut!!
                EditShortcutDialog(
                    shortcut = targetItem,
                    faviconCache = state.faviconCache,
                    onFetchFavicon = { state.fetchFavicon(it) },
                    onPickFile = { onPicked ->
                        bridge.pickFiles("*/*", false) { uris ->
                            val picked = uris.firstOrNull()
                            if (picked != null) {
                                val pathStr = picked.path
                                val directPath = if (pathStr != null && (pathStr.contains("/storage/") || pathStr.contains("/sdcard/"))) {
                                    pathStr.substring(pathStr.indexOf("/storage/").coerceAtLeast(pathStr.indexOf("/sdcard/")))
                                } else picked.toString()
                                onPicked(directPath)
                            }
                        }
                    },
                    onDelete = {
                        val updated = state.shortcuts.filter { it.id != targetItem.id }
                        state.shortcuts = updated
                        state.vaultManager.saveShortcuts(updated)
                        try { File(bridge.getPluginDir(), "ide/vault_${targetItem.id}").deleteRecursively() } catch (_: Exception) {}
                        state.editingShortcut = null
                        bridge.showToast("Shortcut deleted")
                    },
                    onSave = { name, rawUrl ->
                        val trimmedUrl = rawUrl.trim()
                        val isLocal = isLocalFilePath(trimmedUrl)
                        val (finalUrl, srcPath) = if (isLocal) {
                            val isolatedSubPath = "ide/vault_${targetItem.id}/index.html"
                            val (success, vaultedPath) = state.vaultManager.syncLocalFileToVault(trimmedUrl, isolatedSubPath)
                            if (success) {
                                bridge.showToast("✅ Synced to private vault slot!")
                                Pair(vaultedPath, normalizeLocalFilePath(trimmedUrl))
                            } else {
                                val cleanNorm = normalizeLocalFilePath(trimmedUrl)
                                Pair("file://$cleanNorm", cleanNorm)
                            }
                        } else {
                            val webUrl = if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) "https://$trimmedUrl" else trimmedUrl
                            Pair(webUrl, null)
                        }

                        val updated = state.shortcuts.map {
                            if (it.id == targetItem.id) it.copy(title = name.trim().ifEmpty { targetItem.title }, url = finalUrl, localSourcePath = srcPath) else it
                        }
                        state.shortcuts = updated
                        state.vaultManager.saveShortcuts(updated)
                        state.fetchFavicon(extractDomain(finalUrl))
                        state.editingShortcut = null
                    },
                    onDismiss = { state.editingShortcut = null }
                )
            }

            if (state.isAddingShortcut) {
                AddShortcutDialog(
                    faviconCache = state.faviconCache,
                    onFetchFavicon = { state.fetchFavicon(it) },
                    onPickFile = { onPicked ->
                        bridge.pickFiles("*/*", false) { uris ->
                            val picked = uris.firstOrNull()
                            if (picked != null) {
                                val pathStr = picked.path
                                val directPath = if (pathStr != null && (pathStr.contains("/storage/") || pathStr.contains("/sdcard/"))) {
                                    pathStr.substring(pathStr.indexOf("/storage/").coerceAtLeast(pathStr.indexOf("/sdcard/")))
                                } else picked.toString()
                                onPicked(directPath)
                            }
                        }
                    },
                    onAdd = { name, rawUrl ->
                        val trimmedUrl = rawUrl.trim()
                        val isLocal = isLocalFilePath(trimmedUrl)
                        val newShortcutId = "sc_${System.currentTimeMillis()}"
                        val (finalUrl, srcPath) = if (isLocal) {
                            val isolatedSubPath = "ide/vault_$newShortcutId/index.html"
                            val (success, vaultedPath) = state.vaultManager.syncLocalFileToVault(trimmedUrl, isolatedSubPath)
                            if (success) {
                                bridge.showToast("✅ Isolated vault created for local app")
                                Pair(vaultedPath, normalizeLocalFilePath(trimmedUrl))
                            } else {
                                val cleanNorm = normalizeLocalFilePath(trimmedUrl)
                                Pair("file://$cleanNorm", cleanNorm)
                            }
                        } else {
                            val webUrl = if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) "https://$trimmedUrl" else trimmedUrl
                            Pair(webUrl, null)
                        }
                        val title = name.trim().ifEmpty { if (isLocal) "Local App" else extractDomain(finalUrl) }
                        val newItem = ShortcutItem(
                            id = newShortcutId,
                            title = title,
                            url = finalUrl,
                            iconText = if (isLocal) "💻" else title.take(1).uppercase(),
                            colorValue = 0xFF58A6FF,
                            localSourcePath = srcPath
                        )
                        val updated = state.shortcuts + newItem
                        state.shortcuts = updated
                        state.vaultManager.saveShortcuts(updated)
                        state.fetchFavicon(extractDomain(finalUrl))
                        state.isAddingShortcut = false
                    },
                    onDismiss = { state.isAddingShortcut = false }
                )
            }

            if (state.editingProfile != null) {
                val targetProf = state.editingProfile!!
                RenameProfileDialog(
                    profile = targetProf,
                    onSave = { newName ->
                        val updated = state.profiles.map { if (it.id == targetProf.id) it.copy(name = newName) else it }
                        state.profiles = updated
                        state.vaultManager.saveProfiles(updated)
                        state.editingProfile = null
                    },
                    onDismiss = { state.editingProfile = null }
                )
            }

            UndoBanner(
                visible = state.showUndoBanner,
                message = state.undoMessage,
                onUndo = {
                    state.undoJob?.cancel()
                    state.poolManager.pendingPurge.forEach { (id, wv) -> state.poolManager.pool[id] = wv }
                    state.poolManager.pendingPurge.clear()

                    val backup = state.lastClosedTabsSnapshot
                    if (backup != null && backup.isNotEmpty()) {
                        state.tabs = backup
                        val targetId = if (backup.any { it.id == state.lastActiveTabIdSnapshot }) state.lastActiveTabIdSnapshot!! else backup.first().id
                        state.activeTabId = targetId
                        state.vaultManager.saveSession(backup, targetId)
                        state.attachTabWebView(targetId)
                    }
                    state.showUndoBanner = false
                    state.lastClosedTabsSnapshot = null
                },
                onDismiss = {
                    state.undoJob?.cancel()
                    state.showUndoBanner = false
                    state.poolManager.purgePending(state.containerLayout)
                    state.lastClosedTabsSnapshot = null
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp)
                    .zIndex(30f)
            )
        }
    }
}