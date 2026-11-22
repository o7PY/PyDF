package io.github.o7py.margin.input

import kotlin.math.hypot

/**
 * Two-finger navigation state machine (SPEC §6.2/§6.3). Pure logic: fed finger samples plus stylus state,
 * emits pan/zoom/fling through [Listener]. One-finger input never does anything.
 */
class GestureNavigator(
    private var tuning: InputTuning,
    private val stylus: StylusState,
    private val listener: Listener,
    private val density: Float = 1f,
) {
    interface Listener {
        fun onPan(dx: Float, dy: Float)
        fun onZoom(factor: Float, focusX: Float, focusY: Float)
        fun onGestureStart()
        fun onGestureEnd(velocityX: Float, velocityY: Float)
    }

    /** Stylus status as seen by the canvas. */
    interface StylusState {
        val isTouching: Boolean
        val isHovering: Boolean
        val lastLiftTime: Long
    }

    data class Finger(val id: Int, val x: Float, val y: Float, val touchMajorDp: Float)

    private enum class State { IDLE, CANDIDATE, ACTIVE, ACTIVE_ONE, REJECTED }

    /** Lock mode: one finger scrolls. */
    var oneFingerPan = false
    /** Lock mode turns pinch-zoom off. */
    var zoomEnabled = true
    private val touchSlop = 8f * density
    private var oneStartX = 0f
    private var oneStartY = 0f

    private var state = State.IDLE
    private val down = LinkedHashMap<Int, Long>()      // finger id -> down time
    private val last = HashMap<Int, Finger>()
    private var firstDownTime = 0L
    private val velSamples = ArrayDeque<Triple<Long, Float, Float>>() // time, midX, midY
    /** Finger distance when the gesture started; zoom only engages once the pinch clearly departs from it. */
    private var startSpan = 0f
    private var zooming = false

    val isActive get() = state == State.ACTIVE || state == State.ACTIVE_ONE

    fun updateTuning(t: InputTuning) { tuning = t }

    private fun tooLarge(f: Finger) = tuning.maxTouchMajorDp > 0f && f.touchMajorDp > tuning.maxTouchMajorDp

    private fun penBlocks(now: Long) =
        stylus.isTouching || stylus.isHovering || now - stylus.lastLiftTime < tuning.stylusCooldownMs

    fun fingerDown(f: Finger, now: Long) {
        down[f.id] = now
        last[f.id] = f
        when {
            down.size == 1 -> {
                firstDownTime = now
                oneStartX = f.x; oneStartY = f.y
                state = if (penBlocks(now) || tooLarge(f)) State.REJECTED else State.CANDIDATE
            }
            down.size == 2 && state == State.ACTIVE_ONE -> {
                // one-finger scroll becomes a two-finger gesture
                listener.onGestureEnd(0f, 0f)
                if (penBlocks(now)) state = State.REJECTED else startTwoFinger(f, now)
            }
            down.size == 2 && state == State.CANDIDATE -> {
                val first = last[down.keys.first()] ?: f
                val ok = now - firstDownTime <= tuning.pairWindowMs && !tooLarge(f) && !tooLarge(first) && !penBlocks(now)
                if (ok) startTwoFinger(f, now) else state = State.REJECTED
            }
            else -> { // 3+ fingers, or 2nd finger after rejection: cancel until all lift
                if (state == State.ACTIVE) listener.onGestureEnd(0f, 0f)
                state = State.REJECTED
            }
        }
    }

    private fun startTwoFinger(f: Finger, now: Long) {
        val first = last[down.keys.first()] ?: f
        state = State.ACTIVE
        velSamples.clear()
        zooming = false
        startSpan = hypot(first.x - f.x, first.y - f.y)
        listener.onGestureStart()
        sampleVelocity(now)
    }

    /** Called with the current positions of all fingers down. */
    fun fingersMoved(fingers: List<Finger>, now: Long) {
        if (state == State.CANDIDATE && down.size == 1 && oneFingerPan) {
            val f = fingers.firstOrNull { it.id in down }
            if (f != null && hypot(f.x - oneStartX, f.y - oneStartY) > touchSlop && !penBlocks(now)) {
                state = State.ACTIVE_ONE
                velSamples.clear()
                listener.onGestureStart()
                last[f.id] = Finger(f.id, oneStartX, oneStartY, f.touchMajorDp) // include the slop so it doesn't lag
            }
        }
        if (state == State.ACTIVE_ONE) {
            val id = down.keys.firstOrNull() ?: return
            val f = fingers.firstOrNull { it.id == id } ?: return
            val l = last[id] ?: f
            if (f.x != l.x || f.y != l.y) listener.onPan(f.x - l.x, f.y - l.y)
            last[id] = f
            sampleVelocity(now)
            return
        }
        if (state != State.ACTIVE) { fingers.forEach { if (it.id in down) last[it.id] = it }; return }
        val ids = down.keys.toList()
        if (ids.size != 2) return
        val a0 = last[ids[0]] ?: return; val b0 = last[ids[1]] ?: return
        val a1 = fingers.firstOrNull { it.id == ids[0] } ?: a0
        val b1 = fingers.firstOrNull { it.id == ids[1] } ?: b0
        val m0x = (a0.x + b0.x) / 2f; val m0y = (a0.y + b0.y) / 2f
        val m1x = (a1.x + b1.x) / 2f; val m1y = (a1.y + b1.y) / 2f
        val d0 = hypot(a0.x - b0.x, a0.y - b0.y); val d1 = hypot(a1.x - b1.x, a1.y - b1.y)
        if (!zooming && kotlin.math.abs(d1 - startSpan) > tuning.zoomStartDp * density) zooming = true
        if (zooming && zoomEnabled && d0 > 1f && d1 > 1f && d1 / d0 != 1f) listener.onZoom(d1 / d0, m1x, m1y)
        if (m1x != m0x || m1y != m0y) listener.onPan(m1x - m0x, m1y - m0y)
        last[ids[0]] = a1; last[ids[1]] = b1
        sampleVelocity(now)
    }

    fun fingerUp(id: Int, now: Long) {
        if (state == State.ACTIVE || state == State.ACTIVE_ONE) {
            val (vx, vy) = velocity(now)
            listener.onGestureEnd(vx, vy)
            state = State.REJECTED // remaining finger does nothing until all lift
        }
        down.remove(id); last.remove(id)
        if (down.isEmpty()) state = State.IDLE
    }

    fun cancel() {
        if (state == State.ACTIVE) listener.onGestureEnd(0f, 0f)
        down.clear(); last.clear(); state = State.IDLE
    }

    private fun sampleVelocity(now: Long) {
        val pts = down.keys.mapNotNull { last[it] }
        if (pts.isEmpty()) return
        velSamples.addLast(Triple(now, pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat()))
        while (velSamples.size > 2 && now - velSamples.first().first > 100) velSamples.removeFirst()
    }

    /** Midpoint velocity over the last ~100 ms, px/s. */
    private fun velocity(now: Long): Pair<Float, Float> {
        if (velSamples.size < 2) return 0f to 0f
        val f = velSamples.first(); val l = velSamples.last()
        if (now - l.first > 80) return 0f to 0f // fingers paused before lifting
        val dt = (l.first - f.first).coerceAtLeast(1) / 1000f
        return (l.second - f.second) / dt to (l.third - f.third) / dt
    }
}
