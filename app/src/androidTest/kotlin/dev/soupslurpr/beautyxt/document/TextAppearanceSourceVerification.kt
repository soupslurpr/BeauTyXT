package dev.soupslurpr.beautyxt.document

import android.app.Instrumentation
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.MutatePriority
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import dev.soupslurpr.beautyxt.BeauTyXTApplication
import dev.soupslurpr.beautyxt.ui.editor.TextAppearanceStoreState
import dev.soupslurpr.beautyxt.ui.editor.readDocumentAppearances
import dev.soupslurpr.beautyxt.ui.editor.AppearanceGroup
import dev.soupslurpr.beautyxt.ui.editor.AppearanceOption
import dev.soupslurpr.beautyxt.ui.editor.DocumentSelection
import dev.soupslurpr.beautyxt.ui.editor.DocumentSelectionLayout
import dev.soupslurpr.beautyxt.ui.editor.EditorInputRejection
import dev.soupslurpr.beautyxt.ui.editor.EditorPresentation
import dev.soupslurpr.beautyxt.ui.editor.SelectionPoint
import dev.soupslurpr.beautyxt.ui.editor.SelectionTextMeasurement
import dev.soupslurpr.beautyxt.ui.editor.SemanticViewportAnchor
import dev.soupslurpr.beautyxt.ui.editor.SourceFindDecoration
import dev.soupslurpr.beautyxt.ui.editor.TextAppearance
import dev.soupslurpr.beautyxt.ui.editor.TextAppearanceStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Checks raw offsets against the actual decorated field and unwrapped lazy text layouts. */
internal fun Instrumentation.verifySourceAppearanceGeometry() {
    val store = (targetContext.applicationContext as BeauTyXTApplication).textAppearance
    awaitReadingCondition("appearance preferences did not load") { store.state.value is TextAppearanceStoreState.Ready }
    val original = (store.state.value as TextAppearanceStoreState.Ready).preferences.source
    var primaryFailure: Throwable? = null
    try {
        verifySourceLayouts(store)
        verifyReadOnlySourceWidthCeiling(store)
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        runFixtureCleanup(primaryFailure, {
            store.update(AppearanceGroup.Source, original)
            val file = targetContext.noBackupFilesDir.resolve("text-appearance.properties")
            awaitReadingCondition("source appearance was not restored") {
                runCatching { file.bufferedReader().use(::readDocumentAppearances).source == original }.getOrDefault(false)
            }
        })
    }
}

private fun Instrumentation.verifyReadOnlySourceWidthCeiling(store: TextAppearanceStore) {
    store.update(AppearanceGroup.Source, TextAppearance().change(AppearanceOption.Size, 72f)
        .change(AppearanceOption.TabWidth, 16f).change(AppearanceOption.Wrap, 0f))
    val source = "\tW".repeat(1024)
    val display = source.replace('\t', ' ')
    val maximumLayoutWidth = 262_142
    withReadingPage(source, viewOnly = true, initialPresentation = EditorPresentation.Text) { activity, _ ->
        val view = activity.window.decorView
        awaitReadingCondition("unwrapped read-only text did not reach Compose's width ceiling") {
            highlightTextLayouts(view).any { it.layoutInput.text.text == display && it.size.width == maximumLayoutWidth }
        }
        fun axis() = horizontalSourceNode(view)?.config?.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
        val maximum = onSourceUi { checkNotNull(axis()).maxValue() }
        check(maximum >= maximumLayoutWidth - view.width && maximum < maximumLayoutWidth)
        fun scrollToEnd() = onSourceUi {
            val scroll = checkNotNull(horizontalSourceNode(view)?.config?.getOrNull(SemanticsActions.ScrollBy)?.action)
            check(scroll(maximumLayoutWidth.toFloat(), 0f))
        }
        scrollToEnd()
        awaitReadingCondition("read-only scroll did not clamp at the measured edge") { axis()?.value?.invoke() == maximum }
        scrollToEnd()
        waitForAccessibilityIdle()
        check(onSourceUi { axis()?.value?.invoke() } == maximum)
    }
}

private fun Instrumentation.verifySourceLayouts(store: TextAppearanceStore) {
    val group = AppearanceGroup.Source
    store.update(group, TextAppearance().change(AppearanceOption.TabWidth, 7f)
        .change(AppearanceOption.WordSpacing, 25f).change(AppearanceOption.Wrap, 0f))
    val raw = "A\tB\t\u0301 C"
    val display = "A B\t\u0301 C"
    for (readOnly in listOf(false, true)) {
        withReadingPage(raw, viewOnly = readOnly, initialPresentation = EditorPresentation.Text) { activity, session ->
            val view = activity.window.decorView
            awaitReadingCondition("source viewport did not accept Find") { session.canShowFind && session.showFind(showKeyboard = false) }
            onSourceUi {
                session.updateFindFieldValue(TextFieldValue("\tB\t\u0301 "))
            }
            awaitReadingCondition("tab Find did not complete") { session.isFindComplete && session.findResults.size == 1 }
            onSourceUi { check(session.selectFindResult(0)) }
            awaitReadingCondition("Find, authored space and tab styling did not coexist on the source layout") {
                highlightTextLayouts(view).any { layout ->
                    val text = layout.layoutInput.text
                    text.text == display &&
                        text.spanStyles.any { it.start == 1 && it.end == 2 && it.item.textGeometricTransform?.scaleX == 7f } &&
                        text.spanStyles.none { it.start == 3 && it.end == 4 && it.item.textGeometricTransform != null } &&
                        text.spanStyles.any { it.start == 5 && it.end == 6 && it.item.letterSpacing == .25.em } &&
                        (1..5).all { offset -> text.spanStyles.any {
                            offset in it.start until it.end && it.item.textDecoration == TextDecoration.Underline
                        } }
                }
            }
            if (readOnly) onSourceUi {
                session.closeFind()
                val layout = highlightTextLayouts(view).first { it.layoutInput.text.text == display }
                val registry = DocumentSelectionLayout(session)
                val bounds = Rect(5f, 0f, 30f, layout.size.height.toFloat())
                fun measured(current: Boolean) = SelectionTextMeasurement(Any(), layout, Offset.Zero,
                    { SelectionPoint.Source(it.toLong()) }, { (it as? SelectionPoint.Source)?.offset?.toInt() },
                    SourceFindDecoration(checkNotNull(session.state.metrics).revision, raw), { current }, { bounds })
                val stale = measured(false)
                val live = measured(true)
                registry.texts[0] = stale
                registry.texts[0] = live
                registry.release(0, stale.owner)
                check(registry.hit(Offset(4f, 1f), exact = true) == null)
                check(registry.hit(Offset(5f, 1f), exact = true) === live)
                check(live.at(Offset(-100f, 1f)) == live.at(Offset(5f, 1f)))
                check(session.selectSource(2, 3))
                check(registry.moveEndpoint(first = false, forward = true))
                check((session.documentSelection as DocumentSelection.Source).focus == 4L)
                check(stale.graphemeText == display)
                registry.release(0, live.owner)
                check(registry.texts.isEmpty())
            } else {
                val rejection = EditorInputRejection.BulkSize
                onSourceUi {
                    session.closeFind()
                    requireNotNull(session.activeDraft).reportInputRejection(rejection)
                }
                val message = targetContext.getString(rejection.messageResource)
                waitForAccessibilityNode("unwrapped field error") { it.isEditable && it.error?.toString() == message }
                awaitReadingCondition("unwrapped supporting text was not independently laid out") {
                    highlightTextLayouts(view).any { it.layoutInput.text.text == message && it.lineCount > 1 }
                }
            }
        }
    }

    // A Find viewport holds 16 blocks. Make it taller than the screen to exercise recycling.
    store.updateOption(group, AppearanceOption.Size, 44f)
    val needle = "right edge"
    val wide = "long source ".repeat(40) + needle
    val text = (listOf(wide) + (1..80).map { "Short line $it" }).joinToString("\n")
    for (readOnly in listOf(false, true)) withReadingPage(text, viewOnly = readOnly,
        initialPresentation = EditorPresentation.Text) { activity, session ->
        val view = activity.window.decorView
        awaitReadingCondition("source viewport did not accept Find") { session.canShowFind && session.showFind(showKeyboard = false) }
        fun scrollNode() = horizontalSourceNode(view)
        fun offset() = scrollNode()?.config?.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.value?.invoke()
        onSourceUi {
            session.updateFindFieldValue(TextFieldValue(needle))
        }
        awaitReadingCondition("offscreen match missing") { session.isFindComplete && session.findResults.size == 1 }
        awaitReadingCondition("source moved before Find navigation") { offset() == 0f }
        onSourceUi { check(session.selectFindResult(0)) }
        awaitReadingCondition("Find did not reveal the match inside the text viewport (readOnly=$readOnly)") {
            session.readOnlySourceScrollRestoration == null && session.activeDraft?.scrollRestoration == null &&
                (offset() ?: 0f) > 200f &&
                visibleMatchBounds(view, needle).any { (match, clip) ->
                    !clip.isEmpty && match.left >= clip.left - 2 && match.right <= clip.right + 2
                }
        }
        if (!readOnly) return@withReadingPage
        val original = onSourceUi { checkNotNull(offset()) }
        val last = session.state.blocks.lastIndex
        check(last > 5)
        runBlocking { withContext(AndroidUiDispatcher.Main) { session.viewportListState.scrollToItem(last) } }
        check(onSourceUi { session.viewportListState.firstVisibleItemIndex > 0 }) { "the widest row is still on screen" }
        check(onSourceUi { offset() } == original) { "recycling the widest row changed the shared offset" }
        onSourceUi {
            val action = checkNotNull(scrollNode()?.config?.getOrNull(SemanticsActions.ScrollBy)?.action)
            check(action(-200f, 0f))
        }
        awaitReadingCondition("horizontal accessibility scroll did not move the shared viewport") {
            (offset() ?: original) < original - 10
        }
        // Tab-free cells may keep exactly the same bounds through this reflow.
        store.updateOption(group, AppearanceOption.TabWidth, 3f)
        awaitReadingCondition("tab-free reflow left a stale restoration") {
            (offset() ?: 0f) == 0f && session.readOnlySourceScrollRestoration == null
        }
        val target = session.state.blocks[1].block
        for (inset in listOf(-3, 8)) {
            onSourceUi {
                val anchor = SemanticViewportAnchor(checkNotNull(session.state.metrics).revision, target.globalUtf16End, inset)
                session.restorePositionAfterAppearanceChange(EditorPresentation.Text, anchor)
            }
            awaitReadingCondition("offscreen line-end restoration did not settle at $inset") {
                session.readOnlySourceScrollRestoration == null && session.viewportListState.layoutInfo.visibleItemsInfo.any {
                    it.key == session.state.blocks[1].key && kotlin.math.abs(it.offset - inset) <= 1
                }
            }
        }
        runBlocking {
            withContext(AndroidUiDispatcher.Main) {
                session.viewportListState.scroll(MutatePriority.UserInput) {
                    val before = session.viewportListState.firstVisibleItemIndex
                    val anchor = SemanticViewportAnchor(checkNotNull(session.state.metrics).revision,
                        session.state.blocks.last().block.globalUtf16Start, 0)
                    session.restorePositionAfterAppearanceChange(EditorPresentation.Text, anchor)
                    check(session.readOnlySourceScrollRestoration != null)
                    withTimeout(3_000L) { snapshotFlow { session.readOnlySourceScrollRestoration }.first { it == null } }
                    check(session.viewportListState.firstVisibleItemIndex == before) { "appearance interrupted a user scroll" }
                }
            }
        }
    }
}

private fun <T> onSourceUi(action: () -> T): T = runBlocking { withContext(AndroidUiDispatcher.Main) { action() } }

private fun horizontalSourceNode(view: View): SemanticsNode? {
    if (view is ViewRootForTest) return view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
        .firstOrNull { (it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.maxValue?.invoke() ?: 0f) > 500f }
    if (view is ViewGroup) for (index in 0 until view.childCount) {
        horizontalSourceNode(view.getChildAt(index))?.let { return it }
    }
    return null
}
