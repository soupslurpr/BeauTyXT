package dev.soupslurpr.beautyxt.ui.designsystem

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString

/** Exposes an Expressive menu action as one button without a selection state. */
@Composable
internal fun ActionMenuItem(
    label: String,
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        shape = shape,
        leadingIcon = leadingIcon,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier.focusRequester(focusRequester).clearAndSetSemantics {
            // Material 3's shaped action item currently exposes selected=false.
            // Keep its label, activation, and keyboard focus without that state.
            role = Role.Button
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
