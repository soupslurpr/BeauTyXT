/* Verifies bounded paragraph context independently of Android's text renderer. */
package dev.soupslurpr.beautyxt.printing

import dev.soupslurpr.beautyxt.document.DocumentLineEnding
import dev.soupslurpr.beautyxt.document.DocumentMetrics
import dev.soupslurpr.beautyxt.document.EditorDocumentSnapshot
import dev.soupslurpr.beautyxt.document.RenderBlock
import dev.soupslurpr.beautyxt.document.ViewportCursor
import dev.soupslurpr.beautyxt.document.ViewportLimits
import dev.soupslurpr.beautyxt.document.ViewportSnapshot
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PARAGRAPH_TEST_FRAGMENT_UNITS = 256

/** Verifies bidi detection, immutable assembly, cancellation and explicit layout bounds. */
class SourcePrintParagraphTest {
    /** Recognizes RTL scripts, supplementary RTL scalars, numbers and every explicit bidi control. */
    @Test
    fun recognizesParagraphContext() {
        for (text in listOf(
            "Arabic العربية", "Hebrew עברית", "\u0661", "\uD83A\uDD00", "\u061C", "\u200F",
            "\u202A", "\u202B", "\u202C", "\u202D", "\u202E",
            "\u2066", "\u2067", "\u2068", "\u2069"
        )) {
            assertTrue(text, requiresParagraphBidi(text))
        }
        for (text in listOf("", "123 . (word)", "e\u0301", "中文", "😀", "\u200E")) {
            assertFalse(text, requiresParagraphBidi(text))
        }
    }

    /** Assembles the complete paragraph even when its first bidi character appears very late. */
    @Test
    fun preservesLateDirectionalContext() = runBlocking {
        val text = "123 ".repeat(300) + "العربية" + "\u202Eend\u202C"
        assertEquals(text, ParagraphSnapshot(text).complete())
    }

    /** Keeps enormous ordinary LTR lines on the bounded streaming path. */
    @Test
    fun doesNotAssembleLargeLtrParagraphs() = runBlocking {
        assertNull(ParagraphSnapshot("Plain text ".repeat(20_000)).complete())
    }

    /** Accepts exactly the bidi layout budget and rejects either an early or late overflow trigger. */
    @Test
    fun enforcesTheParagraphBudget() {
        val prefix = "a".repeat(MAXIMUM_BIDI_PRINT_PARAGRAPH_UNITS - 1)
        runBlocking { assertEquals(prefix + "א", ParagraphSnapshot(prefix + "א").complete()) }
        for (text in listOf("א" + prefix + "x", prefix + "xא")) {
            assertThrows(PrintParagraphLimitException::class.java) {
                runBlocking { ParagraphSnapshot(text).complete() }
            }
        }
    }

    /** Checks cancellation during the scan and again during bounded paragraph assembly. */
    @Test
    fun observesCancellationInBothPasses() {
        for (cancelAfter in listOf(2, 7)) {
            val snapshot = ParagraphSnapshot("א" + "a".repeat(1_000))
            var checks = 0
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    snapshot.complete {
                        checks += 1
                        if (checks == cancelAfter) throw CancellationException("test cancellation")
                    }
                }
            }
            assertEquals(cancelAfter, checks)
        }
    }

    /** Rejects a changed immutable revision instead of combining unrelated fragments. */
    @Test
    fun rejectsChangedSnapshotMetrics() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { ParagraphSnapshot("א".repeat(1_000), changesRevision = true).complete() }
        }
    }
}

/** Exposes deterministic single-line fragments without an Android or native dependency. */
private class ParagraphSnapshot(
    private val text: String,
    private val changesRevision: Boolean = false
) : EditorDocumentSnapshot {
    private val byteLength = text.toByteArray(Charsets.UTF_8).size.toLong()
    private val metrics = DocumentMetrics(
        revision = 0,
        byteLength = byteLength,
        serializedByteLength = byteLength,
        characterLength = text.codePointCount(0, text.length).toLong(),
        utf16Length = text.length.toLong(),
        lineCount = 1,
        wordCount = 0,
        hasUtf8Bom = false,
        hasLfLineEndings = false,
        hasCrlfLineEndings = false,
        hasCrLineEndings = false,
        insertedLineEnding = DocumentLineEnding.Lf,
        isEditable = false
    )
    private val limits = ViewportLimits(
        maxBlocks = 1,
        maxBlockUtf16Units = PARAGRAPH_TEST_FRAGMENT_UNITS,
        maxTotalUtf16Units = PARAGRAPH_TEST_FRAGMENT_UNITS
    )

    /** Runs the production paragraph preflight with the first bounded block. */
    suspend fun complete(ensureActive: suspend () -> Unit = {}): String? =
        completeBidirectionalPrintParagraph(
            this,
            metrics,
            viewport(ViewportCursor(0, 0, 0), limits).blocks.single(),
            limits,
            ensureActive
        )

    /** Returns the requested fixed-size slice and its exact global offsets. */
    override fun viewport(cursor: ViewportCursor, limits: ViewportLimits): ViewportSnapshot {
        val start = cursor.utf16Offset.toInt()
        val end = minOf(text.length, start + limits.maxBlockUtf16Units)
        return ViewportSnapshot(
            metrics = if (changesRevision && start > 0) metrics.copy(revision = 1) else metrics,
            blocks = listOf(
                RenderBlock(
                    0,
                    start.toLong(),
                    end.toLong(),
                    0,
                    text.substring(start, end),
                    start > 0,
                    end < text.length
                )
            ),
            previous = null,
            next = if (end < text.length) ViewportCursor(0, 0, end.toLong()) else null
        )
    }

    /** Rejects writes because this fixture only exposes immutable viewports. */
    override fun writeSnapshot(
        outputRawFileDescriptor: Int,
        cancellationRawFileDescriptor: Int,
        timeoutMillis: Long
    ): Long = error("paragraph fixture does not write")

    /** Releases no resources because this fixture only owns immutable text. */
    override fun close() = Unit
}
