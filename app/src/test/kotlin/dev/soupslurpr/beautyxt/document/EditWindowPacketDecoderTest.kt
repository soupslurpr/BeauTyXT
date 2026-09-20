package dev.soupslurpr.beautyxt.document

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEST_EDIT_REVISION = 7L
private const val TEST_EDIT_PACKET_VERSION_OFFSET = 4
private const val TEST_EDIT_PACKET_FLAGS_OFFSET = 6
private const val TEST_EDIT_REVISION_OFFSET = 8
private const val TEST_EDIT_METRICS_RESERVED_OFFSET = 66
private const val TEST_EDIT_RANGE_START_OFFSET = 72
private const val TEST_EDIT_RANGE_END_OFFSET = 80
private const val TEST_EDIT_SELECTION_START_OFFSET = 88
private const val TEST_EDIT_SELECTION_END_OFFSET = 96
private const val TEST_EDIT_START_LINE_OFFSET = 104
private const val TEST_EDIT_START_UTF16_OFFSET = 112
private const val TEST_EDIT_RESERVED_OFFSET = 124
private const val TEST_EDIT_TEXT_OFFSET = 128
private const val TEST_EDIT_PACKET_MAGIC = 0x5457_4542L

/** Verifies strict decoding of the Rust edit-window wire format. */
class EditWindowPacketDecoderTest {
    /** Verifies bounded multiline text and global coordinates decode exactly. */
    @Test
    fun decodesValidPacket() {
        val snapshot = EditWindowPacketDecoder.decode(createValidPacket())

        assertEquals(
            DocumentMetrics(
                revision = TEST_EDIT_REVISION,
                byteLength = 15,
                serializedByteLength = 18,
                characterLength = 12,
                utf16Length = 13,
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
        assertEquals(Utf16Range(start = 3, end = 8), snapshot.range)
        assertEquals(Utf16Range(start = 6, end = 8), snapshot.selection)
        assertEquals(
            ViewportCursor(
                revision = TEST_EDIT_REVISION,
                line = 0,
                utf16Offset = 3
            ),
            snapshot.start
        )
        assertEquals("lo\n😀", snapshot.text)
        assertTrue(snapshot.hasPrevious)
        assertTrue(snapshot.hasNext)
    }

    /** Verifies edit-window packets cannot be confused with another protocol. */
    @Test
    fun rejectsInvalidMagicAndVersion() {
        val magicPacket = createValidPacket()
        magicPacket[0] = 0
        assertProtocolFailure(
            expectedMessage = "edit-window packet has invalid magic",
            packet = magicPacket
        )

        val versionPacket = createValidPacket()
        versionPacket[TEST_EDIT_PACKET_VERSION_OFFSET] = 4
        assertProtocolFailure(
            expectedMessage = "unsupported edit-window packet version 4",
            packet = versionPacket
        )
    }

    /** Verifies reserved and undefined flag bits cannot change meaning. */
    @Test
    fun rejectsUnsupportedFlagsAndReservedBytes() {
        val flagsPacket = createValidPacket()
        flagsPacket[TEST_EDIT_PACKET_FLAGS_OFFSET] = 4
        assertProtocolFailure(
            expectedMessage = "edit-window flags contains unsupported bits",
            packet = flagsPacket
        )

        val metricsReservedPacket = createValidPacket()
        metricsReservedPacket[TEST_EDIT_METRICS_RESERVED_OFFSET] = 1
        assertProtocolFailure(
            expectedMessage = "metrics reserved bytes must be zero",
            packet = metricsReservedPacket
        )

        val editReservedPacket = createValidPacket()
        editReservedPacket[TEST_EDIT_RESERVED_OFFSET] = 1
        assertProtocolFailure(
            expectedMessage = "edit-window reserved bytes must be zero",
            packet = editReservedPacket
        )
    }

    /** Verifies malformed, unnormalized, truncated, and trailing data fail closed. */
    @Test
    fun rejectsInvalidTextPayloads() {
        val malformedPacket = createValidPacket()
        malformedPacket[TEST_EDIT_TEXT_OFFSET] = 0xc0.toByte()
        assertProtocolFailure(
            expectedMessage = "edit-window text is not valid utf-8",
            packet = malformedPacket
        )

        val unnormalizedPacket = createValidPacket()
        unnormalizedPacket[TEST_EDIT_TEXT_OFFSET] = '\r'.code.toByte()
        assertProtocolFailure(
            expectedMessage = "edit-window text is not normalized",
            packet = unnormalizedPacket
        )

        val completePacket = createValidPacket()
        assertProtocolFailure(
            expectedMessage = "edit-window text exceeds the packet bounds",
            packet = completePacket.copyOf(completePacket.size - 1)
        )
        assertProtocolFailure(
            expectedMessage = "edit-window packet has trailing bytes",
            packet = completePacket.copyOf(completePacket.size + 1)
        )
    }

    /** Verifies decoded text cannot exceed the native protocol boundary. */
    @Test
    fun rejectsTextBeyondProtocolLimit() {
        val textBytes =
            ByteArray(MAX_EDIT_WINDOW_UTF16_UNITS + 1).apply {
                fill('a'.code.toByte())
            }
        val packet =
            ByteArrayOutputStream().apply {
                writeLittleEndianInt(TEST_EDIT_PACKET_MAGIC)
                writeLittleEndianShort(3)
                writeLittleEndianShort(0)
                writeLittleEndianLong(0)
                writeLittleEndianLong(textBytes.size.toLong())
                writeLittleEndianLong(textBytes.size.toLong())
                writeLittleEndianLong(textBytes.size.toLong())
                writeLittleEndianLong(textBytes.size.toLong())
                writeLittleEndianLong(1)
                writeLittleEndianLong(1)
                write(1)
                write(0)
                repeat(6) { write(0) }
                writeLittleEndianLong(0)
                writeLittleEndianLong(textBytes.size.toLong())
                writeLittleEndianLong(0)
                writeLittleEndianLong(0)
                writeLittleEndianLong(0)
                writeLittleEndianLong(0)
                writeLittleEndianInt(textBytes.size.toLong())
                writeLittleEndianInt(0)
                write(textBytes)
            }.toByteArray()

        assertProtocolFailure(
            expectedMessage = "edit-window text exceeds the protocol limit",
            packet = packet
        )
    }

    /** Verifies global ranges and selections must match decoded UTF-16 text. */
    @Test
    fun rejectsInconsistentRanges() {
        val reversedRangePacket = createValidPacket()
        reversedRangePacket.writeLittleEndianLong(TEST_EDIT_RANGE_START_OFFSET, 9)
        assertProtocolFailure(
            expectedMessage = "edit-window end precedes its start",
            packet = reversedRangePacket
        )

        val textRangePacket = createValidPacket()
        textRangePacket.writeLittleEndianLong(TEST_EDIT_RANGE_END_OFFSET, 9)
        assertProtocolFailure(
            expectedMessage = "edit-window text conflicts with its global range",
            packet = textRangePacket
        )

        val outsideSelectionPacket = createValidPacket()
        outsideSelectionPacket.writeLittleEndianLong(TEST_EDIT_SELECTION_START_OFFSET, 2)
        assertProtocolFailure(
            expectedMessage = "selection exceeds the edit-window range",
            packet = outsideSelectionPacket
        )

        val splitSurrogatePacket = createValidPacket()
        splitSurrogatePacket.writeLittleEndianLong(TEST_EDIT_SELECTION_START_OFFSET, 7)
        assertProtocolFailure(
            expectedMessage = "selection start divides a surrogate pair",
            packet = splitSurrogatePacket
        )
    }

    /** Verifies coordinates and boundary flags remain canonical. */
    @Test
    fun rejectsInconsistentCoordinatesAndFlags() {
        val previousFlagPacket = createValidPacket()
        previousFlagPacket[TEST_EDIT_PACKET_FLAGS_OFFSET] = 2
        assertProtocolFailure(
            expectedMessage = "previous flag conflicts with the edit-window range",
            packet = previousFlagPacket
        )

        val nextFlagPacket = createValidPacket()
        nextFlagPacket[TEST_EDIT_PACKET_FLAGS_OFFSET] = 1
        assertProtocolFailure(
            expectedMessage = "next flag conflicts with the edit-window range",
            packet = nextFlagPacket
        )

        val startOffsetPacket = createValidPacket()
        startOffsetPacket.writeLittleEndianLong(TEST_EDIT_START_UTF16_OFFSET, 4)
        assertProtocolFailure(
            expectedMessage = "edit-window start position conflicts with its global offset",
            packet = startOffsetPacket
        )

        val startLinePacket = createValidPacket()
        startLinePacket.writeLittleEndianLong(TEST_EDIT_START_LINE_OFFSET, 1)
        assertProtocolFailure(
            expectedMessage = "edit-window text exceeds the document lines",
            packet = startLinePacket
        )
    }

    /** Verifies unsigned native values cannot wrap into negative offsets. */
    @Test
    fun rejectsUnsignedValuesOutsideTheSupportedRange() {
        val packet = createValidPacket()
        packet[TEST_EDIT_REVISION_OFFSET + Long.SIZE_BYTES - 1] = 0x80.toByte()

        assertProtocolFailure(
            expectedMessage = "revision exceeds the supported range",
            packet = packet
        )
    }

    /** Creates one deterministic packet matching the Rust version 3 encoder. */
    private fun createValidPacket(): ByteArray {
        val textBytes = "lo\n😀".toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().apply {
            writeLittleEndianInt(TEST_EDIT_PACKET_MAGIC)
            writeLittleEndianShort(3)
            writeLittleEndianShort(3)
            writeLittleEndianLong(TEST_EDIT_REVISION)
            writeLittleEndianLong(15)
            writeLittleEndianLong(18)
            writeLittleEndianLong(12)
            writeLittleEndianLong(13)
            writeLittleEndianLong(2)
            writeLittleEndianLong(3)
            write(5)
            write(0)
            repeat(6) { write(0) }
            writeLittleEndianLong(3)
            writeLittleEndianLong(8)
            writeLittleEndianLong(6)
            writeLittleEndianLong(8)
            writeLittleEndianLong(0)
            writeLittleEndianLong(3)
            writeLittleEndianInt(textBytes.size.toLong())
            writeLittleEndianInt(0)
            write(textBytes)
        }.toByteArray()
    }

    /** Asserts that decoding fails with one stable protocol error. */
    private fun assertProtocolFailure(expectedMessage: String, packet: ByteArray) {
        val error =
            assertThrows(EditWindowProtocolException::class.java) {
                EditWindowPacketDecoder.decode(packet)
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
