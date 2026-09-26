package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.*
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AtomicBatchCancellationTest {
    @Test fun cancellationDuringNativePublicationStillReportsTheCommittedRevision() = runBlocking {
        val original = TestEditorDocument("cat cat")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val document = object : EditorDocument by original {
            override fun replaceBatch(revision: Long, patches: List<DocumentPatch>): DocumentMetrics {
                started.complete(Unit)
                runBlocking { release.await() }
                return original.replaceBatch(revision, patches)
            }
        }
        val state = EditorDocumentState(document)
        state.loadInitialViewport()
        var result: DocumentReplacementResult = DocumentReplacementResult.Unavailable
        val operation = launch {
            result = state.replaceDocumentBatch(0, listOf(DocumentPatch(Utf16Range(0, 3), "cat", "dog"),
                DocumentPatch(Utf16Range(4, 7), "cat", "fox")), Utf16Range(3, 3))
        }
        started.await()
        operation.cancel()
        release.complete(Unit)
        operation.join()
        assertEquals(DocumentReplacementResult.Applied(1), result)
        assertEquals("dog fox", original.text)
        assertEquals(1L, state.metrics!!.revision)
        assertEquals(EditorDocumentStatus.Ready, state.status)
        assertTrue(state.canCloseSafely)
        state.close()
    }
}
