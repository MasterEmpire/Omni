package com.omni.hub.loader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.omni.hub.container.PluginContainerActivity
import com.omni.hub.container.PluginLauncherActivity
import kotlin.math.abs

data class DrawerAliasSpec(
    val pluginId: String,
    val aliasClassName: String,
    val defaultLabel: String
)

object AppDrawerManager {

    private val ALIAS_SPECS = listOf(
        DrawerAliasSpec("omni_browser", "com.omni.hub.launcher.OmniBrowserAlias", "Omni Chrome"),
        DrawerAliasSpec("omni_notes", "com.omni.hub.launcher.OmniNotesAlias", "Omni Notes"),
        DrawerAliasSpec("omni_player", "com.omni.hub.launcher.OmniPlayerAlias", "Omni Player"),
        DrawerAliasSpec("focus_shield", "com.omni.hub.launcher.FocusShieldAlias", "Focus Shield"),
        DrawerAliasSpec("scroll_lock", "com.omni.hub.launcher.ScrollLockAlias", "ScrollLock"),
        DrawerAliasSpec("ethiopian_calendar", "com.omni.hub.launcher.EthiopianCalendarAlias", "Ethiopian Calendar"),
        DrawerAliasSpec("omni_keyboard", "com.omni.hub.launcher.OmniKeyboardAlias", "Omni Board"),
        DrawerAliasSpec("spotify_recorder", "com.omni.hub.launcher.SpotifyRecorderAlias", "Spotify Recorder"),
        DrawerAliasSpec("tele_alert", "com.omni.hub.launcher.TeleAlertAlias", "TeleAlert Sentinel"),
        DrawerAliasSpec("sample_utility", "com.omni.hub.launcher.SampleUtilityAlias", "Sample Utility")
    )

    private val ALIAS_BY_PLUGIN = ALIAS_SPECS.associateBy { it.pluginId }
    private val PLUGIN_BY_ALIAS = ALIAS_SPECS.associateBy { it.aliasClassName }

    fun hasDrawerAlias(pluginId: String): Boolean = ALIAS_BY_PLUGIN.containsKey(pluginId)

    fun resolvePluginIdForAlias(aliasClassName: String?): String? {
        if (aliasClassName.isNullOrBlank()) return null
        return PLUGIN_BY_ALIAS[aliasClassName]?.pluginId
    }

    fun getDefaultName(pluginId: String): String? = ALIAS_BY_PLUGIN[pluginId]?.defaultLabel

    fun isInDrawer(context: Context, pluginId: String): Boolean {
        val spec = ALIAS_BY_PLUGIN[pluginId] ?: return false
        return try {
            val cn = ComponentName(context.packageName, spec.aliasClassName)
            context.packageManager.getComponentEnabledSetting(cn) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } catch (_: Exception) {
            false
        }
    }

    fun setDrawerEnabled(context: Context, pluginId: String, enabled: Boolean): Boolean {
        val spec = ALIAS_BY_PLUGIN[pluginId] ?: return false
        return try {
            val cn = ComponentName(context.packageName, spec.aliasClassName)
            val newState = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            context.packageManager.setComponentEnabledSetting(cn, newState, PackageManager.DONT_KILL_APP)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun pinHomeShortcutFallback(context: Context, plugin: PluginMetadata) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(context, "Pinned shortcuts require Android 8.0+", Toast.LENGTH_SHORT).show()
            return
        }
        val sm = context.getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            Toast.makeText(context, "Launcher does not support pinned shortcuts", Toast.LENGTH_SHORT).show()
            return
        }

        val launchIntent = Intent(context, PluginLauncherActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("omni://launch/${plugin.id}")
            putExtra(PluginContainerActivity.EXTRA_PLUGIN_ID, plugin.id)
        }

        val badge = buildFallbackBitmap(plugin.id, plugin.name, plugin.icon)
        val shortcut = ShortcutInfo.Builder(context, "omni_pin_${plugin.id}")
            .setShortLabel(plugin.name.take(24))
            .setLongLabel(plugin.name)
            .setIcon(Icon.createWithBitmap(badge))
            .setIntent(launchIntent)
            .build()

        sm.requestPinShortcut(shortcut, null)
    }

    private fun buildFallbackBitmap(id: String, name: String, cleanIcon: String?): Bitmap {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val hue = (abs(id.hashCode()) % 360).toFloat()
        val startColor = Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.72f))
        val endColor = Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.70f, 0.85f))

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), startColor, endColor, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 28f, 28f, bgPaint)

        val symbol = if (!cleanIcon.isNullOrBlank() && cleanIcon.length <= 4 && !cleanIcon.contains("/") && !cleanIcon.contains(".")) {
            cleanIcon
        } else {
            name.take(1).uppercase()
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 58f
            textAlign = Paint.Align.CENTER
        }
        val yPos = (size / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(symbol, size / 2f, yPos, textPaint)
        return bmp
    }
}