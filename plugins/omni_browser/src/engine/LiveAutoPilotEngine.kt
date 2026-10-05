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
        bridge.log("AUTOPILOT_PIPELINE", "🟢 [ARMED] Live Auto-Pilot is now ACTIVE and watching AI Studio.")
        webView.evaluateJavascript(buildSentinelScript(), null)
        webView.evaluateJavascript("window.__omniLiveAutoPilotActive = true;", null)
    }

    fun disarm(webView: WebView?, bridge: HostBridge) {
        if (webView == null) return
        bridge.showToast("Live Auto-Pilot Disarmed")
        bridge.log("AUTOPILOT_PIPELINE", "🔴 [DISARMED] Live Auto-Pilot deactivated. All scrapers silenced.")
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
                if (window.__omniSentinelInterval) {
                    clearInterval(window.__omniSentinelInterval);
                }

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

                function extractCxpBlock(text) {
                    if (!text) return null;

                    const hasValidCxpTag = (str) => {
                        if (!str || str.length < 15) return false;
                        const tNames = ['comment', 'replace' + '_block', 'create' + '_file', 'delete' + '_file', 'rename' + '_file', 'export' + '_files'].join('|');
                        return new RegExp('<(?:' + tNames + ')\\b', 'i').test(str);
                    };

                    // Strategy 1: Explicit unique wrapper tag <cxp>...</cxp> or <patch>...</patch>
                    // Retains the full tag wrapper (wMatch[0]) so downstream IDE can parse attributes like project="..."
                    const wrapperRegex = /<(?:cxp|omni_cxp|patch)[^>]*>[\s\S]*?<\/(?:cxp|omni_cxp|patch)>/gi;
                    const wrapperCandidates = [];
                    let wMatch;
                    while ((wMatch = wrapperRegex.exec(text)) !== null) {
                        const candidate = wMatch[0].trim();
                        if (hasValidCxpTag(candidate)) {
                            wrapperCandidates.push(candidate);
                        }
                    }
                    if (wrapperCandidates.length > 0) {
                        // Join all valid blocks to support sequential multi-project patching!
                        return wrapperCandidates.join('\n\n');
                    }

                    // Strategy 2: Markdown code fence match (if in raw mode)
                    const fenceRegex = /```(?:xml|cxp)?\s*([\s\S]*?)```/gi;
                    const fenceCandidates = [];
                    let fMatch;
                    while ((fMatch = fenceRegex.exec(text)) !== null) {
                        let candidate = fMatch[1].trim();
                        const innerMatch = candidate.match(/<(?:cxp|omni_cxp|patch)>([\s\S]*?)<\/(?:cxp|omni_cxp|patch)>/i);
                        if (innerMatch) candidate = innerMatch[1].trim();
                        if (hasValidCxpTag(candidate)) {
                            fenceCandidates.push(candidate);
                        }
                    }
                    if (fenceCandidates.length > 0) {
                        return fenceCandidates[fenceCandidates.length - 1];
                    }

                    // Strategy 3: Direct structural CXP boundary match (first tag to last closing tag)
                    const tagNames = ['cxp', 'omni_cxp', 'patch', 'comment', 'replace' + '_block', 'create' + '_file', 'delete' + '_file', 'rename' + '_file', 'export' + '_files'].join('|');
                    const pattern = '(<(?:' + tagNames + ')[\\s\\S]*<\\/(?:' + tagNames + ')>)';
                    const rawTagMatch = text.match(new RegExp(pattern, 'i'));
                    if (rawTagMatch && hasValidCxpTag(rawTagMatch[1])) {
                        return rawTagMatch[1].trim();
                    }

                    return null;
                }

                function isCxpBalanced(rawPayload) {
                    if (!rawPayload) return false;
                    const tag = (name) => new RegExp('<' + name, 'gi');
                    const endTag = (name) => new RegExp('<\\/' + name + '>', 'gi');

                    const openCxp = (rawPayload.match(/<(?:cxp|omni_cxp|patch)\b/gi) || []).length;
                    const closeCxp = (rawPayload.match(/<\/(?:cxp|omni_cxp|patch)>/gi) || []).length;
                    if (openCxp !== closeCxp) return false;

                    const openReplace = (rawPayload.match(tag('replace' + '_block')) || []).length;
                    const closeReplace = (rawPayload.match(endTag('replace' + '_block')) || []).length;
                    const openCreate = (rawPayload.match(tag('create' + '_file')) || []).length;
                    const closeCreate = (rawPayload.match(endTag('create' + '_file')) || []).length;
                    const openComment = (rawPayload.match(tag('comment')) || []).length;
                    const closeComment = (rawPayload.match(endTag('comment')) || []).length;

                    if (openReplace === 0 && openCreate === 0 && openComment === 0 && !rawPayload.includes('<export_files') && !rawPayload.includes('<delete_file') && !rawPayload.includes('<rename_file')) return false;
                    return openReplace === closeReplace && openCreate === closeCreate && openComment === closeComment;
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

                window.__omniSentinelInterval = setInterval(() => {
                    if (!window.__omniLiveAutoPilotActive) return;

                    const isGen = checkUiGenerating();
                    if (isGen) {
                        window.__omniWasGenerating = true;
                        window.__omniStillTicks = 0;
                        return;
                    }

                    if (window.__omniWasGenerating) {
                        window.__omniWasGenerating = false;
                        if (window.OmniIdeBridge && window.OmniIdeBridge.log) {
                            window.OmniIdeBridge.log('AUTOPILOT_PIPELINE', '🔍 [STAGE 1: SETTLED] Generation finished. Inspecting DOM for balanced CXP / Python tags...');
                        }
                    }

                    const allModelContainers = Array.from(document.querySelectorAll('.chat-turn-container.model, ms-chat-turn .chat-turn-container.model, [data-turn-role="Model"], [data-turn-role="model" i], ms-chat-turn:not(.user)'));
                    if (allModelContainers.length === 0) return;

                    // Battle-tested turn selection from AiStudioAutomator: scan backwards for the turn containing mounted answer nodes
                    let latestTurn = null;
                    for (let i = allModelContainers.length - 1; i >= 0; i--) {
                        const t = allModelContainers[i];
                        const hasRealAnswer = Array.from(t.querySelectorAll('ms-text-chunk, ms-code-block, ms-cmark-node, pre code, .rendered-markdown'))
                            .some(el => !el.closest('ms-thought-chunk'));
                        if (hasRealAnswer) {
                            latestTurn = t;
                            break;
                        }
                    }
                    if (!latestTurn) {
                        latestTurn = allModelContainers[allModelContainers.length - 1];
                    }

                    const screenText = getScreenText(latestTurn);
                    if (!screenText) return;

                    // Stillness Watchdog: Track character growth on active turn
                    window.__omniLastText = window.__omniLastText || '';
                    window.__omniStillTicks = window.__omniStillTicks || 0;

                    if (screenText.length > window.__omniLastText.length) {
                        window.__omniStillTicks = 0;
                        window.__omniLastText = screenText;
                        return;
                    } else {
                        window.__omniStillTicks++;
                        window.__omniLastText = screenText;
                    }

                    // Require at least 4 stillness ticks (2.4s of zero text growth)
                    if (window.__omniStillTicks < 4) {
                        return;
                    }

                    // 1. Detect & Auto-Bridge CXP Patch Markup to Conduit IDE
                    if (latestTurn.getAttribute('data-omni-cxp-executed') !== 'true') {
                        const cxpPayload = extractCxpBlock(screenText);
                        if (cxpPayload) {
                            const balanced = isCxpBalanced(cxpPayload);
                            if (balanced) {
                                latestTurn.setAttribute('data-omni-cxp-executed', 'true');
                                if (window.OmniIdeBridge && window.OmniIdeBridge.log) {
                                    window.OmniIdeBridge.log('AUTOPILOT_PIPELINE', '📦 [STAGE 1: SCRAPED] Balanced CXP extracted (' + cxpPayload.length + ' chars). Beaming to Conduit IDE...');
                                }
                                if (window.OmniIdeBridge && window.OmniIdeBridge.dispatchCxpToIde) {
                                    window.OmniIdeBridge.dispatchCxpToIde(cxpPayload);
                                }
                            } else {
                                window.__cxpTicks = (window.__cxpTicks || 0) + 1;
                                if (window.__cxpTicks % 5 === 0 && window.OmniIdeBridge && window.OmniIdeBridge.log) {
                                    window.OmniIdeBridge.log('AUTOPILOT_PIPELINE', '⏳ [STAGE 1: WAITING CLOSURES] Detected CXP but tags still streaming. Length: ' + cxpPayload.length);
                                }
                            }
                        }
                    }

                    // 2. Detect & Auto-Bridge Python Tool Execution to Nexus
                    if (latestTurn.getAttribute('data-omni-executed') !== 'true') {
                        const match = screenText.match(/<(?:omni_action\s+name=["']execute_python["']|execute_python)>([\s\S]*?)<\/(?:omni_action|execute_python)>/i);

                        if (match && match[1]) {
                            latestTurn.setAttribute('data-omni-executed', 'true');
                            const pythonScript = match[1].trim();

                            if (window.OmniPythonBridge && window.OmniPythonBridge.executeFromLivePage) {
                                window.OmniPythonBridge.log('LIVE_AUTO', 'Captured Python block from chat turn. Executing on Nexus...');
                                window.OmniPythonBridge.executeFromLivePage(pythonScript);
                            }
                        }
                    }

                    // 3. Detect & Auto-Bridge Project File Pulling to Conduit IDE
                    if (latestTurn.getAttribute('data-omni-pull-executed') !== 'true') {
                        const pullMatch = screenText.match(/<(?:omni_action\s+name=["']pull_files["']|pull_files)(?:\s+[^>]*?)?>[\s\S]*?<\/(?:omni_action|pull_files)>/i);
                        if (pullMatch) {
                            latestTurn.setAttribute('data-omni-pull-executed', 'true');
                            const pullPayload = pullMatch[0].trim();
                            if (window.OmniIdeBridge && window.OmniIdeBridge.requestFilePull) {
                                if (window.OmniIdeBridge.log) {
                                    window.OmniIdeBridge.log('AUTOPILOT_PIPELINE', '📂 [STAGE 1: SCRAPED PULL] Captured pull_files request. Beaming to Conduit IDE...');
                                }
                                window.OmniIdeBridge.requestFilePull(pullPayload);
                            }
                        }
                    }
                }, 600);
            })();
        """.trimIndent()
    }
}