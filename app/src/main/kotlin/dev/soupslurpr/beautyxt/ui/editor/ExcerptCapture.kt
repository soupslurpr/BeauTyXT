package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.*
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Owns the selected logical text and frozen context needed to generate a formatted excerpt. */
internal class ExcerptCapture(
    val revision: Long,
    val plain: CapturedDocumentRevision,
    val exactSource: Boolean,
    val textFormat: DocumentFormat,
    private val readingDocument: MarkdownPreviewDocument?,
    private val readingSelection: DocumentSelection.Reading?,
    private val labelText: String? = null
) : AutoCloseable {
    val canFormat get() = readingSelection != null || labelText != null
    fun formatted(): FormattedExcerpt? = when {
        readingDocument != null && readingSelection != null -> selectedMarkdown(readingDocument, readingSelection)
        labelText != null -> FormattedExcerpt(excerptEscape(labelText), MarkdownPreviewDocument(
            labelText.toByteArray().size.toLong(), emptyList(), 0, false), emptyList())
        else -> null
    }
    override fun close() = plain.close()
}

/** Creates an independently owned excerpt tree with bounded native insertions and cancellation. */
internal suspend fun captureGeneratedExcerpt(text: String): CapturedDocumentRevision = RustDocument.createEmpty().use { document ->
    var revision = 0L
    var offset = 0
    while (offset < text.length) {
        currentCoroutineContext().ensureActive()
        var end = minOf(offset + 64 * 1024, text.length)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        revision = document.replace(revision, Utf16Range(offset.toLong(), offset.toLong()), text.substring(offset, end)).revision
        offset = end
    }
    val snapshot = document.captureSnapshot(revision)
    try { CapturedDocumentRevision(snapshot.metrics(), snapshot) }
    catch (failure: Throwable) { snapshot.close(); throw failure }
}

/** A source selection offers formatting only when both endpoints identify the exact visible passage. */
internal fun readingSelectionForSource(document: MarkdownPreviewDocument, selection: DocumentSelection.Source): DocumentSelection.Reading? {
    fun point(offset: Long, end: Boolean): ReadingPoint? {
        val candidates = ArrayList<ReadingPoint>()
        document.blocks.forEachIndexed { index, block ->
            block.sourceMaps.forEach { map ->
                val position = when {
                    offset == map.source.start -> map.renderedStart
                    offset == map.source.end -> map.renderedEnd
                    offset > map.source.start && offset < map.source.end &&
                        map.source.end - map.source.start == (map.renderedEnd - map.renderedStart).toLong() ->
                        map.renderedStart + (offset - map.source.start).toInt()
                    else -> null
                }
                if (position != null) candidates += ReadingPoint(index, position)
            }
        }
        return if (end) candidates.minOrNull() else candidates.maxOrNull()
    }
    val start = point(selection.range.start, false) ?: return null
    val end = point(selection.range.end, true) ?: return null
    if (start >= end || atomicReadingPoint(document, start, false) != start || atomicReadingPoint(document, end, true) != end) return null
    return if (selection.anchor <= selection.focus) DocumentSelection.Reading(selection.revision, start, end)
        else DocumentSelection.Reading(selection.revision, end, start)
}
