package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import dev.soupslurpr.beautyxt.HomeActivity
import dev.soupslurpr.beautyxt.illustration.IllustrationFailure
import dev.soupslurpr.beautyxt.illustration.IllustrationKind
import dev.soupslurpr.beautyxt.illustration.IllustrationLimits
import dev.soupslurpr.beautyxt.illustration.IllustrationPacketBuffer
import dev.soupslurpr.beautyxt.illustration.IllustrationPath
import dev.soupslurpr.beautyxt.illustration.IllustrationResult
import dev.soupslurpr.beautyxt.illustration.IllustrationWorkerConnection
import dev.soupslurpr.beautyxt.illustration.IsolatedDiagramService
import dev.soupslurpr.beautyxt.illustration.IsolatedIllustrationProbeService
import dev.soupslurpr.beautyxt.illustration.IsolatedMathService
import dev.soupslurpr.beautyxt.illustration.NativeIllustration
import dev.soupslurpr.beautyxt.illustration.RestartingIllustrationWorker
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import dev.soupslurpr.beautyxt.markdown.client.IsolatedMarkdownRenderer
import dev.soupslurpr.beautyxt.printing.IllustrationPrintSpan
import dev.soupslurpr.beautyxt.printing.PrintBlockBackgroundColor
import dev.soupslurpr.beautyxt.printing.defaultPrintSettings
import dev.soupslurpr.beautyxt.printing.printRasterLayout
import dev.soupslurpr.beautyxt.printing.renderMarkdownDocumentPdf
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme
import dev.soupslurpr.beautyxt.ui.editor.DocumentEditor
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentState
import dev.soupslurpr.beautyxt.ui.editor.EditorPresentation
import dev.soupslurpr.beautyxt.ui.editor.EditorSession
import dev.soupslurpr.beautyxt.ui.editor.MarkdownPreviewStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private val nativeMathSource = """
    # Mathematics

    A fraction ${'$'}\frac{1}{2}${'$'} and a root ${'$'}\sqrt{x^2+y^2}${'$'} sit on this baseline.

    ${'$'}${'$'}
    \sum_{n=1}^{\infty}\frac{1}{n^2}=\frac{\pi^2}{6}
    ${'$'}${'$'}

    ## Matrices

    ```math
    \begin{pmatrix} a & b \\
    c & d \end{pmatrix}
    ```

    Unsupported ${'$'}\includegraphics{example.png}${'$'} remains available as source.

    ## Bracket delimiters

    Inline \(a^2+b^2=c^2\) stays inline.

    \[\frac{a+b}{c}\]
""".trimIndent()

private val flowchartSource = """
    flowchart TD
    A[Read locally] --> B{Edit?}
    B -->|Yes| C[Save to source]
    B -->|No| D[Share]
""".trimIndent()

private val sequenceSource = """
    sequenceDiagram
    participant A as Reader
    participant B as Editor
    A->>B: Open document
    B-->>A: Render locally
""".trimIndent()

private val nativeDiagramSource =
    "# Diagrams\n\n```mermaid\n$flowchartSource\n```\n\n" +
        "## Messages\n\n```mermaid\n$sequenceSource\n```"

private val extraDiagramSources = linkedMapOf(
    "States" to
        "stateDiagram-v2\n[*] --> Reading\nReading --> Editing: Refine\nEditing --> Reading: Preview\nReading --> [*]",
    "Classes" to
        "classDiagram\nclass Document {\n+String title\n+read() String\n}\nclass Reader\nReader --> Document: opens",
    "Relationships" to
        "erDiagram\nDOCUMENT ||--o{ REVISION : has\nDOCUMENT {\nstring title\n}\nREVISION {\nint number\n}",
    "Languages" to
        "flowchart TD\naccTitle: Read in your language\naccDescr: A path from reading to sharing.\nA[Read 你好] --> B[Refine العربية]\nB --> C[Share עברית]"
)

internal fun verifyNativeDiagrams(context: Context) = runBlocking {
    verifyIllustrationPrintSurface()
    IllustrationWorkerConnection(
        context,
        IsolatedDiagramService::class.java,
        IllustrationLimits.MAX_DIAGRAM_SOURCE_BYTES
    ).use { worker ->
        for (label in listOf(
            "你好",
            "العربية",
            "עברית",
            "Read 你好",
            "Refine العربية",
            "Share עברית"
        )) {
            val result = worker.render("flowchart LR\nA[$label] --> B[Read]", true)
            check(result is IllustrationResult.Rendered) {
                "platform script $label failed: $result"
            }
        }
        for (source in listOf(flowchartSource, sequenceSource) + extraDiagramSources.values) {
            val result = worker.render(source, true)
            check(result is IllustrationResult.Rendered) { "native diagram failed: $result" }
            check(result.drawing.paths.isNotEmpty())
            check(
                result.drawing.paths.all {
                    it.color in 0..3
                }
            ) {
                "${source.lineSequence().first()} escaped its host palette: " +
                    result.drawing.paths.map { it.color }.toSet()
            }
            if (source.contains("accTitle:")) {
                check(result.drawing.title == "Read in your language")
                check(result.drawing.description == "A path from reading to sharing.")
            }
        }
        check(
            worker.render(
                "flowchart TD\nclick A call callback()",
                true
            ) is IllustrationResult.Fallback
        )
        check(worker.render(flowchartSource, true) is IllustrationResult.Rendered)
    }
    val fragmentedSource = "```mermaid\nflowchart TD\n%% " + "a".repeat(4_500) + "\nA-->B\n```"
    val joined = renderNativeMathFixture(context, fragmentedSource)
    check(joined.blocks.size == 1 && joined.blocks.single().text.length > 4_096)
    check(joined.blocks.single().spans.single().illustration is IllustrationResult.Rendered)
    val allSources = nativeDiagramSource + extraDiagramSources.entries.joinToString(
        ""
    ) { (name, source) ->
        "\n\n## $name\n\n```mermaid\n$source\n```"
    }
    val preview = renderNativeMathFixture(context, allSources)
    check(
        preview.blocks.flatMap { it.spans }.count {
            it.illustration is IllustrationResult.Rendered &&
                it.illustration.kind == IllustrationKind.Diagram
        } == 2 + extraDiagramSources.size
    )
    val attributes = PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setResolution(PrintAttributes.Resolution("diagrams", "Diagrams", 144, 144))
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .build()
    val settings = defaultPrintSettings(true)
    val pdf = File(mathArtifacts(context), "native-diagrams.pdf")
    pdf.outputStream().use { output ->
        check(
            renderMarkdownDocumentPdf(
                output,
                preview,
                "Diagrams.md",
                printRasterLayout(attributes, settings),
                settings,
                arrayOf(PageRange.ALL_PAGES),
                context.resources
            ).totalPageCount > 0
        )
    }
    PdfRenderer(
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
    ).use { renderer ->
        for (term in listOf(
            "stateDiagram-v2",
            "classDiagram",
            "erDiagram",
            "你好",
            "العربية",
            "עברית"
        )) {
            check(
                (0 until renderer.pageCount).any { index ->
                    renderer.openPage(index).use { it.searchText(term).isNotEmpty() }
                }
            ) { "diagram PDF lost searchable source: $term" }
        }
    }
}

/** Opaque label cutouts use the actual paper/container surface, not a universal white patch. */
private fun verifyIllustrationPrintSurface() {
    val rectangle = NativeIllustration(
        1f,
        1f,
        0f,
        listOf(
            IllustrationPath(
                1,
                false,
                floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f, 4f)
            )
        ),
        116
    )
    for (surface in listOf(Color.WHITE, PrintBlockBackgroundColor)) {
        val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            val paint = Paint().apply { textSize = 10f }
            IllustrationPrintSpan(rectangle, "surface", 16f, 16f, surface)
                .draw(Canvas(image), "\uFFFC", 0, 1, 0f, 0, 0, 10, paint)
            check(image.getPixel(5, 5) == surface) { "printed illustration lost its surface color" }
        } finally {
            image.recycle()
        }
    }
}

/** Actual isolated process termination, not a mock of coroutine timeout behavior. */
internal fun verifyIllustrationWorkerLifecycle(context: Context) = runBlocking {
    RestartingIllustrationWorker(
        context,
        IsolatedIllustrationProbeService::class.java,
        16
    ).use { worker ->
        for (source in listOf("hang", "crash")) {
            check(worker.render(source, true) is IllustrationResult.Fallback)
            check(
                worker.render("ready", false) ==
                    IllustrationResult.Fallback(IllustrationFailure.Unsupported)
            ) { "a failed source poisoned later requests" }
        }
        worker.releaseInstance()
        check(
            worker.render("ready", false) ==
                IllustrationResult.Fallback(IllustrationFailure.Unsupported)
        ) { "foregrounding could not acquire a fresh worker" }
    }
    fun connection() =
        IllustrationWorkerConnection(context, IsolatedIllustrationProbeService::class.java, 16)
    for (failure in listOf("hang", "crash")) {
        connection().use { worker ->
            check(
                worker.render("ready", false) ==
                    IllustrationResult.Fallback(IllustrationFailure.Unsupported)
            )
            val started = android.os.SystemClock.elapsedRealtime()
            check(
                withTimeout(5_000) {
                    worker.render(failure, true)
                } is IllustrationResult.Fallback
            )
            check(android.os.SystemClock.elapsedRealtime() - started < 4_000) {
                "worker death relied on the longer client deadline"
            }
        }
    }
    connection().use { worker ->
        check(worker.render("ready", false) is IllustrationResult.Fallback)
        val job = async { worker.render("hang", true) }
        delay(30)
        withTimeout(1_000) { job.cancelAndJoin() }
        check(job.isCancelled)
        check(
            worker.render("ready", false) ==
                IllustrationResult.Fallback(IllustrationFailure.Unavailable)
        )
    }
    connection().use { worker ->
        check(
            worker.render("ready", false) ==
                IllustrationResult.Fallback(IllustrationFailure.Unsupported)
        )
    }
}

/** Exercises actual isolated instances, immutable descriptor publication, and shared PDF output. */
internal fun verifyNativeMath(context: Context) = runBlocking {
    IllustrationPacketBuffer.create().use { buffer ->
        buffer.duplicateWriter().use { writer ->
            val byte = byteArrayOf(0)
            check(
                runCatching {
                    android.system.Os.pwrite(
                        writer.fileDescriptor,
                        byte,
                        0,
                        1,
                        IllustrationLimits.MAX_PACKET_BYTES.toLong()
                    )
                }.isFailure
            ) { "worker could grow its output" }
            check(runCatching { android.system.Os.ftruncate(writer.fileDescriptor, 0) }.isFailure)
            val header = IllustrationLimits.HEADER_BYTES
            android.system.Os.pwrite(writer.fileDescriptor, ByteArray(header), 0, header, 0)
            check(buffer.readCompleted(header).size == header)
            check(
                runCatching {
                    android.system.Os.pwrite(writer.fileDescriptor, byte, 0, 1, 0)
                }.isFailure
            ) {
                "published output remained writable through a retained worker descriptor"
            }
        }
    }
    coroutineScope {
        List(2) {
            async {
                IllustrationWorkerConnection(
                    context,
                    IsolatedMathService::class.java,
                    IllustrationLimits.MAX_MATH_SOURCE_BYTES
                ).use { worker ->
                    for (formula in listOf(
                        "x^2+y_1",
                        "\\frac{1}{2}",
                        "\\sqrt[3]{x}",
                        "\\int_0^1 x^2 dx"
                    )) {
                        val result = worker.render(formula, true)
                        check(result is IllustrationResult.Rendered) {
                            "native math failed: $result"
                        }
                        check(result.drawing.paths.isNotEmpty())
                    }
                    check(
                        worker.render("\\includegraphics{x}", true) ==
                            IllustrationResult.Fallback(IllustrationFailure.Unsupported)
                    )
                    check(worker.render("x", false) is IllustrationResult.Rendered) {
                        "an unsupported expression poisoned subsequent formulas"
                    }
                }
            }
        }.awaitAll()
    }
    val preview = renderNativeMathFixture(context)
    check(
        preview.blocks.flatMap {
            it.spans
        }.count { it.illustration is IllustrationResult.Rendered } ==
            6
    )
    check(
        preview.blocks.flatMap {
            it.spans
        }.count { it.illustration is IllustrationResult.Fallback } ==
            1
    )
    val attributes = PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setResolution(PrintAttributes.Resolution("math", "Math", 144, 144))
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .build()
    val settings = defaultPrintSettings(true)
    val root = mathArtifacts(context)
    File(root, "native-math.pdf").outputStream().use { output ->
        val result = renderMarkdownDocumentPdf(
            output,
            preview,
            "Mathematics.md",
            printRasterLayout(attributes, settings),
            settings,
            arrayOf(PageRange.ALL_PAGES),
            context.resources
        )
        check(result.totalPageCount > 0)
    }
    val mixedSource = """
        # Formulas with text

        English العربية ${'$'}x^2${'$'} עברית end

        | Expression | Result |
        | --- | --- |
        | ${'$'}\frac{1}{2}${'$'} | Half |
        | ${'$'}\sqrt{4}${'$'} | Two |
    """.trimIndent()
    val mixedPreview = renderNativeMathFixture(context, mixedSource)
    check(
        mixedPreview.blocks.flatMap { it.spans }.count {
            it.illustration is IllustrationResult.Rendered
        } == 3
    )
    val mixedPdf = File(root, "native-math-bidi-table.pdf")
    mixedPdf.outputStream().use { output ->
        renderMarkdownDocumentPdf(
            output,
            mixedPreview,
            "Mixed mathematics.md",
            printRasterLayout(attributes, settings),
            settings,
            arrayOf(PageRange.ALL_PAGES),
            context.resources
        )
    }
    PdfRenderer(ParcelFileDescriptor.open(mixedPdf, ParcelFileDescriptor.MODE_READ_ONLY)).use {
        it.openPage(0).use { page ->
            for (term in listOf("العربية", "עברית", "English", "Half", "Two", "x^2")) {
                check(page.searchText(term).isNotEmpty()) {
                    "illustrated PDF lost searchable text: $term"
                }
            }
        }
    }
}

/** Verifies that multiline display math produces visible Compose content and exact source actions. */
internal fun Instrumentation.verifyNativeMathUi() = verifyIllustrationUi(
    nativeMathSource,
    "Mathematics.md",
    "Formula: \n\\sum",
    "Copy formula source",
    "\n\\sum_{n=1}^{\\infty}\\frac{1}{n^2}=\\frac{\\pi^2}{6}\n",
    "native-math-preview.webp"
)

internal fun Instrumentation.verifyNativeDiagramUi() = verifyIllustrationUi(
    nativeDiagramSource,
    "Diagrams.md",
    "Diagram: flowchart",
    "Copy code",
    "$flowchartSource\n",
    "native-diagram-preview.webp"
)

internal fun Instrumentation.verifyNativeDiagramParserUi() = verifyIllustrationUi(
    "# Parser recovery\n\n```mermaid\n$sequenceEmptyCommentRegression\n```\n\n" +
        "```mermaid\nsequenceDiagram\nAlice->>Bob: Ready\n```",
    "Parser recovery.md",
    "Diagram: sequenceDiagram",
    "Copy code",
    "$sequenceEmptyCommentRegression\n",
    "native-diagram-parser-recovery.webp",
    expectedEditedSource = "sequenceDiagram\nAlice->>Bob: Ready\n",
    requiredMessage = "Shown as source—"
)

internal fun Instrumentation.verifyNativeDiagramFamiliesUi() {
    extraDiagramSources.forEach { (name, source) ->
        verifyIllustrationUi(
            "# $name\n\n```mermaid\n$source\n```",
            "$name.md",
            if (name ==
                "Languages"
            ) {
                "Diagram: Read in your language"
            } else {
                "Diagram: ${source.lineSequence().first()}"
            },
            "Copy code",
            "$source\n",
            "native-${name.lowercase()}-preview.webp"
        )
    }
}

private fun Instrumentation.verifyIllustrationUi(
    source: String,
    title: String,
    descriptionPrefix: String,
    copyDescription: String,
    expectedCopy: String,
    screenshot: String,
    expectedEditedSource: String = expectedCopy,
    requiredMessage: String? = null
) {
    val document = RustDocument.createEmpty()
    val metrics = document.replace(0L, Utf16Range(0, 0), source)
    val session = EditorSession(
        title = title,
        state = EditorDocumentState(document, initialRevision = metrics.revision),
        initialPresentation = EditorPresentation.MarkdownPreview,
        markdownRenderer = IsolatedMarkdownRenderer(targetContext)
    )
    val activity = startActivitySync(
        Intent(targetContext, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    ) as HomeActivity
    try {
        runBlocking {
            withContext(Dispatchers.Main) {
                activity.setContent {
                    BeauTyXTTheme {
                        Box(Modifier.fillMaxSize()) {
                            DocumentEditor(session, activity::finish, closesDocumentTask = false)
                        }
                    }
                }
                session.openInitialEditor()
                withTimeout(15_000) {
                    snapshotFlow {
                        session.markdownPreviewStatus
                    }.first { it is MarkdownPreviewStatus.Ready }
                }
            }
        }
        waitForAccessibilityIdle()
        waitForAccessibilityNode("illustration source description") {
            it.contentDescription?.toString()?.startsWith(descriptionPrefix) == true
        }
        waitForAccessibilityNode("all visible illustrations settled") { node ->
            node.parent == null &&
                node.findAccessibilityNodeInfosByText("Rendering…").none { it.isVisibleToUser }
        }
        if (requiredMessage != null) {
            waitForAccessibilityNode("illustration fallback explains source presentation") {
                it.text?.toString()?.startsWith(requiredMessage) == true && it.isVisibleToUser
            }
        }
        // These opt-in visual fixtures follow copy interactions. Let the system clipboard
        // confirmation finish before capturing; it is not part of BeauTyXT's layout.
        android.os.SystemClock.sleep(6_000)
        // One emulator sample while visible, not a peak or a real-device memory claim.
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        File(mathArtifacts(targetContext), screenshot.removeSuffix(".webp") + "-memory.txt")
            .writeText(
                "appPssKiB=${memory.totalPss}\n" +
                    "nativeAllocatedBytes=${Debug.getNativeHeapAllocatedSize()}\n" +
                    "javaUsedBytes=${Runtime.getRuntime().totalMemory() -
                        Runtime.getRuntime().freeMemory()}\n"
            )
        val screen = checkNotNull(uiAutomation.takeScreenshot())
        File(mathArtifacts(targetContext), screenshot).outputStream().use {
            check(screen.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it))
        }
        screen.recycle()
        if (descriptionPrefix.startsWith("Diagram:")) {
            fun clickDescription(description: String) {
                var control = waitForAccessibilityNode(description) {
                    it.contentDescription?.toString() == description
                }
                while (!control.isClickable && control.parent != null) control = control.parent
                check(
                    control.performAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                    )
                )
                waitForAccessibilityIdle()
            }
            clickDescription("Show diagram at text size")
            waitForAccessibilityNode("diagram can return to fitted overview") {
                it.contentDescription?.toString() == "Fit diagram to width"
            }
            val enlarged = checkNotNull(uiAutomation.takeScreenshot())
            File(
                mathArtifacts(targetContext),
                screenshot.removeSuffix(".webp") + "-text-size.webp"
            ).outputStream().use {
                check(enlarged.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it))
            }
            enlarged.recycle()
            check(session.presentation == EditorPresentation.MarkdownPreview)
            clickDescription("Fit diagram to width")
        }
        val copy = waitForAccessibilityNode("copy illustration") {
            it.contentDescription?.toString() == copyDescription
        }
        var copyControl = copy
        while (!copyControl.isClickable &&
            copyControl.parent != null
        ) {
            copyControl = copyControl.parent
        }
        check(copyControl.isEnabled) { "formula copy is disabled" }
        check(
            copyControl.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        ) {
            "formula copy control rejected click"
        }
        waitForAccessibilityIdle()
        runOnMainSync {
            val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
            check(
                clipboard.primaryClip?.getItemAt(0)?.text?.toString() ==
                    expectedCopy
            ) { "formula clipboard text differs from complete original TeX" }
        }
        val illustration = waitForAccessibilityNode("illustration edit-source action") { node ->
            node.contentDescription?.toString()?.startsWith(descriptionPrefix) == true &&
                node.actionList.any { it.label?.toString() == "Edit source text" }
        }
        val action = illustration.actionList.single { it.label?.toString() == "Edit source text" }
        check(illustration.performAction(action.id))
        waitForAccessibilityNode("complete illustrated source opens at its source anchor") { node ->
            node.isEditable && node.text?.toString()?.contains(expectedEditedSource) == true &&
                node.textSelectionStart == node.text.toString().indexOf(expectedEditedSource)
        }
        runBlocking {
            withContext(Dispatchers.Main) {
                check(session.state.metrics?.revision == metrics.revision)
                check(session.showMarkdownPreview())
                withTimeout(15_000) {
                    snapshotFlow {
                        session.markdownPreviewStatus
                    }.first { it is MarkdownPreviewStatus.Ready }
                }
            }
        }
        waitForAccessibilityNode("illustration survives editing-mode round trip") {
            it.contentDescription?.toString()?.startsWith(descriptionPrefix) == true
        }
    } catch (failure: Throwable) {
        uiAutomation.takeScreenshot()?.let { screen ->
            try {
                File(
                    mathArtifacts(targetContext),
                    screenshot.removeSuffix(".webp") + "-failure.webp"
                )
                    .outputStream().use {
                        check(screen.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it))
                    }
            } finally {
                screen.recycle()
            }
        }
        throw failure
    } finally {
        runOnMainSync {
            activity.getSystemService(android.content.ClipboardManager::class.java)
                .clearPrimaryClip()
            session.close()
            activity.finish()
        }
    }
}

private suspend fun renderNativeMathFixture(
    context: Context,
    source: String = nativeMathSource
): MarkdownPreviewDocument = RustDocument.createEmpty().use { document ->
    val metrics = document.replace(0L, Utf16Range(0, 0), source)
    document.captureSnapshot(metrics.revision).use { snapshot ->
        IsolatedMarkdownRenderer(context).render(snapshot, metrics.serializedByteLength)
    }
}

private fun mathArtifacts(context: Context): File =
    File(checkNotNull(context.getExternalFilesDir(null)), "native-math-verification").also {
        check(it.mkdirs() || it.isDirectory)
    }
