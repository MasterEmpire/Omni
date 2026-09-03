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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
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
import com.omni.plugin.browser.utils.extractDomain
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

private fun getFileEmoji(filename: String): String {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "zip", "tar", "gz", "rar", "7z" -> "📦"
        "html", "htm", "js", "ts", "json", "kt", "java", "py", "css" -> "💻"
        "png", "jpg", "jpeg", "webp", "gif", "svg" -> "🖼️"
        "mp4", "mkv", "webm", "mov" -> "🎬"
        "mp3", "wav", "m4a", "flac" -> "🎵"
        "pdf", "doc", "docx", "txt", "md" -> "📄"
        "apk" -> "🤖"
        else -> "📁"
    }
}

private fun resolveMimeType(file: File): String {
    val ext = file.extension.lowercase(Locale.US)
    val defaultMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    if (!defaultMime.isNullOrEmpty()) return defaultMime

    return when (ext) {
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "txt", "log", "md" -> "text/plain"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4", "mkv", "webm" -> "video/*"
        "mp3", "wav", "m4a", "flac" -> "audio/*"
        else -> "*/*"
    }
}

private fun getShareableUri(context: Context, file: File): Uri {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(file.name)
            val queryUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

            context.contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    return android.content.ContentUris.withAppendedId(queryUri, id)
                }
            }

            val filesUri = MediaStore.Files.getContentUri("external")
            context.contentResolver.query(filesUri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    return android.content.ContentUris.withAppendedId(filesUri, id)
                }
            }
        }
    } catch (_: Exception) {}

    try {
        val fileProviderClass = Class.forName("androidx.core.content.FileProvider")
        val getUriMethod = fileProviderClass.getMethod(
            "getUriForFile",
            Context::class.java,
            String::class.java,
            File::class.java
        )
        val uri = getUriMethod.invoke(null, context, "${context.packageName}.fileprovider", file) as? Uri
        if (uri != null) return uri
    } catch (_: Exception) {}

    try {
        val builder = android.os.StrictMode.VmPolicy.Builder()
        android.os.StrictMode.setVmPolicy(builder.build())
    } catch (_: Exception) {}

    return Uri.fromFile(file)
}

@Composable
fun DownloadsManagerDialog(
    bridge: HostBridge,
    activeDownloadsList: List<ActiveDownloadItem>,
    completedFilesList: List<File>,
    onCancelDownload: (Long) -> Unit,
    onDeleteFile: (File) -> Unit,
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
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onSolveNow: () -> Unit,
    onClearCookiesAndCache: () -> Unit,
    onSave: (String, Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var tempKey by remember { mutableStateOf(apiKey) }
    var tempAuto by remember { mutableStateOf(autoSolve) }
    var tempForceDark by remember { mutableStateOf(forceDark) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF282C34),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚙️", fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Text("Settings & Backup", color = Color(0xFFE8EAED), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Session & Profile Backup Vault", color = Color(0xFF8AB4F8), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Backs up all 10 profiles, tabs, custom shortcuts, solver keys, and local IDE vaults. Auto-mirrored to Documents/.omni_vault/.", color = Color(0xFF9AA0A6), fontSize = 11.sp)

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
                onClick = { onSave(tempKey.trim(), tempAuto, tempForceDark) },
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
    onSave: (String, String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var editName by remember(shortcut) { mutableStateOf(shortcut.title) }
    var editUrl by remember(shortcut) { mutableStateOf(shortcut.localSourcePath ?: shortcut.url) }
    var isDefault by remember(shortcut) { mutableStateOf(shortcut.isDefault) }
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

                if (com.omni.plugin.browser.utils.isLocalFilePath(editUrl)) {
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
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDelete) {
                    Text("Delete", color = Color(0xFFF28B82), fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { onSave(editName, editUrl, isDefault) },
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
fun AddShortcutDialog(
    faviconCache: Map<String, Bitmap>,
    onFetchFavicon: (String) -> Unit,
    onPickFile: ((String) -> Unit) -> Unit,
    onAdd: (String, String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("https://") }
    var isDefault by remember { mutableStateOf(false) }
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
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(newName, newUrl, isDefault) },
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