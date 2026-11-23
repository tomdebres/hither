package com.tomdebres.hither.ui.issues

import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.model.Issue
import com.tomdebres.hither.core.model.IssuePage
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.PageInfo
import com.tomdebres.hither.core.rules.IssueFilter
import com.tomdebres.hither.core.rules.RequestTitle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Seerr's Issues page on the JVM (M6, DECISIONS 106), against a scripted source. */
@OptIn(ExperimentalCoroutinesApi::class)
class IssuesModelTest {
    private class Script : IssuesSource {
        val asked = mutableListOf<Triple<IssueFilter, Int, Int>>()
        val answers = ArrayDeque<CompletableDeferred<IssuePage>>()
        val titlesAsked = mutableListOf<Int>()

        override suspend fun page(filter: IssueFilter, skip: Int, take: Int): IssuePage {
            asked += Triple(filter, skip, take)
            return CompletableDeferred<IssuePage>().also { answers.addLast(it) }.await()
        }

        override suspend fun title(type: MediaType, tmdbId: Int): RequestTitle {
            titlesAsked += tmdbId
            return RequestTitle("Film $tmdbId", "2026", null, null)
        }
    }

    private val script = Script()

    private fun issue(id: Int, tmdbId: Int = 100) = Issue(id = id, media = MediaInfo(mediaType = "movie", tmdbId = tmdbId))

    private fun page(issues: List<Issue>, total: Int = issues.size) = IssuePage(PageInfo(results = total), issues)

    private fun TestScope.model() = IssuesModel(script, CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()), StandardTestDispatcher(testScheduler))

    @Test
    fun `it opens on Open, reads each title once, and a filter reads from the top`() = runTest {
        val issues = model()
        issues.open()
        advanceUntilIdle()
        assertEquals(listOf(Triple(IssueFilter.OPEN, 0, 10)), script.asked)
        assertEquals(IssuesBody.LOADING, issues.body)
        script.answers.removeFirst().complete(page(listOf(issue(1), issue(2), issue(3, tmdbId = 200))))
        advanceUntilIdle()
        assertEquals(IssuesBody.ROWS, issues.body)
        assertEquals(listOf(100, 200), script.titlesAsked)
        issues.choose(IssueFilter.RESOLVED)
        advanceUntilIdle()
        assertEquals(Triple(IssueFilter.RESOLVED, 0, 10), script.asked.last())
        script.answers.removeFirst().complete(page(emptyList()))
        advanceUntilIdle()
        assertEquals(IssuesBody.EMPTY, issues.body)
    }

    /**
     * A9 (the review of A6's merged code): a later page that fails keeps the rows, and Try Again at the list's end asks
     * for that page, once, not the list from its top; moving along does not ask again (DECISIONS 42, 65). Before, Try
     * Again was never shown and nothing asked again: the list stayed short until the filter changed.
     */
    @Test
    fun `Try Again after a failed later page asks for that page once and keeps the rows`() = runTest {
        val issues = model()
        issues.open()
        advanceUntilIdle()
        script.answers.removeFirst().complete(page((1..10).map { issue(it) }, total = 12))
        advanceUntilIdle()
        issues.focused(6)
        advanceUntilIdle()
        assertEquals(Triple(IssueFilter.OPEN, 10, 10), script.asked.last())
        script.answers.removeFirst().completeExceptionally(RuntimeException("down"))
        advanceUntilIdle()
        assertEquals("the rows stay", 10, issues.rows.size)
        // moving along does not ask again: Try Again does, for the page after the rows, once however often pressed
        issues.focused(9)
        advanceUntilIdle()
        assertEquals(2, script.asked.size)
        issues.retry()
        issues.retry()
        advanceUntilIdle()
        assertEquals(
            "Try Again asks for the page after the rows",
            listOf(Triple(IssueFilter.OPEN, 0, 10), Triple(IssueFilter.OPEN, 10, 10), Triple(IssueFilter.OPEN, 10, 10)),
            script.asked,
        )
        assertEquals("the rows stay while it is out", 10, issues.rows.size)
        script.answers.removeFirst().complete(page((11..12).map { issue(it) }, total = 12))
        advanceUntilIdle()
        assertEquals(12, issues.rows.size)
        assertEquals(IssuesBody.ROWS, issues.body)
    }

    @Test
    fun `the next page as focus nears the end, and back from a title the list is kept`() = runTest {
        val issues = model()
        issues.open()
        advanceUntilIdle()
        script.answers.removeFirst().complete(page((1..10).map { issue(it) }, total = 12))
        advanceUntilIdle()
        issues.focused(3)
        advanceUntilIdle()
        assertEquals(1, script.asked.size)
        issues.focused(6)
        advanceUntilIdle()
        assertEquals(Triple(IssueFilter.OPEN, 10, 10), script.asked.last())
        script.answers.removeFirst().complete(page((11..12).map { issue(it) }, total = 12))
        advanceUntilIdle()
        assertEquals(12, issues.rows.size)
        issues.returnToRow = true
        issues.open()
        advanceUntilIdle()
        assertEquals("not read again", 2, script.asked.size)
        assertEquals(6, issues.focusedIndex)
    }

    @Test
    fun `a fresh arrival reads from the top and its entry is the first lockup, not the one left before`() = runTest {
        // A8 (DECISIONS 350): open() without a lockup to come back to read the list from the top but kept the index of
        // the lockup left the time before, so Right from the side row went to it (choose() already reset it).
        val issues = model()
        issues.open()
        advanceUntilIdle()
        script.answers.removeFirst().complete(page((1..10).map { issue(it) }))
        advanceUntilIdle()
        issues.focused(6)
        // another tab, and back: not to a lockup
        issues.returnToRow = false
        issues.open()
        advanceUntilIdle()
        assertEquals("read again from the top", Triple(IssueFilter.OPEN, 0, 10), script.asked.last())
        assertEquals("the grid's entry is the first lockup", 0, issues.focusedIndex)
    }

    @Test
    fun `a refusal says the right has gone, a failure offers Try Again`() = runTest {
        val issues = model()
        issues.open()
        advanceUntilIdle()
        // Seerr's own refusal of the right (its 403 body, the session alive): the right has gone
        script.answers.removeFirst().completeExceptionally(SeerrException.Refused("You do not have permission to access this endpoint"))
        advanceUntilIdle()
        assertEquals(IssuesBody.NO_RIGHT, issues.body)
        issues.retry()
        advanceUntilIdle()
        script.answers.removeFirst().completeExceptionally(RuntimeException("down"))
        advanceUntilIdle()
        assertEquals(IssuesBody.FAILED, issues.body)
        issues.retry()
        advanceUntilIdle()
        assertEquals(3, script.asked.size)
        script.answers.removeFirst().complete(page(listOf(issue(1))))
        advanceUntilIdle()
        assertEquals(IssuesBody.ROWS, issues.body)
    }

    @Test
    fun `a refusal that is not Seerr's own (a proxy's page) is a failure to try again, not a lost right`() = runTest {
        val issues = model()
        issues.open()
        advanceUntilIdle()
        script.answers.removeFirst().completeExceptionally(SeerrException.Refused("<html>403 Forbidden</html>"))
        advanceUntilIdle()
        assertEquals(IssuesBody.FAILED, issues.body)
    }

}
