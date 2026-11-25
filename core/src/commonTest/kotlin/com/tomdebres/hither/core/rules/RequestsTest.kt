package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.RequestSeason
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.TvDetails
import kotlin.test.assertEquals
import com.tomdebres.hither.core.model.User
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.Test

/** My Requests' rows (2.10): what each row of the requests list shows and opens. */
class RequestsTest {
    private val base = "http://seerr.test"
    private val images = ImageSource(base, cacheImages = true, tmdbImages = ImageSource.TMDB_IMAGES)

    private fun listed(
        id: Int,
        type: String = "movie",
        tmdbId: Int = id * 10,
        status: Int = RequestStatus.APPROVED,
        media: Int = MediaStatus.PROCESSING,
        media4k: Int = MediaStatus.UNKNOWN,
        is4k: Boolean = false,
        seasons: List<Int> = emptyList(),
        createdAt: String? = "2026-11-10T09:00:00.000Z",
    ) = MediaRequest(
        id = id,
        status = status,
        type = type,
        is4k = is4k,
        createdAt = createdAt,
        media = MediaInfo(mediaType = type, tmdbId = tmdbId, status = media, status4k = media4k),
        seasons = seasons.map { RequestSeason(seasonNumber = it) },
    )

    @Test
    fun `a row is the request's own - its chip - copy - seasons and date - in the server's order`() {
        val rows = requestRows(
            listOf(
                listed(3, type = "tv", tmdbId = 77, status = RequestStatus.PENDING, media = MediaStatus.PENDING, seasons = listOf(2, 1)),
                listed(2, is4k = true, media = MediaStatus.AVAILABLE, media4k = MediaStatus.PROCESSING),
                listed(1, status = RequestStatus.DECLINED, media = MediaStatus.UNKNOWN, createdAt = null),
            ),
        )
        assertEquals(listOf("request:3", "request:2", "request:1"), rows.map { it.key })
        val series = rows[0]
        assertEquals(MediaType.TV to 77, series.type to series.tmdbId)
        assertEquals("tv:77", series.titleKey)
        assertEquals("Pending", series.badge!!.text)
        assertEquals(listOf("1", "2"), series.seasons)
        assertEquals("2026-11-10T09:00:00.000Z", series.requestedAt)
        // the 4K request speaks for the 4K copy, not the regular one in the library
        val fourK = rows[1]
        assertEquals("4K Requested", fourK.badge!!.text)
        assertEquals(true, fourK.is4k)
        assertEquals(emptyList<String>(), fourK.seasons)
        // declined is the request's own word, whatever the title's record says
        assertEquals("Declined" to BadgeKind.DANGER, rows[2].badge!!.let { it.text to it.kind })
        assertNull(rows[2].requestedAt)
    }

    @Test
    fun `a row says who moved the request on and when - as Seerr's Modified line - only where somebody has`() {
        val moved = MediaRequest(
            id = 5, status = RequestStatus.APPROVED, type = "movie", updatedAt = "2026-11-12T09:00:00.000Z",
            media = MediaInfo(mediaType = "movie", tmdbId = 7), modifiedBy = User(id = 9, displayName = "Approver"),
        )
        val untouched = moved.copy(id = 6, status = RequestStatus.PENDING, modifiedBy = null)
        val byEmail = moved.copy(id = 7, modifiedBy = User(id = 10, displayName = "", email = "approver@example.invalid"))
        val rows = requestRows(listOf(moved, untouched, byEmail))
        assertEquals(listOf("Approver", null, "approver@example.invalid"), rows.map { it.modifiedBy })
        assertEquals(listOf("2026-11-12T09:00:00.000Z", null, "2026-11-12T09:00:00.000Z"), rows.map { it.modifiedAt })
    }

    @Test
    fun `a request with no title to open has no row`() {
        val rows = requestRows(
            listOf(
                listed(1),
                listed(2).copy(type = "music", media = MediaInfo(mediaType = "music", tmdbId = 5)),
                listed(3).copy(media = null),
                listed(4, tmdbId = 0),
            ),
        )
        assertEquals(listOf("request:1"), rows.map { it.key })
        // the media type may come only on the record
        assertEquals(MediaType.TV, requestRows(listOf(listed(5, type = "tv").copy(type = null))).single().type)
    }

    @Test
    fun `a further page appends what is new - and a request the list moved onto it is shown once`() {
        val first = requestRows(listOf(listed(9), listed(8)))
        val more = appendRequests(first, listOf(listed(8), listed(7)))
        assertEquals(listOf("request:9", "request:8", "request:7"), more.map { it.key })
        assertSame(first, appendRequests(first, listOf(listed(9))))
    }

    @Test
    fun `seasons in order - Specials for season 0 - six and then how many more`() {
        assertEquals(listOf("Specials", "1", "3"), seasonLabels(listOf(3, 0, 1, 1)))
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), seasonLabels((6 downTo 1).toList()))
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "+3"), seasonLabels((1..9).toList()))
    }

    @Test
    fun `the title's name - year and pictures come from its details - through the image proxy`() {
        val film = requestTitle(MovieDetails(id = 1, title = "Coco", releaseDate = "2017-10-27", posterPath = "/p.jpg", backdropPath = "/b.jpg"), images)
        assertEquals(RequestTitle("Coco", "2017", "$base/imageproxy/tmdb/t/p/w300_and_h450_face/p.jpg", "$base/imageproxy/tmdb/t/p/w1920_and_h800_multi_faces/b.jpg"), film)
        val series = requestTitle(TvDetails(id = 2, name = "Bluey", firstAirDate = "", posterPath = "../x.jpg"), images)
        assertEquals(RequestTitle("Bluey", null, null, null), series)
    }

    @Test
    fun `a row opens its title's page with the card Discover would draw`() {
        val row = requestRows(listOf(listed(4, type = "tv", tmdbId = 77, is4k = true, media = MediaStatus.AVAILABLE, media4k = MediaStatus.PENDING))).single()
        val card = requestCard(row, RequestTitle("Bluey", "2018", "poster", "backdrop"))
        assertEquals(TitleCard("tv:77", 77, MediaType.TV, "Bluey", "2018", "poster", cardBadge(row.media)), card)
        // the card's badge is the regular copy's, as on Discover, though the request was for 4K
        assertEquals("Available", card.badge!!.text)
        // before the title has come, the page reads its own
        assertEquals("", requestCard(row, null).title)
    }
}
