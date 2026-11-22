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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.omni.plugin.browser.models.ActiveDownloadItem
import com.omni.plugin.browser.models.BrowserProfile
import com.omni.plugin.browser.models.ShortcutItem
import com.omni.plugin.browser.utils.*
import com.omni.plugin.browser.ui.ALL_BROWSER_SLOT_LABELS
import com.omni.plugin.browser.ui.BROWSER_SLOT_LABEL_MAP
import com.omni.plugin.browser.ui.BrowserIcons
import com.omni.plugin.browser.ui.getBrowserIconForSlot
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
fun LocalShortcutCodeEditorDialog(
    bridge: HostBridge,
    appTitle: String,
    initialCode: String,
    onSaveCode: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var codeText by remember(initialCode) { mutableStateOf(initialCode) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color(0xFF16181D),
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Surface(
                    color = Color(0xFF1E222A),
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Close", tint = Color(0xFFE8EAED))
                            }
                            Column {
                                Text(
                                    text = "💻 ${appTitle.ifBlank { "Local App" }} Code",
                                    color = Color(0xFFE8EAED),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${codeText.lines().size} lines • ${codeText.length} chars",
                                    color = Color(0xFF8B949E),
                                    fontSize = 10.sp
                                )
                            }
                        }

                        Button(
                            onClick = { onSaveCode(codeText) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text("Save Code", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Clear / Copy / Paste Action Toolbar
                Surface(
                    color = Color(0xFF1B1F26),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                codeText = ""
                                bridge.showToast("Editor cleared")
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF28B82)),
                            border = BorderStroke(1.dp, Color(0xFFF28B82).copy(alpha = 0.5f)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(34.dp)
                        ) {
                            Text("🗑️ Clear", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                bridge.copyToClipboard(codeText)
                                bridge.showToast("📋 Copied ${codeText.length} chars")
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF8AB4F8)),
                            border = BorderStroke(1.dp, Color(0xFF8AB4F8).copy(alpha = 0.5f)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(34.dp)
                        ) {
                            Text("📋 Copy", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                val clip = bridge.getClipboardText()
                                if (!clip.isNullOrEmpty()) {
                                    codeText = clip
                                    bridge.showToast("📥 Pasted ${clip.length} chars")
                                } else {
                                    bridge.showToast("Clipboard is empty")
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.55f)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(34.dp)
                        ) {
                            Text("📥 Paste", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF2E333D))

                // Code Editor Body
                OutlinedTextField(
                    value = codeText,
                    onValueChange = { codeText = it },
                    placeholder = { Text("Paste or edit app HTML/JS source code here...", color = Color(0xFF5F6368), fontSize = 12.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = Color(0xFFC9D1D9)
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0D1117),
                        unfocusedContainerColor = Color(0xFF0D1117),
                        focusedBorderColor = Color(0xFF30363D),
                        unfocusedBorderColor = Color(0xFF21262D)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(10.dp)
                )
            }
        }
    }
}

@Composable
fun EditShortcutDialog(
    bridge: HostBridge,
    shortcut: ShortcutItem,
    faviconCache: Map<String, Bitmap>,
    onFetchFavicon: (String) -> Unit,
    onPickFile: ((String) -> Unit) -> Unit,
    onReadLocalCode: (String) -> Result<String>,
    onDelete: () -> Unit,
    onSave: (String, String, Boolean, Boolean, String?) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var editName by remember(shortcut) { mutableStateOf(shortcut.title) }
    var editUrl by remember(shortcut) { mutableStateOf(shortcut.localSourcePath ?: shortcut.url) }
    var isDefault by remember(shortcut) { mutableStateOf(shortcut.isDefault) }
    var isGlobalProfile by remember(shortcut) { mutableStateOf(shortcut.isGlobalProfile) }
    var draftEditedCode by remember(shortcut) { mutableStateOf<String?>(null) }
    var editorInitialCode by remember { mutableStateOf<String?>(null) }
    var isLoadingCode by remember { mutableStateOf(false) }
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
                    onValueChange = {
                        editUrl = it
                        draftEditedCode = null
                    },
                    label = { Text("URL or Local File Path") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            onPickFile {
                                editUrl = it
                                draftEditedCode = null
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

                if (com.omni.plugin.browser.utils.isLocalFilePath(editUrl) || shortcut.localSourcePath != null) {
                    OutlinedButton(
                        onClick = {
                            if (draftEditedCode != null) {
                                editorInitialCode = draftEditedCode
                            } else {
                                isLoadingCode = true
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) { onReadLocalCode(editUrl) }
                                    isLoadingCode = false
                                    res.onSuccess { txt ->
                                        editorInitialCode = txt
                                    }.onFailure { err ->
                                        bridge.showToast("⚠️ ${err.message ?: "Could not load code"}")
                                        editorInitialCode = ""
                                    }
                                }
                            }
                        },
                        enabled = !isLoadingCode,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                        border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.65f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLoadingCode) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF00E5FF))
                            Spacer(Modifier.width(8.dp))
                            Text("Loading Source...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Text(
                                if (draftEditedCode != null) "💻 Edit App Code (Modified)" else "💻 View / Edit App Code",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

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
                            Text(if (draftEditedCode != null) "✏️" else "🔄", fontSize = 15.sp)
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (draftEditedCode != null) "Edited Code Queued" else "Auto-Sync on Save",
                                    color = Color(0xFF58A6FF),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    if (draftEditedCode != null) "Tapping Save will apply your edited code into the vault."
                                    else "Tapping Save will re-sync the latest file directly from disk into the vault.",
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
                    onClick = { onSave(editName, editUrl, isDefault, isGlobalProfile, draftEditedCode) },
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

    if (editorInitialCode != null) {
        LocalShortcutCodeEditorDialog(
            bridge = bridge,
            appTitle = editName.ifBlank { shortcut.title },
            initialCode = editorInitialCode!!,
            onSaveCode = { updated ->
                draftEditedCode = updated
                editorInitialCode = null
                bridge.showToast("✅ Code ready — tap Save to apply")
            },
            onDismiss = { editorInitialCode = null }
        )
    }
}

@Composable
fun MenuCustomizerDialog(
    initialActive: List<String>,
    initialHidden: List<String>,
    initialBottomBar: List<String> = listOf("smart_notes", "downloads", "home", "tabs", "menu"),
    initialTopBar: List<String> = listOf("quick_toggle", "tabs"),
    initialTopLeft: List<String> = listOf("home"),
    onSave: (List<String>, List<String>, List<String>, List<String>, List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var topLeft by remember { mutableStateOf(initialTopLeft.distinct()) }
    var topBar by remember { mutableStateOf(initialTopBar.distinct()) }
    var bottomBar by remember { mutableStateOf(initialBottomBar.distinct()) }
    var active by remember { mutableStateOf(initialActive.distinct()) }
    var hidden by remember { mutableStateOf(initialHidden.distinct()) }
    var activeZone by remember { mutableStateOf("BOTTOM") } // "TOP_LEFT", "TOP_RIGHT", "BOTTOM", "GRID"
    var addPickerZone by remember { mutableStateOf<String?>(null) }

    fun reorderByKey(list: List<String>, fromKey: Any?, toKey: Any?): List<String> {
        val fromIdx = list.indexOfFirst { it == fromKey }
        val toIdx = list.indexOfFirst { it == toKey }
        if (fromIdx == -1 || toIdx == -1 || fromIdx == toIdx) return list
        val m = list.toMutableList()
        val item = m.removeAt(fromIdx)
        m.add(toIdx, item)
        return m
    }

    fun isMenuAvailableSomewhere(removingZone: String): Boolean {
        val inLeft = if (removingZone == "TOP_LEFT") 0 else topLeft.count { it == "menu" }
        val inRight = if (removingZone == "TOP_RIGHT") 0 else topBar.count { it == "menu" }
        val inBottom = if (removingZone == "BOTTOM") 0 else bottomBar.count { it == "menu" }
        return (inLeft + inRight + inBottom) > 0
    }

    val zoneTabs = listOf(
        Triple("TOP_LEFT", "Top Left", Color(0xFFBC8CFF)),
        Triple("TOP_RIGHT", "Top Right", Color(0xFF00E5FF)),
        Triple("BOTTOM", "Bottom", Color(0xFFD29922)),
        Triple("GRID", "Grid", Color(0xFF81C995))
    )

    val (currentItems, zoneMax, zoneAccent) = when (activeZone) {
        "TOP_LEFT" -> Triple(topLeft, 3, Color(0xFFBC8CFF))
        "TOP_RIGHT" -> Triple(topBar, 3, Color(0xFF00E5FF))
        "BOTTOM" -> Triple(bottomBar, 7, Color(0xFFD29922))
        else -> Triple(active, 99, Color(0xFF81C995))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF282C34),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(BrowserIcons.LayoutGrid, contentDescription = null, tint = Color(0xFF8AB4F8), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Customize Bars & Menu", color = Color(0xFFE8EAED), fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 4-Tab Zone Switcher
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF161B22))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    zoneTabs.forEach { (zId, zLabel, zCol) ->
                        val isSel = activeZone == zId
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) zCol.copy(alpha = 0.2f) else Color.Transparent,
                            border = if (isSel) BorderStroke(1.dp, zCol) else null,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { activeZone = zId }
                        ) {
                            Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = zLabel,
                                    color = if (isSel) zCol else Color(0xFF9AA0A6),
                                    fontSize = 10.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // Header + Always-Visible Add Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val countLabel = if (activeZone == "GRID") {
                        "HAMBURGER GRID (${currentItems.size} items)"
                    } else {
                        "DRAG TO REORDER (${currentItems.size}/$zoneMax)"
                    }
                    Text(countLabel, color = zoneAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = zoneAccent.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, zoneAccent.copy(alpha = 0.65f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                if (activeZone != "GRID" && currentItems.size >= zoneMax) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Max $zoneMax items here — remove one first",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    addPickerZone = activeZone
                                }
                            }
                    ) {
                        Text(
                            text = "+ Add",
                            color = zoneAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                // Drag-and-Drop Reorderable List for Selected Zone
                key(activeZone) {
                    val listState = rememberLazyListState()
                    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
                        when (activeZone) {
                            "TOP_LEFT" -> topLeft = reorderByKey(topLeft, from.key, to.key)
                            "TOP_RIGHT" -> topBar = reorderByKey(topBar, from.key, to.key)
                            "BOTTOM" -> bottomBar = reorderByKey(bottomBar, from.key, to.key)
                            "GRID" -> active = reorderByKey(active, from.key, to.key)
                        }
                    }

                    if (currentItems.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF161B22)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No items in this zone. Tap + Add above.", color = Color(0xFF8B949E), fontSize = 12.sp)
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            items(currentItems, key = { it }) { id ->
                                ReorderableItem(reorderableState, key = id) { isDragging ->
                                    val elevation by animateDpAsState(if (isDragging) 12.dp else 0.dp)
                                    val canRemove = id != "menu" || (activeZone != "GRID" && isMenuAvailableSomewhere(activeZone))

                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isDragging) Color(0xFF21262D) else Color(0xFF161B22),
                                        border = BorderStroke(
                                            if (isDragging) 1.5.dp else 1.dp,
                                            if (isDragging) zoneAccent else zoneAccent.copy(alpha = 0.35f)
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .graphicsLayer { shadowElevation = elevation.toPx() }
                                            .longPressDraggableHandle(
                                                onDragStarted = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                }
                                            )
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier
                                                        .size(28.dp)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .draggableHandle(
                                                            onDragStarted = {
                                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            }
                                                        )
                                                ) {
                                                    Icon(
                                                        BrowserIcons.DragHandle,
                                                        contentDescription = "Drag",
                                                        tint = if (isDragging) zoneAccent else Color(0xFF8B949E),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                                Spacer(Modifier.width(6.dp))
                                                Icon(
                                                    getBrowserIconForSlot(id),
                                                    contentDescription = null,
                                                    tint = zoneAccent,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    BROWSER_SLOT_LABEL_MAP[id] ?: id,
                                                    color = Color.White,
                                                    fontSize = 12.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            if (canRemove) {
                                                TextButton(
                                                    onClick = {
                                                        when (activeZone) {
                                                            "TOP_LEFT" -> topLeft = topLeft.filter { it != id }
                                                            "TOP_RIGHT" -> topBar = topBar.filter { it != id }
                                                            "BOTTOM" -> bottomBar = bottomBar.filter { it != id }
                                                            "GRID" -> {
                                                                active = active.filter { it != id }
                                                                if (id !in hidden) hidden = hidden + id
                                                            }
                                                        }
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 6.dp),
                                                    modifier = Modifier.height(24.dp)
                                                ) {
                                                    Text(
                                                        if (activeZone == "GRID") "Hide" else "Remove",
                                                        color = Color(0xFFF28B82),
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
                }

                // Hidden Items Strip inside Grid Tab
                if (activeZone == "GRID" && hidden.isNotEmpty()) {
                    HorizontalDivider(color = Color(0xFF3C4043))
                    Text("HIDDEN ITEMS (${hidden.size}) — Tap to restore", color = Color(0xFF9AA0A6), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 84.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            hidden.chunked(2).forEach { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    pair.forEach { hId ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color(0xFF161B22),
                                            border = BorderStroke(1.dp, Color(0xFF3C4043)),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    hidden = hidden.filter { it != hId }
                                                    if (hId !in active) active = active + hId
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    BROWSER_SLOT_LABEL_MAP[hId] ?: hId,
                                                    color = Color(0xFF9AA0A6),
                                                    fontSize = 10.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text("+ Show", color = Color(0xFF81C995), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(active, hidden, bottomBar, topBar, topLeft) },
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

    if (addPickerZone != null) {
        val zone = addPickerZone!!
        val (zoneTitle, currentList, accentColor) = when (zone) {
            "TOP_LEFT" -> Triple("Add to Top Left", topLeft, Color(0xFFBC8CFF))
            "TOP_RIGHT" -> Triple("Add to Top Right", topBar, Color(0xFF00E5FF))
            "BOTTOM" -> Triple("Add to Bottom Bar", bottomBar, Color(0xFFD29922))
            else -> Triple("Add to Hamburger Grid", active, Color(0xFF81C995))
        }

        AlertDialog(
            onDismissRequest = { addPickerZone = null },
            containerColor = Color(0xFF1E222A),
            title = {
                Text(zoneTitle, color = accentColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val candidates = ALL_BROWSER_SLOT_LABELS.filter { (id, _) ->
                        zone != "GRID" || id != "menu"
                    }
                    items(candidates.size) { idx ->
                        val (slotId, slotLabel) = candidates[idx]
                        val alreadyInZone = slotId in currentList
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (alreadyInZone) accentColor.copy(alpha = 0.14f) else Color(0xFF161B22),
                            border = BorderStroke(1.dp, if (alreadyInZone) accentColor else Color(0xFF30363D)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    when (zone) {
                                        "TOP_LEFT" -> if (!alreadyInZone && topLeft.size < 3) topLeft = topLeft + slotId
                                        "TOP_RIGHT" -> if (!alreadyInZone && topBar.size < 3) topBar = topBar + slotId
                                        "BOTTOM" -> if (!alreadyInZone && bottomBar.size < 7) bottomBar = bottomBar + slotId
                                        "GRID" -> {
                                            if (!alreadyInZone) {
                                                active = active + slotId
                                                hidden = hidden.filter { it != slotId }
                                            }
                                        }
                                    }
                                    addPickerZone = null
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        getBrowserIconForSlot(slotId),
                                        contentDescription = null,
                                        tint = if (alreadyInZone) accentColor else Color(0xFFE8EAED),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        slotLabel,
                                        color = if (alreadyInZone) accentColor else Color(0xFFE8EAED),
                                        fontSize = 12.sp,
                                        fontWeight = if (alreadyInZone) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                                Text(
                                    if (alreadyInZone) "Added" else "+ Add",
                                    color = if (alreadyInZone) accentColor else Color(0xFF81C995),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { addPickerZone = null }) {
                    Text("Close", color = Color(0xFF9AA0A6))
                }
            }
        )
    }
}
@Composable
fun AddShortcutDialog(
    bridge: HostBridge,
    faviconCache: Map<String, Bitmap>,
    onFetchFavicon: (String) -> Unit,
    onPickFile: ((String) -> Unit) -> Unit,
    onReadLocalCode: (String) -> Result<String>,
    onAdd: (String, String, Boolean, Boolean, String?) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("https://") }
    var isDefault by remember { mutableStateOf(false) }
    var isGlobalProfile by remember { mutableStateOf(true) }
    var draftEditedCode by remember { mutableStateOf<String?>(null) }
    var editorInitialCode by remember { mutableStateOf<String?>(null) }
    var isLoadingCode by remember { mutableStateOf(false) }
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
                    onValueChange = {
                        newUrl = it
                        draftEditedCode = null
                    },
                    label = { Text("URL or File Path") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            onPickFile { directPath ->
                                newUrl = directPath
                                draftEditedCode = null
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
                    OutlinedButton(
                        onClick = {
                            if (draftEditedCode != null) {
                                editorInitialCode = draftEditedCode
                            } else {
                                isLoadingCode = true
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) { onReadLocalCode(newUrl) }
                                    isLoadingCode = false
                                    res.onSuccess { txt ->
                                        editorInitialCode = txt
                                    }.onFailure { err ->
                                        bridge.showToast("⚠️ ${err.message ?: "Could not load code"}")
                                        editorInitialCode = ""
                                    }
                                }
                            }
                        },
                        enabled = !isLoadingCode,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                        border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.65f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLoadingCode) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF00E5FF))
                            Spacer(Modifier.width(8.dp))
                            Text("Loading Source...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Text(
                                if (draftEditedCode != null) "💻 Edit App Code (Modified)" else "💻 View / Edit App Code",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
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
            Button(
                onClick = { onAdd(newName, newUrl, isDefault, isGlobalProfile, draftEditedCode) },
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

    if (editorInitialCode != null) {
        LocalShortcutCodeEditorDialog(
            bridge = bridge,
            appTitle = newName.ifBlank { "Local App" },
            initialCode = editorInitialCode!!,
            onSaveCode = { updated ->
                draftEditedCode = updated
                editorInitialCode = null
                bridge.showToast("✅ Code ready — tap Add to save")
            },
            onDismiss = { editorInitialCode = null }
        )
    }
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