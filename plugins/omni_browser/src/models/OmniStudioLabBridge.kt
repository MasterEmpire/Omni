package com.omni.plugin.browser.models

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.omni.hub.api.HostBridge
import com.omni.plugin.browser.engine.AiStudioAutomator
import com.omni.plugin.browser.engine.ChatGptAutomator
import com.omni.plugin.browser.engine.GeminiAutomator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Android-to-JavaScript RPC Bridge powering Omni Studio Lab Playbooks.
 * Maps high-level async JavaScript calls to AI Studio Automator, Nexus Python,
 * Conduit IDE file pulling/patching, and filesystem operations.
 */
class OmniStudioLabBridge(
    private val context: Context,
    private val bridge: HostBridge,
    private val automator: AiStudioAutomator,
    private val chatGptAutomator: ChatGptAutomator = ChatGptAutomator(context, bridge),
    private val geminiAutomator: GeminiAutomator = GeminiAutomator(context, bridge),
    private val onIdePull: (project: String, files: List<String>, onResult: (String, Int) -> Unit) -> Unit,
    private val onIdeCommit: (xml: String, onResult: (String, String) -> Unit) -> Unit,
    private val scope: CoroutineScope,
    private val getActiveWebView: () -> WebView?,
    private val onPipelineStateChanged: (Boolean) -> Unit = {},
    private val onOpenTab: (url: String, profileId: String?) -> Unit = { _, _ -> }
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var savedWindowBrightness: Float? = null
    private var savedSystemBrightness: Int? = null
    private var savedSystemBrightnessMode: Int? = null
    private var dimmerStatusTextView: android.widget.TextView? = null
    private var lastDimmerTapMs: Long = 0L

    private fun findHostActivity(): android.app.Activity? {
        var ctx: Context? = getActiveWebView()?.context ?: context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is android.app.Activity) return ctx
            ctx = ctx.baseContext
        }
        return ctx as? android.app.Activity
    }

    private fun applyActivityKeepScreenOn(enabled: Boolean) {
        val window = findHostActivity()?.window ?: return
        if (enabled) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun applyKeepAwakeInternal(enabled: Boolean) {
        applyActivityKeepScreenOn(enabled)
        if (enabled) {
            if (bridge.canDrawOverlays()) {
                val anchor = android.view.View(context.applicationContext).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
                bridge.showOverlay(
                    tag = "omni_keep_screen_awake",
                    view = anchor,
                    width = 1,
                    height = 1,
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START,
                    x = 0,
                    y = 0,
                    focusable = false,
                    touchable = false
                )
            }
        } else {
            bridge.dismissOverlay("omni_keep_screen_awake")
        }
    }

    private fun applyBrightnessDim(dim: Boolean) {
        val appCtx = context.applicationContext
        val activity = findHostActivity()
        if (dim) {
            if (activity != null) {
                val lp = activity.window.attributes
                if (savedWindowBrightness == null) {
                    savedWindowBrightness = lp.screenBrightness
                }
                lp.screenBrightness = 0.01f
                activity.window.attributes = lp
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
                android.provider.Settings.System.canWrite(appCtx)) {
                try {
                    if (savedSystemBrightness == null) {
                        savedSystemBrightness = android.provider.Settings.System.getInt(
                            appCtx.contentResolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS
                        )
                        savedSystemBrightnessMode = android.provider.Settings.System.getInt(
                            appCtx.contentResolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE
                        )
                    }
                    bridge.setScreenBrightness(1)
                } catch (_: Exception) {}
            }
        } else {
            if (activity != null && savedWindowBrightness != null) {
                val lp = activity.window.attributes
                lp.screenBrightness = savedWindowBrightness ?: android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                activity.window.attributes = lp
                savedWindowBrightness = null
            }
            if (savedSystemBrightness != null &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
                android.provider.Settings.System.canWrite(appCtx)) {
                try {
                    android.provider.Settings.System.putInt(
                        appCtx.contentResolver,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS,
                        savedSystemBrightness!!
                    )
                    savedSystemBrightnessMode?.let { mode ->
                        android.provider.Settings.System.putInt(
                            appCtx.contentResolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                            mode
                        )
                    }
                } catch (_: Exception) {}
                savedSystemBrightness = null
                savedSystemBrightnessMode = null
            }
        }
    }

    private fun setNightDimmerInternal(enabled: Boolean, statusText: String): Boolean {
        if (!enabled) {
            bridge.dismissOverlay("omni_night_dimmer")
            dimmerStatusTextView = null
            applyBrightnessDim(false)
            return false
        }

        applyKeepAwakeInternal(true)
        applyBrightnessDim(true)

        if (!bridge.canDrawOverlays()) {
            return false
        }

        val appCtx = context.applicationContext
        val density = appCtx.resources.displayMetrics.density
        val pad = (24 * density).toInt()

        val root = android.widget.FrameLayout(appCtx).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        val col = android.widget.LinearLayout(appCtx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }

        val titleTv = android.widget.TextView(appCtx).apply {
            text = "🌙 NIGHT SHIFT OLED SHIELD"
            setTextColor(android.graphics.Color.parseColor("#484F58"))
            textSize = 12f
            gravity = android.view.Gravity.CENTER
        }

        val statusTv = android.widget.TextView(appCtx).apply {
            text = statusText.ifBlank { "Pipeline active in background..." }
            setTextColor(android.graphics.Color.parseColor("#6E7681"))
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding(0, (8 * density).toInt(), 0, (18 * density).toInt())
        }
        dimmerStatusTextView = statusTv

        val wakeBtn = android.widget.TextView(appCtx).apply {
            text = "☀️ Tap Twice Anywhere or Tap Here to Wake"
            setTextColor(android.graphics.Color.parseColor("#58A6FF"))
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#0D1117"))
            setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
            setOnClickListener {
                setNightDimmerInternal(false, "")
            }
        }

        col.addView(titleTv)
        col.addView(statusTv)
        col.addView(wakeBtn)

        val lp = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER
        )
        root.addView(col, lp)
        root.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastDimmerTapMs <= 650L) {
                setNightDimmerInternal(false, "")
            } else {
                lastDimmerTapMs = now
            }
        }

        return bridge.showOverlay(
            tag = "omni_night_dimmer",
            view = root,
            width = android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            height = android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            gravity = android.view.Gravity.CENTER,
            x = 0,
            y = 0,
            focusable = false,
            touchable = true
        )
    }

    private fun resolveProfileId(input: String): String {
        val trimmed = input.trim()
        if (trimmed.equals("random", ignoreCase = true)) {
            val vault = com.omni.plugin.browser.storage.VaultManager(context, bridge)
            val profiles = (vault.loadProfiles() ?: emptyList()).ifEmpty {
                listOf(BrowserProfile("default", "Default", 0xFF2979FF))
            }
            val analytics = vault.loadAiAnalytics()
            val now = System.currentTimeMillis()
            val unverifiedFiltered = profiles.filter { !(analytics[it.id]?.needsVerification ?: false) }
            val candidateBase = if (unverifiedFiltered.isNotEmpty()) unverifiedFiltered else profiles
            val healthy = candidateBase.filter { prof ->
                val qTime = analytics[prof.id]?.lastQuotaHitTimestamp ?: 0L
                !com.omni.plugin.browser.utils.isQuotaCurrentlyActive(qTime, now)
            }
            val pool = if (healthy.isNotEmpty()) healthy else candidateBase
            val chosen = pool.random()
            bridge.log("STUDIO_LAB", "🎲 [RANDOM PROFILE] Selected '${chosen.name}' (${chosen.id}) from ${pool.size} profile(s)")
            return chosen.id
        }
        if (trimmed.isEmpty() || trimmed.equals("default", ignoreCase = true)) return "default"
        val profiles = com.omni.plugin.browser.storage.VaultManager(context, bridge).loadProfiles() ?: emptyList()
        val matched = profiles.find { it.id.equals(trimmed, ignoreCase = true) }
            ?: profiles.find { it.name.trim().equals(trimmed, ignoreCase = true) }
        return matched?.id ?: trimmed
    }

    private fun resolve(callId: String, data: Any?) {
        val json = when (data) {
            is String -> JSONObject.quote(data)
            is JSONObject, is JSONArray -> data.toString()
            null -> "null"
            else -> JSONObject.quote(data.toString())
        }
        mainHandler.post {
            getActiveWebView()?.evaluateJavascript("if (window.__omniRpcResolve) { window.__omniRpcResolve('$callId', $json); }", null)
        }
    }

    private fun reject(callId: String, error: String) {
        val quoted = JSONObject.quote(error)
        mainHandler.post {
            getActiveWebView()?.evaluateJavascript("if (window.__omniRpcReject) { window.__omniRpcReject('$callId', $quoted); }", null)
        }
    }

    private fun progress(callId: String, data: Any?) {
        val json = when (data) {
            is String -> JSONObject.quote(data)
            is JSONObject, is JSONArray -> data.toString()
            else -> JSONObject.quote(data.toString())
        }
        mainHandler.post {
            getActiveWebView()?.evaluateJavascript("if (window.__omniRpcProgress) { window.__omniRpcProgress('$callId', $json); }", null)
        }
    }

    private fun progressDelta(callId: String, thoughtsDelta: String, outputDelta: String, isFirst: Boolean) {
        val deltaObj = JSONObject().apply {
            put("__isDelta", true)
            put("dt", thoughtsDelta)
            put("do", outputDelta)
            put("isFirst", isFirst)
        }
        progress(callId, deltaObj)
    }

    private fun parseAttachmentsFromJson(arr: JSONArray?): List<AutomationAttachment> {
        val attachments = mutableListOf<AutomationAttachment>()
        if (arr == null) return attachments
        for (i in 0 until arr.length()) {
            val item = arr.get(i)
            if (item is JSONObject) {
                attachments.add(
                    AutomationAttachment(
                        name = item.optString("name", "attachment_$i"),
                        mimeType = item.optString("mime", item.optString("mimeType", "application/octet-stream")),
                        sizeBytes = item.optLong("size", 0L),
                        base64Data = item.optString("data", item.optString("base64Data", ""))
                    )
                )
            } else if (item is String) {
                val f = File(item)
                if (f.exists() && f.isFile) {
                    val bytes = f.readBytes()
                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    attachments.add(
                        AutomationAttachment(
                            name = f.name,
                            mimeType = com.omni.plugin.browser.utils.resolveMimeType(f),
                            sizeBytes = f.length(),
                            base64Data = b64
                        )
                    )
                }
            }
        }
        return attachments
    }

    // --- 0. Profile Discovery & Quota Health ---
    @JavascriptInterface
    fun profilesList(callId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val vault = com.omni.plugin.browser.storage.VaultManager(context, bridge)
                val profiles = (vault.loadProfiles() ?: emptyList()).ifEmpty {
                    listOf(BrowserProfile("default", "Default", 0xFF2979FF))
                }
                val analytics = vault.loadAiAnalytics()
                val now = System.currentTimeMillis()
                val today = com.omni.plugin.browser.utils.getAnalyticsTodayKey(now)
                val arr = JSONArray()
                profiles.forEach { prof ->
                    val a = analytics[prof.id]
                    val qTime = a?.lastQuotaHitTimestamp ?: 0L
                    val isExhausted = com.omni.plugin.browser.utils.isQuotaCurrentlyActive(qTime, now)
                    val needsVerify = a?.needsVerification ?: false
                    arr.put(
                        JSONObject().apply {
                            put("id", prof.id)
                            put("name", prof.name)
                            put("color", String.format("#%06X", (0xFFFFFF and prof.colorValue.toInt())))
                            put("promptsSentToday", a?.effectivePromptsToday(today) ?: 0)
                            put("totalPromptsLifetime", a?.totalPromptsLifetime ?: 0)
                            put("lastQuotaHitTimestamp", qTime)
                            put("isQuotaExhausted", isExhausted)
                            put("needsVerification", needsVerify)
                            put("verificationRequiredAt", a?.verificationRequiredAt ?: 0L)
                            put("verificationReason", a?.verificationReason ?: "")
                            put("isAvailable", !isExhausted && !needsVerify)
                        }
                    )
                }
                resolve(callId, arr)
            } catch (e: Exception) {
                reject(callId, e.message ?: "Failed listing profiles")
            }
        }
    }
    // --- 1. Multi-Session Puppet Pool Methods (Uncapped Concurrent Sessions) ---
    @JavascriptInterface
    fun studioCreateSession(callId: String, profileId: String, model: String) {
        mainHandler.post {
            val resolvedId = resolveProfileId(profileId)
            automator.createSession(resolvedId, model) { sessionId, err ->
                if (sessionId != null) {
                    val res = JSONObject().apply {
                        put("sessionId", sessionId)
                        put("profileId", resolvedId)
                    }
                    resolve(callId, res)
                } else {
                    reject(callId, err ?: "Failed creating session")
                }
            }
        }
    }
    @JavascriptInterface
    fun studioCreateSessionWithOpts(callId: String, optsJson: String) {
        mainHandler.post {
            try {
                val json = JSONObject(optsJson)
                val rawProfile = json.optString("profile", json.optString("profileId", "default")).ifEmpty { "default" }
                val resolvedId = resolveProfileId(rawProfile)
                val model = json.optString("model", "")
                val tempChat: Boolean? = if (json.has("temporaryChat") && !json.isNull("temporaryChat")) json.optBoolean("temporaryChat") else null
                automator.createSession(resolvedId, model, tempChat) { sessionId, err ->
                    if (sessionId != null) {
                        val res = JSONObject().apply {
                            put("sessionId", sessionId)
                            put("profileId", resolvedId)
                        }
                        resolve(callId, res)
                    } else {
                        reject(callId, err ?: "Failed creating session")
                    }
                }
            } catch (e: Exception) {
                reject(callId, "Invalid session options: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun studioConfigureSession(callId: String, sessionId: String, configJson: String) {
        mainHandler.post {
            try {
                val json = JSONObject(configJson)
                automator.configureSession(
                    sessionId = sessionId,
                    sysTitle = json.optString("sysTitle", ""),
                    sysPrompt = json.optString("sysPrompt", ""),
                    thinkingLevel = json.optString("thinkingLevel", "Default"),
                    model = json.optString("model", ""),
                    fallbackEnabled = json.optBoolean("fallbackEnabled", true),
                    temporaryChat = json.optBoolean("temporaryChat", false),
                    headlessTurbo = json.optBoolean("headlessTurbo", true)
                ) { success, err ->
                    if (success) resolve(callId, "Configured")
                    else reject(callId, err ?: "Configuration error")
                }
            } catch (e: Exception) {
                reject(callId, "Invalid config JSON: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun studioSendSessionTurn(callId: String, sessionId: String, turnJson: String) {
        mainHandler.post {
            try {
                val json = JSONObject(turnJson)
                val prompt = json.optString("prompt", "")
                val label = json.optString("label", "Turn")
                val attachments = parseAttachmentsFromJson(json.optJSONArray("attachments"))

                automator.executeSessionTurn(
                    sessionId = sessionId,
                    prompt = prompt,
                    attachments = attachments,
                    label = label,
                    onProgress = { thoughts, output ->
                        val progObj = JSONObject().apply {
                            put("thoughts", thoughts)
                            put("output", output)
                        }
                        progress(callId, progObj)
                    },
                    onDelta = { thoughtsDelta, outputDelta, isFirst ->
                        progressDelta(callId, thoughtsDelta, outputDelta, isFirst)
                    },
                    onComplete = { thoughts, output ->
                        val chatUrl = automator.getSessionUrl(sessionId)
                        val profId = automator.sessions[sessionId]?.profileId ?: ""
                        val resObj = JSONObject().apply {
                            put("thoughts", thoughts)
                            put("output", output)
                            put("finishReason", 0)
                            put("truncated", false)
                            put("url", chatUrl)
                            put("profileId", profId)
                        }
                        resolve(callId, resObj)
                    },
                    onCompleteMeta = { thoughts, output, finishReason, truncated ->
                        val chatUrl = automator.getSessionUrl(sessionId)
                        val profId = automator.sessions[sessionId]?.profileId ?: ""
                        val resObj = JSONObject().apply {
                            put("thoughts", thoughts)
                            put("output", output)
                            put("finishReason", finishReason)
                            put("truncated", truncated)
                            put("url", chatUrl)
                            put("profileId", profId)
                        }
                        resolve(callId, resObj)
                    },
                    onError = { err ->
                        reject(callId, err)
                    }
                )
            } catch (e: Exception) {
                reject(callId, "Turn execution error: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun studioCloseSession(callId: String, sessionId: String) {
        mainHandler.post {
            automator.closeSession(sessionId)
            resolve(callId, "Closed")
        }
    }
    @JavascriptInterface
    fun studioNewChatSession(callId: String, sessionId: String) {
        mainHandler.post {
            automator.newChatSession(sessionId) { success, err ->
                if (success) resolve(callId, "Ready")
                else reject(callId, err ?: "Failed resetting AI Studio session")
            }
        }
    }

    // --- Backward-Compatible Default Session Wrappers ---
    @JavascriptInterface
    fun studioInit(callId: String, profileId: String) {
        studioInit(callId, profileId, "")
    }

    @JavascriptInterface
    fun studioInit(callId: String, profileId: String, model: String) {
        mainHandler.post {
            val resolvedId = resolveProfileId(profileId)
            automator.prepareSession(resolvedId, model) { success, err ->
                if (success) resolve(callId, "Ready")
                else reject(callId, err ?: "Failed initializing AI Studio session")
            }
        }
    }

    @JavascriptInterface
    fun studioConfigure(callId: String, configJson: String) {
        mainHandler.post {
            try {
                val json = JSONObject(configJson)
                automator.configure(
                    sysTitle = json.optString("sysTitle", ""),
                    sysPrompt = json.optString("sysPrompt", ""),
                    thinkingLevel = json.optString("thinkingLevel", "Default"),
                    model = json.optString("model", "Gemini 3.7 Flash"),
                    fallbackEnabled = json.optBoolean("fallbackEnabled", true),
                    temporaryChat = json.optBoolean("temporaryChat", false)
                ) { success, err ->
                    if (success) resolve(callId, "Configured")
                    else reject(callId, err ?: "Configuration error")
                }
            } catch (e: Exception) {
                reject(callId, "Invalid config JSON: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun studioSendTurn(callId: String, turnJson: String) {
        mainHandler.post {
            try {
                val json = JSONObject(turnJson)
                val prompt = json.optString("prompt", "")
                val label = json.optString("label", "Playbook Turn")
                val attachments = parseAttachmentsFromJson(json.optJSONArray("attachments"))

                automator.executeTurn(
                    prompt = prompt,
                    attachments = attachments,
                    label = label,
                    onProgress = { thoughts, output ->
                        val progObj = JSONObject().apply {
                            put("thoughts", thoughts)
                            put("output", output)
                        }
                        progress(callId, progObj)
                    },
                    onComplete = { thoughts, output ->
                        val resObj = JSONObject().apply {
                            put("thoughts", thoughts)
                            put("output", output)
                        }
                        resolve(callId, resObj)
                    },
                    onError = { err ->
                        reject(callId, err)
                    }
                )
            } catch (e: Exception) {
                reject(callId, "Turn execution error: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun studioAbort() {
        mainHandler.post {
            automator.isAborted = true
        }
    }

    @JavascriptInterface
    fun studioClose() {
        mainHandler.post {
            automator.stop(null)
        }
    }

    // --- 1B. ChatGPT Puppet Pool Methods (Dedicated Engine) ---
    @JavascriptInterface
    fun gptCreateSession(callId: String, profileId: String, model: String, temporaryChat: Boolean) {
        mainHandler.post {
            val resolvedId = resolveProfileId(profileId)
            chatGptAutomator.createSession(resolvedId, model, temporaryChat) { sessionId, err ->
                if (sessionId != null) {
                    val res = JSONObject().apply {
                        put("sessionId", sessionId)
                        put("profileId", resolvedId)
                    }
                    resolve(callId, res)
                } else {
                    reject(callId, err ?: "Failed creating ChatGPT session")
                }
            }
        }
    }

    @JavascriptInterface
    fun gptConfigureSession(callId: String, sessionId: String, configJson: String) {
        mainHandler.post {
            try {
                val json = JSONObject(configJson)
                val tempChat: Boolean? = if (json.has("temporaryChat") && !json.isNull("temporaryChat")) json.getBoolean("temporaryChat") else null
                chatGptAutomator.configureSession(
                    sessionId = sessionId,
                    model = json.optString("model", ""),
                    temporaryChat = tempChat,
                    headlessTurbo = json.optBoolean("headlessTurbo", true)
                ) { success, err ->
                    if (success) resolve(callId, "Configured")
                    else reject(callId, err ?: "ChatGPT configuration error")
                }
            } catch (e: Exception) {
                reject(callId, "Invalid config JSON: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun gptSendSessionTurn(callId: String, sessionId: String, turnJson: String) {
        mainHandler.post {
            try {
                val json = JSONObject(turnJson)
                val prompt = json.optString("prompt", "")
                val label = json.optString("label", "Turn")
                val attachments = parseAttachmentsFromJson(json.optJSONArray("attachments"))

                chatGptAutomator.executeSessionTurn(
                    sessionId = sessionId,
                    prompt = prompt,
                    attachments = attachments,
                    label = label,
                    onProgress = { thoughts, output ->
                        val progObj = JSONObject().apply {
                            put("thoughts", thoughts)
                            put("output", output)
                        }
                        progress(callId, progObj)
                    },
                    onDelta = { thoughtsDelta, outputDelta, isFirst ->
                        progressDelta(callId, thoughtsDelta, outputDelta, isFirst)
                    },
                    onComplete = { thoughts, output ->
                        val chatUrl = chatGptAutomator.getSessionUrl(sessionId)
                        val profId = chatGptAutomator.sessions[sessionId]?.profileId ?: ""
                        val resObj = JSONObject().apply {
                            put("thoughts", thoughts)
                            put("output", output)
                            put("url", chatUrl)
                            put("profileId", profId)
                        }
                        resolve(callId, resObj)
                    },
                    onError = { err ->
                        reject(callId, err)
                    }
                )
            } catch (e: Exception) {
                reject(callId, "ChatGPT turn error: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun gptNewChatSession(callId: String, sessionId: String, temporaryChatJson: String) {
        mainHandler.post {
            val tempChat: Boolean? = when (temporaryChatJson.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            chatGptAutomator.newChatSession(sessionId, tempChat) { success, err ->
                if (success) resolve(callId, "Ready")
                else reject(callId, err ?: "Failed resetting ChatGPT session")
            }
        }
    }

    @JavascriptInterface
    fun gptCloseSession(callId: String, sessionId: String) {
        mainHandler.post {
            chatGptAutomator.closeSession(sessionId)
            resolve(callId, "Closed")
        }
    }

    @JavascriptInterface
    fun gptInit(callId: String, profileId: String, model: String, temporaryChat: Boolean) {
        mainHandler.post {
            val resolvedId = resolveProfileId(profileId)
            chatGptAutomator.prepareSession(resolvedId, model, temporaryChat) { success, err ->
                if (success) resolve(callId, "Ready")
                else reject(callId, err ?: "Failed initializing ChatGPT session")
            }
        }
    }

    @JavascriptInterface
    fun gptConfigure(callId: String, configJson: String) {
        mainHandler.post {
            try {
                val json = JSONObject(configJson)
                val tempChat: Boolean? = if (json.has("temporaryChat") && !json.isNull("temporaryChat")) json.getBoolean("temporaryChat") else null
                chatGptAutomator.configure(
                    model = json.optString("model", ""),
                    temporaryChat = tempChat,
                    headlessTurbo = json.optBoolean("headlessTurbo", true)
                ) { success, err ->
                    if (success) resolve(callId, "Configured")
                    else reject(callId, err ?: "ChatGPT configuration error")
                }
            } catch (e: Exception) {
                reject(callId, "Invalid config JSON: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun gptSendTurn(callId: String, turnJson: String) {
        mainHandler.post {
            try {
                val json = JSONObject(turnJson)
                val prompt = json.optString("prompt", "")
                val label = json.optString("label", "Playbook Turn")
                val attachments = parseAttachmentsFromJson(json.optJSONArray("attachments"))

                chatGptAutomator.executeTurn(
                    prompt = prompt,
                    attachments = attachments,
                    label = label,
                    onProgress = { thoughts, output ->
                        val progObj = JSONObject().apply {
                            put("thoughts", thoughts)
                            put("output", output)
                        }
                        progress(callId, progObj)
                    },
                    onDelta = { thoughtsDelta, outputDelta, isFirst ->
                        progressDelta(callId, thoughtsDelta, outputDelta, isFirst)
                    },
                    onComplete = { thoughts, output ->
                        val resObj = JSONObject().apply {
                            put("thoughts", thoughts)
                            put("output", output)
                        }
                        resolve(callId, resObj)
                    },
                    onError = { err ->
                        reject(callId, err)
                    }
                )
            } catch (e: Exception) {
                reject(callId, "ChatGPT turn error: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun gptNewChat(callId: String, temporaryChatJson: String) {
        mainHandler.post {
            val tempChat: Boolean? = when (temporaryChatJson.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            chatGptAutomator.newChat(tempChat) { success, err ->
                if (success) resolve(callId, "Ready")
                else reject(callId, err ?: "Failed resetting ChatGPT session")
            }
        }
    }

    @JavascriptInterface
    fun gptAbort() {
        mainHandler.post {
            chatGptAutomator.isAborted = true
        }
    }

    @JavascriptInterface
    fun gptClose() {
        mainHandler.post {
            chatGptAutomator.closeAllSessions()
        }
    }

    // --- 1C. Gemini Puppet Pool Methods (Dedicated Engine) ---
    @JavascriptInterface
    fun geminiCreateSession(callId: String, profileId: String, model: String, temporaryChat: Boolean) {
        mainHandler.post {
            val resolvedId = resolveProfileId(profileId)
            geminiAutomator.createSession(resolvedId, model, temporaryChat) { sessionId, err ->
                if (sessionId != null) {
                    val res = JSONObject().apply {
                        put("sessionId", sessionId)
                        put("profileId", resolvedId)
                    }
                    resolve(callId, res)
                } else {
                    reject(callId, err ?: "Failed creating Gemini session")
                }
            }
        }
    }

    @JavascriptInterface
    fun geminiConfigureSession(callId: String, sessionId: String, configJson: String) {
        mainHandler.post {
            try {
                val json = JSONObject(configJson)
                val tempChat: Boolean? = if (json.has("temporaryChat") && !json.isNull("temporaryChat")) json.getBoolean("temporaryChat") else null
                geminiAutomator.configureSession(
                    sessionId = sessionId,
                    model = json.optString("model", ""),
                    temporaryChat = tempChat,
                    headlessTurbo = json.optBoolean("headlessTurbo", true)
                ) { success, err ->
                    if (success) resolve(callId, "Configured")
                    else reject(callId, err ?: "Gemini configuration error")
                }
            } catch (e: Exception) {
                reject(callId, "Invalid config JSON: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun geminiSendSessionTurn(callId: String, sessionId: String, turnJson: String) {
        mainHandler.post {
            try {
                val json = JSONObject(turnJson)
                val prompt = json.optString("prompt", "")
                val label = json.optString("label", "Turn")
                val attachments = parseAttachmentsFromJson(json.optJSONArray("attachments"))

                geminiAutomator.executeSessionTurn(
                    sessionId = sessionId,
                    prompt = prompt,
                    attachments = attachments,
                    label = label,
                    onProgress = { thoughts, output ->
                        val progObj = JSONObject().apply {
                            put("thoughts", thoughts)
                            put("output", output)
                        }
                        progress(callId, progObj)
                    },
                    onDelta = { thoughtsDelta, outputDelta, isFirst ->
                        progressDelta(callId, thoughtsDelta, outputDelta, isFirst)
                    },
                    onComplete = { thoughts, output ->
                        val chatUrl = geminiAutomator.getSessionUrl(sessionId)
                        val profId = geminiAutomator.sessions[sessionId]?.profileId ?: ""
                        val resObj = JSONObject().apply {
                            put("thoughts", thoughts)
                            put("output", output)
                            put("url", chatUrl)
                            put("profileId", profId)
                        }
                        resolve(callId, resObj)
                    },
                    onError = { err ->
                        reject(callId, err)
                    }
                )
            } catch (e: Exception) {
                reject(callId, "Gemini turn error: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun geminiNewChatSession(callId: String, sessionId: String, temporaryChatJson: String) {
        mainHandler.post {
            val tempChat: Boolean? = when (temporaryChatJson.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            geminiAutomator.newChatSession(sessionId, tempChat) { success, err ->
                if (success) resolve(callId, "Ready")
                else reject(callId, err ?: "Failed resetting Gemini session")
            }
        }
    }

    @JavascriptInterface
    fun geminiCloseSession(callId: String, sessionId: String) {
        mainHandler.post {
            geminiAutomator.closeSession(sessionId)
            resolve(callId, "Closed")
        }
    }

    @JavascriptInterface
    fun geminiInit(callId: String, profileId: String, model: String, temporaryChat: Boolean) {
        mainHandler.post {
            val resolvedId = resolveProfileId(profileId)
            geminiAutomator.prepareSession(resolvedId, model, temporaryChat) { success, err ->
                if (success) resolve(callId, "Ready")
                else reject(callId, err ?: "Failed initializing Gemini session")
            }
        }
    }

    @JavascriptInterface
    fun geminiConfigure(callId: String, configJson: String) {
        mainHandler.post {
            try {
                val json = JSONObject(configJson)
                val tempChat: Boolean? = if (json.has("temporaryChat") && !json.isNull("temporaryChat")) json.getBoolean("temporaryChat") else null
                geminiAutomator.configure(
                    model = json.optString("model", ""),
                    temporaryChat = tempChat,
                    headlessTurbo = json.optBoolean("headlessTurbo", true)
                ) { success, err ->
                    if (success) resolve(callId, "Configured")
                    else reject(callId, err ?: "Gemini configuration error")
                }
            } catch (e: Exception) {
                reject(callId, "Invalid config JSON: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun geminiSendTurn(callId: String, turnJson: String) {
        mainHandler.post {
            try {
                val json = JSONObject(turnJson)
                val prompt = json.optString("prompt", "")
                val label = json.optString("label", "Playbook Turn")
                val attachments = parseAttachmentsFromJson(json.optJSONArray("attachments"))

                geminiAutomator.executeTurn(
                    prompt = prompt,
                    attachments = attachments,
                    label = label,
                    onProgress = { thoughts, output ->
                        val progObj = JSONObject().apply {
                            put("thoughts", thoughts)
                            put("output", output)
                        }
                        progress(callId, progObj)
                    },
                    onDelta = { thoughtsDelta, outputDelta, isFirst ->
                        progressDelta(callId, thoughtsDelta, outputDelta, isFirst)
                    },
                    onComplete = { thoughts, output ->
                        val resObj = JSONObject().apply {
                            put("thoughts", thoughts)
                            put("output", output)
                        }
                        resolve(callId, resObj)
                    },
                    onError = { err ->
                        reject(callId, err)
                    }
                )
            } catch (e: Exception) {
                reject(callId, "Gemini turn error: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun geminiNewChat(callId: String, temporaryChatJson: String) {
        mainHandler.post {
            val tempChat: Boolean? = when (temporaryChatJson.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            geminiAutomator.newChat(tempChat) { success, err ->
                if (success) resolve(callId, "Ready")
                else reject(callId, err ?: "Failed resetting Gemini session")
            }
        }
    }

    @JavascriptInterface
    fun geminiAbort() {
        mainHandler.post {
            geminiAutomator.isAborted = true
        }
    }

    @JavascriptInterface
    fun geminiClose() {
        mainHandler.post {
            geminiAutomator.closeAllSessions()
        }
    }

    // --- 2. Nexus Python Execution ---
    @JavascriptInterface
    fun pythonRun(callId: String, code: String) {
        scope.launch(Dispatchers.IO) {
            bridge.executePython(
                code = code,
                onOutput = { chunk ->
                    progress(callId, chunk)
                },
                onComplete = { success, output ->
                    if (success) resolve(callId, output)
                    else reject(callId, output)
                }
            )
        }
    }

    // --- 3. Conduit IDE Interoperability ---
    @JavascriptInterface
    fun idePullFiles(callId: String, project: String, filesJson: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val arr = JSONArray(filesJson)
                val fileList = mutableListOf<String>()
                for (i in 0 until arr.length()) fileList.add(arr.getString(i))

                onIdePull(project, fileList) { dumpText, count ->
                    val resArr = JSONArray()
                    dumpText.split(Regex("--- START OF FILE [^\n]+ ---")).filter { it.isNotBlank() }.forEach { chunk ->
                        val pathLine = chunk.lines().find { it.startsWith("FILE:") }
                        val path = pathLine?.removePrefix("FILE:")?.trim() ?: "unknown"
                        val content = chunk.substringAfter(pathLine ?: "").trim()
                        resArr.put(JSONObject().apply {
                            put("path", path)
                            put("content", content)
                        })
                    }
                    if (resArr.length() == 0 && dumpText.isNotBlank()) {
                        resArr.put(JSONObject().apply {
                            put("path", fileList.firstOrNull() ?: "project_files")
                            put("content", dumpText)
                        })
                    }
                    resolve(callId, resArr)
                }
            } catch (e: Exception) {
                reject(callId, "IDE Pull error: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun ideCommitCxp(callId: String, xml: String) {
        mainHandler.post {
            onIdeCommit(xml) { status, details ->
                val res = JSONObject().apply {
                    put("status", status)
                    put("details", details)
                }
                resolve(callId, res)
            }
        }
    }

    // --- 4. Isolated & Storage Filesystem ---
    @JavascriptInterface
    fun fsRead(callId: String, path: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val f = File(path)
                val target = if (f.isAbsolute && f.exists()) f else File(bridge.getPluginDir(), path)
                if (target.exists() && target.isFile) {
                    resolve(callId, target.readText(Charsets.UTF_8))
                } else {
                    reject(callId, "File not found: $path")
                }
            } catch (e: Exception) {
                reject(callId, e.message ?: "Read failed")
            }
        }
    }

    @JavascriptInterface
    fun fsWrite(callId: String, path: String, content: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val f = File(path)
                val target = if (f.isAbsolute) f else File(bridge.getPluginDir(), path)
                target.parentFile?.mkdirs()
                target.writeText(content, Charsets.UTF_8)
                resolve(callId, target.absolutePath)
            } catch (e: Exception) {
                reject(callId, e.message ?: "Write failed")
            }
        }
    }

    @JavascriptInterface
    fun fsList(callId: String, path: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val f = File(path)
                val target = if (f.isAbsolute && f.exists()) f else File(bridge.getPluginDir(), path)
                val files = target.listFiles()?.map { it.name } ?: emptyList()
                resolve(callId, JSONArray(files))
            } catch (e: Exception) {
                reject(callId, e.message ?: "List failed")
            }
        }
    }

    // --- 5. UI Utilities ---
    @JavascriptInterface
    fun toast(msg: String) { bridge.showToast(msg) }

    @JavascriptInterface
    fun vibrate(durationMs: Long) { bridge.vibrate(durationMs) }

    @JavascriptInterface
    fun copyToClipboard(text: String) { bridge.copyToClipboard(text) }

    @JavascriptInterface
    fun getClipboardText(): String { return bridge.getClipboardText() ?: "" }
    @JavascriptInterface
    fun browserOpenTab(url: String, profileId: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        val resolvedProf = if (profileId.isBlank() || profileId.equals("random", ignoreCase = true)) null else resolveProfileId(profileId)
        mainHandler.post {
            onOpenTab(cleanUrl, resolvedProf)
        }
    }

    // --- 6. Hardware, Screen Awake & WakeLock Management ---
    @JavascriptInterface
    fun canDrawOverlays(): Boolean {
        return bridge.canDrawOverlays()
    }

    @JavascriptInterface
    fun requestOverlayPermission() {
        mainHandler.post {
            bridge.requestOverlayPermission()
        }
    }

    @JavascriptInterface
    fun screenKeepAwake(callId: String, enabled: Boolean) {
        mainHandler.post {
            try {
                applyKeepAwakeInternal(enabled)
                resolve(callId, JSONObject().apply {
                    put("keepAwake", enabled)
                    put("overlayActive", enabled && bridge.canDrawOverlays())
                })
            } catch (e: Exception) {
                reject(callId, e.message ?: "Failed toggling keepScreenAwake")
            }
        }
    }

    @JavascriptInterface
    fun nightDimmerSet(callId: String, enabled: Boolean, statusText: String) {
        mainHandler.post {
            try {
                val overlayShown = setNightDimmerInternal(enabled, statusText)
                resolve(callId, JSONObject().apply {
                    put("enabled", enabled)
                    put("overlayMode", overlayShown)
                })
            } catch (e: Exception) {
                reject(callId, e.message ?: "Failed toggling Night Dimmer")
            }
        }
    }

    @JavascriptInterface
    fun nightDimmerUpdate(statusText: String) {
        mainHandler.post {
            dimmerStatusTextView?.text = statusText
        }
    }

    @JavascriptInterface
    fun wakeLockAcquire(callId: String, tag: String) {
        mainHandler.post {
            try {
                onPipelineStateChanged(true)
                bridge.acquireWakeLock(tag.ifEmpty { "StudioLab" })
                bridge.startForegroundTask("Omni Factory", "Running background pipeline ($tag)")
                resolve(callId, "Acquired")
            } catch (e: Exception) {
                reject(callId, e.message ?: "Failed acquiring WakeLock")
            }
        }
    }

    @JavascriptInterface
    fun wakeLockRelease(callId: String) {
        mainHandler.post {
            try {
                onPipelineStateChanged(false)
                setNightDimmerInternal(false, "")
                applyKeepAwakeInternal(false)
                bridge.releaseWakeLock()
                bridge.stopForegroundTask()
                resolve(callId, "Released")
            } catch (e: Exception) {
                reject(callId, e.message ?: "Failed releasing WakeLock")
            }
        }
    }
}