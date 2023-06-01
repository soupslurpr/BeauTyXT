package dev.soupslurpr.beautyxt.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.google.accompanist.systemuicontroller.rememberSystemUiController

/**
 * Dark color scheme for devices < Android 12, which do not support dynamic color.
 */
private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
)

/**
 * Light color scheme for devices < Android 12, which do not support dynamic color.
 */
private val LightColorScheme = lightColorScheme(
    primary = Color.Blue,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = Color(0xFFf5f5f5),
    surface = Color(0xFFf5f5f5),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color.Black,
    onSurface = Color.Black,
    primaryContainer = Color.White,
    secondaryContainer = Color.White,
    tertiaryContainer = Color.White,

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun BeauTyXTTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val systemUiController = rememberSystemUiController()
    /**
     * Set the status bar and navigation bar colors to the background color of the app.
     */
    if (darkTheme) {
        systemUiController.setSystemBarsColor(
            color = colorScheme.background
        )
        systemUiController.setNavigationBarColor(
            color = colorScheme.background
        )
    } else {
        systemUiController.setSystemBarsColor(
            color = colorScheme.background
        )
        systemUiController.setNavigationBarColor(
            color = colorScheme.background
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}