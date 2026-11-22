package io.github.o7py.margin

import io.github.o7py.margin.input.GestureNavigator
import io.github.o7py.margin.input.GestureNavigator.Finger
import io.github.o7py.margin.input.InputTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureNavigatorTest {
    private class Stylus : GestureNavigator.StylusState {
        override var isTouching = false
        override var isHovering = false
        override var lastLiftTime = -10_000L
    }

    private class Rec : GestureNavigator.Listener {
        var pans = 0f; var zoom = 1f; var started = 0
        override fun onPan(dx: Float, dy: Float) { pans += dy }
        override fun onZoom(factor: Float, focusX: Float, focusY: Float) { zoom *= factor }
        override fun onGestureStart() { started++ }
        override fun onGestureEnd(velocityX: Float, velocityY: Float) {}
    }

    private val stylus = Stylus()
    private val rec = Rec()
    private val nav = GestureNavigator(InputTuning(), stylus, rec)

    @Test fun twoFingerDragScrolls() {
        nav.fingerDown(Finger(0, 100f, 100f, 10f), 0)
        nav.fingerDown(Finger(1, 200f, 100f, 10f), 5)
        assertTrue(nav.isActive)
        nav.fingersMoved(listOf(Finger(0, 100f, 150f, 70f), Finger(1, 200f, 150f, 70f)), 20)
        assertEquals(50f, rec.pans, 0.01f)
    }

    @Test fun oneFingerDoesNothing() {
        nav.fingerDown(Finger(0, 100f, 100f, 10f), 0)
        nav.fingersMoved(listOf(Finger(0, 100f, 300f, 10f)), 20)
        assertFalse(nav.isActive); assertEquals(0f, rec.pans, 0f)
    }

    @Test fun slowSecondFingerRejected() {
        nav.fingerDown(Finger(0, 100f, 100f, 10f), 0)
        nav.fingerDown(Finger(1, 200f, 100f, 10f), 400)
        assertFalse(nav.isActive)
    }

    @Test fun hoveringPenBlocks() {
        stylus.isHovering = true
        nav.fingerDown(Finger(0, 100f, 100f, 10f), 0)
        nav.fingerDown(Finger(1, 200f, 100f, 10f), 5)
        assertFalse(nav.isActive)
    }

    @Test fun cooldownAfterPenLift() {
        stylus.lastLiftTime = 1000
        nav.fingerDown(Finger(0, 100f, 100f, 10f), 1200)
        nav.fingerDown(Finger(1, 200f, 100f, 10f), 1205)
        assertFalse(nav.isActive)
    }

    @Test fun threeFingersCancel() {
        nav.fingerDown(Finger(0, 100f, 100f, 10f), 0)
        nav.fingerDown(Finger(1, 200f, 100f, 10f), 5)
        nav.fingerDown(Finger(2, 300f, 100f, 10f), 30)
        assertFalse(nav.isActive)
        nav.fingerUp(2, 40)
        nav.fingersMoved(listOf(Finger(0, 100f, 150f, 10f), Finger(1, 200f, 150f, 10f)), 50)
        assertEquals(0f, rec.pans, 0f)
    }

    @Test fun largeContactRejectedWhenEnabled() {
        val nav2 = GestureNavigator(InputTuning(maxTouchMajorDp = 45f), stylus, rec)
        nav2.fingerDown(Finger(0, 100f, 100f, 80f), 0)
        nav2.fingerDown(Finger(1, 200f, 100f, 10f), 5)
        assertFalse(nav2.isActive)
    }

    @Test fun pinchZooms() {
        nav.fingerDown(Finger(0, 100f, 100f, 10f), 0)
        nav.fingerDown(Finger(1, 200f, 100f, 10f), 5)
        nav.fingersMoved(listOf(Finger(0, 50f, 100f, 10f), Finger(1, 250f, 100f, 10f)), 20)
        assertEquals(2f, rec.zoom, 0.001f)
    }

    @Test fun scrollWithWobblyFingersDoesNotZoom() {
        nav.fingerDown(Finger(0, 100f, 100f, 10f), 0)
        nav.fingerDown(Finger(1, 300f, 100f, 10f), 5)
        // fingers drift apart/together by up to 30 px while scrolling down
        nav.fingersMoved(listOf(Finger(0, 95f, 140f, 10f), Finger(1, 310f, 140f, 10f)), 20)
        nav.fingersMoved(listOf(Finger(0, 110f, 200f, 10f), Finger(1, 290f, 200f, 10f)), 40)
        assertEquals(1f, rec.zoom, 0f)
        assertEquals(100f, rec.pans, 0.01f)
    }

    @Test fun acceptedGestureContinuesWhenPenHovers() {
        nav.fingerDown(Finger(0, 100f, 100f, 10f), 0)
        nav.fingerDown(Finger(1, 200f, 100f, 10f), 5)
        stylus.isHovering = true
        nav.fingersMoved(listOf(Finger(0, 100f, 130f, 10f), Finger(1, 200f, 130f, 10f)), 20)
        assertEquals(30f, rec.pans, 0.01f)
    }

    @Test fun lockedOneFingerScrolls() {
        nav.oneFingerPan = true
        nav.fingerDown(Finger(0, 100f, 100f, 10f), 0)
        nav.fingersMoved(listOf(Finger(0, 100f, 104f, 10f)), 10) // within slop
        assertEquals(0f, rec.pans, 0f)
        nav.fingersMoved(listOf(Finger(0, 100f, 160f, 10f)), 30)
        assertTrue(nav.isActive)
        assertEquals(60f, rec.pans, 0.01f)
    }

    @Test fun lockedPinchDoesNotZoom() {
        nav.zoomEnabled = false
        nav.fingerDown(Finger(0, 100f, 100f, 10f), 0)
        nav.fingerDown(Finger(1, 200f, 100f, 10f), 5)
        nav.fingersMoved(listOf(Finger(0, 0f, 100f, 10f), Finger(1, 300f, 100f, 10f)), 20)
        assertEquals(1f, rec.zoom, 0f)
    }

    @Test fun unlockedOneFingerStillIgnored() {
        nav.fingerDown(Finger(0, 100f, 100f, 10f), 0)
        nav.fingersMoved(listOf(Finger(0, 100f, 300f, 10f)), 30)
        assertEquals(0f, rec.pans, 0f)
    }
}
