package com.tomdebres.hither.ui.browse

import com.tomdebres.hither.core.model.DiscoverQuery
import com.tomdebres.hither.core.model.Genre
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.rules.BrowseFilters
import com.tomdebres.hither.core.rules.ProviderTile
import com.tomdebres.hither.core.rules.Years
import com.tomdebres.hither.ui.discover.DiscoverPage
import com.tomdebres.hither.ui.grid.GridBody
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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Movies and Series pages on the JVM (M6, DECISIONS 102), against a scripted source. */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseModelTest {
    private class Script : BrowseSource {
        val asked = mutableListOf<Pair<DiscoverQuery, Int>>()
        val answers = ArrayDeque<CompletableDeferred<DiscoverPage>>()
        var optionsAsked = 0
        val optionAnswers = ArrayDeque<CompletableDeferred<FilterOptions>>()

        override suspend fun page(type: MediaType, query: DiscoverQuery, page: Int): DiscoverPage {
            asked += query to page
            return CompletableDeferred<DiscoverPage>().also { answers.addLast(it) }.await()
        }

        override suspend fun options(type: MediaType): FilterOptions {
            optionsAsked++
            return CompletableDeferred<FilterOptions>().also { optionAnswers.addLast(it) }.await()
        }
    }

    private val script = Script()
    private val options = FilterOptions(listOf(Genre(18, "Drama")), emptyList(), listOf(ProviderTile(8, "Netflix", null)), region = "NL")

    private fun page(ids: IntRange) = DiscoverPage("http://seerr.test", Page(page = 1, totalPages = 1, results = ids.map { MediaResult(id = it, mediaType = "movie", title = "Film $it") }))

    private fun TestScope.model() = BrowseModel(
        type = MediaType.MOVIE,
        source = script,
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
        work = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `the page opens on Seerr's default sort, unfiltered`() = runTest {
        val browse = model()
        browse.start()
        advanceUntilIdle()
        assertEquals(listOf(DiscoverQuery(sortBy = "popularity.desc") to 1), script.asked)
        script.answers.removeFirst().complete(page(1..3))
        advanceUntilIdle()
        assertEquals(GridBody.RESULTS, browse.grid.body)
    }

    @Test
    fun `a new sort is a new grid from the top, the old one stopped, and the same sort changes nothing`() = runTest {
        val browse = model()
        browse.start()
        advanceUntilIdle()
        val first = browse.grid
        val out = script.answers.removeFirst()
        browse.sortBy("vote_average.desc")
        advanceUntilIdle()
        assertNotSame(first, browse.grid)
        assertEquals(DiscoverQuery(sortBy = "vote_average.desc") to 1, script.asked.last())
        out.complete(page(1..3))
        advanceUntilIdle()
        assertTrue("the old grid's answer went nowhere", first.cards.isEmpty())
        val second = browse.grid
        browse.sortBy("vote_average.desc")
        browse.sortBy("first_air_date.desc")
        assertSame("the same sort, or a series' sort on films, changes nothing", second, browse.grid)
        assertEquals(2, script.asked.size)
    }

    @Test
    fun `filters apply as the panel leaves them, services in the region the choices were read for`() = runTest {
        val browse = model()
        browse.start()
        browse.loadOptions()
        browse.loadOptions()
        advanceUntilIdle()
        assertEquals("read once", 1, script.optionsAsked)
        script.optionAnswers.removeFirst().complete(options)
        advanceUntilIdle()
        assertEquals(options, browse.options)
        val grid = browse.grid
        browse.apply(BrowseFilters())
        assertSame("unchanged filters read nothing again", grid, browse.grid)
        browse.apply(BrowseFilters(genres = setOf(18), years = Years.NINETIES, providers = setOf(8)))
        advanceUntilIdle()
        assertEquals(
            DiscoverQuery(sortBy = "popularity.desc", genre = "18", releasedFrom = "1990-01-01", releasedTo = "1999-12-31", watchProviders = "8", watchRegion = "NL") to 1,
            script.asked.last(),
        )
    }

    @Test
    fun `choices that fail are read again on the next try`() = runTest {
        val browse = model()
        browse.loadOptions()
        advanceUntilIdle()
        script.optionAnswers.removeFirst().completeExceptionally(RuntimeException("down"))
        advanceUntilIdle()
        assertTrue(browse.optionsFailed)
        assertFalse(browse.optionsLoading)
        browse.loadOptions()
        advanceUntilIdle()
        assertEquals(2, script.optionsAsked)
        assertFalse(browse.optionsFailed)
        script.optionAnswers.removeFirst().complete(options)
        advanceUntilIdle()
        browse.loadOptions()
        advanceUntilIdle()
        assertEquals("kept once read", 2, script.optionsAsked)
    }
}
