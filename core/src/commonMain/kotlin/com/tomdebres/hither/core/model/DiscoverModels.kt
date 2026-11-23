package com.tomdebres.hither.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One row of the server's Discover settings (`GET /settings/discover`, Seerr's `DiscoverSlider`): its
 * [type] (Seerr's `DiscoverSliderType`, [SliderType]), its place ([order]), whether it is shown
 * ([enabled]), and for a custom slider the admin's [title] and its [data] (a keyword id, a genre id, a
 * search, a studio or network id, or "region,provider").
 */
@Serializable
data class DiscoverSlider(
    val id: Int = 0,
    val type: Int = 0,
    val order: Int = 0,
    val isBuiltIn: Boolean = false,
    val enabled: Boolean = true,
    val title: String? = null,
    val data: String? = null,
)

/** Seerr's `DiscoverSliderType` (`server/constants/discover.ts`). */
object SliderType {
    const val RECENTLY_ADDED = 1
    const val RECENT_REQUESTS = 2
    const val PLEX_WATCHLIST = 3
    const val TRENDING = 4
    const val POPULAR_MOVIES = 5
    const val MOVIE_GENRES = 6
    const val UPCOMING_MOVIES = 7
    const val STUDIOS = 8
    const val POPULAR_TV = 9
    const val TV_GENRES = 10
    const val UPCOMING_TV = 11
    const val NETWORKS = 12
    const val TMDB_MOVIE_KEYWORD = 13
    const val TMDB_MOVIE_GENRE = 14
    const val TMDB_TV_KEYWORD = 15
    const val TMDB_TV_GENRE = 16
    const val TMDB_SEARCH = 17
    const val TMDB_STUDIO = 18
    const val TMDB_NETWORK = 19
    const val TMDB_MOVIE_STREAMING_SERVICES = 20
    const val TMDB_TV_STREAMING_SERVICES = 21
}

/** `GET /media`: library records, as the Recently Added row reads them. */
@Serializable
data class LibraryPage(
    val pageInfo: PageInfo = PageInfo(),
    val results: List<MediaInfo> = emptyList(),
)

/**
 * What `GET /discover/movies` and `GET /discover/tv` are asked for beyond the page (Seerr's
 * `QueryFilterOptions`): the Movies and Series pages' sort and filters, a keyword's grid and the custom
 * sliders. Null leaves a filter out. Dates are `yyyy-MM-dd`.
 */
data class DiscoverQuery(
    /** Seerr's `MovieSortOptions` or `TvSortOptions`, "popularity.desc" and the like. */
    val sortBy: String? = null,
    /** Genre ids, comma-separated ("and"). */
    val genre: String? = null,
    /** Keyword ids, comma-separated. */
    val keywords: String? = null,
    /** An original language, ISO 639-1. */
    val language: String? = null,
    val releasedFrom: String? = null,
    val releasedTo: String? = null,
    /** Streaming service ids, pipe- or comma-separated, in [watchRegion]. */
    val watchProviders: String? = null,
    val watchRegion: String? = null,
) {
    /** The query's parameters for a film or a series ([movie]): their dates' names differ. */
    fun parameters(movie: Boolean): List<Pair<String, String>> = listOfNotNull(
        sortBy?.let { "sortBy" to it },
        genre?.let { "genre" to it },
        keywords?.let { "keywords" to it },
        language?.let { "language" to it },
        releasedFrom?.let { (if (movie) "primaryReleaseDateGte" else "firstAirDateGte") to it },
        releasedTo?.let { (if (movie) "primaryReleaseDateLte" else "firstAirDateLte") to it },
        watchProviders?.let { "watchProviders" to it },
        watchRegion?.let { "watchRegion" to it },
    )
}

/** An original language as TMDB lists them (`GET /languages`). */
@Serializable
data class Language(
    @SerialName("iso_639_1") val code: String = "",
    @SerialName("english_name") val englishName: String = "",
    val name: String = "",
)
