package com.omni.plugin.keyboard.engine

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
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
import android.widget.ScrollView
import android.widget.TextView
import com.omni.hub.api.AccessibilityDispatcher
import com.omni.hub.api.AccessibilityListener
import com.omni.hub.api.OmniLogger
import com.omni.plugin.keyboard.model.AmharicData

object GhostOverlayManager {

    private const val PREFS_NAME = "omni_ghost_prefs"
    private const val KEY_ENABLED = "ghost_mode_enabled"
    private const val OVERLAY_TAG = "OMNI_GHOST_OVERLAY_ROOT"
    private const val PROP_ACTIVE_INSTANCE = "omni_active_ghost_instance"

    private val INSTANCE_ID = System.currentTimeMillis().toString()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var containerView: FrameLayout? = null
    private var bubbleView: FrameLayout? = null
    private var expandedLayout: LinearLayout? = null
    private var pillPreviewText: TextView? = null
    private var pillScrollView: ScrollView? = null

    private var activeTargetNode: AccessibilityNodeInfo? = null
    private var isListenerAttached = false
    private var isPillExpanded = false
    private var isManuallyMoved = false

    private var currentTransliteratedText = ""
    private var bubbleSavedX = 20
    private var bubbleSavedY = 400
    private var pillSavedX = 24
    private var pillSavedY = 800

    private var accessibilityListener: AccessibilityListener? = null

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
        System.setProperty(PROP_ACTIVE_INSTANCE, INSTANCE_ID)
        bustZombies(context)
        if (!isListenerAttached) {
            val listener: AccessibilityListener = { event, service ->
                val activeId = System.getProperty(PROP_ACTIVE_INSTANCE)
                if (activeId != null && activeId != INSTANCE_ID) {
                    accessibilityListener?.let { AccessibilityDispatcher.removeListener(it) }
                    dismissOverlay()
                } else {
                    handleAccessibilityEvent(event, service)
                }
            }
            accessibilityListener = listener
            AccessibilityDispatcher.addListener(listener)
            isListenerAttached = true
            OmniLogger.log("GHOST_PILL", "👻 Ghost Transliteration Sentinel active [$INSTANCE_ID]")
        }
    }

    fun destroy() {
        dismissOverlay()
        if (isListenerAttached) {
            accessibilityListener?.let { AccessibilityDispatcher.removeListener(it) }
            accessibilityListener = null
            isListenerAttached = false
            OmniLogger.log("GHOST_PILL", "Ghost Transliteration Sentinel stopped")
        }
    }

    private fun bustZombies(context: Context) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        try {
            containerView?.let { v ->
                if (v.isAttachedToWindow) wm.removeViewImmediate(v)
            }
        } catch (_: Exception) {}
        containerView = null
        bubbleView = null
        expandedLayout = null

        // Deep-purge stale views left by previous dynamic classloaders
        try {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val wmg = wmgClass.getMethod("getInstance").invoke(null)
            val mViewsField = wmgClass.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val views = mViewsField.get(wmg) as? java.util.ArrayList<View>
            views?.toList()?.forEach { v ->
                if (v.tag == OVERLAY_TAG) {
                    try {
                        wm.removeViewImmediate(v)
                        OmniLogger.log("GHOST_KILL", "🧹 Decapitated zombie overlay from previous session")
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
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
                        pillPreviewText?.text = if (currentTransliteratedText.isEmpty()) "..." else currentTransliteratedText
                        pillScrollView?.post {
                            pillScrollView?.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (isPillExpanded && !isManuallyMoved) {
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

        bustZombies(context)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val density = context.resources.displayMetrics.density
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

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

        val root = FrameLayout(context).apply {
            tag = OVERLAY_TAG
        }

        // 1. Collapsed Floating Bubble
        val bubble = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                (48 * density).toInt(),
                (48 * density).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6161B22"))
                setStroke((1.8f * density).toInt(), Color.parseColor("#58A6FF"))
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

        // Drag Handling for Bubble
        var bubbleStartX = 0
        var bubbleStartY = 0
        var bubbleTouchX = 0f
        var bubbleTouchY = 0f
        var bubbleDragged = false

        bubble.setOnTouchListener { _, event ->
            val p = root.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    bubbleStartX = p.x
                    bubbleStartY = p.y
                    bubbleTouchX = event.rawX
                    bubbleTouchY = event.rawY
                    bubbleDragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - bubbleTouchX).toInt()
                    val dy = (event.rawY - bubbleTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        bubbleDragged = true
                        p.x = bubbleStartX + dx
                        p.y = bubbleStartY + dy
                        bubbleSavedX = p.x
                        bubbleSavedY = p.y
                        if (root.isAttachedToWindow) wm.updateViewLayout(root, p)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!bubbleDragged) {
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

        // 2. Expanded Container (Action Strip Above + Text Pill Below)
        val expanded = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // --- Tier 1: Left-Aligned Action Buttons Strip ---
        val actionStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (38 * density).toInt()
            ).apply {
                bottomMargin = (6 * density).toInt()
            }
        }

        fun createActionButton(iconText: String, textColor: Int, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = iconText
                setTextColor(textColor)
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    (36 * density).toInt(),
                    (32 * density).toInt()
                ).apply {
                    rightMargin = (8 * density).toInt()
                }
                background = GradientDrawable().apply {
                    cornerRadius = 8 * density
                    setColor(Color.parseColor("#E621262D"))
                    setStroke((1f * density).toInt(), Color.parseColor("#30363D"))
                }
                setOnClickListener { onClick() }
            }
        }

        // 1. Drag Handle
        val dragHandle = TextView(context).apply {
            text = " ⠿ "
            setTextColor(Color.parseColor("#58A6FF"))
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                (38 * density).toInt(),
                (32 * density).toInt()
            ).apply {
                rightMargin = (8 * density).toInt()
            }
            background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(Color.parseColor("#E621262D"))
                setStroke((1f * density).toInt(), Color.parseColor("#388BFD"))
            }
        }

        var pillStartX = 0
        var pillStartY = 0
        var pillTouchX = 0f
        var pillTouchY = 0f

        dragHandle.setOnTouchListener { _, event ->
            val p = root.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pillStartX = p.x
                    pillStartY = p.y
                    pillTouchX = event.rawX
                    pillTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - pillTouchX).toInt()
                    val dy = (event.rawY - pillTouchY).toInt()
                    p.x = pillStartX + dx
                    p.y = pillStartY + dy
                    pillSavedX = p.x
                    pillSavedY = p.y
                    isManuallyMoved = true
                    if (root.isAttachedToWindow) wm.updateViewLayout(root, p)
                    true
                }
                else -> false
            }
        }
        actionStrip.addView(dragHandle)

        // 2. Launch Omni Board Studio Button
        val studioBtn = createActionButton("⚡", Color.parseColor("#58A6FF")) {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("extra_open_plugin_id", "omni_keyboard")
                }
                if (intent != null) context.startActivity(intent)
            } catch (_: Exception) {}
        }
        actionStrip.addView(studioBtn)

        // 3. Turn Off Ghost Mode Button
        val turnOffBtn = createActionButton("⏻", Color.parseColor("#F85149")) {
            setGhostEnabled(context, false)
        }
        actionStrip.addView(turnOffBtn)

        // 4. Fold / Collapse Button
        val foldBtn = createActionButton("▼", Color.parseColor("#8B949E")) {
            collapseToBubble(context)
        }
        actionStrip.addView(foldBtn)

        expanded.addView(actionStrip)

        // --- Tier 2: Multi-Line Auto-Scrolling Transliteration Pill ---
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 18 * density
                setColor(Color.parseColor("#F2161B22"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#58A6FF"))
            }
            setPadding((12 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Capped ScrollView (Max 3 Lines ~ 76dp) with Auto-Scroll to Bottom
        val scrollView = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxH = (76 * context.resources.displayMetrics.density).toInt()
                val newHSpec = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, newHSpec)
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        pillScrollView = scrollView

        val preview = TextView(context).apply {
            text = "..."
            setTextColor(Color.parseColor("#3FB950"))
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setLineSpacing(3f * density, 1f)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        pillPreviewText = preview
        scrollView.addView(preview)
        pill.addView(scrollView)

        val btnSpacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams((8 * density).toInt(), 1)
        }
        pill.addView(btnSpacer)

        // Compact Insert Button
        val insertBtn = TextView(context).apply {
            text = "📥"
            textSize = 15f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                (36 * density).toInt(),
                (36 * density).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1F6FEB"))
            }
            setOnClickListener {
                insertTransliteratedText()
            }
        }
        pill.addView(insertBtn)

        expanded.addView(pill)

        root.addView(bubble)
        root.addView(expanded)

        bubbleView = bubble
        expandedLayout = expanded
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
        val expanded = expandedLayout ?: return
        val bubble = bubbleView ?: return

        isPillExpanded = true
        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels

        val pillWidth = screenWidth - (24 * density).toInt()
        val pillX = (12 * density).toInt()

        val totalEstimatedHeight = (88 * density).toInt()
        var pillY = targetBounds.top - totalEstimatedHeight - (14 * density).toInt()
        if (pillY < (36 * density).toInt()) {
            pillY = targetBounds.bottom + (10 * density).toInt()
        }

        if (isManuallyMoved) {
            pillY = pillSavedY
        } else {
            pillSavedX = pillX
            pillSavedY = pillY
        }

        mainHandler.post {
            try {
                bubble.visibility = View.GONE
                expanded.visibility = View.VISIBLE

                val params = root.layoutParams as WindowManager.LayoutParams
                params.width = pillWidth
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.x = if (isManuallyMoved) pillSavedX else pillX
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
        if (!isPillExpanded || isManuallyMoved) return

        val density = context.resources.displayMetrics.density
        val totalEstimatedHeight = (88 * density).toInt()
        var pillY = targetBounds.top - totalEstimatedHeight - (14 * density).toInt()
        if (pillY < (36 * density).toInt()) {
            pillY = targetBounds.bottom + (10 * density).toInt()
        }

        mainHandler.post {
            try {
                val params = root.layoutParams as WindowManager.LayoutParams
                params.y = pillY
                pillSavedY = pillY
                if (root.isAttachedToWindow) {
                    wm.updateViewLayout(root, params)
                }
            } catch (_: Exception) {}
        }
    }

    private fun collapseToBubble(context: Context) {
        val root = containerView ?: return
        val wm = windowManager ?: return
        val expanded = expandedLayout ?: return
        val bubble = bubbleView ?: return

        isPillExpanded = false
        val density = context.resources.displayMetrics.density
        val bubbleSize = (48 * density).toInt()

        mainHandler.post {
            try {
                expanded.visibility = View.GONE
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
                        windowManager?.removeViewImmediate(v)
                    }
                }
            } catch (_: Exception) {}
            containerView = null
            bubbleView = null
            expandedLayout = null
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