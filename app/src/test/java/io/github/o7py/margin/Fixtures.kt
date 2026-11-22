package io.github.o7py.margin

import io.github.o7py.margin.model.Box
import io.github.o7py.margin.model.CharGeom
import io.github.o7py.margin.model.InkPoint
import io.github.o7py.margin.model.PageText
import io.github.o7py.margin.model.TextLineGeom
import kotlin.math.cos
import kotlin.math.sin

object Fixtures {
    const val LINE_H = 12f
    const val CHAR_W = 6f

    /** A page with [lines] lines of [chars] chars each, starting at (72, 72), 16 pt leading. Text from [content] if given. */
    fun page(lines: Int = 5, chars: Int = 60, content: List<String>? = null): PageText = PageText((0 until lines).map { li ->
        val y0 = 72f + li * 16f
        val str = content?.getOrNull(li)
        val n = str?.length ?: chars
        val cs = (0 until n).map { ci -> CharGeom(Box(72f + ci * CHAR_W, y0, 72f + (ci + 1) * CHAR_W, y0 + LINE_H), c = str?.get(ci)?.code ?: 'x'.code) }
        val chars = n
        TextLineGeom(Box(72f, y0, 72f + chars * CHAR_W, y0 + LINE_H), cs)
    })

    fun line(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 30, dtMs: Long = 8, jitter: Float = 0f): List<InkPoint> =
        (0 until n).map { i ->
            val t = i / (n - 1f)
            val j = if (jitter == 0f) 0f else jitter * sin(i * 1.7f)
            InkPoint(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t + j, 0.5f, i * dtMs)
        }

    fun circle(cx: Float, cy: Float, rx: Float, ry: Float, n: Int = 60): List<InkPoint> =
        (0..n).map { i ->
            val a = Math.PI + 2 * Math.PI * i / n
            InkPoint((cx + rx * cos(a)).toFloat(), (cy + ry * sin(a)).toFloat(), 0.5f, i * 8L)
        }

    /** Cursive-like handwriting: loops moving right with big vertical motion and back-strokes. */
    fun handwriting(x0: Float, cy: Float, letters: Int = 6): List<InkPoint> {
        val pts = ArrayList<InkPoint>()
        var t = 0L
        for (k in 0 until letters) {
            for (i in 0..20) {
                val a = 2 * Math.PI * i / 20
                val x = x0 + k * 10f + 6f * sin(a).toFloat() + i * 0.4f
                val y = cy - 8f * cos(a).toFloat()
                pts.add(InkPoint(x, y, 0.5f, t)); t += 8
            }
        }
        return pts
    }
}
