package com.omni.plugin.player

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale

// ========================================================
// 1. SUBTITLE HUB SCREEN (Screenshot 3)
// ========================================================

private fun Modifier.consumeAllTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            event.changes.forEach { it.consume() }
        }
    }
}

private fun Modifier.scrimDismissTouches(onDismiss: () -> Unit): Modifier = pointerInput(onDismiss) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent(PointerEventPass.Main)
            if (down.type != PointerEventType.Press) {
                down.changes.forEach { it.consume() }
                continue
            }
            val first = down.changes.firstOrNull() ?: continue
            if (first.isConsumed) continue
            val startPos = first.position
            first.consume()
            var moved = false
            val touchSlop = viewConfiguration.touchSlop
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Main)
                ev.changes.forEach { it.consume() }
                val ch = ev.changes.firstOrNull { it.id == first.id } ?: break
                if (ev.type == PointerEventType.Move) {
                    if ((ch.position - startPos).getDistance() > touchSlop) {
                        moved = true
                    }
                } else if (ev.type == PointerEventType.Release) {
                    if (!moved) {
                        onDismiss()
                    }
                    break
                }
            }
        }
    }
}

@Composable
fun SubtitleHubSheet(
    subtitleState: SubtitleState,
    aiEngineSummary: String = "",
    onOpenFile: () -> Unit,
    onOpenDownload: () -> Unit,
    onOpenStyle: () -> Unit,
    onOpenDelay: () -> Unit,
    onOpenAiSettings: () -> Unit = {},
    onStateChanged: () -> Unit = {},
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .scrimDismissTouches(onDismiss = onClose)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0xFF0B0E14), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .consumeAllTouches()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // Header: Back Arrow, Title, Green Toggle Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Subtitle",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Medium
                    )
                }

                Switch(
                    checked = subtitleState.isEnabled,
                    onCheckedChange = {
                        subtitleState.isEnabled = it
                        onStateChanged()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF00E676),
                        uncheckedThumbColor = Color(0xFF8B949E),
                        uncheckedTrackColor = Color(0xFF21262D)
                    )
                )
            }

            Spacer(Modifier.height(14.dp))

            // Current subtitle row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        if (subtitleState.currentSubtitleName != null) {
                            subtitleState.clearSubtitle()
                            onStateChanged()
                        }
                    }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Current subtitle",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontStyle = FontStyle.Italic
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitleState.currentSubtitleName ?: "None (Tap to disable or select below)",
                        color = if (subtitleState.currentSubtitleName != null) Color(0xFF00E676) else Color(0xFF8B949E),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text("›", color = Color(0xFF8B949E), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 6.dp))

            // Open File Row
            Text(
                text = "Open file",
                color = Color.White,
                fontSize = 15.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenFile() }
                    .padding(vertical = 12.dp, horizontal = 4.dp)
            )

            // Online Download Row
            Text(
                text = "Online download",
                color = Color.White,
                fontSize = 15.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenDownload() }
                    .padding(vertical = 12.dp, horizontal = 4.dp)
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 6.dp))

            // Subtitle Style Row
            Text(
                text = "Subtitle style",
                color = Color.White,
                fontSize = 15.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenStyle() }
                    .padding(vertical = 12.dp, horizontal = 4.dp)
            )

            // Subtitle Delay Row
            Text(
                text = "Subtitle delay",
                color = Color.White,
                fontSize = 15.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenDelay() }
                    .padding(vertical = 12.dp, horizontal = 4.dp)
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 6.dp))

            // AI Explainer & Warm Engine Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenAiSettings() }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AI Explainer & Engine",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontStyle = FontStyle.Italic
                    )
                    if (aiEngineSummary.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = aiEngineSummary,
                            color = Color(0xFF00E676),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text("›", color = Color(0xFF8B949E), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(10.dp))
        }
    }
}

// ========================================================
// 2. SUBTITLE STYLE STUDIO (Screenshot 1)
// ========================================================

@Composable
fun SubtitleStyleSheet(
    subtitleState: SubtitleState,
    onStyleChanged: () -> Unit = {},
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .scrimDismissTouches(onDismiss = onBack)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0xFF0B0E14), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .consumeAllTouches()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Subtitle style",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(12.dp))

            // Live "Sample Subtitle" Preview Card (Screenshot 1)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E222A)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = subtitleState.boxColor,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Sample Subtitle",
                        color = subtitleState.fontColor,
                        fontSize = subtitleState.fontSizeSp.sp,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Section: Preset styles
            Text(
                text = "Preset styles",
                color = Color.White,
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SUBTITLE_PRESETS.forEach { preset ->
                    val isSelected = subtitleState.selectedPresetId == preset.id
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF161B22),
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) Color(0xFF00E676) else Color.White.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                subtitleState.applyPreset(preset)
                                onStyleChanged()
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Surface(
                                color = preset.bgColor,
                                shape = RoundedCornerShape(3.dp),
                                modifier = Modifier.padding(2.dp)
                            ) {
                                Text(
                                    text = "Aa",
                                    color = preset.textColor,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Section: Font color
            Text(
                text = "Font color",
                color = Color.White,
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(SUBTITLE_PALETTE) { color ->
                    val isSelected = subtitleState.fontColor == color
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(color)
                            .border(
                                width = if (isSelected) 2.dp else 0.8.dp,
                                color = if (isSelected) Color(0xFF00E676) else Color.White.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .clickable {
                                subtitleState.selectColor(color)
                                onStyleChanged()
                            }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Section: Size Slider with counter and Reset icon
            Text(
                text = "Size",
                color = Color.White,
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(bottom = 2.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Slider(
                    value = subtitleState.fontSizeSp,
                    onValueChange = {
                        subtitleState.fontSizeSp = it
                        onStyleChanged()
                    },
                    valueRange = 14f..36f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color(0xFF00E676),
                        inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.weight(1f)
                )

                Spacer(Modifier.width(8.dp))

                Text(
                    text = "${subtitleState.fontSizeSp.toInt()}",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(28.dp)
                )

                IconButton(onClick = {
                    subtitleState.resetSize()
                    onStyleChanged()
                }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reset Size", tint = Color.White)
                }
            }
        }
    }
}

// ========================================================
// 3. FLOATING SUBTITLE DELAY CONTROLLER (Screenshot 2)
// ========================================================

@Composable
fun SubtitleDelayWidget(
    subtitleState: SubtitleState,
    onDelayChanged: () -> Unit = {},
    onClose: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xD911151D),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
        modifier = Modifier
            .width(86.dp)
            .shadow(12.dp, RoundedCornerShape(12.dp))
            .consumeAllTouches()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp)
        ) {
            // Close ✕
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                IconButton(onClick = onClose, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF8B949E), modifier = Modifier.size(14.dp))
                }
            }

            Spacer(Modifier.height(4.dp))

            // Green Minus Button [-]
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF00E676),
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        val rounded = (subtitleState.delaySec - 0.1f)
                        subtitleState.delaySec = String.format(Locale.US, "%.1f", rounded).toFloat()
                        onDelayChanged()
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("-", color = Color.Black, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Offset Display (e.g. -83.8) with underline
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = String.format(Locale.US, "%.1f", subtitleState.delaySec),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .width(42.dp)
                        .height(1.5.dp)
                        .background(Color.White)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Green Plus Button [+]
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF00E676),
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        val rounded = (subtitleState.delaySec + 0.1f)
                        subtitleState.delaySec = String.format(Locale.US, "%.1f", rounded).toFloat()
                        onDelayChanged()
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("+", color = Color.Black, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ========================================================
// 4. ON-VIDEO SUBTITLE OVERLAY
// ========================================================

@Composable
fun SubtitleOnVideoOverlay(
    cueText: String?,
    subtitleState: SubtitleState,
    inlineVisible: Boolean = false,
    inlineText: String = "",
    inlineStatus: String = "",
    inlineIsLoading: Boolean = false,
    inlineError: String? = null,
    onTapInlineBox: () -> Unit = {},
    onPositionChanged: (Float) -> Unit,
    onTapSubtitle: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (cueText.isNullOrBlank() || !subtitleState.isEnabled) return

    var isDragging by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val boxHeightPx = constraints.maxHeight.toFloat()
        val verticalBias = (1f - 2f * subtitleState.yFraction).coerceIn(-0.90f, 0.92f)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .align(BiasAlignment(0f, verticalBias))
                .padding(horizontal = 24.dp)
        ) {
            AnimatedVisibility(
                visible = inlineVisible,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                val inlineFontSp = (subtitleState.fontSizeSp * 0.80f).coerceIn(13f, 22f)
                Surface(
                    color = if (subtitleState.boxColor.alpha == 0f) Color(0xB3000000) else subtitleState.boxColor,
                    shape = RoundedCornerShape(4.dp),
                    border = if (subtitleState.hasOutline) {
                        BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
                    } else null,
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .heightIn(max = 175.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onTapInlineBox() }
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (inlineIsLoading) {
                                CircularProgressIndicator(
                                    color = if (subtitleState.fontColor == Color.Black) Color(0xFF00E676) else subtitleState.fontColor,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            val displayMsg = when {
                                inlineError != null -> inlineError
                                inlineText.isNotBlank() -> inlineText
                                inlineStatus.isNotBlank() -> inlineStatus
                                else -> "Translating..."
                            }
                            Text(
                                text = displayMsg,
                                color = if (inlineError != null) Color(0xFFF85149) else subtitleState.fontColor,
                                fontSize = inlineFontSp.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                lineHeight = (inlineFontSp * 1.25f).sp
                            )
                        }

                        if (inlineError != null) {
                            Spacer(Modifier.height(4.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF1F6FEB),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { onTapInlineBox() }
                            ) {
                                Text(
                                    text = "↻ Retry in New Session",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }

            Surface(
                color = subtitleState.boxColor,
                shape = RoundedCornerShape(4.dp),
                border = if (isDragging) BorderStroke(1.5.dp, Color(0xFF00E676)) else null,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .pointerInput(cueText) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                isDragging = true
                            },
                            onDragEnd = {
                                isDragging = false
                                onPositionChanged(subtitleState.yFraction)
                            },
                            onDragCancel = {
                                isDragging = false
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val deltaFraction = -dragAmount.y / boxHeightPx
                                val newFraction = (subtitleState.yFraction + deltaFraction).coerceIn(0.04f, 0.85f)
                                subtitleState.yFraction = newFraction
                            }
                        )
                    }
                    .clickable {
                        if (!isDragging) {
                            onTapSubtitle()
                        }
                    }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isDragging) {
                        Text(
                            text = "DRAG TO REPOSITION",
                            color = Color(0xFF00E676),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Text(
                        text = cueText,
                        color = subtitleState.fontColor,
                        fontSize = subtitleState.fontSizeSp.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        lineHeight = (subtitleState.fontSizeSp * 1.25f).sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

// ========================================================
// 5. AI DIALOGUE INSPECTOR (GROQ QWEN 3.8 27B)
// ========================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiDialogueInspectorSheet(
    context: Context,
    bridge: com.omni.hub.api.HostBridge,
    currentCue: String,
    lineText: String = "",
    lineStatus: String = "",
    lineIsLoading: Boolean = false,
    lineError: String? = null,
    onRetryLine: () -> Unit = {},
    onConfigureKey: () -> Unit,
    onResumeMovie: () -> Unit,
    onClose: () -> Unit
) {
    var selectedWord by remember { mutableStateOf<String?>(null) }
    var wordExplanationText by remember { mutableStateOf<String?>(null) }
    var wordStatusText by remember { mutableStateOf("") }
    var isWordLoading by remember { mutableStateOf(false) }
    var wordErrorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val activeProvider = OmniAiBridgeClient.getProvider(context)
    val isOmni = activeProvider == OmniAiBridgeClient.PROVIDER_OMNI_BROWSER

    val rawTokens = remember(currentCue) {
        currentCue.split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    fun sanitizeWord(token: String): String {
        return token.replace(Regex("^[^a-zA-Z0-9]+|[^a-zA-Z0-9]+$"), "")
    }

    fun requestWordExplanation(word: String) {
        if (isOmni) {
            isWordLoading = true
            wordErrorMessage = null
            wordExplanationText = ""
            wordStatusText = if (OmniAiBridgeClient.warmState == "WARM") "Sending..." else "Warming AI Studio..."
            val prompt = "In that subtitle line (\"$currentCue\"), explain the word/term \"$word\" briefly in 2 points:\n1. Direct Meaning\n2. Meaning in this scene."
            OmniAiBridgeClient.askStreaming(
                context = context,
                bridge = bridge,
                prompt = prompt,
                label = "Word: $word",
                onStatus = { st -> wordStatusText = st },
                onDelta = { delta ->
                    wordExplanationText = (wordExplanationText ?: "") + delta
                },
                onComplete = { finalOut ->
                    wordExplanationText = finalOut
                    isWordLoading = false
                },
                onError = { err ->
                    wordErrorMessage = err
                    isWordLoading = false
                }
            )
        } else {
            scope.launch {
                isWordLoading = true
                wordErrorMessage = null
                try {
                    wordExplanationText = GroqAiService.explainWord(context, word, currentCue)
                } catch (e: Exception) {
                    wordErrorMessage = e.message ?: "Failed to generate explanation"
                } finally {
                    isWordLoading = false
                }
            }
        }
    }

    val isLoading = if (selectedWord != null) isWordLoading else lineIsLoading
    val errorMessage = if (selectedWord != null) wordErrorMessage else lineError
    val explanationText = if (selectedWord != null) wordExplanationText else lineText
    val statusText = if (selectedWord != null) wordStatusText else lineStatus

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .scrimDismissTouches(onDismiss = onClose)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0xFF0D1117), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .consumeAllTouches()
                .padding(16.dp)
        ) {
            // Header: Bulletproof Two-Tier Layout (Guaranteed No Overflow)
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AI Scene Explainer",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF161B22),
                            border = BorderStroke(0.8.dp, Color(0xFF58A6FF).copy(alpha = 0.6f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onConfigureKey() }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("⚙️ Settings", color = Color(0xFF58A6FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF8B949E))
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF00E676).copy(alpha = 0.15f),
                        border = BorderStroke(0.8.dp, Color(0xFF00E676).copy(alpha = 0.4f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onConfigureKey() }
                    ) {
                        Text(
                            text = if (isOmni) {
                                val dot = when (OmniAiBridgeClient.warmState) {
                                    "WARM" -> "🟢"
                                    "WARMING" -> "🟡"
                                    "ERROR" -> "🔴"
                                    else -> "⚪"
                                }
                                "$dot ${OmniAiBridgeClient.getModel(context)}"
                            } else "qwen3.8-27b",
                            color = Color(0xFF00E676),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    if (isOmni) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF161B22),
                            border = BorderStroke(0.8.dp, Color(0xFF00E676).copy(alpha = 0.5f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    OmniAiBridgeClient.newChat(bridge) { ok, err ->
                                        bridge.showToast(if (ok) "Fresh AI Studio chat ready" else (err ?: "Reset failed"))
                                    }
                                }
                        ) {
                            Text(
                                text = "New Chat",
                                color = Color(0xFF00E676),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Subtitle Tokenizer: Interactive Tappable Word Chips
            Text(
                text = "Tap any word for dictionary + scene nuance:",
                color = Color(0xFF8B949E),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // "Full Line" Chip
                val isFullSelected = selectedWord == null
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isFullSelected) Color(0xFF00E676) else Color(0xFF161B22),
                    border = BorderStroke(0.8.dp, if (isFullSelected) Color(0xFF00E676) else Color.White.copy(alpha = 0.15f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            if (selectedWord != null) {
                                selectedWord = null
                            }
                        }
                ) {
                    Text(
                        text = "Full Line",
                        color = if (isFullSelected) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }

                // Individual Word Chips
                rawTokens.forEach { token ->
                    val clean = sanitizeWord(token)
                    val isThisSelected = selectedWord.equals(clean, ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isThisSelected) Color(0xFF00E676) else Color(0xFF161B22),
                        border = BorderStroke(0.8.dp, if (isThisSelected) Color(0xFF00E676) else Color.White.copy(alpha = 0.15f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                if (clean.isNotEmpty() && !isThisSelected) {
                                    selectedWord = clean
                                    requestWordExplanation(clean)
                                }
                            }
                    ) {
                        Text(
                            text = token,
                            color = if (isThisSelected) Color.Black else Color.White,
                            fontSize = 12.sp,
                            fontWeight = if (isThisSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // AI Explanation Box
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF161B22),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 220.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    contentAlignment = if (isLoading) Alignment.Center else Alignment.TopStart
                ) {
                    if (isLoading && explanationText.isNullOrBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(color = Color(0xFF00E676), strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                            Text(
                                text = statusText.ifBlank { if (isOmni) "Omni AI Studio thinking..." else "Groq LPU thinking..." },
                                color = Color(0xFF8B949E),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else if (errorMessage != null && explanationText.isNullOrBlank()) {
                        Column {
                            Text("Explanation Error:", color = Color(0xFFF85149), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(errorMessage, color = Color(0xFFC9D1D9), fontSize = 12.sp)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    val w = selectedWord
                                    if (w != null) requestWordExplanation(w) else onRetryLine()
                                }) {
                                    Text("Retry", color = Color(0xFF00E676), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = {
                                        OmniAiBridgeClient.closeSession(bridge)
                                        val w = selectedWord
                                        if (w != null) requestWordExplanation(w) else onRetryLine()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("↻ Retry in New Session", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            item {
                                if (selectedWord != null) {
                                    Text(
                                        text = "Target: \"$selectedWord\"",
                                        color = Color(0xFF00E676),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(6.dp))
                                }
                                Text(
                                    text = explanationText ?: "No explanation available.",
                                    color = Color(0xFFE6EDF3),
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Bottom Actions: Resume Movie Button
            Button(
                onClick = onResumeMovie,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Text(
                    text = "▶  Resume Movie",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}

// ========================================================
// 6. GROQ API KEY CONFIGURATION MODAL
// ========================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroqKeyConfigDialog(
    context: Context,
    bridge: com.omni.hub.api.HostBridge,
    onKeySaved: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val omniAvailable = remember { OmniAiBridgeClient.isOmniBrowserInstalled(bridge) }
    var selectedProvider by remember { mutableStateOf(OmniAiBridgeClient.getProvider(context)) }
    var keyInput by remember { mutableStateOf(GroqAiService.getApiKey(context) ?: "") }
    var groqPromptInput by remember { mutableStateOf(GroqAiService.getSystemPrompt(context)) }

    var profiles by remember { mutableStateOf(listOf(OmniProfileInfo("default", "Default"))) }
    var selectedProfileId by remember { mutableStateOf(OmniAiBridgeClient.getProfileId(context)) }
    var modelInput by remember { mutableStateOf(OmniAiBridgeClient.getModel(context)) }
    var thinkingLevel by remember { mutableStateOf(OmniAiBridgeClient.getThinkingLevel(context)) }
    var tempChat by remember { mutableStateOf(OmniAiBridgeClient.getTemporaryChat(context)) }
    var keepContext by remember { mutableStateOf(OmniAiBridgeClient.getKeepMovieContext(context)) }
    var omniPromptInput by remember { mutableStateOf(OmniAiBridgeClient.getOmniSystemPrompt(context)) }

    LaunchedEffect(omniAvailable) {
        if (omniAvailable) {
            OmniAiBridgeClient.fetchProfiles(bridge) { loaded ->
                profiles = loaded
            }
        }
    }

    fun persistSettings() {
        OmniAiBridgeClient.saveProvider(context, selectedProvider)
        if (selectedProvider == OmniAiBridgeClient.PROVIDER_GROQ) {
            if (keyInput.isNotBlank()) GroqAiService.saveApiKey(context, keyInput.trim())
            GroqAiService.saveSystemPrompt(context, groqPromptInput)
        } else {
            OmniAiBridgeClient.saveProfileId(context, selectedProfileId)
            OmniAiBridgeClient.saveModel(context, modelInput)
            OmniAiBridgeClient.saveThinkingLevel(context, thinkingLevel)
            OmniAiBridgeClient.saveTemporaryChat(context, tempChat)
            OmniAiBridgeClient.saveKeepMovieContext(context, keepContext)
            OmniAiBridgeClient.saveOmniSystemPrompt(context, omniPromptInput)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161B22),
        shape = RoundedCornerShape(16.dp),
        title = {
            Column {
                Text("AI Explainer Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (selectedProvider == OmniAiBridgeClient.PROVIDER_OMNI_BROWSER)
                        "Omni Browser • Persistent Headless AI Studio"
                    else
                        "Groq LPU • Qwen 3.8 27B",
                    color = Color(0xFF00E676),
                    fontSize = 11.sp
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("AI Provider", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        OmniAiBridgeClient.PROVIDER_GROQ to "Groq (Qwen 3.8)",
                        OmniAiBridgeClient.PROVIDER_OMNI_BROWSER to "Omni Browser"
                    ).forEach { (pKey, pLabel) ->
                        val isSel = selectedProvider == pKey
                        val enabled = pKey == OmniAiBridgeClient.PROVIDER_GROQ || omniAvailable
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) Color(0xFF00E676).copy(alpha = 0.2f) else Color(0xFF0D1117),
                            border = BorderStroke(1.dp, if (isSel) Color(0xFF00E676) else Color.White.copy(alpha = 0.15f)),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = enabled) { selectedProvider = pKey }
                        ) {
                            Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (!enabled) "Omni (Missing)" else pLabel,
                                    color = if (!enabled) Color(0xFF484F58) else if (isSel) Color(0xFF00E676) else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                if (selectedProvider == OmniAiBridgeClient.PROVIDER_GROQ) {
                    Text("Groq API Key", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        singleLine = true,
                        placeholder = { Text("gsk_...", fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E676),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("System Instruction", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "Reset Default",
                            color = Color(0xFF58A6FF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { groqPromptInput = GroqAiService.DEFAULT_SYSTEM_PROMPT }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    OutlinedTextField(
                        value = groqPromptInput,
                        onValueChange = { groqPromptInput = it },
                        minLines = 3,
                        maxLines = 5,
                        placeholder = { Text("Custom system prompt...", fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E676),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text("Account Profile", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        profiles.forEach { prof ->
                            val isSel = selectedProfileId == prof.id
                            val badge = when {
                                prof.needsVerification -> " 🔒"
                                prof.isQuotaExhausted -> " 🔴"
                                else -> ""
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) Color(0xFF00E676).copy(alpha = 0.2f) else Color(0xFF0D1117),
                                border = BorderStroke(
                                    1.dp,
                                    when {
                                        isSel -> Color(0xFF00E676)
                                        prof.needsVerification -> Color(0xFFFF6D00).copy(alpha = 0.6f)
                                        prof.isQuotaExhausted -> Color(0xFFF85149).copy(alpha = 0.6f)
                                        else -> Color.White.copy(alpha = 0.15f)
                                    }
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selectedProfileId = prof.id }
                            ) {
                                Text(
                                    text = "${prof.name}$badge",
                                    color = if (isSel) Color(0xFF00E676) else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    Text("Model", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E676),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Gemini 3.8 Flash", "Gemini 3.7 Flash", "Gemini 2.5 Pro").forEach { m ->
                            val isSel = modelInput.equals(m, ignoreCase = true)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSel) Color(0xFF58A6FF).copy(alpha = 0.2f) else Color(0xFF0D1117),
                                border = BorderStroke(1.dp, if (isSel) Color(0xFF58A6FF) else Color.White.copy(alpha = 0.12f)),
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { modelInput = m }
                            ) {
                                Text(m, color = if (isSel) Color(0xFF58A6FF) else Color(0xFF8B949E), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                    }

                    Text("Thinking Level", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf("Default", "Minimal", "Low", "High").forEach { lvl ->
                            val isSel = thinkingLevel == lvl
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) Color(0xFF00E676).copy(alpha = 0.2f) else Color(0xFF0D1117),
                                border = BorderStroke(1.dp, if (isSel) Color(0xFF00E676) else Color.White.copy(alpha = 0.15f)),
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { thinkingLevel = lvl }
                            ) {
                                Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                    Text(lvl, color = if (isSel) Color(0xFF00E676) else Color(0xFF8B949E), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Keep Movie Context", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Reuse same session across subtitles", color = Color(0xFF8B949E), fontSize = 10.sp)
                        }
                        Switch(
                            checked = keepContext,
                            onCheckedChange = { keepContext = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF00E676))
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Temporary Chat", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Don't clutter Google Drive history", color = Color(0xFF8B949E), fontSize = 10.sp)
                        }
                        Switch(
                            checked = tempChat,
                            onCheckedChange = { tempChat = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF00E676))
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("System Instruction", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "Reset Default",
                            color = Color(0xFF58A6FF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { omniPromptInput = OmniAiBridgeClient.DEFAULT_OMNI_PROMPT }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    OutlinedTextField(
                        value = omniPromptInput,
                        onValueChange = { omniPromptInput = it },
                        minLines = 3,
                        maxLines = 5,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E676),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Warm Session Button
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF0D1117),
                        border = BorderStroke(
                            1.dp,
                            when (OmniAiBridgeClient.warmState) {
                                "WARM" -> Color(0xFF00E676)
                                "WARMING" -> Color(0xFFD29922)
                                "ERROR" -> Color(0xFFF85149)
                                else -> Color(0xFF58A6FF)
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = OmniAiBridgeClient.warmState != "WARMING") {
                                persistSettings()
                                OmniAiBridgeClient.warmSession(context, bridge) { ok, err ->
                                    bridge.showToast(if (ok) "🟢 AI Studio Session Warm & Ready!" else (err ?: "Warm-up failed"))
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (OmniAiBridgeClient.warmState == "WARMING") {
                                    CircularProgressIndicator(color = Color(0xFFD29922), strokeWidth = 2.dp, modifier = Modifier.size(15.dp))
                                } else {
                                    Text(
                                        when (OmniAiBridgeClient.warmState) {
                                            "WARM" -> "🟢"
                                            "ERROR" -> "🔴"
                                            else -> "🔥"
                                        },
                                        fontSize = 14.sp
                                    )
                                }
                                Text(
                                    text = when (OmniAiBridgeClient.warmState) {
                                        "WARMING" -> OmniAiBridgeClient.warmStatusMessage.ifBlank { "Warming AI Studio..." }
                                        "WARM" -> "Session Warm (Tap to Re-Sync)"
                                        "ERROR" -> OmniAiBridgeClient.warmStatusMessage.ifBlank { "Retry Warm Session" }
                                        else -> "Warm Session Now"
                                    },
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    persistSettings()
                    onKeySaved(
                        if (selectedProvider == OmniAiBridgeClient.PROVIDER_OMNI_BROWSER) "OMNI"
                        else keyInput.trim()
                    )
                },
                enabled = selectedProvider == OmniAiBridgeClient.PROVIDER_OMNI_BROWSER || keyInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
            ) {
                Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF8B949E))
            }
        }
    )
}