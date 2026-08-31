package com.omni.plugin.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import java.io.File
import java.io.FileOutputStream
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.ProfileStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.omni.hub.api.HostBridge
import com.omni.hub.api.PluginEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ShortcutItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val iconText: String = "",
    val colorValue: Long = 0xFF4285F4,
    val localSourcePath: String? = null
)

data class BrowserProfile(
    val id: String,
    val name: String,
    val colorValue: Long
)

val PROFILE_PALETTE = listOf(
    0xFF8AB4F8, // Blue (Default)
    0xFFC58AF9, // Purple (AI 2)
    0xFF81C995, // Emerald (AI 3)
    0xFFFDD663, // Amber
    0xFFF28B82, // Coral Rose
    0xFF78D9EC, // Cyan
    0xFFFFB74D, // Orange
    0xFFA7FFEB  // Mint
)

data class BrowserTab(
    val id: String,
    val title: String = "New Tab",
    val url: String = "about:blank",
    val stateBundle: Bundle? = null,
    val thumbnail: Bitmap? = null,
    val lastAccessedTime: Long = System.currentTimeMillis(),
    val profileId: String = "default"
)

class OmniBlobBridge(
    private val onBlobReceived: (base64Data: String, mimeType: String, filename: String) -> Unit,
    private val onLog: (tag: String, message: String) -> Unit
) {
    @JavascriptInterface
    fun processBlob(base64Data: String, mimeType: String, filename: String) {
        onBlobReceived(base64Data, mimeType, filename)
    }

    @JavascriptInterface
    fun log(tag: String, message: String) {
        onLog(tag, message)
    }
}

data class ActiveDownloadItem(
    val downloadId: Long,
    val filename: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val progress: Float,
    val status: Int
)

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

    @OptIn(ExperimentalFoundationApi::class)
    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun ChromeBrowserScreen(bridge: HostBridge) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val focusManager = LocalFocusManager.current
        val coroutineScope = rememberCoroutineScope()
        val MAX_HOT_TABS = 8

        // --- All State Definitions Hoisted to Top ---
        val ideInternalPath = remember { "file://${bridge.getPluginDir()}/ide/index.html" }

        val defaultShortcuts = remember {
            listOf(
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
        }

        var shortcuts by remember { mutableStateOf(defaultShortcuts) }
        val faviconCache = remember { mutableStateMapOf<String, Bitmap>() }

        var profiles by remember {
            mutableStateOf(
                listOf(
                    BrowserProfile("default", "Default", 0xFF8AB4F8)
                )
            )
        }
        var selectedProfileId by remember { mutableStateOf("default") }
        var editingProfile by remember { mutableStateOf<BrowserProfile?>(null) }
        var editingShortcut by remember { mutableStateOf<ShortcutItem?>(null) }
        var isAddingShortcut by remember { mutableStateOf(false) }

        var tabs by remember {
            mutableStateOf(
                listOf(
                    BrowserTab(
                        id = "tab_1",
                        title = "New Tab",
                        url = "about:blank",
                        profileId = "default"
                    )
                )
            )
        }
        var activeTabId by remember { mutableStateOf("tab_1") }
        var isTabSwitcherOpen by remember { mutableStateOf(false) }
        var isHomeOverlayOpen by remember { mutableStateOf(false) }

        var currentUrl by remember { mutableStateOf("about:blank") }
        var urlInputText by remember { mutableStateOf("") }
        var pageTitle by remember { mutableStateOf("New Tab") }
        var isLoading by remember { mutableStateOf(false) }
        var loadProgress by remember { mutableFloatStateOf(0f) }
        var canGoBack by remember { mutableStateOf(false) }
        var canGoForward by remember { mutableStateOf(false) }
        var isDesktopMode by remember { mutableStateOf(false) }
        var showMenu by remember { mutableStateOf(false) }

        val webViewPool = remember { mutableMapOf<String, WebView>() }
        val pendingPurgeWebViews = remember { mutableMapOf<String, WebView>() }
        var containerLayout: FrameLayout? by remember { mutableStateOf(null) }
        var webViewInstance: WebView? by remember { mutableStateOf(null) }

        fun purgePendingWebViews() {
            pendingPurgeWebViews.forEach { (_, wv) ->
                try {
                    wv.stopLoading()
                    wv.onPause()
                    containerLayout?.removeView(wv)
                    wv.destroy()
                } catch (_: Exception) {}
            }
            pendingPurgeWebViews.clear()
        }

        var mobileUA by remember { mutableStateOf("") }
        var showSettingsDialog by remember { mutableStateOf(false) }
        var showDownloadsDialog by remember { mutableStateOf(false) }
        val trackedDownloadIds = remember { mutableStateListOf<Long>() }
        var activeDownloadsList by remember { mutableStateOf<List<ActiveDownloadItem>>(emptyList()) }
        var completedFilesList by remember { mutableStateOf<List<File>>(emptyList()) }

        var solverApiKey by remember { mutableStateOf("") }
        var autoSolveEnabled by remember { mutableStateOf(true) }
        var isSolvingCaptcha by remember { mutableStateOf(false) }

        // --- AI Studio Automator State ---
        var showAutomationDialog by remember { mutableStateOf(false) }
        var showAutomationResultDialog by remember { mutableStateOf(false) }
        var autoSelectedProfileId by remember { mutableStateOf("default") }
        var autoSelectedModel by remember { mutableStateOf("Gemini 3.7 Flash") }
        var autoThinkingLevel by remember { mutableStateOf("High") }
        var autoSystemPrompt by remember { mutableStateOf("") }
        var autoUserPrompt by remember { mutableStateOf("") }
        var isAutomating by remember { mutableStateOf(false) }
        var automationStatus by remember { mutableStateOf("Idle") }
        var automationThoughts by remember { mutableStateOf("") }
        var automationResult by remember { mutableStateOf("") }
        var automationError by remember { mutableStateOf<String?>(null) }
        var automationElapsedSec by remember { mutableIntStateOf(0) }
        var headlessAutomationWv by remember { mutableStateOf<WebView?>(null) }

        var lastClosedTabsSnapshot by remember { mutableStateOf<List<BrowserTab>?>(null) }
        var lastActiveTabIdSnapshot by remember { mutableStateOf<String?>(null) }
        var undoMessage by remember { mutableStateOf("") }
        var showUndoBanner by remember { mutableStateOf(false) }
        var undoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

        fun refreshCompletedDownloads() {
            try {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OmniDownloads")
                if (dir.exists() && dir.isDirectory) {
                    completedFilesList = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
                } else {
                    completedFilesList = emptyList()
                }
            } catch (_: Exception) {
                completedFilesList = emptyList()
            }
        }

        LaunchedEffect(trackedDownloadIds.size, showDownloadsDialog) {
            while (trackedDownloadIds.isNotEmpty() || showDownloadsDialog) {
                if (trackedDownloadIds.isNotEmpty()) {
                    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                    if (dm != null) {
                        try {
                            val query = DownloadManager.Query().setFilterById(*trackedDownloadIds.toLongArray())
                            val cursor = dm.query(query)
                            val updated = mutableListOf<ActiveDownloadItem>()
                            val finishedIds = mutableListOf<Long>()

                            if (cursor != null && cursor.moveToFirst()) {
                                val idCol = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                                val titleCol = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                                val bytesCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                                val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                                val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)

                                do {
                                    val dId = if (idCol >= 0) cursor.getLong(idCol) else -1L
                                    val title = if (titleCol >= 0) cursor.getString(titleCol) ?: "Download" else "Download"
                                    val bytes = if (bytesCol >= 0) cursor.getLong(bytesCol) else 0L
                                    val total = if (totalCol >= 0) cursor.getLong(totalCol) else -1L
                                    val status = if (statusCol >= 0) cursor.getInt(statusCol) else DownloadManager.STATUS_RUNNING
                                    val prog = if (total > 0) (bytes.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

                                    if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                                        finishedIds.add(dId)
                                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                            refreshCompletedDownloads()
                                        }
                                    } else {
                                        updated.add(ActiveDownloadItem(dId, title, bytes, total, prog, status))
                                    }
                                } while (cursor.moveToNext())
                                cursor.close()
                            }
                            activeDownloadsList = updated
                            trackedDownloadIds.removeAll(finishedIds)
                        } catch (_: Exception) {}
                    }
                }
                if (showDownloadsDialog) {
                    refreshCompletedDownloads()
                }
                delay(1000)
            }
        }

        var restoreTrigger by remember { mutableStateOf<((Uri) -> Unit)?>(null) }
        val backupPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri != null) {
                restoreTrigger?.invoke(uri)
            }
        }

        var activeFileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
        val fileChooserLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            activeFileChooserCallback?.onReceiveValue(uris)
            activeFileChooserCallback = null
        }

        val desktopUA = remember {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        }

        val blobInterceptorScript = remember {
            """
            (function() {
                if (window.__omniBlobHooked && window.__omniIframeHooked) return;
                window.__omniBlobHooked = true;
                window.__omniIframeHooked = true;

                window.__omniBlobMap = window.__omniBlobMap || new Map();

                function getTopBlobMap() {
                    try {
                        if (window.top && window.top.__omniBlobMap) return window.top.__omniBlobMap;
                    } catch(_) {}
                    return window.__omniBlobMap;
                }

                function shipBlobToHost(dataUrl, mimeType, filename) {
                    if (window.OmniBlobDownloader && window.OmniBlobDownloader.processBlob) {
                        window.OmniBlobDownloader.processBlob(dataUrl, mimeType, filename);
                        return;
                    }
                    try {
                        if (window.top && window.top !== window) {
                            window.top.postMessage({
                                type: '__OMNI_BLOB_SHIP__',
                                dataUrl: dataUrl,
                                mimeType: mimeType,
                                filename: filename
                            }, '*');
                        }
                    } catch(_) {}
                }

                // Top-level listener for sandboxed iframe postMessage
                if (window === window.top) {
                    window.addEventListener('message', function(ev) {
                        if (ev.data && ev.data.type === '__OMNI_BLOB_SHIP__') {
                            if (window.OmniBlobDownloader && window.OmniBlobDownloader.log) {
                                window.OmniBlobDownloader.log('BLOB_IFRAME_MSG', 'Received blob from sandboxed subframe: ' + ev.data.filename);
                            }
                            shipBlobToHost(ev.data.dataUrl, ev.data.mimeType, ev.data.filename);
                        }
                    }, true);
                }

                function hookWindowScope(targetWin) {
                    if (!targetWin) return;
                    try {
                        if (targetWin.__omniScopeHooked) return;
                        targetWin.__omniScopeHooked = true;
                    } catch(_) { return; }

                    function hookCreateObjectURL(target) {
                        if (!target || !target.createObjectURL || target.createObjectURL.__omniHooked) return;
                        const orig = target.createObjectURL;
                        const hooked = function(blob) {
                            const url = orig.apply(this, arguments);
                            try {
                                if (blob instanceof Blob) {
                                    const bMap = getTopBlobMap();
                                    bMap.set(url, blob);
                                    if (targetWin.__omniBlobMap) targetWin.__omniBlobMap.set(url, blob);
                                    if (bMap.size > 400) {
                                        const firstKey = bMap.keys().next().value;
                                        bMap.delete(firstKey);
                                    }
                                    if (window.OmniBlobDownloader && window.OmniBlobDownloader.log) {
                                        window.OmniBlobDownloader.log('BLOB_MAP', 'Vaulted Blob in RAM: ' + url + ' (' + blob.size + 'b, type: ' + blob.type + ')');
                                    }
                                }
                            } catch(e) {}
                            return url;
                        };
                        hooked.__omniHooked = true;
                        try {
                            target.createObjectURL = hooked;
                        } catch(e) {
                            try {
                                Object.defineProperty(target, 'createObjectURL', {
                                    value: hooked,
                                    writable: true,
                                    configurable: true
                                });
                            } catch(_) {}
                        }
                    }

                    try { hookCreateObjectURL(targetWin.URL); } catch(_) {}
                    try { if (targetWin.webkitURL) hookCreateObjectURL(targetWin.webkitURL); } catch(_) {}

                    const origRevoke = targetWin.URL ? targetWin.URL.revokeObjectURL : null;
                    if (origRevoke && !origRevoke.__omniHooked) {
                        const hookedRevoke = function(url) {
                            setTimeout(function() {
                                try { origRevoke.call(targetWin.URL, url); } catch(e) {}
                                try {
                                    const bMap = getTopBlobMap();
                                    bMap.delete(url);
                                } catch(_) {}
                            }, 30000);
                        };
                        hookedRevoke.__omniHooked = true;
                        try { targetWin.URL.revokeObjectURL = hookedRevoke; } catch(_) {}
                    }

                    function extractAndShip(blobObj, mimeType, filename) {
                        if (window.OmniBlobDownloader && window.OmniBlobDownloader.log) {
                            window.OmniBlobDownloader.log('BLOB_CLICK', 'Intercepted anchor click for: ' + filename + ' (' + blobObj.size + 'b). Processing via RAM...');
                        }
                        const reader = new targetWin.FileReader();
                        reader.onloadend = function() {
                            shipBlobToHost(reader.result, mimeType || blobObj.type || 'application/octet-stream', filename || 'download');
                        };
                        reader.onerror = function() {
                            if (window.OmniBlobDownloader && window.OmniBlobDownloader.processBlob) {
                                window.OmniBlobDownloader.processBlob('ERROR', '', 'FileReader failed to read in-memory Blob');
                            }
                        };
                        reader.readAsDataURL(blobObj);
                    }

                    // Hook anchor clicks in this window scope
                    if (targetWin.HTMLAnchorElement && targetWin.HTMLAnchorElement.prototype) {
                        const origAnchorClick = targetWin.HTMLAnchorElement.prototype.click;
                        if (origAnchorClick && !origAnchorClick.__omniHooked) {
                            targetWin.HTMLAnchorElement.prototype.click = function() {
                                try {
                                    const href = this.href || '';
                                    const downloadName = this.getAttribute('download') || this.download || '';
                                    const bMap = getTopBlobMap();
                                    if (href.startsWith('blob:') && bMap && bMap.has(href)) {
                                        const blob = bMap.get(href);
                                        extractAndShip(blob, blob.type, downloadName || 'download');
                                        return;
                                    }
                                } catch(e) {}
                                return origAnchorClick.apply(this, arguments);
                            };
                            targetWin.HTMLAnchorElement.prototype.click.__omniHooked = true;
                        }
                    }

                    try {
                        targetWin.document.addEventListener('click', function(e) {
                            const anchor = e.target && (e.target.tagName === 'A' ? e.target : (e.target.closest ? e.target.closest('a') : null));
                            if (!anchor) return;

                            const href = anchor.href || '';
                            const downloadName = anchor.getAttribute('download') || anchor.download || '';
                            const bMap = getTopBlobMap();

                            if (href.startsWith('blob:') || anchor.hasAttribute('download')) {
                                if (href.startsWith('blob:') && bMap && bMap.has(href)) {
                                    const blob = bMap.get(href);
                                    extractAndShip(blob, blob.type, downloadName || 'download');
                                    e.preventDefault();
                                    e.stopPropagation();
                                } else if (href.startsWith('data:')) {
                                    shipBlobToHost(href, '', downloadName || 'download');
                                    e.preventDefault();
                                    e.stopPropagation();
                                }
                            }
                        }, true);
                    } catch(_) {}
                }

                // 1. Hook Current Top Window
                hookWindowScope(window);

                // 2. Hook All Existing and Future IFrames (MakerSuite / AI Studio Sub-frames)
                function scanAndHookIframes() {
                    try {
                        const iframes = document.querySelectorAll('iframe');
                        iframes.forEach(function(ifr) {
                            try {
                                if (ifr.contentWindow) {
                                    hookWindowScope(ifr.contentWindow);
                                }
                            } catch(_) {}
                            if (!ifr.__omniLoadHooked) {
                                ifr.__omniLoadHooked = true;
                                ifr.addEventListener('load', function() {
                                    try { if (ifr.contentWindow) hookWindowScope(ifr.contentWindow); } catch(_) {}
                                });
                            }
                        });
                    } catch(_) {}
                }

                scanAndHookIframes();

                // Observe dynamic DOM insertions for sub-frames
                try {
                    const observer = new MutationObserver(function(mutations) {
                        scanAndHookIframes();
                    });
                    observer.observe(document.documentElement || document.body, {
                        childList: true,
                        subtree: true
                    });
                } catch(_) {}
            })();
            """.trimIndent()
        }

        var createNewTabHandler: ((String) -> Unit)? = null
        var attachTabWebViewHandler: ((String) -> Unit)? = null

        fun extractDomain(url: String): String {
            return try {
                val clean = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
                val uri = Uri.parse(clean)
                uri.host?.removePrefix("www.") ?: url
            } catch (_: Exception) {
                url
            }
        }

        fun sanitizeUrlForCopy(rawUrl: String): String {
            if (rawUrl.isEmpty() || rawUrl == "about:blank") return rawUrl
            return try {
                val uri = Uri.parse(rawUrl)
                val host = uri.host?.lowercase(Locale.US) ?: return rawUrl
                val scheme = uri.scheme ?: "https"

                // 1. Google Search Sanitizer (Preserve query & filters only)
                if (host.contains("google.") && uri.path?.contains("/search") == true) {
                    val q = uri.getQueryParameter("q")
                    if (!q.isNullOrEmpty()) {
                        val builder = Uri.Builder()
                            .scheme(scheme)
                            .authority(host)
                            .path(uri.path)
                            .appendQueryParameter("q", q)

                        listOf("tbm", "tbs", "udm", "start", "hl").forEach { param ->
                            uri.getQueryParameter(param)?.let { builder.appendQueryParameter(param, it) }
                        }
                        return builder.build().toString()
                    }
                }

                // 2. YouTube Watch Sanitizer (Preserve video ID, playlist, timestamp)
                if (host.contains("youtube.com") && uri.path?.contains("/watch") == true) {
                    val v = uri.getQueryParameter("v")
                    if (!v.isNullOrEmpty()) {
                        val builder = Uri.Builder()
                            .scheme(scheme)
                            .authority(host)
                            .path(uri.path)
                            .appendQueryParameter("v", v)
                        listOf("t", "list", "index").forEach { param ->
                            uri.getQueryParameter(param)?.let { builder.appendQueryParameter(param, it) }
                        }
                        return builder.build().toString()
                    }
                }

                if (host == "youtu.be") {
                    val videoId = uri.path?.removePrefix("/")
                    if (!videoId.isNullOrEmpty()) {
                        val builder = Uri.Builder()
                            .scheme(scheme)
                            .authority("youtu.be")
                            .path("/$videoId")
                        uri.getQueryParameter("t")?.let { builder.appendQueryParameter("t", it) }
                        return builder.build().toString()
                    }
                }

                // 3. Amazon ASIN Canonicalizer (/dp/ASIN)
                if (host.contains("amazon.")) {
                    val asinRegex = Regex("/(?:dp|gp/product)/([A-Z0-9]{10})")
                    val match = asinRegex.find(uri.path ?: "")
                    if (match != null) {
                        val asin = match.groupValues[1]
                        return "$scheme://$host/dp/$asin"
                    }
                }

                // 4. Universal Marketing & Tracking Parameter Stripper
                val trackingParams = setOf(
                    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
                    "fbclid", "gclid", "gclsrc", "dclid", "msclkid", "twclid", "igshid", "igsh",
                    "_ga", "_gl", "_hsenc", "_hsmi", "yclid", "wickedid", "rb_clickid", "s_kwcid",
                    "ref", "ref_", "ref_src", "ref_url", "si", "pp", "embeds_referring_euri", "source_ve_path",
                    "feature", "oq", "ved", "sclient", "gs_lp", "gs_lcrp", "biw", "bih", "dpr", "ei", "sei",
                    "sca_esv", "sca_upv", "pccc", "rdt", "is_from_webapp", "_r", "_d"
                )

                val queryNames = uri.queryParameterNames
                if (queryNames.isEmpty()) {
                    return rawUrl
                }

                val builder = uri.buildUpon().clearQuery()
                for (name in queryNames) {
                    if (!trackingParams.contains(name.lowercase(Locale.US))) {
                        val values = uri.getQueryParameters(name)
                        for (v in values) {
                            builder.appendQueryParameter(name, v)
                        }
                    }
                }

                val result = builder.build().toString()
                result.removeSuffix("?")
            } catch (_: Exception) {
                rawUrl
            }
        }

        fun normalizeLocalFilePath(rawPath: String): String {
            var path = rawPath.trim()
            try {
                path = Uri.decode(path)
            } catch (_: Exception) {}

            while (path.startsWith("file://")) {
                path = path.removePrefix("file://")
            }
            while (path.startsWith("file:/")) {
                path = path.removePrefix("file:/")
            }
            if (path.startsWith("/sdcard/")) {
                path = "/storage/emulated/0/" + path.removePrefix("/sdcard/")
            } else if (path.startsWith("sdcard/")) {
                path = "/storage/emulated/0/" + path.removePrefix("sdcard/")
            }
            if (!path.startsWith("/") && (path.startsWith("storage/") || path.startsWith("data/"))) {
                path = "/$path"
            }
            return path.trim()
        }

        fun isLocalFilePath(input: String): Boolean {
            val clean = input.trim().lowercase()
            return clean.startsWith("/") ||
                   clean.startsWith("file:") ||
                   clean.startsWith("content:") ||
                   clean.startsWith("sdcard/") ||
                   clean.startsWith("storage/") ||
                   clean.endsWith(".html") ||
                   clean.endsWith(".htm")
        }

        fun syncLocalFileToVault(sourcePath: String, targetSubPath: String = "ide/index.html"): Pair<Boolean, String> {
            val cleanInput = sourcePath.trim()
            bridge.log("IDE_SYNC", "Attempting sync for source: $cleanInput")

            try {
                var bytes: ByteArray? = null
                if (cleanInput.startsWith("content://")) {
                    val uri = Uri.parse(cleanInput)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        bytes = input.readBytes()
                    }
                } else {
                    val cleanPath = normalizeLocalFilePath(cleanInput)
                    val srcFile = File(cleanPath)
                    if (srcFile.exists() && srcFile.isFile) {
                        bytes = srcFile.readBytes()
                    } else {
                        try {
                            val uri = Uri.parse(if (cleanInput.startsWith("file://")) cleanInput else "file://$cleanPath")
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                bytes = input.readBytes()
                            }
                        } catch (_: Exception) {}
                    }
                    if (bytes == null && !srcFile.exists()) {
                        val errMsg = "File not found at: $cleanPath"
                        bridge.log("IDE_SYNC_ERR", errMsg)
                        return Pair(false, errMsg)
                    }
                }

                if (bytes != null && bytes!!.isNotEmpty()) {
                    val savedAbsPath = bridge.saveFile(targetSubPath, bytes!!)
                    val kb = String.format(Locale.US, "%.1f", bytes!!.size / 1024.0)
                    bridge.log("IDE_SYNC", "✅ Vaulted $cleanInput -> $savedAbsPath ($kb KB)")
                    return Pair(true, "file://$savedAbsPath")
                } else {
                    val errMsg = "File is empty or unreadable"
                    bridge.log("IDE_SYNC_ERR", "$errMsg: $cleanInput")
                    return Pair(false, errMsg)
                }
            } catch (e: Exception) {
                val errMsg = "Sync error: ${e.message}"
                bridge.log("IDE_SYNC_ERR", "$errMsg on $cleanInput")
                return Pair(false, errMsg)
            }
        }

        fun autoMirrorVaultToDocuments() {
            try {
                val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val vaultDir = File(docsDir, ".omni_vault").apply { mkdirs() }
                
                fun mirrorFile(relPath: String, outName: String) {
                    val src = File(bridge.getPluginDir(), relPath)
                    if (src.exists() && src.isFile) {
                        src.copyTo(File(vaultDir, outName), overwrite = true)
                    }
                }
                mirrorFile("config/profiles.json", "profiles.json")
                mirrorFile("config/shortcuts.json", "shortcuts.json")
                mirrorFile("config/session.json", "session.json")
                mirrorFile("config/solver.json", "solver.json")

                // Mirror all isolated local IDE vaults
                val ideDir = File(bridge.getPluginDir(), "ide")
                if (ideDir.exists() && ideDir.isDirectory) {
                    val destIdeDir = File(vaultDir, "ide").apply { mkdirs() }
                    ideDir.copyRecursively(destIdeDir, overwrite = true)
                }
            } catch (_: Exception) {}
        }

        fun saveShortcutsToDisk(list: List<ShortcutItem>) {
            try {
                val arr = org.json.JSONArray()
                list.forEach { s ->
                    val obj = org.json.JSONObject().apply {
                        put("id", s.id)
                        put("title", s.title)
                        put("url", s.url)
                        put("iconText", s.iconText)
                        put("colorValue", s.colorValue)
                        if (!s.localSourcePath.isNullOrEmpty()) {
                            put("localSourcePath", s.localSourcePath)
                        }
                    }
                    arr.put(obj)
                }
                bridge.saveFile("config/shortcuts.json", arr.toString().toByteArray(Charsets.UTF_8))
                autoMirrorVaultToDocuments()
            } catch (_: Exception) {}
        }

        fun saveProfilesToDisk(list: List<BrowserProfile>) {
            try {
                val arr = org.json.JSONArray()
                list.forEach { p ->
                    val obj = org.json.JSONObject().apply {
                        put("id", p.id)
                        put("name", p.name)
                        put("colorValue", p.colorValue)
                    }
                    arr.put(obj)
                }
                bridge.saveFile("config/profiles.json", arr.toString().toByteArray(Charsets.UTF_8))
                autoMirrorVaultToDocuments()
            } catch (_: Exception) {}
        }

        fun saveSessionToDisk(tabList: List<BrowserTab>, currentActiveId: String) {
            try {
                val json = org.json.JSONObject()
                json.put("activeTabId", currentActiveId)
                val arr = org.json.JSONArray()
                tabList.forEach { tab ->
                    val tObj = org.json.JSONObject().apply {
                        put("id", tab.id)
                        put("title", tab.title)
                        put("url", tab.url)
                        put("profileId", tab.profileId)
                        put("lastAccessedTime", tab.lastAccessedTime)
                    }
                    arr.put(tObj)
                }
                json.put("tabs", arr)
                bridge.saveFile("config/session.json", json.toString().toByteArray(Charsets.UTF_8))
                autoMirrorVaultToDocuments()
            } catch (_: Exception) {}
        }

        fun exportFullBackup() {
            try {
                CookieManager.getInstance().flush()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val backupFilename = "OmniBrowser_Backup_$timestamp.zip"
                val tempZipFile = File(context.cacheDir, backupFilename)

                java.util.zip.ZipOutputStream(FileOutputStream(tempZipFile)).use { zos ->
                    fun addFileToZip(relativePath: String, entryName: String) {
                        val file = File(bridge.getPluginDir(), relativePath)
                        if (file.exists() && file.isFile) {
                            zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }

                    addFileToZip("config/profiles.json", "profiles.json")
                    addFileToZip("config/shortcuts.json", "shortcuts.json")
                    addFileToZip("config/session.json", "session.json")
                    addFileToZip("config/solver.json", "solver.json")

                    // Recursively archive all multi-tenant local IDE vaults
                    val ideDir = File(bridge.getPluginDir(), "ide")
                    if (ideDir.exists() && ideDir.isDirectory) {
                        ideDir.walkTopDown().filter { it.isFile }.forEach { f ->
                            val relPath = "ide/" + f.relativeTo(ideDir).path.replace("\\", "/")
                            zos.putNextEntry(java.util.zip.ZipEntry(relPath))
                            f.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }

                val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val targetDir = File(docsDir, "OmniBackups").apply { mkdirs() }
                val targetFile = File(targetDir, backupFilename)
                tempZipFile.copyTo(targetFile, overwrite = true)
                tempZipFile.delete()

                bridge.log("BACKUP", "Created backup: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                bridge.showToast("✅ Backup saved to Documents/OmniBackups/$backupFilename")

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, Uri.parse("file://${targetFile.absolutePath}"))
                    putExtra(Intent.EXTRA_SUBJECT, "Omni Chrome Backup ($timestamp)")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(Intent.createChooser(shareIntent, "Share Omni Chrome Backup").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (_: Exception) {}

            } catch (e: Exception) {
                bridge.showToast("Backup failed: ${e.message}")
                bridge.log("BACKUP_ERR", "Export error: ${e.message}")
            }
        }

        fun restoreFromBackup(uri: Uri) {
            try {
                val tempFile = File(context.cacheDir, "import_${System.currentTimeMillis()}.zip")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException("Cannot read backup file.")

                var profCount = 0
                var tabCount = 0

                java.util.zip.ZipInputStream(tempFile.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val bytes = zis.readBytes()
                        if (entry.name.startsWith("ide/")) {
                            bridge.saveFile(entry.name, bytes)
                        } else {
                            when (entry.name) {
                                "profiles.json" -> {
                                    bridge.saveFile("config/profiles.json", bytes)
                                    val arr = org.json.JSONArray(String(bytes, Charsets.UTF_8))
                                    val loaded = mutableListOf<BrowserProfile>()
                                    for (i in 0 until arr.length()) {
                                        val obj = arr.getJSONObject(i)
                                        loaded.add(BrowserProfile(obj.getString("id"), obj.getString("name"), obj.getLong("colorValue")))
                                    }
                                    if (loaded.isNotEmpty()) {
                                        profiles = loaded
                                        profCount = loaded.size
                                    }
                                }
                                "shortcuts.json" -> {
                                    bridge.saveFile("config/shortcuts.json", bytes)
                                    val arr = org.json.JSONArray(String(bytes, Charsets.UTF_8))
                                    val loaded = mutableListOf<ShortcutItem>()
                                    for (i in 0 until arr.length()) {
                                        val obj = arr.getJSONObject(i)
                                        loaded.add(
                                            ShortcutItem(
                                                id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                                                title = obj.getString("title"),
                                                url = obj.getString("url"),
                                                iconText = obj.optString("iconText", ""),
                                                colorValue = obj.optLong("colorValue", 0xFF4285F4),
                                                localSourcePath = obj.optString("localSourcePath", null).takeIf { !it.isNullOrEmpty() }
                                            )
                                        )
                                    }
                                    if (loaded.isNotEmpty()) shortcuts = loaded
                                }
                                "session.json" -> {
                                    bridge.saveFile("config/session.json", bytes)
                                    val sObj = org.json.JSONObject(String(bytes, Charsets.UTF_8))
                                    val savedActiveId = sObj.optString("activeTabId", "")
                                    val arr = sObj.optJSONArray("tabs")
                                    if (arr != null && arr.length() > 0) {
                                        val loadedTabs = mutableListOf<BrowserTab>()
                                        for (i in 0 until arr.length()) {
                                            val tObj = arr.getJSONObject(i)
                                            loadedTabs.add(
                                                BrowserTab(
                                                    id = tObj.getString("id"),
                                                    title = tObj.optString("title", "New Tab"),
                                                    url = tObj.optString("url", "about:blank"),
                                                    lastAccessedTime = tObj.optLong("lastAccessedTime", System.currentTimeMillis()),
                                                    profileId = tObj.optString("profileId", "default")
                                                )
                                            )
                                        }
                                        if (loadedTabs.isNotEmpty()) {
                                            tabs = loadedTabs
                                            tabCount = loadedTabs.size
                                            val targetId = if (savedActiveId.isNotEmpty() && loadedTabs.any { it.id == savedActiveId }) savedActiveId else loadedTabs.first().id
                                            activeTabId = targetId
                                            attachTabWebViewHandler?.invoke(targetId)
                                        }
                                    }
                                }
                                "solver.json" -> {
                                    bridge.saveFile("config/solver.json", bytes)
                                    val json = org.json.JSONObject(String(bytes, Charsets.UTF_8))
                                    solverApiKey = json.optString("apiKey", "")
                                    autoSolveEnabled = json.optBoolean("autoSolve", true)
                                }
                                "ide_index.html" -> {
                                    bridge.saveFile("ide/index.html", bytes)
                                }
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
                tempFile.delete()
                autoMirrorVaultToDocuments()
                bridge.showToast("✅ Restored $profCount profiles, $tabCount tabs & isolated IDEs!")
            } catch (e: Exception) {
                bridge.showToast("Restore failed: ${e.message}")
                bridge.log("RESTORE_ERR", "Restore error: ${e.message}")
            }
        }

        restoreTrigger = { uri -> restoreFromBackup(uri) }

        fun saveBase64ToDownloads(base64Data: String, mimeType: String, rawFilename: String) {
            bridge.log("DOWNLOAD", "Processing Base64 payload (mime: '$mimeType', rawFilename: '$rawFilename', len: ${base64Data.length})")
            try {
                val cleanBase64 = if (base64Data.contains(",")) base64Data.substringAfter(",") else base64Data
                val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                bridge.log("DOWNLOAD", "Decoded ${bytes.size} bytes from payload.")

                val ext = when {
                    mimeType.contains("pdf") -> "pdf"
                    mimeType.contains("png") -> "png"
                    mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
                    mimeType.contains("zip") -> "zip"
                    mimeType.contains("json") -> "json"
                    mimeType.contains("html") -> "html"
                    mimeType.contains("javascript") || mimeType.contains("js") -> "js"
                    mimeType.contains("css") -> "css"
                    mimeType.contains("text") -> "txt"
                    else -> "bin"
                }

                var filename = rawFilename.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (filename.isEmpty() || filename == "null" || filename == "blob" || filename == "download") {
                    filename = "download_${System.currentTimeMillis()}.$ext"
                } else if (!filename.contains(".")) {
                    filename = "$filename.$ext"
                }

                var saved = false
                var savedLocation = ""

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                            put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifEmpty { "application/octet-stream" })
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OmniDownloads")
                        }
                        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri)?.use { os ->
                                os.write(bytes)
                            }
                            saved = true
                            savedLocation = "Downloads/OmniDownloads/$filename (MediaStore)"
                            bridge.log("DOWNLOAD", "Saved via MediaStore -> $uri ($savedLocation)")
                        } else {
                            bridge.log("DOWNLOAD_WARN", "MediaStore returned null URI. Falling back to direct filesystem write.")
                        }
                    } catch (e: Exception) {
                        bridge.log("DOWNLOAD_WARN", "MediaStore insert failed: ${e.message}. Falling back to direct File write.")
                    }
                }

                // Fallback / Direct File Writing
                if (!saved) {
                    val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val targetDir = File(publicDocs, "OmniDownloads").apply { if (!exists()) mkdirs() }
                    val targetFile = File(targetDir, filename)
                    FileOutputStream(targetFile).use { it.write(bytes) }
                    saved = true
                    savedLocation = targetFile.absolutePath
                    bridge.log("DOWNLOAD", "Saved directly to File -> $savedLocation (${targetFile.length()} bytes)")
                }

                bridge.showToast("Saved $filename to Downloads/OmniDownloads")
                bridge.log("DOWNLOAD_SUCCESS", "✅ Download complete: $filename (${bytes.size} bytes)")
                refreshCompletedDownloads()
            } catch (e: Exception) {
                bridge.showToast("Blob save failed: ${e.message}")
                bridge.log("DOWNLOAD_ERR", "Failed saving base64 file: ${e.message}\n${e.stackTraceToString()}")
            }
        }

        fun handleExternalUri(url: String, view: WebView?): Boolean {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                if (url.contains("play.google.com/store/apps/details")) {
                    val uri = Uri.parse(url)
                    val pkg = uri.getQueryParameter("id")
                    if (!pkg.isNullOrEmpty()) {
                        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                            setPackage("com.android.vending")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        return try {
                            context.startActivity(marketIntent)
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }
                }
                return false
            }

            if (url.startsWith("intent://")) {
                return try {
                    val parsedIntent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val resolveInfo = context.packageManager.resolveActivity(parsedIntent, 0)
                    if (resolveInfo != null) {
                        context.startActivity(parsedIntent)
                        true
                    } else {
                        val fallbackUrl = parsedIntent.getStringExtra("browser_fallback_url")
                        if (!fallbackUrl.isNullOrEmpty()) {
                            view?.loadUrl(fallbackUrl)
                            true
                        } else {
                            val pkg = parsedIntent.`package`
                            if (!pkg.isNullOrEmpty()) {
                                val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(marketIntent)
                                true
                            } else false
                        }
                    }
                } catch (e: Exception) {
                    bridge.log("INTENT_ERR", "Could not dispatch intent URI: ${e.message}")
                    false
                }
            }

            if (url.startsWith("market://")) {
                return try {
                    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(marketIntent)
                    true
                } catch (e: Exception) {
                    val pkg = Uri.parse(url).getQueryParameter("id")
                    if (!pkg.isNullOrEmpty()) {
                        view?.loadUrl("https://play.google.com/store/apps/details?id=$pkg")
                        true
                    } else false
                }
            }

            return try {
                val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(genericIntent)
                true
            } catch (e: Exception) {
                bridge.log("INTENT_WARN", "No handler for scheme: $url")
                false
            }
        }

        fun triggerFileDownload(view: WebView?, url: String, userAgent: String, contentDisposition: String, mimeType: String) {
            bridge.log("DOWNLOAD", "triggerFileDownload invoked for: ${if (url.length > 40) url.take(40) + "..." else url} (mime: '$mimeType')")

            if (url.startsWith("blob:") || url.startsWith("data:")) {
                val suggestedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                if (url.startsWith("data:")) {
                    saveBase64ToDownloads(url, mimeType, suggestedName)
                } else {
                    val safeName = suggestedName.replace("'", "\\'").replace("\"", "\\\"")
                    val safeMime = mimeType.replace("'", "\\'").replace("\"", "\\\"")
                    val blobScript = """
                        (function() {
                            const blobUrl = '$url';
                            const targetName = '$safeName';
                            const targetMime = '$safeMime';

                            if (window.OmniBlobDownloader && window.OmniBlobDownloader.log) {
                                window.OmniBlobDownloader.log('BLOB_EXTRACT', 'Initiating CSP-safe extraction for: ' + blobUrl);
                            }

                            // 1. Primary Strategy: In-Memory Blob Vault (Zero network, 100% CSP-Immune)
                            if (window.__omniBlobMap && window.__omniBlobMap.has(blobUrl)) {
                                const blobObj = window.__omniBlobMap.get(blobUrl);
                                if (window.OmniBlobDownloader && window.OmniBlobDownloader.log) {
                                    window.OmniBlobDownloader.log('BLOB_VAULT', 'Found Blob in RAM (' + blobObj.size + ' bytes). Reading via FileReader...');
                                }
                                const reader = new FileReader();
                                reader.onloadend = function() {
                                    if (window.OmniBlobDownloader && window.OmniBlobDownloader.processBlob) {
                                        window.OmniBlobDownloader.processBlob(reader.result, targetMime || blobObj.type || 'application/octet-stream', targetName);
                                    }
                                };
                                reader.onerror = function(e) {
                                    if (window.OmniBlobDownloader && window.OmniBlobDownloader.processBlob) {
                                        window.OmniBlobDownloader.processBlob('ERROR', '', 'FileReader error: ' + (e ? e.toString() : 'unknown'));
                                    }
                                };
                                reader.readAsDataURL(blobObj);
                                return;
                            }

                            // 2. Secondary Strategy: CSP-Safe Hidden IFrame Extraction (Bypasses connect-src entirely)
                            if (window.OmniBlobDownloader && window.OmniBlobDownloader.log) {
                                window.OmniBlobDownloader.log('BLOB_FALLBACK', 'Blob not in RAM map. Launching CSP-immune iframe extractor...');
                            }

                            try {
                                const iframe = document.createElement('iframe');
                                iframe.style.display = 'none';
                                iframe.style.width = '0px';
                                iframe.style.height = '0px';
                                iframe.src = blobUrl;

                                let resolved = false;

                                iframe.onload = function() {
                                    if (resolved) return;
                                    resolved = true;
                                    try {
                                        const iDoc = iframe.contentDocument || iframe.contentWindow.document;
                                        if (iDoc) {
                                            const text = iDoc.body ? (iDoc.body.innerText || iDoc.body.textContent || '') : '';
                                            if (text && text.length > 0) {
                                                const base64Text = btoa(unescape(encodeURIComponent(text)));
                                                const dataUrl = 'data:' + (targetMime || 'text/plain') + ';base64,' + base64Text;
                                                if (window.OmniBlobDownloader && window.OmniBlobDownloader.processBlob) {
                                                    window.OmniBlobDownloader.processBlob(dataUrl, targetMime || 'text/plain', targetName);
                                                }
                                                setTimeout(function() { try { document.body.removeChild(iframe); } catch(_) {} }, 1000);
                                                return;
                                            }
                                        }
                                    } catch(e) {
                                        if (window.OmniBlobDownloader && window.OmniBlobDownloader.log) {
                                            window.OmniBlobDownloader.log('IFRAME_WARN', 'Iframe DOM read exception: ' + e.toString());
                                        }
                                    }
                                };

                                iframe.onerror = function(err) {
                                    if (window.OmniBlobDownloader && window.OmniBlobDownloader.log) {
                                        window.OmniBlobDownloader.log('IFRAME_ERR', 'Iframe failed to load blob URL: ' + (err ? err.toString() : 'unknown'));
                                    }
                                };

                                document.body.appendChild(iframe);

                                setTimeout(function() {
                                    if (!resolved) {
                                        resolved = true;
                                        if (window.OmniBlobDownloader && window.OmniBlobDownloader.processBlob) {
                                            window.OmniBlobDownloader.processBlob('ERROR', '', 'Extraction timed out (Blob created before vault hook)');
                                        }
                                        try { document.body.removeChild(iframe); } catch(_) {}
                                    }
                                }, 6000);

                            } catch (err) {
                                if (window.OmniBlobDownloader && window.OmniBlobDownloader.processBlob) {
                                    window.OmniBlobDownloader.processBlob('ERROR', '', 'Extractor exception: ' + err.toString());
                                }
                            }
                        })();
                    """.trimIndent()
                    val targetView = view ?: webViewInstance
                    targetView?.evaluateJavascript(blobScript, null)
                    bridge.showToast("Extracting Blob download...")
                }
                return
            }

            try {
                val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    if (mimeType.isNotEmpty() && mimeType != "application/octet-stream") {
                        setMimeType(mimeType)
                    }
                    addRequestHeader("User-Agent", userAgent)
                    val cookie = CookieManager.getInstance().getCookie(url)
                    if (!cookie.isNullOrEmpty()) {
                        addRequestHeader("Cookie", cookie)
                    }
                    setDescription("Downloading file...")
                    setTitle(filename)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                }

                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                val dlId = dm?.enqueue(request)
                if (dlId != null) {
                    trackedDownloadIds.add(dlId)
                }
                bridge.showToast("Downloading $filename...")
                bridge.log("DOWNLOAD", "Queued system download for $url ($filename) [ID: $dlId]")
            } catch (e: Exception) {
                bridge.showToast("Download error: ${e.message}")
                bridge.log("DOWNLOAD_ERR", "Download queue exception: ${e.message}")
            }
        }

        // Load persisted solver and profile configurations (with Auto-Vault Resurrection)
        LaunchedEffect(Unit) {
            try {
                val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val vaultDir = File(docsDir, ".omni_vault")
                if (vaultDir.exists()) {
                    fun restoreIfMissing(relPath: String, vaultName: String) {
                        if (bridge.readFile(relPath) == null) {
                            val vFile = File(vaultDir, vaultName)
                            if (vFile.exists() && vFile.isFile) {
                                bridge.saveFile(relPath, vFile.readBytes())
                            }
                        }
                    }
                    restoreIfMissing("config/profiles.json", "profiles.json")
                    restoreIfMissing("config/shortcuts.json", "shortcuts.json")
                    restoreIfMissing("config/session.json", "session.json")
                    restoreIfMissing("config/solver.json", "solver.json")

                    // Resurrect multi-tenant IDE vaults from Documents
                    val vaultIdeDir = File(vaultDir, "ide")
                    if (vaultIdeDir.exists() && vaultIdeDir.isDirectory) {
                        vaultIdeDir.walkTopDown().filter { it.isFile }.forEach { f ->
                            val relPath = "ide/" + f.relativeTo(vaultIdeDir).path.replace("\\", "/")
                            if (bridge.readFile(relPath) == null) {
                                bridge.saveFile(relPath, f.readBytes())
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            try {
                val savedBytes = bridge.readFile("config/solver.json")
                if (savedBytes != null) {
                    val json = org.json.JSONObject(String(savedBytes, Charsets.UTF_8))
                    solverApiKey = json.optString("apiKey", "")
                    autoSolveEnabled = json.optBoolean("autoSolve", true)
                }
            } catch (_: Exception) {}

            try {
                val scBytes = bridge.readFile("config/shortcuts.json")
                if (scBytes != null) {
                    val arr = org.json.JSONArray(String(scBytes, Charsets.UTF_8))
                    val loadedSc = mutableListOf<ShortcutItem>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        loadedSc.add(
                            ShortcutItem(
                                id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                                title = obj.getString("title"),
                                url = obj.getString("url"),
                                iconText = obj.optString("iconText", ""),
                                colorValue = obj.optLong("colorValue", 0xFF4285F4),
                                localSourcePath = obj.optString("localSourcePath", null).takeIf { !it.isNullOrEmpty() }
                            )
                        )
                    }
                    if (loadedSc.isNotEmpty()) {
                        shortcuts = loadedSc
                    }
                }
            } catch (_: Exception) {}

            try {
                val profBytes = bridge.readFile("config/profiles.json")
                if (profBytes != null) {
                    val arr = org.json.JSONArray(String(profBytes, Charsets.UTF_8))
                    val loaded = mutableListOf<BrowserProfile>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        loaded.add(BrowserProfile(obj.getString("id"), obj.getString("name"), obj.getLong("colorValue")))
                    }
                    if (loaded.isNotEmpty()) {
                        profiles = loaded
                    }
                }
            } catch (_: Exception) {}

            try {
                val sessionBytes = bridge.readFile("config/session.json")
                if (sessionBytes != null) {
                    val sObj = org.json.JSONObject(String(sessionBytes, Charsets.UTF_8))
                    val arr = sObj.optJSONArray("tabs")
                    if (arr != null && arr.length() > 0) {
                        val loadedTabs = mutableListOf<BrowserTab>()
                        for (i in 0 until arr.length()) {
                            val tObj = arr.getJSONObject(i)
                            loadedTabs.add(
                                BrowserTab(
                                    id = tObj.getString("id"),
                                    title = tObj.optString("title", "New Tab"),
                                    url = tObj.optString("url", "about:blank"),
                                    lastAccessedTime = tObj.optLong("lastAccessedTime", System.currentTimeMillis()),
                                    profileId = tObj.optString("profileId", "default")
                                )
                            )
                        }
                        if (loadedTabs.isNotEmpty()) {
                            val homeTabId = "tab_home_${System.currentTimeMillis()}"
                            val homeTab = BrowserTab(
                                id = homeTabId,
                                title = "New Tab",
                                url = "about:blank",
                                profileId = "default"
                            )
                            val nonBlankTabs = loadedTabs.filter { it.url != "about:blank" }
                            tabs = listOf(homeTab) + nonBlankTabs
                            activeTabId = homeTabId
                            currentUrl = "about:blank"
                            urlInputText = ""
                            pageTitle = "New Tab"
                            attachTabWebViewHandler?.invoke(homeTabId)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        fun captureThumbnail(): Bitmap? {
            val wv = webViewInstance ?: return null
            if (wv.width <= 0 || wv.height <= 0) return null
            return try {
                val scale = 0.5f
                val w = (wv.width * scale).toInt().coerceAtLeast(1)
                val h = (wv.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                val canvas = Canvas(bitmap)
                canvas.scale(scale, scale)
                wv.draw(canvas)
                bitmap
            } catch (_: Exception) {
                null
            }
        }

        fun extractSolverError(json: org.json.JSONObject, raw: String): String {
            val desc = json.optString("errorDescription")
            if (desc.isNotEmpty()) return desc
            val code = json.optString("errorCode")
            if (code.isNotEmpty()) return code
            val msg = json.optString("message", json.optString("msg", json.optString("error", "")))
            if (msg.isNotEmpty()) return msg
            val status = json.optString("status")
            if (status.isNotEmpty() && status != "processing") return status
            return if (raw.length > 120) raw.take(120) + "..." else raw
        }

        fun executeSolver(siteKey: String, pageUrl: String) {
            if (solverApiKey.isEmpty()) {
                bridge.showToast("Configure NoCaptchaAI API key in settings first.")
                return
            }
            if (isSolvingCaptcha) return

            isSolvingCaptcha = true
            bridge.showToast("🤖 Solving CAPTCHA with NoCaptchaAI...")
            bridge.log("SOLVER", "Creating task for sitekey: $siteKey on $pageUrl")

            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val createPayload = org.json.JSONObject().apply {
                        put("clientKey", solverApiKey)
                        put("task", org.json.JSONObject().apply {
                            put("type", "ReCaptchaV2TaskProxyLess")
                            put("websiteURL", pageUrl)
                            put("websiteKey", siteKey)
                        })
                    }

                    var responseStr = bridge.httpPost("https://api.nocaptchaai.com/createTask", createPayload.toString())
                    if (responseStr == null) {
                        responseStr = bridge.httpPost("https://api.nocaptchaai.com/solve", createPayload.toString())
                    }

                    if (responseStr != null) {
                        bridge.log("SOLVER", "Task Creation Response: $responseStr")
                        val respObj = org.json.JSONObject(responseStr)

                        var solutionToken = respObj.optJSONObject("solution")?.optString("gRecaptchaResponse")
                            ?: respObj.optString("token", "")

                        val taskId = respObj.optString("taskId", "")

                        if (solutionToken.isEmpty() && taskId.isNotEmpty()) {
                            bridge.log("SOLVER", "Task registered with ID: $taskId. Polling for solution...")
                            val pollPayload = org.json.JSONObject().apply {
                                put("clientKey", solverApiKey)
                                put("taskId", taskId)
                            }.toString()

                            var attempts = 0
                            while (attempts < 15 && solutionToken.isEmpty()) {
                                delay(2000)
                                attempts++
                                val pollRespStr = bridge.httpPost("https://api.nocaptchaai.com/getTaskResult", pollPayload)
                                if (pollRespStr != null) {
                                    val pollObj = org.json.JSONObject(pollRespStr)
                                    val status = pollObj.optString("status", "")
                                    bridge.log("SOLVER", "Poll #$attempts status: $status")

                                    if (status == "ready" || status == "solved") {
                                        solutionToken = pollObj.optJSONObject("solution")?.optString("gRecaptchaResponse")
                                            ?: pollObj.optString("token", "")
                                        break
                                    } else if (status == "failed" || pollObj.optInt("errorId", 0) != 0) {
                                        val err = extractSolverError(pollObj, pollRespStr)
                                        withContext(Dispatchers.Main) {
                                            bridge.showToast("Solver failed: $err")
                                            bridge.log("SOLVER_ERR", "Polling Error: $pollRespStr")
                                        }
                                        return@launch
                                    }
                                }
                            }
                        }

                        if (solutionToken.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                val injectionScript = """
                                    javascript:(function() {
                                        const token = '$solutionToken';
                                        const textareas = document.querySelectorAll('textarea[name="g-recaptcha-response"], #g-recaptcha-response');
                                        textareas.forEach(t => { t.value = token; t.innerHTML = token; });
                                        try {
                                            if (window.___grecaptcha_cfg && window.___grecaptcha_cfg.clients) {
                                                Object.values(window.___grecaptcha_cfg.clients).forEach(client => {
                                                    for (const key in client) {
                                                        if (client[key] && typeof client[key].callback === 'function') {
                                                            client[key].callback(token);
                                                        }
                                                    }
                                                });
                                            }
                                        } catch(e) {}
                                    })();
                                """.trimIndent()
                                webViewInstance?.evaluateJavascript(injectionScript, null)
                                bridge.showToast("✅ CAPTCHA Solved & Injected!")
                                bridge.log("SOLVER", "Successfully injected response token into DOM.")
                            }
                        } else {
                            val errMsg = extractSolverError(respObj, responseStr)
                            withContext(Dispatchers.Main) {
                                bridge.showToast("Solver returned: $errMsg")
                                bridge.log("SOLVER_ERR", "API Response: $responseStr")
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            bridge.showToast("Failed to reach solver API endpoint.")
                            bridge.log("SOLVER_ERR", "HTTP POST to solver endpoint returned null.")
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        bridge.showToast("Solver error: ${e.message}")
                        bridge.log("SOLVER_ERR", "Exception: ${e.message}")
                    }
                } finally {
                    isSolvingCaptcha = false
                }
            }
        }

        fun scanAndSolveCaptcha() {
            val detectorScript = """
                (function() {
                    const el = document.querySelector('[data-sitekey]');
                    if (el) return el.getAttribute('data-sitekey');
                    const iframe = document.querySelector('iframe[src*="recaptcha"], iframe[src*="turnstile"]');
                    if (iframe) {
                        const m = iframe.src.match(/k=([^&]+)/) || iframe.src.match(/sitekey=([^&]+)/);
                        if (m) return m[1];
                    }
                    return '';
                })();
            """.trimIndent()

            webViewInstance?.evaluateJavascript(detectorScript) { siteKeyRaw ->
                val siteKey = siteKeyRaw?.replace("\"", "")?.trim() ?: ""
                if (siteKey.isNotEmpty()) {
                    executeSolver(siteKey, currentUrl)
                } else {
                    bridge.showToast("No active CAPTCHA widget found on page.")
                }
            }
        }

        fun pruneHotPool(keepTabId: String) {
            if (webViewPool.size <= MAX_HOT_TABS) return

            val tabsInPool = tabs.filter { it.id in webViewPool.keys && it.id != keepTabId }
                .sortedBy { it.lastAccessedTime }

            var numToEvict = webViewPool.size - MAX_HOT_TABS
            for (tabToEvict in tabsInPool) {
                if (numToEvict <= 0) break
                val wv = webViewPool.remove(tabToEvict.id)
                if (wv != null) {
                    val bundle = Bundle()
                    wv.saveState(bundle)
                    tabs = tabs.map { if (it.id == tabToEvict.id) it.copy(stateBundle = bundle) else it }

                    wv.stopLoading()
                    wv.onPause()
                    containerLayout?.removeView(wv)
                    wv.destroy()
                    numToEvict--
                    bridge.log("HOT_POOL", "Evicted cold tab: ${tabToEvict.id} (${tabToEvict.title})")
                }
            }
            CookieManager.getInstance().flush()
        }

        fun createConfiguredWebView(tabId: String, initialUrl: String, savedState: Bundle? = null, profileId: String = "default"): WebView {
            val webView = WebView(context).apply {
                setBackgroundColor(android.graphics.Color.parseColor("#1F2227"))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    try {
                        WebViewCompat.addDocumentStartJavaScript(this, blobInterceptorScript, setOf("*"))
                    } catch (_: Exception) {}
                }

                // Multi-Profile container binding & Cookie Isolation
                if (profileId != "default" && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    try {
                        val profileStore = ProfileStore.getInstance()
                        val profile = profileStore.getOrCreateProfile(profileId)
                        WebViewCompat.setProfile(this, profileId)
                        profile.cookieManager.setAcceptCookie(true)
                        profile.cookieManager.setAcceptThirdPartyCookies(this, true)
                    } catch (e: Exception) {
                        bridge.log("PROFILE_WARN", "Could not set profile '$profileId': ${e.message}")
                    }
                } else {
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                }

                setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                    triggerFileDownload(this, url, userAgent, contentDisposition, mimeType)
                }

                val blobBridge = OmniBlobBridge(
                    onBlobReceived = { base64Data, mime, filename ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (base64Data == "ERROR") {
                                bridge.showToast("Blob extract failed: $filename")
                                bridge.log("DOWNLOAD_ERR", "Blob extraction error: $filename")
                            } else {
                                saveBase64ToDownloads(base64Data, mime, filename)
                            }
                        }
                    },
                    onLog = { tag, msg ->
                        bridge.log(tag, msg)
                    }
                )
                addJavascriptInterface(blobBridge, "OmniBlobDownloader")

                val rawUA = settings.userAgentString
                val mobileUserAgent = rawUA.replace("; wv", "").replace(Regex("Version/[0-9.]+ "), "")
                mobileUA = mobileUserAgent
                settings.userAgentString = if (isDesktopMode) desktopUA else mobileUserAgent

                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message?
                    ): Boolean {
                        if (resultMsg == null) return false
                        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                        val tempWv = WebView(context).apply {
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                                    val targetUrl = req?.url?.toString() ?: return false
                                    if (handleExternalUri(targetUrl, v)) {
                                        return true
                                    }
                                    createNewTabHandler?.invoke(targetUrl)
                                    return true
                                }
                            }
                        }
                        transport.webView = tempWv
                        resultMsg.sendToTarget()
                        return true
                    }

                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        if (activeTabId == tabId) {
                            loadProgress = newProgress / 100f
                            isLoading = newProgress in 1..99
                        }
                    }

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        if (!title.isNullOrEmpty()) {
                            if (activeTabId == tabId) pageTitle = title
                            tabs = tabs.map { if (it.id == tabId) it.copy(title = title) else it }
                            saveSessionToDisk(tabs, activeTabId)
                        }
                    }

                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        bridge.log("CHROME_CONSOLE", "[$tabId][${consoleMessage?.messageLevel()}] ${consoleMessage?.message()}")
                        return true
                    }

                    override fun onPermissionRequest(request: PermissionRequest?) {
                        request?.grant(request.resources)
                    }

                    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                        callback?.invoke(origin, true, false)
                    }

                    override fun onShowFileChooser(
                        view: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        activeFileChooserCallback?.onReceiveValue(null)
                        activeFileChooserCallback = filePathCallback
                        return try {
                            val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }
                            fileChooserLauncher.launch(intent)
                            true
                        } catch (e: Exception) {
                            bridge.log("FILE_CHOOSER_ERR", "Failed to launch file picker: ${e.message}")
                            activeFileChooserCallback?.onReceiveValue(null)
                            activeFileChooserCallback = null
                            false
                        }
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return false
                        if (url.startsWith("blob:") || url.startsWith("data:")) {
                            triggerFileDownload(view, url, view?.settings?.userAgentString ?: "", "", "")
                            return true
                        }
                        return handleExternalUri(url, view)
                    }

                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                        handler?.proceed()
                    }

                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        if (activeTabId == tabId) {
                            canGoBack = view?.canGoBack() ?: false
                            canGoForward = view?.canGoForward() ?: false
                            if (url != null && url != "about:blank") {
                                currentUrl = url
                                urlInputText = url
                            }
                        }
                        if (url != null && url != "about:blank") {
                            tabs = tabs.map { if (it.id == tabId) it.copy(url = url) else it }
                            saveSessionToDisk(tabs, activeTabId)
                        }
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        if (activeTabId == tabId) isLoading = true
                        view?.evaluateJavascript(blobInterceptorScript, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (activeTabId == tabId) isLoading = false
                        view?.evaluateJavascript(blobInterceptorScript, null)
                        CookieManager.getInstance().flush()

                        if (autoSolveEnabled && solverApiKey.isNotEmpty() && url != null && url != "about:blank") {
                            val detectorScript = """
                                (function() {
                                    const el = document.querySelector('[data-sitekey]');
                                    if (el) return el.getAttribute('data-sitekey');
                                    const iframe = document.querySelector('iframe[src*="recaptcha"], iframe[src*="turnstile"]');
                                    if (iframe) {
                                        const m = iframe.src.match(/k=([^&]+)/) || iframe.src.match(/sitekey=([^&]+)/);
                                        if (m) return m[1];
                                    }
                                    return '';
                                })();
                            """.trimIndent()

                            view?.evaluateJavascript(detectorScript) { siteKeyRaw ->
                                val siteKey = siteKeyRaw?.replace("\"", "")?.trim() ?: ""
                                if (siteKey.isNotEmpty()) {
                                    executeSolver(siteKey, url)
                                }
                            }
                        }
                    }
                }

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    allowFileAccessFromFileURLs = true
                    allowUniversalAccessFromFileURLs = true
                    setSupportMultipleWindows(true)
                    javaScriptCanOpenWindowsAutomatically = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mediaPlaybackRequiresUserGesture = false
                    loadWithOverviewMode = true
                    useWideViewPort = true

                    try {
                        val method = javaClass.getMethod("setRequestedWithHeaderOriginAllowList", Set::class.java)
                        method.invoke(this, emptySet<String>())
                    } catch (_: Exception) {}
                }
            }

            if (savedState != null) {
                webView.restoreState(savedState)
            } else if (initialUrl.isNotEmpty() && initialUrl != "about:blank") {
                webView.loadUrl(initialUrl)
            }
            return webView
        }

        fun attachTabWebView(targetTabId: String) {
            val container = containerLayout ?: return
            val now = System.currentTimeMillis()
            tabs = tabs.map { if (it.id == targetTabId) it.copy(lastAccessedTime = now) else it }

            webViewPool.forEach { (id, wv) ->
                if (id != targetTabId) {
                    wv.onPause()
                }
            }
            CookieManager.getInstance().flush()
            container.removeAllViews()

            val targetTab = tabs.find { it.id == targetTabId } ?: return
            val isNewInstance = !webViewPool.containsKey(targetTabId)

            val targetWv = webViewPool.getOrPut(targetTabId) {
                createConfiguredWebView(targetTabId, targetTab.url, targetTab.stateBundle, targetTab.profileId)
            }

            if (isNewInstance) {
                pruneHotPool(targetTabId)
            }

            // Hydrate cold/restored webviews that have an active URL
            if (targetTab.url.isNotEmpty() && targetTab.url != "about:blank" && (targetWv.url == null || targetWv.url == "about:blank")) {
                targetWv.loadUrl(targetTab.url)
            }

            container.addView(targetWv)
            targetWv.onResume()
            webViewInstance = targetWv

            currentUrl = targetTab.url
            urlInputText = if (targetTab.url == "about:blank") "" else targetTab.url
            pageTitle = targetTab.title
            canGoBack = targetWv.canGoBack()
            canGoForward = targetWv.canGoForward()
        }

        attachTabWebViewHandler = { targetId -> attachTabWebView(targetId) }

        fun createNewTab(targetUrl: String = "about:blank", targetProfileId: String = selectedProfileId) {
            val thumb = captureThumbnail()
            val bundle = Bundle()
            webViewInstance?.saveState(bundle)

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

            saveSessionToDisk(tabs, newId)
            attachTabWebView(newId)
        }

        createNewTabHandler = { targetUrl -> createNewTab(targetUrl) }

        fun switchToTab(targetId: String) {
            if (targetId == activeTabId) {
                isTabSwitcherOpen = false
                return
            }
            val thumb = captureThumbnail()
            val bundle = Bundle()
            webViewInstance?.saveState(bundle)

            val updatedTabs = tabs.map { 
                if (it.id == activeTabId) it.copy(thumbnail = thumb ?: it.thumbnail, stateBundle = bundle) else it 
            }
            tabs = updatedTabs
            activeTabId = targetId
            isTabSwitcherOpen = false

            saveSessionToDisk(tabs, targetId)
            attachTabWebView(targetId)
        }

        fun closeTab(targetId: String) {
            purgePendingWebViews()

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
                purgePendingWebViews()
                lastClosedTabsSnapshot = null
            }

            // Move WebView to hot staging limbo instead of destroying it
            webViewPool.remove(targetId)?.let { wv ->
                wv.onPause()
                containerLayout?.removeView(wv)
                pendingPurgeWebViews[targetId] = wv
            }

            val currentIdx = tabs.indexOfFirst { it.id == targetId }
            val remainingTabs = tabs.filter { it.id != targetId }

            if (remainingTabs.isEmpty()) {
                val newId = "tab_${System.currentTimeMillis()}"
                val freshTab = BrowserTab(id = newId, title = "New Tab", url = "about:blank")
                tabs = listOf(freshTab)
                activeTabId = newId
                saveSessionToDisk(tabs, newId)
                attachTabWebView(newId)
            } else {
                tabs = remainingTabs
                if (targetId == activeTabId) {
                    val nextIdx = (currentIdx - 1).coerceAtLeast(0).coerceAtMost(remainingTabs.size - 1)
                    val nextTab = remainingTabs[nextIdx]
                    activeTabId = nextTab.id
                    saveSessionToDisk(tabs, nextTab.id)
                    attachTabWebView(nextTab.id)
                } else {
                    saveSessionToDisk(tabs, activeTabId)
                }
            }
        }

        fun closeAllTabs() {
            purgePendingWebViews()

            val count = tabs.size
            lastClosedTabsSnapshot = tabs
            lastActiveTabIdSnapshot = activeTabId
            undoMessage = "$count tabs closed"
            showUndoBanner = true
            undoJob?.cancel()
            undoJob = coroutineScope.launch {
                delay(4500)
                showUndoBanner = false
                purgePendingWebViews()
                lastClosedTabsSnapshot = null
            }

            // Move all active WebViews into hot staging limbo
            webViewPool.forEach { (id, wv) ->
                wv.onPause()
                pendingPurgeWebViews[id] = wv
            }
            webViewPool.clear()
            containerLayout?.removeAllViews()

            val newId = "tab_${System.currentTimeMillis()}"
            tabs = listOf(BrowserTab(id = newId, title = "New Tab", url = "about:blank"))
            activeTabId = newId
            isTabSwitcherOpen = false

            saveSessionToDisk(tabs, newId)
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
                focusManager.clearFocus()
                return
            }

            isHomeOverlayOpen = false
            urlInputText = if (target == "about:blank") "" else target
            currentUrl = target
            tabs = tabs.map { if (it.id == activeTabId) it.copy(url = target) else it }
            webViewInstance?.loadUrl(target)
            focusManager.clearFocus()
        }

        fun injectErudaDevTools() {
            if (currentUrl == "about:blank" || webViewInstance == null) {
                bridge.showToast("Cannot inject DevTools on a blank page.")
                return
            }
            bridge.showToast("🛠️ Initializing Eruda DevTools...")
            val erudaScript = """
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
                        return;
                    }
                    var script = document.createElement('script');
                    script.src = 'https://cdn.jsdelivr.net/npm/eruda';
                    script.onload = function() {
                        eruda.init({
                            tool: ['console', 'elements', 'network', 'resource', 'info', 'snippets', 'storage'],
                            defaults: {
                                displaySize: 60,
                                transparency: 0.95,
                                theme: 'Dark'
                            }
                        });
                        eruda.show();
                    };
                    document.body.appendChild(script);
                })();
            """.trimIndent()
            webViewInstance?.evaluateJavascript(erudaScript, null)
        }

        fun saveHtmlSnapshot(rawHtml: String, prefix: String = "DOM_Dump"): String {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "${prefix}_$timestamp.html"
            var savedToDownloads = false

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/html")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OmniSnapshots")
                    }
                    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            os.write(rawHtml.toByteArray(Charsets.UTF_8))
                        }
                        savedToDownloads = true
                    }
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val targetDir = File(downloadsDir, "OmniSnapshots").apply { mkdirs() }
                    val targetFile = File(targetDir, filename)
                    FileOutputStream(targetFile).use { os ->
                        os.write(rawHtml.toByteArray(Charsets.UTF_8))
                    }
                    savedToDownloads = true
                }
            } catch (e: Exception) {
                bridge.log("DOM_SNAPSHOT_ERR", "Downloads folder write failed: ${e.message}")
            }

            bridge.saveFile("snapshots/$filename", rawHtml.toByteArray(Charsets.UTF_8))
            val locationMsg = if (savedToDownloads) "Downloads/OmniSnapshots/$filename" else "snapshots/$filename"
            bridge.log("DOM_SNAPSHOT", "Saved DOM snapshot: $locationMsg (${rawHtml.length} bytes)")
            bridge.showToast("✅ Saved DOM to $locationMsg")
            return locationMsg
        }

        fun captureDomSnapshot() {
            val script = """
                (function() {
                    try {
                        var clone = document.documentElement.cloneNode(true);
                        var head = clone.querySelector('head');
                        if (head) {
                            var existingBase = head.querySelector('base');
                            if (!existingBase) {
                                var base = document.createElement('base');
                                base.href = window.location.href;
                                head.insertBefore(base, head.firstChild);
                            }
                        }
                        return '<!DOCTYPE html>\n' + clone.outerHTML;
                    } catch(e) {
                        return document.documentElement.outerHTML;
                    }
                })();
            """.trimIndent()

            webViewInstance?.evaluateJavascript(script) { html ->
                if (!html.isNullOrEmpty()) {
                    val rawHtml = if (html.startsWith("\"") && html.endsWith("\"")) {
                        try {
                            org.json.JSONObject("{\"h\":$html}").getString("h")
                        } catch (_: Exception) { html }
                    } else html

                    saveHtmlSnapshot(rawHtml, "DOM_Dump")
                } else {
                    bridge.showToast("Could not capture page DOM.")
                }
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

        DisposableEffect(isTabSwitcherOpen, showSettingsDialog, showDownloadsDialog, showAutomationDialog, showAutomationResultDialog, editingProfile, editingShortcut, isAddingShortcut, isHomeOverlayOpen, canGoBack, currentUrl, webViewInstance, showMenu) {
            bridge.setOnBackPressedHandler {
                when {
                    showMenu -> {
                        showMenu = false
                        true
                    }
                    showAutomationResultDialog -> {
                        showAutomationResultDialog = false
                        true
                    }
                    showAutomationDialog -> {
                        showAutomationDialog = false
                        true
                    }
                    showDownloadsDialog -> {
                        showDownloadsDialog = false
                        true
                    }
                    editingShortcut != null -> {
                        editingShortcut = null
                        true
                    }
                    isAddingShortcut -> {
                        isAddingShortcut = false
                        true
                    }
                    editingProfile != null -> {
                        editingProfile = null
                        true
                    }
                    showSettingsDialog -> {
                        showSettingsDialog = false
                        true
                    }
                    isTabSwitcherOpen -> {
                        isTabSwitcherOpen = false
                        true
                    }
                    isHomeOverlayOpen -> {
                        isHomeOverlayOpen = false
                        true
                    }
                    currentUrl == "about:blank" -> {
                        false
                    }
                    webViewInstance?.canGoBack() == true -> {
                        webViewInstance?.goBack()
                        true
                    }
                    else -> {
                        navigateTo("about:blank")
                        true
                    }
                }
            }
            onDispose {
                bridge.setOnBackPressedHandler(null)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1F2227))
                    .statusBarsPadding()
                    .imePadding()
            ) {
                val activeTab = tabs.find { it.id == activeTabId }
                val activeProf = profiles.find { it.id == activeTab?.profileId } ?: profiles.firstOrNull() ?: BrowserProfile("default", "Default", 0xFF8AB4F8)
                val profColor = remember(activeProf.colorValue) { Color(activeProf.colorValue) }

                // --- Top Chrome Omnibox Header ---
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
                        IconButton(
                            onClick = {
                                if (currentUrl != "about:blank") {
                                    isHomeOverlayOpen = !isHomeOverlayOpen
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Home,
                                contentDescription = "Home",
                                tint = if (isHomeOverlayOpen || currentUrl == "about:blank") profColor else Color(0xFF9AA0A6)
                            )
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
                                    onValueChange = { urlInputText = it },
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
                                        onGo = { navigateTo(urlInputText) }
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
                                )

                                if (urlInputText.isNotEmpty()) {
                                    IconButton(
                                        onClick = { urlInputText = "" },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color(0xFF9AA0A6), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.width(6.dp))

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(profColor.copy(alpha = 0.2f))
                                .border(1.5.dp, profColor, RoundedCornerShape(6.dp))
                                .clickable {
                                    val thumb = captureThumbnail()
                                    val bundle = Bundle()
                                    webViewInstance?.saveState(bundle)
                                    tabs = tabs.map { if (it.id == activeTabId) it.copy(stateBundle = bundle, thumbnail = thumb ?: it.thumbnail) else it }
                                    CookieManager.getInstance().flush()
                                    isTabSwitcherOpen = true
                                }
                        ) {
                            Text(
                                "${tabs.size}",
                                color = profColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                                                Spacer(Modifier.width(2.dp))

                        IconButton(
                            onClick = { showMenu = !showMenu },
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

                // --- Loading Progress Bar ---
                AnimatedVisibility(
                    visible = isLoading,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    LinearProgressIndicator(
                        progress = { loadProgress },
                        color = profColor,
                        trackColor = Color(0xFF282C34),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                    )
                }

                // --- Main Content (Speed Dial vs WebView) ---
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    AndroidView(
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                containerLayout = this
                                val initialWv = webViewPool.getOrPut(activeTabId) {
                                    createConfiguredWebView(activeTabId, currentUrl)
                                }
                                webViewInstance = initialWv
                                addView(initialWv)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (currentUrl == "about:blank" || isHomeOverlayOpen) {
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
                                        .clickable { isHomeOverlayOpen = false }
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
                                            "Profile: ${activeProf.name}",
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
                                        fetchFavicon(domain)
                                    }
                                    val iconBmp = faviconCache[domain]

                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .combinedClickable(
                                                onClick = {
                                                    if (isHomeOverlayOpen && currentUrl != "about:blank") {
                                                        isHomeOverlayOpen = false
                                                        createNewTab(item.url)
                                                    } else {
                                                        navigateTo(item.url)
                                                    }
                                                },
                                                onLongClick = {
                                                    bridge.vibrate(40L)
                                                    editingShortcut = item
                                                }
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
                                            .clickable { isAddingShortcut = true }
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
                }
            }

            // --- Headless AI Studio Automation Engine ---
            fun startAutomationRun() {
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

                // Clean up any stale headless worker
                try {
                    headlessAutomationWv?.stopLoading()
                    headlessAutomationWv?.onPause()
                    containerLayout?.removeView(headlessAutomationWv)
                    headlessAutomationWv?.destroy()
                } catch (_: Exception) {}

                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

                val jsEscapedPrompt = org.json.JSONObject.quote(autoUserPrompt.trim())
                val jsEscapedSysPrompt = org.json.JSONObject.quote(autoSystemPrompt.trim())
                val jsEscapedThinking = org.json.JSONObject.quote(autoThinkingLevel)
                val jsEscapedModel = org.json.JSONObject.quote(autoSelectedModel)

                val automationScript = """
                    (async function() {
                        if (window.__omniAutomating) {
                            return;
                        }
                        window.__omniAutomating = true;

                        const PROMPT = $jsEscapedPrompt;
                        const SYS_PROMPT = $jsEscapedSysPrompt;
                        const THINKING_LEVEL = $jsEscapedThinking;
                        const TARGET_MODEL = $jsEscapedModel;

                        const delay = ms => new Promise(r => setTimeout(r, ms));

                        function hostLog(tag, msg) {
                            try {
                                if (window.OmniAutomator && window.OmniAutomator.onLog) {
                                    window.OmniAutomator.onLog(tag, String(msg));
                                }
                            } catch(_) {}
                        }

                        function updateStatus(msg) {
                            hostLog('STATUS', msg);
                            try {
                                if (window.OmniAutomator && window.OmniAutomator.onStatus) {
                                    window.OmniAutomator.onStatus(String(msg));
                                }
                            } catch(_) {}
                        }

                        try {
                            hostLog('INIT', 'AI Studio Automator script launched. URL: ' + window.location.href);

                            // 1. Auth check
                            if (window.location.href.includes('accounts.google.com') || window.location.href.includes('/signin/')) {
                                const authErr = 'Account not logged in on this profile. Please log in to Google AI Studio first.';
                                hostLog('AUTH_ERR', authErr);
                                if (window.OmniAutomator) window.OmniAutomator.onError(authErr);
                                window.__omniAutomating = false;
                                return;
                            }

                            updateStatus('Waiting for AI Studio UI and Session Handshake...');
                            let mountAttempts = 0;
                            let promptArea = null;

                            while (mountAttempts < 45) {
                                promptArea = document.querySelector(
                                    'textarea[formcontrolname="promptText"], ' +
                                    'ms-autosize-textarea textarea, ' +
                                    'textarea[aria-label*="Prompt"], ' +
                                    'textarea[placeholder*="Start typing"], ' +
                                    'textarea'
                                );
                                
                                // Check if backend token placeholder has resolved (session ready)
                                const isSessionLoading = document.querySelector('.loading-token-count-placeholder') !== null;
                                if (promptArea && !isSessionLoading) break;

                                await delay(1000);
                                mountAttempts++;
                                if (mountAttempts % 5 === 0) {
                                    hostLog('MOUNT', 'Waiting for UI & Session Handshake... (attempt ' + mountAttempts + '/45, sessionLoading=' + isSessionLoading + ')');
                                }
                            }

                            if (!promptArea) {
                                const err = 'Failed to locate prompt textarea after 45s. DOM did not load in time.';
                                hostLog('MOUNT_ERR', err);
                                if (window.OmniAutomator) window.OmniAutomator.onError(err);
                                window.__omniAutomating = false;
                                return;
                            }

                            // Buffer 1.2s to ensure Google Cloud token handshake is 100% stable
                            await delay(1200);
                            hostLog('DOM', 'Found prompt textarea: ' + promptArea.tagName + '.' + promptArea.className);
                            updateStatus('Configuring Model and Thinking Settings...');

                            // 2. Configure Thinking Level if present
                            try {
                                const thinkingSelect = document.querySelector('ms-thinking-level-setting mat-select, mat-select[aria-label*="Thinking"]');
                                if (thinkingSelect && THINKING_LEVEL !== 'Default') {
                                    hostLog('SETTINGS', 'Selecting Thinking Level: ' + THINKING_LEVEL);
                                    thinkingSelect.click();
                                    await delay(500);
                                    const options = Array.from(document.querySelectorAll('mat-option'));
                                    const match = options.find(o => o.textContent.trim().toLowerCase().includes(THINKING_LEVEL.toLowerCase()));
                                    if (match) {
                                        match.click();
                                        hostLog('SETTINGS', 'Applied Thinking Level: ' + match.textContent.trim());
                                    } else {
                                        document.body.click();
                                    }
                                    await delay(300);
                                }
                            } catch(e) {
                                hostLog('SETTINGS_WARN', 'Thinking level setting failed: ' + e.message);
                            }

                            // 3. Configure System Instructions if provided
                            if (SYS_PROMPT && SYS_PROMPT.length > 0) {
                                try {
                                    updateStatus('Injecting System Instructions...');
                                    const sysCard = document.querySelector('ms-system-instructions-panel .system-instructions-card, ms-system-instructions-panel button');
                                    if (sysCard) {
                                        sysCard.click();
                                        await delay(600);
                                        const sysTa = document.querySelector('ms-system-instructions textarea, textarea[aria-label*="System"], textarea[placeholder*="System"]');
                                        if (sysTa) {
                                            sysTa.focus();
                                            const nativeProtoSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
                                            nativeProtoSetter.call(sysTa, SYS_PROMPT);
                                            sysTa.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
                                            sysTa.dispatchEvent(new Event('change', { bubbles: true }));
                                            hostLog('SYS_PROMPT', 'Injected ' + SYS_PROMPT.length + ' chars into System Instructions.');
                                            await delay(300);
                                        }
                                    }
                                } catch(e) {
                                    hostLog('SYS_PROMPT_ERR', 'System instructions injection failed: ' + e.message);
                                }
                            }

                            // Purge any lingering CDK backdrops
                            document.querySelectorAll('.cdk-overlay-backdrop').forEach(b => {
                                try { b.click(); } catch(_) {}
                            });
                            await delay(200);

                            // 4. Inject Prompt using native insertion & Spacebar transition
                            async function injectAndSubmitPrompt() {
                                updateStatus('Injecting prompt into Angular engine...');
                                promptArea.focus();
                                promptArea.select();

                                let insertedNatively = false;
                                try {
                                    insertedNatively = document.execCommand('insertText', false, PROMPT);
                                } catch(_) {}

                                if (!insertedNatively || promptArea.value !== PROMPT) {
                                    const nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
                                    nativeSetter.call(promptArea, PROMPT);
                                    promptArea.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, inputType: 'insertText', data: PROMPT }));
                                    promptArea.dispatchEvent(new Event('change', { bubbles: true }));
                                }

                                // Keypress cycle to transition Angular's internal DraftManager
                                promptArea.focus();
                                promptArea.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', code: 'Space', keyCode: 32, which: 32, bubbles: true, composed: true }));
                                promptArea.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, inputType: 'insertText', data: ' ' }));
                                promptArea.dispatchEvent(new KeyboardEvent('keyup', { key: ' ', code: 'Space', keyCode: 32, which: 32, bubbles: true, composed: true }));
                                await delay(200);

                                promptArea.dispatchEvent(new KeyboardEvent('keydown', { key: 'Backspace', code: 'Backspace', keyCode: 8, which: 8, bubbles: true, composed: true }));
                                promptArea.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, inputType: 'deleteContentBackward' }));
                                promptArea.dispatchEvent(new KeyboardEvent('keyup', { key: 'Backspace', code: 'Backspace', keyCode: 8, which: 8, bubbles: true, composed: true }));
                                promptArea.dispatchEvent(new Event('change', { bubbles: true }));

                                await delay(600);
                                promptArea.focus();

                                updateStatus('Submitting prompt to Gemini...');
                                const runBtn = document.querySelector(
                                    'ms-run-button button, ' +
                                    'button.ctrl-enter-submits, ' +
                                    'button[aria-label*="Run"], ' +
                                    'button[data-test-id="run-button"], ' +
                                    'button.run-button, ' +
                                    'button[type="submit"]'
                                );

                                if (runBtn) {
                                    hostLog('RUN', 'Located run button: disabled=' + runBtn.disabled + ', aria-disabled=' + runBtn.getAttribute('aria-disabled'));
                                }

                                // Dispatch synthetic Ctrl+Enter on prompt textarea
                                promptArea.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, ctrlKey: true, bubbles: true, composed: true }));
                                promptArea.dispatchEvent(new KeyboardEvent('keypress', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, ctrlKey: true, bubbles: true, composed: true }));
                                promptArea.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, ctrlKey: true, bubbles: true, composed: true }));

                                if (runBtn && !runBtn.disabled && runBtn.getAttribute('aria-disabled') !== 'true') {
                                    runBtn.click();
                                    hostLog('RUN', 'Clicked Run button.');
                                }
                            }

                            await injectAndSubmitPrompt();

                            // 5. Robust Content Scraper Loop with Deduplication & Auto-Healing Retries
                            updateStatus('Listening for response stream...');
                            let lastOutput = '';
                            let lastThoughts = '';
                            let stabilityTicks = 0;
                            let hasStartedReceiving = false;
                            let totalTicks = 0;
                            let retryCount = 0;

                            while (totalTicks < 180) {
                                await delay(1000);
                                totalTicks++;

                                // Auto-scroll virtual container so Angular renders all latest chunks
                                try {
                                    const scrollContainer = document.querySelector('ms-autoscroll-container, .chat-view-container, .chat-container');
                                    if (scrollContainer) {
                                        scrollContainer.scrollTop = scrollContainer.scrollHeight;
                                    }
                                } catch(_) {}

                                // A. Error Trapping with Auto-Healing Self-Retry
                                const errorNode = document.querySelector(
                                    'ms-callout.error-callout .message, ' +
                                    '.error-callout .message, ' +
                                    'ms-toast .message, ' +
                                    '.model-error, ' +
                                    '.toast .message, ' +
                                    'ms-error-panel'
                                );

                                if (errorNode) {
                                    const errTxt = (errorNode.innerText || errorNode.textContent || '').trim();
                                    if (errTxt.length > 0 && !errTxt.toLowerCase().includes('saved')) {
                                        if (retryCount < 2 && (errTxt.toLowerCase().includes('permission') || errTxt.toLowerCase().includes('failed to generate'))) {
                                            retryCount++;
                                            hostLog('RETRY', 'Encountered transient 403 (' + errTxt + '). Performing auto-healing retry #' + retryCount + '...');
                                            
                                            // Dismiss error toast
                                            try {
                                                const dismissBtn = document.querySelector('ms-callout .dismiss-button, button[aria-label="Dismiss"]');
                                                if (dismissBtn) dismissBtn.click();
                                            } catch(_) {}

                                            await delay(1500);
                                            await injectAndSubmitPrompt();
                                            continue;
                                        }

                                        hostLog('STUDIO_ERROR', 'Google AI Studio error: ' + errTxt);
                                        if (window.OmniAutomator) window.OmniAutomator.onError(errTxt);
                                        window.__omniAutomating = false;
                                        return;
                                    }
                                }

                                // B. Extract Model Thoughts (Clean leaf selector to avoid duplication)
                                let currentThoughts = '';
                                const thoughtChunks = Array.from(document.querySelectorAll('ms-thought-chunk ms-text-chunk, ms-thought-chunk .cmark-node'));
                                if (thoughtChunks.length > 0) {
                                    currentThoughts = thoughtChunks
                                        .map(n => n.innerText || n.textContent || '')
                                        .filter(Boolean)
                                        .join('\n')
                                        .trim();
                                } else {
                                    const rawThoughts = Array.from(document.querySelectorAll('ms-thought-chunk'));
                                    if (rawThoughts.length > 0) {
                                        currentThoughts = rawThoughts
                                            .map(n => n.innerText || n.textContent || '')
                                            .filter(Boolean)
                                            .join('\n')
                                            .trim();
                                    }
                                }

                                // C. Extract Rendered Markdown with strict deduplication (Priority: leaf ms-cmark-nodes only)
                                let currentOutput = '';
                                const modelTurns = document.querySelectorAll('ms-chat-turn .chat-turn-container.model, div[data-turn-role="Model"]');

                                if (modelTurns.length > 0) {
                                    const lastTurn = modelTurns[modelTurns.length - 1];
                                    
                                    // 1st Priority: Leaf ms-cmark-node elements (avoids parent container duplication!)
                                    const cmarkNodes = Array.from(lastTurn.querySelectorAll('ms-cmark-node'));
                                    if (cmarkNodes.length > 0) {
                                        currentOutput = cmarkNodes
                                            .map(n => n.innerText || n.textContent || '')
                                            .filter(Boolean)
                                            .join('\n\n')
                                            .trim();
                                    } else {
                                        // 2nd Priority: Prompt chunk text
                                        const textChunks = Array.from(lastTurn.querySelectorAll('ms-prompt-chunk > ms-text-chunk:not(ms-thought-chunk ms-text-chunk)'));
                                        if (textChunks.length > 0) {
                                            currentOutput = textChunks
                                                .map(n => n.innerText || n.textContent || '')
                                                .filter(Boolean)
                                                .join('\n\n')
                                                .trim();
                                        } else {
                                            // 3rd Priority: Top level turn container
                                            const turnRoot = lastTurn.querySelector('.turn-content');
                                            if (turnRoot) {
                                                currentOutput = (turnRoot.innerText || turnRoot.textContent || '').trim();
                                            }
                                        }
                                    }
                                }

                                const isSpinnerActive = document.querySelector('mat-spinner, ms-loading-indicator, .loading-dots, .streaming-active') !== null;
                                const isResponseReadyAnnounced = document.querySelector('#cdk-live-announcer-0')?.textContent?.includes('Response ready') === true;
                                const hasFeedbackButtons = document.querySelector('.response-feedback-button, .model-run-time-pill') !== null;

                                if (currentOutput.length > 0 || currentThoughts.length > 0) {
                                    hasStartedReceiving = true;
                                    if (window.OmniAutomator) window.OmniAutomator.onProgress(currentThoughts, currentOutput);
                                }

                                if (totalTicks % 3 === 0) {
                                    hostLog('STREAM_TICK', 'Tick ' + totalTicks + 's: modelTurns=' + modelTurns.length + ', outLen=' + currentOutput.length + ', spinner=' + isSpinnerActive + ', ready=' + isResponseReadyAnnounced);
                                }

                                if (hasStartedReceiving) {
                                    if (currentOutput === lastOutput && currentThoughts === lastThoughts && currentOutput.length > 0) {
                                        stabilityTicks++;
                                        // Complete when text is stable and either spinner is gone, response announced ready, or feedback buttons mounted
                                        if (stabilityTicks >= 2 && (!isSpinnerActive || isResponseReadyAnnounced || hasFeedbackButtons)) {
                                            updateStatus('Output generation complete!');
                                            hostLog('DONE', 'Final output captured: ' + currentOutput.length + ' characters.');
                                            if (window.OmniAutomator) window.OmniAutomator.onComplete(currentThoughts, currentOutput);
                                            window.__omniAutomating = false;
                                            return;
                                        }
                                    } else {
                                        stabilityTicks = 0;
                                        lastOutput = currentOutput;
                                        lastThoughts = currentThoughts;
                                    }
                                }
                            }

                            if (lastOutput.length > 0) {
                                if (window.OmniAutomator) window.OmniAutomator.onComplete(lastThoughts, lastOutput);
                            } else {
                                const timeoutMsg = 'Operation timed out after 180s. Tap "Dump Live DOM" below to inspect page state.';
                                hostLog('TIMEOUT', timeoutMsg);
                                if (window.OmniAutomator) window.OmniAutomator.onError(timeoutMsg);
                            }
                            window.__omniAutomating = false;

                        } catch (fatalErr) {
                            hostLog('FATAL_ERR', (fatalErr.stack || fatalErr.message || String(fatalErr)));
                            if (window.OmniAutomator) window.OmniAutomator.onError('Script error: ' + fatalErr.message);
                            window.__omniAutomating = false;
                        }
                    })();
                """.trimIndent()

                val autoWv = WebView(context).apply {
                    translationX = -20000f
                    alpha = 0.01f

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        allowFileAccess = true
                        setSupportMultipleWindows(true)
                        userAgentString = desktopUA
                    }

                    if (autoSelectedProfileId != "default" && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                        try {
                            val profileStore = ProfileStore.getInstance()
                            profileStore.getOrCreateProfile(autoSelectedProfileId)
                            WebViewCompat.setProfile(this, autoSelectedProfileId)
                        } catch (_: Exception) {}
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            val msg = consoleMessage?.message() ?: ""
                            val lvl = consoleMessage?.messageLevel()?.name ?: "LOG"
                            val line = consoleMessage?.lineNumber() ?: 0
                            val src = consoleMessage?.sourceId() ?: ""
                            val tag = when (consoleMessage?.messageLevel()) {
                                ConsoleMessage.MessageLevel.ERROR -> "AI_STUDIO_ERR"
                                ConsoleMessage.MessageLevel.WARNING -> "AI_STUDIO_WARN"
                                else -> "AI_STUDIO_CONSOLE"
                            }
                            bridge.log(tag, "[$lvl] $msg (line $line in $src)")
                            return true
                        }

                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            if (newProgress % 25 == 0 || newProgress == 100) {
                                bridge.log("AI_STUDIO_LOAD", "Headless load: $newProgress%")
                            }
                        }
                    }

                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onStatus(msg: String) {
                            mainHandler.post { automationStatus = msg }
                        }

                        @JavascriptInterface
                        fun onLog(tag: String, msg: String) {
                            bridge.log("AI_STUDIO_$tag", msg)
                        }

                        @JavascriptInterface
                        fun onProgress(thoughts: String, output: String) {
                            mainHandler.post {
                                automationThoughts = thoughts
                                automationResult = output
                                automationStatus = "Streaming response..."
                            }
                        }

                        @JavascriptInterface
                        fun onComplete(thoughts: String, output: String) {
                            mainHandler.post {
                                automationThoughts = thoughts
                                automationResult = output
                                automationStatus = "Completed"
                                isAutomating = false
                            }
                        }

                        @JavascriptInterface
                        fun onError(err: String) {
                            mainHandler.post {
                                automationError = err
                                automationStatus = "Failed"
                                isAutomating = false
                            }
                        }
                    }, "OmniAutomator")

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            bridge.log("AI_STUDIO_NAV", "Started loading: $url")
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            bridge.log("AI_STUDIO_NAV", "Finished loading: $url")
                            if (url != null && url.contains("aistudio.google.com")) {
                                bridge.log("AI_STUDIO_INJECT", "Injecting automation script into $url")
                                view?.evaluateJavascript(automationScript, null)
                            }
                        }

                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            super.onReceivedError(view, request, error)
                            bridge.log("AI_STUDIO_ERR", "WebView Error: ${error?.description} on URL: ${request?.url}")
                            if (request?.isForMainFrame == true) {
                                mainHandler.post {
                                    automationError = "Network error connecting to AI Studio (${error?.description})"
                                    isAutomating = false
                                }
                            }
                        }
                    }
                }

                headlessAutomationWv = autoWv
                
                // Attach off-screen with MATCH_PARENT so Angular Virtual Scroll measures the full screen
                mainHandler.post {
                    containerLayout?.addView(autoWv, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                    autoWv.onResume()
                    autoWv.loadUrl("https://aistudio.google.com/prompts/new_chat")
                }
            }

            // Timer ticker during automation
            LaunchedEffect(isAutomating) {
                while (isAutomating) {
                    delay(1000)
                    automationElapsedSec++
                }
            }

            // --- Dialog 1: AI Studio Automation Order Sheet ---
            if (showAutomationDialog) {
                AlertDialog(
                    onDismissRequest = { showAutomationDialog = false },
                    containerColor = Color(0xFF282C34),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🤖", fontSize = 22.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("AI Studio Automator", color = Color(0xFFE8EAED), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    },
                    text = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)
                        ) {
                            Text("Configure and dispatch prompts directly to Google AI Studio headlessly using your active profile cookies.", color = Color(0xFF9AA0A6), fontSize = 11.sp)

                            // Profile Picker
                            Column {
                                Text("Target Profile (Account)", color = Color(0xFF8AB4F8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    profiles.forEach { prof ->
                                        val isSel = prof.id == autoSelectedProfileId
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSel) Color(prof.colorValue).copy(alpha = 0.25f) else Color(0xFF1F2227),
                                            border = BorderStroke(1.dp, if (isSel) Color(prof.colorValue) else Color(0xFF3C4043)),
                                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { autoSelectedProfileId = prof.id }
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                            ) {
                                                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(prof.colorValue)))
                                                Spacer(Modifier.width(6.dp))
                                                Text(prof.name, color = Color(0xFFE8EAED), fontSize = 11.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                                            }
                                        }
                                    }
                                }
                            }

                            // Thinking Level Picker
                            Column {
                                Text("Thinking Level", color = Color(0xFF8AB4F8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("High", "Low", "Default").forEach { level ->
                                        val isSel = autoThinkingLevel == level
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSel) Color(0xFF8AB4F8).copy(alpha = 0.25f) else Color(0xFF1F2227),
                                            border = BorderStroke(1.dp, if (isSel) Color(0xFF8AB4F8) else Color(0xFF3C4043)),
                                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { autoThinkingLevel = level }
                                        ) {
                                            Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                                Text(level, color = if (isSel) Color(0xFF8AB4F8) else Color(0xFF9AA0A6), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }

                            // System Prompt (Optional)
                            OutlinedTextField(
                                value = autoSystemPrompt,
                                onValueChange = { autoSystemPrompt = it },
                                label = { Text("System Instructions (Optional)") },
                                placeholder = { Text("You are a specialized assistant...", color = Color(0xFF5F6368), fontSize = 12.sp) },
                                maxLines = 4,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color(0xFFE8EAED),
                                    unfocusedTextColor = Color(0xFFE8EAED),
                                    focusedBorderColor = Color(0xFF8AB4F8),
                                    unfocusedBorderColor = Color(0xFF3C4043)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            // User Prompt
                            OutlinedTextField(
                                value = autoUserPrompt,
                                onValueChange = { autoUserPrompt = it },
                                label = { Text("User Prompt") },
                                placeholder = { Text("Enter prompt to run headlessly...", color = Color(0xFF5F6368), fontSize = 12.sp) },
                                minLines = 3,
                                maxLines = 6,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color(0xFFE8EAED),
                                    unfocusedTextColor = Color(0xFFE8EAED),
                                    focusedBorderColor = Color(0xFF8AB4F8),
                                    unfocusedBorderColor = Color(0xFF3C4043)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { startAutomationRun() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8AB4F8)),
                            enabled = autoUserPrompt.trim().isNotEmpty()
                        ) {
                            Text("⚡ Run Automation", color = Color(0xFF1F2227), fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAutomationDialog = false }) {
                            Text("Cancel", color = Color(0xFF9AA0A6))
                        }
                    }
                )
            }

            // --- Dialog 2: Live Execution & Scraped Result Terminal ---
            if (showAutomationResultDialog) {
                var thoughtsExpanded by remember { mutableStateOf(false) }

                AlertDialog(
                    onDismissRequest = {
                        if (!isAutomating) showAutomationResultDialog = false
                    },
                    containerColor = Color(0xFF282C34),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    title = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isAutomating) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color(0xFF8AB4F8), strokeWidth = 2.dp)
                                } else if (automationError != null) {
                                    Text("❌", fontSize = 18.sp)
                                } else {
                                    Text("✅", fontSize = 18.sp)
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (isAutomating) "Executing Prompt..." else if (automationError != null) "Execution Error" else "AI Studio Response",
                                    color = Color(0xFFE8EAED),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF1F2227)) {
                                Text("${automationElapsedSec}s", color = Color(0xFF8AB4F8), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    },
                    text = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
                        ) {
                            // Status Banner
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (automationError != null) Color(0xFF4C1C1C) else Color(0xFF1F2227),
                                border = BorderStroke(1.dp, if (automationError != null) Color(0xFFF28B82) else Color(0xFF3C4043)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = automationError ?: "Status: $automationStatus",
                                    color = if (automationError != null) Color(0xFFF28B82) else Color(0xFF8AB4F8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }

                            // Expandable Thoughts Accordion
                            if (automationThoughts.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF1F2227),
                                    border = BorderStroke(1.dp, Color(0xFF3C4043)),
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { thoughtsExpanded = !thoughtsExpanded }
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("🧠", fontSize = 14.sp)
                                                Spacer(Modifier.width(6.dp))
                                                Text("Model Reasoning / Thoughts", color = Color(0xFFE8EAED), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                            Icon(
                                                if (thoughtsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                contentDescription = null,
                                                tint = Color(0xFF9AA0A6),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        if (thoughtsExpanded) {
                                            Spacer(Modifier.height(6.dp))
                                            androidx.compose.foundation.text.selection.SelectionContainer {
                                                Text(
                                                    text = automationThoughts,
                                                    color = Color(0xFF9AA0A6),
                                                    fontSize = 11.sp,
                                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                    lineHeight = 15.sp,
                                                    modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Final Scraped Output Terminal
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .background(Color(0xFF16181D), RoundedCornerShape(8.dp))
                                    .border(1.dp, Color(0xFF3C4043), RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            ) {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    item {
                                        androidx.compose.foundation.text.selection.SelectionContainer {
                                            Text(
                                                text = if (automationResult.isNotEmpty()) automationResult else if (isAutomating) "Listening for response stream from AI Studio..." else "No output generated.",
                                                color = Color(0xFFE8EAED),
                                                fontSize = 12.sp,
                                                lineHeight = 17.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Live Diagnostic DOM Dump Button (Direct MediaStore & External Downloads Hook)
                            OutlinedButton(
                                onClick = {
                                    bridge.showToast("Dumping live DOM to Downloads/OmniSnapshots...")
                                    val script = """
                                        (function() {
                                            try {
                                                var clone = document.documentElement.cloneNode(true);
                                                var head = clone.querySelector('head');
                                                if (head) {
                                                    var existingBase = head.querySelector('base');
                                                    if (!existingBase) {
                                                        var base = document.createElement('base');
                                                        base.href = window.location.href;
                                                        head.insertBefore(base, head.firstChild);
                                                    }
                                                }
                                                return '<!DOCTYPE html>\n' + clone.outerHTML;
                                            } catch(e) {
                                                return document.documentElement.outerHTML;
                                            }
                                        })();
                                    """.trimIndent()
                                    
                                    headlessAutomationWv?.evaluateJavascript(script) { html ->
                                        if (!html.isNullOrEmpty() && html != "null") {
                                            val rawHtml = if (html.startsWith("\"") && html.endsWith("\"")) {
                                                try {
                                                    org.json.JSONObject("{\"h\":$html}").getString("h")
                                                } catch (_: Exception) { html }
                                            } else html
                                            saveHtmlSnapshot(rawHtml, "Automator_DOM")
                                        } else {
                                            bridge.showToast("Could not capture page DOM.")
                                        }
                                    }
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF8AB4F8))
                            ) {
                                Text("🔍 Dump DOM", fontSize = 11.sp)
                            }

                            if (automationResult.isNotEmpty()) {
                                Button(
                                    onClick = {
                                        bridge.copyToClipboard(automationResult)
                                        bridge.showToast("Copied result to clipboard!")
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636))
                                ) {
                                    Text("📋 Copy", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Button(
                                onClick = {
                                    showAutomationResultDialog = false
                                    try {
                                        headlessAutomationWv?.stopLoading()
                                        headlessAutomationWv?.onPause()
                                        containerLayout?.removeView(headlessAutomationWv)
                                        headlessAutomationWv?.destroy()
                                        headlessAutomationWv = null
                                    } catch (_: Exception) {}
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3C4043))
                            ) {
                                Text(if (isAutomating) "Stop" else "Close", color = Color.White)
                            }
                        }
                    }
                )
            }

            // --- Downloads Manager Dialog ---
            if (showDownloadsDialog) {
                fun formatBytes(bytes: Long): String {
                    if (bytes <= 0) return "0 B"
                    val kb = bytes / 1024.0
                    val mb = kb / 1024.0
                    val gb = mb / 1024.0
                    return when {
                        gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
                        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
                        kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
                        else -> "$bytes B"
                    }
                }

                fun getFileEmoji(filename: String): String {
                    val ext = filename.substringAfterLast('.', "").lowercase()
                    return when (ext) {
                        "zip", "tar", "gz", "rar", "7z" -> "📦"
                        "html", "htm", "js", "ts", "json", "kt", "java", "py", "css" -> "💻"
                        "png", "jpg", "jpeg", "webp", "gif", "svg" -> "🖼️"
                        "mp4", "mkv", "webm", "mov" -> "🎬"
                        "mp3", "wav", "m4a", "flac" -> "🎵"
                        "pdf", "doc", "docx", "txt", "md" -> "📄"
                        "apk" -> "🤖"
                        else -> "📁"
                    }
                }

                fun openDownloadedFile(file: File) {
                    try {
                        val ext = file.extension.lowercase()
                        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse("file://${file.absolutePath}"), mime)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Open with...").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (e: Exception) {
                        bridge.showToast("Could not open file: ${e.message}")
                    }
                }

                fun shareDownloadedFile(file: File) {
                    try {
                        val ext = file.extension.lowercase()
                        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = mime
                            putExtra(Intent.EXTRA_STREAM, Uri.parse("file://${file.absolutePath}"))
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share ${file.name}").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (e: Exception) {
                        bridge.showToast("Share failed: ${e.message}")
                    }
                }

                AlertDialog(
                    onDismissRequest = { showDownloadsDialog = false },
                    containerColor = Color(0xFF282C34),
                    title = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("📥", fontSize = 20.sp)
                                Spacer(Modifier.width(8.dp))
                                Text("Downloads", color = Color(0xFFE8EAED), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            }
                            if (completedFilesList.isNotEmpty()) {
                                Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF1F2227)) {
                                    Text(
                                        "${completedFilesList.size} files",
                                        color = Color(0xFF8AB4F8),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 440.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Section: Active Downloads
                            if (activeDownloadsList.isNotEmpty()) {
                                Text("ACTIVE DOWNLOADS", color = Color(0xFF81C995), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                activeDownloadsList.forEach { active ->
                                    Card(
                                        shape = RoundedCornerShape(10.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2227)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    active.filename,
                                                    color = Color.White,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                IconButton(
                                                    onClick = {
                                                        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                                                        dm?.remove(active.downloadId)
                                                        trackedDownloadIds.remove(active.downloadId)
                                                    },
                                                    modifier = Modifier.size(22.dp)
                                                ) {
                                                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color(0xFFF28B82), modifier = Modifier.size(16.dp))
                                                }
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            LinearProgressIndicator(
                                                progress = { active.progress },
                                                color = Color(0xFF81C995),
                                                trackColor = Color(0xFF3C4043),
                                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    "${formatBytes(active.bytesDownloaded)} / ${if (active.totalBytes > 0) formatBytes(active.totalBytes) else "--"}",
                                                    color = Color(0xFF9AA0A6),
                                                    fontSize = 10.sp
                                                )
                                                Text(
                                                    "${(active.progress * 100).toInt()}%",
                                                    color = Color(0xFF81C995),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider(color = Color(0xFF3C4043))
                            }

                            // Section: Completed Files Vault
                            Text("SAVED FILES (OmniDownloads)", color = Color(0xFF8AB4F8), fontWeight = FontWeight.Bold, fontSize = 11.sp)

                            if (completedFilesList.isEmpty() && activeDownloadsList.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(140.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("📂", fontSize = 28.sp)
                                        Spacer(Modifier.height(6.dp))
                                        Text("No downloads yet", color = Color(0xFF9AA0A6), fontSize = 13.sp)
                                    }
                                }
                            } else {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    items(
                                        count = completedFilesList.size,
                                        key = { idx -> completedFilesList[idx].absolutePath }
                                    ) { idx ->
                                        val file = completedFilesList[idx]
                                        Card(
                                            shape = RoundedCornerShape(10.dp),
                                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2227)),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable { openDownloadedFile(file) }
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(getFileEmoji(file.name), fontSize = 20.sp)
                                                Spacer(Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        file.name,
                                                        color = Color(0xFFE8EAED),
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Spacer(Modifier.height(2.dp))
                                                    Text(
                                                        "${formatBytes(file.length())} • ${SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(file.lastModified()))}",
                                                        color = Color(0xFF9AA0A6),
                                                        fontSize = 10.sp
                                                    )
                                                }

                                                IconButton(
                                                    onClick = { shareDownloadedFile(file) },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(Icons.Default.Share, contentDescription = "Share", tint = Color(0xFF8AB4F8), modifier = Modifier.size(16.dp))
                                                }

                                                IconButton(
                                                    onClick = {
                                                        try {
                                                            file.delete()
                                                            refreshCompletedDownloads()
                                                            bridge.showToast("Deleted ${file.name}")
                                                        } catch (e: Exception) {
                                                            bridge.showToast("Delete failed: ${e.message}")
                                                        }
                                                    },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFF28B82), modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { showDownloadsDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8AB4F8))
                        ) {
                            Text("Close", color = Color(0xFF1F2227), fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }

            // --- Browser Settings & Backup Dialog ---
            if (showSettingsDialog) {
                var tempKey by remember { mutableStateOf(solverApiKey) }
                var tempAuto by remember { mutableStateOf(autoSolveEnabled) }

                AlertDialog(
                    onDismissRequest = { showSettingsDialog = false },
                    containerColor = Color(0xFF282C34),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("⚙️", fontSize = 20.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("Settings & Backup", color = Color(0xFFE8EAED), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("Session & Profile Backup Vault", color = Color(0xFF8AB4F8), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Backs up all 10 profiles, tabs, custom shortcuts, solver keys, and local IDE vaults. Auto-mirrored to Documents/.omni_vault/.", color = Color(0xFF9AA0A6), fontSize = 11.sp)

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        exportFullBackup()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("📦 Export", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = {
                                        backupPickerLauncher.launch("application/zip")
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF8AB4F8)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("📥 Restore", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            HorizontalDivider(color = Color(0xFF3C4043))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("NoCaptchaAI Solver", color = Color(0xFF8AB4F8), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                TextButton(onClick = { scanAndSolveCaptcha() }) {
                                    Text("Solve Now", color = Color(0xFF81C995), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            OutlinedTextField(
                                value = tempKey,
                                onValueChange = { tempKey = it },
                                label = { Text("NoCaptchaAI API Key") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color(0xFFE8EAED),
                                    unfocusedTextColor = Color(0xFFE8EAED),
                                    focusedBorderColor = Color(0xFF8AB4F8),
                                    unfocusedBorderColor = Color(0xFF5F6368)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Auto-Solve on Page Load", color = Color(0xFFE8EAED), fontSize = 12.sp)
                                Switch(
                                    checked = tempAuto,
                                    onCheckedChange = { tempAuto = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF8AB4F8))
                                )
                            }

                            HorizontalDivider(color = Color(0xFF3C4043))

                            TextButton(
                                onClick = {
                                    CookieManager.getInstance().removeAllCookies(null)
                                    webViewInstance?.clearCache(true)
                                    bridge.showToast("Cookies and Cache cleared.")
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Clear Cookies & Cache", color = Color(0xFFF28B82), fontSize = 12.sp)
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                solverApiKey = tempKey.trim()
                                autoSolveEnabled = tempAuto
                                val cfg = org.json.JSONObject().apply {
                                    put("apiKey", solverApiKey)
                                    put("autoSolve", autoSolveEnabled)
                                }
                                bridge.saveFile("config/solver.json", cfg.toString().toByteArray(Charsets.UTF_8))
                                autoMirrorVaultToDocuments()
                                bridge.showToast("Settings saved!")
                                showSettingsDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8AB4F8))
                        ) {
                            Text("Save", color = Color(0xFF1F2227), fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSettingsDialog = false }) {
                            Text("Close", color = Color(0xFF9AA0A6))
                        }
                    }
                )
            }

            // --- Edit Shortcut Dialog ---
            if (editingShortcut != null) {
                val targetItem = editingShortcut!!
                var editName by remember(targetItem) { mutableStateOf(targetItem.title) }
                var editUrl by remember(targetItem) { mutableStateOf(targetItem.localSourcePath ?: targetItem.url) }
                val previewDomain = remember(editUrl) { extractDomain(editUrl) }

                LaunchedEffect(previewDomain) {
                    fetchFavicon(previewDomain)
                }
                val previewBmp = faviconCache[previewDomain]

                AlertDialog(
                    onDismissRequest = { editingShortcut = null },
                    containerColor = Color(0xFF282C34),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (previewBmp != null) {
                                Image(
                                    bitmap = previewBmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp).clip(CircleShape)
                                )
                            } else {
                                Surface(shape = CircleShape, color = Color(targetItem.colorValue), modifier = Modifier.size(24.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(editName.take(1).uppercase(), fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text("Edit Shortcut", color = Color(0xFFE8EAED), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = editName,
                                onValueChange = { editName = it },
                                label = { Text("Name") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color(0xFFE8EAED),
                                    unfocusedTextColor = Color(0xFFE8EAED),
                                    focusedBorderColor = Color(0xFF8AB4F8),
                                    unfocusedBorderColor = Color(0xFF5F6368)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = editUrl,
                                onValueChange = { editUrl = it },
                                label = { Text("URL or Local File Path") },
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = {
                                        bridge.pickFiles("*/*", false) { uris ->
                                            val picked = uris.firstOrNull()
                                            if (picked != null) {
                                                val pathStr = picked.path
                                                val directPath = if (pathStr != null && (pathStr.contains("/storage/") || pathStr.contains("/sdcard/"))) {
                                                    pathStr.substring(pathStr.indexOf("/storage/").coerceAtLeast(pathStr.indexOf("/sdcard/")))
                                                } else {
                                                    picked.toString()
                                                }
                                                editUrl = directPath
                                            }
                                        }
                                    }) {
                                        Icon(Icons.Default.Add, contentDescription = "Browse File", tint = Color(0xFF8AB4F8))
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color(0xFFE8EAED),
                                    unfocusedTextColor = Color(0xFFE8EAED),
                                    focusedBorderColor = Color(0xFF8AB4F8),
                                    unfocusedBorderColor = Color(0xFF5F6368)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    val updated = shortcuts.filter { it.id != targetItem.id }
                                    shortcuts = updated
                                    saveShortcutsToDisk(updated)
                                    try {
                                        File(bridge.getPluginDir(), "ide/vault_${targetItem.id}").deleteRecursively()
                                    } catch (_: Exception) {}
                                    editingShortcut = null
                                    bridge.showToast("Shortcut deleted")
                                }
                            ) {
                                Text("Delete", color = Color(0xFFF28B82), fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    val trimmedUrl = editUrl.trim()
                                    val isLocal = isLocalFilePath(trimmedUrl)

                                    val (finalUrl, srcPath) = if (isLocal) {
                                        val isolatedSubPath = "ide/vault_${targetItem.id}/index.html"
                                        val (success, vaultedPath) = syncLocalFileToVault(trimmedUrl, isolatedSubPath)
                                        if (success) {
                                            bridge.showToast("✅ Synced to private vault slot!")
                                            Pair(vaultedPath, normalizeLocalFilePath(trimmedUrl))
                                        } else {
                                            bridge.showToast("⚠️ $vaultedPath (See Diagnostics)")
                                            val cleanNorm = normalizeLocalFilePath(trimmedUrl)
                                            Pair("file://$cleanNorm", cleanNorm)
                                        }
                                    } else {
                                        val webUrl = if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) "https://$trimmedUrl" else trimmedUrl
                                        Pair(webUrl, null)
                                    }

                                    val updated = shortcuts.map {
                                        if (it.id == targetItem.id) it.copy(
                                            title = editName.trim().ifEmpty { targetItem.title },
                                            url = finalUrl,
                                            localSourcePath = srcPath
                                        ) else it
                                    }
                                    shortcuts = updated
                                    saveShortcutsToDisk(updated)
                                    fetchFavicon(extractDomain(finalUrl))
                                    editingShortcut = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8AB4F8))
                            ) {
                                Text("Save", color = Color(0xFF1F2227), fontWeight = FontWeight.Bold)
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { editingShortcut = null }) {
                            Text("Cancel", color = Color(0xFF9AA0A6))
                        }
                    }
                )
            }

            // --- Add Shortcut Dialog ---
            if (isAddingShortcut) {
                var newName by remember { mutableStateOf("") }
                var newUrl by remember { mutableStateOf("https://") }
                val previewDomain = remember(newUrl) { extractDomain(newUrl) }

                LaunchedEffect(previewDomain) {
                    if (previewDomain.isNotEmpty() && previewDomain != "https://") {
                        fetchFavicon(previewDomain)
                    }
                }
                val previewBmp = faviconCache[previewDomain]

                AlertDialog(
                    onDismissRequest = { isAddingShortcut = false },
                    containerColor = Color(0xFF282C34),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (previewBmp != null) {
                                Image(
                                    bitmap = previewBmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp).clip(CircleShape)
                                )
                            } else {
                                Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF8AB4F8))
                            }
                            Spacer(Modifier.width(10.dp))
                            Text("Add Shortcut", color = Color(0xFFE8EAED), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = newName,
                                onValueChange = { newName = it },
                                label = { Text("Name (e.g. GitHub or Local IDE)") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color(0xFFE8EAED),
                                    unfocusedTextColor = Color(0xFFE8EAED),
                                    focusedBorderColor = Color(0xFF8AB4F8),
                                    unfocusedBorderColor = Color(0xFF5F6368)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = newUrl,
                                onValueChange = { newUrl = it },
                                label = { Text("URL or File Path") },
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = {
                                        bridge.pickFiles("*/*", false) { uris ->
                                            val picked = uris.firstOrNull()
                                            if (picked != null) {
                                                val pathStr = picked.path
                                                val directPath = if (pathStr != null && (pathStr.contains("/storage/") || pathStr.contains("/sdcard/"))) {
                                                    pathStr.substring(pathStr.indexOf("/storage/").coerceAtLeast(pathStr.indexOf("/sdcard/")))
                                                } else {
                                                    picked.toString()
                                                }
                                                newUrl = directPath
                                                if (newName.isEmpty()) {
                                                    newName = directPath.substringAfterLast("/").substringBeforeLast(".")
                                                }
                                            }
                                        }
                                    }) {
                                        Icon(Icons.Default.Add, contentDescription = "Browse File", tint = Color(0xFF8AB4F8))
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color(0xFFE8EAED),
                                    unfocusedTextColor = Color(0xFFE8EAED),
                                    focusedBorderColor = Color(0xFF8AB4F8),
                                    unfocusedBorderColor = Color(0xFF5F6368)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val trimmedUrl = newUrl.trim()
                                val isLocal = isLocalFilePath(trimmedUrl)
                                val newShortcutId = "sc_${System.currentTimeMillis()}"
                                val (finalUrl, srcPath) = if (isLocal) {
                                    val isolatedSubPath = "ide/vault_$newShortcutId/index.html"
                                    val (success, vaultedPath) = syncLocalFileToVault(trimmedUrl, isolatedSubPath)
                                    if (success) {
                                        bridge.showToast("✅ Isolated vault created for local app")
                                        Pair(vaultedPath, normalizeLocalFilePath(trimmedUrl))
                                    } else {
                                        bridge.showToast("⚠️ $vaultedPath (See Diagnostics)")
                                        val cleanNorm = normalizeLocalFilePath(trimmedUrl)
                                        Pair("file://$cleanNorm", cleanNorm)
                                    }
                                } else {
                                    val webUrl = if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) "https://$trimmedUrl" else trimmedUrl
                                    Pair(webUrl, null)
                                }
                                val title = newName.trim().ifEmpty { if (isLocal) "Local App" else extractDomain(finalUrl) }
                                val newItem = ShortcutItem(
                                    id = newShortcutId,
                                    title = title,
                                    url = finalUrl,
                                    iconText = if (isLocal) "💻" else title.take(1).uppercase(),
                                    colorValue = 0xFF58A6FF,
                                    localSourcePath = srcPath
                                )
                                val updated = shortcuts + newItem
                                shortcuts = updated
                                saveShortcutsToDisk(updated)
                                fetchFavicon(extractDomain(finalUrl))
                                isAddingShortcut = false
                            },
                            enabled = newUrl.length >= 3,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8AB4F8))
                        ) {
                            Text("Add", color = Color(0xFF1F2227), fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { isAddingShortcut = false }) {
                            Text("Cancel", color = Color(0xFF9AA0A6))
                        }
                    }
                )
            }

            // --- Rename Profile Dialog ---
            if (editingProfile != null) {
                val targetProf = editingProfile!!
                var renameText by remember(targetProf) { mutableStateOf(targetProf.name) }

                AlertDialog(
                    onDismissRequest = { editingProfile = null },
                    containerColor = Color(0xFF282C34),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color(targetProf.colorValue))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Rename Profile", color = Color(0xFFE8EAED), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    },
                    text = {
                        OutlinedTextField(
                            value = renameText,
                            onValueChange = { renameText = it },
                            label = { Text("Profile Display Name") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color(0xFFE8EAED),
                                unfocusedTextColor = Color(0xFFE8EAED),
                                focusedBorderColor = Color(targetProf.colorValue),
                                unfocusedBorderColor = Color(0xFF5F6368)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val newName = renameText.trim().ifEmpty { targetProf.name }
                                val updated = profiles.map { if (it.id == targetProf.id) it.copy(name = newName) else it }
                                profiles = updated
                                saveProfilesToDisk(updated)
                                editingProfile = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(targetProf.colorValue))
                        ) {
                            Text("Save", color = Color(0xFF1F2227), fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { editingProfile = null }) {
                            Text("Cancel", color = Color(0xFF9AA0A6))
                        }
                    }
                )
            }

            // --- In-Layout Browser Menu Overlay (Single Unified Scrim & Surface) ---
            if (showMenu) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(20f)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { showMenu = false })
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
                                detectTapGestures(onTap = { /* Consume taps inside menu so they do not dismiss */ })
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
                                        showMenu = false
                                        if (canGoBack) webViewInstance?.goBack()
                                        else if (currentUrl != "about:blank") navigateTo("about:blank")
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
                                        showMenu = false
                                        webViewInstance?.goForward()
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
                                        showMenu = false
                                        if (webViewInstance != null && currentUrl != "about:blank") {
                                            val wvUrl = webViewInstance?.url
                                            if (wvUrl == null || wvUrl == "about:blank" || wvUrl.isEmpty()) {
                                                webViewInstance?.loadUrl(currentUrl)
                                            } else {
                                                webViewInstance?.reload()
                                            }
                                        }
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
                                            showMenu = false
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

                            InLayoutMenuItem("🤖 AI Studio Automator", color = Color(0xFF8AB4F8), isBold = true) {
                                showAutomationDialog = true
                            }

                            InLayoutMenuItem("💻 Open Local IDE", color = Color(0xFF58A6FF), isBold = true) {
                                val localIdeShortcuts = shortcuts.filter { it.localSourcePath != null || it.url.contains("/ide/") || it.title.contains("IDE", ignoreCase = true) }
                                val target = localIdeShortcuts.firstOrNull()
                                if (target != null) {
                                    val src = target.localSourcePath ?: "/storage/emulated/0/Download/F/index.html"
                                    val isolatedPath = "ide/vault_${target.id}/index.html"
                                    val vFile = File(bridge.getPluginDir(), isolatedPath)
                                    val targetUrl = if (vFile.exists() && vFile.length() > 0) {
                                        "file://${vFile.absolutePath}"
                                    } else {
                                        val (success, resPath) = syncLocalFileToVault(src, isolatedPath)
                                        if (success) {
                                            bridge.showToast("✅ Synced ${target.title} from disk!")
                                            resPath
                                        } else {
                                            target.url
                                        }
                                    }
                                    navigateTo(targetUrl)
                                } else {
                                    val defaultFile = File(bridge.getPluginDir(), "ide/index.html")
                                    val targetUrl = if (defaultFile.exists() && defaultFile.length() > 0) {
                                        "file://${defaultFile.absolutePath}"
                                    } else {
                                        val (success, resPath) = syncLocalFileToVault("/storage/emulated/0/Download/F/index.html", "ide/index.html")
                                        if (success) resPath else ideInternalPath
                                    }
                                    navigateTo(targetUrl)
                                }
                            }

                            InLayoutMenuItem(
                                title = if (trackedDownloadIds.isNotEmpty()) "📥 Downloads (${trackedDownloadIds.size})" else "📥 Downloads",
                                color = if (trackedDownloadIds.isNotEmpty()) Color(0xFF81C995) else Color(0xFF8AB4F8),
                                isBold = true
                            ) {
                                refreshCompletedDownloads()
                                showDownloadsDialog = true
                            }

                            InLayoutMenuItem("+ New Tab", color = Color(0xFF8AB4F8), isBold = true) {
                                createNewTab()
                            }

                            InLayoutMenuItem("Close Tab") {
                                closeTab(activeTabId)
                            }

                            InLayoutMenuItem(if (isDesktopMode) "✓ Desktop Site" else "Desktop Site") {
                                isDesktopMode = !isDesktopMode
                                webViewInstance?.settings?.userAgentString = if (isDesktopMode) desktopUA else mobileUA
                                webViewInstance?.reload()
                                bridge.showToast(if (isDesktopMode) "Desktop Mode Enabled" else "Mobile Mode Enabled")
                            }

                            InLayoutMenuItem("Copy Clean URL") {
                                if (currentUrl != "about:blank") {
                                    val cleanUrl = sanitizeUrlForCopy(currentUrl)
                                    bridge.copyToClipboard(cleanUrl)
                                }
                            }

                            InLayoutMenuItem("🛠️ Eruda DevTools (Console)", color = Color(0xFF8AB4F8), isBold = true) {
                                injectErudaDevTools()
                            }

                            InLayoutMenuItem("Capture DOM Snapshot", color = Color(0xFF8AB4F8)) {
                                captureDomSnapshot()
                            }

                            InLayoutMenuItem("⚙️ Settings & Backup", color = Color(0xFF8AB4F8), isBold = true) {
                                showSettingsDialog = true
                            }

                            HorizontalDivider(color = Color(0xFF3C4043), modifier = Modifier.padding(vertical = 4.dp))

                            InLayoutMenuItem("Exit Omni Chrome", color = Color(0xFFF28B82), isBold = true) {
                                showMenu = false
                                showSettingsDialog = false
                                editingShortcut = null
                                isAddingShortcut = false
                                editingProfile = null
                                coroutineScope.launch {
                                    // 400ms guarantees the Compose render pass and hardware buffer swap 
                                    // are fully completed before the task manager takes the bitmap snapshot.
                                    delay(400) 
                                    bridge.close()
                                }
                            }
                        }
                    }
                }
            }

            if (isTabSwitcherOpen) {
                TabSwitcherScreen(
                    tabs = tabs,
                    activeTabId = activeTabId,
                    profiles = profiles,
                    selectedProfileId = selectedProfileId,
                    onSelectProfile = {
                        selectedProfileId = it
                        saveProfilesToDisk(profiles)
                    },
                    onAddProfile = {
                        val nextIdx = profiles.size
                        val newId = "profile_${System.currentTimeMillis()}"
                        val newColor = PROFILE_PALETTE[nextIdx % PROFILE_PALETTE.size]
                        val newProf = BrowserProfile(newId, "Account ${nextIdx + 1}", newColor)
                        val updated = profiles + newProf
                        profiles = updated
                        selectedProfileId = newId
                        saveProfilesToDisk(updated)
                    },
                    onRenameProfile = { prof -> editingProfile = prof },
                    onSelectTab = { switchToTab(it) },
                    onCloseTab = { closeTab(it) },
                    onNewTab = { profId -> createNewTab(targetProfileId = profId) },
                    onCloseAll = { closeAllTabs() },
                    onCloseSwitcher = { isTabSwitcherOpen = false },
                    modifier = Modifier.fillMaxSize().zIndex(10f)
                )
            }

            // --- Floating Undo Toast / Snackbar Banner ---
            AnimatedVisibility(
                visible = showUndoBanner,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp)
                    .zIndex(30f)
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
                            text = undoMessage,
                            color = Color(0xFFE8EAED),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )

                        TextButton(
                            onClick = {
                                undoJob?.cancel()
                                // Move all hot staged WebViews back to the active pool
                                pendingPurgeWebViews.forEach { (id, wv) ->
                                    webViewPool[id] = wv
                                }
                                pendingPurgeWebViews.clear()

                                val backup = lastClosedTabsSnapshot
                                if (backup != null && backup.isNotEmpty()) {
                                    tabs = backup
                                    val targetId = if (backup.any { it.id == lastActiveTabIdSnapshot }) lastActiveTabIdSnapshot!! else backup.first().id
                                    activeTabId = targetId
                                    saveSessionToDisk(backup, targetId)
                                    attachTabWebView(targetId)
                                }
                                showUndoBanner = false
                                lastClosedTabsSnapshot = null
                            },
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
                            onClick = {
                                undoJob?.cancel()
                                showUndoBanner = false
                                purgePendingWebViews()
                                lastClosedTabsSnapshot = null
                            },
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
    }
}

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
    val currentProfile = profiles.find { it.id == selectedProfileId } ?: profiles.firstOrNull() ?: BrowserProfile("default", "Account 1", 0xFF8AB4F8)

    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

    LaunchedEffect(Unit) {
        val activeIndex = tabs.indexOfFirst { it.id == activeTabId }
        val targetIndex = if (activeIndex >= 0) activeIndex else (tabs.size - 1).coerceAtLeast(0)
        if (targetIndex > 0) {
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
                val tabProfile = profiles.find { it.id == tab.profileId } ?: profiles.firstOrNull() ?: BrowserProfile("default", "Default", 0xFF8AB4F8)

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