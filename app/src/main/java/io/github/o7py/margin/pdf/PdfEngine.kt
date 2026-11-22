package io.github.o7py.margin.pdf

import android.graphics.Bitmap
import io.github.o7py.margin.model.Annot
import io.github.o7py.margin.model.PageEntry
import io.github.o7py.margin.model.PageText
import java.io.File

/**
 * Engine abstraction (SPEC §5) so MuPDF (AGPL) can be swapped. All geometry is page space with the page's
 * top-left at (0, 0), y down, in points.
 */
interface PdfEngine {
    sealed class OpenResult {
        data class Ok(val pages: List<PageEntry>) : OpenResult()
        object NeedsPassword : OpenResult()
        object WrongPassword : OpenResult()
        data class Failed(val message: String) : OpenResult()
    }

    suspend fun open(file: File, password: String?): OpenResult

    /**
     * Imports the editable (Ink/Highlight) annotations of an original page and removes them from the render
     * document so the app draws them as vectors. Idempotent; returns the page's annotations on first call
     * and null afterwards.
     */
    suspend fun importAnnotations(entry: PageEntry): List<Annot>?

    /** Renders the [pageId] area starting at pixel (left, top) of the page scaled by [scale] into [bitmap]. */
    suspend fun render(pageId: Long, bitmap: Bitmap, scale: Float, left: Int, top: Int): Boolean

    suspend fun pageText(pageId: Long): PageText

    /** The document outline, flattened depth-first; empty if the PDF has none. */
    suspend fun outline(): List<io.github.o7py.margin.model.OutlineItem>

    /** Makes the render document's page order match [pages] (inserted pages are created on demand). */
    suspend fun applyLayout(pages: List<PageEntry>)

    /** Writes a new full PDF with [pages] and [annots] to [out], then verifies it reopens. */
    suspend fun save(pages: List<PageEntry>, annots: Map<Long, List<Annot>>, out: File)

    /** After a successful save, [newSource] (the saved file, page order = [pages]) becomes the save baseline. */
    suspend fun rebase(newSource: File, pages: List<PageEntry>)

    /** Index of [pageId] in the current source file, or -1 if it only exists in this session. */
    fun sourceIndexOf(pageId: Long): Int

    suspend fun close()
}
