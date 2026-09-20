package dev.soupslurpr.beautyxt.ui.editor

private const val MAX_HISTORY_ENTRIES = 128
private const val MAX_HISTORY_UTF16_UNITS = 256 * 1_024

/** Owns revision-bound undo and redo entries within one shared memory budget. */
internal class EditorHistory(
    private val maxEntries: Int = MAX_HISTORY_ENTRIES,
    private val maxRetainedUtf16Units: Int = MAX_HISTORY_UTF16_UNITS
) {
    init {
        require(maxEntries > 0) { "history entry limit must be positive" }
        require(maxRetainedUtf16Units > 0) { "history text limit must be positive" }
    }

    private val undoEntries = ArrayDeque<CommittedEditDelta>()
    private val redoEntries = ArrayDeque<CommittedEditDelta>()

    var headRevision: Long? = null
        private set

    var retainedUtf16Units = 0
        private set

    val undoEntry: CommittedEditDelta?
        get() = undoEntries.lastOrNull()

    val redoEntry: CommittedEditDelta?
        get() = redoEntries.lastOrNull()

    /** Appends one verified edit and releases any superseded redo branch. */
    fun record(delta: CommittedEditDelta) {
        if (headRevision != null && headRevision != delta.revisionBefore) {
            clear()
        }
        while (redoEntries.isNotEmpty()) {
            retainedUtf16Units =
                Math.subtractExact(retainedUtf16Units, redoEntries.removeLast().retainedUtf16Units)
        }
        val retainedUnits = delta.retainedUtf16Units
        if (retainedUnits > maxRetainedUtf16Units) {
            clear(revision = delta.revisionAfter)
            return
        }
        while (
            undoEntries.size >= maxEntries ||
            retainedUnits > maxRetainedUtf16Units - retainedUtf16Units
        ) {
            retainedUtf16Units =
                Math.subtractExact(retainedUtf16Units, undoEntries.removeFirst().retainedUtf16Units)
        }
        undoEntries.addLast(delta)
        retainedUtf16Units = Math.addExact(retainedUtf16Units, retainedUnits)
        headRevision = delta.revisionAfter
    }

    /** Moves the exact undo entry only after its native inverse succeeds. */
    fun completeUndo(entry: CommittedEditDelta, revision: Long) {
        moveAppliedEntry(undoEntries, redoEntries, entry, revision)
    }

    /** Moves the exact redo entry only after its native replacement succeeds. */
    fun completeRedo(entry: CommittedEditDelta, revision: Long) {
        moveAppliedEntry(redoEntries, undoEntries, entry, revision)
    }

    /** Releases all retained text and optionally binds the empty journal to a revision. */
    fun clear(revision: Long? = null) {
        require(revision == null || revision >= 0L) { "history revision must be nonnegative" }
        undoEntries.clear()
        redoEntries.clear()
        retainedUtf16Units = 0
        headRevision = revision
    }

    /** Advances the journal without duplicating an applied entry's retained text. */
    private fun moveAppliedEntry(
        source: ArrayDeque<CommittedEditDelta>,
        destination: ArrayDeque<CommittedEditDelta>,
        entry: CommittedEditDelta,
        revision: Long
    ) {
        check(source.lastOrNull() === entry) {
            "history changed while its native edit was running"
        }
        require(revision == Math.incrementExact(checkNotNull(headRevision))) {
            "applied history revision must advance exactly once"
        }
        source.removeLast()
        destination.addLast(entry)
        headRevision = revision
    }
}
