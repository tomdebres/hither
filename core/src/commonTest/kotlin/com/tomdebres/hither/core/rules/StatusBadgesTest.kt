package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.model.DownloadItem
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.RequestStatus
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class StatusBadgesTest {
    @Test
    fun `each library status has Seerr's word and colour`() {
        val expected = mapOf(
            MediaStatus.AVAILABLE to ("Available" to BadgeKind.SUCCESS),
            MediaStatus.PARTIALLY_AVAILABLE to ("Partially Available" to BadgeKind.SUCCESS),
            MediaStatus.PROCESSING to ("Requested" to BadgeKind.PRIMARY),
            MediaStatus.PENDING to ("Pending" to BadgeKind.WARNING),
            MediaStatus.DELETED to ("Deleted" to BadgeKind.DANGER),
            MediaStatus.BLOCKLISTED to ("Blocklisted" to BadgeKind.DANGER),
        )
        for ((status, want) in expected) {
            val badge = mediaBadge(status)!!
            assertEquals(want, badge.text to badge.kind, "status=$status")
        }
    }

    @Test
    fun `unknown - absent and unheard-of statuses have no badge`() {
        assertNull(mediaBadge(MediaStatus.UNKNOWN))
        assertNull(mediaBadge(null))
        assertNull(mediaBadge(0))
        assertNull(mediaBadge(99))
    }

    @Test
    fun `the 4K copy says so`() {
        assertEquals("4K Available", mediaBadge(MediaStatus.AVAILABLE, is4k = true)!!.text)
        assertEquals("4K Requested", mediaBadge(MediaStatus.PROCESSING, is4k = true)!!.text)
        assertEquals("4K Pending", mediaBadge(MediaStatus.PENDING, is4k = true)!!.text)
    }

    @Test
    fun `a running download reads Processing in the status's own colour`() {
        val requested = mediaBadge(MediaStatus.PROCESSING, inProgress = true)!!
        assertEquals("Processing" to BadgeKind.PRIMARY, requested.text to requested.kind)
        val upgrading = mediaBadge(MediaStatus.AVAILABLE, inProgress = true)!!
        assertEquals("Processing" to BadgeKind.SUCCESS, upgrading.text to upgrading.kind)
        // a pending request has no download to speak of
        assertEquals("Pending", mediaBadge(MediaStatus.PENDING, inProgress = true)!!.text)
    }

    @Test
    fun `a request's badge - declined and failed are its own - the rest is the title's`() {
        fun badge(request: Int, media: Int, is4k: Boolean = false) =
            requestBadge(MediaRequest(status = request, is4k = is4k, media = MediaInfo(status = media, status4k = media)))

        assertEquals("Declined" to BadgeKind.DANGER, badge(RequestStatus.DECLINED, MediaStatus.UNKNOWN)!!.let { it.text to it.kind })
        assertEquals("Failed" to BadgeKind.DANGER, badge(RequestStatus.FAILED, MediaStatus.PROCESSING)!!.let { it.text to it.kind })
        assertEquals("Pending" to BadgeKind.WARNING, badge(RequestStatus.PENDING, MediaStatus.PENDING)!!.let { it.text to it.kind })
        assertEquals("Pending" to BadgeKind.WARNING, badge(RequestStatus.PENDING, MediaStatus.DELETED)!!.let { it.text to it.kind })
        assertEquals("Requested" to BadgeKind.PRIMARY, badge(RequestStatus.APPROVED, MediaStatus.PROCESSING)!!.let { it.text to it.kind })
        assertEquals("Available" to BadgeKind.SUCCESS, badge(RequestStatus.COMPLETED, MediaStatus.AVAILABLE)!!.let { it.text to it.kind })
        assertEquals("4K Declined", badge(RequestStatus.DECLINED, MediaStatus.UNKNOWN, is4k = true)!!.text)
    }

    @Test
    fun `a 4K request reads the 4K copy's status and downloads`() {
        val request = MediaRequest(
            status = RequestStatus.APPROVED,
            is4k = true,
            media = MediaInfo(
                status = MediaStatus.AVAILABLE,
                status4k = MediaStatus.PROCESSING,
                downloadStatus4k = listOf(DownloadItem(title = "x")),
            ),
        )
        val badge = requestBadge(request)!!
        assertEquals("4K Processing", badge.text)
        assertEquals(BadgeKind.PRIMARY, badge.kind)
        assertEquals(true, badge.inProgress)
        // the same record, asked about as a regular request, is simply available
        assertEquals("Available", requestBadge(request.copy(is4k = false))!!.text)
    }

    @Test
    fun `a series request shows its own stage until its seasons are in - not the series' status`() {
        // Tom's call (DECISIONS 80, the polish audit's item 24): season 2 just asked for of a series whose
        // season 1 is in read "Partially Available", which the family takes for "season 2 is there".
        fun series(request: Int, media: Int, is4k: Boolean = false, downloading: Boolean = false) = requestBadge(
            MediaRequest(
                status = request,
                is4k = is4k,
                type = "tv",
                media = MediaInfo(
                    mediaType = "tv",
                    status = media,
                    status4k = media,
                    downloadStatus = if (downloading && !is4k) listOf(DownloadItem(title = "x")) else emptyList(),
                ),
            ),
        )!!.let { it.text to it.kind }

        assertEquals("Requested" to BadgeKind.PRIMARY, series(RequestStatus.APPROVED, MediaStatus.PARTIALLY_AVAILABLE))
        assertEquals("Pending" to BadgeKind.WARNING, series(RequestStatus.PENDING, MediaStatus.PARTIALLY_AVAILABLE))
        assertEquals("Pending" to BadgeKind.WARNING, series(RequestStatus.PENDING, MediaStatus.AVAILABLE))
        assertEquals("4K Requested" to BadgeKind.PRIMARY, series(RequestStatus.APPROVED, MediaStatus.PARTIALLY_AVAILABLE, is4k = true))
        // a download in progress still says so
        assertEquals("Processing" to BadgeKind.PRIMARY, series(RequestStatus.APPROVED, MediaStatus.PARTIALLY_AVAILABLE, downloading = true))
        // its seasons in: Seerr completes the request (or approves it with the copy whole) and it is Available
        assertEquals("Available" to BadgeKind.SUCCESS, series(RequestStatus.COMPLETED, MediaStatus.PARTIALLY_AVAILABLE))
        assertEquals("Available" to BadgeKind.SUCCESS, series(RequestStatus.APPROVED, MediaStatus.AVAILABLE))
        // declined, failed and deleted are as before
        assertEquals("Declined" to BadgeKind.DANGER, series(RequestStatus.DECLINED, MediaStatus.PARTIALLY_AVAILABLE))
        assertEquals("Failed" to BadgeKind.DANGER, series(RequestStatus.FAILED, MediaStatus.PARTIALLY_AVAILABLE))
        assertEquals("Deleted" to BadgeKind.DANGER, series(RequestStatus.COMPLETED, MediaStatus.DELETED))
    }

    @Test
    fun `each season state has its badge`() {
        val expected = mapOf(
            SeasonState.NOT_REQUESTED to ("Not Requested" to BadgeKind.PRIMARY),
            SeasonState.PENDING to ("Pending" to BadgeKind.WARNING),
            SeasonState.REQUESTED to ("Requested" to BadgeKind.PRIMARY),
            SeasonState.PARTIALLY_AVAILABLE to ("Partially Available" to BadgeKind.SUCCESS),
            SeasonState.AVAILABLE to ("Available" to BadgeKind.SUCCESS),
        )
        for ((state, want) in expected) {
            val badge = seasonBadge(state)
            assertEquals(want, badge.text to badge.kind, "$state")
        }
    }
}
