package io.github.o7py.margin.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import io.github.o7py.margin.model.Annot

/**
 * Draws page bitmaps (low-res + tiles) and the committed editable annotations as vectors (Picture per page,
 * re-recorded only when the page's annotations change).
 */
class PagesView(context: Context, private val layout: PageLayout, private val cache: RenderCache) : View(context) {
    var source: PageSource? = null
    /** Annotations being erased: hidden here, faded out by the overlay. */
    var hiddenIds: Set<String> = emptySet()
        set(v) { field = v; invalidate() }
    var lowResScale = 0.5f
    /** Scale the current full-res tiles were requested for, and the previous one (shown while new tiles load). */
    var tileScale = 0f
    var prevTileScale = 0f
    /** Bumped when the layout changes so tiles of moved pages are not reused wrongly (keys use page id). */
    /** Night mode: pages (content + annotations) are drawn through [NightFilter]. */
    var night = false
        set(v) { field = v; invalidate() }
    private val nightPaint = NightFilter.layerPaint()

    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(28, 0, 0, 0) }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val src = Rect()
    private val dst = RectF()

    private class Layer(val picture: Picture, val version: Int, val hidden: Set<String>)
    private val layers = HashMap<Long, Layer>()

    fun dropLayers() { layers.clear() }

    override fun onDraw(c: Canvas) {
        val src0 = source ?: return
        val s = layout.scale
        for (i in layout.visibleRange()) {
            val page = layout.pages[i]
            layout.screenRect(i, rect)
            c.drawRect(rect.left, rect.top + 1.5f, rect.right + 1.5f, rect.bottom + 3f, shadowPaint)
            val nightLayer = if (night) c.saveLayer(rect, nightPaint) else -1
            c.drawRect(rect, pagePaint)
            // low-res underlay
            cache[RenderCache.Key(page.id, RenderCache.LOW_RES, 0, 0, 0)]?.let { b -> c.drawBitmap(b, null, rect, bmpPaint) }
            // full-res tiles (previous scale first as fallback)
            if (prevTileScale > 0f && prevTileScale != tileScale) drawTiles(c, page.id, i, prevTileScale, s)
            if (tileScale > 0f) drawTiles(c, page.id, i, tileScale, s)
            // vector annotations
            val annots = src0.annotsOn(page.id)
            if (annots.isNotEmpty()) {
                val layer = layerFor(page.id, annots, src0.pageVersion(page.id))
                c.save()
                c.clipRect(rect)
                c.translate(rect.left, rect.top)
                c.scale(s, s)
                c.drawPicture(layer.picture)
                c.restore()
            }
            if (nightLayer >= 0) c.restoreToCount(nightLayer)
        }
    }

    private fun drawTiles(c: Canvas, pageId: Long, i: Int, ts: Float, s: Float) {
        val page = layout.pages[i]
        val pw = page.width * ts; val ph = page.height * ts
        val k = s / ts
        val bits = ts.toRawBits()
        val left = layout.toScreenX(i, 0f); val top = layout.toScreenY(i, 0f)
        // visible tile range
        val tx0 = (((0f - left) / k) / RenderCache.TILE).toInt().coerceAtLeast(0)
        val ty0 = (((0f - top) / k) / RenderCache.TILE).toInt().coerceAtLeast(0)
        val tx1 = (((width - left) / k) / RenderCache.TILE).toInt().coerceAtMost(((pw - 1) / RenderCache.TILE).toInt())
        val ty1 = (((height - top) / k) / RenderCache.TILE).toInt().coerceAtMost(((ph - 1) / RenderCache.TILE).toInt())
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val b = cache[RenderCache.Key(pageId, bits, tx, ty, 0)] ?: continue
            val x0 = tx * RenderCache.TILE; val y0 = ty * RenderCache.TILE
            val w = minOf(RenderCache.TILE.toFloat(), pw - x0).toInt().coerceAtLeast(1)
            val h = minOf(RenderCache.TILE.toFloat(), ph - y0).toInt().coerceAtLeast(1)
            src.set(0, 0, w, h)
            dst.set(left + x0 * k, top + y0 * k, left + (x0 + w) * k, top + (y0 + h) * k)
            c.drawBitmap(b, src, dst, bmpPaint)
        }
    }

    private fun layerFor(pageId: Long, annots: List<Annot>, version: Int): Layer {
        val cur = layers[pageId]
        if (cur != null && cur.version == version && cur.hidden == hiddenIds) return cur
        val page = layout.pages.firstOrNull { it.id == pageId }
        val pic = Picture()
        val pc = pic.beginRecording(((page?.width ?: 1000f) + 1).toInt(), ((page?.height ?: 1000f) + 1).toInt())
        for (a in annots) if (a.id !in hiddenIds) AnnotPainter.draw(pc, a)
        pic.endRecording()
        return Layer(pic, version, hiddenIds).also { layers[pageId] = it }
    }

    fun lowResBitmap(pageW: Float, pageH: Float): Bitmap =
        Bitmap.createBitmap((pageW * lowResScale).toInt().coerceAtLeast(1), (pageH * lowResScale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
}
