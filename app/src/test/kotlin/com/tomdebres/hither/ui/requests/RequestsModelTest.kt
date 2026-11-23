package com.tomdebres.hither.ui.requests

import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.PageInfo
import com.tomdebres.hither.core.model.RequestPage
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.rules.RequestTitle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** My Requests on the JVM, against a scripted source whose every answer the test gives. */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestsModelTest {
    private class Script : RequestsSource {
        val pages = mutableListOf<Pair<Int, Int>>()
        val titlesAsked = mutableListOf<Int>()
        var titlesOut = 0
        var mostTitlesOut = 0
        private val pageAnswers = mutableMapOf<Pair<Int, Int>, CompletableDeferred<RequestPage>>()
        private val titleAnswers = mutableMapOf<Int, CompletableDeferred<RequestTitle>>()

        fun pageAt(skip: Int, take: Int = RequestsModel.TAKE) = pageAnswers.getOrPut(skip to take) { CompletableDeferred() }
        fun titleOf(tmdbId: Int) = titleAnswers.getOrPut(tmdbId) { CompletableDeferred() }

        override suspend fun page(skip: Int, take: Int): RequestPage {
            pages += skip to take
            try {
                return pageAt(skip, take).await()
            } finally {
                pageAnswers.remove(skip to take)
            }
        }

        override suspend fun title(type: MediaType, tmdbId: Int): RequestTitle {
            titlesAsked += tmdbId
            titlesOut++
            mostTitlesOut = maxOf(mostTitlesOut, titlesOut)
            try {
                return titleOf(tmdbId).await()
            } finally {
                titlesOut--
                titleAnswers.remove(tmdbId)
            }
        }
    }

    private val script = Script()

    /** A request for title [tmdbId] (id*100 unless given), approved, the title processing. */
    private fun request(id: Int, tmdbId: Int = id * 100, media: Int = MediaStatus.PROCESSING) = MediaRequest(
        id = id,
        status = RequestStatus.APPROVED,
        type = "movie",
        createdAt = "2026-11-10T09:00:00.000Z",
        media = MediaInfo(mediaType = "movie", tmdbId = tmdbId, status = media),
    )

    private fun page(requests: List<MediaRequest>, total: Int = requests.size) =
        RequestPage(PageInfo(results = total), requests)

    private fun TestScope.model() = RequestsModel(
        source = script,
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
        work = StandardTestDispatcher(testScheduler),
    )

    private fun Script.answerTitles() {
        for (id in titlesAsked.toList()) titleOf(id).complete(RequestTitle("Film $id", "2026", null, null))
    }

    @Test
    fun `the first page newest first, each title asked for once and a few at a time`() = runTest {
        val requests = model()
        assertEquals(RequestsBody.EMPTY, requests.body)
        requests.open()
        runCurrent()
        assertEquals(RequestsBody.LOADING, requests.body)
        assertEquals(listOf(0 to 10), script.pages)
        // two requests for one title (the regular copy and the 4K one): one call for its name
        val list = (10 downTo 4).map { request(it) } + request(3, tmdbId = 400)
        script.pageAt(0).complete(page(list))
        runCurrent()
        assertEquals(RequestsBody.ROWS, requests.body)
        assertEquals((10 downTo 3).map { "request:$it" }, requests.rows.map { it.key })
        assertEquals(RequestsModel.TITLES_AT_ONCE, script.mostTitlesOut)
        repeat(3) {
            script.answerTitles()
            runCurrent()
        }
        advanceUntilIdle()
        assertEquals((10 downTo 4).map { it * 100 }, script.titlesAsked)
        assertEquals("Film 400", requests.title(requests.rows.last())!!.title)
        // a page that filled the screen asks for no more until focus comes near its end
        assertEquals(1, script.pages.size)
    }

    @Test
    fun `the next page as focus nears the end, once at a time and never past the last`() = runTest {
        val requests = model()
        requests.open()
        runCurrent()
        script.pageAt(0).complete(page((30 downTo 21).map { request(it) }, total = 15))
        advanceUntilIdle()
        requests.focused(4)
        runCurrent()
        assertEquals(listOf(0 to 10), script.pages)
        requests.focused(5)
        requests.focused(6)
        runCurrent()
        assertEquals(listOf(0 to 10, 10 to 10), script.pages)
        assertTrue(requests.loadingMore)
        // the list moved meanwhile: request 21 comes again at the top of the next page
        script.pageAt(10).complete(page((21 downTo 16).map { request(it) }, total = 16))
        advanceUntilIdle()
        assertEquals((30 downTo 16).map { "request:$it" }, requests.rows.map { it.key })
        requests.focused(14)
        advanceUntilIdle()
        assertEquals(2, script.pages.size)
    }

    @Test
    fun `nothing requested yet is said, not shown as a failure`() = runTest {
        val requests = model()
        requests.open()
        runCurrent()
        script.pageAt(0).complete(page(emptyList()))
        advanceUntilIdle()
        assertEquals(RequestsBody.EMPTY, requests.body)
        assertFalse(requests.failed)
    }

    @Test
    fun `a first page that fails offers Try Again, which stays until its answer`() = runTest {
        val requests = model()
        requests.open()
        runCurrent()
        script.pageAt(0).completeExceptionally(SeerrException.Http(500, null))
        advanceUntilIdle()
        assertEquals(RequestsBody.FAILED, requests.body)
        requests.retry()
        requests.retry()
        runCurrent()
        assertEquals(RequestsBody.FAILED, requests.body)
        assertTrue(requests.retrying)
        assertEquals(2, script.pages.size)
        script.pageAt(0).complete(page(listOf(request(1))))
        advanceUntilIdle()
        assertEquals(RequestsBody.ROWS, requests.body)
        assertFalse(requests.retrying)
    }

    @Test
    fun `coming back reads the list again - a new request at the top, statuses in place, focus on the same row`() = runTest {
        val requests = model()
        requests.open()
        runCurrent()
        script.pageAt(0).complete(page((5 downTo 1).map { request(it) }))
        advanceUntilIdle()
        script.answerTitles()
        advanceUntilIdle()
        requests.focused(2)
        assertEquals("request:3", requests.rows[requests.focusedIndex].key)
        requests.open()
        runCurrent()
        // as far as it has been read, from the top
        assertEquals(0 to 10, script.pages.last())
        val fresh = listOf(request(6)) + (5 downTo 1).map { request(it, media = if (it == 3) MediaStatus.AVAILABLE else MediaStatus.PROCESSING) }
        script.pageAt(0).complete(page(fresh))
        advanceUntilIdle()
        assertEquals((6 downTo 1).map { "request:$it" }, requests.rows.map { it.key })
        assertEquals("request:3", requests.rows[requests.focusedIndex].key)
        assertEquals("Available", requests.rows[requests.focusedIndex].badge!!.text)
        // only the new title is asked for
        assertEquals(listOf(500, 400, 300, 200, 100, 600), script.titlesAsked)
    }

    @Test
    fun `a request made since becomes the top row with focus if focus was on the top row, not otherwise`() = runTest {
        val requests = model()
        requests.open()
        runCurrent()
        script.pageAt(0).complete(page((5 downTo 1).map { request(it) }))
        advanceUntilIdle()
        requests.focused(0)
        requests.open()
        runCurrent()
        script.pageAt(0).complete(page((6 downTo 1).map { request(it) }))
        advanceUntilIdle()
        assertEquals("request:6", requests.rows[requests.focusedIndex].key)
        assertEquals(1, requests.newTop)
        // the same again: nothing new above, nothing to move
        requests.open()
        runCurrent()
        script.pageAt(0).complete(page((6 downTo 1).map { request(it) }))
        advanceUntilIdle()
        assertEquals(1, requests.newTop)
        // the user moved down while the list was out: focus stays on their row
        requests.open()
        runCurrent()
        requests.focused(2)
        script.pageAt(0).complete(page((7 downTo 1).map { request(it) }))
        advanceUntilIdle()
        assertEquals("request:4", requests.rows[requests.focusedIndex].key)
        assertEquals(1, requests.newTop)
    }

    @Test
    fun `a refresh that fails keeps the rows, and an empty list is simply asked for again`() = runTest {
        val requests = model()
        requests.open()
        runCurrent()
        script.pageAt(0).complete(page(listOf(request(1))))
        advanceUntilIdle()
        requests.refresh()
        runCurrent()
        script.pageAt(0).completeExceptionally(SeerrException.Unreachable(RuntimeException()))
        advanceUntilIdle()
        assertEquals(listOf("request:1"), requests.rows.map { it.key })
        assertFalse(requests.failed)

        val empty = model()
        empty.refresh()
        runCurrent()
        // never opened: nothing to refresh
        assertEquals(2, script.pages.size)
        empty.open()
        runCurrent()
        script.pageAt(0).complete(page(emptyList()))
        advanceUntilIdle()
        empty.open()
        runCurrent()
        assertEquals(RequestsBody.LOADING, empty.body)
        script.pageAt(0).complete(page(listOf(request(2))))
        advanceUntilIdle()
        assertEquals(listOf("request:2"), empty.rows.map { it.key })
    }

    @Test
    fun `a later page that fails ends the list with Try Again, which asks for that page once`() = runTest {
        val requests = model()
        requests.open()
        runCurrent()
        script.pageAt(0).complete(page((20 downTo 11).map { request(it) }, total = 12))
        advanceUntilIdle()
        requests.focused(7)
        runCurrent()
        script.pageAt(10).completeExceptionally(SeerrException.Http(500, null))
        advanceUntilIdle()
        assertTrue(requests.moreFailed)
        // moving along does not retry it
        requests.focused(8)
        runCurrent()
        assertEquals(2, script.pages.size)
        requests.retry()
        requests.retry()
        runCurrent()
        assertEquals(3, script.pages.size)
        assertTrue(requests.moreFailed)
        assertTrue(requests.retryingMore)
        assertFalse(requests.loadingMore)
        script.pageAt(10).complete(page(listOf(request(10), request(9)), total = 12))
        advanceUntilIdle()
        assertFalse(requests.moreFailed)
        assertEquals(12, requests.rows.size)
    }

    @Test
    fun `Try Again at the end while a refresh is out does nothing, rather than sticking on Trying`() = runTest {
        val requests = model()
        requests.open()
        runCurrent()
        script.pageAt(0).complete(page((20 downTo 11).map { request(it) }, total = 12))
        advanceUntilIdle()
        requests.focused(7)
        runCurrent()
        script.pageAt(10).completeExceptionally(SeerrException.Http(500, null))
        advanceUntilIdle()
        requests.refresh()
        runCurrent()
        // a refresh is out: the press is not taken, and the row still offers Try Again
        requests.retry()
        runCurrent()
        assertFalse(requests.retryingMore)
        assertTrue(requests.moreFailed)
        script.pageAt(0, take = 10).complete(page((20 downTo 11).map { request(it) }, total = 12))
        advanceUntilIdle()
        // once it is back, Try Again asks for the page
        requests.retry()
        runCurrent()
        assertTrue(requests.retryingMore)
        assertEquals(10 to 10, script.pages.last())
    }

    @Test
    fun `a title whose details failed is asked for again at the next refresh`() = runTest {
        val requests = model()
        requests.open()
        runCurrent()
        script.pageAt(0).complete(page(listOf(request(1))))
        runCurrent()
        script.titleOf(100).completeExceptionally(SeerrException.Http(500, null))
        advanceUntilIdle()
        assertEquals(TitleState.Failed, requests.titles["movie:100"])
        requests.refresh()
        runCurrent()
        script.pageAt(0).complete(page(listOf(request(1))))
        runCurrent()
        script.titleOf(100).complete(RequestTitle("Film 100", null, null, null))
        advanceUntilIdle()
        assertEquals("Film 100", requests.title(requests.rows.single())!!.title)
        assertEquals(listOf(100, 100), script.titlesAsked)
    }
    // ---- M6: Cancel Request (DECISIONS 101) ----------------------------------------------------------

    /** A source that cancels when the test says so, counting each DELETE. */
    private class Cancels(private val inner: RequestsSource) : RequestsSource by inner {
        val asked = mutableListOf<Int>()
        val answers = ArrayDeque<CompletableDeferred<Unit>>()

        override suspend fun cancel(requestId: Int) {
            asked += requestId
            CompletableDeferred<Unit>().also { answers.addLast(it) }.await()
        }
    }

    @Test
    fun `one press is one cancel, a pending row only, and its row goes when told, focus keeping its row`() = runTest {
        val cancels = Cancels(script)
        val requests = RequestsModel(cancels, CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()), StandardTestDispatcher(testScheduler))
        requests.open()
        advanceUntilIdle()
        script.pageAt(0).complete(page(listOf(request(1).copy(status = RequestStatus.PENDING), request(2), request(3).copy(status = RequestStatus.PENDING))))
        advanceUntilIdle()
        val outcomes = mutableListOf<CancelOutcome>()
        requests.cancel(requests.rows[1]) { outcomes += it }
        advanceUntilIdle()
        assertEquals("an approved request is not cancelled", emptyList<Int>(), cancels.asked)

        requests.cancel(requests.rows[0]) { outcomes += it }
        requests.cancel(requests.rows[0]) { outcomes += it }
        requests.cancel(requests.rows[2]) { outcomes += it }
        advanceUntilIdle()
        assertEquals("one at a time, once", listOf(1), cancels.asked)
        assertEquals(1, requests.cancelling)
        cancels.answers.removeFirst().complete(Unit)
        advanceUntilIdle()
        assertEquals(null, requests.cancelling)
        assertEquals(listOf<CancelOutcome>(CancelOutcome.Cancelled(1, null)), outcomes)
        assertEquals("the row stays until the screen has moved focus", 3, requests.rows.size)
        requests.focused(1)
        requests.drop(1)
        assertEquals(listOf(2, 3), requests.rows.map { it.id })
        assertEquals("focus stays on its row", 0, requests.focusedIndex)
    }

    @Test
    fun `a request decided meanwhile is not cancelled, and the list is read again`() = runTest {
        val cancels = object : RequestsSource by script {
            override suspend fun cancel(requestId: Int) = throw SeerrException.Refused("You do not have permission to delete this request.")
        }
        val requests = RequestsModel(cancels, CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()), StandardTestDispatcher(testScheduler))
        requests.open()
        advanceUntilIdle()
        script.pageAt(0).complete(page(listOf(request(1).copy(status = RequestStatus.PENDING))))
        advanceUntilIdle()
        val asked = script.pages.size
        val outcomes = mutableListOf<CancelOutcome>()
        requests.cancel(requests.rows[0]) { outcomes += it }
        advanceUntilIdle()
        assertEquals(listOf<CancelOutcome>(CancelOutcome.NotPending(null)), outcomes)
        assertEquals("read again", asked + 1, script.pages.size)
        assertEquals(1, requests.rows.size)
    }
}
