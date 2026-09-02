package com.omni.plugin.browser.ui.dialogs

import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import com.omni.plugin.browser.models.AutomationAttachment
import com.omni.plugin.browser.models.SequentialPromptStep
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

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) String.format(java.util.Locale.US, "%.1f MB", mb) else String.format(java.util.Locale.US, "%.0f KB", kb)
}

@Composable
private fun ProfilePickerSection(
    profiles: List<BrowserProfile>,
    selectedProfileId: String,
    onSelectProfileId: (String) -> Unit
) {
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
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color(prof.colorValue)))
                        Spacer(Modifier.width(6.dp))
                        Text(prof.name, color = Color(0xFFE8EAED), fontSize = 11.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingLevelSection(
    thinkingLevel: String,
    onThinkingLevelChange: (String) -> Unit
) {
    Column {
        Text("Thinking Level", color = Color(0xFF8AB4F8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Default", "High", "Low", "Minimal").forEach { level ->
                val isSel = thinkingLevel == level
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSel) Color(0xFF8AB4F8).copy(alpha = 0.25f) else Color(0xFF1F2227),
                    border = BorderStroke(1.dp, if (isSel) Color(0xFF8AB4F8) else Color(0xFF3C4043)),
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { onThinkingLevelChange(level) }
                ) {
                    Box(modifier = Modifier.padding(vertical = 5.dp), contentAlignment = Alignment.Center) {
                        Text(level, color = if (isSel) Color(0xFF8AB4F8) else Color(0xFF9AA0A6), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TemporaryChatSection(
    temporaryChat: Boolean,
    onTemporaryChatChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (temporaryChat) Color(0xFF8AB4F8).copy(alpha = 0.15f) else Color(0xFF1F2227),
        border = BorderStroke(1.dp, if (temporaryChat) Color(0xFF8AB4F8) else Color(0xFF3C4043)),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onTemporaryChatChange(!temporaryChat) }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🕵️", fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Temporary Chat (Incognito)", color = if (temporaryChat) Color(0xFF8AB4F8) else Color(0xFFE8EAED), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Do not save prompt or responses to Google Drive", color = Color(0xFF9AA0A6), fontSize = 10.sp)
                }
            }
            Switch(
                checked = temporaryChat,
                onCheckedChange = onTemporaryChatChange,
                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF8AB4F8)),
                modifier = Modifier.height(24.dp)
            )
        }
    }
}

@Composable
private fun AttachmentsSection(
    attachments: List<AutomationAttachment>,
    onPickFiles: () -> Unit,
    onRemoveAttachment: (String) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📎 Attachments", color = Color(0xFF8AB4F8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (attachments.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF1F2227)) {
                        Text("${attachments.size}", color = Color(0xFF81C995), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }

            TextButton(
                onClick = onPickFiles,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF8AB4F8))
                Spacer(Modifier.width(4.dp))
                Text("Add Files", color = Color(0xFF8AB4F8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                attachments.forEach { fileItem ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1F2227),
                        border = BorderStroke(1.dp, Color(0xFF3C4043)),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                        ) {
                            Text(
                                when {
                                    fileItem.mimeType.startsWith("image/") -> "🖼️"
                                    fileItem.mimeType.contains("pdf") -> "📄"
                                    fileItem.mimeType.startsWith("video/") -> "🎬"
                                    fileItem.mimeType.startsWith("audio/") -> "🎵"
                                    else -> "📁"
                                },
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(
                                    fileItem.name.take(16),
                                    color = Color(0xFFE8EAED),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                                Text(
                                    formatFileSize(fileItem.sizeBytes),
                                    color = Color(0xFF9AA0A6),
                                    fontSize = 9.sp
                                )
                            }
                            IconButton(
                                onClick = { onRemoveAttachment(fileItem.id) },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove",
                                    tint = Color(0xFFF28B82),
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemPresetsSection(
    systemPresets: List<com.omni.plugin.browser.models.SystemInstructionPreset>,
    systemPromptTitle: String,
    onSystemPromptTitleChange: (String) -> Unit,
    systemPrompt: String,
    onSystemPromptChange: (String) -> Unit,
    fallbackEnabled: Boolean,
    onFallbackEnabledChange: (Boolean) -> Unit,
    onSavePreset: (String, String) -> Unit,
    onSelectPreset: (com.omni.plugin.browser.models.SystemInstructionPreset) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("System Instructions Preset", color = Color(0xFF8AB4F8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            if (systemPromptTitle.isNotEmpty() || systemPrompt.isNotEmpty()) {
                TextButton(
                    onClick = { onSavePreset(systemPromptTitle, systemPrompt) },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("💾 Save Preset", color = Color(0xFF81C995), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (systemPresets.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (systemPromptTitle.isEmpty()) Color(0xFF8AB4F8).copy(alpha = 0.2f) else Color(0xFF1F2227),
                    border = BorderStroke(1.dp, if (systemPromptTitle.isEmpty()) Color(0xFF8AB4F8) else Color(0xFF3C4043)),
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                        onSystemPromptTitleChange("")
                        onSystemPromptChange("")
                    }
                ) {
                    Text("➕ Custom", color = Color(0xFFE8EAED), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                }

                systemPresets.take(3).forEach { preset ->
                    val isSel = systemPromptTitle.equals(preset.title, ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSel) Color(0xFF8AB4F8).copy(alpha = 0.25f) else Color(0xFF1F2227),
                        border = BorderStroke(1.dp, if (isSel) Color(0xFF8AB4F8) else Color(0xFF3C4043)),
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onSelectPreset(preset) }
                    ) {
                        Text(preset.title.take(12), color = if (isSel) Color(0xFF8AB4F8) else Color(0xFF9AA0A6), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = systemPromptTitle,
            onValueChange = onSystemPromptTitleChange,
            label = { Text("Preset Title (e.g. Sarcastic Buddy)") },
            placeholder = { Text("Title matches existing in Studio or creates new", color = Color(0xFF5F6368), fontSize = 11.sp) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color(0xFFE8EAED),
                unfocusedTextColor = Color(0xFFE8EAED),
                focusedBorderColor = Color(0xFF8AB4F8),
                unfocusedBorderColor = Color(0xFF3C4043)
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = systemPrompt,
            onValueChange = onSystemPromptChange,
            label = { Text("Instructions Body (Optional if title exists)") },
            placeholder = { Text("You are a specialized assistant...", color = Color(0xFF5F6368), fontSize = 11.sp) },
            maxLines = 3,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color(0xFFE8EAED),
                unfocusedTextColor = Color(0xFFE8EAED),
                focusedBorderColor = Color(0xFF8AB4F8),
                unfocusedBorderColor = Color(0xFF3C4043)
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Auto-fallback to local vault if missing in Studio", color = Color(0xFF9AA0A6), fontSize = 10.sp)
            Switch(
                checked = fallbackEnabled,
                onCheckedChange = onFallbackEnabledChange,
                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF8AB4F8)),
                modifier = Modifier.height(24.dp)
            )
        }
    }
}

@Composable
private fun StepCardItem(
    index: Int,
    step: SequentialPromptStep,
    canRemove: Boolean,
    onUpdatePromptStep: (id: String, prompt: String?, repeatCount: Int?, isInfinite: Boolean?) -> Unit,
    onRemovePromptStep: (id: String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2227)),
        border = BorderStroke(1.dp, Color(0xFF3C4043)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF8AB4F8).copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, Color(0xFF8AB4F8))
                ) {
                    Text(
                        "Step ${index + 1}",
                        color = Color(0xFF8AB4F8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Repeat:", color = Color(0xFF9AA0A6), fontSize = 10.sp)
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (step.isInfinite) Color(0xFFFDD663).copy(alpha = 0.2f) else Color(0xFF282C34),
                        border = BorderStroke(1.dp, if (step.isInfinite) Color(0xFFFDD663) else Color(0xFF3C4043))
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!step.isInfinite) {
                                Text(
                                    "${step.repeatCount}x",
                                    color = Color(0xFFE8EAED),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                IconButton(
                                    onClick = {
                                        val next = (step.repeatCount % 10) + 1
                                        onUpdatePromptStep(step.id, null, next, false)
                                    },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Text("+", color = Color(0xFF8AB4F8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Text(
                                    "∞ Loop",
                                    color = Color(0xFFFDD663),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(4.dp))

                    IconButton(
                        onClick = { onUpdatePromptStep(step.id, null, null, !step.isInfinite) },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Text(
                            "∞",
                            color = if (step.isInfinite) Color(0xFFFDD663) else Color(0xFF5F6368),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (canRemove) {
                        IconButton(
                            onClick = { onRemovePromptStep(step.id) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove Step",
                                tint = Color(0xFFF28B82),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            OutlinedTextField(
                value = step.prompt,
                onValueChange = { onUpdatePromptStep(step.id, it, null, null) },
                placeholder = { Text("Prompt for Step ${index + 1}...", color = Color(0xFF5F6368), fontSize = 11.sp) },
                minLines = 2,
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFFE8EAED),
                    unfocusedTextColor = Color(0xFFE8EAED),
                    focusedBorderColor = Color(0xFF8AB4F8),
                    unfocusedBorderColor = Color(0xFF3C4043)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PromptChainSection(
    promptSteps: List<SequentialPromptStep>,
    onAddPromptStep: () -> Unit,
    onUpdatePromptStep: (id: String, prompt: String?, repeatCount: Int?, isInfinite: Boolean?) -> Unit,
    onRemovePromptStep: (id: String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔄 Ordered Prompt Chain", color = Color(0xFF8AB4F8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF1F2227)) {
                    Text("${promptSteps.size} Step(s)", color = Color(0xFF8AB4F8), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            TextButton(
                onClick = onAddPromptStep,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF81C995))
                Spacer(Modifier.width(4.dp))
                Text("+ Add Next Step", color = Color(0xFF81C995), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        promptSteps.forEachIndexed { idx, step ->
            StepCardItem(
                index = idx,
                step = step,
                canRemove = promptSteps.size > 1,
                onUpdatePromptStep = onUpdatePromptStep,
                onRemovePromptStep = onRemovePromptStep
            )
        }
    }
}

@Composable
fun AutomationOrderDialog(
    profiles: List<BrowserProfile>,
    selectedProfileId: String,
    onSelectProfileId: (String) -> Unit,
    thinkingLevel: String,
    onThinkingLevelChange: (String) -> Unit,
    temporaryChat: Boolean,
    onTemporaryChatChange: (Boolean) -> Unit,
    attachments: List<AutomationAttachment>,
    onPickFiles: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    systemPresets: List<com.omni.plugin.browser.models.SystemInstructionPreset>,
    systemPromptTitle: String,
    onSystemPromptTitleChange: (String) -> Unit,
    systemPrompt: String,
    onSystemPromptChange: (String) -> Unit,
    fallbackEnabled: Boolean,
    onFallbackEnabledChange: (Boolean) -> Unit,
    onSavePreset: (String, String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onSelectPreset: (com.omni.plugin.browser.models.SystemInstructionPreset) -> Unit,
    promptSteps: List<SequentialPromptStep>,
    onAddPromptStep: () -> Unit,
    onUpdatePromptStep: (id: String, prompt: String?, repeatCount: Int?, isInfinite: Boolean?) -> Unit,
    onRemovePromptStep: (id: String) -> Unit,
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Configure and dispatch prompts directly to Google AI Studio headlessly using your active profile cookies.", color = Color(0xFF9AA0A6), fontSize = 11.sp)

                ProfilePickerSection(
                    profiles = profiles,
                    selectedProfileId = selectedProfileId,
                    onSelectProfileId = onSelectProfileId
                )

                ThinkingLevelSection(
                    thinkingLevel = thinkingLevel,
                    onThinkingLevelChange = onThinkingLevelChange
                )

                TemporaryChatSection(
                    temporaryChat = temporaryChat,
                    onTemporaryChatChange = onTemporaryChatChange
                )

                AttachmentsSection(
                    attachments = attachments,
                    onPickFiles = onPickFiles,
                    onRemoveAttachment = onRemoveAttachment
                )

                SystemPresetsSection(
                    systemPresets = systemPresets,
                    systemPromptTitle = systemPromptTitle,
                    onSystemPromptTitleChange = onSystemPromptTitleChange,
                    systemPrompt = systemPrompt,
                    onSystemPromptChange = onSystemPromptChange,
                    fallbackEnabled = fallbackEnabled,
                    onFallbackEnabledChange = onFallbackEnabledChange,
                    onSavePreset = onSavePreset,
                    onSelectPreset = onSelectPreset
                )

                PromptChainSection(
                    promptSteps = promptSteps,
                    onAddPromptStep = onAddPromptStep,
                    onUpdatePromptStep = onUpdatePromptStep,
                    onRemovePromptStep = onRemovePromptStep
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onRun,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8AB4F8)),
                enabled = promptSteps.any { it.prompt.trim().isNotEmpty() } || userPrompt.trim().isNotEmpty() || attachments.isNotEmpty()
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
    onBackToStaging: () -> Unit = {},
    onDumpDom: () -> Unit,
    onCopyResult: () -> Unit,
    onCloseOrStop: () -> Unit
) {
    var thoughtsExpanded by remember { mutableStateOf(false) }
    var activeTabMode by remember { mutableStateOf("stream") } // Default to 'stream' to protect SurfaceFlinger

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
                    IconButton(
                        onClick = onBackToStaging,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back to Staging",
                            tint = Color(0xFF8AB4F8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onBackToStaging,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF8AB4F8))
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Staging", fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = onDumpDom,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF8AB4F8))
                ) {
                    Text("🔍 DOM", fontSize = 11.sp)
                }

                if (automationResult.isNotEmpty()) {
                    Button(
                        onClick = onCopyResult,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636))
                    ) {
                        Text("📋 Copy", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = onCloseOrStop,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3C4043))
                ) {
                    Text(if (isAutomating) "Stop" else "Close", color = Color.White, fontSize = 11.sp)
                }
            }
        }
    )
}