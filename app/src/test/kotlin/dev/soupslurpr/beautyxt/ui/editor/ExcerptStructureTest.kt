package dev.soupslurpr.beautyxt.ui.editor

import dev.soupslurpr.beautyxt.document.*
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.client.MarkdownRenderer
import dev.soupslurpr.beautyxt.testing.ImmediateSessionTestDispatcher
import dev.soupslurpr.beautyxt.testing.TestEditorDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcerptStructureTest {
    private fun block(kind: MarkdownBlockKind, text: String) = MarkdownRenderBlock(
        kind, false, false, false, false, false, false, 0, 0, 0, 0, text, "", emptyList()
    )

    private val blocks = listOf(
        block(MarkdownBlockKind.Paragraph, "before"),
        block(MarkdownBlockKind.Rule, ""),
        block(MarkdownBlockKind.Paragraph, "after")
    )
    private val document = MarkdownPreviewDocument(32, blocks, 0, false)

    @Test fun retainsAnIncludedRuleBetweenSelectedParagraphs() {
        val result = selectedMarkdown(document, DocumentSelection.Reading(
            0, ReadingPoint(0, 0), ReadingPoint(2, 5)
        ))
        assertEquals(blocks.map { it.kind }, result.document.blocks.map { it.kind })
        assertEquals("before\n\n---\n\nafter", result.markdown)
    }

    @Test fun excludesARuleOutsideTheSelectedReadingRange() {
        val result = selectedMarkdown(document, DocumentSelection.Reading(
            0, ReadingPoint(0, 1), ReadingPoint(0, 4)
        ))
        assertEquals("efo", result.markdown)
        assertFalse(result.document.blocks.any { it.kind == MarkdownBlockKind.Rule })
    }

    @Test fun aCollapsedRulePositionDoesNotSelectStructuralContent() {
        val result = selectedMarkdown(document, DocumentSelection.Reading(
            0, ReadingPoint(1, 0), ReadingPoint(1, 0)
        ))
        assertEquals("", result.markdown)
        assertEquals(0, result.document.blocks.size)
    }

    @Test fun anIncludedEmptyCodeFenceRetainsItsKindWithoutAddingContent() {
        val code = block(MarkdownBlockKind.Code, "").copy(metadata = "text")
        val selected = selectedMarkdown(document.copy(blocks = listOf(blocks[0], code, blocks[2])),
            DocumentSelection.Reading(0, ReadingPoint(0, 0), ReadingPoint(2, 5)))
        assertEquals(MarkdownBlockKind.Code, selected.document.blocks[1].kind)
        assertEquals("before\n\n```text\n```\n\nafter", selected.markdown)
    }

    @Test fun anIncludedEmptyHeadingRetainsItsLevel() {
        val heading = block(MarkdownBlockKind.Heading, "").copy(headingLevel = 2)
        val selected = selectedMarkdown(document.copy(blocks = listOf(blocks[0], heading, blocks[2])),
            DocumentSelection.Reading(0, ReadingPoint(0, 0), ReadingPoint(2, 5)))
        assertEquals(MarkdownBlockKind.Heading, selected.document.blocks[1].kind)
        assertEquals(2, selected.document.blocks[1].headingLevel)
        assertEquals("before\n\n## \n\nafter", selected.markdown)
    }

    @Test fun selectingTheWholeReadingDocumentIncludesATrailingDivider() {
        val source = "before\n\n---"
        val model = document.copy(inputByteLength = source.length.toLong(), blocks = blocks.take(2))
        val delegate = TestEditorDocument(source)
        val backend = object : EditorDocument by delegate {
            override fun viewport(cursor: ViewportCursor, limits: ViewportLimits): ViewportSnapshot {
                val metrics = delegate.editWindow(0, Utf16Range(0, 0), EditWindowLimits(16 * 1024)).metrics
                return ViewportSnapshot(metrics, listOf(RenderBlock(0, 0, 6, 1, "before", false, false)),
                    null, ViewportCursor(0, 1, 0))
            }
        }
        val session = EditorSession("Selection.md", EditorDocumentState(backend, ImmediateSessionTestDispatcher),
            initialPresentation = EditorPresentation.MarkdownPreview,
            markdownRenderer = MarkdownRenderer { _, _ -> model },
            operationDispatcher = ImmediateSessionTestDispatcher)
        try {
            session.openInitialEditor()
            assertTrue(session.selectWholeDocument())
            val selection = session.documentSelection as DocumentSelection.Reading
            assertEquals(ReadingPoint(1, 0), selection.end)
            assertEquals("before\n\n---", selectedMarkdown(model, selection).markdown)
        } finally {
            session.close()
        }
    }

}
