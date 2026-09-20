package dev.soupslurpr.beautyxt.document

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

private const val TEST_METRICS_REVISION = 7L
private const val TEST_METRICS_PACKET_VERSION_OFFSET = 4
private const val TEST_METRICS_PACKET_FLAGS_OFFSET = 6
private const val TEST_METRICS_REVISION_OFFSET = 8
private const val TEST_METRICS_SERIALIZED_BYTE_LENGTH_OFFSET = 24
private const val TEST_METRICS_INSERTED_LINE_ENDING_OFFSET = 65
private const val TEST_METRICS_RESERVED_OFFSET = 66
private const val TEST_METRICS_PACKET_MAGIC = 0x544d_4542L
private const val TEST_METRICS_PACKET_VERSION = 3
private const val TEST_METRICS_BYTE_LENGTH = 15L
private const val TEST_METRICS_SERIALIZED_BYTE_LENGTH = 18L
private const val TEST_METRICS_CHARACTER_LENGTH = 12L
private const val TEST_METRICS_UTF16_LENGTH = 13L
private const val TEST_METRICS_LINE_COUNT = 2L
private const val TEST_METRICS_WORD_COUNT = 3L
private const val TEST_METRICS_FLAGS = 1 or (1 shl 1) or (1 shl 2) or (1 shl 3)
private const val TEST_METRICS_RESERVED_BYTES = 6

/** Verifies strict decoding of the Rust document-metrics wire format. */
class DocumentMetricsPacketDecoderTest {
    /** Verifies every authoritative metric decodes exactly. */
    @Test
    fun decodesValidPacket() {
        val metrics = DocumentMetricsPacketDecoder.decode(createValidPacket())

        assertEquals(
            DocumentMetrics(
                revision = TEST_METRICS_REVISION,
                byteLength = TEST_METRICS_BYTE_LENGTH,
                serializedByteLength = TEST_METRICS_SERIALIZED_BYTE_LENGTH,
                characterLength = TEST_METRICS_CHARACTER_LENGTH,
                utf16Length = TEST_METRICS_UTF16_LENGTH,
                lineCount = TEST_METRICS_LINE_COUNT,
                wordCount = TEST_METRICS_WORD_COUNT,
                hasUtf8Bom = true,
                hasLfLineEndings = true,
                hasCrlfLineEndings = true,
                hasCrLineEndings = false,
                insertedLineEnding = DocumentLineEnding.CrLf,
                isEditable = true
            ),
            metrics
        )
    }

    /** Verifies packets cannot be confused with another protocol version. */
    @Test
    fun rejectsInvalidMagicAndVersion() {
        val magicPacket = createValidPacket()
        magicPacket[0] = 0
        assertProtocolFailure(
            expectedMessage = "document-metrics packet has invalid magic",
            packet = magicPacket
        )

        val versionPacket = createValidPacket()
        versionPacket[TEST_METRICS_PACKET_VERSION_OFFSET] = 4
        assertProtocolFailure(
            expectedMessage = "unsupported document-metrics packet version 4",
            packet = versionPacket
        )
    }

    /** Verifies serialized output cannot be shorter than logical text. */
    @Test
    fun rejectsShortSerializedLength() {
        val packet = createValidPacket()
        packet.writeLittleEndianLong(
            offset = TEST_METRICS_SERIALIZED_BYTE_LENGTH_OFFSET,
            value = TEST_METRICS_BYTE_LENGTH - 1L
        )

        assertProtocolFailure(
            expectedMessage = "serialized byte length is shorter than logical byte length",
            packet = packet
        )
    }

    /** Verifies undefined flags and reserved metric bytes fail closed. */
    @Test
    fun rejectsUnsupportedFlagsAndReservedBytes() {
        val flagsPacket = createValidPacket()
        flagsPacket[TEST_METRICS_PACKET_FLAGS_OFFSET] = 1
        assertProtocolFailure(
            expectedMessage = "document-metrics flags contains unsupported bits",
            packet = flagsPacket
        )

        val reservedPacket = createValidPacket()
        reservedPacket[TEST_METRICS_RESERVED_OFFSET] = 1
        assertProtocolFailure(
            expectedMessage = "metrics reserved bytes must be zero",
            packet = reservedPacket
        )

        val lineEndingPacket = createValidPacket()
        lineEndingPacket[TEST_METRICS_INSERTED_LINE_ENDING_OFFSET] = 3
        assertProtocolFailure(
            expectedMessage = "inserted line ending is unsupported",
            packet = lineEndingPacket
        )
    }

    /** Verifies truncated and trailing packets fail closed. */
    @Test
    fun rejectsInvalidPacketLengths() {
        val completePacket = createValidPacket()
        assertProtocolFailure(
            expectedMessage = "document-metrics packet is shorter than its header",
            packet = completePacket.copyOf(completePacket.size - 1)
        )
        assertProtocolFailure(
            expectedMessage = "document-metrics packet has trailing bytes",
            packet = completePacket.copyOf(completePacket.size + 1)
        )
    }

    /** Verifies unsigned native values cannot wrap into negative offsets. */
    @Test
    fun rejectsUnsignedValuesOutsideTheSupportedRange() {
        val packet = createValidPacket()
        packet[TEST_METRICS_REVISION_OFFSET + Long.SIZE_BYTES - 1] = 0x80.toByte()

        assertProtocolFailure(
            expectedMessage = "revision exceeds the supported range",
            packet = packet
        )
    }

    /** Creates one deterministic packet matching the Rust version 3 encoder. */
    private fun createValidPacket(): ByteArray = ByteArrayOutputStream().apply {
        writeLittleEndianInt(TEST_METRICS_PACKET_MAGIC)
        writeLittleEndianShort(TEST_METRICS_PACKET_VERSION)
        writeLittleEndianShort(0)
        writeLittleEndianLong(TEST_METRICS_REVISION)
        writeLittleEndianLong(TEST_METRICS_BYTE_LENGTH)
        writeLittleEndianLong(TEST_METRICS_SERIALIZED_BYTE_LENGTH)
        writeLittleEndianLong(TEST_METRICS_CHARACTER_LENGTH)
        writeLittleEndianLong(TEST_METRICS_UTF16_LENGTH)
        writeLittleEndianLong(TEST_METRICS_LINE_COUNT)
        writeLittleEndianLong(TEST_METRICS_WORD_COUNT)
        write(TEST_METRICS_FLAGS)
        write(1)
        repeat(TEST_METRICS_RESERVED_BYTES) { write(0) }
    }.toByteArray()

    /** Asserts that decoding fails with one stable protocol error. */
    private fun assertProtocolFailure(expectedMessage: String, packet: ByteArray) {
        val error =
            assertThrows(DocumentMetricsProtocolException::class.java) {
                DocumentMetricsPacketDecoder.decode(packet)
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

    /** Writes a nonnegative long into an existing little-endian packet field. */
    private fun ByteArray.writeLittleEndianLong(offset: Int, value: Long) {
        require(offset >= 0 && offset <= size - Long.SIZE_BYTES) {
            "test field must fit the packet"
        }
        require(value >= 0) { "test value must be nonnegative" }
        repeat(Long.SIZE_BYTES) { byteIndex ->
            this[offset + byteIndex] =
                (value ushr (Byte.SIZE_BITS * byteIndex)).toByte()
        }
    }
}
