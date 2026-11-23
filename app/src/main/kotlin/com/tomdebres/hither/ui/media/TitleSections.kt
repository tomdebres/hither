package com.tomdebres.hither.ui.media

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.AssistChip
import androidx.tv.material3.AssistChipDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.tomdebres.hither.R
import com.tomdebres.hither.core.model.Keyword
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.rules.CastCard
import com.tomdebres.hither.core.rules.CollectionCard
import com.tomdebres.hither.core.rules.MediaPage
import com.tomdebres.hither.core.rules.RelatedKind
import com.tomdebres.hither.core.rules.RequestButton
import com.tomdebres.hither.core.rules.SeasonLine
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.core.rules.seasonRows
import com.tomdebres.hither.ui.components.ReportRecomposition
import com.tomdebres.hither.ui.theme.BackdropLockup
import com.tomdebres.hither.ui.theme.BackdropLockupHeight
import com.tomdebres.hither.ui.theme.HitherType
import com.tomdebres.hither.ui.theme.Metrics
import com.tomdebres.hither.ui.theme.PortraitLockup
import com.tomdebres.hither.ui.theme.PortraitLockupHeight
import com.tomdebres.hither.ui.theme.PosterLockup
import com.tomdebres.hither.ui.theme.Shelf
import com.tomdebres.hither.ui.theme.ShelfTitle
import com.tomdebres.hither.ui.theme.StatusLabel
import com.tomdebres.hither.ui.theme.Tokens
import com.tomdebres.hither.ui.theme.WordsCard
import com.tomdebres.hither.ui.theme.bringIntoViewAtRest
import com.tomdebres.hither.ui.theme.mixesKinds
import com.tomdebres.hither.ui.theme.rememberTvClick
import com.tomdebres.hither.ui.theme.shelfPlace

// A title's page below its first screen (A4; tvOS's `TitleSections.swift`), as the Apple TV app's product page goes on:
// the seasons a series can still be asked for, Cast & Crew, the collection, Recommendations and Similar, the keywords,
// then Information (`Information.kt`), read and never focused, at the page's foot. Each shelf comes back to what it was
// left on, and each section comes up to the page's line as focus goes into it.

// ---- the seasons (Tom's D2 for tvOS, the review's F9 for Android) -------------------------------------------------

/** A season the page offers to ask for, and the request button whose modal it opens. */
data class SeasonToAskFor(val line: SeasonLine, val button: RequestButton)

/**
 * The seasons that can still be asked for, newest first as Seerr lists them (tvOS's `seasonsToAskFor`): each with the
 * first of the page's request buttons (the regular copy's, then the 4K one's) whose copy has it open, as the request
 * modal would list it (core's `seasonRows`: not in a live request, not in the library or on its way there).
 */
fun seasonsToAskFor(page: MediaPage): List<SeasonToAskFor> {
    val open = page.buttons.map { button -> button to seasonRows(page, button.is4k).filter { !it.taken }.mapTo(HashSet()) { it.seasonNumber } }
    return page.seasonLines.mapNotNull { line -> open.firstOrNull { line.number in it.second }?.let { SeasonToAskFor(line, it.first) } }
}

/**
 * The seasons that cannot be asked for, oldest first: in the library, asked for already, or out of the account's reach.
 * They take no focus; Information lists those with a status.
 */
fun seasonsAtRest(page: MediaPage): List<SeasonLine> {
    val asked = seasonsToAskFor(page).mapTo(HashSet()) { it.line.number }
    return page.seasonLines.filter { it.number !in asked }.asReversed()
}

/** Seasons side by side that share their status, as Information names them: "Season 1", "Seasons 2–4", "Specials". */
data class SeasonRun(val first: Int, val last: Int, val line: SeasonLine)

/** [seasons] (oldest first) that have a status, in runs of neighbours with the same status; the specials alone. */
fun seasonRuns(seasons: List<SeasonLine>): List<SeasonRun> {
    val runs = mutableListOf<SeasonRun>()
    for (season in seasons) {
        if (season.badges.isEmpty()) continue
        val run = runs.lastOrNull()
        if (run != null && run.first != 0 && season.number != 0 && season.number == run.last + 1 && run.line.badges == season.badges) {
            runs[runs.lastIndex] = run.copy(last = season.number)
        } else {
            runs += SeasonRun(season.number, season.number, season)
        }
    }
    return runs
}

// ---- where a section comes to on the page ---------------------------------------------------------------------------

/**
 * Brings a stop's top to the line [at]; a stop too tall to end on the screen from there comes up until its foot is
 * [foot] from the bottom, its top never above [foot] from the top (polish 2: the whole overview, the facts box).
 */
@OptIn(ExperimentalFoundationApi::class)
internal class Pivot(private val at: Float, private val foot: Float = -1f) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val top = if (foot >= 0 && at + size > containerSize - foot) maxOf(foot, containerSize - foot - size) else at
        return offset - top
    }
}

/** What lies under the last section a page's focus can reach (Information), measured, for [section]. Not state. */
internal class PageFoot {
    var height = 0
}

/**
 * A page's section as one place for its scroll (tvOS's `scrollTo(section, anchor:)`): whatever takes focus inside it
 * asks the page to bring the whole section into view, its header on the page's line, not the one control. The first
 * screen's section (a title's actions and overview, a person's header and biography) is the page's top, so the page
 * stays at its top while focus is there. The last section that takes focus brings [foot] with it, so Information, which
 * takes none, is read whole under it (tvOS: the last section scrolls the page's foot into view).
 */
internal fun Modifier.section(foot: PageFoot? = null): Modifier = this then SectionElement(foot)

private class SectionElement(val foot: PageFoot?) : ModifierNodeElement<SectionNode>() {
    override fun create() = SectionNode(foot)

    override fun update(node: SectionNode) {
        node.foot = foot
    }

    override fun equals(other: Any?) = other is SectionElement && other.foot === foot

    override fun hashCode() = System.identityHashCode(foot)
}

private class SectionNode(var foot: PageFoot?) : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        val own = requireLayoutCoordinates()
        val below = foot?.height ?: 0
        bringIntoView { Rect(0f, 0f, own.size.width.toFloat(), (own.size.height + below).toFloat()) }
    }
}

/**
 * A page's soft top edge once it has scrolled (polish's item 16; A4): solid over the top margin, where a section that has
 * come up to its line leaves the tail of the one above it, then clearing over the room above the section's header, so
 * no cut line of words or pictures is left at the screen's edge. Drawn, and read only in the draw phase.
 */
@Composable
internal fun TopEdge(scroll: ScrollState, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(Metrics.TopMargin + Metrics.AboveShelf)
            .drawBehind { if (scroll.value > 0) drawRect(TopEdgeBrush) },
    )
}

/** Solid to the top margin (27 of 45 dp), then clear at the shelf header's line. */
private val TopEdgeBrush = Brush.verticalGradient(0f to Tokens.Background, 0.6f to Tokens.Background, 1f to Tokens.Background.copy(alpha = 0f))

// ---- the sections ---------------------------------------------------------------------------------------------------

/** The page's sections under its first screen, in order; the last one that takes focus brings Information with it. */
private enum class SectionKind { SEASONS, CAST, COLLECTION, RECOMMENDATIONS, SIMILAR, KEYWORDS }

/**
 * The focus stops below the first screen, said by name so arrival, the modal's return and Back can find them, and what
 * the seasons' shelf was left on (kept while the page is shown; the other shelves keep theirs in the model, as they are
 * left for other pages).
 */
internal class TitleStops {
    val overview = FocusRequester()
    val seasons = FocusRequester()
    val cast = FocusRequester()
    val collection = FocusRequester()
    val keywords = FocusRequester()
    val related: Map<RelatedKind, FocusRequester> = RelatedKind.entries.associateWith { FocusRequester() }

    /** The season card left, by its number; null until one has had focus. */
    var seasonLeft: Int? by mutableStateOf(null)
    val seasonRow = LazyListState()
    private val seasonCards = HashMap<Int, FocusRequester>()

    /** Each season card's own requester, so focus can go back to one by number after a request. */
    fun seasonCard(number: Int): FocusRequester = seasonCards.getOrPut(number) { FocusRequester() }
}

/** The cast, then the crew Seerr lists under the overview, as Cast & Crew's portraits (the crew with their jobs). */
fun castAndCrew(page: MediaPage): List<CastCard> =
    page.cast + page.crew.mapIndexed { index, line -> CastCard("crew:$index:${line.personId}", line.name, line.job, photo = null, personId = line.personId) }

/** The page's sections below its first screen (tvOS's `TitleSections`). Rows move under [rowPivot] (DECISIONS 40). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TitleSections(
    page: MediaPage,
    model: MediaModel,
    stops: TitleStops,
    foot: PageFoot,
    rowPivot: BringIntoViewSpec,
    onSeason: (SeasonToAskFor) -> Unit,
    onPerson: (Int, CastCard) -> Unit,
    onCollection: (CollectionCard) -> Unit,
    onTitle: (RelatedKind, Int, TitleCard) -> Unit,
    onKeyword: (Int, Keyword) -> Unit,
) {
    val seasons = remember(page) { seasonsToAskFor(page) }
    val people = remember(page) { castAndCrew(page) }
    val related = model.related
    val shown = buildList {
        if (seasons.isNotEmpty()) add(SectionKind.SEASONS)
        if (people.isNotEmpty()) add(SectionKind.CAST)
        if (page.collection != null) add(SectionKind.COLLECTION)
        if (related[RelatedKind.RECOMMENDATIONS].orEmpty().isNotEmpty()) add(SectionKind.RECOMMENDATIONS)
        if (related[RelatedKind.SIMILAR].orEmpty().isNotEmpty()) add(SectionKind.SIMILAR)
        if (page.keywords.isNotEmpty()) add(SectionKind.KEYWORDS)
    }
    fun place(kind: SectionKind) = Modifier.section(if (kind == shown.last()) foot else null)
    CompositionLocalProvider(LocalBringIntoViewSpec provides rowPivot) {
        for (kind in shown) {
            when (kind) {
                SectionKind.SEASONS -> SeasonsShelf(seasons, stops, onSeason, place(kind))
                SectionKind.CAST -> CastShelf(people, model, stops.cast, onPerson, place(kind))
                SectionKind.COLLECTION -> CollectionShelf(page.collection!!, stops.collection, { onCollection(page.collection!!) }, place(kind))
                SectionKind.RECOMMENDATIONS, SectionKind.SIMILAR -> {
                    val relatedKind = if (kind == SectionKind.RECOMMENDATIONS) RelatedKind.RECOMMENDATIONS else RelatedKind.SIMILAR
                    val title = when {
                        relatedKind == RelatedKind.RECOMMENDATIONS -> stringResource(R.string.recommendations)
                        page.type == MediaType.MOVIE -> stringResource(R.string.similar_titles)
                        else -> stringResource(R.string.similar_series)
                    }
                    RelatedShelf(relatedKind, title, related[relatedKind].orEmpty(), model, stops.related.getValue(relatedKind), onTitle, place(kind))
                }
                SectionKind.KEYWORDS -> KeywordShelf(page.keywords, model, stops.keywords, onKeyword, place(kind))
            }
        }
    }
}

/**
 * The seasons a series can still be asked for (the review's F9: every season was a stop that did nothing on Select), as
 * a shelf of cards, newest first: the season, its episodes and each copy's status, glyph and word. Select opens the
 * series' request with that season ticked. Coming back into the shelf lands on the card left, or the first once a
 * request has taken that one off it.
 */
@Composable
private fun SeasonsShelf(seasons: List<SeasonToAskFor>, stops: TitleStops, onSeason: (SeasonToAskFor) -> Unit, modifier: Modifier) {
    ReportRecomposition("media seasons")
    Column(modifier.testTag("media seasons")) {
        ShelfTitle(stringResource(R.string.seasons))
        Shelf(state = stops.seasonRow, entry = stops.seasons, height = Metrics.SeasonCardHeight) {
            itemsIndexed(seasons, key = { _, offer -> offer.line.number }) { index, offer ->
                val number = offer.line.number
                val isEntry by remember(number, seasons) {
                    derivedStateOf { number == (stops.seasonLeft?.takeIf { left -> seasons.any { it.line.number == left } } ?: seasons.first().line.number) }
                }
                SeasonCard(
                    offer = offer,
                    onSelect = { onSeason(offer) },
                    onFocused = { stops.seasonLeft = number },
                    modifier = Modifier
                        .focusRequester(stops.seasonCard(number))
                        .shelfPlace(isEntry, stops.seasons, first = index == 0, last = index == seasons.lastIndex),
                )
            }
        }
    }
}

@Composable
private fun SeasonCard(offer: SeasonToAskFor, onSelect: () -> Unit, onFocused: () -> Unit, modifier: Modifier) {
    val season = offer.line
    val name = if (season.number == 0) stringResource(R.string.specials) else stringResource(R.string.season_n, season.number)
    val episodes = if (season.episodes == 1) stringResource(R.string.episode_one) else stringResource(R.string.episode_count, season.episodes)
    WordsCard(
        onSelect = onSelect,
        tag = "series season ${season.number}",
        description = name,
        state = listOf(episodes).plus(season.badges.map { it.text }).joinToString(", "),
        width = Metrics.SeasonCardWidth,
        height = Metrics.SeasonCardHeight,
        modifier = modifier,
        onFocused = onFocused,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            BasicText(name, style = HitherType.Subtitle.copy(color = Tokens.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(episodes, style = HitherType.Caption.copy(color = Tokens.TextSecondary), maxLines = 1)
            Spacer(Modifier.weight(1f))
            season.badges.forEach { StatusLabel(it, Modifier.padding(top = 4.dp)) }
        }
    }
}

/**
 * Cast & Crew as the Apple TV app shows them (A3's portrait lockups): the cast with their parts, then the crew with
 * their jobs, initials where there is no picture. Select opens the person's page; the portrait left and the row's scroll
 * are the model's, so Back finds them.
 */
@Composable
private fun CastShelf(people: List<CastCard>, model: MediaModel, entry: FocusRequester, onOpen: (Int, CastCard) -> Unit, modifier: Modifier) {
    ReportRecomposition("media cast")
    Column(modifier.testTag("cast")) {
        ShelfTitle(stringResource(R.string.cast_and_crew))
        Shelf(state = model.castRow, entry = entry, height = PortraitLockupHeight) {
            itemsIndexed(people, key = { _, person -> person.key }) { index, person ->
                ReportRecomposition("cast item ${person.key}")
                val isEntry by remember(index, people.size) { derivedStateOf { index == model.castIndex.coerceIn(0, people.lastIndex) } }
                PortraitLockup(
                    key = person.key,
                    name = person.name,
                    part = person.character,
                    photo = person.photo,
                    onFocused = { model.castIndex = index },
                    onSelect = { onOpen(index, person) },
                    modifier = Modifier.shelfPlace(isEntry, entry, first = index == 0, last = index == people.lastIndex),
                )
            }
        }
    }
}

/** The film's collection (polish 2's item 12): a backdrop lockup with its name; Select opens the collection's films. */
@Composable
private fun CollectionShelf(card: CollectionCard, requester: FocusRequester, onSelect: () -> Unit, modifier: Modifier) {
    ReportRecomposition("media collection")
    Column(modifier.testTag("collection")) {
        ShelfTitle(stringResource(R.string.collection))
        Box(Modifier.padding(start = Metrics.SideMargin).height(BackdropLockupHeight)) {
            BackdropLockup(
                picture = card.backdrop,
                title = card.name,
                description = card.name,
                onSelect = onSelect,
                tag = "media collection",
                titleTag = "collection name",
                modifier = Modifier
                    .focusRequester(requester)
                    .focusProperties {
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                    },
            ) {
                BasicText(stringResource(R.string.view_collection), style = HitherType.Caption.copy(color = Tokens.TextSecondary), maxLines = 1)
            }
        }
    }
}

/**
 * Seerr's "Recommendations" or "Similar Titles" ("Similar Series"): a shelf of poster lockups, Select opening the
 * title's page (M6, DECISIONS 100), the lockup left kept in the model.
 */
@Composable
private fun RelatedShelf(
    kind: RelatedKind,
    title: String,
    cards: List<TitleCard>,
    model: MediaModel,
    entry: FocusRequester,
    onOpen: (RelatedKind, Int, TitleCard) -> Unit,
    modifier: Modifier,
) {
    ReportRecomposition("media related $kind")
    Column(modifier.testTag("related $kind")) {
        ShelfTitle(title)
        val mixed = remember(cards) { mixesKinds(cards) }
        Shelf(state = model.relatedRows.getValue(kind), entry = entry) {
            itemsIndexed(cards, key = { _, card -> card.key }) { index, card ->
                ReportRecomposition("related item ${card.key}")
                val isEntry by remember(index, cards.size) { derivedStateOf { index == (model.relatedIndex[kind] ?: 0).coerceIn(0, cards.lastIndex) } }
                PosterLockup(
                    card = card,
                    onSelect = { onOpen(kind, index, card) },
                    onFocused = { model.relatedIndex[kind] = index },
                    mixed = mixed,
                    modifier = Modifier.shelfPlace(isEntry, entry, first = index == 0, last = index == cards.lastIndex),
                )
            }
        }
    }
}

/**
 * The keywords as one shelf under their header (the review's S26 for tvOS: a wrapping cloud of tags is a web page's):
 * tv-material's chips in a row that scrolls sideways as focus moves along it, closer than lockups. Select opens the
 * keyword's titles; coming back into the shelf lands on the chip left (the model's).
 */
@Composable
private fun KeywordShelf(keywords: List<Keyword>, model: MediaModel, entry: FocusRequester, onOpen: (Int, Keyword) -> Unit, modifier: Modifier) {
    ReportRecomposition("media keywords")
    Column(modifier.testTag("media keywords")) {
        ShelfTitle(stringResource(R.string.keywords))
        LazyRow(
            state = model.keywordRow,
            contentPadding = PaddingValues(horizontal = Metrics.SideMargin),
            horizontalArrangement = Arrangement.spacedBy(Metrics.ChipGap),
            modifier = Modifier
                .fillMaxWidth()
                .height(Metrics.ChipShelfHeight)
                .focusProperties { onEnter = { entry.requestFocus() } }
                .focusGroup()
                .testTag("keyword shelf"),
        ) {
            itemsIndexed(keywords, key = { _, keyword -> keyword.id }) { index, keyword ->
                val isEntry by remember(index, keywords.size) { derivedStateOf { index == model.keywordIndex.coerceIn(0, keywords.lastIndex) } }
                KeywordChip(
                    keyword = keyword,
                    onSelect = { onOpen(index, keyword) },
                    onFocused = { model.keywordIndex = index },
                    modifier = Modifier.shelfPlace(isEntry, entry, first = index == 0, last = index == keywords.lastIndex),
                )
            }
        }
    }
}

/**
 * A keyword as tv-material's `AssistChip`, a capsule (tvOS's capsule button): grey at rest, white with black words with
 * focus, on [rememberTvClick]'s rules (its own scale held at 1, Hither's in the layer phase, the place at rest brought
 * into view). Tagged `keyword <name>`.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun KeywordChip(keyword: Keyword, onSelect: () -> Unit, onFocused: () -> Unit, modifier: Modifier) {
    ReportRecomposition("keyword tag ${keyword.id}")
    val click = rememberTvClick(onSelect)
    AssistChip(
        onClick = click.onClick,
        modifier = modifier
            .bringIntoViewAtRest()
            .onFocusChanged { if (it.isFocused) onFocused() }
            .then(click.modifier)
            .testTag("keyword ${keyword.name}"),
        shape = AssistChipDefaults.shape(shape = CircleShape),
        colors = AssistChipDefaults.colors(
            containerColor = Tokens.Surface,
            contentColor = Tokens.Text,
            focusedContainerColor = Tokens.Text,
            focusedContentColor = Tokens.FieldFocusedText,
            pressedContainerColor = Tokens.Text,
            pressedContentColor = Tokens.FieldFocusedText,
        ),
        scale = AssistChipDefaults.scale(focusedScale = 1f),
        border = AssistChipDefaults.border(border = Border.None, focusedBorder = Border.None, pressedBorder = Border.None),
        interactionSource = click.interaction,
    ) {
        Text(keyword.name, style = HitherType.Caption, maxLines = 1)
    }
}
