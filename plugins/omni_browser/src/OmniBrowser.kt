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
import com.omni.plugin.browser.engine.AiStudioAutomator
import com.omni.plugin.browser.engine.AutomationCallback
import com.omni.plugin.browser.models.*
import com.omni.plugin.browser.state.BrowserStateHolder
import com.omni.plugin.browser.storage.VaultManager
import com.omni.plugin.browser.ui.*
import com.omni.plugin.browser.ui.dialogs.*
import com.omni.plugin.browser.ui.dialogs.SmartNotesDialog
import com.omni.plugin.browser.utils.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class OmniBrowser : PluginEntry() {

    companion object {
        @Volatile
        private var activeSolverAutomator: AiStudioAutomator? = null

        fun abortCurrentSolve() {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                activeSolverAutomator?.isAborted = true
                activeSolverAutomator?.stop(null)
                activeSolverAutomator = null
            }
        }
    }

    override fun onSystemEvent(event: String, payload: Map<String, Any>) {
        if (event == "ABORT_SOLVE") {
            val bridge = payload["bridge"] as? HostBridge
            bridge?.log("OMNI_SOLVE_IPC", "🛑 Processing ABORT_SOLVE. Halting AI Studio automator immediately.")
            abortCurrentSolve()
            return
        }

        if (event == "SOLVE_EXAM") {
            val ctx = payload["context"] as? Context ?: return
            val bridge = payload["bridge"] as? HostBridge ?: return
            val rawUris = payload["extra_image_uris"] ?: payload["uris"]
            @Suppress("UNCHECKED_CAST")
            val uris = (rawUris as? List<android.net.Uri>) ?: emptyList()
            val replyAction = payload["reply_action"] as? String ?: "com.universal.app.ACTION_OMNI_RESULT"
            val statusAction = payload["status_action"] as? String ?: "com.universal.app.ACTION_OMNI_STATUS"
            val presetTitle = payload["preset_title"] as? String ?: "Exam Solver"
            val userPrompt = payload["user_prompt"] as? String ?: ""
            val requestId = payload["request_id"] as? String ?: "req_${System.currentTimeMillis()}"

            bridge.log("OMNI_SOLVE_IPC", "🔥 Received SOLVE_EXAM request [$requestId] with ${uris.size} image URI(s). Preset: '$presetTitle'")

            fun sendStatus(msg: String) {
                bridge.log("OMNI_SOLVE_STATUS", "⚡ Status: $msg")
                try {
                    val statusIntent = Intent(statusAction).apply {
                        putExtra("extra_message", msg)
                        setPackage("com.universal.app")
                    }
                    ctx.sendBroadcast(statusIntent)
                } catch (e: Exception) {
                    bridge.log("OMNI_SOLVE_ERR", "Failed sending status broadcast: ${e.message}")
                }
            }

            fun sendResult(success: Boolean, json: String?, error: String?) {
                bridge.log("OMNI_SOLVE_RESULT", "🏁 Dispatching result (success=$success, error='$error', jsonLen=${json?.length ?: 0})")
                try {
                    val resultIntent = Intent(replyAction).apply {
                        putExtra("extra_success", success)
                        putExtra("extra_solution_json", json ?: "")
                        putExtra("extra_error", error ?: "")
                        setPackage("com.universal.app")
                    }
                    ctx.sendBroadcast(resultIntent)
                } catch (e: Exception) {
                    bridge.log("OMNI_SOLVE_ERR", "Failed sending result broadcast: ${e.message}")
                }
            }

            val imageCount = uris.size
            val imageText = if (imageCount == 1) "1 image" else "$imageCount images"
            sendStatus("Received $imageText. Preparing Omni.")

            val attachments = mutableListOf<AutomationAttachment>()
            for ((idx, uri) in uris.withIndex()) {
                try {
                    var name = "exam_image_$idx.jpg"
                    var size = 0L

                    ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                            if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                        }
                    }

                    val (bytes, finalMime) = optimizeImageForAiStudio(ctx, uri)
                    if (bytes.isNotEmpty()) {
                        size = bytes.size.toLong()
                        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        val safeName = if (finalMime == "image/jpeg" && !name.endsWith(".jpg", ignoreCase = true) && !name.endsWith(".jpeg", ignoreCase = true)) {
                            "${name.substringBeforeLast(".")}.jpg"
                        } else name
                        attachments.add(
                            AutomationAttachment(
                                name = safeName,
                                mimeType = finalMime,
                                sizeBytes = size,
                                base64Data = b64
                            )
                        )
                        bridge.log("OMNI_SOLVE_ATTACH", "✅ Optimized & ingested attachment [$idx]: $safeName ($size bytes, mime=$finalMime)")
                    } else {
                        bridge.log("OMNI_SOLVE_WARN", "⚠️ Empty byte stream for URI [$idx]: $uri")
                    }
                } catch (e: Exception) {
                    bridge.log("OMNI_SOLVE_ERR", "❌ Failed reading URI [$idx] ($uri): ${e.message}")
                }
            }

            if (attachments.isEmpty() && userPrompt.isBlank()) {
                bridge.log("OMNI_SOLVE_ERR", "❌ No valid image attachments or prompt provided.")
                sendResult(false, null, "No readable exam images found")
                return
            }

            val vaultManager = VaultManager(ctx, bridge)
            vaultManager.resurrectFromVault()

            val presets = vaultManager.loadSystemPresets() ?: emptyList()
            val matchedPreset = presets.find { it.title.equals(presetTitle.trim(), ignoreCase = true) }
            val finalSysTitle = matchedPreset?.title ?: presetTitle.trim()
            val finalSysPrompt = matchedPreset?.body ?: ""

            val profiles = vaultManager.loadProfiles() ?: emptyList()
            val eligibleProfiles = profiles.filter { profile ->
                !profile.id.equals("default", ignoreCase = true) &&
                !profile.name.trim().equals("default", ignoreCase = true)
            }

            if (eligibleProfiles.isEmpty()) {
                val noProfErr = "No secondary solver profile found in Omni Chrome. Main Default account is protected."
                bridge.log("OMNI_SOLVE_ERR", "❌ Abort: $noProfErr")
                sendStatus("No secondary profile found. Create an account in Omni Chrome.")
                sendResult(false, null, noProfErr)
                return
            }

            val prefs = ctx.getSharedPreferences("omni_solver_prefs", Context.MODE_PRIVATE)
            val lastIdx = prefs.getInt("solver_profile_round_robin_idx", -1)
            val nextIdx = (lastIdx + 1) % eligibleProfiles.size
            prefs.edit().putInt("solver_profile_round_robin_idx", nextIdx).apply()

            val targetProfile = eligibleProfiles[nextIdx]
            val targetProfileId = targetProfile.id

            bridge.log("OMNI_SOLVE_PRESET", "Using preset: '$finalSysTitle' (${finalSysPrompt.length} chars), Profile: '${targetProfile.name}' ($targetProfileId) [idx: $nextIdx/${eligibleProfiles.size}]")

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val automator = AiStudioAutomator(ctx, bridge)
                activeSolverAutomator = automator
                val steps = listOf(
                    SequentialPromptStep(
                        prompt = userPrompt,
                        repeatCount = 1,
                        isInfinite = false,
                        attachments = attachments
                    )
                )

                sendStatus("Connecting to Omni solver on ${targetProfile.name}.")
                bridge.log("OMNI_SOLVE_EXEC", "Starting headless AI Studio automator on ${targetProfile.name} ($targetProfileId)...")

                automator.start(
                    profileId = targetProfileId,
                    steps = steps,
                    userPrompt = userPrompt,
                    systemPromptTitle = finalSysTitle,
                    systemPrompt = finalSysPrompt,
                    thinkingLevel = "Default",
                    model = "Gemini 3.7 Flash",
                    fallbackEnabled = true,
                    temporaryChat = false,
                    attachments = emptyList(),
                    containerLayout = null,
                    callback = object : AutomationCallback {
                        private var lastProgressBroadcastTime = 0L

                        override fun onStatus(msg: String) {
                            sendStatus(msg)
                        }

                        override fun onProgress(thoughts: String, output: String) {
                            bridge.log("OMNI_SOLVE_PROGRESS", "Streaming response (${output.length} chars, thoughts: ${thoughts.length} chars)")
                            val now = System.currentTimeMillis()
                            // Pulse live streaming heartbeat every 8 seconds to reset client Dead Man Switch
                            if (now - lastProgressBroadcastTime > 8000L) {
                                lastProgressBroadcastTime = now
                                val statusMessage = if (output.isEmpty() && thoughts.isNotEmpty()) {
                                    "Thinking and analyzing questions."
                                } else {
                                    "Generating answers."
                                }
                                sendStatus(statusMessage)
                            }
                        }

                        override fun onComplete(thoughts: String, output: String) {
                            bridge.log("OMNI_SOLVE_SUCCESS", "🎉 AI Studio returned solution (${output.length} chars)")
                            bridge.log("OMNI_SOLVE_AUDIT", "Head (first 200 chars): ${output.take(200).replace("\n", " ")}")
                            bridge.log("OMNI_SOLVE_AUDIT", "Tail (last 200 chars): ${output.takeLast(200).replace("\n", " ")}")
                            bridge.log("OMNI_SOLVE_FULL_DUMP", "=== [DISPATCHING SOLUTION TO UNIVERSAL (${output.length} chars)] ===\n$output\n=== [END DISPATCH] ===")
                            sendResult(true, output, null)
                            automator.stop(null)
                            if (activeSolverAutomator == automator) activeSolverAutomator = null
                        }

                        override fun onError(err: String) {
                            if (automator.isAborted) {
                                bridge.log("OMNI_SOLVE_ERROR", "🛑 Suppressed error callback because automator was ABORTED.")
                                return
                            }
                            bridge.log("OMNI_SOLVE_ERROR", "💥 AI Studio automator reported error: $err")
                            sendResult(false, null, err)
                            automator.stop(null)
                            if (activeSolverAutomator == automator) activeSolverAutomator = null
                        }
                    }
                )
            }
        }
    }

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
            state.canGoBack, state.currentUrl, state.currentWebView, state.showMenu,
            state.showDownloadBanner
        ) {
            bridge.setOnBackPressedHandler { state.handleBackPressed() }
            onDispose { bridge.setOnBackPressedHandler(null) }
        }

        // --- UI Rendering ---
        val activeTab = state.tabs.find { it.id == state.activeTabId }
        val activeProf = state.profiles.find { it.id == activeTab?.profileId } ?: state.profiles.firstOrNull() ?: BrowserProfile("default", "Default", 0xFF2979FF)
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
                    onOpenIdeNeighbor = { state.toggleIdeNeighbor() },
                    showIdePickerMenu = state.showIdePickerMenu,
                    onDismissIdePicker = { state.showIdePickerMenu = false },
                    localShortcuts = state.getLocalShortcuts(),
                    onSelectIdeShortcut = { item -> state.launchIdeShortcutAsNeighbor(item) },
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
                                state.attachTabWebView(state.activeTabId)
                            }
                        },
                        update = { _ ->
                            if (state.containerLayout != null && state.currentWebView == null) {
                                state.attachTabWebView(state.activeTabId)
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
                            onReturnToLivePage = {
                                state.isHomeOverlayOpen = false
                                if (state.currentUrl != "about:blank") {
                                    state.urlInputText = state.currentUrl
                                }
                            },
                            onShortcutClick = { item ->
                                val targetUrl = if (isLocalFilePath(item.url) || item.localSourcePath != null || item.url.contains("/ide/") || item.title.contains("IDE", ignoreCase = true)) {
                                    state.resolveIdeUrl(item)
                                } else item.url

                                if (state.isHomeOverlayOpen && state.currentUrl != "about:blank") {
                                    state.isHomeOverlayOpen = false
                                    state.createNewTab(targetUrl)
                                } else {
                                    state.navigateTo(targetUrl)
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
                isBackgroundAudioEnabled = state.isBackgroundAudioEnabled,
                onToggleBackgroundAudio = { state.toggleBackgroundAudio() },
                onOpenSmartNotes = { state.showSmartNotesDialog = true },
                onOpenAutomation = { state.showAutomationDialog = true },
                onOpenLocalIde = {
                    val targetUrl = state.resolveIdeUrl()
                    state.navigateTo(targetUrl)
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
                    state.toggleDesktopMode()
                },
                onCopyCleanUrl = {
                    if (state.currentUrl != "about:blank") {
                        val cleanUrl = sanitizeUrlForCopy(state.currentUrl)
                        bridge.copyToClipboard(cleanUrl)
                    }
                },
                onInjectEruda = {
                    state.injectEruda()
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
                        state.changeSelectedProfile(it)
                    },
                    onAddProfile = {
                        val nextIdx = state.profiles.size
                        val newId = "profile_${System.currentTimeMillis()}"
                        val newColor = PROFILE_PALETTE[nextIdx % PROFILE_PALETTE.size]
                        val newProf = BrowserProfile(newId, "Account ${nextIdx + 1}", newColor)
                        state.profiles = state.profiles + newProf
                        state.changeSelectedProfile(newId)
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
            if (state.showSmartNotesDialog) {
                SmartNotesDialog(
                    bridge = bridge,
                    notes = state.smartNotes,
                    onSaveNote = { title: String, content: String, id: String? -> state.saveSmartNote(title, content, id) },
                    onDeleteNote = { id: String -> state.deleteSmartNote(id) },
                    onInjectToPage = { text: String -> state.injectTextToActivePage(text) },
                    onDismiss = { state.showSmartNotesDialog = false }
                )
            }

            if (state.showAutomationDialog) {
                AutomationOrderDialog(
                    profiles = state.profiles,
                    selectedProfileId = state.autoSelectedProfileId,
                    onSelectProfileId = { state.autoSelectedProfileId = it },
                    thinkingLevel = state.autoThinkingLevel,
                    onThinkingLevelChange = { state.autoThinkingLevel = it },
                    temporaryChat = state.autoTemporaryChat,
                    onTemporaryChatChange = { state.autoTemporaryChat = it },
                    attachments = state.autoAttachments,
                    onPickFiles = {
                        bridge.pickFiles("*/*", true) { uris ->
                            state.attachFiles(uris)
                        }
                    },
                    onRemoveAttachment = { state.removeAttachment(it) },
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
                    promptSteps = state.promptSteps,
                    onAddPromptStep = { state.addPromptStep() },
                    onUpdatePromptStep = { id, p, r, inf -> state.updatePromptStep(id, p, r, inf) },
                    onRemovePromptStep = { state.removePromptStep(it) },
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
                    onBackToStaging = {
                        state.showAutomationResultDialog = false
                        state.showAutomationDialog = true
                    },
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
                            forceDark = state.forceDarkWebPages,
                            localPort = state.localServerPort,
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
                            onSave = { key, auto, dark, port ->
                                state.solverApiKey = key
                                state.autoSolveEnabled = auto
                                state.forceDarkWebPages = dark
                                state.localServerPort = port
                                state.poolManager.localPort = port
                                state.poolManager.updateForceDark(dark)
                                state.vaultManager.saveSolverConfig(key, auto, dark, port)
                                bridge.showToast("Settings saved (Port: $port)!")
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
                    onSave = { name, rawUrl, isDef ->
                        val trimmedUrl = rawUrl.trim()
                        val isLocal = isLocalFilePath(trimmedUrl)
                        val (finalUrl, srcPath) = if (isLocal) {
                            val isolatedSubPath = "ide/vault_${targetItem.id}/index.html"
                            val (success, _) = state.vaultManager.syncLocalFileToVault(trimmedUrl, isolatedSubPath)
                            if (success) {
                                bridge.showToast("✅ Synced to private vault slot!")
                            }
                            Pair("http://localhost:${state.localServerPort}/vault_${targetItem.id}/index.html", normalizeLocalFilePath(trimmedUrl))
                        } else {
                            val webUrl = if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) "https://$trimmedUrl" else trimmedUrl
                            Pair(webUrl, null)
                        }

                        val updated = state.shortcuts.map {
                            if (it.id == targetItem.id) {
                                it.copy(title = name.trim().ifEmpty { targetItem.title }, url = finalUrl, localSourcePath = srcPath, isDefault = isDef)
                            } else if (isDef) {
                                it.copy(isDefault = false)
                            } else it
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
                    onAdd = { name, rawUrl, isDef ->
                        val trimmedUrl = rawUrl.trim()
                        val isLocal = isLocalFilePath(trimmedUrl)
                        val newShortcutId = "sc_${System.currentTimeMillis()}"
                        val (finalUrl, srcPath) = if (isLocal) {
                            val isolatedSubPath = "ide/vault_$newShortcutId/index.html"
                            val (success, _) = state.vaultManager.syncLocalFileToVault(trimmedUrl, isolatedSubPath)
                            if (success) {
                                bridge.showToast("✅ Isolated vault created for local app")
                            }
                            Pair("http://localhost:${state.localServerPort}/vault_$newShortcutId/index.html", normalizeLocalFilePath(trimmedUrl))
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
                            localSourcePath = srcPath,
                            isDefault = isDef
                        )
                        val updated = state.shortcuts.map { if (isDef) it.copy(isDefault = false) else it } + newItem
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

            DownloadCompletedPillBanner(
                visible = state.showDownloadBanner,
                file = state.latestDownloadedFile,
                onOpen = { file ->
                    openDownloadedFile(context, file, bridge)
                },
                onDismiss = { state.showDownloadBanner = false },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 58.dp)
                    .zIndex(35f)
            )

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