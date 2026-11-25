package com.tomdebres.hither.ui.requests

import com.tomdebres.hither.core.rules.withoutRequest

import com.tomdebres.hither.core.rules.canCancel

import com.tomdebres.hither.core.SeerrException

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.RequestPage
import com.tomdebres.hither.core.rules.RequestRow
import com.tomdebres.hither.core.rules.RequestTitle
import com.tomdebres.hither.core.rules.appendRequests
import com.tomdebres.hither.core.rules.requestRows
import com.tomdebres.hither.core.rules.requestTitle
import com.tomdebres.hither.core.rules.wantsNextPage
import com.tomdebres.hither.session.SeerrAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** What the requests page shows under its heading. */
enum class RequestsBody {
    /** The first page is on its way: placeholders. */
    LOADING,

    /** It did not come: the reason and Try Again. */
    FAILED,

    /** The user has asked for nothing yet. */
    EMPTY,

    ROWS,
}

/** A title's name and pictures for its rows: on their way, here, or not to be had just now. */
sealed interface TitleState {
    data object Loading : TitleState

    data class Ready(val title: RequestTitle) : TitleState

    /** Asked for again at the next refresh. */
    data object Failed : TitleState
}

/**
 * My Requests (`docs/design/seerr-reference.md`, 2.10): the signed-in user's own requests,
 * newest first, every status, no filters (DECISIONS 13). Pages of [TAKE] are asked for as focus
 * nears the end, as Discover's rows are (44). The list does not carry the titles' names and
 * pictures, so each title's details are asked for once, a few at a time, as Seerr's web asks
 * for them row by row.
 *
 * Every arrival on the page ([open]) after the first reads the list again, and so does the app
 * coming back to the front ([refresh]; DECISIONS 45): statuses change in place, a request made
 * meanwhile appears at the top, and focus stays on the row it was on. A failed refresh keeps
 * what is shown. Kept above the pages (`HomeScreen`), so a title's page and Back find the row.
 *
 * State is Compose state, changed on [scope]'s thread; answers are read on [work].
 */
class RequestsModel(
    private val source: RequestsSource,
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher = Dispatchers.Default,
) {
    var rows: List<RequestRow> by mutableStateOf(emptyList())
        private set

    /** Each title's name and pictures, by [RequestRow.titleKey]; shared by its requests. */
    val titles = mutableStateMapOf<String, TitleState>()

    var loading: Boolean by mutableStateOf(false)
        private set
    var failed: Boolean by mutableStateOf(false)
        private set

    /** Try Again on the first page is out: the button stays ("Trying…") and keeps focus until the answer. */
    var retrying: Boolean by mutableStateOf(false)
        private set

    /** Try Again at the list's end is out: it stays ("Trying…") and keeps focus until the answer. */
    var retryingMore: Boolean by mutableStateOf(false)
        private set

    /** The row the list was left on, so focus comes back to it. */
    var focusedIndex: Int by mutableIntStateOf(0)
        internal set

    /** Coming back from a title's page: focus goes to the row left, not to the top. */
    var returnToRow: Boolean = false

    /** The request whose cancelling is out: its button reads "Cancelling…" and nothing else is taken. */
    var cancelling: Int? by mutableStateOf(null)
        private set

    /**
     * Cancels [row] once (asking first is the screen's): one press, one DELETE, nothing while one is out.
     * The row stays until [drop], which the screen calls on [CancelOutcome.Cancelled].
     */
    fun cancel(row: RequestRow, done: (CancelOutcome) -> Unit) {
        if (cancelling != null || !canCancel(row)) return
        cancelling = row.id
        val name = title(row)?.title
        scope.launch {
            val outcome = try {
                withContext(work) { source.cancel(row.id) }
                CancelOutcome.Cancelled(row.id, name)
            } catch (e: CancellationException) {
                cancelling = null
                throw e
            } catch (_: SeerrException.Refused) {
                CancelOutcome.NotPending(name)
            } catch (_: Exception) {
                CancelOutcome.Failed(name)
            }
            cancelling = null
            if (outcome is CancelOutcome.NotPending) refresh()
            done(outcome)
        }
    }

    /**
     * The cancelled request's row goes. The list's entry stays on the place it had, so it is the row that
     * came up under it (the one above, for the last): focus falls back into the list and lands there.
     */
    fun drop(id: Int) {
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0) return
        rows = withoutRequest(rows, id)
        if (focusedIndex > index) focusedIndex -= 1
        focusedIndex = focusedIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    }

    /**
     * Bumped when a refresh has put newer requests above the top row while focus was on it: the
     * page takes focus and the scroll to the new top, so what was just asked for is in view.
     */
    var newTop: Int by mutableIntStateOf(0)
        private set

    val listState = LazyListState()

    val body: RequestsBody
        get() = when {
            rows.isNotEmpty() -> RequestsBody.ROWS
            retrying -> RequestsBody.FAILED
            loading -> RequestsBody.LOADING
            failed -> RequestsBody.FAILED
            else -> RequestsBody.EMPTY
        }

    /** More rows are on their way after the ones shown. */
    val loadingMore: Boolean get() = rows.isNotEmpty() && loading && !retryingMore

    /** A later page failed: the rows stay and a Try Again row ends the list until its page comes. */
    val moreFailed: Boolean get() = rows.isNotEmpty() && (failed || retryingMore)

    private var started = false
    private var generation = 0
    private var pages = 0
    private var totalPages = 1
    private var rereading = false
    private var listHasFocus = false
    private val titleSlots = Semaphore(TITLES_AT_ONCE)

    /** The page has come up: the first time, the first page; after that, the list read again. */
    fun open() {
        if (!started) {
            started = true
            load()
        } else {
            refresh()
        }
    }

    /**
     * The list again, as far as it has been read: each row takes its fresh status and requests made
     * since appear in their place. Focus stays on its row, except on the top row, where the user is
     * looking at the newest: there the new top row takes it ([newTop]). Nothing happens while a page
     * is out, and a failure keeps the rows there were. A list with no rows (empty, or its first page
     * failed) is simply asked for again.
     */
    fun refresh() {
        if (!started || loading || rereading) return
        if (rows.isEmpty()) {
            load()
            return
        }
        val generation = generation
        rereading = true
        scope.launch {
            val fresh = try {
                withContext(work) { source.page(skip = 0, take = pages * TAKE) }
            } catch (e: CancellationException) {
                rereading = false
                throw e
            } catch (_: Exception) {
                rereading = false
                return@launch
            }
            rereading = false
            if (this@RequestsModel.generation != generation) return@launch
            val updated = requestRows(fresh.results)
            // Where focus is now, not when the list was asked for: the user may have moved.
            val focusedKey = rows.getOrNull(focusedIndex)?.key
            val onTop = focusedIndex == 0
            rows = updated
            totalPages = pagesOf(fresh)
            if (onTop) {
                if (updated.firstOrNull()?.key != focusedKey) newTop++
                focusedIndex = 0
            } else {
                // The row left keeps its place in the user's eye: focus follows it by key.
                focusedIndex = updated.indexOfFirst { it.key == focusedKey }.takeIf { it >= 0 }
                    ?: focusedIndex.coerceIn(0, (updated.size - 1).coerceAtLeast(0))
            }
            fetchTitles()
        }
    }

    /**
     * Try Again: the first page again if that failed, else the page after the rows. Not while a
     * refresh is out (it will replace the rows the page would follow): the press is not taken and
     * Try Again stays as it was, rather than reading "Trying…" for a page never asked for.
     */
    fun retry() {
        if (!failed || loading || rereading) return
        if (rows.isEmpty()) {
            retrying = true
            load()
        } else {
            retryingMore = true
            nextPage()
        }
    }

    /** A row has focus: remember it, and ask for more as the end comes near. */
    fun focused(index: Int) {
        focusedIndex = index
        listHasFocus = true
        if (!failed && wantsNextPage(rows.size, index, pages, totalPages, loading || rereading, FILLS_SCREEN, LOOKAHEAD)) nextPage()
    }

    /** Focus has left the list. */
    fun left() {
        listHasFocus = false
    }

    /** The title [row] is for, as far as it is known. */
    fun title(row: RequestRow): RequestTitle? = (titles[row.titleKey] as? TitleState.Ready)?.title

    private fun load() {
        val generation = ++generation
        rows = emptyList()
        pages = 0
        totalPages = 1
        focusedIndex = 0
        failed = false
        loading = true
        scope.launch { take(generation, page = 1) }
    }

    private fun nextPage() {
        if (loading || rereading || pages >= totalPages) return
        failed = false
        loading = true
        val generation = generation
        scope.launch { take(generation, pages + 1) }
    }

    private suspend fun take(generation: Int, page: Int) {
        val answer = try {
            withContext(work) { source.page(skip = (page - 1) * TAKE, take = TAKE) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (this.generation == generation) {
                loading = false
                retrying = false
                retryingMore = false
                failed = true
            }
            return
        }
        if (this.generation != generation) return
        rows = if (page == 1) requestRows(answer.results) else appendRequests(rows, answer.results)
        pages = page
        totalPages = pagesOf(answer)
        loading = false
        retrying = false
        retryingMore = false
        fetchTitles()
        val focus = if (listHasFocus) focusedIndex else -1
        if (wantsNextPage(rows.size, focus, pages, totalPages, busy = false, FILLS_SCREEN, LOOKAHEAD)) nextPage()
    }

    /** Asks for each title the rows need and nobody has asked for, or whose last answer failed. */
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

    /** How many pages of [TAKE] the server's count makes; a refresh reads several at once. */
    private fun pagesOf(page: RequestPage): Int = (page.pageInfo.results + TAKE - 1) / TAKE

    companion object {
        /** Seerr's own page size for the requests list. */
        const val TAKE = 10

        /** Rows on the screen at once: a screenful, and how far ahead the next page is asked for. */
        const val FILLS_SCREEN = 5
        const val LOOKAHEAD = 5

        /** Titles asked for at once: a page's worth would be ten calls in one go. */
        const val TITLES_AT_ONCE = 4
    }
}

/** Where the requests and the titles' names come from: the signed-in server, or a test's script. */
interface RequestsSource {
    /** The user's own requests, newest first, every status. */
    suspend fun page(skip: Int, take: Int): RequestPage

    suspend fun title(type: MediaType, tmdbId: Int): RequestTitle

    /** Cancels (removes) one of the user's own pending requests (M6, DECISIONS 101). */
    suspend fun cancel(requestId: Int) {
        error("this list cancels nothing")
    }
}

/**
 * The signed-in user's requests from the server. `requestedBy` is always this user: Seerr gives
 * an account without REQUEST_VIEW only its own requests whatever is asked (its web's Requests
 * page shows this account exactly that), and one that holds it would otherwise get everyone's.
 * So the page is the user's own on any account, as Seerr's own profile page asks for it.
 */
fun seerrRequestsSource(access: SeerrAccess, userId: Int) = object : RequestsSource {
    override suspend fun page(skip: Int, take: Int): RequestPage =
        access.call { client -> client.requests(filter = "all", take = take, skip = skip, requestedBy = userId) }

    override suspend fun title(type: MediaType, tmdbId: Int): RequestTitle = access.call { client ->
        when (type) {
            MediaType.MOVIE -> requestTitle(client.movie(tmdbId), client.images)
            MediaType.TV -> requestTitle(client.tv(tmdbId), client.images)
        }
    }

    // A 401 or Seerr's own 403 was refused before the route ran: renewed and sent once more, as a request is.
    override suspend fun cancel(requestId: Int) = access.call { client -> client.cancelRequest(requestId) }
}

/**
 * Discover's Recent Requests (Tom, 2026-11-13, DECISIONS 118): Seerr's `RecentRequestsSlider` asks
 * `/request?filter=all&take=10&sort=added&skip=0` with no `requestedBy`, and the server decides whose: everyone's
 * for an account with View Requests or Manage Requests, its own otherwise. Only the row reads it; My Requests,
 * since-last-seen and the home screen keep asking for the account's own ([seerrRequestsSource], `ReadyLook`).
 */
fun seerrRecentRequestsSource(access: SeerrAccess) = object : RequestsSource {
    override suspend fun page(skip: Int, take: Int): RequestPage =
        access.call { client -> client.requests(filter = "all", take = take, skip = skip, sort = "added") }

    override suspend fun title(type: MediaType, tmdbId: Int): RequestTitle = access.call { client ->
        when (type) {
            MediaType.MOVIE -> requestTitle(client.movie(tmdbId), client.images)
            MediaType.TV -> requestTitle(client.tv(tmdbId), client.images)
        }
    }
}

/** What cancelling a request came to (M6, DECISIONS 101). */
sealed interface CancelOutcome {
    val title: String?

    /** Gone from the server; the row goes once focus has moved off it. */
    data class Cancelled(val id: Int, override val title: String?) : CancelOutcome

    /** Seerr refused (it is no longer pending: approved or declined meanwhile); the list is read again. */
    data class NotPending(override val title: String?) : CancelOutcome

    data class Failed(override val title: String?) : CancelOutcome
}
