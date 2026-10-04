package com.omni.plugin.browser.engine

import android.webkit.WebView
import com.omni.hub.api.HostBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Dedicated Engine managing live on-screen AI Studio scraping,
 * conditional virtualizer autoscroll, and Nexus Python tool dispatch.
 */
object LiveAutoPilotEngine {

    fun arm(webView: WebView?, bridge: HostBridge) {
        if (webView == null) return
        bridge.showToast("⚡ Live AI Auto-Pilot Armed!")
        bridge.log("LIVE_AUTO", "Live AI Auto-Pilot armed.")
        webView.evaluateJavascript(buildSentinelScript(), null)
        webView.evaluateJavascript("window.__omniLiveAutoPilotActive = true;", null)
    }

    fun disarm(webView: WebView?, bridge: HostBridge) {
        if (webView == null) return
        bridge.showToast("Live Auto-Pilot Disarmed")
        bridge.log("LIVE_AUTO", "Live AI Auto-Pilot disarmed.")
        webView.evaluateJavascript("window.__omniLiveAutoPilotActive = false;", null)
    }

    fun syncOnPageFinished(webView: WebView?, url: String?, isArmed: Boolean) {
        if (webView == null || url == null || !url.contains("aistudio.google.com")) return
        webView.evaluateJavascript(buildSentinelScript(), null)
        if (isArmed) {
            webView.evaluateJavascript("window.__omniLiveAutoPilotActive = true;", null)
        }
    }

    fun executeLivePython(
        code: String,
        webView: WebView?,
        bridge: HostBridge,
        coroutineScope: CoroutineScope,
        onStatusChanged: (String) -> Unit
    ) {
        if (webView == null) return
        onStatusChanged("Executing on Nexus...")
        bridge.showToast("🐍 Running Python script on Nexus...")
        bridge.log("LIVE_AUTO", "Executing Python on Nexus:\n$code")

        val streamBuffer = StringBuilder()

        bridge.executePython(
            code = code,
            onOutput = { chunk ->
                streamBuffer.append(chunk)
                bridge.log("LIVE_PYTHON_STREAM", chunk)
            },
            onComplete = { success, output ->
                coroutineScope.launch(Dispatchers.Main) {
                    onStatusChanged("Watching")
                    val effectiveOutput = if (output.isNotBlank()) output.trim() else streamBuffer.toString().trim()
                    bridge.log("LIVE_AUTO", "Python finished (success=$success, outputLen=${effectiveOutput.length})")
                    deliverPythonResult(webView, bridge, success, effectiveOutput)
                }
            }
        )
    }

    private fun deliverPythonResult(
        webView: WebView,
        bridge: HostBridge,
        success: Boolean,
        output: String
    ) {
        var cleanOutput = output
        var attachedFileName: String? = null
        var attachedBase64: String? = null
        var attachedMime: String? = null

        // Detect ATTACH_FILE: /path/to/file marker
        val marker = "ATTACH_FILE:"
        if (output.contains(marker)) {
            val lines = output.lines()
            val fileLine = lines.find { it.trim().startsWith(marker) }
            if (fileLine != null) {
                val filePath = fileLine.substringAfter(marker).trim()
                val targetFile = java.io.File(filePath)
                if (targetFile.exists() && targetFile.isFile) {
                    try {
                        val bytes = targetFile.readBytes()
                        attachedBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        attachedFileName = targetFile.name
                        attachedMime = com.omni.plugin.browser.utils.resolveMimeType(targetFile)
                        bridge.log("LIVE_AUTO", "📎 Ingested attachment: ${targetFile.name} (${bytes.size} bytes)")
                        cleanOutput = lines.filter { !it.trim().startsWith(marker) }.joinToString("\n").trim()
                    } catch (e: Exception) {
                        bridge.log("LIVE_AUTO_ERR", "Failed reading attachment: ${e.message}")
                    }
                }
            }
        }

        val prefix = if (success) "[Python Output]:\n" else "[Python Error]:\n"
        val fullText = prefix + cleanOutput
        val escapedText = JSONObject.quote(fullText)
        val escapedName = if (attachedFileName != null) JSONObject.quote(attachedFileName) else "null"
        val escapedB64 = if (attachedBase64 != null) JSONObject.quote(attachedBase64) else "null"
        val escapedMime = if (attachedMime != null) JSONObject.quote(attachedMime) else "null"

        bridge.log("LIVE_AUTO", "Delivering result to AI Studio (${cleanOutput.length} chars, file=$attachedFileName)")
        webView.evaluateJavascript(
            "if (window.__omniDeliverPythonResult) { window.__omniDeliverPythonResult($escapedText, $escapedName, $escapedB64, $escapedMime); }",
            null
        )
    }

    fun buildSentinelScript(): String {
        return """
            (function() {
                if (window.__omniLiveSentinelLoaded) return;
                window.__omniLiveSentinelLoaded = true;

                const delay = (ms) => new Promise(r => setTimeout(r, ms));

                function checkUiGenerating() {
                    const runBtn = document.querySelector('ms-run-button button, button.ctrl-enter-submits, button[aria-label*="Stop"], button.stoppable');
                    if (runBtn) {
                        const aria = (runBtn.getAttribute('aria-label') || '').toLowerCase();
                        const label = (runBtn.querySelector('.run-button-label')?.textContent || '').toLowerCase();
                        if (runBtn.classList.contains('stoppable') ||
                            runBtn.querySelector('.stoppable-spinner, .stoppable-stop, svg[class*="stoppable"]') ||
                            aria.includes('stop') || label.includes('stop')) {
                            return true;
                        }
                    }
                    if (document.querySelector('.run-button.stoppable, ms-run-button .stoppable-spinner, ms-run-button .stoppable-stop, button.stoppable')) {
                        return true;
                    }
                    if (document.querySelector('.thinking-progress-icon.in-progress, ms-thought-chunk .in-progress, [class*="thinking-progress-icon"][class*="in-progress"]')) {
                        return true;
                    }
                    if (document.querySelector('ms-chat-turn loading-indicator, ms-chat-turn .loading-indicator, ms-chat-turn mat-spinner, ms-chat-turn mat-progress-bar')) {
                        return true;
                    }
                    return false;
                }

                function getScreenText(turnEl) {
                    if (!turnEl) return '';
                    try {
                        const answerCandidates = Array.from(turnEl.querySelectorAll('ms-text-chunk, ms-code-block, ms-cmark-node, pre code, .rendered-markdown'))
                            .filter(el => !el.closest('ms-thought-chunk'));
                        if (answerCandidates.length === 0) return '';
                        const contentContainer = turnEl.querySelector('.chat-turn-content, ms-chat-turn-content, .turn-content') || turnEl;
                        const tempDiv = document.createElement('div');
                        tempDiv.style.position = 'fixed';
                        tempDiv.style.left = '-99999px';
                        tempDiv.style.top = '-99999px';
                        tempDiv.style.opacity = '0';
                        tempDiv.style.pointerEvents = 'none';

                        const clone = contentContainer.cloneNode(true);
                        clone.querySelectorAll('ms-thought-chunk, .author-label, .header, .timestamp, .info-container, .turn-information, loading-indicator, .turn-footer, button, .actions-container, ms-run-button').forEach(el => el.remove());
                        tempDiv.appendChild(clone);
                        document.body.appendChild(tempDiv);

                        let text = (tempDiv.innerText || '').trim();
                        if (!text) text = (tempDiv.textContent || '').trim();
                        document.body.removeChild(tempDiv);
                        return text;
                    } catch(e) {
                        return '';
                    }
                }

                function isRunButtonReady(btn) {
                    if (!btn) return false;
                    if (btn.disabled || btn.hasAttribute('disabled')) return false;
                    if (btn.getAttribute('aria-disabled') === 'true') return false;
                    if (btn.classList.contains('disabled') || btn.classList.contains('mat-mdc-button-disabled') || btn.classList.contains('mdc-button--disabled')) return false;
                    return true;
                }

                function safeInjectText(el, text) {
                    try {
                        el.focus();
                        el.click();
                        const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                        const descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
                        if (descriptor && descriptor.set) {
                            descriptor.set.call(el, text);
                        } else {
                            el.value = text;
                        }
                        el.dispatchEvent(new Event('focus', { bubbles: true }));
                        el.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
                        try {
                            el.dispatchEvent(new InputEvent('input', {
                                bubbles: true,
                                cancelable: true,
                                inputType: 'insertText',
                                data: text
                            }));
                        } catch(_) {}
                        el.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
                    } catch(e) {
                        try { el.value = text; } catch(_) {}
                        el.dispatchEvent(new Event('input', { bubbles: true }));
                    }
                }

                            window.__omniDeliverPythonResult = async function(textResult, fileName, fileBase64, mimeType) {
                // 1. If a generated file was produced by Python, attach it via DataTransfer
                if (fileName && fileBase64) {
                    try {
                        const fileInput = document.querySelector('input[data-test-upload-file-input], input[type="file"].file-input, input[type="file"]');
                        if (fileInput) {
                            const dt = new DataTransfer();
                            const byteChars = atob(fileBase64);
                            const byteArray = new Uint8Array(byteChars.length);
                            for (let i = 0; i < byteChars.length; i++) {
                                byteArray[i] = byteChars.charCodeAt(i);
                            }
                            const blob = new Blob([byteArray], { type: mimeType || 'application/octet-stream' });
                            const file = new File([blob], fileName, { type: mimeType || 'application/octet-stream' });
                            dt.items.add(file);
                            fileInput.files = dt.files;
                            fileInput.dispatchEvent(new Event('change', { bubbles: true }));
                            await delay(1600);
                        }
                    } catch(e) {}
                }

                // 2. Inject text into textarea
                const promptArea = document.querySelector('textarea[formcontrolname="promptText"], textarea[aria-label="Enter a prompt"], textarea');
                if (promptArea) {
                    safeInjectText(promptArea, textResult);
                    await delay(800);
                    let readyWait = 0;
                    while (readyWait < 25) {
                        const submitBtn = document.querySelector('ms-run-button button:not(.stoppable), button.ctrl-enter-submits:not(.stoppable), button[type="submit"]:not(.stoppable)');
                        if (submitBtn && isRunButtonReady(submitBtn)) {
                            submitBtn.click();
                            break;
                        }
                        await delay(300);
                        readyWait++;
                    }
                }
            };

                setInterval(() => {
                    if (!window.__omniLiveAutoPilotActive) return;

                    // If model is generating, yield until the turn finishes without hijacking scroll
                    if (checkUiGenerating()) return;

                    const allModelTurns = Array.from(document.querySelectorAll('.chat-turn-container.model, ms-chat-turn .chat-turn-container.model, [data-turn-role="Model"]'));
                    if (allModelTurns.length === 0) return;
                    const latestTurn = allModelTurns[allModelTurns.length - 1];

                    if (latestTurn.getAttribute('data-omni-executed') === 'true') return;

                    const screenText = getScreenText(latestTurn);
                    if (!screenText) return;

                    const match = screenText.match(/<(?:omni_action\s+name=["']execute_python["']|execute_python)>([\s\S]*?)<\/(?:omni_action|execute_python)>/i);

                    if (match && match[1]) {
                        latestTurn.setAttribute('data-omni-executed', 'true');
                        const pythonScript = match[1].trim();

                        if (window.OmniPythonBridge && window.OmniPythonBridge.executeFromLivePage) {
                            window.OmniPythonBridge.log('LIVE_AUTO', 'Captured Python block from chat turn. Executing on Nexus...');
                            window.OmniPythonBridge.executeFromLivePage(pythonScript);
                        }
                    }
                }, 600);
            })();
        """.trimIndent()
    }
}