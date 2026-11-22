package io.github.o7py.margin.input

import io.github.o7py.margin.model.StrokeAction
import io.github.o7py.margin.model.Tool

/**
 * Pen tip/button → action. The app has a single "smart" mode: the Pen highlights text when the stroke is a
 * horizontal swipe along a line (StrokeClassifier), and is ink otherwise. The side button always erases.
 */
object ModeController {

    fun actionFor(selected: Tool, buttonHeld: Boolean, pageHasText: Boolean): StrokeAction = when {
        buttonHeld -> StrokeAction.ERASE
        selected == Tool.ERASER -> StrokeAction.ERASE
        selected == Tool.HIGHLIGHTER -> StrokeAction.HIGHLIGHT
        pageHasText -> StrokeAction.SMART
        else -> StrokeAction.INK
    }
}
