package com.omni.plugin.notes

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import com.omni.hub.api.HostBridge
import com.omni.hub.api.PluginEntry
import com.omni.plugin.notes.model.NoteItem
import com.omni.plugin.notes.model.NoteSecurity
import com.omni.plugin.notes.model.NoteStatus
import com.omni.plugin.notes.model.NoteThemeMode
import com.omni.plugin.notes.model.NoteType
import com.omni.plugin.notes.storage.NoteRepository
import com.omni.plugin.notes.ui.NoteEditorScreen
import com.omni.plugin.notes.ui.NotesHomeScreen
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class OmniNotesPlugin : PluginEntry() {

    private val pluginScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sentinelJob: Job? = null

    override fun onCreateView(context: Context, bridge: HostBridge, baseDir: String): View {
        createNotificationChannel(context)
        if (!NoteRepository.isLoaded) {
            pluginScope.launch { NoteRepository.load(bridge) }
        }
        startReminderSentinel(context, bridge)

        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val themeMode = NoteRepository.settings.themeMode
                val isDark = themeMode == NoteThemeMode.DARK

                MaterialTheme(
                    colorScheme = if (isDark) {
                        darkColorScheme(
                            background = Color(0xFF0D1117),
                            surface = Color(0xFF161B22),
                            primary = Color(0xFFE5A93C)
                        )
                    } else {
                        lightColorScheme(
                            background = Color(0xFFF6F5F0),
                            surface = Color(0xFFFFFFFF),
                            primary = Color(0xFFD97706)
                        )
                    }
                ) {
                    OmniNotesApp(context, bridge)
                }
            }
        }
    }

    override fun onStart(context: Context, bridge: HostBridge, baseDir: String) {
        createNotificationChannel(context)
        pluginScope.launch { NoteRepository.load(bridge) }
        startReminderSentinel(context, bridge)
        bridge.log("OMNI_NOTES", "📝 Omni Notes reminder sentinel armed.")
    }

    override fun onStop(context: Context) {
        sentinelJob?.cancel()
        sentinelJob = null
    }

    private fun startReminderSentinel(context: Context, bridge: HostBridge) {
        if (sentinelJob?.isActive == true) return
        sentinelJob = pluginScope.launch {
            while (isActive) {
                try {
                    val now = System.currentTimeMillis()
                    val dueNotes = NoteRepository.notes.filter {
                        it.status != NoteStatus.TRASH &&
                            it.reminderAtMs != null &&
                            !it.reminderTriggered &&
                            now >= it.reminderAtMs
                    }

                    dueNotes.forEach { due ->
                        NoteRepository.markReminderTriggered(bridge, due.id)
                        dispatchReminderNotification(context, bridge, due)
                        if (bridge.canDrawOverlays()) {
                            Handler(Looper.getMainLooper()).post {
                                mountStickyOverlay(context, bridge, due, isReminderAlert = true)
                            }
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    bridge.log("OMNI_NOTES_ERR", "Reminder sentinel tick error: ${e.message}")
                }
                delay(25_000L)
            }
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Omni Notes Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Scheduled note reminder alerts"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 350, 150, 450)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun dispatchReminderNotification(context: Context, bridge: HostBridge, note: NoteItem) {
        try {
            bridge.vibrate(400L)
            val title = note.title.ifBlank { "Omni Note Reminder" }
            val bodyText = when {
                note.isLocked -> "🔒 Locked note reminder is due"
                note.type == NoteType.CHECKLIST -> {
                    val done = note.checklist.count { it.isChecked }
                    "☑️ Checklist ($done/${note.checklist.size} completed)"
                }
                else -> note.content.ifBlank { "Reminder scheduled for now" }
            }

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                putExtra("extra_open_plugin_id", "omni_notes")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pendingIntent = if (launchIntent != null) {
                PendingIntent.getActivity(
                    context,
                    note.id.hashCode(),
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else null

            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("⏰ $title")
                .setContentText(bodyText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                .setAutoCancel(true)
                .apply {
                    if (pendingIntent != null) setContentIntent(pendingIntent)
                }
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(note.id.hashCode(), notif)
        } catch (_: Exception) {}
    }

    @Composable
    fun OmniNotesApp(context: Context, bridge: HostBridge) {
        var editingNoteId by remember { mutableStateOf<String?>(null) }
        var creatingNewType by remember { mutableStateOf<NoteType?>(null) }
        var creatingColorId by remember { mutableStateOf("yellow") }
        var creatingFolderId by remember { mutableStateOf<String?>(null) }

        var pendingUnlockNote by remember { mutableStateOf<NoteItem?>(null) }
        var pinInput by remember { mutableStateOf("") }
        var pinError by remember { mutableStateOf<String?>(null) }

        val activeEditorNote = editingNoteId?.let { id ->
            NoteRepository.notes.find { it.id == id }
        }
        val isInEditor = activeEditorNote != null || creatingNewType != null

        var homeBackInterceptor by remember { mutableStateOf<(() -> Boolean)?>(null) }

        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    pluginScope.launch { NoteRepository.syncIfModifiedOnDisk(bridge) }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        DisposableEffect(isInEditor, homeBackInterceptor) {
            bridge.setOnBackPressedHandler {
                val handledByChild = homeBackInterceptor?.invoke() ?: false
                if (handledByChild) {
                    true
                } else {
                    false
                }
            }
            onDispose {
                bridge.setOnBackPressedHandler(null)
            }
        }

        if (isInEditor) {
            NoteEditorScreen(
                context = context,
                bridge = bridge,
                existingNote = activeEditorNote,
                initialType = activeEditorNote?.type ?: (creatingNewType ?: NoteType.TEXT),
                initialColorId = activeEditorNote?.colorId ?: creatingColorId,
                initialFolderId = activeEditorNote?.folderId ?: creatingFolderId,
                registerBackInterceptor = { interceptor -> homeBackInterceptor = interceptor },
                onExitEditor = {
                    editingNoteId = null
                    creatingNewType = null
                }
            )
        } else {
            NotesHomeScreen(
                context = context,
                bridge = bridge,
                registerBackInterceptor = { interceptor -> homeBackInterceptor = interceptor },
                onOpenNote = { note ->
                    if (note.isLocked) {
                        pinInput = ""
                        pinError = null
                        pendingUnlockNote = note
                    } else {
                        editingNoteId = note.id
                        creatingNewType = null
                    }
                },
                onCreateNote = { type, colorId, folderId ->
                    creatingColorId = colorId
                    creatingFolderId = folderId
                    creatingNewType = type
                    editingNoteId = null
                }
            )
        }

        // PIN Unlock Prompt
        pendingUnlockNote?.let { lockedNote ->
            AlertDialog(
                onDismissRequest = { pendingUnlockNote = null },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔒", fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Unlock Note",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            lockedNote.title.ifBlank { "Protected Note" },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = {
                                pinInput = it.filter { ch -> ch.isDigit() }.take(6)
                                pinError = null
                            },
                            label = { Text("Enter PIN") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            isError = pinError != null
                        )
                        if (pinError != null) {
                            Text(pinError!!, color = Color(0xFFF85149), fontSize = 12.sp)
                        }
                    }
                },
                confirmButton = {
                    Button(
                                            onClick = {
                        val expectedHash = NoteRepository.settings.masterPinHash ?: lockedNote.pinHash
                        if (expectedHash == null || NoteSecurity.hashPin(pinInput) == expectedHash) {
                            val targetId = lockedNote.id
                                pendingUnlockNote = null
                                editingNoteId = targetId
                                creatingNewType = null
                            } else {
                                pinError = "Incorrect PIN"
                                bridge.vibrate(120L)
                            }
                        }
                    ) {
                        Text("Unlock")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingUnlockNote = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "omni_notes_reminders"
        private const val STICKY_PREFIX = "omni_note_sticky_"
        val activeStickyNoteIds = mutableStateListOf<String>()
        private val stickyPositions = ConcurrentHashMap<String, Pair<Int, Int>>()

        fun isStickyMounted(noteId: String): Boolean = activeStickyNoteIds.contains(noteId)

        fun toggleStickyOverlay(context: Context, bridge: HostBridge, note: NoteItem) {
            if (isStickyMounted(note.id)) {
                dismissStickyOverlay(bridge, note.id)
                bridge.showToast("Unpinned floating sticky note")
            } else {
                mountStickyOverlay(context, bridge, note, isReminderAlert = false)
            }
        }

        fun dismissStickyOverlay(bridge: HostBridge, noteId: String) {
            bridge.dismissOverlay("$STICKY_PREFIX$noteId")
            activeStickyNoteIds.remove(noteId)
        }

        fun mountStickyOverlay(
            context: Context,
            bridge: HostBridge,
            note: NoteItem,
            isReminderAlert: Boolean = false
        ) {
            if (!bridge.canDrawOverlays()) {
                bridge.showToast("Grant 'Display over other apps' permission for floating sticky notes")
                bridge.requestOverlayPermission()
                return
            }

            if (note.isLocked && !isReminderAlert) {
                bridge.showToast("Unlock note or remove PIN before pinning as floating overlay")
                return
            }

            val appContext = context.applicationContext
            val density = appContext.resources.displayMetrics.density
            fun dp(v: Float): Int = (v * density).toInt()

            val tag = "$STICKY_PREFIX${note.id}"
            val color = note.color
            val accentInt = color.accentHex.toInt()
            val bgInt = color.darkBgHex.toInt()

            val pos = stickyPositions.getOrPut(note.id) { Pair(dp(24f), dp(140f)) }
            var currentX = pos.first
            var currentY = pos.second

            val root = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(bgInt)
                    cornerRadius = dp(14f).toFloat()
                    setStroke(dp(1.5f), accentInt)
                }
                elevation = dp(12f).toFloat()
            }

            // Draggable Header Bar
            val header = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(8f), dp(10f), dp(8f))
                background = GradientDrawable().apply {
                    setColor(accentInt)
                    cornerRadii = floatArrayOf(
                        dp(12f).toFloat(), dp(12f).toFloat(),
                        dp(12f).toFloat(), dp(12f).toFloat(),
                        0f, 0f, 0f, 0f
                    )
                }
            }

            val headerTitle = TextView(appContext).apply {
                val prefix = if (isReminderAlert) "⏰ " else "📌 "
                text = prefix + note.title.ifBlank { if (note.type == NoteType.CHECKLIST) "Checklist" else "Sticky Note" }
                setTextColor(AndroidColor.parseColor("#0D1117"))
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val openBtn = TextView(appContext).apply {
                text = "↗"
                setTextColor(AndroidColor.parseColor("#0D1117"))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
                setOnClickListener {
                    try {
                        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.apply {
                            putExtra("extra_open_plugin_id", "omni_notes")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        if (launchIntent != null) appContext.startActivity(launchIntent)
                    } catch (_: Exception) {}
                }
            }

            val closeBtn = TextView(appContext).apply {
                text = "✕"
                setTextColor(AndroidColor.parseColor("#0D1117"))
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(8f), dp(2f), dp(4f), dp(2f))
                setOnClickListener {
                    dismissStickyOverlay(bridge, note.id)
                }
            }

            header.addView(headerTitle)
            header.addView(openBtn)
            header.addView(closeBtn)

            var downRawX = 0f
            var downRawY = 0f
            var startX = 0
            var startY = 0

            header.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = currentX
                        startY = currentY
                        downRawX = event.rawX
                        downRawY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        currentX = (startX + (event.rawX - downRawX)).toInt().coerceAtLeast(0)
                        currentY = (startY + (event.rawY - downRawY)).toInt().coerceAtLeast(0)
                        stickyPositions[note.id] = Pair(currentX, currentY)
                        bridge.updateOverlay(tag, currentX, currentY)
                        true
                    }
                    else -> false
                }
            }

            root.addView(header)

            // Scrollable Body
            val scrollView = ScrollView(appContext).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val bodyLayout = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f), dp(10f), dp(12f), dp(12f))
            }

            if (note.isLocked) {
                val lockedTv = TextView(appContext).apply {
                    text = "🔒 Protected reminder is due!"
                    setTextColor(AndroidColor.WHITE)
                    textSize = 13f
                }
                bodyLayout.addView(lockedTv)
            } else if (note.type == NoteType.CHECKLIST) {
                if (note.checklist.isEmpty()) {
                    val emptyTv = TextView(appContext).apply {
                        text = "(Empty checklist)"
                        setTextColor(AndroidColor.parseColor("#8B949E"))
                        textSize = 12f
                    }
                    bodyLayout.addView(emptyTv)
                } else {
                    note.checklist.take(12).forEach { entry ->
                        val rowTv = TextView(appContext).apply {
                            text = if (entry.isChecked) "☑ ${entry.text}" else "☐ ${entry.text}"
                            textSize = 13f
                            setPadding(0, dp(3f), 0, dp(3f))
                            setTextColor(
                                AndroidColor.parseColor(if (entry.isChecked) "#8B949E" else "#F0F6FC")
                            )
                            if (entry.isChecked) {
                                paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                            }
                            setOnClickListener {
                                val updatedList = note.checklist.map {
                                    if (it.id == entry.id) it.copy(isChecked = !it.isChecked) else it
                                }
                                val updatedNote = note.copy(checklist = updatedList, updatedAtMs = System.currentTimeMillis())
                                NoteRepository.upsertNote(bridge, updatedNote)
                                bridge.vibrate(20L)
                                mountStickyOverlay(appContext, bridge, updatedNote, isReminderAlert = false)
                            }
                        }
                        bodyLayout.addView(rowTv)
                    }
                }
            } else {
                val contentTv = TextView(appContext).apply {
                    text = note.content.ifBlank { "(Empty note)" }
                    setTextColor(AndroidColor.parseColor("#F0F6FC"))
                    textSize = 13f
                    setOnClickListener {
                        if (note.content.isNotBlank()) {
                            bridge.copyToClipboard(note.content)
                        }
                    }
                }
                bodyLayout.addView(contentTv)
            }

            if (isReminderAlert && note.reminderAtMs != null) {
                val timeStr = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(note.reminderAtMs))
                val remTv = TextView(appContext).apply {
                    text = "Due: $timeStr"
                    setTextColor(accentInt)
                    textSize = 11f
                    setPadding(0, dp(6f), 0, 0)
                }
                bodyLayout.addView(remTv)
            }

            scrollView.addView(bodyLayout)
            root.addView(scrollView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            val screenWidth = appContext.resources.displayMetrics.widthPixels
            val overlayWidth = (screenWidth * 0.68f).toInt().coerceIn(dp(220f), dp(340f))

            val shown = bridge.showOverlay(
                tag = tag,
                view = root,
                width = overlayWidth,
                height = ViewGroup.LayoutParams.WRAP_CONTENT,
                gravity = Gravity.TOP or Gravity.START,
                x = currentX,
                y = currentY,
                focusable = false,
                touchable = true,
                autoDismissMs = if (isReminderAlert) 25_000L else 0L
            )

            if (shown) {
                if (!activeStickyNoteIds.contains(note.id)) {
                    activeStickyNoteIds.add(note.id)
                }
                if (!isReminderAlert) {
                    bridge.showToast("📌 Pinned floating sticky note (drag header to move)")
                }
            }
        }
    }
}