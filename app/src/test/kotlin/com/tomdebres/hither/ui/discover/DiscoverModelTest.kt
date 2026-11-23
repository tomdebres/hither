package com.tomdebres.hither.ui.discover

import com.tomdebres.hither.core.rules.RowSource

import com.tomdebres.hither.core.rules.DiscoverPlan

import com.tomdebres.hither.core.model.MediaType

import com.tomdebres.hither.core.model.DiscoverQuery

import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.DiscoverRow
import com.tomdebres.hither.core.rules.StatusWord
import com.tomdebres.hither.core.rules.mediaBadge
import com.tomdebres.hither.session.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Discover's rows on the JVM, against a scripted source whose every answer the test gives. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverModelTest {
    private val base = "http://seerr.test:5055"

    /** Every page asked for waits here until the test answers it. */
    private class Script : DiscoverSource {
        val asked = mutableListOf<Pair<DiscoverRow, Int>>()
        private val answers = mutableMapOf<Pair<DiscoverRow, Int>, CompletableDeferred<DiscoverPage>>()

        fun answer(row: DiscoverRow, page: Int) = answers.getOrPut(row to page) { CompletableDeferred() }

        override suspend fun page(row: DiscoverRow, page: Int): DiscoverPage {
            asked += row to page
            val answer = answer(row, page)
            try {
                return answer.await()
            } finally {
                answers.remove(row to page)
            }
        }
    }

    private val script = Script()

    private fun page(ids: Iterable<Int>, page: Int = 1, totalPages: Int = 3, status: (Int) -> Int? = { null }) = DiscoverPage(
        base,
        Page(
            page = page,
            totalPages = totalPages,
            results = ids.map { id ->
                MediaResult(id = id, mediaType = "movie", title = "Film $id", posterPath = "/p$id.jpg", mediaInfo = status(id)?.let { MediaInfo(tmdbId = id, status = it) })
            },
        ),
    )

    private fun TestScope.model(settings: PublicSettings = PublicSettings()) = DiscoverModel(
        source = script,
        session = Session(User(id = 8, permissions = Permission.REQUEST), settings),
        // Not the test's own scope: rows a test leaves unanswered are still waiting when it ends.
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
        work = StandardTestDispatcher(testScheduler),
    )

    private fun DiscoverModel.row(row: DiscoverRow) = rows.first { it.row == row }

    @Test
    fun `the five rows are Seerr's, in its words, Upcoming Series last`() = runTest {
        // Upcoming Series joined the four of M1 in the polish (DECISIONS 84), in Seerr's word for it.
        assertEquals(listOf("Trending", "Popular Movies", "Popular Series", "Upcoming Movies", "Upcoming Series"), model().rows.map { it.row.title })
    }

    @Test
    fun `each row loads on its own, and one failing or slow leaves the others with their cards`() = runTest {
        val discover = model()
        discover.start()
        advanceUntilIdle()
        assertEquals(DiscoverRow.entries.map { it to 1 }, script.asked)
        assertTrue(discover.rows.all { it.body == RowBody.LOADING })

        script.answer(DiscoverRow.TRENDING, 1).complete(page(1..10))
        script.answer(DiscoverRow.POPULAR_MOVIES, 1).completeExceptionally(SeerrException.Http(500, "Unable to retrieve popular movies."))
        script.answer(DiscoverRow.POPULAR_SERIES, 1).complete(page(20..29))
        advanceUntilIdle()

        assertEquals(RowBody.CARDS, discover.row(DiscoverRow.TRENDING).body)
        assertEquals(10, discover.row(DiscoverRow.TRENDING).cards.size)
        assertEquals(RowBody.FAILED, discover.row(DiscoverRow.POPULAR_MOVIES).body)
        assertEquals(RowBody.CARDS, discover.row(DiscoverRow.POPULAR_SERIES).body)
        assertEquals(RowBody.LOADING, discover.row(DiscoverRow.UPCOMING_MOVIES).body)
    }

    @Test
    fun `start asks once however often it is called`() = runTest {
        val discover = model()
        discover.start()
        discover.start()
        advanceUntilIdle()
        assertEquals(DiscoverRow.entries.size, script.asked.size)
    }

    @Test
    fun `Try Again on a failed row asks for its first page again and shows it`() = runTest {
        val discover = model()
        discover.start()
        advanceUntilIdle()
        script.answer(DiscoverRow.POPULAR_MOVIES, 1).completeExceptionally(SeerrException.Unreachable(RuntimeException()))
        advanceUntilIdle()
        val movies = discover.row(DiscoverRow.POPULAR_MOVIES)
        assertEquals(RowBody.FAILED, movies.body)

        discover.retry(movies)
        assertEquals(RowBody.LOADING, movies.body)
        discover.retry(movies) // a second press while it is out does nothing
        advanceUntilIdle()
        assertEquals(2, script.asked.count { it == DiscoverRow.POPULAR_MOVIES to 1 })

        script.answer(DiscoverRow.POPULAR_MOVIES, 1).complete(page(1..8))
        advanceUntilIdle()
        assertEquals(RowBody.CARDS, movies.body)
    }

    @Test
    fun `an empty answer says so`() = runTest {
        val discover = model()
        discover.start()
        advanceUntilIdle()
        script.answer(DiscoverRow.UPCOMING_MOVIES, 1).complete(page(emptyList(), totalPages = 1))
        advanceUntilIdle()
        assertEquals(RowBody.EMPTY, discover.row(DiscoverRow.UPCOMING_MOVIES).body)
    }

    @Test
    fun `focus near the end asks for the next page once, and appends it without repeats`() = runTest {
        val discover = model()
        discover.start()
        advanceUntilIdle()
        script.answer(DiscoverRow.TRENDING, 1).complete(page(1..20))
        advanceUntilIdle()
        val trending = discover.row(DiscoverRow.TRENDING)

        discover.focused(trending, 3)
        advanceUntilIdle()
        assertFalse(DiscoverRow.TRENDING to 2 in script.asked)

        discover.focused(trending, 14)
        discover.focused(trending, 15)
        discover.focused(trending, 16)
        advanceUntilIdle()
        assertEquals(1, script.asked.count { it == DiscoverRow.TRENDING to 2 })
        assertTrue(trending.loadingMore)

        // TMDB's list moved: page 2 repeats three titles from page 1
        script.answer(DiscoverRow.TRENDING, 2).complete(page(18..37, page = 2))
        advanceUntilIdle()
        assertEquals((1..37).map { "movie:$it" }, trending.cards.map { it.key })
        assertFalse(trending.loadingMore)
        assertEquals(16, trending.focusedIndex)
    }

    @Test
    fun `no page is asked for past the last`() = runTest {
        val discover = model()
        discover.start()
        advanceUntilIdle()
        script.answer(DiscoverRow.TRENDING, 1).complete(page(1..20, totalPages = 1))
        advanceUntilIdle()
        discover.focused(discover.row(DiscoverRow.TRENDING), 19)
        advanceUntilIdle()
        assertFalse(DiscoverRow.TRENDING to 2 in script.asked)
    }

    @Test
    fun `a later page that fails keeps the cards and offers Try Again at the end, which asks for that page`() = runTest {
        val discover = model()
        discover.start()
        advanceUntilIdle()
        script.answer(DiscoverRow.TRENDING, 1).complete(page(1..20))
        advanceUntilIdle()
        val trending = discover.row(DiscoverRow.TRENDING)
        discover.focused(trending, 19)
        advanceUntilIdle()
        script.answer(DiscoverRow.TRENDING, 2).completeExceptionally(SeerrException.Http(500, null))
        advanceUntilIdle()

        assertEquals(RowBody.CARDS, trending.body)
        assertEquals(20, trending.cards.size)
        assertTrue(trending.moreFailed)
        // moving along the row does not hammer a failing server
        discover.focused(trending, 18)
        advanceUntilIdle()
        assertEquals(1, script.asked.count { it == DiscoverRow.TRENDING to 2 })

        discover.retry(trending)
        advanceUntilIdle()
        assertEquals(2, script.asked.count { it == DiscoverRow.TRENDING to 2 })
        script.answer(DiscoverRow.TRENDING, 2).complete(page(21..40, page = 2))
        advanceUntilIdle()
        assertEquals(40, trending.cards.size)
        assertFalse(trending.moreFailed)
    }

    @Test
    fun `while Try Again for a later page is out its card stays, reading Trying, and a second press asks nothing`() = runTest {
        val discover = model()
        discover.start()
        advanceUntilIdle()
        script.answer(DiscoverRow.TRENDING, 1).complete(page(1..20))
        advanceUntilIdle()
        val trending = discover.row(DiscoverRow.TRENDING)
        discover.focused(trending, 19)
        advanceUntilIdle()
        script.answer(DiscoverRow.TRENDING, 2).completeExceptionally(SeerrException.Http(500, null))
        advanceUntilIdle()

        discover.retry(trending)
        discover.retry(trending)
        runCurrent()
        // the card is still the row's end: no placeholder takes its place, so focus is not dropped
        assertTrue(trending.moreFailed)
        assertTrue(trending.retryingMore)
        assertFalse(trending.loadingMore)
        assertEquals(2, script.asked.count { it == DiscoverRow.TRENDING to 2 })
        // failing again: Try Again as it was
        script.answer(DiscoverRow.TRENDING, 2).completeExceptionally(SeerrException.Http(500, null))
        advanceUntilIdle()
        assertTrue(trending.moreFailed)
        assertFalse(trending.retryingMore)
        discover.retry(trending)
        runCurrent()
        script.answer(DiscoverRow.TRENDING, 2).complete(page(21..40, page = 2))
        advanceUntilIdle()
        assertFalse(trending.moreFailed)
        assertFalse(trending.retryingMore)
        assertEquals(40, trending.cards.size)
    }

    @Test
    fun `a row the server's filters leave short fills itself before anyone reaches its end`() = runTest {
        // hideAvailable on: 17 of page 1's 20 titles are in the library
        val discover = model(PublicSettings(hideAvailable = true))
        discover.start()
        advanceUntilIdle()
        script.answer(DiscoverRow.TRENDING, 1).complete(page(1..20) { if (it > 3) MediaStatus.AVAILABLE else null })
        advanceUntilIdle()
        val trending = discover.row(DiscoverRow.TRENDING)
        assertEquals(3, trending.cards.size)
        assertEquals(1, script.asked.count { it == DiscoverRow.TRENDING to 2 })

        script.answer(DiscoverRow.TRENDING, 2).complete(page(21..40, page = 2))
        advanceUntilIdle()
        assertEquals(23, trending.cards.size)
        assertFalse(DiscoverRow.TRENDING to 3 in script.asked)
    }

    @Test
    fun `live status, badges are asked for again and change in place, nothing moves`() = runTest {
        val discover = model()
        discover.start()
        advanceUntilIdle()
        script.answer(DiscoverRow.TRENDING, 1).complete(page(1..8) { if (it == 2) MediaStatus.PENDING else null })
        advanceUntilIdle()
        val trending = discover.row(DiscoverRow.TRENDING)
        val before = trending.cards.map { it.key }
        assertEquals(StatusWord.PENDING, trending.cards[1].badge?.word)

        discover.refreshBadges()
        advanceUntilIdle()
        // the server's list has moved on, and two titles have changed state
        script.answer(DiscoverRow.TRENDING, 1).complete(
            page(listOf(5, 3, 1, 99, 8, 2, 7, 6, 4)) {
                when (it) {
                    2 -> MediaStatus.AVAILABLE
                    5 -> MediaStatus.PROCESSING
                    else -> null
                }
            },
        )
        advanceUntilIdle()
        // same cards, same order: the reshuffle and the newcomer (99) are ignored
        assertEquals(before, trending.cards.map { it.key })
        assertEquals(StatusWord.AVAILABLE, trending.cards[1].badge?.word)
        assertEquals(StatusWord.REQUESTED, trending.cards[4].badge?.word)
        assertNull(trending.cards[0].badge)
    }

    @Test
    fun `a badge refresh that fails keeps the badges there were`() = runTest {
        val discover = model()
        discover.start()
        advanceUntilIdle()
        script.answer(DiscoverRow.TRENDING, 1).complete(page(1..8) { if (it == 2) MediaStatus.PENDING else null })
        advanceUntilIdle()
        discover.refreshBadges()
        advanceUntilIdle()
        script.answer(DiscoverRow.TRENDING, 1).completeExceptionally(SeerrException.Unreachable(RuntimeException()))
        advanceUntilIdle()
        val trending = discover.row(DiscoverRow.TRENDING)
        assertEquals(StatusWord.PENDING, trending.cards[1].badge?.word)
        assertEquals(RowBody.CARDS, trending.body)
    }

    @Test
    fun `a status from a title's page reaches its card in every row, in place, and nothing else`() = runTest {
        val discover = model()
        discover.start()
        advanceUntilIdle()
        script.answer(DiscoverRow.TRENDING, 1).complete(page(1..8))
        script.answer(DiscoverRow.POPULAR_MOVIES, 1).complete(page(listOf(3, 9, 10, 11, 12, 13, 14, 15)))
        advanceUntilIdle()
        val trending = discover.row(DiscoverRow.TRENDING)
        val movies = discover.row(DiscoverRow.POPULAR_MOVIES)
        val before = trending.cards.map { it.key }
        val asked = script.asked.size
        val requested = mediaBadge(MediaStatus.PROCESSING)

        discover.setBadge("movie:3", requested)
        assertEquals(requested, trending.cards[2].badge)
        assertEquals(requested, movies.cards[0].badge)
        assertEquals(before, trending.cards.map { it.key })
        assertTrue(trending.cards.filter { it.key != "movie:3" }.all { it.badge == null })
        // no row was asked for again
        advanceUntilIdle()
        assertEquals(asked, script.asked.size)
        // the same badge again changes nothing; a card no row holds is ignored
        val cards = trending.cards
        discover.setBadge("movie:3", requested)
        discover.setBadge("tv:3", mediaBadge(MediaStatus.PENDING))
        assertTrue(cards === trending.cards)
    }

    @Test
    fun `the row last focused is where Discover comes back to`() = runTest {
        val discover = model()
        discover.start()
        advanceUntilIdle()
        script.answer(DiscoverRow.POPULAR_SERIES, 1).complete(page(1..20))
        advanceUntilIdle()
        assertEquals(0, discover.lastRow)
        discover.focused(discover.row(DiscoverRow.POPULAR_SERIES), 4)
        assertEquals(2, discover.lastRow)
        assertEquals(4, discover.row(DiscoverRow.POPULAR_SERIES).focusedIndex)
    }
    // ---- M6: the server's Discover settings, Recently Added (DECISIONS 99) ------------------------------

    private val zombies = DiscoverRow("CUSTOM_13", "Zombies", RowSource.Query(MediaType.MOVIE, DiscoverQuery(keywords = "12377")), 113)

    private fun TestScope.planned(plan: CompletableDeferred<DiscoverPlan>) = DiscoverModel(
        source = script,
        session = Session(User(id = 8, permissions = Permission.REQUEST), PublicSettings()),
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
        work = StandardTestDispatcher(testScheduler),
        plans = PlanSource { plan.await() },
    )

    @Test
    fun `the rows follow the plan, Hither's own standing in until it comes, and none is asked for twice`() = runTest {
        val plan = CompletableDeferred<DiscoverPlan>()
        val discover = planned(plan)
        discover.start()
        advanceUntilIdle()
        assertEquals("nothing asked before the plan", emptyList<Pair<DiscoverRow, Int>>(), script.asked)
        assertEquals(DiscoverRow.entries, discover.rows.map { it.row })
        val before = discover.row(DiscoverRow.TRENDING)
        plan.complete(DiscoverPlan(recentRequests = false, rows = listOf(DiscoverRow.RECENTLY_ADDED, DiscoverRow.POPULAR_MOVIES, zombies, DiscoverRow.TRENDING)))
        advanceUntilIdle()
        assertEquals(listOf("Recently Added", "Popular Movies", "Zombies", "Trending"), discover.rows.map { it.row.title })
        assertFalse(discover.recentShown)
        assertTrue("a row the plan keeps is the same row", before === discover.row(DiscoverRow.TRENDING))
        assertEquals(discover.rows.map { it.row to 1 }.toSet(), script.asked.toSet())
        assertEquals(4, script.asked.size)
    }

    @Test
    fun `a plan that cannot be read leaves Hither's own rows, Recently Added only with the right`() = runTest {
        val discover = planned(CompletableDeferred<DiscoverPlan>().also { it.completeExceptionally(IllegalStateException("down")) })
        discover.start()
        advanceUntilIdle()
        assertEquals(DiscoverRow.entries, discover.rows.map { it.row })
        assertTrue(discover.recentShown)
        assertEquals(5, script.asked.size)
    }

    @Test
    fun `an empty Recently Added goes, as Seerr's web leaves it out`() = runTest {
        val discover = planned(CompletableDeferred(DiscoverPlan(true, listOf(DiscoverRow.RECENTLY_ADDED, DiscoverRow.TRENDING))))
        discover.start()
        advanceUntilIdle()
        script.answer(DiscoverRow.RECENTLY_ADDED, 1).complete(page(emptyList(), totalPages = 1))
        advanceUntilIdle()
        assertEquals(listOf(DiscoverRow.TRENDING), discover.rows.map { it.row })
    }

    @Test
    fun `starting on Trending finds it wherever the plan puts it`() = runTest {
        val discover = planned(CompletableDeferred(DiscoverPlan(true, listOf(DiscoverRow.RECENTLY_ADDED, zombies, DiscoverRow.TRENDING))))
        discover.startOnRows()
        discover.start()
        advanceUntilIdle()
        assertEquals(2, discover.lastRow)
        assertFalse(discover.inRecent)
    }

    // ---- "See More" at the end of Popular Movies and Popular Series (Tom, 2026-11-12, DECISIONS 112) -----

    @Test
    fun `Popular Movies reads its 20 and the four posters See More carries, and no further`() = runTest {
        val discover = model()
        discover.start()
        advanceUntilIdle()
        val popular = discover.row(DiscoverRow.POPULAR_MOVIES)
        script.answer(DiscoverRow.POPULAR_MOVIES, 1).complete(page(1..20, totalPages = 9))
        advanceUntilIdle()
        // focus near the end of the 20: the next page, for the card's posters
        discover.focused(popular, 15)
        advanceUntilIdle()
        assertEquals(DiscoverRow.POPULAR_MOVIES to 2, script.asked.last())
        // a page that brings just the four (filters took the rest): any other row would page on from here
        script.answer(DiscoverRow.POPULAR_MOVIES, 2).complete(page(21..24, page = 2, totalPages = 9))
        advanceUntilIdle()
        val asked = script.asked.size
        // on the 20th card, and on See More: nothing more is asked for, though seven pages are left
        discover.focused(popular, 19)
        discover.focused(popular, 20)
        advanceUntilIdle()
        assertEquals("asked for more past the card's posters: ${script.asked.drop(asked)}", asked, script.asked.size)
        // Trending, without See More, carries on as it always has
        script.answer(DiscoverRow.TRENDING, 1).complete(page(101..120, totalPages = 9))
        advanceUntilIdle()
        discover.focused(discover.row(DiscoverRow.TRENDING), 19)
        advanceUntilIdle()
        assertEquals(DiscoverRow.TRENDING to 2, script.asked.last())
    }

    @Test
    fun `focus on See More is the row's place, so Discover comes back to it`() = runTest {
        val discover = model()
        discover.start()
        advanceUntilIdle()
        script.answer(DiscoverRow.POPULAR_SERIES, 1).complete(page(1..20, totalPages = 9))
        advanceUntilIdle()
        discover.focused(discover.row(DiscoverRow.POPULAR_SERIES), 20)
        assertEquals(2, discover.lastRow)
        assertEquals(20, discover.row(DiscoverRow.POPULAR_SERIES).focusedIndex)
        assertFalse(discover.inRecent)
    }

    @Test
    fun `a plan asks for arrival focus again before the rows are used, and after only when the row used goes`() = runTest {
        val plan = CompletableDeferred<DiscoverPlan>()
        val discover = planned(plan)
        discover.start()
        advanceUntilIdle()
        plan.complete(DiscoverPlan(true, listOf(DiscoverRow.RECENTLY_ADDED, DiscoverRow.TRENDING)))
        advanceUntilIdle()
        assertEquals("the plan put a new row first: arrival focus again", 1, discover.refocus)
        assertEquals(0, discover.lastRow)

        // the user takes Recently Added's first card, and the row turns out empty: focus goes on
        script.answer(DiscoverRow.TRENDING, 1).complete(page(1..20))
        advanceUntilIdle()
        discover.focused(discover.row(DiscoverRow.TRENDING), 2)
        script.answer(DiscoverRow.RECENTLY_ADDED, 1).complete(page(emptyList(), totalPages = 1))
        advanceUntilIdle()
        assertEquals("Trending, the row used, is still there", 1, discover.refocus)
        assertEquals(0, discover.lastRow)
    }
}
