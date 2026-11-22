package io.github.o7py.margin.edit

import io.github.o7py.margin.model.Box
import io.github.o7py.margin.model.InkPoint
import io.github.o7py.margin.model.PageText
import kotlin.math.abs

/** Maps a stroke to a character range and highlight boxes (SPEC §8.3). Pure. */
object TextSnapper {

    /** Global char index hit by (x, y), or null. Uses each line's expanded band and the nearest char in it. */
    fun charAt(text: PageText, x: Float, y: Float, lineExpand: Float = 0.15f): Int? {
        var bestLine = -1
        var bestD = Float.MAX_VALUE
        text.lines.forEachIndexed { i, l ->
            val e = l.height * lineExpand
            if (y >= l.bbox.y0 - e && y <= l.bbox.y1 + e && x >= l.bbox.x0 - l.height && x <= l.bbox.x1 + l.height) {
                val d = abs(y - l.bbox.cy)
                if (d < bestD) { bestD = d; bestLine = i }
            }
        }
        if (bestLine < 0) return null
        var idx: Int? = null
        var best = Float.MAX_VALUE
        val base = text.lineStart[bestLine]
        text.lines[bestLine].chars.forEachIndexed { ci, c ->
            val d = when {
                x < c.box.x0 -> c.box.x0 - x
                x > c.box.x1 -> x - c.box.x1
                else -> 0f
            }
            if (d < best) { best = d; idx = base + ci }
        }
        return idx
    }

    /** First..last char hit in reading order, or null if the stroke touches no text. */
    fun range(text: PageText, points: List<InkPoint>, lineExpand: Float = 0.15f): IntRange? {
        var lo = Int.MAX_VALUE; var hi = Int.MIN_VALUE
        for (p in points) {
            val i = charAt(text, p.x, p.y, lineExpand) ?: continue
            if (i < lo) lo = i
            if (i > hi) hi = i
        }
        return if (lo <= hi) lo..hi else null
    }

    /** One box per line covered by [range]: char extent in x, line bbox in y. */
    fun boxes(text: PageText, range: IntRange): List<Box> {
        val out = ArrayList<Box>()
        var base = 0
        for (line in text.lines) {
            val start = base; val end = base + line.chars.size - 1
            base += line.chars.size
            if (end < range.first || start > range.last || line.chars.isEmpty()) continue
            val a = maxOf(start, range.first) - start
            val b = minOf(end, range.last) - start
            var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE
            for (k in a..b) { x0 = minOf(x0, line.chars[k].box.x0); x1 = maxOf(x1, line.chars[k].box.x1) }
            out.add(Box(x0, line.bbox.y0, x1, line.bbox.y1))
        }
        return out
    }

    /**
     * Incremental snapping for live strokes: feed only new points; keeps the first..last char range.
     * O(new points) per update instead of O(all points).
     */
    class Accumulator(private val text: PageText, private val lineExpand: Float) {
        private var lo = Int.MAX_VALUE
        private var hi = Int.MIN_VALUE
        private var processed = 0
        private var cachedRange: IntRange? = null
        private var cachedBoxes: List<Box> = emptyList()

        fun update(points: List<InkPoint>) {
            for (k in processed until points.size) {
                val i = charAt(text, points[k].x, points[k].y, lineExpand) ?: continue
                if (i < lo) lo = i
                if (i > hi) hi = i
            }
            processed = points.size
        }

        val range: IntRange? get() = if (lo <= hi) lo..hi else null

        fun boxes(): List<Box> {
            val r = range ?: return emptyList()
            if (r != cachedRange) { cachedRange = r; cachedBoxes = boxes(text, r) }
            return cachedBoxes
        }
    }

    fun snap(text: PageText, points: List<InkPoint>, lineExpand: Float = 0.15f): List<Box>? =
        range(text, points, lineExpand)?.let { boxes(text, it) }

    // ------------------------------------------------------------------ selection helpers

    fun isSpace(c: Int) = c == 0x20 || c == 0xA0 || Character.isWhitespace(c)

    /** True when there's a visible gap between chars [a] and [b] (b = a + 1) on the same line, i.e. a missing space. */
    private fun gapBetween(text: PageText, a: Int, b: Int): Boolean {
        val ca = text.chars[a]; val cb = text.chars[b]
        if (ca.line != cb.line) return true
        val h = text.lines[ca.line].height
        return cb.box.x0 - ca.box.x1 > h * 0.25f
    }

    /** The word containing char [idx]: a run of non-space chars on one line, split at visible gaps. */
    fun wordAt(text: PageText, idx: Int): IntRange {
        val chars = text.chars
        if (idx !in chars.indices) return IntRange.EMPTY
        if (isSpace(chars[idx].c)) return idx..idx
        var a = idx; var b = idx
        while (a > 0 && !isSpace(chars[a - 1].c) && !gapBetween(text, a - 1, a)) a--
        while (b < chars.size - 1 && !isSpace(chars[b + 1].c) && !gapBetween(text, b, b + 1)) b++
        return a..b
    }

    /** Nearest char to (x, y) with no distance limit: nearest line vertically, then nearest char in it. */
    fun nearestChar(text: PageText, x: Float, y: Float): Int? {
        if (text.lines.isEmpty()) return null
        var li = 0; var best = Float.MAX_VALUE
        // PDFs often split one visual line into several text lines (math, font changes): score by vertical
        // distance first, horizontal distance to the line's extent second
        text.lines.forEachIndexed { i, l ->
            val dy = when { y < l.bbox.y0 -> l.bbox.y0 - y; y > l.bbox.y1 -> y - l.bbox.y1; else -> 0f }
            val dx = when { x < l.bbox.x0 -> l.bbox.x0 - x; x > l.bbox.x1 -> x - l.bbox.x1; else -> 0f }
            val d = dy * 4f + dx
            if (d < best) { best = d; li = i }
        }
        val base = text.lineStart[li]
        val line = text.lines[li]
        var ci = 0; var bd = Float.MAX_VALUE
        line.chars.forEachIndexed { k, c ->
            val d = kotlin.math.abs(x - c.box.cx)
            if (d < bd) { bd = d; ci = k }
        }
        return base + ci
    }

    /** Plain text of [range]: newline between lines, a space where the PDF has a visible gap but no space char. */
    fun textOf(text: PageText, range: IntRange): String {
        val sb = StringBuilder()
        val chars = text.chars
        for (i in range) {
            if (i !in chars.indices) continue
            if (i > range.first && i - 1 in chars.indices) {
                val prev = chars[i - 1]
                if (prev.line != chars[i].line) { if (sb.isNotEmpty() && sb.last() != ' ') sb.append('\n') else if (sb.isNotEmpty()) sb.setCharAt(sb.length - 1, '\n') }
                else if (gapBetween(text, i - 1, i) && !isSpace(prev.c) && !isSpace(chars[i].c)) sb.append(' ')
            }
            val c = chars[i].c
            if (c > 0) sb.appendCodePoint(c)
        }
        return sb.toString().trim()
    }
}
