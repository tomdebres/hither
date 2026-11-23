package com.tomdebres.hither.ui.discover

import com.tomdebres.hither.core.rules.seesIssues
import com.tomdebres.hither.core.rules.seesRequesters

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.TvDetails
import com.tomdebres.hither.core.rules.DiscoverPlan
import com.tomdebres.hither.core.rules.DiscoverRow
import com.tomdebres.hither.core.rules.RowSource
import com.tomdebres.hither.core.rules.discoverPlan
import com.tomdebres.hither.core.rules.recentlyAddedResult
import com.tomdebres.hither.core.rules.StatusBadge
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.core.rules.appendPage
import com.tomdebres.hither.core.rules.titleCard
import com.tomdebres.hither.core.rules.rowWantsNextPage
import com.tomdebres.hither.core.rules.withBadge
import com.tomdebres.hither.core.rules.withFreshBadges
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** What a row has to show where its cards would be. */
enum class RowBody {
    /** The cards (and, at the end, a placeholder while more come or a Try Again if they failed). */
    CARDS,

    /** Nothing yet: placeholders. */
    LOADING,

    /** The first page failed: the reason and Try Again. */
    FAILED,

    /** The server had nothing for this row: "No results." */
    EMPTY,
}

/** One row of Discover: its cards, how far it has paged, and where it was left. */
@Stable
class RowState(val row: DiscoverRow) {
    var cards: List<TitleCard> by mutableStateOf(emptyList())
        internal set
    var loading by mutableStateOf(true)
        internal set
    var failed by mutableStateOf(false)
        internal set

    /** Try Again at the row's end is out: the card stays ("Trying…") and keeps focus until its page comes. */
    var retryingMore by mutableStateOf(false)
        internal set

    /** The card the row was left on, so focus comes back to it; 0 until one has had focus. */
    var focusedIndex by mutableStateOf(0)
        internal set

    /** The row's scroll, kept here so it survives a visit to another page. */
    val listState = LazyListState()

    internal var page = 0
    internal var totalPages = 1
    internal var hasFocus = false

    /** Bumped on every reload, so an answer to an older load is dropped. */
    internal var generation = 0

    val body: RowBody
        get() = when {
            cards.isNotEmpty() -> RowBody.CARDS
            loading -> RowBody.LOADING
            failed -> RowBody.FAILED
            else -> RowBody.EMPTY
        }

    /** More cards are on their way after the ones shown (not a retried page: its Try Again card stays). */
    val loadingMore: Boolean get() = cards.isNotEmpty() && loading && !retryingMore

    /** The last page asked for failed, after some cards had come; or Try Again for it is out. */
    val moreFailed: Boolean get() = cards.isNotEmpty() && (failed || retryingMore)
}

/**
 * Discover's rows, each loading on its own: one failing or slow row never holds up the others. Which
 * rows, in what order, is the server's Discover settings and the account's live rights ([plans], M6,
 * DECISIONS 99): Hither's own five stand in while that is read, and are kept by name, so a row the plan
 * keeps is never asked for twice. Pages are asked for as focus nears a row's end; badges are asked for
 * again when the app comes back to the front ([refreshBadges]). All state is Compose state, changed on
 * [scope]'s thread; the server's answers are read and turned into cards on [work].
 */
class DiscoverModel(
    private val source: DiscoverSource,
    private val session: Session,
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher = Dispatchers.Default,
    /** The server's Discover settings and the account's rights; none: Hither's own rows, as before M6. */
    private val plans: PlanSource? = null,
) {
    /** The rows shown, in order: Hither's own until the plan is read. */
    var rows: List<RowState> by mutableStateOf(DiscoverRow.entries.map { RowState(it) })
        private set

    /** Whether Recent Requests is on the page: the server's Discover settings say (DECISIONS 99). */
    var recentShown: Boolean by mutableStateOf(true)
        private set

    /**
     * How many of [rows] come before Recent Requests: its place in the server's order (Tom's pick, polish 2,
     * DECISIONS 119). Hither's own rows have none above it until the plan is read.
     */
    var recentAt: Int by mutableIntStateOf(0)
        private set

    /** The page's vertical scroll, kept for the same reason as each row's. */
    val columnState = LazyListState()

    /** The row that had focus last, by name: kept across a new plan, which can put rows above it. */
    private var lastRowName: String? = null

    /** The row that had focus last, so coming back to Discover lands where it was left. Read off composition. */
    val lastRow: Int get() = rows.indexOfFirst { it.row.name == lastRowName }.coerceAtLeast(0)

    /**
     * Whether the row last used is Recent Requests (DECISIONS 84), and the card left there. [inRecent] is a
     * plain field, read when focus arrives or leaves the bar, never in composition, so a move between rows
     * recomposes nothing; [recentIndex] is state read through a `derivedStateOf` by the cards, as a row's
     * focused index is. A new page starts at its top ([atTop]): whichever row the server's order puts first.
     */
    var inRecent: Boolean = false
        private set

    /** No row used yet, or Back took the page to its top: arrival goes to the topmost row (polish 2). */
    val atTop: Boolean get() = !inRecent && lastRowName == null
    var recentIndex: Int by mutableIntStateOf(0)
        private set

    /** A card of Recent Requests has focus. */
    fun recentFocused(index: Int) {
        inRecent = true
        recentIndex = index
    }

    /**
     * Arrival on the first of Discover's own rows (Trending) rather than Recent Requests: how
     * `scripts/measure-discover` starts every build on the same path, whatever rows lie above
     * Trending (DECISIONS 90). Called before the screen first shows.
     */
    fun startOnRows() {
        inRecent = false
        lastRowName = DiscoverRow.TRENDING.name
    }

    /** Recent Requests went while it held focus (the user has no requests): the first row takes over. */
    fun recentGone() {
        inRecent = false
        recentIndex = 0
    }

    /** The bar's Issues (M6, DECISIONS 106): the rights as signed in, then as the plan reads them live. */
    var seesIssues: Boolean by mutableStateOf(seesIssues(session.user.permissions))

    /**
     * Recent Requests' cards name who asked (Tom, 2026-11-13, DECISIONS 118): Seerr's RequestCard's rule, the
     * rights as signed in, then as the plan reads them live.
     */
    var seesRequesters: Boolean by mutableStateOf(seesRequesters(session.user.permissions))
        private set

    private var started = false

    /** Reads the plan (where there is one), then asks for every row's first page, once. */
    fun start() {
        if (started) return
        started = true
        val plans = plans
        if (plans == null) {
            rows.forEach { load(it) }
            return
        }
        scope.launch {
            val plan = try {
                withContext(work) { plans.plan() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Neither the settings nor the rights could be read: Hither's own rows, the rights as signed in.
                discoverPlan(null, session.user.permissions)
            }
            follow(plan)
            rows.forEach { if (it.generation == 0) load(it) }
        }
    }

    /** The rows as the plan has them, each row already there kept as it is. */
    private fun follow(plan: DiscoverPlan) {
        val kept = rows.associateBy { it.row.name }
        recentShown = plan.recentRequests
        // Recent Requests coming off the top (or onto it) changes where the page starts
        val topMoved = (recentAt == 0) != (plan.recentAt == 0)
        recentAt = plan.recentAt.coerceIn(0, plan.rows.size)
        seesIssues = plan.issues
        seesRequesters = plan.requesters
        showRows(plan.rows.map { kept[it.name] ?: RowState(it) }, topMoved)
    }

    /**
     * Bumped when the row focus would come back to has gone (a plan without it, an empty Recently Added):
     * the screen hands focus to the row last used, else the first. Compose itself moves a removed item's
     * focus somewhere (the bar's first pill), so the screen cannot wait for focus to be nowhere.
     */
    var refocus: Int by mutableIntStateOf(0)
        private set

    /** A row's card has had focus since Discover started: the user has used the rows. */
    private var rowsUsed = false

    private fun showRows(next: List<RowState>, topMoved: Boolean = false) {
        // A list at its top stays at its top: anchored to its first row's key, rows a plan puts above that
        // row would sit above the view (M3's lesson).
        val atTop = columnState.firstVisibleItemIndex == 0 && columnState.firstVisibleItemScrollOffset == 0
        val target = lastRowName
        val firstChanged = rows.firstOrNull()?.row?.name != next.firstOrNull()?.row?.name
        rows = next
        if (atTop) columnState.requestScrollToItem(0)
        when {
            // The row last used has gone: the first row takes focus.
            rowsUsed && target != null && next.none { it.row.name == target } -> {
                lastRowName = null
                refocus++
            }
            // Before anyone has used the rows (the plan arriving at the start), focus goes where arrival
            // puts it on the new rows: the first row, or Trending for measure-discover. The placeholder that
            // held it may have scrolled out of the list's window.
            !rowsUsed && (firstChanged || topMoved || target != null) -> {
                // the page's top moved before anyone used the rows: arrival's place in Recent Requests was the
                // old top's, not the user's choice, and the page goes to its new top, where focus will be (polish 2)
                if (firstChanged || topMoved) {
                    inRecent = false
                    columnState.requestScrollToItem(0)
                }
                if (target != null && next.none { it.row.name == target }) lastRowName = null
                refocus++
            }
        }
    }

    /**
     * Try Again on a row: the first page again if that failed, the next page if a later one did.
     * For a later page the card stays, reading "Trying…", until the page comes (DECISIONS 69).
     */
    fun retry(row: RowState) {
        if (!row.failed || row.loading) return
        if (row.cards.isEmpty()) {
            load(row)
        } else {
            row.retryingMore = true
            nextPage(row)
        }
    }

    /**
     * A card in [row] has focus (or its "See More" card, at the index after the cards it shows, DECISIONS
     * 112): remember it, so focus comes back to it, Back from the Movies or Series page included; and ask
     * for more if the end is near.
     */
    fun focused(row: RowState, index: Int) {
        row.focusedIndex = index
        row.hasFocus = true
        lastRowName = row.row.name
        rowsUsed = true
        inRecent = false
        if (!row.failed && rowWantsNextPage(row.row, row.cards.size, index, row.page, row.totalPages, row.loading)) nextPage(row)
    }

    /** Back from the rows: the page goes to its top, so the topmost row is the one to come back to. */
    fun toTop() {
        lastRowName = null
        inRecent = false
    }

    /** Focus has left [row]: it stops counting as near its end. */
    fun left(row: RowState) {
        row.hasFocus = false
    }

    /**
     * The statuses again, for the cards already shown, in place: the pages each row has loaded
     * are asked for again and each card takes its fresh badge. Nothing moves and nothing is
     * added, so the user's place is kept. A failure keeps the badges there were.
     */
    fun refreshBadges() {
        for (row in rows) {
            if (row.cards.isEmpty() || row.loading) continue
            val generation = row.generation
            val pages = row.page
            scope.launch {
                val fresh = try {
                    (1..pages).flatMap { page -> fetch(row.row, page).cards() }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@launch
                }
                if (row.generation == generation) row.cards = withFreshBadges(row.cards, fresh)
            }
        }
    }

    /**
     * A title's page has a new status for it (a request just made, or a fresher read): every
     * row holding its card shows the new badge at once, without asking for the row again.
     */
    fun setBadge(key: String, badge: StatusBadge?) {
        for (row in rows) {
            val updated = withBadge(row.cards, key, badge)
            if (updated !== row.cards) row.cards = updated
        }
    }

    private fun load(row: RowState) {
        val generation = ++row.generation
        row.cards = emptyList()
        row.page = 0
        row.totalPages = 1
        row.focusedIndex = 0
        row.failed = false
        row.loading = true
        scope.launch { take(row, generation, page = 1) }
    }

    private fun nextPage(row: RowState) {
        if (row.loading || row.page >= row.totalPages) return
        row.failed = false
        row.loading = true
        scope.launch { take(row, row.generation, page = row.page + 1) }
    }

    private suspend fun take(row: RowState, generation: Int, page: Int) {
        val answer = try {
            fetch(row.row, page)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (row.generation == generation) {
                row.loading = false
                row.retryingMore = false
                row.failed = true
            }
            return
        }
        if (row.generation != generation) return
        // Seerr's web leaves out an empty Recently Added (RecentlyAddedSlider); the screen hands focus on
        // if it held it.
        if (row.row.source == RowSource.RecentlyAdded && page == 1 && answer.page.results.isEmpty()) {
            showRows(rows - row)
            return
        }
        // Appended here, on the main thread, to the row as it is now: a badge refresh that
        // landed meanwhile is kept.
        row.cards = appendPage(row.cards, answer.page.results, answer.baseUrl, session.settings, session.user.permissions)
        row.page = page
        row.totalPages = answer.page.totalPages
        row.loading = false
        row.retryingMore = false
        // A row that filters left short fills itself; one with focus near its end carries on.
        val focus = if (row.hasFocus) row.focusedIndex else -1
        if (rowWantsNextPage(row.row, row.cards.size, focus, row.page, row.totalPages, busy = false)) nextPage(row)
    }

    /** One page of [row], asked for and read off the main thread. */
    private suspend fun fetch(row: DiscoverRow, page: Int): DiscoverPage = withContext(work) { source.page(row, page) }
}

/** One page of a row as the server sent it, and the server it came from (for poster addresses). */
class DiscoverPage(val baseUrl: String, val page: Page<MediaResult>) {
    fun cards(): List<TitleCard> = page.results.mapNotNull { titleCard(it, baseUrl) }
}

/** Where a row's pages come from: the signed-in server, or a test's script. */
fun interface DiscoverSource {
    suspend fun page(row: DiscoverRow, page: Int): DiscoverPage
}

/** Where Discover's plan comes from: the server's Discover settings and the account's live rights, or a test's script. */
fun interface PlanSource {
    suspend fun plan(): DiscoverPlan
}

/**
 * The plan from the signed-in server: `settings/discover` and `auth/me` asked for together (the rights
 * read live, Tom's pick for Recently Added, 96). Settings the server will not give leave Hither's own rows;
 * an ended session is renewed as for any call.
 */
fun seerrPlan(access: SeerrAccess) = PlanSource {
    access.call { client ->
        coroutineScope {
            val user = async { client.me() }
            val sliders = async {
                try {
                    client.discoverSliders()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SeerrException.SignedOut) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            discoverPlan(sliders.await(), user.await().permissions)
        }
    }
}

/**
 * The rows' pages from the signed-in server, through the call that renews an ended session. Recently
 * Added's titles are its records' details, read four at a time and kept for the session, so coming back
 * to the front asks again only for the records (their statuses) and any new title.
 */
fun seerrSource(access: SeerrAccess): DiscoverSource {
    val details = ConcurrentHashMap<String, MediaResult>()
    return DiscoverSource { row, page ->
        access.call { client ->
            val answer = if (row.source == RowSource.RecentlyAdded) client.recentlyAdded(details) else client.page(row, page)
            DiscoverPage(client.baseUrl, answer)
        }
    }
}

private suspend fun SeerrClient.page(row: DiscoverRow, page: Int): Page<MediaResult> = when (val source = row.source) {
    RowSource.Trending -> trending(page)
    RowSource.PopularMovies -> popularMovies(page)
    RowSource.PopularSeries -> popularSeries(page)
    RowSource.UpcomingMovies -> upcomingMovies(page)
    RowSource.UpcomingSeries -> upcomingSeries(page)
    RowSource.RecentlyAdded -> error("Recently Added is read through its records")
    is RowSource.Query -> discover(source.type, source.query, page)
    is RowSource.Search -> search(source.query, page)
    is RowSource.Studio -> studioMovies(source.id, page)
    is RowSource.Network -> networkSeries(source.id, page)
}

/** Seerr's Recently Added: the library's 20 newest, each record's title read from its details (one page). */
private suspend fun SeerrClient.recentlyAdded(cache: MutableMap<String, MediaResult>): Page<MediaResult> {
    val records = library().results.filter { it.tmdbId > 0 && MediaType.fromApi(it.mediaType) != null }
    val gate = Semaphore(4)
    val results = coroutineScope {
        records.map { record ->
            async {
                val key = "${record.mediaType}:${record.tmdbId}"
                val known = cache[key] ?: gate.withPermit {
                    // A title whose details will not come is left out, as Seerr's card shows nothing for it.
                    val read: MovieDetails? = if (record.mediaType == MediaType.MOVIE.api) orNone { movie(record.tmdbId) } else null
                    val readTv: TvDetails? = if (record.mediaType == MediaType.TV.api) orNone { tv(record.tmdbId) } else null
                    recentlyAddedResult(record, read, readTv)?.also { cache[key] = it.copy(mediaInfo = null) }
                }
                known?.copy(mediaInfo = record)
            }
        }.awaitAll().filterNotNull()
    }
    return Page(page = 1, totalPages = 1, totalResults = results.size, results = results)
}

/** The answer, or null when this one title's details failed; an ended session and cancelling still end the read. */
private suspend fun <T> orNone(read: suspend () -> T): T? = try {
    read()
} catch (e: CancellationException) {
    throw e
} catch (e: SeerrException.SignedOut) {
    throw e
} catch (_: Exception) {
    null
}
