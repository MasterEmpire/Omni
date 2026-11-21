package com.omni.plugin.browser.engine

/**
 * Dedicated Puppet Engine for Google Gemini Consumer Web App (gemini.google.com).
 * Completely separate from AiStudioPuppetScript and ChatGptPuppetScript.
 */
fun buildGeminiPuppetScript(): String {
    return """
        (function() {
            if (window.__omniGeminiPuppet) return;

            const delay = (ms) => new Promise(r => setTimeout(r, ms));
            const randomDelay = (min, max) => delay(Math.floor(Math.random() * (max - min + 1) + min));

            const DISMISS_DICTIONARY = [
                'got it', 'dismiss', 'i understand', 'accept', 'accept all', 'agree',
                'close', 'not now', 'no thanks', 'maybe later', 'continue', 'skip'
            ];

            function hostLog(tag, msg) {
                try {
                    if (window.OmniGeminiAutomator && window.OmniGeminiAutomator.onLog) {
                        window.OmniGeminiAutomator.onLog(tag, String(msg));
                    }
                } catch(_) {}
            }

            function updateStatus(msg) {
                hostLog('STATUS', msg);
                try {
                    if (window.OmniGeminiAutomator && window.OmniGeminiAutomator.onStatus) {
                        window.OmniGeminiAutomator.onStatus(String(msg));
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
                    const dialogs = document.querySelectorAll('mat-dialog-container, .mat-mdc-dialog-container, [role="dialog"], [aria-modal="true"]');
                    for (const dlg of dialogs) {
                        if (!isElementVisible(dlg)) continue;
                        const btns = Array.from(dlg.querySelectorAll('button, [role="button"]'));
                        for (const btn of btns) {
                            if (!isElementVisible(btn)) continue;
                            const txt = (btn.textContent || '').trim().toLowerCase();
                            const aria = (btn.getAttribute('aria-label') || '').trim().toLowerCase();
                            if (DISMISS_DICTIONARY.some(d => txt === d || aria === d || aria.includes('close') || aria.includes('dismiss'))) {
                                hostLog('INTERSTITIAL', 'Dismissing Gemini modal via "' + (txt || aria) + '"');
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

            function findQuillEditor() {
                const selectors = [
                    'rich-textarea .ql-editor[role="textbox"]',
                    'div.ql-editor[aria-label="Enter a prompt for Gemini"]',
                    'div[aria-label="Enter a prompt for Gemini"][role="textbox"]',
                    'rich-textarea [contenteditable="true"]'
                ];
                for (const sel of selectors) {
                    const el = document.querySelector(sel);
                    if (el && isElementVisible(el)) return el;
                }
                return null;
            }

            function findSendButton() {
                const selectors = [
                    'button[aria-label="Send message"]',
                    'button[aria-label="Submit"]',
                    'button.send-button',
                    'button.branded-send-primary-button',
                    'button.submit-button'
                ];
                for (const sel of selectors) {
                    const btn = document.querySelector(sel);
                    if (btn && isElementVisible(btn)) return btn;
                }
                return null;
            }

            function findStopButton() {
                const selectors = [
                    'button[aria-label="Stop response"]',
                    'button[aria-label="Cancel send"]',
                    'button.stop-button'
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
                if (btn.classList.contains('disabled') || btn.classList.contains('mat-mdc-button-disabled')) return false;
                return true;
            }

            function checkUiGenerating() {
                return !!findStopButton();
            }

            function isTemporaryChatActive() {
                if (document.querySelector('button[aria-label="Turn off temporary chat"]')) return true;
                try {
                    if (window.location.search.indexOf('temporary') !== -1) return true;
                } catch(_) {}
                return false;
            }

            function injectQuillText(el, text) {
                try {
                    el.focus();
                    el.click();

                    // Clear existing blank/draft state & insert cleanly via execCommand for Quill sync
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
                        el.innerHTML = '';
                        const p = document.createElement('p');
                        p.textContent = text;
                        el.appendChild(p);
                        el.classList.remove('ql-blank');
                        el.dispatchEvent(new InputEvent('input', {
                            bubbles: true,
                            cancelable: true,
                            inputType: 'insertText',
                            data: text
                        }));
                    }
                    el.classList.remove('ql-blank');
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                } catch(e) {
                    hostLog('INJECT_ERR', 'Quill inject failed: ' + e.message);
                    try { el.textContent = text; } catch(_) {}
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                }
            }

            function prepareFileList(attachmentsList) {
                const dt = new DataTransfer();
                for (const att of attachmentsList) {
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
                return dt;
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
                const dt = prepareFileList(uniqueList);
                if (dt.files.length === 0) return;

                let fileInput = document.querySelector('input[type="file"]');

                // Gemini mounts/triggers <input type="file"> dynamically via Upload & tools menu
                if (!fileInput) {
                    let capturedInput = null;
                    const origInputClick = HTMLInputElement.prototype.click;
                    HTMLInputElement.prototype.click = function() {
                        if (this.type === 'file') {
                            capturedInput = this;
                            hostLog('ATTACHMENTS', 'Intercepted dynamic file input click cleanly.');
                            return;
                        }
                        return origInputClick.apply(this, arguments);
                    };

                    try {
                        const uploadToolsBtn = document.querySelector('button[aria-label="Upload & tools"]');
                        if (uploadToolsBtn && isElementVisible(uploadToolsBtn)) {
                            uploadToolsBtn.click();
                            await waitForEl('button[aria-label*="Upload files"], button[aria-label*="Add photos"], [role="menuitem"]', 1500, 25);
                            const menuItems = Array.from(document.querySelectorAll('button[role="menuitem"], [mat-menu-item], button[aria-label*="Upload files"], button[aria-label*="Add photos"], button[aria-label*="Files"]'));
                            const uploadOpt = menuItems.find(el => {
                                const txt = (el.textContent || '').toLowerCase();
                                const aria = (el.getAttribute('aria-label') || '').toLowerCase();
                                return aria.includes('upload files') || aria.includes('add photos') || txt.includes('upload files') || txt.includes('add photos');
                            });
                            if (uploadOpt) {
                                uploadOpt.click();
                                await delay(200);
                            } else {
                                document.body.click();
                            }
                        }
                        fileInput = capturedInput || document.querySelector('input[type="file"]');
                    } finally {
                        HTMLInputElement.prototype.click = origInputClick;
                    }
                }

                if (fileInput) {
                    fileInput.files = dt.files;
                    fileInput.dispatchEvent(new Event('change', { bubbles: true }));
                    fileInput.dispatchEvent(new Event('input', { bubbles: true }));
                } else {
                    // Fallback: Drag & drop directly onto composer surface
                    const target = findQuillEditor() || document.body;
                    target.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt }));
                }

                await randomDelay(900, 1400);
                let waitTicks = 0;
                const maxTicks = Math.max(60, uniqueList.length * 30);
                while (waitTicks < maxTicks) {
                    const uploading = document.querySelector('mat-progress-bar, mat-spinner, [role="progressbar"]');
                    if (!uploading) break;
                    await delay(400);
                    waitTicks++;
                }
                await delay(400);
            }

            function scrapeLastModelResponse() {
                const responses = Array.from(document.querySelectorAll('model-response, model-response-content, .model-response-text'));
                if (responses.length === 0) return '';
                const last = responses[responses.length - 1];
                const content = last.querySelector('message-content, .model-response-text, .markdown') || last;
                return (content.innerText || content.textContent || '').trim();
            }

            window.__omniGeminiPuppet = {
                isConfigured: false,
                isBusy: false,
                aborted: false,

                abort: function() {
                    this.aborted = true;
                    this.isBusy = false;
                    const stopBtn = findStopButton();
                    if (stopBtn) stopBtn.click();
                    hostLog('PUPPET', 'Gemini puppet execution aborted.');
                },

                init: async function() {
                    hostLog('PUPPET_INIT', 'Initializing Gemini Puppet Engine...');
                    const href = window.location.href;
                    if (href.includes('accounts.google.com') || href.includes('/signin/')) {
                        const authErr = 'Not logged into Google Gemini on this profile. Please sign in to gemini.google.com first.';
                        hostLog('AUTH_ERR', authErr);
                        if (window.OmniGeminiAutomator && window.OmniGeminiAutomator.onPuppetReady) {
                            window.OmniGeminiAutomator.onPuppetReady(false, authErr);
                        }
                        return false;
                    }

                    await dismissInterstitials();
                    let attempts = 0;
                    let editor = null;
                    while (attempts < 700) {
                        if (attempts % 20 === 0) await dismissInterstitials();
                        editor = findQuillEditor();
                        if (editor) break;
                        await delay(50);
                        attempts++;
                    }

                    if (!editor) {
                        const err = 'Failed to locate Gemini Quill composer after 35s.';
                        hostLog('MOUNT_ERR', err);
                        if (window.OmniGeminiAutomator && window.OmniGeminiAutomator.onPuppetReady) {
                            window.OmniGeminiAutomator.onPuppetReady(false, err);
                        }
                        return false;
                    }

                    await delay(100);
                    hostLog('PUPPET_INIT', 'Gemini Quill composer mounted and ready!');
                    if (window.OmniGeminiAutomator && window.OmniGeminiAutomator.onPuppetReady) {
                        window.OmniGeminiAutomator.onPuppetReady(true, 'Ready');
                    }
                    return true;
                },

                configure: async function(opts) {
                    opts = opts || {};
                    const TARGET_MODEL = (opts.model || '').trim();
                    const TEMPORARY_CHAT = opts.temporaryChat;
                    const HEADLESS_TURBO = opts.headlessTurbo === true;

                    try {
                        if (HEADLESS_TURBO && window.__omniGeminiEnableHeadlessTurbo) {
                            window.__omniGeminiEnableHeadlessTurbo();
                        }

                        await dismissInterstitials();

                        if (typeof TEMPORARY_CHAT === 'boolean') {
                            const curTemp = isTemporaryChatActive();
                            if (TEMPORARY_CHAT !== curTemp) {
                                let tempBtn = document.querySelector(
                                    TEMPORARY_CHAT
                                        ? 'button[aria-label="Turn on temporary chat"]'
                                        : 'button[aria-label="Turn off temporary chat"]'
                                );
                                // If sidebar is collapsed, open Main menu to reveal temporary chat button
                                if (!tempBtn || !isElementVisible(tempBtn)) {
                                    const menuBtn = document.querySelector('button[aria-label="Main menu"], button[aria-label="Open sidebar"]');
                                    if (menuBtn && isElementVisible(menuBtn)) {
                                        menuBtn.click();
                                        await delay(300);
                                        tempBtn = document.querySelector(
                                            TEMPORARY_CHAT
                                                ? 'button[aria-label="Turn on temporary chat"]'
                                                : 'button[aria-label="Turn off temporary chat"]'
                                        );
                                    }
                                }
                                if (tempBtn && isElementVisible(tempBtn)) {
                                    hostLog('TEMP_CHAT', (TEMPORARY_CHAT ? 'Enabling' : 'Disabling') + ' Gemini Temporary Chat...');
                                    tempBtn.click();
                                    await delay(400);
                                } else {
                                    hostLog('TEMP_CHAT', 'Temporary Chat toggle button not found in current state.');
                                }
                            }
                        }

                        if (TARGET_MODEL && TARGET_MODEL.toLowerCase() !== 'default') {
                            try {
                                const pickerBtn = document.querySelector(
                                    'button[aria-label*="Open mode picker"], [data-test-id="bard-mode-menu-button"] button, bard-mode-switcher button'
                                );
                                if (pickerBtn && isElementVisible(pickerBtn)) {
                                    const curLabel = (pickerBtn.innerText || pickerBtn.getAttribute('aria-label') || '').toLowerCase();
                                    const targetLower = TARGET_MODEL.toLowerCase();
                                    if (!curLabel.includes(targetLower)) {
                                        hostLog('MODEL', 'Opening Gemini mode picker for: ' + TARGET_MODEL);
                                        pickerBtn.click();
                                        await waitForEl('button.bard-mode-list-button[role="menuitem"], .mat-mdc-menu-panel [role="menuitem"]', 1500, 25);
                                        const items = Array.from(document.querySelectorAll('button.bard-mode-list-button[role="menuitem"], .mat-mdc-menu-panel [role="menuitem"]'));
                                        const match = items.find(el => {
                                            const titleEl = el.querySelector('[data-test-id="bard-mode-menu-title"], .title') || el;
                                            return (titleEl.innerText || el.innerText || '').toLowerCase().includes(targetLower);
                                        });
                                        if (match) {
                                            match.click();
                                            hostLog('MODEL', 'Selected Gemini mode: ' + (match.innerText || '').trim());
                                            await delay(250);
                                        } else {
                                            document.body.click();
                                        }
                                    }
                                }
                            } catch(e) {
                                hostLog('MODEL_WARN', 'Mode switch skipped: ' + e.message);
                            }
                        }

                        this.isConfigured = true;
                        if (window.OmniGeminiAutomator && window.OmniGeminiAutomator.onPuppetConfigured) {
                            window.OmniGeminiAutomator.onPuppetConfigured(true, 'Configured');
                        }
                        return true;
                    } catch(err) {
                        hostLog('CONFIG_ERR', err.message);
                        if (window.OmniGeminiAutomator && window.OmniGeminiAutomator.onPuppetConfigured) {
                            window.OmniGeminiAutomator.onPuppetConfigured(false, err.message);
                        }
                        return false;
                    }
                },

                newChat: async function(opts) {
                    opts = opts || {};
                    const wantTemp = opts.temporaryChat;
                    hostLog('NEW_CHAT', 'Starting fresh Gemini chat (temporaryChat=' + wantTemp + ')...');
                    const newBtn = document.querySelector('[data-test-id="new-chat-button"] a, [data-test-id="new-chat-button"], a[aria-label="New chat"], [data-test-id="side-nav-sparkle-button"]');
                    if (newBtn && isElementVisible(newBtn)) {
                        newBtn.click();
                        await delay(300);
                        let ticks = 0;
                        while (ticks < 80) {
                            if (findQuillEditor() && document.querySelectorAll('model-response').length === 0) {
                                break;
                            }
                            await delay(50);
                            ticks++;
                        }
                        if (typeof wantTemp === 'boolean') {
                            await this.configure({ temporaryChat: wantTemp });
                        }
                        if (window.OmniGeminiAutomator && window.OmniGeminiAutomator.onPuppetNewChat) {
                            window.OmniGeminiAutomator.onPuppetNewChat(true, 'Ready');
                        }
                        return true;
                    }

                    window.location.href = 'https://gemini.google.com/app';
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
                    const wireTurnToken = (window.__omniGeminiWire && window.__omniGeminiWire.beginTurn) ? window.__omniGeminiWire.beginTurn() : 0;
                    const baselineResponseCount = document.querySelectorAll('model-response').length;

                    if (attachments && Array.isArray(attachments) && attachments.length > 0) {
                        await injectAttachments(attachments, stepLabel);
                    }

                    let editor = null;
                    for (let i = 0; i < 60; i++) {
                        editor = findQuillEditor();
                        if (editor) break;
                        await delay(50);
                    }

                    if (!editor) {
                        this.isBusy = false;
                        const err = 'Gemini Quill composer not found.';
                        if (window.OmniGeminiAutomator && window.OmniGeminiAutomator.onPuppetTurnError) {
                            window.OmniGeminiAutomator.onPuppetTurnError(err);
                        }
                        return { thoughts: '', output: '', error: err };
                    }

                    if (prompt && prompt.trim().length > 0) {
                        injectQuillText(editor, prompt);
                        await delay(50);
                    }

                    let sendBtn = null;
                    for (let w = 0; w < 120; w++) {
                        if (checkUiGenerating()) break;
                        sendBtn = findSendButton();
                        if (sendBtn && isButtonReady(sendBtn)) break;
                        await delay(25);
                    }

                    if (!checkUiGenerating()) {
                        if (sendBtn && isButtonReady(sendBtn)) {
                            hostLog('RUN', 'Clicking Gemini Send button...');
                            sendBtn.click();
                        } else {
                            hostLog('RUN', 'Fallback Enter keydown on Quill...');
                            editor.focus();
                            editor.dispatchEvent(new KeyboardEvent('keydown', {
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
                    window.addEventListener('__omniGeminiWireUpdate', onWireEv);

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
                        if (useDeltaMode && window.OmniGeminiAutomator && window.OmniGeminiAutomator.onPuppetTurnDelta) {
                            const dt = latestTurnThoughts.slice(emittedThoughtsLen);
                            const doStr = currentTurnOutput.slice(emittedOutputLen);
                            if (dt.length > 0 || doStr.length > 0) {
                                const isFirstAny = (emittedThoughtsLen === 0 && emittedOutputLen === 0);
                                emittedThoughtsLen = latestTurnThoughts.length;
                                emittedOutputLen = currentTurnOutput.length;
                                window.OmniGeminiAutomator.onPuppetTurnDelta(dt, doStr, isFirstAny);
                            }
                        } else if (window.OmniGeminiAutomator && window.OmniGeminiAutomator.onPuppetTurnProgress) {
                            if (latestTurnThoughts.length !== emittedThoughtsLen || currentTurnOutput.length !== emittedOutputLen) {
                                emittedThoughtsLen = latestTurnThoughts.length;
                                emittedOutputLen = currentTurnOutput.length;
                                window.OmniGeminiAutomator.onPuppetTurnProgress(latestTurnThoughts, currentTurnOutput);
                            }
                        }
                    }

                    try {
                        while (totalTicks < MAX_TICKS && !this.aborted) {
                            const preWire = (wireTurnToken && window.__omniGeminiWire) ? window.__omniGeminiWire.getTurn(wireTurnToken) : null;
                            if (!preWire || !preWire.done) {
                                await waitForWireOrTick(200);
                            }
                            totalTicks++;
                            idleTicks++;

                            const wire = (wireTurnToken && window.__omniGeminiWire) ? window.__omniGeminiWire.getTurn(wireTurnToken) : null;
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
                                    if (window.OmniGeminiAutomator && window.OmniGeminiAutomator.onPuppetTurnError) {
                                        window.OmniGeminiAutomator.onPuppetTurnError(wire.error);
                                    }
                                    return { thoughts: latestTurnThoughts, output: currentTurnOutput, conversationId: conversationId, error: wire.error };
                                }

                                if (wire.done) {
                                    if (currentTurnOutput.trim().length > 0) {
                                        hostLog('WIRE_STOP', 'Gemini wire stream completed (' + wire.transport + ', chunks=' + wire.chunkCount + ', len=' + currentTurnOutput.length + ')');
                                        break;
                                    } else {
                                        await delay(200);
                                        const fbTxt = scrapeLastModelResponse();
                                        if (fbTxt.length > currentTurnOutput.length) currentTurnOutput = fbTxt;
                                        emitTurnUpdate();
                                        if (currentTurnOutput.trim().length > 0) break;
                                    }
                                }

                                if (wire.active) {
                                    continue;
                                }
                            }

                            // Fallback DOM scraping if wire stream wasn't caught
                            if (!usedWire) {
                                const responses = document.querySelectorAll('model-response');
                                if (responses.length > baselineResponseCount) {
                                    const domTxt = scrapeLastModelResponse();
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
                                const timeoutErr = 'Gemini response timed out with no output.';
                                this.isBusy = false;
                                if (window.OmniGeminiAutomator && window.OmniGeminiAutomator.onPuppetTurnError) {
                                    window.OmniGeminiAutomator.onPuppetTurnError(timeoutErr);
                                }
                                return { thoughts: '', output: '', conversationId: conversationId, error: timeoutErr };
                            }
                        }
                    } finally {
                        window.removeEventListener('__omniGeminiWireUpdate', onWireEv);
                    }

                    this.isBusy = false;
                    if (this.aborted) {
                        return { thoughts: latestTurnThoughts, output: currentTurnOutput, conversationId: conversationId, error: 'Aborted' };
                    }
                    if (currentTurnOutput.trim().length === 0) {
                        const emptyErr = 'Gemini generation ended with empty output.';
                        if (window.OmniGeminiAutomator && window.OmniGeminiAutomator.onPuppetTurnError) {
                            window.OmniGeminiAutomator.onPuppetTurnError(emptyErr);
                        }
                        return { thoughts: latestTurnThoughts, output: '', conversationId: conversationId, error: emptyErr };
                    }

                                        try {
                        if (window.OmniGeminiAutomator && window.OmniGeminiAutomator.onTurnMeta) {
                            window.OmniGeminiAutomator.onTurnMeta(
                                window.location.href || '',
                                isTemporaryChatActive() ? '' : (conversationId || '')
                            );
                        }
                    } catch(_) {}
if (window.OmniGeminiAutomator && window.OmniGeminiAutomator.onPuppetTurnComplete) {
                        window.OmniGeminiAutomator.onPuppetTurnComplete(latestTurnThoughts, currentTurnOutput);
                    }
                    return { thoughts: latestTurnThoughts, output: currentTurnOutput, conversationId: conversationId, error: null };
                }
            };
        })();
    """.trimIndent()
}