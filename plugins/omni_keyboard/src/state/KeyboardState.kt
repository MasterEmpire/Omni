package com.omni.plugin.keyboard.state

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.*
import com.omni.hub.api.KeyboardHost
import com.omni.plugin.keyboard.model.KeyboardMode
import com.omni.plugin.keyboard.model.ShiftState

class KeyboardState(val host: KeyboardHost) {

    var mode by mutableStateOf(KeyboardMode.ALPHA)
    var shiftState by mutableStateOf(ShiftState.LOWERCASE)
    var isAmharic by mutableStateOf(false)
    var isDarkTheme by mutableStateOf(true)
    var showNumberRow by mutableStateOf(false)
    var isToolbarOverride by mutableStateOf(false)
    var heightScalePercent by mutableIntStateOf(100)

    var cursorPosition by mutableIntStateOf(0)
    var suggestions = mutableStateListOf<String>()

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

    fun commitChar(char: String) {
        val ic = host.getCurrentInputConnection() ?: return
        val textToCommit = if (shiftState != ShiftState.LOWERCASE && char.length == 1) {
            char.uppercase()
        } else {
            char
        }

        ic.commitText(textToCommit, 1)
        host.getHostBridge().vibrate(12L)

        if (shiftState == ShiftState.SHIFTED) {
            shiftState = ShiftState.LOWERCASE
        }
    }

    fun handleDelete() {
        val ic = host.getCurrentInputConnection() ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
        host.getHostBridge().vibrate(25L)
    }

    fun handleEnter() {
        val ic = host.getCurrentInputConnection() ?: return
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

        if (!handled) {
            ic.commitText("\n", 1)
        }
        host.getHostBridge().vibrate(25L)
    }

    fun handleSpace() {
        val ic = host.getCurrentInputConnection() ?: return
        ic.commitText(" ", 1)
        host.getHostBridge().vibrate(15L)
    }

    fun sendDpad(keyCode: Int) {
        host.sendHostKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        host.sendHostKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        host.getHostBridge().vibrate(15L)
    }
}