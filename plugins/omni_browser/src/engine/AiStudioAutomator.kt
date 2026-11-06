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
    var onReadyCallback: ((Boolean, String?) -> Unit)? = null,
    var onConfigCallback: ((Boolean, String?) -> Unit)? = null,
    var onTurnProgressCallback: ((String, String) -> Unit)? = null,
    var onTurnCompleteCallback: ((String, String) -> Unit)? = null,
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
    private var legacyBatchCallback: AutomationCallback? = null

    private fun normalizeModelSlug(rawModel: String): String {
        val trimmed = rawModel.trim()
        if (trimmed.isEmpty() || trimmed.equals("Default", ignoreCase = true)) return ""
        return trimmed.lowercase(java.util.Locale.US)
            .replace(" ", "-")
            .replace("_", "-")
    }

    // --- MULTI-SESSION FACTORY: Spawn Independent Profile Session ---
    @SuppressLint("SetJavaScriptEnabled")
    fun createSession(
        profileId: String,
        model: String = "",
        onReady: (sessionId: String?, error: String?) -> Unit
    ) {
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
        val viewWidth = displayMetrics.widthPixels.coerceAtLeast(1080)
        val viewHeight = displayMetrics.heightPixels.coerceAtLeast(2400)

        val sessionInstance = ActivePuppetSession(
            sessionId = sessionId,
            profileId = profileId,
            model = model,
            webView = WebView(context)
        )

        val autoWv = sessionInstance.webView.apply {
            translationX = 0f
            alpha = 1f
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            measure(
                android.view.View.MeasureSpec.makeMeasureSpec(viewWidth, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(viewHeight, android.view.View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, viewWidth, viewHeight)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                useWideViewPort = true
                loadWithOverviewMode = true
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
                        msg.contains("GapiService.upload failed", ignoreCase = true)
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
                    bridge.updateForegroundTask("AI Studio ($profileId): $msg")
                    mainHandler.post { legacyBatchCallback?.onStatus(msg) }
                }

                @JavascriptInterface
                fun onLog(tag: String, msg: String) {
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
                fun onPuppetTurnProgress(thoughts: String, output: String) {
                    mainHandler.post {
                        sessionInstance.onTurnProgressCallback?.invoke(thoughts, output)
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
            }, "OmniAutomator")

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.evaluateJavascript(BOT_BYPASS_POLYFILL, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null && url.contains("aistudio.google.com")) {
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

        val slug = normalizeModelSlug(model)
        val targetUrl = if (slug.isNotEmpty()) {
            "https://aistudio.google.com/prompts/new_chat?model=$slug"
        } else {
            "https://aistudio.google.com/prompts/new_chat"
        }

        autoWv.onResume()
        bridge.startForegroundTask("AI Studio Puppet", "Active puppet sessions: ${sessions.size}")
        autoWv.loadUrl(targetUrl)
    }

    fun configureSession(
        sessionId: String,
        sysTitle: String = "",
        sysPrompt: String = "",
        thinkingLevel: String = "Default",
        model: String = "",
        fallbackEnabled: Boolean = true,
        temporaryChat: Boolean = false,
        onDone: (Boolean, String?) -> Unit
    ) {
        val session = sessions[sessionId]
        if (session == null) {
            onDone(false, "Session '$sessionId' is not active.")
            return
        }
        session.onConfigCallback = onDone

        val configObj = JSONObject().apply {
            put("sysTitle", sysTitle.trim())
            put("sysPrompt", sysPrompt.trim())
            put("thinkingLevel", thinkingLevel)
            put("model", model.ifEmpty { session.model })
            put("fallbackEnabled", fallbackEnabled)
            put("temporaryChat", temporaryChat)
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
        onError: (String) -> Unit
    ) {
        val session = sessions[sessionId]
        if (session == null) {
            onError("Session '$sessionId' is not active.")
            return
        }

        session.onTurnProgressCallback = onProgress
        session.onTurnCompleteCallback = onComplete
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
        }

        val script = "if (window.__omniPuppet) { window.__omniPuppet.executeTurn(${turnObj}); }"
        session.webView.evaluateJavascript(script, null)
    }

    fun closeSession(sessionId: String) {
        val session = sessions.remove(sessionId) ?: return
        bridge.log("AI_STUDIO_POOL", "🛑 Closing Session [$sessionId]. Remaining: ${sessions.size}")
        try {
            session.webView.evaluateJavascript("if (window.__omniPuppet) { window.__omniPuppet.abort(); }", null)
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
        val allIds = sessions.keys.toList()
        allIds.forEach { closeSession(it) }
    }

    // --- Backward-Compatible Default Session Wrappers ---
    fun prepareSession(
        profileId: String,
        onReady: (Boolean, String?) -> Unit
    ) {
        defaultSessionId?.let { closeSession(it) }
        createSession(profileId) { sId, err ->
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
        configureSession(sId, sysTitle, sysPrompt, thinkingLevel, model, fallbackEnabled, temporaryChat, onDone)
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