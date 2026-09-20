package dev.soupslurpr.beautyxt.exporting.client

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.document.SHA_256_BYTE_COUNT
import dev.soupslurpr.beautyxt.document.SourceVersion
import dev.soupslurpr.beautyxt.exporting.ExportProtocol
import dev.soupslurpr.beautyxt.exporting.IExportCallback
import dev.soupslurpr.beautyxt.exporting.ISourceSaveCallback
import dev.soupslurpr.beautyxt.ipc.ReliableSnapshotPipe
import dev.soupslurpr.beautyxt.ipc.SealedInput
import dev.soupslurpr.beautyxt.ipc.TransferredFileDescriptor
import dev.soupslurpr.beautyxt.ipc.acquireResourceWithTimeout
import dev.soupslurpr.beautyxt.ipc.openProviderDescriptor
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val DESTINATION_OPEN_TIMEOUT_MILLIS = 30_000L
private const val SERVICE_BIND_TIMEOUT_MILLIS = 10_000L
private const val EXPORT_TIMEOUT_MILLIS = 300_000L
private const val EXPORT_WATCHDOG_MILLIS = EXPORT_TIMEOUT_MILLIS + 10_000L
private const val CANCELLATION_SETTLE_TIMEOUT_MILLIS = 10_000L
private const val DESTINATION_OPEN_MODE = "rw"
private const val REJECTED_DESTINATION_ERROR = "export request unavailable"
private const val SNAPSHOT_PIPE_ERROR = "document snapshot unavailable"
private const val SOURCE_DESCRIPTOR_ERROR = "source operation unavailable"
private const val NO_PRODUCED_BYTES = -1L
private val nextExportJobId = AtomicLong(ExportProtocol.MIN_JOB_ID)

/** Saves one immutable document revision through an isolated descriptor worker. */
internal class IsolatedDocumentExporter(context: Context) : SourceDocumentExporter {
    private val applicationContext = context.applicationContext

    /** Opens one transient selection and returns only an owned descriptor capability. */
    suspend fun openDestination(
        selection: TransientDestinationSelection
    ): TransientExportDestination {
        val uri = selection.takeUri()
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            throw DocumentExportException(DocumentExportFailure.DESTINATION_UNAVAILABLE)
        }
        return TransientExportDestination.from(openSelectedDestination(uri))
    }

    /** Takes one destination and sealed input for a cancellation-safe binary export. */
    suspend fun save(destination: TransientExportDestination, input: SealedInput) {
        val successVerified = AtomicBoolean(false)
        var unclaimedInput: ParcelFileDescriptor? = null
        var unclaimedDestination: ParcelFileDescriptor? = destination.takeDescriptor()
        try {
            unclaimedInput = input.takeReader()
            require(
                input.byteCount in ExportProtocol.MIN_BYTE_LIMIT..ExportProtocol.MAX_BYTE_LIMIT
            ) {
                "prepared input exceeds export limits"
            }
            withContext(Dispatchers.IO) {
                val ownedInput = checkNotNull(unclaimedInput)
                val ownedDestination = checkNotNull(unclaimedDestination)
                unclaimedInput = null
                unclaimedDestination = null
                try {
                    supervisorScope {
                        savePreparedInput(
                            inputDescriptor = ownedInput,
                            destinationDescriptor = ownedDestination,
                            expectedBytes = input.byteCount,
                            successVerified = successVerified,
                            snapshotProducer = null
                        )
                    }
                } catch (failure: Throwable) {
                    closeQuietly(ownedInput)
                    closeRejectedDestination(ownedDestination)
                    throw failure
                }
            }
        } catch (cancellation: CancellationException) {
            if (!successVerified.get()) {
                throw cancellation
            }
        } finally {
            closeQuietly(unclaimedInput)
            closeRejectedDestination(unclaimedDestination)
        }
    }

    /** Takes one destination descriptor and streams only the captured revision. */
    suspend fun save(
        destination: TransientExportDestination,
        snapshot: EditorDocumentSnapshot,
        expectedBytes: Long
    ) {
        val successVerified = AtomicBoolean(false)
        var unclaimedDestination: ParcelFileDescriptor? = destination.takeDescriptor()
        try {
            withContext(Dispatchers.IO) {
                val ownedDestination =
                    checkNotNull(unclaimedDestination) {
                        "destination descriptor ownership is unavailable"
                    }
                unclaimedDestination = null
                if (expectedBytes < 0L) {
                    closeRejectedDestination(ownedDestination)
                    throw IllegalArgumentException("expected byte count must be nonnegative")
                }
                if (expectedBytes > ExportProtocol.MAX_BYTE_LIMIT) {
                    closeRejectedDestination(ownedDestination)
                    throw DocumentExportException(DocumentExportFailure.TOO_LARGE)
                }
                try {
                    supervisorScope {
                        saveThroughPipe(
                            destinationDescriptor = ownedDestination,
                            snapshot = snapshot,
                            expectedBytes = expectedBytes,
                            successVerified = successVerified
                        )
                    }
                } catch (failure: Throwable) {
                    closeRejectedDestination(ownedDestination)
                    throw failure
                }
            }
        } catch (cancellation: CancellationException) {
            if (!successVerified.get()) {
                throw cancellation
            }
        } finally {
            closeRejectedDestination(unclaimedDestination)
        }
    }

    /** Conditionally replaces one source and returns its verified new version. */
    override suspend fun saveSource(
        source: TransientSourceDescriptor,
        stagedPackage: StagedSourceSavePackage,
        expectedSourceVersion: SourceVersion?
    ): SourceSaveReceipt {
        val verifiedReceipt = AtomicReference<SourceSaveReceipt?>()
        var unclaimedPackage: ParcelFileDescriptor? = null
        var unclaimedBacking: ParcelFileDescriptor? = null
        var unclaimedSource: ParcelFileDescriptor? = source.takeDescriptor()
        try {
            withContext(Dispatchers.IO) {
                val ownedSource =
                    checkNotNull(unclaimedSource) { "source descriptor ownership is unavailable" }
                val stagedDescriptors = stagedPackage.takeDescriptors()
                unclaimedPackage = stagedDescriptors.packageDescriptor
                unclaimedBacking = stagedDescriptors.sourceBackingDescriptor
                val ownedPackage = checkNotNull(unclaimedPackage) {
                    "staged package descriptor ownership is unavailable"
                }
                val ownedBacking = unclaimedBacking
                unclaimedPackage = null
                unclaimedBacking = null
                unclaimedSource = null
                try {
                    supervisorScope {
                        runSourceOperation(
                            operation = SourceOperation.SAVE,
                            inputDescriptor = ownedPackage,
                            backingDescriptor = ownedBacking,
                            sourceDescriptor = ownedSource,
                            expectedBytes = stagedPackage.outputByteLength,
                            expectedSourceVersion = expectedSourceVersion,
                            verifiedReceipt = verifiedReceipt,
                            inspectedVersion = null
                        )
                    }
                } catch (failure: Throwable) {
                    closeQuietly(ownedPackage)
                    closeQuietly(ownedBacking)
                    closeQuietly(ownedSource)
                    throw failure
                }
            }
        } catch (cancellation: CancellationException) {
            return verifiedReceipt.get() ?: throw cancellation
        } finally {
            closeQuietly(unclaimedPackage)
            closeQuietly(unclaimedBacking)
            closeQuietly(unclaimedSource)
        }
        return checkNotNull(verifiedReceipt.get()) {
            "source save completed without a verified receipt"
        }
    }

    /** Verifies one exact source version through a read-only isolated probe. */
    override suspend fun verifySource(
        source: TransientSourceDescriptor,
        expectedSourceVersion: SourceVersion
    ) {
        var unclaimedSource: ParcelFileDescriptor? = source.takeDescriptor()
        try {
            withContext(Dispatchers.IO) {
                val ownedSource =
                    checkNotNull(unclaimedSource) { "source descriptor ownership is unavailable" }
                unclaimedSource = null
                try {
                    supervisorScope {
                        runSourceOperation(
                            operation = SourceOperation.VERIFY,
                            inputDescriptor = ownedSource,
                            backingDescriptor = null,
                            sourceDescriptor = null,
                            expectedBytes = expectedSourceVersion.byteLength,
                            expectedSourceVersion = expectedSourceVersion,
                            verifiedReceipt = null,
                            inspectedVersion = null
                        )
                    }
                } catch (failure: Throwable) {
                    closeQuietly(ownedSource)
                    throw failure
                }
            }
        } finally {
            closeQuietly(unclaimedSource)
        }
    }

    /** Returns one source's exact bounded version through a read-only isolated probe. */
    override suspend fun inspectSource(
        source: TransientSourceDescriptor,
        maximumBytes: Long
    ): SourceVersion {
        val inspectedVersion = AtomicReference<SourceVersion?>()
        var unclaimedSource: ParcelFileDescriptor? = source.takeDescriptor()
        try {
            withContext(Dispatchers.IO) {
                val ownedSource =
                    checkNotNull(unclaimedSource) { "source descriptor ownership is unavailable" }
                unclaimedSource = null
                try {
                    supervisorScope {
                        runSourceOperation(
                            operation = SourceOperation.INSPECT,
                            inputDescriptor = ownedSource,
                            backingDescriptor = null,
                            sourceDescriptor = null,
                            expectedBytes = maximumBytes,
                            expectedSourceVersion = null,
                            verifiedReceipt = null,
                            inspectedVersion = inspectedVersion
                        )
                    }
                } catch (failure: Throwable) {
                    closeQuietly(ownedSource)
                    throw failure
                }
            }
        } finally {
            closeQuietly(unclaimedSource)
        }
        return checkNotNull(inspectedVersion.get()) {
            "source inspection completed without an exact version"
        }
    }

    /** Runs one isolated source save, verification, or inspection lifecycle. */
    private suspend fun CoroutineScope.runSourceOperation(
        operation: SourceOperation,
        inputDescriptor: ParcelFileDescriptor,
        backingDescriptor: ParcelFileDescriptor?,
        sourceDescriptor: ParcelFileDescriptor?,
        expectedBytes: Long,
        expectedSourceVersion: SourceVersion?,
        verifiedReceipt: AtomicReference<SourceSaveReceipt?>?,
        inspectedVersion: AtomicReference<SourceVersion?>?
    ) {
        require(expectedBytes in ExportProtocol.MIN_BYTE_LIMIT..ExportProtocol.MAX_BYTE_LIMIT) {
            "expected byte count exceeds export limits"
        }
        require(operation != SourceOperation.VERIFY || expectedSourceVersion != null) {
            "source verification requires an expected version"
        }
        require(operation != SourceOperation.INSPECT || expectedSourceVersion == null) {
            "source inspection accepts no expected version"
        }
        require(operation == SourceOperation.SAVE || backingDescriptor == null) {
            "read-only source operations accept no package backing"
        }
        require(operation == SourceOperation.SAVE || sourceDescriptor == null) {
            "read-only source operations accept exactly one descriptor"
        }
        require(operation != SourceOperation.SAVE || sourceDescriptor != null) {
            "source saving requires an output source descriptor"
        }
        val expectedSourceBytes =
            expectedSourceVersion?.byteLength
                ?: ExportProtocol.NO_EXPECTED_SOURCE_BYTE_LENGTH
        val expectedSourceSha256 = expectedSourceVersion?.copySha256() ?: ByteArray(0)
        val jobId = nextJobId()
        var input: ParcelFileDescriptor? = inputDescriptor
        var backing: ParcelFileDescriptor? = backingDescriptor
        var source: ParcelFileDescriptor? = sourceDescriptor
        val binding =
            try {
                IsolatedExportServiceBinding(applicationContext)
            } catch (failure: Throwable) {
                closeQuietly(input)
                input = null
                closeQuietly(backing)
                backing = null
                closeQuietly(source)
                source = null
                throw failure
            }
        var transferredInput: TransferredFileDescriptor? = null
        var transferredBacking: TransferredFileDescriptor? = null
        var transferredSource: TransferredFileDescriptor? = null
        var completion: CompletableDeferred<SourceTerminalStatus>? = null
        var callback: SourceCallback? = null
        var accepted = false
        var authoritativeTerminal = false
        var completed = false
        try {
            binding.bind()
            val service =
                try {
                    withTimeout(SERVICE_BIND_TIMEOUT_MILLIS) {
                        binding.awaitService()
                    }
                } catch (failure: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw DocumentExportException(
                        DocumentExportFailure.SERVICE_UNAVAILABLE,
                        failure
                    )
                }
            transferredInput = TransferredFileDescriptor.from(checkNotNull(input))
            input = null
            if (operation == SourceOperation.SAVE) {
                transferredBacking = backing?.let(TransferredFileDescriptor::from)
                backing = null
                transferredSource = TransferredFileDescriptor.from(checkNotNull(source))
                source = null
            }
            completion = CompletableDeferred()
            binding.attachOperation(completion)
            val sourceCallback =
                SourceCallback(
                    jobId = jobId,
                    operation = operation,
                    completion = completion
                )
            callback = sourceCallback
            val acceptCode =
                try {
                    when (operation) {
                        SourceOperation.SAVE ->
                            service.startConditionalSourceSave(
                                jobId,
                                transferredInput,
                                transferredBacking,
                                transferredSource,
                                expectedBytes,
                                expectedSourceBytes,
                                expectedSourceSha256,
                                EXPORT_TIMEOUT_MILLIS,
                                sourceCallback
                            )

                        SourceOperation.VERIFY ->
                            service.startSourceVerification(
                                jobId,
                                transferredInput,
                                expectedSourceBytes,
                                expectedSourceSha256,
                                EXPORT_TIMEOUT_MILLIS,
                                sourceCallback
                            )

                        SourceOperation.INSPECT ->
                            service.startSourceInspection(
                                jobId,
                                transferredInput,
                                expectedBytes,
                                EXPORT_TIMEOUT_MILLIS,
                                sourceCallback
                            )
                    }
                } catch (failure: RemoteException) {
                    throw DocumentExportException(
                        operation.indeterminateFailure(),
                        failure
                    )
                } catch (failure: RuntimeException) {
                    throw DocumentExportException(
                        operation.indeterminateFailure(),
                        failure
                    )
                } catch (failure: LinkageError) {
                    throw DocumentExportException(
                        operation.indeterminateFailure(),
                        failure
                    )
                } finally {
                    transferredInput.closeWithError(SOURCE_DESCRIPTOR_ERROR)
                    transferredBacking?.closeWithError(SOURCE_DESCRIPTOR_ERROR)
                    transferredSource?.closeWithError(SOURCE_DESCRIPTOR_ERROR)
                    transferredInput = null
                    transferredBacking = null
                    transferredSource = null
                    closeQuietly(input)
                    input = null
                    closeQuietly(backing)
                    backing = null
                    closeQuietly(source)
                    source = null
                }
            throwForSourceAcceptCode(acceptCode, operation)
            accepted = true
            currentCoroutineContext().ensureActive()

            val terminalStatus =
                try {
                    withTimeout(EXPORT_WATCHDOG_MILLIS) {
                        completion.await()
                    }
                } catch (failure: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw DocumentExportException(DocumentExportFailure.TIMED_OUT, failure)
                }
            if (!sourceCallback.hasCanonicalTerminal()) {
                throw DocumentExportException(DocumentExportFailure.INVALID_RESPONSE)
            }
            when (val result = terminalStatus.toTerminalResult(operation, expectedBytes)) {
                is SourceTerminalResult.Saved -> {
                    check(operation == SourceOperation.SAVE) {
                        "source verification returned a save receipt"
                    }
                    checkNotNull(verifiedReceipt).set(result.receipt)
                }

                SourceTerminalResult.Verified -> {
                    check(operation == SourceOperation.VERIFY) {
                        "source save returned a verification result"
                    }
                }

                is SourceTerminalResult.Inspected -> {
                    check(operation == SourceOperation.INSPECT) {
                        "source operation returned an inspection result"
                    }
                    checkNotNull(inspectedVersion).set(result.sourceVersion)
                }

                is SourceTerminalResult.Failed -> {
                    authoritativeTerminal =
                        result.failure != DocumentExportFailure.INVALID_RESPONSE
                    throw DocumentExportException(result.failure)
                }
            }
            authoritativeTerminal = true
            completed = true
        } catch (failure: CancellationException) {
            if (accepted) {
                binding.cancelExport(jobId)
                when (
                    val settled =
                        awaitSourceCancellation(
                            completion = completion,
                            callback = callback,
                            operation = operation,
                            expectedBytes = expectedBytes
                        )
                ) {
                    is SourceTerminalResult.Saved -> {
                        checkNotNull(verifiedReceipt).set(settled.receipt)
                        completed = true
                        return
                    }

                    SourceTerminalResult.Verified -> {
                        completed = true
                        return
                    }

                    is SourceTerminalResult.Inspected -> {
                        checkNotNull(inspectedVersion).set(settled.sourceVersion)
                        completed = true
                        return
                    }

                    is SourceTerminalResult.Failed -> {
                        if (settled.failure == DocumentExportFailure.SOURCE_UNCERTAIN) {
                            throw DocumentExportException(settled.failure, failure)
                        }
                    }

                    null ->
                        throw DocumentExportException(
                            operation.indeterminateFailure(),
                            failure
                        )
                }
            }
            throw failure
        } catch (failure: DocumentExportException) {
            if (accepted) {
                binding.cancelExport(jobId)
            }
            if (accepted && !authoritativeTerminal) {
                throw DocumentExportException(operation.indeterminateFailure(), failure)
            }
            throw failure
        } catch (failure: Exception) {
            if (accepted) {
                binding.cancelExport(jobId)
                throw DocumentExportException(operation.indeterminateFailure(), failure)
            }
            throw DocumentExportException(DocumentExportFailure.WRITE_FAILED, failure)
        } catch (failure: LinkageError) {
            if (accepted) {
                binding.cancelExport(jobId)
                throw DocumentExportException(operation.indeterminateFailure(), failure)
            }
            throw failure
        } finally {
            completion?.cancel()
            transferredInput?.closeWithError(SOURCE_DESCRIPTOR_ERROR)
            transferredBacking?.closeWithError(SOURCE_DESCRIPTOR_ERROR)
            transferredSource?.closeWithError(SOURCE_DESCRIPTOR_ERROR)
            closeQuietly(input)
            closeQuietly(backing)
            closeQuietly(source)
            if (accepted && !completed) {
                binding.cancelExport(jobId)
            }
            binding.close()
        }
    }

    /** Waits for one authoritative source result after caller cancellation. */
    private suspend fun awaitSourceCancellation(
        completion: CompletableDeferred<SourceTerminalStatus>?,
        callback: SourceCallback?,
        operation: SourceOperation,
        expectedBytes: Long
    ): SourceTerminalResult? {
        val terminalStatus =
            try {
                withContext(NonCancellable) {
                    withTimeout(CANCELLATION_SETTLE_TIMEOUT_MILLIS) {
                        completion?.await()
                    }
                }
            } catch (_: Exception) {
                return null
            }
                ?: return null
        if (callback?.hasCanonicalTerminal() != true) {
            return null
        }
        val result = terminalStatus.toTerminalResult(operation, expectedBytes)
        return result.takeUnless {
            it is SourceTerminalResult.Failed &&
                it.failure == DocumentExportFailure.INVALID_RESPONSE
        }
    }

    /** Prepares one live producer pipe before running the shared export lifecycle. */
    private suspend fun CoroutineScope.saveThroughPipe(
        destinationDescriptor: ParcelFileDescriptor,
        snapshot: EditorDocumentSnapshot,
        expectedBytes: Long,
        successVerified: AtomicBoolean
    ) {
        val pipe = try {
            ReliableSnapshotPipe.create(EXPORT_TIMEOUT_MILLIS)
        } catch (failure: Exception) {
            throw DocumentExportException(DocumentExportFailure.PIPE_UNAVAILABLE, failure)
        }
        try {
            savePreparedInput(
                inputDescriptor = pipe.takeReader(),
                destinationDescriptor = destinationDescriptor,
                expectedBytes = expectedBytes,
                successVerified = successVerified,
                snapshotProducer = SnapshotPipeProducer(pipe = pipe, snapshot = snapshot)
            )
        } finally {
            pipe.close()
        }
    }

    /** Owns binding, descriptors, callback completion, and cleanup for one export. */
    private suspend fun CoroutineScope.savePreparedInput(
        inputDescriptor: ParcelFileDescriptor,
        destinationDescriptor: ParcelFileDescriptor,
        expectedBytes: Long,
        successVerified: AtomicBoolean,
        snapshotProducer: SnapshotPipeProducer?
    ) {
        require(expectedBytes in ExportProtocol.MIN_BYTE_LIMIT..ExportProtocol.MAX_BYTE_LIMIT) {
            "expected byte count exceeds export limits"
        }
        val jobId = nextJobId()
        var input: ParcelFileDescriptor? = inputDescriptor
        var destination: ParcelFileDescriptor? = destinationDescriptor
        val binding =
            try {
                IsolatedExportServiceBinding(applicationContext)
            } catch (failure: Throwable) {
                closeQuietly(input)
                input = null
                closeRejectedDestination(destination)
                destination = null
                snapshotProducer?.fail()
                throw failure
            }
        var transferredInput: TransferredFileDescriptor? = null
        var transferredDestination: TransferredFileDescriptor? = null
        var completion: CompletableDeferred<ExportTerminalStatus>? = null
        var callback: ExportCallback? = null
        var producer: Deferred<Long>? = null
        var accepted = false
        var completed = false
        val producedBytes = AtomicLong(NO_PRODUCED_BYTES)
        try {
            binding.bind()
            val service =
                try {
                    withTimeout(SERVICE_BIND_TIMEOUT_MILLIS) {
                        binding.awaitService()
                    }
                } catch (failure: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw DocumentExportException(
                        DocumentExportFailure.SERVICE_UNAVAILABLE,
                        failure
                    )
                }
            transferredInput = TransferredFileDescriptor.from(checkNotNull(input))
            input = null
            transferredDestination = TransferredFileDescriptor.from(checkNotNull(destination))
            destination = null
            completion = CompletableDeferred()
            binding.attachOperation(completion)
            val exportCallback =
                ExportCallback(
                    jobId = jobId,
                    expectedBytes = expectedBytes,
                    completion = completion
                )
            callback = exportCallback
            val acceptCode =
                try {
                    service.startExport(
                        jobId,
                        transferredInput,
                        transferredDestination,
                        expectedBytes,
                        EXPORT_TIMEOUT_MILLIS,
                        exportCallback
                    )
                } catch (failure: RemoteException) {
                    throw DocumentExportException(
                        DocumentExportFailure.SERVICE_UNAVAILABLE,
                        failure
                    )
                } finally {
                    transferredInput.closeWithError(SNAPSHOT_PIPE_ERROR)
                    transferredDestination.closeWithError(REJECTED_DESTINATION_ERROR)
                    transferredInput = null
                    transferredDestination = null
                    closeQuietly(input)
                    input = null
                    closeRejectedDestination(destination)
                    destination = null
                }
            throwForAcceptCode(acceptCode)
            accepted = true
            currentCoroutineContext().ensureActive()

            producer = snapshotProducer?.start(this, producedBytes)
            val terminalStatus =
                try {
                    withTimeout(EXPORT_WATCHDOG_MILLIS) {
                        completion.await()
                    }
                } catch (failure: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw DocumentExportException(DocumentExportFailure.TIMED_OUT, failure)
                }
            terminalStatus.failureOrNull(expectedBytes)?.let { failure ->
                throw DocumentExportException(failure)
            }
            if (!exportCallback.hasCanonicalTerminal()) {
                throw DocumentExportException(DocumentExportFailure.INVALID_RESPONSE)
            }
            if (producer != null) {
                withContext(NonCancellable) {
                    producer.join()
                }
                if (producedBytes.get() != expectedBytes) {
                    throw DocumentExportException(DocumentExportFailure.INVALID_RESPONSE)
                }
            }
            successVerified.set(true)
            completed = true
        } catch (failure: CancellationException) {
            if (accepted) {
                binding.cancelExport(jobId)
            }
            if (
                accepted &&
                awaitCommittedCancellation(
                    completion = completion,
                    callback = callback,
                    producer = producer,
                    producedBytes = producedBytes,
                    expectedBytes = expectedBytes
                )
            ) {
                successVerified.set(true)
                completed = true
                return
            }
            snapshotProducer?.fail()
            producer?.cancel()
            throw failure
        } catch (failure: DocumentExportException) {
            if (accepted) {
                binding.cancelExport(jobId)
            }
            snapshotProducer?.fail()
            producer?.cancel()
            throw failure
        } catch (failure: Exception) {
            if (accepted) {
                binding.cancelExport(jobId)
            }
            snapshotProducer?.fail()
            producer?.cancel()
            throw DocumentExportException(DocumentExportFailure.WRITE_FAILED, failure)
        } finally {
            completion?.cancel()
            transferredInput?.closeWithError(SNAPSHOT_PIPE_ERROR)
            transferredDestination?.closeWithError(REJECTED_DESTINATION_ERROR)
            closeQuietly(input)
            closeRejectedDestination(destination)
            if (accepted && !completed) {
                binding.cancelExport(jobId)
            }
            if (!completed) {
                snapshotProducer?.fail()
            }
            binding.close()
        }
    }

    /** Waits briefly for the authoritative result after cancellation races commit. */
    private suspend fun awaitCommittedCancellation(
        completion: CompletableDeferred<ExportTerminalStatus>?,
        callback: ExportCallback?,
        producer: Deferred<Long>?,
        producedBytes: AtomicLong,
        expectedBytes: Long
    ): Boolean {
        val terminalStatus =
            try {
                withContext(NonCancellable) {
                    withTimeout(CANCELLATION_SETTLE_TIMEOUT_MILLIS) {
                        completion?.await()
                    }
                }
            } catch (_: Exception) {
                return false
            }
                ?: return false
        if (
            terminalStatus.failureOrNull(expectedBytes) != null ||
            callback?.hasCanonicalTerminal() != true
        ) {
            return false
        }
        if (producer != null) {
            withContext(NonCancellable) {
                producer.join()
            }
            return producedBytes.get() == expectedBytes
        }
        return true
    }

    /** Opens one selected destination without truncating before service acceptance. */
    private suspend fun openSelectedDestination(uri: Uri): ParcelFileDescriptor = try {
        acquireResourceWithTimeout(DESTINATION_OPEN_TIMEOUT_MILLIS) {
            openProviderDescriptor(
                resolver = applicationContext.contentResolver,
                uri = uri,
                mode = DESTINATION_OPEN_MODE,
                closeUnclaimed = ::closeRejectedDestination
            )
        }
    } catch (failure: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw DocumentExportException(DocumentExportFailure.DESTINATION_UNAVAILABLE, failure)
    } catch (failure: OperationCanceledException) {
        currentCoroutineContext().ensureActive()
        throw DocumentExportException(DocumentExportFailure.DESTINATION_UNAVAILABLE, failure)
    } catch (failure: SecurityException) {
        throw DocumentExportException(DocumentExportFailure.DESTINATION_UNAVAILABLE, failure)
    } catch (failure: IOException) {
        throw DocumentExportException(DocumentExportFailure.DESTINATION_UNAVAILABLE, failure)
    }

    /** Rejects every non-accepted synchronous service result. */
    private fun throwForAcceptCode(acceptCode: Int) {
        when (acceptCode) {
            ExportProtocol.ACCEPT_ACCEPTED -> Unit

            ExportProtocol.ACCEPT_BUSY ->
                throw DocumentExportException(DocumentExportFailure.SERVICE_BUSY)

            ExportProtocol.ACCEPT_INVALID_ARGUMENT ->
                throw DocumentExportException(DocumentExportFailure.INVALID_RESPONSE)

            ExportProtocol.ACCEPT_CALLBACK_UNAVAILABLE ->
                throw DocumentExportException(DocumentExportFailure.SERVICE_UNAVAILABLE)

            else -> throw DocumentExportException(DocumentExportFailure.INVALID_RESPONSE)
        }
    }

    /** Rejects source accept codes while preserving indeterminate save safety. */
    private fun throwForSourceAcceptCode(acceptCode: Int, operation: SourceOperation) {
        when (acceptCode) {
            ExportProtocol.ACCEPT_ACCEPTED -> Unit

            ExportProtocol.ACCEPT_BUSY ->
                throw DocumentExportException(DocumentExportFailure.SERVICE_BUSY)

            ExportProtocol.ACCEPT_INVALID_ARGUMENT ->
                throw DocumentExportException(DocumentExportFailure.INVALID_RESPONSE)

            ExportProtocol.ACCEPT_CALLBACK_UNAVAILABLE ->
                throw DocumentExportException(DocumentExportFailure.SERVICE_UNAVAILABLE)

            else -> throw DocumentExportException(operation.indeterminateFailure())
        }
    }

    /** Returns one process-unique positive export job identifier. */
    private fun nextJobId(): Long {
        val jobId = nextExportJobId.getAndUpdate { current ->
            if (current == Long.MAX_VALUE) ExportProtocol.MIN_JOB_ID else current + 1L
        }
        check(ExportProtocol.isValidJobId(jobId)) {
            "export job identifier must be positive"
        }
        return jobId
    }
}

/** Owns one opened destination descriptor until export claims it. */
internal class TransientExportDestination private constructor(
    private var descriptor: ParcelFileDescriptor?
) : AutoCloseable {
    /** Transfers the only destination capability to one export operation. */
    @Synchronized
    fun takeDescriptor(): ParcelFileDescriptor =
        checkNotNull(descriptor) { "destination descriptor is unavailable" }.also {
            descriptor = null
        }

    /** Rejects an unclaimed destination without exposing provider details. */
    @Synchronized
    override fun close() {
        closeRejectedDestination(descriptor)
        descriptor = null
    }

    companion object {
        /** Creates one owner around a freshly opened destination capability. */
        fun from(descriptor: ParcelFileDescriptor): TransientExportDestination =
            TransientExportDestination(descriptor)
    }
}

/** Publishes exactly one terminal callback for the expected export job. */
private class ExportCallback(
    private val jobId: Long,
    private val expectedBytes: Long,
    private val completion: CompletableDeferred<ExportTerminalStatus>
) : IExportCallback.Stub() {
    private val protocol = ExportCallbackProtocol()

    override fun onExportStatus(
        jobId: Long,
        state: Int,
        resultCode: Int,
        inputBytes: Long,
        outputBytes: Long
    ) {
        if (Binder.getCallingUid() == Process.myUid() || jobId != this.jobId) {
            completion.completeExceptionally(
                DocumentExportException(DocumentExportFailure.INVALID_RESPONSE)
            )
            return
        }
        val status =
            ExportTerminalStatus(
                state = state,
                resultCode = resultCode,
                inputBytes = inputBytes,
                outputBytes = outputBytes
            )
        when (protocol.accept(status)) {
            ExportCallbackDecision.RUNNING -> Unit

            ExportCallbackDecision.TERMINAL -> {
                completion.complete(status)
            }

            ExportCallbackDecision.INVALID ->
                completion.completeExceptionally(
                    DocumentExportException(DocumentExportFailure.INVALID_RESPONSE)
                )
        }
    }

    /** Returns whether exactly one ordered terminal callback remains valid. */
    fun hasCanonicalTerminal(): Boolean = protocol.hasCanonicalTerminal()
}

/** Publishes exactly one terminal source callback for the expected job. */
private class SourceCallback(
    private val jobId: Long,
    private val operation: SourceOperation,
    private val completion: CompletableDeferred<SourceTerminalStatus>
) : ISourceSaveCallback.Stub() {
    private val protocol = ExportCallbackProtocol()

    override fun onSourceSaveStatus(
        jobId: Long,
        state: Int,
        resultCode: Int,
        inputBytes: Long,
        outputBytes: Long,
        outputStarted: Boolean,
        sourceSha256: ByteArray?
    ) {
        if (
            Binder.getCallingUid() == Process.myUid() ||
            jobId != this.jobId ||
            sourceSha256 == null
        ) {
            completion.completeExceptionally(
                DocumentExportException(DocumentExportFailure.INVALID_RESPONSE)
            )
            return
        }
        if (
            state == ExportProtocol.STATE_RUNNING &&
            (outputStarted || sourceSha256.isNotEmpty())
        ) {
            completion.completeExceptionally(
                DocumentExportException(DocumentExportFailure.INVALID_RESPONSE)
            )
            return
        }
        val expectedDigestBytes =
            if (
                (
                    operation == SourceOperation.SAVE ||
                        operation == SourceOperation.INSPECT
                    ) &&
                state == ExportProtocol.STATE_COMPLETE &&
                resultCode == ExportProtocol.RESULT_SUCCESS
            ) {
                SHA_256_BYTE_COUNT
            } else {
                0
            }
        if (sourceSha256.size != expectedDigestBytes) {
            completion.completeExceptionally(
                DocumentExportException(DocumentExportFailure.INVALID_RESPONSE)
            )
            return
        }
        val orderingStatus =
            ExportTerminalStatus(
                state = state,
                resultCode = resultCode,
                inputBytes = inputBytes,
                outputBytes = outputBytes
            )
        val status =
            SourceTerminalStatus(
                state = state,
                resultCode = resultCode,
                inputBytes = inputBytes,
                outputBytes = outputBytes,
                outputStarted = outputStarted,
                sourceSha256 = sourceSha256
            )
        when (protocol.accept(orderingStatus)) {
            ExportCallbackDecision.RUNNING -> Unit

            ExportCallbackDecision.TERMINAL -> completion.complete(status)

            ExportCallbackDecision.INVALID ->
                completion.completeExceptionally(
                    DocumentExportException(DocumentExportFailure.INVALID_RESPONSE)
                )
        }
    }

    /** Returns whether exactly one ordered terminal callback remains valid. */
    fun hasCanonicalTerminal(): Boolean = protocol.hasCanonicalTerminal()
}

/** Starts and interrupts one live snapshot producer for the legacy save path. */
private class SnapshotPipeProducer(
    private val pipe: ReliableSnapshotPipe,
    private val snapshot: EditorDocumentSnapshot
) {
    /** Starts one bounded producer and records its exact byte count. */
    fun start(scope: CoroutineScope, producedBytes: AtomicLong): Deferred<Long> =
        scope.async(Dispatchers.IO) {
            try {
                pipe.writeSnapshot(snapshot).also(producedBytes::set)
            } catch (failure: Exception) {
                throw DocumentExportException(DocumentExportFailure.SNAPSHOT_FAILED, failure)
            } catch (failure: LinkageError) {
                throw DocumentExportException(DocumentExportFailure.SNAPSHOT_FAILED, failure)
            }
        }

    /** Interrupts one running producer through its explicit cancellation pipe. */
    fun fail() {
        pipe.failWriter()
    }
}

/** Closes one caller-owned descriptor without retaining provider details. */
private fun closeQuietly(descriptor: ParcelFileDescriptor?) {
    try {
        descriptor?.close()
    } catch (_: Exception) {
        // Descriptor ownership ends even when close reports an error.
    }
}

/** Closes an unaccepted destination with a provider-visible failure when supported. */
private fun closeRejectedDestination(destination: ParcelFileDescriptor?) {
    try {
        destination?.closeWithError(REJECTED_DESTINATION_ERROR)
    } catch (_: IOException) {
        closeQuietly(destination)
    } catch (_: RuntimeException) {
        closeQuietly(destination)
    }
}
