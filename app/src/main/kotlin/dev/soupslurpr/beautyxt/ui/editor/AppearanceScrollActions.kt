/* Keeps accessibility scrolling within the appearance panel's own viewports. */
package dev.soupslurpr.beautyxt.ui.editor

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.scrollByOffset
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch

/**
 * Compose currently forwards accessibility scrolling into the sheet as touch
 * deltas, without the fling that settles it at an anchor. Scroll each state
 * directly for these actions; ordinary touch keeps its normal nested scrolling.
 * Apply before the viewport's scroll modifier so these actions take precedence.
 */
@Composable
internal fun Modifier.appearanceScrollActions(vertical: ScrollableState, horizontal: ScrollableState? = null): Modifier {
    val scope = rememberCoroutineScope()
    val scroll: suspend (Offset) -> Offset = { offset ->
        val x = if (offset.x != 0f && horizontal != null) horizontal.animateScrollBy(offset.x) else 0f
        val y = if (offset.y != 0f) vertical.animateScrollBy(offset.y) else 0f
        Offset(x, y)
    }
    return semantics {
        scrollBy { x, y ->
            scope.launch { scroll(Offset(x, y)) }
            true
        }
        scrollByOffset { scroll(it) }
    }
}
