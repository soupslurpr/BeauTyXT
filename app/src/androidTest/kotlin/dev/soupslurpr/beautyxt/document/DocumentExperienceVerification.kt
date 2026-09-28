package dev.soupslurpr.beautyxt.document

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import dev.soupslurpr.beautyxt.ipc.SealedInput
import dev.soupslurpr.beautyxt.markdown.client.IsolatedMarkdownRenderer
import dev.soupslurpr.beautyxt.sharing.ExcerptShares
import dev.soupslurpr.beautyxt.sharing.readSharedTextSnapshot
import dev.soupslurpr.beautyxt.transfer.client.IsolatedTransferProcessor
import dev.soupslurpr.beautyxt.transfer.client.NfcTextSource
import dev.soupslurpr.beautyxt.transfer.client.QrLuminanceFrame
import dev.soupslurpr.beautyxt.ui.editor.*
import dev.soupslurpr.beautyxt.ui.transfer.NFC_TRANSFER_MIME_TYPE
import kotlinx.coroutines.*
import java.io.FileNotFoundException

/** Verifies actual JNI, isolated render/transfer workers, PDF links, and pathname-free grants. */
internal fun verifyDocumentExperience(context: Context) = runBlocking {
    dev.soupslurpr.beautyxt.illustration.IllustrationWorkerConnection(context,
        dev.soupslurpr.beautyxt.ipc.NativeServiceNames.MATH,
        dev.soupslurpr.beautyxt.illustration.IllustrationLimits.MAX_MATH_SOURCE_BYTES).use { worker ->
        val fraction = worker.render("\\frac{a}{b}", true) as dev.soupslurpr.beautyxt.illustration.IllustrationResult.Rendered
        check(fraction.drawing.isTextComplete)
        check(fraction.drawing.textRuns.map { it.text }.containsAll(listOf("a", "b"))) {
            "fraction semantic runs changed: ${fraction.drawing.textRuns.map { it.text }}"
        }
        check(fraction.drawing.textRuns.all { it.boxes.isNotEmpty() })
    }
    dev.soupslurpr.beautyxt.illustration.IllustrationWorkerConnection(context,
        dev.soupslurpr.beautyxt.ipc.NativeServiceNames.DIAGRAM,
        dev.soupslurpr.beautyxt.illustration.IllustrationLimits.MAX_DIAGRAM_SOURCE_BYTES).use { worker ->
        val diagram = worker.render("flowchart LR\nA[First label] --> B[Second label]", true)
            as dev.soupslurpr.beautyxt.illustration.IllustrationResult.Rendered
        check(diagram.drawing.isTextComplete)
        check(diagram.drawing.textRuns.map { it.text }.containsAll(listOf("First label", "Second label")))
        check(diagram.drawing.textRuns.none { "flowchart" in it.text })
    }
    RustDocument.createEmpty().use { document ->
        val text = "cat concatenate cat\nα😀"
        val revision = document.replace(0, Utf16Range(0, 0), text).revision
        document.compileSearch("cat", SearchOptions(wholeWord = true)).use { query ->
            val found = query.source(revision, Utf16Range(0, text.length.toLong()), SearchCursor(0), "dog")
            check(found.completion == SearchCompletion.Complete && found.hits.size == 2)
            val patches = found.hits.map { DocumentPatch(it.range, it.text, checkNotNull(it.replacement)) }
            val next = document.replaceBatch(revision, patches)
            check(next.revision == revision + 1)
            check(document.readRange(next.revision, Utf16Range(0, text.length.toLong())) == "dog concatenate dog\nα😀")
            document.restoreHistory(next.revision, next.revision, undo = true)
        }
        document.compileSearch("(?P<word>cat)", SearchOptions(regex = true)).use { query ->
            val found = query.source(revision + 2, Utf16Range(0, text.length.toLong()), SearchCursor(0), "${'$'}{word}\\n${'$'}${'$'}")
            check(found.hits.first().replacement == "cat\n${'$'}")
        }
        document.compileSearch("^cat", SearchOptions(regex = true)).use { query ->
            check(query.text(" cat ", Utf16Range(1, 4), SearchCursor(1)).hits.isEmpty())
        }
        document.captureRange(revision + 2, Utf16Range(4, 15)).use { slice ->
            check(slice.metrics().serializedByteLength == 11L)
            check(slice.duplicate().use { readSharedTextSnapshot(it, 11) } == "concatenate")
        }
    }

    val markdown = "# Secret\n\nDO NOT EXPORT\n\n## Results\n\nSee [project guide](https://example.com/guide) and [results](#results). Claim[^note]\n\n[^note]: Evidence.\n\nUNSELECTED TAIL"
    val renderer = IsolatedMarkdownRenderer(context)
    // Parse generated Markdown through the actual isolated renderer, including adjacent styles.
    for (fixture in listOf(
        "**bold *both* bold** next",
        "<strong>a</strong><em>b</em> and ~~gone~~ plus <sup>up</sup><sub>down</sub>",
        "4. parent\n    2. child\n5. next\n\n* separate\n",
        "| Name | Value |\n| :--- | ---: |\n| **one** | `a|b` |\n",
        "> ## Heading\n>\n> **Quoted** text\n\n```kotlin\n    indented()\n```"
    )) {
        val original = captureGeneratedExcerpt(fixture).use { renderer.render(it.snapshot, it.metrics.serializedByteLength) }
        val last = original.blocks.lastIndex
        val excerpt = selectedMarkdown(original, DocumentSelection.Reading(0,
            ReadingPoint(0, -readingListPrefix(original.blocks[0]).length), ReadingPoint(last, original.blocks[last].text.length)))
        val restored = captureGeneratedExcerpt(excerpt.markdown).use { renderer.render(it.snapshot, it.metrics.serializedByteLength) }
        fun signature(model: dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument) = model.blocks.map { block ->
            listOf(block.kind, block.text, block.headingLevel, block.listDepth, block.listNumber, block.quoteDepth,
                block.text.indices.map { offset -> block.spans.filter { offset in it.start until it.end }.fold(0) { styles, span -> styles or span.styles } })
        }
        check(signature(original) == signature(restored)) {
            "Generated Markdown changed selected structure: ${excerpt.markdown}\nBefore: ${signature(original)}\nAfter: ${signature(restored)}"
        }
    }
    val model = captureGeneratedExcerpt(markdown).use { renderer.render(it.snapshot, it.metrics.serializedByteLength) }
    val first = model.blocks.indexOfFirst { it.text == "Results" }
    val last = model.blocks.indexOfFirst { it.text.startsWith("See ") }
    val selected = DocumentSelection.Reading(7, ReadingPoint(first, 0), ReadingPoint(last, model.blocks[last].text.length))
    val plain = selectedReadingText(model, selected, MAX_EXCERPT_MARKDOWN_BYTES)
    val transfer = IsolatedTransferProcessor(context)
    var revision = 7L
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val nfcHandoff = CompletableDeferred<Triple<ByteArray, DocumentFormat, String?>>()
    var nfcHandoffBytes = 0L
    val controller = ExcerptExportController(scope,
        captureSelection = { ExcerptCapture(7, captureGeneratedExcerpt(plain), false, DocumentFormat.PlainText, model, selected) },
        currentRevision = { revision }, matchesSourceUri = { false }, renderer, transfer, transfer,
        showQr = { _, _, _ -> }, writeNfc = { envelope, bytes, format, label ->
            // Use Android's actual NDEF framing, as the NFC writer does at handoff.
            val message = envelope.use {
                NdefMessage(arrayOf(NdefRecord.createMime(NFC_TRANSFER_MIME_TYPE, it.copyBytes()))).toByteArray()
            }
            nfcHandoffBytes = bytes
            check(nfcHandoff.complete(Triple(message, format, label)))
        })
    suspend fun ready(): PreparedExcerpt = withTimeout(30_000) {
        while (true) {
            val result = withContext(Dispatchers.Main) {
                check(controller.message == null) { "Excerpt preparation failed: ${controller.message}" }
                controller.prepared.takeUnless { controller.busy }
            }
            if (result != null) return@withTimeout result
            delay(20)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    try {
        withContext(Dispatchers.Main) { controller.open(context, "Notes.md") }
        check(ready().text.snapshot.duplicate().use { readSharedTextSnapshot(it, ready().text.metrics.serializedByteLength) } == plain)
        withContext(Dispatchers.Main) { controller.selectFormat(ExcerptFormat.Markdown) }
        val generated = ready()
        val exact = generated.text.snapshot.duplicate().use { readSharedTextSnapshot(it, generated.text.metrics.serializedByteLength) }
        check("Secret" !in exact && "DO NOT EXPORT" !in exact && "UNSELECTED TAIL" !in exact)
        check("[^1]: Evidence" in exact && "https://example.com/guide" in exact)
        check(generated.notices.any { it.kind == ExcerptNoticeKind.AddedNote })
        withContext(Dispatchers.Main) { controller.selectFormat(ExcerptFormat.Pdf) }
        val pdf = ready()
        check(pdf.pdfPages > 0)
        checkNotNull(pdf.pdf).openReadOnly(context).use { input ->
            PdfRenderer(input).use { reader ->
                reader.openPage(0).use { page ->
                    val text = page.textContents.joinToString("\n") { it.text }
                    check("project guide" in text && "Secret" !in text && "UNSELECTED TAIL" !in text)
                    check(page.linkContents.any { it.uri.toString() == "https://example.com/guide" }) { "PDF lost web link annotations" }
                    check(page.gotoLinks.isNotEmpty()) { "PDF lost internal link destinations" }
                }
            }
        }
        withContext(Dispatchers.Main) { revision++; check(controller.isStale && !controller.canApply); revision-- }
        withContext(Dispatchers.Main) { controller.selectDestination(ExcerptDestination.Qr) }
        val qr = checkNotNull(ready().qr)
        val side = (qr.dimension + 8) * 8
        val luminance = ByteArray(side * side) { -1 }
        repeat(qr.dimension) { row -> repeat(qr.dimension) { column -> if (qr.isDark(row, column)) {
            repeat(8) { y -> repeat(8) { x -> luminance[((row + 4) * 8 + y) * side + (column + 4) * 8 + x] = 0 } }
        } } }
        check(transfer.decodeQr(QrLuminanceFrame(side, side, luminance)).text == plain)
        withContext(Dispatchers.Main) { controller.selectDestination(ExcerptDestination.Nfc) }
        val plainNfc = checkNotNull(ready().nfc)
        val plainMessage = NdefMessage(arrayOf(NdefRecord.createMime(NFC_TRANSFER_MIME_TYPE, plainNfc.copyBytes())))
        val decodedPlain = transfer.decodeNfc(plainMessage.toByteArray())
        check(decodedPlain.text == plain && decodedPlain.format == DocumentFormat.PlainText)
        check(decodedPlain.nfcMetadata?.source == NfcTextSource.BeauTyXT && decodedPlain.nfcMetadata.tagLabel == null)
        withContext(Dispatchers.Main) {
            controller.updateTagLabel("EX1")
            controller.selectFormat(ExcerptFormat.Markdown)
        }
        checkNotNull(ready().nfc)
        withContext(Dispatchers.Main) { controller.apply(context) }
        val (message, handedOffFormat, label) = withTimeout(30_000) { nfcHandoff.await() }
        check(handedOffFormat == DocumentFormat.Markdown && label == "EX1")
        check(nfcHandoffBytes == exact.toByteArray(Charsets.UTF_8).size.toLong())
        val received = transfer.decodeNfc(message)
        check(received.text == exact && received.format == DocumentFormat.Markdown)
        check(received.nfcMetadata?.source == NfcTextSource.BeauTyXT && received.nfcMetadata.tagLabel == label)
        withContext(Dispatchers.Main) { check(!controller.visible && controller.prepared?.nfc == null) }
    } finally { withContext(Dispatchers.Main) { controller.close(); scope.cancel() } }

    // Two consumers must have independent seek positions and no writable or source capability.
    SealedInput.fromBytes("only selected".toByteArray(), 100).use { input ->
        val lease = ExcerptShares.retain(context, "Notes excerpt.txt", "text/plain", input.duplicate())
        val uri = lease.uri
        check(context.contentResolver.getType(uri) == "text/plain")
        context.contentResolver.query(uri, null, null, null, null)!!.use { metadata ->
            check(metadata.moveToFirst() && metadata.count == 1)
            check(metadata.getString(metadata.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) == "Notes excerpt.txt")
            check(metadata.getLong(metadata.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE)) == 13L)
        }
        check(runCatching { context.contentResolver.openFileDescriptor(uri, "rw") }.exceptionOrNull() is FileNotFoundException)
        val altered = uri.buildUpon().appendQueryParameter("different", "capability").build()
        check(runCatching { context.contentResolver.openFileDescriptor(altered, "r") }.exceptionOrNull() is FileNotFoundException)
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { firstReader ->
            context.contentResolver.openFileDescriptor(uri, "r")!!.use { secondReader ->
                check(Os.fcntlInt(firstReader.fileDescriptor, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE == OsConstants.O_RDONLY)
                Os.lseek(firstReader.fileDescriptor, 5, OsConstants.SEEK_SET)
                ParcelFileDescriptor.AutoCloseInputStream(secondReader).use { check(it.readBytes().decodeToString() == "only selected") }
            }
        }
        lease.close()
        check(runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.exceptionOrNull() is FileNotFoundException)
    }
}
