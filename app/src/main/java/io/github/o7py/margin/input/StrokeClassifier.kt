package io.github.o7py.margin.input

import io.github.o7py.margin.model.Geometry
import io.github.o7py.margin.model.InkPoint
import io.github.o7py.margin.model.PageText
import io.github.o7py.margin.model.TextLineGeom
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Smart Reading classifier (SPEC §8.2). Pure functions over page-space points and line geometry.
 * Distances in [Params] are in page points (the caller converts dp → pt using the current zoom).
 */
object StrokeClassifier {

    enum class Decision { UNDECIDED, HIGHLIGHT, INK }

    data class Params(
        val decisionDistance: Float,
        val maxAngleDeg: Float = 20f,
        val minBandFraction: Float = 0.75f,
        val maxReversals: Int = 1,
        val maxSpanLines: Float = 1.2f,
        val lineExpand: Float = 0.15f,
        val maxCenterOffset: Float = 0.22f,
        val tapMaxMs: Long = 150,
        /** Pen movement within this radius of the start counts as "resting" (press before highlighting). */
        val dwellRadius: Float = 0f,
    ) {
        companion object {
            fun from(t: InputTuning, ptPerDp: Float) = Params(
                decisionDistance = t.decisionDistanceDp * ptPerDp,
                maxAngleDeg = t.classifierAngleDeg,
                minBandFraction = t.classifierBandFraction,
                maxReversals = t.classifierMaxReversals,
                maxSpanLines = t.classifierMaxSpanLines,
                lineExpand = t.lineExpandFraction,
                maxCenterOffset = t.classifierMaxCenterOffset,
                tapMaxMs = t.tapMaxMs,
                dwellRadius = t.dwellRadiusDp * ptPerDp,
            )
        }
    }

    /** Step 1: index of the text line whose expanded bbox contains (x, y), or null. */
    fun lineAt(text: PageText, x: Float, y: Float, expand: Float): Int? {
        var best: Int? = null
        var bestD = Float.MAX_VALUE
        text.lines.forEachIndexed { i, l ->
            val e = l.height * expand
            if (l.bbox.expand(e, e).contains(x, y)) {
                val d = abs(y - l.bbox.cy)
                if (d < bestD) { bestD = d; best = i }
            }
        }
        return best
    }

    /**
     * Steps 2–5. [points] is the stroke so far; [finished] is true at pen-up.
     * Returns UNDECIDED only while the stroke is shorter than the decision distance and not finished.
     */
    fun classify(points: List<InkPoint>, line: TextLineGeom, p: Params, finished: Boolean): Decision {
        if (points.isEmpty()) return Decision.INK
        // ignore the initial rest (pen pressed down before moving): its jitter is not part of the stroke shape
        val moving = trimDwell(points, p.dwellRadius)
        // decide on real displacement from pen-down, not path length: jitter while resting must not trigger it
        val travel = maxDisplacement(points)
        if (travel < p.decisionDistance) {
            if (!finished) return Decision.UNDECIDED
            val duration = points.last().t - points.first().t
            if (duration < p.tapMaxMs || moving.size < 2) return Decision.INK // quick tap or no movement → ink dot
        }
        return if (looksLikeHighlight(moving, line, p)) Decision.HIGHLIGHT else Decision.INK
    }

    /** Drops leading points that stay within [radius] of the first point (keeps the last of them as the start). */
    fun trimDwell(points: List<InkPoint>, radius: Float): List<InkPoint> {
        if (radius <= 0f || points.size < 3) return points
        val p0 = points.first()
        var i = 0
        while (i + 1 < points.size && Geometry.dist(points[i + 1].x, points[i + 1].y, p0.x, p0.y) <= radius) i++
        return points.subList(i, points.size)
    }

    /** How long the pen rested within [radius] of its first point before moving (ms). */
    fun dwellMs(points: List<InkPoint>, radius: Float): Long {
        if (points.isEmpty()) return 0
        val p0 = points.first()
        var last = p0.t
        for (q in points) {
            if (Geometry.dist(q.x, q.y, p0.x, p0.y) > radius) break
            last = q.t
        }
        return last - p0.t
    }

    fun maxDisplacement(points: List<InkPoint>): Float {
        if (points.isEmpty()) return 0f
        val p0 = points.first()
        var m = 0f
        for (q in points) m = maxOf(m, Geometry.dist(q.x, q.y, p0.x, p0.y))
        return m
    }

    fun looksLikeHighlight(points: List<InkPoint>, line: TextLineGeom, p: Params): Boolean {
        val h = line.height.coerceAtLeast(1f)
        // angle of principal direction vs. baseline (horizontal)
        if (principalAngleDeg(points) > p.maxAngleDeg) return false
        // fraction of points inside the expanded line band
        val e = h * p.lineExpand
        val top = line.bbox.y0 - e; val bottom = line.bbox.y1 + e
        val inBand = points.count { it.y in top..bottom }
        if (inBand.toFloat() / points.size < p.minBandFraction) return false
        // vertical span
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        if (maxY - minY > p.maxSpanLines * h) return false
        // direction reversals in x (handwriting has many)
        if (xReversals(points, h * 0.3f) > p.maxReversals) return false
        // stroke must run through the line, not under/over it (rejects underlines)
        val ys = points.map { it.y }.sorted()
        val median = ys[ys.size / 2]
        if (abs(median - line.bbox.cy) > p.maxCenterOffset * h) return false
        return true
    }

    /** Angle (0..90°) between the stroke's principal axis and the horizontal. */
    fun principalAngleDeg(points: List<InkPoint>): Float {
        if (points.size < 2) return 0f
        var mx = 0.0; var my = 0.0
        for (q in points) { mx += q.x; my += q.y }
        mx /= points.size; my /= points.size
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (q in points) {
            val dx = q.x - mx; val dy = q.y - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val theta = 0.5 * atan2(2 * sxy, sxx - syy) // major axis angle
        var deg = abs(Math.toDegrees(theta)).toFloat()
        if (deg > 90f) deg = 180f - deg
        // a near-isotropic blob (circle) has no meaningful direction: treat as not horizontal
        val tr = sxx + syy
        val det = sxx * syy - sxy * sxy
        val disc = sqrt(((tr * tr) / 4 - det).coerceAtLeast(0.0))
        val l1 = tr / 2 + disc; val l2 = tr / 2 - disc
        if (l1 > 0 && l2 / l1 > 0.25) return 90f
        return deg
    }

    /** Number of sign changes of horizontal motion, ignoring wiggles smaller than [hysteresis]. */
    fun xReversals(points: List<InkPoint>, hysteresis: Float): Int {
        var dir = 0
        var anchor = points.first().x
        var reversals = 0
        for (q in points) {
            val d = q.x - anchor
            when {
                dir >= 0 && d < -hysteresis -> { if (dir > 0) reversals++; dir = -1; anchor = q.x }
                dir <= 0 && d > hysteresis -> { if (dir < 0) reversals++; dir = 1; anchor = q.x }
                dir > 0 && q.x > anchor -> anchor = q.x
                dir < 0 && q.x < anchor -> anchor = q.x
            }
        }
        return reversals
    }
}
