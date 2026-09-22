package dev.soupslurpr.beautyxt.transfer

/** Returns whether one request uses canonical bounded arguments. */
internal fun isValidTransferRequest(
    operation: Int,
    expectedInputBytes: Long,
    argumentZero: Long,
    argumentOne: Long,
    timeoutMillis: Long
): Boolean {
    if (timeoutMillis !in
        TransferProtocol.MIN_TIMEOUT_MILLIS..TransferProtocol.MAX_TIMEOUT_MILLIS
    ) {
        return false
    }
    return when (operation) {
        TransferProtocol.OPERATION_ENCODE_QR ->
            expectedInputBytes in
                TransferProtocol.MIN_INPUT_BYTES..TransferProtocol.MAX_QR_TEXT_BYTES &&
                isTransferFormat(argumentZero) &&
                argumentOne == 0L

        TransferProtocol.OPERATION_DECODE_QR -> {
            val pixels =
                try {
                    Math.multiplyExact(argumentZero, argumentOne)
                } catch (_: ArithmeticException) {
                    return false
                }
            argumentZero >= TransferProtocol.MIN_QR_FRAME_SIDE &&
                argumentOne >= TransferProtocol.MIN_QR_FRAME_SIDE &&
                pixels == expectedInputBytes &&
                pixels <= TransferProtocol.MAX_QR_FRAME_PIXELS
        }

        TransferProtocol.OPERATION_ENCODE_NFC ->
            expectedInputBytes in
                TransferProtocol.MIN_INPUT_BYTES..TransferProtocol.MAX_NFC_TEXT_BYTES &&
                isTransferFormat(argumentZero) &&
                isValidPackedNfcTagLabel(argumentOne)

        TransferProtocol.OPERATION_DECODE_NFC ->
            expectedInputBytes >= TransferProtocol.MIN_NFC_MESSAGE_BYTES &&
                expectedInputBytes <= TransferProtocol.MAX_NFC_MESSAGE_BYTES &&
                argumentZero == 0L &&
                argumentOne == 0L

        else -> false
    }
}

/** Returns whether one value is a stable transfer format. */
private fun isTransferFormat(value: Long): Boolean = value == TransferProtocol.FORMAT_PLAIN_TEXT ||
    value == TransferProtocol.FORMAT_MARKDOWN

/** Returns whether native transfer statistics form one canonical result. */
internal fun areValidTransferStatistics(
    resultCode: Int,
    operation: Int,
    expectedInputBytes: Long,
    argumentZero: Long,
    argumentOne: Long,
    inputBytes: Long,
    outputBytes: Long,
    detailZero: Long,
    detailOne: Long
): Boolean {
    if (
        inputBytes < 0L ||
        outputBytes !in
        TransferProtocol.MIN_OUTPUT_BYTES..TransferProtocol.MAX_OUTPUT_BYTES ||
        detailZero < 0L ||
        detailOne < 0L
    ) {
        return false
    }
    if (resultCode != TransferProtocol.RESULT_SUCCESS) {
        return inputBytes == 0L &&
            outputBytes == 0L &&
            detailZero == 0L &&
            detailOne == 0L
    }
    if (inputBytes != expectedInputBytes) {
        return false
    }
    return when (operation) {
        TransferProtocol.OPERATION_ENCODE_QR ->
            outputBytes > 0L &&
                detailZero in 21L..TransferProtocol.MAX_QR_MODULES_PER_SIDE &&
                isTransferFormat(detailOne)

        TransferProtocol.OPERATION_DECODE_QR ->
            outputBytes <= TransferProtocol.MAX_QR_TEXT_BYTES &&
                isTransferFormat(detailZero) &&
                detailOne == outputBytes

        TransferProtocol.OPERATION_DECODE_NFC ->
            outputBytes in 1L..TransferProtocol.MAX_NFC_RESULT_PACKET_BYTES &&
                isNfcSource(detailZero) &&
                isTransferFormat(detailOne)

        TransferProtocol.OPERATION_ENCODE_NFC ->
            outputBytes ==
                expectedInputBytes +
                TransferProtocol.TRANSFER_ENVELOPE_BASE_OVERHEAD_BYTES +
                packedNfcTagLabelLength(argumentOne) &&
                detailZero == argumentZero &&
                detailOne == argumentOne

        else -> false
    }
}

/** Returns whether one value is a stable NFC source classification. */
private fun isNfcSource(value: Long): Boolean =
    value in TransferProtocol.NFC_SOURCE_BEAUTYXT..TransferProtocol.NFC_SOURCE_SMART_POSTER
