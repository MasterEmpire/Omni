package com.omni.plugin.player

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.input.pointer.pointerInput
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

@Composable
fun SubtitleHubSheet(
    subtitleState: SubtitleState,
    onOpenFile: () -> Unit,
    onOpenDownload: () -> Unit,
    onOpenStyle: () -> Unit,
    onOpenDelay: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable(onClick = onClose)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0xFF0B0E14), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .clickable(enabled = false) {}
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
                    onCheckedChange = { subtitleState.isEnabled = it },
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
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable(onClick = onBack)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0xFF0B0E14), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .clickable(enabled = false) {}
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
                            .clickable { subtitleState.applyPreset(preset) }
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
                            .clickable { subtitleState.selectColor(color) }
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
                    onValueChange = { subtitleState.fontSizeSp = it },
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

                IconButton(onClick = { subtitleState.resetSize() }, modifier = Modifier.size(32.dp)) {
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
    onClose: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xD911151D),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
        modifier = Modifier
            .width(86.dp)
            .shadow(12.dp, RoundedCornerShape(12.dp))
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
    onPositionChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    if (cueText.isNullOrBlank() || !subtitleState.isEnabled) return

    var isDragging by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val boxHeightPx = constraints.maxHeight.toFloat()
        val verticalBias = (1f - 2f * subtitleState.yFraction).coerceIn(-0.90f, 0.92f)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(BiasAlignment(0f, verticalBias))
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = subtitleState.boxColor,
                shape = RoundedCornerShape(4.dp),
                border = if (isDragging) BorderStroke(1.5.dp, Color(0xFF00E676)) else null,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .pointerInput(Unit) {
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