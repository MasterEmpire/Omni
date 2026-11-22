package com.omni.plugin.browser.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.omni.hub.api.HostBridge
import com.omni.plugin.browser.utils.AI_STUDIO_HEADLESS_TURBO_SCRIPT
import com.omni.plugin.browser.utils.AI_STUDIO_WIRE_INTERCEPTOR_SCRIPT
import com.omni.plugin.browser.utils.BOT_BYPASS_POLYFILL
import com.omni.plugin.browser.utils.DOM_SNAPSHOT_SCRIPT
import com.omni.plugin.browser.engine.buildAiStudioPuppetScript
import com.omni.plugin.browser.engine.buildAiStudioAutomationScript
import org.json.JSONObject

interface AutomationCallback {
    fun onStatus(msg: String)
    fun onProgress(thoughts: String, output: String)
    fun onComplete(thoughts: String, output: String)
    fun onError(err: String)
}

data class ActivePuppetSession(
    val sessionId: String,
    val profileId: String,
    val model: String,
    val webView: WebView,
    @Volatile var currentUrl: String = "",
    var onReadyCallback: ((Boolean, String?) -> Unit)? = null,
    var onConfigCallback: ((Boolean, String?) -> Unit)? = null,
    var onNewChatCallback: ((Boolean, String?) -> Unit)? = null,
    var onTurnProgressCallback: ((String, String) -> Unit)? = null,
    var onTurnDeltaCallback: ((String, String, Boolean) -> Unit)? = null,
    var onTurnCompleteCallback: ((String, String) -> Unit)? = null,
    var onTurnCompleteMetaCallback: ((String, String, Int, Boolean) -> Unit)? = null,
    var onTurnCompleteTelemetryCallback: ((String, String, Int, Boolean, String) -> Unit)? = null,
    var onTurnErrorCallback: ((String) -> Unit)? = null,
    var isAborted: Boolean = false
)

/**
 * High-Level Multi-Session Puppet Pool for Google AI Studio.
 * Allows firing concurrent headless puppet sessions across multiple profiles with zero throttle cap.
 */
class AiStudioAutomator(
    private val context: Context,
    private val bridge: HostBridge
) {
    companion object {
        data class HeadlessWebViewRecord(
            val webView: WebView,
            val sessionId: String,
            val profileId: String,
            val automatorRef: java.lang.ref.WeakReference<AiStudioAutomator>,
            val createdAtMs: Long = System.currentTimeMillis(),
            @Volatile var lastActiveAtMs: Long = System.currentTimeMillis(),
            @Volatile var isDead: Boolean = false
        )

        val liveHeadlessWebViews = java.util.concurrent.CopyOnWriteArraySet<WebView>()
        val headlessRegistry = java.util.concurrent.ConcurrentHashMap<WebView, HeadlessWebViewRecord>()

        @Volatile
        var onLivePoolChanged: (() -> Unit)? = null

        fun touchHeadlessWebView(wv: WebView?) {
            if (wv == null) return
            headlessRegistry[wv]?.lastActiveAtMs = System.currentTimeMillis()
        }

        fun destroyWebViewSafely(wv: WebView) {
            liveHeadlessWebViews.remove(wv)
            headlessRegistry.remove(wv)
            try { wv.evaluateJavascript("if (window.__omniPuppet) { window.__omniPuppet.abort(); }", null) } catch (_: Exception) {}
            try { wv.removeJavascriptInterface("OmniAutomator") } catch (_: Exception) {}
            try { wv.removeJavascriptInterface("OmniAnalyticsBridge") } catch (_: Exception) {}
            try { wv.stopLoading() } catch (_: Exception) {}
            try { wv.onPause() } catch (_: Exception) {}
            try { (wv.parent as? ViewGroup)?.removeView(wv) } catch (_: Exception) {}
            try { wv.loadUrl("about:blank") } catch (_: Exception) {}
            try { wv.clearHistory() } catch (_: Exception) {}
            try { wv.destroy() } catch (_: Exception) {}
        }

        fun purgeOrphanedAndGhostWebViews(
            bridge: HostBridge? = null,
            reason: String = "audit"
        ): Int {
            val toBust = mutableListOf<Pair<WebView, String>>()

            for (wv in liveHeadlessWebViews) {
                val rec = headlessRegistry[wv]
                if (rec == null) {
                    toBust.add(wv to "untracked_orphan")
                    continue
                }
                val owner = rec.automatorRef.get()
                val ownerSession = owner?.sessions?.get(rec.sessionId)
                val why = when {
                    rec.isDead -> "renderer_dead"
                    owner == null -> "owner_gc_orphaned"
                    ownerSession == null -> "session_missing"
                    ownerSession.isAborted -> "session_aborted"
                    else -> null
                }
                if (why != null) {
                    toBust.add(wv to why)
                }
            }

            for ((wv, _) in headlessRegistry) {
                if (!liveHeadlessWebViews.contains(wv)) {
                    toBust.add(wv to "dangling_registry")
                }
            }

            var bustedCount = 0
            for ((wv, why) in toBust.distinctBy { it.first }) {
                val rec = headlessRegistry[wv]
                if (rec != null) {
                    rec.automatorRef.get()?.sessions?.remove(rec.sessionId)
                }
                destroyWebViewSafely(wv)
                bustedCount++
                bridge?.log("GHOST_BUSTER", "👻💥 Busted ghost WebView [${rec?.sessionId ?: "unknown"}] (profile=${rec?.profileId ?: "?"}, reason=$why, trigger=$reason)")
            }

            if (bustedCount > 0) {
                onLivePoolChanged?.invoke()
            }
            return bustedCount
        }
    }
    val sessions = java.util.concurrent.ConcurrentHashMap<String, ActivePuppetSession>()
    private var defaultSessionId: String? = null

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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextBootSlotAtMs = java.util.concurrent.atomic.AtomicLong(0L)
    private var legacyBatchCallback: AutomationCallback? = null
    private val siteCacheManager = com.omni.plugin.browser.services.SiteCacheManager(context, bridge)

    private fun normalizeModelSlug(rawModel: String): String {
        val trimmed = rawModel.trim()
        if (trimmed.isEmpty() || trimmed.equals("Default", ignoreCase = true)) return ""
        return trimmed.lowercase(java.util.Locale.US)
            .replace(" ", "-")
            .replace("_", "-")
    }
    fun getSessionUrl(sessionId: String): String {
        val session = sessions[sessionId] ?: return ""
        val wvUrl = try { session.webView.url ?: "" } catch (_: Exception) { "" }
        return when {
            wvUrl.contains("/prompts/") && !wvUrl.contains("/prompts/new_chat") -> wvUrl
            session.currentUrl.contains("/prompts/") && !session.currentUrl.contains("/prompts/new_chat") -> session.currentUrl
            wvUrl.isNotBlank() && wvUrl != "about:blank" -> wvUrl
            else -> session.currentUrl
        }
    }

    // --- MULTI-SESSION FACTORY: Spawn Independent Profile Session ---
    @SuppressLint("SetJavaScriptEnabled")
    fun createSession(
        profileId: String,
        model: String = "",
        temporaryChat: Boolean? = null,
        onReady: (sessionId: String?, error: String?) -> Unit
    ) {
        purgeOrphanedAndGhostWebViews(bridge, reason = "pre_spawn")
        val sessionId = "session_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(6)}"

        if (profileId != "default" && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                val profileStore = ProfileStore.getInstance()
                profileStore.getOrCreateProfile(profileId).cookieManager.flush()
            } catch (_: Exception) {}
        } else {
            CookieManager.getInstance().flush()
        }

        val displayMetrics = context.resources.displayMetrics
        val viewWidth = displayMetrics.widthPixels.coerceAtLeast(412)
        val viewHeight = displayMetrics.heightPixels.coerceAtLeast(800)

        val sessionInstance = ActivePuppetSession(
            sessionId = sessionId,
            profileId = profileId,
            model = model,
            webView = WebView(context.applicationContext)
        )

        val autoWv = sessionInstance.webView.apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                } catch (_: Exception) {}
            }
            liveHeadlessWebViews.add(this)
            headlessRegistry[this] = HeadlessWebViewRecord(
                webView = this,
                sessionId = sessionId,
                profileId = profileId,
                automatorRef = java.lang.ref.WeakReference(this@AiStudioAutomator)
            )
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
                    WebViewCompat.addDocumentStartJavaScript(this, AI_STUDIO_HEADLESS_TURBO_SCRIPT, setOf("https://aistudio.google.com"))
                    WebViewCompat.addDocumentStartJavaScript(this, AI_STUDIO_WIRE_INTERCEPTOR_SCRIPT, setOf("https://aistudio.google.com"))
                    bridge.log("WIRE_SETUP", "Registered Turbo & Wire scripts at document-start for [$sessionId]")
                } catch (e: Exception) {
                    bridge.log("WIRE_SETUP_ERR", "addDocumentStartJavaScript failed for [$sessionId]: ${e.message}")
                }
            } else {
                bridge.log("WIRE_SETUP_WARN", "DOCUMENT_START_SCRIPT not supported on this WebView")
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                useWideViewPort = true
                loadWithOverviewMode = true
                blockNetworkImage = false
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
                    val profileStore = ProfileStore.getInstance()
                    val profile = profileStore.getOrCreateProfile(profileId)
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
                    if (msg.isEmpty() ||
                        msg.contains("preloaded using link preload", ignoreCase = true) ||
                        msg.contains("Failed to upload to drive", ignoreCase = true) ||
                        msg.contains("GapiService.upload failed", ignoreCase = true) ||
                        msg.contains("violates the following Content Security Policy directive", ignoreCase = true) ||
                        msg.contains("Unknown Key:", ignoreCase = true) ||
                        msg.contains("NG0953", ignoreCase = true)
                    ) return true

                    val lvl = consoleMessage?.messageLevel()?.name ?: "LOG"
                    val line = consoleMessage?.lineNumber() ?: 0
                    val src = consoleMessage?.sourceId() ?: ""
                    bridge.log("AI_STUDIO_[$sessionId]", "[$lvl] $msg (line $line in $src)")
                    return true
                }
            }

            addJavascriptInterface(object {
                @JavascriptInterface
                fun onStatus(msg: String) {
                    touchHeadlessWebView(sessionInstance.webView)
                    bridge.updateForegroundTask("AI Studio ($profileId): $msg")
                    mainHandler.post { legacyBatchCallback?.onStatus(msg) }
                }

                @JavascriptInterface
                fun onLog(tag: String, msg: String) {
                    touchHeadlessWebView(sessionInstance.webView)
                    bridge.log("AI_STUDIO_${tag}[$sessionId]", msg)
                }

                @JavascriptInterface
                fun onProgress(thoughts: String, output: String) {
                    mainHandler.post { legacyBatchCallback?.onProgress(thoughts, output) }
                }

                @JavascriptInterface
                fun onComplete(thoughts: String, output: String) {
                    mainHandler.post { legacyBatchCallback?.onComplete(thoughts, output) }
                }

                @JavascriptInterface
                fun onError(err: String) {
                    if (sessionInstance.isAborted) return
                    mainHandler.post { legacyBatchCallback?.onError(err) }
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
                    touchHeadlessWebView(sessionInstance.webView)
                    mainHandler.post {
                        val cb = sessionInstance.onTurnProgressCallback
                        if (cb != null) {
                            cb.invoke(thoughts, output)
                        } else {
                            legacyBatchCallback?.onProgress(thoughts, output)
                        }
                    }
                }

                @JavascriptInterface
                fun onPuppetTurnDelta(thoughtsDelta: String, outputDelta: String, isFirst: Boolean) {
                    touchHeadlessWebView(sessionInstance.webView)
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
                fun onPuppetTurnCompleteWithMeta(thoughts: String, output: String, finishReason: Int, truncated: Boolean) {
                    mainHandler.post {
                        val metaCb = sessionInstance.onTurnCompleteMetaCallback
                        if (metaCb != null) {
                            metaCb.invoke(thoughts, output, finishReason, truncated)
                        } else {
                            sessionInstance.onTurnCompleteCallback?.invoke(thoughts, output)
                        }
                    }
                }
                @JavascriptInterface
                fun onPuppetTurnCompleteDetailed(thoughts: String, output: String, finishReason: Int, truncated: Boolean, telemetryJson: String) {
                    mainHandler.post {
                        val telCb = sessionInstance.onTurnCompleteTelemetryCallback
                        if (telCb != null) {
                            telCb.invoke(thoughts, output, finishReason, truncated, telemetryJson)
                        } else {
                            val metaCb = sessionInstance.onTurnCompleteMetaCallback
                            if (metaCb != null) {
                                metaCb.invoke(thoughts, output, finishReason, truncated)
                            } else {
                                sessionInstance.onTurnCompleteCallback?.invoke(thoughts, output)
                            }
                        }
                    }
                }

                @JavascriptInterface
                fun onPuppetTurnError(err: String) {
                    mainHandler.post {
                        sessionInstance.onTurnErrorCallback?.invoke(err)
                    }
                }
                @JavascriptInterface
                fun onTurnUrl(url: String) {
                    if (url.isNotBlank()) {
                        sessionInstance.currentUrl = url
                    }
                }
            }, "OmniAutomator")
            addJavascriptInterface(
                com.omni.plugin.browser.models.OmniAnalyticsBridge(
                    onPromptSent = { pId ->
                        com.omni.plugin.browser.storage.VaultManager.recordPrompt(
                            context, bridge, pId.ifEmpty { profileId }, isHeadless = true
                        )
                    },
                    onQuotaHit = { pId, reason ->
                        com.omni.plugin.browser.storage.VaultManager.recordQuotaHit(
                            context, bridge, pId.ifEmpty { profileId }, reason
                        )
                    },
                    onLog = { tag, msg -> bridge.log(tag, msg) },
                    onVerificationLock = { pId, reason ->
                        com.omni.plugin.browser.storage.VaultManager.recordVerificationLock(
                            context, bridge, pId.ifEmpty { profileId }, reason
                        )
                    }
                ),
                "OmniAnalyticsBridge"
            )

            webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    bridge.log("AI_STUDIO_RENDER_CRASH", "💀 Headless renderer died for [$sessionId] (didCrash=${detail?.didCrash()})")
                    if (view != null) {
                        headlessRegistry[view]?.isDead = true
                    }
                    mainHandler.post {
                        val crashMsg = "AI Studio headless renderer crashed (OOM)"
                        sessionInstance.onReadyCallback?.invoke(false, crashMsg)
                        sessionInstance.onReadyCallback = null
                        sessionInstance.onConfigCallback?.invoke(false, crashMsg)
                        sessionInstance.onConfigCallback = null
                        sessionInstance.onNewChatCallback?.invoke(false, crashMsg)
                        sessionInstance.onNewChatCallback = null
                        sessionInstance.onTurnErrorCallback?.invoke(crashMsg)
                        sessionInstance.onTurnErrorCallback = null
                        closeSession(sessionId)
                    }
                    return true
                }
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val reqUri = request?.url ?: return super.shouldInterceptRequest(view, request)
                    val urlStr = reqUri.toString().lowercase(java.util.Locale.US)
                    val host = reqUri.host?.lowercase(java.util.Locale.US) ?: ""
                    val path = reqUri.path?.lowercase(java.util.Locale.US) ?: ""

                    val isHeavyMedia = path.endsWith(".mp4") ||
                        path.endsWith(".webm") ||
                        path.endsWith(".mp3") ||
                        path.endsWith(".wav")

                    if (isHeavyMedia) {
                        val origin = request.requestHeaders?.get("Origin") ?: "https://aistudio.google.com"
                        val corsHeaders = mapOf(
                            "Access-Control-Allow-Origin" to origin,
                            "Access-Control-Allow-Credentials" to "true",
                            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                            "Access-Control-Allow-Headers" to "*"
                        )
                        return WebResourceResponse("text/plain", "UTF-8", 200, "OK", corsHeaders, java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    if (!request.isForMainFrame) {
                        val cachedResp = siteCacheManager.interceptRequest(request, "aistudio.google.com", profileId)
                        if (cachedResp != null) return cachedResp
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    if (!url.isNullOrBlank() && url != "about:blank") {
                        sessionInstance.currentUrl = url
                    }
                }
override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.evaluateJavascript("window.__omniProfileId = ${JSONObject.quote(profileId)};", null)
                    view?.evaluateJavascript(BOT_BYPASS_POLYFILL, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null && url.contains("aistudio.google.com")) {
                        view?.evaluateJavascript("window.__omniProfileId = ${JSONObject.quote(profileId)};", null)
                        bridge.log("AI_STUDIO_INJECT", "Injecting Puppet Core Engine into [$sessionId] ($url)")
                        val script = buildAiStudioPuppetScript() + "\nwindow.__omniPuppet.init();"
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
        bridge.log("AI_STUDIO_POOL", "🚀 Initialized Session [$sessionId] for Profile '$profileId' (Active Sessions: ${sessions.size})")
        mainHandler.post { onLivePoolChanged?.invoke() }

        val slug = normalizeModelSlug(model)
        val queryParts = mutableListOf<String>()
        if (slug.isNotEmpty()) queryParts.add("model=$slug")
        if (temporaryChat != null) queryParts.add("temporary=$temporaryChat")
        val targetUrl = if (queryParts.isNotEmpty()) {
            "https://aistudio.google.com/prompts/new_chat?" + queryParts.joinToString("&")
        } else {
            "https://aistudio.google.com/prompts/new_chat"
        }

        val now = System.currentTimeMillis()
        val jitterMs = (1600L..3200L).random()
        val scheduledAt = nextBootSlotAtMs.updateAndGet { prev -> maxOf(now, prev + jitterMs) }
        val staggerDelayMs = (scheduledAt - now).coerceAtLeast(0L)

        bridge.startForegroundTask("AI Studio Puppet", "Active puppet sessions: ${sessions.size}")
        if (staggerDelayMs > 0L) {
            bridge.log("AI_STUDIO_POOL", "⏱️ Staggering Session [$sessionId] boot by ${staggerDelayMs}ms to protect RAM/CPU")
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

    fun newChatSession(
        sessionId: String,
        onDone: (Boolean, String?) -> Unit
    ) {
        val session = sessions[sessionId]
        if (session == null) {
            onDone(false, "Session '$sessionId' is not active.")
            return
        }
        touchHeadlessWebView(session.webView)
        session.onNewChatCallback = onDone
        session.webView.evaluateJavascript("if (window.__omniPuppet && window.__omniPuppet.newChat) { window.__omniPuppet.newChat(); }", null)
    }

    fun configureSession(
        sessionId: String,
        sysTitle: String = "",
        sysPrompt: String = "",
        thinkingLevel: String = "Default",
        model: String = "",
        fallbackEnabled: Boolean = true,
        temporaryChat: Boolean = false,
        headlessTurbo: Boolean = true,
        onDone: (Boolean, String?) -> Unit
    ) {
        val session = sessions[sessionId]
        if (session == null) {
            onDone(false, "Session '$sessionId' is not active.")
            return
        }
        touchHeadlessWebView(session.webView)
        session.onConfigCallback = onDone

        val configObj = JSONObject().apply {
            put("sysTitle", sysTitle.trim())
            put("sysPrompt", sysPrompt.trim())
            put("thinkingLevel", thinkingLevel)
            put("model", model.ifEmpty { session.model })
            put("fallbackEnabled", fallbackEnabled)
            put("temporaryChat", temporaryChat)
            put("headlessTurbo", headlessTurbo)
        }

        val script = "if (window.__omniPuppet) { window.__omniPuppet.configure(${configObj}); }"
        session.webView.evaluateJavascript(script, null)
    }

    fun executeSessionTurn(
        sessionId: String,
        prompt: String,
        attachments: List<com.omni.plugin.browser.models.AutomationAttachment> = emptyList(),
        label: String = "Turn",
        onProgress: (thoughts: String, output: String) -> Unit,
        onComplete: (thoughts: String, output: String) -> Unit,
        onError: (String) -> Unit,
        onDelta: ((thoughtsDelta: String, outputDelta: String, isFirst: Boolean) -> Unit)? = null,
        onCompleteMeta: ((thoughts: String, output: String, finishReason: Int, truncated: Boolean) -> Unit)? = null,
        onCompleteTelemetry: ((thoughts: String, output: String, finishReason: Int, truncated: Boolean, telemetryJson: String) -> Unit)? = null
    ) {
        val session = sessions[sessionId]
        if (session == null) {
            onError("Session '$sessionId' is not active.")
            return
        }
        touchHeadlessWebView(session.webView)
        session.onTurnProgressCallback = onProgress
        session.onTurnDeltaCallback = onDelta
        session.onTurnCompleteCallback = onComplete
        session.onTurnCompleteMetaCallback = onCompleteMeta
        session.onTurnCompleteTelemetryCallback = onCompleteTelemetry
        session.onTurnErrorCallback = onError

        val attachmentsArray = org.json.JSONArray()
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

        val script = "if (window.__omniPuppet) { window.__omniPuppet.executeTurn(${turnObj}); }"
        session.webView.evaluateJavascript(script, null)
    }

    fun closeSession(sessionId: String) {
        val session = sessions.remove(sessionId) ?: return
        session.isAborted = true
        bridge.log("AI_STUDIO_POOL", "🛑 Closing Session [$sessionId]. Remaining: ${sessions.size}")
        destroyWebViewSafely(session.webView)
        mainHandler.post { onLivePoolChanged?.invoke() }

        if (defaultSessionId == sessionId) {
            defaultSessionId = null
        }
        if (sessions.isEmpty()) {
            bridge.stopForegroundTask()
        }
    }

    fun closeAllSessions() {
        val allIds = sessions.keys.toList()
        allIds.forEach { closeSession(it) }
    }

    // --- Backward-Compatible Default Session Wrappers ---
    fun prepareSession(
        profileId: String,
        model: String = "",
        temporaryChat: Boolean? = null,
        onReady: (Boolean, String?) -> Unit
    ) {
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

    fun configure(
        sysTitle: String = "",
        sysPrompt: String = "",
        thinkingLevel: String = "Default",
        model: String = "Gemini 3.7 Flash",
        fallbackEnabled: Boolean = true,
        temporaryChat: Boolean = false,
        onDone: (Boolean, String?) -> Unit
    ) {
        val sId = defaultSessionId
        if (sId == null) {
            onDone(false, "No active session.")
            return
        }
        configureSession(
            sessionId = sId,
            sysTitle = sysTitle,
            sysPrompt = sysPrompt,
            thinkingLevel = thinkingLevel,
            model = model,
            fallbackEnabled = fallbackEnabled,
            temporaryChat = temporaryChat,
            onDone = onDone
        )
    }

    fun executeTurn(
        prompt: String,
        attachments: List<com.omni.plugin.browser.models.AutomationAttachment> = emptyList(),
        label: String = "Turn",
        onProgress: (thoughts: String, output: String) -> Unit,
        onComplete: (thoughts: String, output: String) -> Unit,
        onError: (String) -> Unit
    ) {
        val sId = defaultSessionId
        if (sId == null) {
            onError("No active session.")
            return
        }
        executeSessionTurn(sId, prompt, attachments, label, onProgress, onComplete, onError)
    }

    // --- High-Level Backward Compatibility Orchestrator (Used by Exam Solver & Dialog) ---
    fun start(
        profileId: String,
        steps: List<com.omni.plugin.browser.models.SequentialPromptStep> = emptyList(),
        userPrompt: String = "",
        systemPromptTitle: String = "",
        systemPrompt: String = "",
        thinkingLevel: String = "Default",
        model: String = "Gemini 3.7 Flash",
        fallbackEnabled: Boolean = true,
        temporaryChat: Boolean = false,
        attachments: List<com.omni.plugin.browser.models.AutomationAttachment> = emptyList(),
        containerLayout: FrameLayout?,
        callback: AutomationCallback
    ) {
        legacyBatchCallback = callback

        val effectivePrompt = if (steps.isNotEmpty()) steps.first().prompt else userPrompt
        val jsEscapedPrompt = JSONObject.quote(effectivePrompt.trim())
        val jsEscapedSysTitle = JSONObject.quote(systemPromptTitle.trim())
        val jsEscapedSysPrompt = JSONObject.quote(systemPrompt.trim())
        val jsEscapedThinking = JSONObject.quote(thinkingLevel)
        val jsEscapedModel = JSONObject.quote(model)

        val attachmentsArray = org.json.JSONArray()
        attachments.forEach { att ->
            attachmentsArray.put(
                JSONObject().apply {
                    put("name", att.name)
                    put("mime", att.mimeType)
                    put("data", att.base64Data)
                }
            )
        }
        val jsAttachments = attachmentsArray.toString()

        val stepsArray = org.json.JSONArray()
        val targetSteps = if (steps.isNotEmpty()) steps else listOf(com.omni.plugin.browser.models.SequentialPromptStep(prompt = userPrompt))
        targetSteps.forEach { step ->
            val stepAttArray = org.json.JSONArray()
            step.attachments.forEach { att ->
                stepAttArray.put(
                    JSONObject().apply {
                        put("name", att.name)
                        put("mime", att.mimeType)
                        put("data", att.base64Data)
                    }
                )
            }
            stepsArray.put(
                JSONObject().apply {
                    put("id", step.id)
                    put("prompt", step.prompt.trim())
                    put("repeatCount", step.repeatCount.coerceAtLeast(1))
                    put("isInfinite", step.isInfinite)
                    put("attachments", stepAttArray)
                }
            )
        }
        val jsSteps = stepsArray.toString()

        val automationScript = buildAiStudioAutomationScript(
            prompt = jsEscapedPrompt,
            sysTitle = jsEscapedSysTitle,
            sysPrompt = jsEscapedSysPrompt,
            thinkingLevel = jsEscapedThinking,
            model = jsEscapedModel,
            fallbackEnabled = fallbackEnabled,
            temporaryChat = temporaryChat,
            attachmentsJson = jsAttachments,
            stepsJson = jsSteps
        )

        createSession(profileId, model) { sId, err ->
            if (sId == null) {
                callback.onError(err ?: "Failed preparing session")
                return@createSession
            }
            defaultSessionId = sId
            val wv = sessions[sId]?.webView
            if (containerLayout != null && wv != null) {
                wv.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                containerLayout.removeAllViews()
                containerLayout.addView(wv)
            }
            wv?.evaluateJavascript(automationScript, null)
        }
    }

    fun stop(containerLayout: FrameLayout?) {
        isAborted = true
        bridge.stopForegroundTask()
        containerLayout?.removeAllViews()
        closeAllSessions()
        legacyBatchCallback = null
    }

    fun dumpDom(onResult: (String?) -> Unit) {
        headlessWv?.evaluateJavascript(DOM_SNAPSHOT_SCRIPT) { html ->
            if (!html.isNullOrEmpty() && html != "null") {
                val rawHtml = if (html.startsWith("\"") && html.endsWith("\"")) {
                    try {
                        JSONObject("{\"h\":$html}").getString("h")
                    } catch (_: Exception) { html }
                } else html
                onResult(rawHtml)
            } else {
                onResult(null)
            }
        } ?: onResult(null)
    }
}