package io.github.o7py.margin.edit

import io.github.o7py.margin.model.Annot
import io.github.o7py.margin.model.PageEntry

/** The mutable in-memory edit model of one open document. */
class EditState(pages: List<PageEntry>) {
    val pages: MutableList<PageEntry> = pages.toMutableList()
    private val annots = HashMap<Long, MutableList<Annot>>()
    /** Bumped on every change to annotations of a page (for render caches). */
    private val pageVersions = HashMap<Long, Int>()
    var layoutVersion = 0; private set
    var nextPageId: Long = (pages.maxOfOrNull { it.id } ?: -1L) + 1

    fun annotsOn(pageId: Long): List<Annot> = annots[pageId] ?: emptyList()
    fun allAnnots(): Map<Long, List<Annot>> = annots
    fun pageVersion(pageId: Long) = pageVersions[pageId] ?: 0
    private fun touch(pageId: Long) { pageVersions[pageId] = pageVersion(pageId) + 1 }

    fun add(a: Annot, index: Int = -1) {
        val l = annots.getOrPut(a.pageId) { ArrayList() }
        if (index in 0..l.size) l.add(index, a) else l.add(a)
        touch(a.pageId)
    }

    /** Removes [a]; returns its former index or -1. */
    fun remove(a: Annot): Int {
        val l = annots[a.pageId] ?: return -1
        val i = l.indexOfFirst { it.id == a.id }
        if (i >= 0) { l.removeAt(i); touch(a.pageId) }
        return i
    }

    fun indexOf(a: Annot) = annots[a.pageId]?.indexOfFirst { it.id == a.id } ?: -1

    fun insertPage(index: Int, entry: PageEntry) { pages.add(index, entry); layoutVersion++ }
    fun removePage(index: Int): PageEntry { layoutVersion++; return pages.removeAt(index) }
    fun pageIndex(pageId: Long) = pages.indexOfFirst { it.id == pageId }
    fun newPageId() = nextPageId++
}
