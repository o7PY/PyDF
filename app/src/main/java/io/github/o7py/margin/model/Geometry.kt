package io.github.o7py.margin.model

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A sampled pen point in PDF page coordinates (points, MuPDF convention: origin top-left, y down). */
data class InkPoint(val x: Float, val y: Float, val pressure: Float = 1f, val t: Long = 0L)

data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val width get() = x1 - x0
    val height get() = y1 - y0
    val cx get() = (x0 + x1) / 2f
    val cy get() = (y0 + y1) / 2f
    fun contains(x: Float, y: Float) = x in x0..x1 && y in y0..y1
    fun expand(dx: Float, dy: Float = dx) = Box(x0 - dx, y0 - dy, x1 + dx, y1 + dy)
    fun intersects(o: Box) = x0 <= o.x1 && o.x0 <= x1 && y0 <= o.y1 && o.y0 <= y1
    fun union(o: Box) = Box(min(x0, o.x0), min(y0, o.y0), max(x1, o.x1), max(y1, o.y1))

    companion object {
        fun of(points: List<InkPoint>, pad: Float = 0f): Box {
            var a = Float.MAX_VALUE; var b = Float.MAX_VALUE; var c = -Float.MAX_VALUE; var d = -Float.MAX_VALUE
            for (p in points) { a = min(a, p.x); b = min(b, p.y); c = max(c, p.x); d = max(d, p.y) }
            if (points.isEmpty()) return Box(0f, 0f, 0f, 0f)
            return Box(a - pad, b - pad, c + pad, d + pad)
        }
    }
}

object Geometry {
    fun dist(ax: Float, ay: Float, bx: Float, by: Float) = hypot(ax - bx, ay - by)

    /** Distance from point p to segment ab. */
    fun pointSegDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-9f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** Minimal distance between segments ab and cd. */
    fun segSegDist(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Float {
        if (segmentsIntersect(ax, ay, bx, by, cx, cy, dx, dy)) return 0f
        return minOf(
            pointSegDist(ax, ay, cx, cy, dx, dy), pointSegDist(bx, by, cx, cy, dx, dy),
            pointSegDist(cx, cy, ax, ay, bx, by), pointSegDist(dx, dy, ax, ay, bx, by)
        )
    }

    private fun cross(ox: Float, oy: Float, ax: Float, ay: Float, bx: Float, by: Float) = (ax - ox) * (by - oy) - (ay - oy) * (bx - ox)

    fun segmentsIntersect(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Boolean {
        val d1 = cross(cx, cy, dx, dy, ax, ay); val d2 = cross(cx, cy, dx, dy, bx, by)
        val d3 = cross(ax, ay, bx, by, cx, cy); val d4 = cross(ax, ay, bx, by, dx, dy)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    /** Distance from a point to a box (0 if inside). */
    fun pointBoxDist(px: Float, py: Float, b: Box): Float {
        val dx = max(max(b.x0 - px, 0f), px - b.x1)
        val dy = max(max(b.y0 - py, 0f), py - b.y1)
        return hypot(dx, dy)
    }

    /** Ramer–Douglas–Peucker simplification. Always keeps first and last point. */
    fun simplify(points: List<InkPoint>, tolerance: Float): List<InkPoint> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true; keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            var maxD = 0f; var idx = -1
            val a = points[s]; val b = points[e]
            for (i in s + 1 until e) {
                val d = pointSegDist(points[i].x, points[i].y, a.x, a.y, b.x, b.y)
                // keep points whose pressure differs noticeably, so width variation survives simplification
                val pd = abs(points[i].pressure - (a.pressure + b.pressure) / 2f) * 2f
                val score = max(d, if (pd > 0.25f) tolerance * 1.01f else 0f)
                if (score > maxD) { maxD = score; idx = i }
            }
            if (idx >= 0 && maxD > tolerance) {
                keep[idx] = true
                stack.addLast(s to idx); stack.addLast(idx to e)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Light exponential smoothing of positions (pressure untouched). Keeps endpoints exact. */
    fun smooth(points: List<InkPoint>, alpha: Float = 0.5f): List<InkPoint> {
        if (points.size < 3) return points
        val out = ArrayList<InkPoint>(points.size)
        out.add(points[0])
        var sx = points[0].x; var sy = points[0].y
        for (i in 1 until points.size - 1) {
            val p = points[i]
            sx += alpha * (p.x - sx); sy += alpha * (p.y - sy)
            out.add(p.copy(x = sx, y = sy))
        }
        out.add(points.last())
        return out
    }

    fun pathLength(points: List<InkPoint>): Float {
        var l = 0f
        for (i in 1 until points.size) l += dist(points[i - 1].x, points[i - 1].y, points[i].x, points[i].y)
        return l
    }
}
