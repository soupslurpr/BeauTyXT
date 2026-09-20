package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.document.DocumentRemovalAction
import dev.soupslurpr.beautyxt.document.DocumentRemovalCapabilities
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.testing.TestEditorDocumentSnapshot
import dev.soupslurpr.beautyxt.transfer.TransferProtocol
import dev.soupslurpr.beautyxt.transfer.client.NfcTransferEnvelope
import dev.soupslurpr.beautyxt.transfer.client.NfcTransferProcessor
import dev.soupslurpr.beautyxt.transfer.client.QrCodeGrid
import dev.soupslurpr.beautyxt.transfer.client.QrLuminanceFrame
import dev.soupslurpr.beautyxt.transfer.client.QrTransferProcessor
import dev.soupslurpr.beautyxt.transfer.client.ReceivedTransferText

/** Records deterministic QR encoding calls without crossing an Android process. */
internal class TestQrTransferProcessor : QrTransferProcessor {
    val encodedTexts = mutableListOf<String>()
    val encodedByteCounts = mutableListOf<Long>()
    val encodedFormats = mutableListOf<DocumentFormat>()
    val grid = testQrCodeGrid()

    /** Records and returns one deterministic bounded QR grid. */
    override suspend fun encodeQr(
        snapshot: EditorDocumentSnapshot,
        expectedBytes: Long,
        format: DocumentFormat
    ): QrCodeGrid {
        val testSnapshot = snapshot as TestEditorDocumentSnapshot
        require(testSnapshot.byteLength == expectedBytes) {
            "test QR processor received an unexpected byte count"
        }
        encodedTexts += testSnapshot.text
        encodedByteCounts += expectedBytes
        encodedFormats += format
        return grid
    }

    /** Rejects camera decoding because editor-session tests only encode. */
    override suspend fun decodeQr(frame: QrLuminanceFrame): ReceivedTransferText =
        error("test QR decoder is unavailable")
}

/** Records deterministic NFC encoding calls without crossing an Android process. */
internal class TestNfcTransferProcessor : NfcTransferProcessor {
    val encodedTexts = mutableListOf<String>()
    val encodedByteCounts = mutableListOf<Long>()
    val encodedFormats = mutableListOf<DocumentFormat>()
    val encodedTagLabels = mutableListOf<String?>()
    lateinit var envelope: NfcTransferEnvelope
        private set

    /** Records and returns one deterministic bounded NFC envelope. */
    override suspend fun encodeNfc(
        snapshot: EditorDocumentSnapshot,
        expectedBytes: Long,
        format: DocumentFormat,
        tagLabel: String?
    ): NfcTransferEnvelope {
        val testSnapshot = snapshot as TestEditorDocumentSnapshot
        require(testSnapshot.byteLength == expectedBytes) {
            "test NFC processor received an unexpected byte count"
        }
        encodedTexts += testSnapshot.text
        encodedByteCounts += expectedBytes
        encodedFormats += format
        encodedTagLabels += tagLabel
        envelope =
            NfcTransferEnvelope.fromIsolatedResult(
                bytes =
                    ByteArray(
                        Math.toIntExact(
                            expectedBytes +
                                TransferProtocol.TRANSFER_ENVELOPE_BASE_OVERHEAD_BYTES +
                                (tagLabel?.length ?: 0)
                        )
                    ),
                expectedTextBytes = expectedBytes,
                tagLabel = tagLabel
            )
        return envelope
    }

    /** Rejects tag decoding because editor-session tests only encode. */
    override suspend fun decodeNfc(message: ByteArray): ReceivedTransferText =
        error("test NFC decoder is unavailable")
}

/** Creates one structurally valid deterministic packed QR grid for session tests. */
private fun testQrCodeGrid(): QrCodeGrid {
    val dimension = 21
    val moduleCount = dimension * dimension
    val packedByteCount = (moduleCount + 7) / Byte.SIZE_BITS
    val packet = ByteArray(11 + packedByteCount)
    packet[0] = 0x42
    packet[1] = 0x58
    packet[2] = 0x51
    packet[3] = 0x52
    packet[4] = 1
    packet[5] = (dimension ushr Byte.SIZE_BITS).toByte()
    packet[6] = dimension.toByte()
    packet[7] = (packedByteCount ushr 24).toByte()
    packet[8] = (packedByteCount ushr 16).toByte()
    packet[9] = (packedByteCount ushr Byte.SIZE_BITS).toByte()
    packet[10] = packedByteCount.toByte()
    return QrCodeGrid.decode(packet = packet, expectedDimension = dimension.toLong())
}

/** Records deterministic retained-source saves for editor-session tests. */
internal class TestEditorDocumentSource(
    private val encodedUri: String? = null,
    private val removalCapabilities: DocumentRemovalCapabilities =
        DocumentRemovalCapabilities.None,
    private val querySourceRemovalCapabilities: suspend () -> DocumentRemovalCapabilities = {
        removalCapabilities
    },
    private val removeSource: suspend (DocumentRemovalAction) -> Unit = {},
    private val save: suspend (TestEditorDocumentSnapshot, Int) -> Unit = { _, _ -> }
) : WritableEditorDocumentSource,
    RemovableEditorDocumentSource {
    val savedRevisions = mutableListOf<Long>()
    val savedTexts = mutableListOf<String>()
    var closeCallCount = 0
        private set
    var removalCapabilityQueryCount = 0
        private set
    val removalActions = mutableListOf<DocumentRemovalAction>()

    /** Returns whether this source has the configured exact test URI. */
    override fun matchesSourceUri(encodedUri: String): Boolean = this.encodedUri == encodedUri

    /** Returns the configured URI for deterministic share tests. */
    override fun encodedShareUri(): String? = encodedUri

    /** Returns deterministic provider removal capabilities. */
    override suspend fun queryRemovalCapabilities(): DocumentRemovalCapabilities {
        removalCapabilityQueryCount = Math.incrementExact(removalCapabilityQueryCount)
        return querySourceRemovalCapabilities()
    }

    /** Records one deterministic provider removal request. */
    override suspend fun remove(action: DocumentRemovalAction) {
        removalActions += action
        removeSource(action)
    }

    /** Saves and consumes one exact test snapshot. */
    override suspend fun saveRevision(snapshot: EditorDocumentSnapshot, expectedBytes: Long) {
        val testSnapshot = snapshot as TestEditorDocumentSnapshot
        require(testSnapshot.byteLength == expectedBytes) {
            "test source received an unexpected byte count"
        }
        savedRevisions += testSnapshot.revision
        savedTexts += testSnapshot.text
        save(testSnapshot, savedRevisions.size)
        if (!testSnapshot.isClosed) {
            testSnapshot.consume()
        }
    }

    /** Records one retained-source ownership release. */
    override fun close() {
        closeCallCount = Math.incrementExact(closeCallCount)
    }
}
