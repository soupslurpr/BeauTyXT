@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package dev.soupslurpr.beautyxt.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.designsystem.AppMark
import dev.soupslurpr.beautyxt.ui.designsystem.BeauTyXTTheme

private val CompactSpacing = 8.dp
private val RelatedContentSpacing = 12.dp
private val HomeHorizontalPadding = 24.dp
private val HomeDocumentActionHeight = 64.dp
private val HomeStackedActionMinHeight = 64.dp
private val HomeBrandMarkSize = 80.dp
private val HomeWideVerticalPadding = 16.dp
private val HomeVerticalPadding = 24.dp
private val HomeActionSpacing = 8.dp
private val HomeContentMaxWidth = 520.dp
private val HomeWideContentMaxWidth = 960.dp
private val HomeWideLayoutMinWidth = 680.dp
private val HomeBrandActionSpacing = 40.dp
private val HomeHorizontalActionsMinWidth = 440.dp
private val OpenProgressSize = 24.dp
private val HomeActionIconSize = 24.dp
private val HomeActionHorizontalPadding = 8.dp
private val HomeActionShape = RoundedCornerShape(percent = 50)
private val HomePressedActionShape = RoundedCornerShape(20.dp)
private const val MAX_HORIZONTAL_LAYOUT_FONT_SCALE = 1.3f

/** Describes one action inside a responsive horizontal home group. */
private data class HomeAction(
    val label: String,
    @DrawableRes val iconRes: Int,
    val onClick: () -> Unit,
    val enabled: Boolean,
    val shape: Shape,
    val pressedShape: Shape,
    val containerColor: Color,
    val contentColor: Color,
    val emphasized: Boolean = false
)

/** Returns whether the leading icons and labels have enough room for one row. */
internal fun usesHorizontalHomeActions(maxWidth: Dp, fontScale: Float): Boolean {
    require(maxWidth > 0.dp) { "action width must be positive" }
    require(fontScale.isFinite() && fontScale > 0f) { "font scale must be positive and finite" }
    val requiredWidth = HomeHorizontalActionsMinWidth * maxOf(1f, fontScale)
    return maxWidth >= requiredWidth &&
        fontScale <= MAX_HORIZONTAL_LAYOUT_FONT_SCALE
}

/** Returns whether identity and actions fit beside each other without crowding text. */
internal fun usesWideHomeLayout(maxWidth: Dp, maxHeight: Dp, fontScale: Float): Boolean {
    require(maxWidth > 0.dp) { "home width must be positive" }
    require(maxHeight > 0.dp) { "home height must be positive" }
    require(fontScale.isFinite() && fontScale > 0f) { "font scale must be positive and finite" }
    return maxWidth >= HomeWideLayoutMinWidth && maxWidth > maxHeight &&
        fontScale <= MAX_HORIZONTAL_LAYOUT_FONT_SCALE
}

/** Displays the converged home hierarchy with responsive expressive behavior. */
@Composable
internal fun HomeScreen(
    openStatus: OpenStatus,
    isNewDocumentEnabled: Boolean,
    onNewDocument: () -> Unit,
    onOpenDocument: () -> Unit,
    onCancelDocumentOpen: () -> Unit,
    isCameraAvailable: Boolean,
    isScanQrEnabled: Boolean,
    onScanQr: () -> Unit,
    isNfcAvailable: Boolean,
    isReadNfcEnabled: Boolean,
    onReadNfc: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                        )
                    )
        ) {
            HomeContent(
                openStatus = openStatus,
                isNewDocumentEnabled = isNewDocumentEnabled,
                onNewDocument = onNewDocument,
                onOpenDocument = onOpenDocument,
                onCancelDocumentOpen = onCancelDocumentOpen,
                isCameraAvailable = isCameraAvailable,
                isScanQrEnabled = isScanQrEnabled,
                onScanQr = onScanQr,
                isNfcAvailable = isNfcAvailable,
                isReadNfcEnabled = isReadNfcEnabled,
                onReadNfc = onReadNfc,
                onAbout = onAbout,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** Displays the appropriate responsive arrangement around one shared action hierarchy. */
@Composable
private fun HomeContent(
    openStatus: OpenStatus,
    isNewDocumentEnabled: Boolean,
    onNewDocument: () -> Unit,
    onOpenDocument: () -> Unit,
    onCancelDocumentOpen: () -> Unit,
    isCameraAvailable: Boolean,
    isScanQrEnabled: Boolean,
    onScanQr: () -> Unit,
    isNfcAvailable: Boolean,
    isReadNfcEnabled: Boolean,
    onReadNfc: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val fontScale = LocalDensity.current.fontScale
        val actions: @Composable (Modifier) -> Unit = { actionModifier ->
            Column(
                modifier = actionModifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                HomeActionSections(
                    openStatus = openStatus,
                    isNewDocumentEnabled = isNewDocumentEnabled,
                    onNewDocument = onNewDocument,
                    onOpenDocument = onOpenDocument,
                    onCancelDocumentOpen = onCancelDocumentOpen,
                    isCameraAvailable = isCameraAvailable,
                    isScanQrEnabled = isScanQrEnabled,
                    onScanQr = onScanQr,
                    isNfcAvailable = isNfcAvailable,
                    isReadNfcEnabled = isReadNfcEnabled,
                    onReadNfc = onReadNfc,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = onAbout) { Text(stringResource(R.string.home_about)) }
            }
        }

        val viewportHeight = maxHeight
        if (usesWideHomeLayout(maxWidth, maxHeight, fontScale)) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = HomeWideContentMaxWidth)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = viewportHeight)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(
                        horizontal = HomeHorizontalPadding,
                        vertical = HomeWideVerticalPadding
                    ),
                horizontalArrangement = Arrangement.spacedBy(HomeBrandActionSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HomeBrandIdentity(
                    markSize = HomeBrandMarkSize,
                    modifier = Modifier.weight(0.8f)
                )
                HomeActionRegion(
                    modifier = Modifier.weight(1.2f),
                    actions = actions
                )
            }
        } else {
            BalancedHomeContent(viewportHeight = viewportHeight, actions = actions)
        }
    }
}

/** Balances recognizable identity with a compact expressive tool surface. */
@Composable
private fun BalancedHomeContent(viewportHeight: Dp, actions: @Composable (Modifier) -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewportHeight)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                )
                .padding(horizontal = HomeHorizontalPadding)
                .padding(top = HomeVerticalPadding, bottom = RelatedContentSpacing),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        HomeBrandIdentity(
            markSize = HomeBrandMarkSize,
            modifier =
                Modifier
                    .widthIn(max = HomeContentMaxWidth)
                    .fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(HomeBrandActionSpacing))
        HomeActionRegion(actions = actions)
    }
}

/** Bounds the action palette without enclosing it in another card. */
@Composable
private fun HomeActionRegion(
    modifier: Modifier = Modifier,
    actions: @Composable (Modifier) -> Unit
) {
    actions(modifier.widthIn(max = HomeContentMaxWidth).fillMaxWidth())
}

/** Centers the app mark above the name and purpose. */
@Composable
private fun HomeBrandIdentity(markSize: Dp, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        AppMark(size = markSize)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.home_brand_name),
                modifier = Modifier.semantics { heading() },
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.displaySmall
            )
            Text(
                text = stringResource(R.string.home_tagline),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** Displays the shared Documents and Receive hierarchy. */
@Composable
private fun HomeActionSections(
    openStatus: OpenStatus,
    isNewDocumentEnabled: Boolean,
    onNewDocument: () -> Unit,
    onOpenDocument: () -> Unit,
    onCancelDocumentOpen: () -> Unit,
    isCameraAvailable: Boolean,
    isScanQrEnabled: Boolean,
    onScanQr: () -> Unit,
    isNfcAvailable: Boolean,
    isReadNfcEnabled: Boolean,
    onReadNfc: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        DocumentActions(
            openStatus = openStatus,
            isNewDocumentEnabled = isNewDocumentEnabled,
            onNewDocument = onNewDocument,
            onOpenDocument = onOpenDocument,
            onCancelDocumentOpen = onCancelDocumentOpen
        )
        OpenFeedback(openStatus = openStatus)
        ReceiveActions(
            isCameraAvailable = isCameraAvailable,
            isScanQrEnabled = isScanQrEnabled,
            onScanQr = onScanQr,
            isNfcAvailable = isNfcAvailable,
            isReadNfcEnabled = isReadNfcEnabled,
            onReadNfc = onReadNfc
        )
    }
}

/** Displays the open and new-document actions. */
@Composable
private fun DocumentActions(
    openStatus: OpenStatus,
    isNewDocumentEnabled: Boolean,
    onNewDocument: () -> Unit,
    onOpenDocument: () -> Unit,
    onCancelDocumentOpen: () -> Unit
) {
    val openButtonEnabled =
        openStatus != OpenStatus.Selecting &&
            openStatus != OpenStatus.Cancelling
    val onOpenClick =
        if (openStatus == OpenStatus.Opening) {
            onCancelDocumentOpen
        } else {
            onOpenDocument
        }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (usesHorizontalHomeActions(maxWidth, LocalDensity.current.fontScale)) {
            HomeActionGroup(
                leadingAction =
                    HomeAction(
                        label = openDocumentActionLabel(openStatus).asString(),
                        iconRes = R.drawable.ic_file_open,
                        onClick = onOpenClick,
                        enabled = openButtonEnabled,
                        shape = HomeActionShape,
                        pressedShape = HomePressedActionShape,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        emphasized = true
                    ),
                trailingAction =
                    HomeAction(
                        label = stringResource(R.string.home_new_document),
                        iconRes = R.drawable.ic_note_add,
                        onClick = onNewDocument,
                        enabled = isNewDocumentEnabled,
                        shape = HomeActionShape,
                        pressedShape = HomePressedActionShape,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(CompactSpacing)
            ) {
                HomeActionButton(
                    label = openDocumentActionLabel(openStatus).asString(),
                    iconRes = R.drawable.ic_file_open,
                    onClick = onOpenClick,
                    enabled = openButtonEnabled,
                    shape = HomeActionShape,
                    pressedShape = HomePressedActionShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    emphasized = true,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = HomeStackedActionMinHeight)
                )
                HomeActionButton(
                    label = stringResource(R.string.home_new_document),
                    iconRes = R.drawable.ic_note_add,
                    onClick = onNewDocument,
                    enabled = isNewDocumentEnabled,
                    shape = HomeActionShape,
                    pressedShape = HomePressedActionShape,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = HomeStackedActionMinHeight)
                )
            }
        }
    }
}

/** Groups direct receive actions in one quiet surface with hardware availability. */
@Composable
private fun ReceiveActions(
    isCameraAvailable: Boolean,
    isScanQrEnabled: Boolean,
    onScanQr: () -> Unit,
    isNfcAvailable: Boolean,
    isReadNfcEnabled: Boolean,
    onReadNfc: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(modifier = Modifier.padding(CompactSpacing)) {
            Text(
                text = stringResource(R.string.home_receive_text),
                modifier = Modifier
                    .padding(horizontal = RelatedContentSpacing, vertical = CompactSpacing)
                    .semantics { heading() },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium
            )
            ReceiveAction(
                label = stringResource(R.string.action_scan_qr),
                iconRes = R.drawable.ic_qr_code_2,
                onClick = onScanQr,
                enabled = isCameraAvailable && isScanQrEnabled,
                supportingText = if (isCameraAvailable) {
                    null
                } else {
                    stringResource(R.string.home_camera_unavailable)
                }
            )
            ReceiveAction(
                label = stringResource(R.string.action_read_nfc),
                iconRes = R.drawable.ic_nfc,
                onClick = onReadNfc,
                enabled = isNfcAvailable && isReadNfcEnabled,
                supportingText = if (isNfcAvailable) {
                    null
                } else {
                    stringResource(R.string.home_nfc_unavailable)
                }
            )
        }
    }
}

/** Keeps an unavailable action's explanation readable and part of its accessibility label. */
@Composable
private fun ReceiveAction(
    label: String,
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
    enabled: Boolean,
    supportingText: String? = null
) {
    val actionColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().semantics { role = Role.Button },
        color = Color.Transparent,
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(RelatedContentSpacing),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(HomeActionIconSize),
                tint = if (enabled) MaterialTheme.colorScheme.primary else actionColor
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = label,
                    color = actionColor,
                    style = MaterialTheme.typography.labelLarge
                )
                if (supportingText != null) {
                    Text(
                        text = supportingText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/** Displays two actions whose widths and shapes respond as one expressive group. */
@Composable
private fun HomeActionGroup(
    leadingAction: HomeAction,
    trailingAction: HomeAction
) {
    val leadingInteractionSource = remember { MutableInteractionSource() }
    val trailingInteractionSource = remember { MutableInteractionSource() }

    ButtonGroup(
        overflowIndicator = { menuState ->
            ButtonGroupDefaults.OverflowIndicator(menuState = menuState)
        },
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(HomeActionSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        homeActionItem(leadingAction, leadingInteractionSource)
        homeActionItem(trailingAction, trailingInteractionSource)
    }
}

/** Adds one custom BeauTyXT action to a Material expressive button group. */
private fun ButtonGroupScope.homeActionItem(
    action: HomeAction,
    interactionSource: MutableInteractionSource
) {
    customItem(
        buttonGroupContent = {
            HomeActionButton(
                label = action.label,
                iconRes = action.iconRes,
                onClick = action.onClick,
                enabled = action.enabled,
                shape = action.shape,
                pressedShape = action.pressedShape,
                containerColor = action.containerColor,
                contentColor = action.contentColor,
                emphasized = action.emphasized,
                interactionSource = interactionSource,
                modifier =
                    Modifier
                        .weight(1f)
                        .animateWidth(
                            interactionSource = interactionSource,
                            compressionLimit = HomeActionHorizontalPadding
                        )
                        .height(HomeDocumentActionHeight)
            )
        },
        menuContent = { menuState ->
            DropdownMenuItem(
                text = { Text(action.label) },
                onClick = {
                    menuState.dismiss()
                    action.onClick()
                },
                leadingIcon = {
                    Icon(painter = painterResource(action.iconRes), contentDescription = null)
                },
                enabled = action.enabled,
                interactionSource = interactionSource
            )
        }
    )
}

/** Displays one expressive home action with a restrained pressed-state morph. */
@Composable
private fun HomeActionButton(
    label: String,
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
    enabled: Boolean,
    shape: Shape,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    pressedShape: Shape = ButtonDefaults.pressedShape,
    emphasized: Boolean = false,
    interactionSource: MutableInteractionSource? = null
) {
    Button(
        onClick = onClick,
        shapes =
            ButtonDefaults.shapes(
                shape = shape,
                pressedShape = pressedShape
            ),
        modifier = modifier,
        enabled = enabled,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
        contentPadding =
            PaddingValues(horizontal = HomeActionHorizontalPadding, vertical = 12.dp),
        interactionSource = interactionSource
    ) {
        val actionIcon: @Composable () -> Unit = {
            Icon(
                painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(HomeActionIconSize)
            )
        }
        val actionLabel: @Composable () -> Unit = {
            Text(
                text = label,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Medium,
                style = ButtonDefaults.textStyleFor(HomeDocumentActionHeight)
            )
        }
        actionIcon()
        Spacer(modifier = Modifier.width(RelatedContentSpacing))
        actionLabel()
    }
}

/** Displays bounded progress or one sanitized open failure. */
@Composable
private fun OpenFeedback(openStatus: OpenStatus, modifier: Modifier = Modifier) {
    val message =
        when (openStatus) {
            OpenStatus.Idle,
            OpenStatus.Selecting -> return

            OpenStatus.Opening -> stringResource(R.string.home_opening_file)

            OpenStatus.Cancelling -> stringResource(R.string.home_cancelling_open)

            is OpenStatus.Failed -> openStatus.message.asString()
        }
    val isFailure = openStatus is OpenStatus.Failed

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    liveRegion =
                        if (isFailure) {
                            LiveRegionMode.Assertive
                        } else {
                            LiveRegionMode.Polite
                        }
                },
        color =
            if (isFailure) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        contentColor =
            if (isFailure) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(RelatedContentSpacing),
            horizontalArrangement = Arrangement.spacedBy(RelatedContentSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isFailure) {
                CircularProgressIndicator(modifier = Modifier.size(OpenProgressSize))
            }
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Returns one context-sensitive label for the source-backed home action. */
internal fun openDocumentActionLabel(openStatus: OpenStatus): UiText = when (openStatus) {
    OpenStatus.Selecting -> UiText.Resource(R.string.home_selecting_file)

    OpenStatus.Opening -> UiText.Resource(R.string.home_cancel_open)

    OpenStatus.Cancelling -> UiText.Resource(R.string.home_cancelling)

    OpenStatus.Idle,
    is OpenStatus.Failed -> UiText.Resource(R.string.home_open_file)
}

/** Previews the standard phone landing screen. */
@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    BeauTyXTTheme {
        HomeScreen(
            openStatus = OpenStatus.Idle,
            isNewDocumentEnabled = true,
            onNewDocument = {},
            onOpenDocument = {},
            onCancelDocumentOpen = {},
            isCameraAvailable = true,
            isScanQrEnabled = true,
            onScanQr = {},
            isNfcAvailable = true,
            isReadNfcEnabled = true,
            onReadNfc = {},
            onAbout = {}
        )
    }
}

/** Previews the wide landing layout with active feedback. */
@Preview(
    name = "Wide landscape",
    showBackground = true,
    widthDp = 840,
    heightDp = 360
)
@Composable
private fun WideHomeScreenPreview() {
    BeauTyXTTheme {
        HomeScreen(
            openStatus = OpenStatus.Opening,
            isNewDocumentEnabled = false,
            onNewDocument = {},
            onOpenDocument = {},
            onCancelDocumentOpen = {},
            isCameraAvailable = true,
            isScanQrEnabled = false,
            onScanQr = {},
            isNfcAvailable = true,
            isReadNfcEnabled = false,
            onReadNfc = {},
            onAbout = {}
        )
    }
}

/** Previews large text with both receive hardware explanations. */
@Preview(
    name = "Large font",
    showBackground = true,
    widthDp = 360,
    heightDp = 640,
    fontScale = 2f
)
@Composable
private fun LargeFontHomeScreenPreview() {
    BeauTyXTTheme {
        HomeScreen(
            openStatus = OpenStatus.Failed(UiText.Resource(R.string.import_open_failed)),
            isNewDocumentEnabled = true,
            onNewDocument = {},
            onOpenDocument = {},
            onCancelDocumentOpen = {},
            isCameraAvailable = false,
            isScanQrEnabled = false,
            onScanQr = {},
            isNfcAvailable = false,
            isReadNfcEnabled = false,
            onReadNfc = {},
            onAbout = {}
        )
    }
}
