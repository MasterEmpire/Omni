package com.omni.plugin.telealert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.telephony.SmsMessage
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import com.omni.hub.api.HostBridge
import com.omni.hub.api.PluginEntry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class TrackedPackage(
    val id: String,
    val packageName: String,
    val serviceNumber: String,
    val effectiveTimeMs: Long,
    val expiryTimeMs: Long,
    val expiryDateStr: String,
    val rawSms: String,
    val interceptedAtMs: Long = System.currentTimeMillis(),
    var notifiedAlert: Boolean = false,
    var notifiedExpired: Boolean = false
)

class TeleAlertPlugin : PluginEntry() {

    private var activeContext: Context? = null
    private var activeBridge: HostBridge? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null

    private fun getActiveScope(): CoroutineScope {
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
        return scope
    }

    @Volatile private var isMonitoring = false
    @Volatile private var leadTimeMinutes = 15

    private val trackedPackages = mutableStateListOf<TrackedPackage>()
    private var uiUpdateTrigger by mutableStateOf(0L)

    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            if (action == "android.provider.Telephony.SMS_RECEIVED") {
                handleIncomingSms(intent)
            }
        }
    }

    override fun onCreateView(context: Context, bridge: HostBridge, baseDir: String): View {
        activeContext = context
        activeBridge = bridge
        initSentinel(context, bridge)

        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        background = Color(0xFF0D1117),
                        surface = Color(0xFF161B22),
                        primary = Color(0xFF3FB950)
                    )
                ) {
                    TeleAlertScreen(context, bridge)
                }
            }
        }
    }

    override fun onStart(context: Context, bridge: HostBridge, baseDir: String) {
        activeContext = context
        activeBridge = bridge
        initSentinel(context, bridge)
        bridge.log("TELE_ALERT", "🚀 Headless Daemon booted. Monitoring Ethio Telecom package expirations.")
        bridge.acquireWakeLock("TeleAlertSentinel")
    }

    override fun onStop(context: Context) {
        try {
            if (isMonitoring) {
                context.applicationContext.unregisterReceiver(smsReceiver)
                isMonitoring = false
            }
        } catch (_: Exception) {}
        monitorJob?.cancel()
        monitorJob = null
        activeBridge?.releaseWakeLock()
        activeBridge?.log("TELE_ALERT", "🛑 TeleAlert Sentinel stopped.")
    }

    private fun initSentinel(context: Context, bridge: HostBridge) {
        loadSettings(bridge)
        loadPackages(bridge)
        createNotificationChannel(context)
        startSmsListener(context, bridge)
        startMonitoringLoop(context, bridge)
    }

    private fun startSmsListener(context: Context, bridge: HostBridge) {
        if (isMonitoring) return
        val filter = IntentFilter("android.provider.Telephony.SMS_RECEIVED").apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.applicationContext.registerReceiver(smsReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.applicationContext.registerReceiver(smsReceiver, filter)
            }
            isMonitoring = true
            bridge.log("TELE_ALERT", "📡 SMS Broadcast receiver mounted. TeleAlert listening for incoming packages.")
        } catch (e: Exception) {
            bridge.log("TELE_ALERT_ERR", "Failed registering SMS receiver: ${e.message}")
        }
    }

    private fun handleIncomingSms(intent: Intent) {
        val bundle = intent.extras ?: return
        val pdus = bundle.get("pdus") as? Array<*> ?: return
        val format = bundle.getString("format")

        val fullText = StringBuilder()
        var sender = ""

        for (pdu in pdus) {
            val bytes = pdu as? ByteArray ?: continue
            val msg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                SmsMessage.createFromPdu(bytes, format)
            } else {
                @Suppress("DEPRECATION")
                SmsMessage.createFromPdu(bytes)
            }
            fullText.append(msg.messageBody)
            sender = msg.originatingAddress ?: sender
        }

        val rawBody = fullText.toString()
        activeBridge?.log("TELE_ALERT", "📨 Received SMS from '$sender' (${rawBody.length} chars)")

        // Heuristic check: Is this a telebirr / Ethio telecom notification?
        val isRelevant = rawBody.contains("telebirr", ignoreCase = true) ||
            rawBody.contains("Ethio telecom", ignoreCase = true) ||
            rawBody.contains("service offer", ignoreCase = true) ||
            rawBody.contains("expired on", ignoreCase = true) ||
            sender.contains("telebirr", ignoreCase = true) ||
            sender.contains("127") ||
            sender.contains("994")

        if (isRelevant) {
            processAndTrackSms(rawBody, "SMS Broadcast [$sender]")
        }
    }

    fun parsePackageFromText(rawText: String): TrackedPackage? {
        // 1. Parse Expiration Timestamp
        val expiryRegex = Regex(
            """(?:will be expired on|expired on|expires on|expiry(?: date)?:?)\s+([A-Za-z]{3}\s+\d{1,2},\s+\d{4}\s+\d{1,2}:\d{2}:\d{2}\s+[AP]M)""",
            RegexOption.IGNORE_CASE
        )
        val altExpiryRegex = Regex(
            """(?:will be expired on|expired on|expires on)\s+([A-Za-z]{3}\s+\d{1,2},\s+\d{4}\s+\d{1,2}:\d{2}\s+[AP]M)""",
            RegexOption.IGNORE_CASE
        )

        var parsedExpiryMs = 0L
        var matchedDateStr = ""

        val dateFormats = listOf(
            SimpleDateFormat("MMM d, yyyy h:mm:ss a", Locale.US),
            SimpleDateFormat("MMM d, yyyy hh:mm:ss a", Locale.US),
            SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        )

        val expMatch = expiryRegex.find(rawText) ?: altExpiryRegex.find(rawText)
        if (expMatch != null) {
            matchedDateStr = expMatch.groupValues[1].trim()
            for (fmt in dateFormats) {
                try {
                    val d = fmt.parse(matchedDateStr)
                    if (d != null) {
                        parsedExpiryMs = d.time
                        break
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. Relative Validity Fallback ("expired after 2 hours")
        if (parsedExpiryMs == 0L) {
            val relativeRegex = Regex("""(?:expired after|valid for)\s+(\d+)\s+(hour|hr|minute|min|day)s?""", RegexOption.IGNORE_CASE)
            val relMatch = relativeRegex.find(rawText)
            if (relMatch != null) {
                val amount = relMatch.groupValues[1].toLongOrNull() ?: 1L
                val unit = relMatch.groupValues[2].lowercase()
                val deltaMs = when {
                    unit.startsWith("min") -> amount * 60_000L
                    unit.startsWith("day") -> amount * 86_400_000L
                    else -> amount * 3_600_000L
                }
                parsedExpiryMs = System.currentTimeMillis() + deltaMs
                matchedDateStr = SimpleDateFormat("MMM d, yyyy h:mm:ss a", Locale.US).format(Date(parsedExpiryMs))
            }
        }

        if (parsedExpiryMs == 0L) return null

        // 3. Parse Effective Timestamp
        var effectiveMs = System.currentTimeMillis()
        val effRegex = Regex("""(?:effective as of|effective from)\s+([A-Za-z]{3}\s+\d{1,2},\s+\d{4}\s+\d{1,2}:\d{2}:\d{2}\s+[AP]M)""", RegexOption.IGNORE_CASE)
        val effMatch = effRegex.find(rawText)
        if (effMatch != null) {
            for (fmt in dateFormats) {
                try {
                    val d = fmt.parse(effMatch.groupValues[1].trim())
                    if (d != null) {
                        effectiveMs = d.time
                        break
                    }
                } catch (_: Exception) {}
            }
        }

        // 4. Parse Package Description
        val pkgRegex = Regex("""(?:service offer|offer)\s+(.*?)\s+(?:from telebirr|to be expired|is added)""", RegexOption.IGNORE_CASE)
        val pkgMatch = pkgRegex.find(rawText)
        val packageName = pkgMatch?.groupValues?.get(1)?.trim() ?: "Telebirr Internet Package"

        // 5. Parse Service Number
        val numRegex = Regex("""(?:service number|number)\s+([0-9+]{9,13})""", RegexOption.IGNORE_CASE)
        val numMatch = numRegex.find(rawText)
        val serviceNum = numMatch?.groupValues?.get(1)?.trim() ?: ""

        return TrackedPackage(
            id = "pkg_${UUID.randomUUID().toString().take(8)}",
            packageName = packageName,
            serviceNumber = serviceNum,
            effectiveTimeMs = effectiveMs,
            expiryTimeMs = parsedExpiryMs,
            expiryDateStr = matchedDateStr,
            rawSms = rawText
        )
    }

    fun processAndTrackSms(rawText: String, source: String): TrackedPackage? {
        val parsed = parsePackageFromText(rawText)
        if (parsed == null) {
            activeBridge?.log("TELE_ALERT_WARN", "⚠️ Could not parse valid package expiration from: '$rawText'")
            return null
        }

        val now = System.currentTimeMillis()
        if (parsed.expiryTimeMs <= now) {
            activeBridge?.log("TELE_ALERT_WARN", "⚠️ Package '${parsed.packageName}' has an expiration in the PAST (${parsed.expiryDateStr}). Ignored.")
            return null
        }

        // Prevent duplicate tracking (sync disk first in case daemon already recorded it)
        activeBridge?.let { loadPackages(it) }
        val duplicate = trackedPackages.find {
            it.packageName == parsed.packageName && it.expiryTimeMs == parsed.expiryTimeMs
        }
        if (duplicate != null) {
            activeBridge?.log("TELE_ALERT", "ℹ️ Package already actively tracked: '${parsed.packageName}'")
            return duplicate
        }

        trackedPackages.add(0, parsed)
        uiUpdateTrigger = System.currentTimeMillis()
        activeBridge?.let { savePackages(it) }

        val remainingMin = (parsed.expiryTimeMs - now) / 60_000L
        activeBridge?.log(
            "TELE_ALERT",
            "✅ [TRACKED] '$source' -> '${parsed.packageName}' | Expires: ${parsed.expiryDateStr} (${remainingMin}m remaining)"
        )
        activeBridge?.showToast("Tracked: ${parsed.packageName} (${remainingMin}m left)")
        return parsed
    }

    private fun syncNotificationFlagsFromDisk(bridge: HostBridge) {
        try {
            val bytes = bridge.readFile("tele_packages.json") ?: return
            val arr = JSONArray(String(bytes, Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val alert = obj.optBoolean("notified_alert", false)
                val expired = obj.optBoolean("notified_expired", false)
                trackedPackages.find { it.id == id }?.let { target ->
                    if (alert) target.notifiedAlert = true
                    if (expired) target.notifiedExpired = true
                }
            }
        } catch (_: Exception) {}
    }

    private fun startMonitoringLoop(context: Context, bridge: HostBridge) {
        if (monitorJob?.isActive == true) return

        monitorJob = getActiveScope().launch {
            while (isActive) {
                delay(10_000L) // Scan active packages every 10 seconds
                val now = System.currentTimeMillis()
                var listChanged = false

                // Sync notification states from disk to eliminate double-firing between Daemon & UI instances
                syncNotificationFlagsFromDisk(bridge)

                trackedPackages.forEach { pkg ->
                    val remainingMs = pkg.expiryTimeMs - now
                    val alertThresholdMs = leadTimeMinutes * 60_000L

                    // 1. Early Warning Trigger (X minutes before expiration)
                    if (remainingMs in 1..alertThresholdMs && !pkg.notifiedAlert) {
                        pkg.notifiedAlert = true
                        listChanged = true
                        val minLeft = (remainingMs / 60_000L).coerceAtLeast(1)
                        bridge.log("TELE_ALERT", "🚨 ALERT TRIGGERED: '${pkg.packageName}' expires in $minLeft minutes!")
                        sendAlertNotification(
                            context = context,
                            title = "⚠️ Package Expiring Soon!",
                            message = "Your ${pkg.packageName} expires in $minLeft min (${pkg.expiryDateStr}). Recharge now!",
                            notificationId = pkg.id.hashCode()
                        )
                    }

                    // 2. Exact Expiration Trigger
                    if (remainingMs <= 0 && !pkg.notifiedExpired) {
                        pkg.notifiedExpired = true
                        listChanged = true
                        bridge.log("TELE_ALERT", "🛑 EXPIRED: '${pkg.packageName}' has officially expired!")
                        sendAlertNotification(
                            context = context,
                            title = "🛑 Package Expired!",
                            message = "${pkg.packageName} expired at ${pkg.expiryDateStr}. Mobile data is now unshielded!",
                            notificationId = pkg.id.hashCode() + 1
                        )
                    }
                }

                if (listChanged) {
                    savePackages(bridge)
                    uiUpdateTrigger = System.currentTimeMillis()
                }
            }
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Package Expiry Sentinel Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority early warnings before internet packages expire"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 180, 100, 180)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun sendAlertNotification(context: Context, title: String, message: String, notificationId: Int) {
        try {
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(notificationId, notif)
        } catch (_: Exception) {}
    }

    private fun loadSettings(bridge: HostBridge) {
        try {
            val bytes = bridge.readFile("tele_settings.json") ?: return
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            leadTimeMinutes = json.optInt("lead_time_min", 15)
        } catch (_: Exception) {}
    }

    private fun saveSettings(bridge: HostBridge) {
        try {
            val json = JSONObject().apply {
                put("lead_time_min", leadTimeMinutes)
            }
            bridge.saveFile("tele_settings.json", json.toString(2).toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {}
    }

    private fun loadPackages(bridge: HostBridge) {
        try {
            val bytes = bridge.readFile("tele_packages.json") ?: return
            val arr = JSONArray(String(bytes, Charsets.UTF_8))
            trackedPackages.clear()
            val now = System.currentTimeMillis()

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val exp = obj.getLong("expiry_time_ms")
                // Keep active packages or packages expired in the last 2 hours for record
                if (exp > now - (2 * 3600_000L)) {
                    trackedPackages.add(
                        TrackedPackage(
                            id = obj.getString("id"),
                            packageName = obj.getString("package_name"),
                            serviceNumber = obj.optString("service_number", ""),
                            effectiveTimeMs = obj.optLong("effective_time_ms", now),
                            expiryTimeMs = exp,
                            expiryDateStr = obj.getString("expiry_date_str"),
                            rawSms = obj.optString("raw_sms", ""),
                            interceptedAtMs = obj.optLong("intercepted_at_ms", now),
                            notifiedAlert = obj.optBoolean("notified_alert", false),
                            notifiedExpired = obj.optBoolean("notified_expired", false)
                        )
                    )
                }
            }
        } catch (_: Exception) {}
    }

    private fun savePackages(bridge: HostBridge) {
        try {
            val arr = JSONArray()
            trackedPackages.forEach { p ->
                val obj = JSONObject().apply {
                    put("id", p.id)
                    put("package_name", p.packageName)
                    put("service_number", p.serviceNumber)
                    put("effective_time_ms", p.effectiveTimeMs)
                    put("expiry_time_ms", p.expiryTimeMs)
                    put("expiry_date_str", p.expiryDateStr)
                    put("raw_sms", p.rawSms)
                    put("intercepted_at_ms", p.interceptedAtMs)
                    put("notified_alert", p.notifiedAlert)
                    put("notified_expired", p.notifiedExpired)
                }
                arr.put(obj)
            }
            bridge.saveFile("tele_packages.json", arr.toString(2).toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {}
    }

    @Composable
    fun TeleAlertScreen(context: Context, bridge: HostBridge) {
        var leadTime by remember { mutableIntStateOf(leadTimeMinutes) }
        var leadText by remember { mutableStateOf(leadTimeMinutes.toString()) }
        var showSimulator by remember { mutableStateOf(false) }

        var sampleSmsText by remember {
            mutableStateOf(
                "Dear Customer \nAs per your request the new service offer Two Birr 480 MB Telegram package from telebirr to be expired after 2 hours is added to your service number 0933407551. The offer is effective as of Sep 28, 2026 5:37:58 PM and will be expired on Sep 28, 2026 7:37:58 PM. \nDownload and use telebirr SuperApp from http://onelink.to/fpgu4m and get 20 percent discount during package purchase. \nEthio telecom"
            )
        }

        // Live Monotonic Tick for UI countdowns
        var currentTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                currentTimeMs = System.currentTimeMillis()
                delay(1000L)
            }
        }

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
                            .background(Brush.linearGradient(listOf(Color(0xFF238636), Color(0xFF2EA043)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("📡", fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("TeleAlert Sentinel", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isMonitoring) Color(0xFF3FB950) else Color(0xFFDA3633))
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                if (isMonitoring) "SMS LISTENER ARMED" else "OFFLINE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isMonitoring) Color(0xFF3FB950) else Color(0xFF8B949E)
                            )
                        }
                    }
                }

                Row {
                    IconButton(onClick = {
                        bridge.requestPermissions(arrayOf(android.Manifest.permission.RECEIVE_SMS, android.Manifest.permission.READ_SMS)) { res ->
                            if (res[android.Manifest.permission.RECEIVE_SMS] == true) {
                                startSmsListener(context, bridge)
                                bridge.showToast("SMS permission granted!")
                            } else {
                                bridge.showToast("SMS permission denied.")
                            }
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Re-arm listener", tint = Color(0xFF58A6FF))
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
            }

            Spacer(Modifier.height(14.dp))

            // Lead Time Configuration Card (Slider + Editable Numeric Input Box)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Notifications, contentDescription = null, tint = Color(0xFF58A6FF), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Alert Lead Time", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        // Editable Exact Number Box
                        OutlinedTextField(
                            value = leadText,
                            onValueChange = { newVal ->
                                val filtered = newVal.filter { it.isDigit() }.take(3)
                                leadText = filtered
                                val num = filtered.toIntOrNull()
                                if (num != null && num in 1..180) {
                                    leadTime = num
                                    leadTimeMinutes = num
                                    saveSettings(bridge)
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(76.dp).height(48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF58A6FF),
                                unfocusedBorderColor = Color(0xFF30363D)
                            ),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        "Notify me $leadTime minutes before data package expires",
                        color = Color(0xFF8B949E),
                        fontSize = 11.sp
                    )

                    // Synchronized Slider (1 to 120 mins)
                    Slider(
                        value = leadTime.toFloat(),
                        onValueChange = { fVal ->
                            val intVal = fVal.toInt().coerceIn(1, 120)
                            leadTime = intVal
                            leadTimeMinutes = intVal
                            leadText = intVal.toString()
                        },
                        onValueChangeFinished = {
                            saveSettings(bridge)
                            bridge.showToast("Alert lead time set to $leadTime minutes")
                        },
                        valueRange = 1f..120f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF58A6FF),
                            activeTrackColor = Color(0xFF1F6FEB),
                            inactiveTrackColor = Color(0xFF21262D)
                        )
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Simulator Toggle Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showSimulator = !showSimulator }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (showSimulator) "▼ Hide SMS Sandbox" else "▶ Open SMS Sandbox & Simulator",
                    color = Color(0xFF58A6FF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text("Instant Test", color = Color(0xFF8B949E), fontSize = 10.sp)
            }

            AnimatedVisibility(visible = showSimulator) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    border = BorderStroke(1.dp, Color(0xFF58A6FF).copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Paste or edit Ethio Telecom / telebirr SMS to test parser:", color = Color(0xFF8B949E), fontSize = 10.sp)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = sampleSmsText,
                            onValueChange = { sampleSmsText = it },
                            modifier = Modifier.fillMaxWidth().height(110.dp),
                            shape = RoundedCornerShape(8.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF388BFD),
                                unfocusedBorderColor = Color(0xFF30363D)
                            )
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val result = processAndTrackSms(sampleSmsText, "Sandbox Simulator")
                                if (result != null) {
                                    bridge.showToast("Parsed: ${result.packageName}")
                                } else {
                                    bridge.showToast("Could not parse expiry date or date is in past.")
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().height(34.dp)
                        ) {
                            Text("Simulate & Track Package", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Active Tracked Packages Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tracked Packages (${trackedPackages.size})", color = Color(0xFFC9D1D9), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (trackedPackages.isNotEmpty()) {
                    TextButton(onClick = {
                        trackedPackages.clear()
                        savePackages(bridge)
                    }) {
                        Text("Clear All", color = Color(0xFFF85149), fontSize = 11.sp)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            if (trackedPackages.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📬", fontSize = 32.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("No active packages tracked", color = Color(0xFF8B949E), fontSize = 13.sp)
                        Text("Incoming Ethio Telecom / telebirr texts appear here.", color = Color(0xFF484F58), fontSize = 11.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(trackedPackages, key = { it.id }) { pkg ->
                        val remainingMs = pkg.expiryTimeMs - currentTimeMs
                        val totalDuration = (pkg.expiryTimeMs - pkg.effectiveTimeMs).coerceAtLeast(1L)
                        val elapsed = (currentTimeMs - pkg.effectiveTimeMs).coerceAtLeast(0L)
                        val progress = (elapsed.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)

                        val isExpired = remainingMs <= 0
                        val isAlertZone = remainingMs in 1..(leadTime * 60_000L)

                        val statusColor = when {
                            isExpired -> Color(0xFFDA3633)
                            isAlertZone -> Color(0xFFD29922)
                            else -> Color(0xFF238636)
                        }

                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                            border = BorderStroke(1.dp, statusColor.copy(alpha = if (isAlertZone) 0.8f else 0.2f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            pkg.packageName,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        if (pkg.serviceNumber.isNotEmpty()) {
                                            Text("Account: ${pkg.serviceNumber}", color = Color(0xFF8B949E), fontSize = 10.sp)
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = statusColor.copy(alpha = 0.15f),
                                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
                                    ) {
                                        Text(
                                            when {
                                                isExpired -> "EXPIRED"
                                                isAlertZone -> "ALERT: RECHARGE"
                                                else -> "ACTIVE"
                                            },
                                            color = statusColor,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            trackedPackages.remove(pkg)
                                            savePackages(bridge)
                                        },
                                        modifier = Modifier.size(24.dp).padding(start = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color(0xFF8B949E), modifier = Modifier.size(14.dp))
                                    }
                                }

                                Spacer(Modifier.height(10.dp))

                                // Progress Indicator
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    color = statusColor,
                                    trackColor = Color(0xFF21262D)
                                )

                                Spacer(Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("Expires at:", color = Color(0xFF8B949E), fontSize = 10.sp)
                                        Text(pkg.expiryDateStr, color = Color(0xFFC9D1D9), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("Time Remaining:", color = Color(0xFF8B949E), fontSize = 10.sp)
                                        Text(
                                            formatCountdown(remainingMs),
                                            color = statusColor,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun formatCountdown(ms: Long): String {
        if (ms <= 0) return "00:00:00"
        val totalSec = ms / 1000L
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    companion object {
        private const val CHANNEL_ID = "tele_alert_expiry_channel_v2"
    }
}