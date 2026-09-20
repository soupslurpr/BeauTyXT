package dev.soupslurpr.beautyxt.document

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

private const val TEST_FIND_REVISION = 7L
private const val TEST_FIND_DOCUMENT_UTF16_UNITS = 15L
private const val TEST_FIND_PACKET_MAGIC = 0x4e46_4542L
private const val TEST_FIND_VERSION_OFFSET = 4
private const val TEST_FIND_FLAGS_OFFSET = 6
private const val TEST_FIND_REVISION_OFFSET = 8
private const val TEST_FIND_METRICS_RESERVED_OFFSET = 66
private const val TEST_FIND_MATCH_START_OFFSET = 72
private const val TEST_FIND_MATCH_END_OFFSET = 80
private const val TEST_FIND_MATCH_LINE_OFFSET = 88
private const val TEST_FIND_MATCH_UTF16_OFFSET = 96
private const val TEST_FIND_REMAINING_START_OFFSET = 104
private const val TEST_FIND_REMAINING_END_OFFSET = 112

/** Verifies strict decoding of the fixed-size Rust find wire format. */
class FindPacketDecoderTest {
    /** Verifies one revision-bound Unicode match decodes exactly. */
    @Test
    fun decodesMatchPacket() {
        val batch = FindPacketDecoder.decode(createMatchPacket())

        assertEquals(expectedMetrics(), batch.metrics)
        assertEquals(
            FindMatch(
                range = Utf16Range(start = 6L, end = 8L),
                start =
                    ViewportCursor(
                        revision = TEST_FIND_REVISION,
                        line = 0L,
                        utf16Offset = 6L
                    )
            ),
            batch.match
        )
        assertNull(batch.remainingCandidateRange)
    }

    /** Verifies remaining and exhausted batches preserve their distinct states. */
    @Test
    fun decodesRemainingAndExhaustedPackets() {
        val remaining = FindPacketDecoder.decode(createRemainingPacket())
        val exhausted = FindPacketDecoder.decode(createExhaustedPacket())

        assertNull(remaining.match)
        assertEquals(Utf16Range(start = 8L, end = 15L), remaining.remainingCandidateRange)
        assertNull(exhausted.match)
        assertNull(exhausted.remainingCandidateRange)
    }

    /** Verifies packet identity, version, flags, and fixed length fail closed. */
    @Test
    fun rejectsInvalidEnvelope() {
        val magicPacket = createMatchPacket()
        magicPacket[0] = 0
        assertProtocolFailure("find packet has invalid magic", magicPacket)

        val versionPacket = createMatchPacket()
        versionPacket[TEST_FIND_VERSION_OFFSET] = 3
        assertProtocolFailure("unsupported find packet version 3", versionPacket)

        val unsupportedFlagsPacket = createMatchPacket()
        unsupportedFlagsPacket[TEST_FIND_FLAGS_OFFSET] = 4
        assertProtocolFailure("find flags contains unsupported bits", unsupportedFlagsPacket)

        val conflictingFlagsPacket = createMatchPacket()
        conflictingFlagsPacket[TEST_FIND_FLAGS_OFFSET] = 3
        assertProtocolFailure(
            "find packet contains both a match and a remaining candidate",
            conflictingFlagsPacket
        )

        val packet = createMatchPacket()
        assertProtocolFailure(
            "find packet is shorter than its fixed length",
            packet.copyOf(packet.size - 1)
        )
        assertProtocolFailure("find packet has trailing bytes", packet.copyOf(packet.size + 1))
    }

    /** Verifies metrics and unsigned fields retain the common protocol checks. */
    @Test
    fun rejectsInvalidMetricsAndUnsignedValues() {
        val reservedPacket = createMatchPacket()
        reservedPacket[TEST_FIND_METRICS_RESERVED_OFFSET] = 1
        assertProtocolFailure("metrics reserved bytes must be zero", reservedPacket)

        val unsignedPacket = createMatchPacket()
        unsignedPacket[TEST_FIND_REVISION_OFFSET + Long.SIZE_BYTES - 1] = 0x80.toByte()
        assertProtocolFailure("revision exceeds the supported range", unsignedPacket)
    }

    /** Verifies absent result fields use only their canonical zero encoding. */
    @Test
    fun rejectsNoncanonicalAbsentFields() {
        val absentMatchPacket = createRemainingPacket()
        absentMatchPacket.writeLittleEndianLong(TEST_FIND_MATCH_START_OFFSET, 1L)
        assertProtocolFailure("absent find match contains nonzero fields", absentMatchPacket)

        val absentRemainingPacket = createMatchPacket()
        absentRemainingPacket.writeLittleEndianLong(TEST_FIND_REMAINING_END_OFFSET, 1L)
        assertProtocolFailure(
            "absent remaining find candidate contains nonzero fields",
            absentRemainingPacket
        )
    }

    /** Verifies match ranges and line-relative positions stay within their metrics. */
    @Test
    fun rejectsInvalidMatches() {
        val emptyPacket = createMatchPacket()
        emptyPacket.writeLittleEndianLong(TEST_FIND_MATCH_END_OFFSET, 6L)
        assertProtocolFailure("find match must be nonempty", emptyPacket)

        val reversedPacket = createMatchPacket()
        reversedPacket.writeLittleEndianLong(TEST_FIND_MATCH_END_OFFSET, 5L)
        assertProtocolFailure("find match end precedes its start", reversedPacket)

        val outsidePacket = createMatchPacket()
        outsidePacket.writeLittleEndianLong(
            TEST_FIND_MATCH_END_OFFSET,
            TEST_FIND_DOCUMENT_UTF16_UNITS + 1L
        )
        assertProtocolFailure("find match exceeds the document", outsidePacket)

        val linePacket = createMatchPacket()
        linePacket.writeLittleEndianLong(TEST_FIND_MATCH_LINE_OFFSET, 2L)
        assertProtocolFailure("find match line exceeds the document", linePacket)

        val offsetPacket = createMatchPacket()
        offsetPacket.writeLittleEndianLong(TEST_FIND_MATCH_UTF16_OFFSET, 7L)
        assertProtocolFailure(
            "find match line-relative offset exceeds its global start",
            offsetPacket
        )

        val firstLinePacket = createMatchPacket()
        firstLinePacket.writeLittleEndianLong(TEST_FIND_MATCH_UTF16_OFFSET, 5L)
        assertProtocolFailure(
            "first-line find match has an inconsistent start offset",
            firstLinePacket
        )
    }

    /** Verifies remaining candidates are nonempty and document-bounded. */
    @Test
    fun rejectsInvalidRemainingCandidates() {
        val emptyPacket = createRemainingPacket()
        emptyPacket.writeLittleEndianLong(TEST_FIND_REMAINING_END_OFFSET, 8L)
        assertProtocolFailure("remaining find candidate must be nonempty", emptyPacket)

        val reversedPacket = createRemainingPacket()
        reversedPacket.writeLittleEndianLong(TEST_FIND_REMAINING_END_OFFSET, 7L)
        assertProtocolFailure(
            "remaining find candidate end precedes its start",
            reversedPacket
        )

        val outsidePacket = createRemainingPacket()
        outsidePacket.writeLittleEndianLong(
            TEST_FIND_REMAINING_END_OFFSET,
            TEST_FIND_DOCUMENT_UTF16_UNITS + 1L
        )
        assertProtocolFailure("remaining find candidate exceeds the document", outsidePacket)
    }

    /** Verifies Kotlin rejects malformed or unbounded requests before JNI. */
    @Test
    fun validatesFindRequests() {
        val validRequest =
            FindRequest(
                revision = TEST_FIND_REVISION,
                query = "😀",
                candidateRange = Utf16Range(start = 0L, end = TEST_FIND_DOCUMENT_UTF16_UNITS),
                direction = FindDirection.Forward,
                maxCandidateUtf16Units = 2
            )
        assertEquals(2, validRequest.maxCandidateUtf16Units)
        assertEquals("line\nbreak", validRequest.copy(query = "line\nbreak").query)

        assertRequestFailure("find query must not be empty") { validRequest.copy(query = "") }
        assertRequestFailure("find query contains a carriage return") {
            validRequest.copy(query = "line\rbreak")
        }
        assertRequestFailure("find query contains an unpaired surrogate") {
            validRequest.copy(query = "\ud800")
        }
        assertRequestFailure("find query exceeds the utf-16 limit") {
            validRequest.copy(query = "x".repeat(MAX_FIND_QUERY_UTF16_UNITS + 1))
        }
        assertRequestFailure("maximum candidate utf-16 units must be at least two") {
            validRequest.copy(maxCandidateUtf16Units = 1)
        }
        assertRequestFailure("maximum candidate utf-16 units exceed the native limit") {
            validRequest.copy(maxCandidateUtf16Units = MAX_FIND_CANDIDATE_UTF16_UNITS + 1)
        }
    }

    /** Creates one packet containing a match and canonical absent remaining fields. */
    private fun createMatchPacket(): ByteArray = createPacket(
        flags = 1,
        matchStart = 6L,
        matchEnd = 8L,
        matchLine = 0L,
        matchUtf16Offset = 6L,
        remainingStart = 0L,
        remainingEnd = 0L
    )

    /** Creates one packet containing a remaining range and canonical absent match fields. */
    private fun createRemainingPacket(): ByteArray = createPacket(
        flags = 2,
        matchStart = 0L,
        matchEnd = 0L,
        matchLine = 0L,
        matchUtf16Offset = 0L,
        remainingStart = 8L,
        remainingEnd = TEST_FIND_DOCUMENT_UTF16_UNITS
    )

    /** Creates one terminal packet without a match or remaining candidate. */
    private fun createExhaustedPacket(): ByteArray = createPacket(
        flags = 0,
        matchStart = 0L,
        matchEnd = 0L,
        matchLine = 0L,
        matchUtf16Offset = 0L,
        remainingStart = 0L,
        remainingEnd = 0L
    )

    /** Creates one deterministic packet matching the Rust version 2 encoder. */
    private fun createPacket(
        flags: Int,
        matchStart: Long,
        matchEnd: Long,
        matchLine: Long,
        matchUtf16Offset: Long,
        remainingStart: Long,
        remainingEnd: Long
    ): ByteArray = ByteArrayOutputStream().apply {
        writeLittleEndianInt(TEST_FIND_PACKET_MAGIC)
        writeLittleEndianShort(2)
        writeLittleEndianShort(flags)
        writeLittleEndianLong(TEST_FIND_REVISION)
        writeLittleEndianLong(17L)
        writeLittleEndianLong(17L)
        writeLittleEndianLong(14L)
        writeLittleEndianLong(TEST_FIND_DOCUMENT_UTF16_UNITS)
        writeLittleEndianLong(2L)
        writeLittleEndianLong(3L)
        write(5)
        write(0)
        repeat(6) { write(0) }
        writeLittleEndianLong(matchStart)
        writeLittleEndianLong(matchEnd)
        writeLittleEndianLong(matchLine)
        writeLittleEndianLong(matchUtf16Offset)
        writeLittleEndianLong(remainingStart)
        writeLittleEndianLong(remainingEnd)
    }.toByteArray()

    /** Returns the metrics encoded by every deterministic find packet. */
    private fun expectedMetrics(): DocumentMetrics = DocumentMetrics(
        revision = TEST_FIND_REVISION,
        byteLength = 17L,
        serializedByteLength = 17L,
        characterLength = 14L,
        utf16Length = TEST_FIND_DOCUMENT_UTF16_UNITS,
        lineCount = 2L,
        wordCount = 3L,
        hasUtf8Bom = false,
        hasLfLineEndings = true,
        hasCrlfLineEndings = false,
        hasCrLineEndings = false,
        insertedLineEnding = DocumentLineEnding.Lf,
        isEditable = true
    )

    /** Asserts that decoding fails with one stable protocol error. */
    private fun assertProtocolFailure(expectedMessage: String, packet: ByteArray) {
        val error =
            assertThrows(FindProtocolException::class.java) {
                FindPacketDecoder.decode(packet)
            }
        assertEquals(expectedMessage, error.message)
    }

    /** Asserts that request construction fails with one stable boundary error. */
    private fun assertRequestFailure(expectedMessage: String, action: () -> Unit) {
        val error = assertThrows(IllegalArgumentException::class.java) { action() }
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
        require(value >= 0L) { "test value must be nonnegative" }
        repeat(Long.SIZE_BYTES) { byteIndex ->
            write((value ushr (Byte.SIZE_BITS * byteIndex)).toInt())
        }
    }

    /** Replaces a nonnegative long field in little-endian byte order. */
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
