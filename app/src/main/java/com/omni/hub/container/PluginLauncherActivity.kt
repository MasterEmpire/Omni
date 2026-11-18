package com.omni.hub.container

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.omni.hub.MainActivity
import com.omni.hub.loader.AppDrawerManager
import com.omni.hub.loader.OmniTaskManager
import com.omni.hub.loader.PluginManager

/**
 * Zero-flash translucent trampoline for App Drawer <activity-alias> entries
 * and pinned Home Screen shortcuts. Launches the target plugin directly into its
 * independent PluginSlot task without bringing MainActivity to the foreground.
 */
class PluginLauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val componentClass = intent?.component?.className
        val pluginId = intent?.getStringExtra(PluginContainerActivity.EXTRA_PLUGIN_ID)
            ?: intent?.data?.lastPathSegment?.takeIf { it.isNotBlank() }
            ?: AppDrawerManager.resolvePluginIdForAlias(componentClass)

        if (pluginId.isNullOrBlank()) {
            finish()
            return
        }

        val installed = PluginManager.getInstalledPlugins(this).find { it.id == pluginId }
        if (installed == null) {
            val fallbackName = AppDrawerManager.getDefaultName(pluginId) ?: pluginId
            Toast.makeText(this, "$fallbackName is not installed yet. Install it inside Omni Hub.", Toast.LENGTH_SHORT).show()
            val hubIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(hubIntent)
            finish()
            return
        }

        if (OmniTaskManager.isStandaloneWindowEnabled(this, installed.id)) {
            OmniTaskManager.launchOrResume(this, installed.id, installed.name, installed.entryClass)
        } else {
            val hostIntent = Intent(this, MainActivity::class.java).apply {
                putExtra("extra_open_plugin_id", installed.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(hostIntent)
        }

        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}