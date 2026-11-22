package io.github.o7py.margin.pdf

import android.graphics.Bitmap
import android.util.Log
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.DisplayList
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import io.github.o7py.margin.model.Annot
import io.github.o7py.margin.model.Box
import io.github.o7py.margin.model.CharGeom
import io.github.o7py.margin.model.InkPoint
import io.github.o7py.margin.model.PageEntry
import io.github.o7py.margin.model.PageText
import io.github.o7py.margin.model.PressureCurve
import io.github.o7py.margin.model.TextLineGeom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** All MuPDF calls run on one dedicated thread (MuPDF objects are not thread-safe). */
object MuPdfThread {
    val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mupdf").apply { priority = Thread.NORM_PRIORITY + 1 }
    }.asCoroutineDispatcher()
}

class MuPdfEngine : PdfEngine {
    private val ctx = MuPdfThread.dispatcher

    private var doc: PDFDocument? = null
    private var file: File? = null
    private var password: String? = null

    /** Current render-document order, as page ids. */
    private var layout: MutableList<Long> = mutableListOf()
    /** Page dictionaries by page id (originals captured on open, inserted ones created on demand). */
    private val pageObjs = HashMap<Long, PDFObject>()
    /** Page-space origin offsets of each page id (MuPDF bounds may not start at 0,0). */
    private val origins = HashMap<Long, Pair<Float, Float>>()
    private val imported = HashSet<Long>()
    /** Page id → page index in the current source [file]. */
    private val sourceIds = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    private val pageCache = object : LinkedHashMap<Long, PDFPage>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, PDFPage>?): Boolean {
            if (size > 8) { e?.value?.destroy(); return true }
            return false
        }
    }
    private val listCache = object : LinkedHashMap<Long, DisplayList>(6, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, DisplayList>?): Boolean {
            if (size > 6) { e?.value?.destroy(); return true }
            return false
        }
    }
    private val textCache = HashMap<Long, PageText>()

    override suspend fun open(file: File, password: String?): PdfEngine.OpenResult = withContext(ctx) {
        try {
            closeInternal()
            val d = Document.openDocument(file.absolutePath)
            if (d.needsPassword()) {
                if (password == null) { d.destroy(); return@withContext PdfEngine.OpenResult.NeedsPassword }
                if (!d.authenticatePassword(password)) { d.destroy(); return@withContext PdfEngine.OpenResult.WrongPassword }
            }
            val pdf = d.asPDF() ?: run { d.destroy(); return@withContext PdfEngine.OpenResult.Failed("Not a PDF file") }
            val n = pdf.countPages()
            if (n <= 0) return@withContext PdfEngine.OpenResult.Failed("This PDF has no pages")
            val pages = ArrayList<PageEntry>(n)
            for (i in 0 until n) {
                val p = pdf.loadPage(i)
                val b = p.bounds
                p.destroy()
                val id = i.toLong()
                origins[id] = b.x0 to b.y0
                pageObjs[id] = pdf.findPage(i)
                sourceIds[id] = i
                pages.add(PageEntry.Original(id, i, b.x1 - b.x0, b.y1 - b.y0))
            }
            doc = pdf; this@MuPdfEngine.file = file; this@MuPdfEngine.password = password
            layout = pages.map { it.id }.toMutableList()
            PdfEngine.OpenResult.Ok(pages)
        } catch (t: Throwable) {
            Log.w(TAG, "open failed", t)
            PdfEngine.OpenResult.Failed(t.message ?: "Could not open this PDF")
        }
    }

    private fun page(pageId: Long): PDFPage? {
        pageCache[pageId]?.let { return it }
        val d = doc ?: return null
        val idx = layout.indexOf(pageId)
        if (idx < 0) return null
        val p = d.loadPage(idx) as PDFPage
        pageCache[pageId] = p
        return p
    }

    override suspend fun importAnnotations(entry: PageEntry): List<Annot>? = withContext(ctx) {
        if (entry !is PageEntry.Original || !imported.add(entry.id)) return@withContext null
        val p = page(entry.id) ?: return@withContext null
        val (ox, oy) = origins[entry.id] ?: (0f to 0f)
        val out = ArrayList<Annot>()
        var changed = false
        try {
            for (a in p.annotations ?: emptyArray()) {
                val type = a.type
                if (type != PDFAnnotation.TYPE_INK && type != PDFAnnotation.TYPE_HIGHLIGHT) continue
                val nm = runCatching { a.`object`.get("NM")?.takeIf { it.isString }?.asString() }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: Annot.newId()
                val color = colorOf(a.color)
                val opacity = runCatching { a.opacity }.getOrDefault(1f)
                if (type == PDFAnnotation.TYPE_HIGHLIGHT) {
                    val quads = (a.quadPoints ?: emptyArray()).map { q ->
                        val r = q.toRect(); Box(r.x0 - ox, r.y0 - oy, r.x1 - ox, r.y1 - oy)
                    }
                    if (quads.isNotEmpty()) out.add(Annot.TextHighlight(nm, entry.id, color, quads, Annot.TEXT_HIGHLIGHT_OPACITY))
                } else {
                    val width = runCatching { a.borderWidth }.getOrDefault(1f).takeIf { it > 0f } ?: 1f
                    val ink = a.inkList ?: emptyArray()
                    val obj = a.`object`
                    val pressures = runCatching { obj.get("MarginP")?.takeIf { it.isArray } }.getOrNull()
                    val baseW = runCatching { obj.get("MarginW")?.takeIf { it.isNumber }?.asFloat() }.getOrNull()
                    ink.forEachIndexed { k, stroke ->
                        val id = if (k == 0) nm else "$nm#$k"
                        val pts = stroke.mapIndexed { j, pt ->
                            val pr = if (ink.size == 1 && pressures != null && j < pressures.size()) pressures.get(j).asFloat() else 1f
                            InkPoint(pt.x - ox, pt.y - oy, pr)
                        }
                        if (pts.isEmpty()) return@forEachIndexed
                        if (opacity < 0.6f) out.add(Annot.FreeHighlight(id, entry.id, color, width, pts, opacity))
                        else out.add(Annot.Ink(id, entry.id, color, baseW ?: width, pts))
                    }
                }
                p.deleteAnnotation(a)
                changed = true
            }
            if (changed) { p.update(); listCache.remove(entry.id)?.destroy() }
        } catch (t: Throwable) {
            Log.w(TAG, "annotation import failed on page ${entry.sourceIndex}", t)
        }
        out
    }

    override suspend fun render(pageId: Long, bitmap: Bitmap, scale: Float, left: Int, top: Int): Boolean = withContext(ctx) {
        try {
            val list = listCache[pageId] ?: run {
                val p = page(pageId) ?: return@withContext false
                p.toDisplayList().also { listCache[pageId] = it }
            }
            val (ox, oy) = origins[pageId] ?: (0f to 0f)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            val dev = AndroidDrawDevice(bitmap)
            val m = Matrix(scale, 0f, 0f, scale, -ox * scale - left, -oy * scale - top)
            // the scissor rect is in device (bitmap) space
            list.run(dev, m, Rect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), Cookie())
            dev.close(); dev.destroy()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "render failed", t); false
        }
    }

    override suspend fun pageText(pageId: Long): PageText = withContext(ctx) {
        textCache[pageId]?.let { return@withContext it }
        val result = try {
            val p = page(pageId) ?: return@withContext PageText.EMPTY
            val (ox, oy) = origins[pageId] ?: (0f to 0f)
            val st = p.toStructuredText()
            val lines = ArrayList<TextLineGeom>()
            var any = false
            for (block in st.blocks ?: emptyArray()) {
                for (line in block.lines ?: continue) {
                    val chars = ArrayList<CharGeom>()
                    for (c in line.chars ?: continue) {
                        val q = c.quad ?: continue
                        val r = q.toRect()
                        if (r.isEmpty || !r.isValid) continue
                        if (!c.isWhitespace) any = true
                        chars.add(CharGeom(Box(r.x0 - ox, r.y0 - oy, r.x1 - ox, r.y1 - oy), c = c.c))
                    }
                    if (chars.isEmpty()) continue
                    val b = line.bbox
                    lines.add(TextLineGeom(Box(b.x0 - ox, b.y0 - oy, b.x1 - ox, b.y1 - oy), chars))
                }
            }
            st.destroy()
            if (any) PageText(lines) else PageText.EMPTY
        } catch (t: Throwable) {
            Log.w(TAG, "text failed", t); PageText.EMPTY
        }
        textCache[pageId] = result
        result
    }

    override suspend fun outline(): List<io.github.o7py.margin.model.OutlineItem> = withContext(ctx) {
        val d = doc ?: return@withContext emptyList()
        val out = ArrayList<io.github.o7py.margin.model.OutlineItem>()
        fun walk(items: Array<com.artifex.mupdf.fitz.Outline>?, level: Int) {
            for (o in items ?: return) {
                try {
                    val dest = d.resolveLinkDestination(o)
                    val page = dest?.page ?: -1
                    if (page >= 0 && !o.title.isNullOrBlank()) {
                        val id = sourceIds.entries.firstOrNull { it.value == page }?.key ?: page.toLong()
                        val oy = origins[id]?.second ?: 0f
                        val y = dest.y.takeIf { !it.isNaN() && it > -1e5f && it < 1e5f }?.minus(oy) ?: 0f
                        out.add(io.github.o7py.margin.model.OutlineItem(o.title.trim(), level, id, y.coerceAtLeast(0f)))
                    }
                } catch (t: Throwable) { Log.w(TAG, "outline entry skipped", t) }
                walk(o.down, level + 1)
            }
        }
        try { walk(d.loadOutline(), 0) } catch (t: Throwable) { Log.w(TAG, "no outline", t) }
        out
    }

    override suspend fun applyLayout(pages: List<PageEntry>) = withContext(ctx) {
        val d = doc ?: return@withContext
        pageCache.values.forEach { it.destroy() }; pageCache.clear()
        for (e in pages) if (e is PageEntry.Inserted && e.id !in pageObjs) {
            pageObjs[e.id] = createBlankPage(d, e)
            origins[e.id] = 0f to 0f
        }
        syncTree(d, layout, pages.map { it.id }, pageObjs)
    }

    override suspend fun save(pages: List<PageEntry>, annots: Map<Long, List<Annot>>, out: File) = withContext(ctx) {
        val src = file ?: error("No document open")
        val live = doc ?: error("No document open")
        val fresh = Document.openDocument(src.absolutePath)
        try {
            if (fresh.needsPassword()) check(fresh.authenticatePassword(password ?: "")) { "Password rejected" }
            val pdf = fresh.asPDF() ?: error("Not a PDF")
            val n = pdf.countPages()
            val byIndex = HashMap<Int, Long>()
            sourceIds.forEach { (id, idx) -> byIndex[idx] = id }
            val current = MutableList(n) { byIndex[it] ?: (-1L - it) }
            val objs = HashMap<Long, PDFObject>()
            for (i in 0 until n) objs[current[i]] = pdf.findPage(i)
            // pages that are not in the source file (inserted, or restored by undo after a save) come from the live doc
            for (e in pages) if (!sourceIds.containsKey(e.id)) {
                val liveIdx = layout.indexOf(e.id)
                check(liveIdx >= 0) { "Page ${e.id} missing from render document" }
                val at = pdf.countPages()
                pdf.graftPage(at, live, liveIdx)
                objs[e.id] = pdf.findPage(at)
                current.add(e.id)
            }
            syncTree(pdf, current, pages.map { it.id }, objs)

            pages.forEachIndexed { index, entry ->
                val p = pdf.loadPage(index) as PDFPage
                val (ox, oy) = origins[entry.id] ?: (0f to 0f)
                // every Ink/Highlight was imported into the model before saving: replace them all
                for (a in p.annotations ?: emptyArray()) {
                    if (a.type == PDFAnnotation.TYPE_INK || a.type == PDFAnnotation.TYPE_HIGHLIGHT) p.deleteAnnotation(a)
                }
                for (a in annots[entry.id].orEmpty()) writeAnnot(pdf, p, a, ox, oy)
                p.update()
                p.destroy()
            }
            if (out.exists()) out.delete()
            pdf.save(out.absolutePath, "garbage,compress")
        } finally {
            fresh.destroy()
        }
        // verify it reopens
        val check = Document.openDocument(out.absolutePath)
        try {
            if (check.needsPassword()) check(check.authenticatePassword(password ?: "")) { "Saved file rejected password" }
            check(check.countPages() == pages.size) { "Saved file has wrong page count" }
            check.loadPage(0).destroy()
        } finally { check.destroy() }
    }

    override suspend fun rebase(newSource: File, pages: List<PageEntry>) = withContext(ctx) {
        file = newSource
        sourceIds.clear()
        pages.forEachIndexed { i, e -> sourceIds[e.id] = i }
    }

    override fun sourceIndexOf(pageId: Long): Int = sourceIds[pageId] ?: -1

    override suspend fun close() = withContext(ctx) { closeInternal() }

    private fun closeInternal() {
        pageCache.values.forEach { runCatching { it.destroy() } }; pageCache.clear()
        listCache.values.forEach { runCatching { it.destroy() } }; listCache.clear()
        textCache.clear(); pageObjs.clear(); sourceIds.clear(); origins.clear(); imported.clear(); layout.clear()
        doc?.destroy(); doc = null
    }

    private fun writeAnnot(pdf: PDFDocument, p: PDFPage, a: Annot, ox: Float, oy: Float) {
        when (a) {
            is Annot.Ink -> {
                val an = p.createAnnotation(PDFAnnotation.TYPE_INK)
                an.color = rgb(a.color)
                val mean = a.points.map { PressureCurve.factor(it.pressure) }.average().toFloat()
                an.borderWidth = a.baseWidth * mean
                an.inkList = arrayOf(a.points.map { Point(it.x + ox, it.y + oy) }.toTypedArray())
                val obj = an.`object`
                obj.put("NM", pdf.newString(a.id))
                val arr = pdf.newArray()
                a.points.forEach { arr.push(it.pressure) }
                obj.put("MarginP", arr)
                obj.put("MarginW", a.baseWidth)
                an.update()
            }
            is Annot.FreeHighlight -> {
                val an = p.createAnnotation(PDFAnnotation.TYPE_INK)
                an.color = rgb(a.color)
                an.borderWidth = a.width
                an.opacity = a.opacity
                an.inkList = arrayOf(a.points.map { Point(it.x + ox, it.y + oy) }.toTypedArray())
                an.`object`.put("NM", pdf.newString(a.id))
                an.update()
            }
            is Annot.TextHighlight -> {
                val an = p.createAnnotation(PDFAnnotation.TYPE_HIGHLIGHT)
                an.color = rgb(a.color)
                an.opacity = a.opacity
                an.quadPoints = a.quads.map { b ->
                    Quad(b.x0 + ox, b.y0 + oy, b.x1 + ox, b.y0 + oy, b.x0 + ox, b.y1 + oy, b.x1 + ox, b.y1 + oy)
                }.toTypedArray()
                an.`object`.put("NM", pdf.newString(a.id))
                an.update()
            }
        }
    }

    companion object {
        const val TAG = "Margin"

        fun rgb(c: Int) = floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)

        fun colorOf(f: FloatArray?): Int {
            if (f == null || f.isEmpty()) return 0xFFFFEB3B.toInt()
            fun b(v: Float) = (v.coerceIn(0f, 1f) * 255 + 0.5f).toInt()
            return when (f.size) {
                1 -> android.graphics.Color.rgb(b(f[0]), b(f[0]), b(f[0]))
                4 -> android.graphics.Color.rgb(b((1 - f[0]) * (1 - f[3])), b((1 - f[1]) * (1 - f[3])), b((1 - f[2]) * (1 - f[3])))
                else -> android.graphics.Color.rgb(b(f[0]), b(f[1]), b(f[2]))
            }
        }

        fun createBlankPage(d: PDFDocument, e: PageEntry.Inserted): PDFObject {
            val c = rgb(e.color)
            val content = "%.4f %.4f %.4f rg 0 0 %.3f %.3f re f".format(java.util.Locale.US, c[0], c[1], c[2], e.width, e.height)
            return d.addPage(Rect(0f, 0f, e.width, e.height), 0, d.newDictionary(), content)
        }

        /**
         * Edits the page tree from [current] (mutated) to [target] order with single inserts/deletes.
         */
        fun syncTree(d: PDFDocument, current: MutableList<Long>, target: List<Long>, objs: Map<Long, PDFObject>) {
            var i = 0
            while (i < target.size || i < current.size) {
                if (i < target.size && i < current.size && current[i] == target[i]) { i++; continue }
                val cur = current.getOrNull(i)
                val want = target.getOrNull(i)
                if (cur != null && (want == null || cur !in target || (want in current && current.indexOf(want) > i))) {
                    d.deletePage(i); current.removeAt(i)
                } else if (want != null) {
                    d.insertPage(i, objs[want] ?: error("Missing page object for $want"))
                    current.add(i, want)
                    i++
                }
            }
        }
    }
}
