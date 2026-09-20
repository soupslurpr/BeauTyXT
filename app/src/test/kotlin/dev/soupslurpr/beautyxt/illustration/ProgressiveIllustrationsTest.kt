package dev.soupslurpr.beautyxt.illustration

import dev.soupslurpr.beautyxt.markdown.MARKDOWN_SPAN_STYLE_MATH
import dev.soupslurpr.beautyxt.markdown.MarkdownBlockKind
import dev.soupslurpr.beautyxt.markdown.MarkdownInlineSpan
import dev.soupslurpr.beautyxt.markdown.MarkdownRenderBlock
import dev.soupslurpr.beautyxt.markdown.MarkdownSourceMap
import dev.soupslurpr.beautyxt.markdown.MarkdownSourceRange
import dev.soupslurpr.beautyxt.markdown.sourceUtf16OffsetForRendered
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveIllustrationsTest {
    @Test
    fun cacheEvictsOffscreenContentButNeverHigherPriorityPinnedDrawings() {
        val cache = IllustrationCache(byteLimit = 100, entryLimit = 2)
        val a = request("a")
        val b = request("b")
        val c = request("c")
        assertTrue(cache.put(a, rendered(48), emptySet()))
        assertTrue(cache.put(b, rendered(48), setOf(a)))
        assertFalse(cache.put(c, rendered(48), setOf(a, b)))
        assertEquals(96, cache.retainedBytes)
        assertTrue(cache.put(c, rendered(48), setOf(a)))
        assertNotNull(cache[a])
        assertNull(cache[b])
        assertNotNull(cache[c])
        cache.clear()
        assertEquals(0, cache.retainedBytes)
        assertTrue(cache.snapshot().isEmpty())
    }

    @Test
    fun manyLaterViewportsRenderWithoutLifetimeAttemptExhaustion() = runBlocking {
        var calls = 0
        var released = false
        val cache = IllustrationCache(byteLimit = 100, entryLimit = 2)
        val scheduler =
            ProgressiveIllustrations(this, {
                calls++
                rendered()
            }, { released = true }, cache = cache)
        try {
            withTimeout(5_000) {
                repeat(300) { index ->
                    val request = request("x_$index")
                    scheduler.updateViewport(listOf(request))
                    scheduler.results.first { it[request] is IllustrationResult.Rendered }
                    assertTrue(cache.retainedBytes <= 100)
                    assertTrue(cache.snapshot().size <= 2)
                }
            }
            assertEquals(300, calls)
        } finally {
            scheduler.close()
        }
        assertTrue(released)
        assertTrue(scheduler.results.value.isEmpty())
    }

    @Test
    fun newestViewportWinsAfterOneHealthyInFlightCallWithoutCancellingIt() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val a = request("a")
        val skipped = request("offscreen")
        val latest = request("latest")
        val scheduler = ProgressiveIllustrations(this, {
            calls += it.source
            if (it == a) {
                started.complete(Unit)
                finish.await()
            }
            rendered()
        }, {})
        try {
            scheduler.updateViewport(listOf(a, skipped))
            withTimeout(1_000) { started.await() }
            scheduler.updateViewport(listOf(latest))
            finish.complete(Unit)
            withTimeout(1_000) {
                scheduler.results.first { it[latest] is IllustrationResult.Rendered }
            }
            assertEquals(listOf("a", "latest"), calls)
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun viewportBudgetDoesNotPermanentlyPoisonIndividuallyValidContent(): Unit = runBlocking {
        var tick = 0L
        val a = request("a")
        val b = request("b")
        val scheduler = ProgressiveIllustrations(this, {
            tick += 13_000_000_000L
            rendered()
        }, {}, nanoTime = { tick })
        try {
            scheduler.updateViewport(listOf(a, b))
            withTimeout(1_000) {
                scheduler.results.first {
                    it[b] ==
                        IllustrationResult.Fallback(IllustrationFailure.Budget)
                }
            }
            scheduler.updateViewport(listOf(b))
            withTimeout(1_000) { scheduler.results.first { it[b] is IllustrationResult.Rendered } }
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun revisionOwnedCacheSurvivesClosingAndReopeningThePreview(): Unit = runBlocking {
        var calls = 0
        val cache = IllustrationCache()
        val request = request("x")
        fun scheduler() = ProgressiveIllustrations(this, {
            calls++
            rendered()
        }, {}, cache = cache, clearCacheOnClose = false)
        scheduler().use {
            it.updateViewport(listOf(request))
            withTimeout(1_000) {
                it.results.first { results -> results[request] is IllustrationResult.Rendered }
            }
        }
        scheduler().use {
            assertTrue(it.results.value[request] is IllustrationResult.Rendered)
            it.updateViewport(listOf(request))
            assertEquals(1, calls)
        }
        assertEquals(1, cache.snapshot().size)
    }

    @Test
    fun aTemporaryUnavailableWorkerCanRecoverAfterViewportChange(): Unit = runBlocking {
        var available = false
        val request = request("x")
        val scheduler = ProgressiveIllustrations(this, {
            if (available) {
                rendered()
            } else {
                IllustrationResult.Fallback(
                    IllustrationFailure.Unavailable
                )
            }
        }, {})
        try {
            scheduler.updateViewport(listOf(request))
            withTimeout(1_000) {
                scheduler.results.first {
                    it[request] == IllustrationResult.Fallback(IllustrationFailure.Unavailable)
                }
            }
            scheduler.updateViewport(emptyList())
            withTimeout(1_000) { scheduler.results.first { it.isEmpty() } }
            available = true
            scheduler.updateViewport(listOf(request))
            withTimeout(1_000) {
                scheduler.results.first { it[request] is IllustrationResult.Rendered }
            }
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun aQuickBackgroundRoundTripRetriesAReleasedInFlightWorker(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val request = request("x")
        var calls = 0
        val scheduler = ProgressiveIllustrations(this, {
            calls++
            if (calls == 1) {
                entered.complete(Unit)
                finish.await()
                IllustrationResult.Fallback(IllustrationFailure.Unavailable)
            } else {
                rendered()
            }
        }, {})
        try {
            scheduler.updateViewport(listOf(request))
            withTimeout(1_000) { entered.await() }
            // The worker release can finish after the app is already visible again. A latest-
            // value queue must not coalesce this lifecycle boundary into the original viewport.
            scheduler.updateViewport(emptyList())
            scheduler.updateViewport(listOf(request))
            finish.complete(Unit)
            withTimeout(1_000) {
                scheduler.results.first { it[request] is IllustrationResult.Rendered }
            }
            assertEquals(2, calls)
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun closingDropsPendingWorkAndCannotPublishLateResults() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        var released = 0
        val scheduler = ProgressiveIllustrations(this, {
            entered.complete(Unit)
            hold.await()
            rendered()
        }, { released++ })
        scheduler.updateViewport(listOf(request("x")))
        withTimeout(1_000) { entered.await() }
        scheduler.close()
        scheduler.close()
        hold.complete(Unit)
        assertEquals(1, released)
        assertTrue(scheduler.results.value.isEmpty())
    }

    @Test
    fun completeFenceRetainsStableBlockSlotsAndExactSourceNavigation() {
        val first = block(
            "flowchart LR\n%% " + "a".repeat(4_000),
            11
        ).copy(kind = MarkdownBlockKind.Code, metadata = "mermaid", spans = emptyList())
        val second = block(
            "\nA-->B\n",
            first.source.end.toInt()
        ).copy(
            kind = MarkdownBlockKind.Code,
            continuesPrevious = true,
            metadata = "mermaid",
            spans = emptyList()
        )
        val last = block("tail", second.source.end.toInt())
        val blocks = listOf(first, second, last)
        val plan = MarkdownIllustrationPlan(blocks)
        val request = plan.requests(listOf(1)).single()
        assertEquals(first.text + second.text, request.source)
        val joined = plan.decorate(0) { rendered().forKind(it.kind) }
        assertEquals(first.text + second.text, joined.text)
        assertEquals(second.source.start, joined.sourceUtf16OffsetForRendered(first.text.length))
        assertTrue(plan.decorate(1) { rendered().forKind(it.kind) }.illustrationContinuation)
        assertEquals(last.text, plan.decorate(2) { rendered() }.text)
        val pending = plan.decorate(0) { IllustrationResult.Pending(it.kind) }
        assertEquals(first.text, pending.text)
        assertEquals(second, plan.decorate(1) { IllustrationResult.Pending(it.kind) })
        val fallback = plan.decorate(0) {
            IllustrationResult.Fallback(IllustrationFailure.Unsupported, it.kind)
        }
        assertEquals(first.text, fallback.text)
        assertEquals(first.sourceMaps, fallback.sourceMaps)
    }

    @Test
    fun planDoesNotEnqueueOversizedMultilineOrAlreadyRenderedContent() {
        val source =
            listOf(
                block("é".repeat(3_000)),
                block("x\ny"),
                block("z").let {
                    it.copy(spans = listOf(it.spans.single().copy(illustration = rendered())))
                }
            )
        val plan = MarkdownIllustrationPlan(source)
        assertTrue(plan.requests(source.indices.asIterable()).isEmpty())
        assertEquals(
            IllustrationResult.Fallback(IllustrationFailure.TooLarge),
            plan.decorate(0) {
                error("worker")
            }.spans.single().illustration
        )
        assertEquals(
            IllustrationResult.Fallback(IllustrationFailure.Unsupported),
            plan.decorate(1) {
                error("worker")
            }.spans.single().illustration
        )
        assertSame(source[2], plan.decorate(2) { error("worker") })
    }

    private fun request(source: String) = IllustrationRequest(source, false, IllustrationKind.Math)
    private fun rendered(bytes: Int = 48) =
        IllustrationResult.Rendered(NativeIllustration(2f, 1f, 0.8f, emptyList(), bytes))
    private fun block(source: String, offset: Int = 0) = MarkdownRenderBlock(
        MarkdownBlockKind.Paragraph, false, false, false, false, false, false,
        0, 0, 0, 0, source, "",
        listOf(MarkdownInlineSpan(0, source.length, MARKDOWN_SPAN_STYLE_MATH, null)),
        source = MarkdownSourceRange(offset.toLong(), offset.toLong() + source.length),
        sourceMaps = listOf(
            MarkdownSourceMap(
                0,
                source.length,
                MarkdownSourceRange(
                    offset.toLong(),
                    offset.toLong() + source.length
                )
            )
        )
    )
}
