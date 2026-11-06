package com.omni.plugin.browser.utils

import android.net.Uri
import java.util.Locale

const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

val DESKTOP_VIEWPORT_SCRIPT = """
(function() {
    function enforceDesktopViewport() {
        var meta = document.querySelector('meta[name="viewport"]');
        if (!meta) {
            meta = document.createElement('meta');
            meta.name = 'viewport';
            document.head.appendChild(meta);
        }
        var screenW = window.screen.width || screen.width || 412;
        var scale = Math.min(1.0, screenW / 1280).toFixed(4);
        meta.setAttribute('content', 'width=1280, initial-scale=' + scale + ', maximum-scale=3.0, user-scalable=yes');
    }
    if (document.head) enforceDesktopViewport();
    document.addEventListener('DOMContentLoaded', enforceDesktopViewport);
    window.addEventListener('load', enforceDesktopViewport);
})();
""".trimIndent()

fun convertUrlForDesktop(url: String, toDesktop: Boolean): String {
    if (url.isEmpty() || url == "about:blank") return url
    return try {
        val uri = Uri.parse(url)
        val host = uri.host ?: return url
        if (toDesktop) {
            if (host.startsWith("m.")) {
                val newHost = if (host == "m.youtube.com") "www.youtube.com" else host.removePrefix("m.")
                uri.buildUpon().authority(newHost).build().toString()
            } else if (host.startsWith("mobile.")) {
                uri.buildUpon().authority(host.removePrefix("mobile.")).build().toString()
            } else url
        } else {
            if (host == "www.youtube.com") {
                uri.buildUpon().authority("m.youtube.com").build().toString()
            } else url
        }
    } catch (_: Exception) {
        url
    }
}

fun convertLocalFileToLocalhost(rawUrl: String, port: Int): String {
    if (!rawUrl.startsWith("file://") || !rawUrl.contains("/ide/")) return rawUrl
    val subpath = rawUrl.substringAfter("/ide/").removePrefix("/")
    return when {
        subpath.isEmpty() || subpath == "index.html" -> "http://localhost:$port/"
        else -> "http://localhost:$port/$subpath"
    }
}

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
                if (parameters.name === 'clipboard-read' || parameters.name === 'clipboard-write') return Promise.resolve({ state: 'granted' });
                if (parameters.name === 'microphone' || parameters.name === 'camera') return Promise.resolve({ state: 'granted' });
                return originalQuery.call(navigator, parameters);
            };
            window.navigator.permissions.query.toString = function() { return "function query() { [native code] }"; };
        } 
    } catch(e) {}
    try {
        if (!window.navigator.clipboard) {
            window.navigator.clipboard = {};
        }
        const origReadText = window.navigator.clipboard.readText;
        window.navigator.clipboard.readText = function() {
            if (window.OmniClipboardBridge && window.OmniClipboardBridge.getClipboardText) {
                return Promise.resolve(window.OmniClipboardBridge.getClipboardText());
            }
            return origReadText ? origReadText.apply(this, arguments) : Promise.resolve("");
        };
        window.navigator.clipboard.readText.toString = function() { return "function readText() { [native code] }"; };

        const origWriteText = window.navigator.clipboard.writeText;
        window.navigator.clipboard.writeText = function(text) {
            if (window.OmniClipboardBridge && window.OmniClipboardBridge.setClipboardText) {
                window.OmniClipboardBridge.setClipboardText(String(text));
                return Promise.resolve();
            }
            return origWriteText ? origWriteText.apply(this, arguments) : Promise.resolve();
        };
        window.navigator.clipboard.writeText.toString = function() { return "function writeText() { [native code] }"; };

        if (!window.navigator.clipboard.read) {
            window.navigator.clipboard.read = function() {
                const text = window.OmniClipboardBridge ? window.OmniClipboardBridge.getClipboardText() : "";
                if (typeof ClipboardItem !== 'undefined') {
                    const blob = new Blob([text], { type: 'text/plain' });
                    return Promise.resolve([new ClipboardItem({ 'text/plain': blob })]);
                }
                return Promise.resolve([]);
            };
        }
    } catch(e) {}

            // 🛡️ Intelligent Keyboard & Focus Viewport Guard (Scrolls inputs into view above IME)
        try {
            function scrollActiveInputIntoView(element) {
                var el = element || document.activeElement;
                if (!el) return;
                var isInput = el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable || el.getAttribute('contenteditable') === 'true';
                if (isInput) {
                    setTimeout(function() {
                        try {
                            el.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'nearest' });
                        } catch(_) {
                            try { el.scrollIntoView(false); } catch(_) {}
                        }
                    }, 280);
                }
            }

            document.addEventListener('focusin', function(e) {
                scrollActiveInputIntoView(e.target);
            }, true);

            document.addEventListener('click', function(e) {
                var target = e.target;
                if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) {
                    scrollActiveInputIntoView(target);
                }
            }, true);

            if (window.visualViewport) {
                window.visualViewport.addEventListener('resize', function() {
                    scrollActiveInputIntoView();
                });
            }
            window.addEventListener('resize', function() {
                scrollActiveInputIntoView();
            });
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

val DATA_SAVER_POLYFILL = """
(function() {
    try {
        if (navigator.connection) {
            Object.defineProperty(navigator.connection, 'saveData', { get: () => true, configurable: true });
        }
    } catch(_) {}
    function killMediaPreload() {
        document.querySelectorAll('video, audio').forEach(function(el) {
            try {
                el.preload = 'none';
                el.autoplay = false;
                el.removeAttribute('autoplay');
                el.pause();
            } catch(_) {}
        });
    }
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', killMediaPreload);
    } else {
        killMediaPreload();
    }
})();
""".trimIndent()

fun isMeteredOrMobileConnection(context: android.content.Context): Boolean {
    return try {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return true
        val activeNetwork = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return true
        val isWifi = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        val isMetered = cm.isActiveNetworkMetered
        !(isWifi && !isMetered)
    } catch (_: Exception) {
        true
    }
}

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
                displaySize: 65,
                transparency: 0.95,
                theme: 'Dark'
            }
        });
        eruda.show();
    };
    document.body.appendChild(script);
})();
""".trimIndent()

fun buildErudaInjectionScript(erudaSource: String): String {
    val prefix = """
        (function() {
            try {
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
    """.trimIndent() + "\n"

    val suffix = "\n" + """
                if (window.eruda) {
                    eruda.init({
                        tool: ['console', 'elements', 'network', 'resource', 'info', 'snippets', 'storage'],
                        defaults: {
                            displaySize: 65,
                            transparency: 0.95,
                            theme: 'Dark'
                        }
                    });
                    eruda.show();

                    var armor = document.getElementById('omni-eruda-armor');
                    if (!armor) {
                        armor = document.createElement('style');
                        armor.id = 'omni-eruda-armor';
                        armor.textContent = '#eruda, .eruda-container, .eruda-entry-btn { z-index: 2147483647 !important; pointer-events: auto !important; opacity: 1 !important; display: block !important; }';
                        (document.head || document.documentElement).appendChild(armor);
                    }
                }
            } catch(e) {
                console.error('Eruda injection failure: ' + e.message);
            }
        })();
    """.trimIndent()

    return prefix + erudaSource + suffix
}

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

fun buildAiStudioPuppetScript(): String {
    return """
        (function() {
            if (window.__omniPuppet) return;

            const delay = (ms) => new Promise(r => setTimeout(r, ms));
            const randomDelay = (min, max) => delay(Math.floor(Math.random() * (max - min + 1) + min));

            const DISMISS_DICTIONARY = [
                'got it', 'dismiss', 'i understand', 'accept', 'agree', 'i agree',
                'close', 'not now', 'no thanks', 'maybe later', 'continue', 'get started',
                'try it', 'try now', 'acknowledge', 'confirm', 'ok', 'done', 'skip',
                'remind me later', 'never', 'dismiss all', 'accept all', 'stay here',
                'keep using web', 'close dialog', 'understand'
            ];

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
                const fileWord = uniqueList.length === 1 ? 'image' : 'images';
                const uploadPrefix = label && !label.includes('Global') ? (label + ' ') : '';
                updateStatus(uploadPrefix + 'Uploading ' + uniqueList.length + ' ' + fileWord + '.');
                hostLog('ATTACHMENTS', 'Injecting ' + uniqueList.length + ' file(s) into file input for ' + label);

                const fileInput = document.querySelector('input[data-test-upload-file-input], input[type="file"].file-input, input[type="file"]');
                if (fileInput) {
                    const dt = new DataTransfer();
                    for (const att of uniqueList) {
                        try {
                            const b64Clean = att.data.includes(',') ? att.data.split(',')[1] : att.data;
                            const byteChars = atob(b64Clean);
                            const byteArray = new Uint8Array(byteChars.length);
                            for (let i = 0; i < byteChars.length; i++) {
                                byteArray[i] = byteChars.charCodeAt(i);
                            }
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

                        updateStatus('Processing attached images.');
                        let uploadWait = 0;
                        const maxUploadTicks = Math.max(80, uniqueList.length * 40);
                        while (uploadWait < maxUploadTicks) {
                            const activeUploadIndicator = document.querySelector('mat-progress-bar, mat-spinner, ms-prompt-box .loading-indicator, .media-upload-progress, ms-prompt-box .loading');
                            if (!activeUploadIndicator) break;
                            await delay(500);
                            uploadWait++;
                            if (uploadWait % 16 === 0) {
                                updateStatus('Still uploading images...');
                            }
                        }
                        await randomDelay(800, 1200);
                        hostLog('ATTACHMENTS', 'Media attachments processed for ' + label);
                    }
                }
            }

            function checkUiGenerating() {
                const runBtn = document.querySelector('ms-run-button button, button.ctrl-enter-submits');
                if (runBtn) {
                    if (runBtn.classList.contains('stoppable') ||
                        runBtn.querySelector('.stoppable-spinner, .stoppable-stop, svg[class*="stoppable"]') ||
                        (runBtn.getAttribute('aria-label') || '').toLowerCase().includes('stop') ||
                        (runBtn.querySelector('.run-button-label')?.textContent || '').toLowerCase().includes('stop')) {
                        return true;
                    }
                }
                if (document.querySelector('.run-button.stoppable, ms-run-button .stoppable-spinner, ms-run-button .stoppable-stop, button.stoppable, button[aria-label*="Stop"]')) {
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

            function isBracketsBalanced(text) {
                if (!text) return false;
                const trimmed = text.trim();
                if (!trimmed.endsWith('}') && !trimmed.endsWith('```')) return false;
                let braceCount = 0;
                let bracketCount = 0;
                let inString = false;
                let escaped = false;
                for (let i = 0; i < trimmed.length; i++) {
                    const char = trimmed[i];
                    if (escaped) {
                        escaped = false;
                        continue;
                    }
                    if (char === '\\') {
                        escaped = true;
                        continue;
                    }
                    if (char === '"') {
                        inString = !inString;
                        continue;
                    }
                    if (!inString) {
                        if (char === '{') braceCount++;
                        else if (char === '}') braceCount--;
                        else if (char === '[') bracketCount++;
                        else if (char === ']') bracketCount--;
                    }
                }
                return !inString && braceCount === 0 && bracketCount === 0;
            }

            function getScreenText(turnEl) {
                if (!turnEl) {
                    hostLog('SCRAPE_WARN', 'getScreenText called with null turn element.');
                    return '';
                }
                try {
                    const answerCandidates = Array.from(turnEl.querySelectorAll('ms-text-chunk, ms-code-block, ms-cmark-node, pre code, .rendered-markdown'))
                        .filter(el => !el.closest('ms-thought-chunk'));

                    if (answerCandidates.length === 0) {
                        return '';
                    }

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
                    if (!text) {
                        text = (tempDiv.textContent || '').trim();
                        if (text) {
                            hostLog('SCRAPE_FALLBACK', 'innerText returned empty, fell back to textContent (' + text.length + ' chars)');
                        }
                    }
                    document.body.removeChild(tempDiv);

                    return text;
                } catch(e) {
                    hostLog('SCRAPE_ERR', 'Exception in getScreenText: ' + e.message);
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
                    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', bubbles: true }));
                    el.dispatchEvent(new KeyboardEvent('keyup', { key: 'a', bubbles: true }));
                } catch(e) {
                    hostLog('INJECT_ERR', 'Error typing text: ' + e.message);
                    try { el.value = text; } catch(_) {}
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                }
            }

            function isTemporaryChatActive() {
                return !!(
                    document.querySelector('ms-incognito-mode-indicator') ||
                    document.querySelector('.incognito-container') ||
                    (document.querySelector('.page-title')?.textContent || '').toLowerCase().includes('temporary chat')
                );
            }

            window.__omniPuppet = {
                isConfigured: false,
                isBusy: false,
                aborted: false,

                abort: function() {
                    this.aborted = true;
                    this.isBusy = false;
                    hostLog('PUPPET', 'Puppet execution aborted by host.');
                },

                init: async function() {
                    hostLog('PUPPET_INIT', 'Initializing AI Studio Puppet Engine...');
                    if (window.location.href.includes('accounts.google.com') || window.location.href.includes('/signin/')) {
                        const authErr = 'Account not logged in on this profile. Please log in to Google AI Studio first.';
                        hostLog('AUTH_ERR', authErr);
                        if (window.OmniAutomator && window.OmniAutomator.onPuppetReady) {
                            window.OmniAutomator.onPuppetReady(false, authErr);
                        }
                        return false;
                    }

                    await dismissInterstitials();
                    let mountAttempts = 0;
                    let promptArea = null;

                    while (mountAttempts < 35) {
                        await dismissInterstitials();
                        promptArea = document.querySelector('textarea[formcontrolname="promptText"], textarea[aria-label="Enter a prompt"], textarea');
                        if (promptArea && isElementVisible(promptArea)) break;
                        await delay(1000);
                        mountAttempts++;
                        if (mountAttempts % 5 === 0) {
                            hostLog('MOUNT', 'Polling prompt area (' + mountAttempts + '/35)...');
                        }
                    }

                    if (!promptArea) {
                        const err = 'Failed to locate prompt textarea after 35s.';
                        hostLog('MOUNT_ERR', err);
                        if (window.OmniAutomator && window.OmniAutomator.onPuppetReady) {
                            window.OmniAutomator.onPuppetReady(false, err);
                        }
                        return false;
                    }

                    hostLog('PUPPET_INIT', 'Prompt area detected and ready!');
                    if (window.OmniAutomator && window.OmniAutomator.onPuppetReady) {
                        window.OmniAutomator.onPuppetReady(true, 'Ready');
                    }
                    return true;
                },

                configure: async function(opts) {
                    opts = opts || {};
                    const SYS_TITLE = opts.sysTitle || '';
                    const SYS_PROMPT = opts.sysPrompt || '';
                    const THINKING_LEVEL = opts.thinkingLevel || 'Default';
                    const TEMPORARY_CHAT = !!opts.temporaryChat;
                    const FALLBACK_ENABLED = opts.fallbackEnabled !== false;

                    try {
                        await dismissInterstitials();
                        const currentTempState = isTemporaryChatActive();
                        if (TEMPORARY_CHAT !== currentTempState) {
                            hostLog('TEMP_CHAT', (TEMPORARY_CHAT ? 'Enabling' : 'Disabling') + ' Temporary Chat...');
                            updateStatus(TEMPORARY_CHAT ? 'Activating Temporary Chat...' : 'Restoring persistent chat...');
                            const overflowBtn = document.querySelector('.overflow-menu-wrapper button, button[aria-label*="View more actions"]');
                            if (overflowBtn && isElementVisible(overflowBtn)) {
                                overflowBtn.click();
                                await randomDelay(800, 1200);
                                const tempChatOption = document.querySelector('button[data-test-incognito-toggle], button[aria-label*="temporary chat"]');
                                if (tempChatOption) {
                                    tempChatOption.click();
                                    await randomDelay(1000, 1400);
                                } else {
                                    document.body.click();
                                    await randomDelay(300, 500);
                                }
                            }
                        }

                        const needsSettings = (SYS_PROMPT && SYS_PROMPT.length > 0) || (SYS_TITLE && SYS_TITLE.length > 0) || (THINKING_LEVEL && THINKING_LEVEL !== 'Default');
                        if (needsSettings) {
                            let sysCard = document.querySelector('button.system-instructions-card, button[data-test-system-instructions-card], ms-system-instructions-panel button');
                            if (!isElementVisible(sysCard)) {
                                updateStatus('Opening Settings Drawer...');
                                const tuneBtn = document.querySelector('button.runsettings-toggle-button, button[aria-label*="Toggle run settings"], button[aria-label*="run settings"]');
                                if (tuneBtn && isElementVisible(tuneBtn)) {
                                    tuneBtn.click();
                                    await randomDelay(1000, 1500);
                                }
                            }

                            if (THINKING_LEVEL && THINKING_LEVEL !== 'Default') {
                                try {
                                    const thinkingSelect = document.querySelector('ms-thinking-level-setting mat-select, mat-select[aria-label*="Thinking"]');
                                    if (thinkingSelect) {
                                        const currentVal = (thinkingSelect.textContent || '').trim().toLowerCase();
                                        if (!currentVal.includes(THINKING_LEVEL.toLowerCase())) {
                                            thinkingSelect.click();
                                            await randomDelay(800, 1200);
                                            const options = Array.from(document.querySelectorAll('.mat-mdc-select-panel mat-option, mat-option'));
                                            const match = options.find(o => (o.textContent || '').trim().toLowerCase().includes(THINKING_LEVEL.toLowerCase()));
                                            if (match) match.click();
                                            else document.body.click();
                                            await randomDelay(600, 1000);
                                        }
                                    }
                                } catch(e) {
                                    hostLog('SETTINGS_WARN', 'Thinking level setting failed: ' + e.message);
                                }
                            }

                            if ((SYS_TITLE && SYS_TITLE.length > 0) || (SYS_PROMPT && SYS_PROMPT.length > 0)) {
                                try {
                                    updateStatus('Checking System Instructions...');
                                    sysCard = document.querySelector('button.system-instructions-card, button[data-test-system-instructions-card], ms-system-instructions-panel button');
                                    const cardSub = (sysCard ? (sysCard.querySelector('.subtitle')?.textContent || '') : '').trim();
                                    const alreadyMatches = (SYS_TITLE && cardSub.toLowerCase().includes(SYS_TITLE.toLowerCase())) ||
                                                           (SYS_PROMPT && cardSub.length > 15 && SYS_PROMPT.startsWith(cardSub.slice(0, 15)));

                                    if (alreadyMatches && !FALLBACK_ENABLED) {
                                        hostLog('SYS_PROMPT', 'System instructions match preview. Skipping.');
                                    } else if (sysCard) {
                                        sysCard.click();
                                        await randomDelay(1200, 1800);
                                        let matchedExisting = false;

                                        const sysSelect = document.querySelector('ms-system-instructions mat-select, .select-tooltip-wrapper mat-select');
                                        if (sysSelect) {
                                            const curPreset = (sysSelect.textContent || '').trim().toLowerCase();
                                            if (SYS_TITLE && curPreset === SYS_TITLE.toLowerCase()) {
                                                matchedExisting = true;
                                            } else {
                                                sysSelect.click();
                                                await randomDelay(800, 1200);
                                                const options = Array.from(document.querySelectorAll('.mat-mdc-select-panel mat-option, mat-option'));
                                                if (SYS_TITLE && SYS_TITLE.length > 0) {
                                                    const match = options.find(opt => (opt.textContent || '').trim().toLowerCase() === SYS_TITLE.toLowerCase());
                                                    if (match) {
                                                        match.click();
                                                        matchedExisting = true;
                                                        await randomDelay(800, 1200);
                                                    }
                                                }
                                                if (!matchedExisting) {
                                                    const createNewOpt = options.find(opt => (opt.textContent || '').includes('Create new instruction'));
                                                    if (createNewOpt) {
                                                        createNewOpt.click();
                                                        await randomDelay(800, 1200);
                                                    } else {
                                                        document.body.click();
                                                        await randomDelay(400, 600);
                                                    }
                                                }
                                            }
                                        }

                                        if (!matchedExisting && FALLBACK_ENABLED && SYS_PROMPT && SYS_PROMPT.length > 0) {
                                            const titleInput = document.querySelector('ms-system-instructions .title-row input, ms-system-instructions input[placeholder*="Title"], ms-system-instructions input');
                                            if (titleInput && SYS_TITLE && SYS_TITLE.length > 0 && titleInput.value !== SYS_TITLE) {
                                                safeInjectText(titleInput, SYS_TITLE);
                                                await randomDelay(400, 700);
                                            }
                                            let sysTa = null;
                                            for (let a = 0; a < 15; a++) {
                                                sysTa = document.querySelector('ms-system-instructions textarea, textarea.in-run-settings, textarea[aria-label*="System"], textarea[placeholder*="instructions"]');
                                                if (sysTa && isElementVisible(sysTa)) break;
                                                await delay(400);
                                            }
                                            if (sysTa && sysTa.value !== SYS_PROMPT) {
                                                safeInjectText(sysTa, SYS_PROMPT);
                                                await randomDelay(800, 1400);
                                            }
                                        }

                                        let saveWait = 0;
                                        while (saveWait < 12) {
                                            const saveIndicator = document.querySelector('mat-dialog-container .saving-status, .saving-status');
                                            if (saveIndicator && (saveIndicator.textContent || '').toLowerCase().includes('saved')) break;
                                            await delay(300);
                                            saveWait++;
                                        }
                                        await randomDelay(500, 800);
                                        const closeBtn = document.querySelector('mat-dialog-container button[mat-dialog-close], mat-dialog-container button[aria-label*="Close"], button[data-test-close-button]');
                                        if (closeBtn) {
                                            closeBtn.click();
                                            await randomDelay(1000, 1500);
                                        }
                                    }
                                } catch(e) {
                                    hostLog('SYS_PROMPT_ERR', 'System instructions step failed: ' + e.message);
                                }
                            }

                            let promptReady = false;
                            for (let w = 0; w < 6; w++) {
                                const pa = document.querySelector('textarea[formcontrolname="promptText"], textarea[aria-label="Enter a prompt"], textarea');
                                if (pa && isElementVisible(pa)) { promptReady = true; break; }
                                await delay(250);
                            }
                            if (!promptReady) {
                                const openDrawer = document.querySelector('ms-run-settings.expanded, .content-container ms-run-settings:not(.collapsed)');
                                if (openDrawer && isElementVisible(openDrawer)) {
                                    const closeSettingsBtn = openDrawer.querySelector('button[aria-label*="Close run settings"], button[aria-label*="Close"]');
                                    if (closeSettingsBtn && isElementVisible(closeSettingsBtn)) {
                                        closeSettingsBtn.click();
                                        await randomDelay(800, 1200);
                                    }
                                }
                            }
                        }

                        this.isConfigured = true;
                        if (window.OmniAutomator && window.OmniAutomator.onPuppetConfigured) {
                            window.OmniAutomator.onPuppetConfigured(true, 'Configured');
                        }
                        return true;
                    } catch(err) {
                        hostLog('CONFIG_ERR', err.message);
                        if (window.OmniAutomator && window.OmniAutomator.onPuppetConfigured) {
                            window.OmniAutomator.onPuppetConfigured(false, err.message);
                        }
                        return false;
                    }
                },

                executeTurn: async function(turnOpts) {
                    turnOpts = turnOpts || {};
                    const prompt = turnOpts.prompt || '';
                    const attachments = turnOpts.attachments || [];
                    const stepLabel = turnOpts.label || 'Turn';
                    this.aborted = false;
                    this.isBusy = true;

                    updateStatus((stepLabel + ': Submitting prompt.').trim());
                    await dismissInterstitials();

                    if (attachments && Array.isArray(attachments) && attachments.length > 0) {
                        await injectAttachments(attachments, stepLabel);
                    }

                    let activePromptArea = null;
                    for (let a = 0; a < 25; a++) {
                        const allAreas = Array.from(document.querySelectorAll('textarea[formcontrolname="promptText"], textarea[aria-label="Enter a prompt"], textarea'));
                        activePromptArea = allAreas.length > 0 ? allAreas[allAreas.length - 1] : null;
                        if (activePromptArea && isElementVisible(activePromptArea)) break;
                        await dismissInterstitials();
                        await delay(400);
                    }

                    if (activePromptArea && prompt && prompt.trim().length > 0) {
                        safeInjectText(activePromptArea, prompt);
                        await randomDelay(800, 1400);
                    }

                    if (!checkUiGenerating()) {
                        updateStatus((stepLabel + ': Preparing to run.').trim());
                        let readyTicks = 0;
                        let submitBtn = null;
                        const maxReadyTicks = Math.max(120, (attachments ? attachments.length : 0) * 30);

                        while (readyTicks < maxReadyTicks) {
                            if (checkUiGenerating()) break;
                            submitBtn = document.querySelector('ms-run-button button:not(.stoppable), button.ctrl-enter-submits:not(.stoppable), button[type="submit"]:not(.stoppable)');
                            if (submitBtn && isRunButtonReady(submitBtn)) break;
                            await delay(500);
                            readyTicks++;
                        }

                        let submitTriggered = false;
                        if (!checkUiGenerating() && submitBtn && isRunButtonReady(submitBtn)) {
                            hostLog('RUN', 'Clicking Run button for ' + stepLabel + '...');
                            submitBtn.click();
                            submitTriggered = true;
                            let verifyTicks = 0;
                            while (verifyTicks < 8) {
                                await delay(500);
                                if (checkUiGenerating() || document.querySelector('ms-thought-chunk')) break;
                                verifyTicks++;
                            }
                        }

                        if (!checkUiGenerating() && !submitTriggered && activePromptArea && (activePromptArea.value || '').trim().length > 0) {
                            hostLog('RUN', 'Fallback Ctrl+Enter for ' + stepLabel + '...');
                            activePromptArea.focus();
                            activePromptArea.dispatchEvent(new KeyboardEvent('keydown', {
                                key: 'Enter', code: 'Enter', keyCode: 13, which: 13, ctrlKey: true, bubbles: true, cancelable: true
                            }));
                            let fallbackVerify = 0;
                            while (fallbackVerify < 6) {
                                await delay(500);
                                if (checkUiGenerating()) break;
                                fallbackVerify++;
                            }
                        }
                    }

                    updateStatus((stepLabel + ': Generating answers.').trim());
                    let lastOutputText = '';
                    let lastThoughtsText = '';
                    let contentStaticTicks = 0;
                    let totalTurnTicks = 0;
                    let idleTicks = 0;
                    const MAX_IDLE_TICKS = 100;
                    const MAX_TOTAL_TICKS = 300;
                    let turnRetryCount = 0;
                    const MAX_RETRIES = 2;
                    let currentTurnOutput = '';
                    let latestTurnThoughts = '';

                    while (totalTurnTicks < MAX_TOTAL_TICKS && !this.aborted) {
                        await delay(600);
                        totalTurnTicks++;
                        idleTicks++;

                        try {
                            const autoscroll = document.querySelector('ms-autoscroll-container, .chat-view-container');
                            if (autoscroll) autoscroll.scrollTop = autoscroll.scrollHeight;
                            window.scrollTo(0, document.body.scrollHeight);
                        } catch(_) {}

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
                                updateStatus((stepLabel + ': Retrying turn, attempt ' + turnRetryCount + '.').trim());
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
                                lastOutputText = '';
                                contentStaticTicks = 0;
                                idleTicks = 0;
                                currentTurnOutput = '';
                                await delay(1200);
                                continue;
                            } else {
                                hostLog('STUDIO_ERROR', 'Fatal error on ' + stepLabel + ': ' + errTxt);
                                this.isBusy = false;
                                if (window.OmniAutomator && window.OmniAutomator.onPuppetTurnError) {
                                    window.OmniAutomator.onPuppetTurnError(errTxt);
                                }
                                return { thoughts: latestTurnThoughts, output: '', error: errTxt };
                            }
                        }

                        const thoughtBlocks = Array.from(document.querySelectorAll('ms-thought-chunk'));
                        if (thoughtBlocks.length > 0) {
                            latestTurnThoughts = thoughtBlocks
                                .map(b => (b.innerText || b.textContent || '').trim())
                                .filter(Boolean)
                                .join('\n\n')
                                .trim();
                        }

                        const allModelContainers = Array.from(document.querySelectorAll('.chat-turn-container.model, ms-chat-turn .chat-turn-container.model, [data-turn-role="Model"]'));
                        let activeModelTurn = null;
                        for (let i = allModelContainers.length - 1; i >= 0; i--) {
                            const t = allModelContainers[i];
                            const hasRealAnswer = Array.from(t.querySelectorAll('ms-text-chunk, ms-code-block, ms-cmark-node, pre code, .rendered-markdown'))
                                .some(el => !el.closest('ms-thought-chunk'));
                            if (hasRealAnswer) {
                                activeModelTurn = t;
                                break;
                            }
                        }
                        if (!activeModelTurn && allModelContainers.length > 0) {
                            activeModelTurn = allModelContainers[allModelContainers.length - 1];
                        }

                        if (activeModelTurn) {
                            const screenText = getScreenText(activeModelTurn);
                            if (screenText.length >= currentTurnOutput.length) {
                                currentTurnOutput = screenText;
                            }
                        }

                        const hasContent = currentTurnOutput.length > 0;
                        const textGrew = currentTurnOutput.length > lastOutputText.length;
                        const thoughtsChanged = latestTurnThoughts !== lastThoughtsText;

                        if (textGrew) {
                            idleTicks = 0;
                            contentStaticTicks = 0;
                            lastOutputText = currentTurnOutput;
                            lastThoughtsText = latestTurnThoughts;
                        } else if (thoughtsChanged) {
                            idleTicks = 0;
                            contentStaticTicks = 0;
                            lastThoughtsText = latestTurnThoughts;
                        } else if (hasContent) {
                            contentStaticTicks++;
                        }

                        if (!hasContent && idleTicks >= MAX_IDLE_TICKS) {
                            if (turnRetryCount < MAX_RETRIES) {
                                turnRetryCount++;
                                hostLog('IDLE_RETRY', 'Silence timeout. Auto-rerun ' + turnRetryCount + '/' + MAX_RETRIES);
                                idleTicks = 0;
                                contentStaticTicks = 0;
                                const allTurns = document.querySelectorAll('ms-chat-turn, .chat-turn-container');
                                const lastTurn = allTurns.length > 0 ? allTurns[allTurns.length - 1] : null;
                                const rerunBtn = lastTurn ? lastTurn.querySelector('button[name="rerun-button"], button.rerun-button, button[aria-label*="Rerun"]') : null;
                                if (rerunBtn && isElementVisible(rerunBtn)) rerunBtn.click();
                                else {
                                    const retrySubmit = document.querySelector('ms-run-button button:not(.stoppable)');
                                    if (retrySubmit && isRunButtonReady(retrySubmit)) retrySubmit.click();
                                }
                                await delay(2000);
                                continue;
                            } else {
                                const timeoutErr = 'AI Studio generation stalled after ' + MAX_RETRIES + ' rerun attempts.';
                                this.isBusy = false;
                                if (window.OmniAutomator && window.OmniAutomator.onPuppetTurnError) {
                                    window.OmniAutomator.onPuppetTurnError(timeoutErr);
                                }
                                return { thoughts: latestTurnThoughts, output: '', error: timeoutErr };
                            }
                        }

                        if (window.OmniAutomator && window.OmniAutomator.onPuppetTurnProgress) {
                            window.OmniAutomator.onPuppetTurnProgress(latestTurnThoughts, currentTurnOutput);
                        }

                        const trimmedOutput = currentTurnOutput.trim();
                        const looksLikeJson = trimmedOutput.startsWith('{') || trimmedOutput.startsWith('[') || trimmedOutput.includes('"solutions"');
                        const balanced = looksLikeJson && isBracketsBalanced(trimmedOutput);
                        const targetTicks = balanced ? 5 : 25;

                        if (hasContent && contentStaticTicks >= targetTicks && (balanced || trimmedOutput.length >= 20)) {
                            hostLog('DECISION_STOP', 'Turn completed. Output length: ' + currentTurnOutput.length);
                            break;
                        }
                    }

                    this.isBusy = false;
                    if (window.OmniAutomator && window.OmniAutomator.onPuppetTurnComplete) {
                        window.OmniAutomator.onPuppetTurnComplete(latestTurnThoughts, currentTurnOutput);
                    }
                    return { thoughts: latestTurnThoughts, output: currentTurnOutput, error: null };
                },

                runBatch: async function(config, steps) {
                    const ready = await this.init();
                    if (!ready) return;

                    await this.configure(config);
                    await randomDelay(800, 1200);

                    if (config.attachments && config.attachments.length > 0) {
                        await injectAttachments(config.attachments, 'Turn 1 Global');
                    }

                    let fullCumulativeOutput = '';
                    let latestTurnThoughts = '';
                    let totalTurnsExecuted = 0;

                    for (let stepIdx = 0; stepIdx < steps.length; stepIdx++) {
                        const currentStep = steps[stepIdx];
                        const stepNum = stepIdx + 1;
                        const maxRepeats = currentStep.isInfinite ? Infinity : (currentStep.repeatCount || 1);
                        let currentRepeat = 0;

                        while (currentRepeat < maxRepeats && !this.aborted) {
                            currentRepeat++;
                            totalTurnsExecuted++;
                            let stepPrefix = (steps.length > 1 ? 'Step ' + stepNum + '. ' : '') + (maxRepeats > 1 ? 'Repeat ' + currentRepeat + '. ' : '');
                            const label = stepPrefix.trim();

                            const result = await this.executeTurn({
                                prompt: currentStep.prompt,
                                attachments: currentStep.attachments,
                                label: label
                            });

                            if (result.error) {
                                if (window.OmniAutomator) window.OmniAutomator.onError(result.error);
                                return;
                            }

                            latestTurnThoughts = result.thoughts;
                            fullCumulativeOutput = fullCumulativeOutput.length > 0
                                ? fullCumulativeOutput + '\n\n--- [Turn ' + totalTurnsExecuted + ': ' + label + '] ---\n' + result.output
                                : result.output;

                            if (window.OmniAutomator) window.OmniAutomator.onProgress(latestTurnThoughts, fullCumulativeOutput);

                            const isLast = (stepIdx === steps.length - 1) && (currentRepeat >= maxRepeats);
                            if (!isLast) {
                                updateStatus((stepPrefix + 'Step completed. Preparing next turn.').trim());
                                await randomDelay(1800, 2600);
                            }
                        }
                    }

                    updateStatus('Solution ready.');
                    if (window.OmniAutomator) window.OmniAutomator.onComplete(latestTurnThoughts, fullCumulativeOutput);
                }
            };
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
    val puppetScript = buildAiStudioPuppetScript()
    return """
        (async function() {
            if (window.__omniAutomating) return;
            window.__omniAutomating = true;

            $puppetScript

            const config = {
                sysTitle: $sysTitle,
                sysPrompt: $sysPrompt,
                thinkingLevel: $thinkingLevel,
                model: $model,
                fallbackEnabled: $fallbackEnabled,
                temporaryChat: $temporaryChat,
                attachments: $attachmentsJson
            };

            const stepsData = $stepsJson;
            const fallbackPrompt = $prompt;
            const steps = (stepsData && Array.isArray(stepsData) && stepsData.length > 0)
                ? stepsData
                : [{ prompt: fallbackPrompt, repeatCount: 1, isInfinite: false, attachments: [] }];

            try {
                await window.__omniPuppet.runBatch(config, steps);
            } catch(e) {
                if (window.OmniAutomator) window.OmniAutomator.onError(e.message);
            } finally {
                window.__omniAutomating = false;
            }
        })();
    """.trimIndent()
}

fun optimizeImageForAiStudio(
    context: android.content.Context,
    uri: android.net.Uri,
    maxDimension: Int = 2048,
    jpegQuality: Int = 90
): Pair<ByteArray, String> {
    val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"

    if (!mimeType.startsWith("image/")) {
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        return Pair(rawBytes, mimeType)
    }

    try {
        var orientation = android.media.ExifInterface.ORIENTATION_NORMAL
        context.contentResolver.openInputStream(uri)?.use { input ->
            try {
                val exif = android.media.ExifInterface(input)
                orientation = exif.getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL
                )
            } catch (_: Exception) {}
        }

        val boundsOptions = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, boundsOptions)
        }

        val origW = boundsOptions.outWidth
        val origH = boundsOptions.outHeight
        if (origW <= 0 || origH <= 0) {
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            return Pair(rawBytes, mimeType)
        }

        var inSampleSize = 1
        var tempW = origW
        var tempH = origH
        while (tempW > maxDimension * 1.4 || tempH > maxDimension * 1.4) {
            inSampleSize *= 2
            tempW /= 2
            tempH /= 2
        }

        val decodeOptions = android.graphics.BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val decodedBitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, decodeOptions)
        } ?: return Pair(context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0), mimeType)

        var targetW = decodedBitmap.width
        var targetH = decodedBitmap.height
        if (targetW > maxDimension || targetH > maxDimension) {
            val ratio = if (targetW > targetH) {
                maxDimension.toFloat() / targetW
            } else {
                maxDimension.toFloat() / targetH
            }
            targetW = (targetW * ratio).toInt().coerceAtLeast(1)
            targetH = (targetH * ratio).toInt().coerceAtLeast(1)
        }

        val matrix = android.graphics.Matrix()
        if (targetW != decodedBitmap.width || targetH != decodedBitmap.height) {
            val scaleX = targetW.toFloat() / decodedBitmap.width
            val scaleY = targetH.toFloat() / decodedBitmap.height
            matrix.postScale(scaleX, scaleY)
        }

        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }

        val finalBitmap = if (!matrix.isIdentity) {
            val transformed = android.graphics.Bitmap.createBitmap(
                decodedBitmap, 0, 0, decodedBitmap.width, decodedBitmap.height, matrix, true
            )
            if (transformed != decodedBitmap) {
                decodedBitmap.recycle()
            }
            transformed
        } else {
            decodedBitmap
        }

        val baos = java.io.ByteArrayOutputStream()
        finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, jpegQuality, baos)
        finalBitmap.recycle()

        return Pair(baos.toByteArray(), "image/jpeg")
    } catch (e: Exception) {
        val fallback = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        return Pair(fallback, mimeType)
    }
}

fun getFileEmoji(filename: String): String {
    val ext = filename.substringAfterLast('.', "").lowercase(Locale.US)
    return when (ext) {
        "zip", "tar", "gz", "rar", "7z" -> "📦"
        "html", "htm", "js", "ts", "json", "kt", "java", "py", "css" -> "💻"
        "png", "jpg", "jpeg", "webp", "gif", "svg" -> "🖼️"
        "mp4", "mkv", "webm", "mov" -> "🎬"
        "mp3", "wav", "m4a", "flac" -> "🎵"
        "pdf", "doc", "docx", "txt", "md" -> "📄"
        "apk" -> "🤖"
        else -> "📁"
    }
}

fun resolveMimeType(file: java.io.File): String {
    val ext = file.extension.lowercase(Locale.US)
    val defaultMime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    if (!defaultMime.isNullOrEmpty()) return defaultMime

    return when (ext) {
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "txt", "log", "md" -> "text/plain"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4", "mkv", "webm" -> "video/*"
        "mp3", "wav", "m4a", "flac" -> "audio/*"
        else -> "*/*"
    }
}

fun getShareableUri(context: android.content.Context, file: java.io.File): Uri {
    // 1. Primary & Recommended: FileProvider (Direct content URI, zero collision)
    try {
        val fileProviderClass = Class.forName("androidx.core.content.FileProvider")
        val getUriMethod = fileProviderClass.getMethod(
            "getUriForFile",
            android.content.Context::class.java,
            String::class.java,
            java.io.File::class.java
        )
        val uri = getUriMethod.invoke(null, context, "${context.packageName}.fileprovider", file) as? Uri
        if (uri != null) return uri
    } catch (_: Exception) {}

    // 2. Secondary: MediaStore lookup strictly verified by exact absolute disk path
    try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val projection = arrayOf(android.provider.MediaStore.MediaColumns._ID)
            val exactPathSelection = "${android.provider.MediaStore.MediaColumns.DATA} = ?"
            val exactPathArgs = arrayOf(file.absolutePath)

            val queryUri = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            context.contentResolver.query(queryUri, projection, exactPathSelection, exactPathArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID))
                    return android.content.ContentUris.withAppendedId(queryUri, id)
                }
            }

            val filesUri = android.provider.MediaStore.Files.getContentUri("external")
            context.contentResolver.query(filesUri, projection, exactPathSelection, exactPathArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID))
                    return android.content.ContentUris.withAppendedId(filesUri, id)
                }
            }
        }
    } catch (_: Exception) {}

    // 3. Fallback: Relax VM policy and return direct file:// URI
    try {
        val builder = android.os.StrictMode.VmPolicy.Builder()
        android.os.StrictMode.setVmPolicy(builder.build())
    } catch (_: Exception) {}

    return Uri.fromFile(file)
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
        else -> "$bytes B"
    }
}

fun openDownloadedFile(context: android.content.Context, file: java.io.File, bridge: com.omni.hub.api.HostBridge) {
    try {
        if (!file.exists()) {
            bridge.showToast("File no longer exists on storage")
            return
        }
        val mime = resolveMimeType(file)
        val contentUri = getShareableUri(context, file)
        bridge.log("DOWNLOAD_OPEN", "Opening ${file.name} ($mime, ${formatFileSize(file.length())}) via $contentUri")
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = android.content.Intent.createChooser(intent, "Open with...").apply {
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    } catch (e: android.content.ActivityNotFoundException) {
        bridge.showToast("No installed app found to open .${file.extension} files")
    } catch (e: Exception) {
        bridge.showToast("Could not open file: ${e.message}")
    }
}

val BACKGROUND_MEDIA_SCRIPT = """
(function() {
    if (window.__omniMediaHooked) return;
    window.__omniMediaHooked = true;

    function hostLog(tag, msg) {
        try {
            if (window.OmniMediaBridge && window.OmniMediaBridge.log) {
                window.OmniMediaBridge.log(tag, msg);
            }
        } catch(_) {}
    }

    // 1. Defang Visibility & Focus Snitches
    try {
        Object.defineProperty(document, 'hidden', { get: () => false, configurable: true });
        Object.defineProperty(document, 'visibilityState', { get: () => 'visible', configurable: true });
        Object.defineProperty(document, 'webkitVisibilityState', { get: () => 'visible', configurable: true });
        Object.defineProperty(document, 'webkitHidden', { get: () => false, configurable: true });
        document.hasFocus = function() { return true; };
    } catch(e) {}

    // Trap property event setters
    try {
        Object.defineProperty(document, 'onvisibilitychange', { get: () => null, set: () => {}, configurable: true });
        Object.defineProperty(window, 'onblur', { get: () => null, set: () => {}, configurable: true });
        Object.defineProperty(window, 'onpagehide', { get: () => null, set: () => {}, configurable: true });
    } catch(e) {}

    // Trap listener registrations that pause background video silently
    const origAddEvent = EventTarget.prototype.addEventListener;
    EventTarget.prototype.addEventListener = function(type, listener, options) {
        if (type === 'visibilitychange' || type === 'webkitvisibilitychange') {
            return;
        }
        if (type === 'blur' || type === 'pagehide' || type === 'freeze') {
            if (this === window || this === document) {
                return;
            }
        }
        return origAddEvent.call(this, type, listener, options);
    };

    // 2. Wrap IntersectionObserver so player element is treated as 100% on-screen
    if (window.IntersectionObserver) {
        try {
            const OrigIO = window.IntersectionObserver;
            window.IntersectionObserver = function(callback, options) {
                const wrappedCallback = function(entries, observer) {
                    const spoofedEntries = entries.map(entry => {
                        if (entry.target && (entry.target.tagName === 'VIDEO' || entry.target.querySelector('video') || entry.target.classList?.contains('html5-video-player'))) {
                            return new Proxy(entry, {
                                get: function(target, prop) {
                                    if (prop === 'isIntersecting') return true;
                                    if (prop === 'intersectionRatio') return 1.0;
                                    return target[prop];
                                }
                            });
                        }
                        return entry;
                    });
                    return callback(spoofedEntries, observer);
                };
                return new OrigIO(wrappedCallback, options);
            };
            window.IntersectionObserver.prototype = OrigIO.prototype;
        } catch(e) {}
    }

    // 3. Hook MediaSession action handlers (YouTube internal player controller)
    if (navigator.mediaSession) {
        navigator.mediaSession.__omniActionHandlers = navigator.mediaSession.__omniActionHandlers || {};
        const origSetAction = navigator.mediaSession.setActionHandler;
        navigator.mediaSession.setActionHandler = function(action, handler) {
            navigator.mediaSession.__omniActionHandlers[action] = handler;
            hostLog('MEDIA_JS', 'Captured navigator.mediaSession action handler: ' + action);
            return origSetAction.call(this, action, handler);
        };
    }

    // 4. Two-Way Host Playback Toggle Command
    window.__omniTogglePlay = function(play) {
        hostLog('MEDIA_JS', 'window.__omniTogglePlay command received (play=' + play + ')');

        let handledBySession = false;
        if (navigator.mediaSession && navigator.mediaSession.__omniActionHandlers) {
            const targetAction = play ? 'play' : 'pause';
            const actionFn = navigator.mediaSession.__omniActionHandlers[targetAction];
            if (typeof actionFn === 'function') {
                try {
                    actionFn();
                    handledBySession = true;
                    hostLog('MEDIA_JS', 'Successfully dispatched to navigator.mediaSession.' + targetAction + '()');
                } catch(e) {
                    hostLog('MEDIA_JS_WARN', 'MediaSession action failed: ' + e.message);
                }
            }
        }

        // Direct HTMLMediaElement control
        const media = Array.from(document.querySelectorAll('video, audio'));
        hostLog('MEDIA_JS', 'Direct control applied to ' + media.length + ' media element(s)');
        media.forEach(m => {
            try {
                if (play) {
                    const p = m.play();
                    if (p && p.catch) p.catch(err => hostLog('MEDIA_JS_WARN', 'play() caught: ' + err.message));
                } else {
                    m.pause();
                }
            } catch(e) {}
        });

        // UI button fallback
        if (!handledBySession) {
            const playBtn = document.querySelector('.ytp-play-button, button.play-button');
            if (playBtn) {
                const label = (playBtn.getAttribute('aria-label') || playBtn.getAttribute('data-title-no-tooltip') || '').toLowerCase();
                const isPaused = label.includes('play');
                if ((play && isPaused) || (!play && !isPaused)) {
                    playBtn.click();
                    hostLog('MEDIA_JS', 'Toggled YouTube player button via DOM click');
                }
            }
        }
    };

    function getMediaTitle() {
        if (navigator.mediaSession && navigator.mediaSession.metadata && navigator.mediaSession.metadata.title) {
            return navigator.mediaSession.metadata.title;
        }
        const ytTitle = document.querySelector('.ytp-title-link, h1.title, .slim-video-metadata-title');
        if (ytTitle && ytTitle.textContent) return ytTitle.textContent.trim();
        return document.title.replace(' - YouTube', '').trim() || 'Audio Track';
    }

    function getMediaArtist() {
        if (navigator.mediaSession && navigator.mediaSession.metadata && navigator.mediaSession.metadata.artist) {
            return navigator.mediaSession.metadata.artist;
        }
        const ytChannel = document.querySelector('#owner-name, #channel-name, .ytm-channel-name');
        if (ytChannel && ytChannel.textContent) return ytChannel.textContent.trim();
        return window.location.hostname;
    }

    function notifyState(isPlaying) {
        if (window.OmniMediaBridge && window.OmniMediaBridge.reportMediaState) {
            const title = getMediaTitle();
            const artist = getMediaArtist();
            hostLog('MEDIA_JS', 'Notifying state -> title: "' + title + '" | artist: "' + artist + '" | isPlaying: ' + isPlaying);
            window.OmniMediaBridge.reportMediaState(title, artist, isPlaying);
        }
    }

    function hookMedia(el) {
        if (el.__omniHooked) return;
        el.__omniHooked = true;
        el.addEventListener('play', () => notifyState(true));
        el.addEventListener('playing', () => notifyState(true));
        el.addEventListener('pause', () => notifyState(false));
        el.addEventListener('ended', () => notifyState(false));
        hostLog('MEDIA_JS', 'Hooked events on ' + el.tagName + ' element');
    }

    document.querySelectorAll('video, audio').forEach(hookMedia);

    try {
        const observer = new MutationObserver((mutations) => {
            mutations.forEach(m => {
                m.addedNodes.forEach(node => {
                    if (node.nodeType === 1) {
                        if (node.tagName === 'VIDEO' || node.tagName === 'AUDIO') hookMedia(node);
                        node.querySelectorAll && node.querySelectorAll('video, audio').forEach(hookMedia);
                    }
                });
            });
        });
        observer.observe(document.documentElement || document.body, { childList: true, subtree: true });
    } catch(e) {}
})();
""".trimIndent()

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

val OMNI_SHIELD_BLOCKED_HTML = """
    <!DOCTYPE html>
    <html>
    <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Blocked by Omni Shield</title>
        <style>
            body {
                background-color: #0D1117;
                color: #E8EAED;
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                display: flex;
                flex-direction: column;
                align-items: center;
                justify-content: center;
                height: 100vh;
                margin: 0;
                padding: 24px;
                box-sizing: border-box;
                text-align: center;
            }
            .shield-icon { font-size: 54px; margin-bottom: 16px; }
            h1 { color: #F85149; font-size: 22px; margin: 0 0 10px 0; }
            p { color: #8B949E; font-size: 13px; line-height: 1.5; max-width: 320px; margin: 0; }
            .badge {
                margin-top: 24px;
                background: #161B22;
                border: 1px solid rgba(248, 81, 73, 0.4);
                color: #F85149;
                font-size: 11px;
                font-weight: bold;
                padding: 6px 12px;
                border-radius: 12px;
            }
        </style>
    </head>
    <body>
        <div class="shield-icon">🛡️</div>
        <h1>Access Strictly Restricted</h1>
        <p>Omni Shield is permanently active in microkernel hardware mode. Explicit adult content and domains are restricted at all times.</p>
        <div class="badge">HARDCODED SENTINEL ACTIVE</div>
    </body>
    </html>
""".trimIndent()

private val ADULT_DOMAINS = setOf(
    "pornhub", "xvideos", "xnxx", "xhamster", "onlyfans", "chaturbate", "redtube",
    "youporn", "spankbang", "tube8", "beeg", "stripchat", "cam4", "brazzers",
    "bangbros", "naughtyamerica", "eporner", "tnaflix", "rule34", "erome", "fapello",
    "heavy-r", "motherless", "literotica", "danbooru", "gelbooru", "e-hentai",
    "nhentai", "hitomi", "livejasmin", "myfreecams", "camsoda", "imlive",
    "thothub", "coomer", "kemono", "hqporner", "daftsex", "vporn", "redgifs",
    "pornhat", "sunporno", "nuvid", "txxx", "cumlouder", "porndoe", "upornia",
    "nudostar", "bunkr", "fansly", "manyvids", "simpcity", "cyberdrop",
    "bongacams", "camwhores"
)

private val DISTRACTION_DOMAINS = setOf(
    "twitter", "x.com", "t.co", "reddit", "instagram", "tiktok", "facebook", "vk.com", 
    "tumblr", "pinterest", "4chan", "8kun", "snapchat", "tinder", "bumble", 
    "badoo", "of.com", "fansly", "bsky.app", "bsky.social", "bluesky", "mastodon", "mastodon.social",
    "threads.net", "threads", "discord.com", "discord.gg", "discordapp.com", "discord",
    "twitch.tv", "twitch", "kick.com", "kick", "9gag.com", "9gag", "ifunny.co", "ifunny",
    "imgur.com", "imgur", "weibo.com", "weibo", "hinge.co", "hinge", "okcupid.com", "okcupid",
    "grindr.com", "grindr", "match.com", "pof.com", "lemmy.world", "lemmy.ml", "lemmy",
    "kbin.social", "kbin", "nitter", "redlib", "libreddit", "teddit", "proxitok",
    "primal.net", "iris.to", "coracle.social", "snort.social", "nostr", "spoutible.com", "spoutible",
    "croxyproxy.com", "croxyproxy", "proxysite.com", "proxysite", "blockaway.net", "blockaway",
    "kproxy.com", "kproxy", "hide.me", "hidemyass.com", "plainproxies.com", "filterbypass.me",
    "vpnbook.com", "zend2.com", "4everproxy.com"
)

val EXTENSIVE_EXPLICIT_TOKENS = setOf(
    "porn", "porno", "xxx", "hentai", "jav", "incest", "milf", "milfs", "gangbang",
    "blowjob", "handjob", "cumshot", "deepthroat", "masturbat", "masturbation",
    "creampie", "shemale", "cuckold", "bdsm", "camgirl", "stripclub", "hardcoresex",
    "nude", "nudes", "nudity", "naked", "sex", "sexy", "sexx", "pussy", "pussies",
    "tits", "titties", "titty", "boobs", "boobies", "dick", "dicks", "cock", "cocks",
    "vagina", "penis", "slut", "sluts", "whore", "whores", "rule34", "danbooru",
    "gelbooru", "e-hentai", "nhentai", "hitomi", "thothub", "coomer", "kemono",
    "squirt", "dildo", "vibrator", "stepmom", "stepbro", "futa", "erotica", "erotic",
    "fetish", "kink", "bondage", "orgasm", "escorts", "smut", "doujin", "bitch",
    "cunt", "faggot", "motherfucker", "camshow", "stripchat", "chaturbate",
    "spankbang", "xvideos", "pornhub", "eporner", "beeg", "naughtyamerica",
    "brazzers", "bangbros", "faketaxi", "publicagent", "onlyfans", "fansly",
    "manyvids", "loyalfans", "xhamster", "redtube", "xnxx", "xnx", "tube8",
    "youporn", "nsfw", "gore", "bestiality", "zoophilia", "necrophilia",
    "pedophile", "loli", "shota", "snuff", "scat", "pegging", "gloryhole",
    "bukkake", "threesome", "swinger", "cuck", "jerkoff", "joi", "ceb",
    "sph", "sissy", "chastity", "findom", "footfetish", "dominatrix",
    "submissive", "hentaigasm", "hanime", "asshole", "buttplug", "anal",
    "facesitting", "facial", "cum", "swallow", "redgifs"
)

private var lastFocusLockCheckTime = 0L
private var cachedFocusLockState = false

fun resetFocusLockCache() {
    lastFocusLockCheckTime = 0L
}

fun isFocusLockActive(context: android.content.Context): Boolean {
    val now = System.currentTimeMillis()
    if (now - lastFocusLockCheckTime < 500L) return cachedFocusLockState

    var isActive = false

    // 1. Automatic Night Curfew (10:00 PM – 6:00 AM)
    val cal = java.util.Calendar.getInstance()
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
    if (hour >= 22 || hour < 6) {
        isActive = true
    }

    if (!isActive) {
        try {
            // 2. Check ScrollLock (both self-imposed 1-hour lock and 30m doomscroll penalty box)
            val slState = java.io.File(context.getDir("plugins_data", android.content.Context.MODE_PRIVATE), "scroll_lock/scroll_lock_state.json")
            if (slState.exists()) {
                val json = org.json.JSONObject(slState.readText(Charsets.UTF_8))
                val manualUntil = json.optLong("manual_lock_until_ms", 0L)
                val penaltyUntil = json.optLong("penalty_until_ms", 0L)
                if (now < manualUntil || now < penaltyUntil) {
                    isActive = true
                }
            }

            // 3. Check FocusShield (checking config/ subpath & root, and manual_lock_until_ms)
            val fsDirs = listOf(
                java.io.File(context.getDir("plugins_data", android.content.Context.MODE_PRIVATE), "focus_shield/config/focus_shield_state.json"),
                java.io.File(context.getDir("plugins_data", android.content.Context.MODE_PRIVATE), "focus_shield/focus_shield_state.json")
            )
            for (fsFile in fsDirs) {
                if (fsFile.exists() && !isActive) {
                    val json = org.json.JSONObject(fsFile.readText(Charsets.UTF_8))
                    val manualUntil = json.optLong("manual_lock_until_ms", 0L)
                    val lockUntil = json.optLong("lock_until_ms", 0L)
                    val isExplicitActive = json.optBoolean("is_active", false)
                    if (now < manualUntil || now < lockUntil || isExplicitActive) {
                        isActive = true
                        break
                    }
                }
            }
        } catch (_: Exception) {}
    }

    cachedFocusLockState = isActive
    lastFocusLockCheckTime = now
    return isActive
}

fun buildFocusWatchdogScript(): String {
    val regexStr = EXTENSIVE_EXPLICIT_TOKENS.joinToString("|")
    return """
    (function() {
        if (window.__omniFocusWatchdogActive) return;
        const host = (window.location.hostname || '').toLowerCase();
        if (host.includes('aistudio.google.com') || host.includes('localhost') || host.includes('127.0.0.1')) {
            return;
        }
        window.__omniFocusWatchdogActive = true;
        const badWords = new RegExp('\\b(' + '$regexStr' + ')\\b', 'i');
        
        function checkText(txt) {
            if (txt && badWords.test(txt)) {
                if (window.OmniFocusBridge) window.OmniFocusBridge.violationDetected();
                return true;
            }
            return false;
        }
        
        ['input', 'change', 'keyup'].forEach(evt => {
            document.addEventListener(evt, e => {
                const target = e.target;
                if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable || target.getAttribute('contenteditable') === 'true')) {
                    const val = target.value || target.innerText || target.textContent;
                    if (checkText(val)) {
                        try { target.value = ''; target.innerText = ''; target.textContent = ''; } catch(_) {}
                    }
                }
            }, true);
        });
        
        setInterval(() => {
            document.querySelectorAll('input, textarea, [contenteditable="true"]').forEach(el => {
                const val = el.value || el.innerText || el.textContent;
                if (checkText(val)) {
                    try { el.value = ''; el.innerText = ''; el.textContent = ''; } catch(_) {}
                }
            });
        }, 2000);
    })();
    """.trimIndent()
}

fun isOmniShieldBlocked(rawUrl: String?, context: android.content.Context? = null): Boolean {
    if (rawUrl.isNullOrBlank()) return false
    val clean = rawUrl.lowercase(Locale.US).trim()
    if (clean == "about:blank" ||
        clean.startsWith("http://localhost") ||
        clean.startsWith("http://127.0.0.1") ||
        clean.contains("aistudio.google.com") ||
        clean.contains("generativelanguage.googleapis.com") ||
        clean.contains("alkalimakersuite")) return false

    val isFocusLocked = context != null && isFocusLockActive(context)
    val activeDomains = if (isFocusLocked) ADULT_DOMAINS + DISTRACTION_DOMAINS else ADULT_DOMAINS
    val activeTokens = EXTENSIVE_EXPLICIT_TOKENS

    try {
        val uri = Uri.parse(if (!clean.startsWith("http://") && !clean.startsWith("https://")) "https://$clean" else clean)
        val host = uri.host?.lowercase(Locale.US) ?: clean

        // 1. Search Engine Exemption: Query strings on engines are guarded by enforceSafeSearch, not domain evictions
        // Defend against translator and cache proxy bypasses (e.g. translate.google.com?u=...)
        val isTranslationOrCacheProxy = host.startsWith("translate.google.") || 
                                        host.contains("webcache.googleusercontent.") || 
                                        host.contains("googleusercontent.com")
        val isSearchEngine = !isTranslationOrCacheProxy && 
                             (host.contains("google.") || host.contains("duckduckgo.com") ||
                              host.contains("bing.com") || host.contains("ecosia.org"))

        // Deep URL Probe: Catch web proxies or translators passing target URLs inside query parameters
        val embeddedTarget = uri.getQueryParameter("url") ?: uri.getQueryParameter("u") ?: uri.getQueryParameter("target") ?: uri.getQueryParameter("dest")
        if (!embeddedTarget.isNullOrEmpty() && isOmniShieldBlocked(embeddedTarget, context)) {
            return true
        }

        if (isSearchEngine) {
            return false
        }

        // 2. Strict Host-Only Domain Boundary Matching (Prevents URL path/query false alarms)
        for (domain in activeDomains) {
            val cleanDomain = domain.removePrefix("www.").lowercase(Locale.US)
            val matches = if (cleanDomain.contains(".")) {
                host == cleanDomain || host.endsWith(".$cleanDomain")
            } else {
                host == "$cleanDomain.com" || host.endsWith(".$cleanDomain.com") || host.split('.').contains(cleanDomain)
            }
            if (matches) return true
        }

        // 3. Exact word tokens (split on non-alphanumeric delimiters)
        val tokens = clean.split(Regex("[^a-z0-9]")).filter { it.length >= 3 }
        for (token in tokens) {
            if (activeTokens.contains(token)) {
                // Protect engineering keywords against ambiguous roots
                if (token == "anal" && (clean.contains("analytics") || clean.contains("analysis") || clean.contains("analog") || clean.contains("analyze"))) continue
                if (token == "cock" && (clean.contains("peacock") || clean.contains("cocktail") || clean.contains("cockpit"))) continue
                return true
            }
        }

        // 4. Substring matching for distinct compound explicit words (skipping ambiguous roots)
        val ambiguousTokens = setOf("anal", "cock", "dick", "cuck", "tit", "tits", "jerk")
        for (token in activeTokens) {
            if (token.length >= 4 && !ambiguousTokens.contains(token) && clean.contains(token)) {
                return true
            }
        }

        // 5. Word-boundary regex for short high-risk tokens
        val shortExplicitRegex = Regex("(?:^|[^a-z])(sex|xxx|cum|fap|joi|sph)(?:[^a-z]|$)")
        if (shortExplicitRegex.containsMatchIn(clean)) return true
    } catch (_: Exception) {
        for (domain in activeDomains) {
            val cleanDomain = domain.removePrefix("www.").lowercase(Locale.US)
            if (clean.contains(cleanDomain)) return true
        }
    }
    return false
}

fun enforceSafeSearch(rawUrl: String): String {
    return try {
        val uri = Uri.parse(rawUrl)
        val host = uri.host?.lowercase(Locale.US) ?: return rawUrl

        if (host.contains("google.") && uri.path?.contains("/search") == true) {
            return uri.buildUpon().appendQueryParameter("safe", "active").build().toString()
        }

        if (host.contains("duckduckgo.com")) {
            return uri.buildUpon().appendQueryParameter("kp", "1").build().toString()
        }

        if (host.contains("bing.com") && uri.path?.contains("/search") == true) {
            return uri.buildUpon().appendQueryParameter("adlt", "strict").build().toString()
        }

        if (host.contains("youtube.com") || host == "youtu.be") {
            return uri.buildUpon().appendQueryParameter("safe", "strict").build().toString()
        }

        if (host.contains("reddit.com")) {
            return uri.buildUpon()
                .appendQueryParameter("include_over_18", "0")
                .appendQueryParameter("nsfw", "0")
                .build().toString()
        }

        rawUrl
    } catch (_: Exception) {
        rawUrl
    }
}

fun sanitizeSafeUrl(rawUrl: String?, context: android.content.Context? = null): String {
    if (rawUrl.isNullOrBlank() || rawUrl == "about:blank") return "about:blank"
    val trimmed = rawUrl.trim()
    if (isOmniShieldBlocked(trimmed, context)) {
        return "about:blank"
    }
    val safeEnforced = enforceSafeSearch(trimmed)
    if (safeEnforced.length > 1200) {
        val cleanBase = safeEnforced.substringBefore('?')
        return if (cleanBase.length in 1..1200) cleanBase else "about:blank"
    }
    return safeEnforced
}

fun isLocalFilePath(input: String): Boolean {
    val clean = input.trim().lowercase()
    return clean.startsWith("/") ||
           clean.startsWith("file:") ||
           clean.startsWith("content:") ||
           clean.startsWith("sdcard/") ||
           clean.startsWith("storage/") ||
           clean.endsWith(".html") ||
           clean.endsWith(".htm") ||
           (clean.contains("localhost") && (clean.contains("/ide") || clean.contains("vault_")))
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