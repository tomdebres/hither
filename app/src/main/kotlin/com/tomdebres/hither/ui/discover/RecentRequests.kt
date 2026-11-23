package com.tomdebres.hither.ui.discover

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.R
import com.tomdebres.hither.core.rules.RequestRow
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.core.rules.requestCard
import com.tomdebres.hither.ui.components.ReportRecomposition
import com.tomdebres.hither.ui.components.showsFocus
import com.tomdebres.hither.ui.requests.RequestsBody
import com.tomdebres.hither.ui.requests.RequestsModel
import com.tomdebres.hither.ui.requests.TitleState
import com.tomdebres.hither.ui.theme.BackdropLockup
import com.tomdebres.hither.ui.theme.BackdropLockupHeight
import com.tomdebres.hither.ui.theme.Metrics
import com.tomdebres.hither.ui.theme.PlaceholderLockup
import com.tomdebres.hither.ui.theme.RequesterLine
import com.tomdebres.hither.ui.theme.Shelf
import com.tomdebres.hither.ui.theme.ShelfTitle
import com.tomdebres.hither.ui.theme.StatusLabel

/** How many requests the row shows: Seerr's `take=10`. */
const val RECENT_TAKE = 10

/** What the row is: placeholders while the requests are on their way, their cards, or nothing. */
enum class RecentBody { LOADING, CARDS, NONE }

/**
 * Whether Discover shows Recent Requests: while its model reads the first page, its placeholders; with
 * requests, their cards; with none (or a failed read), no row at all, as Seerr's `RecentRequestsSlider`
 * returns nothing for an empty list. A failed read is read again when Discover next comes up.
 */
fun recentBody(requests: RequestsModel): RecentBody = when {
    requests.rows.isNotEmpty() -> RecentBody.CARDS
    requests.body == RequestsBody.LOADING -> RecentBody.LOADING
    else -> RecentBody.NONE
}

/**
 * Seerr's Recent Requests, heading Discover (`docs/design/seerr-reference.md`, 2.6 and 2.10; Tom's
 * call, DECISIONS 80): the ten newest requests as Seerr's slider asks for them, everyone's where the
 * account may see them and its own otherwise, each card naming who asked where Seerr's would (Tom,
 * 2026-11-13, DECISIONS 118). A row as the others: its title, then cards moving under a still focus,
 * fenced at both ends, coming back to the card left. Select opens the title's page with the card a
 * Discover row would draw. While loading, one target holds focus and hands it to the first card.
 */
/**
 * Seerr's Recent Requests, heading Discover (`docs/design/seerr-reference.md`, 2.6 and 2.10; Tom's call, DECISIONS 80):
 * the ten newest requests as Seerr's slider asks for them, everyone's where the account may see them and its own
 * otherwise. Since A3 (DECISIONS 320) a shelf of backdrop lockups as tvOS's: the title's backdrop, its name under it, and
 * who asked (where Seerr's card would say, Tom, DECISIONS 118) beside the request's glyph and word. A row as the others:
 * its header, lockups moving under a still focus, fenced at both ends, coming back to the lockup left. Select opens the
 * title's page with the card a Discover row would draw. While loading, one target holds focus and hands it to the first
 * lockup.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RecentRow(
    requests: RequestsModel,
    body: RecentBody,
    entry: FocusRequester,
    model: DiscoverModel,
    pivot: BringIntoViewSpec,
    onFocus: (Boolean) -> Unit,
    onOpen: (TitleCard) -> Unit,
    /** Up from the row: the bar's Discover tab, said outright (a wide card's nearest control could be search). */
    up: FocusRequester,
    modifier: Modifier = Modifier,
) {
    var holderFocused by remember { mutableStateOf(false) }
    // The placeholders held focus and the lockups have come: the first takes it (as a row's notice hands it on,
    // DECISIONS 42), decided in the composition that swaps the lockups in.
    val handOff = remember(body == RecentBody.CARDS) { body == RecentBody.CARDS && holderFocused }
    // The placeholders stay, holding focus, until it is handed on (A3, DECISIONS 326): removed in the composition that
    // swaps the lockups in, they took focus with them before the lockups were laid out, Compose put it on the first
    // focusable down from the screen's top (the next row's), and the hand-off brought it back a frame later.
    var heldTooLong by remember { mutableStateOf(false) }
    LaunchedEffect(handOff) {
        // Not if focus was sent elsewhere meanwhile: a plan that put another row on top (polish 2) asked for it in
        // the same frames, and this hand-off would take it back.
        if (!handOff || !model.inRecent) return@LaunchedEffect
        repeat(5) {
            withFrameNanos { }
            // and again after each frame: the plan can move the top in the frame waited for (it sets `inRecent` false as
            // it asks for focus), and the hand-off took focus back from it, the page opening a row down (A2, DECISIONS 317)
            if (!model.inRecent) return@LaunchedEffect
            if (runCatching { entry.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
        heldTooLong = true
    }
    Column(
        modifier
            .fillMaxWidth()
            .height(Metrics.ShelfHeader + BackdropLockupHeight)
            .onFocusChanged { onFocus(it.hasFocus) }
            .testTag("recent requests"),
    ) {
        ShelfTitle(stringResource(R.string.recent_requests))
        // The lockups, and over them the placeholders while they hold focus: one place in the composition for each,
        // whichever shows, so the placeholders' node (and its focus) lasts until the hand-off moves focus off it.
        Box {
            if (body == RecentBody.CARDS) {
                val rows = requests.rows.take(RECENT_TAKE)
                CompositionLocalProvider(LocalBringIntoViewSpec provides pivot) {
                    Shelf(state = rememberLazyListState(), entry = entry, height = BackdropLockupHeight) {
                        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                            ReportRecomposition("recent item ${row.key}")
                            val isEntry by remember(index) { derivedStateOf { index == model.recentIndex.coerceAtMost(rows.lastIndex) } }
                            RequestLockup(
                                row = row,
                                title = requests.titles[row.titleKey],
                                requester = row.requester?.takeIf { model.seesRequesters },
                                onSelect = { card -> onOpen(card) },
                                onFocused = { model.recentFocused(index) },
                                modifier = Modifier
                                    .then(if (isEntry) Modifier.focusRequester(entry) else Modifier)
                                    .focusProperties {
                                        this.up = up
                                        if (index == 0) left = FocusRequester.Cancel
                                        if (index == rows.lastIndex) right = FocusRequester.Cancel
                                    },
                            )
                        }
                    }
                }
            }
            if (body != RecentBody.CARDS || (holderFocused && !heldTooLong)) {
                // One target while the requests come: three placeholders in the shelf's place, the first drawn focused. The
                // row's way in is the lockups' once they have come.
                Row(
                    Modifier
                        .padding(start = Metrics.SideMargin)
                        .height(BackdropLockupHeight)
                        .then(if (body != RecentBody.CARDS) Modifier.focusRequester(entry) else Modifier)
                        .onFocusChanged {
                            holderFocused = it.isFocused
                            // the placeholder holding focus counts as the row's place, as its first lockup would
                            if (it.isFocused) model.recentFocused(0)
                        }
                        .focusProperties {
                            this.up = up
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .semantics { showsFocus = holderFocused }
                        .testTag("recent loading"),
                    horizontalArrangement = Arrangement.spacedBy(Metrics.LockupGap),
                    verticalAlignment = Alignment.Top,
                ) {
                    repeat(3) {
                        PlaceholderLockup(width = Metrics.BackdropWidth, height = Metrics.BackdropHeight, focused = holderFocused && it == 0)
                    }
                }
            }
        }
    }
}

/**
 * One request as a backdrop lockup (tvOS's `RequestCardView`): the title's backdrop, its name under it (a placeholder
 * bar until the list has it, "Unknown Title" if it could not be read), then who asked ([requester], where the account may
 * see it) and the request's glyph and word. Tagged `recent <key>`.
 */
@Composable
private fun RequestLockup(
    row: RequestRow,
    title: TitleState?,
    requester: String?,
    onSelect: (TitleCard) -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = (title as? TitleState.Ready)?.title
    val name = when (title) {
        is TitleState.Ready -> ready!!.title
        TitleState.Failed -> stringResource(R.string.unknown_title)
        else -> null
    }
    BackdropLockup(
        picture = ready?.backdrop,
        title = name,
        description = name ?: "",
        onSelect = { onSelect(requestCard(row, ready)) },
        tag = "recent ${row.key}",
        modifier = modifier,
        onFocused = onFocused,
        titleTag = "recent title",
    ) {
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (requester != null) RequesterLine(requester)
            row.badge?.let { StatusLabel(it) }
        }
    }
}
