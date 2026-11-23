package com.tomdebres.hither.ui.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.R
import com.tomdebres.hither.core.rules.DiscoverRow
import com.tomdebres.hither.core.rules.SEE_MORE_POSTERS
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.core.rules.shownCards
import com.tomdebres.hither.core.rules.showsSeeMore
import com.tomdebres.hither.ui.bar.BarContent
import com.tomdebres.hither.ui.bar.Tab
import com.tomdebres.hither.ui.bar.TabPage
import com.tomdebres.hither.ui.bar.TopBar
import com.tomdebres.hither.ui.bar.rememberBar
import com.tomdebres.hither.ui.bar.scrolledFromTop
import com.tomdebres.hither.ui.components.LocalDpadPresses
import com.tomdebres.hither.ui.components.ReportRecomposition
import com.tomdebres.hither.ui.components.selectBeforeKeyboard
import com.tomdebres.hither.ui.components.showsFocus
import com.tomdebres.hither.ui.requests.RequestsModel
import com.tomdebres.hither.ui.theme.ButtonLook
import com.tomdebres.hither.ui.theme.HitherType
import com.tomdebres.hither.ui.theme.LockupButton
import com.tomdebres.hither.ui.theme.Metrics
import com.tomdebres.hither.ui.theme.Page
import com.tomdebres.hither.ui.theme.PageKind
import com.tomdebres.hither.ui.theme.PlaceholderLockup
import com.tomdebres.hither.ui.theme.PosterLockup
import com.tomdebres.hither.ui.theme.PosterLockupHeight
import com.tomdebres.hither.ui.theme.Shelf
import com.tomdebres.hither.ui.theme.ShelfTitle
import com.tomdebres.hither.ui.theme.StateKind
import com.tomdebres.hither.ui.theme.StateView
import com.tomdebres.hither.ui.theme.Tokens
import com.tomdebres.hither.ui.theme.mixesKinds
import com.tomdebres.hither.ui.theme.shelfPlace
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// The rows at ten feet, in dp on the 960 x 540 dp panel: since A3 (DECISIONS 320), shelves of lockups under their headers
// (`ui/theme/Lockups.kt`, tvOS's `Shelf` and `ShelfTitle`).

/** The bar's foot (`ui/bar`): the rows start under it, in the list's top padding, and the bar scrolls away with them. */
private val BarBottom = Metrics.BarFoot

/** A row: its shelf's header (62 dp, as the rows' before A3), then the lockups, pictures and captions. */
private val RowPitch = Metrics.ShelfHeader + PosterLockupHeight

/**
 * The line the focused row's pictures rest on: where the first row's are with the page at the top, so Down brings every
 * later row up to the same place and the eye stays put (DECISIONS 40).
 */
private val CardLine = BarBottom + Metrics.ShelfHeader

/** Room after the last row so that it too can come up to the line. */
private val AfterLastRow = 540.dp - CardLine - PosterLockupHeight

/**
 * Where a focused card is brought on the screen: its top on [CardLine] (the page scrolls a
 * whole row at a time and the focused row always rests in the same place), and, inside a row,
 * its left edge on the first column where the row allows, so the row moves under a still
 * focus and stops at its ends.
 */
@OptIn(ExperimentalFoundationApi::class)
private class Pivot(private val at: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - at
}

/**
 * Discover: the rows, each loading on its own, under the bar with Discover as the current tab (DECISIONS 149), which
 * the home draws over the page and which scrolls away with the rows (A2). Focus arrives on the first row (or, coming
 * back, on the card that was left, a row's "See More" card included; or, back from a page the bar opened or with focus
 * moving along the bar, on its control, 129). Back from the rows goes to the bar's Discover and the top of the page;
 * Back from the bar leaves (the home's).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiscoverScreen(
    model: DiscoverModel,
    onOpen: (TitleCard) -> Unit,
    /**
     * Seerr's Movies or Series page, from the "See More" card that ends Popular Movies and Popular Series
     * (Tom, 2026-11-12, DECISIONS 112; they were the bar's, 102). Back from it comes to that card.
     */
    onSeeMore: (DiscoverRow) -> Unit = {},
    /** My Requests' model: Recent Requests heads the page from it (DECISIONS 84); none, no row. */
    recent: RequestsModel? = null,
    /** The bar: the same on every tab page (DECISIONS 149). */
    bar: BarContent = BarContent(),
) {
    val density = LocalDensity.current
    val entries = remember { RowEntries() }
    val recentEntry = remember { FocusRequester() }
    val recentGone by remember(recent) { derivedStateOf { recent == null || !model.recentShown || recentBody(recent) == RecentBody.NONE } }
    var recentHasFocus by remember { mutableStateOf(false) }
    // Shown while it has something to show, and kept while it holds focus, so focus is moved off it
    // before it goes (removed with focus, it reported focus lost before anything could hand it on).
    val recentShows by remember { derivedStateOf { !recentGone || recentHasFocus } }
    // Where the rows begin for focus: the card left in Recent Requests if it was the row last used and
    // is there, else the card left in the row last used; before any, the page's top row, which is Recent
    // Requests where the server's order puts it first (polish 2, DECISIONS 131). Read when focus arrives,
    // never in composition.
    val rowsEntry: () -> FocusRequester = remember(model) {
        {
            when {
                model.inRecent && recentShows -> recentEntry
                // (has something to show: while it is going, kept only for its focus, the top is the first row)
                model.atTop && !recentGone && model.recentAt == 0 -> recentEntry
                else -> model.rows.getOrNull(model.lastRow)?.let { entries.of(it.row.name) } ?: recentEntry
            }
        }
    }
    @Suppress("NAME_SHADOWING")
    val bar = rememberBar(bar)
    val frame = bar.frame
    val barFocus = frame.focus
    val scope = rememberCoroutineScope()
    // Until the first D-pad press, focus in the rows is arrival's, or where Compose put it when a focused placeholder
    // went: not the user's use of a row, so the model is not told (its start-up rules, polish 2's re-homing when the
    // plan moves the top, hold only while no row has been used). On main that focus fell on the bar's Discover, which
    // the model never heard of; with the bar over the page it can fall on a card (A2). The presses are `DpadHost`'s:
    // it takes a remote's arrows ahead of the keyboard, so the rows never see them as key events.
    val presses = LocalDpadPresses.current
    val pressesAtArrival = remember { presses.count }
    val untouched = { presses.count == pressesAtArrival }
    // Arrival's target until then, from what arrival knew: the card left (coming back, or Trending for measure-discover),
    // else the page's top as it is now. Not from the model's own "last used" (a stray focus on Recent Requests' cards
    // marks it), so the plan or Recent Requests changing the top moves the target, and stray focus never does.
    val arrivedInRecent = remember { model.inRecent }
    val arrivedAtTop = remember { model.atTop }
    val arrivalEntry: () -> FocusRequester = {
        when {
            arrivedInRecent && recentShows -> recentEntry
            !arrivedAtTop -> model.rows.getOrNull(model.lastRow)?.let { entries.of(it.row.name) } ?: recentEntry
            !recentGone && model.recentAt.coerceIn(0, model.rows.size) == 0 -> recentEntry
            else -> model.rows.firstOrNull()?.let { entries.of(it.row.name) } ?: recentEntry
        }
    }
    // And until then focus stays on arrival's target, the page's top row (or Trending for measure-discover): as its
    // placeholders give way to cards, Compose takes focus again by itself, Down from the screen's top, and can land a row
    // or two down, the page scrolled and the bar gone (A2: on main the bar was the first thing there).
    val holdArrival: () -> Unit = {
        if (untouched() && !frame.barHasFocus) {
            scope.launch {
                repeat(10) {
                    withFrameNanos { }
                    if (!untouched() || frame.barHasFocus) return@launch
                    if (runCatching { arrivalEntry().requestFocus() }.getOrDefault(false)) return@launch
                }
            }
        }
    }

    // ...and when arrival's target itself changes before then (the plan, the rights or Recent Requests landing after
    // focus did), focus in the rows follows it.
    val rowsHaveFocus = remember { BooleanArray(1) }
    val arrivalTarget by remember(model) { derivedStateOf { arrivalEntry() } }
    LaunchedEffect(arrivalTarget) { if (rowsHaveFocus[0]) holdArrival() }

    LaunchedEffect(Unit) { model.start() }
    // Focus on arrival: back from a page the bar opened, its control (129); else the card the user left (or the
    // first row's first place), coming from a tab too (149). The target may need a frame or two to be laid out (a
    // lazy list composes as it measures).
    LaunchedEffect(Unit) {
        val back = bar.returnTo.take()?.let(barFocus::of)
        // The rows' entry is asked for again each frame: Recent Requests can turn out empty and go
        // between the first frame and the one its target is laid out in.
        repeat(10) {
            withFrameNanos { }
            if (runCatching { (back ?: rowsEntry()).requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    // Recent Requests has nothing to show while it holds focus (its placeholders, and the user has no
    // requests): the first row takes focus, and then the row goes, rather than focus landing on nothing.
    val handOff = recentGone && recentHasFocus
    LaunchedEffect(handOff) {
        if (!handOff) return@LaunchedEffect
        model.recentGone()
        repeat(10) {
            withFrameNanos { }
            if (runCatching { rowsEntry().requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    // The plan changed the rows (or an empty Recently Added went) and the row focus would come back to is
    // gone: the first row takes it, rather than focus being left where Compose put it (DECISIONS 99).
    // Only a change made while this visit to Discover is on screen: a bump from before it (the plan arriving as
    // Discover first came up) was dealt with then, and replaying it on the way back from a bar page took focus
    // from the pill arrival had given it to (the polish 2 audit's item 1).
    val refocus = model.refocus
    val refocusOnArrival = remember { refocus }
    LaunchedEffect(refocus) {
        if (refocus == refocusOnArrival) return@LaunchedEffect
        repeat(10) {
            withFrameNanos { }
            if (runCatching { rowsEntry().requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    // Back from the rows: the bar's Discover, the page at its top (Back from the bar is the home's: Hither leaves).
    BackHandler(enabled = !frame.barHasFocus) {
        model.toTop()
        runCatching { barFocus.discover.requestFocus() }
        scope.launch { model.columnState.animateScrollToItem(0) }
    }
    // The frame (A2): Down from the bar comes back to the card left in the row last used, said outright (the rows' list
    // spans the whole screen, bar included, so a search downwards from the bar would not count it as below), read when
    // the bar is left; the bar goes up as the rows scroll, read in its placement, so a scroll recomposes nothing. Nothing
    // is ever under the bar, so nothing is drawn there for it to hide (DECISIONS 90's cull went with the solid bar; 314).
    TabPage(frame, down = rowsEntry, scrolled = remember(model) { { model.columnState.scrolledFromTop() } })
    val columnPivot = remember(density) { Pivot(with(density) { CardLine.toPx() }) }
    val rowPivot = remember(density) { Pivot(with(density) { Metrics.SideMargin.toPx() }) }


    // The page's top row rests with the page at its top (40's line is the first row's own place). While focus is on the
    // row that is the page's top, a list left scrolled when its scroll stopped (cut short by a row coming or going above
    // as Discover starts: the plan, the rights, Recent Requests, each in their own time) goes back to its top: unseen
    // under the solid bar before A2, it left the bar half away and spent the next Up on the scroll. The top is read as it
    // is now, so a row that becomes the top while it holds focus (Recent Requests going) is held too. A plain job per
    // row while it has focus, not state: nothing recomposes.
    val topKey: () -> Any? = remember(model, recent) {
        { if (recentShows && recent != null && model.recentAt.coerceIn(0, model.rows.size) == 0) RECENT_KEY else model.rows.firstOrNull()?.row?.name }
    }
    val topRest: (Any) -> Modifier = remember(model, topKey) {
        { key ->
            val job = arrayOfNulls<Job>(1)
            Modifier.onFocusChanged {
                if (it.hasFocus) {
                    if (job[0]?.isActive != true) {
                        job[0] = scope.launch {
                            snapshotFlow { topKey() == key && !model.columnState.isScrollInProgress && model.columnState.scrolledFromTop() > 0 }
                                .collect { offTop -> if (offTop) model.columnState.animateScrollToItem(0) }
                        }
                    }
                } else {
                    job[0]?.cancel()
                    job[0] = null
                }
            }
        }
    }

    Page(PageKind.Shelves) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides columnPivot) {
            LazyColumn(
                state = model.columnState,
                contentPadding = PaddingValues(top = BarBottom, bottom = AfterLastRow),
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { rowsHaveFocus[0] = it.hasFocus }
                    .testTag("rows"),
            ) {
                // The server's order (Tom's pick, polish 2, DECISIONS 131): the rows above Recent Requests, Recent
                // Requests, then the rest. Seerr's default puts Recently Added first.
                val recentAt = model.recentAt.coerceIn(0, model.rows.size)
                val rowItem: @Composable LazyItemScope.(RowState) -> Unit = { row ->
                    DiscoverRowView(
                        row = row,
                        entry = entries.of(row.row.name),
                        pivot = rowPivot,
                        onFocused = { card -> if (!untouched()) model.focused(row, card) else if (entries.of(row.row.name) !== arrivalEntry()) holdArrival() },
                        onLeft = { model.left(row) },
                        onRetry = { model.retry(row) },
                        onOpen = onOpen,
                        onSeeMore = onSeeMore,
                        modifier = remember(row.row.name) { topRest(row.row.name) },
                    )
                }
                items(model.rows.subList(0, recentAt), key = { row -> row.row.name }) { row -> rowItem(row) }
                if (recentShows && recent != null) {
                    item(key = RECENT_KEY) {
                        RecentRow(
                            requests = recent,
                            body = recentBody(recent).takeIf { it != RecentBody.NONE } ?: RecentBody.LOADING,
                            entry = recentEntry,
                            model = model,
                            pivot = rowPivot,
                            onFocus = {
                                recentHasFocus = it
                                if (it && untouched() && arrivalEntry() !== recentEntry) holdArrival()
                            },
                            // Up from the page's top row is the bar's Discover pill, said outright; lower down, the row above
                            up = if (recentAt == 0) barFocus.discover else FocusRequester.Default,
                            onOpen = onOpen,
                            modifier = remember { topRest(RECENT_KEY) },
                        )
                    }
                }
                items(model.rows.subList(recentAt, model.rows.size), key = { row -> row.row.name }) { row -> rowItem(row) }
            }
        }
        // Shown on its own (a test's), the page draws the bar itself; in the app the home draws it over the page.
        if (!bar.hosted) TopBar(current = { Tab.DISCOVER }, content = bar)
    }
}

/** Recent Requests' key in the rows' list. */
private const val RECENT_KEY = "recent"

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoverRowView(
    row: RowState,
    entry: FocusRequester,
    pivot: BringIntoViewSpec,
    onFocused: (Int) -> Unit,
    onLeft: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (TitleCard) -> Unit,
    onSeeMore: (DiscoverRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    var noticeFocused by remember { mutableStateOf(false) }
    val body = row.body
    // The row's placeholders or its error had focus and the cards have come: focus moves to
    // the first card rather than being left on nothing. Decided in the composition that swaps
    // the cards in, while the notice still holds focus (its removal reports it unfocused).
    val handOff = remember(body == RowBody.CARDS) { body == RowBody.CARDS && noticeFocused }
    // The notice stays, holding focus, until it has handed it on (A3, DECISIONS 326): removed in the composition that
    // swaps the lockups in, it took focus with it before the lockups were laid out, and Compose put focus on the first
    // focusable down from the screen's top, another row's, for a frame or more.
    var heldTooLong by remember { mutableStateOf(false) }
    LaunchedEffect(handOff) {
        if (handOff) {
            repeat(5) {
                withFrameNanos { }
                if (runCatching { entry.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            }
            heldTooLong = true
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .height(RowPitch)
            .onFocusChanged { if (!it.hasFocus) onLeft() }
            .testTag("row ${row.row.name}"),
    ) {
        // The title in words only: Seerr's circled arrow after a linked row's title went (Tom, 2026-11-13, DECISIONS
        // 135); the row's See More stays its way to the page.
        ShelfTitle(row.row.title)
        // The lockups, and over them the notice while it holds focus: one place in the composition for each, whichever
        // shows, so the notice's node (and its focus) lasts until the hand-off moves focus off it.
        Box {
            if (body == RowBody.CARDS) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides pivot) {
                    Cards(row, entry, onFocused, onRetry, onOpen, onSeeMore)
                }
            }
            if (body != RowBody.CARDS || (noticeFocused && !heldTooLong)) {
                // the row's way in is the lockups' once they have come
                RowWithoutCards(body, entry.takeIf { body != RowBody.CARDS }, onRetry, onFocus = { noticeFocused = it }, tag = row.row.name)
            }
        }
    }
}

@Composable
private fun Cards(
    row: RowState,
    entry: FocusRequester,
    onFocused: (Int) -> Unit,
    onRetry: () -> Unit,
    onOpen: (TitleCard) -> Unit,
    onSeeMore: (DiscoverRow) -> Unit,
) {
    val all = row.cards
    // Popular Movies and Popular Series show their first 20 and end with "See More" (DECISIONS 112); the
    // titles after the 20 are read only for the card's four posters.
    val shown = shownCards(row.row, all.size)
    val cards = if (shown == all.size) all else all.subList(0, shown)
    // Every row Seerr links ends with it once it has its 20 (Tom's pick, polish 2, DECISIONS 120, 131)
    val seeMore = row.row.takeIf { showsSeeMore(it, all.size) }
    // With "See More" there, a later page that failed or is on its way only costs the card a poster.
    val retryAtEnd = row.moreFailed && seeMore == null
    // Try Again at the end had focus and its page has come: focus goes to the first new card, where
    // Try Again was, rather than being left on nothing (as search's grid does, DECISIONS 65, 69).
    // Decided in the composition that removes the card, while it still reports focus.
    var retryFocused by remember { mutableStateOf(false) }
    val countAtRetry = remember { IntArray(1) }
    if (retryAtEnd) countAtRetry[0] = cards.size
    val handOff = remember(retryAtEnd) { !retryAtEnd && retryFocused }
    LaunchedEffect(handOff) {
        if (!handOff) return@LaunchedEffect
        onFocused(countAtRetry[0].coerceAtMost(row.cards.lastIndex))
        repeat(5) {
            withFrameNanos { }
            if (runCatching { entry.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    // Films and series side by side (Trending): each lockup says which it is under its title (Seerr's chips, as words).
    val mixed = remember(all) { mixesKinds(all) }
    // Coming into the row from above or below lands on the lockup it was left on.
    Shelf(state = row.listState, entry = entry) {
        itemsIndexed(cards, key = { _, card -> card.key }) { index, card ->
            ReportRecomposition("card item ${card.key}")
            // The row's way in is the lockup it was left on. Asked through a derived state, so a move recomposes the
            // lockup the way in leaves and the one it reaches; read directly, every lockup in the row would recompose on
            // every move (DECISIONS 53, 57).
            val isEntry by remember(index) { derivedStateOf { index == row.focusedIndex } }
            PosterLockup(
                card = card,
                onSelect = { onOpen(card) },
                onFocused = { onFocused(index) },
                mixed = mixed,
                // Fenced at the row's ends (REVIEW.md: a row's end does not jump to another row).
                modifier = Modifier.shelfPlace(isEntry, entry, first = index == 0, last = index == cards.lastIndex && !retryAtEnd && seeMore == null),
            )
        }
        if (row.loadingMore && seeMore == null) {
            item(key = "more") { PlaceholderLockup() }
        }
        if (retryAtEnd) {
            item(key = "retry") {
                RetryCard(
                    onRetry = onRetry,
                    busy = row.retryingMore,
                    modifier = Modifier.onFocusChanged { retryFocused = it.isFocused }.focusProperties { right = FocusRequester.Cancel },
                )
            }
        }
        if (seeMore != null) {
            item(key = "see more") {
                ReportRecomposition("see more ${row.row.name}")
                // The row's way in when it was left here, so Back from the page it opens comes back to it.
                val isEntry by remember(shown) { derivedStateOf { row.focusedIndex == shown } }
                SeeMoreCard(
                    row = seeMore,
                    posters = all.subList(shown, minOf(all.size, shown + SEE_MORE_POSTERS)).map { it.poster },
                    onSelect = { onSeeMore(seeMore) },
                    onFocused = { onFocused(shown) },
                    tag = row.row.name,
                    modifier = Modifier
                        .then(if (isEntry) Modifier.focusRequester(entry) else Modifier)
                        // the row's last stop (REVIEW.md: a row's end does not jump to another row)
                        .focusProperties { right = FocusRequester.Cancel },
                )
            }
        }
    }
}

/**
 * Where a row's lockups would be while there are none: still placeholders in the shelf's place while it loads (tvOS's),
 * the reason and Try Again when it failed, "No results." when the server had nothing, each a `StateView` in the row.
 * One focus target, the same one throughout, so a row that is loading can hold focus and pass it on to its first lockup
 * when they come. An empty row is not a stop: Up and Down pass over it, and only a row that turned out empty while it
 * held focus keeps it until the user moves on.
 */
@Composable
private fun RowWithoutCards(
    body: RowBody,
    entry: FocusRequester?,
    onRetry: () -> Unit,
    onFocus: (Boolean) -> Unit,
    tag: String,
) {
    var focused by remember { mutableStateOf(false) }
    val select = { if (body == RowBody.FAILED) onRetry() }
    Row(
        Modifier
            .then(if (body == RowBody.LOADING || body == RowBody.CARDS) Modifier.padding(start = Metrics.SideMargin) else Modifier.fillMaxWidth())
            .height(PosterLockupHeight)
            .then(if (entry != null) Modifier.focusRequester(entry) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                onFocus(it.isFocused)
            }
            .focusProperties {
                canFocus = body != RowBody.EMPTY || focused
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            }
            .selectBeforeKeyboard(onSelect = select)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = select)
            // What its first placeholder or its button draws, said on the target itself.
            .semantics { showsFocus = focused }
            .testTag("notice $tag"),
        // A row with no cards says why as a `StateView` (A1; the polish audit's item 13: one way), centred in the row.
        horizontalArrangement = if (body == RowBody.LOADING || body == RowBody.CARDS) Arrangement.spacedBy(Metrics.LockupGap) else Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = if (body == RowBody.LOADING || body == RowBody.CARDS) Alignment.Top else Alignment.CenterVertically,
    ) {
        when (body) {
            RowBody.LOADING, RowBody.CARDS -> repeat(7) { PlaceholderLockup(focused = focused && it == 0) }
            // the words, then Try Again under them, drawn from the row's focus: the row is the one target (DECISIONS 41)
            RowBody.FAILED -> StateView(
                StateKind.FAILED,
                stringResource(R.string.row_failed),
                wordsTag = "notice text $tag",
                focusOnArrival = false,
            ) { _ ->
                ButtonLook(stringResource(R.string.try_again), focused, Modifier.testTag("try again $tag"), prominent = true)
            }
            RowBody.EMPTY ->
                if (focused) {
                    ButtonLook(stringResource(R.string.no_results), focused = true)
                } else {
                    StateView(StateKind.EMPTY, stringResource(R.string.no_results), wordsTag = "notice text $tag", focusOnArrival = false)
                }
        }
    }
}

/**
 * The end of a row (or search's grid) whose next page failed: a lockup's place, Try Again on it (a `LockupButton`). While
 * its page is out ([busy]: search's grid) it says so and stays where focus is.
 */
@Composable
internal fun RetryCard(onRetry: () -> Unit, modifier: Modifier = Modifier, busy: Boolean = false) {
    val words = stringResource(if (busy) R.string.trying else R.string.try_again)
    LockupButton(onSelect = onRetry, tag = "retry more", description = words, caption = null, modifier = modifier, busy = busy) {
        BasicText(
            words,
            style = HitherType.Body.copy(color = Tokens.Text, fontWeight = FontWeight.Medium),
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/** One focus target per row, by the row's name, so a plan that adds or moves rows keeps each row's own. */
private class RowEntries {
    private val byName = HashMap<String, FocusRequester>()

    fun of(name: String): FocusRequester = byName.getOrPut(name) { FocusRequester() }
}
