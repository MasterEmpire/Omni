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
import com.omni.plugin.browser.utils.buildAiStudioAutomationScript
import org.json.JSONObject

interface AutomationCallback {
    fun onStatus(msg: String)
    fun onProgress(thoughts: String, output: String)
    fun onComplete(thoughts: String, output: String)
    fun onError(err: String)
}

class AiStudioAutomator(
    private val context: Context,
    private val bridge: HostBridge
) {
    var headlessWv: WebView? = null
        private set

    @SuppressLint("SetJavaScriptEnabled")
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
        stop(containerLayout)

        if (profileId != "default" && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                val profileStore = ProfileStore.getInstance()
                profileStore.getOrCreateProfile(profileId).cookieManager.flush()
            } catch (_: Exception) {}
        } else {
            CookieManager.getInstance().flush()
        }

        val mainHandler = Handler(Looper.getMainLooper())
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

        bridge.startForegroundTask("AI Studio Automator", "Running automated AI Studio prompt sequence...")

        val displayMetrics = context.resources.displayMetrics
        val viewWidth = displayMetrics.widthPixels.coerceAtLeast(1080)
        val viewHeight = displayMetrics.heightPixels.coerceAtLeast(2400)

        val autoWv = WebView(context).apply {
            translationX = 0f
            alpha = 1f

            measure(
                android.view.View.MeasureSpec.makeMeasureSpec(viewWidth, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(viewHeight, android.view.View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, viewWidth, viewHeight)
            bridge.log("AI_STUDIO_VIEWPORT", "📐 Headless phone viewport forced to: ${viewWidth}x${viewHeight} px")

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
                    bridge.updateForegroundTask("AI Studio: $msg")
                    mainHandler.post { callback.onStatus(msg) }
                }

                @JavascriptInterface
                fun onLog(tag: String, msg: String) {
                    bridge.log("AI_STUDIO_$tag", msg)
                }

                @JavascriptInterface
                fun onProgress(thoughts: String, output: String) {
                    mainHandler.post { callback.onProgress(thoughts, output) }
                }

                @JavascriptInterface
                fun onComplete(thoughts: String, output: String) {
                    bridge.stopForegroundTask()
                    mainHandler.post { callback.onComplete(thoughts, output) }
                }

                @JavascriptInterface
                fun onError(err: String) {
                    bridge.stopForegroundTask()
                    mainHandler.post { callback.onError(err) }
                }
            }, "OmniAutomator")

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.layout(0, 0, viewWidth, viewHeight)
                    bridge.log("AI_STUDIO_NAV", "Started loading: $url (Viewport: ${view?.width}x${view?.height})")
                    view?.evaluateJavascript(BOT_BYPASS_POLYFILL, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.layout(0, 0, viewWidth, viewHeight)
                    bridge.log("AI_STUDIO_NAV", "Finished loading: $url (Viewport: ${view?.width}x${view?.height})")
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
                            callback.onError("Network error connecting to AI Studio (${error?.description})")
                        }
                    }
                }
            }
        }

        headlessWv = autoWv
        containerLayout?.addView(
            autoWv,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        autoWv.onResume()
        autoWv.loadUrl("https://aistudio.google.com/prompts/new_chat")
    }

    fun stop(containerLayout: FrameLayout?) {
        bridge.stopForegroundTask()
        try {
            headlessWv?.stopLoading()
            headlessWv?.onPause()
            (headlessWv?.parent as? ViewGroup)?.removeView(headlessWv)
            containerLayout?.removeView(headlessWv)
            headlessWv?.destroy()
        } catch (_: Exception) {}
        headlessWv = null
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