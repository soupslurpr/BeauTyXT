package dev.soupslurpr.beautyxt.importing.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val TEST_QUERY_TIMEOUT_MILLIS = 500L
private const val TEST_COMPLETION_TIMEOUT_SECONDS = 5L
private const val TEST_MAX_CONCURRENT_QUERIES = 1

class SelectedDocumentDisplayNameQueryTest {
    @Test
    fun returnsACompletedDisplayName() = runBlocking {
        val runner = createRunner()

        assertEquals(
            "notes.txt",
            runner.query(
                cancellationAction = { fail("completed query was cancelled") },
                queryAction = { "notes.txt" }
            )
        )
    }

    @Test
    fun convertsProviderFailureToMissingMetadataAndReleasesWorker() = runBlocking {
        val runner = createRunner()

        assertNull(
            runner.query(
                cancellationAction = { fail("failed query was cancelled") },
                queryAction = { throw IllegalStateException("synthetic provider failure") }
            )
        )
        assertEquals(
            "recovered.txt",
            runner.query(
                cancellationAction = { fail("recovery query was cancelled") },
                queryAction = { "recovered.txt" }
            )
        )
    }

    @Test
    fun timesOutOneBlockedQueryWithoutQueuingAnother() = runBlocking {
        val dispatcher = CompletionTrackingDispatcher(createTestDispatcher())
        val runner = createRunner(dispatcher)
        val workerStarted = CountDownLatch(1)
        val workerRelease = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        val cancellationObserved = CountDownLatch(1)
        val secondQueryRan = AtomicBoolean(false)
        val query =
            async(start = CoroutineStart.UNDISPATCHED) {
                runner.query(
                    cancellationAction = cancellationObserved::countDown,
                    queryAction = {
                        workerStarted.countDown()
                        try {
                            workerRelease.await()
                            "late.txt"
                        } finally {
                            workerFinished.countDown()
                        }
                    }
                )
            }
        try {
            assertTrue(workerStarted.awaitCompletion())
            assertNull(query.await())
            assertTrue(cancellationObserved.awaitCompletion())
            assertNull(
                runner.query(
                    cancellationAction = { fail("rejected query was cancelled") },
                    queryAction = {
                        secondQueryRan.set(true)
                        "queued.txt"
                    }
                )
            )
            assertFalse(secondQueryRan.get())
        } finally {
            workerRelease.countDown()
            assertTrue(workerFinished.awaitCompletion())
            assertTrue(dispatcher.awaitCompletion())
        }
        assertEquals(
            "recovered.txt",
            runner.query(
                cancellationAction = { fail("recovery query was cancelled") },
                queryAction = { "recovered.txt" }
            )
        )
    }

    @Test
    fun propagatesCallerCancellationAndCancelsProviderQuery() = runBlocking {
        val runner = createRunner()
        val workerStarted = CountDownLatch(1)
        val workerRelease = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        val cancellationObserved = CountDownLatch(1)
        val query =
            async(start = CoroutineStart.UNDISPATCHED) {
                runner.query(
                    cancellationAction = cancellationObserved::countDown,
                    queryAction = {
                        workerStarted.countDown()
                        try {
                            workerRelease.await()
                            "late.txt"
                        } finally {
                            workerFinished.countDown()
                        }
                    }
                )
            }
        try {
            assertTrue(workerStarted.awaitCompletion())
            query.cancel(CancellationException("synthetic cancellation"))
            try {
                query.await()
                fail("caller cancellation was not propagated")
            } catch (_: CancellationException) {
            }
            assertTrue(cancellationObserved.awaitCompletion())
        } finally {
            workerRelease.countDown()
            assertTrue(workerFinished.awaitCompletion())
        }
    }

    /** Creates one single-worker runner with a short deterministic test bound. */
    private fun createRunner(
        dispatcher: CoroutineDispatcher = createTestDispatcher()
    ): BoundedProviderQueryRunner = BoundedProviderQueryRunner(
        timeoutMillis = TEST_QUERY_TIMEOUT_MILLIS,
        dispatcher = dispatcher
    )

    /** Creates one bounded dispatcher for a test runner. */
    private fun createTestDispatcher(): CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(TEST_MAX_CONCURRENT_QUERIES)

    /** Waits a fixed bound for one deterministic test transition. */
    private fun CountDownLatch.awaitCompletion(): Boolean =
        await(TEST_COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
}

/** Reports when the most recently dispatched worker has fully returned. */
private class CompletionTrackingDispatcher(private val delegate: CoroutineDispatcher) :
    CoroutineDispatcher() {
    private val completion = CountDownLatch(1)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        delegate.dispatch(
            context,
            Runnable {
                try {
                    block.run()
                } finally {
                    completion.countDown()
                }
            }
        )
    }

    /** Waits a fixed bound for the tracked worker to return. */
    fun awaitCompletion(): Boolean =
        completion.await(TEST_COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
}
