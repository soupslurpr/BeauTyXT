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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.designsystem.ActionMenuItem

private val EditorActionSize = 48.dp
private val EditorActionBarPadding = 8.dp
private val EditorHistoryDividerWidth = 9.dp
private val EditorModeSpacing = 4.dp
private val EditorModeIconSize = 20.dp
private val EditorModeIconGap = 8.dp
private val EditorModeLabelSlack = 4.dp

/** Describes which direct controls fit in the bottom toolbar before spilling into overflow. */
internal data class EditorActionBarPlan(
    val showUndo: Boolean,
    val showRedo: Boolean,
    val showMode: Boolean,
    val showSave: Boolean,
    val showShare: Boolean,
    val showOverflow: Boolean
)

/** Preserves full touch targets by moving secondary actions into narrow-window overflow. */
internal fun usesEditorActionOverflow(availableWidth: Dp, offersSave: Boolean): Boolean {
    require(availableWidth >= 0.dp) { "toolbar width must not be negative" }
    val actionCount = if (offersSave) 5 else 4
    return availableWidth <
        EditorActionSize * actionCount + EditorHistoryDividerWidth + EditorModeSpacing
}

/** Computes a progressive overflow plan that keeps every direct target at least 48 dp wide. */
internal fun editorActionBarPlan(
    availableWidth: Dp,
    showsHistory: Boolean,
    offersSave: Boolean
): EditorActionBarPlan {
    require(availableWidth >= 0.dp) { "toolbar width must not be negative" }
    if (showsHistory) {
        if (!usesEditorActionOverflow(availableWidth, offersSave)) {
            return EditorActionBarPlan(
                showUndo = true,
                showRedo = true,
                showMode = true,
                showSave = offersSave,
                showShare = true,
                showOverflow = false
            )
        }
        val undoModeSaveMoreWidth =
            EditorActionSize * (if (offersSave) 4 else 3) + EditorModeSpacing
        if (availableWidth >= undoModeSaveMoreWidth) {
            return EditorActionBarPlan(
                showUndo = true,
                showRedo = false,
                showMode = true,
                showSave = offersSave,
                showShare = false,
                showOverflow = true
            )
        }
        val modeSaveMoreWidth = EditorActionSize * (if (offersSave) 3 else 2) + EditorModeSpacing
        if (availableWidth >= modeSaveMoreWidth) {
            return EditorActionBarPlan(
                showUndo = false,
                showRedo = false,
                showMode = true,
                showSave = offersSave,
                showShare = false,
                showOverflow = true
            )
        }
        val modeMoreWidth = EditorActionSize * 2 + EditorModeSpacing
        if (availableWidth >= modeMoreWidth) {
            return EditorActionBarPlan(
                showUndo = false,
                showRedo = false,
                showMode = true,
                showSave = false,
                showShare = false,
                showOverflow = true
            )
        }
        return EditorActionBarPlan(
            showUndo = false,
            showRedo = false,
            showMode = false,
            showSave = false,
            showShare = false,
            showOverflow = true
        )
    }
    val fullNonHistoryWidth = EditorActionSize * (if (offersSave) 3 else 2) + EditorModeSpacing
    if (availableWidth >= fullNonHistoryWidth) {
        return EditorActionBarPlan(
            showUndo = false,
            showRedo = false,
            showMode = true,
            showSave = offersSave,
            showShare = true,
            showOverflow = false
        )
    }
    val modeMoreWidth = EditorActionSize * 2 + EditorModeSpacing
    if (availableWidth >= modeMoreWidth) {
        return EditorActionBarPlan(
            showUndo = false,
            showRedo = false,
            showMode = true,
            showSave = false,
            showShare = false,
            showOverflow = true
        )
    }
    return EditorActionBarPlan(
        showUndo = false,
        showRedo = false,
        showMode = false,
        showSave = false,
        showShare = false,
        showOverflow = true
    )
}

/** Keeps frequent document actions within reach without covering document content. */
@Composable
internal fun EditorActionBar(
    session: EditorSession,
    availableHeight: Dp,
    onPreview: () -> Unit,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    shareEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val isPreview = session.presentation == EditorPresentation.MarkdownPreview
    val showsHistory = !isPreview && !session.isViewOnly
    val offersSave = !session.hasDocumentSource || session.isViewOnly
    val modeLabel = when {
        !isPreview -> stringResource(R.string.editor_read)
        session.isViewOnly -> stringResource(R.string.editor_source)
        else -> stringResource(R.string.editor_edit)
    }
    val modeDescription = if (isPreview) {
        stringResource(R.string.editor_show_source)
    } else {
        stringResource(R.string.editor_preview_markdown)
    }
    val modeIconRes = if (isPreview && !session.isViewOnly) {
        R.drawable.ic_edit
    } else {
        R.drawable.ic_description
    }
    val modeEnabled = if (isPreview) session.canShowTextEditor else session.canShowMarkdownPreview
    val onModeChange = if (isPreview) onEdit else onPreview
    val saveLabel = if (session.isViewOnly) {
        stringResource(R.string.editor_save_editable)
    } else {
        stringResource(R.string.editor_save_document)
    }

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge
    val measuredModeButtonWidth = remember(modeLabel, labelStyle, density, layoutDirection) {
        val textWidth = with(density) {
            textMeasurer.measure(modeLabel, style = labelStyle, maxLines = 1).size.width.toDp()
        }
        val contentPadding = ButtonDefaults.ContentPadding
        textWidth +
            EditorModeIconSize +
            EditorModeIconGap +
            contentPadding.calculateLeftPadding(layoutDirection) +
            contentPadding.calculateRightPadding(layoutDirection) +
            EditorModeLabelSlack
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = EditorActionBarPadding),
        contentAlignment = Alignment.Center
    ) {
        val heightMode = editorToolbarHeightMode(availableHeight)
        val contentPadding = FloatingToolbarDefaults.ContentPadding
        val horizontalInnerPadding = if (heightMode == EditorToolbarHeightMode.Compact) {
            EditorActionBarPadding * 2
        } else {
            contentPadding.calculateLeftPadding(layoutDirection) +
                contentPadding.calculateRightPadding(layoutDirection)
        }
        val controlsWidth = (maxWidth - horizontalInnerPadding).coerceAtLeast(0.dp)
        val plan = editorActionBarPlan(
            availableWidth = controlsWidth,
            showsHistory = showsHistory,
            offersSave = offersSave
        )
        val otherDirectWidth = when {
            plan.showOverflow -> Dp.Infinity
            showsHistory -> EditorActionSize * (if (offersSave) 4 else 3) +
                EditorHistoryDividerWidth + EditorModeSpacing
            else -> EditorActionSize * (if (offersSave) 2 else 1) + EditorModeSpacing
        }
        val showLabel = !plan.showOverflow && otherDirectWidth + measuredModeButtonWidth <= controlsWidth
        val toolbarContent: @Composable RowScope.() -> Unit = {
            if (plan.showUndo) {
                EditorToolButton(
                    iconRes = R.drawable.ic_undo,
                    label = stringResource(R.string.editor_undo),
                    enabled = session.canUndo,
                    onClick = { session.requestUndo() }
                )
            }
            if (plan.showRedo) {
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
            if (plan.showMode) {
                if (showLabel) {
                    FilledTonalButton(
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.ContentPadding,
                        onClick = onModeChange,
                        enabled = modeEnabled,
                        modifier = Modifier
                            .heightIn(min = EditorActionSize)
                            .semantics { contentDescription = modeDescription }
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painterResource(modeIconRes),
                                contentDescription = null,
                                modifier = Modifier.size(EditorModeIconSize)
                            )
                            Spacer(Modifier.width(EditorModeIconGap))
                            Text(modeLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                if (plan.showSave || plan.showShare || plan.showOverflow) {
                    Spacer(Modifier.width(EditorModeSpacing))
                }
            }
            if (plan.showSave) {
                EditorToolButton(
                    iconRes = R.drawable.ic_save,
                    label = saveLabel,
                    enabled = session.canStartSaveAs,
                    onClick = onSave
                )
            }
            if (plan.showShare) {
                EditorToolButton(
                    iconRes = R.drawable.ic_share,
                    label = stringResource(R.string.editor_send_export_description),
                    enabled = shareEnabled,
                    onClick = onShare
                )
            }
            if (plan.showOverflow) {
                EditorOverflowActions(
                    session = session,
                    plan = plan,
                    showsHistory = showsHistory,
                    offersSave = offersSave,
                    modeDescription = modeDescription,
                    modeIconRes = modeIconRes,
                    modeEnabled = modeEnabled,
                    onModeChange = onModeChange,
                    saveLabel = saveLabel,
                    onSave = onSave,
                    shareEnabled = shareEnabled,
                    onShare = onShare
                )
            }
        }
        EditorFloatingCapsule(heightMode = heightMode, content = toolbarContent)
    }
}

/** Shares the three-tier floating capsule chrome between document and selection toolbars. */
@Composable
internal fun EditorFloatingCapsule(
    heightMode: EditorToolbarHeightMode,
    content: @Composable RowScope.() -> Unit
) {
    val colors = FloatingToolbarDefaults.standardFloatingToolbarColors()
    if (heightMode == EditorToolbarHeightMode.Compact) {
        Surface(
            shape = CircleShape,
            color = colors.toolbarContainerColor,
            contentColor = colors.toolbarContentColor
        ) {
            Row(
                modifier = Modifier.padding(horizontal = EditorActionBarPadding),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }
    } else {
        val verticalPadding = if (heightMode == EditorToolbarHeightMode.Padded) {
            EditorActionBarPadding
        } else {
            0.dp
        }
        HorizontalFloatingToolbar(
            expanded = true,
            modifier = Modifier.padding(vertical = verticalPadding),
            contentPadding = FloatingToolbarDefaults.ContentPadding,
            colors = colors,
            content = content
        )
    }
}

private data class OverflowActionEntry(
    val label: String,
    @param:DrawableRes val iconRes: Int,
    val enabled: Boolean,
    val onClick: () -> Unit
)

/** Keeps displaced toolbar commands reachable when narrow windows reduce direct actions. */
@Composable
private fun EditorOverflowActions(
    session: EditorSession,
    plan: EditorActionBarPlan,
    showsHistory: Boolean,
    offersSave: Boolean,
    modeDescription: String,
    @DrawableRes modeIconRes: Int,
    modeEnabled: Boolean,
    onModeChange: () -> Unit,
    saveLabel: String,
    onSave: () -> Unit,
    shareEnabled: Boolean,
    onShare: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val items = buildList {
        if (showsHistory && !plan.showUndo) {
            add(
                OverflowActionEntry(
                    label = stringResource(R.string.editor_undo),
                    iconRes = R.drawable.ic_undo,
                    enabled = session.canUndo,
                    onClick = { session.requestUndo() }
                )
            )
        }
        if (showsHistory && !plan.showRedo) {
            add(
                OverflowActionEntry(
                    label = stringResource(R.string.editor_redo),
                    iconRes = R.drawable.ic_redo,
                    enabled = session.canRedo,
                    onClick = { session.requestRedo() }
                )
            )
        }
        if (!plan.showMode) {
            add(
                OverflowActionEntry(
                    label = modeDescription,
                    iconRes = modeIconRes,
                    enabled = modeEnabled,
                    onClick = onModeChange
                )
            )
        }
        if (offersSave && !plan.showSave) {
            add(
                OverflowActionEntry(
                    label = saveLabel,
                    iconRes = R.drawable.ic_save,
                    enabled = session.canStartSaveAs,
                    onClick = onSave
                )
            )
        }
        if (!plan.showShare) {
            add(
                OverflowActionEntry(
                    label = stringResource(R.string.editor_send_export),
                    iconRes = R.drawable.ic_share,
                    enabled = shareEnabled,
                    onClick = onShare
                )
            )
        }
    }
    Box {
        EditorToolButton(
            iconRes = R.drawable.ic_more_vert,
            label = stringResource(R.string.editor_more_actions),
            enabled = true,
            onClick = { expanded = true }
        )
        DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShape(index = 0, count = 1),
                modifier = Modifier.verticalScroll(rememberScrollState()),
                containerColor = MenuDefaults.groupStandardContainerColor
            ) {
                items.forEachIndexed { index, entry ->
                    val shape = when {
                        items.size == 1 -> MenuDefaults.leadingItemShape
                        index == 0 -> MenuDefaults.leadingItemShape
                        index == items.lastIndex -> MenuDefaults.trailingItemShape
                        else -> MenuDefaults.middleItemShape
                    }
                    ActionMenuItem(
                        shape = shape,
                        label = entry.label,
                        leadingIcon = { Icon(painterResource(entry.iconRes), contentDescription = null) },
                        enabled = entry.enabled,
                        onClick = {
                            expanded = false
                            entry.onClick()
                        }
                    )
                }
            }
        }
    }
}

/** Displays an accessible toolbar action with a tooltip and expressive press shape. */
@Composable
internal fun EditorToolButton(
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
                shapes = IconButtonDefaults.shapes(),
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
