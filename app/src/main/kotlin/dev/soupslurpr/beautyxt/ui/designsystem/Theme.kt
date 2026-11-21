package dev.soupslurpr.beautyxt.ui.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toDrawable

private val BeauTyXTMotionScheme = MotionScheme.expressive()

/** Uses pure black page surfaces while keeping dynamic dark containers and ink intact. */
internal fun ColorScheme.withTrueBlackDarkSurfaces(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    onBackground = onSurface
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Applies BeauTyXT's Material 3 Expressive theme. */
@Composable
fun BeauTyXTTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    trueBlackDarkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme =
        if (darkTheme) {
            val dark = dynamicDarkColorScheme(context)
            if (trueBlackDarkTheme) dark.withTrueBlackDarkSurfaces() else dark
        } else {
            dynamicLightColorScheme(context)
        }

    val activity = remember(context) { context.findActivity() }
    val surfaceArgb = colorScheme.surface.toArgb()
    val appliedSurfaceArgb = remember(activity) { mutableStateOf<Int?>(null) }
    SideEffect {
        if (activity != null && appliedSurfaceArgb.value != surfaceArgb) {
            activity.window.setBackgroundDrawable(surfaceArgb.toDrawable())
            appliedSurfaceArgb.value = surfaceArgb
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = BeauTyXTMotionScheme,
        content = content
    )
}
