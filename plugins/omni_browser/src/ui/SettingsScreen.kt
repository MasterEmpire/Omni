package com.omni.plugin.browser.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.hub.api.HostBridge

@Composable
fun SettingsScreen(
    bridge: HostBridge,
    apiKey: String,
    autoSolve: Boolean,
    forceDark: Boolean,
    localPort: Int,
    isErudaEnabled: Boolean,
    isDesktopMode: Boolean,
    isBackgroundAudioEnabled: Boolean,
    dataSaverMode: String,
    filePickerProvider: String,
    blockAiStudioBack: Boolean,
    geminiApiKey: String,
    autoReadAloud: Boolean,
    geminiVoiceName: String,
    readAloudEnabled: Boolean,
    showReadAloudPill: Boolean,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onSolveNow: () -> Unit,
    onClearCookiesAndCache: () -> Unit,
    onCustomizeMenu: () -> Unit,
    onApplyAll: (
        apiKey: String,
        autoSolve: Boolean,
        forceDark: Boolean,
        localPort: Int,
        eruda: Boolean,
        desktop: Boolean,
        backgroundAudio: Boolean,
        dataSaverMode: String,
        filePickerProvider: String,
        blockAiStudioBack: Boolean,
        geminiApiKey: String,
        autoReadAloud: Boolean,
        geminiVoiceName: String,
        readAloudEnabled: Boolean,
        showReadAloudPill: Boolean
    ) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var searchQuery by remember { mutableStateOf("") }
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

    var showGeminiKey by remember { mutableStateOf(false) }
    var showSolverKey by remember { mutableStateOf(false) }
    var confirmClearCache by remember { mutableStateOf(false) }

    fun commitNow() {
        val portNum = tempPortText.toIntOrNull()?.coerceIn(1024, 65535) ?: localPort
        onApplyAll(
            tempKey.trim(),
            tempAuto,
            tempForceDark,
            portNum,
            tempEruda,
            tempDesktop,
            tempAudio,
            tempDataSaverMode,
            tempPickerProvider,
            tempBlockAiStudioBack,
            tempGeminiApiKey.trim(),
            tempAutoReadAloud,
            tempGeminiVoiceName,
            tempReadAloudEnabled,
            tempShowReadAloudPill
        )
    }

    // Ensure typed API keys / port changes are never lost on system back
    DisposableEffect(Unit) {
        onDispose {
            commitNow()
        }
    }

    val q = searchQuery.trim().lowercase()
    fun matches(vararg keywords: String): Boolean {
        if (q.isEmpty()) return true
        return keywords.any { it.lowercase().contains(q) }
    }

    val showAppearance = matches("appearance", "layout", "menu", "bottom", "top", "bar", "grid", "dark", "theme", "desktop", "viewport")
    val showAiVoice = matches("ai studio", "read aloud", "voice", "gemini", "live", "pill", "auto-read", "persona", "aoede", "puck", "kore", "fenrir", "back guard", "api key")
    val showNetwork = matches("data", "saver", "extreme", "medium", "audio", "background", "media", "upload", "file", "picker", "nexus", "system")
    val showDev = matches("developer", "eruda", "devtools", "console", "port", "localhost", "captcha", "nocaptcha", "solver", "api key")
    val showBackup = matches("backup", "export", "restore", "zip", "vault", "privacy", "cookies", "cache", "clear")

    Surface(
        color = Color(0xFF16181D),
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top AppBar
            Surface(
                color = Color(0xFF1B1F26),
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    commitNow()
                                    onClose()
                                }
                            ) {
                                Icon(
                                    BrowserIcons.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color(0xFFE8EAED),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Settings",
                                color = Color(0xFFE8EAED),
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF238636).copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, Color(0xFF3FB950).copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = "Auto-Saved",
                                color = Color(0xFF3FB950),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    // Search Filter Pill
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search settings (e.g. voice, dark, port, layout)...", color = Color(0xFF8B949E), fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(BrowserIcons.Search, contentDescription = null, tint = Color(0xFF8AB4F8), modifier = Modifier.size(17.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(28.dp)) {
                                    Icon(BrowserIcons.Close, contentDescription = "Clear", tint = Color(0xFF8B949E), modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF16181D),
                            unfocusedContainerColor = Color(0xFF16181D),
                            focusedTextColor = Color(0xFFE8EAED),
                            unfocusedTextColor = Color(0xFFE8EAED),
                            focusedBorderColor = Color(0xFF58A6FF),
                            unfocusedBorderColor = Color(0xFF30363D)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF282C34))

            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // 1. APPEARANCE & LAYOUT
                if (showAppearance) {
                    item(key = "cat_appearance") {
                        SettingsSectionCard(
                            icon = BrowserIcons.LayoutGrid,
                            title = "Appearance & Layout",
                            accent = Color(0xFF8AB4F8)
                        ) {
                            if (matches("layout", "menu", "bottom", "top", "bar", "grid", "customize")) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            commitNow()
                                            onCustomizeMenu()
                                        }
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Bars & Menu Layout", color = Color(0xFFE8EAED), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        Text("Drag & drop Top Left/Right, Bottom Bar & 5x2 Grid", color = Color(0xFF8B949E), fontSize = 11.sp)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF1F6FEB),
                                        modifier = Modifier.padding(start = 8.dp)
                                    ) {
                                        Text(
                                            "Customize",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                                HorizontalDivider(color = Color(0xFF2B303A))
                            }

                            if (matches("dark", "theme", "appearance")) {
                                SettingsSwitchRow(
                                    title = "Force Dark Web Content",
                                    subtitle = "Inverts blinding light web pages into dark mode",
                                    checked = tempForceDark,
                                    accent = Color(0xFF8AB4F8),
                                    onCheckedChange = {
                                        tempForceDark = it
                                        commitNow()
                                    }
                                )
                            }

                            if (matches("desktop", "viewport", "appearance")) {
                                if (matches("dark", "theme", "appearance")) HorizontalDivider(color = Color(0xFF2B303A))
                                SettingsSwitchRow(
                                    title = "Desktop Site (1280px Viewport)",
                                    subtitle = "Spoofs desktop user agent and wide layout width",
                                    checked = tempDesktop,
                                    accent = Color(0xFF8AB4F8),
                                    onCheckedChange = {
                                        tempDesktop = it
                                        commitNow()
                                    }
                                )
                            }
                        }
                    }
                }

                // 2. AI STUDIO & READ ALOUD
                if (showAiVoice) {
                    item(key = "cat_ai_voice") {
                        SettingsSectionCard(
                            icon = BrowserIcons.VolumeUp,
                            title = "AI Studio & Read Aloud (Gemini 3.8 Live)",
                            accent = Color(0xFF00E5FF)
                        ) {
                            if (matches("enable", "read aloud", "voice", "ai studio")) {
                                SettingsSwitchRow(
                                    title = "Enable Read Aloud",
                                    subtitle = "Injects Read buttons on AI Studio bubbles and enables live voice",
                                    checked = tempReadAloudEnabled,
                                    accent = Color(0xFF00E5FF),
                                    onCheckedChange = {
                                        tempReadAloudEnabled = it
                                        commitNow()
                                    }
                                )
                                HorizontalDivider(color = Color(0xFF2B303A))
                            }

                            if (matches("pill", "floating", "read aloud", "voice")) {
                                SettingsSwitchRow(
                                    title = "Show Floating Read Pill",
                                    subtitle = "Draggable Read Last pill over AI Studio",
                                    checked = tempShowReadAloudPill,
                                    enabled = tempReadAloudEnabled,
                                    accent = Color(0xFF00E5FF),
                                    onCheckedChange = {
                                        tempShowReadAloudPill = it
                                        commitNow()
                                    }
                                )
                                HorizontalDivider(color = Color(0xFF2B303A))
                            }

                            if (matches("auto", "read aloud", "voice")) {
                                SettingsSwitchRow(
                                    title = "Auto-Read Aloud on New Response",
                                    subtitle = "Speaks newly arrived AI Studio replies automatically",
                                    checked = tempAutoReadAloud,
                                    enabled = tempReadAloudEnabled,
                                    accent = Color(0xFF00E5FF),
                                    onCheckedChange = {
                                        tempAutoReadAloud = it
                                        commitNow()
                                    }
                                )
                                HorizontalDivider(color = Color(0xFF2B303A))
                            }

                            if (matches("gemini", "api", "key", "voice", "read aloud")) {
                                Spacer(Modifier.height(6.dp))
                                Text("Gemini API Key", color = Color(0xFFE8EAED), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = tempGeminiApiKey,
                                    onValueChange = { tempGeminiApiKey = it },
                                    placeholder = { Text("AIza...", color = Color(0xFF5F6368), fontSize = 12.sp) },
                                    singleLine = true,
                                    visualTransformation = if (showGeminiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(
                                                onClick = { showGeminiKey = !showGeminiKey },
                                                contentPadding = PaddingValues(horizontal = 6.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text(if (showGeminiKey) "Hide" else "Show", color = Color(0xFF8B949E), fontSize = 10.sp)
                                            }
                                            TextButton(
                                                onClick = {
                                                    val clip = bridge.getClipboardText()?.trim() ?: ""
                                                    if (clip.isNotEmpty()) {
                                                        tempGeminiApiKey = clip
                                                        commitNow()
                                                        Toast.makeText(context, "Pasted & saved Gemini key", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 6.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text("Paste", color = Color(0xFF00E5FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                            IconButton(
                                                onClick = {
                                                    commitNow()
                                                    Toast.makeText(context, "Gemini API Key saved", Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.size(30.dp)
                                            ) {
                                                Icon(Icons.Default.Check, contentDescription = "Apply", tint = Color(0xFF3FB950), modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color(0xFFE8EAED),
                                        unfocusedTextColor = Color(0xFFE8EAED),
                                        focusedBorderColor = Color(0xFF00E5FF),
                                        unfocusedBorderColor = Color(0xFF3C4043)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            if (matches("persona", "voice", "aoede", "puck", "kore", "fenrir", "read aloud")) {
                                Text("Voice Persona", color = Color(0xFF9AA0A6), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf("Default", "Aoede", "Puck", "Kore", "Fenrir").forEach { vName ->
                                        val isSel = tempGeminiVoiceName == vName
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSel) Color(0xFF00E5FF).copy(alpha = 0.22f) else Color(0xFF16181D),
                                            border = BorderStroke(1.dp, if (isSel) Color(0xFF00E5FF) else Color(0xFF3C4043)),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    tempGeminiVoiceName = vName
                                                    commitNow()
                                                }
                                        ) {
                                            Box(modifier = Modifier.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
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
                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider(color = Color(0xFF2B303A))
                            }

                            if (matches("back", "guard", "ai studio")) {
                                SettingsSwitchRow(
                                    title = "AI Studio Back Guard",
                                    subtitle = "Requires double-tap back on AI Studio to protect active sessions",
                                    checked = tempBlockAiStudioBack,
                                    accent = Color(0xFF8AB4F8),
                                    onCheckedChange = {
                                        tempBlockAiStudioBack = it
                                        commitNow()
                                    }
                                )
                            }
                        }
                    }
                }

                // 3. NETWORK, MEDIA & UPLOADS
                if (showNetwork) {
                    item(key = "cat_network") {
                        SettingsSectionCard(
                            icon = BrowserIcons.Shield,
                            title = "Network, Media & Uploads",
                            accent = Color(0xFF3FB950)
                        ) {
                            if (matches("data", "saver", "extreme", "medium", "network")) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Data-Saver Mode", color = Color(0xFFE8EAED), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = tempDataSaverMode,
                                        color = when (tempDataSaverMode) {
                                            "EXTREME" -> Color(0xFF3FB950)
                                            "MEDIUM" -> Color(0xFF58A6FF)
                                            else -> Color(0xFF8B949E)
                                        },
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = when (tempDataSaverMode) {
                                        "EXTREME" -> "Blocks images, custom web fonts, media preloads & ad trackers on mobile data."
                                        "MEDIUM" -> "Keeps images visible; blocks heavy fonts, media preloads & trackers on mobile data."
                                        else -> "Off: full unthrottled web fidelity."
                                    },
                                    color = Color(0xFF8B949E),
                                    fontSize = 11.sp
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("OFF" to "Off", "MEDIUM" to "Medium", "EXTREME" to "Extreme").forEach { (modeKey, modeLabel) ->
                                        val isSel = tempDataSaverMode == modeKey
                                        val col = when (modeKey) {
                                            "EXTREME" -> Color(0xFF3FB950)
                                            "MEDIUM" -> Color(0xFF58A6FF)
                                            else -> Color(0xFF8AB4F8)
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSel) col.copy(alpha = 0.22f) else Color(0xFF16181D),
                                            border = BorderStroke(1.dp, if (isSel) col else Color(0xFF3C4043)),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    tempDataSaverMode = modeKey
                                                    commitNow()
                                                }
                                        ) {
                                            Box(modifier = Modifier.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
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
                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider(color = Color(0xFF2B303A))
                            }

                            if (matches("audio", "background", "media")) {
                                SettingsSwitchRow(
                                    title = "Background Audio Playback",
                                    subtitle = "Keeps media playing when screen is off or switched",
                                    checked = tempAudio,
                                    accent = Color(0xFF3FB950),
                                    onCheckedChange = {
                                        tempAudio = it
                                        commitNow()
                                    }
                                )
                                HorizontalDivider(color = Color(0xFF2B303A))
                            }

                            if (matches("upload", "file", "picker", "nexus", "system")) {
                                Spacer(Modifier.height(6.dp))
                                Text("File Upload Provider", color = Color(0xFFE8EAED), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("File explorer launched when tapping upload buttons on sites", color = Color(0xFF8B949E), fontSize = 11.sp)
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (tempPickerProvider == "NEXUS") Color(0xFF00E5FF).copy(alpha = 0.2f) else Color(0xFF16181D),
                                        border = BorderStroke(1.dp, if (tempPickerProvider == "NEXUS") Color(0xFF00E5FF) else Color(0xFF3C4043)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                tempPickerProvider = "NEXUS"
                                                commitNow()
                                            }
                                    ) {
                                        Box(modifier = Modifier.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                                            Text("Conduit Nexus", color = if (tempPickerProvider == "NEXUS") Color(0xFF00E5FF) else Color(0xFF9AA0A6), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (tempPickerProvider == "SYSTEM") Color(0xFF8AB4F8).copy(alpha = 0.2f) else Color(0xFF16181D),
                                        border = BorderStroke(1.dp, if (tempPickerProvider == "SYSTEM") Color(0xFF8AB4F8) else Color(0xFF3C4043)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                tempPickerProvider = "SYSTEM"
                                                commitNow()
                                            }
                                    ) {
                                        Box(modifier = Modifier.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                                            Text("Android System", color = if (tempPickerProvider == "SYSTEM") Color(0xFF8AB4F8) else Color(0xFF9AA0A6), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. DEVELOPER & SOLVER
                if (showDev) {
                    item(key = "cat_dev") {
                        SettingsSectionCard(
                            icon = BrowserIcons.Terminal,
                            title = "Developer & CAPTCHA Solver",
                            accent = Color(0xFFD29922)
                        ) {
                            if (matches("eruda", "devtools", "console", "developer")) {
                                SettingsSwitchRow(
                                    title = "Eruda DevTools Console",
                                    subtitle = "Injects floating in-page inspection console on pages",
                                    checked = tempEruda,
                                    accent = Color(0xFFD29922),
                                    onCheckedChange = {
                                        tempEruda = it
                                        commitNow()
                                    }
                                )
                                HorizontalDivider(color = Color(0xFF2B303A))
                            }

                            if (matches("port", "localhost", "developer")) {
                                Spacer(Modifier.height(6.dp))
                                Text("Localhost Server Port", color = Color(0xFFE8EAED), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = tempPortText,
                                    onValueChange = { tempPortText = it.filter { ch -> ch.isDigit() }.take(5) },
                                    placeholder = { Text("8080", color = Color(0xFF5F6368), fontSize = 12.sp) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    trailingIcon = {
                                        IconButton(
                                            onClick = {
                                                commitNow()
                                                Toast.makeText(context, "Port updated to ${tempPortText.ifEmpty { "8080" }}", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = "Apply Port", tint = Color(0xFF3FB950), modifier = Modifier.size(18.dp))
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color(0xFFE8EAED),
                                        unfocusedTextColor = Color(0xFFE8EAED),
                                        focusedBorderColor = Color(0xFFD29922),
                                        unfocusedBorderColor = Color(0xFF3C4043)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider(color = Color(0xFF2B303A))
                            }

                            if (matches("captcha", "nocaptcha", "solver", "api key")) {
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("NoCaptchaAI Solver", color = Color(0xFFE8EAED), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    TextButton(
                                        onClick = {
                                            commitNow()
                                            onSolveNow()
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text("Solve Now", color = Color(0xFF81C995), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                OutlinedTextField(
                                    value = tempKey,
                                    onValueChange = { tempKey = it },
                                    label = { Text("NoCaptchaAI API Key") },
                                    singleLine = true,
                                    visualTransformation = if (showSolverKey) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(
                                                onClick = { showSolverKey = !showSolverKey },
                                                contentPadding = PaddingValues(horizontal = 6.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text(if (showSolverKey) "Hide" else "Show", color = Color(0xFF8B949E), fontSize = 10.sp)
                                            }
                                            TextButton(
                                                onClick = {
                                                    val clip = bridge.getClipboardText()?.trim() ?: ""
                                                    if (clip.isNotEmpty()) {
                                                        tempKey = clip
                                                        commitNow()
                                                        Toast.makeText(context, "Pasted & saved solver key", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 6.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text("Paste", color = Color(0xFF8AB4F8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                            IconButton(
                                                onClick = {
                                                    commitNow()
                                                    Toast.makeText(context, "Solver key saved", Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.size(30.dp)
                                            ) {
                                                Icon(Icons.Default.Check, contentDescription = "Apply", tint = Color(0xFF3FB950), modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color(0xFFE8EAED),
                                        unfocusedTextColor = Color(0xFFE8EAED),
                                        focusedBorderColor = Color(0xFFD29922),
                                        unfocusedBorderColor = Color(0xFF3C4043)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                SettingsSwitchRow(
                                    title = "Auto-Solve on Page Load",
                                    subtitle = "Automatically attempts CAPTCHA solving when detected",
                                    checked = tempAuto,
                                    accent = Color(0xFFD29922),
                                    onCheckedChange = {
                                        tempAuto = it
                                        commitNow()
                                    }
                                )
                            }
                        }
                    }
                }

                // 5. BACKUP & PRIVACY
                if (showBackup) {
                    item(key = "cat_backup") {
                        SettingsSectionCard(
                            icon = BrowserIcons.ArchiveBox,
                            title = "Backup & Privacy",
                            accent = Color(0xFF58A6FF)
                        ) {
                            if (matches("backup", "export", "restore", "zip", "vault")) {
                                Text(
                                    "Full ZIP backup of profiles, tabs, shortcuts, layout & vaults. Auto-mirrored to Documents/.omni_vault/.",
                                    color = Color(0xFF8B949E),
                                    fontSize = 11.sp
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            commitNow()
                                            onExportBackup()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Export ZIP", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }

                                    OutlinedButton(
                                        onClick = onRestoreBackup,
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF8AB4F8)),
                                        border = BorderStroke(1.dp, Color(0xFF58A6FF).copy(alpha = 0.6f)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Restore ZIP", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider(color = Color(0xFF2B303A))
                            }

                            if (matches("privacy", "cookies", "cache", "clear")) {
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { confirmClearCache = true }
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Clear Cookies & Cache", color = Color(0xFFF28B82), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        Text("Wipes browser cookies and cached web files", color = Color(0xFF8B949E), fontSize = 11.sp)
                                    }
                                    Text("Clear", color = Color(0xFFF28B82), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                if (!showAppearance && !showAiVoice && !showNetwork && !showDev && !showBackup) {
                    item(key = "no_results") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No settings match \"$searchQuery\"", color = Color(0xFF8B949E), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    if (confirmClearCache) {
        AlertDialog(
            onDismissRequest = { confirmClearCache = false },
            containerColor = Color(0xFF282C34),
            title = { Text("Clear Cookies & Cache?", color = Color(0xFFE8EAED), fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Text("This will sign you out of websites (including Google AI Studio) and clear WebView cache.", color = Color(0xFF9AA0A6), fontSize = 12.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClearCache = false
                        onClearCookiesAndCache()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDA3633))
                ) {
                    Text("Clear All", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearCache = false }) {
                    Text("Cancel", color = Color(0xFF9AA0A6))
                }
            }
        )
    }
}

@Composable
private fun SettingsSectionCard(
    icon: ImageVector,
    title: String,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1E222A),
        border = BorderStroke(1.dp, Color(0xFF2E333D)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                text = title,
                color = if (enabled) Color(0xFFE8EAED) else Color(0xFF5F6368),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = if (enabled) Color(0xFF8B949E) else Color(0xFF484F58),
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = accent)
        )
    }
}