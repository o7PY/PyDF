package io.github.o7py.margin

import io.github.o7py.margin.edit.HitTest
import io.github.o7py.margin.input.ModeController
import io.github.o7py.margin.model.Annot
import io.github.o7py.margin.model.Box
import io.github.o7py.margin.model.Geometry
import io.github.o7py.margin.model.InkPoint
import io.github.o7py.margin.model.PressureCurve
import io.github.o7py.margin.model.StrokeAction
import io.github.o7py.margin.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeAndGeometryTest {
    @Test fun smartPenMapping() {
        assertEquals(StrokeAction.SMART, ModeController.actionFor(Tool.PEN, false, true))
        assertEquals(StrokeAction.INK, ModeController.actionFor(Tool.PEN, false, false))
        assertEquals(StrokeAction.HIGHLIGHT, ModeController.actionFor(Tool.HIGHLIGHTER, false, true))
        assertEquals(StrokeAction.ERASE, ModeController.actionFor(Tool.ERASER, false, true))
    }

    @Test fun buttonAlwaysErases() {
        for (t in Tool.entries) assertEquals(StrokeAction.ERASE, ModeController.actionFor(t, true, true))
    }

    @Test fun pressureCurveClamps() {
        assertEquals(PressureCurve.MIN_FRACTION, PressureCurve.factor(0f), 1e-6f)
        assertEquals(1f, PressureCurve.factor(1f), 1e-6f)
    }

    @Test fun rdpRemovesCollinearPoints() {
        val pts = (0..100).map { InkPoint(it.toFloat(), 0f, 0.5f) }
        assertEquals(2, Geometry.simplify(pts, 0.3f).size)
    }

    @Test fun rdpKeepsCorners() {
        val pts = (0..50).map { InkPoint(it.toFloat(), 0f, 0.5f) } + (1..50).map { InkPoint(50f, it.toFloat(), 0.5f) }
        assertEquals(3, Geometry.simplify(pts, 0.3f).size)
    }

    @Test fun eraserHitsInkAndHighlight() {
        val ink = Annot.Ink("a", 0, 0, 1f, listOf(InkPoint(0f, 0f), InkPoint(100f, 0f)))
        assertTrue(HitTest.hits(ink, 50f, -10f, 50f, 10f, 2f))
        assertFalse(HitTest.hits(ink, 50f, 10f, 60f, 10f, 2f))
        val hl = Annot.TextHighlight("b", 0, 0, listOf(Box(0f, 0f, 50f, 12f)))
        assertTrue(HitTest.hits(hl, 25f, 6f, 26f, 6f, 1f))
        assertTrue(HitTest.hits(hl, -5f, -5f, 60f, 20f, 0f))
        assertFalse(HitTest.hits(hl, 60f, 30f, 70f, 30f, 2f))
    }
}
