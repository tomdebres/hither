package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Fixtures
import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.CastMember
import com.tomdebres.hither.core.model.Credits
import com.tomdebres.hither.core.model.DownloadItem
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.SeasonSummary
import com.tomdebres.hither.core.model.TvDetails
import com.tomdebres.hither.core.model.User
import kotlin.test.assertEquals
import com.tomdebres.hither.core.model.CollectionBrief
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/** The media page's content from the recorded details (2.8), and the rules under it. */
class MediaPageTest {
    private val base = "http://seerr.test:5055"
    private val images = ImageSource(base, cacheImages = true, tmdbImages = ImageSource.TMDB_IMAGES)
    private val recorded = Fixtures.read<PublicSettings>("settings-public")

    /** The recorded account: REQUEST, AUTO_APPROVE, REQUEST_4K_MOVIE, AUTO_APPROVE_4K_MOVIE. */
    private val tv = User(id = 8, permissions = 67744)

    private val film = Fixtures.read<MovieDetails>("movie")
    private val filmInLibrary = Fixtures.read<MovieDetails>("movie-in-library")
    private val series = Fixtures.read<TvDetails>("tv")
    private val seriesPartly = Fixtures.read<TvDetails>("tv-in-library")

    @Test
    fun `a film nobody has asked for - its facts - art and cast - Request and Request in 4K`() {
        val page = moviePage(film, images, recorded, tv)
        assertEquals(MediaType.MOVIE, page.type)
        assertEquals(1423191, page.tmdbId)
        assertEquals("Resident Evil", page.title)
        assertEquals("2026", page.year)
        assertEquals(listOf("94 minutes", "Horror, Science Fiction, Adventure"), page.attributes)
        assertEquals("A new era of evil.", page.tagline)
        assertTrue(page.overview!!.isNotBlank())
        assertEquals(
            listOf(
                Fact("Status", "Released"),
                Fact("Release Date", "2026-09-16", FactKind.DATE),
                Fact("Revenue", "400290973", FactKind.MONEY),
                Fact("Budget", "75000000", FactKind.MONEY),
                Fact("Original Language", "en", FactKind.LANGUAGE),
                Fact("Production Countries", "United States of America, Germany, France"),
                Fact("Studios", film.productionCompanies.joinToString(", ") { it.name }),
            ),
            page.facts,
        )
        assertEquals(listOf("Director" to "Zach Cregger", "Producer" to "Robert Kulzer", "Producer" to "Roy Lee", "Producer" to "Miri Yoon"), page.crew.map { it.job to it.name })
        assertNull(page.trailer, "the recorded film has a featurette and a clip, no trailer")
        assertEquals(listOf("outbreak", "survival", "decapitation", "zombie", "based on video game"), page.keywords.map { it.name })
        assertEquals(emptyList<StreamingService>(), page.streaming)
        assertEquals(1958, page.voteCount)
        assertEquals("$base/imageproxy/tmdb/t/p/w300_and_h450_face${film.posterPath}", page.poster)
        assertEquals("$base/imageproxy/tmdb/t/p/w1920_and_h800_multi_faces${film.backdropPath}", page.backdrop)
        assertEquals(listOf("Austin Abrams", "Paul Walter Hauser", "Kali Reis", "Zach Cherry", "Johnno Wilson"), page.cast.map { it.name })
        assertEquals("Bryan", page.cast.first().character)
        assertEquals("$base/imageproxy/tmdb/t/p/w600_and_h900_bestv2/nvbJNwyhIICUFcS4lhxj9mGvL5z.jpg", page.cast.first().photo)
        assertEquals(emptyList<StatusBadge>(), page.badges)
        assertNull(page.cardBadge)
        assertEquals(listOf("Request", "Request in 4K"), page.buttons.map { it.text })
        assertEquals(listOf(false, true), page.buttons.map { it.is4k })
        assertEquals(emptyList<SeasonSummary>(), page.seasons)
    }

    @Test
    fun `a film in the library - Available - and only Request in 4K left`() {
        val page = moviePage(filmInLibrary, images, recorded, tv)
        assertEquals(listOf("Available"), page.badges.map { it.text })
        assertEquals("Available", page.cardBadge?.text)
        assertEquals(listOf("Request in 4K"), page.buttons.map { it.text })
    }

    @Test
    fun `a series - its seasons counted without the specials - its rating for the region - Request`() {
        val page = tvPage(series, images, recorded, tv)
        assertEquals(MediaType.TV, page.type)
        assertEquals("Phineas and Ferb", page.title)
        assertEquals("2007", page.year)
        // seasons 1 to 6 have episodes; 0 is the specials
        assertEquals(listOf("6 Seasons", "Animation, Comedy, Family, Sci-Fi & Fantasy"), page.attributes)
        assertEquals("TV-G", page.certification)
        assertEquals(listOf("Status", "First Air Date", "Original Language", "Production Country", "Networks"), page.facts.map { it.name })
        assertEquals(Fact("First Air Date", "2007-08-17", FactKind.DATE), page.facts[1])
        assertEquals("th9hxCGH_ms", page.trailer)
        assertEquals(emptyList<StreamingService>(), page.streaming, "the recorded server streams for the US; the fixture keeps Argentina's")
        assertEquals(listOf("Disney Plus"), tvPage(series, images, recorded.copy(streamingRegion = "AR"), tv).streaming.map { it.name })
        assertEquals(series.seasons, page.seasons)
        // 4K is off for series on the recorded server
        assertEquals(listOf("Request"), page.buttons.map { it.text })
    }

    @Test
    fun `a series partly in the library - Partially Available and Request More`() {
        val page = tvPage(seriesPartly, images, recorded, tv)
        assertEquals(listOf("Partially Available"), page.badges.map { it.text })
        assertEquals(listOf("Request More"), page.buttons.map { it.text })
        assertEquals(RequestAction.REQUEST_MORE, page.buttons.single().action)
    }

    @Test
    fun `the certification is the server's Discover region's - the US where it names none`() {
        assertEquals("TP", tvPage(series, images, recorded.copy(discoverRegion = "FR"), tv).certification)
        assertNull(tvPage(series, images, recorded.copy(discoverRegion = "NL"), tv).certification)
        val rated = film.copy(
            releases = film.releases.copy(
                results = film.releases.results + Fixtures.read<MovieDetails>("movie").releases.results.first().copy(
                    country = "US",
                    releaseDates = listOf(
                        com.tomdebres.hither.core.model.ReleaseDate(certification = "", type = 1),
                        com.tomdebres.hither.core.model.ReleaseDate(certification = "R", type = 3),
                    ),
                ),
            ),
        )
        // the first release in the region that carries one
        assertEquals("R", moviePage(rated, images, recorded, tv).certification)
        // the recorded film has releases for two other countries only
        assertNull(moviePage(film, images, recorded, tv).certification)
    }

    @Test
    fun `the 4K badge shows where 4K is on for the type and the user may ask for it`() {
        val record = media(status = MediaStatus.AVAILABLE, status4k = MediaStatus.PROCESSING)
        val withRecord = film.copy(mediaInfo = record)
        assertEquals(listOf("Available", "4K Requested"), moviePage(withRecord, images, recorded, tv).badges.map { it.text })
        // no 4K permission: no 4K badge (Seerr's MovieDetails)
        assertEquals(listOf("Available"), moviePage(withRecord, images, recorded, user(Permission.REQUEST)).badges.map { it.text })
        // 4K off for films on the server
        assertEquals(listOf("Available"), moviePage(withRecord, images, recorded.copy(movie4kEnabled = false), tv).badges.map { it.text })
        // a series follows series4kEnabled and the series' 4K permission
        val partly = seriesPartly.copy(mediaInfo = record)
        assertEquals(listOf("Available"), tvPage(partly, images, recorded, tv).badges.map { it.text })
        val fourKSeries = user(Permission.REQUEST or Permission.REQUEST_4K_TV)
        assertEquals(listOf("Available", "4K Requested"), tvPage(partly, images, recorded.copy(series4kEnabled = true), fourKSeries).badges.map { it.text })
        // a 4K copy nobody has asked for has no badge
        assertEquals(listOf("Available"), moviePage(film.copy(mediaInfo = media(status = MediaStatus.AVAILABLE)), images, recorded, tv).badges.map { it.text })
    }

    @Test
    fun `a download in progress turns the badge into Processing - on the page and on the card`() {
        val downloading = film.copy(
            mediaInfo = media(status = MediaStatus.PROCESSING).copy(downloadStatus = listOf(DownloadItem(title = "x"))),
        )
        val page = moviePage(downloading, images, recorded, tv)
        assertEquals(listOf("Processing"), page.badges.map { it.text })
        assertTrue(page.badges.single().inProgress)
        assertEquals("Processing", page.cardBadge?.text)
    }

    @Test
    fun `one season reads 1 Season - and a series with none counted has no such attribute`() {
        val one = series.copy(seasons = listOf(SeasonSummary(seasonNumber = 0, episodeCount = 3), SeasonSummary(seasonNumber = 1, episodeCount = 8)))
        assertEquals("1 Season", tvPage(one, images, recorded, tv).attributes.first())
        val unaired = series.copy(seasons = listOf(SeasonSummary(seasonNumber = 1, episodeCount = 0)), genres = emptyList())
        assertEquals(emptyList<String>(), tvPage(unaired, images, recorded, tv).attributes)
    }

    @Test
    fun `blanks are left out - no runtime - tagline - overview - status or date`() {
        val bare = film.copy(runtime = 0, tagline = " ", overview = "", status = null, releaseDate = "", genres = emptyList())
        val page = moviePage(bare, images, recorded, tv)
        assertEquals(emptyList<String>(), page.attributes)
        assertNull(page.tagline)
        assertNull(page.overview)
        assertTrue(page.facts.none { it.name == "Status" || it.kind == FactKind.DATE })
        assertNull(page.year)
    }

    @Test
    fun `the cast stops at 20 - keys stay unique when one person plays two parts - odd photo paths draw none`() {
        val many = (1..30).map { CastMember(id = it, name = "Person $it", profilePath = "/p$it.jpg") }
        val twice = listOf(
            CastMember(id = 7, name = "Seven", character = "One", profilePath = "/../etc/passwd"),
            CastMember(id = 7, name = "Seven", character = "Two"),
        )
        val page = moviePage(film.copy(credits = Credits(cast = twice + many)), images, recorded, tv)
        assertEquals(CAST_LIMIT, page.cast.size)
        assertEquals(page.cast.size, page.cast.map { it.key }.toSet().size)
        assertNull(page.cast[0].photo)
        assertNull(page.cast[1].photo)
        assertEquals("$base/imageproxy/tmdb/t/p/w600_and_h900_bestv2/p1.jpg", page.cast[2].photo)
    }

    @Test
    fun `the buttons follow the options - nothing to ask for - no button`() {
        assertEquals(emptyList<RequestButton>(), requestButtons(RequestOptions.NONE))
        val more4k = RequestOptions(RequestAction.NONE, RequestAction.REQUEST_MORE, false, false, true)
        assertEquals(listOf("Request More in 4K"), requestButtons(more4k).map { it.text })
        // an account that may not request at all
        assertEquals(emptyList<RequestButton>(), moviePage(film, images, recorded, user(0)).buttons)
    }

    @Test
    fun `a film's collection is the card at the top of the facts column - with the collection's backdrop`() {
        assertNull(moviePage(film, images, recorded, tv).collection, "the recorded film belongs to none")
        val inOne = film.copy(collection = CollectionBrief(id = 8650, name = " Example Collection ", backdropPath = "/c.jpg"))
        assertEquals(
            CollectionCard(8650, "Example Collection", "$base/imageproxy/tmdb/t/p/w1440_and_h320_multi_faces/c.jpg"),
            moviePage(inOne, images, recorded, tv).collection,
        )
        assertNull(moviePage(film.copy(collection = CollectionBrief(id = 1)), images, recorded, tv).collection, "no name, no card")
        assertNull(tvPage(series, images, recorded, tv).collection, "a series has none")
    }

    @Test
    fun `a series lists its seasons newest first - each with its episodes and badge`() {
        val page = tvPage(seriesPartly, images, recorded, tv)
        val lines = page.seasonLines
        assertEquals(page.seasons.filter { it.seasonNumber != 0 && it.episodeCount > 0 }.map { it.seasonNumber }.reversed(), lines.map { it.number })
        val held = seriesPartly.mediaInfo!!.seasons.filter { it.status == com.tomdebres.hither.core.model.MediaStatus.AVAILABLE }.map { it.seasonNumber }
        for (n in held) assertEquals(listOf("Available"), lines.single { it.number == n }.badges.map { it.text }, "season $n")
        assertEquals(emptyList<SeasonLine>(), moviePage(film, images, recorded, tv).seasonLines)
    }

}
