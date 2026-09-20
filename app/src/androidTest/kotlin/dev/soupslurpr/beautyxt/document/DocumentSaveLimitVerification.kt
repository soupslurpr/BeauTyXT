package dev.soupslurpr.beautyxt.document

private const val SAVE_LIMIT_BYTES = 256L * 1024 * 1024
private const val SAVE_LIMIT_CHUNK_SIZE = 512 * 1024

/** Exercises the real JNI rejection at the production ceiling without exporting a private file. */
internal fun verifyDocumentSaveLimit() {
    val limits = ViewportLimits(
        maxBlocks = 1,
        maxBlockUtf16Units = 16,
        maxTotalUtf16Units = 16
    )
    RustDocument.createEmpty().use { document ->
        var metrics = document.viewport(ViewportCursor(0L, 0L, 0L), limits).metrics
        val chunk = "x".repeat(SAVE_LIMIT_CHUNK_SIZE)
        repeat((SAVE_LIMIT_BYTES / SAVE_LIMIT_CHUNK_SIZE).toInt()) {
            metrics = document.replace(
                metrics.revision,
                Utf16Range(metrics.utf16Length, metrics.utf16Length),
                chunk
            )
        }
        check(metrics.byteLength == SAVE_LIMIT_BYTES)
        val failure = runCatching {
            // Same UTF-16 length, but one more serialized UTF-8 byte.
            document.replace(
                metrics.revision,
                Utf16Range(metrics.utf16Length - 1L, metrics.utf16Length),
                "é"
            )
        }.exceptionOrNull()
        check(failure is DocumentSizeLimitException) {
            "save-size rejection lost its typed JNI exception: $failure"
        }
        val tail = document.viewport(
            ViewportCursor(metrics.revision, 0L, metrics.utf16Length - 16L),
            limits
        )
        check(tail.metrics == metrics && tail.blocks.single().text == "x".repeat(16)) {
            "save-size rejection changed the native revision or text"
        }

        metrics = document.replace(
            metrics.revision,
            Utf16Range(metrics.utf16Length - 2L, metrics.utf16Length),
            ""
        )
        metrics = document.replace(
            metrics.revision,
            Utf16Range(metrics.utf16Length, metrics.utf16Length),
            "é"
        )
        check(metrics.byteLength == SAVE_LIMIT_BYTES) {
            "a size-reducing edit did not allow recovery to the exact save boundary"
        }
    }
}
