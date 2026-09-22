package dev.soupslurpr.beautyxt.illustration

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Process
import dev.soupslurpr.beautyxt.ipc.TransferredFileDescriptor
import dev.soupslurpr.beautyxt.ipc.newIsolatedServiceInstanceName
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One private worker instance per enrichment operation; calls on that instance are serialized. */
internal class IllustrationWorkerConnection(
    context: Context,
    private val serviceName: String,
    private val maximumInputBytes: Int
) : ServiceConnection,
    IBinder.DeathRecipient,
    AutoCloseable {
    private val application = context.applicationContext
    private val connectionLock = Any()
    private val connected = CompletableDeferred<IIllustrationService>()
    private val service = AtomicReference<IIllustrationService?>()
    private val serviceBinder = AtomicReference<IBinder?>()
    private val pending = AtomicReference<Pending?>()
    private val closed = AtomicBoolean(false)
    private val bound = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val mutex = Mutex()
    private val instanceName = newIsolatedServiceInstanceName()
    private var nextJob = 1L

    val isClosed: Boolean get() = closed.get()

    suspend fun render(source: String, display: Boolean): IllustrationResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (source.isEmpty() || source.length > maximumInputBytes) {
                    return@withLock IllustrationResult.Fallback(IllustrationFailure.TooLarge)
                }
                val bytes = source.toByteArray(Charsets.UTF_8)
                if (bytes.size > maximumInputBytes) {
                    return@withLock IllustrationResult.Fallback(IllustrationFailure.TooLarge)
                }
                try {
                    withTimeout(7_000L) { renderBytes(bytes, display) }
                } catch (_: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    close()
                    IllustrationResult.Fallback(IllustrationFailure.TimedOut)
                } catch (cancelled: CancellationException) {
                    close()
                    throw cancelled
                } catch (_: IllustrationProtocolException) {
                    close()
                    IllustrationResult.Fallback(IllustrationFailure.Invalid)
                } catch (_: Exception) {
                    close()
                    IllustrationResult.Fallback(IllustrationFailure.Unavailable)
                } finally {
                    bytes.fill(0)
                }
            }
        }

    private suspend fun renderBytes(source: ByteArray, display: Boolean): IllustrationResult {
        check(!closed.get())
        if (started.compareAndSet(false, true)) bind()
        val remote = connected.await()
        currentCoroutineContext().ensureActive()
        IllustrationPacketBuffer.create().use { output ->
            val job = Pending(nextJob++)
            check(pending.compareAndSet(null, job))
            val callback = object : IIllustrationCallback.Stub() {
                override fun onResult(jobId: Long, status: Int, packetBytes: Int) {
                    if (Binder.getCallingUid() == Process.myUid() || jobId != job.id ||
                        status !in 0..5 || (status != 0 && packetBytes != 0) ||
                        (
                            status == 0 &&
                                packetBytes !in
                                IllustrationLimits.HEADER_BYTES..IllustrationLimits.MAX_PACKET_BYTES
                            )
                    ) {
                        job.result.completeExceptionally(IllustrationProtocolException())
                    } else {
                        job.result.complete(Receipt(status, packetBytes))
                    }
                }
            }
            var finished = false
            try {
                check(!closed.get())
                val transferred = TransferredFileDescriptor.from(output.duplicateWriter())
                try {
                    // No synchronous worker reply can strand the IO thread past its deadline.
                    remote.render(job.id, source, display, transferred, callback)
                } finally {
                    transferred.closeWithError("illustration writer transferred")
                }
                val receipt = job.result.await()
                currentCoroutineContext().ensureActive()
                val result = if (receipt.status == 0) {
                    IllustrationResult.Rendered(
                        IllustrationPacketDecoder.decode(output.readCompleted(receipt.bytes))
                    )
                } else {
                    IllustrationResult.Fallback(
                        when (receipt.status) {
                            1 -> IllustrationFailure.Unsupported
                            2 -> IllustrationFailure.TooLarge
                            3 -> IllustrationFailure.Invalid
                            5 -> IllustrationFailure.TimedOut
                            else -> IllustrationFailure.Unavailable
                        }
                    )
                }
                finished = true
                // A hard-timeout receipt can arrive just before Binder death. Do not race the
                // next request into that dying process; a later source gets a fresh instance.
                if (receipt.status == 4 || receipt.status == 5) close()
                return result
            } finally {
                if (!finished) runCatching { remote.cancel(job.id) }
                pending.compareAndSet(job, null)
                job.result.cancel()
            }
        }
    }

    private fun bind() {
        val accepted = application.bindIsolatedService(
            Intent().setClassName(application, serviceName),
            Context.BIND_AUTO_CREATE,
            instanceName,
            application.mainExecutor,
            this
        )
        check(accepted) { "Illustration service unavailable" }
        bound.set(true)
        if (closed.get()) unbind()
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        synchronized(connectionLock) {
            if (closed.get()) return
            try {
                binder.linkToDeath(this, 0)
                if (!serviceBinder.compareAndSet(null, binder)) {
                    binder.unlinkToDeath(this, 0)
                    return
                }
                val remote = IIllustrationService.Stub.asInterface(binder)
                service.set(remote)
                if (!connected.complete(remote)) close()
            } catch (_: Exception) {
                failConnection()
            }
        }
    }

    override fun onServiceDisconnected(name: ComponentName) = failConnection()
    override fun onBindingDied(name: ComponentName) = failConnection()
    override fun onNullBinding(name: ComponentName) = failConnection()
    override fun binderDied() = failConnection()

    private fun failConnection() {
        val failure = IllegalStateException("Illustration worker disconnected")
        connected.completeExceptionally(failure)
        pending.get()?.result?.completeExceptionally(failure)
        close()
    }

    override fun close() {
        synchronized(connectionLock) {
            if (!closed.compareAndSet(false, true)) return
            val remote = service.getAndSet(null)
            pending.getAndSet(null)?.let { job ->
                runCatching { remote?.cancel(job.id) }
                job.result.completeExceptionally(
                    IllegalStateException("Illustration worker closed")
                )
            }
            serviceBinder.getAndSet(null)?.let { binder ->
                runCatching { binder.unlinkToDeath(this, 0) }
            }
            connected.completeExceptionally(IllegalStateException("Illustration connection closed"))
        }
        unbind()
    }

    private fun unbind() {
        if (bound.compareAndSet(true, false)) runCatching { application.unbindService(this) }
    }

    private class Pending(val id: Long) {
        val result = CompletableDeferred<Receipt>()
    }
    private data class Receipt(val status: Int, val bytes: Int)
}
