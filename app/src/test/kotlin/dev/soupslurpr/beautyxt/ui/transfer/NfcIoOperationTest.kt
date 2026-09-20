package dev.soupslurpr.beautyxt.ui.transfer

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NFC_TEST_TIMEOUT_MILLIS = 5_000L

/** Verifies structured ownership of blocking NFC operations without a hardware tag. */
class NfcIoOperationTest {
    /** Closes a successful connection once, off the caller's UI-equivalent thread. */
    @Test
    fun closesSuccessfulOperationOffCallerThread() = runBlocking {
        val callerThread = Thread.currentThread()
        val closeCount = AtomicInteger()
        val result = runNfcIo(
            closeConnection = {
                assertNotSame(callerThread, Thread.currentThread())
                closeCount.incrementAndGet()
            }
        ) { context ->
            assertNotSame(callerThread, Thread.currentThread())
            context.ensureActive()
            "verified"
        }
        assertEquals("verified", result)
        assertEquals(1, closeCount.get())
    }

    /** Preserves the primary I/O failure even if cleanup also fails. */
    @Test
    fun preservesOperationFailure() = runBlocking {
        val failure = IOException("tag left the field")
        val closeCount = AtomicInteger()
        try {
            runNfcIo(
                closeConnection = {
                    closeCount.incrementAndGet()
                    throw SecurityException("tag revoked")
                }
            ) { throw failure }
            throw AssertionError("operation must fail")
        } catch (actual: IOException) {
            assertEquals(failure.javaClass, actual.javaClass)
            assertEquals(failure.message, actual.message)
        }
        assertEquals(1, closeCount.get())
    }

    /** Interrupts blocked I/O before joining its worker and completes all cleanup. */
    @Test
    fun closesBlockedConnectionOnCancellation() = runBlocking {
        withTimeout(NFC_TEST_TIMEOUT_MILLIS) {
            val entered = CompletableDeferred<Unit>()
            val connectionClosed = CountDownLatch(1)
            val workerFinished = AtomicBoolean()
            val closeCount = AtomicInteger()
            val callerThread = Thread.currentThread()
            val operation = launch {
                runNfcIo(
                    closeConnection = {
                        assertNotSame(callerThread, Thread.currentThread())
                        closeCount.incrementAndGet()
                        connectionClosed.countDown()
                    }
                ) {
                    entered.complete(Unit)
                    try {
                        awaitNfcLatch(connectionClosed)
                        throw IOException("operation interrupted")
                    } finally {
                        workerFinished.set(true)
                    }
                }
            }
            entered.await()
            operation.cancelAndJoin()
            assertTrue(workerFinished.get())
            assertEquals(2, closeCount.get())
        }
    }

    /** Cleans up a connect that completes after cancellation without starting a write. */
    @Test
    fun closesLateConnectionBeforeNextOperation() = runBlocking {
        withTimeout(NFC_TEST_TIMEOUT_MILLIS) {
            val entered = CompletableDeferred<Unit>()
            val firstClose = CountDownLatch(1)
            val connected = AtomicBoolean()
            val writes = AtomicInteger()
            val closeCount = AtomicInteger()
            val operation = launch {
                runNfcIo(
                    closeConnection = {
                        closeCount.incrementAndGet()
                        connected.set(false)
                        firstClose.countDown()
                    }
                ) { context ->
                    entered.complete(Unit)
                    awaitNfcLatch(firstClose)
                    connected.set(true)
                    context.ensureActive()
                    writes.incrementAndGet()
                }
            }
            entered.await()
            val followingOperation = launch(start = CoroutineStart.UNDISPATCHED) {
                runNfcIo(closeConnection = {}) {
                    assertEquals(2, closeCount.get())
                    assertFalse(connected.get())
                }
            }
            operation.cancelAndJoin()
            followingOperation.join()
            assertFalse(connected.get())
            assertEquals(0, writes.get())
            assertEquals(2, closeCount.get())
        }
    }

    /** Cancels a queued operation without opening or closing another screen's tag. */
    @Test
    fun cancelsQueuedOperationBeforeTouchingConnection() = runBlocking {
        withTimeout(NFC_TEST_TIMEOUT_MILLIS) {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val active = launch {
                runNfcIo(closeConnection = { release.countDown() }) {
                    entered.complete(Unit)
                    awaitNfcLatch(release)
                }
            }
            entered.await()
            val touched = AtomicBoolean()
            val queued = launch(start = CoroutineStart.UNDISPATCHED) {
                runNfcIo(closeConnection = { touched.set(true) }) { touched.set(true) }
            }
            queued.cancelAndJoin()
            active.cancelAndJoin()
            assertFalse(touched.get())
        }
    }
}

/** Bounds a fake blocking tag call so a broken cancellation test cannot hang the suite. */
private fun awaitNfcLatch(latch: CountDownLatch) {
    check(latch.await(NFC_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        "test NFC connection did not receive cancellation"
    }
}
