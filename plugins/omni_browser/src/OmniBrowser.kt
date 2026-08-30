package com.omni.plugin.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalFocusManager
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

data class ShortcutItem(val title: String, val url: String, val iconText: String, val color: Color)

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

class OmniBrowser : PluginEntry() {

    override fun onCreateView(context: Context, bridge: HostBridge, baseDir: String): View {
        return ComposeView(context).apply {
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
        val context = androidx.compose.ui.platform.LocalContext.current
        val focusManager = LocalFocusManager.current

        val MAX_HOT_TABS = 8

        // Multi-Profile State Management
        var profiles by remember {
            mutableStateOf(
                listOf(
                    BrowserProfile("default", "Default", 0xFF8AB4F8)
                )
            )
        }
        var selectedProfileId by remember { mutableStateOf("default") }
        var editingProfile by remember { mutableStateOf<BrowserProfile?>(null) }

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
            } catch (_: Exception) {}
        }

        fun saveBase64ToDownloads(base64Data: String, mimeType: String, rawFilename: String) {
            try {
                val cleanBase64 = if (base64Data.contains(",")) base64Data.substringAfter(",") else base64Data
                val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                val ext = when {
                    mimeType.contains("pdf") -> "pdf"
                    mimeType.contains("png") -> "png"
                    mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
                    mimeType.contains("zip") -> "zip"
                    mimeType.contains("json") -> "json"
                    mimeType.contains("html") -> "html"
                    else -> "bin"
                }
                val filename = if (rawFilename.isNotEmpty() && rawFilename != "null" && rawFilename != "blob") rawFilename else "download_${System.currentTimeMillis()}.$ext"

                var saved = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
                    }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val targetDir = File(dir, "OmniDownloads").apply { mkdirs() }
                    val file = File(targetDir, filename)
                    FileOutputStream(file).use { it.write(bytes) }
                    saved = true
                }

                if (saved) {
                    bridge.showToast("Saved $filename to Downloads/OmniDownloads")
                    bridge.log("DOWNLOAD", "Saved Blob download: $filename (${bytes.size} bytes)")
                }
            } catch (e: Exception) {
                bridge.showToast("Blob download failed: ${e.message}")
                bridge.log("DOWNLOAD_ERR", "Blob decode error: ${e.message}")
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

        fun triggerFileDownload(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                val suggestedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                if (url.startsWith("data:")) {
                    saveBase64ToDownloads(url, mimeType, suggestedName)
                } else {
                    val blobScript = """
                        (function() {
                            var xhr = new XMLHttpRequest();
                            xhr.open('GET', '$url', true);
                            xhr.responseType = 'blob';
                            xhr.onload = function() {
                                if (this.status === 200 || this.status === 0) {
                                    var reader = new FileReader();
                                    reader.readAsDataURL(this.response);
                                    reader.onloadend = function() {
                                        window.OmniBlobDownloader.processBlob(reader.result, '$mimeType', '$suggestedName');
                                    };
                                }
                            };
                            xhr.send();
                        })();
                    """.trimIndent()
                    webViewInstance?.evaluateJavascript(blobScript, null)
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
                dm?.enqueue(request)
                bridge.showToast("Downloading $filename...")
                bridge.log("DOWNLOAD", "Queued system download for $url ($filename)")
            } catch (e: Exception) {
                bridge.showToast("Download error: ${e.message}")
                bridge.log("DOWNLOAD_ERR", "Download queue exception: ${e.message}")
            }
        }

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
        var containerLayout: FrameLayout? by remember { mutableStateOf(null) }
        var webViewInstance: WebView? by remember { mutableStateOf(null) }

        var mobileUA by remember { mutableStateOf("") }
        var showSolverDialog by remember { mutableStateOf(false) }
        var solverApiKey by remember { mutableStateOf("") }
        var autoSolveEnabled by remember { mutableStateOf(true) }
        var isSolvingCaptcha by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()

        val desktopUA = remember {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        }

        // Load persisted solver and profile configurations
        LaunchedEffect(Unit) {
            try {
                val savedBytes = bridge.readFile("config/solver.json")
                if (savedBytes != null) {
                    val json = org.json.JSONObject(String(savedBytes, Charsets.UTF_8))
                    solverApiKey = json.optString("apiKey", "")
                    autoSolveEnabled = json.optBoolean("autoSolve", true)
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
                            val targetId = if (savedActiveId.isNotEmpty() && loadedTabs.any { it.id == savedActiveId }) savedActiveId else loadedTabs.first().id
                            activeTabId = targetId
                            val currentActive = loadedTabs.first { it.id == targetId }
                            currentUrl = currentActive.url
                            urlInputText = if (currentActive.url == "about:blank") "" else currentActive.url
                            pageTitle = currentActive.title
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
                    triggerFileDownload(url, userAgent, contentDisposition, mimeType)
                }

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun processBlob(base64Data: String, mimeType: String, filename: String) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            saveBase64ToDownloads(base64Data, mimeType, filename)
                        }
                    }
                }, "OmniBlobDownloader")

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
                                    createNewTab(targetUrl)
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
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return false
                        if (url.startsWith("blob:") || url.startsWith("data:")) {
                            triggerFileDownload(url, view?.settings?.userAgentString ?: "", "", "")
                            return true
                        }
                        return handleExternalUri(url, view)
                    }

                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                        // Allow navigation through self-signed and local development certificates
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

                        val stealthPolyfill = """
                            javascript:(function() {
                                const nativeToString = Function.prototype.toString;
                                const registeredNativeFunctions = new WeakSet();

                                function makeNative(fn, name) {
                                    if (name) {
                                        try {
                                            Object.defineProperty(fn, 'name', { value: name, configurable: true });
                                        } catch(e) {}
                                    }
                                    registeredNativeFunctions.add(fn);
                                    return fn;
                                }

                                Function.prototype.toString = function() {
                                    if (registeredNativeFunctions.has(this)) {
                                        return "function " + (this.name || "") + "() { [native code] }";
                                    }
                                    return nativeToString.apply(this, arguments);
                                };
                                makeNative(Function.prototype.toString, 'toString');

                                try {
                                    delete Object.getPrototypeOf(navigator).webdriver;
                                } catch(e) {}

                                try {
                                    if (!window.chrome) {
                                        window.chrome = {
                                            app: {
                                                isInstalled: false,
                                                InstallState: { DISABLED: "disabled", INSTALLED: "installed", NOT_INSTALLED: "not_installed" },
                                                RunningState: { CANNOT_RUN: "cannot_run", READY_TO_RUN: "ready_to_run", RUNNING: "running" }
                                            },
                                            runtime: {
                                                OnInstalledReason: {},
                                                OnRestartRequiredReason: {},
                                                PlatformArch: {},
                                                PlatformNaclArch: {},
                                                PlatformOs: {},
                                                RequestUpdateCheckStatus: {},
                                                connect: makeNative(function connect(){}, 'connect'),
                                                sendMessage: makeNative(function sendMessage(){}, 'sendMessage')
                                            },
                                            csi: makeNative(function csi(){}, 'csi'),
                                            loadTimes: makeNative(function loadTimes(){}, 'loadTimes')
                                        };
                                    }
                                } catch(e) {}

                                try {
                                    const pluginData = [
                                        { name: "PDF Viewer", filename: "internal-pdf-viewer", description: "Portable Document Format" },
                                        { name: "Chrome PDF Viewer", filename: "internal-pdf-viewer", description: "Portable Document Format" },
                                        { name: "Chromium PDF Viewer", filename: "internal-pdf-viewer", description: "Portable Document Format" },
                                        { name: "Microsoft Edge PDF Viewer", filename: "internal-pdf-viewer", description: "Portable Document Format" },
                                        { name: "WebKit built-in PDF", filename: "internal-pdf-viewer", description: "Portable Document Format" }
                                    ];

                                    const fakePlugins = Object.create(PluginArray.prototype);
                                    const fakeMimes = Object.create(MimeTypeArray.prototype);

                                    pluginData.forEach((p, idx) => {
                                        const pluginObj = Object.create(Plugin.prototype);
                                        Object.defineProperties(pluginObj, {
                                            name: { value: p.name, enumerable: true },
                                            filename: { value: p.filename, enumerable: true },
                                            description: { value: p.description, enumerable: true },
                                            length: { value: 0, enumerable: true }
                                        });
                                        fakePlugins[idx] = pluginObj;
                                        fakePlugins[p.name] = pluginObj;
                                    });

                                    Object.defineProperties(fakePlugins, {
                                        length: { value: pluginData.length, enumerable: false },
                                        item: { value: makeNative(function item(i) { return this[i] || null; }, 'item'), enumerable: false },
                                        namedItem: { value: makeNative(function namedItem(name) { return this[name] || null; }, 'namedItem'), enumerable: false },
                                        refresh: { value: makeNative(function refresh() {}, 'refresh'), enumerable: false }
                                    });

                                    const navProto = Object.getPrototypeOf(navigator);
                                    Object.defineProperty(navProto, 'plugins', {
                                        get: makeNative(function plugins() { return fakePlugins; }, 'get plugins'),
                                        enumerable: true,
                                        configurable: true
                                    });

                                    Object.defineProperty(navProto, 'mimeTypes', {
                                        get: makeNative(function mimeTypes() { return fakeMimes; }, 'get mimeTypes'),
                                        enumerable: true,
                                        configurable: true
                                    });

                                    Object.defineProperty(navProto, 'pdfViewerEnabled', {
                                        get: makeNative(function pdfViewerEnabled() { return true; }, 'get pdfViewerEnabled'),
                                        enumerable: true,
                                        configurable: true
                                    });
                                } catch(e) {}

                                try {
                                    const chromeBrands = Object.freeze([
                                        Object.freeze({ brand: 'Not_A Brand', version: '8' }),
                                        Object.freeze({ brand: 'Chromium', version: '128' }),
                                        Object.freeze({ brand: 'Google Chrome', version: '128' })
                                    ]);

                                    const fakeUaData = {
                                        brands: chromeBrands,
                                        mobile: true,
                                        platform: 'Android',
                                        getHighEntropyValues: makeNative(function getHighEntropyValues(hints) {
                                            return Promise.resolve({
                                                brands: chromeBrands,
                                                mobile: true,
                                                platform: 'Android',
                                                architecture: 'arm',
                                                bitness: '64',
                                                model: 'SM-A315G',
                                                platformVersion: '12.0.0',
                                                fullVersionList: chromeBrands
                                            });
                                        }, 'getHighEntropyValues'),
                                        toJSON: makeNative(function toJSON() {
                                            return { brands: chromeBrands, mobile: true, platform: 'Android' };
                                        }, 'toJSON')
                                    };

                                    const navProto = Object.getPrototypeOf(navigator);
                                    Object.defineProperty(navProto, 'userAgentData', {
                                        get: makeNative(function userAgentData() { return fakeUaData; }, 'get userAgentData'),
                                        configurable: true,
                                        enumerable: true
                                    });
                                } catch(e) {}

                                try {
                                    if (window.Notification) {
                                        Object.defineProperty(window.Notification, 'permission', {
                                            get: makeNative(function permission() { return 'default'; }, 'get permission'),
                                            configurable: true,
                                            enumerable: true
                                        });
                                    }

                                    if (window.Permissions && Permissions.prototype && Permissions.prototype.query) {
                                        const origQuery = Permissions.prototype.query;
                                        Permissions.prototype.query = makeNative(function query(params) {
                                            if (params && params.name === 'notifications') {
                                                const status = Object.create(PermissionStatus.prototype || Object.prototype);
                                                Object.defineProperties(status, {
                                                    name: { value: 'notifications', enumerable: true },
                                                    state: { value: 'prompt', enumerable: true, writable: false },
                                                    status: { value: 'prompt', enumerable: true, writable: false },
                                                    onchange: { value: null, enumerable: true, writable: true }
                                                });
                                                return Promise.resolve(status);
                                            }
                                            return origQuery.apply(this, arguments);
                                        }, 'query');
                                    }
                                } catch(e) {}

                                try {
                                    const originalContentWindow = Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype, 'contentWindow').get;
                                    Object.defineProperty(HTMLIFrameElement.prototype, 'contentWindow', {
                                        get: makeNative(function contentWindow() {
                                            const win = originalContentWindow.call(this);
                                            if (win) {
                                                try {
                                                    if (!win.chrome) win.chrome = window.chrome;
                                                    if (win.navigator) delete Object.getPrototypeOf(win.navigator).webdriver;
                                                } catch(e) {}
                                            }
                                            return win;
                                        }, 'get contentWindow'),
                                        configurable: true,
                                        enumerable: true
                                    });
                                } catch(e) {}

                                try {
                                    if (!('speechSynthesis' in window)) {
                                        window.speechSynthesis = {
                                            pending: false,
                                            speaking: false,
                                            paused: false,
                                            onvoiceschanged: null,
                                            getVoices: makeNative(function getVoices() { return []; }, 'getVoices'),
                                            speak: makeNative(function speak() {}, 'speak'),
                                            cancel: makeNative(function cancel() {}, 'cancel'),
                                            pause: makeNative(function pause() {}, 'pause'),
                                            resume: makeNative(function resume() {}, 'resume'),
                                            addEventListener: makeNative(function addEventListener() {}, 'addEventListener'),
                                            removeEventListener: makeNative(function removeEventListener() {}, 'removeEventListener'),
                                            dispatchEvent: makeNative(function dispatchEvent() { return true; }, 'dispatchEvent')
                                        };
                                    }
                                } catch(e) {}
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(stealthPolyfill, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (activeTabId == tabId) isLoading = false
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

            container.addView(targetWv)
            targetWv.onResume()
            webViewInstance = targetWv

            currentUrl = targetTab.url
            urlInputText = if (targetTab.url == "about:blank") "" else targetTab.url
            pageTitle = targetTab.title
            canGoBack = targetWv.canGoBack()
            canGoForward = targetWv.canGoForward()
        }

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
            webViewPool.remove(targetId)?.let { wv ->
                wv.stopLoading()
                wv.onPause()
                containerLayout?.removeView(wv)
                wv.destroy()
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
            webViewPool.forEach { (_, wv) ->
                wv.stopLoading()
                wv.onPause()
                wv.destroy()
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
                input.startsWith("localhost") || input.startsWith("127.0.0.1") || input.startsWith("192.168.") || input.startsWith("10.") || input.startsWith("172.") -> "http://$input"
                input.contains(".") && !input.contains(" ") -> "https://$input"
                else -> "https://www.google.com/search?q=${URLEncoder.encode(input, "UTF-8")}"
            }

            urlInputText = if (target == "about:blank") "" else target
            currentUrl = target
            tabs = tabs.map { if (it.id == activeTabId) it.copy(url = target) else it }
            webViewInstance?.loadUrl(target)
            focusManager.clearFocus()
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

                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val filename = "DOM_Dump_$timestamp.html"
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

                    // Keep backup in plugin workspace
                    bridge.saveFile("snapshots/$filename", rawHtml.toByteArray(Charsets.UTF_8))
                    bridge.log("DOM_SNAPSHOT", "Saved DOM snapshot: $filename (${rawHtml.length} bytes)")

                    if (savedToDownloads) {
                        bridge.showToast("Saved to Downloads/OmniSnapshots/$filename")
                    } else {
                        bridge.showToast("Saved to plugin sandbox: snapshots/$filename")
                    }
                } else {
                    bridge.showToast("Could not capture page DOM.")
                }
            }
        }

        val shortcuts = remember {
            listOf(
                ShortcutItem("Bot Test", "https://bot.sannysoft.com/", "🕵️", Color(0xFF34A853)),
                ShortcutItem("Google", "https://www.google.com", "G", Color(0xFF4285F4)),
                ShortcutItem("YouTube", "https://m.youtube.com", "▶", Color(0xFFEA4335)),
                ShortcutItem("GitHub", "https://github.com", "⌥", Color(0xFF24292E)),
                ShortcutItem("Reddit", "https://reddit.com", "R", Color(0xFFFF4500)),
                ShortcutItem("DuckDuckGo", "https://duckduckgo.com", "D", Color(0xFFDE5833)),
                ShortcutItem("Wikipedia", "https://wikipedia.org", "W", Color(0xFF5F6368)),
                ShortcutItem("BrowserLeaks", "https://browserleaks.com/javascript", "🔍", Color(0xFF9C27B0))
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1F2227))
                    .statusBarsPadding()
                    .imePadding()
            ) {
                // --- Top Chrome Omnibox Header ---
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(1f)
                        .graphicsLayer()
                        .background(Color(0xFF1F2227))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Home Button
                        IconButton(
                            onClick = { navigateTo("about:blank") },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(Icons.Default.Home, contentDescription = "Home", tint = Color(0xFF9AA0A6))
                        }

                        val activeTab = tabs.find { it.id == activeTabId }
                        val activeProf = profiles.find { it.id == activeTab?.profileId } ?: profiles.firstOrNull() ?: BrowserProfile("default", "Default", 0xFF8AB4F8)

                        // Chrome Address Pill with Account Color Glow
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(Color(0xFF282C34))
                                .border(1.dp, Color(activeProf.colorValue).copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp)
                            ) {
                                Icon(
                                    imageVector = if (currentUrl.startsWith("https://")) Icons.Default.Check else if (currentUrl.startsWith("http://")) Icons.Default.Info else Icons.Default.Search,
                                    contentDescription = null,
                                    tint = if (currentUrl.startsWith("https://")) Color(0xFF81C995) else if (currentUrl.startsWith("http://")) Color(0xFFFDD663) else Color(0xFF9AA0A6),
                                    modifier = Modifier.size(16.dp)
                                )

                                Spacer(Modifier.width(8.dp))

                                BasicTextField(
                                    value = urlInputText,
                                    onValueChange = { urlInputText = it },
                                    singleLine = true,
                                    maxLines = 1,
                                    cursorBrush = SolidColor(Color(0xFF8AB4F8)),
                                    textStyle = TextStyle(
                                        color = Color(0xFFE8EAED),
                                        fontSize = 14.sp,
                                        lineHeight = 18.sp,
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
                                                    color = Color(0xFF80868B),
                                                    fontSize = 14.sp,
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

                        // Tab Counter Badge
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.5.dp, Color(0xFF9AA0A6), RoundedCornerShape(6.dp))
                                .clickable {
                                    val thumb = captureThumbnail()
                                    val bundle = Bundle()
                                    webViewInstance?.saveState(bundle)
                                    tabs = tabs.map { if (it.id == activeTabId) it.copy(stateBundle = bundle, thumbnail = thumb ?: it.thumbnail) else it }
                                    CookieManager.getInstance().flush()
                                    isTabSwitcherOpen = true
                                }
                        ) {
                            Text("${tabs.size}", color = Color(0xFFE8EAED), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(Modifier.width(4.dp))

                        // 3-Dots Menu
                        Box {
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Color(0xFF9AA0A6))
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                modifier = Modifier.background(Color(0xFF282C34))
                            ) {
                                // Chrome-style Quick Action Row (Back, Forward, Reload)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
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
                                            webViewInstance?.reload()
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = "Reload",
                                            tint = Color(0xFFE8EAED)
                                        )
                                    }
                                }

                                HorizontalDivider(color = Color(0xFF3C4043))

                                DropdownMenuItem(
                                    text = { Text("+ New Tab", color = Color(0xFF8AB4F8), fontWeight = FontWeight.Bold) },
                                    onClick = {
                                        showMenu = false
                                        createNewTab()
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Close Tab", color = Color(0xFFE8EAED)) },
                                    onClick = {
                                        showMenu = false
                                        closeTab(activeTabId)
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text(if (isDesktopMode) "✓ Desktop Site" else "Desktop Site", color = Color(0xFFE8EAED)) },
                                    onClick = {
                                        showMenu = false
                                        isDesktopMode = !isDesktopMode
                                        webViewInstance?.settings?.userAgentString = if (isDesktopMode) desktopUA else mobileUA
                                        webViewInstance?.reload()
                                        bridge.showToast(if (isDesktopMode) "Desktop Mode Enabled" else "Mobile Mode Enabled")
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Copy URL", color = Color(0xFFE8EAED)) },
                                    onClick = {
                                        showMenu = false
                                        if (currentUrl != "about:blank") {
                                            bridge.copyToClipboard(currentUrl)
                                        }
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("🤖 Auto-Solve CAPTCHA", color = Color(0xFF81C995), fontWeight = FontWeight.Bold) },
                                    onClick = {
                                        showMenu = false
                                        scanAndSolveCaptcha()
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("⚙️ Solver Settings", color = Color(0xFF8AB4F8)) },
                                    onClick = {
                                        showMenu = false
                                        showSolverDialog = true
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Capture DOM Snapshot", color = Color(0xFF8AB4F8)) },
                                    onClick = {
                                        showMenu = false
                                        captureDomSnapshot()
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Clear Cookies & Cache", color = Color(0xFFF28B82)) },
                                    onClick = {
                                        showMenu = false
                                        CookieManager.getInstance().removeAllCookies(null)
                                        webViewInstance?.clearCache(true)
                                        bridge.showToast("Cookies and Cache cleared.")
                                    }
                                )

                                HorizontalDivider(color = Color(0xFF3C4043))

                                DropdownMenuItem(
                                    text = { Text("Exit Omni Chrome", color = Color(0xFFF28B82), fontWeight = FontWeight.Bold) },
                                    onClick = {
                                        showMenu = false
                                        bridge.close()
                                    }
                                )
                            }
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
                        color = Color(0xFF8AB4F8),
                        trackColor = Color(0xFF282C34),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp)
                    )
                }

                // --- Main Content (Speed Dial vs WebView) ---
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    // Dedicated Multi-WebView Host Container
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

                    // Speed-Dial Homepage Overlay (Shown on about:blank)
                    if (currentUrl == "about:blank") {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF1F2227))
                            .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.height(40.dp))

                            // Chrome Logo Aesthetic
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFF8AB4F8),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("🌐", fontSize = 18.sp)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "Omni Chrome",
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE8EAED)
                                )
                            }

                            Text(
                                "Fast, Stealthy, Dynamic Browsing",
                                fontSize = 13.sp,
                                color = Color(0xFF9AA0A6),
                                modifier = Modifier.padding(top = 6.dp, bottom = 32.dp)
                            )

                            // Shortcuts Grid
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(shortcuts) { item ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { navigateTo(item.url) }
                                            .padding(8.dp)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color(0xFF282C34),
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(item.iconText, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = item.color)
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
                            }
                        }
                    }
                }
            }

            // --- Solver Settings Dialog ---
            if (showSolverDialog) {
                var tempKey by remember { mutableStateOf(solverApiKey) }
                var tempAuto by remember { mutableStateOf(autoSolveEnabled) }

                AlertDialog(
                    onDismissRequest = { showSolverDialog = false },
                    containerColor = Color(0xFF282C34),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🤖", fontSize = 20.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("NoCaptchaAI Settings", color = Color(0xFFE8EAED), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "Enter your NoCaptchaAI client key to enable automated background CAPTCHA resolution.",
                                color = Color(0xFF9AA0A6),
                                fontSize = 12.sp
                            )

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
                                Text("Auto-Solve on Page Load", color = Color(0xFFE8EAED), fontSize = 13.sp)
                                Switch(
                                    checked = tempAuto,
                                    onCheckedChange = { tempAuto = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF8AB4F8))
                                )
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
                                bridge.showToast("Solver settings saved!")
                                showSolverDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8AB4F8))
                        ) {
                            Text("Save", color = Color(0xFF1F2227), fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSolverDialog = false }) {
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

    Column(
        modifier = modifier
            .background(Color(0xFF1F2227))
            .statusBarsPadding()
    ) {
        // Tab Switcher Header Bar
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
                // Left: New Tab Button & Profile Selector Pill
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onNewTab(selectedProfileId) }) {
                        Icon(Icons.Default.Add, contentDescription = "New Tab", tint = Color(0xFFE8EAED))
                    }

                    // Profile Selector Pill
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

        // 2-Column Tabs Card Grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
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
                        // Tab Card Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isActive) Color(0xFF333842) else Color(0xFF21252B))
                                .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Account Color Dot
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(tabProfile.colorValue))
                            )
                            Spacer(Modifier.width(6.dp))

                            Text(
                                text = if (tab.url == "about:blank") "New Tab" else tab.title.ifEmpty { "Web Page" },
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

                        // Tab Card Preview Body
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(Color(0xFF1F2227)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (tab.thumbnail != null && tab.url != "about:blank") {
                                Image(
                                    bitmap = tab.thumbnail.asImageBitmap(),
                                    contentDescription = tab.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Text(
                                        text = if (tab.url == "about:blank") "🌐" else "📄",
                                        fontSize = 24.sp
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = if (tab.url == "about:blank") "about:blank" else tab.url.replace("https://", "").replace("http://", "").take(25),
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