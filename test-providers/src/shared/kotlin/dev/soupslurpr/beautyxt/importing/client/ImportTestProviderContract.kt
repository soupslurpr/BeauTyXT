package dev.soupslurpr.beautyxt.importing.client

// Shared by the instrumentation client and its separately installed provider APK.
internal const val PATH_SEEKABLE = "seekable"
internal const val PATH_PIPE = "pipe"
internal const val PATH_PAUSED_PIPE = "paused-pipe"
internal const val PATH_FAILED_PIPE = "failed-pipe"
internal const val SIZE_QUERY_PARAMETER = "bytes"
internal const val OPERATION_TOKEN_QUERY_PARAMETER = "operation"
internal const val OPERATION_TOKEN_KEY = "operation-token"
internal const val OPERATION_TOKEN_HEX_CHARS = 32
internal const val METHOD_RESET_PAUSED_PIPE = "reset-paused-pipe"
internal const val METHOD_PAUSED_PIPE_STATUS = "paused-pipe-status"
internal const val METHOD_RELEASE_PAUSED_PIPE = "release-paused-pipe"
internal const val METHOD_OFFER_PERSISTABLE_READ_GRANT = "offer-persistable-read-grant"
internal const val METHOD_REVOKE_READ_GRANT = "revoke-read-grant"
internal const val GRANT_TARGET_PACKAGE_KEY = "target-package"
internal const val GRANT_URI_KEY = "grant-uri"
internal const val STATUS_OPENED_KEY = "opened"
internal const val STATUS_PREFIX_DELIVERED_KEY = "prefix-delivered"
internal const val STATUS_WRITE_FAILED_KEY = "write-failed"
internal const val STATUS_TERMINATED_KEY = "terminated"
internal const val MIN_SOURCE_BYTES = OPERATION_TOKEN_HEX_CHARS + 1L
internal const val MAX_SOURCE_BYTES = 256L * 1024L * 1024L
