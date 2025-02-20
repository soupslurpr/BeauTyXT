package dev.soupslurpr.beautyxt.newtyxt.dev.soupslurpr.beautyxt.ui.common

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

@Composable
fun ScreenLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(bottom = 16.dp, start = 16.dp, end = 16.dp),
    additionalContentPadding: PaddingValues = PaddingValues(all = 0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(16.dp),
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    layoutDirection: LayoutDirection = LocalLayoutDirection.current,
    content: LazyListScope.() -> Unit
) {
    LazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection)
                    + additionalContentPadding.calculateStartPadding(layoutDirection),
            top = contentPadding.calculateTopPadding()
                    + additionalContentPadding.calculateTopPadding(),
            end = contentPadding.calculateEndPadding(layoutDirection)
                    + additionalContentPadding.calculateEndPadding(layoutDirection),
            bottom = contentPadding.calculateBottomPadding()
                    + additionalContentPadding.calculateBottomPadding()
        ),
        reverseLayout = reverseLayout,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        flingBehavior = flingBehavior,
        userScrollEnabled = userScrollEnabled,
        content = content,
    )
}