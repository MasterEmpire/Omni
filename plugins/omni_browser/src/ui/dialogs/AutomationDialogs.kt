package com.omni.plugin.browser.ui.dialogs

import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.viewinterop.AndroidView
import com.omni.plugin.browser.models.BrowserProfile

@Composable
fun AutomationOrderDialog(
    profiles: List<BrowserProfile>,
    selectedProfileId: String,
    onSelectProfileId: (String) -> Unit,
    thinkingLevel: String,
    onThinkingLevelChange: (String) -> Unit,
    systemPrompt: String,
    onSystemPromptChange: (String) -> Unit,
    userPrompt: String,
    onUserPromptChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onRun: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF282C34),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🤖", fontSize = 22.sp)
                Spacer(Modifier.width(8.dp))
                Text("AI Studio Automator", color = Color(0xFFE8EAED), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)
            ) {
                Text("Configure and dispatch prompts directly to Google AI Studio headlessly using your active profile cookies.", color = Color(0xFF9AA0A6), fontSize = 11.sp)

                // Profile Picker
                Column {
                    Text("Target Profile (Account)", color = Color(0xFF8AB4F8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        profiles.forEach { prof ->
                            val isSel = prof.id == selectedProfileId
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) Color(prof.colorValue).copy(alpha = 0.25f) else Color(0xFF1F2227),
                                border = BorderStroke(1.dp, if (isSel) Color(prof.colorValue) else Color(0xFF3C4043)),
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSelectProfileId(prof.id) }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(prof.colorValue)))
                                    Spacer(Modifier.width(6.dp))
                                    Text(prof.name, color = Color(0xFFE8EAED), fontSize = 11.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }

                // Thinking Level Picker
                Column {
                    Text("Thinking Level", color = Color(0xFF8AB4F8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("High", "Low", "Default").forEach { level ->
                            val isSel = thinkingLevel == level
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) Color(0xFF8AB4F8).copy(alpha = 0.25f) else Color(0xFF1F2227),
                                border = BorderStroke(1.dp, if (isSel) Color(0xFF8AB4F8) else Color(0xFF3C4043)),
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { onThinkingLevelChange(level) }
                            ) {
                                Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                    Text(level, color = if (isSel) Color(0xFF8AB4F8) else Color(0xFF9AA0A6), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // System Prompt (Optional)
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = onSystemPromptChange,
                    label = { Text("System Instructions (Optional)") },
                    placeholder = { Text("You are a specialized assistant...", color = Color(0xFF5F6368), fontSize = 12.sp) },
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFE8EAED),
                        unfocusedTextColor = Color(0xFFE8EAED),
                        focusedBorderColor = Color(0xFF8AB4F8),
                        unfocusedBorderColor = Color(0xFF3C4043)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // User Prompt
                OutlinedTextField(
                    value = userPrompt,
                    onValueChange = onUserPromptChange,
                    label = { Text("User Prompt") },
                    placeholder = { Text("Enter prompt to run headlessly...", color = Color(0xFF5F6368), fontSize = 12.sp) },
                    minLines = 3,
                    maxLines = 6,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFE8EAED),
                        unfocusedTextColor = Color(0xFFE8EAED),
                        focusedBorderColor = Color(0xFF8AB4F8),
                        unfocusedBorderColor = Color(0xFF3C4043)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onRun,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8AB4F8)),
                enabled = userPrompt.trim().isNotEmpty()
            ) {
                Text("⚡ Run Automation", color = Color(0xFF1F2227), fontWeight = FontWeight.Bold)
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
fun AutomationResultDialog(
    isAutomating: Boolean,
    automationStatus: String,
    automationThoughts: String,
    automationResult: String,
    automationError: String?,
    automationElapsedSec: Int,
    automationWebView: WebView? = null,
    onDumpDom: () -> Unit,
    onCopyResult: () -> Unit,
    onCloseOrStop: () -> Unit
) {
    var thoughtsExpanded by remember { mutableStateOf(false) }
    var activeTabMode by remember { mutableStateOf("viewport") } // "viewport" vs "stream"

    AlertDialog(
        onDismissRequest = {
            if (!isAutomating) onCloseOrStop()
        },
        containerColor = Color(0xFF282C34),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isAutomating) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color(0xFF8AB4F8), strokeWidth = 2.dp)
                    } else if (automationError != null) {
                        Text("❌", fontSize = 18.sp)
                    } else {
                        Text("✅", fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isAutomating) "AI Studio Live Runner" else if (automationError != null) "Execution Error" else "AI Studio Response",
                        color = Color(0xFFE8EAED),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF1F2227)) {
                    Text("${automationElapsedSec}s", color = Color(0xFF8AB4F8), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)
            ) {
                // Mode Toggle Segmented Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1F2227))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (activeTabMode == "viewport") Color(0xFF8AB4F8).copy(alpha = 0.2f) else Color.Transparent,
                        border = if (activeTabMode == "viewport") BorderStroke(1.dp, Color(0xFF8AB4F8)) else null,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { activeTabMode = "viewport" }
                    ) {
                        Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "👁️ Live Viewport",
                                color = if (activeTabMode == "viewport") Color(0xFF8AB4F8) else Color(0xFF9AA0A6),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (activeTabMode == "stream") Color(0xFF8AB4F8).copy(alpha = 0.2f) else Color.Transparent,
                        border = if (activeTabMode == "stream") BorderStroke(1.dp, Color(0xFF8AB4F8)) else null,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { activeTabMode = "stream" }
                    ) {
                        Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "📝 Output & Stream",
                                color = if (activeTabMode == "stream") Color(0xFF8AB4F8) else Color(0xFF9AA0A6),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Live Viewport Display
                if (activeTabMode == "viewport") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF16181D))
                            .border(1.dp, Color(0xFF3C4043), RoundedCornerShape(8.dp))
                    ) {
                        if (automationWebView != null) {
                            AndroidView(
                                factory = { ctx ->
                                    FrameLayout(ctx).apply {
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                        (automationWebView.parent as? ViewGroup)?.removeView(automationWebView)
                                        addView(
                                            automationWebView,
                                            ViewGroup.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                        )
                                    }
                                },
                                update = { frame ->
                                    if (automationWebView.parent != frame) {
                                        (automationWebView.parent as? ViewGroup)?.removeView(automationWebView)
                                        frame.removeAllViews()
                                        frame.addView(
                                            automationWebView,
                                            ViewGroup.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No active automation WebView attached.", color = Color(0xFF9AA0A6), fontSize = 12.sp)
                            }
                        }

                        // Floating Glassmorphic Status Pill
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1F2227).copy(alpha = 0.9f),
                            border = BorderStroke(1.dp, if (automationError != null) Color(0xFFF28B82) else Color(0xFF8AB4F8).copy(alpha = 0.6f)),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp, start = 8.dp, end = 8.dp)
                        ) {
                            Text(
                                text = automationError ?: "⚡ $automationStatus",
                                color = if (automationError != null) Color(0xFFF28B82) else Color(0xFF8AB4F8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                maxLines = 1
                            )
                        }
                    }
                } else {
                    // Output & Stream Display
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (automationError != null) Color(0xFF4C1C1C) else Color(0xFF1F2227),
                        border = BorderStroke(1.dp, if (automationError != null) Color(0xFFF28B82) else Color(0xFF3C4043)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = automationError ?: "Status: $automationStatus",
                            color = if (automationError != null) Color(0xFFF28B82) else Color(0xFF8AB4F8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(8.dp)
                        )
                    }

                    if (automationThoughts.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1F2227),
                            border = BorderStroke(1.dp, Color(0xFF3C4043)),
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { thoughtsExpanded = !thoughtsExpanded }
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("🧠", fontSize = 14.sp)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Model Reasoning / Thoughts", color = Color(0xFFE8EAED), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Icon(
                                        if (thoughtsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = Color(0xFF9AA0A6),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                if (thoughtsExpanded) {
                                    Spacer(Modifier.height(6.dp))
                                    androidx.compose.foundation.text.selection.SelectionContainer {
                                        Text(
                                            text = automationThoughts,
                                            color = Color(0xFF9AA0A6),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = 15.sp,
                                            modifier = Modifier.fillMaxWidth().heightIn(max = 100.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(230.dp)
                            .background(Color(0xFF16181D), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF3C4043), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Text(
                                        text = if (automationResult.isNotEmpty()) automationResult else if (isAutomating) "Listening for response stream from AI Studio..." else "No output generated.",
                                        color = Color(0xFFE8EAED),
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDumpDom,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF8AB4F8))
                ) {
                    Text("🔍 Dump DOM", fontSize = 11.sp)
                }

                if (automationResult.isNotEmpty()) {
                    Button(
                        onClick = onCopyResult,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636))
                    ) {
                        Text("📋 Copy", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = onCloseOrStop,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3C4043))
                ) {
                    Text(if (isAutomating) "Stop" else "Close", color = Color.White)
                }
            }
        }
    )
}