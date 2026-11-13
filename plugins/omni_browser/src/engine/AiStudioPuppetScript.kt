package com.omni.plugin.browser.engine

/**
 * Dedicated Google AI Studio Puppet Engine and Batch Automation Scripts.
 * Contains the complete, battle-tested DOM automation logic without omissions.
 */
fun buildAiStudioPuppetScript(): String {
    return """
        (function() {
            if (window.__omniPuppet) return;

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

            async function waitForEl(selector, timeoutMs, intervalMs) {
                const step = intervalMs || 25;
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
                    hostLog('PUPPET_INIT', 'Initializing AI Studio Puppet Engine (Turbo)...');
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

                    while (mountAttempts < 700) {
                        if (mountAttempts % 20 === 0) await dismissInterstitials();
                        promptArea = document.querySelector('textarea[formcontrolname="promptText"], textarea[aria-label="Enter a prompt"], textarea');
                        if (promptArea && isElementVisible(promptArea)) break;
                        await delay(50);
                        mountAttempts++;
                    }

                    if (!promptArea) {
                        const err = 'Failed to locate prompt textarea after 35s.';
                        hostLog('MOUNT_ERR', err);
                        if (window.OmniAutomator && window.OmniAutomator.onPuppetReady) {
                            window.OmniAutomator.onPuppetReady(false, err);
                        }
                        return false;
                    }

                    hostLog('PUPPET_INIT', 'Prompt area mounted. Waiting for AI Studio auth/model boot RPCs to settle...');
                    let warmTicks = 0;
                    while (warmTicks < 150 && !this.aborted) {
                        const warm = (window.__omniWire && window.__omniWire.isSessionWarm) ? window.__omniWire.isSessionWarm() : null;
                        if (warm && warm.ready) {
                            hostLog('PUPPET_INIT', 'Boot settled cleanly (' + (warmTicks * 40) + 'ms, accessToken=' + warm.hasAccessToken + ', models=' + warm.hasListModels + ').');
                            break;
                        }
                        await delay(40);
                        warmTicks++;
                    }
                    await delay(30);
                    hostLog('PUPPET_INIT', 'Session warm and ready!');
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
                    const HEADLESS_TURBO = opts.headlessTurbo === true;

                    try {
                        if (HEADLESS_TURBO && window.__omniEnableHeadlessTurbo) {
                            window.__omniEnableHeadlessTurbo();
                        }

                        await dismissInterstitials();
                        const currentTempState = isTemporaryChatActive();
                        if (TEMPORARY_CHAT !== currentTempState) {
                            hostLog('TEMP_CHAT', (TEMPORARY_CHAT ? 'Enabling' : 'Disabling') + ' Temporary Chat...');
                            updateStatus(TEMPORARY_CHAT ? 'Activating Temporary Chat...' : 'Restoring persistent chat...');
                            const overflowBtn = document.querySelector('.overflow-menu-wrapper button, button[aria-label*="View more actions"]');
                            if (overflowBtn && isElementVisible(overflowBtn)) {
                                overflowBtn.click();
                                const tempChatOption = await waitForEl('button[data-test-incognito-toggle], button[aria-label*="temporary chat"]', 1500, 25);
                                if (tempChatOption) {
                                    tempChatOption.click();
                                    await delay(120);
                                } else {
                                    document.body.click();
                                    await delay(50);
                                }
                            }
                        }

                        const TARGET_MODEL = opts.model || '';
                        const needsSettings = (SYS_PROMPT && SYS_PROMPT.length > 0) || (SYS_TITLE && SYS_TITLE.length > 0) || (THINKING_LEVEL && THINKING_LEVEL !== 'Default') || (TARGET_MODEL && TARGET_MODEL !== 'Default');
                        if (needsSettings) {
                            let sysCard = document.querySelector('button.system-instructions-card, button[data-test-system-instructions-card], ms-system-instructions-panel button');
                            if (!isElementVisible(sysCard)) {
                                updateStatus('Opening Settings Drawer...');
                                const tuneBtn = document.querySelector('button.runsettings-toggle-button, button[aria-label*="Toggle run settings"], button[aria-label*="run settings"]');
                                if (tuneBtn && isElementVisible(tuneBtn)) {
                                    tuneBtn.click();
                                    await waitForEl('ms-thinking-level-setting mat-select, button.system-instructions-card, ms-model-setting mat-select', 1500, 25);
                                }
                            }

                            if (TARGET_MODEL && TARGET_MODEL !== 'Default' && TARGET_MODEL.length > 0) {
                                try {
                                    const modelSelect = document.querySelector('ms-model-setting mat-select, mat-select[aria-label*="Model"], ms-model-switcher button');
                                    if (modelSelect) {
                                        const curModel = (modelSelect.textContent || '').trim().toLowerCase();
                                        const targetClean = TARGET_MODEL.toLowerCase().replace(/[-_]/g, ' ');
                                        if (!curModel.includes(targetClean)) {
                                            hostLog('SETTINGS', 'Switching model to: ' + TARGET_MODEL);
                                            modelSelect.click();
                                            await waitForEl('.mat-mdc-select-panel mat-option, mat-option, .model-option', 1500, 25);
                                            const options = Array.from(document.querySelectorAll('.mat-mdc-select-panel mat-option, mat-option, .model-option'));
                                            const match = options.find(o => {
                                                const oText = (o.textContent || '').trim().toLowerCase();
                                                return oText.includes(TARGET_MODEL.toLowerCase()) || oText.includes(targetClean);
                                            });
                                            if (match) {
                                                match.click();
                                                hostLog('SETTINGS', 'Model switched to: ' + (match.textContent || '').trim());
                                            } else {
                                                document.body.click();
                                            }
                                            await delay(80);
                                        }
                                    }
                                } catch(e) {
                                    hostLog('SETTINGS_WARN', 'Model switch attempt: ' + e.message);
                                }
                            }

                            if (THINKING_LEVEL && THINKING_LEVEL !== 'Default') {
                                try {
                                    const thinkingSelect = document.querySelector('ms-thinking-level-setting mat-select, mat-select[aria-label*="Thinking"]');
                                    if (thinkingSelect) {
                                        const currentVal = (thinkingSelect.textContent || '').trim().toLowerCase();
                                        if (!currentVal.includes(THINKING_LEVEL.toLowerCase())) {
                                            thinkingSelect.click();
                                            await waitForEl('.mat-mdc-select-panel mat-option, mat-option', 1500, 25);
                                            const options = Array.from(document.querySelectorAll('.mat-mdc-select-panel mat-option, mat-option'));
                                            const match = options.find(o => (o.textContent || '').trim().toLowerCase().includes(THINKING_LEVEL.toLowerCase()));
                                            if (match) match.click();
                                            else document.body.click();
                                            await delay(80);
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
                                        await waitForEl('ms-system-instructions', 1800, 25);
                                        let matchedExisting = false;

                                        const sysSelect = document.querySelector('ms-system-instructions mat-select, .select-tooltip-wrapper mat-select');
                                        if (sysSelect) {
                                            const curPreset = (sysSelect.textContent || '').trim().toLowerCase();
                                            if (SYS_TITLE && curPreset === SYS_TITLE.toLowerCase()) {
                                                matchedExisting = true;
                                            } else {
                                                sysSelect.click();
                                                await waitForEl('.mat-mdc-select-panel mat-option, mat-option', 1500, 25);
                                                const options = Array.from(document.querySelectorAll('.mat-mdc-select-panel mat-option, mat-option'));
                                                if (SYS_TITLE && SYS_TITLE.length > 0) {
                                                    const match = options.find(opt => (opt.textContent || '').trim().toLowerCase() === SYS_TITLE.toLowerCase());
                                                    if (match) {
                                                        match.click();
                                                        matchedExisting = true;
                                                        await delay(120);
                                                    }
                                                }
                                                if (!matchedExisting) {
                                                    const createNewOpt = options.find(opt => (opt.textContent || '').includes('Create new instruction'));
                                                    if (createNewOpt) {
                                                        createNewOpt.click();
                                                        await delay(120);
                                                    } else {
                                                        document.body.click();
                                                        await delay(60);
                                                    }
                                                }
                                            }
                                        }

                                        if (!matchedExisting && FALLBACK_ENABLED && SYS_PROMPT && SYS_PROMPT.length > 0) {
                                            const titleInput = document.querySelector('ms-system-instructions .title-row input, ms-system-instructions input[placeholder*="Title"], ms-system-instructions input');
                                            if (titleInput && SYS_TITLE && SYS_TITLE.length > 0 && titleInput.value !== SYS_TITLE) {
                                                safeInjectText(titleInput, SYS_TITLE);
                                                await delay(60);
                                            }
                                            const sysTa = await waitForEl('ms-system-instructions textarea, textarea.in-run-settings, textarea[aria-label*="System"], textarea[placeholder*="instructions"]', 2000, 25);
                                            if (sysTa && sysTa.value !== SYS_PROMPT) {
                                                safeInjectText(sysTa, SYS_PROMPT);
                                                await delay(150);
                                            }
                                        }

                                        let saveWait = 0;
                                        while (saveWait < 30) {
                                            const saveIndicator = document.querySelector('mat-dialog-container .saving-status, .saving-status');
                                            if (saveIndicator && (saveIndicator.textContent || '').toLowerCase().includes('saved')) break;
                                            await delay(100);
                                            saveWait++;
                                        }
                                        const closeBtn = document.querySelector('mat-dialog-container button[mat-dialog-close], mat-dialog-container button[aria-label*="Close"], button[data-test-close-button]');
                                        if (closeBtn) {
                                            closeBtn.click();
                                            await delay(150);
                                        }
                                    }
                                } catch(e) {
                                    hostLog('SYS_PROMPT_ERR', 'System instructions step failed: ' + e.message);
                                }
                            }

                            let promptReady = false;
                            for (let w = 0; w < 10; w++) {
                                const pa = document.querySelector('textarea[formcontrolname="promptText"], textarea[aria-label="Enter a prompt"], textarea');
                                if (pa && isElementVisible(pa)) { promptReady = true; break; }
                                await delay(40);
                            }
                            if (!promptReady) {
                                const openDrawer = document.querySelector('ms-run-settings.expanded, .content-container ms-run-settings:not(.collapsed)');
                                if (openDrawer && isElementVisible(openDrawer)) {
                                    const closeSettingsBtn = openDrawer.querySelector('button[aria-label*="Close run settings"], button[aria-label*="Close"]');
                                    if (closeSettingsBtn && isElementVisible(closeSettingsBtn)) {
                                        closeSettingsBtn.click();
                                        await delay(150);
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
                    const useDeltaMode = !!turnOpts.deltaMode;
                    this.aborted = false;
                    this.isBusy = true;

                    updateStatus((stepLabel + ': Submitting prompt.').trim());
                    const wireTurnToken = (window.__omniWire && window.__omniWire.beginTurn) ? window.__omniWire.beginTurn() : 0;
                    hostLog('WIRE_TURN', 'beginTurn -> token=' + wireTurnToken + ', wireHooked=' + !!(window.__omniWire && window.__omniWire.hasHooked));

                    if (!this.isConfigured) {
                        await dismissInterstitials();
                    }

                    if (attachments && Array.isArray(attachments) && attachments.length > 0) {
                        await injectAttachments(attachments, stepLabel);
                    }

                    let activePromptArea = null;
                    for (let a = 0; a < 100; a++) {
                        const allAreas = Array.from(document.querySelectorAll('textarea[formcontrolname="promptText"], textarea[aria-label="Enter a prompt"], textarea'));
                        activePromptArea = allAreas.length > 0 ? allAreas[allAreas.length - 1] : null;
                        if (activePromptArea && isElementVisible(activePromptArea)) break;
                        if (a % 8 === 0) await dismissInterstitials();
                        await delay(50);
                    }

                    if (activePromptArea && prompt && prompt.trim().length > 0) {
                        safeInjectText(activePromptArea, prompt);
                        await delay(16);
                    }

                    if (!checkUiGenerating()) {
                        updateStatus((stepLabel + ': Preparing to run.').trim());
                        let readyTicks = 0;
                        let submitBtn = null;
                        const maxReadyTicks = Math.max(2400, (attachments ? attachments.length : 0) * 600);

                        while (readyTicks < maxReadyTicks) {
                            if (checkUiGenerating()) break;
                            submitBtn = document.querySelector('ms-run-button button:not(.stoppable), button.ctrl-enter-submits:not(.stoppable), button[type="submit"]:not(.stoppable)');
                            if (submitBtn && isRunButtonReady(submitBtn)) break;
                            await delay(25);
                            readyTicks++;
                        }

                        if (!checkUiGenerating() && submitBtn && isRunButtonReady(submitBtn)) {
                            hostLog('RUN', 'Clicking Run button for ' + stepLabel + '...');
                            submitBtn.click();
                        } else if (!checkUiGenerating() && activePromptArea && (activePromptArea.value || '').trim().length > 0) {
                            hostLog('RUN', 'Fallback Ctrl+Enter for ' + stepLabel + '...');
                            activePromptArea.focus();
                            activePromptArea.dispatchEvent(new KeyboardEvent('keydown', {
                                key: 'Enter', code: 'Enter', keyCode: 13, which: 13, ctrlKey: true, bubbles: true, cancelable: true
                            }));
                        }
                    }

                    updateStatus((stepLabel + ': Generating answers.').trim());
                    let lastOutputText = '';
                    let lastThoughtsText = '';
                    let emittedOutputLen = 0;
                    let emittedThoughtsLen = 0;
                    let contentStaticTicks = 0;
                    let totalTurnTicks = 0;
                    let idleTicks = 0;
                    const MAX_IDLE_TICKS = 280; // ~70s silence at 250ms tick
                    const MAX_TOTAL_TICKS = 8600;
                    let turnRetryCount = 0;
                    const MAX_RETRIES = 2;
                    let currentTurnOutput = '';
                    let latestTurnThoughts = '';
                    let activeWireToken = wireTurnToken;
                    let usedWireStream = false;
                    let loggedWireMiss = false;
                    let fallbackCtrlTriggered = false;

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
                        if (!d || (activeWireToken && d.turnToken !== activeWireToken)) return;
                        notifyWire();
                    };
                    window.addEventListener('__omniWireUpdate', onWireEv);

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
                        if (useDeltaMode && window.OmniAutomator && window.OmniAutomator.onPuppetTurnDelta) {
                            const dt = latestTurnThoughts.slice(emittedThoughtsLen);
                            const doStr = currentTurnOutput.slice(emittedOutputLen);
                            if (dt.length > 0 || doStr.length > 0) {
                                const isFirstAny = (emittedThoughtsLen === 0 && emittedOutputLen === 0);
                                emittedThoughtsLen = latestTurnThoughts.length;
                                emittedOutputLen = currentTurnOutput.length;
                                window.OmniAutomator.onPuppetTurnDelta(dt, doStr, isFirstAny);
                            }
                        } else if (window.OmniAutomator && window.OmniAutomator.onPuppetTurnProgress) {
                            if (latestTurnThoughts.length !== emittedThoughtsLen || currentTurnOutput.length !== emittedOutputLen) {
                                emittedThoughtsLen = latestTurnThoughts.length;
                                emittedOutputLen = currentTurnOutput.length;
                                window.OmniAutomator.onPuppetTurnProgress(latestTurnThoughts, currentTurnOutput);
                            }
                        }
                    }

                    try {
                        while (totalTurnTicks < MAX_TOTAL_TICKS && !this.aborted) {
                            const preWire = (activeWireToken && window.__omniWire) ? window.__omniWire.getTurn(activeWireToken) : null;
                            if (!preWire || !preWire.done) {
                                await waitForWireOrTick(250);
                            }
                            totalTurnTicks++;
                            idleTicks++;

                            const wire = (activeWireToken && window.__omniWire) ? window.__omniWire.getTurn(activeWireToken) : null;

                            if (!wire && !fallbackCtrlTriggered && totalTurnTicks === 6 && !checkUiGenerating()) {
                                if (activePromptArea && (activePromptArea.value || '').trim().length > 0) {
                                    fallbackCtrlTriggered = true;
                                    hostLog('RUN', 'Delayed fallback Ctrl+Enter for ' + stepLabel + '...');
                                    activePromptArea.focus();
                                    activePromptArea.dispatchEvent(new KeyboardEvent('keydown', {
                                        key: 'Enter', code: 'Enter', keyCode: 13, which: 13, ctrlKey: true, bubbles: true, cancelable: true
                                    }));
                                }
                            }

                            if (!wire && !loggedWireMiss && totalTurnTicks === 12) {
                                loggedWireMiss = true;
                                const latestSnap = (window.__omniWire && window.__omniWire.getLatest) ? window.__omniWire.getLatest() : null;
                                hostLog('WIRE_MISS', 'No stream matched token=' + activeWireToken + ' after 3s. latest=' + (latestSnap ? (latestSnap.url + ' (done=' + latestSnap.done + ', out=' + latestSnap.output.length + ')') : 'null'));
                            }

                            if (wire) {
                                usedWireStream = true;
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
                                    const lowerWireErr = String(wire.error).toLowerCase();
                                    const isWireQuota = wire.httpStatus === 429 || wire.statusCode === 8 ||
                                        lowerWireErr.includes('quota') || lowerWireErr.includes('resource exhausted') ||
                                        lowerWireErr.includes('resource_exhausted') || lowerWireErr.includes('rate limit') || lowerWireErr.includes('429');

                                    if (isWireQuota) {
                                        hostLog('WIRE_ERROR', 'Quota exhausted on wire (' + wire.httpStatus + '/' + wire.statusCode + '): ' + wire.error);
                                        this.isBusy = false;
                                        if (window.OmniAutomator && window.OmniAutomator.onPuppetTurnError) {
                                            window.OmniAutomator.onPuppetTurnError('Quota Exhausted (429): ' + wire.error);
                                        }
                                        return { thoughts: latestTurnThoughts, output: '', error: 'Quota Exhausted (429): ' + wire.error };
                                    }

                                    const isWireRetryable = wire.httpStatus >= 500 || wire.httpStatus === 403 || wire.statusCode === 7 ||
                                        lowerWireErr.includes('caller does not have permission') || lowerWireErr.includes('permission denied') ||
                                        lowerWireErr.includes('internal') || lowerWireErr.includes('overloaded') ||
                                        lowerWireErr.includes('unavailable') || lowerWireErr.includes('network');

                                    if (isWireRetryable && turnRetryCount < MAX_RETRIES) {
                                        turnRetryCount++;
                                        hostLog('WIRE_RETRY', 'Transient wire error: "' + wire.error + '". Retrying ' + turnRetryCount + '/' + MAX_RETRIES + '...');
                                        updateStatus((stepLabel + ': Retrying turn, attempt ' + turnRetryCount + '.').trim());
                                        await delay(1200 + (turnRetryCount * 500));
                                        activeWireToken = (window.__omniWire && window.__omniWire.beginTurn) ? window.__omniWire.beginTurn() : 0;
                                        const allTurns = document.querySelectorAll('ms-chat-turn, .chat-turn-container');
                                        const lastTurn = allTurns.length > 0 ? allTurns[allTurns.length - 1] : null;
                                        const rerunBtn = lastTurn ? lastTurn.querySelector('button[name="rerun-button"], button.rerun-button, button[aria-label*="Rerun"]') : null;
                                        if (rerunBtn && isElementVisible(rerunBtn)) rerunBtn.click();
                                        else {
                                            const submitBtn = document.querySelector('ms-run-button button:not(.stoppable)');
                                            if (submitBtn) submitBtn.click();
                                        }
                                        lastOutputText = '';
                                        currentTurnOutput = '';
                                        latestTurnThoughts = '';
                                        emittedOutputLen = 0;
                                        emittedThoughtsLen = 0;
                                        contentStaticTicks = 0;
                                        idleTicks = 0;
                                        continue;
                                    } else {
                                        hostLog('WIRE_ERROR', 'Fatal wire error on ' + stepLabel + ': ' + wire.error);
                                        this.isBusy = false;
                                        if (window.OmniAutomator && window.OmniAutomator.onPuppetTurnError) {
                                            window.OmniAutomator.onPuppetTurnError(wire.error);
                                        }
                                        return { thoughts: latestTurnThoughts, output: '', error: wire.error };
                                    }
                                }

                                if (wire.done && wire.statusCode === 0) {
                                    if (currentTurnOutput.trim().length > 0) {
                                        const firstTokAt = (wire.firstThoughtAt && wire.firstOutputAt) ? Math.min(wire.firstThoughtAt, wire.firstOutputAt) : (wire.firstThoughtAt || wire.firstOutputAt || 0);
                                        const wireTtft = firstTokAt && wire.startedAt ? (firstTokAt - wire.startedAt) : 0;
                                        hostLog('WIRE_STOP', '⚡ DECISION: Wire stream finished cleanly (' + wire.transport + ', chunks=' + wire.chunkCount + ', wireTTFT=' + wireTtft + 'ms, size=' + currentTurnOutput.length + 'c). Zero stillness wait!');
                                        break;
                                    } else {
                                        hostLog('WIRE_FALLBACK', 'Wire stream closed with 0 parsed chars; falling back to DOM scrape.');
                                        let fbTicks = 0;
                                        while (fbTicks < 10 && checkUiGenerating()) {
                                            await delay(150);
                                            fbTicks++;
                                        }
                                        const fbContainers = Array.from(document.querySelectorAll('.chat-turn-container.model, ms-chat-turn .chat-turn-container.model, [data-turn-role="Model"]'));
                                        if (fbContainers.length > 0) {
                                            const fbTxt = getScreenText(fbContainers[fbContainers.length - 1]);
                                            if (fbTxt.length > currentTurnOutput.length) currentTurnOutput = fbTxt;
                                        }
                                        emitTurnUpdate();
                                        if (currentTurnOutput.trim().length > 0) break;
                                    }
                                }

                                // Fast-path: while wire is actively streaming, skip DOM scraping & UI polls entirely!
                                if (wire.active) {
                                    continue;
                                }
                            }

                            // DOM Error Banner Guard (only checked when wire is not actively streaming)
                            const errBanner = document.querySelector('ms-banner .error-banner-message, ms-global-banner');
                            const turnErr = document.querySelector('.chat-turn-container.model:last-of-type .model-error, ms-chat-turn:last-of-type .model-error');
                            const toast = document.querySelector('.cdk-overlay-container .mat-mdc-simple-snack-bar');
                            const errTxt = ((errBanner ? errBanner.innerText : "") + (turnErr ? turnErr.innerText : "") + (toast ? toast.innerText : "")).trim();

                            if (errTxt && !errTxt.toLowerCase().includes('saved')) {
                                const lowerErr = errTxt.toLowerCase();
                                const isQuota = lowerErr.includes('quota') || lowerErr.includes('resource exhausted') || lowerErr.includes('resource_exhausted') || lowerErr.includes('rate limit') || lowerErr.includes('429');
                                if (isQuota) {
                                    hostLog('STUDIO_ERROR', 'Quota exhausted (429): ' + errTxt);
                                    this.isBusy = false;
                                    if (window.OmniAutomator && window.OmniAutomator.onPuppetTurnError) {
                                        window.OmniAutomator.onPuppetTurnError('Quota Exhausted (429): ' + errTxt);
                                    }
                                    return { thoughts: latestTurnThoughts, output: '', error: 'Quota Exhausted (429): ' + errTxt };
                                }

                                const isRetryable = lowerErr.includes('internal error') ||
                                                    lowerErr.includes('permission denied') ||
                                                    lowerErr.includes('overloaded') ||
                                                    lowerErr.includes('try again') ||
                                                    lowerErr.includes('server error');

                                if (isRetryable && turnRetryCount < MAX_RETRIES) {
                                    turnRetryCount++;
                                    hostLog('RETRY', 'Transient error: "' + errTxt + '". Retrying attempt ' + turnRetryCount + '/' + MAX_RETRIES + '...');
                                    updateStatus((stepLabel + ': Retrying turn, attempt ' + turnRetryCount + '.').trim());
                                    await delay(1200 + (turnRetryCount * 500));
                                    activeWireToken = (window.__omniWire && window.__omniWire.beginTurn) ? window.__omniWire.beginTurn() : 0;

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
                                    emittedOutputLen = 0;
                                    emittedThoughtsLen = 0;
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

                            // Fallback DOM scraping only when wire hasn't captured this turn yet
                            if (!wire || (!wire.active && !wire.done)) {
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
                            }

                            const isBackendGenerating = (wire && wire.active) ? true : checkUiGenerating();
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
                                if (usedWireStream) {
                                    if (!checkUiGenerating()) {
                                        contentStaticTicks++;
                                        if (contentStaticTicks >= 8) {
                                            hostLog('WIRE_SAFETY_STOP', '⚡ Wire safety settle: UI stopped generating with ' + currentTurnOutput.length + ' chars captured.');
                                            break;
                                        }
                                    } else {
                                        contentStaticTicks = 0;
                                    }
                                } else {
                                    if (isBackendGenerating) {
                                        contentStaticTicks = 0;
                                    } else {
                                        contentStaticTicks++;
                                    }
                                }
                            }

                            if (!hasContent && idleTicks >= MAX_IDLE_TICKS) {
                                if (turnRetryCount < MAX_RETRIES) {
                                    turnRetryCount++;
                                    hostLog('IDLE_RETRY', 'Silence timeout. Auto-rerun ' + turnRetryCount + '/' + MAX_RETRIES);
                                    idleTicks = 0;
                                    contentStaticTicks = 0;
                                    activeWireToken = (window.__omniWire && window.__omniWire.beginTurn) ? window.__omniWire.beginTurn() : 0;
                                    const allTurns = document.querySelectorAll('ms-chat-turn, .chat-turn-container');
                                    const lastTurn = allTurns.length > 0 ? allTurns[allTurns.length - 1] : null;
                                    const rerunBtn = lastTurn ? lastTurn.querySelector('button[name="rerun-button"], button.rerun-button, button[aria-label*="Rerun"]') : null;
                                    if (rerunBtn && isElementVisible(rerunBtn)) rerunBtn.click();
                                    else {
                                        const retrySubmit = document.querySelector('ms-run-button button:not(.stoppable)');
                                        if (retrySubmit && isRunButtonReady(retrySubmit)) retrySubmit.click();
                                    }
                                    await delay(1200);
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

                            emitTurnUpdate();

                            if (!usedWireStream) {
                                const trimmedOutput = currentTurnOutput.trim();
                                const looksLikeJson = trimmedOutput.startsWith('{') || trimmedOutput.startsWith('[') || trimmedOutput.includes('"solutions"');
                                const balanced = looksLikeJson && isBracketsBalanced(trimmedOutput);
                                const targetTicks = balanced ? 18 : 32;

                                if (hasContent && !isBackendGenerating && contentStaticTicks >= targetTicks && (balanced || trimmedOutput.length >= 20)) {
                                    hostLog('DECISION_STOP', '🛑 DECISION: Fallback DOM turn completed (' + currentTurnOutput.length + ' chars).');
                                    break;
                                }
                            }
                        }
                    } finally {
                        window.removeEventListener('__omniWireUpdate', onWireEv);
                    }

                    this.isBusy = false;
                    if (currentTurnOutput.trim().length === 0) {
                        const emptyErr = 'AI Studio generation ended with empty output';
                        if (window.OmniAutomator && window.OmniAutomator.onPuppetTurnError) {
                            window.OmniAutomator.onPuppetTurnError(emptyErr);
                        }
                        return { thoughts: latestTurnThoughts, output: '', error: emptyErr };
                    }

                    // Cloud Sync Grace Period: Only needed for persistent chats
                    if (!isTemporaryChatActive()) {
                        updateStatus((stepLabel + ': Committing cloud auto-save...').trim());
                        hostLog('DRIVE_SYNC', 'Waiting for Google AI Studio cloud auto-save to commit before finishing turn...');
                        let syncWaitTicks = 0;
                        while (syncWaitTicks < 10 && !this.aborted) {
                            const saveIndicator = document.querySelector('mat-dialog-container .saving-status, .saving-status, [aria-label*="Saved"], [data-test-save-status]');
                            if (saveIndicator && (saveIndicator.textContent || '').toLowerCase().includes('saved')) {
                                hostLog('DRIVE_SYNC', '✅ Google AI Studio marked chat as Saved.');
                                break;
                            }
                            await delay(400);
                            syncWaitTicks++;
                        }
                        await delay(800);
                    }

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