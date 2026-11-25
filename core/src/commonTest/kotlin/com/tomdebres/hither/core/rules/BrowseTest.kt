package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.model.DiscoverQuery
import com.tomdebres.hither.core.model.Language
import com.tomdebres.hither.core.model.WatchProvider
import com.tomdebres.hither.core.model.MediaType
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

/** The Movies and Series pages' sort and filters (M6, DECISIONS 102), against Seerr at 3f7d5c3. */
class BrowseTest {
    @Test
    fun `Seerr's eight sorts in its order and words - a series by first air date and name`() {
        assertEquals(
            listOf(
                "popularity.asc", "popularity.desc", "release_date.asc", "release_date.desc",
                "vote_average.asc", "vote_average.desc", "original_title.asc", "original_title.desc",
            ),
            sortOptions(MediaType.MOVIE).map { it.value },
        )
        assertEquals("Title (Z-A) Descending", sortOptions(MediaType.MOVIE).last().label)
        assertEquals("first_air_date.desc", sortOptions(MediaType.TV)[3].value)
        assertEquals("First Air Date Descending", sortOptions(MediaType.TV)[3].label)
        assertEquals("original_name.asc", sortOptions(MediaType.TV)[6].value)
    }

    @Test
    fun `the filters become Seerr's query - and an unknown sort is the default`() {
        assertEquals(DiscoverQuery(sortBy = "popularity.desc"), browseQuery(MediaType.MOVIE, DEFAULT_SORT, BrowseFilters(), "US"))
        assertEquals(
            DiscoverQuery(
                sortBy = "vote_average.desc", genre = "18,27", releasedFrom = "2010-01-01", releasedTo = "2019-12-31",
                watchProviders = "8|337", watchRegion = "NL", language = "fr",
            ),
            browseQuery(MediaType.MOVIE, "vote_average.desc", BrowseFilters(setOf(27, 18), Years.TWENTY_TENS, setOf(337, 8), "fr"), "NL"),
        )
        assertEquals("popularity.desc", browseQuery(MediaType.TV, "release_date.desc", BrowseFilters(), "US").sortBy, "a film's sort is not a series'")
        val recent = browseQuery(MediaType.TV, DEFAULT_SORT, BrowseFilters(years = Years.TWENTY_TWENTIES), "US")
        assertEquals("2020-01-01" to null, recent.releasedFrom to recent.releasedTo)
        assertEquals(null to "1979-12-31", browseQuery(MediaType.TV, DEFAULT_SORT, BrowseFilters(years = Years.EARLIER), "US").let { it.releasedFrom to it.releasedTo })
        assertEquals(listOf("firstAirDateGte" to "2020-01-01"), recent.parameters(movie = false).filter { it.first.startsWith("firstAir") }, "the series' dates go by their own name")
    }

    @Test
    fun `Seerr's active filter words - and the region streaming services are listed for`() {
        assertNull(activeFilters(BrowseFilters()))
        assertEquals("Filters (1)", activeFilters(BrowseFilters(language = "nl")))
        assertEquals("Filters (3)", activeFilters(BrowseFilters(genres = setOf(1, 2), years = Years.EIGHTIES, providers = setOf(8))))
        assertEquals("NL", browseRegion("NL", "US"))
        assertEquals("US", browseRegion("", "US"))
        assertEquals("US", browseRegion(null, ""))
        assertEquals("GB", browseRegion(" ", "GB"))
    }

    @Test
    fun `the first 24 services by display priority with their logos - and the languages by English name`() {
        val providers = (1..30).map { WatchProvider(id = it, name = "Service $it", logoPath = "/l$it.png", displayPriority = 31 - it) } +
            listOf(WatchProvider(id = 0, name = "none"), WatchProvider(id = 30, name = "Service 30 again", displayPriority = 0))
        val tiles = providerTiles(providers, ImageSource("http://seerr.test", cacheImages = true, tmdbImages = ImageSource.TMDB_IMAGES))
        assertEquals(PROVIDER_LIMIT, tiles.size)
        assertEquals(listOf(30, 29, 28), tiles.take(3).map { it.id })
        assertEquals("Service 30", tiles.first().name)
        assertEquals("http://seerr.test/imageproxy/tmdb/t/p/w92/l30.png", tiles.first().logo)
        val languages = listOf(Language("nl", "Dutch", "Nederlands"), Language("fr", "French", "Français"), Language("xx", "", ""), Language("ab", "abkhazian", ""), Language("nl", "Dutch again", ""))
        assertEquals(listOf("ab", "nl", "fr"), languageOptions(languages).map { it.code })
    }
}
