package dev.soupslurpr.beautyxt.ipc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ACQUISITION_TIMEOUT_MILLIS = 60_000L

/** Verifies ownership when cancellation races the timed acquisition return. */
class ResourceAcquisitionTest {
    @Test
    fun transfersSuccessfulResourceWithoutClosing() = runBlocking {
        var closeCount = 0
        val resource = AutoCloseable { closeCount++ }

        val received = acquireResourceWithTimeout(ACQUISITION_TIMEOUT_MILLIS) { resource }

        assertSame(resource, received)
        assertEquals(0, closeCount)
        received.close()
        assertEquals(1, closeCount)
    }

    @Test
    fun closesResourceWhenAcquisitionScopeCancelsBeforeReturning() = runBlocking {
        var closeCount = 0
        val resource = AutoCloseable { closeCount++ }

        val failure = runCatching {
            acquireResourceWithTimeout(ACQUISITION_TIMEOUT_MILLIS) {
                currentCoroutineContext().cancel()
                resource
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(1, closeCount)
    }

    @Test
    fun preservesCancellationWhenUndeliveredResourceCloseFails() = runBlocking {
        val cleanupFailure = IllegalStateException("synthetic resource close failure")
        val resource = AutoCloseable { throw cleanupFailure }

        val failure = runCatching {
            acquireResourceWithTimeout(ACQUISITION_TIMEOUT_MILLIS) {
                currentCoroutineContext().cancel()
                resource
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertSame(cleanupFailure, checkNotNull(failure).suppressed.single())
    }
}
