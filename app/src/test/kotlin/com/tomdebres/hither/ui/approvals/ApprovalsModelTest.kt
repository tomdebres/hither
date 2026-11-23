package com.tomdebres.hither.ui.approvals

import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.PageInfo
import com.tomdebres.hither.core.model.RequestPage
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.RequestFilter
import com.tomdebres.hither.core.rules.RequestTitle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Everyone's requests as the TV's admin, as its model drives them (DECISIONS 94). */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalsModelTest {
    private val family = User(id = 8, displayName = "User 8")
    private fun film(id: Int, status: Int = RequestStatus.PENDING) =
        MediaRequest(id = id, status = status, type = "movie", media = MediaInfo(mediaType = "movie", tmdbId = id * 10, status = MediaStatus.PENDING), requestedBy = family)

    private inner class Source : ApprovalsSource {
        var me = User(id = 9, displayName = "Approver", permissions = Permission.MANAGE_REQUESTS)
        var requests = mutableListOf(film(1), film(2), film(3, RequestStatus.APPROVED))
        val asked = mutableListOf<RequestFilter>()
        val skips = mutableListOf<Int>()
        val decided = mutableListOf<Pair<Int, Boolean>>()
        var gate: CompletableDeferred<Unit>? = null
        var failure: Exception? = null
        override suspend fun me() = me
        var pageGate: CompletableDeferred<Unit>? = null
        var firstPageGate: CompletableDeferred<Unit>? = null
        var allReads = 0
        var failSecondAllRead = false
        var pagesFail = false
        override suspend fun page(filter: RequestFilter, skip: Int, take: Int): RequestPage {
            asked += filter
            skips += skip
            if (skip > 0) pageGate?.await()
            if (filter == RequestFilter.ALL && ++allReads == 2 && failSecondAllRead) throw java.io.IOException("no answer")
            if (skip == 0 && filter == RequestFilter.ALL) firstPageGate?.await()
            if (pagesFail) throw java.io.IOException("no answer")
            val chosen = requests.filter { filter == RequestFilter.ALL || (filter == RequestFilter.PENDING && it.status == RequestStatus.PENDING) }
            return RequestPage(PageInfo(results = chosen.size), chosen.drop(skip).take(take))
        }
        override suspend fun title(type: MediaType, tmdbId: Int) = RequestTitle("Film $tmdbId", "2026", null, null)
        override suspend fun decide(requestId: Int, approve: Boolean): MediaRequest {
            decided += requestId to approve
            gate?.await()
            failure?.let { throw it }
            return requests.single { it.id == requestId }
        }
    }

    private val source = Source()
    private val outcomes = mutableListOf<Decided>()
    private fun TestScope.model() =
        ApprovalsModel(source, backgroundScope, onDecided = { outcomes += it }, work = UnconfinedTestDispatcher(testScheduler)).also { it.open() }

    /**
     * A9 (the review of A6's merged code): a later page that fails keeps the rows, and Try Again at the list's end asks
     * for that page, once, not the list from its top; moving along does not ask again (DECISIONS 42, 65). Before, Try
     * Again was never shown and nothing asked again: the list stayed short until the filter changed.
     */
    @Test
    fun tryAgainAfterAFailedLaterPageAsksForThatPageOnceAndKeepsTheRows() = runTest(UnconfinedTestDispatcher()) {
        source.requests = (1..12).map { film(it) }.toMutableList()
        val model = model()
        assertEquals(10, model.rows.size)
        source.pagesFail = true
        model.focused(6)
        assertEquals(listOf(0, 10), source.skips)
        assertEquals("the rows stay", 10, model.rows.size)
        // moving along does not ask again: Try Again does, for the page after the rows, once however often pressed
        model.focused(9)
        assertEquals(listOf(0, 10), source.skips)
        source.pagesFail = false
        source.pageGate = CompletableDeferred()
        model.retry()
        model.retry()
        assertEquals("Try Again asks for the page after the rows", listOf(0, 10, 10), source.skips)
        assertEquals("the rows stay while it is out", 10, model.rows.size)
        source.pageGate!!.complete(Unit)
        assertEquals((1..12).toList(), model.rows.map { it.id })
    }

    @Test
    fun itOpensOnPendingWithTheRightsReadLive() = runTest(UnconfinedTestDispatcher()) {
        val model = model()
        assertEquals(RequestFilter.PENDING, model.filter)
        assertEquals(listOf(RequestFilter.PENDING), source.asked)
        assertEquals(listOf(1, 2), model.rows.map { it.id })
        assertTrue(model.mayDecide)
        assertTrue(model.decisions(model.rows[0]))
    }

    @Test
    fun withoutManageRequestsThereAreNoDecisions() = runTest(UnconfinedTestDispatcher()) {
        source.me = User(id = 9, permissions = Permission.REQUEST)
        val model = model()
        assertFalse(model.mayDecide)
        assertFalse(model.decisions(model.rows[0]))
        model.decide(1, approve = true)
        assertTrue("nothing sent", source.decided.isEmpty())
    }

    @Test
    fun aFilterChosenReadsTheListUnderIt() = runTest(UnconfinedTestDispatcher()) {
        val model = model()
        model.choose(RequestFilter.ALL)
        assertEquals(listOf(RequestFilter.PENDING, RequestFilter.ALL), source.asked)
        assertEquals(listOf(1, 2, 3), model.rows.map { it.id })
    }

    @Test
    fun oneDecisionAtATimeAndFocusMovesBeforeTheRowChanges() = runTest(UnconfinedTestDispatcher()) {
        source.gate = CompletableDeferred()
        val model = model()
        model.decide(1, approve = true)
        model.decide(1, approve = true)
        model.decide(2, approve = false)
        assertEquals("one press, one decision", listOf(1 to true), source.decided)
        assertEquals(1 to true, model.deciding)
        source.gate!!.complete(Unit)
        assertEquals("focus goes to the next one first", FocusAfter.Row(2), model.focusAfter)
        assertEquals("the row has not changed yet", RequestStatus.PENDING, model.requests.single { it.id == 1 }.status)
        model.focusMoved()
        val decided = model.requests.single { it.id == 1 }
        assertEquals(RequestStatus.APPROVED, decided.status)
        assertEquals(9, decided.modifiedBy?.id)
        assertEquals("the row reads its new status at once", "Requested", model.rows.single { it.id == 1 }.badge?.text)
        assertFalse(model.decisions(model.rows.single { it.id == 1 }))
        assertNull(model.deciding)
        assertEquals(listOf<Decided>(Decided.Done("Film 10", approved = true)), outcomes)
    }

    @Test
    fun theLastOneSendsFocusToTheFilters() = runTest(UnconfinedTestDispatcher()) {
        source.requests = mutableListOf(film(1))
        val model = model()
        model.decide(1, approve = false)
        assertEquals(FocusAfter.Filters, model.focusAfter)
        model.focusMoved()
        assertEquals("Declined", model.rows.single().badge?.text)
    }

    @Test
    fun aRequestSomeoneElseDecidedIsSaidAndTheListReadAgain() = runTest(UnconfinedTestDispatcher()) {
        val model = model()
        source.failure = SeerrException.NotPending("Only pending requests can be approved or declined.")
        source.requests[0] = film(1, RequestStatus.APPROVED)
        model.decide(1, approve = true)
        assertEquals(listOf<Decided>(Decided.NotPending("Film 10")), outcomes)
        assertEquals("read again", listOf(2), model.rows.map { it.id })
        assertNull(model.focusAfter)
        assertEquals("the queue's dialog says so (A5)", DecisionProblem(Decided.NotPending("Film 10"), requestId = 1, approve = true), model.problem)
    }

    @Test
    fun aReReadThatFailsWhileAPageIsOutLeavesNothingLoading() = runTest(UnconfinedTestDispatcher()) {
        // twelve pending: two pages
        source.requests = (1..12).map { film(it) }.toMutableList()
        val model = model()
        assertEquals(10, model.rows.size)
        source.pageGate = CompletableDeferred()
        model.focused(9)
        assertTrue("the next page is out", model.loading)
        source.failure = SeerrException.NotPending("Only pending requests can be approved or declined.")
        source.pagesFail = true
        model.decide(1, approve = true)
        source.pageGate!!.complete(Unit)
        assertFalse("the superseded page and the failed re-read leave nothing loading", model.loading)
        source.pagesFail = false
        source.pageGate = null
        model.focused(9)
        assertEquals("paging goes on", 12, model.rows.size)
    }

    @Test
    fun aNotPendingAnswerAfterANewFilterLeavesThatFiltersFirstPageAlone() = runTest(UnconfinedTestDispatcher()) {
        source.gate = CompletableDeferred()
        val model = model()
        model.decide(1, approve = true)
        source.firstPageGate = CompletableDeferred()
        // a re-read now would fail: only All's own first read may answer
        source.failSecondAllRead = true
        model.choose(RequestFilter.ALL)
        source.failure = SeerrException.NotPending("Only pending requests can be approved or declined.")
        source.gate!!.complete(Unit)
        source.firstPageGate!!.complete(Unit)
        assertEquals("All's first page shown", listOf(1, 2, 3), model.rows.map { it.id })
        assertFalse(model.loading)
    }

    @Test
    fun rightsLostOnTheServerTakeTheButtonsAway() = runTest(UnconfinedTestDispatcher()) {
        val model = model()
        source.failure = SeerrException.Refused("You do not have permission to access this endpoint")
        source.me = User(id = 9, permissions = Permission.REQUEST)
        model.decide(1, approve = true)
        assertEquals(listOf<Decided>(Decided.NoRight), outcomes)
        assertEquals(DecisionProblem(Decided.NoRight, requestId = 1, approve = true), model.problem)
        assertFalse(model.mayDecide)
        assertEquals(RequestStatus.PENDING, model.requests.single { it.id == 1 }.status)
    }

    @Test
    fun anyOtherFailureIsSeerrsWordsAndChangesNothing() = runTest(UnconfinedTestDispatcher()) {
        val model = model()
        source.failure = SeerrException.Http(500, "boom")
        model.decide(1, approve = true)
        assertEquals(listOf<Decided>(Decided.Failed), outcomes)
        assertNull(model.deciding)
        assertEquals(RequestStatus.PENDING, model.requests.single { it.id == 1 }.status)
        // the queue's dialog, until it has closed (A5; tvOS's problemSeen)
        assertEquals(DecisionProblem(Decided.Failed, requestId = 1, approve = true), model.problem)
        model.problemSeen()
        assertNull(model.problem)
        source.failure = null
        model.decide(1, approve = true)
        assertEquals("pressable again", 2, source.decided.size)
        assertNull("a decision that went through is no problem", model.problem)
    }
}
