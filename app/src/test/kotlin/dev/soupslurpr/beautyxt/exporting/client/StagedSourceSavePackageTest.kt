package dev.soupslurpr.beautyxt.exporting.client

import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.document.NO_SOURCE_SAVE_BACKING_BYTE_LENGTH
import dev.soupslurpr.beautyxt.document.SourceSavePackageMetrics
import dev.soupslurpr.beautyxt.exporting.ExportProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** Verifies staged package bounds before Android descriptor acquisition. */
class StagedSourceSavePackageTest {
    @Test
    fun acceptsCanonicalPackageMetrics() {
        listOf(
            SourceSavePackageMetrics(
                packageByteLength = 64L,
                outputByteLength = 0L,
                payloadByteLength = 0L,
                sourceBackingByteLength = NO_SOURCE_SAVE_BACKING_BYTE_LENGTH,
                recordCount = 0L
            ),
            SourceSavePackageMetrics(
                packageByteLength = 93L,
                outputByteLength = 5L,
                payloadByteLength = 5L,
                sourceBackingByteLength = NO_SOURCE_SAVE_BACKING_BYTE_LENGTH,
                recordCount = 1L
            ),
            SourceSavePackageMetrics(
                packageByteLength = 137L,
                outputByteLength = 100L,
                payloadByteLength = 1L,
                sourceBackingByteLength = 100L,
                recordCount = 3L
            ),
            SourceSavePackageMetrics(
                packageByteLength = 88L,
                outputByteLength = 100L,
                payloadByteLength = 0L,
                sourceBackingByteLength = 100L,
                recordCount = 1L
            )
        ).forEach { metrics ->
            validateSourceSavePackageMetrics(metrics, metrics.outputByteLength)
        }
    }

    @Test
    fun rejectsNoncanonicalPackageLength() {
        val metrics =
            SourceSavePackageMetrics(
                packageByteLength = 94L,
                outputByteLength = 5L,
                payloadByteLength = 5L,
                sourceBackingByteLength = NO_SOURCE_SAVE_BACKING_BYTE_LENGTH,
                recordCount = 1L
            )

        assertThrows(IllegalStateException::class.java) {
            validateSourceSavePackageMetrics(metrics, expectedBytes = 5L)
        }
    }

    @Test
    fun rejectsBackingWithoutSourceCoverage() {
        val metrics =
            SourceSavePackageMetrics(
                packageByteLength = 93L,
                outputByteLength = 5L,
                payloadByteLength = 5L,
                sourceBackingByteLength = 5L,
                recordCount = 1L
            )

        assertThrows(IllegalStateException::class.java) {
            validateSourceSavePackageMetrics(metrics, expectedBytes = 5L)
        }
    }

    @Test
    fun rejectsSparseCoverageWithoutBacking() {
        val metrics =
            SourceSavePackageMetrics(
                packageByteLength = 89L,
                outputByteLength = 5L,
                payloadByteLength = 1L,
                sourceBackingByteLength = NO_SOURCE_SAVE_BACKING_BYTE_LENGTH,
                recordCount = 1L
            )

        assertThrows(IllegalStateException::class.java) {
            validateSourceSavePackageMetrics(metrics, expectedBytes = 5L)
        }
    }

    @Test
    fun rejectsNegativeExpectedLengthWithoutConsumingSnapshot() {
        val snapshot = UnusedEditorDocumentSnapshot()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                StagedSourceSavePackage.capture(snapshot = snapshot, expectedBytes = -1L)
            }
        }

        assertFalse(snapshot.isClosed)
    }

    @Test
    fun rejectsOversizedExpectedLengthWithoutConsumingSnapshot() {
        val snapshot = UnusedEditorDocumentSnapshot()

        val failure =
            assertThrows(DocumentExportException::class.java) {
                runBlocking {
                    StagedSourceSavePackage.capture(
                        snapshot = snapshot,
                        expectedBytes = ExportProtocol.MAX_BYTE_LIMIT + 1L
                    )
                }
            }

        assertEquals(DocumentExportFailure.TOO_LARGE, failure.failure)
        assertFalse(snapshot.isClosed)
    }
}

/** Records closure while rejecting every unexpected snapshot write. */
private class UnusedEditorDocumentSnapshot : EditorDocumentSnapshot {
    var isClosed = false
        private set

    override fun writeSnapshot(
        outputRawFileDescriptor: Int,
        cancellationRawFileDescriptor: Int,
        timeoutMillis: Long
    ): Long = error("bounded snapshot validation unexpectedly started a write")

    override fun close() {
        isClosed = true
    }
}
