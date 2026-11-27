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

    fun arm(webView: WebView?, bridge: HostBridge, showToast: Boolean = true) {
        if (webView == null) return
        if (showToast) bridge.showToast("⚡ Live AI Auto-Pilot Armed!")
        bridge.log("AUTOPILOT_PIPELINE", "🟢 [ARMED] Live Auto-Pilot is now ACTIVE and watching AI Studio.")
        webView.evaluateJavascript(buildSentinelScript(), null)
        webView.evaluateJavascript(
            "window.__omniLiveAutoPilotActive = true; if (typeof window.__omniAuditArmedTurn === 'function') window.__omniAuditArmedTurn($showToast);",
            null
        )
    }

    fun disarm(webView: WebView?, bridge: HostBridge, showToast: Boolean = true) {
        if (webView == null) return
        if (showToast) bridge.showToast("Live Auto-Pilot Disarmed")
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
                android.os.Handler(android.os.Looper.getMainLooper()).post {
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
        val deliveryScript = """
            (function() {
                if (typeof window.__omniDeliverPythonResult === 'function') {
                    window.__omniDeliverPythonResult($escapedText, $escapedName, $escapedB64, $escapedMime);
                    return 'DELIVERED';
                } else {
                    if (window.OmniPythonBridge && window.OmniPythonBridge.log) {
                        window.OmniPythonBridge.log('LIVE_AUTO_ERR', 'window.__omniDeliverPythonResult is missing on page (loc=' + window.location.href + ')');
                    }
                    return 'MISSING_DELIVERY_FN';
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(deliveryScript) { evalResult ->
            bridge.log("LIVE_AUTO", "Delivery evaluateJavascript callback returned: $evalResult")
        }
    }

    fun buildSentinelScript(): String {
        return """
            (function() {
                if (window.__omniSentinelInterval) {
                    clearInterval(window.__omniSentinelInterval);
                }

                const delay = (ms) => new Promise(r => setTimeout(r, ms));

                function maskNative(fn, name) {
                    try {
                        const label = 'function ' + (name || fn.name || '') + '() { [native code] }';
                        Object.defineProperty(fn, 'toString', {
                            value: function() { return label; },
                            configurable: true
                        });
                    } catch(_) {}
                    return fn;
                }

                // 🛡️ 24/7 Visibility Spoof: Deceive Angular/DOM renderer to keep streaming active even when tab/app is backgrounded
                try {
                    Object.defineProperty(document, 'hidden', { get: () => false, configurable: true });
                    Object.defineProperty(document, 'visibilityState', { get: () => 'visible', configurable: true });
                    Object.defineProperty(document, 'webkitVisibilityState', { get: () => 'visible', configurable: true });
                    Object.defineProperty(document, 'webkitHidden', { get: () => false, configurable: true });
                    document.hasFocus = maskNative(function hasFocus() { return true; }, 'hasFocus');
                } catch(_) {}

                // Trap visibility change listeners so AI Studio doesn't freeze its rendering loop
                if (!window.__omniVisibilityHooked) {
                    window.__omniVisibilityHooked = true;
                    const origAddEvent = EventTarget.prototype.addEventListener;
                    EventTarget.prototype.addEventListener = maskNative(function addEventListener(type, listener, options) {
                        if (type === 'visibilitychange' || type === 'webkitvisibilitychange' || type === 'freeze') {
                            return;
                        }
                        return origAddEvent.call(this, type, listener, options);
                    }, 'addEventListener');
                }

                // 🛡️ Background rAF Unchoker: Force token typing into DOM even when tab/app is in background!
                if (!window.__omniRafUnchoked) {
                    window.__omniRafUnchoked = true;
                    const origRaf = window.requestAnimationFrame;
                    const origCancelRaf = window.cancelAnimationFrame;
                    const pendingCallbacks = new Map();
                    let rafIdCounter = 1;

                    window.requestAnimationFrame = maskNative(function requestAnimationFrame(cb) {
                        const id = rafIdCounter++;
                        let executed = false;
                        let nativeId = null;

                        try {
                            nativeId = origRaf.call(window, function(ts) {
                                if (!executed) {
                                    executed = true;
                                    pendingCallbacks.delete(id);
                                    cb(ts);
                                }
                            });
                        } catch(_) {}

                        // If backgrounded, native rAF stalls. Fallback timer fires so tokens mount to DOM!
                        const timeoutId = setTimeout(function() {
                            if (!executed) {
                                executed = true;
                                pendingCallbacks.delete(id);
                                try { if (nativeId !== null) origCancelRaf.call(window, nativeId); } catch(_) {}
                                cb(performance.now());
                            }
                        }, 24 + Math.floor(Math.random() * 10));

                        pendingCallbacks.set(id, { nativeId: nativeId, timeoutId: timeoutId });
                        return id;
                    }, 'requestAnimationFrame');

                    window.cancelAnimationFrame = maskNative(function cancelAnimationFrame(id) {
                        const entry = pendingCallbacks.get(id);
                        if (entry) {
                            pendingCallbacks.delete(id);
                            try { if (entry.nativeId !== null) origCancelRaf.call(window, entry.nativeId); } catch(_) {}
                            clearTimeout(entry.timeoutId);
                        }
                    }, 'cancelAnimationFrame');
                }

                function hookPromptSubmission() {
                    const runBtn = document.querySelector('ms-run-button button, button.ctrl-enter-submits, button[type="submit"]');
                    if (runBtn && !runBtn.__omniHooked) {
                        runBtn.__omniHooked = true;
                        runBtn.addEventListener('click', function() {
                            if (window.__omniWire && window.__omniWire.notifyUserPrompt) {
                                window.__omniWire.notifyUserPrompt();
                            }
                            if (!window.__omniWireHooked && window.OmniAnalyticsBridge && window.OmniAnalyticsBridge.recordPrompt) {
                                window.OmniAnalyticsBridge.recordPrompt(window.__omniProfileId || 'default');
                            }
                        }, true);
                    }
                    const ta = document.querySelector('textarea[formcontrolname="promptText"], textarea[aria-label="Enter a prompt"], textarea');
                    if (ta && !ta.__omniHooked) {
                        ta.__omniHooked = true;
                        ta.addEventListener('keydown', function(e) {
                            if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                                if (window.__omniWire && window.__omniWire.notifyUserPrompt) {
                                    window.__omniWire.notifyUserPrompt();
                                }
                                if (!window.__omniWireHooked && window.OmniAnalyticsBridge && window.OmniAnalyticsBridge.recordPrompt) {
                                    window.OmniAnalyticsBridge.recordPrompt(window.__omniProfileId || 'default');
                                }
                            }
                        }, true);
                    }
                }

                function checkQuotaErrors() {
                    const errorTargets = document.querySelectorAll('ms-banner .error-banner-message, ms-global-banner, .chat-turn-container.model .model-error, ms-chat-turn .model-error, .cdk-overlay-container .mat-mdc-simple-snack-bar, .cdk-overlay-container mat-snack-bar-container, .mat-mdc-snack-bar-label, .model-error');
                    const now = Date.now();
                    for (const el of errorTargets) {
                        const txt = (el.innerText || el.textContent || '').trim();
                        const lower = txt.toLowerCase();
                        if (lower.includes('quota') || lower.includes('resource has been exhausted') || lower.includes('resource_exhausted') || lower.includes('rate limit') || lower.includes('429')) {
                            const snippet = txt.slice(0, 140);
                            if (el.getAttribute('data-omni-quota-logged') !== 'true') {
                                el.setAttribute('data-omni-quota-logged', 'true');
                                if (window.__omniLastDomQuotaTxt === snippet && (now - (window.__omniLastDomQuotaAt || 0)) < 10000) {
                                    continue;
                                }
                                window.__omniLastDomQuotaTxt = snippet;
                                window.__omniLastDomQuotaAt = now;
                                if (window.OmniAnalyticsBridge && window.OmniAnalyticsBridge.reportQuotaHit) {
                                    window.OmniAnalyticsBridge.reportQuotaHit(window.__omniProfileId || 'default', snippet);
                                }
                            }
                        }
                    }
                }

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
                    const allTurns = document.querySelectorAll('ms-chat-turn');
                    const lastTurn = allTurns.length > 0 ? allTurns[allTurns.length - 1] : null;
                    if (lastTurn && lastTurn.querySelector('.thinking-progress-icon.in-progress, ms-thought-chunk .in-progress, [class*="thinking-progress-icon"][class*="in-progress"], loading-indicator, .loading-indicator, mat-spinner, mat-progress-bar')) {
                        return true;
                    }
                    return false;
                }

                function extractCxpBlock(text) {
                    if (!text) return null;

                    // Evaluates the "Meat" between the buns
                    function isBurgerValidAndBalanced(burgerStr) {
                        if (!burgerStr || burgerStr.length < 20) return false;

                        // 1. Must contain at least one real mutation/export operational tag (never <comment> or <pull_files> alone)
                        const hasAction = /<(?:replace_block|create_file|delete_file|rename_file|export_files)\b/i.test(burgerStr);
                        if (!hasAction) return false;

                        // Reject pull_files wrappers masquerading as code patches
                        if (/<pull_files\b/i.test(burgerStr) && !/<(?:replace_block|create_file|delete_file|rename_file)\b/i.test(burgerStr)) {
                            return false;
                        }

                        // 2. Exactly one open wrapper and one close wrapper in this candidate slice
                        const openWrapper = (burgerStr.match(/<(?:cxp|omni_cxp|patch)\b/gi) || []).length;
                        const closeWrapper = (burgerStr.match(/<\/(?:cxp|omni_cxp|patch)>/gi) || []).length;
                        if (openWrapper !== 1 || closeWrapper !== 1) return false;

                        // 3. Paired Container Tags Balance Sentry
                        const tagCount = (name) => (burgerStr.match(new RegExp('<' + name + '\\b', 'gi')) || []).length;
                        const endTagCount = (name) => (burgerStr.match(new RegExp('<\\/' + name + '>', 'gi')) || []).length;

                        if (tagCount('replace_block') !== endTagCount('replace_block')) return false;
                        if (tagCount('create_file') !== endTagCount('create_file')) return false;
                        if (tagCount('comment') !== endTagCount('comment')) return false;
                        if (tagCount('find') !== endTagCount('find')) return false;
                        if (tagCount('replace_with') !== endTagCount('replace_with')) return false;
                        if (tagCount('content') !== endTagCount('content')) return false;

                        return true;
                    }

                    // Top-Down Sequential Hamburger Scanner with "Tightest Bun Rule"
                    const openRegex = /<(?:cxp|omni_cxp|patch)\b[^>]*>/gi;
                    const closeRegex = /<\/(?:cxp|omni_cxp|patch)>/gi;
                    const validBurgers = [];
                    let searchIndex = 0;

                    while (searchIndex < text.length) {
                        closeRegex.lastIndex = searchIndex;
                        const closeMatch = closeRegex.exec(text);
                        if (!closeMatch) break;

                        const closeStart = closeMatch.index;
                        const closeEnd = closeStart + closeMatch[0].length;

                        // Find the tightest (latest) opening tag before this closing tag
                        openRegex.lastIndex = searchIndex;
                        let lastOpenMatch = null;
                        let oMatch;
                        while ((oMatch = openRegex.exec(text)) !== null) {
                            if (oMatch.index >= closeStart) break;
                            lastOpenMatch = oMatch;
                        }

                        if (lastOpenMatch) {
                            const candidate = text.substring(lastOpenMatch.index, closeEnd).trim();
                            if (isBurgerValidAndBalanced(candidate)) {
                                validBurgers.push(candidate);
                                searchIndex = closeEnd;
                                continue;
                            }
                        }

                        searchIndex = closeEnd;
                    }

                    if (validBurgers.length > 0) {
                        return validBurgers.join('\n\n');
                    }

                    return null;
                }

                function extractPythonBlock(text) {
                    if (!text) return null;

                    const pairs = [
                        {
                            open: /<execute_python\b[^>]*>/gi,
                            close: /<\/execute_python>/gi,
                            innerTagCheck: /<\/?(?:execute_python|omni_action)\b/i
                        },
                        {
                            open: /<omni_action\s+name=["']execute_python["'][^>]*>/gi,
                            close: /<\/omni_action>/gi,
                            innerTagCheck: /<\/?omni_action\b/i
                        }
                    ];

                    let foundScript = null;

                    for (const pair of pairs) {
                        let searchIndex = 0;
                        while (searchIndex < text.length) {
                            pair.close.lastIndex = searchIndex;
                            const closeMatch = pair.close.exec(text);
                            if (!closeMatch) break;

                            const closeStart = closeMatch.index;
                            const closeEnd = closeStart + closeMatch[0].length;

                            pair.open.lastIndex = searchIndex;
                            let lastOpenMatch = null;
                            let oMatch;
                            while ((oMatch = pair.open.exec(text)) !== null) {
                                if (oMatch.index >= closeStart) break;
                                lastOpenMatch = oMatch;
                            }

                            if (lastOpenMatch) {
                                const openEnd = lastOpenMatch.index + lastOpenMatch[0].length;
                                const innerCode = text.substring(openEnd, closeStart).trim();

                                if (innerCode.length > 0 && !pair.innerTagCheck.test(innerCode)) {
                                    let cleanCode = innerCode
                                        .replace(/^```(?:python|py)?[\r\n]+/i, '')
                                        .trim();
                                    if (cleanCode.endsWith('```')) {
                                        cleanCode = cleanCode.slice(0, -3).trim();
                                    }

                                    if (cleanCode.length > 0) {
                                        foundScript = cleanCode;
                                        searchIndex = closeEnd;
                                        continue;
                                    }
                                }
                            }
                            searchIndex = closeEnd;
                        }
                        if (foundScript) break;
                    }

                    return foundScript;
                }

                function extractPullFilesBlock(text) {
                    if (!text) return null;

                    const selfClosingRegex = /<(?:pull_files|omni_action\s+name=["']pull_files["'])\b[^>]*?\/>/gi;
                    let scMatch;
                    let lastSelfClosing = null;
                    while ((scMatch = selfClosingRegex.exec(text)) !== null) {
                        const raw = scMatch[0].trim();
                        if (/files?\s*=/i.test(raw)) {
                            lastSelfClosing = raw;
                        }
                    }
                    if (lastSelfClosing) return lastSelfClosing;

                    const pairs = [
                        {
                            open: /<pull_files\b[^>]*>/gi,
                            close: /<\/pull_files>/gi,
                            innerTagCheck: /<\/?pull_files\b/i
                        },
                        {
                            open: /<omni_action\s+name=["']pull_files["'][^>]*>/gi,
                            close: /<\/omni_action>/gi,
                            innerTagCheck: /<\/?omni_action\b/i
                        }
                    ];

                    let foundPull = null;

                    for (const pair of pairs) {
                        let searchIndex = 0;
                        while (searchIndex < text.length) {
                            pair.close.lastIndex = searchIndex;
                            const closeMatch = pair.close.exec(text);
                            if (!closeMatch) break;

                            const closeStart = closeMatch.index;
                            const closeEnd = closeStart + closeMatch[0].length;

                            pair.open.lastIndex = searchIndex;
                            let lastOpenMatch = null;
                            let oMatch;
                            while ((oMatch = pair.open.exec(text)) !== null) {
                                if (oMatch.index >= closeStart) break;
                                lastOpenMatch = oMatch;
                            }

                            if (lastOpenMatch) {
                                const openEnd = lastOpenMatch.index + lastOpenMatch[0].length;
                                const inner = text.substring(openEnd, closeStart).trim();
                                const fullBurger = text.substring(lastOpenMatch.index, closeEnd).trim();

                                const hasAction = /<file\b/i.test(inner) || /files?\s*=/i.test(lastOpenMatch[0]);
                                const fileOpenCount = (inner.match(/<file\b/gi) || []).length;
                                const fileCloseCount = (inner.match(/<\/file>/gi) || []).length;
                                const isBalanced = fileOpenCount === fileCloseCount;

                                if (hasAction && isBalanced && !pair.innerTagCheck.test(inner)) {
                                    foundPull = fullBurger;
                                    searchIndex = closeEnd;
                                    continue;
                                }
                            }
                            searchIndex = closeEnd;
                        }
                        if (foundPull) break;
                    }

                    return foundPull;
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
                        clone.style.display = 'block';
                        clone.querySelectorAll('ms-thought-chunk, .author-label, .header, .timestamp, .info-container, .turn-information, loading-indicator, .turn-footer, button, .actions-container, ms-run-button, [data-omni-voice-btn]').forEach(el => el.remove());
                        clone.querySelectorAll('ms-text-chunk, ms-cmark-node, ms-code-block, pre, code').forEach(el => { el.style.display = 'block'; });
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

                        if (el.isContentEditable || el.getAttribute('contenteditable') === 'true') {
                            var range = document.createRange();
                            range.selectNodeContents(el);
                            range.collapse(false);
                            var sel = window.getSelection();
                            sel.removeAllRanges();
                            sel.addRange(range);

                            var currentContent = (el.innerText || el.textContent || '').trim();
                            var separator = currentContent.length > 0 ? '\n\n' : '';
                            var toInsert = separator + text;

                            var inserted = false;
                            try {
                                inserted = document.execCommand('insertText', false, toInsert);
                            } catch(_) {}

                            if (!inserted) {
                                var textNode = document.createTextNode(toInsert);
                                el.appendChild(textNode);
                                var newRange = document.createRange();
                                newRange.selectNodeContents(el);
                                newRange.collapse(false);
                                sel.removeAllRanges();
                                sel.addRange(newRange);
                            }
                            el.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
                            el.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
                        } else {
                            var currentVal = el.value || '';
                            var isSingleLine = el.tagName === 'INPUT';
                            var separator = '';
                            if (currentVal.length > 0) {
                                if (isSingleLine) {
                                    separator = currentVal.endsWith(' ') ? '' : ' ';
                                } else {
                                    separator = currentVal.endsWith('\n\n') ? '' : (currentVal.endsWith('\n') ? '\n' : '\n\n');
                                }
                            }
                            var newVal = currentVal + separator + text;
                            var proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                            var descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
                            if (descriptor && descriptor.set) {
                                descriptor.set.call(el, newVal);
                            } else {
                                el.value = newVal;
                            }
                            try {
                                el.selectionStart = el.selectionEnd = newVal.length;
                            } catch(_) {}
                            el.dispatchEvent(new Event('focus', { bubbles: true }));
                            el.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
                            try {
                                el.dispatchEvent(new InputEvent('input', {
                                    bubbles: true,
                                    cancelable: true,
                                    inputType: 'insertText',
                                    data: separator + text
                                }));
                            } catch(_) {}
                            el.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
                            try { el.scrollTop = el.scrollHeight; } catch(_) {}
                        }
                    } catch(e) {
                        try { el.value = (el.value ? el.value + '\n\n' : '') + text; } catch(_) {}
                        el.dispatchEvent(new Event('input', { bubbles: true }));
                    }
                }

                            function isPythonBinaryFile(name, mime) {
                const n = (name || '').toLowerCase();
                const m = (mime || '').toLowerCase();
                if (m.startsWith('image/') || m.startsWith('audio/') || m.startsWith('video/')) return true;
                if (m.includes('pdf') || m.includes('zip') || m.includes('tar') || m.includes('gzip') || m.includes('octet-stream')) return true;
                const binaryExts = ['.zip', '.rar', '.7z', '.tar', '.gz', '.jpg', '.jpeg', '.png', '.webp', '.gif', '.mp3', '.wav', '.mp4', '.pdf', '.bin', '.wasm'];
                return binaryExts.some(ext => n.endsWith(ext));
            }

            window.__omniDeliverPythonResult = async function(textResult, fileName, fileBase64, mimeType) {
                if (window.OmniPythonBridge && window.OmniPythonBridge.log) {
                    window.OmniPythonBridge.log('LIVE_AUTO_JS', 'Delivering python output to AI Studio (' + (textResult ? textResult.length : 0) + ' chars, file=' + fileName + ')...');
                }

                let effectivePrompt = textResult || '';
                let needsMediaUpload = false;

                // 1. Direct [AIS_METADATA] for text/code results (0ms upload wait)
                if (fileName && fileBase64) {
                    if (!isPythonBinaryFile(fileName, mimeType)) {
                        try {
                            const binStr = atob(fileBase64.includes(',') ? fileBase64.split(',')[1] : fileBase64);
                            const bytes = new Uint8Array(binStr.length);
                            for (let i = 0; i < binStr.length; i++) bytes[i] = binStr.charCodeAt(i);
                            const textBody = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
                            effectivePrompt += '\n\n[AIS_METADATA] [Attached file: name="' + fileName + '", mime_type="' + (mimeType || 'text/plain') + '"]\n--- START OF FILE ' + fileName + ' ---\n\n' + textBody;
                            if (window.OmniPythonBridge && window.OmniPythonBridge.log) {
                                window.OmniPythonBridge.log('LIVE_AUTO_JS', '⚡ Delivered Python text file "' + fileName + '" directly via [AIS_METADATA] (0ms wait)');
                            }
                        } catch(_) {
                            needsMediaUpload = true;
                        }
                    } else {
                        needsMediaUpload = true;
                    }
                }

                // 2. Binary Media Attachments (Images, audio, video, zip, pdf) via DataTransfer
                if (needsMediaUpload && fileName && fileBase64) {
                    try {
                        const fileInput = document.querySelector('input[data-test-upload-file-input], input[type="file"].file-input, input[type="file"]');
                        if (fileInput) {
                            const dt = new DataTransfer();
                            const byteChars = atob(fileBase64.includes(',') ? fileBase64.split(',')[1] : fileBase64);
                            const byteArray = new Uint8Array(byteChars.length);
                            for (let i = 0; i < byteChars.length; i++) byteArray[i] = byteChars.charCodeAt(i);
                            const blob = new Blob([byteArray], { type: mimeType || 'application/octet-stream' });
                            const file = new File([blob], fileName, { type: mimeType || 'application/octet-stream' });
                            dt.items.add(file);
                            fileInput.files = dt.files;
                            fileInput.dispatchEvent(new Event('change', { bubbles: true }));
                            await delay(1200);

                            let upTicks = 0;
                            while (upTicks < 60) {
                                const prog = document.querySelector('mat-progress-bar, mat-spinner, ms-prompt-box .loading-indicator, .media-upload-progress');
                                if (!prog) break;
                                await delay(500);
                                upTicks++;
                            }
                        }
                    } catch(e) {
                        if (window.OmniPythonBridge && window.OmniPythonBridge.log) {
                            window.OmniPythonBridge.log('LIVE_AUTO_JS_ERR', 'Failed attaching binary media: ' + e.message);
                        }
                    }
                }

                async function humanTap(el) {
                    if (!el) return;
                    try {
                        const rect = el.getBoundingClientRect();
                        const w = Math.max(4, rect.width || 24);
                        const h = Math.max(4, rect.height || 24);
                        const clientX = Math.round(rect.left + w / 2 + (Math.random() - 0.5) * w * 0.4);
                        const clientY = Math.round(rect.top + h / 2 + (Math.random() - 0.5) * h * 0.4);
                        const mOpts = { bubbles: true, cancelable: true, view: window, clientX: clientX, clientY: clientY, button: 0, buttons: 1 };
                        const pOpts = Object.assign({}, mOpts, { pointerId: 1, pointerType: 'touch', isPrimary: true, pressure: 0.55 });
                        try { el.dispatchEvent(new PointerEvent('pointerdown', pOpts)); } catch(_) {}
                        try { el.dispatchEvent(new MouseEvent('mousedown', mOpts)); } catch(_) {}
                        await delay(30 + Math.floor(Math.random() * 45));
                        try { el.dispatchEvent(new PointerEvent('pointerup', Object.assign({}, pOpts, { buttons: 0, pressure: 0 }))); } catch(_) {}
                        try { el.dispatchEvent(new MouseEvent('mouseup', Object.assign({}, mOpts, { buttons: 0 }))); } catch(_) {}
                        el.click();
                    } catch(_) {
                        el.click();
                    }
                }

                // 3. Inject text into textarea and click Run
                const promptArea = document.querySelector('textarea[formcontrolname="promptText"], textarea[aria-label="Enter a prompt"], textarea');
                if (promptArea) {
                    await humanTap(promptArea);
                    await delay(140 + Math.floor(Math.random() * 180));
                    safeInjectText(promptArea, effectivePrompt);
                    await delay(650 + Math.floor(Math.random() * 750));
                    let readyWait = 0;
                    while (readyWait < 25) {
                        const submitBtn = document.querySelector('ms-run-button button:not(.stoppable), button.ctrl-enter-submits:not(.stoppable), button[type="submit"]:not(.stoppable)');
                        if (submitBtn && isRunButtonReady(submitBtn)) {
                            await humanTap(submitBtn);
                            break;
                        }
                        await delay(300);
                        readyWait++;
                    }
                } else {
                    if (window.OmniPythonBridge && window.OmniPythonBridge.log) {
                        window.OmniPythonBridge.log('LIVE_AUTO_JS_ERR', 'Prompt textarea not found in DOM.');
                    }
                }
            };

                window.__omniDispatchedHashes = window.__omniDispatchedHashes || new Set();

                function hashPayload(str) {
                    const s = String(str || '').trim();
                    let h = 2166136261;
                    for (let i = 0; i < s.length; i++) {
                        h ^= s.charCodeAt(i);
                        h = Math.imul(h, 16777619);
                    }
                    return (h >>> 0).toString(16) + '_' + s.length;
                }

                window.__omniAuditPendingCxp = function() {
                    if (!window.__omniLiveAutoPilotActive) return;
                    processAutoPilotTick(false, false);
                };

                window.__omniAuditArmedTurn = function(logDiag) {
                    if (!window.__omniLiveAutoPilotActive) return;
                    window.__omniLastText = '';
                    window.__omniStillTicks = 0;
                    processAutoPilotTick(true, !!logDiag);
                };

                function dispatchActionsFromPayload(rawText, sourceTag, targetDomTurn) {
                    if (!rawText) return false;
                    let dispatchedAny = false;

                    // 1. Detect & Auto-Bridge CXP Patch Markup to Conduit IDE
                    const cxpPayload = extractCxpBlock(rawText);
                    if (cxpPayload) {
                        const cxpHash = 'cxp_' + hashPayload(cxpPayload);
                        if (!window.__omniDispatchedHashes.has(cxpHash)) {
                            window.__omniDispatchedHashes.add(cxpHash);
                            dispatchedAny = true;
                            if (window.OmniIdeBridge && window.OmniIdeBridge.log) {
                                window.OmniIdeBridge.log('AUTOPILOT_PIPELINE', '📦 [' + sourceTag + '] Valid CXP burger extracted (' + cxpPayload.length + ' chars, hash=' + cxpHash + '). Beaming to Conduit IDE...');
                            }
                            if (window.OmniIdeBridge && window.OmniIdeBridge.dispatchCxpToIde) {
                                window.OmniIdeBridge.dispatchCxpToIde(cxpPayload);
                            }
                        }
                    }

                    // 2. Detect & Auto-Bridge Python Tool Execution to Nexus
                    const pythonScript = extractPythonBlock(rawText);
                    if (pythonScript) {
                        const pyHash = 'py_' + hashPayload(pythonScript);
                        if (!window.__omniDispatchedHashes.has(pyHash)) {
                            window.__omniDispatchedHashes.add(pyHash);
                            dispatchedAny = true;
                            if (window.OmniPythonBridge && window.OmniPythonBridge.executeFromLivePage) {
                                window.OmniPythonBridge.log('LIVE_AUTO', '[' + sourceTag + '] Captured Python block (' + pythonScript.length + ' chars). Executing on Nexus...');
                                window.OmniPythonBridge.executeFromLivePage(pythonScript);
                            }
                        }
                    }

                    // 3. Detect & Auto-Bridge Project File Pulling to Conduit IDE
                    const pullPayload = extractPullFilesBlock(rawText);
                    if (pullPayload) {
                        const pullHash = 'pull_' + hashPayload(pullPayload);
                        if (!window.__omniDispatchedHashes.has(pullHash)) {
                            window.__omniDispatchedHashes.add(pullHash);
                            dispatchedAny = true;
                            if (window.OmniIdeBridge && window.OmniIdeBridge.requestFilePull) {
                                if (window.OmniIdeBridge.log) {
                                    window.OmniIdeBridge.log('AUTOPILOT_PIPELINE', '📂 [' + sourceTag + '] Captured pull_files request (' + pullPayload.length + ' chars). Beaming to Conduit IDE...');
                                }
                                window.OmniIdeBridge.requestFilePull(pullPayload);
                            }
                        }
                    }
                    return dispatchedAny;
                }

                function processAutoPilotTick(forceImmediate, logDiagnostics) {
                    if (!window.__omniLiveAutoPilotActive) return;

                    if (forceImmediate) {
                        try {
                            const scroller = document.querySelector('ms-autoscroll-container, .chat-view-container, .chat-history');
                            if (scroller) scroller.scrollTop = scroller.scrollHeight;
                        } catch(_) {}
                    }

                    const allModelContainers = Array.from(document.querySelectorAll('.chat-turn-container.model, ms-chat-turn .chat-turn-container.model, [data-turn-role="Model"], [data-turn-role="model" i], ms-chat-turn'))
                        .filter(el => {
                            if (el.classList.contains('user')) return false;
                            if ((el.getAttribute('data-turn-role') || '').toLowerCase() === 'user') return false;
                            if (el.tagName && el.tagName.toLowerCase() === 'ms-chat-turn') {
                                return !el.querySelector('.chat-turn-container.user, [data-turn-role="User" i]');
                            }
                            return !el.closest('.chat-turn-container.user, [data-turn-role="User" i]');
                        });
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
                    if (!latestTurn && allModelContainers.length > 0) {
                        latestTurn = allModelContainers[allModelContainers.length - 1];
                    }

                    // Priority 1: Wire Interceptor (Instant, zero stillness timer, immune to virtual scroll & Markdown mangling)
                    const latestWire = (window.__omniWire && window.__omniWire.getLatest) ? window.__omniWire.getLatest() : null;
                    if (latestWire) {
                        if (latestWire.error) {
                            const lowerErr = String(latestWire.error).toLowerCase();
                            if (latestWire.httpStatus === 429 || latestWire.statusCode === 8 || lowerErr.includes('quota') || lowerErr.includes('resource_exhausted')) {
                                if (!latestWire.__quotaReported) {
                                    latestWire.__quotaReported = true;
                                    if (window.OmniAnalyticsBridge && window.OmniAnalyticsBridge.reportQuotaHit) {
                                        window.OmniAnalyticsBridge.reportQuotaHit(window.__omniProfileId || 'default', String(latestWire.error).slice(0, 120));
                                    }
                                }
                            }
                        }
                        const wirePayload = latestWire.stitchedOutput || latestWire.output || '';
                        if (latestWire.done && latestWire.statusCode === 0 && wirePayload.trim().length > 0) {
                            if (latestWire.id !== window.__omniLastDispatchedWireId) {
                                window.__omniLastDispatchedWireId = latestWire.id;
                                const didDispatch = dispatchActionsFromPayload(wirePayload, 'WIRE_INTERCEPT', latestTurn);
                                if (didDispatch || !forceImmediate) return;
                            }
                            // Do NOT return early here: allow DOM Fallback to catch subsequent turns if wire missed them!
                        }
                        if (latestWire.active) {
                            const wireLen = (latestWire.output ? latestWire.output.length : 0) + (latestWire.thoughts ? latestWire.thoughts.length : 0);
                            if (window.__omniTrackedWireId !== latestWire.id || wireLen !== window.__omniTrackedWireLen) {
                                const isNewWire = window.__omniTrackedWireId !== latestWire.id;
                                window.__omniTrackedWireId = latestWire.id;
                                window.__omniTrackedWireLen = wireLen;
                                window.__omniLastWireActivityAt = isNewWire ? (latestWire.startedAt || Date.now()) : Date.now();
                            }
                            const uiStillGen = checkUiGenerating();
                            const idleMs = Date.now() - (window.__omniLastWireActivityAt || latestWire.startedAt || Date.now());
                            const staleLimit = uiStillGen ? 12000 : 2500;
                            if (!forceImmediate && (uiStillGen || idleMs <= staleLimit)) {
                                return; // Actively streaming over wire; wait for exact stream close
                            }
                        }
                    }

                    // Priority 2: DOM Fallback (covers unhooked endpoints & pre-existing turns)
                    const uiGen = checkUiGenerating();
                    if (uiGen || !latestTurn) {
                        if (logDiagnostics && window.OmniIdeBridge && window.OmniIdeBridge.log) {
                            window.OmniIdeBridge.log('AUTOPILOT_PIPELINE', 'ℹ️ [ARM_AUDIT] Skipped DOM scan (uiGenerating=' + uiGen + ', hasLatestTurn=' + !!latestTurn + ')');
                        }
                        return;
                    }

                    if (window.__omniLastTurnRef !== latestTurn || window.__omniLastTurnCount !== allModelContainers.length) {
                        window.__omniLastTurnRef = latestTurn;
                        window.__omniLastTurnCount = allModelContainers.length;
                        window.__omniLastText = '';
                        window.__omniStillTicks = 0;
                    }

                    const screenText = getScreenText(latestTurn);
                    if (!screenText) {
                        if (logDiagnostics && window.OmniIdeBridge && window.OmniIdeBridge.log) {
                            window.OmniIdeBridge.log('AUTOPILOT_PIPELINE', 'ℹ️ [ARM_AUDIT] Latest model turn yielded 0 chars from DOM.');
                        }
                        return;
                    }

                    if (forceImmediate) {
                        window.__omniLastText = screenText;
                        window.__omniStillTicks = 4;
                        const didDispatchDom = dispatchActionsFromPayload(screenText, 'DOM_ARM_AUDIT', latestTurn);
                        if (!didDispatchDom && logDiagnostics && window.OmniIdeBridge && window.OmniIdeBridge.log) {
                            window.OmniIdeBridge.log('AUTOPILOT_PIPELINE', 'ℹ️ [ARM_AUDIT] Scanned ' + screenText.length + ' chars in latest turn; no new CXP/Python/Pull block found.');
                        }
                        return;
                    }

                    window.__omniLastText = window.__omniLastText || '';
                    window.__omniStillTicks = window.__omniStillTicks || 0;

                    if (screenText !== window.__omniLastText) {
                        window.__omniStillTicks = 0;
                        window.__omniLastText = screenText;
                        return;
                    } else {
                        window.__omniStillTicks++;
                    }

                    if (window.__omniStillTicks < 4) return;
                    dispatchActionsFromPayload(screenText, 'DOM_FALLBACK', latestTurn);
                }

                if (!window.__omniWireListenerAttached) {
                    window.__omniWireListenerAttached = true;
                    window.addEventListener('__omniWireUpdate', function(ev) {
                        var rec = ev && ev.detail;
                        if (rec && rec.done) {
                            processAutoPilotTick();
                        }
                    });
                }

                window.__omniSentinelInterval = setInterval(() => {
                    hookPromptSubmission();
                    checkQuotaErrors();

                    const latestWire = (window.__omniWire && window.__omniWire.getLatest) ? window.__omniWire.getLatest() : null;
                    const isGen = (latestWire && latestWire.active) ? true : checkUiGenerating();

                    if (isGen !== window.__omniLastReportedGenState) {
                        window.__omniLastReportedGenState = isGen;
                        if (window.OmniIdeBridge && window.OmniIdeBridge.reportGenerationActive) {
                            window.OmniIdeBridge.reportGenerationActive(isGen);
                        }
                    }

                    if (!window.__omniLiveAutoPilotActive) return;

                    if (isGen) {
                        try {
                            const scroller = document.querySelector('ms-autoscroll-container, .chat-view-container, .chat-history');
                            if (scroller) scroller.scrollTop = scroller.scrollHeight;
                        } catch(_) {}
                        window.__omniWasGenerating = true;
                        window.__omniStillTicks = 0;
                        return;
                    }

                    if (window.__omniWasGenerating) {
                        window.__omniWasGenerating = false;
                        if (window.OmniIdeBridge && window.OmniIdeBridge.log) {
                            window.OmniIdeBridge.log('AUTOPILOT_PIPELINE', '🔍 [STAGE 1: SETTLED] Generation finished.');
                        }
                    }

                    processAutoPilotTick();
                }, 400);
            })();
        """.trimIndent()
    }
}