package com.tomdebres.hither.ui.search

import com.tomdebres.hither.ui.grid.CardGridModel
import com.tomdebres.hither.ui.grid.FILLS_GRID
import com.tomdebres.hither.ui.grid.GRID_COLUMNS
import com.tomdebres.hither.ui.grid.GRID_LOOKAHEAD

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tomdebres.hither.core.rules.StatusBadge
import com.tomdebres.hither.core.rules.GridCard
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.core.rules.appendResults
import com.tomdebres.hither.core.rules.searchQuery
import com.tomdebres.hither.core.rules.wantsNextPage
import com.tomdebres.hither.core.rules.withGridBadge
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.ui.discover.DiscoverPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the search page shows under the field. */
enum class SearchBody {
    /** Nothing typed yet. */
    EMPTY,

    /** The first answer for these words is on its way, and there is nothing older to show. */
    LOADING,

    /** It did not come: the reason and Try Again. */
    FAILED,

    RESULTS,

    /** The server had nothing for these words: "No results." */
    NO_RESULTS,
}

/**
 * Search, on the field in Seerr's bar (2.4). Typing asks the server once the words have rested
 * for [debounceMillis] (Seerr's search waits 300 ms the same way), and the keyboard's action asks
 * at once; the results of the words before stay until the new ones come, so the grid does not
 * blink at each letter. Answers to older words are dropped. Pages are asked for as focus nears
 * the end, as Discover's rows are. Kept above the pages (`HomeScreen`), so the words, the results
 * and the card left are where they were when the user comes back from a title's page.
 */
class SearchModel(
    private val source: SearchSource,
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher = Dispatchers.Default,
    private val debounceMillis: Long = 300,
) : CardGridModel {
    /** The field's text, as typed. */
    var typed: String by mutableStateOf("")
        private set

    /** The words the results are for (trimmed); null while nothing has been asked. */
    var query: String? by mutableStateOf(null)
        private set

    override var cards: List<GridCard> by mutableStateOf(emptyList())
        private set
    var loading: Boolean by mutableStateOf(false)
        private set
    var failed: Boolean by mutableStateOf(false)
        private set

    /** Try Again is out: its button stays ("Trying…") and keeps focus until the answer. */
    var retrying: Boolean by mutableStateOf(false)
        private set

    /** The card the grid was left on, so focus comes back to it. */
    override var focusedIndex: Int by mutableIntStateOf(0)
        private set

    /** Coming back from a title's page: focus goes to the card left, not to the field. */
    override var returnToCard: Boolean = false

    override val gridState = LazyGridState()

    /** Try Again at the grid's end is out: the card stays ("Trying…") and keeps focus until the answer. */
    override var retryingMore: Boolean by mutableStateOf(false)
        private set

    /** A later page failed: the results stay, and a Try Again card ends the grid until its page comes. */
    override val moreFailed: Boolean get() = cards.isNotEmpty() && (failed || retryingMore)

    val body: SearchBody
        get() = when {
            query == null && !loading -> SearchBody.EMPTY
            cards.isNotEmpty() -> SearchBody.RESULTS
            retrying -> SearchBody.FAILED
            loading -> SearchBody.LOADING
            failed -> SearchBody.FAILED
            else -> SearchBody.NO_RESULTS
        }

    private var job: Job? = null

    /** The words [job] is waiting for or asking. */
    private var asking: String? = null
    private var generation = 0
    private var page = 0
    private var totalPages = 1
    private var gridHasFocus = false

    /** The field changed: ask once the words have rested; nothing typed clears the page. */
    fun type(text: String) {
        typed = text
        val words = searchQuery(text)
        // Already waiting for or asking these words (the field reports more than text changes).
        if (words != null && words == asking && job?.isActive == true) return
        job?.cancel()
        asking = null
        if (words == null) {
            generation++
            query = null
            cards = emptyList()
            loading = false
            failed = false
            retrying = false
            retryingMore = false
            return
        }
        if (words == query && !failed && !loading) return
        asking = words
        job = scope.launch {
            delay(debounceMillis)
            search(words)
        }
    }

    /** The keyboard's action: ask now, unless these words are already shown or being asked. */
    fun submit() {
        val words = searchQuery(typed) ?: return
        if (words == query && !failed && !loading) return
        if (words == query && loading) return
        job?.cancel()
        asking = words
        job = scope.launch { search(words) }
    }

    /** Try Again: the first page again if that failed, else the page after the cards. */
    override fun retry() {
        val words = query ?: return
        if (!failed || loading) return
        if (cards.isEmpty()) {
            job?.cancel()
            asking = words
            // At the press, not when the coroutine starts: a second press finds it out.
            failed = false
            loading = true
            retrying = true
            job = scope.launch { search(words, retrying = true) }
        } else {
            retryingMore = true
            nextPage()
        }
    }

    /** A card has focus: remember it, and ask for more two rows before the end. */
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
        val updated = withGridBadge(cards, key, badge)
        if (updated !== cards) cards = updated
    }

    private suspend fun search(words: String, retrying: Boolean = false) {
        val generation = ++generation
        this.retrying = retrying
        retryingMore = false
        query = words
        page = 0
        totalPages = 1
        failed = false
        loading = true
        take(generation, words, page = 1)
    }

    private fun nextPage() {
        val words = query ?: return
        if (loading || page >= totalPages) return
        failed = false
        loading = true
        val generation = generation
        scope.launch { take(generation, words, page + 1) }
    }

    private suspend fun take(generation: Int, words: String, page: Int) {
        val answer = try {
            withContext(work) { source.page(words, page) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (this.generation == generation) {
                loading = false
                retrying = false
                retryingMore = false
                failed = true
                // Results of other words would sit under these ones: gone with the failure.
                if (page == 1) cards = emptyList()
            }
            return
        }
        if (this.generation != generation) return
        if (page == 1) {
            cards = appendResults(emptyList(), answer.page.results, answer.images)
            focusedIndex = 0
        } else {
            cards = appendResults(cards, answer.page.results, answer.images)
        }
        this.page = page
        totalPages = answer.page.totalPages
        loading = false
        retrying = false
        retryingMore = false
        val focus = if (gridHasFocus) focusedIndex else -1
        if (wantsNextPage(cards.size, focus, this.page, totalPages, busy = false, FILLS_GRID, GRID_LOOKAHEAD)) nextPage()
    }

    companion object {
        /** Six cards across, two rows on the screen: a grid's screenful, and how far ahead it asks for more. */
        const val COLUMNS = GRID_COLUMNS
    }
}

/** Where search results come from: the signed-in server, or a test's script. */
fun interface SearchSource {
    suspend fun page(query: String, page: Int): DiscoverPage
}

/** Search on the signed-in server: the query percent-encoded in full by the client (HANDOVER). */
fun seerrSearch(access: SeerrAccess) = SearchSource { query, page ->
    access.call { client -> DiscoverPage(client.images, client.search(query, page)) }
}
