package com.omni.plugin.scrolllock

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.View
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.net.Uri
import com.omni.hub.api.AccessibilityDispatcher
import com.omni.hub.api.AccessibilityListener
import com.omni.hub.api.HostBridge
import com.omni.hub.api.PluginEntry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

class ScrollLockPlugin : PluginEntry() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeContext: Context? = null
    private var activeBridge: HostBridge? = null
    private var monitorJob: Job? = null

    // State Variables
    @Volatile private var isRunning = false
    @Volatile private var currentSessionMs = 0L
    @Volatile private var lastActiveAppTimeMs = 0L
    @Volatile private var penaltyUntilMs = 0L
    @Volatile private var manualLockUntilMs = 0L
    @Volatile private var totalTimeTodayMs = 0L
    @Volatile private var totalEvictionsToday = 0
    @Volatile private var nightBlocksToday = 0
    @Volatile private var sabotageStrikes = 0
    @Volatile private var lastResetDay = -1
    @Volatile private var tamperDetected = false
    @Volatile private var youtubeSafeBypass = false
    @Volatile private var shortsDeflectedToday = 0
    @Volatile private var lastDeflectMs = 0L

    private val accessibilityListener: AccessibilityListener = { event, service ->
        handleAccessibilityEvent(event, service)
    }

    private val targetPackages = mutableStateListOf(
        "com.zhiliaoapp.musically",      // TikTok Global
        "com.zhiliaoapp.musically.go",   // TikTok Lite
        "com.ss.android.ugc.trill",      // TikTok Alternative
        "com.instagram.android",         // Instagram Reels
        "com.google.android.youtube",    // YouTube Shorts
        "com.twitter.android",           // X / Twitter
        "com.reddit.frontpage"           // Reddit
    )

    private val appDisplayNames = mapOf(
        "com.zhiliaoapp.musically" to "TikTok",
        "com.zhiliaoapp.musically.go" to "TikTok Lite",
        "com.ss.android.ugc.trill" to "TikTok Asia",
        "com.instagram.android" to "Instagram",
        "com.google.android.youtube" to "YouTube Shorts",
        "com.twitter.android" to "X (Twitter)",
        "com.reddit.frontpage" to "Reddit"
    )

    override fun onCreateView(context: Context, bridge: HostBridge, baseDir: String): View {
        activeContext = context
        activeBridge = bridge
        loadPersistedState(bridge)
        createNotificationChannel(context)
        ensureMonitoringRunning(context, bridge)
        AccessibilityDispatcher.addListener(accessibilityListener)

        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        background = Color(0xFF0D1117),
                        surface = Color(0xFF161B22),
                        primary = Color(0xFFF85149)
                    )
                ) {
                    ScrollLockDashboard(context, bridge)
                }
            }
        }
    }

    override fun onStart(context: Context, bridge: HostBridge, baseDir: String) {
        activeContext = context
        activeBridge = bridge
        loadPersistedState(bridge)
        createNotificationChannel(context)
        ensureMonitoringRunning(context, bridge)
        AccessibilityDispatcher.addListener(accessibilityListener)
        bridge.log("SCROLL_LOCK", "🛡️ ScrollLock Daemon booted in background.")
        bridge.startForegroundTask("ScrollLock Sentinel Armed", "Protecting against doom scrolling")
    }

    override fun onStop(context: Context) {
        dismissSirenNotification(context)
        AccessibilityDispatcher.removeListener(accessibilityListener)
        monitorJob?.cancel()
        isRunning = false
        scope.cancel()
        activeBridge?.stopForegroundTask()
        activeBridge?.log("SCROLL_LOCK", "🛑 ScrollLock Daemon halted.")
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

    private fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    private fun getMissingPermissions(context: Context): List<String> {
        val missing = mutableListOf<String>()
        if (!hasUsageStatsPermission(context)) missing.add("Usage Access")
        if (!hasOverlayPermission(context)) missing.add("Display Over Other Apps")
        if (!hasNotificationPermission(context)) missing.add("Notifications")
        return missing
    }

    private fun isNightCurfew(): Boolean {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        // Hardcoded Curfew: 11:00 PM (23:00) to 6:00 AM (06:00)
        return hour >= 23 || hour < 6
    }

    private fun getForegroundApp(context: Context): String? {
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
            val endTime = System.currentTimeMillis()
            val beginTime = endTime - 1000 * 10

            val events = usm.queryEvents(beginTime, endTime)
            var lastPackage: String? = null
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastPackage = event.packageName
                }
            }
            if (lastPackage != null) return lastPackage

            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, beginTime, endTime)
            val sorted = stats.filter { it.lastTimeUsed > 0 }.maxByOrNull { it.lastTimeUsed }
            return sorted?.packageName
        } catch (_: Exception) {
            return null
        }
    }

    private fun kickToHome(context: Context, bridge: HostBridge, reason: String) {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(homeIntent)
            bridge.vibrate(600L)
            bridge.showToast("⛔ ScrollLock: $reason")
            bridge.log("SCROLL_LOCK_EVICT", reason)
        } catch (e: Exception) {
            bridge.log("SCROLL_LOCK_ERR", "Eviction failed: ${e.message}")
        }
    }

    private fun ensureMonitoringRunning(context: Context, bridge: HostBridge) {
        if (monitorJob?.isActive == true) return
        isRunning = true

        monitorJob = scope.launch {
            var lastTickMs = System.currentTimeMillis()
            var lastTamperNagMs = 0L

            while (isActive) {
                delay(1500L)
                val now = System.currentTimeMillis()
                val delta = (now - lastTickMs).coerceIn(0L, 5000L)
                lastTickMs = now

                // Check midnight reset
                val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
                if (lastResetDay != currentDay) {
                    lastResetDay = currentDay
                    totalTimeTodayMs = 0L
                    totalEvictionsToday = 0
                    nightBlocksToday = 0
                    shortsDeflectedToday = 0
                    savePersistedState(bridge)
                }

                // 1. Anti-Tamper Watchdog Verification (Usage + Overlay + Notifications)
                val missingPerms = getMissingPermissions(context)
                if (missingPerms.isNotEmpty()) {
                    val missingSummary = missingPerms.joinToString(", ")
                    if (!tamperDetected) {
                        tamperDetected = true
                        sabotageStrikes++
                        savePersistedState(bridge)
                        bridge.log("SCROLL_LOCK_TAMPER", "🚨 INTEGRITY BREACH: [$missingSummary] revoked! Strike #$sabotageStrikes recorded.")
                    }

                    // Pester relentlessly every 7 seconds: Haptics, Siren Alert, and Direct Screen Takeover
                    if (now - lastTamperNagMs > 7_000L) {
                        lastTamperNagMs = now
                        bridge.vibrate(900L)
                        sendSirenNotification(
                            context,
                            "🚨 SCROLLLOCK PERMISSION BREACH",
                            "Missing: $missingSummary! Strike #$sabotageStrikes. Tap to restore immediately."
                        )
                        try {
                            context.startActivity(createSettingsIntent(context))
                        } catch (e: Exception) {
                            bridge.log("SCROLL_LOCK_ERR", "Failed auto-launching settings: ${e.message}")
                        }
                    }
                    continue
                } else {
                    if (tamperDetected) {
                        tamperDetected = false
                        dismissSirenNotification(context)
                        bridge.showToast("✅ ScrollLock Integrity Restored: All permissions active.")
                        bridge.log("SCROLL_LOCK", "✅ All permissions restored. Siren dismissed.")
                    }
                }

                // 2. Poll Active Foreground App (with YouTube Safe Bypass support)
                val fgApp = getForegroundApp(context)
                val isTargetApp = fgApp != null && targetPackages.contains(fgApp) && !(youtubeSafeBypass && fgApp == "com.google.android.youtube")

                if (isTargetApp) {
                    val appName = appDisplayNames[fgApp] ?: fgApp ?: "Target App"

                    // Rule A: Bedtime Curfew Eviction (11PM - 6AM)
                    if (isNightCurfew()) {
                        nightBlocksToday++
                        totalEvictionsToday++
                        savePersistedState(bridge)
                        kickToHome(context, bridge, "Bedtime Curfew Active (11PM-6AM). Put the phone down!")
                        continue
                    }

                    // Rule B: Penalty Box Enforcement (1-hour cooldown)
                    if (now < penaltyUntilMs) {
                        totalEvictionsToday++
                        savePersistedState(bridge)
                        val remainingMin = ((penaltyUntilMs - now) / 60_000L).coerceAtLeast(1)
                        kickToHome(context, bridge, "Penalty active! You are locked out of $appName for $remainingMin more min.")
                        continue
                    }

                    // Rule C: Manual Self-Lockout Enforcement
                    if (now < manualLockUntilMs) {
                        totalEvictionsToday++
                        savePersistedState(bridge)
                        val remainingMin = ((manualLockUntilMs - now) / 60_000L).coerceAtLeast(1)
                        kickToHome(context, bridge, "Self-imposed focus lock! $remainingMin more min remaining.")
                        continue
                    }

                    // Rule D: Session Accumulation & 30-Minute Cap
                    currentSessionMs += delta
                    totalTimeTodayMs += delta
                    lastActiveAppTimeMs = now

                    // 30 Minutes Continuous Limit Reached!
                    if (currentSessionMs >= 30 * 60 * 1000L) {
                        currentSessionMs = 0L
                        penaltyUntilMs = now + (60 * 60 * 1000L) // 1 Hour Penalty
                        totalEvictionsToday++
                        savePersistedState(bridge)
                        bridge.log("SCROLL_LOCK", "⛔ 30m limit hit on $appName! Entering 1-hour penalty box.")
                        kickToHome(context, bridge, "30-minute scroll limit reached! 1-hour penalty box initiated.")
                        continue
                    }

                } else {
                    // Grace Period: Only reset session if absent from all target apps for >= 5 minutes
                    if (now - lastActiveAppTimeMs >= 5 * 60 * 1000L) {
                        currentSessionMs = 0L
                    }
                }
            }
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ScrollLock Anti-Doom Sentinel Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority eviction alerts and tamper breach warnings"
                enableVibration(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent, service: AccessibilityService) {
        if (!youtubeSafeBypass) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.google.android.youtube") return

        val now = System.currentTimeMillis()
        if (now - lastDeflectMs < 1200L) return

        val root = try { service.rootInActiveWindow } catch (_: Exception) { null } ?: return
        try {
            val shortsReel = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/reel_watch_fragment_root")
            val shortsPlayer = if (shortsReel.isNullOrEmpty()) root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/reel_recycler") else shortsReel
            val isShorts = !shortsPlayer.isNullOrEmpty()

            if (isShorts) {
                lastDeflectMs = now
                var clicked = false

                // Tier 1: Click Home button on bottom navigation bar
                val homeNodes = root.findAccessibilityNodeInfosByText("Home")
                if (!homeNodes.isNullOrEmpty()) {
                    for (node in homeNodes) {
                        var target: AccessibilityNodeInfo? = node
                        while (target != null && !target.isClickable) {
                            target = target.parent
                        }
                        if (target?.isClickable == true) {
                            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            clicked = true
                            break
                        }
                    }
                }

                // Tier 2: Fallback Global Action Back
                if (!clicked) {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                }

                shortsDeflectedToday++
                activeBridge?.let { savePersistedState(it) }
                activeBridge?.vibrate(350L)
                activeBridge?.showToast("🚫 Shorts Deflected! Educational YouTube allowed.")
                activeBridge?.log("SCROLL_LOCK", "🚫 YouTube Short deflected (Tier: ${if (clicked) "HomeTab" else "GlobalBack"})")
            }
        } catch (e: Exception) {
            activeBridge?.log("SCROLL_LOCK_ERR", "Error in shorts deflector: ${e.message}")
        }
    }

    private fun createSettingsIntent(context: Context): Intent {
        return when {
            !hasUsageStatsPermission(context) -> {
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            }
            !hasOverlayPermission(context) -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                } else {
                    Intent(Settings.ACTION_SETTINGS)
                }
            }
            !hasNotificationPermission(context) -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                }
            }
            else -> {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            }
        }.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    private fun dismissSirenNotification(context: Context) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(8892)
        } catch (_: Exception) {}
    }

    private fun sendSirenNotification(context: Context, title: String, message: String) {
        try {
            val specificIntent = createSettingsIntent(context)
            val pi = android.app.PendingIntent.getActivity(
                context,
                8891,
                specificIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setAutoCancel(true)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(8892, notif)
        } catch (_: Exception) {}
    }

    private fun loadPersistedState(bridge: HostBridge) {
        try {
            val bytes = bridge.readFile("scroll_lock_state.json") ?: return
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            totalTimeTodayMs = json.optLong("total_time_today_ms", 0L)
            totalEvictionsToday = json.optInt("total_evictions_today", 0)
            nightBlocksToday = json.optInt("night_blocks_today", 0)
            sabotageStrikes = json.optInt("sabotage_strikes", 0)
            lastResetDay = json.optInt("last_reset_day", -1)
            penaltyUntilMs = json.optLong("penalty_until_ms", 0L)
            manualLockUntilMs = json.optLong("manual_lock_until_ms", 0L)
            currentSessionMs = json.optLong("current_session_ms", 0L)
            lastActiveAppTimeMs = json.optLong("last_active_app_time_ms", 0L)
            youtubeSafeBypass = json.optBoolean("youtube_safe_bypass", false)
            shortsDeflectedToday = json.optInt("shorts_deflected_today", 0)

            val targetsArray = json.optJSONArray("target_packages")
            if (targetsArray != null && targetsArray.length() > 0) {
                for (i in 0 until targetsArray.length()) {
                    val p = targetsArray.getString(i)
                    if (!targetPackages.contains(p)) targetPackages.add(p)
                }
            }
        } catch (_: Exception) {}
    }

    private fun savePersistedState(bridge: HostBridge) {
        try {
            val json = JSONObject().apply {
                put("total_time_today_ms", totalTimeTodayMs)
                put("total_evictions_today", totalEvictionsToday)
                put("night_blocks_today", nightBlocksToday)
                put("sabotage_strikes", sabotageStrikes)
                put("last_reset_day", lastResetDay)
                put("penalty_until_ms", penaltyUntilMs)
                put("manual_lock_until_ms", manualLockUntilMs)
                put("current_session_ms", currentSessionMs)
                put("last_active_app_time_ms", lastActiveAppTimeMs)
                put("youtube_safe_bypass", youtubeSafeBypass)
                put("shorts_deflected_today", shortsDeflectedToday)

                val arr = JSONArray()
                targetPackages.forEach { arr.put(it) }
                put("target_packages", arr)
            }
            bridge.saveFile("scroll_lock_state.json", json.toString(2).toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {}
    }

    @Composable
    fun ScrollLockDashboard(context: Context, bridge: HostBridge) {
        var currentTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var selectedManualMinutes by remember { mutableIntStateOf(30) }
        var missingPerms by remember { mutableStateOf(getMissingPermissions(context)) }
        var isA11yActive by remember { mutableStateOf(AccessibilityDispatcher.isServiceActive()) }

        LaunchedEffect(Unit) {
            while (true) {
                currentTimeMs = System.currentTimeMillis()
                missingPerms = getMissingPermissions(context)
                isA11yActive = AccessibilityDispatcher.isServiceActive()
                delay(1000L)
            }
        }

        val inPenalty = currentTimeMs < penaltyUntilMs
        val inManualLock = currentTimeMs < manualLockUntilMs
        val isCurfew = isNightCurfew()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D1117))
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFFDA3633), Color(0xFFF85149)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🛡️", fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("ScrollLock", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            missingPerms.isNotEmpty() -> Color(0xFFDA3633)
                                            inPenalty || inManualLock || isCurfew -> Color(0xFFD29922)
                                            else -> Color(0xFF238636)
                                        }
                                    )
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                when {
                                    missingPerms.isNotEmpty() -> "DEFCON 1: TAMPER BREACH (${missingPerms.size})"
                                    inPenalty -> "PENALTY BOX ACTIVE"
                                    inManualLock -> "FOCUS LOCKOUT ACTIVE"
                                    isCurfew -> "NIGHT CURFEW ENGAGED"
                                    else -> "SENTINEL ARMED & MONITORING"
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    missingPerms.isNotEmpty() -> Color(0xFFF85149)
                                    inPenalty || inManualLock || isCurfew -> Color(0xFFE3B341)
                                    else -> Color(0xFF3FB950)
                                }
                            )
                        }
                    }
                }

                Button(
                    onClick = { bridge.close() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Exit", color = Color(0xFFC9D1D9), fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(14.dp))

            // Tamper Breach Alert Card (Usage, Overlay, Notifications)
            if (missingPerms.isNotEmpty()) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF490202)),
                    border = BorderStroke(1.dp, Color(0xFFF85149)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🚨", fontSize = 20.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("CRITICAL PERMISSIONS MISSING!", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "ScrollLock watchdog detected missing permissions: ${missingPerms.joinToString(", ")}. Haptic nag active.",
                            color = Color(0xFFFFD2D2),
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.height(10.dp))

                        if (!hasUsageStatsPermission(context)) {
                            Button(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDA3633)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().height(36.dp)
                            ) {
                                Text("1. Grant Usage Access", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(6.dp))
                        }

                        if (!hasOverlayPermission(context)) {
                            Button(
                                onClick = {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}")
                                    ).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBC4C00)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().height(36.dp)
                            ) {
                                Text("2. Grant Overlay (Draw Over Apps)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(6.dp))
                        }

                        if (!hasNotificationPermission(context)) {
                            Button(
                                onClick = {
                                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                    } else {
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = android.net.Uri.parse("package:${context.packageName}")
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                    }
                                    context.startActivity(intent)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8957E5)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().height(36.dp)
                            ) {
                                Text("3. Grant Notification Access", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Hero Status Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                border = BorderStroke(
                    1.dp,
                    when {
                        inPenalty -> Color(0xFFF85149)
                        inManualLock -> Color(0xFF1F6FEB)
                        isCurfew -> Color(0xFF8957E5)
                        else -> Color(0xFF238636)
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    when {
                        inPenalty -> {
                            val remMs = penaltyUntilMs - currentTimeMs
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("⛔ 1-HOUR PENALTY BOX", color = Color(0xFFF85149), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("Continuous 30m limit blown. All target apps locked.", color = Color(0xFF8B949E), fontSize = 11.sp)
                                }
                                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFF85149).copy(alpha = 0.2f)) {
                                    Text(
                                        formatTimer(remMs),
                                        color = Color(0xFFF85149),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        inManualLock -> {
                            val remMs = manualLockUntilMs - currentTimeMs
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("🔒 SELF-IMPOSED LOCKOUT", color = Color(0xFF58A6FF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("Irreversible deep work lockdown active.", color = Color(0xFF8B949E), fontSize = 11.sp)
                                }
                                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF1F6FEB).copy(alpha = 0.2f)) {
                                    Text(
                                        formatTimer(remMs),
                                        color = Color(0xFF58A6FF),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        isCurfew -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("🌙 BEDTIME CURFEW ACTIVE", color = Color(0xFFBC8CFF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("11:00 PM – 6:00 AM lockdown. Instant eviction armed.", color = Color(0xFF8B949E), fontSize = 11.sp)
                                }
                                Text("SLEEP", color = Color(0xFFBC8CFF), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                        }

                        else -> {
                            val sessionSec = (currentSessionMs / 1000L).coerceAtLeast(0L)
                            val maxSec = 30 * 60L
                            val progress = (sessionSec.toFloat() / maxSec.toFloat()).coerceIn(0f, 1f)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("CURRENT SCROLL SESSION", color = Color(0xFF3FB950), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("Resets only after 5 min off target apps", color = Color(0xFF8B949E), fontSize = 11.sp)
                                }
                                Text(
                                    "${sessionSec / 60}m / 30m",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = if (progress > 0.8f) Color(0xFFF85149) else Color(0xFF238636),
                                trackColor = Color(0xFF21262D)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Telemetry Grid
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    icon = "⏱️",
                    label = "Today's Scroll",
                    value = formatMinutes(totalTimeTodayMs / 60_000L),
                    valueColor = Color.White
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    icon = "🔨",
                    label = "Evictions",
                    value = "$totalEvictionsToday kicks",
                    valueColor = Color(0xFFF85149)
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    icon = "🌙",
                    label = "Night Blocks",
                    value = "$nightBlocksToday blocks",
                    valueColor = Color(0xFFBC8CFF)
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    icon = "⚠️",
                    label = "Sabotage Strikes",
                    value = "$sabotageStrikes strikes",
                    valueColor = if (sabotageStrikes > 0) Color(0xFFDA3633) else Color(0xFF8B949E)
                )
            }

            Spacer(Modifier.height(14.dp))

            // Self-Imposed Lockout Selector
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Self-Imposed Deep Focus", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                    Text("No cancel button. Once locked, it cannot be undone.", color = Color(0xFF8B949E), fontSize = 11.sp)
                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(15, 30, 60, 120).forEach { mins ->
                            val isSelected = selectedManualMinutes == mins
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) Color(0xFF1F6FEB) else Color(0xFF21262D),
                                border = BorderStroke(1.dp, if (isSelected) Color(0xFF58A6FF) else Color(0xFF30363D)),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedManualMinutes = mins }
                            ) {
                                Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        if (mins >= 60) "${mins / 60}h" else "${mins}m",
                                        color = if (isSelected) Color.White else Color(0xFF8B949E),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Button(
                        onClick = {
                            manualLockUntilMs = System.currentTimeMillis() + (selectedManualMinutes * 60_000L)
                            savePersistedState(bridge)
                            bridge.showToast("Locked out of target apps for $selectedManualMinutes minutes!")
                        },
                        enabled = !inPenalty && !inManualLock,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDA3633)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(36.dp)
                    ) {
                        Text("Engage Lockout (Irreversible)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Protected Apps Header
            Text(
                "Guarded Culprits (${targetPackages.size})",
                fontWeight = FontWeight.Bold,
                color = Color(0xFFC9D1D9),
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(appDisplayNames.keys.toList()) { pkg ->
                    val isChecked = targetPackages.contains(pkg)
                    val label = appDisplayNames[pkg] ?: pkg

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF161B22),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(pkg, color = Color(0xFF484F58), fontSize = 10.sp)
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF238636).copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, Color(0xFF238636).copy(alpha = 0.4f))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("🔒", fontSize = 10.sp)
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "LOCKED IN",
                                        color = Color(0xFF3FB950),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MetricCard(
        modifier: Modifier = Modifier,
        icon: String,
        label: String,
        value: String,
        valueColor: Color
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF161B22),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = modifier
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(icon, fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(label, color = Color(0xFF8B949E), fontSize = 10.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }

    private fun formatMinutes(mins: Long): String {
        val h = mins / 60
        val m = mins % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    private fun formatTimer(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSec = ms / 1000L
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    companion object {
        private const val CHANNEL_ID = "scroll_lock_alerts_channel"
    }
}