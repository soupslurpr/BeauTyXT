package dev.soupslurpr.beautyxt

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Test

private const val CLEANUP_TEST_TIMEOUT_MILLIS = 5_000L

/** Verifies process-owned startup cleanup never exposes documents before success. */
class LegacyCleanupTest {
    /** Runs one cleanup off the caller thread and gates every waiting activity. */
    @Test
    fun blocksDocumentAccessWithoutBlockingTheCaller() = runBlocking {
        withTimeout(CLEANUP_TEST_TIMEOUT_MILLIS) {
            val callerThread = Thread.currentThread()
            val release = CountDownLatch(1)
            val attempts = AtomicInteger()
            val cleanup = LegacyCleanup {
                assertNotSame(callerThread, Thread.currentThread())
                attempts.incrementAndGet()
                check(release.await(CLEANUP_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    "test cleanup did not receive its completion signal"
                }
            }
            val firstActivity = async(start = CoroutineStart.UNDISPATCHED) { cleanup.awaitReady() }
            val secondActivity = async(start = CoroutineStart.UNDISPATCHED) { cleanup.awaitReady() }
            try {
                cleanup.start()
                cleanup.start()
                assertEquals(LegacyCleanupStatus.Running, cleanup.status.value)
                assertFalse(firstActivity.isCompleted)
                assertFalse(secondActivity.isCompleted)
                firstActivity.cancelAndJoin()
                release.countDown()
                secondActivity.await()
                cleanup.start()
                assertEquals(LegacyCleanupStatus.Ready, cleanup.status.value)
                assertEquals(1, attempts.get())
            } finally {
                release.countDown()
            }
        }
    }

    /** Keeps pending provider access suspended through failure until an explicit retry succeeds. */
    @Test
    fun requiresSuccessfulRetryAfterFailure() = runBlocking {
        withTimeout(CLEANUP_TEST_TIMEOUT_MILLIS) {
            val attempts = AtomicInteger()
            val cleanup = LegacyCleanup {
                if (attempts.incrementAndGet() == 1) {
                    throw IOException("synthetic storage failure")
                }
            }
            val activity = async(start = CoroutineStart.UNDISPATCHED) { cleanup.awaitReady() }
            cleanup.start()
            cleanup.status.first { it == LegacyCleanupStatus.Failed }
            assertFalse(activity.isCompleted)
            assertEquals(1, attempts.get())
            cleanup.start()
            activity.await()
            assertEquals(LegacyCleanupStatus.Ready, cleanup.status.value)
            assertEquals(2, attempts.get())
        }
    }

    /** Converts inaccessible old storage into a retryable state rather than a startup crash. */
    @Test
    fun reportsRevokedStorageAccess() = runBlocking {
        withTimeout(CLEANUP_TEST_TIMEOUT_MILLIS) {
            val cleanup = LegacyCleanup { throw SecurityException("synthetic access denial") }
            cleanup.start()
            cleanup.status.first { it == LegacyCleanupStatus.Failed }
            assertEquals(LegacyCleanupStatus.Failed, cleanup.status.value)
        }
    }
}
