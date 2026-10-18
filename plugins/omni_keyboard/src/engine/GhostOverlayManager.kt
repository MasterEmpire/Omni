package com.omni.plugin.keyboard.engine

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.omni.hub.api.AccessibilityDispatcher
import com.omni.hub.api.AccessibilityListener
import com.omni.hub.api.OmniLogger
import com.omni.plugin.keyboard.model.AmharicData

object GhostOverlayManager {

    private const val PREFS_NAME = "omni_ghost_prefs"
    private const val KEY_ENABLED = "ghost_mode_enabled"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var containerView: FrameLayout? = null
    private var bubbleView: FrameLayout? = null
    private var pillView: LinearLayout? = null
    private var pillPreviewText: TextView? = null

    private var activeTargetNode: AccessibilityNodeInfo? = null
    private var isListenerAttached = false
    private var isPillExpanded = false

    private var currentTransliteratedText = ""
    private var bubbleSavedX = 20
    private var bubbleSavedY = 400

    private val accessibilityListener: AccessibilityListener = { event, service ->
        handleAccessibilityEvent(event, service)
    }

    fun isGhostEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, false)
    }

    fun setGhostEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            init(context)
        } else {
            dismissOverlay()
        }
    }

    fun init(context: Context) {
        if (!isListenerAttached) {
            AccessibilityDispatcher.addListener(accessibilityListener)
            isListenerAttached = true
            OmniLogger.log("GHOST_PILL", "👻 Ghost Transliteration Sentinel active")
        }
    }

    fun destroy() {
        dismissOverlay()
        if (isListenerAttached) {
            AccessibilityDispatcher.removeListener(accessibilityListener)
            isListenerAttached = false
            OmniLogger.log("GHOST_PILL", "Ghost Transliteration Sentinel stopped")
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent, service: AccessibilityService) {
        if (!isGhostEnabled(service)) return

        val pkgName = event.packageName?.toString() ?: ""
        if (pkgName.isEmpty() || pkgName.contains(service.packageName)) {
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val source = event.source ?: return
                if (source.isEditable && !source.isPassword) {
                    activeTargetNode = source
                    mainHandler.removeCallbacksAndMessages(null)
                    mainHandler.postDelayed({
                        if (source.refresh() && source.isFocused) {
                            val bounds = Rect()
                            source.getBoundsInScreen(bounds)
                            if (bounds.width() > 0 && bounds.height() > 0) {
                                ensureWindowAttached(service)
                                expandToPill(service, bounds)
                            }
                        }
                    }, 120L)
                }
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val source = event.source
                if (source != null && source.isEditable) {
                    activeTargetNode = source
                    val rawText = source.text?.toString() ?: event.text?.joinToString("") ?: ""
                    currentTransliteratedText = transliterate(rawText)
                    mainHandler.post {
                        pillPreviewText?.text = if (currentTransliteratedText.isEmpty()) "Ready to transliterate..." else currentTransliteratedText
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (isPillExpanded) {
                    activeTargetNode?.let { node ->
                        if (node.refresh()) {
                            val bounds = Rect()
                            node.getBoundsInScreen(bounds)
                            if (bounds.width() > 0 && bounds.height() > 0) {
                                updatePillPosition(service, bounds)
                            }
                        }
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                mainHandler.postDelayed({
                    checkKeyboardState(service)
                }, 150L)
            }
        }
    }

    private fun checkKeyboardState(service: AccessibilityService) {
        if (!isPillExpanded) return

        var isImeVisible = false
        try {
            val windows = service.windows
            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    val rect = Rect()
                    window.getBoundsInScreen(rect)
                    if (rect.height() > 100) {
                        isImeVisible = true
                        break
                    }
                }
            }
        } catch (_: Exception) {}

        val isTargetStillFocused = try {
            activeTargetNode?.refresh() == true && activeTargetNode?.isFocused == true
        } catch (_: Exception) { false }

        if (!isImeVisible && !isTargetStillFocused) {
            collapseToBubble(service)
        }
    }

    private fun ensureWindowAttached(context: Context) {
        if (containerView != null && containerView?.isAttachedToWindow == true) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            OmniLogger.log("GHOST_WARN", "Cannot mount pill: SYSTEM_ALERT_WINDOW missing")
            return
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val density = context.resources.displayMetrics.density
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // NON-FOCUSABLE FLAG: Zero focus fighting, Gboard stays active natively
        val params = WindowManager.LayoutParams(
            (48 * density).toInt(),
            (48 * density).toInt(),
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleSavedX
            y = bubbleSavedY
        }

        val root = FrameLayout(context)

        // 1. Draggable Bubble Layout (Collapsed State)
        val bubble = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                (48 * density).toInt(),
                (48 * density).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6161B22"))
                setStroke((2f * density).toInt(), Color.parseColor("#58A6FF"))
            }

            val icon = TextView(context).apply {
                text = "ፊ"
                setTextColor(Color.parseColor("#58A6FF"))
                textSize = 18f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }
            addView(icon)
        }

        // Drag-and-drop listener for bubble
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var hasDragged = false

        bubble.setOnTouchListener { _, event ->
            val p = root.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x
                    startY = p.y
                    touchX = event.rawX
                    touchY = event.rawY
                    hasDragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        hasDragged = true
                        p.x = startX + dx
                        p.y = startY + dy
                        bubbleSavedX = p.x
                        bubbleSavedY = p.y
                        if (root.isAttachedToWindow) wm.updateViewLayout(root, p)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!hasDragged) {
                        activeTargetNode?.let { node ->
                            if (node.refresh()) {
                                val b = Rect()
                                node.getBoundsInScreen(b)
                                expandToPill(context, b)
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }

        // 2. Transliteration Header Pill Layout (Expanded State)
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = 24 * density
                setColor(Color.parseColor("#F2161B22"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#58A6FF"))
            }
            setPadding((12 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
        }

        val pillIcon = TextView(context).apply {
            text = "✨ "
            textSize = 14f
        }
        pill.addView(pillIcon)

        val preview = TextView(context).apply {
            text = "Ready to transliterate..."
            setTextColor(Color.parseColor("#3FB950"))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        pillPreviewText = preview
        pill.addView(preview)

        // INSERT ONLY BUTTON
        val insertBtn = TextView(context).apply {
            text = "📥 Insert"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#1F6FEB"))
            }
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            setOnClickListener {
                insertTransliteratedText()
            }
        }
        pill.addView(insertBtn)

        val closeBtn = TextView(context).apply {
            text = "  ✕"
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding((4 * density).toInt(), 0, (6 * density).toInt(), 0)
            setOnClickListener {
                collapseToBubble(context)
            }
        }
        pill.addView(closeBtn)

        root.addView(bubble)
        root.addView(pill)

        bubbleView = bubble
        pillView = pill
        containerView = root

        try {
            wm.addView(root, params)
        } catch (e: Exception) {
            OmniLogger.log("GHOST_ERR", "Error adding overlay: ${e.message}")
        }
    }

    private fun expandToPill(context: Context, targetBounds: Rect) {
        val root = containerView ?: return
        val wm = windowManager ?: return
        val pill = pillView ?: return
        val bubble = bubbleView ?: return

        isPillExpanded = true
        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        val pillHeight = (44 * density).toInt()

        val pillWidth = screenWidth - (24 * density).toInt()
        val pillX = (12 * density).toInt()

        // Place directly above target text field, or below if field is at top edge
        var pillY = targetBounds.top - pillHeight - (8 * density).toInt()
        if (pillY < (30 * density).toInt()) {
            pillY = targetBounds.bottom + (8 * density).toInt()
        }

        mainHandler.post {
            try {
                bubble.visibility = View.GONE
                pill.visibility = View.VISIBLE
                pill.layoutParams = FrameLayout.LayoutParams(pillWidth, pillHeight)

                val params = root.layoutParams as WindowManager.LayoutParams
                params.width = pillWidth
                params.height = pillHeight
                params.x = pillX
                params.y = pillY

                if (root.isAttachedToWindow) {
                    wm.updateViewLayout(root, params)
                }
            } catch (_: Exception) {}
        }
    }

    private fun updatePillPosition(context: Context, targetBounds: Rect) {
        val root = containerView ?: return
        val wm = windowManager ?: return
        if (!isPillExpanded) return

        val density = context.resources.displayMetrics.density
        val pillHeight = (44 * density).toInt()
        var pillY = targetBounds.top - pillHeight - (8 * density).toInt()
        if (pillY < (30 * density).toInt()) {
            pillY = targetBounds.bottom + (8 * density).toInt()
        }

        mainHandler.post {
            try {
                val params = root.layoutParams as WindowManager.LayoutParams
                params.y = pillY
                if (root.isAttachedToWindow) {
                    wm.updateViewLayout(root, params)
                }
            } catch (_: Exception) {}
        }
    }

    private fun collapseToBubble(context: Context) {
        val root = containerView ?: return
        val wm = windowManager ?: return
        val pill = pillView ?: return
        val bubble = bubbleView ?: return

        isPillExpanded = false
        val density = context.resources.displayMetrics.density
        val bubbleSize = (48 * density).toInt()

        mainHandler.post {
            try {
                pill.visibility = View.GONE
                bubble.visibility = View.VISIBLE

                val params = root.layoutParams as WindowManager.LayoutParams
                params.width = bubbleSize
                params.height = bubbleSize
                params.x = bubbleSavedX
                params.y = bubbleSavedY

                if (root.isAttachedToWindow) {
                    wm.updateViewLayout(root, params)
                }
            } catch (_: Exception) {}
        }
    }

    private fun insertTransliteratedText() {
        val node = activeTargetNode ?: return
        if (currentTransliteratedText.isEmpty()) return

        try {
            node.refresh()
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, currentTransliteratedText)
            }
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            OmniLogger.log("GHOST", "Inserted '$currentTransliteratedText' (success: $success)")
        } catch (e: Exception) {
            OmniLogger.log("GHOST_ERR", "Error inserting transliteration: ${e.message}")
        }
    }

    fun dismissOverlay() {
        val action = Runnable {
            try {
                containerView?.let { v ->
                    if (v.isAttachedToWindow) {
                        windowManager?.removeView(v)
                    }
                }
            } catch (_: Exception) {}
            containerView = null
            bubbleView = null
            pillView = null
            activeTargetNode = null
            isPillExpanded = false
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else mainHandler.post(action)
    }

    fun transliterate(input: String): String {
        val out = StringBuilder()
        var i = 0
        val len = input.length

        fun matchAt(sub: String): String? =
            AmharicData.map[sub] ?: AmharicData.map[sub.lowercase()]

        while (i < len) {
            val c = input[i]
            var matched = false
            val maxLookahead = minOf(4, len - i)
            for (step in maxLookahead downTo 1) {
                val slice = input.substring(i, i + step)
                val amharicChar = matchAt(slice)
                if (amharicChar != null) {
                    out.append(amharicChar)
                    i += step
                    matched = true
                    break
                }
            }
            if (!matched) {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}