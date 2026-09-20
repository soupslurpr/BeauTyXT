package dev.soupslurpr.beautyxt.importing

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory

private const val NO_NATIVE_JOB_HANDLE = 0L
private const val IMPORT_THREAD_NAME = "BeauTyXT import"

/** Runs one bounded native import job inside an isolated service process. */
class IsolatedImportService : Service() {
    private val stateLock = Any()
    private val importExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(ImportThreadFactory)
    private var activeJob: ImportJob? = null
    private var destroyed = false

    private val serviceBinder =
        object : IImportService.Stub() {
            override fun startImport(
                jobId: Long,
                input: ParcelFileDescriptor?,
                output: ParcelFileDescriptor?,
                maxInputBytes: Long,
                maxOutputBytes: Long,
                timeoutMillis: Long,
                callback: IImportCallback?
            ): Int = acceptImport(
                jobId = jobId,
                input = input,
                output = output,
                maxInputBytes = maxInputBytes,
                maxOutputBytes = maxOutputBytes,
                timeoutMillis = timeoutMillis,
                callback = callback
            )

            override fun cancelImport(jobId: Long) {
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
        importExecutor.shutdown()
        super.onDestroy()
    }

    /** Validates, owns, and schedules one import request. */
    private fun acceptImport(
        jobId: Long,
        input: ParcelFileDescriptor?,
        output: ParcelFileDescriptor?,
        maxInputBytes: Long,
        maxOutputBytes: Long,
        timeoutMillis: Long,
        callback: IImportCallback?
    ): Int {
        val inputDescriptor =
            input
                ?: return rejectImport(
                    ImportProtocol.ACCEPT_INVALID_ARGUMENT,
                    input,
                    output
                )
        val outputDescriptor =
            output
                ?: return rejectImport(
                    ImportProtocol.ACCEPT_INVALID_ARGUMENT,
                    inputDescriptor,
                    output
                )
        if (
            jobId < 0L ||
            maxInputBytes < ImportProtocol.MIN_BYTE_LIMIT ||
            maxInputBytes > ImportProtocol.MAX_BYTE_LIMIT ||
            maxOutputBytes < ImportProtocol.MIN_BYTE_LIMIT ||
            maxOutputBytes > ImportProtocol.MAX_BYTE_LIMIT ||
            timeoutMillis < ImportProtocol.MIN_TIMEOUT_MILLIS ||
            timeoutMillis > ImportProtocol.MAX_TIMEOUT_MILLIS ||
            !isValid(inputDescriptor) ||
            !isValid(outputDescriptor)
        ) {
            return rejectImport(
                ImportProtocol.ACCEPT_INVALID_ARGUMENT,
                inputDescriptor,
                outputDescriptor
            )
        }
        val importCallback =
            callback
                ?: return rejectImport(
                    ImportProtocol.ACCEPT_CALLBACK_UNAVAILABLE,
                    inputDescriptor,
                    outputDescriptor
                )
        val callbackBinder = importCallback.asBinder()
        if (!callbackBinder.isBinderAlive) {
            return rejectImport(
                ImportProtocol.ACCEPT_CALLBACK_UNAVAILABLE,
                inputDescriptor,
                outputDescriptor
            )
        }

        val job =
            ImportJob(
                jobId = jobId,
                input = inputDescriptor,
                output = outputDescriptor,
                maxInputBytes = maxInputBytes,
                maxOutputBytes = maxOutputBytes,
                timeoutMillis = timeoutMillis,
                callback = importCallback,
                callbackBinder = callbackBinder
            )
        val acceptCode = registerJob(job)
        if (acceptCode != ImportProtocol.ACCEPT_ACCEPTED) {
            closeDescriptors(inputDescriptor, outputDescriptor)
            return acceptCode
        }

        sendRunningStatus(job)
        try {
            importExecutor.execute { runImport(job) }
        } catch (_: RejectedExecutionException) {
            val resultCode =
                if (isCancellationRequested(job)) {
                    ImportProtocol.RESULT_CANCELLED
                } else {
                    ImportProtocol.RESULT_INTERNAL
                }
            finishImport(job, ImportCompletion.fromResult(resultCode))
        }
        return ImportProtocol.ACCEPT_ACCEPTED
    }

    /** Registers a job and its callback death recipient atomically. */
    private fun registerJob(job: ImportJob): Int = synchronized(stateLock) {
        if (destroyed) {
            return@synchronized ImportProtocol.ACCEPT_CALLBACK_UNAVAILABLE
        }
        if (activeJob != null) {
            return@synchronized ImportProtocol.ACCEPT_BUSY
        }

        activeJob = job
        try {
            job.callbackBinder.linkToDeath(job.callbackDeathRecipient, 0)
        } catch (_: RemoteException) {
            activeJob = null
            job.callbackAvailable = false
            return@synchronized ImportProtocol.ACCEPT_CALLBACK_UNAVAILABLE
        } catch (_: RuntimeException) {
            activeJob = null
            job.callbackAvailable = false
            return@synchronized ImportProtocol.ACCEPT_CALLBACK_UNAVAILABLE
        }
        ImportProtocol.ACCEPT_ACCEPTED
    }

    /** Runs the native job and converts its bounded result. */
    private fun runImport(job: ImportJob) {
        val resultValues = LongArray(ImportProtocol.RESULT_VALUE_COUNT)
        val resultSha256 = ByteArray(ImportProtocol.RESULT_SHA_256_BYTE_COUNT)
        var resultCode = ImportProtocol.RESULT_INTERNAL
        var nativeHandle = NO_NATIVE_JOB_HANDLE
        try {
            if (isCancellationRequested(job)) {
                resultCode = ImportProtocol.RESULT_CANCELLED
            } else {
                nativeHandle = NativeImportWorker.createJob()
                check(nativeHandle > NO_NATIVE_JOB_HANDLE) {
                    "native import job handle must be positive"
                }
                publishNativeHandle(job, nativeHandle)
                resultCode =
                    NativeImportWorker.runJob(
                        jobHandle = nativeHandle,
                        inputFileDescriptor = job.input.fd,
                        outputFileDescriptor = job.output.fd,
                        maxInputBytes = job.maxInputBytes,
                        maxOutputBytes = job.maxOutputBytes,
                        timeoutMillis = job.timeoutMillis,
                        resultValues = resultValues,
                        resultSha256 = resultSha256
                    )
            }
        } catch (_: Exception) {
            resultCode = ImportProtocol.RESULT_INTERNAL
        } catch (_: LinkageError) {
            resultCode = ImportProtocol.RESULT_INTERNAL
        } finally {
            if (nativeHandle != NO_NATIVE_JOB_HANDLE) {
                destroyNativeJob(job, nativeHandle)
            }
        }
        resultCode =
            resolveProviderCompletion(
                nativeResultCode = resultCode,
                checkInputError = { hasInputCompletionError(job.input) },
                handleInputError = {
                    resetOutput(job.output)
                    resultValues.fill(0L)
                    resultSha256.fill(0)
                }
            )

        val completion = decodeCompletion(resultCode, resultValues, resultSha256)
        finishImport(job, completion)
    }

    /** Publishes a native handle and applies any pending cancellation. */
    private fun publishNativeHandle(job: ImportJob, nativeHandle: Long) {
        synchronized(stateLock) {
            check(activeJob === job) { "import job is no longer active" }
            check(job.nativeHandle == NO_NATIVE_JOB_HANDLE) {
                "native import job handle is already set"
            }
            job.nativeHandle = nativeHandle
            if (job.cancellationRequested) {
                cancelNativeJobLocked(job)
            }
        }
    }

    /** Destroys a published native handle without racing cancellation. */
    private fun destroyNativeJob(job: ImportJob, nativeHandle: Long) {
        synchronized(stateLock) {
            if (job.nativeHandle != nativeHandle) {
                return
            }
            try {
                NativeImportWorker.destroyJob(nativeHandle)
            } catch (_: Exception) {
                // Preserve the authoritative native result.
            } catch (_: LinkageError) {
                // Preserve the authoritative native result.
            } finally {
                job.nativeHandle = NO_NATIVE_JOB_HANDLE
            }
        }
    }

    /** Decodes a native result into one terminal callback value. */
    private fun decodeCompletion(
        rawResultCode: Int,
        resultValues: LongArray,
        resultSha256: ByteArray
    ): ImportCompletion {
        val inputBytes = resultValues[ImportProtocol.RESULT_INPUT_BYTES_INDEX]
        val outputBytes = resultValues[ImportProtocol.RESULT_OUTPUT_BYTES_INDEX]
        val sourceFlagsValue = resultValues[ImportProtocol.RESULT_SOURCE_FLAGS_INDEX]
        val validStatistics =
            areValidImportStatistics(
                resultCode = rawResultCode,
                inputBytes = inputBytes,
                outputBytes = outputBytes,
                sourceFlagsValue = sourceFlagsValue,
                sourceSha256 = resultSha256
            )
        val resultCode =
            if (validStatistics) {
                sanitizeResultCode(rawResultCode)
            } else {
                ImportProtocol.RESULT_INTERNAL
            }
        return ImportCompletion(
            state = stateForResult(resultCode),
            resultCode = resultCode,
            inputBytes = if (validStatistics) inputBytes else 0L,
            outputBytes = if (validStatistics) outputBytes else 0L,
            sourceFlags = if (validStatistics) sourceFlagsValue.toInt() else 0,
            sourceSha256 =
                if (validStatistics) {
                    resultSha256.copyOf()
                } else {
                    ByteArray(ImportProtocol.RESULT_SHA_256_BYTE_COUNT)
                }
        )
    }

    /** Returns a stable result code for any native return value. */
    private fun sanitizeResultCode(resultCode: Int): Int = when (resultCode) {
        ImportProtocol.RESULT_SUCCESS,
        ImportProtocol.RESULT_CANCELLED,
        ImportProtocol.RESULT_INVALID_UTF8,
        ImportProtocol.RESULT_UNSUPPORTED_BOM,
        ImportProtocol.RESULT_INPUT_LIMIT,
        ImportProtocol.RESULT_OUTPUT_LIMIT,
        ImportProtocol.RESULT_TIMEOUT,
        ImportProtocol.RESULT_INVALID_DESCRIPTOR,
        ImportProtocol.RESULT_INPUT_IO,
        ImportProtocol.RESULT_OUTPUT_IO,
        ImportProtocol.RESULT_INTERNAL -> resultCode

        else -> ImportProtocol.RESULT_INTERNAL
    }

    /** Returns whether a reliable provider reported an input completion error. */
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

    /** Best-effort truncates and rewinds a failed anonymous output buffer. */
    private fun resetOutput(output: ParcelFileDescriptor) {
        val fileDescriptor =
            try {
                output.fileDescriptor
            } catch (_: RuntimeException) {
                return
            }
        try {
            Os.ftruncate(fileDescriptor, 0L)
        } catch (_: ErrnoException) {
            // Application-side completion validation still rejects this output.
        }
        try {
            Os.lseek(fileDescriptor, 0L, OsConstants.SEEK_SET)
        } catch (_: ErrnoException) {
            // Application-side completion validation still rejects this output.
        }
    }

    /** Maps one fixed result code to its terminal state. */
    private fun stateForResult(resultCode: Int): Int = when (resultCode) {
        ImportProtocol.RESULT_SUCCESS -> ImportProtocol.STATE_COMPLETE
        ImportProtocol.RESULT_CANCELLED -> ImportProtocol.STATE_CANCELLED
        else -> ImportProtocol.STATE_FAILED
    }

    /** Sends the nonterminal running state when the callback remains alive. */
    private fun sendRunningStatus(job: ImportJob) {
        val shouldNotify =
            synchronized(stateLock) {
                activeJob === job && job.callbackAvailable && !job.terminalDelivered
            }
        if (!shouldNotify) {
            return
        }
        try {
            job.callback.onImportStatus(
                job.jobId,
                ImportProtocol.STATE_RUNNING,
                ImportProtocol.RESULT_SUCCESS,
                0L,
                0L,
                0,
                ByteArray(ImportProtocol.RESULT_SHA_256_BYTE_COUNT)
            )
        } catch (_: RemoteException) {
            handleCallbackDeath(job)
        } catch (_: RuntimeException) {
            handleCallbackDeath(job)
        }
    }

    /** Closes job resources and emits exactly one terminal state. */
    private fun finishImport(job: ImportJob, completion: ImportCompletion) {
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
                job.callback.onImportStatus(
                    job.jobId,
                    completion.state,
                    completion.resultCode,
                    completion.inputBytes,
                    completion.outputBytes,
                    completion.sourceFlags,
                    completion.sourceSha256
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
    private fun requestCancellationLocked(job: ImportJob) {
        if (job.terminalDelivered || job.cancellationRequested) {
            return
        }
        job.cancellationRequested = true
        cancelNativeJobLocked(job)
    }

    /** Requests native cancellation while the handle cannot be destroyed. */
    private fun cancelNativeJobLocked(job: ImportJob) {
        val nativeHandle = job.nativeHandle
        if (nativeHandle == NO_NATIVE_JOB_HANDLE) {
            return
        }
        try {
            NativeImportWorker.cancelJob(nativeHandle)
        } catch (_: Exception) {
            // Preserve the eventual authoritative native result.
        } catch (_: LinkageError) {
            // Preserve the eventual authoritative native result.
        }
    }

    /** Returns whether cancellation was requested for this job. */
    private fun isCancellationRequested(job: ImportJob): Boolean =
        synchronized(stateLock) { job.cancellationRequested }

    /** Cancels a job whose callback binder has died. */
    private fun handleCallbackDeath(job: ImportJob) {
        synchronized(stateLock) {
            if (activeJob !== job || job.terminalDelivered) {
                return
            }
            job.callbackAvailable = false
            requestCancellationLocked(job)
        }
    }

    /** Unlinks the callback death recipient after job completion. */
    private fun unlinkCallbackDeath(job: ImportJob) {
        try {
            job.callbackBinder.unlinkToDeath(job.callbackDeathRecipient, 0)
        } catch (_: RuntimeException) {
            // The callback binder is already dead or unlinked.
        }
    }

    /** Rejects an import and closes every descriptor received by the service. */
    private fun rejectImport(
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

    /** Holds the mutable lifecycle state for one accepted job. */
    private inner class ImportJob(
        val jobId: Long,
        val input: ParcelFileDescriptor,
        val output: ParcelFileDescriptor,
        val maxInputBytes: Long,
        val maxOutputBytes: Long,
        val timeoutMillis: Long,
        val callback: IImportCallback,
        val callbackBinder: IBinder
    ) {
        val callbackDeathRecipient = IBinder.DeathRecipient { handleCallbackDeath(this) }
        var nativeHandle = NO_NATIVE_JOB_HANDLE
        var cancellationRequested = false
        var callbackAvailable = true
        var terminalDelivered = false
    }

    /** Holds one sanitized terminal status. */
    private data class ImportCompletion(
        val state: Int,
        val resultCode: Int,
        val inputBytes: Long,
        val outputBytes: Long,
        val sourceFlags: Int,
        val sourceSha256: ByteArray
    ) {
        companion object {
            /** Creates a terminal result without native statistics. */
            fun fromResult(resultCode: Int): ImportCompletion = ImportCompletion(
                state = stateForResultCode(resultCode),
                resultCode = resultCode,
                inputBytes = 0L,
                outputBytes = 0L,
                sourceFlags = 0,
                sourceSha256 = ByteArray(ImportProtocol.RESULT_SHA_256_BYTE_COUNT)
            )

            /** Maps one fixed result code without a service instance. */
            private fun stateForResultCode(resultCode: Int): Int = when (resultCode) {
                ImportProtocol.RESULT_SUCCESS -> ImportProtocol.STATE_COMPLETE
                ImportProtocol.RESULT_CANCELLED -> ImportProtocol.STATE_CANCELLED
                else -> ImportProtocol.STATE_FAILED
            }
        }
    }

    /** Creates the dedicated import executor thread. */
    private object ImportThreadFactory : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread = Thread(runnable, IMPORT_THREAD_NAME)
    }
}

/** Returns whether native import statistics form a canonical result. */
internal fun areValidImportStatistics(
    resultCode: Int,
    inputBytes: Long,
    outputBytes: Long,
    sourceFlagsValue: Long,
    sourceSha256: ByteArray
): Boolean {
    if (
        inputBytes < 0L ||
        outputBytes < 0L ||
        sourceFlagsValue < 0L ||
        sourceFlagsValue and ImportProtocol.SOURCE_FLAGS_MASK.toLong().inv() != 0L ||
        sourceSha256.size != ImportProtocol.RESULT_SHA_256_BYTE_COUNT
    ) {
        return false
    }
    return if (resultCode == ImportProtocol.RESULT_SUCCESS) {
        inputBytes == outputBytes
    } else {
        inputBytes == 0L &&
            outputBytes == 0L &&
            sourceFlagsValue == 0L &&
            sourceSha256.all { byte -> byte == 0.toByte() }
    }
}
