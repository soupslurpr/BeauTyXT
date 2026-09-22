package dev.soupslurpr.beautyxt.importing

/** Returns whether native import statistics form a canonical result. */
internal fun areValidImportStatistics(
    resultCode: Int,
    inputBytes: Long,
    outputBytes: Long,
    sourceFlagsValue: Long,
    sourceSha256: ByteArray
): Boolean {
    if (
        inputBytes < 0L ||
        outputBytes < 0L ||
        sourceFlagsValue < 0L ||
        sourceFlagsValue and ImportProtocol.SOURCE_FLAGS_MASK.toLong().inv() != 0L ||
        sourceSha256.size != ImportProtocol.RESULT_SHA_256_BYTE_COUNT
    ) {
        return false
    }
    return if (resultCode == ImportProtocol.RESULT_SUCCESS) {
        inputBytes == outputBytes
    } else {
        inputBytes == 0L &&
            outputBytes == 0L &&
            sourceFlagsValue == 0L &&
            sourceSha256.all { byte -> byte == 0.toByte() }
    }
}
