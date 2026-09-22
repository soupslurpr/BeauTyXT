package dev.soupslurpr.beautyxt.importing.client

import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import dev.soupslurpr.beautyxt.document.RustDocument
import dev.soupslurpr.beautyxt.importing.IImportCallback
import dev.soupslurpr.beautyxt.importing.ImportProtocol
import dev.soupslurpr.beautyxt.ipc.TransferredFileDescriptor
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val SERVICE_BIND_TIMEOUT_MILLIS = 10_000L
private const val IMPORT_TIMEOUT_MILLIS = 300_000L
private const val IMPORT_WATCHDOG_MILLIS = IMPORT_TIMEOUT_MILLIS + 10_000L
private const val FIRST_IMPORT_JOB_ID = 1L
private val nextImportJobId = AtomicLong(FIRST_IMPORT_JOB_ID)

/** Opens one selected document through isolated UTF-8 validation. */
internal class IsolatedDocumentImporter(
    context: Context,
    private val bindingFactory: (Context) -> IsolatedImportServiceBinding =
        ::IsolatedImportServiceBinding
) {
    private val applicationContext = context.applicationContext

    /** Opens one content URI and optionally probes its autosave capability. */
    suspend fun open(uri: Uri, allowSourceWriteAccess: Boolean = true): ImportedDocument {
        var undeliveredDocument: ImportedDocument? = null
        var primaryFailure: Throwable? = null
        try {
            val importedDocument =
                withContext(Dispatchers.IO) {
                    openOwnedDocument(
                        uri = uri,
                        allowSourceWriteAccess = allowSourceWriteAccess
                    ).also { openedDocument ->
                        undeliveredDocument = openedDocument
                    }
                }
            currentCoroutineContext().ensureActive()
            undeliveredDocument = null
            return importedDocument
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                undeliveredDocument?.close()
            } catch (cleanupFailure: Throwable) {
                if (primaryFailure == null) {
                    throw cleanupFailure
                }
                primaryFailure.addSuppressed(cleanupFailure)
            }
        }
    }

    /** Builds one aggregate owner and closes every capability until transfer succeeds. */
    private suspend fun openOwnedDocument(
        uri: Uri,
        allowSourceWriteAccess: Boolean
    ): ImportedDocument {
        val selectedSource =
            try {
                SelectedDocumentSource.takeOwnership(applicationContext, uri)
            } catch (failure: IllegalArgumentException) {
                throw DocumentImportException(DocumentImportFailure.SOURCE_UNAVAILABLE, failure)
            }
        var document: RustDocument? = null
        var transferred = false
        var primaryFailure: Throwable? = null
        try {
            val metadata =
                querySelectedDocumentMetadata(
                    contentResolver = applicationContext.contentResolver,
                    uri = uri
                )
            document =
                openThroughBuffer(
                    selectedSource = selectedSource,
                    buffer = AnonymousImportBuffer.create()
                )
            currentCoroutineContext().ensureActive()
            val sourceAccess =
                if (
                    allowSourceWriteAccess &&
                    selectedSource.hasCompatibleWriteAccess()
                ) {
                    ImportedSourceAccess.ReadWrite
                } else {
                    ImportedSourceAccess.ReadOnly
                }
            return ImportedDocument.takeOwnership(
                document = document,
                selectedSource = selectedSource,
                sourceAccess = sourceAccess,
                metadata = metadata
            ).also {
                document = null
                transferred = true
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                document?.close()
            } catch (cleanupFailure: Throwable) {
                if (primaryFailure == null) {
                    primaryFailure = cleanupFailure
                } else {
                    primaryFailure.addSuppressed(cleanupFailure)
                }
            }
            if (!transferred) {
                try {
                    selectedSource.close()
                } catch (cleanupFailure: Throwable) {
                    if (primaryFailure == null) {
                        throw cleanupFailure
                    }
                    primaryFailure.addSuppressed(cleanupFailure)
                }
            }
            if (primaryFailure != null && primaryFailure !is CancellationException) {
                throw primaryFailure
            }
        }
    }

    /** Owns binding, descriptors, callback completion, and cleanup for one job. */
    private suspend fun openThroughBuffer(
        selectedSource: SelectedDocumentSource,
        buffer: AnonymousImportBuffer
    ): RustDocument {
        val jobId = nextJobId()
        val binding = bindingFactory(applicationContext)
        var sourceDescriptor: ParcelFileDescriptor? = null
        var bufferWriter: ParcelFileDescriptor? = null
        var completion: CompletableDeferred<ImportTerminalStatus>? = null
        var accepted = false
        var document: RustDocument? = null
        var primaryFailure: Throwable? = null
        try {
            binding.bind()
            val service =
                try {
                    withTimeout(SERVICE_BIND_TIMEOUT_MILLIS) {
                        binding.awaitService()
                    }
                } catch (failure: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw DocumentImportException(
                        DocumentImportFailure.SERVICE_UNAVAILABLE,
                        failure
                    )
                }

            sourceDescriptor =
                try {
                    selectedSource.openReadDescriptor()
                } catch (failure: SelectedDocumentSourceException) {
                    throw DocumentImportException(
                        DocumentImportFailure.SOURCE_UNAVAILABLE,
                        failure
                    )
                }
            bufferWriter = buffer.takeWriter()
            completion = CompletableDeferred()
            binding.attachOperation(completion)
            val callback = ImportCallback(jobId = jobId, completion = completion)
            val acceptCode =
                try {
                    service.startImport(
                        jobId,
                        TransferredFileDescriptor.from(sourceDescriptor),
                        TransferredFileDescriptor.from(bufferWriter),
                        ImportProtocol.MAX_BYTE_LIMIT,
                        ImportProtocol.MAX_BYTE_LIMIT,
                        IMPORT_TIMEOUT_MILLIS,
                        callback
                    )
                } catch (failure: RemoteException) {
                    throw DocumentImportException(
                        DocumentImportFailure.SERVICE_UNAVAILABLE,
                        failure
                    )
                } finally {
                    closeQuietly(bufferWriter)
                    bufferWriter = null
                }
            throwForAcceptCode(acceptCode)
            accepted = true
            currentCoroutineContext().ensureActive()

            // The service owns the transferred data and reliable provider status.
            // Wait for its terminal receipt before opening the validated buffer.
            val terminalStatus =
                try {
                    withTimeout(IMPORT_WATCHDOG_MILLIS) {
                        completion.await()
                    }
                } catch (failure: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw DocumentImportException(DocumentImportFailure.TIMED_OUT, failure)
                }
            closeQuietly(sourceDescriptor)
            sourceDescriptor = null
            terminalStatus.failureOrNull(
                maxInputBytes = ImportProtocol.MAX_BYTE_LIMIT,
                maxOutputBytes = ImportProtocol.MAX_BYTE_LIMIT
            )?.let { failure -> throw DocumentImportException(failure) }
            currentCoroutineContext().ensureActive()

            val openedDocument =
                try {
                    RustDocument.openSource(
                        rawFileDescriptor = buffer.prepareReader(terminalStatus.outputBytes),
                        expectedBytes = terminalStatus.outputBytes
                    )
                } catch (failure: Exception) {
                    throw DocumentImportException(DocumentImportFailure.OPEN_FAILED, failure)
                } catch (failure: LinkageError) {
                    throw DocumentImportException(DocumentImportFailure.OPEN_FAILED, failure)
                }
            try {
                currentCoroutineContext().ensureActive()
                selectedSource.advanceSourceVersion(
                    expectedVersion = null,
                    newVersion = terminalStatus.sourceVersion()
                )
            } catch (failure: Throwable) {
                openedDocument.close()
                throw failure
            }
            document = openedDocument
            return openedDocument
        } catch (failure: CancellationException) {
            primaryFailure = failure
            if (accepted) {
                binding.cancelImport(jobId)
            }
            throw failure
        } catch (failure: DocumentImportException) {
            primaryFailure = failure
            throw failure
        } catch (failure: Exception) {
            val sanitized = DocumentImportException(DocumentImportFailure.OPEN_FAILED, failure)
            primaryFailure = sanitized
            throw sanitized
        } finally {
            completion?.cancel()
            closeQuietly(sourceDescriptor)
            closeQuietly(bufferWriter)
            if (accepted && document == null) {
                binding.cancelImport(jobId)
            }
            binding.close()
            try {
                buffer.close()
            } catch (cleanupFailure: Throwable) {
                document?.close()
                if (primaryFailure == null) {
                    throw DocumentImportException(
                        DocumentImportFailure.BUFFER_UNAVAILABLE,
                        cleanupFailure
                    )
                }
                primaryFailure.addSuppressed(cleanupFailure)
            }
        }
    }

    /** Rejects every non-accepted synchronous service result. */
    private fun throwForAcceptCode(acceptCode: Int) {
        when (acceptCode) {
            ImportProtocol.ACCEPT_ACCEPTED -> Unit

            ImportProtocol.ACCEPT_BUSY ->
                throw DocumentImportException(DocumentImportFailure.SERVICE_BUSY)

            ImportProtocol.ACCEPT_INVALID_ARGUMENT ->
                throw DocumentImportException(DocumentImportFailure.INVALID_RESPONSE)

            ImportProtocol.ACCEPT_CALLBACK_UNAVAILABLE ->
                throw DocumentImportException(DocumentImportFailure.SERVICE_UNAVAILABLE)

            else -> throw DocumentImportException(DocumentImportFailure.INVALID_RESPONSE)
        }
    }

    /** Returns one process-unique nonnegative import job identifier. */
    private fun nextJobId(): Long {
        val jobId = nextImportJobId.getAndUpdate { current ->
            if (current == Long.MAX_VALUE) FIRST_IMPORT_JOB_ID else current + 1L
        }
        check(jobId >= FIRST_IMPORT_JOB_ID) { "import job identifier must be positive" }
        return jobId
    }
}

/** Publishes exactly one terminal callback for the expected import job. */
private class ImportCallback(
    private val jobId: Long,
    private val completion: CompletableDeferred<ImportTerminalStatus>
) : IImportCallback.Stub() {
    private val runningDelivered = AtomicBoolean(false)
    private val terminalDelivered = AtomicBoolean(false)

    override fun onImportStatus(
        jobId: Long,
        state: Int,
        resultCode: Int,
        inputBytes: Long,
        outputBytes: Long,
        sourceFlags: Int,
        sourceSha256: ByteArray?
    ) {
        if (Binder.getCallingUid() == Process.myUid()) {
            rejectResponse()
            return
        }
        if (jobId != this.jobId) {
            rejectResponse()
            return
        }
        val digest = sourceSha256
        if (digest?.size != ImportProtocol.RESULT_SHA_256_BYTE_COUNT) {
            rejectResponse()
            return
        }
        if (state == ImportProtocol.STATE_RUNNING) {
            val validRunningStatus =
                resultCode == ImportProtocol.RESULT_SUCCESS &&
                    inputBytes == 0L &&
                    outputBytes == 0L &&
                    sourceFlags == 0 &&
                    digest.all { byte -> byte == 0.toByte() }
            if (!validRunningStatus || !runningDelivered.compareAndSet(false, true)) {
                rejectResponse()
            }
            return
        }
        if (!runningDelivered.get() || !terminalDelivered.compareAndSet(false, true)) {
            rejectResponse()
            return
        }
        completion.complete(
            ImportTerminalStatus(
                state = state,
                resultCode = resultCode,
                inputBytes = inputBytes,
                outputBytes = outputBytes,
                sourceFlags = sourceFlags,
                sourceSha256 = digest
            )
        )
    }

    /** Rejects one callback that violates the isolated import protocol. */
    private fun rejectResponse() {
        completion.completeExceptionally(
            DocumentImportException(DocumentImportFailure.INVALID_RESPONSE)
        )
    }
}

/** Closes one caller-owned descriptor without retaining provider details. */
private fun closeQuietly(descriptor: ParcelFileDescriptor?) {
    try {
        descriptor?.close()
    } catch (_: IOException) {
        // Descriptor ownership ends even when close reports an error.
    }
}
