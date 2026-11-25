package com.tomdebres.hither.ui.grid

import com.tomdebres.hither.core.rules.ImageSource
import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.rules.StatusBadge
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.core.rules.mediaBadge
import com.tomdebres.hither.ui.discover.DiscoverPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A titles grid (a keyword's titles; M6, DECISIONS 98) on the JVM, against a scripted source. */
@OptIn(ExperimentalCoroutinesApi::class)
class TitleGridModelTest {
    private class Script : GridSource {
        val asked = mutableListOf<Int>()
        private val answers = mutableMapOf<Int, CompletableDeferred<DiscoverPage>>()

        fun answer(page: Int) = answers.getOrPut(page) { CompletableDeferred() }

        override suspend fun page(page: Int): DiscoverPage {
            asked += page
            try {
                return answer(page).await()
            } finally {
                answers.remove(page)
            }
        }
    }

    private val script = Script()

    private fun page(ids: Iterable<Int>, page: Int = 1, totalPages: Int = 1) = DiscoverPage(
        ImageSource.unknown("http://seerr.test"),
        Page(page = page, totalPages = totalPages, results = ids.map { MediaResult(id = it, mediaType = "movie", title = "Film $it") }),
    )

    private fun TestScope.model() = TitleGridModel(
        source = script,
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
        work = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `the first page is asked for once on start, placeholders until it comes`() = runTest {
        val grid = model()
        assertEquals(GridBody.LOADING, grid.body)
        grid.start()
        grid.start()
        advanceUntilIdle()
        assertEquals(listOf(1), script.asked)
        assertEquals(GridBody.LOADING, grid.body)
        script.answer(1).complete(page(1..20, totalPages = 3))
        advanceUntilIdle()
        assertEquals(GridBody.RESULTS, grid.body)
        assertEquals((1..20).map { "movie:$it" }, grid.cards.map { it.key })
    }

    @Test
    fun `the next page is asked for as focus nears the end, once, and joins the grid`() = runTest {
        val grid = model()
        grid.start()
        advanceUntilIdle()
        script.answer(1).complete(page(1..20, totalPages = 2))
        advanceUntilIdle()
        grid.focused(3)
        advanceUntilIdle()
        assertEquals("not yet: more than two rows ahead", listOf(1), script.asked)
        grid.focused(8)
        grid.focused(9)
        advanceUntilIdle()
        assertEquals(listOf(1, 2), script.asked)
        script.answer(2).complete(page(21..30, page = 2, totalPages = 2))
        advanceUntilIdle()
        assertEquals(30, grid.cards.size)
        grid.focused(29)
        advanceUntilIdle()
        assertEquals("the last page was the last", listOf(1, 2), script.asked)
    }

    @Test
    fun `a failed first page offers Try Again, which asks once more, and nothing comes back as No results`() = runTest {
        val grid = model()
        grid.start()
        advanceUntilIdle()
        script.answer(1).completeExceptionally(SeerrException.Unreachable(RuntimeException("down")))
        advanceUntilIdle()
        assertEquals(GridBody.FAILED, grid.body)
        grid.retry()
        assertTrue(grid.retrying)
        assertEquals("Try Again keeps its place while out", GridBody.FAILED, grid.body)
        grid.retry()
        advanceUntilIdle()
        assertEquals(listOf(1, 1), script.asked)
        script.answer(1).complete(page(emptyList()))
        advanceUntilIdle()
        assertEquals(GridBody.NO_RESULTS, grid.body)
        assertFalse(grid.retrying)
    }

    @Test
    fun `a failed later page keeps the cards and ends the grid with Try Again`() = runTest {
        val grid = model()
        grid.start()
        advanceUntilIdle()
        script.answer(1).complete(page(1..12, totalPages = 2))
        advanceUntilIdle()
        // twelve cards fill the screen: the second page waits for focus
        grid.focused(0)
        advanceUntilIdle()
        assertEquals(listOf(1, 2), script.asked)
        script.answer(2).completeExceptionally(SeerrException.Unreachable(RuntimeException("down")))
        advanceUntilIdle()
        assertTrue(grid.moreFailed)
        assertEquals(12, grid.cards.size)
        grid.retry()
        assertTrue(grid.retryingMore)
        advanceUntilIdle()
        script.answer(2).complete(page(13..14, page = 2, totalPages = 2))
        advanceUntilIdle()
        assertFalse(grid.moreFailed)
        assertEquals(14, grid.cards.size)
    }

    @Test
    fun `a title's new status reaches its card in the grid`() = runTest {
        val grid = model()
        grid.start()
        advanceUntilIdle()
        script.answer(1).complete(page(1..3))
        advanceUntilIdle()
        val requested: StatusBadge? = mediaBadge(3)
        grid.setBadge("movie:2", requested)
        assertEquals(listOf(null, requested, null), grid.cards.map { (it as TitleCard).badge })
    }
}
