package com.omni.plugin.keyboard.model

enum class KeyboardMode {
    ALPHA,
    SYMBOLS_PAGE_1,
    SYMBOLS_PAGE_2,
    EMOJI,
    TEXT_EDITOR
}

enum class ShiftState {
    LOWERCASE,
    SHIFTED,
    CAPS_LOCKED
}

data class KeySpec(
    val primary: String,
    val hint: String? = null,
    val weight: Float = 1f,
    val isAction: Boolean = false
)

object KeyboardLayouts {
    val numberRow = listOf(
        KeySpec("1"), KeySpec("2"), KeySpec("3"), KeySpec("4"), KeySpec("5"),
        KeySpec("6"), KeySpec("7"), KeySpec("8"), KeySpec("9"), KeySpec("0")
    )

    val qwertyRow1 = listOf(
        KeySpec("q", "1"), KeySpec("w", "2"), KeySpec("e", "3"), KeySpec("r", "4"), KeySpec("t", "5"),
        KeySpec("y", "6"), KeySpec("u", "7"), KeySpec("i", "8"), KeySpec("o", "9"), KeySpec("p", "0")
    )

    val qwertyRow2 = listOf(
        KeySpec("a", "@"), KeySpec("s", "#"), KeySpec("d", "$"), KeySpec("f", "%"), KeySpec("g", "&"),
        KeySpec("h"), KeySpec("j"), KeySpec("k"), KeySpec("l")
    )

    val qwertyRow3 = listOf(
        KeySpec("z", "-"), KeySpec("x", "'"), KeySpec("c", "\""), KeySpec("v", ":"),
        KeySpec("b", ";"), KeySpec("n"), KeySpec("m")
    )

    val symbolsPage1Row1 = listOf(
        KeySpec("1"), KeySpec("2"), KeySpec("3"), KeySpec("4"), KeySpec("5"),
        KeySpec("6"), KeySpec("7"), KeySpec("8"), KeySpec("9"), KeySpec("0")
    )

    val symbolsPage1Row2 = listOf(
        KeySpec("@"), KeySpec("#"), KeySpec("$"), KeySpec("%"), KeySpec("&"),
        KeySpec("-"), KeySpec("+"), KeySpec("("), KeySpec(")")
    )

    val symbolsPage1Row3 = listOf(
        KeySpec("!"), KeySpec("\""), KeySpec(":"), KeySpec(";"),
        KeySpec("/"), KeySpec("?")
    )

    val symbolsPage2Row1 = listOf(
        KeySpec("~"), KeySpec("`"), KeySpec("|"), KeySpec("•"), KeySpec("√"),
        KeySpec("π"), KeySpec("÷"), KeySpec("×"), KeySpec("¶"), KeySpec("Δ")
    )

    val symbolsPage2Row2 = listOf(
        KeySpec("£"), KeySpec("€"), KeySpec("¥"), KeySpec("¢"), KeySpec("^"),
        KeySpec("_"), KeySpec("="), KeySpec("{"), KeySpec("}")
    )

    val symbolsPage2Row3 = listOf(
        KeySpec("\\"), KeySpec("'"), KeySpec("<"), KeySpec(">"),
        KeySpec("["), KeySpec("]")
    )
}