package com.omni.plugin.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import java.io.File
import java.io.FileOutputStream
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.ProfileStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.omni.hub.api.HostBridge
import com.omni.hub.api.PluginEntry
import com.omni.plugin.browser.models.*
import com.omni.plugin.browser.utils.*
import com.omni.plugin.browser.ui.TabSwitcherScreen
import com.omni.plugin.browser.ui.dialogs.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OmniBrowser : PluginEntry() {

    override fun onCreateView(context: Context, bridge: HostBridge, baseDir: String): View {
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        background = Color(0xFF1F2227),
                        surface = Color(0xFF282C34),
                        primary = Color(0xFF8AB4F8),
                        onSurface = Color(0xFFE8EAED)
                    )
                ) {
                    ChromeBrowserScreen(bridge)
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun ChromeBrowserScreen(bridge: HostBridge) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val focusManager = LocalFocusManager.current
        val coroutineScope = rememberCoroutineScope()
        val MAX_HOT_TABS = 8

        // --- All State Definitions Hoisted to Top ---
        val ideInternalPath = remember { "file://${bridge.getPluginDir()}/ide/index.html" }

        val defaultShortcuts = remember {
            listOf(
                ShortcutItem(title = "Local IDE", url = ideInternalPath, iconText = "💻", colorValue = 0xFF58A6FF, localSourcePath = "/storage/emulated/0/Download/F/index.html"),
                ShortcutItem(title = "Google", url = "https://www.google.com", iconText = "G", colorValue = 0xFF4285F4),
                ShortcutItem(title = "YouTube", url = "https://m.youtube.com", iconText = "▶", colorValue = 0xFFEA4335),
                ShortcutItem(title = "Bot Test", url = "https://bot.sannysoft.com/", iconText = "🕵️", colorValue = 0xFF34A853),
                ShortcutItem(title = "GitHub", url = "https://github.com", iconText = "⌥", colorValue = 0xFF24292E),
                ShortcutItem(title = "Reddit", url = "https://reddit.com", iconText = "R", colorValue = 0xFFFF4500),
                ShortcutItem(title = "DuckDuckGo", url = "https://duckduckgo.com", iconText = "D", colorValue = 0xFFDE5833),
                ShortcutItem(title = "Wikipedia", url = "https://wikipedia.org", iconText = "W", colorValue = 0xFF5F6368),
                ShortcutItem(title = "BrowserLeaks", url = "https://browserleaks.com/javascript", iconText = "🔍", colorValue = 0xFF9C27B0)
            )
        }

        var shortcuts by remember { mutableStateOf(defaultShortcuts) }
        val faviconCache = remember { mutableStateMapOf<String, Bitmap>() }

        var profiles by remember {
            mutableStateOf(
                listOf(
                    BrowserProfile("default", "Default", 0xFF8AB4F8)
                )
            )
        }
        var selectedProfileId by remember { mutableStateOf("default") }
        var editingProfile by remember { mutableStateOf<BrowserProfile?>(null) }
        var editingShortcut by remember { mutableStateOf<ShortcutItem?>(null) }
        var isAddingShortcut by remember { mutableStateOf(false) }

        var tabs by remember {
            mutableStateOf(
                listOf(
                    BrowserTab(
                        id = "tab_1",
                        title = "New Tab",
                        url = "about:blank",
                        profileId = "default"
                    )
                )
            )
        }
        var activeTabId by remember { mutableStateOf("tab_1") }
        var isTabSwitcherOpen by remember { mutableStateOf(false) }
        var isHomeOverlayOpen by remember { mutableStateOf(false) }

        var currentUrl by remember { mutableStateOf("about:blank") }
        var urlInputText by remember { mutableStateOf("") }
        var pageTitle by remember { mutableStateOf("New Tab") }
        var isLoading by remember { mutableStateOf(false) }
        var loadProgress by remember { mutableFloatStateOf(0f) }
        var canGoBack by remember { mutableStateOf(false) }
        var canGoForward by remember { mutableStateOf(false) }
        var isDesktopMode by remember { mutableStateOf(false) }
        var showMenu by remember { mutableStateOf(false) }

        val webViewPool = remember { mutableMapOf<String, WebView>() }
        val pendingPurgeWebViews = remember { mutableMapOf<String, WebView>() }
        var containerLayout: FrameLayout? by remember { mutableStateOf(null) }
        var webViewInstance: WebView? by remember { mutableStateOf(null) }

        fun purgePendingWebViews() {
            pendingPurgeWebViews.forEach { (_, wv) ->
                try {
                    wv.stopLoading()
                    wv.onPause()
                    containerLayout?.removeView(wv)
                    wv.destroy()
                } catch (_: Exception) {}
            }
            pendingPurgeWebViews.clear()
        }

        var mobileUA by remember { mutableStateOf("") }
        var showSettingsDialog by remember { mutableStateOf(false) }
        var showDownloadsDialog by remember { mutableStateOf(false) }
        val trackedDownloadIds = remember { mutableStateListOf<Long>() }
        var activeDownloadsList by remember { mutableStateOf<List<ActiveDownloadItem>>(emptyList()) }
        var completedFilesList by remember { mutableStateOf<List<File>>(emptyList()) }

        var solverApiKey by remember { mutableStateOf("") }
        var autoSolveEnabled by remember { mutableStateOf(true) }
        var isSolvingCaptcha by remember { mutableStateOf(false) }

        // --- AI Studio Automator State ---
        var showAutomationDialog by remember { mutableStateOf(false) }
        var showAutomationResultDialog by remember { mutableStateOf(false) }
        var autoSelectedProfileId by remember { mutableStateOf("default") }
        var autoSelectedModel by remember { mutableStateOf("Gemini 3.7 Flash") }
        var autoThinkingLevel by remember { mutableStateOf("High") }
        var autoSystemPrompt by remember { mutableStateOf("") }
        var autoUserPrompt by remember { mutableStateOf("") }
        var isAutomating by remember { mutableStateOf(false) }
        var automationStatus by remember { mutableStateOf("Idle") }
        var automationThoughts by remember { mutableStateOf("") }
        var automationResult by remember { mutableStateOf("") }
        var automationError by remember { mutableStateOf<String?>(null) }
        var automationElapsedSec by remember { mutableIntStateOf(0) }
        var headlessAutomationWv by remember { mutableStateOf<WebView?>(null) }

        var lastClosedTabsSnapshot by remember { mutableStateOf<List<BrowserTab>?>(null) }
        var lastActiveTabIdSnapshot by remember { mutableStateOf<String?>(null) }
        var undoMessage by remember { mutableStateOf("") }
        var showUndoBanner by remember { mutableStateOf(false) }
        var undoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

        fun refreshCompletedDownloads() {
            try {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OmniDownloads")
                if (dir.exists() && dir.isDirectory) {
                    completedFilesList = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
                } else {
                    completedFilesList = emptyList()
                }
            } catch (_: Exception) {
                completedFilesList = emptyList()
            }
        }

        LaunchedEffect(trackedDownloadIds.size, showDownloadsDialog) {
            while (trackedDownloadIds.isNotEmpty() || showDownloadsDialog) {
                if (trackedDownloadIds.isNotEmpty()) {
                    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                    if (dm != null) {
                        try {
                            val query = DownloadManager.Query().setFilterById(*trackedDownloadIds.toLongArray())
                            val cursor = dm.query(query)
                            val updated = mutableListOf<ActiveDownloadItem>()
                            val finishedIds = mutableListOf<Long>()

                            if (cursor != null && cursor.moveToFirst()) {
                                val idCol = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                                val titleCol = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                                val bytesCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                                val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                                val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)

                                do {
                                    val dId = if (idCol >= 0) cursor.getLong(idCol) else -1L
                                    val title = if (titleCol >= 0) cursor.getString(titleCol) ?: "Download" else "Download"
                                    val bytes = if (bytesCol >= 0) cursor.getLong(bytesCol) else 0L
                                    val total = if (totalCol >= 0) cursor.getLong(totalCol) else -1L
                                    val status = if (statusCol >= 0) cursor.getInt(statusCol) else DownloadManager.STATUS_RUNNING
                                    val prog = if (total > 0) (bytes.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

                                    if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                                        finishedIds.add(dId)
                                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                            refreshCompletedDownloads()
                                        }
                                    } else {
                                        updated.add(ActiveDownloadItem(dId, title, bytes, total, prog, status))
                                    }
                                } while (cursor.moveToNext())
                                cursor.close()
                            }
                            activeDownloadsList = updated
                            trackedDownloadIds.removeAll(finishedIds)
                        } catch (_: Exception) {}
                    }
                }
                if (showDownloadsDialog) {
                    refreshCompletedDownloads()
                }
                delay(1000)
            }
        }

        var restoreTrigger by remember { mutableStateOf<((Uri) -> Unit)?>(null) }
        val backupPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri != null) {
                restoreTrigger?.invoke(uri)
            }
        }

        var activeFileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
        val fileChooserLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            activeFileChooserCallback?.onReceiveValue(uris)
            activeFileChooserCallback = null
        }

        val desktopUA = remember { DESKTOP_USER_AGENT }
        val botBypassPolyfill = remember { BOT_BYPASS_POLYFILL }
        val blobInterceptorScript = remember { BLOB_INTERCEPTOR_SCRIPT }

        var createNewTabHandler: ((String) -> Unit)? = null
        var attachTabWebViewHandler: ((String) -> Unit)? = null

        fun syncLocalFileToVault(sourcePath: String, targetSubPath: String = "ide/index.html"): Pair<Boolean, String> {
            val cleanInput = sourcePath.trim()
            bridge.log("IDE_SYNC", "Attempting sync for source: $cleanInput")

            try {
                var bytes: ByteArray? = null
                if (cleanInput.startsWith("content://")) {
                    val uri = Uri.parse(cleanInput)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        bytes = input.readBytes()
                    }
                } else {
                    val cleanPath = normalizeLocalFilePath(cleanInput)
                    val srcFile = File(cleanPath)
                    if (srcFile.exists() && srcFile.isFile) {
                        bytes = srcFile.readBytes()
                    } else {
                        try {
                            val uri = Uri.parse(if (cleanInput.startsWith("file://")) cleanInput else "file://$cleanPath")
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                bytes = input.readBytes()
                            }
                        } catch (_: Exception) {}
                    }
                    if (bytes == null && !srcFile.exists()) {
                        val errMsg = "File not found at: $cleanPath"
                        bridge.log("IDE_SYNC_ERR", errMsg)
                        return Pair(false, errMsg)
                    }
                }

                if (bytes != null && bytes!!.isNotEmpty()) {
                    val savedAbsPath = bridge.saveFile(targetSubPath, bytes!!)
                    val kb = String.format(Locale.US, "%.1f", bytes!!.size / 1024.0)
                    bridge.log("IDE_SYNC", "✅ Vaulted $cleanInput -> $savedAbsPath ($kb KB)")
                    return Pair(true, "file://$savedAbsPath")
                } else {
                    val errMsg = "File is empty or unreadable"
                    bridge.log("IDE_SYNC_ERR", "$errMsg: $cleanInput")
                    return Pair(false, errMsg)
                }
            } catch (e: Exception) {
                val errMsg = "Sync error: ${e.message}"
                bridge.log("IDE_SYNC_ERR", "$errMsg on $cleanInput")
                return Pair(false, errMsg)
            }
        }

        fun autoMirrorVaultToDocuments() {
            try {
                val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val vaultDir = File(docsDir, ".omni_vault").apply { mkdirs() }
                
                fun mirrorFile(relPath: String, outName: String) {
                    val src = File(bridge.getPluginDir(), relPath)
                    if (src.exists() && src.isFile) {
                        src.copyTo(File(vaultDir, outName), overwrite = true)
                    }
                }
                mirrorFile("config/profiles.json", "profiles.json")
                mirrorFile("config/shortcuts.json", "shortcuts.json")
                mirrorFile("config/session.json", "session.json")
                mirrorFile("config/solver.json", "solver.json")

                // Mirror all isolated local IDE vaults
                val ideDir = File(bridge.getPluginDir(), "ide")
                if (ideDir.exists() && ideDir.isDirectory) {
                    val destIdeDir = File(vaultDir, "ide").apply { mkdirs() }
                    ideDir.copyRecursively(destIdeDir, overwrite = true)
                }
            } catch (_: Exception) {}
        }

        fun saveShortcutsToDisk(list: List<ShortcutItem>) {
            try {
                val arr = org.json.JSONArray()
                list.forEach { s ->
                    val obj = org.json.JSONObject().apply {
                        put("id", s.id)
                        put("title", s.title)
                        put("url", s.url)
                        put("iconText", s.iconText)
                        put("colorValue", s.colorValue)
                        if (!s.localSourcePath.isNullOrEmpty()) {
                            put("localSourcePath", s.localSourcePath)
                        }
                    }
                    arr.put(obj)
                }
                bridge.saveFile("config/shortcuts.json", arr.toString().toByteArray(Charsets.UTF_8))
                autoMirrorVaultToDocuments()
            } catch (_: Exception) {}
        }

        fun saveProfilesToDisk(list: List<BrowserProfile>) {
            try {
                val arr = org.json.JSONArray()
                list.forEach { p ->
                    val obj = org.json.JSONObject().apply {
                        put("id", p.id)
                        put("name", p.name)
                        put("colorValue", p.colorValue)
                    }
                    arr.put(obj)
                }
                bridge.saveFile("config/profiles.json", arr.toString().toByteArray(Charsets.UTF_8))
                autoMirrorVaultToDocuments()
            } catch (_: Exception) {}
        }

        fun saveSessionToDisk(tabList: List<BrowserTab>, currentActiveId: String) {
            try {
                val json = org.json.JSONObject()
                json.put("activeTabId", currentActiveId)
                val arr = org.json.JSONArray()
                tabList.forEach { tab ->
                    val tObj = org.json.JSONObject().apply {
                        put("id", tab.id)
                        put("title", tab.title)
                        put("url", tab.url)
                        put("profileId", tab.profileId)
                        put("lastAccessedTime", tab.lastAccessedTime)
                    }
                    arr.put(tObj)
                }
                json.put("tabs", arr)
                bridge.saveFile("config/session.json", json.toString().toByteArray(Charsets.UTF_8))
                autoMirrorVaultToDocuments()
            } catch (_: Exception) {}
        }

        fun exportFullBackup() {
            try {
                CookieManager.getInstance().flush()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val backupFilename = "OmniBrowser_Backup_$timestamp.zip"
                val tempZipFile = File(context.cacheDir, backupFilename)

                java.util.zip.ZipOutputStream(FileOutputStream(tempZipFile)).use { zos ->
                    fun addFileToZip(relativePath: String, entryName: String) {
                        val file = File(bridge.getPluginDir(), relativePath)
                        if (file.exists() && file.isFile) {
                            zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }

                    addFileToZip("config/profiles.json", "profiles.json")
                    addFileToZip("config/shortcuts.json", "shortcuts.json")
                    addFileToZip("config/session.json", "session.json")
                    addFileToZip("config/solver.json", "solver.json")

                    // Recursively archive all multi-tenant local IDE vaults
                    val ideDir = File(bridge.getPluginDir(), "ide")
                    if (ideDir.exists() && ideDir.isDirectory) {
                        ideDir.walkTopDown().filter { it.isFile }.forEach { f ->
                            val relPath = "ide/" + f.relativeTo(ideDir).path.replace("\\", "/")
                            zos.putNextEntry(java.util.zip.ZipEntry(relPath))
                            f.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }

                val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val targetDir = File(docsDir, "OmniBackups").apply { mkdirs() }
                val targetFile = File(targetDir, backupFilename)
                tempZipFile.copyTo(targetFile, overwrite = true)
                tempZipFile.delete()

                bridge.log("BACKUP", "Created backup: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                bridge.showToast("✅ Backup saved to Documents/OmniBackups/$backupFilename")

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, Uri.parse("file://${targetFile.absolutePath}"))
                    putExtra(Intent.EXTRA_SUBJECT, "Omni Chrome Backup ($timestamp)")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(Intent.createChooser(shareIntent, "Share Omni Chrome Backup").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (_: Exception) {}

            } catch (e: Exception) {
                bridge.showToast("Backup failed: ${e.message}")
                bridge.log("BACKUP_ERR", "Export error: ${e.message}")
            }
        }

        fun restoreFromBackup(uri: Uri) {
            try {
                val tempFile = File(context.cacheDir, "import_${System.currentTimeMillis()}.zip")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException("Cannot read backup file.")

                var profCount = 0
                var tabCount = 0

                java.util.zip.ZipInputStream(tempFile.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val bytes = zis.readBytes()
                        if (entry.name.startsWith("ide/")) {
                            bridge.saveFile(entry.name, bytes)
                        } else {
                            when (entry.name) {
                                "profiles.json" -> {
                                    bridge.saveFile("config/profiles.json", bytes)
                                    val arr = org.json.JSONArray(String(bytes, Charsets.UTF_8))
                                    val loaded = mutableListOf<BrowserProfile>()
                                    for (i in 0 until arr.length()) {
                                        val obj = arr.getJSONObject(i)
                                        loaded.add(BrowserProfile(obj.getString("id"), obj.getString("name"), obj.getLong("colorValue")))
                                    }
                                    if (loaded.isNotEmpty()) {
                                        profiles = loaded
                                        profCount = loaded.size
                                    }
                                }
                                "shortcuts.json" -> {
                                    bridge.saveFile("config/shortcuts.json", bytes)
                                    val arr = org.json.JSONArray(String(bytes, Charsets.UTF_8))
                                    val loaded = mutableListOf<ShortcutItem>()
                                    for (i in 0 until arr.length()) {
                                        val obj = arr.getJSONObject(i)
                                        loaded.add(
                                            ShortcutItem(
                                                id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                                                title = obj.getString("title"),
                                                url = obj.getString("url"),
                                                iconText = obj.optString("iconText", ""),
                                                colorValue = obj.optLong("colorValue", 0xFF4285F4),
                                                localSourcePath = obj.optString("localSourcePath", null).takeIf { !it.isNullOrEmpty() }
                                            )
                                        )
                                    }
                                    if (loaded.isNotEmpty()) shortcuts = loaded
                                }
                                "session.json" -> {
                                    bridge.saveFile("config/session.json", bytes)
                                    val sObj = org.json.JSONObject(String(bytes, Charsets.UTF_8))
                                    val savedActiveId = sObj.optString("activeTabId", "")
                                    val arr = sObj.optJSONArray("tabs")
                                    if (arr != null && arr.length() > 0) {
                                        val loadedTabs = mutableListOf<BrowserTab>()
                                        for (i in 0 until arr.length()) {
                                            val tObj = arr.getJSONObject(i)
                                            loadedTabs.add(
                                                BrowserTab(
                                                    id = tObj.getString("id"),
                                                    title = tObj.optString("title", "New Tab"),
                                                    url = tObj.optString("url", "about:blank"),
                                                    lastAccessedTime = tObj.optLong("lastAccessedTime", System.currentTimeMillis()),
                                                    profileId = tObj.optString("profileId", "default")
                                                )
                                            )
                                        }
                                        if (loadedTabs.isNotEmpty()) {
                                            tabs = loadedTabs
                                            tabCount = loadedTabs.size
                                            val targetId = if (savedActiveId.isNotEmpty() && loadedTabs.any { it.id == savedActiveId }) savedActiveId else loadedTabs.first().id
                                            activeTabId = targetId
                                            attachTabWebViewHandler?.invoke(targetId)
                                        }
                                    }
                                }
                                "solver.json" -> {
                                    bridge.saveFile("config/solver.json", bytes)
                                    val json = org.json.JSONObject(String(bytes, Charsets.UTF_8))
                                    solverApiKey = json.optString("apiKey", "")
                                    autoSolveEnabled = json.optBoolean("autoSolve", true)
                                }
                                "ide_index.html" -> {
                                    bridge.saveFile("ide/index.html", bytes)
                                }
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
                tempFile.delete()
                autoMirrorVaultToDocuments()
                bridge.showToast("✅ Restored $profCount profiles, $tabCount tabs & isolated IDEs!")
            } catch (e: Exception) {
                bridge.showToast("Restore failed: ${e.message}")
                bridge.log("RESTORE_ERR", "Restore error: ${e.message}")
            }
        }

        restoreTrigger = { uri -> restoreFromBackup(uri) }

        fun saveBase64ToDownloads(base64Data: String, mimeType: String, rawFilename: String) {
            bridge.log("DOWNLOAD", "Processing Base64 payload (mime: '$mimeType', rawFilename: '$rawFilename', len: ${base64Data.length})")
            try {
                val cleanBase64 = if (base64Data.contains(",")) base64Data.substringAfter(",") else base64Data
                val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                bridge.log("DOWNLOAD", "Decoded ${bytes.size} bytes from payload.")

                val ext = when {
                    mimeType.contains("pdf") -> "pdf"
                    mimeType.contains("png") -> "png"
                    mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
                    mimeType.contains("zip") -> "zip"
                    mimeType.contains("json") -> "json"
                    mimeType.contains("html") -> "html"
                    mimeType.contains("javascript") || mimeType.contains("js") -> "js"
                    mimeType.contains("css") -> "css"
                    mimeType.contains("text") -> "txt"
                    else -> "bin"
                }

                var filename = rawFilename.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (filename.isEmpty() || filename == "null" || filename == "blob" || filename == "download") {
                    filename = "download_${System.currentTimeMillis()}.$ext"
                } else if (!filename.contains(".")) {
                    filename = "$filename.$ext"
                }

                var saved = false
                var savedLocation = ""

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                            put(MediaStore.MediaColumns.MIME_TYPE, mimeType.ifEmpty { "application/octet-stream" })
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OmniDownloads")
                        }
                        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri)?.use { os ->
                                os.write(bytes)
                            }
                            saved = true
                            savedLocation = "Downloads/OmniDownloads/$filename (MediaStore)"
                            bridge.log("DOWNLOAD", "Saved via MediaStore -> $uri ($savedLocation)")
                        } else {
                            bridge.log("DOWNLOAD_WARN", "MediaStore returned null URI. Falling back to direct filesystem write.")
                        }
                    } catch (e: Exception) {
                        bridge.log("DOWNLOAD_WARN", "MediaStore insert failed: ${e.message}. Falling back to direct File write.")
                    }
                }

                // Fallback / Direct File Writing
                if (!saved) {
                    val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val targetDir = File(publicDocs, "OmniDownloads").apply { if (!exists()) mkdirs() }
                    val targetFile = File(targetDir, filename)
                    FileOutputStream(targetFile).use { it.write(bytes) }
                    saved = true
                    savedLocation = targetFile.absolutePath
                    bridge.log("DOWNLOAD", "Saved directly to File -> $savedLocation (${targetFile.length()} bytes)")
                }

                bridge.showToast("Saved $filename to Downloads/OmniDownloads")
                bridge.log("DOWNLOAD_SUCCESS", "✅ Download complete: $filename (${bytes.size} bytes)")
                refreshCompletedDownloads()
            } catch (e: Exception) {
                bridge.showToast("Blob save failed: ${e.message}")
                bridge.log("DOWNLOAD_ERR", "Failed saving base64 file: ${e.message}\n${e.stackTraceToString()}")
            }
        }

        fun handleExternalUri(url: String, view: WebView?): Boolean {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                if (url.contains("play.google.com/store/apps/details")) {
                    val uri = Uri.parse(url)
                    val pkg = uri.getQueryParameter("id")
                    if (!pkg.isNullOrEmpty()) {
                        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                            setPackage("com.android.vending")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        return try {
                            context.startActivity(marketIntent)
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }
                }
                return false
            }

            if (url.startsWith("intent://")) {
                return try {
                    val parsedIntent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val resolveInfo = context.packageManager.resolveActivity(parsedIntent, 0)
                    if (resolveInfo != null) {
                        context.startActivity(parsedIntent)
                        true
                    } else {
                        val fallbackUrl = parsedIntent.getStringExtra("browser_fallback_url")
                        if (!fallbackUrl.isNullOrEmpty()) {
                            view?.loadUrl(fallbackUrl)
                            true
                        } else {
                            val pkg = parsedIntent.`package`
                            if (!pkg.isNullOrEmpty()) {
                                val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(marketIntent)
                                true
                            } else false
                        }
                    }
                } catch (e: Exception) {
                    bridge.log("INTENT_ERR", "Could not dispatch intent URI: ${e.message}")
                    false
                }
            }

            if (url.startsWith("market://")) {
                return try {
                    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(marketIntent)
                    true
                } catch (e: Exception) {
                    val pkg = Uri.parse(url).getQueryParameter("id")
                    if (!pkg.isNullOrEmpty()) {
                        view?.loadUrl("https://play.google.com/store/apps/details?id=$pkg")
                        true
                    } else false
                }
            }

            return try {
                val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(genericIntent)
                true
            } catch (e: Exception) {
                bridge.log("INTENT_WARN", "No handler for scheme: $url")
                false
            }
        }

        fun triggerFileDownload(view: WebView?, url: String, userAgent: String, contentDisposition: String, mimeType: String) {
            bridge.log("DOWNLOAD", "triggerFileDownload invoked for: ${if (url.length > 40) url.take(40) + "..." else url} (mime: '$mimeType')")

            if (url.startsWith("blob:") || url.startsWith("data:")) {
                val suggestedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                if (url.startsWith("data:")) {
                    saveBase64ToDownloads(url, mimeType, suggestedName)
                } else {
                    val safeName = suggestedName.replace("'", "\\'").replace("\"", "\\\"")
                    val safeMime = mimeType.replace("'", "\\'").replace("\"", "\\\"")
                    val blobScript = buildBlobExtractionScript(url, safeName, safeMime)
                    val targetView = view ?: webViewInstance
                    targetView?.evaluateJavascript(blobScript, null)
                    bridge.showToast("Extracting Blob download...")
                }
                return
            }

            try {
                val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    if (mimeType.isNotEmpty() && mimeType != "application/octet-stream") {
                        setMimeType(mimeType)
                    }
                    addRequestHeader("User-Agent", userAgent)
                    val cookie = CookieManager.getInstance().getCookie(url)
                    if (!cookie.isNullOrEmpty()) {
                        addRequestHeader("Cookie", cookie)
                    }
                    setDescription("Downloading file...")
                    setTitle(filename)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                }

                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                val dlId = dm?.enqueue(request)
                if (dlId != null) {
                    trackedDownloadIds.add(dlId)
                }
                bridge.showToast("Downloading $filename...")
                bridge.log("DOWNLOAD", "Queued system download for $url ($filename) [ID: $dlId]")
            } catch (e: Exception) {
                bridge.showToast("Download error: ${e.message}")
                bridge.log("DOWNLOAD_ERR", "Download queue exception: ${e.message}")
            }
        }

        // Load persisted solver and profile configurations (with Auto-Vault Resurrection)
        LaunchedEffect(Unit) {
            try {
                val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val vaultDir = File(docsDir, ".omni_vault")
                if (vaultDir.exists()) {
                    fun restoreIfMissing(relPath: String, vaultName: String) {
                        if (bridge.readFile(relPath) == null) {
                            val vFile = File(vaultDir, vaultName)
                            if (vFile.exists() && vFile.isFile) {
                                bridge.saveFile(relPath, vFile.readBytes())
                            }
                        }
                    }
                    restoreIfMissing("config/profiles.json", "profiles.json")
                    restoreIfMissing("config/shortcuts.json", "shortcuts.json")
                    restoreIfMissing("config/session.json", "session.json")
                    restoreIfMissing("config/solver.json", "solver.json")

                    // Resurrect multi-tenant IDE vaults from Documents
                    val vaultIdeDir = File(vaultDir, "ide")
                    if (vaultIdeDir.exists() && vaultIdeDir.isDirectory) {
                        vaultIdeDir.walkTopDown().filter { it.isFile }.forEach { f ->
                            val relPath = "ide/" + f.relativeTo(vaultIdeDir).path.replace("\\", "/")
                            if (bridge.readFile(relPath) == null) {
                                bridge.saveFile(relPath, f.readBytes())
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            try {
                val savedBytes = bridge.readFile("config/solver.json")
                if (savedBytes != null) {
                    val json = org.json.JSONObject(String(savedBytes, Charsets.UTF_8))
                    solverApiKey = json.optString("apiKey", "")
                    autoSolveEnabled = json.optBoolean("autoSolve", true)
                }
            } catch (_: Exception) {}

            try {
                val scBytes = bridge.readFile("config/shortcuts.json")
                if (scBytes != null) {
                    val arr = org.json.JSONArray(String(scBytes, Charsets.UTF_8))
                    val loadedSc = mutableListOf<ShortcutItem>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        loadedSc.add(
                            ShortcutItem(
                                id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                                title = obj.getString("title"),
                                url = obj.getString("url"),
                                iconText = obj.optString("iconText", ""),
                                colorValue = obj.optLong("colorValue", 0xFF4285F4),
                                localSourcePath = obj.optString("localSourcePath", null).takeIf { !it.isNullOrEmpty() }
                            )
                        )
                    }
                    if (loadedSc.isNotEmpty()) {
                        shortcuts = loadedSc
                    }
                }
            } catch (_: Exception) {}

            try {
                val profBytes = bridge.readFile("config/profiles.json")
                if (profBytes != null) {
                    val arr = org.json.JSONArray(String(profBytes, Charsets.UTF_8))
                    val loaded = mutableListOf<BrowserProfile>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        loaded.add(BrowserProfile(obj.getString("id"), obj.getString("name"), obj.getLong("colorValue")))
                    }
                    if (loaded.isNotEmpty()) {
                        profiles = loaded
                    }
                }
            } catch (_: Exception) {}

            try {
                val sessionBytes = bridge.readFile("config/session.json")
                if (sessionBytes != null) {
                    val sObj = org.json.JSONObject(String(sessionBytes, Charsets.UTF_8))
                    val arr = sObj.optJSONArray("tabs")
                    if (arr != null && arr.length() > 0) {
                        val loadedTabs = mutableListOf<BrowserTab>()
                        for (i in 0 until arr.length()) {
                            val tObj = arr.getJSONObject(i)
                            loadedTabs.add(
                                BrowserTab(
                                    id = tObj.getString("id"),
                                    title = tObj.optString("title", "New Tab"),
                                    url = tObj.optString("url", "about:blank"),
                                    lastAccessedTime = tObj.optLong("lastAccessedTime", System.currentTimeMillis()),
                                    profileId = tObj.optString("profileId", "default")
                                )
                            )
                        }
                        if (loadedTabs.isNotEmpty()) {
                            val homeTabId = "tab_home_${System.currentTimeMillis()}"
                            val homeTab = BrowserTab(
                                id = homeTabId,
                                title = "New Tab",
                                url = "about:blank",
                                profileId = "default"
                            )
                            val nonBlankTabs = loadedTabs.filter { it.url != "about:blank" }
                            tabs = listOf(homeTab) + nonBlankTabs
                            activeTabId = homeTabId
                            currentUrl = "about:blank"
                            urlInputText = ""
                            pageTitle = "New Tab"
                            attachTabWebViewHandler?.invoke(homeTabId)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        fun captureThumbnail(): Bitmap? {
            val wv = webViewInstance ?: return null
            if (wv.width <= 0 || wv.height <= 0) return null
            return try {
                val scale = 0.5f
                val w = (wv.width * scale).toInt().coerceAtLeast(1)
                val h = (wv.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                val canvas = Canvas(bitmap)
                canvas.scale(scale, scale)
                wv.draw(canvas)
                bitmap
            } catch (_: Exception) {
                null
            }
        }

        fun extractSolverError(json: org.json.JSONObject, raw: String): String {
            val desc = json.optString("errorDescription")
            if (desc.isNotEmpty()) return desc
            val code = json.optString("errorCode")
            if (code.isNotEmpty()) return code
            val msg = json.optString("message", json.optString("msg", json.optString("error", "")))
            if (msg.isNotEmpty()) return msg
            val status = json.optString("status")
            if (status.isNotEmpty() && status != "processing") return status
            return if (raw.length > 120) raw.take(120) + "..." else raw
        }

        fun executeSolver(siteKey: String, pageUrl: String) {
            if (solverApiKey.isEmpty()) {
                bridge.showToast("Configure NoCaptchaAI API key in settings first.")
                return
            }
            if (isSolvingCaptcha) return

            isSolvingCaptcha = true
            bridge.showToast("🤖 Solving CAPTCHA with NoCaptchaAI...")
            bridge.log("SOLVER", "Creating task for sitekey: $siteKey on $pageUrl")

            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val createPayload = org.json.JSONObject().apply {
                        put("clientKey", solverApiKey)
                        put("task", org.json.JSONObject().apply {
                            put("type", "ReCaptchaV2TaskProxyLess")
                            put("websiteURL", pageUrl)
                            put("websiteKey", siteKey)
                        })
                    }

                    var responseStr = bridge.httpPost("https://api.nocaptchaai.com/createTask", createPayload.toString())
                    if (responseStr == null) {
                        responseStr = bridge.httpPost("https://api.nocaptchaai.com/solve", createPayload.toString())
                    }

                    if (responseStr != null) {
                        bridge.log("SOLVER", "Task Creation Response: $responseStr")
                        val respObj = org.json.JSONObject(responseStr)

                        var solutionToken = respObj.optJSONObject("solution")?.optString("gRecaptchaResponse")
                            ?: respObj.optString("token", "")

                        val taskId = respObj.optString("taskId", "")

                        if (solutionToken.isEmpty() && taskId.isNotEmpty()) {
                            bridge.log("SOLVER", "Task registered with ID: $taskId. Polling for solution...")
                            val pollPayload = org.json.JSONObject().apply {
                                put("clientKey", solverApiKey)
                                put("taskId", taskId)
                            }.toString()

                            var attempts = 0
                            while (attempts < 15 && solutionToken.isEmpty()) {
                                delay(2000)
                                attempts++
                                val pollRespStr = bridge.httpPost("https://api.nocaptchaai.com/getTaskResult", pollPayload)
                                if (pollRespStr != null) {
                                    val pollObj = org.json.JSONObject(pollRespStr)
                                    val status = pollObj.optString("status", "")
                                    bridge.log("SOLVER", "Poll #$attempts status: $status")

                                    if (status == "ready" || status == "solved") {
                                        solutionToken = pollObj.optJSONObject("solution")?.optString("gRecaptchaResponse")
                                            ?: pollObj.optString("token", "")
                                        break
                                    } else if (status == "failed" || pollObj.optInt("errorId", 0) != 0) {
                                        val err = extractSolverError(pollObj, pollRespStr)
                                        withContext(Dispatchers.Main) {
                                            bridge.showToast("Solver failed: $err")
                                            bridge.log("SOLVER_ERR", "Polling Error: $pollRespStr")
                                        }
                                        return@launch
                                    }
                                }
                            }
                        }

                        if (solutionToken.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                val injectionScript = buildCaptchaInjectionScript(solutionToken)
                                webViewInstance?.evaluateJavascript(injectionScript, null)
                                bridge.showToast("✅ CAPTCHA Solved & Injected!")
                                bridge.log("SOLVER", "Successfully injected response token into DOM.")
                            }
                        } else {
                            val errMsg = extractSolverError(respObj, responseStr)
                            withContext(Dispatchers.Main) {
                                bridge.showToast("Solver returned: $errMsg")
                                bridge.log("SOLVER_ERR", "API Response: $responseStr")
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            bridge.showToast("Failed to reach solver API endpoint.")
                            bridge.log("SOLVER_ERR", "HTTP POST to solver endpoint returned null.")
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        bridge.showToast("Solver error: ${e.message}")
                        bridge.log("SOLVER_ERR", "Exception: ${e.message}")
                    }
                } finally {
                    isSolvingCaptcha = false
                }
            }
        }

        fun scanAndSolveCaptcha() {
            val detectorScript = CAPTCHA_DETECTOR_SCRIPT

            webViewInstance?.evaluateJavascript(detectorScript) { siteKeyRaw ->
                val siteKey = siteKeyRaw?.replace("\"", "")?.trim() ?: ""
                if (siteKey.isNotEmpty()) {
                    executeSolver(siteKey, currentUrl)
                } else {
                    bridge.showToast("No active CAPTCHA widget found on page.")
                }
            }
        }

        fun pruneHotPool(keepTabId: String) {
            if (webViewPool.size <= MAX_HOT_TABS) return

            val tabsInPool = tabs.filter { it.id in webViewPool.keys && it.id != keepTabId }
                .sortedBy { it.lastAccessedTime }

            var numToEvict = webViewPool.size - MAX_HOT_TABS
            for (tabToEvict in tabsInPool) {
                if (numToEvict <= 0) break
                val wv = webViewPool.remove(tabToEvict.id)
                if (wv != null) {
                    val bundle = Bundle()
                    wv.saveState(bundle)
                    tabs = tabs.map { if (it.id == tabToEvict.id) it.copy(stateBundle = bundle) else it }

                    wv.stopLoading()
                    wv.onPause()
                    containerLayout?.removeView(wv)
                    wv.destroy()
                    numToEvict--
                    bridge.log("HOT_POOL", "Evicted cold tab: ${tabToEvict.id} (${tabToEvict.title})")
                }
            }
            CookieManager.getInstance().flush()
        }

        fun createConfiguredWebView(tabId: String, initialUrl: String, savedState: Bundle? = null, profileId: String = "default"): WebView {
            val webView = WebView(context).apply {
                setBackgroundColor(android.graphics.Color.parseColor("#1F2227"))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    try {
                        WebViewCompat.addDocumentStartJavaScript(this, blobInterceptorScript, setOf("*"))
                    } catch (_: Exception) {}
                }

                // Multi-Profile container binding & Cookie Isolation
                if (profileId != "default" && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    try {
                        val profileStore = ProfileStore.getInstance()
                        val profile = profileStore.getOrCreateProfile(profileId)
                        WebViewCompat.setProfile(this, profileId)
                        profile.cookieManager.setAcceptCookie(true)
                        profile.cookieManager.setAcceptThirdPartyCookies(this, true)
                    } catch (e: Exception) {
                        bridge.log("PROFILE_WARN", "Could not set profile '$profileId': ${e.message}")
                    }
                } else {
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                }

                setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                    triggerFileDownload(this, url, userAgent, contentDisposition, mimeType)
                }

                val blobBridge = OmniBlobBridge(
                    onBlobReceived = { base64Data, mime, filename ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (base64Data == "ERROR") {
                                bridge.showToast("Blob extract failed: $filename")
                                bridge.log("DOWNLOAD_ERR", "Blob extraction error: $filename")
                            } else {
                                saveBase64ToDownloads(base64Data, mime, filename)
                            }
                        }
                    },
                    onLog = { tag, msg ->
                        bridge.log(tag, msg)
                    }
                )
                addJavascriptInterface(blobBridge, "OmniBlobDownloader")

                val rawUA = settings.userAgentString
                val mobileUserAgent = rawUA.replace("; wv", "").replace(Regex("Version/[0-9.]+ "), "")
                mobileUA = mobileUserAgent
                settings.userAgentString = if (isDesktopMode) desktopUA else mobileUserAgent

                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message?
                    ): Boolean {
                        if (resultMsg == null) return false
                        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                        val tempWv = WebView(context).apply {
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                                    val targetUrl = req?.url?.toString() ?: return false
                                    if (handleExternalUri(targetUrl, v)) {
                                        return true
                                    }
                                    createNewTabHandler?.invoke(targetUrl)
                                    return true
                                }
                            }
                        }
                        transport.webView = tempWv
                        resultMsg.sendToTarget()
                        return true
                    }

                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        if (activeTabId == tabId) {
                            loadProgress = newProgress / 100f
                            isLoading = newProgress in 1..99
                        }
                    }

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        if (!title.isNullOrEmpty()) {
                            if (activeTabId == tabId) pageTitle = title
                            tabs = tabs.map { if (it.id == tabId) it.copy(title = title) else it }
                            saveSessionToDisk(tabs, activeTabId)
                        }
                    }

                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        bridge.log("CHROME_CONSOLE", "[$tabId][${consoleMessage?.messageLevel()}] ${consoleMessage?.message()}")
                        return true
                    }

                    override fun onPermissionRequest(request: PermissionRequest?) {
                        request?.grant(request.resources)
                    }

                    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                        callback?.invoke(origin, true, false)
                    }

                    override fun onShowFileChooser(
                        view: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        activeFileChooserCallback?.onReceiveValue(null)
                        activeFileChooserCallback = filePathCallback
                        return try {
                            val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }
                            fileChooserLauncher.launch(intent)
                            true
                        } catch (e: Exception) {
                            bridge.log("FILE_CHOOSER_ERR", "Failed to launch file picker: ${e.message}")
                            activeFileChooserCallback?.onReceiveValue(null)
                            activeFileChooserCallback = null
                            false
                        }
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return false
                        if (url.startsWith("blob:") || url.startsWith("data:")) {
                            triggerFileDownload(view, url, view?.settings?.userAgentString ?: "", "", "")
                            return true
                        }
                        return handleExternalUri(url, view)
                    }

                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                        handler?.proceed()
                    }

                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        if (activeTabId == tabId) {
                            canGoBack = view?.canGoBack() ?: false
                            canGoForward = view?.canGoForward() ?: false
                            if (url != null && url != "about:blank") {
                                currentUrl = url
                                urlInputText = url
                            }
                        }
                        if (url != null && url != "about:blank") {
                            tabs = tabs.map { if (it.id == tabId) it.copy(url = url) else it }
                            saveSessionToDisk(tabs, activeTabId)
                        }
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        if (activeTabId == tabId) isLoading = true
                        view?.evaluateJavascript(botBypassPolyfill, null)
                        view?.evaluateJavascript(blobInterceptorScript, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (activeTabId == tabId) isLoading = false
                        view?.evaluateJavascript(blobInterceptorScript, null)
                        CookieManager.getInstance().flush()

                        if (autoSolveEnabled && solverApiKey.isNotEmpty() && url != null && url != "about:blank") {
                            val detectorScript = CAPTCHA_DETECTOR_SCRIPT

                            view?.evaluateJavascript(detectorScript) { siteKeyRaw ->
                                val siteKey = siteKeyRaw?.replace("\"", "")?.trim() ?: ""
                                if (siteKey.isNotEmpty()) {
                                    executeSolver(siteKey, url)
                                }
                            }
                        }
                    }
                }

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    allowFileAccessFromFileURLs = true
                    allowUniversalAccessFromFileURLs = true
                    setSupportMultipleWindows(true)
                    javaScriptCanOpenWindowsAutomatically = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mediaPlaybackRequiresUserGesture = false
                    loadWithOverviewMode = true
                    useWideViewPort = true

                    try {
                        val method = javaClass.getMethod("setRequestedWithHeaderOriginAllowList", Set::class.java)
                        method.invoke(this, emptySet<String>())
                    } catch (_: Exception) {}
                }
            }

            if (savedState != null) {
                webView.restoreState(savedState)
            } else if (initialUrl.isNotEmpty() && initialUrl != "about:blank") {
                webView.loadUrl(initialUrl)
            }
            return webView
        }

        fun attachTabWebView(targetTabId: String) {
            val container = containerLayout ?: return
            val now = System.currentTimeMillis()
            tabs = tabs.map { if (it.id == targetTabId) it.copy(lastAccessedTime = now) else it }

            webViewPool.forEach { (id, wv) ->
                if (id != targetTabId) {
                    wv.onPause()
                }
            }
            CookieManager.getInstance().flush()
            container.removeAllViews()

            val targetTab = tabs.find { it.id == targetTabId } ?: return
            val isNewInstance = !webViewPool.containsKey(targetTabId)

            val targetWv = webViewPool.getOrPut(targetTabId) {
                createConfiguredWebView(targetTabId, targetTab.url, targetTab.stateBundle, targetTab.profileId)
            }

            if (isNewInstance) {
                pruneHotPool(targetTabId)
            }

            // Hydrate cold/restored webviews that have an active URL
            if (targetTab.url.isNotEmpty() && targetTab.url != "about:blank" && (targetWv.url == null || targetWv.url == "about:blank")) {
                targetWv.loadUrl(targetTab.url)
            }

            container.addView(targetWv)
            targetWv.onResume()
            webViewInstance = targetWv

            currentUrl = targetTab.url
            urlInputText = if (targetTab.url == "about:blank") "" else targetTab.url
            pageTitle = targetTab.title
            canGoBack = targetWv.canGoBack()
            canGoForward = targetWv.canGoForward()
        }

        attachTabWebViewHandler = { targetId -> attachTabWebView(targetId) }

        fun createNewTab(targetUrl: String = "about:blank", targetProfileId: String = selectedProfileId) {
            val thumb = captureThumbnail()
            val bundle = Bundle()
            webViewInstance?.saveState(bundle)

            val updatedTabs = tabs.map { 
                if (it.id == activeTabId) it.copy(thumbnail = thumb ?: it.thumbnail, stateBundle = bundle) else it 
            }

            val newId = "tab_${System.currentTimeMillis()}"
            val newTab = BrowserTab(
                id = newId, 
                title = if (targetUrl == "about:blank") "New Tab" else targetUrl, 
                url = targetUrl,
                lastAccessedTime = System.currentTimeMillis(),
                profileId = targetProfileId
            )
            tabs = updatedTabs + newTab
            activeTabId = newId
            isTabSwitcherOpen = false

            saveSessionToDisk(tabs, newId)
            attachTabWebView(newId)
        }

        createNewTabHandler = { targetUrl -> createNewTab(targetUrl) }

        fun switchToTab(targetId: String) {
            if (targetId == activeTabId) {
                isTabSwitcherOpen = false
                return
            }
            val thumb = captureThumbnail()
            val bundle = Bundle()
            webViewInstance?.saveState(bundle)

            val updatedTabs = tabs.map { 
                if (it.id == activeTabId) it.copy(thumbnail = thumb ?: it.thumbnail, stateBundle = bundle) else it 
            }
            tabs = updatedTabs
            activeTabId = targetId
            isTabSwitcherOpen = false

            saveSessionToDisk(tabs, targetId)
            attachTabWebView(targetId)
        }

        fun closeTab(targetId: String) {
            purgePendingWebViews()

            val closedTab = tabs.find { it.id == targetId }
            lastClosedTabsSnapshot = tabs
            lastActiveTabIdSnapshot = activeTabId
            val tabTitle = if (closedTab?.url == "about:blank") "New tab" else (closedTab?.title?.take(18) ?: "Tab")
            undoMessage = "$tabTitle closed"
            showUndoBanner = true
            undoJob?.cancel()
            undoJob = coroutineScope.launch {
                delay(4500)
                showUndoBanner = false
                purgePendingWebViews()
                lastClosedTabsSnapshot = null
            }

            // Move WebView to hot staging limbo instead of destroying it
            webViewPool.remove(targetId)?.let { wv ->
                wv.onPause()
                containerLayout?.removeView(wv)
                pendingPurgeWebViews[targetId] = wv
            }

            val currentIdx = tabs.indexOfFirst { it.id == targetId }
            val remainingTabs = tabs.filter { it.id != targetId }

            if (remainingTabs.isEmpty()) {
                val newId = "tab_${System.currentTimeMillis()}"
                val freshTab = BrowserTab(id = newId, title = "New Tab", url = "about:blank")
                tabs = listOf(freshTab)
                activeTabId = newId
                saveSessionToDisk(tabs, newId)
                attachTabWebView(newId)
            } else {
                tabs = remainingTabs
                if (targetId == activeTabId) {
                    val nextIdx = (currentIdx - 1).coerceAtLeast(0).coerceAtMost(remainingTabs.size - 1)
                    val nextTab = remainingTabs[nextIdx]
                    activeTabId = nextTab.id
                    saveSessionToDisk(tabs, nextTab.id)
                    attachTabWebView(nextTab.id)
                } else {
                    saveSessionToDisk(tabs, activeTabId)
                }
            }
        }

        fun closeAllTabs() {
            purgePendingWebViews()

            val count = tabs.size
            lastClosedTabsSnapshot = tabs
            lastActiveTabIdSnapshot = activeTabId
            undoMessage = "$count tabs closed"
            showUndoBanner = true
            undoJob?.cancel()
            undoJob = coroutineScope.launch {
                delay(4500)
                showUndoBanner = false
                purgePendingWebViews()
                lastClosedTabsSnapshot = null
            }

            // Move all active WebViews into hot staging limbo
            webViewPool.forEach { (id, wv) ->
                wv.onPause()
                pendingPurgeWebViews[id] = wv
            }
            webViewPool.clear()
            containerLayout?.removeAllViews()

            val newId = "tab_${System.currentTimeMillis()}"
            tabs = listOf(BrowserTab(id = newId, title = "New Tab", url = "about:blank"))
            activeTabId = newId
            isTabSwitcherOpen = false

            saveSessionToDisk(tabs, newId)
            attachTabWebView(newId)
        }

        fun navigateTo(rawInput: String) {
            val input = rawInput.trim()
            if (input.isEmpty()) return

            val target = when {
                input == "about:blank" -> "about:blank"
                input.startsWith("http://") || input.startsWith("https://") -> input
                input.startsWith("file://") -> input
                input.startsWith("/storage/") || input.startsWith("/") -> "file://$input"
                input.startsWith("localhost") || input.startsWith("127.0.0.1") || input.startsWith("192.168.") || input.startsWith("10.") || input.startsWith("172.") -> "http://$input"
                input.contains(".") && !input.contains(" ") -> "https://$input"
                else -> "https://www.google.com/search?q=${URLEncoder.encode(input, "UTF-8")}"
            }

            if (isHomeOverlayOpen && currentUrl != "about:blank") {
                isHomeOverlayOpen = false
                createNewTab(target)
                focusManager.clearFocus()
                return
            }

            isHomeOverlayOpen = false
            urlInputText = if (target == "about:blank") "" else target
            currentUrl = target
            tabs = tabs.map { if (it.id == activeTabId) it.copy(url = target) else it }
            webViewInstance?.loadUrl(target)
            focusManager.clearFocus()
        }

        fun injectErudaDevTools() {
            if (currentUrl == "about:blank" || webViewInstance == null) {
                bridge.showToast("Cannot inject DevTools on a blank page.")
                return
            }
            bridge.showToast("🛠️ Initializing Eruda DevTools...")
            val erudaScript = ERUDA_DEVTOOLS_SCRIPT
            webViewInstance?.evaluateJavascript(erudaScript, null)
        }

        fun saveHtmlSnapshot(rawHtml: String, prefix: String = "DOM_Dump"): String {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "${prefix}_$timestamp.html"
            var savedToDownloads = false

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/html")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OmniSnapshots")
                    }
                    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            os.write(rawHtml.toByteArray(Charsets.UTF_8))
                        }
                        savedToDownloads = true
                    }
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val targetDir = File(downloadsDir, "OmniSnapshots").apply { mkdirs() }
                    val targetFile = File(targetDir, filename)
                    FileOutputStream(targetFile).use { os ->
                        os.write(rawHtml.toByteArray(Charsets.UTF_8))
                    }
                    savedToDownloads = true
                }
            } catch (e: Exception) {
                bridge.log("DOM_SNAPSHOT_ERR", "Downloads folder write failed: ${e.message}")
            }

            bridge.saveFile("snapshots/$filename", rawHtml.toByteArray(Charsets.UTF_8))
            val locationMsg = if (savedToDownloads) "Downloads/OmniSnapshots/$filename" else "snapshots/$filename"
            bridge.log("DOM_SNAPSHOT", "Saved DOM snapshot: $locationMsg (${rawHtml.length} bytes)")
            bridge.showToast("✅ Saved DOM to $locationMsg")
            return locationMsg
        }

        fun captureDomSnapshot() {
            val script = DOM_SNAPSHOT_SCRIPT

            webViewInstance?.evaluateJavascript(script) { html ->
                if (!html.isNullOrEmpty()) {
                    val rawHtml = if (html.startsWith("\"") && html.endsWith("\"")) {
                        try {
                            org.json.JSONObject("{\"h\":$html}").getString("h")
                        } catch (_: Exception) { html }
                    } else html

                    saveHtmlSnapshot(rawHtml, "DOM_Dump")
                } else {
                    bridge.showToast("Could not capture page DOM.")
                }
            }
        }

        fun fetchFavicon(domain: String) {
            if (domain.isEmpty() || faviconCache.containsKey(domain)) return
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val clean = domain.removePrefix("www.")
                    val faviconUrl = "https://www.google.com/s2/favicons?domain=$clean&sz=128"
                    val conn = java.net.URL(faviconUrl).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    if (conn.responseCode in 200..299) {
                        conn.inputStream.use { input ->
                            val bmp = BitmapFactory.decodeStream(input)
                            if (bmp != null) {
                                withContext(Dispatchers.Main) {
                                    faviconCache[domain] = bmp
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        DisposableEffect(isTabSwitcherOpen, showSettingsDialog, showDownloadsDialog, showAutomationDialog, showAutomationResultDialog, editingProfile, editingShortcut, isAddingShortcut, isHomeOverlayOpen, canGoBack, currentUrl, webViewInstance, showMenu) {
            bridge.setOnBackPressedHandler {
                when {
                    showMenu -> {
                        showMenu = false
                        true
                    }
                    showAutomationResultDialog -> {
                        showAutomationResultDialog = false
                        true
                    }
                    showAutomationDialog -> {
                        showAutomationDialog = false
                        true
                    }
                    showDownloadsDialog -> {
                        showDownloadsDialog = false
                        true
                    }
                    editingShortcut != null -> {
                        editingShortcut = null
                        true
                    }
                    isAddingShortcut -> {
                        isAddingShortcut = false
                        true
                    }
                    editingProfile != null -> {
                        editingProfile = null
                        true
                    }
                    showSettingsDialog -> {
                        showSettingsDialog = false
                        true
                    }
                    isTabSwitcherOpen -> {
                        isTabSwitcherOpen = false
                        true
                    }
                    isHomeOverlayOpen -> {
                        isHomeOverlayOpen = false
                        true
                    }
                    currentUrl == "about:blank" -> {
                        false
                    }
                    webViewInstance?.canGoBack() == true -> {
                        webViewInstance?.goBack()
                        true
                    }
                    else -> {
                        navigateTo("about:blank")
                        true
                    }
                }
            }
            onDispose {
                bridge.setOnBackPressedHandler(null)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1F2227))
                    .statusBarsPadding()
                    .imePadding()
            ) {
                val activeTab = tabs.find { it.id == activeTabId }
                val activeProf = profiles.find { it.id == activeTab?.profileId } ?: profiles.firstOrNull() ?: BrowserProfile("default", "Default", 0xFF8AB4F8)
                val profColor = remember(activeProf.colorValue) { Color(activeProf.colorValue) }

                // --- Top Chrome Omnibox Header ---
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(1f)
                        .graphicsLayer()
                        .background(Color(0xFF16181D))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                if (currentUrl != "about:blank") {
                                    isHomeOverlayOpen = !isHomeOverlayOpen
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Home,
                                contentDescription = "Home",
                                tint = if (isHomeOverlayOpen || currentUrl == "about:blank") profColor else Color(0xFF9AA0A6)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(profColor.copy(alpha = 0.15f))
                                .border(1.5.dp, profColor, RoundedCornerShape(20.dp))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(profColor)
                                )

                                Spacer(Modifier.width(6.dp))

                                Icon(
                                    imageVector = if (currentUrl.startsWith("https://")) Icons.Default.Check else if (currentUrl.startsWith("http://")) Icons.Default.Info else Icons.Default.Search,
                                    contentDescription = null,
                                    tint = if (currentUrl.startsWith("https://")) Color(0xFF81C995) else if (currentUrl.startsWith("http://")) Color(0xFFFDD663) else profColor,
                                    modifier = Modifier.size(16.dp)
                                )

                                Spacer(Modifier.width(6.dp))

                                BasicTextField(
                                    value = urlInputText,
                                    onValueChange = { urlInputText = it },
                                    singleLine = true,
                                    maxLines = 1,
                                    cursorBrush = SolidColor(profColor),
                                    textStyle = TextStyle(
                                        color = Color(0xFFE8EAED),
                                        fontSize = 13.sp,
                                        lineHeight = 16.sp,
                                        platformStyle = PlatformTextStyle(
                                            includeFontPadding = false
                                        )
                                    ),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Go
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onGo = { navigateTo(urlInputText) }
                                    ),
                                    decorationBox = { innerTextField ->
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            if (urlInputText.isEmpty()) {
                                                Text(
                                                    text = "Search or type URL",
                                                    color = Color(0xFF9AA0A6),
                                                    fontSize = 13.sp,
                                                    style = TextStyle(
                                                        platformStyle = PlatformTextStyle(
                                                            includeFontPadding = false
                                                        )
                                                    )
                                                )
                                            }
                                            innerTextField()
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .wrapContentHeight(Alignment.CenterVertically)
                                )

                                if (urlInputText.isNotEmpty()) {
                                    IconButton(
                                        onClick = { urlInputText = "" },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color(0xFF9AA0A6), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.width(6.dp))

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(profColor.copy(alpha = 0.2f))
                                .border(1.5.dp, profColor, RoundedCornerShape(6.dp))
                                .clickable {
                                    val thumb = captureThumbnail()
                                    val bundle = Bundle()
                                    webViewInstance?.saveState(bundle)
                                    tabs = tabs.map { if (it.id == activeTabId) it.copy(stateBundle = bundle, thumbnail = thumb ?: it.thumbnail) else it }
                                    CookieManager.getInstance().flush()
                                    isTabSwitcherOpen = true
                                }
                        ) {
                            Text(
                                "${tabs.size}",
                                color = profColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                                                Spacer(Modifier.width(2.dp))

                        IconButton(
                            onClick = { showMenu = !showMenu },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "Menu",
                                tint = if (showMenu) profColor else Color(0xFF9AA0A6)
                            )
                        }
                    }
                }

                // --- Loading Progress Bar ---
                AnimatedVisibility(
                    visible = isLoading,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    LinearProgressIndicator(
                        progress = { loadProgress },
                        color = profColor,
                        trackColor = Color(0xFF282C34),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                    )
                }

                // --- Main Content (Speed Dial vs WebView) ---
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    AndroidView(
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                containerLayout = this
                                val initialWv = webViewPool.getOrPut(activeTabId) {
                                    createConfiguredWebView(activeTabId, currentUrl)
                                }
                                webViewInstance = initialWv
                                addView(initialWv)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (currentUrl == "about:blank" || isHomeOverlayOpen) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF1F2227))
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (isHomeOverlayOpen && currentUrl != "about:blank") {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = Color(0xFF282C34),
                                    border = BorderStroke(1.dp, Color(0xFF8AB4F8).copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .clickable { isHomeOverlayOpen = false }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Color(0xFF8AB4F8), modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Return to Live Page", color = Color(0xFF8AB4F8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                            } else {
                                Spacer(Modifier.height(40.dp))
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = profColor,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("🌐", fontSize = 20.sp)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        "Omni Chrome",
                                        fontSize = 26.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFE8EAED)
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = profColor.copy(alpha = 0.2f),
                                        border = BorderStroke(1.dp, profColor.copy(alpha = 0.7f))
                                    ) {
                                        Text(
                                            "Profile: ${activeProf.name}",
                                            color = profColor,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            Text(
                                if (isHomeOverlayOpen && currentUrl != "about:blank") "Live page suspended safely in background" else "Fast, Stealthy, Dynamic Browsing",
                                fontSize = 13.sp,
                                color = Color(0xFF9AA0A6),
                                modifier = Modifier.padding(top = 6.dp, bottom = 32.dp)
                            )

                            LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(shortcuts, key = { it.id }) { item ->
                                    val domain = remember(item.url) { extractDomain(item.url) }
                                    LaunchedEffect(domain) {
                                        fetchFavicon(domain)
                                    }
                                    val iconBmp = faviconCache[domain]

                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .combinedClickable(
                                                onClick = {
                                                    if (isHomeOverlayOpen && currentUrl != "about:blank") {
                                                        isHomeOverlayOpen = false
                                                        createNewTab(item.url)
                                                    } else {
                                                        navigateTo(item.url)
                                                    }
                                                },
                                                onLongClick = {
                                                    bridge.vibrate(40L)
                                                    editingShortcut = item
                                                }
                                            )
                                            .padding(8.dp)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color(0xFF282C34),
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                if (iconBmp != null) {
                                                    Image(
                                                        bitmap = iconBmp.asImageBitmap(),
                                                        contentDescription = item.title,
                                                        modifier = Modifier
                                                            .size(26.dp)
                                                            .clip(CircleShape),
                                                        contentScale = ContentScale.Fit
                                                    )
                                                } else {
                                                    Text(
                                                        text = item.iconText.ifEmpty { item.title.take(1).uppercase() },
                                                        fontSize = 18.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(item.colorValue)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            item.title,
                                            fontSize = 11.sp,
                                            color = Color(0xFFE8EAED),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }

                                item {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { isAddingShortcut = true }
                                            .padding(8.dp)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color(0xFF282C34),
                                            border = BorderStroke(1.dp, Color(0xFF5F6368)),
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Default.Add,
                                                    contentDescription = "Add Shortcut",
                                                    tint = Color(0xFF8AB4F8),
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            "Add",
                                            fontSize = 11.sp,
                                            color = Color(0xFF8AB4F8),
                                            maxLines = 1,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // --- Headless AI Studio Automation Engine ---
            fun startAutomationRun() {
                if (autoUserPrompt.trim().isEmpty()) {
                    bridge.showToast("Please provide a prompt to run.")
                    return
                }

                showAutomationDialog = false
                showAutomationResultDialog = true
                isAutomating = true
                automationStatus = "Initializing Headless Session..."
                automationThoughts = ""
                automationResult = ""
                automationError = null
                automationElapsedSec = 0

                // Clean up any stale headless worker
                try {
                    headlessAutomationWv?.stopLoading()
                    headlessAutomationWv?.onPause()
                    containerLayout?.removeView(headlessAutomationWv)
                    headlessAutomationWv?.destroy()
                } catch (_: Exception) {}

                // Flush active cookies before spinning up headless worker
                if (autoSelectedProfileId != "default" && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    try {
                        val profileStore = ProfileStore.getInstance()
                        profileStore.getOrCreateProfile(autoSelectedProfileId).cookieManager.flush()
                    } catch (_: Exception) {}
                } else {
                    CookieManager.getInstance().flush()
                }

                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

                val jsEscapedPrompt = org.json.JSONObject.quote(autoUserPrompt.trim())
                val jsEscapedSysPrompt = org.json.JSONObject.quote(autoSystemPrompt.trim())
                val jsEscapedThinking = org.json.JSONObject.quote(autoThinkingLevel)
                val jsEscapedModel = org.json.JSONObject.quote(autoSelectedModel)

                val automationScript = buildAiStudioAutomationScript(
                    prompt = jsEscapedPrompt,
                    sysPrompt = jsEscapedSysPrompt,
                    thinkingLevel = jsEscapedThinking,
                    model = jsEscapedModel
                )

                val autoWv = WebView(context).apply {
                    translationX = 0f
                    alpha = 1f

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        setSupportMultipleWindows(true)
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                        val rawUA = userAgentString
                        userAgentString = rawUA.replace("; wv", "").replace(Regex("Version/[0-9.]+ "), "")

                        try {
                            val method = javaClass.getMethod("setRequestedWithHeaderOriginAllowList", Set::class.java)
                            method.invoke(this, emptySet<String>())
                        } catch (_: Exception) {}
                    }

                    if (autoSelectedProfileId != "default" && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                        try {
                            val profileStore = ProfileStore.getInstance()
                            val profile = profileStore.getOrCreateProfile(autoSelectedProfileId)
                            WebViewCompat.setProfile(this, autoSelectedProfileId)
                            profile.cookieManager.setAcceptCookie(true)
                            profile.cookieManager.setAcceptThirdPartyCookies(this, true)
                        } catch (_: Exception) {}
                    } else {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            val msg = consoleMessage?.message() ?: ""
                            val lvl = consoleMessage?.messageLevel()?.name ?: "LOG"
                            val line = consoleMessage?.lineNumber() ?: 0
                            val src = consoleMessage?.sourceId() ?: ""
                            val tag = when (consoleMessage?.messageLevel()) {
                                ConsoleMessage.MessageLevel.ERROR -> "AI_STUDIO_ERR"
                                ConsoleMessage.MessageLevel.WARNING -> "AI_STUDIO_WARN"
                                else -> "AI_STUDIO_CONSOLE"
                            }
                            bridge.log(tag, "[$lvl] $msg (line $line in $src)")
                            return true
                        }

                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            if (newProgress % 25 == 0 || newProgress == 100) {
                                bridge.log("AI_STUDIO_LOAD", "Headless load: $newProgress%")
                            }
                        }
                    }

                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onStatus(msg: String) {
                            mainHandler.post { automationStatus = msg }
                        }

                        @JavascriptInterface
                        fun onLog(tag: String, msg: String) {
                            bridge.log("AI_STUDIO_$tag", msg)
                        }

                        @JavascriptInterface
                        fun onProgress(thoughts: String, output: String) {
                            mainHandler.post {
                                automationThoughts = thoughts
                                automationResult = output
                                automationStatus = "Streaming response..."
                            }
                        }

                        @JavascriptInterface
                        fun onComplete(thoughts: String, output: String) {
                            mainHandler.post {
                                automationThoughts = thoughts
                                automationResult = output
                                automationStatus = "Completed"
                                isAutomating = false
                            }
                        }

                        @JavascriptInterface
                        fun onError(err: String) {
                            mainHandler.post {
                                automationError = err
                                automationStatus = "Failed"
                                isAutomating = false
                            }
                        }
                    }, "OmniAutomator")

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            bridge.log("AI_STUDIO_NAV", "Started loading: $url")
                            view?.evaluateJavascript(botBypassPolyfill, null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            bridge.log("AI_STUDIO_NAV", "Finished loading: $url")
                            if (url != null && url.contains("aistudio.google.com")) {
                                bridge.log("AI_STUDIO_INJECT", "Injecting automation script into $url")
                                view?.evaluateJavascript(automationScript, null)
                            }
                        }

                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            super.onReceivedError(view, request, error)
                            bridge.log("AI_STUDIO_ERR", "WebView Error: ${error?.description} on URL: ${request?.url}")
                            if (request?.isForMainFrame == true) {
                                mainHandler.post {
                                    automationError = "Network error connecting to AI Studio (${error?.description})"
                                    isAutomating = false
                                }
                            }
                        }
                    }
                }

                headlessAutomationWv = autoWv
                containerLayout?.addView(
                    autoWv,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                autoWv.onResume()
                autoWv.loadUrl("https://aistudio.google.com/prompts/new_chat")
            }

            // Timer ticker during automation
            LaunchedEffect(isAutomating) {
                while (isAutomating) {
                    delay(1000)
                    automationElapsedSec++
                }
            }

            // --- Dialogs & Modals ---
            if (showAutomationDialog) {
                AutomationOrderDialog(
                    profiles = profiles,
                    selectedProfileId = autoSelectedProfileId,
                    onSelectProfileId = { autoSelectedProfileId = it },
                    thinkingLevel = autoThinkingLevel,
                    onThinkingLevelChange = { autoThinkingLevel = it },
                    systemPrompt = autoSystemPrompt,
                    onSystemPromptChange = { autoSystemPrompt = it },
                    userPrompt = autoUserPrompt,
                    onUserPromptChange = { autoUserPrompt = it },
                    onDismiss = { showAutomationDialog = false },
                    onRun = { startAutomationRun() }
                )
            }

            if (showAutomationResultDialog) {
                AutomationResultDialog(
                    isAutomating = isAutomating,
                    automationStatus = automationStatus,
                    automationThoughts = automationThoughts,
                    automationResult = automationResult,
                    automationError = automationError,
                    automationElapsedSec = automationElapsedSec,
                    onDumpDom = {
                        bridge.showToast("Dumping live DOM to Downloads/OmniSnapshots...")
                        headlessAutomationWv?.evaluateJavascript(DOM_SNAPSHOT_SCRIPT) { html ->
                            if (!html.isNullOrEmpty() && html != "null") {
                                val rawHtml = if (html.startsWith("\"") && html.endsWith("\"")) {
                                    try {
                                        org.json.JSONObject("{\"h\":$html}").getString("h")
                                    } catch (_: Exception) { html }
                                } else html
                                saveHtmlSnapshot(rawHtml, "Automator_DOM")
                            } else {
                                bridge.showToast("Could not capture page DOM.")
                            }
                        }
                    },
                    onCopyResult = {
                        bridge.copyToClipboard(automationResult)
                        bridge.showToast("Copied result to clipboard!")
                    },
                    onCloseOrStop = {
                        showAutomationResultDialog = false
                        try {
                            headlessAutomationWv?.stopLoading()
                            headlessAutomationWv?.onPause()
                            containerLayout?.removeView(headlessAutomationWv)
                            headlessAutomationWv?.destroy()
                            headlessAutomationWv = null
                        } catch (_: Exception) {}
                    }
                )
            }

            if (showDownloadsDialog) {
                DownloadsManagerDialog(
                    bridge = bridge,
                    activeDownloadsList = activeDownloadsList,
                    completedFilesList = completedFilesList,
                    onCancelDownload = { id ->
                        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                        dm?.remove(id)
                        trackedDownloadIds.remove(id)
                    },
                    onDeleteFile = { file ->
                        try {
                            file.delete()
                            refreshCompletedDownloads()
                            bridge.showToast("Deleted ${file.name}")
                        } catch (e: Exception) {
                            bridge.showToast("Delete failed: ${e.message}")
                        }
                    },
                    onDismiss = { showDownloadsDialog = false }
                )
            }

            if (showSettingsDialog) {
                SettingsBackupDialog(
                    apiKey = solverApiKey,
                    autoSolve = autoSolveEnabled,
                    onExportBackup = { exportFullBackup() },
                    onRestoreBackup = { backupPickerLauncher.launch("application/zip") },
                    onSolveNow = { scanAndSolveCaptcha() },
                    onClearCookiesAndCache = {
                        CookieManager.getInstance().removeAllCookies(null)
                        webViewInstance?.clearCache(true)
                        bridge.showToast("Cookies and Cache cleared.")
                    },
                    onSave = { key, auto ->
                        solverApiKey = key
                        autoSolveEnabled = auto
                        val cfg = org.json.JSONObject().apply {
                            put("apiKey", solverApiKey)
                            put("autoSolve", autoSolveEnabled)
                        }
                        bridge.saveFile("config/solver.json", cfg.toString().toByteArray(Charsets.UTF_8))
                        autoMirrorVaultToDocuments()
                        bridge.showToast("Settings saved!")
                        showSettingsDialog = false
                    },
                    onDismiss = { showSettingsDialog = false }
                )
            }

            if (editingShortcut != null) {
                val targetItem = editingShortcut!!
                EditShortcutDialog(
                    shortcut = targetItem,
                    faviconCache = faviconCache,
                    onFetchFavicon = { fetchFavicon(it) },
                    onPickFile = { onPicked ->
                        bridge.pickFiles("*/*", false) { uris ->
                            val picked = uris.firstOrNull()
                            if (picked != null) {
                                val pathStr = picked.path
                                val directPath = if (pathStr != null && (pathStr.contains("/storage/") || pathStr.contains("/sdcard/"))) {
                                    pathStr.substring(pathStr.indexOf("/storage/").coerceAtLeast(pathStr.indexOf("/sdcard/")))
                                } else {
                                    picked.toString()
                                }
                                onPicked(directPath)
                            }
                        }
                    },
                    onDelete = {
                        val updated = shortcuts.filter { it.id != targetItem.id }
                        shortcuts = updated
                        saveShortcutsToDisk(updated)
                        try {
                            File(bridge.getPluginDir(), "ide/vault_${targetItem.id}").deleteRecursively()
                        } catch (_: Exception) {}
                        editingShortcut = null
                        bridge.showToast("Shortcut deleted")
                    },
                    onSave = { name, rawUrl ->
                        val trimmedUrl = rawUrl.trim()
                        val isLocal = isLocalFilePath(trimmedUrl)

                        val (finalUrl, srcPath) = if (isLocal) {
                            val isolatedSubPath = "ide/vault_${targetItem.id}/index.html"
                            val (success, vaultedPath) = syncLocalFileToVault(trimmedUrl, isolatedSubPath)
                            if (success) {
                                bridge.showToast("✅ Synced to private vault slot!")
                                Pair(vaultedPath, normalizeLocalFilePath(trimmedUrl))
                            } else {
                                bridge.showToast("⚠️ $vaultedPath (See Diagnostics)")
                                val cleanNorm = normalizeLocalFilePath(trimmedUrl)
                                Pair("file://$cleanNorm", cleanNorm)
                            }
                        } else {
                            val webUrl = if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) "https://$trimmedUrl" else trimmedUrl
                            Pair(webUrl, null)
                        }

                        val updated = shortcuts.map {
                            if (it.id == targetItem.id) it.copy(
                                title = name.trim().ifEmpty { targetItem.title },
                                url = finalUrl,
                                localSourcePath = srcPath
                            ) else it
                        }
                        shortcuts = updated
                        saveShortcutsToDisk(updated)
                        fetchFavicon(extractDomain(finalUrl))
                        editingShortcut = null
                    },
                    onDismiss = { editingShortcut = null }
                )
            }

            if (isAddingShortcut) {
                AddShortcutDialog(
                    faviconCache = faviconCache,
                    onFetchFavicon = { fetchFavicon(it) },
                    onPickFile = { onPicked ->
                        bridge.pickFiles("*/*", false) { uris ->
                            val picked = uris.firstOrNull()
                            if (picked != null) {
                                val pathStr = picked.path
                                val directPath = if (pathStr != null && (pathStr.contains("/storage/") || pathStr.contains("/sdcard/"))) {
                                    pathStr.substring(pathStr.indexOf("/storage/").coerceAtLeast(pathStr.indexOf("/sdcard/")))
                                } else {
                                    picked.toString()
                                }
                                onPicked(directPath)
                            }
                        }
                    },
                    onAdd = { name, rawUrl ->
                        val trimmedUrl = rawUrl.trim()
                        val isLocal = isLocalFilePath(trimmedUrl)
                        val newShortcutId = "sc_${System.currentTimeMillis()}"
                        val (finalUrl, srcPath) = if (isLocal) {
                            val isolatedSubPath = "ide/vault_$newShortcutId/index.html"
                            val (success, vaultedPath) = syncLocalFileToVault(trimmedUrl, isolatedSubPath)
                            if (success) {
                                bridge.showToast("✅ Isolated vault created for local app")
                                Pair(vaultedPath, normalizeLocalFilePath(trimmedUrl))
                            } else {
                                bridge.showToast("⚠️ $vaultedPath (See Diagnostics)")
                                val cleanNorm = normalizeLocalFilePath(trimmedUrl)
                                Pair("file://$cleanNorm", cleanNorm)
                            }
                        } else {
                            val webUrl = if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) "https://$trimmedUrl" else trimmedUrl
                            Pair(webUrl, null)
                        }
                        val title = name.trim().ifEmpty { if (isLocal) "Local App" else extractDomain(finalUrl) }
                        val newItem = ShortcutItem(
                            id = newShortcutId,
                            title = title,
                            url = finalUrl,
                            iconText = if (isLocal) "💻" else title.take(1).uppercase(),
                            colorValue = 0xFF58A6FF,
                            localSourcePath = srcPath
                        )
                        val updated = shortcuts + newItem
                        shortcuts = updated
                        saveShortcutsToDisk(updated)
                        fetchFavicon(extractDomain(finalUrl))
                        isAddingShortcut = false
                    },
                    onDismiss = { isAddingShortcut = false }
                )
            }

            if (editingProfile != null) {
                val targetProf = editingProfile!!
                RenameProfileDialog(
                    profile = targetProf,
                    onSave = { newName ->
                        val updated = profiles.map { if (it.id == targetProf.id) it.copy(name = newName) else it }
                        profiles = updated
                        saveProfilesToDisk(updated)
                        editingProfile = null
                    },
                    onDismiss = { editingProfile = null }
                )
            }

            // --- In-Layout Browser Menu Overlay (Single Unified Scrim & Surface) ---
            if (showMenu) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(20f)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { showMenu = false })
                        }
                        .statusBarsPadding()
                        .padding(top = 52.dp, end = 8.dp),
                    contentAlignment = Alignment.TopEnd
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF282C34),
                        tonalElevation = 8.dp,
                        shadowElevation = 10.dp,
                        border = BorderStroke(1.dp, Color(0xFF3C4043)),
                        modifier = Modifier
                            .width(250.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { /* Consume taps inside menu so they do not dismiss */ })
                            }
                    ) {
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceAround,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        showMenu = false
                                        if (canGoBack) webViewInstance?.goBack()
                                        else if (currentUrl != "about:blank") navigateTo("about:blank")
                                    },
                                    enabled = canGoBack || currentUrl != "about:blank"
                                ) {
                                    Icon(
                                        Icons.Default.ArrowBack,
                                        contentDescription = "Back",
                                        tint = if (canGoBack || currentUrl != "about:blank") Color(0xFFE8EAED) else Color(0xFF5F6368)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        showMenu = false
                                        webViewInstance?.goForward()
                                    },
                                    enabled = canGoForward
                                ) {
                                    Icon(
                                        Icons.Default.ArrowForward,
                                        contentDescription = "Forward",
                                        tint = if (canGoForward) Color(0xFFE8EAED) else Color(0xFF5F6368)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        showMenu = false
                                        if (webViewInstance != null && currentUrl != "about:blank") {
                                            val wvUrl = webViewInstance?.url
                                            if (wvUrl == null || wvUrl == "about:blank" || wvUrl.isEmpty()) {
                                                webViewInstance?.loadUrl(currentUrl)
                                            } else {
                                                webViewInstance?.reload()
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Reload",
                                        tint = Color(0xFFE8EAED)
                                    )
                                }
                            }

                            HorizontalDivider(color = Color(0xFF3C4043), modifier = Modifier.padding(vertical = 4.dp))

                            @Composable
                            fun InLayoutMenuItem(
                                title: String,
                                color: Color = Color(0xFFE8EAED),
                                isBold: Boolean = false,
                                onClick: () -> Unit
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            showMenu = false
                                            onClick()
                                        }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = title,
                                        color = color,
                                        fontSize = 13.sp,
                                        fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }

                            InLayoutMenuItem("🤖 AI Studio Automator", color = Color(0xFF8AB4F8), isBold = true) {
                                showAutomationDialog = true
                            }

                            InLayoutMenuItem("💻 Open Local IDE", color = Color(0xFF58A6FF), isBold = true) {
                                val localIdeShortcuts = shortcuts.filter { it.localSourcePath != null || it.url.contains("/ide/") || it.title.contains("IDE", ignoreCase = true) }
                                val target = localIdeShortcuts.firstOrNull()
                                if (target != null) {
                                    val src = target.localSourcePath ?: "/storage/emulated/0/Download/F/index.html"
                                    val isolatedPath = "ide/vault_${target.id}/index.html"
                                    val vFile = File(bridge.getPluginDir(), isolatedPath)
                                    val targetUrl = if (vFile.exists() && vFile.length() > 0) {
                                        "file://${vFile.absolutePath}"
                                    } else {
                                        val (success, resPath) = syncLocalFileToVault(src, isolatedPath)
                                        if (success) {
                                            bridge.showToast("✅ Synced ${target.title} from disk!")
                                            resPath
                                        } else {
                                            target.url
                                        }
                                    }
                                    navigateTo(targetUrl)
                                } else {
                                    val defaultFile = File(bridge.getPluginDir(), "ide/index.html")
                                    val targetUrl = if (defaultFile.exists() && defaultFile.length() > 0) {
                                        "file://${defaultFile.absolutePath}"
                                    } else {
                                        val (success, resPath) = syncLocalFileToVault("/storage/emulated/0/Download/F/index.html", "ide/index.html")
                                        if (success) resPath else ideInternalPath
                                    }
                                    navigateTo(targetUrl)
                                }
                            }

                            InLayoutMenuItem(
                                title = if (trackedDownloadIds.isNotEmpty()) "📥 Downloads (${trackedDownloadIds.size})" else "📥 Downloads",
                                color = if (trackedDownloadIds.isNotEmpty()) Color(0xFF81C995) else Color(0xFF8AB4F8),
                                isBold = true
                            ) {
                                refreshCompletedDownloads()
                                showDownloadsDialog = true
                            }

                            InLayoutMenuItem("+ New Tab", color = Color(0xFF8AB4F8), isBold = true) {
                                createNewTab()
                            }

                            InLayoutMenuItem("Close Tab") {
                                closeTab(activeTabId)
                            }

                            InLayoutMenuItem(if (isDesktopMode) "✓ Desktop Site" else "Desktop Site") {
                                isDesktopMode = !isDesktopMode
                                webViewInstance?.settings?.userAgentString = if (isDesktopMode) desktopUA else mobileUA
                                webViewInstance?.reload()
                                bridge.showToast(if (isDesktopMode) "Desktop Mode Enabled" else "Mobile Mode Enabled")
                            }

                            InLayoutMenuItem("Copy Clean URL") {
                                if (currentUrl != "about:blank") {
                                    val cleanUrl = sanitizeUrlForCopy(currentUrl)
                                    bridge.copyToClipboard(cleanUrl)
                                }
                            }

                            InLayoutMenuItem("🛠️ Eruda DevTools (Console)", color = Color(0xFF8AB4F8), isBold = true) {
                                injectErudaDevTools()
                            }

                            InLayoutMenuItem("Capture DOM Snapshot", color = Color(0xFF8AB4F8)) {
                                captureDomSnapshot()
                            }

                            InLayoutMenuItem("⚙️ Settings & Backup", color = Color(0xFF8AB4F8), isBold = true) {
                                showSettingsDialog = true
                            }

                            HorizontalDivider(color = Color(0xFF3C4043), modifier = Modifier.padding(vertical = 4.dp))

                            InLayoutMenuItem("Exit Omni Chrome", color = Color(0xFFF28B82), isBold = true) {
                                showMenu = false
                                showSettingsDialog = false
                                editingShortcut = null
                                isAddingShortcut = false
                                editingProfile = null
                                coroutineScope.launch {
                                    // 400ms guarantees the Compose render pass and hardware buffer swap 
                                    // are fully completed before the task manager takes the bitmap snapshot.
                                    delay(400) 
                                    bridge.close()
                                }
                            }
                        }
                    }
                }
            }

            if (isTabSwitcherOpen) {
                TabSwitcherScreen(
                    tabs = tabs,
                    activeTabId = activeTabId,
                    profiles = profiles,
                    selectedProfileId = selectedProfileId,
                    onSelectProfile = {
                        selectedProfileId = it
                        saveProfilesToDisk(profiles)
                    },
                    onAddProfile = {
                        val nextIdx = profiles.size
                        val newId = "profile_${System.currentTimeMillis()}"
                        val newColor = PROFILE_PALETTE[nextIdx % PROFILE_PALETTE.size]
                        val newProf = BrowserProfile(newId, "Account ${nextIdx + 1}", newColor)
                        val updated = profiles + newProf
                        profiles = updated
                        selectedProfileId = newId
                        saveProfilesToDisk(updated)
                    },
                    onRenameProfile = { prof -> editingProfile = prof },
                    onSelectTab = { switchToTab(it) },
                    onCloseTab = { closeTab(it) },
                    onNewTab = { profId -> createNewTab(targetProfileId = profId) },
                    onCloseAll = { closeAllTabs() },
                    onCloseSwitcher = { isTabSwitcherOpen = false },
                    modifier = Modifier.fillMaxSize().zIndex(10f)
                )
            }

            // --- Floating Undo Toast / Snackbar Banner ---
            AnimatedVisibility(
                visible = showUndoBanner,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp)
                    .zIndex(30f)
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFF21262D),
                    border = BorderStroke(1.dp, Color(0xFF30363D)),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .wrapContentWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = undoMessage,
                            color = Color(0xFFE8EAED),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )

                        TextButton(
                            onClick = {
                                undoJob?.cancel()
                                // Move all hot staged WebViews back to the active pool
                                pendingPurgeWebViews.forEach { (id, wv) ->
                                    webViewPool[id] = wv
                                }
                                pendingPurgeWebViews.clear()

                                val backup = lastClosedTabsSnapshot
                                if (backup != null && backup.isNotEmpty()) {
                                    tabs = backup
                                    val targetId = if (backup.any { it.id == lastActiveTabIdSnapshot }) lastActiveTabIdSnapshot!! else backup.first().id
                                    activeTabId = targetId
                                    saveSessionToDisk(backup, targetId)
                                    attachTabWebView(targetId)
                                }
                                showUndoBanner = false
                                lastClosedTabsSnapshot = null
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "UNDO",
                                color = Color(0xFF58A6FF),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        IconButton(
                            onClick = {
                                undoJob?.cancel()
                                showUndoBanner = false
                                purgePendingWebViews()
                                lastClosedTabsSnapshot = null
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = Color(0xFF8B949E),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}