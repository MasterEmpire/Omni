package com.omni.plugin.browser.utils

val GEMINI_HEADLESS_TURBO_SCRIPT = """
(function() {
    if (window.__omniGeminiHeadlessTurboHooked) return;
    window.__omniGeminiHeadlessTurboHooked = true;

    try {
        Object.defineProperty(document, 'hidden', { get: () => false, configurable: true });
        Object.defineProperty(document, 'visibilityState', { get: () => 'visible', configurable: true });
        Object.defineProperty(document, 'webkitVisibilityState', { get: () => 'visible', configurable: true });
        Object.defineProperty(document, 'webkitHidden', { get: () => false, configurable: true });
        document.hasFocus = function() { return true; };
    } catch(_) {}

    if (!window.__omniGeminiVisibilityHooked) {
        window.__omniGeminiVisibilityHooked = true;
        const origAddEvent = EventTarget.prototype.addEventListener;
        EventTarget.prototype.addEventListener = function(type, listener, options) {
            if (type === 'visibilitychange' || type === 'webkitvisibilitychange' || type === 'freeze') {
                return;
            }
            return origAddEvent.call(this, type, listener, options);
        };
    }

    if (!window.__omniGeminiRafUnchoked) {
        window.__omniGeminiRafUnchoked = true;
        const origRaf = window.requestAnimationFrame;
        const origCancelRaf = window.cancelAnimationFrame;
        const pendingCallbacks = new Map();
        let rafIdCounter = 1;

        window.requestAnimationFrame = function(cb) {
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

            const throttleMs = window.__omniGeminiHeadlessTurbo ? 350 : 180;
            const timeoutId = setTimeout(function() {
                if (!executed) {
                    executed = true;
                    pendingCallbacks.delete(id);
                    try { if (nativeId !== null) origCancelRaf.call(window, nativeId); } catch(_) {}
                    cb(performance.now());
                }
            }, throttleMs);

            pendingCallbacks.set(id, { nativeId: nativeId, timeoutId: timeoutId });
            return id;
        };

        window.cancelAnimationFrame = function(id) {
            const entry = pendingCallbacks.get(id);
            if (entry) {
                pendingCallbacks.delete(id);
                try { if (entry.nativeId !== null) origCancelRaf.call(window, entry.nativeId); } catch(_) {}
                clearTimeout(entry.timeoutId);
            }
        };
    }

    window.__omniGeminiEnableHeadlessTurbo = function() {
        window.__omniGeminiHeadlessTurbo = true;
        try {
            if (!document.getElementById('omni-gemini-headless-turbo-css')) {
                const style = document.createElement('style');
                style.id = 'omni-gemini-headless-turbo-css';
                style.textContent = [
                    '*, *::before, *::after { animation-duration: 0.001ms !important; animation-delay: 0ms !important; transition-duration: 0.001ms !important; transition-delay: 0ms !important; }',
                    'model-response, model-response-content, .model-response-text, img { display: none !important; }'
                ].join('\n');
                (document.head || document.documentElement).appendChild(style);
            }
        } catch(_) {}
    };
})();
""".trimIndent()

val GEMINI_WIRE_INTERCEPTOR_SCRIPT = """
(function() {
    if (window.__omniGeminiWireHooked) return;
    try {
        var host = (window.location && window.location.hostname) ? window.location.hostname.toLowerCase() : '';
        if (host && host.indexOf('gemini.google.com') === -1) return;
    } catch(_) {}
    window.__omniGeminiWireHooked = true;

    var turnCounter = 0;
    var activeTurnToken = 0;
    var streamsById = {};
    var streamsByTurn = {};
    var latestStreamId = 0;
    var hasBootSettled = false;

    function wireLog(tag, msg) {
        try {
            if (window.OmniGeminiAutomator && window.OmniGeminiAutomator.onLog) {
                window.OmniGeminiAutomator.onLog(tag, String(msg));
            } else if (window.OmniIdeBridge && window.OmniIdeBridge.log) {
                window.OmniIdeBridge.log(tag, String(msg));
            }
        } catch(_) {}
    }

    function isGeminiStreamUrl(url) {
        if (!url || typeof url !== 'string') return false;
        if (url.indexOf('StreamGenerateCurationDocument') !== -1) return false;
        return url.indexOf('BardFrontendService/StreamGenerate') !== -1 ||
               url.indexOf('BardFrontendService.StreamGenerate') !== -1 ||
               url.indexOf('rpcids=RxAFq') !== -1;
    }

    function isGeminiBootUrl(url) {
        if (!url || typeof url !== 'string') return false;
        return url.indexOf('/_/BardChatUi/data/batchexecute') !== -1;
    }

    function createStreamRecord(url, transport) {
        var id = ++latestStreamId;
        var turnTok = activeTurnToken;
        var rec = {
            id: id,
            turnToken: turnTok,
            url: url,
            transport: transport,
            active: true,
            done: false,
            error: null,
            httpStatus: 0,
            conversationId: '',
            responseId: '',
            chunkCount: 0,
            thoughts: '',
            output: '',
            rawSample: '',
            startedAt: Date.now(),
            firstThoughtAt: 0,
            firstOutputAt: 0,
            endedAt: 0
        };
        streamsById[id] = rec;
        if (turnTok > 0) {
            streamsByTurn[turnTok] = rec;
        }
        return rec;
    }

    function emitUpdate(rec) {
        try {
            window.dispatchEvent(new CustomEvent('__omniGeminiWireUpdate', { detail: rec }));
        } catch(_) {}
    }

    function joinStringParts(val) {
        if (typeof val === 'string') return val;
        if (!Array.isArray(val)) return '';
        var acc = [];
        for (var i = 0; i < val.length; i++) {
            if (typeof val[i] === 'string') acc.push(val[i]);
        }
        return acc.join('');
    }

    function looksLikeIdOrNoise(str) {
        if (!str || typeof str !== 'string') return true;
        var t = str.trim();
        if (!t) return true;
        if (t.indexOf('c_') === 0 || t.indexOf('r_') === 0 || t.indexOf('rc_') === 0) return true;
        if (t === 'en' || t === 'COMPLETE' || t === 'INCOMPLETE') return true;
        return false;
    }

    function applyTextUpdate(rec, candidateText, thoughtsText) {
        if (thoughtsText && typeof thoughtsText === 'string') {
            if (thoughtsText.length >= rec.thoughts.length || thoughtsText.indexOf(rec.thoughts) === 0) {
                if (thoughtsText.length > 0 && !rec.firstThoughtAt) rec.firstThoughtAt = Date.now();
                rec.thoughts = thoughtsText;
            }
        }
        if (candidateText && typeof candidateText === 'string') {
            if (candidateText.length > 0 && !rec.firstOutputAt) rec.firstOutputAt = Date.now();
            if (rec.output.length === 0 || candidateText.length >= rec.output.length || candidateText.indexOf(rec.output.slice(0, 24)) === 0) {
                rec.output = candidateText;
            } else if (rec.output.indexOf(candidateText) === -1) {
                rec.output = candidateText;
            }
        }
    }

    function extractGeminiPayload(rec, inner) {
        if (!Array.isArray(inner)) return;

        // Conversation / Response IDs at inner[1]
        if (Array.isArray(inner[1])) {
            if (typeof inner[1][0] === 'string' && inner[1][0]) rec.conversationId = inner[1][0];
            if (typeof inner[1][1] === 'string' && inner[1][1]) rec.responseId = inner[1][1];
        }

        // 1. Standard BardFrontendService/StreamGenerate candidate array at inner[4]
        if (Array.isArray(inner[4]) && inner[4].length > 0) {
            var cand = inner[4][0];
            if (Array.isArray(cand)) {
                var candText = joinStringParts(cand[1]);
                var candThoughts = joinStringParts(cand[37]);
                if (candText || candThoughts) {
                    applyTextUpdate(rec, candText, candThoughts);
                    return;
                }
            }
        }

        // 2. Alternate _.Sy (FqbQQc) proto layout at inner[7] (field 8 = _.Pdc[])
        if (Array.isArray(inner[7]) && inner[7].length > 0) {
            var pdc = inner[7][0];
            if (Array.isArray(pdc)) {
                var pText = joinStringParts(pdc[1]) || (typeof pdc[10] === 'string' ? pdc[10] : '');
                var pThoughts = joinStringParts(pdc[36]) || joinStringParts(pdc[37]);
                if ((pText && !looksLikeIdOrNoise(pText)) || pThoughts) {
                    applyTextUpdate(rec, looksLikeIdOrNoise(pText) ? '' : pText, pThoughts);
                    return;
                }
            }
        }
    }

    function handleEnvelopeArray(rec, arr) {
        if (!Array.isArray(arr)) return;
        for (var i = 0; i < arr.length; i++) {
            var item = arr[i];
            if (!Array.isArray(item)) continue;
            var tag = item[0];
            if (tag === 'wrb.fr') {
                var payloadStr = item[2];
                if (typeof payloadStr === 'string' && payloadStr.length > 0) {
                    try {
                        var inner = JSON.parse(payloadStr);
                        rec.chunkCount++;
                        if (rec.chunkCount === 1) {
                            rec.rawSample = payloadStr.slice(0, 360);
                        }
                        extractGeminiPayload(rec, inner);
                        if (rec.chunkCount === 1 || rec.chunkCount % 15 === 0) {
                            wireLog('GEMINI_WIRE_CHUNK', 'Stream #' + rec.id + ' chunk #' + rec.chunkCount + ' | out=' + rec.output.length + 'c, thoughts=' + rec.thoughts.length + 'c');
                        }
                        emitUpdate(rec);
                    } catch(e) {
                        wireLog('GEMINI_WIRE_PARSE_ERR', 'Inner JSON parse failed: ' + (e ? e.message : 'unknown'));
                    }
                } else if (Array.isArray(item[5]) && item[5].length > 0 && !rec.output) {
                    var errCode = item[5][0];
                    if (errCode && errCode !== 0) {
                        rec.error = 'Gemini RPC Error Code: ' + errCode;
                        emitUpdate(rec);
                    }
                }
            } else if (tag === 'er' && item[5] && !rec.output) {
                rec.error = 'Gemini Transport Error: ' + JSON.stringify(item[5]);
                emitUpdate(rec);
            }
        }
    }

    // Mirrors Google Closure Vac(a, b) length-prefixed chunk slicer from m_vLefie
    function createVacParser(rec) {
        var buffer = '';
        var cursor = 0;
        var strippedHeader = false;

        return {
            feed: function(fullText, isAppend) {
                if (isAppend) {
                    buffer += fullText;
                } else {
                    buffer = fullText;
                }

                if (!strippedHeader) {
                    var xssDouble = buffer.indexOf(")]}'\n\n");
                    if (xssDouble >= 0) {
                        cursor = xssDouble + 6;
                        strippedHeader = true;
                    } else {
                        var xssSingle = buffer.indexOf(")]}'\n");
                        if (xssSingle >= 0) {
                            cursor = xssSingle + 5;
                            strippedHeader = true;
                        } else if (buffer.length > 10 && buffer[0] !== ')') {
                            strippedHeader = true;
                        } else {
                            return;
                        }
                    }
                }

                while (cursor < buffer.length) {
                    var nlIdx = buffer.indexOf('\n', cursor);
                    if (nlIdx === -1) break;
                    if (nlIdx === cursor) {
                        cursor++;
                        continue;
                    }
                    var lenStr = buffer.substring(cursor, nlIdx).trim();
                    if (!lenStr) {
                        cursor = nlIdx + 1;
                        continue;
                    }
                    var len = Number(lenStr);
                    if (isNaN(len) || len <= 0) {
                        if (lenStr[0] === '[') {
                            try {
                                var direct = JSON.parse(lenStr);
                                cursor = nlIdx + 1;
                                handleEnvelopeArray(rec, direct);
                                continue;
                            } catch(_) {}
                        }
                        break;
                    }

                    if (nlIdx + len > buffer.length) break;

                    var chunkSlice = buffer.substr(nlIdx, len);
                    try {
                        var parsed = JSON.parse(chunkSlice);
                        cursor = nlIdx + len;
                        handleEnvelopeArray(rec, parsed);
                    } catch(_) {
                        // Fallback if length did not include the leading newline
                        if (nlIdx + 1 + len <= buffer.length) {
                            try {
                                var altSlice = buffer.substr(nlIdx + 1, len);
                                var altParsed = JSON.parse(altSlice);
                                cursor = nlIdx + 1 + len;
                                handleEnvelopeArray(rec, altParsed);
                                continue;
                            } catch(__) {}
                        }
                        break;
                    }
                }
            }
        };
    }

    function finalizeRecord(rec, httpStatus, errMsg) {
        if (!rec || rec.done) return;
        rec.active = false;
        rec.done = true;
        rec.endedAt = Date.now();
        if (httpStatus) rec.httpStatus = httpStatus;
        if (errMsg && !rec.error) rec.error = errMsg;
        if (rec.httpStatus === 429 && !rec.error) {
            rec.error = 'Rate Limit Reached (429)';
        } else if (rec.httpStatus >= 400 && !rec.error) {
            rec.error = 'HTTP Error ' + rec.httpStatus;
        }
        wireLog('GEMINI_WIRE_END', 'Stream #' + rec.id + ' (' + rec.transport + ') ended | http=' + rec.httpStatus + ' chunks=' + rec.chunkCount + ' out=' + rec.output.length + 'c thoughts=' + rec.thoughts.length + 'c err=' + rec.error + ' | ms=' + (rec.endedAt - rec.startedAt));
        emitUpdate(rec);
    }

    // 1. Hook XMLHttpRequest (Google Closure Aac/Wac transport)
    if (typeof XMLHttpRequest !== 'undefined' && !XMLHttpRequest.prototype.__omniGeminiWireWrapped) {
        var origOpen = XMLHttpRequest.prototype.open;
        var origSend = XMLHttpRequest.prototype.send;
        var origAbort = XMLHttpRequest.prototype.abort;

        XMLHttpRequest.prototype.open = function(method, url) {
            this.__omniGeminiUrl = String(url || '');
            this.__omniGeminiRec = null;
            this.__omniGeminiDrain = null;
            return origOpen.apply(this, arguments);
        };

        XMLHttpRequest.prototype.abort = function() {
            try {
                if (this.__omniGeminiDrain) this.__omniGeminiDrain();
                var rec = this.__omniGeminiRec;
                if (rec && !rec.done) {
                    if (rec.chunkCount > 0 || rec.output.length > 0 || rec.thoughts.length > 0 || rec.error) {
                        finalizeRecord(rec, this.status || rec.httpStatus || 200, null);
                    } else {
                        finalizeRecord(rec, this.status || 0, 'XHR aborted');
                    }
                }
            } catch(_) {}
            return origAbort.apply(this, arguments);
        };

        XMLHttpRequest.prototype.send = function() {
            var url = this.__omniGeminiUrl || '';
            if (isGeminiBootUrl(url)) {
                this.addEventListener('loadend', function() { hasBootSettled = true; });
            }
            if (isGeminiStreamUrl(url)) {
                var xhr = this;
                var rec = createStreamRecord(url, 'xhr');
                var parser = createVacParser(rec);
                wireLog('GEMINI_WIRE_START', 'Stream #' + rec.id + ' [XHR] turn=' + rec.turnToken + ' url=' + url);

                function drainXhr() {
                    try {
                        if (xhr.status && !rec.httpStatus) rec.httpStatus = xhr.status;
                        var txt = xhr.responseText || '';
                        if (txt.length > 0) {
                            parser.feed(txt, false);
                        }
                    } catch(err) {
                        wireLog('GEMINI_WIRE_XHR_ERR', err ? err.message : 'drainXhr error');
                    }
                }

                xhr.__omniGeminiRec = rec;
                xhr.__omniGeminiDrain = drainXhr;

                xhr.addEventListener('progress', drainXhr);
                xhr.addEventListener('readystatechange', function() {
                    if (xhr.readyState >= 3) drainXhr();
                    if (xhr.readyState === 4 && !rec.done) {
                        finalizeRecord(rec, xhr.status || 200, null);
                    }
                });
                xhr.addEventListener('load', function() {
                    drainXhr();
                    if (!rec.done) finalizeRecord(rec, xhr.status || 200, null);
                });
                xhr.addEventListener('loadend', function() {
                    drainXhr();
                    if (!rec.done) {
                        var err = (xhr.status === 0 && !rec.output && !rec.thoughts) ? 'XHR aborted or network error' : null;
                        finalizeRecord(rec, xhr.status || 200, err);
                    }
                });
                xhr.addEventListener('error', function() {
                    finalizeRecord(rec, xhr.status || 0, 'XHR network error');
                });
            }
            return origSend.apply(this, arguments);
        };
        XMLHttpRequest.prototype.__omniGeminiWireWrapped = true;
    }

    // 2. Hook window.fetch (Fallback/Hybrid transport)
    if (typeof window.fetch === 'function' && !window.fetch.__omniGeminiWireWrapped) {
        var origFetch = window.fetch;
        window.fetch = function(input, init) {
            var url = '';
            try {
                url = (typeof input === 'string') ? input : (input && input.url ? input.url : String(input || ''));
            } catch(_) {}

            if (isGeminiBootUrl(url)) {
                hasBootSettled = true;
            }
            if (!isGeminiStreamUrl(url)) {
                return origFetch.apply(this, arguments);
            }

            var rec = createStreamRecord(url, 'fetch');
            var parser = createVacParser(rec);
            wireLog('GEMINI_WIRE_START', 'Stream #' + rec.id + ' [FETCH] turn=' + rec.turnToken + ' url=' + url);

            return origFetch.apply(this, arguments).then(function(res) {
                rec.httpStatus = res.status;
                try {
                    var clone = res.clone();
                    if (clone.body && clone.body.getReader) {
                        var reader = clone.body.getReader();
                        var decoder = new TextDecoder('utf-8');
                        (function pump() {
                            reader.read().then(function(step) {
                                if (step.done) {
                                    var tail = decoder.decode();
                                    if (tail) parser.feed(tail, true);
                                    finalizeRecord(rec, res.status, null);
                                    return;
                                }
                                if (step.value) {
                                    parser.feed(decoder.decode(step.value, { stream: true }), true);
                                }
                                pump();
                            }).catch(function(err) {
                                finalizeRecord(rec, res.status, err ? err.message : 'Stream read error');
                            });
                        })();
                    } else {
                        clone.text().then(function(txt) {
                            parser.feed(txt, false);
                            finalizeRecord(rec, res.status, null);
                        }).catch(function(err) {
                            finalizeRecord(rec, res.status, err ? err.message : 'Text read error');
                        });
                    }
                } catch(e) {
                    finalizeRecord(rec, res.status, e ? e.message : 'Clone error');
                }
                return res;
            }, function(err) {
                finalizeRecord(rec, 0, err ? err.message : 'Fetch error');
                throw err;
            });
        };
        window.fetch.__omniGeminiWireWrapped = true;
    }

    wireLog('GEMINI_WIRE_INIT', 'Gemini Wire hook armed on ' + (window.location ? window.location.href : 'unknown'));

    window.__omniGeminiWire = {
        hasHooked: true,
        isSessionWarm: function() {
            return {
                ready: (document.readyState !== 'loading' && (hasBootSettled || document.querySelectorAll('rich-textarea .ql-editor, [aria-label="Enter a prompt for Gemini"]').length > 0))
            };
        },
        beginTurn: function() {
            activeTurnToken = ++turnCounter;
            streamsByTurn[activeTurnToken] = null;
            return activeTurnToken;
        },
        getTurn: function(token) {
            return token ? (streamsByTurn[token] || null) : null;
        },
        getLatest: function() {
            return latestStreamId > 0 ? (streamsById[latestStreamId] || null) : null;
        }
    };
})();
""".trimIndent()