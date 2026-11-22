package io.github.o7py.margin

import io.github.o7py.margin.Fixtures.LINE_H
import io.github.o7py.margin.input.StrokeClassifier
import io.github.o7py.margin.input.StrokeClassifier.Decision
import io.github.o7py.margin.model.InkPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StrokeClassifierTest {
    private val text = Fixtures.page()
    private val params = StrokeClassifier.Params(decisionDistance = 18f, dwellRadius = 3f)
    private val line0 = text.lines[0]
    private val mid = line0.bbox.cy

    private fun decide(points: List<InkPoint>): Decision {
        val li = StrokeClassifier.lineAt(text, points[0].x, points[0].y, params.lineExpand) ?: return Decision.INK
        return StrokeClassifier.classify(points, text.lines[li], params, finished = true)
    }

    @Test fun horizontalSwipeIsHighlight() =
        assertEquals(Decision.HIGHLIGHT, decide(Fixtures.line(80f, mid, 260f, mid + 1f)))

    @Test fun slightlySlopedJitteryHighlight() =
        assertEquals(Decision.HIGHLIGHT, decide(Fixtures.line(80f, mid - 2f, 200f, mid + 2f, jitter = 0.8f)))

    @Test fun rightToLeftSwipeIsHighlight() =
        assertEquals(Decision.HIGHLIGHT, decide(Fixtures.line(260f, mid, 90f, mid)))

    @Test fun underlineBelowLineIsInk() {
        val y = line0.bbox.y1 + 1.5f // just under the line, inside the 15 % expanded band
        assertEquals(Decision.INK, decide(Fixtures.line(80f, y, 260f, y)))
    }

    @Test fun circleAroundWordIsInk() =
        assertEquals(Decision.INK, decide(Fixtures.circle(120f, mid, 22f, 10f)))

    @Test fun handwritingOverTextIsInk() =
        assertEquals(Decision.INK, decide(Fixtures.handwriting(90f, mid)))

    @Test fun diagonalStrokeIsInk() =
        assertEquals(Decision.INK, decide(Fixtures.line(80f, mid, 160f, mid + 60f)))

    @Test fun verticalStrokeIsInk() =
        assertEquals(Decision.INK, decide(Fixtures.line(100f, mid, 101f, mid + 50f)))

    @Test fun strokeStartingOffTextIsInkImmediately() =
        assertNull(StrokeClassifier.lineAt(text, 40f, 40f, params.lineExpand))

    @Test fun quickTapIsInkDot() {
        val tap = listOf(InkPoint(100f, mid, 0.5f, 0), InkPoint(101f, mid, 0.5f, 40))
        assertEquals(Decision.INK, StrokeClassifier.classify(tap, line0, params, finished = true))
    }

    @Test fun undecidedBeforeDecisionDistance() {
        val pts = Fixtures.line(80f, mid, 90f, mid, n = 5)
        assertEquals(Decision.UNDECIDED, StrokeClassifier.classify(pts, line0, params, finished = false))
    }

    @Test fun earlyHorizontalPrefixDecidesHighlight() {
        val pts = Fixtures.line(80f, mid, 100f, mid, n = 10)
        assertEquals(Decision.HIGHLIGHT, StrokeClassifier.classify(pts, line0, params, finished = false))
    }

    @Test fun reversalsCounted() {
        val zig = listOf(0f, 10f, 0f, 10f, 0f).mapIndexed { i, x -> InkPoint(x, 0f, 1f, i.toLong()) }
        assertEquals(3, StrokeClassifier.xReversals(zig, LINE_H * 0.3f))
    }

    /** Pen resting with jitter: path length grows but it hasn't really moved. */
    private fun jitteryDwell(x: Float, y: Float, n: Int = 40): List<InkPoint> =
        (0 until n).map { i -> InkPoint(x + (if (i % 2 == 0) 1.2f else -1.2f), y + (if (i % 3 == 0) 1f else -0.8f), 0.6f, i * 8L) }

    @Test fun pressThenSwipeIsHighlight() {
        val dwell = jitteryDwell(80f, mid)
        val swipe = Fixtures.line(80f, mid, 260f, mid).map { it.copy(t = it.t + 320) }
        assertEquals(Decision.HIGHLIGHT, decide(dwell + swipe))
    }

    @Test fun jitterWhileRestingDoesNotDecideEarly() {
        val dwell = jitteryDwell(80f, mid)
        assertEquals(Decision.UNDECIDED, StrokeClassifier.classify(dwell, line0, params, finished = false))
    }

    @Test fun pressThenHandwritingStaysInk() {
        val dwell = jitteryDwell(90f, mid)
        val hw = Fixtures.handwriting(90f, mid).map { it.copy(t = it.t + 320) }
        assertEquals(Decision.INK, decide(dwell + hw))
    }

    @Test fun dwellDurationMeasured() {
        val dwell = jitteryDwell(80f, mid, n = 40)
        // the swipe's first point (t = 400) is still within the resting radius
        assertEquals(400L, StrokeClassifier.dwellMs(dwell + Fixtures.line(80f, mid, 200f, mid).map { it.copy(t = it.t + 400) }, 3f))
    }
}
