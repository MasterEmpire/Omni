package com.omni.plugin.browser.engine

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.webkit.ProfileStore
import java.io.File
import java.util.Locale
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.omni.hub.api.HostBridge
import com.omni.plugin.browser.models.BrowserTab
import com.omni.plugin.browser.models.OmniBlobBridge
import com.omni.plugin.browser.utils.*

interface WebViewEventListener {
    fun onProgressChanged(tabId: String, progress: Int)
    fun onReceivedTitle(tabId: String, title: String)
    fun onUrlChanged(tabId: String, url: String, canGoBack: Boolean, canGoForward: Boolean)
    fun onPageStarted(tabId: String, url: String)
    fun onPageFinished(tabId: String, url: String)
    fun onDownloadTriggered(view: WebView, url: String, userAgent: String, contentDisposition: String, mimeType: String)
    fun onBlobReceived(base64Data: String, mime: String, filename: String)
    fun onNewTabRequested(url: String)
    fun onExternalUri(url: String, view: WebView?): Boolean
    fun onOpenFileChooser(filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: WebChromeClient.FileChooserParams?)
    fun onRenderProcessKilled(tabId: String)
}

class WebViewPoolManager(
    private val context: Context,
    private val bridge: HostBridge,
    private val maxHotTabs: Int = 8
) {
    val pool = mutableMapOf<String, WebView>()
    val pendingPurge = mutableMapOf<String, WebView>()
    val tabDesktopModes = mutableMapOf<String, Boolean>()

    var mobileUA: String = ""
        private set

    private fun interceptLocalhostRequest(uri: Uri): WebResourceResponse? {
        return try {
            val rawPath = uri.path?.removePrefix("/") ?: ""
            val pluginDir = File(bridge.getPluginDir())

            val targetFile = when {
                rawPath.isEmpty() || rawPath == "index.html" -> {
                    File(pluginDir, "ide/index.html")
                }
                rawPath.startsWith("vault_") -> {
                    File(pluginDir, "ide/$rawPath")
                }
                rawPath.startsWith("ide/") -> {
                    File(pluginDir, rawPath)
                }
                else -> {
                    val ideCandidate = File(pluginDir, "ide/$rawPath")
                    if (ideCandidate.exists()) ideCandidate else File(pluginDir, rawPath)
                }
            }

            val finalFile = if (targetFile.isDirectory) File(targetFile, "index.html") else targetFile

            if (finalFile.exists() && finalFile.isFile) {
                val mime = when (finalFile.extension.lowercase(Locale.US)) {
                    "html", "htm" -> "text/html"
                    "js", "mjs" -> "application/javascript"
                    "css" -> "text/css"
                    "json" -> "application/json"
                    "png" -> "image/png"
                    "jpg", "jpeg" -> "image/jpeg"
                    "webp" -> "image/webp"
                    "svg" -> "image/svg+xml"
                    "ico" -> "image/x-icon"
                    "wasm" -> "application/wasm"
                    "woff2" -> "font/woff2"
                    "woff" -> "font/woff"
                    "ttf" -> "font/ttf"
                    else -> "application/octet-stream"
                }

                val response = WebResourceResponse(mime, "UTF-8", finalFile.inputStream())
                response.responseHeaders = mutableMapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
                    "Access-Control-Allow-Headers" to "*",
                    "Cache-Control" to "no-cache, no-store, must-revalidate"
                )
                response
            } else {
                // SPA fallback for client-side OAuth callbacks without file extensions
                val spaFallback = File(pluginDir, "ide/index.html")
                if (spaFallback.exists() && !rawPath.contains(".")) {
                    val response = WebResourceResponse("text/html", "UTF-8", spaFallback.inputStream())
                    response.responseHeaders = mutableMapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD"
                    )
                    response
                } else null
            }
        } catch (e: Exception) {
            bridge.log("LOCAL_HOST_ERR", "Error intercepting localhost request $uri: ${e.message}")
            null
        }
    }

    fun setTabDesktopMode(tabId: String, isDesktop: Boolean) {
        tabDesktopModes[tabId] = isDesktop
        val wv = pool[tabId] ?: return
        wv.settings.userAgentString = if (isDesktop) DESKTOP_USER_AGENT else mobileUA
        wv.settings.useWideViewPort = true
        wv.settings.loadWithOverviewMode = true
        if (isDesktop) {
            wv.evaluateJavascript(DESKTOP_VIEWPORT_SCRIPT, null)
        }
    }

    var isForceDarkWebPages: Boolean = false
    var localPort: Int = 8080

    fun applyForceDark(settings: WebSettings, enabled: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, enabled)
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDark(
                settings,
                if (enabled) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
            )
        }
    }

    fun updateForceDark(enabled: Boolean) {
        isForceDarkWebPages = enabled
        pool.values.forEach { wv ->
            applyForceDark(wv.settings, enabled)
        }
    }

    fun purgePending(containerLayout: FrameLayout?) {
        pendingPurge.forEach { (_, wv) ->
            try {
                wv.stopLoading()
                wv.onPause()
                containerLayout?.removeView(wv)
                wv.destroy()
            } catch (_: Exception) {}
        }
        pendingPurge.clear()
    }

    fun pruneHotPool(keepTabId: String, tabs: List<BrowserTab>, onTabStateSaved: (String, Bundle) -> Unit, containerLayout: FrameLayout?) {
        if (pool.size <= maxHotTabs) return

        val tabsInPool = tabs.filter { it.id in pool.keys && it.id != keepTabId }
            .sortedBy { it.lastAccessedTime }

        var numToEvict = pool.size - maxHotTabs
        for (tabToEvict in tabsInPool) {
            if (numToEvict <= 0) break
            val wv = pool.remove(tabToEvict.id)
            if (wv != null) {
                val bundle = Bundle()
                wv.saveState(bundle)
                onTabStateSaved(tabToEvict.id, bundle)

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

    @SuppressLint("SetJavaScriptEnabled")
    fun createConfiguredWebView(
        tabId: String,
        initialUrl: String,
        savedState: Bundle?,
        profileId: String,
        isDesktop: Boolean,
        listener: WebViewEventListener
    ): WebView {
        val webView = WebView(context).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#1F2227"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                try {
                    WebViewCompat.addDocumentStartJavaScript(this, BLOB_INTERCEPTOR_SCRIPT, setOf("*"))
                } catch (_: Exception) {}
            }

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
                listener.onDownloadTriggered(this, url, userAgent, contentDisposition, mimeType)
            }

            val blobBridge = OmniBlobBridge(
                onBlobReceived = { base64Data, mime, filename ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        listener.onBlobReceived(base64Data, mime, filename)
                    }
                },
                onLog = { tag, msg -> bridge.log(tag, msg) }
            )
            addJavascriptInterface(blobBridge, "OmniBlobDownloader")

            val rawUA = settings.userAgentString
            val cleanMobileUA = rawUA.replace("; wv", "").replace(Regex("Version/[0-9.]+ "), "")
            mobileUA = cleanMobileUA
            tabDesktopModes[tabId] = isDesktop
            settings.userAgentString = if (isDesktop) DESKTOP_USER_AGENT else cleanMobileUA
            applyForceDark(settings, isForceDarkWebPages)

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
                                if (listener.onExternalUri(targetUrl, v)) {
                                    return true
                                }
                                listener.onNewTabRequested(targetUrl)
                                return true
                            }
                        }
                    }
                    transport.webView = tempWv
                    resultMsg.sendToTarget()
                    return true
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    listener.onProgressChanged(tabId, newProgress)
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (!title.isNullOrEmpty()) {
                        listener.onReceivedTitle(tabId, title)
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
                    listener.onOpenFileChooser(filePathCallback, fileChooserParams)
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    val isMainFrame = request.isForMainFrame

                    // Subframes & iframes (such as Conduit IDE live preview) must render in-memory blobs freely
                    if (!isMainFrame) {
                        return false
                    }

                    if (url.startsWith("blob:") || url.startsWith("data:")) {
                        listener.onDownloadTriggered(view ?: this@apply, url, settings.userAgentString, "", "")
                        return true
                    }
                    return listener.onExternalUri(url, view)
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val reqUri = request?.url ?: return super.shouldInterceptRequest(view, request)
                    val host = reqUri.host?.lowercase(Locale.US) ?: ""
                    val port = if (reqUri.port != -1) reqUri.port else 80

                    if ((host == "localhost" || host == "127.0.0.1") && port == localPort) {
                        return interceptLocalhostRequest(reqUri)
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    bridge.log("RENDER_CRASH", "Chromium render process terminated for tab [$tabId] (didCrash: ${detail?.didCrash()})")
                    pool.remove(tabId)
                    try {
                        (view?.parent as? ViewGroup)?.removeView(view)
                        view?.destroy()
                    } catch (_: Exception) {}
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        listener.onRenderProcessKilled(tabId)
                    }
                    return true
                }

                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                    handler?.proceed()
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    if (url != null && url != "about:blank") {
                        listener.onUrlChanged(tabId, url, view?.canGoBack() ?: false, view?.canGoForward() ?: false)
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (url != null) listener.onPageStarted(tabId, url)
                    view?.evaluateJavascript(BOT_BYPASS_POLYFILL, null)
                    view?.evaluateJavascript(BLOB_INTERCEPTOR_SCRIPT, null)
                    if (tabDesktopModes[tabId] == true) {
                        view?.evaluateJavascript(DESKTOP_VIEWPORT_SCRIPT, null)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null) listener.onPageFinished(tabId, url)
                    view?.evaluateJavascript(BLOB_INTERCEPTOR_SCRIPT, null)
                    if (tabDesktopModes[tabId] == true) {
                        view?.evaluateJavascript(DESKTOP_VIEWPORT_SCRIPT, null)
                    }
                    CookieManager.getInstance().flush()
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
}