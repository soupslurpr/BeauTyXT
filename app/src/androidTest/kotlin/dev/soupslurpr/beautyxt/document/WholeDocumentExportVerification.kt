package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.soupslurpr.beautyxt.exporting.client.StatelessExportTestSinks
import dev.soupslurpr.beautyxt.ipc.SealedInput
import dev.soupslurpr.beautyxt.markdown.client.IsolatedMarkdownRenderer
import dev.soupslurpr.beautyxt.sharing.readSharedTextSnapshot
import dev.soupslurpr.beautyxt.ui.editor.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*

/** Uses the real editor, immutable native snapshots, PDF renderer, and a separate-UID save sink. */
internal fun Instrumentation.verifyWholeDocumentExport() {
    verifyWholeSourceBytes()
    val markdown = "# Whole document\n\nBefore the selection.\n\n" +
        "Selected **passage** with [web](https://example.com/guide) and [heading](#whole-document).\n\n" +
        "After the selection."
    withReadingPage(markdown) { activity, session ->
        requireActionableContentDescription("Send and export").performRequiredClick()
        val export = session.excerptExport
        awaitExport(export)
        runOnMainSync {
            export.dismiss()
            check(session.selectReading(ReadingPoint(2, 0), ReadingPoint(2, 8)))
            check(session.openDocumentExport(activity))
        }
        val selection = session.documentSelection
        awaitExport(export)
        check(export.exportScope == ExportScope.Document && export.fileName == "Reading taps.md")
        check(export.preparedText() == markdown && session.documentSelection == selection)
        check(export.formats == listOf(ExcerptFormat.Text, ExcerptFormat.ReadingText, ExcerptFormat.Pdf))
        requireActionableText("Plain text").performRequiredClick()
        awaitExport(export)
        val reading = export.preparedText()
        check("**" !in reading && "https://" !in reading && "Before the selection." in reading &&
            "After the selection." in reading && "Selected passage" in reading)
        check(export.fileName == "Reading taps.txt" && export.mimeType == "text/plain")
        runOnMainSync { export.refresh() }
        awaitExport(export)
        check(export.format == ExcerptFormat.ReadingText && export.preparedText() == reading)
        requireActionableText("PDF").performRequiredClick()
        awaitExport(export)
        val pdf = checkNotNull(export.prepared)
        check(pdf.pdfPages == 1 && export.fileName == "Reading taps.pdf")
        checkNotNull(pdf.pdf).openReadOnly(targetContext).use { input ->
            PdfRenderer(input).use { renderer -> renderer.openPage(0).use { page ->
                val text = page.textContents.joinToString("\n") { it.text }
                check("Before the selection." in text && "After the selection." in text && "**" !in text)
                check(page.linkContents.any { it.uri.toString() == "https://example.com/guide" })
                check(page.gotoLinks.isNotEmpty()) { "Whole-document PDF lost its internal destinations" }
            } }
        }
        val bytes = checkNotNull(pdf.pdf).openReadOnly(targetContext).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).use { reader -> reader.readBytes() }
        }
        val token = UUID.randomUUID().toString().replace("-", "")
        val resolver = targetContext.contentResolver
        val authority = context.packageName
        try {
            StatelessExportTestSinks.reset(resolver, authority, token)
            runOnMainSync {
                check(export.usePreparedDestination(ExcerptDestination.Save))
                check(export.prepared === pdf && export.format == ExcerptFormat.Pdf)
                check(export.chooseSaveDestination())
                export.destinationReturned(StatelessExportTestSinks.normal(authority, token))
            }
            awaitReadingCondition("whole PDF save did not complete") { !export.busy && !export.handingOff }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            runBlocking { withTimeout(10_000) {
                while (!StatelessExportTestSinks.status(resolver, authority, token).terminal) delay(10)
            } }
            val saved = StatelessExportTestSinks.status(resolver, authority, token)
            check(!saved.hasError && saved.byteCount == bytes.size.toLong() && saved.sha256Hex == digest) {
                "Save did not write the exact previewed whole-document PDF"
            }
            runOnMainSync {
                check(export.usePreparedDestination(ExcerptDestination.Share))
                check(export.prepared === pdf && export.format == ExcerptFormat.Pdf)
                export.dismiss()
            }
            check(session.documentSelection == selection && !session.state.hasDocumentChanges)
        } finally { StatelessExportTestSinks.reset(resolver, authority, token) }
    }

    withReadingPage("Earlier text", initialPresentation = EditorPresentation.Text) { activity, session ->
        val latest = "Freshly typed 👩‍🔬 text"
        runOnMainSync {
            // The toolbar must capture text even before Compose observes the newest IME edit.
            checkNotNull(session.activeDraft).textFieldState.edit { replace(0, length, latest) }
            check(session.openDocumentExport(activity))
        }
        val export = session.excerptExport
        awaitExport(export)
        check(export.preparedText() == latest) { "Whole export missed the latest editor draft" }
        runOnMainSync {
            val draft = checkNotNull(session.activeDraft)
            draft.textFieldState.edit { replace(0, length, "Updated while reviewing") }
            session.observeActiveEdit(draft, draft.captureFieldValue())
            check(export.isStale && !export.canApply)
            export.refresh()
        }
        awaitExport(export)
        check(export.preparedText() == "Updated while reviewing" && !export.isStale)
    }

    // Exceed the direct-text boundary and the editor's current window; file output remains complete.
    val large = "x".repeat(128 * 1024 + 1) + "\nLAST SOURCE LINE"
    withReadingPage(large, initialPresentation = EditorPresentation.Text) { activity, session ->
        runOnMainSync { check(session.openDocumentExport(activity)) }
        val export = session.excerptExport
        awaitExport(export)
        check(!export.fitsDestination(ExcerptDestination.Share) && export.fitsDestination(ExcerptDestination.Save))
        check(export.textBytes == large.toByteArray().size.toLong())
        runOnMainSync { export.updateShareAsFile(true) }
        awaitExport(export)
        check(export.canApply && export.fitsDestination(ExcerptDestination.Share))
        val payload = checkNotNull(export.prepared)
        val actual = payload.text.snapshot.duplicate().use {
            SealedInput.fromSnapshot(it, payload.text.metrics.serializedByteLength, 256L * 1024 * 1024).use { input ->
                ParcelFileDescriptor.AutoCloseInputStream(input.openReadOnly(targetContext)).use { reader -> reader.readBytes() }
            }
        }
        check(actual.contentEquals(large.toByteArray())) { "Whole export stopped at the visible editing window" }
    }
}

private fun Instrumentation.verifyWholeSourceBytes() {
    val original = "\uFEFF# Exact source\r\n\r\nA **bold** word.\r\n".toByteArray()
    val document = SealedInput.fromBytes(original, original.size.toLong()).use { input ->
        input.takeReader().use { RustDocument.openSource(it.fd, original.size.toLong()) }
    }
    val session = EditorSession("Exact.md", EditorDocumentState(document),
        markdownRenderer = IsolatedMarkdownRenderer(targetContext))
    try {
        runOnMainSync { session.openInitialEditor() }
        awaitReadingCondition("exact source did not open") { session.activeDraft != null }
        runOnMainSync { check(session.openDocumentExport(targetContext)) }
        val export = session.excerptExport
        awaitExport(export)
        check(export.preparedText().toByteArray().contentEquals(original)) { "Whole export changed the BOM or CRLF bytes" }
        runOnMainSync { export.dismiss(); export.open(targetContext, "Exact.md", ExportScope.Document, sharesSourceFile = true) }
        awaitExport(export)
        check(export.isSharingSourceFile && export.shareAsFile)
        runOnMainSync { export.selectFormat(ExcerptFormat.Pdf) }
        awaitExport(export)
        check(!export.isSharingSourceFile && export.prepared?.pdf != null)
        check(!session.state.hasDocumentChanges)
    } finally { runOnMainSync { session.close() } }
}

private fun Instrumentation.awaitExport(export: ExcerptExportController) {
    awaitReadingCondition("whole-document export did not prepare") {
        check(export.message == null) { "Whole-document export failed: ${export.message}" }
        export.prepared != null && !export.busy
    }
}

private fun ExcerptExportController.preparedText(): String = runBlocking {
    val payload = checkNotNull(prepared)
    payload.text.snapshot.duplicate().use { readSharedTextSnapshot(it, payload.text.metrics.serializedByteLength) }
}
