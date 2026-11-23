package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.text.TextRange
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.importing.client.SelectedDocumentMetadata
import dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.QueuedSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.autosave.NewDocumentAutosaveGate
import dev.soupslurpr.beautyxt.ui.autosave.NewDocumentAutosaveReadiness
import dev.soupslurpr.beautyxt.ui.autosave.NewDocumentCreationResult
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val TEST_CLOCK: Clock = Clock.fixed(Instant.parse("2026-11-23T15:07:03Z"), ZoneId.of("UTC"))

/** Verifies lazy new-document creation, serialization, failure recovery, and received-text scoping. */
class EditorSessionNewDocumentAutosaveTest {
    @Test
    fun untouchedEmptyDocumentCreatesZeroFilesAndClosesImmediately() {
        val gate = FakeAutosaveGate()
        val session = createNewSession(gate)
        session.openInitialEditor()

        assertEquals(NewDocumentAutosaveState.Ready, session.newDocumentAutosaveState)
        assertEquals(0, gate.createCalls)
        assertEquals(CloseRequestResult.CloseNow, session.requestClose())
        assertEquals(0, gate.createCalls)
        session.close()
    }

    @Test
    fun typingThenClearingBeforeSyncCreatesZeroFiles() {
        val delayGate = CompletableDeferred<Unit>()
        val gate = FakeAutosaveGate()
        val session = EditorSession(
            title = "New document",
            state = EditorDocumentState(
                document = TestEditorDocument(""),
                workerDispatcher = ImmediateSessionTestDispatcher
            ),
            operationDispatcher = ImmediateSessionTestDispatcher,
            editSynchronizationDelay = { delayGate.await() },
            newDocumentAutosave = gate,
            clock = TEST_CLOCK
        )
        session.openInitialEditor()

        editDraft(session, "Draft")
        editDraft(session, "")
        delayGate.complete(Unit)

        assertEquals(0, gate.createCalls)
        assertFalse(session.hasDocumentSource)
        session.close()
    }

    @Test
    fun sessionStartedWhileSettingsLoadingStillAutosavesOnceLoaded() {
        val savedSource = TestEditorDocumentSource("content://provider/document/cold-start")
        val gate = FakeAutosaveGate(
            readiness = NewDocumentAutosaveReadiness.Loading,
            format = DocumentFormat.Markdown,
            onCreate = { suggestName ->
                val name = suggestName(DocumentFormat.Markdown)
                NewDocumentCreationResult.Created(
                    source = savedSource,
                    format = DocumentFormat.Markdown,
                    displayName = name,
                    metadata = SelectedDocumentMetadata(displayName = name)
                )
            }
        )
        val session = createNewSession(gate)
        session.openInitialEditor()

        // While settings are still loading, UI does not prematurely promise autosave.
        assertEquals(NewDocumentAutosaveState.Inactive, session.effectiveNewDocumentAutosaveState)

        // Settings finish loading as Armed before typing begins.
        gate.readiness = NewDocumentAutosaveReadiness.Armed
        assertEquals(NewDocumentAutosaveState.Ready, session.effectiveNewDocumentAutosaveState)

        editDraft(session, "Cold start note")
        assertEquals(1, gate.createCalls)
        assertEquals("2026-11-23 15.07.03.md", session.title)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertEquals(listOf("Cold start note"), savedSource.savedTexts)
        session.close()
    }

    @Test
    fun deferredCreationSurfacesRetryableUnavailableStateInsteadOfStuckSavingSoon() {
        val gate = FakeAutosaveGate(
            onCreate = { NewDocumentCreationResult.Deferred }
        )
        val session = createNewSession(gate)
        session.openInitialEditor()

        editDraft(session, "Stalled folder")
        assertEquals(1, gate.createCalls)
        assertEquals(
            NewDocumentAutosaveState.Failed(
                message = UiText.Resource(R.string.autosave_folder_unavailable),
                canRetryCreate = true
            ),
            session.newDocumentAutosaveState
        )
        assertEquals(
            UiText.Resource(R.string.status_autosave_failed),
            saveStateDescription(
                sourceSaveStatus = session.sourceSaveStatus,
                saveStatus = session.saveStatus,
                hasUnsavedChanges = session.hasUnsavedChanges,
                saveUnavailableReason = session.saveUnavailableReason,
                isViewOnly = session.isViewOnly,
                newDocumentAutosaveState = session.newDocumentAutosaveState
            )
        )
        assertEquals(
            EditorStatusTone.Error,
            saveStateTone(
                sourceSaveStatus = session.sourceSaveStatus,
                saveStatus = session.saveStatus,
                hasUnsavedChanges = session.hasUnsavedChanges,
                newDocumentAutosaveState = session.newDocumentAutosaveState
            )
        )
        assertTrue(session.canStartSaveAs)
        assertTrue(session.canCloseSafely)
        session.close()
    }

    @Test
    fun firstNonEmptyEditLazilyCreatesFileAndVerifiesAutosave() {
        val savedSource = TestEditorDocumentSource("content://provider/document/1")
        val gate = FakeAutosaveGate(
            format = DocumentFormat.Markdown,
            onCreate = { suggestName ->
                val name = suggestName(DocumentFormat.Markdown)
                NewDocumentCreationResult.Created(
                    source = savedSource,
                    format = DocumentFormat.Markdown,
                    displayName = name,
                    metadata = SelectedDocumentMetadata(displayName = name)
                )
            }
        )
        val session = createNewSession(gate)
        session.openInitialEditor()

        assertEquals(DocumentFormat.Markdown, session.documentFormat)
        editDraft(session, "Interview notes")

        assertEquals(1, gate.createCalls)
        assertEquals("2026-11-23 15.07.03.md", session.title)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertEquals(NewDocumentAutosaveState.Inactive, session.newDocumentAutosaveState)
        assertTrue(session.hasDocumentSource)
        assertFalse(session.hasUnsavedChanges)
        assertEquals(listOf("Interview notes"), savedSource.savedTexts)

        // Subsequent edits use normal source autosave and never re-run creation.
        editDraft(session, "Interview notes\nFollow-up question")
        assertEquals(1, gate.createCalls)
        assertEquals(
            listOf("Interview notes", "Interview notes\nFollow-up question"),
            savedSource.savedTexts
        )
        session.close()
    }

    @Test
    fun firstWriteFailureAfterCreationRetriesSameFileWithoutCreatingDuplicate() {
        var failFirstWrite = true
        val savedSource = TestEditorDocumentSource(
            encodedUri = "content://provider/document/first-write-fail",
            save = { _, _ ->
                if (failFirstWrite) {
                    failFirstWrite = false
                    throw IOException("transient write failure")
                }
            }
        )
        val gate = FakeAutosaveGate(
            onCreate = { suggestName ->
                val name = suggestName(DocumentFormat.PlainText)
                NewDocumentCreationResult.Created(
                    source = savedSource,
                    format = DocumentFormat.PlainText,
                    displayName = name,
                    metadata = SelectedDocumentMetadata(displayName = name)
                )
            }
        )
        val session = createNewSession(gate)
        session.openInitialEditor()

        editDraft(session, "Important notes")
        assertEquals(1, gate.createCalls)
        assertTrue(session.sourceSaveStatus is SourceSaveStatus.Failed)
        assertTrue(session.hasDocumentSource)

        session.retrySourceSave()
        assertEquals(1, gate.createCalls)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertEquals(listOf("Important notes", "Important notes"), savedSource.savedTexts)
        session.close()
    }

    @Test
    fun composingTextCheckpointedOnPauseTriggersLazyCreation() {
        val savedSource = TestEditorDocumentSource("content://provider/document/2")
        val gate = FakeAutosaveGate(
            onCreate = { suggestName ->
                val name = suggestName(DocumentFormat.PlainText)
                NewDocumentCreationResult.Created(
                    source = savedSource,
                    format = DocumentFormat.PlainText,
                    displayName = name,
                    metadata = SelectedDocumentMetadata(displayName = name)
                )
            }
        )
        val session = createNewSession(gate)
        session.openInitialEditor()

        val draft = checkNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(0, length, "Quick note")
            selection = TextRange(10)
        }
        val composingValue = ActiveEditFieldValue(
            text = "Quick note",
            selection = TextRange(10),
            composition = TextRange(0, 10)
        )
        session.observeActiveEdit(draft, composingValue)
        assertEquals(0, gate.createCalls)

        session.checkpointPendingEdit()

        assertEquals(1, gate.createCalls)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertEquals(listOf("Quick note"), savedSource.savedTexts)
        session.close()
    }

    @Test
    fun failedCreationLatchesUntilExplicitRetryAndManualSaveRemainsAvailable() {
        var shouldFail = true
        val savedSource = TestEditorDocumentSource("content://provider/document/3")
        val gate = FakeAutosaveGate(
            onCreate = { suggestName ->
                if (shouldFail) {
                    NewDocumentCreationResult.Failed(
                        message = UiText.Resource(R.string.autosave_create_failed),
                        canRetryCreate = true
                    )
                } else {
                    val name = suggestName(DocumentFormat.PlainText)
                    NewDocumentCreationResult.Created(
                        source = savedSource,
                        format = DocumentFormat.PlainText,
                        displayName = name,
                        metadata = SelectedDocumentMetadata(displayName = name)
                    )
                }
            }
        )
        val session = createNewSession(gate)
        session.openInitialEditor()

        editDraft(session, "First line")
        assertEquals(1, gate.createCalls)
        assertTrue(session.newDocumentAutosaveState is NewDocumentAutosaveState.Failed)
        assertTrue(session.hasUnsavedChanges)
        assertTrue(session.canStartSaveAs)

        // Further edits do not spam creation while latched.
        editDraft(session, "First line\nSecond line")
        assertEquals(1, gate.createCalls)

        shouldFail = false
        session.retryNewDocumentAutosave()
        assertEquals(2, gate.createCalls)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertEquals(listOf("First line\nSecond line"), savedSource.savedTexts)
        session.close()
    }

    @Test
    fun manualSavePreemptsAndSupersedesAutoCreation() {
        val delayGate = CompletableDeferred<Unit>()
        val manualSource = TestEditorDocumentSource("content://provider/manual/1")
        val gate = FakeAutosaveGate()
        val session = EditorSession(
            title = "New document",
            state = EditorDocumentState(
                document = TestEditorDocument(""),
                workerDispatcher = ImmediateSessionTestDispatcher
            ),
            operationDispatcher = ImmediateSessionTestDispatcher,
            editSynchronizationDelay = { delayGate.await() },
            newDocumentAutosave = gate,
            clock = TEST_CLOCK
        )
        session.openInitialEditor()

        editDraft(session, "Manual destination")
        assertTrue(session.showSaveFormatSelection())
        delayGate.complete(Unit)
        assertEquals(0, gate.createCalls)

        session.selectSaveFormat(DocumentFormat.PlainText)
        val request = checkNotNull(session.claimSaveDestination())
        assertTrue(session.beginSaveDestinationPreparation(request))
        assertTrue(
            session.saveSelectedSource(
                request = request,
                documentSource = manualSource,
                sourceDisplayName = "chosen.txt"
            )
        )
        assertEquals(0, gate.createCalls)
        assertEquals("chosen.txt", session.title)
        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertEquals(NewDocumentAutosaveState.Inactive, session.newDocumentAutosaveState)
        assertEquals(listOf("Manual destination"), manualSource.savedTexts)
        session.close()
    }

    @Test
    fun closeDuringCreationQueuesUntilVerifiedSaveCompletes() {
        val dispatcher = QueuedSessionTestDispatcher()
        val createGate = CompletableDeferred<NewDocumentCreationResult>()
        val savedSource = TestEditorDocumentSource("content://provider/document/4")
        val gate = FakeAutosaveGate(onCreate = { createGate.await() })
        val session = EditorSession(
            title = "New document",
            state = EditorDocumentState(
                document = TestEditorDocument(""),
                workerDispatcher = dispatcher
            ),
            operationDispatcher = dispatcher,
            editSynchronizationDelay = {},
            newDocumentAutosave = gate,
            clock = TEST_CLOCK
        )
        session.openInitialEditor()
        dispatcher.runAll()

        editDraft(session, "Closing safely")
        dispatcher.runAll()
        assertEquals(NewDocumentAutosaveState.Creating, session.newDocumentAutosaveState)
        assertFalse(session.canCloseSafely)
        assertFalse(session.canStartSaveAs)

        assertEquals(CloseRequestResult.Queued, session.requestClose())
        assertTrue(session.isClosePending)

        createGate.complete(
            NewDocumentCreationResult.Created(
                source = savedSource,
                format = DocumentFormat.PlainText,
                displayName = "2026-11-23 15.07.03.txt",
                metadata = SelectedDocumentMetadata(displayName = "2026-11-23 15.07.03.txt")
            )
        )
        dispatcher.runAll()

        assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
        assertTrue(session.canCloseSafely)
        assertEquals(CloseRequestResult.CloseNow, session.resolvePendingClose())
        assertEquals(listOf("Closing safely"), savedSource.savedTexts)
        session.close()
    }

    @Test
    fun unadoptedCreatedSourceIsClosedWhenSessionClosesAfterCreateReturns() {
        val savedSource = TestEditorDocumentSource("content://provider/document/5")
        lateinit var session: EditorSession
        val gate = FakeAutosaveGate(
            onCreate = {
                session.close()
                NewDocumentCreationResult.Created(
                    source = savedSource,
                    format = DocumentFormat.PlainText,
                    displayName = "2026-11-23 15.07.03.txt",
                    metadata = null
                )
            }
        )
        session = createNewSession(gate)
        session.openInitialEditor()

        editDraft(session, "Aborted")

        assertTrue(savedSource.savedTexts.isEmpty())
        assertFalse(session.hasDocumentSource)
        assertEquals(1, savedSource.closeCallCount)
    }

    @Test
    fun transientSessionWithoutGateNeverAutoCreatesFiles() {
        val session = EditorSession(
            title = "Shared text.txt",
            state = EditorDocumentState(
                document = TestEditorDocument("Shared snippet"),
                workerDispatcher = ImmediateSessionTestDispatcher
            ),
            operationDispatcher = ImmediateSessionTestDispatcher,
            editSynchronizationDelay = {},
            clock = TEST_CLOCK
        )
        session.openInitialEditor()
        editDraft(session, "Shared snippet edited")
        assertEquals(NewDocumentAutosaveState.Inactive, session.newDocumentAutosaveState)
        assertEquals(SourceSaveStatus.NoSource, session.sourceSaveStatus)
        assertFalse(session.hasDocumentSource)
        session.close()
    }

    @Test
    fun uncertainCreationFailureDoesNotAllowBlindRetry() {
        val gate = FakeAutosaveGate(
            onCreate = {
                NewDocumentCreationResult.Failed(
                    message = UiText.Resource(R.string.autosave_create_uncertain),
                    canRetryCreate = false
                )
            }
        )
        val session = createNewSession(gate)
        session.openInitialEditor()

        editDraft(session, "Uncertain write")
        assertEquals(1, gate.createCalls)
        val failed = session.newDocumentAutosaveState as NewDocumentAutosaveState.Failed
        assertFalse(failed.canRetryCreate)

        session.retryNewDocumentAutosave()
        assertEquals(1, gate.createCalls)
        assertTrue(session.canStartSaveAs)
        session.close()
    }

    @Test
    fun saveStateDescriptionAndToneHighlightUnsavedMemoryDocuments() {
        assertEquals(
            EditorStatusTone.Standard,
            saveStateTone(
                sourceSaveStatus = SourceSaveStatus.NoSource,
                saveStatus = SaveStatus.Idle,
                hasUnsavedChanges = false
            )
        )
        assertEquals(
            EditorStatusTone.UnsavedAttention,
            saveStateTone(
                sourceSaveStatus = SourceSaveStatus.NoSource,
                saveStatus = SaveStatus.Idle,
                hasUnsavedChanges = true
            )
        )
        assertEquals(
            EditorStatusTone.Standard,
            saveStateTone(
                sourceSaveStatus = SourceSaveStatus.NoSource,
                saveStatus = SaveStatus.Idle,
                hasUnsavedChanges = true,
                newDocumentAutosaveState = NewDocumentAutosaveState.Ready
            )
        )
        assertEquals(
            EditorStatusTone.Error,
            saveStateTone(
                sourceSaveStatus = SourceSaveStatus.NoSource,
                saveStatus = SaveStatus.Idle,
                hasUnsavedChanges = true,
                newDocumentAutosaveState = NewDocumentAutosaveState.Failed(
                    UiText.Resource(R.string.autosave_create_failed),
                    canRetryCreate = true
                )
            )
        )
        assertEquals(
            UiText.Resource(R.string.status_new_autosave_ready),
            saveStateDescription(
                sourceSaveStatus = SourceSaveStatus.NoSource,
                saveStatus = SaveStatus.Idle,
                hasUnsavedChanges = false,
                saveUnavailableReason = null,
                newDocumentAutosaveState = NewDocumentAutosaveState.Ready
            )
        )
        assertEquals(
            UiText.Resource(R.string.status_new_autosave_creating),
            saveStateDescription(
                sourceSaveStatus = SourceSaveStatus.NoSource,
                saveStatus = SaveStatus.Idle,
                hasUnsavedChanges = true,
                saveUnavailableReason = null,
                newDocumentAutosaveState = NewDocumentAutosaveState.Creating
            )
        )
        assertEquals(
            UiText.Resource(R.string.file_info_autosave_ready),
            fileInfoSaveState(
                status = SourceSaveStatus.NoSource,
                isViewOnly = false,
                newDocumentAutosaveState = NewDocumentAutosaveState.Ready
            )
        )
        assertEquals(
            UiText.Resource(R.string.file_info_autosave_creating),
            fileInfoSaveState(
                status = SourceSaveStatus.NoSource,
                isViewOnly = false,
                newDocumentAutosaveState = NewDocumentAutosaveState.Creating
            )
        )
    }

    @Test
    fun closeAfterCheckpointBoundsDeferredCreationAttemptsDuringTeardown() = runBlocking {
        val gate = FakeAutosaveGate(
            readiness = NewDocumentAutosaveReadiness.Armed,
            onCreate = { NewDocumentCreationResult.Deferred }
        )
        val session = createNewSession(gate)
        session.openInitialEditor()
        editDraft(session, "Draft while folder stays unavailable")
        assertEquals(1, gate.createCalls)

        session.closeAfterCheckpoint()

        // Teardown may try at most one bounded attempt and must terminate cleanly.
        assertTrue(gate.createCalls <= 2)
    }

    private fun createNewSession(gate: FakeAutosaveGate): EditorSession = EditorSession(
        title = "New document",
        state = EditorDocumentState(
            document = TestEditorDocument(""),
            workerDispatcher = ImmediateSessionTestDispatcher
        ),
        sourceFormat = if (gate.readiness == NewDocumentAutosaveReadiness.Armed) {
            gate.preferredFormat
        } else {
            DocumentFormat.PlainText
        },
        operationDispatcher = ImmediateSessionTestDispatcher,
        editSynchronizationDelay = {},
        newDocumentAutosave = gate,
        clock = TEST_CLOCK
    )

    private fun editDraft(session: EditorSession, updatedText: String) {
        val draft = checkNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(0, length, updatedText)
            selection = TextRange(updatedText.length)
        }
        val value = ActiveEditFieldValue(
            text = updatedText,
            selection = TextRange(updatedText.length),
            composition = null
        )
        session.observeActiveEdit(draft, value)
    }

    private class FakeAutosaveGate(
        override var readiness: NewDocumentAutosaveReadiness = NewDocumentAutosaveReadiness.Armed,
        val format: DocumentFormat = DocumentFormat.PlainText,
        private val onCreate: suspend ((DocumentFormat) -> String) -> NewDocumentCreationResult = {
            NewDocumentCreationResult.Disabled
        }
    ) : NewDocumentAutosaveGate {
        var createCalls = 0
            private set

        override val preferredFormat: DocumentFormat
            get() = format

        override suspend fun createNewDocument(
            suggestName: (DocumentFormat) -> String
        ): NewDocumentCreationResult {
            createCalls += 1
            return onCreate(suggestName)
        }
    }
}
