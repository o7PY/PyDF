package io.github.o7py.margin.canvas

import android.graphics.Bitmap
import io.github.o7py.margin.edit.Command
import io.github.o7py.margin.model.Annot
import io.github.o7py.margin.model.PageText

/** What the canvas needs from the open document session. */
interface PageSource {
    /** Makes sure the page's editable annotations are imported before it is rendered. */
    suspend fun prepare(pageId: Long)
    suspend fun render(pageId: Long, bitmap: Bitmap, scale: Float, left: Int, top: Int): Boolean
    suspend fun text(pageId: Long): PageText
    /** Text already loaded for the page, or null if not yet known. */
    fun cachedText(pageId: Long): PageText?
    fun annotsOn(pageId: Long): List<Annot>
    fun pageVersion(pageId: Long): Int
    fun execute(c: Command)
}
