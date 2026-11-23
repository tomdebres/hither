package com.tomdebres.hither.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.tomdebres.hither.R
import com.tomdebres.hither.ui.components.ReportRecomposition

/** The platter's corners round a text stop (tvOS's 12 points). */
private val StopShape = RoundedCornerShape(Metrics.ImageRadius)

/**
 * A few lines of a longer text on a page, as the Apple TV app shows a description (H2's X7; tvOS's `TextStop`): one stop,
 * the words cut at [lines] with "More" beside the last where they go on, and Select ([onSelect]) opening the whole, as
 * [ReadingScreen]. The title page's overview, a person's biography.
 *
 * tv-material's clickable `Surface` on [rememberTvClick]'s rules (Select once a press, the stand-in focus, `ShowsFocus`).
 * At rest it is the page's words alone, no container; with focus a light platter, the words dark on it (tvOS's plain
 * button: its words on the system's light platter), and Hither's slight lift in the layer phase, as a block of text at
 * the buttons' 1.1 would grow by a fifth of the screen. The words keep the page's line: the platter reaches
 * [Metrics.TextStopInset] past them on each side, outside the place the caller gives it. [header] goes above the words
 * inside the stop (the title page's tagline). Tagged [tag]; the words [textTag].
 */
@Composable
fun TextStop(
    text: String,
    onSelect: () -> Unit,
    tag: String,
    textTag: String,
    modifier: Modifier = Modifier,
    lines: Int = 4,
    header: (@Composable () -> Unit)? = null,
) {
    ReportRecomposition("text stop $tag")
    val click = rememberTvClick(onSelect, scale = Metrics.TextStopScale)
    // Whether the words go on past [lines]: "More" shows beside the last line while they do.
    var cut by remember(text, lines) { mutableStateOf(false) }
    Surface(
        onClick = click.onClick,
        modifier = modifier
            .outset(Metrics.TextStopInset)
            .then(click.modifier)
            .testTag(tag),
        shape = ClickableSurfaceDefaults.shape(StopShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Tokens.Text,
            focusedContainerColor = Tokens.FieldFocused,
            focusedContentColor = Tokens.FieldFocusedText,
            pressedContainerColor = Tokens.FieldFocused,
            pressedContentColor = Tokens.FieldFocusedText,
        ),
        // tv-material's own scale reads its animation in composition (A1, A3): held at 1, [click] lifts in the layer phase.
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        interactionSource = click.interaction,
    ) {
        Column(Modifier.padding(horizontal = Metrics.TextStopInset, vertical = 8.dp)) {
            header?.invoke()
            Row {
                Text(
                    text,
                    style = HitherType.Body,
                    maxLines = lines,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { cut = it.hasVisualOverflow },
                    modifier = Modifier
                        .weight(1f)
                        .alignBy(LastBaseline)
                        .testTag(textTag),
                )
                if (cut) {
                    Text(
                        stringResource(R.string.more),
                        style = HitherType.Caption.copy(fontWeight = FontWeight.Bold),
                        // the words' own colour, a step down: grey at rest, dark grey on the platter
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                        maxLines = 1,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .alignBy(LastBaseline)
                            .testTag("$tag more")
                            // the stop reads its words; "More" is what Select does, not a word of them (the tag stays
                            // outside the cleared node: M5)
                            .clearAndSetSemantics { },
                    )
                }
            }
        }
    }
}

/** Lays this out [by] wider on each side than the place it is given, the place itself unmoved: a platter round words. */
private fun Modifier.outset(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx()
    val wider = constraints.copy(
        minWidth = constraints.minWidth + extra * 2,
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra * 2 else constraints.maxWidth,
    )
    val placeable = measurable.measure(wider)
    layout(placeable.width - extra * 2, placeable.height) { placeable.place(-extra, 0) }
}
