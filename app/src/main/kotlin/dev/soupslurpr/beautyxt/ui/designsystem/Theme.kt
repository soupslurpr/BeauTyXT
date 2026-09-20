package dev.soupslurpr.beautyxt.ui.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val BeauTyXTMotionScheme = MotionScheme.expressive()

/** Applies BeauTyXT's Material 3 Expressive theme. */
@Composable
fun BeauTyXTTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme =
        if (darkTheme) {
            dynamicDarkColorScheme(context)
        } else {
            dynamicLightColorScheme(context)
        }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = BeauTyXTMotionScheme,
        content = content
    )
}
