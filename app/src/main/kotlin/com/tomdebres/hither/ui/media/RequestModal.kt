package com.tomdebres.hither.ui.media

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.tomdebres.hither.R
import com.tomdebres.hither.core.rules.MediaPage
import com.tomdebres.hither.core.rules.RequestModalView
import com.tomdebres.hither.core.rules.SeasonRow
import com.tomdebres.hither.core.rules.requestModal
import com.tomdebres.hither.ui.components.ReportRecomposition
import com.tomdebres.hither.ui.theme.ConfirmDialog
import com.tomdebres.hither.ui.theme.Formats
import com.tomdebres.hither.ui.theme.PanelAction
import com.tomdebres.hither.ui.theme.PanelChoice
import com.tomdebres.hither.ui.theme.PanelHeader
import com.tomdebres.hither.ui.theme.PanelNote
import com.tomdebres.hither.ui.theme.ProblemDialog
import com.tomdebres.hither.ui.theme.SidePanel
import com.tomdebres.hither.ui.theme.SidePanelScope
import com.tomdebres.hither.ui.theme.StatusLabel

// A title's request (A5, H2's X6; tvOS's `Title/RequestModal.swift`). Seerr's web modal, its season table, switches,
// quota ring and backdrop band went: a film has nothing to choose, so it is a question; a series chooses its seasons, so
// it is a form of choices.

/**
 * What a request will come to, in sentences (tvOS's `RequestNotes`, the review's S15, S16): Seerr's "approved
 * automatically" without its coloured mark, and the quota as words ("2 movie requests left. You may request 3 movies
 * every 7 days."), not Seerr's heat-coloured ring.
 */
@Composable
private fun requestNotes(view: RequestModalView): List<String> {
    val automatic = stringResource(R.string.approved_automatically)
    val quota = view.quota?.let { stringResource(R.string.quota_sentence, it.text.replaceFirstChar(Char::uppercaseChar), it.allowance) }
    return listOfNotNull(automatic.takeIf { view.approvedAutomatically }, quota)
}

/**
 * A film's request, asked in a [ConfirmDialog] (the review's F2, F19; tvOS's alert): "Request Coco?" (or "Request Coco
 * in 4K?" for the 4K copy), the notes as its message, then Request, focused (Select on the page's button was already the
 * asking), and Cancel. Past the quota it says so with OK alone (a [ProblemDialog]). It goes as Request is pressed: while
 * the request is out the page's button says "Requesting…" ([open] is false), and focus is back on it ([opener]).
 */
@Composable
fun RequestQuestion(model: MediaModel, page: MediaPage?, draft: RequestDraft?, open: Boolean, opener: FocusRequester?) {
    val view = if (page != null && draft != null) requestModal(page, draft.is4k, draft.selected, sending = false) else null
    val notes = if (view != null) requestNotes(view) else emptyList()
    val message = notes.joinToString("\n").ifEmpty { null }
    val asks = view == null || view.primaryEnabled
    val name = page?.title.orEmpty()
    ConfirmDialog(
        open = open && asks,
        title = if (draft?.is4k == true) stringResource(R.string.request_question_4k, name) else stringResource(R.string.request_question, name),
        message = message,
        confirm = stringResource(R.string.request),
        onConfirm = model::send,
        onCancel = model::cancel,
        opener = opener,
        tag = "request modal",
        cancelTag = "cancel",
        confirmTag = "primary",
        destructive = false,
        focusConfirm = true,
    )
    ProblemDialog(
        open = open && !asks,
        title = view?.primary.orEmpty(),
        message = message,
        onDismiss = model::cancel,
        opener = opener,
        tag = "request modal",
    )
}

/**
 * A series' request as a [SidePanel] (the review's X6, F19; tvOS's `RequestSheet`): Seerr's title ("Request Series",
 * "Request Series in 4K"), the series' name over its seasons as ticked rows: "All Seasons" first, then "Season 2 · 66
 * Episodes" each; a season already taken is on, dimmed, and shows its status in place of the tick (the only one that
 * says a status: an open season without a tick already says it is not requested, S6); past the quota a season is
 * greyed and stays off. With partial requests off the rows are to read: every open season goes, ticked and still. The
 * notes under them, and the primary at the foot ("Request 2 Seasons"; "Requesting…" while out, keeping focus). Back
 * closes it (the caller cancels); there is no Cancel.
 *
 * Focus arrives on [entry]: opened from a season's card (A4, tvOS's D2), that season is ticked, its row brought into view
 * and the primary has focus, so a second Select asks for it rather than unticking it (tvOS's polish 6); else the first
 * open season, else the primary. The caller puts focus back after (the card, the next season along, or the button).
 * Kept composed while closed ([open]).
 */
@Composable
fun RequestPanel(model: MediaModel, page: MediaPage?, draft: RequestDraft?, open: Boolean, entry: FocusRequester) {
    val computed = if (page != null && draft != null) requestModal(page, draft.is4k, draft.selected, model.sending) else null
    // One rows list per page and copy: a flip then recomposes the row flipped, not every row (the rows change with the
    // pick only where it reaches the quota and greys the rest, M6).
    val greyed = computed?.rows.orEmpty().filter { it.overQuota }.map { it.seasonNumber }
    val rows = remember(page, draft?.is4k, greyed) { computed?.rows.orEmpty() }
    val view = computed?.copy(rows = rows)
    val toggle = remember(model) { { season: Int -> model.toggle(season) } }
    val toggleAll = remember(model) { { model.toggleAll() } }
    val chosen = draft?.season.takeIf { view?.primaryEnabled == true }
    val openRows = view?.rows.orEmpty().filter { !it.taken }
    val firstOpen = openRows.firstOrNull()?.seasonNumber?.takeIf { view?.picker == true && chosen == null }
    SidePanel(
        open = open && view != null,
        title = view?.title.orEmpty(),
        onClose = model::cancel,
        opener = null,
        tag = "request modal",
        entry = entry,
        action = view?.let { shown ->
            {
                PanelAction(
                    words = shown.primary,
                    onClick = model::send,
                    // While the request is out it keeps focus but does nothing (one press, one request).
                    enabled = shown.primaryEnabled,
                    busy = model.sending,
                    tag = "primary",
                    modifier = if (firstOpen == null) Modifier.focusRequester(entry) else Modifier,
                )
            }
        },
    ) {
        if (view == null || draft == null) return@SidePanel
        ReportRecomposition("season table")
        PanelHeader(view.subtitle, tag = "request subject")
        val allOn = openRows.isNotEmpty() && openRows.all { it.seasonNumber in draft.selected }
        PanelChoice(
            words = stringResource(R.string.all_seasons),
            on = allOn || (!view.picker && openRows.isNotEmpty()),
            onSelect = toggleAll,
            tag = "season all",
            enabled = view.picker && openRows.isNotEmpty(),
        )
        view.rows.forEach { row ->
            SeasonChoice(
                row = row,
                on = row.taken || row.seasonNumber in draft.selected || (!view.picker && !row.taken),
                picker = view.picker,
                entry = if (row.seasonNumber == firstOpen) entry else null,
                inView = row.seasonNumber == chosen,
                onToggle = toggle,
            )
        }
        val notes = requestNotes(view)
        if (notes.isNotEmpty()) {
            PanelNote(notes.joinToString(" "), tag = if (view.approvedAutomatically) "approved automatically" else "request notes")
        }
    }
}

/**
 * One season ("Season 2 · 66 Episodes"), a composable of its own so a flip or a move recomposes that row alone: ticked
 * where on; taken, it is dimmed and shows its status in place of the tick; past the quota greyed and off.
 */
@Composable
private fun SidePanelScope.SeasonChoice(row: SeasonRow, on: Boolean, picker: Boolean, entry: FocusRequester?, inView: Boolean, onToggle: (Int) -> Unit) {
    ReportRecomposition("season ${row.seasonNumber}")
    val episodes = if (row.episodes == 1) stringResource(R.string.episode_one) else stringResource(R.string.episode_count, row.episodes)
    PanelChoice(
        words = Formats.join(listOf(row.name, episodes)).orEmpty(),
        on = on,
        onSelect = { onToggle(row.seasonNumber) },
        tag = "season ${row.seasonNumber}",
        modifier = if (entry != null) Modifier.focusRequester(entry) else Modifier,
        enabled = picker && !row.taken && !(row.overQuota && !on),
        ticks = !row.taken,
        inView = inView,
        status = if (row.taken) {
            { Box(Modifier.alpha(TakenAlpha)) { StatusLabel(row.badge) } }
        } else {
            null
        },
    )
}

/** A taken season's status, as dimmed as its words (a disabled row's). */
private const val TakenAlpha = 0.5f
