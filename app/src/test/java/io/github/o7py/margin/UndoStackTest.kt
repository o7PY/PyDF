package io.github.o7py.margin

import io.github.o7py.margin.edit.Command
import io.github.o7py.margin.edit.EditState
import io.github.o7py.margin.edit.UndoStack
import io.github.o7py.margin.model.Annot
import io.github.o7py.margin.model.InkPoint
import io.github.o7py.margin.model.PageEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoStackTest {
    private fun state() = EditState(listOf(PageEntry.Original(0, 0, 612f, 792f), PageEntry.Original(1, 1, 612f, 792f)))
    private fun ink(id: String, page: Long = 0) = Annot.Ink(id, page, 0, 1f, listOf(InkPoint(0f, 0f), InkPoint(10f, 10f)))

    @Test fun addUndoRedo() {
        val s = state(); val u = UndoStack()
        u.execute(Command.AddAnnot(ink("a")), s)
        assertEquals(1, s.annotsOn(0).size)
        u.undo(s); assertEquals(0, s.annotsOn(0).size)
        u.redo(s); assertEquals(1, s.annotsOn(0).size)
    }

    @Test fun eraseRestoresOrder() {
        val s = state(); val u = UndoStack()
        listOf("a", "b", "c").forEach { u.execute(Command.AddAnnot(ink(it)), s) }
        u.execute(Command.EraseItems(listOf(s.annotsOn(0)[0], s.annotsOn(0)[2])), s)
        assertEquals(listOf("b"), s.annotsOn(0).map { it.id })
        u.undo(s)
        assertEquals(listOf("a", "b", "c"), s.annotsOn(0).map { it.id })
    }

    @Test fun newCommandClearsRedo() {
        val s = state(); val u = UndoStack()
        u.execute(Command.AddAnnot(ink("a")), s); u.undo(s)
        assertTrue(u.canRedo)
        u.execute(Command.AddAnnot(ink("b")), s)
        assertFalse(u.canRedo)
    }

    @Test fun depthIsBounded() {
        val s = state(); val u = UndoStack(depth = 3)
        repeat(5) { u.execute(Command.AddAnnot(ink("i$it")), s) }
        var n = 0; while (u.undo(s) != null) n++
        assertEquals(3, n)
        assertEquals(2, s.annotsOn(0).size)
    }

    @Test fun insertAndDeletePage() {
        val s = state(); val u = UndoStack()
        val e = PageEntry.Inserted(s.newPageId(), 612f, 792f, -1)
        u.execute(Command.InsertPage(1, e), s)
        assertEquals(listOf(0L, e.id, 1L), s.pages.map { it.id })
        u.execute(Command.DeletePage(0, s.pages[0]), s)
        assertEquals(listOf(e.id, 1L), s.pages.map { it.id })
        u.undo(s); u.undo(s)
        assertEquals(listOf(0L, 1L), s.pages.map { it.id })
    }

    @Test fun stateIdReturnsAfterUndo() {
        val s = state(); val u = UndoStack()
        val clean = u.stateId
        u.execute(Command.AddAnnot(ink("a")), s)
        val afterA = u.stateId
        assertTrue(afterA != clean)
        u.undo(s); assertEquals(clean, u.stateId)
        u.redo(s); assertEquals(afterA, u.stateId)
    }
}
