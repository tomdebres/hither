package com.tomdebres.hither.ui.home

import com.tomdebres.hither.ui.settings.ConfirmSignOut
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import com.tomdebres.hither.ui.bar.BarContent
import com.tomdebres.hither.ui.bar.BarFrame
import com.tomdebres.hither.ui.bar.TopBar
import com.tomdebres.hither.ui.bar.BarReturn
import com.tomdebres.hither.ui.bar.BarSpot
import com.tomdebres.hither.ui.bar.Tab
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.tomdebres.hither.R
import com.tomdebres.hither.ready.Front
import com.tomdebres.hither.core.rules.StatusBadge
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.ui.theme.Page
import com.tomdebres.hither.ui.theme.PageKind
import com.tomdebres.hither.ui.theme.PlainButton
import com.tomdebres.hither.ui.theme.ProminentButton
import com.tomdebres.hither.ui.theme.StateKind
import com.tomdebres.hither.ui.theme.StateView
import com.tomdebres.hither.ui.discover.DiscoverModel
import com.tomdebres.hither.ui.discover.DiscoverScreen
import com.tomdebres.hither.ui.discover.seerrPlan
import com.tomdebres.hither.ui.discover.seerrSource
import com.tomdebres.hither.ui.media.MediaModel
import com.tomdebres.hither.ui.media.MediaScreen
import com.tomdebres.hither.ui.media.seerrMediaSource
import com.tomdebres.hither.ui.media.seerrRatings
import com.tomdebres.hither.ui.media.seerrRequests
import com.tomdebres.hither.core.model.Keyword
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.ui.grid.TitleGridModel
import com.tomdebres.hither.ui.grid.TitleGridScreen
import com.tomdebres.hither.ui.grid.keywordTitles
import java.time.LocalDate
import com.tomdebres.hither.ui.grid.rowTitles
import com.tomdebres.hither.core.rules.seeMoreTarget
import com.tomdebres.hither.core.rules.SeeMore
import com.tomdebres.hither.core.rules.RowSource
import com.tomdebres.hither.core.rules.DiscoverRow
import com.tomdebres.hither.core.model.DiscoverQuery
import com.tomdebres.hither.ui.grid.collectionTitles
import com.tomdebres.hither.core.rules.PersonCard
import com.tomdebres.hither.ui.media.seerrRelated
import com.tomdebres.hither.ui.person.PersonModel
import com.tomdebres.hither.ui.person.PersonScreen
import com.tomdebres.hither.ui.person.seerrPersonSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import java.util.IdentityHashMap
import com.tomdebres.hither.ui.browse.BrowseModel
import com.tomdebres.hither.ui.browse.BrowseScreen
import com.tomdebres.hither.ui.browse.seerrBrowse
import com.tomdebres.hither.ui.issues.IssuesModel
import com.tomdebres.hither.ui.issues.IssuesScreen
import com.tomdebres.hither.ui.issues.seerrIssuesSource
import com.tomdebres.hither.ui.media.seerrIssueReporter
import com.tomdebres.hither.ui.settings.DiagnosticsScreen
import com.tomdebres.hither.core.FailureLog
import com.tomdebres.hither.session.Diagnostics
import com.tomdebres.hither.ui.requests.RequestsModel
import com.tomdebres.hither.ui.requests.RequestsScreen
import com.tomdebres.hither.ui.requests.seerrRecentRequestsSource
import com.tomdebres.hither.ui.requests.seerrRequestsSource
import com.tomdebres.hither.ui.search.SearchModel
import com.tomdebres.hither.ui.search.SearchScreen
import com.tomdebres.hither.ui.search.seerrSearch
import com.tomdebres.hither.ui.settings.AboutScreen
import com.tomdebres.hither.ui.settings.PrivacyScreen
import com.tomdebres.hither.ui.settings.SettingsScreen
import com.tomdebres.hither.ui.settings.AdminScreen
import com.tomdebres.hither.ui.settings.AdminSetup
import com.tomdebres.hither.session.AdminAccess
import com.tomdebres.hither.session.AdminCalls
import com.tomdebres.hither.ui.approvals.ApprovalsModel
import com.tomdebres.hither.ui.approvals.ApprovalsScreen
import com.tomdebres.hither.ui.approvals.Decided
import com.tomdebres.hither.ui.approvals.adminApprovalsSource
import com.tomdebres.hither.ui.settings.AdminPinDialog
import com.tomdebres.hither.ui.settings.AdminSwitchModel
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import com.tomdebres.hither.ui.components.AdminBadge
import com.tomdebres.hither.ui.components.AdminBadgeRoom
import androidx.compose.runtime.collectAsState
import com.tomdebres.hither.ui.theme.Metrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Where a signed-in user is. Discover is home; Requests and Issues are the bar's other tabs (DECISIONS 149), and Back
 * from either comes home. The pages the bar opens (search, Settings) come back with Back to the tab page they were
 * opened from; the others to the page that opened them.
 */
private sealed interface Place {
    data object Discover : Place

    /**
     * A title's page; Back returns to [from], or, for one a home-screen card opened ([leaves]), to
     * Discover with the app sent back to the TV's home screen.
     */
    data class Media(val card: TitleCard, val from: Place = Discover, val leaves: Boolean = false) : Place

    data object Search : Place

    /**
     * A keyword's titles of one type (M6, DECISIONS 98), opened from a title's page; Back returns to
     * [from], that page, with focus on the tag. Not a data class: each opening is a page of its own.
     */
    class KeywordTitles(val keyword: Keyword, val type: MediaType, val from: Place) : Place

    /**
     * A collection's films (polish 2, DECISIONS 130), from the collection card on a film's page; Back returns to
     * [from], that page, with focus on the card. Not a data class: each opening is a page of its own.
     */
    class Collection(val collectionId: Int, val name: String, val from: Place) : Place

    /** A person's page (M6, DECISIONS 100), from a cast card or search; Back returns to [from]. */
    class Person(val personId: Int, val name: String, val from: Place) : Place

    data object Requests : Place

    /** Seerr's Issues page (M6, DECISIONS 106), from the bar for an account with an issue right. */
    data object Issues : Place

    /**
     * The Movies or Series page (M6, DECISIONS 102), from a row's "See More" card (112; every linked row since
     * polish 2, 131): [base] is the row's query, [heading] its title (none: "Movies" or "Series"). Back returns to
     * Discover, focus on that card. Not a data class: each opening is a page of its own, read from the top.
     */
    class Browse(val type: MediaType, val base: DiscoverQuery = DiscoverQuery(), val heading: String? = null) : Place

    /**
     * A row's own titles as a grid (polish 2, DECISIONS 131), from its "See More": Trending, a studio, a network
     * or a search, as Seerr links them. Back returns to Discover, focus on that card.
     */
    class RowGrid(val row: DiscoverRow) : Place

    /** Settings; [fromAdmin]: back from that page, focus on its row. */
    data class Settings(val fromAdmin: Boolean = false) : Place

    /** About › Diagnostics (M6, DECISIONS 107; under About since A7, as on tvOS). */
    data object Diagnostics : Place

    /** The TV's admin account (M5), behind Settings. */
    data object Admin : Place

    /** Everyone's requests, as the active admin (M5, DECISIONS 94). */
    data object Approvals : Place

    /** About; [fromDiagnostics]: back from it, focus on its row. */
    data class About(val fromDiagnostics: Boolean = false) : Place

    data object Privacy : Place
}

/** A tab's page. */
private fun placeOf(tab: Tab): Place = when (tab) {
    Tab.DISCOVER -> Place.Discover
    Tab.REQUESTS -> Place.Requests
    Tab.ISSUES -> Place.Issues
}

/** The tab [place] is the page of; null for a page that is not a tab's. */
private fun tabOf(place: Place): Tab? = when (place) {
    Place.Discover -> Tab.DISCOVER
    Place.Requests -> Tab.REQUESTS
    Place.Issues -> Tab.ISSUES
    else -> null
}

/** The pages under [place], itself first: the way Back goes from it (a title's page, a keyword's titles). */
private fun stackOf(place: Place): List<Place> = generateSequence(place) {
    when (it) {
        is Place.Media -> it.from
        is Place.KeywordTitles -> it.from
        is Place.Collection -> it.from
        is Place.Person -> it.from
        else -> null
    }
}.toList()

/**
 * The models of the pages on the stack (DECISIONS 98), one per place (by identity), each with a scope of
 * its own: a page left for another keeps its model, so Back finds it as it was (its scroll, its
 * focus, its answers); a page Back has left is dropped, and its loading stops, as leaving a page always
 * stopped it.
 */
private class PageModels(private val parent: CoroutineScope) {
    private val kept = IdentityHashMap<Place, Pair<Any, CoroutineScope>>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(place: Place, make: (CoroutineScope) -> T): T {
        kept[place]?.let { return it.first as T }
        val scope = CoroutineScope(parent.coroutineContext + Job(parent.coroutineContext[Job]))
        return make(scope).also { kept[place] = it to scope }
    }

    /** Drops every page not under [place]. */
    fun keepOnly(place: Place) {
        val stack = stackOf(place).toSet()
        val gone = kept.keys.filter { it !in stack }
        for (old in gone) kept.remove(old)?.second?.cancel()
    }

    fun grids(): List<TitleGridModel> = kept.values.mapNotNull { it.first as? TitleGridModel }

    fun browses(): List<BrowseModel> = kept.values.mapNotNull { it.first as? BrowseModel }

    fun pages(): List<MediaModel> = kept.values.mapNotNull { it.first as? MediaModel }
}

/**
 * What the signed-in screens tell the rest of the app, and ask of it (M4): Hither is at the front
 * (look at the requests again, and give what to show: [front]), My Requests was opened (the bar's
 * count starts again), a title was opened or played (it leaves Watch Next), and Back from a page a
 * home-screen card opened (leave to the TV's home screen).
 */
@Immutable
class HomeSide(
    val front: suspend () -> Front? = { null },
    val onRequestsOpened: () -> Unit = {},
    val onTitleOpened: (key: String) -> Unit = {},
    val onLeave: () -> Unit = {},
)

/**
 * Signed in: Discover, and the pages reached from it (search; My Requests; a title's page, from
 * Discover, search or a request; Settings, About and Privacy behind the avatar), each going back
 * one step with Back. Discover's, search's and the requests' models live here, above the pages,
 * so rows, results, scroll and focus are where they were when the user comes back.
 */
@Composable
fun HomeScreen(
    state: Screen.Home,
    access: SeerrAccess,
    onSignOut: () -> Unit,
    /** A title a home-screen card asked for: its page opens, and Back from it leaves the app. */
    link: TitleCard? = null,
    onLinkTaken: () -> Unit = {},
    side: HomeSide = HomeSide(),
    /** Discover's focus arrives on Trending, not Recent Requests: `scripts/measure-discover` (DECISIONS 90). */
    startOnTrending: Boolean = false,
    /** The TV's admin account (M5, DECISIONS 92): Settings' "Admin account". None, no row. */
    admin: AdminAccess? = null,
    /** Calls as the active admin (DECISIONS 93): everyone's requests, Approve and Decline (94). */
    adminCalls: AdminCalls? = null,
    /** What Settings › Diagnostics lists (M6, DECISIONS 107): the process's own log, or a test's. */
    failures: FailureLog = Diagnostics.log,
) {
    val scope = rememberCoroutineScope()
    val discover = remember(state.session) {
        // The rows follow the server's Discover settings, read with the account's live rights (DECISIONS 99).
        DiscoverModel(seerrSource(access), state.session, scope, plans = seerrPlan(access)).also { if (startOnTrending) it.startOnRows() }
    }
    // Search lives here too, so its words, results and the card left are kept across a title's page.
    val search = remember(state.session) { SearchModel(seerrSearch(access), scope) }
    // My Requests: the user's own, whatever the account may see of others' (DECISIONS 66).
    val requests = remember(state.session) { RequestsModel(seerrRequestsSource(access, state.session.user.id), scope) }
    // Discover's Recent Requests: Seerr's slider, whose requests the server's rights decide (DECISIONS 118).
    val recent = remember(state.session) { RequestsModel(seerrRecentRequestsSource(access), scope) }
    // Issues (M6): kept like My Requests, read again each time the page opens.
    val issues = remember(state.session) { IssuesModel(seerrIssuesSource(access), scope) }
    // The pages on the stack keep their models (DECISIONS 98).
    val pages = remember(state.session) { PageModels(scope) }
    // A title's status has changed (read on its page, or made by a request): every card of it shows it.
    val onBadge = { key: String, badge: StatusBadge? ->
        discover.setBadge(key, badge)
        search.setBadge(key, badge)
        pages.grids().forEach { it.setBadge(key, badge) }
        pages.browses().forEach { it.setBadge(key, badge) }
        pages.pages().forEach { it.setBadge(key, badge) }
    }
    var place by remember { mutableStateOf<Place>(Place.Discover) }
    LaunchedEffect(place) { pages.keepOnly(place) }
    // The tab page last shown: search and Settings, which the bar opens on any of them, come back to it (149). Read
    // only when leaving those pages, never in composition.
    var tab by remember { mutableStateOf(Tab.DISCOVER) }
    SideEffect { tabOf(place)?.let { tab = it } }
    // Back from a page the bar opened: focus on its control on the page Back comes to (129, 149).
    val barReturn = remember { BarReturn() }
    // The TV's admin while active (M5): its mark on every screen.
    val activeAdmin = admin?.active?.collectAsState()?.value
    // An admin kept on this TV makes "N waiting for approval" the way into everyone's requests (DECISIONS
    // 94). The count is Seerr's, everyone's, read with the family's own session (Seerr's route asks no
    // right), so the admin is never used in the background; it is read on arrival, on coming back to the
    // front and after each decision.
    var adminKept by remember { mutableStateOf(false) }
    var adminWaiting by remember { mutableStateOf<Int?>(null) }
    var countAsks by remember { mutableIntStateOf(0) }
    LaunchedEffect(admin, activeAdmin, place) {
        adminKept = admin?.kept() != null
    }
    LaunchedEffect(adminKept, countAsks) {
        if (!adminKept) {
            adminWaiting = null
            return@LaunchedEffect
        }
        runCatching { access.call { it.requestCount() } }.getOrNull()?.let { adminWaiting = it.pending }
    }
    // The way in asks for the PIN when the admin is not active.
    var switchingIn by remember { mutableStateOf(false) }
    // Back to the family while on everyone's requests: that page is the admin's, so it goes too.
    LaunchedEffect(activeAdmin) {
        if (activeAdmin == null && place == Place.Approvals) place = placeOf(tab)
    }
    var backgrounded by remember { mutableStateOf(false) }
    // The notices, the bar's count and the waiting count (DECISIONS 79).
    val alerts = remember(state.session) { HomeAlerts() }
    // The frame between the bar and the tab page under it (A2): the bar is drawn once, over the three tab pages.
    val frame = remember { BarFrame() }
    // Opening My Requests starts the count again (79). Its page shows as focus passes over its tab (A2), so it counts as
    // opened once it has stayed [REQUESTS_OPENED_AFTER_MS], as tvOS's does (184): walking the bar from Discover to search
    // keeps the count. Select on its tab opens it at once; a title opened from it keeps it open.
    var requestsSeen by remember { mutableStateOf(false) }
    val openRequests = {
        if (!requestsSeen) {
            requestsSeen = true
            alerts.requestsOpened()
            side.onRequestsOpened()
        }
    }
    val onRequests = Place.Requests in stackOf(place)
    LaunchedEffect(onRequests) {
        if (!onRequests) {
            requestsSeen = false
            return@LaunchedEffect
        }
        delay(REQUESTS_OPENED_AFTER_MS)
        openRequests()
    }
    // Focus came to another tab: its page in place of this one, focus left on that tab (A2), no history (149).
    val toTab: (Tab) -> Unit = { to ->
        if (tabOf(place) != to) {
            barReturn.set(BarSpot.of(to))
            place = placeOf(to)
        }
    }
    // The bar, the same on every tab page (149); made again only when what it shows changes, so a page change
    // recomposes its tabs and not the bar.
    val bar = remember(state.displayName, activeAdmin, adminKept, admin, adminCalls, alerts, discover) {
        BarContent(
            // The account stands for whoever is signed in: the approver while the admin is active (109).
            displayName = activeAdmin?.displayName ?: state.displayName,
            onTab = toTab,
            onTabSelected = { if (it == Tab.REQUESTS) openRequests() },
            issues = { discover.seesIssues },
            onSearch = { place = Place.Search },
            onAccount = { place = Place.Settings() },
            requestsCount = { alerts.requestsCount },
            waiting = { alerts.waiting ?: adminWaiting },
            adminMark = activeAdmin?.displayName,
            onWaiting = if (adminKept && admin != null && adminCalls != null) {
                { if (activeAdmin != null) place = Place.Approvals else switchingIn = true }
            } else {
                null
            },
            returnTo = barReturn,
            frame = frame,
            hosted = true,
        )
    }
    val currentTab = remember { { tabOf(place) ?: tab } }
    // Back on the bar of Requests' or Issues' page: Discover's tab, and so its page (A2; Android TV's top navigation: from
    // content to its tab, from another tab to the first, from the first out). On Discover's bar nothing takes Back, and
    // Hither leaves. Back from a page's content is the page's own: to its tab.
    BackHandler(enabled = frame.barHasFocus && tabOf(place).let { it != null && it != Tab.DISCOVER }) {
        // Discover's page arrives with focus on its tab (focus on search or the account is outside the tabs, and focus
        // coming to a tab from outside them never switches the page)
        barReturn.set(BarSpot.DISCOVER)
        place = Place.Discover
    }

    // Live status: coming back to the front, the cards' badges and the requests are asked for again.
    // Starting and coming back, the requests are looked at for the TV's home screen too (M4).
    LifecycleStartEffect(discover) {
        if (backgrounded) countAsks++
        if (backgrounded) {
            discover.refreshBadges()
            requests.refresh()
            recent.refresh()
        }
        // Arriving and coming back: what changed since Hither last showed it.
        scope.launch {
            val asked = alerts.asking()
            side.front()?.let { alerts.take(it, asked) }
        }
        onStopOrDispose { backgrounded = true }
    }

    // Recent Requests is read as Discover comes up, the first time and each time it is come back to, as
    // Seerr's slider reads it whenever Discover mounts (DECISIONS 118). My Requests reads its own as its
    // page opens.
    LaunchedEffect(recent, place) { if (place == Place.Discover) recent.open() }

    // A home-screen card's title: its page, over whatever was showing.
    LaunchedEffect(link) {
        if (link != null) {
            place = Place.Media(link, leaves = true)
            onLinkTaken()
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val current = place) {
            Place.Discover -> DiscoverScreen(
                model = discover,
                onOpen = { place = Place.Media(it) },
                onSeeMore = { row ->
                    when (val target = seeMoreTarget(row, LocalDate.now().toString())) {
                        // Popular Movies and Series open the plain pages (112); every other row's page carries its title
                        is SeeMore.Browse -> place = Place.Browse(
                            target.type,
                            target.base,
                            heading = row.title.takeIf { row.source != RowSource.PopularMovies && row.source != RowSource.PopularSeries },
                        )
                        SeeMore.Grid -> place = Place.RowGrid(row)
                        null -> Unit
                    }
                },
                recent = recent,
                bar = bar,
            )
            Place.Search -> SearchScreen(
                model = search,
                onOpen = { card ->
                    place = when (card) {
                        is TitleCard -> Place.Media(card, from = Place.Search)
                        is PersonCard -> Place.Person(card.personId, card.name, from = Place.Search)
                    }
                },
                // back to the tab page it was opened on, focus on its search pill (129, 149)
                onBack = {
                    barReturn.set(BarSpot.SEARCH)
                    place = placeOf(tab)
                },
                // the field leaves the mark its room
                endInset = if (activeAdmin != null) AdminBadgeRoom else 0.dp,
            )
            Place.Requests -> RequestsScreen(
                model = requests,
                onOpen = { place = Place.Media(it, from = Place.Requests) },
                // the empty page's way to Discover: its rows, as on arriving (Back on the bar is the frame's, A2)
                onBack = { place = Place.Discover },
                bar = bar,
            )
            is Place.Media -> {
                BackHandler {
                    place = current.from
                    if (current.leaves) side.onLeave()
                }
                // Opened in Hither: the title leaves Watch Next (M4).
                LaunchedEffect(current.card.key) { side.onTitleOpened(current.card.key) }
                // The page's own scope, kept while a page it opened is over it: Back from the page
                // stops its loading. A request runs on the home's scope instead, so its answer still
                // reaches the card after Back.
                val media = pages.get(current) { pageScope ->
                    MediaModel(
                        source = seerrMediaSource(access),
                        card = current.card,
                        scope = pageScope,
                        requests = seerrRequests(access),
                        ratingsSource = seerrRatings(access),
                        relatedSource = seerrRelated(access),
                        requestScope = scope,
                        onBadge = onBadge,
                        issues = seerrIssueReporter(access),
                    )
                }
                // A composition of its own per page: a title opened from Recommendations is a page after a
                // page in this same branch, and must not inherit the one under it (its arrival, its effects).
                key(current) {
                    MediaScreen(
                        media,
                        onPlayed = side.onTitleOpened,
                        onKeyword = { keyword -> place = Place.KeywordTitles(keyword, current.card.type, from = current) },
                        onPerson = { person -> place = Place.Person(person.personId, person.name, from = current) },
                        onTitle = { card -> place = Place.Media(card, from = current) },
                        onCollection = { collection -> place = Place.Collection(collection.id, collection.name, from = current) },
                    )
                }
            }
            Place.Issues -> IssuesScreen(
                model = issues,
                onOpen = { place = Place.Media(it, from = Place.Issues) },
                // Back on the bar is the frame's (A2); this is for the page shown on its own
                onBack = { place = Place.Discover },
                bar = bar,
            )
            is Place.Browse -> {
                val browse = pages.get(current) { pageScope -> BrowseModel(current.type, seerrBrowse(access), pageScope, base = current.base, heading = current.heading) }
                key(current) { BrowseScreen(browse, onOpen = { place = Place.Media(it, from = current) }, onBack = { place = Place.Discover }) }
            }
            is Place.RowGrid -> {
                val grid = pages.get(current) { pageScope -> TitleGridModel(rowTitles(access, current.row), pageScope) }
                key(current) {
                    TitleGridScreen(
                        model = grid,
                        heading = current.row.title,
                        onOpen = { place = Place.Media(it, from = current) },
                        onBack = { place = Place.Discover },
                    )
                }
            }
            is Place.Person -> {
                val person = pages.get(current) { pageScope -> PersonModel(seerrPersonSource(access), current.personId, current.name, pageScope) }
                key(current) { PersonScreen(person, onOpen = { place = Place.Media(it, from = current) }, onBack = { place = current.from }) }
            }
            is Place.KeywordTitles -> {
                val grid = pages.get(current) { pageScope ->
                    TitleGridModel(keywordTitles(access, current.type, current.keyword.id), pageScope)
                }
                key(current) {
                    TitleGridScreen(
                        model = grid,
                        heading = stringResource(if (current.type == MediaType.MOVIE) R.string.keyword_movies else R.string.keyword_series, current.keyword.name.replaceFirstChar { it.uppercase() }),
                        onOpen = { place = Place.Media(it, from = current) },
                        onBack = { place = current.from },
                    )
                }
            }
            is Place.Collection -> {
                val grid = pages.get(current) { pageScope -> TitleGridModel(collectionTitles(access, current.collectionId), pageScope) }
                key(current) {
                    TitleGridScreen(
                        model = grid,
                        heading = current.name,
                        onOpen = { place = Place.Media(it, from = current) },
                        onBack = { place = current.from },
                    )
                }
            }
            is Place.Settings -> {
                // back to the tab page the avatar was on, focus on the avatar (129, 149)
                BackHandler {
                    barReturn.set(BarSpot.AVATAR)
                    place = placeOf(tab)
                }
                SettingsScreen(
                    // While the admin is active, the account is the approver's, as Seerr shows whoever is signed in (109).
                    displayName = activeAdmin?.displayName ?: state.displayName,
                    email = if (activeAdmin != null) null else state.session.user.email,
                    serverTitle = state.serverTitle,
                    serverAddress = state.session.baseUrl,
                    method = state.session.method,
                    onAbout = { place = Place.About() },
                    onSignOut = onSignOut,
                    onAdmin = admin?.let { { place = Place.Admin } },
                    backFromAdmin = current.fromAdmin,
                    switchBackTo = activeAdmin?.let { state.displayName },
                    activeAs = activeAdmin?.displayName,
                    onSwitchBack = { admin?.back() },
                )
            }
            Place.Approvals -> {
                val pageScope = rememberCoroutineScope()
                val approvals = remember(adminCalls) {
                    adminCalls?.let { calls ->
                        // What a decision did shows on its lockup, and what went wrong is the queue's own dialog (A5): here only
                        // the waiting count follows.
                        // The titles' pictures from where the family's session says the server's come from (DECISIONS 382).
                        val source = adminApprovalsSource(calls, images = { access.call { it.images } })
                        ApprovalsModel(source, pageScope, onDecided = { decided ->
                            when (decided) {
                                is Decided.Done -> {
                                    // the count follows at once, and is read again
                                    adminWaiting = adminWaiting?.minus(1)?.coerceAtLeast(0)
                                    alerts.decided()
                                    countAsks++
                                }
                                is Decided.NotPending -> countAsks++
                                Decided.NoRight, Decided.Failed -> Unit
                            }
                        })
                    }
                }
                if (approvals != null) {
                    ApprovalsScreen(approvals, onOpen = { place = Place.Media(it, from = Place.Approvals) }, onBack = { place = placeOf(tab) })
                }
            }
            Place.Admin -> {
                // The page's own scope: leaving it stops what it was doing.
                val pageScope = rememberCoroutineScope()
                val setup = remember(admin) { admin?.let { AdminSetup(it, state.session.user, state.session.baseUrl, pageScope) } }
                if (setup != null) AdminScreen(setup, onBack = { place = Place.Settings(fromAdmin = true) })
            }
            Place.Diagnostics -> {
                BackHandler { place = Place.About(fromDiagnostics = true) }
                DiagnosticsScreen(failures)
            }
            is Place.About -> {
                BackHandler { place = Place.Settings() }
                AboutScreen(onPrivacy = { place = Place.Privacy }, onDiagnostics = { place = Place.Diagnostics }, backFromDiagnostics = current.fromDiagnostics)
            }
            Place.Privacy -> {
                BackHandler { place = Place.About() }
                PrivacyScreen()
            }
        }
        // The bar, once over the three tab pages (A2): it stays as focus moving along it changes the page under it, and
        // scrolls away with that page. After the page, so it draws over the page's top (its list's top padding).
        if (tabOf(place) != null) TopBar(current = currentTab, content = bar)
        // The way in asks for the PIN first when the admin is not active (DECISIONS 94).
        if (switchingIn && admin != null) {
            val switchScope = rememberCoroutineScope()
            val switcher = remember(admin) {
                AdminSwitchModel(admin, switchScope, onIn = {
                    switchingIn = false
                    place = Place.Approvals
                }, onGone = {
                    switchingIn = false
                    adminKept = false
                })
            }
            val name by produceState("", admin) { value = admin.kept()?.displayName.orEmpty() }
            AdminPinDialog(switcher, adminName = name, onCancel = { switchingIn = false })
        }
        // The admin's badge, top right on the bar's line, over every page but the tab pages, whose account control reads
        // "Admin" (DECISIONS 93, 149; A2). Drawn after the pages, so a page's own modal does not hide it.
        val badgeShows = activeAdmin != null && tabOf(place) == null
        if (activeAdmin != null && badgeShows) {
            // On the bar's line, centred on it (DECISIONS 109).
            Box(Modifier.align(Alignment.TopEnd).padding(top = Metrics.TopMargin, end = Metrics.SideMargin).height(Metrics.BarHeight), contentAlignment = Alignment.Center) {
                AdminBadge(activeAdmin.displayName)
            }
        }
        // The notices' banner top right in the safe area, beside the centred bar (A2; tvOS's `NoticeBanner`), under the
        // admin's badge where that shows. It takes no focus (DECISIONS 79). Notices only: no outcome is a toast (A5).
        Notices(
            alerts,
            banner = Modifier.align(Alignment.TopEnd).padding(top = if (badgeShows) Metrics.BarFoot else Metrics.TopMargin, end = Metrics.SideMargin),
        )
    }
}

/** How long My Requests' page stays before it counts as opened (tvOS's `RequestAlerts.openedAfter`, DECISIONS 184). */
const val REQUESTS_OPENED_AFTER_MS = 2_000L

/**
 * A stored account whose server is not answering: say so, and offer the two ways on, as a failed `StateView` (A1): Try
 * Again prominent and focused, Sign Out plain, asking first.
 */
@Composable
fun UnavailableScreen(state: Screen.Unavailable, onRetry: () -> Unit, onSignOut: () -> Unit) {
    val signOut = remember { FocusRequester() }
    var asking by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Page(PageKind.Screen) {
            StateView(
                StateKind.FAILED,
                // which server, and that the sign-in is kept (the polish audit's item 28)
                words = when {
                    state.csrfNeedsHttps -> stringResource(R.string.error_csrf_later)
                    state.server != null -> stringResource(R.string.error_unreachable_named, state.server)
                    else -> stringResource(R.string.error_unreachable_kept)
                },
                wordsTag = "unavailable",
                modifier = Modifier.align(Alignment.Center),
            ) { first ->
                ProminentButton(
                    text = stringResource(if (state.retrying) R.string.trying else R.string.try_again),
                    onClick = onRetry,
                    modifier = first.testTag("try again"),
                )
                PlainButton(
                    text = stringResource(R.string.sign_out),
                    // asks first: beside Try Again, one Right and Select would cost the sign-in (item 29)
                    onClick = { asking = true },
                    modifier = Modifier.focusRequester(signOut).testTag("sign out"),
                )
            }
        }
        ConfirmSignOut(asking, state.method, opener = signOut, onConfirm = onSignOut, onCancel = { asking = false })
    }
}
