package dev.soupslurpr.beautyxt.markdown

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEST_INPUT_BYTES = 14L
private const val TEST_PACKET_BYTES_OFFSET = 32
private const val TEST_DOCUMENT_FLAGS_OFFSET = 16
private const val TEST_BLOCK_COUNT_OFFSET = 40
private const val TEST_SPAN_COUNT_OFFSET = 44
private const val TEST_TEXT_BYTES_OFFSET = 48
private const val TEST_AUXILIARY_BYTES_OFFSET = 56
private const val TEST_SOURCE_MAP_COUNT_OFFSET = 72
private const val TEST_BLOCK_FLAGS_OFFSET = 84
private const val TEST_BLOCK_TEXT_OFFSET = 152
private const val TEST_SPAN_END_OFFSET = 166
private const val TEST_SOURCE_MAP_RENDERED_END_OFFSET = 190
private const val TEST_SOURCE_MAP_SOURCE_END_OFFSET = 202
private const val TEST_PACKET_VERSION = 6L
private const val TEST_HEADER_BYTES = 80L
private const val TEST_BLOCK_HEADER_BYTES = 72L
private const val TEST_SPAN_HEADER_BYTES = 24L
private const val TEST_SOURCE_MAP_HEADER_BYTES = 24L
private const val TEST_TABLE_START_FLAG = 1L shl 12

/** Verifies strict decoding of the isolated Markdown render format. */
class MarkdownPacketDecoderTest {
    @Test
    fun rejectsOldVersionsAndInvalidAlertOwnerDepths() {
        val legacyMagic = createHeadingPacket()
        legacyMagic[7] = '5'.code.toByte()
        assertProtocolFailure("Markdown packet has invalid magic", legacyMagic)
        val legacyVersion = createHeadingPacket()
        legacyVersion.writeLittleEndianInt(8, 5)
        assertProtocolFailure("unsupported Markdown packet version", legacyVersion)
        val alert = TestBlock(MarkdownBlockKind.Paragraph, flags = 1L shl 6,
            quoteDepth = 2, text = "deeper", quoteAlertDepth = 1)
        for (depth in listOf(0L, 3L, 33L, 0xffff_ffffL)) {
            assertProtocolFailure("Markdown alert owner depth is invalid",
                createSingleBlockPacket(alert.copy(quoteAlertDepth = depth)))
        }
        assertProtocolFailure("Markdown alert owner depth is invalid",
            createSingleBlockPacket(alert.copy(flags = 0)))
        val max = MarkdownPacketDecoder.decode(createSingleBlockPacket(alert.copy(
            quoteDepth = 32, quoteAlertDepth = 32))).blocks.single()
        assertEquals(32, max.quoteAlertDepth)
        assertEquals(0, MarkdownPacketDecoder.decode(createHeadingPacket()).blocks.single().quoteAlertDepth)
    }

    @Test
    fun distinguishesLiteralTabsAndValidatesStructuralSeparators() {
        val table = TestBlock(MarkdownBlockKind.TableRow, text = "a\tb\tc", metadata = "nr", separators = listOf(3))
        val decoded = MarkdownPacketDecoder.decode(createSingleBlockPacket(table)).blocks.single()
        assertEquals(listOf(3), decoded.tableCellSeparators)
        assertEquals(listOf(MarkdownTableCellRange(0, 3), MarkdownTableCellRange(4, 5)), decoded.markdownTableCellRanges())
        for (offsets in listOf(listOf(0), listOf(5), listOf(3, 3), listOf(3, 1))) {
            assertProtocolFailure("Markdown table separator is invalid",
                createSingleBlockPacket(table.copy(separators = offsets)))
        }
        assertProtocolFailure("Markdown table alignment count is inconsistent",
            createSingleBlockPacket(table.copy(separators = emptyList())))
        assertProtocolFailure("Markdown table separator count is invalid",
            createSingleBlockPacket(table.copy(kind = MarkdownBlockKind.Paragraph)))
    }

    @Test
    fun retainsFootnoteOwnershipWithoutChangingCodeMetadata() {
        val block = MarkdownPacketDecoder.decode(createSingleBlockPacket(TestBlock(
            MarkdownBlockKind.Code, text = "exact code", metadata = "rust", footnoteLabel = "note"))).blocks.single()
        assertEquals(MarkdownBlockKind.Code, block.kind)
        assertEquals("rust", block.metadata)
        assertEquals("note", block.footnoteLabel)
        assertProtocolFailure("Markdown footnote owner is inconsistent", createSingleBlockPacket(TestBlock(
            MarkdownBlockKind.Footnote, text = "definition", metadata = "note", footnoteLabel = null)))
    }

    @Test
    fun permitsUnmappedPresentationButRejectsOverlappingOrEmptySourceMaps() {
        val partial = createHeadingPacket()
        partial.writeLittleEndianInt(TEST_SOURCE_MAP_RENDERED_END_OFFSET - 4, 2)
        assertEquals(2, MarkdownPacketDecoder.decode(partial).blocks.single().sourceMaps.single().renderedStart)
        val empty = createHeadingPacket()
        empty.writeLittleEndianLong(TEST_SOURCE_MAP_SOURCE_END_OFFSET, 0)
        assertProtocolFailure("Markdown source-map source range is invalid", empty)
        val reversed = createHeadingPacket()
        reversed.writeLittleEndianInt(TEST_SOURCE_MAP_RENDERED_END_OFFSET - 4, 8)
        assertProtocolFailure("Markdown source-map rendered range is invalid", reversed)
        val heading = createHeadingPacket()
        val overlap = heading + heading.copyOfRange(heading.size - TEST_SOURCE_MAP_HEADER_BYTES.toInt(), heading.size)
        overlap.writeLittleEndianLong(TEST_PACKET_BYTES_OFFSET, overlap.size.toLong())
        overlap.writeLittleEndianInt(TEST_SOURCE_MAP_COUNT_OFFSET, 2)
        overlap.writeLittleEndianInt(TEST_HEADER_BYTES.toInt() + 64, 2)
        assertProtocolFailure("Markdown source-map rendered range is invalid", overlap)
    }

    /** Decodes one Unicode heading and its strong inline range. */
    @Test
    fun decodesValidHeadingPacket() {
        val document = MarkdownPacketDecoder.decode(createHeadingPacket())

        assertEquals(TEST_INPUT_BYTES, document.inputByteLength)
        assertEquals(8L, document.inputUtf16Length)
        assertEquals(1, document.blocks.size)
        assertEquals(1, document.spanCount)
        assertEquals(1, document.sourceMapCount)
        assertFalse(document.containsRawHtml)
        val block = document.blocks.single()
        assertEquals(MarkdownBlockKind.Heading, block.kind)
        assertEquals(1, block.headingLevel)
        assertEquals("Hello 😀", block.text)
        assertEquals(
            MarkdownInlineSpan(
                start = 0,
                end = 5,
                styles = MARKDOWN_SPAN_STYLE_STRONG,
                destination = null
            ),
            block.spans.single()
        )
        assertNull(block.spans.single().destination)
        assertEquals(MarkdownSourceRange(0, 8), block.source)
        assertEquals(
            MarkdownSourceMap(
                renderedStart = 0,
                renderedEnd = 8,
                source = MarkdownSourceRange(0, 8)
            ),
            block.sourceMaps.single()
        )
    }

    /** Rejects aggregate sizes and document flags that disagree with content. */
    @Test
    fun rejectsInconsistentHeaderMetrics() {
        val sizePacket = createHeadingPacket()
        sizePacket.writeLittleEndianLong(
            TEST_PACKET_BYTES_OFFSET,
            sizePacket.size.toLong() + 1L
        )
        assertProtocolFailure(
            "Markdown packet byte count is inconsistent",
            sizePacket
        )

        val rawHtmlPacket = createHeadingPacket()
        rawHtmlPacket.writeLittleEndianInt(TEST_DOCUMENT_FLAGS_OFFSET, 1L)
        assertProtocolFailure(
            "Markdown raw HTML flag is inconsistent",
            rawHtmlPacket
        )
    }

    /** Rejects span allocations as soon as they exceed the remaining aggregate. */
    @Test
    fun rejectsSpanCountsBeforeDecodingAnotherBlock() {
        assertProtocolFailure(
            "Markdown block span count exceeds the remaining aggregate",
            createRepeatedHeadingPacket(spanCount = 1L, sourceMapCount = 2L)
        )
    }

    /** Rejects source-map allocations before decoding an over-budget block. */
    @Test
    fun rejectsSourceMapCountsBeforeDecodingAnotherBlock() {
        assertProtocolFailure(
            "Markdown block source-map count exceeds the remaining aggregate",
            createRepeatedHeadingPacket(spanCount = 2L, sourceMapCount = 1L)
        )
    }

    /** Rejects undefined block flags and malformed UTF-8 payloads. */
    @Test
    fun rejectsUnsupportedAndMalformedBlockData() {
        val flagPacket = createHeadingPacket()
        flagPacket.writeLittleEndianInt(TEST_BLOCK_FLAGS_OFFSET, 1L shl 15)
        assertProtocolFailure(
            "block flags contains unsupported bits",
            flagPacket
        )

        val utf8Packet = createHeadingPacket()
        utf8Packet[TEST_BLOCK_TEXT_OFFSET] = 0xc0.toByte()
        assertProtocolFailure(
            "block text is not valid UTF-8",
            utf8Packet
        )
    }

    /** Rejects a styled range that divides a supplementary Unicode scalar. */
    @Test
    fun rejectsSpanThatDividesUnicodeCharacter() {
        val packet = createHeadingPacket()
        packet.writeLittleEndianInt(TEST_SPAN_END_OFFSET, 7L)

        assertProtocolFailure(
            "Markdown span divides a Unicode character",
            packet
        )
    }

    /** Rejects rendered and source ranges that escape their validated bounds. */
    @Test
    fun rejectsInvalidSourceMapRanges() {
        val renderedPacket = createHeadingPacket()
        renderedPacket.writeLittleEndianInt(TEST_SOURCE_MAP_RENDERED_END_OFFSET, 7L)
        assertProtocolFailure(
            "Markdown source map divides a Unicode character",
            renderedPacket
        )

        val sourcePacket = createHeadingPacket()
        sourcePacket.writeLittleEndianLong(TEST_SOURCE_MAP_SOURCE_END_OFFSET, 9L)
        assertProtocolFailure(
            "Markdown source-map source range is invalid",
            sourcePacket
        )
    }

    /** Rejects worker output containing a text block unsafe for Compose layout. */
    @Test
    fun rejectsBlockBeyondThePresentationLimit() {
        val packet =
            createPlainTextPacket(
                "x".repeat(MarkdownProtocol.MAX_BLOCK_TEXT_BYTES.toInt() + 1)
            )

        assertProtocolFailure("Markdown block text exceeds its limit", packet)
    }

    /** Decodes alert identity and exact table alignment metadata. */
    @Test
    fun decodesExtendedBlockSemantics() {
        val alert =
            MarkdownPacketDecoder.decode(
                createSingleBlockPacket(
                    TestBlock(
                        kind = MarkdownBlockKind.Paragraph,
                        flags = (1L shl 6) or (1L shl 11),
                        quoteDepth = 2L,
                        quoteAlertDepth = 1L,
                        text = "Remember this"
                    )
                )
            ).blocks.single()
        assertEquals(MarkdownQuoteKind.Note, alert.quoteKind)
        assertTrue(alert.startsQuoteAlert)
        assertEquals(1, alert.quoteAlertDepth)

        val table =
            MarkdownPacketDecoder.decode(
                createSingleBlockPacket(
                    TestBlock(
                        kind = MarkdownBlockKind.TableRow,
                        flags = TEST_TABLE_START_FLAG,
                        text = "left\tcenter\tright",
                        metadata = "lcr"
                    )
                )
            ).blocks.single()
        assertEquals(
            listOf(
                MarkdownTableAlignment.Left,
                MarkdownTableAlignment.Center,
                MarkdownTableAlignment.Right
            ),
            table.tableAlignments
        )
        assertTrue(table.startsTable)
    }

    /** A later list paragraph is distinct from a bounded fragment of its predecessor. */
    @Test
    fun decodesListParagraphContinuation() {
        val block = MarkdownPacketDecoder.decode(
            createSingleBlockPacket(
                TestBlock(
                    MarkdownBlockKind.ListItem,
                    flags = (1L shl 13) or (1L shl 1),
                    text = "Another paragraph."
                )
            )
        ).blocks.single()
        assertTrue(block.continuesListItem)
        assertTrue(block.isOrderedListItem)
        assertFalse(block.continuesPrevious)
        assertEquals(0L, block.listNumber)
    }

    /** The extra paragraph cannot invent a task marker or appear outside a list. */
    @Test
    fun rejectsConflictingListParagraphContinuation() {
        for (block in listOf(
            TestBlock(MarkdownBlockKind.Paragraph, flags = 1L shl 13, text = "text"),
            TestBlock(
                MarkdownBlockKind.ListItem,
                flags = (1L shl 13) or (1L shl 2),
                text = "☑ text"
            )
        )) {
            assertProtocolFailure(
                "Markdown list continuation has conflicting fields",
                createSingleBlockPacket(block)
            )
        }
    }

    /** Rejects table starts attached to another kind or a continuation chunk. */
    @Test
    fun rejectsConflictingTableStarts() {
        for (block in listOf(
            TestBlock(MarkdownBlockKind.Paragraph, TEST_TABLE_START_FLAG, text = "text"),
            TestBlock(
                MarkdownBlockKind.TableRow,
                TEST_TABLE_START_FLAG or 1L,
                text = "cell",
                metadata = "n"
            )
        )) {
            assertProtocolFailure(
                "Markdown table start conflicts with its block",
                createSingleBlockPacket(block)
            )
        }
    }

    /** Decodes a local footnote target without classifying it as an external link. */
    @Test
    fun decodesFootnoteReferenceDestination() {
        val span =
            MarkdownPacketDecoder.decode(
                createSingleBlockPacket(
                    TestBlock(
                        kind = MarkdownBlockKind.Paragraph,
                        text = "[^note]",
                        spans =
                            listOf(
                                TestSpan(
                                    start = 0L,
                                    end = 7L,
                                    styles =
                                        MARKDOWN_SPAN_STYLE_CODE.toLong() or
                                            MARKDOWN_SPAN_STYLE_FOOTNOTE_REFERENCE.toLong(),
                                    flags = 1L shl 1,
                                    destination = "note"
                                )
                            )
                    )
                )
            ).blocks.single().spans.single()

        assertEquals("note", span.destination)
        assertEquals(
            MarkdownInlineDestinationKind.FootnoteReference,
            span.destinationKind
        )
    }

    /** Keeps an empty ordinary link typed so policy can report it as unavailable. */
    @Test
    fun decodesEmptyOrdinaryLinkDestination() {
        val span =
            MarkdownPacketDecoder.decode(
                createSingleBlockPacket(
                    TestBlock(
                        kind = MarkdownBlockKind.Paragraph,
                        text = "empty",
                        spans =
                            listOf(
                                TestSpan(
                                    start = 0L,
                                    end = 5L,
                                    styles = 0L,
                                    flags = 1L,
                                    destination = ""
                                )
                            )
                    )
                )
            ).blocks.single().spans.single()

        assertEquals("", span.destination)
        assertEquals(MarkdownInlineDestinationKind.Link, span.destinationKind)
    }

    /** Rejects task flags whose marker would violate the presentation contract. */
    @Test
    fun rejectsMissingTaskMarkers() {
        assertProtocolFailure(
            "Markdown task marker is inconsistent",
            createSingleBlockPacket(
                TestBlock(MarkdownBlockKind.ListItem, flags = 1L shl 2, text = "task")
            )
        )
    }

    /** Rejects inline styles over the synthetic marker removed by Compose. */
    @Test
    fun rejectsStyledTaskMarkers() {
        assertProtocolFailure(
            "Markdown task marker contains inline styling",
            createSingleBlockPacket(
                TestBlock(
                    MarkdownBlockKind.ListItem,
                    flags = 1L shl 3,
                    text = "☐ task",
                    spans = listOf(TestSpan(0, 1, 1, 0, ""))
                )
            )
        )
    }

    /** Accepts both canonical task states with styling confined to task content. */
    @Test
    fun acceptsCanonicalTaskMarkers() {
        for ((flag, text) in listOf((1L shl 2) to "☑ task", (1L shl 3) to "☐ task")) {
            val block = MarkdownPacketDecoder.decode(
                createSingleBlockPacket(
                    TestBlock(
                        MarkdownBlockKind.ListItem,
                        flags = flag,
                        text = text,
                        spans = listOf(TestSpan(2, 6, 1, 0, ""))
                    )
                )
            ).blocks.single()
            assertEquals(text, block.text)
            assertEquals(2, block.spans.single().start)
        }
    }

    /** Creates one deterministic packet matching the Rust version-six encoder. */
    private fun createHeadingPacket(): ByteArray {
        val textBytes = "Hello 😀".toByteArray(Charsets.UTF_8)
        val packetBytes =
            TEST_HEADER_BYTES +
                TEST_BLOCK_HEADER_BYTES +
                textBytes.size +
                TEST_SPAN_HEADER_BYTES +
                TEST_SOURCE_MAP_HEADER_BYTES
        return ByteArrayOutputStream().apply {
            write("BTXTMDR6".toByteArray(Charsets.US_ASCII))
            writeLittleEndianInt(TEST_PACKET_VERSION)
            writeLittleEndianInt(TEST_HEADER_BYTES)
            writeLittleEndianInt(0)
            writeLittleEndianInt(0)
            writeLittleEndianLong(TEST_INPUT_BYTES)
            writeLittleEndianLong(packetBytes)
            writeLittleEndianInt(1)
            writeLittleEndianInt(1)
            writeLittleEndianLong(textBytes.size.toLong())
            writeLittleEndianLong(0)
            writeLittleEndianLong(8)
            writeLittleEndianInt(1)
            writeLittleEndianInt(0)

            writeLittleEndianInt(MarkdownBlockKind.Heading.protocolValue.toLong())
            writeLittleEndianInt(0)
            writeLittleEndianInt(1)
            writeLittleEndianInt(0)
            writeLittleEndianInt(0)
            writeLittleEndianInt(0)
            writeLittleEndianLong(0)
            writeLittleEndianInt(textBytes.size.toLong())
            writeLittleEndianInt(0)
            writeLittleEndianInt(1)
            writeLittleEndianInt(0)
            writeLittleEndianLong(0)
            writeLittleEndianLong(8)
            writeLittleEndianInt(1)
            writeLittleEndianInt(0)
            write(textBytes)

            writeLittleEndianInt(0)
            writeLittleEndianInt(5)
            writeLittleEndianInt(MARKDOWN_SPAN_STYLE_STRONG.toLong())
            writeLittleEndianInt(0)
            writeLittleEndianInt(0)
            writeLittleEndianInt(0)

            writeLittleEndianInt(0)
            writeLittleEndianInt(8)
            writeLittleEndianLong(0)
            writeLittleEndianLong(8)
        }.toByteArray()
    }

    /** Hides excess model entries as auxiliary bytes in a two-block packet header. */
    private fun createRepeatedHeadingPacket(spanCount: Long, sourceMapCount: Long): ByteArray {
        val heading = createHeadingPacket()
        val packet = heading + heading.copyOfRange(TEST_HEADER_BYTES.toInt(), heading.size)
        packet.writeLittleEndianLong(TEST_PACKET_BYTES_OFFSET, packet.size.toLong())
        packet.writeLittleEndianInt(TEST_BLOCK_COUNT_OFFSET, 2L)
        packet.writeLittleEndianInt(TEST_SPAN_COUNT_OFFSET, spanCount)
        packet.writeLittleEndianInt(TEST_SOURCE_MAP_COUNT_OFFSET, sourceMapCount)
        packet.writeLittleEndianLong(
            TEST_TEXT_BYTES_OFFSET,
            2L * "Hello 😀".toByteArray(Charsets.UTF_8).size
        )
        packet.writeLittleEndianLong(
            TEST_AUXILIARY_BYTES_OFFSET,
            (2L - spanCount) * TEST_SPAN_HEADER_BYTES +
                (2L - sourceMapCount) * TEST_SOURCE_MAP_HEADER_BYTES
        )
        return packet
    }

    /** Creates one otherwise minimal paragraph packet with exact UTF-8 text. */
    private fun createPlainTextPacket(text: String): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val sourceMapCount = if (text.isEmpty()) 0L else 1L
        val packetBytes =
            TEST_HEADER_BYTES +
                TEST_BLOCK_HEADER_BYTES +
                textBytes.size +
                TEST_SOURCE_MAP_HEADER_BYTES * sourceMapCount
        return ByteArrayOutputStream().apply {
            write("BTXTMDR6".toByteArray(Charsets.US_ASCII))
            writeLittleEndianInt(TEST_PACKET_VERSION)
            writeLittleEndianInt(TEST_HEADER_BYTES)
            writeLittleEndianInt(0)
            writeLittleEndianInt(0)
            writeLittleEndianLong(textBytes.size.toLong())
            writeLittleEndianLong(packetBytes)
            writeLittleEndianInt(1)
            writeLittleEndianInt(0)
            writeLittleEndianLong(textBytes.size.toLong())
            writeLittleEndianLong(0)
            writeLittleEndianLong(text.length.toLong())
            writeLittleEndianInt(sourceMapCount)
            writeLittleEndianInt(0)

            writeLittleEndianInt(MarkdownBlockKind.Paragraph.protocolValue.toLong())
            writeLittleEndianInt(0)
            writeLittleEndianInt(0)
            writeLittleEndianInt(0)
            writeLittleEndianInt(0)
            writeLittleEndianInt(0)
            writeLittleEndianLong(0)
            writeLittleEndianInt(textBytes.size.toLong())
            writeLittleEndianInt(0)
            writeLittleEndianInt(0)
            writeLittleEndianInt(0)
            writeLittleEndianLong(0)
            writeLittleEndianLong(text.length.toLong())
            writeLittleEndianInt(sourceMapCount)
            writeLittleEndianInt(0)
            write(textBytes)
            if (sourceMapCount != 0L) {
                writeLittleEndianInt(0)
                writeLittleEndianInt(text.length.toLong())
                writeLittleEndianLong(0)
                writeLittleEndianLong(text.length.toLong())
            }
        }.toByteArray()
    }

    /** Creates one deterministic version-six packet for extended semantic fields. */
    private fun createSingleBlockPacket(block: TestBlock): ByteArray {
        val textBytes = block.text.toByteArray(Charsets.UTF_8)
        val metadataBytes = block.metadata.toByteArray(Charsets.UTF_8)
        val destinationBytes =
            block.spans.map { span -> span.destination.toByteArray(Charsets.UTF_8) }
        val spanBytes = TEST_SPAN_HEADER_BYTES * block.spans.size
        val ownerBytes = block.footnoteLabel.orEmpty().toByteArray(Charsets.UTF_8)
        val separatorBytes = block.separators.size * Int.SIZE_BYTES
        val auxiliaryBytes = metadataBytes.size + destinationBytes.sumOf(ByteArray::size) + ownerBytes.size + separatorBytes
        val sourceMapCount = if (block.text.isEmpty()) 0L else 1L
        val packetBytes =
            TEST_HEADER_BYTES +
                TEST_BLOCK_HEADER_BYTES +
                textBytes.size +
                auxiliaryBytes +
                spanBytes +
                TEST_SOURCE_MAP_HEADER_BYTES * sourceMapCount
        return ByteArrayOutputStream().apply {
            write("BTXTMDR6".toByteArray(Charsets.US_ASCII))
            writeLittleEndianInt(TEST_PACKET_VERSION)
            writeLittleEndianInt(TEST_HEADER_BYTES)
            writeLittleEndianInt(0)
            writeLittleEndianInt(0)
            writeLittleEndianLong(textBytes.size.toLong())
            writeLittleEndianLong(packetBytes)
            writeLittleEndianInt(1)
            writeLittleEndianInt(block.spans.size.toLong())
            writeLittleEndianLong(textBytes.size.toLong())
            writeLittleEndianLong(auxiliaryBytes.toLong())
            writeLittleEndianLong(block.text.length.toLong())
            writeLittleEndianInt(sourceMapCount)
            writeLittleEndianInt(0)

            writeLittleEndianInt(block.kind.protocolValue.toLong())
            writeLittleEndianInt(block.flags)
            writeLittleEndianInt(0)
            writeLittleEndianInt(block.quoteDepth)
            writeLittleEndianInt(if (block.kind == MarkdownBlockKind.ListItem) 1 else 0)
            writeLittleEndianInt(block.separators.size.toLong())
            writeLittleEndianLong(0)
            writeLittleEndianInt(textBytes.size.toLong())
            writeLittleEndianInt(metadataBytes.size.toLong())
            writeLittleEndianInt(block.spans.size.toLong())
            writeLittleEndianInt(ownerBytes.size.toLong())
            writeLittleEndianLong(0)
            writeLittleEndianLong(block.text.length.toLong())
            writeLittleEndianInt(sourceMapCount)
            writeLittleEndianInt(block.quoteAlertDepth)
            write(textBytes)
            write(metadataBytes)
            write(ownerBytes)
            block.separators.forEach { writeLittleEndianInt(it.toLong()) }

            block.spans.forEachIndexed { spanIndex, span ->
                val destination = destinationBytes[spanIndex]
                writeLittleEndianInt(span.start)
                writeLittleEndianInt(span.end)
                writeLittleEndianInt(span.styles)
                writeLittleEndianInt(span.flags)
                writeLittleEndianInt(destination.size.toLong())
                writeLittleEndianInt(0)
                write(destination)
            }
            if (sourceMapCount != 0L) {
                writeLittleEndianInt(0)
                writeLittleEndianInt(block.text.length.toLong())
                writeLittleEndianLong(0)
                writeLittleEndianLong(block.text.length.toLong())
            }
        }.toByteArray()
    }

    /** Describes one minimal test block for the version-six packet writer. */
    private data class TestBlock(
        val kind: MarkdownBlockKind,
        val flags: Long = 0L,
        val quoteDepth: Long = 0L,
        val text: String,
        val metadata: String = "",
        val spans: List<TestSpan> = emptyList(),
        val separators: List<Int> = if (kind == MarkdownBlockKind.TableRow) text.indices.filter { text[it] == '\t' } else emptyList(),
        val footnoteLabel: String? = metadata.takeIf { kind == MarkdownBlockKind.Footnote },
        val quoteAlertDepth: Long = 0L
    )

    /** Describes one minimal test span for the version-six packet writer. */
    private data class TestSpan(
        val start: Long,
        val end: Long,
        val styles: Long,
        val flags: Long,
        val destination: String
    )

    /** Asserts that decoding fails with one stable protocol error. */
    private fun assertProtocolFailure(expectedMessage: String, packet: ByteArray) {
        val error =
            assertThrows(MarkdownProtocolException::class.java) {
                MarkdownPacketDecoder.decode(packet)
            }
        assertEquals(expectedMessage, error.message)
    }

    /** Writes an unsigned integer in little-endian byte order. */
    private fun ByteArrayOutputStream.writeLittleEndianInt(value: Long) {
        repeat(Int.SIZE_BYTES) { byteIndex ->
            write((value ushr (Byte.SIZE_BITS * byteIndex)).toInt())
        }
    }

    /** Writes a nonnegative long in little-endian byte order. */
    private fun ByteArrayOutputStream.writeLittleEndianLong(value: Long) {
        require(value >= 0L) { "test value must be nonnegative" }
        repeat(Long.SIZE_BYTES) { byteIndex ->
            write((value ushr (Byte.SIZE_BITS * byteIndex)).toInt())
        }
    }

    /** Replaces one unsigned integer in little-endian byte order. */
    private fun ByteArray.writeLittleEndianInt(offset: Int, value: Long) {
        require(offset >= 0 && offset + Int.SIZE_BYTES <= size) {
            "test field must fit in the packet"
        }
        repeat(Int.SIZE_BYTES) { byteIndex ->
            this[offset + byteIndex] =
                (value ushr (Byte.SIZE_BITS * byteIndex)).toByte()
        }
    }

    /** Replaces one nonnegative long in little-endian byte order. */
    private fun ByteArray.writeLittleEndianLong(offset: Int, value: Long) {
        require(offset >= 0 && offset + Long.SIZE_BYTES <= size) {
            "test field must fit in the packet"
        }
        require(value >= 0L) { "test value must be nonnegative" }
        repeat(Long.SIZE_BYTES) { byteIndex ->
            this[offset + byteIndex] =
                (value ushr (Byte.SIZE_BITS * byteIndex)).toByte()
        }
    }
}
