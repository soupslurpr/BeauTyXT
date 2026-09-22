@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package dev.soupslurpr.beautyxt.ui

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
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
private val HomeActionHeight = 64.dp
private val HomeDocumentActionHeight = 120.dp
private val HomeStackedActionMinHeight = 60.dp
private val HomeBrandMarkSize = 80.dp
private val HomeWideVerticalPadding = 48.dp
private val HomeVerticalPadding = 24.dp
private val HomeConnectedActionSpacing = 3.dp
private val HomeContentMaxWidth = 520.dp
private val HomeWideContentMaxWidth = 960.dp
private val HomeWideLayoutMinWidth = 680.dp
private val HomeBrandActionSpacing = 40.dp
private val HomeWideActionTopInset = 16.dp
private val HomeHorizontalActionsMinWidth = 320.dp
private val OpenProgressSize = 24.dp
private val HomeActionIconSize = 24.dp
private val HomeActionHorizontalPadding = 8.dp
private val HomeLeadingActionShape =
    RoundedCornerShape(topStart = 34.dp, bottomStart = 34.dp, topEnd = 10.dp, bottomEnd = 10.dp)
private val HomeTrailingActionShape =
    RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp, topEnd = 34.dp, bottomEnd = 34.dp)
private val HomePressedLeadingActionShape =
    RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 12.dp, bottomEnd = 12.dp)
private val HomePressedTrailingActionShape =
    RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp, topEnd = 24.dp, bottomEnd = 24.dp)
private val HomeStandaloneActionShape = RoundedCornerShape(26.dp)
private val HomePressedStandaloneActionShape = RoundedCornerShape(20.dp)
private const val MAX_HORIZONTAL_LAYOUT_FONT_SCALE = 1.3f

/** Describes one action inside a coordinated horizontal home group. */
private data class HomeConnectedAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val enabled: Boolean,
    val shape: Shape,
    val pressedShape: Shape,
    val containerColor: Color,
    val contentColor: Color,
    val emphasized: Boolean = false,
    val verticalContent: Boolean = false
)

/** Returns whether one action family has enough room for a connected row. */
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
    isScanQrEnabled: Boolean,
    onScanQr: () -> Unit,
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
                isScanQrEnabled = isScanQrEnabled,
                onScanQr = onScanQr,
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
    isScanQrEnabled: Boolean,
    onScanQr: () -> Unit,
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
                    isScanQrEnabled = isScanQrEnabled,
                    onScanQr = onScanQr,
                    isReadNfcEnabled = isReadNfcEnabled,
                    onReadNfc = onReadNfc,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = onAbout) { Text(stringResource(R.string.about_title)) }
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
                    modifier = Modifier.weight(1.2f).padding(top = HomeWideActionTopInset),
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
    isScanQrEnabled: Boolean,
    onScanQr: () -> Unit,
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
            isScanQrEnabled = isScanQrEnabled,
            onScanQr = onScanQr,
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
            HomeConnectedActionGroup(
                leadingAction =
                    HomeConnectedAction(
                        label = openDocumentActionLabel(openStatus).asString(),
                        icon = HomeOpenIcon,
                        onClick = onOpenClick,
                        enabled = openButtonEnabled,
                        shape = HomeLeadingActionShape,
                        pressedShape = HomePressedLeadingActionShape,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        emphasized = true,
                        verticalContent = true
                    ),
                trailingAction =
                    HomeConnectedAction(
                        label = stringResource(R.string.home_new_document),
                        icon = HomeNewIcon,
                        onClick = onNewDocument,
                        enabled = isNewDocumentEnabled,
                        shape = HomeTrailingActionShape,
                        pressedShape = HomePressedTrailingActionShape,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        verticalContent = true
                    )
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(CompactSpacing)
            ) {
                HomeActionButton(
                    label = openDocumentActionLabel(openStatus).asString(),
                    icon = HomeOpenIcon,
                    onClick = onOpenClick,
                    enabled = openButtonEnabled,
                    shape = HomeStandaloneActionShape,
                    pressedShape = HomePressedStandaloneActionShape,
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
                    icon = HomeNewIcon,
                    onClick = onNewDocument,
                    enabled = isNewDocumentEnabled,
                    shape = HomeStandaloneActionShape,
                    pressedShape = HomePressedStandaloneActionShape,
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

/** Displays local QR and NFC receive actions. */
@Composable
private fun ReceiveActions(
    isScanQrEnabled: Boolean,
    onScanQr: () -> Unit,
    isReadNfcEnabled: Boolean,
    onReadNfc: () -> Unit
) {
    HomeSection(title = stringResource(R.string.home_receive_text)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (usesHorizontalHomeActions(maxWidth, LocalDensity.current.fontScale)) {
                HomeConnectedActionGroup(
                    leadingAction =
                        HomeConnectedAction(
                            label = stringResource(R.string.action_scan_qr),
                            icon = HomeQrIcon,
                            onClick = onScanQr,
                            enabled = isScanQrEnabled,
                            shape = HomeLeadingActionShape,
                            pressedShape = HomePressedLeadingActionShape,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                    trailingAction =
                        HomeConnectedAction(
                            label = stringResource(R.string.action_read_nfc),
                            icon = HomeNfcIcon,
                            onClick = onReadNfc,
                            enabled = isReadNfcEnabled,
                            shape = HomeTrailingActionShape,
                            pressedShape = HomePressedTrailingActionShape,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(CompactSpacing)
                ) {
                    HomeActionButton(
                        label = stringResource(R.string.action_scan_qr),
                        icon = HomeQrIcon,
                        onClick = onScanQr,
                        enabled = isScanQrEnabled,
                        shape = HomeStandaloneActionShape,
                        pressedShape = HomePressedStandaloneActionShape,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = HomeStackedActionMinHeight)
                    )
                    HomeActionButton(
                        label = stringResource(R.string.action_read_nfc),
                        icon = HomeNfcIcon,
                        onClick = onReadNfc,
                        enabled = isReadNfcEnabled,
                        shape = HomeStandaloneActionShape,
                        pressedShape = HomePressedStandaloneActionShape,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = HomeStackedActionMinHeight)
                    )
                }
            }
        }
    }
}

/** Displays two actions whose widths and shapes respond as one expressive group. */
@Composable
private fun HomeConnectedActionGroup(
    leadingAction: HomeConnectedAction,
    trailingAction: HomeConnectedAction
) {
    val leadingInteractionSource = remember { MutableInteractionSource() }
    val trailingInteractionSource = remember { MutableInteractionSource() }

    ButtonGroup(
        overflowIndicator = { menuState ->
            ButtonGroupDefaults.OverflowIndicator(menuState = menuState)
        },
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(HomeConnectedActionSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        homeConnectedActionItem(leadingAction, leadingInteractionSource)
        homeConnectedActionItem(trailingAction, trailingInteractionSource)
    }
}

/** Adds one custom BeauTyXT action to a Material expressive button group. */
private fun ButtonGroupScope.homeConnectedActionItem(
    action: HomeConnectedAction,
    interactionSource: MutableInteractionSource
) {
    customItem(
        buttonGroupContent = {
            HomeActionButton(
                label = action.label,
                icon = action.icon,
                onClick = action.onClick,
                enabled = action.enabled,
                shape = action.shape,
                pressedShape = action.pressedShape,
                containerColor = action.containerColor,
                contentColor = action.contentColor,
                emphasized = action.emphasized,
                verticalContent = action.verticalContent,
                interactionSource = interactionSource,
                modifier =
                    Modifier
                        .weight(1f)
                        .animateWidth(
                            interactionSource = interactionSource,
                            compressionLimit = HomeActionHorizontalPadding
                        )
                        .height(
                            if (action.verticalContent) {
                                HomeDocumentActionHeight
                            } else {
                                HomeActionHeight
                            }
                        )
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
                    Icon(imageVector = action.icon, contentDescription = null)
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
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    shape: Shape,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    pressedShape: Shape = ButtonDefaults.pressedShape,
    emphasized: Boolean = false,
    verticalContent: Boolean = false,
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
            Icon(icon, contentDescription = null, modifier = Modifier.size(HomeActionIconSize))
        }
        val actionLabel: @Composable () -> Unit = {
            Text(
                text = label,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Medium,
                style = if (verticalContent) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.labelLarge
                }
            )
        }
        if (verticalContent) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(RelatedContentSpacing)
            ) {
                actionIcon()
                actionLabel()
            }
        } else {
            actionIcon()
            Spacer(modifier = Modifier.width(RelatedContentSpacing))
            actionLabel()
        }
    }
}

/** Displays one consistently styled group of home actions. */
@Composable
private fun HomeSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(CompactSpacing)) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleSmall
        )
        content()
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
            isScanQrEnabled = true,
            onScanQr = {},
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
            isScanQrEnabled = false,
            onScanQr = {},
            isReadNfcEnabled = true,
            onReadNfc = {},
            onAbout = {}
        )
    }
}

/** Previews the landing layout at a large font scale. */
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
            isScanQrEnabled = true,
            onScanQr = {},
            isReadNfcEnabled = false,
            onReadNfc = {},
            onAbout = {}
        )
    }
}
