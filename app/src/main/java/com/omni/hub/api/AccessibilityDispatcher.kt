package com.omni.hub.api

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CopyOnWriteArrayList

typealias AccessibilityListener = (event: AccessibilityEvent, service: AccessibilityService) -> Unit

object AccessibilityDispatcher {
    @Volatile
    private var activeService: AccessibilityService? = null

    private val listeners = CopyOnWriteArrayList<AccessibilityListener>()

    fun setService(service: AccessibilityService?) {
        activeService = service
        OmniLogger.log("A11Y", if (service != null) "AccessibilityService attached to dispatcher" else "AccessibilityService detached")
    }

    fun isServiceActive(): Boolean = activeService != null

    fun addListener(listener: AccessibilityListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: AccessibilityListener) {
        listeners.remove(listener)
    }

    fun dispatchEvent(event: AccessibilityEvent, service: AccessibilityService) {
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