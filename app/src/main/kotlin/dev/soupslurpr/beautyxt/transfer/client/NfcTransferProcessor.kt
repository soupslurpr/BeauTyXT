package dev.soupslurpr.beautyxt.transfer.client

import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot

/** Encodes and decodes bounded NFC transfers outside the application process. */
internal interface NfcTransferProcessor {
    /** Encodes one exact editor revision into a validated NFC envelope. */
    suspend fun encodeNfc(
        snapshot: EditorDocumentSnapshot,
        expectedBytes: Long,
        format: DocumentFormat,
        tagLabel: String?
    ): NfcTransferEnvelope

    /** Decodes one complete bounded NDEF message into validated inert text. */
    suspend fun decodeNfc(message: ByteArray): ReceivedTransferText
}
