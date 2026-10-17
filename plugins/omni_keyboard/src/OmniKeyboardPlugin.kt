package com.omni.plugin.keyboard

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.omni.hub.api.DynamicKeyboardPlugin
import com.omni.hub.api.HostBridge
import com.omni.hub.api.KeyboardHost
import com.omni.hub.api.PluginEntry
import com.omni.plugin.keyboard.state.KeyboardState
import com.omni.plugin.keyboard.ui.KeyboardScreen
import com.omni.plugin.keyboard.ui.KeyboardSandboxScreen

class OmniKeyboardPlugin : PluginEntry(), DynamicKeyboardPlugin {

    private var keyboardState: KeyboardState? = null

    override fun onCreateView(context: Context, bridge: HostBridge, baseDir: String): View {
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                KeyboardSandboxScreen(context, bridge, baseDir)
            }
        }
    }

    override fun onCreateKeyboardView(host: KeyboardHost, baseDir: String): View {
        val state = KeyboardState(host)
        keyboardState = state

        return ComposeView(host.getHostContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                KeyboardScreen(state = state)
            }
        }
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        keyboardState?.updateSuggestions()
    }

    override fun onFinishInput() {
        keyboardState?.onDestroy()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        keyboardState?.onUpdateSelection(newSelEnd)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // Adjust layout orientation
    }

    override fun onStart(context: Context, bridge: HostBridge, baseDir: String) {
        com.omni.plugin.keyboard.engine.GhostOverlayManager.init(context)
    }

    override fun onStop(context: Context) {
        com.omni.plugin.keyboard.engine.GhostOverlayManager.destroy()
    }
}