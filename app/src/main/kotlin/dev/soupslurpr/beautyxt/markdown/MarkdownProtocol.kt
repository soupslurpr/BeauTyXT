package dev.soupslurpr.beautyxt.markdown

/** Defines the stable numeric protocol shared by Markdown clients and workers. */
internal object MarkdownProtocol {
    const val ACCEPT_ACCEPTED = 0
    const val ACCEPT_BUSY = 1
    const val ACCEPT_INVALID_ARGUMENT = 2
    const val ACCEPT_CALLBACK_UNAVAILABLE = 3

    const val STATE_RUNNING = 0
    const val STATE_COMPLETE = 1
    const val STATE_FAILED = 2
    const val STATE_CANCELLED = 3

    const val RESULT_SUCCESS = 0
    const val RESULT_CANCELLED = 1
    const val RESULT_TIMEOUT = 2
    const val RESULT_INPUT_LIMIT = 3
    const val RESULT_INPUT_LENGTH_MISMATCH = 4
    const val RESULT_INVALID_UTF8 = 5
    const val RESULT_RENDER_LIMIT = 6
    const val RESULT_INVALID_DESCRIPTOR = 7
    const val RESULT_INPUT_IO = 8
    const val RESULT_OUTPUT_IO = 9
    const val RESULT_INTERNAL = 10

    const val DOCUMENT_FLAG_RAW_HTML = 1
    const val DOCUMENT_FLAGS_MASK = DOCUMENT_FLAG_RAW_HTML

    const val MIN_INPUT_BYTES = 0L
    const val MAX_INPUT_BYTES = 16L * 1024L * 1024L
    const val MIN_PACKET_BYTES = 1L
    const val MAX_PACKET_BYTES = 32L * 1024L * 1024L
    const val MIN_TIMEOUT_MILLIS = 1L
    const val MAX_TIMEOUT_MILLIS = 300_000L
    const val MAX_BLOCK_TEXT_BYTES = 4L * 1024L
    const val MAX_BLOCK_COUNT = 16_384L
    const val MAX_SPAN_COUNT = 131_072L
    const val MAX_SOURCE_MAP_COUNT = 262_144L + MAX_BLOCK_COUNT

    const val RESULT_VALUE_COUNT = 5
    const val RESULT_INPUT_BYTES_INDEX = 0
    const val RESULT_PACKET_BYTES_INDEX = 1
    const val RESULT_BLOCK_COUNT_INDEX = 2
    const val RESULT_SPAN_COUNT_INDEX = 3
    const val RESULT_FLAGS_INDEX = 4
}
