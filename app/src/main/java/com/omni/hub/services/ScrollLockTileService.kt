package com.omni.hub.services

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.omni.hub.api.OmniLogger
import com.omni.hub.loader.PluginTaskEngine
import org.json.JSONObject
import java.io.File

class ScrollLockTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val now = System.currentTimeMillis()
        val lockUntil = readManualLockUntil()

        if (now < lockUntil) {
            // Already active - irreversible!
            val remMin = ((lockUntil - now) / 60_000L).coerceAtLeast(1)
            Toast.makeText(this, "⛔ Focus Lock active! ${remMin}m remaining. No early exit!", Toast.LENGTH_SHORT).show()
            vibrate(150L)
            updateTileState(lockUntil)
        } else {
            // Engage 1-hour lockout!
            val newUntil = now + (60 * 60 * 1000L)
            setManualLockUntil(newUntil)
            updateTileState(newUntil)
            vibrate(400L)

            Toast.makeText(this, "🔒 1-Hour Focus Lock Engaged! Target apps locked.", Toast.LENGTH_SHORT).show()
            OmniLogger.log("SCROLL_LOCK_TILE", "Quick Tile engaged 1-hour focus lock until $newUntil")

            // Ensure background sentinel is awake
            PluginTaskEngine.resurrectDaemons(this)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState(readManualLockUntil())
    }

    private fun updateTileState(lockUntil: Long) {
        val t = qsTile ?: return
        val now = System.currentTimeMillis()

        if (now < lockUntil) {
            val remMin = ((lockUntil - now) / 60_000L).coerceAtLeast(1)
            t.state = Tile.STATE_ACTIVE
            t.label = "Focus Lock"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                t.subtitle = "${remMin}m left"
            }
        } else {
            t.state = Tile.STATE_INACTIVE
            t.label = "ScrollLock"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                t.subtitle = "Tap for 1h lock"
            }
        }
        t.updateTile()
    }

    private fun readManualLockUntil(): Long {
        return try {
            val stateFile = File(getDir("plugins_data", Context.MODE_PRIVATE), "scroll_lock/scroll_lock_state.json")
            if (stateFile.exists()) {
                val json = JSONObject(stateFile.readText(Charsets.UTF_8))
                json.optLong("manual_lock_until_ms", 0L)
            } else 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun setManualLockUntil(untilMs: Long) {
        try {
            val stateFile = File(getDir("plugins_data", Context.MODE_PRIVATE), "scroll_lock/scroll_lock_state.json")
            stateFile.parentFile?.mkdirs()
            val json = if (stateFile.exists()) {
                try { JSONObject(stateFile.readText(Charsets.UTF_8)) } catch (_: Exception) { JSONObject() }
            } else JSONObject()
            json.put("manual_lock_until_ms", untilMs)
            stateFile.writeText(json.toString(2), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    private fun vibrate(durationMs: Long) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v?.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }
}