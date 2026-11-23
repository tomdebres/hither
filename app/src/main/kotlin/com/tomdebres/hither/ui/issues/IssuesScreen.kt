package com.tomdebres.hither.ui.issues

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.R
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.rules.IssueFilter
import com.tomdebres.hither.core.rules.IssueRow
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.ui.bar.BarContent
import com.tomdebres.hither.ui.bar.Tab
import com.tomdebres.hither.ui.bar.TabPage
import com.tomdebres.hither.ui.bar.TopBar
import com.tomdebres.hither.ui.bar.rememberBar
import com.tomdebres.hither.ui.components.ReportRecomposition
import com.tomdebres.hither.ui.requests.ListTop
import com.tomdebres.hither.ui.requests.TitleState
import com.tomdebres.hither.ui.theme.EndRetry
import com.tomdebres.hither.ui.theme.EndRetryHandOff
import com.tomdebres.hither.ui.theme.Formats
import com.tomdebres.hither.ui.theme.LibrarySplit
import com.tomdebres.hither.ui.theme.LockupFocus
import com.tomdebres.hither.ui.theme.LockupGrid
import com.tomdebres.hither.ui.theme.LockupStatus
import com.tomdebres.hither.ui.theme.OwedFocus
import com.tomdebres.hither.ui.theme.Page
import com.tomdebres.hither.ui.theme.PageKind
import com.tomdebres.hither.ui.theme.PlainButton
import com.tomdebres.hither.ui.theme.PosterLockup
import com.tomdebres.hither.ui.theme.ProminentButton
import com.tomdebres.hither.ui.theme.SideRow
import com.tomdebres.hither.ui.theme.SplitFocus
import com.tomdebres.hither.ui.theme.StateKind
import com.tomdebres.hither.ui.theme.StateView
import com.tomdebres.hither.ui.theme.listEnd
import com.tomdebres.hither.ui.theme.lockupPlace
import com.tomdebres.hither.ui.theme.scrolledFromTop
import com.tomdebres.hither.ui.theme.settleFocus
import kotlinx.coroutines.launch

/**
 * Seerr's Issues page (M6, DECISIONS 106), the Issues tab's, as tvOS's Library (H2's A6, DECISIONS 338; tvOS's
 * `IssuesView`): Seerr's filters (Open, Resolved, All) as the side list, the chosen one ticked; and the issues as poster
 * lockups four across, each with Open or Resolved under the title and a line of what and when ("Subtitle · 3 days ago").
 * Its own fixed filters went, so the page takes the bar as the other tab pages do: the bar scrolls away with the grid.
 *
 * - **Select** on a filter chooses it, and focus stays there (each choice is read from the server); on the chosen one it
 *   goes into its lockups. Select on a lockup opens the title's page (Seerr's issue page, with comments and Resolve, is
 *   Tom's in Seerr); back from it, focus is on the lockup left.
 * - **Focus:** Down from the bar lands on the chosen filter; Right goes into the grid, Left from it back to the filter.
 *   Back from a page the bar opened, focus is on its control (129).
 * - **States:** loading; a failure's Try Again; Seerr's refusal says the account may no longer see issues; an empty
 *   filter offers Seerr's "Show All Issues". Where one of them held focus and is replaced, focus goes to what replaces it
 *   (named as the body swaps), the chosen filter while a list is on its way.
 * - **Back** from the page goes to the bar's Issues; Back on the bar is the home's (to Discover's tab), or, shown on its
 *   own, [onBack].
 */
@Composable
fun IssuesScreen(
    model: IssuesModel,
    onOpen: (TitleCard) -> Unit,
    onBack: () -> Unit,
    /** The bar: the same on every tab page (DECISIONS 149). */
    bar: BarContent = BarContent(),
) {
    LaunchedEffect(model) { model.open() }
    @Suppress("NAME_SHADOWING")
    val bar = rememberBar(bar)
    val frame = bar.frame
    val barFocus = frame.focus
    val scope = rememberCoroutineScope()
    val filterFocus = remember { FilterFocus() }
    val chosen = filterFocus.of(model.filter)
    val entry = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val showAll = remember { FocusRequester() }
    val lockups = remember { LockupFocus() }
    // A grid of its own for each filter, from the top; back from a title's page, on the line of the lockup left.
    val grid = remember(model.filter) { LazyGridState(firstVisibleItemIndex = if (model.returnToRow) model.focusedIndex else 0) }
    val body = model.body

    // Back from the page: the bar's Issues (A2), the grid at its top. Back on the bar: the home's, or this page's own.
    BackHandler(enabled = !frame.barHasFocus) {
        if (grid.firstVisibleItemIndex > 0 || grid.firstVisibleItemScrollOffset > 0) scope.launch { grid.animateScrollToItem(0) }
        runCatching { barFocus.issues.requestFocus() }
    }
    BackHandler(enabled = frame.barHasFocus && !bar.hosted) { onBack() }

    // Arrival: back to the lockup left (from its title), or to a bar control (from a page the bar opened, or a move along
    // the bar), else the chosen filter (tvOS's default focus on the side list).
    LaunchedEffect(Unit) {
        val backToBar = bar.returnTo.take()?.let(barFocus::of)
        val backToRow = model.returnToRow && model.rows.isNotEmpty()
        model.returnToRow = false
        settleFocus { backToBar ?: if (backToRow) entry else filterFocus.of(model.filter) }
    }
    // What held focus in the grid's place is going (Try Again asking again, Show All choosing All): focus goes to what
    // replaces it, named as the body swaps (`OwedFocus`); while the next list is on its way it waits on the chosen filter.
    val split = remember { SplitFocus() }
    OwedFocus(body, split, target = { contentTarget(model, entry, retry, showAll) }, park = { filterFocus.of(model.filter) })
    // The frame (A2): Down from the bar lands on the chosen filter; the bar goes up as the grid scrolls.
    TabPage(
        frame,
        down = { filterFocus.of(model.filter) },
        scrolled = remember(model, grid) { { if (model.body == IssuesBody.ROWS) grid.scrolledFromTop() else 0 } },
    )

    Page(PageKind.Tab, modifier = Modifier.testTag("issues page")) {
        LibrarySplit(
            top = ListTop,
            sideEntry = chosen,
            contentEntry = { contentTarget(model, entry, retry, showAll) },
            side = {
                IssueFilter.entries.forEachIndexed { index, filter ->
                    val isChosen = filter == model.filter
                    SideRow(
                        words = filter.label,
                        onSelect = {
                            if (isChosen) {
                                // The chosen one again: into its lockups.
                                contentTarget(model, entry, retry, showAll)?.let { runCatching { it.requestFocus() } }
                            } else {
                                split.forget()
                                model.choose(filter)
                            }
                        },
                        tag = "issue filter ${filter.api}",
                        chosen = isChosen,
                        modifier = Modifier
                            .focusRequester(filterFocus.of(filter))
                            .onFocusChanged { if (isChosen) split.parkHasFocus = it.isFocused }
                            .focusProperties { if (index == 0) up = barFocus.issues },
                    )
                }
            },
        ) {
            Box(Modifier.fillMaxSize().onFocusChanged { split.contentHasFocus = it.hasFocus }) {
                when (body) {
                    IssuesBody.ROWS -> IssueGrid(model, grid, entry, lockups, up = barFocus.issues, onOpen = onOpen)
                    // Each state a `StateView` (A1). The page hands focus on itself (`owed`), so the states do not take it.
                    IssuesBody.LOADING -> StateView(StateKind.LOADING, stringResource(R.string.issues_loading), Modifier.padding(top = ListTop + 60.dp), wordsTag = "issues loading")
                    IssuesBody.FAILED -> StateView(StateKind.FAILED, stringResource(R.string.issues_failed), Modifier.padding(top = ListTop + 60.dp), wordsTag = "failure text", focusOnArrival = false) { first ->
                        ProminentButton(text = stringResource(R.string.try_again), onClick = model::retry, modifier = first.focusRequester(retry).testTag("try again"))
                    }
                    IssuesBody.NO_RIGHT -> StateView(StateKind.NO_RIGHT, stringResource(R.string.issues_no_right), Modifier.padding(top = ListTop + 60.dp), wordsTag = "issues no right")
                    IssuesBody.EMPTY -> StateView(
                        StateKind.EMPTY,
                        stringResource(R.string.no_results),
                        Modifier.padding(top = ListTop + 60.dp),
                        wordsTag = "issues empty",
                        focusOnArrival = false,
                        // Seerr's own way out of an empty filter
                        action = if (model.filter == IssueFilter.ALL) {
                            null
                        } else {
                            { first ->
                                PlainButton(
                                    text = stringResource(R.string.show_all_issues),
                                    onClick = { model.choose(IssueFilter.ALL) },
                                    modifier = first.focusRequester(showAll).testTag("show all"),
                                )
                            }
                        },
                    )
                }
            }
        }
        // Shown on its own (a test's), the page draws the bar itself; in the app the home draws it over the page.
        if (!bar.hosted) TopBar(current = { Tab.ISSUES }, content = bar)
    }
}

/** One focus target per filter's side row, made outside composition (lint's rule). */
private class FilterFocus {
    private val byFilter = IssueFilter.entries.associateWith { FocusRequester() }

    fun of(filter: IssueFilter): FocusRequester = byFilter.getValue(filter)
}

/** What holds focus in the grid's place: the lockup left, Try Again, Show All; nothing while loading or without one. */
private fun contentTarget(model: IssuesModel, entry: FocusRequester, retry: FocusRequester, showAll: FocusRequester): FocusRequester? =
    when (model.body) {
        IssuesBody.ROWS -> entry
        IssuesBody.FAILED -> retry
        IssuesBody.EMPTY -> if (model.filter == IssueFilter.ALL) null else showAll
        IssuesBody.LOADING, IssuesBody.NO_RIGHT -> null
    }

/** The issues as lockups, newest first, four across; a move recomposes the two lockups it touches (DECISIONS 57). */
@Composable
private fun IssueGrid(
    model: IssuesModel,
    grid: LazyGridState,
    entry: FocusRequester,
    lockups: LockupFocus,
    up: FocusRequester,
    onOpen: (TitleCard) -> Unit,
) {
    val rows = model.rows
    val unknown = stringResource(R.string.unknown_title)
    // A later page that failed ends the grid with Try Again (A9, as My Requests'): focus on to the first new lockup after.
    val retryAtEnd = model.moreFailed
    val retry = remember { EndRetry() }
    EndRetryHandOff(retry, retryAtEnd, rows.size) { first ->
        model.focused(first.coerceAtMost(model.rows.lastIndex))
        settleFocus { entry }
    }
    val more = model.loadingMore || retryAtEnd
    LockupGrid(grid, top = ListTop, tag = "issues list") {
        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
            ReportRecomposition("issue item ${row.key}")
            val isEntry by remember(index) { derivedStateOf { index == model.focusedIndex } }
            val state = row.titleKey?.let { model.titles[it] }
            val ready = (state as? TitleState.Ready)?.title
            val name = ready?.title ?: if (state == TitleState.Failed || row.titleKey == null) unknown else ""
            val card = TitleCard(row.titleKey ?: row.key, row.tmdbId, row.type ?: MediaType.MOVIE, name, ready?.year, ready?.poster, badge = null)
            PosterLockup(
                card = card,
                onSelect = { open(model, row, onOpen) },
                modifier = Modifier.lockupPlace(index, rows.size, lockups.of(row.key), isEntry, entry, up = up, more = more),
                onFocused = { model.focused(index) },
                // Open or Resolved, then what and when ("Subtitle · 3 days ago"); the type alone where that does not fit
                status = LockupStatus.issue(row.open, row.status, line = Formats.join(listOf(row.kind, Formats.relative(row.createdAt))), short = row.kind),
                tag = row.key,
            )
        }
        listEnd(loadingMore = model.loadingMore, failed = retryAtEnd, retrying = model.retryingMore, onRetry = model::retry, retry = retry)
    }
}

/** The title an issue is about, as a card opens it; nothing for an issue whose title this build cannot say. */
private fun open(model: IssuesModel, row: IssueRow, onOpen: (TitleCard) -> Unit) {
    val type = row.type ?: return
    val key = row.titleKey ?: return
    val ready = (model.titles[key] as? TitleState.Ready)?.title
    model.returnToRow = true
    onOpen(TitleCard(key, row.tmdbId, type, ready?.title.orEmpty(), ready?.year, ready?.poster, badge = null))
}
