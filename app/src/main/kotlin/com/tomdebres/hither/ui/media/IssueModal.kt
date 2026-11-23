package com.tomdebres.hither.ui.media

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.tomdebres.hither.R
import com.tomdebres.hither.core.rules.ISSUE_KINDS
import com.tomdebres.hither.core.rules.IssueDraft
import com.tomdebres.hither.core.rules.MediaPage
import com.tomdebres.hither.core.rules.issueEpisodes
import com.tomdebres.hither.core.rules.issueSeasons
import com.tomdebres.hither.ui.components.EntryField
import com.tomdebres.hither.ui.theme.PanelAction
import com.tomdebres.hither.ui.theme.PanelHeader
import com.tomdebres.hither.ui.theme.PanelOption
import com.tomdebres.hither.ui.theme.PanelPick
import com.tomdebres.hither.ui.theme.SidePanel

/**
 * Seerr's "Report an Issue" (`CreateIssueModal`, M6, DECISIONS 106) as a [SidePanel] (A5; tvOS's `IssueSheet`): the
 * title's name over, for a series, the affected season and episode (those in the library, "All" first; the one season
 * chosen when it is alone, core's `issueDraft`), then Seerr's four types, each a row with its choice at the right that
 * opens the choices as a list in the panel (`PanelPick`: Seerr's radio rows and select boxes went); the words under
 * "What's wrong?" (required, typed with the keyboard through `EntryField`: Select opens it, Done lands on the button;
 * Seerr's red asterisk went, S23); then Submit Issue last. One press, one report ("Submitting…" while out, keeping focus).
 *
 * Focus arrives on [entry]: the season where there is one to choose, else the type. Back closes it (the caller closes
 * the form, unless a report is out), a list open in it first; there is no Cancel. Kept composed while closed ([open]).
 * [held]: a report that failed keeps the form as it was while out ("Submitting…", Back not taken) until its dialog
 * has gone (A9).
 */
@Composable
fun IssuePanel(
    model: MediaModel,
    page: MediaPage?,
    draft: IssueDraft?,
    open: Boolean,
    entry: FocusRequester,
    submit: FocusRequester,
    held: Boolean = false,
    onSend: () -> Unit,
) {
    val seasons = remember(page) { page?.let(::issueSeasons).orEmpty() }
    val busy = model.reporting || held
    SidePanel(
        open = open && page != null && draft != null,
        title = stringResource(R.string.report_issue),
        onClose = { if (!held) model.closeIssue() },
        opener = null,
        tag = "issue modal",
        entry = entry,
        action = {
            PanelAction(
                words = stringResource(if (busy) R.string.submitting_issue else R.string.submit_issue),
                onClick = onSend,
                enabled = draft?.message?.isNotBlank() == true,
                busy = busy,
                tag = "issue submit",
                modifier = Modifier.focusRequester(submit),
            )
        },
    ) {
        if (page == null || draft == null) return@SidePanel
        PanelHeader(page.title, tag = "issue subject")
        if (seasons.isNotEmpty()) {
            val episodes = if (draft.season > 0) issueEpisodes(page, draft.season) else 0
            PanelPick(
                title = stringResource(R.string.affected_season),
                options = listOf(PanelOption(0, stringResource(R.string.issue_all_seasons))) +
                    seasons.map { PanelOption(it, stringResource(R.string.season_n, it)) },
                selection = draft.season,
                onPick = { model.editIssue(draft.copy(season = it, episode = 0)) },
                tag = "issue season",
                modifier = Modifier.focusRequester(entry),
            )
            PanelPick(
                title = stringResource(R.string.affected_episode),
                options = listOf(PanelOption(0, stringResource(R.string.all_episodes))) +
                    (1..episodes).map { PanelOption(it, stringResource(R.string.episode_n, it)) },
                selection = draft.episode,
                onPick = { model.editIssue(draft.copy(episode = it)) },
                tag = "issue episode",
                // Only once a season is chosen: "All Seasons" has no episodes to name.
                enabled = episodes > 0,
            )
        }
        PanelPick(
            title = stringResource(R.string.issue_type),
            options = ISSUE_KINDS.map { PanelOption(it.type, it.label) },
            selection = draft.type,
            onPick = { model.editIssue(draft.copy(type = it)) },
            tag = "issue type",
            modifier = if (seasons.isEmpty()) Modifier.focusRequester(entry) else Modifier,
        )
        PanelHeader(stringResource(R.string.whats_wrong), tag = "label what's wrong")
        EntryField(
            value = draft.message,
            onValueChange = { model.editIssue(draft.copy(message = it)) },
            placeholder = stringResource(R.string.issue_detail),
            imeAction = ImeAction.Done,
            tag = "issue message",
            modifier = Modifier.fillMaxWidth(),
            // Done lands on the button, as the keyboard's last field does in a form.
            onImeAction = { runCatching { submit.requestFocus() } },
        )
    }
}
