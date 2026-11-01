/* Serializes connection publication, operation attachment, failure, and close. */
package dev.soupslurpr.beautyxt.ipc

import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.CompletableDeferred

/** Holds only live proxies and fails every attached waiter when its owner closes. */
internal class IsolatedServiceConnection<S : Any>(private val unavailable: (Throwable?) -> Exception) {
    private val lock = Any()
    private val connected = CompletableDeferred<S>()
    private var closed = false
    private var failure: Exception? = null
    private var binder: IBinder? = null
    private var service: S? = null
    private var operation: CompletableDeferred<*>? = null

    val isClosed: Boolean get() = synchronized(lock) { closed }
    val remote: S? get() = synchronized(lock) { service }

    suspend fun awaitService(): S {
        val result = connected.await()
        return synchronized(lock) {
            if (closed || failure != null) throw failure ?: unavailable(null)
            result
        }
    }

    /** Links and decodes outside the gate; a late result loses ownership after close. */
    fun connect(remoteBinder: IBinder, recipient: IBinder.DeathRecipient, decode: (IBinder) -> S) {
        if (synchronized(lock) { closed || failure != null || binder != null }) return
        try {
            remoteBinder.linkToDeath(recipient, 0)
            val remoteService = decode(remoteBinder)
            val retained = synchronized(lock) {
                if (closed || failure != null || binder != null) {
                    false
                } else {
                    binder = remoteBinder
                    service = remoteService
                    if (connected.complete(remoteService)) {
                        true
                    } else {
                        binder = null
                        service = null
                        false
                    }
                }
            }
            if (!retained) unlink(remoteBinder, recipient)
        } catch (cause: RemoteException) {
            unlink(remoteBinder, recipient)
            fail(cause)
        } catch (cause: RuntimeException) {
            unlink(remoteBinder, recipient)
            fail(cause)
        }
    }

    /** Attaches while live, or immediately fails a waiter offered after close. */
    fun attach(completion: CompletableDeferred<*>) {
        synchronized(lock) {
            val rejected = failure
            if (closed || rejected != null) {
                completion.completeExceptionally(rejected ?: unavailable(null))
            } else {
                check(operation == null) { "isolated service operation is already attached" }
                operation = completion
            }
        }
    }

    fun detach(completion: CompletableDeferred<*>) {
        synchronized(lock) { if (operation === completion) operation = null }
    }

    /** Closes publication before releasing the external death observation. */
    fun close(recipient: IBinder.DeathRecipient) {
        val previous = synchronized(lock) {
            if (closed) return
            closed = true
            val terminal = failure ?: unavailable(null).also { failure = it }
            service = null
            connected.completeExceptionally(terminal)
            operation?.completeExceptionally(terminal)
            operation = null
            binder.also { binder = null }
        }
        previous?.let { unlink(it, recipient) }
    }

    /** Fails existing and future waiters at the same boundary as proxy publication. */
    fun fail(cause: Throwable? = null) {
        synchronized(lock) {
            if (closed || failure != null) return
            val terminal = unavailable(cause)
            failure = terminal
            service = null
            connected.completeExceptionally(terminal)
            operation?.completeExceptionally(terminal)
        }
    }

    private fun unlink(remoteBinder: IBinder, recipient: IBinder.DeathRecipient) {
        try {
            remoteBinder.unlinkToDeath(recipient, 0)
        } catch (_: RuntimeException) {
            // The Binder can already be dead or absent after a failed/late link.
        }
    }
}
