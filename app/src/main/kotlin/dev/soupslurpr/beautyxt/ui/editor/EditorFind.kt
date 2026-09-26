/* Displays incremental Find controls and match feedback. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.asString


private val FindSingleRowMinWidth = 360.dp

/** Moves Find actions below the query when its text would be crowded. */
internal fun usesCompactFindLayout(availableWidth: Dp, fontScale: Float): Boolean {
    require(availableWidth >= 0.dp) { "Find width must not be negative" }
    require(fontScale.isFinite() && fontScale > 0f) { "font scale must be positive and finite" }
    return availableWidth < FindSingleRowMinWidth * maxOf(1f, fontScale)
}

/** Returns the concise accessible status for one retained Find result. */
internal fun findStatusMessage(status: FindStatus, matchCase: Boolean = false): UiText =
    when (status) {
        FindStatus.Idle -> UiText.Resource(
            if (matchCase) R.string.find_case_enabled else R.string.find_ignores_case
        )

        FindStatus.Searching -> UiText.Resource(R.string.find_searching)

        FindStatus.NoMatches -> UiText.Resource(R.string.find_no_matches)

        is FindStatus.Failed -> status.message

        is FindStatus.Match -> {
            val displayLine = Math.incrementExact(status.match.start.line)
            UiText.Resource(
                when (status.wrappedAt) {
                    FindWrap.Beginning -> R.string.find_wrapped_beginning
                    FindWrap.End -> R.string.find_wrapped_end
                    null -> R.string.find_match_line
                },
                listOf(displayLine)
            )
        }
    }

internal enum class FindInputFocus { Document, Query, Replacement, Results }

/** Keeps match navigation beside its count; location history has a separate labeled row. */
@Composable
internal fun EditorFindChrome(session: EditorSession, useInlineStatus: Boolean, onClose: () -> Unit) {
    val focus = remember(session) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val queryLabel = stringResource(R.string.find_in_document)
    LaunchedEffect(session, session.findFocusRequest) {
        if (session.findInputFocus != FindInputFocus.Query) return@LaunchedEffect
        withFrameNanos { }
        if (session.findInputFocus != FindInputFocus.Query) return@LaunchedEffect
        focus.requestFocus()
        if (session.findRequestsKeyboard) keyboard?.show() else keyboard?.hide()
    }
    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.find_close))
            }
            TextField(
                value = session.findFieldValue,
                onValueChange = { session.updateFindFieldValue(it) },
                modifier = Modifier.weight(1f).focusRequester(focus)
                    .onFocusChanged { if (it.isFocused) session.recordFindInputFocus(FindInputFocus.Query) }
                    .semantics { contentDescription = queryLabel }.onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || event.key != Key.Enter) false
                    else {
                        if (event.isCtrlPressed) insertQueryLineBreak(session)
                        else if (event.isShiftPressed) session.findPrevious() else session.findNext()
                        true
                    }
                },
                label = { Text(stringResource(R.string.find_in_document), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                maxLines = if (useInlineStatus) 1 else 4,
                supportingText = if (useInlineStatus) ({ Text("${findDomainScopeText(session)} · ${findCountText(session)}") }) else null,
                trailingIcon = if (session.findFieldValue.text.isNotEmpty()) ({
                    IconButton(onClick = {
                        session.updateFindFieldValue(TextFieldValue(""))
                        focus.requestFocus()
                        keyboard?.show()
                    }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.find_clear_query)) }
                }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = session.findRequestsKeyboard),
                keyboardActions = KeyboardActions(onSearch = { session.findNext() }),
                shape = MaterialTheme.shapes.large
            )
            if (session.findStatus is FindStatus.Failed || session.findCoverageMessage != null)
                TextButton(onClick = { session.retryFind() }) { Text(stringResource(R.string.action_retry)) }
            if (useInlineStatus) {
                IconButton(onClick = { session.findPrevious() }, enabled = session.findResults.isNotEmpty()) {
                    Icon(painterResource(R.drawable.ic_keyboard_arrow_up), stringResource(R.string.find_previous))
                }
                IconButton(onClick = { session.findNext() }, enabled = session.findResults.isNotEmpty()) {
                    Icon(painterResource(R.drawable.ic_keyboard_arrow_down), stringResource(R.string.find_next))
                }
                TextButton(onClick = { session.updateFindResultsExpanded(!session.isFindResultsExpanded) }) {
                    Text(stringResource(if (session.isFindResultsExpanded) R.string.find_hide_results else R.string.find_results))
                }
            }
        }
        if (!useInlineStatus) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(findDomainScopeText(session), style = MaterialTheme.typography.labelMedium)
                Text(findCountText(session), style = MaterialTheme.typography.labelLarge)
            }
            IconButton(onClick = { session.findPrevious() }, enabled = session.findResults.isNotEmpty()) {
                Icon(painterResource(R.drawable.ic_keyboard_arrow_up), stringResource(R.string.find_previous))
            }
            IconButton(onClick = { session.findNext() }, enabled = session.findResults.isNotEmpty()) {
                Icon(painterResource(R.drawable.ic_keyboard_arrow_down), stringResource(R.string.find_next))
            }
        }
        if (!session.isFindResultsExpanded) {
            val feedback = if (session.isFindScopePaused) UiText.Resource(R.string.find_scope_lost)
                else (session.findStatus as? FindStatus.Failed)?.message ?: session.findActionMessage
            feedback?.let { Text(it.asString(), Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall) }
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { session.updateFindResultsExpanded(!session.isFindResultsExpanded) }) {
                Text(stringResource(if (session.isFindResultsExpanded) R.string.find_hide_results else R.string.find_results))
            }
            TextButton(onClick = { if (session.isReplaceVisible) session.hideReplace() else session.showReplace() }, enabled = !session.isViewOnly) {
                Text(stringResource(if (session.presentation == EditorPresentation.MarkdownPreview) R.string.replace_in_source else R.string.replace_title))
            }
            TextButton(onClick = {
                if (session.presentation == EditorPresentation.MarkdownPreview) session.showTextEditor() else session.showMarkdownPreview()
            }, enabled = session.canShowTextEditor || session.canShowMarkdownPreview) {
                Text(stringResource(if (session.presentation == EditorPresentation.MarkdownPreview) R.string.find_source_domain else R.string.find_reading_domain))
            }
        }
        }
        HorizontalDivider()
    }
}

@Composable
private fun findDomainScopeText(session: EditorSession): String = stringResource(R.string.find_domain_scope,
    stringResource(if (session.presentation == EditorPresentation.Text) R.string.find_source_domain else R.string.find_reading_domain),
    stringResource(if (session.isFindSelectionScope) R.string.find_selection_scope else R.string.find_document_scope))

@Composable
private fun findCountText(session: EditorSession): String = when {
    session.findFieldValue.text.isEmpty() -> stringResource(R.string.find_in_document)
    !session.isFindComplete -> pluralStringResource(R.plurals.find_partial_matches, session.findResults.size, session.findResults.size)
    session.findResultIndex >= 0 -> pluralStringResource(R.plurals.find_match_count, session.findResults.size, session.findResultIndex + 1, session.findResults.size)
    else -> pluralStringResource(R.plurals.find_total_matches, session.findResults.size, session.findResults.size)
}

private fun insertQueryLineBreak(session: EditorSession) {
    val value = session.findFieldValue
    val start = value.selection.min
    val text = value.text.replaceRange(start, value.selection.max, "\n")
    session.updateFindFieldValue(TextFieldValue(text, androidx.compose.ui.text.TextRange(start + 1)))
}

/** Options, scope, previews, exclusions and Apply share one scroll surface. */
@Composable
internal fun EditorFindResults(session: EditorSession, wide: Boolean, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val resultsFocus = remember(session) { FocusRequester() }
    LaunchedEffect(session, session.findFocusRequest) {
        if (session.findInputFocus != FindInputFocus.Results) return@LaunchedEffect
        withFrameNanos { }
        if (session.findInputFocus != FindInputFocus.Results) return@LaunchedEffect
        resultsFocus.requestFocus()
        keyboard?.hide()
    }
    fun focusReview() {
        session.recordFindInputFocus(FindInputFocus.Results)
        resultsFocus.requestFocus()
        keyboard?.hide()
    }
    LazyColumn(modifier.fillMaxWidth().focusRequester(resultsFocus)
        .onFocusChanged { if (it.isFocused) session.recordFindInputFocus(FindInputFocus.Results) }
        .onPreviewKeyEvent { event ->
            session.findInputFocus == FindInputFocus.Results &&
                handleEditorHistoryShortcut(event, session::requestUndo, session::requestRedo)
        }.focusable(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "options") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (session.presentation == EditorPresentation.Text) R.string.find_source_domain else R.string.find_reading_domain),
                    style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = session.isFindCaseSensitive, onClick = { session.updateFindCaseSensitivity(!session.isFindCaseSensitive) },
                        label = { Text(stringResource(R.string.find_match_case)) })
                    FilterChip(selected = session.isFindWholeWord, onClick = { session.updateFindWholeWord(!session.isFindWholeWord) }, enabled = !session.isFindRegex,
                        label = { Text(stringResource(R.string.find_whole_word)) })
                    FilterChip(selected = session.isFindRegex, onClick = { session.updateFindRegex(!session.isFindRegex) },
                        label = { Text(stringResource(R.string.find_regex)) })
                }
                if (session.isFindRegex) Text(stringResource(R.string.find_regex_help), style = MaterialTheme.typography.bodySmall)
                if (session.presentation == EditorPresentation.MarkdownPreview) {
                    FilterChip(selected = session.includeIllustrationSource,
                        onClick = { session.updateIncludeIllustrationSource(!session.includeIllustrationSource) },
                        label = { Text(stringResource(R.string.find_include_source)) })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !session.isFindSelectionScope,
                        onClick = { session.useDocumentFindScope() }, label = { Text(stringResource(R.string.find_document_scope)) })
                    FilterChip(selected = session.isFindSelectionScope,
                        onClick = { session.captureSelectionFindScope() }, label = { Text(stringResource(R.string.find_selection_scope)) })
                    TextButton(onClick = { insertQueryLineBreak(session) }) { Text(stringResource(R.string.find_insert_line_break)) }
                }
                if (session.isReplaceVisible) {
                    val replacementFocus = remember(session) { FocusRequester() }
                    LaunchedEffect(session, session.findFocusRequest) {
                        if (session.findInputFocus == FindInputFocus.Replacement) {
                            withFrameNanos { }
                            if (session.findInputFocus == FindInputFocus.Replacement) replacementFocus.requestFocus()
                        }
                    }
                    OutlinedTextField(value = session.replacementFieldValue,
                        onValueChange = session::updateReplacementFieldValue,
                        modifier = Modifier.fillMaxWidth().focusRequester(replacementFocus)
                            .onFocusChanged { if (it.isFocused) session.recordFindInputFocus(FindInputFocus.Replacement) },
                        label = { Text(stringResource(R.string.replace_with)) },
                        minLines = 2, maxLines = 6, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default))
                }
            }
        }
        item(key = "status") {
            val included = session.includedReplacementCount
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (session.findStatus == FindStatus.Searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                // Announce the review summary together, without rereading every action button.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    (session.findStatus as? FindStatus.Failed)?.let { Text(it.message.asString(), color = MaterialTheme.colorScheme.error) }
                    session.findCoverageMessage?.let { Text(it.asString(), style = MaterialTheme.typography.bodyMedium) }
                    session.findActionMessage?.let { Text(it.asString()) }
                    if (session.isFindScopePaused) Text(stringResource(R.string.find_scope_lost), color = MaterialTheme.colorScheme.error)
                    if (session.isReplaceVisible) {
                        Text(stringResource(R.string.replace_review_scope, included, session.excludedFindResults.size,
                            stringResource(if (session.capturedFindScope == null) R.string.find_document_scope else R.string.find_selection_scope)))
                        val unchanged = session.unchangedReplacementCount
                        if (unchanged > 0) Text(pluralStringResource(R.plurals.replace_unchanged, unchanged, unchanged))
                    }
                }
                if (session.findCoverageMessage != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (session.canContinueFind) {
                            TextButton(onClick = { session.continueFind() }) { Text(stringResource(R.string.find_continue)) }
                        }
                        TextButton(onClick = { session.retryFind() }) {
                            Text(stringResource(if (session.canContinueFind || session.hasEarlierFindResults)
                                R.string.find_restart else R.string.find_retry))
                        }
                    }
                }
                if (session.isReplaceVisible) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            focusReview(); session.applyFindReplacements()
                        }, enabled = session.canApplyFindReplacements) { Text(pluralStringResource(R.plurals.replace_apply, included, included)) }
                        TextButton(onClick = {
                            focusReview(); session.applyFindReplacements(currentOnly = true)
                        }, enabled = session.canReplaceCurrent) {
                            Text(stringResource(R.string.replace_current))
                        }
                        TextButton(onClick = { focusReview(); session.requestUndo() }, enabled = session.canUndo) {
                            Text(stringResource(R.string.editor_undo))
                        }
                        TextButton(onClick = { focusReview(); session.requestRedo() }, enabled = session.canRedo) {
                            Text(stringResource(R.string.editor_redo))
                        }
                    }
                }
            }
        }
        items(session.findResults.size, key = { "match-$it" }) { index ->
            val result = session.findResults[index]
            val current = index == session.findResultIndex
            val includeDescription = stringResource(R.string.replace_include_match, index + 1)
            Surface(shape = MaterialTheme.shapes.large,
                color = if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Top) {
                    if (session.isReplaceVisible) Checkbox(checked = index !in session.excludedFindResults,
                        onCheckedChange = { session.toggleFindResultIncluded(index) },
                        modifier = Modifier.semantics { contentDescription = includeDescription })
                    Column(Modifier.weight(1f).clickable {
                        focus.clearFocus(); keyboard?.hide()
                        if (session.selectFindResult(index) && !wide && !session.isReplaceVisible) session.updateFindResultsExpanded(false)
                    }.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${index + 1} · ${representationLabel(result.representation)}", style = MaterialTheme.typography.labelMedium)
                        if (result.alsoMatchesSource) Text(stringResource(R.string.find_also_source), style = MaterialTheme.typography.labelSmall)
                        Text(buildAnnotatedString {
                            append(result.hit.before)
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = MaterialTheme.colorScheme.tertiaryContainer)) {
                                append(if (result.hit.text.isEmpty()) "▏" else result.hit.text)
                            }
                            append(result.hit.after)
                        }, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        if (result.hit.text.isEmpty()) Text(stringResource(R.string.find_insertion_marker), style = MaterialTheme.typography.labelSmall)
                        result.hit.replacement?.let { replacement ->
                            Text(stringResource(R.string.replace_after), style = MaterialTheme.typography.labelSmall)
                            Text(replacement.ifEmpty { "∅" }, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        }
                        if (result.representation in listOf(SearchRepresentation.FormulaSource, SearchRepresentation.DiagramSource)) {
                            TextButton(onClick = { session.selectFindResult(index); session.showTextEditor() }) { Text(stringResource(R.string.find_source_domain)) }
                        }
                    }
                }
            }
        }
    }
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

/** Written labels make this independent history distinct from the query's match arrows. */
@Composable
internal fun EditorLocationControls(session: EditorSession) {
    if (!session.hasPreviousLocation && !session.hasNextLocation && session.locationMessage == null) return
    Column(Modifier.fillMaxWidth()) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { session.returnToDocumentLocation(false) }, enabled = session.hasPreviousLocation) {
                Text(stringResource(R.string.location_previous))
            }
            TextButton(onClick = { session.returnToDocumentLocation(true) }, enabled = session.hasNextLocation) {
                Text(stringResource(R.string.location_next))
            }
        }
        session.locationMessage?.let { Text(it.asString(), Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
        HorizontalDivider()
    }
}
