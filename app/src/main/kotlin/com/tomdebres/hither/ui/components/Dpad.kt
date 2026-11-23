package com.tomdebres.hither.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import com.tomdebres.hither.ui.theme.HitherTheme

/**
 * Why this file exists. Android offers every hardware key to the on-screen keyboard's app
 * before the focused window sees it. A keyboard that is hidden is supposed to pass them on;
 * the one on the API 31 TV emulator sometimes does not, and swallows Select and the arrows
 * while Enter gets through, which leaves a D-pad user with a screen that ignores them. Hither
 * does not depend on the keyboard's manners: **while no field is being typed in, the D-pad
 * is handled here, ahead of the keyboard.** While a field is open the keys go to the keyboard
 * as usual (its own keys are walked with the D-pad), except Back, which closes the field.
 */

/** Whether some field has the keyboard open. Set by [EntryField], read by [DpadHost]. */
internal val LocalTyping = compositionLocalOf<MutableState<Boolean>> { mutableStateOf(false) }

/**
 * How many D-pad presses (the arrows and Select) the screen has had, counted by [DpadHost] on both paths: ahead of the
 * keyboard (a remote) and as key events (a test's key input, which passes the keyboard's stage by). [DpadHost] takes the
 * arrows itself and moves focus, so nothing under it sees them as key events: a screen that must tell its own arrival
 * from the user's first press (Discover's start, A2) reads this, at the moment it asks. Not state.
 */
class DpadPresses {
    var count: Int = 0
        internal set
}

val LocalDpadPresses = staticCompositionLocalOf { DpadPresses() }

/**
 * Wraps a screen: Up, Down, Left and Right move focus ahead of the keyboard unless a field is open. It is the root of
 * every screen, the app's and a device test's, so it also gives them tv-material's theme (`HitherTheme`, A1): a test
 * that shows one screen draws it as the app does.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DpadHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) = HitherTheme {
    val typing = remember { mutableStateOf(false) }
    val presses = remember { DpadPresses() }
    val focus = LocalFocusManager.current
    CompositionLocalProvider(LocalTyping provides typing, LocalDpadPresses provides presses) {
        Box(
            modifier
                .onPreInterceptKeyBeforeSoftKeyboard { event ->
                    if (event.type == KeyEventType.KeyDown && (event.direction() != null || event.isSelect)) presses.count++
                    val direction = event.direction()
                    if (typing.value || direction == null) {
                        false
                    } else {
                        // One step per press, and per repeat while held; nothing on release.
                        if (event.type == KeyEventType.KeyDown) focus.moveFocus(direction)
                        true
                    }
                }
                // a test's key input, which passes the keyboard's stage by (counted once: the stage above consumed a remote's)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && (event.direction() != null || event.isSelect)) presses.count++
                    false
                },
        ) { content() }
    }
}

private fun KeyEvent.direction(): FocusDirection? = when (key) {
    Key.DirectionUp -> FocusDirection.Up
    Key.DirectionDown -> FocusDirection.Down
    Key.DirectionLeft -> FocusDirection.Left
    Key.DirectionRight -> FocusDirection.Right
    else -> null
}

internal val KeyEvent.isSelect: Boolean
    get() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/**
 * Select on a focused control, taken ahead of the keyboard. One press is one [onSelect]: it
 * fires on release, only if the press began on this control, and a held key's repeats do
 * nothing (REVIEW.md: a remote's Select repeats). Put it before the control's `clickable`,
 * which stays for pointers and accessibility.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.selectBeforeKeyboard(enabled: Boolean = true, onSelect: () -> Unit): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    this
        .onFocusChanged { if (!it.isFocused) pressed = false }
        .onInterceptKeyBeforeSoftKeyboard { event ->
            if (!enabled || !event.isSelect) {
                false
            } else {
                when (event.type) {
                    KeyEventType.KeyDown -> pressed = true
                    KeyEventType.KeyUp -> if (pressed) {
                        pressed = false
                        onSelect()
                    }
                }
                true
            }
        }
}

/**
 * Gives [requester] focus when a screen arrives. It waits a frame first: when this screen
 * replaces one whose focused control is being removed, that removal clears focus after this
 * screen's first composition, and a request made before it is lost.
 */
@Composable
fun FocusOnArrival(requester: FocusRequester) {
    LaunchedEffect(requester) {
        withFrameNanos { }
        requester.requestFocus()
    }
}
