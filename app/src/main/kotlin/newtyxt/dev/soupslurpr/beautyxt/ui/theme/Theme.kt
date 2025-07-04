package dev.soupslurpr.beautyxt.newtyxt.dev.soupslurpr.beautyxt.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

// TODO: make sure this is correct lol
@Composable
fun BeautyxtTheme(
    content: @Composable () -> Unit
) {
    val isDarkTheme = isSystemInDarkTheme()
    val lightColorScheme = defaultLightScheme
    val darkColorScheme = defaultDarkScheme

    val colorScheme = if (isDarkTheme) {
        darkColorScheme
    } else {
        lightColorScheme
    }

    MaterialTheme(colorScheme = colorScheme) {
        content()
    }
}