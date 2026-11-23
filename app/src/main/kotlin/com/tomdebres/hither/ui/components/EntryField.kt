package com.tomdebres.hither.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.ui.theme.EntryProblem
import com.tomdebres.hither.ui.theme.HitherType
import com.tomdebres.hither.ui.theme.Metrics
import com.tomdebres.hither.ui.theme.Tokens

/** A field's focus handle and whether its keyboard is open; lets one field hand on to the next. */
@Stable
class EntryFieldState {
    val focus = FocusRequester()
    var editing by mutableStateOf(false)
        private set

    /** Open the keyboard on this field. */
    fun edit() {
        editing = true
    }

    internal fun done() {
        editing = false
    }
}

@Composable
fun rememberEntryFieldState(): EntryFieldState = remember { EntryFieldState() }

/**
 * A text field at ten feet, as the Apple TV app's (H2's X4; tvOS's `EntryField`): a capsule [Metrics.EntryField] high,
 * the column's width, the words [Metrics.EntryFieldInset] in. At rest a form row's grey ([Tokens.Field]) with the
 * placeholder in grey; with focus, or while its keyboard is open, the focused white ([Tokens.FieldFocused]) with black
 * words and a placeholder that reads on it (5.6:1). No edge, no shadow and no lift: the white is the focus. [error]
 * shows as red words under it, never a row ([EntryProblem], tagged [errorTag]).
 *
 * Made for a D-pad, as `SeerrField` was before it (M1, the polish audit): the field is one focus target, and **Select
 * opens the on-screen keyboard** for it. Moving across a field does not open the keyboard, so Down from the first field
 * reaches the button without three keyboards on the way. The keyboard's action key closes it and calls [onImeAction]
 * (Next opens the next field, Done lands on the button); Back closes it and stays on the field. Inside an
 * `EntryColumn`, the open field is kept above the keyboard ([EntryLift]).
 *
 * [search]: search's field (A8), the same capsule with a magnifier before the words, as the Apple TV app's search field
 * has one ([Metrics.SearchGlyph]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EntryField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    state: EntryFieldState = rememberEntryFieldState(),
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    password: Boolean = false,
    enabled: Boolean = true,
    tag: String = placeholder,
    error: String? = null,
    errorTag: String = "$tag error",
    search: Boolean = false,
    onImeAction: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) } // from the focus state, never an interaction source (DECISIONS 28)
    // Lit: drawn focused, while it has focus or its keyboard is open. The words' colour follows it in composition
    // (once each way); the fill eases in the draw phase.
    val lit = focused || state.editing
    val amount = focusAmount(lit)
    val inner = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val typing = LocalTyping.current
    val lift = LocalEntryLift.current
    var innerHadFocus by remember { mutableStateOf(false) }

    // Leaving edit mode: focus goes back to the field itself first, so it never lands on nothing.
    fun finish() {
        if (!state.editing) return
        state.focus.requestFocus()
        state.done()
        keyboard?.hide()
    }

    LaunchedEffect(state.editing) {
        typing.value = state.editing
        if (state.editing) {
            inner.requestFocus()
            keyboard?.show()
        } else {
            innerHadFocus = false
        }
    }
    DisposableEffect(lift) {
        onDispose {
            if (state.editing) typing.value = false
            lift?.forget(state)
        }
    }
    // Back while typing closes this field. Taken ahead of the keyboard, so that the keyboard
    // hiding itself can never leave the field open with nothing showing; the BackHandler is
    // for a Back that arrives the ordinary way.
    BackHandler(enabled = state.editing) { finish() }

    Column(modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag)
                .semantics { contentDescription = placeholder }
                // The white is the focus (tvOS's field), search's too (A8: Seerr's pill grew 3%)
                .focusScale(lit, scale = 1f, amount = amount)
                .height(Metrics.EntryField)
                .then(if (lift != null) Modifier.onGloballyPositioned { lift.place(state, it) } else Modifier)
                .clip(CircleShape)
                .focusFill(CircleShape, Tokens.Field, Tokens.FieldFocused, amount)
                .focusRequester(state.focus)
                .onFocusChanged { focused = it.isFocused }
                .onPreInterceptKeyBeforeSoftKeyboard { event ->
                    if (state.editing && event.key == Key.Back) {
                        if (event.type == KeyEventType.KeyUp) finish()
                        true
                    } else {
                        false
                    }
                }
                .selectBeforeKeyboard(enabled = enabled && !state.editing) { state.edit() }
                .clickable(interactionSource = interaction, indication = null, enabled = enabled) { state.edit() }
                .padding(start = if (search) Metrics.EntryFieldInset + Metrics.SearchGlyph + Metrics.SearchGlyphGap else Metrics.EntryFieldInset, end = Metrics.EntryFieldInset),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (search) {
                Image(
                    HitherIcons.Search,
                    contentDescription = null,
                    modifier = Modifier.offset(x = -(Metrics.SearchGlyph + Metrics.SearchGlyphGap)).size(Metrics.SearchGlyph),
                    colorFilter = ColorFilter.tint(if (lit) Tokens.FieldFocusedPrompt else Tokens.FieldPrompt),
                )
            }
            if (value.isEmpty()) {
                BasicText(
                    text = placeholder,
                    style = HitherType.Body,
                    color = focusColor(Tokens.FieldPrompt, Tokens.FieldFocusedPrompt, amount),
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("$tag input")
                    .focusRequester(inner)
                    .focusProperties { canFocus = state.editing }
                    .onFocusChanged {
                        if (it.isFocused) {
                            innerHadFocus = true
                        } else if (innerHadFocus && state.editing) {
                            // Focus moved away by D-pad while typing: the field is no longer being edited.
                            state.done()
                            keyboard?.hide()
                        }
                    },
                enabled = enabled,
                singleLine = true,
                textStyle = HitherType.Body.copy(color = if (lit) Tokens.FieldFocusedText else Tokens.Text),
                cursorBrush = SolidColor(Tokens.FieldFocusedText),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = if (password) KeyboardType.Password else keyboardType,
                    imeAction = imeAction,
                ),
                keyboardActions = KeyboardActions(onAny = {
                    finish()
                    onImeAction()
                }),
            )
        }
        if (error != null) EntryProblem(error, errorTag, Modifier.padding(top = 6.dp))
    }
}

/**
 * Keeps the field open for typing whole above the on-screen keyboard (DECISIONS 83, the polish audit's item 6). The
 * window does not pan ([keyboardAsInset]): the keyboard is an inset, and the page lifts itself by just enough that the
 * open field's foot sits [KeyboardGap] above the keyboard's top. Put [liftAboveKeyboard] on what is to move (the page,
 * its title with it: nothing is drawn over the title) and give the lift to the `EntryColumn` whose fields it watches.
 * The lift is read in the layer phase, so a field opening moves the page without recomposing it.
 */
@Stable
class EntryLift internal constructor(internal val ime: WindowInsets) {
    internal var within: LayoutCoordinates? = null
    private val places = mutableStateMapOf<EntryFieldState, LayoutCoordinates>()

    internal fun place(field: EntryFieldState, at: LayoutCoordinates) {
        if (places[field] !== at) places[field] = at
    }

    internal fun forget(field: EntryFieldState) {
        places.remove(field)
    }

    /** The open field's foot in the lifted content's coordinates, in pixels; -1 with none open or not laid out. */
    internal fun footOfOpen(): Float {
        val inside = within?.takeIf { it.isAttached } ?: return -1f
        val open = places.entries.firstOrNull { it.key.editing }?.value?.takeIf { it.isAttached } ?: return -1f
        return inside.localBoundingBoxOf(open, clipBounds = false).bottom
    }
}

@Composable
fun rememberEntryLift(): EntryLift {
    val ime = WindowInsets.ime
    return remember(ime) { EntryLift(ime) }
}

/** The lift, for the fields of an `EntryColumn` ([EntryLift]). */
val LocalEntryLift = compositionLocalOf<EntryLift?> { null }

/** Lifts what it is on by [lift]'s open field (see [EntryLift]); its own place is where the fields are measured from. */
fun Modifier.liftAboveKeyboard(lift: EntryLift): Modifier = this
    .graphicsLayer {
        val open = lift.footOfOpen()
        val keyboard = lift.ime.getBottom(this)
        translationY = if (open < 0f || keyboard == 0) 0f else -(open + KeyboardGap.toPx() - (size.height - keyboard)).coerceAtLeast(0f)
    }
    .onGloballyPositioned { lift.within = it }

/** Room between the open field's foot and the keyboard's top. */
private val KeyboardGap = 16.dp
