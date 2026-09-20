package dev.soupslurpr.beautyxt.ui.transfer

import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.transfer.TransferProtocol
import dev.soupslurpr.beautyxt.transfer.client.NfcResultPacket
import dev.soupslurpr.beautyxt.transfer.client.NfcTextSource
import dev.soupslurpr.beautyxt.transfer.client.NfcTransferEnvelope
import dev.soupslurpr.beautyxt.transfer.client.TransferException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

private const val NFC_RESULT_PACKET_HEADER_BYTES = 16

/** Verifies ownership and strict decoding at the Android side of the isolated NFC boundary. */
class NfcTransferValidationTest {
    /** Owns isolated envelope output defensively and rejects access after explicit clearing. */
    @Test
    fun clearsOwnedEnvelope() {
        val isolatedBytes =
            ByteArray(TransferProtocol.MIN_TRANSFER_ENVELOPE_BYTES.toInt()) { 1 }
        val envelope =
            NfcTransferEnvelope.fromIsolatedResult(
                bytes = isolatedBytes,
                expectedTextBytes = 0L,
                tagLabel = null
            )
        isolatedBytes.fill(2)

        assertArrayEquals(
            ByteArray(TransferProtocol.MIN_TRANSFER_ENVELOPE_BYTES.toInt()) { 1 },
            envelope.copyBytes()
        )
        envelope.close()
        envelope.close()
        assertThrows(IllegalStateException::class.java) {
            envelope.copyBytes()
        }
    }

    /** Accepts the exact maximum envelope including a three-character tag label. */
    @Test
    fun acceptsMaximumLabeledEnvelope() {
        val envelope =
            NfcTransferEnvelope.fromIsolatedResult(
                bytes = ByteArray(TransferProtocol.MAX_NFC_ENVELOPE_BYTES.toInt()),
                expectedTextBytes = TransferProtocol.MAX_NFC_TEXT_BYTES,
                tagLabel = "A01"
            )

        assertEquals(TransferProtocol.MAX_NFC_ENVELOPE_BYTES.toInt(), envelope.byteCount)
        envelope.close()
    }

    /** Rejects an envelope whose byte count or optional label disagrees with the request. */
    @Test
    fun rejectsMismatchedEnvelopeMetadata() {
        assertThrows(TransferException::class.java) {
            NfcTransferEnvelope.fromIsolatedResult(
                bytes = ByteArray(TransferProtocol.MIN_TRANSFER_ENVELOPE_BYTES.toInt()),
                expectedTextBytes = 0L,
                tagLabel = "A01"
            )
        }
        assertThrows(TransferException::class.java) {
            NfcTransferEnvelope.fromIsolatedResult(
                bytes = ByteArray(TransferProtocol.MIN_TRANSFER_ENVELOPE_BYTES.toInt()),
                expectedTextBytes = 0L,
                tagLabel = "a01"
            )
        }
    }

    /** Decodes one URI as inert plain text with its source retained for review. */
    @Test
    fun decodesInertUriResult() {
        val result =
            NfcResultPacket.decode(
                packet =
                    nfcResultPacket(
                        source = NfcTextSource.Uri,
                        format = DocumentFormat.PlainText,
                        text = "https://example.test/path"
                    ),
                expectedSource = TransferProtocol.NFC_SOURCE_URI,
                expectedFormat = TransferProtocol.FORMAT_PLAIN_TEXT
            )

        assertEquals("https://example.test/path", result.text)
        assertEquals(DocumentFormat.PlainText, result.format)
        assertEquals(NfcTextSource.Uri, result.nfcMetadata?.source)
        assertNull(result.nfcMetadata?.tagLabel)
    }

    /** Retains supported HTML source exactly while assigning the safe Markdown presentation. */
    @Test
    fun decodesHtmlSourceForSafeMarkdownPresentation() {
        val html = "<p>Hello <strong>world</strong></p>"
        val result =
            NfcResultPacket.decode(
                packet =
                    nfcResultPacket(
                        source = NfcTextSource.HtmlMime,
                        format = DocumentFormat.Markdown,
                        text = html
                    ),
                expectedSource = TransferProtocol.NFC_SOURCE_HTML_MIME,
                expectedFormat = TransferProtocol.FORMAT_MARKDOWN
            )

        assertEquals(html, result.text)
        assertEquals(DocumentFormat.Markdown, result.format)
        assertEquals(NfcTextSource.HtmlMime, result.nfcMetadata?.source)
    }

    /** Retains only the selected Smart Poster URI and optional review title. */
    @Test
    fun decodesSmartPosterReviewMetadata() {
        val result =
            NfcResultPacket.decode(
                packet =
                    nfcResultPacket(
                        source = NfcTextSource.SmartPoster,
                        format = DocumentFormat.PlainText,
                        title = "Documentation",
                        text = "https://example.test/docs"
                    ),
                expectedSource = TransferProtocol.NFC_SOURCE_SMART_POSTER,
                expectedFormat = TransferProtocol.FORMAT_PLAIN_TEXT
            )

        assertEquals("https://example.test/docs", result.text)
        assertEquals("Documentation", result.nfcMetadata?.smartPosterTitle)
    }

    /** Rejects invalid UTF-8 and metadata forbidden for a standard NFC source. */
    @Test
    fun rejectsMalformedOrContradictoryResultPackets() {
        val malformedUtf8 =
            nfcResultPacket(
                source = NfcTextSource.Text,
                format = DocumentFormat.PlainText,
                textBytes = byteArrayOf(0xc3.toByte())
            )
        assertThrows(TransferException::class.java) {
            NfcResultPacket.decode(
                packet = malformedUtf8,
                expectedSource = TransferProtocol.NFC_SOURCE_TEXT,
                expectedFormat = TransferProtocol.FORMAT_PLAIN_TEXT
            )
        }

        val labeledStandardText =
            nfcResultPacket(
                source = NfcTextSource.Text,
                format = DocumentFormat.PlainText,
                tagLabel = "A01",
                text = "hello"
            )
        assertThrows(TransferException::class.java) {
            NfcResultPacket.decode(
                packet = labeledStandardText,
                expectedSource = TransferProtocol.NFC_SOURCE_TEXT,
                expectedFormat = TransferProtocol.FORMAT_PLAIN_TEXT
            )
        }
    }
}

/** Builds one deterministic isolated NFC result packet for client-boundary tests. */
private fun nfcResultPacket(
    source: NfcTextSource,
    format: DocumentFormat,
    tagLabel: String = "",
    title: String = "",
    text: String = "",
    textBytes: ByteArray = text.toByteArray()
): ByteArray {
    val labelBytes = tagLabel.toByteArray()
    val titleBytes = title.toByteArray()
    val packet =
        ByteArray(
            NFC_RESULT_PACKET_HEADER_BYTES +
                labelBytes.size +
                titleBytes.size +
                textBytes.size
        )
    packet[0] = 0x42
    packet[1] = 0x58
    packet[2] = 0x4e
    packet[3] = 0x46
    packet[4] = 1
    packet[5] = source.wireValue.toByte()
    packet[6] =
        when (format) {
            DocumentFormat.PlainText -> TransferProtocol.FORMAT_PLAIN_TEXT.toByte()
            DocumentFormat.Markdown -> TransferProtocol.FORMAT_MARKDOWN.toByte()
        }
    packet[7] = labelBytes.size.toByte()
    writeUnsignedInt(packet, offset = 8, value = titleBytes.size)
    writeUnsignedInt(packet, offset = 12, value = textBytes.size)
    var offset = NFC_RESULT_PACKET_HEADER_BYTES
    labelBytes.copyInto(packet, destinationOffset = offset)
    offset += labelBytes.size
    titleBytes.copyInto(packet, destinationOffset = offset)
    offset += titleBytes.size
    textBytes.copyInto(packet, destinationOffset = offset)
    return packet
}

/** Writes one non-negative network-order integer into a test packet. */
private fun writeUnsignedInt(packet: ByteArray, offset: Int, value: Int) {
    require(value >= 0) { "test packet value must be non-negative" }
    repeat(Int.SIZE_BYTES) { byteOffset ->
        val shift = (Int.SIZE_BYTES - byteOffset - 1) * Byte.SIZE_BITS
        packet[offset + byteOffset] = (value ushr shift).toByte()
    }
}
