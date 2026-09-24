@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.soupslurpr.beautyxt.ui.transfer

import android.annotation.SuppressLint
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.transfer.TransferProtocol
import dev.soupslurpr.beautyxt.transfer.client.QrLuminanceFrame
import dev.soupslurpr.beautyxt.transfer.client.QrTransferProcessor
import dev.soupslurpr.beautyxt.transfer.client.ReceivedTransferText
import dev.soupslurpr.beautyxt.transfer.client.TransferException
import dev.soupslurpr.beautyxt.transfer.client.TransferFailure
import dev.soupslurpr.beautyxt.ui.PredictiveBackMotionHandler
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.asString
import dev.soupslurpr.beautyxt.ui.designsystem.ShortLoadingIndicator
import dev.soupslurpr.beautyxt.ui.predictiveBackMotion
import dev.soupslurpr.beautyxt.ui.rememberPredictiveBackMotionState
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private val ScannerPermissionMaxWidth = 520.dp
private val ScannerHorizontalPadding = 20.dp
private val ScannerVerticalPadding = 16.dp
private val ScannerSpacing = 12.dp
private val ScannerGuideSize = 264.dp
private val ScannerGuideStroke = 3.dp
private val ScannerLoadingSize = 48.dp
private val TargetAnalysisResolution = Size(1280, 960)
private val CAMERA_UNAVAILABLE_MESSAGE = UiText.Resource(
    R.string.scanner_camera_unavailable_message
)
private val CAMERA_FRAME_MESSAGE = UiText.Resource(R.string.scanner_camera_frame_message)
private val QR_UNSUPPORTED_MESSAGE = UiText.Resource(R.string.scanner_qr_unsupported_message)
private val QR_AMBIGUOUS_MESSAGE = UiText.Resource(R.string.scanner_qr_ambiguous_message)
private val QR_TIMEOUT_MESSAGE = UiText.Resource(R.string.scanner_qr_timeout_message)
private val QR_SERVICE_MESSAGE = UiText.Resource(R.string.scanner_qr_service_message)
private val QR_READ_FAILURE_MESSAGE = UiText.Resource(R.string.scanner_qr_read_failure_message)

/** Displays one foreground-only camera scanner for bounded BeauTyXT QR transfers. */
@Composable
internal fun QrScannerScreen(
    processor: QrTransferProcessor,
    hasCameraPermission: Boolean,
    onRequestCameraPermission: () -> Unit,
    onReceived: (ReceivedTransferText) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val predictiveBackState = rememberPredictiveBackMotionState()
    PredictiveBackMotionHandler(state = predictiveBackState, onBack = onClose)
    if (!hasCameraPermission) {
        QrCameraPermissionScreen(
            onRequestPermission = onRequestCameraPermission,
            onClose = onClose,
            modifier = modifier.predictiveBackMotion(predictiveBackState)
        )
        return
    }
    var scannerMessage by remember { mutableStateOf<UiText?>(null) }
    var isCameraStarting by remember { mutableStateOf(true) }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .predictiveBackMotion(predictiveBackState)
                .background(Color.Black)
    ) {
        QrCameraPreview(
            processor = processor,
            onCameraReady = { isCameraStarting = false },
            onFailure = { message ->
                isCameraStarting = false
                scannerMessage = message
            },
            onReceived = onReceived,
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer()
                    .zIndex(1f)
        ) {
            TopAppBar(
                title = { Text(stringResource(R.string.scanner_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.scanner_close)
                        )
                    }
                },
                windowInsets =
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                    )
            )
            BoxWithConstraints(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                        )
                        .padding(horizontal = ScannerHorizontalPadding),
                contentAlignment = Alignment.Center
            ) {
                val guideSize = minOf(ScannerGuideSize, maxWidth, maxHeight)
                if (!isCameraStarting) {
                    Surface(
                        modifier = Modifier.size(guideSize),
                        color = Color.Transparent,
                        shape = RoundedCornerShape(guideSize / 6),
                        border = BorderStroke(ScannerGuideStroke, Color.White)
                    ) {}
                }
            }
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                            )
                        )
                        .padding(
                            horizontal = ScannerHorizontalPadding,
                            vertical = ScannerVerticalPadding
                        ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ScannerSpacing)
            ) {
                if (isCameraStarting) {
                    ShortLoadingIndicator(
                        modifier = Modifier.size(ScannerLoadingSize).clearAndSetSemantics {},
                        color = Color.White
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(
                        modifier = Modifier.padding(ScannerVerticalPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(ScannerSpacing)
                    ) {
                        Text(
                            text =
                                scannerMessage?.asString()
                                    ?: stringResource(
                                        if (isCameraStarting) {
                                            R.string.scanner_starting_camera
                                        } else {
                                            R.string.scanner_fit_code
                                        }
                                    ),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                        Text(
                            text = stringResource(R.string.scanner_frames),
                            color =
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

/** Explains the foreground camera permission before Android requests it. */
@Composable
private fun QrCameraPermissionScreen(
    onRequestPermission: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(ScannerHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier.widthIn(max = ScannerPermissionMaxWidth).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.scanner_permission_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.size(ScannerSpacing))
                Text(
                    text =
                        stringResource(R.string.scanner_permission_message),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.size(ScannerVerticalPadding))
                Button(
                    onClick = onRequestPermission,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.scanner_allow_camera))
                }
                OutlinedButton(
                    onClick = onClose,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.scanner_not_now))
                }
            }
        }
    }
}

/** Binds CameraX preview and analysis only for the lifetime of this composition. */
@SuppressLint("MissingPermission")
@Composable
private fun QrCameraPreview(
    processor: QrTransferProcessor,
    onCameraReady: () -> Unit,
    onFailure: (UiText) -> Unit,
    onReceived: (ReceivedTransferText) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCameraReady by rememberUpdatedState(onCameraReady)
    val currentOnFailure by rememberUpdatedState(onFailure)
    val currentOnReceived by rememberUpdatedState(onReceived)
    val previewView =
        remember(context) {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }
    val analysisExecutor: ExecutorService =
        remember { Executors.newSingleThreadExecutor() }
    val analyzer =
        remember(processor) {
            QrFrameAnalyzer(
                processor = processor,
                onFailure = { message -> currentOnFailure(message) },
                onReceived = { transfer -> currentOnReceived(transfer) }
            )
        }
    DisposableEffect(context, lifecycleOwner, previewView, analyzer, analysisExecutor) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        var disposed = false
        var releaseUseCases: (() -> Unit)? = null
        val streamObserver = Observer<PreviewView.StreamState> { state ->
            if (!disposed && state == PreviewView.StreamState.STREAMING) {
                currentOnCameraReady()
            }
        }
        // In COMPATIBLE mode, STREAMING means the preview is actually visible.
        previewView.previewStreamState.observe(lifecycleOwner, streamObserver)
        providerFuture.addListener(
            {
                if (disposed) {
                    return@addListener
                }
                try {
                    val cameraProvider = providerFuture.get()
                    val preview =
                        Preview.Builder().build().also { cameraPreview ->
                            cameraPreview.surfaceProvider = previewView.surfaceProvider
                        }
                    val resolutionSelector =
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    TargetAnalysisResolution,
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                )
                            )
                            .setResolutionFilter { sizes, _ ->
                                sizes.filter { size ->
                                    isSupportedQrAnalysisSize(size.width, size.height)
                                }
                            }.build()
                    val analysis =
                        ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setResolutionSelector(resolutionSelector)
                            .build()
                    analysis.setAnalyzer(analysisExecutor, analyzer)
                    releaseUseCases = {
                        analysis.clearAnalyzer()
                        cameraProvider.unbind(preview, analysis)
                    }
                    val cameraSelector =
                        if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        } else {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        }
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        analysis
                    )
                } catch (_: Exception) {
                    currentOnFailure(CAMERA_UNAVAILABLE_MESSAGE)
                } catch (_: LinkageError) {
                    currentOnFailure(CAMERA_UNAVAILABLE_MESSAGE)
                }
            },
            mainExecutor
        )
        onDispose {
            disposed = true
            previewView.previewStreamState.removeObserver(streamObserver)
            analyzer.close()
            try {
                releaseUseCases?.invoke()
            } catch (_: Exception) {}
            analysisExecutor.shutdownNow()
        }
    }
    AndroidView(
        factory = { previewView },
        modifier = modifier.background(Color.Black)
    )
}

/** Copies and privately decodes at most one bounded CameraX frame at a time. */
internal class QrFrameAnalyzer(
    private val processor: QrTransferProcessor,
    private val onFailure: (UiText) -> Unit,
    private val onReceived: (ReceivedTransferText) -> Unit
) : ImageAnalysis.Analyzer,
    AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val analyzing = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)
    private val frameLock = Any()
    private var reusableFrame: ByteArray? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Copies one eligible luminance plane before releasing its camera buffer. */
    override fun analyze(image: ImageProxy) {
        if (closed.get() || completed.get() || !analyzing.compareAndSet(false, true)) {
            image.close()
            return
        }
        val frame =
            try {
                copyFrame(image)
            } catch (_: Exception) {
                null
            } finally {
                image.close()
            }
        if (frame == null) {
            analyzing.set(false)
            if (!closed.get()) {
                scope.launch { onFailure(CAMERA_FRAME_MESSAGE) }
            }
            return
        }
        scope.launch {
            try {
                val received = processor.decodeQr(frame)
                if (!closed.get() && completed.compareAndSet(false, true)) {
                    onReceived(received)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: TransferException) {
                if (!closed.get()) {
                    qrScanFailureMessage(failure.failure)?.let(onFailure)
                }
            } catch (_: Exception) {
                if (!closed.get()) {
                    onFailure(QR_READ_FAILURE_MESSAGE)
                }
            } catch (_: LinkageError) {
                if (!closed.get()) {
                    onFailure(QR_SERVICE_MESSAGE)
                }
            } finally {
                recycleFrame(frame.bytes)
                analyzing.set(false)
            }
        }
    }

    /** Cancels active decoding and releases the one reusable frame buffer. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        scope.cancel()
        synchronized(frameLock) {
            reusableFrame = null
        }
    }

    /** Copies one bounded Y plane into a contiguous grayscale frame. */
    private fun copyFrame(image: ImageProxy): QrLuminanceFrame? {
        val width = image.width
        val height = image.height
        if (
            width < TransferProtocol.MIN_QR_FRAME_SIDE ||
            height < TransferProtocol.MIN_QR_FRAME_SIDE
        ) {
            return null
        }
        val pixelCount = Math.multiplyExact(width.toLong(), height.toLong())
        if (pixelCount > TransferProtocol.MAX_QR_FRAME_PIXELS) {
            return null
        }
        val plane = image.planes.firstOrNull() ?: return null
        val bytes = takeFrame(Math.toIntExact(pixelCount))
        if (
            !copyLuminancePlane(
                source = plane.buffer,
                width = width,
                height = height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                destination = bytes
            )
        ) {
            recycleFrame(bytes)
            return null
        }
        return QrLuminanceFrame(width = width, height = height, bytes = bytes)
    }

    /** Takes one exact-sized reusable frame or allocates its first bounded buffer. */
    private fun takeFrame(size: Int): ByteArray = synchronized(frameLock) {
        val reusable = reusableFrame
        reusableFrame = null
        if (reusable?.size == size) reusable else ByteArray(size)
    }

    /** Retains at most one exact frame buffer for the next analysis attempt. */
    private fun recycleFrame(bytes: ByteArray) {
        synchronized(frameLock) {
            if (!closed.get() && reusableFrame == null) {
                reusableFrame = bytes
            }
        }
    }
}

/** Keeps CameraX fallback sizes within the isolated decoder's frame bounds. */
internal fun isSupportedQrAnalysisSize(width: Int, height: Int): Boolean =
    width >= TransferProtocol.MIN_QR_FRAME_SIDE &&
        height >= TransferProtocol.MIN_QR_FRAME_SIDE &&
        width.toLong() * height.toLong() <= TransferProtocol.MAX_QR_FRAME_PIXELS

/** Copies one strided luminance plane without changing its source position. */
internal fun copyLuminancePlane(
    source: ByteBuffer,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
    destination: ByteArray
): Boolean {
    require(width > 0) { "luminance width must be positive" }
    require(height > 0) { "luminance height must be positive" }
    require(rowStride > 0) { "luminance row stride must be positive" }
    require(pixelStride > 0) { "luminance pixel stride must be positive" }
    require(destination.size == Math.multiplyExact(width, height)) {
        "luminance destination size does not match its dimensions"
    }
    val start = source.position()
    val lastIndex =
        start.toLong() +
            Math.multiplyExact((height - 1).toLong(), rowStride.toLong()) +
            Math.multiplyExact((width - 1).toLong(), pixelStride.toLong())
    if (lastIndex >= source.limit().toLong()) {
        return false
    }
    val input = source.duplicate()
    repeat(height) { row ->
        val sourceRow = Math.addExact(start, Math.multiplyExact(row, rowStride))
        val destinationRow = Math.multiplyExact(row, width)
        if (pixelStride == 1) {
            input.position(sourceRow)
            input.get(destination, destinationRow, width)
        } else {
            repeat(width) { column ->
                destination[destinationRow + column] =
                    input.get(sourceRow + column * pixelStride)
            }
        }
    }
    return true
}

/** Returns one sanitized scanner message or null while no QR code is visible. */
private fun qrScanFailureMessage(failure: TransferFailure): UiText? = when (failure) {
    TransferFailure.NoQrCode -> null

    TransferFailure.Unsupported -> QR_UNSUPPORTED_MESSAGE

    TransferFailure.AmbiguousQr -> QR_AMBIGUOUS_MESSAGE

    TransferFailure.TimedOut -> QR_TIMEOUT_MESSAGE

    TransferFailure.ServiceUnavailable,
    TransferFailure.ServiceBusy -> QR_SERVICE_MESSAGE

    TransferFailure.TooLarge,
    TransferFailure.InvalidUtf8,
    TransferFailure.InvalidNdef,
    TransferFailure.AmbiguousNdef,
    TransferFailure.SnapshotFailed,
    TransferFailure.InvalidResponse,
    TransferFailure.ProcessingFailed -> QR_READ_FAILURE_MESSAGE
}
