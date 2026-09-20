package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.text.TextRange
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.DocumentFormat
import dev.soupslurpr.beautyxt.document.EditorDocument
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.document.Utf16Range
import dev.soupslurpr.beautyxt.document.ViewportCursor
import dev.soupslurpr.beautyxt.document.ViewportLimits
import dev.soupslurpr.beautyxt.document.ViewportSnapshot
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.MarkdownSourceMap
import dev.soupslurpr.beautyxt.markdown.MarkdownSourceRange
import dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderException
import dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderFailure
import dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderer
import dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.QueuedSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.TestDestinationOwner
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import dev.soupslurpr.beautyxt.testing.TestEditorDocumentSnapshot
import dev.soupslurpr.beautyxt.ui.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val INITIAL_MARKDOWN = "# Heading"
private const val EDITED_MARKDOWN = "# Changed"
private const val SAVED_PREVIEW_SOURCE_OFFSET = 4L
private const val SAVED_PREVIEW_TOP_OFFSET_PIXELS = -7

/** Verifies retained Markdown preview transitions independently from Compose. */
class MarkdownPreviewSessionTest {
    @Test
    fun navigatesOnlyHeadingsFromTheCurrentPreviewRevision() {
        createSession(
            TestEditorDocument(INITIAL_MARKDOWN),
            RecordingMarkdownRenderer()
        ).use { session ->
            session.openInitialEditor()
            assertTrue(session.showMarkdownPreview())
            val ready = session.markdownPreviewStatus as MarkdownPreviewStatus.Ready
            val entry = ready.layout.outline.single()
            session.observeMarkdownPreviewViewportAnchor(ready.revision, 4L)

            assertFalse(session.navigateToHeading(ready.revision + 1, entry))
            assertFalse(session.navigateToHeading(ready.revision, entry.copy(sourceOffset = 999)))
            assertTrue(session.navigateToHeading(ready.revision, entry))
            val navigated = session.markdownPreviewStatus as MarkdownPreviewStatus.Ready
            assertEquals(entry.sourceOffset, navigated.scrollRestoration?.sourceUtf16Offset)
            assertEquals(0, navigated.scrollRestoration?.viewportTopOffsetPixels)

            session.showTextEditor()
            assertEquals(TextRange.Zero, session.activeDraft?.captureFieldValue()?.selection)
            assertFalse(session.navigateToHeading(ready.revision, entry))
        }
    }

    @Test
    fun closesInitialReadingThroughUnsavedContentProtection() {
        val document = TestEditorDocument("")
        val received = document.replace(0, Utf16Range(0, 0), INITIAL_MARKDOWN)
        EditorSession(
            title = "Received document.md",
            state = EditorDocumentState(
                document,
                ImmediateSessionTestDispatcher,
                initialRevision = received.revision,
                initialUnsavedContent = true
            ),
            initialPresentation = EditorPresentation.MarkdownPreview,
            operationDispatcher = ImmediateSessionTestDispatcher,
            markdownRenderer = RecordingMarkdownRenderer()
        ).use { session ->
            session.openInitialEditor()

            assertFalse(session.returnsToSourceOnBack)
            assertEquals(CloseRequestResult.ConfirmationShown, session.requestClose())
            assertTrue(session.isDiscardConfirmationVisible)
            assertEquals(EditorPresentation.MarkdownPreview, session.presentation)
            assertNull(session.activeDraft)
        }
    }

    @Test
    fun closesInitialReadOnlyPreviewWithoutOpeningSource() {
        createSession(
            TestEditorDocument(INITIAL_MARKDOWN),
            RecordingMarkdownRenderer(),
            documentSource = PreviewReadOnlySource(),
            initialPresentation = EditorPresentation.MarkdownPreview
        ).use { session ->
            session.openInitialEditor()

            assertFalse(session.returnsToSourceOnBack)
            assertEquals(CloseRequestResult.CloseNow, session.requestClose())
            assertNull(session.activeDraft)
        }
    }

    @Test
    fun returnsExplicitPreviewToItsSourceEditor() {
        createSession(
            TestEditorDocument(INITIAL_MARKDOWN),
            RecordingMarkdownRenderer()
        ).use { session ->
            session.openInitialEditor()
            assertFalse(session.returnsToSourceOnBack)
            assertTrue(session.showMarkdownPreview())

            assertTrue(session.returnsToSourceOnBack)
            session.showTextEditor()
            assertFalse(session.returnsToSourceOnBack)
            assertEquals(INITIAL_MARKDOWN, session.activeDraft?.captureFieldValue()?.text)
        }
    }

    @Test
    fun makesLaterExplicitPreviewNestedAfterLeavingInitialReading() {
        createSession(
            TestEditorDocument(INITIAL_MARKDOWN),
            RecordingMarkdownRenderer(),
            initialPresentation = EditorPresentation.MarkdownPreview
        ).use { session ->
            session.openInitialEditor()
            assertFalse(session.returnsToSourceOnBack)

            session.showTextEditor()
            assertTrue(session.showMarkdownPreview())

            assertTrue(session.returnsToSourceOnBack)
        }
    }

    @Test
    fun retainsReadingBackDestinationThroughFailureAndRetry() {
        var attempts = 0
        val renderer = RecordingMarkdownRenderer()
        createSession(
            TestEditorDocument(INITIAL_MARKDOWN),
            MarkdownRenderer { snapshot, expectedBytes ->
                attempts += 1
                if (attempts == 1) throw MarkdownRenderException(MarkdownRenderFailure.TooComplex)
                renderer.render(snapshot, expectedBytes)
            },
            initialPresentation = EditorPresentation.MarkdownPreview
        ).use { session ->
            session.openInitialEditor()
            assertTrue(session.markdownPreviewStatus is MarkdownPreviewStatus.Failed)
            assertFalse(session.returnsToSourceOnBack)

            assertTrue(session.retryMarkdownPreview())

            assertTrue(session.markdownPreviewStatus is MarkdownPreviewStatus.Ready)
            assertFalse(session.returnsToSourceOnBack)
        }
    }

    @Test
    fun preparesPreviewNavigationOnWorkerBeforePublishing() {
        val document = TestEditorDocument(INITIAL_MARKDOWN)
        val worker = QueuedSessionTestDispatcher()
        createSession(
            document,
            RecordingMarkdownRenderer(),
            workerDispatcher = worker
        ).use { session ->
            session.openInitialEditor()
            worker.runAll()

            assertTrue(session.showMarkdownPreview())
            assertTrue(session.markdownPreviewStatus is MarkdownPreviewStatus.Rendering)
            assertEquals(1, worker.pendingCount)
            worker.runAll()

            val ready = session.markdownPreviewStatus as MarkdownPreviewStatus.Ready
            assertEquals(0, ready.layout.headings.itemIndex("heading"))
            assertSame(ready.document.blocks.single(), ready.layout.items.single().blocks.single())
            assertTrue(document.capturedSnapshots.all { snapshot -> snapshot.closeCallCount == 1 })
        }
    }

    @Test
    fun discardsQueuedPreviewPreparationWhenSourceWins() {
        val document = TestEditorDocument(INITIAL_MARKDOWN)
        val worker = QueuedSessionTestDispatcher()
        createSession(
            document,
            RecordingMarkdownRenderer(),
            workerDispatcher = worker
        ).use { session ->
            session.openInitialEditor()
            worker.runAll()
            assertTrue(session.showMarkdownPreview())
            assertTrue(session.markdownPreviewStatus is MarkdownPreviewStatus.Rendering)

            session.showTextEditor()
            worker.runAll()

            assertEquals(EditorPresentation.Text, session.presentation)
            assertEquals(MarkdownPreviewStatus.Idle, session.markdownPreviewStatus)
            assertTrue(document.capturedSnapshots.all { snapshot -> snapshot.closeCallCount == 1 })
        }
    }

    /** Opens incoming Markdown without creating or focusing an editable source window. */
    @Test
    fun opensInitialPreviewWithoutActivatingEditor() {
        val document = TestEditorDocument(INITIAL_MARKDOWN)
        val renderer = RecordingMarkdownRenderer()
        createSession(
            document,
            renderer,
            initialPresentation = EditorPresentation.MarkdownPreview,
            shouldFocusInitialEditor = true
        ).use { session ->
            session.openInitialEditor()

            assertEquals(EditorPresentation.MarkdownPreview, session.presentation)
            assertTrue(session.markdownPreviewStatus is MarkdownPreviewStatus.Ready)
            assertNull(session.activeDraft)
            assertTrue(document.editWindowCalls.isEmpty())
            assertFalse(session.isViewOnly)
            assertEquals(listOf(INITIAL_MARKDOWN), renderer.renderedTexts)
            assertTrue(document.capturedSnapshots.all(TestEditorDocumentSnapshot::isClosed))

            session.showTextEditor()

            val draft = requireNotNull(session.activeDraft)
            assertEquals(INITIAL_MARKDOWN, draft.textFieldState.text.toString())
            assertTrue(draft.shouldRestoreEditorFocus)
        }
    }

    /** Keeps read-only provider capabilities unchanged in both initial preview and source. */
    @Test
    fun keepsInitialPreviewReadOnly() {
        val document = TestEditorDocument(INITIAL_MARKDOWN)
        createSession(
            document,
            RecordingMarkdownRenderer(),
            documentSource = PreviewReadOnlySource(),
            initialPresentation = EditorPresentation.MarkdownPreview
        ).use { session ->
            session.openInitialEditor()

            assertEquals(EditorPresentation.MarkdownPreview, session.presentation)
            assertTrue(session.isViewOnly)
            assertNull(session.activeDraft)

            session.showTextEditor()

            assertEquals(EditorPresentation.Text, session.presentation)
            assertTrue(session.isViewOnly)
            assertNull(session.activeDraft)
            assertTrue(document.editWindowCalls.isEmpty())
        }
    }

    /** Returns a read-only source to the reading position without opening an editable field. */
    @Test
    fun followsTheReadingPositionInReadOnlySource() {
        val text = "# Heading\n\nEarlier paragraph\n\nLater paragraph\n"
        val document = SourcePositionPreviewDocument(TestEditorDocument(text))
        createSession(
            document,
            RecordingMarkdownRenderer(),
            documentSource = PreviewReadOnlySource()
        ).use { session ->
            session.openScrolledPreview()
            val offset = text.indexOf("Later").toLong()
            session.observeMarkdownPreviewViewportAnchor(0L, offset, -7)

            session.showTextEditor()

            assertEquals(EditorPresentation.Text, session.presentation)
            assertEquals(offset, session.state.blocks.first().block.globalUtf16Start)
            assertEquals("Later paragraph", session.state.blocks.first().block.text)
            assertTrue(session.isViewOnly)
            assertNull(session.activeDraft)
            assertTrue(document.delegate.editWindowCalls.isEmpty())
        }
    }

    /** Retains the old viewport after a failed source jump and permits an exact retry. */
    @Test
    fun retriesReadOnlySourceNavigationWithoutLosingTheViewport() {
        val text = "# Heading\n\nEarlier paragraph\n\nLater paragraph\n"
        val document = SourcePositionPreviewDocument(TestEditorDocument(text))
        createSession(
            document,
            RecordingMarkdownRenderer(),
            documentSource = PreviewReadOnlySource()
        ).use { session ->
            session.openScrolledPreview()
            val previousBlocks = session.state.blocks.toList()
            val offset = text.indexOf("Later").toLong()
            session.observeMarkdownPreviewViewportAnchor(0L, offset, -7)
            document.beforePositionedViewport = { error("injected viewport failure") }

            session.showTextEditor()

            assertEquals(EditorPresentation.MarkdownPreview, session.presentation)
            assertTrue(session.markdownPreviewStatus is MarkdownPreviewStatus.Failed)
            assertEquals(previousBlocks, session.state.blocks.toList())
            assertNull(session.readOnlySourceScrollRestoration)
            assertTrue(session.canShowTextEditor)

            document.beforePositionedViewport = {}
            session.showTextEditor()

            assertEquals(EditorPresentation.Text, session.presentation)
            assertEquals(offset, session.state.blocks.first().block.globalUtf16Start)
            assertNull(session.activeDraft)
        }
    }

    /** Coalesces repeated source requests and prevents queued work from publishing after close. */
    @Test
    fun ownsQueuedReadOnlySourceNavigationUntilCompletionOrClose() {
        for (closeWhilePending in listOf(false, true)) {
            val worker = QueuedSessionTestDispatcher()
            val text = "# Heading\n\nLater paragraph\n"
            val document = SourcePositionPreviewDocument(TestEditorDocument(text))
            val session = createSession(
                document,
                RecordingMarkdownRenderer(),
                documentSource = PreviewReadOnlySource(),
                initialPresentation = EditorPresentation.MarkdownPreview,
                workerDispatcher = worker
            )
            try {
                session.openInitialEditor()
                worker.runAll()
                val ready = session.markdownPreviewStatus as MarkdownPreviewStatus.Ready
                session.consumeMarkdownPreviewScrollRestoration(
                    ready.revision,
                    requireNotNull(ready.scrollRestoration)
                )
                val offset = text.indexOf("Later").toLong()
                session.observeMarkdownPreviewViewportAnchor(ready.revision, offset, -7)

                session.showTextEditor()
                assertFalse(session.canShowTextEditor)
                session.showTextEditor()
                if (closeWhilePending) session.close()
                worker.runAll()

                if (closeWhilePending) {
                    assertNull(session.readOnlySourceScrollRestoration)
                    assertEquals(0, document.positionedViewportCalls)
                } else {
                    assertEquals(1, document.positionedViewportCalls)
                    assertEquals(EditorPresentation.Text, session.presentation)
                    assertEquals(offset, session.state.blocks.first().block.globalUtf16Start)
                }
                assertNull(session.activeDraft)
                assertTrue(document.delegate.editWindowCalls.isEmpty())
            } finally {
                session.close()
                worker.runAll()
            }
        }
    }

    /** Leaves new documents and explicit edits source-first even when their text is Markdown. */
    @Test
    fun keepsInitialEditingBehavior() {
        val renderer = RecordingMarkdownRenderer()
        createSession(
            TestEditorDocument(INITIAL_MARKDOWN),
            renderer,
            shouldFocusInitialEditor = true
        ).use { session ->
            session.openInitialEditor()

            assertEquals(EditorPresentation.Text, session.presentation)
            assertTrue(requireNotNull(session.activeDraft).shouldRestoreEditorFocus)
            assertTrue(renderer.renderedTexts.isEmpty())
        }
    }

    /** Preserves the chosen mode and draft when a retained session is attached again. */
    @Test
    fun appliesInitialPreviewOnlyOnce() {
        val document = TestEditorDocument(INITIAL_MARKDOWN)
        val renderer = RecordingMarkdownRenderer()
        createSession(
            document,
            renderer,
            initialPresentation = EditorPresentation.MarkdownPreview
        ).use { session ->
            session.openInitialEditor()
            val preview = session.markdownPreviewStatus
            session.openInitialEditor()

            assertSame(preview, session.markdownPreviewStatus)
            assertEquals(1, document.viewportCalls.size)
            assertEquals(1, renderer.renderedTexts.size)

            session.showTextEditor()
            val draft = requireNotNull(session.activeDraft)
            draft.textFieldState.edit { selection = TextRange(4) }
            session.openInitialEditor()

            assertEquals(EditorPresentation.Text, session.presentation)
            assertSame(draft, session.activeDraft)
            assertEquals(TextRange(4), draft.textFieldState.selection)
            assertEquals(1, renderer.renderedTexts.size)
        }
    }

    /** Keeps source accessible after the first render fails without losing received text. */
    @Test
    fun opensSourceAfterInitialPreviewFailure() {
        val document = TestEditorDocument(INITIAL_MARKDOWN)
        val renderer = MarkdownRenderer { _, _ ->
            throw MarkdownRenderException(MarkdownRenderFailure.TooComplex)
        }
        createSession(
            document,
            renderer,
            initialPresentation = EditorPresentation.MarkdownPreview
        ).use { session ->
            session.openInitialEditor()

            assertEquals(EditorPresentation.MarkdownPreview, session.presentation)
            assertTrue(session.markdownPreviewStatus is MarkdownPreviewStatus.Failed)
            assertNull(session.activeDraft)
            assertTrue(document.capturedSnapshots.all(TestEditorDocumentSnapshot::isClosed))

            session.showTextEditor()

            assertEquals(EditorPresentation.Text, session.presentation)
            assertEquals(
                INITIAL_MARKDOWN,
                requireNotNull(session.activeDraft).textFieldState.text.toString()
            )
        }
    }

    /** Prevents a delayed initial render from taking over an explicitly selected source view. */
    @Test
    fun cancelsInitialPreviewWhenSourceIsSelected() {
        val renderGate = CompletableDeferred<Unit>()
        val document = TestEditorDocument(INITIAL_MARKDOWN)
        val recordingRenderer = RecordingMarkdownRenderer()
        val renderer = MarkdownRenderer { snapshot, expectedBytes ->
            renderGate.await()
            recordingRenderer.render(snapshot, expectedBytes)
        }
        createSession(
            document,
            renderer,
            initialPresentation = EditorPresentation.MarkdownPreview
        ).use { session ->
            session.openInitialEditor()
            session.openInitialEditor()
            assertTrue(session.markdownPreviewStatus is MarkdownPreviewStatus.Rendering)
            assertNull(session.activeDraft)
            assertEquals(1, document.capturedSnapshots.size)

            session.showTextEditor()
            renderGate.complete(Unit)

            assertEquals(EditorPresentation.Text, session.presentation)
            assertNotNull(session.activeDraft)
            assertEquals(MarkdownPreviewStatus.Idle, session.markdownPreviewStatus)
            assertTrue(recordingRenderer.renderedTexts.isEmpty())
            assertTrue(document.capturedSnapshots.all(TestEditorDocumentSnapshot::isClosed))
        }
    }

    /** Retains the initial reading request while a failed viewport is retried. */
    @Test
    fun previewsAfterInitialViewportRetry() {
        var allowViewport = false
        val delegate = TestEditorDocument(INITIAL_MARKDOWN)
        val document = object : EditorDocument by delegate {
            override fun viewport(
                cursor: ViewportCursor,
                limits: ViewportLimits
            ): ViewportSnapshot {
                check(allowViewport) { "synthetic viewport failure" }
                return delegate.viewport(cursor, limits)
            }
        }
        createSession(
            document,
            RecordingMarkdownRenderer(),
            initialPresentation = EditorPresentation.MarkdownPreview
        ).use { session ->
            session.openInitialEditor()
            assertTrue(session.state.status is EditorDocumentStatus.Failed)
            assertNull(session.activeDraft)

            allowViewport = true
            session.retryViewport()

            assertEquals(EditorPresentation.MarkdownPreview, session.presentation)
            assertTrue(session.markdownPreviewStatus is MarkdownPreviewStatus.Ready)
            assertNull(session.activeDraft)
            assertTrue(delegate.editWindowCalls.isEmpty())
        }
    }

    /** Keeps incoming reading mode through its first successful source adoption. */
    @Test
    fun preservesInitialPreviewAcrossFirstSave() {
        val source = PreviewSaveSource()
        createSession(
            TestEditorDocument(INITIAL_MARKDOWN),
            RecordingMarkdownRenderer(),
            initialPresentation = EditorPresentation.MarkdownPreview
        ).use { session ->
            session.openInitialEditor()
            val preview = session.markdownPreviewStatus as MarkdownPreviewStatus.Ready
            val request = session.prepareMarkdownSave()

            assertTrue(session.saveSelectedSource(request, source, "saved.md"))
            session.openInitialEditor()

            assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
            assertEquals(EditorPresentation.MarkdownPreview, session.presentation)
            assertFalse(session.returnsToSourceOnBack)
            assertSame(preview, session.markdownPreviewStatus)
            assertNull(session.activeDraft)
            assertEquals(listOf(INITIAL_MARKDOWN), source.savedTexts)
        }
    }

    /** Keeps a scrolled preview and its source anchor through a delayed first save. */
    @Test
    fun preservesPreviewAcrossFirstSave() {
        val saveGate = CompletableDeferred<Unit>()
        val source = PreviewSaveSource { saveGate.await() }
        val renderer = RecordingMarkdownRenderer()
        val session = createSession(TestEditorDocument(INITIAL_MARKDOWN), renderer)
        session.use {
            val preview = session.openScrolledPreview()
            val request = session.prepareMarkdownSave()

            assertTrue(session.saveSelectedSource(request, source, "saved.md"))

            assertEquals(SourceSaveStatus.Saving, session.sourceSaveStatus)
            assertSame(preview, session.markdownPreviewStatus)
            assertEquals(EditorPresentation.MarkdownPreview, session.presentation)
            assertNull(session.activeDraft)

            saveGate.complete(Unit)

            assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
            assertFalse(session.hasUnsavedChanges)
            assertSame(preview, session.markdownPreviewStatus)
            assertEquals(EditorPresentation.MarkdownPreview, session.presentation)
            assertNull(session.activeDraft)
            assertEquals(listOf(INITIAL_MARKDOWN), source.savedTexts)
            assertEquals(listOf(INITIAL_MARKDOWN), renderer.renderedTexts)

            session.showTextEditor()

            val draft = requireNotNull(session.activeDraft)
            assertEquals(
                TextRange(SAVED_PREVIEW_SOURCE_OFFSET.toInt()),
                draft.textFieldState.selection
            )
            assertEquals(
                SemanticViewportAnchor(
                    revision = preview.revision,
                    sourceUtf16Offset = SAVED_PREVIEW_SOURCE_OFFSET,
                    viewportTopOffsetPixels = SAVED_PREVIEW_TOP_OFFSET_PIXELS
                ),
                requireNotNull(draft.scrollRestoration).anchor
            )
        }
    }

    /** Retains preview when a failed first source save succeeds on retry. */
    @Test
    fun preservesPreviewAcrossFirstSaveRetry() {
        var allowSave = false
        val source = PreviewSaveSource { check(allowSave) { "synthetic save failure" } }
        createSession(
            TestEditorDocument(INITIAL_MARKDOWN),
            RecordingMarkdownRenderer()
        ).use { session ->
            val preview = session.openScrolledPreview()
            val request = session.prepareMarkdownSave()
            assertTrue(session.saveSelectedSource(request, source))

            assertTrue(session.sourceSaveStatus is SourceSaveStatus.Failed)
            assertSame(preview, session.markdownPreviewStatus)
            assertEquals(EditorPresentation.MarkdownPreview, session.presentation)
            assertNull(session.activeDraft)

            allowSave = true
            session.retrySourceSave()

            assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
            assertSame(preview, session.markdownPreviewStatus)
            assertEquals(EditorPresentation.MarkdownPreview, session.presentation)
            assertNull(session.activeDraft)
            assertEquals(listOf(INITIAL_MARKDOWN), source.savedTexts)
        }
    }

    /** Leaves preview unchanged when either save selection stage is cancelled. */
    @Test
    fun preservesPreviewWhenSaveSelectionIsCancelled() {
        createSession(
            TestEditorDocument(INITIAL_MARKDOWN),
            RecordingMarkdownRenderer()
        ).use { session ->
            val preview = session.openScrolledPreview()
            assertTrue(session.showSaveFormatSelection())
            session.dismissSaveFormatSelection()

            assertEquals(SaveStatus.Idle, session.saveStatus)
            assertSame(preview, session.markdownPreviewStatus)
            assertEquals(EditorPresentation.MarkdownPreview, session.presentation)

            assertTrue(session.showSaveFormatSelection())
            session.selectSaveFormat(DocumentFormat.Markdown)
            val request = requireNotNull(session.claimSaveDestination())
            assertTrue(session.cancelSaveDestination(request))

            assertEquals(SaveStatus.Idle, session.saveStatus)
            assertSame(preview, session.markdownPreviewStatus)
            assertEquals(EditorPresentation.MarkdownPreview, session.presentation)
            assertNull(session.activeDraft)
            assertTrue(session.shouldSelectNewDocumentSource)
        }
    }

    /** Preserves preview after a failed destination is replaced through a fresh picker. */
    @Test
    fun preservesPreviewAcrossDestinationRetry() {
        val source = PreviewSaveSource()
        createSession(
            TestEditorDocument(INITIAL_MARKDOWN),
            RecordingMarkdownRenderer()
        ).use { session ->
            val preview = session.openScrolledPreview()
            val failedRequest = session.prepareMarkdownSave()
            assertTrue(
                session.failSaveDestination(
                    failedRequest,
                    UiText.Literal("The destination is unavailable")
                )
            )
            assertTrue(session.restartExplicitSave())
            session.selectSaveFormat(DocumentFormat.Markdown)
            val request = requireNotNull(session.claimSaveDestination())
            assertTrue(session.beginSaveDestinationPreparation(request))
            assertTrue(session.saveSelectedSource(request, source))

            assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
            assertSame(preview, session.markdownPreviewStatus)
            assertEquals(EditorPresentation.MarkdownPreview, session.presentation)
            assertNull(session.activeDraft)
        }
    }

    /** Enters a focused editor only after an explicit editable-file save completes. */
    @Test
    fun entersEditingAfterSavingAReadOnlyPreviewAsEditable() {
        val saveGate = CompletableDeferred<Unit>()
        val source = PreviewSaveSource { saveGate.await() }
        val session = createSession(
            TestEditorDocument(INITIAL_MARKDOWN),
            RecordingMarkdownRenderer(),
            documentSource = PreviewReadOnlySource()
        )
        session.use {
            val preview = session.openScrolledPreview()
            val request = session.prepareMarkdownSave()
            assertTrue(session.saveSelectedSource(request, source))

            assertTrue(session.isViewOnly)
            assertSame(preview, session.markdownPreviewStatus)
            assertEquals(EditorPresentation.MarkdownPreview, session.presentation)
            assertNull(session.activeDraft)

            saveGate.complete(Unit)

            assertEquals(SourceSaveStatus.Saved, session.sourceSaveStatus)
            assertFalse(session.isViewOnly)
            assertEquals(EditorPresentation.Text, session.presentation)
            assertTrue(requireNotNull(session.activeDraft).shouldRestoreEditorFocus)
            assertEquals(
                Utf16Range(SAVED_PREVIEW_SOURCE_OFFSET, SAVED_PREVIEW_SOURCE_OFFSET),
                session.activeDraft?.edit?.snapshot?.selection
            )
            assertEquals(listOf(INITIAL_MARKDOWN), source.savedTexts)
        }
    }

    /** Keeps a read-only preview locked when saving a copy instead of making it editable. */
    @Test
    fun preservesReadOnlyPreviewWhenSavingACopy() {
        val destination = PreviewSaveSource()
        val owner = TestDestinationOwner()
        val session = createSession(
            TestEditorDocument(INITIAL_MARKDOWN),
            RecordingMarkdownRenderer(),
            documentSource = PreviewReadOnlySource()
        )
        session.use {
            val preview = session.openScrolledPreview()
            assertTrue(session.showSaveCopyFormatSelection())
            session.selectSaveFormat(DocumentFormat.Markdown)
            val request = requireNotNull(session.claimSaveDestination())
            assertTrue(session.beginSaveDestinationPreparation(request))
            assertTrue(session.saveSelectedDestination(request, owner, destination::saveRevision))

            assertTrue(session.saveStatus is SaveStatus.Succeeded)
            assertTrue(session.isViewOnly)
            assertSame(preview, session.markdownPreviewStatus)
            assertEquals(EditorPresentation.MarkdownPreview, session.presentation)
            assertNull(session.activeDraft)
            assertEquals(listOf(INITIAL_MARKDOWN), destination.savedTexts)
            assertEquals(1, owner.closeCallCount)
        }
    }

    /** Renders a clean revision and returns to a focused complete editor. */
    @Test
    fun entersAndLeavesPreviewWithoutChangingTheDocument() {
        val document = TestEditorDocument(INITIAL_MARKDOWN)
        val renderer = RecordingMarkdownRenderer()
        val session = createSession(document, renderer)
        session.openInitialEditor()

        assertTrue(session.canShowMarkdownPreview)
        assertTrue(session.showMarkdownPreview())

        assertEquals(EditorPresentation.MarkdownPreview, session.presentation)
        val preview = session.markdownPreviewStatus as MarkdownPreviewStatus.Ready
        assertEquals(INITIAL_MARKDOWN, preview.document.blocks.single().text)
        assertEquals(0L, requireNotNull(preview.scrollRestoration).sourceUtf16Offset)
        assertEquals(1, renderer.renderedTexts.size)
        assertTrue(document.capturedSnapshots.single().isClosed)
        assertTrue(session.canShowTextEditor)

        session.showTextEditor()

        assertEquals(EditorPresentation.Text, session.presentation)
        assertEquals(MarkdownPreviewStatus.Idle, session.markdownPreviewStatus)
        assertNotNull(session.activeDraft)
        assertEquals(INITIAL_MARKDOWN, document.text)
        session.close()
    }

    /** Renders a fresh bounded model after the previous preview is released. */
    @Test
    fun rerendersPreviewAfterReturningToTheEditor() {
        val document = TestEditorDocument(INITIAL_MARKDOWN)
        val renderer = RecordingMarkdownRenderer()
        val session = createSession(document, renderer)
        session.openInitialEditor()

        assertTrue(session.showMarkdownPreview())
        session.showTextEditor()
        assertTrue(session.showMarkdownPreview())

        assertEquals(2, renderer.renderedTexts.size)
        assertTrue(session.markdownPreviewStatus is MarkdownPreviewStatus.Ready)
        session.close()
    }

    /** Restores the exact editor caret after leaving one revision-bound preview. */
    @Test
    fun restoresTheEditorCaretAfterPreview() {
        val document = TestEditorDocument(INITIAL_MARKDOWN)
        val renderer = RecordingMarkdownRenderer()
        val session = createSession(document, renderer)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        val expectedCaret = TextRange(INITIAL_MARKDOWN.indexOf("Heading"))
        draft.textFieldState.edit {
            selection = expectedCaret
        }

        assertTrue(session.showMarkdownPreview())
        assertEquals(EditorPresentation.MarkdownPreview, session.presentation)

        session.showTextEditor()

        assertEquals(
            expectedCaret,
            requireNotNull(session.activeDraft).textFieldState.selection
        )
        session.close()
    }

    /** Returns to the first actually visible preview source line and vertical inset. */
    @Test
    fun restoresTheObservedPreviewViewport() {
        val document = TestEditorDocument(INITIAL_MARKDOWN)
        val renderer = RecordingMarkdownRenderer()
        val session = createSession(document, renderer)
        session.openInitialEditor()
        assertTrue(session.showMarkdownPreview())
        val preview = session.markdownPreviewStatus as MarkdownPreviewStatus.Ready

        session.observeMarkdownPreviewViewportAnchor(
            revision = preview.revision,
            utf16Offset = 4L,
            viewportTopOffsetPixels = -7
        )
        session.showTextEditor()

        val draft = requireNotNull(session.activeDraft)
        assertEquals(TextRange(4), draft.textFieldState.selection)
        assertEquals(
            SemanticViewportAnchor(
                revision = preview.revision,
                sourceUtf16Offset = 4L,
                viewportTopOffsetPixels = -7
            ),
            requireNotNull(draft.scrollRestoration).anchor
        )
        session.close()
    }

    /** Consumes an entry restoration once so recreation keeps the live list position. */
    @Test
    fun consumesPreviewEntryRestorationExactlyOnce() {
        val document = TestEditorDocument(INITIAL_MARKDOWN)
        val renderer = RecordingMarkdownRenderer()
        val session = createSession(document, renderer)
        session.openInitialEditor()
        assertTrue(session.showMarkdownPreview())
        val ready = session.markdownPreviewStatus as MarkdownPreviewStatus.Ready
        val restoration = requireNotNull(ready.scrollRestoration)

        assertTrue(
            session.consumeMarkdownPreviewScrollRestoration(
                revision = ready.revision,
                restoration = restoration
            )
        )
        assertNull(
            (session.markdownPreviewStatus as MarkdownPreviewStatus.Ready).scrollRestoration
        )
        assertFalse(
            session.consumeMarkdownPreviewScrollRestoration(
                revision = ready.revision,
                restoration = restoration
            )
        )
        session.observeMarkdownPreviewViewportAnchor(
            revision = ready.revision,
            utf16Offset = 4L,
            viewportTopOffsetPixels = -3
        )
        session.showTextEditor()
        assertEquals(TextRange(4), requireNotNull(session.activeDraft).textFieldState.selection)
        session.close()
    }

    /** Opens a writable bounded editor at one exact rendered-tap source position. */
    @Test
    fun editsOneExactPreviewSourcePosition() {
        val document = TestEditorDocument(INITIAL_MARKDOWN)
        val renderer = RecordingMarkdownRenderer()
        val session = createSession(document, renderer)
        session.openInitialEditor()
        assertTrue(session.showMarkdownPreview())
        val preview = session.markdownPreviewStatus as MarkdownPreviewStatus.Ready

        assertTrue(
            session.showTextEditorAtSource(
                revision = preview.revision,
                utf16Offset = 6L,
                viewportTopOffsetPixels = 19
            )
        )

        val draft = requireNotNull(session.activeDraft)
        assertEquals(EditorPresentation.Text, session.presentation)
        assertEquals(TextRange(6), draft.textFieldState.selection)
        assertTrue(draft.shouldRestoreEditorFocus)
        assertEquals(19, requireNotNull(draft.scrollRestoration).anchor.viewportTopOffsetPixels)
        session.close()
    }

    /** Reaches the full UTF-16 source end, including trailing non-rendered characters. */
    @Test
    fun editsTheCompleteDocumentEnd() {
        val text = "# Café 😺\n\nTail.\n\n"
        val session = createSession(TestEditorDocument(text), RecordingMarkdownRenderer())
        session.openInitialEditor()
        assertTrue(session.showMarkdownPreview())
        val preview = session.markdownPreviewStatus as MarkdownPreviewStatus.Ready

        assertTrue(
            session.showTextEditorAtSource(
                revision = preview.revision,
                utf16Offset = text.length.toLong()
            )
        )

        val draft = requireNotNull(session.activeDraft)
        assertEquals(TextRange(text.length), draft.textFieldState.selection)
        assertEquals(text, draft.textFieldState.text.toString())
        assertTrue(draft.shouldRestoreEditorFocus)
        assertFalse(session.state.hasDocumentChanges)
        session.close()
    }

    /** Rejects stale and out-of-range preview positions without leaving preview. */
    @Test
    fun rejectsInvalidPreviewSourcePositions() {
        val document = TestEditorDocument(INITIAL_MARKDOWN)
        val renderer = RecordingMarkdownRenderer()
        val session = createSession(document, renderer)
        session.openInitialEditor()
        assertTrue(session.showMarkdownPreview())
        val preview = session.markdownPreviewStatus as MarkdownPreviewStatus.Ready

        assertFalse(
            session.showTextEditorAtSource(
                revision = Math.incrementExact(preview.revision),
                utf16Offset = 0L
            )
        )
        assertFalse(
            session.showTextEditorAtSource(
                revision = preview.revision,
                utf16Offset = INITIAL_MARKDOWN.length.toLong() + 1L
            )
        )
        assertEquals(EditorPresentation.MarkdownPreview, session.presentation)
        assertTrue(session.markdownPreviewStatus is MarkdownPreviewStatus.Ready)
        session.close()
    }

    /** Commits the newest field value before capturing the preview revision. */
    @Test
    fun synchronizesActiveEditBeforePreview() {
        val document = TestEditorDocument(INITIAL_MARKDOWN)
        val renderer = RecordingMarkdownRenderer()
        val session = createSession(document, renderer)
        session.openInitialEditor()
        val draft = requireNotNull(session.activeDraft)
        draft.textFieldState.edit {
            replace(0, length, EDITED_MARKDOWN)
        }
        session.observeActiveEdit(draft, draft.captureFieldValue())

        assertTrue(session.showMarkdownPreview())

        assertEquals(EDITED_MARKDOWN, document.text)
        assertEquals(EditorPresentation.MarkdownPreview, session.presentation)
        val preview = session.markdownPreviewStatus as MarkdownPreviewStatus.Ready
        assertEquals(EDITED_MARKDOWN, preview.document.blocks.single().text)
        assertEquals(listOf(EDITED_MARKDOWN), renderer.renderedTexts)
        assertFalse(session.hasPendingEditWindowAction)
        session.close()
    }

    /** Retains a sanitized failure and replaces it after an explicit retry. */
    @Test
    fun retriesFailedPreview() {
        val document = TestEditorDocument(INITIAL_MARKDOWN)
        var attemptCount = 0
        val recordingRenderer = RecordingMarkdownRenderer()
        val renderer =
            MarkdownRenderer { snapshot, expectedBytes ->
                attemptCount = Math.incrementExact(attemptCount)
                if (attemptCount == 1) {
                    throw MarkdownRenderException(MarkdownRenderFailure.TooComplex)
                }
                recordingRenderer.render(snapshot, expectedBytes)
            }
        val session = createSession(document, renderer)
        session.openInitialEditor()

        assertTrue(session.showMarkdownPreview())
        val failure = session.markdownPreviewStatus as MarkdownPreviewStatus.Failed
        assertEquals(
            UiText.Resource(R.string.operation_markdown_preview_too_complex),
            failure.message
        )
        assertTrue(session.retryMarkdownPreview())

        assertEquals(2, attemptCount)
        assertTrue(session.markdownPreviewStatus is MarkdownPreviewStatus.Ready)
        assertEquals(2, document.capturedSnapshots.size)
        assertTrue(document.capturedSnapshots.all(TestEditorDocumentSnapshot::isClosed))
        session.close()
    }

    /** Creates one synchronously dispatched session with preview enabled. */
    private fun createSession(
        document: EditorDocument,
        renderer: MarkdownRenderer,
        documentSource: EditorDocumentSource? = null,
        initialPresentation: EditorPresentation = EditorPresentation.Text,
        shouldFocusInitialEditor: Boolean = false,
        workerDispatcher: CoroutineDispatcher = ImmediateSessionTestDispatcher
    ): EditorSession = EditorSession(
        title = "Markdown test",
        state = EditorDocumentState(document, workerDispatcher),
        documentSource = documentSource,
        initialPresentation = initialPresentation,
        shouldFocusInitialEditor = shouldFocusInitialEditor,
        operationDispatcher = ImmediateSessionTestDispatcher,
        editSynchronizationDelay = {},
        markdownRenderer = renderer
    )

    /** Publishes a nonzero reading anchor after consuming the initial preview restoration. */
    private fun EditorSession.openScrolledPreview(): MarkdownPreviewStatus.Ready {
        openInitialEditor()
        assertTrue(showMarkdownPreview())
        val ready = markdownPreviewStatus as MarkdownPreviewStatus.Ready
        assertTrue(
            consumeMarkdownPreviewScrollRestoration(
                revision = ready.revision,
                restoration = requireNotNull(ready.scrollRestoration)
            )
        )
        observeMarkdownPreviewViewportAnchor(
            revision = ready.revision,
            utf16Offset = SAVED_PREVIEW_SOURCE_OFFSET,
            viewportTopOffsetPixels = SAVED_PREVIEW_TOP_OFFSET_PIXELS
        )
        return markdownPreviewStatus as MarkdownPreviewStatus.Ready
    }

    /** Claims one first-save destination after format selection and picker preparation. */
    private fun EditorSession.prepareMarkdownSave(): SaveDestinationRequest {
        assertTrue(showSaveFormatSelection())
        selectSaveFormat(DocumentFormat.Markdown)
        val request = requireNotNull(claimSaveDestination())
        assertTrue(beginSaveDestinationPreparation(request))
        return request
    }
}

/** Models exact read-only viewport requests without granting any edit capability. */
private class SourcePositionPreviewDocument(val delegate: TestEditorDocument) :
    EditorDocument by delegate {
    var beforePositionedViewport: () -> Unit = {}
    var positionedViewportCalls = 0

    override fun viewport(cursor: ViewportCursor, limits: ViewportLimits): ViewportSnapshot {
        val origin = cursor.copy(line = 0L, utf16Offset = 0L)
        val complete = delegate.viewport(origin, limits)
        if (cursor == origin) return complete
        positionedViewportCalls += 1
        beforePositionedViewport()
        val index = complete.blocks.indexOfFirst { it.logicalLine == cursor.line }
        check(index >= 0)
        val block = complete.blocks[index]
        val offset = Math.toIntExact(cursor.utf16Offset)
        return complete.copy(
            blocks = listOf(
                block.copy(
                    globalUtf16Start = block.globalUtf16Start + offset,
                    text = block.text.substring(offset),
                    continuesAtStart = offset != 0
                )
            ) + complete.blocks.drop(index + 1),
            previous = cursor
        )
    }
}

/** Provides a read-only test source without exposing a share URI or external resources. */
private class PreviewReadOnlySource : EditorDocumentSource {
    override fun matchesSourceUri(encodedUri: String): Boolean = false

    override fun encodedShareUri(): String? = null

    override fun close() = Unit
}

/** Records successful preview saves after an optional deterministic delay or failure. */
private class PreviewSaveSource(private val beforeSave: suspend () -> Unit = {}) :
    WritableEditorDocumentSource {
    val savedTexts = mutableListOf<String>()

    override fun matchesSourceUri(encodedUri: String): Boolean = false

    override fun encodedShareUri(): String? = null

    override fun close() = Unit

    /** Consumes the exact fixture snapshot only after the simulated provider accepts it. */
    override suspend fun saveRevision(snapshot: EditorDocumentSnapshot, expectedBytes: Long) {
        val testSnapshot = snapshot as TestEditorDocumentSnapshot
        require(testSnapshot.byteLength == expectedBytes) {
            "test save byte length is inconsistent"
        }
        beforeSave()
        testSnapshot.consume()
        savedTexts += testSnapshot.text
    }
}

/** Renders deterministic fixture snapshots into one simple heading model. */
private class RecordingMarkdownRenderer : MarkdownRenderer {
    val renderedTexts = mutableListOf<String>()

    /** Consumes one fixture stream and records its exact immutable text. */
    override suspend fun render(
        snapshot: dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot,
        expectedBytes: Long
    ): MarkdownPreviewDocument {
        val testSnapshot = snapshot as TestEditorDocumentSnapshot
        check(testSnapshot.byteLength == expectedBytes) {
            "test Markdown byte length is inconsistent"
        }
        testSnapshot.consume()
        renderedTexts += testSnapshot.text
        return MarkdownPreviewDocument(
            inputByteLength = expectedBytes,
            blocks =
                listOf(
                    MarkdownRenderBlock(
                        kind = MarkdownBlockKind.Heading,
                        continuesPrevious = false,
                        isOrderedListItem = false,
                        isTaskChecked = false,
                        isTaskUnchecked = false,
                        isTableHeader = false,
                        containsRawHtml = false,
                        headingLevel = 1,
                        quoteDepth = 0,
                        listDepth = 0,
                        listNumber = 0L,
                        text = testSnapshot.text,
                        metadata = "",
                        spans = emptyList(),
                        source =
                            MarkdownSourceRange(
                                start = 0L,
                                end = testSnapshot.text.length.toLong()
                            ),
                        sourceMaps =
                            listOf(
                                MarkdownSourceMap(
                                    renderedStart = 0,
                                    renderedEnd = testSnapshot.text.length,
                                    source =
                                        MarkdownSourceRange(
                                            start = 0L,
                                            end = testSnapshot.text.length.toLong()
                                        )
                                )
                            )
                    )
                ),
            spanCount = 0,
            containsRawHtml = false,
            inputUtf16Length = testSnapshot.text.length.toLong()
        )
    }
}
