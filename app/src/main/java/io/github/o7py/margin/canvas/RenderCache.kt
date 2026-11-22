package io.github.o7py.margin.canvas

import android.graphics.Bitmap
import android.util.LruCache

/** LRU bitmap cache for low-res page bitmaps and full-res 512 px tiles (SPEC §11). */
class RenderCache(maxBytes: Int) {
    data class Key(val pageId: Long, val scaleBits: Int, val tx: Int, val ty: Int, val version: Int)

    private val pool = ArrayDeque<Bitmap>()

    private val lru = object : LruCache<Key, Bitmap>(maxBytes) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
        override fun entryRemoved(evicted: Boolean, key: Key, oldValue: Bitmap, newValue: Bitmap?) {
            if (oldValue.width == TILE && oldValue.height == TILE && pool.size < 12 && !oldValue.isRecycled) pool.addLast(oldValue)
        }
    }

    operator fun get(k: Key): Bitmap? = lru.get(k)
    fun put(k: Key, b: Bitmap) { lru.put(k, b) }
    fun contains(k: Key) = lru.get(k) != null

    fun obtainTile(): Bitmap = pool.removeFirstOrNull() ?: Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
    fun recycleToPool(b: Bitmap) { if (b.width == TILE && b.height == TILE && pool.size < 12) pool.addLast(b) }

    fun clear() { lru.evictAll(); pool.clear() }

    companion object {
        const val TILE = 512
        const val LOW_RES = -1 // scaleBits marker for the low-res whole-page bitmap
    }
}
