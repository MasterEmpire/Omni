package com.omni.hub.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.omni.hub.api.AccessibilityDispatcher
import com.omni.hub.api.OmniLogger

class OmniAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            val info = serviceInfo ?: android.accessibilityservice.AccessibilityServiceInfo()
            info.eventTypes = android.view.accessibility.AccessibilityEvent.TYPES_ALL_MASK
            info.feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = info.flags or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            serviceInfo = info
        } catch (e: Exception) {
            OmniLogger.log("A11Y_WARN", "Failed dynamically updating serviceInfo: ${e.message}")
        }
        AccessibilityDispatcher.setService(this)
        OmniLogger.log("A11Y", "OmniAccessibilityService active & listening with full event mask")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) {
            AccessibilityDispatcher.dispatchEvent(event, this)
        }
    }

    override fun onInterrupt() {
        OmniLogger.log("A11Y", "OmniAccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance == this) {
            instance = null
        }
        AccessibilityDispatcher.setService(null)
        OmniLogger.log("A11Y", "OmniAccessibilityService unbound by system")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        AccessibilityDispatcher.setService(null)
    }

    companion object {
        @Volatile
        var instance: OmniAccessibilityService? = null

        fun isRunning(): Boolean = AccessibilityDispatcher.isServiceActive()
    }
}