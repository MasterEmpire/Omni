package com.omni.plugin.keyboard.engine

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
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
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
    private var activeOverlayView: View? = null
    private var activeTargetNode: AccessibilityNodeInfo? = null
    private var isListenerAttached = false

    private val accessibilityListener: AccessibilityListener = { event, _ ->
        handleAccessibilityEvent(event)
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
            OmniLogger.log("GHOST_ENGINE", "👻 Ghost Overlay Sentinel initialized and listening")
        }
    }

    fun destroy() {
        dismissOverlay()
        if (isListenerAttached) {
            AccessibilityDispatcher.removeListener(accessibilityListener)
            isListenerAttached = false
            OmniLogger.log("GHOST_ENGINE", "Ghost Overlay Sentinel destroyed")
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent) {
        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) {
            return
        }

        val pkgName = event.packageName?.toString() ?: ""
        // Rule 1: Sentry against Echo-Chamber Recursion
        if (pkgName.isEmpty() || pkgName.contains("com.omni.hub")) {
            return
        }

        val source = event.source ?: return
        if (!source.isEditable || source.isPassword) {
            return
        }

        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            try {
                if (source.refresh() && source.isFocused) {
                    val bounds = Rect()
                    source.getBoundsInScreen(bounds)
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        val appContext = source.javaClass.classLoader?.let {
                            // Obtain reference to window manager via main looper context
                        }
                        showOverlay(bounds, source)
                    }
                }
            } catch (e: Exception) {
                OmniLogger.log("GHOST_ERR", "Error probing target node: ${e.message}")
            }
        }, 120L)
    }

    private fun showOverlay(bounds: Rect, targetNode: AccessibilityNodeInfo) {
        mainHandler.post {
            try {
                val context = activeOverlayView?.context ?: run {
                    val act = AccessibilityDispatcher.getRootInActiveWindow()
                    // Fallback to active service context
                    com.omni.hub.services.OmniAccessibilityService.instance ?: return@post
                }

                if (!isGhostEnabled(context)) return@post

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                    OmniLogger.log("GHOST_WARN", "Cannot spawn ghost overlay: System alert window permission missing.")
                    return@post
                }

                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
                windowManager = wm
                activeTargetNode = targetNode

                dismissOverlay()

                val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val density = context.resources.displayMetrics.density
                val overlayHeight = maxOf(bounds.height(), (48 * density).toInt())
                val overlayWidth = maxOf(bounds.width(), (220 * density).toInt())

                val params = WindowManager.LayoutParams(
                    overlayWidth,
                    overlayHeight + (26 * density).toInt(),
                    windowType,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = bounds.left
                    y = (bounds.top - (26 * density).toInt()).coerceAtLeast(0)
                }

                val rootLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#F2161B22"))
                        cornerRadius = 10 * density
                        setStroke((1.5f * density).toInt(), Color.parseColor("#58A6FF"))
                    }
                    setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                }

                // Top Status Bar: Live Transliteration Preview
                val headerRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (20 * density).toInt()
                    )
                }

                val badgeText = TextView(context).apply {
                    text = "👻 GHOST: "
                    setTextColor(Color.parseColor("#58A6FF"))
                    textSize = 10f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                headerRow.addView(badgeText)

                val previewView = TextView(context).apply {
                    text = "Ready..."
                    setTextColor(Color.parseColor("#3FB950"))
                    textSize = 11f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    maxLines = 1
                }
                headerRow.addView(previewView)

                val closeBtn = TextView(context).apply {
                    text = " ✕ "
                    setTextColor(Color.parseColor("#8B949E"))
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setOnClickListener { dismissOverlay() }
                }
                headerRow.addView(closeBtn)

                rootLayout.addView(headerRow)

                // The Hijack Input Field
                val inputField = EditText(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                    background = null
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setHintTextColor(Color.parseColor("#6E7681"))
                    hint = "Type phonetically (e.g. selam)..."
                    isFocusableInTouchMode = true
                    isFocusable = true
                    imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI

                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            val raw = s?.toString() ?: ""
                            val converted = transliterate(raw)
                            previewView.text = if (converted.isEmpty()) "Ready..." else converted
                            syncToTarget(targetNode, converted)
                        }
                        override fun afterTextChanged(s: Editable?) {}
                    })

                    setOnEditorActionListener { _, actionId, event ->
                        val isEnter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                        if (actionId == EditorInfo.IME_ACTION_SEND ||
                            actionId == EditorInfo.IME_ACTION_DONE ||
                            actionId == EditorInfo.IME_ACTION_GO ||
                            isEnter) {
                            val currentText = transliterate(text.toString())
                            syncToTarget(targetNode, currentText)
                            dismissOverlay()
                            true
                        } else false
                    }
                }

                rootLayout.addView(inputField)

                // Dismiss when tapping outside the overlay
                rootLayout.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_OUTSIDE) {
                        dismissOverlay()
                        true
                    } else false
                }

                // Dismiss on Back Press
                rootLayout.isFocusableInTouchMode = true
                rootLayout.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        dismissOverlay()
                        true
                    } else false
                }

                wm.addView(rootLayout, params)
                activeOverlayView = rootLayout

                inputField.postDelayed({
                    inputField.requestFocus()
                }, 50L)

                OmniLogger.log("GHOST_ENGINE", "🚀 Mounted Ghost Overlay at (${params.x}, ${params.y})")
            } catch (e: Exception) {
                OmniLogger.log("GHOST_ERR", "Failed mounting ghost overlay: ${e.message}")
            }
        }
    }

    private fun syncToTarget(node: AccessibilityNodeInfo?, text: String) {
        if (node == null) return
        try {
            node.refresh()
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            OmniLogger.log("GHOST_SYNC_ERR", "Failed injecting transliterated text into target node: ${e.message}")
        }
    }

    fun dismissOverlay() {
        val action = Runnable {
            try {
                activeOverlayView?.let { v ->
                    if (v.isAttachedToWindow) {
                        windowManager?.removeView(v)
                    }
                }
            } catch (e: Exception) {
                OmniLogger.log("GHOST_ERR", "Error dismissing overlay: ${e.message}")
            }
            activeOverlayView = null
            activeTargetNode = null
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