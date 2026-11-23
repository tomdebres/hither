package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.DiscoverQuery
import com.tomdebres.hither.core.model.DiscoverSlider
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.SliderType
import com.tomdebres.hither.core.model.TvDetails

/**
 * What Discover shows (M6, DECISIONS 99): whether Recent Requests heads the page, and the rows under it,
 * in order.
 */
data class DiscoverPlan(
    val recentRequests: Boolean,
    val rows: List<DiscoverRow>,
    /** The bar's Issues (M6, DECISIONS 106): the account holds an issue right, read live with the plan. */
    val issues: Boolean = false,
    /** Recent Requests' cards name who asked (DECISIONS 118): [seesRequesters], read live with the plan. */
    val requesters: Boolean = false,
    /** How many of [rows] come before Recent Requests: its place in the server's order (Tom's pick, polish 2, 119). */
    val recentAt: Int = 0,
)

/**
 * Whether the account may see Recently Added: Seerr's web shows the row only to Recent View or Manage
 * Requests (`RecentlyAddedSlider`), read from the account's live rights (Tom's pick, 96).
 */
fun seesRecentlyAdded(permissions: Int?): Boolean = Permission.hasAny(permissions, Permission.MANAGE_REQUESTS, Permission.RECENT_VIEW)

/**
 * Whether a request card names who asked: Seerr's RequestCard shows the requester only to Manage Requests or
 * View Requests (Tom, 2026-11-13, DECISIONS 118). The same rights are what make Seerr's list everyone's, so an
 * account without them sees its own requests, unnamed.
 */
fun seesRequesters(permissions: Int?): Boolean = Permission.hasAny(permissions, Permission.MANAGE_REQUESTS, Permission.REQUEST_VIEW)

/**
 * Discover's rows from the server's Discover settings ([sliders], `GET /settings/discover`) and the
 * account's [permissions] (Tom's picks, 96):
 * - Every enabled slider in the server's order, as Seerr's sliders follow it (Tom's pick, polish 2, 119; it
 *   ended 84's and 99's Recent Requests first): **Recently Added** only for an account that may see it,
 *   **Recent Requests** where it is enabled, at [DiscoverPlan.recentAt], the built-in rows, and the admin's
 *   custom sliders under their own titles (keyword, genre, search, studio, network, streaming services).
 * - Seerr's built-in genre, studio and network sliders stay out (80, item 11), and so does the Plex
 *   watchlist (a local account's is empty, the parity audit's "Not proposed").
 * - A custom slider whose data Hither cannot read (no title, a studio that is not a number, a streaming
 *   slider without its region) is left out rather than shown wrong.
 * - Without the server's settings ([sliders] null: not read, or refused), Hither's own rows as before M6,
 *   Recently Added and then Recent Requests above them, as Seerr's default order has them.
 */
fun discoverPlan(sliders: List<DiscoverSlider>?, permissions: Int?): DiscoverPlan {
    val recent = seesRecentlyAdded(permissions)
    if (sliders == null) {
        val head = listOfNotNull(DiscoverRow.RECENTLY_ADDED.takeIf { recent })
        return DiscoverPlan(true, head + DiscoverRow.entries, seesIssues(permissions), seesRequesters(permissions), recentAt = head.size)
    }
    val shown = sliders.filter { it.enabled }.sortedBy { it.order }
    val rows = mutableListOf<DiscoverRow>()
    var recentAt: Int? = null
    for (slider in shown) {
        val row = when (slider.type) {
            SliderType.RECENT_REQUESTS -> {
                if (recentAt == null) recentAt = rows.size
                null
            }
            SliderType.RECENTLY_ADDED -> DiscoverRow.RECENTLY_ADDED.takeIf { recent }
            SliderType.TRENDING -> DiscoverRow.TRENDING
            SliderType.POPULAR_MOVIES -> DiscoverRow.POPULAR_MOVIES
            SliderType.POPULAR_TV -> DiscoverRow.POPULAR_SERIES
            SliderType.UPCOMING_MOVIES -> DiscoverRow.UPCOMING_MOVIES
            SliderType.UPCOMING_TV -> DiscoverRow.UPCOMING_SERIES
            else -> customRow(slider)
        }
        if (row != null && rows.none { it.name == row.name }) rows += row
    }
    return DiscoverPlan(recentAt != null, rows, seesIssues(permissions), seesRequesters(permissions), recentAt = recentAt ?: 0)
}

/** An admin's custom slider as a row, or null for a built-in Hither leaves out or data it cannot read. */
private fun customRow(slider: DiscoverSlider): DiscoverRow? {
    if (slider.isBuiltIn) return null
    val title = slider.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val data = slider.data?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val source = when (slider.type) {
        SliderType.TMDB_MOVIE_KEYWORD -> RowSource.Query(MediaType.MOVIE, DiscoverQuery(keywords = data))
        SliderType.TMDB_TV_KEYWORD -> RowSource.Query(MediaType.TV, DiscoverQuery(keywords = data))
        SliderType.TMDB_MOVIE_GENRE -> RowSource.Query(MediaType.MOVIE, DiscoverQuery(genre = data))
        SliderType.TMDB_TV_GENRE -> RowSource.Query(MediaType.TV, DiscoverQuery(genre = data))
        SliderType.TMDB_SEARCH -> RowSource.Search(data)
        SliderType.TMDB_STUDIO -> RowSource.Studio(data.toIntOrNull() ?: return null)
        SliderType.TMDB_NETWORK -> RowSource.Network(data.toIntOrNull() ?: return null)
        SliderType.TMDB_MOVIE_STREAMING_SERVICES, SliderType.TMDB_TV_STREAMING_SERVICES -> {
            // Seerr's data is "<region>,<providers>" (CreateSlider); its web splits at the first comma.
            val region = data.substringBefore(',', missingDelimiterValue = "").trim()
            val providers = data.substringAfter(',', missingDelimiterValue = "").trim()
            if (region.isEmpty() || providers.isEmpty()) return null
            val type = if (slider.type == SliderType.TMDB_MOVIE_STREAMING_SERVICES) MediaType.MOVIE else MediaType.TV
            RowSource.Query(type, DiscoverQuery(watchRegion = region, watchProviders = providers))
        }
        else -> return null
    }
    return DiscoverRow("CUSTOM_${slider.id}", title, source, ordinal = 100 + slider.id)
}

/**
 * A Recently Added card's title, from its library [record] and the title's details, as Seerr's
 * `TmdbTitleCard` reads it: the details' name and pictures, the record's status.
 */
fun recentlyAddedResult(record: MediaInfo, movie: MovieDetails?, tv: TvDetails?): MediaResult? = when {
    movie != null -> MediaResult(
        id = movie.id, mediaType = "movie", title = movie.title, overview = movie.overview, posterPath = movie.posterPath,
        backdropPath = movie.backdropPath, releaseDate = movie.releaseDate, voteAverage = movie.voteAverage, mediaInfo = record,
    )
    tv != null -> MediaResult(
        id = tv.id, mediaType = "tv", name = tv.name, overview = tv.overview, posterPath = tv.posterPath,
        backdropPath = tv.backdropPath, firstAirDate = tv.firstAirDate, voteAverage = tv.voteAverage, mediaInfo = record,
    )
    else -> null
}
