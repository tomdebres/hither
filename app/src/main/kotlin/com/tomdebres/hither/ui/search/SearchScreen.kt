package com.tomdebres.hither.ui.search

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import com.tomdebres.hither.ui.theme.Metrics
import com.tomdebres.hither.ui.theme.Page
import com.tomdebres.hither.ui.theme.PageKind
import com.tomdebres.hither.ui.theme.StateKind
import com.tomdebres.hither.ui.theme.StateView
import com.tomdebres.hither.ui.grid.CardGrid
import com.tomdebres.hither.ui.grid.GridPadding
import com.tomdebres.hither.ui.grid.GridPivot
import com.tomdebres.hither.ui.grid.GridPlaceholders
import androidx.compose.ui.unit.Dp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.R
import com.tomdebres.hither.core.rules.GridCard
import com.tomdebres.hither.ui.theme.ProminentButton
import com.tomdebres.hither.ui.components.EntryField
import com.tomdebres.hither.ui.components.rememberEntryFieldState
import kotlinx.coroutines.launch

/**
 * The search page, as the Apple TV app's (A8, DECISIONS 347; tvOS's `SearchView`): the field across the top as an `EntryField` capsule,
 * and under it the results six across, titles as poster lockups and people as portraits (A3's `CardGrid`), or a
 * `StateView` on why there are none, in tvOS's words ("No Results for “…”" over "Check the spelling or try a new
 * search.", the system's own for a search that found nothing). Typing asks once the words have rested 300 ms (the
 * model's). Arriving from the bar, the keyboard opens on the field at once (the user pressed Select on search to type);
 * coming back from a title's page, focus returns to the card left, with the words and the results as they were. Down
 * from the field reaches the grid, Up from its first row the field. Back from the grid goes to the field, Back from the
 * field is the caller's ([onBack]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    model: SearchModel,
    /** A result: a title's card opens its page, a person's (M6) theirs. */
    onOpen: (GridCard) -> Unit,
    onBack: () -> Unit,
    /** Room left at the field's right end: the admin mark's, while it shows (DECISIONS 93). */
    endInset: Dp = 0.dp,
) {
    val field = rememberEntryFieldState()
    val entry = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val pivot = remember(density) { GridPivot(with(density) { GridPadding.toPx() }) }
    var gridHasFocus by remember { mutableStateOf(false) }
    val body = model.body

    LaunchedEffect(Unit) {
        val backToCard = model.returnToCard && model.cards.isNotEmpty()
        model.returnToCard = false
        repeat(10) {
            withFrameNanos { }
            val done = if (backToCard) {
                runCatching { entry.requestFocus() }.getOrDefault(false)
            } else {
                runCatching { field.focus.requestFocus() }.getOrDefault(false)
            }
            if (done) {
                if (!backToCard) field.edit()
                return@LaunchedEffect
            }
        }
    }
    // Try Again had focus and its answer has come: focus goes to the first result, or to the
    // field when there is none, rather than being left on nothing. Decided in the composition
    // that swaps the button out, while it still reports focus (as Discover's rows hand off).
    var retryFocused by remember { mutableStateOf(false) }
    val handOff = remember(body) { if (body != SearchBody.FAILED && retryFocused) body else null }
    LaunchedEffect(handOff) {
        val target = when (handOff) {
            null -> return@LaunchedEffect
            SearchBody.RESULTS -> entry
            else -> field.focus
        }
        repeat(5) {
            withFrameNanos { }
            if (runCatching { target.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    // Back from the grid: to the field, and the grid back to its top.
    BackHandler(enabled = gridHasFocus) {
        field.focus.requestFocus()
        scope.launch { model.gridState.scrollToItem(0) }
    }
    BackHandler(enabled = !gridHasFocus && !field.editing) { onBack() }

    // A pushed page with no title: the field across its top, at the margins (tvOS's search field).
    Page(PageKind.Pushed(null)) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Metrics.TopMargin))
            EntryField(
                value = model.typed,
                onValueChange = model::type,
                placeholder = stringResource(R.string.search_placeholder),
                state = field,
                imeAction = ImeAction.Search,
                search = true,
                tag = "search field",
                onImeAction = model::submit,
                modifier = Modifier
                    .padding(start = Metrics.SideMargin, end = Metrics.SideMargin + endInset)
                    .fillMaxWidth()
                    .focusProperties {
                        down = when (body) {
                            SearchBody.RESULTS -> entry
                            SearchBody.FAILED -> retry
                            else -> FocusRequester.Cancel
                        }
                    },
            )
            when (body) {
                // Each a `StateView` (A1); the field keeps focus while it is being typed in, so none takes it on arrival.
                SearchBody.EMPTY -> StateView(StateKind.EMPTY, stringResource(R.string.search_empty), Modifier.padding(top = 60.dp), icon = Icons.Outlined.Search, wordsTag = "search notice")
                SearchBody.NO_RESULTS -> StateView(
                    StateKind.EMPTY,
                    stringResource(R.string.no_results_for, model.query ?: model.typed.trim()),
                    Modifier.padding(top = 60.dp),
                    detail = stringResource(R.string.no_results_hint),
                    icon = Icons.Outlined.Search,
                    wordsTag = "no results",
                    detailTag = "no results hint",
                )
                SearchBody.LOADING -> GridPlaceholders()
                SearchBody.FAILED -> StateView(StateKind.FAILED, stringResource(R.string.search_failed), Modifier.padding(top = 60.dp), wordsTag = "failure text", focusOnArrival = false) { first ->
                    ProminentButton(
                        text = stringResource(if (model.retrying) R.string.trying else R.string.try_again),
                        onClick = model::retry,
                        busy = model.retrying,
                        modifier = first
                            .focusRequester(retry)
                            .onFocusChanged { retryFocused = it.isFocused }
                            .testTag("try again"),
                    )
                }
                SearchBody.RESULTS -> CompositionLocalProvider(LocalBringIntoViewSpec provides pivot) {
                    CardGrid(
                        model = model,
                        entry = entry,
                        onOpen = onOpen,
                        modifier = Modifier.onFocusChanged {
                            gridHasFocus = it.hasFocus
                            if (!it.hasFocus) model.left()
                        },
                    )
                }
            }
        }
    }
}

