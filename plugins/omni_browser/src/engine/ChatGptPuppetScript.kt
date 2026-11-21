package com.omni.plugin.browser.engine

/**
 * Dedicated ChatGPT Puppet Engine.
 * Operates completely independently of Google AI Studio.
 */
fun buildChatGptPuppetScript(): String {
    return """
        (function() {
            if (window.__omniGptPuppet) return;

            const delay = (ms) => new Promise(r => setTimeout(r, ms));
            const randomDelay = (min, max) => delay(Math.floor(Math.random() * (max - min + 1) + min));

            const DISMISS_DICTIONARY = [
                'got it', 'dismiss', 'dismiss suggestion', 'stay logged out',
                'maybe later', 'not now', 'no thanks', 'close', 'accept', 'continue'
            ];

            function hostLog(tag, msg) {
                try {
                    if (window.OmniGptAutomator && window.OmniGptAutomator.onLog) {
                        window.OmniGptAutomator.onLog(tag, String(msg));
                    }
                } catch(_) {}
            }

            function updateStatus(msg) {
                hostLog('STATUS', msg);
                try {
                    if (window.OmniGptAutomator && window.OmniGptAutomator.onStatus) {
                        window.OmniGptAutomator.onStatus(String(msg));
                    }
                } catch(_) {}
            }

            function isElementVisible(el) {
                if (!el) return false;
                const rect = el.getBoundingClientRect();
                const st = window.getComputedStyle(el);
                return rect.width > 0 && rect.height > 0 && st.visibility !== 'hidden' && st.display !== 'none';
            }

            async function waitForEl(selector, timeoutMs, intervalMs) {
                const step = intervalMs || 30;
                const maxTries = Math.max(1, Math.ceil((timeoutMs || 2000) / step));
                for (let i = 0; i < maxTries; i++) {
                    const el = document.querySelector(selector);
                    if (el && isElementVisible(el)) return el;
                    await delay(step);
                }
                return null;
            }

            async function dismissInterstitials() {
                try {
                    const dialogs = document.querySelectorAll('[role="dialog"], [aria-modal="true"]');
                    for (const dlg of dialogs) {
                        if (!isElementVisible(dlg)) continue;
                        const btns = Array.from(dlg.querySelectorAll('button, [role="button"]'));
                        for (const btn of btns) {
                            if (!isElementVisible(btn)) continue;
                            const txt = (btn.textContent || '').trim().toLowerCase();
                            const aria = (btn.getAttribute('aria-label') || '').trim().toLowerCase();
                            if (DISMISS_DICTIONARY.some(d => txt === d || aria === d || aria.includes('close') || aria.includes('dismiss'))) {
                                hostLog('INTERSTITIAL', 'Dismissing ChatGPT modal via "' + (txt || aria) + '"');
                                btn.click();
                                await delay(250);
                                break;
                            }
                        }
                    }
                } catch(e) {
                    hostLog('INTERSTITIAL_ERR', e.message);
                }
            }

            function findComposerBox() {
                const selectors = [
                    'div.ProseMirror[data-composer-markdown]',
                    'div[aria-label="Ask ChatGPT"][role="textbox"]',
                    'div.ProseMirror[role="textbox"]',
                    'div.ProseMirror[contenteditable="true"]',
                    'textarea#prompt-textarea'
                ];
                for (const sel of selectors) {
                    const el = document.querySelector(sel);
                    if (el && isElementVisible(el)) return el;
                }
                return null;
            }

            function findSendButton() {
                const selectors = [
                    'button[aria-label="Send"]',
                    'button[data-testid="send-button"]',
                    'button#composer-submit-button',
                    '[data-composer-trailing-controls="true"] button[type="submit"]'
                ];
                for (const sel of selectors) {
                    const btn = document.querySelector(sel);
                    if (btn && isElementVisible(btn)) return btn;
                }
                return null;
            }

            function findStopButton() {
                const selectors = [
                    'button[aria-label="Stop"]',
                    'button[data-testid="stop-button"]',
                    'button#stop-button'
                ];
                for (const sel of selectors) {
                    const btn = document.querySelector(sel);
                    if (btn && isElementVisible(btn)) return btn;
                }
                return null;
            }

            function isButtonReady(btn) {
                if (!btn) return false;
                if (btn.disabled || btn.hasAttribute('disabled')) return false;
                if (btn.getAttribute('aria-disabled') === 'true') return false;
                return true;
            }

            function checkUiGenerating() {
                return !!findStopButton();
            }

            function isTemporaryChatActive() {
                try {
                    if (window.location.search.indexOf('temporary-chat=true') !== -1) return true;
                } catch(_) {}
                if (document.querySelector('button[aria-label="Turn off temporary chat"]')) return true;
                const bodyText = (document.body ? document.body.innerText : '').toLowerCase();
                if (bodyText.indexOf("this chat won't appear in your history") !== -1 || bodyText.indexOf("this chat won't appear in your conversation history") !== -1) {
                    return true;
                }
                return false;
            }

            function injectComposerText(el, text) {
                try {
                    el.focus();
                    el.click();

                    if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
                        const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                        const desc = Object.getOwnPropertyDescriptor(proto, 'value');
                        if (desc && desc.set) desc.set.call(el, text);
                        else el.value = text;
                        el.dispatchEvent(new Event('input', { bubbles: true }));
                        el.dispatchEvent(new Event('change', { bubbles: true }));
                        return;
                    }

                    // ProseMirror contenteditable injection
                    const sel = window.getSelection();
                    const range = document.createRange();
                    range.selectNodeContents(el);
                    sel.removeAllRanges();
                    sel.addRange(range);

                    let inserted = false;
                    try {
                        inserted = document.execCommand('insertText', false, text);
                    } catch(_) {}

                    if (!inserted) {
                        el.textContent = text;
                        el.dispatchEvent(new InputEvent('input', {
                            bubbles: true,
                            cancelable: true,
                            inputType: 'insertText',
                            data: text
                        }));
                    }
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                } catch(e) {
                    hostLog('INJECT_ERR', 'ProseMirror inject failed: ' + e.message);
                    try { el.textContent = text; } catch(_) {}
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                }
            }

            const uploadedKeys = new Set();
            async function injectAttachments(attachmentsList, label) {
                if (!attachmentsList || !Array.isArray(attachmentsList) || attachmentsList.length === 0) return;
                const uniqueList = attachmentsList.filter(att => {
                    const key = (att.name || 'file') + '_' + (att.data ? att.data.length : 0);
                    if (uploadedKeys.has(key)) return false;
                    uploadedKeys.add(key);
                    return true;
                });
                if (uniqueList.length === 0) return;

                updateStatus(label + ': Uploading ' + uniqueList.length + ' file(s)...');
                const fileInput = document.querySelector('input[type="file"][aria-label="Attach files"], input[type="file"][aria-label="Attach photos"], input[type="file"]');
                if (!fileInput) {
                    hostLog('ATTACHMENTS_ERR', 'No ChatGPT file input found.');
                    return;
                }

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
                    } catch (e) {
                        hostLog('ATTACHMENTS_ERR', 'Failed preparing ' + att.name + ': ' + e.message);
                    }
                }

                if (dt.files.length > 0) {
                    fileInput.files = dt.files;
                    fileInput.dispatchEvent(new Event('change', { bubbles: true }));
                    fileInput.dispatchEvent(new Event('input', { bubbles: true }));
                    await randomDelay(900, 1400);

                    let waitTicks = 0;
                    const maxTicks = Math.max(60, uniqueList.length * 30);
                    while (waitTicks < maxTicks) {
                        const uploading = document.querySelector('[role="progressbar"], [data-testid*="upload-progress"], circle.animate-spin');
                        if (!uploading) break;
                        await delay(400);
                        waitTicks++;
                    }
                    await delay(400);
                }
            }

            function scrapeLastAssistantTurn() {
                const turns = Array.from(document.querySelectorAll('[data-message-author-role="assistant"]'));
                if (turns.length === 0) return '';
                const last = turns[turns.length - 1];
                const md = last.querySelector('.markdown, .prose') || last;
                return (md.innerText || md.textContent || '').trim();
            }

            window.__omniGptPuppet = {
                isConfigured: false,
                isBusy: false,
                aborted: false,

                abort: function() {
                    this.aborted = true;
                    this.isBusy = false;
                    const stopBtn = findStopButton();
                    if (stopBtn) stopBtn.click();
                    hostLog('PUPPET', 'ChatGPT puppet execution aborted.');
                },

                init: async function() {
                    hostLog('PUPPET_INIT', 'Initializing ChatGPT Puppet Engine...');
                    const href = window.location.href;
                    if (href.includes('auth.openai.com') || href.includes('/auth/login')) {
                        const authErr = 'Not logged into ChatGPT on this profile. Please sign in to chatgpt.com first.';
                        hostLog('AUTH_ERR', authErr);
                        if (window.OmniGptAutomator && window.OmniGptAutomator.onPuppetReady) {
                            window.OmniGptAutomator.onPuppetReady(false, authErr);
                        }
                        return false;
                    }

                    await dismissInterstitials();
                    let attempts = 0;
                    let composer = null;
                    while (attempts < 700) {
                        if (attempts % 20 === 0) await dismissInterstitials();
                        composer = findComposerBox();
                        if (composer) break;
                        await delay(50);
                        attempts++;
                    }

                    if (!composer) {
                        const err = 'Failed to locate ChatGPT ProseMirror composer after 35s.';
                        hostLog('MOUNT_ERR', err);
                        if (window.OmniGptAutomator && window.OmniGptAutomator.onPuppetReady) {
                            window.OmniGptAutomator.onPuppetReady(false, err);
                        }
                        return false;
                    }

                    await delay(100);
                    hostLog('PUPPET_INIT', 'ChatGPT composer mounted and ready!');
                    if (window.OmniGptAutomator && window.OmniGptAutomator.onPuppetReady) {
                        window.OmniGptAutomator.onPuppetReady(true, 'Ready');
                    }
                    return true;
                },

                configure: async function(opts) {
                    opts = opts || {};
                    const TARGET_MODEL = (opts.model || '').trim();
                    const TEMPORARY_CHAT = opts.temporaryChat;
                    const HEADLESS_TURBO = opts.headlessTurbo === true;

                    try {
                        if (HEADLESS_TURBO && window.__omniGptEnableHeadlessTurbo) {
                            window.__omniGptEnableHeadlessTurbo();
                        }

                        await dismissInterstitials();

                        if (typeof TEMPORARY_CHAT === 'boolean') {
                            const curTemp = isTemporaryChatActive();
                            if (TEMPORARY_CHAT !== curTemp) {
                                const btn = document.querySelector(
                                    TEMPORARY_CHAT
                                        ? 'button[aria-label="Temporary chat"]'
                                        : 'button[aria-label="Turn off temporary chat"]'
                                );
                                if (btn && isElementVisible(btn)) {
                                    hostLog('TEMP_CHAT', (TEMPORARY_CHAT ? 'Enabling' : 'Disabling') + ' Temporary Chat via button...');
                                    btn.click();
                                    await delay(350);
                                } else {
                                    hostLog('TEMP_CHAT', 'Temporary Chat toggle button unavailable in current thread state.');
                                }
                            }
                        }

                        if (TARGET_MODEL && TARGET_MODEL.toLowerCase() !== 'default') {
                            try {
                                const pickerBtn = document.querySelector(
                                    'button[aria-label="Select ChatGPT model"], button[aria-label="Select model"], button[data-testid="model-switcher-dropdown-button"]'
                                );
                                if (pickerBtn && isElementVisible(pickerBtn)) {
                                    const curLabel = (pickerBtn.innerText || pickerBtn.getAttribute('aria-label') || '').toLowerCase();
                                    const targetLower = TARGET_MODEL.toLowerCase();
                                    if (!curLabel.includes(targetLower)) {
                                        hostLog('MODEL', 'Opening model picker for: ' + TARGET_MODEL);
                                        pickerBtn.click();
                                        await waitForEl('[role="menu"], [role="listbox"]', 1500, 25);
                                        const items = Array.from(document.querySelectorAll('[role="menuitem"], [role="menuitemradio"], [role="option"]'));
                                        const match = items.find(el => (el.innerText || '').toLowerCase().includes(targetLower));
                                        if (match) {
                                            match.click();
                                            hostLog('MODEL', 'Selected model: ' + (match.innerText || '').trim());
                                            await delay(250);
                                        } else {
                                            document.body.click();
                                        }
                                    }
                                }
                            } catch(e) {
                                hostLog('MODEL_WARN', 'Model switch skipped: ' + e.message);
                            }
                        }

                        this.isConfigured = true;
                        if (window.OmniGptAutomator && window.OmniGptAutomator.onPuppetConfigured) {
                            window.OmniGptAutomator.onPuppetConfigured(true, 'Configured');
                        }
                        return true;
                    } catch(err) {
                        hostLog('CONFIG_ERR', err.message);
                        if (window.OmniGptAutomator && window.OmniGptAutomator.onPuppetConfigured) {
                            window.OmniGptAutomator.onPuppetConfigured(false, err.message);
                        }
                        return false;
                    }
                },

                newChat: async function(opts) {
                    opts = opts || {};
                    const wantTemp = opts.temporaryChat;
                    hostLog('NEW_CHAT', 'Starting fresh ChatGPT session (temporaryChat=' + wantTemp + ')...');
                    const newBtn = document.querySelector('a[aria-label="New chat"], button[aria-label="New chat"]');
                    if (newBtn && isElementVisible(newBtn)) {
                        newBtn.click();
                        await delay(300);
                        let ticks = 0;
                        while (ticks < 80) {
                            if (findComposerBox() && document.querySelectorAll('[data-message-author-role="assistant"]').length === 0) {
                                break;
                            }
                            await delay(50);
                            ticks++;
                        }
                        if (typeof wantTemp === 'boolean') {
                            await this.configure({ temporaryChat: wantTemp });
                        }
                        if (window.OmniGptAutomator && window.OmniGptAutomator.onPuppetNewChat) {
                            window.OmniGptAutomator.onPuppetNewChat(true, 'Ready');
                        }
                        return true;
                    }

                    // Fallback full navigation (onPageFinished -> init() will resolve onNewChatCallback)
                    const targetUrl = wantTemp ? 'https://chatgpt.com/?temporary-chat=true' : 'https://chatgpt.com/';
                    window.location.href = targetUrl;
                    return true;
                },

                executeTurn: async function(turnOpts) {
                    turnOpts = turnOpts || {};
                    const prompt = turnOpts.prompt || '';
                    const attachments = turnOpts.attachments || [];
                    const stepLabel = turnOpts.label || 'Turn';
                    const useDeltaMode = !!turnOpts.deltaMode;
                    this.aborted = false;
                    this.isBusy = true;

                    updateStatus(stepLabel + ': Submitting prompt...');
                    const wireTurnToken = (window.__omniGptWire && window.__omniGptWire.beginTurn) ? window.__omniGptWire.beginTurn() : 0;
                    const baselineAssistantCount = document.querySelectorAll('[data-message-author-role="assistant"]').length;

                    if (attachments && Array.isArray(attachments) && attachments.length > 0) {
                        await injectAttachments(attachments, stepLabel);
                    }

                    let composer = null;
                    for (let i = 0; i < 60; i++) {
                        composer = findComposerBox();
                        if (composer) break;
                        await delay(50);
                    }

                    if (!composer) {
                        this.isBusy = false;
                        const err = 'ChatGPT composer not found.';
                        if (window.OmniGptAutomator && window.OmniGptAutomator.onPuppetTurnError) {
                            window.OmniGptAutomator.onPuppetTurnError(err);
                        }
                        return { thoughts: '', output: '', error: err };
                    }

                    if (prompt && prompt.trim().length > 0) {
                        injectComposerText(composer, prompt);
                        await delay(40);
                    }

                    // Wait briefly for Send button to appear & become enabled
                    let sendBtn = null;
                    for (let w = 0; w < 120; w++) {
                        if (checkUiGenerating()) break;
                        sendBtn = findSendButton();
                        if (sendBtn && isButtonReady(sendBtn)) break;
                        await delay(25);
                    }

                    if (!checkUiGenerating()) {
                        if (sendBtn && isButtonReady(sendBtn)) {
                            hostLog('RUN', 'Clicking ChatGPT Send button...');
                            sendBtn.click();
                        } else {
                            hostLog('RUN', 'Fallback Enter keydown on ProseMirror...');
                            composer.focus();
                            composer.dispatchEvent(new KeyboardEvent('keydown', {
                                key: 'Enter', code: 'Enter', keyCode: 13, which: 13, shiftKey: false, bubbles: true, cancelable: true
                            }));
                        }
                    }

                    updateStatus(stepLabel + ': Streaming response...');
                    let currentTurnOutput = '';
                    let latestTurnThoughts = '';
                    let emittedOutputLen = 0;
                    let emittedThoughtsLen = 0;
                    let totalTicks = 0;
                    let idleTicks = 0;
                    let staticTicks = 0;
                    let usedWire = false;
                    let conversationId = '';
                    const MAX_TICKS = 6000;
                    const MAX_IDLE_TICKS = 360;

                    let wireWakeup = null;
                    const notifyWire = () => {
                        if (wireWakeup) {
                            const fn = wireWakeup;
                            wireWakeup = null;
                            fn();
                        }
                    };
                    const onWireEv = (ev) => {
                        const d = ev && ev.detail;
                        if (!d || (wireTurnToken && d.turnToken !== wireTurnToken)) return;
                        notifyWire();
                    };
                    window.addEventListener('__omniGptWireUpdate', onWireEv);

                    const waitForWireOrTick = (ms) => new Promise((resolve) => {
                        let settled = false;
                        const finish = () => {
                            if (settled) return;
                            settled = true;
                            clearTimeout(tid);
                            resolve();
                        };
                        const tid = setTimeout(() => {
                            if (wireWakeup === finish) wireWakeup = null;
                            finish();
                        }, ms);
                        wireWakeup = finish;
                    });

                    function emitTurnUpdate() {
                        if (useDeltaMode && window.OmniGptAutomator && window.OmniGptAutomator.onPuppetTurnDelta) {
                            const dt = latestTurnThoughts.slice(emittedThoughtsLen);
                            const doStr = currentTurnOutput.slice(emittedOutputLen);
                            if (dt.length > 0 || doStr.length > 0) {
                                const isFirstAny = (emittedThoughtsLen === 0 && emittedOutputLen === 0);
                                emittedThoughtsLen = latestTurnThoughts.length;
                                emittedOutputLen = currentTurnOutput.length;
                                window.OmniGptAutomator.onPuppetTurnDelta(dt, doStr, isFirstAny);
                            }
                        } else if (window.OmniGptAutomator && window.OmniGptAutomator.onPuppetTurnProgress) {
                            if (latestTurnThoughts.length !== emittedThoughtsLen || currentTurnOutput.length !== emittedOutputLen) {
                                emittedThoughtsLen = latestTurnThoughts.length;
                                emittedOutputLen = currentTurnOutput.length;
                                window.OmniGptAutomator.onPuppetTurnProgress(latestTurnThoughts, currentTurnOutput);
                            }
                        }
                    }

                    try {
                        while (totalTicks < MAX_TICKS && !this.aborted) {
                            const preWire = (wireTurnToken && window.__omniGptWire) ? window.__omniGptWire.getTurn(wireTurnToken) : null;
                            if (!preWire || !preWire.done) {
                                await waitForWireOrTick(200);
                            }
                            totalTicks++;
                            idleTicks++;

                            const wire = (wireTurnToken && window.__omniGptWire) ? window.__omniGptWire.getTurn(wireTurnToken) : null;
                            if (wire) {
                                usedWire = true;
                                if (wire.conversationId) conversationId = wire.conversationId;
                                if (wire.thoughts && wire.thoughts.length > latestTurnThoughts.length) {
                                    latestTurnThoughts = wire.thoughts;
                                    idleTicks = 0;
                                }
                                if (wire.output && wire.output.length > currentTurnOutput.length) {
                                    currentTurnOutput = wire.output;
                                    idleTicks = 0;
                                }

                                emitTurnUpdate();

                                if (wire.error) {
                                    this.isBusy = false;
                                    if (window.OmniGptAutomator && window.OmniGptAutomator.onPuppetTurnError) {
                                        window.OmniGptAutomator.onPuppetTurnError(wire.error);
                                    }
                                    return { thoughts: latestTurnThoughts, output: currentTurnOutput, conversationId: conversationId, error: wire.error };
                                }

                                if (wire.done) {
                                    if (currentTurnOutput.trim().length > 0) {
                                        hostLog('WIRE_STOP', 'ChatGPT wire stream completed (' + wire.transport + ', chunks=' + wire.chunkCount + ', len=' + currentTurnOutput.length + ')');
                                        break;
                                    } else {
                                        await delay(200);
                                        const fbTxt = scrapeLastAssistantTurn();
                                        if (fbTxt.length > currentTurnOutput.length) currentTurnOutput = fbTxt;
                                        emitTurnUpdate();
                                        if (currentTurnOutput.trim().length > 0) break;
                                    }
                                }

                                if (wire.active) {
                                    continue;
                                }
                            }

                            // Fallback DOM polling if wire hasn't attached
                            if (!usedWire) {
                                const turns = document.querySelectorAll('[data-message-author-role="assistant"]');
                                if (turns.length > baselineAssistantCount) {
                                    const domTxt = scrapeLastAssistantTurn();
                                    if (domTxt.length > currentTurnOutput.length) {
                                        currentTurnOutput = domTxt;
                                        idleTicks = 0;
                                        staticTicks = 0;
                                        emitTurnUpdate();
                                    } else if (currentTurnOutput.length > 0 && !checkUiGenerating()) {
                                        staticTicks++;
                                        if (staticTicks >= 10) break;
                                    }
                                }
                            }

                            if (!currentTurnOutput && !latestTurnThoughts && idleTicks >= MAX_IDLE_TICKS) {
                                const timeoutErr = 'ChatGPT response timed out with no output.';
                                this.isBusy = false;
                                if (window.OmniGptAutomator && window.OmniGptAutomator.onPuppetTurnError) {
                                    window.OmniGptAutomator.onPuppetTurnError(timeoutErr);
                                }
                                return { thoughts: '', output: '', conversationId: conversationId, error: timeoutErr };
                            }
                        }
                    } finally {
                        window.removeEventListener('__omniGptWireUpdate', onWireEv);
                    }

                    this.isBusy = false;
                    if (this.aborted) {
                        return { thoughts: latestTurnThoughts, output: currentTurnOutput, conversationId: conversationId, error: 'Aborted' };
                    }
                    if (currentTurnOutput.trim().length === 0) {
                        const emptyErr = 'ChatGPT generation ended with empty output.';
                        if (window.OmniGptAutomator && window.OmniGptAutomator.onPuppetTurnError) {
                            window.OmniGptAutomator.onPuppetTurnError(emptyErr);
                        }
                        return { thoughts: latestTurnThoughts, output: '', conversationId: conversationId, error: emptyErr };
                    }

                                        try {
                        if (window.OmniGptAutomator && window.OmniGptAutomator.onTurnMeta) {
                            window.OmniGptAutomator.onTurnMeta(
                                window.location.href || '',
                                isTemporaryChatActive() ? '' : (conversationId || '')
                            );
                        }
                    } catch(_) {}
if (window.OmniGptAutomator && window.OmniGptAutomator.onPuppetTurnComplete) {
                        window.OmniGptAutomator.onPuppetTurnComplete(latestTurnThoughts, currentTurnOutput);
                    }
                    return { thoughts: latestTurnThoughts, output: currentTurnOutput, conversationId: conversationId, error: null };
                }
            };
        })();
    """.trimIndent()
}