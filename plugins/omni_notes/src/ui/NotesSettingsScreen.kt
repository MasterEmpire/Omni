package com.omni.plugin.notes.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.hub.api.HostBridge
import com.omni.plugin.notes.model.DefaultCreateAction
import com.omni.plugin.notes.model.NoteSecurity
import com.omni.plugin.notes.model.NoteThemeMode
import com.omni.plugin.notes.model.NoteViewMode
import com.omni.plugin.notes.storage.NoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesSettingsScreen(
    context: Context,
    bridge: HostBridge,
    registerBackInterceptor: ((() -> Boolean)?) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val settings = NoteRepository.settings
    val isDark = settings.themeMode == NoteThemeMode.DARK
    val bgColor = if (isDark) Color(0xFF0D1117) else Color(0xFFF6F5F0)
    val surfaceColor = if (isDark) Color(0xFF161B22) else Color.White
    val primaryText = if (isDark) Color(0xFFF0F6FC) else Color(0xFF1F2328)
    val mutedText = if (isDark) Color(0xFF8B949E) else Color(0xFF57606A)
    val accent = Color(0xFFE5A93C)

    var standaloneWindowEnabled by remember { mutableStateOf(bridge.isStandaloneWindowEnabled()) }
    var hasVaultAccess by remember { mutableStateOf(NoteRepository.hasExternalVaultPermission()) }
    var showMasterPinDialog by remember { mutableStateOf(false) }

    var pendingImportJson by remember { mutableStateOf<String?>(null) }
    var pendingImportEncryptedCount by remember { mutableIntStateOf(0) }
    var showColorNoteDecryptDialog by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        registerBackInterceptor {
            onBack()
            true
        }
        onDispose { }
    }

    Scaffold(
        containerColor = bgColor,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = surfaceColor),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = primaryText)
                    }
                },
                title = {
                    Text("⚙️ Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = primaryText)
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Appearance & Paper
            SettingsSectionCard(surfaceColor = surfaceColor, borderColor = mutedText.copy(alpha = 0.2f)) {
                Text("APPEARANCE & PAPER", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                SettingsSwitchRow(
                    title = "Light Theme",
                    subtitle = if (isDark) "Dark slate UI active" else "Bright pastel UI active",
                    checked = !isDark,
                    primaryText = primaryText,
                    mutedText = mutedText,
                    onCheckedChange = { light ->
                        NoteRepository.updateSettings(
                            bridge,
                            settings.copy(themeMode = if (light) NoteThemeMode.LIGHT else NoteThemeMode.DARK)
                        )
                    }
                )

                HorizontalDivider(color = mutedText.copy(alpha = 0.14f))

                SettingsSwitchRow(
                    title = "Paper Mode (Ruled Lines & Margin)",
                    subtitle = "Notebook horizontal lines + writable left margin column",
                    checked = settings.isPaperMode,
                    primaryText = primaryText,
                    mutedText = mutedText,
                    onCheckedChange = { paper ->
                        NoteRepository.updateSettings(bridge, settings.copy(isPaperMode = paper))
                    }
                )

                HorizontalDivider(color = mutedText.copy(alpha = 0.14f))

                Spacer(Modifier.height(6.dp))
                Text("Default Card Layout", color = primaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    NoteViewMode.entries.forEach { mode ->
                        val isSel = settings.viewMode == mode
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) accent.copy(alpha = 0.22f) else bgColor,
                            border = BorderStroke(1.dp, if (isSel) accent else mutedText.copy(alpha = 0.25f)),
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable {
                                NoteRepository.updateSettings(bridge, settings.copy(viewMode = mode))
                            }
                        ) {
                            Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    "${mode.badge} ${mode.label}",
                                    color = if (isSel) accent else primaryText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // 2. Editor & Quick Create
            SettingsSectionCard(surfaceColor = surfaceColor, borderColor = mutedText.copy(alpha = 0.2f)) {
                Text("EDITOR & FORMAT", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                Text("Default '+' Button Action", color = primaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Long-press '+' anytime to choose the other type", color = mutedText, fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    DefaultCreateAction.entries.forEach { action ->
                        val isSel = settings.defaultCreateAction == action
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) accent.copy(alpha = 0.22f) else bgColor,
                            border = BorderStroke(1.dp, if (isSel) accent else mutedText.copy(alpha = 0.25f)),
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable {
                                NoteRepository.updateSettings(bridge, settings.copy(defaultCreateAction = action))
                            }
                        ) {
                            Box(modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    action.label,
                                    color = if (isSel) accent else primaryText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = mutedText.copy(alpha = 0.14f))

                SettingsSwitchRow(
                    title = "Enable Markdown & Clickable Links",
                    subtitle = "Renders **bold**, *italic*, headings, code & opens URLs in Omni Browser",
                    checked = settings.isMarkdownEnabled,
                    primaryText = primaryText,
                    mutedText = mutedText,
                    onCheckedChange = { md ->
                        NoteRepository.updateSettings(bridge, settings.copy(isMarkdownEnabled = md))
                    }
                )
            }

            // 3. Security & Window
            SettingsSectionCard(surfaceColor = surfaceColor, borderColor = mutedText.copy(alpha = 0.2f)) {
                Text("SECURITY & WINDOW", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showMasterPinDialog = true }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (settings.masterPinHash.isNullOrBlank()) "🔒 Set Global Master Lock PIN" else "🔐 Change Master Lock PIN",
                            color = primaryText,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text("One password protects all locked notes across Omni Notes & Browser", color = mutedText, fontSize = 10.sp)
                    }
                    Text("Configure", color = Color(0xFF58A6FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                HorizontalDivider(color = mutedText.copy(alpha = 0.14f))

                SettingsSwitchRow(
                    title = "Standalone OS Window",
                    subtitle = "Show Omni Notes as a separate card in your phone Recents",
                    checked = standaloneWindowEnabled,
                    primaryText = primaryText,
                    mutedText = mutedText,
                    onCheckedChange = { enabled ->
                        standaloneWindowEnabled = enabled
                        bridge.setStandaloneWindowEnabled(enabled)
                    }
                )
            }

            // 4. Proactive Vault Backup & Migration
            SettingsSectionCard(surfaceColor = surfaceColor, borderColor = mutedText.copy(alpha = 0.2f)) {
                Text("PROACTIVE VAULT BACKUP & MIGRATION", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                val lastSyncStr = remember(NoteRepository.lastAutoVaultBackupMs) {
                    val ms = NoteRepository.lastAutoVaultBackupMs
                    if (ms > 0L) "Last auto-synced ${SimpleDateFormat("h:mm:ss a", Locale.US).format(Date(ms))}"
                    else "Auto-mirrors every change to Documents/.omni_runtime/notes_backup/"
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (hasVaultAccess) Color(0xFF238636).copy(alpha = 0.16f) else Color(0xFFF85149).copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, if (hasVaultAccess) Color(0xFF3FB950) else Color(0xFFF85149)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            if (!hasVaultAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                try {
                                    val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(i)
                                } catch (_: Exception) {}
                            } else {
                                hasVaultAccess = NoteRepository.hasExternalVaultPermission()
                            }
                        }
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            if (hasVaultAccess) "🛡️ Proactive Auto-Vault Active" else "⚠️ All Files Access Required for Auto-Vault",
                            color = if (hasVaultAccess) Color(0xFF3FB950) else Color(0xFFF85149),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (hasVaultAccess) lastSyncStr else "Tap here to grant All Files Access so notes survive app uninstallation.",
                            color = mutedText,
                            fontSize = 10.sp
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val path = withContext(Dispatchers.IO) { NoteRepository.exportVaultBackup(bridge) }
                                hasVaultAccess = NoteRepository.hasExternalVaultPermission()
                                bridge.showToast(if (path != null) "Backed up ${NoteRepository.notes.size} notes!" else "Backup failed")
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("💾 Save Vault Now", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val count = withContext(Dispatchers.IO) { NoteRepository.importVaultBackup(bridge) }
                                val msg = when {
                                    count > 0 -> "Restored $count notes from Vault!"
                                    count == 0 -> "All Vault notes already up to date"
                                    count == -1 -> "No Vault backup found yet"
                                    else -> "Restore failed"
                                }
                                bridge.showToast(msg)
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("📥 Restore Vault", fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        bridge.pickFiles("application/json", false) { uris ->
                            val uri = uris.firstOrNull() ?: return@pickFiles
                            scope.launch {
                                try {
                                    val (raw, encCount) = withContext(Dispatchers.IO) {
                                        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                                        if (text.isNullOrBlank()) Pair(null, 0)
                                        else Pair(text, NoteRepository.countEncryptedInColorNoteJson(text))
                                    }
                                    if (raw.isNullOrBlank()) {
                                        bridge.showToast("Selected file is empty")
                                        return@launch
                                    }
                                    if (encCount > 0) {
                                        pendingImportJson = raw
                                        pendingImportEncryptedCount = encCount
                                        showColorNoteDecryptDialog = true
                                    } else {
                                        val res = withContext(Dispatchers.IO) { NoteRepository.importColorNoteJson(bridge, raw, null) }
                                        bridge.showToast("Imported ${res.importedCount} ColorNote notes!")
                                    }
                                } catch (e: Exception) {
                                    bridge.showToast("Import error: ${e.message}")
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📒 Import ColorNote JSON", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    if (showMasterPinDialog) {
        val hasExisting = !settings.masterPinHash.isNullOrBlank()
        var currentPin by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showMasterPinDialog = false },
            containerColor = surfaceColor,
            title = {
                Text(
                    if (hasExisting) "🔐 Change Master PIN" else "🔒 Set Global Master PIN",
                    color = primaryText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasExisting) {
                        OutlinedTextField(
                            value = currentPin,
                            onValueChange = { currentPin = it.filter { c -> c.isDigit() }.take(6); err = null },
                            label = { Text("Current PIN") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = primaryText, unfocusedTextColor = primaryText)
                        )
                    }
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(6); err = null },
                        label = { Text("New PIN (4-6 digits)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = primaryText, unfocusedTextColor = primaryText)
                    )
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(6); err = null },
                        label = { Text("Confirm New PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = primaryText, unfocusedTextColor = primaryText)
                    )
                    if (err != null) {
                        Text(err!!, color = Color(0xFFF85149), fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (hasExisting && NoteSecurity.hashPin(currentPin) != settings.masterPinHash) {
                            err = "Incorrect current PIN"
                            return@Button
                        }
                        if (newPin.length < 4) {
                            err = "Use at least 4 digits"
                            return@Button
                        }
                        if (newPin != confirmPin) {
                            err = "PINs do not match"
                            return@Button
                        }
                        NoteRepository.updateSettings(bridge, settings.copy(masterPinHash = NoteSecurity.hashPin(newPin)))
                        showMasterPinDialog = false
                        bridge.showToast("🔐 Master PIN saved")
                    }
                ) {
                    Text("Save PIN")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMasterPinDialog = false }) {
                    Text("Cancel", color = mutedText)
                }
            }
        )
    }

    if (showColorNoteDecryptDialog && pendingImportJson != null) {
        var passwordInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                showColorNoteDecryptDialog = false
                pendingImportJson = null
            },
            containerColor = surfaceColor,
            title = { Text("🔒 Locked Notes Detected", color = primaryText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Found $pendingImportEncryptedCount locked note(s) in this ColorNote export. Enter your password to decrypt them (or skip locked notes).",
                        color = mutedText,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("ColorNote Password / PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = primaryText, unfocusedTextColor = primaryText)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val raw = pendingImportJson!!
                        val pw = passwordInput
                        showColorNoteDecryptDialog = false
                        pendingImportJson = null
                        scope.launch {
                            val res = withContext(Dispatchers.IO) { NoteRepository.importColorNoteJson(bridge, raw, pw) }
                            bridge.showToast("Imported ${res.importedCount} notes (${res.decryptedCount} decrypted, ${res.lockedSkippedOrFailedCount} skipped)")
                        }
                    },
                    enabled = passwordInput.isNotEmpty()
                ) {
                    Text("Decrypt & Import")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val raw = pendingImportJson!!
                        showColorNoteDecryptDialog = false
                        pendingImportJson = null
                        scope.launch {
                            val res = withContext(Dispatchers.IO) { NoteRepository.importColorNoteJson(bridge, raw, null) }
                            bridge.showToast("Imported ${res.importedCount} unencrypted notes (${res.lockedSkippedOrFailedCount} locked skipped)")
                        }
                    }
                ) {
                    Text("Skip Locked", color = mutedText)
                }
            }
        )
    }
}

@Composable
private fun SettingsSectionCard(
    surfaceColor: Color,
    borderColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = surfaceColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    primaryText: Color,
    mutedText: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = primaryText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(subtitle, color = mutedText, fontSize = 10.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}