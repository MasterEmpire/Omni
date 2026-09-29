package com.omni.hub.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omni.hub.api.OmniLogger
import com.omni.hub.loader.PluginTaskEngine

class OmniBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        OmniLogger.log("BOOT_RECEIVER", "⚡ Received broadcast: $action. Auto-resurrecting dynamic daemons...")
        PluginTaskEngine.resurrectDaemons(context)
    }
}