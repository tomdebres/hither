package com.tomdebres.hither.ui.requests

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.R
import com.tomdebres.hither.core.rules.RequestRow
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.core.rules.canCancel
import com.tomdebres.hither.core.rules.requestCard
import com.tomdebres.hither.ui.bar.BarContent
import com.tomdebres.hither.ui.bar.Tab
import com.tomdebres.hither.ui.bar.TabPage
import com.tomdebres.hither.ui.bar.TopBar
import com.tomdebres.hither.ui.bar.rememberBar
import com.tomdebres.hither.ui.components.ReportRecomposition
import com.tomdebres.hither.ui.theme.ConfirmDialog
import com.tomdebres.hither.ui.theme.Problem
import com.tomdebres.hither.ui.theme.ProblemAlert
import com.tomdebres.hither.ui.theme.Formats
import com.tomdebres.hither.ui.theme.LIST_COLUMNS
import com.tomdebres.hither.ui.theme.LibrarySplit
import com.tomdebres.hither.ui.theme.LockupAction
import com.tomdebres.hither.ui.theme.EndRetry
import com.tomdebres.hither.ui.theme.EndRetryHandOff
import com.tomdebres.hither.ui.theme.listEnd
import com.tomdebres.hither.ui.theme.LockupFocus
import com.tomdebres.hither.ui.theme.LockupGrid
import com.tomdebres.hither.ui.theme.LockupMenu
import com.tomdebres.hither.ui.theme.LockupStatus
import com.tomdebres.hither.ui.theme.Metrics
import com.tomdebres.hither.ui.theme.OwedFocus
import com.tomdebres.hither.ui.theme.Page
import com.tomdebres.hither.ui.theme.PageKind
import com.tomdebres.hither.ui.theme.PlaceholderLockup
import com.tomdebres.hither.ui.theme.PlainButton
import com.tomdebres.hither.ui.theme.PosterLockup
import com.tomdebres.hither.ui.theme.ProminentButton
import com.tomdebres.hither.ui.theme.SideRow
import com.tomdebres.hither.ui.theme.SplitFocus
import com.tomdebres.hither.ui.theme.StateKind
import com.tomdebres.hither.ui.theme.StateView
import com.tomdebres.hither.ui.theme.StatusLockupHeight
import com.tomdebres.hither.ui.theme.lockupPlace
import com.tomdebres.hither.ui.theme.scrolledFromTop
import com.tomdebres.hither.ui.theme.settleFocus
import com.tomdebres.hither.ui.theme.settleOnLockup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where a list's first line of pictures sits on a tab's page: under the bar, with room for a lifted lockup. */
internal val ListTop = Metrics.BarFoot + Metrics.LiftRoom

/**
 * My Requests as tvOS's Library (H2's A6, DECISIONS 338; tvOS's `RequestsView`): under the bar with Requests as the
 * current tab (DECISIONS 149), a side list of My Requests (ticked: the page's one category) and, while the right is read,
 * Waiting for Approval with its count (DECISIONS 339); and the user's own requests, newest first, as poster lockups four
 * across, each with its status and when under the title ("Pending", "2 days ago by User 1"). The bar scrolls away with
 * the grid (A2).
 *
 * - **Focus:** Down from the bar lands on My Requests, as on the Library's categories; Right goes into the grid, on the
 *   lockup left; Left from the grid comes back to My Requests. Arriving from a tab, focus stays on the bar; back from a
 *   title's page, on its lockup; back from a page the bar opened, on its control (129).
 * - **Select** on a lockup opens the title ([onOpen]). **A long press** (or the remote's Menu key) on a pending request
 *   opens its menu, Cancel Request, which asks first (87's question); once cancelled the lockup goes, focus moved first
 *   to the one that takes its place (the next, else the one before, else My Requests), so it never lands on nothing.
 * - **States:** while the first page loads the lockups' places stand in; a failure offers Try Again, which keeps focus
 *   until its answer and hands it on; with no requests the page says so, with a way to Discover ([onBack]). A later page
 *   that fails ends the grid with Try Again.
 * - **Kept from the rows:** the paging, the read on every arrival and on coming to the front (the model), newer requests
 *   taking focus at the top ([RequestsModel.newTop]), the read once a minute while a download runs.
 * - **Back** from the page goes to the bar's Requests and the grid's top; Back on the bar is the home's (to Discover's
 *   tab), or, shown on its own, [onBack].
 */
@Composable
fun RequestsScreen(
    model: RequestsModel,
    onOpen: (TitleCard) -> Unit,
    onBack: () -> Unit,
    /** The bar: the same on every tab page (DECISIONS 149); it carries the waiting count and its way in (339). */
    bar: BarContent = BarContent(),
) {
    @Suppress("NAME_SHADOWING")
    val bar = rememberBar(bar)
    val frame = bar.frame
    val barFocus = frame.focus
    val mine = remember { FocusRequester() }
    val entry = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val empty = remember { FocusRequester() }
    val lockups = remember { LockupFocus() }
    val scope = rememberCoroutineScope()
    // Back from a title's page: the grid opens on the line of the lockup left (the page's own state went with it).
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = if (model.returnToRow) model.focusedIndex.coerceAtLeast(0) else 0)
    val body = model.body
    // The lockup that holds focus, as it says when it takes it; null once focus has left the grid. Not state.
    val focusedKey = remember { arrayOfNulls<String>(1) }

    // A pending request's menu (long press), then Cancel Request's question; focus goes back to its lockup once both
    // have gone, unless the lockup went (cancelled).
    var menuFor by remember { mutableStateOf<RequestRow?>(null) }
    var asking by remember { mutableStateOf<RequestRow?>(null) }
    var giveBack by remember { mutableStateOf<String?>(null) }
    // A cancel that did not go through (A5; tvOS's alert): its dialog, then focus back on its lockup.
    var problem by remember { mutableStateOf<Problem?>(null) }
    var problemKey by remember { mutableStateOf<String?>(null) }
    val words = LocalContext.current.resources
    LaunchedEffect(menuFor, asking) {
        val key = giveBack ?: return@LaunchedEffect
        if (menuFor != null || asking != null) return@LaunchedEffect
        settleOnLockup(key, { giveBack }, lockups) { k -> model.rows.any { it.key == k } }
        if (giveBack == key) giveBack = null
    }

    // While a lockup shows a download, the list is read again once a minute (Seerr's download sync runs once a minute;
    // M6), and its estimates move on with it.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val downloading by remember { derivedStateOf { model.rows.any { it.badge?.progress != null } } }
    LaunchedEffect(downloading) {
        while (downloading) {
            delay(RE_READ_MS)
            model.refresh()
            now = System.currentTimeMillis()
        }
    }

    // Arrival: back to the lockup left (from its title), or to a bar control (from a page the bar opened, or a move along
    // the bar), else My Requests. Then the list is read again (the first time: read at all).
    LaunchedEffect(Unit) {
        val backToBar = bar.returnTo.take()?.let(barFocus::of)
        val backToRow = model.returnToRow && model.rows.isNotEmpty()
        model.returnToRow = false
        if (!backToRow) model.focusedIndex = 0
        model.open()
        settleFocus { backToBar ?: if (backToRow) entry else mine }
    }
    // What held focus in the grid's place (Try Again; or the lockups, if a read emptied the list) is going: focus goes to
    // what replaces it, named as the body swaps (`OwedFocus`), My Requests while nothing there can take it.
    val split = remember { SplitFocus() }
    OwedFocus(body, split, target = { contentTarget(model.body, entry, retry, empty) }, park = { mine })
    // Newer requests came above the top lockup while focus was on it: the newest takes focus.
    LaunchedEffect(model.newTop) {
        if (model.newTop == 0 || !split.contentHasFocus) return@LaunchedEffect
        grid.scrollToItem(0)
        settleFocus { entry }
    }
    // Back from the page: the bar's Requests (A2), the grid at its top. Back on the bar: the home's, or this page's own.
    BackHandler(enabled = !frame.barHasFocus) {
        model.focusedIndex = 0
        if (grid.firstVisibleItemIndex > 0 || grid.firstVisibleItemScrollOffset > 0) scope.launch { grid.animateScrollToItem(0) }
        runCatching { barFocus.requests.requestFocus() }
    }
    BackHandler(enabled = frame.barHasFocus && !bar.hosted) { onBack() }
    // The frame (A2): Down from the bar lands on My Requests; the bar goes up as the grid scrolls.
    TabPage(
        frame,
        down = { mine },
        scrolled = remember(model, grid) { { if (model.body == RequestsBody.ROWS) grid.scrolledFromTop() else 0 } },
    )
    // A menu, a question or a problem over the page: the bar goes with the page under it.
    var problemShown by remember { mutableStateOf(false) }
    val covered = menuFor != null || asking != null || problemShown
    DisposableEffect(frame, covered) {
        frame.covered = covered
        onDispose { frame.covered = false }
    }

    Page(PageKind.Tab, modifier = Modifier.testTag("requests page")) {
        LibrarySplit(
            top = ListTop,
            sideEntry = mine,
            contentEntry = { contentTarget(model.body, entry, retry, empty) },
            side = {
                // The page's one category, so always the chosen one; Select goes into its lockups, as Right does.
                SideRow(
                    words = stringResource(R.string.my_requests),
                    onSelect = { contentTarget(model.body, entry, retry, empty)?.let { runCatching { it.requestFocus() } } },
                    tag = "side my requests",
                    chosen = true,
                    modifier = Modifier
                        .focusRequester(mine)
                        .onFocusChanged { split.parkHasFocus = it.isFocused }
                        .focusProperties { up = barFocus.requests },
                )
                WaitingRow(bar)
            },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged {
                        split.contentHasFocus = it.hasFocus
                        if (!it.hasFocus) focusedKey[0] = null
                    },
            ) {
                when (body) {
                    RequestsBody.LOADING -> LoadingLine()
                    // The page hands focus on itself (`contentTarget`, decided as the body swaps), so the states do not.
                    RequestsBody.FAILED -> StateView(
                        StateKind.FAILED,
                        stringResource(R.string.requests_failed),
                        Modifier.padding(top = ListTop + 60.dp),
                        wordsTag = "failure text",
                        focusOnArrival = false,
                    ) { first ->
                        ProminentButton(
                            text = stringResource(if (model.retrying) R.string.trying else R.string.try_again),
                            onClick = model::retry,
                            busy = model.retrying,
                            modifier = first.focusRequester(retry).testTag("try again"),
                        )
                    }
                    RequestsBody.EMPTY -> NoRequests(empty, onBack)
                    RequestsBody.ROWS -> RequestGrid(
                        model = model,
                        grid = grid,
                        entry = entry,
                        lockups = lockups,
                        up = barFocus.requests,
                        now = now,
                        onOpen = onOpen,
                        onFocused = { key -> focusedKey[0] = key },
                        onMenu = { row ->
                            if (model.cancelling == null) {
                                giveBack = row.key
                                menuFor = row
                            }
                        },
                    )
                }
            }
        }
        // Shown on its own (a test's), the page draws the bar itself; in the app the home draws it over the page.
        if (!bar.hosted) TopBar(current = { Tab.REQUESTS }, content = bar)
        val menu = menuFor
        LockupMenu(
            open = menu != null,
            title = menu?.let { model.title(it)?.title } ?: stringResource(R.string.unknown_title),
            message = null,
            actions = listOf(
                LockupAction(stringResource(R.string.cancel_request), tag = "cancel ${menu?.key}", destructive = true) {
                    asking = menu
                    menuFor = null
                },
            ),
            onDismiss = { menuFor = null },
            tag = "request menu",
        )
        // Asked first (87); Cancel Request in red words (rule 2), Keep Request focused. Focus goes back to the lockup.
        val row = asking
        val name = row?.let { model.title(it)?.title }
        ConfirmDialog(
            open = row != null,
            title = stringResource(R.string.cancel_request_title),
            message = if (name != null) stringResource(R.string.cancel_request_body, name) else stringResource(R.string.cancel_request_body_untitled),
            confirm = stringResource(R.string.cancel_request),
            dismiss = stringResource(R.string.keep_request),
            onConfirm = {
                asking = null
                row?.let { asked ->
                    model.cancel(asked) { outcome ->
                        // The lockup goes: focus moves first to the one that takes its place (the next, else the one
                        // before, else My Requests), named here, then the lockup is dropped (when a focused control goes,
                        // Compose puts focus on the page's first, 149). The answer can come while the question is still
                        // drawn, its focus trap refusing the move: asked each frame until it takes.
                        if (outcome is CancelOutcome.Cancelled) {
                            // its lockup holds focus, or is about to have it back from the question
                            val held = focusedKey[0] == asked.key || giveBack == asked.key
                            if (giveBack == asked.key) giveBack = null
                            val rows = model.rows
                            val at = rows.indexOfFirst { it.id == outcome.id }
                            val next = rows.getOrNull(at + 1) ?: rows.getOrNull(at - 1)
                            scope.launch {
                                if (held) {
                                    val target = next?.let { lockups.of(it.key) } ?: mine
                                    for (frame in 0 until 10) {
                                        if (runCatching { target.requestFocus() }.getOrDefault(false)) break
                                        withFrameNanos { }
                                    }
                                }
                                model.drop(outcome.id)
                            }
                        } else {
                            // Not cancelled: the lockup stays, and it says why (no toast, A5). Its lockup has focus back.
                            problemKey = asked.key
                            problem = Problem(
                                words.getString(R.string.request_not_cancelled),
                                words.getString(if (outcome is CancelOutcome.NotPending) R.string.not_cancellable else R.string.modify_failed),
                            )
                        }
                    }
                }
            },
            onCancel = { asking = null },
            opener = null,
            tag = "confirm cancel request",
            cancelTag = "keep request",
        )
        // Not over another lockup's menu or question (opened in the moment before it shows): it waits until they have
        // gone, so its OK never hands focus past a menu still open (A9).
        ProblemAlert(
            problem.takeIf { menuFor == null && asking == null },
            onDone = {
                problem = null
                // back to the lockup it was about, while it is there (a re-read may have taken it away)
                problemKey?.let { key ->
                    giveBack = null
                    scope.launch { settleOnLockup(key, { key }, lockups) { k -> model.rows.any { it.key == k } } }
                }
                problemKey = null
            },
            opener = null,
            tag = "cancel problem",
            onShowing = { problemShown = it },
        )
    }
}

/**
 * "Waiting for Approval" (DECISIONS 339; tvOS's S33, in place of A2's words hung under the bar's Requests): while an
 * admin is kept on the TV, a row that opens everyone's requests (the PIN first when the admin is not active), its count
 * at its right above zero; for an account that may approve itself, the count in words only, dimmed and no stop for
 * focus, above zero; without the right, nothing.
 */
@Composable
private fun WaitingRow(bar: BarContent) {
    val waiting = bar.waiting()
    val onWaiting = bar.onWaiting
    val words = stringResource(R.string.waiting_row)
    when {
        onWaiting != null -> SideRow(
            words = words,
            onSelect = onWaiting,
            tag = "waiting",
            value = waiting?.takeIf { it > 0 }?.toString(),
            opens = true,
        )
        waiting != null && waiting > 0 -> SideRow(words = words, onSelect = {}, tag = "waiting", value = "$waiting", enabled = false)
    }
}

/** Seerr's download sync runs once a minute: a page reads the requests no oftener while one downloads. */
private const val RE_READ_MS = 60_000L

/** What holds focus in the grid's place: the lockup left, Try Again, the way back; nothing while the first page loads. */
private fun contentTarget(body: RequestsBody, entry: FocusRequester, retry: FocusRequester, empty: FocusRequester): FocusRequester? =
    when (body) {
        RequestsBody.ROWS -> entry
        RequestsBody.LOADING -> null
        RequestsBody.FAILED -> retry
        RequestsBody.EMPTY -> empty
    }


/**
 * The lockups, newest first, four across. A move recomposes the lockup it leaves and the one it reaches (DECISIONS 57).
 * More load as focus nears the end; a later page that fails ends the grid with Try Again, which keeps focus while its
 * page is out and then hands it to the first new lockup.
 */
@Composable
private fun RequestGrid(
    model: RequestsModel,
    grid: LazyGridState,
    entry: FocusRequester,
    lockups: LockupFocus,
    up: FocusRequester,
    now: Long,
    onOpen: (TitleCard) -> Unit,
    onFocused: (String) -> Unit,
    onMenu: (RequestRow) -> Unit,
) {
    val rows = model.rows
    val retryAtEnd = model.moreFailed
    val retry = remember { EndRetry() }
    EndRetryHandOff(retry, retryAtEnd, rows.size) { first ->
        model.focused(first.coerceAtMost(model.rows.lastIndex))
        settleFocus { entry }
    }
    val unknown = stringResource(R.string.unknown_title)
    val cancellingWords = stringResource(R.string.cancelling_request)
    val more = model.loadingMore || retryAtEnd
    LockupGrid(
        grid,
        top = ListTop,
        tag = "requests",
        modifier = Modifier.onFocusChanged { if (!it.hasFocus) model.left() },
    ) {
        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
            ReportRecomposition("request item ${row.key}")
            // Asked through a derived state, so a move recomposes the two lockups it touches (57).
            val isEntry by remember(index) { derivedStateOf { index == model.focusedIndex } }
            val state = model.titles[row.titleKey]
            val title = (state as? TitleState.Ready)?.title
            val card = requestCard(row, title).let { if (state == TitleState.Failed) it.copy(title = unknown) else it }
            val line = when {
                model.cancelling == row.id -> cancellingWords
                else -> requestLine(row, now)
            }
            PosterLockup(
                card = card,
                onSelect = {
                    model.returnToRow = true
                    onOpen(requestCard(row, model.title(row)))
                },
                modifier = Modifier.lockupPlace(index, rows.size, lockups.of(row.key), isEntry, entry, up = up, more = more),
                onFocused = {
                    onFocused(row.key)
                    model.focused(index)
                },
                status = row.badge?.let { LockupStatus(it, line, short = if (line == cancellingWords) null else requestWhen(row, now)) },
                tag = "request ${row.key}",
                onLongClick = if (canCancel(row)) {
                    { onMenu(row) }
                } else {
                    null
                },
            )
        }
        listEnd(loadingMore = model.loadingMore, failed = retryAtEnd, retrying = model.retryingMore, onRetry = model::retry, retry = retry)
    }
}

/**
 * What the line under a request's status says (tvOS's): the download's estimate while one runs; "2 days ago by User 1"
 * once somebody has moved it on (Seerr's "Modified … by …", Tom's 124); else when it was asked for.
 */
@Composable
private fun requestLine(row: RequestRow, now: Long): String? {
    val estimate = row.badge?.progress?.let { Formats.due(it.estimatedAt, now) }
    if (estimate != null) return stringResource(R.string.download_estimate, estimate.replaceFirstChar { it.lowercase() })
    val who = row.modifiedBy
    val modified = Formats.relative(row.modifiedAt, now)
    if (who != null && modified != null) return stringResource(R.string.when_by_who, modified, who)
    return Formats.relative(row.requestedAt, now)
}

/** When alone, for a line that does not fit a poster's width: when it was moved on, else asked for. */
private fun requestWhen(row: RequestRow, now: Long): String? = Formats.relative(if (row.modifiedBy == null) row.requestedAt else row.modifiedAt, now)

/** The first line's places, still, while the first page comes (tvOS's placeholders): the shape of what is coming. */
@Composable
internal fun LoadingLine() {
    Row(
        Modifier.padding(top = ListTop).testTag("loading"),
        horizontalArrangement = Arrangement.spacedBy(Metrics.LockupGap),
    ) {
        repeat(LIST_COLUMNS) { PlaceholderLockup(Modifier.height(StatusLockupHeight)) }
    }
}

/**
 * No requests: an empty `StateView` (A1), Hither's words, and a way back to Discover that holds focus, so focus never
 * lands on nothing (REVIEW.md).
 */
@Composable
private fun NoRequests(requester: FocusRequester, onBack: () -> Unit) {
    StateView(
        StateKind.EMPTY,
        stringResource(R.string.requests_empty),
        Modifier.padding(top = ListTop + 60.dp),
        detail = stringResource(R.string.requests_empty_hint),
        wordsTag = "requests empty",
        focusOnArrival = false,
    ) { first ->
        PlainButton(
            text = stringResource(R.string.back_to_discover),
            onClick = onBack,
            modifier = first.focusRequester(requester).testTag("back to discover"),
        )
    }
}
