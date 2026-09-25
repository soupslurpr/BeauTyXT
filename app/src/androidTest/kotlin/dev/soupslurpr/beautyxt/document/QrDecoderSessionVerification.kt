package dev.soupslurpr.beautyxt.document

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.createQrDocumentSessionIntent
import dev.soupslurpr.beautyxt.transfer.client.IsolatedTransferProcessor
import dev.soupslurpr.beautyxt.transfer.client.QrFrameDecoder
import dev.soupslurpr.beautyxt.transfer.client.QrLuminanceFrame
import dev.soupslurpr.beautyxt.transfer.client.TransferException
import dev.soupslurpr.beautyxt.transfer.client.TransferFailure
import dev.soupslurpr.beautyxt.ui.editor.QrCodeColors
import dev.soupslurpr.beautyxt.ui.editor.renderExpressiveQrCodeBitmap
import dev.soupslurpr.beautyxt.ui.transfer.NFC_TRANSFER_MIME_TYPE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private const val SESSION_TEXT = "# Scan session\n\nCoastal morning: café, 漢字, 😀."

private fun Instrumentation.transferWorkerIds(): Set<Int> {
    val pattern = "^${targetContext.packageName.replace(".", "[.]")}:transfer:"
    return ParcelFileDescriptor.AutoCloseInputStream(
        uiAutomation.executeShellCommand("pgrep -f $pattern")
    ).bufferedReader().use { reader ->
        reader.readText().split(Regex("\\s+")).mapNotNull(String::toIntOrNull).toSet()
    }
}

/** Verifies serial requests, independent session ownership, and idle cancellation. */
internal fun Instrumentation.verifyQrDecoderSession() = runBlocking {
    val activity = startActivitySync(
        Intent(targetContext, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    val processor = IsolatedTransferProcessor(targetContext)
    val frame = RustDocument.createEmpty().use { document ->
        val metrics = document.replace(0L, Utf16Range(0L, 0L), SESSION_TEXT)
        document.captureSnapshot(metrics.revision).use { snapshot ->
            val grid = processor.encodeQr(snapshot, metrics.serializedByteLength, DocumentFormat.Markdown)
            val bitmap = renderExpressiveQrCodeBitmap(
                grid, QrCodeColors(Color(0xff17315e), Color(0xffafc6ff))
            )
            try { qrDecoderSessionFrame(bitmap) } finally { bitmap.recycle() }
        }
    }
    val blank = QrLuminanceFrame(1280, 960, ByteArray(1280 * 960) { 120 })
    fun processIds(): Set<Int> = transferWorkerIds()
    suspend fun awaitGone(pids: Set<Int>) = withTimeout(10_000) {
        while (processIds().any { it in pids }) delay(20)
    }
    suspend fun expectBlank(decoder: QrFrameDecoder) {
        val failure = try {
            decoder.decodeQr(blank)
            error("Blank camera frame unexpectedly decoded")
        } catch (failure: TransferException) { failure }
        check(failure.failure == TransferFailure.NoQrCode)
    }
    suspend fun timedDecode(decoder: QrFrameDecoder): Double {
        val started = SystemClock.elapsedRealtimeNanos()
        val received = decoder.decodeQr(frame)
        val millis = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        check(received.text == SESSION_TEXT && received.format == DocumentFormat.Markdown)
        return millis
    }
    try {
        awaitGone(processIds())
        val fresh = mutableListOf<Double>()
        val session = mutableListOf<Double>()
        var escaped: QrFrameDecoder? = null
        var sessionPids = emptySet<Int>()
        repeat(6) { sample ->
            val millis = timedDecode(QrFrameDecoder(processor::decodeQr))
            fresh += millis
            Log.i("BeauTyXTQrSession", "fresh sample=$sample millis=$millis")
        }
        awaitGone(processIds())
        val before = processIds()
        processor.withQrDecoder { decoder ->
            escaped = decoder
            sessionPids = processIds() - before
            check(sessionPids.size == 1) { "Scanner did not prepare exactly one worker" }
            repeat(12) { sample ->
                expectBlank(decoder)
                val millis = timedDecode(decoder)
                session += millis
                Log.i("BeauTyXTQrSession", "session sample=$sample millis=$millis")
                check(processIds() - before == sessionPids) { "A frame replaced the scan worker" }
            }
            processor.withQrDecoder { independent ->
                val independentPids = processIds() - before - sessionPids
                check(independentPids.size == 1) { "Separate scanners shared a worker" }
                timedDecode(independent)
            }
            RustDocument.createEmpty().use { document ->
                val metrics = document.replace(0L, Utf16Range(0L, 0L), SESSION_TEXT)
                document.captureSnapshot(metrics.revision).use { snapshot ->
                    processor.encodeNfc(
                        snapshot, metrics.serializedByteLength, DocumentFormat.Markdown, null
                    ).use { envelope ->
                        val payload = envelope.copyBytes()
                        try {
                            val message = NdefMessage(
                                arrayOf(NdefRecord.createMime(NFC_TRANSFER_MIME_TYPE, payload))
                            ).toByteArray()
                            try {
                                val received = processor.decodeNfc(message)
                                check(received.text == SESSION_TEXT)
                                check(received.format == DocumentFormat.Markdown)
                            } finally {
                                message.fill(0)
                            }
                        } finally {
                            payload.fill(0)
                        }
                    }
                }
            }
            timedDecode(decoder)
        }
        awaitGone(sessionPids)
        val afterClose = try {
            checkNotNull(escaped).decodeQr(frame)
            error("A decoder escaped its closed scanner session")
        } catch (failure: TransferException) { failure }
        check(afterClose.failure == TransferFailure.ServiceUnavailable)

        val prepared = CompletableDeferred<Set<Int>>()
        val waiting = async {
            processor.withQrDecoder {
                prepared.complete(processIds())
                awaitCancellation()
            }
        }
        val idlePids = withTimeout(10_000) { prepared.await() }
        check(idlePids.isNotEmpty())
        waiting.cancelAndJoin()
        awaitGone(idlePids)
        processor.withQrDecoder { decoder ->
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { decoder.decodeQr(blank) }
            cancelled.cancelAndJoin()
            val failure = try {
                decoder.decodeQr(frame)
                error("A cancelled frame left its worker reusable")
            } catch (failure: TransferException) { failure }
            check(failure.failure == TransferFailure.ServiceUnavailable)
        }
        processor.withQrDecoder { timedDecode(it) }
        awaitGone(processIds())
        Log.i("BeauTyXTQrSession", "freshMillis=$fresh sessionMillis=$session ownership=passed")
    } finally {
        frame.bytes.fill(0)
        blank.bytes.fill(0)
        runOnMainSync(activity::finishAndRemoveTask)
    }
}

/** Checks that a background scanner drops its worker and resume starts a new one. */
internal fun Instrumentation.verifyQrScannerWorkerLifecycle() = runBlocking {
    check(targetContext.checkSelfPermission(android.Manifest.permission.CAMERA) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED)
    fun processIds(): Set<Int> = transferWorkerIds()
    suspend fun awaitOneWorker(): Int = withTimeout(10_000) {
        var worker: Int? = null
        while (worker == null) {
            worker = processIds().singleOrNull()
            if (worker == null) delay(20)
        }
        worker
    }
    suspend fun awaitNoWorkers() = withTimeout(10_000) {
        while (processIds().isNotEmpty()) delay(20)
    }
    awaitNoWorkers()
    val scanner = startActivitySync(
        createQrDocumentSessionIntent(targetContext).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    try {
        val first = awaitOneWorker()
        check(uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
        withTimeout(10_000) {
            while (true) {
                var started = true
                runOnMainSync {
                    started = (scanner as LifecycleOwner).lifecycle.currentState
                        .isAtLeast(Lifecycle.State.STARTED)
                }
                if (!started) break
                delay(20)
            }
        }
        awaitNoWorkers()
        delay(300)
        check(processIds().isEmpty()) { "A worker restarted while the scanner was backgrounded" }
        runOnMainSync {
            targetContext.getSystemService(ActivityManager::class.java).appTasks
                .single { it.taskInfo?.taskId == scanner.taskId }.moveToFront()
        }
        val resumed = awaitOneWorker()
        check(resumed != first) { "The scanner reused its backgrounded process" }
        runOnMainSync(scanner::finishAndRemoveTask)
        awaitNoWorkers()
        Log.i("BeauTyXTQrSession", "background released=$first resume fresh=$resumed close released=passed")
    } finally {
        runOnMainSync(scanner::finishAndRemoveTask)
        awaitNoWorkers()
    }
}
