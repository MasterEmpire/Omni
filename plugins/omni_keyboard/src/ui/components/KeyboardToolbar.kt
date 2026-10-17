package com.omni.plugin.keyboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.plugin.keyboard.model.KeyboardMode
import com.omni.plugin.keyboard.state.KeyboardState
import com.omni.plugin.keyboard.ui.theme.KeyboardPalette

@Composable
fun KeyboardToolbar(
    state: KeyboardState,
    palette: KeyboardPalette,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(palette.background)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Toggle Between Suggestions & Toolbar Menu
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { state.isToolbarOverride = !state.isToolbarOverride },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (state.isToolbarOverride) "🔤" else "⚡",
                fontSize = 16.sp
            )
        }

        Spacer(Modifier.width(4.dp))

        if (state.isToolbarOverride || state.suggestions.isEmpty()) {
            // Quick Tools Bar
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ToolButton("🎨", "Theme") { state.toggleTheme() }
                ToolButton("🌐", "Language") { state.toggleLanguage() }
                ToolButton("123", "Number Row") { state.showNumberRow = !state.showNumberRow }
                ToolButton("✏️", "Text Editor") {
                    state.mode = if (state.mode == KeyboardMode.TEXT_EDITOR) KeyboardMode.ALPHA else KeyboardMode.TEXT_EDITOR
                }
                ToolButton("📐", "Height") {
                    state.heightScalePercent = if (state.heightScalePercent >= 140) 100 else state.heightScalePercent + 15
                }
                ToolButton("📋", "Diagnostics") { state.host.getHostBridge().showDiagnostics() }
            }
        } else {
            // Suggestion Strip
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                state.suggestions.take(3).forEachIndexed { index, suggestion ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable {
                                state.commitChar(suggestion + " ")
                                state.suggestions.clear()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = suggestion,
                            color = if (index == 0) palette.accent else palette.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }

                    if (index < state.suggestions.size - 1 && index < 2) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(18.dp)
                                .background(palette.textSecondary.copy(alpha = 0.3f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: String,
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = icon, fontSize = 16.sp)
    }
}