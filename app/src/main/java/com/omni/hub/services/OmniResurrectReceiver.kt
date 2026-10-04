package com.omni.hub.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omni.hub.api.OmniLogger
import com.omni.hub.loader.PluginTaskEngine

class OmniResurrectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            PluginTaskEngine.resurrectDaemons(context.applicationContext)
        } catch (e: Exception) {
            OmniLogger.log("RESURRECT_ERR", "Failed to resurrect daemons from receiver: ${e.message}")
        }
    }
}