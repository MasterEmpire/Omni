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
    var profiles by mutableStateOf(listOf(BrowserProfile("default", "Default", 0xFF8AB4F8)))
    var selectedProfileId by mutableStateOf("default")
    var editingProfile by mutableStateOf<BrowserProfile?>(null)

    // Shortcuts State
    val ideInternalPath = "file://${bridge.getPluginDir()}/ide/index.html"
    val defaultShortcuts = listOf(
        ShortcutItem(title = "Local IDE", url = ideInternalPath, iconText = "💻", colorValue = 0xFF58A6FF, localSourcePath = "/storage/emulated/0/Download/F/index.html"),
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
    var isHomeOverlayOpen by mutableStateOf(false)

    var currentUrl by mutableStateOf("about:blank")
    var urlInputText by mutableStateOf("")
    var pageTitle by mutableStateOf("New Tab")
    var isLoading by mutableStateOf(false)
    var loadProgress by mutableFloatStateOf(0f)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var isDesktopMode by mutableStateOf(false)
    var showMenu by mutableStateOf(false)

    var containerLayout: FrameLayout? = null
    var currentWebView: WebView? = null

    // Dialog Visibility States
    var showSettingsDialog by mutableStateOf(false)
    var showDownloadsDialog by mutableStateOf(false)
    val trackedDownloadIds = mutableStateListOf<Long>()
    var activeDownloadsList by mutableStateOf<List<ActiveDownloadItem>>(emptyList())
    var completedFilesList by mutableStateOf<List<File>>(emptyList())

    // Solver States
    var solverApiKey by mutableStateOf("")
    var autoSolveEnabled by mutableStateOf(true)

    // Automator States
    var showAutomationDialog by mutableStateOf(false)
    var showAutomationResultDialog by mutableStateOf(false)
    var autoSelectedProfileId by mutableStateOf("default")
    var autoSelectedModel by mutableStateOf("Gemini 3.7 Flash")
    var autoThinkingLevel by mutableStateOf("Default")
    var systemPresets by mutableStateOf<List<SystemInstructionPreset>>(emptyList())
    var autoSystemPromptTitle by mutableStateOf("")
    var autoSystemPrompt by mutableStateOf("")
    var autoFallbackToLocalPreset by mutableStateOf(true)
    var autoUserPrompt by mutableStateOf("")
    var isAutomating by mutableStateOf(false)
    var automationStatus by mutableStateOf("Idle")
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

    // File Chooser Callback
    var activeFileChooserCallback: ValueCallback<Array<Uri>>? = null

    fun init() {
        vaultManager.resurrectFromVault()

        vaultManager.loadSolverConfig()?.let { (key, auto) ->
            solverApiKey = key
            autoSolveEnabled = auto
        }

        vaultManager.loadShortcuts()?.let { loaded ->
            if (loaded.isNotEmpty()) shortcuts = loaded
        }

        vaultManager.loadProfiles()?.let { loaded ->
            if (loaded.isNotEmpty()) profiles = loaded
        }

        vaultManager.loadSystemPresets()?.let { loaded ->
            if (loaded.isNotEmpty()) systemPresets = loaded
        }

        vaultManager.loadSession()?.let { (loadedTabs, savedActiveId) ->
            if (loadedTabs.isNotEmpty()) {
                val homeTabId = "tab_home_${System.currentTimeMillis()}"
                val homeTab = BrowserTab(id = homeTabId, title = "New Tab", url = "about:blank", profileId = "default")
                val nonBlankTabs = loadedTabs.filter { it.url != "about:blank" }
                tabs = listOf(homeTab) + nonBlankTabs
                activeTabId = homeTabId
                currentUrl = "about:blank"
                urlInputText = ""
                pageTitle = "New Tab"
            }
        }
    }

    fun refreshCompletedDownloads() {
        completedFilesList = downloadController.fetchCompletedDownloads()
    }

    fun pollDownloads() {
        if (trackedDownloadIds.isNotEmpty()) {
            val (updated, finished) = downloadController.queryActiveDownloads(trackedDownloadIds)
            activeDownloadsList = updated
            if (finished.isNotEmpty()) {
                trackedDownloadIds.removeAll(finished)
                refreshCompletedDownloads()
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
            if (id != targetTabId) wv.onPause()
        }
        CookieManager.getInstance().flush()
        container.removeAllViews()

        val targetTab = tabs.find { it.id == targetTabId } ?: return
        val isNewInstance = !poolManager.pool.containsKey(targetTabId)

        val targetWv = poolManager.pool.getOrPut(targetTabId) {
            poolManager.createConfiguredWebView(
                tabId = targetTabId,
                initialUrl = targetTab.url,
                savedState = targetTab.stateBundle,
                profileId = targetTab.profileId,
                isDesktop = isDesktopMode,
                listener = this
            )
        }

        if (isNewInstance) {
            poolManager.pruneHotPool(targetTabId, tabs, { id, b ->
                tabs = tabs.map { if (it.id == id) it.copy(stateBundle = b) else it }
            }, containerLayout)
        }

        if (targetTab.url.isNotEmpty() && targetTab.url != "about:blank" && (targetWv.url == null || targetWv.url == "about:blank")) {
            targetWv.loadUrl(targetTab.url)
        }

        container.addView(targetWv)
        targetWv.onResume()
        currentWebView = targetWv

        currentUrl = targetTab.url
        urlInputText = if (targetTab.url == "about:blank") "" else targetTab.url
        pageTitle = targetTab.title
        canGoBack = targetWv.canGoBack()
        canGoForward = targetWv.canGoForward()
    }

    fun createNewTab(targetUrl: String = "about:blank", targetProfileId: String = selectedProfileId) {
        val thumb = currentWebView?.captureThumbnail()
        val bundle = Bundle()
        currentWebView?.saveState(bundle)

        val updatedTabs = tabs.map {
            if (it.id == activeTabId) it.copy(thumbnail = thumb ?: it.thumbnail, stateBundle = bundle) else it
        }

        val newId = "tab_${System.currentTimeMillis()}"
        val newTab = BrowserTab(
            id = newId,
            title = if (targetUrl == "about:blank") "New Tab" else targetUrl,
            url = targetUrl,
            lastAccessedTime = System.currentTimeMillis(),
            profileId = targetProfileId
        )
        tabs = updatedTabs + newTab
        activeTabId = newId
        isTabSwitcherOpen = false

        vaultManager.saveSession(tabs, newId)
        attachTabWebView(newId)
    }

    fun switchToTab(targetId: String) {
        if (targetId == activeTabId) {
            isTabSwitcherOpen = false
            return
        }
        val thumb = currentWebView?.captureThumbnail()
        val bundle = Bundle()
        currentWebView?.saveState(bundle)

        tabs = tabs.map {
            if (it.id == activeTabId) it.copy(thumbnail = thumb ?: it.thumbnail, stateBundle = bundle) else it
        }
        activeTabId = targetId
        isTabSwitcherOpen = false

        vaultManager.saveSession(tabs, targetId)
        attachTabWebView(targetId)
    }

    fun closeTab(targetId: String) {
        poolManager.purgePending(containerLayout)

        val closedTab = tabs.find { it.id == targetId }
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

        poolManager.pool.remove(targetId)?.let { wv ->
            wv.onPause()
            containerLayout?.removeView(wv)
            poolManager.pendingPurge[targetId] = wv
        }

        val currentIdx = tabs.indexOfFirst { it.id == targetId }
        val remainingTabs = tabs.filter { it.id != targetId }

        if (remainingTabs.isEmpty()) {
            val newId = "tab_${System.currentTimeMillis()}"
            val freshTab = BrowserTab(id = newId, title = "New Tab", url = "about:blank")
            tabs = listOf(freshTab)
            activeTabId = newId
            vaultManager.saveSession(tabs, newId)
            attachTabWebView(newId)
        } else {
            tabs = remainingTabs
            if (targetId == activeTabId) {
                val nextIdx = (currentIdx - 1).coerceAtLeast(0).coerceAtMost(remainingTabs.size - 1)
                val nextTab = remainingTabs[nextIdx]
                activeTabId = nextTab.id
                vaultManager.saveSession(tabs, nextTab.id)
                attachTabWebView(nextTab.id)
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

        poolManager.pool.forEach { (id, wv) ->
            wv.onPause()
            poolManager.pendingPurge[id] = wv
        }
        poolManager.pool.clear()
        containerLayout?.removeAllViews()

        val newId = "tab_${System.currentTimeMillis()}"
        tabs = listOf(BrowserTab(id = newId, title = "New Tab", url = "about:blank"))
        activeTabId = newId
        isTabSwitcherOpen = false

        vaultManager.saveSession(tabs, newId)
        attachTabWebView(newId)
    }

    fun navigateTo(rawInput: String) {
        val input = rawInput.trim()
        if (input.isEmpty()) return

        val target = when {
            input == "about:blank" -> "about:blank"
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
        currentWebView?.loadUrl(target)
    }

    fun solveCurrentCaptcha() {
        captchaService.scanAndSolve(solverApiKey, currentUrl, currentWebView) { siteKey ->
            coroutineScope.launch {
                captchaService.executeSolver(solverApiKey, siteKey, currentUrl, currentWebView)
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

    fun startAutomation() {
        if (autoUserPrompt.trim().isEmpty()) {
            bridge.showToast("Please provide a prompt to run.")
            return
        }

        showAutomationDialog = false
        showAutomationResultDialog = true
        isAutomating = true
        automationStatus = "Initializing Headless Session..."
        automationThoughts = ""
        automationResult = ""
        automationError = null
        automationElapsedSec = 0

        automator.start(
            profileId = autoSelectedProfileId,
            userPrompt = autoUserPrompt,
            systemPromptTitle = autoSystemPromptTitle,
            systemPrompt = autoSystemPrompt,
            thinkingLevel = autoThinkingLevel,
            model = autoSelectedModel,
            fallbackEnabled = autoFallbackToLocalPreset,
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
            showMenu -> { showMenu = false; true }
            showAutomationResultDialog -> { showAutomationResultDialog = false; true }
            showAutomationDialog -> { showAutomationDialog = false; true }
            showDownloadsDialog -> { showDownloadsDialog = false; true }
            editingShortcut != null -> { editingShortcut = null; true }
            isAddingShortcut -> { isAddingShortcut = false; true }
            editingProfile != null -> { editingProfile = null; true }
            showSettingsDialog -> { showSettingsDialog = false; true }
            isTabSwitcherOpen -> { isTabSwitcherOpen = false; true }
            isHomeOverlayOpen -> { isHomeOverlayOpen = false; true }
            currentUrl == "about:blank" -> false
            currentWebView?.canGoBack() == true -> { currentWebView?.goBack(); true }
            else -> { navigateTo("about:blank"); true }
        }
    }

    // --- WebViewEventListener Impl ---
    override fun onProgressChanged(tabId: String, progress: Int) {
        if (activeTabId == tabId) {
            loadProgress = progress / 100f
            isLoading = progress in 1..99
        }
    }

    override fun onReceivedTitle(tabId: String, title: String) {
        if (activeTabId == tabId) pageTitle = title
        tabs = tabs.map { if (it.id == tabId) it.copy(title = title) else it }
        vaultManager.saveSession(tabs, activeTabId)
    }

    override fun onUrlChanged(tabId: String, url: String, canGoBack: Boolean, canGoForward: Boolean) {
        if (activeTabId == tabId) {
            this.canGoBack = canGoBack
            this.canGoForward = canGoForward
            currentUrl = url
            urlInputText = url
        }
        tabs = tabs.map { if (it.id == tabId) it.copy(url = url) else it }
        vaultManager.saveSession(tabs, activeTabId)
    }

    override fun onPageStarted(tabId: String, url: String) {
        if (activeTabId == tabId) isLoading = true
    }

    override fun onPageFinished(tabId: String, url: String) {
        if (activeTabId == tabId) isLoading = false
        if (autoSolveEnabled && solverApiKey.isNotEmpty() && url != "about:blank") {
            solveCurrentCaptcha()
        }
    }

    override fun onDownloadTriggered(view: WebView, url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        downloadController.triggerFileDownload(view, url, userAgent, contentDisposition, mimeType) { dlId ->
            trackedDownloadIds.add(dlId)
        }
    }

    override fun onBlobReceived(base64Data: String, mime: String, filename: String) {
        if (base64Data == "ERROR") {
            bridge.showToast("Blob extract failed: $filename")
            bridge.log("DOWNLOAD_ERR", "Blob extraction error: $filename")
        } else {
            downloadController.saveBase64ToDownloads(base64Data, mime, filename)
            refreshCompletedDownloads()
        }
    }

    override fun onNewTabRequested(url: String) {
        createNewTab(url)
    }

    override fun onExternalUri(url: String, view: WebView?): Boolean {
        return handleExternalUri(context, url, view, bridge)
    }

    override fun onOpenFileChooser(filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: WebChromeClient.FileChooserParams?) {
        activeFileChooserCallback?.onReceiveValue(null)
        activeFileChooserCallback = filePathCallback
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