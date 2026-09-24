@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.printing.PrintContentMode
import dev.soupslurpr.beautyxt.printing.PrintFontFamily
import dev.soupslurpr.beautyxt.printing.PrintMarginField
import dev.soupslurpr.beautyxt.printing.PrintMarginUnit
import dev.soupslurpr.beautyxt.printing.PrintSetupDraft
import dev.soupslurpr.beautyxt.printing.convertPrintMarginUnit
import dev.soupslurpr.beautyxt.printing.maximumPrintMarginText
import dev.soupslurpr.beautyxt.printing.validatePrintSetup
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceButtons
import dev.soupslurpr.beautyxt.ui.designsystem.SingleChoiceOption

private val PrintSetupHorizontalPadding = 16.dp
private val PrintSetupBottomPadding = 24.dp
private val PrintSetupSectionSpacing = 16.dp
private val PrintSetupItemSpacing = 12.dp
private val PrintSetupControlSpacing = 8.dp
private val PrintSetupPinnedActionsMinimumHeight = 480.dp

/** Displays transient, rotation-stable options before Android's print screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrintSetupSheet(session: EditorSession) {
    val draft = session.printSetupDraft ?: return
    val validation = validatePrintSetup(draft)
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    // Choose the layout from the window, not the changing IME inset, so opening the
    // keyboard preserves the focused field and its scroll container. Short windows
    // scroll the actions with the form instead of reserving all space for a footer.
    val scrollActions =
        windowHeight < PrintSetupPinnedActionsMinimumHeight * density.fontScale.coerceAtLeast(1f)
    val scrollState = rememberScrollState()
    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        )

    ModalBottomSheet(
        onDismissRequest = session::dismissPrintSetup,
        sheetState = sheetState,
        dragHandle = if (scrollActions) null else ({ BottomSheetDefaults.DragHandle() })
    ) {
        Column(
            modifier = if (scrollActions) {
                Modifier.fillMaxWidth().verticalScroll(scrollState)
                    .padding(top = PrintSetupItemSpacing)
            } else {
                Modifier.fillMaxWidth()
            }
        ) {
            Column(
                modifier =
                    (
                        if (scrollActions) {
                            Modifier
                        } else {
                            Modifier.weight(1f, fill = false)
                                .verticalScroll(scrollState)
                        }
                        )
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Horizontal
                            )
                        )
                        .padding(horizontal = PrintSetupHorizontalPadding)
                        .padding(bottom = PrintSetupBottomPadding)
            ) {
                Text(
                    text = stringResource(R.string.print_setup),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(PrintSetupControlSpacing))
                Text(
                    text = stringResource(R.string.print_service_disclosure),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(PrintSetupSectionSpacing))
                PrintSetupSection(title = stringResource(R.string.print_content)) {
                    val contentControls: @Composable () -> Unit = {
                        SingleChoiceButtons(
                            choices =
                                listOf(
                                    SingleChoiceOption(
                                        label = stringResource(R.string.print_source_text),
                                        selected = draft.contentMode == PrintContentMode.Source,
                                        onClick = {
                                            session.updatePrintSetup(
                                                draft.copy(contentMode = PrintContentMode.Source)
                                            )
                                        }
                                    ),
                                    SingleChoiceOption(
                                        label = stringResource(R.string.print_formatted_markdown),
                                        selected =
                                            draft.contentMode == PrintContentMode.FormattedMarkdown,
                                        enabled = session.canPrintFormattedMarkdown,
                                        onClick = {
                                            session.updatePrintSetup(
                                                draft.copy(
                                                    contentMode = PrintContentMode.FormattedMarkdown
                                                )
                                            )
                                        }
                                    )
                                )
                        )
                        Text(
                            text =
                                when {
                                    !session.canPrintFormattedMarkdown ->
                                        stringResource(R.string.print_formatted_limit)

                                    draft.contentMode == PrintContentMode.Source ->
                                        stringResource(R.string.print_source_description)

                                    else -> stringResource(R.string.print_formatted_description)
                                },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    PrintSetupItem(content = contentControls)
                }
                Spacer(Modifier.height(PrintSetupSectionSpacing))
                PrintSetupSection(
                    title = stringResource(R.string.print_margins),
                    summary = if (validation.invalidMarginFields.isNotEmpty()) {
                        stringResource(R.string.print_check_margins)
                    } else if (
                        setOf(draft.topMargin, draft.bottomMargin, draft.leftMargin, draft.rightMargin)
                            .size == 1
                    ) {
                        stringResource(
                            R.string.print_margin_summary,
                            draft.topMargin,
                            draft.marginUnit.abbreviation
                        )
                    } else {
                        stringResource(R.string.print_custom_margins)
                    },
                    hasError = validation.invalidMarginFields.isNotEmpty()
                ) {
                    val marginUnitControls: @Composable () -> Unit = {
                        SingleChoiceButtons(
                            choices =
                                listOf(
                                    SingleChoiceOption(
                                        label = stringResource(R.string.print_inches),
                                        selected = draft.marginUnit == PrintMarginUnit.Inches,
                                        onClick = {
                                            session.updatePrintSetup(
                                                convertPrintMarginUnit(
                                                    draft,
                                                    PrintMarginUnit.Inches
                                                )
                                            )
                                        }
                                    ),
                                    SingleChoiceOption(
                                        label = stringResource(R.string.print_millimetres),
                                        selected = draft.marginUnit == PrintMarginUnit.Millimetres,
                                        onClick = {
                                            session.updatePrintSetup(
                                                convertPrintMarginUnit(
                                                    draft,
                                                    PrintMarginUnit.Millimetres
                                                )
                                            )
                                        }
                                    )
                                )
                        )
                    }
                    val verticalMarginControls: @Composable () -> Unit = {
                        MarginFieldRow(
                            draft = draft,
                            firstField = PrintMarginField.Top,
                            firstValue = draft.topMargin,
                            secondField = PrintMarginField.Bottom,
                            secondValue = draft.bottomMargin,
                            invalidFields = validation.invalidMarginFields,
                            onChange = session::updatePrintSetup
                        )
                    }
                    val horizontalMarginControls: @Composable () -> Unit = {
                        MarginFieldRow(
                            draft = draft,
                            firstField = PrintMarginField.Left,
                            firstValue = draft.leftMargin,
                            secondField = PrintMarginField.Right,
                            secondValue = draft.rightMargin,
                            invalidFields = validation.invalidMarginFields,
                            onChange = session::updatePrintSetup
                        )
                        Text(
                            text = stringResource(R.string.print_minimum_margin),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(PrintSetupItemSpacing)
                    ) {
                        PrintSetupItem(
                            content = marginUnitControls
                        )
                        PrintSetupItem(
                            content = verticalMarginControls
                        )
                        PrintSetupItem(
                            content = horizontalMarginControls
                        )
                    }
                }
                Spacer(Modifier.height(PrintSetupSectionSpacing))
                PrintSetupSection(
                    title = stringResource(R.string.print_text),
                    summary = if (validation.isFontSizeInvalid) {
                        stringResource(R.string.print_text_size_error)
                    } else {
                        stringResource(
                            if (draft.contentMode == PrintContentMode.Source && !draft.wrapLongLines) {
                                R.string.print_text_summary_clipped
                            } else {
                                R.string.print_text_summary
                            },
                            draft.fontFamily.label,
                            draft.fontSize
                        )
                    },
                    hasError = validation.isFontSizeInvalid
                ) {
                    val fontControls: @Composable () -> Unit = {
                        SingleChoiceButtons(
                            choices =
                                PrintFontFamily.entries.map { family ->
                                    SingleChoiceOption(
                                        label = family.label,
                                        selected = draft.fontFamily == family,
                                        onClick = {
                                            session.updatePrintSetup(
                                                draft.copy(fontFamily = family)
                                            )
                                        }
                                    )
                                }
                        )
                    }
                    val fontSizeControl: @Composable () -> Unit = {
                        OutlinedTextField(
                            value = draft.fontSize,
                            onValueChange = { value ->
                                session.updatePrintSetup(draft.copy(fontSize = value))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.print_text_size)) },
                            suffix = { Text(stringResource(R.string.print_point_unit)) },
                            supportingText = if (validation.isFontSizeInvalid) {
                                { Text(stringResource(R.string.print_text_size_error)) }
                            } else {
                                null
                            },
                            isError = validation.isFontSizeInvalid,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    val wrapSupportingText =
                        if (draft.contentMode == PrintContentMode.Source) {
                            stringResource(R.string.print_source_wrapping_description)
                        } else {
                            stringResource(R.string.print_markdown_wrapping_description)
                        }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(PrintSetupItemSpacing)
                    ) {
                        PrintSetupItem(
                            content = fontControls
                        )
                        PrintSetupItem(
                            content = fontSizeControl
                        )
                        PrintSetupToggleItem(
                            label = stringResource(R.string.print_wrap_source_lines),
                            supportingText = wrapSupportingText,
                            checked = draft.wrapLongLines,
                            enabled = draft.contentMode == PrintContentMode.Source,
                            onCheckedChange = { checked ->
                                session.updatePrintSetup(draft.copy(wrapLongLines = checked))
                            }
                        )
                    }
                }
                Spacer(Modifier.height(PrintSetupSectionSpacing))
                PrintSetupSection(
                    title = stringResource(R.string.print_page_details),
                    summary = stringResource(
                        when {
                            draft.showFileName && draft.showPageNumbers -> R.string.print_details_both
                            draft.showFileName -> R.string.print_file_name_header
                            draft.showPageNumbers -> R.string.print_page_numbers
                            else -> R.string.print_details_none
                        }
                    )
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(PrintSetupControlSpacing)
                    ) {
                        PrintSetupToggleItem(
                            label = stringResource(R.string.print_file_name_header),
                            checked = draft.showFileName,
                            onCheckedChange = { checked ->
                                session.updatePrintSetup(draft.copy(showFileName = checked))
                            }
                        )
                        PrintSetupToggleItem(
                            label = stringResource(R.string.print_page_numbers),
                            checked = draft.showPageNumbers,
                            onCheckedChange = { checked ->
                                session.updatePrintSetup(draft.copy(showPageNumbers = checked))
                            }
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FlowRow(
                modifier = Modifier.fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                        )
                    )
                    .padding(
                        horizontal = PrintSetupHorizontalPadding,
                        vertical = PrintSetupItemSpacing
                    ),
                horizontalArrangement = Arrangement.spacedBy(
                    PrintSetupControlSpacing,
                    Alignment.End
                ),
                verticalArrangement = Arrangement.spacedBy(PrintSetupControlSpacing)
            ) {
                TextButton(onClick = session::dismissPrintSetup) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = { session.confirmPrintSetup() },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ContentPadding,
                    enabled =
                        validation.isValid &&
                            (
                                draft.contentMode != PrintContentMode.FormattedMarkdown ||
                                    session.canPrintFormattedMarkdown
                                )
                ) {
                    Text(stringResource(R.string.print_open_screen))
                }
            }
        }
    }
}

/** Groups one labeled set of transient print controls. */
@Composable
private fun PrintSetupSection(
    title: String,
    summary: String? = null,
    hasError: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by retain { mutableStateOf(summary == null) }
    val showControls = expanded
    val chevronRotation by animateFloatAsState(
        targetValue = if (showControls) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "Print section expansion"
    )
    val focusManager = LocalFocusManager.current
    val expansionState = stringResource(
        if (showControls) R.string.print_section_expanded else R.string.print_section_collapsed
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column {
            if (summary == null) {
                Text(
                    text = title,
                    modifier = Modifier.padding(16.dp).semantics { heading() },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleSmall
                )
            } else {
                Surface(
                    onClick = {
                        focusManager.clearFocus()
                        expanded = !expanded
                    },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth().semantics {
                        heading()
                        role = Role.Button
                        stateDescription = expansionState
                    }
                ) {
                    Row(
                        modifier = Modifier.heightIn(min = 72.dp).padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (hasError) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        Icon(
                            painterResource(R.drawable.ic_keyboard_arrow_down),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp).graphicsLayer {
                                rotationZ = chevronRotation
                            }
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = showControls,
                enter = expandVertically(
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    expandFrom = Alignment.Top
                ) + fadeIn(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
                exit = shrinkVertically(
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    shrinkTowards = Alignment.Top
                ) + fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec())
            ) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    content()
                }
            }
        }
    }
}

/** Groups related print controls without adding a second background container. */
@Composable
private fun PrintSetupItem(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PrintSetupItemSpacing)) { content() }
}

/** Displays one accessible whole-row switch with a full-size touch target. */
@Composable
private fun PrintSetupToggleItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supportingText: String? = null,
    enabled: Boolean = true
) {
    PrintToggleContent(
        label = label,
        checked = checked,
        supportingText = supportingText,
        enabled = enabled,
        modifier =
            Modifier.heightIn(min = 48.dp).toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            ).padding(vertical = PrintSetupControlSpacing)
    )
}

/** Displays two independent physical margin fields. */
@Composable
private fun MarginFieldRow(
    draft: PrintSetupDraft,
    firstField: PrintMarginField,
    firstValue: String,
    secondField: PrintMarginField,
    secondValue: String,
    invalidFields: Set<PrintMarginField>,
    onChange: (PrintSetupDraft) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PrintSetupControlSpacing)
    ) {
        PrintMarginTextField(
            field = firstField,
            value = firstValue,
            unit = draft.marginUnit,
            isError = firstField in invalidFields,
            onValueChange = { value -> onChange(draft.withMargin(firstField, value)) },
            modifier = Modifier.weight(1f)
        )
        PrintMarginTextField(
            field = secondField,
            value = secondValue,
            unit = draft.marginUnit,
            isError = secondField in invalidFields,
            onValueChange = { value -> onChange(draft.withMargin(secondField, value)) },
            modifier = Modifier.weight(1f)
        )
    }
}

/** Displays one exact margin entry with its physical unit. */
@Composable
private fun PrintMarginTextField(
    field: PrintMarginField,
    value: String,
    unit: PrintMarginUnit,
    isError: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(field.label) },
        suffix = { Text(unit.abbreviation) },
        supportingText = if (isError) {
            (
                {
                    Text(
                        stringResource(
                            R.string.print_margin_error,
                            maximumPrintMarginText(unit),
                            unit.abbreviation
                        )
                    )
                }
                )
        } else {
            null
        },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

/** Displays one switch label and control without assigning its input semantics. */
@Composable
private fun PrintToggleContent(
    label: String,
    checked: Boolean,
    supportingText: String?,
    enabled: Boolean,
    modifier: Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PrintSetupItemSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color =
                    if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                style = MaterialTheme.typography.bodyLarge
            )
            supportingText?.let { text ->
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.size(width = 52.dp, height = 32.dp),
            enabled = enabled
        )
    }
}

/** Replaces one exact margin field while retaining every other transient choice. */
private fun PrintSetupDraft.withMargin(field: PrintMarginField, value: String): PrintSetupDraft =
    when (field) {
        PrintMarginField.Top -> copy(topMargin = value)
        PrintMarginField.Bottom -> copy(bottomMargin = value)
        PrintMarginField.Left -> copy(leftMargin = value)
        PrintMarginField.Right -> copy(rightMargin = value)
    }

private val PrintFontFamily.label: String
    @Composable get() = stringResource(
        when (this) {
            PrintFontFamily.SansSerif -> R.string.print_font_sans
            PrintFontFamily.Serif -> R.string.print_font_serif
            PrintFontFamily.Monospace -> R.string.print_font_mono
        }
    )

private val PrintMarginField.label: String
    @Composable get() = stringResource(
        when (this) {
            PrintMarginField.Top -> R.string.print_margin_top
            PrintMarginField.Bottom -> R.string.print_margin_bottom
            PrintMarginField.Left -> R.string.print_margin_left
            PrintMarginField.Right -> R.string.print_margin_right
        }
    )

private val PrintMarginUnit.abbreviation: String
    @Composable get() = stringResource(
        when (this) {
            PrintMarginUnit.Inches -> R.string.print_inch_unit
            PrintMarginUnit.Millimetres -> R.string.print_millimetre_unit
        }
    )
