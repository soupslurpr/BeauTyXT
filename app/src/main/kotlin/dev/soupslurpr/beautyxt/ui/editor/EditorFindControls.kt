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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.document.MAX_FIND_QUERY_UTF16_UNITS
import dev.soupslurpr.beautyxt.document.MAX_REPLACEMENT_REVIEW_UNITS
import dev.soupslurpr.beautyxt.ui.asString
import dev.soupslurpr.beautyxt.ui.designsystem.ActionMenuItem
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceButtons
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceOption

private val MinimumInlineFindQueryWidth = 160.dp
private val InlineFindCloseWidth = 48.dp
private val InlineFindToolsWidth = 96.dp
private val InlineFindNavWidth = 249.dp
private val InlineFindDoneWidth = 72.dp
private val InlineFindRowHorizontalPadding = 16.dp
private val InlineFindReplacementGap = 8.dp

/** Describes which inline landscape Find controls fit while keeping at least 160 dp per field. */
internal data class InlineFindLayoutPlan(
    val showReplacementBesideQuery: Boolean,
    val showOptionsIcon: Boolean,
    val showStatusControls: Boolean,
    val showDoneButton: Boolean
)

/** Budgets visible input fields first, then keeps as many inline actions as fit beside them. */
internal fun inlineFindLayoutPlan(
    availableWidth: Dp,
    fontScale: Float,
    replaceVisible: Boolean,
    hasFailure: Boolean
): InlineFindLayoutPlan {
    require(availableWidth >= 0.dp) { "Find width must not be negative" }
    require(fontScale.isFinite() && fontScale > 0f) { "Find font scale must be positive and finite" }
    val clampedScale = maxOf(1f, fontScale)
    val rowInnerWidth = (availableWidth - InlineFindRowHorizontalPadding).coerceAtLeast(0.dp)
    val statusWidth = if (hasFailure) minOf(320.dp, availableWidth * 0.4f) else InlineFindNavWidth
    val doneWidth = InlineFindDoneWidth * clampedScale
    val fullTrailingWidth = InlineFindToolsWidth + statusWidth + doneWidth
    val navMoreTrailingWidth = InlineFindCloseWidth + statusWidth
    val moreOnlyTrailingWidth = InlineFindCloseWidth
    val singleFieldWidth = InlineFindCloseWidth + MinimumInlineFindQueryWidth
    val dualFieldsWidth = InlineFindCloseWidth + MinimumInlineFindQueryWidth * 2 + InlineFindReplacementGap

    if (replaceVisible) {
        if (rowInnerWidth >= dualFieldsWidth + fullTrailingWidth) {
            return InlineFindLayoutPlan(
                showReplacementBesideQuery = true,
                showOptionsIcon = true,
                showStatusControls = true,
                showDoneButton = true
            )
        }
        if (rowInnerWidth >= dualFieldsWidth + navMoreTrailingWidth) {
            return InlineFindLayoutPlan(
                showReplacementBesideQuery = true,
                showOptionsIcon = false,
                showStatusControls = true,
                showDoneButton = false
            )
        }
        if (rowInnerWidth >= dualFieldsWidth + moreOnlyTrailingWidth) {
            return InlineFindLayoutPlan(
                showReplacementBesideQuery = true,
                showOptionsIcon = false,
                showStatusControls = false,
                showDoneButton = false
            )
        }
    }
    return when {
        rowInnerWidth >= singleFieldWidth + fullTrailingWidth -> InlineFindLayoutPlan(
            showReplacementBesideQuery = false,
            showOptionsIcon = true,
            showStatusControls = true,
            showDoneButton = true
        )

        rowInnerWidth >= singleFieldWidth + navMoreTrailingWidth -> InlineFindLayoutPlan(
            showReplacementBesideQuery = false,
            showOptionsIcon = false,
            showStatusControls = true,
            showDoneButton = false
        )

        else -> InlineFindLayoutPlan(
            showReplacementBesideQuery = false,
            showOptionsIcon = false,
            showStatusControls = false,
            showDoneButton = false
        )
    }
}

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
    val queryInput = remember(session) {
        findInputTransformation(MAX_FIND_QUERY_UTF16_UNITS) { session.isFindVisible }
    }
    val replacementInput = remember(session) { findInputTransformation(MAX_REPLACEMENT_REVIEW_UNITS) }
    var menu by remember(session) { mutableStateOf(false) }
    var isQueryFocused by remember(session) { mutableStateOf(false) }
    var isReplacementFocused by remember(session) { mutableStateOf(false) }
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
    val imeTargetVisible = WindowInsets.imeAnimationTarget.getBottom(LocalDensity.current) > 0
    val currentImeTargetVisible by rememberUpdatedState(imeTargetVisible)
    BoxWithConstraints(Modifier.fillMaxWidth()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        val fontScale = maxOf(1f, LocalDensity.current.fontScale)
        val failure = session.findStatus as? FindStatus.Failed
        val inlineFailureWidth = minOf(320.dp, maxWidth * 0.4f)
        val inlinePlan = if (useInlineStatus) {
            inlineFindLayoutPlan(
                availableWidth = maxWidth,
                fontScale = LocalDensity.current.fontScale,
                replaceVisible = session.isReplaceVisible,
                hasFailure = failure != null
            )
        } else {
            null
        }
        val wide = inlinePlan?.showReplacementBesideQuery ?: (maxWidth >= 600.dp * fontScale)
        var previousWide by remember(session) { mutableStateOf(wide) }
        var suppressReplaceKeyboardOnReflow by remember(session) { mutableStateOf(false) }
        LaunchedEffect(session, session.findFocusRequest) {
            suppressReplaceKeyboardOnReflow = false
        }
        LaunchedEffect(session, session.isReplaceVisible, session.findInputFocus) {
            if (!session.isReplaceVisible || session.findInputFocus != FindInputFocus.Replacement) {
                suppressReplaceKeyboardOnReflow = false
            }
        }
        LaunchedEffect(session, wide) {
            if (previousWide == wide) return@LaunchedEffect
            previousWide = wide
            if (session.isReplaceVisible && session.findInputFocus == FindInputFocus.Replacement) {
                val keepKeyboard = currentImeTargetVisible
                suppressReplaceKeyboardOnReflow = !keepKeyboard
                withFrameNanos { }
                if (session.isReplaceVisible && session.findInputFocus == FindInputFocus.Replacement) {
                    replacementFocus.requestFocus()
                    if (keepKeyboard) {
                        keyboard?.show()
                    }
                }
            }
        }
        val compact = usesCompactFindLayout(maxWidth, LocalDensity.current.fontScale)
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
        val focusBorderColor = MaterialTheme.colorScheme.primary
        val queryShape = MaterialTheme.shapes.extraLarge
        val replacementShape = MaterialTheme.shapes.large
        @Composable fun replacement(modifier: Modifier) {
            TextField(state = session.replacementFieldState,
                modifier = modifier
                    .then(
                        if (isReplacementFocused) Modifier.border(2.dp, focusBorderColor, replacementShape)
                        else Modifier
                    )
                    .focusRequester(replacementFocus)
                    .onFocusChanged {
                        isReplacementFocused = it.isFocused
                        if (it.isFocused) session.recordFindInputFocus(FindInputFocus.Replacement)
                    },
                label = { Text(stringResource(R.string.replace_with)) },
                inputTransformation = replacementInput,
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = if (useInlineStatus) 1 else 3),
                shape = replacementShape, colors = fieldColors,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Default,
                    showKeyboardOnFocus = !suppressReplaceKeyboardOnReflow &&
                        (session.findRequestsKeyboard || imeTargetVisible)
                ))
        }
        val showInlineOptionsIcon = inlinePlan?.showOptionsIcon ?: true
        val showInlineStatusControls = inlinePlan?.showStatusControls ?: false
        val showInlineDoneButton = inlinePlan?.showDoneButton ?: false
        var menuAnchoredInTopRow by remember(session) { mutableStateOf(false) }
        var menuShowOptions by remember(session) { mutableStateOf(false) }
        var menuShowNav by remember(session) { mutableStateOf(false) }
        var menuShowDone by remember(session) { mutableStateOf(false) }
        val showToolsInTopRow = !compact || useInlineStatus || (menu && menuAnchoredInTopRow)
        @Composable fun tools(includeOptionsIcon: Boolean = true, anchoredInTopRow: Boolean = true) = Row {
            if (includeOptionsIcon) {
                FindIcon(R.drawable.ic_tune, stringResource(R.string.find_options), onOptions)
            }
            Box {
                FindIcon(
                    R.drawable.ic_more_vert,
                    stringResource(R.string.find_more),
                    {
                        menuAnchoredInTopRow = anchoredInTopRow
                        menuShowOptions = !includeOptionsIcon
                        menuShowNav = useInlineStatus && !showInlineStatusControls
                        menuShowDone = useInlineStatus && !showInlineDoneButton
                        menu = true
                    }
                )
                DropdownMenuPopup(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuGroup(
                        shapes = MenuDefaults.groupShape(index = 0, count = 1),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        containerColor = MenuDefaults.groupStandardContainerColor
                    ) {
                        val leadingShape = MenuDefaults.leadingItemShape
                        val middleShape = MenuDefaults.middleItemShape
                        val trailingShape = MenuDefaults.trailingItemShape
                        var hasLeading = false
                        fun nextShape(isLast: Boolean = false) = when {
                            !hasLeading -> {
                                hasLeading = true
                                leadingShape
                            }
                            isLast -> trailingShape
                            else -> middleShape
                        }
                        if (menuShowOptions) {
                            ActionMenuItem(
                                shape = nextShape(),
                                label = stringResource(R.string.find_options),
                                leadingIcon = { Icon(painterResource(R.drawable.ic_tune), contentDescription = null) },
                                onClick = { menu = false; onOptions() }
                            )
                        }
                        if (menuShowNav) {
                            ActionMenuItem(
                                shape = nextShape(),
                                label = stringResource(R.string.find_results),
                                leadingIcon = { Icon(painterResource(R.drawable.ic_format_list_bulleted), contentDescription = null) },
                                enabled = session.findFieldValue.text.isNotEmpty(),
                                onClick = { menu = false; openResults(false) }
                            )
                            ActionMenuItem(
                                shape = nextShape(),
                                label = stringResource(R.string.find_previous),
                                leadingIcon = { Icon(painterResource(R.drawable.ic_keyboard_arrow_up), contentDescription = null) },
                                enabled = session.findResults.isNotEmpty(),
                                onClick = { menu = false; session.findPrevious() }
                            )
                            ActionMenuItem(
                                shape = nextShape(),
                                label = stringResource(R.string.find_next),
                                leadingIcon = { Icon(painterResource(R.drawable.ic_keyboard_arrow_down), contentDescription = null) },
                                enabled = session.findResults.isNotEmpty(),
                                onClick = { menu = false; session.findNext() }
                            )
                        }
                        if (menuShowDone) {
                            ActionMenuItem(
                                shape = nextShape(),
                                label = stringResource(R.string.export_done),
                                leadingIcon = { Icon(painterResource(R.drawable.ic_check), contentDescription = null) },
                                onClick = { menu = false; focusDocument() }
                            )
                        }
                        if (hasLeading) {
                            HorizontalDivider(modifier = Modifier.padding(MenuDefaults.HorizontalDividerPadding))
                        }
                        ActionMenuItem(
                            shape = nextShape(),
                            label = stringResource(if (session.presentation == EditorPresentation.MarkdownPreview)
                                R.string.find_search_source else R.string.find_read_formatted),
                            leadingIcon = {
                                Icon(
                                    painterResource(
                                        if (session.presentation == EditorPresentation.MarkdownPreview && !session.isViewOnly) {
                                            R.drawable.ic_edit
                                        } else {
                                            R.drawable.ic_description
                                        }
                                    ),
                                    contentDescription = null
                                )
                            },
                            enabled = if (session.presentation == EditorPresentation.MarkdownPreview)
                                session.canShowTextEditor else session.canShowMarkdownPreview,
                            onClick = {
                                menu = false
                                focusDocument()
                                if (session.presentation == EditorPresentation.MarkdownPreview) session.showTextEditor()
                                else { session.hideReplace(); session.showMarkdownPreview() }
                            }
                        )
                        ActionMenuItem(
                            shape = nextShape(),
                            label = stringResource(R.string.editor_undo),
                            leadingIcon = { Icon(painterResource(R.drawable.ic_undo), contentDescription = null) },
                            enabled = session.canUndo,
                            onClick = { menu = false; focusDocument(); session.requestUndo() }
                        )
                        ActionMenuItem(
                            shape = nextShape(),
                            label = stringResource(R.string.editor_redo),
                            leadingIcon = { Icon(painterResource(R.drawable.ic_redo), contentDescription = null) },
                            enabled = session.canRedo,
                            onClick = { menu = false; focusDocument(); session.requestRedo() }
                        )
                        HorizontalDivider(modifier = Modifier.padding(MenuDefaults.HorizontalDividerPadding))
                        ActionMenuItem(
                            shape = nextShape(),
                            label = stringResource(R.string.location_previous),
                            leadingIcon = { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null) },
                            enabled = session.hasPreviousLocation,
                            onClick = { menu = false; focusDocument(); session.returnToDocumentLocation(false) }
                        )
                        ActionMenuItem(
                            shape = nextShape(isLast = true),
                            label = stringResource(R.string.location_next),
                            leadingIcon = {
                                Icon(
                                    painterResource(R.drawable.ic_arrow_back),
                                    contentDescription = null,
                                    modifier = Modifier.rotate(180f)
                                )
                            },
                            enabled = session.hasNextLocation,
                            onClick = { menu = false; focusDocument(); session.returnToDocumentLocation(true) }
                        )
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (useInlineStatus) 0.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                FindIcon(R.drawable.ic_close, stringResource(R.string.find_close), onClose)
                val queryLabel = stringResource(R.string.find_in_document)
                TextField(state = session.findFieldState,
                    modifier = Modifier.weight(1f)
                        .then(
                            if (isQueryFocused) Modifier.border(2.dp, focusBorderColor, queryShape)
                            else Modifier
                        )
                        .focusRequester(queryFocus)
                        .onFocusChanged {
                            isQueryFocused = it.isFocused
                            if (it.isFocused) session.recordFindInputFocus(FindInputFocus.Query)
                        }
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
                    inputTransformation = queryInput,
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = if (useInlineStatus) 1 else 3),
                    shape = queryShape, colors = fieldColors,
                    trailingIcon = if (session.findFieldValue.text.isNotEmpty()) ({
                        FindIcon(R.drawable.ic_close, stringResource(R.string.find_clear_query), {
                            session.updateFindFieldValue(TextFieldValue(""))
                            queryFocus.requestFocus()
                            keyboard?.show()
                        })
                    }) else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = session.findRequestsKeyboard),
                    onKeyboardAction = { session.findNext() })
                if (wide && session.isReplaceVisible) {
                    Spacer(Modifier.width(8.dp))
                    replacement(Modifier.weight(1f))
                }
                if (showToolsInTopRow) {
                    tools(includeOptionsIcon = showInlineOptionsIcon, anchoredInTopRow = true)
                }
                if (showInlineStatusControls) {
                    if (failure != null) EditorFindFailure(failure, Modifier.width(inlineFailureWidth),
                        onRetry = session::retryFind, options = activeOptions.joinToString(" · "))
                    else EditorMatchNavigation(session, onResults = { openResults(false) },
                        options = activeOptions.joinToString(" · "))
                }
                if (showInlineDoneButton) {
                    TextButton(onClick = { focusDocument() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.export_done))
                    }
                }
            }
            if (useInlineStatus && !showInlineStatusControls && failure == null &&
                (session.findFieldValue.text.isNotEmpty() || activeOptions.isNotEmpty())
            ) {
                val summary = listOf(
                    findCountText(session).takeIf { session.findFieldValue.text.isNotEmpty() },
                    activeOptions.joinToString(" · ").takeIf(String::isNotEmpty)
                ).filterNotNull().joinToString(" · ")
                Text(
                    text = summary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            AnimatedVisibility(session.isReplaceVisible && !wide,
                enter = expandVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) +
                    fadeIn(animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()),
                exit = shrinkVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) +
                    fadeOut(animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec())) {
                Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = if (useInlineStatus) 4.dp else 8.dp)) {
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
                extraActions = { if (!showToolsInTopRow) tools(anchoredInTopRow = false) })
            EditorFindFeedback(session, Modifier.padding(horizontal = 16.dp), showFailure = !showInlineStatusControls, onUndo = {
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
