package dev.soupslurpr.beautyxt.exporting.client

// Shared by the instrumentation client and its separately installed provider APK.
internal const val PATH_SINK = "sink"
internal const val PATH_PAUSED_SINK = "paused-sink"
internal const val PATH_BLOCKED_SINK = "blocked-sink"
internal const val PATH_FAILED_SINK = "failed-sink"
internal const val PATH_DRAINED_FAILED_SINK = "drained-failed-sink"
internal const val OPERATION_TOKEN_QUERY_PARAMETER = "operation"
internal const val OPERATION_TOKEN_KEY = "operation-token"
internal const val OPERATION_TOKEN_HEX_CHARS = 32
internal const val METHOD_RESET_SINK = "reset-sink"
internal const val METHOD_SINK_STATUS = "sink-status"
internal const val METHOD_RELEASE_PAUSED_SINK = "release-paused-sink"
internal const val STATUS_OPENED_KEY = "opened"
internal const val STATUS_TERMINAL_KEY = "terminal"
internal const val STATUS_ERROR_KEY = "error"
internal const val STATUS_PAUSED_KEY = "paused"
internal const val STATUS_BYTE_COUNT_KEY = "byte-count"
internal const val STATUS_SHA256_KEY = "sha256"
internal const val STATUS_PREFIX_KEY = "prefix"
internal const val STATUS_SUFFIX_KEY = "suffix"
internal const val MAX_SINK_BYTES = 256L * 1024L * 1024L
internal const val MAX_STATUS_SAMPLE_BYTES = 256
internal const val DRAINED_FAILURE_BYTES = 17L
internal const val BLOCKED_PIPE_BYTES = 4096
