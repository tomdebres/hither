package com.tomdebres.hither.ui.approvals

import com.tomdebres.hither.core.rules.ImageSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.RequestPage
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.RequestFilter
import com.tomdebres.hither.core.rules.RequestRow
import com.tomdebres.hither.core.rules.RequestTitle
import com.tomdebres.hither.core.rules.afterDecision
import com.tomdebres.hither.core.rules.decisionsFor
import com.tomdebres.hither.core.rules.mayDecide
import com.tomdebres.hither.core.rules.nextToDecide
import com.tomdebres.hither.core.rules.requestRows
import com.tomdebres.hither.core.rules.requestTitle
import com.tomdebres.hither.core.rules.wantsNextPage
import com.tomdebres.hither.session.AdminCalls
import com.tomdebres.hither.ui.requests.TitleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Where the all-users view's requests come from: the server as the active admin, or a test's script. */
interface ApprovalsSource {
    /** The admin's rights, read live on every open. */
    suspend fun me(): User

    /** Everyone's requests under [filter], newest first. */
    suspend fun page(filter: RequestFilter, skip: Int, take: Int): RequestPage

    suspend fun title(type: MediaType, tmdbId: Int): RequestTitle

    /** Approve ([approve]) or decline: the request as Seerr saved it. */
    suspend fun decide(requestId: Int, approve: Boolean): MediaRequest
}

/**
 * The server as the active admin (DECISIONS 93, 94): every call through [AdminCalls.asAdmin] and nothing else. The titles'
 * pictures come from where the family's session says the server's do ([images], DECISIONS 382): the admin's own client
 * may not have read the server's switch since the app started.
 */
fun adminApprovalsSource(admin: AdminCalls, images: suspend () -> ImageSource) = object : ApprovalsSource {
    override suspend fun me(): User = admin.asAdmin { it.me() }

    override suspend fun page(filter: RequestFilter, skip: Int, take: Int): RequestPage =
        admin.asAdmin { it.requests(filter = filter.api, take = take, skip = skip, sort = "added") }

    override suspend fun title(type: MediaType, tmdbId: Int): RequestTitle {
        val pictures = images()
        return admin.asAdmin { client ->
            when (type) {
                MediaType.MOVIE -> requestTitle(client.movie(tmdbId), pictures)
                MediaType.TV -> requestTitle(client.tv(tmdbId), pictures)
            }
        }
    }

    override suspend fun decide(requestId: Int, approve: Boolean): MediaRequest = admin.asAdmin { it.decide(requestId, approve) }
}

/** What the view shows under its filters. */
enum class ApprovalsBody { LOADING, FAILED, EMPTY, ROWS }

/** Where focus goes once a decision is in, before the row changes (so nothing is removed from under it). */
sealed interface FocusAfter {
    /** The next request still to decide: its Approve. */
    data class Row(val requestId: Int) : FocusAfter

    /** None left: the current filter. */
    data object Filters : FocusAfter
}

/** What a decision came to (DECISIONS 85's queue): for the waiting count, and when it did not go through, [ApprovalsModel.problem]. */
sealed interface Decided {
    data class Done(val title: String, val approved: Boolean) : Decided

    /** Someone decided it first (Seerr's 409): the list is read again. */
    data class NotPending(val title: String) : Decided

    /** The account's live rights no longer allow it: the buttons go. */
    data object NoRight : Decided

    /** Anything else: Seerr's own words. */
    data object Failed : Decided
}

/**
 * A decision that did not go through (A5; tvOS's `problem` and `problemRow`): what it came to, on which request and which
 * way, for the queue's `ProblemDialog`, after which focus goes back to that request's lockup.
 */
data class DecisionProblem(val decided: Decided, val requestId: Int, val approve: Boolean)

/**
 * Seerr's Requests page for an approver, at ten feet (M5, DECISIONS 94): everyone's requests under one
 * of Seerr's filters, opening on Pending; Approve and Decline on each pending one, for an account whose
 * live rights allow them ([approver] read on every open). One press, one decision. When one is in,
 * focus moves first ([focusAfter], to the next request to decide or the filters), then the row takes
 * its new status at once ([focusMoved]; the server's word comes with the next read), the outcome goes to
 * [onDecided] and the waiting count follows. One that does not go through is [problem] (A5: no toast either way).
 * Pages of [TAKE] as focus nears the end.
 */
class ApprovalsModel(
    private val source: ApprovalsSource,
    private val scope: CoroutineScope,
    private val onDecided: (Decided) -> Unit = {},
    private val work: CoroutineDispatcher = Dispatchers.Default,
) {
    var filter: RequestFilter by mutableStateOf(RequestFilter.PENDING)
        private set

    /** The requests as Seerr gave them (and, after a decision, as it leaves them). */
    var requests: List<MediaRequest> by mutableStateOf(emptyList())
        private set

    val rows: List<RequestRow> get() = requestRows(requests)

    val titles = mutableStateMapOf<String, TitleState>()

    /** The admin's rights as last read; null until read. */
    var approver: User? by mutableStateOf(null)
        private set

    var loading: Boolean by mutableStateOf(false)
        private set
    var failed: Boolean by mutableStateOf(false)
        private set

    /** Try Again at the list's end is out: it stays ("Trying…") and keeps focus until the answer (A9). */
    var retryingMore: Boolean by mutableStateOf(false)
        private set

    /** The request a decision is out for, and which: nothing else is taken meanwhile. */
    var deciding: Pair<Int, Boolean>? by mutableStateOf(null)
        private set

    /** A decision that did not go through, for the queue's dialog, until [problemSeen]. */
    var problem: DecisionProblem? by mutableStateOf(null)
        private set

    /** The queue's dialog about [problem] has closed. */
    fun problemSeen() {
        problem = null
    }

    /** Set when a decision is in: the screen moves focus there, then calls [focusMoved]. */
    var focusAfter: FocusAfter? by mutableStateOf(null)
        private set

    val mayDecide: Boolean get() = mayDecide(approver)

    val body: ApprovalsBody
        get() = when {
            rows.isNotEmpty() -> ApprovalsBody.ROWS
            loading -> ApprovalsBody.LOADING
            failed -> ApprovalsBody.FAILED
            else -> ApprovalsBody.EMPTY
        }

    /** More requests are on their way after the ones shown (a page, or the list read again). */
    val loadingMore: Boolean get() = rows.isNotEmpty() && loading && !failed && !retryingMore

    /**
     * A later page failed: the rows stay and a Try Again ends the list until its page comes (A9; DECISIONS 65's rule,
     * My Requests' since M3). Before, nothing did, and moving along never asked again: the list stayed short.
     */
    val moreFailed: Boolean get() = rows.isNotEmpty() && (failed || retryingMore)

    private var generation = 0
    private var pages = 0
    private var totalPages = 1
    private var waiting: Pair<MediaRequest, Boolean>? = null
    private val titleSlots = Semaphore(4)

    fun decisions(row: RequestRow): Boolean = requests.firstOrNull { it.id == row.id }?.let { decisionsFor(it, approver) } == true

    /** The page has come up: the rights read again, and the list from the top. */
    fun open() {
        scope.launch {
            approver = runCatching { withContext(work) { source.me() } }.getOrNull()
        }
        load()
    }

    /** A filter chosen: the list under it, from the top. */
    fun choose(next: RequestFilter) {
        if (next == filter && rows.isNotEmpty()) return
        filter = next
        load()
    }

    /**
     * Try Again: the list from its top if its first page failed, the page after the rows if a later one did (A9). Not
     * while the list is being read again (it will replace the rows the page would follow): the press is not taken.
     */
    fun retry() {
        if (loading) return
        if (rows.isNotEmpty() && failed) {
            retryingMore = true
            nextPage()
        } else {
            load()
        }
    }

    /** A row has focus: ask for more as the end comes near; not after a failure (Try Again asks). */
    fun focused(index: Int) {
        if (!failed && wantsNextPage(rows.size, index, pages, totalPages, loading, FILLS_SCREEN, LOOKAHEAD)) nextPage()
    }

    /** Approve or decline [requestId]: once per press, nothing while one is out. */
    fun decide(requestId: Int, approve: Boolean) {
        if (deciding != null || focusAfter != null) return
        val request = requests.firstOrNull { it.id == requestId } ?: return
        if (!decisionsFor(request, approver)) return
        deciding = requestId to approve
        scope.launch {
            val name = title(request)
            try {
                withContext(work) { source.decide(requestId, approve) }
            } catch (e: CancellationException) {
                deciding = null
                throw e
            } catch (_: SeerrException.NotPending) {
                deciding = null
                problem = DecisionProblem(Decided.NotPending(name), requestId, approve)
                onDecided(Decided.NotPending(name))
                reread()
                return@launch
            } catch (_: SeerrException.Refused) {
                deciding = null
                approver = runCatching { withContext(work) { source.me() } }.getOrNull()
                problem = DecisionProblem(Decided.NoRight, requestId, approve)
                onDecided(Decided.NoRight)
                return@launch
            } catch (_: Exception) {
                deciding = null
                problem = DecisionProblem(Decided.Failed, requestId, approve)
                onDecided(Decided.Failed)
                return@launch
            }
            waiting = request to approve
            focusAfter = nextToDecide(requests, requestId)?.let { FocusAfter.Row(it) } ?: FocusAfter.Filters
            onDecided(Decided.Done(name, approve))
        }
    }

    /** Focus has moved off the decided row: it takes its new status now. */
    fun focusMoved() {
        val (request, approve) = waiting ?: run {
            focusAfter = null
            return
        }
        requests = requests.map { if (it.id == request.id) afterDecision(it, approve, approver) else it }
        waiting = null
        deciding = null
        focusAfter = null
    }

    private fun title(request: MediaRequest): String =
        requestRows(listOf(request)).firstOrNull()?.let { (titles[it.titleKey] as? TitleState.Ready)?.title?.title }.orEmpty()

    private fun load() {
        val generation = ++generation
        requests = emptyList()
        pages = 0
        totalPages = 1
        failed = false
        retryingMore = false
        loading = true
        scope.launch { take(generation, 1) }
    }

    /**
     * The list again as far as it was read (after someone else decided a request first). It supersedes a
     * page that may be out, so that page's answer is dropped and it no longer counts as loading (the
     * reviewer's Nit on #39: a stranded `loading` stopped paging and Try Again for good).
     */
    private fun reread() {
        // A filter chosen meanwhile is reading its first page: that read is the fresh list, so it is
        // left to finish (the reviewer's second Nit on #39: the re-read dropped it).
        if (pages == 0) return
        val generation = ++generation
        val upTo = (pages.coerceAtLeast(1)) * TAKE
        loading = true
        scope.launch {
            val fresh = try {
                withContext(work) { source.page(filter, 0, upTo) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (this@ApprovalsModel.generation != generation) return@launch
            loading = false
            if (fresh == null) return@launch
            requests = fresh.results
            totalPages = (fresh.pageInfo.results + TAKE - 1) / TAKE
            fetchTitles()
        }
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
        val known = requests.mapTo(HashSet()) { it.id }
        requests = if (page == 1) answer.results else requests + answer.results.filter { known.add(it.id) }
        pages = page
        totalPages = (answer.pageInfo.results + TAKE - 1) / TAKE
        loading = false
        fetchTitles()
    }

    private fun fetchTitles() {
        for (row in rows.distinctBy { it.titleKey }) {
            val state = titles[row.titleKey]
            if (state != null && state != TitleState.Failed) continue
            titles[row.titleKey] = TitleState.Loading
            scope.launch {
                val title = try {
                    titleSlots.withPermit { withContext(work) { source.title(row.type, row.tmdbId) } }
                } catch (e: CancellationException) {
                    titles.remove(row.titleKey)
                    throw e
                } catch (_: Exception) {
                    titles[row.titleKey] = TitleState.Failed
                    return@launch
                }
                titles[row.titleKey] = TitleState.Ready(title)
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
