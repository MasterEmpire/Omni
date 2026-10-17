package com.omni.hub.services

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.omni.hub.MainActivity
import com.omni.hub.api.DynamicKeyboardPlugin
import com.omni.hub.api.HostBridge
import com.omni.hub.api.HostBridgeImpl
import com.omni.hub.api.KeyboardHost
import com.omni.hub.api.OmniLogger
import com.omni.hub.api.OverlayLifecycleOwner
import com.omni.hub.loader.LoadedPlugin
import com.omni.hub.loader.PluginLoader
import com.omni.hub.loader.SharedLibManager
import java.io.File

class OmniKeyboardService : InputMethodService(), KeyboardHost {

    private var activePlugin: LoadedPlugin? = null
    private var activeKeyboardPlugin: DynamicKeyboardPlugin? = null
    private var hostBridge: HostBridgeImpl? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var currentKeyboardView: View? = null

    companion object {
        const val KEYBOARD_PLUGIN_ID = "omni_keyboard"
        const val DEFAULT_ENTRY_CLASS = "com.omni.plugin.keyboard.OmniKeyboardPlugin"
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleOwner = OverlayLifecycleOwner()
        OmniLogger.log("IME_SERVICE", "OmniKeyboardService created and initialized")
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleOwner?.destroy()
        lifecycleOwner = null
        activeKeyboardPlugin?.onFinishInput()
        activeKeyboardPlugin = null
        activePlugin = null
        currentKeyboardView = null
        OmniLogger.log("IME_SERVICE", "OmniKeyboardService destroyed")
    }

    override fun onCreateInputView(): View {
        return loadOrReloadPluginView()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        // Hot-Swap Detection: Check if classes.dex on disk is newer than the active loaded session
        val targetDex = File(getDir("plugins", Context.MODE_PRIVATE), "$KEYBOARD_PLUGIN_ID/classes.dex")
        if (targetDex.exists() && activePlugin != null && targetDex.lastModified() > activePlugin!!.loadedAt) {
            OmniLogger.log("IME_SERVICE", "🔄 Newer bytecode detected for [$KEYBOARD_PLUGIN_ID]. Hot-swapping keyboard view...")
            PluginLoader.invalidate(KEYBOARD_PLUGIN_ID)
            val newView = loadOrReloadPluginView()
            setInputView(newView)
        }

        try {
            activeKeyboardPlugin?.onStartInput(info, restarting)
        } catch (e: Exception) {
            OmniLogger.log("IME_ERR", "Exception in onStartInput: ${e.message}")
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        try {
            activeKeyboardPlugin?.onFinishInput()
        } catch (_: Exception) {}
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        try {
            activeKeyboardPlugin?.onUpdateSelection(
                oldSelStart, oldSelEnd,
                newSelStart, newSelEnd,
                candidatesStart, candidatesEnd
            )
        } catch (_: Exception) {}
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            activeKeyboardPlugin?.onConfigurationChanged(newConfig)
        } catch (_: Exception) {}
    }

    private fun loadOrReloadPluginView(): View {
        val rootContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(android.graphics.Color.parseColor("#141414"))
        }

        val owner = lifecycleOwner ?: OverlayLifecycleOwner().also { lifecycleOwner = it }

        try {
            val loaded = PluginLoader.loadFromDir(this, KEYBOARD_PLUGIN_ID, DEFAULT_ENTRY_CLASS)
            activePlugin = loaded

            val bridge = HostBridgeImpl(this, loaded.dataDir) {
                requestHideKeyboard()
            }
            hostBridge = bridge

            val pluginInstance = loaded.instance
            if (pluginInstance is DynamicKeyboardPlugin) {
                activeKeyboardPlugin = pluginInstance
                val keyboardView = pluginInstance.onCreateKeyboardView(this, loaded.baseDir.absolutePath)

                keyboardView.setViewTreeLifecycleOwner(owner)
                keyboardView.setViewTreeViewModelStoreOwner(owner)
                keyboardView.setViewTreeSavedStateRegistryOwner(owner)

                currentKeyboardView = keyboardView
                rootContainer.addView(keyboardView)
                OmniLogger.log("IME_SERVICE", "✅ Loaded dynamic keyboard from [$KEYBOARD_PLUGIN_ID]")
                return rootContainer
            } else {
                // Fallback standard plugin view
                val view = pluginInstance.onCreateView(this, bridge, loaded.baseDir.absolutePath)
                view.setViewTreeLifecycleOwner(owner)
                view.setViewTreeViewModelStoreOwner(owner)
                view.setViewTreeSavedStateRegistryOwner(owner)

                currentKeyboardView = view
                rootContainer.addView(view)
                return rootContainer
            }
        } catch (e: Exception) {
            OmniLogger.log("IME_WARN", "Keyboard plugin not ready or failed to load: ${e.message}")
            rootContainer.addView(buildFallbackView(e.message))
            return rootContainer
        }
    }

    private fun buildFallbackView(errorMessage: String?): View {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((16 * density).toInt(), (24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt())
            setBackgroundColor(android.graphics.Color.parseColor("#161B22"))
            minimumHeight = (220 * density).toInt()

            val titleView = TextView(this@OmniKeyboardService).apply {
                text = "⚡ Omni Board Shell Active"
                setTextColor(android.graphics.Color.parseColor("#58A6FF"))
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            addView(titleView)

            val descView = TextView(this@OmniKeyboardService).apply {
                text = if (errorMessage.isNullOrEmpty()) {
                    "Plugin 'omni_keyboard' is not installed yet.\nOpen Omni Hub to build or install the module."
                } else {
                    "Waiting for valid 'omni_keyboard' bundle.\n$errorMessage"
                }
                setTextColor(android.graphics.Color.parseColor("#8B949E"))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, (8 * density).toInt(), 0, (14 * density).toInt())
            }
            addView(descView)

            val openBtn = TextView(this@OmniKeyboardService).apply {
                text = "OPEN OMNI HUB"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setBackgroundColor(android.graphics.Color.parseColor("#1F6FEB"))
                setPadding((20 * density).toInt(), (10 * density).toInt(), (20 * density).toInt(), (10 * density).toInt())
                setOnClickListener {
                    val intent = Intent(this@OmniKeyboardService, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    startActivity(intent)
                }
            }
            addView(openBtn)
        }
    }

    // --- KeyboardHost Implementation ---
    override fun getHostContext(): Context = this
    override fun getHostBridge(): HostBridge = hostBridge ?: HostBridgeImpl(this, filesDir) {}
    override fun getCurrentInputConnection(): InputConnection? = super.getCurrentInputConnection()
    override fun getCurrentEditorInfo(): EditorInfo? = super.getCurrentInputEditorInfo()
    override fun getPersistentDictDir(): File {
        val docsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
        val dictDir = File(File(docsDir, ".omni_runtime"), "dicts")
        if (!dictDir.exists()) dictDir.mkdirs()
        return dictDir
    }

    override fun switchInputMethod(imeId: String?) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (imeId != null) {
            imm?.setInputMethod(window.window?.attributes?.token, imeId)
        } else {
            imm?.showInputMethodPicker()
        }
    }

    override fun sendHostKeyEvent(event: KeyEvent) {
        val ic = currentInputConnection
        if (ic != null) {
            ic.sendKeyEvent(event)
        } else {
            sendDownUpKeyEvents(event.keyCode)
        }
    }

    override fun performHostEditorAction(action: Int): Boolean {
        return currentInputConnection?.performEditorAction(action) ?: false
    }

    override fun requestHideKeyboard() {
        requestHideSelf(0)
    }
}