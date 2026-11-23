package com.tomdebres.hither.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable

/**
 * A loading place's pulse, 100% to 50% and back over 2 s (Seerr's `animate-pulse`), read in the draw or layer phase.
 * Discover's and the grids' placeholders are still since A3 (tvOS's); the title page's buttons and My Requests' rows
 * still pulse until their parts (A4, A6) redraw them.
 */
@Composable
fun rememberPulse(): () -> Float {
    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "alpha",
    )
    return { pulse.value }
}
