package io.github.o7py.margin.input

/**
 * Every input threshold in one place (SPEC §6.4). Editable on the device from Developer tuning.
 * Defaults reflect docs/device-notes.md (Tab S6 Lite).
 */
data class InputTuning(
    /** Max finger touchMajor for starting navigation, in dp. 0 = disabled (Tab S6 Lite reports synthetic sizes). */
    val maxTouchMajorDp: Float = 0f,
    /** Second finger must land within this many ms of the first. */
    val pairWindowMs: Long = 250,
    /** Fingers can't start navigation until this long after the stylus lifted. */
    val stylusCooldownMs: Long = 400,
    /** Pinch distance change needed before a two-finger gesture starts zooming (prevents zoom jitter while scrolling). */
    val zoomStartDp: Float = 48f,
    /** Smart Reading: max angle between stroke and text baseline. */
    val classifierAngleDeg: Float = 20f,
    /** Smart Reading: min fraction of points inside the expanded line band. */
    val classifierBandFraction: Float = 0.75f,
    /** Smart Reading: stroke length before deciding. */
    val decisionDistanceDp: Float = 24f,
    /** Smart Reading: max horizontal direction reversals. */
    val classifierMaxReversals: Int = 1,
    /** Smart Reading: max vertical span as a multiple of line height. */
    val classifierMaxSpanLines: Float = 1.2f,
    /** Smart Reading: line bbox expansion as a fraction of line height. */
    val lineExpandFraction: Float = 0.15f,
    /** Smart Reading: median stroke y may be at most this fraction of line height from the line centre (rejects underlines). */
    val classifierMaxCenterOffset: Float = 0.22f,
    /** Pen pressed and held still this long on text = highlight (0 = off). */
    val dwellHighlightMs: Long = 350,
    /** Movement within this radius still counts as holding still. */
    val dwellRadiusDp: Float = 4f,
    /** A shorter/quicker touch on text is an ink dot, not a highlight. */
    val tapMaxMs: Long = 150,
    /** Whole-stroke eraser hit radius at screen scale. */
    val eraserRadiusDp: Float = 8f,
    /** Draw touch circles, classifier decisions and line bands. */
    val debugOverlays: Boolean = false,
)
