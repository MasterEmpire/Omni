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
            cb.onProgress(data);
        }
    };

    function rpcCall(method, args, onProgress) {
        return new Promise((resolve, reject) => {
            if (!window.__OmniStudioLabBridge) {
                return reject(new Error("OmniStudioLabBridge is not attached to this tab"));
            }
            const id = 'call_' + Date.now() + '_' + Math.random().toString(36).substr(2, 6);
            callbacks.set(id, { resolve, reject, onProgress });
            try {
                window.__OmniStudioLabBridge[method](id, ...(args || []));
            } catch(e) {
                callbacks.delete(id);
                reject(e);
            }
        });
    }

    window.Omni = {
        studio: {
            createSession: function(opts) {
                opts = opts || {};
                var profile = opts.profile || opts.profileId || 'default';
                var model = opts.model || '';
                return rpcCall('studioCreateSession', [profile, model]).then(function(res) {
                    var sId = res.sessionId;
                    return {
                        id: sId,
                        profile: profile,
                        configure: function(configOpts) {
                            return rpcCall('studioConfigureSession', [sId, JSON.stringify(configOpts || {})]);
                        },
                        send: function(turnOpts, onProgress) {
                            return rpcCall('studioSendSessionTurn', [sId, JSON.stringify(turnOpts || {})], onProgress);
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
        python: {
            run: function(code, onProgress) {
                return rpcCall('pythonRun', [code], onProgress);
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
    };
    console.log("⚡ [Omni Studio Lab] window.Omni SDK initialized & ready.");
})();
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