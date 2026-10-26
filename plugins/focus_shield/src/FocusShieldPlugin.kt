package com.omni.plugin.focus

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.AppOpsManager
import android.graphics.Path
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.hub.api.AccessibilityDispatcher
import com.omni.hub.api.AccessibilityListener
import com.omni.hub.api.HostBridge
import com.omni.hub.api.PluginEntry
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

data class LockedAppItem(
    val packageName: String,
    val appName: String,
    val addedAt: Long = System.currentTimeMillis()
)

data class InstalledAppInfo(
    val packageName: String,
    val appName: String
)

object NetworkClockGuard {
    @Volatile var networkOffsetMs: Long = 0L
    @Volatile var lastSyncElapsedRealtime: Long = 0L

    fun syncNetworkTime(bridge: HostBridge) {
        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(6, TimeUnit.SECONDS)
                    .readTimeout(6, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url("https://www.google.com")
                    .head()
                    .build()
                val resp = client.newCall(req).execute()
                val dateHeader = resp.header("Date")
                if (!dateHeader.isNullOrEmpty()) {
                    val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
                    val serverTime = format.parse(dateHeader)?.time
                    if (serverTime != null) {
                        val localTime = System.currentTimeMillis()
                        networkOffsetMs = serverTime - localTime
                        lastSyncElapsedRealtime = SystemClock.elapsedRealtime()
                        bridge.log("CLOCK_SYNC", "Network clock synced: offset=${networkOffsetMs}ms")
                    }
                }
            } catch (e: Exception) {
                bridge.log("CLOCK_SYNC_WARN", "Network clock sync exception: ${e.message}")
            }
        }.start()
    }

    fun getAccurateTimeMs(): Long {
        val localTime = System.currentTimeMillis()
        return localTime + networkOffsetMs
    }
}

class FocusShieldPlugin : PluginEntry() {

    private val accessibilityListener: AccessibilityListener = { event, service ->
        handleAccessibilityEvent(event, service)
    }

    override fun onCreateView(context: Context, bridge: HostBridge, baseDir: String): View {
        activeContext = context
        activeBridge = bridge
        loadPersistedState(bridge)
        ensureMonitoringRunning(context, bridge)
        AccessibilityDispatcher.addListener(accessibilityListener)

        val daemonPrefs = context.getSharedPreferences("omni_daemon_registry", Context.MODE_PRIVATE)
        val daemonJson = try {
            JSONObject(daemonPrefs.getString("active_daemons", "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        if (!daemonJson.has("focus_shield")) {
            daemonJson.put("focus_shield", "com.omni.plugin.focus.FocusShieldPlugin")
            daemonPrefs.edit().putString("active_daemons", daemonJson.toString()).apply()
        }

        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        background = Color(0xFF0D1117),
                        surface = Color(0xFF161B22),
                        primary = Color(0xFF58A6FF)
                    )
                ) {
                    FocusShieldScreen(context, bridge, this@FocusShieldPlugin)
                }
            }
        }
    }

    override fun onStart(context: Context, bridge: HostBridge, baseDir: String) {
        activeContext = context
        activeBridge = bridge
        loadPersistedState(bridge)
        ensureMonitoringRunning(context, bridge)
        AccessibilityDispatcher.addListener(accessibilityListener)
        bridge.log("FOCUS_SHIELD", "Focus Shield background sentinel armed with pop-up killer.")
    }

    override fun onStop(context: Context) {
        // UI dismissed: Keep background monitor and accessibility active
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent, service: AccessibilityService) {
        val bridge = activeBridge ?: return
        val context = activeContext ?: service

        // 1. FAST-EXIT GATE: 0ms overhead for keyboards, typing events, Settings, and Omni Hub itself
        val eventPkg = event.packageName?.toString() ?: ""
        if (eventPkg.isEmpty() ||
            eventPkg == context.packageName ||
            eventPkg == "com.omni.hub" ||
            eventPkg == "com.android.settings" ||
            eventPkg.startsWith("com.android.settings.") ||
            eventPkg.contains("inputmethod") ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            return
        }

        val accurateTime = NetworkClockGuard.getAccurateTimeMs()
        val isPanicActive = manualLockUntilMs > accurateTime
        val cal = Calendar.getInstance().apply { timeInMillis = accurateTime }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val isNightActive = hour >= 22 || hour < 6

        if (!isPanicActive && !isNightActive) {
            if (isOverlayMounted) {
                dismissPopupOverlay(bridge)
            }
            return
        }

        val customPkgs = customLockedApps.map { it.packageName }.toSet()

        // 2. Dual-Window Inspection: Keep all target windows for sniper click, find largest for overlay shield
        val windows = try {
            service.windows
        } catch (_: Exception) {
            emptyList()
        }

        data class TargetWindowEntry(val root: AccessibilityNodeInfo, val bounds: Rect, val pkg: String)
        val targetWindows = mutableListOf<TargetWindowEntry>()
        var primaryContentWindow: TargetWindowEntry? = null
        var largestArea = 0

        if (windows.isNotEmpty()) {
            for (w in windows) {
                val root = try {
                    w.root
                } catch (_: Exception) {
                    null
                } ?: continue

                val pkg = root.packageName?.toString() ?: continue
                if (pkg != context.packageName && pkg != "com.omni.hub" &&
                    pkg != "com.android.settings" && !pkg.startsWith("com.android.settings.") &&
                    (KNOWN_BROWSER_PACKAGES.contains(pkg) || customPkgs.contains(pkg))) {
                    val rect = Rect()
                    w.getBoundsInScreen(rect)
                    val entry = TargetWindowEntry(root, rect, pkg)
                    targetWindows.add(entry)

                    val area = rect.width() * rect.height()
                    if (rect.height() >= 350 && area > largestArea) {
                        largestArea = area
                        primaryContentWindow = entry
                    }
                }
            }
        }

        // Fallback: Check rootInActiveWindow ONLY IF its package is a blocked target & height >= 350px
        if (targetWindows.isEmpty()) {
            val activeRoot = try {
                service.rootInActiveWindow
            } catch (_: Exception) {
                null
            }
            if (activeRoot != null) {
                val rootPkg = activeRoot.packageName?.toString() ?: ""
                if (rootPkg != context.packageName && rootPkg != "com.omni.hub" &&
                    rootPkg != "com.sec.android.app.launcher" && rootPkg != "com.android.systemui" &&
                    rootPkg != "com.android.settings" && !rootPkg.startsWith("com.android.settings.") &&
                    !rootPkg.contains("inputmethod") &&
                    (KNOWN_BROWSER_PACKAGES.contains(rootPkg) || customPkgs.contains(rootPkg))) {
                    val rect = Rect()
                    activeRoot.getBoundsInScreen(rect)
                    val entry = TargetWindowEntry(activeRoot, rect, rootPkg)
                    targetWindows.add(entry)
                    if (rect.height() >= 350) {
                        primaryContentWindow = entry
                    }
                }
            }
        }

        // If NO window on screen belongs to a blocked app:
        if (targetWindows.isEmpty()) {
            if (isOverlayMounted) {
                val now = System.currentTimeMillis()
                if (now - lastTargetSeenTimeMs > 2500L) {
                    dismissPopupOverlay(bridge)
                }
            }
            return
        }

        lastTargetSeenTimeMs = System.currentTimeMillis()

        // Primary content window for the overlay shield (spans full pop-up body)
        val shieldWindow = primaryContentWindow ?: targetWindows.maxByOrNull { it.bounds.height() }!!
        val bounds = shieldWindow.bounds
        val pkg = shieldWindow.pkg

        val appLabel = try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            pkg
        }

        val dm = context.resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels

        val isPopup = bounds.width() < (screenWidth * 0.96f) ||
                      bounds.height() < (screenHeight * 0.92f) ||
                      bounds.top > 80 || bounds.left > 10

        // 3. Execute Dual-Window Sniper on ALL target windows (searching pill window + content window)
        val killed = huntAndKillMultiWindow(service, targetWindows, bridge, appLabel)
        if (killed) {
            return
        }

        // 4. Mount or reposition precision overlay over the FULL pop-up body
        if (isPopup) {
            mountOrUpdatePopupOverlay(context, bridge, appLabel, bounds)
        } else {
            dismissPopupOverlay(bridge)
        }
    }

    private fun huntAndKillMultiWindow(
        service: AccessibilityService,
        windows: List<Any>,
        bridge: HostBridge,
        appLabel: String
    ): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastTapAttemptTimeMs < 400L) return false

        bridge.log("FOCUS_SHIELD_TAP", "🎯 [SNIPER_HUNT] Scanning ${windows.size} window(s) for [$appLabel]...")

        // --- STAGE 1: Direct Headshot on android:id/close_window across all windows ---
        for (w in windows) {
            val root = (w as? Any)?.let { getWindowRoot(it) } ?: continue
            val closeNodes = root.findAccessibilityNodeInfosByViewId("android:id/close_window")
            if (closeNodes.isNotEmpty()) {
                val closeBtn = closeNodes[0]
                val btnBounds = Rect()
                closeBtn.getBoundsInScreen(btnBounds)
                bridge.log("FOCUS_SHIELD_TAP", "🔍 [STAGE 1] Found 'android:id/close_window'! Bounds: ${btnBounds.toShortString()}, clickable=${closeBtn.isClickable}")

                if (closeBtn.isClickable) {
                    lastTapAttemptTimeMs = now
                    val clicked = closeBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    bridge.log("FOCUS_SHIELD_TAP", "💥 [STAGE 1] Executed ACTION_CLICK on close_window! Result: $clicked")
                    if (clicked) {
                        engageKillSuccess(bridge)
                        return true
                    }
                } else {
                    closeBtn.parent?.let { parent ->
                        if (parent.isClickable) {
                            lastTapAttemptTimeMs = now
                            val clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            bridge.log("FOCUS_SHIELD_TAP", "💥 [STAGE 1] Executed ACTION_CLICK on close_window parent! Result: $clicked")
                            if (clicked) {
                                engageKillSuccess(bridge)
                                return true
                            }
                        }
                    }
                }
            }
        }

        // --- STAGE 2: Direct Target on Samsung 'Quick options' Pill Handle (Window A) ---
        for (w in windows) {
            val root = getWindowRoot(w) ?: continue
            val quickPill = findNodeByDescOrText(root, "Quick options")
            if (quickPill != null) {
                val qBounds = Rect()
                quickPill.getBoundsInScreen(qBounds)
                bridge.log("FOCUS_SHIELD_TAP", "🔍 [STAGE 2] Found Samsung 'Quick options' pill handle! Bounds: ${qBounds.toShortString()}, clickable=${quickPill.isClickable}")

                lastTapAttemptTimeMs = now
                var expanded = false
                if (quickPill.isClickable) {
                    expanded = quickPill.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                if (!expanded && quickPill.parent?.isClickable == true) {
                    expanded = quickPill.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }

                // If node click failed, simulate physical gesture tap at exact center
                if (!expanded && qBounds.width() > 0 && qBounds.height() > 0) {
                    bridge.log("FOCUS_SHIELD_TAP", "🖐️ [STAGE 2] Node click failed. Simulating physical finger tap at (${qBounds.centerX()}, ${qBounds.centerY()})...")
                    expanded = dispatchTapGesture(service, qBounds.centerX().toFloat(), qBounds.centerY().toFloat(), bridge)
                }

                bridge.log("FOCUS_SHIELD_TAP", "👉 [STAGE 2] 'Quick options' pill expansion result: $expanded")
                if (expanded) {
                    return false // Let next event frame click the newly revealed close_window
                }
            }
        }

        // --- STAGE 3: Secondary Handle Fallback on android:id/caption ---
        for (w in windows) {
            val root = getWindowRoot(w) ?: continue
            val captionNodes = root.findAccessibilityNodeInfosByViewId("android:id/caption")
            if (captionNodes.isNotEmpty()) {
                val caption = captionNodes[0]
                val capBounds = Rect()
                caption.getBoundsInScreen(capBounds)
                bridge.log("FOCUS_SHIELD_TAP", "🔍 [STAGE 3] Found 'android:id/caption'. Bounds: ${capBounds.toShortString()}, clickable=${caption.isClickable}")

                var expanded = false
                if (caption.isClickable) {
                    expanded = caption.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                if (!expanded) {
                    for (i in 0 until caption.childCount) {
                        val child = caption.getChild(i) ?: continue
                        if (child.isClickable) {
                            expanded = child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            bridge.log("FOCUS_SHIELD_TAP", "👆 [STAGE 3] Tapped caption child [#$i: ${child.viewIdResourceName}]! Result: $expanded")
                            if (expanded) break
                        }
                    }
                }
                if (!expanded && capBounds.width() > 0 && capBounds.height() > 0) {
                    expanded = dispatchTapGesture(service, capBounds.centerX().toFloat(), capBounds.centerY().toFloat(), bridge)
                }
                lastTapAttemptTimeMs = now
                bridge.log("FOCUS_SHIELD_TAP", "👉 [STAGE 3] Caption fallback tap result: $expanded")
            }
        }

        // --- STAGE 4: Secondary Target: android:id/minimize_window ---
        for (w in windows) {
            val root = getWindowRoot(w) ?: continue
            val minNodes = root.findAccessibilityNodeInfosByViewId("android:id/minimize_window")
            if (minNodes.isNotEmpty()) {
                val minBtn = minNodes[0]
                bridge.log("FOCUS_SHIELD_TAP", "🔍 [STAGE 4] Found 'android:id/minimize_window'! Clickable=${minBtn.isClickable}")
                if (minBtn.isClickable) {
                    lastTapAttemptTimeMs = now
                    val minimized = minBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    bridge.log("FOCUS_SHIELD_TAP", "💥 [STAGE 4] Clicked minimize_window! Result: $minimized")
                    if (minimized) {
                        engageKillSuccess(bridge)
                        return true
                    }
                }
            }
        }

        // --- STAGE 5: Generic Close Candidate Search ---
        for (w in windows) {
            val root = getWindowRoot(w) ?: continue
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            findCloseCandidates(root, candidates)
            for (cand in candidates) {
                if (cand.viewIdResourceName == "android:id/close_window") continue
                if (cand.isClickable) {
                    lastTapAttemptTimeMs = now
                    val clicked = cand.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    bridge.log("FOCUS_SHIELD_TAP", "💥 [STAGE 5] Clicked generic candidate [${cand.contentDescription ?: cand.text}]! Result: $clicked")
                    if (clicked) {
                        engageKillSuccess(bridge)
                        return true
                    }
                }
            }
        }

        // Async debug dump if still not closed (Throttled every 3.5s, background thread)
        if (now - lastSubtreeDumpTimeMs > 3500L && windows.isNotEmpty()) {
            lastSubtreeDumpTimeMs = now
            val primaryRoot = getWindowRoot(windows[0])
            if (primaryRoot != null) {
                CoroutineScope(Dispatchers.Default).launch {
                    val dump = dumpSubtree(primaryRoot, 0)
                    bridge.log("FOCUS_SHIELD_DUMP", "❌ [DEBUG DUMP] Pop-up active. Hierarchy:\n$dump")
                }
            }
        }

        return false
    }

    private fun getWindowRoot(entry: Any): AccessibilityNodeInfo? {
        return try {
            val field = entry.javaClass.getDeclaredField("root")
            field.isAccessible = true
            field.get(entry) as? AccessibilityNodeInfo
        } catch (_: Exception) {
            null
        }
    }

    private fun engageKillSuccess(bridge: HostBridge) {
        bridge.vibrate(150L)
        isClosingPopUp = true
        dismissPopupOverlay(bridge)
        CoroutineScope(Dispatchers.Main).launch {
            delay(150L)
            dismissPopupOverlay(bridge)
            delay(350L)
            isClosingPopUp = false
        }
    }

    private fun dispatchTapGesture(service: AccessibilityService, x: Float, y: Float, bridge: HostBridge): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return try {
                val path = Path().apply { moveTo(x, y) }
                val stroke = GestureDescription.StrokeDescription(path, 0, 50)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                val dispatched = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        bridge.log("FOCUS_SHIELD_TAP", "🖐️ [GESTURE_SUCCESS] Physical tap completed at ($x, $y)!")
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        bridge.log("FOCUS_SHIELD_TAP", "⚠️ [GESTURE_CANCELLED] Physical tap cancelled at ($x, $y).")
                    }
                }, null)
                bridge.log("FOCUS_SHIELD_TAP", "🖐️ [GESTURE_DISPATCH] Physical tap dispatched at ($x, $y). Result: $dispatched")
                dispatched
            } catch (e: Exception) {
                bridge.log("FOCUS_SHIELD_TAP_ERR", "Physical gesture error: ${e.message}")
                false
            }
        }
        return false
    }

    private fun findNodeByDescOrText(node: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val lowerTarget = target.lowercase(Locale.US)
        val desc = node.contentDescription?.toString()?.lowercase(Locale.US) ?: ""
        val text = node.text?.toString()?.lowercase(Locale.US) ?: ""

        if (desc.contains(lowerTarget) || text.contains(lowerTarget)) {
            if (node.isClickable) return node
            if (node.parent?.isClickable == true) return node.parent
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val found = findNodeByDescOrText(child, target)
            if (found != null) return found
        }
        return null
    }

    private fun findCloseCandidates(node: AccessibilityNodeInfo?, results: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        val id = node.viewIdResourceName?.lowercase(Locale.US) ?: ""
        val desc = node.contentDescription?.toString()?.lowercase(Locale.US) ?: ""
        val text = node.text?.toString()?.lowercase(Locale.US) ?: ""

        val isCloseId = id.contains("close") || id.contains("dismiss") || id.contains("cancel") || id.contains("exit")
        val isCloseDesc = desc.contains("close") || desc.contains("dismiss") || desc.contains("exit") || desc == "x"
        val isCloseText = text == "close" || text == "x" || text == "✕" || text == "✖"

        if (isCloseId || isCloseDesc || isCloseText) {
            results.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findCloseCandidates(child, results)
            }
        }
    }

    private fun dumpSubtree(node: AccessibilityNodeInfo?, depth: Int): String {
        if (node == null || depth > 5) return ""
        val indent = "  ".repeat(depth)
        val id = node.viewIdResourceName ?: "null"
        val cls = node.className?.toString()?.substringAfterLast(".") ?: "View"
        val desc = node.contentDescription ?: ""
        val text = node.text ?: ""
        val clickable = if (node.isClickable) " [CLICKABLE]" else ""
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val sb = StringBuilder()
        sb.append("$indent- <$cls id=\"$id\" desc=\"$desc\" text=\"$text\" bounds=\"${bounds.toShortString()}\"$clickable>\n")

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                sb.append(dumpSubtree(child, depth + 1))
            }
        }
        return sb.toString()
    }

    private fun mountOrUpdatePopupOverlay(
        context: Context,
        bridge: HostBridge,
        appLabel: String,
        bounds: Rect
    ) {
        if (isClosingPopUp) {
            bridge.log("FOCUS_SHIELD_POPUP", "Suppressing overlay mount: window is actively closing.")
            return
        }

        val x = bounds.left.coerceAtLeast(0)
        val y = bounds.top.coerceAtLeast(0)
        val width = bounds.width().coerceAtLeast(350)
        val height = bounds.height().coerceAtLeast(400)

        if (!isOverlayMounted) {
            if (!bridge.canDrawOverlays()) {
                bridge.log("FOCUS_SHIELD_WARN", "Cannot mount pop-up shield: Overlay permission missing!")
                return
            }

            val overlayView = buildBlockingOverlayView(context, appLabel) {
                bridge.log("FOCUS_SHIELD_POPUP", "User tapped 'Dismiss Pop-Up' on overlay. Dispatching Home and eviction.")
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(homeIntent)
                dismissPopupOverlay(bridge)
            }

            val shown = bridge.showOverlay(
                tag = POPUP_OVERLAY_TAG,
                view = overlayView,
                width = width,
                height = height,
                gravity = Gravity.TOP or Gravity.START,
                x = x,
                y = y,
                focusable = false,
                touchable = true
            )

            if (shown) {
                isOverlayMounted = true
                lastOverlayBounds.set(bounds)
                bridge.log("FOCUS_SHIELD_POPUP", "Mounted precision shield on [$appLabel] covering full window: [x=$x, y=$y, ${width}x${height}]")
            }
        } else {
            if (lastOverlayBounds != bounds) {
                lastOverlayBounds.set(bounds)
                bridge.updateOverlay(POPUP_OVERLAY_TAG, x, y, width, height)
                bridge.log("FOCUS_SHIELD_POPUP", "Repositioned precision shield to [x=$x, y=$y, ${width}x${height}]")
            }
        }
    }

    private fun dismissPopupOverlay(bridge: HostBridge) {
        if (isOverlayMounted) {
            bridge.dismissOverlay(POPUP_OVERLAY_TAG)
            isOverlayMounted = false
            lastOverlayBounds.setEmpty()
            bridge.log("FOCUS_SHIELD_POPUP", "Precision pop-up shield dismissed.")
        }
    }

    private fun buildBlockingOverlayView(
        context: Context,
        appLabel: String,
        onCloseClicked: () -> Unit
    ): View {
        val density = context.resources.displayMetrics.density
        return FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#EE0D1117"))
            isClickable = true
            isFocusable = false

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(
                    (20 * density).toInt(),
                    (20 * density).toInt(),
                    (20 * density).toInt(),
                    (20 * density).toInt()
                )

                val iconView = TextView(context).apply {
                    text = "🔒"
                    textSize = 34f
                    gravity = Gravity.CENTER
                }
                addView(iconView)

                val titleView = TextView(context).apply {
                    text = "Focus Lock Active"
                    textSize = 17f
                    setTextColor(android.graphics.Color.WHITE)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
                }
                addView(titleView)

                val descView = TextView(context).apply {
                    text = "$appLabel is locked in pop-up view."
                    textSize = 12f
                    setTextColor(android.graphics.Color.parseColor("#8B949E"))
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, (18 * density).toInt())
                }
                addView(descView)

                val closeBtn = Button(context).apply {
                    text = "Dismiss Pop-Up"
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(android.graphics.Color.parseColor("#DA3633"))
                    setOnClickListener {
                        onCloseClicked()
                    }
                }
                addView(closeBtn)
            }

            addView(
                container,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            )
        }
    }

    private fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun getForegroundApp(context: Context): String? {
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return lastKnownForegroundApp
            val now = System.currentTimeMillis()

            val beginTime = if (lastEventQueryTimeMs > 0L && (now - lastEventQueryTimeMs) < 60_000L) {
                (lastEventQueryTimeMs - 5000L).coerceAtLeast(now - 15 * 60 * 1000L)
            } else {
                now - 15 * 60 * 1000L
            }

            val events = usm.queryEvents(beginTime, now)
            lastEventQueryTimeMs = now

            val event = UsageEvents.Event()
            var latestPackage: String? = null
            var latestTimestamp = 0L

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    if (event.timeStamp >= latestTimestamp) {
                        latestTimestamp = event.timeStamp
                        latestPackage = event.packageName
                    }
                }
            }

            if (latestPackage != null) {
                lastKnownForegroundApp = latestPackage
            }
            return lastKnownForegroundApp
        } catch (_: Exception) {
            return lastKnownForegroundApp
        }
    }

    private fun checkAndDeflectForeground(
        context: Context,
        bridge: HostBridge,
        isPanicActive: Boolean,
        isNightSchedule: Boolean
    ) {
        if (isOverlayMounted || isClosingPopUp) {
            return
        }

        val fgPkg = getForegroundApp(context) ?: return
        if (fgPkg == context.packageName || fgPkg == "com.omni.hub") return

        // Settings explicitly whitelisted
        if (fgPkg == "com.android.settings" || fgPkg.startsWith("com.android.settings.")) {
            return
        }

        val customPkgs = customLockedApps.map { it.packageName }.toSet()
        val isBrowser = KNOWN_BROWSER_PACKAGES.contains(fgPkg)
        val isCustomBlocked = customPkgs.contains(fgPkg)

        val isTargetBlocked = isBrowser || isCustomBlocked

        if (isTargetBlocked) {
            val appLabel = try {
                val appInfo = context.packageManager.getApplicationInfo(fgPkg, 0)
                context.packageManager.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                fgPkg
            }

            val reason = when {
                isPanicActive -> "Focus Lock active! Browsers and blacklist trapped."
                isNightSchedule -> "Night Guard active (10:00 PM – 6:00 AM). Sleep mode engaged."
                else -> "App is blacklisted."
            }

            bridge.log("FOCUS_SHIELD", "Deflecting full-screen blocked app [$appLabel] ($fgPkg) to Home!")

            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(homeIntent)
            bridge.vibrate(200L)
            bridge.showToast("Focus Shield: $appLabel is locked. $reason")
        }
    }

    private fun checkImmediateEviction(context: Context, bridge: HostBridge) {
        try {
            val accurateTime = NetworkClockGuard.getAccurateTimeMs()
            val isPanic = manualLockUntilMs > accurateTime
            val cal = Calendar.getInstance().apply { timeInMillis = accurateTime }
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val isNight = hour >= 22 || hour < 6
            if (isPanic || isNight) {
                checkAndDeflectForeground(context, bridge, isPanic, isNight)
            }
        } catch (_: Exception) {}
    }

    private fun ensureMonitoringRunning(context: Context, bridge: HostBridge) {
        synchronized(sentinelLock) {
            if (screenReceiver == null) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent?) {
                        when (intent?.action) {
                            Intent.ACTION_SCREEN_OFF -> {
                                lastEventQueryTimeMs = 0L
                                dismissPopupOverlay(bridge)
                                if (isWakeLockHeld) {
                                    bridge.releaseWakeLock()
                                    isWakeLockHeld = false
                                    bridge.log("FOCUS_SHIELD", "Screen OFF: WakeLock released. Hibernating.")
                                }
                            }
                            Intent.ACTION_SCREEN_ON,
                            Intent.ACTION_USER_PRESENT -> {
                                lastEventQueryTimeMs = 0L
                                if (!isWakeLockHeld) {
                                    bridge.acquireWakeLock("FocusShieldSentinel")
                                    isWakeLockHeld = true
                                    bridge.log("FOCUS_SHIELD", "Screen ON: WakeLock engaged.")
                                }
                                checkImmediateEviction(context, bridge)
                            }
                        }
                    }
                }
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                try {
                    context.applicationContext.registerReceiver(receiver, filter)
                    screenReceiver = receiver
                } catch (_: Exception) {}
            }

            if (globalMonitorJob?.isActive == true) return
            isRunning = true

            globalMonitorJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                NetworkClockGuard.syncNetworkTime(bridge)
                var lastClockSyncTime = 0L

                while (isActive) {
                    try {
                        delay(350L)

                        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                        val isInteractive = pm?.isInteractive ?: true
                        val isLocked = km?.isKeyguardLocked ?: false

                        if (!isInteractive || isLocked) {
                            if (isWakeLockHeld) {
                                bridge.releaseWakeLock()
                                isWakeLockHeld = false
                            }
                            continue
                        } else {
                            if (!isWakeLockHeld) {
                                bridge.acquireWakeLock("FocusShieldSentinel")
                                isWakeLockHeld = true
                            }
                        }

                        val nowRealtime = SystemClock.elapsedRealtime()
                        if (nowRealtime - lastClockSyncTime > 15 * 60 * 1000L) {
                            lastClockSyncTime = nowRealtime
                            NetworkClockGuard.syncNetworkTime(bridge)
                        }

                        val accurateTime = NetworkClockGuard.getAccurateTimeMs()
                        val isPanicActive = manualLockUntilMs > accurateTime

                        val cal = Calendar.getInstance().apply { timeInMillis = accurateTime }
                        val hour = cal.get(Calendar.HOUR_OF_DAY)
                        val isNightSchedule = hour >= 22 || hour < 6

                        val isEnforcing = isPanicActive || isNightSchedule

                        if (isEnforcing) {
                            checkAndDeflectForeground(context, bridge, isPanicActive, isNightSchedule)
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        bridge.log("FOCUS_SHIELD_ERR", "Sentinel loop auto-recovered: ${t.message}")
                        delay(1000L)
                    }
                }
            }
        }
    }

    private fun loadPersistedState(bridge: HostBridge) {
        try {
            val bytes = bridge.readFile("config/focus_shield_state.json")
            if (bytes != null && bytes.isNotEmpty()) {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                manualLockUntilMs = json.optLong("manual_lock_until_ms", 0L)
                val appsArr = json.optJSONArray("custom_apps") ?: JSONArray()
                val list = mutableListOf<LockedAppItem>()
                for (i in 0 until appsArr.length()) {
                    val o = appsArr.getJSONObject(i)
                    list.add(
                        LockedAppItem(
                            packageName = o.getString("pkg"),
                            appName = o.getString("name"),
                            addedAt = o.optLong("time", 0L)
                        )
                    )
                }
                customLockedApps = list
            }
        } catch (_: Exception) {}
    }

    private fun savePersistedState(bridge: HostBridge) {
        try {
            val json = JSONObject().apply {
                put("manual_lock_until_ms", manualLockUntilMs)
                val arr = JSONArray()
                customLockedApps.forEach { app ->
                    arr.put(JSONObject().apply {
                        put("pkg", app.packageName)
                        put("name", app.appName)
                        put("time", app.addedAt)
                    })
                }
                put("custom_apps", arr)
            }
            bridge.saveFile("config/focus_shield_state.json", json.toString(2).toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {}
    }

    @Composable
    fun FocusShieldScreen(
        context: Context,
        bridge: HostBridge,
        plugin: FocusShieldPlugin
    ) {
        var showAddAppDialog by remember { mutableStateOf(false) }
        var appToAddPending by remember { mutableStateOf<InstalledAppInfo?>(null) }
        var currentTimeMs by remember { mutableLongStateOf(NetworkClockGuard.getAccurateTimeMs()) }
        var currentManualLockUntil by remember { mutableLongStateOf(manualLockUntilMs) }
        var lockedAppsList by remember { mutableStateOf(customLockedApps) }

        LaunchedEffect(Unit) {
            NetworkClockGuard.syncNetworkTime(bridge)
            while (true) {
                currentTimeMs = NetworkClockGuard.getAccurateTimeMs()
                currentManualLockUntil = manualLockUntilMs
                lockedAppsList = customLockedApps
                delay(1000L)
            }
        }

        val isPanicActive = currentManualLockUntil > currentTimeMs
        val remainingMs = (currentManualLockUntil - currentTimeMs).coerceAtLeast(0L)
        val remMin = remainingMs / 60_000L
        val remSec = (remainingMs % 60_000L) / 1000L

        val cal = Calendar.getInstance().apply { timeInMillis = currentTimeMs }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val isNightActive = hour >= 22 || hour < 6

        val hasUsageStats = remember(currentTimeMs) {
            hasUsageStatsPermission(context)
        }
        val isA11yActive = remember(currentTimeMs) {
            AccessibilityDispatcher.isServiceActive(context)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D1117))
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🛡️", fontSize = 24.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Focus Shield", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("UsageStats & Pop-Up Hunter", color = Color(0xFF8B949E), fontSize = 11.sp)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF161B22),
                    border = BorderStroke(1.dp, Color(0xFF30363D))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (isA11yActive) Color(0xFF3FB950) else Color(0xFFDA3633))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (isA11yActive) "Pop-Up Sentry Armed" else "A11y Disabled",
                            color = Color(0xFFC9D1D9),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (!hasUsageStats) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF4C1C1C)),
                    border = BorderStroke(1.dp, Color(0xFFF85149)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Usage Access Required", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Grant Usage Access so Focus Shield can detect and deflect browsers.", color = Color(0xFFE8EAED), fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF85149)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Grant", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (!isA11yActive) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF381A4C)),
                    border = BorderStroke(1.dp, Color(0xFFBC8CFF)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Accessibility Needed for Pop-ups", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Enable Omni Accessibility Service to kill floating & freeform pop-up browser loopholes.", color = Color(0xFFE8EAED), fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8957E5)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Enable", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Big Panic Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isPanicActive) Color(0xFF3D1616) else Color(0xFF161B22)
                ),
                border = BorderStroke(
                    1.5.dp,
                    if (isPanicActive) Color(0xFFF85149) else Color(0xFF1F6FEB)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                if (isPanicActive) "🔒 1-Hour Lockout Active" else "Instant Emergency Shield",
                                color = if (isPanicActive) Color(0xFFF85149) else Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                if (isPanicActive) "Irreversible: No early cancel" else "Locks external browsers, pop-ups, and blacklist immediately",
                                color = Color(0xFF8B949E),
                                fontSize = 11.sp
                            )
                        }

                        if (isPanicActive) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFFF85149).copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, Color(0xFFF85149))
                            ) {
                                Text(
                                    String.format(Locale.US, "%02d:%02d", remMin, remSec),
                                    color = Color(0xFFF85149),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Button(
                        onClick = {
                            if (!isPanicActive) {
                                val newUntil = NetworkClockGuard.getAccurateTimeMs() + 3600_000L
                                manualLockUntilMs = newUntil
                                currentManualLockUntil = newUntil
                                plugin.savePersistedState(bridge)
                                plugin.checkImmediateEviction(context, bridge)
                                bridge.vibrate(200L)
                                bridge.showToast("🔒 1-Hour Lockout Engaged! Pop-ups & browsers locked.")
                            } else {
                                bridge.showToast("⛔ Lockout is irreversible! Wait for timer.")
                                bridge.vibrate(60L)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPanicActive) Color(0xFF21262D) else Color(0xFF1F6FEB)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Text(
                            if (isPanicActive) "⛔ Locked (Active for ${remMin}m ${remSec}s)" else "🛡️ Lock All Browsers for 1 Hour",
                            color = if (isPanicActive) Color(0xFFF85149) else Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Night Guard Card
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                border = BorderStroke(1.dp, Color(0xFF30363D)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🌙", fontSize = 22.sp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Night Guard (10:00 PM – 6:00 AM)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Automatic sleep protection (Omni Browser exempt)", color = Color(0xFF8B949E), fontSize = 10.sp)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isNightActive) Color(0xFF3FB950).copy(alpha = 0.2f) else Color(0xFF21262D)
                    ) {
                        Text(
                            if (isNightActive) "ACTIVE NOW" else "ARMED",
                            color = if (isNightActive) Color(0xFF3FB950) else Color(0xFF8B949E),
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Permanent Blacklist Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Permanent Blacklist (${lockedAppsList.size})", color = Color(0xFFC9D1D9), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Apps trapped forever in lockouts", color = Color(0xFF8B949E), fontSize = 10.sp)
                }

                Button(
                    onClick = { showAddAppDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add App", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(8.dp))

            if (lockedAppsList.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔒", fontSize = 32.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("No custom apps blacklisted yet", color = Color(0xFF8B949E), fontSize = 13.sp)
                        Text("All web browsers are already auto-blocked.", color = Color(0xFF484F58), fontSize = 11.sp)
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(lockedAppsList, key = { it.packageName }) { app ->
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                            border = BorderStroke(1.dp, Color(0xFF30363D)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.appName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(app.packageName, color = Color(0xFF8B949E), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFF85149).copy(alpha = 0.15f),
                                    border = BorderStroke(1.dp, Color(0xFFF85149).copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        "LOCKED",
                                        color = Color(0xFFF85149),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Comprehensive Installed Apps Picker Dialog
        if (showAddAppDialog) {
            val installedApps = remember {
                val pm = context.packageManager
                val appsMap = mutableMapOf<String, String>()

                val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                val resolveList = pm.queryIntentActivities(launcherIntent, 0)
                for (ri in resolveList) {
                    val pkg = ri.activityInfo.packageName
                    if (pkg != context.packageName && pkg != "com.omni.hub") {
                        appsMap[pkg] = ri.loadLabel(pm).toString()
                    }
                }

                try {
                    val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    for (app in allApps) {
                        val pkg = app.packageName
                        if (pkg != context.packageName && pkg != "com.omni.hub") {
                            val isUserApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                                            (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                            if (isUserApp && !appsMap.containsKey(pkg)) {
                                val label = pm.getApplicationLabel(app).toString()
                                if (label.isNotBlank() && !label.startsWith("com.")) {
                                    appsMap[pkg] = label
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}

                appsMap.map { InstalledAppInfo(it.key, it.value) }
                    .sortedBy { it.appName.lowercase(Locale.US) }
            }

            var searchQuery by remember { mutableStateOf("") }
            val filteredApps = remember(searchQuery, installedApps) {
                if (searchQuery.isBlank()) installedApps
                else installedApps.filter {
                    it.appName.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
                }
            }

            AlertDialog(
                onDismissRequest = { showAddAppDialog = false },
                containerColor = Color(0xFF161B22),
                title = { Text("Select App to Blacklist", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search ${installedApps.size} installed apps...", color = Color(0xFF8B949E), fontSize = 12.sp) },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF8B949E), modifier = Modifier.size(16.dp)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text("⚠️ Caution: Added apps CANNOT be removed.", color = Color(0xFFF85149), fontSize = 10.sp, fontWeight = FontWeight.Bold)

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(filteredApps, key = { it.packageName }) { appInfo ->
                                val isAlreadyAdded = lockedAppsList.any { it.packageName == appInfo.packageName }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isAlreadyAdded) Color(0xFF21262D) else Color.Transparent)
                                        .clickable(enabled = !isAlreadyAdded) {
                                            appToAddPending = appInfo
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(appInfo.appName, color = if (isAlreadyAdded) Color(0xFF8B949E) else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Text(appInfo.packageName, color = Color(0xFF484F58), fontSize = 9.sp, maxLines = 1)
                                    }

                                    if (isAlreadyAdded) {
                                        Text("Added", color = Color(0xFF8B949E), fontSize = 10.sp)
                                    } else {
                                        Text("+ Add", color = Color(0xFF58A6FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAddAppDialog = false }) {
                        Text("Close", color = Color(0xFF8B949E))
                    }
                }
            )
        }

        // Irreversible Confirmation Prompt
        if (appToAddPending != null) {
            val pending = appToAddPending!!
            AlertDialog(
                onDismissRequest = { appToAddPending = null },
                containerColor = Color(0xFF21262D),
                title = { Text("⚠️ Lock ${pending.appName} Forever?", color = Color(0xFFF85149), fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                text = {
                    Text(
                        "Once you add '${pending.appName}', there is NO undo and NO delete button. It will be permanently blocked from 10 PM to 6 AM and during any 1-hour lockout.\n\nAre you sure you want to lock it?",
                        color = Color(0xFFC9D1D9),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val newItem = LockedAppItem(pending.packageName, pending.appName)
                            val updated = customLockedApps + newItem
                            customLockedApps = updated
                            lockedAppsList = updated
                            plugin.savePersistedState(bridge)
                            plugin.checkImmediateEviction(context, bridge)
                            bridge.vibrate(100L)
                            bridge.showToast("🔒 Permanently locked ${pending.appName}!")
                            appToAddPending = null
                            showAddAppDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDA3633))
                    ) {
                        Text("Yes, Lock Forever", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { appToAddPending = null }) {
                        Text("Cancel", color = Color(0xFF8B949E))
                    }
                }
            )
        }
    }

    companion object {
        private const val POPUP_OVERLAY_TAG = "focus_shield_popup_block"
        private val sentinelLock = Any()
        @Volatile private var globalMonitorJob: Job? = null
        @Volatile var isRunning = false
        @Volatile var manualLockUntilMs = 0L
        @Volatile var customLockedApps = listOf<LockedAppItem>()
        @Volatile var isWakeLockHeld = false
        @Volatile var screenReceiver: BroadcastReceiver? = null
        @Volatile var lastEventQueryTimeMs = 0L
        @Volatile var lastKnownForegroundApp: String? = null
        @Volatile var activeContext: Context? = null
        @Volatile var activeBridge: HostBridge? = null

        @Volatile var isOverlayMounted = false
        @Volatile var isClosingPopUp = false
        @Volatile var lastOverlayBounds = Rect()
        @Volatile var lastTargetSeenTimeMs = 0L
        @Volatile var lastSubtreeDumpTimeMs = 0L
        @Volatile var lastTapAttemptTimeMs = 0L

        val KNOWN_BROWSER_PACKAGES = setOf(
            "com.sec.android.app.sbrowser",        // Samsung Internet
            "com.sec.android.app.sbrowser.beta",   // Samsung Internet Beta
            "com.android.chrome",                  // Chrome Stable
            "com.chrome.beta",                     // Chrome Beta
            "com.chrome.dev",                      // Chrome Dev
            "com.chrome.canary",                   // Chrome Canary
            "org.mozilla.firefox",                 // Firefox
            "org.mozilla.firefox_beta",            // Firefox Beta
            "org.mozilla.focus",                   // Firefox Focus
            "org.mozilla.fenix",                   // Firefox Nightly
            "com.brave.browser",                   // Brave
            "com.brave.browser_beta",              // Brave Beta
            "com.brave.browser_nightly",           // Brave Nightly
            "com.microsoft.emmx",                  // Microsoft Edge
            "com.opera.browser",                   // Opera
            "com.opera.mini.native",               // Opera Mini
            "com.opera.touch",                     // Opera Touch
            "com.opera.gx",                        // Opera GX
            "com.duckduckgo.mobile.android",       // DuckDuckGo
            "com.kiwibrowser.browser",             // Kiwi Browser
            "com.vivaldi.browser",                 // Vivaldi
            "mark.via.gp",                         // Via Browser
            "org.torproject.torbrowser",           // Tor Browser
            "com.ucmobile.intl",                   // UC Browser
            "com.cloudmosa.puffinFree",            // Puffin
            "acr.browser.barebones",               // Lightning
            "com.mi.globalbrowser",                // Mi Browser
            "com.mi.globalbrowser.mini",           // Mint Browser
            "com.android.browser"                  // AOSP Browser
        )
    }
}