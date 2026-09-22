package dev.soupslurpr.beautyxt.markdown

/** Returns whether native Markdown statistics form one canonical result. */
internal fun areValidMarkdownStatistics(
    resultCode: Int,
    expectedInputBytes: Long,
    maxPacketBytes: Long,
    inputBytes: Long,
    packetBytes: Long,
    blockCount: Long,
    spanCount: Long,
    documentFlags: Long
): Boolean {
    if (
        expectedInputBytes !in
        MarkdownProtocol.MIN_INPUT_BYTES..MarkdownProtocol.MAX_INPUT_BYTES ||
        maxPacketBytes !in
        MarkdownProtocol.MIN_PACKET_BYTES..MarkdownProtocol.MAX_PACKET_BYTES ||
        inputBytes < 0L ||
        packetBytes < 0L ||
        blockCount < 0L ||
        spanCount < 0L ||
        documentFlags < 0L ||
        documentFlags and MarkdownProtocol.DOCUMENT_FLAGS_MASK.toLong().inv() != 0L
    ) {
        return false
    }
    return if (resultCode == MarkdownProtocol.RESULT_SUCCESS) {
        inputBytes == expectedInputBytes &&
            packetBytes in MarkdownProtocol.MIN_PACKET_BYTES..maxPacketBytes &&
            blockCount <= MarkdownProtocol.MAX_BLOCK_COUNT &&
            spanCount <= MarkdownProtocol.MAX_SPAN_COUNT
    } else {
        inputBytes == 0L &&
            packetBytes == 0L &&
            blockCount == 0L &&
            spanCount == 0L &&
            documentFlags == 0L
    }
}
