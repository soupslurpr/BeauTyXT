/* Verifies retained QR export ownership without Android provider I/O. */
package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FIRST_GENERATION = 1L
private const val NEXT_GENERATION = 2L
private const val SOURCE_URI = "content://test.documents/source.txt"
private const val IMAGE_URI = "content://test.documents/code.png"

/** Verifies single-write ownership, source protection, and cancellation boundaries. */
class QrImageExportControllerTest {
    /** Never changes the encoding underneath a pending picker or provider write. */
    @Test
    fun retainsFormatForTheWholeExport() = withExportController { controller, _ ->
        assertTrue(controller.selectFormat(QrImageFormat.Png))
        assertTrue(controller.chooseDestination(FIRST_GENERATION))
        assertFalse(controller.selectFormat(QrImageFormat.WebP))
        assertEquals(QrImageFormat.Png, controller.state.format)
        val gate = CompletableDeferred<Unit>()
        assertTrue(controller.saveSelectedDestination(FIRST_GENERATION, IMAGE_URI) { gate.await() })
        assertFalse(controller.selectFormat(QrImageFormat.WebP))
        assertEquals(QrImageFormat.Png, controller.state.format)
        gate.complete(Unit)
        assertTrue(controller.selectFormat(QrImageFormat.WebP))
        assertEquals(QrImageSaveStatus.Idle, controller.state.status)
        assertTrue(controller.chooseDestination(NEXT_GENERATION))
        controller.cancelDestination(NEXT_GENERATION)
        assertEquals(QrImageFormat.WebP, controller.state.format)
    }

    /** Keeps one write active across repeated UI attachment and duplicate results. */
    @Test
    fun writesEachDestinationOnlyOnce() = withExportController { controller, _ ->
        val writeGate = CompletableDeferred<Unit>()
        var writes = 0
        assertTrue(controller.chooseDestination(FIRST_GENERATION))
        assertTrue(
            controller.saveSelectedDestination(FIRST_GENERATION, IMAGE_URI) {
                writes += 1
                writeGate.await()
            }
        )
        assertEquals(QrImageSaveStatus.Saving, controller.state.status)
        assertFalse(controller.chooseDestination(FIRST_GENERATION))
        assertFalse(controller.chooseDestination(NEXT_GENERATION))
        assertFalse(
            controller.saveSelectedDestination(FIRST_GENERATION, IMAGE_URI) {
                writes += 1
            }
        )
        controller.cancelDestination(FIRST_GENERATION)
        controller.failDestination(FIRST_GENERATION)
        assertEquals(QrImageSaveStatus.Saving, controller.state.status)
        writeGate.complete(Unit)
        assertEquals(1, writes)
        assertEquals(QrImageSaveStatus.Succeeded, controller.state.status)
        assertTrue(controller.chooseDestination(NEXT_GENERATION))
    }

    /** Rejects the retained source before calling a destructive destination writer. */
    @Test
    fun neverWritesAnImageOverTheOpenDocument() = withExportController { controller, _ ->
        var writes = 0
        assertTrue(controller.chooseDestination(FIRST_GENERATION))
        assertFalse(
            controller.saveSelectedDestination(FIRST_GENERATION, SOURCE_URI) {
                writes += 1
            }
        )
        assertEquals(0, writes)
        assertEquals(QrImageSaveStatus.Failed, controller.state.status)
        assertTrue(controller.chooseDestination(FIRST_GENERATION))
    }

    /** Leaves the current destination untouched when an older result arrives. */
    @Test
    fun ignoresStalePickerResults() = withExportController { controller, _ ->
        assertTrue(controller.chooseDestination(NEXT_GENERATION))
        controller.cancelDestination(FIRST_GENERATION)
        controller.failDestination(FIRST_GENERATION)
        assertFalse(
            controller.saveSelectedDestination(FIRST_GENERATION, IMAGE_URI) {
                error("stale destination must not be written")
            }
        )
        assertEquals(
            QrImageExportState(QrImageSaveStatus.ChoosingDestination, NEXT_GENERATION),
            controller.state
        )
    }

    /** Releases cancelled and failed pickers for another explicit attempt. */
    @Test
    fun retriesAfterPickerCancellationOrFailure() = withExportController { controller, _ ->
        assertTrue(controller.chooseDestination(FIRST_GENERATION))
        controller.cancelDestination(FIRST_GENERATION)
        assertEquals(QrImageSaveStatus.Idle, controller.state.status)
        assertTrue(controller.chooseDestination(NEXT_GENERATION))
        controller.failDestination(NEXT_GENERATION)
        assertEquals(QrImageSaveStatus.Failed, controller.state.status)
        assertTrue(controller.chooseDestination(NEXT_GENERATION))
    }

    /** Reports provider failure and permits a later destination to succeed. */
    @Test
    fun retriesAfterWriteFailure() = withExportController { controller, _ ->
        assertTrue(controller.chooseDestination(FIRST_GENERATION))
        assertTrue(
            controller.saveSelectedDestination(FIRST_GENERATION, IMAGE_URI) {
                throw IOException("test provider rejected output")
            }
        )
        assertEquals(QrImageSaveStatus.Failed, controller.state.status)
        assertTrue(controller.chooseDestination(FIRST_GENERATION))
        assertTrue(controller.saveSelectedDestination(FIRST_GENERATION, IMAGE_URI) {})
        assertEquals(QrImageSaveStatus.Succeeded, controller.state.status)
    }

    /** Cancels a suspended write when its owning session permanently closes. */
    @Test
    fun stopsWithItsDocumentSession() = withExportController { controller, scope ->
        val writeGate = CompletableDeferred<Unit>()
        var finishedWriting = false
        assertTrue(controller.chooseDestination(FIRST_GENERATION))
        assertTrue(
            controller.saveSelectedDestination(FIRST_GENERATION, IMAGE_URI) {
                writeGate.await()
                finishedWriting = true
            }
        )
        scope.cancel()
        writeGate.complete(Unit)
        assertFalse(finishedWriting)
        assertEquals(QrImageSaveStatus.Failed, controller.state.status)
        assertFalse(controller.chooseDestination(NEXT_GENERATION))
    }

    /** Rejects picker results arriving after the session's scope has closed. */
    @Test
    fun ignoresPickerResultsAfterSessionClose() = withExportController { controller, scope ->
        assertTrue(controller.chooseDestination(FIRST_GENERATION))
        scope.cancel()
        assertFalse(
            controller.saveSelectedDestination(FIRST_GENERATION, IMAGE_URI) {
                error("closed session must not write")
            }
        )
    }
}

/** Runs a deterministic controller check and always releases its coroutine scope. */
private inline fun withExportController(verify: (QrImageExportController, CoroutineScope) -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + ImmediateSessionTestDispatcher)
    try {
        verify(QrImageExportController(scope) { uri -> uri == SOURCE_URI }, scope)
    } finally {
        scope.cancel()
    }
}
