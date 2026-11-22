package io.github.o7py.margin.model

enum class Tool { PEN, HIGHLIGHTER, ERASER }

enum class Mode { WRITING, READING, SMART }

/** What a single pen stroke will do, decided at pen-down. */
enum class StrokeAction {
    INK,            // pen ink
    HIGHLIGHT,      // text highlight where possible, else freehand highlight
    ERASE,          // whole-stroke eraser
    SMART,          // provisional ink, classifier may convert into a text highlight
}

object Palette {
    val pen = listOf(0xFF1A1A1A, 0xFF5F6368, 0xFFD93025, 0xFFF29900, 0xFF188038, 0xFF1A73E8, 0xFF8430CE, 0xFF795548).map { it.toInt() }
    val penNames = listOf("Black", "Dark gray", "Red", "Orange", "Green", "Blue", "Purple", "Brown")
    val highlighter = listOf(0xFFFFEB3B, 0xFF69F0AE, 0xFF64B5F6, 0xFFFF80AB, 0xFFFFB74D, 0xFFB39DDB).map { it.toInt() }
    val highlighterNames = listOf("Yellow", "Green", "Blue", "Pink", "Orange", "Purple")
    val pageBackgrounds = listOf(0xFFFFFFFF, 0xFFFBF6E9, 0xFFF1F3F4, 0xFFFFF9C4, 0xFFE3F2FD, 0xFFE8F5E9, 0xFF2B2B2B).map { it.toInt() }
    val pageBackgroundNames = listOf("White", "Cream", "Light gray", "Light yellow", "Light blue", "Light green", "Charcoal")

    /** Pen widths in pt at 100 % zoom. */
    val penWidths = listOf(0.6f, 1.2f, 2.0f, 3.5f)
    val penWidthNames = listOf("Fine", "Medium", "Bold", "Marker")
    /** Freehand highlighter widths in pt. Default ≈ 3× bold pen. */
    val highlighterWidths = listOf(6f, 12f)
    val highlighterWidthNames = listOf("Regular", "Wide")
}
