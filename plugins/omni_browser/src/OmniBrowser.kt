package com.omni.plugin.browser

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.omni.hub.api.HostBridge
import com.omni.hub.api.PluginEntry
import com.omni.plugin.browser.engine.AiStudioAutomator
import com.omni.plugin.browser.engine.AutomationCallback
import com.omni.plugin.browser.models.*
import com.omni.plugin.browser.state.BrowserStateHolder
import com.omni.plugin.browser.storage.VaultManager
import com.omni.plugin.browser.ui.*
import com.omni.plugin.browser.ui.dialogs.*
import com.omni.plugin.browser.ui.dialogs.SmartNotesDialog
import com.omni.plugin.browser.utils.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class OmniBrowser : PluginEntry() {

    companion object {
        private data class IpcAiSession(
            val automator: AiStudioAutomator,
            val sessionId: String,
            val profileId: String,
            val model: String,
            val temporaryChat: Boolean,
            var sysTitle: String,
            var sysPrompt: String,
            var thinkingLevel: String,
            var turnSeq: Long = 0L
        )

        private val ipcAiSessions = java.util.concurrent.ConcurrentHashMap<String, IpcAiSession>()

        @Volatile
        private var activeSolverAutomator: AiStudioAutomator? = null
        @Volatile
        private var solverAbortFlag: Boolean = false
        @Volatile
        private var activeBrowserStateRef: java.lang.ref.WeakReference<BrowserStateHolder>? = null

        private fun ensureIpcSession(
            ctx: Context,
            bridge: HostBridge,
            sessionKey: String,
            requestedProfileId: String,
            model: String,
            thinkingLevel: String,
            temporaryChat: Boolean,
            sysTitle: String,
            sysPrompt: String,
            onStatus: ((String) -> Unit)?,
            onReady: (IpcAiSession?, String?) -> Unit
        ) {
            val vault = VaultManager(ctx, bridge)
            vault.resurrectFromVault()
            val profiles = (vault.loadProfiles() ?: emptyList()).ifEmpty {
                listOf(BrowserProfile("default", "Default", 0xFF2979FF))
            }
            val analytics = vault.loadAiAnalytics()
            val now = System.currentTimeMillis()

            val resolvedProfile = if (requestedProfileId.equals("random", ignoreCase = true)) {
                val unverified = profiles.filter { !(analytics[it.id]?.needsVerification ?: false) }
                val base = if (unverified.isNotEmpty()) unverified else profiles
                val fresh = base.filter { !isQuotaCurrentlyActive(analytics[it.id]?.lastQuotaHitTimestamp ?: 0L, now) }
                (if (fresh.isNotEmpty()) fresh else base).random().id
            } else {
                val matched = profiles.find { it.id.equals(requestedProfileId, ignoreCase = true) }
                    ?: profiles.find { it.name.trim().equals(requestedProfileId.trim(), ignoreCase = true) }
                matched?.id ?: requestedProfileId.ifBlank { "default" }
            }

            if (analytics[resolvedProfile]?.needsVerification == true) {
                val profName = profiles.find { it.id == resolvedProfile }?.name ?: resolvedProfile
                onReady(null, "Profile '$profName' requires Google verification. Approve it in Omni Browser Health Deck.")
                return
            }

            val existing = ipcAiSessions[sessionKey]
            if (existing != null && existing.automator.sessions.containsKey(existing.sessionId)) {
                val sameBootParams = existing.profileId == resolvedProfile &&
                    existing.model == model &&
                    existing.temporaryChat == temporaryChat
                if (sameBootParams) {
                    val needsReconfig = existing.sysTitle != sysTitle ||
                        existing.sysPrompt != sysPrompt ||
                        existing.thinkingLevel != thinkingLevel
                    if (!needsReconfig) {
                        onReady(existing, null)
                        return
                    }
                    onStatus?.invoke("Updating AI Studio config...")
                    existing.automator.configureSession(
                        sessionId = existing.sessionId,
                        sysTitle = sysTitle,
                        sysPrompt = sysPrompt,
                        thinkingLevel = thinkingLevel,
                        model = model,
                        fallbackEnabled = true,
                        temporaryChat = temporaryChat,
                        headlessTurbo = true
                    ) { ok, err ->
                        if (ok) {
                            existing.sysTitle = sysTitle
                            existing.sysPrompt = sysPrompt
                            existing.thinkingLevel = thinkingLevel
                            onReady(existing, null)
                        } else {
                            onReady(null, err ?: "Failed configuring AI Studio session")
                        }
                    }
                    return
                } else {
                    existing.automator.closeSession(existing.sessionId)
                    ipcAiSessions.remove(sessionKey)
                }
            }

            onStatus?.invoke("Warming AI Studio...")
            val automator = AiStudioAutomator(ctx.applicationContext, bridge)
            automator.createSession(resolvedProfile, model, temporaryChat) { sessionId, err ->
                if (sessionId == null) {
                    onReady(null, err ?: "Failed starting AI Studio session")
                    return@createSession
                }
                onStatus?.invoke("Applying instructions...")
                automator.configureSession(
                    sessionId = sessionId,
                    sysTitle = sysTitle,
                    sysPrompt = sysPrompt,
                    thinkingLevel = thinkingLevel,
                    model = model,
                    fallbackEnabled = true,
                    temporaryChat = temporaryChat,
                    headlessTurbo = true
                ) { ok, cfgErr ->
                    if (!ok) {
                        automator.closeSession(sessionId)
                        onReady(null, cfgErr ?: "Failed configuring AI Studio")
                        return@configureSession
                    }
                    val created = IpcAiSession(
                        automator = automator,
                        sessionId = sessionId,
                        profileId = resolvedProfile,
                        model = model,
                        temporaryChat = temporaryChat,
                        sysTitle = sysTitle,
                        sysPrompt = sysPrompt,
                        thinkingLevel = thinkingLevel
                    )
                    ipcAiSessions[sessionKey] = created
                    onReady(created, null)
                }
            }
        }

        fun abortCurrentSolve() {
            solverAbortFlag = true
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                activeSolverAutomator?.isAborted = true
                activeSolverAutomator?.stop(null)
                activeSolverAutomator = null
            }
        }
    }

    override fun onSystemEvent(event: String, payload: Map<String, Any>) {
        if (event == "TRIM_MEMORY") {
            val level = (payload["level"] as? Int) ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                activeBrowserStateRef?.get()?.trimMemory(level)
            }
            return
        }

        if (event == "ABORT_SOLVE") {
            val bridge = payload["bridge"] as? HostBridge
            bridge?.log("OMNI_SOLVE_IPC", "🛑 Processing ABORT_SOLVE. Halting AI Studio automator immediately.")
            abortCurrentSolve()
            return
        }
        if (event == "AI_LIST_PROFILES") {
            val ctx = payload["context"] as? Context ?: return
            val bridge = payload["bridge"] as? HostBridge ?: return
            @Suppress("UNCHECKED_CAST")
            val onResult = payload["on_result"] as? (List<Map<String, Any>>) -> Unit ?: return
            val vault = VaultManager(ctx, bridge)
            vault.resurrectFromVault()
            val profiles = (vault.loadProfiles() ?: emptyList()).ifEmpty {
                listOf(BrowserProfile("default", "Default", 0xFF2979FF))
            }
            val analytics = vault.loadAiAnalytics()
            val now = System.currentTimeMillis()
            val today = getAnalyticsTodayKey(now)
            val resultList = profiles.map { prof ->
                val a = analytics[prof.id]
                val qTime = a?.lastQuotaHitTimestamp ?: 0L
                val isExhausted = isQuotaCurrentlyActive(qTime, now)
                val needsVerify = a?.needsVerification ?: false
                mapOf<String, Any>(
                    "id" to prof.id,
                    "name" to prof.name,
                    "promptsSentToday" to (a?.effectivePromptsToday(today) ?: 0),
                    "isQuotaExhausted" to isExhausted,
                    "needsVerification" to needsVerify,
                    "isAvailable" to (!isExhausted && !needsVerify)
                )
            }
            onResult(resultList)
            return
        }

        if (event == "AI_WARM_SESSION") {
            val ctx = payload["context"] as? Context ?: return
            val bridge = payload["bridge"] as? HostBridge ?: return
            val sessionKey = (payload["session_key"] as? String)?.ifBlank { "default_ipc" } ?: "default_ipc"
            val profileId = (payload["profile_id"] as? String)?.ifBlank { "default" } ?: "default"
            val model = (payload["model"] as? String) ?: "Gemini 3.8 Flash"
            val thinkingLevel = (payload["thinking_level"] as? String)?.ifBlank { "Default" } ?: "Default"
            val temporaryChat = (payload["temporary_chat"] as? Boolean) ?: false
            val sysTitle = (payload["system_title"] as? String) ?: "Omni Assistant"
            val sysPrompt = (payload["system_prompt"] as? String) ?: ""
            @Suppress("UNCHECKED_CAST")
            val onStatus = payload["on_status"] as? (String) -> Unit
            @Suppress("UNCHECKED_CAST")
            val onReady = payload["on_ready"] as? (Boolean, String?) -> Unit

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ensureIpcSession(
                    ctx = ctx,
                    bridge = bridge,
                    sessionKey = sessionKey,
                    requestedProfileId = profileId,
                    model = model,
                    thinkingLevel = thinkingLevel,
                    temporaryChat = temporaryChat,
                    sysTitle = sysTitle,
                    sysPrompt = sysPrompt,
                    onStatus = onStatus
                ) { session, err ->
                    onReady?.invoke(session != null, err)
                }
            }
            return
        }

        if (event == "AI_ASK_STREAM") {
            val ctx = payload["context"] as? Context ?: return
            val bridge = payload["bridge"] as? HostBridge ?: return
            val sessionKey = (payload["session_key"] as? String)?.ifBlank { "default_ipc" } ?: "default_ipc"
            val prompt = (payload["prompt"] as? String) ?: ""
            val label = (payload["label"] as? String)?.ifBlank { "Turn" } ?: "Turn"
            val profileId = (payload["profile_id"] as? String)?.ifBlank { "default" } ?: "default"
            val model = (payload["model"] as? String) ?: "Gemini 3.8 Flash"
            val thinkingLevel = (payload["thinking_level"] as? String)?.ifBlank { "Default" } ?: "Default"
            val temporaryChat = (payload["temporary_chat"] as? Boolean) ?: true
            val sysTitle = (payload["system_title"] as? String) ?: "Omni Assistant"
            val sysPrompt = (payload["system_prompt"] as? String) ?: ""
            val forceNewChat = (payload["new_chat"] as? Boolean) ?: false
            @Suppress("UNCHECKED_CAST")
            val onStatus = payload["on_status"] as? (String) -> Unit
            @Suppress("UNCHECKED_CAST")
            val onDelta = payload["on_delta"] as? (String, String, Boolean) -> Unit
            @Suppress("UNCHECKED_CAST")
            val onProgress = payload["on_progress"] as? (String, String) -> Unit
            @Suppress("UNCHECKED_CAST")
            val onComplete = payload["on_complete"] as? (String, String) -> Unit
            @Suppress("UNCHECKED_CAST")
            val onError = payload["on_error"] as? (String) -> Unit

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ensureIpcSession(
                    ctx = ctx,
                    bridge = bridge,
                    sessionKey = sessionKey,
                    requestedProfileId = profileId,
                    model = model,
                    thinkingLevel = thinkingLevel,
                    temporaryChat = temporaryChat,
                    sysTitle = sysTitle,
                    sysPrompt = sysPrompt,
                    onStatus = onStatus
                ) { session, err ->
                    if (session == null) {
                        onError?.invoke(err ?: "Could not connect to AI Studio")
                        return@ensureIpcSession
                    }
                    val mySeq = ++session.turnSeq
                    fun runTurnNow() {
                        if (session.turnSeq != mySeq) return
                        onStatus?.invoke("Generating...")
                        session.automator.executeSessionTurn(
                            sessionId = session.sessionId,
                            prompt = prompt,
                            attachments = emptyList(),
                            label = label,
                            onProgress = { thoughts, output ->
                                if (session.turnSeq == mySeq) onProgress?.invoke(thoughts, output)
                            },
                            onComplete = { thoughts, output ->
                                if (session.turnSeq == mySeq) onComplete?.invoke(thoughts, output)
                            },
                            onError = { turnErr ->
                                val isFatalSession = turnErr.contains("Verification Required", ignoreCase = true) ||
                                    turnErr.contains("Quota Exhausted", ignoreCase = true) ||
                                    turnErr.contains("permission", ignoreCase = true)
                                if (isFatalSession) {
                                    session.automator.closeSession(session.sessionId)
                                    ipcAiSessions.remove(sessionKey)
                                }
                                if (session.turnSeq == mySeq) onError?.invoke(turnErr)
                            },
                            onDelta = if (onDelta != null) {
                                { dt, doStr, isFirst ->
                                    if (session.turnSeq == mySeq) onDelta.invoke(dt, doStr, isFirst)
                                }
                            } else null
                        )
                    }

                    if (forceNewChat) {
                        onStatus?.invoke("Resetting chat...")
                        session.automator.newChatSession(session.sessionId) { ok, ncErr ->
                            if (!ok) {
                                onError?.invoke(ncErr ?: "Failed resetting chat")
                                return@newChatSession
                            }
                            session.automator.configureSession(
                                sessionId = session.sessionId,
                                sysTitle = sysTitle,
                                sysPrompt = sysPrompt,
                                thinkingLevel = thinkingLevel,
                                model = model,
                                fallbackEnabled = true,
                                temporaryChat = temporaryChat,
                                headlessTurbo = true
                            ) { _, _ -> runTurnNow() }
                        }
                    } else {
                        runTurnNow()
                    }
                }
            }
            return
        }

        if (event == "AI_NEW_CHAT") {
            val sessionKey = (payload["session_key"] as? String)?.ifBlank { "default_ipc" } ?: "default_ipc"
            @Suppress("UNCHECKED_CAST")
            val onDone = payload["on_done"] as? (Boolean, String?) -> Unit
            val session = ipcAiSessions[sessionKey]
            if (session == null) {
                onDone?.invoke(true, "No active session")
                return
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                session.automator.newChatSession(session.sessionId) { ok, err ->
                    if (!ok) {
                        onDone?.invoke(false, err)
                        return@newChatSession
                    }
                    session.automator.configureSession(
                        sessionId = session.sessionId,
                        sysTitle = session.sysTitle,
                        sysPrompt = session.sysPrompt,
                        thinkingLevel = session.thinkingLevel,
                        model = session.model,
                        fallbackEnabled = true,
                        temporaryChat = session.temporaryChat,
                        headlessTurbo = true
                    ) { cfgOk, cfgErr ->
                        onDone?.invoke(cfgOk, cfgErr)
                    }
                }
            }
            return
        }

        if (event == "AI_CLOSE_SESSION") {
            val sessionKey = (payload["session_key"] as? String)?.ifBlank { "default_ipc" } ?: "default_ipc"
            val session = ipcAiSessions.remove(sessionKey)
            if (session != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    session.automator.closeSession(session.sessionId)
                }
            }
            return
        }

        if (event == "SOLVE_EXAM") {
            val ctx = payload["context"] as? Context ?: return
            val bridge = payload["bridge"] as? HostBridge ?: return
            val rawUris = payload["extra_image_uris"] ?: payload["uris"]
            @Suppress("UNCHECKED_CAST")
            val uris = (rawUris as? List<android.net.Uri>) ?: emptyList()
            val replyAction = payload["reply_action"] as? String ?: "com.universal.app.ACTION_OMNI_RESULT"
            val statusAction = payload["status_action"] as? String ?: "com.universal.app.ACTION_OMNI_STATUS"
            val presetTitle = payload["preset_title"] as? String ?: "Exam Solver"
            val userPrompt = payload["user_prompt"] as? String ?: ""
            val requestId = payload["request_id"] as? String ?: "req_${System.currentTimeMillis()}"

            bridge.log("OMNI_SOLVE_IPC", "🔥 Received SOLVE_EXAM request [$requestId] with ${uris.size} image URI(s). Preset: '$presetTitle'")

            fun sendStatus(msg: String) {
                bridge.log("OMNI_SOLVE_STATUS", "⚡ Status: $msg")
                try {
                    val statusIntent = Intent(statusAction).apply {
                        putExtra("extra_message", msg)
                        setPackage("com.universal.app")
                    }
                    ctx.sendBroadcast(statusIntent)
                } catch (e: Exception) {
                    bridge.log("OMNI_SOLVE_ERR", "Failed sending status broadcast: ${e.message}")
                }
            }

            fun sendResult(success: Boolean, json: String?, error: String?) {
                bridge.log("OMNI_SOLVE_RESULT", "🏁 Dispatching result (success=$success, error='$error', jsonLen=${json?.length ?: 0})")
                try {
                    val resultIntent = Intent(replyAction).apply {
                        putExtra("extra_success", success)
                        putExtra("extra_solution_json", json ?: "")
                        putExtra("extra_error", error ?: "")
                        setPackage("com.universal.app")
                    }
                    ctx.sendBroadcast(resultIntent)
                } catch (e: Exception) {
                    bridge.log("OMNI_SOLVE_ERR", "Failed sending result broadcast: ${e.message}")
                }
            }

            val imageCount = uris.size
            val imageText = if (imageCount == 1) "1 image" else "$imageCount images"
            sendStatus("Received $imageText. Preparing Omni.")

            val attachments = mutableListOf<AutomationAttachment>()
            for ((idx, uri) in uris.withIndex()) {
                try {
                    var name = "exam_image_$idx.jpg"
                    var size = 0L

                    ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                            if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                        }
                    }

                    val (bytes, finalMime) = optimizeImageForAiStudio(ctx, uri)
                    if (bytes.isNotEmpty()) {
                        size = bytes.size.toLong()
                        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        val safeName = if (finalMime == "image/jpeg" && !name.endsWith(".jpg", ignoreCase = true) && !name.endsWith(".jpeg", ignoreCase = true)) {
                            "${name.substringBeforeLast(".")}.jpg"
                        } else name
                        attachments.add(
                            AutomationAttachment(
                                name = safeName,
                                mimeType = finalMime,
                                sizeBytes = size,
                                base64Data = b64
                            )
                        )
                        bridge.log("OMNI_SOLVE_ATTACH", "✅ Optimized & ingested attachment [$idx]: $safeName ($size bytes, mime=$finalMime)")
                    } else {
                        bridge.log("OMNI_SOLVE_WARN", "⚠️ Empty byte stream for URI [$idx]: $uri")
                    }
                } catch (e: Exception) {
                    bridge.log("OMNI_SOLVE_ERR", "❌ Failed reading URI [$idx] ($uri): ${e.message}")
                }
            }

            if (attachments.isEmpty() && userPrompt.isBlank()) {
                bridge.log("OMNI_SOLVE_ERR", "❌ No valid image attachments or prompt provided.")
                sendResult(false, null, "No readable exam images found")
                return
            }

            val vaultManager = VaultManager(ctx, bridge)
            vaultManager.resurrectFromVault()

            val presets = vaultManager.loadSystemPresets() ?: emptyList()
            val matchedPreset = presets.find { it.title.equals(presetTitle.trim(), ignoreCase = true) }
            val finalSysTitle = matchedPreset?.title ?: presetTitle.trim()
            val finalSysPrompt = matchedPreset?.body ?: ""
            val profiles = vaultManager.loadProfiles() ?: emptyList()
            val analyticsMap = vaultManager.loadAiAnalytics()
            val nowMs = System.currentTimeMillis()
            val nonDefaultProfiles = profiles.filter { profile ->
                !profile.id.equals("default", ignoreCase = true) &&
                !profile.name.trim().equals("default", ignoreCase = true)
            }
            val verifiedProfiles = nonDefaultProfiles.filter { !(analyticsMap[it.id]?.needsVerification ?: false) }
            val freshProfiles = verifiedProfiles.filter { prof ->
                val qTime = analyticsMap[prof.id]?.lastQuotaHitTimestamp ?: 0L
                !isQuotaCurrentlyActive(qTime, nowMs)
            }

            if (nonDefaultProfiles.isEmpty()) {
                val noProfErr = "No secondary solver profile found in Omni Chrome. Main Default account is protected."
                bridge.log("OMNI_SOLVE_ERR", "❌ Abort: $noProfErr")
                sendStatus("No secondary profile found. Create an account in Omni Chrome.")
                sendResult(false, null, noProfErr)
                return
            }

            if (verifiedProfiles.isEmpty()) {
                val verifyErr = "All secondary solver profiles require Google verification. Approve one in Omni Chrome Health Deck."
                bridge.log("OMNI_SOLVE_ERR", "🔒 Abort: $verifyErr")
                sendStatus("All solver profiles locked for verification.")
                sendResult(false, null, verifyErr)
                return
            }

            if (freshProfiles.isEmpty()) {
                val quotaErr = "All verified secondary solver profiles have exhausted today's quota (resets at PT midnight)."
                bridge.log("OMNI_SOLVE_ERR", "🔴 Abort: $quotaErr")
                sendStatus("All solver profiles quota-exhausted today.")
                sendResult(false, null, quotaErr)
                return
            }

            val prefs = ctx.getSharedPreferences("omni_solver_prefs", Context.MODE_PRIVATE)
            val lastIdx = prefs.getInt("solver_profile_round_robin_idx", -1)
            val startIdx = (lastIdx + 1) % freshProfiles.size
            val rotatedCandidates = (0 until freshProfiles.size).map { offset ->
                freshProfiles[(startIdx + offset) % freshProfiles.size]
            }
            val solverModel = (payload["model"] as? String)?.ifBlank { "Gemini 3.7 Flash" } ?: "Gemini 3.7 Flash"
            solverAbortFlag = false

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                fun attemptSolve(candidateIdx: Int, lastErr: String?) {
                    if (solverAbortFlag) {
                        bridge.log("OMNI_SOLVE_ERROR", "🛑 Halted solve rotation because ABORT_SOLVE was triggered.")
                        return
                    }
                    if (candidateIdx >= rotatedCandidates.size) {
                        val finalErr = lastErr ?: "All eligible solver profiles failed."
                        bridge.log("OMNI_SOLVE_ERROR", "💥 Exhausted all ${rotatedCandidates.size} candidate profile(s). Final error: $finalErr")
                        sendResult(false, null, finalErr)
                        return
                    }

                    val targetProfile = rotatedCandidates[candidateIdx]
                    val targetProfileId = targetProfile.id
                    val liveAnalytic = vaultManager.loadAiAnalytics()[targetProfileId]
                    val curNow = System.currentTimeMillis()
                    if (liveAnalytic != null && (liveAnalytic.needsVerification || isQuotaCurrentlyActive(liveAnalytic.lastQuotaHitTimestamp, curNow))) {
                        bridge.log("OMNI_SOLVE_ROTATE", "⏭️ Skipping '${targetProfile.name}' ($targetProfileId) — newly locked/quota-hit. Advancing...")
                        attemptSolve(candidateIdx + 1, lastErr)
                        return
                    }

                    prefs.edit().putInt("solver_profile_round_robin_idx", (startIdx + candidateIdx) % freshProfiles.size).apply()
                    bridge.log("OMNI_SOLVE_PRESET", "Using preset: '$finalSysTitle' (${finalSysPrompt.length} chars), Profile: '${targetProfile.name}' ($targetProfileId) [candidate ${candidateIdx + 1}/${rotatedCandidates.size}]")

                    val automator = AiStudioAutomator(ctx, bridge)
                    activeSolverAutomator = automator
                    val steps = listOf(
                        SequentialPromptStep(
                            prompt = userPrompt,
                            repeatCount = 1,
                            isInfinite = false,
                            attachments = attachments
                        )
                    )

                    sendStatus("Connecting to Omni solver on ${targetProfile.name}.")
                    bridge.log("OMNI_SOLVE_EXEC", "Starting headless AI Studio automator on ${targetProfile.name} ($targetProfileId)...")

                    automator.start(
                        profileId = targetProfileId,
                        steps = steps,
                        userPrompt = userPrompt,
                        systemPromptTitle = finalSysTitle,
                        systemPrompt = finalSysPrompt,
                        thinkingLevel = "Default",
                        model = solverModel,
                        fallbackEnabled = true,
                        temporaryChat = false,
                        attachments = emptyList(),
                        containerLayout = null,
                        callback = object : AutomationCallback {
                            private var lastProgressBroadcastTime = 0L

                            override fun onStatus(msg: String) {
                                sendStatus(msg)
                            }

                            override fun onProgress(thoughts: String, output: String) {
                                bridge.log("OMNI_SOLVE_PROGRESS", "Streaming response (${output.length} chars, thoughts: ${thoughts.length} chars)")
                                val now = System.currentTimeMillis()
                                // Pulse live streaming heartbeat every 8 seconds to reset client Dead Man Switch
                                if (now - lastProgressBroadcastTime > 8000L) {
                                    lastProgressBroadcastTime = now
                                    val statusMessage = if (output.isEmpty() && thoughts.isNotEmpty()) {
                                        "Thinking and analyzing questions."
                                    } else {
                                        "Generating answers."
                                    }
                                    sendStatus(statusMessage)
                                }
                            }

                            override fun onComplete(thoughts: String, output: String) {
                                bridge.log("OMNI_SOLVE_SUCCESS", "🎉 AI Studio returned solution (${output.length} chars)")
                                bridge.log("OMNI_SOLVE_AUDIT", "Head (first 200 chars): ${output.take(200).replace("\n", " ")}")
                                bridge.log("OMNI_SOLVE_AUDIT", "Tail (last 200 chars): ${output.takeLast(200).replace("\n", " ")}")
                                bridge.log("OMNI_SOLVE_FULL_DUMP", "=== [DISPATCHING SOLUTION TO UNIVERSAL (${output.length} chars)] ===\n$output\n=== [END DISPATCH] ===")
                                sendResult(true, output, null)
                                automator.stop(null)
                                if (activeSolverAutomator == automator) activeSolverAutomator = null
                            }

                            override fun onError(err: String) {
                                if (automator.isAborted || solverAbortFlag) {
                                    bridge.log("OMNI_SOLVE_ERROR", "🛑 Suppressed error callback because automator was ABORTED.")
                                    return
                                }
                                bridge.log("OMNI_SOLVE_ERROR", "💥 AI Studio automator reported error on '${targetProfile.name}': $err")
                                automator.stop(null)
                                if (activeSolverAutomator == automator) activeSolverAutomator = null

                                val lowerErr = err.lowercase()
                                val isQuotaErr = lowerErr.contains("quota") ||
                                    lowerErr.contains("429") ||
                                    lowerErr.contains("resource exhausted") ||
                                    lowerErr.contains("resource_exhausted") ||
                                    lowerErr.contains("rate limit")
                                val isVerifyErr = lowerErr.contains("verification required") ||
                                    lowerErr.contains("verify-account") ||
                                    lowerErr.contains("must be verified") ||
                                    lowerErr.contains("unusual activity")
                                val isPermErr = lowerErr.contains("permission denied") ||
                                    lowerErr.contains("caller does not have")

                                if (isQuotaErr) {
                                    val existingQTime = vaultManager.loadAiAnalytics()[targetProfileId]?.lastQuotaHitTimestamp ?: 0L
                                    if (System.currentTimeMillis() - existingQTime > 15_000L) {
                                        VaultManager.recordQuotaHit(ctx, bridge, targetProfileId, err)
                                    }
                                } else if (isVerifyErr) {
                                    val alreadyLocked = vaultManager.loadAiAnalytics()[targetProfileId]?.needsVerification ?: false
                                    if (!alreadyLocked) {
                                        VaultManager.recordVerificationLock(ctx, bridge, targetProfileId, err)
                                    }
                                }

                                val isRotatable = isQuotaErr || isVerifyErr || isPermErr
                                if (isRotatable && (candidateIdx + 1) < rotatedCandidates.size) {
                                    val nextProf = rotatedCandidates[candidateIdx + 1]
                                    bridge.log("OMNI_SOLVE_ROTATE", "↪️ Rotating from '${targetProfile.name}' to '${nextProf.name}' [${candidateIdx + 2}/${rotatedCandidates.size}]")
                                    sendStatus("${targetProfile.name} unavailable; switching to ${nextProf.name}.")
                                    attemptSolve(candidateIdx + 1, err)
                                } else {
                                    sendResult(false, null, err)
                                }
                            }
                        }
                    )
                }

                attemptSolve(0, null)
            }
        }
    }
    override fun onCreateView(context: Context, bridge: HostBridge, baseDir: String): View {
        bridge.log("OMNI_TRACE", "🌐 [BROWSER_INIT] OmniBrowser.onCreateView invoked!")
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
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
        val context = LocalContext.current
        val focusManager = LocalFocusManager.current
        val coroutineScope = rememberCoroutineScope()

        val state = remember {
            BrowserStateHolder(context, bridge, coroutineScope).also {
                activeBrowserStateRef = java.lang.ref.WeakReference(it)
            }
        }

        // --- Activity Launchers ---
        var restoreCallback by remember { mutableStateOf<((android.net.Uri) -> Unit)?>(null) }
        val backupPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri -> if (uri != null) restoreCallback?.invoke(uri) }

        val fileChooserLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            var uris: Array<android.net.Uri>? = null
            if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                val clip = result.data?.clipData
                if (clip != null && clip.itemCount > 0) {
                    val list = mutableListOf<android.net.Uri>()
                    for (i in 0 until clip.itemCount) {
                        clip.getItemAt(i).uri?.let { list.add(it) }
                    }
                    if (list.isNotEmpty()) uris = list.toTypedArray()
                } else if (result.data?.data != null) {
                    uris = arrayOf(result.data!!.data!!)
                }
            }
            if (uris == null) {
                uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            }
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                state.recordDomainUploadPath(state.currentUrl, result.data)
            }
            state.bridge.log("FILE_CHOOSER", "📥 Picked ${uris?.size ?: 0} URI(s) (resultCode=${result.resultCode})")
            state.activeFileChooserCallback?.onReceiveValue(uris)
            state.activeFileChooserCallback = null
        }

        DisposableEffect(fileChooserLauncher) {
            state.registerFileChooserLauncher { intent ->
                fileChooserLauncher.launch(intent)
            }
            onDispose {
                state.registerFileChooserLauncher(null)
            }
        }

        // --- Lifecycle and Background Loops ---
        LaunchedEffect(Unit) {
            bridge.log("OMNI_TRACE", "🌐 [BROWSER_MOUNT] ChromeBrowserScreen mounted & state.init() running!")
            state.init()
        }

        val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    state.checkAndConsumePendingUrl()
                }
            }
            val filter = IntentFilter("com.omni.hub.action.BROWSER_OPEN_URL")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }
            } catch (e: Exception) {}

            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                        state.exitBackgroundExecutionMode()
                        state.checkAndConsumePendingUrl()
                    }
                    androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                        state.enterBackgroundExecutionMode()
                    }
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)

            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
            }
        }

        LaunchedEffect(state.trackedDownloadIds.size, state.showDownloadsDialog) {
            while (state.trackedDownloadIds.isNotEmpty() || state.showDownloadsDialog) {
                state.pollDownloads()
                delay(1000)
            }
        }

        LaunchedEffect(state.isAutomating) {
            while (state.isAutomating) {
                delay(1000)
                state.automationElapsedSec++
            }
        }

        DisposableEffect(
            state.isTabSwitcherOpen, state.showSettingsDialog, state.showDownloadsDialog,
            state.showAutomationDialog, state.showAutomationResultDialog, state.editingProfile,
            state.editingShortcut, state.isAddingShortcut, state.isHomeOverlayOpen,
            state.canGoBack, state.currentUrl, state.currentWebView, state.showMenu,
            state.showDownloadBanner, state.customFullscreenView, state.isBrowserImmersiveMode
        ) {
            bridge.setOnBackPressedHandler { state.handleBackPressed() }
            onDispose { bridge.setOnBackPressedHandler(null) }
        }

        DisposableEffect(Unit) {
            onDispose {
                bridge.setImmersiveMode(false)
                bridge.setScreenOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            }
        }

        // --- UI Rendering ---
        val activeTab = state.tabs.find { it.id == state.activeTabId }
        val activeProf = state.profiles.find { it.id == activeTab?.profileId } ?: state.profiles.firstOrNull() ?: BrowserProfile("default", "Default", 0xFF2979FF)
        val profColor = remember(activeProf.colorValue) { Color(activeProf.colorValue) }
        val rootView = androidx.compose.ui.platform.LocalView.current
        var isImeLayoutVisible by remember { mutableStateOf(false) }
        DisposableEffect(rootView) {
            val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
                val rect = android.graphics.Rect()
                rootView.getWindowVisibleDisplayFrame(rect)
                val screenHeight = rootView.rootView.height
                val keypadHeight = screenHeight - rect.bottom
                isImeLayoutVisible = screenHeight > 0 && keypadHeight > screenHeight * 0.15f
            }
            rootView.viewTreeObserver.addOnGlobalLayoutListener(listener)
            onDispose {
                rootView.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            }
        }
        val isImeVisible = isImeLayoutVisible || (WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0)

        fun dispatchSlotAction(slotId: String) {
            when (slotId) {
                "quick_toggle" -> state.togglePreviousTab()
                "tabs" -> state.openTabSwitcher(focusManager)
                "menu" -> {
                    state.refreshSiteCacheSize()
                    state.showMenu = !state.showMenu
                }
                "home" -> {
                    if (state.currentUrl != "about:blank") {
                        state.isHomeOverlayOpen = !state.isHomeOverlayOpen
                    }
                }
                "back" -> {
                    if (state.canGoBack) state.currentWebView?.goBack()
                    else if (state.currentUrl != "about:blank") state.navigateTo("about:blank")
                }
                "forward" -> state.currentWebView?.goForward()
                "reload" -> state.reloadCurrentPage()
                "new_tab" -> state.createNewTab()
                "smart_notes" -> state.openSmartNotes()
                "downloads" -> {
                    state.refreshCompletedDownloads()
                    state.showDownloadsDialog = true
                }
                "force_dark" -> state.toggleForceDark()
                "immersive_mode" -> state.toggleBrowserImmersiveMode()
                "data_saver" -> state.toggleDataSaver()
                "site_cache" -> state.toggleCurrentSiteCache()
                "page_zoom" -> state.showZoomBar = true
                "favorite_tab" -> state.toggleTabFavorite(state.activeTabId)
                "focus_lock" -> state.toggleFocusLock()
                "copy_url" -> if (state.currentUrl != "about:blank") bridge.copyToClipboard(sanitizeUrlForCopy(state.currentUrl))
                "dom_snapshot" -> {
                    state.currentWebView?.evaluateJavascript(DOM_SNAPSHOT_SCRIPT) { html ->
                        if (!html.isNullOrEmpty()) {
                            val rawHtml = if (html.startsWith("\"") && html.endsWith("\"")) {
                                try { org.json.JSONObject("{\"h\":$html}").getString("h") } catch (e: Exception) { html }
                            } else html
                            saveHtmlSnapshot(context, bridge, rawHtml, "DOM_Dump")
                        }
                    }
                }
                "full_source" -> state.captureFullWebSource()
                "local_ide" -> state.openLocalIdeAsNeighbor()
                "autopilot" -> state.toggleLiveAutoPilot()
                "studio_lab" -> state.showStudioLabDialog = true
                "ai_automator" -> state.showAutomationDialog = true
                "recents" -> bridge.showRecents()
                "diagnostics" -> bridge.showDiagnostics()
                "settings" -> state.showSettingsDialog = true
                "exit" -> {
                    state.showMenu = false
                    coroutineScope.launch {
                        delay(300)
                        bridge.close()
                    }
                }
            }
        }

        fun dispatchSlotLongClick(slotId: String) {
            when (slotId) {
                "menu" -> {
                    bridge.vibrate(25L)
                    state.isBottomBarCollapsed = !state.isBottomBarCollapsed
                }
                "quick_toggle" -> state.switchToThirdRecentTab()
                "tabs" -> state.togglePreviousTab()
                "site_cache" -> state.clearCurrentSiteCache()
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    object : FrameLayout(ctx) {
                        override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean = false
                        override fun onInterceptTouchEvent(ev: android.view.MotionEvent?): Boolean = true
                    }.apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        alpha = 0.004f
                        isClickable = false
                        isFocusable = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        state.foregroundHeadlessContainer = this
                        state.syncHeadlessWebViewAnchors()
                    }
                },
                update = { frame ->
                    if (state.foregroundHeadlessContainer !== frame) {
                        state.foregroundHeadlessContainer = frame
                    }
                    if (!state.isBackgroundOverlayHosting &&
                        frame.childCount == 0 &&
                        com.omni.plugin.browser.engine.AiStudioAutomator.liveHeadlessWebViews.isNotEmpty()
                    ) {
                        state.syncHeadlessWebViewAnchors()
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.004f }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1F2227))
                    .then(if (state.isBrowserImmersiveMode) Modifier else Modifier.statusBarsPadding())
                    .imePadding()
            ) {
                if (!state.isBrowserImmersiveMode) {
                    OmniBrowserTopBar(
                        currentUrl = state.currentUrl,
                        urlInputText = state.urlInputText,
                        onUrlTextChange = {
                            state.onSearchInputChanged(it)
                        },
                        onNavigate = {
                            state.navigateTo(it)
                            state.dismissSearch()
                            focusManager.clearFocus()
                        },
                        profColor = profColor,
                        tabCount = state.tabs.size,
                        topLeftItems = state.topLeftItems,
                        topBarItems = state.topBarItems,
                        isHomeOverlayOpen = state.isHomeOverlayOpen,
                        isSearchFocused = state.isSearchFocused,
                        onSearchFocusChange = { focused ->
                            if (focused && !state.isTabSwitcherOpen) {
                                state.isSearchFocused = true
                                state.onSearchInputChanged(state.urlInputText)
                            } else {
                                state.dismissSearch()
                            }
                        },
                        onActionClick = { dispatchSlotAction(it) },
                        onActionLongClick = { dispatchSlotLongClick(it) },
                        isAutoPilotActive = state.isLiveAutoPilotEnabled,
                        isDataSaverActive = state.isEffectiveDataSaver(),
                        forceDarkWebPages = state.forceDarkWebPages,
                        isBrowserImmersiveActive = state.isBrowserImmersiveMode,
                        dataSaverMode = state.dataSaverMode,
                        isCurrentTabFavorite = activeTab?.isFavorite == true,
                        isFocusLockActive = isFocusLockActive(context),
                        activeDownloadsCount = state.trackedDownloadIds.size,
                        canGoBack = state.canGoBack,
                        canGoForward = state.canGoForward,
                        onTopBarDragStart = { state.onTopBarDragStart() },
                        onTopBarDrag = { state.onTopBarDrag(it) },
                        onTopBarDragEnd = { state.onTopBarDragEnd() },
                        onTopBarDragCancel = { state.onTopBarDragCancel() }
                    )
                }

                AnimatedVisibility(
                    visible = state.isLoading,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    LinearProgressIndicator(
                        progress = { state.loadProgress },
                        color = profColor,
                        trackColor = Color(0xFF282C34),
                        modifier = Modifier.fillMaxWidth().height(3.dp)
                    )
                }

                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val screenW = constraints.maxWidth.toFloat()
                    LaunchedEffect(screenW) {
                        state.swipeScreenWidth = screenW
                    }

                    // Active Tab Container (Translates smoothly with thumb drag)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = state.tabSwipeOffset.value
                            }
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                FrameLayout(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                    state.containerLayout = this
                                    state.attachTabWebView(state.activeTabId)
                                }
                            },
                            update = { frame ->
                                val targetVisibility = if (state.isTabSwitcherOpen || state.showSettingsDialog) android.view.View.INVISIBLE else android.view.View.VISIBLE
                                if (frame.visibility != targetVisibility) {
                                    frame.visibility = targetVisibility
                                    state.bridge.log("TOUCH_SHIELD", "WebView container visibility -> ${if (state.isTabSwitcherOpen) "INVISIBLE (Shielded from touch)" else "VISIBLE"}")
                                }
                                if (!state.isBackgroundOverlayHosting && state.containerLayout != null && state.currentWebView == null) {
                                    state.attachTabWebView(state.activeTabId)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        if (state.currentUrl == "about:blank" || state.isHomeOverlayOpen) {
                            SpeedDialView(
                                currentUrl = state.currentUrl,
                                isHomeOverlayOpen = state.isHomeOverlayOpen,
                                activeProfile = activeProf,
                                profColor = profColor,
                                shortcuts = state.shortcuts,
                                faviconCache = state.faviconCache,
                                onFetchFavicon = { state.fetchFavicon(it) },
                                onReturnToLivePage = {
                                    state.isHomeOverlayOpen = false
                                    if (state.currentUrl != "about:blank") {
                                        state.urlInputText = state.currentUrl
                                    }
                                },
                                onShortcutClick = { item ->
                                    val targetUrl = if (isLocalFilePath(item.url) || item.localSourcePath != null || item.url.contains("/ide/") || item.title.contains("IDE", ignoreCase = true)) {
                                        state.resolveIdeUrl(item)
                                    } else item.url

                                    val targetProfId = if (item.isGlobalProfile) "default" else state.selectedProfileId

                                    if (state.isHomeOverlayOpen && state.currentUrl != "about:blank") {
                                        state.isHomeOverlayOpen = false
                                        state.createNewTab(targetUrl = targetUrl, targetProfileId = targetProfId)
                                    } else if (item.isGlobalProfile && activeProf.id != "default") {
                                        state.createNewTab(targetUrl = targetUrl, targetProfileId = "default")
                                    } else {
                                        state.navigateTo(targetUrl)
                                    }
                                },
                                onShortcutLongClick = { item ->
                                    bridge.vibrate(40L)
                                    state.editingShortcut = item
                                },
                                onAddShortcutClick = { state.isAddingShortcut = true }
                            )
                        }
                    }

                    // Interactive Incoming Neighbor Tab Preview (Translates alongside active tab)
                    if (state.isTabSwiping && state.swipeTargetTab != null) {
                        val targetTab = state.swipeTargetTab!!
                        val isFromRight = state.tabSwipeOffset.value < 0
                        val incomingBaseX = if (isFromRight) screenW else -screenW
                        val targetProf = state.profiles.find { it.id == targetTab.profileId } ?: state.profiles.firstOrNull() ?: BrowserProfile("default", "Default", 0xFF2979FF)

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = incomingBaseX + state.tabSwipeOffset.value
                                }
                                .background(Color(0xFF16181D))
                        ) {
                            if (targetTab.thumbnail != null && targetTab.url != "about:blank") {
                                Image(
                                    bitmap = targetTab.thumbnail!!.asImageBitmap(),
                                    contentDescription = targetTab.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                SpeedDialView(
                                    currentUrl = "about:blank",
                                    isHomeOverlayOpen = true,
                                    activeProfile = targetProf,
                                    profColor = Color(targetProf.colorValue),
                                    shortcuts = state.shortcuts,
                                    faviconCache = state.faviconCache,
                                    onFetchFavicon = { state.fetchFavicon(it) },
                                    onReturnToLivePage = {},
                                    onShortcutClick = {},
                                    onShortcutLongClick = {},
                                    onAddShortcutClick = {}
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFF161B22).copy(alpha = 0.94f),
                                border = BorderStroke(1.dp, Color(targetProf.colorValue)),
                                shadowElevation = 8.dp,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 16.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(targetProf.colorValue))
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = targetProf.name,
                                        color = Color(targetProf.colorValue),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "• ${if (targetTab.url == "about:blank") "Speed Dial" else targetTab.title.take(18)}",
                                        color = Color(0xFFE8EAED),
                                        fontSize = 12.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                if (!isImeVisible && !state.isBottomBarCollapsed && !state.isBrowserImmersiveMode) {
                    OmniBottomBar(
                        bottomBarItems = state.bottomBarItems,
                        profColor = profColor,
                        tabCount = state.tabs.size,
                        currentUrl = state.currentUrl,
                        canGoBack = state.canGoBack,
                        canGoForward = state.canGoForward,
                        isHomeOverlayOpen = state.isHomeOverlayOpen,
                        isAutoPilotActive = state.isLiveAutoPilotEnabled,
                        forceDarkWebPages = state.forceDarkWebPages,
                        isBrowserImmersiveActive = state.isBrowserImmersiveMode,
                        dataSaverMode = state.dataSaverMode,
                        isCurrentTabFavorite = activeTab?.isFavorite == true,
                        isFocusLockActive = isFocusLockActive(context),
                        activeDownloadsCount = state.trackedDownloadIds.size,
                        showMenu = state.showMenu,
                        onItemClick = { dispatchSlotAction(it) },
                        onItemLongClick = { dispatchSlotLongClick(it) }
                    )
                }
            }

            // --- Overlays & Screens ---
                            BrowserMenuOverlay(
                    showMenu = state.showMenu,
                    onDismiss = { state.showMenu = false },
                    canGoBack = state.canGoBack,
                    canGoForward = state.canGoForward,
                    currentUrl = state.currentUrl,
                                        dataSaverMode = state.dataSaverMode,
                    onToggleDataSaver = { state.toggleDataSaver() },
                    currentZoomPercent = Math.round(state.getCurrentDomainZoom() * 100f),
                    onOpenZoom = { state.showZoomBar = true },
                    isSiteCached = state.isCurrentSiteCached(),
                    siteCacheSizeText = state.siteCacheManager.formatSize(state.currentSiteCacheSize),
                    onToggleSiteCache = { state.toggleCurrentSiteCache() },
                    onClearSiteCache = { state.clearCurrentSiteCache() },
                    isCurrentTabFavorite = activeTab?.isFavorite == true,
                    onToggleFavoriteTab = { state.toggleTabFavorite(state.activeTabId) },
                    forceDarkWebPages = state.forceDarkWebPages,
                    onToggleForceDark = { state.toggleForceDark() },
                    isBrowserImmersiveActive = state.isBrowserImmersiveMode,
                    onToggleImmersiveMode = { state.toggleBrowserImmersiveMode() },
                    isFocusLockActive = isFocusLockActive(context),
                    onToggleFocusLock = { state.toggleFocusLock() },
                    onBackClick = {
                    if (state.canGoBack) state.currentWebView?.goBack()
                    else if (state.currentUrl != "about:blank") state.navigateTo("about:blank")
                },
                onForwardClick = { state.currentWebView?.goForward() },
                onReloadClick = {
                    if (state.currentWebView != null && state.currentUrl != "about:blank") {
                        val wvUrl = state.currentWebView?.url
                        if (wvUrl == null || wvUrl == "about:blank" || wvUrl.isEmpty()) {
                            state.currentWebView?.loadUrl(state.currentUrl)
                        } else {
                            state.currentWebView?.reload()
                        }
                    }
                },
                onNewTab = { state.createNewTab() },
                onOpenTabSwitcher = { state.openTabSwitcher(focusManager) },
                onQuickToggleTab = { state.togglePreviousTab() },
                onHomeClick = {
                    if (state.currentUrl != "about:blank") {
                        state.isHomeOverlayOpen = !state.isHomeOverlayOpen
                    }
                },
                onCopyCleanUrl = {
                    if (state.currentUrl != "about:blank") {
                        val cleanUrl = sanitizeUrlForCopy(state.currentUrl)
                        bridge.copyToClipboard(cleanUrl)
                    }
                },
                onCaptureDomSnapshot = {
                    state.currentWebView?.evaluateJavascript(DOM_SNAPSHOT_SCRIPT) { html ->
                        if (!html.isNullOrEmpty()) {
                            val rawHtml = if (html.startsWith("\"") && html.endsWith("\"")) {
                                try { org.json.JSONObject("{\"h\":$html}").getString("h") } catch (e: Exception) { html }
                            } else html
                            saveHtmlSnapshot(context, bridge, rawHtml, "DOM_Dump")
                        }
                    }
                },
                onCaptureFullSource = {
                    state.captureFullWebSource()
                },
                isLiveAutoPilotEnabled = state.isLiveAutoPilotEnabled,
                onToggleLiveAutoPilot = { state.toggleLiveAutoPilot() },
                isAutoPilotConfirmEnabled = state.isAutoPilotConfirmEnabled,
                onToggleAutoPilotConfirm = { state.toggleAutoPilotConfirm() },
                activeMenuItems = state.activeMenuItems,
                onOpenLocalIde = {
                    state.openLocalIdeAsNeighbor()
                },
                onOpenAutomation = { state.showAutomationDialog = true },
                onOpenSmartNotes = { state.openSmartNotes() },
                onOpenStudioLab = { state.showStudioLabDialog = true },
                activeDownloadsCount = state.trackedDownloadIds.size,
                onOpenDownloads = {
                    state.refreshCompletedDownloads()
                    state.showDownloadsDialog = true
                },
                onShowRecents = {
                    state.showMenu = false
                    bridge.showRecents()
                },
                onShowDiagnostics = {
                    state.showMenu = false
                    bridge.showDiagnostics()
                },
                onOpenSettings = { state.showSettingsDialog = true },
                onCustomizeLayout = { state.showMenuCustomizerDialog = true },
                onExitBrowser = {
                    state.showMenu = false
                    coroutineScope.launch {
                        delay(400)
                        bridge.close()
                    }
                }
            )

            if (state.isTabSwitcherOpen) {
                SideEffect {
                    bridge.log("OMNI_TRACE", "🎭 [SWITCHER_TREE] TabSwitcherScreen rendered in Compose tree! activeTabId='${state.activeTabId}'")
                }
                TabSwitcherScreen(
                    bridge = bridge,
                    tabs = state.tabs,
                    activeTabId = state.activeTabId,
                    profiles = state.profiles,
                    selectedProfileId = state.selectedProfileId,
                    tabGroups = state.tabGroups,
                    isSelectionMode = state.isSelectionMode,
                    selectedTabIds = state.selectedTabIds,
                    showGroupNamingDialog = state.showGroupNamingDialog,
                    onEnterSelectionMode = { state.enterSelectionMode(it) },
                    onToggleTabSelection = { state.toggleTabSelection(it) },
                    onSelectAllTabs = { state.selectAllTabs() },
                    onClearSelectionMode = { state.clearSelectionMode() },
                    onPromptGroupSelection = { state.promptGroupSelection() },
                    onConfirmCreateGroup = { title, color -> state.confirmCreateGroup(title, color) },
                    onDismissGroupNamingDialog = { state.showGroupNamingDialog = false },
                    onRestoreTabGroup = { state.restoreTabGroup(it) },
                    onRestoreSingleGroupedTab = { gId, item -> state.restoreSingleGroupedTab(gId, item) },
                    onDeleteTabGroup = { state.deleteTabGroup(it) },
                    onReorderProfiles = { from, to -> state.reorderProfiles(from, to) },
                    onSelectProfile = {
                        state.changeSelectedProfile(it)
                    },
                    onAddProfile = {
                        val nextIdx = state.profiles.size
                        val newId = "profile_${System.currentTimeMillis()}"
                        val newColor = PROFILE_PALETTE[nextIdx % PROFILE_PALETTE.size]
                        val newProf = BrowserProfile(newId, "Account ${nextIdx + 1}", newColor)
                        state.profiles = state.profiles + newProf
                        state.changeSelectedProfile(newId)
                    },
                    onRenameProfile = { prof -> state.editingProfile = prof },
                    onDeleteProfile = { prof -> state.deleteProfile(prof) },
                    aiAnalytics = state.aiAnalytics,
                    onClearQuotaHit = { state.clearQuotaHit(it) },
                    onClearVerificationLock = { state.clearVerificationLock(it) },
                    onSelectTab = { state.switchToTab(it) },
                    onCloseTab = { state.closeTab(it) },
                    onNewTab = { profId -> state.createNewTab(targetProfileId = profId) },
                    onCloseAll = { state.closeAllTabs() },
                    onCloseSwitcher = {
                        state.clearSelectionMode()
                        state.isTabSwitcherOpen = false
                    },
                    onToggleFavorite = { state.toggleTabFavorite(it) },
                    onFavoriteSelectedTabs = { state.favoriteSelectedTabs() },
                    onReorderTabs = { from, to -> state.reorderTabs(from, to) },
                    onSaveTabOrder = { state.saveCurrentTabOrder() },
                    modifier = Modifier.fillMaxSize().zIndex(10f)
                )
            }
            if (state.showSettingsDialog) {
                SettingsScreen(
                    bridge = bridge,
                    apiKey = state.solverApiKey,
                    autoSolve = state.autoSolveEnabled,
                    forceDark = state.forceDarkWebPages,
                    localPort = state.localServerPort,
                    isErudaEnabled = state.isErudaEnabled,
                    isDesktopMode = state.isDesktopMode,
                    isBackgroundAudioEnabled = state.isBackgroundAudioEnabled,
                    dataSaverMode = state.dataSaverMode,
                    filePickerProvider = state.filePickerProvider,
                    blockAiStudioBack = state.blockAiStudioBack,
                    geminiApiKey = state.geminiApiKey,
                    autoReadAloud = state.autoReadAloud,
                    geminiVoiceName = state.geminiVoiceName,
                    readAloudEnabled = state.readAloudEnabled,
                    showReadAloudPill = state.showReadAloudPill,
                    onExportBackup = { state.exportBackup() },
                    onRestoreBackup = {
                        restoreCallback = { uri -> state.restoreBackup(uri) }
                        backupPickerLauncher.launch("application/zip")
                    },
                    onSolveNow = { state.solveCurrentCaptcha() },
                    onClearCookiesAndCache = {
                        CookieManager.getInstance().removeAllCookies(null)
                        state.currentWebView?.clearCache(true)
                        bridge.showToast("Cookies and Cache cleared.")
                    },
                    onCustomizeMenu = {
                        state.showSettingsDialog = false
                        state.showMenuCustomizerDialog = true
                    },
                    onApplyAll = { key, auto, dark, port, eruda, desktop, audio, mode, picker, blockBack, gemKey, autoRead, gemVoice, readEnabled, showPill ->
                        val darkChanged = dark != state.forceDarkWebPages
                        val erudaTurnedOn = eruda && !state.isErudaEnabled
                        val audioTurnedOff = !audio && state.isBackgroundAudioEnabled

                        state.solverApiKey = key
                        state.autoSolveEnabled = auto
                        state.forceDarkWebPages = dark
                        state.localServerPort = port
                        state.isErudaEnabled = eruda
                        state.isDesktopMode = desktop
                        state.isBackgroundAudioEnabled = audio
                        state.dataSaverMode = mode
                        state.filePickerProvider = picker
                        state.blockAiStudioBack = blockBack
                        state.geminiApiKey = gemKey
                        state.autoReadAloud = autoRead
                        state.geminiVoiceName = gemVoice
                        state.readAloudEnabled = readEnabled
                        state.showReadAloudPill = showPill

                        if (!readEnabled) {
                            state.stopVoiceReadAloud()
                        }
                        if (audioTurnedOff) {
                            bridge.stopMediaPlayback()
                            state.isMediaPlaying = false
                        }
                        state.poolManager.localPort = port
                        if (darkChanged) {
                            state.poolManager.updateForceDark(dark)
                        }
                        state.syncDataSaver()
                        state.syncVoiceStateToActiveWebView()
                        if (desktop != state.tabs.find { it.id == state.activeTabId }?.isDesktop) {
                            state.toggleDesktopMode()
                        }
                        if (erudaTurnedOn) {
                            state.injectEruda()
                        }
                        state.vaultManager.saveSolverConfig(
                            key, auto, dark, port, eruda, desktop, audio, mode, picker, blockBack,
                            state.activeMenuItems, state.hiddenMenuItems, gemKey, autoRead, gemVoice,
                            readEnabled, showPill
                        )
                    },
                    onClose = { state.showSettingsDialog = false },
                    modifier = Modifier.fillMaxSize().zIndex(45f)
                )
            }

        // --- Modals & Dialogs ---
        val hostActivity = context as? android.app.Activity
        val isHostAlive = hostActivity == null || (!hostActivity.isFinishing && !hostActivity.isDestroyed)

        if (isHostAlive) {
            if (state.showAutoPilotConfirmDialog && state.pendingAutoPilotPayload != null) {
                val rawPayload = state.pendingAutoPilotPayload!!
                val actionType = state.pendingAutoPilotActionType ?: "CXP"

                val (dialogIcon, dialogTitle, confirmLabel) = when (actionType) {
                    "PYTHON" -> Triple("🐍", "Auto-Pilot: Run Python", "Execute on Nexus")
                    "PULL" -> Triple("📂", "Auto-Pilot: Pull Files", "Deliver Files")
                    else -> Triple("🛡️", "Auto-Pilot: Commit Patch", "Commit to IDE")
                }

                val previewSummary = remember(rawPayload, actionType) {
                    when (actionType) {
                        "PYTHON" -> "Python Script (${rawPayload.lines().size} lines)"
                        "PULL" -> {
                            val count = Regex("<file\\b", RegexOption.IGNORE_CASE).findAll(rawPayload).count()
                            "Pull request for $count file(s)"
                        }
                        else -> {
                            val replaces = Regex("<replace_block\\b", RegexOption.IGNORE_CASE).findAll(rawPayload).count()
                            val creates = Regex("<create_file\\b", RegexOption.IGNORE_CASE).findAll(rawPayload).count()
                            val deletes = Regex("<delete_file\\b", RegexOption.IGNORE_CASE).findAll(rawPayload).count()
                            "Replaces: $replaces | Creates: $creates | Deletes: $deletes"
                        }
                    }
                }

                val projMatch = remember(rawPayload) {
                    Regex("(?:project|repo)=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(rawPayload)?.groupValues?.get(1) ?: "Default Workspace"
                }

                AlertDialog(
                    onDismissRequest = { state.rejectAutoPilotAction() },
                    containerColor = Color(0xFF1E2228),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(dialogIcon, fontSize = 20.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(dialogTitle, color = Color(0xFFE8EAED), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF16181D),
                                border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("Scope: $projMatch", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Spacer(Modifier.height(2.dp))
                                    Text(previewSummary, color = Color(0xFF9AA0A6), fontSize = 11.sp)
                                }
                            }
                            Text("Payload Preview:", color = Color(0xFFE8EAED), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF121418))
                                    .padding(8.dp)
                            ) {
                                androidx.compose.foundation.lazy.LazyColumn {
                                    item {
                                        Text(
                                            text = rawPayload,
                                            color = Color(0xFFD4D4D4),
                                            fontSize = 10.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { state.approveAutoPilotAction() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                        ) {
                            Text(confirmLabel, color = Color(0xFF121418), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { state.rejectAutoPilotAction() }) {
                            Text("Discard", color = Color(0xFFF28B82), fontSize = 12.sp)
                        }
                    }
                )
            }

            if (state.showStudioLabDialog) {
                com.omni.plugin.browser.ui.dialogs.StudioLabDialog(
                    bridge = bridge,
                    playbooks = state.playbooks,
                    onSavePlaybook = { title, desc, content, id -> state.savePlaybook(title, desc, content, id) },
                    onDeletePlaybook = { id -> state.deletePlaybook(id) },
                    onLaunchPlaybook = { pb -> state.launchPlaybook(pb) },
                    onDismiss = { state.showStudioLabDialog = false }
                )
            }

            if (state.showSmartNotesDialog) {
                    SmartNotesDialog(
                        bridge = bridge,
                        session = state.smartNotesSession,
                        notes = state.smartNotes,
                        folders = state.smartNoteFolders,
                        unlockedNoteIds = state.unlockedSmartNoteIds,
                        currentPageUrl = state.currentUrl,
                        currentPageTitle = state.pageTitle,
                        onUnlockNote = { note, pin -> state.verifySmartNotePin(note, pin) },
                        onSaveNote = { title, content, id, colorId, folderId, isPinned ->
                            state.saveSmartNote(title, content, id, colorId, folderId, isPinned)
                        },
                        onTogglePin = { id: String -> state.toggleSmartNotePin(id) },
                        onDeleteNote = { id: String -> state.deleteSmartNote(id) },
                        onInjectToPage = { text: String -> state.injectTextToActivePage(text) },
                        onDismiss = { state.showSmartNotesDialog = false }
                    )
                }

                if (state.showAutomationDialog) {
                    AutomationOrderDialog(
                        profiles = state.profiles,
                        selectedProfileId = state.autoSelectedProfileId,
                        onSelectProfileId = { state.autoSelectedProfileId = it },
                        thinkingLevel = state.autoThinkingLevel,
                        onThinkingLevelChange = { state.autoThinkingLevel = it },
                        temporaryChat = state.autoTemporaryChat,
                        onTemporaryChatChange = { state.autoTemporaryChat = it },
                        attachments = state.autoAttachments,
                        onPickFiles = {
                            bridge.pickFiles("*/*", true) { uris ->
                                state.attachFiles(uris)
                            }
                        },
                        onRemoveAttachment = { state.removeAttachment(it) },
                        systemPresets = state.systemPresets,
                        systemPromptTitle = state.autoSystemPromptTitle,
                        onSystemPromptTitleChange = { state.autoSystemPromptTitle = it },
                        systemPrompt = state.autoSystemPrompt,
                        onSystemPromptChange = { state.autoSystemPrompt = it },
                        fallbackEnabled = state.autoFallbackToLocalPreset,
                        onFallbackEnabledChange = { state.autoFallbackToLocalPreset = it },
                        onSavePreset = { t, b -> state.saveCurrentSystemPreset(t, b) },
                        onDeletePreset = { id -> state.deleteSystemPreset(id) },
                        onSelectPreset = { preset -> state.selectSystemPreset(preset) },
                        promptSteps = state.promptSteps,
                        onAddPromptStep = { state.addPromptStep() },
                        onUpdatePromptStep = { id, p, r, inf -> state.updatePromptStep(id, p, r, inf) },
                        onRemovePromptStep = { state.removePromptStep(it) },
                        userPrompt = state.autoUserPrompt,
                        onUserPromptChange = { state.autoUserPrompt = it },
                        onDismiss = { state.showAutomationDialog = false },
                        onRun = { state.startAutomation() }
                    )
                }

                if (state.showAutomationResultDialog) {
                    AutomationResultDialog(
                        isAutomating = state.isAutomating,
                        automationStatus = state.automationStatus,
                        automationThoughts = state.automationThoughts,
                        automationResult = state.automationResult,
                        automationError = state.automationError,
                        automationElapsedSec = state.automationElapsedSec,
                        automationWebView = state.automator.headlessWv,
                        onBackToStaging = {
                            state.showAutomationResultDialog = false
                            state.showAutomationDialog = true
                        },
                        onDumpDom = {
                            bridge.showToast("Dumping live DOM to Downloads/OmniSnapshots...")
                            state.automator.dumpDom { rawHtml ->
                                if (rawHtml != null) saveHtmlSnapshot(context, bridge, rawHtml, "Automator_DOM")
                            }
                        },
                        onCopyResult = {
                            bridge.copyToClipboard(state.automationResult)
                            bridge.showToast("Copied result to clipboard!")
                        },
                        onCloseOrStop = { state.stopAutomation() }
                    )
                }

                if (state.showMenuCustomizerDialog) {
                    MenuCustomizerDialog(
                        initialActive = state.activeMenuItems,
                        initialHidden = state.hiddenMenuItems,
                        initialBottomBar = state.bottomBarItems,
                        initialTopBar = state.topBarItems,
                        initialTopLeft = state.topLeftItems,
                        onSave = { aMenu, hMenu, bMenu, tMenu, tlMenu ->
                            val hasAnyMenuTrigger = ("menu" in bMenu) || ("menu" in tMenu) || ("menu" in tlMenu)
                            val safeBottom = if (hasAnyMenuTrigger) bMenu else (bMenu + "menu")
                            state.activeMenuItems = aMenu
                            state.hiddenMenuItems = hMenu
                            state.bottomBarItems = safeBottom
                            state.topBarItems = tMenu
                            state.topLeftItems = tlMenu
                            state.vaultManager.saveMenuLayout(aMenu, hMenu, safeBottom, tMenu, tlMenu)
                            bridge.showToast("Bars & menu layout updated!")
                            state.showMenuCustomizerDialog = false
                        },
                        onDismiss = { state.showMenuCustomizerDialog = false }
                    )
                }

                if (state.showDownloadsDialog) {
                    DownloadsManagerDialog(
                        bridge = bridge,
                        activeDownloadsList = state.activeDownloadsList,
                        completedFilesList = state.completedFilesList,
                        onCancelDownload = { id ->
                            (context.getSystemService(Context.DOWNLOAD_SERVICE) as? android.app.DownloadManager)?.remove(id)
                            state.trackedDownloadIds.remove(id)
                        },
                        onDeleteFile = { file ->
                            state.deleteDownloadedFile(file)
                        },
                        onAttachFile = { file ->
                            state.showDownloadsDialog = false
                            state.attachFileToCurrentWebPage(file)
                        },
                        onDismiss = { state.showDownloadsDialog = false }
                    )
                }



                if (state.editingShortcut != null) {
                    val targetItem = state.editingShortcut!!
                    EditShortcutDialog(
                        bridge = bridge,
                        shortcut = targetItem,
                        faviconCache = state.faviconCache,
                        onFetchFavicon = { state.fetchFavicon(it) },
                        onPickFile = { onPicked ->
                            bridge.pickFiles("*/*", false) { uris ->
                                val picked = uris.firstOrNull()
                                if (picked != null) {
                                    val pathStr = picked.path
                                    val directPath = if (pathStr != null && (pathStr.contains("/storage/") || pathStr.contains("/sdcard/"))) {
                                        pathStr.substring(pathStr.indexOf("/storage/").coerceAtLeast(pathStr.indexOf("/sdcard/")))
                                    } else picked.toString()
                                    onPicked(directPath)
                                }
                            }
                        },
                        onReadLocalCode = { urlOrPath ->
                            state.vaultManager.readLocalAppCode(urlOrPath, targetItem.id)
                        },
                        onDelete = {
                            val updated = state.shortcuts.filter { it.id != targetItem.id }
                            state.shortcuts = updated
                            state.vaultManager.saveShortcuts(updated)
                            try { File(bridge.getPluginDir(), "ide/vault_${targetItem.id}").deleteRecursively() } catch (e: Exception) {}
                            state.editingShortcut = null
                            bridge.showToast("Shortcut deleted")
                        },
                        onSave = { name, rawUrl, isDef, isGlobal, editedCode ->
                            val trimmedUrl = rawUrl.trim()
                            val effectiveSource = when {
                                isLocalFilePath(trimmedUrl) && !trimmedUrl.contains("localhost") -> trimmedUrl
                                !targetItem.localSourcePath.isNullOrEmpty() -> targetItem.localSourcePath
                                targetItem.url.contains("vault_") || targetItem.title.contains("IDE", true) -> targetItem.localSourcePath ?: "/storage/emulated/0/Download/F/index.html"
                                else -> null
                            }

                            val isLocal = effectiveSource != null || isLocalFilePath(trimmedUrl)
                            val (finalUrl, srcPath) = if (isLocal && effectiveSource != null) {
                                val isolatedSubPath = "ide/vault_${targetItem.id}/index.html"
                                val (success, err) = if (editedCode != null) {
                                    state.vaultManager.saveLocalAppCode(editedCode, isolatedSubPath, effectiveSource)
                                } else {
                                    state.vaultManager.syncLocalFileToVault(effectiveSource, isolatedSubPath)
                                }
                                if (success) {
                                    bridge.showToast(if (editedCode != null) "✅ Saved edited app code!" else "✅ Synced latest file from storage!")
                                } else {
                                    bridge.showToast("⚠️ Auto-sync warning: $err")
                                }
                                Pair("http://localhost:${state.localServerPort}/vault_${targetItem.id}/index.html", normalizeLocalFilePath(effectiveSource))
                            } else {
                                val webUrl = if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) "https://$trimmedUrl" else trimmedUrl
                                Pair(webUrl, null)
                            }

                            val updated = state.shortcuts.map {
                                if (it.id == targetItem.id) {
                                    it.copy(title = name.trim().ifEmpty { targetItem.title }, url = finalUrl, localSourcePath = srcPath, isDefault = isDef, isGlobalProfile = isGlobal)
                                } else if (isDef) {
                                    it.copy(isDefault = false)
                                } else it
                            }
                            state.shortcuts = updated
                            state.vaultManager.saveShortcuts(updated)
                            state.fetchFavicon(extractDomain(finalUrl))
                            state.editingShortcut = null
                        },
                        onDismiss = { state.editingShortcut = null }
                    )
                }

                if (state.isAddingShortcut) {
                    AddShortcutDialog(
                        bridge = bridge,
                        faviconCache = state.faviconCache,
                        onFetchFavicon = { state.fetchFavicon(it) },
                        onPickFile = { onPicked ->
                            bridge.pickFiles("*/*", false) { uris ->
                                val picked = uris.firstOrNull()
                                if (picked != null) {
                                    val pathStr = picked.path
                                    val directPath = if (pathStr != null && (pathStr.contains("/storage/") || pathStr.contains("/sdcard/"))) {
                                        pathStr.substring(pathStr.indexOf("/storage/").coerceAtLeast(pathStr.indexOf("/sdcard/")))
                                    } else picked.toString()
                                    onPicked(directPath)
                                }
                            }
                        },
                        onReadLocalCode = { urlOrPath ->
                            state.vaultManager.readLocalAppCode(urlOrPath, null)
                        },
                        onAdd = { name, rawUrl, isDef, isGlobal, editedCode ->
                            val trimmedUrl = rawUrl.trim()
                            val isLocal = isLocalFilePath(trimmedUrl)
                            val newShortcutId = "sc_${System.currentTimeMillis()}"
                            val (finalUrl, srcPath) = if (isLocal) {
                                val isolatedSubPath = "ide/vault_$newShortcutId/index.html"
                                val (success, _) = if (editedCode != null) {
                                    state.vaultManager.saveLocalAppCode(editedCode, isolatedSubPath, trimmedUrl)
                                } else {
                                    state.vaultManager.syncLocalFileToVault(trimmedUrl, isolatedSubPath)
                                }
                                if (success) {
                                    bridge.showToast(if (editedCode != null) "✅ Vault created with edited code" else "✅ Isolated vault created for local app")
                                }
                                Pair("http://localhost:${state.localServerPort}/vault_$newShortcutId/index.html", normalizeLocalFilePath(trimmedUrl))
                            } else {
                                val webUrl = if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) "https://$trimmedUrl" else trimmedUrl
                                Pair(webUrl, null)
                            }
                            val title = name.trim().ifEmpty { if (isLocal) "Local App" else extractDomain(finalUrl) }
                            val newItem = ShortcutItem(
                                id = newShortcutId,
                                title = title,
                                url = finalUrl,
                                iconText = if (isLocal) "💻" else title.take(1).uppercase(),
                                colorValue = 0xFF58A6FF,
                                localSourcePath = srcPath,
                                isDefault = isDef,
                                isGlobalProfile = isGlobal
                            )
                            val updated = state.shortcuts.map { if (isDef) it.copy(isDefault = false) else it } + newItem
                            state.shortcuts = updated
                            state.vaultManager.saveShortcuts(updated)
                            state.fetchFavicon(extractDomain(finalUrl))
                            state.isAddingShortcut = false
                        },
                        onDismiss = { state.isAddingShortcut = false }
                    )
                }

                if (state.editingProfile != null) {
                    val targetProf = state.editingProfile!!
                    EditProfileDialog(
                        profile = targetProf,
                        onSave = { newName, newColor ->
                            val updated = state.profiles.map {
                                if (it.id == targetProf.id) it.copy(name = newName, colorValue = newColor) else it
                            }
                            state.profiles = updated
                            state.vaultManager.saveProfiles(updated)
                            state.editingProfile = null
                        },
                        onDismiss = { state.editingProfile = null }
                    )
                }
            }

            if (state.isSearchFocused && state.suggestions.isNotEmpty()) {
                OmniboxSuggestionOverlay(
                    suggestions = state.suggestions,
                    onSelectSuggestion = { sug ->
                        focusManager.clearFocus()
                        state.selectSuggestion(sug)
                    },
                    onFillSuggestion = { sug ->
                        state.fillSuggestion(sug)
                    },
                    onDeleteSuggestion = { sug ->
                        state.deleteHistoryItem(sug)
                    },
                    onDismiss = {
                        state.dismissSearch()
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 52.dp)
                        .zIndex(50f)
                )
            }

            DownloadCompletedPillBanner(
                visible = state.showDownloadBanner,
                file = state.latestDownloadedFile,
                onOpen = { file ->
                    openDownloadedFile(context, file, bridge)
                },
                onAttach = { file ->
                    state.attachFileToCurrentWebPage(file)
                },
                onDelete = { file ->
                    state.deleteDownloadedFile(file)
                },
                onDismiss = { state.showDownloadBanner = false },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 58.dp)
                    .zIndex(35f)
            )

            PageZoomBar(
                visible = state.showZoomBar,
                currentDomain = state.getDomainForUrl(state.currentUrl),
                zoom = state.getCurrentDomainZoom(),
                onZoomChange = { newZoom ->
                    state.setDomainZoom(state.getDomainForUrl(state.currentUrl), newZoom)
                },
                onReset = {
                    state.setDomainZoom(state.getDomainForUrl(state.currentUrl), 1.0f)
                },
                onDismiss = { state.showZoomBar = false },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (!isImeVisible) 58.dp else 16.dp)
                    .zIndex(36f)
            )

            CxpIngestionPillBanner(
                visible = state.showCxpPill,
                status = state.cxpPillStatus,
                message = state.cxpPillMessage,
                onOpenIde = {
                    if (state.cxpPillStatus != "PATCHING") {
                        state.showCxpPill = false
                    }
                    val ideId = state.cxpPillIdeTabId ?: state.getMostRecentIdeTab()?.id
                    if (ideId != null && state.tabs.any { it.id == ideId }) {
                        state.switchToTab(ideId)
                    } else {
                        state.openLocalIdeAsNeighbor()
                    }
                },
                onDismiss = { state.showCxpPill = false },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 58.dp)
                    .zIndex(40f)
            )
            ReadAloudPillBanner(
                visible = state.readAloudEnabled && state.showReadAloudPill &&
                    !state.isTabSwitcherOpen && !state.isHomeOverlayOpen && !state.showSettingsDialog &&
                    (state.currentUrl.contains("aistudio.google.com") || state.voicePlaybackState != "IDLE"),
                playbackState = state.voicePlaybackState,
                chunkProgress = state.voiceChunkProgress,
                autoReadAloud = state.autoReadAloud,
                onReadOrStop = { state.triggerReadLastBubble() },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp)
                    .zIndex(34f)
            )
            BottomBarExpandFab(
                visible = state.isBottomBarCollapsed && !isImeVisible && !state.isTabSwitcherOpen && !state.showSettingsDialog && !state.isBrowserImmersiveMode,
                profColor = profColor,
                isAutoPilotActive = state.isLiveAutoPilotEnabled,
                onExpand = { state.isBottomBarCollapsed = false },
                onOpenMenu = {
                    state.refreshSiteCacheSize()
                    state.showMenu = true
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 12.dp, bottom = 12.dp)
                    .zIndex(33f)
            )

            if (state.customFullscreenView != null) {
                val fsView = state.customFullscreenView!!
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .zIndex(60f)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                (fsView.parent as? ViewGroup)?.removeView(fsView)
                                addView(
                                    fsView,
                                    ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                )
                            }
                        },
                        update = { frame ->
                            if (fsView.parent !== frame) {
                                (fsView.parent as? ViewGroup)?.removeView(fsView)
                                frame.removeAllViews()
                                frame.addView(
                                    fsView,
                                    ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    ImmersiveFloatingControlsPill(
                        visible = true,
                        onRotateClick = { state.toggleFullscreenRotation() },
                        onExitClick = { state.exitCustomFullscreen() },
                        draggable = true,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 12.dp, end = 12.dp)
                    )
                }
            }

            UndoBanner(
                visible = state.showUndoBanner,
                message = state.undoMessage,
                onUndo = {
                    state.undoJob?.cancel()
                    state.poolManager.pendingPurge.forEach { (id, wv) -> state.poolManager.pool[id] = wv }
                    state.poolManager.pendingPurge.clear()

                    val backup = state.lastClosedTabsSnapshot
                    if (backup != null && backup.isNotEmpty()) {
                        state.tabs = backup
                        val targetId = if (backup.any { it.id == state.lastActiveTabIdSnapshot }) state.lastActiveTabIdSnapshot!! else backup.first().id
                        state.activeTabId = targetId
                        state.vaultManager.saveSession(backup, targetId)
                        state.attachTabWebView(targetId)
                    }
                    state.showUndoBanner = false
                    state.lastClosedTabsSnapshot = null
                },
                onDismiss = {
                    state.undoJob?.cancel()
                    state.showUndoBanner = false
                    state.poolManager.purgePending(state.containerLayout)
                    state.lastClosedTabsSnapshot = null
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (!isImeVisible) 58.dp else 20.dp)
                    .zIndex(30f)
            )
        }
    }
}