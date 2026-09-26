package dev.soupslurpr.beautyxt.ui

import androidx.compose.ui.text.TextRange
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.exporting.client.DocumentExportException
import dev.soupslurpr.beautyxt.exporting.client.DocumentExportFailure
import dev.soupslurpr.beautyxt.importing.client.DocumentImportException
import dev.soupslurpr.beautyxt.importing.client.DocumentImportFailure
import dev.soupslurpr.beautyxt.sharing.IncomingDocumentShare
import dev.soupslurpr.beautyxt.sharing.IncomingSourcePurpose
import dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.QueuedSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.TestDestinationOwner
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import dev.soupslurpr.beautyxt.testing.TestEditorDocumentSnapshot
import dev.soupslurpr.beautyxt.ui.editor.ConflictRecoverableEditorDocumentSource
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentSource
import dev.soupslurpr.beautyxt.ui.editor.EditorDocumentState
import dev.soupslurpr.beautyxt.ui.editor.EditorSession
import dev.soupslurpr.beautyxt.ui.editor.SaveDestinationPurpose
import dev.soupslurpr.beautyxt.ui.editor.SaveStatus
import dev.soupslurpr.beautyxt.ui.editor.SourceSaveStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RETAINED_SOURCE_URI = "content://test.documents/source.txt"
private const val OTHER_SOURCE_URI = "content://test.documents/other.txt"

/** Records source saves and explicit ownership release in application-session tests. */
private class TestDocumentSource(
    private val encodedUri: String? = null,
    private val saveFailure: Exception? = null
) : ConflictRecoverableEditorDocumentSource {
    var saveCallCount = 0
        private set

    var closeCallCount = 0
        private set

    /** Returns whether this source has the configured exact test URI. */
    override fun matchesSourceUri(encodedUri: String): Boolean = this.encodedUri == encodedUri

    /** Returns the configured URI for deterministic share tests. */
    override fun encodedShareUri(): String? = encodedUri

    /** Records one exact source save. */
    override suspend fun saveRevision(snapshot: EditorDocumentSnapshot, expectedBytes: Long) {
        val testSnapshot = snapshot as TestEditorDocumentSnapshot
        require(testSnapshot.byteLength == expectedBytes) {
            "test source received an unexpected byte count"
        }
        saveCallCount = Math.incrementExact(saveCallCount)
        saveFailure?.let { failure -> throw failure }
        testSnapshot.consume()
    }

    /** Uses the same deterministic behavior for a confirmed overwrite. */
    override suspend fun overwriteRevision(snapshot: EditorDocumentSnapshot, expectedBytes: Long) {
        saveRevision(snapshot, expectedBytes)
    }

    /** Records one explicit ownership release. */
    override fun close() {
        closeCallCount = Math.incrementExact(closeCallCount)
    }
}

/** Records ownership for a selected source that cannot receive autosaves. */
private class TestReadOnlyDocumentSource(private val encodedUri: String) : EditorDocumentSource {
    var closeCallCount = 0
        private set

    /** Returns whether this source has the configured exact test URI. */
    override fun matchesSourceUri(encodedUri: String): Boolean = this.encodedUri == encodedUri

    /** Returns the configured URI for deterministic share tests. */
    override fun encodedShareUri(): String = encodedUri

    /** Records one explicit ownership release. */
    override fun close() {
        closeCallCount = Math.incrementExact(closeCallCount)
    }
}

/** Verifies retained application-session state and import ownership. */
class BeauTyXTSessionTest {
    /** Verifies view-only replacement rejects the exact original source URI. */
    @Test
    fun identifiesTheReadOnlySourceDuringEditableReplacement() {
        val source = TestReadOnlyDocumentSource(RETAINED_SOURCE_URI)
        val editor =
            createEditor(
                document = TestEditorDocument("selected"),
                title = "Selected document",
                documentSource = source
            )
        val session =
            BeauTyXTSession(
                sessionDispatcher = ImmediateSessionTestDispatcher,
                createEmptyEditor = { editor }
            )
        session.startNewDocument()
        editor.openInitialEditor()
        assertTrue(editor.isViewOnly)
        assertTrue(editor.showSaveFormatSelection())
        editor.selectSaveFormat(DocumentFormat.PlainText)
        val request = requireNotNull(session.claimSaveDestination(editor))

        assertEquals(SaveDestinationPurpose.SourceReplacement, request.purpose)
        assertTrue(session.isCurrentSourceDestination(request, RETAINED_SOURCE_URI))
        assertFalse(session.isCurrentSourceDestination(request, OTHER_SOURCE_URI))

        assertTrue(session.cancelSaveDestination(request.format))
        session.close()
        assertEquals(1, source.closeCallCount)
    }

    /** Verifies only an exact active copy may target the retained source URI. */
    @Test
    fun identifiesOnlyTheExactActiveCopyAsTheCurrentSourceDestination() {
        val source = TestDocumentSource(encodedUri = RETAINED_SOURCE_URI)
        val document = TestEditorDocument("selected")
        val editor =
            createEditor(
                document = document,
                title = "Selected document",
                documentSource = source
            )
        val session =
            BeauTyXTSession(
                sessionDispatcher = ImmediateSessionTestDispatcher,
                createEmptyEditor = { editor }
            )
        session.startNewDocument()
        editor.openInitialEditor()
        editor.showSaveCopyFormatSelection()
        editor.selectSaveFormat(DocumentFormat.PlainText)
        val request = requireNotNull(session.claimSaveDestination(editor))

        assertTrue(session.isCurrentSourceDestination(request, RETAINED_SOURCE_URI))
        assertFalse(session.isCurrentSourceDestination(request, OTHER_SOURCE_URI))
        assertFalse(
            session.isCurrentSourceDestination(
                request.copy(generation = Math.incrementExact(request.generation)),
                RETAINED_SOURCE_URI
            )
        )
        assertFalse(
            session.isCurrentSourceDestination(
                request.copy(purpose = SaveDestinationPurpose.SourceReplacement),
                RETAINED_SOURCE_URI
            )
        )

        assertTrue(session.cancelSaveDestination(request.format))
        assertFalse(session.isCurrentSourceDestination(request, RETAINED_SOURCE_URI))
        session.close()
        assertEquals(1, source.closeCallCount)
    }

    /** Verifies a failed zero-byte source cannot bypass replacement identity checks. */
    @Test
    fun identifiesOnlyTheExactActiveReplacementAsTheCurrentSourceDestination() {
        val source =
            TestDocumentSource(
                encodedUri = RETAINED_SOURCE_URI,
                saveFailure = IllegalStateException("synthetic source failure")
            )
        val document = TestEditorDocument("")
        val editor = createEditor(document = document, title = "New document")
        val session =
            BeauTyXTSession(
                sessionDispatcher = ImmediateSessionTestDispatcher,
                createEmptyEditor = { editor }
            )
        session.startNewDocument()
        editor.openInitialEditor()
        editor.showSaveFormatSelection()
        editor.selectSaveFormat(DocumentFormat.PlainText)

        val initialRequest = requireNotNull(session.claimSaveDestination(editor))
        assertEquals(SaveDestinationPurpose.SourceReplacement, initialRequest.purpose)
        assertTrue(editor.beginSaveDestinationPreparation(initialRequest))
        assertTrue(
            session.saveSelectedSource(
                format = initialRequest.format,
                documentSource = source
            )
        )
        assertTrue(editor.sourceSaveStatus is SourceSaveStatus.Failed)
        assertEquals(1, source.saveCallCount)

        editor.showSaveFormatSelection()
        editor.selectSaveFormat(DocumentFormat.Markdown)
        val replacementRequest = requireNotNull(session.claimSaveDestination(editor))

        assertEquals(SaveDestinationPurpose.SourceReplacement, replacementRequest.purpose)
        assertTrue(session.isCurrentSourceDestination(replacementRequest, RETAINED_SOURCE_URI))
        assertFalse(session.isCurrentSourceDestination(replacementRequest, OTHER_SOURCE_URI))
        assertFalse(
            session.isCurrentSourceDestination(
                replacementRequest.copy(
                    generation = Math.incrementExact(replacementRequest.generation)
                ),
                RETAINED_SOURCE_URI
            )
        )
        assertFalse(
            session.isCurrentSourceDestination(
                replacementRequest.copy(purpose = SaveDestinationPurpose.Copy),
                RETAINED_SOURCE_URI
            )
        )

        assertTrue(session.cancelSaveDestination(replacementRequest.format))
        assertFalse(
            session.isCurrentSourceDestination(replacementRequest, RETAINED_SOURCE_URI)
        )
        session.close()
        assertEquals(1, source.closeCallCount)
    }

    /** Verifies selection status remains explicit and restartable after failures. */
    @Test
    fun tracksSelectionAndSanitizedFailureStatus() {
        val session = createSession()

        assertEquals(OpenStatus.Idle, session.openStatus)
        assertTrue(session.beginDocumentSelection())
        assertEquals(OpenStatus.Selecting, session.openStatus)
        assertFalse(session.beginDocumentSelection())

        session.cancelDocumentSelection()
        assertEquals(OpenStatus.Idle, session.openStatus)

        assertTrue(session.beginDocumentSelection())
        session.openSelectedDocument {
            throw IllegalStateException("sensitive test detail")
        }

        assertEquals(
            OpenStatus.Failed(UiText.Resource(R.string.import_open_failed)),
            session.openStatus
        )
        assertFalse(session.isOpenBusy)
        assertNull(session.editor)
        assertTrue(session.beginDocumentSelection())

        session.close()
    }

    /** Verifies native import initialization failures remain sanitized and retryable. */
    @Test
    fun reportsImportLinkageFailure() {
        val session = createSession()
        assertTrue(session.beginDocumentSelection())

        session.openSelectedDocument {
            throw UnsatisfiedLinkError("sensitive native detail")
        }

        assertEquals(
            OpenStatus.Failed(UiText.Resource(R.string.import_open_failed)),
            session.openStatus
        )
        assertNull(session.editor)
        assertFalse(session.isOpenBusy)
        assertTrue(session.beginDocumentSelection())
        session.close()
    }

    /** Verifies a successful reload atomically replaces and closes the conflicted editor. */
    @Test
    fun replacesAConflictedEditorAfterSuccessfulReload() {
        val originalDocument = TestEditorDocument("source")
        val originalSource =
            TestDocumentSource(
                encodedUri = RETAINED_SOURCE_URI,
                saveFailure =
                    DocumentExportException(DocumentExportFailure.SOURCE_CONFLICT)
            )
        val originalEditor = createConflictedEditor(originalDocument, originalSource)
        val replacementDocument = TestEditorDocument("external version")
        val replacementEditor = createEditor(replacementDocument, title = "Reloaded document")
        val session =
            BeauTyXTSession(
                sessionDispatcher = ImmediateSessionTestDispatcher,
                createEmptyEditor = { originalEditor }
            )
        session.startNewDocument()
        assertTrue(originalEditor.requestSourceReloadConfirmation())
        var receivedUri: String? = null

        assertTrue(
            session.reloadEditorSource(originalEditor) { encodedUri ->
                receivedUri = encodedUri
                replacementEditor
            }
        )

        assertEquals(RETAINED_SOURCE_URI, receivedUri)
        assertSame(replacementEditor, session.editor)
        assertEquals(1, originalDocument.closeCallCount)
        assertEquals(1, originalSource.closeCallCount)
        assertEquals(0, replacementDocument.closeCallCount)
        session.close()
        assertEquals(1, replacementDocument.closeCallCount)
    }

    /** Verifies a failed reload leaves local edits and source ownership intact. */
    @Test
    fun keepsAConflictedEditorAfterReloadFailure() {
        val originalDocument = TestEditorDocument("source")
        val originalSource =
            TestDocumentSource(
                encodedUri = RETAINED_SOURCE_URI,
                saveFailure =
                    DocumentExportException(DocumentExportFailure.SOURCE_CONFLICT)
            )
        val originalEditor = createConflictedEditor(originalDocument, originalSource)
        val session =
            BeauTyXTSession(
                sessionDispatcher = ImmediateSessionTestDispatcher,
                createEmptyEditor = { originalEditor }
            )
        session.startNewDocument()
        assertTrue(originalEditor.requestSourceReloadConfirmation())

        assertTrue(
            session.reloadEditorSource(originalEditor) {
                throw IllegalStateException("sensitive reload detail")
            }
        )

        assertSame(originalEditor, session.editor)
        assertTrue(originalEditor.sourceSaveStatus is SourceSaveStatus.Conflict)
        assertTrue(originalEditor.hasUnsavedChanges)
        assertTrue(originalEditor.canAcceptEditorInput)
        assertEquals(0, originalDocument.closeCallCount)
        assertEquals(0, originalSource.closeCallCount)
        session.close()
        assertEquals(1, originalDocument.closeCallCount)
        assertEquals(1, originalSource.closeCallCount)
    }

    /** Verifies sanitized import failures retain only their bounded user message. */
    @Test
    fun publishesASanitizedDocumentImportFailure() {
        val session = createSession()
        assertTrue(session.beginDocumentSelection())

        session.openSelectedDocument {
            throw DocumentImportException(
                failure = DocumentImportFailure.INVALID_UTF8,
                cause = IllegalStateException("sensitive provider detail")
            )
        }

        assertEquals(
            OpenStatus.Failed(DocumentImportFailure.INVALID_UTF8.userMessage),
            session.openStatus
        )
        assertNull(session.editor)
        assertFalse(session.isOpenBusy)
        session.close()
    }

    /** Verifies one retained import publishes exactly one owned editor. */
    @Test
    fun publishesASuccessfulRetainedImport() {
        val document = TestEditorDocument("opened")
        val candidate = createEditor(document, title = "Opened document")
        val editorCompletion = CompletableDeferred<EditorSession>()
        val session = createSession()
        assertTrue(session.beginDocumentSelection())

        session.openSelectedDocument {
            editorCompletion.await()
        }

        assertEquals(OpenStatus.Opening, session.openStatus)
        assertTrue(session.isOpenBusy)
        assertNull(session.editor)

        editorCompletion.complete(candidate)

        val editor = requireNotNull(session.editor)
        assertSame(candidate, editor)
        assertEquals(OpenStatus.Idle, session.openStatus)
        assertFalse(session.isOpenBusy)
        assertEquals("Opened document", editor.title)
        assertEquals(0, document.closeCallCount)

        session.closeEditor()
        assertNull(session.editor)
        assertEquals(1, document.closeCallCount)

        session.close()
        assertEquals(1, document.closeCallCount)
    }

    /** Verifies a process-restored picker result can begin from a fresh session. */
    @Test
    fun opensASelectedDocumentAfterTransientSelectionStateIsLost() {
        val document = TestEditorDocument("restored result")
        val candidate = createEditor(document, title = "Opened document")
        val session = createSession()

        session.openSelectedDocument { candidate }

        assertSame(candidate, session.editor)
        assertEquals(OpenStatus.Idle, session.openStatus)
        session.close()
        assertEquals(1, document.closeCallCount)
    }

    /** Verifies cancellation owns its slot until one late result is closed. */
    @Test
    fun cancelsAndClosesALateImportBeforeStartingANewGeneration() {
        val lateDocument = TestEditorDocument("late")
        val lateCandidate = createEditor(lateDocument, title = "Late document")
        val firstCompletion = CompletableDeferred<EditorSession>()
        val currentDocument = TestEditorDocument("current")
        val currentCandidate = createEditor(currentDocument, title = "Opened document")
        val session = createSession()
        assertTrue(session.beginDocumentSelection())
        session.openSelectedDocument {
            withContext(NonCancellable) {
                firstCompletion.await()
            }
        }

        session.cancelDocumentOpen()

        assertEquals(OpenStatus.Cancelling, session.openStatus)
        assertTrue(session.isOpenBusy)
        assertFalse(session.beginDocumentSelection())
        assertNull(session.editor)

        firstCompletion.complete(lateCandidate)

        assertEquals(OpenStatus.Idle, session.openStatus)
        assertFalse(session.isOpenBusy)
        assertNull(session.editor)
        assertEquals(1, lateDocument.closeCallCount)

        assertTrue(session.beginDocumentSelection())
        session.openSelectedDocument { currentCandidate }

        assertSame(currentCandidate, session.editor)
        assertEquals("Opened document", requireNotNull(session.editor).title)
        assertEquals(OpenStatus.Idle, session.openStatus)
        assertEquals(0, currentDocument.closeCallCount)

        session.close()
        assertEquals(1, lateDocument.closeCallCount)
        assertEquals(1, currentDocument.closeCallCount)
    }

    /** Verifies a post-cancellation failure cannot replace cancellation status. */
    @Test
    fun ignoresAnImportFailureRaisedDuringCancellationCleanup() {
        val cancellationGate = CompletableDeferred<EditorSession>()
        val session = createSession()
        assertTrue(session.beginDocumentSelection())
        session.openSelectedDocument {
            try {
                cancellationGate.await()
            } catch (_: CancellationException) {
                throw IllegalStateException("synthetic cancellation cleanup failure")
            }
        }

        session.cancelDocumentOpen()

        assertEquals(OpenStatus.Idle, session.openStatus)
        assertFalse(session.isOpenBusy)
        assertNull(session.editor)
        assertTrue(session.beginDocumentSelection())

        session.close()
    }

    /** Verifies cancellation completes before a lazily dispatched import starts. */
    @Test
    fun cancelsAnImportBeforeItsCoroutineEnters() {
        val sessionDispatcher = QueuedSessionTestDispatcher()
        val document = TestEditorDocument("must not open")
        val candidate = createEditor(document, title = "Must not open")
        var openerCallCount = 0
        val session = BeauTyXTSession(sessionDispatcher = sessionDispatcher)
        assertTrue(session.beginDocumentSelection())
        session.openSelectedDocument {
            openerCallCount += 1
            candidate
        }

        assertEquals(OpenStatus.Opening, session.openStatus)
        assertEquals(1, sessionDispatcher.pendingCount)

        session.cancelDocumentOpen()

        assertEquals(OpenStatus.Cancelling, session.openStatus)
        assertTrue(session.isOpenBusy)
        assertFalse(session.beginDocumentSelection())

        sessionDispatcher.runAll()

        assertEquals(OpenStatus.Idle, session.openStatus)
        assertFalse(session.isOpenBusy)
        assertEquals(0, openerCallCount)
        assertEquals(0, document.closeCallCount)
        assertNull(session.editor)
        assertTrue(session.beginDocumentSelection())

        session.close()
    }

    /** Verifies session close invalidates and owns a result that arrives afterward. */
    @Test
    fun closesALateImportAfterTheApplicationSessionEnds() {
        val lateDocument = TestEditorDocument("late")
        val lateCandidate = createEditor(lateDocument, title = "Late document")
        val editorCompletion = CompletableDeferred<EditorSession>()
        val session = createSession()
        assertTrue(session.beginDocumentSelection())
        session.openSelectedDocument {
            withContext(NonCancellable) {
                editorCompletion.await()
            }
        }

        session.close()
        session.close()

        assertEquals(OpenStatus.Idle, session.openStatus)
        assertNull(session.editor)
        assertFalse(session.beginDocumentSelection())

        editorCompletion.complete(lateCandidate)

        assertEquals(1, lateDocument.closeCallCount)
        assertNull(session.editor)
        assertEquals(OpenStatus.Idle, session.openStatus)
    }

    /** Verifies one new editor and its document close exactly once. */
    @Test
    fun closesANewEditorExactlyOnce() {
        val document = TestEditorDocument("")
        val editor = createEditor(document, title = "New document")
        var factoryCallCount = 0
        val session =
            BeauTyXTSession(
                sessionDispatcher = ImmediateSessionTestDispatcher,
                createEmptyEditor = {
                    factoryCallCount += 1
                    editor
                }
            )

        session.startNewDocument()
        session.startNewDocument()

        assertSame(editor, session.editor)
        assertEquals(1, factoryCallCount)
        assertEquals(0, document.closeCallCount)

        session.close()
        session.close()
        session.startNewDocument()

        assertNull(session.editor)
        assertEquals(OpenStatus.Idle, session.openStatus)
        assertEquals(1, factoryCallCount)
        assertEquals(1, document.closeCallCount)
    }

    /** Verifies new-document initialization failures remain sanitized and retryable. */
    @Test
    fun reportsNewDocumentCreationFailures() {
        val document = TestEditorDocument("")
        val editor = createEditor(document, title = "New document")
        var factoryCallCount = 0
        val session =
            BeauTyXTSession(
                sessionDispatcher = ImmediateSessionTestDispatcher,
                createEmptyEditor = {
                    factoryCallCount = Math.incrementExact(factoryCallCount)
                    when (factoryCallCount) {
                        1 -> throw IllegalStateException("sensitive factory detail")
                        2 -> throw UnsatisfiedLinkError("sensitive native detail")
                        else -> editor
                    }
                }
            )

        session.startNewDocument()

        assertEquals(
            OpenStatus.Failed(UiText.Resource(R.string.operation_new_document_failure)),
            session.openStatus
        )
        assertNull(session.editor)
        assertFalse(session.isOpenBusy)

        session.startNewDocument()

        assertEquals(
            OpenStatus.Failed(UiText.Resource(R.string.operation_new_document_failure)),
            session.openStatus
        )
        assertNull(session.editor)
        assertFalse(session.isOpenBusy)

        session.startNewDocument()

        assertSame(editor, session.editor)
        assertEquals(OpenStatus.Idle, session.openStatus)
        assertEquals(3, factoryCallCount)
        session.close()
        assertEquals(1, document.closeCallCount)
    }

    /** Verifies picker results route only to the exact retained editor request. */
    @Test
    fun routesOneExactSaveDestinationResult() {
        val document = TestEditorDocument("selected")
        val editor = createEditor(document, title = "New document")
        val session =
            BeauTyXTSession(
                sessionDispatcher = ImmediateSessionTestDispatcher,
                createEmptyEditor = { editor }
            )
        session.startNewDocument()
        editor.openInitialEditor()
        editor.showSaveCopyFormatSelection()
        editor.selectSaveFormat(DocumentFormat.Markdown)

        val request = requireNotNull(session.claimSaveDestination(editor))
        var exportCallCount = 0
        val wrongFormatOwner = TestDestinationOwner()

        assertEquals(DocumentFormat.Markdown, request.format)
        assertFalse(
            session.saveSelectedDestination(
                DocumentFormat.PlainText,
                wrongFormatOwner
            ) { _, _ ->
                exportCallCount += 1
            }
        )
        assertEquals(0, wrongFormatOwner.closeCallCount)
        wrongFormatOwner.close()
        assertEquals(SaveStatus.SelectingDestination(request), editor.saveStatus)
        assertTrue(editor.beginSaveDestinationPreparation(request))
        val acceptedOwner = TestDestinationOwner()
        assertTrue(
            session.saveSelectedDestination(
                DocumentFormat.Markdown,
                acceptedOwner
            ) { _, _ ->
                exportCallCount += 1
            }
        )
        assertEquals(1, exportCallCount)
        assertEquals(
            SaveStatus.Succeeded(request = request, hasNewerChanges = false),
            editor.saveStatus
        )
        assertEquals(1, acceptedOwner.closeCallCount)
        assertTrue(document.capturedSnapshots.single().isClosed)
        assertEquals(1, document.capturedSnapshots.single().closeCallCount)
        val duplicateOwner = TestDestinationOwner()
        assertFalse(
            session.saveSelectedDestination(
                DocumentFormat.Markdown,
                duplicateOwner
            ) { _, _ ->
                exportCallCount += 1
            }
        )
        assertEquals(0, duplicateOwner.closeCallCount)
        duplicateOwner.close()
        assertEquals(1, exportCallCount)

        session.close()
    }

    /** Verifies a first selected source routes only to its exact retained request. */
    @Test
    fun routesOneExactSelectedSourceResult() {
        val document = TestEditorDocument("selected source")
        val editor = createEditor(document, title = "New document")
        val sourceDisplayName = "Provider notes.md"
        val session =
            BeauTyXTSession(
                sessionDispatcher = ImmediateSessionTestDispatcher,
                createEmptyEditor = { editor }
            )
        session.startNewDocument()
        editor.openInitialEditor()
        editor.showSaveFormatSelection()
        editor.selectSaveFormat(DocumentFormat.Markdown)

        val request = requireNotNull(session.claimSaveDestination(editor))
        val wrongFormatSource = TestDocumentSource()
        assertFalse(
            session.saveSelectedSource(
                format = DocumentFormat.PlainText,
                documentSource = wrongFormatSource,
                sourceDisplayName = "Wrong format.txt"
            )
        )
        assertEquals("New document", editor.title)
        assertEquals(0, wrongFormatSource.closeCallCount)
        wrongFormatSource.close()
        assertEquals(SaveStatus.SelectingDestination(request), editor.saveStatus)
        assertTrue(editor.beginSaveDestinationPreparation(request))

        val acceptedSource = TestDocumentSource()
        assertTrue(
            session.saveSelectedSource(
                format = DocumentFormat.Markdown,
                documentSource = acceptedSource,
                sourceDisplayName = sourceDisplayName
            )
        )
        assertEquals(sourceDisplayName, editor.title)
        assertEquals(1, acceptedSource.saveCallCount)
        assertEquals(0, acceptedSource.closeCallCount)

        val duplicateSource = TestDocumentSource()
        assertFalse(
            session.saveSelectedSource(
                format = DocumentFormat.Markdown,
                documentSource = duplicateSource
            )
        )
        assertEquals(0, duplicateSource.closeCallCount)
        duplicateSource.close()

        session.close()
        assertEquals(1, acceptedSource.closeCallCount)
    }

    /** Verifies picker-result work survives a temporary composition exit. */
    @Test
    fun retainsSaveDestinationResultAcrossCompositionExit() {
        val document = TestEditorDocument("selected")
        val editor = createEditor(document, title = "New document")
        val session =
            BeauTyXTSession(
                sessionDispatcher = ImmediateSessionTestDispatcher,
                createEmptyEditor = { editor }
            )
        session.startNewDocument()
        editor.openInitialEditor()
        editor.showSaveFormatSelection()
        editor.selectSaveFormat(DocumentFormat.PlainText)
        val expectedRequest = requireNotNull(session.claimSaveDestination(editor))
        val resultGate = CompletableDeferred<Unit>()
        var resultCompletionCount = 0

        assertTrue(
            session.launchSaveDestinationResult(DocumentFormat.PlainText) { request ->
                assertEquals(expectedRequest, request)
                resultGate.await()
                resultCompletionCount = Math.incrementExact(resultCompletionCount)
                assertTrue(
                    session.failSaveDestination(
                        format = DocumentFormat.PlainText,
                        message = UiText.Literal("Synthetic destination failure")
                    )
                )
            }
        )

        session.onExitedComposition()

        assertEquals(0, resultCompletionCount)
        assertEquals(SaveStatus.PreparingDestination(expectedRequest), editor.saveStatus)

        resultGate.complete(Unit)

        assertEquals(1, resultCompletionCount)
        assertEquals(
            SaveStatus.Failed(
                request = expectedRequest,
                message = UiText.Literal("Synthetic destination failure")
            ),
            editor.saveStatus
        )
        session.close()
    }

    /** Verifies destination preparation cancellation releases its retained result slot. */
    @Test
    fun cancelsSaveDestinationPreparationWithCleanup() {
        val document = TestEditorDocument("selected")
        val editor = createEditor(document, title = "New document")
        val session =
            BeauTyXTSession(
                sessionDispatcher = ImmediateSessionTestDispatcher,
                createEmptyEditor = { editor }
            )
        session.startNewDocument()
        editor.openInitialEditor()
        editor.showSaveFormatSelection()
        editor.selectSaveFormat(DocumentFormat.PlainText)
        val expectedRequest = requireNotNull(session.claimSaveDestination(editor))
        val resultGate = CompletableDeferred<Unit>()
        val cleanupGate = CompletableDeferred<Unit>()
        var cleanupStarted = false
        var cleanupCount = 0

        assertTrue(
            session.launchSaveDestinationResult(DocumentFormat.PlainText) { request ->
                assertEquals(expectedRequest, request)
                try {
                    resultGate.await()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted = true
                        cleanupGate.await()
                        cleanupCount = Math.incrementExact(cleanupCount)
                    }
                }
            }
        )

        assertEquals(SaveStatus.PreparingDestination(expectedRequest), editor.saveStatus)
        assertTrue(session.cancelSaveDestinationResult(editor))

        assertTrue(cleanupStarted)
        assertEquals(
            SaveStatus.CancellingDestinationPreparation(expectedRequest),
            editor.saveStatus
        )
        assertFalse(editor.canCloseSafely)
        cleanupGate.complete(Unit)

        assertEquals(1, cleanupCount)
        assertEquals(
            SaveStatus.Cancelled(expectedRequest),
            editor.saveStatus
        )
        assertFalse(session.cancelSaveDestinationResult(editor))
        session.close()
    }

    /** Verifies an orphaned create result reports its possible empty destination. */
    @Test
    fun reportsASaveResultAfterItsTransientSessionEnds() {
        val session = createSession()

        session.reportAbandonedSaveResult()

        assertEquals(
            OpenStatus.Failed(UiText.Resource(R.string.operation_abandoned_save)),
            session.openStatus
        )
        session.close()
    }

    /** Verifies a pending destination result prevents its editor from closing. */
    @Test
    fun retainsEditorOwnershipUntilDestinationSelectionEnds() {
        val document = TestEditorDocument("pending")
        val editor = createEditor(document, title = "New document")
        val session =
            BeauTyXTSession(
                sessionDispatcher = ImmediateSessionTestDispatcher,
                createEmptyEditor = { editor }
            )
        session.startNewDocument()
        editor.openInitialEditor()
        editor.showSaveFormatSelection()
        editor.selectSaveFormat(DocumentFormat.PlainText)
        requireNotNull(session.claimSaveDestination(editor))

        session.closeEditor()

        assertSame(editor, session.editor)
        assertEquals(0, document.closeCallCount)
        assertFalse(session.closeEditor(afterExit = true))
        assertTrue(session.cancelSaveDestination(DocumentFormat.PlainText))

        session.closeEditor()

        assertNull(session.editor)
        assertEquals(1, document.closeCallCount)
        session.close()
    }

    /** Verifies a result delivered after session close is rejected without use. */
    @Test
    fun rejectsSaveDestinationResultAfterSessionClose() {
        val document = TestEditorDocument("closed")
        val editor = createEditor(document, title = "New document")
        val session =
            BeauTyXTSession(
                sessionDispatcher = ImmediateSessionTestDispatcher,
                createEmptyEditor = { editor }
            )
        session.startNewDocument()
        editor.openInitialEditor()
        editor.showSaveFormatSelection()
        editor.selectSaveFormat(DocumentFormat.Markdown)
        requireNotNull(session.claimSaveDestination(editor))
        var exportCalled = false

        session.close()
        val closedOwner = TestDestinationOwner()

        assertFalse(
            session.saveSelectedDestination(
                DocumentFormat.Markdown,
                closedOwner
            ) { _, _ ->
                exportCalled = true
            }
        )
        assertEquals(0, closedOwner.closeCallCount)
        closedOwner.close()
        assertFalse(exportCalled)
        assertEquals(1, document.closeCallCount)
    }

    /** Verifies composition exit retains resources until permanent retirement. */
    @Test
    fun retainsAnEditorAcrossCompositionExitUntilRetired() {
        val document = TestEditorDocument("retained")
        val editor = createEditor(document, title = "New document")
        val session =
            BeauTyXTSession(
                sessionDispatcher = ImmediateSessionTestDispatcher,
                createEmptyEditor = { editor }
            )
        session.onRetained()
        session.onEnteredComposition()
        session.startNewDocument()

        session.onExitedComposition()

        assertSame(editor, session.editor)
        assertEquals(0, document.closeCallCount)

        session.onEnteredComposition()
        assertSame(editor, session.editor)
        assertEquals(0, document.closeCallCount)

        session.onExitedComposition()
        session.onRetired()
        session.onRetired()

        assertNull(session.editor)
        assertEquals(1, document.closeCallCount)
    }

    /** Keeps a closing page readable until its exit completes and releases it once. */
    @Test
    fun keepsAClosingEditorAliveUntilEntryRetirement() {
        val document = TestEditorDocument("still visible")
        val source = TestDocumentSource(RETAINED_SOURCE_URI)
        val editor = createEditor(document, "Selected document", source)
        val session = BeauTyXTSession(
            sessionDispatcher = ImmediateSessionTestDispatcher,
            createEmptyEditor = { editor }
        )
        session.startNewDocument()
        editor.openInitialEditor()

        assertTrue(session.closeEditor(afterExit = true))
        assertSame(editor, session.editor)
        assertEquals("still visible", requireNotNull(editor.activeDraft).textFieldState.text.toString())
        assertEquals(0, document.closeCallCount)
        assertEquals(0, source.closeCallCount)

        session.onExitedComposition()
        session.onRetired()
        session.onRetired()

        assertNull(session.editor)
        assertEquals(1, document.closeCallCount)
        assertEquals(1, source.closeCallCount)
    }

    /** An animated discard must not retry a conflicted source on composition exit. */
    @Test
    fun retiringAnAnimatedDiscardDoesNotSaveTheDiscardedRevision() {
        val document = TestEditorDocument("source")
        val source = TestDocumentSource(
            saveFailure = DocumentExportException(DocumentExportFailure.SOURCE_CONFLICT)
        )
        val editor = createConflictedEditor(document, source)
        val session = BeauTyXTSession(
            sessionDispatcher = ImmediateSessionTestDispatcher,
            createEmptyEditor = { editor }
        )
        session.startNewDocument()
        val saveCount = source.saveCallCount

        assertTrue(session.closeEditor(afterExit = true))
        session.checkpointPendingEdit()
        session.onExitedComposition()
        assertSame(editor, session.editor)
        assertEquals(saveCount, source.saveCallCount)
        assertEquals(0, document.closeCallCount)

        session.onRetired()

        assertEquals(saveCount, source.saveCallCount)
        assertEquals(1, source.closeCallCount)
        assertEquals(1, document.closeCallCount)
    }

    /** Verifies permanent retirement checkpoints composing text to its source. */
    @Test
    fun checkpointsTheCurrentSourceBeforeRetirement() {
        val document = TestEditorDocument("before")
        val source = TestDocumentSource()
        val editor =
            EditorSession(
                title = "Selected document",
                state = EditorDocumentState(document, ImmediateSessionTestDispatcher),
                documentSource = source,
                operationDispatcher = ImmediateSessionTestDispatcher,
                editSynchronizationDelay = {}
            )
        val session =
            BeauTyXTSession(
                sessionDispatcher = ImmediateSessionTestDispatcher,
                createEmptyEditor = { editor }
            )
        session.startNewDocument()
        editor.openInitialEditor()
        val draft = requireNotNull(editor.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "after")
            selection = TextRange(length)
        }
        editor.observeActiveEdit(
            draft = draft,
            value =
                draft.captureFieldValue().copy(
                    composition = TextRange(start = 0, end = 5)
                )
        )

        session.onRetired()

        assertEquals(1, source.saveCallCount)
        assertEquals(1, source.closeCallCount)
        assertEquals(1, document.closeCallCount)
        assertNull(session.editor)
    }

    /** Verifies an unclaimed retained value releases its resources once. */
    @Test
    fun closesAnUnusedRetainedSessionExactlyOnce() {
        val document = TestEditorDocument("unused")
        val editor = createEditor(document, title = "New document")
        val session =
            BeauTyXTSession(
                sessionDispatcher = ImmediateSessionTestDispatcher,
                createEmptyEditor = { editor }
            )
        session.startNewDocument()

        session.onUnused()
        session.onUnused()

        assertNull(session.editor)
        assertEquals(OpenStatus.Idle, session.openStatus)
        assertEquals(1, document.closeCallCount)
        assertFalse(session.beginDocumentSelection())
    }

    /** Verifies off-thread retirement marshals ownership cleanup to its dispatcher. */
    @Test
    fun dispatchesOffThreadRetirementBeforeClosingResources() {
        val sessionDispatcher = QueuedSessionTestDispatcher()
        val document = TestEditorDocument("retired")
        val editor = createEditor(document, title = "New document")
        val session =
            BeauTyXTSession(
                sessionDispatcher = sessionDispatcher,
                createEmptyEditor = { editor }
            )
        session.startNewDocument()
        val retirementThread = Thread(session::onRetired)

        retirementThread.start()
        retirementThread.join()

        assertSame(editor, session.editor)
        assertEquals(0, document.closeCallCount)
        assertFalse(session.beginDocumentSelection())

        sessionDispatcher.runAll()

        assertNull(session.editor)
        assertEquals(1, document.closeCallCount)
    }

    /** Verifies direct shared text remains an offer until explicit confirmation. */
    @Test
    fun opensConfirmedSharedTextAsATransientEditor() {
        val share =
            IncomingDocumentShare.Text(
                text = "shared",
                format = DocumentFormat.PlainText
            )
        val editor = createEditor(TestEditorDocument("shared"), title = "Shared text.txt")
        val session =
            BeauTyXTSession(
                sessionDispatcher = ImmediateSessionTestDispatcher,
                createTransientTextEditor = { received ->
                    assertSame(share, received)
                    editor
                }
            )

        session.offerIncomingShare(share)

        assertSame(share, session.incomingShare)
        assertNull(session.editor)
        assertTrue(session.openIncomingText())
        assertNull(session.incomingShare)
        assertSame(editor, session.editor)
        session.close()
    }

    /** Verifies a shared provider URI transfers only after confirmation and an idle editor. */
    @Test
    fun transfersOnlyAConfirmedIncomingSource() {
        val share =
            IncomingDocumentShare.Source(
                encodedUri = RETAINED_SOURCE_URI,
                format = DocumentFormat.Markdown
            )
        val existingEditor = createEditor(TestEditorDocument("existing"), title = "Existing")
        val session =
            BeauTyXTSession(
                sessionDispatcher = ImmediateSessionTestDispatcher,
                createEmptyEditor = { existingEditor }
            )
        session.startNewDocument()
        session.offerIncomingShare(share)

        assertNull(session.takeIncomingSource())
        assertSame(share, session.incomingShare)

        session.closeEditor()
        assertSame(share, session.takeIncomingSource())
        assertNull(session.incomingShare)
        session.close()
    }

    /** Verifies Android view and edit requests begin importing without confirmation. */
    @Test
    fun opensDirectAndroidSourceRequestsImmediately() {
        IncomingSourcePurpose.entries
            .filter { purpose -> purpose != IncomingSourcePurpose.Share }
            .forEach { purpose ->
                val document = TestEditorDocument(purpose.name)
                val editor = createEditor(document, title = purpose.name)
                val session = createSession()
                val share =
                    IncomingDocumentShare.Source(
                        encodedUri = RETAINED_SOURCE_URI,
                        format = DocumentFormat.PlainText,
                        purpose = purpose
                    )
                session.offerIncomingShare(share)

                assertTrue(session.openIncomingRequestedSource { editor })
                assertNull(session.incomingShare)
                assertSame(editor, session.editor)

                session.closeEditor()
                assertNull(session.editor)
                assertEquals(1, document.closeCallCount)
                session.close()
            }
    }

    /** Verifies explicit file shares retain their review gate. */
    @Test
    fun keepsSharedFilesBehindConfirmation() {
        val session = createSession()
        val share =
            IncomingDocumentShare.Source(
                encodedUri = RETAINED_SOURCE_URI,
                format = DocumentFormat.PlainText,
                purpose = IncomingSourcePurpose.Share
            )
        session.offerIncomingShare(share)

        assertFalse(session.openIncomingRequestedSource { error("must not open") })
        assertSame(share, session.incomingShare)
        assertNull(session.editor)
        session.close()
    }

    /** Verifies a pending direct source retains opening status until publication. */
    @Test
    fun tracksDirectSourceOpeningUntilImportCompletes() {
        val completion = CompletableDeferred<EditorSession>()
        val document = TestEditorDocument("direct")
        val editor = createEditor(document, title = "Direct")
        val session = createSession()
        session.offerIncomingShare(
            IncomingDocumentShare.Source(
                encodedUri = RETAINED_SOURCE_URI,
                format = DocumentFormat.PlainText,
                purpose = IncomingSourcePurpose.View
            )
        )

        assertTrue(session.openIncomingRequestedSource { completion.await() })
        assertEquals(OpenStatus.Opening, session.openStatus)
        assertNull(session.editor)

        completion.complete(editor)

        assertSame(editor, session.editor)
        assertEquals(OpenStatus.Idle, session.openStatus)
        session.close()
        assertEquals(1, document.closeCallCount)
    }

    /** Creates one application session with an immediate retained dispatcher. */
    private fun createSession(): BeauTyXTSession =
        BeauTyXTSession(sessionDispatcher = ImmediateSessionTestDispatcher)

    /** Creates one editor whose first local change deterministically conflicts. */
    private fun createConflictedEditor(
        document: TestEditorDocument,
        documentSource: TestDocumentSource
    ): EditorSession {
        val editor =
            EditorSession(
                title = "Conflicted document",
                state = EditorDocumentState(document, ImmediateSessionTestDispatcher),
                documentSource = documentSource,
                operationDispatcher = ImmediateSessionTestDispatcher,
                editSynchronizationDelay = {}
            )
        editor.openInitialEditor()
        val draft = requireNotNull(editor.activeDraft)
        draft.textFieldState.edit {
            replace(start = 0, end = length, text = "local edit")
            selection = TextRange(length)
        }
        editor.observeActiveEdit(draft, draft.captureFieldValue())
        check(editor.sourceSaveStatus is SourceSaveStatus.Conflict) {
            "test editor did not reach a source conflict"
        }
        return editor
    }

    /** Creates one synchronously dispatched editor over a test document. */
    private fun createEditor(
        document: TestEditorDocument,
        title: String,
        documentSource: EditorDocumentSource? = null
    ): EditorSession = EditorSession(
        title = title,
        state = EditorDocumentState(document, ImmediateSessionTestDispatcher),
        documentSource = documentSource,
        operationDispatcher = ImmediateSessionTestDispatcher
    )
}
