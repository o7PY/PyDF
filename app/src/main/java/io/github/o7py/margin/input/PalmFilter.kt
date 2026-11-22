package io.github.o7py.margin.input

import android.view.MotionEvent

enum class PointerKind { STYLUS, ERASER, FINGER }

/**
 * Pointer classification for the page canvas (SPEC §6.1) and Samsung S Pen quirks (docs/device-notes.md).
 */
object PalmFilter {
    /** Samsung "S Pen side button held" actions observed on the Tab S6 Lite. */
    const val SAMSUNG_PEN_DOWN = 211
    const val SAMSUNG_PEN_UP = 212
    const val SAMSUNG_PEN_MOVE = 213
    const val SAMSUNG_PEN_CANCEL = 214

    fun kind(toolType: Int): PointerKind = when (toolType) {
        MotionEvent.TOOL_TYPE_STYLUS -> PointerKind.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> PointerKind.ERASER
        else -> PointerKind.FINGER // FINGER, UNKNOWN, MOUSE: never draws
    }

    fun isPen(kind: PointerKind) = kind != PointerKind.FINGER

    /** Maps Samsung's custom actions onto standard ones; returns the input action otherwise. */
    fun normalizeAction(actionMasked: Int): Int = when (actionMasked) {
        SAMSUNG_PEN_DOWN -> MotionEvent.ACTION_DOWN
        SAMSUNG_PEN_UP -> MotionEvent.ACTION_UP
        SAMSUNG_PEN_MOVE -> MotionEvent.ACTION_MOVE
        SAMSUNG_PEN_CANCEL -> MotionEvent.ACTION_CANCEL
        else -> actionMasked
    }

    fun isSamsungButtonAction(actionMasked: Int) = actionMasked in SAMSUNG_PEN_DOWN..SAMSUNG_PEN_CANCEL

    /** "Button held" per SPEC §6.1, extended with Samsung's custom actions. */
    fun buttonHeld(actionMasked: Int, buttonState: Int, toolType: Int): Boolean =
        isSamsungButtonAction(actionMasked) ||
            (buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0 ||
            toolType == MotionEvent.TOOL_TYPE_ERASER
}
