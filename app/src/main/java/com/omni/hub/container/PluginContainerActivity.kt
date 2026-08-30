package com.omni.hub.container

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.omni.hub.api.HostBridgeImpl
import com.omni.hub.loader.PluginLoader

/**
 * Isolated Container Activity hosting dynamic plugin views.
 * Attaches the necessary Jetpack Compose ViewTree owners so Compose features work in DEX payloads.
 */
class PluginContainerActivity : Activity(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private var currentPluginBaseDir: java.io.File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(android.graphics.Color.parseColor("#1F2227"))
        savedStateRegistryController.performRestore(savedInstanceState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID)
        val entryClass = intent.getStringExtra(EXTRA_ENTRY_CLASS)

        if (pluginId.isNullOrEmpty() || entryClass.isNullOrEmpty()) {
            finish()
            return
        }

        try {
            val loadedPlugin = PluginLoader.loadFromDir(this, pluginId, entryClass)
            currentPluginBaseDir = loadedPlugin.baseDir

            val hostBridge = HostBridgeImpl(this, loadedPlugin.dataDir) {
                finish()
            }

            val pluginView = loadedPlugin.instance.onCreateView(this, hostBridge, loadedPlugin.baseDir.absolutePath)

            // Bind Compose ViewTree owners
            pluginView.setViewTreeLifecycleOwner(this)
            pluginView.setViewTreeViewModelStoreOwner(this)
            pluginView.setViewTreeSavedStateRegistryOwner(this)

            setContentView(pluginView)

        } catch (e: Exception) {
            val errorLayout = FrameLayout(this).apply {
                val tv = TextView(this@PluginContainerActivity).apply {
                    text = "Failed to load plugin [$pluginId]:\n\n${e.message}\n\n${e.stackTraceToString()}"
                    setTextColor(android.graphics.Color.RED)
                    setPadding(40, 60, 40, 40)
                    textSize = 14f
                }
                addView(tv)
            }
            setContentView(errorLayout)
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onResume() {
        super.onResume()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onPause() {
        super.onPause()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override fun onStop() {
        super.onStop()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }

    companion object {
        const val EXTRA_PLUGIN_ID = "extra_plugin_id"
        const val EXTRA_ENTRY_CLASS = "extra_entry_class"
    }
}