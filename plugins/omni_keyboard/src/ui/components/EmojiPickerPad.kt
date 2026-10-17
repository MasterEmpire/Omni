package com.omni.plugin.keyboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.omni.plugin.keyboard.model.EmojiData
import com.omni.plugin.keyboard.model.KeyboardMode
import com.omni.plugin.keyboard.state.KeyboardState
import com.omni.plugin.keyboard.ui.theme.KeyboardPalette

@Composable
fun EmojiPickerPad(
    state: KeyboardState,
    palette: KeyboardPalette,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(palette.background)
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(EmojiData.list) { emoji ->
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { state.commitChar(emoji) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = emoji, fontSize = 24.sp)
                }
            }
        }

        // Bottom Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { state.mode = KeyboardMode.ALPHA },
                colors = ButtonDefaults.buttonColors(containerColor = palette.keyNormal),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1.5f).fillMaxHeight()
            ) {
                Text("ABC", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            Button(
                onClick = { state.handleSpace() },
                colors = ButtonDefaults.buttonColors(containerColor = palette.keyNormal),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(4f).fillMaxHeight()
            ) {
                Text("Space", color = palette.textSecondary, fontSize = 12.sp)
            }

            Button(
                onClick = { state.handleDelete() },
                colors = ButtonDefaults.buttonColors(containerColor = palette.keyNormal),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1.5f).fillMaxHeight()
            ) {
                Text("⌫", color = palette.textPrimary, fontSize = 16.sp)
            }

            Button(
                onClick = { state.handleEnter() },
                colors = ButtonDefaults.buttonColors(containerColor = palette.keyAction),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1.5f).fillMaxHeight()
            ) {
                Text("↵", color = palette.textPrimary, fontSize = 16.sp)
            }
        }
    }
}