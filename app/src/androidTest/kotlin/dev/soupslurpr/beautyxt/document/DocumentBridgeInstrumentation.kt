/* Verifies packaged Rust bridges and document workflows on Android. */
package dev.soupslurpr.beautyxt.document

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.print.PageRange
import android.print.PrintAttributes
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.util.Log
import androidx.compose.ui.graphics.Color
import dev.soupslurpr.beautyxt.exporting.client.DocumentExportException
import dev.soupslurpr.beautyxt.exporting.client.DocumentExportFailure
import dev.soupslurpr.beautyxt.exporting.client.ExportSinkStatus
import dev.soupslurpr.beautyxt.exporting.client.IsolatedDocumentExporter
import dev.soupslurpr.beautyxt.exporting.client.SourceDocumentExporter
import dev.soupslurpr.beautyxt.exporting.client.SourceSaveReceipt
import dev.soupslurpr.beautyxt.exporting.client.StagedSourceSavePackage
import dev.soupslurpr.beautyxt.exporting.client.StatelessExportTestSinks
import dev.soupslurpr.beautyxt.exporting.client.TransientDestinationSelection
import dev.soupslurpr.beautyxt.exporting.client.TransientExportDestination
import dev.soupslurpr.beautyxt.exporting.client.TransientSourceDescriptor
import dev.soupslurpr.beautyxt.importing.IImportCallback
import dev.soupslurpr.beautyxt.importing.IImportService
import dev.soupslurpr.beautyxt.importing.ImportProtocol
import dev.soupslurpr.beautyxt.importing.IsolatedImportService
import dev.soupslurpr.beautyxt.importing.client.DocumentImportException
import dev.soupslurpr.beautyxt.importing.client.DocumentImportFailure
import dev.soupslurpr.beautyxt.importing.client.ImportedDocument
import dev.soupslurpr.beautyxt.importing.client.ImportedSourceAccess
import dev.soupslurpr.beautyxt.importing.client.IsolatedDocumentImporter
import dev.soupslurpr.beautyxt.importing.client.SelectedDocumentSource
import dev.soupslurpr.beautyxt.importing.client.StatelessImportTestSources
import dev.soupslurpr.beautyxt.importing.client.isCompatibleAutosaveDescriptor
import dev.soupslurpr.beautyxt.importing.client.querySelectedDocumentDisplayName
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownExternalAction
import dev.soupslurpr.beautyxt.markdown.MarkdownLinkAction
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import dev.soupslurpr.beautyxt.markdown.MarkdownQuoteKind
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.MarkdownTableAlignment
import dev.soupslurpr.beautyxt.markdown.client.IsolatedMarkdownRenderer
import dev.soupslurpr.beautyxt.markdown.markdownLinkAction
import dev.soupslurpr.beautyxt.printing.PrintContentMode
import dev.soupslurpr.beautyxt.printing.PrintMargins
import dev.soupslurpr.beautyxt.printing.PrintPageSelection
import dev.soupslurpr.beautyxt.printing.PrintRasterLayout
import dev.soupslurpr.beautyxt.printing.PrintRenderResult
import dev.soupslurpr.beautyxt.printing.PrintSettings
import dev.soupslurpr.beautyxt.printing.WrittenPageRanges
import dev.soupslurpr.beautyxt.printing.defaultPrintSettings
import dev.soupslurpr.beautyxt.printing.printRasterLayout
import dev.soupslurpr.beautyxt.printing.renderDocumentTextPdf
import dev.soupslurpr.beautyxt.printing.renderMarkdownDocumentPdf
import dev.soupslurpr.beautyxt.removeLegacyPrivateState
import dev.soupslurpr.beautyxt.sharing.verifyIncomingDocumentIntents
import dev.soupslurpr.beautyxt.transfer.client.IsolatedTransferProcessor
import dev.soupslurpr.beautyxt.transfer.client.QrLuminanceFrame
import dev.soupslurpr.beautyxt.ui.editor.EXPORTED_QR_CODE_SIDE_PIXELS
import dev.soupslurpr.beautyxt.ui.editor.QrCodeColors
import dev.soupslurpr.beautyxt.ui.editor.QrImageDestinationContract
import dev.soupslurpr.beautyxt.ui.editor.QrImageDestinationRequest
import dev.soupslurpr.beautyxt.ui.editor.QrImageFormat
import dev.soupslurpr.beautyxt.ui.editor.SelectedEditorDocumentSource
import dev.soupslurpr.beautyxt.ui.editor.markdownExternalLinkIntent
import dev.soupslurpr.beautyxt.ui.editor.saveExpressiveQrCodeImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

private const val TEST_REPLACEMENT = "hello 😀\nsecond"
private const val TEST_FIND_QUERY = "SECOND"
private const val TEST_MARKDOWN_SOURCE =
    "# Private preview\n\n**Strong** and *emphasis*.\n\n" +
        "- [x] local\n\n```rust\nlet safe = true;\n```\n\n" +
        "<p>Safe <strong>semantic HTML</strong>.</p>\n\n" +
        "Markdown with <span>inline HTML</span>.\n\n" +
        "Unicode [café 😀](https://example.com/path?q=1) and " +
        "![solar ☀](https://example.com/image.png).\n\n" +
        "<script>never executed</script>\n\n" +
        "Body[^note]\n\n[^note]: Preview data is transient.\n\n" +
        "| A | B |\n| - | - |\n| 1 | 2 |\n\n| C |\n| - |\n| 3 |\n\n" +
        "<div><table><tr><td>D</td></tr><tr><td>4</td></tr></table>" +
        "<table><tr><td>E</td></tr><tr><td>5</td></tr></table></div>\n"
private const val TEST_POST_RECEIPT_REPLACEMENT = "hello 😀\nsecond!"
private const val TEST_QR_IMAGE_TEXT = "# QR image\n\nLocal and private."
private const val TEST_QR_IMAGE_NAME = "beautyxt-qr-image-test"
private const val TEST_PRINT_PDF_NAME = "beautyxt-print-test.pdf"
private const val TEST_PRINT_SELECTION_PDF_NAME = "beautyxt-print-selection-test.pdf"
private const val TEST_MARKDOWN_PRINT_PDF_NAME = "beautyxt-markdown-print-test.pdf"
private const val TEST_MULTIPAGE_MARKDOWN_PRINT_PDF_NAME =
    "beautyxt-markdown-multipage-test.pdf"
private const val TEST_MULTIPAGE_MARKDOWN_SELECTION_PDF_NAME =
    "beautyxt-markdown-multipage-selection-test.pdf"
private const val TEST_MULTIPAGE_MARKDOWN_REPEAT_PDF_NAME =
    "beautyxt-markdown-multipage-repeat-test.pdf"
private const val PDF_MIME_TYPE = "application/pdf"
private const val TEST_PRINT_LINE_COUNT = 180
private const val TEST_PRINT_MEDIA_MILS = 3_000
private const val TEST_PRINT_DPI = 144
private const val TEST_PRINT_RENDER_SIDE_PIXELS = 600
private const val TEST_PRINT_DARK_CHANNEL_LIMIT = 220
private const val TEST_MULTIPAGE_MARKDOWN_BLOCK_LINES = 40
private const val TEST_MULTIPAGE_MARKDOWN_TABLE_ROWS = 24
private const val TEST_MULTIPAGE_MARKDOWN_WIDE_COLUMNS = 17
private const val TEST_MULTIPAGE_MARKDOWN_MINIMUM_PAGES = 8
private const val TEST_MULTIPAGE_MARKDOWN_MAXIMUM_PAGES = 64
private const val TEST_MULTIPAGE_MARKDOWN_CANCEL_CHECKPOINT = 8
private const val TEST_INITIAL_REVISION = 0L
private const val TEST_EDITED_REVISION = 1L
private const val TEST_MAX_BLOCKS = 4
private const val TEST_IMPORT_VIEWPORT_MAX_BLOCKS = 5
private const val TEST_MAX_BLOCK_UTF16_UNITS = 32
private const val TEST_MAX_TOTAL_UTF16_UNITS = 64
private const val TEST_MAX_EDIT_WINDOW_UTF16_UNITS = 12
private const val TEST_SNAPSHOT_TIMEOUT_MILLIS = 10_000L
private const val LUMINANCE_RED_WEIGHT = 77
private const val LUMINANCE_GREEN_WEIGHT = 150
private const val LUMINANCE_BLUE_WEIGHT = 29
private const val LUMINANCE_ROUNDING_OFFSET = 128
private const val LUMINANCE_WEIGHT_SHIFT = 8
private const val TEST_SELECTION_START_UTF16 = 6L
private const val TEST_SELECTION_END_UTF16 = 8L
private const val TEST_FIND_MATCH_START_UTF16 = 9L
private const val TEST_FIND_MATCH_END_UTF16 = 15L
private const val TEST_SNAPSHOT_EDIT_END_UTF16 = 1L
private const val TEST_SNAPSHOT_REPLACEMENT = "H"
private const val TEST_CONFLICT_EDIT_START_UTF16 = 1L
private const val TEST_CONFLICT_EDIT_END_UTF16 = 2L
private const val TEST_CONFLICT_REPLACEMENT = "J"
private const val TEST_EXTERNAL_SOURCE_LOGICAL_OFFSET = 2
private const val TEST_EXTERNAL_SOURCE_REPLACEMENT: Byte = 0x5a
private const val TEST_CONFLICT_SAVE_ATTEMPTS = 2
private const val TEST_IMPORT_JOB_ID = 1L
private const val TEST_PROVIDER_ERROR_JOB_ID = 2L
private const val TEST_IMPORT_MAX_BYTES = 4L * 1024L
private const val TEST_IMPORT_TIMEOUT_MILLIS = 10_000L
private const val TEST_BIND_TIMEOUT_MILLIS = 10_000L
private const val TEST_CALLBACK_TIMEOUT_MILLIS = 15_000L
private const val TEST_PROFILE_VERIFICATION_TIMEOUT_MILLIS = 15_000L
private const val TEST_SUITE_TIMEOUT_MILLIS = 240_000L
private const val TEST_STATUS_STARTED = 1
private const val TEST_STATUS_PASSED = 0
private const val TEST_STATUS_FAILED = -2
private const val TEST_CLEANUP_TIMEOUT_MILLIS = 2_000L
private const val TEST_INPUT_BUFFER_NAME = "beautyxt-test-input"
private const val TEST_OUTPUT_BUFFER_NAME = "beautyxt-test-output"
private const val TEST_PROVIDER_OUTPUT_BUFFER_NAME = "beautyxt-test-provider-output"
private const val TEST_PROVIDER_ERROR_MESSAGE = "synthetic provider failure"
private const val TEST_SELECTED_DOCUMENT_PREFIX = "beautyxt-selected-document-"
private const val TEST_CREATED_DOCUMENT_PREFIX = "beautyxt-created-document-"
private const val TEST_IMPORT_SOURCE_BODY = "alpha\r\nbeta\rgamma\ndelta"
private const val TEST_IMPORT_LOGICAL_BODY = "alpha\nbeta\ngamma\ndelta"
private const val TEST_LARGE_SOURCE_BYTES = 16L * 1024L * 1024L
private const val TEST_LARGE_SOURCE_SEPARATOR: Byte = 0x0a
private const val TEST_LARGE_SOURCE_FILL: Byte = 0x78
private const val TEST_LARGE_DIRTY_OFFSET = 33L
private const val TEST_LARGE_DIRTY_REPLACEMENT = "y"
private const val TEST_OPERATION_TOKEN_BYTES = 16
private const val TEST_OPERATION_RANDOM_SEED = 0L
private const val TEST_PROVIDER_STATE_TIMEOUT_MILLIS = 15_000L
private const val TEST_PROVIDER_POLL_MILLIS = 10L
private const val TEST_IMPORT_PROCESS_SUFFIX = ":import"
private const val TEST_PROCESS_COMPONENT_SEPARATOR = ":"
private const val TEST_PROCESS_LOOKUP_COMMAND = "pgrep -f"
private const val TEST_PROCESS_CRASH_COMMAND = "am crash --user current"
private const val TEST_HASH_ALGORITHM = "SHA-256"
private const val TEST_LOWERCASE_HEX_DIGITS = "0123456789abcdef"
private const val TEST_FILE_READ_BUFFER_BYTES = 16 * 1024
private const val TEST_RUNTIME_CODE_CACHE_DIRECTORY = "code_cache"
private const val TEST_LEGACY_CLEANUP_ROOT = "beautyxt-legacy-cleanup-test"
private const val TEST_LEGACY_WEBVIEW_HISTORY = "app_webview/Default/History"
private const val TEST_LEGACY_DATABASE_ENTRY = "databases/legacy.db"
private const val TEST_LEGACY_DATASTORE_ENTRY = "files/datastore/settings.preferences_pb"
private const val TEST_LEGACY_SHARED_PREFERENCE_ENTRY = "shared_prefs/legacy.xml"
private const val TEST_PRESERVED_PRIVATE_ENTRY = "preserved/current-runtime-state"
private const val TEST_LEGACY_STAGE_PREFIX = ".beautyxt-import-"
private const val TEST_CREDENTIAL_STORAGE_ROOT = "credential"
private const val TEST_DEVICE_STORAGE_ROOT = "device"
private const val TEST_EXTERNAL_STORAGE_ROOT_PREFIX = "external"
private const val TEST_IMPORT_BUFFER_LINK_MARKER = "memfd:beautyxt-import"
private const val TEST_EXPORT_STAGE_LINK_MARKER = "memfd:beautyxt-source-save"
private const val TEST_SOURCE_BACKING_NAME = "beautyxt-source-save-backing"
private const val TEST_SOURCE_BACKING_LINK_MARKER = "memfd:$TEST_SOURCE_BACKING_NAME"
private const val TEST_SOURCE_BACKED_PACKAGE_BYTES = 256 * 1024
private const val TEST_SOURCE_BACKED_PACKAGE_RECORDS = 3L
private const val TEST_SOURCE_BACKED_PACKAGE_PAYLOAD_BYTES = 1L
private const val TEST_SOURCE_SAVE_PACKAGE_HEADER_BYTES = 64L
private const val TEST_SOURCE_SAVE_PACKAGE_RECORD_BYTES = 24L
private const val TEST_ACTIVE_IMPORT_BUFFER_DESCRIPTORS = 1
private const val TEST_MFD_ALLOW_SEALING = 0x0002
private const val TEST_F_GET_SEALS = 1034
private const val TEST_F_SEAL_SEAL = 0x0001
private const val TEST_F_SEAL_SHRINK = 0x0002
private const val TEST_F_SEAL_GROW = 0x0004
private const val TEST_F_SEAL_WRITE = 0x0008
private const val TEST_REQUIRED_EXPORT_STAGE_SEALS =
    TEST_F_SEAL_SEAL or TEST_F_SEAL_SHRINK or TEST_F_SEAL_GROW or TEST_F_SEAL_WRITE
private const val TEST_MAX_SHELL_OUTPUT_CHARS = 1024
private const val TEST_MAX_REPORTED_CAUSES = 8
private const val TEST_MAX_REPORTED_STORAGE_CHANGES = 16
private const val TEST_INITIAL_VERIFICATION_PHASE = "instrumentation startup"
private const val TEST_PHASE_ARGUMENT = "phase"
private const val TEST_RETAIN_PRINT_ARTIFACTS_ARGUMENT = "retainPrintArtifacts"
private const val TEST_PROFILE_VERIFIER_CLASS_NAME =
    "androidx.profileinstaller.ProfileVerifier"
private const val TEST_PROFILE_VERIFIER_METHOD_NAME = "getCompilationStatusAsync"
private const val TEST_EXPECTED_SOURCE_FLAGS =
    ImportProtocol.SOURCE_FLAG_UTF8_BOM or
        ImportProtocol.SOURCE_FLAG_CRLF or
        ImportProtocol.SOURCE_FLAG_BARE_CR or
        ImportProtocol.SOURCE_FLAG_BARE_LF
private val TEST_UTF8_BOM = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
private val TEST_OPERATION_TOKEN =
    ByteArray(TEST_OPERATION_TOKEN_BYTES).also { token ->
        Random(TEST_OPERATION_RANDOM_SEED).nextBytes(token)
    }
private val TEST_OPERATION_TOKEN_HEX = TEST_OPERATION_TOKEN.toLowercaseHex()
private val TEST_OPERATION_TOKEN_HEX_BYTES = TEST_OPERATION_TOKEN_HEX.toByteArray(Charsets.UTF_8)
private val TEST_OPERATION_TOKEN_BINARY_DIGEST =
    MessageDigest.getInstance(TEST_HASH_ALGORITHM).digest(TEST_OPERATION_TOKEN)
private val TEST_OPERATION_TOKEN_TEXT_DIGEST =
    MessageDigest.getInstance(TEST_HASH_ALGORITHM).digest(TEST_OPERATION_TOKEN_HEX_BYTES)
private val TEST_OPERATION_TOKEN_BINARY_DIGEST_HEX =
    TEST_OPERATION_TOKEN_BINARY_DIGEST.toLowercaseHex()
private val TEST_OPERATION_TOKEN_TEXT_DIGEST_HEX =
    TEST_OPERATION_TOKEN_TEXT_DIGEST.toLowercaseHex()
private val TEST_FORBIDDEN_STORAGE_PATTERNS =
    listOf(
        TEST_OPERATION_TOKEN,
        TEST_OPERATION_TOKEN_HEX_BYTES,
        TEST_OPERATION_TOKEN_BINARY_DIGEST,
        TEST_OPERATION_TOKEN_BINARY_DIGEST_HEX.toByteArray(Charsets.UTF_8),
        TEST_OPERATION_TOKEN_TEXT_DIGEST,
        TEST_OPERATION_TOKEN_TEXT_DIGEST_HEX.toByteArray(Charsets.UTF_8)
    )
private val TEST_FORBIDDEN_STORAGE_NAME_PARTS =
    listOf(
        TEST_OPERATION_TOKEN_HEX,
        TEST_OPERATION_TOKEN_BINARY_DIGEST_HEX,
        TEST_OPERATION_TOKEN_TEXT_DIGEST_HEX
    )
private val TEST_SELECTED_DOCUMENT_NAME =
    "$TEST_SELECTED_DOCUMENT_PREFIX$TEST_OPERATION_TOKEN_HEX.txt"
private val TEST_CREATED_DOCUMENT_NAME =
    "$TEST_CREATED_DOCUMENT_PREFIX$TEST_OPERATION_TOKEN_HEX.txt"
private val TEST_IMPORT_SOURCE_TEXT = "$TEST_OPERATION_TOKEN_HEX\n$TEST_IMPORT_SOURCE_BODY"
private val TEST_IMPORT_LOGICAL_TEXT = "$TEST_OPERATION_TOKEN_HEX\n$TEST_IMPORT_LOGICAL_BODY"
private val TEST_IMPORT_INPUT_BYTES =
    TEST_UTF8_BOM + TEST_IMPORT_SOURCE_TEXT.toByteArray(Charsets.UTF_8)
private val TEST_PROVIDER_INPUT_BYTES =
    "$TEST_OPERATION_TOKEN_HEX\ncomplete-looking prefix".toByteArray(Charsets.UTF_8)

private const val TAG = "DocumentBridgeInstrumentation"

/** Exercises the packaged Rust bridges and isolated import service on Android. */
class DocumentBridgeInstrumentation : Instrumentation() {
    @Volatile
    private var currentVerificationPhase = TEST_INITIAL_VERIFICATION_PHASE
    private var selectedVerificationPhase: String? = null
    private var retainPrintArtifacts = false
    private var executedVerificationPhases = 0

    override fun onCreate(arguments: Bundle?) {
        selectedVerificationPhase = arguments?.getString(TEST_PHASE_ARGUMENT)
        retainPrintArtifacts =
            arguments?.getString(TEST_RETAIN_PRINT_ARTIFACTS_ARGUMENT)?.toBooleanStrict() ?: false
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val results = Bundle()
        // Report the selected verification batch using Android's test-result protocol.
        // A finished instrumentation process alone is not evidence that a test ran.
        val status = Bundle().apply {
            putString("class", DocumentBridgeInstrumentation::class.java.name)
            putString("test", selectedVerificationPhase ?: "document workflows")
            putInt("numtests", 1)
            putInt("current", 1)
        }
        sendStatus(TEST_STATUS_STARTED, status)
        val resultCode =
            try {
                verifyBridgesOnBackgroundThread()
                results.putString(
                    REPORT_STREAM_KEY,
                    if (selectedVerificationPhase == null) {
                        "all document bridge and Android workflow verification phases passed"
                    } else {
                        "verification passed: $selectedVerificationPhase"
                    }
                )
                sendStatus(TEST_STATUS_PASSED, status)
                Activity.RESULT_OK
            } catch (throwable: Throwable) {
                val report = failureReport(throwable)
                results.putString(REPORT_STREAM_KEY, report)
                status.putString("stack", report)
                sendStatus(TEST_STATUS_FAILED, status)
                Activity.RESULT_CANCELED
            }
        finish(resultCode, results)
    }

    /** Runs every blocking bridge check away from the application main thread. */
    private fun verifyBridgesOnBackgroundThread() {
        val executor =
            Executors.newSingleThreadExecutor(
                NamedThreadFactory("BeauTyXT bridge instrumentation")
            )
        val verification =
            executor.submit {
                check(Looper.myLooper() != Looper.getMainLooper()) {
                    "bridge verification must run off the main thread"
                }
                if (!retainPrintArtifacts) clearPrintVerificationArtifacts(targetContext)
                if (selectedVerificationPhase != null &&
                    selectedVerificationPhase != "runtime profile metadata"
                ) {
                    awaitRuntimeProfileMetadata()
                }
                verifyPhase("runtime profile metadata", ::awaitRuntimeProfileMetadata)
                verifyPhase("legacy private state cleanup", ::verifyLegacyPrivateStateCleanup)
                verifyPhase("legacy cleanup link boundaries", ::verifyLegacyCleanupLinkBoundaries)
                verifyOptInPhase("legacy cleanup timing", ::measureLegacyCleanupTraversal)
                verifyPhase("legacy cleanup presentation") { verifyLegacyCleanupPresentation() }
                verifyOptInPhase("legacy cleanup visuals") {
                    verifyLegacyCleanupPresentation(capturePreviews = true)
                }
                verifyPhase("license scroll insets", ::verifyNoticeScrollInsets)
                verifyPhase("document bridge", ::verifyDocumentBridge)
                verifyPhase("unique isolated instances") {
                    verifyUniqueServiceInstances(targetContext)
                }
                verifyOptInPhase("document save-size admission", ::verifyDocumentSaveLimit)
                verifyPhase("native source positions", ::verifyNativeSourcePositionNavigation)
                verifyPhase("read-only source position") { verifyReadOnlySourcePosition() }
                verifyOptInPhase("read-only source visuals") {
                    verifyReadOnlySourcePosition(capturePreview = true)
                }
                verifyPhase("isolated Markdown preview", ::verifyIsolatedMarkdownPreview)
                verifyPrintPhase("native math") { verifyNativeMath(targetContext) }
                verifyOptInPhase("native math UI") { verifyNativeMathUi() }
                verifyPrintPhase("native diagrams") { verifyNativeDiagrams(targetContext) }
                verifyPrintPhase("Markdown print pagination") {
                    verifyMarkdownPrintPagination(targetContext)
                }
                verifyOptInPhase("native diagram UI") { verifyNativeDiagramUi() }
                verifyOptInPhase("native diagram families UI") { verifyNativeDiagramFamiliesUi() }
                verifyOptInPhase("progressive illustrations UI") {
                    verifyProgressiveIllustrationsUi()
                }
                verifyPhase("illustration lifecycle") {
                    verifyIllustrationWorkerLifecycle(targetContext)
                }
                verifyPhase("diagram parser containment") {
                    verifyNativeDiagramParserContainment(targetContext)
                }
                verifyOptInPhase("diagram parser containment UI") { verifyNativeDiagramParserUi() }
                verifyPhase("wide Markdown tables", ::verifyWideMarkdownTables)
                verifyPhase("Markdown link dispatch", ::verifyMarkdownLinkIntents)
                verifyPhase("Markdown code copy") { verifyMarkdownCodeCopy() }
                verifyPhase("Markdown background taps") { verifyMarkdownBackgroundTaps() }
                verifyPhase("reading selection back") { verifyReadingSelectionBack() }
                verifyOptInPhase("Markdown code copy visuals") {
                    verifyMarkdownCodeCopy(capturePreviews = true)
                }
                verifyPhase("expressive QR image", ::verifyExpressiveQrImage)
                verifyPhase("QR image encodings") {
                    verifyQrImageEncodings(
                        targetContext,
                        luminance = { it.toQrLuminanceFrame() },
                        report = { Log.i(TAG, it) }
                    )
                }
                verifyOptInPhase("QR image benchmark") {
                    verifyQrImageEncodings(
                        targetContext,
                        luminance = { it.toQrLuminanceFrame() },
                        report = { Log.i(TAG, it) },
                        benchmark = true
                    )
                }
                verifyPhase("QR share dialog") { verifyQrShareDialog() }
                verifyOptInPhase("QR share visuals") { verifyQrShareDialog(capturePreview = true) }
                verifyOptInPhase("QR share compact visuals") {
                    check(uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90))
                    try {
                        verifyQrShareDialog(capturePreview = true)
                    } finally {
                        uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
                    }
                }
                verifyPhase("QR image cancellation") {
                    verifyQrImageExportCancellation(targetContext, context.packageName)
                }
                verifyPhase("NFC label dialog") { verifyNfcLabelDialog() }
                verifyOptInPhase("NFC label visuals") {
                    verifyNfcLabelDialog(capturePreview = true)
                }
                verifyPhase("UI resources") { verifyUiResources(targetContext) }
                verifyPhase("keyboard privacy hints") { verifyKeyboardPrivacy() }
                verifyPhase("streaming text PDF", ::verifyStreamingTextPdf)
                verifyPhase("formatted Markdown PDF", ::verifyFormattedMarkdownPdf)
                verifyPrintPhase("color glyph PDF") { verifyPrintColorGlyphs(targetContext) }
                verifyPrintPhase("vector PDF") { verifyVectorPrint(targetContext) }
                verifyOptInPhase("print font outlines") { verifyPrintFontOutlines(targetContext) }
                verifyPrintPhase("PDF text layer") { verifyPrintTextLayer(targetContext) }
                verifyPrintPhase("PDF fragment continuity") {
                    verifyPrintFragmentContinuity(targetContext)
                }
                verifyPrintPhase("Markdown print fragment continuity") {
                    verifyMarkdownPrintFragmentContinuity(targetContext)
                }
                verifyPhase("multipage Markdown PDF", ::verifyMultipageMarkdownPdf)
                verifyPhase(
                    "autosave descriptor classification",
                    ::verifyAutosaveDescriptorClassification
                )
                verifyPhase("incoming document intents", ::verifyIncomingDocumentIntents)
                verifyPhase("incoming review interactions", ::verifyIncomingReviewInteractions)
                verifyPhase("editor toolbar", ::verifyEditorToolbarInteractions)
                verifyPhase("compact editor controls", ::verifyCompactEditorControls)
                verifyPhase("compact print setup", ::verifyCompactPrintSetup)
                verifyPhase("short document edit recovery") { verifyShortDocumentEditRecovery() }
                verifyOptInPhase("edit recovery visuals") {
                    verifyShortDocumentEditRecovery(capturePreviews = true)
                }
                verifyPhase("edit scroll resize", ::verifyEditScrollRestorationAfterResize)
                verifyPhase("initial reading back", ::verifyInitialReadingBack)
                verifyPhase("Markdown recovery presentation") {
                    verifyMarkdownRecoveryPresentation()
                }
                verifyOptInPhase("Markdown recovery visuals") {
                    verifyMarkdownRecoveryPresentation(capturePreviews = true)
                }
                verifyPhase("composing editor history", ::verifyComposingHistoryInteractions)
                verifyPhase(
                    "editor history shortcut ownership",
                    ::verifyEditorHistoryShortcutOwnership
                )
                verifyPhase("rapid editor back", ::verifyRapidBackGuardsUnsavedDraft)
                verifyPhase("activity recreation", ::verifyEditorActivityRecreation)
                verifyPhase("scanner activity recreation", ::verifyScannerActivityRecreation)
                verifyOptInPhase("scanner camera ownership", ::verifyScannerCameraOwnership)
                verifyPhase(
                    "source-identity recreation privacy",
                    ::verifySourceIdentityRecreationPrivacy
                )
                verifyPhase("background source checkpoint", ::verifyBackgroundSourceCheckpoint)
                verifyOptInPhase(
                    "staging background source checkpoint",
                    ::verifyStagingBackgroundSourceCheckpoint
                )
                verifyOptInPhase(
                    "staging large background source checkpoint",
                    ::verifyStagingLargeBackgroundSourceCheckpoint
                )
                verifyOptInPhase(
                    "staging scanner process death",
                    ::verifyStagingScannerProcessDeath
                )
                verifyPhase("direct isolated import", ::verifyIsolatedImportService)
                verifyPhase("direct provider failure", ::verifyReliableProviderFailure)
                verifyPhase("selected document import", ::verifyProductionDocumentImporter)
                verifyPhase("created document source", ::verifyCreatedDocumentSource)
                verifyPhase("large seekable import", ::verifyLargeSeekableSource)
                verifyPhase("large streaming import", ::verifyLargeStreamingSource)
                verifyPhase("paused import completion", ::verifyPausedSourceCompletion)
                verifyPhase("production provider failure", ::verifyProductionProviderFailure)
                verifyPhase("production cancellation", ::verifyProductionCancellation)
                verifyPhase("isolated service death", ::verifyProductionServiceDeath)
                verifyPhase(
                    "snapshot descriptor ownership",
                    ::verifySnapshotPipeDescriptorOwnership
                )
                verifyPhase("transfer input ownership", ::verifyTransferInputOwnership)
                verifyPhase("shared text snapshots", ::verifySharedTextSnapshots)
                verifyPhase("empty exact export", ::verifyEmptyExactExport)
                verifyPhase("small exact export", ::verifySmallExactExport)
                verifyPhase("sealed source-save package", ::verifySealedSourceSavePackage)
                verifyPhase(
                    "complete-payload export failure",
                    ::verifyCompletePayloadExportFailure
                )
                verifyPhase("large exact export", ::verifyLargeExactExport)
                verifyPhase("production export failure", ::verifyProductionExportFailure)
                verifyPhase("production export cancellation", ::verifyProductionExportCancellation)
                check(selectedVerificationPhase == null || executedVerificationPhases == 1) {
                    "unknown verification phase: $selectedVerificationPhase"
                }
            }
        try {
            verification.get(TEST_SUITE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        } catch (failure: TimeoutException) {
            verification.cancel(true)
            throw IllegalStateException("bridge verification exceeded its time limit", failure)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("bridge verification was interrupted", failure)
        } finally {
            executor.shutdownNow()
        }
    }

    /** Verifies update cleanup removes only the private paths owned by legacy BeauTyXT. */
    private fun verifyLegacyPrivateStateCleanup() {
        val fixtureRoot = File(targetContext.cacheDir, TEST_LEGACY_CLEANUP_ROOT)
        check(!fixtureRoot.exists() || fixtureRoot.deleteRecursively()) {
            "legacy cleanup fixture could not be reset"
        }
        val removedEntries =
            listOf(
                TEST_LEGACY_WEBVIEW_HISTORY,
                TEST_LEGACY_DATABASE_ENTRY,
                TEST_LEGACY_DATASTORE_ENTRY,
                TEST_LEGACY_SHARED_PREFERENCE_ENTRY
            ).map { relativePath ->
                File(fixtureRoot, relativePath).also { entry ->
                    check(entry.parentFile?.mkdirs() != false) {
                        "legacy cleanup fixture directory could not be created"
                    }
                    entry.writeText(TEST_OPERATION_TOKEN_HEX)
                }
            }
        val preservedEntry =
            File(fixtureRoot, TEST_PRESERVED_PRIVATE_ENTRY).also { entry ->
                check(entry.parentFile?.mkdirs() != false) {
                    "preserved cleanup fixture directory could not be created"
                }
                entry.writeText("current runtime state")
            }
        try {
            removeLegacyPrivateState(fixtureRoot)
            check(removedEntries.none(File::exists)) {
                "legacy cleanup retained an obsolete private entry"
            }
            check(preservedEntry.readText() == "current runtime state") {
                "legacy cleanup changed unrelated private state"
            }
        } finally {
            check(!fixtureRoot.exists() || fixtureRoot.deleteRecursively()) {
                "legacy cleanup fixture could not be removed"
            }
        }
    }

    /** Verifies autosave accepts only regular, seekable, exact read-write descriptors. */
    private fun verifyAutosaveDescriptorClassification() {
        AnonymousTestBuffer.create("beautyxt-autosave-probe").use { buffer ->
            buffer.duplicate().use { descriptor ->
                val fileDescriptor = descriptor.fileDescriptor
                check(isCompatibleAutosaveDescriptor(fileDescriptor)) {
                    "regular read-write descriptor was rejected"
                }
                val openFlags = Os.fcntlInt(fileDescriptor, OsConstants.F_GETFL, 0)
                Os.fcntlInt(
                    fileDescriptor,
                    OsConstants.F_SETFL,
                    openFlags or OsConstants.O_APPEND
                )
                check(!isCompatibleAutosaveDescriptor(fileDescriptor)) {
                    "append descriptor was accepted for autosave"
                }
            }
        }

        val firstSocket = FileDescriptor()
        val secondSocket = FileDescriptor()
        try {
            Os.socketpair(
                OsConstants.AF_UNIX,
                OsConstants.SOCK_STREAM,
                0,
                firstSocket,
                secondSocket
            )
            val openFlags = Os.fcntlInt(firstSocket, OsConstants.F_GETFL, 0)
            check((openFlags and OsConstants.O_ACCMODE) == OsConstants.O_RDWR) {
                "socket test descriptor is not read-write"
            }
            check(!isCompatibleAutosaveDescriptor(firstSocket)) {
                "nonregular read-write descriptor was accepted for autosave"
            }
        } finally {
            if (firstSocket.valid()) {
                Os.close(firstSocket)
            }
            if (secondSocket.valid()) {
                Os.close(secondSocket)
            }
        }
    }

    /** Waits for delayed AndroidX performance-profile metadata to settle. */
    private fun awaitRuntimeProfileMetadata() {
        val verifierClass = Class.forName(TEST_PROFILE_VERIFIER_CLASS_NAME)
        val statusFuture =
            verifierClass
                .getMethod(TEST_PROFILE_VERIFIER_METHOD_NAME)
                .invoke(null) as? Future<*>
        checkNotNull(statusFuture) {
            "profile verifier returned an unexpected future type"
        }.get(
            TEST_PROFILE_VERIFICATION_TIMEOUT_MILLIS,
            TimeUnit.MILLISECONDS
        )
    }

    /** Records one named verification phase before running it. */
    private fun verifyPhase(name: String, action: () -> Unit) {
        require(name.isNotBlank()) { "verification phase name must not be blank" }
        if (selectedVerificationPhase?.let { selected -> selected != name } == true) {
            return
        }
        executedVerificationPhases = Math.incrementExact(executedVerificationPhases)
        currentVerificationPhase = name
        val startedAt = SystemClock.uptimeMillis()
        Log.i(TAG, "verifying $name")
        action()
        Log.i(TAG, "verified $name in ${SystemClock.uptimeMillis() - startedAt} ms")
    }

    /** Releases synthetic PDFs before later storage audits unless capture was requested. */
    private fun verifyPrintPhase(name: String, action: () -> Unit) {
        verifyPhase(name) {
            AutoCloseable {
                if (!retainPrintArtifacts) clearPrintVerificationArtifacts(targetContext)
            }.use {
                action()
            }
        }
    }

    /** Runs one explicitly selected phase outside the default suite. */
    private fun verifyOptInPhase(name: String, action: () -> Unit) {
        require(name.isNotBlank()) { "opt-in verification phase name must not be blank" }
        if (selectedVerificationPhase == name) {
            verifyPhase(name, action)
        }
    }

    /** Records one named suspending phase before running it. */
    private suspend fun <Result> verifySuspendingPhase(
        name: String,
        action: suspend () -> Result
    ): Result {
        require(name.isNotBlank()) { "verification phase name must not be blank" }
        currentVerificationPhase = name
        return action()
    }

    /** Returns one bounded phase, failure enum, and cause-chain report. */
    private fun failureReport(throwable: Throwable): String {
        val causes =
            generateSequence(throwable) { failure ->
                failure.cause?.takeUnless { cause -> cause === failure }
            }.take(TEST_MAX_REPORTED_CAUSES).toList()
        val importFailures =
            causes
                .filterIsInstance<DocumentImportException>()
                .map(DocumentImportException::failure)
                .distinct()
        val importFailureReport =
            if (importFailures.isEmpty()) {
                ""
            } else {
                "; import failures ${importFailures.joinToString()}"
            }
        val exportFailures =
            causes
                .filterIsInstance<DocumentExportException>()
                .map(DocumentExportException::failure)
                .distinct()
        val exportFailureReport =
            if (exportFailures.isEmpty()) {
                ""
            } else {
                "; export failures ${exportFailures.joinToString()}"
            }
        val causeReport =
            causes.joinToString(separator = " <- ") { failure ->
                val message = failure.message
                if (message.isNullOrBlank()) {
                    failure.javaClass.simpleName
                } else {
                    "${failure.javaClass.simpleName}: $message"
                }
            }
        return "bridge instrumentation failed during $currentVerificationPhase" +
            "$importFailureReport$exportFailureReport: $causeReport"
    }

    /** Verifies document creation, editing, and bounded viewport decoding. */
    private fun verifyDocumentBridge() {
        RustDocument.createEmpty().use { document ->
            val limits =
                ViewportLimits(
                    maxBlocks = TEST_MAX_BLOCKS,
                    maxBlockUtf16Units = TEST_MAX_BLOCK_UTF16_UNITS,
                    maxTotalUtf16Units = TEST_MAX_TOTAL_UTF16_UNITS
                )
            val emptySnapshot =
                document.viewport(
                    cursor =
                        ViewportCursor(
                            revision = TEST_INITIAL_REVISION,
                            line = 0,
                            utf16Offset = 0
                        ),
                    limits = limits
                )
            check(emptySnapshot.metrics.byteLength == 0L) {
                "empty document has a nonzero byte length"
            }
            check(emptySnapshot.blocks.single().text.isEmpty()) {
                "empty document returned nonempty text"
            }

            val revision =
                document.replace(
                    expectedRevision = TEST_INITIAL_REVISION,
                    range = Utf16Range(start = 0, end = 0),
                    replacement = TEST_REPLACEMENT
                ).revision
            check(revision == TEST_EDITED_REVISION) {
                "first replacement did not advance the revision"
            }

            val editedSnapshot =
                document.viewport(
                    cursor = ViewportCursor(revision = revision, line = 0, utf16Offset = 0),
                    limits = limits
                )
            check(editedSnapshot.metrics.revision == revision) {
                "viewport returned an unexpected revision"
            }
            check(editedSnapshot.blocks.map(RenderBlock::text) == listOf("hello 😀", "second")) {
                "viewport returned unexpected block text"
            }

            val findBatch =
                document.find(
                    FindRequest(
                        revision = revision,
                        query = TEST_FIND_QUERY,
                        candidateRange =
                            Utf16Range(
                                start = 0L,
                                end = editedSnapshot.metrics.utf16Length
                            ),
                        direction = FindDirection.Forward,
                        maxCandidateUtf16Units = TEST_MAX_TOTAL_UTF16_UNITS
                    )
                )
            val findMatch = checkNotNull(findBatch.match) {
                "bounded find returned no expected match"
            }
            check(findBatch.metrics == editedSnapshot.metrics) {
                "bounded find returned unexpected metrics"
            }
            check(findBatch.remainingCandidateRange == null) {
                "bounded find returned progress alongside a match"
            }
            check(
                findMatch.range ==
                    Utf16Range(
                        start = TEST_FIND_MATCH_START_UTF16,
                        end = TEST_FIND_MATCH_END_UTF16
                    )
            ) {
                "bounded find returned an unexpected global match range"
            }
            check(
                findMatch.start ==
                    ViewportCursor(
                        revision = revision,
                        line = 1L,
                        utf16Offset = 0L
                    )
            ) {
                "bounded find returned an unexpected line-relative start"
            }
            val caseSensitiveFind =
                document.find(
                    FindRequest(
                        revision = revision,
                        query = TEST_FIND_QUERY,
                        matchCase = true,
                        candidateRange =
                            Utf16Range(
                                start = 0L,
                                end = editedSnapshot.metrics.utf16Length
                            ),
                        direction = FindDirection.Forward,
                        maxCandidateUtf16Units = TEST_MAX_TOTAL_UTF16_UNITS
                    )
                )
            check(caseSensitiveFind.match == null) {
                "case-sensitive find ignored the requested case"
            }
            check(caseSensitiveFind.remainingCandidateRange == null) {
                "case-sensitive find did not exhaust the bounded document"
            }

            val pagingLimits = limits.copy(maxBlocks = 1)
            val firstPage =
                document.viewport(
                    cursor = ViewportCursor(revision = revision, line = 0, utf16Offset = 0),
                    limits = pagingLimits
                )
            val secondPageCursor = checkNotNull(firstPage.next) {
                "bounded viewport did not expose its next page"
            }
            val secondPage =
                document.viewport(
                    cursor = secondPageCursor,
                    limits = pagingLimits
                )
            val previousPageCursor = checkNotNull(secondPage.previous) {
                "bounded viewport did not expose its previous page"
            }
            check(previousPageCursor == secondPageCursor) {
                "bounded viewport returned a nonadjacent previous-page anchor"
            }
            val previousPage =
                document.previousViewport(
                    cursor = previousPageCursor,
                    limits = pagingLimits
                )
            val previousBlock = checkNotNull(previousPage.blocks.lastOrNull()) {
                "reverse viewport returned no preceding content"
            }
            val followingBlock = checkNotNull(secondPage.blocks.firstOrNull()) {
                "forward viewport returned no following content"
            }
            checkRenderBlocksJoin(earlier = previousBlock, later = followingBlock)
            check(previousPage.previous == null && previousPage.next == secondPageCursor) {
                "reverse viewport returned unexpected page anchors"
            }

            val editWindow =
                document.editWindow(
                    revision = revision,
                    selection =
                        Utf16Range(
                            start = TEST_SELECTION_START_UTF16,
                            end = TEST_SELECTION_END_UTF16
                        ),
                    limits =
                        EditWindowLimits(
                            maxUtf16Units = TEST_MAX_EDIT_WINDOW_UTF16_UNITS
                        )
                )
            check(editWindow.metrics.revision == revision) {
                "edit window returned an unexpected revision"
            }
            check(
                editWindow.selection ==
                    Utf16Range(
                        start = TEST_SELECTION_START_UTF16,
                        end = TEST_SELECTION_END_UTF16
                    )
            ) {
                "edit window returned an unexpected selection"
            }
            check(
                editWindow.text ==
                    TEST_REPLACEMENT.substring(
                        editWindow.range.start.toInt(),
                        editWindow.range.end.toInt()
                    )
            ) {
                "edit window text conflicts with its source range"
            }
            check('\n' in editWindow.text) {
                "edit window did not preserve multiline text"
            }

            val staleWindowFailure =
                runCatching {
                    document.editWindow(
                        revision = TEST_INITIAL_REVISION,
                        selection = Utf16Range(start = 0, end = 0),
                        limits =
                            EditWindowLimits(
                                maxUtf16Units = TEST_MAX_EDIT_WINDOW_UTF16_UNITS
                            )
                    )
                }.exceptionOrNull()
            check(
                staleWindowFailure is StaleDocumentRevisionException &&
                    staleWindowFailure.expectedRevision == TEST_INITIAL_REVISION &&
                    staleWindowFailure.actualRevision == revision
            ) {
                "edit window did not reject a stale revision: " +
                    "${staleWindowFailure?.javaClass?.simpleName}: " +
                    staleWindowFailure?.message
            }

            val capturedSnapshot = document.captureSnapshot(revision)
            val staleSnapshotFailure =
                runCatching {
                    document.captureSnapshot(TEST_INITIAL_REVISION)
                }.exceptionOrNull()
            check(
                staleSnapshotFailure is StaleDocumentRevisionException &&
                    staleSnapshotFailure.expectedRevision == TEST_INITIAL_REVISION &&
                    staleSnapshotFailure.actualRevision == revision
            ) {
                "snapshot capture did not reject a stale revision: " +
                    "${staleSnapshotFailure?.javaClass?.simpleName}: " +
                    staleSnapshotFailure?.message
            }
            val nextRevision =
                document.replace(
                    expectedRevision = revision,
                    range = Utf16Range(start = 0, end = TEST_SNAPSHOT_EDIT_END_UTF16),
                    replacement = TEST_SNAPSHOT_REPLACEMENT
                ).revision
            val capturedViewport =
                capturedSnapshot.viewport(
                    cursor = ViewportCursor(revision = revision, line = 0L, utf16Offset = 0L),
                    limits = limits
                )
            check(
                capturedViewport.metrics.revision == revision &&
                    capturedViewport.blocks.map(RenderBlock::text) ==
                    listOf("hello 😀", "second")
            ) {
                "captured snapshot viewport changed after a later edit"
            }
            val unusedSnapshot = document.captureSnapshot(nextRevision)
            unusedSnapshot.close()
            unusedSnapshot.close()
            val unusedSnapshotFailure =
                runCatching {
                    unusedSnapshot.writeSnapshot(
                        outputRawFileDescriptor = 0,
                        cancellationRawFileDescriptor = 1,
                        timeoutMillis = TEST_SNAPSHOT_TIMEOUT_MILLIS
                    )
                }.exceptionOrNull()
            check(
                unusedSnapshotFailure is IllegalStateException &&
                    unusedSnapshotFailure.message == "snapshot is closed"
            ) {
                "closed unused snapshot remained writable"
            }

            document.close()
            val capturedBytes = capturedSnapshot.use(::readSnapshotBytes)
            check(capturedBytes.contentEquals(TEST_REPLACEMENT.toByteArray(Charsets.UTF_8))) {
                "captured snapshot changed after its source document"
            }
            val consumedSnapshotFailure =
                runCatching {
                    capturedSnapshot.writeSnapshot(
                        outputRawFileDescriptor = 0,
                        cancellationRawFileDescriptor = 1,
                        timeoutMillis = TEST_SNAPSHOT_TIMEOUT_MILLIS
                    )
                }.exceptionOrNull()
            check(
                consumedSnapshotFailure is IllegalStateException &&
                    consumedSnapshotFailure.message == "snapshot is closed"
            ) {
                "streamed snapshot was not consumed"
            }
        }
    }

    /** Verifies the packaged Markdown JNI bridge through its isolated service. */
    private fun verifyIsolatedMarkdownPreview() {
        val applicationContext = targetContext.applicationContext
        RustDocument.createEmpty().use { document ->
            val metrics =
                document.replace(
                    expectedRevision = TEST_INITIAL_REVISION,
                    range = Utf16Range(start = 0L, end = 0L),
                    replacement = TEST_MARKDOWN_SOURCE +
                        "\n\n3. Markdown first paragraph.\n\n   Markdown second paragraph.\n\n" +
                        "<ol start=\"3\"><li><p>HTML first paragraph.</p>" +
                        "<p>HTML second paragraph.</p></li></ol>\n"
                )
            document.captureSnapshot(metrics.revision).use { snapshot ->
                val preview =
                    runBlocking {
                        IsolatedMarkdownRenderer(applicationContext).render(
                            snapshot = snapshot,
                            expectedBytes = metrics.serializedByteLength
                        )
                    }
                check(preview.inputByteLength == metrics.serializedByteLength) {
                    "Markdown preview returned an unexpected input size"
                }
                check(preview.containsRawHtml) {
                    "Markdown preview did not report literal raw HTML"
                }
                check(preview.blocks.firstOrNull()?.kind == MarkdownBlockKind.Heading) {
                    "Markdown preview did not render its leading heading"
                }
                check(preview.blocks.any { block -> block.kind == MarkdownBlockKind.Code }) {
                    "Markdown preview did not render its code block"
                }
                val tableRows = preview.blocks.filter { block ->
                    block.kind == MarkdownBlockKind.TableRow
                }
                check(
                    tableRows.map { row -> row.startsTable } ==
                        listOf(true, false, true, false, true, false, true, false)
                ) {
                    "Markdown preview merged independent table boundaries"
                }
                check(preview.blocks.any { block -> block.kind == MarkdownBlockKind.ListItem }) {
                    "Markdown preview did not render its task-list item"
                }
                for (text in listOf("Markdown second paragraph.", "HTML second paragraph.")) {
                    val continuation = preview.blocks.single { block -> block.text == text }
                    check(
                        continuation.continuesListItem && !continuation.continuesPrevious &&
                            continuation.listNumber == 0L
                    ) {
                        "Markdown preview confused a later list paragraph with a new item or fragment"
                    }
                }
                check(
                    preview.blocks.any { block ->
                        !block.containsRawHtml && block.text == "Safe semantic HTML."
                    }
                ) {
                    "Markdown preview did not render safe semantic HTML"
                }
                check(
                    preview.blocks.any { block ->
                        !block.containsRawHtml && block.text == "Markdown with inline HTML."
                    }
                ) {
                    "Markdown preview did not render paired inline HTML"
                }
                val linkAndImage =
                    preview.blocks.singleOrNull { block ->
                        block.text == "Unicode café 😀 and Image: solar ☀."
                    }
                checkNotNull(linkAndImage) {
                    "Markdown preview did not render Unicode links and inert image alt text"
                }
                check(
                    linkAndImage.spans.mapNotNull { span -> span.destination } ==
                        listOf("https://example.com/path?q=1")
                ) {
                    "Markdown preview did not preserve only the text link destination"
                }
                check(
                    preview.blocks.any { block ->
                        block.containsRawHtml && "<script>" in block.text
                    }
                ) {
                    "Markdown preview did not retain unsupported HTML as inert source"
                }
                check(
                    preview.blocks.any { block ->
                        block.kind == MarkdownBlockKind.Footnote &&
                            block.metadata == "note" &&
                            block.text == "Preview data is transient."
                    }
                ) {
                    "Markdown preview did not render its footnote definition"
                }
            }
        }
    }

    /** Checks that unusually wide empty rows survive both native encoding and strict decoding. */
    private fun verifyWideMarkdownTables() {
        for (source in listOf(
            "|${" |".repeat(4097)}\n|${" - |".repeat(4097)}\n|${" |".repeat(4097)}\n",
            "<table><tr>${"<td></td>".repeat(4097)}</tr></table>"
        )) {
            RustDocument.createEmpty().use { document ->
                val metrics = document.replace(
                    TEST_INITIAL_REVISION,
                    Utf16Range(0L, 0L),
                    source
                )
                document.captureSnapshot(metrics.revision).use { snapshot ->
                    val preview = runBlocking {
                        IsolatedMarkdownRenderer(targetContext.applicationContext).render(
                            snapshot,
                            metrics.serializedByteLength
                        )
                    }
                    check(preview.blocks.size >= 2)
                    check(
                        preview.blocks.all { block ->
                            block.kind == MarkdownBlockKind.TableRow &&
                                block.metadata.length <= 4096
                        }
                    )
                    val separators = preview.blocks.sumOf { block ->
                        block.text.count { it == '\t' }
                    }
                    check(separators == if (source.startsWith("|")) 8192 else 4096) {
                        "wide table row chunking lost empty cells"
                    }
                }
            }
        }
    }

    /** Verifies external Markdown links remain implicit and grant no URI permissions. */
    private fun verifyMarkdownLinkIntents() {
        val applicationContext = targetContext.applicationContext
        val packageInfo =
            applicationContext.packageManager.getPackageInfo(
                applicationContext.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
        check(Manifest.permission.INTERNET !in packageInfo.requestedPermissions.orEmpty()) {
            "Markdown links must not add Internet permission"
        }
        val cases =
            listOf(
                Triple("https://example.com", MarkdownExternalAction.View, Intent.ACTION_VIEW),
                Triple(
                    "mailto:test@example.com",
                    MarkdownExternalAction.SendTo,
                    Intent.ACTION_SENDTO
                ),
                Triple("tel:+15551234567", MarkdownExternalAction.Dial, Intent.ACTION_DIAL)
            )
        val uriGrantFlags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        cases.forEach { (destination, externalAction, expectedIntentAction) ->
            val link = markdownLinkAction(destination) as MarkdownLinkAction.External
            check(link.action == externalAction) {
                "Markdown link selected the wrong constrained Android action"
            }
            val intent = checkNotNull(markdownExternalLinkIntent(applicationContext, link)) {
                "Markdown link did not create an Android intent"
            }
            check(intent.action == expectedIntentAction && intent.dataString == destination) {
                "Markdown link changed its external destination"
            }
            check(intent.component == null && intent.`package` == null && intent.selector == null) {
                "Markdown link intent unexpectedly targets one application"
            }
            check(intent.clipData == null && intent.flags and uriGrantFlags == 0) {
                "Markdown link intent unexpectedly grants URI access"
            }
            check(
                (externalAction == MarkdownExternalAction.View) ==
                    intent.hasCategory(Intent.CATEGORY_BROWSABLE)
            ) {
                "Markdown link intent has an unexpected browsable category"
            }
        }
    }

    /** Verifies both image formats use matching picker metadata and remain scannable. */
    private fun verifyExpressiveQrImage() {
        QrImageFormat.entries.forEach { format -> verifyExpressiveQrImage(format) }
    }

    private fun verifyExpressiveQrImage(format: QrImageFormat) {
        val applicationContext = targetContext.applicationContext
        val resolver = applicationContext.contentResolver
        val contract = QrImageDestinationContract()
        val intent = contract.createIntent(
            applicationContext,
            QrImageDestinationRequest("Notes.md", format)
        )
        check(intent.action == Intent.ACTION_CREATE_DOCUMENT) { "QR picker did not create a document" }
        check(intent.hasCategory(Intent.CATEGORY_OPENABLE)) { "QR picker must request an openable destination" }
        check(intent.type == format.mimeType) { "QR picker MIME type did not match its format" }
        check(intent.getStringExtra(Intent.EXTRA_TITLE) == "Notes QR.${format.extension}") {
            "QR picker filename did not match its format"
        }
        val result = Intent().setData(Uri.parse("content://example.documents/qr"))
        check(contract.parseResult(Activity.RESULT_OK, result) == result.data)
        check(contract.parseResult(Activity.RESULT_CANCELED, result) == null)
        check(contract.parseResult(Activity.RESULT_OK, null) == null)
        val transferProcessor = IsolatedTransferProcessor(applicationContext)
        val destination =
            checkNotNull(
                resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "$TEST_QR_IMAGE_NAME.${format.extension}")
                        put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS
                        )
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                )
            ) {
                "could not create a QR image destination in MediaStore"
            }
        try {
            RustDocument.createEmpty().use { document ->
                val metrics =
                    document.replace(
                        expectedRevision = TEST_INITIAL_REVISION,
                        range = Utf16Range(start = 0L, end = 0L),
                        replacement = TEST_QR_IMAGE_TEXT
                    )
                document.captureSnapshot(metrics.revision).use { snapshot ->
                    val grid =
                        runBlocking {
                            transferProcessor.encodeQr(
                                snapshot = snapshot,
                                expectedBytes = metrics.serializedByteLength,
                                format = DocumentFormat.Markdown
                            )
                        }
                    val rejectedDestination =
                        applicationContext.cacheDir.resolve("rejected-qr-image.txt")
                    val originalText = "QR image export must not overwrite this file"
                    try {
                        rejectedDestination.writeText(originalText)
                        val failure = runCatching {
                            runBlocking {
                                saveExpressiveQrCodeImage(
                                    context = applicationContext,
                                    destination = Uri.fromFile(rejectedDestination),
                                    grid = grid,
                                    colors = QrCodeColors(Color.Black, Color.White),
                                    format = format
                                )
                            }
                        }.exceptionOrNull()
                        check(failure is java.io.IOException) {
                            "QR image export accepted a non-provider destination"
                        }
                        check(rejectedDestination.readText() == originalText) {
                            "QR image export changed a rejected destination"
                        }
                    } finally {
                        rejectedDestination.delete()
                    }
                    runBlocking {
                        saveExpressiveQrCodeImage(
                            context = applicationContext,
                            destination = destination,
                            grid = grid,
                            colors =
                                QrCodeColors(
                                    modules = Color(0xff17315e),
                                    background = Color(0xffafc6ff)
                                ),
                            format = format
                        )
                    }
                }
            }

            val bitmapOptions = BitmapFactory.Options()
            val bitmap =
                checkNotNull(
                    resolver.openInputStream(destination).use { input ->
                        BitmapFactory.decodeStream(input, null, bitmapOptions)
                    }
                ) {
                    "exported QR image did not decode as a bitmap"
                }
            val received =
                try {
                    check(bitmapOptions.outMimeType == format.mimeType) {
                        "exported QR bytes did not match the chosen image format"
                    }
                    check(
                        bitmap.width == EXPORTED_QR_CODE_SIDE_PIXELS &&
                            bitmap.height == EXPORTED_QR_CODE_SIDE_PIXELS
                    ) {
                        "exported QR image dimensions were not canonical"
                    }
                    runBlocking {
                        transferProcessor.decodeQr(bitmap.toQrLuminanceFrame())
                    }
                } finally {
                    bitmap.recycle()
                }
            check(received.text == TEST_QR_IMAGE_TEXT) {
                "exported QR image did not preserve its text"
            }
            check(received.format == DocumentFormat.Markdown) {
                "exported QR image did not preserve its document format"
            }
        } finally {
            resolver.delete(destination, null, null)
        }
    }

    /** Verifies the bounded PDF stream through Android's production PDF renderer. */
    private fun verifyStreamingTextPdf() {
        verifyPrintPageRanges()
        val applicationContext = targetContext.applicationContext
        val resolver = applicationContext.contentResolver
        val completeDestination =
            createPrintPdfDestination(resolver, TEST_PRINT_PDF_NAME)
        val selectedDestination =
            createPrintPdfDestination(resolver, TEST_PRINT_SELECTION_PDF_NAME)
        try {
            val text =
                buildString {
                    repeat(TEST_PRINT_LINE_COUNT) { lineIndex ->
                        append("Line ")
                        append(Math.incrementExact(lineIndex))
                        append(": local text 😀 漢字 مرحبا")
                        append('\n')
                    }
                }
            val attributes = createTestPrintAttributes()
            val layout = printRasterLayout(attributes)
            RustDocument.createEmpty().use { document ->
                val metrics =
                    document.replace(
                        expectedRevision = TEST_INITIAL_REVISION,
                        range = Utf16Range(start = 0L, end = 0L),
                        replacement = text
                    )
                document.captureSnapshot(metrics.revision).use { snapshot ->
                    val completeResult =
                        checkNotNull(resolver.openOutputStream(completeDestination, "w")) {
                            "could not open the complete PDF destination"
                        }.use { output ->
                            runBlocking {
                                renderDocumentTextPdf(
                                    destination = output,
                                    snapshot = snapshot,
                                    metrics = metrics,
                                    title = "Unicode print 😀",
                                    layout = layout,
                                    resources = targetContext.resources,
                                    requestedPages = arrayOf(PageRange.ALL_PAGES)
                                )
                            }
                        }
                    check(completeResult.totalPageCount > 1) {
                        "print verification text did not span multiple pages"
                    }
                    check(completeResult.writtenPageRanges == listOf(PageRange.ALL_PAGES)) {
                        "complete PDF reported unexpected page ranges"
                    }
                    verifyRenderedPdf(
                        resolver = resolver,
                        destination = completeDestination,
                        expectedPageCount = completeResult.totalPageCount,
                        pagesToInspect =
                            intArrayOf(0, completeResult.totalPageCount - 1)
                    )

                    val selectedPage = PageRange(1, 1)
                    val selectedResult =
                        checkNotNull(resolver.openOutputStream(selectedDestination, "w")) {
                            "could not open the selected-page PDF destination"
                        }.use { output ->
                            runBlocking {
                                renderDocumentTextPdf(
                                    destination = output,
                                    snapshot = snapshot,
                                    metrics = metrics,
                                    title = "Selected page",
                                    layout = layout,
                                    resources = targetContext.resources,
                                    requestedPages = arrayOf(selectedPage)
                                )
                            }
                        }
                    check(selectedResult.totalPageCount == completeResult.totalPageCount) {
                        "selected PDF changed the logical page count"
                    }
                    check(selectedResult.writtenPageRanges == listOf(selectedPage)) {
                        "selected PDF reported unexpected page ranges"
                    }
                    verifyRenderedPdf(
                        resolver = resolver,
                        destination = selectedDestination,
                        expectedPageCount = 1,
                        pagesToInspect = intArrayOf(0)
                    )
                }
            }
        } finally {
            resolver.delete(completeDestination, null, null)
            resolver.delete(selectedDestination, null, null)
        }
    }

    /** Verifies semantic Markdown and safe HTML print through the shared render model. */
    private fun verifyFormattedMarkdownPdf() {
        val applicationContext = targetContext.applicationContext
        val resolver = applicationContext.contentResolver
        val destination = createPrintPdfDestination(resolver, TEST_MARKDOWN_PRINT_PDF_NAME)
        try {
            val settings =
                defaultPrintSettings(formattedMarkdown = true).copy(
                    margins =
                        PrintMargins(
                            topMils = 250,
                            bottomMils = 500,
                            leftMils = 750,
                            rightMils = 1_000
                        )
                )
            check(settings.contentMode == PrintContentMode.FormattedMarkdown) {
                "Markdown print defaults selected source output"
            }
            val layout = printRasterLayout(createTestPrintAttributes(), settings)
            check(
                layout.contentLeftPoints == 54 &&
                    layout.contentBottomPoints == 36 &&
                    layout.contentWidthPoints == 90 &&
                    layout.contentHeightPoints == 162
            ) {
                "independent physical print margins were not applied exactly"
            }
            RustDocument.createEmpty().use { document ->
                val metrics =
                    document.replace(
                        expectedRevision = TEST_INITIAL_REVISION,
                        range = Utf16Range(start = 0L, end = 0L),
                        replacement = TEST_MARKDOWN_SOURCE
                    )
                val preview =
                    document.captureSnapshot(metrics.revision).use { snapshot ->
                        runBlocking {
                            IsolatedMarkdownRenderer(applicationContext).render(
                                snapshot = snapshot,
                                expectedBytes = metrics.serializedByteLength
                            )
                        }
                    }
                val result =
                    checkNotNull(resolver.openOutputStream(destination, "w")) {
                        "could not open the Markdown PDF destination"
                    }.use { output ->
                        runBlocking {
                            renderMarkdownDocumentPdf(
                                destination = output,
                                document = preview,
                                title = "Formatted Markdown",
                                layout = layout,
                                resources = targetContext.resources,
                                settings = settings,
                                requestedPages = arrayOf(PageRange.ALL_PAGES)
                            )
                        }
                    }
                check(result.writtenPageRanges == listOf(PageRange.ALL_PAGES)) {
                    "formatted Markdown PDF reported unexpected page ranges"
                }
                verifyRenderedPdf(
                    resolver = resolver,
                    destination = destination,
                    expectedPageCount = result.totalPageCount,
                    pagesToInspect = intArrayOf(0)
                )
            }
        } finally {
            resolver.delete(destination, null, null)
        }
    }

    /** Verifies semantic Markdown pagination, fallback, cancellation, and reuse on Android. */
    private fun verifyMultipageMarkdownPdf() {
        val applicationContext = targetContext.applicationContext
        val resolver = applicationContext.contentResolver
        val completeDestination =
            createPrintPdfDestination(resolver, TEST_MULTIPAGE_MARKDOWN_PRINT_PDF_NAME)
        val selectedDestination =
            createPrintPdfDestination(resolver, TEST_MULTIPAGE_MARKDOWN_SELECTION_PDF_NAME)
        val repeatedDestination =
            createPrintPdfDestination(resolver, TEST_MULTIPAGE_MARKDOWN_REPEAT_PDF_NAME)
        try {
            val settings =
                defaultPrintSettings(formattedMarkdown = true).copy(
                    margins =
                        PrintMargins(
                            topMils = 250,
                            bottomMils = 250,
                            leftMils = 250,
                            rightMils = 250
                        )
                )
            val layout = printRasterLayout(createTestPrintAttributes(), settings)
            val document = multipageMarkdownPrintDocument()
            val completeResult =
                renderMarkdownTestPdf(
                    resolver = resolver,
                    destination = completeDestination,
                    document = document,
                    title = "Multipage Markdown",
                    layout = layout,
                    settings = settings,
                    requestedPages = arrayOf(PageRange.ALL_PAGES)
                )
            check(
                completeResult.totalPageCount in
                    TEST_MULTIPAGE_MARKDOWN_MINIMUM_PAGES..TEST_MULTIPAGE_MARKDOWN_MAXIMUM_PAGES
            ) {
                "multipage Markdown produced an unexpected logical page count"
            }
            check(completeResult.writtenPageRanges == listOf(PageRange.ALL_PAGES)) {
                "complete multipage Markdown reported unexpected page ranges"
            }
            verifyRenderedPdf(
                resolver = resolver,
                destination = completeDestination,
                expectedPageCount = completeResult.totalPageCount,
                pagesToInspect = IntArray(completeResult.totalPageCount) { pageIndex -> pageIndex }
            )

            val selectedRange = PageRange(1, 2)
            val selectedResult =
                renderMarkdownTestPdf(
                    resolver = resolver,
                    destination = selectedDestination,
                    document = document,
                    title = "Selected Markdown pages",
                    layout = layout,
                    settings = settings,
                    requestedPages = arrayOf(selectedRange)
                )
            check(selectedResult.totalPageCount == completeResult.totalPageCount) {
                "selected Markdown pages changed the logical page count"
            }
            check(selectedResult.writtenPageRanges == listOf(selectedRange)) {
                "selected Markdown PDF reported unexpected page ranges"
            }
            verifyRenderedPdf(
                resolver = resolver,
                destination = selectedDestination,
                expectedPageCount = 2,
                pagesToInspect = intArrayOf(0, 1)
            )

            verifyMarkdownPrintCancellation(
                document = document,
                layout = layout,
                settings = settings
            )

            val repeatedResult =
                renderMarkdownTestPdf(
                    resolver = resolver,
                    destination = repeatedDestination,
                    document = document,
                    title = "Multipage Markdown",
                    layout = layout,
                    settings = settings,
                    requestedPages = arrayOf(PageRange.ALL_PAGES)
                )
            check(repeatedResult == completeResult) {
                "repeated Markdown rendering changed its result"
            }
            verifyRenderedPdf(
                resolver = resolver,
                destination = repeatedDestination,
                expectedPageCount = repeatedResult.totalPageCount,
                pagesToInspect =
                    intArrayOf(
                        0,
                        repeatedResult.totalPageCount / 2,
                        repeatedResult.totalPageCount - 1
                    )
            )
        } finally {
            resolver.delete(completeDestination, null, null)
            resolver.delete(selectedDestination, null, null)
            resolver.delete(repeatedDestination, null, null)
        }
    }

    /** Renders one semantic Markdown test model to a provider-owned destination. */
    private fun renderMarkdownTestPdf(
        resolver: ContentResolver,
        destination: Uri,
        document: MarkdownPreviewDocument,
        title: String,
        layout: PrintRasterLayout,
        settings: PrintSettings,
        requestedPages: Array<out PageRange>
    ): PrintRenderResult = checkNotNull(resolver.openOutputStream(destination, "w")) {
        "could not open the Markdown PDF destination"
    }.use { output ->
        runBlocking {
            renderMarkdownDocumentPdf(
                destination = output,
                document = document,
                title = title,
                layout = layout,
                resources = targetContext.resources,
                settings = settings,
                requestedPages = requestedPages
            )
        }
    }

    /** Verifies cancellation interrupts one partial render without poisoning the model. */
    private fun verifyMarkdownPrintCancellation(
        document: MarkdownPreviewDocument,
        layout: PrintRasterLayout,
        settings: PrintSettings
    ) {
        val destination = ByteArrayOutputStream()
        var checkpoints = 0
        val cancelled =
            try {
                runBlocking {
                    renderMarkdownDocumentPdf(
                        destination = destination,
                        document = document,
                        title = "Cancelled Markdown",
                        layout = layout,
                        resources = targetContext.resources,
                        settings = settings,
                        requestedPages = arrayOf(PageRange.ALL_PAGES),
                        isCancelled = {
                            checkpoints = Math.incrementExact(checkpoints)
                            checkpoints >= TEST_MULTIPAGE_MARKDOWN_CANCEL_CHECKPOINT
                        }
                    )
                }
                false
            } catch (_: CancellationException) {
                true
            }
        check(cancelled) { "multipage Markdown ignored cancellation" }
        check(checkpoints == TEST_MULTIPAGE_MARKDOWN_CANCEL_CHECKPOINT) {
            "multipage Markdown cancellation used an unexpected checkpoint"
        }
        check(destination.size() > 0) {
            "cancelled Markdown rendering wrote no bounded PDF prefix"
        }
    }

    /** Creates one bounded semantic model that forces every pagination path. */
    private fun multipageMarkdownPrintDocument(): MarkdownPreviewDocument {
        val leftAlignment = MarkdownTableAlignment.Left
        val centerAlignment = MarkdownTableAlignment.Center
        val rightAlignment = MarkdownTableAlignment.Right
        val tableAlignments = listOf(leftAlignment, centerAlignment, rightAlignment)
        val blocks =
            buildList {
                add(
                    markdownPrintTestBlock(
                        kind = MarkdownBlockKind.Heading,
                        text = "Multipage Markdown hardening",
                        headingLevel = 1
                    )
                )
                add(
                    markdownPrintTestBlock(
                        kind = MarkdownBlockKind.Paragraph,
                        text = "Every following block is local, inert, and intentionally bounded."
                    )
                )
                add(
                    markdownPrintTestBlock(
                        kind = MarkdownBlockKind.Paragraph,
                        text = markdownPrintTestLines("Alert rail line"),
                        quoteDepth = 1,
                        quoteKind = MarkdownQuoteKind.Warning,
                        startsQuoteAlert = true
                    )
                )
                add(
                    markdownPrintTestBlock(
                        kind = MarkdownBlockKind.Paragraph,
                        text = "The alert continues after its multipage block.",
                        quoteDepth = 1,
                        quoteKind = MarkdownQuoteKind.Warning
                    )
                )
                add(
                    markdownPrintTestBlock(
                        kind = MarkdownBlockKind.Code,
                        text = markdownPrintTestLines("val localLine"),
                        metadata = "kotlin"
                    )
                )
                add(
                    markdownPrintTestBlock(
                        kind = MarkdownBlockKind.TableRow,
                        text = "Alignment\tCenter\tNumber",
                        isTableHeader = true,
                        tableAlignments = tableAlignments
                    )
                )
                repeat(TEST_MULTIPAGE_MARKDOWN_TABLE_ROWS) { rowIndex ->
                    add(
                        markdownPrintTestBlock(
                            kind = MarkdownBlockKind.TableRow,
                            text =
                                "Row ${Math.incrementExact(rowIndex)}\tMiddle\t" +
                                    Math.multiplyExact(Math.incrementExact(rowIndex), 7),
                            tableAlignments = tableAlignments
                        )
                    )
                }
                add(markdownPrintTestBlock(MarkdownBlockKind.Paragraph, "Wide fallback follows."))
                val wideAlignments =
                    List(TEST_MULTIPAGE_MARKDOWN_WIDE_COLUMNS) { columnIndex ->
                        when (columnIndex % tableAlignments.size) {
                            0 -> leftAlignment
                            1 -> centerAlignment
                            else -> rightAlignment
                        }
                    }
                add(
                    markdownPrintTestBlock(
                        kind = MarkdownBlockKind.TableRow,
                        text =
                            List(TEST_MULTIPAGE_MARKDOWN_WIDE_COLUMNS) { columnIndex ->
                                "H${Math.incrementExact(columnIndex)}"
                            }.joinToString("\t"),
                        isTableHeader = true,
                        tableAlignments = wideAlignments
                    )
                )
                add(
                    markdownPrintTestBlock(
                        kind = MarkdownBlockKind.TableRow,
                        text =
                            List(TEST_MULTIPAGE_MARKDOWN_WIDE_COLUMNS) { columnIndex ->
                                "V${Math.incrementExact(columnIndex)}"
                            }.joinToString("\t"),
                        tableAlignments = wideAlignments
                    )
                )
                add(
                    markdownPrintTestBlock(
                        MarkdownBlockKind.Paragraph,
                        "A page-taller table row follows."
                    )
                )
                add(
                    markdownPrintTestBlock(
                        kind = MarkdownBlockKind.TableRow,
                        text = markdownPrintTestLines("Oversized cell line"),
                        tableAlignments = listOf(leftAlignment)
                    )
                )
                add(
                    markdownPrintTestBlock(
                        MarkdownBlockKind.Paragraph,
                        "Inert HTML follows."
                    )
                )
                add(
                    markdownPrintTestBlock(
                        kind = MarkdownBlockKind.HtmlLiteral,
                        text = markdownPrintTestLines("<iframe>Never loaded</iframe>")
                    )
                )
                add(
                    markdownPrintTestBlock(
                        MarkdownBlockKind.Paragraph,
                        "The final page remains reachable after every fallback."
                    )
                )
            }
        val inputByteLength =
            blocks.fold(0L) { total, block ->
                Math.addExact(total, block.text.toByteArray(Charsets.UTF_8).size.toLong())
            }
        return MarkdownPreviewDocument(
            inputByteLength = inputByteLength,
            blocks = blocks,
            spanCount = 0,
            containsRawHtml = true
        )
    }

    /** Creates one numbered multiline block within the worker's presentation bound. */
    private fun markdownPrintTestLines(prefix: String): String {
        require(prefix.isNotBlank()) { "Markdown print test prefix must not be blank" }
        return buildString {
            repeat(TEST_MULTIPAGE_MARKDOWN_BLOCK_LINES) { lineIndex ->
                append(prefix)
                append(' ')
                append(Math.incrementExact(lineIndex))
                append(": local text remains bounded")
                append('\n')
            }
        }
    }

    /** Creates one otherwise unstyled semantic block for pagination verification. */
    private fun markdownPrintTestBlock(
        kind: MarkdownBlockKind,
        text: String,
        metadata: String = "",
        headingLevel: Int = 0,
        quoteDepth: Int = 0,
        quoteKind: MarkdownQuoteKind? = null,
        startsQuoteAlert: Boolean = false,
        isTableHeader: Boolean = false,
        tableAlignments: List<MarkdownTableAlignment> = emptyList()
    ): MarkdownRenderBlock = MarkdownRenderBlock(
        kind = kind,
        continuesPrevious = false,
        isOrderedListItem = false,
        isTaskChecked = false,
        isTaskUnchecked = false,
        isTableHeader = isTableHeader,
        containsRawHtml = kind == MarkdownBlockKind.HtmlLiteral,
        headingLevel = headingLevel,
        quoteDepth = quoteDepth,
        listDepth = 0,
        listNumber = 0L,
        text = text,
        metadata = metadata,
        spans = emptyList(),
        quoteKind = quoteKind,
        startsQuoteAlert = startsQuoteAlert,
        tableAlignments = tableAlignments
    )

    /** Creates complete deterministic print attributes for PDF verification. */
    private fun createTestPrintAttributes(): PrintAttributes = PrintAttributes.Builder()
        .setMediaSize(
            PrintAttributes.MediaSize(
                "beautyxt-test-square",
                "BeauTyXT test square",
                TEST_PRINT_MEDIA_MILS,
                TEST_PRINT_MEDIA_MILS
            )
        )
        .setResolution(
            PrintAttributes.Resolution(
                "beautyxt-test-resolution",
                "BeauTyXT test resolution",
                TEST_PRINT_DPI,
                TEST_PRINT_DPI
            )
        )
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
        .build()

    /** Verifies page membership and compact written-range accounting on Android. */
    private fun verifyPrintPageRanges() {
        val selection =
            PrintPageSelection(
                arrayOf(
                    PageRange(2, 4),
                    PageRange(7, 7)
                )
            )
        check(!selection.includesEveryPage) { "bounded print ranges included every page" }
        check(!selection.contains(1) && selection.contains(2) && selection.contains(4)) {
            "first bounded print range had incorrect membership"
        }
        check(!selection.contains(5) && selection.contains(7) && !selection.contains(8)) {
            "second bounded print range had incorrect membership"
        }
        val sparse = PrintPageSelection(Array(10_000) { index -> PageRange(index * 2, index * 2) })
        for (pageIndex in 20_000 downTo 0) {
            check(sparse.contains(pageIndex) == (pageIndex < 20_000 && pageIndex % 2 == 0)) {
                "sparse print ranges lost membership on reverse traversal"
            }
        }
        val finalPage = PrintPageSelection(arrayOf(PageRange(Int.MAX_VALUE, Int.MAX_VALUE)))
        check(finalPage.contains(Int.MAX_VALUE) && !finalPage.contains(Int.MAX_VALUE - 1))
        val allPages = PrintPageSelection(arrayOf(PageRange.ALL_PAGES))
        check(
            allPages.includesEveryPage && allPages.contains(0) && allPages.contains(Int.MAX_VALUE)
        )
        val written = WrittenPageRanges()
        intArrayOf(2, 3, 5, 8, 9, 10).forEach(written::append)
        check(
            written.toList() ==
                listOf(
                    PageRange(2, 3),
                    PageRange(5, 5),
                    PageRange(8, 10)
                )
        ) {
            "written print pages were not compacted exactly"
        }
    }

    /** Creates one pending public PDF owned only for the duration of verification. */
    private fun createPrintPdfDestination(resolver: ContentResolver, displayName: String): Uri {
        require(displayName.endsWith(".pdf")) { "test PDF name must use the PDF extension" }
        return checkNotNull(
            resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, PDF_MIME_TYPE)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            )
        ) {
            "could not create a PDF destination in MediaStore"
        }
    }

    /** Reopens one PDF with Android and verifies selected pages contain dark marks. */
    private fun verifyRenderedPdf(
        resolver: ContentResolver,
        destination: Uri,
        expectedPageCount: Int,
        pagesToInspect: IntArray
    ) {
        require(expectedPageCount > 0) { "expected PDF page count must be positive" }
        require(pagesToInspect.isNotEmpty()) { "PDF pages to inspect must not be empty" }
        checkNotNull(resolver.openFileDescriptor(destination, "r")) {
            "could not reopen the PDF destination"
        }.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                check(renderer.pageCount == expectedPageCount) {
                    "Android reported an unexpected PDF page count"
                }
                pagesToInspect.forEach { pageIndex ->
                    check(pageIndex in 0 until renderer.pageCount) {
                        "requested PDF inspection page is out of range"
                    }
                    renderer.openPage(pageIndex).use { page ->
                        val bitmap =
                            Bitmap.createBitmap(
                                TEST_PRINT_RENDER_SIDE_PIXELS,
                                TEST_PRINT_RENDER_SIDE_PIXELS,
                                Bitmap.Config.ARGB_8888
                            )
                        try {
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(
                                bitmap,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )
                            check(bitmap.containsDarkPixel()) {
                                "Android rendered a blank PDF page"
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
    }

    /** Returns whether one rendered page contains a visibly nonwhite pixel. */
    private fun Bitmap.containsDarkPixel(): Boolean {
        val pixels = IntArray(Math.multiplyExact(width, height))
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.any { pixel ->
            val red = pixel ushr 16 and 0xff
            val green = pixel ushr 8 and 0xff
            val blue = pixel and 0xff
            red < TEST_PRINT_DARK_CHANNEL_LIMIT ||
                green < TEST_PRINT_DARK_CHANNEL_LIMIT ||
                blue < TEST_PRINT_DARK_CHANNEL_LIMIT
        }
    }

    /** Converts one opaque QR bitmap into the scanner's bounded luminance frame. */
    private fun Bitmap.toQrLuminanceFrame(): QrLuminanceFrame {
        val pixelCount = Math.multiplyExact(width, height)
        val pixels = IntArray(pixelCount)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val luminance = ByteArray(pixelCount)
        pixels.forEachIndexed { pixelIndex, pixel ->
            val red = pixel ushr 16 and 0xff
            val green = pixel ushr 8 and 0xff
            val blue = pixel and 0xff
            luminance[pixelIndex] =
                (
                    (
                        red * LUMINANCE_RED_WEIGHT +
                            green * LUMINANCE_GREEN_WEIGHT +
                            blue * LUMINANCE_BLUE_WEIGHT +
                            LUMINANCE_ROUNDING_OFFSET
                        ) ushr LUMINANCE_WEIGHT_SHIFT
                    ).toByte()
        }
        return QrLuminanceFrame(width = width, height = height, bytes = luminance)
    }

    /** Verifies two viewport blocks meet at one exact document boundary. */
    private fun checkRenderBlocksJoin(earlier: RenderBlock, later: RenderBlock) {
        if (earlier.logicalLine == later.logicalLine) {
            check(earlier.continuesAtEnd && later.continuesAtStart) {
                "same-line viewport boundary has invalid continuation flags"
            }
            check(earlier.globalUtf16End == later.globalUtf16Start) {
                "same-line viewport boundary has a discontinuous utf-16 range"
            }
            return
        }

        check(later.logicalLine == Math.incrementExact(earlier.logicalLine)) {
            "viewport boundary logical lines are not contiguous"
        }
        check(!earlier.continuesAtEnd && !later.continuesAtStart) {
            "adjacent-line viewport boundary has invalid continuation flags"
        }
        val expectedGlobalStart =
            Math.addExact(
                earlier.globalUtf16End,
                earlier.lineTerminatorUtf16Units.toLong()
            )
        check(later.globalUtf16Start == expectedGlobalStart) {
            "adjacent-line viewport boundary has a discontinuous utf-16 range"
        }
    }

    /** Reads one small native snapshot through independent output and cancellation pipes. */
    private fun readSnapshotBytes(snapshot: EditorDocumentSnapshot): ByteArray {
        val outputDescriptors = ParcelFileDescriptor.createReliablePipe()
        val cancellationDescriptors = ParcelFileDescriptor.createPipe()
        try {
            val writtenBytes =
                snapshot.writeSnapshot(
                    outputRawFileDescriptor = outputDescriptors[1].fd,
                    cancellationRawFileDescriptor = cancellationDescriptors[0].fd,
                    timeoutMillis = TEST_SNAPSHOT_TIMEOUT_MILLIS
                )
            outputDescriptors[1].close()
            val bytes =
                ParcelFileDescriptor.AutoCloseInputStream(outputDescriptors[0]).use { input ->
                    input.readBytes()
                }
            check(writtenBytes == bytes.size.toLong()) {
                "snapshot bridge returned an unexpected byte count"
            }
            return bytes
        } finally {
            outputDescriptors.forEach(::closeDescriptorQuietly)
            cancellationDescriptors.forEach(::closeDescriptorQuietly)
        }
    }

    /** Verifies descriptor import and isolated callback identity end to end. */
    private fun verifyIsolatedImportService() {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "isolated import binding must run off the main thread"
        }
        val applicationContext = targetContext.applicationContext
        val inputBuffer =
            AnonymousTestBuffer.create(
                name = TEST_INPUT_BUFFER_NAME,
                initialBytes = TEST_IMPORT_INPUT_BYTES
            )
        val outputBuffer = AnonymousTestBuffer.create(TEST_OUTPUT_BUFFER_NAME)
        val connectionExecutor =
            Executors.newSingleThreadExecutor(
                NamedThreadFactory("BeauTyXT import connection")
            )
        val connection = ImportServiceConnection()
        var isBound = false
        var primaryFailure: Throwable? = null
        try {
            val intent = Intent(applicationContext, IsolatedImportService::class.java)
            isBound =
                applicationContext.bindIsolatedService(
                    intent,
                    Context.BIND_AUTO_CREATE,
                    dev.soupslurpr.beautyxt.ipc.newIsolatedServiceInstanceName(),
                    connectionExecutor,
                    connection
                )
            check(isBound) { "isolated import service rejected the explicit bind" }
            val service = connection.awaitService()
            val callback = ImportCallback(TEST_IMPORT_JOB_ID)

            val acceptCode =
                inputBuffer.duplicate().use { inputDescriptor ->
                    outputBuffer.duplicate().use { outputDescriptor ->
                        service.startImport(
                            TEST_IMPORT_JOB_ID,
                            inputDescriptor,
                            outputDescriptor,
                            TEST_IMPORT_MAX_BYTES,
                            TEST_IMPORT_MAX_BYTES,
                            TEST_IMPORT_TIMEOUT_MILLIS,
                            callback
                        )
                    }
                }
            check(acceptCode == ImportProtocol.ACCEPT_ACCEPTED) {
                "isolated import service returned accept code $acceptCode"
            }

            val terminalStatus = callback.awaitTerminalStatus()
            verifyTerminalStatus(terminalStatus)
            val importedBytes = outputBuffer.readBytes()
            check(importedBytes.contentEquals(TEST_IMPORT_INPUT_BYTES)) {
                "isolated import output did not preserve source bytes"
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                cleanupImportResources(
                    applicationContext = applicationContext,
                    connection = connection,
                    connectionExecutor = connectionExecutor,
                    isBound = isBound
                )
            } catch (cleanupFailure: Throwable) {
                if (primaryFailure == null) {
                    primaryFailure = cleanupFailure
                    throw cleanupFailure
                }
                primaryFailure.addSuppressed(cleanupFailure)
            } finally {
                closeBuffers(primaryFailure, inputBuffer, outputBuffer)
            }
        }
    }

    /** Verifies terminal counters, source flags, and isolated Binder identity. */
    private fun verifyTerminalStatus(status: ImportCallbackStatus) {
        check(status.jobId == TEST_IMPORT_JOB_ID) {
            "terminal callback returned an unexpected job identifier"
        }
        check(status.state == ImportProtocol.STATE_COMPLETE) {
            "terminal callback returned state ${status.state}"
        }
        check(status.resultCode == ImportProtocol.RESULT_SUCCESS) {
            "terminal callback returned result ${status.resultCode}"
        }
        check(status.inputBytes == TEST_IMPORT_INPUT_BYTES.size.toLong()) {
            "terminal callback returned an unexpected input byte count"
        }
        check(status.outputBytes == TEST_IMPORT_INPUT_BYTES.size.toLong()) {
            "terminal callback returned an unexpected output byte count"
        }
        check(status.sourceFlags == TEST_EXPECTED_SOURCE_FLAGS) {
            "terminal callback returned source flags ${status.sourceFlags}"
        }
        val expectedSha256 =
            MessageDigest.getInstance(TEST_HASH_ALGORITHM).digest(TEST_IMPORT_INPUT_BYTES)
        check(status.sourceSha256.contentEquals(expectedSha256)) {
            "terminal callback returned an unexpected source sha-256"
        }
        check(status.callingUid >= 0) {
            "terminal callback returned an invalid calling uid"
        }
        check(status.callingUid != Process.myUid()) {
            "terminal callback ran with the application uid"
        }
    }

    /** Verifies a reliable provider error cannot masquerade as clean EOF. */
    private fun verifyReliableProviderFailure() {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "provider failure binding must run off the main thread"
        }
        val applicationContext = targetContext.applicationContext
        val outputBuffer = AnonymousTestBuffer.create(TEST_PROVIDER_OUTPUT_BUFFER_NAME)
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val inputDescriptor = pipe[0]
        val providerDescriptor = pipe[1]
        val connectionExecutor =
            Executors.newSingleThreadExecutor(
                NamedThreadFactory("BeauTyXT provider failure connection")
            )
        val connection = ImportServiceConnection()
        var isBound = false
        var primaryFailure: Throwable? = null
        try {
            writeProviderFailure(providerDescriptor)
            val intent = Intent(applicationContext, IsolatedImportService::class.java)
            isBound =
                applicationContext.bindIsolatedService(
                    intent,
                    Context.BIND_AUTO_CREATE,
                    dev.soupslurpr.beautyxt.ipc.newIsolatedServiceInstanceName(),
                    connectionExecutor,
                    connection
                )
            check(isBound) { "isolated import service rejected the provider failure bind" }
            val service = connection.awaitService()
            val callback = ImportCallback(TEST_PROVIDER_ERROR_JOB_ID)

            val acceptCode =
                inputDescriptor.use { source ->
                    outputBuffer.duplicate().use { output ->
                        service.startImport(
                            TEST_PROVIDER_ERROR_JOB_ID,
                            source,
                            output,
                            TEST_IMPORT_MAX_BYTES,
                            TEST_IMPORT_MAX_BYTES,
                            TEST_IMPORT_TIMEOUT_MILLIS,
                            callback
                        )
                    }
                }
            check(acceptCode == ImportProtocol.ACCEPT_ACCEPTED) {
                "provider failure import returned accept code $acceptCode"
            }

            val terminalStatus = callback.awaitTerminalStatus()
            check(terminalStatus.state == ImportProtocol.STATE_FAILED) {
                "provider failure returned state ${terminalStatus.state}"
            }
            check(terminalStatus.resultCode == ImportProtocol.RESULT_INPUT_IO) {
                "provider failure returned result ${terminalStatus.resultCode}"
            }
            check(
                terminalStatus.inputBytes == 0L &&
                    terminalStatus.outputBytes == 0L &&
                    terminalStatus.sourceFlags == 0 &&
                    terminalStatus.sourceSha256.size ==
                    ImportProtocol.RESULT_SHA_256_BYTE_COUNT &&
                    terminalStatus.sourceSha256.all { byte -> byte == 0.toByte() }
            ) {
                "provider failure exposed nonzero completion statistics"
            }
            check(terminalStatus.callingUid != Process.myUid()) {
                "provider failure callback ran with the application uid"
            }
            check(outputBuffer.byteLength() == 0L) {
                "provider failure left a populated output buffer"
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            closeDescriptorQuietly(inputDescriptor)
            closeDescriptorQuietly(providerDescriptor)
            try {
                cleanupProviderFailureResources(
                    applicationContext = applicationContext,
                    connection = connection,
                    connectionExecutor = connectionExecutor,
                    isBound = isBound
                )
            } catch (cleanupFailure: Throwable) {
                if (primaryFailure == null) {
                    primaryFailure = cleanupFailure
                    throw cleanupFailure
                }
                primaryFailure.addSuppressed(cleanupFailure)
            } finally {
                closeBuffers(primaryFailure, outputBuffer)
            }
        }
    }

    /** Verifies a selected content URI reaches Rust byte-exactly without storage residue. */
    private fun verifyProductionDocumentImporter() {
        val applicationContext = targetContext.applicationContext
        val resolver = applicationContext.contentResolver
        val selectedDocument =
            checkNotNull(
                resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, TEST_SELECTED_DOCUMENT_NAME)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                )
            ) {
                "could not create the selected-document test item"
            }
        var primaryFailure: Throwable? = null
        try {
            checkNotNull(resolver.openOutputStream(selectedDocument, "wt")).use { output ->
                output.write(TEST_IMPORT_INPUT_BYTES)
            }
            val publishValues =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
            check(resolver.update(selectedDocument, publishValues, null, null) == 1) {
                "could not publish the selected-document test item"
            }

            val selectedDocumentUriBytes =
                selectedDocument.toString().toByteArray(Charsets.UTF_8)
            val selectedDocumentUriDigest =
                MessageDigest.getInstance(TEST_HASH_ALGORITHM).digest(selectedDocumentUriBytes)
            val selectedDocumentUriDigestHex = selectedDocumentUriDigest.toLowercaseHex()
            val initialState =
                StatelessSessionState.capture(
                    context = applicationContext,
                    additionalForbiddenPatterns =
                        listOf(
                            selectedDocumentUriBytes,
                            selectedDocumentUriDigest,
                            selectedDocumentUriDigestHex.toByteArray(Charsets.UTF_8)
                        ),
                    additionalForbiddenNameParts = listOf(selectedDocumentUriDigestHex)
                )
            val document =
                runBlocking {
                    IsolatedDocumentImporter(applicationContext).open(selectedDocument)
                }
            document.use { importedDocument ->
                check(importedDocument.sourceAccess == ImportedSourceAccess.ReadWrite) {
                    "writable production source was reported as read only"
                }
                val snapshot =
                    importedDocument.viewport(
                        cursor =
                            ViewportCursor(
                                revision = TEST_INITIAL_REVISION,
                                line = 0,
                                utf16Offset = 0
                            ),
                        limits =
                            ViewportLimits(
                                maxBlocks = TEST_IMPORT_VIEWPORT_MAX_BLOCKS,
                                maxBlockUtf16Units = TEST_MAX_BLOCK_UTF16_UNITS,
                                maxTotalUtf16Units = TEST_MAX_TOTAL_UTF16_UNITS
                            )
                    )
                check(
                    snapshot.blocks.map(RenderBlock::text) ==
                        TEST_IMPORT_LOGICAL_TEXT.lines()
                ) {
                    "production importer returned unexpected document text"
                }
                check(
                    snapshot.metrics.byteLength ==
                        TEST_IMPORT_LOGICAL_TEXT.toByteArray(Charsets.UTF_8).size.toLong()
                ) {
                    "production importer returned an unexpected logical byte length"
                }
                check(
                    snapshot.metrics.serializedByteLength == TEST_IMPORT_INPUT_BYTES.size.toLong()
                ) {
                    "production importer returned an unexpected serialized byte length"
                }
                val snapshotBytes =
                    importedDocument.captureSnapshot(snapshot.metrics.revision).use(
                        ::readSnapshotBytes
                    )
                check(snapshotBytes.contentEquals(TEST_IMPORT_INPUT_BYTES)) {
                    "production importer snapshot did not preserve source bytes"
                }
                var sourceOwner: SelectedDocumentSource? =
                    importedDocument.takeSelectedSource()
                try {
                    val selectedSource = checkNotNull(sourceOwner)
                    check(selectedSource.uri == selectedDocument) {
                        "production importer changed the selected source uri"
                    }
                    val expectedSourceVersion =
                        SourceVersion.from(
                            byteLength = TEST_IMPORT_INPUT_BYTES.size.toLong(),
                            sha256 =
                                MessageDigest.getInstance(TEST_HASH_ALGORITHM)
                                    .digest(TEST_IMPORT_INPUT_BYTES)
                        )
                    check(selectedSource.sourceVersion == expectedSourceVersion) {
                        "production importer returned an unexpected source version"
                    }
                    check(
                        runCatching { importedDocument.takeSelectedSource() }
                            .exceptionOrNull() is IllegalStateException
                    ) {
                        "production importer transferred its source more than once"
                    }
                    runBlocking {
                        selectedSource.openReadWriteDescriptor().use { firstDescriptor ->
                            selectedSource.openReadWriteDescriptor().use { secondDescriptor ->
                                check(firstDescriptor.fd != secondDescriptor.fd) {
                                    "source save reused an open provider descriptor"
                                }
                            }
                        }
                    }
                    val editorSource =
                        SelectedEditorDocumentSource.takeImportedOwnership(
                            selectedSource = selectedSource,
                            exporter = IsolatedDocumentExporter(applicationContext)
                        )
                    sourceOwner = null
                    editorSource.use { source ->
                        val editedMetrics =
                            importedDocument.replace(
                                expectedRevision = snapshot.metrics.revision,
                                range =
                                    Utf16Range(
                                        start = 0L,
                                        end = TEST_SNAPSHOT_EDIT_END_UTF16
                                    ),
                                replacement = TEST_SNAPSHOT_REPLACEMENT
                            )
                        runBlocking {
                            source.saveRevision(
                                snapshot = importedDocument.captureSnapshot(editedMetrics.revision),
                                expectedBytes = editedMetrics.serializedByteLength
                            )
                        }
                        val expectedSourceBytes = TEST_IMPORT_INPUT_BYTES.copyOf()
                        expectedSourceBytes[TEST_UTF8_BOM.size] =
                            TEST_SNAPSHOT_REPLACEMENT.single().code.toByte()
                        val savedSourceBytes =
                            checkNotNull(resolver.openInputStream(selectedDocument)).use { input ->
                                input.readBytes()
                            }
                        check(savedSourceBytes.contentEquals(expectedSourceBytes)) {
                            "source autosave did not preserve exact serialized bytes"
                        }
                        check(
                            editedMetrics.serializedByteLength == expectedSourceBytes.size.toLong()
                        ) {
                            "source autosave returned an unexpected serialized byte length"
                        }
                        val expectedSavedSourceVersion =
                            SourceVersion.from(
                                byteLength = expectedSourceBytes.size.toLong(),
                                sha256 =
                                    MessageDigest.getInstance(TEST_HASH_ALGORITHM)
                                        .digest(expectedSourceBytes)
                            )
                        val savedSourceVersion = checkNotNull(selectedSource.sourceVersion) {
                            "successful source save cleared its exact baseline"
                        }
                        check(savedSourceVersion == expectedSavedSourceVersion) {
                            "successful source save did not advance its exact baseline"
                        }
                        check(savedSourceVersion != expectedSourceVersion) {
                            "successful source save retained its imported baseline"
                        }
                        runBlocking {
                            source.saveRevision(
                                snapshot = importedDocument.captureSnapshot(editedMetrics.revision),
                                expectedBytes = editedMetrics.serializedByteLength
                            )
                        }
                        check(selectedSource.sourceVersion == savedSourceVersion) {
                            "second source save did not use the preceding verified baseline"
                        }
                        check(
                            checkNotNull(resolver.openInputStream(selectedDocument)).use { input ->
                                input.readBytes()
                            }.contentEquals(expectedSourceBytes)
                        ) {
                            "second source save changed an identical serialized revision"
                        }

                        val conflictedMetrics =
                            importedDocument.replace(
                                expectedRevision = editedMetrics.revision,
                                range =
                                    Utf16Range(
                                        start = TEST_CONFLICT_EDIT_START_UTF16,
                                        end = TEST_CONFLICT_EDIT_END_UTF16
                                    ),
                                replacement = TEST_CONFLICT_REPLACEMENT
                            )
                        val externallyModifiedSourceBytes = expectedSourceBytes.copyOf()
                        val externalSourceEditOffset =
                            TEST_UTF8_BOM.size + TEST_EXTERNAL_SOURCE_LOGICAL_OFFSET
                        check(
                            externallyModifiedSourceBytes[externalSourceEditOffset] !=
                                TEST_EXTERNAL_SOURCE_REPLACEMENT
                        ) {
                            "external source replacement did not change the selected bytes"
                        }
                        externallyModifiedSourceBytes[externalSourceEditOffset] =
                            TEST_EXTERNAL_SOURCE_REPLACEMENT
                        check(externallyModifiedSourceBytes.size == expectedSourceBytes.size) {
                            "external source replacement changed the selected byte length"
                        }
                        checkNotNull(
                            resolver.openOutputStream(selectedDocument, "wt")
                        ).use { output ->
                            output.write(externallyModifiedSourceBytes)
                        }
                        check(
                            checkNotNull(resolver.openInputStream(selectedDocument)).use { input ->
                                input.readBytes()
                            }.contentEquals(externallyModifiedSourceBytes)
                        ) {
                            "external source replacement did not reach the selected document"
                        }

                        repeat(TEST_CONFLICT_SAVE_ATTEMPTS) { attemptIndex ->
                            val failure =
                                runBlocking {
                                    try {
                                        source.saveRevision(
                                            snapshot =
                                                importedDocument.captureSnapshot(
                                                    conflictedMetrics.revision
                                                ),
                                            expectedBytes =
                                                conflictedMetrics.serializedByteLength
                                        )
                                        null
                                    } catch (failure: DocumentExportException) {
                                        failure
                                    }
                                }
                            check(failure?.failure == DocumentExportFailure.SOURCE_CONFLICT) {
                                "conditional source save attempt ${attemptIndex + 1} " +
                                    "did not report a source conflict"
                            }
                            val sourceBytesAfterConflict =
                                checkNotNull(
                                    resolver.openInputStream(selectedDocument)
                                ).use { input ->
                                    input.readBytes()
                                }
                            check(
                                sourceBytesAfterConflict.contentEquals(
                                    externallyModifiedSourceBytes
                                )
                            ) {
                                "source conflict attempt ${attemptIndex + 1} changed provider bytes"
                            }
                            check(selectedSource.sourceVersion == savedSourceVersion) {
                                "source conflict attempt ${attemptIndex + 1} changed the baseline"
                            }
                        }
                    }
                } finally {
                    sourceOwner?.close()
                }
                initialState.requireUnchanged(
                    context = applicationContext,
                    expectedImportBufferDescriptors = TEST_ACTIVE_IMPORT_BUFFER_DESCRIPTORS
                )
            }
            initialState.requireUnchanged(applicationContext)
            val readOnlyDocument =
                runBlocking {
                    IsolatedDocumentImporter(applicationContext).open(
                        uri = selectedDocument,
                        allowSourceWriteAccess = false
                    )
                }
            readOnlyDocument.use { importedDocument ->
                check(importedDocument.sourceAccess == ImportedSourceAccess.ReadOnly) {
                    "write-disabled import retained an autosave capability"
                }
            }
            initialState.requireUnchanged(applicationContext)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                check(resolver.delete(selectedDocument, null, null) == 1) {
                    "could not delete the selected-document test item"
                }
            } catch (cleanupFailure: Throwable) {
                if (primaryFailure == null) {
                    throw cleanupFailure
                }
                primaryFailure.addSuppressed(cleanupFailure)
            }
        }
    }

    /** Verifies a newly created empty destination establishes its first exact baseline. */
    private fun verifyCreatedDocumentSource() {
        val applicationContext = targetContext.applicationContext
        val resolver = applicationContext.contentResolver
        val expectedBytes = TEST_REPLACEMENT.toByteArray(Charsets.UTF_8)
        val expectedVersion =
            SourceVersion.from(
                byteLength = expectedBytes.size.toLong(),
                sha256 =
                    MessageDigest.getInstance(TEST_HASH_ALGORITHM)
                        .digest(expectedBytes)
            )
        val createdDocument =
            checkNotNull(
                resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, TEST_CREATED_DOCUMENT_NAME)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                )
            ) {
                "could not create the new-source test item"
            }
        var primaryFailure: Throwable? = null
        try {
            check(
                runBlocking {
                    querySelectedDocumentDisplayName(resolver, createdDocument)
                } == TEST_CREATED_DOCUMENT_NAME
            ) {
                "created source returned an unexpected display name"
            }
            var sourceOwner: SelectedDocumentSource? =
                SelectedDocumentSource.takeOwnership(applicationContext, createdDocument)
            try {
                val selectedSource = checkNotNull(sourceOwner)
                check(selectedSource.sourceVersion == null) {
                    "newly created source unexpectedly had a byte baseline"
                }
                val editorSource =
                    SelectedEditorDocumentSource.takeCreatedOwnership(
                        selectedSource = selectedSource,
                        exporter = IsolatedDocumentExporter(applicationContext)
                    )
                sourceOwner = null
                editorSource.use { source ->
                    RustDocument.createEmpty().use { document ->
                        val metrics =
                            document.replace(
                                expectedRevision = TEST_INITIAL_REVISION,
                                range = Utf16Range(start = 0L, end = 0L),
                                replacement = TEST_REPLACEMENT
                            )
                        currentVerificationPhase = "created document source initial save"
                        runBlocking {
                            source.saveRevision(
                                snapshot = document.captureSnapshot(metrics.revision),
                                expectedBytes = metrics.serializedByteLength
                            )
                        }
                        check(metrics.serializedByteLength == expectedBytes.size.toLong()) {
                            "newly created source returned an unexpected byte length"
                        }
                        currentVerificationPhase = "created document source post-save read"
                        check(
                            checkNotNull(resolver.openInputStream(createdDocument)).use { input ->
                                input.readBytes()
                            }.contentEquals(expectedBytes)
                        ) {
                            "newly created source did not receive the exact first revision"
                        }
                        check(selectedSource.sourceVersion == expectedVersion) {
                            "newly created source did not establish its exact first baseline"
                        }
                    }
                }
            } finally {
                sourceOwner?.close()
            }
            val publishValues =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
            check(resolver.update(createdDocument, publishValues, null, null) == 1) {
                "could not publish the new-source test item"
            }
            currentVerificationPhase = "created document source nonempty conflict"
            verifyNonemptyNewSourceConflict(
                applicationContext = applicationContext,
                createdDocument = createdDocument,
                expectedBytes = expectedBytes
            )
            currentVerificationPhase = "created document source post-receipt failure"
            verifyPostReceiptFailure(
                applicationContext = applicationContext,
                createdDocument = createdDocument,
                expectedBytes = expectedBytes,
                expectedVersion = expectedVersion
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                check(resolver.delete(createdDocument, null, null) == 1) {
                    "could not delete the new-source test item"
                }
            } catch (cleanupFailure: Throwable) {
                if (primaryFailure == null) {
                    throw cleanupFailure
                }
                primaryFailure.addSuppressed(cleanupFailure)
            }
        }
    }

    /** Verifies a null baseline cannot replace a nonempty provider target. */
    private fun verifyNonemptyNewSourceConflict(
        applicationContext: Context,
        createdDocument: Uri,
        expectedBytes: ByteArray
    ) {
        val resolver = applicationContext.contentResolver
        var sourceOwner: SelectedDocumentSource? =
            SelectedDocumentSource.takeOwnership(applicationContext, createdDocument)
        try {
            val selectedSource = checkNotNull(sourceOwner)
            val editorSource =
                SelectedEditorDocumentSource.takeCreatedOwnership(
                    selectedSource = selectedSource,
                    exporter = IsolatedDocumentExporter(applicationContext)
                )
            sourceOwner = null
            editorSource.use { source ->
                RustDocument.createEmpty().use { document ->
                    val metrics =
                        document.replace(
                            expectedRevision = TEST_INITIAL_REVISION,
                            range = Utf16Range(start = 0L, end = 0L),
                            replacement = TEST_POST_RECEIPT_REPLACEMENT
                        )
                    val failure =
                        runBlocking {
                            try {
                                source.saveRevision(
                                    snapshot = document.captureSnapshot(metrics.revision),
                                    expectedBytes = metrics.serializedByteLength
                                )
                                null
                            } catch (failure: DocumentExportException) {
                                failure
                            }
                        }
                    check(failure?.failure == DocumentExportFailure.SOURCE_CONFLICT) {
                        "nonempty new target did not report a source conflict"
                    }
                    check(selectedSource.sourceVersion == null) {
                        "nonempty new-target conflict established a source baseline"
                    }
                }
            }
            check(
                checkNotNull(resolver.openInputStream(createdDocument)).use { input ->
                    input.readBytes().contentEquals(expectedBytes)
                }
            ) {
                "nonempty new-target conflict changed provider bytes"
            }
        } finally {
            sourceOwner?.close()
        }
    }

    /** Verifies a failed fresh probe keeps the prior source baseline. */
    private fun verifyPostReceiptFailure(
        applicationContext: Context,
        createdDocument: Uri,
        expectedBytes: ByteArray,
        expectedVersion: SourceVersion
    ) {
        val resolver = applicationContext.contentResolver
        val replacementBytes = TEST_POST_RECEIPT_REPLACEMENT.toByteArray(Charsets.UTF_8)
        val replacementVersion =
            SourceVersion.from(
                byteLength = replacementBytes.size.toLong(),
                sha256 =
                    MessageDigest.getInstance(TEST_HASH_ALGORITHM)
                        .digest(replacementBytes)
            )
        var sourceOwner: SelectedDocumentSource? =
            SelectedDocumentSource.takeOwnership(applicationContext, createdDocument)
        val exporter =
            ReceiptThenVerificationFailureExporter(
                expectedBaseline = expectedVersion,
                receiptVersion = replacementVersion
            )
        try {
            val selectedSource = checkNotNull(sourceOwner)
            selectedSource.advanceSourceVersion(
                expectedVersion = null,
                newVersion = expectedVersion
            )
            val editorSource =
                SelectedEditorDocumentSource.takeImportedOwnership(
                    selectedSource = selectedSource,
                    exporter = exporter
                )
            sourceOwner = null
            editorSource.use { source ->
                RustDocument.createEmpty().use { document ->
                    val metrics =
                        document.replace(
                            expectedRevision = TEST_INITIAL_REVISION,
                            range = Utf16Range(start = 0L, end = 0L),
                            replacement = TEST_POST_RECEIPT_REPLACEMENT
                        )
                    val failure =
                        runBlocking {
                            try {
                                source.saveRevision(
                                    snapshot = document.captureSnapshot(metrics.revision),
                                    expectedBytes = metrics.serializedByteLength
                                )
                                null
                            } catch (failure: DocumentExportException) {
                                failure
                            }
                        }
                    check(failure?.failure == DocumentExportFailure.SOURCE_UNCERTAIN) {
                        "post-receipt verification failure was not made uncertain"
                    }
                    check(
                        (failure.cause as? DocumentExportException)?.failure ==
                            DocumentExportFailure.SOURCE_CONFLICT
                    ) {
                        "post-receipt verification failure lost its isolated cause"
                    }
                    check(selectedSource.sourceVersion == expectedVersion) {
                        "post-receipt verification failure advanced the source baseline"
                    }
                }
            }
            check(exporter.saveCallCount == 1) {
                "post-receipt verification test did not save exactly once"
            }
            check(exporter.verificationCallCount == 1) {
                "post-receipt verification test did not verify exactly once"
            }
            check(
                checkNotNull(resolver.openInputStream(createdDocument)).use { input ->
                    input.readBytes().contentEquals(expectedBytes)
                }
            ) {
                "post-receipt verification test unexpectedly changed source bytes"
            }
        } finally {
            sourceOwner?.close()
        }
    }

    /** Verifies metrics and viewport output for a large anonymous seekable source. */
    private fun verifyLargeSeekableSource() {
        val applicationContext = targetContext.applicationContext
        val authorityPackage = context.packageName
        val uri =
            StatelessImportTestSources.seekable(
                authorityPackage = authorityPackage,
                sourceBytes = TEST_LARGE_SOURCE_BYTES,
                operationTokenHex = TEST_OPERATION_TOKEN_HEX
            )
        val siblingUri =
            StatelessImportTestSources.streaming(
                authorityPackage = authorityPackage,
                sourceBytes = TEST_LARGE_SOURCE_BYTES,
                operationTokenHex = TEST_OPERATION_TOKEN_HEX
            )
        val initialState = StatelessSessionState.capture(applicationContext)
        StatelessImportTestSources.offerPersistableReadGrant(
            resolver = applicationContext.contentResolver,
            authorityPackage = authorityPackage,
            targetPackage = applicationContext.packageName,
            uri = uri,
            operationTokenHex = TEST_OPERATION_TOKEN_HEX
        )
        StatelessImportTestSources.offerPersistableReadGrant(
            resolver = applicationContext.contentResolver,
            authorityPackage = authorityPackage,
            targetPackage = applicationContext.packageName,
            uri = siblingUri,
            operationTokenHex = TEST_OPERATION_TOKEN_HEX
        )
        try {
            check(applicationContext.hasExplicitReadGrant(uri)) {
                "test provider did not grant the selected source"
            }
            check(applicationContext.hasExplicitReadGrant(siblingUri)) {
                "test provider did not grant the sibling source"
            }
            initialState.requireUnchanged(applicationContext)
            verifyLargeProductionSource(uri)
            check(applicationContext.hasExplicitReadGrant(uri)) {
                "closing one source revoked a grant owned by the Android task"
            }
            check(applicationContext.hasExplicitReadGrant(siblingUri)) {
                "closing one source revoked a sibling grant"
            }
        } finally {
            StatelessImportTestSources.revokeReadGrant(
                resolver = applicationContext.contentResolver,
                authorityPackage = authorityPackage,
                targetPackage = applicationContext.packageName,
                uri = uri,
                operationTokenHex = TEST_OPERATION_TOKEN_HEX
            )
            StatelessImportTestSources.revokeReadGrant(
                resolver = applicationContext.contentResolver,
                authorityPackage = authorityPackage,
                targetPackage = applicationContext.packageName,
                uri = siblingUri,
                operationTokenHex = TEST_OPERATION_TOKEN_HEX
            )
        }
        initialState.requireUnchanged(applicationContext)
    }

    /** Verifies metrics and viewport output for a large reliable pipe source. */
    private fun verifyLargeStreamingSource() {
        verifyLargeProductionSource(
            StatelessImportTestSources.streaming(
                authorityPackage = context.packageName,
                sourceBytes = TEST_LARGE_SOURCE_BYTES,
                operationTokenHex = TEST_OPERATION_TOKEN_HEX
            )
        )
    }

    /** Opens one large source and validates its metrics and requested viewport prefix. */
    private fun verifyLargeProductionSource(uri: Uri) {
        val applicationContext = targetContext.applicationContext
        val initialState = StatelessSessionState.capture(applicationContext)
        val document =
            runBlocking {
                IsolatedDocumentImporter(applicationContext).open(uri)
            }
        document.use { importedDocument ->
            check(importedDocument.sourceAccess == ImportedSourceAccess.ReadOnly) {
                "read-only test provider was reported as writable"
            }
            val snapshot = importedDocument.initialTestViewport()
            check(snapshot.metrics.byteLength == TEST_LARGE_SOURCE_BYTES) {
                "large source returned an unexpected byte length"
            }
            check(snapshot.metrics.characterLength == TEST_LARGE_SOURCE_BYTES) {
                "large source returned an unexpected character length"
            }
            check(snapshot.metrics.utf16Length == TEST_LARGE_SOURCE_BYTES) {
                "large source returned an unexpected utf-16 length"
            }
            check(snapshot.metrics.lineCount == 2L) {
                "large source returned an unexpected line count"
            }
            val expectedPrefix = TEST_OPERATION_TOKEN_HEX.take(TEST_MAX_BLOCK_UTF16_UNITS)
            check(snapshot.blocks.first().text == expectedPrefix) {
                "large source viewport did not begin with its operation token"
            }
            initialState.requireUnchanged(
                context = applicationContext,
                expectedImportBufferDescriptors = TEST_ACTIVE_IMPORT_BUFFER_DESCRIPTORS
            )
        }
        initialState.requireUnchanged(applicationContext)
    }

    /** Verifies no path appears while a successful stream remains active. */
    private fun verifyPausedSourceCompletion() {
        val applicationContext = targetContext.applicationContext
        val resolver = applicationContext.contentResolver
        val authorityPackage = context.packageName
        StatelessImportTestSources.resetPaused(
            resolver = resolver,
            authorityPackage = authorityPackage,
            operationTokenHex = TEST_OPERATION_TOKEN_HEX
        )
        val initialState = StatelessSessionState.capture(applicationContext)
        val document =
            runBlocking {
                val deferred =
                    async {
                        IsolatedDocumentImporter(applicationContext).open(
                            StatelessImportTestSources.paused(
                                authorityPackage = authorityPackage,
                                operationTokenHex = TEST_OPERATION_TOKEN_HEX
                            )
                        )
                    }
                try {
                    awaitPausedSource(resolver, authorityPackage)
                    initialState.requireUnchanged(
                        context = applicationContext,
                        expectedImportBufferDescriptors =
                        TEST_ACTIVE_IMPORT_BUFFER_DESCRIPTORS
                    )
                    StatelessImportTestSources.releasePaused(
                        resolver = resolver,
                        authorityPackage = authorityPackage,
                        operationTokenHex = TEST_OPERATION_TOKEN_HEX
                    )
                    val importedDocument = withTimeout(TEST_CALLBACK_TIMEOUT_MILLIS) {
                        deferred.await()
                    }
                    awaitPausedTerminal(
                        resolver = resolver,
                        authorityPackage = authorityPackage,
                        expectedWriteFailure = false
                    )
                    importedDocument
                } finally {
                    StatelessImportTestSources.releasePaused(
                        resolver = resolver,
                        authorityPackage = authorityPackage,
                        operationTokenHex = TEST_OPERATION_TOKEN_HEX
                    )
                    if (!deferred.isCompleted) {
                        deferred.cancel()
                    }
                }
            }
        document.use { importedDocument ->
            val snapshot = importedDocument.initialTestViewport()
            check(snapshot.metrics.byteLength > TEST_IMPORT_INPUT_BYTES.size) {
                "paused source returned an unexpectedly small document"
            }
            initialState.requireUnchanged(
                context = applicationContext,
                expectedImportBufferDescriptors = TEST_ACTIVE_IMPORT_BUFFER_DESCRIPTORS
            )
        }
        initialState.requireUnchanged(applicationContext)
    }

    /** Verifies reliable provider failure leaves no document state or grant. */
    private fun verifyProductionProviderFailure() {
        val applicationContext = targetContext.applicationContext
        val initialState = StatelessSessionState.capture(applicationContext)
        val failure =
            runBlocking {
                expectDocumentImportFailure {
                    IsolatedDocumentImporter(applicationContext).open(
                        StatelessImportTestSources.failed(
                            authorityPackage = context.packageName,
                            operationTokenHex = TEST_OPERATION_TOKEN_HEX
                        )
                    )
                }
            }
        check(failure == DocumentImportFailure.OPEN_FAILED) {
            "provider failure returned $failure"
        }
        initialState.requireUnchanged(applicationContext)
    }

    /** Verifies caller cancellation closes every anonymous import capability. */
    private fun verifyProductionCancellation() {
        val applicationContext = targetContext.applicationContext
        val resolver = applicationContext.contentResolver
        val authorityPackage = context.packageName
        StatelessImportTestSources.resetPaused(
            resolver = resolver,
            authorityPackage = authorityPackage,
            operationTokenHex = TEST_OPERATION_TOKEN_HEX
        )
        val initialState = StatelessSessionState.capture(applicationContext)
        runBlocking {
            val deferred =
                async {
                    IsolatedDocumentImporter(applicationContext).open(
                        StatelessImportTestSources.paused(
                            authorityPackage = authorityPackage,
                            operationTokenHex = TEST_OPERATION_TOKEN_HEX
                        )
                    )
                }
            try {
                verifySuspendingPhase("production cancellation source pause") {
                    awaitPausedSource(resolver, authorityPackage)
                }
                initialState.requireUnchanged(
                    context = applicationContext,
                    expectedImportBufferDescriptors = TEST_ACTIVE_IMPORT_BUFFER_DESCRIPTORS
                )
                val processId =
                    verifySuspendingPhase("production cancellation process lookup") {
                        awaitIsolatedImportProcessId(applicationContext)
                    }
                deferred.cancel(CancellationException("test import cancellation"))
                try {
                    deferred.await()
                    error("cancelled import returned a document")
                } catch (_: CancellationException) {
                    // Cancellation is the expected terminal result.
                }
                verifySuspendingPhase("production cancellation process exit") {
                    awaitIsolatedImportProcessIdAbsent(
                        applicationContext = applicationContext,
                        processId = processId
                    )
                }
                StatelessImportTestSources.releasePaused(
                    resolver = resolver,
                    authorityPackage = authorityPackage,
                    operationTokenHex = TEST_OPERATION_TOKEN_HEX
                )
                verifySuspendingPhase("production cancellation provider terminal") {
                    awaitPausedTerminal(
                        resolver = resolver,
                        authorityPackage = authorityPackage,
                        expectedWriteFailure = true
                    )
                }
            } finally {
                StatelessImportTestSources.releasePaused(
                    resolver = resolver,
                    authorityPackage = authorityPackage,
                    operationTokenHex = TEST_OPERATION_TOKEN_HEX
                )
                if (!deferred.isCompleted) {
                    deferred.cancel()
                }
            }
        }
        initialState.requireUnchanged(applicationContext)
    }

    /** Verifies isolated-service death discards the anonymous import buffer. */
    private fun verifyProductionServiceDeath() {
        val applicationContext = targetContext.applicationContext
        val resolver = applicationContext.contentResolver
        val authorityPackage = context.packageName
        StatelessImportTestSources.resetPaused(
            resolver = resolver,
            authorityPackage = authorityPackage,
            operationTokenHex = TEST_OPERATION_TOKEN_HEX
        )
        val initialState = StatelessSessionState.capture(applicationContext)
        val failure =
            try {
                runBlocking {
                    supervisorScope {
                        val deferred =
                            async {
                                IsolatedDocumentImporter(applicationContext).open(
                                    StatelessImportTestSources.paused(
                                        authorityPackage = authorityPackage,
                                        operationTokenHex = TEST_OPERATION_TOKEN_HEX
                                    )
                                )
                            }
                        try {
                            verifySuspendingPhase("isolated service death source pause") {
                                awaitPausedSource(resolver, authorityPackage)
                            }
                            initialState.requireUnchanged(
                                context = applicationContext,
                                expectedImportBufferDescriptors =
                                TEST_ACTIVE_IMPORT_BUFFER_DESCRIPTORS
                            )
                            val processId =
                                verifySuspendingPhase("isolated service death process lookup") {
                                    awaitIsolatedImportProcessId(applicationContext)
                                }
                            crashProcess(processId)
                            verifySuspendingPhase("isolated service death process exit") {
                                awaitIsolatedImportProcessIdAbsent(
                                    applicationContext = applicationContext,
                                    processId = processId
                                )
                            }
                            val importFailure =
                                verifySuspendingPhase("isolated service death callback") {
                                    withTimeout(TEST_CALLBACK_TIMEOUT_MILLIS) {
                                        expectDocumentImportFailure { deferred.await() }
                                    }
                                }
                            StatelessImportTestSources.releasePaused(
                                resolver = resolver,
                                authorityPackage = authorityPackage,
                                operationTokenHex = TEST_OPERATION_TOKEN_HEX
                            )
                            verifySuspendingPhase("isolated service death provider terminal") {
                                awaitPausedTerminal(
                                    resolver = resolver,
                                    authorityPackage = authorityPackage,
                                    expectedWriteFailure = true
                                )
                            }
                            importFailure
                        } finally {
                            StatelessImportTestSources.releasePaused(
                                resolver = resolver,
                                authorityPackage = authorityPackage,
                                operationTokenHex = TEST_OPERATION_TOKEN_HEX
                            )
                            if (!deferred.isCompleted) {
                                deferred.cancel()
                            }
                        }
                    }
                }
            } finally {
                runBlocking {
                    verifySuspendingPhase("isolated service death cleanup") {
                        awaitIsolatedImportProcessAbsent(applicationContext)
                    }
                }
            }
        check(failure == DocumentImportFailure.SERVICE_UNAVAILABLE) {
            "service death returned $failure"
        }
        initialState.requireUnchanged(applicationContext)
    }

    /** Verifies an empty revision reaches one exact stateless sink. */
    private fun verifyEmptyExactExport() {
        val applicationContext = targetContext.applicationContext
        val resolver = applicationContext.contentResolver
        val authorityPackage = context.packageName
        val expectedBytes = ByteArray(0)
        StatelessExportTestSinks.reset(
            resolver = resolver,
            authorityPackage = authorityPackage,
            operationTokenHex = TEST_OPERATION_TOKEN_HEX
        )
        val initialState = StatelessSessionState.capture(applicationContext)
        try {
            RustDocument.createEmpty().use { document ->
                document.captureSnapshot(TEST_INITIAL_REVISION).use { exportSnapshot ->
                    runBlocking {
                        saveSnapshotToUri(
                            applicationContext = applicationContext,
                            uri =
                                StatelessExportTestSinks.normal(
                                    authorityPackage = authorityPackage,
                                    operationTokenHex = TEST_OPERATION_TOKEN_HEX
                                ),
                            snapshot = exportSnapshot,
                            expectedBytes = 0L
                        )
                    }
                }
                val status =
                    runBlocking {
                        awaitExportSinkTerminal(resolver, authorityPackage)
                    }
                requireExactExportStatus(
                    status = status,
                    expectedBytes = 0L,
                    expectedSha256 =
                        MessageDigest.getInstance(TEST_HASH_ALGORITHM)
                            .digest(expectedBytes)
                            .toLowercaseHex(),
                    expectedPrefix = expectedBytes,
                    expectedSuffix = expectedBytes
                )
                check(document.initialTestViewport().metrics.byteLength == 0L) {
                    "empty export changed its source document"
                }
                initialState.requireUnchanged(applicationContext)
            }
            initialState.requireUnchanged(applicationContext)
        } finally {
            StatelessExportTestSinks.reset(
                resolver = resolver,
                authorityPackage = authorityPackage,
                operationTokenHex = TEST_OPERATION_TOKEN_HEX
            )
        }
    }

    /** Verifies one edited UTF-8/LF document reaches an exact stateless sink. */
    private fun verifySmallExactExport() {
        val applicationContext = targetContext.applicationContext
        val resolver = applicationContext.contentResolver
        val authorityPackage = context.packageName
        val expectedBytes = TEST_REPLACEMENT.toByteArray(Charsets.UTF_8)
        StatelessExportTestSinks.reset(
            resolver = resolver,
            authorityPackage = authorityPackage,
            operationTokenHex = TEST_OPERATION_TOKEN_HEX
        )
        val initialState = StatelessSessionState.capture(applicationContext)
        try {
            RustDocument.createEmpty().use { document ->
                val revision =
                    document.replace(
                        expectedRevision = TEST_INITIAL_REVISION,
                        range = Utf16Range(start = 0L, end = 0L),
                        replacement = TEST_REPLACEMENT
                    ).revision
                check(revision == TEST_EDITED_REVISION) {
                    "small export edit returned an unexpected revision"
                }
                document.captureSnapshot(revision).use { exportSnapshot ->
                    runBlocking {
                        saveSnapshotToUri(
                            applicationContext = applicationContext,
                            uri =
                                StatelessExportTestSinks.normal(
                                    authorityPackage = authorityPackage,
                                    operationTokenHex = TEST_OPERATION_TOKEN_HEX
                                ),
                            snapshot = exportSnapshot,
                            expectedBytes = expectedBytes.size.toLong()
                        )
                    }
                }
                val status =
                    runBlocking {
                        awaitExportSinkTerminal(resolver, authorityPackage)
                    }
                requireExactExportStatus(
                    status = status,
                    expectedBytes = expectedBytes.size.toLong(),
                    expectedSha256 =
                        MessageDigest.getInstance(TEST_HASH_ALGORITHM)
                            .digest(expectedBytes)
                            .toLowercaseHex(),
                    expectedPrefix = expectedBytes,
                    expectedSuffix = expectedBytes
                )
                val snapshot = document.testViewport(revision)
                check(snapshot.blocks.map(RenderBlock::text) == TEST_REPLACEMENT.lines()) {
                    "small export changed its source document"
                }
                initialState.requireUnchanged(applicationContext)
            }
            initialState.requireUnchanged(applicationContext)
        } catch (failure: Throwable) {
            val status =
                runCatching {
                    StatelessExportTestSinks.status(
                        resolver = resolver,
                        authorityPackage = authorityPackage,
                        operationTokenHex = TEST_OPERATION_TOKEN_HEX
                    )
                }.getOrNull()
            val statusReport =
                if (status == null) {
                    "unavailable"
                } else {
                    "opened=${status.opened}, terminal=${status.terminal}, " +
                        "error=${status.hasError}, bytes=${status.byteCount}"
                }
            throw IllegalStateException("small export sink status: $statusReport", failure)
        } finally {
            StatelessExportTestSinks.reset(
                resolver = resolver,
                authorityPackage = authorityPackage,
                operationTokenHex = TEST_OPERATION_TOKEN_HEX
            )
        }
    }

    /** Verifies edit-only and source-backed package capabilities directly. */
    private fun verifySealedSourceSavePackage() {
        verifyEditOnlySourceSavePackage()
        verifySourceBackedSourceSavePackage()
    }

    /** Verifies an edit-only source-save package is compact and permanently sealed. */
    private fun verifyEditOnlySourceSavePackage() {
        val applicationContext = targetContext.applicationContext
        val expectedBytes = TEST_REPLACEMENT.toByteArray(Charsets.UTF_8)
        val initialState = StatelessSessionState.capture(applicationContext)
        RustDocument.createEmpty().use { document ->
            val revision =
                document.replace(
                    expectedRevision = TEST_INITIAL_REVISION,
                    range = Utf16Range(start = 0L, end = 0L),
                    replacement = TEST_REPLACEMENT
                ).revision
            val stagedPackage =
                document.captureSnapshot(revision).use { snapshot ->
                    runBlocking {
                        StagedSourceSavePackage.capture(
                            snapshot = snapshot,
                            expectedBytes = expectedBytes.size.toLong()
                        )
                    }
                }
            stagedPackage.use { staged ->
                check(staged.outputByteLength == expectedBytes.size.toLong()) {
                    "staged package reported an unexpected output length"
                }
                check(staged.payloadByteLength == expectedBytes.size.toLong()) {
                    "edit-only package did not inline exactly its output"
                }
                val descriptors = staged.takeDescriptors()
                check(descriptors.sourceBackingDescriptor == null) {
                    "edit-only package unexpectedly retained a source backing"
                }
                descriptors.packageDescriptor.use { descriptor ->
                    val status = Os.fstat(descriptor.fileDescriptor)
                    check(OsConstants.S_ISREG(status.st_mode)) {
                        "staged package is not a regular descriptor"
                    }
                    check(status.st_nlink == 0L) {
                        "staged package unexpectedly has a filesystem link"
                    }
                    check(status.st_uid == Process.myUid()) {
                        "staged package has an unexpected owner"
                    }
                    check(status.st_size == staged.packageByteLength) {
                        "staged package has an unexpected size"
                    }
                    check(
                        Os.readlink("/proc/self/fd/${descriptor.fd}")
                            .contains(TEST_EXPORT_STAGE_LINK_MARKER)
                    ) {
                        "staged package is not backed by the expected anonymous descriptor"
                    }
                    check(
                        Os.fcntlInt(descriptor.fileDescriptor, TEST_F_GET_SEALS, 0) and
                            TEST_REQUIRED_EXPORT_STAGE_SEALS ==
                            TEST_REQUIRED_EXPORT_STAGE_SEALS
                    ) {
                        "staged package is missing required seals"
                    }
                    check(Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR) == 0L) {
                        "staged package was not rewound"
                    }
                    val actualBytes =
                        ParcelFileDescriptor.AutoCloseInputStream(descriptor.dup()).use { input ->
                            input.readBytes()
                        }
                    check(
                        actualBytes.takeLast(expectedBytes.size).toByteArray()
                            .contentEquals(expectedBytes)
                    ) {
                        "staged package changed its inline payload"
                    }
                    Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_SET)
                    val writeFailure =
                        try {
                            Os.write(descriptor.fileDescriptor, byteArrayOf(0), 0, 1)
                            null
                        } catch (failure: ErrnoException) {
                            failure
                        }
                    check(writeFailure?.errno == OsConstants.EPERM) {
                        "staged package accepted a write after sealing"
                    }
                }
                check(runCatching(staged::takeDescriptors).isFailure) {
                    "staged package transferred its descriptors more than once"
                }
            }
            initialState.requireUnchanged(applicationContext)
        }
        initialState.requireUnchanged(applicationContext)
    }

    /** Verifies one sparse edit retains an exact sealed source backing. */
    private fun verifySourceBackedSourceSavePackage() {
        val applicationContext = targetContext.applicationContext
        val initialState = StatelessSessionState.capture(applicationContext)
        val sourceBytes = ByteArray(TEST_SOURCE_BACKED_PACKAGE_BYTES) { TEST_LARGE_SOURCE_FILL }
        AnonymousTestBuffer.create(
            name = TEST_SOURCE_BACKING_NAME,
            initialBytes = sourceBytes,
            allowSealing = true
        ).use { sourceBuffer ->
            sourceBuffer.duplicate().use { sourceDescriptor ->
                RustDocument.openSource(
                    rawFileDescriptor = sourceDescriptor.fd,
                    expectedBytes = sourceBytes.size.toLong()
                ).use { document ->
                    val editOffset = sourceBytes.size.toLong() / 2L
                    val editedMetrics =
                        document.replace(
                            expectedRevision = TEST_INITIAL_REVISION,
                            range = Utf16Range(start = editOffset, end = editOffset + 1L),
                            replacement = TEST_LARGE_DIRTY_REPLACEMENT
                        )
                    val stagedPackage =
                        document.captureSnapshot(editedMetrics.revision).use { snapshot ->
                            runBlocking {
                                StagedSourceSavePackage.capture(
                                    snapshot = snapshot,
                                    expectedBytes = editedMetrics.serializedByteLength
                                )
                            }
                        }
                    stagedPackage.use { staged ->
                        check(staged.outputByteLength == sourceBytes.size.toLong()) {
                            "source-backed package reported an unexpected output length"
                        }
                        check(
                            staged.payloadByteLength ==
                                TEST_SOURCE_BACKED_PACKAGE_PAYLOAD_BYTES
                        ) {
                            "source-backed package did not retain only its live edit"
                        }
                        check(staged.recordCount == TEST_SOURCE_BACKED_PACKAGE_RECORDS) {
                            "source-backed package reported an unexpected record count"
                        }
                        val expectedPackageBytes =
                            TEST_SOURCE_SAVE_PACKAGE_HEADER_BYTES +
                                TEST_SOURCE_BACKED_PACKAGE_RECORDS *
                                TEST_SOURCE_SAVE_PACKAGE_RECORD_BYTES +
                                TEST_SOURCE_BACKED_PACKAGE_PAYLOAD_BYTES
                        check(staged.packageByteLength == expectedPackageBytes) {
                            "source-backed package length is not compact"
                        }
                        val descriptors = staged.takeDescriptors()
                        descriptors.packageDescriptor.use { packageDescriptor ->
                            checkSealedSourceSaveDescriptor(
                                descriptor = packageDescriptor,
                                expectedBytes = expectedPackageBytes,
                                expectedLinkMarker = TEST_EXPORT_STAGE_LINK_MARKER,
                                label = "source-backed package"
                            )
                            check(
                                Os.lseek(
                                    packageDescriptor.fileDescriptor,
                                    0L,
                                    OsConstants.SEEK_CUR
                                ) == 0L
                            ) {
                                "source-backed package was not rewound"
                            }
                        }
                        checkNotNull(descriptors.sourceBackingDescriptor) {
                            "source-backed package omitted its immutable backing"
                        }.use { backingDescriptor ->
                            checkSealedSourceSaveDescriptor(
                                descriptor = backingDescriptor,
                                expectedBytes = sourceBytes.size.toLong(),
                                expectedLinkMarker = TEST_SOURCE_BACKING_LINK_MARKER,
                                label = "source backing"
                            )
                        }
                        check(runCatching(staged::takeDescriptors).isFailure) {
                            "source-backed package transferred its descriptors more than once"
                        }
                    }
                }
            }
        }
        initialState.requireUnchanged(applicationContext)
    }

    /** Requires one exact anonymous source-save capability with permanent seals. */
    private fun checkSealedSourceSaveDescriptor(
        descriptor: ParcelFileDescriptor,
        expectedBytes: Long,
        expectedLinkMarker: String,
        label: String
    ) {
        val status = Os.fstat(descriptor.fileDescriptor)
        check(OsConstants.S_ISREG(status.st_mode)) {
            "$label is not a regular descriptor"
        }
        check(status.st_nlink == 0L) { "$label unexpectedly has a filesystem link" }
        check(status.st_uid == Process.myUid()) { "$label has an unexpected owner" }
        check(status.st_size == expectedBytes) { "$label has an unexpected size" }
        check(Os.readlink("/proc/self/fd/${descriptor.fd}").contains(expectedLinkMarker)) {
            "$label has an unexpected anonymous backing"
        }
        check(
            Os.fcntlInt(descriptor.fileDescriptor, TEST_F_GET_SEALS, 0) and
                TEST_REQUIRED_EXPORT_STAGE_SEALS ==
                TEST_REQUIRED_EXPORT_STAGE_SEALS
        ) {
            "$label is missing required seals"
        }
    }

    /** Verifies a reliable destination error overrides a complete native copy. */
    private fun verifyCompletePayloadExportFailure() {
        val applicationContext = targetContext.applicationContext
        val resolver = applicationContext.contentResolver
        val authorityPackage = context.packageName
        val expectedBytes = TEST_REPLACEMENT.toByteArray(Charsets.UTF_8)
        check(
            expectedBytes.size.toLong() == StatelessExportTestSinks.drainedFailureBytes
        ) {
            "drained failure fixture byte count changed"
        }
        StatelessExportTestSinks.reset(
            resolver = resolver,
            authorityPackage = authorityPackage,
            operationTokenHex = TEST_OPERATION_TOKEN_HEX
        )
        val initialState = StatelessSessionState.capture(applicationContext)
        try {
            RustDocument.createEmpty().use { document ->
                val revision =
                    document.replace(
                        expectedRevision = TEST_INITIAL_REVISION,
                        range = Utf16Range(start = 0L, end = 0L),
                        replacement = TEST_REPLACEMENT
                    ).revision
                check(revision == TEST_EDITED_REVISION) {
                    "drained failure edit returned an unexpected revision"
                }
                val sourceBeforeFailure = document.testViewport(revision)
                val failure =
                    document.captureSnapshot(revision).use { exportSnapshot ->
                        runBlocking {
                            expectDocumentExportFailure {
                                saveSnapshotToUri(
                                    applicationContext = applicationContext,
                                    uri =
                                        StatelessExportTestSinks.failedAfterCompleteDrain(
                                            authorityPackage = authorityPackage,
                                            operationTokenHex = TEST_OPERATION_TOKEN_HEX
                                        ),
                                    snapshot = exportSnapshot,
                                    expectedBytes = expectedBytes.size.toLong()
                                )
                            }
                        }
                    }
                check(failure == DocumentExportFailure.WRITE_FAILED) {
                    "complete-payload export failure returned $failure"
                }
                val status =
                    runBlocking {
                        awaitExportSinkTerminal(resolver, authorityPackage)
                    }
                check(status.opened && status.terminal && status.hasError) {
                    "complete-payload sink did not report its terminal error"
                }
                check(status.byteCount == expectedBytes.size.toLong()) {
                    "complete-payload sink returned an unexpected byte count"
                }
                check(
                    status.sha256Hex ==
                        MessageDigest.getInstance(TEST_HASH_ALGORITHM)
                            .digest(expectedBytes)
                            .toLowercaseHex()
                ) {
                    "complete-payload sink returned an unexpected digest"
                }
                check(status.prefix.contentEquals(expectedBytes)) {
                    "complete-payload sink returned an unexpected prefix"
                }
                check(status.suffix.contentEquals(expectedBytes)) {
                    "complete-payload sink returned an unexpected suffix"
                }
                check(document.testViewport(revision) == sourceBeforeFailure) {
                    "complete-payload failure changed its source document"
                }
                initialState.requireUnchanged(applicationContext)
            }
            initialState.requireUnchanged(applicationContext)
        } finally {
            StatelessExportTestSinks.reset(
                resolver = resolver,
                authorityPackage = authorityPackage,
                operationTokenHex = TEST_OPERATION_TOKEN_HEX
            )
        }
    }

    /** Verifies a 16 MiB source exports exactly through bounded observations. */
    private fun verifyLargeExactExport() {
        val applicationContext = targetContext.applicationContext
        val resolver = applicationContext.contentResolver
        val authorityPackage = context.packageName
        StatelessExportTestSinks.reset(
            resolver = resolver,
            authorityPackage = authorityPackage,
            operationTokenHex = TEST_OPERATION_TOKEN_HEX
        )
        val initialState = StatelessSessionState.capture(applicationContext)
        try {
            openLargeSeekableDocument(applicationContext, authorityPackage).use { document ->
                initialState.requireUnchanged(
                    context = applicationContext,
                    expectedImportBufferDescriptors = TEST_ACTIVE_IMPORT_BUFFER_DESCRIPTORS
                )
                document.captureSnapshot(TEST_INITIAL_REVISION).use { exportSnapshot ->
                    runBlocking {
                        saveSnapshotToUri(
                            applicationContext = applicationContext,
                            uri =
                                StatelessExportTestSinks.normal(
                                    authorityPackage = authorityPackage,
                                    operationTokenHex = TEST_OPERATION_TOKEN_HEX
                                ),
                            snapshot = exportSnapshot,
                            expectedBytes = TEST_LARGE_SOURCE_BYTES
                        )
                    }
                }
                val status =
                    runBlocking {
                        awaitExportSinkTerminal(resolver, authorityPackage)
                    }
                requireExactExportStatus(
                    status = status,
                    expectedBytes = TEST_LARGE_SOURCE_BYTES,
                    expectedSha256 = expectedLargeSourceSha256(),
                    expectedPrefix = expectedLargeSourcePrefix(status.prefix.size),
                    expectedSuffix = ByteArray(status.suffix.size) { TEST_LARGE_SOURCE_FILL }
                )
                val snapshot = document.initialTestViewport()
                check(snapshot.metrics.byteLength == TEST_LARGE_SOURCE_BYTES) {
                    "large export changed its source byte length"
                }
                initialState.requireUnchanged(
                    context = applicationContext,
                    expectedImportBufferDescriptors = TEST_ACTIVE_IMPORT_BUFFER_DESCRIPTORS
                )
            }
            initialState.requireUnchanged(applicationContext)
        } finally {
            StatelessExportTestSinks.reset(
                resolver = resolver,
                authorityPackage = authorityPackage,
                operationTokenHex = TEST_OPERATION_TOKEN_HEX
            )
        }
    }

    /** Verifies a destination failure preserves one edited large document. */
    private fun verifyProductionExportFailure() {
        val applicationContext = targetContext.applicationContext
        val resolver = applicationContext.contentResolver
        val authorityPackage = context.packageName
        StatelessExportTestSinks.reset(
            resolver = resolver,
            authorityPackage = authorityPackage,
            operationTokenHex = TEST_OPERATION_TOKEN_HEX
        )
        val initialState = StatelessSessionState.capture(applicationContext)
        try {
            openLargeSeekableDocument(applicationContext, authorityPackage).use { document ->
                val dirtyRevision =
                    document.replace(
                        expectedRevision = TEST_INITIAL_REVISION,
                        range =
                            Utf16Range(
                                start = TEST_LARGE_DIRTY_OFFSET,
                                end = TEST_LARGE_DIRTY_OFFSET + 1L
                            ),
                        replacement = TEST_LARGE_DIRTY_REPLACEMENT
                    ).revision
                check(dirtyRevision == TEST_EDITED_REVISION) {
                    "large dirty export edit returned an unexpected revision"
                }
                val sourceBeforeFailure = document.testViewport(dirtyRevision)
                val failure =
                    document.captureSnapshot(dirtyRevision).use { exportSnapshot ->
                        runBlocking {
                            expectDocumentExportFailure {
                                saveSnapshotToUri(
                                    applicationContext = applicationContext,
                                    uri =
                                        StatelessExportTestSinks.failedAfterPrefix(
                                            authorityPackage = authorityPackage,
                                            operationTokenHex = TEST_OPERATION_TOKEN_HEX
                                        ),
                                    snapshot = exportSnapshot,
                                    expectedBytes = TEST_LARGE_SOURCE_BYTES
                                )
                            }
                        }
                    }
                check(failure == DocumentExportFailure.WRITE_FAILED) {
                    "provider export failure returned $failure"
                }
                val status =
                    runBlocking {
                        awaitExportSinkTerminal(resolver, authorityPackage)
                    }
                check(status.opened && status.terminal && status.hasError) {
                    "failed export sink did not report its terminal error"
                }
                check(status.byteCount in 1L until TEST_LARGE_SOURCE_BYTES) {
                    "failed export sink consumed an unexpected byte count"
                }
                val sourceAfterFailure = document.testViewport(dirtyRevision)
                check(sourceAfterFailure == sourceBeforeFailure) {
                    "failed export changed its dirty source document"
                }
                initialState.requireUnchanged(
                    context = applicationContext,
                    expectedImportBufferDescriptors = TEST_ACTIVE_IMPORT_BUFFER_DESCRIPTORS
                )
            }
            initialState.requireUnchanged(applicationContext)
        } finally {
            StatelessExportTestSinks.reset(
                resolver = resolver,
                authorityPackage = authorityPackage,
                operationTokenHex = TEST_OPERATION_TOKEN_HEX
            )
        }
    }

    /** Verifies cancelling a blocked large export reaches terminal cleanup. */
    private fun verifyProductionExportCancellation() {
        val applicationContext = targetContext.applicationContext
        val resolver = applicationContext.contentResolver
        val authorityPackage = context.packageName
        StatelessExportTestSinks.reset(
            resolver = resolver,
            authorityPackage = authorityPackage,
            operationTokenHex = TEST_OPERATION_TOKEN_HEX
        )
        val initialState = StatelessSessionState.capture(applicationContext)
        try {
            openLargeSeekableDocument(applicationContext, authorityPackage).use { document ->
                val sourceBeforeCancellation = document.initialTestViewport()
                document.captureSnapshot(TEST_INITIAL_REVISION).use { exportSnapshot ->
                    runBlocking {
                        val deferred =
                            async {
                                saveSnapshotToUri(
                                    applicationContext = applicationContext,
                                    uri =
                                        StatelessExportTestSinks.paused(
                                            authorityPackage = authorityPackage,
                                            operationTokenHex = TEST_OPERATION_TOKEN_HEX
                                        ),
                                    snapshot = exportSnapshot,
                                    expectedBytes = TEST_LARGE_SOURCE_BYTES
                                )
                            }
                        var sinkOpened = false
                        try {
                            verifySuspendingPhase("production export cancellation sink pause") {
                                awaitPausedExportSink(resolver, authorityPackage)
                            }
                            sinkOpened = true
                            initialState.requireUnchanged(
                                context = applicationContext,
                                expectedImportBufferDescriptors =
                                TEST_ACTIVE_IMPORT_BUFFER_DESCRIPTORS
                            )
                            deferred.cancel(CancellationException("test export cancellation"))
                            try {
                                deferred.await()
                                error("cancelled export completed successfully")
                            } catch (_: CancellationException) {
                                // Cancellation is the expected terminal result.
                            }
                        } finally {
                            StatelessExportTestSinks.releasePaused(
                                resolver = resolver,
                                authorityPackage = authorityPackage,
                                operationTokenHex = TEST_OPERATION_TOKEN_HEX
                            )
                            if (!deferred.isCompleted) {
                                deferred.cancel()
                            }
                            if (sinkOpened) {
                                val terminalStatus =
                                    verifySuspendingPhase(
                                        "production export cancellation sink terminal"
                                    ) {
                                        awaitExportSinkTerminal(resolver, authorityPackage)
                                    }
                                check(terminalStatus.byteCount < TEST_LARGE_SOURCE_BYTES) {
                                    "cancelled export sink consumed the complete document"
                                }
                                check(terminalStatus.hasError) {
                                    "cancelled export sink reported a clean terminal state"
                                }
                            }
                        }
                    }
                }
                check(document.initialTestViewport() == sourceBeforeCancellation) {
                    "cancelled export changed its source document"
                }
                initialState.requireUnchanged(
                    context = applicationContext,
                    expectedImportBufferDescriptors = TEST_ACTIVE_IMPORT_BUFFER_DESCRIPTORS
                )
            }
            initialState.requireUnchanged(applicationContext)
        } finally {
            StatelessExportTestSinks.releasePaused(
                resolver = resolver,
                authorityPackage = authorityPackage,
                operationTokenHex = TEST_OPERATION_TOKEN_HEX
            )
            StatelessExportTestSinks.reset(
                resolver = resolver,
                authorityPackage = authorityPackage,
                operationTokenHex = TEST_OPERATION_TOKEN_HEX
            )
        }
    }

    /** Waits until the export sink has opened and reached its pause checkpoint. */
    private suspend fun awaitPausedExportSink(
        resolver: ContentResolver,
        authorityPackage: String
    ): ExportSinkStatus = withTimeout(TEST_PROVIDER_STATE_TIMEOUT_MILLIS) {
        while (true) {
            val status =
                StatelessExportTestSinks.status(
                    resolver = resolver,
                    authorityPackage = authorityPackage,
                    operationTokenHex = TEST_OPERATION_TOKEN_HEX
                )
            check(!status.terminal && !status.hasError) {
                "paused export sink terminated before its checkpoint"
            }
            if (status.opened && status.paused) {
                return@withTimeout status
            }
            delay(TEST_PROVIDER_POLL_MILLIS)
        }
        error("paused export sink polling exited unexpectedly")
    }

    /** Waits for one export sink to publish terminal bounded metadata. */
    private suspend fun awaitExportSinkTerminal(
        resolver: ContentResolver,
        authorityPackage: String
    ): ExportSinkStatus = withTimeout(TEST_PROVIDER_STATE_TIMEOUT_MILLIS) {
        while (true) {
            val status =
                StatelessExportTestSinks.status(
                    resolver = resolver,
                    authorityPackage = authorityPackage,
                    operationTokenHex = TEST_OPERATION_TOKEN_HEX
                )
            if (status.terminal) {
                check(status.opened && !status.paused) {
                    "terminal export sink returned inconsistent lifecycle state"
                }
                return@withTimeout status
            }
            delay(TEST_PROVIDER_POLL_MILLIS)
        }
        error("export sink polling exited unexpectedly")
    }

    /** Waits until a paused provider has delivered data without reporting failure. */
    private suspend fun awaitPausedSource(resolver: ContentResolver, authorityPackage: String) {
        withTimeout(TEST_PROVIDER_STATE_TIMEOUT_MILLIS) {
            while (true) {
                val status =
                    StatelessImportTestSources.pausedStatus(
                        resolver = resolver,
                        authorityPackage = authorityPackage,
                        operationTokenHex = TEST_OPERATION_TOKEN_HEX
                    )
                check(!status.writeFailed) {
                    "paused test provider failed before its checkpoint"
                }
                if (
                    status.opened &&
                    status.prefixDelivered &&
                    targetImportBufferDescriptorCount() ==
                    TEST_ACTIVE_IMPORT_BUFFER_DESCRIPTORS
                ) {
                    return@withTimeout
                }
                delay(TEST_PROVIDER_POLL_MILLIS)
            }
        }
    }

    /** Waits for the paused writer and verifies whether its peer remained open. */
    private suspend fun awaitPausedTerminal(
        resolver: ContentResolver,
        authorityPackage: String,
        expectedWriteFailure: Boolean
    ) {
        withTimeout(TEST_PROVIDER_STATE_TIMEOUT_MILLIS) {
            while (true) {
                val status =
                    StatelessImportTestSources.pausedStatus(
                        resolver = resolver,
                        authorityPackage = authorityPackage,
                        operationTokenHex = TEST_OPERATION_TOKEN_HEX
                    )
                if (status.terminated) {
                    check(status.writeFailed == expectedWriteFailure) {
                        "paused provider observed unexpected terminal writer behavior"
                    }
                    return@withTimeout
                }
                delay(TEST_PROVIDER_POLL_MILLIS)
            }
        }
    }

    /** Waits for the isolated import process identifier through shell observation. */
    private suspend fun awaitIsolatedImportProcessId(applicationContext: Context): Int =
        withTimeout(TEST_PROVIDER_STATE_TIMEOUT_MILLIS) {
            while (true) {
                val processIds = isolatedImportProcessIds(applicationContext)
                processIds.singleOrNull()?.let { processId ->
                    check(processId != Process.myPid()) {
                        "isolated import process matched the application process"
                    }
                    return@withTimeout processId
                }
                delay(TEST_PROVIDER_POLL_MILLIS)
            }
            error("isolated import process lookup ended unexpectedly")
        }

    /** Waits until no isolated import process remains. */
    private suspend fun awaitIsolatedImportProcessAbsent(applicationContext: Context) {
        withTimeout(TEST_PROVIDER_STATE_TIMEOUT_MILLIS) {
            while (true) {
                if (isolatedImportProcessIds(applicationContext).isEmpty()) {
                    return@withTimeout
                }
                delay(TEST_PROVIDER_POLL_MILLIS)
            }
        }
    }

    /** Waits until one exact isolated import process identifier disappears. */
    private suspend fun awaitIsolatedImportProcessIdAbsent(
        applicationContext: Context,
        processId: Int
    ) {
        require(processId > 0) { "process identifier must be positive" }
        withTimeout(TEST_PROVIDER_STATE_TIMEOUT_MILLIS) {
            while (true) {
                if (processId !in isolatedImportProcessIds(applicationContext)) {
                    return@withTimeout
                }
                delay(TEST_PROVIDER_POLL_MILLIS)
            }
        }
    }

    /** Returns process identifiers for the declared isolated import process prefix. */
    private fun isolatedImportProcessIds(applicationContext: Context): List<Int> {
        val processNamePrefix =
            applicationContext.packageName +
                TEST_IMPORT_PROCESS_SUFFIX +
                TEST_PROCESS_COMPONENT_SEPARATOR
        val processNamePattern = "^${processNamePrefix.replace(".", "[.]")}"
        return runShellCommand("$TEST_PROCESS_LOOKUP_COMMAND $processNamePattern")
            .splitToSequence(Regex("\\s+"))
            .filter(String::isNotBlank)
            .mapNotNull(String::toIntOrNull)
            .toList()
    }

    /** Crashes one observed isolated process through Activity Manager. */
    private fun crashProcess(processId: Int) {
        require(processId > 0) { "process identifier must be positive" }
        runShellCommand("$TEST_PROCESS_CRASH_COMMAND $processId")
    }

    /** Runs one fixed instrumentation shell command and returns bounded output. */
    private fun runShellCommand(command: String): String {
        val output = CharArray(TEST_MAX_SHELL_OUTPUT_CHARS + 1)
        val outputCharacters =
            ParcelFileDescriptor.AutoCloseInputStream(
                uiAutomation.executeShellCommand(command)
            ).bufferedReader(Charsets.UTF_8).use { reader ->
                var charactersRead = 0
                while (charactersRead < output.size) {
                    val read =
                        reader.read(
                            output,
                            charactersRead,
                            output.size - charactersRead
                        )
                    if (read < 0) {
                        break
                    }
                    check(read > 0) { "shell output reader made no progress" }
                    charactersRead += read
                }
                charactersRead
            }
        check(outputCharacters <= TEST_MAX_SHELL_OUTPUT_CHARS) {
            "instrumentation shell output exceeded its limit"
        }
        return output.concatToString(0, outputCharacters).trim()
    }

    /** Returns one expected sanitized import failure and closes unexpected success. */
    private suspend fun expectDocumentImportFailure(
        action: suspend () -> ImportedDocument
    ): DocumentImportFailure = try {
        action().use { error("failed import returned a document") }
    } catch (failure: DocumentImportException) {
        failure.failure
    }

    /** Opens one transient test selection and transfers only its descriptor to export. */
    private suspend fun saveSnapshotToUri(
        applicationContext: Context,
        uri: Uri,
        snapshot: EditorDocumentSnapshot,
        expectedBytes: Long
    ) {
        val exporter = IsolatedDocumentExporter(applicationContext)
        val selection = TransientDestinationSelection.from(uri)
        var destination: TransientExportDestination? = null
        try {
            destination = exporter.openDestination(selection)
            exporter.save(
                destination = destination,
                snapshot = snapshot,
                expectedBytes = expectedBytes
            )
        } finally {
            destination?.close()
            selection.close()
        }
    }

    /** Returns one expected sanitized export failure. */
    private suspend fun expectDocumentExportFailure(
        action: suspend () -> Unit
    ): DocumentExportFailure = try {
        action()
        error("failed export completed successfully")
    } catch (failure: DocumentExportException) {
        failure.failure
    }

    /** Opens the deterministic 16 MiB seekable source through production import. */
    private fun openLargeSeekableDocument(
        applicationContext: Context,
        authorityPackage: String
    ): ImportedDocument = runBlocking {
        IsolatedDocumentImporter(applicationContext).open(
            StatelessImportTestSources.seekable(
                authorityPackage = authorityPackage,
                sourceBytes = TEST_LARGE_SOURCE_BYTES,
                operationTokenHex = TEST_OPERATION_TOKEN_HEX
            )
        )
    }

    /** Returns whether this process holds an explicit temporary read grant. */
    private fun Context.hasExplicitReadGrant(uri: Uri): Boolean = checkUriPermission(
        uri,
        Process.myPid(),
        Process.myUid(),
        Intent.FLAG_GRANT_READ_URI_PERMISSION
    ) == PackageManager.PERMISSION_GRANTED

    /** Verifies exact terminal counts, digest, and bounded edge samples. */
    private fun requireExactExportStatus(
        status: ExportSinkStatus,
        expectedBytes: Long,
        expectedSha256: String,
        expectedPrefix: ByteArray,
        expectedSuffix: ByteArray
    ) {
        require(expectedBytes >= 0L) { "expected export byte count must be nonnegative" }
        check(status.opened && status.terminal && !status.hasError && !status.paused) {
            "successful export sink returned inconsistent lifecycle state"
        }
        check(status.byteCount == expectedBytes) {
            "successful export sink returned an unexpected byte count"
        }
        check(status.sha256Hex == expectedSha256) {
            "successful export sink returned an unexpected digest"
        }
        check(status.prefix.contentEquals(expectedPrefix)) {
            "successful export sink returned an unexpected prefix"
        }
        check(status.suffix.contentEquals(expectedSuffix)) {
            "successful export sink returned an unexpected suffix"
        }
    }

    /** Computes the large source digest with one fixed-size filler buffer. */
    private fun expectedLargeSourceSha256(): String {
        val digest = MessageDigest.getInstance(TEST_HASH_ALGORITHM)
        digest.update(TEST_OPERATION_TOKEN_HEX_BYTES)
        digest.update(TEST_LARGE_SOURCE_SEPARATOR)
        var remainingBytes =
            TEST_LARGE_SOURCE_BYTES -
                TEST_OPERATION_TOKEN_HEX_BYTES.size -
                1L
        val filler = ByteArray(TEST_FILE_READ_BUFFER_BYTES) { TEST_LARGE_SOURCE_FILL }
        while (remainingBytes > 0L) {
            val bytesToHash = minOf(remainingBytes, filler.size.toLong()).toInt()
            digest.update(filler, 0, bytesToHash)
            remainingBytes -= bytesToHash
        }
        return digest.digest().toLowercaseHex()
    }

    /** Returns a bounded deterministic prefix for the large source. */
    private fun expectedLargeSourcePrefix(byteCount: Int): ByteArray {
        require(byteCount >= 0) { "expected prefix byte count must be nonnegative" }
        require(byteCount.toLong() <= TEST_LARGE_SOURCE_BYTES) {
            "expected prefix exceeds the large source"
        }
        return ByteArray(byteCount) { byteIndex ->
            when {
                byteIndex < TEST_OPERATION_TOKEN_HEX_BYTES.size ->
                    TEST_OPERATION_TOKEN_HEX_BYTES[byteIndex]

                byteIndex == TEST_OPERATION_TOKEN_HEX_BYTES.size ->
                    TEST_LARGE_SOURCE_SEPARATOR

                else -> TEST_LARGE_SOURCE_FILL
            }
        }
    }

    /** Returns the standard bounded viewport used by import instrumentation. */
    private fun EditorDocument.initialTestViewport(): ViewportSnapshot =
        testViewport(TEST_INITIAL_REVISION)

    /** Returns one standard bounded viewport for an exact document revision. */
    private fun EditorDocument.testViewport(revision: Long): ViewportSnapshot = viewport(
        cursor =
            ViewportCursor(
                revision = revision,
                line = 0,
                utf16Offset = 0
            ),
        limits =
            ViewportLimits(
                maxBlocks = TEST_MAX_BLOCKS,
                maxBlockUtf16Units = TEST_MAX_BLOCK_UTF16_UNITS,
                maxTotalUtf16Units = TEST_MAX_TOTAL_UTF16_UNITS
            )
    )

    /** Writes a complete-looking prefix before reporting a provider failure. */
    private fun writeProviderFailure(descriptor: ParcelFileDescriptor) {
        var offset = 0
        while (offset < TEST_PROVIDER_INPUT_BYTES.size) {
            val writtenBytes =
                Os.write(
                    descriptor.fileDescriptor,
                    TEST_PROVIDER_INPUT_BYTES,
                    offset,
                    TEST_PROVIDER_INPUT_BYTES.size - offset
                )
            check(writtenBytes > 0) { "provider failure pipe made no write progress" }
            offset += writtenBytes
        }
        descriptor.closeWithError(TEST_PROVIDER_ERROR_MESSAGE)
    }

    /** Releases one provider-failure binding and its connection executor. */
    private fun cleanupProviderFailureResources(
        applicationContext: Context,
        connection: ServiceConnection,
        connectionExecutor: ExecutorService,
        isBound: Boolean
    ) {
        val failures = ArrayList<Throwable>()
        if (isBound) {
            try {
                applicationContext.unbindService(connection)
            } catch (failure: RuntimeException) {
                failures += failure
            }
        }
        connectionExecutor.shutdownNow()
        try {
            check(
                connectionExecutor.awaitTermination(
                    TEST_CLEANUP_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS
                )
            ) {
                "provider failure connection executor did not stop"
            }
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            failures += failure
        } catch (failure: RuntimeException) {
            failures += failure
        }
        if (failures.isNotEmpty()) {
            val cleanupFailure =
                IllegalStateException("provider failure cleanup failed", failures.first())
            failures.drop(1).forEach(cleanupFailure::addSuppressed)
            throw cleanupFailure
        }
    }

    /** Closes one test descriptor without masking its primary failure. */
    private fun closeDescriptorQuietly(descriptor: ParcelFileDescriptor) {
        try {
            descriptor.close()
        } catch (_: Exception) {
            // Descriptor cleanup remains best effort after test completion.
        }
    }

    /** Unbinds and stops callback dispatch for one direct service test. */
    private fun cleanupImportResources(
        applicationContext: Context,
        connection: ServiceConnection,
        connectionExecutor: ExecutorService,
        isBound: Boolean
    ) {
        val failures = ArrayList<Throwable>()
        if (isBound) {
            try {
                applicationContext.unbindService(connection)
            } catch (failure: RuntimeException) {
                failures += failure
            }
        }
        connectionExecutor.shutdownNow()
        try {
            check(
                connectionExecutor.awaitTermination(
                    TEST_CLEANUP_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS
                )
            ) {
                "import connection executor did not stop"
            }
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            failures += failure
        } catch (failure: RuntimeException) {
            failures += failure
        }
        if (failures.isNotEmpty()) {
            val cleanupFailure =
                IllegalStateException("isolated import cleanup failed", failures.first())
            failures.drop(1).forEach(cleanupFailure::addSuppressed)
            throw cleanupFailure
        }
    }

    /** Closes anonymous fixtures without masking an earlier test failure. */
    private fun closeBuffers(primaryFailure: Throwable?, vararg buffers: AnonymousTestBuffer) {
        var cleanupFailure: Throwable? = null
        buffers.forEach { buffer ->
            try {
                buffer.close()
            } catch (failure: Throwable) {
                if (cleanupFailure == null) {
                    cleanupFailure = failure
                } else {
                    cleanupFailure.addSuppressed(failure)
                }
            }
        }
        cleanupFailure?.let { failure ->
            if (primaryFailure == null) {
                throw failure
            }
            primaryFailure.addSuppressed(failure)
        }
    }

    private companion object {
        const val REPORT_STREAM_KEY = "stream"
    }
}

/** Owns one unlinked memfd used by direct service instrumentation. */
private class AnonymousTestBuffer private constructor(
    private val device: Long,
    private val inode: Long,
    private var descriptor: ParcelFileDescriptor?
) : AutoCloseable {
    /** Returns a caller-owned duplicate of the open anonymous descriptor. */
    @Synchronized
    fun duplicate(): ParcelFileDescriptor {
        val owner = checkNotNull(descriptor) { "anonymous test buffer is closed" }
        validateIdentity(owner)
        return owner.dup()
    }

    /** Returns the current descriptor byte length. */
    @Synchronized
    fun byteLength(): Long {
        val owner = checkNotNull(descriptor) { "anonymous test buffer is closed" }
        return validateIdentity(owner).st_size
    }

    /** Reads the complete bounded descriptor after rewinding it. */
    @Synchronized
    fun readBytes(): ByteArray {
        val owner = checkNotNull(descriptor) { "anonymous test buffer is closed" }
        validateIdentity(owner)
        Os.lseek(owner.fileDescriptor, 0L, OsConstants.SEEK_SET)
        return ParcelFileDescriptor.AutoCloseInputStream(owner.dup()).use { input ->
            input.readBytes()
        }
    }

    /** Closes the owner exactly once. */
    @Synchronized
    override fun close() {
        val owner = descriptor ?: return
        descriptor = null
        owner.close()
    }

    /** Validates anonymous type, ownership, links, and stable identity. */
    private fun validateIdentity(owner: ParcelFileDescriptor): StructStat {
        val status = Os.fstat(owner.fileDescriptor)
        check(OsConstants.S_ISREG(status.st_mode)) {
            "anonymous test buffer is not regular"
        }
        check(status.st_nlink == 0L) {
            "anonymous test buffer has a filesystem link"
        }
        check(status.st_uid == Process.myUid()) {
            "anonymous test buffer has an unexpected owner"
        }
        check(status.st_dev == device && status.st_ino == inode) {
            "anonymous test buffer identity changed"
        }
        return status
    }

    companion object {
        /** Creates one private, unlinked descriptor with deterministic initial bytes. */
        fun create(
            name: String,
            initialBytes: ByteArray = byteArrayOf(),
            allowSealing: Boolean = false
        ): AnonymousTestBuffer {
            require(name.isNotBlank()) { "anonymous test buffer name must not be blank" }
            val flags =
                OsConstants.MFD_CLOEXEC or
                    if (allowSealing) {
                        TEST_MFD_ALLOW_SEALING
                    } else {
                        0
                    }
            val rawDescriptor = Os.memfd_create(name, flags)
            var owner: ParcelFileDescriptor? = null
            try {
                writeDescriptorBytes(rawDescriptor, initialBytes)
                Os.lseek(rawDescriptor, 0L, OsConstants.SEEK_SET)
                val status = Os.fstat(rawDescriptor)
                check(OsConstants.S_ISREG(status.st_mode)) {
                    "anonymous test buffer is not regular"
                }
                check(status.st_nlink == 0L) {
                    "anonymous test buffer has a filesystem link"
                }
                check(status.st_uid == Process.myUid()) {
                    "anonymous test buffer has an unexpected owner"
                }
                check(status.st_size == initialBytes.size.toLong()) {
                    "anonymous test buffer has an unexpected size"
                }
                owner = ParcelFileDescriptor.dup(rawDescriptor)
                return AnonymousTestBuffer(
                    device = status.st_dev,
                    inode = status.st_ino,
                    descriptor = owner
                )
            } catch (failure: Throwable) {
                try {
                    owner?.close()
                } catch (closeFailure: IOException) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            } finally {
                try {
                    Os.close(rawDescriptor)
                } catch (_: ErrnoException) {
                    // Every duplicated descriptor retains explicit ownership.
                }
            }
        }
    }
}

/** Requires the target process to own exactly the expected anonymous buffers. */
private fun requireTargetImportBufferDescriptorCount(expectedCount: Int) {
    val actualCount = targetImportBufferDescriptorCount()
    check(actualCount == expectedCount) {
        "target process owns $actualCount anonymous import descriptors; " +
            "expected $expectedCount"
    }
}

/** Returns the number of target-process descriptors for production import buffers. */
private fun targetImportBufferDescriptorCount(): Int {
    val descriptorDirectory = File("/proc/self/fd")
    val descriptors = checkNotNull(descriptorDirectory.listFiles()) {
        "could not inspect target process descriptors"
    }
    return descriptors.count { descriptor ->
        try {
            Os.readlink(descriptor.path).contains(TEST_IMPORT_BUFFER_LINK_MARKER)
        } catch (failure: ErrnoException) {
            if (failure.errno != OsConstants.ENOENT) {
                throw failure
            }
            false
        }
    }
}

/** Requires the target process to retain no completed source-save package. */
private fun requireNoTargetSourceSavePackageDescriptors() {
    val descriptorDirectory = File("/proc/self/fd")
    val descriptors = checkNotNull(descriptorDirectory.listFiles()) {
        "could not inspect target process descriptors"
    }
    val actualCount =
        descriptors.count { descriptor ->
            try {
                Os.readlink(descriptor.path).contains(TEST_EXPORT_STAGE_LINK_MARKER)
            } catch (failure: ErrnoException) {
                if (failure.errno != OsConstants.ENOENT) {
                    throw failure
                }
                false
            }
        }
    check(actualCount == 0) {
        "target process retains $actualCount source-save package descriptors"
    }
}

/** Captures app-owned storage for one stateless operation. */
private data class StatelessSessionState(
    val storageEntries: Map<String, AppStorageEntry>,
    val additionalForbiddenPatterns: List<ByteArray>,
    val additionalForbiddenNameParts: List<String>
) {
    /** Requires app-owned storage to remain identical and forbidden capabilities absent. */
    fun requireUnchanged(context: Context, expectedImportBufferDescriptors: Int = 0) {
        val current =
            capture(
                context = context,
                expectedImportBufferDescriptors = expectedImportBufferDescriptors,
                additionalForbiddenPatterns = additionalForbiddenPatterns,
                additionalForbiddenNameParts = additionalForbiddenNameParts
            )
        check(current.storageEntries == storageEntries) {
            "app-owned storage changed during document import: " +
                storageChangeReport(current.storageEntries)
        }
    }

    /** Returns a bounded list of added, removed, and modified app-owned paths. */
    private fun storageChangeReport(currentEntries: Map<String, AppStorageEntry>): String {
        val paths = (storageEntries.keys + currentEntries.keys).toSortedSet()
        val changes =
            paths.mapNotNull { path ->
                val initial = storageEntries[path]
                val current = currentEntries[path]
                when {
                    initial == null -> "added $path"
                    current == null -> "removed $path"
                    initial != current -> "modified $path"
                    else -> null
                }
            }
        val report = changes.take(TEST_MAX_REPORTED_STORAGE_CHANGES).joinToString()
        return if (changes.size <= TEST_MAX_REPORTED_STORAGE_CHANGES) {
            report
        } else {
            "$report, and ${changes.size - TEST_MAX_REPORTED_STORAGE_CHANGES} more"
        }
    }

    companion object {
        /** Captures stable app data and rejects every forbidden retained capability. */
        fun capture(
            context: Context,
            expectedImportBufferDescriptors: Int = 0,
            additionalForbiddenPatterns: List<ByteArray> = emptyList(),
            additionalForbiddenNameParts: List<String> = emptyList()
        ): StatelessSessionState {
            require(expectedImportBufferDescriptors >= 0) {
                "expected import buffer descriptor count must be nonnegative"
            }
            require(additionalForbiddenPatterns.none(ByteArray::isEmpty)) {
                "additional forbidden storage patterns must not be empty"
            }
            require(additionalForbiddenNameParts.none(String::isEmpty)) {
                "additional forbidden storage name parts must not be empty"
            }
            requireTargetImportBufferDescriptorCount(expectedImportBufferDescriptors)
            requireNoTargetSourceSavePackageDescriptors()
            val retainedAdditionalPatterns =
                additionalForbiddenPatterns.map(ByteArray::copyOf)
            val retainedAdditionalNameParts = additionalForbiddenNameParts.toList()
            val forbiddenPatterns =
                TEST_FORBIDDEN_STORAGE_PATTERNS + retainedAdditionalPatterns
            val forbiddenNameParts =
                TEST_FORBIDDEN_STORAGE_NAME_PARTS + retainedAdditionalNameParts
            val entries = sortedMapOf<String, AppStorageEntry>()
            val applicationInfo = context.applicationInfo
            captureStorageRoot(
                root = File(applicationInfo.dataDir),
                rootName = TEST_CREDENTIAL_STORAGE_ROOT,
                forbiddenPatterns = forbiddenPatterns,
                forbiddenNameParts = forbiddenNameParts,
                entries = entries
            )
            captureStorageRoot(
                root = File(checkNotNull(applicationInfo.deviceProtectedDataDir)),
                rootName = TEST_DEVICE_STORAGE_ROOT,
                forbiddenPatterns = forbiddenPatterns,
                forbiddenNameParts = forbiddenNameParts,
                entries = entries
            )
            appOwnedExternalStorageRoots(context).forEachIndexed { rootIndex, root ->
                captureStorageRoot(
                    root = root,
                    rootName = "$TEST_EXTERNAL_STORAGE_ROOT_PREFIX-$rootIndex",
                    forbiddenPatterns = forbiddenPatterns,
                    forbiddenNameParts = forbiddenNameParts,
                    entries = entries
                )
            }
            check(context.contentResolver.persistedUriPermissions.isEmpty()) {
                "persisted uri grants must remain empty"
            }
            return StatelessSessionState(
                storageEntries = entries,
                additionalForbiddenPatterns = retainedAdditionalPatterns,
                additionalForbiddenNameParts = retainedAdditionalNameParts
            )
        }

        /** Returns every available canonical app-owned external storage root once. */
        @Suppress("DEPRECATION")
        private fun appOwnedExternalStorageRoots(context: Context): List<File> {
            val standardChildren =
                context.getExternalFilesDirs(null).asList() +
                    context.externalCacheDirs.asList()
            val standardPackageRoots =
                standardChildren
                    .filterNotNull()
                    .mapNotNull(File::getParentFile)
            val separateRoots =
                context.externalMediaDirs.asList() + context.obbDirs.asList()
            val canonicalRoots =
                (standardPackageRoots + separateRoots)
                    .filterNotNull()
                    .filter(File::exists)
                    .map { root ->
                        try {
                            root.canonicalFile
                        } catch (failure: IOException) {
                            throw IllegalStateException(
                                "could not canonicalize app-owned external storage",
                                failure
                            )
                        }
                    }
                    .filter(File::exists)
                    .distinctBy(File::getPath)
            return canonicalRoots
                .filter { candidate ->
                    canonicalRoots.none { possibleAncestor ->
                        candidate != possibleAncestor &&
                            candidate.isDescendantOf(possibleAncestor)
                    }
                }
                .sortedBy(File::getPath)
        }

        /** Returns whether this canonical path is nested below another canonical root. */
        private fun File.isDescendantOf(root: File): Boolean {
            val rootPath = root.path
            val rootPrefix =
                if (rootPath.endsWith(File.separator)) {
                    rootPath
                } else {
                    rootPath + File.separator
                }
            return path.startsWith(rootPrefix)
        }

        /** Captures root metadata and stable children while scanning runtime cache bytes. */
        private fun captureStorageRoot(
            root: File,
            rootName: String,
            forbiddenPatterns: List<ByteArray>,
            forbiddenNameParts: List<String>,
            entries: MutableMap<String, AppStorageEntry>
        ) {
            requireStorageNameAllowed(root.name, forbiddenNameParts)
            val status = Os.lstat(root.path)
            check(OsConstants.S_ISDIR(status.st_mode)) {
                "app-owned storage root is not a directory"
            }
            entries[rootName] = status.toAppStorageEntry(digest = null)
            val children = checkNotNull(root.listFiles()) {
                "could not inspect app-owned storage"
            }
            children.sortedBy(File::getName).forEach { child ->
                if (child.name == TEST_RUNTIME_CODE_CACHE_DIRECTORY) {
                    scanStorageEntry(
                        file = child,
                        forbiddenPatterns = forbiddenPatterns,
                        forbiddenNameParts = forbiddenNameParts
                    )
                } else {
                    captureStorageEntry(
                        file = child,
                        relativePath = "$rootName/${child.name}",
                        forbiddenPatterns = forbiddenPatterns,
                        forbiddenNameParts = forbiddenNameParts,
                        entries = entries
                    )
                }
            }
        }

        /** Records one entry and recursively visits real directories. */
        private fun captureStorageEntry(
            file: File,
            relativePath: String,
            forbiddenPatterns: List<ByteArray>,
            forbiddenNameParts: List<String>,
            entries: MutableMap<String, AppStorageEntry>
        ) {
            requireStorageNameAllowed(file.name, forbiddenNameParts)
            val status = Os.lstat(file.path)
            val digest =
                if (OsConstants.S_ISREG(status.st_mode)) {
                    fingerprintRegularFile(file, forbiddenPatterns)
                } else {
                    null
                }
            entries[relativePath] = status.toAppStorageEntry(digest)
            if (!OsConstants.S_ISDIR(status.st_mode)) {
                return
            }
            val children = checkNotNull(file.listFiles()) {
                "could not inspect app-owned directory"
            }
            children.sortedBy(File::getName).forEach { child ->
                captureStorageEntry(
                    file = child,
                    relativePath = "$relativePath/${child.name}",
                    forbiddenPatterns = forbiddenPatterns,
                    forbiddenNameParts = forbiddenNameParts,
                    entries = entries
                )
            }
        }

        /** Scans one excluded runtime tree for forbidden operation identities. */
        private fun scanStorageEntry(
            file: File,
            forbiddenPatterns: List<ByteArray>,
            forbiddenNameParts: List<String>
        ) {
            requireStorageNameAllowed(file.name, forbiddenNameParts)
            val status = Os.lstat(file.path)
            if (OsConstants.S_ISREG(status.st_mode)) {
                fingerprintRegularFile(file, forbiddenPatterns)
                return
            }
            check(OsConstants.S_ISDIR(status.st_mode)) {
                "app-owned runtime entry is neither a regular file nor directory"
            }
            val children = checkNotNull(file.listFiles()) {
                "could not inspect app-owned runtime directory"
            }
            children.sortedBy(File::getName).forEach { child ->
                scanStorageEntry(
                    file = child,
                    forbiddenPatterns = forbiddenPatterns,
                    forbiddenNameParts = forbiddenNameParts
                )
            }
        }

        /** Rejects one forbidden app-owned name. */
        private fun requireStorageNameAllowed(name: String, forbiddenNameParts: List<String>) {
            check(!name.startsWith(TEST_LEGACY_STAGE_PREFIX)) {
                "legacy import stage remains in app-owned storage"
            }
            check(
                forbiddenNameParts.none { forbiddenNamePart ->
                    name.contains(forbiddenNamePart)
                }
            ) {
                "operation identity entered app-owned storage metadata"
            }
        }

        /** Hashes one regular file and rejects every forbidden operation identity. */
        private fun fingerprintRegularFile(file: File, forbiddenPatterns: List<ByteArray>): String {
            require(forbiddenPatterns.isNotEmpty()) {
                "forbidden storage patterns must not be empty"
            }
            val digest = MessageDigest.getInstance(TEST_HASH_ALGORITHM)
            val failureTables = forbiddenPatterns.map(::patternFailureTable)
            val matchedPatternBytes = IntArray(forbiddenPatterns.size)
            val buffer = ByteArray(TEST_FILE_READ_BUFFER_BYTES)
            FileInputStream(file).use { input ->
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) {
                        break
                    }
                    digest.update(buffer, 0, bytesRead)
                    repeat(bytesRead) { byteIndex ->
                        val value = buffer[byteIndex]
                        forbiddenPatterns.indices.forEach { patternIndex ->
                            val pattern = forbiddenPatterns[patternIndex]
                            val failureTable = failureTables[patternIndex]
                            var matchedBytes = matchedPatternBytes[patternIndex]
                            while (matchedBytes > 0 && value != pattern[matchedBytes]) {
                                matchedBytes = failureTable[matchedBytes - 1]
                            }
                            if (value == pattern[matchedBytes]) {
                                matchedBytes += 1
                                check(matchedBytes != pattern.size) {
                                    "operation identity entered app-owned storage"
                                }
                            }
                            matchedPatternBytes[patternIndex] = matchedBytes
                        }
                    }
                }
            }
            return digest.digest().toLowercaseHex()
        }

        /** Builds the prefix fallback table for one streaming byte-pattern search. */
        private fun patternFailureTable(pattern: ByteArray): IntArray {
            require(pattern.isNotEmpty()) { "forbidden storage pattern must not be empty" }
            val failure = IntArray(pattern.size)
            var prefixBytes = 0
            var patternIndex = 1
            while (patternIndex < pattern.size) {
                if (pattern[patternIndex] == pattern[prefixBytes]) {
                    prefixBytes += 1
                    failure[patternIndex] = prefixBytes
                    patternIndex += 1
                } else if (prefixBytes > 0) {
                    prefixBytes = failure[prefixBytes - 1]
                } else {
                    patternIndex += 1
                }
            }
            return failure
        }
    }
}

/** Returns lowercase hexadecimal without intermediate per-byte strings. */
private fun ByteArray.toLowercaseHex(): String {
    val hexadecimal = CharArray(size * 2)
    forEachIndexed { byteIndex, value ->
        val unsignedValue = value.toInt() and 0xff
        val outputIndex = byteIndex * 2
        hexadecimal[outputIndex] = TEST_LOWERCASE_HEX_DIGITS[unsignedValue ushr 4]
        hexadecimal[outputIndex + 1] = TEST_LOWERCASE_HEX_DIGITS[unsignedValue and 0x0f]
    }
    return hexadecimal.concatToString()
}

/** Converts one lstat result into stable app-owned storage metadata. */
private fun StructStat.toAppStorageEntry(digest: String?): AppStorageEntry = AppStorageEntry(
    mode = st_mode,
    device = st_dev,
    inode = st_ino,
    size = st_size,
    modifiedSeconds = st_mtim.tv_sec,
    modifiedNanoseconds = st_mtim.tv_nsec,
    digest = digest
)

/** Identifies one app-owned entry without retaining its contents. */
private data class AppStorageEntry(
    val mode: Int,
    val device: Long,
    val inode: Long,
    val size: Long,
    val modifiedSeconds: Long,
    val modifiedNanoseconds: Long,
    val digest: String?
)

/** Writes one complete deterministic buffer to a descriptor. */
private fun writeDescriptorBytes(descriptor: FileDescriptor, bytes: ByteArray) {
    var offset = 0
    while (offset < bytes.size) {
        val writtenBytes = Os.write(descriptor, bytes, offset, bytes.size - offset)
        check(writtenBytes > 0) { "anonymous descriptor made no write progress" }
        offset += writtenBytes
    }
}

/** Returns a save receipt before making the required fresh verification fail. */
private class ReceiptThenVerificationFailureExporter(
    private val expectedBaseline: SourceVersion,
    receiptVersion: SourceVersion
) : SourceDocumentExporter {
    private val receipt = SourceSaveReceipt(receiptVersion)
    private val saveCalls = AtomicInteger()
    private val verificationCalls = AtomicInteger()

    val saveCallCount: Int
        get() = saveCalls.get()

    val verificationCallCount: Int
        get() = verificationCalls.get()

    /** Returns one synthetic receipt without changing the source descriptor. */
    override suspend fun saveSource(
        source: TransientSourceDescriptor,
        stagedPackage: StagedSourceSavePackage,
        expectedSourceVersion: SourceVersion?
    ): SourceSaveReceipt {
        source.use {
            require(expectedSourceVersion == expectedBaseline) {
                "synthetic source save received an unexpected baseline"
            }
            require(stagedPackage.outputByteLength == receipt.sourceVersion.byteLength) {
                "synthetic source save received an unexpected staged length"
            }
            saveCalls.incrementAndGet()
        }
        return receipt
    }

    /** Fails one fresh source verification after validating its expected receipt. */
    override suspend fun verifySource(
        source: TransientSourceDescriptor,
        expectedSourceVersion: SourceVersion
    ) {
        source.use {
            require(expectedSourceVersion == receipt.sourceVersion) {
                "synthetic source verification received an unexpected version"
            }
            verificationCalls.incrementAndGet()
        }
        throw DocumentExportException(DocumentExportFailure.SOURCE_CONFLICT)
    }

    /** Rejects an unexpected source inspection in this verification-only fixture. */
    override suspend fun inspectSource(
        source: TransientSourceDescriptor,
        maximumBytes: Long
    ): SourceVersion {
        source.close()
        error("synthetic source inspection was not expected")
    }
}

/** Waits for one explicit isolated-service connection with a fixed bound. */
private class ImportServiceConnection : ServiceConnection {
    private val connectionLatch = CountDownLatch(1)
    private val service = AtomicReference<IImportService?>()
    private val failureMessage = AtomicReference<String?>()

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service.set(IImportService.Stub.asInterface(binder))
        connectionLatch.countDown()
    }

    override fun onNullBinding(name: ComponentName) {
        failureMessage.compareAndSet(null, "isolated import service returned a null binding")
        connectionLatch.countDown()
    }

    override fun onBindingDied(name: ComponentName) {
        failureMessage.compareAndSet(null, "isolated import service binding died")
        connectionLatch.countDown()
    }

    override fun onServiceDisconnected(name: ComponentName) {
        failureMessage.compareAndSet(null, "isolated import service disconnected")
    }

    /** Returns the connected service or fails after a bounded wait. */
    fun awaitService(): IImportService {
        check(connectionLatch.await(TEST_BIND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            "isolated import service did not connect in time"
        }
        failureMessage.get()?.let { message -> error(message) }
        return checkNotNull(service.get()) {
            "isolated import service connected without a binder"
        }
    }
}

/** Captures the first terminal import callback and its Binder caller UID. */
private class ImportCallback(private val expectedJobId: Long) : IImportCallback.Stub() {
    private val terminalLatch = CountDownLatch(1)
    private val terminalStatus = AtomicReference<ImportCallbackStatus?>()
    private val terminalCallbackCount = AtomicInteger()

    override fun onImportStatus(
        jobId: Long,
        state: Int,
        resultCode: Int,
        inputBytes: Long,
        outputBytes: Long,
        sourceFlags: Int,
        sourceSha256: ByteArray?
    ) {
        if (state == ImportProtocol.STATE_RUNNING) {
            return
        }
        val exactSourceSha256 = checkNotNull(sourceSha256) {
            "isolated import callback returned no source sha-256"
        }
        val status =
            ImportCallbackStatus(
                jobId = jobId,
                state = state,
                resultCode = resultCode,
                inputBytes = inputBytes,
                outputBytes = outputBytes,
                sourceFlags = sourceFlags,
                sourceSha256 = exactSourceSha256.copyOf(),
                callingUid = Binder.getCallingUid()
            )
        terminalCallbackCount.incrementAndGet()
        terminalStatus.compareAndSet(null, status)
        terminalLatch.countDown()
    }

    /** Returns exactly one terminal status after a bounded wait. */
    fun awaitTerminalStatus(): ImportCallbackStatus {
        check(terminalLatch.await(TEST_CALLBACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            "isolated import service did not return a terminal callback in time"
        }
        check(terminalCallbackCount.get() == 1) {
            "isolated import service returned multiple terminal callbacks"
        }
        val status = checkNotNull(terminalStatus.get()) {
            "isolated import callback completed without terminal status"
        }
        check(status.jobId == expectedJobId) {
            "isolated import callback returned a different job identifier"
        }
        return status
    }
}

/** Stores one terminal import callback without retaining Binder objects. */
private data class ImportCallbackStatus(
    val jobId: Long,
    val state: Int,
    val resultCode: Int,
    val inputBytes: Long,
    val outputBytes: Long,
    val sourceFlags: Int,
    val sourceSha256: ByteArray,
    val callingUid: Int
)

/** Creates fixed-name threads for bounded instrumentation work. */
private class NamedThreadFactory(private val threadName: String) : ThreadFactory {
    override fun newThread(runnable: Runnable): Thread = Thread(runnable, threadName)
}
