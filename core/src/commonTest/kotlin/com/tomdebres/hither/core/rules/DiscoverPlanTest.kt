package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.DiscoverQuery
import com.tomdebres.hither.core.model.DiscoverSlider
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.SliderType
import com.tomdebres.hither.core.model.TvDetails
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/** Discover's rows from the server's Discover settings (M6, DECISIONS 99), against Seerr at 3f7d5c3. */
class DiscoverPlanTest {
    private val family = Permission.REQUEST
    private val recentView = Permission.REQUEST or Permission.RECENT_VIEW

    /** Seerr's defaultSliders on a new server: ids and types 1-12, in that order. */
    private val stock = (1..12).map { DiscoverSlider(id = it, type = it, order = it - 1, isBuiltIn = true, enabled = true) }

    private fun custom(id: Int, type: Int, title: String?, data: String?, order: Int) =
        DiscoverSlider(id = id, type = type, order = order, isBuiltIn = false, enabled = true, title = title, data = data)

    @Test
    fun `a stock server's rows are Seerr's built-ins in its order - genres - studios - networks and the watchlist left out`() {
        val plan = discoverPlan(stock, family)
        assertTrue(plan.recentRequests)
        assertEquals(
            listOf("TRENDING", "POPULAR_MOVIES", "UPCOMING_MOVIES", "POPULAR_SERIES", "UPCOMING_SERIES"),
            plan.rows.map { it.name },
        )
    }

    @Test
    fun `Recently Added and Recent Requests follow the server's order - Recently Added only for Recent View or Manage Requests`() {
        // Seerr's default: Recently Added (0), then Recent Requests (1)
        val stockPlan = discoverPlan(stock, recentView)
        assertEquals("RECENTLY_ADDED", stockPlan.rows.first().name)
        assertEquals(1, stockPlan.recentAt, "Recent Requests after Recently Added")
        assertEquals("RECENTLY_ADDED", discoverPlan(stock, Permission.MANAGE_REQUESTS).rows.first().name)
        assertEquals("RECENTLY_ADDED", discoverPlan(stock, Permission.ADMIN).rows.first().name)
        // without the right, no Recently Added: Recent Requests heads the page
        assertTrue(discoverPlan(stock, family).rows.none { it.name == "RECENTLY_ADDED" })
        assertEquals(0, discoverPlan(stock, family).recentAt)
        // wherever the server puts each, there it is (Tom's pick, polish 2: 99's exception gone)
        val last = stock.map { if (it.type == SliderType.RECENTLY_ADDED) it.copy(order = 99) else it }
        assertEquals("RECENTLY_ADDED", discoverPlan(last, recentView).rows.last().name)
        assertEquals(0, discoverPlan(last, recentView).recentAt)
        val requestsLater = stock.map { if (it.type == SliderType.RECENT_REQUESTS) it.copy(order = 5) else it }
        // order 5 comes after Trending (3) and Popular Movies (4): those two and Recently Added before it
        assertEquals(listOf("RECENTLY_ADDED", "TRENDING", "POPULAR_MOVIES"), discoverPlan(requestsLater, recentView).rows.take(3).map { it.name })
        assertEquals(3, discoverPlan(requestsLater, recentView).recentAt)
        val hidden = stock.map { if (it.type == SliderType.RECENTLY_ADDED) it.copy(enabled = false) else it }
        assertTrue(discoverPlan(hidden, recentView).rows.none { it.name == "RECENTLY_ADDED" })
        assertEquals(0, discoverPlan(hidden, recentView).recentAt)
    }

    @Test
    fun `the server's order and hidden rows are followed - Recent Requests included`() {
        val turned = stock.map {
            when (it.type) {
                SliderType.TRENDING -> it.copy(order = 50)
                SliderType.POPULAR_TV -> it.copy(enabled = false)
                SliderType.RECENT_REQUESTS -> it.copy(enabled = false)
                else -> it
            }
        }
        val plan = discoverPlan(turned, family)
        assertFalse(plan.recentRequests)
        assertEquals(listOf("POPULAR_MOVIES", "UPCOMING_MOVIES", "UPCOMING_SERIES", "TRENDING"), plan.rows.map { it.name })
    }

    @Test
    fun `custom sliders are rows under their own titles - each reading what Seerr's web reads`() {
        val sliders = stock + listOf(
            custom(13, SliderType.TMDB_MOVIE_KEYWORD, "Zombies", "12377", 20),
            custom(14, SliderType.TMDB_TV_KEYWORD, "Agents", "4289,212795", 21),
            custom(15, SliderType.TMDB_MOVIE_GENRE, "Horror", "27", 22),
            custom(16, SliderType.TMDB_TV_GENRE, "Animated", "16", 23),
            custom(17, SliderType.TMDB_SEARCH, "Star Wars", "star wars", 24),
            custom(18, SliderType.TMDB_STUDIO, "Constantin", "47", 25),
            custom(19, SliderType.TMDB_NETWORK, "Disney Channel", "54", 26),
            custom(20, SliderType.TMDB_MOVIE_STREAMING_SERVICES, "On Netflix", "US,8|337", 27),
            custom(21, SliderType.TMDB_TV_STREAMING_SERVICES, "Series on Disney", "NL,337", 28),
        )
        val rows = discoverPlan(sliders, family).rows.drop(5)
        assertEquals(
            listOf("Zombies", "Agents", "Horror", "Animated", "Star Wars", "Constantin", "Disney Channel", "On Netflix", "Series on Disney"),
            rows.map { it.title },
        )
        assertEquals(
            listOf(
                RowSource.Query(MediaType.MOVIE, DiscoverQuery(keywords = "12377")),
                RowSource.Query(MediaType.TV, DiscoverQuery(keywords = "4289,212795")),
                RowSource.Query(MediaType.MOVIE, DiscoverQuery(genre = "27")),
                RowSource.Query(MediaType.TV, DiscoverQuery(genre = "16")),
                RowSource.Search("star wars"),
                RowSource.Studio(47),
                RowSource.Network(54),
                RowSource.Query(MediaType.MOVIE, DiscoverQuery(watchRegion = "US", watchProviders = "8|337")),
                RowSource.Query(MediaType.TV, DiscoverQuery(watchRegion = "NL", watchProviders = "337")),
            ),
            rows.map { it.source },
        )
        assertEquals("CUSTOM_13", rows.first().name)
        assertEquals(rows.size, rows.map { it.ordinal }.toSet().size)
    }

    @Test
    fun `a custom slider Hither cannot read is left out`() {
        val sliders = stock + listOf(
            custom(13, SliderType.TMDB_MOVIE_KEYWORD, " ", "12377", 20),
            custom(14, SliderType.TMDB_STUDIO, "Studio", "not a number", 21),
            custom(15, SliderType.TMDB_MOVIE_STREAMING_SERVICES, "Services", "8", 22),
            custom(16, SliderType.TMDB_SEARCH, "Nothing", "", 23),
            custom(17, 99, "From a newer Seerr", "x", 24),
            custom(18, SliderType.TMDB_MOVIE_GENRE, "Hidden", "27", 25).copy(enabled = false),
        )
        assertEquals(5, discoverPlan(sliders, family).rows.size)
    }

    @Test
    fun `without the server's settings - Hither's own rows as before - Recently Added first for those who may see it`() {
        assertEquals(DiscoverRow.entries, discoverPlan(null, family).rows)
        assertTrue(discoverPlan(null, family).recentRequests)
        assertEquals(listOf(DiscoverRow.RECENTLY_ADDED) + DiscoverRow.entries, discoverPlan(null, recentView).rows)
        // Seerr's default order at the top: Recently Added, then Recent Requests
        assertEquals(1, discoverPlan(null, recentView).recentAt)
        assertEquals(0, discoverPlan(null, family).recentAt)
    }

    @Test
    fun `request cards name who asked only for View Requests or Manage Requests - as Seerr's RequestCard`() {
        assertTrue(discoverPlan(stock, family or Permission.REQUEST_VIEW).requesters)
        assertTrue(discoverPlan(stock, Permission.MANAGE_REQUESTS).requesters)
        assertTrue(discoverPlan(stock, Permission.ADMIN).requesters)
        assertTrue(discoverPlan(null, family or Permission.REQUEST_VIEW).requesters)
        // Recent View is Recently Added's right, not this one
        assertFalse(discoverPlan(stock, family).requesters)
        assertFalse(discoverPlan(stock, recentView).requesters)
        assertFalse(discoverPlan(null, null).requesters)
    }

    @Test
    fun `a Recently Added card is the record's status under the details' name and pictures`() {
        val record = MediaInfo(id = 3, mediaType = "movie", tmdbId = 7, status = 5)
        val film = recentlyAddedResult(record, MovieDetails(id = 7, title = "Film", posterPath = "/p.jpg", releaseDate = "2026-01-02", overview = "About."), null)!!
        assertEquals("Film", film.displayTitle)
        assertEquals("movie", film.mediaType)
        assertEquals(record, film.mediaInfo)
        assertEquals("Available", titleCard(film, "http://s")!!.badge?.text)
        assertEquals("About.", titleCard(film, "http://s")!!.overview)
        val series = recentlyAddedResult(record.copy(mediaType = "tv"), null, TvDetails(id = 7, name = "Show", firstAirDate = "2020-01-01"))!!
        assertEquals("tv:7", titleCard(series, "http://s")!!.key)
        assertNull(recentlyAddedResult(record, null, null))
    }
}

