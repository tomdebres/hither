package com.tomdebres.hither.ui.browse

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tomdebres.hither.core.model.DiscoverQuery
import com.tomdebres.hither.core.model.Genre
import com.tomdebres.hither.core.model.Language
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.rules.BrowseFilters
import com.tomdebres.hither.core.rules.DEFAULT_SORT
import com.tomdebres.hither.core.rules.ProviderTile
import com.tomdebres.hither.core.rules.StatusBadge
import com.tomdebres.hither.core.rules.browseQuery
import com.tomdebres.hither.core.rules.browseRegion
import com.tomdebres.hither.core.rules.languageOptions
import com.tomdebres.hither.core.rules.providerTiles
import com.tomdebres.hither.core.rules.sortOptions
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.ui.discover.DiscoverPage
import com.tomdebres.hither.ui.grid.GridSource
import com.tomdebres.hither.ui.grid.TitleGridModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the filters offer for one type: TMDB's genres, the languages, the region's first streaming services. */
data class FilterOptions(
    val genres: List<Genre>,
    val languages: List<Language>,
    val providers: List<ProviderTile>,
    /** The region the services are listed for, and asked for in. */
    val region: String,
)

/** Where the Movies and Series pages read from: the signed-in server, or a test's script. */
interface BrowseSource {
    suspend fun page(type: MediaType, query: DiscoverQuery, page: Int): DiscoverPage

    suspend fun options(type: MediaType): FilterOptions
}

/**
 * Seerr's Movies or Series page (M6, DECISIONS 102; audit item 6): a titles grid under Seerr's sort and the
 * short filter set. A new sort or filter set is a new grid (its own scope: the old grid's loading stops),
 * read from its first page. The filters' choices are read the first time the filters open, and again
 * there after a failure. Kept on the home's page stack, so a title's page and Back find it as it was.
 */
class BrowseModel(
    val type: MediaType,
    private val source: BrowseSource,
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher = Dispatchers.Default,
    /** The query of the row whose See More opened the page, kept under the sort and filters (polish 2, DECISIONS 131). */
    private val base: DiscoverQuery = DiscoverQuery(),
    /** The page's heading: the row's title, or none for "Movies" or "Series". */
    val heading: String? = null,
) {
    var sort: String by mutableStateOf(DEFAULT_SORT)
        private set
    var filters: BrowseFilters by mutableStateOf(BrowseFilters())
        private set

    var options: FilterOptions? by mutableStateOf(null)
        private set
    var optionsLoading: Boolean by mutableStateOf(false)
        private set
    var optionsFailed: Boolean by mutableStateOf(false)
        private set

    /** The region the services were listed for; a query only names one once services are picked from it. */
    private var region = "US"
    private var gridScope = child()

    /** The grid for the sort and filters now: replaced, not refilled, when either changes. */
    var grid: TitleGridModel by mutableStateOf(newGrid())
        private set

    fun start() = grid.start()

    /** One of Seerr's sorts for this type; the same one again changes nothing. */
    fun sortBy(value: String) {
        if (value == sort || sortOptions(type).none { it.value == value }) return
        sort = value
        renew()
    }

    /** The filters as the panel left them; unchanged, nothing is read again. */
    fun apply(next: BrowseFilters) {
        if (next == filters) return
        filters = next
        renew()
    }

    /** Reads the filters' choices, once (again after a failure); nothing while a read is out. */
    fun loadOptions() {
        if (options != null || optionsLoading) return
        optionsLoading = true
        optionsFailed = false
        scope.launch {
            try {
                val read = withContext(work) { source.options(type) }
                region = read.region
                options = read
            } catch (e: CancellationException) {
                optionsLoading = false
                throw e
            } catch (_: Exception) {
                optionsFailed = true
            }
            optionsLoading = false
        }
    }

    /** A title's page has a new status for it: its card shows it. */
    fun setBadge(key: String, badge: StatusBadge?) = grid.setBadge(key, badge)

    private fun child() = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))

    private fun newGrid(): TitleGridModel {
        val query = browseQuery(type, sort, filters, region, base)
        return TitleGridModel(GridSource { page -> source.page(type, query, page) }, gridScope, work)
    }

    private fun renew() {
        gridScope.cancel()
        gridScope = child()
        grid = newGrid()
        grid.start()
    }
}

/**
 * The pages from the server: `discover/movies|tv` with the query; the choices from `genres`, `languages`
 * and `watchproviders` in the account's streaming region, else the server's, else the US (as "Streaming
 * On" chooses).
 */
fun seerrBrowse(access: SeerrAccess) = object : BrowseSource {
    override suspend fun page(type: MediaType, query: DiscoverQuery, page: Int): DiscoverPage =
        access.call { client -> DiscoverPage(client.images, client.discover(type, query, page)) }

    override suspend fun options(type: MediaType): FilterOptions = access.call { client ->
        coroutineScope {
            val settings = async { client.publicSettings() }
            val user = async { client.me() }
            val genres = async { client.genres(type) }
            val languages = async { client.languages() }
            val region = browseRegion(user.await().settings?.streamingRegion, settings.await().streamingRegion)
            val providers = client.watchProviders(type, region)
            FilterOptions(
                genres = genres.await().filter { it.name.isNotBlank() },
                languages = languageOptions(languages.await()),
                providers = providerTiles(providers, client.images),
                region = region,
            )
        }
    }
}
