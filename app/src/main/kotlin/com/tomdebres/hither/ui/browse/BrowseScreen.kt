package com.tomdebres.hither.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.R
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.rules.BrowseFilters
import com.tomdebres.hither.core.rules.GridCard
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.core.rules.Years
import com.tomdebres.hither.core.rules.sortOptions
import com.tomdebres.hither.ui.components.FocusOnArrival
import com.tomdebres.hither.ui.components.HitherIcons
import com.tomdebres.hither.ui.grid.CardGrid
import com.tomdebres.hither.ui.grid.GridBody
import com.tomdebres.hither.ui.grid.GridPadding
import com.tomdebres.hither.ui.grid.GridPivot
import com.tomdebres.hither.ui.grid.GridPlaceholders
import com.tomdebres.hither.ui.theme.HitherListItem
import com.tomdebres.hither.ui.theme.Metrics
import com.tomdebres.hither.ui.theme.Overlay
import com.tomdebres.hither.ui.theme.PanelChoice
import com.tomdebres.hither.ui.theme.PanelOption
import com.tomdebres.hither.ui.theme.PanelPick
import com.tomdebres.hither.ui.theme.Page
import com.tomdebres.hither.ui.theme.PageKind
import com.tomdebres.hither.ui.theme.PlainButton
import com.tomdebres.hither.ui.theme.ProminentButton
import com.tomdebres.hither.ui.theme.RowValue
import com.tomdebres.hither.ui.theme.SidePanel
import com.tomdebres.hither.ui.theme.SidePanelScope
import com.tomdebres.hither.ui.theme.StateKind
import com.tomdebres.hither.ui.theme.StateView
import com.tomdebres.hither.ui.theme.Tokens

/**
 * The Movies or Series page, as the Apple TV app's (A8, DECISIONS 345-346; tvOS's `BrowseView`; Seerr's page, M6,
 * 102): a pushed page, its title centred and grey at the top margin, Sort and Filters at the right of the title's line,
 * over the titles six across on A3's lockups. Focus arrives on the first lockup (or the one left, back from a title's
 * page); Up from the grid's first line reaches Sort and Filters.
 *
 * - **Sort** says the sort in use in tvOS's words ("Most Popular", [SortName]) and opens the sort list under it: Seerr's
 *   eight, each pair's usual way first, the one in use ticked and focused ([SortList]).
 * - **Filters** says how many are set ("Filters (1)") and opens the filters in the panel from the right: a row each for
 *   the decades, the genres, the original language and the streaming services, each opening its choices, and "Clear
 *   Filters" while any is set ([FilterPanel]). What the panel shows is applied as it closes.
 *
 * After either, focus is back on its button and the grid is read again from the top (a new grid: the model's paging,
 * and A3's focus memory for the one left). Back is the caller's.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowseScreen(model: BrowseModel, onOpen: (TitleCard) -> Unit, onBack: () -> Unit) {
    val entry = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val sortButton = remember { FocusRequester() }
    val filtersButton = remember { FocusRequester() }
    var sorting by remember { mutableStateOf(false) }
    var filtering by remember { mutableStateOf(false) }
    // What the panel shows while it is open; applied as it closes.
    var draft by remember { mutableStateOf(model.filters) }
    // After the sort list or the panel: focus back on the button that opened it.
    var backTo by remember { mutableStateOf<FocusRequester?>(null) }
    // Where Sort ends on the title's line: the sort list hangs under it, its right edge under Sort's (tvOS's menu).
    var sortEnd by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val pivot = remember(density) { GridPivot(with(density) { GridPadding.toPx() }) }
    val grid = model.grid
    // The grid the page arrived with: only its first answer takes focus. A grid a new sort or filter made
    // leaves focus on the button that made it.
    val arrival = remember { grid }
    LaunchedEffect(model) { model.start() }
    BackHandler(enabled = !sorting && !filtering) { onBack() }
    val body = grid.body
    LaunchedEffect(grid, body) {
        if (grid !== arrival) return@LaunchedEffect
        val target = when (body) {
            GridBody.RESULTS -> entry
            GridBody.FAILED -> retry
            else -> return@LaunchedEffect
        }
        grid.returnToCard = false
        settle(target)
    }
    LaunchedEffect(backTo) {
        val to = backTo ?: return@LaunchedEffect
        settle(to)
        backTo = null
    }
    val open: (GridCard) -> Unit = { card -> if (card is TitleCard) onOpen(card) }
    val choices = remember(model.type) { sortChoices(sortOptions(model.type)) }

    // The page, and over all of it (its title's line too) the sort list or the filters' panel.
    Box(Modifier.fillMaxSize()) {
        Page(
            PageKind.Pushed(model.heading ?: stringResource(if (model.type == MediaType.MOVIE) R.string.nav_movies else R.string.nav_series), titleTag = "browse heading"),
            bleed = true,
            trailing = {
                PlainButton(
                    text = choices.firstOrNull { it.option.value == model.sort }?.let { sortWords(it) }.orEmpty(),
                    onClick = { sorting = true },
                    icon = HitherIcons.ArrowUpDown,
                    modifier = Modifier
                        .focusRequester(sortButton)
                        .focusProperties {
                            left = FocusRequester.Cancel
                            up = FocusRequester.Cancel
                        }
                        .onGloballyPositioned { sortEnd = it.boundsInRoot().right }
                        .testTag("sort"),
                )
                PlainButton(
                    text = filtersWords(model.filters.count),
                    onClick = {
                        draft = model.filters
                        filtering = true
                        model.loadOptions()
                    },
                    icon = HitherIcons.ListFilter,
                    modifier = Modifier
                        .focusRequester(filtersButton)
                        .focusProperties {
                            right = FocusRequester.Cancel
                            up = FocusRequester.Cancel
                        }
                        .testTag("filters"),
                )
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                // A grid of its own per sort and filter set: nothing of the last one's focus or scroll carries over.
                key(grid) {
                    when (body) {
                        GridBody.LOADING -> GridPlaceholders()
                        GridBody.NO_RESULTS -> StateView(StateKind.EMPTY, stringResource(R.string.no_results), Modifier.padding(top = 60.dp), wordsTag = "no results")
                        // The page hands focus to Try Again itself (`settle`), as the state comes.
                        GridBody.FAILED -> StateView(StateKind.FAILED, stringResource(R.string.grid_failed), Modifier.padding(top = 60.dp), wordsTag = "failure text", focusOnArrival = false) { first ->
                            ProminentButton(
                                text = stringResource(if (grid.retrying) R.string.trying else R.string.try_again),
                                onClick = grid::retry,
                                busy = grid.retrying,
                                modifier = first.focusRequester(retry).testTag("try again"),
                            )
                        }
                        GridBody.RESULTS -> CompositionLocalProvider(LocalBringIntoViewSpec provides pivot) {
                            CardGrid(
                                model = grid,
                                entry = entry,
                                onOpen = open,
                                modifier = Modifier.onFocusChanged { if (!it.hasFocus) grid.left() },
                                tag = "grid",
                            )
                        }
                    }
                }
            }
        }
        if (sorting) {
            SortList(
                choices = choices,
                current = model.sort,
                end = sortEnd,
                onPick = { value ->
                    sorting = false
                    model.sortBy(value)
                    backTo = sortButton
                },
                onDismiss = {
                    sorting = false
                    backTo = sortButton
                },
            )
        }
        // Kept composed while closed, so it can hand focus back to Filters as it goes.
        FilterPanel(
            open = filtering,
            type = model.type,
            model = model,
            draft = draft,
            onDraft = { draft = it },
            opener = filtersButton,
            onClose = {
                filtering = false
                model.apply(draft)
            },
        )
    }
}

/** A sort's words: tvOS's where Hither has them, else core's label (a sort core adds later). */
@Composable
private fun sortWords(choice: SortChoice): String = choice.name?.let { stringResource(it.words) } ?: choice.option.label

/** The Filters button and the panel's title, counting what is set (tvOS's `Words.filters`): "Filters", "Filters (1)". */
@Composable
private fun filtersWords(count: Int): String = if (count > 0) stringResource(R.string.filters_count, count) else stringResource(R.string.filters)

/** Asks [target] for focus once it is laid out: a few frames at most. */
private suspend fun settle(target: FocusRequester) {
    repeat(10) {
        withFrameNanos { }
        if (runCatching { target.requestFocus() }.getOrDefault(false)) return
    }
}

/**
 * The sort list (tvOS's menu of the sorts): under the title's line, its right edge under Sort's ([end], px from the
 * left), the page dimmed under it. A row each (tv-material's `ListItem`, [HitherListItem]), the one in use ticked (rule
 * 3) and focused on arrival; Select picks and closes, Back closes; focus stays inside while it is open.
 */
@Composable
private fun SortList(choices: List<SortChoice>, current: String, end: Float, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val at = choices.indexOfFirst { it.option.value == current }.coerceAtLeast(0)
    val entry = remember { FocusRequester() }
    FocusOnArrival(entry)
    BackHandler { onDismiss() }
    val density = LocalDensity.current
    val width = LocalConfiguration.current.screenWidthDp.dp
    val fromEnd = with(density) { if (end > 0f) (width.toPx() - end).toDp() else Metrics.SideMargin }
    Overlay(Modifier.testTag("sort list"), dim = 0.5f) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = Metrics.TopMargin + Metrics.PageTitleHeight + 8.dp, end = fromEnd)
                .width(Metrics.SortListWidth)
                .clip(PanelShape)
                .background(Tokens.Field)
                .padding(8.dp)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            choices.forEachIndexed { index, choice ->
                HitherListItem(
                    headline = sortWords(choice),
                    onClick = { onPick(choice.option.value) },
                    selected = choice.option.value == current,
                    tag = "pick ${choice.option.value}",
                    modifier = Modifier.fillMaxWidth().then(if (index == at) Modifier.focusRequester(entry) else Modifier),
                )
            }
        }
    }
}

private val PanelShape = RoundedCornerShape(12.dp)

/** The filters' lists of choices that the panel's rows open (the language's is the panel's own `PanelPick`). */
private enum class FilterList(val tag: String) { YEARS("filter years"), GENRES("filter genres"), PROVIDERS("filter providers") }

/**
 * The filters (tvOS's filter sheet; DECISIONS 102's short set) in A5's `SidePanel` from the right: "Clear Filters" while
 * any is set, then a row each for the release (or first air) date's decades, the genres, the original language and the
 * streaming services, each with what is chosen at its right and opening its choices in the panel. The title counts what
 * is set, as the button does ("Filters (1)"). Focus arrives on the first row and stays inside; Back from a list comes
 * back to its row, Back from the rows closes the panel ([onClose], where the page applies what it shows) and focus goes
 * back to Filters ([opener]). The choices are read the first time the panel opens (the model's), with their own loading
 * and failure.
 *
 * The language is one of many and has a word for none ("All Languages"), so it is the panel's own `PanelPick`. The
 * decades (one, or none: Select on the chosen one clears it) and the genres and services (any number) open lists of
 * their own here ([FilterChoices]), as `PanelPick` picks one and closes.
 */
@Composable
private fun FilterPanel(
    open: Boolean,
    type: MediaType,
    model: BrowseModel,
    draft: BrowseFilters,
    onDraft: (BrowseFilters) -> Unit,
    opener: FocusRequester,
    onClose: () -> Unit,
) {
    var list by remember { mutableStateOf<FilterList?>(null) }
    val rows = remember { FilterList.entries.associateWith { FocusRequester() } }
    // Back from a list of choices: focus on the row that opened it.
    var backToRow by remember { mutableStateOf<FilterList?>(null) }
    LaunchedEffect(backToRow) {
        val row = backToRow ?: return@LaunchedEffect
        settle(rows.getValue(row))
        backToRow = null
    }
    val closeList = {
        backToRow = list
        list = null
    }
    val options = model.options
    val dateWords = stringResource(if (type == MediaType.MOVIE) R.string.release_date else R.string.first_air_date)
    val title = when (list) {
        null -> filtersWords(draft.count)
        FilterList.YEARS -> dateWords
        FilterList.GENRES -> stringResource(R.string.genres)
        FilterList.PROVIDERS -> stringResource(R.string.streaming_services)
    }
    SidePanel(
        open = open,
        title = title,
        onClose = { if (list != null) closeList() else onClose() },
        opener = opener,
        tag = "filter panel",
        entry = rows.getValue(FilterList.YEARS),
    ) {
        val shown = list
        if (shown != null) {
            FilterChoices(shown, options, draft, onDraft, onPicked = closeList)
            return@SidePanel
        }
        if (draft.count > 0) {
            HitherListItem(
                headline = stringResource(R.string.clear_filters),
                // Focus moves to the first row before this one goes with the filters.
                onClick = {
                    runCatching { rows.getValue(FilterList.YEARS).requestFocus() }
                    onDraft(BrowseFilters())
                },
                tag = "clear filters",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FilterRow(FilterList.YEARS, dateWords, draft.years?.label, rows) { list = it }
        when {
            options != null -> {
                if (options.genres.isNotEmpty()) {
                    val chosen = options.genres.filter { it.id in draft.genres }.joinToString(", ") { it.name }
                    FilterRow(FilterList.GENRES, stringResource(R.string.genres), chosen.ifEmpty { null }, rows) { list = it }
                }
                PanelPick(
                    title = stringResource(R.string.original_language),
                    options = listOf(PanelOption<String?>(null, stringResource(R.string.all_languages))) +
                        options.languages.map { PanelOption<String?>(it.code, it.englishName) },
                    selection = draft.language,
                    onPick = { onDraft(draft.copy(language = it)) },
                    tag = "language",
                )
                if (options.providers.isNotEmpty()) {
                    val chosen = options.providers.filter { it.id in draft.providers }.joinToString(", ") { it.name }
                    FilterRow(FilterList.PROVIDERS, stringResource(R.string.streaming_services), chosen.ifEmpty { null }, rows) { list = it }
                }
            }
            // In the panel, where focus already is on its first row: the states take none on arrival.
            model.optionsFailed -> StateView(StateKind.FAILED, stringResource(R.string.filters_failed), Modifier.padding(top = 24.dp), wordsTag = "failure text", focusOnArrival = false) { action ->
                PlainButton(
                    text = stringResource(R.string.try_again),
                    onClick = model::loadOptions,
                    modifier = action.testTag("filters try again"),
                )
            }
            else -> StateView(StateKind.LOADING, stringResource(R.string.filters_loading), Modifier.padding(top = 24.dp), wordsTag = "filters loading")
        }
    }
}

/**
 * A filter's row, as the panel's `PanelPick` rows are: its name, what is chosen at its right ([value], cut to fit), a
 * chevron; Select opens its choices. TalkBack reads what is chosen as its state.
 */
@Composable
private fun FilterRow(list: FilterList, words: String, value: String?, rows: Map<FilterList, FocusRequester>, onOpen: (FilterList) -> Unit) {
    HitherListItem(
        headline = words,
        onClick = { onOpen(list) },
        tag = list.tag,
        chevron = true,
        trailing = value?.let { { RowValue(it, Modifier.widthIn(max = FilterValueWidth).testTag("${list.tag} value")) } },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(rows.getValue(list))
            .semantics { stateDescription = value.orEmpty() },
    )
}

/** What is chosen, at a row's right: no wider than leaves the row's own words whole. */
private val FilterValueWidth = 170.dp

/**
 * A filter's choices in the panel, as the panel's choices (`PanelChoice`), the chosen ticked (rule 3): the decades one
 * at a time (Select picks one, or clears the one picked, and goes back to the rows, as a `PanelPick` list does); the
 * genres or the services any number (Select ticks or clears, and stays; Back goes back). Focus arrives on the first
 * chosen, else the first.
 */
@Composable
private fun SidePanelScope.FilterChoices(list: FilterList, options: FilterOptions?, draft: BrowseFilters, onDraft: (BrowseFilters) -> Unit, onPicked: () -> Unit) {
    data class Choice(val words: String, val on: Boolean, val tag: String, val select: () -> Unit)
    val choices: List<Choice> = when (list) {
        FilterList.YEARS -> Years.entries.map { years ->
            Choice(years.label, draft.years == years, "years ${years.name}") {
                onDraft(draft.copy(years = if (draft.years == years) null else years))
                onPicked()
            }
        }
        FilterList.GENRES -> options?.genres.orEmpty().map { genre ->
            val on = genre.id in draft.genres
            Choice(genre.name, on, "genre ${genre.id}") { onDraft(draft.copy(genres = if (on) draft.genres - genre.id else draft.genres + genre.id)) }
        }
        FilterList.PROVIDERS -> options?.providers.orEmpty().map { tile ->
            val on = tile.id in draft.providers
            Choice(tile.name, on, "provider ${tile.id}") { onDraft(draft.copy(providers = if (on) draft.providers - tile.id else draft.providers + tile.id)) }
        }
    }
    val at = remember(list) { choices.indexOfFirst { it.on }.coerceAtLeast(0) }
    val entry = remember { FocusRequester() }
    FocusOnArrival(entry)
    choices.forEachIndexed { index, choice ->
        PanelChoice(
            words = choice.words,
            on = choice.on,
            onSelect = choice.select,
            tag = choice.tag,
            modifier = if (index == at) Modifier.focusRequester(entry) else Modifier,
        )
    }
}
