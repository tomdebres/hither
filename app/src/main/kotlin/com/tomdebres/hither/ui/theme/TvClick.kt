package com.tomdebres.hither.ui.theme

import android.view.ViewConfiguration
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.tomdebres.hither.ui.components.focusAmount
import com.tomdebres.hither.ui.components.focusScale
import com.tomdebres.hither.ui.components.isSelect

/**
 * What a tv-material clickable is handed to keep Hither's D-pad rules and test hooks (DECISIONS 294): the guards A1 wrote
 * into `TvButton` (`Buttons.kt`), in one place for every tv-material clickable: its `Button` (`TvButton` uses it since
 * A7), its `ListItem` (Settings' rows, `HitherListItem`), the PIN's keys, the bar's `Tab`, `IconButton` and `Button`
 * (A2, which wrote the same rules for the bar alongside A7 and folded them into this as it landed second), and the
 * lockups (A3).
 *
 * - [onClick]: once per press. Select is taken ahead of the on-screen keyboard, and so ahead of tv-material's own
 *   handler, which clicks on any key-up, even one whose press began on another control; where the keyboard's stage is
 *   passed by (a test's key input), [modifier]'s preview handler keeps the same rule. One press is followed on both
 *   paths by one [SelectPress].
 * - **Long press** (A6, DECISIONS 340; Android TV's "press and hold … more options"): with an `onLongClick`, Select held
 *   for the system's long-press time fires it, once, as it is held, and that press then fires no click; the held key's
 *   further repeats and its release do nothing. The remote's Menu key fires the same, where a remote has one (Fire TV's).
 *   tv-material's own long press never sees the key (it is taken first), so [onLongClick] is handed to the clickable for
 *   pointers and TalkBack only.
 * - Busy is focusable and inert; disabled takes no focus (tv-material would focus it).
 * - The focus scale in the layer phase ([Metrics.FocusScale] by default): the clickable's own is to be held at 1.
 * - [interaction]: hand it to the clickable, which draws its focus from it. A frame after focus comes, a stand-in focus
 *   is emitted on it too, so a collector that missed the focusable's own (DECISIONS 28's case; tv-material's, as a
 *   page arrives) still draws it; it is taken back when focus goes.
 * - [shown]: whether the clickable shows focus, as `ShowsFocus` says it to the tests.
 */
class TvClick internal constructor(
    val interaction: MutableInteractionSource,
    val shown: Boolean,
    val onClick: () -> Unit,
    val modifier: Modifier,
    /** The long press's action, for the clickable's own `onLongClick` (pointers, TalkBack); null where there is none. */
    val onLongClick: (() -> Unit)? = null,
)

/**
 * [TvClick] for one clickable: [onClick] once per press while [enabled] and not [busy], drawn at [scale] with focus;
 * [onLongClick], where given, on a long press of Select or the Menu key, and then no click.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun rememberTvClick(
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
    scale: Float = Metrics.FocusScale,
    onLongClick: (() -> Unit)? = null,
): TvClick {
    val interaction = remember { MutableInteractionSource() }
    val shown by interaction.collectIsFocusedAsState()
    val amount = focusAmount(shown)
    val act = { if (!busy) onClick() }
    val long = onLongClick?.let { action -> { if (!busy) action() } }
    // Focus as the focus system has it, said again to every collector of [interaction] a frame after it came. The
    // focusable's own Focus reaches only the collectors already listening: a control given focus as its page arrives
    // could be drawn by one and not the other (A7: the code page's Cancel had focus and Hither's scale, and tv-material
    // drew it at rest). A frame on, tv-material's collector has started. The stand-in goes when focus does; while it
    // and the focusable's own are both there, nobody's state changes, so nothing recomposes for it.
    var focused by remember { mutableStateOf(false) }
    val standIn = remember { arrayOfNulls<FocusInteraction.Focus>(1) }
    LaunchedEffect(focused) {
        if (focused) {
            withFrameNanos { }
            if (standIn[0] == null) standIn[0] = FocusInteraction.Focus().also { interaction.emit(it) }
        } else {
            standIn[0]?.let { interaction.emit(FocusInteraction.Unfocus(it)) }
            standIn[0] = null
        }
    }
    val press = remember { SelectPress(ViewConfiguration.getLongPressTimeout().toLong()) }
    val key: (KeyEvent) -> Boolean = { event ->
        when {
            !enabled -> false
            // The remote's own key for more (Fire TV's Menu): the long press's action, where there is one.
            event.key == Key.Menu -> if (long == null) {
                false
            } else {
                if (event.type == KeyEventType.KeyUp) long()
                true
            }
            !event.isSelect -> false
            else -> {
                val native = event.nativeKeyEvent
                when (event.type) {
                    KeyEventType.KeyDown -> if (press.down(native.repeatCount, native.downTime, native.eventTime, native.isLongPress, long != null)) long?.invoke()
                    KeyEventType.KeyUp -> when (press.up(native.eventTime, long != null)) {
                        SelectPress.Ends.CLICK -> act()
                        SelectPress.Ends.LONG -> long?.invoke()
                        SelectPress.Ends.NOTHING -> Unit
                    }
                }
                true
            }
        }
    }
    val modifier = Modifier
        .focusProperties { canFocus = enabled }
        .onFocusChanged {
            focused = it.isFocused
            if (!it.isFocused) press.cancel()
        }
        .focusScale(shown, scale = scale, amount = amount)
        .alpha(if (busy) 0.5f else 1f)
        // a remote's keys, ahead of the on-screen keyboard (`Dpad.kt`); a test's key input passes that stage by
        .onInterceptKeyBeforeSoftKeyboard(key)
        .onPreviewKeyEvent(key)
    return TvClick(interaction, shown, act, modifier, long)
}

/**
 * One press of Select on one control, from its key-down to its key-up (A6, DECISIONS 340). Not state: it is read and
 * written at key events only. A press begins only with a first key-down on this control (repeat 0), so a key held while
 * focus came to it, or a press begun on another control (a dialog that a long press opened, its key still down), does
 * nothing here.
 */
internal class SelectPress(private val longPressMs: Long) {
    /** What a press's release does. */
    enum class Ends { CLICK, LONG, NOTHING }

    /** When the press under way went down (the key event's down time), or -1 with none under way. */
    private var downAt = -1L

    /** The press under way has been a long press, and fired it. */
    private var wasLong = false

    /**
     * A key-down: the first starts a press; a held key's repeat is the long press when the system marks it so (its first
     * repeat, at the long-press time) or it has been held that long. True when this one is the long press, once; with no
     * long action ([hasLong] false) a repeat is nothing.
     */
    fun down(repeat: Int, downTime: Long, eventTime: Long, systemLong: Boolean, hasLong: Boolean): Boolean {
        if (repeat == 0) {
            downAt = downTime
            wasLong = false
            return false
        }
        if (downAt < 0 || wasLong || !hasLong) return false
        if (systemLong || eventTime - downAt >= longPressMs) {
            wasLong = true
            return true
        }
        return false
    }

    /**
     * The release: the click of a press begun here; nothing after a long press, or for a press that did not begin here; a
     * long press for one held the long-press time without the remote repeating the key (some do not), where there is a
     * long action.
     */
    fun up(eventTime: Long, hasLong: Boolean): Ends {
        val began = downAt
        val long = wasLong
        cancel()
        return when {
            began < 0 || long -> Ends.NOTHING
            hasLong && eventTime - began >= longPressMs -> Ends.LONG
            else -> Ends.CLICK
        }
    }

    /** Focus has gone: whatever press was under way ends with nothing. */
    fun cancel() {
        downAt = -1L
        wasLong = false
    }
}
