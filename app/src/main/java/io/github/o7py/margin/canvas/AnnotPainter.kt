package io.github.o7py.margin.canvas

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import io.github.o7py.margin.model.Annot
import io.github.o7py.margin.model.Box
import io.github.o7py.margin.model.InkPoint
import io.github.o7py.margin.model.PressureCurve

/** Draws annotations in page coordinates (caller applies the page → screen transform). */
object AnnotPainter {
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.ROUND
    }
    private val textHlPaint = Paint().apply { style = Paint.Style.FILL; blendMode = BlendMode.MULTIPLY }
    private val path = Path()

    fun draw(c: Canvas, a: Annot, alpha: Float = 1f) {
        when (a) {
            is Annot.Ink -> drawInk(c, a.points, a.baseWidth, a.color, alpha)
            is Annot.FreeHighlight -> drawFreeHighlight(c, a.points, a.width, a.color, a.opacity * alpha)
            is Annot.TextHighlight -> drawTextHighlight(c, a.quads, a.color, a.opacity * alpha)
        }
    }

    fun withAlpha(color: Int, a: Float) = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    /** Variable-width ink: one round-capped segment per point pair. [scaleWidth] converts width to the canvas units. */
    fun drawInk(c: Canvas, pts: List<InkPoint>, baseWidth: Float, color: Int, alpha: Float = 1f, scaleWidth: Float = 1f,
                mapX: (InkPoint) -> Float = { it.x }, mapY: (InkPoint) -> Float = { it.y }) {
        if (pts.isEmpty()) return
        val col = withAlpha(color, alpha)
        if (pts.size == 1) {
            dotPaint.color = col
            c.drawCircle(mapX(pts[0]), mapY(pts[0]), baseWidth * scaleWidth * PressureCurve.factor(pts[0].pressure) / 2f, dotPaint)
            return
        }
        inkPaint.color = col
        if (alpha < 1f) {
            // translucent (fading) ink: avoid dark joints by drawing a single path at mean width
            val mean = pts.map { PressureCurve.factor(it.pressure) }.average().toFloat()
            inkPaint.strokeWidth = baseWidth * scaleWidth * mean
            path.rewind(); path.moveTo(mapX(pts[0]), mapY(pts[0]))
            for (i in 1 until pts.size) path.lineTo(mapX(pts[i]), mapY(pts[i]))
            c.drawPath(path, inkPaint)
            return
        }
        var px = mapX(pts[0]); var py = mapY(pts[0])
        for (i in 1 until pts.size) {
            val p = pts[i]
            val x = mapX(p); val y = mapY(p)
            inkPaint.strokeWidth = baseWidth * scaleWidth * PressureCurve.factor((pts[i - 1].pressure + p.pressure) / 2f)
            c.drawLine(px, py, x, y, inkPaint)
            px = x; py = y
        }
    }

    fun drawFreeHighlight(c: Canvas, pts: List<InkPoint>, width: Float, color: Int, opacity: Float, scaleWidth: Float = 1f,
                          mapX: (InkPoint) -> Float = { it.x }, mapY: (InkPoint) -> Float = { it.y }) {
        if (pts.isEmpty()) return
        hlPaint.color = withAlpha(color, opacity)
        hlPaint.strokeWidth = width * scaleWidth
        path.rewind()
        path.moveTo(mapX(pts[0]), mapY(pts[0]))
        if (pts.size == 1) path.lineTo(mapX(pts[0]) + 0.01f, mapY(pts[0]))
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            path.quadTo(mapX(a), mapY(a), (mapX(a) + mapX(b)) / 2f, (mapY(a) + mapY(b)) / 2f)
        }
        path.lineTo(mapX(pts.last()), mapY(pts.last()))
        c.drawPath(path, hlPaint)
    }

    fun drawTextHighlight(c: Canvas, quads: List<Box>, color: Int, opacity: Float,
                          mapBox: (Box) -> Box = { it }) {
        textHlPaint.color = withAlpha(color, opacity)
        for (q in quads) {
            val b = mapBox(q)
            c.drawRect(b.x0, b.y0, b.x1, b.y1, textHlPaint)
        }
    }
}
