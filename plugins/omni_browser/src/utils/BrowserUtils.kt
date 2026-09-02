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
                const bMap = getTopBlobMap();

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

fun buildAiStudioAutomationScript(
    prompt: String,
    sysTitle: String,
    sysPrompt: String,
    thinkingLevel: String,
    model: String,
    fallbackEnabled: Boolean = true,
    temporaryChat: Boolean = false,
    attachmentsJson: String = "[]",
    stepsJson: String = "[]"
): String {
    return """
        (async function() {
            if (window.__omniAutomating) {
                return;
            }
            window.__omniAutomating = true;

            const STEPS_DATA = $stepsJson;
            const FALLBACK_PROMPT = $prompt;
            const SYS_TITLE = $sysTitle;
            const SYS_PROMPT = $sysPrompt;
            const THINKING_LEVEL = $thinkingLevel;
            const TARGET_MODEL = $model;
            const FALLBACK_ENABLED = $fallbackEnabled;
            const TEMPORARY_CHAT = $temporaryChat;
            const ATTACHMENTS = $attachmentsJson;

            const STEPS = (STEPS_DATA && Array.isArray(STEPS_DATA) && STEPS_DATA.length > 0)
                ? STEPS_DATA
                : [{ prompt: FALLBACK_PROMPT, repeatCount: 1, isInfinite: false, attachments: [] }];

            const delay = (ms) => new Promise(r => setTimeout(r, ms));
            const randomDelay = (min, max) => delay(Math.floor(Math.random() * (max - min + 1) + min));

            // Comprehensive English Interstitial & Dialog Dismissal Dictionary
            const DISMISS_DICTIONARY = [
                'got it', 'dismiss', 'i understand', 'accept', 'agree', 'i agree',
                'close', 'not now', 'no thanks', 'maybe later', 'continue', 'get started',
                'try it', 'try now', 'acknowledge', 'confirm', 'ok', 'done', 'skip',
                'remind me later', 'never', 'dismiss all', 'accept all', 'stay here',
                'keep using web', 'close dialog', 'understand'
            ];

            async function dismissInterstitials() {
                try {
                    const dialogContainers = document.querySelectorAll(
                        'mat-dialog-container, .mat-mdc-dialog-container, [role="dialog"], [aria-modal="true"], ms-announcement-modal, ms-onboarding-dialog, ms-welcome-dialog, ms-terms-dialog, .modal, .popup'
                    );

                    for (const dialog of dialogContainers) {
                        if (!isElementVisible(dialog)) continue;
                        if (dialog.querySelector('ms-system-instructions') && window.__omniConfiguringSysPrompt) {
                            continue;
                        }

                        const buttons = Array.from(dialog.querySelectorAll('button, [role="button"], a.btn'));
                        let dismissed = false;

                        for (const btn of buttons) {
                            if (!isElementVisible(btn)) continue;
                            const txt = (btn.textContent || '').trim().toLowerCase();
                            const aria = (btn.getAttribute('aria-label') || '').trim().toLowerCase();
                            const testId = (btn.getAttribute('data-testid') || btn.getAttribute('data-test') || '').toLowerCase();

                            if (DISMISS_DICTIONARY.some(d => txt === d || txt.startsWith(d + ' ') || aria.includes(d) || testId.includes(d)) ||
                                aria.includes('close') || aria.includes('dismiss')) {
                                hostLog('INTERSTITIAL', 'Dismissing popup/modal with button: "' + (txt || aria) + '"');
                                btn.click();
                                dismissed = true;
                                await delay(400);
                                break;
                            }
                        }

                        if (!dismissed) {
                            const closeIconBtn = dialog.querySelector('button[aria-label*="close"], button[aria-label*="Close"], button[data-test-close-button], .close-button, .close-btn, mat-icon[fonticon="close"]');
                            if (closeIconBtn && isElementVisible(closeIconBtn)) {
                                hostLog('INTERSTITIAL', 'Dismissing popup/modal via close icon button');
                                closeIconBtn.click();
                                await delay(400);
                            }
                        }
                    }

                    const banners = document.querySelectorAll('ms-banner, ms-global-banner, .announcement-banner');
                    for (const banner of banners) {
                        if (!isElementVisible(banner)) continue;
                        const bannerDismiss = banner.querySelector('button[aria-label*="dismiss"], button[aria-label*="close"], button.dismiss-button, button.close');
                        if (bannerDismiss && isElementVisible(bannerDismiss)) {
                            hostLog('INTERSTITIAL', 'Dismissing announcement banner');
                            bannerDismiss.click();
                            await delay(200);
                        }
                    }
                } catch(e) {
                    hostLog('INTERSTITIAL_ERR', 'Error during interstitial check: ' + e.message);
                }
            }

            const uploadedAttachmentKeys = new Set();
            async function injectAttachments(attachmentsList, label) {
                if (!attachmentsList || !Array.isArray(attachmentsList) || attachmentsList.length === 0) return;
                const uniqueList = attachmentsList.filter(att => {
                    const key = (att.name || 'file') + '_' + (att.size || (att.data ? att.data.length : 0));
                    if (uploadedAttachmentKeys.has(key)) return false;
                    uploadedAttachmentKeys.add(key);
                    return true;
                });
                if (uniqueList.length === 0) {
                    hostLog('ATTACHMENTS', 'Skipping already uploaded attachments for ' + label);
                    return;
                }
                updateStatus(label + ' - Uploading ' + uniqueList.length + ' file(s)...');
                hostLog('ATTACHMENTS', 'Injecting ' + uniqueList.length + ' file(s) into file input for ' + label);

                const fileInput = document.querySelector('input[data-test-upload-file-input], input[type="file"].file-input, input[type="file"]');
                if (fileInput) {
                    const dt = new DataTransfer();
                    for (const att of uniqueList) {
                        try {
                            const b64Clean = att.data.includes(',') ? att.data.split(',')[1] : att.data;
                            const byteChars = atob(b64Clean);
                            const byteNums = new Array(byteChars.length);
                            for (let i = 0; i < byteChars.length; i++) {
                                byteNums[i] = byteChars.charCodeAt(i);
                            }
                            const byteArray = new Uint8Array(byteNums);
                            const blob = new Blob([byteArray], { type: att.mime || 'application/octet-stream' });
                            const file = new File([blob], att.name || 'file', { type: att.mime || 'application/octet-stream' });
                            dt.items.add(file);
                            hostLog('ATTACHMENTS', 'Prepared File: ' + file.name + ' (' + file.size + ' bytes)');
                        } catch (e) {
                            hostLog('ATTACHMENTS_ERR', 'Failed preparing ' + att.name + ': ' + e.message);
                        }
                    }

                    if (dt.files.length > 0) {
                        fileInput.files = dt.files;
                        fileInput.dispatchEvent(new Event('change', { bubbles: true }));
                        await randomDelay(1200, 1800);

                        updateStatus(label + ' - Ingesting media into AI Studio...');
                        let uploadWait = 0;
                        while (uploadWait < 60) {
                            const activeUploadIndicator = document.querySelector('mat-progress-bar, mat-spinner, ms-prompt-box .loading-indicator, .media-upload-progress, ms-prompt-box .loading');
                            if (!activeUploadIndicator) break;
                            await delay(500);
                            uploadWait++;
                        }
                        await randomDelay(800, 1200);
                        hostLog('ATTACHMENTS', 'Media attachments processed for ' + label);
                    }
                }
            }

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

            function isElementVisible(el) {
                if (!el) return false;
                const rect = el.getBoundingClientRect();
                return rect.width > 0 && rect.height > 0 && window.getComputedStyle(el).visibility !== 'hidden' && window.getComputedStyle(el).display !== 'none';
            }

            function isGenerating() {
                const stoppable = document.querySelector('.run-button.stoppable, ms-run-button .stoppable-spinner, ms-run-button .stoppable-stop');
                const spinner = document.querySelector('ms-run-button .stoppable-spinner, mat-spinner, ms-loading-indicator');
                return !!(stoppable || spinner);
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
                    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', bubbles: true }));
                    el.dispatchEvent(new KeyboardEvent('keyup', { key: 'a', bubbles: true }));
                } catch(e) {
                    hostLog('INJECT_ERR', 'Error typing text: ' + e.message);
                    try { el.value = text; } catch(_) {}
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                }
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

                updateStatus('Checking UI & Dismissing popups...');
                await dismissInterstitials();

                updateStatus('Waiting for AI Studio UI...');
                let mountAttempts = 0;
                let promptArea = null;

                while (mountAttempts < 35) {
                    await dismissInterstitials();
                    promptArea = document.querySelector('textarea[formcontrolname="promptText"], textarea[aria-label="Enter a prompt"], textarea');
                    if (promptArea && isElementVisible(promptArea)) break;
                    await delay(1000);
                    mountAttempts++;
                    if (mountAttempts % 5 === 0) {
                        hostLog('MOUNT', 'Polling UI (' + mountAttempts + '/35)...');
                    }
                }

                if (!promptArea) {
                    const err = 'Failed to locate prompt textarea after 30s.';
                    hostLog('MOUNT_ERR', err);
                    if (window.OmniAutomator) window.OmniAutomator.onError(err);
                    window.__omniAutomating = false;
                    return;
                }

                await randomDelay(1000, 1600);

                function isTemporaryChatActive() {
                    return !!(
                        document.querySelector('ms-incognito-mode-indicator') ||
                        document.querySelector('.incognito-container') ||
                        (document.querySelector('.page-title')?.textContent || '').toLowerCase().includes('temporary chat')
                    );
                }

                // 1.5 Sync Temporary Chat Mode with User Preference (Bi-directional)
                const currentTempState = isTemporaryChatActive();
                if (TEMPORARY_CHAT !== currentTempState) {
                    const actionLabel = TEMPORARY_CHAT ? 'Enabling Temporary Chat' : 'Disabling Temporary Chat (Restoring persistent mode)';
                    hostLog('TEMP_CHAT', actionLabel + ' in Google AI Studio...');
                    updateStatus(TEMPORARY_CHAT ? 'Activating Temporary Chat...' : 'Restoring persistent chat...');

                    const overflowBtn = document.querySelector('.overflow-menu-wrapper button, button[aria-label*="View more actions"]');
                    if (overflowBtn && isElementVisible(overflowBtn)) {
                        overflowBtn.click();
                        await randomDelay(800, 1200);

                        const tempChatOption = document.querySelector('button[data-test-incognito-toggle], button[aria-label*="temporary chat"]');
                        if (tempChatOption) {
                            tempChatOption.click();
                            await randomDelay(1000, 1400);
                            hostLog('TEMP_CHAT', 'Temporary Chat state synchronized. Now active: ' + isTemporaryChatActive());
                        } else {
                            document.body.click();
                            await randomDelay(300, 500);
                        }
                    }
                } else {
                    hostLog('TEMP_CHAT', 'Temporary Chat already matches desired state (' + TEMPORARY_CHAT + '). Skipping.');
                }

                // 2. Open Settings Drawer if System Prompt or Thinking Level is configured
                const needsSettings = (SYS_PROMPT && SYS_PROMPT.length > 0) || (SYS_TITLE && SYS_TITLE.length > 0) || (THINKING_LEVEL && THINKING_LEVEL !== 'Default');

                if (needsSettings) {
                    let sysCard = document.querySelector('button.system-instructions-card, button[data-test-system-instructions-card], ms-system-instructions-panel button');

                    if (!isElementVisible(sysCard)) {
                        updateStatus('Opening Settings Drawer...');
                        const tuneBtn = document.querySelector('button.runsettings-toggle-button, button[aria-label*="Toggle run settings"], button[aria-label*="run settings"]');
                        if (tuneBtn && isElementVisible(tuneBtn)) {
                            hostLog('SETTINGS', 'Clicking run settings toggle button (tune icon)...');
                            tuneBtn.click();
                            await randomDelay(1000, 1500);
                        }
                    }

                    // A. Configure Thinking Level if present
                    if (THINKING_LEVEL && THINKING_LEVEL !== 'Default') {
                        try {
                            const thinkingSelect = document.querySelector('ms-thinking-level-setting mat-select, mat-select[aria-label*="Thinking"]');
                            if (thinkingSelect) {
                                const currentThinkingVal = (thinkingSelect.textContent || '').trim().toLowerCase();
                                if (currentThinkingVal.includes(THINKING_LEVEL.toLowerCase())) {
                                    hostLog('SETTINGS', 'Thinking Level already set to "' + THINKING_LEVEL + '". Skipping.');
                                } else {
                                    hostLog('SETTINGS', 'Opening Thinking Level dropdown...');
                                    thinkingSelect.click();
                                    await randomDelay(800, 1200);

                                    const options = Array.from(document.querySelectorAll('.mat-mdc-select-panel mat-option, mat-option'));
                                    const match = options.find(o => (o.textContent || '').trim().toLowerCase().includes(THINKING_LEVEL.toLowerCase()));
                                    if (match) {
                                        match.click();
                                        hostLog('SETTINGS', 'Applied Thinking Level: ' + (match.textContent || '').trim());
                                    } else {
                                        document.body.click();
                                    }
                                    await randomDelay(600, 1000);
                                }
                            }
                        } catch(e) {
                            hostLog('SETTINGS_WARN', 'Thinking level setting failed: ' + e.message);
                        }
                    }

                    // B. Select or Inject System Instructions
                    if ((SYS_TITLE && SYS_TITLE.length > 0) || (SYS_PROMPT && SYS_PROMPT.length > 0)) {
                        try {
                            updateStatus('Checking System Instructions...');
                            sysCard = document.querySelector('button.system-instructions-card, button[data-test-system-instructions-card], ms-system-instructions-panel button');
                            
                            const cardSub = (sysCard ? (sysCard.querySelector('.subtitle')?.textContent || '') : '').trim();
                            const alreadyMatches = (SYS_TITLE && cardSub.toLowerCase().includes(SYS_TITLE.toLowerCase())) ||
                                                   (SYS_PROMPT && cardSub.length > 15 && SYS_PROMPT.startsWith(cardSub.slice(0, 15)));

                            if (alreadyMatches && !FALLBACK_ENABLED) {
                                hostLog('SYS_PROMPT', 'System instructions already match active card preview. Skipping dialog.');
                            } else if (sysCard) {
                                hostLog('SYS_PROMPT', 'Opening System Instructions dialog...');
                                sysCard.click();
                                await randomDelay(1200, 1800);

                                let matchedExisting = false;

                                // 1. Scan AI Studio mat-select dropdown for existing saved preset by title
                                const sysSelect = document.querySelector('ms-system-instructions mat-select, .select-tooltip-wrapper mat-select');
                                if (sysSelect) {
                                    const currentPresetText = (sysSelect.textContent || '').trim().toLowerCase();
                                    if (SYS_TITLE && currentPresetText === SYS_TITLE.toLowerCase()) {
                                        hostLog('SYS_PROMPT', 'Preset "' + SYS_TITLE + '" already active in dropdown.');
                                        matchedExisting = true;
                                    } else {
                                        hostLog('SYS_PROMPT', 'Opening System Instructions preset dropdown...');
                                        sysSelect.click();
                                        await randomDelay(800, 1200);

                                        const options = Array.from(document.querySelectorAll('.mat-mdc-select-panel mat-option, mat-option'));

                                        if (SYS_TITLE && SYS_TITLE.length > 0) {
                                            const match = options.find(opt => {
                                                const txt = (opt.textContent || '').trim().toLowerCase();
                                                return txt === SYS_TITLE.toLowerCase();
                                            });
                                            if (match) {
                                                hostLog('SYS_PROMPT', 'Found existing preset in AI Studio for title: "' + SYS_TITLE + '". Selecting...');
                                                match.click();
                                                matchedExisting = true;
                                                await randomDelay(800, 1200);
                                            }
                                        }

                                        if (!matchedExisting) {
                                            const createNewOpt = options.find(opt => (opt.textContent || '').includes('Create new instruction'));
                                            if (createNewOpt) {
                                                hostLog('SYS_PROMPT', 'Selecting "+ Create new instruction" option...');
                                                createNewOpt.click();
                                                await randomDelay(800, 1200);
                                            } else {
                                                document.body.click();
                                                await randomDelay(400, 600);
                                            }
                                        }
                                    }
                                }

                                // 2. If new or not matched in studio, inject title + body from local vault
                                if (!matchedExisting) {
                                    if (FALLBACK_ENABLED && SYS_PROMPT && SYS_PROMPT.length > 0) {
                                        hostLog('SYS_PROMPT', 'Injecting custom title and instructions body into dialog...');

                                        const titleInput = document.querySelector('ms-system-instructions .title-row input, ms-system-instructions input[placeholder*="Title"], ms-system-instructions input');
                                        if (titleInput && SYS_TITLE && SYS_TITLE.length > 0) {
                                            if (titleInput.value !== SYS_TITLE) {
                                                safeInjectText(titleInput, SYS_TITLE);
                                                await randomDelay(400, 700);
                                            }
                                        }

                                        let sysTa = null;
                                        for (let a = 0; a < 15; a++) {
                                            sysTa = document.querySelector('ms-system-instructions textarea, textarea.in-run-settings, textarea[aria-label*="System"], textarea[placeholder*="instructions"]');
                                            if (sysTa && isElementVisible(sysTa)) break;
                                            await delay(400);
                                        }

                                        if (sysTa) {
                                            if (sysTa.value !== SYS_PROMPT) {
                                                updateStatus('Typing System Instructions...');
                                                safeInjectText(sysTa, SYS_PROMPT);
                                                hostLog('SYS_PROMPT', 'Injected ' + SYS_PROMPT.length + ' chars into System Instructions.');
                                                await randomDelay(800, 1400);
                                            }
                                        }
                                    } else {
                                        hostLog('SYS_PROMPT_WARN', 'Preset title "' + SYS_TITLE + '" not in AI Studio and no fallback body provided.');
                                    }
                                }

                                // Wait for auto-save status
                                let saveWait = 0;
                                while (saveWait < 12) {
                                    const saveIndicator = document.querySelector('mat-dialog-container .saving-status, .saving-status');
                                    if (saveIndicator && (saveIndicator.textContent || '').toLowerCase().includes('saved')) {
                                        hostLog('SYS_PROMPT', 'System instructions saved status confirmed.');
                                        break;
                                    }
                                    await delay(300);
                                    saveWait++;
                                }
                                await randomDelay(500, 800);

                                // 3. Close dialog via close button
                                const closeBtn = document.querySelector('mat-dialog-container button[mat-dialog-close], mat-dialog-container button[aria-label*="Close"], button[data-test-close-button]');
                                if (closeBtn) {
                                    hostLog('SYS_PROMPT', 'Closing System Instructions dialog...');
                                    closeBtn.click();
                                    await randomDelay(1000, 1500);
                                }
                            } else {
                                hostLog('SYS_PROMPT_WARN', 'Could not find System Instructions card button.');
                            }
                        } catch(e) {
                            hostLog('SYS_PROMPT_ERR', 'System instructions step failed: ' + e.message);
                        } finally {
                            window.__omniConfiguringSysPrompt = false;
                        }
                    }

                    // C. Target-First Viewport Check: Check if prompt area is already visible/unblocked
                    let promptReady = false;
                    for (let w = 0; w < 6; w++) {
                        const pa = document.querySelector('textarea[formcontrolname="promptText"], textarea[aria-label="Enter a prompt"], textarea');
                        if (pa && isElementVisible(pa)) {
                            promptReady = true;
                            break;
                        }
                        await delay(250);
                    }

                    // Only attempt manual drawer close if prompt area remains blocked by an open drawer
                    if (!promptReady) {
                        const openDrawer = document.querySelector('ms-run-settings.expanded, .content-container ms-run-settings:not(.collapsed)');
                        if (openDrawer && isElementVisible(openDrawer)) {
                            const closeSettingsBtn = openDrawer.querySelector('button[aria-label*="Close run settings"], button[aria-label*="Close"]');
                            if (closeSettingsBtn && isElementVisible(closeSettingsBtn)) {
                                hostLog('SETTINGS', 'Prompt still obscured. Manually closing settings drawer...');
                                closeSettingsBtn.click();
                                await randomDelay(800, 1200);
                            }
                        }
                    } else {
                        hostLog('SETTINGS', 'Settings drawer auto-collapsed with dialog. UI ready.');
                    }
                }

                await randomDelay(800, 1200);

                // 3. Initial Attachments Dispatch (Turn 1 Global)
                if (ATTACHMENTS && Array.isArray(ATTACHMENTS) && ATTACHMENTS.length > 0) {
                    await injectAttachments(ATTACHMENTS, 'Turn 1 Global');
                }

                // 4. Sequential Multi-Turn Prompt Chain Execution
                let totalTurnsExecuted = 0;
                let fullCumulativeOutput = '';
                let latestTurnThoughts = '';

                for (let stepIdx = 0; stepIdx < STEPS.length; stepIdx++) {
                    const currentStep = STEPS[stepIdx];
                    const stepNum = stepIdx + 1;
                    const maxRepeats = currentStep.isInfinite ? Infinity : (currentStep.repeatCount || 1);
                    let currentRepeat = 0;

                    while (currentRepeat < maxRepeats) {
                        currentRepeat++;
                        totalTurnsExecuted++;

                        const stepLabel = currentStep.isInfinite
                            ? 'Step ' + stepNum + '/' + STEPS.length + ' (Loop ∞, Turn ' + currentRepeat + ')'
                            : 'Step ' + stepNum + '/' + STEPS.length + ' (Repeat ' + currentRepeat + '/' + maxRepeats + ')';

                        updateStatus(stepLabel + ' - Injecting prompt...');
                        hostLog('CHAIN', 'Executing ' + stepLabel + ' [Total Turns: ' + totalTurnsExecuted + ']');

                        await dismissInterstitials();

                        // Ingest step-specific attachments if provided
                        if (currentStep.attachments && Array.isArray(currentStep.attachments) && currentStep.attachments.length > 0) {
                            await injectAttachments(currentStep.attachments, stepLabel);
                        }

                        // Find the bottom-most active textarea
                        let activePromptArea = null;
                        for (let a = 0; a < 25; a++) {
                            const allAreas = Array.from(document.querySelectorAll('textarea[formcontrolname="promptText"], textarea[aria-label="Enter a prompt"], textarea'));
                            activePromptArea = allAreas.length > 0 ? allAreas[allAreas.length - 1] : null;
                            if (activePromptArea && isElementVisible(activePromptArea)) break;
                            await dismissInterstitials();
                            await delay(400);
                        }

                        if (activePromptArea && currentStep.prompt && currentStep.prompt.trim().length > 0) {
                            safeInjectText(activePromptArea, currentStep.prompt);
                            await randomDelay(800, 1400);
                        }

                        // Ingestion Readiness & Submit for this turn
                        if (!isGenerating()) {
                            updateStatus(stepLabel + ' - Waiting for Run button readiness...');
                            let readyTicks = 0;
                            let submitBtn = null;

                            while (readyTicks < 90) {
                                if (isGenerating()) break;
                                submitBtn = document.querySelector('ms-run-button button:not(.stoppable), button.ctrl-enter-submits:not(.stoppable), button[type="submit"]:not(.stoppable)');
                                if (submitBtn && isRunButtonReady(submitBtn)) break;
                                await delay(500);
                                readyTicks++;
                            }

                            if (!isGenerating() && submitBtn && isRunButtonReady(submitBtn)) {
                                hostLog('RUN', 'Clicking Run button for ' + stepLabel + '...');
                                submitBtn.click();
                                await randomDelay(800, 1400);
                            }

                            if (!isGenerating() && activePromptArea) {
                                hostLog('RUN', 'Fallback Ctrl+Enter for ' + stepLabel + '...');
                                activePromptArea.focus();
                                activePromptArea.dispatchEvent(new KeyboardEvent('keydown', {
                                    key: 'Enter',
                                    code: 'Enter',
                                    keyCode: 13,
                                    which: 13,
                                    ctrlKey: true,
                                    bubbles: true,
                                    cancelable: true
                                }));
                                await randomDelay(800, 1200);
                            }
                        }

                        // Dynamic Response Polling & Streaming Detection Watchdog
                        updateStatus(stepLabel + ' - Streaming response...');
                        let lastLen = 0;
                        let lastThoughtsLen = 0;
                        let stable = 0;
                        let totalTurnTicks = 0;
                        let idleTicks = 0;
                        const MAX_IDLE_TICKS = 60; // 36 seconds of verified freeze with zero token change
                        const MAX_TOTAL_TICKS = 1500; // 15 minutes ceiling per turn for extreme reasoning models
                        let turnRetryCount = 0;
                        const MAX_RETRIES = 3;
                        let currentTurnOutput = '';

                        while (totalTurnTicks < MAX_TOTAL_TICKS) {
                            await delay(600);
                            totalTurnTicks++;
                            idleTicks++;

                            const errBanner = document.querySelector('ms-banner .error-banner-message, ms-global-banner');
                            const turnErr = document.querySelector('.chat-turn-container.model:last-of-type .model-error, ms-chat-turn:last-of-type .model-error');
                            const toast = document.querySelector('.cdk-overlay-container .mat-mdc-simple-snack-bar');
                            const errTxt = ((errBanner ? errBanner.innerText : "") + (turnErr ? turnErr.innerText : "") + (toast ? toast.innerText : "")).trim();

                            if (errTxt && !errTxt.toLowerCase().includes('saved')) {
                                const lowerErr = errTxt.toLowerCase();
                                const isRetryable = lowerErr.includes('internal error') ||
                                                    lowerErr.includes('permission denied') ||
                                                    lowerErr.includes('overloaded') ||
                                                    lowerErr.includes('resource exhausted') ||
                                                    lowerErr.includes('quota') ||
                                                    lowerErr.includes('try again') ||
                                                    lowerErr.includes('server error');

                                if (isRetryable && turnRetryCount < MAX_RETRIES) {
                                    turnRetryCount++;
                                    hostLog('RETRY', 'Transient error: "' + errTxt + '". Retrying attempt ' + turnRetryCount + '/' + MAX_RETRIES + '...');
                                    updateStatus(stepLabel + ' (Retry ' + turnRetryCount + '/' + MAX_RETRIES + ')...');
                                    await delay(1800 + (turnRetryCount * 600));

                                    const allTurns = document.querySelectorAll('ms-chat-turn, .chat-turn-container');
                                    const lastTurn = allTurns.length > 0 ? allTurns[allTurns.length - 1] : null;
                                    const rerunBtn = lastTurn ? lastTurn.querySelector('button[name="rerun-button"], button.rerun-button, button[aria-label*="Rerun"]') : null;

                                    if (rerunBtn && isElementVisible(rerunBtn)) {
                                        rerunBtn.click();
                                    } else {
                                        const submitBtn = document.querySelector('ms-run-button button:not(.stoppable)');
                                        if (submitBtn) submitBtn.click();
                                    }

                                    lastLen = 0;
                                    stable = 0;
                                    turnTicks = 0;
                                    currentTurnOutput = '';
                                    await delay(1200);
                                    continue;
                                } else {
                                    hostLog('STUDIO_ERROR', 'Fatal error on ' + stepLabel + ': ' + errTxt);
                                    if (window.OmniAutomator) window.OmniAutomator.onError(errTxt);
                                    window.__omniAutomating = false;
                                    return;
                                }
                            }

                            // Capture Thoughts
                            const thoughtBlocks = Array.from(document.querySelectorAll('ms-thought-chunk'));
                            if (thoughtBlocks.length > 0) {
                                latestTurnThoughts = thoughtBlocks
                                    .map(b => (b.innerText || b.textContent || '').trim())
                                    .filter(Boolean)
                                    .join('\n\n')
                                    .trim();
                            }

                            // Capture latest model turn output
                            const models = document.querySelectorAll('.chat-turn-container.model, ms-chat-turn .chat-turn-container.model');
                            if (models.length > 0) {
                                const latest = models[models.length - 1];
                                const textChunks = Array.from(latest.querySelectorAll('ms-text-chunk:not(ms-thought-chunk ms-text-chunk)'));
                                if (textChunks.length > 0) {
                                    currentTurnOutput = textChunks
                                        .map(c => (c.innerText || c.textContent || '').trim())
                                        .filter(Boolean)
                                        .join('\n\n')
                                        .trim();
                                } else {
                                    const contentBody = latest.querySelector('.chat-turn-content, ms-chat-turn-content, .text-chunk');
                                    currentTurnOutput = contentBody ? (contentBody.innerText || contentBody.textContent || '').trim() : '';
                                }
                            }

                            const combinedDisplay = fullCumulativeOutput.length > 0
                                ? fullCumulativeOutput + '\n\n--- [Turn ' + totalTurnsExecuted + ': ' + stepLabel + '] ---\n' + currentTurnOutput
                                : currentTurnOutput;

                            if (currentTurnOutput.length > 0 || latestTurnThoughts.length > 0) {
                                if (window.OmniAutomator) window.OmniAutomator.onProgress(latestTurnThoughts, combinedDisplay);
                            }

                            const spinner = document.querySelector('ms-run-button .stoppable-spinner, mat-spinner, ms-loading-indicator');
                            if (!spinner && currentTurnOutput.length > 0) {
                                const cLen = currentTurnOutput.length;
                                if (cLen > 0 && cLen === lastLen) {
                                    stable++;
                                } else {
                                    stable = 0;
                                    lastLen = cLen;
                                }

                                if (stable >= 3) {
                                    hostLog('TURN_DONE', 'Completed ' + stepLabel + ' (' + currentTurnOutput.length + ' chars).');
                                    fullCumulativeOutput = fullCumulativeOutput.length > 0
                                        ? fullCumulativeOutput + '\n\n--- [Turn ' + totalTurnsExecuted + ': ' + stepLabel + '] ---\n' + currentTurnOutput
                                        : currentTurnOutput;
                                    break;
                                }
                            }
                        }

                        // Settle delay before triggering next turn in chain
                        const isLastRepeatOfLastStep = (stepIdx === STEPS.length - 1) && (currentRepeat >= maxRepeats);
                        if (!isLastRepeatOfLastStep) {
                            updateStatus(stepLabel + ' completed. Preparing next turn...');
                            await randomDelay(1800, 2600);
                        }
                    }
                }

                updateStatus('All sequence steps completed!');
                hostLog('CHAIN_DONE', 'Finished prompt chain. Total turns executed: ' + totalTurnsExecuted + '.');
                if (window.OmniAutomator) window.OmniAutomator.onComplete(latestTurnThoughts, fullCumulativeOutput);
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

fun android.webkit.WebView.captureThumbnail(): android.graphics.Bitmap? {
    if (width <= 0 || height <= 0) return null
    return try {
        val scale = 0.5f
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.scale(scale, scale)
        draw(canvas)
        bitmap
    } catch (_: Exception) {
        null
    }
}

fun handleExternalUri(context: android.content.Context, url: String, view: android.webkit.WebView?, bridge: com.omni.hub.api.HostBridge): Boolean {
    if (url.startsWith("http://") || url.startsWith("https://")) {
        if (url.contains("play.google.com/store/apps/details")) {
            val uri = Uri.parse(url)
            val pkg = uri.getQueryParameter("id")
            if (!pkg.isNullOrEmpty()) {
                val marketIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                    setPackage("com.android.vending")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                return try {
                    context.startActivity(marketIntent)
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
        return false
    }

    if (url.startsWith("intent://")) {
        return try {
            val parsedIntent = android.content.Intent.parseUri(url, android.content.Intent.URI_INTENT_SCHEME).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val resolveInfo = context.packageManager.resolveActivity(parsedIntent, 0)
            if (resolveInfo != null) {
                context.startActivity(parsedIntent)
                true
            } else {
                val fallbackUrl = parsedIntent.getStringExtra("browser_fallback_url")
                if (!fallbackUrl.isNullOrEmpty()) {
                    view?.loadUrl(fallbackUrl)
                    true
                } else {
                    val pkg = parsedIntent.`package`
                    if (!pkg.isNullOrEmpty()) {
                        val marketIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(marketIntent)
                        true
                    } else false
                }
            }
        } catch (e: Exception) {
            bridge.log("INTENT_ERR", "Could not dispatch intent URI: ${e.message}")
            false
        }
    }

    if (url.startsWith("market://")) {
        return try {
            val marketIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(marketIntent)
            true
        } catch (e: Exception) {
            val pkg = Uri.parse(url).getQueryParameter("id")
            if (!pkg.isNullOrEmpty()) {
                view?.loadUrl("https://play.google.com/store/apps/details?id=$pkg")
                true
            } else false
        }
    }

    return try {
        val genericIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(genericIntent)
        true
    } catch (e: Exception) {
        bridge.log("INTENT_WARN", "No handler for scheme: $url")
        false
    }
}

fun saveHtmlSnapshot(context: android.content.Context, bridge: com.omni.hub.api.HostBridge, rawHtml: String, prefix: String = "DOM_Dump"): String {
    val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
    val filename = "${prefix}_$timestamp.html"
    var savedToDownloads = false

    try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/html")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/OmniSnapshots")
            }
            val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(rawHtml.toByteArray(Charsets.UTF_8))
                }
                savedToDownloads = true
            }
        } else {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val targetDir = java.io.File(downloadsDir, "OmniSnapshots").apply { mkdirs() }
            val targetFile = java.io.File(targetDir, filename)
            java.io.FileOutputStream(targetFile).use { os ->
                os.write(rawHtml.toByteArray(Charsets.UTF_8))
            }
            savedToDownloads = true
        }
    } catch (e: Exception) {
        bridge.log("DOM_SNAPSHOT_ERR", "Downloads folder write failed: ${e.message}")
    }

    bridge.saveFile("snapshots/$filename", rawHtml.toByteArray(Charsets.UTF_8))
    val locationMsg = if (savedToDownloads) "Downloads/OmniSnapshots/$filename" else "snapshots/$filename"
    bridge.log("DOM_SNAPSHOT", "Saved DOM snapshot: $locationMsg (${rawHtml.length} bytes)")
    bridge.showToast("✅ Saved DOM to $locationMsg")
    return locationMsg
}