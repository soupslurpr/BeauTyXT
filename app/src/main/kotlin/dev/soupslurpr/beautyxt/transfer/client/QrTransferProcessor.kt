package dev.soupslurpr.beautyxt.transfer.client

import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot

/** Encodes and decodes bounded QR transfers outside the application process. */
internal interface QrTransferProcessor {
    /** Encodes one exact editor revision into a packed QR module grid. */
    suspend fun encodeQr(
        snapshot: EditorDocumentSnapshot,
        expectedBytes: Long,
        format: DocumentFormat
    ): QrCodeGrid

    /** Decodes one exact grayscale frame into validated transfer text. */
    suspend fun decodeQr(frame: QrLuminanceFrame): ReceivedTransferText
}
