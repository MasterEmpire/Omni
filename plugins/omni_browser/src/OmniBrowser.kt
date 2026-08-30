package com.omni.plugin.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.*
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

data class BrowserTab(
    val id: String,
    val title: String = "New Tab",
    val url: String = "about:blank",
    val stateBundle: Bundle? = null,
    val thumbnail: Bitmap? = null
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

        var tabs by remember {
            mutableStateOf(
                listOf(
                    BrowserTab(
                        id = "tab_1",
                        title = "New Tab",
                        url = "about:blank"
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

        // Load persisted solver configuration
        LaunchedEffect(Unit) {
            try {
                val savedBytes = bridge.readFile("config/solver.json")
                if (savedBytes != null) {
                    val json = org.json.JSONObject(String(savedBytes, Charsets.UTF_8))
                    solverApiKey = json.optString("apiKey", "")
                    autoSolveEnabled = json.optBoolean("autoSolve", true)
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

                    // 1. Dispatch Task Creation
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

                        // 2. Poll getTaskResult if taskId returned asynchronously
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

                        // 3. Inject Solution Token into DOM
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

        fun createConfiguredWebView(tabId: String, initialUrl: String): WebView {
            val webView = WebView(context).apply {
                setBackgroundColor(android.graphics.Color.parseColor("#1F2227"))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                val rawUA = settings.userAgentString
                val mobileUserAgent = rawUA.replace("; wv", "").replace(Regex("Version/[0-9.]+ "), "")
                mobileUA = mobileUserAgent
                settings.userAgentString = mobileUserAgent

                webChromeClient = object : WebChromeClient() {
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

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            }

            if (initialUrl.isNotEmpty() && initialUrl != "about:blank") {
                webView.loadUrl(initialUrl)
            }
            return webView
        }

        fun attachTabWebView(targetTabId: String) {
            val container = containerLayout ?: return
            
            webViewPool.forEach { (id, wv) ->
                if (id != targetTabId) {
                    wv.onPause()
                }
            }
            container.removeAllViews()

            val targetTab = tabs.find { it.id == targetTabId } ?: return
            val targetWv = webViewPool.getOrPut(targetTabId) {
                createConfiguredWebView(targetTabId, targetTab.url)
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

        fun createNewTab(targetUrl: String = "about:blank") {
            val thumb = captureThumbnail()
            val updatedTabs = tabs.map { if (it.id == activeTabId && thumb != null) it.copy(thumbnail = thumb) else it }

            val newId = "tab_${System.currentTimeMillis()}"
            val newTab = BrowserTab(id = newId, title = if (targetUrl == "about:blank") "New Tab" else targetUrl, url = targetUrl)
            tabs = updatedTabs + newTab
            activeTabId = newId
            isTabSwitcherOpen = false

            attachTabWebView(newId)
        }

        fun switchToTab(targetId: String) {
            if (targetId == activeTabId) {
                isTabSwitcherOpen = false
                return
            }
            val thumb = captureThumbnail()
            val updatedTabs = tabs.map { if (it.id == activeTabId && thumb != null) it.copy(thumbnail = thumb) else it }
            tabs = updatedTabs
            activeTabId = targetId
            isTabSwitcherOpen = false

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
                attachTabWebView(newId)
            } else {
                tabs = remainingTabs
                if (targetId == activeTabId) {
                    val nextIdx = (currentIdx - 1).coerceAtLeast(0).coerceAtMost(remainingTabs.size - 1)
                    val nextTab = remainingTabs[nextIdx]
                    activeTabId = nextTab.id
                    attachTabWebView(nextTab.id)
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

            attachTabWebView(newId)
        }

        fun navigateTo(rawInput: String) {
            val input = rawInput.trim()
            if (input.isEmpty()) return

            val target = when {
                input == "about:blank" -> "about:blank"
                input.startsWith("http://") || input.startsWith("https://") -> input
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
            webViewInstance?.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { html ->
                if (!html.isNullOrEmpty()) {
                    val rawHtml = if (html.startsWith("\"") && html.endsWith("\"")) {
                        try {
                            org.json.JSONObject("{\"h\":$html}").getString("h")
                        } catch (_: Exception) { html }
                    } else html

                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val filename = "snapshots/DOM_Dump_$timestamp.html"
                    bridge.saveFile(filename, rawHtml.toByteArray(Charsets.UTF_8))
                    bridge.log("DOM_SNAPSHOT", "Saved DOM snapshot to: $filename (${rawHtml.length} bytes)")
                    bridge.showToast("DOM Snapshot Saved to $filename")
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

                        // Chrome Address Pill
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(Color(0xFF282C34))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp)
                            ) {
                                Icon(
                                    imageVector = if (currentUrl.startsWith("https://")) Icons.Default.Check else Icons.Default.Search,
                                    contentDescription = null,
                                    tint = if (currentUrl.startsWith("https://")) Color(0xFF81C995) else Color(0xFF9AA0A6),
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
                                tabs = tabs.map { if (it.id == activeTabId) it.copy(stateBundle = bundle, thumbnail = thumb) else it }
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

            if (isTabSwitcherOpen) {
                TabSwitcherScreen(
                    tabs = tabs,
                    activeTabId = activeTabId,
                    onSelectTab = { switchToTab(it) },
                    onCloseTab = { closeTab(it) },
                    onNewTab = { createNewTab() },
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
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseAll: () -> Unit,
    onCloseSwitcher: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showTabMenu by remember { mutableStateOf(false) }

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
                .padding(horizontal = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNewTab) {
                    Icon(Icons.Default.Add, contentDescription = "New Tab", tint = Color(0xFFE8EAED))
                }

                Text(
                    text = "${tabs.size} open ${if (tabs.size == 1) "tab" else "tabs"}",
                    color = Color(0xFFE8EAED),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
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
                                    onNewTab()
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
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF282C34)),
                    border = if (isActive) BorderStroke(2.dp, Color(0xFF8AB4F8)) else null,
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