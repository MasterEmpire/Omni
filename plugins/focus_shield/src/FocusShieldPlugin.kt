package com.omni.plugin.focus

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.View
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
import androidx.compose.material.icons.filled.Close
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
import com.omni.hub.api.HostBridge
import com.omni.hub.api.PluginEntry
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
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
                        bridge.log("CLOCK_SYNC", "✅ Network clock synced: offset=${networkOffsetMs}ms")
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

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isDaemonRunning = false

    override fun onStart(context: Context, bridge: HostBridge, baseDir: String) {
        bridge.log("FOCUS_SHIELD", "🚀 Focus Shield daemon starting up...")
        isDaemonRunning = true
        NetworkClockGuard.syncNetworkTime(bridge)

        scope.launch {
            var lastClockSyncTime = 0L

            while (isDaemonRunning) {
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastClockSyncTime > 15 * 60 * 1000L) {
                        lastClockSyncTime = now
                        NetworkClockGuard.syncNetworkTime(bridge)
                    }

                    val accurateTime = NetworkClockGuard.getAccurateTimeMs()
                    val state = readState(bridge)
                    val isPanicActive = state.manualLockUntilMs > accurateTime

                    val cal = Calendar.getInstance().apply { timeInMillis = accurateTime }
                    val hour = cal.get(Calendar.HOUR_OF_DAY)
                    val isNightSchedule = hour >= 22 || hour < 6

                    val isEnforcing = isPanicActive || isNightSchedule

                    if (isEnforcing) {
                        checkAndDeflectForeground(context, bridge, state.customLockedApps)
                        delay(180)
                    } else {
                        delay(1500)
                    }
                } catch (t: Throwable) {
                    bridge.log("FOCUS_SHIELD_ERR", "Watchdog loop exception: ${t.message}")
                    delay(1000)
                }
            }
        }
    }

    override fun onStop(context: Context) {
        isDaemonRunning = false
        scope.cancel()
    }

    private fun checkAndDeflectForeground(
        context: Context,
        bridge: HostBridge,
        customLockedApps: List<LockedAppItem>
    ) {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 4000, now) ?: return
        val event = UsageEvents.Event()

        var topPackage: String? = null
        var topTimestamp = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                if (event.timeStamp >= topTimestamp) {
                    topTimestamp = event.timeStamp
                    topPackage = event.packageName
                }
            }
        }

        val fgPkg = topPackage ?: return
        if (fgPkg == context.packageName || fgPkg == "com.omni.hub") return

        val browsers = getInstalledBrowsers(context)
        val customPkgs = customLockedApps.map { it.packageName }.toSet()

        val isTargetBlocked = browsers.contains(fgPkg) || customPkgs.contains(fgPkg) || fgPkg == "com.android.settings"

        if (isTargetBlocked) {
            val appLabel = try {
                val appInfo = context.packageManager.getApplicationInfo(fgPkg, 0)
                context.packageManager.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                fgPkg
            }

            bridge.log("FOCUS_SHIELD", "⛔ Deflecting blocked app [$appLabel] ($fgPkg) to Home!")

            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(homeIntent)
            bridge.vibrate(100L)
            bridge.showToast("🔒 Focus Shield: $appLabel is locked.")
        }
    }

    private fun getInstalledBrowsers(context: Context): Set<String> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val list = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        return list.map { it.activityInfo.packageName }
            .filter { it != context.packageName && it != "com.omni.hub" }
            .toSet()
    }

    private data class ShieldState(
        val manualLockUntilMs: Long,
        val customLockedApps: List<LockedAppItem>
    )

    private fun readState(bridge: HostBridge): ShieldState {
        return try {
            val bytes = bridge.readFile("config/focus_shield_state.json")
            if (bytes != null && bytes.isNotEmpty()) {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                val lockUntil = json.optLong("manual_lock_until_ms", 0L)
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
                ShieldState(lockUntil, list)
            } else {
                ShieldState(0L, emptyList())
            }
        } catch (_: Exception) {
            ShieldState(0L, emptyList())
        }
    }

    private fun writeState(bridge: HostBridge, state: ShieldState) {
        try {
            val json = JSONObject().apply {
                put("manual_lock_until_ms", state.manualLockUntilMs)
                val arr = JSONArray()
                state.customLockedApps.forEach { app ->
                    arr.put(JSONObject().apply {
                        put("pkg", app.packageName)
                        put("name", app.appName)
                        put("time", app.addedAt)
                    })
                }
                put("custom_apps", arr)
            }
            bridge.saveFile("config/focus_shield_state.json", json.toString().toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {}
    }

    override fun onCreateView(context: Context, bridge: HostBridge, baseDir: String): View {
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

    @Composable
    fun FocusShieldScreen(
        context: Context,
        bridge: HostBridge,
        plugin: FocusShieldPlugin
    ) {
        val coroutineScope = rememberCoroutineScope()
        var shieldState by remember { mutableStateOf(plugin.readState(bridge)) }
        var showAddAppDialog by remember { mutableStateOf(false) }
        var appToAddPending by remember { mutableStateOf<InstalledAppInfo?>(null) }
        var currentTimeMs by remember { mutableStateOf(NetworkClockGuard.getAccurateTimeMs()) }

        // Live Ticking Clock (every second)
        LaunchedEffect(Unit) {
            NetworkClockGuard.syncNetworkTime(bridge)
            while (true) {
                currentTimeMs = NetworkClockGuard.getAccurateTimeMs()
                delay(1000)
            }
        }

        val isPanicActive = shieldState.manualLockUntilMs > currentTimeMs
        val remainingMs = (shieldState.manualLockUntilMs - currentTimeMs).coerceAtLeast(0L)
        val remMin = remainingMs / 60_000L
        val remSec = (remainingMs % 60_000L) / 1000L

        val cal = Calendar.getInstance().apply { timeInMillis = currentTimeMs }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val isNightActive = hour >= 22 || hour < 6

        // Check Permissions
        val hasUsageStats = remember(currentTimeMs) {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm?.queryEvents(now - 1000, now)
            events != null
        }
        val hasOverlay = remember(currentTimeMs) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D1117))
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            // Header Bar
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
                        Text("UsageStats Bouncer", color = Color(0xFF8B949E), fontSize = 11.sp)
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
                                .background(if (NetworkClockGuard.networkOffsetMs != 0L) Color(0xFF3FB950) else Color(0xFFD29922))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (NetworkClockGuard.networkOffsetMs != 0L) "Net Clock Verified" else "Local Clock",
                            color = Color(0xFFC9D1D9),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Permission Warning Cards if missing
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
                            Text("Grant Usage Access so Focus Shield can deflect blocked apps reliably.", color = Color(0xFFE8EAED), fontSize = 11.sp)
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

            // Big Tactile Panic Card
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
                                if (isPanicActive) "Irreversible: No early cancel" else "Locks external browsers & blacklist across day or night",
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
                                val newState = shieldState.copy(manualLockUntilMs = newUntil)
                                shieldState = newState
                                plugin.writeState(bridge, newState)
                                bridge.vibrate(200L)
                                bridge.showToast("🔒 1-Hour Lockout Engaged! No bypass allowed.")
                            } else {
                                bridge.showToast("⛔ Lockout is irreversible! Wait for timer to finish.")
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

            // Night Guard Schedule Card (10 PM to 6 AM)
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

            // Custom Trapped Apps Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Permanent Blacklist (${shieldState.customLockedApps.size})", color = Color(0xFFC9D1D9), fontWeight = FontWeight.Bold, fontSize = 14.sp)
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

            if (shieldState.customLockedApps.isEmpty()) {
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
                    items(shieldState.customLockedApps, key = { it.packageName }) { app ->
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

        // Searchable Installed Apps Picker Dialog
        if (showAddAppDialog) {
            val installedApps = remember {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                val resolveList = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                resolveList.map {
                    InstalledAppInfo(
                        packageName = it.activityInfo.packageName,
                        appName = it.loadLabel(pm).toString()
                    )
                }.filter { it.packageName != context.packageName && it.packageName != "com.omni.hub" }
                 .distinctBy { it.packageName }
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
                            placeholder = { Text("Search installed apps...", color = Color(0xFF8B949E), fontSize = 12.sp) },
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
                                val isAlreadyAdded = shieldState.customLockedApps.any { it.packageName == appInfo.packageName }

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

        // Irreversible Confirmation Prompt for Adding App
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
                            val updatedApps = shieldState.customLockedApps + newItem
                            val newState = shieldState.copy(customLockedApps = updatedApps)
                            shieldState = newState
                            plugin.writeState(bridge, newState)
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
}