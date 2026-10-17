package com.omni.plugin.keyboard.state

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.*
import com.omni.hub.api.KeyboardHost
import com.omni.plugin.keyboard.model.KeyboardMode
import com.omni.plugin.keyboard.model.ShiftState

import android.media.AudioManager
import com.omni.plugin.keyboard.engine.*
import com.omni.plugin.keyboard.model.AmharicData
import kotlinx.coroutines.*
import java.io.File

class KeyboardState(val host: KeyboardHost) {

    var mode by mutableStateOf(KeyboardMode.ALPHA)
    var shiftState by mutableStateOf(ShiftState.LOWERCASE)
    var isAmharic by mutableStateOf(false)
    var isDarkTheme by mutableStateOf(true)
    var showNumberRow by mutableStateOf(false)
    var isToolbarOverride by mutableStateOf(false)
    var heightScalePercent by mutableIntStateOf(100)

    var cursorPosition by mutableIntStateOf(0)
    var suggestions = mutableStateListOf<Prediction>()
    var isPunctPopupVisible by mutableStateOf(false)

    // Delete Suggestion Confirmation
    var isDeleteSuggestionMode by mutableStateOf(false)
    var wordToDelete by mutableStateOf("")

    // Amharic Syllable State
    private var amharicBuffer = ""
    private var expectedCursorPos = 0

    // Autocorrect Engine State
    var isAutocorrectEnabled by mutableStateOf(true)
    private var isAutocorrectSuppressed = false
    private var currentBestPrediction: String? = null
    private var lastAutocorrect: Pair<String, String>? = null
    private var lastSpaceTime = 0L

    // Coroutine Scope & Engine
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var searchJob: Job? = null
    val predictor: TextPredictor = TextPredictor(
        dataDir = File(host.getHostContext().filesDir, "omni_keyboard"),
        persistentDictDir = host.getPersistentDictDir()
    ) { msg -> host.getHostBridge().log("PRED", msg) }

    val clipHistoryDir: File = File(host.getHostContext().filesDir, "omni_keyboard")

    init {
        predictor.loadDictionary()
    }

    fun onDestroy() {
        predictor.flush()
        scope.cancel()
    }

    private val separators = setOf(".", ",", "!", "?", ";", ":", "።", "፣", "፤", "፥", "፦", "፧")

    var onSandboxCommit: ((String) -> Unit)? = null
    var onSandboxDelete: (() -> Unit)? = null
    var onSandboxEnter: (() -> Unit)? = null

    fun cycleShift() {
        shiftState = when (shiftState) {
            ShiftState.LOWERCASE -> ShiftState.SHIFTED
            ShiftState.SHIFTED -> ShiftState.CAPS_LOCKED
            ShiftState.CAPS_LOCKED -> ShiftState.LOWERCASE
        }
        host.getHostBridge().vibrate(20L)
    }

    fun toggleLanguage() {
        isAmharic = !isAmharic
        host.getHostBridge().vibrate(40L)
        host.getHostBridge().showToast(if (isAmharic) "አማርኛ (Amharic)" else "English (US)")
    }

    fun toggleTheme() {
        isDarkTheme = !isDarkTheme
        host.getHostBridge().vibrate(15L)
    }

    fun onUpdateSelection(newSelEnd: Int) {
        cursorPosition = newSelEnd
        if (isAmharic && amharicBuffer.isNotEmpty() && newSelEnd != expectedCursorPos) {
            amharicBuffer = ""
        }
        updateSuggestions()
    }

    fun commitChar(char: String) {
        val text = if (shiftState != ShiftState.LOWERCASE && char.length == 1) {
            char.uppercase()
        } else {
            char
        }

        val ic = host.getCurrentInputConnection()
        if (ic == null) {
            onSandboxCommit?.invoke(text)
            host.getHostBridge().vibrate(12L)
            if (shiftState == ShiftState.SHIFTED) shiftState = ShiftState.LOWERCASE
            return
        }

        if (separators.contains(text)) {
            val before = ic.getTextBeforeCursor(1, 0)
            if (before == " ") {
                ic.deleteSurroundingText(1, 0)
            }
        }

        if (text.trim().isNotEmpty()) {
            isAutocorrectSuppressed = false
            lastAutocorrect = null
        }

        if (isAmharic) {
            val newBuffer = amharicBuffer + text
            fun resolve(key: String): String? = AmharicData.map[key] ?: AmharicData.map[key.lowercase()]

            val extendedMatch = resolve(newBuffer)
            if (extendedMatch != null) {
                DiscoveryLogger.log(host.getHostContext(), "AMHARIC_MORPH", mapOf("from" to amharicBuffer, "to" to extendedMatch))
                ic.beginBatchEdit()
                var deleted = 0
                if (amharicBuffer.isNotEmpty()) {
                    ic.deleteSurroundingText(1, 0)
                    deleted = 1
                }
                ic.commitText(extendedMatch, 1)
                ic.endBatchEdit()

                expectedCursorPos = cursorPosition - deleted + extendedMatch.length
                amharicBuffer = newBuffer
                host.getHostBridge().vibrate(14L)
                updateSuggestions()
                return
            }

            amharicBuffer = ""
            val freshMatch = resolve(text)
            if (freshMatch != null) {
                ic.commitText(freshMatch, 1)
                expectedCursorPos = cursorPosition + freshMatch.length
                amharicBuffer = text
                host.getHostBridge().vibrate(14L)
                updateSuggestions()
                return
            }
        }

        DiscoveryLogger.log(host.getHostContext(), "COMMIT", mapOf("val" to text, "lang" to if(isAmharic) "am" else "en"))
        ic.beginBatchEdit()
        try {
            ic.commitText(text, 1)
            expectedCursorPos = cursorPosition + text.length
        } finally {
            ic.endBatchEdit()
        }

        host.getHostBridge().vibrate(12L)
        if (shiftState == ShiftState.SHIFTED && text.length == 1 && Character.isUpperCase(text[0])) {
            shiftState = ShiftState.LOWERCASE
        }
        updateSuggestions()
    }

    fun handleDelete() {
        DiscoveryLogger.log(host.getHostContext(), "DELETE", mapOf("pos" to cursorPosition, "lang" to if(isAmharic) "am" else "en"))
        amharicBuffer = ""
        val ic = host.getCurrentInputConnection()
        if (ic == null) {
            onSandboxDelete?.invoke()
            host.getHostBridge().vibrate(25L)
            return
        }

        // Revert Autocorrect logic
        if (lastAutocorrect != null) {
            val (original, replacement) = lastAutocorrect!!
            val expectedString = "$replacement "
            val text = ic.getTextBeforeCursor(expectedString.length, 0)?.toString()

            if (text == expectedString) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(expectedString.length, 0)
                ic.commitText(original, 1)
                ic.endBatchEdit()

                lastAutocorrect = null
                isAutocorrectSuppressed = true
                host.getHostBridge().vibrate(30L)
                updateSuggestions()
                return
            }
        }

        isAutocorrectSuppressed = false
        lastAutocorrect = null
        val selectedText = ic.getSelectedText(0)
        if (selectedText.isNullOrEmpty()) {
            ic.deleteSurroundingText(1, 0)
        } else {
            ic.commitText("", 1)
        }
        host.getHostBridge().vibrate(20L)
        updateSuggestions()
    }

    fun handleSpace() {
        val ic = host.getCurrentInputConnection()
        if (ic == null) {
            onSandboxCommit?.invoke(" ")
            host.getHostBridge().vibrate(15L)
            return
        }

        val rawTypedWord = ic.getTextBeforeCursor(50, 0)?.toString()?.substringAfterLast(" ")?.substringAfterLast("\n") ?: ""
        var processedAutoCorrect = false

        // Rule 1: 'i' Capitalization
        if (!isAmharic && rawTypedWord == "i") {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText("I ", 1)
            ic.endBatchEdit()
            processedAutoCorrect = true
        }

        val charBefore = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        val isSeparatorBefore = separators.contains(charBefore)

        if (!processedAutoCorrect && !isSeparatorBefore && isAutocorrectEnabled && !isAmharic && currentBestPrediction != null && !isAutocorrectSuppressed) {
            val bestPred = suggestions.firstOrNull()
            val replacement = bestPred?.text ?: ""
            val isConfident = bestPred?.shouldAutoCorrect == true

            val isRawWordValid = suggestions.any { it.text.equals(rawTypedWord, ignoreCase = true) } || predictor.isValidWord(rawTypedWord)
            val isException = replacement == "I"

            if (isConfident && rawTypedWord.isNotEmpty() && rawTypedWord != replacement && (!isRawWordValid || isException)) {
                DiscoveryLogger.log(host.getHostContext(), "AUTOCORRECT", mapOf("typed" to rawTypedWord, "fixed" to replacement, "lang" to "en"))
                ic.beginBatchEdit()
                ic.deleteSurroundingText(rawTypedWord.length, 0)
                ic.commitText(replacement + " ", 1)
                ic.endBatchEdit()

                lastAutocorrect = Pair(rawTypedWord, replacement)
                processedAutoCorrect = true

                scope.launch(Dispatchers.Default) {
                    predictor.learnWord(replacement, null, null, isExplicit = true)
                }
            }
        }

        if (!processedAutoCorrect) {
            if (rawTypedWord.isNotEmpty()) {
                val fullText = ic.getTextBeforeCursor(100, 0)?.toString() ?: ""
                val tokens = fullText.split(" ").filter { it.isNotBlank() }
                val idx = tokens.size - 1
                val prev = if (idx >= 1) tokens[idx - 1].trim { !it.isLetterOrDigit() } else null
                val pp = if (idx >= 2) tokens[idx - 2].trim { !it.isLetterOrDigit() } else null

                scope.launch(Dispatchers.Default) {
                    predictor.learnWord(rawTypedWord, prev, pp, isExplicit = false)
                }
            }

            val now = System.currentTimeMillis()
            if (now - lastSpaceTime < 300) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpaceTime = 0
            } else {
                ic.commitText(" ", 1)
                lastSpaceTime = now
            }
        }

        host.getHostBridge().vibrate(15L)
        updateSuggestions()
    }

    fun handleEnter() {
        val ic = host.getCurrentInputConnection()
        if (ic == null) {
            onSandboxEnter?.invoke()
            host.getHostBridge().vibrate(25L)
            return
        }

        val info = host.getCurrentEditorInfo()
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val handled = when (action) {
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE -> host.performHostEditorAction(action)
            else -> false
        }
        if (!handled) ic.commitText("\n", 1)
        host.getHostBridge().vibrate(25L)
    }

    fun onSpaceGlide(steps: Int) {
        val ic = host.getCurrentInputConnection() ?: return
        val newPos = (cursorPosition + steps).coerceAtLeast(0)
        ic.setSelection(newPos, newPos)
        cursorPosition = newPos
        host.getHostBridge().vibrate(10L)
    }

    fun onDeleteSwipe(wordCount: Int, cachedText: String) {
        val ic = host.getCurrentInputConnection() ?: return
        var deleteLength = calculateSwipeDeleteLength(cachedText, wordCount)
        var start = (cursorPosition - deleteLength).coerceAtLeast(0)

        if (start < cursorPosition && start > 0) {
            if (cachedText.getOrNull(start) == ' ') {
                start++
                deleteLength--
            }
        }
        ic.setSelection(start, cursorPosition)
    }

    private fun calculateSwipeDeleteLength(text: String, words: Int): Int {
        if (words <= 0) return 0
        var count = 0
        var i = text.length - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        var inWord = false
        while (i >= 0) {
            val isSpace = text[i].isWhitespace()
            if (!isSpace) {
                inWord = true
            } else if (inWord) {
                count++
                inWord = false
                if (count == words) return text.length - (i + 1)
            }
            i--
        }
        return text.length
    }

    fun updateSuggestions() {
        searchJob?.cancel()
        if (isDeleteSuggestionMode) return

        val ic = host.getCurrentInputConnection()
        val text = ic?.getTextBeforeCursor(60, 0)?.toString() ?: ""
        val parts = text.split(" ", "\n")
        val currentWord = (parts.lastOrNull() ?: "").trim { !it.isLetterOrDigit() }
        val previousWord = (if (parts.size >= 2) parts[parts.size - 2] else "").trim { !it.isLetterOrDigit() }
        val ppWord = (if (parts.size >= 3) parts[parts.size - 3] else "").trim { !it.isLetterOrDigit() }

        searchJob = scope.launch {
            val preds = withContext(Dispatchers.Default) {
                predictor.predict(currentWord, previousWord, ppWord, isAmharic)
            }
            if (isActive) {
                suggestions.clear()
                suggestions.addAll(preds)
                currentBestPrediction = preds.firstOrNull()?.text
            }
        }
    }

    fun pickSuggestion(suggestion: String) {
        val ic = host.getCurrentInputConnection() ?: return
        val text = ic.getTextBeforeCursor(100, 0)?.toString() ?: ""
        if (text.isEmpty()) return

        var ignoreCount = 0
        var i = text.length - 1
        var separator = ""
        if (i >= 0) {
            val lastChar = text[i].toString()
            if (separators.contains(lastChar)) {
                ignoreCount = 1
                separator = lastChar
                i--
            }
        }

        while (i >= 0) {
            val c = text[i]
            if (!c.isLetterOrDigit() && c != '\'') break
            i--
        }
        val start = i + 1
        val textToCommit = suggestion + separator + " "
        val effectiveEnd = text.length - ignoreCount
        if (start > effectiveEnd) {
            ic.commitText(textToCommit, 1)
            return
        }

        val wordToReplace = text.substring(start, effectiveEnd)
        ic.beginBatchEdit()
        ic.finishComposingText()
        val charsToDelete = wordToReplace.length + ignoreCount
        ic.deleteSurroundingText(charsToDelete, 0)
        ic.commitText(textToCommit, 1)
        ic.endBatchEdit()

        expectedCursorPos = cursorPosition - charsToDelete + textToCommit.length
        lastAutocorrect = null
        isAutocorrectSuppressed = false

        DiscoveryLogger.log(host.getHostContext(), "SUGGESTION_PICK", mapOf("typed" to wordToReplace, "picked" to suggestion, "lang" to if(isAmharic) "am" else "en"))
        scope.launch(Dispatchers.Default) {
            predictor.learnWord(suggestion, null, null, isExplicit = true)
            if (wordToReplace.isNotEmpty() && wordToReplace != suggestion) {
                predictor.learnCorrection(wordToReplace, suggestion)
            }
        }
        updateSuggestions()
    }

    fun showDeleteConfirmation(word: String) {
        isDeleteSuggestionMode = true
        wordToDelete = word
    }

    fun confirmDeleteWord() {
        val word = wordToDelete
        scope.launch(Dispatchers.Default) { predictor.removeWord(word) }
        isDeleteSuggestionMode = false
        wordToDelete = ""
        updateSuggestions()
    }

    fun cancelDeleteWord() {
        isDeleteSuggestionMode = false
        wordToDelete = ""
        updateSuggestions()
    }

    fun sendDpad(keyCode: Int) {
        host.sendHostKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        host.sendHostKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        host.getHostBridge().vibrate(15L)
    }
}