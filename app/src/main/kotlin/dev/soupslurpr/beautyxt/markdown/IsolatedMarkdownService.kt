package dev.soupslurpr.beautyxt.markdown

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dev.soupslurpr.beautyxt.ipc.TransferredFileDescriptor
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory

private const val NO_NATIVE_JOB_HANDLE = 0L
private const val MARKDOWN_THREAD_NAME = "BeauTyXT Markdown"
private const val REJECTED_INPUT_ERROR = "markdown input rejected"
private const val REJECTED_OUTPUT_ERROR = "markdown output rejected"

/** Runs one bounded native Markdown render inside a dedicated isolated process. */
class IsolatedMarkdownService : Service() {
    private val stateLock = Any()
    private val renderExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(MarkdownThreadFactory)
    private var activeJob: MarkdownJob? = null
    private var destroyed = false

    private val serviceBinder =
        object : IMarkdownService.Stub() {
            override fun startRender(
                jobId: Long,
                input: TransferredFileDescriptor?,
                output: TransferredFileDescriptor?,
                expectedInputBytes: Long,
                maxPacketBytes: Long,
                timeoutMillis: Long,
                callback: IMarkdownCallback?
            ): Int = acceptRender(
                jobId = jobId,
                input = input,
                output = output,
                expectedInputBytes = expectedInputBytes,
                maxPacketBytes = maxPacketBytes,
                timeoutMillis = timeoutMillis,
                callback = callback
            )

            override fun cancelRender(jobId: Long) {
                requestCancellation(jobId)
            }
        }

    override fun onBind(intent: Intent?): IBinder = serviceBinder

    override fun onUnbind(intent: Intent?): Boolean {
        requestCancellation()
        return false
    }

    override fun onDestroy() {
        synchronized(stateLock) {
            destroyed = true
            activeJob?.let(::requestCancellationLocked)
        }
        renderExecutor.shutdown()
        super.onDestroy()
    }

    /** Validates, owns, and schedules one Markdown render request. */
    private fun acceptRender(
        jobId: Long,
        input: TransferredFileDescriptor?,
        output: TransferredFileDescriptor?,
        expectedInputBytes: Long,
        maxPacketBytes: Long,
        timeoutMillis: Long,
        callback: IMarkdownCallback?
    ): Int {
        val inputDescriptor = takeTransferredDescriptor(input)
        val outputDescriptor = takeTransferredDescriptor(output)
        input?.closeWithError(REJECTED_INPUT_ERROR)
        output?.closeWithError(REJECTED_OUTPUT_ERROR)
        if (inputDescriptor == null || outputDescriptor == null) {
            return rejectRender(
                MarkdownProtocol.ACCEPT_INVALID_ARGUMENT,
                inputDescriptor,
                outputDescriptor
            )
        }
        if (
            jobId <= 0L ||
            expectedInputBytes !in
            MarkdownProtocol.MIN_INPUT_BYTES..MarkdownProtocol.MAX_INPUT_BYTES ||
            maxPacketBytes !in
            MarkdownProtocol.MIN_PACKET_BYTES..MarkdownProtocol.MAX_PACKET_BYTES ||
            timeoutMillis !in
            MarkdownProtocol.MIN_TIMEOUT_MILLIS..MarkdownProtocol.MAX_TIMEOUT_MILLIS ||
            !isValid(inputDescriptor) ||
            !isValid(outputDescriptor)
        ) {
            return rejectRender(
                MarkdownProtocol.ACCEPT_INVALID_ARGUMENT,
                inputDescriptor,
                outputDescriptor
            )
        }
        val renderCallback =
            callback
                ?: return rejectRender(
                    MarkdownProtocol.ACCEPT_CALLBACK_UNAVAILABLE,
                    inputDescriptor,
                    outputDescriptor
                )
        val callbackBinder = renderCallback.asBinder()
        if (!callbackBinder.isBinderAlive) {
            return rejectRender(
                MarkdownProtocol.ACCEPT_CALLBACK_UNAVAILABLE,
                inputDescriptor,
                outputDescriptor
            )
        }

        val job =
            MarkdownJob(
                jobId = jobId,
                input = inputDescriptor,
                output = outputDescriptor,
                expectedInputBytes = expectedInputBytes,
                maxPacketBytes = maxPacketBytes,
                timeoutMillis = timeoutMillis,
                callback = renderCallback,
                callbackBinder = callbackBinder
            )
        val acceptCode = registerJob(job)
        if (acceptCode != MarkdownProtocol.ACCEPT_ACCEPTED) {
            closeDescriptors(inputDescriptor, outputDescriptor)
            return acceptCode
        }

        sendRunningStatus(job)
        try {
            renderExecutor.execute { runRender(job) }
        } catch (_: RejectedExecutionException) {
            val resultCode =
                if (isCancellationRequested(job)) {
                    MarkdownProtocol.RESULT_CANCELLED
                } else {
                    MarkdownProtocol.RESULT_INTERNAL
                }
            finishRender(job, MarkdownCompletion.fromResult(resultCode))
        }
        return MarkdownProtocol.ACCEPT_ACCEPTED
    }

    /** Registers a job and its callback death recipient atomically. */
    private fun registerJob(job: MarkdownJob): Int = synchronized(stateLock) {
        if (destroyed) {
            return@synchronized MarkdownProtocol.ACCEPT_CALLBACK_UNAVAILABLE
        }
        if (activeJob != null) {
            return@synchronized MarkdownProtocol.ACCEPT_BUSY
        }
        activeJob = job
        try {
            job.callbackBinder.linkToDeath(job.callbackDeathRecipient, 0)
        } catch (_: RemoteException) {
            activeJob = null
            job.callbackAvailable = false
            return@synchronized MarkdownProtocol.ACCEPT_CALLBACK_UNAVAILABLE
        } catch (_: RuntimeException) {
            activeJob = null
            job.callbackAvailable = false
            return@synchronized MarkdownProtocol.ACCEPT_CALLBACK_UNAVAILABLE
        }
        MarkdownProtocol.ACCEPT_ACCEPTED
    }

    /** Runs the native renderer and converts its bounded result. */
    private fun runRender(job: MarkdownJob) {
        val resultValues = LongArray(MarkdownProtocol.RESULT_VALUE_COUNT)
        var resultCode = MarkdownProtocol.RESULT_INTERNAL
        var nativeHandle = NO_NATIVE_JOB_HANDLE
        try {
            if (isCancellationRequested(job)) {
                resultCode = MarkdownProtocol.RESULT_CANCELLED
            } else {
                nativeHandle = NativeMarkdownWorker.createJob()
                check(nativeHandle > NO_NATIVE_JOB_HANDLE) {
                    "native Markdown job handle must be positive"
                }
                publishNativeHandle(job, nativeHandle)
                resultCode =
                    NativeMarkdownWorker.runJob(
                        jobHandle = nativeHandle,
                        inputFileDescriptor = job.input.fd,
                        outputFileDescriptor = job.output.fd,
                        expectedInputBytes = job.expectedInputBytes,
                        maxPacketBytes = job.maxPacketBytes,
                        timeoutMillis = job.timeoutMillis,
                        resultValues = resultValues
                    )
            }
        } catch (_: Exception) {
            resultCode = MarkdownProtocol.RESULT_INTERNAL
        } catch (_: LinkageError) {
            resultCode = MarkdownProtocol.RESULT_INTERNAL
        } finally {
            if (nativeHandle != NO_NATIVE_JOB_HANDLE) {
                destroyNativeJob(job, nativeHandle)
            }
        }
        if (
            resultCode == MarkdownProtocol.RESULT_SUCCESS &&
            hasInputCompletionError(job.input)
        ) {
            resultCode = MarkdownProtocol.RESULT_INPUT_IO
            resetOutput(job.output)
            resultValues.fill(0L)
        }
        finishRender(job, decodeCompletion(job, resultCode, resultValues))
    }

    /** Publishes a native handle and applies pending cancellation. */
    private fun publishNativeHandle(job: MarkdownJob, nativeHandle: Long) {
        synchronized(stateLock) {
            check(activeJob === job) { "Markdown job is no longer active" }
            check(job.nativeHandle == NO_NATIVE_JOB_HANDLE) {
                "native Markdown job handle is already set"
            }
            job.nativeHandle = nativeHandle
            if (job.cancellationRequested) {
                cancelNativeJobLocked(job)
            }
        }
    }

    /** Destroys a published native handle without racing cancellation. */
    private fun destroyNativeJob(job: MarkdownJob, nativeHandle: Long) {
        synchronized(stateLock) {
            if (job.nativeHandle != nativeHandle) {
                return
            }
            try {
                NativeMarkdownWorker.destroyJob(nativeHandle)
            } catch (_: Exception) {
                // Preserve the authoritative native result.
            } catch (_: LinkageError) {
                // Preserve the authoritative native result.
            } finally {
                job.nativeHandle = NO_NATIVE_JOB_HANDLE
            }
        }
    }

    /** Decodes a native result into one canonical terminal callback value. */
    private fun decodeCompletion(
        job: MarkdownJob,
        rawResultCode: Int,
        values: LongArray
    ): MarkdownCompletion {
        val inputBytes = values[MarkdownProtocol.RESULT_INPUT_BYTES_INDEX]
        val packetBytes = values[MarkdownProtocol.RESULT_PACKET_BYTES_INDEX]
        val blockCount = values[MarkdownProtocol.RESULT_BLOCK_COUNT_INDEX]
        val spanCount = values[MarkdownProtocol.RESULT_SPAN_COUNT_INDEX]
        val documentFlags = values[MarkdownProtocol.RESULT_FLAGS_INDEX]
        val validStatistics =
            areValidMarkdownStatistics(
                resultCode = rawResultCode,
                expectedInputBytes = job.expectedInputBytes,
                maxPacketBytes = job.maxPacketBytes,
                inputBytes = inputBytes,
                packetBytes = packetBytes,
                blockCount = blockCount,
                spanCount = spanCount,
                documentFlags = documentFlags
            )
        val resultCode =
            if (validStatistics) {
                sanitizeResultCode(rawResultCode)
            } else {
                MarkdownProtocol.RESULT_INTERNAL
            }
        return MarkdownCompletion(
            state = stateForResult(resultCode),
            resultCode = resultCode,
            inputBytes = if (validStatistics) inputBytes else 0L,
            packetBytes = if (validStatistics) packetBytes else 0L,
            blockCount = if (validStatistics) blockCount else 0L,
            spanCount = if (validStatistics) spanCount else 0L,
            documentFlags = if (validStatistics) documentFlags else 0L
        )
    }

    /** Returns a stable result code for any native return value. */
    private fun sanitizeResultCode(resultCode: Int): Int = when (resultCode) {
        MarkdownProtocol.RESULT_SUCCESS,
        MarkdownProtocol.RESULT_CANCELLED,
        MarkdownProtocol.RESULT_TIMEOUT,
        MarkdownProtocol.RESULT_INPUT_LIMIT,
        MarkdownProtocol.RESULT_INPUT_LENGTH_MISMATCH,
        MarkdownProtocol.RESULT_INVALID_UTF8,
        MarkdownProtocol.RESULT_RENDER_LIMIT,
        MarkdownProtocol.RESULT_INVALID_DESCRIPTOR,
        MarkdownProtocol.RESULT_INPUT_IO,
        MarkdownProtocol.RESULT_OUTPUT_IO,
        MarkdownProtocol.RESULT_INTERNAL -> resultCode

        else -> MarkdownProtocol.RESULT_INTERNAL
    }

    /** Returns whether a reliable snapshot pipe reported producer failure. */
    private fun hasInputCompletionError(input: ParcelFileDescriptor): Boolean = try {
        if (!input.canDetectErrors()) {
            false
        } else {
            input.checkError()
            false
        }
    } catch (_: IOException) {
        true
    } catch (_: RuntimeException) {
        true
    }

    /** Best-effort clears a failed anonymous output buffer. */
    private fun resetOutput(output: ParcelFileDescriptor) {
        val descriptor =
            try {
                output.fileDescriptor
            } catch (_: RuntimeException) {
                return
            }
        try {
            Os.ftruncate(descriptor, 0L)
        } catch (_: ErrnoException) {
            // Client-side packet validation still rejects this output.
        }
        try {
            Os.lseek(descriptor, 0L, OsConstants.SEEK_SET)
        } catch (_: ErrnoException) {
            // Client-side packet validation still rejects this output.
        }
    }

    /** Maps one fixed result code to its terminal state. */
    private fun stateForResult(resultCode: Int): Int = when (resultCode) {
        MarkdownProtocol.RESULT_SUCCESS -> MarkdownProtocol.STATE_COMPLETE
        MarkdownProtocol.RESULT_CANCELLED -> MarkdownProtocol.STATE_CANCELLED
        else -> MarkdownProtocol.STATE_FAILED
    }

    /** Sends the nonterminal running state when the callback remains alive. */
    private fun sendRunningStatus(job: MarkdownJob) {
        val shouldNotify =
            synchronized(stateLock) {
                activeJob === job && job.callbackAvailable && !job.terminalDelivered
            }
        if (!shouldNotify) {
            return
        }
        try {
            job.callback.onMarkdownStatus(
                job.jobId,
                MarkdownProtocol.STATE_RUNNING,
                MarkdownProtocol.RESULT_SUCCESS,
                0L,
                0L,
                0L,
                0L,
                0L
            )
        } catch (_: RemoteException) {
            handleCallbackDeath(job)
        } catch (_: RuntimeException) {
            handleCallbackDeath(job)
        }
    }

    /** Closes job resources and emits exactly one terminal state. */
    private fun finishRender(job: MarkdownJob, completion: MarkdownCompletion) {
        closeDescriptors(job.input, job.output)
        val notifyCallback =
            synchronized(stateLock) {
                if (job.terminalDelivered) {
                    return
                }
                job.terminalDelivered = true
                job.callbackAvailable
            }
        unlinkCallbackDeath(job)
        if (notifyCallback) {
            try {
                job.callback.onMarkdownStatus(
                    job.jobId,
                    completion.state,
                    completion.resultCode,
                    completion.inputBytes,
                    completion.packetBytes,
                    completion.blockCount,
                    completion.spanCount,
                    completion.documentFlags
                )
            } catch (_: RemoteException) {
                // The terminal state has already been emitted exactly once.
            } catch (_: RuntimeException) {
                // The terminal state has already been emitted exactly once.
            }
        }
        synchronized(stateLock) {
            if (activeJob === job) {
                activeJob = null
            }
        }
    }

    /** Cancels the active job when its identifier matches. */
    private fun requestCancellation(jobId: Long) {
        synchronized(stateLock) {
            val job = activeJob ?: return
            if (job.jobId == jobId) {
                requestCancellationLocked(job)
            }
        }
    }

    /** Cancels the active job regardless of its identifier. */
    private fun requestCancellation() {
        synchronized(stateLock) {
            activeJob?.let(::requestCancellationLocked)
        }
    }

    /** Marks cancellation and forwards it to a published native handle. */
    private fun requestCancellationLocked(job: MarkdownJob) {
        if (job.terminalDelivered || job.cancellationRequested) {
            return
        }
        job.cancellationRequested = true
        cancelNativeJobLocked(job)
    }

    /** Requests native cancellation while the handle cannot be destroyed. */
    private fun cancelNativeJobLocked(job: MarkdownJob) {
        val nativeHandle = job.nativeHandle
        if (nativeHandle == NO_NATIVE_JOB_HANDLE) {
            return
        }
        try {
            NativeMarkdownWorker.cancelJob(nativeHandle)
        } catch (_: Exception) {
            // Preserve the eventual authoritative native result.
        } catch (_: LinkageError) {
            // Preserve the eventual authoritative native result.
        }
    }

    /** Returns whether cancellation was requested for this job. */
    private fun isCancellationRequested(job: MarkdownJob): Boolean =
        synchronized(stateLock) { job.cancellationRequested }

    /** Cancels a job whose callback binder has died. */
    private fun handleCallbackDeath(job: MarkdownJob) {
        synchronized(stateLock) {
            if (activeJob !== job || job.terminalDelivered) {
                return
            }
            job.callbackAvailable = false
            requestCancellationLocked(job)
        }
    }

    /** Unlinks the callback death recipient after job completion. */
    private fun unlinkCallbackDeath(job: MarkdownJob) {
        try {
            job.callbackBinder.unlinkToDeath(job.callbackDeathRecipient, 0)
        } catch (_: RuntimeException) {
            // The callback binder is already dead or unlinked.
        }
    }

    /** Rejects a render and closes every descriptor received by the service. */
    private fun rejectRender(
        acceptCode: Int,
        input: ParcelFileDescriptor?,
        output: ParcelFileDescriptor?
    ): Int {
        closeDescriptors(input, output)
        return acceptCode
    }

    /** Returns whether a descriptor is open at the service boundary. */
    private fun isValid(descriptor: ParcelFileDescriptor): Boolean = try {
        descriptor.fileDescriptor.valid()
    } catch (_: RuntimeException) {
        false
    }

    /** Claims one descriptor transferred into the isolated process. */
    private fun takeTransferredDescriptor(
        transferredDescriptor: TransferredFileDescriptor?
    ): ParcelFileDescriptor? = try {
        transferredDescriptor?.takeDescriptor()
    } catch (_: RuntimeException) {
        null
    }

    /** Closes two service-owned descriptors without leaking close failures. */
    private fun closeDescriptors(input: ParcelFileDescriptor?, output: ParcelFileDescriptor?) {
        closeDescriptor(input)
        if (output !== input) {
            closeDescriptor(output)
        }
    }

    /** Closes one service-owned descriptor. */
    private fun closeDescriptor(descriptor: ParcelFileDescriptor?) {
        try {
            descriptor?.close()
        } catch (_: IOException) {
            // Descriptor ownership ends even when close reports an error.
        }
    }

    /** Holds the mutable lifecycle state for one accepted render job. */
    private inner class MarkdownJob(
        val jobId: Long,
        val input: ParcelFileDescriptor,
        val output: ParcelFileDescriptor,
        val expectedInputBytes: Long,
        val maxPacketBytes: Long,
        val timeoutMillis: Long,
        val callback: IMarkdownCallback,
        val callbackBinder: IBinder
    ) {
        val callbackDeathRecipient = IBinder.DeathRecipient { handleCallbackDeath(this) }
        var nativeHandle = NO_NATIVE_JOB_HANDLE
        var cancellationRequested = false
        var callbackAvailable = true
        var terminalDelivered = false
    }

    /** Holds one sanitized terminal render status. */
    private data class MarkdownCompletion(
        val state: Int,
        val resultCode: Int,
        val inputBytes: Long,
        val packetBytes: Long,
        val blockCount: Long,
        val spanCount: Long,
        val documentFlags: Long
    ) {
        companion object {
            /** Creates a terminal result without native statistics. */
            fun fromResult(resultCode: Int): MarkdownCompletion = MarkdownCompletion(
                state = stateForResultCode(resultCode),
                resultCode = resultCode,
                inputBytes = 0L,
                packetBytes = 0L,
                blockCount = 0L,
                spanCount = 0L,
                documentFlags = 0L
            )

            /** Maps one fixed result code without a service instance. */
            private fun stateForResultCode(resultCode: Int): Int = when (resultCode) {
                MarkdownProtocol.RESULT_SUCCESS -> MarkdownProtocol.STATE_COMPLETE
                MarkdownProtocol.RESULT_CANCELLED -> MarkdownProtocol.STATE_CANCELLED
                else -> MarkdownProtocol.STATE_FAILED
            }
        }
    }

    /** Creates the dedicated Markdown executor thread. */
    private object MarkdownThreadFactory : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread = Thread(runnable, MARKDOWN_THREAD_NAME)
    }
}

/** Returns whether native Markdown statistics form one canonical result. */
internal fun areValidMarkdownStatistics(
    resultCode: Int,
    expectedInputBytes: Long,
    maxPacketBytes: Long,
    inputBytes: Long,
    packetBytes: Long,
    blockCount: Long,
    spanCount: Long,
    documentFlags: Long
): Boolean {
    if (
        expectedInputBytes !in
        MarkdownProtocol.MIN_INPUT_BYTES..MarkdownProtocol.MAX_INPUT_BYTES ||
        maxPacketBytes !in
        MarkdownProtocol.MIN_PACKET_BYTES..MarkdownProtocol.MAX_PACKET_BYTES ||
        inputBytes < 0L ||
        packetBytes < 0L ||
        blockCount < 0L ||
        spanCount < 0L ||
        documentFlags < 0L ||
        documentFlags and MarkdownProtocol.DOCUMENT_FLAGS_MASK.toLong().inv() != 0L
    ) {
        return false
    }
    return if (resultCode == MarkdownProtocol.RESULT_SUCCESS) {
        inputBytes == expectedInputBytes &&
            packetBytes in MarkdownProtocol.MIN_PACKET_BYTES..maxPacketBytes &&
            blockCount <= MarkdownProtocol.MAX_BLOCK_COUNT &&
            spanCount <= MarkdownProtocol.MAX_SPAN_COUNT
    } else {
        inputBytes == 0L &&
            packetBytes == 0L &&
            blockCount == 0L &&
            spanCount == 0L &&
            documentFlags == 0L
    }
}
