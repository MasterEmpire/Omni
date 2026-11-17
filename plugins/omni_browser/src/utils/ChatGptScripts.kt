package com.omni.plugin.browser.utils

val CHATGPT_HEADLESS_TURBO_SCRIPT = """
(function() {
    if (window.__omniGptHeadlessTurboHooked) return;
    window.__omniGptHeadlessTurboHooked = true;

    try {
        Object.defineProperty(document, 'hidden', { get: () => false, configurable: true });
        Object.defineProperty(document, 'visibilityState', { get: () => 'visible', configurable: true });
        Object.defineProperty(document, 'webkitVisibilityState', { get: () => 'visible', configurable: true });
        Object.defineProperty(document, 'webkitHidden', { get: () => false, configurable: true });
        document.hasFocus = function() { return true; };
    } catch(_) {}

    if (!window.__omniGptVisibilityHooked) {
        window.__omniGptVisibilityHooked = true;
        const origAddEvent = EventTarget.prototype.addEventListener;
        EventTarget.prototype.addEventListener = function(type, listener, options) {
            if (type === 'visibilitychange' || type === 'webkitvisibilitychange' || type === 'freeze') {
                return;
            }
            return origAddEvent.call(this, type, listener, options);
        };
    }

    if (!window.__omniGptRafUnchoked) {
        window.__omniGptRafUnchoked = true;
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

            const timeoutId = setTimeout(function() {
                if (!executed) {
                    executed = true;
                    pendingCallbacks.delete(id);
                    try { if (nativeId !== null) origCancelRaf.call(window, nativeId); } catch(_) {}
                    cb(performance.now());
                }
            }, 18);

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

    window.__omniGptEnableHeadlessTurbo = function() {
        window.__omniGptHeadlessTurbo = true;
        try {
            if (!document.getElementById('omni-gpt-headless-turbo-css')) {
                const style = document.createElement('style');
                style.id = 'omni-gpt-headless-turbo-css';
                style.textContent = [
                    '*, *::before, *::after { animation-duration: 0.001ms !important; animation-delay: 0ms !important; transition-duration: 0.001ms !important; transition-delay: 0ms !important; }',
                    '[data-message-author-role="assistant"] .markdown { content-visibility: hidden !important; contain: strict !important; }'
                ].join('\n');
                (document.head || document.documentElement).appendChild(style);
            }
        } catch(_) {}
    };
})();
""".trimIndent()

val CHATGPT_WIRE_INTERCEPTOR_SCRIPT = """
(function() {
    if (window.__omniGptWireHooked) return;
    try {
        var host = (window.location && window.location.hostname) ? window.location.hostname.toLowerCase() : '';
        if (host && host.indexOf('chatgpt.com') === -1 && host.indexOf('chatgpt-staging.com') === -1) return;
    } catch(_) {}
    window.__omniGptWireHooked = true;

    var turnCounter = 0;
    var activeTurnToken = 0;
    var streamsById = {};
    var streamsByTurn = {};
    var latestStreamId = 0;
    var hasBootSettled = false;

    function wireLog(tag, msg) {
        try {
            if (window.OmniGptAutomator && window.OmniGptAutomator.onLog) {
                window.OmniGptAutomator.onLog(tag, String(msg));
            } else if (window.OmniIdeBridge && window.OmniIdeBridge.log) {
                window.OmniIdeBridge.log(tag, String(msg));
            }
        } catch(_) {}
    }

    function isConversationStreamUrl(url) {
        if (!url || typeof url !== 'string') return false;
        return url.indexOf('/backend-api/f/conversation') !== -1 ||
               url.indexOf('/backend-api/conversation') !== -1;
    }

    function isCelsiusWsUrl(url) {
        if (!url || typeof url !== 'string') return false;
        return url.indexOf('chatgpt.com') !== -1 ||
               url.indexOf('openai.com') !== -1 ||
               url.indexOf('celsius') !== -1;
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
            handedOffToWs: false,
            wsDone: false,
            error: null,
            httpStatus: 0,
            conversationId: '',
            chunkCount: 0,
            thoughts: '',
            output: '',
            thoughtsOrder: [],
            thoughtsMap: {},
            outputOrder: [],
            outputMap: {},
            deltaDecoder: null,
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
            window.dispatchEvent(new CustomEvent('__omniGptWireUpdate', { detail: rec }));
        } catch(_) {}
    }

    function rebuildTexts(rec) {
        if (rec.thoughtsOrder.length > 0) {
            var tAcc = [];
            for (var i = 0; i < rec.thoughtsOrder.length; i++) {
                var val = rec.thoughtsMap[rec.thoughtsOrder[i]];
                if (val) tAcc.push(val);
            }
            var joinedT = tAcc.join('\n\n');
            if (joinedT.length > 0 && !rec.firstThoughtAt) rec.firstThoughtAt = Date.now();
            rec.thoughts = joinedT;
        }
        if (rec.outputOrder.length > 0) {
            var oAcc = [];
            for (var j = 0; j < rec.outputOrder.length; j++) {
                var oVal = rec.outputMap[rec.outputOrder[j]];
                if (oVal) oAcc.push(oVal);
            }
            var joinedO = oAcc.join('\n\n');
            if (joinedO.length > 0 && !rec.firstOutputAt) rec.firstOutputAt = Date.now();
            rec.output = joinedO;
        }
    }

    // Mirrors ChatGPT bundle 418851.js class u (v1 JSON-Pointer delta decoder)
    var DELTA_KEY_MAP = [['channel', 'c'], ['path', 'p'], ['op', 'o'], ['value', 'v']];
    var INDEX_RE = /^(?:0|[1-9]\d*)$/;

    function deepCloneVal(v) {
        if (v === null || typeof v !== 'object') return v;
        if (Array.isArray(v)) {
            var arr = new Array(v.length);
            for (var i = 0; i < v.length; i++) arr[i] = deepCloneVal(v[i]);
            return arr;
        }
        var out = {};
        var keys = Object.keys(v);
        for (var k = 0; k < keys.length; k++) {
            out[keys[k]] = deepCloneVal(v[keys[k]]);
        }
        return out;
    }

    function isSafeSegment(seg) {
        return seg !== '__proto__' && seg !== 'constructor' && seg !== 'prototype';
    }

    function parsePointer(pathStr) {
        var segs = ['__root'];
        if (!pathStr || pathStr === '') return segs;
        var p = pathStr[0] === '/' ? pathStr.substring(1) : pathStr;
        var parts = p.split('/');
        for (var i = 0; i < parts.length; i++) {
            var raw = parts[i];
            var seg = INDEX_RE.test(raw) ? parseInt(raw, 10) : raw.replace(/~1/g, '/').replace(/~0/g, '~');
            if (!isSafeSegment(seg)) throw new Error('Unsafe delta path segment');
            segs.push(seg);
        }
        return segs;
    }

    function isPlainObject(v) {
        return typeof v === 'object' && v !== null && !Array.isArray(v);
    }

    function applyJsonDeltaOp(rootObj, opObj) {
        var segs = parsePointer(opObj.path || '');
        var leaf = segs.pop();
        if (leaf === undefined) return;
        var target = rootObj;
        for (var i = 0; i < segs.length; i++) {
            var key = segs[i];
            if (!Object.prototype.hasOwnProperty.call(target, key) || target[key] === undefined) {
                var nextKey = (i + 1 < segs.length) ? segs[i + 1] : leaf;
                target[key] = (typeof nextKey === 'number') ? [] : {};
            }
            target = target[key];
        }
        var cur = Object.prototype.hasOwnProperty.call(target, leaf) ? target[leaf] : undefined;
        switch (opObj.op) {
            case 'patch': {
                var sub = { __root: cur };
                if (Array.isArray(opObj.value)) {
                    for (var j = 0; j < opObj.value.length; j++) {
                        applyJsonDeltaOp(sub, opObj.value[j]);
                    }
                }
                target[leaf] = sub.__root;
                break;
            }
            case 'add':
                if (Array.isArray(target) && typeof leaf === 'number') {
                    target.splice(leaf, 0, deepCloneVal(opObj.value));
                } else {
                    target[leaf] = deepCloneVal(opObj.value);
                }
                break;
            case 'remove':
                if (Array.isArray(target) && typeof leaf === 'number') {
                    target.splice(leaf, 1);
                } else {
                    delete target[leaf];
                }
                break;
            case 'replace':
                target[leaf] = deepCloneVal(opObj.value);
                break;
            case 'append':
                if (typeof cur === 'string') {
                    target[leaf] = cur + String(opObj.value == null ? '' : opObj.value);
                } else if (Array.isArray(cur)) {
                    var toPush = Array.isArray(opObj.value) ? opObj.value : [opObj.value];
                    for (var k = 0; k < toPush.length; k++) cur.push(deepCloneVal(toPush[k]));
                } else if (isPlainObject(cur) && isPlainObject(opObj.value)) {
                    var eKeys = Object.keys(opObj.value);
                    for (var m = 0; m < eKeys.length; m++) {
                        var ek = eKeys[m];
                        if (isSafeSegment(ek)) cur[ek] = deepCloneVal(opObj.value[ek]);
                    }
                } else {
                    target[leaf] = deepCloneVal(opObj.value);
                }
                break;
            case 'truncate':
                if (cur !== undefined) {
                    if (typeof cur === 'string') {
                        target[leaf] = cur.substring(0, opObj.value);
                    } else if (Array.isArray(cur)) {
                        cur.length = opObj.value;
                    }
                }
                break;
        }
    }

    function normalizePatchItem(item) {
        var out = {};
        var keys = Object.keys(item);
        for (var i = 0; i < keys.length; i++) out[keys[i]] = item[keys[i]];
        for (var j = 0; j < DELTA_KEY_MAP.length; j++) {
            var fullK = DELTA_KEY_MAP[j][0];
            var shortK = DELTA_KEY_MAP[j][1];
            if (Object.prototype.hasOwnProperty.call(item, shortK)) {
                out[fullK] = item[shortK];
                delete out[shortK];
            }
        }
        if (out.op === 'patch' && Array.isArray(out.value)) {
            out.value = out.value.map(normalizePatchItem);
        }
        return out;
    }

    function createDeltaDecoder() {
        var valueByChannel = {};
        var prevDelta = { channel: 0, op: 'add', path: '', value: undefined };

        return {
            applyDelta: function(rawDelta) {
                if (!rawDelta || typeof rawDelta !== 'object') return null;
                var inherited = {};
                var rKeys = Object.keys(rawDelta);
                for (var i = 0; i < rKeys.length; i++) inherited[rKeys[i]] = rawDelta[rKeys[i]];
                for (var j = 0; j < DELTA_KEY_MAP.length; j++) {
                    var fullK = DELTA_KEY_MAP[j][0];
                    var shortK = DELTA_KEY_MAP[j][1];
                    if (fullK !== 'value' && !Object.prototype.hasOwnProperty.call(rawDelta, shortK) && !Object.prototype.hasOwnProperty.call(rawDelta, fullK)) {
                        inherited[shortK] = prevDelta[fullK];
                    }
                }
                var decoded = normalizePatchItem(inherited);
                var ch = (typeof decoded.channel === 'number' && decoded.channel >= 0) ? decoded.channel : 0;
                var wrapper = { __root: valueByChannel[ch] };
                applyJsonDeltaOp(wrapper, decoded);
                valueByChannel[ch] = wrapper.__root;
                prevDelta = decoded;
                return wrapper.__root;
            }
        };
    }

    function extractContentText(contentObj) {
        if (!contentObj || typeof contentObj !== 'object') return '';
        var cType = contentObj.content_type || '';
        if (cType === 'thoughts' && Array.isArray(contentObj.thoughts)) {
            var tParts = [];
            for (var i = 0; i < contentObj.thoughts.length; i++) {
                var th = contentObj.thoughts[i];
                if (th && typeof th === 'object') {
                    var piece = th.content || th.summary || '';
                    if (piece) tParts.push(piece);
                } else if (typeof th === 'string' && th) {
                    tParts.push(th);
                }
            }
            return tParts.join('\n\n');
        }
        if (Array.isArray(contentObj.parts)) {
            var pAcc = [];
            for (var j = 0; j < contentObj.parts.length; j++) {
                var part = contentObj.parts[j];
                if (typeof part === 'string') {
                    pAcc.push(part);
                } else if (part && typeof part === 'object') {
                    if (typeof part.text === 'string') pAcc.push(part.text);
                    else if (typeof part.content === 'string') pAcc.push(part.content);
                }
            }
            return pAcc.join('');
        }
        if (typeof contentObj.text === 'string') return contentObj.text;
        return '';
    }

    function ingestMessageObject(rec, msg, convId) {
        if (!msg || typeof msg !== 'object') return;
        if (convId && typeof convId === 'string') rec.conversationId = convId;
        if (msg.conversation_id && typeof msg.conversation_id === 'string') rec.conversationId = msg.conversation_id;

        var role = msg.author && msg.author.role ? msg.author.role : '';
        if (role === 'user' || role === 'system') return;

        var msgId = msg.id || ('msg_' + (msg.channel || 'default'));
        var content = msg.content;
        if (!content || typeof content !== 'object') return;

        var cType = content.content_type || 'text';
        var txt = extractContentText(content);
        if (!txt) return;

        var isThought = (cType === 'thoughts') || (msg.channel === 'analysis') || (role === 'tool' && cType === 'thoughts');
        if (isThought) {
            if (!Object.prototype.hasOwnProperty.call(rec.thoughtsMap, msgId)) {
                rec.thoughtsOrder.push(msgId);
            }
            rec.thoughtsMap[msgId] = txt;
        } else if (role === 'assistant' || !role) {
            if (cType === 'text' || cType === 'multimodal_text' || cType === 'code' || cType === 'tether_browsing_display') {
                if (!Object.prototype.hasOwnProperty.call(rec.outputMap, msgId)) {
                    rec.outputOrder.push(msgId);
                }
                rec.outputMap[msgId] = txt;
            }
        }
        rebuildTexts(rec);
    }

    function processDecodedPayload(rec, dataObj) {
        if (!dataObj || typeof dataObj !== 'object') return;
        rec.chunkCount++;

        if (typeof dataObj.conversation_id === 'string' && dataObj.conversation_id) {
            rec.conversationId = dataObj.conversation_id;
        }

        if (dataObj.type === 'stream_handoff') {
            rec.handedOffToWs = true;
            wireLog('GPT_WIRE_HANDOFF', 'Stream #' + rec.id + ' handed off to Celsius WebSocket');
            return;
        }

        if (dataObj.error) {
            rec.error = (typeof dataObj.error === 'string') ? dataObj.error : JSON.stringify(dataObj.error);
            emitUpdate(rec);
            return;
        }

        if (dataObj.type === 'message_stream_complete') {
            finalizeRecord(rec, rec.httpStatus || 200, null, true);
            return;
        }

        var msg = dataObj.message || (dataObj.v && dataObj.v.message) || dataObj.input_message;
        if (msg) {
            ingestMessageObject(rec, msg, dataObj.conversation_id);
        } else if (dataObj.v && typeof dataObj.v === 'object' && dataObj.v.content) {
            ingestMessageObject(rec, dataObj.v, dataObj.conversation_id);
        }

        if (rec.chunkCount === 1 || rec.chunkCount % 25 === 0) {
            wireLog('GPT_WIRE_CHUNK', 'Stream #' + rec.id + ' chunk #' + rec.chunkCount + ' | out=' + rec.output.length + 'c, thoughts=' + rec.thoughts.length + 'c');
        }
        emitUpdate(rec);
    }

    // Mirrors tY from 807489.3f6f62fc4e.js
    function parseSSEBlock(block) {
        if (!block) return null;
        var eventName;
        var lines = block.split(/\r?\n/);
        var dataLines = [];
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            if (line.indexOf('event:') === 0) {
                eventName = line.slice(6).trim();
            } else if (line.indexOf('data:') === 0) {
                dataLines.push(line.slice(5).trim());
            }
        }
        if (dataLines.length === 0) return null;
        var joined = dataLines.join('\n');
        if (joined === '[DONE]') return { event: eventName, done: true, data: '[DONE]' };
        try {
            return { event: eventName, done: false, data: JSON.parse(joined) };
        } catch(_) {
            return { event: eventName, done: false, raw: joined };
        }
    }

    function handleParsedSSE(rec, parsed) {
        if (!parsed) return;
        if (parsed.done) {
            finalizeRecord(rec, rec.httpStatus || 200, null, true);
            return;
        }
        if (parsed.event === 'heartbeat' || parsed.event === 'ping') return;

        if (parsed.event === 'delta_encoding') {
            var enc = String(parsed.data || '');
            if (enc === 'v1' || !rec.deltaDecoder) {
                rec.deltaDecoder = createDeltaDecoder();
            }
            return;
        }

        if (parsed.event === 'delta') {
            if (!rec.deltaDecoder) rec.deltaDecoder = createDeltaDecoder();
            var applied = rec.deltaDecoder.applyDelta(parsed.data);
            if (applied) {
                processDecodedPayload(rec, applied);
            }
            return;
        }

        if (parsed.data && typeof parsed.data === 'object') {
            processDecodedPayload(rec, parsed.data);
        }
    }

    function finalizeRecord(rec, httpStatus, errMsg, forceComplete) {
        if (!rec || rec.done) return;
        if (httpStatus) rec.httpStatus = httpStatus;
        if (errMsg && !rec.error) rec.error = errMsg;
        if (rec.handedOffToWs && !forceComplete && !rec.error && rec.httpStatus < 400) {
            return;
        }
        rec.active = false;
        rec.done = true;
        rec.endedAt = Date.now();
        if (rec.httpStatus === 429 && !rec.error) {
            rec.error = 'Rate Limit Reached (429)';
        } else if (rec.httpStatus >= 400 && !rec.error) {
            rec.error = 'HTTP Error ' + rec.httpStatus;
        }
        wireLog('GPT_WIRE_END', 'Stream #' + rec.id + ' (' + rec.transport + ') ended | http=' + rec.httpStatus + ' chunks=' + rec.chunkCount + ' out=' + rec.output.length + 'c thoughts=' + rec.thoughts.length + 'c err=' + rec.error + ' | ms=' + (rec.endedAt - rec.startedAt));
        emitUpdate(rec);
    }

    // 1. Hook window.fetch (HTTP SSE Stream)
    if (typeof window.fetch === 'function' && !window.fetch.__omniGptWireWrapped) {
        var origFetch = window.fetch;
        window.fetch = function(input, init) {
            var url = '';
            try {
                url = (typeof input === 'string') ? input : (input && input.url ? input.url : String(input || ''));
            } catch(_) {}

            if (url.indexOf('/backend-api/models') !== -1 || url.indexOf('/backend-api/me') !== -1) {
                hasBootSettled = true;
            }

            if (!isConversationStreamUrl(url) || url.indexOf('/init') !== -1) {
                return origFetch.apply(this, arguments);
            }

            var rec = createStreamRecord(url, 'fetch');
            wireLog('GPT_WIRE_START', 'Stream #' + rec.id + ' [FETCH] turn=' + rec.turnToken + ' url=' + url);

            return origFetch.apply(this, arguments).then(function(res) {
                rec.httpStatus = res.status;
                try {
                    var clone = res.clone();
                    if (clone.body && clone.body.getReader) {
                        var reader = clone.body.getReader();
                        var decoder = new TextDecoder('utf-8');
                        var boundaryRe = /\r\n\r\n|\n\n/g;
                        var buffer = '';

                        (function pump() {
                            reader.read().then(function(step) {
                                if (step.done) {
                                    var tail = buffer + decoder.decode();
                                    if (tail.trim()) {
                                        handleParsedSSE(rec, parseSSEBlock(tail));
                                    }
                                    finalizeRecord(rec, res.status, null, false);
                                    return;
                                }
                                if (step.value) {
                                    buffer += decoder.decode(step.value, { stream: true });
                                    var cursor = 0;
                                    var match;
                                    while ((match = boundaryRe.exec(buffer)) !== null) {
                                        var block = buffer.slice(cursor, match.index);
                                        cursor = boundaryRe.lastIndex;
                                        handleParsedSSE(rec, parseSSEBlock(block));
                                    }
                                    buffer = buffer.slice(cursor);
                                    boundaryRe.lastIndex = Math.max(0, buffer.length - 3);
                                }
                                pump();
                            }).catch(function(err) {
                                finalizeRecord(rec, res.status, err ? err.message : 'Stream read error', true);
                            });
                        })();
                    } else {
                        clone.text().then(function(txt) {
                            var blocks = txt.split(/\r\n\r\n|\n\n/);
                            for (var i = 0; i < blocks.length; i++) {
                                handleParsedSSE(rec, parseSSEBlock(blocks[i]));
                            }
                            finalizeRecord(rec, res.status, null, false);
                        }).catch(function(err) {
                            finalizeRecord(rec, res.status, err ? err.message : 'Response text error', true);
                        });
                    }
                } catch(e) {
                    finalizeRecord(rec, res.status, e ? e.message : 'Clone error', true);
                }
                return res;
            }, function(err) {
                finalizeRecord(rec, 0, err ? err.message : 'Fetch network error', true);
                throw err;
            });
        };
        window.fetch.__omniGptWireWrapped = true;
    }

    // 2. Hook window.WebSocket (Celsius WS Fallback / Stream Handoff)
    if (typeof window.WebSocket === 'function' && !window.WebSocket.__omniGptWireWrapped) {
        var OrigWS = window.WebSocket;
        var wsActiveRecBySocket = new WeakMap();

        function handleWsFrame(ws, wsUrl, rawData) {
            if (typeof rawData !== 'string') return;
            var msg;
            try { msg = JSON.parse(rawData); } catch(_) { return; }
            if (!msg || typeof msg !== 'object') return;

            if (msg.type === 'stream-item' && typeof msg.encoded_item === 'string') {
                var rec = streamsByTurn[activeTurnToken] || wsActiveRecBySocket.get(ws);
                if (!rec || rec.done) {
                    rec = createStreamRecord(wsUrl, 'ws');
                    wsActiveRecBySocket.set(ws, rec);
                    wireLog('GPT_WIRE_START', 'Stream #' + rec.id + ' [WS] turn=' + rec.turnToken + ' url=' + wsUrl);
                } else {
                    wsActiveRecBySocket.set(ws, rec);
                }

                var trimmed = msg.encoded_item.trim();
                if (trimmed === 'data: [DONE]' || trimmed === '[DONE]') {
                    rec.wsDone = true;
                    finalizeRecord(rec, rec.httpStatus || 200, null, true);
                    return;
                }
                var parsed = parseSSEBlock(msg.encoded_item);
                if (parsed) handleParsedSSE(rec, parsed);
            } else if (msg.payload && msg.payload.type === 'done') {
                var doneRec = streamsByTurn[activeTurnToken] || wsActiveRecBySocket.get(ws);
                if (doneRec && !doneRec.done) {
                    doneRec.wsDone = true;
                    finalizeRecord(doneRec, doneRec.httpStatus || 200, null, true);
                }
            } else if (msg.payload && msg.payload.error) {
                var errRec = streamsByTurn[activeTurnToken] || wsActiveRecBySocket.get(ws);
                if (errRec && !errRec.done) {
                    finalizeRecord(errRec, 500, String(msg.payload.error), true);
                }
            }
        }

        function WrappedWebSocket(url, protocols) {
            var ws = (protocols !== undefined) ? new OrigWS(url, protocols) : new OrigWS(url);
            try {
                var wsUrl = String(url || '');
                if (isCelsiusWsUrl(wsUrl)) {
                    ws.addEventListener('message', function(ev) {
                        handleWsFrame(ws, wsUrl, ev.data);
                    });
                    ws.addEventListener('close', function() {
                        var rec = wsActiveRecBySocket.get(ws);
                        if (rec && rec.handedOffToWs && !rec.done) {
                            finalizeRecord(rec, rec.httpStatus || 200, null, true);
                        }
                    });
                }
            } catch(_) {}
            return ws;
        }
        WrappedWebSocket.prototype = OrigWS.prototype;
        WrappedWebSocket.CONNECTING = OrigWS.CONNECTING;
        WrappedWebSocket.OPEN = OrigWS.OPEN;
        WrappedWebSocket.CLOSING = OrigWS.CLOSING;
        WrappedWebSocket.CLOSED = OrigWS.CLOSED;
        WrappedWebSocket.__omniGptWireWrapped = true;
        window.WebSocket = WrappedWebSocket;
    }

    wireLog('GPT_WIRE_INIT', 'ChatGPT Wire hook armed on ' + (window.location ? window.location.href : 'unknown'));

    window.__omniGptWire = {
        hasHooked: true,
        isSessionWarm: function() {
            return {
                ready: (document.readyState !== 'loading' && (hasBootSettled || document.querySelectorAll('div.ProseMirror[data-composer-markdown], div[aria-label="Ask ChatGPT"]').length > 0))
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