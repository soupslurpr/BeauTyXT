package dev.soupslurpr.beautyxt.transfer

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
private const val TRANSFER_THREAD_NAME = "BeauTyXT Transfer"
private const val REJECTED_INPUT_ERROR = "transfer input rejected"
private const val REJECTED_OUTPUT_ERROR = "transfer output rejected"

/** Runs one bounded native QR or NFC transformation in an isolated process. */
class IsolatedTransferService : Service() {
    private val stateLock = Any()
    private val transferExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(TransferThreadFactory)
    private var activeJob: TransferJob? = null
    private var destroyed = false

    private val serviceBinder =
        object : ITransferService.Stub() {
            override fun startTransfer(
                jobId: Long,
                operation: Int,
                input: TransferredFileDescriptor?,
                output: TransferredFileDescriptor?,
                expectedInputBytes: Long,
                argumentZero: Long,
                argumentOne: Long,
                timeoutMillis: Long,
                callback: ITransferCallback?
            ): Int = acceptTransfer(
                jobId = jobId,
                operation = operation,
                input = input,
                output = output,
                expectedInputBytes = expectedInputBytes,
                argumentZero = argumentZero,
                argumentOne = argumentOne,
                timeoutMillis = timeoutMillis,
                callback = callback
            )

            override fun cancelTransfer(jobId: Long) {
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
        transferExecutor.shutdown()
        super.onDestroy()
    }

    /** Validates, owns, and schedules one transfer request. */
    private fun acceptTransfer(
        jobId: Long,
        operation: Int,
        input: TransferredFileDescriptor?,
        output: TransferredFileDescriptor?,
        expectedInputBytes: Long,
        argumentZero: Long,
        argumentOne: Long,
        timeoutMillis: Long,
        callback: ITransferCallback?
    ): Int {
        val inputDescriptor = takeTransferredDescriptor(input)
        val outputDescriptor = takeTransferredDescriptor(output)
        input?.closeWithError(REJECTED_INPUT_ERROR)
        output?.closeWithError(REJECTED_OUTPUT_ERROR)
        if (inputDescriptor == null || outputDescriptor == null) {
            return rejectTransfer(
                TransferProtocol.ACCEPT_INVALID_ARGUMENT,
                inputDescriptor,
                outputDescriptor
            )
        }
        if (
            jobId <= 0L ||
            !isValidTransferRequest(
                operation = operation,
                expectedInputBytes = expectedInputBytes,
                argumentZero = argumentZero,
                argumentOne = argumentOne,
                timeoutMillis = timeoutMillis
            ) ||
            !isValid(inputDescriptor) ||
            !isValid(outputDescriptor)
        ) {
            return rejectTransfer(
                TransferProtocol.ACCEPT_INVALID_ARGUMENT,
                inputDescriptor,
                outputDescriptor
            )
        }
        val transferCallback =
            callback
                ?: return rejectTransfer(
                    TransferProtocol.ACCEPT_CALLBACK_UNAVAILABLE,
                    inputDescriptor,
                    outputDescriptor
                )
        val callbackBinder = transferCallback.asBinder()
        if (!callbackBinder.isBinderAlive) {
            return rejectTransfer(
                TransferProtocol.ACCEPT_CALLBACK_UNAVAILABLE,
                inputDescriptor,
                outputDescriptor
            )
        }
        val job =
            TransferJob(
                jobId = jobId,
                operation = operation,
                input = inputDescriptor,
                output = outputDescriptor,
                expectedInputBytes = expectedInputBytes,
                argumentZero = argumentZero,
                argumentOne = argumentOne,
                timeoutMillis = timeoutMillis,
                callback = transferCallback,
                callbackBinder = callbackBinder
            )
        val acceptCode = registerJob(job)
        if (acceptCode != TransferProtocol.ACCEPT_ACCEPTED) {
            closeDescriptors(inputDescriptor, outputDescriptor)
            return acceptCode
        }
        sendRunningStatus(job)
        try {
            transferExecutor.execute { runTransfer(job) }
        } catch (_: RejectedExecutionException) {
            val resultCode =
                if (isCancellationRequested(job)) {
                    TransferProtocol.RESULT_CANCELLED
                } else {
                    TransferProtocol.RESULT_INTERNAL
                }
            finishTransfer(job, TransferCompletion.fromResult(resultCode))
        }
        return TransferProtocol.ACCEPT_ACCEPTED
    }

    /** Registers one job and its callback death recipient atomically. */
    private fun registerJob(job: TransferJob): Int = synchronized(stateLock) {
        if (destroyed) {
            return@synchronized TransferProtocol.ACCEPT_CALLBACK_UNAVAILABLE
        }
        if (activeJob != null) {
            return@synchronized TransferProtocol.ACCEPT_BUSY
        }
        activeJob = job
        try {
            job.callbackBinder.linkToDeath(job.callbackDeathRecipient, 0)
        } catch (_: RemoteException) {
            activeJob = null
            job.callbackAvailable = false
            return@synchronized TransferProtocol.ACCEPT_CALLBACK_UNAVAILABLE
        } catch (_: RuntimeException) {
            activeJob = null
            job.callbackAvailable = false
            return@synchronized TransferProtocol.ACCEPT_CALLBACK_UNAVAILABLE
        }
        TransferProtocol.ACCEPT_ACCEPTED
    }

    /** Runs the native transformation and converts its bounded result. */
    private fun runTransfer(job: TransferJob) {
        val resultValues = LongArray(TransferProtocol.RESULT_VALUE_COUNT)
        var resultCode = TransferProtocol.RESULT_INTERNAL
        var nativeHandle = NO_NATIVE_JOB_HANDLE
        try {
            if (isCancellationRequested(job)) {
                resultCode = TransferProtocol.RESULT_CANCELLED
            } else {
                nativeHandle = NativeTransferWorker.createJob()
                check(nativeHandle > NO_NATIVE_JOB_HANDLE) {
                    "native transfer job handle must be positive"
                }
                publishNativeHandle(job, nativeHandle)
                resultCode =
                    NativeTransferWorker.runJob(
                        jobHandle = nativeHandle,
                        operation = job.operation,
                        inputFileDescriptor = job.input.fd,
                        outputFileDescriptor = job.output.fd,
                        expectedInputBytes = job.expectedInputBytes,
                        argumentZero = job.argumentZero,
                        argumentOne = job.argumentOne,
                        timeoutMillis = job.timeoutMillis,
                        resultValues = resultValues
                    )
            }
        } catch (_: Exception) {
            resultCode = TransferProtocol.RESULT_INTERNAL
        } catch (_: LinkageError) {
            resultCode = TransferProtocol.RESULT_INTERNAL
        } finally {
            if (nativeHandle != NO_NATIVE_JOB_HANDLE) {
                destroyNativeJob(job, nativeHandle)
            }
        }
        finishTransfer(job, decodeCompletion(job, resultCode, resultValues))
    }

    /** Publishes one native handle and applies pending cancellation. */
    private fun publishNativeHandle(job: TransferJob, nativeHandle: Long) {
        synchronized(stateLock) {
            check(activeJob === job) { "transfer job is no longer active" }
            check(job.nativeHandle == NO_NATIVE_JOB_HANDLE) {
                "native transfer job handle is already set"
            }
            job.nativeHandle = nativeHandle
            if (job.cancellationRequested) {
                cancelNativeJobLocked(job)
            }
        }
    }

    /** Destroys one published native handle without racing cancellation. */
    private fun destroyNativeJob(job: TransferJob, nativeHandle: Long) {
        synchronized(stateLock) {
            if (job.nativeHandle != nativeHandle) {
                return
            }
            try {
                NativeTransferWorker.destroyJob(nativeHandle)
            } catch (_: Exception) {
                // Preserve the authoritative native result.
            } catch (_: LinkageError) {
                // Preserve the authoritative native result.
            } finally {
                job.nativeHandle = NO_NATIVE_JOB_HANDLE
            }
        }
    }

    /** Decodes one native result into a canonical terminal callback value. */
    private fun decodeCompletion(
        job: TransferJob,
        rawResultCode: Int,
        values: LongArray
    ): TransferCompletion {
        val inputBytes = values[TransferProtocol.RESULT_INPUT_BYTES_INDEX]
        val outputBytes = values[TransferProtocol.RESULT_OUTPUT_BYTES_INDEX]
        val detailZero = values[TransferProtocol.RESULT_DETAIL_ZERO_INDEX]
        val detailOne = values[TransferProtocol.RESULT_DETAIL_ONE_INDEX]
        val validStatistics =
            areValidTransferStatistics(
                resultCode = rawResultCode,
                operation = job.operation,
                expectedInputBytes = job.expectedInputBytes,
                argumentZero = job.argumentZero,
                argumentOne = job.argumentOne,
                inputBytes = inputBytes,
                outputBytes = outputBytes,
                detailZero = detailZero,
                detailOne = detailOne
            )
        val resultCode =
            if (validStatistics) {
                sanitizeResultCode(rawResultCode)
            } else {
                resetOutput(job.output)
                TransferProtocol.RESULT_INTERNAL
            }
        return TransferCompletion(
            state = stateForResult(resultCode),
            resultCode = resultCode,
            inputBytes = if (validStatistics) inputBytes else 0L,
            outputBytes = if (validStatistics) outputBytes else 0L,
            detailZero = if (validStatistics) detailZero else 0L,
            detailOne = if (validStatistics) detailOne else 0L
        )
    }

    /** Returns one stable result code for any native return value. */
    private fun sanitizeResultCode(resultCode: Int): Int = when (resultCode) {
        TransferProtocol.RESULT_SUCCESS,
        TransferProtocol.RESULT_CANCELLED,
        TransferProtocol.RESULT_TIMEOUT,
        TransferProtocol.RESULT_INPUT_LIMIT,
        TransferProtocol.RESULT_INPUT_LENGTH_MISMATCH,
        TransferProtocol.RESULT_INVALID_UTF8,
        TransferProtocol.RESULT_OUTPUT_LIMIT,
        TransferProtocol.RESULT_INVALID_DESCRIPTOR,
        TransferProtocol.RESULT_INPUT_IO,
        TransferProtocol.RESULT_OUTPUT_IO,
        TransferProtocol.RESULT_UNSUPPORTED,
        TransferProtocol.RESULT_NOT_FOUND,
        TransferProtocol.RESULT_AMBIGUOUS,
        TransferProtocol.RESULT_INVALID_INPUT,
        TransferProtocol.RESULT_INTERNAL,
        TransferProtocol.RESULT_INVALID_NDEF,
        TransferProtocol.RESULT_AMBIGUOUS_NDEF -> resultCode

        else -> TransferProtocol.RESULT_INTERNAL
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
            // Client-side output validation still rejects this result.
        }
        try {
            Os.lseek(descriptor, 0L, OsConstants.SEEK_SET)
        } catch (_: ErrnoException) {
            // Client-side output validation still rejects this result.
        }
    }

    /** Maps one fixed result code to its terminal state. */
    private fun stateForResult(resultCode: Int): Int = when (resultCode) {
        TransferProtocol.RESULT_SUCCESS -> TransferProtocol.STATE_COMPLETE
        TransferProtocol.RESULT_CANCELLED -> TransferProtocol.STATE_CANCELLED
        else -> TransferProtocol.STATE_FAILED
    }

    /** Sends the nonterminal running state while the callback remains alive. */
    private fun sendRunningStatus(job: TransferJob) {
        val shouldNotify =
            synchronized(stateLock) {
                activeJob === job && job.callbackAvailable && !job.terminalDelivered
            }
        if (!shouldNotify) {
            return
        }
        try {
            job.callback.onTransferStatus(
                job.jobId,
                TransferProtocol.STATE_RUNNING,
                TransferProtocol.RESULT_SUCCESS,
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
    private fun finishTransfer(job: TransferJob, completion: TransferCompletion) {
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
                job.callback.onTransferStatus(
                    job.jobId,
                    completion.state,
                    completion.resultCode,
                    completion.inputBytes,
                    completion.outputBytes,
                    completion.detailZero,
                    completion.detailOne
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
    private fun requestCancellationLocked(job: TransferJob) {
        if (job.terminalDelivered || job.cancellationRequested) {
            return
        }
        job.cancellationRequested = true
        cancelNativeJobLocked(job)
    }

    /** Requests native cancellation while the handle cannot be destroyed. */
    private fun cancelNativeJobLocked(job: TransferJob) {
        val nativeHandle = job.nativeHandle
        if (nativeHandle == NO_NATIVE_JOB_HANDLE) {
            return
        }
        try {
            NativeTransferWorker.cancelJob(nativeHandle)
        } catch (_: Exception) {
            // Preserve the eventual authoritative native result.
        } catch (_: LinkageError) {
            // Preserve the eventual authoritative native result.
        }
    }

    /** Returns whether cancellation was requested for this job. */
    private fun isCancellationRequested(job: TransferJob): Boolean =
        synchronized(stateLock) { job.cancellationRequested }

    /** Cancels a job whose callback binder has died. */
    private fun handleCallbackDeath(job: TransferJob) {
        synchronized(stateLock) {
            if (activeJob !== job || job.terminalDelivered) {
                return
            }
            job.callbackAvailable = false
            requestCancellationLocked(job)
        }
    }

    /** Unlinks callback death observation after job completion. */
    private fun unlinkCallbackDeath(job: TransferJob) {
        try {
            job.callbackBinder.unlinkToDeath(job.callbackDeathRecipient, 0)
        } catch (_: RuntimeException) {
            // The callback binder is already dead or unlinked.
        }
    }

    /** Rejects one transfer and closes every received descriptor. */
    private fun rejectTransfer(
        acceptCode: Int,
        input: ParcelFileDescriptor?,
        output: ParcelFileDescriptor?
    ): Int {
        closeDescriptors(input, output)
        return acceptCode
    }

    /** Returns whether one descriptor is open at the service boundary. */
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

    /** Holds mutable lifecycle state for one accepted transfer job. */
    private inner class TransferJob(
        val jobId: Long,
        val operation: Int,
        val input: ParcelFileDescriptor,
        val output: ParcelFileDescriptor,
        val expectedInputBytes: Long,
        val argumentZero: Long,
        val argumentOne: Long,
        val timeoutMillis: Long,
        val callback: ITransferCallback,
        val callbackBinder: IBinder
    ) {
        val callbackDeathRecipient = IBinder.DeathRecipient { handleCallbackDeath(this) }
        var nativeHandle = NO_NATIVE_JOB_HANDLE
        var cancellationRequested = false
        var callbackAvailable = true
        var terminalDelivered = false
    }

    /** Holds one sanitized terminal transfer status. */
    private data class TransferCompletion(
        val state: Int,
        val resultCode: Int,
        val inputBytes: Long,
        val outputBytes: Long,
        val detailZero: Long,
        val detailOne: Long
    ) {
        companion object {
            /** Creates one terminal result without native statistics. */
            fun fromResult(resultCode: Int): TransferCompletion = TransferCompletion(
                state = stateForResultCode(resultCode),
                resultCode = resultCode,
                inputBytes = 0L,
                outputBytes = 0L,
                detailZero = 0L,
                detailOne = 0L
            )

            /** Maps one fixed result code without a service instance. */
            private fun stateForResultCode(resultCode: Int): Int = when (resultCode) {
                TransferProtocol.RESULT_SUCCESS -> TransferProtocol.STATE_COMPLETE
                TransferProtocol.RESULT_CANCELLED -> TransferProtocol.STATE_CANCELLED
                else -> TransferProtocol.STATE_FAILED
            }
        }
    }

    /** Creates the dedicated transfer executor thread. */
    private object TransferThreadFactory : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread = Thread(runnable, TRANSFER_THREAD_NAME)
    }
}

/** Returns whether one request uses canonical bounded arguments. */
internal fun isValidTransferRequest(
    operation: Int,
    expectedInputBytes: Long,
    argumentZero: Long,
    argumentOne: Long,
    timeoutMillis: Long
): Boolean {
    if (timeoutMillis !in
        TransferProtocol.MIN_TIMEOUT_MILLIS..TransferProtocol.MAX_TIMEOUT_MILLIS
    ) {
        return false
    }
    return when (operation) {
        TransferProtocol.OPERATION_ENCODE_QR ->
            expectedInputBytes in
                TransferProtocol.MIN_INPUT_BYTES..TransferProtocol.MAX_QR_TEXT_BYTES &&
                isTransferFormat(argumentZero) &&
                argumentOne == 0L

        TransferProtocol.OPERATION_DECODE_QR -> {
            val pixels =
                try {
                    Math.multiplyExact(argumentZero, argumentOne)
                } catch (_: ArithmeticException) {
                    return false
                }
            argumentZero >= TransferProtocol.MIN_QR_FRAME_SIDE &&
                argumentOne >= TransferProtocol.MIN_QR_FRAME_SIDE &&
                pixels == expectedInputBytes &&
                pixels <= TransferProtocol.MAX_QR_FRAME_PIXELS
        }

        TransferProtocol.OPERATION_ENCODE_NFC ->
            expectedInputBytes in
                TransferProtocol.MIN_INPUT_BYTES..TransferProtocol.MAX_NFC_TEXT_BYTES &&
                isTransferFormat(argumentZero) &&
                isValidPackedNfcTagLabel(argumentOne)

        TransferProtocol.OPERATION_DECODE_NFC ->
            expectedInputBytes >= TransferProtocol.MIN_NFC_MESSAGE_BYTES &&
                expectedInputBytes <= TransferProtocol.MAX_NFC_MESSAGE_BYTES &&
                argumentZero == 0L &&
                argumentOne == 0L

        else -> false
    }
}

/** Returns whether one value is a stable transfer format. */
private fun isTransferFormat(value: Long): Boolean = value == TransferProtocol.FORMAT_PLAIN_TEXT ||
    value == TransferProtocol.FORMAT_MARKDOWN

/** Returns whether native transfer statistics form one canonical result. */
internal fun areValidTransferStatistics(
    resultCode: Int,
    operation: Int,
    expectedInputBytes: Long,
    argumentZero: Long,
    argumentOne: Long,
    inputBytes: Long,
    outputBytes: Long,
    detailZero: Long,
    detailOne: Long
): Boolean {
    if (
        inputBytes < 0L ||
        outputBytes !in
        TransferProtocol.MIN_OUTPUT_BYTES..TransferProtocol.MAX_OUTPUT_BYTES ||
        detailZero < 0L ||
        detailOne < 0L
    ) {
        return false
    }
    if (resultCode != TransferProtocol.RESULT_SUCCESS) {
        return inputBytes == 0L &&
            outputBytes == 0L &&
            detailZero == 0L &&
            detailOne == 0L
    }
    if (inputBytes != expectedInputBytes) {
        return false
    }
    return when (operation) {
        TransferProtocol.OPERATION_ENCODE_QR ->
            outputBytes > 0L &&
                detailZero in 21L..TransferProtocol.MAX_QR_MODULES_PER_SIDE &&
                isTransferFormat(detailOne)

        TransferProtocol.OPERATION_DECODE_QR ->
            outputBytes <= TransferProtocol.MAX_QR_TEXT_BYTES &&
                isTransferFormat(detailZero) &&
                detailOne == outputBytes

        TransferProtocol.OPERATION_DECODE_NFC ->
            outputBytes in 1L..TransferProtocol.MAX_NFC_RESULT_PACKET_BYTES &&
                isNfcSource(detailZero) &&
                isTransferFormat(detailOne)

        TransferProtocol.OPERATION_ENCODE_NFC ->
            outputBytes ==
                expectedInputBytes +
                TransferProtocol.TRANSFER_ENVELOPE_BASE_OVERHEAD_BYTES +
                packedNfcTagLabelLength(argumentOne) &&
                detailZero == argumentZero &&
                detailOne == argumentOne

        else -> false
    }
}

/** Returns whether one value is a stable NFC source classification. */
private fun isNfcSource(value: Long): Boolean =
    value in TransferProtocol.NFC_SOURCE_BEAUTYXT..TransferProtocol.NFC_SOURCE_SMART_POSTER
