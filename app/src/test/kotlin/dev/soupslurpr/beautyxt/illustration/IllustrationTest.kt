package dev.soupslurpr.beautyxt.illustration

import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_DISPLAY_MATH
import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_MATH
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan
import dev.soupslurpr.beautyxt.markdown.MarkdownPreviewDocument
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.MarkdownSourceMap
import dev.soupslurpr.beautyxt.markdown.MarkdownSourceRange
import dev.soupslurpr.beautyxt.markdown.markdownInlinePresentation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IllustrationTest {
    @Test
    fun decodesCompleteClosedPacket() {
        val drawing = IllustrationPacketDecoder.decode(packet())
        assertEquals(2f, drawing.width, 0f)
        assertEquals(1f, drawing.height, 0f)
        assertEquals(0.8f, drawing.baseline, 0f)
        assertEquals(1, drawing.paths.size)
        assertEquals(10, drawing.paths.single().components.size)
    }

    @Test
    fun boundedAuthoredAlternativesDecodeStrictUtf8AndPreserveOriginalPaths() {
        val plain = packet()
        val title = "Décisions".toByteArray()
        val description = "Read → refine → share".toByteArray()
        val bytes = plain.copyOfRange(0, 48) + title + description +
            plain.copyOfRange(48, plain.size)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(12, bytes.size)
            putInt(40, title.size)
            putInt(44, description.size)
        }
        val drawing = IllustrationPacketDecoder.decode(bytes)
        assertEquals("Décisions\nRead → refine → share", drawing.authoredDescription())
        assertEquals(1, drawing.paths.size)
        val invalid = bytes.copyOf().apply { this[48] = 0xff.toByte() }
        assertThrows(IllustrationProtocolException::class.java) {
            IllustrationPacketDecoder.decode(invalid)
        }
        for ((offset, size) in listOf(40 to 1_025, 44 to 4_097, 40 to -1)) {
            val corrupt = bytes.copyOf()
            ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, size)
            assertThrows(IllustrationProtocolException::class.java) {
                IllustrationPacketDecoder.decode(corrupt)
            }
        }
    }

    @Test
    fun fallbackExplanationsAreSpecificAndRepeatedReasonsAppearOnlyOnce() {
        val spans = IllustrationFailure.entries.flatMap { reason ->
            List(2) {
                block("x").spans.single().copy(illustration = IllustrationResult.Fallback(reason))
            }
        }
        val messages = illustrationFallbackMessages(spans)
        assertEquals(IllustrationFailure.entries.size, messages.size)
        assertEquals(messages.size, messages.toSet().size)
        assertTrue(illustrationFallbackMessages(block("x").spans).isEmpty())
    }

    @Test
    fun validatesClipGrammarAndAggregateAccounting() {
        val plain = packet()
        val components = floatArrayOf(0f, 0f, 0f, 1f, 2f, 0f, 1f, 2f, 1f, 4f)
        val bytes = plain.copyOf(plain.size + 8 + components.size * 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(12, bytes.size)
            putInt(32, 20)
            putInt(36, 1)
            putInt(60, 1)
            position(plain.size)
            putInt(0)
            putInt(components.size)
            components.forEach(::putFloat)
        }
        val clipped = IllustrationPacketDecoder.decode(bytes)
        assertEquals(1, clipped.paths.single().clips.size)
        for (length in bytes.indices) {
            assertThrows(IllustrationProtocolException::class.java) {
                IllustrationPacketDecoder.decode(bytes.copyOf(length))
            }
        }
        for ((offset, value) in listOf(
            36 to 2,
            60 to 9,
            plain.size to 2,
            plain.size + 4 to Int.MAX_VALUE,
            plain.size + 12 to Float.NaN.toRawBits()
        )) {
            val invalid = bytes.copyOf()
            ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            assertThrows(IllustrationProtocolException::class.java) {
                IllustrationPacketDecoder.decode(invalid)
            }
        }
    }

    @Test
    fun rejectsEveryTruncationAndTrailingBytes() {
        val bytes = packet()
        for (length in bytes.indices) {
            assertThrows(IllustrationProtocolException::class.java) {
                IllustrationPacketDecoder.decode(bytes.copyOf(length))
            }
        }
        assertThrows(IllustrationProtocolException::class.java) {
            IllustrationPacketDecoder.decode(bytes + 0)
        }
    }

    @Test
    fun rejectsUntrustedSizesFlagsCoordinatesAndGrammar() {
        val corruptions = listOf(
            8 to 1, 12 to 0, 16 to Int.MAX_VALUE, 20 to Float.NaN.toRawBits(),
            24 to 0, 28 to 2f.toRawBits(), 32 to Int.MAX_VALUE, 36 to 1,
            40 to Int.MAX_VALUE, 44 to -1, 48 to 4, 52 to 3, 56 to Int.MAX_VALUE, 60 to 1,
            64 to 1f.toRawBits(), 68 to Float.POSITIVE_INFINITY.toRawBits(),
            100 to 5f.toRawBits()
        )
        corruptions.forEach { (offset, value) ->
            val bytes = packet()
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            assertThrows("offset $offset", IllustrationProtocolException::class.java) {
                IllustrationPacketDecoder.decode(bytes)
            }
        }
    }

    @Test
    fun alignsFormulaBaselineAndContainsFullDepth() {
        for (baseline in listOf(0f, 0.2f, 0.8f, 1f)) {
            val drawing = NativeIllustration(2f, 1f, baseline, emptyList(), 40)
            val box = formulaPlaceholder(drawing, -0.9f, 0.25f)
            val center = (-0.9f + 0.25f) / 2f
            assertEquals(0f, center - box.height / 2 + box.top + baseline, 0.00001f)
            assertTrue(box.top >= -0.00001f)
            assertTrue(box.top + drawing.height <= box.height + 0.00001f)
        }
    }

    @Test
    fun hugeValidAppearancesCannotOverflowComposePixelConstraints() {
        for (baseline in listOf(0f, 4_096f, 8_192f)) {
            val drawing = NativeIllustration(8_192f, 8_192f, baseline, emptyList(), 40)
            for (diagram in listOf(false, true)) {
                for (fontSize in listOf(18f, 72f, 216f)) {
                    val box =
                        illustrationPreviewGeometry(
                            drawing,
                            fontSize,
                            -fontSize,
                            fontSize / 4,
                            diagram
                        )
                    assertTrue(box.width in 0f..4_096.01f)
                    assertTrue(box.height in 0f..4_096.01f)
                    assertTrue(box.top >= -0.001f)
                    assertTrue(box.top + drawing.height * box.scale <= box.height + 0.001f)
                }
            }
        }
    }

    @Test
    fun sharesExactFormulaWithinPreviewButNotStyle() = runBlocking {
        var calls = 0
        val original = document(listOf(block("x"), block("x"), block("x", display = true)))
        val result = illustrateMarkdown(original, { _, _ ->
            calls++
            rendered()
        })
        assertEquals(2, calls)
        assertSame(result.blocks[0].spans[0].illustration, result.blocks[1].spans[0].illustration)
        assertEquals(original.blocks.map { it.text }, result.blocks.map { it.text })
        assertEquals(original.blocks.map { it.source }, result.blocks.map { it.source })
    }

    @Test
    fun boundsUniqueFormulaAttemptsAndRetainedPackets() = runBlocking {
        var calls = 0
        val result = illustrateMarkdown(
            document(List(200) { block("x_$it") }),
            { _, _ ->
                calls++
                rendered(IllustrationLimits.MAX_PACKET_BYTES)
            }
        )
        assertEquals(16, calls)
        assertEquals(
            16,
            result.blocks.count {
                it.spans.single().illustration is IllustrationResult.Rendered
            }
        )
        assertTrue(result.blocks.last().spans.single().illustration is IllustrationResult.Fallback)
        calls = 0
        illustrateMarkdown(document(List(200) { block("x_$it") }), { _, _ ->
            calls++
            IllustrationResult.Fallback(IllustrationFailure.Unsupported)
        })
        assertEquals(MAX_DOCUMENT_ILLUSTRATIONS, calls)
    }

    @Test
    fun rejectsOversizedUtf8AndFencesBeforeWorker() = runBlocking {
        val blocks = listOf(
            block("é".repeat(3_000)),
            block(
                "x".repeat(4_096)
            ).copy(kind = MarkdownBlockKind.Code, metadata = "math", spans = emptyList()),
            block(
                "y"
            ).copy(kind = MarkdownBlockKind.Code, continuesPrevious = true, spans = emptyList())
        )
        var calls = 0
        val result = illustrateMarkdown(document(blocks), { _, _ ->
            calls++
            rendered()
        })
        assertEquals(0, calls)
        assertEquals(blocks.map { it.text }, result.blocks.map { it.text })
    }

    @Test
    fun joinsWholeDiagramAcrossTransportBoundaryWithoutLosingSourceMaps() = runBlocking {
        val first = "flowchart TD\n%% " + "a".repeat(4_096)
        val second = "\nA-->B\n"
        fun fragment(text: String, offset: Int, continuation: Boolean) = block(text).copy(
            kind = MarkdownBlockKind.Code,
            metadata = "mermaid",
            continuesPrevious = continuation,
            spans = emptyList(),
            source = MarkdownSourceRange(offset.toLong(), (offset + text.length).toLong()),
            sourceMaps = listOf(
                MarkdownSourceMap(
                    0,
                    text.length,
                    MarkdownSourceRange(
                        offset.toLong(),
                        (
                            offset +
                                text.length
                            ).toLong()
                    )
                )
            )
        )
        val original = document(
            listOf(
                fragment(first, 11, false),
                fragment(
                    second,
                    11 + first.length,
                    true
                )
            )
        )
        var calls = 0
        val result =
            illustrateMarkdown(original, { _, _ ->
                error("diagram entered math worker")
            }, renderDiagram = { source, _ ->
                assertEquals(first + second, source)
                calls++
                rendered()
            })
        assertEquals(1, calls)
        val joined = result.blocks.single()
        assertEquals(first + second, joined.text)
        assertEquals(IllustrationKind.Diagram, joined.spans.single().illustration?.kind)
        assertEquals(11L, joined.source.start)
        assertEquals((11 + first.length + second.length).toLong(), joined.source.end)
        assertEquals(first.length, joined.sourceMaps[1].renderedStart)
        assertEquals(original.sourceMapCount, result.sourceMapCount)
        val rejected =
            illustrateMarkdown(original, { _, _ -> error("math worker") }, renderDiagram = { _, _ ->
                IllustrationResult.Fallback(IllustrationFailure.Unsupported)
            })
        assertEquals(original.blocks.map { it.text }, rejected.blocks.map { it.text })
        assertEquals(original.blocks.map { it.sourceMaps }, rejected.blocks.map { it.sourceMaps })
    }

    @Test
    fun totalDeadlineStopsFurtherWorkerCalls() = runBlocking {
        var tick = 0L
        var calls = 0
        val result = illustrateMarkdown(
            document(listOf(block("x"), block("y"))),
            { _, _ ->
                calls++
                rendered()
            },
            nanoTime = { tick.also { tick += 7_000_000_000L } }
        )
        assertEquals(1, calls)
        assertEquals(
            IllustrationResult.Fallback(IllustrationFailure.Budget),
            result.blocks.last().spans.single().illustration
        )
    }

    @Test
    fun cancellationIsNotConvertedToVisibleFallback() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                illustrateMarkdown(document(listOf(block("x"))), { _, _ ->
                    throw CancellationException("test")
                })
            }
        }
    }

    @Test
    fun multilineDisplayFormulaIsAtomicAndKeepsExactSourceMapping() {
        val source = "\n\\begin{matrix} a & b \\\\\n c & d \\end{matrix}\n"
        val block = block(source, display = true)
        val spans = listOf(block.spans.single().copy(illustration = rendered()))
        val presentation = checkNotNull(markdownInlinePresentation(source, spans, emptyMap()))
        assertEquals("\uFFFC", presentation.text)
        assertEquals(0, presentation.sourceUtf16OffsetForPresentation(0, source.length, spans))
        assertEquals(
            source.length,
            presentation.sourceUtf16OffsetForPresentation(1, source.length, spans)
        )
        assertEquals(0, presentation.presentationUtf16OffsetForSource(5, source.length, spans))
    }

    private fun rendered(bytes: Int = 40) = IllustrationResult.Rendered(
        NativeIllustration(2f, 1f, 0.8f, emptyList(), bytes)
    )

    private fun document(blocks: List<MarkdownRenderBlock>) = MarkdownPreviewDocument(
        blocks.sumOf { it.text.length }.toLong(),
        blocks,
        blocks.sumOf { it.spans.size },
        false
    )

    private fun block(source: String, display: Boolean = false) = MarkdownRenderBlock(
        MarkdownBlockKind.Paragraph, false, false, false, false, false, false,
        0, 0, 0, 0, source, "",
        listOf(
            MarkdownInlineSpan(
                0,
                source.length,
                MARKDOWN_SPAN_STYLE_MATH or if (display) MARKDOWN_SPAN_STYLE_DISPLAY_MATH else 0,
                null
            )
        ),
        source = MarkdownSourceRange(0, source.length.toLong())
    )

    private fun packet(): ByteArray {
        val components = floatArrayOf(0f, 0f, 0f, 1f, 2f, 0f, 1f, 1f, 1f, 4f)
        return ByteBuffer.allocate(64 + components.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("BTXTILL3".toByteArray())
            putInt(3)
            putInt(capacity())
            putInt(1)
            putFloat(2f)
            putFloat(1f)
            putFloat(0.8f)
            putInt(components.size)
            putInt(0)
            putInt(0)
            putInt(0)
            putInt(0)
            putInt(0)
            putInt(components.size)
            putInt(0)
            components.forEach { putFloat(it) }
        }.array()
    }
}
