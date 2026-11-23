package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.RequestSeason
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.User
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/** The all-users view's rules (DECISIONS 94): Seerr's filters, who gets Approve and Decline, the status after one, focus after one. */
class ApprovalsTest {
    private val approver = User(id = 9, displayName = "Approver", permissions = Permission.MANAGE_REQUESTS)
    private val family = User(id = 8, displayName = "User 8", permissions = Permission.REQUEST)

    private fun film(id: Int, status: Int = RequestStatus.PENDING, media: Int = MediaStatus.PENDING, is4k: Boolean = false) =
        MediaRequest(id = id, status = status, type = "movie", is4k = is4k, media = MediaInfo(mediaType = "movie", tmdbId = id * 10, status = media, status4k = media), requestedBy = family)

    private fun series(id: Int, media: Int = MediaStatus.PENDING) =
        MediaRequest(id = id, status = RequestStatus.PENDING, type = "tv", media = MediaInfo(mediaType = "tv", tmdbId = id * 10, status = media),
            seasons = listOf(RequestSeason(1, 1), RequestSeason(2, 2)), requestedBy = family)

    @Test
    fun theFiltersAreSeerrsInItsOrderAndWords() {
        assertEquals(
            listOf("All", "Pending", "Approved", "Completed", "Processing", "Failed", "Available", "Unavailable", "Deleted"),
            RequestFilter.entries.map { it.words },
        )
        assertEquals(listOf("all", "pending", "approved", "completed", "processing", "failed", "available", "unavailable", "deleted"), RequestFilter.entries.map { it.api })
    }

    @Test
    fun approveAndDeclineAreForAPendingRequestAndAnAccountThatMayDecide() {
        assertTrue(decisionsFor(film(1), approver))
        assertFalse(decisionsFor(film(1, status = RequestStatus.APPROVED), approver), "not pending")
        assertFalse(decisionsFor(film(1), family), "no Manage Requests")
        assertFalse(decisionsFor(film(1), null), "rights not read")
        assertTrue(decisionsFor(film(1), User(id = 1, permissions = Permission.ADMIN)), "ADMIN holds it")
    }

    @Test
    fun anApprovedFilmIsApprovedByTheApproverAndItsCopyProcessing() {
        val after = afterDecision(film(1), approved = true, by = approver)
        assertEquals(RequestStatus.APPROVED, after.status)
        assertEquals(9, after.modifiedBy?.id)
        assertEquals(MediaStatus.PROCESSING, after.media?.status)
        assertEquals("Requested", requestBadge(after)?.text)
    }

    @Test
    fun anApprovedCopyAlreadyAvailableStaysSo() {
        assertEquals(MediaStatus.PARTIALLY_AVAILABLE, afterDecision(series(2, media = MediaStatus.PARTIALLY_AVAILABLE), true, approver).media?.status)
        val fourK = afterDecision(film(3, is4k = true), true, approver)
        assertEquals(MediaStatus.PROCESSING, fourK.media?.status4k, "the 4K copy moves")
    }

    @Test
    fun aDeclinedFilmsCopyGoesBackToUnknownAndItReadsDeclined() {
        val after = afterDecision(film(1), approved = false, by = approver)
        assertEquals(RequestStatus.DECLINED, after.status)
        assertEquals(MediaStatus.UNKNOWN, after.media?.status)
        assertEquals("Declined", requestBadge(after)?.text)
        assertEquals(MediaStatus.DELETED, afterDecision(film(2, media = MediaStatus.DELETED), false, approver).media?.status, "a deleted copy stays deleted")
    }

    @Test
    fun aSeriesRequestIsDecidedWholeWithItsSeasons() {
        val approved = afterDecision(series(4), approved = true, by = approver)
        assertEquals(listOf(RequestStatus.APPROVED, RequestStatus.APPROVED), approved.seasons.map { it.status })
        assertEquals(listOf(1, 2), approved.seasons.map { it.seasonNumber })
        val declined = afterDecision(series(4), approved = false, by = approver)
        assertEquals(listOf(RequestStatus.DECLINED, RequestStatus.DECLINED), declined.seasons.map { it.status })
        assertEquals(MediaStatus.UNKNOWN, declined.media?.status)
        assertEquals(MediaStatus.PARTIALLY_AVAILABLE, afterDecision(series(5, MediaStatus.PARTIALLY_AVAILABLE), false, approver).media?.status, "a series partly in keeps its copy")
    }

    @Test
    fun aRequestNotPendingIsLeftAsItIs() {
        val approved = film(1, status = RequestStatus.APPROVED, media = MediaStatus.PROCESSING)
        assertEquals(approved, afterDecision(approved, approved = false, by = approver))
    }

    @Test
    fun aDecidedRequestLeavesPendingAndJoinsItsNewFilter() {
        val pending = film(1)
        assertTrue(inFilter(pending, RequestFilter.PENDING))
        assertTrue(inFilter(pending, RequestFilter.UNAVAILABLE))
        val approved = afterDecision(pending, true, approver)
        assertFalse(inFilter(approved, RequestFilter.PENDING))
        assertTrue(inFilter(approved, RequestFilter.APPROVED))
        assertTrue(inFilter(approved, RequestFilter.PROCESSING))
        val declined = afterDecision(pending, false, approver)
        assertFalse(inFilter(declined, RequestFilter.UNAVAILABLE))
        assertTrue(inFilter(declined, RequestFilter.ALL))
        assertFalse(inFilter(film(2, media = MediaStatus.BLOCKLISTED), RequestFilter.ALL), "every filter leaves out a blocklisted copy")
    }

    @Test
    fun afterADecisionFocusGoesToTheNextOneDownElseUp() {
        val rows = listOf(film(1), film(2, status = RequestStatus.APPROVED), film(3), film(4))
        assertEquals(3, nextToDecide(rows, decided = 1), "the next pending below, past a decided one")
        assertEquals(4, nextToDecide(rows, decided = 3))
        assertEquals(3, nextToDecide(rows, decided = 4), "the last: the nearest above")
        assertNull(nextToDecide(listOf(film(1), film(2, status = RequestStatus.DECLINED)), decided = 1), "none left")
    }
}
