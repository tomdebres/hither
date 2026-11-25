package com.tomdebres.hither.ui.issues

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.model.Issue
import com.tomdebres.hither.core.model.IssuePage
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.rules.IssueFilter
import com.tomdebres.hither.core.rules.IssueRow
import com.tomdebres.hither.core.rules.RequestTitle
import com.tomdebres.hither.core.rules.issueRows
import com.tomdebres.hither.core.rules.requestTitle
import com.tomdebres.hither.core.rules.wantsNextPage
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.ui.requests.TitleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Where the Issues page reads from: the signed-in server, or a test's script. */
interface IssuesSource {
    /**
     * Issues under [filter], newest first. Seerr's 403 without a right is [SeerrException.Refused] with
     * [SeerrException.Refused.lacksRight]; any other refusal is a failure.
     */
    suspend fun page(filter: IssueFilter, skip: Int, take: Int): IssuePage

    suspend fun title(type: MediaType, tmdbId: Int): RequestTitle
}

fun seerrIssuesSource(access: SeerrAccess) = object : IssuesSource {
    override suspend fun page(filter: IssueFilter, skip: Int, take: Int): IssuePage =
        access.call { client -> client.issues(filter = filter.api, take = take, skip = skip) }

    override suspend fun title(type: MediaType, tmdbId: Int): RequestTitle = access.call { client ->
        when (type) {
            MediaType.MOVIE -> requestTitle(client.movie(tmdbId), client.images)
            MediaType.TV -> requestTitle(client.tv(tmdbId), client.images)
        }
    }
}

/** What the page shows under its filter. */
enum class IssuesBody { LOADING, FAILED, NO_RIGHT, EMPTY, ROWS }

/**
 * Seerr's Issues page at ten feet (M6, DECISIONS 106): issues under one of Seerr's filters, opening on Open,
 * newest first; the titles read four at a time; pages of [TAKE] as focus nears the end. Seerr's 403 (the
 * right gone since Discover read it) is said, not retried. The list is read again each time the page opens.
 */
class IssuesModel(
    private val source: IssuesSource,
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher = Dispatchers.Default,
) {
    var filter: IssueFilter by mutableStateOf(IssueFilter.OPEN)
        private set
    private var issues: List<Issue> by mutableStateOf(emptyList())
    val rows: List<IssueRow> get() = issueRows(issues)
    val titles = mutableStateMapOf<String, TitleState>()
    var loading: Boolean by mutableStateOf(false)
        private set
    var failed: Boolean by mutableStateOf(false)
        private set
    var refused: Boolean by mutableStateOf(false)
        private set

    /** Try Again at the list's end is out: it stays ("Trying…") and keeps focus until the answer (A9). */
    var retryingMore: Boolean by mutableStateOf(false)
        private set

    /** The row left for a title's page: focus comes back to it. */
    var focusedIndex: Int by mutableIntStateOf(0)
        private set
    var returnToRow: Boolean = false

    val body: IssuesBody
        get() = when {
            rows.isNotEmpty() -> IssuesBody.ROWS
            refused -> IssuesBody.NO_RIGHT
            loading -> IssuesBody.LOADING
            failed -> IssuesBody.FAILED
            else -> IssuesBody.EMPTY
        }

    /** More issues are on their way after the ones shown. */
    val loadingMore: Boolean get() = rows.isNotEmpty() && loading && !failed && !retryingMore

    /**
     * A later page failed: the rows stay and a Try Again ends the list until its page comes (A9; DECISIONS 65's rule,
     * My Requests' since M3). Before, nothing did, and moving along never asked again: the list stayed short.
     */
    val moreFailed: Boolean get() = rows.isNotEmpty() && (failed || retryingMore)

    private var generation = 0
    private var pages = 0
    private var totalPages = 1
    private val titleSlots = Semaphore(4)

    /** The page has come up: from the top, under the filter it had, unless coming back to a row. */
    fun open() {
        if (returnToRow && rows.isNotEmpty()) return
        load()
    }

    fun choose(next: IssueFilter) {
        if (next == filter && rows.isNotEmpty()) return
        filter = next
        focusedIndex = 0
        load()
    }

    /** Try Again: the list from its top if its first page failed, the page after the rows if a later one did (A9). */
    fun retry() {
        if (loading) return
        if (rows.isNotEmpty() && failed) {
            retryingMore = true
            nextPage()
        } else {
            load()
        }
    }

    /** A lockup has focus: remember it, and ask for more as the end comes near; not after a failure (Try Again asks). */
    fun focused(index: Int) {
        focusedIndex = index
        if (!failed && wantsNextPage(rows.size, index, pages, totalPages, loading, FILLS_SCREEN, LOOKAHEAD)) nextPage()
    }

    /** The list again from its top: the grid's entry with it, so a fresh arrival's Right lands on the first (A8, DECISIONS 350). */
    private fun load() {
        val generation = ++generation
        issues = emptyList()
        focusedIndex = 0
        pages = 0
        totalPages = 1
        failed = false
        refused = false
        retryingMore = false
        loading = true
        scope.launch { take(generation, 1) }
    }

    private fun nextPage() {
        if (loading || pages >= totalPages) return
        failed = false
        loading = true
        val generation = generation
        scope.launch { take(generation, pages + 1) }
    }

    private suspend fun take(generation: Int, page: Int) {
        val answer = try {
            withContext(work) { source.page(filter, (page - 1) * TAKE, TAKE) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SeerrException.Refused) {
            if (this.generation == generation) {
                loading = false
                retryingMore = false
                // Only Seerr's own refusal of the right means "no right"; a proxy's or any other 403 is a failure to
                // try again (the polish 2 audit's item 17)
                if (e.lacksRight) refused = true else failed = true
            }
            return
        } catch (_: Exception) {
            if (this.generation == generation) {
                loading = false
                retryingMore = false
                failed = true
            }
            return
        }
        if (this.generation != generation) return
        retryingMore = false
        val known = issues.mapTo(HashSet()) { it.id }
        issues = if (page == 1) answer.results else issues + answer.results.filter { known.add(it.id) }
        pages = page
        totalPages = (answer.pageInfo.results + TAKE - 1) / TAKE
        loading = false
        fetchTitles()
    }

    private fun fetchTitles() {
        for (row in rows.distinctBy { it.titleKey }) {
            val key = row.titleKey ?: continue
            val type = row.type ?: continue
            val state = titles[key]
            if (state != null && state != TitleState.Failed) continue
            titles[key] = TitleState.Loading
            scope.launch {
                val title = try {
                    titleSlots.withPermit { withContext(work) { source.title(type, row.tmdbId) } }
                } catch (e: CancellationException) {
                    titles.remove(key)
                    throw e
                } catch (_: Exception) {
                    titles[key] = TitleState.Failed
                    return@launch
                }
                titles[key] = TitleState.Ready(title)
            }
        }
    }

    companion object {
        /** Seerr's own page size. */
        const val TAKE = 10
        const val FILLS_SCREEN = 5
        const val LOOKAHEAD = 5
    }
}
