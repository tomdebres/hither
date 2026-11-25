package com.tomdebres.hither.ui.grid

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tomdebres.hither.core.model.DiscoverQuery
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.ui.discover.seerrSource
import com.tomdebres.hither.core.rules.DiscoverRow
import com.tomdebres.hither.core.rules.GridCard
import com.tomdebres.hither.core.rules.StatusBadge
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.core.rules.appendSearchPage
import com.tomdebres.hither.core.rules.wantsNextPage
import com.tomdebres.hither.core.rules.withBadge
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.ui.discover.DiscoverPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a titles grid shows. */
enum class GridBody { LOADING, FAILED, RESULTS, NO_RESULTS }

/**
 * A grid of titles from one query, paged as search is (M6, DECISIONS 98): a keyword's titles now, the
 * Movies and Series pages later. The first page is asked for on [start]; later pages as focus nears the
 * end; a failed first page offers Try Again in its place, a failed later one at the grid's end. Kept on
 * the page stack in `HomeScreen`, so a title's page and Back find the grid and the card left.
 */
class TitleGridModel(
    private val source: GridSource,
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher = Dispatchers.Default,
) : CardGridModel {
    private var titles: List<TitleCard> by mutableStateOf(emptyList())
    override val cards: List<GridCard> get() = titles
    var loading: Boolean by mutableStateOf(false)
        private set
    var failed: Boolean by mutableStateOf(false)
        private set

    /** Try Again for the first page is out: its button stays ("Trying…") and keeps focus. */
    var retrying: Boolean by mutableStateOf(false)
        private set

    override var focusedIndex: Int by mutableIntStateOf(0)
        private set
    override var returnToCard: Boolean = false
    override val gridState = LazyGridState()
    override var retryingMore: Boolean by mutableStateOf(false)
        private set
    override val moreFailed: Boolean get() = cards.isNotEmpty() && (failed || retryingMore)

    val body: GridBody
        get() = when {
            cards.isNotEmpty() -> GridBody.RESULTS
            retrying -> GridBody.FAILED
            loading || !started -> GridBody.LOADING
            failed -> GridBody.FAILED
            else -> GridBody.NO_RESULTS
        }

    private var started by mutableStateOf(false)
    private var page = 0
    private var totalPages = 1
    private var gridHasFocus = false

    fun start() {
        if (started) return
        started = true
        loading = true
        scope.launch { take(1) }
    }

    /** Try Again: the first page again if that failed, else the page after the cards. */
    override fun retry() {
        if (!failed || loading) return
        if (cards.isEmpty()) {
            failed = false
            loading = true
            retrying = true
            scope.launch { take(1) }
        } else {
            retryingMore = true
            nextPage()
        }
    }

    override fun focused(index: Int) {
        focusedIndex = index
        gridHasFocus = true
        if (!failed && wantsNextPage(cards.size, index, page, totalPages, loading, FILLS_GRID, GRID_LOOKAHEAD)) nextPage()
    }

    fun left() {
        gridHasFocus = false
    }

    /** A title's page has a new status for it: its card shows it, in place. */
    fun setBadge(key: String, badge: StatusBadge?) {
        val updated = withBadge(titles, key, badge)
        if (updated !== titles) titles = updated
    }

    private fun nextPage() {
        if (loading || page >= totalPages) return
        failed = false
        loading = true
        scope.launch { take(page + 1) }
    }

    private suspend fun take(page: Int) {
        val answer = try {
            withContext(work) { source.page(page) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            loading = false
            retrying = false
            retryingMore = false
            failed = true
            return
        }
        titles = appendSearchPage(if (page == 1) emptyList() else titles, answer.page.results, answer.images)
        this.page = page
        totalPages = answer.page.totalPages
        loading = false
        retrying = false
        retryingMore = false
        val focus = if (gridHasFocus) focusedIndex else -1
        if (wantsNextPage(cards.size, focus, this.page, totalPages, busy = false, FILLS_GRID, GRID_LOOKAHEAD)) nextPage()
    }
}

/** Where a grid's pages come from: the signed-in server, or a test's script. */
fun interface GridSource {
    suspend fun page(page: Int): DiscoverPage
}

/** A Discover row's own titles, page by page, as the row reads them: its See More grid (polish 2, DECISIONS 131). */
fun rowTitles(access: SeerrAccess, row: DiscoverRow): GridSource {
    val rows = seerrSource(access)
    return GridSource { page -> rows.page(row, page) }
}

/**
 * A collection's films (`collection/{id}`, the polish 2 audit's item 12), in Seerr's order (by release date, the
 * server's): one page, as Seerr's collection page lists them all.
 */
fun collectionTitles(access: SeerrAccess, collectionId: Int) = GridSource { page ->
    access.call { client ->
        val parts = if (page == 1) client.collection(collectionId).parts else emptyList()
        DiscoverPage(client.images, Page(page = page, totalPages = 1, totalResults = parts.size, results = parts))
    }
}

/** A keyword's titles of one type (`discover/movies|tv?keywords=`), as Seerr's keyword link opens them. */
fun keywordTitles(access: SeerrAccess, type: MediaType, keywordId: Int) = GridSource { page ->
    access.call { client -> DiscoverPage(client.images, client.discover(type, DiscoverQuery(keywords = keywordId.toString()), page)) }
}
