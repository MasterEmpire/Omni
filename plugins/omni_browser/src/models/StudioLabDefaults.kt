package com.omni.plugin.browser.models

/**
 * Client-Side SDK Runtime & Starter Playbook Template for Omni Studio Lab.
 * Formatted with string concatenation to completely avoid Conduit IDE ${} variable expansion.
 */
val OMNI_STUDIO_LAB_CLIENT_SCRIPT = """
(function() {
    if (window.Omni) return;
    const callbacks = new Map();

    window.__omniRpcResolve = function(id, data) {
        const cb = callbacks.get(id);
        if (cb) {
            callbacks.delete(id);
            cb.resolve(data);
        }
    };

    window.__omniRpcReject = function(id, errorMsg) {
        const cb = callbacks.get(id);
        if (cb) {
            callbacks.delete(id);
            cb.reject(new Error(errorMsg));
        }
    };

    window.__omniRpcProgress = function(id, data) {
        const cb = callbacks.get(id);
        if (cb && typeof cb.onProgress === 'function') {
            if (data && data.__isDelta) {
                if (data.dt) cb.thoughtsAcc += data.dt;
                if (data.do) cb.outputAcc += data.do;
                cb.onProgress({
                    thoughts: cb.thoughtsAcc,
                    output: cb.outputAcc,
                    deltaThoughts: data.dt || '',
                    deltaOutput: data.do || '',
                    isFirst: !!data.isFirst
                });
            } else {
                cb.onProgress(data);
            }
        }
    };

    window.__omniRpcInput = function(id, prompt) {
        const cb = callbacks.get(id);
        if (cb && typeof cb.onInput === 'function') {
            cb.onInput(prompt, function(val) {
                if (window.__OmniStudioLabBridge && window.__OmniStudioLabBridge.pythonProvideInput) {
                    window.__OmniStudioLabBridge.pythonProvideInput(id, String(val || ''));
                }
            });
        }
    };

    function rpcCall(method, args, onProgress, onInput) {
        return new Promise((resolve, reject) => {
            if (!window.__OmniStudioLabBridge) {
                return reject(new Error("OmniStudioLabBridge is not attached to this tab"));
            }
            const id = 'call_' + Date.now() + '_' + Math.random().toString(36).substr(2, 6);
            callbacks.set(id, { resolve, reject, onProgress, onInput, thoughtsAcc: '', outputAcc: '' });
            try {
                window.__OmniStudioLabBridge[method](id, ...(args || []));
            } catch(e) {
                callbacks.delete(id);
                reject(e);
            }
        });
    }

    window.Omni = {
        profiles: {
            list: function() {
                return rpcCall('profilesList', []);
            }
        },
        studio: {
            createSession: function(opts) {
                opts = opts || {};
                var profile = opts.profile || opts.profileId || 'default';
                var model = opts.model || '';
                var bootPromise = (opts.temporaryChat !== undefined && window.__OmniStudioLabBridge && window.__OmniStudioLabBridge.studioCreateSessionWithOpts)
                    ? rpcCall('studioCreateSessionWithOpts', [JSON.stringify(opts)])
                    : rpcCall('studioCreateSession', [profile, model]);
                return bootPromise.then(function(res) {
                    var sId = res.sessionId;
                    var resolvedProf = res.profileId || profile;
                    return {
                        id: sId,
                        profile: resolvedProf,
                        configure: function(configOpts) {
                            return rpcCall('studioConfigureSession', [sId, JSON.stringify(configOpts || {})]);
                        },
                        send: function(turnOpts, onProgress) {
                            return rpcCall('studioSendSessionTurn', [sId, JSON.stringify(turnOpts || {})], onProgress);
                        },
                        newChat: function() {
                            return rpcCall('studioNewChatSession', [sId]);
                        },
                        close: function() {
                            return rpcCall('studioCloseSession', [sId]);
                        }
                    };
                });
            },
            init: function(profileId, model) {
                return rpcCall('studioInit', [profileId || 'default', model || '']);
            },
            configure: function(opts) {
                return rpcCall('studioConfigure', [JSON.stringify(opts || {})]);
            },
            send: function(opts, onProgress) {
                return rpcCall('studioSendTurn', [JSON.stringify(opts || {})], onProgress);
            },
            abort: function() {
                if (window.__OmniStudioLabBridge) window.__OmniStudioLabBridge.studioAbort();
            },
            close: function() {
                if (window.__OmniStudioLabBridge) window.__OmniStudioLabBridge.studioClose();
            }
        },
        chatgpt: {
            createSession: function(opts) {
                opts = opts || {};
                var profile = opts.profile || opts.profileId || 'default';
                var model = opts.model || '';
                var temporaryChat = !!opts.temporaryChat;
                return rpcCall('gptCreateSession', [profile, model, temporaryChat]).then(function(res) {
                    var sId = res.sessionId;
                    var resolvedProf = res.profileId || profile;
                    return {
                        id: sId,
                        profile: resolvedProf,
                        configure: function(configOpts) {
                            return rpcCall('gptConfigureSession', [sId, JSON.stringify(configOpts || {})]);
                        },
                        send: function(turnOpts, onProgress) {
                            return rpcCall('gptSendSessionTurn', [sId, JSON.stringify(turnOpts || {})], onProgress);
                        },
                        newChat: function(newChatOpts) {
                            var tc = (newChatOpts && typeof newChatOpts.temporaryChat === 'boolean') ? String(newChatOpts.temporaryChat) : 'null';
                            return rpcCall('gptNewChatSession', [sId, tc]);
                        },
                        close: function() {
                            return rpcCall('gptCloseSession', [sId]);
                        }
                    };
                });
            },
            init: function(profileId, model, temporaryChat) {
                return rpcCall('gptInit', [profileId || 'default', model || '', !!temporaryChat]);
            },
            configure: function(opts) {
                return rpcCall('gptConfigure', [JSON.stringify(opts || {})]);
            },
            send: function(opts, onProgress) {
                return rpcCall('gptSendTurn', [JSON.stringify(opts || {})], onProgress);
            },
            newChat: function(opts) {
                var tc = (opts && typeof opts.temporaryChat === 'boolean') ? String(opts.temporaryChat) : 'null';
                return rpcCall('gptNewChat', [tc]);
            },
            abort: function() {
                if (window.__OmniStudioLabBridge) window.__OmniStudioLabBridge.gptAbort();
            },
            close: function() {
                if (window.__OmniStudioLabBridge) window.__OmniStudioLabBridge.gptClose();
            }
        },
        gemini: {
            createSession: function(opts) {
                opts = opts || {};
                var profile = opts.profile || opts.profileId || 'default';
                var model = opts.model || '';
                var temporaryChat = !!opts.temporaryChat;
                return rpcCall('geminiCreateSession', [profile, model, temporaryChat]).then(function(res) {
                    var sId = res.sessionId;
                    var resolvedProf = res.profileId || profile;
                    return {
                        id: sId,
                        profile: resolvedProf,
                        configure: function(configOpts) {
                            return rpcCall('geminiConfigureSession', [sId, JSON.stringify(configOpts || {})]);
                        },
                        send: function(turnOpts, onProgress) {
                            return rpcCall('geminiSendSessionTurn', [sId, JSON.stringify(turnOpts || {})], onProgress);
                        },
                        newChat: function(newChatOpts) {
                            var tc = (newChatOpts && typeof newChatOpts.temporaryChat === 'boolean') ? String(newChatOpts.temporaryChat) : 'null';
                            return rpcCall('geminiNewChatSession', [sId, tc]);
                        },
                        close: function() {
                            return rpcCall('geminiCloseSession', [sId]);
                        }
                    };
                });
            },
            init: function(profileId, model, temporaryChat) {
                return rpcCall('geminiInit', [profileId || 'default', model || '', !!temporaryChat]);
            },
            configure: function(opts) {
                return rpcCall('geminiConfigure', [JSON.stringify(opts || {})]);
            },
            send: function(opts, onProgress) {
                return rpcCall('geminiSendTurn', [JSON.stringify(opts || {})], onProgress);
            },
            newChat: function(opts) {
                var tc = (opts && typeof opts.temporaryChat === 'boolean') ? String(opts.temporaryChat) : 'null';
                return rpcCall('geminiNewChat', [tc]);
            },
            abort: function() {
                if (window.__OmniStudioLabBridge) window.__OmniStudioLabBridge.geminiAbort();
            },
            close: function() {
                if (window.__OmniStudioLabBridge) window.__OmniStudioLabBridge.geminiClose();
            }
        },
        python: {
            run: function(code, onProgress, onInput) {
                return rpcCall('pythonRun', [code], onProgress, onInput);
            },
            provideInput: function(callId, value) {
                if (window.__OmniStudioLabBridge && window.__OmniStudioLabBridge.pythonProvideInput) {
                    window.__OmniStudioLabBridge.pythonProvideInput(callId, String(value || ''));
                }
            }
        },
        ide: {
            pullFiles: function(project, files) {
                return rpcCall('idePullFiles', [project, JSON.stringify(files || [])]);
            },
            commitCxp: function(xml) {
                return rpcCall('ideCommitCxp', [xml]);
            }
        },
        fs: {
            read: function(path) {
                return rpcCall('fsRead', [path]);
            },
            write: function(path, content) {
                return rpcCall('fsWrite', [path, content]);
            },
            list: function(path) {
                return rpcCall('fsList', [path]);
            }
        },
        wakeLock: {
            acquire: function(tag) {
                return rpcCall('wakeLockAcquire', [tag || 'StudioLab']);
            },
            release: function() {
                return rpcCall('wakeLockRelease', []);
            }
        },
        screen: {
            keepAwake: function(enabled) {
                return rpcCall('screenKeepAwake', [!!enabled]);
            },
            setNightDimmer: function(enabled, statusText) {
                return rpcCall('nightDimmerSet', [!!enabled, String(statusText || '')]);
            },
            updateNightDimmer: function(statusText) {
                if (window.__OmniStudioLabBridge && window.__OmniStudioLabBridge.nightDimmerUpdate) {
                    window.__OmniStudioLabBridge.nightDimmerUpdate(String(statusText || ''));
                }
            }
        },
        overlay: {
            canDraw: function() {
                if (window.__OmniStudioLabBridge && window.__OmniStudioLabBridge.canDrawOverlays) {
                    return !!window.__OmniStudioLabBridge.canDrawOverlays();
                }
                return false;
            },
            request: function() {
                if (window.__OmniStudioLabBridge && window.__OmniStudioLabBridge.requestOverlayPermission) {
                    window.__OmniStudioLabBridge.requestOverlayPermission();
                }
            }
        },
        toast: function(msg) {
            if (window.__OmniStudioLabBridge) window.__OmniStudioLabBridge.toast(String(msg));
        },
        vibrate: function(ms) {
            if (window.__OmniStudioLabBridge) window.__OmniStudioLabBridge.vibrate(Number(ms) || 50);
        },
        copy: function(text) {
            if (window.__OmniStudioLabBridge) window.__OmniStudioLabBridge.copyToClipboard(String(text));
        },
        getClipboard: function() {
            if (window.__OmniStudioLabBridge) return window.__OmniStudioLabBridge.getClipboardText();
            return "";
        }
,
        browser: {
            openTab: function(url, profileId) {
                if (window.__OmniStudioLabBridge && window.__OmniStudioLabBridge.browserOpenTab) {
                    window.__OmniStudioLabBridge.browserOpenTab(String(url || ''), String(profileId || ''));
                    return true;
                }
                return false;
            }
        }
    };
    console.log("⚡ [Omni Studio Lab] window.Omni SDK initialized & ready.");
})();
""".trimIndent()
val DEFAULT_PYTHON_LAB_HTML = """
<!-- omni-python-lab-v2 -->
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>Python Lab</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <style>
        body { background-color: #0D1117; color: #E8EAED; font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; -webkit-tap-highlight-color: transparent; }
        .mono { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace; }
        ::-webkit-scrollbar { width: 6px; height: 6px; }
        ::-webkit-scrollbar-thumb { background: #30363D; border-radius: 3px; }
    </style>
</head>
<body class="flex flex-col h-screen overflow-hidden text-sm">
    <!-- Slim Single-Row Header -->
    <header class="flex items-center justify-between px-2.5 h-11 bg-[#161B22] border-b border-[#30363D] shrink-0">
        <div class="flex items-center gap-2 min-w-0">
            <button id="btn-sidebar-toggle" title="Open Sidebar" class="relative flex items-center justify-center w-8 h-8 rounded-lg bg-[#21262D] border border-[#30363D] text-[#E8EAED] active:scale-95 transition">
                <span class="text-base leading-none">☰</span>
                <span id="sidebar-unread-dot" class="hidden absolute top-1 right-1 w-2 h-2 rounded-full bg-[#00E5FF]"></span>
            </button>
            <span class="text-base">🐍</span>
            <span id="active-view-label" class="font-bold text-xs text-[#00E5FF] truncate">Editor</span>
            <span id="status-pill" class="px-2 py-0.5 text-[10px] font-bold rounded-full bg-[#21262D] text-[#8B949E] border border-[#30363D] shrink-0">Idle</span>
        </div>
        <div class="flex items-center gap-1.5 shrink-0">
            <button id="btn-quick-console" title="Toggle Console" class="relative px-2 py-1 text-[11px] font-semibold rounded-lg bg-[#21262D] text-[#C9D1D9] border border-[#30363D] active:scale-95 transition">
                <span>📺</span>
                <span id="unread-dot" class="hidden inline-block w-1.5 h-1.5 rounded-full bg-[#00E5FF] ml-0.5"></span>
            </button>
            <button id="btn-run" class="flex items-center gap-1 px-3 py-1 font-bold text-xs rounded-lg bg-[#00E5FF] text-[#0D1117] hover:bg-[#33EAFF] active:scale-95 transition">
                <span>▶</span> <span>Run</span>
            </button>
            <button id="btn-stop" class="hidden px-2.5 py-1 font-bold text-xs rounded-lg bg-[#F85149] text-white hover:bg-[#FF7B72] active:scale-95 transition">
                ■ Stop
            </button>
        </div>
    </header>

    <!-- Slide-Out Sidebar Backdrop -->
    <div id="sidebar-backdrop" class="hidden fixed inset-0 bg-black/60 z-40"></div>

    <!-- Organized Slide-Out Sidebar Drawer -->
    <aside id="sidebar-drawer" class="fixed top-0 left-0 bottom-0 w-72 max-w-[85vw] bg-[#161B22] border-r border-[#30363D] z-50 transform -translate-x-full transition-transform duration-200 flex flex-col">
        <div class="flex items-center justify-between px-3.5 h-11 border-b border-[#30363D] shrink-0">
            <div class="flex items-center gap-2">
                <span class="text-base">🐍</span>
                <span class="font-bold text-xs text-[#00E5FF] tracking-wide">Python Lab</span>
            </div>
            <button id="btn-sidebar-close" class="w-7 h-7 flex items-center justify-center rounded-md bg-[#21262D] text-[#8B949E] hover:text-white text-xs font-bold">✕</button>
        </div>

        <div class="flex-1 overflow-y-auto p-3 space-y-4">
            <!-- Section 1: Workspace Views -->
            <div class="space-y-1.5">
                <div class="px-1 text-[10px] font-bold uppercase tracking-wider text-[#8B949E]">Workspace</div>
                <div class="space-y-1">
                    <button data-tab="editor" class="tab-btn w-full flex items-center justify-between px-2.5 py-2 rounded-lg text-xs font-bold bg-[#1F242C] text-[#00E5FF] border border-[#30363D]">
                        <span>💻 Code Editor</span>
                    </button>
                    <button data-tab="console" class="tab-btn w-full flex items-center justify-between px-2.5 py-2 rounded-lg text-xs font-semibold text-[#C9D1D9] hover:bg-[#21262D]">
                        <span>📺 Console Output</span>
                        <span id="sidebar-console-dot" class="hidden w-2 h-2 rounded-full bg-[#00E5FF]"></span>
                    </button>
                    <button data-tab="repl" class="tab-btn w-full flex items-center justify-between px-2.5 py-2 rounded-lg text-xs font-semibold text-[#C9D1D9] hover:bg-[#21262D]">
                        <span>⚡ Interactive REPL</span>
                    </button>
                    <button data-tab="library" class="tab-btn w-full flex items-center justify-between px-2.5 py-2 rounded-lg text-xs font-semibold text-[#C9D1D9] hover:bg-[#21262D]">
                        <span>📚 Scripts Library</span>
                    </button>
                </div>
            </div>

            <!-- Section 2: Editor Actions -->
            <div class="space-y-1.5 border-t border-[#21262D] pt-3">
                <div class="px-1 text-[10px] font-bold uppercase tracking-wider text-[#8B949E]">Editor Actions</div>
                <div class="grid grid-cols-2 gap-1.5">
                    <button id="btn-paste" class="px-2.5 py-2 rounded-lg bg-[#21262D] text-[#8AB4F8] text-xs font-semibold text-left hover:bg-[#30363D]">📋 Paste</button>
                    <button id="btn-copy-code" class="px-2.5 py-2 rounded-lg bg-[#21262D] text-[#8AB4F8] text-xs font-semibold text-left hover:bg-[#30363D]">📄 Copy</button>
                    <button id="btn-save-lib" class="px-2.5 py-2 rounded-lg bg-[#21262D] text-[#3FB950] text-xs font-semibold text-left hover:bg-[#30363D]">💾 Save</button>
                    <button id="btn-clear-code" class="px-2.5 py-2 rounded-lg bg-[#21262D] text-[#F85149] text-xs font-semibold text-left hover:bg-[#30363D]">🗑️ Clear</button>
                </div>
            </div>

            <!-- Section 3: Snippets -->
            <div class="space-y-1.5 border-t border-[#21262D] pt-3">
                <div class="px-1 text-[10px] font-bold uppercase tracking-wider text-[#8B949E]">Quick Snippets</div>
                <div class="space-y-1">
                    <button data-snippet="hello" class="snippet-btn w-full px-2.5 py-1.5 rounded-lg bg-[#21262D]/70 text-[#C9D1D9] text-xs text-left hover:bg-[#30363D] hover:text-[#00E5FF]">👋 Interactive Stdin Demo</button>
                    <button data-snippet="storage" class="snippet-btn w-full px-2.5 py-1.5 rounded-lg bg-[#21262D]/70 text-[#C9D1D9] text-xs text-left hover:bg-[#30363D] hover:text-[#00E5FF]">📂 Storage & Filesystem Walk</button>
                    <button data-snippet="diag" class="snippet-btn w-full px-2.5 py-1.5 rounded-lg bg-[#21262D]/70 text-[#C9D1D9] text-xs text-left hover:bg-[#30363D] hover:text-[#00E5FF]">🔍 Device & Kernel Diagnostics</button>
                    <button data-snippet="bench" class="snippet-btn w-full px-2.5 py-1.5 rounded-lg bg-[#21262D]/70 text-[#C9D1D9] text-xs text-left hover:bg-[#30363D] hover:text-[#00E5FF]">⏱️ Prime Sieve Benchmark</button>
                    <button data-snippet="http" class="snippet-btn w-full px-2.5 py-1.5 rounded-lg bg-[#21262D]/70 text-[#C9D1D9] text-xs text-left hover:bg-[#30363D] hover:text-[#00E5FF]">🌐 Network & Public IP Fetcher</button>
                </div>
            </div>

            <!-- Section 4: Runtime & Power Settings -->
            <div class="space-y-1.5 border-t border-[#21262D] pt-3">
                <div class="px-1 text-[10px] font-bold uppercase tracking-wider text-[#8B949E]">Settings & Power</div>
                <div class="space-y-1.5">
                    <button id="toggle-wakelock-btn" class="w-full flex items-center justify-between px-2.5 py-2 rounded-lg bg-[#21262D] border border-[#30363D] text-xs font-semibold text-white">
                        <span>⚡ Background WakeLock</span>
                        <span id="wakelock-badge" class="text-[10px] font-bold text-[#8B949E]">OFF</span>
                    </button>
                    <button id="toggle-awake-btn" class="w-full flex items-center justify-between px-2.5 py-2 rounded-lg bg-[#21262D] border border-[#30363D] text-xs font-semibold text-white">
                        <span>📱 Keep Screen Awake</span>
                        <span id="awake-badge" class="text-[10px] font-bold text-[#8B949E]">OFF</span>
                    </button>
                    <button id="btn-flush-runtime" class="w-full px-2.5 py-2 rounded-lg bg-[#DA3633]/20 border border-[#F85149]/40 text-[#F85149] text-xs font-bold text-left hover:bg-[#DA3633]/30">🧹 Flush & Reset Runtime</button>
                </div>
            </div>
        </div>
    </aside>

    <!-- Main Screen Container -->
    <main class="flex-1 relative overflow-hidden bg-[#0D1117]">
        <!-- TAB 1: Code Editor -->
        <section id="tab-editor" class="tab-content flex flex-col h-full">
            <div class="flex-1 flex overflow-hidden relative">
                <div id="line-numbers" class="w-10 pt-3 pb-3 pr-2 text-right text-[11px] font-mono text-[#484F58] select-none bg-[#0D1117] border-r border-[#21262D] overflow-hidden">1</div>
                <textarea id="code-input" spellcheck="false" class="flex-1 h-full p-3 bg-transparent text-[#E8EAED] font-mono text-xs outline-none resize-none leading-5" placeholder="# Write or paste Python code here...&#10;print('Hello from Nexus!')"></textarea>
            </div>
        </section>

        <!-- TAB 2: Live Console / Output & Stdin -->
        <section id="tab-console" class="tab-content hidden flex flex-col h-full bg-black">
            <div class="flex items-center justify-between px-3 py-1.5 bg-[#161B22] border-b border-[#21262D] text-xs shrink-0">
                <div class="flex items-center gap-2">
                    <span class="font-bold text-[#81C784]">📺 STDOUT / STDERR</span>
                    <span id="output-meta" class="text-[10px] text-[#8B949E]">0 chars</span>
                </div>
                <div class="flex items-center gap-2">
                    <button id="btn-copy-output" class="px-2 py-0.5 rounded bg-[#21262D] text-[#8AB4F8] hover:bg-[#30363D]">📋 Copy</button>
                    <button id="btn-clear-output" class="px-2 py-0.5 rounded bg-[#21262D] text-[#F85149] hover:bg-[#30363D]">🗑️ Clear</button>
                </div>
            </div>

            <pre id="console-output" class="flex-1 p-3 text-xs font-mono text-[#81C784] overflow-y-auto whitespace-pre-wrap select-text leading-5">Python Lab Ready. Tap [▶ Run] to execute code on Nexus.</pre>

            <div id="stdin-bar" class="hidden flex items-center gap-2 p-2.5 bg-[#161B22] border-t-2 border-[#00E5FF] shadow-lg shrink-0">
                <span class="text-xs font-bold text-[#00E5FF] shrink-0 flex items-center gap-1">
                    <span class="animate-pulse">💬</span> <span id="stdin-prompt">INPUT ></span>
                </span>
                <input id="stdin-input" type="text" autocomplete="off" class="flex-1 px-3 py-1.5 bg-[#0D1117] border border-[#30363D] focus:border-[#00E5FF] rounded text-xs font-mono text-white outline-none" placeholder="Type input and press Enter...">
                <button id="btn-stdin-submit" class="px-3 py-1.5 bg-[#00E5FF] text-[#0D1117] font-bold rounded text-xs hover:bg-[#33EAFF] active:scale-95 transition">Send ⏎</button>
            </div>
        </section>

        <!-- TAB 3: Interactive REPL -->
        <section id="tab-repl" class="tab-content hidden flex flex-col h-full bg-black">
            <div class="flex items-center justify-between px-3 py-1.5 bg-[#161B22] border-b border-[#21262D] text-xs shrink-0">
                <span class="font-bold text-[#00E5FF]">⚡ Interactive Line REPL</span>
                <button id="btn-clear-repl" class="px-2 py-0.5 rounded bg-[#21262D] text-[#8B949E] hover:text-white">Clear</button>
            </div>
            <pre id="repl-history" class="flex-1 p-3 text-xs font-mono text-[#C9D1D9] overflow-y-auto whitespace-pre-wrap select-text leading-5">Python 3.11 interactive REPL ready.&#10;>>> </pre>
            <div class="flex items-center gap-2 p-2 bg-[#161B22] border-t border-[#30363D] shrink-0">
                <span class="font-mono text-xs font-bold text-[#00E5FF]">>>></span>
                <input id="repl-input" type="text" class="flex-1 px-3 py-1.5 bg-[#0D1117] border border-[#30363D] focus:border-[#00E5FF] rounded text-xs font-mono text-white outline-none" placeholder="e.g. 2 + 2 or os.listdir('.')">
                <button id="btn-repl-send" class="px-3 py-1.5 bg-[#1F6FEB] text-white font-bold rounded text-xs hover:bg-[#388BFD]">Run</button>
            </div>
        </section>

        <!-- TAB 4: Scripts Library -->
        <section id="tab-library" class="tab-content hidden flex flex-col h-full p-3 overflow-y-auto space-y-3">
            <div class="flex items-center justify-between border-b border-[#30363D] pb-2">
                <div>
                    <h2 class="font-bold text-white text-sm">📚 Saved Scripts & Templates</h2>
                    <p class="text-[11px] text-[#8B949E]">Tap Load to open any script in the editor</p>
                </div>
                <button id="btn-new-script" class="px-2.5 py-1 rounded bg-[#238636] text-white font-bold text-xs hover:bg-[#2EA043]">+ New</button>
            </div>
            <div id="library-list" class="space-y-2"></div>
        </section>
    </main>

    <script>
        (function() {
            var activeTab = 'editor';
            var isRunning = false;
            var isWakeLockOn = false;
            var isKeepAwakeOn = false;
            var currentProvideInput = null;

            var codeInput = document.getElementById('code-input');
            var lineNumbers = document.getElementById('line-numbers');
            var consoleOutput = document.getElementById('console-output');
            var outputMeta = document.getElementById('output-meta');
            var statusPill = document.getElementById('status-pill');
            var activeViewLabel = document.getElementById('active-view-label');
            var unreadDot = document.getElementById('unread-dot');
            var sidebarUnreadDot = document.getElementById('sidebar-unread-dot');
            var sidebarConsoleDot = document.getElementById('sidebar-console-dot');
            var btnRun = document.getElementById('btn-run');
            var btnStop = document.getElementById('btn-stop');
            var stdinBar = document.getElementById('stdin-bar');
            var stdinPrompt = document.getElementById('stdin-prompt');
            var stdinInput = document.getElementById('stdin-input');
            var btnStdinSubmit = document.getElementById('btn-stdin-submit');
            var sidebarDrawer = document.getElementById('sidebar-drawer');
            var sidebarBackdrop = document.getElementById('sidebar-backdrop');

            var VIEW_TITLES = {
                editor: 'Editor',
                console: 'Console',
                repl: 'REPL',
                library: 'Library'
            };

            function openSidebar() {
                sidebarBackdrop.classList.remove('hidden');
                sidebarDrawer.classList.remove('-translate-x-full');
            }

            function closeSidebar() {
                sidebarDrawer.classList.add('-translate-x-full');
                sidebarBackdrop.classList.add('hidden');
            }

            document.getElementById('btn-sidebar-toggle').addEventListener('click', openSidebar);
            document.getElementById('btn-sidebar-close').addEventListener('click', closeSidebar);
            sidebarBackdrop.addEventListener('click', closeSidebar);

            var STARTER_TEMPLATES = [
                {
                    id: 'tmpl_hello',
                    title: '👋 Interactive Stdin Demo',
                    code: '# Python Lab Interactive Demo\nname = input("Enter your name: ")\nprint("Welcome to Nexus, " + name + "!")\nage = input("Enter your age: ")\nprint("In 5 years, you will be " + str(int(age) + 5) + " years old.")'
                },
                {
                    id: 'tmpl_storage',
                    title: '📂 Storage & Filesystem Walk',
                    code: 'import os\n\npath = "/storage/emulated/0"\nprint("Scanning: " + path)\nitems = os.listdir(path)\nprint("Found " + str(len(items)) + " items:\\n")\nfor item in sorted(items)[:20]:\n    full = os.path.join(path, item)\n    is_dir = os.path.isdir(full)\n    size = os.path.getsize(full) if not is_dir else 0\n    print(("📁 " if is_dir else "📄 ") + item + (" (" + str(size // 1024) + " KB)" if not is_dir else ""))'
                },
                {
                    id: 'tmpl_diag',
                    title: '🔍 Device & Kernel Diagnostics',
                    code: 'import sys, platform, os\n\nprint("Python Version : " + platform.python_version())\nprint("Platform OS    : " + sys.platform)\nprint("Architecture   : " + str(platform.architecture()))\nprint("CPU Cores      : " + str(os.cpu_count()))\nprint("Working Dir    : " + os.getcwd())'
                },
                {
                    id: 'tmpl_bench',
                    title: '⏱️ Prime Sieve Benchmark',
                    code: 'import time\n\ndef sieve(n):\n    primes = [True] * (n + 1)\n    p = 2\n    while p * p <= n:\n        if primes[p]:\n            for i in range(p * p, n + 1, p):\n                primes[i] = False\n        p += 1\n    return [p for p in range(2, n + 1) if primes[p]]\n\nt0 = time.time()\nfound = sieve(100000)\nelapsed = (time.time() - t0) * 1000\nprint("Computed " + str(len(found)) + " primes in " + "{:.2f}".format(elapsed) + " ms.")\nprint("Highest prime: " + str(found[-1]))'
                },
                {
                    id: 'tmpl_http',
                    title: '🌐 Network & Public IP Fetcher',
                    code: 'import urllib.request, json\n\nprint("Pinging external API...")\nreq = urllib.request.urlopen("https://api.ipify.org?format=json", timeout=10)\nres = json.loads(req.read().decode("utf-8"))\nprint("Connected! Public IP: " + res.get("ip", "unknown"))'
                }
            ];

            document.querySelectorAll('.tab-btn').forEach(function(btn) {
                btn.addEventListener('click', function() {
                    switchTab(btn.getAttribute('data-tab'));
                    closeSidebar();
                });
            });

            document.getElementById('btn-quick-console').addEventListener('click', function() {
                switchTab(activeTab === 'console' ? 'editor' : 'console');
            });

            function switchTab(tabId) {
                activeTab = tabId;
                activeViewLabel.innerText = VIEW_TITLES[tabId] || 'Python Lab';
                document.querySelectorAll('.tab-btn').forEach(function(b) {
                    var isCur = b.getAttribute('data-tab') === tabId;
                    b.className = 'tab-btn w-full flex items-center justify-between px-2.5 py-2 rounded-lg text-xs ' +
                        (isCur ? 'font-bold bg-[#1F242C] text-[#00E5FF] border border-[#30363D]' : 'font-semibold text-[#C9D1D9] hover:bg-[#21262D]');
                });
                document.querySelectorAll('.tab-content').forEach(function(c) {
                    c.classList.add('hidden');
                });
                var target = document.getElementById('tab-' + tabId);
                if (target) target.classList.remove('hidden');
                if (tabId === 'console') {
                    unreadDot.classList.add('hidden');
                    sidebarUnreadDot.classList.add('hidden');
                    sidebarConsoleDot.classList.add('hidden');
                }
            }

            function updateLineNumbers() {
                var lines = codeInput.value.split('\n').length;
                var arr = [];
                for (var i = 1; i <= lines; i++) arr.push(i);
                lineNumbers.innerText = arr.join('\n');
            }

            codeInput.addEventListener('input', updateLineNumbers);
            codeInput.addEventListener('scroll', function() {
                lineNumbers.scrollTop = codeInput.scrollTop;
            });
            codeInput.addEventListener('keydown', function(e) {
                if (e.key === 'Tab') {
                    e.preventDefault();
                    var start = this.selectionStart;
                    var end = this.selectionEnd;
                    this.value = this.value.substring(0, start) + '    ' + this.value.substring(end);
                    this.selectionStart = this.selectionEnd = start + 4;
                    updateLineNumbers();
                }
            });

            var savedCode = localStorage.getItem('nexus_py_code');
            codeInput.value = savedCode !== null ? savedCode : STARTER_TEMPLATES[0].code;
            updateLineNumbers();

            btnRun.addEventListener('click', function() {
                executeCode(codeInput.value);
            });

            function executeCode(code) {
                if (isRunning) return;
                var trimmed = code.trim();
                if (!trimmed) {
                    if (window.Omni) window.Omni.toast('Write some Python code first');
                    return;
                }
                localStorage.setItem('nexus_py_code', code);

                isRunning = true;
                btnRun.classList.add('hidden');
                btnStop.classList.remove('hidden');
                statusPill.innerText = 'Running';
                statusPill.className = 'px-2 py-0.5 text-[10px] font-bold rounded-full bg-[#1F6FEB]/20 text-[#58A6FF] border border-[#58A6FF]/40 shrink-0';
                consoleOutput.innerText = '';
                outputMeta.innerText = '0 chars';
                stdinBar.classList.add('hidden');
                if (window.Omni) window.Omni.vibrate(25);

                switchTab('console');

                var totalChars = 0;

                window.Omni.python.run(
                    code,
                    function(chunk) {
                        consoleOutput.innerText += chunk;
                        totalChars += chunk.length;
                        outputMeta.innerText = totalChars + ' chars';
                        consoleOutput.scrollTop = consoleOutput.scrollHeight;
                        if (activeTab !== 'console') {
                            unreadDot.classList.remove('hidden');
                            sidebarUnreadDot.classList.remove('hidden');
                            sidebarConsoleDot.classList.remove('hidden');
                        }
                    },
                    function(prompt, provide) {
                        currentProvideInput = provide;
                        statusPill.innerText = 'Input';
                        statusPill.className = 'px-2 py-0.5 text-[10px] font-bold rounded-full bg-[#E5A93C]/20 text-[#E5A93C] border border-[#E5A93C]/40 shrink-0';
                        stdinPrompt.innerText = (prompt || 'INPUT').trim() + ' >';
                        stdinInput.value = '';
                        stdinBar.classList.remove('hidden');
                        switchTab('console');
                        setTimeout(function() { stdinInput.focus(); }, 150);
                        if (window.Omni) {
                            window.Omni.vibrate(40);
                            window.Omni.toast('Python awaiting input');
                        }
                    }
                ).then(function(finalOutput) {
                    finishRun(true, finalOutput);
                }).catch(function(err) {
                    finishRun(false, err ? err.message : 'Execution error');
                });
            }

            function finishRun(success, result) {
                isRunning = false;
                btnRun.classList.remove('hidden');
                btnStop.classList.add('hidden');
                stdinBar.classList.add('hidden');
                currentProvideInput = null;

                if (success) {
                    statusPill.innerText = 'Ready';
                    statusPill.className = 'px-2 py-0.5 text-[10px] font-bold rounded-full bg-[#238636]/20 text-[#3FB950] border border-[#3FB950]/40 shrink-0';
                    if (result && !consoleOutput.innerText.includes(result)) {
                        consoleOutput.innerText += '\n' + result;
                    }
                    if (window.Omni) window.Omni.toast('Execution finished');
                } else {
                    statusPill.innerText = 'Error';
                    statusPill.className = 'px-2 py-0.5 text-[10px] font-bold rounded-full bg-[#DA3633]/20 text-[#F85149] border border-[#F85149]/40 shrink-0';
                    consoleOutput.innerText += '\n❌ ' + result;
                    if (window.Omni) window.Omni.toast('Script error');
                }
                outputMeta.innerText = consoleOutput.innerText.length + ' chars';
                consoleOutput.scrollTop = consoleOutput.scrollHeight;
            }

            function submitStdin() {
                if (!currentProvideInput) return;
                var val = stdinInput.value;
                consoleOutput.innerText += (stdinPrompt.innerText + ' ' + val + '\n');
                var fn = currentProvideInput;
                currentProvideInput = null;
                stdinBar.classList.add('hidden');
                statusPill.innerText = 'Running';
                statusPill.className = 'px-2 py-0.5 text-[10px] font-bold rounded-full bg-[#1F6FEB]/20 text-[#58A6FF] border border-[#58A6FF]/40 shrink-0';
                fn(val);
            }

            btnStdinSubmit.addEventListener('click', submitStdin);
            stdinInput.addEventListener('keydown', function(e) {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    submitStdin();
                }
            });

            btnStop.addEventListener('click', function() {
                if (window.Omni) {
                    window.Omni.python.run('import sys; sys.exit(0)', function(){}, function(){});
                    window.Omni.toast('Interrupted script');
                }
                finishRun(false, 'Halted by user');
            });

            document.querySelectorAll('.snippet-btn').forEach(function(b) {
                b.addEventListener('click', function() {
                    var sType = b.getAttribute('data-snippet');
                    var match = STARTER_TEMPLATES.find(function(t) { return t.id.indexOf(sType) !== -1; });
                    if (match) {
                        codeInput.value = match.code;
                        updateLineNumbers();
                        switchTab('editor');
                        closeSidebar();
                        if (window.Omni) window.Omni.toast('Loaded ' + match.title);
                    }
                });
            });

            document.getElementById('btn-copy-code').addEventListener('click', function() {
                if (window.Omni) {
                    window.Omni.copy(codeInput.value);
                    window.Omni.toast('Copied code to clipboard');
                }
                closeSidebar();
            });
            document.getElementById('btn-paste').addEventListener('click', function() {
                if (window.Omni) {
                    var clip = window.Omni.getClipboard();
                    if (clip) {
                        codeInput.value = clip;
                        updateLineNumbers();
                        switchTab('editor');
                        window.Omni.toast('Pasted from clipboard');
                    } else {
                        window.Omni.toast('Clipboard is empty');
                    }
                }
                closeSidebar();
            });
            document.getElementById('btn-clear-code').addEventListener('click', function() {
                codeInput.value = '';
                updateLineNumbers();
                switchTab('editor');
                closeSidebar();
            });

            document.getElementById('btn-copy-output').addEventListener('click', function() {
                if (window.Omni) {
                    window.Omni.copy(consoleOutput.innerText);
                    window.Omni.toast('Copied output');
                }
            });
            document.getElementById('btn-clear-output').addEventListener('click', function() {
                consoleOutput.innerText = '';
                outputMeta.innerText = '0 chars';
            });

            var replInput = document.getElementById('repl-input');
            var replHistory = document.getElementById('repl-history');
            document.getElementById('btn-repl-send').addEventListener('click', runReplLine);
            replInput.addEventListener('keydown', function(e) {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    runReplLine();
                }
            });

            function runReplLine() {
                var line = replInput.value.trim();
                if (!line) return;
                replInput.value = '';
                replHistory.innerText += line + '\n';
                var wrapped = 'try:\n    __r = eval(' + JSON.stringify(line) + ')\n    if __r is not None: print(repr(__r))\nexcept SyntaxError:\n    exec(' + JSON.stringify(line) + ')\n';
                window.Omni.python.run(wrapped, function(chunk) {
                    replHistory.innerText += chunk;
                }, function(){}).then(function(out) {
                    if (out && !replHistory.innerText.endsWith(out)) replHistory.innerText += out + '\n';
                    replHistory.innerText += '>>> ';
                    replHistory.scrollTop = replHistory.scrollHeight;
                }).catch(function(err) {
                    replHistory.innerText += 'Error: ' + err.message + '\n>>> ';
                    replHistory.scrollTop = replHistory.scrollHeight;
                });
            }

            document.getElementById('btn-clear-repl').addEventListener('click', function() {
                replHistory.innerText = 'Python 3.11 interactive REPL ready.\n>>> ';
            });

            function renderLibrary() {
                var libList = document.getElementById('library-list');
                libList.innerHTML = '';
                var userScripts = [];
                try {
                    userScripts = JSON.parse(localStorage.getItem('nexus_user_scripts') || '[]');
                } catch(_) {}

                var all = STARTER_TEMPLATES.concat(userScripts);
                all.forEach(function(item, idx) {
                    var card = document.createElement('div');
                    card.className = 'p-3 rounded-lg bg-[#161B22] border border-[#30363D] flex items-center justify-between gap-3 hover:border-[#00E5FF] transition cursor-pointer';
                    card.innerHTML = '<div class="flex-1 overflow-hidden">' +
                        '<div class="font-bold text-white text-xs truncate">' + item.title + '</div>' +
                        '<div class="text-[10px] text-[#8B949E] font-mono truncate">' + (item.code.split('\n')[0] || '') + '</div>' +
                        '</div>' +
                        '<div class="flex items-center gap-1.5 shrink-0">' +
                        '<button class="load-btn px-2.5 py-1 rounded bg-[#1F6FEB] text-white font-bold text-xs">Load</button>' +
                        (idx >= STARTER_TEMPLATES.length ? '<button class="del-btn px-2 py-1 rounded bg-[#DA3633] text-white text-xs">✕</button>' : '') +
                        '</div>';

                    card.querySelector('.load-btn').addEventListener('click', function(e) {
                        e.stopPropagation();
                        codeInput.value = item.code;
                        updateLineNumbers();
                        switchTab('editor');
                        if (window.Omni) window.Omni.toast('Loaded ' + item.title);
                    });

                    var del = card.querySelector('.del-btn');
                    if (del) {
                        del.addEventListener('click', function(e) {
                            e.stopPropagation();
                            userScripts = userScripts.filter(function(s) { return s.id !== item.id; });
                            localStorage.setItem('nexus_user_scripts', JSON.stringify(userScripts));
                            renderLibrary();
                        });
                    }
                    libList.appendChild(card);
                });
            }

            document.getElementById('btn-save-lib').addEventListener('click', function() {
                var title = prompt('Enter a title for this script:', 'My Script ' + new Date().toLocaleTimeString());
                if (!title) return;
                var userScripts = [];
                try {
                    userScripts = JSON.parse(localStorage.getItem('nexus_user_scripts') || '[]');
                } catch(_) {}
                userScripts.unshift({
                    id: 'usr_' + Date.now(),
                    title: title,
                    code: codeInput.value
                });
                localStorage.setItem('nexus_user_scripts', JSON.stringify(userScripts));
                renderLibrary();
                closeSidebar();
                if (window.Omni) window.Omni.toast('Saved to Library');
            });

            document.getElementById('btn-new-script').addEventListener('click', function() {
                codeInput.value = '# New Script\nprint("Hello")';
                updateLineNumbers();
                switchTab('editor');
            });

            renderLibrary();

            var toggleWakeLockBtn = document.getElementById('toggle-wakelock-btn');
            var wakeLockBadge = document.getElementById('wakelock-badge');
            var toggleAwakeBtn = document.getElementById('toggle-awake-btn');
            var awakeBadge = document.getElementById('awake-badge');

            toggleWakeLockBtn.addEventListener('click', function() {
                isWakeLockOn = !isWakeLockOn;
                if (isWakeLockOn) {
                    if (window.Omni && window.Omni.wakeLock) window.Omni.wakeLock.acquire('PythonLab');
                    wakeLockBadge.innerText = 'ON';
                    wakeLockBadge.className = 'text-[10px] font-bold text-[#00E5FF]';
                    toggleWakeLockBtn.className = 'w-full flex items-center justify-between px-2.5 py-2 rounded-lg bg-[#00E5FF]/15 border border-[#00E5FF] text-xs font-semibold text-[#00E5FF]';
                    if (window.Omni) window.Omni.toast('Background WakeLock engaged');
                } else {
                    if (window.Omni && window.Omni.wakeLock) window.Omni.wakeLock.release();
                    wakeLockBadge.innerText = 'OFF';
                    wakeLockBadge.className = 'text-[10px] font-bold text-[#8B949E]';
                    toggleWakeLockBtn.className = 'w-full flex items-center justify-between px-2.5 py-2 rounded-lg bg-[#21262D] border border-[#30363D] text-xs font-semibold text-white';
                    if (window.Omni) window.Omni.toast('WakeLock released');
                }
            });

            toggleAwakeBtn.addEventListener('click', function() {
                isKeepAwakeOn = !isKeepAwakeOn;
                if (window.Omni && window.Omni.screen) window.Omni.screen.keepAwake(isKeepAwakeOn);
                awakeBadge.innerText = isKeepAwakeOn ? 'ON' : 'OFF';
                awakeBadge.className = isKeepAwakeOn ? 'text-[10px] font-bold text-[#3FB950]' : 'text-[10px] font-bold text-[#8B949E]';
                toggleAwakeBtn.className = isKeepAwakeOn
                    ? 'w-full flex items-center justify-between px-2.5 py-2 rounded-lg bg-[#3FB950]/15 border border-[#3FB950] text-xs font-semibold text-[#3FB950]'
                    : 'w-full flex items-center justify-between px-2.5 py-2 rounded-lg bg-[#21262D] border border-[#30363D] text-xs font-semibold text-white';
                if (window.Omni) window.Omni.toast(isKeepAwakeOn ? 'Screen keep awake ON' : 'Screen keep awake OFF');
            });

            document.getElementById('btn-flush-runtime').addEventListener('click', function() {
                if (window.Omni) {
                    window.Omni.python.run('import gc, sys, os; gc.collect(); os.chdir("/storage/emulated/0"); print("Python runtime garbage-collected and reset to /storage/emulated/0")', function(){}, function(){}).then(function() {
                        window.Omni.toast('Runtime Flushed & Reset');
                    });
                }
                closeSidebar();
            });
        })();
    </script>
</body>
</html>
""".trimIndent()

val DEFAULT_STARTER_PLAYBOOK_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Omni Autonomous Playbook</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <style>
        body { background-color: #0D1117; color: #E8EAED; font-family: ui-sans-serif, system-ui, sans-serif; }
    </style>
</head>
<body class="p-4 max-w-2xl mx-auto space-y-4">
    <div class="flex items-center justify-between border-b border-gray-800 pb-3">
        <div>
            <h1 class="text-xl font-bold text-sky-400 flex items-center gap-2">
                <span>🧪</span> Omni Studio Agent
            </h1>
            <p class="text-xs text-gray-400">Google AI Studio Puppet + Nexus Python + Conduit IDE</p>
        </div>
        <span id="status-badge" class="px-2.5 py-1 text-xs font-semibold rounded-full bg-gray-800 text-gray-300 border border-gray-700">
            Idle
        </span>
    </div>

    <!-- Quick Action Deck -->
    <div class="grid grid-cols-2 gap-2 text-xs">
        <button id="btn-studio-init" class="bg-gray-800 hover:bg-gray-700 border border-gray-700 p-2.5 rounded-lg font-medium text-left">
            🧠 1. Connect AI Studio
        </button>
        <button id="btn-python-test" class="bg-gray-800 hover:bg-gray-700 border border-gray-700 p-2.5 rounded-lg font-medium text-left">
            🐍 2. Run Python on Nexus
        </button>
        <button id="btn-ide-pull" class="bg-gray-800 hover:bg-gray-700 border border-gray-700 p-2.5 rounded-lg font-medium text-left">
            📂 3. Pull Conduit Project Files
        </button>
        <button id="btn-full-chain" class="bg-sky-600 hover:bg-sky-500 text-white p-2.5 rounded-lg font-bold text-left">
            ⚡ 4. Run Full Autonomous Chain
        </button>
    </div>

    <!-- Prompt Box -->
    <div class="space-y-1.5">
        <label class="text-xs font-semibold text-gray-400 uppercase tracking-wider">Prompt Instruction</label>
        <textarea id="prompt-input" rows="3" class="w-full bg-[#161B22] border border-gray-700 rounded-lg p-3 text-sm focus:border-sky-500 outline-none" placeholder="Type prompt for AI Studio puppet..."></textarea>
    </div>

    <!-- Execution Log Terminal -->
    <div class="space-y-1.5">
        <div class="flex items-center justify-between">
            <label class="text-xs font-semibold text-gray-400 uppercase tracking-wider">Live Execution Stream</label>
            <button id="btn-clear-log" class="text-xs text-gray-500 hover:text-gray-300">Clear</button>
        </div>
        <pre id="log-console" class="w-full h-64 bg-black/60 border border-gray-800 rounded-lg p-3 text-xs font-mono text-green-400 overflow-y-auto whitespace-pre-wrap">Waiting for commands...</pre>
    </div>

    <script>
        const logEl = document.getElementById('log-console');
        const statusBadge = document.getElementById('status-badge');
        function log(msg, color) {
            color = color || 'text-green-400';
            var time = new Date().toLocaleTimeString();
            logEl.innerHTML += '<div class="' + color + '">[' + time + '] ' + msg + '</div>';
            logEl.scrollTop = logEl.scrollHeight;
        }
        function setStatus(text, color) {
            color = color || 'bg-sky-900 text-sky-300 border-sky-700';
            statusBadge.className = 'px-2.5 py-1 text-xs font-semibold rounded-full border ' + color;
            statusBadge.innerText = text;
        }

        document.getElementById('btn-clear-log').onclick = function() { logEl.innerHTML = ''; };

        // 1. Connect AI Studio
        document.getElementById('btn-studio-init').onclick = async function() {
            try {
                setStatus('Connecting Studio...', 'bg-amber-900 text-amber-300 border-amber-700');
                log("🚀 Initializing AI Studio headless puppet session...");
                await Omni.studio.init('default');
                log("⚙️ Configuring Thinking Level to High and setting prompt preset...");
                await Omni.studio.configure({
                    thinkingLevel: "High",
                    temporaryChat: true,
                    sysTitle: "Omni Agent",
                    sysPrompt: "You are an autonomous engineering assistant connected to Nexus Python and Conduit IDE."
                });
                setStatus('Studio Ready', 'bg-emerald-900 text-emerald-300 border-emerald-700');
                log("✅ AI Studio Puppet is fully armed and ready!", "text-emerald-400");
                Omni.toast("AI Studio Puppet Ready!");
            } catch(e) {
                setStatus('Error', 'bg-rose-900 text-rose-300 border-rose-700');
                log("❌ Studio Error: " + e.message, "text-rose-400");
            }
        };

        // 2. Run Python on Nexus
        document.getElementById('btn-python-test').onclick = async function() {
            try {
                setStatus('Python Running...', 'bg-amber-900 text-amber-300 border-amber-700');
                log("🐍 Dispatching Python script to Nexus microkernel...");
                const code = 'import sys, platform\n' +
                             'print("Python " + platform.python_version() + " active on Nexus!")\n' +
                             'print("Platform: " + sys.platform)\n' +
                             'for i in range(1, 4):\n' +
                             '    print("Counting tick " + str(i) + " from background process...")\n';

                const output = await Omni.python.run(code, function(chunk) {
                    log(chunk.trim(), "text-yellow-300");
                });
                setStatus('Python Done', 'bg-emerald-900 text-emerald-300 border-emerald-700');
                log("🏁 Output:\n" + output, "text-emerald-300");
            } catch(e) {
                setStatus('Error', 'bg-rose-900 text-rose-300 border-rose-700');
                log("❌ Python Error: " + e.message, "text-rose-400");
            }
        };

        // 3. Pull Conduit Project Files
        document.getElementById('btn-ide-pull').onclick = async function() {
            try {
                setStatus('Pulling Files...', 'bg-amber-900 text-amber-300 border-amber-700');
                log("📂 Requesting project files from Conduit IDE...");
                const files = await Omni.ide.pullFiles("Omni", ["README.md", "build.gradle"]);
                setStatus('Files Pulled', 'bg-emerald-900 text-emerald-300 border-emerald-700');
                log("✅ Pulled " + files.length + " file(s) from Conduit IDE!", "text-emerald-400");
                files.forEach(function(f) {
                    var len = f.content ? f.content.length : 0;
                    log("📄 " + f.path + " (" + len + " chars)");
                });
            } catch(e) {
                setStatus('Error', 'bg-rose-900 text-rose-300 border-rose-700');
                log("❌ Pull Error: " + e.message, "text-rose-400");
            }
        };

        // 4. Full Autonomous Chain
        document.getElementById('btn-full-chain').onclick = async function() {
            try {
                setStatus('Executing Chain...', 'bg-purple-900 text-purple-300 border-purple-700');
                log("⚡ Starting Autonomous Pipeline...", "text-purple-300");

                log("1️⃣ Ensuring Studio session...");
                await Omni.studio.init('default');

                const prompt = document.getElementById('prompt-input').value.trim() || 
                    "Analyze this project structure and suggest an optimization.";

                log('2️⃣ Sending prompt to AI Studio: "' + prompt + '"');
                const turn = await Omni.studio.send({ prompt: prompt }, function(progress) {
                    if (progress && progress.thoughts) log("🧠 " + progress.thoughts.slice(-120), "text-gray-400");
                });

                log("🎉 AI Studio Responded:\n" + turn.output, "text-sky-300");
                setStatus('Chain Complete', 'bg-emerald-900 text-emerald-300 border-emerald-700');
                Omni.toast("Autonomous Chain Finished!");
            } catch(e) {
                setStatus('Error', 'bg-rose-900 text-rose-300 border-rose-700');
                log("💥 Chain Error: " + e.message, "text-rose-400");
            }
        };
    </script>
</body>
</html>
""".trimIndent()