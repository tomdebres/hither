package com.tomdebres.hither.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.tomdebres.hither.R
import com.tomdebres.hither.core.rules.PIN_LENGTH
import com.tomdebres.hither.core.rules.PinEntry as Typed
import com.tomdebres.hither.ui.theme.HitherType
import com.tomdebres.hither.ui.theme.Overlay
import com.tomdebres.hither.ui.theme.Tokens
import com.tomdebres.hither.ui.theme.rememberTvClick

/**
 * Where the admin's PIN is typed (H2's X8): the whole screen, black, as the Apple TV app's digit entry is full screen
 * (Android has none of its own, so the pad stays Hither's): the [title], the [line] under it (what it is for, or what
 * went wrong: red when [trouble]), four dots and the pad ([PinPad]). Choosing the PIN twice and the switch to the admin
 * use it. Focus cannot leave it; Back is the pad's (a digit off, then [onBack]); the remote's digit keys type.
 *
 * Over everything, in an `Overlay` at full strength: nothing of the page under it shows, nor takes focus.
 */
@Composable
fun PinEntry(
    title: String,
    line: String,
    onPin: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trouble: Boolean = false,
    /** Clears what is typed when it changes (the next entry of the PIN, a wrong try). */
    entryKey: Any = Unit,
    enabled: Boolean = true,
    tag: String = "pin entry",
    titleTag: String = "pin title",
    lineTag: String = "pin note",
) {
    Overlay(modifier.testTag(tag), dim = 1f) {
        Column(
            Modifier
                .align(Alignment.Center)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                title,
                style = HitherType.Title.copy(color = Tokens.Text, textAlign = TextAlign.Center),
                modifier = Modifier.semantics { heading() }.testTag(titleTag),
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                line,
                style = HitherType.Body.copy(color = if (trouble) Tokens.ErrorText else Tokens.TextSecondary, textAlign = TextAlign.Center),
                modifier = Modifier.widthIn(max = LineWidth).testTag(lineTag),
            )
            Spacer(Modifier.height(28.dp))
            PinPad(onPin = onPin, onBack = onBack, entryKey = entryKey, enabled = enabled)
        }
    }
}

/** The line under the title: at most a short paragraph's width, centred. */
private val LineWidth = 480.dp

/**
 * A D-pad number pad for the admin's PIN (M5, DECISIONS 92): four places, then the keys as a phone has them (1 to 9 in
 * threes, then delete and 0), tv-material's own buttons, round. Focus arrives on 5, in the middle, so every key is two
 * moves away at most, and stays on the key pressed, so a repeated digit is one more Select. The remote's own number keys
 * type too. The fourth digit hands the PIN over at once: there is no OK key to find. Back takes the last digit off, and
 * with none typed leaves through [onBack].
 *
 * The digits are never drawn and never in the semantics: the places say how many are typed. A focus move recomposes the
 * two keys it touches; a digit, the places.
 */
@Composable
fun PinPad(
    onPin: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Clears what is typed when it changes (the next entry of the PIN). */
    entryKey: Any = Unit,
    enabled: Boolean = true,
) {
    ReportRecomposition("pin pad")
    var entry by remember(entryKey) { mutableStateOf(Typed()) }
    val five = remember { FocusRequester() }
    FocusOnArrival(five)
    val type = { digit: Int ->
        if (enabled) {
            val next = entry.type(digit)
            if (next.full) {
                entry = Typed()
                onPin(next.digits)
            } else {
                entry = next
            }
        }
    }
    val delete = { entry = entry.delete() }
    BackHandler { if (entry.digits.isNotEmpty()) delete() else onBack() }
    Column(
        modifier
            .onPreviewKeyEvent { event ->
                // A remote with number keys types straight in; its delete key takes one off.
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = NumberKeys.indexOf(event.key)
                when {
                    digit >= 0 -> { type(digit); true }
                    event.key == Key.Backspace || event.key == Key.Delete -> { delete(); true }
                    else -> false
                }
            }
            .testTag("pin pad"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Places(count = { entry.digits.length })
        Spacer(Modifier.size(28.dp))
        Column(
            // During a wait the keys take nothing, so they look it: half strength (the polish 2 audit's item 29). Focus
            // stays where it is: a key is never disabled, it only does nothing.
            Modifier.focusGroup().alpha(if (enabled) 1f else 0.5f).semantics { if (!enabled) disabled() }.testTag("pin keys"),
            verticalArrangement = Arrangement.spacedBy(KeyGap),
        ) {
            for (row in Rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                    for ((column, key) in row.withIndex()) {
                        val first = row.indexOfFirst { it != BLANK }
                        val fence = Modifier.focusProperties {
                            if (column == first) left = FocusRequester.Cancel
                            if (column == row.lastIndex) right = FocusRequester.Cancel
                        }
                        when (key) {
                            BLANK -> Spacer(Modifier.size(KeySize))
                            DELETE -> PadKey(
                                label = stringResource(R.string.pin_delete),
                                onSelect = { delete() },
                                modifier = fence.testTag("pin delete"),
                                icon = true,
                            )
                            else -> PadKey(
                                label = key.toString(),
                                onSelect = { type(key) },
                                modifier = fence.then(if (key == 5) Modifier.focusRequester(five) else Modifier).testTag("pin $key"),
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val DELETE = -1
private const val BLANK = -2

/** A phone's pad: 0 in the middle of the last row, delete on its right, the left empty (the polish 2 audit's item 28). */
private val Rows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(BLANK, 0, DELETE))
private val NumberKeys = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
private val KeySize = 56.dp
private val KeyGap = 16.dp

/** The places: four dots, a ring until a digit is typed in it, then filled white. The count is all they show. */
@Composable
private fun Places(count: () -> Int) {
    ReportRecomposition("pin places")
    val typed = count()
    val said = stringResource(R.string.pin_typed, typed, PIN_LENGTH)
    Row(
        Modifier.semantics { contentDescription = said }.testTag("pin places"),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        repeat(PIN_LENGTH) { place ->
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .then(if (place < typed) Modifier.background(Tokens.Text) else Modifier)
                    .border(1.5.dp, if (place < typed) Tokens.Text else Tokens.TextSecondary, CircleShape)
                    .testTag(if (place < typed) "pin place filled" else "pin place"),
            )
        }
    }
}

/**
 * A key: tv-material's `Button`, round, in the theme's colours (a grey disc at rest, white with black words with
 * focus), kept to Hither's rules as every tv-material clickable is ([rememberTvClick]): Select once per press, the
 * focus scale in the layer phase, focus shown from the start.
 */
@Composable
private fun PadKey(label: String, onSelect: () -> Unit, modifier: Modifier = Modifier, icon: Boolean = false) {
    ReportRecomposition("pin key $label")
    val click = rememberTvClick(onSelect)
    Button(
        onClick = click.onClick,
        modifier = modifier.then(click.modifier).size(KeySize).semantics { contentDescription = label },
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(CircleShape),
        contentPadding = PaddingValues(0.dp),
        interactionSource = click.interaction,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon) {
                Icon(HitherIcons.Delete, contentDescription = null, modifier = Modifier.size(22.dp))
            } else {
                Text(label, style = HitherType.Title)
            }
        }
    }
}
