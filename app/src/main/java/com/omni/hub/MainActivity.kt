package com.omni.hub

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.omni.hub.api.FilePickerDispatcher
import com.omni.hub.api.OmniLogger
import com.omni.hub.api.PermissionDispatcher
import com.omni.hub.container.PluginContainerActivity
import com.omni.hub.loader.AppTaskSession
import com.omni.hub.loader.OmniTaskManager
import com.omni.hub.loader.PluginManager
import com.omni.hub.loader.PluginMetadata
import com.omni.hub.loader.PluginTaskEngine
import com.omni.hub.loader.SharedLibManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class CloudModule(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val entryClass: String,
    val fileName: String,
    val downloadUrl: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0D1117),
                    surface = Color(0xFF161B22),
                    primary = Color(0xFF58A6FF)
                )
            ) {
                DashboardScreen(this)
            }
        }
    }
}

private val supabaseHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()

private suspend fun fetchCloudModules(): Pair<Boolean, List<CloudModule>> = withContext(Dispatchers.IO) {
    val list = mutableListOf<CloudModule>()
    var isOnline = false
    try {
        val url = "https://vlzgfaqrnyiqfxxxvtas.supabase.co/rest/v1/omni_modules?select=*&order=created_at.desc"
        val anonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZsemdmYXFybnlpcWZ4eHh2dGFzIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjU1NTk5NDAsImV4cCI6MjA4MTEzNTk0MH0.y93d68JWyGL7NKXZEHLunAuayMEWw1K6yATFGLxkUxY"
        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer $anonKey")
            .build()

        val response = supabaseHttpClient.newCall(request).execute()
        if (response.isSuccessful) {
            isOnline = true
            val jsonStr = response.body?.string() ?: "[]"
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    CloudModule(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        description = obj.optString("description", "Dynamic Cloud Module"),
                        version = obj.optString("version", "1.0.0"),
                        entryClass = obj.getString("entry_class"),
                        fileName = obj.optString("file_name", ""),
                        downloadUrl = obj.getString("download_url")
                    )
                )
            }
        }
    } catch (_: Exception) {}
    Pair(isOnline, list)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(context: Context) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var plugins by remember { mutableStateOf(PluginManager.getInstalledPlugins(context)) }
    var cloudModules by remember { mutableStateOf<List<CloudModule>>(emptyList()) }
    var isCloudLoading by remember { mutableStateOf(false) }
    var isBackendOnline by remember { mutableStateOf<Boolean?>(null) }
    var downloadingIds by remember { mutableStateOf(setOf<String>()) }

    var showImportDialog by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showLogModal by remember { mutableStateOf(false) }
    var showTaskManagerModal by remember { mutableStateOf(false) }
    var selectedZipUri by remember { mutableStateOf<Uri?>(null) }
    var runningStates by remember { mutableStateOf(mapOf<String, Boolean>()) }

    fun refreshRunningStates() {
        runningStates = plugins.associate { it.id to PluginTaskEngine.isTaskRunning(it.id) }
    }

    fun loadCloudCatalog() {
        scope.launch {
            isCloudLoading = true
            OmniLogger.log("CATALOG", "Fetching live module catalog from Supabase...")
            val (isOnline, modules) = fetchCloudModules()
            cloudModules = modules
            isBackendOnline = isOnline
            isCloudLoading = false
        }
    }

    var activePermissionCallback by remember { mutableStateOf<((Map<String, Boolean>) -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        activePermissionCallback?.invoke(result)
        activePermissionCallback = null
    }

    var activeFilePickerCallback by remember { mutableStateOf<((List<Uri>) -> Unit)?>(null) }
    val singleFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        activeFilePickerCallback?.invoke(if (uri != null) listOf(uri) else emptyList())
        activeFilePickerCallback = null
    }
    val multipleFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        activeFilePickerCallback?.invoke(uris)
        activeFilePickerCallback = null
    }

    LaunchedEffect(Unit) {
        PermissionDispatcher.registerLauncher { perms, cb ->
            activePermissionCallback = cb
            permissionLauncher.launch(perms)
        }
        FilePickerDispatcher.registerLauncher { mimeType, allowMultiple, cb ->
            activeFilePickerCallback = cb
            if (allowMultiple) {
                multipleFilePicker.launch(mimeType)
            } else {
                singleFilePicker.launch(mimeType)
            }
        }
        OmniLogger.log("INIT", "Omni Hub Dashboard loaded. Ensuring shared runtime...")
        scope.launch {
            SharedLibManager.ensureSharedRuntime(context)
        }
        loadCloudCatalog()
    }

    LaunchedEffect(plugins) {
        refreshRunningStates()
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedZipUri = uri
            showImportDialog = true
        }
    }

    val currentSession = OmniTaskManager.currentForegroundSession

    Box(modifier = Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF161B22),
                modifier = Modifier.width(320.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    var showImportDropdown by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Cloud Catalog", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { showLogModal = true }) {
                                Text("📋", fontSize = 16.sp)
                            }

                            Box {
                                IconButton(onClick = { showImportDropdown = true }) {
                                    Icon(Icons.Default.Add, contentDescription = "Import Module", tint = Color(0xFF238636))
                                }
                                DropdownMenu(
                                    expanded = showImportDropdown,
                                    onDismissRequest = { showImportDropdown = false },
                                    modifier = Modifier.background(Color(0xFF21262D))
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("📦", fontSize = 14.sp)
                                                Spacer(Modifier.width(8.dp))
                                                Text("Local ZIP Import", color = Color.White, fontSize = 13.sp)
                                            }
                                        },
                                        onClick = {
                                            showImportDropdown = false
                                            filePicker.launch("application/zip")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("🌐", fontSize = 14.sp)
                                                Spacer(Modifier.width(8.dp))
                                                Text("Download OTA URL", color = Color(0xFF58A6FF), fontSize = 13.sp)
                                            }
                                        },
                                        onClick = {
                                            showImportDropdown = false
                                            showUrlDialog = true
                                        }
                                    )
                                }
                            }

                            IconButton(onClick = { loadCloudCatalog() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh Catalog", tint = Color(0xFF8B949E))
                            }
                        }
                    }

                    Text(
                        "Supabase Public Module Store",
                        fontSize = 11.sp,
                        color = Color(0xFF8B949E),
                        modifier = Modifier.padding(bottom = 14.dp)
                    )

                    if (isCloudLoading) {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color(0xFF58A6FF), modifier = Modifier.size(32.dp))
                        }
                    } else if (cloudModules.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text("No cloud modules published yet.", color = Color(0xFF8B949E), fontSize = 13.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(cloudModules) { module ->
                                val isInstalled = plugins.any { it.id == module.id }
                                val isDownloading = downloadingIds.contains(module.id)

                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1117))
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(module.name, fontWeight = FontWeight.Bold, color = Color(0xFFC9D1D9), fontSize = 15.sp)
                                            Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFF21262D)) {
                                                Text("v${module.version}", color = Color(0xFF58A6FF), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        Text(module.description, color = Color(0xFF8B949E), fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                                        Text(module.entryClass, color = Color(0xFF484F58), fontSize = 10.sp, modifier = Modifier.padding(bottom = 8.dp))

                                        Button(
                                            onClick = {
                                                if (!isDownloading) {
                                                    downloadingIds = downloadingIds + module.id
                                                    scope.launch {
                                                        try {
                                                            val wasForeground = OmniTaskManager.currentForegroundSession?.pluginId == module.id
                                                            val wasActive = OmniTaskManager.activeSessions.any { it.pluginId == module.id }

                                                            PluginTaskEngine.stopTask(context, module.id)
                                                            OmniTaskManager.activeSessions.find { it.pluginId == module.id }?.let { session ->
                                                                OmniTaskManager.killTask(context, session.taskId)
                                                            }
                                                            val installed = PluginManager.installPluginFromUrl(context, module.downloadUrl, module.name, module.entryClass)
                                                            plugins = PluginManager.getInstalledPlugins(context)
                                                            refreshRunningStates()

                                                            if (wasForeground || wasActive) {
                                                                OmniTaskManager.reloadPluginSession(
                                                                    context = context,
                                                                    pluginId = installed.id,
                                                                    pluginName = installed.name,
                                                                    entryClass = installed.entryClass,
                                                                    reopenForeground = wasForeground
                                                                )
                                                            }

                                                            Toast.makeText(context, if (isInstalled) "${module.name} Updated & Reloaded!" else "${module.name} Installed!", Toast.LENGTH_SHORT).show()
                                                        } catch (e: Exception) {
                                                            Toast.makeText(context, "Install failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                                        } finally {
                                                            downloadingIds = downloadingIds - module.id
                                                        }
                                                    }
                                                }
                                            },
                                            enabled = !isDownloading,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isInstalled) Color(0xFF238636) else Color(0xFF1F6FEB)
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth().height(36.dp)
                                        ) {
                                            if (isDownloading) {
                                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                            } else if (isInstalled) {
                                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Update / Re-install", fontSize = 12.sp)
                                            } else {
                                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Download & Install", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Omni Hub", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                            
                            // Backend Connectivity Beacon Dot
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        when (isBackendOnline) {
                                            true -> Color(0xFF238636) // Green (Online / Synced)
                                            false -> Color(0xFFDA3633) // Red (Offline)
                                            null -> Color(0xFFD29922) // Yellow (Checking)
                                        }
                                    )
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Open Cloud Store Sidebar",
                                tint = Color(0xFF58A6FF),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            plugins = PluginManager.getInstalledPlugins(context)
                            refreshRunningStates()
                            loadCloudCatalog()
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF161B22))
                )
            },
            floatingActionButton = {
                val taskCount = OmniTaskManager.activeSessions.size
                ExtendedFloatingActionButton(
                    onClick = { showTaskManagerModal = true },
                    icon = {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Recents Task Manager",
                            tint = Color.White
                        )
                    },
                    text = {
                        Text(
                            if (taskCount > 0) "Recents ($taskCount)" else "Recents",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    containerColor = if (taskCount > 0) Color(0xFF1F6FEB) else Color(0xFF21262D),
                    contentColor = Color.White
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color(0xFF0D1117))
                    .padding(16.dp)
            ) {
                Text(
                    "Installed Modules (${plugins.size})",
                    color = Color(0xFF8B949E),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                if (plugins.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No dynamic modules loaded", color = Color(0xFF8B949E), fontSize = 16.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("Import a local bundle or download via Cloud Catalog / OTA URL.", color = Color(0xFF484F58), fontSize = 13.sp)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(plugins) { plugin ->
                            val isHeadlessRunning = runningStates[plugin.id] == true
                            PluginCard(
                                plugin = plugin,
                                isHeadlessRunning = isHeadlessRunning,
                                onLaunchUI = {
                                    OmniTaskManager.launchOrResume(context, plugin.id, plugin.name, plugin.entryClass)
                                },
                                onToggleHeadless = {
                                    if (isHeadlessRunning) {
                                        PluginTaskEngine.stopTask(context, plugin.id)
                                        Toast.makeText(context, "Stopped headless task: ${plugin.name}", Toast.LENGTH_SHORT).show()
                                    } else {
                                        PluginTaskEngine.executeHeadless(context, plugin.id, plugin.entryClass)
                                        Toast.makeText(context, "Started headless task: ${plugin.name}", Toast.LENGTH_SHORT).show()
                                    }
                                    refreshRunningStates()
                                },
                                onDelete = {
                                    PluginTaskEngine.stopTask(context, plugin.id)
                                    val activeSession = OmniTaskManager.activeSessions.find { it.pluginId == plugin.id }
                                    if (activeSession != null) {
                                        OmniTaskManager.killTask(context, activeSession.taskId)
                                    }
                                    PluginManager.deletePlugin(context, plugin.id)
                                    plugins = PluginManager.getInstalledPlugins(context)
                                    refreshRunningStates()
                                    Toast.makeText(context, "Deleted ${plugin.name}", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showImportDialog && selectedZipUri != null) {
        LaunchedEffect(selectedZipUri) {
            try {
                val wasForegroundId = OmniTaskManager.currentForegroundSession?.pluginId
                val wasActiveIdList = OmniTaskManager.activeSessions.map { it.pluginId }.toSet()

                val installed = PluginManager.installPluginFromUri(context, selectedZipUri!!)
                plugins = PluginManager.getInstalledPlugins(context)
                refreshRunningStates()

                val shouldOpenForeground = wasForegroundId == installed.id || !wasActiveIdList.contains(installed.id)
                OmniTaskManager.reloadPluginSession(
                    context = context,
                    pluginId = installed.id,
                    pluginName = installed.name,
                    entryClass = installed.entryClass,
                    reopenForeground = shouldOpenForeground
                )

                Toast.makeText(context, "Installed & Launched ${installed.name}!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Install failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            showImportDialog = false
            selectedZipUri = null
        }
    }

    if (showUrlDialog) {
        DownloadUrlDialog(
            onDismiss = { showUrlDialog = false },
            onConfirm = { url ->
                showUrlDialog = false
                scope.launch {
                    try {
                        Toast.makeText(context, "Downloading OTA module...", Toast.LENGTH_SHORT).show()
                        val wasForegroundId = OmniTaskManager.currentForegroundSession?.pluginId
                        val wasActiveIdList = OmniTaskManager.activeSessions.map { it.pluginId }.toSet()

                        val installed = PluginManager.installPluginFromUrl(context, url)
                        plugins = PluginManager.getInstalledPlugins(context)
                        refreshRunningStates()

                        val shouldOpenForeground = wasForegroundId == installed.id || !wasActiveIdList.contains(installed.id)
                        OmniTaskManager.reloadPluginSession(
                            context = context,
                            pluginId = installed.id,
                            pluginName = installed.name,
                            entryClass = installed.entryClass,
                            reopenForeground = shouldOpenForeground
                        )

                        Toast.makeText(context, "Updated & Reloaded ${installed.name}!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "OTA Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    if (showLogModal) {
        LogConsoleDialog(
            onDismiss = { showLogModal = false },
            onCopy = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Omni Hub Logs", OmniLogger.getLogs())
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "All logs copied to clipboard!", Toast.LENGTH_SHORT).show()
            },
            onClear = {
                OmniLogger.clear()
            }
        )
    }

    if (showTaskManagerModal) {
        TaskManagerDialog(
            sessions = OmniTaskManager.activeSessions,
            onResumeTask = { session ->
                OmniTaskManager.resumeSession(session)
                showTaskManagerModal = false
            },
            onKillTask = { taskId ->
                OmniTaskManager.killTask(context, taskId)
            },
            onKillAll = {
                OmniTaskManager.killAllTasks(context)
                showTaskManagerModal = false
            },
            onDismiss = { showTaskManagerModal = false }
        )
    }

    // Fullscreen Active App Overlay
    if (currentSession != null) {
        key(currentSession.taskId) {
            BackHandler {
                val handled = currentSession.bridge.handleBackPressed()
                if (!handled) {
                    OmniTaskManager.suspendCurrent()
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0D1117))
            ) {
                AndroidView(
                    factory = {
                        val view = currentSession.pluginView
                        (view.parent as? ViewGroup)?.removeView(view)
                        (view as? androidx.compose.ui.platform.AbstractComposeView)?.apply {
                            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                        }
                        view.apply {
                            setViewTreeLifecycleOwner(context as? ComponentActivity)
                            setViewTreeViewModelStoreOwner(context as? ComponentActivity)
                            setViewTreeSavedStateRegistryOwner(context as? ComponentActivity)
                            isFocusable = true
                            isFocusableInTouchMode = true
                            requestFocus()
                        }
                        view
                    },
                    update = { view ->
                        view.apply {
                            setViewTreeLifecycleOwner(context as? ComponentActivity)
                            setViewTreeViewModelStoreOwner(context as? ComponentActivity)
                            setViewTreeSavedStateRegistryOwner(context as? ComponentActivity)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
}

@Composable
fun TaskManagerDialog(
    sessions: List<AppTaskSession>,
    onResumeTask: (AppTaskSession) -> Unit,
    onKillTask: (String) -> Unit,
    onKillAll: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161B22),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent Tasks", color = Color(0xFF58A6FF), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF21262D)) {
                        Text(
                            "${sessions.size}",
                            color = Color(0xFFC9D1D9),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                if (sessions.isNotEmpty()) {
                    TextButton(onClick = onKillAll) {
                        Text("Clear All", color = Color(0xFFDA3633), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        text = {
            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No recent apps in memory", color = Color(0xFF8B949E), fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("Launched apps stay here when you exit.", color = Color(0xFF484F58), fontSize = 11.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(sessions, key = { it.taskId }) { session ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1117)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onResumeTask(session) }
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF21262D))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF238636))
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            session.pluginName,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    }

                                    IconButton(
                                        onClick = { onKillTask(session.taskId) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Kill Task",
                                            tint = Color(0xFFF85149),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(110.dp)
                                        .background(Color(0xFF161B22)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (session.thumbnail != null) {
                                        Image(
                                            bitmap = session.thumbnail!!.asImageBitmap(),
                                            contentDescription = session.pluginName,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("📱", fontSize = 24.sp)
                                            Spacer(Modifier.height(4.dp))
                                            Text("Tap to resume", color = Color(0xFF8B949E), fontSize = 11.sp)
                                        }
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
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
            ) {
                Text("Close", color = Color(0xFFC9D1D9))
            }
        }
    )
}

@Composable
fun PluginCard(
    plugin: PluginMetadata,
    isHeadlessRunning: Boolean,
    onLaunchUI: () -> Unit,
    onToggleHeadless: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(plugin.installedAt) {
        SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(plugin.installedAt))
    }

    val (iconText, iconGradient) = remember(plugin.id, plugin.name) {
        when {
            plugin.id.contains("browser") || plugin.name.contains("Chrome", ignoreCase = true) ->
                "🌐" to listOf(Color(0xFF1F6FEB), Color(0xFF58A6FF))
            plugin.id.contains("ide") || plugin.name.contains("IDE", ignoreCase = true) ->
                "💻" to listOf(Color(0xFF8957E5), Color(0xFFBC8CFF))
            else ->
                "⚡" to listOf(Color(0xFF238636), Color(0xFF3FB950))
        }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        border = BorderStroke(
            1.dp,
            if (isHeadlessRunning) Color(0xFF238636).copy(alpha = 0.8f) else Color.White.copy(alpha = 0.08f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(iconGradient)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(iconText, fontSize = 22.sp)
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = plugin.name,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE8EAED)
                        )
                        if (isHeadlessRunning) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF238636).copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, Color(0xFF238636))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF3FB950))
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "ACTIVE",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF3FB950)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = plugin.description.ifEmpty { plugin.entryClass.substringAfterLast(".") },
                        fontSize = 12.sp,
                        color = Color(0xFF8B949E),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = Color(0xFFF85149).copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Installed $dateStr",
                    fontSize = 11.sp,
                    color = Color(0xFF484F58)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = onToggleHeadless,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isHeadlessRunning) Color(0xFFF85149).copy(alpha = 0.6f) else Color(0xFF30363D)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (isHeadlessRunning) Color(0xFFF85149) else Color(0xFF8B949E)
                        ),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            if (isHeadlessRunning) "Stop Task" else "Run Headless",
                            fontSize = 11.sp
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    Button(
                        onClick = onLaunchUI,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Open UI", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadUrlDialog(
    onDismiss: () -> Unit,
    onConfirm: (url: String) -> Unit
) {
    var url by remember { mutableStateOf("https://") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161B22),
        title = { Text("Download OTA Module", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Omni Hub auto-discovers plugin metadata (Name, Entry Class, Version) directly from the embedded plugin.json.",
                    fontSize = 12.sp,
                    color = Color(0xFF8B949E)
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Bundle HTTPS URL (.zip)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(url) },
                enabled = url.startsWith("https://") && url.endsWith(".zip"),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636))
            ) { Text("Download") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFF8B949E)) }
        }
    )
}

@Composable
fun LogConsoleDialog(
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit
) {
    var logsText by remember { mutableStateOf(OmniLogger.getLogs()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161B22),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Diagnostics Console", color = Color(0xFF58A6FF), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Row {
                    TextButton(onClick = {
                        onClear()
                        logsText = ""
                    }) {
                        Text("Clear", color = Color(0xFFDA3633), fontSize = 12.sp)
                    }
                    TextButton(onClick = onCopy) {
                        Text("Copy All", color = Color(0xFF238636), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(350.dp)
                    .background(Color(0xFF0D1117), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                text = logsText.ifEmpty { "No diagnostic logs recorded." },
                                color = Color(0xFFC9D1D9),
                                fontSize = 11.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
            ) {
                Text("Close")
            }
        }
    )
}