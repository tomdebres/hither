package com.tomdebres.hither.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

/**
 * Whether a control is *drawn* as focused. Not read out by any screen reader; it is here so a
 * test can tell "has focus" (the system's view) from "shows focus" (what the user sees), which
 * came apart once: a control that was handed focus while its screen was still arriving had it
 * and did not show it.
 */
val ShowsFocus = SemanticsPropertyKey<Boolean>("ShowsFocus")
var SemanticsPropertyReceiver.showsFocus by ShowsFocus

/**
 * How much a control shows its focus, 0 to 1: 200 ms in, 150 ms out, ease-out (2.3). Read it
 * in the draw or layer phase (a `graphicsLayer` block, `drawBehind`), never in composition: a
 * read in composition recomposes the control on every frame of the fade (DECISIONS 57).
 */
@Composable
fun focusAmount(focused: Boolean): () -> Float {
    val amount = remember { Animatable(if (focused) 1f else 0f) }
    LaunchedEffect(focused) {
        val target = if (focused) 1f else 0f
        // A control composed at rest is already there; a tween to where it is would still tick
        // through 150 ms of frames, once for every card that scrolls into view.
        if (amount.value != target) {
            amount.animateTo(target, tween(durationMillis = if (focused) 200 else 150, easing = FastOutSlowInEasing))
        }
    }
    return { amount.value }
}

/**
 * The focus scale, in the layer phase: what every focusable thing in Hither scales by (tv-material's controls and the
 * lockups hold their own at 1 and take this, DECISIONS 294, 321), with `ShowsFocus` said to the tests. The control
 * draws the rest of its focus itself. **No shadow anywhere**: Seerr drew none on a control (DECISIONS 67), and since A3
 * nothing draws one on a card either (DECISIONS 322). Read [amount] in the layer phase.
 */
@Composable
fun Modifier.focusScale(
    focused: Boolean,
    scale: Float = 1.06f,
    amount: () -> Float = focusAmount(focused),
): Modifier = this
    .semantics { showsFocus = focused }
    .graphicsLayer {
        val s = 1f + (scale - 1f) * amount()
        scaleX = s
        scaleY = s
    }

/**
 * A colour from [rest] to [focused] as [amount] goes from 0 to 1, for text drawn with
 * `BasicText(color = …)`: read in the draw phase, so a focus fade redraws the text and never
 * recomposes it (DECISIONS 57). Seerr fades its controls' colours (`transition duration-150`);
 * this keeps them in step with the scale.
 */
fun focusColor(rest: Color, focused: Color, amount: () -> Float): ColorProducer =
    if (rest == focused) ColorProducer { rest } else ColorProducer { lerp(rest, focused, amount().coerceIn(0f, 1f)) }

/** A fill in [shape] from [rest] to [focused] as focus eases, drawn behind the content (draw phase only). */
fun Modifier.focusFill(shape: Shape, rest: Color, focused: Color, amount: () -> Float): Modifier =
    drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        onDrawBehind {
            val colour = lerp(rest, focused, amount().coerceIn(0f, 1f))
            if (colour.alpha > 0f) drawOutline(outline, colour)
        }
    }

/**
 * A gradient fill in [shape] from [rest] to [focused] as focus eases: [rest] underneath and
 * [focused] over it at the focus amount, so two opaque gradients cross-fade (draw phase only).
 */
fun Modifier.focusFill(shape: Shape, rest: Brush, focused: Brush, amount: () -> Float): Modifier =
    drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        onDrawBehind {
            val a = amount().coerceIn(0f, 1f)
            if (a < 1f) drawOutline(outline, rest)
            if (a > 0f) drawOutline(outline, focused, alpha = a)
        }
    }

/**
 * A [width] border inside [shape] from [rest] to [focused] as focus eases, drawn over the content
 * as `Modifier.border` draws it (draw phase only).
 */
fun Modifier.focusBorder(width: Dp, shape: Shape, rest: Color, focused: Color, amount: () -> Float): Modifier =
    drawWithCache {
        val w = width.toPx()
        val outline = shape.createOutline(Size(size.width - w, size.height - w), layoutDirection, this)
        val stroke = Stroke(w)
        onDrawWithContent {
            drawContent()
            val colour = lerp(rest, focused, amount().coerceIn(0f, 1f))
            if (colour.alpha > 0f) translate(w / 2, w / 2) { drawOutline(outline, colour, style = stroke) }
        }
    }
