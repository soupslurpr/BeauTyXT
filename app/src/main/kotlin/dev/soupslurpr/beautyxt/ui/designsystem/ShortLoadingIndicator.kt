@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.soupslurpr.beautyxt.ui.designsystem

import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color

/** Displays a short indeterminate wait while respecting disabled system animations. */
@Composable
internal fun ShortLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = LoadingIndicatorDefaults.indicatorColor
) {
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    if (motionScale?.scaleFactor == 0f) {
        // The indeterminate component keeps changing shapes at zero motion scale.
        // Hold its first shape while preserving indeterminate progress semantics.
        LoadingIndicator(
            progress = { 0f },
            modifier = modifier.progressSemantics(),
            color = color,
            polygons = LoadingIndicatorDefaults.IndeterminateIndicatorPolygons
        )
    } else {
        LoadingIndicator(modifier = modifier, color = color)
    }
}
