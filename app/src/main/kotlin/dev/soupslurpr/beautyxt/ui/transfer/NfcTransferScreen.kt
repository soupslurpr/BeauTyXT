package dev.soupslurpr.beautyxt.ui.transfer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.transfer.TransferProtocol
import dev.soupslurpr.beautyxt.transfer.client.NfcTransferProcessor
import dev.soupslurpr.beautyxt.transfer.client.ReceivedTransferText
import dev.soupslurpr.beautyxt.transfer.client.TransferException
import dev.soupslurpr.beautyxt.transfer.client.TransferFailure
import dev.soupslurpr.beautyxt.transfer.reportedNfcTagId
import dev.soupslurpr.beautyxt.ui.PredictiveBackMotionHandler
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.asString
import dev.soupslurpr.beautyxt.ui.editor.NfcWriteStatus
import dev.soupslurpr.beautyxt.ui.editor.formatTransferByteCount
import dev.soupslurpr.beautyxt.ui.predictiveBackMotion
import dev.soupslurpr.beautyxt.ui.rememberPredictiveBackMotionState
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal const val NFC_TRANSFER_MIME_TYPE =
    "application/vnd.dev.soupslurpr.beautyxt.transfer"
private val NfcHorizontalPadding = 24.dp
private val NfcVerticalPadding = 16.dp
private val NfcContentSpacing = 16.dp
private val NfcCompactSpacing = 8.dp
private val NfcContentMaxWidth = 560.dp
private val NfcSignalSize = 80.dp
private val NfcSignalShape = RoundedCornerShape(28.dp)
private val NfcProgressSize = 28.dp
private const val NFC_READER_FLAGS =
    NfcAdapter.FLAG_READER_NFC_A or
        NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_NFC_F or
        NfcAdapter.FLAG_READER_NFC_V or
        NfcAdapter.FLAG_READER_NFC_BARCODE
private val NFC_DISABLED_MESSAGE = UiText.Resource(R.string.nfc_disabled_message)
private val NFC_READ_WAITING_MESSAGE = UiText.Resource(R.string.nfc_read_waiting_message)
private val NFC_READING_MESSAGE = UiText.Resource(R.string.nfc_reading_message)
private val NFC_UNSUPPORTED_MESSAGE = UiText.Resource(R.string.nfc_unsupported_message)
private val NFC_INVALID_MESSAGE = UiText.Resource(R.string.nfc_invalid_message)
private val NFC_AMBIGUOUS_MESSAGE = UiText.Resource(R.string.nfc_ambiguous_message)
private val NFC_TOO_LARGE_MESSAGE = UiText.Resource(R.string.nfc_too_large_message)
private val NFC_READ_FAILURE_MESSAGE = UiText.Resource(R.string.nfc_read_failure_message)
private val NFC_DECODE_FAILURE_MESSAGE = UiText.Resource(R.string.nfc_decode_failure_message)
private val NFC_SERVICE_MESSAGE = UiText.Resource(R.string.nfc_service_message)
private val NFC_WRITE_READY_MESSAGE = UiText.Resource(R.string.nfc_write_ready_message)
private val NFC_WRITING_MESSAGE = UiText.Resource(R.string.nfc_writing_message)
private val NFC_NOT_FORMATTED_MESSAGE = UiText.Resource(R.string.nfc_not_formatted_message)
private val NFC_READ_ONLY_MESSAGE = UiText.Resource(R.string.nfc_read_only_message)
private val NFC_WRITE_FAILURE_MESSAGE = UiText.Resource(R.string.nfc_write_failure_message)

/** Displays one foreground-only reader for bounded supported NFC text. */
@Composable
internal fun NfcReadScreen(
    adapter: NfcAdapter,
    processor: NfcTransferProcessor,
    onReceived: (ReceivedTransferText) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val predictiveBackState = rememberPredictiveBackMotionState()
    PredictiveBackMotionHandler(state = predictiveBackState, onBack = onClose)
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var isNfcEnabled by remember(adapter) { mutableStateOf(adapter.isEnabled) }
    var status by remember { mutableStateOf<NfcScreenStatus>(NfcScreenStatus.Waiting) }
    val currentOnReceived by rememberUpdatedState(onReceived)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isNfcEnabled = adapter.isEnabled
        if (isNfcEnabled && status != NfcScreenStatus.Waiting) {
            status = NfcScreenStatus.Waiting
        }
    }
    LifecycleResumeEffect(activity, processor, isNfcEnabled) {
        if (activity == null || !isNfcEnabled) {
            onPauseOrDispose {}
        } else {
            val controller =
                NfcReadController(
                    processor = processor,
                    onWorking = { status = NfcScreenStatus.Working },
                    onFailure = { message -> status = NfcScreenStatus.Failed(message) },
                    onReceived = { currentOnReceived(it) }
                )
            try {
                adapter.enableReaderMode(activity, controller, NFC_READER_FLAGS, null)
            } catch (_: RuntimeException) {
                status = NfcScreenStatus.Failed(NFC_READ_FAILURE_MESSAGE)
            }
            onPauseOrDispose {
                controller.close()
                if (status == NfcScreenStatus.Working) {
                    status = NfcScreenStatus.Waiting
                }
                try {
                    adapter.disableReaderMode(activity)
                } catch (_: RuntimeException) {}
            }
        }
    }
    NfcTransferScaffold(
        title = stringResource(R.string.nfc_read_title),
        heading = if (isNfcEnabled) {
            stringResource(
                R.string.nfc_ready_receive
            )
        } else {
            stringResource(R.string.nfc_off)
        },
        message =
            if (isNfcEnabled) {
                status.message(
                    waitingMessage = NFC_READ_WAITING_MESSAGE,
                    workingMessage = NFC_READING_MESSAGE
                ).asString()
            } else {
                NFC_DISABLED_MESSAGE.asString()
            },
        isWorking = isNfcEnabled && status == NfcScreenStatus.Working,
        disclosure =
            stringResource(R.string.nfc_read_disclosure),
        onOpenNfcSettings = if (isNfcEnabled) null else ({ context.openNfcSettings() }),
        onClose = onClose,
        modifier = modifier.predictiveBackMotion(predictiveBackState)
    )
}

/** Displays one explicitly armed foreground writer for a prepared NFC envelope. */
@Composable
internal fun NfcWriteScreen(
    adapter: NfcAdapter,
    ready: NfcWriteStatus.Ready,
    onWritten: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val predictiveBackState = rememberPredictiveBackMotionState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var isNfcEnabled by remember(adapter) { mutableStateOf(adapter.isEnabled) }
    var state by retain(ready.generation) { mutableStateOf<NfcWriteState>(NfcWriteState.Ready) }
    val isArmed = state.isArmed
    val isWorking = state == NfcWriteState.Writing

    /** Checks live state even when an action arrives before recomposition. */
    fun closeIfIdle() {
        if (state != NfcWriteState.Writing) {
            onClose()
        }
    }

    PredictiveBackMotionHandler(
        state = predictiveBackState,
        enabled = !isWorking,
        onBack = ::closeIfIdle
    )
    BackHandler(enabled = isWorking) {}
    val currentOnWritten by rememberUpdatedState(onWritten)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isNfcEnabled = adapter.isEnabled
        if (!isNfcEnabled) {
            state = state.paused()
        }
    }
    LifecycleResumeEffect(activity, ready.generation, isNfcEnabled && isArmed) {
        if (activity == null || !isNfcEnabled || !isArmed) {
            onPauseOrDispose {}
        } else {
            val controller =
                NfcWriteController(
                    ready = ready,
                    onWorking = { state = NfcWriteState.Writing },
                    onFailure = { message -> state = NfcWriteState.Failed(message) },
                    onWritten = { currentOnWritten(ready.generation) }
                )
            try {
                adapter.enableReaderMode(activity, controller, NFC_READER_FLAGS, null)
            } catch (_: RuntimeException) {
                state = NfcWriteState.Failed(NFC_WRITE_FAILURE_MESSAGE)
            }
            onPauseOrDispose {
                controller.close()
                state = state.paused()
                try {
                    adapter.disableReaderMode(activity)
                } catch (_: RuntimeException) {}
            }
        }
    }
    val formatLabel =
        when (ready.format) {
            DocumentFormat.PlainText -> stringResource(R.string.format_plain_text)
            DocumentFormat.Markdown -> stringResource(R.string.format_markdown)
        }
    val message = if (!isNfcEnabled) {
        NFC_DISABLED_MESSAGE
    } else {
        when (val currentState = state) {
            NfcWriteState.Ready ->
                UiText.Resource(R.string.nfc_write_confirmation)

            NfcWriteState.Armed -> NFC_WRITE_READY_MESSAGE

            NfcWriteState.Writing -> NFC_WRITING_MESSAGE

            is NfcWriteState.Failed -> currentState.message
        }
    }
    val ndefMessageBytes = nfcTransferMessageByteCount(ready.envelope.byteCount)
    NfcTransferScaffold(
        title = stringResource(R.string.nfc_write_title),
        heading =
            when {
                !isNfcEnabled -> stringResource(R.string.nfc_off)
                isArmed -> stringResource(R.string.nfc_bring_tag)
                else -> stringResource(R.string.nfc_confirm_write)
            },
        message = message.asString(),
        isWorking = isNfcEnabled && isWorking,
        disclosure =
            ready.tagLabel?.let { tagLabel ->
                stringResource(R.string.nfc_label_disclosure, tagLabel) + " "
            }.orEmpty() +
                stringResource(
                    R.string.nfc_write_disclosure,
                    formatLabel,
                    formatTransferByteCount(ready.textBytes),
                    formatTransferByteCount(TransferProtocol.MAX_NFC_TEXT_BYTES),
                    formatTransferByteCount(ndefMessageBytes)
                ),
        onOpenNfcSettings = if (isNfcEnabled) null else ({ context.openNfcSettings() }),
        primaryAction =
            if (isNfcEnabled && !isArmed) {
                NfcAction(label = stringResource(R.string.nfc_start_writing)) {
                    if (!state.isArmed) {
                        state = NfcWriteState.Armed
                    }
                }
            } else {
                null
            },
        secondaryAction =
            if (isArmed) {
                NfcAction(
                    label = stringResource(R.string.nfc_stop_waiting),
                    enabled = !isWorking
                ) {
                    if (state != NfcWriteState.Writing) {
                        state = NfcWriteState.Ready
                    }
                }
            } else {
                null
            },
        closeEnabled = !isWorking,
        onClose = ::closeIfIdle,
        modifier = modifier.predictiveBackMotion(predictiveBackState)
    )
}

/** Displays shared Material 3 framing for a foreground NFC operation. */
@Composable
private fun NfcTransferScaffold(
    title: String,
    heading: String,
    message: String,
    isWorking: Boolean,
    disclosure: String,
    onOpenNfcSettings: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    primaryAction: NfcAction? = null,
    secondaryAction: NfcAction? = null,
    closeEnabled: Boolean = true
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                    )
        ) {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onClose, enabled = closeEnabled) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.nfc_close)
                        )
                    }
                },
                windowInsets =
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                    )
            )
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                        )
                        .verticalScroll(rememberScrollState())
                        .padding(NfcHorizontalPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    modifier = Modifier.size(NfcSignalSize),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = NfcSignalShape
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isWorking) {
                            CircularProgressIndicator(modifier = Modifier.size(NfcProgressSize))
                        } else {
                            Text(
                                text = stringResource(R.string.nfc_symbol),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(NfcContentSpacing))
                Column(
                    modifier =
                        Modifier
                            .widthIn(max = NfcContentMaxWidth)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(NfcCompactSpacing)
                ) {
                    Text(
                        text = heading,
                        modifier = Modifier.semantics { heading() },
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = disclosure,
                        modifier = Modifier.padding(vertical = NfcVerticalPadding),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    onOpenNfcSettings?.let { openSettings ->
                        FilledTonalButton(
                            onClick = openSettings,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.nfc_open_settings))
                        }
                    }
                    primaryAction?.let { action ->
                        Button(
                            onClick = action.onClick,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = action.enabled
                        ) {
                            Text(action.label)
                        }
                    }
                    secondaryAction?.let { action ->
                        OutlinedButton(
                            onClick = action.onClick,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = action.enabled
                        ) {
                            Text(action.label)
                        }
                    }
                    OutlinedButton(
                        onClick = onClose,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = closeEnabled
                    ) {
                        Text(stringResource(R.string.nfc_cancel))
                    }
                }
            }
        }
    }
}

/** Stores one optional NFC screen action. */
private data class NfcAction(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit
) {
    init {
        require(label.isNotBlank()) { "NFC action label must not be blank" }
    }
}

/** Describes one foreground NFC screen's retryable state. */
private sealed interface NfcScreenStatus {
    /** Indicates that the foreground reader is waiting for a tag. */
    data object Waiting : NfcScreenStatus

    /** Indicates that one discovered tag is being handled. */
    data object Working : NfcScreenStatus

    /** Contains one sanitized retryable tag failure. */
    data class Failed(val message: UiText) : NfcScreenStatus

    /** Returns the visible message for this foreground operation state. */
    fun message(waitingMessage: UiText, workingMessage: UiText): UiText = when (this) {
        Waiting -> waitingMessage
        Working -> workingMessage
        is Failed -> message
    }
}

/** Reads and privately decodes at most one discovered NFC tag at a time. */
private class NfcReadController(
    private val processor: NfcTransferProcessor,
    private val onWorking: () -> Unit,
    private val onFailure: (UiText) -> Unit,
    private val onReceived: (ReceivedTransferText) -> Unit
) : NfcAdapter.ReaderCallback,
    AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)
    private val working = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Reads and verifies one newly discovered tag. */
    override fun onTagDiscovered(tag: Tag) {
        if (closed.get() || completed.get() || !working.compareAndSet(false, true)) {
            return
        }
        scope.launch {
            onWorking()
            try {
                val reportedTagId =
                    try {
                        tag.id?.let(::reportedNfcTagId)
                    } catch (_: RuntimeException) {
                        null
                    }
                val message = readNfcMessage(tag)
                val transfer =
                    try {
                        processor.decodeNfc(message)
                    } finally {
                        message.fill(0)
                    }
                if (!closed.get() && completed.compareAndSet(false, true)) {
                    onReceived(
                        transfer.copy(
                            nfcMetadata =
                                transfer.nfcMetadata?.copy(reportedTagId = reportedTagId)
                        )
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: NfcTagException) {
                if (!closed.get()) {
                    onFailure(nfcReadFailureMessage(failure.failure))
                }
            } catch (failure: TransferException) {
                if (!closed.get()) {
                    onFailure(transferReadFailureMessage(failure.failure))
                }
            } catch (_: Exception) {
                if (!closed.get()) {
                    onFailure(NFC_READ_FAILURE_MESSAGE)
                }
            } catch (_: LinkageError) {
                if (!closed.get()) {
                    onFailure(NFC_SERVICE_MESSAGE)
                }
            } finally {
                working.set(false)
            }
        }
    }

    /** Stops accepting tags and cancels in-flight private decoding. */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            scope.cancel()
        }
    }
}

/** Writes and verifies at most one discovered NFC tag at a time. */
private class NfcWriteController(
    ready: NfcWriteStatus.Ready,
    private val onWorking: () -> Unit,
    private val onFailure: (UiText) -> Unit,
    private val onWritten: () -> Unit
) : NfcAdapter.ReaderCallback,
    AutoCloseable {
    private val message =
        NdefMessage(
            arrayOf(
                NdefRecord.createMime(
                    NFC_TRANSFER_MIME_TYPE,
                    ready.envelope.copyBytes()
                )
            )
        )
    private val expectedMessage = message.toByteArray()
    private val closed = AtomicBoolean(false)
    // Consent permits one attempt, including an unsuccessful one. Do not wait for
    // recomposition to dispose this controller before rejecting another tag.
    private val acceptedTag = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        check(expectedMessage.size.toLong() <= TransferProtocol.MAX_NFC_MESSAGE_BYTES) {
            "prepared NFC message exceeds its complete size limit"
        }
    }

    /** Writes and verifies one newly discovered tag. */
    override fun onTagDiscovered(tag: Tag) {
        if (closed.get() || !acceptedTag.compareAndSet(false, true)) {
            return
        }
        scope.launch {
            onWorking()
            try {
                writeAndVerifyNfcMessage(
                    tag = tag,
                    message = message,
                    expectedMessage = expectedMessage
                )
                if (!closed.get()) {
                    onWritten()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: NfcTagException) {
                if (!closed.get()) {
                    onFailure(nfcWriteFailureMessage(failure.failure))
                }
            } catch (_: Exception) {
                if (!closed.get()) {
                    onFailure(NFC_WRITE_FAILURE_MESSAGE)
                }
            } catch (_: LinkageError) {
                if (!closed.get()) {
                    onFailure(NFC_SERVICE_MESSAGE)
                }
            }
        }
    }

    /** Stops accepting tags and cancels in-flight writes. */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            scope.cancel()
        }
    }
}

/** Identifies one sanitized local NFC tag failure. */
private sealed interface NfcTagFailure {
    data object Unsupported : NfcTagFailure
    data object NotFormatted : NfcTagFailure
    data object ReadOnly : NfcTagFailure
    data object MessageTooLarge : NfcTagFailure
    data class TooSmall(val requiredBytes: Int, val capacityBytes: Int) : NfcTagFailure
    data object InputOutput : NfcTagFailure
    data object Verification : NfcTagFailure
}

/** Reports one local NFC tag failure without retaining tag content. */
private class NfcTagException(val failure: NfcTagFailure, cause: Throwable? = null) :
    Exception(failure::class.simpleName, cause)

/** Reads one complete serialized NDEF message without interpreting application records. */
private suspend fun readNfcMessage(tag: Tag): ByteArray {
    val ndef = Ndef.get(tag) ?: throw NfcTagException(NfcTagFailure.Unsupported)
    return try {
        runNfcIo(closeConnection = ndef::close) { context ->
            ndef.connect()
            context.ensureActive()
            val message = ndef.ndefMessage ?: throw NfcTagException(NfcTagFailure.Unsupported)
            context.ensureActive()
            boundedNfcMessageBytes(message)
        }
    } catch (failure: NfcTagException) {
        throw failure
    } catch (failure: IOException) {
        throw NfcTagException(NfcTagFailure.InputOutput, failure)
    }
}

/** Enforces the complete-message bound before allocating a serialized copy. */
private fun boundedNfcMessageBytes(message: NdefMessage): ByteArray {
    val messageBytes = message.byteArrayLength.toLong()
    if (messageBytes < TransferProtocol.MIN_NFC_MESSAGE_BYTES) {
        throw NfcTagException(NfcTagFailure.Unsupported)
    }
    if (messageBytes > TransferProtocol.MAX_NFC_MESSAGE_BYTES) {
        throw NfcTagException(NfcTagFailure.MessageTooLarge)
    }
    val bytes = message.toByteArray()
    if (bytes.size.toLong() != messageBytes) {
        bytes.fill(0)
        throw NfcTagException(NfcTagFailure.Unsupported)
    }
    return bytes
}

/** Writes one NDEF message and reads back its exact serialized representation. */
private suspend fun writeAndVerifyNfcMessage(
    tag: Tag,
    message: NdefMessage,
    expectedMessage: ByteArray
) {
    val ndef = Ndef.get(tag) ?: throw NfcTagException(NfcTagFailure.NotFormatted)
    try {
        runNfcIo(closeConnection = ndef::close) { context ->
            ndef.connect()
            context.ensureActive()
            if (!ndef.isWritable) {
                throw NfcTagException(NfcTagFailure.ReadOnly)
            }
            if (ndef.maxSize < expectedMessage.size) {
                throw NfcTagException(
                    NfcTagFailure.TooSmall(
                        requiredBytes = expectedMessage.size,
                        capacityBytes = ndef.maxSize
                    )
                )
            }
            context.ensureActive()
            ndef.writeNdefMessage(message)
            context.ensureActive()
            val writtenMessage = ndef.ndefMessage
            context.ensureActive()
            if (writtenMessage == null || writtenMessage.byteArrayLength != expectedMessage.size) {
                throw NfcTagException(NfcTagFailure.Verification)
            }
            val writtenBytes = writtenMessage.toByteArray()
            try {
                if (!writtenBytes.contentEquals(expectedMessage)) {
                    throw NfcTagException(NfcTagFailure.Verification)
                }
            } finally {
                writtenBytes.fill(0)
            }
        }
    } catch (failure: NfcTagException) {
        throw failure
    } catch (failure: IOException) {
        throw NfcTagException(NfcTagFailure.InputOutput, failure)
    }
}

/** Returns one sanitized read message for a local NFC failure. */
private fun nfcReadFailureMessage(failure: NfcTagFailure): UiText = when (failure) {
    NfcTagFailure.Unsupported,
    NfcTagFailure.NotFormatted,
    NfcTagFailure.ReadOnly,
    is NfcTagFailure.TooSmall -> NFC_UNSUPPORTED_MESSAGE

    NfcTagFailure.MessageTooLarge -> NFC_TOO_LARGE_MESSAGE

    NfcTagFailure.InputOutput,
    NfcTagFailure.Verification -> NFC_READ_FAILURE_MESSAGE
}

/** Returns one sanitized read message for an isolated transfer failure. */
private fun transferReadFailureMessage(failure: TransferFailure): UiText = when (failure) {
    TransferFailure.ServiceUnavailable,
    TransferFailure.ServiceBusy,
    TransferFailure.TimedOut -> NFC_SERVICE_MESSAGE

    TransferFailure.TooLarge -> NFC_TOO_LARGE_MESSAGE

    TransferFailure.InvalidNdef,
    TransferFailure.InvalidUtf8 -> NFC_INVALID_MESSAGE

    TransferFailure.AmbiguousNdef -> NFC_AMBIGUOUS_MESSAGE

    TransferFailure.Unsupported -> NFC_UNSUPPORTED_MESSAGE

    TransferFailure.NoQrCode,
    TransferFailure.AmbiguousQr,
    TransferFailure.SnapshotFailed,
    TransferFailure.InvalidResponse,
    TransferFailure.ProcessingFailed -> NFC_DECODE_FAILURE_MESSAGE
}

/** Returns one sanitized write message for a local NFC failure. */
private fun nfcWriteFailureMessage(failure: NfcTagFailure): UiText = when (failure) {
    NfcTagFailure.NotFormatted,
    NfcTagFailure.Unsupported -> NFC_NOT_FORMATTED_MESSAGE

    NfcTagFailure.ReadOnly -> NFC_READ_ONLY_MESSAGE

    NfcTagFailure.MessageTooLarge -> NFC_WRITE_FAILURE_MESSAGE

    is NfcTagFailure.TooSmall ->
        UiText.Resource(
            R.string.nfc_tag_too_small,
            listOf(
                formatTransferByteCount(failure.capacityBytes.toLong()),
                formatTransferByteCount(failure.requiredBytes.toLong())
            )
        )

    NfcTagFailure.InputOutput,
    NfcTagFailure.Verification -> NFC_WRITE_FAILURE_MESSAGE
}

/** Returns the exact serialized byte count of one custom MIME NDEF message. */
private fun nfcTransferMessageByteCount(payloadBytes: Int): Long {
    val payloadLengthBytes = if (payloadBytes < 256) 1 else Int.SIZE_BYTES
    return Math.addExact(
        Math.addExact(2L, payloadLengthBytes.toLong()),
        Math.addExact(NFC_TRANSFER_MIME_TYPE.length.toLong(), payloadBytes.toLong())
    )
}

/** Finds the activity required for Android foreground reader mode. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Opens Android's NFC settings without retaining transfer state outside memory. */
private fun Context.openNfcSettings() {
    try {
        startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
    } catch (_: RuntimeException) {}
}
