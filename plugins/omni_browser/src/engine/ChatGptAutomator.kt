package com.omni.plugin.browser.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.*
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.omni.hub.api.HostBridge
import com.omni.plugin.browser.models.AutomationAttachment
import com.omni.plugin.browser.utils.BOT_BYPASS_POLYFILL
import com.omni.plugin.browser.utils.CHATGPT_HEADLESS_TURBO_SCRIPT
import com.omni.plugin.browser.utils.CHATGPT_WIRE_INTERCEPTOR_SCRIPT
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ActiveGptPuppetSession(
    val sessionId: String,
    val profileId: String,
    val model: String,
    val webView: WebView,
    var onReadyCallback: ((Boolean, String?) -> Unit)? = null,
    var onConfigCallback: ((Boolean, String?) -> Unit)? = null,
    var onNewChatCallback: ((Boolean, String?) -> Unit)? = null,
    var onTurnProgressCallback: ((String, String) -> Unit)? = null,
    var onTurnDeltaCallback: ((String, String, Boolean) -> Unit)? = null,
    var onTurnCompleteCallback: ((String, String) -> Unit)? = null,
    var onTurnErrorCallback: ((String) -> Unit)? = null,
    var isAborted: Boolean = false
)

/**
 * Dedicated Multi-Session Puppet Pool for ChatGPT (chatgpt.com).
 * Completely separate from AiStudioAutomator.
 */
class ChatGptAutomator(
    private val context: Context,
    private val bridge: HostBridge
) {
    val sessions = ConcurrentHashMap<String, ActiveGptPuppetSession>()
    private var defaultSessionId: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextBootSlotAtMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val siteCacheManager = com.omni.plugin.browser.services.SiteCacheManager(context, bridge)

    val headlessWv: WebView?
        get() = defaultSessionId?.let { sessions[it]?.webView } ?: sessions.values.firstOrNull()?.webView

    var isAborted: Boolean
        get() = defaultSessionId?.let { sessions[it]?.isAborted } ?: false
        set(value) {
            defaultSessionId?.let { sessions[it]?.isAborted = value }
            if (value) {
                sessions.values.forEach { it.isAborted = true }
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    fun createSession(
        profileId: String,
        model: String = "",
        temporaryChat: Boolean = false,
        onReady: (sessionId: String?, error: String?) -> Unit
    ) {
        val sessionId = "gpt_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"

        if (profileId != "default" && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                ProfileStore.getInstance().getOrCreateProfile(profileId).cookieManager.flush()
            } catch (_: Exception) {}
        } else {
            CookieManager.getInstance().flush()
        }

        val displayMetrics = context.resources.displayMetrics
        val viewWidth = displayMetrics.widthPixels.coerceAtMost(720).coerceAtLeast(480)
        val viewHeight = displayMetrics.heightPixels.coerceAtMost(1280).coerceAtLeast(800)

        val sessionInstance = ActiveGptPuppetSession(
            sessionId = sessionId,
            profileId = profileId,
            model = model,
            webView = WebView(context)
        )

        val autoWv = sessionInstance.webView.apply {
            setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            translationX = 0f
            alpha = 1f
            layoutParams = ViewGroup.LayoutParams(viewWidth, viewHeight)
            measure(
                android.view.View.MeasureSpec.makeMeasureSpec(viewWidth, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(viewHeight, android.view.View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, viewWidth, viewHeight)

            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                try {
                    val origins = setOf("https://chatgpt.com")
                    WebViewCompat.addDocumentStartJavaScript(this, CHATGPT_HEADLESS_TURBO_SCRIPT, origins)
                    WebViewCompat.addDocumentStartJavaScript(this, CHATGPT_WIRE_INTERCEPTOR_SCRIPT, origins)
                    bridge.log("GPT_WIRE_SETUP", "Registered Turbo & Wire scripts at document-start for [$sessionId]")
                } catch (e: Exception) {
                    bridge.log("GPT_WIRE_SETUP_ERR", "addDocumentStartJavaScript failed for [$sessionId]: ${e.message}")
                }
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                useWideViewPort = true
                loadWithOverviewMode = true
                blockNetworkImage = true
                mediaPlaybackRequiresUserGesture = true
                setGeolocationEnabled(false)
                setSupportMultipleWindows(true)
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                val rawUA = userAgentString
                userAgentString = rawUA.replace("; wv", "").replace(Regex("Version/[0-9.]+ "), "")

                try {
                    val method = javaClass.getMethod("setRequestedWithHeaderOriginAllowList", Set::class.java)
                    method.invoke(this, emptySet<String>())
                } catch (_: Exception) {}
            }

            if (profileId != "default" && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                try {
                    val profile = ProfileStore.getInstance().getOrCreateProfile(profileId)
                    WebViewCompat.setProfile(this, profileId)
                    profile.cookieManager.setAcceptCookie(true)
                    profile.cookieManager.setAcceptThirdPartyCookies(this, true)
                } catch (_: Exception) {}
            } else {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(this, true)
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    val msg = consoleMessage?.message() ?: ""
                    if (msg.isEmpty() || msg.contains("violates the following Content Security Policy", ignoreCase = true)) {
                        return true
                    }
                    val lvl = consoleMessage?.messageLevel()?.name ?: "LOG"
                    bridge.log("CHATGPT_[$sessionId]", "[$lvl] $msg")
                    return true
                }
            }

            addJavascriptInterface(object {
                @JavascriptInterface
                fun onStatus(msg: String) {
                    bridge.updateForegroundTask("ChatGPT ($profileId): $msg")
                }

                @JavascriptInterface
                fun onLog(tag: String, msg: String) {
                    bridge.log("CHATGPT_${tag}[$sessionId]", msg)
                }

                @JavascriptInterface
                fun onPuppetReady(success: Boolean, message: String) {
                    mainHandler.post {
                        sessionInstance.onReadyCallback?.invoke(success, message)
                        sessionInstance.onReadyCallback = null
                        sessionInstance.onNewChatCallback?.invoke(success, message)
                        sessionInstance.onNewChatCallback = null
                    }
                }

                @JavascriptInterface
                fun onPuppetConfigured(success: Boolean, message: String) {
                    mainHandler.post {
                        sessionInstance.onConfigCallback?.invoke(success, message)
                        sessionInstance.onConfigCallback = null
                    }
                }

                @JavascriptInterface
                fun onPuppetNewChat(success: Boolean, message: String) {
                    mainHandler.post {
                        sessionInstance.onNewChatCallback?.invoke(success, message)
                        sessionInstance.onNewChatCallback = null
                    }
                }

                @JavascriptInterface
                fun onPuppetTurnProgress(thoughts: String, output: String) {
                    mainHandler.post {
                        sessionInstance.onTurnProgressCallback?.invoke(thoughts, output)
                    }
                }

                @JavascriptInterface
                fun onPuppetTurnDelta(thoughtsDelta: String, outputDelta: String, isFirst: Boolean) {
                    mainHandler.post {
                        sessionInstance.onTurnDeltaCallback?.invoke(thoughtsDelta, outputDelta, isFirst)
                    }
                }

                @JavascriptInterface
                fun onPuppetTurnComplete(thoughts: String, output: String) {
                    mainHandler.post {
                        sessionInstance.onTurnCompleteCallback?.invoke(thoughts, output)
                    }
                }

                @JavascriptInterface
                fun onPuppetTurnError(err: String) {
                    mainHandler.post {
                        sessionInstance.onTurnErrorCallback?.invoke(err)
                    }
                }
            }, "OmniGptAutomator")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val reqUri = request?.url ?: return super.shouldInterceptRequest(view, request)
                    val host = reqUri.host?.lowercase(Locale.US) ?: ""
                    val path = reqUri.path?.lowercase(Locale.US) ?: ""

                    val isJunkHost = host.contains("googletagmanager.com") ||
                        host.contains("google-analytics.com") ||
                        host.contains("doubleclick.net") ||
                        host.contains("datadoghq.com")

                    val isStaticMedia = path.endsWith(".woff2") ||
                        path.endsWith(".woff") ||
                        path.endsWith(".ttf") ||
                        path.endsWith(".mp4") ||
                        path.endsWith(".webm")

                    if (isJunkHost || isStaticMedia) {
                        return WebResourceResponse("text/plain", "UTF-8", 200, "OK", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    if (!request.isForMainFrame) {
                        val cachedResp = siteCacheManager.interceptRequest(request, "chatgpt.com", profileId)
                        if (cachedResp != null) return cachedResp
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.evaluateJavascript(BOT_BYPASS_POLYFILL, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null && url.contains("chatgpt.com")) {
                        bridge.log("CHATGPT_INJECT", "Injecting ChatGPT Puppet Core Engine into [$sessionId] ($url)")
                        val script = buildChatGptPuppetScript() + "\nwindow.__omniGptPuppet.init();"
                        view?.evaluateJavascript(script, null)
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (sessionInstance.isAborted) return
                    if (request?.isForMainFrame == true) {
                        mainHandler.post {
                            sessionInstance.onReadyCallback?.invoke(false, "Network error: ${error?.description}")
                            sessionInstance.onReadyCallback = null
                        }
                    }
                }
            }
        }

        sessionInstance.onReadyCallback = { success, msg ->
            if (success) {
                onReady(sessionId, null)
            } else {
                closeSession(sessionId)
                onReady(null, msg)
            }
        }

        sessions[sessionId] = sessionInstance
        bridge.log("CHATGPT_POOL", "Initialized ChatGPT Session [$sessionId] for Profile '$profileId' (Active: ${sessions.size})")

        val targetUrl = if (temporaryChat) "https://chatgpt.com/?temporary-chat=true" else "https://chatgpt.com/"
        val now = System.currentTimeMillis()
        val scheduledAt = nextBootSlotAtMs.updateAndGet { prev -> maxOf(now, prev + 900L) }
        val staggerDelayMs = (scheduledAt - now).coerceAtLeast(0L)

        bridge.startForegroundTask("ChatGPT Puppet", "Active puppet sessions: ${sessions.size}")
        if (staggerDelayMs > 0L) {
            mainHandler.postDelayed({
                if (!sessionInstance.isAborted && sessions.containsKey(sessionId)) {
                    autoWv.onResume()
                    autoWv.loadUrl(targetUrl)
                }
            }, staggerDelayMs)
        } else {
            autoWv.onResume()
            autoWv.loadUrl(targetUrl)
        }
    }

    fun configureSession(
        sessionId: String,
        model: String = "",
        temporaryChat: Boolean? = null,
        headlessTurbo: Boolean = true,
        onDone: (Boolean, String?) -> Unit
    ) {
        val session = sessions[sessionId]
        if (session == null) {
            onDone(false, "Session '$sessionId' is not active.")
            return
        }
        session.onConfigCallback = onDone

        val configObj = JSONObject().apply {
            put("model", model.ifEmpty { session.model })
            if (temporaryChat != null) put("temporaryChat", temporaryChat)
            put("headlessTurbo", headlessTurbo)
        }

        val script = "if (window.__omniGptPuppet) { window.__omniGptPuppet.configure(${configObj}); }"
        session.webView.evaluateJavascript(script, null)
    }

    fun newChatSession(
        sessionId: String,
        temporaryChat: Boolean? = null,
        onDone: (Boolean, String?) -> Unit
    ) {
        val session = sessions[sessionId]
        if (session == null) {
            onDone(false, "Session '$sessionId' is not active.")
            return
        }
        session.onNewChatCallback = onDone

        val opts = JSONObject().apply {
            if (temporaryChat != null) put("temporaryChat", temporaryChat)
        }
        val script = "if (window.__omniGptPuppet) { window.__omniGptPuppet.newChat(${opts}); }"
        session.webView.evaluateJavascript(script, null)
    }

    fun executeSessionTurn(
        sessionId: String,
        prompt: String,
        attachments: List<AutomationAttachment> = emptyList(),
        label: String = "Turn",
        onProgress: (thoughts: String, output: String) -> Unit,
        onComplete: (thoughts: String, output: String) -> Unit,
        onError: (String) -> Unit,
        onDelta: ((thoughtsDelta: String, outputDelta: String, isFirst: Boolean) -> Unit)? = null
    ) {
        val session = sessions[sessionId]
        if (session == null) {
            onError("Session '$sessionId' is not active.")
            return
        }

        session.onTurnProgressCallback = onProgress
        session.onTurnDeltaCallback = onDelta
        session.onTurnCompleteCallback = onComplete
        session.onTurnErrorCallback = onError

        val attachmentsArray = JSONArray()
        attachments.forEach { att ->
            attachmentsArray.put(
                JSONObject().apply {
                    put("name", att.name)
                    put("mime", att.mimeType)
                    put("data", att.base64Data)
                }
            )
        }

        val turnObj = JSONObject().apply {
            put("prompt", prompt.trim())
            put("attachments", attachmentsArray)
            put("label", label)
            put("deltaMode", onDelta != null)
        }

        val script = "if (window.__omniGptPuppet) { window.__omniGptPuppet.executeTurn(${turnObj}); }"
        session.webView.evaluateJavascript(script, null)
    }

    fun closeSession(sessionId: String) {
        val session = sessions.remove(sessionId) ?: return
        bridge.log("CHATGPT_POOL", "Closing Session [$sessionId]. Remaining: ${sessions.size}")
        try {
            session.webView.evaluateJavascript("if (window.__omniGptPuppet) { window.__omniGptPuppet.abort(); }", null)
            session.webView.stopLoading()
            session.webView.onPause()
            (session.webView.parent as? ViewGroup)?.removeView(session.webView)
            session.webView.destroy()
        } catch (_: Exception) {}

        if (defaultSessionId == sessionId) {
            defaultSessionId = null
        }
        if (sessions.isEmpty()) {
            bridge.stopForegroundTask()
        }
    }

    fun closeAllSessions() {
        sessions.keys.toList().forEach { closeSession(it) }
    }

    // --- Default Session Convenience Wrappers ---
    fun prepareSession(profileId: String, model: String = "", temporaryChat: Boolean = false, onReady: (Boolean, String?) -> Unit) {
        defaultSessionId?.let { closeSession(it) }
        createSession(profileId, model, temporaryChat) { sId, err ->
            if (sId != null) {
                defaultSessionId = sId
                onReady(true, null)
            } else {
                onReady(false, err)
            }
        }
    }

    fun configure(model: String = "", temporaryChat: Boolean? = null, headlessTurbo: Boolean = true, onDone: (Boolean, String?) -> Unit) {
        val sId = defaultSessionId ?: return onDone(false, "No active ChatGPT session.")
        configureSession(sId, model, temporaryChat, headlessTurbo, onDone)
    }

    fun newChat(temporaryChat: Boolean? = null, onDone: (Boolean, String?) -> Unit) {
        val sId = defaultSessionId ?: return onDone(false, "No active ChatGPT session.")
        newChatSession(sId, temporaryChat, onDone)
    }

    fun executeTurn(
        prompt: String,
        attachments: List<AutomationAttachment> = emptyList(),
        label: String = "Turn",
        onProgress: (thoughts: String, output: String) -> Unit,
        onComplete: (thoughts: String, output: String) -> Unit,
        onError: (String) -> Unit,
        onDelta: ((thoughtsDelta: String, outputDelta: String, isFirst: Boolean) -> Unit)? = null
    ) {
        val sId = defaultSessionId ?: return onError("No active ChatGPT session.")
        executeSessionTurn(sId, prompt, attachments, label, onProgress, onComplete, onError, onDelta)
    }
}