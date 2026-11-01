package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.ui.text.input.TextFieldValue
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.*
import dev.soupslurpr.beautyxt.illustration.*
import dev.soupslurpr.beautyxt.markdown.*
import dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderer
import dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import dev.soupslurpr.beautyxt.ui.UiText
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReadingSelectionProjectionTest {
    /** The production plan joins these two bounded transport slots only after a drawing arrives. */
    private class Fixture : AutoCloseable {
        val diagram = "flowchart TD\n%% ${"a".repeat(4500)}\nA[Read]-->B[Share]\n"
        val source = "Outside before.\n\n```mermaid\n$diagram```\n\nOutside after."
        private val sourceStart = source.indexOf(diagram)
        private fun block(text: String, start: Int, kind: MarkdownBlockKind = MarkdownBlockKind.Paragraph,
            continuation: Boolean = false) = MarkdownRenderBlock(kind, continuation,
            false, false, false, false, false, 0, 0, 0, 0, text,
            if (kind == MarkdownBlockKind.Code) "mermaid" else "", emptyList(),
            source = MarkdownSourceRange(start.toLong(), (start + text.length).toLong()),
            sourceMaps = listOf(MarkdownSourceMap(0, text.length,
                MarkdownSourceRange(start.toLong(), (start + text.length).toLong()))))
        val model = MarkdownPreviewDocument(source.toByteArray().size.toLong(), listOf(
            block("Outside before.", 0),
            block(diagram.take(4096), sourceStart, MarkdownBlockKind.Code),
            block(diagram.drop(4096), sourceStart + 4096, MarkdownBlockKind.Code, true),
            block("Outside after.", source.indexOf("Outside after."))
        ), 0, false)
        val delegate = TestEditorDocument(source)
        var beforeCompile: () -> Unit = {}
        private val backend = object : EditorDocument by delegate {
            override fun viewport(cursor: ViewportCursor, limits: ViewportLimits): ViewportSnapshot {
                check(cursor == ViewportCursor(0, 0, 0))
                val metrics = delegate.editWindow(0, Utf16Range(0, 0), EditWindowLimits(16 * 1024)).metrics
                return ViewportSnapshot(metrics, listOf(RenderBlock(0, 0, 15, 1,
                    "Outside before.", false, false)), null, ViewportCursor(0, 1, 0))
            }
            override fun compileSearch(query: String, options: SearchOptions): DocumentSearch {
                beforeCompile()
                return delegate.compileSearch(query, options)
            }
            override fun captureRange(expectedRevision: Long, range: Utf16Range): EditorDocumentSnapshot =
                TestEditorDocument(delegate.readRange(expectedRevision, range)).use { selected ->
                    val metrics = selected.editWindow(0, Utf16Range(0, 0), EditWindowLimits(16 * 1024)).metrics
                    val snapshot = selected.captureSnapshot(0)
                    object : EditorDocumentSnapshot by snapshot {
                        override fun metrics() = metrics
                    }
                }
        }
        val session = EditorSession("Selection.md", EditorDocumentState(backend, ImmediateSessionTestDispatcher),
            initialPresentation = EditorPresentation.MarkdownPreview,
            markdownRenderer = MarkdownRenderer { _, bytes -> check(bytes == model.inputByteLength); model },
            operationDispatcher = ImmediateSessionTestDispatcher, findDelay = {}, editSynchronizationDelay = {})
            .also { it.openInitialEditor() }
        val ready = session.markdownPreviewStatus as MarkdownPreviewStatus.Ready
        val cache = ready.layout.illustrationCache
        val request = ready.layout.illustrations.requests(listOf(2)).single()
        val rendered = IllustrationResult.Rendered(NativeIllustration(2f, 1f, .8f, emptyList(), 48,
            textRuns = listOf(IllustrationTextRun("Read", emptyList()), IllustrationTextRun("Share", emptyList())),
            isTextComplete = true), IllustrationKind.Diagram)

        fun render(notify: Boolean = true) {
            assertTrue(cache.put(request, rendered, emptySet()))
            if (notify) session.onReadingIllustrationsChanged(ready.revision)
        }

        fun evict(notify: Boolean = true) {
            repeat(MAX_DOCUMENT_ILLUSTRATIONS) { index ->
                assertTrue(cache.put(IllustrationRequest("unsupported_$index", true, IllustrationKind.Diagram),
                    IllustrationResult.Fallback(IllustrationFailure.Unsupported, IllustrationKind.Diagram), emptySet()))
            }
            assertNull(cache.snapshot()[request])
            if (notify) session.onReadingIllustrationsChanged(ready.revision)
        }

        fun selectContinuation(reversed: Boolean = false) {
            val first = ReadingPoint(2, 1)
            val last = ReadingPoint(2, 8)
            assertTrue(if (reversed) session.selectReading(last, first) else session.selectReading(first, last))
        }

        fun selectWholeJoined(reversed: Boolean = false) {
            val first = ReadingPoint(1, 0)
            val last = ReadingPoint(1, diagram.length)
            assertTrue(if (reversed) session.selectReading(last, first) else session.selectReading(first, last))
        }

        fun assertInvalidated() {
            assertNull(session.documentSelection)
            assertEquals(UiText.Resource(R.string.selection_preview_changed), session.selectionMessage)
        }

        override fun close() = session.close()
    }

    @Test fun joiningInvalidatesForwardAndReversedContinuationSelectionsWithoutCopyingEmptyText() = runBlocking {
        for (reversed in listOf(false, true)) Fixture().use { fixture ->
            fixture.selectContinuation(reversed)
            assertEquals("aaaaaaa", fixture.session.selectedPlainText(128 * 1024))
            fixture.render()
            fixture.assertInvalidated()
            var deliveries = 0
            fixture.session.copyDocumentSelection { deliveries++ }
            assertEquals(0, deliveries)
            fixture.assertInvalidated()
            fixture.evict()
            assertNull(fixture.session.documentSelection)
        }
    }

    @Test fun evictionInvalidatesAWholeJoinedSelectionBeforeItsTailCanBeTruncated() = runBlocking {
        for (reversed in listOf(false, true)) Fixture().use { fixture ->
            fixture.render()
            fixture.selectWholeJoined(reversed)
            val text = fixture.session.selectedPlainText(128 * 1024)
            assertTrue(text.contains("A[Read]-->B[Share]"))
            fixture.evict(notify = false)
            var delivered: String? = null
            fixture.session.copyDocumentSelection { delivered = it }
            assertNull(delivered)
            fixture.assertInvalidated()
            fixture.session.onReadingIllustrationsChanged(fixture.ready.revision)
            fixture.assertInvalidated()
        }
    }

    @Test fun wholePendingFenceIsAlsoInvalidatedRatherThanReinterpretedAsAJoinedDrawing() {
        for (reversed in listOf(false, true)) Fixture().use { fixture ->
            val first = ReadingPoint(1, 0)
            val last = ReadingPoint(2, fixture.model.blocks[2].text.length)
            assertTrue(if (reversed) fixture.session.selectReading(last, first) else fixture.session.selectReading(first, last))
            fixture.render()
            fixture.assertInvalidated()
        }
    }

    @Test fun allReadingActionsValidateBeforeUsingALocalSelectionEvenBeforeTheUiCallback() = runBlocking {
        val actions: List<suspend (Fixture) -> Unit> = listOf(
            { fixture ->
                var rejected = false
                try { fixture.session.selectedPlainText(128 * 1024) }
                catch (_: IllegalStateException) { rejected = true }
                assertTrue(rejected)
            },
            { fixture ->
                var deliveries = 0
                fixture.session.copyDocumentSelection { deliveries++ }
                assertEquals(0, deliveries)
            },
            { fixture -> assertNull(captureExcerpt(fixture.session)) },
            { fixture -> fixture.session.showTextEditor() },
            { fixture -> assertFalse(fixture.session.extendSelectionParagraph(true)) },
            { fixture -> assertFalse(fixture.session.captureSelectionFindScope()) },
            { fixture -> fixture.session.findSelectedText(); assertFalse(fixture.session.isFindVisible) }
        )
        for (action in actions) Fixture().use { fixture ->
            fixture.selectContinuation()
            fixture.render(notify = false)
            action(fixture)
            fixture.assertInvalidated()
        }
    }

    @Test fun outsideSelectionsSurviveJoinsEvictionsAndCopyBeforeTheUiCallback() = runBlocking {
        Fixture().use { fixture ->
            assertTrue(fixture.session.selectReading(ReadingPoint(0, 0), ReadingPoint(0, 15)))
            val selected = fixture.session.documentSelection
            fixture.render(notify = false)
            assertEquals("Outside before.", fixture.session.selectedPlainText(100))
            fixture.session.onReadingIllustrationsChanged(fixture.ready.revision)
            fixture.evict(notify = false)
            assertEquals("Outside before.", fixture.session.selectedPlainText(100))
            fixture.session.onReadingIllustrationsChanged(fixture.ready.revision)
            assertEquals(selected, fixture.session.documentSelection)
            assertNull(fixture.session.selectionMessage)
        }
    }

    @Test fun identicalRenderedCacheRefreshPreservesTheWholeIllustrationSelection() = runBlocking {
        Fixture().use { fixture ->
            fixture.render()
            fixture.selectWholeJoined()
            val selected = fixture.session.documentSelection
            val text = fixture.session.selectedPlainText(128 * 1024)
            fixture.render(notify = false)
            assertEquals(text, fixture.session.selectedPlainText(128 * 1024))
            fixture.session.onReadingIllustrationsChanged(fixture.ready.revision)
            assertEquals(selected, fixture.session.documentSelection)
            assertNull(fixture.session.selectionMessage)
        }
    }

    @Test fun capturedReadingScopePausesAfterEvictionEvenWhenTheVisibleSelectionWasCleared() {
        Fixture().use { fixture ->
            fixture.render()
            fixture.selectWholeJoined()
            fixture.session.showFind(false)
            assertTrue(fixture.session.captureSelectionFindScope())
            fixture.session.clearDocumentSelection()
            fixture.session.updateFindFieldValue(TextFieldValue("Share"))
            assertEquals(1, fixture.session.findResults.size)
            fixture.evict(notify = false)
            assertFalse(fixture.session.findNext())
            assertTrue(fixture.session.isFindScopePaused)
            assertTrue(fixture.session.isFindSelectionScope)
            assertTrue(fixture.session.findResults.isEmpty())
            assertNull(fixture.session.capturedReadingFindScope)
            assertNull(fixture.session.capturedFindScope)
            assertNull(fixture.session.documentSelection)
            assertEquals(UiText.Resource(R.string.find_scope_lost), fixture.session.findActionMessage)
            assertFalse(fixture.session.retryFind())
            fixture.session.onReadingIllustrationsChanged(fixture.ready.revision)
            assertTrue(fixture.session.isFindScopePaused)
            fixture.session.useDocumentFindScope()
            assertFalse(fixture.session.isFindScopePaused)
            assertTrue(fixture.session.findResults.isNotEmpty())
        }
    }

    @Test fun changedScopePreservesAVisibleOutsideSelectionWithoutAReselectNotice() = runBlocking {
        Fixture().use { fixture ->
            fixture.render()
            fixture.selectWholeJoined()
            fixture.session.showFind(false)
            assertTrue(fixture.session.captureSelectionFindScope())
            fixture.session.updateFindFieldValue(TextFieldValue("Share"))
            assertTrue(fixture.session.selectReading(ReadingPoint(0, 0), ReadingPoint(0, 15)))
            val selected = fixture.session.documentSelection
            fixture.evict(notify = false)
            assertEquals("Outside before.", fixture.session.selectedPlainText(100))
            assertEquals(selected, fixture.session.documentSelection)
            assertNull(fixture.session.selectionMessage)
            assertTrue(fixture.session.isFindScopePaused)
            assertEquals(UiText.Resource(R.string.find_scope_lost), fixture.session.findActionMessage)
            assertTrue(fixture.session.findResults.isEmpty())
        }
    }

    @Test fun scopeValidationAlsoRunsAfterSearchCompilationBeforeReadingItsProjection() {
        Fixture().use { fixture ->
            fixture.selectContinuation()
            fixture.session.showFind(false)
            assertTrue(fixture.session.captureSelectionFindScope())
            fixture.beforeCompile = { fixture.render(notify = false) }
            fixture.session.updateFindFieldValue(TextFieldValue("a"))
            assertTrue(fixture.session.isFindScopePaused)
            assertTrue(fixture.session.findResults.isEmpty())
            fixture.assertInvalidated()
        }
    }

    @Test fun outsideScopeAndSameProjectionScopeRefreshRemainUsable() {
        Fixture().use { fixture ->
            assertTrue(fixture.session.selectReading(ReadingPoint(0, 0), ReadingPoint(0, 15)))
            fixture.session.showFind(false)
            assertTrue(fixture.session.captureSelectionFindScope())
            fixture.session.clearDocumentSelection()
            fixture.render()
            fixture.session.updateFindFieldValue(TextFieldValue("Outside"))
            assertEquals(1, fixture.session.findResults.size)
            fixture.evict()
            assertFalse(fixture.session.isFindScopePaused)
            assertEquals(1, fixture.session.findResults.size)
        }
        Fixture().use { fixture ->
            fixture.render()
            fixture.selectWholeJoined()
            fixture.session.showFind(false)
            assertTrue(fixture.session.captureSelectionFindScope())
            fixture.session.clearDocumentSelection()
            fixture.session.updateFindFieldValue(TextFieldValue("Share"))
            fixture.render()
            assertFalse(fixture.session.isFindScopePaused)
            assertEquals(1, fixture.session.findResults.size)
        }
    }

    @Test fun aFrozenExcerptKeepsItsOriginalContextAfterCacheEviction() = runBlocking {
        Fixture().use { fixture ->
            fixture.render()
            val start = fixture.source.indexOf(fixture.diagram).toLong()
            assertTrue(fixture.session.selectSource(start, start + fixture.diagram.length))
            val captured = checkNotNull(captureExcerpt(fixture.session))
            captured.use {
                val before = checkNotNull(captured.formatted()).markdown
                assertTrue(before.contains("A[Read]-->B[Share]"))
                fixture.session.clearDocumentSelection()
                fixture.evict()
                assertEquals(before, checkNotNull(captured.formatted()).markdown)
            }
        }
    }

    @Test fun retainedProjectionReferencesAreReleasedOnClearNewSelectionScopeResetAndClose() {
        fun reference(session: EditorSession, name: String): Any? = session.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(session)
        Fixture().use { fixture ->
            fixture.selectContinuation()
            val first = checkNotNull(reference(fixture.session, "selectedReadingProjection"))
            fixture.session.selectReading(ReadingPoint(0, 0), ReadingPoint(0, 15))
            assertNotSame(first, reference(fixture.session, "selectedReadingProjection"))
            fixture.session.selectSource(0, 3)
            assertNull(reference(fixture.session, "selectedReadingProjection"))
            fixture.selectContinuation()
            fixture.session.clearDocumentSelection()
            assertNull(reference(fixture.session, "selectedReadingProjection"))
            fixture.selectContinuation()
            fixture.session.showFind(false)
            assertTrue(fixture.session.captureSelectionFindScope())
            fixture.session.clearDocumentSelection()
            assertNotNull(reference(fixture.session, "findReadingProjection"))
            fixture.session.useDocumentFindScope()
            assertNull(reference(fixture.session, "findReadingProjection"))
            fixture.selectContinuation()
            assertTrue(fixture.session.captureSelectionFindScope())
            fixture.session.closeFind()
            assertNull(reference(fixture.session, "findReadingProjection"))
            fixture.session.close()
            assertNull(reference(fixture.session, "selectedReadingProjection"))
            assertNull(reference(fixture.session, "findReadingProjection"))
            assertNull(reference(fixture.session, "validatedReadingProjectionVersion"))
        }
    }

    @Test fun hiddenSlotTopologyIsCheckedEvenWhenItsTextIsIdentical() {
        Fixture().use { fixture ->
            val selected = DocumentSelection.Reading(0, ReadingPoint(2, 0), ReadingPoint(2, 1))
            val document = fixture.model.copy(blocks = fixture.model.blocks.mapIndexed { index, block ->
                if (index == 2) block.copy(text = "") else block
            })
            val retained = ReadingSelectionProjection(selected, document)
            assertTrue(retained.matches { document.blocks.getOrNull(it) })
            assertFalse(retained.matches { index -> document.blocks.getOrNull(index)?.let {
                if (index == 2) it.copy(illustrationContinuation = true) else it
            } })
        }
    }

    @Test fun aLostLabelScopePausesBeforeCopyWhileItsFrozenLabelTextRemainsUsable() = runBlocking {
        Fixture().use { fixture ->
            fixture.render()
            assertTrue(fixture.session.selectDiagramLabel(1, 0, 1))
            val selected = fixture.session.documentSelection
            fixture.session.showFind(false)
            assertTrue(fixture.session.captureSelectionFindScope())
            fixture.session.updateFindFieldValue(TextFieldValue("Share"))
            assertEquals(1, fixture.session.findResults.size)
            fixture.evict(notify = false)
            assertEquals("Share", fixture.session.selectedPlainText(100))
            assertEquals(selected, fixture.session.documentSelection)
            assertNull(fixture.session.selectionMessage)
            assertTrue(fixture.session.isFindScopePaused)
            assertEquals(UiText.Resource(R.string.find_scope_lost), fixture.session.findActionMessage)
            assertTrue(fixture.session.findResults.isEmpty())
            assertFalse(fixture.session.isFindComplete)
        }
    }

    @Test fun labelScopeValidationSurvivesClearedSelectionAndPreservesIdenticalDrawings() {
        Fixture().use { fixture ->
            fixture.render()
            assertTrue(fixture.session.selectDiagramLabel(1, 0, 1))
            fixture.session.showFind(false)
            assertTrue(fixture.session.captureSelectionFindScope())
            fixture.session.clearDocumentSelection()
            fixture.session.updateFindFieldValue(TextFieldValue("Share"))
            assertEquals(1, fixture.session.findResults.size)
            fixture.render()
            assertFalse(fixture.session.isFindScopePaused)
            assertEquals(1, fixture.session.findResults.size)
            fixture.evict()
            assertTrue(fixture.session.isFindScopePaused)
            assertTrue(fixture.session.findResults.isEmpty())
            assertFalse(fixture.session.isFindComplete)
        }
    }

    /** Exercises the real private capture boundary without adding a production testing hook. */
    private suspend fun captureExcerpt(session: EditorSession): ExcerptCapture? = suspendCoroutineUninterceptedOrReturn { continuation ->
        session.javaClass.getDeclaredMethod("captureSelectionExcerpt", Continuation::class.java)
            .apply { isAccessible = true }.invoke(session, continuation)
    }
}
