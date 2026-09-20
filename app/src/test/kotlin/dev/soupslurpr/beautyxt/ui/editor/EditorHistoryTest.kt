package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.Utf16Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Verifies journal ownership, native revision boundaries, and combined memory limits. */
class EditorHistoryTest {
    @Test
    fun startsEmpty() {
        val history = EditorHistory()
        assertNull(history.undoEntry)
        assertNull(history.redoEntry)
        assertNull(history.headRevision)
        assertEquals(0, history.retainedUtf16Units)
    }

    @Test
    fun movesEntriesWithoutDuplicatingText() {
        val history = EditorHistory()
        val first = delta(0, "old", "new")
        val second = delta(1, "", "😀")
        history.record(first)
        history.record(second)
        assertEquals(8, history.retainedUtf16Units)
        history.completeUndo(second, revision = 3)
        assertSame(first, history.undoEntry)
        assertSame(second, history.redoEntry)
        assertEquals(8, history.retainedUtf16Units)
        history.completeRedo(second, revision = 4)
        assertSame(second, history.undoEntry)
        assertNull(history.redoEntry)
        assertEquals(4L, history.headRevision)
        assertEquals(8, history.retainedUtf16Units)
    }

    @Test
    fun dropsOldestEntriesAtTheCountLimit() {
        val history = EditorHistory(maxEntries = 2)
        val second = delta(1, "", "b")
        val third = delta(2, "", "c")
        history.record(delta(0, "", "a"))
        history.record(second)
        history.record(third)
        assertEquals(2, history.retainedUtf16Units)
        history.completeUndo(third, revision = 4)
        history.completeUndo(second, revision = 5)
        assertNull(history.undoEntry)
    }

    @Test
    fun dropsOldestEntriesAtTheTextLimit() {
        val history = EditorHistory(maxRetainedUtf16Units = 6)
        history.record(delta(0, "", "abc"))
        history.record(delta(1, "", "def"))
        val newest = delta(2, "", "ghij")
        history.record(newest)
        assertEquals(4, history.retainedUtf16Units)
        history.completeUndo(newest, revision = 4)
        assertNull(history.undoEntry)
    }

    @Test
    fun releasesRedoBeforeBudgetingANewBranch() {
        val history = EditorHistory(maxRetainedUtf16Units = 6)
        val first = delta(0, "", "a")
        val abandoned = delta(1, "", "12345")
        history.record(first)
        history.record(abandoned)
        history.completeUndo(abandoned, revision = 3)
        val branch = delta(3, "", "bcde")
        history.record(branch)
        assertNull(history.redoEntry)
        assertEquals(5, history.retainedUtf16Units)
        history.completeUndo(branch, revision = 5)
        assertSame(first, history.undoEntry)
    }

    @Test
    fun discardsHistoryAcrossAnUnrecordableEdit() {
        val history = EditorHistory(maxRetainedUtf16Units = 3)
        history.record(delta(0, "", "a"))
        history.record(delta(1, "a", "abcd"))
        assertNull(history.undoEntry)
        assertNull(history.redoEntry)
        assertEquals(0, history.retainedUtf16Units)
        assertEquals(2L, history.headRevision)
    }

    @Test
    fun discardsBothBranchesAfterAnExternalRevision() {
        val history = EditorHistory()
        val second = delta(1, "", "b")
        history.record(delta(0, "", "a"))
        history.record(second)
        history.completeUndo(second, revision = 3)
        val external = delta(9, "", "c")
        history.record(external)
        assertSame(external, history.undoEntry)
        assertNull(history.redoEntry)
        assertEquals(1, history.retainedUtf16Units)
        assertEquals(10L, history.headRevision)
    }

    @Test
    fun rejectsAReplacedEntryWithoutMutatingTheJournal() {
        val history = EditorHistory()
        val entry = delta(0, "", "a")
        history.record(entry)
        assertThrows(IllegalStateException::class.java) {
            history.completeUndo(entry.copy(), revision = 2)
        }
        assertSame(entry, history.undoEntry)
        assertNull(history.redoEntry)
        assertEquals(1L, history.headRevision)
    }

    @Test
    fun rejectsAnUnexpectedRevisionBeforeMovingAnEntry() {
        val history = EditorHistory()
        val entry = delta(0, "", "a")
        history.record(entry)
        assertThrows(IllegalArgumentException::class.java) {
            history.completeUndo(entry, revision = 3)
        }
        assertSame(entry, history.undoEntry)
        assertNull(history.redoEntry)
        assertEquals(1L, history.headRevision)
    }

    @Test
    fun releasesBothBranchesOnClear() {
        val history = EditorHistory()
        val entry = delta(0, "", "a")
        history.record(entry)
        history.completeUndo(entry, revision = 2)
        history.clear(revision = 7)
        assertNull(history.undoEntry)
        assertNull(history.redoEntry)
        assertEquals(0, history.retainedUtf16Units)
        assertEquals(7L, history.headRevision)
    }

    @Test
    fun requiresPositiveBudgetsAndNonnegativeRevisions() {
        assertThrows(IllegalArgumentException::class.java) { EditorHistory(maxEntries = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            EditorHistory(maxRetainedUtf16Units = 0)
        }
        assertThrows(IllegalArgumentException::class.java) { EditorHistory().clear(revision = -1) }
    }

    /** Creates one valid insertion or replacement at the document start. */
    private fun delta(revision: Long, removed: String, inserted: String) = CommittedEditDelta(
        revisionBefore = revision,
        revisionAfter = revision + 1,
        rangeStart = 0,
        removedText = removed,
        insertedText = inserted,
        selectionBefore = Utf16Range(0, 0),
        selectionAfter = Utf16Range(inserted.length.toLong(), inserted.length.toLong())
    )
}
