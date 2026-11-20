package dev.soupslurpr.beautyxt.ui.designsystem

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.MenuItemShapes
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString

/** Keeps a selection menu item's label, state, activation, and focus on one accessible target. */
@Composable
internal fun SelectionMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    shapes: MenuItemShapes,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    selectedLeadingIcon: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }
    SelectableDropdownMenuItem(
        text = { Text(label) },
        selected = selected,
        onClick = onClick,
        shapes = shapes,
        leadingIcon = leadingIcon,
        selectedLeadingIcon = selectedLeadingIcon,
        trailingContent = trailingContent,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier.focusRequester(focusRequester).clearAndSetSemantics {
            // Expose the label and selection on the same focusable radio target.
            role = Role.RadioButton
            this.selected = selected
            text = AnnotatedString(label)
            focused = isFocused
            if (!enabled) disabled()
            requestFocus {
                if (enabled) focusRequester.requestFocus() else false
            }
            onClick {
                if (enabled) onClick()
                enabled
            }
        }
    )
}
