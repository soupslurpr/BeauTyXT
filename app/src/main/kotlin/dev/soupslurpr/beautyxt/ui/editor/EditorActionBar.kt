@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package dev.soupslurpr.beautyxt.ui.editor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R

private val EditorActionSize = 48.dp
private val EditorActionBarPadding = 8.dp
private val EditorActionLabelMinWidth = 360.dp
private val MinimumPaddedActionBarHeight = 208.dp
private val EditorHistoryDividerWidth = 9.dp
private val EditorModeSpacing = 4.dp

/** Preserves full touch targets by moving secondary actions into narrow-window overflow. */
internal fun usesEditorActionOverflow(availableWidth: Dp, offersSave: Boolean): Boolean {
    require(availableWidth >= 0.dp) { "toolbar width must not be negative" }
    val actionCount = if (offersSave) 5 else 4
    return availableWidth <
        EditorActionSize * actionCount + EditorHistoryDividerWidth + EditorModeSpacing
}

/** Keeps frequent document actions within reach without covering document content. */
@Composable
internal fun EditorActionBar(
    session: EditorSession,
    onPreview: () -> Unit,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    shareEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val isPreview = session.presentation == EditorPresentation.MarkdownPreview
    val offersSave = !session.hasDocumentSource || session.isViewOnly
    val modeLabel = when {
        !isPreview -> stringResource(R.string.editor_read)
        session.isViewOnly -> stringResource(R.string.editor_source)
        else -> stringResource(R.string.editor_edit)
    }
    val modeDescription = if (isPreview) {
        stringResource(
            R.string.editor_show_source
        )
    } else {
        stringResource(R.string.editor_preview_markdown)
    }
    val modeIconRes = if (isPreview) R.drawable.ic_edit else R.drawable.ic_description
    val modeEnabled = if (isPreview) session.canShowTextEditor else session.canShowMarkdownPreview
    val onModeChange = if (isPreview) onEdit else onPreview

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = EditorActionBarPadding),
        contentAlignment = Alignment.Center
    ) {
        val contentPadding = FloatingToolbarDefaults.ContentPadding
        val layoutDirection = LocalLayoutDirection.current
        val controlsWidth = (
            maxWidth - contentPadding.calculateLeftPadding(layoutDirection) -
                contentPadding.calculateRightPadding(layoutDirection)
            ).coerceAtLeast(0.dp)
        val useOverflow = !isPreview && usesEditorActionOverflow(controlsWidth, offersSave)
        val showLabel =
            !useOverflow && maxWidth >= EditorActionLabelMinWidth * LocalDensity.current.fontScale
        val verticalPadding = if (maxHeight >= MinimumPaddedActionBarHeight) {
            EditorActionBarPadding
        } else {
            0.dp
        }
        HorizontalFloatingToolbar(
            expanded = true,
            modifier = Modifier.padding(vertical = verticalPadding),
            contentPadding = contentPadding,
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors()
        ) {
            if (!isPreview) {
                EditorToolButton(
                    iconRes = R.drawable.ic_undo,
                    label = stringResource(R.string.editor_undo),
                    enabled = session.canUndo,
                    onClick = { session.requestUndo() }
                )
                if (!useOverflow) {
                    EditorToolButton(
                        iconRes = R.drawable.ic_redo,
                        label = stringResource(R.string.editor_redo),
                        enabled = session.canRedo,
                        onClick = { session.requestRedo() }
                    )
                    VerticalDivider(
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .width(EditorHistoryDividerWidth)
                            .height(24.dp)
                            .padding(horizontal = 4.dp)
                    )
                }
            }
            if (showLabel) {
                FilledTonalButton(
                    onClick = onModeChange,
                    enabled = modeEnabled,
                    modifier = Modifier.semantics { contentDescription = modeDescription }
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(modeIconRes),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(modeLabel)
                    }
                }
            } else {
                EditorToolButton(
                    iconRes = modeIconRes,
                    label = modeDescription,
                    enabled = modeEnabled,
                    onClick = onModeChange,
                    emphasized = true
                )
            }
            Spacer(Modifier.width(EditorModeSpacing))
            if (offersSave) {
                EditorToolButton(
                    iconRes = R.drawable.ic_save,
                    label = if (session.isViewOnly) {
                        stringResource(
                            R.string.editor_save_editable
                        )
                    } else {
                        stringResource(R.string.editor_save_document)
                    },
                    enabled = session.canStartSaveAs,
                    onClick = onSave,
                    emphasized = true
                )
            }
            if (useOverflow) {
                EditorOverflowActions(session, shareEnabled, onShare)
            } else {
                EditorToolButton(
                    iconRes = R.drawable.ic_share,
                    label = stringResource(R.string.editor_send_export_description),
                    enabled = shareEnabled,
                    onClick = onShare
                )
            }
        }
    }
}

/** Keeps Undo, editing, and saving direct when a narrow window cannot fit all actions. */
@Composable
private fun EditorOverflowActions(
    session: EditorSession,
    shareEnabled: Boolean,
    onShare: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        EditorToolButton(
            iconRes = R.drawable.ic_more_vert,
            label = stringResource(R.string.editor_more_actions),
            enabled = true,
            onClick = { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_redo)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_redo), contentDescription = null) },
                enabled = session.canRedo,
                onClick = {
                    expanded = false
                    session.requestRedo()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_send_export)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_share), contentDescription = null) },
                enabled = shareEnabled,
                onClick = {
                    expanded = false
                    onShare()
                }
            )
        }
    }
}

/** Displays an accessible toolbar action with a tooltip and expressive press shape. */
@Composable
private fun EditorToolButton(
    @DrawableRes iconRes: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    emphasized: Boolean = false
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            TooltipAnchorPosition.Above
        ),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()
    ) {
        if (emphasized) {
            FilledTonalIconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.size(EditorActionSize)
            ) {
                Icon(painterResource(iconRes), contentDescription = label)
            }
        } else {
            IconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.size(EditorActionSize),
                shapes = IconButtonDefaults.shapes()
            ) {
                Icon(painterResource(iconRes), contentDescription = label)
            }
        }
    }
}
