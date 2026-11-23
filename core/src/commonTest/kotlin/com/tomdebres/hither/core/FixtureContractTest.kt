package com.tomdebres.hither.core

import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.RequestPage
import com.tomdebres.hither.core.model.TvDetails
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.RequestAction
import com.tomdebres.hither.core.rules.SeasonState
import com.tomdebres.hither.core.rules.mediaBadge
import com.tomdebres.hither.core.rules.requestBadge
import com.tomdebres.hither.core.rules.requestOptions
import com.tomdebres.hither.core.rules.requestableSeasons
import com.tomdebres.hither.core.rules.seasonState
import com.tomdebres.hither.core.rules.takenSeasons
import com.tomdebres.hither.core.rules.tvRequestBody
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * The models against what a real Seerr 3.3.0 sent (fixtures/README.md says how the recordings
 * were made and scrubbed). If a model stops matching the server's JSON, this is where it shows.
 */
class FixtureContractTest {
    private val settings: PublicSettings = Fixtures.read("settings-public")
    private val me: User = Fixtures.read("auth-me")

    @Test
    fun `public settings carry the switches the rules read`() {
        assertTrue(settings.initialized)
        assertEquals("Seerr", settings.applicationTitle)
        assertTrue(settings.localLogin)
        assertTrue(settings.movie4kEnabled)
        assertFalse(settings.series4kEnabled)
        assertTrue(settings.partialRequestsEnabled)
        assertFalse(settings.enableSpecialEpisodes)
        assertTrue(settings.cacheImages)
    }

    @Test
    fun `auth me is the user with their real permissions`() {
        assertEquals(8, me.id)
        assertEquals("User 8", me.displayName)
        val held = me.permissions
        assertEquals(Permission.REQUEST or Permission.AUTO_APPROVE or Permission.REQUEST_4K_MOVIE or Permission.AUTO_APPROVE_4K_MOVIE, held)
        assertTrue(Permission.hasAny(held, Permission.REQUEST))
        assertFalse(Permission.hasAny(held, Permission.MANAGE_REQUESTS, Permission.REQUEST_4K_TV))
    }

    @Test
    fun `the sign-in response parses and its permissions are not the account's`() {
        val fromSignIn: User = Fixtures.read("auth-local")
        assertEquals(me.id, fromSignIn.id)
        assertEquals(0, fromSignIn.permissions)
        assertFalse(Permission.hasAny(fromSignIn.permissions, Permission.REQUEST))
    }

    @Test
    fun `discover rows parse - films and series alike`() {
        for (name in listOf("discover-trending", "discover-movies", "discover-tv", "discover-movies-upcoming")) {
            val page: Page<MediaResult> = Fixtures.read(name)
            assertEquals(1, page.page, name)
            assertTrue(page.totalPages > 1, name)
            assertEquals(8, page.results.size, name)
            for (result in page.results) {
                assertNotNull(result.type, "$name ${result.id}")
                assertTrue(result.displayTitle.isNotBlank(), "$name ${result.id}")
                assertTrue(result.posterPath!!.startsWith("/"), "$name ${result.id}")
            }
        }
        val trending: Page<MediaResult> = Fixtures.read("discover-trending")
        assertEquals(setOf(MediaType.MOVIE, MediaType.TV), trending.results.map { it.type }.toSet())
        assertTrue(trending.results.all { it.year?.length == 4 })
    }

    @Test
    fun `library status rides on a result and maps to a badge`() {
        val movies: Page<MediaResult> = Fixtures.read("discover-movies")
        val inLibrary = movies.results.filter { it.mediaInfo != null }
        assertEquals(4, inLibrary.size)
        assertTrue(inLibrary.all { result -> result.mediaInfo.let { it?.status == MediaStatus.AVAILABLE && it.tmdbId == result.id } })
        assertEquals("Available", mediaBadge(inLibrary.first().mediaInfo!!.status)!!.text)
        assertNull(mediaBadge(movies.results.first { it.mediaInfo == null }.mediaInfo?.status))

        val series: Page<MediaResult> = Fixtures.read("discover-tv")
        assertEquals("Deleted", mediaBadge(series.results.first { it.mediaInfo?.status == MediaStatus.DELETED }.mediaInfo!!.status)!!.text)
    }

    @Test
    fun `search parses`() {
        val page: Page<MediaResult> = Fixtures.read("search")
        assertEquals(8, page.results.size)
        assertTrue(page.totalResults > page.results.size)
        assertTrue(page.results.all { it.type != null && it.displayTitle.isNotBlank() })
    }

    @Test
    fun `a film nobody asked for - this account may request it - in 4K too - approved at once`() {
        val film: MovieDetails = Fixtures.read("movie")
        assertTrue(film.title.isNotBlank())
        assertTrue(film.overview!!.isNotBlank())
        assertTrue(film.backdropPath!!.startsWith("/"))
        assertTrue(film.genres.isNotEmpty())
        assertEquals(5, film.credits.cast.size)
        assertNull(film.mediaInfo)

        val options = requestOptions(MediaType.MOVIE, settings, me, film.mediaInfo)
        assertEquals(RequestAction.REQUEST, options.standard)
        assertEquals(RequestAction.REQUEST, options.fourK)
        assertTrue(options.autoApproved)
        assertTrue(options.autoApproved4k)
    }

    @Test
    fun `a film in the library - only the 4K copy is left to ask for`() {
        val film: MovieDetails = Fixtures.read("movie-in-library")
        val media = film.mediaInfo!!
        assertEquals(MediaStatus.AVAILABLE, media.status)
        assertEquals(MediaStatus.UNKNOWN, media.status4k)
        assertEquals(1, media.requests.size)
        assertEquals(8, media.requests.single().requestedBy!!.id)
        assertTrue(media.mediaUrl!!.startsWith("https://"))

        val options = requestOptions(MediaType.MOVIE, settings, me, media)
        assertEquals(RequestAction.NONE, options.standard)
        assertEquals(RequestAction.REQUEST, options.fourK)
    }

    @Test
    fun `a series nobody asked for - six seasons on offer - specials not`() {
        val series: TvDetails = Fixtures.read("tv")
        assertTrue(series.name.isNotBlank())
        assertEquals(6, series.numberOfSeasons)
        assertTrue(series.seasons.any { it.seasonNumber == 0 })
        assertEquals(listOf(1, 2, 3, 4, 5, 6), requestableSeasons(series.seasons, settings.enableSpecialEpisodes))

        val options = requestOptions(MediaType.TV, settings, me, series.mediaInfo, series.seasons)
        assertEquals(RequestAction.REQUEST, options.standard)
        // 4K series are off on this server and the account has no 4K series permission
        assertEquals(RequestAction.NONE, options.fourK)
        assertTrue(options.seasonPicker)
        assertTrue(options.autoApproved)
    }

    @Test
    fun `a series partly in the library - Request More - and the taken season cannot be sent`() {
        val series: TvDetails = Fixtures.read("tv-in-library")
        val media = series.mediaInfo!!
        assertEquals(MediaStatus.PARTIALLY_AVAILABLE, media.status)
        assertEquals(setOf(1), takenSeasons(media, is4k = false))
        assertEquals(SeasonState.AVAILABLE, seasonState(1, media, is4k = false))
        assertEquals(SeasonState.NOT_REQUESTED, seasonState(2, media, is4k = false))

        val options = requestOptions(MediaType.TV, settings, me, media, series.seasons)
        assertEquals(RequestAction.REQUEST_MORE, options.standard)
        val body = tvRequestBody(series.id, false, setOf(0, 1, 2, 3), series.seasons, media, settings, options)
        assertEquals(listOf(2, 3), body!!.seasons)
        assertEquals(series.id, body.mediaId)
    }

    @Test
    fun `the requests list parses and every request has a badge`() {
        val page: RequestPage = Fixtures.read("request-list")
        assertEquals(3, page.pageInfo.results)
        assertEquals(3, page.results.size)
        for (request in page.results) {
            assertEquals(MediaType.MOVIE, MediaType.fromApi(request.type))
            assertEquals(me.id, request.requestedBy!!.id)
            assertTrue(request.media!!.tmdbId > 0)
            assertTrue(request.createdAt!!.endsWith("Z"))
            assertEquals("Available", requestBadge(request)!!.text)
        }
    }

    @Test
    fun `error bodies give up their message`() {
        assertEquals("cookie 'connect.sid' required", SeerrClient.serverMessage(Fixtures.text("error-401")))
        assertEquals("Access denied.", SeerrClient.serverMessage(Fixtures.text("error-403-sign-in")))
        assertNull(SeerrClient.serverMessage("<html>Sign in to continue</html>"))
        assertNull(SeerrClient.serverMessage(""))
    }
    @Test
    fun `a title's page carries what M6's media page reads - videos - keywords - providers - crew and facts`() {
        val movie: MovieDetails = Fixtures.read("movie")
        assertEquals(listOf("Featurette", "Clip"), movie.relatedVideos.map { it.type })
        assertTrue(movie.relatedVideos.all { it.site == "YouTube" && it.key.length == 11 })
        assertEquals(12377, movie.keywords.single { it.name == "zombie" }.id)
        assertEquals(listOf("AR"), movie.watchProviders.map { it.country })
        assertEquals(listOf("Apple TV Store", "Amazon Video"), movie.watchProviders.single().buy.map { it.name })
        assertEquals("Director", movie.credits.crew.first().job)
        assertEquals(75_000_000L, movie.budget)
        assertTrue(movie.revenue > 0, "revenue is past Int's range in some films")
        assertEquals("Resident Evil", movie.originalTitle)
        assertEquals(listOf("US", "DE", "FR"), movie.productionCountries.map { it.code })
        assertEquals("Constantin Film", movie.productionCompanies.first().name)
        val digital = movie.releases.results.flatMap { it.releaseDates }.filter { it.type == 4 }
        assertTrue(digital.all { it.releaseDate != null })

        val tv: TvDetails = Fixtures.read("tv")
        assertEquals("Trailer", tv.relatedVideos.single { it.key == "th9hxCGH_ms" }.type)
        assertEquals(listOf("Disney XD", "Disney Channel"), tv.networks.map { it.name }.take(2))
        assertEquals("Disney Plus", tv.watchProviders.single().flatrate.single().name)
        assertEquals("2026-10-06", tv.lastAirDate)
        assertTrue(tv.createdBy.isNotEmpty())
        assertEquals("US", settings.streamingRegion)
        assertEquals("", me.settings?.streamingRegion)
    }
}
