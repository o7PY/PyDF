package io.github.o7py.margin.canvas

import android.graphics.RectF
import io.github.o7py.margin.model.PageEntry

/**
 * Continuous vertical layout of pages in screen pixels (SPEC §11). Zoom is relative to fit-width.
 */
class PageLayout(private val density: Float) {
    var pages: List<PageEntry> = emptyList(); private set
    var viewW = 1f; private set
    var viewH = 1f; private set
    var zoom = 1f; private set
    var scrollX = 0f; private set
    var scrollY = 0f; private set

    val gap get() = 12f * density
    /** Extra space above the first page (toolbar overlay). */
    var topInset = 0f
    private val side get() = 8f * density
    var baseScale = 1f; private set
    val scale get() = baseScale * zoom
    private var tops = FloatArray(0)
    var contentW = 0f; private set
    var contentH = 0f; private set
    val maxZoom = 5f
    val minZoom: Float get() {
        val maxH = pages.maxOfOrNull { it.height } ?: return 1f
        return ((viewH - topInset - 2 * gap) / (maxH * baseScale)).coerceIn(0.1f, 1f)
    }

    fun setPages(p: List<PageEntry>) {
        val anchor = if (pages.isNotEmpty()) position() else null
        pages = p
        relayout()
        anchor?.let { restore(it.first.coerceAtMost(pages.size - 1), it.second) }
    }

    fun setViewSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w.toFloat() == viewW && h.toFloat() == viewH)) return
        // also valid before the first measure: position() is consistent with the provisional layout
        val anchor = if (pages.isNotEmpty()) position() else null
        viewW = w.toFloat(); viewH = h.toFloat()
        relayout()
        anchor?.let { restore(it.first, it.second) }
    }

    private fun relayout() {
        val maxW = pages.maxOfOrNull { it.width } ?: 612f
        // before the view is measured use scale 1; the real layout happens in setViewSize
        baseScale = if (viewW < 100f) 1f else (viewW - 2 * side) / maxW
        zoom = zoom.coerceIn(minZoom, maxZoom)
        val s = scale
        tops = FloatArray(pages.size)
        var y = gap + topInset
        for (i in pages.indices) { tops[i] = y; y += pages[i].height * s + gap }
        contentH = y
        contentW = maxOf(viewW, maxW * s + 2 * side)
        clamp()
    }

    fun pageTop(i: Int) = tops[i]
    fun pageLeft(i: Int) = (contentW - pages[i].width * scale) / 2f

    /** Screen rect of page [i]. */
    fun screenRect(i: Int, out: RectF = RectF()): RectF {
        val s = scale
        val l = pageLeft(i) - scrollX; val t = tops[i] - scrollY
        out.set(l, t, l + pages[i].width * s, t + pages[i].height * s)
        return out
    }

    fun visibleRange(): IntRange {
        if (pages.isEmpty()) return IntRange.EMPTY
        val first = indexAtContentY(scrollY)
        var last = first
        while (last + 1 < pages.size && tops[last + 1] < scrollY + viewH) last++
        return first..last
    }

    /** Page whose top ≤ y (content coordinates). */
    fun indexAtContentY(y: Float): Int {
        var lo = 0; var hi = pages.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (tops[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo.coerceAtLeast(0)
    }

    /** Page hit by a screen point, or -1 if the point is not on a page. */
    fun pageAt(sx: Float, sy: Float): Int {
        if (pages.isEmpty()) return -1
        val i = indexAtContentY(sy + scrollY)
        val r = screenRect(i)
        return if (r.contains(sx, sy)) i else -1
    }

    fun toPageX(i: Int, sx: Float) = (sx + scrollX - pageLeft(i)) / scale
    fun toPageY(i: Int, sy: Float) = (sy + scrollY - tops[i]) / scale
    fun toScreenX(i: Int, px: Float) = pageLeft(i) + px * scale - scrollX
    fun toScreenY(i: Int, py: Float) = tops[i] + py * scale - scrollY

    fun currentPage(): Int = if (pages.isEmpty()) 0 else indexAtContentY(scrollY + viewH / 2f)

    fun panBy(dx: Float, dy: Float) { scrollX -= dx; scrollY -= dy; clamp() }

    fun scrollTo(x: Float, y: Float) { scrollX = x; scrollY = y; clamp() }

    fun zoomBy(factor: Float, fx: Float, fy: Float) {
        if (pages.isEmpty()) return
        val cy = scrollY + fy; val cx = scrollX + fx
        val i = indexAtContentY(cy)
        val px = (cx - pageLeft(i)) / scale; val py = (cy - tops[i]) / scale
        zoom = (zoom * factor).coerceIn(minZoom, maxZoom)
        relayout()
        scrollX = pageLeft(i) + px * scale - fx
        scrollY = tops[i] + py * scale - fy
        clamp()
    }

    fun setZoom(z: Float) { zoom = z; relayout() }

    /** (page index, fraction of that page's height at the top edge of the viewport). */
    fun position(): Pair<Int, Float> {
        if (pages.isEmpty()) return 0 to 0f
        val i = indexAtContentY(scrollY)
        val h = pages[i].height * scale
        return i to ((scrollY - tops[i]) / h).coerceIn(-0.5f, 1f)
    }

    fun restore(page: Int, fraction: Float) {
        if (pages.isEmpty()) return
        val i = page.coerceIn(0, pages.size - 1)
        scrollY = tops[i] + fraction * pages[i].height * scale
        scrollX = (contentW - viewW) / 2f
        clamp()
    }

    fun maxScrollX() = maxOf(0f, contentW - viewW)
    fun maxScrollY() = maxOf(0f, contentH - viewH)

    private fun clamp() {
        scrollX = scrollX.coerceIn(0f, maxScrollX())
        scrollY = scrollY.coerceIn(0f, maxScrollY())
    }
}
