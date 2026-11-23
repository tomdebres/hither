package com.tomdebres.hither.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Test hook (shield-launcher's, DECISIONS 57): a device test provides a counter here to see
 * which parts of a screen recompose, and how often, as the D-pad moves. The app never provides
 * one, so in the app the cost is one static read per composition.
 */
val LocalRecompositionProbe = staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * Reports each *re*composition of the calling composable as [name] to [LocalRecompositionProbe].
 * The first composition is not reported: a card that scrolls into view has to be composed, and
 * the question a test asks is what composes again.
 */
@Composable
@NonRestartableComposable
fun ReportRecomposition(name: String) {
    val probe = LocalRecompositionProbe.current
    if (probe != null) {
        val composed = remember { BooleanArray(1) }
        SideEffect {
            if (composed[0]) probe(name) else composed[0] = true
        }
    }
}

/**
 * Test hook beside [LocalRecompositionProbe]: a part of a screen that may leave its content undrawn
 * reports each draw as its name and whether it drew (DECISIONS 90: Discover's rows under the solid
 * bar). The app never provides one.
 */
val LocalDrawProbe = staticCompositionLocalOf<((String, Boolean) -> Unit)?> { null }
