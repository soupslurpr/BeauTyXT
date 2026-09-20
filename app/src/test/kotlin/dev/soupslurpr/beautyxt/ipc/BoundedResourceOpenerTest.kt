package dev.soupslurpr.beautyxt.ipc

import dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.QueuedSessionTestDispatcher
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BoundedResourceOpenerTest {
    @Test
    fun canceledQueuedCallsNeverOpenAResourceAndReleaseTheirSlots() = runBlocking {
        Fixture().use { fixture ->
            val canceled = fixture.open { error("canceled provider call ran") }
            canceled.cancel()
            val resource = Resource()
            val next = fixture.open { resource }
            assertFalse(next.isCompleted)
            fixture.workers.runAll()
            assertSame(resource, next.await())
            assertEquals(0, resource.closes)
            resource.close()
            assertEquals(1, resource.closes)
        }
    }

    @Test
    fun cancellationDoesNotReleaseAWorkerThatIsStillOpening() = runBlocking {
        Fixture().use { fixture ->
            val late = Resource()
            val nextResource = Resource()
            lateinit var first: Deferred<Resource>
            lateinit var next: Deferred<Resource>
            first = fixture.open {
                first.cancel()
                next = fixture.open { nextResource }
                // Cancellation released the caller, but not the in-progress provider call.
                assertFalse(next.isCompleted)
                assertEquals(0, fixture.workers.pendingCount)
                late
            }
            fixture.workers.runAll()
            assertTrue(first.isCancelled)
            assertEquals(1, late.closes)
            assertSame(nextResource, next.await())
            assertEquals(0, nextResource.closes)
            nextResource.close()
        }
    }

    @Test
    fun closesAResultWhenCancellationWinsBeforeCallerResumption() = runBlocking {
        val caller = QueuedSessionTestDispatcher()
        Fixture(caller).use { fixture ->
            val resource = Resource()
            val request = fixture.open { resource }
            caller.runAll()
            fixture.workers.runAll()
            assertEquals(0, resource.closes)
            request.cancel()
            caller.runAll()
            assertTrue(request.isCancelled)
            assertEquals(1, resource.closes)
        }
    }

    @Test
    fun releasesSlotsAfterProviderFailureAndDispatchRejection() = runBlocking {
        for (dispatchFails in listOf(false, true)) {
            val workers = QueuedSessionTestDispatcher()
            val failure = IOException("synthetic provider failure")
            var reject = dispatchFails
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    if (reject) {
                        reject = false
                        throw failure
                    }
                    workers.dispatch(context, block)
                }
            }
            val scope = CoroutineScope(SupervisorJob() + ImmediateSessionTestDispatcher)
            try {
                val opener = BoundedResourceOpener(1, dispatcher)
                val failed = scope.async {
                    opener.open(cancel = {}, closeUnclaimed = Resource::close) {
                        if (dispatchFails) error("rejected dispatch ran")
                        throw failure
                    }
                }
                workers.runAll()
                val reported = runCatching { failed.await() }.exceptionOrNull()
                assertTrue(reported is IOException)
                assertEquals(failure.message, reported?.message)
                val resource = Resource()
                val recovered = scope.async {
                    opener.open(cancel = {}, closeUnclaimed = Resource::close) { resource }
                }
                workers.runAll()
                assertSame(resource, recovered.await())
                resource.close()
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun timeoutReturnsEvenWhenProviderIgnoresCancellationWithoutStartingMoreCalls() = runBlocking {
        val opener = BoundedResourceOpener(1)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val cancellations = AtomicInteger()
        val calls = AtomicInteger()
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(500L) {
                opener.open(
                    cancel = { cancellations.incrementAndGet() },
                    closeUnclaimed = AutoCloseable::close
                ) {
                    calls.incrementAndGet()
                    started.countDown()
                    check(release.await(5L, TimeUnit.SECONDS)) { "test worker was not released" }
                    AutoCloseable { closed.countDown() }
                }
            }
        }
        try {
            assertTrue(started.await(5L, TimeUnit.SECONDS))
            assertNull(request.await())
            assertEquals(1, cancellations.get())
            assertNull(withTimeoutOrNull(50L) {
                opener.open(
                    cancel = { fail("a waiting request must not contact the provider") },
                    closeUnclaimed = AutoCloseable::close
                ) {
                    calls.incrementAndGet()
                    error("timed-out worker no longer counted toward the limit")
                }
            })
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            assertTrue(closed.await(5L, TimeUnit.SECONDS))
        }
    }

    private class Resource : AutoCloseable {
        var closes = 0
            private set

        override fun close() {
            closes++
        }
    }

    private class Fixture(caller: CoroutineDispatcher = ImmediateSessionTestDispatcher) :
        AutoCloseable {
        val workers = QueuedSessionTestDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + caller)
        private val opener = BoundedResourceOpener(1, workers)

        fun open(acquire: () -> Resource): Deferred<Resource> = scope.async {
            opener.open(cancel = {}, closeUnclaimed = Resource::close, acquire = acquire)
        }

        override fun close() {
            scope.cancel()
            workers.runAll()
        }
    }
}
