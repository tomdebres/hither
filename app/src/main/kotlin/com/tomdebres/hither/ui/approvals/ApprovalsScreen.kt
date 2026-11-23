package com.tomdebres.hither.ui.approvals

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.R
import kotlinx.coroutines.launch
import com.tomdebres.hither.core.rules.RequestFilter
import com.tomdebres.hither.core.rules.RequestRow
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.core.rules.requestCard
import com.tomdebres.hither.ui.components.ReportRecomposition
import com.tomdebres.hither.ui.requests.TitleState
import com.tomdebres.hither.ui.theme.ConfirmDialog
import com.tomdebres.hither.ui.theme.EndRetry
import com.tomdebres.hither.ui.theme.EndRetryHandOff
import com.tomdebres.hither.ui.theme.Formats
import com.tomdebres.hither.ui.theme.HitherType
import com.tomdebres.hither.ui.theme.LibrarySplit
import com.tomdebres.hither.ui.theme.LockupAction
import com.tomdebres.hither.ui.theme.LockupFocus
import com.tomdebres.hither.ui.theme.LockupGrid
import com.tomdebres.hither.ui.theme.LockupMenu
import com.tomdebres.hither.ui.theme.LockupStatus
import com.tomdebres.hither.ui.theme.Metrics
import com.tomdebres.hither.ui.theme.OwedFocus
import com.tomdebres.hither.ui.theme.Page
import com.tomdebres.hither.ui.theme.PageKind
import com.tomdebres.hither.ui.theme.PlainButton
import com.tomdebres.hither.ui.theme.PosterLockup
import com.tomdebres.hither.ui.theme.Problem
import com.tomdebres.hither.ui.theme.ProblemAlert
import com.tomdebres.hither.ui.theme.ProminentButton
import com.tomdebres.hither.ui.theme.SideRow
import com.tomdebres.hither.ui.theme.SplitFocus
import com.tomdebres.hither.ui.theme.StateKind
import com.tomdebres.hither.ui.theme.StateView
import com.tomdebres.hither.ui.theme.Tokens
import com.tomdebres.hither.ui.theme.listEnd
import com.tomdebres.hither.ui.theme.lockupPlace
import com.tomdebres.hither.ui.theme.settleFocus
import com.tomdebres.hither.ui.theme.settleOnLockup
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first

/** Tom's three of Seerr's nine filters (D3, tvOS's DECISIONS 259), the queue's own first. */
val QueueFilters = listOf(RequestFilter.PENDING, RequestFilter.APPROVED, RequestFilter.ALL)

/** Where the queue's first line of pictures sits under its title: room for a lifted lockup, the grid being cut there. */
private val QueueTop = Metrics.LiftRoom

/**
 * Everyone's requests, as the TV's admin (M5, DECISIONS 94), as tvOS's Library (H2's A6, DECISIONS 338; tvOS's
 * `ApprovalsView`): Seerr's "Requests" as the page's title; on the left the filters Pending, Approved and All (Tom's D3:
 * three of Seerr's nine), the chosen one ticked, and a line if the admin's rights no longer allow deciding; on the right
 * the requests as poster lockups four across, each with its status and "3 days ago by User 8" under the title.
 *
 * - **Select** on a request still to decide opens its dialog ([LockupMenu]): Approve, Decline (red words; it asks again,
 *   as Seerr cannot take a decline back) and Open Title; on any other it opens the title. One press, one decision; the
 *   lockup reads "Approving…" while it is out.
 * - **A decision in:** focus moves first to the next request still to decide (else the chosen filter), then the lockup
 *   takes its new status in place (`ApprovalsModel.focusMoved`), so nothing changes under focus.
 * - **Focus** arrives once, on the first list: the first request to decide, else the first request, else the chosen
 *   filter. Select on a filter chooses it and focus stays there; Right goes into the grid, Left back to the filter.
 * - The admin's calls go through `asAdmin` only (the model's source); the home takes the page away when the admin goes.
 */
@Composable
fun ApprovalsScreen(model: ApprovalsModel, onOpen: (TitleCard) -> Unit, onBack: () -> Unit) {
    LaunchedEffect(model) { model.open() }
    BackHandler { onBack() }
    val filterFocus = remember { QueueFilterFocus() }
    val chosen = filterFocus.of(model.filter)
    val lockups = remember { LockupFocus() }
    val retry = remember { FocusRequester() }
    val showAll = remember { FocusRequester() }
    // The lockup focus was last on in the grid, for Right from the filters: by request key; the first one at first.
    val entryKey = remember { mutableStateOf<String?>(null) }
    // A grid of its own for each filter, from the top.
    val grid = remember(model.filter) { LazyGridState() }
    val body = model.body

    // The request whose dialog is open, then the one Decline asks about; focus goes back to its lockup after.
    var choosing by remember { mutableStateOf<RequestRow?>(null) }
    var asking by remember { mutableStateOf<RequestRow?>(null) }
    var giveBack by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(choosing, asking) {
        val key = giveBack ?: return@LaunchedEffect
        if (choosing != null || asking != null) return@LaunchedEffect
        settleOnLockup(key, { giveBack }, lockups) { k -> model.rows.any { it.key == k } }
        if (giveBack == key) giveBack = null
    }

    val entry = {
        val rows = model.rows
        (rows.firstOrNull { it.key == entryKey.value } ?: rows.firstOrNull())?.let { lockups.of(it.key) }
    }
    val contentTarget = {
        when (model.body) {
            ApprovalsBody.ROWS -> entry()
            ApprovalsBody.FAILED -> retry
            ApprovalsBody.EMPTY -> if (model.filter == RequestFilter.ALL) null else showAll
            ApprovalsBody.LOADING -> null
        }
    }
    // Arrival, once, on the first list (the first answer after the page's read began): the first request to decide, else
    // the first request, else the chosen filter. Not keyed on the body, whose change as the read begins would cancel it.
    LaunchedEffect(model) {
        snapshotFlow { model.body }.dropWhile { it != ApprovalsBody.LOADING }.first { it != ApprovalsBody.LOADING }
        settleFocus {
            val rows = model.rows
            (rows.firstOrNull { model.decisions(it) } ?: rows.firstOrNull())?.let { lockups.of(it.key) } ?: filterFocus.of(model.filter)
        }
    }
    // What held focus in the grid's place is going (Try Again, Show All): focus to what replaces it (`OwedFocus`).
    val split = remember { SplitFocus() }
    OwedFocus(body, split, target = contentTarget, park = { filterFocus.of(model.filter) })
    // A decision is in: focus moves on first, then the lockup changes, so nothing changes under it.
    LaunchedEffect(model.focusAfter) {
        val after = model.focusAfter ?: return@LaunchedEffect
        // the decided lockup does not take focus back from its dialog: it goes on
        giveBack = null
        val next = (after as? FocusAfter.Row)?.let { row -> model.rows.firstOrNull { it.id == row.requestId } }
        val target = next?.let { lockups.of(it.key) } ?: filterFocus.of(model.filter)
        if (!runCatching { target.requestFocus() }.getOrDefault(false)) runCatching { filterFocus.of(model.filter).requestFocus() }
        withFrameNanos { }
        model.focusMoved()
    }

    // A pushed page, its title centred at the top (A1); the split runs to the sides with its own margins.
    Page(PageKind.Pushed(stringResource(R.string.requests_heading), titleTag = "approvals heading"), bleed = true) {
        LibrarySplit(
            top = QueueTop,
            sideEntry = chosen,
            contentEntry = contentTarget,
            side = {
                if (model.approver != null && !model.mayDecide) {
                    BasicText(
                        stringResource(R.string.approvals_no_right, model.approver?.displayName.orEmpty()),
                        style = HitherType.Caption.copy(color = Tokens.ErrorText),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("approvals no right"),
                    )
                }
                QueueFilters.forEach { filter ->
                    val isChosen = filter == model.filter
                    SideRow(
                        words = filter.words,
                        onSelect = {
                            if (isChosen) {
                                // The chosen one again: into its lockups.
                                contentTarget()?.let { runCatching { it.requestFocus() } }
                            } else {
                                split.forget()
                                entryKey.value = null
                                model.choose(filter)
                            }
                        },
                        tag = "filter ${filter.api}",
                        chosen = isChosen,
                        modifier = Modifier
                            .focusRequester(filterFocus.of(filter))
                            .onFocusChanged { if (isChosen) split.parkHasFocus = it.isFocused },
                    )
                }
            },
        ) {
            Box(Modifier.fillMaxSize().onFocusChanged { split.contentHasFocus = it.hasFocus }) {
                when (body) {
                    ApprovalsBody.ROWS -> QueueGrid(
                        model = model,
                        grid = grid,
                        lockups = lockups,
                        entryKey = entryKey,
                        onSelect = { row ->
                            if (model.decisions(row)) {
                                if (model.deciding == null && model.focusAfter == null) {
                                    giveBack = row.key
                                    choosing = row
                                }
                            } else {
                                onOpen(requestCard(row, model.titleOf(row)))
                            }
                        },
                    )
                    // Each state a `StateView` (A1); the page hands focus on itself (`OwedFocus`), so they do not take it.
                    ApprovalsBody.LOADING -> StateView(StateKind.LOADING, stringResource(R.string.approvals_loading), Modifier.padding(top = QueueTop + 60.dp), wordsTag = "approvals loading")
                    ApprovalsBody.FAILED -> StateView(StateKind.FAILED, stringResource(R.string.approvals_failed), Modifier.padding(top = QueueTop + 60.dp), wordsTag = "failure text", focusOnArrival = false) { first ->
                        ProminentButton(text = stringResource(R.string.try_again), onClick = model::retry, modifier = first.focusRequester(retry).testTag("try again"))
                    }
                    ApprovalsBody.EMPTY -> StateView(
                        StateKind.EMPTY,
                        stringResource(R.string.no_results),
                        Modifier.padding(top = QueueTop + 60.dp),
                        wordsTag = "approvals empty",
                        focusOnArrival = false,
                        // Seerr's own way out of an empty filter
                        action = if (model.filter == RequestFilter.ALL) {
                            null
                        } else {
                            { first ->
                                PlainButton(
                                    text = stringResource(R.string.show_all_requests),
                                    onClick = { model.choose(RequestFilter.ALL) },
                                    modifier = first.focusRequester(showAll).testTag("show all"),
                                )
                            }
                        },
                    )
                }
            }
        }
        // The request's dialog (tvOS's `confirmationDialog`): Approve, Decline, Open Title, then Cancel. Approve first and
        // focused, as tvOS's: Select on the lockup was the asking; Decline asks again.
        val row = choosing
        val unknown = stringResource(R.string.unknown_title)
        val whenBy = row?.let { requestedWords(it) }
        val seasons = row?.let { seasonWords(it) }
        LockupMenu(
            open = row != null,
            title = row?.let { model.titleOf(it)?.title } ?: unknown,
            message = Formats.join(listOf(whenBy, seasons)),
            actions = listOf(
                LockupAction(stringResource(R.string.approve), tag = "approve ${row?.key}") {
                    choosing = null
                    row?.let { model.decide(it.id, approve = true) }
                },
                LockupAction(stringResource(R.string.decline), tag = "decline ${row?.key}", destructive = true) {
                    asking = row
                    choosing = null
                },
                LockupAction(stringResource(R.string.open_title), tag = "open title ${row?.key}") {
                    giveBack = null
                    choosing = null
                    row?.let { onOpen(requestCard(it, model.titleOf(it))) }
                },
            ),
            onDismiss = { choosing = null },
            tag = "decision",
            dismissTag = "close decision",
        )
        // A decision that did not go through (A5; tvOS's alert, no toast either way): its dialog, then focus back on its
        // lockup, or, if a read since has taken it away, on the list's entry or the chosen filter.
        val failed = model.problem
        val words = LocalContext.current.resources
        val problem = remember(failed) {
            failed?.let {
                Problem(
                    words.getString(if (it.approve) R.string.not_approved else R.string.not_declined),
                    when (val decided = it.decided) {
                        is Decided.NotPending -> words.getString(R.string.decision_not_pending, decided.title.ifBlank { words.getString(R.string.unknown_title) })
                        Decided.NoRight -> words.getString(R.string.decision_no_right)
                        else -> words.getString(R.string.modify_failed)
                    },
                )
            }
        }
        val scope = rememberCoroutineScope()
        // Not over another request's dialog or Decline's question (opened in the moment before it shows): it waits until
        // they have gone, so its OK never hands focus past a dialog still open (A9).
        ProblemAlert(
            problem.takeIf { choosing == null && asking == null },
            onDone = {
                val id = model.problem?.requestId
                model.problemSeen()
                giveBack = null
                scope.launch {
                    settleFocus {
                        model.rows.firstOrNull { it.id == id }?.let { lockups.of(it.key) } ?: contentTarget() ?: filterFocus.of(model.filter)
                    }
                }
            },
            opener = null,
            tag = "decision problem",
        )
        // Decline asks again, in red words (rule 2), Cancel focused; focus goes back to the lockup after either.
        val declining = asking
        val name = declining?.let { model.titleOf(it)?.title }.orEmpty()
        ConfirmDialog(
            open = declining != null,
            title = stringResource(R.string.decline_title),
            message = stringResource(R.string.decline_body, name.ifBlank { unknown }, declining?.requester.orEmpty()),
            confirm = stringResource(R.string.decline),
            onConfirm = {
                asking = null
                declining?.let { model.decide(it.id, approve = false) }
            },
            onCancel = { asking = null },
            opener = null,
            tag = "confirm decline",
            cancelTag = "cancel decline",
        )
    }
}

/** The title [row] is for, as far as it is known. */
private fun ApprovalsModel.titleOf(row: RequestRow) = (titles[row.titleKey] as? TitleState.Ready)?.title

/** One focus target per filter's side row, made outside composition (lint's rule). */
private class QueueFilterFocus {
    private val byFilter = QueueFilters.associateWith { FocusRequester() }

    fun of(filter: RequestFilter): FocusRequester = byFilter[filter] ?: byFilter.getValue(RequestFilter.ALL)
}

/** The queue's lockups, four across; a decided one keeps its place with its new status until the list is read again. */
@Composable
private fun QueueGrid(
    model: ApprovalsModel,
    grid: LazyGridState,
    lockups: LockupFocus,
    /** The lockup left, for Right from the filters: read through a derived state, so a move recomposes two lockups. */
    entryKey: MutableState<String?>,
    onSelect: (RequestRow) -> Unit,
) {
    val rows = model.rows
    val unknown = stringResource(R.string.unknown_title)
    val approving = stringResource(R.string.approving)
    val declining = stringResource(R.string.declining)
    // A later page that failed ends the grid with Try Again (A9, as My Requests'): focus on to the first new lockup after.
    val retryAtEnd = model.moreFailed
    val retry = remember { EndRetry() }
    EndRetryHandOff(retry, retryAtEnd, rows.size) { first ->
        val key = model.rows.getOrNull(first.coerceAtMost(model.rows.lastIndex))?.key ?: return@EndRetryHandOff
        entryKey.value = key
        settleFocus { lockups.of(key) }
    }
    val more = model.loadingMore || retryAtEnd
    LockupGrid(grid, top = QueueTop, tag = "approvals list") {
        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
            ReportRecomposition("approval item ${row.key}")
            val state = model.titles[row.titleKey]
            val title = (state as? TitleState.Ready)?.title
            val card = requestCard(row, title).let { if (state == TitleState.Failed) it.copy(title = unknown) else it }
            val out = model.deciding?.takeIf { it.first == row.id }?.second
            val line = when (out) {
                true -> approving
                false -> declining
                null -> requestedWords(row)
            }
            val isEntry by remember(row.key, index) { derivedStateOf { entryKey.value?.let { it == row.key } ?: (index == 0) } }
            PosterLockup(
                card = card,
                onSelect = { onSelect(row) },
                modifier = Modifier.lockupPlace(index, rows.size, lockups.of(row.key), isEntry = isEntry, entry = lockups.of(row.key), more = more),
                onFocused = {
                    entryKey.value = row.key
                    model.focused(index)
                },
                status = row.badge?.let { LockupStatus(it, line, short = if (out == null) row.requester else null) },
                tag = "approval ${row.key}",
            )
        }
        listEnd(loadingMore = model.loadingMore, failed = retryAtEnd, retrying = model.retryingMore, onRetry = model::retry, retry = retry)
    }
}

/** "3 days ago by User 8", or either alone (Seerr's "Requested … by …"). */
@Composable
private fun requestedWords(row: RequestRow): String? {
    val time = Formats.relative(row.requestedAt)
    val who = row.requester
    return when {
        time != null && who != null -> stringResource(R.string.when_by_who, time, who)
        else -> time ?: who
    }
}

/** A series request's seasons: "Seasons 1, 2". */
@Composable
private fun seasonWords(row: RequestRow): String? {
    if (row.seasons.isEmpty()) return null
    return "${stringResource(if (row.seasons.size == 1) R.string.season_one else R.string.seasons)} ${row.seasons.joinToString(", ")}"
}
