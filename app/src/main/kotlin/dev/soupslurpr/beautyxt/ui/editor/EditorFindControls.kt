@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.asString
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceButtons
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceOption

/** Keeps the query, replacement, and match navigation above the document. */
@Composable
internal fun EditorFindChrome(
    session: EditorSession,
    useInlineStatus: Boolean,
    onClose: () -> Unit,
    onOptions: () -> Unit
) {
    val queryFocus = remember(session) { FocusRequester() }
    val replacementFocus = remember(session) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var menu by remember(session) { mutableStateOf(false) }
    fun dismissInput() { focus.clearFocus(force = true); keyboard?.hide() }
    fun focusDocument() { dismissInput(); session.focusFindDocument() }
    fun openResults(review: Boolean) {
        dismissInput()
        session.updateFindResultsExpanded(true, reviewReplacements = review)
    }
    LaunchedEffect(session, session.findFocusRequest, session.isReplaceVisible) {
        val target = session.findInputFocus
        if (target != FindInputFocus.Query && target != FindInputFocus.Replacement) return@LaunchedEffect
        withFrameNanos { }
        if (session.findInputFocus != target) return@LaunchedEffect
        if (target == FindInputFocus.Replacement && session.isReplaceVisible) replacementFocus.requestFocus()
        else queryFocus.requestFocus()
        if (session.findRequestsKeyboard) keyboard?.show() else keyboard?.hide()
    }
    BoxWithConstraints(Modifier.fillMaxWidth()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        val wide = useInlineStatus || maxWidth >= 600.dp * maxOf(1f, LocalDensity.current.fontScale)
        val compact = usesCompactFindLayout(maxWidth, LocalDensity.current.fontScale)
        val failure = session.findStatus as? FindStatus.Failed
        val inlineFailureWidth = minOf(320.dp, maxWidth * 0.4f)
        val activeOptions = listOfNotNull(
            stringResource(R.string.find_selection_scope).takeIf { session.isFindSelectionScope },
            stringResource(R.string.find_match_case).takeIf { session.isFindCaseSensitive },
            stringResource(R.string.find_whole_word).takeIf { session.isFindWholeWord && !session.isFindRegex },
            stringResource(R.string.find_regex).takeIf { session.isFindRegex },
            stringResource(R.string.find_include_source).takeIf {
                session.includeIllustrationSource && session.presentation == EditorPresentation.MarkdownPreview
            })
        @Composable fun optionsLabel(modifier: Modifier = Modifier) {
            Text(activeOptions.joinToString(" · "), modifier, style = MaterialTheme.typography.labelMedium,
                maxLines = if (useInlineStatus) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
        }
        val fieldColors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        )
        @Composable fun replacement(modifier: Modifier) {
            TextField(session.replacementFieldValue, session::updateReplacementFieldValue,
                modifier = modifier.focusRequester(replacementFocus)
                    .onFocusChanged { if (it.isFocused) session.recordFindInputFocus(FindInputFocus.Replacement) },
                label = { Text(stringResource(R.string.replace_with)) },
                maxLines = if (useInlineStatus) 1 else 3,
                shape = MaterialTheme.shapes.large, colors = fieldColors,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default, showKeyboardOnFocus = session.findRequestsKeyboard))
        }
        @Composable fun tools() = Row {
            FindIcon(R.drawable.ic_tune, stringResource(R.string.find_options), onOptions)
            Box {
                FindIcon(R.drawable.ic_more_vert, stringResource(R.string.find_more), { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (session.presentation == EditorPresentation.MarkdownPreview)
                            R.string.find_search_source else R.string.find_read_formatted)) },
                        enabled = if (session.presentation == EditorPresentation.MarkdownPreview)
                            session.canShowTextEditor else session.canShowMarkdownPreview,
                        onClick = {
                            menu = false
                            focusDocument()
                            if (session.presentation == EditorPresentation.MarkdownPreview) session.showTextEditor()
                            else { session.hideReplace(); session.showMarkdownPreview() }
                        })
                    DropdownMenuItem(text = { Text(stringResource(R.string.editor_undo)) },
                        enabled = session.canUndo, onClick = { menu = false; focusDocument(); session.requestUndo() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.editor_redo)) },
                        enabled = session.canRedo, onClick = { menu = false; focusDocument(); session.requestRedo() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.location_previous)) },
                        enabled = session.hasPreviousLocation,
                        onClick = { menu = false; focusDocument(); session.returnToDocumentLocation(false) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.location_next)) },
                        enabled = session.hasNextLocation,
                        onClick = { menu = false; focusDocument(); session.returnToDocumentLocation(true) })
                }
            }
        }
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (useInlineStatus) 0.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                FindIcon(R.drawable.ic_close, stringResource(R.string.find_close), onClose)
                val queryLabel = stringResource(R.string.find_in_document)
                TextField(session.findFieldValue, { session.updateFindFieldValue(it) },
                    modifier = Modifier.weight(1f).focusRequester(queryFocus)
                        .onFocusChanged { if (it.isFocused) session.recordFindInputFocus(FindInputFocus.Query) }
                        .semantics { contentDescription = queryLabel }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || event.key != Key.Enter) false else {
                                if (event.isCtrlPressed) insertQueryLineBreak(session)
                                else if (event.isShiftPressed) session.findPrevious() else session.findNext()
                                true
                            }
                        },
                    label = { Text(stringResource(if (session.presentation == EditorPresentation.MarkdownPreview)
                        R.string.find_in_reading else R.string.find_in_source),
                        maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    maxLines = if (useInlineStatus) 1 else 3,
                    shape = MaterialTheme.shapes.extraLarge, colors = fieldColors,
                    trailingIcon = if (session.findFieldValue.text.isNotEmpty()) ({
                        FindIcon(R.drawable.ic_close, stringResource(R.string.find_clear_query), {
                            session.updateFindFieldValue(TextFieldValue(""))
                            queryFocus.requestFocus()
                            keyboard?.show()
                        })
                    }) else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = session.findRequestsKeyboard),
                    keyboardActions = KeyboardActions(onSearch = { session.findNext() }))
                if (wide && session.isReplaceVisible) {
                    Spacer(Modifier.width(8.dp))
                    replacement(Modifier.weight(1f))
                }
                if (!compact || useInlineStatus) tools()
                if (useInlineStatus) {
                    if (failure != null) EditorFindFailure(failure, Modifier.width(inlineFailureWidth),
                        onRetry = session::retryFind, options = activeOptions.joinToString(" · "))
                    else EditorMatchNavigation(session, onResults = { openResults(false) },
                        options = activeOptions.joinToString(" · "))
                    TextButton(onClick = { focusDocument() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.export_done))
                    }
                }
            }
            AnimatedVisibility(session.isReplaceVisible && !wide,
                enter = expandVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) +
                    fadeIn(animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()),
                exit = shrinkVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) +
                    fadeOut(animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec())) {
                Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 8.dp)) {
                    replacement(Modifier.weight(1f))
                }
            }
            if (activeOptions.isNotEmpty() && !useInlineStatus) optionsLabel(Modifier.padding(horizontal = 24.dp))
            if (!useInlineStatus) EditorFindActions(session, wide,
                onReplace = {
                    if (session.isReplaceVisible) { dismissInput(); session.hideReplace() }
                    else session.showReplace(showKeyboard = true)
                },
                onResults = { openResults(false) }, onReview = { openResults(true) },
                extraActions = { if (compact) tools() })
            EditorFindFeedback(session, Modifier.padding(horizontal = 16.dp), showFailure = !useInlineStatus, onUndo = {
                focusDocument()
                session.requestUndoFindReplacement()
            })
        }
    }
}

@Composable
internal fun FindIcon(icon: Int, label: String, action: () -> Unit, enabled: Boolean = true, emphasized: Boolean = false) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        if (emphasized) FilledTonalIconButton(action, enabled = enabled, shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(48.dp)) { Icon(painterResource(icon), label) }
        else IconButton(action, enabled = enabled, shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(48.dp)) { Icon(painterResource(icon), label) }
    }
}

@Composable
private fun EditorFindActions(
    session: EditorSession,
    wide: Boolean,
    onReplace: () -> Unit,
    onResults: () -> Unit,
    onReview: () -> Unit,
    extraActions: @Composable () -> Unit
) {
    @Composable fun navigation() {
        HorizontalFloatingToolbar(expanded = true, colors = FloatingToolbarDefaults.standardFloatingToolbarColors()) {
            EditorMatchNavigation(session, onResults)
        }
        TextButton(onReplace, enabled = !session.isViewOnly, modifier = Modifier.heightIn(min = 56.dp)) {
            Text(stringResource(when {
                session.isReplaceVisible -> R.string.replace_hide
                session.presentation == EditorPresentation.MarkdownPreview -> R.string.replace_in_source
                else -> R.string.replace_title
            }))
        }
        extraActions()
    }
    @Composable fun replacements() {
        OutlinedButton(onReview, enabled = session.findFieldValue.text.isNotEmpty(), shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.replace_review_all))
        }
        FilledTonalButton(onClick = {
            if (session.findResultIndex < 0) session.findNext() else session.applyFindReplacements(currentOnly = true)
        }, enabled = session.canReplaceCurrent || session.findResultIndex < 0 && session.findResults.isNotEmpty(),
            shapes = ButtonDefaults.shapes()) {
            Text(stringResource(if (session.findResultIndex < 0) R.string.replace_first_match else R.string.replace_current))
        }
    }
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
    if (wide) FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        itemVerticalAlignment = Alignment.CenterVertically, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        navigation()
        if (session.isReplaceVisible) replacements()
    } else Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) { navigation() }
        if (session.isReplaceVisible) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)) { replacements() }
    }
}

/** Shares match navigation with the single-row landscape typing layout. */
@Composable
private fun EditorMatchNavigation(session: EditorSession, onResults: () -> Unit, options: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val count = findCountText(session)
        val description = listOf(stringResource(R.string.find_results_description, count), options)
            .filter(String::isNotEmpty).joinToString(" ")
        TextButton(onResults, enabled = session.findFieldValue.text.isNotEmpty(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.widthIn(min = 88.dp, max = 144.dp).heightIn(min = 48.dp).semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            }) {
            Column(Modifier.clearAndSetSemantics { }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(listOf(stringResource(R.string.find_results), options).filter(String::isNotEmpty).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(count, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
        FindIcon(R.drawable.ic_keyboard_arrow_up, stringResource(R.string.find_previous),
            { session.findPrevious() }, session.findResults.isNotEmpty())
        FindIcon(R.drawable.ic_keyboard_arrow_down, stringResource(R.string.find_next),
            { session.findNext() }, session.findResults.isNotEmpty(), emphasized = true)
    }
}

/** Search settings get their own scrollable sheet, independent of match navigation. */
@Composable
internal fun EditorFindOptions(session: EditorSession, onDone: () -> Unit) {
    DocumentSheet(onDone) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(R.string.find_options), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() })
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(16.dp)) {
                    FindToggle(stringResource(R.string.find_match_case), session.isFindCaseSensitive) {
                        session.updateFindCaseSensitivity(it)
                    }
                    FindToggle(stringResource(R.string.find_whole_word), session.isFindWholeWord, !session.isFindRegex) {
                        session.updateFindWholeWord(it)
                    }
                    FindToggle(stringResource(R.string.find_regex), session.isFindRegex, onChange = session::updateFindRegex)
                }
            }
            if (session.isFindRegex) {
                Text(stringResource(R.string.find_regex_help), style = MaterialTheme.typography.bodySmall)
                if (session.isReplaceVisible) Text(stringResource(R.string.replace_regex_help), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.find_scope_title), style = MaterialTheme.typography.titleSmall)
            SingleChoiceButtons(listOf(
                SingleChoiceOption(stringResource(R.string.find_document_scope), !session.isFindSelectionScope, session::useDocumentFindScope),
                SingleChoiceOption(stringResource(R.string.find_selection_scope), session.isFindSelectionScope,
                    { session.captureSelectionFindScope() })
            ))
            if (session.presentation == EditorPresentation.MarkdownPreview) {
                FindToggle(stringResource(R.string.find_include_source), session.includeIllustrationSource,
                    onChange = session::updateIncludeIllustrationSource)
            }
            if (session.isFindScopePaused) Text(stringResource(R.string.find_scope_lost),
                color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            session.findActionMessage?.let { Text(it.asString(), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            TextButton(onClick = { insertQueryLineBreak(session) }) { Text(stringResource(R.string.find_insert_line_break)) }
            FilledTonalButton(onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.export_done))
            }
        }
    }
}

@Composable
private fun FindToggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 16.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
