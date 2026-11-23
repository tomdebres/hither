package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Fixtures
import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.RequestBody
import com.tomdebres.hither.core.model.RequestSeason
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.TvDetails
import com.tomdebres.hither.core.model.User
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/** The request modal's view, the body it sends, and the record after a request (2.9). */
class RequestFlowTest {
    private val base = "http://seerr.test:5055"
    private val recorded = Fixtures.read<PublicSettings>("settings-public")
    private val tv = User(id = 8, permissions = 67744)
    private val waits = User(id = 8, permissions = Permission.REQUEST + Permission.REQUEST_4K_MOVIE)

    private val film = moviePage(Fixtures.read<MovieDetails>("movie"), base, recorded, tv)
    private val series = tvPage(Fixtures.read<TvDetails>("tv"), base, recorded, tv)

    /** The recorded series with season 1 in the library and a completed request for it. */
    private val partly = tvPage(Fixtures.read<TvDetails>("tv-in-library"), base, recorded, tv)

    // ---- the modal ---------------------------------------------------------------------------

    @Test
    fun `a film's modal - its title - the notice for this account - Request`() {
        val view = requestModal(film, is4k = false, selected = emptySet(), sending = false)
        assertEquals("Request Movie", view.title)
        assertEquals("Resident Evil", view.subtitle)
        assertTrue(view.approvedAutomatically)
        assertEquals(emptyList<SeasonRow>(), view.rows)
        assertEquals("Request", view.primary)
        assertTrue(view.primaryEnabled)
        val fourK = requestModal(film, is4k = true, selected = emptySet(), sending = false)
        assertEquals("Request Movie in 4K", fourK.title)
        assertEquals("Request in 4K", fourK.primary)
    }

    @Test
    fun `the notice shows only where auth-me says the request is approved automatically`() {
        val pending = moviePage(Fixtures.read<MovieDetails>("movie"), base, recorded, waits)
        assertFalse(requestModal(pending, is4k = false, selected = emptySet(), sending = false).approvedAutomatically)
        assertFalse(requestModal(pending, is4k = true, selected = emptySet(), sending = false).approvedAutomatically)
        assertTrue(requestModal(film, is4k = true, selected = emptySet(), sending = false).approvedAutomatically)
    }

    @Test
    fun `a series' table - seasons 1 to 6 without the specials - each with its badge - Select Seasons until one is picked`() {
        val view = requestModal(series, is4k = false, selected = emptySet(), sending = false)
        assertEquals("Request Series", view.title)
        assertEquals((1..6).toList(), view.rows.map { it.seasonNumber })
        assertEquals(listOf("Season 1", "Season 2"), view.rows.take(2).map { it.name })
        assertEquals(47, view.rows.first().episodes)
        assertTrue(view.rows.all { it.badge.text == "Not Requested" && !it.taken })
        assertTrue(view.picker)
        assertEquals("Select Season(s)", view.primary)
        assertFalse(view.primaryEnabled)
        assertEquals("Request 1 Season", requestModal(series, false, setOf(3), false).primary)
        val two = requestModal(series, false, setOf(2, 5), false)
        assertEquals("Request 2 Seasons", two.primary)
        assertTrue(two.primaryEnabled)
        assertEquals(listOf(2, 5), two.picked)
    }

    @Test
    fun `a season the library has is shown taken with its status - and never counts as picked`() {
        val view = requestModal(partly, is4k = false, selected = setOf(1, 2), sending = false)
        val one = view.rows.first()
        assertEquals(1, one.seasonNumber)
        assertTrue(one.taken)
        assertEquals("Available", one.badge.text)
        assertTrue(view.rows.drop(1).none { it.taken })
        assertEquals(listOf(2), view.picked)
        assertEquals("Request 1 Season", view.primary)
    }

    @Test
    fun `with specials on - season 0 is offered as Specials`() {
        val withSpecials = tvPage(Fixtures.read<TvDetails>("tv"), base, recorded.copy(enableSpecialEpisodes = true), tv)
        val first = requestModal(withSpecials, false, emptySet(), false).rows.first()
        assertEquals(0, first.seasonNumber)
        assertEquals("Specials", first.name)
    }

    @Test
    fun `with partial requests off the table is to read - and Request asks for every open season`() {
        val whole = tvPage(Fixtures.read<TvDetails>("tv-in-library"), base, recorded.copy(partialRequestsEnabled = false), tv)
        val view = requestModal(whole, is4k = false, selected = emptySet(), sending = false)
        assertFalse(view.picker)
        assertEquals("Request", view.primary)
        assertTrue(view.primaryEnabled)
        assertEquals((2..6).toList(), view.picked)
        assertEquals(RequestBody("tv", 1877, false, (2..6).toList()), requestBody(whole, false, emptySet()))
    }

    @Test
    fun `every season taken - Already Requested - and nothing to send`() {
        val all = MediaInfo(
            tmdbId = 1877,
            status = MediaStatus.PROCESSING,
            requests = listOf(MediaRequest(status = RequestStatus.PENDING, seasons = (1..6).map { RequestSeason(seasonNumber = it) }, requestedBy = User(id = 1))),
        )
        val page = series.withMedia(all)
        val view = requestModal(page, is4k = false, selected = setOf(1), sending = false)
        assertEquals("Already Requested", view.primary)
        assertFalse(view.primaryEnabled)
        assertTrue(view.rows.all { it.taken && it.badge.text == "Pending" })
        assertNull(requestBody(page, false, setOf(1)))
    }

    @Test
    fun `while a request is out the primary reads Requesting and cannot be pressed`() {
        val view = requestModal(series, is4k = false, selected = setOf(1), sending = true)
        assertEquals("Requesting…", view.primary)
        assertFalse(view.primaryEnabled)
        assertFalse(requestModal(film, is4k = false, selected = emptySet(), sending = true).primaryEnabled)
    }

    // ---- the body ----------------------------------------------------------------------------

    @Test
    fun `the body is what was picked - through the rules that build every body`() {
        assertEquals(RequestBody("movie", 1423191, is4k = false), requestBody(film, false, emptySet()))
        assertEquals(RequestBody("movie", 1423191, is4k = true), requestBody(film, true, emptySet()))
        assertEquals(RequestBody("tv", 1877, false, listOf(2, 5)), requestBody(series, false, setOf(5, 2)))
        // a taken season is dropped, an empty pick sends nothing, 4K where series 4K is off sends nothing
        assertEquals(RequestBody("tv", 1877, false, listOf(2)), requestBody(partly, false, setOf(1, 2)))
        assertNull(requestBody(series, false, emptySet()))
        assertNull(requestBody(series, true, setOf(1)))
    }

    // ---- after a request ---------------------------------------------------------------------

    private fun created(status: Int, is4k: Boolean = false, seasons: List<Int> = emptyList()) = MediaRequest(
        id = 1001,
        status = status,
        is4k = is4k,
        seasons = seasons.map { RequestSeason(seasonNumber = it, status = status) },
        requestedBy = User(id = 8),
        // what Seerr's 201 carries: the record as saved, before the approval moved it on
        media = MediaInfo(id = 55, tmdbId = 1423191, status = MediaStatus.PENDING),
    )

    @Test
    fun `an approved film request reads Requested at once - a pending one Pending`() {
        val approved = afterRequest(null, created(RequestStatus.APPROVED), MediaType.MOVIE, 1423191)
        assertEquals(MediaStatus.PROCESSING, approved.status)
        assertEquals(MediaStatus.UNKNOWN, approved.status4k)
        assertEquals(1423191, approved.tmdbId)
        assertEquals("Requested", film.withMedia(approved).badges.single().text)
        assertEquals("Requested", film.withMedia(approved).cardBadge?.text)
        assertEquals(listOf("Request in 4K"), film.withMedia(approved).buttons.map { it.text })

        val pending = afterRequest(null, created(RequestStatus.PENDING), MediaType.MOVIE, 1423191)
        assertEquals(MediaStatus.PENDING, pending.status)
        assertEquals("Pending", film.withMedia(pending).cardBadge?.text)
    }

    @Test
    fun `a 4K request moves the 4K copy and leaves the regular one`() {
        val record = afterRequest(MediaInfo(tmdbId = 1423191, status = MediaStatus.AVAILABLE), created(RequestStatus.APPROVED, is4k = true), MediaType.MOVIE, 1423191)
        assertEquals(MediaStatus.AVAILABLE, record.status)
        assertEquals(MediaStatus.PROCESSING, record.status4k)
        assertEquals(listOf("Available", "4K Requested"), film.withMedia(record).badges.map { it.text })
        assertEquals(emptyList<RequestButton>(), film.withMedia(record).buttons)
    }

    @Test
    fun `a series partly in the library stays Partially Available - and the seasons asked for are taken`() {
        val record = afterRequest(partly.media, created(RequestStatus.APPROVED, seasons = listOf(2, 3)), MediaType.TV, 1877)
        assertEquals(MediaStatus.PARTIALLY_AVAILABLE, record.status)
        assertEquals(setOf(1, 2, 3), takenSeasons(record, is4k = false))
        val after = partly.withMedia(record)
        val rows = requestModal(after, false, emptySet(), false).rows
        assertEquals(listOf("Available", "Requested", "Requested", "Not Requested"), rows.take(4).map { it.badge.text })
        assertEquals(listOf("Request More"), after.buttons.map { it.text })
    }

    @Test
    fun `a pending series request reads Pending - its seasons Pending in the table`() {
        val record = afterRequest(null, created(RequestStatus.PENDING, seasons = listOf(1)), MediaType.TV, 1877)
        assertEquals(MediaStatus.PENDING, record.status)
        val after = series.withMedia(record)
        assertEquals("Pending", after.cardBadge?.text)
        assertEquals("Pending", requestModal(after, false, emptySet(), false).rows.first().badge.text)
    }

    @Test
    fun `a deleted copy asked for again is pending or requested once more`() {
        val record = afterRequest(MediaInfo(tmdbId = 1423191, status = MediaStatus.DELETED), created(RequestStatus.PENDING), MediaType.MOVIE, 1423191)
        assertEquals(MediaStatus.PENDING, record.status)
    }

    // ---- what went wrong ---------------------------------------------------------------------

    @Test
    fun `each answer has its own reading`() {
        assertEquals(RequestFailure.NOTHING_TO_REQUEST, requestFailure(SeerrException.NothingToRequest("No seasons available to request")))
        assertEquals(RequestFailure.ALREADY_REQUESTED, requestFailure(SeerrException.AlreadyRequested("Request for this media already exists.")))
        assertEquals(RequestFailure.REFUSED, requestFailure(SeerrException.Refused("You do not have permission to make 4K movie requests.")))
        // the server's CSRF check over http has its own words: the request was not made (DECISIONS 70)
        assertEquals(RequestFailure.CSRF_NEEDS_HTTPS, requestFailure(SeerrException.CsrfRefused(overHttp = true)))
        assertEquals(RequestFailure.REFUSED, requestFailure(SeerrException.CsrfRefused(overHttp = false)))
        assertEquals(RequestFailure.UNREACHABLE, requestFailure(SeerrException.Unreachable(RuntimeException("timeout"))))
        assertEquals(RequestFailure.SIGNED_OUT, requestFailure(SeerrException.SignedOut(null)))
        assertEquals(RequestFailure.OTHER, requestFailure(SeerrException.Http(500, "boom")))
        assertEquals(RequestFailure.OTHER, requestFailure(SeerrException.NotSeerr()))
    }
}
