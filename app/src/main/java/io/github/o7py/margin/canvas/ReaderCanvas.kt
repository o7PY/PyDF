package io.github.o7py.margin.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.input.motionprediction.MotionEventPredictor
import io.github.o7py.margin.edit.Command
import io.github.o7py.margin.edit.HitTest
import io.github.o7py.margin.edit.TextSnapper
import io.github.o7py.margin.input.GestureNavigator
import io.github.o7py.margin.input.InputTuning
import io.github.o7py.margin.input.ModeController
import io.github.o7py.margin.input.PalmFilter
import io.github.o7py.margin.input.PointerKind
import io.github.o7py.margin.input.StrokeClassifier
import io.github.o7py.margin.model.Annot
import io.github.o7py.margin.model.Box
import io.github.o7py.margin.model.Geometry
import io.github.o7py.margin.model.InkPoint
import io.github.o7py.margin.model.PageEntry
import io.github.o7py.margin.model.PageText
import io.github.o7py.margin.model.StrokeAction
import io.github.o7py.margin.model.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The page canvas: a plain View hierarchy (not inside Compose's pointer system) so Samsung's custom S Pen
 * actions (211–214) arrive untouched. Applies palm rejection (SPEC §6) and routes pen input to tools (SPEC §7–8).
 */
@SuppressLint("ViewConstructor")
class ReaderCanvas(context: Context) : FrameLayout(context), GestureNavigator.StylusState {

    interface Listener {
        fun onViewport(currentPage: Int, position: Pair<Int, Float>, zoom: Float)
        fun onNavigationGesture(startY: Float, totalDy: Float)
        fun onPenDown()
        fun onEdit()
        fun onSelection(info: SelectionInfo?)
    }

    /** Selection bounds in screen pixels plus what the menu needs. */
    data class SelectionInfo(val left: Float, val top: Float, val right: Float, val bottom: Float, val overlapsHighlight: Boolean)

    data class ToolStyle(val penColor: Int, val penWidth: Float, val hlColor: Int, val hlWidth: Float)

    private val density = resources.displayMetrics.density
    val layout = PageLayout(density)
    private val cache = RenderCache((Runtime.getRuntime().maxMemory() / 4).toInt().coerceAtMost(256 * 1024 * 1024))
    private val pagesView = PagesView(context, layout, cache)
    private val overlay = InkOverlayView(context, layout)

    var source: PageSource? = null
        set(v) { field = v; pagesView.source = v; requestRender() }
    var listener: Listener? = null
    var tuning = InputTuning()
        set(v) { field = v; nav.updateTuning(v); overlay.debug = v.debugOverlays; overlay.invalidate() }
    var tool = Tool.PEN
    /** Lock: pinch-zoom is off (orientation is locked by the Activity). One finger always scrolls. */
    var locked = false
        set(v) { field = v; nav.zoomEnabled = !v }

    /** Night mode (smart invert of pages). */
    var night = false
        set(v) { field = v; pagesView.night = v; overlay.night = v; overlay.invalidate() }

    /** Draggable scroll bar on the right edge. */
    var scrollbarEnabled = false
        set(v) { field = v; overlay.scrollbar = v; overlay.invalidate() }
    var style = ToolStyle(0xFF1A1A1A.toInt(), 1.2f, 0xFFFFEB3B.toInt(), 6f)

    // ---- stylus state (also fed from the Activity for hover) ----
    override var isTouching = false; private set
    private var hoverSeenAt = 0L
    override val isHovering get() = SystemClock.uptimeMillis() - hoverSeenAt < HOVER_STALE_MS && hoverActive
    private var hoverActive = false
    override var lastLiftTime = -100_000L; private set

    private val nav = GestureNavigator(tuning, this, NavListener(), density).apply { oneFingerPan = true }
    private val scroller = OverScroller(context)
    private var predictor: MotionEventPredictor? = null
    private var scope: CoroutineScope? = null
    private val renderSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        addView(pagesView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        overlay.animationsEnabled = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        isMotionEventSplittingEnabled = false
    }

    // ------------------------------------------------------------------ pages / viewport

    fun setPages(pages: List<PageEntry>) {
        if (selection != null) clearSelection()
        layout.setPages(pages)
        pagesView.dropLayers()
        onViewportChanged(settleNow = true)
    }

    fun restorePosition(page: Int, fraction: Float, zoom: Float) {
        layout.setZoom(zoom)
        layout.restore(page, fraction)
        onViewportChanged(settleNow = true)
    }

    /** Scrolls so that page [i] at page-space y [py] is near the top (outline jumps). */
    fun scrollToPagePoint(i: Int, py: Float) {
        if (i !in layout.pages.indices) return
        scroller.forceFinished(true)
        val f = (py / layout.pages[i].height).coerceIn(0f, 1f)
        layout.restore(i, f)
        layout.panBy(0f, layout.gap * 2 + layout.topInset)
        onViewportChanged()
    }

    fun scrollToPage(i: Int) {
        if (i !in layout.pages.indices) return
        scroller.forceFinished(true)
        layout.restore(i, 0f)
        layout.panBy(0f, layout.gap) // show the gap above the page
        onViewportChanged()
    }

    fun refreshAnnotations() { pagesView.invalidate() }

    /** Width taken by the page panel on the left: the canvas shrinks so pages fit beside it. */
    fun setLeftInset(px: Int) {
        val lp = layoutParams as? MarginLayoutParams ?: return
        if (lp.leftMargin == px) return
        lp.leftMargin = px
        layoutParams = lp
    }

    /** Height of the toolbar overlapping the canvas: the first page starts below it. */
    fun setTopInset(px: Float) {
        if (px == layout.topInset) return
        layout.topInset = px
        layout.setPages(layout.pages)
        onViewportChanged(settleNow = true)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layout.setViewSize(w, h)
        pagesView.lowResScale = (layout.baseScale * 0.5f).coerceAtMost(1.2f)
        onViewportChanged(settleNow = true)
    }

    private val settle = Runnable {
        if (pagesView.tileScale != layout.scale) {
            pagesView.prevTileScale = pagesView.tileScale
            pagesView.tileScale = layout.scale
        }
        requestRender()
        pagesView.invalidate()
    }

    private fun onViewportChanged(settleNow: Boolean = false) {
        pagesView.invalidate(); overlay.invalidate()
        if (selection != null) publishSelection()
        removeCallbacks(settle)
        if (settleNow) post(settle) else postDelayed(settle, 150)
        requestRender()
        listener?.onViewport(layout.currentPage(), layout.position(), layout.zoom)
    }

    // ------------------------------------------------------------------ render loop

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        predictor = MotionEventPredictor.newInstance(this)
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = sc
        sc.launch {
            while (isActive) {
                val job = nextRenderJob()
                if (job == null) { renderSignal.receive(); continue }
                try { job() } catch (t: Throwable) { Log.w("Margin", "render job failed", t) }
            }
        }
    }

    override fun onDetachedFromWindow() {
        scope?.cancel(); scope = null
        super.onDetachedFromWindow()
    }

    fun requestRender() { renderSignal.trySend(Unit) }

    fun clearRenderCache() { cache.clear(); pagesView.dropLayers(); requestRender() }

    private val failed = HashSet<RenderCache.Key>()

    /** Picks the most important missing piece of work, or null when the viewport is complete. */
    private fun nextRenderJob(): (suspend () -> Unit)? {
        val src = source ?: return null
        val pages = layout.pages
        if (pages.isEmpty() || width == 0) return null
        val visible = layout.visibleRange()
        // 1. low-res of visible pages
        for (i in visible) lowResJob(src, pages[i])?.let { return it }
        // 2. full-res tiles of visible pages, once zoom/scroll settled
        val ts = pagesView.tileScale
        if (ts > 0f && ts == layout.scale) {
            for (i in visible) tileJob(src, i, ts)?.let { return it }
        }
        // 3. text of visible pages (for highlighter / Smart Reading decisions)
        for (i in visible) {
            val id = pages[i].id
            if (src.cachedText(id) == null) return { src.text(id); overlay.invalidate() }
        }
        // 4. neighbours
        for (i in listOf(visible.first - 1, visible.last + 1)) if (i in pages.indices) lowResJob(src, pages[i])?.let { return it }
        return null
    }

    private fun lowResJob(src: PageSource, p: PageEntry): (suspend () -> Unit)? {
        val key = RenderCache.Key(p.id, RenderCache.LOW_RES, 0, 0, 0)
        if (cache.contains(key) || key in failed) return null
        return {
            src.prepare(p.id)
            pagesView.invalidate() // imported annotations
            val b = pagesView.lowResBitmap(p.width, p.height)
            if (src.render(p.id, b, pagesView.lowResScale, 0, 0)) cache.put(key, b) else failed.add(key)
            pagesView.invalidate()
        }
    }

    private fun tileJob(src: PageSource, i: Int, ts: Float): (suspend () -> Unit)? {
        val p = layout.pages[i]
        val left = layout.toScreenX(i, 0f); val top = layout.toScreenY(i, 0f)
        val k = layout.scale / ts
        val pw = p.width * ts; val ph = p.height * ts
        val T = RenderCache.TILE
        val tx0 = ((-left / k) / T).toInt().coerceAtLeast(0)
        val ty0 = ((-top / k) / T).toInt().coerceAtLeast(0)
        val tx1 = (((width - left) / k) / T).toInt().coerceAtMost(((pw - 1) / T).toInt())
        val ty1 = (((height - top) / k) / T).toInt().coerceAtMost(((ph - 1) / T).toInt())
        val bits = ts.toRawBits()
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val key = RenderCache.Key(p.id, bits, tx, ty, 0)
            if (cache.contains(key) || key in failed) continue
            return {
                src.prepare(p.id)
                val b = cache.obtainTile()
                if (pagesView.tileScale == ts && src.render(p.id, b, ts, tx * T, ty * T)) {
                    cache.put(key, b)
                    if (pagesView.prevTileScale != 0f && visibleTilesComplete(ts)) pagesView.prevTileScale = 0f
                } else cache.recycleToPool(b)
                pagesView.invalidate()
            }
        }
        return null
    }

    private fun visibleTilesComplete(ts: Float): Boolean {
        val src = source ?: return true
        for (i in layout.visibleRange()) if (tileJob(src, i, ts) != null) return false
        return true
    }

    // ------------------------------------------------------------------ hover (fed by the Activity too)

    /** Called for every hover/generic motion event in the window (Activity.dispatchGenericMotionEvent). */
    fun observeHover(e: MotionEvent) {
        if (PalmFilter.kind(e.getToolType(0)) == PointerKind.FINGER) return
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> { hoverActive = true; hoverSeenAt = SystemClock.uptimeMillis() }
            MotionEvent.ACTION_HOVER_EXIT -> hoverActive = false
        }
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean { observeHover(event); return true }

    // ------------------------------------------------------------------ touch routing

    private var penPointerId = -1
    private var stroke: Stroke? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(raw: MotionEvent): Boolean {
        if (source == null || layout.pages.isEmpty()) return true
        val samsung = PalmFilter.isSamsungButtonAction(raw.actionMasked)
        val e = if (samsung) MotionEvent.obtain(raw).also { it.action = PalmFilter.normalizeAction(raw.actionMasked) } else raw
        try { route(e, samsung) } finally { if (samsung) e.recycle() }
        return true
    }

    private fun route(e: MotionEvent, samsungButton: Boolean) {
        val now = e.eventTime
        val am = e.actionMasked
        val idx = e.actionIndex
        when (am) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val kind = PalmFilter.kind(e.getToolType(idx))
                if (PalmFilter.isPen(kind)) {
                    if (penPointerId == -1) {
                        penPointerId = e.getPointerId(idx)
                        isTouching = true; hoverActive = false
                        nav.cancel(); scroller.forceFinished(true)
                        requestUnbufferedDispatch(e)
                        val button = samsungButton || PalmFilter.buttonHeld(am, e.buttonState, e.getToolType(idx))
                        penDown(e, idx, button)
                    }
                } else if (!isTouching) {
                    scroller.forceFinished(true)
                    val fx = e.getX(idx); val fy = e.getY(idx)
                    if (am == MotionEvent.ACTION_DOWN && scrollbarEnabled && overlay.scrollbarHit(fx, fy)) {
                        barFingerId = e.getPointerId(idx)
                        barGrabOffset = fy - overlay.thumbCenterY()
                        overlay.thumbDragging = true; overlay.invalidate()
                    } else if (am == MotionEvent.ACTION_DOWN && selection != null && startHandleDrag(fx, fy)) {
                        selFingerId = e.getPointerId(idx)
                    } else {
                        if (e.pointerCount >= 2) lastMidY = (0 until e.pointerCount).map { e.getY(it) }.average().toFloat()
                        nav.fingerDown(finger(e, idx), now)
                        if (am == MotionEvent.ACTION_DOWN) scheduleLongPress(e.getPointerId(idx), fx, fy, now)
                        else { tapMoved = true; cancelSelectLongPress() }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val pi = if (penPointerId != -1) e.findPointerIndex(penPointerId) else -1
                if (pi >= 0) penMove(e, pi)
                if (barFingerId != -1) {
                    val bi = e.findPointerIndex(barFingerId)
                    if (bi >= 0) {
                        layout.scrollTo(layout.scrollX, overlay.scrollForThumbY(e.getY(bi) - barGrabOffset))
                        onViewportChanged()
                    }
                    return
                }
                if (selFingerId != -1) {
                    val si = e.findPointerIndex(selFingerId)
                    if (si >= 0) dragSelection(e.getX(si), e.getY(si))
                    return
                }
                if (lpPointer != -1) {
                    val li = e.findPointerIndex(lpPointer)
                    if (li < 0 || Geometry.dist(e.getX(li), e.getY(li), lpX, lpY) > 10f * density) { tapMoved = true; cancelSelectLongPress() }
                }
                val fingers = (0 until e.pointerCount).filter { PalmFilter.kind(e.getToolType(it)) == PointerKind.FINGER }.map { finger(e, it) }
                if (fingers.isNotEmpty()) {
                    lastMidY = fingers.map { it.y }.average().toFloat()
                    nav.fingersMoved(fingers, now)
                }
                if (tuning.debugOverlays) {
                    overlay.debugTouches = (0 until e.pointerCount).map {
                        Triple(e.getX(it), e.getY(it), maxOf(e.getTouchMajor(it) / 2f, 6f * density))
                    }
                    overlay.invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(idx)
                if (id == penPointerId) {
                    penMove(e, idx)
                    penUp()
                } else if (id == barFingerId) {
                    barFingerId = -1
                    overlay.thumbDragging = false; overlay.invalidate()
                } else if (id == selFingerId) {
                    selFingerId = -1
                    publishSelection()
                } else if (PalmFilter.kind(e.getToolType(idx)) == PointerKind.FINGER) {
                    val wasTap = id == lpPointer && !tapMoved && !nav.isActive && now - lpDownTime < LONG_PRESS_MS
                    nav.fingerUp(id, now)
                    if (wasTap && selection != null) clearSelection()
                    if (id == lpPointer) lpPointer = -1
                }
                cancelSelectLongPress()
                if (am == MotionEvent.ACTION_UP) { nav.cancel(); overlay.debugTouches = emptyList() }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (penPointerId != -1) penCancel()
                cancelSelectLongPress(); selFingerId = -1
                if (barFingerId != -1) { barFingerId = -1; overlay.thumbDragging = false; overlay.invalidate() }
                nav.cancel()
            }
        }
    }

    private fun finger(e: MotionEvent, i: Int) = GestureNavigator.Finger(e.getPointerId(i), e.getX(i), e.getY(i), e.getTouchMajor(i) / density)

    // ------------------------------------------------------------------ navigation

    private inner class NavListener : GestureNavigator.Listener {
        private var startY = 0f
        private var totalDy = 0f
        override fun onGestureStart() { startY = lastMidY; totalDy = 0f }
        override fun onPan(dx: Float, dy: Float) {
            totalDy += dy
            layout.panBy(dx, dy)
            onViewportChanged()
            listener?.onNavigationGesture(startY, totalDy)
        }
        override fun onZoom(factor: Float, focusX: Float, focusY: Float) {
            layout.zoomBy(factor, focusX, focusY)
            onViewportChanged()
        }
        override fun onGestureEnd(velocityX: Float, velocityY: Float) {
            if (velocityX * velocityX + velocityY * velocityY > 250f * 250f * density * density) {
                scroller.fling(layout.scrollX.toInt(), layout.scrollY.toInt(), -velocityX.toInt(), -velocityY.toInt(),
                    0, layout.maxScrollX().toInt(), 0, layout.maxScrollY().toInt())
                postOnAnimation(flingStep)
            }
        }
    }
    private var lastMidY = 0f

    private val flingStep = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                layout.scrollTo(scroller.currX.toFloat(), scroller.currY.toFloat())
                onViewportChanged()
                postOnAnimation(this)
            }
        }
    }

    // ------------------------------------------------------------------ pen strokes

    private enum class Kind { INK, FREE_HL, TEXT_HL, ERASE, SMART_PENDING, HL_PENDING }

    private inner class Stroke(val pageIndex: Int, val pageId: Long, var kind: Kind, val action: StrokeAction) {
        val points = ArrayList<InkPoint>()
        var smartLine: Int = -1
        val erased = LinkedHashMap<String, Annot>()
        var decision = "?"
        /** Incremental char-range snapping for live text highlights. */
        var snap: TextSnapper.Accumulator? = null
        fun snapper(text: PageText, expand: Float) = snap ?: TextSnapper.Accumulator(text, expand).also { snap = it }
    }

    private fun pageText(pageId: Long): PageText? = source?.cachedText(pageId)

    private fun penDown(e: MotionEvent, i: Int, button: Boolean) {
        listener?.onPenDown()
        cancelSelectLongPress()
        if (selection != null) clearSelection()
        val x = e.getX(i); val y = e.getY(i)
        var pi = layout.pageAt(x, y)
        if (pi < 0) {
            // pen-down between pages: only erasing may start there; drawing needs a page
            pi = layout.indexAtContentY(y + layout.scrollY)
        }
        val page = layout.pages[pi]
        val text = pageText(page.id)
        val action = ModeController.actionFor(tool, button, text?.hasText ?: true)
        val onPage = layout.pageAt(x, y) >= 0
        val px = layout.toPageX(pi, x); val py = layout.toPageY(pi, y)
        val expand = tuning.lineExpandFraction
        val kind = when (action) {
            StrokeAction.ERASE -> Kind.ERASE
            StrokeAction.INK -> Kind.INK
            StrokeAction.HIGHLIGHT -> when {
                text == null -> Kind.HL_PENDING
                text.hasText && StrokeClassifier.lineAt(text, px, py, expand) != null -> Kind.TEXT_HL
                else -> Kind.FREE_HL
            }
            StrokeAction.SMART -> when {
                text == null -> Kind.INK
                StrokeClassifier.lineAt(text, px, py, expand) == null -> Kind.INK // step 1: not on text → ink, final
                else -> Kind.SMART_PENDING
            }
        }
        if (!onPage && kind != Kind.ERASE) { stroke = null; return }
        val s = Stroke(pi, page.id, kind, action)
        if (kind == Kind.SMART_PENDING) s.smartLine = StrokeClassifier.lineAt(text!!, px, py, expand) ?: -1
        stroke = s
        removeCallbacks(dwellCheck)
        if (kind == Kind.SMART_PENDING && tuning.dwellHighlightMs > 0) postDelayed(dwellCheck, tuning.dwellHighlightMs)
        if (tuning.debugOverlays) {
            overlay.debugBands = layout.visibleRange().flatMap { vi ->
                pageText(layout.pages[vi].id)?.lines?.map { l -> vi to l.bbox.expand(0f, l.height * expand) } ?: emptyList()
            }
        }
        predictor?.record(e)
        addPoints(s, e, i)
        updateOverlay(s)
    }

    private fun penMove(e: MotionEvent, i: Int) {
        val s = stroke ?: return
        predictor?.record(e)
        addPoints(s, e, i)
        when (s.kind) {
            Kind.SMART_PENDING -> smartDecide(s, finished = false)
            Kind.HL_PENDING -> pageText(s.pageId)?.let { t ->
                val p0 = s.points.first()
                s.kind = if (t.hasText && StrokeClassifier.lineAt(t, p0.x, p0.y, tuning.lineExpandFraction) != null) Kind.TEXT_HL else Kind.FREE_HL
            }
            Kind.ERASE -> eraseAlong(s)
            else -> {}
        }
        overlay.predicted = if (s.kind == Kind.INK || s.kind == Kind.SMART_PENDING || s.kind == Kind.FREE_HL) predictedPoints(s) else emptyList()
        updateOverlay(s)
    }

    private fun addPoints(s: Stroke, e: MotionEvent, i: Int) {
        for (h in 0 until e.historySize) {
            s.points.add(InkPoint(layout.toPageX(s.pageIndex, e.getHistoricalX(i, h)), layout.toPageY(s.pageIndex, e.getHistoricalY(i, h)),
                e.getHistoricalPressure(i, h), e.getHistoricalEventTime(h)))
        }
        s.points.add(InkPoint(layout.toPageX(s.pageIndex, e.getX(i)), layout.toPageY(s.pageIndex, e.getY(i)), e.getPressure(i), e.eventTime))
    }

    private fun predictedPoints(s: Stroke): List<InkPoint> {
        val p = predictor?.predict() ?: return emptyList()
        try {
            val idx = p.findPointerIndex(penPointerId).takeIf { it >= 0 } ?: 0
            val last = s.points.lastOrNull()?.pressure ?: 0.5f
            return listOf(InkPoint(layout.toPageX(s.pageIndex, p.getX(idx)), layout.toPageY(s.pageIndex, p.getY(idx)), last, p.eventTime))
        } finally { p.recycle() }
    }

    private fun ptPerDp() = density / layout.scale

    /** Pen pressed and held still on text → this stroke is a highlight (explicit intent). */
    private val dwellCheck = Runnable {
        val s = stroke ?: return@Runnable
        if (s.kind != Kind.SMART_PENDING) return@Runnable
        val r = tuning.dwellRadiusDp * ptPerDp()
        if (StrokeClassifier.maxDisplacement(s.points) <= r) {
            convertToHighlight(s, "HOLD")
            performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            updateOverlay(s)
        }
    }

    private fun convertToHighlight(s: Stroke, why: String) {
        s.kind = Kind.TEXT_HL; s.decision = why
        overlay.crossfadeStart = SystemClock.uptimeMillis()
        overlay.crossfadeInk = ArrayList(s.points)
        overlay.crossfadeInkColor = style.penColor
        overlay.crossfadeInkWidth = style.penWidth
    }

    private fun smartDecide(s: Stroke, finished: Boolean) {
        val text = pageText(s.pageId) ?: run { s.kind = Kind.INK; return }
        val line = text.lines.getOrNull(s.smartLine) ?: run { s.kind = Kind.INK; return }
        val params = StrokeClassifier.Params.from(tuning, ptPerDp())
        when (StrokeClassifier.classify(s.points, line, params, finished)) {
            StrokeClassifier.Decision.HIGHLIGHT -> convertToHighlight(s, "HIGHLIGHT")
            StrokeClassifier.Decision.INK -> { s.kind = Kind.INK; s.decision = "INK" }
            StrokeClassifier.Decision.UNDECIDED -> {}
        }
        if (tuning.debugOverlays) overlay.debugText = "classifier: ${s.decision}  angle=%.0f°  travel=%.1fpt".format(
            StrokeClassifier.principalAngleDeg(s.points), StrokeClassifier.maxDisplacement(s.points))
    }

    private fun eraseAlong(s: Stroke) {
        val src = source ?: return
        val n = s.points.size
        if (n == 0) return
        val a = s.points[maxOf(0, n - 2)]; val b = s.points[n - 1]
        // the eraser may wander across pages: test against the page under the current point
        val sx = layout.toScreenX(s.pageIndex, b.x); val sy = layout.toScreenY(s.pageIndex, b.y)
        val targets = HashSet<Int>().apply { add(s.pageIndex); layout.pageAt(sx, sy).takeIf { it >= 0 }?.let { add(it) } }
        val r = tuning.eraserRadiusDp * ptPerDp()
        var changed = false
        for (ti in targets) {
            val pid = layout.pages[ti].id
            val ax = layout.toPageX(ti, layout.toScreenX(s.pageIndex, a.x)); val ay = layout.toPageY(ti, layout.toScreenY(s.pageIndex, a.y))
            val bx = layout.toPageX(ti, sx); val by = layout.toPageY(ti, sy)
            for (an in src.annotsOn(pid)) {
                if (an.id in s.erased) continue
                if (HitTest.hits(an, ax, ay, bx, by, r)) {
                    s.erased[an.id] = an; changed = true
                    overlay.fadeOut(an, ti)
                }
            }
        }
        if (changed) pagesView.hiddenIds = s.erased.keys.toSet()
        overlay.eraserCursor = Triple(sx, sy, tuning.eraserRadiusDp * density)
    }

    private fun updateOverlay(s: Stroke) {
        overlay.pageIndex = s.pageIndex
        overlay.points = s.points
        when (s.kind) {
            Kind.INK, Kind.SMART_PENDING -> {
                overlay.kind = InkOverlayView.LiveKind.INK
                overlay.color = style.penColor; overlay.strokeW = style.penWidth
            }
            Kind.FREE_HL, Kind.HL_PENDING -> {
                overlay.kind = InkOverlayView.LiveKind.FREE_HIGHLIGHT
                overlay.color = style.hlColor; overlay.strokeW = style.hlWidth; overlay.opacity = Annot.FREE_HIGHLIGHT_OPACITY
            }
            Kind.TEXT_HL -> {
                overlay.kind = InkOverlayView.LiveKind.TEXT_HIGHLIGHT
                overlay.color = style.hlColor; overlay.opacity = Annot.TEXT_HIGHLIGHT_OPACITY
                overlay.boxes = pageText(s.pageId)?.let { t ->
                    s.snapper(t, tuning.lineExpandFraction).also { it.update(s.points) }.boxes()
                } ?: emptyList()
            }
            Kind.ERASE -> overlay.kind = InkOverlayView.LiveKind.ERASE
        }
        overlay.invalidate()
    }

    private fun penUp() {
        removeCallbacks(dwellCheck)
        val s = stroke
        stroke = null
        penPointerId = -1; isTouching = false; lastLiftTime = SystemClock.uptimeMillis()
        if (s == null) return
        if (s.kind == Kind.SMART_PENDING) smartDecide(s, finished = true)
        if (s.kind == Kind.SMART_PENDING) s.kind = Kind.INK
        if (s.kind == Kind.HL_PENDING) s.kind = Kind.FREE_HL
        val src = source ?: return
        val tol = 0.3f
        val cmd: Command? = when (s.kind) {
            Kind.INK -> {
                val pts = Geometry.simplify(Geometry.smooth(s.points), tol)
                Command.AddAnnot(Annot.Ink(Annot.newId(), s.pageId, style.penColor, style.penWidth, pts))
            }
            Kind.FREE_HL -> {
                val pts = Geometry.simplify(Geometry.smooth(s.points), tol).map { it.copy(pressure = 1f) }
                Command.AddAnnot(Annot.FreeHighlight(Annot.newId(), s.pageId, style.hlColor, style.hlWidth, pts))
            }
            Kind.TEXT_HL -> {
                val boxes = pageText(s.pageId)?.let { t -> s.snapper(t, tuning.lineExpandFraction).also { it.update(s.points) }.boxes() }
                if (boxes.isNullOrEmpty()) {
                    val pts = Geometry.simplify(Geometry.smooth(s.points), tol).map { it.copy(pressure = 1f) }
                    Command.AddAnnot(Annot.FreeHighlight(Annot.newId(), s.pageId, style.hlColor, style.hlWidth, pts))
                } else Command.AddAnnot(Annot.TextHighlight(Annot.newId(), s.pageId, style.hlColor, boxes))
            }
            Kind.ERASE -> if (s.erased.isNotEmpty()) Command.EraseItems(s.erased.values.toList()) else null
            else -> null
        }
        if (cmd != null) { src.execute(cmd); listener?.onEdit() }
        pagesView.hiddenIds = emptySet()
        overlay.crossfadeStart = 0L
        overlay.clearLive()
        pagesView.invalidate()
    }

    private fun penCancel() {
        removeCallbacks(dwellCheck)
        stroke = null
        penPointerId = -1; isTouching = false; lastLiftTime = SystemClock.uptimeMillis()
        pagesView.hiddenIds = emptySet()
        overlay.clearLive()
    }

    /** Small page render for thumbnails (page panel / recents). */
    suspend fun renderThumb(pageId: Long, page: PageEntry, widthPx: Int): Bitmap? {
        val src = source ?: return null
        src.prepare(pageId)
        val s = widthPx / page.width
        val b = Bitmap.createBitmap(widthPx, (page.height * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        return if (src.render(pageId, b, s, 0, 0)) b else null
    }

    // ------------------------------------------------------------------ text selection (finger long-press)

    private class Selection(val pageIndex: Int, val pageId: Long, var anchor: Int, var focus: Int) {
        val range get() = minOf(anchor, focus)..maxOf(anchor, focus)
    }

    private var barFingerId = -1
    private var barGrabOffset = 0f

    private var selection: Selection? = null
    private var selFingerId = -1
    /** While extending from a long-press, the initial word stays selected. */
    private var selWord: IntRange = IntRange.EMPTY
    private var lpPointer = -1
    private var lpX = 0f
    private var lpY = 0f
    private var lpDownTime = 0L
    private var tapMoved = false

    private val longPress = Runnable { onLongPress() }

    private fun scheduleLongPress(id: Int, x: Float, y: Float, now: Long) {
        cancelSelectLongPress()
        lpPointer = id; lpX = x; lpY = y; lpDownTime = now; tapMoved = false
        // the pen rules apply to selection too: no long-press while the pen hovers or just lifted
        if (isHovering || SystemClock.uptimeMillis() - lastLiftTime < tuning.stylusCooldownMs) return
        postDelayed(longPress, LONG_PRESS_MS)
    }

    private fun cancelSelectLongPress() { removeCallbacks(longPress) }

    private fun onLongPress() {
        if (lpPointer == -1 || tapMoved || isTouching || nav.isActive) return
        val pi = layout.pageAt(lpX, lpY).takeIf { it >= 0 } ?: return
        val page = layout.pages[pi]
        val text = pageText(page.id) ?: return
        if (!text.hasText) return
        val idx = TextSnapper.charAt(text, layout.toPageX(pi, lpX), layout.toPageY(pi, lpY), tuning.lineExpandFraction) ?: return
        var word = TextSnapper.wordAt(text, idx)
        if (word.isEmpty()) word = idx..idx
        nav.cancel()
        selection = Selection(pi, page.id, word.first, word.last)
        selWord = word
        selFingerId = lpPointer
        lpPointer = -1
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        showSelection()
    }

    /** Finger down on a handle starts dragging that end. */
    private fun startHandleDrag(x: Float, y: Float): Boolean {
        val s = selection ?: return false
        val (h1, h2) = overlay.handleCenters() ?: return false
        val r = 28f * density
        val r0 = s.range
        return when {
            Geometry.dist(x, y, h2.first, h2.second) <= r -> { s.anchor = r0.first; s.focus = r0.last; selWord = IntRange.EMPTY; true }
            Geometry.dist(x, y, h1.first, h1.second) <= r -> { s.anchor = r0.last; s.focus = r0.first; selWord = IntRange.EMPTY; true }
            else -> false
        }
    }

    private fun dragSelection(x: Float, y: Float) {
        val s = selection ?: return
        val text = pageText(s.pageId) ?: return
        // aim slightly above the finger so the text being selected isn't hidden under it
        val px = layout.toPageX(s.pageIndex, x)
        val py = layout.toPageY(s.pageIndex, y - (if (selWord.isEmpty()) overlay.handleRadius * 2 else 0f))
        val idx = TextSnapper.nearestChar(text, px, py) ?: return
        if (!selWord.isEmpty()) {
            // extending from a long-pressed word: keep the word, grow toward the finger
            if (idx >= selWord.first) { s.anchor = selWord.first; s.focus = maxOf(idx, selWord.last) }
            else { s.anchor = selWord.last; s.focus = idx }
        } else s.focus = idx
        showSelection()
    }

    private fun showSelection() {
        val s = selection
        if (s == null) {
            overlay.selectionPage = -1; overlay.selectionBoxes = emptyList()
        } else {
            val text = pageText(s.pageId)
            overlay.selectionPage = s.pageIndex
            overlay.selectionBoxes = text?.let { TextSnapper.boxes(it, s.range) } ?: emptyList()
        }
        overlay.invalidate()
        if (selFingerId == -1) publishSelection() else listener?.onSelection(null) // hide the menu while dragging
    }

    private fun publishSelection() {
        val s = selection
        if (s == null || overlay.selectionBoxes.isEmpty() || selFingerId != -1) { listener?.onSelection(null); return }
        val boxes = overlay.selectionBoxes
        val p = s.pageIndex
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (bx in boxes) {
            l = minOf(l, layout.toScreenX(p, bx.x0)); t = minOf(t, layout.toScreenY(p, bx.y0))
            r = maxOf(r, layout.toScreenX(p, bx.x1)); b = maxOf(b, layout.toScreenY(p, bx.y1))
        }
        // report in window coordinates (the canvas may be offset by the page panel)
        listener?.onSelection(SelectionInfo(l + left, t + top, r + left, b + top, highlightsInSelection().isNotEmpty()))
    }

    private fun highlightsInSelection(): List<Annot> {
        val s = selection ?: return emptyList()
        val boxes = overlay.selectionBoxes
        return source?.annotsOn(s.pageId)?.filter { a ->
            a is Annot.TextHighlight && a.quads.any { q -> boxes.any { it.expand(-0.5f).intersects(q) } }
        } ?: emptyList()
    }

    fun clearSelection() {
        selection = null; selFingerId = -1; selWord = IntRange.EMPTY
        showSelection()
    }

    fun selectionText(): String? {
        val s = selection ?: return null
        return pageText(s.pageId)?.let { TextSnapper.textOf(it, s.range) }
    }

    fun selectAllOnPage() {
        val s = selection ?: return
        val text = pageText(s.pageId) ?: return
        s.anchor = 0; s.focus = text.chars.size - 1; selWord = IntRange.EMPTY
        showSelection()
    }

    fun highlightSelection(color: Int) {
        val s = selection ?: return
        val src = source ?: return
        val boxes = pageText(s.pageId)?.let { TextSnapper.boxes(it, s.range) } ?: return
        if (boxes.isEmpty()) return
        src.execute(Command.AddAnnot(Annot.TextHighlight(Annot.newId(), s.pageId, color, boxes)))
        listener?.onEdit()
        clearSelection()
    }

    fun removeHighlightsInSelection() {
        val hl = highlightsInSelection()
        if (hl.isNotEmpty()) { source?.execute(Command.EraseItems(hl)); listener?.onEdit() }
        clearSelection()
    }

    companion object {
        const val HOVER_STALE_MS = 600L
        const val LONG_PRESS_MS = 450L
    }
}
