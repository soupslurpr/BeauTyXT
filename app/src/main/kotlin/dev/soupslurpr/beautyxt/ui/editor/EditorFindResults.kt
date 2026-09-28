@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.SearchHit
import dev.soupslurpr.beautyxt.ui.asString

/** A separate match list or batch review, with explicit paths back to the document. */
@Composable
internal fun EditorFindResults(
    session: EditorSession,
    wide: Boolean,
    modifier: Modifier = Modifier,
    onReturn: () -> Unit,
    onEditSearch: () -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val resultsFocus = remember(session) { FocusRequester() }
    val review = session.findResultsPage == FindResultsPage.Replacements
    LaunchedEffect(session, session.findFocusRequest) {
        if (session.findInputFocus != FindInputFocus.Results) return@LaunchedEffect
        withFrameNanos { }
        if (session.findInputFocus != FindInputFocus.Results) return@LaunchedEffect
        resultsFocus.requestFocus()
        keyboard?.hide()
    }
    fun focusReview() {
        focus.clearFocus(force = true)
        session.recordFindInputFocus(FindInputFocus.Results)
        resultsFocus.requestFocus()
        keyboard?.hide()
    }
    Column(modifier.fillMaxWidth()
        .then(if (wide) Modifier else Modifier.windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)))
        .focusRequester(resultsFocus)
        .onFocusChanged { if (it.isFocused) session.recordFindInputFocus(FindInputFocus.Results) }
        .onPreviewKeyEvent { event ->
            session.findInputFocus == FindInputFocus.Results &&
                handleEditorHistoryShortcut(event, session::requestUndo, session::requestRedo)
        }.focusable()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FindIcon(R.drawable.ic_arrow_back, stringResource(R.string.find_hide_results), onReturn)
            Column(Modifier.weight(1f).padding(vertical = 16.dp)) {
                Text(stringResource(if (review) R.string.replace_review_title else R.string.find_results),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(findDomainScopeText(session), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onEditSearch) { Text(stringResource(R.string.find_edit_search)) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (review) item(key = "summary") {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.replace_review_query, session.findFieldValue.text,
                            session.replacementFieldValue.text.ifEmpty { stringResource(R.string.replace_delete) }),
                            style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                            Text(stringResource(R.string.replace_review_scope, session.includedReplacementCount,
                                session.excludedFindResults.size,
                                stringResource(if (session.isFindSelectionScope) R.string.find_selection_scope else R.string.find_document_scope)))
                            val unchanged = session.unchangedReplacementCount
                            if (unchanged > 0) Text(pluralStringResource(R.plurals.replace_unchanged, unchanged, unchanged))
                        }
                    }
                }
            }
            item(key = "status") {
                EditorFindFeedback(session, onUndo = { focusReview(); session.requestUndoFindReplacement() })
                if (session.findStatus == FindStatus.NoMatches) Text(stringResource(R.string.find_no_matches))
                if (session.findFieldValue.text.isEmpty()) Text(stringResource(R.string.find_enter_query))
            }
            itemsIndexed(session.findResults, key = { index, _ -> index }) { index, result ->
                val included = index !in session.excludedFindResults
                val current = index == session.findResultIndex
                val includeDescription = stringResource(R.string.replace_include_match, index + 1)
                Surface(shape = MaterialTheme.shapes.large,
                    color = if (review && included || !review && current) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Top) {
                        if (review) Checkbox(included, onCheckedChange = { session.toggleFindResultIncluded(index) },
                            modifier = Modifier.semantics { contentDescription = includeDescription })
                        Column(Modifier.weight(1f).clickable {
                            focus.clearFocus(force = true)
                            keyboard?.hide()
                            if (session.selectFindResult(index) && !wide) onReturn()
                        }.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.find_result_representation, index + 1, representationLabel(result.representation)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (result.alsoMatchesSource) Text(stringResource(R.string.find_also_source), style = MaterialTheme.typography.labelSmall)
                            val snippet = findSnippetContext(result.hit)
                            val spokenReplacement = if (review && result.hit.replacement != null)
                                stringResource(R.string.replace_result_description,
                                    snippet.first + result.hit.text + snippet.second,
                                    snippet.first + result.hit.replacement + snippet.second) else null
                            Text(buildAnnotatedString {
                                append(snippet.first)
                                val beforeStyle = SpanStyle(fontWeight = FontWeight.Bold,
                                    background = if (review && result.hit.replacement != null) MaterialTheme.colorScheme.errorContainer
                                        else MaterialTheme.colorScheme.tertiaryContainer,
                                    color = if (review && result.hit.replacement != null) MaterialTheme.colorScheme.onErrorContainer
                                        else MaterialTheme.colorScheme.onTertiaryContainer,
                                    textDecoration = if (review && result.hit.replacement != null) TextDecoration.LineThrough else null)
                                withStyle(beforeStyle) { append(result.hit.text.ifEmpty { "▏" }) }
                                if (review) result.hit.replacement?.let { replacement ->
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold,
                                        background = MaterialTheme.colorScheme.tertiaryContainer,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer)) {
                                        append(replacement.ifEmpty { "∅" })
                                    }
                                }
                                append(snippet.second)
                            }, modifier = if (spokenReplacement == null) Modifier else Modifier.clearAndSetSemantics {
                                contentDescription = spokenReplacement
                            }, maxLines = if (review) 8 else 5, overflow = TextOverflow.Ellipsis)
                            if (result.hit.text.isEmpty()) Text(stringResource(R.string.find_insertion_marker), style = MaterialTheme.typography.labelSmall)
                            if (review && !included) Text(stringResource(R.string.replace_excluded), style = MaterialTheme.typography.labelSmall)
                            if (result.representation == SearchRepresentation.FormulaSource || result.representation == SearchRepresentation.DiagramSource) {
                                TextButton(onClick = { session.selectFindResult(index); session.showTextEditor(); onReturn() }) {
                                    Text(stringResource(R.string.find_search_source))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (review) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { focusReview(); session.applyFindReplacements() },
                    enabled = session.canApplyFindReplacements,
                    shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text(pluralStringResource(R.plurals.replace_apply, session.includedReplacementCount, session.includedReplacementCount))
                }
            }
        }
    }
}

/** Keeps failures, incomplete coverage, and replacement feedback available outside review. */
@Composable
internal fun EditorFindFeedback(session: EditorSession, modifier: Modifier = Modifier,
    showFailure: Boolean = true, onUndo: () -> Unit) {
    val failure = session.findStatus as? FindStatus.Failed
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (session.findStatus == FindStatus.Searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (showFailure && failure != null) EditorFindFailure(failure, Modifier.fillMaxWidth(), session::retryFind)
        Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            if (failure == null) session.findCoverageMessage?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall) }
            session.findActionMessage?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall) }
            if (session.replacementUndoNoticeRevision != null) Text(stringResource(R.string.replace_undone),
                style = MaterialTheme.typography.bodySmall)
            session.locationMessage?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall) }
            if (session.isFindScopePaused) Text(stringResource(R.string.find_scope_lost), color = MaterialTheme.colorScheme.error)
        }
        if (session.findCoverageMessage != null && failure == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (session.canContinueFind) TextButton(onClick = { session.continueFind() }) { Text(stringResource(R.string.find_continue)) }
                TextButton(onClick = { session.retryFind() }) {
                    Text(stringResource(if (session.canContinueFind || session.hasEarlierFindResults) R.string.find_restart else R.string.find_retry))
                }
            }
        }
        if (session.canUndoFindReplacement) {
            TextButton(onUndo) { Text(stringResource(R.string.replace_undo)) }
        }
    }
}

/** Can share the query row when the landscape keyboard leaves little vertical space. */
@Composable
internal fun EditorFindFailure(failure: FindStatus.Failed, modifier: Modifier = Modifier,
    onRetry: () -> Unit, options: String = "") {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(buildAnnotatedString {
            if (options.isNotEmpty()) withStyle(SpanStyle(
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium
            )) { append(options); append(" · ") }
            append(failure.message.asString())
        }, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.find_retry))
        }
    }
}

/** Keeps nearby lines, removes unrelated paragraphs, and marks bounded context. */
internal fun findSnippetContext(hit: SearchHit): Pair<String, String> {
    val before = hit.before.substringAfterLast("\n\n")
    val boundedStart = before.length == hit.before.length && hit.range.start > hit.before.length
    val start = if (boundedStart && before.isNotEmpty()) {
        val wordEnd = before.indexOfFirst(Char::isWhitespace)
        "…" + if (wordEnd >= 0) before.substring(wordEnd) else before
    } else before.trimStart('\n')
    val after = hit.after.substringBefore("\n\n").trimEnd('\n')
    // The native search packet includes at most 40 Unicode scalars on each side.
    // At that boundary, finish at a word break and mark the omitted context.
    val boundedEnd = after == hit.after && after.codePointCount(0, after.length) == 40
    val end = if (boundedEnd) {
        val wordStart = after.indexOfLast(Char::isWhitespace)
        (if (wordStart > 0) after.substring(0, wordStart) else after) + "…"
    } else after
    return start to end
}

@Composable
private fun representationLabel(representation: SearchRepresentation): String = stringResource(when (representation) {
    SearchRepresentation.Source -> R.string.find_source_domain
    SearchRepresentation.Reading -> R.string.find_reading_domain
    SearchRepresentation.FormulaSource -> R.string.find_formula_source
    SearchRepresentation.DiagramSource -> R.string.find_diagram_source
    SearchRepresentation.Formula -> R.string.find_formula_run
    SearchRepresentation.DiagramLabel -> R.string.find_diagram_label
})
