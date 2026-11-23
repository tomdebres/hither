package com.tomdebres.hither.ui.theme

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.ui.components.LocalEntryLift
import com.tomdebres.hither.ui.components.liftAboveKeyboard
import com.tomdebres.hither.ui.components.rememberEntryLift

// A form of choices from the right (H2's X6, A5; tvOS's `Theme/FormSheet.swift`). tvOS shows a form in the system's
// sheet, centred; Android TV's own place for one is a panel along the right edge, as Google TV's settings panels are.
// tv-material has neither, so Hither draws it in the window, as its dialogs are drawn (`Dialogs.kt`).

/**
 * A form of choices in a panel that slides in from the right (H2's X6; tvOS's `FormSheet`): [Metrics.SidePanel] wide,
 * the screen's full height, over the page darkened. At its top the [title]; under it, scrolling, [content]: rows of
 * choices ([PanelChoice], ticked where on, never filled), rows that open a list of their own choices inside the panel
 * ([PanelPick]), headers ([PanelHeader]), sentences ([PanelNote]) and a field to type in (`EntryField`, kept above the
 * keyboard while it is open); and last, at its foot, [action], the form's one prominent button ([PanelAction]).
 *
 * - **Focus** arrives on [entry], else on the first row; it stays inside the panel while it is [open]: a move by the
 *   remote never leaves it. A move asked for by name may (a `ProblemDialog` over it taking its OK).
 * - **Back** closes an open list onto its row; else it is [onClose], and the caller closes the panel. There is no
 *   Cancel. Once closed, focus goes back to [opener], the control that opened it, where given; a caller that knows
 *   better (the season card a request came from) passes none and puts it back itself.
 * - It slides in over 250 ms, in the layer phase only, and goes at once, so focus is back at once.
 * - Tagged [tag] on the panel itself; its title is `"panel title"`. Kept composed while closed, so it can hand focus back.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SidePanel(
    open: Boolean,
    title: String,
    onClose: () -> Unit,
    opener: FocusRequester?,
    tag: String,
    modifier: Modifier = Modifier,
    entry: FocusRequester? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable SidePanelScope.() -> Unit,
) {
    PanelReturnsFocus(open, opener)
    if (!open) return
    val scope = remember { SidePanelScope() }
    val rows = remember { FocusRequester() }
    val list = scope.list
    BackHandler { if (scope.list != null) scope.closeList() else onClose() }
    LaunchedEffect(Unit) {
        repeat(10) {
            withFrameNanos { }
            if (runCatching { (entry ?: rows).requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    // An open list's row takes focus back as the list closes.
    LaunchedEffect(scope.backTo) {
        val back = scope.backTo ?: return@LaunchedEffect
        repeat(10) {
            withFrameNanos { }
            if (runCatching { back.requestFocus() }.getOrDefault(false)) {
                scope.backTo = null
                return@LaunchedEffect
            }
        }
        scope.backTo = null
    }
    val slide = remember { Animatable(0f) }
    LaunchedEffect(Unit) { slide.animateTo(1f, tween(SLIDE_MS, easing = FastOutSlowInEasing)) }
    val lift = rememberEntryLift()
    // The page's own scroll rule (a title page's pivot) is not the panel's: a row comes into view by the least move.
    CompositionLocalProvider(LocalBringIntoViewSpec provides PanelScroll, LocalEntryLift provides lift) {
        Overlay(Modifier.graphicsLayer { alpha = slide.value }) {
            Column(
                modifier
                    .align(Alignment.CenterEnd)
                    .graphicsLayer { translationX = (1f - slide.value) * size.width }
                    .liftAboveKeyboard(lift)
                    .fillMaxHeight()
                    .width(Metrics.SidePanel)
                    .background(Tokens.Background)
                    .testTag(tag)
                    // Focus stays in the panel: a move by the remote that would leave it is refused (REVIEW.md: a modal
                    // takes focus). A move asked for by name is let through: a dialog over the panel takes its own.
                    .focusProperties { onExit = { if (requestedFocusDirection.isMove) cancelFocusChange() } }
                    .focusGroup()
                    .padding(start = PanelInset, end = Metrics.SideMargin, top = Metrics.TopMargin, bottom = Metrics.BottomMargin),
            ) {
                BasicText(
                    list?.title ?: title,
                    style = HitherType.Title.copy(color = Tokens.Text),
                    maxLines = 1,
                    modifier = Modifier.padding(start = RowRoom).testTag("panel title"),
                )
                Spacer(Modifier.height(PanelTitleGap))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // The rows stay composed under an open list, so the row that opened it is there to take focus back;
                    // meanwhile they are not drawn, read or reached.
                    Column(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (scope.list != null) 0f else 1f }
                            .semantics { if (scope.list != null) hideFromAccessibility() }
                            .focusRequester(rows)
                            .focusProperties { onEnter = { if (scope.list != null) cancelFocusChange() } }
                            .focusGroup()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = RowRoom, vertical = RowRoom),
                        verticalArrangement = Arrangement.spacedBy(PanelRowGap),
                    ) {
                        scope.content()
                    }
                    if (list != null) PanelListView(list, scope)
                }
                if (action != null && list == null) {
                    Spacer(Modifier.height(PanelActionGap))
                    Box(Modifier.padding(horizontal = RowRoom)) { action() }
                }
            }
        }
    }
}

/**
 * What a [SidePanel]'s content is built from: its rows ([PanelChoice], [PanelPick]), headers ([PanelHeader]) and
 * sentences ([PanelNote]). It knows the list a [PanelPick] has open in the panel.
 */
@Stable
class SidePanelScope internal constructor() {
    internal var list: PanelList? by mutableStateOf(null)
        private set

    /** The row that opened the list that has just closed, to take focus back. */
    internal var backTo: FocusRequester? by mutableStateOf(null)

    internal fun openList(next: PanelList) {
        list = next
    }

    internal fun closeList() {
        backTo = list?.row
        list = null
    }
}

/** One of a [PanelPick]'s choices: its [value], and its [words] in the list and on the row. */
data class PanelOption<T>(val value: T, val words: String)

/** A [PanelPick]'s list, open in the panel: its title, its choices' words, the chosen one, and the row it came from. */
internal class PanelList(val title: String, val words: List<String>, val chosen: Int, val tag: String, val row: FocusRequester, val onPick: (Int) -> Unit)

/**
 * A section's header in a [SidePanel] (tvOS's `FormHeader`): what the rows under it are about (the title a request is
 * for, "What's wrong?"), in its own words' case, secondary.
 */
@Composable
fun SidePanelScope.PanelHeader(words: String, modifier: Modifier = Modifier, tag: String? = null) {
    BasicText(
        words,
        style = HitherType.Caption.copy(color = Tokens.TextSecondary),
        modifier = modifier
            .padding(top = 4.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

/**
 * Sentences under a [SidePanel]'s rows (a section's footer on tvOS): what the choices will come to ("This request needs
 * no approval.", the quota), secondary.
 */
@Composable
fun SidePanelScope.PanelNote(words: String, modifier: Modifier = Modifier, tag: String? = null) {
    BasicText(
        words,
        style = HitherType.Caption.copy(color = Tokens.TextSecondary),
        modifier = modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

/**
 * A choice in a [SidePanel] (tvOS's `FormChoice`): tv-material's `ListItem` ([HitherListItem]), its [words], a tick at
 * its right where it is [on] (rule 3: never a fill), Select to [onSelect] (to flip it, or to pick it). [ticks] false: no
 * tick where something else says it is on ([status], a season in the library). Disabled ([enabled] false) it is dimmed
 * and no stop for focus. [inView]: brought into view as the panel opens (the season a card chose, while focus is on the
 * action). TalkBack and the tests read "on" or "off".
 */
@Composable
fun SidePanelScope.PanelChoice(
    words: String,
    on: Boolean,
    onSelect: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    ticks: Boolean = true,
    inView: Boolean = false,
    status: (@Composable () -> Unit)? = null,
) {
    val view = remember { BringIntoViewRequester() }
    if (inView) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            view.bringIntoView()
        }
    }
    HitherListItem(
        headline = words,
        onClick = onSelect,
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(view)
            .semantics { stateDescription = if (on) "on" else "off" },
        tag = tag,
        enabled = enabled,
        selected = on && ticks,
        trailing = status,
    )
}

/**
 * One of a few, in a [SidePanel] (tvOS's `FormPick`): a row with its [title] at the left and the choice at its right,
 * and a chevron. Select opens [options] as a list of their own in the panel, under the [title], the current one ticked
 * and focused; Select on one picks it ([onPick], only for a change) and goes back to the row, and Back goes back without.
 * The row is tagged [tag] and carries the choice as its state; the list is `"<tag> list"`, each choice in it
 * `"<tag>: <words>"`. Disabled ([enabled] false), it is dimmed and no stop.
 */
@Composable
fun <T> SidePanelScope.PanelPick(
    title: String,
    options: List<PanelOption<T>>,
    selection: T,
    onPick: (T) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val row = remember { FocusRequester() }
    val chosen = options.indexOfFirst { it.value == selection }
    val words = options.getOrNull(chosen)?.words.orEmpty()
    HitherListItem(
        headline = title,
        onClick = {
            openList(
                PanelList(title, options.map { it.words }, chosen, tag, row) { index ->
                    val picked = options[index].value
                    if (picked != selection) onPick(picked)
                },
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(row)
            .semantics { stateDescription = words },
        tag = tag,
        enabled = enabled,
        chevron = true,
        trailing = { RowValue(words, Modifier.testTag("$tag value")) },
    )
}

/**
 * A [SidePanel]'s action (tvOS's `FormAction`): the form's one prominent button ([ProminentButton], rule 1), as wide as
 * the rows, its words centred. While what it does is out ([busy]: "Requesting…", "Submitting…") it keeps focus and
 * does nothing (M2: a disabled control would drop it).
 */
@Composable
fun PanelAction(
    words: String,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    ProminentButton(
        text = words,
        onClick = onClick,
        enabled = enabled || busy,
        busy = busy,
        centred = true,
        modifier = modifier.fillMaxWidth().testTag(tag),
    )
}

/**
 * A [PanelPick]'s list, over the panel's rows: each choice a [HitherListItem], the current one ticked and focused as it
 * opens. Lazy, as a series' episodes can run long. Focus stays in the list until a choice or Back closes it.
 */
@Composable
private fun PanelListView(list: PanelList, scope: SidePanelScope) {
    val items = remember(list) { list.words.map { FocusRequester() } }
    val start = list.chosen.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (start - LIST_LEAD).coerceAtLeast(0))
    LaunchedEffect(list) {
        repeat(10) {
            withFrameNanos { }
            if (runCatching { items[start].requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(RowRoom),
        verticalArrangement = Arrangement.spacedBy(PanelRowGap),
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.Background)
            .focusProperties { onExit = { if (requestedFocusDirection.isMove) cancelFocusChange() } }
            .focusGroup()
            .testTag("${list.tag} list"),
    ) {
        itemsIndexed(list.words) { index, words ->
            HitherListItem(
                headline = words,
                onClick = {
                    list.onPick(index)
                    scope.closeList()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(items[index])
                    .semantics { stateDescription = if (index == list.chosen) "on" else "off" },
                tag = "${list.tag}: $words",
                selected = index == list.chosen,
            )
        }
    }
}

/**
 * After the panel has closed, focus goes back to [opener] a frame later, once the panel's rows have left (when a focused
 * control goes, Compose puts focus on the page's first focusable, DECISIONS 149). As the dialogs' own (`Dialogs.kt`).
 */
@Composable
private fun PanelReturnsFocus(open: Boolean, opener: FocusRequester?) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) {
            shown = true
        } else if (shown) {
            shown = false
            withFrameNanos { }
            opener?.let { runCatching { it.requestFocus() } }
        }
    }
}

/** A move by the remote (or a test's key), not one asked for by name (`requestFocus`, which is Enter). */
private val FocusDirection.isMove: Boolean
    get() = this == FocusDirection.Up || this == FocusDirection.Down || this == FocusDirection.Left ||
        this == FocusDirection.Right || this == FocusDirection.Next || this == FocusDirection.Previous

/** The panel's rows scroll by the least move that shows the focused one whole (the platform's own rule). */
@OptIn(ExperimentalFoundationApi::class)
private val PanelScroll = object : BringIntoViewSpec {}

/** How long the panel takes to slide in. */
private const val SLIDE_MS = 250

/** Choices shown above the chosen one as a list opens. */
private const val LIST_LEAD = 3

/** The panel's inner edge to its words (its outer edge keeps the screen's side margin). */
private val PanelInset = 24.dp

/** Room round the rows for a focused row's lift (1.02 of the row's width) inside the scroll's clip. */
private val RowRoom = 6.dp

/** Under the panel's title, between its rows (tvOS's 12 points), and above its action. */
private val PanelTitleGap = 14.dp
private val PanelRowGap = 8.dp
private val PanelActionGap = 16.dp
