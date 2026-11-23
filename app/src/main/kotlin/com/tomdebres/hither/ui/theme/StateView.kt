package com.tomdebres.hither.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.ui.components.FocusOnArrival

/** What a [StateView] says: something on its way, nothing there, a failure, no right to see it. */
enum class StateKind {
    LOADING,
    EMPTY,
    FAILED,
    NO_RIGHT;

    /** The symbol each kind shows unless a screen names its own (material-icons-core, which tv-material brings). */
    val icon: ImageVector?
        get() = when (this) {
            LOADING -> null
            EMPTY -> Icons.Outlined.Info
            FAILED -> Icons.Outlined.Warning
            NO_RIGHT -> Icons.Outlined.Lock
        }
}

/** The symbol for a list with nothing left in it (tvOS's "all done"): a ticked circle. */
val NothingLeftIcon: ImageVector get() = Icons.Outlined.CheckCircle

/**
 * What a page or a row shows in place of what it could not show (H2's X2; tvOS's `StateView`), drawn one way:
 * the symbol in grey, the [words], an optional [detail] under them, and one way on ([action]), centred across the room it
 * is given. Loading turns Hither's [Spinner] over its words instead of a symbol. It replaces `FailureNotice`, the
 * Discover row's notice, the lists' and search's own empty words (DECISIONS 295).
 *
 * The way on takes focus when the state arrives, so focus never lands on nothing (REVIEW.md): [action] is handed the
 * modifier to put on the control that takes it (Try Again, Back to Discover); [focusOnArrival] is off where the state
 * is not where the user is (a row further down Discover). [wordsTag] and [detailTag] tag the words, so a test can find
 * and measure them.
 */
@Composable
fun StateView(
    kind: StateKind,
    words: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    icon: ImageVector? = kind.icon,
    wordsTag: String = "state words",
    detailTag: String = "state detail",
    focusOnArrival: Boolean = true,
    action: (@Composable RowScope.(Modifier) -> Unit)? = null,
) {
    val first = remember { FocusRequester() }
    if (action != null && focusOnArrival) FocusOnArrival(first)
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            kind == StateKind.LOADING -> Spinner(Modifier.size(Metrics.StateIcon * 0.8f).testTag("state spinner"))
            icon != null -> Image(icon, contentDescription = null, modifier = Modifier.size(Metrics.StateIcon), colorFilter = ColorFilter.tint(Tokens.TextSecondary))
        }
        Spacer(Modifier.height(12.dp))
        BasicText(
            words,
            style = HitherType.Title.copy(color = Tokens.Text, textAlign = TextAlign.Center),
            modifier = Modifier.padding(horizontal = Metrics.SideMargin).testTag(wordsTag),
        )
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            BasicText(
                detail,
                style = HitherType.Body.copy(color = Tokens.TextSecondary, textAlign = TextAlign.Center),
                modifier = Modifier.padding(horizontal = Metrics.SideMargin).testTag(detailTag),
            )
        }
        if (action != null) {
            Spacer(Modifier.height(Metrics.StateActionsLead))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                action(Modifier.focusRequester(first))
            }
        }
    }
}

/**
 * Hither's spinner (tv-material has none): a ring with a quarter open, turning once a second. The turn is read in the
 * layer phase, so it redraws and never recomposes. Compose's test rule stops it where it is.
 */
@Composable
fun Spinner(modifier: Modifier = Modifier, colour: Color = Tokens.Text, width: Dp = 3.dp) {
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "turn",
    )
    Canvas(modifier.graphicsLayer { rotationZ = turn }) {
        val w = width.toPx()
        drawArc(
            colour,
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
            size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
            style = Stroke(w, cap = StrokeCap.Round),
        )
    }
}
