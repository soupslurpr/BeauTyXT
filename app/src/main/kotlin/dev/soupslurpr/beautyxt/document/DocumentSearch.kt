package dev.soupslurpr.beautyxt.document

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

internal const val MAX_REPLACEMENT_PATCHES = 4096
internal const val MAX_REPLACEMENT_HISTORY_UNITS = 256 * 1024
internal const val MAX_SEARCH_RESULTS = 4096

internal data class SearchOptions(
    val regex: Boolean = false,
    val matchCase: Boolean = false,
    val wholeWord: Boolean = false
)

internal data class SearchCursor(val start: Long, val skipEmpty: Boolean = false)
internal enum class SearchCompletion { Complete, PageLimit, WorkLimit, ContextLimit, Cancelled }
internal data class SearchHit(
    val range: Utf16Range,
    val text: String,
    val replacement: String?,
    val before: String,
    val after: String
)
internal data class SearchPage(
    val revision: Long,
    val hits: List<SearchHit>,
    val completion: SearchCompletion,
    val next: SearchCursor
)

/** One compiled query; closing cooperatively cancels native work without a document lock. */
internal interface DocumentSearch : AutoCloseable {
    fun source(revision: Long, scope: Utf16Range, cursor: SearchCursor, replacement: String?): SearchPage
    fun text(text: String, scope: Utf16Range, cursor: SearchCursor): SearchPage
}

/** Exact original-revision patch shared by review, native commit, and Undo. */
internal data class DocumentPatch(val range: Utf16Range, val removed: String, val inserted: String) {
    init {
        require(range.end - range.start == removed.length.toLong())
        require(removed.hasWellFormedUtf16() && inserted.hasWellFormedUtf16())
        require('\r' !in inserted)
    }
    val retainedUnits: Int get() = Math.addExact(removed.length, inserted.length)
}

internal fun inversePatches(patches: List<DocumentPatch>): List<DocumentPatch> {
    var shift = 0L
    val inverse = patches.map { patch ->
        val start = Math.addExact(patch.range.start, shift)
        shift = Math.addExact(shift, patch.inserted.length.toLong() - patch.removed.length)
        DocumentPatch(Utf16Range(start, Math.addExact(start, patch.inserted.length.toLong())),
            patch.inserted, patch.removed)
    }
    // Adjacent deletions have the same inverse insertion point. Merge them
    // before publication, retaining their original left-to-right order.
    return buildList {
        inverse.forEach { patch ->
            val previous = lastOrNull()
            if (previous != null && previous.range.end == patch.range.start) {
                removeAt(lastIndex)
                add(DocumentPatch(Utf16Range(previous.range.start, patch.range.end),
                    previous.removed + patch.removed, previous.inserted + patch.inserted))
            } else add(patch)
        }
    }
}

internal fun encodeReplacementBatch(patches: List<DocumentPatch>): ByteArray {
    require(patches.size <= MAX_REPLACEMENT_PATCHES)
    require(patches.sumOf { it.retainedUnits.toLong() } <= MAX_REPLACEMENT_HISTORY_UNITS)
    val output = ByteArrayOutputStream()
    fun number(value: Long, bytes: Int) {
        repeat(bytes) { index -> output.write((value ushr (index * 8)).toInt() and 255) }
    }
    fun text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        number(bytes.size.toLong(), 4)
        output.write(bytes)
    }
    output.write(byteArrayOf(66, 69, 66, 84, 1, 0, 0, 0))
    number(patches.size.toLong(), 4)
    var previous: Utf16Range? = null
    patches.forEach { patch ->
        require(previous?.let { it.end <= patch.range.start && it.start < patch.range.start } != false)
        number(patch.range.start, 8)
        number(patch.range.end, 8)
        text(patch.removed)
        text(patch.inserted)
        previous = patch.range
    }
    return output.toByteArray()
}

/** Strictly decodes bounded text, coordinates, flags, and coverage from Rust. */
internal fun decodeSearchPage(packet: ByteArray): SearchPage {
    require(packet.size in 28..(2 * 1024 * 1024)) { "invalid search packet length" }
    val reader = DocumentPacketReader(packet) { IllegalArgumentException(it) }
    require(reader.readUnsignedInt("magic") == 0x52534542L)
    require(reader.readUnsignedShort("version") == 1)
    val completion = SearchCompletion.entries.getOrNull(reader.readUnsignedByte("completion"))
        ?: reader.reject("unknown search completion")
    val flags = reader.readUnsignedByte("flags")
    reader.requireAllowedFlags(flags, 3, "search flags")
    val revision = reader.readSupportedUnsignedLong("revision")
    val next = SearchCursor(reader.readSupportedUnsignedLong("cursor"), flags and 1 != 0)
    val count = reader.readUnsignedInt("count")
    require(count <= 256)
    var retained = 0L
    var previous = -1L
    fun text(): String = reader.readUtf8(reader.readUnsignedInt("text length"), "search text")
    val hits = List(count.toInt()) {
        val range = Utf16Range(reader.readSupportedUnsignedLong("start"), reader.readSupportedUnsignedLong("end"))
        val matched = text()
        val replacement = text()
        val before = text()
        val after = text()
        require(range.start > previous && range.end - range.start == matched.length.toLong())
        require(before.codePointCount(0, before.length) <= 40 && after.codePointCount(0, after.length) <= 40)
        require(flags and 2 != 0 || replacement.isEmpty())
        retained += matched.length.toLong() + replacement.length
        require(retained <= MAX_REPLACEMENT_HISTORY_UNITS)
        previous = range.start
        SearchHit(range, matched, replacement.takeIf { flags and 2 != 0 }, before, after)
    }
    require(reader.remainingBytes == 0)
    return SearchPage(revision, hits, completion, next)
}

/** Uses one Rust matcher for rendered semantic units and immutable source snapshots. */
internal class NativeSearch(
    query: String,
    options: SearchOptions,
    private val sourceSearch: (Long, Long, Utf16Range, SearchCursor, String?) -> SearchPage
) : DocumentSearch {
    private val handle = AtomicLong(NativeDocument.compileSearch(query, options.regex, options.matchCase, options.wholeWord))

    override fun source(revision: Long, scope: Utf16Range, cursor: SearchCursor, replacement: String?): SearchPage =
        sourceSearch(openHandle(), revision, scope, cursor, replacement)

    override fun text(text: String, scope: Utf16Range, cursor: SearchCursor): SearchPage =
        decodeSearchPage(NativeDocument.searchText(openHandle(), text, scope.start, scope.end, cursor.start, cursor.skipEmpty))

    private fun openHandle(): Long = handle.get().also { check(it > 0) { "search is closed" } }

    override fun close() {
        val previous = handle.getAndSet(0L)
        if (previous != 0L) NativeDocument.closeSearch(previous)
    }
}
