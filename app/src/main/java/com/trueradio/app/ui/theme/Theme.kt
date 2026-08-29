package com.trueradio.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.trueradio.app.ThemeMode

// Brand green matches the app's launcher icon background (see ic_launcher_background.xml).
private val BrandGreen = Color(0xFF1DB954)
private val BrandGreenLight = Color(0xFF1ED760) // slightly brighter for adequate contrast on dark backgrounds

private val LightColors = lightColorScheme(
    primary = BrandGreen,
    onPrimary = Color.White,
    secondary = BrandGreen,
    tertiary = BrandGreen
)

private val DarkColors = darkColorScheme(
    primary = BrandGreenLight,
    onPrimary = Color.Black,
    secondary = BrandGreenLight,
    tertiary = BrandGreenLight,
    background = Color(0xFF121212), // standard Material dark-theme surface, not pure black
    surface = Color(0xFF121212)
)

/**
 * Wraps content in a Material3 theme honoring [themeMode]: LIGHT/DARK force a specific scheme,
 * SYSTEM follows the device's current light/dark setting and updates live if that changes while
 * the app is open (isSystemInDarkTheme() is itself observable/recomposes on change).
 *
 * Note: this only controls the Compose UI's colors. The very first frame before Compose draws
 * (the native window background/status bar) is controlled separately by
 * res/values/themes.xml + res/values-night/themes.xml, which only ever follow the *system*
 * setting - so a manual LIGHT/DARK override here won't retroactively change that first-frame
 * flash if it conflicts with the system setting. This is a minor, one-frame cosmetic edge case,
 * not a functional issue.
 */
@Composable
fun TrueRadioTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val useDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkColors else LightColors,
        content = content
    )
}
