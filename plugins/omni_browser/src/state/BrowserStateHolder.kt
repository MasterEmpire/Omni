package com.omni.plugin.browser.state

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.runtime.*
import com.omni.hub.api.HostBridge
import com.omni.plugin.browser.engine.*
import com.omni.plugin.browser.models.*
import com.omni.plugin.browser.services.CaptchaSolverService
import com.omni.plugin.browser.services.DownloadController
import com.omni.plugin.browser.storage.VaultManager
import com.omni.plugin.browser.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder

class BrowserStateHolder(
    val context: Context,
    val bridge: HostBridge,
    val coroutineScope: CoroutineScope
) : WebViewEventListener, AutomationCallback {

    // Subsystems
    val vaultManager = VaultManager(context, bridge)
    val downloadController = DownloadController(context, bridge)
    val captchaService = CaptchaSolverService(bridge)
    val poolManager = WebViewPoolManager(context, bridge)
    val automator = AiStudioAutomator(context, bridge)

    // UI & Profile State
    var profiles by mutableStateOf(listOf(BrowserProfile("default", "Default", 0xFF2979FF)))
    var selectedProfileId by mutableStateOf("default")
    var editingProfile by mutableStateOf<BrowserProfile?>(null)

    // Server & Shortcuts State
    var localServerPort by mutableIntStateOf(8080)
    val ideInternalPath: String get() = "http://localhost:$localServerPort/"
    val defaultShortcuts = listOf(
        ShortcutItem(title = "Local IDE", url = "http://localhost:8080/", iconText = "💻", colorValue = 0xFF58A6FF, localSourcePath = "/storage/emulated/0/Download/F/index.html"),
        ShortcutItem(title = "Google", url = "https://www.google.com", iconText = "G", colorValue = 0xFF4285F4),
        ShortcutItem(title = "YouTube", url = "https://m.youtube.com", iconText = "▶", colorValue = 0xFFEA4335),
        ShortcutItem(title = "Bot Test", url = "https://bot.sannysoft.com/", iconText = "🕵️", colorValue = 0xFF34A853),
        ShortcutItem(title = "GitHub", url = "https://github.com", iconText = "⌥", colorValue = 0xFF24292E),
        ShortcutItem(title = "Reddit", url = "https://reddit.com", iconText = "R", colorValue = 0xFFFF4500),
        ShortcutItem(title = "DuckDuckGo", url = "https://duckduckgo.com", iconText = "D", colorValue = 0xFFDE5833),
        ShortcutItem(title = "Wikipedia", url = "https://wikipedia.org", iconText = "W", colorValue = 0xFF5F6368),
        ShortcutItem(title = "BrowserLeaks", url = "https://browserleaks.com/javascript", iconText = "🔍", colorValue = 0xFF9C27B0)
    )
    var shortcuts by mutableStateOf(defaultShortcuts)
    val faviconCache = mutableStateMapOf<String, Bitmap>()
    var editingShortcut by mutableStateOf<ShortcutItem?>(null)
    var isAddingShortcut by mutableStateOf(false)

    // Tabs & Navigation State
    var tabs by mutableStateOf(listOf(BrowserTab(id = "tab_1", title = "New Tab", url = "about:blank", profileId = "default")))
    var activeTabId by mutableStateOf("tab_1")
    var isTabSwitcherOpen by mutableStateOf(false)
    var isHomeOverlayOpen by mutableStateOf(true)

    var currentUrl by mutableStateOf("about:blank")
    var urlInputText by mutableStateOf("")
    var pageTitle by mutableStateOf("New Tab")
    var isLoading by mutableStateOf(false)
    var loadProgress by mutableFloatStateOf(0f)
    val tabProgressMap = mutableStateMapOf<String, Int>()
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var isDesktopMode by mutableStateOf(false)
    var showMenu by mutableStateOf(false)
    var previousActiveTabId by mutableStateOf<String?>(null)

    var containerLayout: FrameLayout? = null
    var currentWebView: WebView? = null

    // Temporary Popup Tab & Opener Tracking
    val popupTabs = mutableSetOf<String>()
    val tabOpenerMap = mutableMapOf<String, String>()

    // Dialog Visibility States
    var showSettingsDialog by mutableStateOf(false)
    var showDownloadsDialog by mutableStateOf(false)
    var showSmartNotesDialog by mutableStateOf(false)
    var smartNotes by mutableStateOf<List<SmartNote>>(emptyList())
    val trackedDownloadIds = mutableStateListOf<Long>()
    var activeDownloadsList by mutableStateOf<List<ActiveDownloadItem>>(emptyList())
    var completedFilesList by mutableStateOf<List<File>>(emptyList())

    // Background Audio & Media State
    var isBackgroundAudioEnabled by mutableStateOf(true)
    var currentMediaTitle by mutableStateOf("")
    var currentMediaArtist by mutableStateOf("")
    var isMediaPlaying by mutableStateOf(false)
    var activeMediaTabId by mutableStateOf<String?>(null)

    // Solver States
    var solverApiKey by mutableStateOf("")
    var autoSolveEnabled by mutableStateOf(true)
    var forceDarkWebPages by mutableStateOf(false)
    var isErudaEnabled by mutableStateOf(false)

    // Automator States
    var showAutomationDialog by mutableStateOf(false)
    var showAutomationResultDialog by mutableStateOf(false)
    var autoSelectedProfileId by mutableStateOf("default")
    var autoSelectedModel by mutableStateOf("Gemini 3.7 Flash")
    var autoThinkingLevel by mutableStateOf("Default")
    var autoTemporaryChat by mutableStateOf(false)
    var autoAttachments by mutableStateOf<List<AutomationAttachment>>(emptyList())
    var systemPresets by mutableStateOf<List<SystemInstructionPreset>>(emptyList())
    var autoSystemPromptTitle by mutableStateOf("")
    var autoSystemPrompt by mutableStateOf("")
    var autoFallbackToLocalPreset by mutableStateOf(true)
    var autoUserPrompt by mutableStateOf("")
    var promptSteps by mutableStateOf(listOf(SequentialPromptStep(prompt = "")))
    var isAutomating by mutableStateOf(false)

    // Live On-Screen Auto-Pilot State
    var isLiveAutoPilotEnabled by mutableStateOf(false)
    var isAutoPilotConfirmEnabled by mutableStateOf(false)
    var pendingAutoPilotPayload by mutableStateOf<String?>(null)
    var pendingAutoPilotActionType by mutableStateOf<String?>(null) // "CXP", "PYTHON", "PULL"
    var showAutoPilotConfirmDialog by mutableStateOf(false)
    var liveAutoPilotStatus by mutableStateOf("Watching")
    var automationStatus by mutableStateOf("Idle")
=== REPLACE
<<< FIND
    fun toggleLiveAutoPilot() {
        isLiveAutoPilotEnabled = !isLiveAutoPilotEnabled
        bridge.log("AUTOPILOT_PIPELINE", "🔘 [TOGGLE] User toggled Live Auto-Pilot: enabled=$isLiveAutoPilotEnabled")
        if (isLiveAutoPilotEnabled) {
            com.omni.plugin.browser.engine.LiveAutoPilotEngine.arm(currentWebView, bridge)
        } else {
            showCxpPill = false
            cxpPillStatus = null
            cxpPillDismissJob?.cancel()
            showAutoPilotConfirmDialog = false
            pendingAutoPilotCxp = null
            com.omni.plugin.browser.engine.LiveAutoPilotEngine.disarm(currentWebView, bridge)
        }
    }

    fun toggleAutoPilotConfirm() {
        isAutoPilotConfirmEnabled = !isAutoPilotConfirmEnabled
        try {
            val prefs = context.getSharedPreferences("omni_browser_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("autopilot_confirm_enabled", isAutoPilotConfirmEnabled).apply()
        } catch (_: Exception) {}
        bridge.showToast(if (isAutoPilotConfirmEnabled) "🛡️ Auto-Pilot: Confirmation Modal ON" else "⚡ Auto-Pilot: Auto-Commit ON")
    }

    fun approveAutoPilotCxp() {
        val xml = pendingAutoPilotCxp ?: return
        showAutoPilotConfirmDialog = false
        pendingAutoPilotCxp = null
        commitCxpToIde(xml)
    }

    fun rejectAutoPilotCxp() {
        showAutoPilotConfirmDialog = false
        pendingAutoPilotCxp = null
        bridge.showToast("🛑 Patch discarded by user")
        bridge.log("AUTOPILOT_PIPELINE", "🛑 [DISCARDED] User rejected patch in confirmation dialog.")
    }
=== REPLACE
    fun toggleLiveAutoPilot() {
        if (!isLiveAutoPilotEnabled && !currentUrl.contains("aistudio.google.com")) {
            bridge.showToast("⚡ Auto-Pilot is only supported on Google AI Studio")
            return
        }
        isLiveAutoPilotEnabled = !isLiveAutoPilotEnabled
        bridge.log("AUTOPILOT_PIPELINE", "🔘 [TOGGLE] User toggled Live Auto-Pilot: enabled=$isLiveAutoPilotEnabled")
        if (isLiveAutoPilotEnabled) {
            com.omni.plugin.browser.engine.LiveAutoPilotEngine.arm(currentWebView, bridge)
        } else {
            showCxpPill = false
            cxpPillStatus = null
            cxpPillDismissJob?.cancel()
            showAutoPilotConfirmDialog = false
            pendingAutoPilotPayload = null
            pendingAutoPilotActionType = null
            com.omni.plugin.browser.engine.LiveAutoPilotEngine.disarm(currentWebView, bridge)
        }
    }

    fun toggleAutoPilotConfirm() {
        isAutoPilotConfirmEnabled = !isAutoPilotConfirmEnabled
        try {
            val prefs = context.getSharedPreferences("omni_browser_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("autopilot_confirm_enabled", isAutoPilotConfirmEnabled).apply()
        } catch (_: Exception) {}
        bridge.showToast(if (isAutoPilotConfirmEnabled) "🛡️ Auto-Pilot: Universal Confirmation ON" else "⚡ Auto-Pilot: Autonomous Mode ON")
    }

    fun approveAutoPilotAction() {
        val payload = pendingAutoPilotPayload ?: return
        val type = pendingAutoPilotActionType ?: "CXP"
        showAutoPilotConfirmDialog = false
        pendingAutoPilotPayload = null
        pendingAutoPilotActionType = null

        when (type) {
            "CXP" -> commitCxpToIde(payload)
            "PYTHON" -> executeLivePythonDirectly(payload)
            "PULL" -> executeFilePullDirectly(payload)
        }
    }

    fun rejectAutoPilotAction() {
        val type = pendingAutoPilotActionType ?: "Action"
        showAutoPilotConfirmDialog = false
        pendingAutoPilotPayload = null
        pendingAutoPilotActionType = null
        bridge.showToast("🛑 $type discarded by user")
        bridge.log("AUTOPILOT_PIPELINE", "🛑 [DISCARDED] User rejected $type in confirmation dialog.")
    }

    // CXP Auto-Commit Banner State
    var cxpPillStatus by mutableStateOf<String?>(null)
    var cxpPillMessage by mutableStateOf("")
    var cxpPillIdeTabId by mutableStateOf<String?>(null)
    var showCxpPill by mutableStateOf(false)
    private var cxpPillDismissJob: Job? = null
    var automationThoughts by mutableStateOf("")
    var automationResult by mutableStateOf("")
    var automationError by mutableStateOf<String?>(null)
    var automationElapsedSec by mutableIntStateOf(0)

    // Undo Banner State
    var lastClosedTabsSnapshot by mutableStateOf<List<BrowserTab>?>(null)
    var lastActiveTabIdSnapshot by mutableStateOf<String?>(null)
    var undoMessage by mutableStateOf("")
    var showUndoBanner by mutableStateOf(false)
    var undoJob by mutableStateOf<Job?>(null)

    // Download Completed Pill State
    var latestDownloadedFile by mutableStateOf<File?>(null)
    var showDownloadBanner by mutableStateOf(false)
    var downloadBannerJob by mutableStateOf<Job?>(null)

    fun notifyDownloadCompleted(file: File) {
        latestDownloadedFile = file
        showDownloadBanner = true
        downloadBannerJob?.cancel()
        downloadBannerJob = coroutineScope.launch {
            delay(6500)
            showDownloadBanner = false
        }
    }

    // File Chooser Callback & Launcher
    var activeFileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var fileChooserLauncherBlock: ((Intent) -> Unit)? = null

    fun registerFileChooserLauncher(launcher: ((Intent) -> Unit)?) {
        fileChooserLauncherBlock = launcher
    }

    fun init() {
        vaultManager.resurrectFromVault()

        try {
            val prefs = context.getSharedPreferences("omni_browser_prefs", Context.MODE_PRIVATE)
            isAutoPilotConfirmEnabled = prefs.getBoolean("autopilot_confirm_enabled", false)
        } catch (_: Exception) {}

        vaultManager.loadSolverConfig()?.let { config ->
            solverApiKey = config.apiKey
            autoSolveEnabled = config.autoSolve
            forceDarkWebPages = config.forceDark
            localServerPort = config.localPort
            isErudaEnabled = config.erudaEnabled
            isDesktopMode = config.desktopMode
            isBackgroundAudioEnabled = config.backgroundAudio
            poolManager.localPort = config.localPort
            poolManager.updateForceDark(config.forceDark)
        }

        vaultManager.loadShortcuts()?.let { loaded ->
            if (loaded.isNotEmpty()) {
                val migrated = loaded.map {
                    if (it.url.startsWith("file://") && it.url.contains("/ide/")) {
                        it.copy(url = convertLocalFileToLocalhost(it.url, localServerPort))
                    } else it
                }
                shortcuts = migrated
                if (migrated != loaded) {
                    vaultManager.saveShortcuts(migrated)
                }
            }
        }

        vaultManager.loadProfiles()?.let { loaded ->
            if (loaded.isNotEmpty()) {
                val upgraded = loaded.mapIndexed { idx, prof ->
                    prof.copy(colorValue = PROFILE_PALETTE[idx % PROFILE_PALETTE.size])
                }
                profiles = upgraded
                vaultManager.saveProfiles(upgraded)
            }
        }

        vaultManager.loadSystemPresets()?.let { loaded ->
            if (loaded.isNotEmpty()) systemPresets = loaded
        }

        vaultManager.loadSmartNotes()?.let { loaded ->
            if (loaded.isNotEmpty()) smartNotes = loaded
        }

        vaultManager.loadSession()?.let { (loadedTabs, savedActiveId, savedProfileId) ->
            val cleanedTabs = loadedTabs.filter { !it.id.startsWith("tab_landing_") }
            if (cleanedTabs.isNotEmpty()) {
                val migratedTabs = cleanedTabs.map {
                    if (it.url.startsWith("file://") && it.url.contains("/ide/")) {
                        it.copy(url = convertLocalFileToLocalhost(it.url, localServerPort))
                    } else it
                }
                tabs = migratedTabs
                val targetTab = migratedTabs.find { it.id == savedActiveId } ?: migratedTabs.last()
                activeTabId = targetTab.id
                val resolvedProfileId = savedProfileId?.takeIf { pId -> profiles.any { it.id == pId } }
                    ?: targetTab.profileId.takeIf { pId -> profiles.any { it.id == pId } }
                    ?: "default"
                selectedProfileId = resolvedProfileId
                autoSelectedProfileId = resolvedProfileId
                currentUrl = targetTab.url
                urlInputText = ""
                pageTitle = targetTab.title
                isHomeOverlayOpen = true
                if (containerLayout != null) {
                    attachTabWebView(targetTab.id)
                }
            }
        }
    }

    fun changeSelectedProfile(newProfileId: String) {
        selectedProfileId = newProfileId
        val currentTab = tabs.find { it.id == activeTabId }
        if (currentTab != null && currentTab.url == "about:blank") {
            tabs = tabs.map {
                if (it.id == activeTabId || it.url == "about:blank") it.copy(profileId = newProfileId) else it
            }
            poolManager.pool.remove(activeTabId)?.let { wv ->
                wv.onPause()
                containerLayout?.removeView(wv)
                wv.destroy()
            }
            attachTabWebView(activeTabId)
        }
        vaultManager.saveProfiles(profiles)
        vaultManager.saveSession(tabs, activeTabId)
    }

    fun refreshCompletedDownloads() {
        completedFilesList = downloadController.fetchCompletedDownloads()
    }

    fun pollDownloads() {
        if (trackedDownloadIds.isNotEmpty()) {
            val (updated, finished, finishedFiles) = downloadController.queryActiveDownloads(trackedDownloadIds)
            activeDownloadsList = updated
            if (finished.isNotEmpty()) {
                trackedDownloadIds.removeAll(finished)
                refreshCompletedDownloads()
                finishedFiles.firstOrNull()?.let { file ->
                    notifyDownloadCompleted(file)
                }
            }
        }
        if (showDownloadsDialog) {
            refreshCompletedDownloads()
        }
    }

    fun attachTabWebView(targetTabId: String) {
        val container = containerLayout ?: return
        val now = System.currentTimeMillis()
        tabs = tabs.map { if (it.id == targetTabId) it.copy(lastAccessedTime = now) else it }

        poolManager.pool.forEach { (id, wv) ->
            if (id != targetTabId) {
                if (!isBackgroundAudioEnabled || id != activeMediaTabId) {
                    wv.onPause()
                }
            }
        }
        CookieManager.getInstance().flush()

        val targetTab = tabs.find { it.id == targetTabId } ?: return
        selectedProfileId = targetTab.profileId
        isDesktopMode = targetTab.isDesktop
        val isNewInstance = !poolManager.pool.containsKey(targetTabId)

        val targetWv = poolManager.pool.getOrPut(targetTabId) {
            poolManager.createConfiguredWebView(
                tabId = targetTabId,
                initialUrl = targetTab.url,
                savedState = targetTab.stateBundle,
                profileId = targetTab.profileId,
                isDesktop = targetTab.isDesktop,
                listener = this
            )
        }

        if (isNewInstance) {
            poolManager.pruneHotPool(targetTabId, tabs, { id, b ->
                tabs = tabs.map { if (it.id == id) it.copy(stateBundle = b) else it }
            }, containerLayout)
        }

        val needsUrlLoad = targetTab.url.isNotEmpty() && targetTab.url != "about:blank" &&
            (targetWv.url == null || targetWv.url == "about:blank" || (isNewInstance && targetWv.copyBackForwardList().size == 0))

        if (needsUrlLoad) {
            targetWv.loadUrl(targetTab.url)
        }

        // Attach new target view BEFORE removing old view to eliminate blank-screen flashing
        if (targetWv.parent !== container) {
            (targetWv.parent as? ViewGroup)?.removeView(targetWv)
            container.addView(targetWv)
        }
        targetWv.onResume()
        targetWv.visibility = android.view.View.VISIBLE

        // Safely remove sibling views now that targetWv is active on screen
        for (i in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(i)
            if (child !== targetWv) {
                container.removeViewAt(i)
            }
        }

        targetWv.requestLayout()
        targetWv.invalidate()
        targetWv.post {
            targetWv.requestLayout()
            targetWv.invalidate()
        }
        currentWebView = targetWv

        currentUrl = targetTab.url
        urlInputText = if (targetTab.url == "about:blank") "" else targetTab.url
        pageTitle = targetTab.title
        canGoBack = targetWv.canGoBack()
        canGoForward = targetWv.canGoForward()

        val recordedProg = tabProgressMap[targetTabId] ?: targetWv.progress
        if (targetTab.url == "about:blank" || recordedProg >= 100 || recordedProg <= 0) {
            isLoading = false
            loadProgress = if (recordedProg >= 100) 1f else 0f
        } else {
            loadProgress = recordedProg / 100f
            isLoading = true
        }
    }

    fun createNewTab(
        targetUrl: String = "about:blank",
        targetProfileId: String = selectedProfileId,
        insertAtIndex: Int? = null
    ) {
        previousActiveTabId = activeTabId
        val thumb = currentWebView?.captureThumbnail()
        val bundle = Bundle()
        currentWebView?.saveState(bundle)

        val updatedTabs = tabs.map {
            if (it.id == activeTabId) it.copy(thumbnail = thumb ?: it.thumbnail, stateBundle = bundle) else it
        }.toMutableList()

        val newId = "tab_${System.currentTimeMillis()}"
        val newTab = BrowserTab(
            id = newId,
            title = if (targetUrl == "about:blank") "New Tab" else targetUrl,
            url = targetUrl,
            lastAccessedTime = System.currentTimeMillis(),
            profileId = targetProfileId
        )

        if (insertAtIndex != null && insertAtIndex in 0..updatedTabs.size) {
            updatedTabs.add(insertAtIndex, newTab)
        } else {
            updatedTabs.add(newTab)
        }

        tabs = updatedTabs
        activeTabId = newId
        isTabSwitcherOpen = false

        vaultManager.saveSession(tabs, newId, targetProfileId)
        attachTabWebView(newId)
    }

    fun switchToTab(targetId: String) {
        if (targetId == activeTabId) {
            isTabSwitcherOpen = false
            isHomeOverlayOpen = false
            return
        }
        previousActiveTabId = activeTabId
        val thumb = currentWebView?.captureThumbnail()
        val bundle = Bundle()
        currentWebView?.saveState(bundle)

        tabs = tabs.map {
            if (it.id == activeTabId) it.copy(thumbnail = thumb ?: it.thumbnail, stateBundle = bundle) else it
        }
        activeTabId = targetId
        val targetTab = tabs.find { it.id == targetId }
        if (targetTab != null) {
            selectedProfileId = targetTab.profileId
        }
        isTabSwitcherOpen = false
        isHomeOverlayOpen = false

        vaultManager.saveSession(tabs, targetId, selectedProfileId)
        attachTabWebView(targetId)
    }

    // Interactive Tab Swipe Parallax State
    var isTabSwiping by mutableStateOf(false)
    val tabSwipeOffset = androidx.compose.animation.core.Animatable(0f)
    var swipeTargetTab by mutableStateOf<BrowserTab?>(null)
    var swipeScreenWidth by mutableFloatStateOf(1080f)
    private var dragJob: Job? = null

    fun onTopBarDragStart() {
        if (tabs.size <= 1) return
        val currentIdx = tabs.indexOfFirst { it.id == activeTabId }
        if (currentIdx == -1) return

        dragJob?.cancel()
        try {
            currentWebView?.captureThumbnail()?.let { freshThumb ->
                tabs = tabs.map { if (it.id == activeTabId) it.copy(thumbnail = freshThumb) else it }
            }
        } catch (_: Exception) {}

        isTabSwiping = true
        swipeTargetTab = null
        dragJob = coroutineScope.launch {
            tabSwipeOffset.snapTo(0f)
        }
    }

    fun onTopBarDrag(dragAmount: Float) {
        if (!isTabSwiping || tabs.size <= 1) return
        val currentIdx = tabs.indexOfFirst { it.id == activeTabId }
        if (currentIdx == -1) return

        val currentOffset = tabSwipeOffset.value
        val rawNext = currentOffset + dragAmount

        val targetTab: BrowserTab?
        val appliedOffset: Float

        if (rawNext < 0) {
            val nextIdx = currentIdx + 1
            if (nextIdx in tabs.indices) {
                targetTab = tabs[nextIdx]
                appliedOffset = rawNext
            } else {
                targetTab = null
                appliedOffset = currentOffset + dragAmount * 0.25f
            }
        } else if (rawNext > 0) {
            val prevIdx = currentIdx - 1
            if (prevIdx in tabs.indices) {
                targetTab = tabs[prevIdx]
                appliedOffset = rawNext
            } else {
                targetTab = null
                appliedOffset = currentOffset + dragAmount * 0.25f
            }
        } else {
            targetTab = null
            appliedOffset = 0f
        }

        swipeTargetTab = targetTab
        dragJob?.cancel()
        dragJob = coroutineScope.launch {
            tabSwipeOffset.snapTo(appliedOffset)
        }
    }

    fun onTopBarDragEnd() {
        if (!isTabSwiping) return
        dragJob?.cancel()
        val currentOffset = tabSwipeOffset.value
        val target = swipeTargetTab
        val screenW = if (swipeScreenWidth > 0) swipeScreenWidth else 1080f
        val density = context.resources.displayMetrics.density
        // Flick-responsive threshold: 55dp or 8% screen width so natural thumb flicks always register
        val threshold = minOf(55f * density, screenW * 0.08f)

        coroutineScope.launch {
            if (target != null && Math.abs(currentOffset) >= threshold) {
                val settleTarget = if (currentOffset < 0) -screenW else screenW
                tabSwipeOffset.animateTo(
                    targetValue = settleTarget,
                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 140, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                )
                switchToTab(target.id)
                bridge.vibrate(25L)
            } else {
                tabSwipeOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 450f)
                )
            }
            tabSwipeOffset.snapTo(0f)
            swipeTargetTab = null
            isTabSwiping = false
        }
    }

    fun onTopBarDragCancel() {
        if (!isTabSwiping) return
        dragJob?.cancel()
        coroutineScope.launch {
            tabSwipeOffset.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 450f))
            tabSwipeOffset.snapTo(0f)
            swipeTargetTab = null
            isTabSwiping = false
        }
    }

    fun switchToNextTab(): Boolean {
        val currentIdx = tabs.indexOfFirst { it.id == activeTabId }
        if (currentIdx != -1 && currentIdx < tabs.lastIndex) {
            val nextTab = tabs[currentIdx + 1]
            switchToTab(nextTab.id)
            bridge.vibrate(25L)
            return true
        }
        return false
    }

    fun switchToPreviousTab(): Boolean {
        val currentIdx = tabs.indexOfFirst { it.id == activeTabId }
        if (currentIdx > 0) {
            val prevTab = tabs[currentIdx - 1]
            switchToTab(prevTab.id)
            bridge.vibrate(25L)
            return true
        }
        return false
    }

    fun closeTab(targetId: String, suppressUndo: Boolean = false) {
        poolManager.purgePending(containerLayout)

        if (previousActiveTabId == targetId) {
            previousActiveTabId = null
        }
        val closedTab = tabs.find { it.id == targetId }
        val isAutoDismissed = popupTabs.remove(targetId)
        val openerId = tabOpenerMap.remove(targetId)

        if (!suppressUndo && !isAutoDismissed) {
            lastClosedTabsSnapshot = tabs
            lastActiveTabIdSnapshot = activeTabId
            val tabTitle = if (closedTab?.url == "about:blank") "New tab" else (closedTab?.title?.take(18) ?: "Tab")
            undoMessage = "$tabTitle closed"
            showUndoBanner = true
            undoJob?.cancel()
            undoJob = coroutineScope.launch {
                delay(4500)
                showUndoBanner = false
                poolManager.purgePending(containerLayout)
                lastClosedTabsSnapshot = null
            }
        }

        tabProgressMap.remove(targetId)
        poolManager.pool.remove(targetId)?.let { wv ->
            wv.onPause()
            containerLayout?.removeView(wv)
            poolManager.pendingPurge[targetId] = wv
        }

        val currentIdx = tabs.indexOfFirst { it.id == targetId }
        val remainingTabs = tabs.filter { it.id != targetId }

        if (remainingTabs.isEmpty()) {
            val newId = "tab_${System.currentTimeMillis()}"
            val freshTab = BrowserTab(id = newId, title = "New Tab", url = "about:blank", profileId = selectedProfileId)
            tabs = listOf(freshTab)
            activeTabId = newId
            vaultManager.saveSession(tabs, newId)
            attachTabWebView(newId)
        } else {
            tabs = remainingTabs
            if (targetId == activeTabId) {
                val targetNextTab = if (openerId != null && remainingTabs.any { it.id == openerId }) {
                    remainingTabs.first { it.id == openerId }
                } else if (previousActiveTabId != null && remainingTabs.any { it.id == previousActiveTabId }) {
                    remainingTabs.first { it.id == previousActiveTabId }
                } else {
                    val nextIdx = (currentIdx - 1).coerceAtLeast(0).coerceAtMost(remainingTabs.size - 1)
                    remainingTabs[nextIdx]
                }
                activeTabId = targetNextTab.id
                vaultManager.saveSession(tabs, targetNextTab.id)
                attachTabWebView(targetNextTab.id)
            } else {
                vaultManager.saveSession(tabs, activeTabId)
            }
        }
    }

    fun closeAllTabs() {
        poolManager.purgePending(containerLayout)

        val count = tabs.size
        lastClosedTabsSnapshot = tabs
        lastActiveTabIdSnapshot = activeTabId
        undoMessage = "$count tabs closed"
        showUndoBanner = true
        undoJob?.cancel()
        undoJob = coroutineScope.launch {
            delay(4500)
            showUndoBanner = false
            poolManager.purgePending(containerLayout)
            lastClosedTabsSnapshot = null
        }

        previousActiveTabId = null
        tabProgressMap.clear()
        poolManager.pool.forEach { (id, wv) ->
            wv.onPause()
            poolManager.pendingPurge[id] = wv
        }
        poolManager.pool.clear()
        containerLayout?.removeAllViews()

        val newId = "tab_${System.currentTimeMillis()}"
        tabs = listOf(BrowserTab(id = newId, title = "New Tab", url = "about:blank", profileId = selectedProfileId))
        activeTabId = newId
        isTabSwitcherOpen = false

        vaultManager.saveSession(tabs, newId)
        attachTabWebView(newId)
    }

    fun reorderTabs(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in tabs.indices || toIndex !in tabs.indices || fromIndex == toIndex) return
        val list = tabs.toMutableList()
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        tabs = list
    }

    fun saveCurrentTabOrder() {
        vaultManager.saveSession(tabs, activeTabId, selectedProfileId)
    }

    fun navigateTo(rawInput: String) {
        val input = rawInput.trim()
        if (input.isEmpty()) return

        val target = when {
            input == "about:blank" -> "about:blank"
            input.startsWith("file://") && input.contains("/ide/") -> convertLocalFileToLocalhost(input, localServerPort)
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.startsWith("file://") -> input
            input.startsWith("/storage/") || input.startsWith("/") -> "file://$input"
            input.startsWith("localhost") || input.startsWith("127.0.0.1") || input.startsWith("192.168.") || input.startsWith("10.") || input.startsWith("172.") -> "http://$input"
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=${URLEncoder.encode(input, "UTF-8")}"
        }

        if (isHomeOverlayOpen && currentUrl != "about:blank") {
            isHomeOverlayOpen = false
            createNewTab(target)
            return
        }

        isHomeOverlayOpen = false
        urlInputText = if (target == "about:blank") "" else target
        currentUrl = target
        tabs = tabs.map { if (it.id == activeTabId) it.copy(url = target) else it }

        if (target == "about:blank") {
            isLoading = false
            loadProgress = 0f
            tabProgressMap[activeTabId] = 0
        } else {
            isLoading = true
            loadProgress = 0.05f
            tabProgressMap[activeTabId] = 5
        }

        currentWebView?.loadUrl(target)
    }

    fun toggleDesktopMode() {
        val currentTab = tabs.find { it.id == activeTabId } ?: return
        val newDesktop = !currentTab.isDesktop
        isDesktopMode = newDesktop

        tabs = tabs.map { if (it.id == activeTabId) it.copy(isDesktop = newDesktop) else it }
        vaultManager.saveSession(tabs, activeTabId)

        poolManager.setTabDesktopMode(activeTabId, newDesktop)

        val targetUrl = convertUrlForDesktop(currentUrl, newDesktop)
        if (targetUrl != currentUrl) {
            navigateTo(targetUrl)
        } else {
            currentWebView?.reload()
        }

        bridge.showToast(if (newDesktop) "Desktop Site (1280px) Enabled" else "Mobile Site Enabled")
    }

    fun toggleBackgroundAudio() {
        isBackgroundAudioEnabled = !isBackgroundAudioEnabled
        if (!isBackgroundAudioEnabled) {
            bridge.stopMediaPlayback()
            currentWebView?.evaluateJavascript("window.__omniTogglePlay(false);", null)
            isMediaPlaying = false
            bridge.showToast("Background Audio Disabled")
        } else {
            bridge.showToast("Background Audio Enabled")
            if (currentMediaTitle.isNotEmpty() && isMediaPlaying) {
                activeMediaTabId?.let { tabId ->
                    bridge.startMediaPlayback(currentMediaTitle, currentMediaArtist, true) { shouldPlay: Boolean ->
                        poolManager.pool[tabId]?.evaluateJavascript("window.__omniTogglePlay($shouldPlay);", null)
                    }
                }
            }
        }
    }

    fun solveCurrentCaptcha() {
        captchaService.scanAndSolve(solverApiKey, currentUrl, currentWebView) { siteKey ->
            coroutineScope.launch {
                captchaService.executeSolver(solverApiKey, siteKey, currentUrl, currentWebView)
            }
        }
    }

    fun toggleLiveAutoPilot() {
        isLiveAutoPilotEnabled = !isLiveAutoPilotEnabled
        bridge.log("AUTOPILOT_PIPELINE", "🔘 [TOGGLE] User toggled Live Auto-Pilot: enabled=$isLiveAutoPilotEnabled")
        if (isLiveAutoPilotEnabled) {
            com.omni.plugin.browser.engine.LiveAutoPilotEngine.arm(currentWebView, bridge)
        } else {
            showCxpPill = false
            cxpPillStatus = null
            cxpPillDismissJob?.cancel()
            showAutoPilotConfirmDialog = false
            pendingAutoPilotCxp = null
            com.omni.plugin.browser.engine.LiveAutoPilotEngine.disarm(currentWebView, bridge)
        }
    }

    fun toggleAutoPilotConfirm() {
        isAutoPilotConfirmEnabled = !isAutoPilotConfirmEnabled
        try {
            val prefs = context.getSharedPreferences("omni_browser_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("autopilot_confirm_enabled", isAutoPilotConfirmEnabled).apply()
        } catch (_: Exception) {}
        bridge.showToast(if (isAutoPilotConfirmEnabled) "🛡️ Auto-Pilot: Confirmation Modal ON" else "⚡ Auto-Pilot: Auto-Commit ON")
    }

    fun approveAutoPilotCxp() {
        val xml = pendingAutoPilotCxp ?: return
        showAutoPilotConfirmDialog = false
        pendingAutoPilotCxp = null
        commitCxpToIde(xml)
    }

    fun rejectAutoPilotCxp() {
        showAutoPilotConfirmDialog = false
        pendingAutoPilotCxp = null
        bridge.showToast("🛑 Patch discarded by user")
        bridge.log("AUTOPILOT_PIPELINE", "🛑 [DISCARDED] User rejected patch in confirmation dialog.")
    }

    override fun onLivePythonRequested(code: String) {
        if (!isLiveAutoPilotEnabled) return
        if (isAutoPilotConfirmEnabled) {
            bridge.log("AUTOPILOT_PIPELINE", "🛡️ [CONFIRM GUARD] Python execution requires confirmation.")
            pendingAutoPilotPayload = code
            pendingAutoPilotActionType = "PYTHON"
            showAutoPilotConfirmDialog = true
            bridge.vibrate(40L)
            return
        }
        executeLivePythonDirectly(code)
    }

    fun executeLivePythonDirectly(code: String) {
        com.omni.plugin.browser.engine.LiveAutoPilotEngine.executeLivePython(
            code = code,
            webView = currentWebView,
            bridge = bridge,
            coroutineScope = coroutineScope,
            onStatusChanged = { liveAutoPilotStatus = it }
        )
    }

    override fun onCxpDispatched(xml: String) {
        if (!isLiveAutoPilotEnabled) {
            bridge.log("AUTOPILOT_PIPELINE", "🛑 [BLOCKED] onCxpDispatched ignored because Live Auto-Pilot is OFF.")
            return
        }
        if (isAutoPilotConfirmEnabled) {
            bridge.log("AUTOPILOT_PIPELINE", "🛡️ [CONFIRM GUARD] CXP Patch requires confirmation.")
            pendingAutoPilotPayload = xml
            pendingAutoPilotActionType = "CXP"
            showAutoPilotConfirmDialog = true
            bridge.vibrate(40L)
            return
        }
        commitCxpToIde(xml)
    }

    fun commitCxpToIde(xml: String) {
        bridge.log("AUTOPILOT_PIPELINE", "🚀 [STAGE 2: HOST ROUTE] CXP XML received (${xml.length} chars). Identifying Conduit IDE tab...")
        bridge.showToast("⚡ Beaming CXP patch to Conduit IDE...")

        var ideTab = getMostRecentIdeTab()
        if (ideTab == null) {
            bridge.log("AUTOPILOT_PIPELINE", "📂 [STAGE 2: SPAWN IDE] Conduit IDE tab not open. Opening neighbor tab...")
            openLocalIdeAsNeighbor()
            ideTab = getMostRecentIdeTab()
        }

        val targetTabId = ideTab?.id ?: activeTabId
        cxpPillIdeTabId = targetTabId
        cxpPillStatus = "PATCHING"
        cxpPillMessage = "Committing patch to Conduit IDE..."
        showCxpPill = true
        cxpPillDismissJob?.cancel()

        val escapedXml = org.json.JSONObject.quote(xml)
        val probeScript = """
            (function() {
                const payload = $escapedXml;
                let attempts = 0;
                function tryIngest() {
                    if (window.__conduitAutoIngestAndCommit) {
                        window.__conduitAutoIngestAndCommit(payload);
                    } else if (attempts < 20) {
                        attempts++;
                        setTimeout(tryIngest, 250);
                    } else {
                        if (window.OmniIdeBridge && window.OmniIdeBridge.reportPatchResult) {
                            window.OmniIdeBridge.reportPatchResult('FAILED', 'Conduit IDE not ready after 5s');
                        }
                    }
                }
                tryIngest();
            })();
        """.trimIndent()

        val ideWv = poolManager.pool[targetTabId]
        if (ideWv != null) {
            ideWv.onResume()
            containerLayout?.let { container ->
                if (ideWv.parent !== container) {
                    (ideWv.parent as? android.view.ViewGroup)?.removeView(ideWv)
                    container.addView(ideWv, 0)
                }
            }

            bridge.log("AUTOPILOT_PIPELINE", "💉 [STAGE 2: INJECT] Dispatched probe into IDE tab [$targetTabId].")
            ideWv.evaluateJavascript(probeScript, null)
        } else {
            bridge.log("AUTOPILOT_PIPELINE", "⏳ [STAGE 2: INJECT QUEUE] IDE tab [$targetTabId] warming up. Polling injection...")
            coroutineScope.launch {
                delay(600)
                val wv = poolManager.pool[targetTabId]
                wv?.onResume()
                wv?.evaluateJavascript(probeScript, null)
            }
        }
    }

    override fun onPatchReported(status: String, details: String) {
        if (!isLiveAutoPilotEnabled) {
            bridge.log("AUTOPILOT_PIPELINE", "🛑 [BLOCKED] onPatchReported ignored because Live Auto-Pilot is OFF (Status: $status, Details: '$details').")
            return
        }
        bridge.log("AUTOPILOT_PIPELINE", "🏁 [STAGE 4: HOST AUDIT] Outcome received from Conduit: Status=$status | Details='$details'")
        cxpPillStatus = status.uppercase(java.util.Locale.US)
        cxpPillMessage = when (cxpPillStatus) {
            "SUCCESS" -> "Patch Committed to Git!"
            "PARTIAL" -> "Committed with AI Healing"
            "PARTIAL_PARSE_BLOCKED" -> "Blocked: Incomplete XML (Review in IDE)"
            "FAILED" -> details.ifEmpty { "Patch Failed" }
            else -> details
        }
        showCxpPill = true
        cxpPillDismissJob?.cancel()
        cxpPillDismissJob = coroutineScope.launch {
            delay(5500)
            showCxpPill = false
            bridge.log("AUTOPILOT_PIPELINE", "🧹 [STAGE 5: DISMISSED] Pill banner auto-dismissed.")
        }
    }

    var activeAiStudioTabId by mutableStateOf<String?>(null)

    override fun onFilePullRequested(xml: String) {
        if (!isLiveAutoPilotEnabled) return
        activeAiStudioTabId = activeTabId
        if (isAutoPilotConfirmEnabled) {
            bridge.log("AUTOPILOT_PULL", "🛡️ [CONFIRM GUARD] File pull requires confirmation.")
            pendingAutoPilotPayload = xml
            pendingAutoPilotActionType = "PULL"
            showAutoPilotConfirmDialog = true
            bridge.vibrate(40L)
            return
        }
        executeFilePullDirectly(xml)
    }

    fun executeFilePullDirectly(xml: String) {
        bridge.log("AUTOPILOT_PULL", "🔍 [STAGE 1: REQUEST] Routing file pull request to Conduit IDE...")
        bridge.showToast("📂 AI requesting project files from Conduit...")

        var ideTab = getMostRecentIdeTab()
        if (ideTab == null) {
            openLocalIdeAsNeighbor()
            ideTab = getMostRecentIdeTab()
        }

        val targetTabId = ideTab?.id ?: return
        val ideWv = poolManager.pool[targetTabId]
        val escapedXml = org.json.JSONObject.quote(xml)
        val pullScript = """
            (function() {
                const req = $escapedXml;
                let attempts = 0;
                function tryPull() {
                    if (window.__conduitPullFiles) {
                        window.__conduitPullFiles(req);
                    } else if (attempts < 20) {
                        attempts++;
                        setTimeout(tryPull, 250);
                    } else {
                        if (window.OmniIdeBridge && window.OmniIdeBridge.deliverPulledFiles) {
                            window.OmniIdeBridge.deliverPulledFiles('[ERROR: Conduit IDE not ready to fulfill pull]', 0);
                        }
                    }
                }
                tryPull();
            })();
        """.trimIndent()

        if (ideWv != null) {
            ideWv.onResume()
            containerLayout?.let { container ->
                if (ideWv.parent !== container) {
                    (ideWv.parent as? android.view.ViewGroup)?.removeView(ideWv)
                    container.addView(ideWv, 0)
                }
            }
            ideWv.evaluateJavascript(pullScript, null)
        } else {
            coroutineScope.launch {
                delay(600)
                val wv = poolManager.pool[targetTabId]
                wv?.onResume()
                wv?.evaluateJavascript(pullScript, null)
            }
        }
    }

    override fun onSyncFavoriteIde(content: String): Boolean {
        if (content.isBlank()) return false
        return try {
            val bytes = content.toByteArray(Charsets.UTF_8)
            val target = shortcuts.firstOrNull { it.isDefault }
                ?: shortcuts.firstOrNull { it.localSourcePath != null || it.url.contains("vault_") || it.title.contains("IDE", ignoreCase = true) }
                ?: shortcuts.firstOrNull { isLocalFilePath(it.url) }

            val targetId = target?.id
            val isolatedSubPath = if (targetId != null) "ide/vault_$targetId/index.html" else "ide/index.html"

            bridge.saveFile(isolatedSubPath, bytes)
            bridge.saveFile("ide/index.html", bytes)

            val localSrc = target?.localSourcePath ?: "/storage/emulated/0/Download/F/index.html"
            try {
                val localFile = File(localSrc)
                if (localFile.exists() && localFile.canWrite()) {
                    localFile.writeBytes(bytes)
                    bridge.log("IDE_SYNC", "✅ Mirrored synced IDE to storage: $localSrc")
                }
            } catch (e: Exception) {
                bridge.log("IDE_SYNC_WARN", "Failed writing to storage file $localSrc: ${e.message}")
            }

            vaultManager.autoMirrorVaultToDocuments()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                bridge.showToast("✅ Synced IDE into local vault & storage (${bytes.size / 1024} KB)")
            }
            bridge.log("IDE_SYNC", "✅ Favorite IDE (${target?.title ?: "Default"}) updated from GitHub ($isolatedSubPath)")
            true
        } catch (e: Exception) {
            bridge.log("IDE_SYNC_ERR", "Failed to sync favorite IDE: ${e.message}")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                bridge.showToast("❌ IDE sync error: ${e.message}")
            }
            false
        }
    }

    override fun onFilesPulled(dumpText: String, fileCount: Int) {
        if (!isLiveAutoPilotEnabled) return
        val studioTabId = activeAiStudioTabId ?: tabs.find { it.url.contains("aistudio.google.com") }?.id ?: return
        val studioWv = poolManager.pool[studioTabId] ?: return
        bridge.log("AUTOPILOT_PULL", "📦 [STAGE 3: DELIVER] Pulled $fileCount file(s) from IDE (${dumpText.length} chars). Enforcing 700KB gate...")

        val bytes = dumpText.toByteArray(Charsets.UTF_8)
        val threshold = 700 * 1024 // 700KB

        if (bytes.size > threshold) {
            bridge.log("AUTOPILOT_PULL", "📎 Size ${bytes.size} bytes > 700KB. Attaching file directly into AI Studio...")
            bridge.showToast("📎 Pulled files > 700KB: Attaching as context file...")
            val ts = System.currentTimeMillis()
            val filename = "project_context_$ts.txt"
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val summaryPrompt = "[Project Files Attached: $filename (${fileCount} files, ${(bytes.size / 1024)} KB). The requested files are attached above.]"
            val escapedPrompt = org.json.JSONObject.quote(summaryPrompt)
            val escapedName = org.json.JSONObject.quote(filename)
            val escapedB64 = org.json.JSONObject.quote(b64)

            val deliveryScript = "if (window.__omniDeliverPythonResult) { window.__omniDeliverPythonResult($escapedPrompt, $escapedName, $escapedB64, 'text/plain'); }"
            studioWv.evaluateJavascript(deliveryScript, null)
        } else {
            bridge.log("AUTOPILOT_PULL", "📝 Size ${bytes.size} bytes <= 700KB. Injecting text into prompt...")
            bridge.showToast("📝 Injecting $fileCount project file(s)...")
            val fullPrompt = "[Project Files Delivered (${fileCount} files)]:\n\n$dumpText"
            val escapedPrompt = org.json.JSONObject.quote(fullPrompt)
            val deliveryScript = "if (window.__omniDeliverPythonResult) { window.__omniDeliverPythonResult($escapedPrompt, null, null, null); }"
            studioWv.evaluateJavascript(deliveryScript, null)
        }
    }

    fun injectEruda() {
        val wv = currentWebView ?: return
        if (currentUrl == "about:blank") return

        val probeScript = """
            (function() {
                if (window.eruda) {
                    if (window.eruda._isInit) {
                        var devtools = eruda.get();
                        if (devtools && devtools._isShow) {
                            eruda.hide();
                        } else {
                            eruda.show();
                        }
                    } else {
                        eruda.init();
                        eruda.show();
                    }
                    return true;
                }
                return false;
            })();
        """.trimIndent()

        wv.evaluateJavascript(probeScript) { result ->
            if (result == "true") return@evaluateJavascript

            bridge.showToast("🛠️ Initializing Eruda DevTools (CSP-Immune)...")
            coroutineScope.launch(Dispatchers.IO) {
                var scriptSource: String? = null

                val cachedBytes = bridge.readFile("cache/eruda.min.js")
                if (cachedBytes != null && cachedBytes.isNotEmpty()) {
                    scriptSource = String(cachedBytes, Charsets.UTF_8)
                }

                if (scriptSource == null) {
                    val downloaded = bridge.httpGet("https://cdn.jsdelivr.net/npm/eruda")
                    if (!downloaded.isNullOrEmpty() && downloaded.length > 500) {
                        bridge.saveFile("cache/eruda.min.js", downloaded.toByteArray(Charsets.UTF_8))
                        scriptSource = downloaded
                    }
                }

                withContext(Dispatchers.Main) {
                    if (!scriptSource.isNullOrEmpty()) {
                        val payload = buildErudaInjectionScript(scriptSource)
                        wv.evaluateJavascript(payload, null)
                    } else {
                        wv.evaluateJavascript(ERUDA_DEVTOOLS_SCRIPT, null)
                    }
                }
            }
        }
    }

    fun getFavoriteIdeShortcut(): ShortcutItem? {
        return shortcuts.firstOrNull { it.isDefault }
            ?: shortcuts.firstOrNull { it.localSourcePath != null }
            ?: shortcuts.firstOrNull { it.url.contains("vault_") }
            ?: shortcuts.firstOrNull { isLocalFilePath(it.url) }
    }

    fun isMatchingIdeTab(tab: BrowserTab, targetShortcut: ShortcutItem?): Boolean {
        val u = tab.url.lowercase(java.util.Locale.US)
        if (targetShortcut == null) {
            return u.contains("localhost:$localServerPort") || u.contains("127.0.0.1:$localServerPort")
        }
        val targetUrl = targetShortcut.url.lowercase(java.util.Locale.US)
        val vaultToken = "vault_${targetShortcut.id}".lowercase(java.util.Locale.US)
        return u.contains(vaultToken) || u == targetUrl || (targetUrl.contains("localhost") && u.contains("localhost:$localServerPort"))
    }

    fun isIdeTab(tab: BrowserTab): Boolean {
        return isMatchingIdeTab(tab, getFavoriteIdeShortcut())
    }

    fun getMostRecentIdeTab(): BrowserTab? {
        val fav = getFavoriteIdeShortcut()
        // Trace back across all tabs in history to find the most recently accessed matching favorite IDE
        return tabs.filter { isMatchingIdeTab(it, fav) }.maxByOrNull { it.lastAccessedTime }
    }

    fun getLocalShortcuts(): List<ShortcutItem> {
        return shortcuts.filter {
            isLocalFilePath(it.url) || it.localSourcePath != null || it.url.contains("/ide/") || it.url.contains("localhost") || it.title.contains("IDE", ignoreCase = true)
        }
    }

    fun openLocalIdeAsNeighbor() {
        val targetUrl = resolveIdeUrl()
        val currentIdx = tabs.indexOfFirst { it.id == activeTabId }
        val currentTab = tabs.find { it.id == activeTabId }
        val targetProfId = currentTab?.profileId ?: selectedProfileId
        val insertIdx = if (currentIdx >= 0) currentIdx + 1 else tabs.size
        createNewTab(targetUrl = targetUrl, targetProfileId = targetProfId, insertAtIndex = insertIdx)
    }

    fun resolveIdeUrl(shortcut: ShortcutItem? = null): String {
        val target = shortcut ?: shortcuts.firstOrNull { it.isDefault } ?: shortcuts.firstOrNull {
            it.localSourcePath != null || it.url.contains("/ide/") || it.url.contains("localhost") || it.title.contains("IDE", ignoreCase = true)
        }
        if (target != null) {
            val src = target.localSourcePath ?: "/storage/emulated/0/Download/F/index.html"
            val isolatedPath = "ide/vault_${target.id}/index.html"
            val vFile = File(bridge.getPluginDir(), isolatedPath)
            if (!vFile.exists() || vFile.length() == 0L) {
                vaultManager.syncLocalFileToVault(src, isolatedPath)
            }
            return "http://localhost:$localServerPort/vault_${target.id}/index.html"
        } else {
            val defaultFile = File(bridge.getPluginDir(), "ide/index.html")
            if (!defaultFile.exists() || defaultFile.length() == 0L) {
                vaultManager.syncLocalFileToVault("/storage/emulated/0/Download/F/index.html", "ide/index.html")
            }
            return "http://localhost:$localServerPort/"
        }
    }

    fun launchIdeShortcutAsNeighbor(item: ShortcutItem) {
        val currentIdx = tabs.indexOfFirst { it.id == activeTabId }
        val thumb = currentWebView?.captureThumbnail()
        val bundle = Bundle()
        currentWebView?.saveState(bundle)

        val updatedTabs = tabs.map {
            if (it.id == activeTabId) it.copy(thumbnail = thumb ?: it.thumbnail, stateBundle = bundle) else it
        }.toMutableList()

        val targetUrl = resolveIdeUrl(item)
        val newId = "tab_${System.currentTimeMillis()}"
        val newTab = BrowserTab(
            id = newId,
            title = item.title,
            url = targetUrl,
            lastAccessedTime = System.currentTimeMillis(),
            profileId = selectedProfileId
        )
        val insertIdx = if (currentIdx >= 0) (currentIdx + 1).coerceAtMost(updatedTabs.size) else updatedTabs.size
        updatedTabs.add(insertIdx, newTab)

        tabs = updatedTabs
        activeTabId = newId
        isTabSwitcherOpen = false

        vaultManager.saveSession(tabs, newId)
        attachTabWebView(newId)
    }

    fun togglePreviousTab() {
        if (tabs.size <= 1) return
        val candidateId = previousActiveTabId
        val target = if (candidateId != null && candidateId != activeTabId && tabs.any { it.id == candidateId }) {
            tabs.find { it.id == candidateId }
        } else {
            tabs.filter { it.id != activeTabId }.maxByOrNull { it.lastAccessedTime }
        }
        if (target != null) {
            switchToTab(target.id)
            bridge.vibrate(25L)
        }
    }

    fun saveSmartNote(title: String, content: String, existingId: String? = null) {
        val cleanContent = content.trim()
        if (cleanContent.isEmpty()) return

        val cleanTitle = title.trim().ifEmpty {
            cleanContent.lines().firstOrNull { it.isNotBlank() }?.take(32) ?: "Note"
        }

        val updated = if (existingId != null && smartNotes.any { it.id == existingId }) {
            smartNotes.map {
                if (it.id == existingId) it.copy(title = cleanTitle, content = cleanContent, updatedAt = System.currentTimeMillis())
                else it
            }
        } else {
            listOf(SmartNote(title = cleanTitle, content = cleanContent)) + smartNotes
        }

        smartNotes = updated
        vaultManager.saveSmartNotes(updated)
        bridge.showToast("Note saved!")
    }

    fun deleteSmartNote(id: String) {
        val updated = smartNotes.filter { it.id != id }
        smartNotes = updated
        vaultManager.saveSmartNotes(updated)
        bridge.showToast("Note deleted")
    }

    fun injectTextToActivePage(text: String) {
        val wv = currentWebView ?: return
        val escapedText = org.json.JSONObject.quote(text)
        val script = """
            (function() {
                try {
                    var text = $escapedText;
                    var el = document.activeElement;
                    if (!el || el === document.body || (!['TEXTAREA', 'INPUT'].includes(el.tagName) && !el.isContentEditable)) {
                        el = document.querySelector('textarea, div[contenteditable="true"], input[type="text"]:not([readonly])');
                    }
                    if (!el) return false;

                    el.focus();
                    if (el.isContentEditable) {
                        document.execCommand('insertText', false, text);
                    } else {
                        var proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                        var descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
                        if (descriptor && descriptor.set) {
                            descriptor.set.call(el, text);
                        } else {
                            el.value = text;
                        }
                        el.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
                        el.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
                    }
                    return true;
                } catch(e) {
                    return false;
                }
            })();
        """.trimIndent()

        wv.evaluateJavascript(script) { result ->
            if (result == "true") {
                bridge.showToast("⚡ Injected into page input!")
                showSmartNotesDialog = false
            } else {
                bridge.copyToClipboard(text)
                bridge.showToast("Copied to clipboard (No active input found)")
                showSmartNotesDialog = false
            }
        }
    }

    fun saveCurrentSystemPreset(title: String, body: String) {
        val cleanTitle = title.trim().ifEmpty { "Preset ${systemPresets.size + 1}" }
        val existing = systemPresets.find { it.title.equals(cleanTitle, ignoreCase = true) }
        val updated = if (existing != null) {
            systemPresets.map { if (it.id == existing.id) it.copy(body = body.trim(), updatedAt = System.currentTimeMillis()) else it }
        } else {
            systemPresets + SystemInstructionPreset(title = cleanTitle, body = body.trim())
        }
        systemPresets = updated
        vaultManager.saveSystemPresets(updated)
        bridge.showToast("💾 Saved preset '$cleanTitle'")
    }

    fun deleteSystemPreset(id: String) {
        val updated = systemPresets.filter { it.id != id }
        systemPresets = updated
        vaultManager.saveSystemPresets(updated)
        bridge.showToast("Deleted preset")
    }

    fun selectSystemPreset(preset: SystemInstructionPreset) {
        autoSystemPromptTitle = preset.title
        autoSystemPrompt = preset.body
    }

    fun attachFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        coroutineScope.launch(Dispatchers.IO) {
            val newItems = mutableListOf<AutomationAttachment>()
            for (uri in uris) {
                try {
                    var name = "attachment_${System.currentTimeMillis()}"
                    var size = 0L

                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                            if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                        }
                    }

                    val (bytes, finalMime) = optimizeImageForAiStudio(context, uri)
                    if (bytes.isNotEmpty()) {
                        size = bytes.size.toLong()
                        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        val safeName = if (finalMime == "image/jpeg" && !name.endsWith(".jpg", ignoreCase = true) && !name.endsWith(".jpeg", ignoreCase = true)) {
                            "${name.substringBeforeLast(".")}.jpg"
                        } else name
                        newItems.add(
                            AutomationAttachment(
                                name = safeName,
                                mimeType = finalMime,
                                sizeBytes = size,
                                base64Data = b64
                            )
                        )
                        bridge.log("ATTACH", "✅ Optimized attachment: $safeName ($size bytes, mime=$finalMime)")
                    }
                } catch (e: Exception) {
                    bridge.log("ATTACH_ERR", "Failed reading attachment: ${e.message}")
                }
            }
            if (newItems.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    autoAttachments = autoAttachments + newItems
                    bridge.showToast("Attached ${newItems.size} optimized file(s)")
                }
            }
        }
    }

    fun removeAttachment(id: String) {
        autoAttachments = autoAttachments.filter { it.id != id }
    }

    fun addPromptStep() {
        promptSteps = promptSteps + SequentialPromptStep(prompt = "")
    }

    fun updatePromptStep(id: String, prompt: String? = null, repeatCount: Int? = null, isInfinite: Boolean? = null) {
        promptSteps = promptSteps.map { step ->
            if (step.id == id) {
                step.copy(
                    prompt = prompt ?: step.prompt,
                    repeatCount = repeatCount ?: step.repeatCount,
                    isInfinite = isInfinite ?: step.isInfinite
                )
            } else step
        }
    }

    fun removePromptStep(id: String) {
        if (promptSteps.size > 1) {
            promptSteps = promptSteps.filter { it.id != id }
        }
    }

    fun startAutomation() {
        val validSteps = promptSteps.filter { it.prompt.trim().isNotEmpty() }
        if (validSteps.isEmpty() && autoUserPrompt.trim().isEmpty() && autoAttachments.isEmpty()) {
            bridge.showToast("Please provide at least one prompt or attachment to run.")
            return
        }

        val effectiveSteps = if (validSteps.isNotEmpty()) {
            promptSteps
        } else {
            listOf(SequentialPromptStep(prompt = autoUserPrompt))
        }

        showAutomationDialog = false
        showAutomationResultDialog = true
        isAutomating = true
        automationStatus = "Initializing Sequential Session..."
        automationThoughts = ""
        automationResult = ""
        automationError = null
        automationElapsedSec = 0

        automator.start(
            profileId = autoSelectedProfileId,
            steps = effectiveSteps,
            systemPromptTitle = autoSystemPromptTitle,
            systemPrompt = autoSystemPrompt,
            thinkingLevel = autoThinkingLevel,
            model = autoSelectedModel,
            fallbackEnabled = autoFallbackToLocalPreset,
            temporaryChat = autoTemporaryChat,
            attachments = autoAttachments,
            containerLayout = containerLayout,
            callback = this
        )
    }

    fun stopAutomation() {
        showAutomationResultDialog = false
        automator.stop(containerLayout)
        isAutomating = false
    }

    fun exportBackup() {
        try {
            val file = vaultManager.exportFullBackup()
            bridge.showToast("✅ Backup saved to Documents/OmniBackups/${file.name}")
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, Uri.parse("file://${file.absolutePath}"))
                putExtra(Intent.EXTRA_SUBJECT, "Omni Chrome Backup")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Omni Chrome Backup").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            bridge.showToast("Backup failed: ${e.message}")
        }
    }

    fun restoreBackup(uri: Uri) {
        try {
            val result = vaultManager.restoreFromBackup(uri)
            result.profiles?.let { profiles = it }
            result.shortcuts?.let { shortcuts = it }
            result.solverApiKey?.let { solverApiKey = it }
            result.autoSolveEnabled?.let { autoSolveEnabled = it }
            result.systemPresets?.let { systemPresets = it }
            result.smartNotes?.let { smartNotes = it }
            result.selectedProfileId?.let {
                selectedProfileId = it
                autoSelectedProfileId = it
            }
            result.forceDark?.let {
                forceDarkWebPages = it
                poolManager.updateForceDark(it)
            }
            result.localPort?.let {
                localServerPort = it
                poolManager.localPort = it
            }
            if (!result.tabs.isNullOrEmpty()) {
                tabs = result.tabs
                val targetId = if (!result.activeTabId.isNullOrEmpty() && result.tabs.any { it.id == result.activeTabId }) result.activeTabId else result.tabs.first().id
                activeTabId = targetId
                attachTabWebView(targetId)
            }
            bridge.showToast("✅ Restored successfully!")
        } catch (e: Exception) {
            bridge.showToast("Restore failed: ${e.message}")
        }
    }

    fun fetchFavicon(domain: String) {
        if (domain.isEmpty() || faviconCache.containsKey(domain)) return
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val clean = domain.removePrefix("www.")
                val faviconUrl = "https://www.google.com/s2/favicons?domain=$clean&sz=128"
                val conn = java.net.URL(faviconUrl).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (conn.responseCode in 200..299) {
                    conn.inputStream.use { input ->
                        val bmp = BitmapFactory.decodeStream(input)
                        if (bmp != null) {
                            withContext(Dispatchers.Main) {
                                faviconCache[domain] = bmp
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun handleBackPressed(): Boolean {
        return when {
            showAutoPilotConfirmDialog -> { rejectAutoPilotAction(); true }
            showDownloadBanner -> { showDownloadBanner = false; true }
            showSmartNotesDialog -> { showSmartNotesDialog = false; true }
            showAutomationResultDialog -> { showAutomationResultDialog = false; true }
            showAutomationDialog -> { showAutomationDialog = false; true }
            showDownloadsDialog -> { showDownloadsDialog = false; true }
            editingShortcut != null -> { editingShortcut = null; true }
            isAddingShortcut -> { isAddingShortcut = false; true }
            editingProfile != null -> { editingProfile = null; true }
            showSettingsDialog -> { showSettingsDialog = false; true }
            isTabSwitcherOpen -> { isTabSwitcherOpen = false; true }
            isHomeOverlayOpen -> {
                isHomeOverlayOpen = false
                if (currentUrl != "about:blank") {
                    urlInputText = currentUrl
                }
                true
            }
            currentUrl == "about:blank" -> false
            currentWebView?.canGoBack() == true -> { currentWebView?.goBack(); true }
            else -> { navigateTo("about:blank"); true }
        }
    }

    override fun onProgressChanged(tabId: String, progress: Int) {
        tabProgressMap[tabId] = progress
        if (activeTabId == tabId) {
            loadProgress = progress / 100f
            val targetTab = tabs.find { it.id == tabId }
            isLoading = progress in 1..99 && targetTab?.url != "about:blank"
        }
    }

    override fun onReceivedTitle(tabId: String, title: String) {
        if (activeTabId == tabId) pageTitle = title
        tabs = tabs.map { if (it.id == tabId) it.copy(title = title) else it }
        vaultManager.saveSession(tabs, activeTabId, selectedProfileId)
    }

    override fun onUrlChanged(tabId: String, url: String, canGoBack: Boolean, canGoForward: Boolean) {
        if (activeTabId == tabId) {
            this.canGoBack = canGoBack
            this.canGoForward = canGoForward
            currentUrl = url
            urlInputText = url
            if (isLiveAutoPilotEnabled && (url == "about:blank" || !url.contains("aistudio.google.com"))) {
                isLiveAutoPilotEnabled = false
                showCxpPill = false
                com.omni.plugin.browser.engine.LiveAutoPilotEngine.disarm(currentWebView, bridge)
                bridge.log("AUTOPILOT_PIPELINE", "🔴 [AUTO-DISARM] Left AI Studio ($url). Live Auto-Pilot disarmed.")
            }
        }
        tabs = tabs.map { if (it.id == tabId) it.copy(url = url) else it }
        vaultManager.saveSession(tabs, activeTabId, selectedProfileId)
    }

    override fun onPageStarted(tabId: String, url: String) {
        tabProgressMap[tabId] = 10
        if (activeTabId == tabId) {
            loadProgress = 0.1f
            isLoading = url != "about:blank"
        }
    }

    override fun onPageFinished(tabId: String, url: String) {
        tabProgressMap[tabId] = 100
        if (activeTabId == tabId) {
            loadProgress = 1f
            isLoading = false
        }
        if (isErudaEnabled && url != "about:blank") {
            injectEruda()
        }
        if (url != null && url.contains("aistudio.google.com")) {
            com.omni.plugin.browser.engine.LiveAutoPilotEngine.syncOnPageFinished(currentWebView, url, isLiveAutoPilotEnabled)
        }
        if (autoSolveEnabled && solverApiKey.isNotEmpty() && url != "about:blank") {
            solveCurrentCaptcha()
        }
    }

    fun checkAndDismissTemporaryTab(view: WebView) {
        val tabId = poolManager.pool.entries.find { it.value == view }?.key ?: activeTabId
        val isPopup = popupTabs.contains(tabId) || tabOpenerMap.containsKey(tabId)
        val currentWvUrl = view.url ?: ""
        val isBlank = currentWvUrl.isEmpty() || currentWvUrl == "about:blank" || currentUrl == "about:blank"
        val hasNoHistory = !view.canGoBack() && view.copyBackForwardList().size <= 1

        if (isPopup && (isBlank || hasNoHistory)) {
            bridge.log("POPUP_CLEANUP", "🧹 Auto-closing temporary blank download tab [$tabId] and recessing back to opener")
            coroutineScope.launch(Dispatchers.Main) {
                delay(200) // Brief yield to allow download dispatch to register
                closeTab(tabId, suppressUndo = true)
            }
        }
    }

    override fun onDownloadTriggered(view: WebView, url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        downloadController.triggerFileDownload(view, url, userAgent, contentDisposition, mimeType) { dlId ->
            trackedDownloadIds.add(dlId)
        }
        checkAndDismissTemporaryTab(view)
    }

    override fun onBlobReceived(base64Data: String, mime: String, filename: String) {
        if (base64Data == "ERROR") {
            bridge.showToast("Blob extract failed: $filename")
            bridge.log("DOWNLOAD_ERR", "Blob extraction error: $filename")
        } else {
            val savedFile = downloadController.saveBase64ToDownloads(base64Data, mime, filename)
            refreshCompletedDownloads()
            if (savedFile != null && savedFile.exists()) {
                notifyDownloadCompleted(savedFile)
            }
        }
        currentWebView?.let { checkAndDismissTemporaryTab(it) }
    }

    override fun onNewTabRequested(url: String, sourceTabId: String?) {
        val parentTab = sourceTabId?.let { sId -> tabs.find { it.id == sId } } ?: tabs.find { it.id == activeTabId }
        val parentIdx = tabs.indexOfFirst { it.id == (parentTab?.id ?: activeTabId) }
        val inheritedProfileId = parentTab?.profileId ?: selectedProfileId
        val insertAt = if (parentIdx >= 0) parentIdx + 1 else tabs.size

        createNewTab(targetUrl = url, targetProfileId = inheritedProfileId, insertAtIndex = insertAt)
    }

    override fun onCreateWindowRequested(sourceTabId: String): WebView? {
        val parentTab = tabs.find { it.id == sourceTabId } ?: tabs.find { it.id == activeTabId }
        val parentIdx = tabs.indexOfFirst { it.id == (parentTab?.id ?: activeTabId) }
        val inheritedProfileId = parentTab?.profileId ?: selectedProfileId
        val insertAt = if (parentIdx >= 0) parentIdx + 1 else tabs.size

        createNewTab(targetUrl = "about:blank", targetProfileId = inheritedProfileId, insertAtIndex = insertAt)
        val newId = activeTabId
        val resolvedParentId = parentTab?.id ?: sourceTabId

        tabOpenerMap[newId] = resolvedParentId
        popupTabs.add(newId)

        // Watchdog: If a site-opened blank popup never loads real content or triggers navigation within 4s, clean it up
        coroutineScope.launch {
            delay(4000)
            val wv = poolManager.pool[newId]
            val wvUrl = wv?.url ?: ""
            if (popupTabs.contains(newId) && (wvUrl.isEmpty() || wvUrl == "about:blank") && wv?.canGoBack() == false) {
                bridge.log("POPUP_CLEANUP", "🧹 Watchdog auto-dismissing orphaned blank popup tab [$newId]")
                closeTab(newId, suppressUndo = true)
            }
        }

        return currentWebView
    }

    override fun onCloseTabRequested(tabId: String) {
        closeTab(tabId)
    }

    override fun onExternalUri(url: String, view: WebView?): Boolean {
        val handled = handleExternalUri(context, url, view, bridge)
        if (handled && view != null) {
            checkAndDismissTemporaryTab(view)
        }
        return handled
    }

    override fun onOpenFileChooser(filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: WebChromeClient.FileChooserParams?) {
        activeFileChooserCallback?.onReceiveValue(null)
        activeFileChooserCallback = filePathCallback

        val launcher = fileChooserLauncherBlock
        if (launcher != null && fileChooserParams != null) {
            try {
                val intent = fileChooserParams.createIntent()
                bridge.log("FILE_CHOOSER", "Dispatching WebChromeClient file chooser intent")
                launcher(intent)
            } catch (e: Exception) {
                bridge.log("FILE_CHOOSER_WARN", "createIntent failed: ${e.message}. Falling back to Intent.ACTION_GET_CONTENT")
                try {
                    val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    launcher(Intent.createChooser(fallbackIntent, "Select File"))
                } catch (err: Exception) {
                    bridge.log("FILE_CHOOSER_ERR", "File chooser invocation failed: ${err.message}")
                    activeFileChooserCallback?.onReceiveValue(null)
                    activeFileChooserCallback = null
                }
            }
        } else if (filePathCallback != null) {
            bridge.pickFiles("*/*", false) { uris ->
                filePathCallback.onReceiveValue(uris.toTypedArray())
                activeFileChooserCallback = null
            }
        }
    }

    override fun onRenderProcessKilled(tabId: String) {
        bridge.log("RENDER_WATCHDOG", "Resurrecting killed render process for tab [$tabId]")
        tabProgressMap[tabId] = 0
        if (activeTabId == tabId) {
            isLoading = false
            loadProgress = 0f
        }
        poolManager.pool.remove(tabId)
        if (activeTabId == tabId) {
            attachTabWebView(tabId)
        }
    }

    override fun onMediaStateChanged(tabId: String, title: String, artist: String, isPlaying: Boolean) {
        if (!isBackgroundAudioEnabled) {
            bridge.log("BACKGROUND_AUDIO", "Media change ignored (Background audio toggle is OFF)")
            return
        }
        bridge.log("BACKGROUND_AUDIO", "onMediaStateChanged -> tabId: $tabId | title: '$title' | isPlaying: $isPlaying")
        currentMediaTitle = title
        currentMediaArtist = artist
        isMediaPlaying = isPlaying
        if (isPlaying) {
            activeMediaTabId = tabId
            bridge.startMediaPlayback(title, artist, true) { shouldPlay: Boolean ->
                bridge.log("BACKGROUND_AUDIO", "Notification IPC action received -> shouldPlay: $shouldPlay for tabId: $tabId")
                poolManager.pool[tabId]?.evaluateJavascript("window.__omniTogglePlay($shouldPlay);", null)
            }
        } else {
            if (activeMediaTabId == tabId) {
                bridge.updateMediaPlayback(title, artist, false)
            }
        }
    }

    // --- AutomationCallback Impl ---
    override fun onStatus(msg: String) { automationStatus = msg }
    override fun onProgress(thoughts: String, output: String) {
        automationThoughts = thoughts
        automationResult = output
        automationStatus = "Streaming response..."
    }
    override fun onComplete(thoughts: String, output: String) {
        automationThoughts = thoughts
        automationResult = output
        automationStatus = "Completed"
        isAutomating = false
    }
    override fun onError(err: String) {
        automationError = err
        automationStatus = "Failed"
        isAutomating = false
    }
}