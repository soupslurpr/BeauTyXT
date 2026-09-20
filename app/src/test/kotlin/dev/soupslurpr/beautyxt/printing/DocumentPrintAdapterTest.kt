/* Verifies print worker ownership without Android's framework-only callbacks. */
package dev.soupslurpr.beautyxt.printing

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Verifies print-adapter lifecycle invariants independent of Android's hidden callbacks. */
class DocumentPrintAdapterTest {
    /** Verifies cancellation before worker entry still closes the transferred destination. */
    @Test
    fun closesDestinationWhenCancelledBeforeStarting() = runBlocking {
        var closes = 0
        var started = false
        val write = launchPrintWrite(AutoCloseable { closes += 1 }) { started = true }

        write.cancel()
        write.join()

        assertEquals(1, closes)
        assertFalse(started)
    }

    /** Verifies an already closed adapter scope cannot strand a newly offered descriptor. */
    @Test
    fun closesDestinationWhenScopeIsAlreadyCancelled() = runBlocking {
        val owner = SupervisorJob().apply { cancel() }
        val scope = CoroutineScope(coroutineContext + owner)
        var closes = 0
        var started = false

        scope.launchPrintWrite(AutoCloseable { closes += 1 }) { started = true }.join()

        assertEquals(1, closes)
        assertFalse(started)
    }

    /** Verifies normal completion releases the descriptor only after writing finishes. */
    @Test
    fun closesDestinationAfterSuccessfulWrite() = runBlocking {
        var closes = 0
        val write = launchPrintWrite(AutoCloseable { closes += 1 }) {
            assertEquals(0, closes)
        }

        write.start()
        write.join()

        assertEquals(1, closes)
    }

    /** Verifies abandoned-descriptor cleanup cannot replace cancellation with an I/O failure. */
    @Test
    fun toleratesCloseFailureAfterCancellation() = runBlocking {
        val write =
            launchPrintWrite(AutoCloseable { throw IOException("synthetic close failure") }) {
                error("cancelled print worker must not run")
            }

        write.cancel()
        write.join()

        assertFalse(write.isActive)
    }

    /** Makes the active slot available before Android synchronously requests another write. */
    @Test
    fun releasesWriteBeforeReentrantCallback() = runBlocking {
        val firstWrite = Any()
        val secondWrite = Any()
        var activeWrite: Any? = firstWrite

        dispatchReleasedPrintWrite(
            release = {
                assertSame(firstWrite, activeWrite)
                activeWrite = null
            }
        ) {
            assertNull(activeWrite)
            activeWrite = secondWrite
        }

        assertSame(secondWrite, activeWrite)
    }
}
