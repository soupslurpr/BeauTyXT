package dev.soupslurpr.beautyxt.markdown.client

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.illustration.IllustrationLimits
import dev.soupslurpr.beautyxt.illustration.IsolatedDiagramService
import dev.soupslurpr.beautyxt.illustration.IsolatedMathService
import dev.soupslurpr.beautyxt.illustration.RestartingIllustrationWorker
import dev.soupslurpr.beautyxt.illustration.illustrateMarkdown
import dev.soupslurpr.beautyxt.ipc.ReliableSnapshotPipe
import dev.soupslurpr.beautyxt.ipc.TransferredFileDescriptor
import dev.soupslurpr.beautyxt.markdown.IMarkdownCallback
import dev.soupslurpr.beautyxt.markdown.MarkdownPacketDecoder
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import dev.soupslurpr.beautyxt.markdown.MarkdownProtocol
import dev.soupslurpr.beautyxt.markdown.MarkdownProtocolException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val SERVICE_BIND_TIMEOUT_MILLIS = 10_000L
private const val RENDER_TIMEOUT_MILLIS = 300_000L
private const val RENDER_WATCHDOG_MILLIS = RENDER_TIMEOUT_MILLIS + 10_000L
private const val FIRST_RENDER_JOB_ID = 1L
private const val SNAPSHOT_PIPE_ERROR = "snapshot unavailable"
private const val REJECTED_PACKET_ERROR = "packet output unavailable"
private val nextRenderJobId = AtomicLong(FIRST_RENDER_JOB_ID)

/** Renders Markdown through a private isolated process and verifies its packet. */
internal class IsolatedMarkdownRenderer(context: Context) : MarkdownRenderer {
    private val applicationContext = context.applicationContext

    /** Borrows one snapshot and returns a verified render model. */
    override suspend fun render(
        snapshot: EditorDocumentSnapshot,
        expectedBytes: Long
    ): MarkdownPreviewDocument {
        val document = renderPreview(snapshot, expectedBytes)
        return withContext(Dispatchers.IO) {
            RestartingIllustrationWorker(
                applicationContext,
                IsolatedMathService::class.java,
                IllustrationLimits.MAX_MATH_SOURCE_BYTES
            ).use { math ->
                RestartingIllustrationWorker(
                    applicationContext,
                    IsolatedDiagramService::class.java,
                    IllustrationLimits.MAX_DIAGRAM_SOURCE_BYTES
                ).use { diagrams ->
                    illustrateMarkdown(document, math::render, renderDiagram = diagrams::render)
                }
            }
        }
    }

    override suspend fun renderPreview(
        snapshot: EditorDocumentSnapshot,
        expectedBytes: Long
    ): MarkdownPreviewDocument {
        if (expectedBytes !in MarkdownProtocol.MIN_INPUT_BYTES..MarkdownProtocol.MAX_INPUT_BYTES) {
            throw MarkdownRenderException(MarkdownRenderFailure.TooLarge)
        }
        return withContext(Dispatchers.IO) {
            coroutineScope {
                renderOwnedSnapshot(snapshot, expectedBytes)
            }
        }
    }

    /** Owns every capability for one isolated render lifecycle. */
    private suspend fun kotlinx.coroutines.CoroutineScope.renderOwnedSnapshot(
        snapshot: EditorDocumentSnapshot,
        expectedBytes: Long
    ): MarkdownPreviewDocument {
        val jobId = nextJobId()
        var pipe: ReliableSnapshotPipe? = null
        var packetBuffer: AnonymousMarkdownPacket? = null
        var inputDescriptor: ParcelFileDescriptor? = null
        var outputDescriptor: ParcelFileDescriptor? = null
        var transferredInput: TransferredFileDescriptor? = null
        var transferredOutput: TransferredFileDescriptor? = null
        var completion: CompletableDeferred<MarkdownTerminalStatus>? = null
        var producer: Deferred<Long>? = null
        var accepted = false
        var completed = false
        val binding = IsolatedMarkdownServiceBinding(applicationContext)
        try {
            pipe = try {
                ReliableSnapshotPipe.create(RENDER_TIMEOUT_MILLIS)
            } catch (failure: Exception) {
                throw MarkdownRenderException(MarkdownRenderFailure.SnapshotFailed, failure)
            }
            packetBuffer = AnonymousMarkdownPacket.create()
            binding.bind()
            val service =
                try {
                    withTimeout(SERVICE_BIND_TIMEOUT_MILLIS) {
                        binding.awaitService()
                    }
                } catch (failure: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw MarkdownRenderException(
                        MarkdownRenderFailure.ServiceUnavailable,
                        failure
                    )
                }
            inputDescriptor = pipe.takeReader()
            outputDescriptor = packetBuffer.takeWriter()
            transferredInput =
                TransferredFileDescriptor.from(checkNotNull(inputDescriptor))
            inputDescriptor = null
            transferredOutput =
                TransferredFileDescriptor.from(checkNotNull(outputDescriptor))
            outputDescriptor = null
            completion = CompletableDeferred()
            binding.attachOperation(completion)
            val callback = MarkdownCallback(jobId, completion)
            val acceptCode =
                try {
                    service.startRender(
                        jobId,
                        transferredInput,
                        transferredOutput,
                        expectedBytes,
                        MarkdownProtocol.MAX_PACKET_BYTES,
                        RENDER_TIMEOUT_MILLIS,
                        callback
                    )
                } catch (failure: RemoteException) {
                    throw MarkdownRenderException(
                        MarkdownRenderFailure.ServiceUnavailable,
                        failure
                    )
                } finally {
                    transferredInput.closeWithError(SNAPSHOT_PIPE_ERROR)
                    transferredOutput.closeWithError(REJECTED_PACKET_ERROR)
                    transferredInput = null
                    transferredOutput = null
                    closeDescriptorQuietly(inputDescriptor)
                    closeDescriptorQuietly(outputDescriptor)
                    inputDescriptor = null
                    outputDescriptor = null
                }
            throwForAcceptCode(acceptCode)
            accepted = true
            currentCoroutineContext().ensureActive()

            val activePipe = pipe
            producer = async(Dispatchers.IO) {
                try {
                    activePipe.writeSnapshot(snapshot)
                } catch (failure: Exception) {
                    throw MarkdownRenderException(MarkdownRenderFailure.SnapshotFailed, failure)
                } catch (failure: LinkageError) {
                    throw MarkdownRenderException(MarkdownRenderFailure.SnapshotFailed, failure)
                }
            }
            val terminalStatus =
                try {
                    withTimeout(RENDER_WATCHDOG_MILLIS) {
                        completion.await()
                    }
                } catch (failure: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw MarkdownRenderException(MarkdownRenderFailure.TimedOut, failure)
                }
            terminalStatus.failureOrNull(expectedBytes)?.let { failure ->
                throw MarkdownRenderException(failure)
            }
            val producedBytes = producer.await()
            if (producedBytes != expectedBytes) {
                throw MarkdownRenderException(MarkdownRenderFailure.InvalidResponse)
            }
            currentCoroutineContext().ensureActive()
            val packet =
                try {
                    packetBuffer.readCompletedPacket(terminalStatus.packetBytes)
                } catch (failure: Exception) {
                    throw MarkdownRenderException(
                        MarkdownRenderFailure.InvalidResponse,
                        failure
                    )
                }
            val document =
                try {
                    MarkdownPacketDecoder.decode(packet)
                } catch (failure: MarkdownProtocolException) {
                    throw MarkdownRenderException(
                        MarkdownRenderFailure.InvalidResponse,
                        failure
                    )
                }
            validateDecodedDocument(document, terminalStatus, expectedBytes)
            completed = true
            return document
        } catch (failure: CancellationException) {
            if (accepted) {
                binding.cancelRender(jobId)
            }
            pipe?.failWriter()
            producer?.cancel()
            throw failure
        } catch (failure: MarkdownRenderException) {
            if (accepted) {
                binding.cancelRender(jobId)
            }
            pipe?.failWriter()
            producer?.cancel()
            throw failure
        } catch (failure: Exception) {
            if (accepted) {
                binding.cancelRender(jobId)
            }
            pipe?.failWriter()
            producer?.cancel()
            throw MarkdownRenderException(MarkdownRenderFailure.RenderFailed, failure)
        } finally {
            completion?.cancel()
            transferredInput?.closeWithError(SNAPSHOT_PIPE_ERROR)
            transferredOutput?.closeWithError(REJECTED_PACKET_ERROR)
            closeDescriptorQuietly(inputDescriptor)
            closeDescriptorQuietly(outputDescriptor)
            if (accepted && !completed) {
                binding.cancelRender(jobId)
            }
            if (!completed) {
                pipe?.failWriter()
            }
            binding.close()
            closeResourceQuietly(pipe)
            closeResourceQuietly(packetBuffer)
        }
    }

    /** Validates decoded packet metrics against the isolated callback. */
    private fun validateDecodedDocument(
        document: MarkdownPreviewDocument,
        status: MarkdownTerminalStatus,
        expectedBytes: Long
    ) {
        if (
            document.inputByteLength != expectedBytes ||
            document.blocks.size.toLong() != status.blockCount ||
            document.spanCount.toLong() != status.spanCount ||
            document.containsRawHtml !=
            (status.documentFlags and MarkdownProtocol.DOCUMENT_FLAG_RAW_HTML.toLong() != 0L)
        ) {
            throw MarkdownRenderException(MarkdownRenderFailure.InvalidResponse)
        }
    }

    /** Rejects every non-accepted synchronous service result. */
    private fun throwForAcceptCode(acceptCode: Int) {
        when (acceptCode) {
            MarkdownProtocol.ACCEPT_ACCEPTED -> Unit

            MarkdownProtocol.ACCEPT_BUSY ->
                throw MarkdownRenderException(MarkdownRenderFailure.ServiceBusy)

            MarkdownProtocol.ACCEPT_INVALID_ARGUMENT ->
                throw MarkdownRenderException(MarkdownRenderFailure.InvalidResponse)

            MarkdownProtocol.ACCEPT_CALLBACK_UNAVAILABLE ->
                throw MarkdownRenderException(MarkdownRenderFailure.ServiceUnavailable)

            else -> throw MarkdownRenderException(MarkdownRenderFailure.InvalidResponse)
        }
    }

    /** Returns one process-unique positive render job identifier. */
    private fun nextJobId(): Long {
        val jobId = nextRenderJobId.getAndUpdate { current ->
            if (current == Long.MAX_VALUE) FIRST_RENDER_JOB_ID else current + 1L
        }
        check(jobId >= FIRST_RENDER_JOB_ID) { "Markdown job identifier must be positive" }
        return jobId
    }
}

/** Publishes exactly one terminal callback for the expected render job. */
private class MarkdownCallback(
    private val jobId: Long,
    private val completion: CompletableDeferred<MarkdownTerminalStatus>
) : IMarkdownCallback.Stub() {
    private val runningDelivered = AtomicBoolean(false)
    private val terminalDelivered = AtomicBoolean(false)

    override fun onMarkdownStatus(
        jobId: Long,
        state: Int,
        resultCode: Int,
        inputBytes: Long,
        packetBytes: Long,
        blockCount: Long,
        spanCount: Long,
        documentFlags: Long
    ) {
        if (Binder.getCallingUid() == Process.myUid() || jobId != this.jobId) {
            rejectResponse()
            return
        }
        if (state == MarkdownProtocol.STATE_RUNNING) {
            val validRunningStatus =
                resultCode == MarkdownProtocol.RESULT_SUCCESS &&
                    inputBytes == 0L &&
                    packetBytes == 0L &&
                    blockCount == 0L &&
                    spanCount == 0L &&
                    documentFlags == 0L
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
            MarkdownTerminalStatus(
                state = state,
                resultCode = resultCode,
                inputBytes = inputBytes,
                packetBytes = packetBytes,
                blockCount = blockCount,
                spanCount = spanCount,
                documentFlags = documentFlags
            )
        )
    }

    /** Rejects one callback that violates the isolated render protocol. */
    private fun rejectResponse() {
        completion.completeExceptionally(
            MarkdownRenderException(MarkdownRenderFailure.InvalidResponse)
        )
    }
}

/** Closes one parcel descriptor without masking an active operation. */
private fun closeDescriptorQuietly(descriptor: ParcelFileDescriptor?) {
    try {
        descriptor?.close()
    } catch (_: Exception) {
        // Descriptor ownership ends even when close reports an error.
    }
}

/** Closes one resource without masking an active operation. */
private fun closeResourceQuietly(resource: AutoCloseable?) {
    try {
        resource?.close()
    } catch (_: Exception) {
        // The primary render outcome remains authoritative.
    }
}
