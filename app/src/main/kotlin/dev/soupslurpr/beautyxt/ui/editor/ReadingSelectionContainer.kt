/* Gives a reading selection its own Back step before document navigation. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/** Clears selected reading text only when Back completes, never while it is being canceled. */
@Composable
internal fun ReadingSelectionContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // Selected document text is transient; do not serialize it into Activity saved state.
    val selection = remember { SelectionState() }
    val hasSelectedText by remember {
        derivedStateOf { selection.selectedTexts.any { it.isNotEmpty() } }
    }
    SelectionContainer(state = selection, modifier = modifier, content = content)
    // This child handler takes precedence over the editor's navigation handler only while selected.
    PredictiveBackHandler(enabled = hasSelectedText) { events ->
        events.collect { _ -> }
        selection.clear()
    }
}
