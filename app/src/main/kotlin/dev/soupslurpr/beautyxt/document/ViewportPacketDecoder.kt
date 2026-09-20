package dev.soupslurpr.beautyxt.document

private const val VIEWPORT_PACKET_MAGIC = 0x5458_5442L
private const val VIEWPORT_PACKET_VERSION = 4
private const val VIEWPORT_HEADER_BYTES = 108
private const val RENDER_BLOCK_HEADER_BYTES = 32
private const val SNAPSHOT_HAS_NEXT = 1
private const val SNAPSHOT_HAS_PREVIOUS = 1 shl 1
private const val SNAPSHOT_ALLOWED_FLAGS = SNAPSHOT_HAS_NEXT or SNAPSHOT_HAS_PREVIOUS
private const val BLOCK_CONTINUES_AT_START = 1
private const val BLOCK_CONTINUES_AT_END = 1 shl 1
private const val BLOCK_ALLOWED_FLAGS =
    BLOCK_CONTINUES_AT_START or BLOCK_CONTINUES_AT_END
private const val BLOCK_RESERVED_BYTES = 2
private const val NORMALIZED_LINE_TERMINATOR_UTF16_UNITS = 1

/** Contains one bounded, line-aligned piece of document text. */
internal data class RenderBlock(
    val logicalLine: Long,
    val globalUtf16Start: Long,
    val globalUtf16End: Long,
    val lineTerminatorUtf16Units: Int,
    val text: String,
    val continuesAtStart: Boolean,
    val continuesAtEnd: Boolean
)

/** Contains one immutable viewport and its revision-bound continuation. */
internal data class ViewportSnapshot(
    val metrics: DocumentMetrics,
    val blocks: List<RenderBlock>,
    val previous: ViewportCursor?,
    val next: ViewportCursor?
)

/** Reports malformed or unsupported data returned by the native bridge. */
internal class ViewportProtocolException(message: String) : IllegalArgumentException(message)

/** Decodes and validates versioned viewport packets from the Rust bridge. */
internal object ViewportPacketDecoder {
    /** Decodes one complete version 4 little-endian viewport packet. */
    fun decode(packet: ByteArray): ViewportSnapshot {
        if (packet.size < VIEWPORT_HEADER_BYTES) {
            throw ViewportProtocolException("viewport packet is shorter than its header")
        }

        val reader = DocumentPacketReader(packet, ::ViewportProtocolException)
        val magic = reader.readUnsignedInt("packet magic")
        if (magic != VIEWPORT_PACKET_MAGIC) {
            throw ViewportProtocolException("viewport packet has invalid magic")
        }

        val version = reader.readUnsignedShort("packet version")
        if (version != VIEWPORT_PACKET_VERSION) {
            throw ViewportProtocolException("unsupported viewport packet version $version")
        }

        val snapshotFlags = reader.readUnsignedShort("snapshot flags")
        reader.requireAllowedFlags(snapshotFlags, SNAPSHOT_ALLOWED_FLAGS, "snapshot flags")
        val metrics = decodeDocumentMetrics(reader)
        val previousLine = reader.readSupportedUnsignedLong("previous line")
        val previousUtf16Offset = reader.readSupportedUnsignedLong("previous utf-16 offset")
        val previous =
            decodeCursor(
                flags = snapshotFlags,
                cursorFlag = SNAPSHOT_HAS_PREVIOUS,
                revision = metrics.revision,
                line = previousLine,
                utf16Offset = previousUtf16Offset,
                lineCount = metrics.lineCount,
                documentUtf16Length = metrics.utf16Length,
                name = "previous"
            )
        val nextLine = reader.readSupportedUnsignedLong("next line")
        val nextUtf16Offset = reader.readSupportedUnsignedLong("next utf-16 offset")
        val next =
            decodeCursor(
                flags = snapshotFlags,
                cursorFlag = SNAPSHOT_HAS_NEXT,
                revision = metrics.revision,
                line = nextLine,
                utf16Offset = nextUtf16Offset,
                lineCount = metrics.lineCount,
                documentUtf16Length = metrics.utf16Length,
                name = "next"
            )

        val blockCount = reader.readUnsignedInt("block count")
        val maximumBlockCount = reader.remainingBytes.toLong() / RENDER_BLOCK_HEADER_BYTES
        if (blockCount > maximumBlockCount) {
            throw ViewportProtocolException("block count exceeds the packet bounds")
        }

        val blocks = ArrayList<RenderBlock>(blockCount.toInt())
        repeat(blockCount.toInt()) {
            blocks += decodeRenderBlock(reader, metrics)
        }
        validateBlockSequence(blocks)

        if (reader.remainingBytes != 0) {
            throw ViewportProtocolException("viewport packet has trailing bytes")
        }
        return ViewportSnapshot(
            metrics = metrics,
            blocks = blocks,
            previous = previous,
            next = next
        )
    }

    /** Creates one revision-bound cursor when the packet declares it present. */
    private fun decodeCursor(
        flags: Int,
        cursorFlag: Int,
        revision: Long,
        line: Long,
        utf16Offset: Long,
        lineCount: Long,
        documentUtf16Length: Long,
        name: String
    ): ViewportCursor? {
        require(name == "previous" || name == "next") { "cursor name is invalid" }
        if (flags and cursorFlag == 0) {
            if (line != 0L || utf16Offset != 0L) {
                throw ViewportProtocolException("absent $name cursor contains nonzero fields")
            }
            return null
        }
        if (line >= lineCount) {
            throw ViewportProtocolException("$name cursor line exceeds the document")
        }
        if (utf16Offset > documentUtf16Length) {
            throw ViewportProtocolException("$name cursor offset exceeds the document")
        }
        return ViewportCursor(revision = revision, line = line, utf16Offset = utf16Offset)
    }

    /** Decodes and validates one render block and its UTF-8 payload. */
    private fun decodeRenderBlock(
        reader: DocumentPacketReader,
        metrics: DocumentMetrics
    ): RenderBlock {
        val logicalLine = reader.readSupportedUnsignedLong("block logical line")
        val globalUtf16Start = reader.readSupportedUnsignedLong("block global utf-16 start")
        val globalUtf16End = reader.readSupportedUnsignedLong("block global utf-16 end")
        val blockFlags = reader.readUnsignedByte("block flags")
        reader.requireAllowedFlags(blockFlags, BLOCK_ALLOWED_FLAGS, "block flags")
        val lineTerminatorUtf16Units =
            reader.readUnsignedByte("line terminator utf-16 units")
        reader.requireZeroBytes(BLOCK_RESERVED_BYTES, "block reserved bytes")
        val textByteLength = reader.readUnsignedInt("block byte length")
        val text = reader.readUtf8(textByteLength, "block text")
        val continuesAtStart = blockFlags and BLOCK_CONTINUES_AT_START != 0
        val continuesAtEnd = blockFlags and BLOCK_CONTINUES_AT_END != 0

        if (logicalLine >= metrics.lineCount) {
            throw ViewportProtocolException("block logical line exceeds the document")
        }
        if (globalUtf16End < globalUtf16Start) {
            throw ViewportProtocolException("block utf-16 end precedes its start")
        }
        if (globalUtf16End - globalUtf16Start != text.length.toLong()) {
            throw ViewportProtocolException("block text conflicts with its global utf-16 range")
        }
        if (globalUtf16End > metrics.utf16Length) {
            throw ViewportProtocolException("block utf-16 range exceeds the document")
        }
        if (lineTerminatorUtf16Units !in 0..NORMALIZED_LINE_TERMINATOR_UTF16_UNITS) {
            throw ViewportProtocolException("block has an unsupported line terminator length")
        }
        if (continuesAtEnd && lineTerminatorUtf16Units != 0) {
            throw ViewportProtocolException("continued block contains a line terminator")
        }
        if (text.isEmpty() && (continuesAtStart || continuesAtEnd)) {
            throw ViewportProtocolException("empty block contains continuation flags")
        }
        if ('\n' in text || '\r' in text) {
            throw ViewportProtocolException("block text contains a line terminator")
        }
        if (
            lineTerminatorUtf16Units.toLong() >
            metrics.utf16Length - globalUtf16End
        ) {
            throw ViewportProtocolException("block line terminator exceeds the document")
        }

        val expectedLineTerminator =
            if (!continuesAtEnd && logicalLine + 1 < metrics.lineCount) {
                NORMALIZED_LINE_TERMINATOR_UTF16_UNITS
            } else {
                0
            }
        if (lineTerminatorUtf16Units != expectedLineTerminator) {
            throw ViewportProtocolException("block line terminator conflicts with its line")
        }
        return RenderBlock(
            logicalLine = logicalLine,
            globalUtf16Start = globalUtf16Start,
            globalUtf16End = globalUtf16End,
            lineTerminatorUtf16Units = lineTerminatorUtf16Units,
            text = text,
            continuesAtStart = continuesAtStart,
            continuesAtEnd = continuesAtEnd
        )
    }

    /** Validates that render blocks form one contiguous viewport sequence. */
    private fun validateBlockSequence(blocks: List<RenderBlock>) {
        var currentBlockIndex = 1
        while (currentBlockIndex < blocks.size) {
            val previous = blocks[currentBlockIndex - 1]
            val current = blocks[currentBlockIndex]
            if (current.logicalLine == previous.logicalLine) {
                if (!previous.continuesAtEnd || !current.continuesAtStart) {
                    throw ViewportProtocolException(
                        "same-line blocks have invalid continuation flags"
                    )
                }
                if (current.globalUtf16Start != previous.globalUtf16End) {
                    throw ViewportProtocolException(
                        "same-line blocks have a discontinuous utf-16 range"
                    )
                }
            } else {
                if (current.logicalLine != previous.logicalLine + 1) {
                    throw ViewportProtocolException("block logical lines are not contiguous")
                }
                if (previous.continuesAtEnd || current.continuesAtStart) {
                    throw ViewportProtocolException(
                        "adjacent lines have invalid continuation flags"
                    )
                }
                val expectedGlobalStart =
                    previous.globalUtf16End + previous.lineTerminatorUtf16Units
                if (current.globalUtf16Start != expectedGlobalStart) {
                    throw ViewportProtocolException(
                        "adjacent lines have a discontinuous utf-16 range"
                    )
                }
            }
            currentBlockIndex += 1
        }
    }
}
