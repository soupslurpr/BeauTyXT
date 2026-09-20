/* Owns print resources across Android callbacks and cancellable worker jobs. */
package dev.soupslurpr.beautyxt.printing

import android.content.res.Resources
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import androidx.annotation.StringRes
import dev.soupslurpr.beautyxt.R
import java.io.BufferedOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PRINT_OUTPUT_BUFFER_BYTES = 64 * 1_024

/** Releases one completed write before running a potentially reentrant framework callback. */
internal suspend fun dispatchReleasedPrintWrite(release: () -> Unit, callback: suspend () -> Unit) {
    release()
    callback()
}

/** Owns a print destination even when cancellation prevents the lazy worker from starting. */
internal fun CoroutineScope.launchPrintWrite(
    destination: AutoCloseable,
    write: suspend CoroutineScope.() -> Unit
): Job = launch(start = CoroutineStart.LAZY, block = write).also { job ->
    job.invokeOnCompletion {
        try {
            destination.close()
        } catch (_: IOException) {}
    }
}

/** Owns one immutable document representation for Android's native print lifecycle. */
internal class DocumentPrintAdapter(
    val jobName: String,
    private val documentName: String,
    private val content: PrintDocumentContent,
    private val settings: PrintSettings,
    private val resources: Resources,
    workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : PrintDocumentAdapter(),
    AutoCloseable {
    private val adapterLock = Any()
    private val closed = AtomicBoolean(false)
    private val operationScope = CoroutineScope(SupervisorJob() + workerDispatcher)
    private var pageLayout: PrintRasterLayout? = null
    private var activeWrite: ActivePrintWrite? = null

    init {
        require(jobName.isNotBlank()) { "print job name must not be blank" }
        require(documentName.isNotBlank()) { "printed document name must not be blank" }
    }

    /** Validates one printer configuration without reading document content. */
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        if (closed.get()) {
            callback.onLayoutFailed(resources.getString(R.string.print_layout_failure))
            return
        }
        try {
            val layout = printRasterLayout(newAttributes, settings)
            synchronized(adapterLock) {
                check(activeWrite == null) { "print layout changed during an active write" }
                check(!closed.get()) { "print adapter is closed" }
                pageLayout = layout
            }
            if (cancellationSignal.isCanceled) {
                callback.onLayoutCancelled()
                return
            }
            callback.onLayoutFinished(
                PrintDocumentInfo.Builder(documentName)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    .build(),
                oldAttributes != newAttributes
            )
        } catch (_: Exception) {
            callback.onLayoutFailed(resources.getString(R.string.print_layout_failure))
        } catch (_: LinkageError) {
            callback.onLayoutFailed(resources.getString(R.string.print_layout_failure))
        }
    }

    /** Streams requested logical pages directly into Android's destination descriptor. */
    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback
    ) {
        val layout = synchronized(adapterLock) { pageLayout }
        if (cancellationSignal.isCanceled) {
            destination.closeQuietly()
            callback.onWriteCancelled()
            return
        }
        if (closed.get() || layout == null) {
            destination.closeQuietly()
            callback.onWriteFailed(resources.getString(R.string.print_write_failure))
            return
        }

        lateinit var ownedWrite: ActivePrintWrite
        val active =
            synchronized(adapterLock) {
                if (closed.get() || activeWrite != null) {
                    null
                } else {
                    val writeJob =
                        operationScope.launchPrintWrite(destination) {
                            writeDocument(
                                pages = pages,
                                destination = destination,
                                cancellationSignal = cancellationSignal,
                                activeWrite = ownedWrite,
                                layout = layout
                            )
                        }
                    ActivePrintWrite(
                        job = writeJob,
                        cancellationSignal = cancellationSignal,
                        callback = callback
                    ).also {
                        ownedWrite = it
                        activeWrite = it
                    }
                }
            }
        if (active == null) {
            destination.closeQuietly()
            callback.onWriteFailed(resources.getString(R.string.print_write_failure))
            return
        }
        val writeJob = active.job
        cancellationSignal.setOnCancelListener {
            writeJob.cancel()
            operationScope.launch {
                writeJob.join()
                dispatchWriteOutcome(
                    active = active,
                    outcome = PrintWriteOutcome.Cancelled
                )
            }
        }
        writeJob.invokeOnCompletion {
            finishWrite(active)
        }
        writeJob.start()
    }

    /** Releases the immutable representation after Android finishes the print lifecycle. */
    override fun onFinish() {
        close()
        super.onFinish()
    }

    /** Cancels active output and closes the owned representation exactly once. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        val write =
            synchronized(adapterLock) {
                pageLayout = null
                activeWrite.also { activeWrite = null }
            }
        write?.cancellationSignal?.setOnCancelListener(null)
        write?.job?.cancel()
        operationScope.cancel()
        content.close()
    }

    /** Performs one complete destination write and dispatches exactly one result. */
    private suspend fun writeDocument(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        activeWrite: ActivePrintWrite,
        layout: PrintRasterLayout
    ) {
        val outcome =
            try {
                val result =
                    BufferedOutputStream(
                        ParcelFileDescriptor.AutoCloseOutputStream(destination),
                        PRINT_OUTPUT_BUFFER_BYTES
                    ).use { output ->
                        when (val currentContent = content) {
                            is SourcePrintDocumentContent ->
                                renderDocumentTextPdf(
                                    destination = output,
                                    snapshot = currentContent.snapshot,
                                    metrics = currentContent.metrics,
                                    title = jobName,
                                    layout = layout,
                                    settings = settings,
                                    requestedPages = pages,
                                    resources = resources,
                                    isCancelled = cancellationSignal::isCanceled
                                )

                            is MarkdownPrintDocumentContent ->
                                renderMarkdownDocumentPdf(
                                    destination = output,
                                    document = currentContent.document,
                                    title = jobName,
                                    layout = layout,
                                    settings = settings,
                                    requestedPages = pages,
                                    resources = resources,
                                    isCancelled = cancellationSignal::isCanceled
                                )
                        }
                    }
                if (cancellationSignal.isCanceled) {
                    PrintWriteOutcome.Cancelled
                } else {
                    PrintWriteOutcome.Finished(result.writtenPageRanges.toTypedArray())
                }
            } catch (cancellation: CancellationException) {
                PrintWriteOutcome.Cancelled
            } catch (_: PrintParagraphLimitException) {
                PrintWriteOutcome.Failed(R.string.print_paragraph_failure)
            } catch (_: MarkdownPrintBlockLimitException) {
                PrintWriteOutcome.Failed(R.string.print_markdown_block_failure)
            } catch (_: IOException) {
                PrintWriteOutcome.Failed()
            } catch (_: Exception) {
                PrintWriteOutcome.Failed()
            } catch (_: LinkageError) {
                PrintWriteOutcome.Failed()
            }
        dispatchWriteOutcome(active = activeWrite, outcome = outcome)
    }

    /** Dispatches a terminal framework callback on the main thread when still owned. */
    private suspend fun dispatchWriteOutcome(active: ActivePrintWrite, outcome: PrintWriteOutcome) {
        if (!active.callbackClaimed.compareAndSet(false, true)) {
            return
        }
        dispatchReleasedPrintWrite(release = { releaseWrite(active) }) {
            withContext(callbackDispatcher + NonCancellable) {
                if (closed.get()) {
                    return@withContext
                }
                when (outcome) {
                    PrintWriteOutcome.Cancelled -> active.callback.onWriteCancelled()

                    is PrintWriteOutcome.Failed ->
                        active.callback.onWriteFailed(resources.getString(outcome.messageResource))

                    is PrintWriteOutcome.Finished -> active.callback.onWriteFinished(outcome.pages)
                }
            }
        }
    }

    /** Releases one active-write slot without disturbing a newer framework request. */
    private fun finishWrite(completed: ActivePrintWrite) {
        releaseWrite(completed)
    }

    /** Releases one exact active write before a reentrant framework callback. */
    private fun releaseWrite(completed: ActivePrintWrite) {
        synchronized(adapterLock) {
            if (activeWrite !== completed) {
                return
            }
            activeWrite = null
        }
    }
}

/** Couples one active worker job with its framework cancellation signal. */
private data class ActivePrintWrite(
    val job: Job,
    val cancellationSignal: CancellationSignal,
    val callback: PrintDocumentAdapter.WriteResultCallback,
    val callbackClaimed: AtomicBoolean = AtomicBoolean(false)
)

/** Represents one terminal background write result. */
private sealed interface PrintWriteOutcome {
    /** Reports exact original logical pages successfully written. */
    data class Finished(val pages: Array<PageRange>) : PrintWriteOutcome

    /** Reports explicit framework or coroutine cancellation. */
    data object Cancelled : PrintWriteOutcome

    /** Reports a sanitized output failure. */
    data class Failed(@StringRes val messageResource: Int = R.string.print_write_failure) :
        PrintWriteOutcome
}

/** Closes one abandoned framework destination without masking its callback result. */
private fun ParcelFileDescriptor.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {}
}
