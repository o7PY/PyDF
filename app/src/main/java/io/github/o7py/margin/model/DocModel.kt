package io.github.o7py.margin.model

/**
 * One page in the edited document's page order. [id] is stable for the session (annotations key on it),
 * so inserting/deleting pages never re-targets annotations.
 */
sealed class PageEntry {
    abstract val id: Long
    abstract val width: Float
    abstract val height: Float

    /** A page that exists in the source file at [sourceIndex]. */
    data class Original(override val id: Long, val sourceIndex: Int, override val width: Float, override val height: Float) : PageEntry()

    /** A blank page inserted in this session, filled with [color]. */
    data class Inserted(override val id: Long, override val width: Float, override val height: Float, val color: Int) : PageEntry()
}

/** Per-page text geometry from MuPDF structured text, in reading order. */
class PageText(val lines: List<TextLineGeom>) {
    val hasText: Boolean get() = lines.isNotEmpty()
    /** Flattened characters in reading order, each with its line index. */
    val chars: List<CharGeom> = lines.flatMapIndexed { li, l -> l.chars.map { it.copy(line = li) } }
    /** Global index of each line's first char. */
    val lineStart: IntArray = IntArray(lines.size).also { a -> var n = 0; lines.forEachIndexed { i, l -> a[i] = n; n += l.chars.size } }

    companion object { val EMPTY = PageText(emptyList()) }
}

/** One character: its box, its line index in reading order, and its Unicode code point ([c] = 0 if unknown). */
data class CharGeom(val box: Box, val line: Int = 0, val c: Int = 0)

data class TextLineGeom(val bbox: Box, val chars: List<CharGeom>) {
    val height get() = bbox.height
}

/** One entry of the PDF's document outline (bookmarks / table of contents), flattened with its depth. */
data class OutlineItem(val title: String, val level: Int, val pageId: Long, val y: Float)
