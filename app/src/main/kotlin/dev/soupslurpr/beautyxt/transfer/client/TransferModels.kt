package dev.soupslurpr.beautyxt.transfer.client

import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.transfer.TransferProtocol
import dev.soupslurpr.beautyxt.transfer.isValidNfcTagLabel
import dev.soupslurpr.beautyxt.transfer.isValidReportedNfcTagId
import dev.soupslurpr.beautyxt.transfer.packedNfcTagLabelLength
import java.nio.charset.CharacterCodingException
import java.util.concurrent.atomic.AtomicBoolean

private val QR_PACKET_MAGIC = byteArrayOf(0x42, 0x58, 0x51, 0x52)
private const val QR_PACKET_VERSION = 1
private const val QR_PACKET_HEADER_BYTES = 11
private val NFC_PACKET_MAGIC = byteArrayOf(0x42, 0x58, 0x4e, 0x46)
private const val NFC_PACKET_VERSION = 1
private const val NFC_PACKET_HEADER_BYTES = 16
private const val NFC_PACKET_SOURCE_OFFSET = 5
private const val NFC_PACKET_FORMAT_OFFSET = 6
private const val NFC_PACKET_TAG_LABEL_LENGTH_OFFSET = 7
private const val NFC_PACKET_TITLE_LENGTH_OFFSET = 8
private const val NFC_PACKET_TEXT_LENGTH_OFFSET = 12

/** Identifies one sanitized isolated-transfer failure. */
internal enum class TransferFailure {
    TooLarge,
    InvalidUtf8,
    InvalidNdef,
    AmbiguousNdef,
    Unsupported,
    NoQrCode,
    AmbiguousQr,
    ServiceUnavailable,
    ServiceBusy,
    SnapshotFailed,
    TimedOut,
    InvalidResponse,
    ProcessingFailed
}

/** Reports one sanitized isolated-transfer failure. */
internal class TransferException(val failure: TransferFailure, cause: Throwable? = null) :
    Exception(failure.name, cause)

/** Contains one exact grayscale frame copied from CameraX. */
internal class QrLuminanceFrame(val width: Int, val height: Int, val bytes: ByteArray) {
    init {
        require(width >= TransferProtocol.MIN_QR_FRAME_SIDE) {
            "QR frame width is below its limit"
        }
        require(height >= TransferProtocol.MIN_QR_FRAME_SIDE) {
            "QR frame height is below its limit"
        }
        require(Math.multiplyExact(width, height) == bytes.size) {
            "QR frame byte count does not match its dimensions"
        }
        require(bytes.size.toLong() <= TransferProtocol.MAX_QR_FRAME_PIXELS) {
            "QR frame exceeds its pixel limit"
        }
    }
}

/** Owns one validated row-major QR module grid. */
internal class QrCodeGrid private constructor(
    val dimension: Int,
    private val packedModules: ByteArray
) {
    /** Returns whether one module is dark. */
    fun isDark(row: Int, column: Int): Boolean {
        require(row in 0 until dimension) { "QR module row is outside the grid" }
        require(column in 0 until dimension) { "QR module column is outside the grid" }
        val moduleIndex = Math.addExact(Math.multiplyExact(row, dimension), column)
        val bitMask = 1 shl (7 - moduleIndex % Byte.SIZE_BITS)
        return packedModules[moduleIndex / Byte.SIZE_BITS].toInt() and bitMask != 0
    }

    companion object {
        /** Decodes one exact packet returned by the isolated Rust encoder. */
        fun decode(packet: ByteArray, expectedDimension: Long): QrCodeGrid {
            if (packet.size < QR_PACKET_HEADER_BYTES) {
                throw TransferException(TransferFailure.InvalidResponse)
            }
            if (!packet.copyOfRange(0, QR_PACKET_MAGIC.size).contentEquals(QR_PACKET_MAGIC)) {
                throw TransferException(TransferFailure.InvalidResponse)
            }
            if (packet[4].toInt() and 0xff != QR_PACKET_VERSION) {
                throw TransferException(TransferFailure.InvalidResponse)
            }
            val dimension = unsignedShort(packet, 5)
            if (
                dimension.toLong() != expectedDimension ||
                dimension !in 21..TransferProtocol.MAX_QR_MODULES_PER_SIDE.toInt() ||
                (dimension - 17) % 4 != 0
            ) {
                throw TransferException(TransferFailure.InvalidResponse)
            }
            val packedByteCount = unsignedInt(packet, 7)
            val moduleCount = Math.multiplyExact(dimension, dimension)
            val expectedPackedByteCount = Math.addExact(moduleCount, 7) / Byte.SIZE_BITS
            if (
                packedByteCount != expectedPackedByteCount ||
                packet.size != Math.addExact(QR_PACKET_HEADER_BYTES, packedByteCount)
            ) {
                throw TransferException(TransferFailure.InvalidResponse)
            }
            val packedModules = packet.copyOfRange(QR_PACKET_HEADER_BYTES, packet.size)
            val unusedBits = Math.multiplyExact(packedByteCount, Byte.SIZE_BITS) - moduleCount
            if (unusedBits > 0) {
                val unusedMask = (1 shl unusedBits) - 1
                if (packedModules.last().toInt() and unusedMask != 0) {
                    throw TransferException(TransferFailure.InvalidResponse)
                }
            }
            return QrCodeGrid(dimension = dimension, packedModules = packedModules)
        }

        /** Reads one unsigned network-order short. */
        private fun unsignedShort(packet: ByteArray, offset: Int): Int =
            ((packet[offset].toInt() and 0xff) shl Byte.SIZE_BITS) or
                (packet[offset + 1].toInt() and 0xff)

        /** Reads one bounded unsigned network-order integer. */
        private fun unsignedInt(packet: ByteArray, offset: Int): Int {
            var value = 0L
            repeat(Int.SIZE_BYTES) { byteOffset ->
                value = value shl Byte.SIZE_BITS
                value = value or (packet[offset + byteOffset].toLong() and 0xffL)
            }
            if (value > Int.MAX_VALUE) {
                throw TransferException(TransferFailure.InvalidResponse)
            }
            return value.toInt()
        }
    }
}

/** Owns one immutable validated envelope ready for an NFC NDEF record. */
internal class NfcTransferEnvelope private constructor(bytes: ByteArray) : AutoCloseable {
    private val bytes = bytes.copyOf()
    private val closed = AtomicBoolean(false)

    /** Returns the immutable envelope byte count. */
    val byteCount: Int
        get() = bytes.size

    /** Returns one defensive payload copy for a single explicit tag write. */
    fun copyBytes(): ByteArray = synchronized(bytes) {
        check(!closed.get()) { "NFC transfer envelope is closed" }
        bytes.copyOf()
    }

    /** Clears the retained envelope bytes exactly once. */
    override fun close() {
        synchronized(bytes) {
            if (closed.compareAndSet(false, true)) {
                bytes.fill(0)
            }
        }
    }

    companion object {
        /** Validates one exact envelope returned by the isolated Rust encoder. */
        fun fromIsolatedResult(
            bytes: ByteArray,
            expectedTextBytes: Long,
            tagLabel: String?
        ): NfcTransferEnvelope {
            if (
                expectedTextBytes !in
                TransferProtocol.MIN_INPUT_BYTES..TransferProtocol.MAX_NFC_TEXT_BYTES ||
                bytes.size.toLong() !=
                expectedTextBytes +
                TransferProtocol.TRANSFER_ENVELOPE_BASE_OVERHEAD_BYTES +
                (tagLabel?.length ?: 0) ||
                !isValidNfcTagLabel(tagLabel) ||
                bytes.size.toLong() > TransferProtocol.MAX_NFC_ENVELOPE_BYTES
            ) {
                throw TransferException(TransferFailure.InvalidResponse)
            }
            return NfcTransferEnvelope(bytes)
        }
    }
}

/** Identifies the inert NFC record representation that supplied received text. */
internal enum class NfcTextSource(val wireValue: Long) {
    BeauTyXT(TransferProtocol.NFC_SOURCE_BEAUTYXT),
    Text(TransferProtocol.NFC_SOURCE_TEXT),
    Uri(TransferProtocol.NFC_SOURCE_URI),
    PlainTextMime(TransferProtocol.NFC_SOURCE_PLAIN_TEXT_MIME),
    MarkdownMime(TransferProtocol.NFC_SOURCE_MARKDOWN_MIME),
    HtmlMime(TransferProtocol.NFC_SOURCE_HTML_MIME),
    SmartPoster(TransferProtocol.NFC_SOURCE_SMART_POSTER);

    companion object {
        /** Returns one source only for its stable isolated wire value. */
        fun fromWire(value: Long): NfcTextSource = entries.firstOrNull { source ->
            source.wireValue == value
        } ?: throw TransferException(TransferFailure.InvalidResponse)
    }
}

/** Contains transient review metadata for one decoded NFC message. */
internal data class ReceivedNfcMetadata(
    val source: NfcTextSource,
    val tagLabel: String? = null,
    val smartPosterTitle: String? = null,
    val reportedTagId: String? = null
) {
    init {
        require(isValidNfcTagLabel(tagLabel)) { "received NFC tag label is invalid" }
        require(isValidReportedNfcTagId(reportedTagId)) { "reported NFC tag ID is invalid" }
        require(smartPosterTitle == null || smartPosterTitle.isNotEmpty()) {
            "Smart Poster title must not be empty"
        }
        require(tagLabel == null || source == NfcTextSource.BeauTyXT) {
            "only BeauTyXT NFC records may carry a tag label"
        }
        require(smartPosterTitle == null || source == NfcTextSource.SmartPoster) {
            "only Smart Posters may carry a title"
        }
    }
}

/** Owns one validated text value returned by an isolated decoder. */
internal data class ReceivedTransferText(
    val text: String,
    val format: DocumentFormat,
    val nfcMetadata: ReceivedNfcMetadata? = null
)

/** Decodes one exact structured NFC result packet from the isolated worker. */
internal object NfcResultPacket {
    /** Returns the received text after validating all packet fields and callback details. */
    fun decode(
        packet: ByteArray,
        expectedSource: Long,
        expectedFormat: Long
    ): ReceivedTransferText {
        if (
            packet.size !in NFC_PACKET_HEADER_BYTES..TransferProtocol.MAX_NFC_RESULT_PACKET_BYTES
                .toInt() ||
            !packet.copyOfRange(0, NFC_PACKET_MAGIC.size).contentEquals(NFC_PACKET_MAGIC) ||
            packet[4].toInt() and 0xff != NFC_PACKET_VERSION
        ) {
            throw TransferException(TransferFailure.InvalidResponse)
        }
        val source =
            NfcTextSource.fromWire(
                (packet[NFC_PACKET_SOURCE_OFFSET].toInt() and 0xff).toLong()
            )
        val format =
            shareDocumentFormatFromWire(
                (packet[NFC_PACKET_FORMAT_OFFSET].toInt() and 0xff).toLong()
            )
        val tagLabelLength = packet[NFC_PACKET_TAG_LABEL_LENGTH_OFFSET].toInt() and 0xff
        val titleLength = unsignedInt(packet, NFC_PACKET_TITLE_LENGTH_OFFSET)
        val textLength = unsignedInt(packet, NFC_PACKET_TEXT_LENGTH_OFFSET)
        val expectedPacketBytes =
            try {
                Math.addExact(
                    NFC_PACKET_HEADER_BYTES,
                    Math.addExact(tagLabelLength, Math.addExact(titleLength, textLength))
                )
            } catch (failure: ArithmeticException) {
                throw TransferException(TransferFailure.InvalidResponse, failure)
            }
        if (
            packet.size != expectedPacketBytes ||
            source.wireValue != expectedSource ||
            format.toTransferWireValue() != expectedFormat ||
            textLength > TransferProtocol.MAX_NFC_DECODED_TEXT_BYTES ||
            tagLabelLength > TransferProtocol.MAX_NFC_TAG_LABEL_BYTES
        ) {
            throw TransferException(TransferFailure.InvalidResponse)
        }
        var offset = NFC_PACKET_HEADER_BYTES
        val tagLabel = decodeUtf8(packet, offset, tagLabelLength).ifEmpty { null }
        offset = Math.addExact(offset, tagLabelLength)
        val smartPosterTitle = decodeUtf8(packet, offset, titleLength).ifEmpty { null }
        offset = Math.addExact(offset, titleLength)
        val text = decodeUtf8(packet, offset, textLength)
        if (
            !isValidNfcTagLabel(tagLabel) ||
            (tagLabel != null && source != NfcTextSource.BeauTyXT) ||
            (smartPosterTitle != null && source != NfcTextSource.SmartPoster) ||
            (source != NfcTextSource.BeauTyXT && sourceFormat(source) != format)
        ) {
            throw TransferException(TransferFailure.InvalidResponse)
        }
        return ReceivedTransferText(
            text = text,
            format = format,
            nfcMetadata =
                ReceivedNfcMetadata(
                    source = source,
                    tagLabel = tagLabel,
                    smartPosterTitle = smartPosterTitle
                )
        )
    }

    /** Strictly decodes one exact UTF-8 packet slice. */
    private fun decodeUtf8(packet: ByteArray, offset: Int, length: Int): String = try {
        packet.decodeToString(
            startIndex = offset,
            endIndex = Math.addExact(offset, length),
            throwOnInvalidSequence = true
        )
    } catch (failure: CharacterCodingException) {
        throw TransferException(TransferFailure.InvalidResponse, failure)
    } catch (failure: IndexOutOfBoundsException) {
        throw TransferException(TransferFailure.InvalidResponse, failure)
    }

    /** Reads one bounded unsigned network-order integer. */
    private fun unsignedInt(packet: ByteArray, offset: Int): Int {
        var value = 0L
        repeat(Int.SIZE_BYTES) { byteOffset ->
            value = value shl Byte.SIZE_BITS
            value = value or (packet[offset + byteOffset].toLong() and 0xffL)
        }
        if (value > Int.MAX_VALUE) {
            throw TransferException(TransferFailure.InvalidResponse)
        }
        return value.toInt()
    }

    /** Returns the only editor format allowed for one standard NFC source. */
    private fun sourceFormat(source: NfcTextSource): DocumentFormat = when (source) {
        NfcTextSource.BeauTyXT -> throw TransferException(TransferFailure.InvalidResponse)

        NfcTextSource.Text,
        NfcTextSource.Uri,
        NfcTextSource.PlainTextMime,
        NfcTextSource.SmartPoster -> DocumentFormat.PlainText

        NfcTextSource.MarkdownMime,
        NfcTextSource.HtmlMime -> DocumentFormat.Markdown
    }
}

/** Contains one canonical terminal status from the isolated worker. */
internal data class TransferTerminalStatus(
    val state: Int,
    val resultCode: Int,
    val inputBytes: Long,
    val outputBytes: Long,
    val detailZero: Long,
    val detailOne: Long
) {
    /** Returns a sanitized failure or null for one canonical success. */
    fun failureOrNull(request: TransferJobRequest): TransferFailure? {
        if (
            inputBytes < 0L ||
            outputBytes !in
            TransferProtocol.MIN_OUTPUT_BYTES..TransferProtocol.MAX_OUTPUT_BYTES ||
            detailZero < 0L ||
            detailOne < 0L
        ) {
            return TransferFailure.InvalidResponse
        }
        return when (state) {
            TransferProtocol.STATE_COMPLETE ->
                if (
                    resultCode == TransferProtocol.RESULT_SUCCESS &&
                    inputBytes == request.expectedInputBytes &&
                    hasValidSuccessDetails(request)
                ) {
                    null
                } else {
                    TransferFailure.InvalidResponse
                }

            TransferProtocol.STATE_CANCELLED ->
                if (
                    resultCode == TransferProtocol.RESULT_CANCELLED &&
                    hasZeroStatistics()
                ) {
                    TransferFailure.ProcessingFailed
                } else {
                    TransferFailure.InvalidResponse
                }

            TransferProtocol.STATE_FAILED ->
                if (hasZeroStatistics()) {
                    failureForResultCode(resultCode)
                } else {
                    TransferFailure.InvalidResponse
                }

            else -> TransferFailure.InvalidResponse
        }
    }

    /** Returns whether success details agree with the exact operation. */
    private fun hasValidSuccessDetails(request: TransferJobRequest): Boolean = when (
        request.operation
    ) {
        TransferProtocol.OPERATION_ENCODE_QR ->
            outputBytes > 0L &&
                detailZero in 21L..TransferProtocol.MAX_QR_MODULES_PER_SIDE &&
                detailOne == request.argumentZero

        TransferProtocol.OPERATION_DECODE_QR ->
            outputBytes <= TransferProtocol.MAX_QR_TEXT_BYTES &&
                isTransferFormat(detailZero) &&
                detailOne == outputBytes

        TransferProtocol.OPERATION_ENCODE_NFC ->
            outputBytes ==
                request.expectedInputBytes +
                TransferProtocol.TRANSFER_ENVELOPE_BASE_OVERHEAD_BYTES +
                packedNfcTagLabelLength(request.argumentOne) &&
                detailZero == request.argumentZero &&
                detailOne == request.argumentOne

        TransferProtocol.OPERATION_DECODE_NFC ->
            outputBytes in 1L..TransferProtocol.MAX_NFC_RESULT_PACKET_BYTES &&
                isNfcSource(detailZero) &&
                isTransferFormat(detailOne)

        else -> false
    }

    /** Returns whether all terminal statistics are zero. */
    private fun hasZeroStatistics(): Boolean = inputBytes == 0L &&
        outputBytes == 0L &&
        detailZero == 0L &&
        detailOne == 0L

    /** Maps one worker result to a sanitized presentation failure. */
    private fun failureForResultCode(resultCode: Int): TransferFailure = when (resultCode) {
        TransferProtocol.RESULT_TIMEOUT -> TransferFailure.TimedOut

        TransferProtocol.RESULT_INPUT_LIMIT -> TransferFailure.TooLarge

        TransferProtocol.RESULT_INVALID_UTF8 -> TransferFailure.InvalidUtf8

        TransferProtocol.RESULT_INVALID_NDEF -> TransferFailure.InvalidNdef

        TransferProtocol.RESULT_AMBIGUOUS_NDEF -> TransferFailure.AmbiguousNdef

        TransferProtocol.RESULT_UNSUPPORTED -> TransferFailure.Unsupported

        TransferProtocol.RESULT_NOT_FOUND -> TransferFailure.NoQrCode

        TransferProtocol.RESULT_AMBIGUOUS -> TransferFailure.AmbiguousQr

        TransferProtocol.RESULT_INPUT_IO -> TransferFailure.SnapshotFailed

        TransferProtocol.RESULT_OUTPUT_LIMIT,
        TransferProtocol.RESULT_OUTPUT_IO,
        TransferProtocol.RESULT_INTERNAL -> TransferFailure.ProcessingFailed

        TransferProtocol.RESULT_INPUT_LENGTH_MISMATCH,
        TransferProtocol.RESULT_INVALID_DESCRIPTOR,
        TransferProtocol.RESULT_INVALID_INPUT -> TransferFailure.InvalidResponse

        else -> TransferFailure.InvalidResponse
    }
}

/** Returns whether one value is a stable NFC source classification. */
internal fun isNfcSource(value: Long): Boolean =
    value in TransferProtocol.NFC_SOURCE_BEAUTYXT..TransferProtocol.NFC_SOURCE_SMART_POSTER

/** Describes one exact isolated transfer request. */
internal data class TransferJobRequest(
    val operation: Int,
    val expectedInputBytes: Long,
    val argumentZero: Long,
    val argumentOne: Long,
    val timeoutMillis: Long
) {
    init {
        require(
            dev.soupslurpr.beautyxt.transfer.isValidTransferRequest(
                operation = operation,
                expectedInputBytes = expectedInputBytes,
                argumentZero = argumentZero,
                argumentOne = argumentOne,
                timeoutMillis = timeoutMillis
            )
        ) {
            "isolated transfer request is invalid"
        }
    }
}

/** Returns whether one value is a stable transfer format. */
internal fun isTransferFormat(value: Long): Boolean = value == TransferProtocol.FORMAT_PLAIN_TEXT ||
    value == TransferProtocol.FORMAT_MARKDOWN

/** Returns the stable wire value for one editor format. */
internal fun DocumentFormat.toTransferWireValue(): Long = when (this) {
    DocumentFormat.PlainText -> TransferProtocol.FORMAT_PLAIN_TEXT
    DocumentFormat.Markdown -> TransferProtocol.FORMAT_MARKDOWN
}

/** Returns one editor format only for a stable wire value. */
internal fun shareDocumentFormatFromWire(value: Long): DocumentFormat = when (value) {
    TransferProtocol.FORMAT_PLAIN_TEXT -> DocumentFormat.PlainText
    TransferProtocol.FORMAT_MARKDOWN -> DocumentFormat.Markdown
    else -> throw TransferException(TransferFailure.InvalidResponse)
}
