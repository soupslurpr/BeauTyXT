/* Displays incremental Find controls and match feedback. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.UiText
import dev.soupslurpr.beautyxt.ui.asString

private val FindInlineStatusMaxWidth = 240.dp

private val FindCaseLabelIconSize = 24.dp
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

/** Displays retained Find controls and one compact result announcement. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorFindChrome(
    session: EditorSession,
    useInlineStatus: Boolean,
    onClose: () -> Unit
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
        )
    ) {
        val compact = usesCompactFindLayout(maxWidth, LocalDensity.current.fontScale)
        Column {
            FindChromeContent(session, useInlineStatus && !compact, compact, onClose)
        }
    }
}

/** Keeps one query input mounted while its surrounding actions reflow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FindChromeContent(
    session: EditorSession,
    useInlineStatus: Boolean,
    compact: Boolean,
    onClose: () -> Unit
) {
    val focusRequester = remember(session) { FocusRequester() }
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val status = session.findStatus
    val fieldDescription = stringResource(R.string.find_in_document)
    val failure = status as? FindStatus.Failed
    val appBarWithSearchColors = SearchBarDefaults.appBarWithSearchColors()
    val searchBarColors = appBarWithSearchColors.searchBarColors
    val findFieldContainerColor = searchBarColors.containerColor
    val findFieldColors =
        SearchBarDefaults.inputFieldColors().copy(
            focusedContainerColor = findFieldContainerColor,
            unfocusedContainerColor = findFieldContainerColor,
            disabledContainerColor = findFieldContainerColor,
            errorContainerColor = findFieldContainerColor,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent
        )
    LaunchedEffect(session) {
        withFrameNanos { }
        focusRequester.requestFocus()
        softwareKeyboardController?.show()
    }
    TopAppBar(
        navigationIcon = {
            FindIconButton(onClick = onClose) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.find_close)
                )
            }
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = session.findFieldValue,
                    onValueChange = { value -> session.updateFindFieldValue(value) },
                    modifier =
                        Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .semantics {
                                contentDescription = fieldDescription
                            },
                    placeholder = {
                        Text(
                            stringResource(R.string.find_in_document),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    trailingIcon = if (compact && session.findFieldValue.text.isEmpty()) {
                        null
                    } else {
                        {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (session.findFieldValue.text.isNotEmpty()) {
                                    FindIconButton(
                                        onClick = {
                                            session.updateFindFieldValue(TextFieldValue())
                                            focusRequester.requestFocus()
                                            softwareKeyboardController?.show()
                                        }
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_close),
                                            contentDescription =
                                                stringResource(R.string.find_clear_query)
                                        )
                                    }
                                }
                                if (!compact) {
                                    FindCaseToggle(
                                        checked = session.isFindCaseSensitive,
                                        onCheckedChange = session::updateFindCaseSensitivity
                                    )
                                }
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Search
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onSearch = { session.findNext() }
                        ),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = findFieldColors,
                    shape = SearchBarDefaults.inputFieldShape
                )
                if (useInlineStatus) {
                    Spacer(Modifier.width(EditorCompactSpacing))
                    FindInlineStatus(
                        status,
                        matchCase = session.isFindCaseSensitive,
                        canRetry = session.canNavigateFind,
                        onRetry = { session.retryFind() }
                    )
                }
            }
        },
        actions = {
            if (!compact) FindNavigation(session)
        },
        windowInsets =
            WindowInsets.safeDrawing.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal
            ),
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = appBarWithSearchColors.appBarContainerColor
            )
    )
    if (useInlineStatus) {
        return
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = appBarWithSearchColors.appBarContainerColor,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Column {
            HorizontalDivider(color = searchBarColors.dividerColor)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinimumBlockHeight)
                        .padding(start = EditorHorizontalPadding, end = EditorCompactSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (status == FindStatus.Searching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(TopBarProgressIndicatorSize),
                        strokeWidth = CompactStrokeWidth
                    )
                    Spacer(Modifier.width(EditorCompactSpacing))
                }
                Text(
                    text = findStatusMessage(
                        status,
                        matchCase = session.isFindCaseSensitive
                    ).asString(),
                    modifier = Modifier.weight(1f).semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
                if (failure != null) {
                    TextButton(
                        onClick = { session.retryFind() },
                        enabled = session.canNavigateFind
                    ) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
                if (compact) {
                    FindCaseToggle(
                        checked = session.isFindCaseSensitive,
                        onCheckedChange = session::updateFindCaseSensitivity
                    )
                    FindNavigation(session)
                }
            }
        }
    }
}

/** Displays adjacent previous/next actions in either Find layout. */
@Composable
private fun FindNavigation(session: EditorSession) {
    FindIconButton(
        onClick = { session.findPrevious() },
        enabled = session.canNavigateFind
    ) {
        Icon(
            painterResource(R.drawable.ic_keyboard_arrow_up),
            contentDescription = stringResource(R.string.find_previous)
        )
    }
    FindIconButton(
        onClick = { session.findNext() },
        enabled = session.canNavigateFind
    ) {
        Icon(
            painterResource(R.drawable.ic_keyboard_arrow_down),
            contentDescription = stringResource(R.string.find_next)
        )
    }
}

/** Displays one Find action with expressive press-shape motion when enabled. */
@Composable
private fun FindIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        enabled = enabled,
        content = content
    )
}

/** Displays the Find case-sensitivity control and its accessible selection state. */
@Composable
private fun FindCaseToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val label = stringResource(R.string.find_match_case)
    val state = stringResource(if (checked) R.string.find_case_on else R.string.find_case_off)
    val modifier =
        Modifier.semantics {
            contentDescription = label
            stateDescription = state
        }
    FilledTonalIconToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shapes = IconButtonDefaults.toggleableShapes(),
        modifier = modifier
    ) {
        FindCaseLabel(checked = checked)
    }
}

/** Displays the compact case-sensitivity label. */
@Composable
private fun FindCaseLabel(checked: Boolean) {
    val iconTextSize = with(LocalDensity.current) { FindCaseLabelIconSize.toSp() }
    Text(
        text = stringResource(R.string.find_case_symbol),
        color = LocalContentColor.current,
        fontSize = iconTextSize,
        fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal,
        lineHeight = iconTextSize
    )
}

/** Displays one live Find result beside the landscape query field. */
@Composable
private fun FindInlineStatus(
    status: FindStatus,
    matchCase: Boolean,
    canRetry: Boolean,
    onRetry: () -> Unit
) {
    Row(
        modifier = Modifier.widthIn(max = FindInlineStatusMaxWidth),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (status == FindStatus.Searching) {
            CircularProgressIndicator(
                modifier = Modifier.size(TopBarProgressIndicatorSize),
                strokeWidth = CompactStrokeWidth
            )
            Spacer(Modifier.width(EditorCompactSpacing))
        }
        Text(
            text = findStatusMessage(status, matchCase = matchCase).asString(),
            modifier = Modifier.weight(1f, fill = false).semantics {
                liveRegion = LiveRegionMode.Polite
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium
        )
        if (status is FindStatus.Failed) {
            TextButton(onClick = onRetry, enabled = canRetry) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}
