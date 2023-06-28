package dev.soupslurpr.beautyxt.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import dev.soupslurpr.beautyxt.settings.SettingsViewModel

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
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,

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
    dataStore: DataStore<Preferences>,
    settingsViewModel: SettingsViewModel = viewModel(),
    content: @Composable () -> Unit
) {

    LaunchedEffect(key1 = Unit) {
        settingsViewModel.populateSettingsFromDatastore(dataStore)
    }

    val settingsUiState by settingsViewModel.uiState.collectAsState()

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val systemUiController = rememberSystemUiController()

    val pitchBlackBackground = settingsUiState.pitchBlackBackground.second.value and darkTheme

    val finalColorScheme: ColorScheme = colorScheme.copy(
        background = if (pitchBlackBackground) {Color.Black} else {colorScheme.background},
        surface = if (pitchBlackBackground) {Color.Black} else {colorScheme.surface}
    )

    /**
     * Set the status bar and navigation bar colors to the background color of the app.
     */
    if (darkTheme) {
        systemUiController.setSystemBarsColor(
            color = finalColorScheme.background
        )
        systemUiController.setNavigationBarColor(
            color = finalColorScheme.background
        )
    } else {
        systemUiController.setSystemBarsColor(
            color = finalColorScheme.background
        )
        systemUiController.setNavigationBarColor(
            color = finalColorScheme.background
        )
    }

    MaterialTheme(
        colorScheme = finalColorScheme,
        typography = Typography,
        content = content
    )
}