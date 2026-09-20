package dev.soupslurpr.beautyxt.transfer

/** Defines the stable numeric protocol shared by transfer clients and workers. */
internal object TransferProtocol {
    const val ACCEPT_ACCEPTED = 0
    const val ACCEPT_BUSY = 1
    const val ACCEPT_INVALID_ARGUMENT = 2
    const val ACCEPT_CALLBACK_UNAVAILABLE = 3

    const val STATE_RUNNING = 0
    const val STATE_COMPLETE = 1
    const val STATE_FAILED = 2
    const val STATE_CANCELLED = 3

    const val OPERATION_ENCODE_QR = 1
    const val OPERATION_DECODE_QR = 2
    const val OPERATION_ENCODE_NFC = 3
    const val OPERATION_DECODE_NFC = 4

    const val FORMAT_PLAIN_TEXT = 0L
    const val FORMAT_MARKDOWN = 1L

    const val NFC_SOURCE_BEAUTYXT = 0L
    const val NFC_SOURCE_TEXT = 1L
    const val NFC_SOURCE_URI = 2L
    const val NFC_SOURCE_PLAIN_TEXT_MIME = 3L
    const val NFC_SOURCE_MARKDOWN_MIME = 4L
    const val NFC_SOURCE_HTML_MIME = 5L
    const val NFC_SOURCE_SMART_POSTER = 6L

    const val RESULT_SUCCESS = 0
    const val RESULT_CANCELLED = 1
    const val RESULT_TIMEOUT = 2
    const val RESULT_INPUT_LIMIT = 3
    const val RESULT_INPUT_LENGTH_MISMATCH = 4
    const val RESULT_INVALID_UTF8 = 5
    const val RESULT_OUTPUT_LIMIT = 6
    const val RESULT_INVALID_DESCRIPTOR = 7
    const val RESULT_INPUT_IO = 8
    const val RESULT_OUTPUT_IO = 9
    const val RESULT_UNSUPPORTED = 10
    const val RESULT_NOT_FOUND = 11
    const val RESULT_AMBIGUOUS = 12
    const val RESULT_INVALID_INPUT = 13
    const val RESULT_INTERNAL = 14
    const val RESULT_INVALID_NDEF = 15
    const val RESULT_AMBIGUOUS_NDEF = 16

    const val MIN_INPUT_BYTES = 0L
    const val MAX_QR_TEXT_BYTES = 1_536L
    const val MAX_NFC_MESSAGE_BYTES = 256L * 1024L
    const val MAX_NFC_DECODED_TEXT_BYTES = 256L * 1024L
    const val MAX_NFC_TAG_LABEL_BYTES = 3L
    const val TRANSFER_ENVELOPE_BASE_OVERHEAD_BYTES = 44L
    const val NFC_NDEF_LONG_RECORD_OVERHEAD_BYTES = 54L
    const val MAX_NFC_ENVELOPE_BYTES =
        MAX_NFC_MESSAGE_BYTES - NFC_NDEF_LONG_RECORD_OVERHEAD_BYTES
    const val MAX_NFC_TEXT_BYTES =
        MAX_NFC_ENVELOPE_BYTES -
            TRANSFER_ENVELOPE_BASE_OVERHEAD_BYTES -
            MAX_NFC_TAG_LABEL_BYTES
    const val MIN_TRANSFER_ENVELOPE_BYTES = TRANSFER_ENVELOPE_BASE_OVERHEAD_BYTES
    const val MIN_NFC_MESSAGE_BYTES = 3L
    const val MAX_NFC_RESULT_PACKET_BYTES = MAX_NFC_MESSAGE_BYTES + 64L
    const val MAX_QR_FRAME_PIXELS = 1_280L * 960L
    const val MIN_OUTPUT_BYTES = 0L
    const val MAX_OUTPUT_BYTES = MAX_NFC_RESULT_PACKET_BYTES
    const val MIN_QR_FRAME_SIDE = 48L
    const val MAX_QR_MODULES_PER_SIDE = 177L
    const val MIN_TIMEOUT_MILLIS = 1L
    const val MAX_TIMEOUT_MILLIS = 30_000L

    const val RESULT_VALUE_COUNT = 4
    const val RESULT_INPUT_BYTES_INDEX = 0
    const val RESULT_OUTPUT_BYTES_INDEX = 1
    const val RESULT_DETAIL_ZERO_INDEX = 2
    const val RESULT_DETAIL_ONE_INDEX = 3
}
