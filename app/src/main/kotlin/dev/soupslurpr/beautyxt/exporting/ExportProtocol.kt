package dev.soupslurpr.beautyxt.exporting

/** Defines the stable numeric protocol shared by export clients and workers. */
object ExportProtocol {
    const val ACCEPT_ACCEPTED = 0
    const val ACCEPT_BUSY = 1
    const val ACCEPT_INVALID_ARGUMENT = 2
    const val ACCEPT_CALLBACK_UNAVAILABLE = 3

    const val MIN_JOB_ID = 1L

    const val STATE_RUNNING = 0
    const val STATE_COMPLETE = 1
    const val STATE_FAILED = 2
    const val STATE_CANCELLED = 3

    const val RESULT_SUCCESS = 0
    const val RESULT_CANCELLED = 1
    const val RESULT_INPUT_LIMIT = 2
    const val RESULT_INPUT_LENGTH_MISMATCH = 3
    const val RESULT_TIMEOUT = 4
    const val RESULT_INVALID_DESCRIPTOR = 5
    const val RESULT_INPUT_IO = 6
    const val RESULT_OUTPUT_IO = 7
    const val RESULT_INTERNAL = 8
    const val RESULT_SOURCE_CONFLICT = 9
    const val RESULT_SOURCE_UNCERTAIN = 10

    const val MIN_BYTE_LIMIT = 0L
    const val MAX_BYTE_LIMIT = 268_435_456L
    const val NO_EXPECTED_SOURCE_BYTE_LENGTH = -1L
    const val MIN_TIMEOUT_MILLIS = 1L
    const val MAX_TIMEOUT_MILLIS = 900_000L

    internal const val RESULT_VALUE_COUNT = 2
    internal const val RESULT_INPUT_BYTES_INDEX = 0
    internal const val RESULT_OUTPUT_BYTES_INDEX = 1
    internal const val SOURCE_RESULT_VALUE_COUNT = 3
    internal const val SOURCE_RESULT_OUTPUT_STARTED_INDEX = 2

    /** Returns whether a job identifier is valid at the service boundary. */
    internal fun isValidJobId(jobId: Long): Boolean = jobId >= MIN_JOB_ID
}
