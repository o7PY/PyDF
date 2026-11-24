package io.github.o7py.margin

import io.github.o7py.margin.edit.TextSnapper
import io.github.o7py.margin.model.Box
import io.github.o7py.margin.model.CharGeom
import io.github.o7py.margin.model.PageText
import io.github.o7py.margin.model.TextLineGeom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextSnapperTest {
    private val text = Fixtures.page(lines = 3, chars = 10) // chars 6 pt wide from x=72

    @Test fun singleLineSnapsToCharBoundaries() {
        val y = text.lines[0].bbox.cy
        val boxes = TextSnapper.snap(text, Fixtures.line(80f, y, 100f, y))!!
        assertEquals(1, boxes.size)
        assertEquals(78f, boxes[0].x0, 0.01f)  // char 1 starts at 78
        assertEquals(102f, boxes[0].x1, 0.01f) // char 4 ends at 102
    }

    @Test fun crossingLinesIncludesWholeIntermediateLines() {
        val y0 = text.lines[0].bbox.cy; val y2 = text.lines[2].bbox.cy
        val pts = Fixtures.line(110f, y0, 115f, y0, n = 3) + Fixtures.line(80f, y2, 85f, y2, n = 3)
        val boxes = TextSnapper.snap(text, pts)!!
        assertEquals(3, boxes.size)
        assertEquals(108f, boxes[0].x0, 0.01f)       // from char 6 to end of line 0
        assertEquals(132f, boxes[0].x1, 0.01f)
        assertEquals(72f, boxes[1].x0, 0.01f)        // whole middle line
        assertEquals(132f, boxes[1].x1, 0.01f)
        assertEquals(72f, boxes[2].x0, 0.01f)        // start of line 2 to char 2
        assertEquals(90f, boxes[2].x1, 0.01f)
    }

    @Test fun offTextGivesNull() = assertNull(TextSnapper.snap(text, Fixtures.line(300f, 300f, 320f, 300f)))

    private val prose = Fixtures.page(lines = 2, content = listOf("hello brave world", "second line"))

    @Test fun wordAtFindsWord() {
        val r = TextSnapper.wordAt(prose, 8) // inside "brave"
        assertEquals("brave", TextSnapper.textOf(prose, r))
    }

    @Test fun wordAtEdges() {
        assertEquals("hello", TextSnapper.textOf(prose, TextSnapper.wordAt(prose, 0)))
        assertEquals("world", TextSnapper.textOf(prose, TextSnapper.wordAt(prose, 16)))
        assertEquals("second", TextSnapper.textOf(prose, TextSnapper.wordAt(prose, 17))) // first char of line 2
    }

    @Test fun textAcrossLinesUsesNewline() {
        assertEquals("world\nsecond", TextSnapper.textOf(prose, 12..22))
    }

    @Test fun nearestCharClampsOutside() {
        assertEquals(0, TextSnapper.nearestChar(prose, 0f, 0f))
        assertEquals(prose.chars.size - 1, TextSnapper.nearestChar(prose, 999f, 999f))
    }

    @Test fun nearestCharPrefersLineUnderXWhenRowIsSplit() {
        // one visual row split into two text lines: [72..132] and [200..260]
        val left = TextLineGeom(Box(72f, 72f, 132f, 84f), (0 until 10).map { CharGeom(Box(72f + it * 6, 72f, 78f + it * 6, 84f), c = 'a'.code) })
        val right = TextLineGeom(Box(200f, 72f, 260f, 84f), (0 until 10).map { CharGeom(Box(200f + it * 6, 72f, 206f + it * 6, 84f), c = 'b'.code) })
        val t = PageText(listOf(left, right))
        val i = TextSnapper.nearestChar(t, 230f, 78f)!!
        assertEquals(1, t.chars[i].line)
    }

    // ---- selection-style highlighting: going back un-highlights

    private val ten = Fixtures.page(lines = 3, chars = 10) // chars 6 pt wide from x=72, lines at y 72/88/104

    @Test fun goingBackOnTheSameLineShrinks() {
        val y = ten.lines[0].bbox.cy
        val pts = Fixtures.line(75f, y, 125f, y) + Fixtures.line(125f, y, 95f, y)   // to char 8, back to char 3
        val acc = TextSnapper.Accumulator(ten, 0.15f)
        acc.update(pts)
        assertEquals(0..3, acc.range)
        assertEquals(0..3, TextSnapper.range(ten, pts))
    }

    @Test fun goingPastTheStartFlipsDirection() {
        val y = ten.lines[0].bbox.cy
        val pts = Fixtures.line(100f, y, 125f, y) + Fixtures.line(125f, y, 75f, y)  // start char 4, end char 0
        assertEquals(0..4, TextSnapper.range(ten, pts))
    }

    @Test fun comingBackFromAnotherLineRemovesThatLine() {
        val y0 = ten.lines[0].bbox.cy; val y2 = ten.lines[2].bbox.cy
        val acc = TextSnapper.Accumulator(ten, 0.15f)
        val down = Fixtures.line(80f, y0, 125f, y2, n = 40)          // line 1 → end of line 3
        acc.update(down)
        assertEquals(1..28, acc.range)                                // three lines highlighted
        assertEquals(3, TextSnapper.boxes(ten, acc.range!!).size)
        val back = down + Fixtures.line(125f, y2, 110f, y0, n = 40)  // back up to line 1
        acc.update(back)
        assertEquals(1..6, acc.range)                                 // only line 1 again
        assertEquals(1, TextSnapper.boxes(ten, acc.range!!).size)
    }

    @Test fun pausingBetweenLinesKeepsTheHighlight() {
        val y0 = ten.lines[0].bbox.cy
        val gapY = ten.lines[0].bbox.y1 + 2f     // exact middle of the gap between line 1 and line 2 bands
        val acc = TextSnapper.Accumulator(ten, 0.15f)
        acc.update(Fixtures.line(80f, y0, 120f, y0) + Fixtures.line(120f, gapY, 121f, gapY, n = 3))
        assertEquals(1..7, acc.range)
    }
}
