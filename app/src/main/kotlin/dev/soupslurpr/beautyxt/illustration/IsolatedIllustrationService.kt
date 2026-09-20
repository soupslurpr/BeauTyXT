package dev.soupslurpr.beautyxt.illustration

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import dev.soupslurpr.beautyxt.ipc.TransferredFileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A host-owned native preparation budget was reached before rendering began. */
internal class IllustrationResourceLimitException : RuntimeException()

/** Private base for independently isolated math and diagram engines, not a document owner. */
abstract class IsolatedIllustrationService : Service() {
    protected abstract val maximumInputBytes: Int
    protected abstract val timeoutMillis: Long
    protected abstract fun renderNative(source: ByteArray, display: Boolean): ByteArray

    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { work ->
        Thread(work, "BeauTyXT illustration")
    }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { work ->
        Thread(work, "BeauTyXT illustration deadline")
    }
    private var active: Job? = null
    private var destroyed = false

    private val binder = object : IIllustrationService.Stub() {
        override fun render(
            jobId: Long,
            source: ByteArray?,
            display: Boolean,
            output: TransferredFileDescriptor?,
            callback: IIllustrationCallback?
        ) {
            fun reject() {
                source?.fill(0)
                runCatching { callback?.onResult(jobId, 4, 0) }
            }
            val descriptor = try {
                output?.takeDescriptor()
            } catch (_: RuntimeException) {
                null
            }
            output?.closeWithError("illustration output rejected")
            if (jobId <= 0 || source == null || source.size !in 1..maximumInputBytes ||
                descriptor == null || callback == null || !validOutput(descriptor)
            ) {
                closeQuietly(descriptor)
                reject()
                return
            }
            val job = Job(jobId, source, display, descriptor, callback)
            synchronized(lock) {
                if (destroyed || active != null) {
                    closeQuietly(descriptor)
                    reject()
                    return
                }
                try {
                    callback.asBinder().linkToDeath(job.death, 0)
                    active = job
                    job.deadline =
                        watchdog.schedule(
                            {
                                terminate(job, timedOut = true)
                            },
                            timeoutMillis,
                            TimeUnit.MILLISECONDS
                        )
                    executor.execute { run(job) }
                } catch (_: Exception) {
                    active = null
                    job.deadline?.cancel(false)
                    runCatching { callback.asBinder().unlinkToDeath(job.death, 0) }
                    closeQuietly(descriptor)
                    reject()
                    return
                }
            }
        }

        override fun cancel(jobId: Long) {
            synchronized(lock) { active?.takeIf { it.id == jobId }?.let(::terminate) }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        // Each client uses a unique bindIsolatedService instance; no other document shares it.
        Process.killProcess(Process.myPid())
        return false
    }

    override fun onDestroy() {
        synchronized(lock) {
            destroyed = true
            active?.let(::terminate)
        }
        executor.shutdownNow()
        watchdog.shutdownNow()
        super.onDestroy()
    }

    private fun run(job: Job) {
        var status = 4
        var packetBytes = 0
        try {
            val response = renderNative(job.source, job.display)
            check(
                response.size in
                    Int.SIZE_BYTES..IllustrationLimits.MAX_PACKET_BYTES + Int.SIZE_BYTES
            )
            status = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN).int
            check(status in 0..3 || status == 5)
            if (status == 0) {
                packetBytes = response.size - Int.SIZE_BYTES
                check(packetBytes >= IllustrationLimits.HEADER_BYTES)
                var offset = 0
                while (offset < packetBytes) {
                    val written = Os.pwrite(
                        job.output.fileDescriptor,
                        response,
                        offset + Int.SIZE_BYTES,
                        packetBytes - offset,
                        offset.toLong()
                    )
                    check(written > 0)
                    offset += written
                }
            } else {
                check(response.size == Int.SIZE_BYTES)
            }
        } catch (_: IllustrationResourceLimitException) {
            status = 2
            packetBytes = 0
        } catch (_: Exception) {
            status = 4
            packetBytes = 0
        } catch (_: LinkageError) {
            status = 4
            packetBytes = 0
        } finally {
            closeQuietly(job.output)
            job.source.fill(0)
        }
        synchronized(lock) {
            if (active !== job || destroyed) return
            active = null
            job.deadline?.cancel(false)
            runCatching { job.callback.asBinder().unlinkToDeath(job.death, 0) }
            runCatching { job.callback.onResult(job.id, status, packetBytes) }
        }
    }

    /** A hard stop also covers upstream parsing/layout code with no cooperative cancellation. */
    private fun terminate(job: Job, timedOut: Boolean = false) {
        synchronized(lock) {
            if (active === job) {
                destroyed = true
                active = null
                try {
                    // Best effort: the one-way receipt may race Binder death. Either outcome
                    // remains a fallback, and the client never depends on this receipt to unblock.
                    if (timedOut) job.callback.onResult(job.id, 5, 0)
                } finally {
                    Process.killProcess(Process.myPid())
                }
            }
        }
    }

    private fun validOutput(descriptor: ParcelFileDescriptor): Boolean = try {
        val stat = Os.fstat(descriptor.fileDescriptor)
        OsConstants.S_ISREG(stat.st_mode) && stat.st_nlink == 0L &&
            stat.st_size == IllustrationLimits.MAX_PACKET_BYTES.toLong()
    } catch (_: Exception) {
        false
    }

    private fun closeQuietly(descriptor: ParcelFileDescriptor?) {
        runCatching { descriptor?.close() }
    }

    private inner class Job(
        val id: Long,
        val source: ByteArray,
        val display: Boolean,
        val output: ParcelFileDescriptor,
        val callback: IIllustrationCallback
    ) {
        val death = IBinder.DeathRecipient { terminate(this) }
        var deadline: ScheduledFuture<*>? = null
    }
}

/** Math failures and its hard deadline cannot terminate the editor or Markdown parser. */
class IsolatedMathService : IsolatedIllustrationService() {
    override val maximumInputBytes = IllustrationLimits.MAX_MATH_SOURCE_BYTES
    override val timeoutMillis = 2_000L
    override fun renderNative(source: ByteArray, display: Boolean): ByteArray =
        NativeMathRenderer.render(source, display)
}

/** Native diagram parsing, font shaping and geometry conversion have their own hard stop. */
class IsolatedDiagramService : IsolatedIllustrationService() {
    override val maximumInputBytes = IllustrationLimits.MAX_DIAGRAM_SOURCE_BYTES
    override val timeoutMillis = 3_000L
    override fun renderNative(source: ByteArray, display: Boolean): ByteArray =
        NativeDiagramRenderer.draw(source)
}
