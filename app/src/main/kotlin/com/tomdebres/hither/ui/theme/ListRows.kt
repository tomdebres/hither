package com.tomdebres.hither.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.ListItemScale
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.tomdebres.hither.ui.components.HitherIcons
import com.tomdebres.hither.ui.components.ReportRecomposition

/** A row's corners: tv-material's own for a list item. */
val RowShape = RoundedCornerShape(8.dp)

/** A row's focus scale, a little less than a button's: a row is wide, and grows out of its list (tvOS's lift is ~2%). */
const val RowFocusScale = 1.02f

/** Words that say less on a row (a value, the call under a failure): the row's own colour, part strength. */
const val RowSecondaryAlpha = 0.7f

/**
 * tv-material's `ListItem`, kept to Hither's D-pad rules and test hooks ([rememberTvClick], as A1's `TvButton` is) and
 * to the look's rules (H2's X5; `Tokens.kt`'s header):
 *
 * - At rest a form row's grey ([Tokens.Field], tvOS's), with focus its white ([Tokens.FieldFocused]) and black words;
 *   [destructive] gives it red words (rule 2), never a fill.
 * - **Selection is never a fill** (rule 3): tv-material fills a [selected] item's container; here it stays the row's
 *   own colour and a tick says it.
 * - No icon before the words (tvOS's F13: each symbol had its own width, so no two labels lined up). After them,
 *   [trailing] (a value), the tick, and a chevron where the row opens a page ([chevron]).
 * - [supporting]: a line under the words, in part strength.
 * - Tagged with [tag] on the merged node, which reads the row's words; `ReportRecomposition("row <words>")`.
 */
@Composable
fun HitherListItem(
    headline: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = headline,
    enabled: Boolean = true,
    busy: Boolean = false,
    selected: Boolean = false,
    destructive: Boolean = false,
    chevron: Boolean = false,
    supporting: String? = null,
    headlineWeight: FontWeight? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ReportRecomposition("row $headline")
    val click = rememberTvClick(onClick, enabled = enabled, busy = busy, scale = RowFocusScale)
    val words = if (destructive) Tokens.Destructive else Tokens.Text
    val focusedWords = if (destructive) Tokens.DestructiveFocused else Tokens.FieldFocusedText
    ListItem(
        selected = selected,
        onClick = click.onClick,
        headlineContent = {
            Text(
                headline,
                style = HitherType.Body.let { if (headlineWeight != null) it.copy(fontWeight = headlineWeight) else it },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier.then(click.modifier).testTag(tag),
        enabled = enabled,
        supportingContent = supporting?.let { line ->
            {
                Text(
                    line,
                    style = HitherType.Caption,
                    color = LocalContentColor.current.copy(alpha = RowSecondaryAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = if (trailing == null && !selected && !chevron) {
            null
        } else {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    trailing?.invoke()
                    if (selected) {
                        Icon(HitherIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp).testTag("tick"))
                    }
                    if (chevron) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp).testTag("chevron"),
                            tint = LocalContentColor.current.copy(alpha = RowSecondaryAlpha),
                        )
                    }
                }
            }
        },
        shape = ListItemDefaults.shape(shape = RowShape),
        colors = ListItemDefaults.colors(
            containerColor = Tokens.Field,
            contentColor = words,
            focusedContainerColor = Tokens.FieldFocused,
            focusedContentColor = focusedWords,
            pressedContainerColor = Tokens.FieldFocused,
            pressedContentColor = focusedWords,
            // rule 3: the chosen row keeps the row's own colours; the tick says it
            selectedContainerColor = Tokens.Field,
            selectedContentColor = words,
            disabledContainerColor = Tokens.Field,
            disabledContentColor = words.copy(alpha = 0.5f),
            focusedSelectedContainerColor = Tokens.FieldFocused,
            focusedSelectedContentColor = focusedWords,
            pressedSelectedContainerColor = Tokens.FieldFocused,
            pressedSelectedContentColor = focusedWords,
        ),
        // tv-material's own scale is read in composition (H2): held at 1, the layer-phase scale is [click]'s
        scale = ListItemScale.None,
        border = ListItemDefaults.border(
            border = Border.None,
            focusedBorder = Border.None,
            pressedBorder = Border.None,
            selectedBorder = Border.None,
            disabledBorder = Border.None,
            focusedSelectedBorder = Border.None,
            focusedDisabledBorder = Border.None,
            pressedSelectedBorder = Border.None,
        ),
        interactionSource = click.interaction,
    )
}

/** A value on a row's right ([HitherListItem]'s trailing, `SettingsValueRow`'s): the row's colour, part strength. */
@Composable
fun RowValue(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = HitherType.Body,
    colour: Color = LocalContentColor.current.copy(alpha = RowSecondaryAlpha),
) {
    Text(text, style = style, color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}
