/* Presents live, app-wide appearance controls beside or above the document. */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.ui.designsystem.SelectionMenuItem
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceButtons
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceOption
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale
import kotlin.math.roundToInt

/** Transient editor state survives both Activity recreation and sheet/pane relocation. */
internal class TextAppearancePanelState {
    var confirmSavedReset by mutableStateOf(false)
    var section by mutableIntStateOf(0)
    val listState = LazyListState()
    var numberDraft by mutableStateOf<AppearanceNumberDraft?>(null)
    var sampleExpanded by mutableStateOf<Boolean?>(null)
}

internal class AppearanceNumberDraft(val group: AppearanceGroup, val option: AppearanceOption,
    value: Float, val isCustom: Boolean) {
    var text by mutableStateOf(appearanceNumber(value, exact = isCustom).let {
        TextFieldValue(it, TextRange(0, it.length))
    })
        private set
    var edited by mutableStateOf(false)
        private set
    val parsed: Float? get() = parseAppearanceNumber(text.text)?.takeIf(option::valid)

    fun updateText(value: TextFieldValue) {
        if (value.text != text.text) edited = true
        text = value
    }

    fun apply(onChange: (Float) -> Unit): Boolean {
        val value = parsed ?: return false
        if (edited) onChange(value)
        return true
    }
}

internal fun partiallyExpandedBottomPaddingPx(
    settledPartial: Boolean,
    contentBottomPx: Float,
    windowHeightPx: Int,
    bottomInsetPx: Int
): Int = if (!settledPartial) 0 else (contentBottomPx - (windowHeightPx - bottomInsetPx)).roundToInt().coerceAtLeast(0)

@Composable
internal fun TextAppearanceSheet(
    panelState: TextAppearancePanelState,
    group: AppearanceGroup,
    onGroupChange: (AppearanceGroup) -> Unit,
    store: TextAppearanceStore,
    state: TextAppearanceStoreState,
    onDismiss: () -> Unit,
    onFind: (Boolean) -> Boolean
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded)
    )
    var offscreenBottomPx by remember { mutableIntStateOf(0) }
    val settledPartial = sheetState.currentValue == SheetValue.PartiallyExpanded &&
        sheetState.targetValue == SheetValue.PartiallyExpanded
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.documentFindShortcuts(onFind),
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        val density = LocalDensity.current
        val windowHeightPx = LocalWindowInfo.current.containerSize.height
        val bottomInsetPx = WindowInsets.safeDrawing.getBottom(density)
        val extraBottomPadding = if (settledPartial) with(density) { offscreenBottomPx.toDp() } else 0.dp
        TextAppearancePanel(
            panelState,
            group,
            onGroupChange,
            store,
            state,
            onDismiss,
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(.92f)
                .documentToolFocus(panelState)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
                .onGloballyPositioned { coordinates ->
                    val bottom = coordinates.positionInWindow().y + coordinates.size.height
                    offscreenBottomPx = partiallyExpandedBottomPaddingPx(
                        settledPartial = true,
                        contentBottomPx = bottom,
                        windowHeightPx = windowHeightPx,
                        bottomInsetPx = bottomInsetPx
                    )
                },
            showSample = sheetState.currentValue == SheetValue.Expanded,
            extraBottomPadding = extraBottomPadding
        )
    }
}

@Composable
internal fun TextAppearancePanel(
    panelState: TextAppearancePanelState,
    group: AppearanceGroup,
    onGroupChange: (AppearanceGroup) -> Unit,
    store: TextAppearanceStore,
    state: TextAppearanceStoreState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    showSample: Boolean = false,
    extraBottomPadding: Dp = 0.dp
) {
    val ready = state as? TextAppearanceStoreState.Ready
    val appearance = ready?.preferences?.get(group) ?: TextAppearance()
    val change: (AppearanceOption, Float?) -> Unit = { option, value -> store.updateOption(group, option, value) }
    Surface(modifier.semantics {
        testTagsAsResourceId = true
        testTag = "appearance_panel"
    }, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BoxWithConstraints {
            val sampleExpanded = panelState.sampleExpanded ?: (maxHeight >= 600.dp && LocalDensity.current.fontScale < 1.5f)
            val sampleVisible = showSample && ready != null && maxHeight >= 360.dp
            val sampleHeight = (maxHeight * .22f).coerceIn(64.dp, 160.dp)
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.appearance_title), Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_close))
                    }
                }
                if (sampleVisible) AppearanceSample(group, appearance, sampleExpanded,
                    onExpandedChange = { panelState.sampleExpanded = it }, height = sampleHeight,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
                LazyColumn(Modifier.weight(1f).appearanceScrollActions(panelState.listState)
                    .semantics { testTag = "appearance_controls" },
                    state = panelState.listState, contentPadding = PaddingValues(24.dp, 8.dp, 24.dp, 24.dp + extraBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state == TextAppearanceStoreState.LoadFailed) {
                        item {
                            Text(stringResource(R.string.appearance_load_failed), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = store::retryLoad) { Text(stringResource(R.string.appearance_retry_load)) }
                            TextButton(onClick = { panelState.confirmSavedReset = true }) {
                                Text(stringResource(R.string.appearance_reset_saved_action))
                            }
                        }
                        return@LazyColumn
                    }
                    if (ready == null) {
                        item { Text(stringResource(R.string.appearance_loading)) }
                        return@LazyColumn
                    }
                    item {
                        AppearanceGroupChoice(group, onGroupChange)
                        Text(stringResource(R.string.appearance_scope), Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (ready.saveFailed) item {
                        Text(stringResource(R.string.appearance_save_failed), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = store::retrySave) { Text(stringResource(R.string.appearance_retry)) }
                    }
                    item {
                        SingleChoiceButtons(listOf(R.string.appearance_text, R.string.appearance_spacing, R.string.appearance_layout)
                            .mapIndexed { index, title -> SingleChoiceOption(stringResource(title), panelState.section == index, { panelState.section = index }) })
                    }
                    when (panelState.section) {
                        0 -> {
                            item { AppearanceFontControl(AppearanceOption.Font, group, appearance, change) }
                            item { AppearanceNumberControl(panelState, AppearanceOption.Size, group, appearance, change,
                                help = stringResource(R.string.appearance_system_size)) }
                            item { AppearanceNumberControl(panelState, AppearanceOption.Weight, group, appearance, change) }
                            if (group == AppearanceGroup.Markdown) {
                                item { AppearanceSectionHeading(R.string.appearance_headings) }
                                item { AppearanceFontControl(AppearanceOption.HeadingFont, group, appearance, change) }
                                item { AppearanceNumberControl(panelState, AppearanceOption.HeadingScale, group, appearance, change,
                                    help = stringResource(R.string.appearance_heading_scale_help)) }
                                item { AppearanceSectionHeading(R.string.appearance_code) }
                                item { AppearanceFontControl(AppearanceOption.CodeFont, group, appearance, change) }
                                item { AppearanceNumberControl(panelState, AppearanceOption.CodeScale, group, appearance, change,
                                    help = stringResource(R.string.appearance_code_scale_help)) }
                            }
                        }
                        1 -> {
                            item { AppearanceNumberControl(panelState, AppearanceOption.LineSpacing, group, appearance, change) }
                            item { AppearanceNumberControl(panelState, AppearanceOption.LetterSpacing, group, appearance, change) }
                            item { AppearanceNumberControl(panelState, AppearanceOption.WordSpacing, group, appearance, change) }
                            if (group == AppearanceGroup.Markdown) item {
                                AppearanceNumberControl(panelState, AppearanceOption.ParagraphSpacing, group, appearance, change)
                            }
                            item {
                                Text(stringResource(R.string.appearance_extra_spacing_help),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        else -> {
                            item { AppearanceSwitchControl(AppearanceOption.FullWidth, group, appearance, change) }
                            if (appearance.value(AppearanceOption.FullWidth, group) == 0f) item {
                                AppearanceNumberControl(panelState, AppearanceOption.ColumnWidth, group, appearance, change,
                                    help = stringResource(R.string.appearance_width_help))
                            }
                            item { AppearanceNumberControl(panelState, AppearanceOption.Margin, group, appearance, change,
                                help = stringResource(R.string.appearance_margin_help)) }
                            if (group == AppearanceGroup.Source) {
                                item { AppearanceSwitchControl(AppearanceOption.Wrap, group, appearance, change) }
                                item { AppearanceNumberControl(panelState, AppearanceOption.TabWidth, group, appearance, change,
                                    help = stringResource(R.string.appearance_tabs_help)) }
                            }
                        }
                    }
                    item {
                        HorizontalDivider()
                        TextButton(onClick = { store.resetGroup(group) },
                            enabled = appearance.custom.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(stringResource(R.string.appearance_reset_group))
                        }
                        Text(stringResource(R.string.appearance_default_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceSectionHeading(title: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HorizontalDivider()
        Text(stringResource(title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun AppearanceGroupChoice(group: AppearanceGroup, onChange: (AppearanceGroup) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(onClick = { expanded = true }, shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(group.title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(painterResource(R.drawable.ic_keyboard_arrow_down), stringResource(R.string.appearance_choose_group))
            }
        }
        DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1), containerColor = MenuDefaults.groupStandardContainerColor) {
                AppearanceGroup.entries.forEachIndexed { index, choice ->
                    SelectionMenuItem(selected = group == choice,
                        label = stringResource(choice.title),
                        shapes = MenuDefaults.itemShape(index, AppearanceGroup.entries.size),
                        selectedLeadingIcon = { Icon(painterResource(R.drawable.ic_check), null) },
                        onClick = { expanded = false; onChange(choice) })
                }
            }
        }
    }
}

/** Measures its content identically whether shown or hidden so Default/Custom toggles never shift layout. */
@Composable
private fun ReservedSlot(visible: Boolean, content: @Composable () -> Unit) {
    Layout(
        content = content,
        modifier = if (visible) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() }
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        val height = placeables.maxOfOrNull { it.height } ?: constraints.minHeight
        layout(width, height) {
            if (visible) placeables.forEach { it.placeRelative(0, 0) }
        }
    }
}

@Composable
private fun AppearanceControlHeading(option: AppearanceOption, custom: Boolean, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(option.title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(if (custom) R.string.appearance_custom else R.string.appearance_default),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ReservedSlot(visible = custom) { AppearanceResetButton(option, enabled = custom, onReset = onReset) }
    }
}

@Composable
private fun AppearanceResetButton(option: AppearanceOption, enabled: Boolean = true, onReset: () -> Unit) {
    val label = stringResource(R.string.appearance_reset_setting, stringResource(option.title))
    TextButton(onClick = onReset, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) {
        Text(stringResource(R.string.appearance_reset))
    }
}

@Composable
private fun AppearanceFontControl(option: AppearanceOption, group: AppearanceGroup, appearance: TextAppearance,
    onChange: (AppearanceOption, Float?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val labels = listOf(R.string.appearance_font_system, R.string.appearance_font_serif, R.string.appearance_font_mono)
    val title = stringResource(option.title)
    val selectedFont = stringResource(labels[appearance.value(option, group).toInt()])
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppearanceControlHeading(option, option in appearance.custom) { onChange(option, null) }
        Box {
            Surface(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = title
                stateDescription = selectedFont
            }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Row(Modifier.clearAndSetSemantics {}.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Aa", fontFamily = appearanceFont(appearance.value(option, group)), style = MaterialTheme.typography.titleLarge)
                    Text(selectedFont, Modifier.weight(1f))
                    Icon(painterResource(R.drawable.ic_keyboard_arrow_down), null)
                }
            }
            DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1), containerColor = MenuDefaults.groupStandardContainerColor) {
                    labels.forEachIndexed { index, label ->
                        SelectionMenuItem(selected = appearance.value(option, group) == index.toFloat(),
                            label = stringResource(label),
                            shapes = MenuDefaults.itemShape(index, labels.size),
                            leadingIcon = { Text("Aa", Modifier.clearAndSetSemantics {},
                                fontFamily = appearanceFont(index.toFloat())) },
                            selectedLeadingIcon = { Text("Aa", Modifier.clearAndSetSemantics {},
                                fontFamily = appearanceFont(index.toFloat())) },
                            trailingContent = {
                                if (appearance.value(option, group) == index.toFloat())
                                    Icon(painterResource(R.drawable.ic_check), null)
                            },
                            onClick = { expanded = false; onChange(option, index.toFloat()) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceSwitchControl(option: AppearanceOption, group: AppearanceGroup, appearance: TextAppearance,
    onChange: (AppearanceOption, Float?) -> Unit) {
    val checked = appearance.value(option, group) == 1f
    val custom = option in appearance.custom
    val title = stringResource(option.title)
    val status = stringResource(if (custom) R.string.appearance_custom else R.string.appearance_default)
    val description = stringResource(R.string.appearance_switch_description, title, status)
    Column {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch,
                onValueChange = { onChange(option, if (it) 1f else 0f) })
                .semantics { contentDescription = description }
                .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, Modifier.weight(1f).clearAndSetSemantics {}, style = MaterialTheme.typography.titleSmall)
                Switch(checked = checked, onCheckedChange = null)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(status, Modifier.weight(1f).clearAndSetSemantics { hideFromAccessibility() },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ReservedSlot(visible = custom) {
                AppearanceResetButton(option, enabled = custom) { onChange(option, null) }
            }
        }
    }
}

internal fun appearanceNumber(value: Float, exact: Boolean = false): String {
    val format = NumberFormat.getNumberInstance(Locale.getDefault()).apply { isGroupingUsed = false }
    if (exact && value != 0f) {
        // Localize the float's shortest round-trippable decimal without widening it to a double.
        val decimal = value.toString().toBigDecimal()
        format.maximumFractionDigits = decimal.scale().coerceAtLeast(0)
        return format.format(decimal)
    }
    format.maximumFractionDigits = 2
    return format.format(value)
}

internal fun parseAppearanceNumber(text: String): Float? {
    val input = text.trim()
    val position = ParsePosition(0)
    val number = NumberFormat.getNumberInstance(Locale.getDefault()).apply { isGroupingUsed = false }
        .parse(input, position)
    return number?.toFloat()?.takeIf { position.index == input.length && it.isFinite() }
}

@Composable
private fun appearanceValue(option: AppearanceOption, value: Float, includeReference: Boolean = true): String {
    if (option == AppearanceOption.TabWidth) {
        val count = value.toInt()
        return pluralStringResource(R.plurals.appearance_value_spaces, count, count)
    }
    return stringResource(when (option) {
        AppearanceOption.Size -> R.string.appearance_value_sp
        AppearanceOption.ColumnWidth, AppearanceOption.Margin -> R.string.appearance_value_dp
        AppearanceOption.LineSpacing, AppearanceOption.ParagraphSpacing ->
            if (includeReference) R.string.appearance_value_times_text_size else R.string.appearance_value_times
        AppearanceOption.LetterSpacing, AppearanceOption.WordSpacing ->
            if (includeReference) R.string.appearance_value_percent_text_size else R.string.appearance_value_percent
        AppearanceOption.HeadingScale, AppearanceOption.CodeScale -> R.string.appearance_value_percent
        else -> R.string.appearance_value_number
    }, appearanceNumber(value))
}

@Composable
private fun AppearanceNumberControl(panelState: TextAppearancePanelState, option: AppearanceOption, group: AppearanceGroup, appearance: TextAppearance,
    onChange: (AppearanceOption, Float?) -> Unit, help: String? = null) {
    val value = appearance.value(option, group)
    val maximum = option.maximum
    val title = stringResource(option.title)
    val formatted = appearanceValue(option, value)
    val reference = when (option) {
        AppearanceOption.LineSpacing, AppearanceOption.ParagraphSpacing -> R.string.appearance_reference_text_size
        AppearanceOption.LetterSpacing, AppearanceOption.WordSpacing -> R.string.appearance_reference_of_text_size
        else -> null
    }
    val defaultValue = appearanceValue(option, option.default(group))
    Column {
        AppearanceControlHeading(option, option in appearance.custom) { onChange(option, null) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            val decrease = stringResource(R.string.appearance_decrease, title)
            IconButton(onClick = { onChange(option, option.nextStep(value, -1)) }, enabled = value > option.minimum) {
                Icon(painterResource(R.drawable.ic_remove), decrease)
            }
            val edit = stringResource(R.string.appearance_edit_setting, title)
            TextButton(onClick = { panelState.numberDraft = AppearanceNumberDraft(group, option, value, option in appearance.custom) }, modifier = Modifier.weight(1f).semantics {
                contentDescription = edit
                stateDescription = formatted
            }) {
                Column(Modifier.weight(1f, fill = false).clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(appearanceValue(option, value, includeReference = false),
                        style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    if (reference != null) Text(stringResource(reference),
                        style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                }
                Icon(painterResource(R.drawable.ic_edit), null, Modifier.padding(start = 8.dp).size(18.dp))
            }
            val increase = stringResource(R.string.appearance_increase, title)
            IconButton(onClick = { onChange(option, option.nextStep(value, 1)) }, enabled = value < maximum) {
                Icon(painterResource(R.drawable.ic_add), increase)
            }
        }
        val slider = remember(option, maximum) { SliderState(value,
            option.intervals - 1, option.minimum..maximum) }
        slider.value = value
        val markerColor = MaterialTheme.colorScheme.onSurfaceVariant
        val defaultFraction = (option.default(group) - option.minimum) / (maximum - option.minimum)
        Slider(state = slider, onValueChange = { onChange(option, option.stepped(it)) },
            track = { state ->
                SliderDefaults.Track(sliderState = state, drawTick = { offset, color ->
                    if (state.steps <= 10) drawCircle(color, 2.dp.toPx(), offset)
                }, modifier = Modifier.drawWithContent {
                    drawContent()
                    val fraction = if (layoutDirection == LayoutDirection.Rtl) 1f - defaultFraction else defaultFraction
                    val x = size.height / 2 + fraction * (size.width - size.height)
                    drawLine(markerColor, Offset(x, size.height + 2.dp.toPx()),
                        Offset(x, size.height + 6.dp.toPx()), 2.dp.toPx())
                })
            },
            modifier = Modifier.fillMaxWidth().semantics {
                // A content description becomes a separate child of the slider in Android.
                text = AnnotatedString(title)
                stateDescription = formatted
            })
        val custom = option in appearance.custom
        ReservedSlot(visible = custom) {
            Text(stringResource(R.string.appearance_default_value, defaultValue),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (help != null) Text(help, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (option == AppearanceOption.Weight && group == AppearanceGroup.Markdown) Text(
            stringResource(R.string.appearance_strong_emphasis),
            Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The dialog stays above either panel at one composition location across window changes. */
@Composable
internal fun TextAppearanceDialogs(panelState: TextAppearancePanelState, store: TextAppearanceStore,
    state: TextAppearanceStoreState, onFind: (Boolean) -> Boolean) {
    if (state == TextAppearanceStoreState.LoadFailed && panelState.confirmSavedReset) {
        val dismiss = { panelState.confirmSavedReset = false }
        AlertDialog(onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.appearance_reset_saved_title)) },
            text = { Text(stringResource(R.string.appearance_reset_saved_message)) },
            confirmButton = { TextButton(onClick = {
                dismiss()
                store.resetSavedAppearance()
            }) { Text(stringResource(R.string.appearance_reset_saved_confirm)) } },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.action_cancel)) } })
    }
    val draft = panelState.numberDraft ?: return
    if (state is TextAppearanceStoreState.Ready) AppearanceNumberDialog(draft,
        onDismiss = { panelState.numberDraft = null },
        onFind = onFind,
        onChange = { store.updateOption(draft.group, draft.option, it) })
}

@Composable
private fun AppearanceNumberDialog(draft: AppearanceNumberDraft,
    onDismiss: () -> Unit, onFind: (Boolean) -> Boolean, onChange: (Float) -> Unit) {
    val shortcuts = Modifier.documentFindShortcuts { replace ->
        val opened = onFind(replace)
        if (opened) onDismiss()
        opened
    }
    val option = draft.option
    val text = draft.text
    val parsed = draft.parsed
    val wholeNumber = option == AppearanceOption.Weight || option == AppearanceOption.TabWidth
    val unit = when (option) {
        AppearanceOption.Size -> R.string.appearance_unit_sp
        AppearanceOption.ColumnWidth, AppearanceOption.Margin -> R.string.appearance_unit_dp
        AppearanceOption.LineSpacing, AppearanceOption.ParagraphSpacing -> R.string.appearance_unit_times
        AppearanceOption.LetterSpacing, AppearanceOption.WordSpacing,
        AppearanceOption.HeadingScale, AppearanceOption.CodeScale -> R.string.appearance_unit_percent
        else -> null
    }
    val suffix = if (option == AppearanceOption.TabWidth && parsed != null)
        pluralStringResource(R.plurals.appearance_unit_spaces, parsed.toInt()) else unit?.let { stringResource(it) }
    val submit = {
        if (draft.apply(onChange)) onDismiss()
    }
    val errorText = if (parsed == null) stringResource(
        if (wholeNumber) R.string.appearance_enter_whole_number else R.string.appearance_enter_number,
        appearanceNumber(option.minimum), appearanceNumber(option.maximum)) else null
    val rangeText = errorText ?: stringResource(R.string.appearance_range,
        appearanceNumber(option.minimum), appearanceValue(option, option.maximum))
    val focus = remember { FocusRequester() }
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    // Reserve system bars and scaled controls before opening the IME so the field keeps its focus.
    val bars = WindowInsets.systemBarsIgnoringVisibility
    val usableHeight = window.height - bars.getTop(density) - bars.getBottom(density)
    val compact = with(density) {
        window.width.toDp() >= 600.dp && usableHeight.toDp() < 480.dp * fontScale.coerceAtLeast(1f)
    }
    val field: @Composable (Modifier, Boolean) -> Unit = { modifier, showRange ->
        OutlinedTextField(value = text, onValueChange = draft::updateText,
            singleLine = true, isError = errorText != null,
            label = { Text(stringResource(option.title)) },
            suffix = if (suffix != null) {{ Text(suffix) }} else null,
            trailingIcon = if (errorText != null) {{ Icon(painterResource(R.drawable.ic_error), null) }} else null,
            supportingText = if (showRange) {{ Text(rangeText,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }} else null,
            keyboardOptions = KeyboardOptions(keyboardType = if (wholeNumber) KeyboardType.Number else KeyboardType.Decimal,
                imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = modifier.focusRequester(focus).semantics {
                if (errorText != null) error(errorText)
            })
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
    val applyButton: @Composable () -> Unit = {
        TextButton(enabled = parsed != null, onClick = submit) { Text(stringResource(R.string.appearance_apply)) }
    }
    val cancelButton: @Composable () -> Unit = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    }
    if (compact) {
        // A short window cannot fit a stacked title, field and action row above the IME.
        // Keep the field and actions together; all remaining content can scroll above it.
        BasicAlertDialog(onDismissRequest = onDismiss, modifier = shortcuts,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(modifier = Modifier.safeDrawingPadding().imePadding().fillMaxWidth(),
                shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        field(Modifier.weight(1f), false)
                        cancelButton()
                        applyButton()
                    }
                    Text(rangeText, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (errorText != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    } else {
        AlertDialog(onDismissRequest = onDismiss, modifier = shortcuts,
            title = { Text(stringResource(option.title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    field(Modifier.fillMaxWidth(), true)
                    Text(stringResource(R.string.appearance_exact_value_help), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = applyButton, dismissButton = cancelButton)
    }
}
