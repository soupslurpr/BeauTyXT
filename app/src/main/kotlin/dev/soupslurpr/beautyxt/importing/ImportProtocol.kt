package dev.soupslurpr.beautyxt.importing

import dev.soupslurpr.beautyxt.document.SHA_256_BYTE_COUNT

/** Defines the stable numeric protocol shared by import clients and workers. */
object ImportProtocol {
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
    const val RESULT_INVALID_UTF8 = 2
    const val RESULT_UNSUPPORTED_BOM = 3
    const val RESULT_INPUT_LIMIT = 4
    const val RESULT_OUTPUT_LIMIT = 5
    const val RESULT_TIMEOUT = 6
    const val RESULT_INVALID_DESCRIPTOR = 7
    const val RESULT_INPUT_IO = 8
    const val RESULT_OUTPUT_IO = 9
    const val RESULT_INTERNAL = 10

    const val SOURCE_FLAG_UTF8_BOM = 1 shl 0
    const val SOURCE_FLAG_CRLF = 1 shl 1
    const val SOURCE_FLAG_BARE_LF = 1 shl 2
    const val SOURCE_FLAG_BARE_CR = 1 shl 3

    const val MIN_BYTE_LIMIT = 1L
    const val MAX_BYTE_LIMIT = 268_435_456L
    const val MIN_TIMEOUT_MILLIS = 1L
    const val MAX_TIMEOUT_MILLIS = 900_000L

    internal const val SOURCE_FLAGS_MASK =
        SOURCE_FLAG_UTF8_BOM or SOURCE_FLAG_CRLF or SOURCE_FLAG_BARE_LF or SOURCE_FLAG_BARE_CR
    internal const val RESULT_VALUE_COUNT = 3
    internal const val RESULT_INPUT_BYTES_INDEX = 0
    internal const val RESULT_OUTPUT_BYTES_INDEX = 1
    internal const val RESULT_SOURCE_FLAGS_INDEX = 2
    internal const val RESULT_SHA_256_BYTE_COUNT = SHA_256_BYTE_COUNT
}
