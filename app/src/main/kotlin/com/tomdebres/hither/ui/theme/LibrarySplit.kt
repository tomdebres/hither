package com.tomdebres.hither.ui.theme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.tomdebres.hither.R
import com.tomdebres.hither.ui.components.FocusOnArrival
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// The lists as tvOS's Library (H2's A6; tvOS's `Theme/LibrarySplit.swift`, its DECISIONS 259; DECISIONS 338): My
// Requests, Issues and the approval queue are each a narrow side list on the left (what to show, and the ways in) and a
// grid of the app's poster lockups on the right, as the Apple TV app's Library lays out its categories and their titles.
// Before this they were Seerr's web rows (`RequestItem`, `IssueItem`), with coloured buttons beside them and filters as
// pills.

/** A list's lockups across its grid: tvOS's four (its grid's six, less the side list's room). */
const val LIST_COLUMNS = 4

/** A list lockup's whole height: the poster and its status caption (three lines under it). */
val StatusLockupHeight: Dp = Metrics.PosterHeight + Metrics.StatusCaptionHeight

/**
 * The split (tvOS's `LibrarySplit`): [side] in a column [Metrics.SideList] wide at the left margin, and [content] (a
 * [LockupGrid], or the state in its place) in the rest, to the right margin. Both start [top] from the split's top: the
 * side list's first row on the grid's first line of pictures.
 *
 * Focus: each side is a group of its own. Left from anywhere in [content] lands on [sideEntry] (the chosen row), not
 * the nearest row; Right from the side list lands on [contentEntry] (the lockup left, or the state's action), not the
 * nearest lockup, and nowhere while there is none (a list on its way). Down from the side list's last row, and Right
 * from the content, go nowhere: the bar's controls up to the right are never the next stop. Focus asked for by name is
 * never redirected (a dialog giving it back, a hand-off).
 */
@Composable
fun LibrarySplit(
    top: Dp,
    sideEntry: FocusRequester,
    contentEntry: () -> FocusRequester?,
    modifier: Modifier = Modifier,
    side: @Composable ColumnScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Row(modifier.fillMaxSize()) {
        Column(
            Modifier
                .padding(start = Metrics.SideMargin, top = top)
                .width(Metrics.SideList)
                .focusProperties {
                    onEnter = { if (requestedFocusDirection == FocusDirection.Left) runCatching { sideEntry.requestFocus() } }
                    // Right goes into the content, on its entry, or nowhere (never to the bar's controls up to the right);
                    // Down from the last row and Left go nowhere. Up is the page's (its tab on the bar).
                    onExit = {
                        when (requestedFocusDirection) {
                            FocusDirection.Right -> contentEntry()?.let { runCatching { it.requestFocus() } } ?: cancelFocusChange()
                            FocusDirection.Down, FocusDirection.Left -> cancelFocusChange()
                            else -> Unit
                        }
                    }
                }
                .focusGroup()
                .testTag("side list"),
            verticalArrangement = Arrangement.spacedBy(SideRowGap),
            content = side,
        )
        Spacer(Modifier.width(Metrics.SplitGap))
        Box(
            Modifier
                .fillMaxHeight()
                .weight(1f)
                .padding(end = Metrics.SideMargin)
                .focusProperties {
                    onEnter = {
                        if (requestedFocusDirection == FocusDirection.Right) contentEntry()?.let { runCatching { it.requestFocus() } }
                    }
                    // nothing lies right of the content: Right from it never reaches the bar's controls up to the right
                    onExit = { if (requestedFocusDirection == FocusDirection.Right) cancelFocusChange() }
                }
                .focusGroup(),
            content = content,
        )
    }
}

/** Between the side list's rows (tvOS's 12 points, and room for a row's 2% lift). */
private val SideRowGap = 8.dp

/**
 * A row of the side list (tvOS's `SideRow`): tv-material's `ListItem` as Settings' rows are ([HitherListItem], so through
 * `rememberTvClick`): its [words], and at its right what it holds ([value], a count), a tick on the [chosen] one (rule 3:
 * never a fill), or a chevron for a row that opens a page ([opens]). Select chooses it, or opens what it leads to.
 * Disabled ([enabled] false) it is words only, dimmed, and no stop for focus (the waiting count for an account that
 * approves itself). TalkBack reads the words, then what it holds.
 */
@Composable
fun SideRow(
    words: String,
    onSelect: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    chosen: Boolean = false,
    value: String? = null,
    opens: Boolean = false,
    enabled: Boolean = true,
    description: String? = null,
) {
    HitherListItem(
        headline = words,
        onClick = onSelect,
        modifier = modifier
            .fillMaxWidth()
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        tag = tag,
        enabled = enabled,
        selected = chosen,
        chevron = opens,
        trailing = value?.let { count -> { RowValue(count, Modifier.testTag("side value")) } },
    )
}

/**
 * The split's lockups (tvOS's `LockupGrid`): [LIST_COLUMNS] across the width the split leaves, spaced to fill it, a
 * line's captions [Metrics.GridRowGap] above the next line's pictures. Lazy, as a list can run long; its page pages as
 * focus nears the end. The first line's pictures sit [top] down (for a tab's page, under the bar, which goes up as the
 * grid scrolls: `TabPage`), and the focused line comes to rest there, the grid moving a line at a time under a still
 * focus (DECISIONS 40's rule, as the other grids). The lockups carry their own places ([lockupPlace]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LockupGrid(
    state: LazyGridState,
    top: Dp,
    tag: String,
    modifier: Modifier = Modifier,
    content: LazyGridScope.() -> Unit,
) {
    val density = LocalDensity.current
    val pivot = remember(density, top) { LinePivot(with(density) { top.toPx() }) }
    val config = LocalConfiguration.current
    val width = config.screenWidthDp.dp - Metrics.SideMargin * 2 - Metrics.SideList - Metrics.SplitGap
    val gap = ((width - Metrics.PosterWidth * LIST_COLUMNS) / (LIST_COLUMNS - 1)).coerceAtLeast(Metrics.LockupGap)
    // room after the last line, so that it too comes up to the first line's place
    val afterLast = (config.screenHeightDp.dp - Metrics.BarFoot - Metrics.LiftRoom - StatusLockupHeight).coerceAtLeast(Metrics.GridRowGap)
    CompositionLocalProvider(LocalBringIntoViewSpec provides pivot) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(LIST_COLUMNS),
            state = state,
            contentPadding = PaddingValues(top = top, bottom = afterLast),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(Metrics.GridRowGap),
            modifier = modifier.fillMaxSize().testTag(tag),
            content = content,
        )
    }
}

/** The focused line comes to [at] px from the grid's top. */
@OptIn(ExperimentalFoundationApi::class)
private class LinePivot(private val at: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - at
}

/**
 * A lockup's place in a [LockupGrid], [index] of [count]: its own requester ([own], for hand-offs that name it), the
 * grid's [entry] where it is the one left ([isEntry]), and fenced: Right from the last column, or from the last lockup
 * ([more] false: nothing after it), goes nowhere; Up from the first line goes to [up] (a tab's own tab on the bar), where
 * given. Left from the first column is the split's (the chosen side row).
 */
fun Modifier.lockupPlace(index: Int, count: Int, own: FocusRequester, isEntry: Boolean, entry: FocusRequester, up: FocusRequester? = null, more: Boolean = false): Modifier =
    focusRequester(own)
        .then(if (isEntry) Modifier.focusRequester(entry) else Modifier)
        .focusProperties {
            if (index % LIST_COLUMNS == LIST_COLUMNS - 1 || (index == count - 1 && !more)) right = FocusRequester.Cancel
            if (index < LIST_COLUMNS && up != null) this.up = up
        }

/** How far a [LockupGrid] has scrolled from its top, px, for the bar (`TabPage`): as a list's `scrolledFromTop`. */
fun LazyGridState.scrolledFromTop(): Int = if (firstVisibleItemIndex >= LIST_COLUMNS) Int.MAX_VALUE else firstVisibleItemScrollOffset

/**
 * Where the split's content stands with focus, for [OwedFocus]: whether the content holds it ([contentHasFocus]) and
 * whether the side row it waits on holds it ([parkHasFocus]), each set by an `onFocusChanged`, and whether focus is owed.
 */
@Stable
class SplitFocus {
    var contentHasFocus by mutableStateOf(false)
    var parkHasFocus by mutableStateOf(false)

    /** 0: nothing owed; 1: owed, as the body swapped; 2: waiting on the side row for the next body. */
    internal var owed = 0

    /** The user chose something else (a side row): nothing is owed any more. */
    fun forget() {
        owed = 0
    }
}

/**
 * Focus the split's content owes when what held it there goes (Try Again asking again, Show All choosing All, the last
 * lockup of a list read empty): when a focused control goes, Compose puts focus on the page's first focusable, the bar
 * (DECISIONS 149; H2's trap). It is decided in the composition that swaps [body], while the old target still reports
 * focus, and then focus goes to what replaces it ([target]: the lockup left, Try Again, the way out). With nothing there
 * to take it (a list on its way) it waits on [park], the chosen side row, so it never lands on nothing, and moves on when
 * the next body comes if it is still there.
 */
@Composable
fun OwedFocus(body: Any, split: SplitFocus, target: () -> FocusRequester?, park: () -> FocusRequester) {
    // The moves run on the page's scope, not the effect's: a body that changes again within a frame or two (a list read
    // at once) restarts the effect, and must not cancel a move half made (a restarting effect loses its one-shot).
    val scope = rememberCoroutineScope()
    val moving = remember { arrayOfNulls<Job>(1) }
    remember(body) {
        if (split.contentHasFocus) split.owed = 1
        body
    }
    LaunchedEffect(body) {
        val was = split.owed
        if (was == 0) return@LaunchedEffect
        val next = target()
        if (next == null) {
            if (was == 1) {
                split.owed = 2
                moving[0]?.cancel()
                moving[0] = scope.launch { settleFocus(park) }
            }
            return@LaunchedEffect
        }
        split.owed = 0
        // still waiting on the side row, or still on its way there (nobody has moved since)
        val parked = split.parkHasFocus || moving[0]?.isActive == true
        moving[0]?.cancel()
        if (was == 1 || parked) moving[0] = scope.launch { settleFocus { next } }
    }
}

/**
 * Gives focus back to the lockup [key] once a frame has passed (whatever was over it has gone), while [current] still
 * names it: a page that hands focus elsewhere meanwhile (a decision in, a lockup cancelled) changes it, and this gives
 * up. Gives up too if the lockup has gone ([present]).
 */
suspend fun settleOnLockup(key: String, current: () -> String?, lockups: LockupFocus, present: (String) -> Boolean) {
    repeat(10) {
        withFrameNanos { }
        if (current() != key || !present(key)) return
        if (runCatching { lockups.of(key).requestFocus() }.getOrDefault(false)) return
    }
}

/** Asks [target] for focus once it is laid out (and whatever it replaces has gone): a few frames at most. */
suspend fun settleFocus(target: () -> FocusRequester) {
    repeat(10) {
        withFrameNanos { }
        if (runCatching { target().requestFocus() }.getOrDefault(false)) return
    }
}

/** One focus target per lockup, by key, made outside composition (lint's rule) and kept for the page. */
class LockupFocus {
    private val byKey = HashMap<String, FocusRequester>()

    fun of(key: String): FocusRequester = byKey.getOrPut(key) { FocusRequester() }
}

/** One of a lockup's actions in its [LockupMenu]: its words and tag; [destructive] in red words (rule 2). */
class LockupAction(val words: String, val tag: String, val destructive: Boolean = false, val onSelect: () -> Unit)

/**
 * A lockup's actions (H2's X6; tvOS's context menu and `confirmationDialog`): a small [ConfirmDialog] of them, drawn as it
 * is, centred over the darkened page: the [title] (the title's name), a [message], the [actions] stacked with focus on
 * the first, and Cancel last. My Requests opens it by a long press on a pending request (Cancel Request), the queue by
 * Select on a request still to decide (Approve, Decline, Open Title). Focus stays inside while it is [open]; Back is
 * Cancel. Where focus goes after it is the page's: an action may open a question of its own ([ConfirmDialog]).
 */
@Composable
fun LockupMenu(
    open: Boolean,
    title: String,
    message: String?,
    actions: List<LockupAction>,
    onDismiss: () -> Unit,
    tag: String = "lockup menu",
    dismissTag: String = "close menu",
) {
    if (!open) return
    val first = remember { FocusRequester() }
    FocusOnArrival(first)
    BackHandler { onDismiss() }
    DialogColumn(title, message, tag) {
        actions.forEachIndexed { index, action ->
            PlainButton(
                text = action.words,
                onClick = action.onSelect,
                destructive = action.destructive,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (index == 0) Modifier.focusRequester(first).focusProperties { up = FocusRequester.Cancel } else Modifier)
                    .testTag(action.tag),
            )
        }
        PlainButton(
            text = stringResource(R.string.cancel),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().focusProperties { down = FocusRequester.Cancel }.testTag(dismissTag),
        )
    }
}

/**
 * Where a list's lockups end (My Requests' since M3, Issues' and the queue's since A9; DECISIONS 65's rule): a still
 * place while the next page is on its way ([loadingMore]); after a later page failed ([failed]), Try Again, which asks
 * for that page once ([onRetry]), keeps focus while it is out ("Trying…", [retrying]) and then hands focus to the first
 * new lockup ([EndRetryHandOff]). Moving along the lockups does not ask again (DECISIONS 42): Try Again is the way.
 * The lockup before it is fenced at its right unless one of these follows (`lockupPlace`'s `more`).
 */
fun LazyGridScope.listEnd(loadingMore: Boolean, failed: Boolean, retrying: Boolean, onRetry: () -> Unit, retry: EndRetry) {
    if (loadingMore) {
        item(key = "more") { PlaceholderLockup(Modifier.height(StatusLockupHeight)) }
    }
    if (failed) {
        item(key = "retry") {
            LockupButton(
                onSelect = onRetry,
                tag = "retry more",
                description = stringResource(R.string.try_again),
                caption = null,
                busy = retrying,
                modifier = Modifier.onFocusChanged { retry.focused = it.isFocused }.focusProperties { right = FocusRequester.Cancel },
            ) {
                RetryFace(retrying)
            }
        }
    }
}

/** A [listEnd]'s Try Again: whether it holds focus, and how many lockups there were while it showed. */
@Stable
class EndRetry {
    internal var focused by mutableStateOf(false)
    internal val countAt = IntArray(1)
}

/**
 * Focus from a [listEnd]'s Try Again once its page has come and it has gone: [handOff] is given the index of the first
 * new lockup (the count there was while Try Again showed), decided in the composition that removes it (DECISIONS 149's
 * trap: a focused control that goes leaves focus to Compose). [count] is the lockups now.
 */
@Composable
fun EndRetryHandOff(retry: EndRetry, failed: Boolean, count: Int, handOff: suspend (first: Int) -> Unit) {
    if (failed) retry.countAt[0] = count
    val go = remember(failed) { !failed && retry.focused }
    LaunchedEffect(go) {
        if (go) handOff(retry.countAt[0])
    }
}

/** Try Again's face on a lockup's place ("Trying…" while it is out). */
@Composable
internal fun RetryFace(busy: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(if (busy) R.string.trying else R.string.try_again), style = HitherType.Body)
    }
}
