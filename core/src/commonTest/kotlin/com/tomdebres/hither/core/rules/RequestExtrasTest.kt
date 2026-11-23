package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Fixtures
import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.DownloadEpisode
import com.tomdebres.hither.core.model.DownloadItem
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.Quota
import com.tomdebres.hither.core.model.QuotaStatus
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.TvDetails
import com.tomdebres.hither.core.model.User
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/** M6's request parts (DECISIONS 101): download progress, cancelling, the quota, against Seerr at 3f7d5c3. */
class RequestExtrasTest {
    private val base = "http://seerr.test:5055"
    private val recorded = Fixtures.read<PublicSettings>("settings-public")
    private val tv = User(id = 8, permissions = 67744)

    private fun item(size: Double, left: Double, eta: String? = "2026-11-12T19:00:00.000Z", episode: DownloadEpisode? = null, id: String? = "d1") =
        DownloadItem(title = "x", status = "downloading", size = size, sizeLeft = left, estimatedCompletionTime = eta, downloadId = id, episode = episode)

    @Test
    fun `progress is the first item's share done - its estimate - and a series' episode or season`() {
        assertEquals(DownloadProgress(42, "2026-11-12T19:00:00.000Z", null), downloadProgress(listOf(item(100.0, 58.0)), MediaType.MOVIE))
        assertEquals(43, downloadProgress(listOf(item(1000.0, 574.0)), MediaType.MOVIE)!!.percent, "rounded as Seerr's Math.round")
        assertEquals(null, downloadProgress(listOf(item(1.0, 0.5, episode = DownloadEpisode(1, 2))), MediaType.MOVIE)!!.episode, "a film has no episode")
        assertEquals("S1E2", downloadProgress(listOf(item(1.0, 0.5, episode = DownloadEpisode(1, 2))), MediaType.TV)!!.episode)
        // a season pack: several episodes, one download
        val pack = listOf(item(1.0, 0.5, episode = DownloadEpisode(1, 1)), item(1.0, 0.5, episode = DownloadEpisode(1, 2)))
        assertEquals("S1", downloadProgress(pack, MediaType.TV)!!.episode)
        assertEquals(0, downloadProgress(listOf(item(0.0, 0.0)), MediaType.MOVIE)!!.percent)
        assertNull(downloadProgress(listOf(item(1.0, 1.0, eta = " ")), MediaType.MOVIE)!!.estimatedAt)
        assertNull(downloadProgress(emptyList(), MediaType.MOVIE))
    }

    @Test
    fun `the media page's and My Requests' badges carry the progress - a card's mini badge does not`() {
        val media = MediaInfo(tmdbId = 7, status = MediaStatus.PROCESSING, downloadStatus = listOf(item(100.0, 25.0)))
        val page = moviePage(MovieDetails(id = 7, title = "Film", mediaInfo = media), base, recorded, tv)
        assertEquals(75, page.badges.single().progress?.percent)
        assertEquals("Processing", page.badges.single().text)
        assertNull(cardBadge(media)?.progress)
        val request = MediaRequest(id = 1, status = RequestStatus.APPROVED, type = "movie", media = media)
        assertEquals(75, requestBadge(request)?.progress?.percent)
        assertNull(requestBadge(request.copy(media = media.copy(downloadStatus = emptyList())))?.progress)
    }

    @Test
    fun `only a pending request may be cancelled - and its row then goes`() {
        val rows = requestRows(
            listOf(
                MediaRequest(id = 1, status = RequestStatus.PENDING, type = "movie", media = MediaInfo(tmdbId = 1)),
                MediaRequest(id = 2, status = RequestStatus.APPROVED, type = "movie", media = MediaInfo(tmdbId = 2)),
            ),
        )
        assertEquals(listOf(true, false), rows.map { canCancel(it) })
        assertEquals(listOf(2), withoutRequest(rows, 1).map { it.id })
    }

    @Test
    fun `the quota line is Seerr's words - and there is none without a limit or with Manage Users`() {
        val quota = Quota(movie = QuotaStatus(days = 7, limit = 3, used = 3, remaining = 0, restricted = true), tv = QuotaStatus(days = 1, limit = 1, used = 0, remaining = 1))
        assertEquals("No movie requests left", quotaLine(quota, MediaType.MOVIE, tv.permissions)!!.text)
        assertEquals("You may request 3 movies every 7 days.", quotaLine(quota, MediaType.MOVIE, tv.permissions)!!.allowance)
        assertEquals("1 season request left", quotaLine(quota, MediaType.TV, tv.permissions)!!.text)
        assertEquals("You may request 1 season every day.", quotaLine(quota, MediaType.TV, tv.permissions)!!.allowance)
        assertNull(quotaLine(Quota(), MediaType.MOVIE, tv.permissions))
        assertNull(quotaLine(null, MediaType.MOVIE, tv.permissions))
        assertNull(quotaLine(quota, MediaType.MOVIE, Permission.MANAGE_USERS))
        assertEquals(2, quotaLine(Quota(movie = QuotaStatus(limit = 3, used = 1)), MediaType.MOVIE, tv.permissions)!!.remaining, "used counts where remaining is missing")
    }

    @Test
    fun `past the quota the modal holds the request - a film at none left - a series past its seasons`() {
        val film = moviePage(Fixtures.read<MovieDetails>("movie"), base, recorded, tv)
        val none = film.copy(quota = Quota(movie = QuotaStatus(limit = 3, used = 3, remaining = 0, restricted = true)))
        val view = requestModal(none, is4k = false, selected = emptySet(), sending = false)
        assertEquals("No Movie Requests Remaining", view.primary)
        assertFalse(view.primaryEnabled)
        assertEquals("No movie requests left", view.quota?.text)
        assertTrue(requestModal(film.copy(quota = Quota(movie = QuotaStatus(limit = 3, used = 1, remaining = 2))), false, emptySet(), false).primaryEnabled)

        val series = tvPage(Fixtures.read<TvDetails>("tv"), base, recorded, tv).copy(quota = Quota(tv = QuotaStatus(limit = 2, used = 0, remaining = 2)))
        val two = requestModal(series, is4k = false, selected = setOf(1, 2), sending = false)
        assertEquals(listOf(1, 2), two.picked)
        assertTrue(two.primaryEnabled)
        assertEquals(listOf(3, 4, 5, 6), two.rows.filter { it.overQuota }.map { it.seasonNumber }, "the others are greyed")
        val one = requestModal(series, is4k = false, selected = setOf(1), sending = false)
        assertTrue(one.rows.none { it.overQuota }, "room for one more: nothing greyed")
        // with partial requests off the whole series is asked for, and six seasons are past two
        val whole = requestModal(series.copy(settings = recorded.copy(partialRequestsEnabled = false)), false, emptySet(), false)
        assertEquals("Not Enough Season Requests Remaining", whole.primary)
        assertFalse(whole.primaryEnabled)
    }

    @Test
    fun `the quota's ring is what is left out of the limit - in Seerr's heat colours`() {
        val line = { left: Int, limit: Int -> QuotaLine(left, limit, 7, seasons = false) }
        assertEquals(60, line(3, 5).percentLeft)
        assertEquals(QuotaHeat.PLENTY, line(3, 5).heat)
        assertEquals(QuotaHeat.HALF, line(1, 2).heat)
        assertEquals(QuotaHeat.LOW, line(1, 10).heat)
        assertEquals(QuotaHeat.HALF, line(2, 10).heat)
        assertEquals(QuotaHeat.EMPTY, line(0, 3).heat)
        assertEquals(0, line(0, 3).percentLeft)
        assertEquals(100, line(5, 5).percentLeft)
    }

}
