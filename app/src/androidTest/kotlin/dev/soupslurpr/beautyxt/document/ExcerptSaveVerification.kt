package dev.soupslurpr.beautyxt.document

import android.content.Context
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.exporting.client.ExportSinkStatus
import dev.soupslurpr.beautyxt.exporting.client.StatelessExportTestSinks
import dev.soupslurpr.beautyxt.sharing.readSharedTextSnapshot
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.editor.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*

/** Cancels real blocked provider writes and checks that their UI ownership ends. */
internal fun verifyExcerptSave(context: Context, authorityPackage: String) = runBlocking {
    val resolver = context.contentResolver
    val token = UUID.randomUUID().toString().replace("-", "")
    val text = "Selected source 👩‍🔬\n".repeat(StatelessExportTestSinks.blockedPipeBytes)
    val expected = text.toByteArray()
    val digest = MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") { "%02x".format(it) }
    check(expected.size > StatelessExportTestSinks.blockedPipeBytes + 1)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val releaseNewCapture = CompletableDeferred<Unit>()
    var captureCount = 0

    suspend fun awaitState(predicate: () -> Boolean) = withTimeout(10_000) {
        while (!withContext(Dispatchers.Main) { predicate() }) delay(10)
    }
    suspend fun sink(token: String, predicate: (ExportSinkStatus) -> Boolean): ExportSinkStatus = withTimeout(10_000) {
        while (true) {
            val status = withContext(Dispatchers.IO) { StatelessExportTestSinks.status(resolver, authorityPackage, token) }
            if (predicate(status)) return@withTimeout status
            delay(10)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    RustDocument.createEmpty().use { source ->
        val metrics = source.replace(0, Utf16Range(0, 0), text)
        val controller = ExcerptExportController(scope, {
            if (++captureCount > 1) releaseNewCapture.await()
            ExcerptCapture(metrics.revision, CapturedDocumentRevision(metrics, source.captureSnapshot(metrics.revision)),
                true, DocumentFormat.PlainText, null, null)
        }, { metrics.revision }, { false }, null, null, null, { _, _, _ -> }, { _, _, _, _ -> })
        try {
            StatelessExportTestSinks.reset(resolver, authorityPackage, token)
            withContext(Dispatchers.Main) { controller.open(context, "Source.txt") }
            awaitState { controller.canApply }
            withContext(Dispatchers.Main) { controller.selectDestination(ExcerptDestination.Save) }
            awaitState { controller.canApply }
            val frozen = controller.prepared
            withContext(Dispatchers.Main) {
                check(controller.chooseSaveDestination())
                controller.destinationReturned(StatelessExportTestSinks.blocked(authorityPackage, token))
            }
            check(sink(token) { it.paused }.byteCount == 1L)
            withContext(Dispatchers.Main) {
                check(controller.canCancelSave && !controller.canConfigure && !controller.canApply)
                // A duplicated picker callback must not release or replace the in-flight save.
                controller.destinationReturned(null)
                check(controller.canCancelSave)
                controller.cancelSave()
            }
            awaitState { !controller.busy && !controller.handingOff }
            withContext(Dispatchers.Main) {
                check(controller.message == UiText.Resource(R.string.excerpt_save_uncertain))
                check(controller.prepared === frozen && controller.canApply)
            }
            StatelessExportTestSinks.releasePaused(resolver, authorityPackage, token)
            check(sink(token) { it.terminal }.let { it.hasError && it.byteCount < expected.size })

            // Retry uses the same frozen payload and reaches clean EOF with its exact bytes.
            StatelessExportTestSinks.reset(resolver, authorityPackage, token)
            withContext(Dispatchers.Main) {
                check(controller.chooseSaveDestination())
                controller.destinationReturned(StatelessExportTestSinks.normal(authorityPackage, token))
                check(controller.message == null && controller.canCancelSave)
            }
            awaitState { !controller.busy && !controller.handingOff }
            check(sink(token) { it.terminal }.let {
                !it.hasError && it.byteCount == expected.size.toLong() && it.sha256Hex == digest
            })
            StatelessExportTestSinks.reset(resolver, authorityPackage, token)
            withContext(Dispatchers.Main) {
                check(controller.message == UiText.Resource(R.string.excerpt_saved))
                check(controller.prepared === frozen && controller.canApply)
                check(controller.chooseSaveDestination())
                controller.destinationReturned(StatelessExportTestSinks.blocked(authorityPackage, token))
            }
            sink(token) { it.paused }
            val retiringJobs = withContext(Dispatchers.Main) {
                checkNotNull(scope.coroutineContext[Job]).children.toList().also {
                    check(it.isNotEmpty())
                    controller.close()
                    controller.open(context, "New review.txt")
                }
            }
            // Keep the new capture suspended until every old callback has completed.
            withTimeout(15_000) { retiringJobs.joinAll() }
            withContext(Dispatchers.Main) {
                check(controller.visible && controller.busy && controller.capture == null && controller.message == null) {
                    "The cancelled save changed the reopened export review"
                }
                check(!controller.handingOff && !controller.canApply)
            }
            StatelessExportTestSinks.releasePaused(resolver, authorityPackage, token)
            sink(token) { it.terminal }
            releaseNewCapture.complete(Unit)
            awaitState { controller.canApply }
            withContext(Dispatchers.Main) {
                check(controller.destination == ExcerptDestination.Share && controller.message == null)
            }
            source.captureSnapshot(metrics.revision).use {
                check(readSharedTextSnapshot(it, metrics.serializedByteLength) == text) { "Saving changed the source" }
            }
        } finally {
            withContext(Dispatchers.Main) { controller.close(); scope.cancel() }
            StatelessExportTestSinks.releasePaused(resolver, authorityPackage, token)
            StatelessExportTestSinks.reset(resolver, authorityPackage, token)
        }
    }
}
