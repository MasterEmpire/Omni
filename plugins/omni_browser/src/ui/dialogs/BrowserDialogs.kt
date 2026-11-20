package com.omni.plugin.browser.ui.dialogs

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.hub.api.HostBridge
import com.omni.plugin.browser.models.ActiveDownloadItem
import com.omni.plugin.browser.models.BrowserProfile
import com.omni.plugin.browser.models.ShortcutItem
import com.omni.plugin.browser.utils.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
        else -> "$bytes B"
    }
}

@Composable
fun DownloadsManagerDialog(
    bridge: HostBridge,
    activeDownloadsList: List<ActiveDownloadItem>,
    completedFilesList: List<File>,
    onCancelDownload: (Long) -> Unit,
    onDeleteFile: (File) -> Unit,
    onAttachFile: (File) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    fun openDownloadedFile(file: File) {
        try {
            if (!file.exists()) {
                bridge.showToast("File no longer exists on storage")
                return
            }
            val mime = resolveMimeType(file)
            val contentUri = getShareableUri(context, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Open with...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: android.content.ActivityNotFoundException) {
            bridge.showToast("No installed app found to open .${file.extension} files")
        } catch (e: Exception) {
            bridge.showToast("Could not open file: ${e.message}")
        }
    }

    fun shareDownloadedFile(file: File) {
        try {
            if (!file.exists()) {
                bridge.showToast("File no longer exists on storage")
                return
            }
            val mime = resolveMimeType(file)
            val contentUri = getShareableUri(context, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Share ${file.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            bridge.showToast("Share failed: ${e.message}")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF282C34),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📥", fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("Downloads", color = Color(0xFFE8EAED), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                if (completedFilesList.isNotEmpty()) {
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF1F2227)) {
                        Text(
                            "${completedFilesList.size} files",
                            color = Color(0xFF8AB4F8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Section: Active Downloads
                if (activeDownloadsList.isNotEmpty()) {
                    Text("ACTIVE DOWNLOADS", color = Color(0xFF81C995), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    activeDownloadsList.forEach { active ->
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2227)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        active.filename,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { onCancelDownload(active.downloadId) },
                                        modifier = Modifier.size(22.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color(0xFFF28B82), modifier = Modifier.size(16.dp))
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { active.progress },
                                    color = Color(0xFF81C995),
                                    trackColor = Color(0xFF3C4043),
                                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "${formatBytes(active.bytesDownloaded)} / ${if (active.totalBytes > 0) formatBytes(active.totalBytes) else "--"}",
                                        color = Color(0xFF9AA0A6),
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        "${(active.progress * 100).toInt()}%",
                                        color = Color(0xFF81C995),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = Color(0xFF3C4043))
                }

                // Section: Saved Files Vault
                Text("SAVED FILES (OmniDownloads)", color = Color(0xFF8AB4F8), fontWeight = FontWeight.Bold, fontSize = 11.sp)

                if (completedFilesList.isEmpty() && activeDownloadsList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📂", fontSize = 28.sp)
                            Spacer(Modifier.height(6.dp))
                            Text("No downloads yet", color = Color(0xFF9AA0A6), fontSize = 13.sp)
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(
                            count = completedFilesList.size,
                            key = { idx -> completedFilesList[idx].absolutePath }
                        ) { idx ->
                            val file = completedFilesList[idx]
                            Card(
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2227)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { openDownloadedFile(file) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(getFileEmoji(file.name), fontSize = 20.sp)
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            file.name,
                                            color = Color(0xFFE8EAED),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            "${formatBytes(file.length())} • ${SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(file.lastModified()))}",
                                            color = Color(0xFF9AA0A6),
                                            fontSize = 10.sp
                                        )
                                    }

                                    IconButton(
                                        onClick = { onAttachFile(file) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Text("📎", fontSize = 14.sp)
                                    }

                                    IconButton(
                                        onClick = { shareDownloadedFile(file) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = "Share", tint = Color(0xFF8AB4F8), modifier = Modifier.size(16.dp))
                                    }

                                    IconButton(
                                        onClick = { onDeleteFile(file) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFF28B82), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8AB4F8))
            ) {
                Text("Close", color = Color(0xFF1F2227), fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun SettingsBackupDialog(
    apiKey: String,
    autoSolve: Boolean,
    forceDark: Boolean = false,
    localPort: Int = 8080,
    isErudaEnabled: Boolean = false,
    isDesktopMode: Boolean = false,
    isBackgroundAudioEnabled: Boolean = true,
    dataSaverMode: String = "MEDIUM",
    filePickerProvider: String = "NEXUS",
    blockAiStudioBack: Boolean = true,
    geminiApiKey: String = "",
    autoReadAloud: Boolean = false,
    geminiVoiceName: String = "Default",
    readAloudEnabled: Boolean = true,
    showReadAloudPill: Boolean = true,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onSolveNow: () -> Unit,
    onClearCookiesAndCache: () -> Unit,
    onCustomizeMenu: () -> Unit,
    onSave: (String, Boolean, Boolean, Int, Boolean, Boolean, Boolean, String, String, Boolean, String, Boolean, String, Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var tempKey by remember { mutableStateOf(apiKey) }
    var tempAuto by remember { mutableStateOf(autoSolve) }
    var tempForceDark by remember { mutableStateOf(forceDark) }
    var tempPortText by remember { mutableStateOf(localPort.toString()) }
    var tempEruda by remember { mutableStateOf(isErudaEnabled) }
    var tempDesktop by remember { mutableStateOf(isDesktopMode) }
    var tempAudio by remember { mutableStateOf(isBackgroundAudioEnabled) }
    var tempDataSaverMode by remember { mutableStateOf(dataSaverMode) }
    var tempPickerProvider by remember { mutableStateOf(filePickerProvider) }
    var tempBlockAiStudioBack by remember { mutableStateOf(blockAiStudioBack) }
    var tempGeminiApiKey by remember { mutableStateOf(geminiApiKey) }
    var tempAutoReadAloud by remember { mutableStateOf(autoReadAloud) }
    var tempGeminiVoiceName by remember { mutableStateOf(geminiVoiceName) }
    var tempReadAloudEnabled by remember { mutableStateOf(readAloudEnabled) }
    var tempShowReadAloudPill by remember { mutableStateOf(showReadAloudPill) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF282C34),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚙️", fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Text("General Settings", color = Color(0xFFE8EAED), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Session & Profile Backup Vault", color = Color(0xFF8AB4F8), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Backs up all profiles, tabs, shortcuts, and vaults. Auto-mirrored to Documents/.omni_vault/.", color = Color(0xFF9AA0A6), fontSize = 11.sp)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onExportBackup,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("📦 Export", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = onRestoreBackup,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF8AB4F8)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("📥 Restore", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(color = Color(0xFF3C4043))

                Text("Browser & Viewport Options", color = Color(0xFF8AB4F8), fontWeight = FontWeight.Bold, fontSize = 13.sp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Desktop Site (1280px Viewport)", color = Color(0xFFE8EAED), fontSize = 12.sp)
                        Text("Spoofs desktop user agent and layout width", color = Color(0xFF9AA0A6), fontSize = 10.sp)
                    }
                    Switch(
                        checked = tempDesktop,
                        onCheckedChange = { tempDesktop = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF8AB4F8))
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Background Audio Playback", color = Color(0xFFE8EAED), fontSize = 12.sp)
                        Text("Keeps media playing when screen is off/switched", color = Color(0xFF9AA0A6), fontSize = 10.sp)
                    }
                    Switch(
                        checked = tempAudio,
                        onCheckedChange = { tempAudio = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF8AB4F8))
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Force Dark Web Content", color = Color(0xFFE8EAED), fontSize = 12.sp)
                        Text("Inverts blinding white web pages into dark mode", color = Color(0xFF9AA0A6), fontSize = 10.sp)
                    }
                    Switch(
                        checked = tempForceDark,
                        onCheckedChange = { tempForceDark = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF8AB4F8))
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🛡️ AI Studio Back Guard", color = Color(0xFFE8EAED), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Requires double-tap back on AI Studio to protect your active session", color = Color(0xFF9AA0A6), fontSize = 10.sp)
                    }
                    Switch(
                        checked = tempBlockAiStudioBack,
                        onCheckedChange = { tempBlockAiStudioBack = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF8AB4F8))
                    )
                }
                HorizontalDivider(color = Color(0xFF3C4043))

                Text("🎙️ Gemini Live Read Aloud (3.8 Live)", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🎙️ Enable Read Aloud", color = Color(0xFFE8EAED), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Injects Read buttons on AI Studio bubbles and enables voice playback", color = Color(0xFF9AA0A6), fontSize = 10.sp)
                    }
                    Switch(
                        checked = tempReadAloudEnabled,
                        onCheckedChange = { tempReadAloudEnabled = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00E5FF))
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("💊 Show Floating Read Pill", color = Color(0xFFE8EAED), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Displays the draggable Read Last pill over AI Studio", color = Color(0xFF9AA0A6), fontSize = 10.sp)
                    }
                    Switch(
                        checked = tempShowReadAloudPill,
                        enabled = tempReadAloudEnabled,
                        onCheckedChange = { tempShowReadAloudPill = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00E5FF))
                    )
                }

                OutlinedTextField(
                    value = tempGeminiApiKey,
                    onValueChange = { tempGeminiApiKey = it },
                    label = { Text("Gemini API Key") },
                    placeholder = { Text("AIza...", color = Color(0xFF5F6368), fontSize = 11.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFE8EAED),
                        unfocusedTextColor = Color(0xFFE8EAED),
                        focusedBorderColor = Color(0xFF00E5FF),
                        unfocusedBorderColor = Color(0xFF5F6368)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🔊 Auto-Read Aloud", color = Color(0xFFE8EAED), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Automatically read new AI Studio responses aloud when they arrive", color = Color(0xFF9AA0A6), fontSize = 10.sp)
                    }
                    Switch(
                        checked = tempAutoReadAloud,
                        onCheckedChange = { tempAutoReadAloud = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00E5FF))
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Voice Persona", color = Color(0xFF9AA0A6), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Default", "Aoede", "Puck", "Kore", "Fenrir").forEach { vName ->
                            val isSel = tempGeminiVoiceName == vName
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) Color(0xFF00E5FF).copy(alpha = 0.22f) else Color(0xFF1F2227),
                                border = BorderStroke(1.dp, if (isSel) Color(0xFF00E5FF) else Color(0xFF3C4043)),
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { tempGeminiVoiceName = vName }
                            ) {
                                Box(modifier = Modifier.padding(vertical = 5.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = vName,
                                        color = if (isSel) Color(0xFF00E5FF) else Color(0xFF9AA0A6),
                                        fontSize = 10.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📂 File Upload Provider", color = Color(0xFFE8EAED), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (tempPickerProvider == "NEXUS") Color(0xFF00E5FF).copy(alpha = 0.2f) else Color(0xFF3C4043)
                        ) {
                            Text(
                                text = if (tempPickerProvider == "NEXUS") "⚡ Nexus" else "📱 System",
                                color = if (tempPickerProvider == "NEXUS") Color(0xFF00E5FF) else Color(0xFF9AA0A6),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text("Selects file explorer app when tapping upload buttons on websites", color = Color(0xFF9AA0A6), fontSize = 10.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (tempPickerProvider == "NEXUS") Color(0xFF00E5FF).copy(alpha = 0.2f) else Color(0xFF1F2227),
                            border = BorderStroke(1.dp, if (tempPickerProvider == "NEXUS") Color(0xFF00E5FF) else Color(0xFF3C4043)),
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { tempPickerProvider = "NEXUS" }
                        ) {
                            Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                Text("⚡ Conduit Nexus", color = if (tempPickerProvider == "NEXUS") Color(0xFF00E5FF) else Color(0xFF9AA0A6), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (tempPickerProvider == "SYSTEM") Color(0xFF8AB4F8).copy(alpha = 0.2f) else Color(0xFF1F2227),
                            border = BorderStroke(1.dp, if (tempPickerProvider == "SYSTEM") Color(0xFF8AB4F8) else Color(0xFF3C4043)),
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { tempPickerProvider = "SYSTEM" }
                        ) {
                            Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                Text("📱 Android System", color = if (tempPickerProvider == "SYSTEM") Color(0xFF8AB4F8) else Color(0xFF9AA0A6), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🛡️ Data-Saver Mode", color = Color(0xFFE8EAED), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = when (tempDataSaverMode) {
                                "EXTREME" -> Color(0xFF3FB950).copy(alpha = 0.2f)
                                "MEDIUM" -> Color(0xFF58A6FF).copy(alpha = 0.2f)
                                else -> Color(0xFF3C4043)
                            }
                        ) {
                            Text(
                                text = tempDataSaverMode,
                                color = when (tempDataSaverMode) {
                                    "EXTREME" -> Color(0xFF3FB950)
                                    "MEDIUM" -> Color(0xFF58A6FF)
                                    else -> Color(0xFF9AA0A6)
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = when (tempDataSaverMode) {
                            "EXTREME" -> "🔥 Extreme: Blocks images, custom web fonts, media preloads & trackers."
                            "MEDIUM" -> "🛡️ Medium (Default): Keeps images visible, but blocks heavy web fonts, video preloads & ad trackers."
                            else -> "Off: Full unthrottled fidelity."
                        },
                        color = Color(0xFF9AA0A6),
                        fontSize = 10.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("OFF" to "Off", "MEDIUM" to "Medium", "EXTREME" to "Extreme").forEach { (modeKey, modeLabel) ->
                            val isSel = tempDataSaverMode == modeKey
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) {
                                    when (modeKey) {
                                        "EXTREME" -> Color(0xFF3FB950).copy(alpha = 0.25f)
                                        "MEDIUM" -> Color(0xFF58A6FF).copy(alpha = 0.25f)
                                        else -> Color(0xFF3C4043)
                                    }
                                } else Color(0xFF1F2227),
                                border = BorderStroke(1.dp, if (isSel) {
                                    when (modeKey) {
                                        "EXTREME" -> Color(0xFF3FB950)
                                        "MEDIUM" -> Color(0xFF58A6FF)
                                        else -> Color(0xFF8AB4F8)
                                    }
                                } else Color(0xFF3C4043)),
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { tempDataSaverMode = modeKey }
                            ) {
                                Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = modeLabel,
                                        color = if (isSel) Color.White else Color(0xFF9AA0A6),
                                        fontSize = 11.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF3C4043))

                Text("Developer & Diagnostics", color = Color(0xFF8AB4F8), fontWeight = FontWeight.Bold, fontSize = 13.sp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🛠️ Eruda DevTools Console", color = Color(0xFFE8EAED), fontSize = 12.sp)
                        Text("Injects floating in-page inspection console on pages", color = Color(0xFF9AA0A6), fontSize = 10.sp)
                    }
                    Switch(
                        checked = tempEruda,
                        onCheckedChange = { tempEruda = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF8AB4F8))
                    )
                }

                OutlinedTextField(
                    value = tempPortText,
                    onValueChange = { tempPortText = it.filter { ch -> ch.isDigit() }.take(5) },
                    label = { Text("Localhost Server Port (e.g. 8080)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFE8EAED),
                        unfocusedTextColor = Color(0xFFE8EAED),
                        focusedBorderColor = Color(0xFF8AB4F8),
                        unfocusedBorderColor = Color(0xFF5F6368)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider(color = Color(0xFF3C4043))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("⋮ Dynamic Menu Matrix", color = Color(0xFFE8EAED), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Control layout, order, and visibility of 3-dots menu", color = Color(0xFF9AA0A6), fontSize = 10.sp)
                    }
                    Button(
                        onClick = onCustomizeMenu,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("Layout", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(color = Color(0xFF3C4043))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("NoCaptchaAI Solver", color = Color(0xFF8AB4F8), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    TextButton(onClick = onSolveNow) {
                        Text("Solve Now", color = Color(0xFF81C995), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedTextField(
                    value = tempKey,
                    onValueChange = { tempKey = it },
                    label = { Text("NoCaptchaAI API Key") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFE8EAED),
                        unfocusedTextColor = Color(0xFFE8EAED),
                        focusedBorderColor = Color(0xFF8AB4F8),
                        unfocusedBorderColor = Color(0xFF5F6368)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto-Solve on Page Load", color = Color(0xFFE8EAED), fontSize = 12.sp)
                    Switch(
                        checked = tempAuto,
                        onCheckedChange = { tempAuto = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF8AB4F8))
                    )
                }

                HorizontalDivider(color = Color(0xFF3C4043))

                TextButton(
                    onClick = onClearCookiesAndCache,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Clear Cookies & Cache", color = Color(0xFFF28B82), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val portNum = tempPortText.toIntOrNull() ?: 8080
                    onSave(
                        tempKey.trim(), tempAuto, tempForceDark, portNum, tempEruda, tempDesktop, tempAudio,
                        tempDataSaverMode, tempPickerProvider, tempBlockAiStudioBack,
                        tempGeminiApiKey.trim(), tempAutoReadAloud, tempGeminiVoiceName,
                        tempReadAloudEnabled, tempShowReadAloudPill
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8AB4F8))
            ) {
                Text("Save", color = Color(0xFF1F2227), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFF9AA0A6))
            }
        }
    )
}

@Composable
fun EditShortcutDialog(
    shortcut: ShortcutItem,
    faviconCache: Map<String, Bitmap>,
    onFetchFavicon: (String) -> Unit,
    onPickFile: ((String) -> Unit) -> Unit,
    onDelete: () -> Unit,
    onSave: (String, String, Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var editName by remember(shortcut) { mutableStateOf(shortcut.title) }
    var editUrl by remember(shortcut) { mutableStateOf(shortcut.localSourcePath ?: shortcut.url) }
    var isDefault by remember(shortcut) { mutableStateOf(shortcut.isDefault) }
    var isGlobalProfile by remember(shortcut) { mutableStateOf(shortcut.isGlobalProfile) }
    val previewDomain = remember(editUrl) { extractDomain(editUrl) }

    LaunchedEffect(previewDomain) {
        onFetchFavicon(previewDomain)
    }
    val previewBmp = faviconCache[previewDomain]

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF282C34),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (previewBmp != null) {
                    Image(
                        bitmap = previewBmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp).clip(CircleShape)
                    )
                } else {
                    Surface(shape = CircleShape, color = Color(shortcut.colorValue), modifier = Modifier.size(24.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(editName.take(1).uppercase(), fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text("Edit Shortcut", color = Color(0xFFE8EAED), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFE8EAED),
                        unfocusedTextColor = Color(0xFFE8EAED),
                        focusedBorderColor = Color(0xFF8AB4F8),
                        unfocusedBorderColor = Color(0xFF5F6368)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = editUrl,
                    onValueChange = { editUrl = it },
                    label = { Text("URL or Local File Path") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { onPickFile { editUrl = it } }) {
                            Icon(Icons.Default.Add, contentDescription = "Browse File", tint = Color(0xFF8AB4F8))
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFE8EAED),
                        unfocusedTextColor = Color(0xFFE8EAED),
                        focusedBorderColor = Color(0xFF8AB4F8),
                        unfocusedBorderColor = Color(0xFF5F6368)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (com.omni.plugin.browser.utils.isLocalFilePath(editUrl) || shortcut.localSourcePath != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1F2227),
                        border = BorderStroke(1.dp, Color(0xFF388BFD).copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🔄", fontSize = 15.sp)
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-Sync on Save", color = Color(0xFF58A6FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "Tapping Save will re-sync the latest file directly from disk into the vault.",
                                    color = Color(0xFF8B949E),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("⭐ Set as Default Header IDE", color = Color(0xFFE8EAED), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Tapping 💻 in header will open this workspace directly", color = Color(0xFF9AA0A6), fontSize = 10.sp)
                        }
                        Switch(
                            checked = isDefault,
                            onCheckedChange = { isDefault = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF8AB4F8))
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("🌐 Global Workspace (Shared Storage)", color = Color(0xFFE8EAED), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Pins app to Default profile so IndexedDB and localStorage are shared across all profiles", color = Color(0xFF9AA0A6), fontSize = 10.sp)
                        }
                        Switch(
                            checked = isGlobalProfile,
                            onCheckedChange = { isGlobalProfile = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF58A6FF))
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDelete) {
                    Text("Delete", color = Color(0xFFF28B82), fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { onSave(editName, editUrl, isDefault, isGlobalProfile) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8AB4F8))
                ) {
                    Text("Save", color = Color(0xFF1F2227), fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF9AA0A6))
            }
        }
    )
}

@Composable
fun MenuCustomizerDialog(
    initialActive: List<String>,
    initialHidden: List<String>,
    initialBottomBar: List<String> = listOf("smart_notes", "downloads", "home", "tabs", "menu"),
    initialTopBar: List<String> = listOf("quick_toggle", "tabs"),
    onSave: (List<String>, List<String>, List<String>, List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var topBar by remember { mutableStateOf(initialTopBar) }
    var bottomBar by remember { mutableStateOf(initialBottomBar) }
    var active by remember { mutableStateOf(initialActive) }
    var hidden by remember { mutableStateOf(initialHidden) }

    val names = mapOf(
        "quick_toggle" to "⇄ Quick Toggle Tab",
        "tabs" to "📑 Tab Switcher",
        "home" to "🏠 Home / Speed Dial",
        "menu" to "☰ Hamburger Menu",
        "back" to "‹ Back",
        "forward" to "› Forward",
        "reload" to "↻ Reload",
        "new_tab" to "＋ New Tab",
        "force_dark" to "☀️/🌙 Dark Mode Toggle",
        "data_saver" to "🛡️ Data-saver Mode",
        "site_cache" to "📦 Cache This Site Vault",
        "page_zoom" to "🔍 Page Zoom Slider",
        "favorite_tab" to "⭐ Favorite Tab",
        "focus_lock" to "🔒 Focus Lock Toggle",
        "copy_url" to "🔗 Copy Clean URL",
        "dom_snapshot" to "📸 Capture DOM Snapshot",
        "full_source" to "🗜️ Export Full Web Source (ZIP)",
        "local_ide" to "💻 Open Local IDE",
        "studio_lab" to "🧪 Studio Lab (Playbooks)",
        "autopilot" to "⚡ Live Auto-Pilot",
        "ai_automator" to "🤖 AI Studio Automator",
        "smart_notes" to "📝 Smart Notes",
        "downloads" to "📥 Downloads",
        "recents" to "📱 Recents Deck",
        "diagnostics" to "📋 Diagnostics Console",
        "settings" to "⚙️ General Settings",
        "exit" to "⏻ Exit Omni Chrome"
    )

    val navCandidates = listOf("quick_toggle", "tabs", "home", "smart_notes", "downloads", "back", "forward", "reload", "new_tab")

    fun moveUp(list: List<String>, idx: Int): List<String> {
        if (idx <= 0) return list
        val m = list.toMutableList()
        val t = m[idx - 1]; m[idx - 1] = m[idx]; m[idx] = t
        return m
    }

    fun moveDown(list: List<String>, idx: Int): List<String> {
        if (idx >= list.lastIndex) return list
        val m = list.toMutableList()
        val t = m[idx + 1]; m[idx + 1] = m[idx]; m[idx] = t
        return m
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF282C34),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("☰", fontSize = 20.sp, color = Color(0xFF8AB4F8), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("Customize Bars & Menu", color = Color(0xFFE8EAED), fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 1. TOP RIGHT BAR SLOTS
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("TOP RIGHT SLOTS (${topBar.size}/3)", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (topBar.size < 3) {
                            val addable = navCandidates.firstOrNull { it !in topBar && it != "menu" }
                            if (addable != null) {
                                TextButton(
                                    onClick = { topBar = topBar + addable },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Text("+ Add", color = Color(0xFF00E5FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                items(topBar.size) { index ->
                    val id = topBar[index]
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF161B22),
                        border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(names[id] ?: id, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { topBar = moveUp(topBar, index) }, enabled = index > 0, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Left", tint = if (index > 0) Color(0xFF58A6FF) else Color(0xFF5F6368))
                                }
                                IconButton(onClick = { topBar = moveDown(topBar, index) }, enabled = index < topBar.lastIndex, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Right", tint = if (index < topBar.lastIndex) Color(0xFF58A6FF) else Color(0xFF5F6368))
                                }
                                TextButton(
                                    onClick = { topBar = topBar.filter { it != id } },
                                    contentPadding = PaddingValues(horizontal = 4.dp),
                                    modifier = Modifier.height(22.dp)
                                ) {
                                    Text("Remove", color = Color(0xFFF28B82), fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }

                // 2. BOTTOM BAR SLOTS
                item {
                    Spacer(Modifier.height(6.dp))
                    Text("BOTTOM NAV BAR (${bottomBar.size}/6)", color = Color(0xFFD29922), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                items(bottomBar.size) { index ->
                    val id = bottomBar[index]
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF161B22),
                        border = BorderStroke(1.dp, Color(0xFFD29922).copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(names[id] ?: id, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { bottomBar = moveUp(bottomBar, index) }, enabled = index > 0, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Left", tint = if (index > 0) Color(0xFF58A6FF) else Color(0xFF5F6368))
                                }
                                IconButton(onClick = { bottomBar = moveDown(bottomBar, index) }, enabled = index < bottomBar.lastIndex, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Right", tint = if (index < bottomBar.lastIndex) Color(0xFF58A6FF) else Color(0xFF5F6368))
                                }
                                if (id != "menu") {
                                    TextButton(
                                        onClick = {
                                            bottomBar = bottomBar.filter { it != id }
                                            if (id !in active && id !in hidden) active = active + id
                                        },
                                        contentPadding = PaddingValues(horizontal = 4.dp),
                                        modifier = Modifier.height(22.dp)
                                    ) {
                                        Text("To Grid", color = Color(0xFF8AB4F8), fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // Quick add extra nav shortcuts (Back, Forward, Reload, New Tab, Quick Toggle)
                val extraAddable = navCandidates.filter { it !in bottomBar || it !in topBar }
                if (extraAddable.isNotEmpty()) {
                    item {
                        Text("ADD QUICK ACTIONS TO BARS", color = Color(0xFF9AA0A6), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            extraAddable.take(4).forEach { candId ->
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF1F2227),
                                    border = BorderStroke(1.dp, Color(0xFF3C4043)),
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                                        if (candId !in bottomBar && bottomBar.size < 6) {
                                            bottomBar = bottomBar + candId
                                        } else if (candId !in topBar && topBar.size < 3) {
                                            topBar = topBar + candId
                                        }
                                    }
                                ) {
                                    Text(names[candId]?.take(12) ?: candId, color = Color(0xFF81C995), fontSize = 9.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
                                }
                            }
                        }
                    }
                }

                // 3. HAMBURGER 5x2 GRID ITEMS
                item {
                    Spacer(Modifier.height(6.dp))
                    Text("HAMBURGER GRID (10 per page)", color = Color(0xFF81C995), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                items(active.size) { index ->
                    val id = active[index]
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1F2227),
                        border = BorderStroke(1.dp, Color(0xFF3C4043)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(names[id] ?: id, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { active = moveUp(active, index) }, enabled = index > 0, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Up", tint = if (index > 0) Color(0xFF58A6FF) else Color(0xFF5F6368))
                                }
                                IconButton(onClick = { active = moveDown(active, index) }, enabled = index < active.lastIndex, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Down", tint = if (index < active.lastIndex) Color(0xFF58A6FF) else Color(0xFF5F6368))
                                }
                                if (bottomBar.size < 6 && id !in bottomBar) {
                                    TextButton(
                                        onClick = {
                                            bottomBar = bottomBar + id
                                        },
                                        contentPadding = PaddingValues(horizontal = 4.dp),
                                        modifier = Modifier.height(22.dp)
                                    ) {
                                        Text("Pin", color = Color(0xFFD29922), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        active = active.filter { it != id }
                                        hidden = hidden + id
                                    },
                                    contentPadding = PaddingValues(horizontal = 4.dp),
                                    modifier = Modifier.height(22.dp)
                                ) {
                                    Text("Hide", color = Color(0xFFF28B82), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                if (hidden.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text("HIDDEN ITEMS", color = Color(0xFF9AA0A6), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    items(hidden.size) { index ->
                        val id = hidden[index]
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF161B22),
                            border = BorderStroke(1.dp, Color(0xFF3C4043)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(names[id] ?: id, color = Color(0xFF9AA0A6), fontSize = 11.sp, modifier = Modifier.weight(1f))
                                TextButton(
                                    onClick = {
                                        hidden = hidden.filter { it != id }
                                        active = active + id
                                    },
                                    contentPadding = PaddingValues(horizontal = 4.dp),
                                    modifier = Modifier.height(22.dp)
                                ) {
                                    Text("+ Show", color = Color(0xFF81C995), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(active, hidden, bottomBar, topBar) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8AB4F8))
            ) {
                Text("Save Layout", color = Color(0xFF1F2227), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF9AA0A6))
            }
        }
    )
}

@Composable
fun AddShortcutDialog(
    faviconCache: Map<String, Bitmap>,
    onFetchFavicon: (String) -> Unit,
    onPickFile: ((String) -> Unit) -> Unit,
    onAdd: (String, String, Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("https://") }
    var isDefault by remember { mutableStateOf(false) }
    var isGlobalProfile by remember { mutableStateOf(true) }
    val previewDomain = remember(newUrl) { extractDomain(newUrl) }

    LaunchedEffect(previewDomain) {
        if (previewDomain.isNotEmpty() && previewDomain != "https://") {
            onFetchFavicon(previewDomain)
        }
    }
    val previewBmp = faviconCache[previewDomain]

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF282C34),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (previewBmp != null) {
                    Image(
                        bitmap = previewBmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp).clip(CircleShape)
                    )
                } else {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF8AB4F8))
                }
                Spacer(Modifier.width(10.dp))
                Text("Add Shortcut", color = Color(0xFFE8EAED), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name (e.g. GitHub or Local IDE)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFE8EAED),
                        unfocusedTextColor = Color(0xFFE8EAED),
                        focusedBorderColor = Color(0xFF8AB4F8),
                        unfocusedBorderColor = Color(0xFF5F6368)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = newUrl,
                    onValueChange = { newUrl = it },
                    label = { Text("URL or File Path") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            onPickFile { directPath ->
                                newUrl = directPath
                                if (newName.isEmpty()) {
                                    newName = directPath.substringAfterLast("/").substringBeforeLast(".")
                                }
                            }
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Browse File", tint = Color(0xFF8AB4F8))
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFE8EAED),
                        unfocusedTextColor = Color(0xFFE8EAED),
                        focusedBorderColor = Color(0xFF8AB4F8),
                        unfocusedBorderColor = Color(0xFF5F6368)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (com.omni.plugin.browser.utils.isLocalFilePath(newUrl)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("⭐ Set as Default Header IDE", color = Color(0xFFE8EAED), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Tapping 💻 in header will open this workspace directly", color = Color(0xFF9AA0A6), fontSize = 10.sp)
                        }
                        Switch(
                            checked = isDefault,
                            onCheckedChange = { isDefault = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF8AB4F8))
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("🌐 Global Workspace (Shared Storage)", color = Color(0xFFE8EAED), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Pins app to Default profile so IndexedDB and localStorage are shared across all profiles", color = Color(0xFF9AA0A6), fontSize = 10.sp)
                        }
                        Switch(
                            checked = isGlobalProfile,
                            onCheckedChange = { isGlobalProfile = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF58A6FF))
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(newName, newUrl, isDefault, isGlobalProfile) },
                enabled = newUrl.length >= 3,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8AB4F8))
            ) {
                Text("Add", color = Color(0xFF1F2227), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF9AA0A6))
            }
        }
    )
}

@Composable
fun RenameProfileDialog(
    profile: BrowserProfile,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var renameText by remember(profile) { mutableStateOf(profile.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF282C34),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(profile.colorValue))
                )
                Spacer(Modifier.width(8.dp))
                Text("Rename Profile", color = Color(0xFFE8EAED), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            OutlinedTextField(
                value = renameText,
                onValueChange = { renameText = it },
                label = { Text("Profile Display Name") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFFE8EAED),
                    unfocusedTextColor = Color(0xFFE8EAED),
                    focusedBorderColor = Color(profile.colorValue),
                    unfocusedBorderColor = Color(0xFF5F6368)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    val newName = renameText.trim().ifEmpty { profile.name }
                    onSave(newName)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(profile.colorValue))
            ) {
                Text("Save", color = Color(0xFF1F2227), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF9AA0A6))
            }
        }
    )
}