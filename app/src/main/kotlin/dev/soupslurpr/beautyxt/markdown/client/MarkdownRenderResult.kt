package dev.soupslurpr.beautyxt.markdown.client

import dev.soupslurpr.beautyxt.markdown.MarkdownProtocol

/** Identifies one sanitized Markdown preview failure. */
internal enum class MarkdownRenderFailure {
    TooLarge,
    TooComplex,
    InvalidUtf8,
    ServiceUnavailable,
    ServiceBusy,
    SnapshotFailed,
    TimedOut,
    InvalidResponse,
    RenderFailed
}

/** Reports one sanitized Markdown preview failure. */
internal class MarkdownRenderException(
    val failure: MarkdownRenderFailure,
    cause: Throwable? = null
) : Exception(failure.name, cause)

/** Contains one canonical terminal status from the isolated renderer. */
internal data class MarkdownTerminalStatus(
    val state: Int,
    val resultCode: Int,
    val inputBytes: Long,
    val packetBytes: Long,
    val blockCount: Long,
    val spanCount: Long,
    val documentFlags: Long
) {
    /** Returns a sanitized failure or null for one canonical success. */
    fun failureOrNull(expectedInputBytes: Long): MarkdownRenderFailure? {
        if (
            expectedInputBytes !in
            MarkdownProtocol.MIN_INPUT_BYTES..MarkdownProtocol.MAX_INPUT_BYTES ||
            inputBytes < 0L ||
            packetBytes < 0L ||
            blockCount < 0L ||
            spanCount < 0L ||
            documentFlags < 0L ||
            documentFlags and MarkdownProtocol.DOCUMENT_FLAGS_MASK.toLong().inv() != 0L
        ) {
            return MarkdownRenderFailure.InvalidResponse
        }
        return when (state) {
            MarkdownProtocol.STATE_COMPLETE ->
                if (
                    resultCode == MarkdownProtocol.RESULT_SUCCESS &&
                    inputBytes == expectedInputBytes &&
                    packetBytes in
                    MarkdownProtocol.MIN_PACKET_BYTES..MarkdownProtocol.MAX_PACKET_BYTES &&
                    blockCount <= MarkdownProtocol.MAX_BLOCK_COUNT &&
                    spanCount <= MarkdownProtocol.MAX_SPAN_COUNT
                ) {
                    null
                } else {
                    MarkdownRenderFailure.InvalidResponse
                }

            MarkdownProtocol.STATE_CANCELLED ->
                if (
                    resultCode == MarkdownProtocol.RESULT_CANCELLED &&
                    hasZeroStatistics()
                ) {
                    MarkdownRenderFailure.RenderFailed
                } else {
                    MarkdownRenderFailure.InvalidResponse
                }

            MarkdownProtocol.STATE_FAILED ->
                if (!hasZeroStatistics()) {
                    MarkdownRenderFailure.InvalidResponse
                } else {
                    failureForResultCode(resultCode)
                }

            else -> MarkdownRenderFailure.InvalidResponse
        }
    }

    /** Returns whether all terminal statistics are zero. */
    private fun hasZeroStatistics(): Boolean = inputBytes == 0L &&
        packetBytes == 0L &&
        blockCount == 0L &&
        spanCount == 0L &&
        documentFlags == 0L

    /** Maps one service result to a sanitized presentation failure. */
    private fun failureForResultCode(resultCode: Int): MarkdownRenderFailure = when (resultCode) {
        MarkdownProtocol.RESULT_TIMEOUT -> MarkdownRenderFailure.TimedOut

        MarkdownProtocol.RESULT_INPUT_LIMIT -> MarkdownRenderFailure.TooLarge

        MarkdownProtocol.RESULT_INVALID_UTF8 -> MarkdownRenderFailure.InvalidUtf8

        MarkdownProtocol.RESULT_RENDER_LIMIT -> MarkdownRenderFailure.TooComplex

        MarkdownProtocol.RESULT_INPUT_LENGTH_MISMATCH,
        MarkdownProtocol.RESULT_INVALID_DESCRIPTOR -> MarkdownRenderFailure.InvalidResponse

        MarkdownProtocol.RESULT_INPUT_IO -> MarkdownRenderFailure.SnapshotFailed

        MarkdownProtocol.RESULT_OUTPUT_IO,
        MarkdownProtocol.RESULT_INTERNAL -> MarkdownRenderFailure.RenderFailed

        else -> MarkdownRenderFailure.InvalidResponse
    }
}
