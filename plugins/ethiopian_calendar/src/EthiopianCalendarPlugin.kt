package com.omni.plugin.calendar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.view.View
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import com.omni.hub.api.HostBridge
import com.omni.hub.api.PluginEntry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

data class EthiopicDate(
    val year: Int,
    val month: Int,
    val day: Int
)

data class CalendarReminder(
    val id: String,
    val title: String,
    val note: String = "",
    val ethYear: Int,
    val ethMonth: Int,
    val ethDay: Int,
    val hour: Int = 9, // Gregorian 24h hour for background matching
    val minute: Int = 0,
    val isNotified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

object EthiopianDateMath {
    val MONTH_NAMES = listOf(
        "መስከረም", "ጥቅምት", "ኅዳር", "ታኅሣሥ", "ጥር", "የካቲት",
        "መጋቢት", "ሚያዝያ", "ግንቦት", "ሰኔ", "ሐምሌ", "ነሐሴ", "ጳጉሜ"
    )

    val WEEKDAY_NAMES = listOf("ሰኞ", "ማክሰኞ", "ረቡዕ", "ሐሙስ", "ዓርብ", "ቅዳሜ", "እሁድ")

    fun isLeapYear(ethYear: Int): Boolean = (ethYear % 4 == 3)

    fun daysInMonth(ethYear: Int, ethMonth: Int): Int {
        return if (ethMonth in 1..12) 30 else if (isLeapYear(ethYear)) 6 else 5
    }

    fun gregorianToJdn(year: Int, month: Int, day: Int): Int {
        val a = (14 - month) / 12
        val y = year + 4800 - a
        val m = month + 12 * a - 3
        return day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045
    }

    fun jdnToGregorian(jdn: Int): Triple<Int, Int, Int> {
        val l = jdn + 68569
        val n = (4 * l) / 146097
        val l2 = l - (146097 * n + 3) / 4
        val i = (4000 * (l2 + 1)) / 1461001
        val l3 = l2 - (1461 * i) / 4 + 31
        val j = (80 * l3) / 2447
        val day = l3 - (2447 * j) / 80
        val l4 = j / 11
        val month = j + 2 - 12 * l4
        val year = 100 * (n - 49) + i + l4
        return Triple(year, month, day)
    }

    fun jdnToEthiopian(jdn: Int): EthiopicDate {
        val r = (jdn - 1723856) % 1461
        val n = (r % 365) + 365 * (r / 1460)
        val year = 4 * ((jdn - 1723856) / 1461) + (r / 365) - (r / 1460)
        val month = (n / 30) + 1
        val day = (n % 30) + 1
        return EthiopicDate(year, month, day)
    }

    fun ethiopianToJdn(ethYear: Int, ethMonth: Int, ethDay: Int): Int {
        return (1723856 + 365) + 365 * (ethYear - 1) + (ethYear / 4) + (30 * ethMonth) + ethDay - 31
    }

    fun getTodayEthiopian(): EthiopicDate {
        val cal = Calendar.getInstance()
        val jdn = gregorianToJdn(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        return jdnToEthiopian(jdn)
    }

    fun getFirstDayOfWeek(ethYear: Int, ethMonth: Int): Int {
        val jdn = ethiopianToJdn(ethYear, ethMonth, 1)
        return jdn % 7
    }

    fun ethiopianTimeToGregorian(ethHour: Int, minute: Int, period: String): Pair<Int, Int> {
        val h = ethHour.coerceIn(1, 12)
        val gregHour = when (period) {
            "ጠዋት" -> (h % 12) + 6             // 12 -> 6 AM, 1 -> 7 AM, 5 -> 11 AM
            "ከሰዓት" -> (h % 12) + 6            // 6 -> 12 PM, 7 -> 1 PM, 11 -> 5 PM
            "ማታ" -> (h % 12) + 18            // 12 -> 6 PM, 1 -> 7 PM, 5 -> 11 PM
            "ሌሊት" -> ((h % 12) + 18) % 24     // 6 -> 12 AM, 7 -> 1 AM, 11 -> 5 AM
            else -> (h % 12) + 6
        }
        return Pair(gregHour, minute)
    }

    fun gregorianToEthiopianTime(gregHour: Int, minute: Int): Triple<Int, Int, String> {
        val ethHour = when {
            gregHour == 6 -> 12
            gregHour == 18 -> 12
            gregHour in 7..17 -> gregHour - 6
            gregHour > 18 -> gregHour - 18
            else -> gregHour + 6
        }
        val period = when (gregHour) {
            in 6..11 -> "ጠዋት"
            in 12..17 -> "ከሰዓት"
            in 18..23 -> "ማታ"
            else -> "ሌሊት"
        }
        return Triple(ethHour, minute, period)
    }
}

class EthiopianCalendarPlugin : PluginEntry() {

    private val pluginScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var daemonJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "omni_ethiopian_calendar_reminders"
        private val remindersLock = Any()
        val allReminders = mutableStateListOf<CalendarReminder>()
        @Volatile var isDaemonRunning = false

        fun loadReminders(bridge: HostBridge) {
            try {
                val bytes = bridge.readFile("calendar_reminders.json") ?: return
                val arr = JSONArray(String(bytes, Charsets.UTF_8))
                synchronized(remindersLock) {
                    allReminders.clear()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        allReminders.add(
                            CalendarReminder(
                                id = obj.getString("id"),
                                title = obj.getString("title"),
                                note = obj.optString("note", ""),
                                ethYear = obj.getInt("ethYear"),
                                ethMonth = obj.getInt("ethMonth"),
                                ethDay = obj.getInt("ethDay"),
                                hour = obj.optInt("hour", 9),
                                minute = obj.optInt("minute", 0),
                                isNotified = obj.optBoolean("isNotified", false),
                                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        fun saveReminders(bridge: HostBridge) {
            try {
                val arr = JSONArray()
                synchronized(remindersLock) {
                    allReminders.forEach { r ->
                        val obj = JSONObject().apply {
                            put("id", r.id)
                            put("title", r.title)
                            put("note", r.note)
                            put("ethYear", r.ethYear)
                            put("ethMonth", r.ethMonth)
                            put("ethDay", r.ethDay)
                            put("hour", r.hour)
                            put("minute", r.minute)
                            put("isNotified", r.isNotified)
                            put("createdAt", r.createdAt)
                        }
                        arr.put(obj)
                    }
                }
                bridge.saveFile("calendar_reminders.json", arr.toString(2).toByteArray(Charsets.UTF_8))
            } catch (_: Exception) {}
        }
    }

    override fun onCreateView(context: Context, bridge: HostBridge, baseDir: String): View {
        createNotificationChannel(context)
        loadReminders(bridge)
        startBackgroundSentinel(context, bridge)

        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        background = Color(0xFF0D1117),
                        surface = Color(0xFF161B22),
                        primary = Color(0xFFE5A93C)
                    )
                ) {
                    EthiopianCalendarApp(context, bridge)
                }
            }
        }
    }

    override fun onStart(context: Context, bridge: HostBridge, baseDir: String) {
        createNotificationChannel(context)
        loadReminders(bridge)
        startBackgroundSentinel(context, bridge)
        bridge.log("CALENDAR", "🇪🇹 Ethiopian Calendar Sentinel Armed in Background.")
    }

    override fun onStop(context: Context) {
        daemonJob?.cancel()
        isDaemonRunning = false
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ethiopian Calendar Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Due date event alerts and notifications"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 450, 150, 450, 150, 900)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startBackgroundSentinel(context: Context, bridge: HostBridge) {
        if (daemonJob?.isActive == true) return
        isDaemonRunning = true

        daemonJob = pluginScope.launch {
            while (isActive) {
                try {
                    delay(30_000L)
                    val todayEth = EthiopianDateMath.getTodayEthiopian()
                    val cal = Calendar.getInstance()
                    val curHour = cal.get(Calendar.HOUR_OF_DAY)
                    val curMin = cal.get(Calendar.MINUTE)

                    var changed = false
                    val dueReminders = mutableListOf<CalendarReminder>()

                    synchronized(remindersLock) {
                        for (i in 0 until allReminders.size) {
                            val r = allReminders[i]
                            if (!r.isNotified) {
                                val isPastDay = r.ethYear < todayEth.year ||
                                    (r.ethYear == todayEth.year && (r.ethMonth < todayEth.month || (r.ethMonth == todayEth.month && r.ethDay < todayEth.day)))
                                val isTodayDue = r.ethYear == todayEth.year && r.ethMonth == todayEth.month && r.ethDay == todayEth.day &&
                                    (curHour > r.hour || (curHour == r.hour && curMin >= r.minute))

                                if (isPastDay || isTodayDue) {
                                    dueReminders.add(r)
                                    allReminders[i] = r.copy(isNotified = true)
                                    changed = true
                                }
                            }
                        }
                    }

                    if (dueReminders.isNotEmpty()) {
                        dueReminders.forEach { r ->
                            dispatchNotification(context, bridge, r)
                        }
                    }

                    if (changed) {
                        saveReminders(bridge)
                    }

                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    bridge.log("CALENDAR_ERR", "Sentinel tick glitch: ${e.message}")
                }
            }
        }
    }

    private fun dispatchNotification(context: Context, bridge: HostBridge, reminder: CalendarReminder) {
        try {
            bridge.vibrate(700L)
            val monthName = EthiopianDateMath.MONTH_NAMES.getOrElse(reminder.ethMonth - 1) { "ወር" }
            val (eHour, eMin, period) = EthiopianDateMath.gregorianToEthiopianTime(reminder.hour, reminder.minute)
            val timeLabel = String.format(Locale.US, "%s %d:%02d", period, eHour, eMin)
            val dateLabel = "$monthName ${reminder.ethDay}፣ ${reminder.ethYear} ዓ.ም ($timeLabel)"

            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("⏰ ${reminder.title}")
                .setContentText("${reminder.note.ifEmpty { "ማስታወሻ" }} • $dateLabel")
                .setStyle(NotificationCompat.BigTextStyle().bigText("${reminder.note.ifEmpty { "ማስታወሻ" }}\n$dateLabel"))
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVibrate(longArrayOf(0, 450, 150, 450, 150, 900))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(reminder.id.hashCode(), notif)
            bridge.log("CALENDAR", "🔔 Heads-Up Alert dispatched for: ${reminder.title} ($dateLabel)")
        } catch (_: Exception) {}
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun EthiopianCalendarApp(context: Context, bridge: HostBridge) {
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val coroutineScope = rememberCoroutineScope()
        var currentScreen by remember { mutableStateOf("calendar") }

        val todayEth = remember { EthiopianDateMath.getTodayEthiopian() }
        var viewingYear by remember { mutableIntStateOf(todayEth.year) }
        var viewingMonth by remember { mutableIntStateOf(todayEth.month) }
        var selectedDay by remember { mutableIntStateOf(todayEth.day) }

        var reminderBeingEdited by remember { mutableStateOf<CalendarReminder?>(null) }
        var showReminderDialog by remember { mutableStateOf(false) }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = Color(0xFF161B22),
                    modifier = Modifier.width(300.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🇪🇹", fontSize = 26.sp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("የቀን መቁጠሪያ", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Ethiopian Calendar", fontSize = 11.sp, color = Color(0xFFE5A93C))
                            }
                        }

                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                        Spacer(Modifier.height(16.dp))

                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.DateRange, contentDescription = null, tint = Color(0xFFE5A93C)) },
                            label = { Text("Calendar View", fontWeight = FontWeight.SemiBold) },
                            selected = currentScreen == "calendar",
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = Color(0xFFE5A93C).copy(alpha = 0.15f),
                                selectedTextColor = Color(0xFFE5A93C),
                                unselectedTextColor = Color(0xFFC9D1D9)
                            ),
                            onClick = {
                                currentScreen = "calendar"
                                coroutineScope.launch { drawerState.close() }
                            }
                        )

                        Spacer(Modifier.height(8.dp))

                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.Notifications, contentDescription = null, tint = Color(0xFF58A6FF)) },
                            label = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("All Reminders", fontWeight = FontWeight.SemiBold)
                                    Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF21262D)) {
                                        Text(
                                            "${allReminders.size}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            },
                            selected = currentScreen == "reminders",
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = Color(0xFF58A6FF).copy(alpha = 0.15f),
                                selectedTextColor = Color(0xFF58A6FF),
                                unselectedTextColor = Color(0xFFC9D1D9)
                            ),
                            onClick = {
                                currentScreen = "reminders"
                                coroutineScope.launch { drawerState.close() }
                            }
                        )

                        Spacer(Modifier.height(8.dp))

                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF3FB950)) },
                            label = { Text("Date Converter", fontWeight = FontWeight.SemiBold) },
                            selected = currentScreen == "converter",
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = Color(0xFF3FB950).copy(alpha = 0.15f),
                                selectedTextColor = Color(0xFF3FB950),
                                unselectedTextColor = Color(0xFFC9D1D9)
                            ),
                            onClick = {
                                currentScreen = "converter"
                                coroutineScope.launch { drawerState.close() }
                            }
                        )
                    }
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val currentMonthName = EthiopianDateMath.MONTH_NAMES[viewingMonth - 1]
                                Text(
                                    "$currentMonthName $viewingYear",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 19.sp,
                                    color = Color.White
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color(0xFFE5A93C))
                            }
                        },
                        actions = {
                            if (currentScreen == "calendar") {
                                // Jump back to today button
                                TextButton(
                                    onClick = {
                                        viewingYear = todayEth.year
                                        viewingMonth = todayEth.month
                                        selectedDay = todayEth.day
                                    }
                                ) {
                                    Text("Today", color = Color(0xFFE5A93C), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }

                                IconButton(
                                    onClick = {
                                        if (viewingMonth > 1) {
                                            viewingMonth--
                                        } else {
                                            viewingMonth = 13
                                            viewingYear--
                                        }
                                        selectedDay = 1
                                    }
                                ) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Prev Month", tint = Color(0xFF8B949E))
                                }

                                IconButton(
                                    onClick = {
                                        if (viewingMonth < 13) {
                                            viewingMonth++
                                        } else {
                                            viewingMonth = 1
                                            viewingYear++
                                        }
                                        selectedDay = 1
                                    }
                                ) {
                                    Icon(Icons.Default.ArrowForward, contentDescription = "Next Month", tint = Color(0xFF8B949E))
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF161B22))
                    )
                },
                floatingActionButton = {
                    if (currentScreen == "calendar") {
                        FloatingActionButton(
                            onClick = {
                                reminderBeingEdited = null
                                showReminderDialog = true
                            },
                            containerColor = Color(0xFFE5A93C),
                            contentColor = Color.Black,
                            shape = CircleShape
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add Reminder")
                        }
                    }
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(Color(0xFF0D1117))
                ) {
                    when (currentScreen) {
                        "calendar" -> CalendarView(
                            year = viewingYear,
                            month = viewingMonth,
                            selectedDay = selectedDay,
                            todayEth = todayEth,
                            onSelectDay = { selectedDay = it },
                            onEditReminder = { rem ->
                                reminderBeingEdited = rem
                                showReminderDialog = true
                            },
                            onDeleteReminder = { id ->
                                synchronized(remindersLock) {
                                    allReminders.removeAll { it.id == id }
                                }
                                saveReminders(bridge)
                            }
                        )
                        "reminders" -> RemindersListView(
                            onEditReminder = { rem ->
                                reminderBeingEdited = rem
                                showReminderDialog = true
                            },
                            onDeleteReminder = { id ->
                                synchronized(remindersLock) {
                                    allReminders.removeAll { it.id == id }
                                }
                                saveReminders(bridge)
                            }
                        )
                        "converter" -> DateConverterView(bridge)
                    }
                }
            }
        }

        if (showReminderDialog) {
            val editing = reminderBeingEdited
            ReminderEditorDialog(
                existingReminder = editing,
                defaultEthYear = editing?.ethYear ?: viewingYear,
                defaultEthMonth = editing?.ethMonth ?: viewingMonth,
                defaultEthDay = editing?.ethDay ?: selectedDay,
                onDismiss = {
                    showReminderDialog = false
                    reminderBeingEdited = null
                },
                onSave = { id, title, note, y, m, d, gregHour, min ->
                    synchronized(remindersLock) {
                        if (id != null) {
                            val idx = allReminders.indexOfFirst { it.id == id }
                            if (idx >= 0) {
                                allReminders[idx] = CalendarReminder(
                                    id = id,
                                    title = title,
                                    note = note,
                                    ethYear = y,
                                    ethMonth = m,
                                    ethDay = d,
                                    hour = gregHour,
                                    minute = min,
                                    isNotified = false
                                )
                            }
                        } else {
                            allReminders.add(
                                0,
                                CalendarReminder(
                                    id = "rem_${System.currentTimeMillis()}",
                                    title = title,
                                    note = note,
                                    ethYear = y,
                                    ethMonth = m,
                                    ethDay = d,
                                    hour = gregHour,
                                    minute = min,
                                    isNotified = false
                                )
                            )
                        }
                    }
                    saveReminders(bridge)
                    showReminderDialog = false
                    reminderBeingEdited = null
                    bridge.showToast(if (id != null) "Reminder Updated!" else "Reminder Saved!")
                }
            )
        }
    }

    @Composable
    fun CalendarView(
        year: Int,
        month: Int,
        selectedDay: Int,
        todayEth: EthiopicDate,
        onSelectDay: (Int) -> Unit,
        onEditReminder: (CalendarReminder) -> Unit,
        onDeleteReminder: (String) -> Unit
    ) {
        val totalDays = remember(year, month) { EthiopianDateMath.daysInMonth(year, month) }
        val startOffset = remember(year, month) { EthiopianDateMath.getFirstDayOfWeek(year, month) }

        val selectedJdn = remember(year, month, selectedDay) {
            EthiopianDateMath.ethiopianToJdn(year, month, selectedDay)
        }
        val selectedGregorian = remember(selectedJdn) {
            EthiopianDateMath.jdnToGregorian(selectedJdn)
        }

        val dayReminders = allReminders.filter {
            it.ethYear == year && it.ethMonth == month && it.ethDay == selectedDay
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Weekday Headers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                EthiopianDateMath.WEEKDAY_NAMES.forEach { dayName ->
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = dayName,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF8B949E)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Calendar Days Grid
            val gridItemsCount = startOffset + totalDays
            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(gridItemsCount) { index ->
                    if (index < startOffset) {
                        Box(modifier = Modifier.aspectRatio(1f))
                    } else {
                        val dayNum = index - startOffset + 1
                        val isToday = (year == todayEth.year && month == todayEth.month && dayNum == todayEth.day)
                        val isSelected = (dayNum == selectedDay)
                        val hasReminders = allReminders.any {
                            it.ethYear == year && it.ethMonth == month && it.ethDay == dayNum
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = when {
                                isSelected -> Color(0xFFE5A93C).copy(alpha = 0.22f)
                                isToday -> Color(0xFF161B22)
                                else -> Color(0xFF161B22)
                            },
                            border = BorderStroke(
                                1.dp,
                                when {
                                    isSelected -> Color(0xFFE5A93C)
                                    isToday -> Color(0xFF3FB950).copy(alpha = 0.8f)
                                    else -> Color.White.copy(alpha = 0.05f)
                                }
                            ),
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelectDay(dayNum) }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "$dayNum",
                                        fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        color = when {
                                            isSelected -> Color(0xFFE5A93C)
                                            isToday -> Color(0xFF3FB950)
                                            else -> Color.White
                                        }
                                    )
                                    if (hasReminders) {
                                        Spacer(Modifier.height(2.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFFE5A93C))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Selected Day Card Banner
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        val mName = EthiopianDateMath.MONTH_NAMES[month - 1]
                        Text(
                            text = "$mName $selectedDay፣ $year ዓ.ም",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color(0xFFE5A93C)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "እ.ኤ.አ ${selectedGregorian.second}/${selectedGregorian.third}/${selectedGregorian.first}",
                            fontSize = 12.sp,
                            color = Color(0xFF8B949E),
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF21262D)
                    ) {
                        Text(
                            text = "${dayReminders.size} Reminders",
                            fontSize = 11.sp,
                            color = Color(0xFFC9D1D9),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Reminders list for the selected day
            if (dayReminders.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No reminders for this day",
                        color = Color(0xFF484F58),
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(dayReminders, key = { it.id }) { rem ->
                        ReminderCard(
                            rem = rem,
                            onEdit = { onEditReminder(rem) },
                            onDelete = { onDeleteReminder(rem.id) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun ReminderCard(
        rem: CalendarReminder,
        onEdit: () -> Unit,
        onDelete: () -> Unit
    ) {
        val (eHour, eMin, period) = remember(rem.hour, rem.minute) {
            EthiopianDateMath.gregorianToEthiopianTime(rem.hour, rem.minute)
        }
        val timeLabel = String.format(Locale.US, "%s %d:%02d", period, eHour, eMin)

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF161B22),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rem.title,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    if (rem.note.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = rem.note,
                            color = Color(0xFF8B949E),
                            fontSize = 12.sp,
                            maxLines = 2
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Time: $timeLabel",
                        color = Color(0xFFE5A93C),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onEdit) {
                        Text("Edit", color = Color(0xFF58A6FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = Color(0xFFF85149).copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun RemindersListView(
        onEditReminder: (CalendarReminder) -> Unit,
        onDeleteReminder: (String) -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                "All Scheduled Reminders (${allReminders.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color.White,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (allReminders.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔔", fontSize = 28.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("No reminders registered", color = Color(0xFF8B949E), fontSize = 13.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(allReminders, key = { it.id }) { rem ->
                        val mName = EthiopianDateMath.MONTH_NAMES.getOrElse(rem.ethMonth - 1) { "" }
                        val dateHeader = "$mName ${rem.ethDay}፣ ${rem.ethYear} ዓ.ም"
                        val (eHour, eMin, period) = EthiopianDateMath.gregorianToEthiopianTime(rem.hour, rem.minute)
                        val timeLabel = String.format(Locale.US, "%s %d:%02d", period, eHour, eMin)

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF161B22),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(rem.title, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                                    if (rem.note.isNotEmpty()) {
                                        Text(rem.note, color = Color(0xFF8B949E), fontSize = 12.sp)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "$dateHeader • $timeLabel",
                                        color = Color(0xFFE5A93C),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { onEditReminder(rem) }) {
                                        Text("Edit", color = Color(0xFF58A6FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    IconButton(onClick = { onDeleteReminder(rem.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFF85149).copy(alpha = 0.8f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun DateConverterView(bridge: HostBridge) {
        var convertMode by remember { mutableStateOf(0) } // 0: Greg -> Eth, 1: Eth -> Greg

        // Gregorian Inputs
        var gYear by remember { mutableStateOf("2026") }
        var gMonth by remember { mutableStateOf("10") }
        var gDay by remember { mutableStateOf("4") }

        // Ethiopian Inputs
        var eYear by remember { mutableStateOf("2019") }
        var eMonth by remember { mutableStateOf("1") }
        var eDay by remember { mutableStateOf("24") }

        var convertedResult by remember { mutableStateOf("") }

        fun performConversion() {
            try {
                if (convertMode == 0) {
                    val gy = gYear.toInt()
                    val gm = gMonth.toInt().coerceIn(1, 12)
                    val gd = gDay.toInt().coerceIn(1, 31)
                    val jdn = EthiopianDateMath.gregorianToJdn(gy, gm, gd)
                    val eth = EthiopianDateMath.jdnToEthiopian(jdn)
                    val mName = EthiopianDateMath.MONTH_NAMES[eth.month - 1]
                    convertedResult = "$mName ${eth.day}፣ ${eth.year} ዓ.ም"
                } else {
                    val ey = eYear.toInt()
                    val em = eMonth.toInt().coerceIn(1, 13)
                    val maxDays = EthiopianDateMath.daysInMonth(ey, em)
                    val ed = eDay.toInt().coerceIn(1, maxDays)
                    val jdn = EthiopianDateMath.ethiopianToJdn(ey, em, ed)
                    val greg = EthiopianDateMath.jdnToGregorian(jdn)
                    convertedResult = "${greg.second}/${greg.third}/${greg.first} (G.C)"
                }
            } catch (e: Exception) {
                convertedResult = "Invalid Date Input"
            }
        }

        LaunchedEffect(convertMode, gYear, gMonth, gDay, eYear, eMonth, eDay) {
            performConversion()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text("Date Converter", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
            Text("Bidirectional Gregorian & Ethiopian calendar engine", color = Color(0xFF8B949E), fontSize = 12.sp)

            Spacer(Modifier.height(16.dp))

            // Switch Mode Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (convertMode == 0) Color(0xFFE5A93C) else Color(0xFF161B22),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { convertMode = 0 }
                ) {
                    Box(modifier = Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Gregorian ➔ Ethiopian",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = if (convertMode == 0) Color.Black else Color.White
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (convertMode == 1) Color(0xFFE5A93C) else Color(0xFF161B22),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { convertMode = 1 }
                ) {
                    Box(modifier = Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Ethiopian ➔ Gregorian",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = if (convertMode == 1) Color.Black else Color.White
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Inputs
            if (convertMode == 0) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = gYear,
                        onValueChange = { gYear = it.filter { c -> c.isDigit() } },
                        label = { Text("Year (ዓመት)") },
                        modifier = Modifier.weight(1.2f),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    OutlinedTextField(
                        value = gMonth,
                        onValueChange = { gMonth = it.filter { c -> c.isDigit() } },
                        label = { Text("Month (ወር)") },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    OutlinedTextField(
                        value = gDay,
                        onValueChange = { gDay = it.filter { c -> c.isDigit() } },
                        label = { Text("Day (ቀን)") },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = eYear,
                        onValueChange = { eYear = it.filter { c -> c.isDigit() } },
                        label = { Text("Year (ዓ.ም)") },
                        modifier = Modifier.weight(1.2f),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    OutlinedTextField(
                        value = eMonth,
                        onValueChange = { eMonth = it.filter { c -> c.isDigit() } },
                        label = { Text("Month (1-13)") },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    OutlinedTextField(
                        value = eDay,
                        onValueChange = { eDay = it.filter { c -> c.isDigit() } },
                        label = { Text("Day (ቀን)") },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Converted Result Display Card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF161B22),
                border = BorderStroke(1.dp, Color(0xFFE5A93C).copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("CONVERTED EQUIVALENT", fontSize = 11.sp, color = Color(0xFF8B949E), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = convertedResult,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE5A93C)
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            bridge.copyToClipboard(convertedResult)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Copy Date", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    @Composable
    fun ReminderEditorDialog(
        existingReminder: CalendarReminder? = null,
        defaultEthYear: Int,
        defaultEthMonth: Int,
        defaultEthDay: Int,
        onDismiss: () -> Unit,
        onSave: (id: String?, title: String, note: String, y: Int, m: Int, d: Int, gregHour: Int, min: Int) -> Unit
    ) {
        var title by remember { mutableStateOf(existingReminder?.title ?: "") }
        var note by remember { mutableStateOf(existingReminder?.note ?: "") }

        val initialEthTime = remember(existingReminder) {
            if (existingReminder != null) {
                EthiopianDateMath.gregorianToEthiopianTime(existingReminder.hour, existingReminder.minute)
            } else {
                Triple(3, 0, "ጠዋት") // Default 3:00 ጠዋት (9:00 AM)
            }
        }

        var ethHourText by remember { mutableStateOf(initialEthTime.first.toString()) }
        var minText by remember { mutableStateOf(String.format(Locale.US, "%02d", initialEthTime.second)) }
        var selectedPeriod by remember { mutableStateOf(initialEthTime.third) }

        val periods = listOf("ጠዋት", "ከሰዓት", "ማታ", "ሌሊት")
        val mName = EthiopianDateMath.MONTH_NAMES[defaultEthMonth - 1]

        val parsedHour = ethHourText.toIntOrNull()?.coerceIn(1, 12) ?: 3
        val parsedMin = minText.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val liveSummary = String.format(Locale.US, "%s %d:%02d", selectedPeriod, parsedHour, parsedMin)

        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF161B22),
            title = {
                Column {
                    Text(
                        if (existingReminder != null) "Edit Reminder" else "Add Reminder",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Text("$mName $defaultEthDay፣ $defaultEthYear ዓ.ም", color = Color(0xFFE5A93C), fontSize = 12.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title (ርዕስ)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )

                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Note / Description (ዝርዝር)") },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )

                    // Period Selector Chips (ጠዋት, ከሰዓት, ማታ, ሌሊት)
                    Text("ክፍለ ጊዜ (Period)", fontSize = 11.sp, color = Color(0xFF8B949E), fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        periods.forEach { period ->
                            val isSel = selectedPeriod == period
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) Color(0xFFE5A93C) else Color(0xFF21262D),
                                border = BorderStroke(1.dp, if (isSel) Color(0xFFE5A93C) else Color.White.copy(alpha = 0.05f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selectedPeriod = period }
                            ) {
                                Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        period,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSel) Color.Black else Color.White
                                    )
                                }
                            }
                        }
                    }

                    // Ethiopian 12-Hour Inputs
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = ethHourText,
                            onValueChange = { ethHourText = it.filter { c -> c.isDigit() }.take(2) },
                            label = { Text("ሰዓት (1-12)") },
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                        )
                        OutlinedTextField(
                            value = minText,
                            onValueChange = { minText = it.filter { c -> c.isDigit() }.take(2) },
                            label = { Text("ደቂቃ (0-59)") },
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                        )
                    }

                    // Real-Time Active Amharic Target Badge
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0D1117),
                        border = BorderStroke(1.dp, Color(0xFFE5A93C).copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🔔", fontSize = 13.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "ማስታወሻው የሚደርሰው፡ $liveSummary",
                                color = Color(0xFFE5A93C),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val h = ethHourText.toIntOrNull()?.coerceIn(1, 12) ?: 3
                        val m = minText.toIntOrNull()?.coerceIn(0, 59) ?: 0
                        val (gregHour, gregMin) = EthiopianDateMath.ethiopianTimeToGregorian(h, m, selectedPeriod)

                        if (title.isNotBlank()) {
                            onSave(
                                existingReminder?.id,
                                title.trim(),
                                note.trim(),
                                defaultEthYear,
                                defaultEthMonth,
                                defaultEthDay,
                                gregHour,
                                gregMin
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5A93C)),
                    enabled = title.isNotBlank()
                ) {
                    Text(if (existingReminder != null) "Update" else "Save", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFF8B949E))
                }
            }
        )
    }
}