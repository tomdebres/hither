package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.model.DiscoverQuery
import com.tomdebres.hither.core.model.Language
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.WatchProvider

// Seerr's Movies and Series pages (M6, DECISIONS 102; audit item 6): its sort, and the audit's short filter
// set (genres, release year, streaming services, original language), from `DiscoverMovies`, `DiscoverTv`
// and `FilterSlideover` at 3f7d5c3.

/** One of Seerr's sorts: TMDB's value and Seerr's words for it. */
data class SortOption(val value: String, val label: String)

/** Seerr's eight sorts for [type], in its order; the page opens on "Popularity Descending". */
fun sortOptions(type: MediaType): List<SortOption> {
    val date = if (type == MediaType.MOVIE) "release_date" to "Release Date" else "first_air_date" to "First Air Date"
    val title = if (type == MediaType.MOVIE) "original_title" else "original_name"
    return listOf(
        SortOption("popularity.asc", "Popularity Ascending"),
        SortOption("popularity.desc", "Popularity Descending"),
        SortOption("${date.first}.asc", "${date.second} Ascending"),
        SortOption("${date.first}.desc", "${date.second} Descending"),
        SortOption("vote_average.asc", "TMDB Rating Ascending"),
        SortOption("vote_average.desc", "TMDB Rating Descending"),
        SortOption("$title.asc", "Title (A-Z) Ascending"),
        SortOption("$title.desc", "Title (Z-A) Descending"),
    )
}

/** The sort a page opens on: Seerr's default. */
const val DEFAULT_SORT = "popularity.desc"

/**
 * A span of years to show titles from: Seerr's From and To dates, as decades a D-pad can pick in one move
 * (a date picker at ten feet is many presses). [from] and [to] are years, inclusive; null is open.
 */
enum class Years(val label: String, val from: Int?, val to: Int?) {
    TWENTY_TWENTIES("2020s", 2020, null),
    TWENTY_TENS("2010s", 2010, 2019),
    TWO_THOUSANDS("2000s", 2000, 2009),
    NINETIES("1990s", 1990, 1999),
    EIGHTIES("1980s", 1980, 1989),
    EARLIER("Before 1980", null, 1979),
}

/** What the filters are set to: the audit's short set. Empty is no filter. */
data class BrowseFilters(
    val genres: Set<Int> = emptySet(),
    val years: Years? = null,
    val providers: Set<Int> = emptySet(),
    val language: String? = null,
) {
    /** How many are set, for "Filters (n)". */
    val count: Int get() = listOf(genres.isNotEmpty(), years != null, providers.isNotEmpty(), language != null).count { it }
}

/** The Filters button counting what is set, "Filters (2)", as the Apple TV app says it (H1); null with none. */
fun activeFilters(filters: BrowseFilters): String? = when (val n = filters.count) {
    0 -> null
    else -> "Filters ($n)"
}

/**
 * The query for a page of [type] with [sort] and [filters] (Seerr's query parameters): genres joined with
 * commas (TMDB's "and", as Seerr's genre selector sends them), the decade as its first and last day,
 * streaming services joined with pipes ("any of", as Seerr's) in [region], the original language.
 */
fun browseQuery(type: MediaType, sort: String, filters: BrowseFilters, region: String, base: DiscoverQuery = DiscoverQuery()): DiscoverQuery {
    val valid = sortOptions(type).any { it.value == sort }
    val from = filters.years?.from?.let { "$it-01-01" }
    val to = filters.years?.to?.let { "$it-12-31" }
    // A page opened from a row's "See More" keeps the row's query (polish 2, DECISIONS 131): the filters narrow it.
    val genres = (base.genre?.split(',').orEmpty().map { it.trim() }.filter { it.isNotEmpty() } + filters.genres.sorted().map { it.toString() }).distinct()
    return DiscoverQuery(
        sortBy = if (valid) sort else DEFAULT_SORT,
        genre = genres.joinToString(",").ifEmpty { null },
        keywords = base.keywords,
        releasedFrom = listOfNotNull(base.releasedFrom, from).maxOrNull(),
        releasedTo = listOfNotNull(base.releasedTo, to).minOrNull(),
        watchProviders = filters.providers.sorted().joinToString("|").ifEmpty { null } ?: base.watchProviders,
        watchRegion = if (filters.providers.isNotEmpty()) region else base.watchRegion,
        language = filters.language ?: base.language,
    )
}

/** The region streaming services are listed for: the account's, else the server's, else the US (as "Streaming On"). */
fun browseRegion(userRegion: String?, serverRegion: String): String =
    userRegion?.takeIf { it.isNotBlank() } ?: serverRegion.takeIf { it.isNotBlank() } ?: "US"

/** A streaming service to pick, with its logo from where the server's pictures come from ([imageUrl]). */
data class ProviderTile(val id: Int, val name: String, val logo: String?)

/** Seerr's selector shows the first 24 by TMDB's display priority, the rest behind "Show more" (left out here). */
const val PROVIDER_LIMIT = 24

/** The services to pick from: Seerr's first [PROVIDER_LIMIT] in TMDB's display priority, each once. */
fun providerTiles(providers: List<WatchProvider>, images: ImageSource): List<ProviderTile> =
    providers.filter { it.id > 0 && it.name.isNotBlank() }
        .distinctBy { it.id }
        .sortedBy { it.displayPriority }
        .take(PROVIDER_LIMIT)
        .map { ProviderTile(it.id, it.name, imageUrl(images, it.logoPath, TmdbImage.LOGO)) }

/** The original languages to pick from: TMDB's, by English name (Seerr's selector sorts by name), each once. */
fun languageOptions(languages: List<Language>): List<Language> =
    languages.filter { it.code.isNotBlank() && it.englishName.isNotBlank() }
        .distinctBy { it.code }
        .sortedBy { it.englishName.lowercase() }
