package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.soupslurpr.beautyxt.illustration.IllustrationCache
import dev.soupslurpr.beautyxt.illustration.IllustrationFailure
import dev.soupslurpr.beautyxt.illustration.IllustrationKind
import dev.soupslurpr.beautyxt.illustration.IllustrationLimits
import dev.soupslurpr.beautyxt.illustration.IllustrationRequest
import dev.soupslurpr.beautyxt.illustration.IllustrationResult
import dev.soupslurpr.beautyxt.illustration.IsolatedDiagramService
import dev.soupslurpr.beautyxt.illustration.IsolatedMathService
import dev.soupslurpr.beautyxt.illustration.MarkdownIllustrationPlan
import dev.soupslurpr.beautyxt.illustration.ProgressiveIllustrations
import dev.soupslurpr.beautyxt.illustration.RestartingIllustrationWorker
import dev.soupslurpr.beautyxt.markdown.renderedUtf16OffsetForSource
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

internal data class MarkdownPreviewIllustrations(
    val results: Map<IllustrationRequest, IllustrationResult> = emptyMap(),
    val requested: Set<IllustrationRequest> = emptySet()
) {
    fun resolve(request: IllustrationRequest): IllustrationResult = results[request]
        ?: if (requested.isEmpty() || request in requested) {
            IllustrationResult.Pending(request.kind)
        } else {
            IllustrationResult.Fallback(IllustrationFailure.Budget, request.kind)
        }
}

/** Workers belong to this composition; bounded results live in the revision's memory-only cache. */
@Composable
internal fun rememberMarkdownIllustrations(
    plan: MarkdownIllustrationPlan,
    cache: IllustrationCache,
    listState: LazyListState,
    navigation: MarkdownPreviewNavigationState,
    items: List<MarkdownPreviewItem>
): MarkdownPreviewIllustrations {
    if (plan.isEmpty) return MarkdownPreviewIllustrations()
    val application = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val workers = remember(plan, cache, listState, navigation, application) {
        val math =
            RestartingIllustrationWorker(
                application,
                IsolatedMathService::class.java,
                IllustrationLimits.MAX_MATH_SOURCE_BYTES
            )
        val diagrams =
            RestartingIllustrationWorker(
                application,
                IsolatedDiagramService::class.java,
                IllustrationLimits.MAX_DIAGRAM_SOURCE_BYTES
            )
        math to diagrams
    }
    val scheduler = remember(plan, cache, listState, navigation, workers, scope) {
        val (math, diagrams) = workers
        ProgressiveIllustrations(
            scope,
            cache = cache,
            clearCacheOnClose = false,
            render = { request ->
                if (request.kind == IllustrationKind.Math) {
                    math.render(request.source, request.display)
                } else {
                    diagrams.render(request.source, true)
                }
            },
            release = {
                math.close()
                diagrams.close()
            },
            publish = { apply ->
                // Do not resize the item being flung or cancel the gesture to restore an anchor.
                snapshotFlow { listState.isScrollInProgress }.first { !it }
                val first = listState.firstVisibleItemIndex
                val offset = listState.firstVisibleItemScrollOffset
                val anchor =
                    markdownPreviewVisibleAnchor(navigation.revision, navigation, listState, items)
                apply()
                withFrameNanos { }
                withFrameNanos { }
                if (anchor != null && !listState.isScrollInProgress &&
                    first == listState.firstVisibleItemIndex &&
                    offset == listState.firstVisibleItemScrollOffset
                ) {
                    val measurement = navigation.measurementForSource(anchor.sourceUtf16Offset)
                    if (measurement != null) {
                        val rendered = measurement.block.renderedUtf16OffsetForSource(
                            anchor.sourceUtf16Offset
                        )
                        val presentation = measurement.presentationUtf16OffsetForRendered(rendered)
                        val line = measurement.layoutResult.getLineForOffset(presentation)
                        val top = measurement.topInWindowPixels - navigation.rootTopInWindowPixels +
                            measurement.layoutResult.getLineTop(line).roundToInt()
                        val target =
                            listState.layoutInfo.viewportStartOffset.coerceAtLeast(0) +
                                anchor.viewportTopOffsetPixels
                        if (top != target) listState.scrollBy((top - target).toFloat())
                    }
                }
            }
        )
    }
    DisposableEffect(scheduler) { onDispose { scheduler.close() } }
    LaunchedEffect(scheduler, lifecycle, items) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                snapshotFlow {
                    val layout = listState.layoutInfo
                    layout.visibleItemsInfo.filter {
                        it.offset + it.size > layout.viewportStartOffset &&
                            it.offset < layout.viewportEndOffset
                    }.map { it.index }.filter { it in items.indices }.take(24)
                }.distinctUntilChanged().collect { visible ->
                    val indices = visible.toMutableList()
                    visible.lastOrNull()?.let { if (it + 1 < items.size) indices += it + 1 }
                    visible.firstOrNull()?.let { if (it > 0) indices += it - 1 }
                    val blocks = indices.flatMap { index ->
                        val item = items[index]
                        val end = item.firstBlockIndex + item.blocks.size
                        (item.firstBlockIndex until end).toList()
                    }
                    scheduler.updateViewport(plan.requests(blocks))
                }
            } finally {
                scheduler.updateViewport(emptyList())
                workers.first.releaseInstance()
                workers.second.releaseInstance()
            }
        }
    }
    val results by scheduler.results.collectAsStateWithLifecycle()
    val requested by scheduler.requested.collectAsStateWithLifecycle()
    return remember(results, requested) { MarkdownPreviewIllustrations(results, requested.toSet()) }
}
