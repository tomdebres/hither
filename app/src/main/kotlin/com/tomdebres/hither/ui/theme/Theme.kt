package com.tomdebres.hither.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * The scheme tv-material's controls read (DECISIONS 292), made from [Tokens]: dark, and neutral where tv-material
 * reads a colour for selection. Its `Tab`, `TabRow`'s indicator, `Switch`, `Checkbox` and chips take `primary` and
 * `secondary` and their containers, so those are white and greys: Seerr's indigo as `primary` would have put indigo on
 * every chosen thing, against rule 3 (H2's bytecode reading). Indigo reaches the screen through [ProminentButton]
 * alone. A focused control is white with black words (`onSurface`, `inverseOnSurface`); a focused card's edge is white
 * (`border`).
 */
val HitherColors: ColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Tokens.Surface,
    onPrimaryContainer = Tokens.Text,
    inversePrimary = Color.Black,
    secondary = Tokens.TextSecondary,
    onSecondary = Color.Black,
    secondaryContainer = Tokens.SurfaceRaised,
    onSecondaryContainer = Tokens.Text,
    tertiary = Tokens.TextSecondary,
    onTertiary = Color.Black,
    tertiaryContainer = Tokens.SurfaceRaised,
    onTertiaryContainer = Tokens.Text,
    background = Tokens.Background,
    onBackground = Tokens.Text,
    surface = Tokens.Background,
    onSurface = Tokens.Text,
    surfaceVariant = Tokens.Surface,
    onSurfaceVariant = Tokens.TextSecondary,
    surfaceTint = Color.White,
    inverseSurface = Tokens.FieldFocused,
    inverseOnSurface = Color.Black,
    error = Tokens.ErrorText,
    onError = Color.Black,
    errorContainer = Tokens.Surface,
    onErrorContainer = Tokens.ErrorText,
    border = Color.White,
    borderVariant = Tokens.Edge,
    scrim = Tokens.Scrim,
)

/**
 * tv-material's `MaterialTheme` round everything Hither draws: [HitherColors] and [HitherTypography]. `DpadHost` puts it
 * at the root of every screen, so the app and a device test that shows one screen see the same theme.
 */
@Composable
fun HitherTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HitherColors, typography = HitherTypography, content = content)
}
