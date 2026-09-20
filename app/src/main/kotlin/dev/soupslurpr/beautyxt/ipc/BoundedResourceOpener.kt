package dev.soupslurpr.beautyxt.ipc

import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore

/** Bounds detached blocking calls, including calls whose providers ignore cancellation. */
internal class BoundedResourceOpener(
    maximumConcurrentCalls: Int,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val slots = Semaphore(maximumConcurrentCalls)

    /** Waiting is cancellable; an occupied slot belongs to its worker until it really returns. */
    suspend fun <Resource> open(
        cancel: () -> Unit,
        closeUnclaimed: (Resource) -> Unit,
        acquire: () -> Resource
    ): Resource {
        slots.acquire()
        return suspendCancellableCoroutine { continuation ->
            val pending = AtomicReference<CancellableContinuation<Resource>?>(continuation)
            continuation.invokeOnCancellation {
                pending.compareAndSet(continuation, null)
                try {
                    cancel()
                } catch (_: Exception) {
                    // A failed provider cancellation must not prevent caller cancellation.
                }
            }
            try {
                // Do not attach a potentially stuck provider call to the caller's job/lifecycle.
                dispatcher.dispatch(EmptyCoroutineContext, Runnable {
                    try {
                        if (pending.get() == null) return@Runnable
                        val resource = acquire()
                        val receiver = pending.getAndSet(null)
                        if (receiver == null) {
                            closeUnclaimed(resource)
                        } else {
                            receiver.resume(resource) { _, value, _ -> closeUnclaimed(value) }
                        }
                    } catch (failure: Exception) {
                        pending.getAndSet(null)?.resumeWith(Result.failure(failure))
                    } finally {
                        slots.release()
                    }
                })
            } catch (failure: Exception) {
                slots.release()
                pending.getAndSet(null)?.resumeWith(Result.failure(failure))
            }
        }
    }
}
