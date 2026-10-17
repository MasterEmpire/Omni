package com.omni.hub.api

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.io.File

/**
 * Host interface provided to dynamic keyboard plugins.
 */
interface KeyboardHost {
    fun getHostContext(): Context
    fun getHostBridge(): HostBridge
    fun getCurrentInputConnection(): InputConnection?
    fun getCurrentEditorInfo(): EditorInfo?
    fun getPersistentDictDir(): File
    fun switchInputMethod(imeId: String?)
    fun sendHostKeyEvent(event: KeyEvent)
    fun performHostEditorAction(action: Int): Boolean
    fun requestHideKeyboard()
}

/**
 * Contract implemented by dynamic keyboard plugins.
 */
interface DynamicKeyboardPlugin {
    fun onCreateKeyboardView(host: KeyboardHost, baseDir: String): View
    fun onStartInput(info: EditorInfo?, restarting: Boolean)
    fun onFinishInput()
    fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    )
    fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
}