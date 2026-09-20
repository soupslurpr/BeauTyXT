package dev.soupslurpr.beautyxt.document

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

private const val DOCUMENT_METRICS_EDITABLE = 1
private const val DOCUMENT_METRICS_HAS_UTF8_BOM = 1 shl 1
private const val DOCUMENT_METRICS_HAS_LF_LINE_ENDINGS = 1 shl 2
private const val DOCUMENT_METRICS_HAS_CRLF_LINE_ENDINGS = 1 shl 3
private const val DOCUMENT_METRICS_HAS_CR_LINE_ENDINGS = 1 shl 4
private const val DOCUMENT_METRICS_ALLOWED_FLAGS =
    DOCUMENT_METRICS_EDITABLE or
        DOCUMENT_METRICS_HAS_UTF8_BOM or
        DOCUMENT_METRICS_HAS_LF_LINE_ENDINGS or
        DOCUMENT_METRICS_HAS_CRLF_LINE_ENDINGS or
        DOCUMENT_METRICS_HAS_CR_LINE_ENDINGS
private const val DOCUMENT_METRICS_RESERVED_BYTES = 6
private const val INSERTED_LINE_ENDING_LF = 0
private const val INSERTED_LINE_ENDING_CRLF = 1
private const val INSERTED_LINE_ENDING_CR = 2
private const val UNSIGNED_INT_MASK = 0xffff_ffffL

/** Identifies the serialized form used for newly inserted logical newlines. */
internal enum class DocumentLineEnding {
    Lf,
    CrLf,
    Cr
}

/** Describes live size, format, revision, and editing facts for a document. */
internal data class DocumentMetrics(
    val revision: Long,
    val byteLength: Long,
    val serializedByteLength: Long,
    val characterLength: Long,
    val utf16Length: Long,
    val lineCount: Long,
    val wordCount: Long,
    val hasUtf8Bom: Boolean,
    val hasLfLineEndings: Boolean,
    val hasCrlfLineEndings: Boolean,
    val hasCrLineEndings: Boolean,
    val insertedLineEnding: DocumentLineEnding,
    val isEditable: Boolean
)

/** Decodes and validates document metrics shared by native packet formats. */
internal fun decodeDocumentMetrics(reader: DocumentPacketReader): DocumentMetrics {
    val revision = reader.readSupportedUnsignedLong("revision")
    val byteLength = reader.readSupportedUnsignedLong("byte length")
    val serializedByteLength = reader.readSupportedUnsignedLong("serialized byte length")
    val characterLength = reader.readSupportedUnsignedLong("character length")
    val utf16Length = reader.readSupportedUnsignedLong("utf-16 length")
    val lineCount = reader.readSupportedUnsignedLong("line count")
    val wordCount = reader.readSupportedUnsignedLong("word count")
    val metricsFlags = reader.readUnsignedByte("metrics flags")
    reader.requireAllowedFlags(
        flags = metricsFlags,
        allowedFlags = DOCUMENT_METRICS_ALLOWED_FLAGS,
        field = "metrics flags"
    )
    val insertedLineEnding =
        when (reader.readUnsignedByte("inserted line ending")) {
            INSERTED_LINE_ENDING_LF -> DocumentLineEnding.Lf
            INSERTED_LINE_ENDING_CRLF -> DocumentLineEnding.CrLf
            INSERTED_LINE_ENDING_CR -> DocumentLineEnding.Cr
            else -> reader.reject("inserted line ending is unsupported")
        }
    reader.requireZeroBytes(DOCUMENT_METRICS_RESERVED_BYTES, "metrics reserved bytes")

    if (lineCount == 0L) {
        reader.reject("line count must be positive")
    }
    if (characterLength > utf16Length) {
        reader.reject("character length exceeds utf-16 length")
    }
    if (wordCount > characterLength) {
        reader.reject("word count exceeds character length")
    }
    if (utf16Length > byteLength) {
        reader.reject("utf-16 length exceeds byte length")
    }
    if (serializedByteLength < byteLength) {
        reader.reject("serialized byte length is shorter than logical byte length")
    }

    val hasLfLineEndings = metricsFlags and DOCUMENT_METRICS_HAS_LF_LINE_ENDINGS != 0
    val hasCrlfLineEndings = metricsFlags and DOCUMENT_METRICS_HAS_CRLF_LINE_ENDINGS != 0
    val hasCrLineEndings = metricsFlags and DOCUMENT_METRICS_HAS_CR_LINE_ENDINGS != 0
    val hasLineEndings = hasLfLineEndings || hasCrlfLineEndings || hasCrLineEndings
    if ((lineCount > 1L) != hasLineEndings) {
        reader.reject("line-ending flags conflict with line count")
    }

    val isEditable = metricsFlags and DOCUMENT_METRICS_EDITABLE != 0
    val expectedEditable = utf16Length <= Int.MAX_VALUE.toLong()
    if (isEditable != expectedEditable) {
        reader.reject("editable flag conflicts with utf-16 length")
    }
    return DocumentMetrics(
        revision = revision,
        byteLength = byteLength,
        serializedByteLength = serializedByteLength,
        characterLength = characterLength,
        utf16Length = utf16Length,
        lineCount = lineCount,
        wordCount = wordCount,
        hasUtf8Bom = metricsFlags and DOCUMENT_METRICS_HAS_UTF8_BOM != 0,
        hasLfLineEndings = hasLfLineEndings,
        hasCrlfLineEndings = hasCrlfLineEndings,
        hasCrLineEndings = hasCrLineEndings,
        insertedLineEnding = insertedLineEnding,
        isEditable = isEditable
    )
}

/** Reads native packet fields and reports format-specific protocol failures. */
internal class DocumentPacketReader(
    packet: ByteArray,
    private val createProtocolException: (String) -> IllegalArgumentException
) {
    private val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
    private val utf8Decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

    val remainingBytes: Int
        get() = buffer.remaining()

    /** Reads one unsigned byte. */
    fun readUnsignedByte(field: String): Int {
        requireRemaining(1, field)
        return buffer.get().toInt() and 0xff
    }

    /** Reads one unsigned little-endian short. */
    fun readUnsignedShort(field: String): Int {
        requireRemaining(Short.SIZE_BYTES, field)
        return buffer.short.toInt() and 0xffff
    }

    /** Reads one unsigned little-endian integer. */
    fun readUnsignedInt(field: String): Long {
        requireRemaining(Int.SIZE_BYTES, field)
        return buffer.int.toLong() and UNSIGNED_INT_MASK
    }

    /** Reads an unsigned long representable by Kotlin's signed offsets. */
    fun readSupportedUnsignedLong(field: String): Long {
        requireRemaining(Long.SIZE_BYTES, field)
        val value = buffer.long
        if (value < 0) {
            reject("$field exceeds the supported range")
        }
        return value
    }

    /** Reads one strictly valid UTF-8 payload. */
    fun readUtf8(byteLength: Long, field: String): String {
        require(byteLength >= 0) { "byte length must be nonnegative" }
        if (byteLength > remainingBytes.toLong()) {
            reject("$field exceeds the packet bounds")
        }
        val length = byteLength.toInt()
        val start = buffer.position()
        val payload = buffer.slice().apply { limit(length) }
        val text =
            try {
                utf8Decoder.decode(payload).toString()
            } catch (_: CharacterCodingException) {
                reject("$field is not valid utf-8")
            }
        buffer.position(start + length)
        return text
    }

    /** Requires flags to contain only bits defined by their packet version. */
    fun requireAllowedFlags(flags: Int, allowedFlags: Int, field: String) {
        if (flags and allowedFlags.inv() != 0) {
            reject("$field contains unsupported bits")
        }
    }

    /** Requires a fixed reserved region to contain only zero bytes. */
    fun requireZeroBytes(byteCount: Int, field: String) {
        require(byteCount >= 0) { "byte count must be nonnegative" }
        repeat(byteCount) {
            if (readUnsignedByte(field) != 0) {
                reject("$field must be zero")
            }
        }
    }

    /** Reports one format-specific protocol failure. */
    fun reject(message: String): Nothing = throw createProtocolException(message)

    /** Requires a fixed-width field to remain inside the packet. */
    private fun requireRemaining(byteCount: Int, field: String) {
        if (remainingBytes < byteCount) {
            reject("$field exceeds the packet bounds")
        }
    }
}
