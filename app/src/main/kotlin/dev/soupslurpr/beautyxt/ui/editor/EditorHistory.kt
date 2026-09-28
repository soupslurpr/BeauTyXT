package dev.soupslurpr.beautyxt.ui.editor

/** Retains only selection/coordinate metadata; native history owns text and eviction. */
internal class EditorHistory(private val maxEntries: Int = 128) {
    init { require(maxEntries > 0) }
    private val undoEntries = ArrayDeque<CommittedEditDelta>()
    private val redoEntries = ArrayDeque<CommittedEditDelta>()
    var headRevision: Long? = null
        private set
    val undoEntry: CommittedEditDelta? get() = undoEntries.lastOrNull()
    val redoEntry: CommittedEditDelta? get() = redoEntries.lastOrNull()

    /** Mirrors native eviction only after the edit and its Undo commit together. */
    fun record(delta: CommittedEditDelta, oldestNativeUndo: Long = 0) {
        if (headRevision != null && headRevision != delta.revisionBefore) clear()
        redoEntries.clear()
        while (undoEntries.isNotEmpty() &&
            (undoEntries.size >= maxEntries || undoEntries.first().revisionAfter < oldestNativeUndo)) {
            undoEntries.removeFirst()
        }
        undoEntries.addLast(delta)
        headRevision = delta.revisionAfter
    }

    fun completeUndo(entry: CommittedEditDelta, revision: Long) =
        moveAppliedEntry(undoEntries, redoEntries, entry, revision)

    fun completeRedo(entry: CommittedEditDelta, revision: Long) =
        moveAppliedEntry(redoEntries, undoEntries, entry, revision)

    fun clear(revision: Long? = null) {
        require(revision == null || revision >= 0)
        undoEntries.clear()
        redoEntries.clear()
        headRevision = revision
    }

    private fun moveAppliedEntry(source: ArrayDeque<CommittedEditDelta>,
        destination: ArrayDeque<CommittedEditDelta>, entry: CommittedEditDelta, revision: Long) {
        check(source.lastOrNull() === entry) { "history changed while its native edit was running" }
        require(revision == Math.incrementExact(checkNotNull(headRevision)))
        source.removeLast()
        destination.addLast(entry)
        headRevision = revision
    }
}
