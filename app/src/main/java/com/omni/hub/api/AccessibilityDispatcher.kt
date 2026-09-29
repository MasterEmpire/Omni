package com.omni.hub.api

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CopyOnWriteArrayList

typealias AccessibilityListener = (event: AccessibilityEvent, service: AccessibilityService) -> Unit

object AccessibilityDispatcher {
    @Volatile
    private var activeService: AccessibilityService? = null
    @Volatile
    var lastEventTimestampMs: Long = 0L
        private set

    private val listeners = CopyOnWriteArrayList<AccessibilityListener>()

    fun setService(service: AccessibilityService?) {
        activeService = service
        if (service != null) {
            lastEventTimestampMs = System.currentTimeMillis()
        }
        OmniLogger.log("A11Y", if (service != null) "AccessibilityService attached to dispatcher" else "AccessibilityService detached")
    }

    /**
     * Deep probe inspection to verify whether the Accessibility Service is genuinely
     * running, connected via Binder, and capable of retrieving window tree dumps.
     * Avoids false positives from stale Settings.Secure SQLite entries after process death.
     */
    fun isServiceActive(context: Context? = null): Boolean {
        val s = activeService ?: return false
        return try {
            if (context != null) {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                if (am == null || !am.isEnabled) return false
            }
            // 1. Probe service configuration
            val info = s.serviceInfo ?: return false
            if (info.eventTypes == 0) return false

            // 2. Deep IPC probe: verify Binder link to AccessibilityManagerService isn't severed
            // If the service is dead or in a zombie unbind state, accessing rootInActiveWindow throws IllegalStateException
            s.rootInActiveWindow
            true
        } catch (e: Exception) {
            OmniLogger.log("A11Y_WARN", "Accessibility deep probe failed (zombie service detected): ${e.message}")
            activeService = null
            false
        }
    }

    fun addListener(listener: AccessibilityListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: AccessibilityListener) {
        listeners.remove(listener)
    }

    fun dispatchEvent(event: AccessibilityEvent, service: AccessibilityService) {
        lastEventTimestampMs = System.currentTimeMillis()
        if (activeService !== service) {
            activeService = service
        }
        listeners.forEach { listener ->
            try {
                listener(event, service)
            } catch (e: Exception) {
                OmniLogger.log("A11Y_ERR", "Error dispatching accessibility event: ${e.message}")
            }
        }
    }

    fun getRootInActiveWindow(): AccessibilityNodeInfo? {
        return try {
            activeService?.rootInActiveWindow
        } catch (_: Exception) {
            null
        }
    }

    fun performGlobalAction(action: Int): Boolean {
        return activeService?.performGlobalAction(action) ?: false
    }
}