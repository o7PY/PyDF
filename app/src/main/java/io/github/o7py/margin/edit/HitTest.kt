package io.github.o7py.margin.edit

import io.github.o7py.margin.model.Annot
import io.github.o7py.margin.model.Box
import io.github.o7py.margin.model.Geometry
import io.github.o7py.margin.model.InkPoint
import io.github.o7py.margin.model.PressureCurve

/** Whole-stroke eraser hit-testing (SPEC §7.1). Pure, page coordinates. */
object HitTest {

    /** True if the eraser segment a→b with [radius] touches [annot]. */
    fun hits(annot: Annot, ax: Float, ay: Float, bx: Float, by: Float, radius: Float): Boolean {
        val seg = Box(minOf(ax, bx), minOf(ay, by), maxOf(ax, bx), maxOf(ay, by)).expand(radius)
        if (!seg.intersects(annot.bounds)) return false
        return when (annot) {
            is Annot.Ink -> polylineHit(annot.points, ax, ay, bx, by) { p -> radius + annot.baseWidth * PressureCurve.factor(p.pressure) / 2f }
            is Annot.FreeHighlight -> polylineHit(annot.points, ax, ay, bx, by) { radius + annot.width / 2f }
            is Annot.TextHighlight -> annot.quads.any { q -> segBoxHit(q.expand(radius), ax, ay, bx, by) }
        }
    }

    private inline fun polylineHit(pts: List<InkPoint>, ax: Float, ay: Float, bx: Float, by: Float, r: (InkPoint) -> Float): Boolean {
        if (pts.size == 1) return Geometry.pointSegDist(pts[0].x, pts[0].y, ax, ay, bx, by) <= r(pts[0])
        for (i in 1 until pts.size) {
            val p = pts[i - 1]; val q = pts[i]
            if (Geometry.segSegDist(p.x, p.y, q.x, q.y, ax, ay, bx, by) <= r(q)) return true
        }
        return false
    }

    private fun segBoxHit(b: Box, ax: Float, ay: Float, bx: Float, by: Float): Boolean {
        if (b.contains(ax, ay) || b.contains(bx, by)) return true
        return Geometry.segmentsIntersect(ax, ay, bx, by, b.x0, b.y0, b.x1, b.y0) ||
            Geometry.segmentsIntersect(ax, ay, bx, by, b.x1, b.y0, b.x1, b.y1) ||
            Geometry.segmentsIntersect(ax, ay, bx, by, b.x1, b.y1, b.x0, b.y1) ||
            Geometry.segmentsIntersect(ax, ay, bx, by, b.x0, b.y1, b.x0, b.y0)
    }
}
