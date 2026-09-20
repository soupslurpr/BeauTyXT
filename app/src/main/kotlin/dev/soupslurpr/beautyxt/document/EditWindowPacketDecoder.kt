package dev.soupslurpr.beautyxt.document

private const val EDIT_WINDOW_PACKET_MAGIC = 0x5457_4542L
private const val EDIT_WINDOW_PACKET_VERSION = 3
private const val EDIT_WINDOW_HEADER_BYTES = 128
private const val EDIT_WINDOW_HAS_PREVIOUS = 1
private const val EDIT_WINDOW_HAS_NEXT = 1 shl 1
private const val EDIT_WINDOW_ALLOWED_FLAGS =
    EDIT_WINDOW_HAS_PREVIOUS or EDIT_WINDOW_HAS_NEXT
private const val EDIT_WINDOW_RESERVED_BYTES = 4

/** Contains one immutable bounded window for IME editing. */
internal data class EditWindowSnapshot(
    val metrics: DocumentMetrics,
    val range: Utf16Range,
    val selection: Utf16Range,
    val start: ViewportCursor,
    val text: String,
    val hasPrevious: Boolean,
    val hasNext: Boolean
)

/** Reports malformed or unsupported edit-window data from the native bridge. */
internal class EditWindowProtocolException(message: String) : IllegalArgumentException(message)

/** Decodes and validates versioned edit-window packets from the Rust bridge. */
internal object EditWindowPacketDecoder {
    /** Decodes one complete version 3 little-endian edit-window packet. */
    fun decode(packet: ByteArray): EditWindowSnapshot {
        if (packet.size < EDIT_WINDOW_HEADER_BYTES) {
            throw EditWindowProtocolException("edit-window packet is shorter than its header")
        }

        val reader = DocumentPacketReader(packet, ::EditWindowProtocolException)
        val magic = reader.readUnsignedInt("packet magic")
        if (magic != EDIT_WINDOW_PACKET_MAGIC) {
            throw EditWindowProtocolException("edit-window packet has invalid magic")
        }
        val version = reader.readUnsignedShort("packet version")
        if (version != EDIT_WINDOW_PACKET_VERSION) {
            throw EditWindowProtocolException("unsupported edit-window packet version $version")
        }

        val flags = reader.readUnsignedShort("edit-window flags")
        reader.requireAllowedFlags(flags, EDIT_WINDOW_ALLOWED_FLAGS, "edit-window flags")
        val metrics = decodeDocumentMetrics(reader)
        val rangeStart = reader.readSupportedUnsignedLong("edit-window start")
        val rangeEnd = reader.readSupportedUnsignedLong("edit-window end")
        if (rangeEnd < rangeStart) {
            throw EditWindowProtocolException("edit-window end precedes its start")
        }
        val range = Utf16Range(start = rangeStart, end = rangeEnd)
        val selectionStart = reader.readSupportedUnsignedLong("selection start")
        val selectionEnd = reader.readSupportedUnsignedLong("selection end")
        if (selectionEnd < selectionStart) {
            throw EditWindowProtocolException("selection end precedes its start")
        }
        val selection = Utf16Range(start = selectionStart, end = selectionEnd)
        val start =
            ViewportCursor(
                revision = metrics.revision,
                line = reader.readSupportedUnsignedLong("edit-window start line"),
                utf16Offset =
                    reader.readSupportedUnsignedLong("edit-window start utf-16 offset")
            )
        val textByteLength = reader.readUnsignedInt("edit-window byte length")
        reader.requireZeroBytes(EDIT_WINDOW_RESERVED_BYTES, "edit-window reserved bytes")
        val text = reader.readUtf8(textByteLength, "edit-window text")

        if (reader.remainingBytes != 0) {
            throw EditWindowProtocolException("edit-window packet has trailing bytes")
        }
        validateSnapshot(flags, metrics, range, selection, start, text)
        return EditWindowSnapshot(
            metrics = metrics,
            range = range,
            selection = selection,
            start = start,
            text = text,
            hasPrevious = flags and EDIT_WINDOW_HAS_PREVIOUS != 0,
            hasNext = flags and EDIT_WINDOW_HAS_NEXT != 0
        )
    }

    /** Validates cross-field edit-window invariants. */
    private fun validateSnapshot(
        flags: Int,
        metrics: DocumentMetrics,
        range: Utf16Range,
        selection: Utf16Range,
        start: ViewportCursor,
        text: String
    ) {
        if (range.end > metrics.utf16Length) {
            throw EditWindowProtocolException("edit-window range exceeds the document")
        }
        if (range.end - range.start != text.length.toLong()) {
            throw EditWindowProtocolException("edit-window text conflicts with its global range")
        }
        if (text.length > MAX_EDIT_WINDOW_UTF16_UNITS) {
            throw EditWindowProtocolException("edit-window text exceeds the protocol limit")
        }
        if (selection.start < range.start || selection.end > range.end) {
            throw EditWindowProtocolException("selection exceeds the edit-window range")
        }
        if (!text.isScalarBoundary((selection.start - range.start).toInt())) {
            throw EditWindowProtocolException("selection start divides a surrogate pair")
        }
        if (!text.isScalarBoundary((selection.end - range.start).toInt())) {
            throw EditWindowProtocolException("selection end divides a surrogate pair")
        }
        if ('\r' in text) {
            throw EditWindowProtocolException("edit-window text is not normalized")
        }
        if (start.line >= metrics.lineCount) {
            throw EditWindowProtocolException("edit-window start line exceeds the document")
        }
        if (start.line == 0L && start.utf16Offset != range.start) {
            throw EditWindowProtocolException(
                "edit-window start position conflicts with its global offset"
            )
        }
        if (start.utf16Offset > range.start) {
            throw EditWindowProtocolException("edit-window start offset exceeds its global offset")
        }
        val lastWindowLine = start.line + text.count { character -> character == '\n' }
        if (lastWindowLine < start.line || lastWindowLine >= metrics.lineCount) {
            throw EditWindowProtocolException("edit-window text exceeds the document lines")
        }

        val hasPrevious = flags and EDIT_WINDOW_HAS_PREVIOUS != 0
        if (hasPrevious != (range.start > 0L)) {
            throw EditWindowProtocolException("previous flag conflicts with the edit-window range")
        }
        val hasNext = flags and EDIT_WINDOW_HAS_NEXT != 0
        if (hasNext != (range.end < metrics.utf16Length)) {
            throw EditWindowProtocolException("next flag conflicts with the edit-window range")
        }
    }
}
