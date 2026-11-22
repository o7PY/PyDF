package io.github.o7py.margin.model

import java.util.UUID

/** Editable annotation in the in-memory model. All geometry is in PDF page coordinates. */
sealed class Annot {
    abstract val id: String          // /NM value; "margin-<uuid>" for app-created
    abstract val pageId: Long        // stable page identity (see PageEntry)
    abstract val color: Int          // ARGB, alpha ignored (opacity is separate)
    abstract val bounds: Box

    /** Opaque pen ink: pressure-sensitive width. */
    data class Ink(
        override val id: String,
        override val pageId: Long,
        override val color: Int,
        val baseWidth: Float,
        val points: List<InkPoint>,
    ) : Annot() {
        override val bounds: Box = Box.of(points, baseWidth)
    }

    /** Freehand highlight: wide, translucent, flat caps, no pressure. */
    data class FreeHighlight(
        override val id: String,
        override val pageId: Long,
        override val color: Int,
        val width: Float,
        val points: List<InkPoint>,
        val opacity: Float = FREE_HIGHLIGHT_OPACITY,
    ) : Annot() {
        override val bounds: Box = Box.of(points, width / 2f)
    }

    /** Text highlight: snapped character quads, stored as axis-aligned boxes (one or more per line). */
    data class TextHighlight(
        override val id: String,
        override val pageId: Long,
        override val color: Int,
        val quads: List<Box>,
        val opacity: Float = TEXT_HIGHLIGHT_OPACITY,
    ) : Annot() {
        override val bounds: Box = quads.reduceOrNull { a, b -> a.union(b) } ?: Box(0f, 0f, 0f, 0f)
    }

    companion object {
        const val FREE_HIGHLIGHT_OPACITY = 0.35f
        const val TEXT_HIGHLIGHT_OPACITY = 0.4f
        fun newId() = "margin-" + UUID.randomUUID().toString()
    }
}

/** Pressure → width multiplier (SPEC §8.1: gamma ≈ 0.6, clamp min 35 % of base). */
object PressureCurve {
    const val GAMMA = 0.6f
    const val MIN_FRACTION = 0.35f
    fun factor(pressure: Float): Float {
        val p = pressure.coerceIn(0f, 1f)
        return Math.pow(p.toDouble(), GAMMA.toDouble()).toFloat().coerceIn(MIN_FRACTION, 1f)
    }
}
