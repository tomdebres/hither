package com.tomdebres.hither.ui.media

import android.content.ActivityNotFoundException
import android.content.res.Resources
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.tomdebres.hither.R
import com.tomdebres.hither.core.model.Keyword
import com.tomdebres.hither.core.rules.CastCard
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.rules.CollectionCard
import com.tomdebres.hither.core.rules.MediaPage
import com.tomdebres.hither.core.rules.PlayAction
import com.tomdebres.hither.core.rules.RequestButton
import com.tomdebres.hither.core.rules.RequestFailure
import com.tomdebres.hither.core.rules.StatusBadge
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.core.rules.canReportIssue
import com.tomdebres.hither.core.rules.youtubeWatchLink
import com.tomdebres.hither.ui.components.DownloadEstimate
import com.tomdebres.hither.ui.components.HitherIcons
import com.tomdebres.hither.ui.components.ReportRecomposition
import com.tomdebres.hither.ui.components.focusScale
import com.tomdebres.hither.ui.theme.Formats
import com.tomdebres.hither.ui.theme.HitherType
import com.tomdebres.hither.ui.theme.Metrics
import com.tomdebres.hither.ui.theme.Page
import com.tomdebres.hither.ui.theme.PageKind
import com.tomdebres.hither.ui.theme.PlainButton
import com.tomdebres.hither.ui.theme.Problem
import com.tomdebres.hither.ui.theme.ProblemAlert
import com.tomdebres.hither.ui.theme.ProminentButton
import com.tomdebres.hither.ui.theme.ReadingScreen
import com.tomdebres.hither.ui.theme.ReadingText
import com.tomdebres.hither.ui.theme.StateKind
import com.tomdebres.hither.ui.theme.StateView
import com.tomdebres.hither.ui.theme.StatusLabel
import com.tomdebres.hither.ui.theme.TextStop
import com.tomdebres.hither.ui.theme.Tokens
import kotlinx.coroutines.delay

// A title's page as the Apple TV app's product page (A4; tvOS's `TitleView.swift`), in dp on the 960 x 540 dp panel
// (tvOS's points halved, `Metrics`).

/** tv-material's button height (A1), and a still placeholder's width while the page loads (tvOS's 240 points). */
private val ButtonHeight = 40.dp
private val LoadingWidth = 120.dp

/** The backdrop is drawn at most 1920 px wide; it is decoded smaller (DECISIONS 62). */
private const val BACKDROP_WIDTH_PX = 1280
private const val BACKDROP_HEIGHT_PX = 533
private const val POSTER_WIDTH_PX = 400
private const val POSTER_HEIGHT_PX = 600

/** Seerr's download sync runs once a minute: the page reads the title no oftener while it downloads. */
private const val RE_READ_MS = 60_000L

/**
 * A title's page as the Apple TV app's product page (A4, H2's plan; tvOS's `TitleView`): the backdrop full-bleed across
 * the top, fading into the page; over its foot the title, one line of what it is (the year, the runtime or the seasons,
 * the genres, the certification boxed), its status as glyphs with their words, the actions as tv-material's buttons in a
 * row (focused on arrival), and the tagline and four lines of the overview with "More" (`TextStop`; Select opens the
 * whole in `ReadingScreen`). Below (`TitleSections`): the seasons a series can still be asked for, each opening the
 * request with itself ticked; Cast & Crew as portraits; the collection; Recommendations and Similar; the keywords as one
 * shelf; and Information at the foot, which takes no focus.
 *
 * While the title loads, the card's title and status stand in and a placeholder holds focus; if it fails, Try Again
 * does. A film's request button asks in a dialog (`RequestQuestion`); a series' opens its seasons in a side panel
 * (`RequestPanel`); Report an Issue opens its form in one (`IssuePanel`); Back closes each. What a request or a report
 * did shows on the page (A5, tvOS's D4: no toast): "Requesting…" on the button while it is out, then the status line;
 * "Issue Reported" on its button. What went wrong is a `ProblemDialog`, once whatever was closing has gone, with focus
 * back where it was. Back from the page is the caller's.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaScreen(
    model: MediaModel,
    onPlayed: (key: String) -> Unit = {},
    /** A keyword: its titles' grid (M6). Focus comes back to the keyword ([MediaModel.returnFocus]). */
    onKeyword: (Keyword) -> Unit = {},
    /** A portrait in Cast & Crew: the person's page (M6, DECISIONS 100). Focus comes back to the portrait. */
    onPerson: (CastCard) -> Unit = {},
    /** A lockup in Recommendations or Similar: that title's page. Focus comes back to the lockup. */
    onTitle: (TitleCard) -> Unit = {},
    /** The collection (polish 2, DECISIONS 130): the collection's films. Focus comes back to it. */
    onCollection: (CollectionCard) -> Unit = {},
) {
    // Kept with the model: coming back from a keyword's titles finds the page where it was.
    val scroll = model.scroll
    LaunchedEffect(model) { model.start() }
    val state = model.state
    val page = (state as? MediaState.Ready)?.page
    val density = LocalDensity.current
    // A section's top comes to the top margin; the first screen stays at the page's top (`section`).
    val columnPivot = remember(density) { Pivot(with(density) { Metrics.TopMargin.toPx() }, with(density) { Metrics.BottomMargin.toPx() }) }
    val rowPivot = remember(density) { Pivot(with(density) { Metrics.SideMargin.toPx() }) }
    val loading = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val stops = remember { TitleStops() }
    val foot = remember { PageFoot() }
    // One per copy: focus comes back to the button that opened the modal.
    val buttonFor = remember { mapOf(false to FocusRequester(), true to FocusRequester()) }
    val playFor = remember { mapOf(false to FocusRequester(), true to FocusRequester()) }
    val noButton = remember { FocusRequester() }
    // "Play on Plex" only where a Plex app on this TV opens the link: never a button that opens
    // nothing (DECISIONS 68). Asked once per page read, not per frame.
    val context = LocalContext.current
    val findPlex = LocalPlexApp.current
    val plexApp = remember(context, findPlex) { findPlex(context) }
    val plays = remember(page?.plays, plexApp) {
        page?.plays.orEmpty().mapNotNull { play -> plexApp.packageFor(play.link)?.let { PlayOn(play, it) } }
    }
    // "Watch Trailer" only where an app on this TV plays it (DECISIONS 96, 98): asked once per page read.
    val findTrailerApp = LocalTrailerApp.current
    val trailer = remember(page?.trailer, context, findTrailerApp) {
        page?.trailer?.let(::youtubeWatchLink)?.let { link -> findTrailerApp(context).packageFor(link)?.let { TrailerOn(link, it) } }
    }
    val trailerButton = remember { FocusRequester() }
    // Seerr's "Report an Issue" (M6, DECISIONS 106): a title in the library, the right read with the page.
    val reportButton = remember { FocusRequester() }
    val issueEntry = remember { FocusRequester() }
    val issueSubmit = remember { FocusRequester() }
    // What went wrong away from the request (an app that would not open, a report not sent), and where focus goes back.
    var problem by remember { mutableStateOf<Problem?>(null) }
    var problemBack by remember { mutableStateOf<FocusRequester?>(null) }
    val words = LocalContext.current.resources
    val report = page != null && model.reportsIssues && canReportIssue(page)
    val hasActions = plays.isNotEmpty() || page?.buttons?.isNotEmpty() == true || trailer != null || report
    // Seerr's order: the play button, then the request buttons, then the trailer; the first has focus on arrival.
    val firstButton = plays.firstOrNull()?.let { playFor.getValue(it.action.is4k) }
        ?: page?.buttons?.firstOrNull()?.let { buttonFor.getValue(it.is4k) }
        ?: trailer?.let { trailerButton }
        ?: reportButton.takeIf { report }
        ?: noButton
    val onTrailer: (TrailerOn) -> Unit = { on ->
        try {
            context.startActivity(trailerIntent(on.link, on.packageName))
        } catch (_: ActivityNotFoundException) {
            // The app went away since the page was read: say so rather than do nothing.
            problemBack = trailerButton
            problem = Problem(words.getString(R.string.trailer_not_opened_title), words.getString(R.string.trailer_not_opened))
        }
    }
    // While a copy downloads, the page is read again once a minute (Seerr's download sync runs once a minute;
    // Tom's pick, 96), and its estimate moves on with it.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val downloading = page?.badges?.any { it.progress != null } == true
    LaunchedEffect(downloading) {
        while (downloading) {
            delay(RE_READ_MS)
            model.reread()
            now = System.currentTimeMillis()
        }
    }
    val onPlay: (PlayOn) -> Unit = { play ->
        try {
            context.startActivity(plexIntent(play.action.link, play.packageName))
            // Played through Hither: it leaves Watch Next (M4).
            onPlayed(model.card.key)
        } catch (_: ActivityNotFoundException) {
            // Plex went away since the page was read: say so rather than do nothing.
            problemBack = playFor.getValue(play.action.is4k)
            problem = Problem(words.getString(R.string.plex_not_opened_title), words.getString(R.string.plex_not_opened))
        }
    }
    val modalEntry = remember { FocusRequester() }
    val draft = model.draft
    val series = page?.type == MediaType.TV
    // The copy whose request is out: its button says "Requesting…" until the answer, even after Back closed the panel.
    var sendingCopy by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(model.sending) { sendingCopy = if (model.sending) model.draft?.is4k else null }
    // The whole overview, open over the page (`ReadingScreen`).
    var reading by remember { mutableStateOf(false) }

    // Focus as each state arrives: the placeholder while loading, Try Again after a failure,
    // and the first action once the page is here (the overview when it has none). The
    // target may need a frame to be laid out, and the one it replaces to be removed.
    val kind = state::class
    LaunchedEffect(kind) {
        // Back from a page this one opened: where it was left (DECISIONS 98).
        val back = model.returnFocus
        model.returnFocus = null
        val target = when (state) {
            MediaState.Loading -> loading
            MediaState.Failed -> retry
            is MediaState.Ready -> when (back) {
                is MediaFocus.Keyword -> stops.keywords
                MediaFocus.Collection -> stops.collection
                is MediaFocus.Cast -> stops.cast
                is MediaFocus.Related -> stops.related.getValue(back.kind)
                null -> if (hasActions) firstButton else stops.overview
            }
        }
        settleFocusOn(target)
    }

    // The modal takes focus as it opens and gives it back as it closes (REVIEW.md): to the button that opened it, or,
    // when a request has left that copy nothing to ask for, to the first button left, else the overview. A season's
    // card that opened it takes focus back while the season can still be asked for; once a request has taken it off
    // the shelf, the next season along does (or the one before, the last on the shelf), and with none left, the first
    // action (tvOS's).
    var openedFor by remember { mutableStateOf<Boolean?>(null) }
    var openedForSeason by remember { mutableStateOf<Int?>(null) }
    // Where focus went as the request closed: a failure's dialog gives it back there.
    var afterRequest by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(draft != null) {
        if (draft != null) {
            openedFor = draft.is4k
            openedForSeason = draft.season
            // The film's question takes focus itself (on Request); the series' panel arrives on its entry.
            if (series) settleFocusOn(modalEntry)
        } else {
            val copy = openedFor ?: return@LaunchedEffect
            val season = openedForSeason
            openedFor = null
            openedForSeason = null
            val now = (model.state as? MediaState.Ready)?.page
            val buttons = now?.buttons.orEmpty()
            val left = if (season != null && now != null) seasonsToAskFor(now).map { it.line.number } else emptyList()
            val seasonBack = left.firstOrNull { it == season } ?: left.firstOrNull { season != null && it < season } ?: left.lastOrNull()
            val target = when {
                season != null && seasonBack != null -> {
                    stops.seasonLeft = seasonBack
                    stops.seasonCard(seasonBack)
                }
                season == null && buttons.any { it.is4k == copy } -> buttonFor.getValue(copy)
                buttons.isNotEmpty() -> buttonFor.getValue(buttons.first().is4k)
                plays.isNotEmpty() -> playFor.getValue(plays.first().action.is4k)
                trailer != null -> trailerButton
                else -> stops.overview
            }
            afterRequest = target
            settleFocusOn(target)
        }
    }
    // The issue form: focus in as it opens, and back on its button as it closes (the first action if the
    // button has gone with the right).
    var issueOpen by remember { mutableStateOf(false) }
    // A report that failed holds the form, as it was while the report was out, until its dialog has gone (A9): Back
    // cannot close it under the dialog's way back, and a second Submit cannot be sent under the first one's dialog.
    var issueHeld by remember { mutableStateOf(false) }
    val issueShown = model.issue != null
    LaunchedEffect(issueShown) {
        if (issueShown) {
            issueOpen = true
            settleFocusOn(issueEntry)
        } else if (issueOpen) {
            issueOpen = false
            settleFocusOn(if (report) reportButton else firstButton)
        }
    }
    // The whole overview closes onto its stop.
    var wasReading by remember { mutableStateOf(false) }
    LaunchedEffect(reading) {
        if (reading) {
            wasReading = true
        } else if (wasReading) {
            wasReading = false
            settleFocusOn(stops.overview)
        }
    }

    // A pushed page with no title: the backdrop is this page's top.
    Page(PageKind.Pushed(null)) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides columnPivot) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .testTag("media page"),
            ) {
                Hero(
                    model = model,
                    page = page,
                    now = now,
                    loading = loading,
                    retry = retry,
                    buttonFor = buttonFor,
                    plays = plays,
                    playFor = playFor,
                    onPlay = onPlay,
                    trailer = trailer,
                    trailerButton = trailerButton,
                    onTrailer = onTrailer,
                    report = report,
                    reportButton = reportButton,
                    overview = stops.overview,
                    up = if (hasActions) firstButton else null,
                    onOverview = { reading = true },
                    sendingCopy = sendingCopy,
                    modifier = Modifier.section(),
                )
                if (page != null) {
                    TitleSections(
                        page = page,
                        model = model,
                        stops = stops,
                        foot = foot,
                        rowPivot = rowPivot,
                        onSeason = { offer -> model.open(offer.button, season = offer.line.number) },
                        onPerson = { index, person ->
                            model.returnFocus = MediaFocus.Cast(index)
                            onPerson(person)
                        },
                        onCollection = { collection ->
                            model.returnFocus = MediaFocus.Collection
                            onCollection(collection)
                        },
                        onTitle = { kind, index, card ->
                            model.returnFocus = MediaFocus.Related(kind, index)
                            onTitle(card)
                        },
                        onKeyword = { index, keyword ->
                            model.returnFocus = MediaFocus.Keyword(index)
                            onKeyword(keyword)
                        },
                    )
                    Information(page, model.ratings, Modifier.onSizeChanged { foot.height = it.height })
                }
                Spacer(Modifier.height(Metrics.BottomMargin))
            }
        }
        // A soft top edge once the page has scrolled, so no sliver of a section is left at the
        // screen's edge (the polish audit's item 16).
        TopEdge(scroll)
        // A film's request is a question; a series' its seasons in a panel (A5). Each kept composed, to give focus back.
        RequestQuestion(
            model = model,
            page = page,
            draft = draft,
            open = page != null && draft != null && !series && !model.sending,
            opener = openedFor?.let(buttonFor::getValue),
        )
        RequestPanel(model, page, draft, open = page != null && draft != null && series, entry = modalEntry)
        IssuePanel(model, page, model.issue, open = page != null && model.issue != null, entry = issueEntry, submit = issueSubmit, held = issueHeld, onSend = {
            model.sendIssue { outcome ->
                when (outcome) {
                    // It shows on the button ("Issue Reported"), as the form closes.
                    is IssueOutcome.Sent -> Unit
                    // The form has closed, and the button with the right: focus on the first action after.
                    IssueOutcome.NoRight -> {
                        problemBack = null
                        problem = Problem(words.getString(R.string.issue_not_sent), words.getString(R.string.issue_no_right))
                    }
                    // The form stays to try again, held until the dialog has gone: focus back on Submit after.
                    IssueOutcome.Failed -> {
                        issueHeld = true
                        problemBack = issueSubmit
                        problem = Problem(words.getString(R.string.issue_not_sent), words.getString(R.string.issue_failed))
                    }
                }
            }
        })
        // A request that did not go through (A5; tvOS's alert, Tom's D4 there): once, with focus back where the request
        // left it.
        val failed = model.outcome as? RequestOutcome.Failed
        val requestProblem = remember(failed) { failed?.let { Problem(words.getString(R.string.request_not_sent), requestFailureWords(it, words)) } }
        ProblemAlert(requestProblem, onDone = model::outcomeSeen, opener = afterRequest ?: firstButton, tag = "request problem")
        ProblemAlert(
            problem,
            onDone = {
                problem = null
                issueHeld = false
            },
            opener = problemBack ?: (if (report) reportButton else firstButton),
        )
        if (reading && page != null) {
            val unavailable = stringResource(R.string.overview_unavailable)
            ReadingScreen(
                title = page.title,
                text = remember(page, unavailable) { overviewText(page, unavailable) },
                onClose = { reading = false },
                tag = "overview screen",
                textTag = "overview whole",
            )
        }
    }
}

/**
 * The whole overview for `ReadingScreen`: the tagline in italics, then the overview a paragraph at a time, or
 * [unavailable] where there is none.
 */
private fun overviewText(page: MediaPage, unavailable: String): ReadingText {
    var text = ReadingText()
    page.tagline?.let { text = text.paragraph(it, italic = true) }
    val paragraphs = page.overview?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    return if (paragraphs.isEmpty()) text.paragraph(unavailable) else paragraphs.fold(text) { acc, paragraph -> acc.paragraph(paragraph) }
}

/** Asks [target] for focus once it is laid out (and whatever it replaces has gone): a few frames at most. */
private suspend fun settleFocusOn(target: FocusRequester) {
    repeat(10) {
        withFrameNanos { }
        if (runCatching { target.requestFocus() }.getOrDefault(false)) return
    }
}

/** What a request that was not created came to, in its own words (Seerr's own sentence where it gave one). */
private fun requestFailureWords(outcome: RequestOutcome.Failed, resources: Resources): String = when (outcome.failure) {
    RequestFailure.NOTHING_TO_REQUEST -> resources.getString(R.string.request_nothing_left)
    RequestFailure.ALREADY_REQUESTED -> resources.getString(R.string.request_already, outcome.title)
    // Seerr's own sentence where it gave one ("You do not have permission to make 4K movie requests.")
    RequestFailure.REFUSED -> outcome.serverMessage ?: resources.getString(R.string.request_refused)
    RequestFailure.UNREACHABLE -> resources.getString(R.string.request_unreachable)
    RequestFailure.CSRF_NEEDS_HTTPS -> resources.getString(R.string.request_csrf)
    RequestFailure.SIGNED_OUT, RequestFailure.OTHER -> resources.getString(R.string.request_error)
}

/**
 * The backdrop across the top, full-bleed (tvOS's `TitleBackdrop`): core's 1920 x 800 picture drawn 960 x 400 dp (decoded
 * smaller, DECISIONS 62), fading into the page at its foot and darker towards the left, where the words sit. Two still
 * gradients: nothing here moves when focus does. A title with no backdrop shows its [poster] on the right instead (the
 * poster beside the words went: tvOS has none). A title's page and a person's.
 */
@Composable
internal fun TitleBackdrop(url: String?, modifier: Modifier = Modifier, poster: String? = null) {
    val context = LocalContext.current
    val backdrop = remember(url, context) {
        url?.let { ImageRequest.Builder(context).data(it).size(BACKDROP_WIDTH_PX, BACKDROP_HEIGHT_PX).build() }
    }
    val posterRequest = remember(poster, context) {
        poster?.let { ImageRequest.Builder(context).data(it).size(POSTER_WIDTH_PX, POSTER_HEIGHT_PX).build() }
    }
    Box(modifier.fillMaxWidth().height(Metrics.TitleBackdropHeight)) {
        if (backdrop != null) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.matchParentSize(),
            )
        } else if (posterRequest != null) {
            AsyncImage(
                model = posterRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = Metrics.HeroPosterTop, end = Metrics.SideMargin)
                    .size(Metrics.HeroPosterWidth, Metrics.HeroPosterHeight)
                    .clip(RoundedCornerShape(Metrics.ImageRadius))
                    .background(Tokens.Placeholder)
                    .testTag("title poster"),
            )
        }
        // Darker towards the left, where the words are; then fading into the page at the foot.
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.horizontalGradient(0f to Tokens.Scrim.copy(alpha = 0.75f), 0.7f to Tokens.Scrim.copy(alpha = 0f))),
        )
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(0.35f to Tokens.Scrim.copy(alpha = 0f), 0.75f to Tokens.Scrim.copy(alpha = 0.6f), 1f to Tokens.Scrim)),
        )
    }
}

/**
 * The first screen: the backdrop, and over its foot the title, its line, its status, the actions and the overview's
 * stop, [Metrics.HeroWords] wide at the side margin. A section of its own (`section`): while focus is here the page
 * stays at its top.
 */
@Composable
private fun Hero(
    model: MediaModel,
    page: MediaPage?,
    now: Long,
    loading: FocusRequester,
    retry: FocusRequester,
    buttonFor: Map<Boolean, FocusRequester>,
    plays: List<PlayOn>,
    playFor: Map<Boolean, FocusRequester>,
    onPlay: (PlayOn) -> Unit,
    trailer: TrailerOn?,
    trailerButton: FocusRequester,
    onTrailer: (TrailerOn) -> Unit,
    report: Boolean,
    reportButton: FocusRequester,
    overview: FocusRequester,
    up: FocusRequester?,
    onOverview: () -> Unit,
    sendingCopy: Boolean?,
    modifier: Modifier,
) {
    ReportRecomposition("media header")
    val card = model.card
    Box(modifier.fillMaxWidth().height(Metrics.HeroHeight)) {
        TitleBackdrop(page?.backdrop, poster = if (page != null && page.backdrop == null) page.poster else null)
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = Metrics.SideMargin)
                .width(Metrics.HeroWords),
        ) {
            BasicText(
                page?.title ?: card.title,
                style = HitherType.Title.copy(color = Tokens.Text),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("media title"),
            )
            if (page != null) {
                MetadataLine(page, Modifier.padding(top = 8.dp))
            } else if (card.year != null) {
                BasicText(card.year!!, style = HitherType.Body.copy(color = Tokens.TextSecondary), modifier = Modifier.padding(top = 8.dp))
            }
            val badges = page?.badges ?: listOfNotNull(card.badge)
            if (badges.isNotEmpty()) StatusLine(badges, now, Modifier.padding(top = 8.dp))
            Actions(
                model.state, loading, retry, buttonFor, plays, playFor, trailer, trailerButton,
                onRetry = model::retry, onRequest = { model.open(it) }, onPlay = onPlay, onTrailer = onTrailer,
                report = report, reportButton = reportButton, onReport = model::openIssue,
                sendingCopy = sendingCopy, issueReported = model.issueReported,
            )
            if (page != null) {
                TextStop(
                    text = page.overview ?: stringResource(R.string.overview_unavailable),
                    onSelect = onOverview,
                    tag = "media overview",
                    textTag = "media overview text",
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth()
                        .focusRequester(overview)
                        .focusProperties {
                            // Up comes back to the first action, said outright: the stop is as wide as the row.
                            if (up != null) this.up = up
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        },
                    header = page.tagline?.let { tagline ->
                        {
                            Text(
                                tagline,
                                style = HitherType.Body.copy(fontStyle = FontStyle.Italic),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(bottom = 4.dp).testTag("media tagline"),
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * What the title is, on one line as the Apple TV app writes it: the year, the runtime ("1 hr 34 min") or the seasons,
 * the genres, between middle dots (`Formats`), then the certification in its outlined box.
 */
@Composable
private fun MetadataLine(page: MediaPage, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText(
            Formats.join(listOf(page.year) + page.attributes.map(Formats::runtimeIn)).orEmpty(),
            style = HitherType.Body.copy(color = Tokens.TextSecondary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).testTag("media attributes"),
        )
        page.certification?.let { certification ->
            Box(
                Modifier
                    .border(1.dp, Tokens.TextSecondary, RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
                    .testTag("media certification"),
            ) {
                BasicText(certification, style = HitherType.Caption.copy(color = Tokens.TextSecondary, fontWeight = FontWeight.SemiBold), maxLines = 1)
            }
        }
    }
}

/** The title's status: each copy's glyph and word ("Available", "4K Requested"), and while a copy downloads, its estimate. */
@Composable
private fun StatusLine(badges: List<StatusBadge>, now: Long, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        badges.forEach { badge ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusLabel(badge, style = HitherType.Body)
                // Seerr's DownloadBlock's time left, for each copy downloading (M6).
                badge.progress?.let { DownloadEstimate(it, now) }
            }
        }
    }
}

/**
 * The actions in a row, tv-material's buttons in Seerr's order, 16 dp apart: "Play on Plex" for each copy Plex has, the
 * request buttons, "Watch Trailer", Report an Issue. The first is focused on arrival, and the row is fenced at both
 * ends. The first request button is the prominent one, the page's main action, where Plex has no copy to play (rule 1,
 * tvOS's: with Play on Plex first, nothing on the page is indigo). While loading, a still placeholder of a button's size
 * holds focus; after a failure, the reason and Try Again.
 */
@Composable
private fun Actions(
    state: MediaState,
    loading: FocusRequester,
    retry: FocusRequester,
    buttonFor: Map<Boolean, FocusRequester>,
    plays: List<PlayOn>,
    playFor: Map<Boolean, FocusRequester>,
    trailer: TrailerOn?,
    trailerButton: FocusRequester,
    onRetry: () -> Unit,
    onRequest: (RequestButton) -> Unit,
    onPlay: (PlayOn) -> Unit,
    onTrailer: (TrailerOn) -> Unit,
    report: Boolean,
    reportButton: FocusRequester,
    onReport: () -> Unit,
    /** The copy whose request is out: its button says "Requesting…", keeps focus and does nothing (one press, one request). */
    sendingCopy: Boolean?,
    /** An issue was reported from the page: its button says so (tvOS's D4), and still opens the form for another. */
    issueReported: Boolean,
) {
    ReportRecomposition("media actions")
    val buttons = (state as? MediaState.Ready)?.page?.buttons.orEmpty()
    if (state is MediaState.Ready && buttons.isEmpty() && plays.isEmpty() && trailer == null && !report) return
    val last = plays.size + buttons.size + (if (trailer != null) 1 else 0) + (if (report) 1 else 0) - 1
    if (state == MediaState.Failed) {
        // What went wrong, then the way on, as every page that did not load says it (item 27); here
        // under the title rather than centred, where the buttons would be.
        // A failed `StateView` (A1); the page hands focus to Try Again itself (`settleFocusOn`).
        StateView(StateKind.FAILED, stringResource(R.string.media_failed), Modifier.padding(top = 20.dp), wordsTag = "failure text", focusOnArrival = false) { first ->
            ProminentButton(
                text = stringResource(R.string.try_again),
                onClick = onRetry,
                modifier = first
                    .focusRequester(retry)
                    .focusProperties {
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                    }
                    .testTag("try again"),
            )
        }
        return
    }
    Row(
        Modifier.padding(top = 20.dp).height(ButtonHeight).focusGroup().testTag("media actions"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            MediaState.Loading -> LoadingButton(loading)
            MediaState.Failed -> Unit // drawn above
            is MediaState.Ready -> {
                plays.forEachIndexed { index, play ->
                    PlainButton(
                        text = play.action.text,
                        onClick = { onPlay(play) },
                        icon = HitherIcons.Play,
                        modifier = Modifier
                            .focusRequester(playFor.getValue(play.action.is4k))
                            .focusProperties {
                                if (index == 0) left = FocusRequester.Cancel
                                if (index == last) right = FocusRequester.Cancel
                            }
                            .testTag("action ${play.action.text}"),
                    )
                }
                buttons.forEachIndexed { i, button ->
                    val index = plays.size + i
                    val request = Modifier
                        .focusRequester(buttonFor.getValue(button.is4k))
                        // Fenced at both ends, as a shelf's lockups are.
                        .focusProperties {
                            if (index == 0) left = FocusRequester.Cancel
                            if (index == last) right = FocusRequester.Cancel
                        }
                        .testTag("action ${button.text}")
                    // While its request is out it says so, where it was pressed (A5, tvOS's D4).
                    val busy = sendingCopy == button.is4k
                    val text = if (busy) stringResource(R.string.requesting) else button.text
                    // One prominent button on the page (rule 1): the first request, where nothing plays; a 4K one beside it is plain.
                    if (i == 0 && plays.isEmpty()) {
                        ProminentButton(text = text, onClick = { onRequest(button) }, busy = busy, modifier = request)
                    } else {
                        PlainButton(text = text, onClick = { onRequest(button) }, busy = busy, modifier = request)
                    }
                }
                // "Watch Trailer" among the actions: a plain button with the film icon (Seerr's ghost button went with A1).
                if (trailer != null) {
                    val index = plays.size + buttons.size
                    PlainButton(
                        text = stringResource(R.string.watch_trailer),
                        onClick = { onTrailer(trailer) },
                        icon = HitherIcons.Film,
                        modifier = Modifier
                            .focusRequester(trailerButton)
                            .focusProperties {
                                if (index == 0) left = FocusRequester.Cancel
                                if (index == last) right = FocusRequester.Cancel
                            }
                            .testTag("action watch trailer"),
                    )
                }
                // Report an Issue, last (M6, DECISIONS 106): a plain button; Seerr's yellow fill and triangle went with A1. Once
                // an issue is reported from the page it says so (A5, tvOS's), and still opens the form for another.
                if (report) {
                    val index = last
                    PlainButton(
                        text = stringResource(if (issueReported) R.string.issue_reported else R.string.report_issue),
                        onClick = onReport,
                        modifier = Modifier
                            .focusRequester(reportButton)
                            .focusProperties {
                                if (index == 0) left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                            }
                            .testTag("action report issue"),
                    )
                }
            }
        }
    }
}

/**
 * A button's place while the page loads (tvOS's `LoadingButton`): a still capsule in the placeholder grey, no pulse (A3:
 * a loading page costs nothing per frame). It holds focus until the buttons come, drawn focused as a lockup is (the white
 * edge and the 1.1, in the layer phase).
 */
@Composable
private fun LoadingButton(requester: FocusRequester) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .focusProperties {
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            }
            .focusable()
            .focusScale(focused, scale = Metrics.FocusScale)
            .size(LoadingWidth, ButtonHeight)
            .clip(CircleShape)
            .background(Tokens.Placeholder)
            .then(if (focused) Modifier.border(2.dp, Tokens.Text, CircleShape) else Modifier)
            .testTag("loading"),
    )
}

/** A "Play on Plex" action with the Plex app that opens it. */
data class PlayOn(val action: PlayAction, val packageName: String)

/** A trailer's link with the app on this TV that plays it. */
data class TrailerOn(val link: String, val packageName: String)
