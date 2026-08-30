package com.omni.plugin.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.omni.hub.api.HostBridge
import com.omni.hub.api.PluginEntry
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ShortcutItem(val title: String, val url: String, val iconText: String, val color: Color)

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
        val focusManager = LocalFocusManager.current

        var currentUrl by remember { mutableStateOf("about:blank") }
        var urlInputText by remember { mutableStateOf("") }
        var pageTitle by remember { mutableStateOf("New Tab") }
        var isLoading by remember { mutableStateOf(false) }
        var loadProgress by remember { mutableFloatStateOf(0f) }
        var canGoBack by remember { mutableStateOf(false) }
        var canGoForward by remember { mutableStateOf(false) }
        var isDesktopMode by remember { mutableStateOf(false) }
        var showMenu by remember { mutableStateOf(false) }

        var webViewInstance: WebView? by remember { mutableStateOf(null) }
        var mobileUA by remember { mutableStateOf("") }

        val desktopUA = remember {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1F2227))
                .statusBarsPadding()
                .imePadding()
        ) {
            // --- Top Chrome Omnibox Header ---
            Surface(
                color = Color(0xFF1F2227),
                modifier = Modifier.fillMaxWidth()
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
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = Color(0xFF282C34),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
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

                            TextField(
                                value = urlInputText,
                                onValueChange = { urlInputText = it },
                                placeholder = {
                                    Text(
                                        "Search or type URL",
                                        color = Color(0xFF80868B),
                                        fontSize = 14.sp
                                    )
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Go
                                ),
                                keyboardActions = KeyboardActions(
                                    onGo = { navigateTo(urlInputText) }
                                ),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    focusedTextColor = Color(0xFFE8EAED),
                                    unfocusedTextColor = Color(0xFFE8EAED)
                                ),
                                modifier = Modifier.weight(1f)
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
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF9AA0A6)),
                        modifier = Modifier
                            .size(28.dp)
                            .clickable { bridge.showToast("Tab management ready in next update!") }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("1", color = Color(0xFFE8EAED), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
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
                                text = { Text("Reload Page", color = Color(0xFFE8EAED)) },
                                onClick = {
                                    showMenu = false
                                    webViewInstance?.reload()
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
                // Background WebView (Always Alive)
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            // 1. Dynamic Hardware-Matched User-Agent Sanitization
                            val rawUA = settings.userAgentString
                            mobileUA = rawUA.replace("; wv", "").replace(Regex("Version/[0-9.]+ "), "")
                            settings.userAgentString = mobileUA

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadProgress = newProgress / 100f
                                    isLoading = newProgress in 1..99
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    if (!title.isNullOrEmpty()) pageTitle = title
                                }

                                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                    bridge.log("CHROME_CONSOLE", "[${consoleMessage?.messageLevel()}] ${consoleMessage?.message()}")
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
                                    canGoBack = view?.canGoBack() ?: false
                                    canGoForward = view?.canGoForward() ?: false
                                    if (url != null && url != "about:blank") {
                                        currentUrl = url
                                        urlInputText = url
                                    }
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true

                                    // Deep Prototype & Native toString Stealth Engine
                                    val stealthPolyfill = """
                                        javascript:(function() {
                                            // --- 0. Global Native toString Masking Engine (Parrot Fix) ---
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

                                            // --- 1. Strip WebDriver Cleanly ---
                                            try {
                                                delete Object.getPrototypeOf(navigator).webdriver;
                                            } catch(e) {}

                                            // --- 2. Mock Chrome Runtime Object ---
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

                                            // --- 3. Mock Real Chromium PluginArray & MimeTypes on Navigator.prototype (Peacock Fix) ---
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

                                            // --- 4. Prototype-Level Client Hints (Leopard & Peacock Fix) ---
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

                                            // --- 5. Prototype-Level Permissions Alignment ---
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

                                            // --- 6. Synchronous Iframe Stealth ---
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

                                            // --- 7. Native Web Speech API Mock (Parrot Compliant) ---
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
                                    isLoading = false
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

                                // Suppress 'X-Requested-With: com.omni.hub' HTTP header across all network requests
                                try {
                                    val method = javaClass.getMethod("setRequestedWithHeaderOriginAllowList", Set::class.java)
                                    method.invoke(this, emptySet<String>())
                                } catch (_: Exception) {}
                            }

                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        }
                    },
                    update = { view -> webViewInstance = view },
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

            // --- Bottom Navigation Toolbar ---
            Surface(
                color = Color(0xFF1F2227),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
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
                        onClick = { webViewInstance?.goForward() },
                        enabled = canGoForward
                    ) {
                        Icon(
                            Icons.Default.ArrowForward,
                            contentDescription = "Forward",
                            tint = if (canGoForward) Color(0xFFE8EAED) else Color(0xFF5F6368)
                        )
                    }

                    IconButton(onClick = { navigateTo("about:blank") }) {
                        Icon(Icons.Default.Home, contentDescription = "New Tab", tint = Color(0xFFE8EAED))
                    }

                    IconButton(onClick = { webViewInstance?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = Color(0xFFE8EAED))
                    }

                    IconButton(onClick = { bridge.close() }) {
                        Icon(Icons.Default.Close, contentDescription = "Exit", tint = Color(0xFFF28B82))
                    }
                }
            }
        }
    }
}