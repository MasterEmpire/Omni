package com.omni.hub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.hub.container.PluginContainerActivity
import com.omni.hub.loader.PluginManager
import com.omni.hub.loader.PluginMetadata
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
    var selectedZipUri by remember { mutableStateOf<Uri?>(null) }
    var runningStates by remember { mutableStateOf(mapOf<String, Boolean>()) }

    fun refreshRunningStates() {
        runningStates = plugins.associate { it.id to com.omni.hub.loader.PluginTaskEngine.isTaskRunning(it.id) }
    }

    fun loadCloudCatalog() {
        scope.launch {
            isCloudLoading = true
            com.omni.hub.api.OmniLogger.log("CATALOG", "Fetching live module catalog from Supabase...")
            cloudModules = fetchCloudModules()
            isBackendOnline = cloudModules.isNotEmpty() || com.omni.hub.loader.SharedLibManager.getSharedDexPaths(context).isNotEmpty()
            isCloudLoading = false
        }
    }

    // Initialize silent background runtime check and cloud sync
    LaunchedEffect(Unit) {
        com.omni.hub.api.OmniLogger.log("INIT", "Omni Hub Dashboard loaded. Ensuring shared runtime...")
        scope.launch {
            com.omni.hub.loader.SharedLibManager.ensureSharedRuntime(context)
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
                            
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF21262D)
                            ) {
                                Text(
                                    "CONTAINER",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF58A6FF)
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        // Prominent Sidebar Menu Trigger
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
                        // In-App Terminal / Diagnostic Log Viewer Button
                        TextButton(
                            onClick = { showLogModal = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD29922))
                        ) {
                            Text("📋 Logs", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        TextButton(onClick = { showUrlDialog = true }) {
                            Text("OTA URL", color = Color(0xFF58A6FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

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
            ExtendedFloatingActionButton(
                onClick = { filePicker.launch("application/zip") },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Import Bundle (.zip)") },
                containerColor = Color(0xFF238636),
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
                        Text("Tap 'Import Bundle' below to load a compiled plugin.", color = Color(0xFF484F58), fontSize = 13.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(plugins) { plugin ->
                        PluginCard(
                            plugin = plugin,
                            onLaunch = {
                                val intent = Intent(context, PluginContainerActivity::class.java).apply {
                                    putExtra(PluginContainerActivity.EXTRA_PLUGIN_ID, plugin.id)
                                    putExtra(PluginContainerActivity.EXTRA_ENTRY_CLASS, plugin.entryClass)
                                }
                                context.startActivity(intent)
                            },
                            onDelete = {
                                PluginManager.deletePlugin(context, plugin.id)
                                plugins = PluginManager.getInstalledPlugins(context)
                                Toast.makeText(context, "Deleted ${plugin.name}", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
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
                        val installed = PluginManager.installPluginFromUrl(context, url)
                        plugins = PluginManager.getInstalledPlugins(context)
                        refreshRunningStates()
                        Toast.makeText(context, "Installed ${installed.name} via OTA Manifest!", Toast.LENGTH_SHORT).show()
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
                val clip = android.content.ClipData.newPlainText("Omni Hub Logs", com.omni.hub.api.OmniLogger.getLogs())
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "All logs copied to clipboard!", Toast.LENGTH_SHORT).show()
            },
            onClear = {
                com.omni.hub.api.OmniLogger.clear()
            }
        )
    }
}

@Composable
fun LogConsoleDialog(
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit
) {
    var logsText by remember { mutableStateOf(com.omni.hub.api.OmniLogger.getLogs()) }

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

@Composable
fun PluginCard(plugin: PluginMetadata, onLaunch: () -> Unit, onDelete: () -> Unit) {
    val dateStr = remember(plugin.installedAt) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(plugin.installedAt))
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(plugin.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC9D1D9))
                Spacer(Modifier.height(4.dp))
                Text(plugin.entryClass, fontSize = 12.sp, color = Color(0xFF58A6FF))
                Spacer(Modifier.height(4.dp))
                Text("Installed: $dateStr", fontSize = 11.sp, color = Color(0xFF8B949E))
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFF85149))
            }

            Button(
                onClick = onLaunch,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Open")
            }
        }
    }
}

@Composable
fun ImportPluginDialog(
    uri: Uri,
    onDismiss: () -> Unit,
    onConfirm: (name: String, entryClass: String, description: String) -> Unit
) {
    var name by remember { mutableStateOf("SampleTool") }
    var entryClass by remember { mutableStateOf("com.omni.plugin.SampleUtility") }
    var desc by remember { mutableStateOf("Dynamic Utility Tool") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161B22),
        title = { Text("Import Plugin Bundle", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Module Display Name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                OutlinedTextField(
                    value = entryClass,
                    onValueChange = { entryClass = it },
                    label = { Text("Entry Class (FQCN)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Description") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, entryClass, desc) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636))
            ) { Text("Install") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFF8B949E)) }
        }
    )
}