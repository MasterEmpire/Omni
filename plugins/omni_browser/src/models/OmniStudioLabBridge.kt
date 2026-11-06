package com.omni.plugin.browser.models

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.omni.hub.api.HostBridge
import com.omni.plugin.browser.engine.AiStudioAutomator
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
    private val onIdePull: (project: String, files: List<String>, onResult: (String, Int) -> Unit) -> Unit,
    private val onIdeCommit: (xml: String, onResult: (String, String) -> Unit) -> Unit,
    private val scope: CoroutineScope,
    private val getActiveWebView: () -> WebView?
) {
    private val mainHandler = Handler(Looper.getMainLooper())

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

    // --- 1. Multi-Session Puppet Pool Methods (Uncapped Concurrent Sessions) ---
    @JavascriptInterface
    fun studioCreateSession(callId: String, profileId: String, model: String) {
        mainHandler.post {
            automator.createSession(profileId.ifEmpty { "default" }, model) { sessionId, err ->
                if (sessionId != null) {
                    val res = JSONObject().apply { put("sessionId", sessionId) }
                    resolve(callId, res)
                } else {
                    reject(callId, err ?: "Failed creating session")
                }
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
    fun studioCloseSession(callId: String, sessionId: String) {
        mainHandler.post {
            automator.closeSession(sessionId)
            resolve(callId, "Closed")
        }
    }

    // --- Backward-Compatible Default Session Wrappers ---
    @JavascriptInterface
    fun studioInit(callId: String, profileId: String) {
        mainHandler.post {
            automator.prepareSession(profileId.ifEmpty { "default" }) { success, err ->
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
}