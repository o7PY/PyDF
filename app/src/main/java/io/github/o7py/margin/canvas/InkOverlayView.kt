package io.github.o7py.margin.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import io.github.o7py.margin.model.Annot
import io.github.o7py.margin.model.Box
import io.github.o7py.margin.model.InkPoint

/**
 * Live layer (SPEC §11): the in-progress stroke with predicted points, Smart Reading cross-fade, erase fade-outs
 * and debug overlays. Separate view so invalidating it doesn't re-record the page layer.
 */
class InkOverlayView(context: Context, private val layout: PageLayout) : View(context) {

    enum class LiveKind { NONE, INK, FREE_HIGHLIGHT, TEXT_HIGHLIGHT, ERASE }

    var kind = LiveKind.NONE
    var pageIndex = -1
    var points: List<InkPoint> = emptyList()
    var predicted: List<InkPoint> = emptyList()
    var color = Color.BLACK
    var strokeW = 1f
    var opacity = 1f
    var boxes: List<Box> = emptyList()
    /** Smart Reading: provisional ink fading out while the highlight fades in. */
    var crossfadeStart = 0L
    var crossfadeInk: List<InkPoint> = emptyList()
    var crossfadeInkColor = Color.BLACK
    var crossfadeInkWidth = 1f
    var animationsEnabled = true
    /** Night mode: live strokes are drawn through the same filter as the pages. */
    var night = false
    private val nightPaint = NightFilter.layerPaint()

    private class Fading(val annot: Annot, val pageIndex: Int, val start: Long)
    private val fading = ArrayList<Fading>()

    // debug overlays
    var debug = false
    var debugTouches: List<Triple<Float, Float, Float>> = emptyList() // x, y, radius px
    var debugText: String = ""
    var debugBands: List<Pair<Int, Box>> = emptyList() // page index, band in page coords

    private val dbgCircle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.rgb(24, 128, 56) }
    private val dbgBand = Paint().apply { color = Color.argb(40, 61, 90, 254) }
    private val dbgTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 36f; color = Color.rgb(61, 90, 254) }
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(160, 95, 99, 104) }
    var eraserCursor: Triple<Float, Float, Float>? = null // screen x, y, radius

    // ---- scroll bar
    var scrollbar = false
    var thumbDragging = false
    private val dp = context.resources.displayMetrics.density
    private val thumbH get() = 72f * dp
    private val barMarginTop get() = layout.topInset + 8f * dp
    private val barMarginBottom get() = 16f * dp
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(30, 0, 0, 0) }

    private fun trackLen() = (height - barMarginTop - barMarginBottom - thumbH).coerceAtLeast(1f)
    fun thumbCenterY(): Float {
        val max = layout.maxScrollY()
        val f = if (max <= 0f) 0f else layout.scrollY / max
        return barMarginTop + thumbH / 2f + f * trackLen()
    }
    fun scrollForThumbY(centerY: Float): Float {
        val f = ((centerY - barMarginTop - thumbH / 2f) / trackLen()).coerceIn(0f, 1f)
        return f * layout.maxScrollY()
    }
    /** Generous touch target: the right 40 dp strip, within the thumb's height plus slack. */
    fun scrollbarHit(x: Float, y: Float) = scrollbar && x >= width - 40f * dp && kotlin.math.abs(y - thumbCenterY()) <= thumbH / 2f + 16f * dp

    private fun drawScrollbar(c: Canvas) {
        if (!scrollbar || layout.maxScrollY() <= 0f) return
        val w = if (thumbDragging) 10f * dp else 6f * dp
        val x1 = width - 6f * dp
        c.drawRoundRect(x1 - 4f * dp, barMarginTop, x1 - 2f * dp, height - barMarginBottom, dp, dp, trackPaint)
        val cy = thumbCenterY()
        thumbPaint.color = if (thumbDragging) selectionColor else Color.argb(150, 95, 99, 104)
        c.drawRoundRect(x1 - w / 2f - 3f * dp, cy - thumbH / 2f, x1 + w / 2f - 3f * dp, cy + thumbH / 2f, w / 2f, w / 2f, thumbPaint)
    }

    /** Text selection: page index + boxes in page coordinates. */
    var selectionPage = -1
    var selectionBoxes: List<Box> = emptyList()
    var selectionColor = Color.rgb(61, 90, 254)
    private val selPaint = Paint().apply { style = Paint.Style.FILL }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val handleRadius = 11f * context.resources.displayMetrics.density

    /** Screen positions of the start/end handle centres, or null. */
    fun handleCenters(): Pair<Pair<Float, Float>, Pair<Float, Float>>? {
        if (selectionPage !in layout.pages.indices || selectionBoxes.isEmpty()) return null
        val a = selectionBoxes.first(); val b = selectionBoxes.last()
        val p = selectionPage
        return (layout.toScreenX(p, a.x0) to layout.toScreenY(p, a.y1) + handleRadius) to
            (layout.toScreenX(p, b.x1) to layout.toScreenY(p, b.y1) + handleRadius)
    }

    fun fadeOut(a: Annot, pageIndex: Int) {
        if (!animationsEnabled) return
        fading.add(Fading(a, pageIndex, SystemClock.uptimeMillis())); postInvalidateOnAnimation()
    }

    fun clearLive() {
        kind = LiveKind.NONE; points = emptyList(); predicted = emptyList(); boxes = emptyList(); eraserCursor = null
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val s = layout.scale
        val nightLayer = if (night && (fading.isNotEmpty() || kind != LiveKind.NONE)) c.saveLayer(null, nightPaint) else -1
        // erased items fading out (120 ms)
        if (fading.isNotEmpty()) {
            val it = fading.iterator()
            while (it.hasNext()) {
                val f = it.next()
                val t = (now - f.start) / 120f
                if (t >= 1f || f.pageIndex >= layout.pages.size) { it.remove(); continue }
                c.save()
                c.translate(layout.toScreenX(f.pageIndex, 0f), layout.toScreenY(f.pageIndex, 0f))
                c.scale(s, s)
                AnnotPainter.draw(c, f.annot, 1f - t)
                c.restore()
            }
            if (fading.isNotEmpty()) postInvalidateOnAnimation()
        }
        if (pageIndex in layout.pages.indices) {
            val pi = pageIndex
            val mx: (InkPoint) -> Float = { layout.toScreenX(pi, it.x) }
            val my: (InkPoint) -> Float = { layout.toScreenY(pi, it.y) }
            val all = if (predicted.isEmpty()) points else points + predicted
            when (kind) {
                LiveKind.INK -> AnnotPainter.drawInk(c, all, strokeW, color, 1f, s, mx, my)
                LiveKind.FREE_HIGHLIGHT -> AnnotPainter.drawFreeHighlight(c, all, strokeW, color, opacity, s, mx, my)
                LiveKind.TEXT_HIGHLIGHT -> {
                    var a = 1f
                    if (crossfadeStart > 0L) {
                        val t = if (animationsEnabled) ((now - crossfadeStart) / 100f).coerceIn(0f, 1f) else 1f
                        a = t
                        if (t < 1f) {
                            AnnotPainter.drawInk(c, crossfadeInk, crossfadeInkWidth, crossfadeInkColor, 1f - t, s, mx, my)
                            postInvalidateOnAnimation()
                        } else crossfadeStart = 0L
                    }
                    AnnotPainter.drawTextHighlight(c, boxes, color, opacity * a) { b ->
                        Box(layout.toScreenX(pi, b.x0), layout.toScreenY(pi, b.y0), layout.toScreenX(pi, b.x1), layout.toScreenY(pi, b.y1))
                    }
                }
                else -> {}
            }
        }
        if (nightLayer >= 0) c.restoreToCount(nightLayer)
        eraserCursor?.let { (x, y, r) -> c.drawCircle(x, y, r, eraserPaint) }
        if (selectionPage in layout.pages.indices && selectionBoxes.isNotEmpty()) {
            val p = selectionPage
            selPaint.color = (selectionColor and 0x00FFFFFF) or (0x55 shl 24)
            for (b in selectionBoxes) c.drawRect(layout.toScreenX(p, b.x0), layout.toScreenY(p, b.y0), layout.toScreenX(p, b.x1), layout.toScreenY(p, b.y1), selPaint)
            handlePaint.color = selectionColor
            handleCenters()?.let { (h1, h2) ->
                for ((x, y) in listOf(h1, h2)) {
                    c.drawRect(x - 1.5f, y - handleRadius - 4f, x + 1.5f, y, handlePaint)
                    c.drawCircle(x, y, handleRadius, handlePaint)
                }
            }
        }
        drawScrollbar(c)
        if (debug) {
            for ((pi, b) in debugBands) if (pi in layout.pages.indices) {
                c.drawRect(layout.toScreenX(pi, b.x0), layout.toScreenY(pi, b.y0), layout.toScreenX(pi, b.x1), layout.toScreenY(pi, b.y1), dbgBand)
            }
            for ((x, y, r) in debugTouches) c.drawCircle(x, y, r, dbgCircle)
            if (debugText.isNotEmpty()) c.drawText(debugText, 24f, height - 140f, dbgTextPaint)
        }
    }
}
