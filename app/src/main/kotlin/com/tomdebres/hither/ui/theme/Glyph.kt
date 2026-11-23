package com.tomdebres.hither.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.core.rules.BadgeKind
import com.tomdebres.hither.core.rules.StatusBadge
import com.tomdebres.hither.core.rules.StatusWord
import com.tomdebres.hither.ui.components.HitherIcons

/** Seerr's status colour for the kind (rule 4): green, indigo, yellow, red, on the glyph's disc and nowhere else. */
val BadgeKind.colour: Color
    get() = when (this) {
        BadgeKind.SUCCESS -> Tokens.Success
        BadgeKind.PRIMARY -> Tokens.Primary
        BadgeKind.WARNING -> Tokens.Warning
        BadgeKind.DANGER -> Tokens.Danger
    }

/**
 * The glyph's mark on the kind's disc: white where white reads (indigo 4.5:1, red 3.8:1), black on the light discs where
 * it cannot (yellow: white 1.9:1, black 10.9:1; green: white 2.3:1, black 9.2:1). tvOS's F27; `ThemeColoursTest`
 * computes each.
 */
val BadgeKind.mark: Color
    get() = when (this) {
        BadgeKind.WARNING, BadgeKind.SUCCESS -> Color.Black
        BadgeKind.PRIMARY, BadgeKind.DANGER -> Color.White
    }

/** The mark that says the status at a glance: Seerr's mini badge's icons where it has one, the nearest for the rest. */
val StatusBadge.icon: ImageVector?
    get() = when (word) {
        StatusWord.AVAILABLE -> HitherIcons.Check
        StatusWord.PARTIALLY_AVAILABLE -> HitherIcons.Minus
        StatusWord.REQUESTED -> HitherIcons.Clock
        StatusWord.PENDING -> HitherIcons.Bell
        StatusWord.DECLINED -> HitherIcons.Cross
        StatusWord.DELETED -> HitherIcons.Trash
        StatusWord.BLOCKLISTED -> HitherIcons.EyeOff
        else -> null
    }

/** The glyph's ring: black at this strength round the disc, so a disc keeps its edge on any picture (DECISIONS 323). */
private const val RING_ALPHA = 0.6f

/**
 * Every status colour Hither draws, drawn one way (rule 4; tvOS's `Glyph`): a disc in the kind's colour, its [icon]
 * in the colour that reads on it ([mark]), and a thin dark ring round the disc so it keeps its edge on a picture of its
 * own colour (tvOS's F35). [spinning] turns a spinner in the mark's colour in place of the icon (a download under way).
 * Drawn, not composed: a still disc costs nothing per frame.
 */
@Composable
fun Glyph(icon: ImageVector?, kind: BadgeKind, modifier: Modifier = Modifier, size: Dp = Metrics.Glyph, spinning: Boolean = false) {
    val disc = kind.colour
    val mark = kind.mark
    Box(
        modifier
            .size(size + Metrics.GlyphRing * 2)
            .drawBehind {
                // the ring: black at 60% a ring's width round the disc. tvOS's 40% gives white posters 2.85:1, under WCAG's
                // 3:1 for an object's edge, where the yellow and green discs cannot give one (A3, LockupTest: 5.7:1)
                drawCircle(Color.Black.copy(alpha = RING_ALPHA))
                drawCircle(disc, radius = size.toPx() / 2)
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            spinning -> Spinner(Modifier.size(size * 0.6f), colour = mark, width = 1.5.dp)
            icon != null -> Image(icon, contentDescription = null, modifier = Modifier.size(size * 0.7f), colorFilter = ColorFilter.tint(mark))
        }
    }
}

/**
 * A status as a glyph (a poster's corner, beside a title): TalkBack reads its words ("4K Requested"). Tagged
 * `badge <words>` as Seerr's mini badge was.
 */
@Composable
fun StatusGlyph(badge: StatusBadge, modifier: Modifier = Modifier, size: Dp = Metrics.Glyph) {
    Glyph(
        badge.icon,
        badge.kind,
        modifier
            .semantics { contentDescription = badge.text }
            .testTag("badge ${badge.text}"),
        size = size,
        spinning = badge.inProgress,
    )
}

/**
 * A status with its words (tvOS's `StatusLabel`): the glyph, then the words in the line's own style, white, never on a
 * fill (rule 4; Seerr's StatusBadge pill went with A1). A download under way adds its episode and share as words. Tagged
 * `status <words>`, as the pill was.
 */
@Composable
fun StatusLabel(badge: StatusBadge, modifier: Modifier = Modifier, style: TextStyle = HitherType.Caption) {
    val progress = badge.progress
    Row(
        modifier
            .testTag("status ${badge.text}")
            .then(if (progress != null) Modifier.semantics { stateDescription = "${progress.percent}%" } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Glyph(badge.icon, badge.kind, spinning = badge.inProgress)
        val words = listOfNotNull(badge.text, progress?.episode, progress?.let { "${it.percent}%" })
        BasicText(words.joinToString(Formats.Separator), style = style.copy(color = Tokens.Text), maxLines = 1, modifier = Modifier.padding(end = 2.dp))
    }
}
