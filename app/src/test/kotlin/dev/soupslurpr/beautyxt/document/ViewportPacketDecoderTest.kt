package dev.soupslurpr.beautyxt.document

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

private const val TEST_REVISION = 7L
private const val TEST_DOCUMENT_UTF16_LENGTH = 13L
private const val TEST_PACKET_FLAGS_OFFSET = 6
private const val TEST_REVISION_OFFSET = 8
private const val TEST_HEADER_RESERVED_OFFSET = 66
private const val TEST_PREVIOUS_LINE_OFFSET = 72
private const val TEST_PREVIOUS_UTF16_OFFSET = 80
private const val TEST_BLOCK_HEADER_OFFSET = 108
private const val TEST_BLOCK_GLOBAL_END_OFFSET = TEST_BLOCK_HEADER_OFFSET + 16
private const val TEST_BLOCK_FLAGS_OFFSET = TEST_BLOCK_HEADER_OFFSET + 24
private const val TEST_BLOCK_TEXT_OFFSET = TEST_BLOCK_HEADER_OFFSET + 32
private const val TEST_PACKET_MAGIC = 0x5458_5442L

/** Verifies strict decoding of the Rust viewport wire format. */
class ViewportPacketDecoderTest {
    /** Verifies metrics, Unicode text, and revision-bound continuation data. */
    @Test
    fun decodesValidPacket() {
        val snapshot = ViewportPacketDecoder.decode(createValidPacket())

        assertEquals(
            DocumentMetrics(
                revision = TEST_REVISION,
                byteLength = 15,
                serializedByteLength = 18,
                characterLength = 12,
                utf16Length = TEST_DOCUMENT_UTF16_LENGTH,
                lineCount = 2,
                wordCount = 3,
                hasUtf8Bom = false,
                hasLfLineEndings = true,
                hasCrlfLineEndings = false,
                hasCrLineEndings = false,
                insertedLineEnding = DocumentLineEnding.Lf,
                isEditable = true
            ),
            snapshot.metrics
        )
        assertEquals(
            listOf(
                RenderBlock(
                    logicalLine = 0,
                    globalUtf16Start = 1,
                    globalUtf16End = 9,
                    lineTerminatorUtf16Units = 1,
                    text = "hello 😀",
                    continuesAtStart = true,
                    continuesAtEnd = false
                )
            ),
            snapshot.blocks
        )
        assertEquals(
            ViewportCursor(revision = TEST_REVISION, line = 0, utf16Offset = 1),
            snapshot.previous
        )
        assertEquals(
            ViewportCursor(revision = TEST_REVISION, line = 1, utf16Offset = 0),
            snapshot.next
        )
    }

    /** Verifies that reserved and undefined flag bits cannot change meaning. */
    @Test
    fun rejectsUnsupportedFlagsAndReservedBytes() {
        val snapshotFlagsPacket = createValidPacket()
        snapshotFlagsPacket[TEST_PACKET_FLAGS_OFFSET] = 4
        assertProtocolFailure(
            expectedMessage = "snapshot flags contains unsupported bits",
            packet = snapshotFlagsPacket
        )

        val blockFlagsPacket = createValidPacket()
        blockFlagsPacket[TEST_BLOCK_FLAGS_OFFSET] = 4
        assertProtocolFailure(
            expectedMessage = "block flags contains unsupported bits",
            packet = blockFlagsPacket
        )

        val reservedPacket = createValidPacket()
        reservedPacket[TEST_HEADER_RESERVED_OFFSET] = 1
        assertProtocolFailure(
            expectedMessage = "metrics reserved bytes must be zero",
            packet = reservedPacket
        )
    }

    /** Verifies that malformed, unnormalized, and truncated payloads fail closed. */
    @Test
    fun rejectsInvalidBlockTextPayloads() {
        val malformedPacket = createValidPacket()
        malformedPacket[TEST_BLOCK_TEXT_OFFSET] = 0xc0.toByte()
        assertProtocolFailure(
            expectedMessage = "block text is not valid utf-8",
            packet = malformedPacket
        )

        val unnormalizedPacket = createValidPacket()
        unnormalizedPacket[TEST_BLOCK_TEXT_OFFSET] = '\r'.code.toByte()
        assertProtocolFailure(
            expectedMessage = "block text contains a line terminator",
            packet = unnormalizedPacket
        )

        val completePacket = createValidPacket()
        val truncatedPacket = completePacket.copyOf(completePacket.size - 1)
        assertProtocolFailure(
            expectedMessage = "block text exceeds the packet bounds",
            packet = truncatedPacket
        )
    }

    /** Verifies that decoded text must exactly match its global UTF-16 range. */
    @Test
    fun rejectsInconsistentGlobalUtf16Range() {
        val packet = createValidPacket()
        packet.writeLittleEndianLong(TEST_BLOCK_GLOBAL_END_OFFSET, 7)

        assertProtocolFailure(
            expectedMessage = "block text conflicts with its global utf-16 range",
            packet = packet
        )
    }

    /** Verifies that unsigned native values cannot wrap into negative offsets. */
    @Test
    fun rejectsUnsignedValuesOutsideTheSupportedRange() {
        val packet = createValidPacket()
        packet[TEST_REVISION_OFFSET + Long.SIZE_BYTES - 1] = 0x80.toByte()

        assertProtocolFailure(
            expectedMessage = "revision exceeds the supported range",
            packet = packet
        )
    }

    /** Verifies that absent cursors and complete packets have canonical fields. */
    @Test
    fun rejectsNoncanonicalPacketBoundaries() {
        val absentNextCursorPacket = createValidPacket()
        absentNextCursorPacket[TEST_PACKET_FLAGS_OFFSET] = 2
        assertProtocolFailure(
            expectedMessage = "absent next cursor contains nonzero fields",
            packet = absentNextCursorPacket
        )

        val absentPreviousCursorPacket = createValidPacket()
        absentPreviousCursorPacket[TEST_PACKET_FLAGS_OFFSET] = 1
        assertProtocolFailure(
            expectedMessage = "absent previous cursor contains nonzero fields",
            packet = absentPreviousCursorPacket
        )

        val completePacket = createValidPacket()
        val packetWithTrailingByte = completePacket.copyOf(completePacket.size + 1)
        assertProtocolFailure(
            expectedMessage = "viewport packet has trailing bytes",
            packet = packetWithTrailingByte
        )
    }

    /** Verifies that each present page anchor stays within the document lines. */
    @Test
    fun rejectsPageAnchorsOutsideTheDocument() {
        val linePacket = createValidPacket()
        linePacket.writeLittleEndianLong(TEST_PREVIOUS_LINE_OFFSET, 2)

        assertProtocolFailure(
            expectedMessage = "previous cursor line exceeds the document",
            packet = linePacket
        )

        val offsetPacket = createValidPacket()
        offsetPacket.writeLittleEndianLong(
            TEST_PREVIOUS_UTF16_OFFSET,
            TEST_DOCUMENT_UTF16_LENGTH + 1
        )

        assertProtocolFailure(
            expectedMessage = "previous cursor offset exceeds the document",
            packet = offsetPacket
        )
    }

    /** Creates one deterministic packet matching the Rust version 4 encoder. */
    private fun createValidPacket(): ByteArray {
        val textBytes = "hello 😀".toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().apply {
            writeLittleEndianInt(TEST_PACKET_MAGIC)
            writeLittleEndianShort(4)
            writeLittleEndianShort(3)
            writeLittleEndianLong(TEST_REVISION)
            writeLittleEndianLong(15)
            writeLittleEndianLong(18)
            writeLittleEndianLong(12)
            writeLittleEndianLong(TEST_DOCUMENT_UTF16_LENGTH)
            writeLittleEndianLong(2)
            writeLittleEndianLong(3)
            write(5)
            write(0)
            repeat(6) { write(0) }
            writeLittleEndianLong(0)
            writeLittleEndianLong(1)
            writeLittleEndianLong(1)
            writeLittleEndianLong(0)
            writeLittleEndianInt(1)

            writeLittleEndianLong(0)
            writeLittleEndianLong(1)
            writeLittleEndianLong(9)
            write(1)
            write(1)
            writeLittleEndianShort(0)
            writeLittleEndianInt(textBytes.size.toLong())
            write(textBytes)
        }.toByteArray()
    }

    /** Asserts that decoding fails with one stable protocol error. */
    private fun assertProtocolFailure(expectedMessage: String, packet: ByteArray) {
        val error =
            assertThrows(ViewportProtocolException::class.java) {
                ViewportPacketDecoder.decode(packet)
            }
        assertEquals(expectedMessage, error.message)
    }

    /** Writes an unsigned short in little-endian byte order. */
    private fun ByteArrayOutputStream.writeLittleEndianShort(value: Int) {
        repeat(Short.SIZE_BYTES) { byteIndex ->
            write(value ushr (Byte.SIZE_BITS * byteIndex))
        }
    }

    /** Writes an unsigned integer in little-endian byte order. */
    private fun ByteArrayOutputStream.writeLittleEndianInt(value: Long) {
        repeat(Int.SIZE_BYTES) { byteIndex ->
            write((value ushr (Byte.SIZE_BITS * byteIndex)).toInt())
        }
    }

    /** Writes a nonnegative long in little-endian byte order. */
    private fun ByteArrayOutputStream.writeLittleEndianLong(value: Long) {
        require(value >= 0) { "test value must be nonnegative" }
        repeat(Long.SIZE_BYTES) { byteIndex ->
            write((value ushr (Byte.SIZE_BITS * byteIndex)).toInt())
        }
    }

    /** Replaces a nonnegative long field in little-endian byte order. */
    private fun ByteArray.writeLittleEndianLong(offset: Int, value: Long) {
        require(offset >= 0 && offset + Long.SIZE_BYTES <= size) {
            "test field must fit in the packet"
        }
        require(value >= 0) { "test value must be nonnegative" }
        repeat(Long.SIZE_BYTES) { byteIndex ->
            this[offset + byteIndex] =
                (value ushr (Byte.SIZE_BITS * byteIndex)).toByte()
        }
    }
}
