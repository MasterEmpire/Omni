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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.omni.hub.api.HostBridge
import com.omni.plugin.browser.models.BrowserTab
import com.omni.plugin.browser.models.OmniBlobBridge
import com.omni.plugin.browser.utils.BLOB_INTERCEPTOR_SCRIPT
import com.omni.plugin.browser.utils.BOT_BYPASS_POLYFILL
import com.omni.plugin.browser.utils.DESKTOP_USER_AGENT

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
}

class WebViewPoolManager(
    private val context: Context,
    private val bridge: HostBridge,
    private val maxHotTabs: Int = 8
) {
    val pool = mutableMapOf<String, WebView>()
    val pendingPurge = mutableMapOf<String, WebView>()

    var mobileUA: String = ""
        private set

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
            settings.userAgentString = if (isDesktop) DESKTOP_USER_AGENT else cleanMobileUA

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
                    if (url.startsWith("blob:") || url.startsWith("data:")) {
                        listener.onDownloadTriggered(view ?: this@apply, url, settings.userAgentString, "", "")
                        return true
                    }
                    return listener.onExternalUri(url, view)
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
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null) listener.onPageFinished(tabId, url)
                    view?.evaluateJavascript(BLOB_INTERCEPTOR_SCRIPT, null)
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