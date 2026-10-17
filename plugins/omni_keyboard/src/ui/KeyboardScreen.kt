package com.omni.plugin.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import com.omni.plugin.keyboard.model.KeyboardLayouts
import com.omni.plugin.keyboard.model.KeyboardMode
import com.omni.plugin.keyboard.model.ShiftState
import com.omni.plugin.keyboard.state.KeyboardState
import com.omni.plugin.keyboard.ui.components.*
import com.omni.plugin.keyboard.ui.theme.KeyboardTheme

@Composable
fun KeyboardScreen(
    state: KeyboardState,
    modifier: Modifier = Modifier
) {
    val palette = if (state.isDarkTheme) KeyboardTheme.dark else KeyboardTheme.light
    val scale = state.heightScalePercent / 100f
    val rowHeight = (KeyboardTheme.defaultRowHeight.value * scale).dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .background(palette.background)
            .navigationBarsPadding()
            .padding(bottom = 4.dp)
    ) {
        // 1. Suggestion & Utility Toolbar
        KeyboardToolbar(state = state, palette = palette)

        if (state.isPunctPopupVisible) {
            PunctuationPopup(
                palette = palette,
                onSelect = {
                    state.commitChar(it)
                    state.isPunctPopupVisible = false
                },
                onDismiss = { state.isPunctPopupVisible = false }
            )
        }

        if (state.mode == KeyboardMode.TEXT_EDITOR) {
            TextEditorPad(state = state, palette = palette)
            return@Column
        }

        if (state.mode == KeyboardMode.EMOJI) {
            EmojiPickerPad(state = state, palette = palette)
            return@Column
        }

        // 2. Optional Number Row
        if (state.showNumberRow) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight * 0.9f)
                    .padding(horizontal = 2.dp)
            ) {
                KeyboardLayouts.numberRow.forEach { key ->
                    CharKey(
                        spec = key,
                        shiftState = ShiftState.LOWERCASE,
                        palette = palette,
                        onPress = { state.commitChar(it) }
                    )
                }
            }
        }

        when (state.mode) {
            KeyboardMode.ALPHA -> {
                // Row 1 (QWERTYUIOP)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .padding(horizontal = 2.dp)
                ) {
                    KeyboardLayouts.qwertyRow1.forEach { key ->
                        CharKey(
                            spec = key,
                            shiftState = state.shiftState,
                            palette = palette,
                            onPress = { state.commitChar(it) },
                            onHintPress = { state.commitChar(it) }
                        )
                    }
                }

                // Row 2 (ASDFGHJKL)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .padding(horizontal = 14.dp)
                ) {
                    KeyboardLayouts.qwertyRow2.forEach { key ->
                        CharKey(
                            spec = key,
                            shiftState = state.shiftState,
                            palette = palette,
                            onPress = { state.commitChar(it) },
                            onHintPress = { state.commitChar(it) }
                        )
                    }
                }

                // Row 3 (Shift, ZXCVBNM, Backspace)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .padding(horizontal = 2.dp)
                ) {
                    FunctionKey(
                        label = when (state.shiftState) {
                            ShiftState.LOWERCASE -> "⇧"
                            ShiftState.SHIFTED -> "⇪"
                            ShiftState.CAPS_LOCKED -> "▲"
                        },
                        weight = 1.4f,
                        palette = palette,
                        isAction = state.shiftState != ShiftState.LOWERCASE,
                        fontSize = 18,
                        onClick = { state.cycleShift() }
                    )

                    KeyboardLayouts.qwertyRow3.forEach { key ->
                        CharKey(
                            spec = key,
                            shiftState = state.shiftState,
                            palette = palette,
                            onPress = { state.commitChar(it) },
                            onHintPress = { state.commitChar(it) }
                        )
                    }

                    FunctionKey(
                        label = "⌫",
                        weight = 1.4f,
                        palette = palette,
                        fontSize = 18,
                        onClick = { state.handleDelete() }
                    )
                }
            }

            KeyboardMode.SYMBOLS_PAGE_1,
            KeyboardMode.SYMBOLS_PAGE_2 -> {
                val r1 = if (state.mode == KeyboardMode.SYMBOLS_PAGE_1) KeyboardLayouts.symbolsPage1Row1 else KeyboardLayouts.symbolsPage2Row1
                val r2 = if (state.mode == KeyboardMode.SYMBOLS_PAGE_1) KeyboardLayouts.symbolsPage1Row2 else KeyboardLayouts.symbolsPage2Row2
                val r3 = if (state.mode == KeyboardMode.SYMBOLS_PAGE_1) KeyboardLayouts.symbolsPage1Row3 else KeyboardLayouts.symbolsPage2Row3

                Row(modifier = Modifier.fillMaxWidth().height(rowHeight).padding(horizontal = 2.dp)) {
                    r1.forEach { CharKey(it, ShiftState.LOWERCASE, palette, { state.commitChar(it) }) }
                }

                Row(modifier = Modifier.fillMaxWidth().height(rowHeight).padding(horizontal = 4.dp)) {
                    r2.forEach { CharKey(it, ShiftState.LOWERCASE, palette, { state.commitChar(it) }) }
                }

                Row(modifier = Modifier.fillMaxWidth().height(rowHeight).padding(horizontal = 2.dp)) {
                    FunctionKey(
                        label = if (state.mode == KeyboardMode.SYMBOLS_PAGE_1) "1/2" else "2/2",
                        weight = 1.4f,
                        palette = palette,
                        onClick = {
                            state.mode = if (state.mode == KeyboardMode.SYMBOLS_PAGE_1) KeyboardMode.SYMBOLS_PAGE_2 else KeyboardMode.SYMBOLS_PAGE_1
                        }
                    )

                    r3.forEach { CharKey(it, ShiftState.LOWERCASE, palette, { state.commitChar(it) }) }

                    FunctionKey(
                        label = "⌫",
                        weight = 1.4f,
                        palette = palette,
                        fontSize = 18,
                        onClick = { state.handleDelete() }
                    )
                }
            }

            else -> {}
        }

        // Bottom Row (Mode Switch ?123, Comma, Space, Period, Enter)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .padding(horizontal = 2.dp)
        ) {
            FunctionKey(
                label = if (state.mode == KeyboardMode.ALPHA) "?123" else "ABC",
                weight = 1.3f,
                palette = palette,
                fontSize = 13,
                onClick = {
                    state.mode = if (state.mode == KeyboardMode.ALPHA) KeyboardMode.SYMBOLS_PAGE_1 else KeyboardMode.ALPHA
                }
            )

            FunctionKey(
                label = ",",
                weight = 1.0f,
                palette = palette,
                onClick = { state.commitChar(",") },
                onLongClick = { state.mode = KeyboardMode.EMOJI }
            )

            SpaceBarKey(
                weight = 4.2f,
                languageLabel = if (state.isAmharic) "አማርኛ" else "English",
                palette = palette,
                onClick = { state.handleSpace() },
                onLongClick = { state.toggleLanguage() },
                onGlide = { steps -> state.onSpaceGlide(steps) }
            )

            FunctionKey(
                label = ".",
                weight = 1.0f,
                palette = palette,
                onClick = { state.commitChar(".") },
                onLongClick = { state.isPunctPopupVisible = true }
            )

            FunctionKey(
                label = "↵",
                weight = 1.5f,
                palette = palette,
                isAction = true,
                fontSize = 20,
                onClick = { state.handleEnter() }
            )
        }
    }
}