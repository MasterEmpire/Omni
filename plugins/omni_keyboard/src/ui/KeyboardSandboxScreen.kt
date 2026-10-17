package com.omni.plugin.keyboard.ui

import android.content.Context
import android.provider.Settings
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.hub.api.HostBridge
import com.omni.hub.api.KeyboardHost
import com.omni.plugin.keyboard.state.KeyboardState
import java.io.File

class SandboxHost(
    private val context: Context,
    private val bridge: HostBridge
) : KeyboardHost {
    override fun getHostContext(): Context = context
    override fun getHostBridge(): HostBridge = bridge
    override fun getCurrentInputConnection(): InputConnection? = null
    override fun getCurrentEditorInfo(): EditorInfo? = null
    override fun getPersistentDictDir(): File = File(bridge.getPluginDir(), "dicts").apply { if (!exists()) mkdirs() }
    override fun switchInputMethod(imeId: String?) {
        bridge.runIntent(Settings.ACTION_INPUT_METHOD_SETTINGS, null, null)
    }
    override fun sendHostKeyEvent(event: KeyEvent) {}
    override fun performHostEditorAction(action: Int): Boolean = false
    override fun requestHideKeyboard() {}
}

@Composable
fun KeyboardSandboxScreen(
    context: Context,
    bridge: HostBridge,
    baseDir: String
) {
    var typedText by remember { mutableStateOf("") }
    val sandboxHost = remember { SandboxHost(context, bridge) }
    val keyboardState = remember {
        KeyboardState(sandboxHost).apply {
            onSandboxCommit = { typedText += it }
            onSandboxDelete = {
                if (typedText.isNotEmpty()) {
                    typedText = typedText.dropLast(1)
                }
            }
            onSandboxEnter = { typedText += "\n" }
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF0D1117),
            surface = Color(0xFF161B22),
            primary = Color(0xFF58A6FF)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D1117))
                .statusBarsPadding()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⌨️", fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Omni Board Studio", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Interactive Sandbox & System Controls", color = Color(0xFF8B949E), fontSize = 11.sp)
                    }
                }
                IconButton(onClick = { bridge.close() }) {
                    Text("✕", color = Color(0xFF8B949E), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Quick Setup Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        bridge.runIntent(Settings.ACTION_INPUT_METHOD_SETTINGS, null, null)
                        bridge.showToast("Enable 'Omni Board' in the on-screen keyboard list")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(36.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("1. Enable in Settings", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                        imm?.showInputMethodPicker()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(36.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("2. Switch Keyboard", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Real-Time Typed Text Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF161B22))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("TEST INPUT BUFFER", color = Color(0xFF8B949E), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        if (typedText.isNotEmpty()) {
                            Text(
                                text = "Clear",
                                color = Color(0xFFDA3633),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { typedText = "" }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = if (typedText.isEmpty()) "Tap any key below to test typing in real time...\nToggle አማርኛ, ?123, Caps, Number Row, or Text Editor mode." else typedText,
                            color = if (typedText.isEmpty()) Color(0xFF484F58) else Color(0xFFE2E2E6),
                            fontSize = 15.sp,
                            fontFamily = FontFamily.SansSerif,
                            lineHeight = 22.sp
                        )
                    }
                }
            }

            // Embedded Interactive Keyboard
            KeyboardScreen(state = keyboardState)
        }
    }
}