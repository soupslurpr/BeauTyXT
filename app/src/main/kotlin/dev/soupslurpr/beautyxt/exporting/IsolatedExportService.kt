package dev.soupslurpr.beautyxt.exporting

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import dev.soupslurpr.beautyxt.document.SHA_256_BYTE_COUNT
import dev.soupslurpr.beautyxt.ipc.TransferredFileDescriptor
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory

private const val NO_NATIVE_JOB_HANDLE = 0L
private const val EXPORT_THREAD_NAME = "BeauTyXT export"
private const val REJECTED_INPUT_ERROR = "export input rejected"
private const val REJECTED_OUTPUT_ERROR = "export request rejected"
private const val FAILED_INPUT_ERROR = "export input terminated"
private const val FAILED_OUTPUT_ERROR = "export did not complete"

/** Runs one bounded native export job inside an isolated service process. */
class IsolatedExportService : Service() {
    private val stateLock = Any()
    private val exportExecutor: ExecutorService =
        Executors.newSingleThreadExecutor(ExportThreadFactory)
    private var activeJob: ExportJob? = null
    private var destroyed = false

    private val serviceBinder =
        object : IExportService.Stub() {
            override fun startExport(
                jobId: Long,
                input: TransferredFileDescriptor?,
                output: TransferredFileDescriptor?,
                expectedBytes: Long,
                timeoutMillis: Long,
                callback: IExportCallback?
            ): Int = acceptExport(
                jobId = jobId,
                input = input,
                output = output,
                expectedBytes = expectedBytes,
                timeoutMillis = timeoutMillis,
                callback = callback
            )

            override fun startConditionalSourceSave(
                jobId: Long,
                packageInput: TransferredFileDescriptor?,
                sourceBacking: TransferredFileDescriptor?,
                source: TransferredFileDescriptor?,
                expectedBytes: Long,
                expectedSourceBytes: Long,
                expectedSourceSha256: ByteArray?,
                timeoutMillis: Long,
                callback: ISourceSaveCallback?
            ): Int = acceptConditionalSourceSave(
                jobId = jobId,
                packageInput = packageInput,
                sourceBacking = sourceBacking,
                source = source,
                expectedBytes = expectedBytes,
                expectedSourceBytes = expectedSourceBytes,
                expectedSourceSha256 = expectedSourceSha256,
                timeoutMillis = timeoutMillis,
                callback = callback
            )

            override fun startSourceVerification(
                jobId: Long,
                source: TransferredFileDescriptor?,
                expectedSourceBytes: Long,
                expectedSourceSha256: ByteArray?,
                timeoutMillis: Long,
                callback: ISourceSaveCallback?
            ): Int = acceptSourceVerification(
                jobId = jobId,
                source = source,
                expectedSourceBytes = expectedSourceBytes,
                expectedSourceSha256 = expectedSourceSha256,
                timeoutMillis = timeoutMillis,
                callback = callback
            )

            override fun startSourceInspection(
                jobId: Long,
                source: TransferredFileDescriptor?,
                maximumSourceBytes: Long,
                timeoutMillis: Long,
                callback: ISourceSaveCallback?
            ): Int = acceptSourceInspection(
                jobId = jobId,
                source = source,
                maximumSourceBytes = maximumSourceBytes,
                timeoutMillis = timeoutMillis,
                callback = callback
            )

            override fun cancelExport(jobId: Long) {
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
        exportExecutor.shutdown()
        super.onDestroy()
    }

    /** Validates, owns, and schedules one export request. */
    private fun acceptExport(
        jobId: Long,
        input: TransferredFileDescriptor?,
        output: TransferredFileDescriptor?,
        expectedBytes: Long,
        timeoutMillis: Long,
        callback: IExportCallback?
    ): Int {
        val inputDescriptor = takeTransferredDescriptor(input)
        val outputDescriptor = takeTransferredDescriptor(output)
        input?.closeWithError(REJECTED_INPUT_ERROR)
        output?.closeWithError(REJECTED_OUTPUT_ERROR)
        if (inputDescriptor == null || outputDescriptor == null) {
            return rejectExport(
                ExportProtocol.ACCEPT_INVALID_ARGUMENT,
                inputDescriptor,
                outputDescriptor
            )
        }
        if (
            !ExportProtocol.isValidJobId(jobId) ||
            expectedBytes < ExportProtocol.MIN_BYTE_LIMIT ||
            expectedBytes > ExportProtocol.MAX_BYTE_LIMIT ||
            timeoutMillis < ExportProtocol.MIN_TIMEOUT_MILLIS ||
            timeoutMillis > ExportProtocol.MAX_TIMEOUT_MILLIS ||
            !isValid(inputDescriptor) ||
            !isValid(outputDescriptor)
        ) {
            return rejectExport(
                ExportProtocol.ACCEPT_INVALID_ARGUMENT,
                inputDescriptor,
                outputDescriptor
            )
        }
        val exportCallback =
            callback
                ?: return rejectExport(
                    ExportProtocol.ACCEPT_CALLBACK_UNAVAILABLE,
                    inputDescriptor,
                    outputDescriptor
                )
        val statusCallback = CopyStatusCallback(exportCallback)
        val callbackBinder = statusCallback.binder
        if (!callbackBinder.isBinderAlive) {
            return rejectExport(
                ExportProtocol.ACCEPT_CALLBACK_UNAVAILABLE,
                inputDescriptor,
                outputDescriptor
            )
        }

        val job =
            ExportJob(
                jobId = jobId,
                input = inputDescriptor,
                backing = null,
                output = outputDescriptor,
                expectedBytes = expectedBytes,
                timeoutMillis = timeoutMillis,
                callback = statusCallback,
                callbackBinder = callbackBinder,
                operation = ExportOperation.COPY,
                sourceExpectation = null
            )
        val acceptCode = registerJob(job)
        if (acceptCode != ExportProtocol.ACCEPT_ACCEPTED) {
            return rejectExport(acceptCode, inputDescriptor, outputDescriptor)
        }

        sendRunningStatus(job)
        try {
            exportExecutor.execute { runExport(job) }
        } catch (_: RejectedExecutionException) {
            val resultCode =
                if (isCancellationRequested(job)) {
                    ExportProtocol.RESULT_CANCELLED
                } else {
                    ExportProtocol.RESULT_INTERNAL
                }
            finishExport(job, ExportCompletion.fromResult(resultCode))
        }
        return ExportProtocol.ACCEPT_ACCEPTED
    }

    /** Validates and schedules one exact conditional source replacement. */
    private fun acceptConditionalSourceSave(
        jobId: Long,
        packageInput: TransferredFileDescriptor?,
        sourceBacking: TransferredFileDescriptor?,
        source: TransferredFileDescriptor?,
        expectedBytes: Long,
        expectedSourceBytes: Long,
        expectedSourceSha256: ByteArray?,
        timeoutMillis: Long,
        callback: ISourceSaveCallback?
    ): Int {
        val packageDescriptor = takeTransferredDescriptor(packageInput)
        val backingDescriptor = takeTransferredDescriptor(sourceBacking)
        val sourceDescriptor = takeTransferredDescriptor(source)
        packageInput?.closeWithError(REJECTED_INPUT_ERROR)
        sourceBacking?.closeWithError(REJECTED_INPUT_ERROR)
        source?.closeWithError(REJECTED_OUTPUT_ERROR)
        val sourceExpectation =
            sourceExpectationOrNull(expectedSourceBytes, expectedSourceSha256)
        if (
            packageDescriptor == null ||
            sourceDescriptor == null ||
            sourceExpectation == SourceExpectation.Invalid
        ) {
            return rejectSourceSave(
                ExportProtocol.ACCEPT_INVALID_ARGUMENT,
                packageDescriptor,
                backingDescriptor,
                sourceDescriptor
            )
        }
        return acceptSourceJob(
            jobId = jobId,
            input = packageDescriptor,
            backing = backingDescriptor,
            output = sourceDescriptor,
            expectedBytes = expectedBytes,
            timeoutMillis = timeoutMillis,
            callback = callback,
            operation = ExportOperation.CONDITIONAL_SOURCE_SAVE,
            sourceExpectation = sourceExpectation
        )
    }

    /** Validates and schedules one read-only exact source verification. */
    private fun acceptSourceVerification(
        jobId: Long,
        source: TransferredFileDescriptor?,
        expectedSourceBytes: Long,
        expectedSourceSha256: ByteArray?,
        timeoutMillis: Long,
        callback: ISourceSaveCallback?
    ): Int {
        val sourceDescriptor = takeTransferredDescriptor(source)
        source?.closeWithError(REJECTED_INPUT_ERROR)
        val sourceExpectation =
            sourceExpectationOrNull(expectedSourceBytes, expectedSourceSha256)
        if (
            sourceDescriptor == null ||
            sourceExpectation !is SourceExpectation.Exact
        ) {
            return rejectExport(
                ExportProtocol.ACCEPT_INVALID_ARGUMENT,
                sourceDescriptor,
                null
            )
        }
        return acceptSourceJob(
            jobId = jobId,
            input = sourceDescriptor,
            backing = null,
            output = null,
            expectedBytes = expectedSourceBytes,
            timeoutMillis = timeoutMillis,
            callback = callback,
            operation = ExportOperation.SOURCE_VERIFICATION,
            sourceExpectation = sourceExpectation
        )
    }

    /** Validates and schedules one read-only bounded source inspection. */
    private fun acceptSourceInspection(
        jobId: Long,
        source: TransferredFileDescriptor?,
        maximumSourceBytes: Long,
        timeoutMillis: Long,
        callback: ISourceSaveCallback?
    ): Int {
        val sourceDescriptor = takeTransferredDescriptor(source)
        source?.closeWithError(REJECTED_INPUT_ERROR)
        if (sourceDescriptor == null) {
            return rejectExport(
                ExportProtocol.ACCEPT_INVALID_ARGUMENT,
                null,
                null
            )
        }
        return acceptSourceJob(
            jobId = jobId,
            input = sourceDescriptor,
            backing = null,
            output = null,
            expectedBytes = maximumSourceBytes,
            timeoutMillis = timeoutMillis,
            callback = callback,
            operation = ExportOperation.SOURCE_INSPECTION,
            sourceExpectation = null
        )
    }

    /** Validates shared source job fields and schedules one accepted request. */
    private fun acceptSourceJob(
        jobId: Long,
        input: ParcelFileDescriptor,
        backing: ParcelFileDescriptor?,
        output: ParcelFileDescriptor?,
        expectedBytes: Long,
        timeoutMillis: Long,
        callback: ISourceSaveCallback?,
        operation: ExportOperation,
        sourceExpectation: SourceExpectation?
    ): Int {
        if (
            !ExportProtocol.isValidJobId(jobId) ||
            expectedBytes !in ExportProtocol.MIN_BYTE_LIMIT..ExportProtocol.MAX_BYTE_LIMIT ||
            timeoutMillis !in
            ExportProtocol.MIN_TIMEOUT_MILLIS..ExportProtocol.MAX_TIMEOUT_MILLIS ||
            !isValid(input) ||
            (backing != null && !isValid(backing)) ||
            (output != null && !isValid(output))
        ) {
            return rejectSourceSave(
                ExportProtocol.ACCEPT_INVALID_ARGUMENT,
                input,
                backing,
                output
            )
        }
        val sourceCallback =
            callback
                ?: return rejectSourceSave(
                    ExportProtocol.ACCEPT_CALLBACK_UNAVAILABLE,
                    input,
                    backing,
                    output
                )
        val statusCallback = SourceStatusCallback(sourceCallback)
        val callbackBinder = statusCallback.binder
        if (!callbackBinder.isBinderAlive) {
            return rejectSourceSave(
                ExportProtocol.ACCEPT_CALLBACK_UNAVAILABLE,
                input,
                backing,
                output
            )
        }

        val job =
            ExportJob(
                jobId = jobId,
                input = input,
                backing = backing,
                output = output,
                expectedBytes = expectedBytes,
                timeoutMillis = timeoutMillis,
                callback = statusCallback,
                callbackBinder = callbackBinder,
                operation = operation,
                sourceExpectation = sourceExpectation
            )
        val acceptCode = registerJob(job)
        if (acceptCode != ExportProtocol.ACCEPT_ACCEPTED) {
            return rejectSourceSave(acceptCode, input, backing, output)
        }

        sendRunningStatus(job)
        try {
            exportExecutor.execute { runExport(job) }
        } catch (_: RejectedExecutionException) {
            val resultCode =
                if (isCancellationRequested(job)) {
                    ExportProtocol.RESULT_CANCELLED
                } else {
                    ExportProtocol.RESULT_INTERNAL
                }
            finishExport(job, ExportCompletion.fromResult(resultCode))
        }
        return ExportProtocol.ACCEPT_ACCEPTED
    }

    /** Decodes one nullable client expectation into a strict service value. */
    private fun sourceExpectationOrNull(
        expectedSourceBytes: Long,
        expectedSourceSha256: ByteArray?
    ): SourceExpectation {
        val digest = expectedSourceSha256 ?: return SourceExpectation.Invalid
        return when {
            expectedSourceBytes == ExportProtocol.NO_EXPECTED_SOURCE_BYTE_LENGTH &&
                digest.isEmpty() -> SourceExpectation.NewTarget

            expectedSourceBytes in
                ExportProtocol.MIN_BYTE_LIMIT..ExportProtocol.MAX_BYTE_LIMIT &&
                digest.size == SHA_256_BYTE_COUNT ->
                SourceExpectation.Exact(
                    byteLength = expectedSourceBytes,
                    sha256 = digest.copyOf()
                )

            else -> SourceExpectation.Invalid
        }
    }

    /** Registers a job and its callback death recipient atomically. */
    private fun registerJob(job: ExportJob): Int = synchronized(stateLock) {
        if (destroyed) {
            return@synchronized ExportProtocol.ACCEPT_CALLBACK_UNAVAILABLE
        }
        if (activeJob != null) {
            return@synchronized ExportProtocol.ACCEPT_BUSY
        }

        activeJob = job
        try {
            job.callbackBinder.linkToDeath(job.callbackDeathRecipient, 0)
        } catch (_: RemoteException) {
            activeJob = null
            job.callbackAvailable = false
            return@synchronized ExportProtocol.ACCEPT_CALLBACK_UNAVAILABLE
        } catch (_: RuntimeException) {
            activeJob = null
            job.callbackAvailable = false
            return@synchronized ExportProtocol.ACCEPT_CALLBACK_UNAVAILABLE
        }
        ExportProtocol.ACCEPT_ACCEPTED
    }

    /** Runs the native job and converts its bounded result. */
    private fun runExport(job: ExportJob) {
        val resultValues =
            LongArray(
                if (job.operation == ExportOperation.COPY) {
                    ExportProtocol.RESULT_VALUE_COUNT
                } else {
                    ExportProtocol.SOURCE_RESULT_VALUE_COUNT
                }
            )
        val resultSha256 = ByteArray(SHA_256_BYTE_COUNT)
        var resultCode = ExportProtocol.RESULT_INTERNAL
        var nativeHandle = NO_NATIVE_JOB_HANDLE
        var nativeOperationInvoked = false
        try {
            if (isCancellationRequested(job)) {
                resultCode = ExportProtocol.RESULT_CANCELLED
            } else {
                nativeHandle = NativeExportWorker.createJob()
                check(nativeHandle > NO_NATIVE_JOB_HANDLE) {
                    "native export job handle must be positive"
                }
                publishNativeHandle(job, nativeHandle)
                resultCode =
                    when (job.operation) {
                        ExportOperation.COPY ->
                            NativeExportWorker.runJob(
                                jobHandle = nativeHandle,
                                inputFileDescriptor = job.input.fd,
                                outputFileDescriptor = checkNotNull(job.output).fd,
                                expectedBytes = job.expectedBytes,
                                timeoutMillis = job.timeoutMillis,
                                resultValues = resultValues
                            ).also { nativeOperationInvoked = true }

                        ExportOperation.CONDITIONAL_SOURCE_SAVE -> {
                            val expectation = checkNotNull(job.sourceExpectation)
                            nativeOperationInvoked = true
                            NativeExportWorker.runConditionalSourceSaveJob(
                                jobHandle = nativeHandle,
                                packageFileDescriptor = job.input.fd,
                                sourceBackingFileDescriptor = job.backing?.fd ?: -1,
                                sourceFileDescriptor = checkNotNull(job.output).fd,
                                expectedBytes = job.expectedBytes,
                                expectedSourceBytes = expectation.byteLengthOrAbsent(),
                                expectedSourceSha256 = expectation.copySha256(),
                                timeoutMillis = job.timeoutMillis,
                                resultValues = resultValues,
                                resultSha256 = resultSha256
                            )
                        }

                        ExportOperation.SOURCE_VERIFICATION -> {
                            val expectation =
                                job.sourceExpectation as? SourceExpectation.Exact
                                    ?: error("source verification expectation is unavailable")
                            nativeOperationInvoked = true
                            NativeExportWorker.runSourceVerificationJob(
                                jobHandle = nativeHandle,
                                sourceFileDescriptor = job.input.fd,
                                expectedSourceBytes = expectation.byteLength,
                                expectedSourceSha256 = expectation.sha256.copyOf(),
                                timeoutMillis = job.timeoutMillis,
                                resultValues = resultValues
                            )
                        }

                        ExportOperation.SOURCE_INSPECTION -> {
                            nativeOperationInvoked = true
                            NativeExportWorker.runSourceInspectionJob(
                                jobHandle = nativeHandle,
                                sourceFileDescriptor = job.input.fd,
                                maximumSourceBytes = job.expectedBytes,
                                timeoutMillis = job.timeoutMillis,
                                resultValues = resultValues,
                                resultSha256 = resultSha256
                            )
                        }
                    }
            }
        } catch (_: Exception) {
            resultCode =
                resultAfterNativeFailure(job, resultValues, nativeOperationInvoked)
        } catch (_: LinkageError) {
            resultCode =
                resultAfterNativeFailure(job, resultValues, nativeOperationInvoked)
        } finally {
            if (nativeHandle != NO_NATIVE_JOB_HANDLE) {
                destroyNativeJob(job, nativeHandle)
            }
        }
        if (resultCode == ExportProtocol.RESULT_SUCCESS) {
            resultCode =
                when {
                    hasDescriptorCompletionError(job.input) ->
                        completionErrorResult(job, resultValues, ExportProtocol.RESULT_INPUT_IO)

                    hasDescriptorCompletionError(job.backing) ->
                        completionErrorResult(job, resultValues, ExportProtocol.RESULT_INPUT_IO)

                    hasDescriptorCompletionError(job.output) ->
                        completionErrorResult(job, resultValues, ExportProtocol.RESULT_OUTPUT_IO)

                    else -> ExportProtocol.RESULT_SUCCESS
                }
            if (resultCode != ExportProtocol.RESULT_SUCCESS) {
                clearFailedResultValues(resultValues, resultCode)
                resultSha256.fill(0)
            }
        }
        val completion =
            if (job.operation == ExportOperation.COPY) {
                decodeCopyCompletion(
                    rawResultCode = resultCode,
                    expectedBytes = job.expectedBytes,
                    resultValues = resultValues
                )
            } else {
                decodeSourceCompletion(
                    operation = job.operation,
                    rawResultCode = resultCode,
                    expectedBytes = job.expectedBytes,
                    resultValues = resultValues,
                    resultSha256 = resultSha256
                )
            }
        finishExport(job, completion)
    }

    /** Conservatively maps a thrown native call after a possible source boundary. */
    private fun resultAfterNativeFailure(
        job: ExportJob,
        resultValues: LongArray,
        nativeOperationInvoked: Boolean
    ): Int {
        if (
            job.operation == ExportOperation.CONDITIONAL_SOURCE_SAVE &&
            nativeOperationInvoked
        ) {
            resultValues[ExportProtocol.SOURCE_RESULT_OUTPUT_STARTED_INDEX] = 1L
            return ExportProtocol.RESULT_SOURCE_UNCERTAIN
        }
        return ExportProtocol.RESULT_INTERNAL
    }

    /** Maps reliable-descriptor completion errors for one operation. */
    private fun completionErrorResult(
        job: ExportJob,
        resultValues: LongArray,
        copyFailure: Int
    ): Int = if (
        job.operation == ExportOperation.CONDITIONAL_SOURCE_SAVE &&
        resultValues[ExportProtocol.SOURCE_RESULT_OUTPUT_STARTED_INDEX] != 0L
    ) {
        ExportProtocol.RESULT_SOURCE_UNCERTAIN
    } else {
        copyFailure
    }

    /** Clears failed statistics while preserving a known destructive boundary. */
    private fun clearFailedResultValues(resultValues: LongArray, resultCode: Int) {
        val outputStarted =
            resultCode == ExportProtocol.RESULT_SOURCE_UNCERTAIN &&
                resultValues.size == ExportProtocol.SOURCE_RESULT_VALUE_COUNT
        resultValues.fill(0L)
        if (outputStarted) {
            resultValues[ExportProtocol.SOURCE_RESULT_OUTPUT_STARTED_INDEX] = 1L
        }
    }

    /** Publishes a native handle and applies any pending cancellation. */
    private fun publishNativeHandle(job: ExportJob, nativeHandle: Long) {
        synchronized(stateLock) {
            check(activeJob === job) { "export job is no longer active" }
            check(job.nativeHandle == NO_NATIVE_JOB_HANDLE) {
                "native export job handle is already set"
            }
            job.nativeHandle = nativeHandle
            if (job.cancellationRequested) {
                cancelNativeJobLocked(job)
            }
        }
    }

    /** Destroys a published native handle without racing cancellation. */
    private fun destroyNativeJob(job: ExportJob, nativeHandle: Long) {
        synchronized(stateLock) {
            if (job.nativeHandle != nativeHandle) {
                return
            }
            try {
                NativeExportWorker.destroyJob(nativeHandle)
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
    private fun decodeCopyCompletion(
        rawResultCode: Int,
        expectedBytes: Long,
        resultValues: LongArray
    ): ExportCompletion {
        val inputBytes = resultValues[ExportProtocol.RESULT_INPUT_BYTES_INDEX]
        val outputBytes = resultValues[ExportProtocol.RESULT_OUTPUT_BYTES_INDEX]
        val validStatistics =
            inputBytes >= 0L &&
                inputBytes <= expectedBytes + 1L &&
                outputBytes >= 0L &&
                outputBytes <= expectedBytes &&
                outputBytes <= inputBytes
        val normalizedResultCode = normalizeResultCode(rawResultCode)
        val validSuccess =
            normalizedResultCode != ExportProtocol.RESULT_SUCCESS ||
                (inputBytes == expectedBytes && outputBytes == expectedBytes)
        val validCompletion = validStatistics && validSuccess
        val resultCode =
            if (validCompletion) {
                normalizedResultCode
            } else {
                ExportProtocol.RESULT_INTERNAL
            }
        return ExportCompletion(
            state = stateForResult(resultCode),
            resultCode = resultCode,
            inputBytes = if (validCompletion) inputBytes else 0L,
            outputBytes = if (validCompletion) outputBytes else 0L
        )
    }

    /** Decodes one native source result and its destructive-boundary evidence. */
    private fun decodeSourceCompletion(
        operation: ExportOperation,
        rawResultCode: Int,
        expectedBytes: Long,
        resultValues: LongArray,
        resultSha256: ByteArray
    ): ExportCompletion {
        val inputBytes = resultValues[ExportProtocol.RESULT_INPUT_BYTES_INDEX]
        val outputBytes = resultValues[ExportProtocol.RESULT_OUTPUT_BYTES_INDEX]
        val outputStartedValue =
            resultValues[ExportProtocol.SOURCE_RESULT_OUTPUT_STARTED_INDEX]
        val outputStarted = outputStartedValue != 0L
        val validOutputStarted = outputStartedValue == 0L || outputStartedValue == 1L
        val validResultCode = isKnownResultCode(rawResultCode)
        val maximumOutputBytes =
            if (operation == ExportOperation.CONDITIONAL_SOURCE_SAVE) expectedBytes else 0L
        val validStatistics =
            inputBytes in 0L..(expectedBytes + 1L) &&
                outputBytes in 0L..maximumOutputBytes &&
                (
                    operation != ExportOperation.CONDITIONAL_SOURCE_SAVE ||
                        outputBytes <= inputBytes
                    )
        var resultCode = normalizeResultCode(rawResultCode)
        if (operation == ExportOperation.CONDITIONAL_SOURCE_SAVE && outputStarted &&
            resultCode != ExportProtocol.RESULT_SUCCESS
        ) {
            resultCode = ExportProtocol.RESULT_SOURCE_UNCERTAIN
        }
        val validSuccess =
            when (operation) {
                ExportOperation.CONDITIONAL_SOURCE_SAVE ->
                    resultCode != ExportProtocol.RESULT_SUCCESS ||
                        (
                            outputStarted &&
                                inputBytes == expectedBytes &&
                                outputBytes == expectedBytes
                            )

                ExportOperation.SOURCE_VERIFICATION ->
                    resultCode != ExportProtocol.RESULT_SUCCESS ||
                        (!outputStarted && inputBytes == expectedBytes && outputBytes == 0L)

                ExportOperation.SOURCE_INSPECTION ->
                    resultCode != ExportProtocol.RESULT_SUCCESS ||
                        (!outputStarted && inputBytes <= expectedBytes && outputBytes == 0L)

                ExportOperation.COPY -> false
            }
        val validDigest =
            resultCode != ExportProtocol.RESULT_SUCCESS ||
                (
                    operation != ExportOperation.CONDITIONAL_SOURCE_SAVE &&
                        operation != ExportOperation.SOURCE_INSPECTION
                    ) ||
                resultSha256.size == SHA_256_BYTE_COUNT
        val validConflict =
            resultCode != ExportProtocol.RESULT_SOURCE_CONFLICT || !outputStarted
        val validUncertain =
            resultCode != ExportProtocol.RESULT_SOURCE_UNCERTAIN || outputStarted
        val validCompletion =
            validResultCode &&
                validOutputStarted &&
                validStatistics &&
                validSuccess &&
                validDigest &&
                validConflict &&
                validUncertain
        if (!validCompletion) {
            resultCode =
                if (operation == ExportOperation.CONDITIONAL_SOURCE_SAVE) {
                    ExportProtocol.RESULT_SOURCE_UNCERTAIN
                } else {
                    ExportProtocol.RESULT_INTERNAL
                }
        }
        return ExportCompletion(
            state = stateForResult(resultCode),
            resultCode = resultCode,
            inputBytes = if (validCompletion) inputBytes else 0L,
            outputBytes = if (validCompletion) outputBytes else 0L,
            outputStarted =
                operation == ExportOperation.CONDITIONAL_SOURCE_SAVE &&
                    (outputStarted || !validCompletion),
            sourceSha256 =
                if (
                    validCompletion &&
                    resultCode == ExportProtocol.RESULT_SUCCESS &&
                    (
                        operation == ExportOperation.CONDITIONAL_SOURCE_SAVE ||
                            operation == ExportOperation.SOURCE_INSPECTION
                        )
                ) {
                    resultSha256.copyOf()
                } else {
                    ByteArray(0)
                }
        )
    }

    /** Returns a stable result code for any native return value. */
    private fun normalizeResultCode(resultCode: Int): Int =
        if (isKnownResultCode(resultCode)) resultCode else ExportProtocol.RESULT_INTERNAL

    /** Returns whether one native result belongs to the stable export protocol. */
    private fun isKnownResultCode(resultCode: Int): Boolean = when (resultCode) {
        ExportProtocol.RESULT_SUCCESS,
        ExportProtocol.RESULT_CANCELLED,
        ExportProtocol.RESULT_INPUT_LIMIT,
        ExportProtocol.RESULT_INPUT_LENGTH_MISMATCH,
        ExportProtocol.RESULT_TIMEOUT,
        ExportProtocol.RESULT_INVALID_DESCRIPTOR,
        ExportProtocol.RESULT_INPUT_IO,
        ExportProtocol.RESULT_OUTPUT_IO,
        ExportProtocol.RESULT_INTERNAL,
        ExportProtocol.RESULT_SOURCE_CONFLICT,
        ExportProtocol.RESULT_SOURCE_UNCERTAIN -> true

        else -> false
    }

    /** Returns whether a reliable descriptor reported terminal peer failure. */
    private fun hasDescriptorCompletionError(descriptor: ParcelFileDescriptor?): Boolean = try {
        if (descriptor == null || !descriptor.canDetectErrors()) {
            false
        } else {
            descriptor.checkError()
            false
        }
    } catch (_: IOException) {
        true
    } catch (_: RuntimeException) {
        true
    }

    /** Maps one fixed result code to its terminal state. */
    private fun stateForResult(resultCode: Int): Int = when (resultCode) {
        ExportProtocol.RESULT_SUCCESS -> ExportProtocol.STATE_COMPLETE
        ExportProtocol.RESULT_CANCELLED -> ExportProtocol.STATE_CANCELLED
        else -> ExportProtocol.STATE_FAILED
    }

    /** Sends the nonterminal running state when the callback remains alive. */
    private fun sendRunningStatus(job: ExportJob) {
        val shouldNotify =
            synchronized(stateLock) {
                activeJob === job && job.callbackAvailable && !job.terminalDelivered
            }
        if (!shouldNotify) {
            return
        }
        try {
            job.callback.send(
                jobId = job.jobId,
                completion =
                    ExportCompletion(
                        state = ExportProtocol.STATE_RUNNING,
                        resultCode = ExportProtocol.RESULT_SUCCESS,
                        inputBytes = 0L,
                        outputBytes = 0L
                    )
            )
        } catch (_: RemoteException) {
            handleCallbackDeath(job)
        } catch (_: RuntimeException) {
            handleCallbackDeath(job)
        }
    }

    /** Closes job resources and emits exactly one terminal state. */
    private fun finishExport(job: ExportJob, nativeCompletion: ExportCompletion) {
        val completion = closeForCompletion(job, nativeCompletion)
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
                job.callback.send(job.jobId, completion)
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

    /** Closes both descriptors and downgrades success when cleanup fails. */
    private fun closeForCompletion(
        job: ExportJob,
        nativeCompletion: ExportCompletion
    ): ExportCompletion {
        if (nativeCompletion.resultCode != ExportProtocol.RESULT_SUCCESS) {
            closeDescriptorWithError(job.input, FAILED_INPUT_ERROR)
            closeDescriptorWithError(job.backing, FAILED_INPUT_ERROR)
            closeDescriptorWithError(job.output, FAILED_OUTPUT_ERROR)
            return nativeCompletion
        }

        if (!closeDescriptor(job.input)) {
            closeDescriptorWithError(job.backing, FAILED_INPUT_ERROR)
            closeDescriptorWithError(job.output, FAILED_OUTPUT_ERROR)
            return completionForCleanupFailure(
                job = job,
                resultCode = ExportProtocol.RESULT_INPUT_IO,
                outputStarted = nativeCompletion.outputStarted
            )
        }
        if (!closeDescriptor(job.backing)) {
            closeDescriptorWithError(job.output, FAILED_OUTPUT_ERROR)
            return completionForCleanupFailure(
                job = job,
                resultCode = ExportProtocol.RESULT_INPUT_IO,
                outputStarted = nativeCompletion.outputStarted
            )
        }
        if (!closeDescriptor(job.output)) {
            closeDescriptor(job.output)
            return completionForCleanupFailure(
                job = job,
                resultCode = ExportProtocol.RESULT_OUTPUT_IO,
                outputStarted = nativeCompletion.outputStarted
            )
        }
        return nativeCompletion
    }

    /** Converts post-save cleanup failures into conservative source uncertainty. */
    private fun completionForCleanupFailure(
        job: ExportJob,
        resultCode: Int,
        outputStarted: Boolean
    ): ExportCompletion =
        if (job.operation == ExportOperation.CONDITIONAL_SOURCE_SAVE && outputStarted) {
            ExportCompletion(
                state = ExportProtocol.STATE_FAILED,
                resultCode = ExportProtocol.RESULT_SOURCE_UNCERTAIN,
                inputBytes = 0L,
                outputBytes = 0L,
                outputStarted = true
            )
        } else {
            ExportCompletion.fromResult(resultCode)
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
    private fun requestCancellationLocked(job: ExportJob) {
        if (job.terminalDelivered || job.cancellationRequested) {
            return
        }
        job.cancellationRequested = true
        cancelNativeJobLocked(job)
    }

    /** Requests native cancellation while the handle cannot be destroyed. */
    private fun cancelNativeJobLocked(job: ExportJob) {
        val nativeHandle = job.nativeHandle
        if (nativeHandle == NO_NATIVE_JOB_HANDLE) {
            return
        }
        try {
            NativeExportWorker.cancelJob(nativeHandle)
        } catch (_: Exception) {
            // Preserve the eventual authoritative native result.
        } catch (_: LinkageError) {
            // Preserve the eventual authoritative native result.
        }
    }

    /** Returns whether cancellation was requested for this job. */
    private fun isCancellationRequested(job: ExportJob): Boolean =
        synchronized(stateLock) { job.cancellationRequested }

    /** Cancels a job whose callback binder has died. */
    private fun handleCallbackDeath(job: ExportJob) {
        synchronized(stateLock) {
            if (activeJob !== job || job.terminalDelivered) {
                return
            }
            job.callbackAvailable = false
            requestCancellationLocked(job)
        }
    }

    /** Unlinks callback death observation after job completion. */
    private fun unlinkCallbackDeath(job: ExportJob) {
        try {
            job.callbackBinder.unlinkToDeath(job.callbackDeathRecipient, 0)
        } catch (_: RuntimeException) {
            // The callback binder is already dead or unlinked.
        }
    }

    /** Claims one descriptor transferred into the isolated process. */
    private fun takeTransferredDescriptor(
        transferredDescriptor: TransferredFileDescriptor?
    ): ParcelFileDescriptor? = try {
        transferredDescriptor?.takeDescriptor()
    } catch (_: RuntimeException) {
        null
    }

    /** Rejects an export and closes every descriptor received by the service. */
    private fun rejectExport(
        acceptCode: Int,
        input: ParcelFileDescriptor?,
        output: ParcelFileDescriptor?
    ): Int {
        closeRejectedOutput(output)
        if (input !== output) {
            closeDescriptor(input)
        }
        return acceptCode
    }

    /** Rejects a source save and closes every received package capability. */
    private fun rejectSourceSave(
        acceptCode: Int,
        input: ParcelFileDescriptor?,
        backing: ParcelFileDescriptor?,
        output: ParcelFileDescriptor?
    ): Int {
        closeRejectedOutput(output)
        closeDescriptor(backing)
        closeDescriptor(input)
        return acceptCode
    }

    /** Closes a rejected destination with a provider-visible failure when supported. */
    private fun closeRejectedOutput(output: ParcelFileDescriptor?) {
        try {
            output?.closeWithError(REJECTED_OUTPUT_ERROR)
        } catch (_: IOException) {
            closeDescriptor(output)
        } catch (_: RuntimeException) {
            closeDescriptor(output)
        }
    }

    /** Closes one accepted failure with a fixed provider-visible error. */
    private fun closeDescriptorWithError(
        descriptor: ParcelFileDescriptor?,
        message: String
    ): Boolean = try {
        descriptor?.closeWithError(message)
        true
    } catch (_: IOException) {
        closeDescriptor(descriptor)
        false
    } catch (_: RuntimeException) {
        closeDescriptor(descriptor)
        false
    }

    /** Returns whether a descriptor is open at the service boundary. */
    private fun isValid(descriptor: ParcelFileDescriptor): Boolean = try {
        descriptor.fileDescriptor.valid()
    } catch (_: RuntimeException) {
        false
    }

    /** Closes one service-owned descriptor and reports its cleanup result. */
    private fun closeDescriptor(descriptor: ParcelFileDescriptor?): Boolean = try {
        descriptor?.close()
        true
    } catch (_: IOException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    /** Holds the mutable lifecycle state for one accepted job. */
    private inner class ExportJob(
        val jobId: Long,
        val input: ParcelFileDescriptor,
        val backing: ParcelFileDescriptor?,
        val output: ParcelFileDescriptor?,
        val expectedBytes: Long,
        val timeoutMillis: Long,
        val callback: StatusCallback,
        val callbackBinder: IBinder,
        val operation: ExportOperation,
        val sourceExpectation: SourceExpectation?
    ) {
        val callbackDeathRecipient = IBinder.DeathRecipient { handleCallbackDeath(this) }
        var nativeHandle = NO_NATIVE_JOB_HANDLE
        var cancellationRequested = false
        var callbackAvailable = true
        var terminalDelivered = false
    }

    /** Holds one sanitized terminal status. */
    private data class ExportCompletion(
        val state: Int,
        val resultCode: Int,
        val inputBytes: Long,
        val outputBytes: Long,
        val outputStarted: Boolean = false,
        val sourceSha256: ByteArray = ByteArray(0)
    ) {
        companion object {
            /** Creates a terminal result without native statistics. */
            fun fromResult(resultCode: Int): ExportCompletion = ExportCompletion(
                state = stateForResultCode(resultCode),
                resultCode = resultCode,
                inputBytes = 0L,
                outputBytes = 0L
            )

            /** Maps one fixed result code without a service instance. */
            private fun stateForResultCode(resultCode: Int): Int = when (resultCode) {
                ExportProtocol.RESULT_SUCCESS -> ExportProtocol.STATE_COMPLETE
                ExportProtocol.RESULT_CANCELLED -> ExportProtocol.STATE_CANCELLED
                else -> ExportProtocol.STATE_FAILED
            }
        }
    }

    /** Creates the dedicated export executor thread. */
    private object ExportThreadFactory : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread = Thread(runnable, EXPORT_THREAD_NAME)
    }

    /** Identifies one isolated worker operation and its callback contract. */
    private enum class ExportOperation {
        COPY,
        CONDITIONAL_SOURCE_SAVE,
        SOURCE_VERIFICATION,
        SOURCE_INSPECTION
    }

    /** Stores one validated source expectation without exposing mutable Binder bytes. */
    private sealed interface SourceExpectation {
        /** Requires an empty target before its intentional first write. */
        data object NewTarget : SourceExpectation

        /** Stores one exact source byte length and digest. */
        data class Exact(val byteLength: Long, val sha256: ByteArray) : SourceExpectation

        /** Marks malformed source expectation fields. */
        data object Invalid : SourceExpectation
    }

    /** Returns the JNI sentinel or one validated expected source length. */
    private fun SourceExpectation.byteLengthOrAbsent(): Long = when (this) {
        SourceExpectation.NewTarget -> ExportProtocol.NO_EXPECTED_SOURCE_BYTE_LENGTH
        is SourceExpectation.Exact -> byteLength
        SourceExpectation.Invalid -> error("invalid source expectation reached JNI")
    }

    /** Returns one defensive expected digest or the new-target empty marker. */
    private fun SourceExpectation.copySha256(): ByteArray = when (this) {
        SourceExpectation.NewTarget -> ByteArray(0)
        is SourceExpectation.Exact -> sha256.copyOf()
        SourceExpectation.Invalid -> error("invalid source expectation reached JNI")
    }

    /** Sends operation-specific statuses through one shared job lifecycle. */
    private interface StatusCallback {
        val binder: IBinder

        /** Sends one bounded running or terminal status. */
        fun send(jobId: Long, completion: ExportCompletion)
    }

    /** Adapts the ordinary export callback without changing its protocol. */
    private class CopyStatusCallback(private val callback: IExportCallback) : StatusCallback {
        override val binder: IBinder = callback.asBinder()

        override fun send(jobId: Long, completion: ExportCompletion) {
            callback.onExportStatus(
                jobId,
                completion.state,
                completion.resultCode,
                completion.inputBytes,
                completion.outputBytes
            )
        }
    }

    /** Adapts conditional source callbacks with boundary and digest evidence. */
    private class SourceStatusCallback(private val callback: ISourceSaveCallback) : StatusCallback {
        override val binder: IBinder = callback.asBinder()

        override fun send(jobId: Long, completion: ExportCompletion) {
            callback.onSourceSaveStatus(
                jobId,
                completion.state,
                completion.resultCode,
                completion.inputBytes,
                completion.outputBytes,
                completion.outputStarted,
                completion.sourceSha256
            )
        }
    }
}
