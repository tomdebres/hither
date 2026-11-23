package com.tomdebres.hither.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonBorder
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.tomdebres.hither.ui.components.ReportRecomposition
import com.tomdebres.hither.ui.components.focusAmount
import com.tomdebres.hither.ui.components.focusScale

/**
 * The screen's one main action (rule 1, DECISIONS 292): tv-material's `Button` filled with Seerr's indigo, white words
 * (6.3:1), and with focus a white edge as well as tv-material's scale. Sign In, Request, Try Again where a page did not
 * load: one per screen. Seerr's other fills (danger, success, warning) are gone: see [PlainButton].
 */
@Composable
fun ProminentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: ImageVector? = null,
    /** Words centred in a button wider than they are (an `EntryColumn`'s, as tvOS's entry button); else at the start. */
    centred: Boolean = false,
) {
    TvButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        busy = busy,
        icon = icon,
        centred = centred,
        colors = ButtonDefaults.colors(
            containerColor = Tokens.Accent,
            contentColor = Color.White,
            focusedContainerColor = Tokens.Accent,
            focusedContentColor = Color.White,
            pressedContainerColor = Tokens.Accent,
            pressedContentColor = Color.White,
            disabledContainerColor = Tokens.Accent.copy(alpha = 0.4f),
            disabledContentColor = Color.White.copy(alpha = 0.6f),
        ),
        border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = CircleShape)),
    )
}

/**
 * Every other button: tv-material's own, in the theme's colours (a grey capsule at rest; white with black words when
 * focused). [destructive] gives it red words (rule 2: Sign Out, Remove Admin Account, Decline, Cancel Request), never
 * a red fill.
 */
@Composable
fun PlainButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    destructive: Boolean = false,
    icon: ImageVector? = null,
    /** Words centred in a button wider than they are (an `EntryColumn`'s); else at the start, tv-material's own. */
    centred: Boolean = false,
) {
    TvButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        busy = busy,
        icon = icon,
        centred = centred,
        colors = if (destructive) {
            ButtonDefaults.colors(
                contentColor = Tokens.Destructive,
                focusedContentColor = Tokens.DestructiveFocused,
                pressedContentColor = Tokens.DestructiveFocused,
            )
        } else {
            ButtonDefaults.colors()
        },
        border = ButtonDefaults.border(),
    )
}

/**
 * A button's look on something that is not itself the focus target: a Discover row's notice is one target from loading
 * to failed to its cards (DECISIONS 41), and draws its Try Again from the row's [focused]. As [ProminentButton] or
 * [PlainButton] draw theirs, with the scale in the layer phase.
 */
@Composable
fun ButtonLook(text: String, focused: Boolean, modifier: Modifier = Modifier, prominent: Boolean = false) {
    val amount = focusAmount(focused)
    val (container, words) = when {
        prominent -> Tokens.Accent to Color.White
        focused -> Color.White to Color.Black
        else -> Tokens.Surface.copy(alpha = 0.8f) to Tokens.Text.copy(alpha = 0.8f)
    }
    Box(
        modifier
            .focusScale(focused, scale = Metrics.FocusScale, amount = amount)
            .heightIn(min = ButtonMinHeight)
            .clip(CircleShape)
            .background(container)
            .then(if (prominent && focused) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
            .padding(ButtonDefaults.ContentPadding),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = HitherType.Body.copy(color = words), maxLines = 1)
    }
}

/** tv-material's own button height (`BaseButtonDefaults.MinHeight`, internal to it). */
private val ButtonMinHeight = 40.dp

/**
 * tv-material's `Button`, kept to Hither's D-pad rules and test hooks (H2's "for H3"; DECISIONS 294):
 *
 * - **Select fires once per press.** `selectBeforeKeyboard` takes Select ahead of the on-screen keyboard and so ahead
 *   of tv-material's own key handling, which fires on any key-up, even one whose press began on another control (a
 *   dialog's Cancel would take the Select that opened the dialog). A remote's Select repeats; one press is one click.
 *   Where the keyboard's stage is passed by (a test's key input), a preview handler keeps the same rule ahead of
 *   tv-material's.
 * - **Busy stays focusable and does nothing** ("Requesting…"): focus is never dropped from under the hand (M2).
 *   Disabled takes no focus, as before; tv-material would focus a disabled button.
 * - **The focus scale runs in the layer phase.** tv-material reads its scale's animation in composition and recomposes
 *   the button on every frame of it (H2, from its bytecode); its own is held at 1 and `focusScale` gives the same 1.1
 *   in a `graphicsLayer` (DECISIONS 57). Its colours, shape and edge are tv-material's.
 * - **The test hooks:** `ShowsFocus` (through `focusScale`) from the interaction source tv-material draws its focus
 *   from, so a test tells "has focus" from "shows focus"; `ReportRecomposition("button <words>")`. Focus is said to
 *   tv-material again a frame after it comes, so a button focused as its page arrives is drawn focused (A7).
 *
 * The rules live in [rememberTvClick] since A7, shared with the list rows and the PIN's keys.
 */
@Composable
private fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    busy: Boolean,
    icon: ImageVector?,
    colors: ButtonColors,
    border: ButtonBorder,
    centred: Boolean = false,
) {
    ReportRecomposition("button $text")
    val click = rememberTvClick(onClick, enabled = enabled, busy = busy)
    Button(
        onClick = click.onClick,
        modifier = modifier.then(click.modifier),
        enabled = enabled,
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = colors,
        border = border,
        contentPadding = if (icon != null) ButtonDefaults.ButtonWithIconContentPadding else ButtonDefaults.ContentPadding,
        interactionSource = click.interaction,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize).testTag("icon"))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = HitherType.Body.let { if (centred) it.copy(textAlign = TextAlign.Center) else it },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (centred) Modifier.weight(1f) else Modifier,
        )
    }
}
