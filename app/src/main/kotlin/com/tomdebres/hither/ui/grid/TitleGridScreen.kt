package com.tomdebres.hither.ui.grid

import com.tomdebres.hither.ui.theme.Page
import com.tomdebres.hither.ui.theme.PageKind
import com.tomdebres.hither.ui.theme.StateKind
import com.tomdebres.hither.ui.theme.StateView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.R
import com.tomdebres.hither.core.rules.GridCard
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.ui.theme.ProminentButton

/**
 * A page of titles under a heading ("zombie Movies"): Seerr's grid, as search lays it out, with the
 * heading where search has its field. Focus arrives on the first card, or on the card left when coming
 * back from a title's page; while the first page loads, placeholders pulse; a failure offers Try Again;
 * none is "No results.". Back is the caller's ([onBack]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TitleGridScreen(model: TitleGridModel, heading: String, onOpen: (TitleCard) -> Unit, onBack: () -> Unit) {
    val open: (GridCard) -> Unit = { card -> if (card is TitleCard) onOpen(card) }
    val entry = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val density = LocalDensity.current
    val pivot = remember(density) { GridPivot(with(density) { GridPadding.toPx() }) }
    LaunchedEffect(model) { model.start() }
    BackHandler { onBack() }
    val body = model.body
    // Focus as the body arrives: the card left (or the first), Try Again after a failure. The empty and
    // loading pages have nothing to focus; Back still leaves.
    var retryFocused by remember { mutableStateOf(false) }
    LaunchedEffect(body) {
        val target = when (body) {
            GridBody.RESULTS -> entry
            GridBody.FAILED -> retry
            else -> return@LaunchedEffect
        }
        model.returnToCard = false
        repeat(10) {
            withFrameNanos { }
            if (runCatching { target.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    // A pushed page: its title centred at the top (A1; Seerr's gradient heading went, the polish 2 audit's item 21's);
    // the grid runs to the sides with its own margins.
    Page(PageKind.Pushed(heading, titleTag = "grid heading"), bleed = true) {
        Column(Modifier.fillMaxSize()) {
            when (body) {
                GridBody.LOADING -> GridPlaceholders()
                GridBody.NO_RESULTS -> StateView(StateKind.EMPTY, stringResource(R.string.no_results), Modifier.padding(top = 60.dp), wordsTag = "no results")
                // The page hands focus to Try Again itself (above), as the state comes.
                GridBody.FAILED -> StateView(StateKind.FAILED, stringResource(R.string.grid_failed), Modifier.padding(top = 60.dp), wordsTag = "failure text", focusOnArrival = false) { first ->
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
                GridBody.RESULTS -> CompositionLocalProvider(LocalBringIntoViewSpec provides pivot) {
                    CardGrid(
                        model = model,
                        entry = entry,
                        onOpen = open,
                        modifier = Modifier.onFocusChanged { if (!it.hasFocus) model.left() },
                        tag = "grid",
                    )
                }
            }
        }
    }
}
