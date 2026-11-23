package com.tomdebres.hither.ui.person

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.tomdebres.hither.R
import com.tomdebres.hither.core.rules.CreditKind
import com.tomdebres.hither.core.rules.CreditSection
import com.tomdebres.hither.core.rules.PersonPage
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.ui.components.ReportRecomposition
import com.tomdebres.hither.ui.media.Pivot
import com.tomdebres.hither.ui.media.TitleBackdrop
import com.tomdebres.hither.ui.media.TopEdge
import com.tomdebres.hither.ui.media.section
import com.tomdebres.hither.ui.theme.Formats
import com.tomdebres.hither.ui.theme.HitherType
import com.tomdebres.hither.ui.theme.Metrics
import com.tomdebres.hither.ui.theme.Page
import com.tomdebres.hither.ui.theme.PageKind
import com.tomdebres.hither.ui.theme.PosterLockup
import com.tomdebres.hither.ui.theme.ProminentButton
import com.tomdebres.hither.ui.theme.ReadingScreen
import com.tomdebres.hither.ui.theme.ReadingText
import com.tomdebres.hither.ui.theme.Shelf
import com.tomdebres.hither.ui.theme.ShelfTitle
import com.tomdebres.hither.ui.theme.StateKind
import com.tomdebres.hither.ui.theme.StateView
import com.tomdebres.hither.ui.theme.TextStop
import com.tomdebres.hither.ui.theme.Tokens
import com.tomdebres.hither.ui.theme.shelfPlace

// A person's page (A4; tvOS's `PersonView.swift`), in dp on the 960 x 540 dp panel.

/** The person's photo, decoded at the size it is drawn. */
private const val PHOTO_PX = 240

/** A placeholder for the biography while the page loads: two lines of Body and the stop's room. */
private val LoadingHeight = 60.dp

/**
 * A person's page (Seerr's PersonDetails, M6, DECISIONS 100; tvOS's `PersonView`): the first title's backdrop across
 * the top, fading into the page; the header at the top (the portrait in a circle, the name, born and died and where,
 * the other names); the biography's first lines as one stop with "More" (`TextStop`; Select opens the whole in
 * `ReadingScreen`; it was a stop per paragraph); then "Appearances" and "Crew" as shelves of poster lockups with the
 * person's part under each. **It opens at its top:** focus arrives on the biography (the first lockup where there is
 * none, and the words saying there is nothing more where there is neither, A5) with the header and the biography whole on
 * the screen, and stays at the top while focus is on the biography.
 * Coming back from a title's page, focus is on the lockup left. Back is the caller's.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PersonScreen(model: PersonModel, onOpen: (TitleCard) -> Unit, onBack: () -> Unit) {
    LaunchedEffect(model) { model.start() }
    BackHandler { onBack() }
    val state = model.state
    val density = LocalDensity.current
    // A shelf's top comes to the top margin; the header and the biography stay at the page's top (`section`).
    val columnPivot = remember(density) { Pivot(with(density) { Metrics.TopMargin.toPx() }, with(density) { Metrics.BottomMargin.toPx() }) }
    val rowPivot = remember(density) { Pivot(with(density) { Metrics.SideMargin.toPx() }) }
    val loading = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val biography = remember { FocusRequester() }
    val empty = remember { FocusRequester() }
    val entries = remember { CreditKind.entries.associateWith { FocusRequester() } }
    // The whole biography, open over the page (`ReadingScreen`).
    var reading by remember { mutableStateOf(false) }
    val kind = state::class
    LaunchedEffect(kind) {
        val back = model.returnFocus
        model.returnFocus = null
        val target = when (state) {
            PersonState.Loading -> loading
            PersonState.Failed -> retry
            is PersonState.Ready -> when {
                back != null -> entries.getValue(back.kind)
                state.page.biography.isNotEmpty() -> biography
                // Nothing to read and nothing to open: the words that say so hold focus (A5), never nothing.
                else -> state.page.sections.firstOrNull()?.let { entries.getValue(it.kind) } ?: empty
            }
        }
        settleFocusOn(target)
    }
    // The whole biography closes onto its stop.
    var wasReading by remember { mutableStateOf(false) }
    LaunchedEffect(reading) {
        if (reading) {
            wasReading = true
        } else if (wasReading) {
            wasReading = false
            settleFocusOn(biography)
        }
    }
    // A pushed page with no title: the person's header is its top.
    Page(PageKind.Pushed(null)) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides columnPivot) {
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(model.scroll)
                    .testTag("person page"),
            ) {
                // The first title's backdrop across the top, scrolling with the page (polish 2's item 18)
                (state as? PersonState.Ready)?.page?.backdrop?.let { TitleBackdrop(it, Modifier.testTag("person backdrop")) }
                Column(Modifier.fillMaxWidth().padding(bottom = Metrics.BottomMargin)) {
                    // The page's top: the header and the biography (or the placeholder, or Try Again), one section, so
                    // the page stays at its top while focus is in it.
                    Column(Modifier.section().padding(top = Metrics.PersonTop)) {
                        when (state) {
                            PersonState.Loading -> {
                                Header(name = model.name, photo = null, facts = emptyList())
                                Box(
                                    Modifier
                                        .padding(start = Metrics.SideMargin)
                                        .size(Metrics.ReadingWidth, LoadingHeight)
                                        .focusRequester(loading)
                                        .focusable()
                                        .clip(RoundedCornerShape(Metrics.ImageRadius))
                                        .background(Tokens.Placeholder)
                                        .testTag("loading"),
                                )
                            }
                            PersonState.Failed -> {
                                Header(name = model.name, photo = null, facts = emptyList())
                                // The page hands focus to Try Again itself (above), as the state comes.
                                StateView(StateKind.FAILED, stringResource(R.string.person_failed), Modifier.padding(top = 16.dp), wordsTag = "failure text", focusOnArrival = false) { first ->
                                    ProminentButton(text = stringResource(R.string.try_again), onClick = model::retry, modifier = first.focusRequester(retry).testTag("try again"))
                                }
                            }
                            is PersonState.Ready -> {
                                val page = state.page
                                Header(name = page.name, photo = page.photo, facts = facts(page))
                                if (page.biography.isEmpty() && page.sections.isEmpty()) {
                                    // A person the server knows nothing more of: the words hold focus, drawing none, as a
                                    // `ReadingView` draws none (the text is the page); Back leaves.
                                    StateView(
                                        StateKind.EMPTY,
                                        stringResource(R.string.person_empty),
                                        Modifier
                                            .padding(top = 16.dp)
                                            .focusRequester(empty)
                                            .focusProperties {
                                                left = FocusRequester.Cancel
                                                right = FocusRequester.Cancel
                                            }
                                            .focusable()
                                            .semantics(mergeDescendants = true) { }
                                            .testTag("person empty"),
                                        wordsTag = "person empty words",
                                        focusOnArrival = false,
                                    )
                                }
                                if (page.biography.isNotEmpty()) {
                                    // Its first lines run on from paragraph to paragraph, as the Apple TV app's description
                                    // does; the whole keeps them.
                                    TextStop(
                                        text = remember(page.biography) { page.biography.joinToString(" ") },
                                        onSelect = { reading = true },
                                        tag = "person biography",
                                        textTag = "biography text",
                                        modifier = Modifier
                                            .padding(start = Metrics.SideMargin)
                                            .width(Metrics.ReadingWidth)
                                            .focusRequester(biography)
                                            .focusProperties {
                                                left = FocusRequester.Cancel
                                                right = FocusRequester.Cancel
                                            },
                                    )
                                }
                            }
                        }
                    }
                    (state as? PersonState.Ready)?.page?.sections?.forEach { section ->
                        CompositionLocalProvider(LocalBringIntoViewSpec provides rowPivot) {
                            CreditsShelf(section, model, entries.getValue(section.kind), Modifier.section()) { index, card ->
                                model.returnFocus = CreditFocus(section.kind, index)
                                onOpen(card)
                            }
                        }
                    }
                }
            }
        }
        // A soft top edge once the page has scrolled to its shelves (the title page's)
        TopEdge(model.scroll)
        val ready = (state as? PersonState.Ready)?.page
        if (reading && ready != null) {
            ReadingScreen(
                title = ready.name,
                text = remember(ready.biography) { ready.biography.fold(ReadingText()) { text, paragraph -> text.paragraph(paragraph) } },
                onClose = { reading = false },
                tag = "biography screen",
                textTag = "biography whole",
            )
        }
    }
}

/** Asks [target] for focus once it is laid out (and whatever it replaces has gone): a few frames at most. */
private suspend fun settleFocusOn(target: FocusRequester) {
    repeat(10) {
        withFrameNanos { }
        if (runCatching { target.requestFocus() }.getOrDefault(false)) return
    }
}

/** "Born {date} · {place}", or "{born} – {died} · {place}", then "Also Known As: …" (`Formats`: the day, the dot). */
@Composable
private fun facts(page: PersonPage): List<String> {
    val born = page.birthday?.let { Formats.day(it) ?: it }
    val died = page.deathday?.let { Formats.day(it) ?: it }
    val life = when {
        born != null && died != null -> stringResource(R.string.person_lifespan, born, died)
        born != null -> stringResource(R.string.person_born, born)
        else -> null
    }
    val first = Formats.join(listOf(life, page.placeOfBirth))
    val names = page.alsoKnownAs.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.person_also_known_as, it.joinToString(", ")) }
    return listOfNotNull(first, names)
}

/** The header (tvOS's): the portrait in a circle, the name, and the facts under it in grey. */
@Composable
private fun Header(name: String, photo: String?, facts: List<String>) {
    val context = LocalContext.current
    val request = remember(photo, context) { photo?.let { ImageRequest.Builder(context).data(it).size(PHOTO_PX, PHOTO_PX).build() } }
    Row(Modifier.padding(horizontal = Metrics.SideMargin), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(Metrics.PersonPortrait)
                .testTag("person photo")
                .clip(CircleShape)
                .background(Tokens.Placeholder),
        ) {
            if (request != null) AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(name, style = HitherType.Title.copy(color = Tokens.Text), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("person name"))
            facts.forEach { BasicText(it, style = HitherType.Body.copy(color = Tokens.TextSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("person fact")) }
        }
    }
    Spacer(Modifier.height(16.dp))
}

/**
 * "Appearances" or "Crew": a shelf of poster lockups, the person's part under each title (A3's), coming back to the
 * lockup left; a move recomposes the two lockups it touches.
 */
@Composable
private fun CreditsShelf(section: CreditSection, model: PersonModel, entry: FocusRequester, modifier: Modifier, onOpen: (Int, TitleCard) -> Unit) {
    ReportRecomposition("credits ${section.kind}")
    val title = stringResource(if (section.kind == CreditKind.APPEARANCES) R.string.person_appearances else R.string.person_crew)
    Column(modifier.testTag("credits ${section.kind}")) {
        ShelfTitle(title)
        Shelf(state = model.rowStates.getValue(section.kind), entry = entry) {
            itemsIndexed(section.cards, key = { _, credit -> credit.card.key }) { index, credit ->
                ReportRecomposition("credit item ${credit.card.key}")
                val isEntry by remember(index) { derivedStateOf { index == (model.rowIndex[section.kind] ?: 0) } }
                val part = credit.part?.let { if (section.kind == CreditKind.APPEARANCES) stringResource(R.string.person_as, it) else it }
                PosterLockup(
                    card = credit.card,
                    onSelect = { onOpen(index, credit.card) },
                    onFocused = { model.rowIndex[section.kind] = index },
                    subtitle = part,
                    modifier = Modifier.shelfPlace(isEntry, entry, first = index == 0, last = index == section.cards.lastIndex),
                )
            }
        }
    }
}
