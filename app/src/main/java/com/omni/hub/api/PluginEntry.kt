package com.omni.hub.api

import android.content.Context
import android.view.View

/**
 * Standard contract interface for all dynamic Omni Hub plugins.
 * Every dynamic module must have an entry class implementing or extending this class.
 */
abstract class PluginEntry {
    /**
     * Returns the primary UI View for interactive plugins (Jetpack Compose View, XML View, etc.).
     * Called by PluginContainerActivity when rendering the plugin.
     */
    open fun onCreateView(context: Context, bridge: HostBridge, baseDir: String): View {
        throw UnsupportedOperationException("This plugin does not provide a visual UI.")
    }

    /**
     * Lifecycle hook invoked when running in headless or background task mode.
     */
    open fun onStart(context: Context, bridge: HostBridge, baseDir: String) {}

    /**
     * Lifecycle hook invoked when the plugin is unloaded or container is destroyed.
     */
    open fun onStop(context: Context) {}

    /**
     * Optional hook to receive system events or custom broadcast triggers from the host.
     */
    open fun onSystemEvent(event: String, payload: Map<String, Any>) {}
}