package com.tomdebres.hither.ui.search

import com.tomdebres.hither.core.rules.TitleCard

import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.rules.mediaBadge
import com.tomdebres.hither.ui.discover.DiscoverPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search on the JVM, against a scripted source whose every answer the test gives. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchModelTest {
    private class Script : SearchSource {
        val asked = mutableListOf<Pair<String, Int>>()
        private val answers = mutableMapOf<Pair<String, Int>, CompletableDeferred<DiscoverPage>>()

        fun answer(query: String, page: Int = 1) = answers.getOrPut(query to page) { CompletableDeferred() }

        override suspend fun page(query: String, page: Int): DiscoverPage {
            asked += query to page
            try {
                return answer(query, page).await()
            } finally {
                answers.remove(query to page)
            }
        }
    }

    private val script = Script()

    private fun page(ids: Iterable<Int>, page: Int = 1, totalPages: Int = 1) = DiscoverPage(
        "http://seerr.test",
        Page(page = page, totalPages = totalPages, results = ids.map { MediaResult(id = it, mediaType = "movie", title = "Film $it") }),
    )

    private fun TestScope.model() = SearchModel(
        source = script,
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
        work = StandardTestDispatcher(testScheduler),
        debounceMillis = 300,
    )

    @Test
    fun `typing asks once the words have rested 300 ms, and only for the last words`() = runTest {
        val search = model()
        assertEquals(SearchBody.EMPTY, search.body)
        search.type("s")
        advanceTimeBy(200)
        search.type("st")
        advanceTimeBy(200)
        search.type("sta ")
        advanceTimeBy(299)
        runCurrent()
        assertTrue(script.asked.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        // the spaces around the words are not sent
        assertEquals(listOf("sta" to 1), script.asked)
        assertEquals(SearchBody.LOADING, search.body)
        script.answer("sta").complete(page(1..3))
        advanceUntilIdle()
        assertEquals(SearchBody.RESULTS, search.body)
        assertEquals(listOf("movie:1", "movie:2", "movie:3"), search.cards.map { it.key })
        assertEquals("sta", search.query)
        // the field reporting the same words again asks nothing
        search.type("sta")
        advanceUntilIdle()
        assertEquals(1, script.asked.size)
    }

    @Test
    fun `the keyboard's action asks at once, and the results of older words stay until the new ones come`() = runTest {
        val search = model()
        search.type("a")
        search.submit()
        runCurrent()
        assertEquals(listOf("a" to 1), script.asked)
        script.answer("a").complete(page(1..2))
        advanceUntilIdle()
        search.type("ab")
        search.submit()
        runCurrent()
        // still the old cards while "ab" is out: no blink at each letter
        assertEquals(SearchBody.RESULTS, search.body)
        assertEquals(listOf("movie:1", "movie:2"), search.cards.map { it.key })
        script.answer("ab").complete(page(listOf(7)))
        advanceUntilIdle()
        assertEquals(listOf("movie:7"), search.cards.map { it.key })
        // the debounce that was waiting for "ab" does not ask again
        advanceUntilIdle()
        assertEquals(2, script.asked.size)
    }

    @Test
    fun `an answer to older words is dropped`() = runTest {
        val search = model()
        search.type("old")
        search.submit()
        runCurrent()
        search.type("new")
        search.submit()
        runCurrent()
        script.answer("new").complete(page(listOf(2)))
        advanceUntilIdle()
        script.answer("old").complete(page(listOf(1)))
        advanceUntilIdle()
        assertEquals(listOf("movie:2"), search.cards.map { it.key })
        assertEquals("new", search.query)
    }

    @Test
    fun `clearing the field clears the page, and nothing for the words is No results`() = runTest {
        val search = model()
        search.type("zz")
        search.submit()
        runCurrent()
        script.answer("zz").complete(page(emptyList()))
        advanceUntilIdle()
        assertEquals(SearchBody.NO_RESULTS, search.body)
        search.type("   ")
        advanceUntilIdle()
        assertEquals(SearchBody.EMPTY, search.body)
        assertNull(search.query)
        assertEquals(1, script.asked.size)
    }

    @Test
    fun `a failure offers Try Again, which keeps its place while it is out and asks once`() = runTest {
        val search = model()
        search.type("coco")
        search.submit()
        runCurrent()
        script.answer("coco").completeExceptionally(SeerrException.Unreachable(RuntimeException("down")))
        advanceUntilIdle()
        assertEquals(SearchBody.FAILED, search.body)
        assertTrue(search.cards.isEmpty())

        search.retry()
        search.retry()
        runCurrent()
        assertEquals(listOf("coco" to 1, "coco" to 1), script.asked)
        // the button stays ("Trying…") until the answer
        assertTrue(search.retrying)
        assertEquals(SearchBody.FAILED, search.body)
        script.answer("coco").complete(page(listOf(5)))
        advanceUntilIdle()
        assertFalse(search.retrying)
        assertEquals(SearchBody.RESULTS, search.body)
    }

    @Test
    fun `more is asked for two rows before the end, never twice, never past the last page`() = runTest {
        val search = model()
        search.type("x")
        search.submit()
        runCurrent()
        script.answer("x").complete(page(1..20, totalPages = 2))
        advanceUntilIdle()
        search.focused(7)
        advanceUntilIdle()
        assertEquals(1, script.asked.size)
        search.focused(8)
        search.focused(9)
        advanceUntilIdle()
        assertEquals(listOf("x" to 1, "x" to 2), script.asked)
        script.answer("x", 2).complete(page(listOf(19, 20, 21), page = 2, totalPages = 2))
        advanceUntilIdle()
        // repeats across pages dropped
        assertEquals(21, search.cards.size)
        search.focused(20)
        advanceUntilIdle()
        assertEquals(2, script.asked.size)
    }

    @Test
    fun `a later page that fails keeps the results, and Try Again asks for that page again`() = runTest {
        val search = model()
        search.type("x")
        search.submit()
        runCurrent()
        script.answer("x").complete(page(1..20, totalPages = 2))
        advanceUntilIdle()
        search.focused(9)
        advanceUntilIdle()
        script.answer("x", 2).completeExceptionally(SeerrException.Unreachable(RuntimeException("down")))
        advanceUntilIdle()
        assertEquals(SearchBody.RESULTS, search.body)
        assertTrue(search.moreFailed)
        assertEquals(20, search.cards.size)
        // moving along does not ask again on its own
        search.focused(19)
        advanceUntilIdle()
        assertEquals(listOf("x" to 1, "x" to 2), script.asked)
        search.retry()
        search.retry()
        advanceUntilIdle()
        assertEquals(listOf("x" to 1, "x" to 2, "x" to 2), script.asked)
        // the card stays ("Trying…") while the page is out
        assertTrue(search.moreFailed)
        assertTrue(search.retryingMore)
        script.answer("x", 2).complete(page(21..25, page = 2, totalPages = 2))
        advanceUntilIdle()
        assertFalse(search.moreFailed)
        assertEquals(25, search.cards.size)
    }

    @Test
    fun `a status from a title's page reaches its result card in place`() = runTest {
        val search = model()
        search.type("x")
        search.submit()
        runCurrent()
        script.answer("x").complete(page(1..3))
        advanceUntilIdle()
        search.setBadge("movie:2", mediaBadge(MediaStatus.PENDING))
        assertEquals(listOf(null, "Pending", null), search.cards.map { (it as TitleCard).badge?.text })
        assertEquals(listOf("movie:1", "movie:2", "movie:3"), search.cards.map { it.key })
    }
}
