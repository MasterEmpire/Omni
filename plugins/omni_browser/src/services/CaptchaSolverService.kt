package com.omni.plugin.browser.services

import android.webkit.WebView
import com.omni.hub.api.HostBridge
import com.omni.plugin.browser.utils.CAPTCHA_DETECTOR_SCRIPT
import com.omni.plugin.browser.utils.buildCaptchaInjectionScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

class CaptchaSolverService(
    private val bridge: HostBridge
) {
    var isSolving: Boolean = false
        private set

    fun extractSolverError(json: JSONObject, raw: String): String {
        val desc = json.optString("errorDescription")
        if (desc.isNotEmpty()) return desc
        val code = json.optString("errorCode")
        if (code.isNotEmpty()) return code
        val msg = json.optString("message", json.optString("msg", json.optString("error", "")))
        if (msg.isNotEmpty()) return msg
        val status = json.optString("status")
        if (status.isNotEmpty() && status != "processing") return status
        return if (raw.length > 120) raw.take(120) + "..." else raw
    }

    suspend fun executeSolver(
        apiKey: String,
        siteKey: String,
        pageUrl: String,
        targetWebView: WebView?
    ): Boolean = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) {
            withContext(Dispatchers.Main) {
                bridge.showToast("Configure NoCaptchaAI API key in settings first.")
            }
            return@withContext false
        }
        if (isSolving) return@withContext false

        isSolving = true
        withContext(Dispatchers.Main) {
            bridge.showToast("🤖 Solving CAPTCHA with NoCaptchaAI...")
        }
        bridge.log("SOLVER", "Creating task for sitekey: $siteKey on $pageUrl")

        try {
            val createPayload = JSONObject().apply {
                put("clientKey", apiKey)
                put("task", JSONObject().apply {
                    put("type", "ReCaptchaV2TaskProxyLess")
                    put("websiteURL", pageUrl)
                    put("websiteKey", siteKey)
                })
            }

            var responseStr = bridge.httpPost("https://api.nocaptchaai.com/createTask", createPayload.toString())
            if (responseStr == null) {
                responseStr = bridge.httpPost("https://api.nocaptchaai.com/solve", createPayload.toString())
            }

            if (responseStr != null) {
                bridge.log("SOLVER", "Task Creation Response: $responseStr")
                val respObj = JSONObject(responseStr)

                var solutionToken = respObj.optJSONObject("solution")?.optString("gRecaptchaResponse")
                    ?: respObj.optString("token", "")

                val taskId = respObj.optString("taskId", "")

                if (solutionToken.isEmpty() && taskId.isNotEmpty()) {
                    bridge.log("SOLVER", "Task registered with ID: $taskId. Polling for solution...")
                    val pollPayload = JSONObject().apply {
                        put("clientKey", apiKey)
                        put("taskId", taskId)
                    }.toString()

                    var attempts = 0
                    while (attempts < 15 && solutionToken.isEmpty()) {
                        delay(2000)
                        attempts++
                        val pollRespStr = bridge.httpPost("https://api.nocaptchaai.com/getTaskResult", pollPayload)
                        if (pollRespStr != null) {
                            val pollObj = JSONObject(pollRespStr)
                            val status = pollObj.optString("status", "")
                            bridge.log("SOLVER", "Poll #$attempts status: $status")

                            if (status == "ready" || status == "solved") {
                                solutionToken = pollObj.optJSONObject("solution")?.optString("gRecaptchaResponse")
                                    ?: pollObj.optString("token", "")
                                break
                            } else if (status == "failed" || pollObj.optInt("errorId", 0) != 0) {
                                val err = extractSolverError(pollObj, pollRespStr)
                                withContext(Dispatchers.Main) {
                                    bridge.showToast("Solver failed: $err")
                                    bridge.log("SOLVER_ERR", "Polling Error: $pollRespStr")
                                }
                                return@withContext false
                            }
                        }
                    }
                }

                if (solutionToken.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        val injectionScript = buildCaptchaInjectionScript(solutionToken)
                        targetWebView?.evaluateJavascript(injectionScript, null)
                        bridge.showToast("✅ CAPTCHA Solved & Injected!")
                        bridge.log("SOLVER", "Successfully injected response token into DOM.")
                    }
                    return@withContext true
                } else {
                    val errMsg = extractSolverError(respObj, responseStr)
                    withContext(Dispatchers.Main) {
                        bridge.showToast("Solver returned: $errMsg")
                        bridge.log("SOLVER_ERR", "API Response: $responseStr")
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    bridge.showToast("Failed to reach solver API endpoint.")
                    bridge.log("SOLVER_ERR", "HTTP POST to solver endpoint returned null.")
                }
            }
            return@withContext false
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                bridge.showToast("Solver error: ${e.message}")
                bridge.log("SOLVER_ERR", "Exception: ${e.message}")
            }
            return@withContext false
        } finally {
            isSolving = false
        }
    }

    fun scanAndSolve(apiKey: String, pageUrl: String, webView: WebView?, onSiteKeyFound: (String) -> Unit) {
        val detectorScript = CAPTCHA_DETECTOR_SCRIPT
        webView?.evaluateJavascript(detectorScript) { siteKeyRaw ->
            val siteKey = siteKeyRaw?.replace("\"", "")?.trim() ?: ""
            if (siteKey.isNotEmpty()) {
                onSiteKeyFound(siteKey)
            } else {
                bridge.showToast("No active CAPTCHA widget found on page.")
            }
        }
    }
}