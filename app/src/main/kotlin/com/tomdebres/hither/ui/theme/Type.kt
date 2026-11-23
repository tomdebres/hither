package com.tomdebres.hither.ui.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography

/**
 * tv-material's own type scale, in its own typeface: Roboto, Android TV's (Tom's Q3 in H2, DECISIONS 293). Inter,
 * Seerr's typeface, left the app's words with it; the logo keeps its word as outlines, so no font file ships.
 *
 * The one place a size is set (`ThemeRulesTest`: no `fontSize =` anywhere else). The theme hands it to tv-material's
 * controls (`HitherTheme`); Hither's own words take the same styles through [HitherType].
 */
val HitherTypography = Typography()

/**
 * The styles Hither's screens draw their words in, each one of [HitherTypography]'s, so a screen names a role and never
 * a size (tvOS's `TextStylesTests` rule). Weights may change at a call site; sizes may not.
 */
object HitherType {
    /** A screen's largest words: the code to enter, a page that has nothing else (headlineMedium, 28 sp, bold). */
    val Display = HitherTypography.headlineMedium.copy(fontWeight = FontWeight.Bold)

    /** A title: a pushed page's title, a dialog's, a shelf's header, a state's words (titleLarge, 22 sp, bold). */
    val Title = HitherTypography.titleLarge.copy(fontWeight = FontWeight.Bold)

    /** A heading inside a page, a card's title (titleMedium, 16 sp, medium). */
    val Subtitle = HitherTypography.titleMedium

    /** Running words and controls' labels (bodyMedium, 14 sp). */
    val Body = HitherTypography.bodyMedium

    /** Smaller words: a year, a date, a hint (bodySmall, 12 sp). */
    val Caption = HitherTypography.bodySmall

    /** The smallest: a count, a chip (labelSmall, 11 sp, medium; Android TV's floor is above 10, DECISIONS 11). */
    val Label = HitherTypography.labelSmall

    /**
     * The code a person reads across the room and types at plex.tv/link or approves in Jellyfin: displayLarge's face,
     * bold and spaced, at the 80 sp M8 chose for it (DECISIONS 238), the one size above tv-material's scale.
     */
    val Code = HitherTypography.displayLarge.copy(fontSize = 80.sp, lineHeight = 92.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.12.em)
}
