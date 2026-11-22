package io.github.o7py.margin.data

import android.content.Context
import android.util.Log
import io.github.o7py.margin.model.Annot
import io.github.o7py.margin.model.Box
import io.github.o7py.margin.model.InkPoint
import io.github.o7py.margin.model.PageEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Crash-safety drafts (SPEC §9.3): the full edit state, keyed by document URI + size + mtime.
 * Original pages are stored by their index in the source file.
 */
class DraftStore(context: Context) {
    private val dir = File(context.filesDir, "drafts").apply { mkdirs() }

    data class Draft(val pages: List<DraftPage>, val annots: List<Annot>, val savedAt: Long)

    /** [sourceIndex] ≥ 0 for pages of the source file, -1 for inserted pages. [key] = page id in the draft. */
    data class DraftPage(val key: Long, val sourceIndex: Int, val width: Float, val height: Float, val color: Int, val annotsIncluded: Boolean)

    fun key(uri: String, size: Long, mtime: Long): String {
        val md = MessageDigest.getInstance("SHA-1").digest("$uri|$size|$mtime".toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    private fun uriPrefix(uri: String) = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    private fun file(uri: String, key: String) = File(dir, "${uriPrefix(uri)}_$key.json")

    fun hasDraftFor(uri: String) = dir.listFiles()?.any { it.name.startsWith(uriPrefix(uri) + "_") } == true

    fun write(uri: String, key: String, pages: List<PageEntry>, sourceIndex: (Long) -> Int, prepared: (Long) -> Boolean, annots: Map<Long, List<Annot>>) {
        val o = JSONObject()
        o.put("savedAt", System.currentTimeMillis())
        val live = pages.map { it.id }.toSet()
        o.put("pages", JSONArray().apply {
            pages.forEach { p ->
                put(JSONObject().apply {
                    put("key", p.id); put("src", if (p is PageEntry.Original) sourceIndex(p.id) else -1)
                    put("w", p.width.toDouble()); put("h", p.height.toDouble())
                    put("color", if (p is PageEntry.Inserted) p.color else 0)
                    put("full", p is PageEntry.Inserted || prepared(p.id))
                })
            }
        })
        o.put("annots", JSONArray().apply {
            annots.forEach { (pid, list) -> if (pid in live) list.forEach { put(encode(it)) } }
        })
        // one draft per URI: drop older keys
        dir.listFiles()?.filter { it.name.startsWith(uriPrefix(uri) + "_") }?.forEach { it.delete() }
        val f = file(uri, key)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
    }

    fun read(uri: String, key: String): Draft? = try {
        val f = file(uri, key)
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            val pa = o.getJSONArray("pages")
            val pages = (0 until pa.length()).map { i ->
                val p = pa.getJSONObject(i)
                DraftPage(p.getLong("key"), p.getInt("src"), p.getDouble("w").toFloat(), p.getDouble("h").toFloat(), p.getInt("color"), p.optBoolean("full", true))
            }
            val aa = o.getJSONArray("annots")
            Draft(pages, (0 until aa.length()).mapNotNull { decode(aa.getJSONObject(it)) }, o.getLong("savedAt"))
        }
    } catch (t: Throwable) {
        Log.w("Margin", "draft unreadable", t); null
    }

    fun delete(uri: String) {
        dir.listFiles()?.filter { it.name.startsWith(uriPrefix(uri) + "_") }?.forEach { it.delete() }
    }

    private fun pts(list: List<InkPoint>) = JSONArray().apply {
        list.forEach { put(it.x.toDouble()); put(it.y.toDouble()); put(it.pressure.toDouble()) }
    }

    private fun readPts(a: JSONArray) = (0 until a.length() / 3).map {
        InkPoint(a.getDouble(it * 3).toFloat(), a.getDouble(it * 3 + 1).toFloat(), a.getDouble(it * 3 + 2).toFloat())
    }

    private fun encode(a: Annot) = JSONObject().apply {
        put("id", a.id); put("page", a.pageId); put("color", a.color)
        when (a) {
            is Annot.Ink -> { put("t", "ink"); put("w", a.baseWidth.toDouble()); put("p", pts(a.points)) }
            is Annot.FreeHighlight -> { put("t", "fh"); put("w", a.width.toDouble()); put("o", a.opacity.toDouble()); put("p", pts(a.points)) }
            is Annot.TextHighlight -> {
                put("t", "th"); put("o", a.opacity.toDouble())
                put("q", JSONArray().apply { a.quads.forEach { b -> put(b.x0.toDouble()); put(b.y0.toDouble()); put(b.x1.toDouble()); put(b.y1.toDouble()) } })
            }
        }
    }

    private fun decode(o: JSONObject): Annot? {
        val id = o.getString("id"); val page = o.getLong("page"); val color = o.getInt("color")
        return when (o.getString("t")) {
            "ink" -> Annot.Ink(id, page, color, o.getDouble("w").toFloat(), readPts(o.getJSONArray("p")))
            "fh" -> Annot.FreeHighlight(id, page, color, o.getDouble("w").toFloat(), readPts(o.getJSONArray("p")), o.getDouble("o").toFloat())
            "th" -> {
                val q = o.getJSONArray("q")
                Annot.TextHighlight(id, page, color, (0 until q.length() / 4).map {
                    Box(q.getDouble(it * 4).toFloat(), q.getDouble(it * 4 + 1).toFloat(), q.getDouble(it * 4 + 2).toFloat(), q.getDouble(it * 4 + 3).toFloat())
                }, o.getDouble("o").toFloat())
            }
            else -> null
        }
    }
}
