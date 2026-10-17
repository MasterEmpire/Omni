package com.omni.plugin.keyboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.plugin.keyboard.model.KeySpec
import com.omni.plugin.keyboard.model.ShiftState
import com.omni.plugin.keyboard.ui.theme.KeyboardPalette
import com.omni.plugin.keyboard.ui.theme.KeyboardTheme

@Composable
fun RowScope.KeyContainer(
    weight: Float,
    palette: KeyboardPalette,
    isAction: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val bgColor = when {
        isPressed -> palette.keyPressed
        isAction -> palette.keyAction
        else -> palette.keyNormal
    }

    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .padding(KeyboardTheme.keyPadding)
            .clip(RoundedCornerShape(KeyboardTheme.keyCornerRadius))
            .background(bgColor)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val press = androidx.compose.foundation.interaction.PressInteraction.Press(it)
                        interactionSource.emit(press)
                        tryAwaitRelease()
                        interactionSource.emit(androidx.compose.foundation.interaction.PressInteraction.Release(press))
                    },
                    onTap = { onClick() },
                    onLongPress = { onLongClick?.invoke() }
                )
            },
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
fun RowScope.CharKey(
    spec: KeySpec,
    shiftState: ShiftState,
    palette: KeyboardPalette,
    onPress: (String) -> Unit,
    onHintPress: ((String) -> Unit)? = null
) {
    val displayChar = if (shiftState != ShiftState.LOWERCASE && spec.primary.length == 1) {
        spec.primary.uppercase()
    } else {
        spec.primary
    }

    KeyContainer(
        weight = spec.weight,
        palette = palette,
        isAction = spec.isAction,
        onClick = { onPress(displayChar) },
        onLongClick = {
            if (spec.hint != null && onHintPress != null) {
                onHintPress(spec.hint)
            }
        }
    ) {
        Text(
            text = displayChar,
            color = palette.textPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )

        if (spec.hint != null) {
            Text(
                text = spec.hint,
                color = palette.textSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 5.dp)
            )
        }
    }
}

@Composable
fun RowScope.FunctionKey(
    label: String,
    weight: Float = 1.4f,
    palette: KeyboardPalette,
    isAction: Boolean = false,
    fontSize: Int = 16,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    KeyContainer(
        weight = weight,
        palette = palette,
        isAction = isAction,
        onClick = onClick,
        onLongClick = onLongClick
    ) {
        Text(
            text = label,
            color = palette.textPrimary,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun RowScope.SpaceBarKey(
    weight: Float = 4.2f,
    languageLabel: String,
    palette: KeyboardPalette,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    KeyContainer(
        weight = weight,
        palette = palette,
        onClick = onClick,
        onLongClick = onLongClick
    ) {
        Text(
            text = languageLabel,
            color = palette.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}