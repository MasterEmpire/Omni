package com.omni.plugin.keyboard.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
fun TextEditorPad(
    state: KeyboardState,
    palette: KeyboardPalette,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.background)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Action Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EditorActionButton("SELECT ALL", Modifier.weight(1f), palette) {
                state.host.getCurrentInputConnection()?.performContextMenuAction(android.R.id.selectAll)
            }
            EditorActionButton("COPY", Modifier.weight(1f), palette) {
                state.host.getCurrentInputConnection()?.performContextMenuAction(android.R.id.copy)
            }
            EditorActionButton("PASTE", Modifier.weight(1f), palette) {
                state.host.getCurrentInputConnection()?.performContextMenuAction(android.R.id.paste)
            }
            EditorActionButton("CUT", Modifier.weight(1f), palette) {
                state.host.getCurrentInputConnection()?.performContextMenuAction(android.R.id.cut)
            }
        }

        Spacer(Modifier.height(14.dp))

        // D-Pad Arrows
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DpadButton("▲", 64.dp, 44.dp, palette) { state.sendDpad(KeyEvent.KEYCODE_DPAD_UP) }

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DpadButton("◀", 64.dp, 44.dp, palette) { state.sendDpad(KeyEvent.KEYCODE_DPAD_LEFT) }
                DpadButton("▼", 64.dp, 44.dp, palette) { state.sendDpad(KeyEvent.KEYCODE_DPAD_DOWN) }
                DpadButton("▶", 64.dp, 44.dp, palette) { state.sendDpad(KeyEvent.KEYCODE_DPAD_RIGHT) }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Exit Text Editor
        Button(
            onClick = { state.mode = KeyboardMode.ALPHA },
            colors = ButtonDefaults.buttonColors(containerColor = palette.keyAction),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().height(38.dp)
        ) {
            Text("BACK TO KEYBOARD", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EditorActionButton(
    text: String,
    modifier: Modifier = Modifier,
    palette: KeyboardPalette,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(palette.keyNormal)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = palette.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DpadButton(
    symbol: String,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    palette: KeyboardPalette,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(width, height)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.keyNormal)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = symbol, color = palette.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}