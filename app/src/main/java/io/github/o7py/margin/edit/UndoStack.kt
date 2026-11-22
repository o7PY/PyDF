package io.github.o7py.margin.edit

import io.github.o7py.margin.model.Annot
import io.github.o7py.margin.model.PageEntry

/** A reversible edit (SPEC §8.5). */
sealed interface Command {
    fun apply(s: EditState)
    fun revert(s: EditState)
    val changesLayout: Boolean get() = false

    data class AddAnnot(val annot: Annot) : Command {
        override fun apply(s: EditState) = s.add(annot)
        override fun revert(s: EditState) { s.remove(annot) }
    }

    /** One eraser stroke's worth of deletions. Indices are recorded so undo restores z-order. */
    class EraseItems(val items: List<Annot>) : Command {
        private val indices = HashMap<String, Int>()
        override fun apply(s: EditState) { items.forEach { indices[it.id] = s.remove(it) } }
        override fun revert(s: EditState) {
            // re-insert in reverse removal order so each recorded index is valid again
            items.asReversed().forEach { s.add(it, indices[it.id] ?: -1) }
        }
    }

    data class InsertPage(val index: Int, val entry: PageEntry) : Command {
        override val changesLayout get() = true
        override fun apply(s: EditState) = s.insertPage(index, entry)
        override fun revert(s: EditState) { s.removePage(index) }
    }

    data class DeletePage(val index: Int, val entry: PageEntry) : Command {
        override val changesLayout get() = true
        override fun apply(s: EditState) { s.removePage(index) }
        override fun revert(s: EditState) = s.insertPage(index, entry)
    }
}

class UndoStack(private val depth: Int = 200) {
    private class Entry(val command: Command, val id: Long)

    private val undo = ArrayDeque<Entry>()
    private val redo = ArrayDeque<Entry>()
    private var nextId = 1L

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    /** Identifies the current edit state: equal ids = same content (used for the unsaved indicator). */
    val stateId: Long get() = undo.lastOrNull()?.id ?: 0L

    fun execute(c: Command, s: EditState) {
        c.apply(s)
        undo.addLast(Entry(c, nextId++))
        while (undo.size > depth) undo.removeFirst()
        redo.clear()
    }

    fun undo(s: EditState): Command? {
        val e = undo.removeLastOrNull() ?: return null
        e.command.revert(s); redo.addLast(e); return e.command
    }

    fun redo(s: EditState): Command? {
        val e = redo.removeLastOrNull() ?: return null
        e.command.apply(s); undo.addLast(e); return e.command
    }

    fun clear() { undo.clear(); redo.clear() }
}
