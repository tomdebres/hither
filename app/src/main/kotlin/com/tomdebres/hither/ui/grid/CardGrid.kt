package com.tomdebres.hither.ui.grid

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.core.rules.GridCard
import com.tomdebres.hither.core.rules.PersonCard
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.ui.components.ReportRecomposition
import com.tomdebres.hither.ui.discover.RetryCard
import com.tomdebres.hither.ui.theme.Metrics
import com.tomdebres.hither.ui.theme.PlaceholderLockup
import com.tomdebres.hither.ui.theme.PortraitLockup
import com.tomdebres.hither.ui.theme.PosterLockup
import com.tomdebres.hither.ui.theme.PosterLockupHeight
import com.tomdebres.hither.ui.theme.mixesKinds

// A grid of lockups (A3, DECISIONS 320: tvOS's grids of poster lockups, people as portraits), shared by search, a
// keyword's titles, a collection's, a row's and the Movies and Series pages (M6), in dp on the 960 x 540 dp panel.

/** The field's, or a heading's, height above the grid. */
internal val GridHeaderHeight = 32.dp

/** The grid's own top padding: where its first row sits, and the line a focused row comes to (room for a focused
 * lockup's 1.1, 9 dp up). */
internal val GridPadding = 28.dp

/** From the screen's top to the grid's first row, under a header line at the top margin. */
internal val GridTop = Metrics.TopMargin + GridHeaderHeight + GridPadding
/** Room after the last row, so that it too can come up to the first row's line. */
internal val AfterLastRow = 540.dp - GridTop - PosterLockupHeight

/** Six cards across, two rows on the screen: a grid's screenful, and how far ahead it asks for more. */
const val GRID_COLUMNS = 6
const val FILLS_GRID = 2 * GRID_COLUMNS
const val GRID_LOOKAHEAD = 2 * GRID_COLUMNS

/**
 * The gap that spreads [GRID_COLUMNS] lockups across the width between the margins, so the grid is as wide as the field
 * (item 23); never under a shelf's own gap.
 */
@Composable
internal fun gridGap(): Dp {
    val width = LocalConfiguration.current.screenWidthDp.dp
    return ((width - Metrics.SideMargin * 2 - Metrics.PosterWidth * GRID_COLUMNS) / (GRID_COLUMNS - 1)).coerceAtLeast(Metrics.LockupGap)
}

/** The focused row comes to the first row's line: the grid scrolls a row at a time under it. */
@OptIn(ExperimentalFoundationApi::class)
internal class GridPivot(private val at: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - at
}

/** What a grid of cards reads and tells: its cards, where focus was, a later page's Try Again. */
interface CardGridModel {
    /** Titles, and in search people too (M6, DECISIONS 100). */
    val cards: List<GridCard>
    val gridState: LazyGridState

    /** The card the grid was left on, so focus comes back to it. */
    val focusedIndex: Int

    /** A later page failed: the cards stay, and a Try Again card ends the grid until its page comes. */
    val moreFailed: Boolean

    /** That Try Again is out: the card stays ("Trying…") and keeps focus until the answer. */
    val retryingMore: Boolean

    /** Coming back from a title's page: focus goes to the card left. */
    var returnToCard: Boolean

    /** A card has focus: remember it, and ask for more ahead of the end. */
    fun focused(index: Int)

    fun retry()
}

/**
 * The lockups: six across, spread to the margins, [Metrics.GridRowGap] between a row's captions and the next row's
 * pictures; titles as poster lockups (saying film or series where the grid mixes them, as search's does), people as
 * portraits. The focused row rests on the first row's line (the grid scrolls a row at a time under it, as Discover does);
 * a move recomposes the lockup it leaves and the one it reaches (DECISIONS 57).
 */
@Composable
fun CardGrid(model: CardGridModel, entry: FocusRequester, onOpen: (GridCard) -> Unit, modifier: Modifier, tag: String = "results") {
    val cards = model.cards
    val mixed = remember(cards) { mixesKinds(cards.filterIsInstance<TitleCard>()) }
    val retryAtEnd = model.moreFailed
    // Try Again at the end had focus and its page has come: focus goes to the first new card,
    // where Try Again was, rather than being left on nothing. Decided in the composition that
    // removes the card, while it still reports focus.
    var retryFocused by remember { mutableStateOf(false) }
    val countAtRetry = remember { IntArray(1) }
    if (retryAtEnd) countAtRetry[0] = cards.size
    val handOff = remember(retryAtEnd) { !retryAtEnd && retryFocused }
    LaunchedEffect(handOff) {
        if (!handOff) return@LaunchedEffect
        model.focused(countAtRetry[0].coerceAtMost(model.cards.lastIndex))
        repeat(5) {
            withFrameNanos { }
            if (runCatching { entry.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    LazyVerticalGrid(
        // Six 240 px cards spread across the 1728 px between the margins, as wide as the field above
        // them (Seerr's grid columns stretch; the polish audit's item 23): 58 px apart on the panel.
        columns = GridCells.Fixed(GRID_COLUMNS),
        state = model.gridState,
        contentPadding = PaddingValues(start = Metrics.SideMargin, end = Metrics.SideMargin, top = GridPadding, bottom = AfterLastRow),
        horizontalArrangement = Arrangement.spacedBy(gridGap(), Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(Metrics.GridRowGap),
        modifier = modifier
            .fillMaxSize()
            // Coming into the grid lands on the card it was left on.
            .focusProperties { onEnter = { entry.requestFocus() } }
            .focusGroup()
            .testTag(tag),
    ) {
        itemsIndexed(cards, key = { _, card -> card.key }) { index, card ->
            ReportRecomposition("card item ${card.key}")
            val isEntry by remember(index) { derivedStateOf { index == model.focusedIndex } }
            val onSelect = {
                model.returnToCard = true
                onOpen(card)
            }
            val place = Modifier
                .then(if (isEntry) Modifier.focusRequester(entry) else Modifier)
                // Fenced at a row's ends: Right from the last column does not wrap to the next row.
                .focusProperties {
                    if (index % GRID_COLUMNS == 0) left = FocusRequester.Cancel
                    if (index % GRID_COLUMNS == GRID_COLUMNS - 1 || (index == cards.lastIndex && !retryAtEnd)) {
                        right = FocusRequester.Cancel
                    }
                }
            when (card) {
                is TitleCard -> PosterLockup(card = card, onSelect = onSelect, onFocused = { model.focused(index) }, mixed = mixed, modifier = place)
                // A person among the results: the photo in a circle and the name (tvOS's portrait lockup).
                is PersonCard -> PortraitLockup(card.key, card.name, part = null, photo = card.photo, onSelect = onSelect, onFocused = { model.focused(index) }, modifier = place)
            }
        }
        // A later page failed: Try Again where the next card would be, as at a Discover row's
        // end; Select asks for that page again (DECISIONS 64).
        if (retryAtEnd) {
            item(key = "retry") {
                RetryCard(
                    onRetry = model::retry,
                    busy = model.retryingMore,
                    modifier = Modifier.onFocusChanged { retryFocused = it.isFocused }.focusProperties {
                        right = FocusRequester.Cancel
                        if (cards.size % GRID_COLUMNS == 0) left = FocusRequester.Cancel
                    },
                )
            }
        }
    }
}

/** Two rows of still lockup places while the first answer comes (tvOS's placeholders). */
@Composable
fun GridPlaceholders() {
    Column(Modifier.padding(start = Metrics.SideMargin, top = GridPadding), verticalArrangement = Arrangement.spacedBy(Metrics.GridRowGap)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(gridGap())) {
                repeat(GRID_COLUMNS) { PlaceholderLockup(Modifier.height(PosterLockupHeight)) }
            }
        }
    }
}
