package com.trueradio.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.trueradio.app.ThemeMode

/**
 * Palette drawn from 1970s silver-face receivers: a champagne brushed-aluminium faceplate, a deep
 * blue backlit tuning window, an amber needle and warm dial lamps, and walnut cabinet sides.
 *
 * The blue and amber are the load-bearing colours - on the real hardware they're the only things
 * that emit light, everything else is reflective metal and wood. Keeping that relationship is
 * what makes the UI read as a lit panel rather than as a blue-and-gold colour scheme.
 */
object RadioPalette {
    /** Faceplate: warm silver, slightly yellow - never neutral grey. */
    val Champagne = Color(0xFFC9C3B2)
    val ChampagneLight = Color(0xFFE0DACA)
    val ChampagneDark = Color(0xFF9A9484)

    /** Backlit dial window. */
    val DialBlue = Color(0xFF0E3A5F)
    val DialBlueDeep = Color(0xFF07223A)
    val DialGlow = Color(0xFF4FA8D8)

    /** Needle / pilot lamps. */
    val Amber = Color(0xFFE8A33D)
    val AmberBright = Color(0xFFFFC65C)

    /** Cabinet. */
    val Walnut = Color(0xFF4A3226)
    val WalnutDark = Color(0xFF2E1F17)

    /** Knobs and switch bezels. */
    val KnobBlack = Color(0xFF1C1A18)
    val KnobRim = Color(0xFF8A8478)

    /** Brushed-metal fill: fine vertical banding approximated with a multi-stop gradient. */
    val brushedMetal = Brush.verticalGradient(
        0.0f to ChampagneLight,
        0.15f to Champagne,
        0.5f to ChampagneDark,
        0.85f to Champagne,
        1.0f to ChampagneLight
    )

    /** Inside of the lit dial window - darker at the edges, glowing toward the centre. */
    val dialWindow = Brush.verticalGradient(
        0.0f to DialBlueDeep,
        0.45f to DialBlue,
        1.0f to DialBlueDeep
    )
}

/**
 * Light scheme = faceplate under room light. Dark scheme = the same receiver in a dark room,
 * where the cabinet recedes and only the dial window and lamps stay lit.
 */
private val LightColors = lightColorScheme(
    primary = RadioPalette.DialBlue,
    onPrimary = RadioPalette.ChampagneLight,
    primaryContainer = RadioPalette.DialBlue,
    onPrimaryContainer = RadioPalette.DialGlow,
    secondary = RadioPalette.Amber,
    onSecondary = RadioPalette.KnobBlack,
    tertiary = RadioPalette.Walnut,
    background = RadioPalette.ChampagneLight,
    onBackground = RadioPalette.KnobBlack,
    surface = RadioPalette.Champagne,
    onSurface = RadioPalette.KnobBlack,
    surfaceVariant = RadioPalette.ChampagneDark,
    onSurfaceVariant = Color(0xFF3A362F),
    outline = RadioPalette.KnobRim,
    error = Color(0xFF9B3A2A)
)

private val DarkColors = darkColorScheme(
    primary = RadioPalette.DialGlow,
    onPrimary = RadioPalette.DialBlueDeep,
    primaryContainer = RadioPalette.DialBlue,
    onPrimaryContainer = RadioPalette.DialGlow,
    secondary = RadioPalette.AmberBright,
    onSecondary = RadioPalette.KnobBlack,
    tertiary = RadioPalette.Walnut,
    background = RadioPalette.WalnutDark,
    onBackground = RadioPalette.ChampagneLight,
    surface = Color(0xFF241A14),
    onSurface = RadioPalette.ChampagneLight,
    surfaceVariant = Color(0xFF3A2A20),
    onSurfaceVariant = RadioPalette.Champagne,
    outline = RadioPalette.KnobRim,
    error = Color(0xFFC5563F)
)

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
