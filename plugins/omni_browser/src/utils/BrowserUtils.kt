package com.omni.plugin.browser.utils

import android.net.Uri
import java.util.Locale

const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

val BOT_BYPASS_POLYFILL = """
(function() {
    try { delete Object.getPrototypeOf(navigator).webdriver; } catch(e) {}
    try {
        if (!window.chrome) {
            window.chrome = { app: { isInstalled: false }, runtime: { connect: function(){}, sendMessage: function(){} }, csi: function(){}, loadTimes: function(){} };
            window.chrome.runtime.connect.toString = function() { return "function connect() { [native code] }"; };
            window.chrome.runtime.sendMessage.toString = function() { return "function sendMessage() { [native code] }"; };
        }
    } catch(e) {}
    try {
        const originalCreateElement = document.createElement;
        document.createElement = function(tagName) {
            const el = originalCreateElement.call(document, tagName);
            if (tagName.toLowerCase() === 'iframe') {
                el.addEventListener('load', function() {
                    try { delete Object.getPrototypeOf(this.contentWindow.navigator).webdriver; } catch(e) {}
                    try { this.contentWindow.chrome = window.chrome; } catch(e) {}
                });
            }
            return el;
        };
        document.createElement.toString = function() { return "function createElement() { [native code] }"; };
    } catch(e) {}
    try {
        if (navigator.plugins.length === 0) {
            const mockP = [
                { name: 'PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
                { name: 'Chrome PDF Viewer', filename: 'internal-pdf-viewer', description: 'Google Chrome PDF' },
                { name: 'Chromium PDF Viewer', filename: 'internal-pdf-viewer', description: 'Chromium PDF' },
                { name: 'Microsoft Edge PDF Viewer', filename: 'internal-pdf-viewer', description: 'Edge PDF' },
                { name: 'WebKit built-in PDF', filename: 'internal-pdf-viewer', description: 'WebKit PDF' }
            ];
            const pArr = [];
            mockP.forEach(p => { const pl = Object.create(Plugin.prototype); Object.assign(pl, p); pArr.push(pl); });
            Object.setPrototypeOf(pArr, PluginArray.prototype);
            const pGetter = function() { return pArr; };
            pGetter.toString = function() { return "function get plugins() { [native code] }"; };
            Object.defineProperty(Object.getPrototypeOf(navigator), 'plugins', { get: pGetter, configurable: true });
            Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] });
        }
    } catch(e) {}
    try {
        if (!window.Notification) window.Notification = { permission: 'default', requestPermission: function() { return Promise.resolve('default'); } };
        if (window.navigator.permissions) {
            const originalQuery = window.navigator.permissions.query;
            window.navigator.permissions.query = function(parameters) {
                if (parameters.name === 'notifications') return Promise.resolve({ state: window.Notification.permission });
                return originalQuery.call(navigator, parameters);
            };
            window.navigator.permissions.query.toString = function() { return "function query() { [native code] }"; };
        } 
    } catch(e) {}
})();
""".trimIndent()

val BLOB_INTERCEPTOR_SCRIPT = """
(function() {
    if (window.__omniBlobHooked && window.__omniIframeHooked) return;
    window.__omniBlobHooked = true;
    window.__omniIframeHooked = true;

    window.__omniBlobMap = window.__omniBlobMap || new Map();

    function getTopBlobMap() {
        try {
            if (window.top && window.top.__omniBlobMap) return window.top.__omniBlobMap;
        } catch(_) {}
        return window.__omniBlobMap;
    }

    function shipBlobToHost(dataUrl, mimeType, filename) {
        if (window.OmniBlobDownloader && window.OmniBlobDownloader.processBlob) {
            window.OmniBlobDownloader.processBlob(dataUrl, mimeType, filename);
            return;
        }
        try {
            if (window.top && window.top !== window) {
                window.top.postMessage({
                    type: '__OMNI_BLOB_SHIP__',
                    dataUrl: dataUrl,
                    mimeType: mimeType,
                    filename: filename
                }, '*');
            }
        } catch(_) {}
    }

    // Top-level listener for sandboxed iframe postMessage
    if (window === window.top) {
        window.addEventListener('message', function(ev) {
            if (ev.data && ev.data.type === '__OMNI_BLOB_SHIP__') {
                if (window.OmniBlobDownloader && window.OmniBlobDownloader.log) {
                    window.OmniBlobDownloader.log('BLOB_IFRAME_MSG', 'Received blob from sandboxed subframe: ' + ev.data.filename);
                }
                shipBlobToHost(ev.data.dataUrl, ev.data.mimeType, ev.data.filename);
            }
        }, true);
    }

    function hookWindowScope(targetWin) {
        if (!targetWin) return;
        try {
            if (targetWin.__omniScopeHooked) return;
            targetWin.__omniScopeHooked = true;
        } catch(_) { return; }

        function hookCreateObjectURL(target) {
            if (!target || !target.createObjectURL || target.createObjectURL.__omniHooked) return;
            const orig = target.createObjectURL;
            const hooked = function(blob) {
                const url = orig.apply(this, arguments);
                try {
                    if (blob instanceof Blob) {
                        const bMap = getTopBlobMap();
                        bMap.set(url, blob);
                        if (targetWin.__omniBlobMap) targetWin.__omniBlobMap.set(url, blob);
                        if (bMap.size > 400) {
                            const firstKey = bMap.keys().next().value;
                            bMap.delete(firstKey);
                        }
                        if (window.OmniBlobDownloader && window.OmniBlobDownloader.log) {
                            window.OmniBlobDownloader.log('BLOB_MAP', 'Vaulted Blob in RAM: ' + url + ' (' + blob.size + 'b, type: ' + blob.type + ')');
                        }
                    }
                } catch(e) {}
                return url;
            };
            hooked.__omniHooked = true;
            try {
                target.createObjectURL = hooked;
            } catch(e) {
                try {
                    Object.defineProperty(target, 'createObjectURL', {
                        value: hooked,
                        writable: true,
                        configurable: true
                    });
                } catch(_) {}
            }
        }

        try { hookCreateObjectURL(targetWin.URL); } catch(_) {}
        try { if (targetWin.webkitURL) hookCreateObjectURL(targetWin.webkitURL); } catch(_) {}

        const origRevoke = targetWin.URL ? targetWin.URL.revokeObjectURL : null;
        if (origRevoke && !origRevoke.__omniHooked) {
            const hookedRevoke = function(url) {
                setTimeout(function() {
                    try { origRevoke.call(targetWin.URL, url); } catch(e) {}
                    try {
                        const bMap = getTopBlobMap();
                        bMap.delete(url);
                    } catch(_) {}
                }, 30000);
            };
            hookedRevoke.__omniHooked = true;
            try { targetWin.URL.revokeObjectURL = hookedRevoke; } catch(_) {}
        }

        function extractAndShip(blobObj, mimeType, filename) {
            if (window.OmniBlobDownloader && window.OmniBlobDownloader.log) {
                window.OmniBlobDownloader.log('BLOB_CLICK', 'Intercepted anchor click for: ' + filename + ' (' + blobObj.size + 'b). Processing via RAM...');
            }
            const reader = new targetWin.FileReader();
            reader.onloadend = function() {
                shipBlobToHost(reader.result, mimeType || blobObj.type || 'application/octet-stream', filename || 'download');
            };
            reader.onerror = function() {
                if (window.OmniBlobDownloader && window.OmniBlobDownloader.processBlob) {
                    window.OmniBlobDownloader.processBlob('ERROR', '', 'FileReader failed to read in-memory Blob');
                }
            };
            reader.readAsDataURL(blobObj);
        }

        // Hook anchor clicks in this window scope
        if (targetWin.HTMLAnchorElement && targetWin.HTMLAnchorElement.prototype) {
            const origAnchorClick = targetWin.HTMLAnchorElement.prototype.click;
            if (origAnchorClick && !origAnchorClick.__omniHooked) {
                targetWin.HTMLAnchorElement.prototype.click = function() {
                    try {
                        const href = this.href || '';
                        const downloadName = this.getAttribute('download') || this.download || '';
                        const bMap = getTopBlobMap();
                        if (href.startsWith('blob:') && bMap && bMap.has(href)) {
                            const blob = bMap.get(href);
                            extractAndShip(blob, blob.type, downloadName || 'download');
                            return;
                        }
                    } catch(e) {}
                    return origAnchorClick.apply(this, arguments);
                };
                targetWin.HTMLAnchorElement.prototype.click.__omniHooked = true;
            }
        }

        try {
            targetWin.document.addEventListener('click', function(e) {
                const anchor = e.target && (e.target.tagName === 'A' ? e.target : (e.target.closest ? e.target.closest('a') : null));
                if (!anchor) return;

                const href = anchor.href || '';
                const downloadName = anchor.getAttribute('download') || anchor.download || '';
                val bMap = getTopBlobMap();

                if (href.startsWith('blob:') || anchor.hasAttribute('download')) {
                    if (href.startsWith('blob:') && bMap && bMap.has(href)) {
                        const blob = bMap.get(href);
                        extractAndShip(blob, blob.type, downloadName || 'download');
                        e.preventDefault();
                        e.stopPropagation();
                    } else if (href.startsWith('data:')) {
                        shipBlobToHost(href, '', downloadName || 'download');
                        e.preventDefault();
                        e.stopPropagation();
                    }
                }
            }, true);
        } catch(_) {}
    }

    // 1. Hook Current Top Window
    hookWindowScope(window);

    // 2. Hook All Existing and Future IFrames (MakerSuite / AI Studio Sub-frames)
    function scanAndHookIframes() {
        try {
            const iframes = document.querySelectorAll('iframe');
            iframes.forEach(function(ifr) {
                try {
                    if (ifr.contentWindow) {
                        hookWindowScope(ifr.contentWindow);
                    }
                } catch(_) {}
                if (!ifr.__omniLoadHooked) {
                    ifr.__omniLoadHooked = true;
                    ifr.addEventListener('load', function() {
                        try { if (ifr.contentWindow) hookWindowScope(ifr.contentWindow); } catch(_) {}
                    });
                }
            });
        } catch(_) {}
    }

    scanAndHookIframes();

    // Observe dynamic DOM insertions for sub-frames
    try {
        const observer = new MutationObserver(function(mutations) {
            scanAndHookIframes();
        });
        observer.observe(document.documentElement || document.body, {
            childList: true,
            subtree: true
        });
    } catch(_) {}
})();
""".trimIndent()

val DOM_SNAPSHOT_SCRIPT = """
(function() {
    try {
        var clone = document.documentElement.cloneNode(true);
        var head = clone.querySelector('head');
        if (head) {
            var existingBase = head.querySelector('base');
            if (!existingBase) {
                var base = document.createElement('base');
                base.href = window.location.href;
                head.insertBefore(base, head.firstChild);
            }
        }
        return '<!DOCTYPE html>\n' + clone.outerHTML;
    } catch(e) {
        return document.documentElement.outerHTML;
    }
})();
""".trimIndent()

val ERUDA_DEVTOOLS_SCRIPT = """
(function() {
    if (window.eruda) {
        if (window.eruda._isInit) {
            var devtools = eruda.get();
            if (devtools && devtools._isShow) {
                eruda.hide();
            } else {
                eruda.show();
            }
        } else {
            eruda.init();
            eruda.show();
        }
        return;
    }
    var script = document.createElement('script');
    script.src = 'https://cdn.jsdelivr.net/npm/eruda';
    script.onload = function() {
        eruda.init({
            tool: ['console', 'elements', 'network', 'resource', 'info', 'snippets', 'storage'],
            defaults: {
                displaySize: 60,
                transparency: 0.95,
                theme: 'Dark'
            }
        });
        eruda.show();
    };
    document.body.appendChild(script);
})();
""".trimIndent()

val CAPTCHA_DETECTOR_SCRIPT = """
(function() {
    const el = document.querySelector('[data-sitekey]');
    if (el) return el.getAttribute('data-sitekey');
    const iframe = document.querySelector('iframe[src*="recaptcha"], iframe[src*="turnstile"]');
    if (iframe) {
        const m = iframe.src.match(/k=([^&]+)/) || iframe.src.match(/sitekey=([^&]+)/);
        if (m) return m[1];
    }
    return '';
})();
""".trimIndent()

fun buildCaptchaInjectionScript(token: String): String {
    return """
        javascript:(function() {
            const token = '$token';
            const textareas = document.querySelectorAll('textarea[name="g-recaptcha-response"], #g-recaptcha-response');
            textareas.forEach(t => { t.value = token; t.innerHTML = token; });
            try {
                if (window.___grecaptcha_cfg && window.___grecaptcha_cfg.clients) {
                    Object.values(window.___grecaptcha_cfg.clients).forEach(client => {
                        for (const key in client) {
                            if (client[key] && typeof client[key].callback === 'function') {
                                client[key].callback(token);
                            }
                        }
                    });
                }
            } catch(e) {}
        })();
    """.trimIndent()
}

fun buildBlobExtractionScript(url: String, safeName: String, safeMime: String): String {
    return """
        (function() {
            const blobUrl = '$url';
            const targetName = '$safeName';
            const targetMime = '$safeMime';

            if (window.OmniBlobDownloader && window.OmniBlobDownloader.log) {
                window.OmniBlobDownloader.log('BLOB_EXTRACT', 'Initiating CSP-safe extraction for: ' + blobUrl);
            }

            // 1. Primary Strategy: In-Memory Blob Vault (Zero network, 100% CSP-Immune)
            if (window.__omniBlobMap && window.__omniBlobMap.has(blobUrl)) {
                if (window.OmniBlobDownloader && window.OmniBlobDownloader.log) {
                    window.OmniBlobDownloader.log('BLOB_VAULT', 'Found Blob in RAM (' + window.__omniBlobMap.get(blobUrl).size + ' bytes). Reading via FileReader...');
                }
                const blobObj = window.__omniBlobMap.get(blobUrl);
                const reader = new FileReader();
                reader.onloadend = function() {
                    if (window.OmniBlobDownloader && window.OmniBlobDownloader.processBlob) {
                        window.OmniBlobDownloader.processBlob(reader.result, targetMime || blobObj.type || 'application/octet-stream', targetName);
                    }
                };
                reader.onerror = function(e) {
                    if (window.OmniBlobDownloader && window.OmniBlobDownloader.processBlob) {
                        window.OmniBlobDownloader.processBlob('ERROR', '', 'FileReader error: ' + (e ? e.toString() : 'unknown'));
                    }
                };
                reader.readAsDataURL(blobObj);
                return;
            }

            // 2. Secondary Strategy: CSP-Safe Hidden IFrame Extraction (Bypasses connect-src entirely)
            if (window.OmniBlobDownloader && window.OmniBlobDownloader.log) {
                window.OmniBlobDownloader.log('BLOB_FALLBACK', 'Blob not in RAM map. Launching CSP-immune iframe extractor...');
            }

            try {
                const iframe = document.createElement('iframe');
                iframe.style.display = 'none';
                iframe.style.width = '0px';
                iframe.style.height = '0px';
                iframe.src = blobUrl;

                let resolved = false;

                iframe.onload = function() {
                    if (resolved) return;
                    resolved = true;
                    try {
                        const iDoc = iframe.contentDocument || iframe.contentWindow.document;
                        if (iDoc) {
                            const text = iDoc.body ? (iDoc.body.innerText || iDoc.body.textContent || '') : '';
                            if (text && text.length > 0) {
                                const base64Text = btoa(unescape(encodeURIComponent(text)));
                                const dataUrl = 'data:' + (targetMime || 'text/plain') + ';base64,' + base64Text;
                                if (window.OmniBlobDownloader && window.OmniBlobDownloader.processBlob) {
                                    window.OmniBlobDownloader.processBlob(dataUrl, targetMime || 'text/plain', targetName);
                                }
                                setTimeout(function() { try { document.body.removeChild(iframe); } catch(_) {} }, 1000);
                                return;
                            }
                        }
                    } catch(e) {
                        if (window.OmniBlobDownloader && window.OmniBlobDownloader.log) {
                            window.OmniBlobDownloader.log('IFRAME_WARN', 'Iframe DOM read exception: ' + e.toString());
                        }
                    }
                };

                iframe.onerror = function(err) {
                    if (window.OmniBlobDownloader && window.OmniBlobDownloader.log) {
                        window.OmniBlobDownloader.log('IFRAME_ERR', 'Iframe failed to load blob URL: ' + (err ? err.toString() : 'unknown'));
                    }
                };

                document.body.appendChild(iframe);

                setTimeout(function() {
                    if (!resolved) {
                        resolved = true;
                        if (window.OmniBlobDownloader && window.OmniBlobDownloader.processBlob) {
                            window.OmniBlobDownloader.processBlob('ERROR', '', 'Extraction timed out (Blob created before vault hook)');
                        }
                        try { document.body.removeChild(iframe); } catch(_) {}
                    }
                }, 6000);

            } catch (err) {
                if (window.OmniBlobDownloader && window.OmniBlobDownloader.processBlob) {
                    window.OmniBlobDownloader.processBlob('ERROR', '', 'Extractor exception: ' + err.toString());
                }
            }
        })();
    """.trimIndent()
}

fun buildAiStudioAutomationScript(prompt: String, sysPrompt: String, thinkingLevel: String, model: String): String {
    return """
        (async function() {
            if (window.__omniAutomating) {
                return;
            }
            window.__omniAutomating = true;

            const PROMPT = $prompt;
            const SYS_PROMPT = $sysPrompt;
            const THINKING_LEVEL = $thinkingLevel;
            const TARGET_MODEL = $model;

            const delay = (ms) => new Promise(r => setTimeout(r, ms));
            const randomDelay = (min, max) => delay(Math.floor(Math.random() * (max - min + 1) + min));

            function hostLog(tag, msg) {
                try {
                    if (window.OmniAutomator && window.OmniAutomator.onLog) {
                        window.OmniAutomator.onLog(tag, String(msg));
                    }
                } catch(_) {}
            }

            function updateStatus(msg) {
                hostLog('STATUS', msg);
                try {
                    if (window.OmniAutomator && window.OmniAutomator.onStatus) {
                        window.OmniAutomator.onStatus(String(msg));
                    }
                } catch(_) {}
            }

            try {
                hostLog('INIT', 'AI Studio Automator script launched. URL: ' + window.location.href);

                // 1. Auth check
                if (window.location.href.includes('accounts.google.com') || window.location.href.includes('/signin/')) {
                    const authErr = 'Account not logged in on this profile. Please log in to Google AI Studio first.';
                    hostLog('AUTH_ERR', authErr);
                    if (window.OmniAutomator) window.OmniAutomator.onError(authErr);
                    window.__omniAutomating = false;
                    return;
                }

                updateStatus('Waiting for AI Studio UI...');
                let mountAttempts = 0;
                let promptArea = null;

                while (mountAttempts < 30) {
                    promptArea = document.querySelector('textarea[formcontrolname="promptText"], textarea[aria-label="Enter a prompt"], textarea');
                    if (promptArea) break;
                    await delay(1000);
                    mountAttempts++;
                    if (mountAttempts % 5 === 0) {
                        hostLog('MOUNT', 'Polling UI (' + mountAttempts + '/30)...');
                    }
                }

                if (!promptArea) {
                    const err = 'Failed to locate prompt textarea after 30s.';
                    hostLog('MOUNT_ERR', err);
                    if (window.OmniAutomator) window.OmniAutomator.onError(err);
                    window.__omniAutomating = false;
                    return;
                }

                await randomDelay(1000, 2000);

                // 2. Configure Thinking Level if present
                try {
                    const thinkingSelect = document.querySelector('ms-thinking-level-setting mat-select, mat-select[aria-label*="Thinking"]');
                    if (thinkingSelect && THINKING_LEVEL !== 'Default') {
                        hostLog('SETTINGS', 'Selecting Thinking Level: ' + THINKING_LEVEL);
                        thinkingSelect.click();
                        await randomDelay(300, 600);
                        const options = Array.from(document.querySelectorAll('mat-option'));
                        const match = options.find(o => o.textContent.trim().toLowerCase().includes(THINKING_LEVEL.toLowerCase()));
                        if (match) {
                            match.click();
                            hostLog('SETTINGS', 'Applied Thinking Level: ' + match.textContent.trim());
                        } else {
                            document.body.click();
                        }
                        await randomDelay(200, 400);
                    }
                } catch(e) {
                    hostLog('SETTINGS_WARN', 'Thinking level setting failed: ' + e.message);
                }

                // 3. Configure System Instructions if provided
                if (SYS_PROMPT && SYS_PROMPT.length > 0) {
                    try {
                        updateStatus('Injecting System Instructions...');
                        const sysCard = document.querySelector('ms-system-instructions-panel .system-instructions-card, ms-system-instructions-panel button');
                        if (sysCard) {
                            sysCard.click();
                            await randomDelay(400, 700);
                            const sysTa = document.querySelector('ms-system-instructions textarea, textarea[aria-label*="System"], textarea[placeholder*="System"]');
                            if (sysTa) {
                                sysTa.focus();
                                sysTa.click();
                                await randomDelay(200, 400);
                                document.execCommand('insertText', false, SYS_PROMPT);
                                sysTa.dispatchEvent(new Event('input', { bubbles: true }));
                                hostLog('SYS_PROMPT', 'Injected ' + SYS_PROMPT.length + ' chars into System Instructions.');
                                await randomDelay(300, 600);
                            }
                        }
                    } catch(e) {
                        hostLog('SYS_PROMPT_ERR', 'System instructions injection failed: ' + e.message);
                    }
                }

                // Purge any lingering CDK backdrops
                document.querySelectorAll('.cdk-overlay-backdrop').forEach(b => {
                    try { b.click(); } catch(_) {}
                });
                await randomDelay(200, 400);

                // 4. Human-like Keyboard & Clipboard Insertion
                updateStatus('Emulating human typing into prompt area...');
                promptArea.focus();
                promptArea.click();
                await randomDelay(300, 600);
                document.execCommand('insertText', false, PROMPT);
                promptArea.dispatchEvent(new Event('input', { bubbles: true }));
                await randomDelay(800, 1500);

                // 5. Human-like Touch Submission (Exact Cortex Protocol)
                updateStatus('Submitting prompt to Gemini...');
                const submitBtn = document.querySelector('ms-run-button button[type="submit"], ms-run-button button.ctrl-enter-submits, button[aria-label*="Run"]');
                if (submitBtn && !submitBtn.disabled) {
                    hostLog('RUN', 'Emulating physical screen touch on Submit button...');
                    submitBtn.dispatchEvent(new Event('touchstart', { bubbles: true }));
                    await randomDelay(50, 150); // Finger dwell time
                    submitBtn.dispatchEvent(new Event('touchend', { bubbles: true }));
                    submitBtn.click();
                } else {
                    hostLog('RUN_WARN', 'Submit button disabled or missing, attempting focus submission...');
                    promptArea.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, ctrlKey: true, bubbles: true }));
                }

                // 6. Polling & Streaming Detection Loop
                updateStatus('Listening for response stream...');
                let lastLen = 0;
                let stable = 0;
                let totalTicks = 0;
                let lastThoughts = '';
                let currentOutput = '';

                while (totalTicks < 180) {
                    await delay(600);
                    totalTicks++;

                    // Sniff for errors
                    const errBanner = document.querySelector('ms-banner .error-banner-message');
                    const turnErr = document.querySelector('.chat-turn-container.model:last-of-type .model-error');
                    const toast = document.querySelector('.cdk-overlay-container .mat-mdc-simple-snack-bar');
                    const errTxt = (errBanner ? errBanner.innerText : "") + (turnErr ? turnErr.innerText : "") + (toast ? toast.innerText : "");

                    if (errTxt && !errTxt.toLowerCase().includes('saved')) {
                        hostLog('STUDIO_ERROR', 'Google AI Studio error: ' + errTxt);
                        if (window.OmniAutomator) window.OmniAutomator.onError(errTxt);
                        window.__omniAutomating = false;
                        return;
                    }

                    // Extract Model Thoughts
                    let currentThoughts = '';
                    const thoughtChunks = Array.from(document.querySelectorAll('ms-thought-chunk ms-text-chunk, ms-thought-chunk .cmark-node'));
                    if (thoughtChunks.length > 0) {
                        currentThoughts = thoughtChunks.map(n => n.innerText || n.textContent || '').filter(Boolean).join('\n').trim();
                        lastThoughts = currentThoughts;
                    }

                    // Extract Model Output
                    const models = document.querySelectorAll('.chat-turn-container.model, ms-chat-turn .chat-turn-container.model');
                    if (models.length > 0) {
                        const latest = models[models.length - 1];
                        const textArr = Array.from(latest.querySelectorAll('ms-text-chunk p, ms-text-chunk span, ms-cmark-node')).map(p => p.innerText || p.textContent || '');
                        currentOutput = textArr.join('\n').trim();
                    }

                    if (currentOutput.length > 0 || currentThoughts.length > 0) {
                        if (window.OmniAutomator) window.OmniAutomator.onProgress(currentThoughts, currentOutput);
                    }

                    // Check streaming spinner
                    const spinner = document.querySelector('ms-run-button .stoppable-spinner, mat-spinner, ms-loading-indicator');
                    if (!spinner && currentOutput.length > 0) {
                        const cLen = currentOutput.length;
                        if (cLen > 0 && cLen === lastLen) {
                            stable++;
                        } else {
                            stable = 0;
                            lastLen = cLen;
                        }

                        if (stable >= 3) {
                            updateStatus('Output generation complete!');
                            hostLog('DONE', 'Final output captured: ' + currentOutput.length + ' characters.');
                            if (window.OmniAutomator) window.OmniAutomator.onComplete(currentThoughts, currentOutput);
                            window.__omniAutomating = false;
                            return;
                        }
                    }
                }

                if (lastLen > 0) {
                    if (window.OmniAutomator) window.OmniAutomator.onComplete(lastThoughts, currentOutput);
                } else {
                    const timeoutMsg = 'Operation timed out after 180s.';
                    hostLog('TIMEOUT', timeoutMsg);
                    if (window.OmniAutomator) window.OmniAutomator.onError(timeoutMsg);
                }
                window.__omniAutomating = false;

            } catch (fatalErr) {
                hostLog('FATAL_ERR', (fatalErr.stack || fatalErr.message || String(fatalErr)));
                if (window.OmniAutomator) window.OmniAutomator.onError('Script error: ' + fatalErr.message);
                window.__omniAutomating = false;
            }
        })();
    """.trimIndent()
}

fun extractDomain(url: String): String {
    return try {
        val clean = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
        val uri = Uri.parse(clean)
        uri.host?.removePrefix("www.") ?: url
    } catch (_: Exception) {
        url
    }
}

fun sanitizeUrlForCopy(rawUrl: String): String {
    if (rawUrl.isEmpty() || rawUrl == "about:blank") return rawUrl
    return try {
        val uri = Uri.parse(rawUrl)
        val host = uri.host?.lowercase(Locale.US) ?: return rawUrl
        val scheme = uri.scheme ?: "https"

        // 1. Google Search Sanitizer (Preserve query & filters only)
        if (host.contains("google.") && uri.path?.contains("/search") == true) {
            val q = uri.getQueryParameter("q")
            if (!q.isNullOrEmpty()) {
                val builder = Uri.Builder()
                    .scheme(scheme)
                    .authority(host)
                    .path(uri.path)
                    .appendQueryParameter("q", q)

                listOf("tbm", "tbs", "udm", "start", "hl").forEach { param ->
                    uri.getQueryParameter(param)?.let { builder.appendQueryParameter(param, it) }
                }
                return builder.build().toString()
            }
        }

        // 2. YouTube Watch Sanitizer (Preserve video ID, playlist, timestamp)
        if (host.contains("youtube.com") && uri.path?.contains("/watch") == true) {
            val v = uri.getQueryParameter("v")
            if (!v.isNullOrEmpty()) {
                val builder = Uri.Builder()
                    .scheme(scheme)
                    .authority(host)
                    .path(uri.path)
                    .appendQueryParameter("v", v)
                listOf("t", "list", "index").forEach { param ->
                    uri.getQueryParameter(param)?.let { builder.appendQueryParameter(param, it) }
                }
                return builder.build().toString()
            }
        }

        if (host == "youtu.be") {
            val videoId = uri.path?.removePrefix("/")
            if (!videoId.isNullOrEmpty()) {
                val builder = Uri.Builder()
                    .scheme(scheme)
                    .authority("youtu.be")
                    .path("/$videoId")
                uri.getQueryParameter("t")?.let { builder.appendQueryParameter("t", it) }
                return builder.build().toString()
            }
        }

        // 3. Amazon ASIN Canonicalizer (/dp/ASIN)
        if (host.contains("amazon.")) {
            val asinRegex = Regex("/(?:dp|gp/product)/([A-Z0-9]{10})")
            val match = asinRegex.find(uri.path ?: "")
            if (match != null) {
                val asin = match.groupValues[1]
                return "$scheme://$host/dp/$asin"
            }
        }

        // 4. Universal Marketing & Tracking Parameter Stripper
        val trackingParams = setOf(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
            "fbclid", "gclid", "gclsrc", "dclid", "msclkid", "twclid", "igshid", "igsh",
            "_ga", "_gl", "_hsenc", "_hsmi", "yclid", "wickedid", "rb_clickid", "s_kwcid",
            "ref", "ref_", "ref_src", "ref_url", "si", "pp", "embeds_referring_euri", "source_ve_path",
            "feature", "oq", "ved", "sclient", "gs_lp", "gs_lcrp", "biw", "bih", "dpr", "ei", "sei",
            "sca_esv", "sca_upv", "pccc", "rdt", "is_from_webapp", "_r", "_d"
        )

        val queryNames = uri.queryParameterNames
        if (queryNames.isEmpty()) {
            return rawUrl
        }

        val builder = uri.buildUpon().clearQuery()
        for (name in queryNames) {
            if (!trackingParams.contains(name.lowercase(Locale.US))) {
                val values = uri.getQueryParameters(name)
                for (v in values) {
                    builder.appendQueryParameter(name, v)
                }
            }
        }

        val result = builder.build().toString()
        result.removeSuffix("?")
    } catch (_: Exception) {
        rawUrl
    }
}

fun normalizeLocalFilePath(rawPath: String): String {
    var path = rawPath.trim()
    try {
        path = Uri.decode(path)
    } catch (_: Exception) {}

    while (path.startsWith("file://")) {
        path = path.removePrefix("file://")
    }
    while (path.startsWith("file:/")) {
        path = path.removePrefix("file:/")
    }
    if (path.startsWith("/sdcard/")) {
        path = "/storage/emulated/0/" + path.removePrefix("/sdcard/")
    } else if (path.startsWith("sdcard/")) {
        path = "/storage/emulated/0/" + path.removePrefix("sdcard/")
    }
    if (!path.startsWith("/") && (path.startsWith("storage/") || path.startsWith("data/"))) {
        path = "/$path"
    }
    return path.trim()
}

fun isLocalFilePath(input: String): Boolean {
    val clean = input.trim().lowercase()
    return clean.startsWith("/") ||
           clean.startsWith("file:") ||
           clean.startsWith("content:") ||
           clean.startsWith("sdcard/") ||
           clean.startsWith("storage/") ||
           clean.endsWith(".html") ||
           clean.endsWith(".htm")
}