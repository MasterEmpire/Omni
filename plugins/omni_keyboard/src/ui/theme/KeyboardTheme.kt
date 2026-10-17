package com.omni.plugin.keyboard.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class KeyboardPalette(
    val background: Color,
    val keyNormal: Color,
    val keyPressed: Color,
    val keyAction: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val toolbarIcon: Color
)

object KeyboardTheme {
    val dark = KeyboardPalette(
        background = Color(0xFF141414),
        keyNormal = Color(0xFF2D313A),
        keyPressed = Color(0xFF3E424D),
        keyAction = Color(0xFF4B5A75),
        textPrimary = Color(0xFFE2E2E6),
        textSecondary = Color(0xFF8E9199),
        accent = Color(0xFF58A6FF),
        toolbarIcon = Color(0xFFC4C7CF)
    )

    val light = KeyboardPalette(
        background = Color(0xFFECECEC),
        keyNormal = Color(0xFFFFFFFF),
        keyPressed = Color(0xFFDCDCDC),
        keyAction = Color(0xFF4B5A75),
        textPrimary = Color(0xFF202124),
        textSecondary = Color(0xFF5F6368),
        accent = Color(0xFF1F6FEB),
        toolbarIcon = Color(0xFF5F6368)
    )

    val keyCornerRadius = 8.dp
    val keyPadding = 3.dp
    val defaultRowHeight = 48.dp
}