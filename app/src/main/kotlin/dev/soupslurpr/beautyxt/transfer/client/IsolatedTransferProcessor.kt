package dev.soupslurpr.beautyxt.transfer.client

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.ipc.SealedInput
import dev.soupslurpr.beautyxt.ipc.TransferredFileDescriptor
import dev.soupslurpr.beautyxt.transfer.ITransferCallback
import dev.soupslurpr.beautyxt.transfer.TransferProtocol
import dev.soupslurpr.beautyxt.transfer.isValidNfcTagLabel
import dev.soupslurpr.beautyxt.transfer.packNfcTagLabel
import java.io.IOException
import java.nio.charset.CharacterCodingException
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
private const val TRANSFER_TIMEOUT_MILLIS = 30_000L
private const val TRANSFER_WATCHDOG_MILLIS = TRANSFER_TIMEOUT_MILLIS + 10_000L
private const val FIRST_TRANSFER_JOB_ID = 1L
private const val REJECTED_INPUT_ERROR = "transfer input unavailable"
private const val REJECTED_OUTPUT_ERROR = "transfer output unavailable"
private val nextTransferJobId = AtomicLong(FIRST_TRANSFER_JOB_ID)

/** Processes bounded QR and NFC data through one isolated Rust worker. */
internal class IsolatedTransferProcessor(context: Context) :
    QrTransferProcessor,
    NfcTransferProcessor {
    private val applicationContext = context.applicationContext

    /** Encodes one exact editor revision into a packed QR module grid. */
    override suspend fun encodeQr(
        snapshot: EditorDocumentSnapshot,
        expectedBytes: Long,
        format: DocumentFormat
    ): QrCodeGrid {
        if (expectedBytes !in
            TransferProtocol.MIN_INPUT_BYTES..TransferProtocol.MAX_QR_TEXT_BYTES
        ) {
            throw TransferException(TransferFailure.TooLarge)
        }
        return withContext(Dispatchers.IO) {
            val request =
                TransferJobRequest(
                    operation = TransferProtocol.OPERATION_ENCODE_QR,
                    expectedInputBytes = expectedBytes,
                    argumentZero = format.toTransferWireValue(),
                    argumentOne = 0L,
                    timeoutMillis = TRANSFER_TIMEOUT_MILLIS
                )
            val input =
                AnonymousTransferInput.fromSnapshot(
                    snapshot = snapshot,
                    expectedBytes = expectedBytes,
                    maxBytes = TransferProtocol.MAX_QR_TEXT_BYTES
                )
            val result = runTransfer(input = input, request = request)
            QrCodeGrid.decode(
                packet = result.bytes,
                expectedDimension = result.status.detailZero
            )
        }
    }

    /** Decodes one exact grayscale frame into validated transfer text. */
    override suspend fun decodeQr(frame: QrLuminanceFrame): ReceivedTransferText =
        withContext(Dispatchers.IO) {
            val request =
                TransferJobRequest(
                    operation = TransferProtocol.OPERATION_DECODE_QR,
                    expectedInputBytes = frame.bytes.size.toLong(),
                    argumentZero = frame.width.toLong(),
                    argumentOne = frame.height.toLong(),
                    timeoutMillis = TRANSFER_TIMEOUT_MILLIS
                )
            val input =
                AnonymousTransferInput.fromBytes(
                    bytes = frame.bytes,
                    maxBytes = TransferProtocol.MAX_QR_FRAME_PIXELS
                )
            decodeReceivedText(runTransfer(input = input, request = request))
        }

    /** Encodes one exact editor revision into an NFC transfer envelope. */
    override suspend fun encodeNfc(
        snapshot: EditorDocumentSnapshot,
        expectedBytes: Long,
        format: DocumentFormat,
        tagLabel: String?
    ): NfcTransferEnvelope {
        require(isValidNfcTagLabel(tagLabel)) { "NFC tag label is invalid" }
        if (expectedBytes !in
            TransferProtocol.MIN_INPUT_BYTES..TransferProtocol.MAX_NFC_TEXT_BYTES
        ) {
            throw TransferException(TransferFailure.TooLarge)
        }
        return withContext(Dispatchers.IO) {
            val request =
                TransferJobRequest(
                    operation = TransferProtocol.OPERATION_ENCODE_NFC,
                    expectedInputBytes = expectedBytes,
                    argumentZero = format.toTransferWireValue(),
                    argumentOne = packNfcTagLabel(tagLabel),
                    timeoutMillis = TRANSFER_TIMEOUT_MILLIS
                )
            val input =
                AnonymousTransferInput.fromSnapshot(
                    snapshot = snapshot,
                    expectedBytes = expectedBytes,
                    maxBytes = TransferProtocol.MAX_NFC_TEXT_BYTES
                )
            val resultBytes = runTransfer(input = input, request = request).bytes
            try {
                NfcTransferEnvelope.fromIsolatedResult(
                    bytes = resultBytes,
                    expectedTextBytes = expectedBytes,
                    tagLabel = tagLabel
                )
            } finally {
                resultBytes.fill(0)
            }
        }
    }

    /** Decodes one complete bounded NDEF message into validated inert text. */
    override suspend fun decodeNfc(message: ByteArray): ReceivedTransferText =
        withContext(Dispatchers.IO) {
            val messageBytes = message.size.toLong()
            if (messageBytes < TransferProtocol.MIN_NFC_MESSAGE_BYTES) {
                throw TransferException(TransferFailure.Unsupported)
            }
            if (messageBytes > TransferProtocol.MAX_NFC_MESSAGE_BYTES) {
                throw TransferException(TransferFailure.TooLarge)
            }
            val request =
                TransferJobRequest(
                    operation = TransferProtocol.OPERATION_DECODE_NFC,
                    expectedInputBytes = messageBytes,
                    argumentZero = 0L,
                    argumentOne = 0L,
                    timeoutMillis = TRANSFER_TIMEOUT_MILLIS
                )
            val input =
                AnonymousTransferInput.fromBytes(
                    bytes = message,
                    maxBytes = TransferProtocol.MAX_NFC_MESSAGE_BYTES
                )
            val result = runTransfer(input = input, request = request)
            try {
                NfcResultPacket.decode(
                    packet = result.bytes,
                    expectedSource = result.status.detailZero,
                    expectedFormat = result.status.detailOne
                )
            } finally {
                result.bytes.fill(0)
            }
        }

    /** Owns input before output preparation or any cancellable worker operation. */
    private suspend fun runTransfer(
        input: SealedInput,
        request: TransferJobRequest
    ): CompletedTransfer = input.use {
        currentCoroutineContext().ensureActive()
        AnonymousTransferOutput.create().use { output ->
            runPreparedTransfer(input, output, request)
        }
    }

    /** Borrows owned buffers for one exact descriptor-only transfer lifecycle. */
    private suspend fun runPreparedTransfer(
        input: SealedInput,
        output: AnonymousTransferOutput,
        request: TransferJobRequest
    ): CompletedTransfer {
        val jobId = nextJobId()
        var inputDescriptor: ParcelFileDescriptor? = null
        var outputDescriptor: ParcelFileDescriptor? = null
        var transferredInput: TransferredFileDescriptor? = null
        var transferredOutput: TransferredFileDescriptor? = null
        var completion: CompletableDeferred<TransferTerminalStatus>? = null
        var accepted = false
        var completed = false
        val binding = IsolatedTransferServiceBinding(applicationContext)
        try {
            binding.bind()
            val service =
                try {
                    withTimeout(SERVICE_BIND_TIMEOUT_MILLIS) {
                        binding.awaitService()
                    }
                } catch (failure: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw TransferException(TransferFailure.ServiceUnavailable, failure)
                }
            inputDescriptor = input.takeReader()
            outputDescriptor = output.takeWriter()
            transferredInput =
                TransferredFileDescriptor.from(checkNotNull(inputDescriptor))
            inputDescriptor = null
            transferredOutput =
                TransferredFileDescriptor.from(checkNotNull(outputDescriptor))
            outputDescriptor = null
            completion = CompletableDeferred()
            binding.attachOperation(completion)
            val callback = TransferCallback(jobId, completion)
            val acceptCode =
                try {
                    service.startTransfer(
                        jobId,
                        request.operation,
                        transferredInput,
                        transferredOutput,
                        request.expectedInputBytes,
                        request.argumentZero,
                        request.argumentOne,
                        request.timeoutMillis,
                        callback
                    )
                } catch (failure: RemoteException) {
                    throw TransferException(TransferFailure.ServiceUnavailable, failure)
                } finally {
                    transferredInput.closeWithError(REJECTED_INPUT_ERROR)
                    transferredOutput.closeWithError(REJECTED_OUTPUT_ERROR)
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
            val terminalStatus =
                try {
                    withTimeout(TRANSFER_WATCHDOG_MILLIS) {
                        completion.await()
                    }
                } catch (failure: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw TransferException(TransferFailure.TimedOut, failure)
                }
            terminalStatus.failureOrNull(request)?.let { failure ->
                throw TransferException(failure)
            }
            currentCoroutineContext().ensureActive()
            val bytes =
                try {
                    output.readCompleted(terminalStatus.outputBytes)
                } catch (failure: TransferException) {
                    throw failure
                } catch (failure: Exception) {
                    throw TransferException(TransferFailure.InvalidResponse, failure)
                }
            completed = true
            return CompletedTransfer(status = terminalStatus, bytes = bytes)
        } catch (cancellation: CancellationException) {
            if (accepted) {
                binding.cancelTransfer(jobId)
            }
            throw cancellation
        } catch (failure: TransferException) {
            if (accepted) {
                binding.cancelTransfer(jobId)
            }
            throw failure
        } catch (failure: Exception) {
            if (accepted) {
                binding.cancelTransfer(jobId)
            }
            throw TransferException(TransferFailure.ProcessingFailed, failure)
        } finally {
            completion?.cancel()
            transferredInput?.closeWithError(REJECTED_INPUT_ERROR)
            transferredOutput?.closeWithError(REJECTED_OUTPUT_ERROR)
            closeDescriptorQuietly(inputDescriptor)
            closeDescriptorQuietly(outputDescriptor)
            if (accepted && !completed) {
                binding.cancelTransfer(jobId)
            }
            binding.close()
        }
    }

    /** Decodes exact output text and its stable format. */
    private fun decodeReceivedText(result: CompletedTransfer): ReceivedTransferText {
        val text =
            try {
                result.bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (failure: CharacterCodingException) {
                throw TransferException(TransferFailure.InvalidResponse, failure)
            }
        return ReceivedTransferText(
            text = text,
            format = shareDocumentFormatFromWire(result.status.detailZero)
        )
    }

    /** Rejects every non-accepted synchronous service result. */
    private fun throwForAcceptCode(acceptCode: Int) {
        when (acceptCode) {
            TransferProtocol.ACCEPT_ACCEPTED -> Unit

            TransferProtocol.ACCEPT_BUSY ->
                throw TransferException(TransferFailure.ServiceBusy)

            TransferProtocol.ACCEPT_INVALID_ARGUMENT ->
                throw TransferException(TransferFailure.InvalidResponse)

            TransferProtocol.ACCEPT_CALLBACK_UNAVAILABLE ->
                throw TransferException(TransferFailure.ServiceUnavailable)

            else -> throw TransferException(TransferFailure.InvalidResponse)
        }
    }

    /** Returns one process-unique positive transfer job identifier. */
    private fun nextJobId(): Long {
        val jobId = nextTransferJobId.getAndUpdate { current ->
            if (current == Long.MAX_VALUE) FIRST_TRANSFER_JOB_ID else current + 1L
        }
        check(jobId >= FIRST_TRANSFER_JOB_ID) { "transfer job identifier must be positive" }
        return jobId
    }
}

/** Publishes exactly one terminal callback for the expected transfer job. */
private class TransferCallback(
    private val jobId: Long,
    private val completion: CompletableDeferred<TransferTerminalStatus>
) : ITransferCallback.Stub() {
    private val runningDelivered = AtomicBoolean(false)
    private val terminalDelivered = AtomicBoolean(false)

    override fun onTransferStatus(
        jobId: Long,
        state: Int,
        resultCode: Int,
        inputBytes: Long,
        outputBytes: Long,
        detailZero: Long,
        detailOne: Long
    ) {
        if (Binder.getCallingUid() == Process.myUid() || jobId != this.jobId) {
            rejectResponse()
            return
        }
        if (state == TransferProtocol.STATE_RUNNING) {
            val validRunningStatus =
                resultCode == TransferProtocol.RESULT_SUCCESS &&
                    inputBytes == 0L &&
                    outputBytes == 0L &&
                    detailZero == 0L &&
                    detailOne == 0L
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
            TransferTerminalStatus(
                state = state,
                resultCode = resultCode,
                inputBytes = inputBytes,
                outputBytes = outputBytes,
                detailZero = detailZero,
                detailOne = detailOne
            )
        )
    }

    /** Rejects one callback that violates the isolated transfer protocol. */
    private fun rejectResponse() {
        completion.completeExceptionally(
            TransferException(TransferFailure.InvalidResponse)
        )
    }
}

/** Holds one completed output and its verified callback metrics. */
private data class CompletedTransfer(val status: TransferTerminalStatus, val bytes: ByteArray)

/** Closes one descriptor without masking the primary transfer outcome. */
private fun closeDescriptorQuietly(descriptor: ParcelFileDescriptor?) {
    try {
        descriptor?.close()
    } catch (_: IOException) {
        // Descriptor ownership ends even when close reports an error.
    }
}
