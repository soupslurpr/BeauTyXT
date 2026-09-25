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

    /** Owns a decoder for one foreground scanning session, including idle waits. */
    suspend fun <T> withQrDecoder(operation: suspend (QrFrameDecoder) -> T): T =
        operation(QrFrameDecoder(::decodeQr))
}

/** Borrows one scan session's decoder; it must not escape its owning operation. */
internal fun interface QrFrameDecoder {
    /** Decodes one bounded frame, serially with every other frame in the session. */
    suspend fun decodeQr(frame: QrLuminanceFrame): ReceivedTransferText
}

/** Only completed content-level misses allow another frame in the same worker. */
internal val TransferFailure.canContinueQrScan: Boolean
    get() = this == TransferFailure.NoQrCode ||
        this == TransferFailure.Unsupported ||
        this == TransferFailure.AmbiguousQr
