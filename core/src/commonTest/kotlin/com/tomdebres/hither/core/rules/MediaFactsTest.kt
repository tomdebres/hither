package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.model.Company
import com.tomdebres.hither.core.model.CountryReleases
import com.tomdebres.hither.core.model.CrewMember
import com.tomdebres.hither.core.model.Episode
import com.tomdebres.hither.core.model.ImdbRating
import com.tomdebres.hither.core.model.Keyword
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.Ratings
import com.tomdebres.hither.core.model.ReleaseDate
import com.tomdebres.hither.core.model.Releases
import com.tomdebres.hither.core.model.RtRating
import com.tomdebres.hither.core.model.TvDetails
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.model.UserSettings
import com.tomdebres.hither.core.model.Video
import com.tomdebres.hither.core.model.WatchProvider
import com.tomdebres.hither.core.model.WatchProviders
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

/** M6's media page rules (DECISIONS 98), each against Seerr's web at 3f7d5c3. */
class MediaFactsTest {
    private val base = "http://seerr.test:5055"
    private val images = ImageSource(base, cacheImages = true, tmdbImages = ImageSource.TMDB_IMAGES)

    private fun video(type: String, size: Int, key: String = "abcdefghijk", site: String = "YouTube") =
        Video(site = site, key = key, name = type, size = size, type = type)

    @Test
    fun `the trailer is the largest YouTube Trailer - and only a real YouTube id`() {
        assertEquals("BBBBBBBBBBB", trailerKey(listOf(video("Trailer", 480, "AAAAAAAAAAA"), video("Trailer", 1080, "BBBBBBBBBBB"), video("Teaser", 2160, "CCCCCCCCCCC"))))
        assertNull(trailerKey(listOf(video("Teaser", 1080))), "a teaser is not a trailer")
        assertNull(trailerKey(listOf(video("Trailer", 1080, site = "Vimeo"))), "Vimeo is not handed to YouTube")
        assertNull(trailerKey(listOf(video("Trailer", 1080, "../../x?y=1"))), "an id that is not one")
        assertNull(trailerKey(emptyList()))
        assertEquals("https://www.youtube.com/watch?v=a-B_c1234Z9", youtubeWatchLink("a-B_c1234Z9"))
        assertNull(youtubeWatchLink("a b"))
    }

    @Test
    fun `crew is Seerr's priority jobs in its order - six at most - ties as TMDB lists them`() {
        val crew = listOf(
            CrewMember(1, "Pat", "Producer"), CrewMember(2, "Dee", "Director"), CrewMember(3, "Grip", "Key Grip"),
            CrewMember(4, "Wren", "Writer"), CrewMember(5, "Sam", "Screenplay"), CrewMember(6, "Ed", "Editor"),
            CrewMember(7, "Cal", "Composer"), CrewMember(8, "Eve", "Executive Producer"), CrewMember(9, "Pia", "Producer"),
        )
        assertEquals(
            listOf("Director" to "Dee", "Screenplay" to "Sam", "Writer" to "Wren", "Composer" to "Cal", "Editor" to "Ed", "Producer" to "Pat"),
            crewLines(crew).map { it.job to it.name },
        )
        assertEquals(2, crewLines(crew).first().personId)
        assertEquals(emptyList<CrewLine>(), crewLines(listOf(CrewMember(3, "Grip", "Key Grip"))))
    }

    private val film = MovieDetails(
        id = 1, title = "Le Film", originalTitle = "Le Film Original", originalLanguage = "fr", status = "Released",
        releaseDate = "2026-01-01", revenue = 3_000_000_000, budget = 0,
        productionCompanies = listOf(Company(1, "A"), Company(2, "B")),
        releases = Releases(
            listOf(
                CountryReleases("NL", listOf(ReleaseDate(type = 3, releaseDate = "2026-02-01T00:00:00.000Z"))),
                CountryReleases(
                    "US",
                    listOf(
                        ReleaseDate(type = 1, releaseDate = "2025-12-01T00:00:00.000Z"),
                        ReleaseDate(type = 3, releaseDate = "2026-01-01T00:00:00.000Z"),
                        ReleaseDate(type = 3, releaseDate = "2026-01-08T00:00:00.000Z"),
                        ReleaseDate(type = 4, releaseDate = "2026-03-01T00:00:00.000Z"),
                        ReleaseDate(type = 6, releaseDate = "2026-06-01T00:00:00.000Z"),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `a film's facts are Seerr's panel in order - its releases by type for the region`() {
        assertEquals(
            listOf(
                Fact("Original Title", "Le Film Original"),
                Fact("Status", "Released"),
                Fact("Theatrical Release", "2026-01-01", FactKind.DATE),
                Fact("Digital Release", "2026-03-01", FactKind.DATE),
                Fact("Revenue", "3000000000", FactKind.MONEY),
                Fact("Original Language", "fr", FactKind.LANGUAGE),
                Fact("Studios", "A, B"),
            ),
            movieFacts(film, PublicSettings()),
        )
        // another region's releases; none there for the type range: the release date
        assertEquals(listOf("Theatrical Release"), movieFacts(film, PublicSettings(discoverRegion = "NL")).filter { it.kind == FactKind.DATE }.map { it.name })
        assertEquals(listOf(Fact("Release Date", "2026-01-01", FactKind.DATE)), movieFacts(film, PublicSettings(discoverRegion = "DE")).filter { it.kind == FactKind.DATE })
        assertNull(movieFacts(film.copy(originalLanguage = "en"), PublicSettings()).firstOrNull { it.name == "Original Title" }, "an English film's original title is its title")
    }

    @Test
    fun `a series' facts are Seerr's panel in order`() {
        val series = TvDetails(
            id = 2, name = "Show", originalName = "Show", originalLanguage = "ja", status = "Ended", firstAirDate = "2020-01-01",
            nextEpisodeToAir = Episode("2026-12-01"), episodeRunTime = listOf(24), networks = listOf(Company(1, "N")),
            keywords = listOf(Keyword(210024, "anime")),
        )
        assertEquals(
            listOf("Original Title", "Series Type", "Status", "First Air Date", "Next Air Date", "Episode Runtime", "Original Language", "Network"),
            tvFacts(series).map { it.name },
        )
        assertEquals("24 minutes", tvFacts(series).single { it.name == "Episode Runtime" }.value)
        assertNull(tvFacts(series.copy(nextEpisodeToAir = Episode("2020-01-01"))).firstOrNull { it.name == "Next Air Date" }, "no next air date the same as the first")
    }

    @Test
    fun `the ratings line is Seerr's - tomato or splat - popcorn or spilled - IMDb - TMDB`() {
        val ratings = Ratings(
            rt = RtRating(criticsRating = "Rotten", criticsScore = 36, audienceRating = "Upright", audienceScore = 91),
            imdb = ImdbRating(criticsScore = 7.2),
        )
        assertEquals(
            listOf(RatingScore(RatingKind.RT_ROTTEN, "36%"), RatingScore(RatingKind.RT_UPRIGHT, "91%"), RatingScore(RatingKind.IMDB, "7.2"), RatingScore(RatingKind.TMDB, "75%")),
            ratingScores(ratings, 7.493, 1958),
        )
        assertEquals(
            listOf(RatingKind.RT_FRESH, RatingKind.RT_SPILLED),
            ratingScores(Ratings(rt = RtRating(criticsRating = "Certified Fresh", criticsScore = 80, audienceRating = "Spilled", audienceScore = 40)), 0.0, 0).map { it.kind },
        )
        assertEquals(emptyList<RatingScore>(), ratingScores(null, 0.0, 0), "nobody voted: no TMDB score")
        assertEquals(emptyList<RatingScore>(), ratingScores(Ratings(rt = RtRating(audienceRating = "Upright", audienceScore = 0)), 0.0, 0), "an audience score of 0 is none (Seerr's !!)")
        assertEquals(listOf(RatingScore(RatingKind.IMDB, "8")), ratingScores(Ratings(imdb = ImdbRating(criticsScore = 8.0)), 0.0, 0), "a whole score as Seerr writes it")
    }

    @Test
    fun `streaming services are the account's region's - else the server's - else the US - flat rate only`() {
        val providers = listOf(
            WatchProviders("US", flatrate = listOf(WatchProvider(8, "Netflix", "/n.png"))),
            WatchProviders("NL", flatrate = listOf(WatchProvider(337, "Disney Plus", "/d.png")), buy = listOf(WatchProvider(2, "Apple TV Store"))),
        )
        val user = User(id = 8)
        assertEquals(listOf("Netflix"), streamingOn(providers, PublicSettings(), user, images).map { it.name })
        assertEquals(listOf("Disney Plus"), streamingOn(providers, PublicSettings(streamingRegion = "NL"), user, images).map { it.name })
        assertEquals(listOf("Netflix"), streamingOn(providers, PublicSettings(streamingRegion = "NL"), user.copy(settings = UserSettings(streamingRegion = "US")), images).map { it.name })
        assertEquals("$base/imageproxy/tmdb/t/p/w92/n.png", streamingOn(providers, PublicSettings(), user, images).single().logo)
        assertEquals(emptyList<StreamingService>(), streamingOn(providers, PublicSettings(streamingRegion = "GB"), user, images))
    }
}
