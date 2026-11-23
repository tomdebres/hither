package com.tomdebres.hither.ui

import androidx.compose.ui.graphics.Color
import com.tomdebres.hither.core.rules.BadgeKind
import com.tomdebres.hither.ui.theme.HitherColors
import com.tomdebres.hither.ui.theme.Tokens
import com.tomdebres.hither.ui.theme.colour
import com.tomdebres.hither.ui.theme.mark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The palette's rules that are numbers (A1, DECISIONS 292), computed from the colours themselves: what must read reads,
 * and nothing tv-material draws for selection is indigo.
 */
class ThemeColoursTest {
    /**
     * Every status glyph's mark reads on its disc, 3:1 at least (WCAG's for a graphic; tvOS's F27: a white bell on
     * Seerr's yellow is 1.9:1, a white tick on its green 2.3:1).
     */
    @Test
    fun glyphMarksReadOnTheirDiscs() {
        for (kind in BadgeKind.entries) {
            val ratio = contrast(kind.colour, kind.mark)
            assertTrue("$kind's mark on its disc: ${"%.1f".format(ratio)}:1", ratio >= 3.0)
        }
    }

    /** The words Hither draws read where they are drawn, 4.5:1 at least (WCAG's for text). */
    @Test
    fun wordsRead() {
        val pairs = listOf(
            Triple("white words on the prominent button's indigo", Color.White, Tokens.Accent),
            Triple("secondary words on a surface", Tokens.TextSecondary, Tokens.Surface),
            Triple("an error's words on a surface", Tokens.ErrorText, Tokens.Surface),
            Triple("a destructive button's words at rest", Tokens.Destructive, Tokens.Surface),
            Triple("a destructive button's words with focus, on the focused white", Tokens.DestructiveFocused, HitherColors.onSurface),
            Triple("a field's placeholder at rest", Tokens.FieldPrompt, Tokens.Field),
            Triple("a focused field's placeholder", Tokens.FieldFocusedPrompt, Tokens.FieldFocused),
            Triple("a focused field's words", Tokens.FieldFocusedText, Tokens.FieldFocused),
            Triple("a focused button's words", HitherColors.inverseOnSurface, HitherColors.onSurface),
        )
        for ((what, words, ground) in pairs) {
            val ratio = contrast(words, ground)
            assertTrue("$what: ${"%.1f".format(ratio)}:1", ratio >= 4.5)
        }
    }

    /**
     * Rule 1 and 3 through tv-material: its tabs, tab indicator, switches, checkboxes and chips read `primary`,
     * `secondary` and their containers, so those are greys (no hue), and a focused card's edge (`border`) is white.
     */
    @Test
    fun theSchemeIsNeutralWhereTvMaterialReadsSelection() {
        val roles = mapOf(
            "primary" to HitherColors.primary,
            "onPrimary" to HitherColors.onPrimary,
            "primaryContainer" to HitherColors.primaryContainer,
            "onPrimaryContainer" to HitherColors.onPrimaryContainer,
            "secondary" to HitherColors.secondary,
            "secondaryContainer" to HitherColors.secondaryContainer,
            "onSecondaryContainer" to HitherColors.onSecondaryContainer,
            "tertiary" to HitherColors.tertiary,
            "surfaceTint" to HitherColors.surfaceTint,
            "inverseSurface" to HitherColors.inverseSurface,
            "border" to HitherColors.border,
        )
        val hued = roles.filterValues { it.red != it.green || it.green != it.blue }.keys
        assertEquals("roles with a hue", emptySet<String>(), hued)
        assertEquals("a focused card's edge", Color.White, HitherColors.border)
        assertEquals("the page", Color.Black, HitherColors.background)
    }

    /** WCAG's contrast ratio between two opaque sRGB colours. */
    private fun contrast(a: Color, b: Color): Double {
        fun channel(c: Float): Double = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
        val (la, lb) = luminance(a) to luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
