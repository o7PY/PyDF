package io.github.o7py.margin.ui

import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.util.LruCache
import io.github.o7py.margin.canvas.PageSource
import io.github.o7py.margin.canvas.ReaderCanvas
import io.github.o7py.margin.data.AppContainer
import io.github.o7py.margin.data.RecentEntity
import io.github.o7py.margin.data.ToolPrefs
import io.github.o7py.margin.edit.Command
import io.github.o7py.margin.edit.EditState
import io.github.o7py.margin.edit.UndoStack
import io.github.o7py.margin.input.InputTuning
import io.github.o7py.margin.model.Annot
import io.github.o7py.margin.model.PageEntry
import io.github.o7py.margin.model.PageText
import io.github.o7py.margin.model.Palette
import io.github.o7py.margin.model.Tool
import io.github.o7py.margin.pdf.MuPdfEngine
import io.github.o7py.margin.pdf.PdfEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class ReaderUi(
    val uri: Uri? = null,
    val title: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val needsPassword: Boolean = false,
    val wrongPassword: Boolean = false,
    val dirty: Boolean = false,
    val tool: Tool = Tool.PEN,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val pages: List<PageEntry> = emptyList(),
    val currentPage: Int = 0,
    /** null while unknown (still scanning). */
    val hasText: Boolean? = null,
    val chromeVisible: Boolean = true,
    val draftAvailable: Boolean = false,
    val saving: Boolean = false,
    val pageIndicatorVisible: Boolean = false,
    /** Lock: orientation + zoom locked, one-finger scroll. */
    val locked: Boolean = false,
    val selection: ReaderCanvas.SelectionInfo? = null,
    val scrollbar: Boolean = false,
    val night: Boolean = false,
    val outline: List<io.github.o7py.margin.model.OutlineItem> = emptyList(),
)

sealed class ReaderEvent {
    data class Snack(val text: String, val undo: Boolean = false) : ReaderEvent()
    /** Original URI is read-only: ask the Activity to run "Save a copy" and then continue on the copy. */
    object NeedsSaveAs : ReaderEvent()
    object Closed : ReaderEvent()
}

/**
 * One open document session: engine + edit model + undo + drafts + saving (SPEC §8.5, §9).
 * Lives as long as the Activity (config changes are handled without recreation).
 */
class ReaderController(
    private val app: AppContainer,
    private val scope: CoroutineScope,
    val canvas: ReaderCanvas,
) : PageSource, ReaderCanvas.Listener {

    private val resolver: ContentResolver = app.appContext.contentResolver
    private val engine: PdfEngine = MuPdfEngine()
    private var state = EditState(emptyList())
    private val undo = UndoStack(200)
    /** Undo state id that matches the file on disk; -1 = the edit state differs from any saved state (e.g. restored draft). */
    private var savedStateId = 0L
    private val texts = HashMap<Long, PageText>()
    private val prepared = HashSet<Long>()
    /** Pages whose annotations came from a restored draft: imported annotations are discarded. */
    private val draftCovered = HashSet<Long>()
    private var workFile: File? = null
    private var draftKey: String = ""
    private var password: String? = null
    private var sweepJob: Job? = null
    private var draftJob: Job? = null
    private var indicatorJob: Job? = null
    private val thumbs = LruCache<Long, Bitmap>(80)

    private val _ui = MutableStateFlow(ReaderUi())
    val ui: StateFlow<ReaderUi> = _ui.asStateFlow()
    val events = MutableSharedFlow<ReaderEvent>(extraBufferCapacity = 8)
    val thumbVersion = MutableStateFlow(0)

    var tools = ToolPrefs(); private set

    init {
        canvas.source = this
        canvas.listener = this
    }

    // ------------------------------------------------------------------ settings

    fun applyTuning(t: InputTuning) { canvas.tuning = t }

    fun applyTools(t: ToolPrefs) {
        tools = t
        canvas.style = ReaderCanvas.ToolStyle(
            t.penColor, Palette.penWidths[t.penWidthIndex.coerceIn(0, 3)],
            t.highlighterColor, Palette.highlighterWidths[t.highlighterWidthIndex.coerceIn(0, 1)],
        )
    }

    fun updateTools(t: ToolPrefs) { applyTools(t); scope.launch { app.settings.saveTools(t) } }

    // ------------------------------------------------------------------ open / close

    fun open(uri: Uri, pw: String? = null) {
        scope.launch {
            if (pw == null) closeSession()
            _ui.value = ReaderUi(uri = uri, title = displayName(uri), loading = true, scrollbar = _ui.value.scrollbar, night = _ui.value.night)
            try {
                val (size, mtime) = sizeAndMtime(uri)
                val file = workFile?.takeIf { pw != null && it.exists() } ?: withContext(Dispatchers.IO) {
                    val f = File(app.workDir, "src-${System.nanoTime()}.pdf")
                    resolver.openInputStream(uri)!!.use { input -> FileOutputStream(f).use { input.copyTo(it) } }
                    f
                }
                workFile = file
                when (val r = engine.open(file, pw)) {
                    is PdfEngine.OpenResult.NeedsPassword -> _ui.update { it.copy(loading = false, needsPassword = true) }
                    is PdfEngine.OpenResult.WrongPassword -> _ui.update { it.copy(loading = false, needsPassword = true, wrongPassword = true) }
                    is PdfEngine.OpenResult.Failed -> _ui.update { it.copy(loading = false, error = friendly(r.message)) }
                    is PdfEngine.OpenResult.Ok -> onOpened(uri, r.pages, size, mtime, pw)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "open failed", t)
                _ui.update { it.copy(loading = false, error = friendly(t.message)) }
            }
        }
    }

    private fun friendly(msg: String?): String =
        "This PDF couldn't be opened. It may be damaged or not a PDF." + (msg?.let { "\n\n($it)" } ?: "")

    private suspend fun onOpened(uri: Uri, pages: List<PageEntry>, size: Long, mtime: Long, pw: String?) {
        password = pw
        state = EditState(pages)
        undo.clear(); savedStateId = 0L; texts.clear(); prepared.clear(); draftCovered.clear(); thumbs.evictAll()
        draftKey = app.drafts.key(uri.toString(), size, mtime)
        val recent = app.db.recents().get(uri.toString())
        val hasText = recent?.hasText?.takeIf { it }
        _ui.update {
            it.copy(loading = false, needsPassword = false, wrongPassword = false, pages = pages,
                tool = lastTool, hasText = hasText, dirty = false, draftAvailable = app.drafts.read(uri.toString(), draftKey) != null)
        }
        canvas.tool = lastTool
        canvas.clearRenderCache()
        canvas.setPages(pages)
        canvas.restorePosition(recent?.lastPage ?: 0, recent?.pageOffset ?: 0f, recent?.zoom ?: 1f)
        app.db.recents().upsert(
            (recent ?: RecentEntity(uri.toString(), displayName(uri), pages.size, 0L)).copy(
                displayName = displayName(uri), pageCount = pages.size, lastOpened = System.currentTimeMillis(),
            )
        )
        if (recent?.thumbnailPath == null || !File(recent.thumbnailPath).exists()) scope.launch { writeRecentThumb(uri) }
        scope.launch { val o = engine.outline(); _ui.update { it.copy(outline = o) } }
        startSweep(uri)
    }

    /** Background: import annotations of every page and find out whether the document has text. */
    private fun startSweep(uri: Uri) {
        sweepJob?.cancel()
        sweepJob = scope.launch {
            // let the first screen render first: the sweep shares the single MuPDF thread with rendering
            delay(800)
            var found = _ui.value.hasText == true
            for (p in state.pages.toList()) {
                kotlinx.coroutines.yield()
                prepare(p.id)
                if (!found && text(p.id).hasText) {
                    found = true
                    _ui.update { it.copy(hasText = true) }
                    app.db.recents().updateHasText(uri.toString(), true)
                }
            }
            if (!found) {
                _ui.update { it.copy(hasText = false) }
                app.db.recents().updateHasText(uri.toString(), false)
            }
        }
    }

    private suspend fun prepareAll() { for (p in state.pages.toList()) prepare(p.id) }

    fun close() {
        scope.launch {
            closeSession()
            events.emit(ReaderEvent.Closed)
        }
    }

    private suspend fun closeSession() {
        sweepJob?.cancel(); draftJob?.cancel()
        savePositionNow()
        canvas.source = null
        engine.close()
        canvas.source = this
        canvas.clearRenderCache()
        undo.clear()
        state = EditState(emptyList())
        canvas.setPages(emptyList())
        workFile?.delete(); workFile = null
        canvas.locked = false
        _ui.value = ReaderUi(scrollbar = _ui.value.scrollbar, night = _ui.value.night)
    }

    // ------------------------------------------------------------------ PageSource

    override suspend fun prepare(pageId: Long) {
        if (pageId in prepared) return
        val entry = state.pages.firstOrNull { it.id == pageId } ?: return
        val imported = engine.importAnnotations(entry)
        prepared.add(pageId)
        if (imported != null && pageId !in draftCovered) {
            imported.forEach { state.add(it) }
            if (imported.isNotEmpty()) canvas.refreshAnnotations()
        }
    }

    override suspend fun render(pageId: Long, bitmap: Bitmap, scale: Float, left: Int, top: Int) = engine.render(pageId, bitmap, scale, left, top)

    override suspend fun text(pageId: Long): PageText = texts[pageId] ?: engine.pageText(pageId).also { texts[pageId] = it }

    override fun cachedText(pageId: Long): PageText? = texts[pageId] ?: state.pages.firstOrNull { it.id == pageId }?.let {
        if (it is PageEntry.Inserted) PageText.EMPTY.also { e -> texts[pageId] = e } else null
    }

    override fun annotsOn(pageId: Long) = state.annotsOn(pageId)
    override fun pageVersion(pageId: Long) = state.pageVersion(pageId)

    override fun execute(c: Command) {
        undo.execute(c, state)
        afterChange(c)
    }

    private fun afterChange(c: Command?) {
        if (c?.changesLayout == true) {
            val pages = state.pages.toList()
            scope.launch {
                engine.applyLayout(pages)
                canvas.setPages(pages)
                _ui.update { it.copy(pages = pages) }
                thumbVersion.value++
            }
        } else canvas.refreshAnnotations()
        if (c is Command.AddAnnot || c is Command.EraseItems) thumbs.remove(
            when (c) { is Command.AddAnnot -> c.annot.pageId; is Command.EraseItems -> c.items.first().pageId; else -> -1 }
        )
        val dirty = undo.stateId != savedStateId
        _ui.update { it.copy(dirty = dirty, canUndo = undo.canUndo, canRedo = undo.canRedo) }
        if (dirty) scheduleDraft() else { draftJob?.cancel(); _ui.value.uri?.let { u -> app.drafts.delete(u.toString()) } }
    }

    fun undo() { undo.undo(state)?.let { afterChange(it) } }
    fun redo() { undo.redo(state)?.let { afterChange(it) } }

    // ------------------------------------------------------------------ modes & tools

    /** The tool survives between documents (like Samsung Notes). */
    private var lastTool = Tool.PEN

    fun setTool(t: Tool) { lastTool = t; canvas.tool = t; _ui.update { it.copy(tool = t) } }

    // ------------------------------------------------------------------ pages

    fun insertPage(at: Int, relativeTo: Int, color: Int) {
        val ref = state.pages.getOrNull(relativeTo.coerceIn(0, state.pages.size - 1)) ?: return
        val entry = PageEntry.Inserted(state.newPageId(), ref.width, ref.height, color)
        texts[entry.id] = PageText.EMPTY
        prepared.add(entry.id)
        execute(Command.InsertPage(at.coerceIn(0, state.pages.size), entry))
        updateTools(tools.copy(pageBackground = color))
        events.tryEmit(ReaderEvent.Snack("Page inserted", undo = true))
    }

    fun deletePage(index: Int) {
        if (state.pages.size <= 1) { events.tryEmit(ReaderEvent.Snack("A document needs at least one page")); return }
        val entry = state.pages.getOrNull(index) ?: return
        execute(Command.DeletePage(index, entry))
        events.tryEmit(ReaderEvent.Snack("Page deleted", undo = true))
    }

    fun goToPage(i: Int) = canvas.scrollToPage(i)

    suspend fun thumbnail(page: PageEntry): Bitmap? {
        thumbs.get(page.id)?.let { return it }
        val b = canvas.renderThumb(page.id, page, 180) ?: return null
        // bake the vector annotations into the thumbnail
        val c = android.graphics.Canvas(b)
        val s = 180f / page.width
        c.scale(s, s)
        state.annotsOn(page.id).forEach { io.github.o7py.margin.canvas.AnnotPainter.draw(c, it) }
        thumbs.put(page.id, b)
        return b
    }

    // ------------------------------------------------------------------ saving (SPEC §9.2)

    fun save(onDone: (Boolean) -> Unit = {}) {
        val uri = _ui.value.uri ?: return
        if (_ui.value.saving) return
        scope.launch {
            var success = false
            _ui.update { it.copy(saving = true) }
            try {
                val tmp = writeTemp()
                val ok = withContext(Dispatchers.IO) {
                    try {
                        resolver.openOutputStream(uri, "wt")!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                        true
                    } catch (t: Throwable) {
                        Log.w(TAG, "write to original failed (read-only?)", t); false
                    }
                }
                if (!ok) { tmp.delete(); events.emit(ReaderEvent.NeedsSaveAs); return@launch }
                afterSaved(uri, tmp)
                success = true
                events.emit(ReaderEvent.Snack("Saved"))
            } catch (t: Throwable) {
                Log.e(TAG, "save failed", t)
                events.emit(ReaderEvent.Snack("Couldn't save: ${t.message ?: "unknown error"}"))
            } finally {
                _ui.update { it.copy(saving = false) }
                onDone(success)
            }
        }
    }

    /** Save a copy to [dest]. If [switchTo], editing continues on the copy (read-only original fallback). */
    fun saveCopy(dest: Uri, switchTo: Boolean) {
        scope.launch {
            _ui.update { it.copy(saving = true) }
            try {
                val tmp = writeTemp()
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(dest, "wt")!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                }
                takePermission(dest)
                if (switchTo) {
                    val old = _ui.value.uri
                    _ui.update { it.copy(uri = dest, title = displayName(dest)) }
                    afterSaved(dest, tmp)
                    old?.let { app.drafts.delete(it.toString()) }
                    app.db.recents().upsert(RecentEntity(dest.toString(), displayName(dest), state.pages.size, System.currentTimeMillis(),
                        hasText = _ui.value.hasText == true))
                    scope.launch { writeRecentThumb(dest) }
                } else tmp.delete()
                events.emit(ReaderEvent.Snack("Saved a copy"))
            } catch (t: Throwable) {
                Log.e(TAG, "save copy failed", t)
                events.emit(ReaderEvent.Snack("Couldn't save a copy: ${t.message ?: "unknown error"}"))
            } finally {
                _ui.update { it.copy(saving = false) }
            }
        }
    }

    private suspend fun writeTemp(): File {
        sweepJob?.join()
        prepareAll()
        val tmp = File(app.workDir, "save-${System.nanoTime()}.pdf")
        engine.save(state.pages.toList(), state.allAnnots().mapValues { it.value.toList() }, tmp)
        return tmp
    }

    private suspend fun afterSaved(uri: Uri, tmp: File) {
        // the saved file becomes the new baseline for future saves
        engine.rebase(tmp, state.pages.toList())
        workFile?.takeIf { it != tmp }?.delete()
        workFile = tmp
        app.drafts.delete(uri.toString())
        val (size, mtime) = sizeAndMtime(uri)
        draftKey = app.drafts.key(uri.toString(), size, mtime)
        draftJob?.cancel()
        savedStateId = undo.stateId
        _ui.update { it.copy(dirty = false, draftAvailable = false) }
        app.db.recents().get(uri.toString())?.let { app.db.recents().upsert(it.copy(pageCount = state.pages.size)) }
        scope.launch { writeRecentThumb(uri) }
    }

    // ------------------------------------------------------------------ drafts (SPEC §9.3)

    private fun scheduleDraft() {
        draftJob?.cancel()
        draftJob = scope.launch { delay(10_000); writeDraftNow() }
    }

    fun writeDraftNow() {
        if (!_ui.value.dirty) return
        val uri = _ui.value.uri ?: return
        val pages = state.pages.toList()
        val annots = state.allAnnots().mapValues { it.value.toList() }
        val prep = prepared.toSet()
        val key = draftKey
        val srcIdx = pages.associate { it.id to engine.sourceIndexOf(it.id) }
        scope.launch(Dispatchers.IO + NonCancellable) {
            try {
                app.drafts.write(uri.toString(), key, pages, { srcIdx[it] ?: -1 }, { it in prep }, annots)
            } catch (t: Throwable) { Log.w(TAG, "draft write failed", t) }
        }
    }

    fun discardDraft() {
        _ui.value.uri?.let { app.drafts.delete(it.toString()) }
        _ui.update { it.copy(draftAvailable = false) }
    }

    fun restoreDraft() {
        val uri = _ui.value.uri ?: return
        scope.launch {
            val d = app.drafts.read(uri.toString(), draftKey)
            if (d == null) { _ui.update { it.copy(draftAvailable = false) }; return@launch }
            sweepJob?.join()
            prepareAll()
            val originals = state.pages.filterIsInstance<PageEntry.Original>().associateBy { it.sourceIndex }
            val keyToId = HashMap<Long, Long>()
            val newPages = ArrayList<PageEntry>()
            var nextId = (state.pages.maxOfOrNull { it.id } ?: 0L) + 1
            for (dp in d.pages) {
                val e: PageEntry = if (dp.sourceIndex >= 0) {
                    originals[dp.sourceIndex] ?: continue
                } else PageEntry.Inserted(nextId++, dp.width, dp.height, dp.color).also { texts[it.id] = PageText.EMPTY; prepared.add(it.id) }
                keyToId[dp.key] = e.id
                newPages.add(e)
            }
            if (newPages.isEmpty()) { discardDraft(); return@launch }
            val newState = EditState(newPages)
            newState.nextPageId = nextId
            // pages whose annotations are in the draft take them from the draft; others keep imported ones
            val covered = d.pages.filter { it.annotsIncluded }.mapNotNull { keyToId[it.key] }.toSet()
            for (p in newPages) if (p.id !in covered) state.annotsOn(p.id).forEach { newState.add(it) }
            for (a in d.annots) {
                val pid = keyToId[a.pageId] ?: continue
                if (pid !in covered) continue
                newState.add(when (a) {
                    is Annot.Ink -> a.copy(pageId = pid)
                    is Annot.FreeHighlight -> a.copy(pageId = pid)
                    is Annot.TextHighlight -> a.copy(pageId = pid)
                })
            }
            draftCovered.addAll(covered)
            state = newState
            undo.clear()
            savedStateId = -1L
            engine.applyLayout(newPages)
            canvas.setPages(newPages)
            canvas.refreshAnnotations()
            thumbs.evictAll(); thumbVersion.value++
            _ui.update { it.copy(pages = newPages, dirty = true, canUndo = false, canRedo = false, draftAvailable = false) }
        }
    }

    // ------------------------------------------------------------------ canvas listener

    /** Uptime of the last viewport change; one coroutine handles indicator fade + position save (no per-frame jobs). */
    private var lastViewportAt = 0L

    override fun onViewport(currentPage: Int, position: Pair<Int, Float>, zoom: Float) {
        lastViewportAt = android.os.SystemClock.uptimeMillis()
        val cur = _ui.value
        if (cur.currentPage != currentPage || !cur.pageIndicatorVisible) {
            _ui.update { it.copy(currentPage = currentPage, pageIndicatorVisible = true) }
        }
        if (indicatorJob?.isActive != true) {
            indicatorJob = scope.launch {
                var savedPosition = false
                while (true) {
                    val idle = android.os.SystemClock.uptimeMillis() - lastViewportAt
                    if (idle >= 500 && !savedPosition) { savePositionNow(); savedPosition = true }
                    if (idle >= 1500) break
                    if (idle < 500) savedPosition = false
                    delay(if (idle < 500) 500 - idle else 1500 - idle)
                }
                _ui.update { it.copy(pageIndicatorVisible = false) }
            }
        }
    }

    private suspend fun savePositionNow() {
        val uri = _ui.value.uri ?: return
        if (state.pages.isEmpty()) return
        val (p, f) = canvas.layout.position()
        app.db.recents().updatePosition(uri.toString(), p, f, canvas.layout.zoom)
    }

    override fun onNavigationGesture(startY: Float, totalDy: Float) {
        // toolbar stays visible (Samsung Notes style)
    }

    fun showChrome() = _ui.update { it.copy(chromeVisible = true) }

    override fun onPenDown() {}
    override fun onEdit() {}
    override fun onSelection(info: ReaderCanvas.SelectionInfo?) { _ui.update { it.copy(selection = info) } }

    // ------------------------------------------------------------------ lock & selection actions

    fun toggleLock() {
        val v = !_ui.value.locked
        canvas.locked = v
        _ui.update { it.copy(locked = v) }
        events.tryEmit(ReaderEvent.Snack(if (v) "Zoom and rotation locked" else "Unlocked"))
    }

    fun applyNight(v: Boolean) { canvas.night = v; _ui.update { it.copy(night = v) } }
    fun toggleNight() { val v = !_ui.value.night; applyNight(v); scope.launch { app.settings.setNight(v) } }

    fun applyScrollbar(v: Boolean) { canvas.scrollbarEnabled = v; _ui.update { it.copy(scrollbar = v) } }
    fun toggleScrollbar() { val v = !_ui.value.scrollbar; applyScrollbar(v); scope.launch { app.settings.setScrollbar(v) } }

    fun goToOutline(item: io.github.o7py.margin.model.OutlineItem) {
        val i = state.pageIndex(item.pageId)
        if (i >= 0) canvas.scrollToPagePoint(i, item.y)
    }

    fun copySelection() {
        val t = canvas.selectionText() ?: return
        val cm = app.appContext.getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Text from PDF", t))
        canvas.clearSelection()
        // Android 13 shows its own clipboard confirmation
    }

    fun highlightSelection(color: Int) {
        canvas.highlightSelection(color)
        updateTools(tools.copy(highlighterColor = color))
    }

    fun selectAll() = canvas.selectAllOnPage()
    fun removeSelectionHighlights() = canvas.removeHighlightsInSelection()
    fun clearSelection() = canvas.clearSelection()

    // ------------------------------------------------------------------ helpers

    private suspend fun writeRecentThumb(uri: Uri) {
        val first = state.pages.firstOrNull() ?: return
        val b = canvas.renderThumb(first.id, first, 300) ?: return
        val c = android.graphics.Canvas(b)
        val s = 300f / first.width
        c.scale(s, s)
        state.annotsOn(first.id).forEach { io.github.o7py.margin.canvas.AnnotPainter.draw(c, it) }
        val f = File(app.thumbsDir, sha1(uri.toString()) + ".png")
        withContext(Dispatchers.IO) { FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 90, it) } }
        app.db.recents().get(uri.toString())?.let { app.db.recents().upsert(it.copy(thumbnailPath = f.absolutePath)) }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun displayName(uri: Uri): String = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "Document"
    } catch (t: Throwable) { uri.lastPathSegment ?: "Document" }

    private fun sizeAndMtime(uri: Uri): Pair<Long, Long> = try {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val size = if (c.isNull(0)) -1L else c.getLong(0)
                val mt = if (c.columnCount > 1 && !c.isNull(1)) c.getLong(1) else -1L
                size to mt
            } else -1L to -1L
        } ?: (-1L to -1L)
    } catch (t: Throwable) { -1L to -1L }

    fun takePermission(uri: Uri) {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try { resolver.takePersistableUriPermission(uri, rw) } catch (_: Throwable) {
            try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
        }
    }

    suspend fun awaitFirstTools() { applyTools(app.settings.tools.first()) }

    companion object { const val TAG = "Margin" }
}
