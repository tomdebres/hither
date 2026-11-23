package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.model.Genre
import com.tomdebres.hither.core.model.Language
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.model.WatchProvider
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.LocalRecompositionProbe
import com.tomdebres.hither.ui.home.HomeScreen
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Movies and Series pages (M6, DECISIONS 102; A8, tvOS's), the whole home through the real client against a
 * scripted server: the "See More" card ending Popular Movies and Popular Series opens them (DECISIONS 112; the bar's
 * pills before), focus on the first card, Back returns to that card; Sort and Filters on the title's line in tvOS's
 * words; the sort list opens on the sort in use, in tvOS's words and order, and a pick reads the grid again; the filters
 * in the side panel from the right, a row each opening its choices, Back to Filters; applied as the panel closes, the
 * button and the panel's title counting them ("Filters (1)"); the language and the services from their lists, Clear
 * Filters; a move among the panel's rows recomposes only the two it touches.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class BrowsePageTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val session = Session(User(id = 8, displayName = "TV", permissions = 32), PublicSettings(applicationTitle = "Seerr", localLogin = true))

    /** Every Movies or Series page asked for, with its query; and the filters' choices. */
    private val browsed = CopyOnWriteArrayList<String>()
    private val choices = CopyOnWriteArrayList<String>()
    private val counts = ConcurrentHashMap<String, Int>()

    private val access = object : SeerrAccess {
        private val client = SeerrClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                val query = request.url.encodedQuery
                val json = SeerrClient.json
                val films = { from: Int -> json.encodeToString(Page.serializer(MediaResult.serializer()), Page(results = (from..from + 7).map { MediaResult(id = it, mediaType = "movie", title = "Film $it") })) }
                val body = when {
                    path == "/api/v1/settings/public" -> json.encodeToString(PublicSettings.serializer(), session.settings)
                    path == "/api/v1/auth/me" -> json.encodeToString(User.serializer(), session.user)
                    path.startsWith("/api/v1/movie/") -> json.encodeToString(MovieDetails.serializer(), MovieDetails(id = path.substringAfterLast('/').toInt(), title = "A film"))
                    // The pages' own calls (Discover's popular rows ask the same paths, with no sort)
                    (path == "/api/v1/discover/movies" || path == "/api/v1/discover/tv") && "sortBy=" in query -> {
                        browsed += "$path?$query"
                        films(if ("sortBy=vote_average" in query) 201 else 101)
                    }
                    path == "/api/v1/genres/movie" -> {
                        choices += path
                        json.encodeToString(ListSerializer(Genre.serializer()), listOf(Genre(18, "Drama"), Genre(35, "Comedy")))
                    }
                    path == "/api/v1/languages" -> {
                        choices += path
                        json.encodeToString(ListSerializer(Language.serializer()), listOf(Language("fr", "French", "Français"), Language("nl", "Dutch", "Nederlands")))
                    }
                    path == "/api/v1/watchproviders/movies" -> {
                        choices += "$path?$query"
                        json.encodeToString(ListSerializer(WatchProvider.serializer()), listOf(WatchProvider(337, "Disney Plus", null, 2), WatchProvider(8, "Netflix", null, 1)))
                    }
                    // Discover's Popular Movies and Popular Series: 20 a page, so each ends with See More
                    path == "/api/v1/discover/movies" || path == "/api/v1/discover/tv" -> {
                        val tv = path.endsWith("/tv")
                        val from = (if (tv) 400 else 300) + (Regex("page=(\\d+)").find(query)?.groupValues?.get(1)?.toInt()?.minus(1) ?: 0) * 20
                        json.encodeToString(
                            Page.serializer(MediaResult.serializer()),
                            Page(totalPages = 5, results = (from + 1..from + 20).map { if (tv) MediaResult(id = it, mediaType = "tv", name = "Series $it") else MediaResult(id = it, mediaType = "movie", title = "Film $it") }),
                        )
                    }
                    // Discover's other rows: three films each
                    else -> json.encodeToString(Page.serializer(MediaResult.serializer()), Page(results = (1..3).map { MediaResult(id = it, mediaType = "movie", title = "Film $it") }))
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
            "http://seerr.test",
            AcceptAllCookiesStorage(),
        )

        override suspend fun <T> call(call: suspend (SeerrClient) -> T): T = call(client)
    }

    private fun show() {
        rule.setContent {
            CompositionLocalProvider(LocalRecompositionProbe provides { name -> counts.merge(name, 1, Int::plus) }) {
                DpadHost { HomeScreen(Screen.Home(session), access, onSignOut = {}) }
            }
        }
        await("card movie:1")
    }

    private fun await(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "a node without a tag" } ?: "a node without a tag"
    }.getOrElse { "nothing" }

    private fun assertFocused(tag: String) {
        runCatching { rule.waitUntil(5_000) { focusedTag() == tag } }
        val now = focusedTag()
        if (now != tag) throw AssertionError("expected focus on $tag, it is on $now")
    }

    private fun words(tag: String): String = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        // the node and everything under it: a tv-material button's words sit a few levels down (A1)
        .flatMap { node -> generateSequence(listOf(node)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }.flatten().toList() }
        .flatMap { it.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty() }
        .joinToString("") { it.text }

    private fun on(tag: String) = rule.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "on"))

    /** From Discover's first card down to Popular Movies, along it to See More, and into the Movies page. */
    private fun openMovies() {
        show()
        press(Key.DirectionDown)
        assertFocused("card movie:301")
        press(Key.DirectionRight, 20)
        assertFocused("see more POPULAR_MOVIES")
        press(Key.DirectionCenter)
        await("browse heading")
        assertFocused("card movie:101")
    }

    /** From the grid (or from the Filters button itself) into the panel: focus on its first row, the choices read. */
    private fun openFilters() {
        if (focusedTag() != "filters") {
            press(Key.DirectionUp)
            assertFocused("sort")
            press(Key.DirectionRight)
        }
        assertFocused("filters")
        press(Key.DirectionCenter)
        await("filter panel")
        assertFocused("filter years")
        await("filter genres")
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    fun seeMoreOpensEachPageAndBackReturnsToIt() {
        openMovies()
        assertEquals("Movies", words("browse heading"))
        assertEquals(listOf("/api/v1/discover/movies?sortBy=popularity.desc&page=1"), browsed.toList())
        back()
        assertFocused("see more POPULAR_MOVIES")
        // the row's last stop
        press(Key.DirectionRight)
        assertFocused("see more POPULAR_MOVIES")
        press(Key.DirectionDown)
        assertFocused("card tv:401")
        press(Key.DirectionRight, 20)
        assertFocused("see more POPULAR_SERIES")
        press(Key.DirectionCenter)
        await("browse heading")
        assertEquals("Series", words("browse heading"))
        assertEquals("/api/v1/discover/tv?sortBy=popularity.desc&page=1", browsed.last())
        back()
        assertFocused("see more POPULAR_SERIES")
        // the bar no longer carries them (Tom, 2026-11-12)
        rule.onAllNodesWithTag("nav Movies").assertCountEquals(0)
        rule.onAllNodesWithTag("nav Series").assertCountEquals(0)
    }

    @Test
    fun sortAndFiltersSitOnTheTitlesLineInTheAppleTvAppsWords() {
        openMovies()
        // measured at rest: focus on the grid, not on either button (a focused button is drawn 1.1)
        val title = bounds("browse heading")
        val sort = bounds("sort")
        val filters = bounds("filters")
        val width = rule.onRoot().fetchSemanticsNode().boundsInRoot.width
        val dp = rule.density.density
        for ((name, button) in listOf("Sort" to sort, "Filters" to filters)) {
            assertEquals("$name centred on the title's line", title.center.y, button.center.y, 2 * dp)
            assertTrue("$name at the right of the title ($button against $title)", button.left > title.right)
        }
        assertTrue("Sort before Filters", sort.right <= filters.left)
        assertEquals("Filters ends at the right margin", width - 48 * dp, filters.right, 2 * dp)
        // tvOS's words: the sort in use, and the button's own name
        assertEquals("Most Popular", words("sort"))
        assertEquals("Filters", words("filters"))
    }

    @Test
    fun theSortListOpensOnTheSortInUseAndAPickReadsTheGridAgainFromTheTop() {
        openMovies()
        press(Key.DirectionUp)
        assertFocused("sort")
        press(Key.DirectionCenter)
        await("sort list")
        assertFocused("pick popularity.desc")
        // tvOS's words and order: each pair's usual way first, the one in use ticked
        val order = listOf("popularity.desc", "popularity.asc", "release_date.desc", "release_date.asc", "vote_average.desc", "vote_average.asc", "original_title.asc", "original_title.desc")
        val picks = SemanticsMatcher("a sort's row") { it.config.getOrElseNullable(SemanticsProperties.TestTag) { null }?.startsWith("pick ") == true }
        val shown = rule.onAllNodes(picks, useUnmergedTree = true).fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.top }.map { it.config[SemanticsProperties.TestTag].removePrefix("pick ") }
        assertEquals(order, shown)
        assertEquals("Most Popular", words("pick popularity.desc"))
        assertEquals("Title A to Z", words("pick original_title.asc"))
        rule.onNode(hasTestTag("tick") and hasAnyAncestor(hasTestTag("pick popularity.desc")), useUnmergedTree = true).assertExists()
        // under the title's line, its rows ending under Sort's right edge (the list's 8 dp inside it); a row at rest
        val dp = rule.density.density
        val row = bounds("pick popularity.asc")
        assertTrue("the list under the title's line", row.top > bounds("browse heading").bottom)
        assertEquals("the list's right edge under Sort's", bounds("sort").right - 8 * dp, row.right, 2 * dp)
        back()
        rule.onAllNodesWithTag("sort list").assertCountEquals(0)
        assertFocused("sort")
        assertEquals("Back changes nothing", 1, browsed.size)

        press(Key.DirectionCenter)
        await("sort list")
        press(Key.DirectionDown, 4)
        assertFocused("pick vote_average.desc")
        press(Key.DirectionCenter)
        rule.onAllNodesWithTag("sort list").assertCountEquals(0)
        assertFocused("sort")
        assertEquals("Highest Rated", words("sort"))
        assertEquals("/api/v1/discover/movies?sortBy=vote_average.desc&page=1", browsed.last())
        await("card movie:201")
        press(Key.DirectionDown)
        assertFocused("card movie:201")
    }

    @Test
    fun theFiltersOpenInThePanelFromTheRightAndBackClosesItToFilters() {
        openMovies()
        openFilters()
        // the panel along the right edge, its title the button's name, a row for each filter
        val width = rule.onRoot().fetchSemanticsNode().boundsInRoot.width
        val panel = bounds("filter panel")
        assertEquals("the panel at the right edge", width, panel.right, 1f)
        assertEquals("420 dp wide", 420 * rule.density.density, panel.width, 2f)
        assertEquals("Filters", words("panel title"))
        for (row in listOf("filter years", "filter genres", "language", "filter providers")) {
            assertTrue("$row in the panel", bounds(row).left >= panel.left)
        }
        assertEquals("All Languages", words("language value"))
        // focus stays inside: Left and Up go nowhere
        press(Key.DirectionLeft)
        assertFocused("filter years")
        press(Key.DirectionUp)
        assertFocused("filter years")
        back()
        rule.onAllNodesWithTag("filter panel").assertCountEquals(0)
        assertFocused("filters")
        assertEquals("nothing set, nothing read again", 1, browsed.size)
    }

    @Test
    fun oneFilterMakesFiltersOneAndTheyApplyAsThePanelCloses() {
        openMovies()
        openFilters()
        // a decade: its list, one picked, back on its row with the decade at its right
        press(Key.DirectionCenter)
        await("years TWENTY_TWENTIES")
        assertEquals("Release Date", words("panel title"))
        assertFocused("years TWENTY_TWENTIES")
        press(Key.DirectionCenter)
        assertFocused("filter years")
        assertEquals("2020s", words("filter years value"))
        assertEquals("Filters (1)", words("panel title"))
        assertEquals("nothing is read while the panel is open", 1, browsed.size)
        back()
        rule.onAllNodesWithTag("filter panel").assertCountEquals(0)
        assertFocused("filters")
        assertEquals("Filters (1)", words("filters"))
        assertEquals("/api/v1/discover/movies?sortBy=popularity.desc&primaryReleaseDateGte=2020-01-01&page=1", browsed.last())

        // a genre too: its list keeps focus as Select ticks it, Back comes back to its row
        openFilters()
        assertEquals("Filters (1)", words("panel title"))
        assertEquals("2020s", words("filter years value"))
        press(Key.DirectionDown)
        assertFocused("filter genres")
        press(Key.DirectionCenter)
        await("genre 18")
        assertEquals("Genres", words("panel title"))
        assertFocused("genre 18")
        press(Key.DirectionCenter)
        on("genre 18")
        rule.onNode(hasTestTag("tick") and hasAnyAncestor(hasTestTag("genre 18")), useUnmergedTree = true).assertExists()
        assertFocused("genre 18")
        back()
        assertFocused("filter genres")
        assertEquals("Drama", words("filter genres value"))
        assertEquals("Filters (2)", words("panel title"))
        back()
        assertFocused("filters")
        assertEquals("Filters (2)", words("filters"))
        assertEquals("/api/v1/discover/movies?sortBy=popularity.desc&genre=18&primaryReleaseDateGte=2020-01-01&page=1", browsed.last())
        // opened again, the choices are not read again
        openFilters()
        assertEquals(3, choices.size)
    }

    @Test
    fun aLanguageFromItsListAServiceFromItsListAndClearFilters() {
        openMovies()
        openFilters()
        press(Key.DirectionDown, 2)
        assertFocused("language")
        assertEquals("All Languages", words("language value"))
        press(Key.DirectionCenter)
        await("language list")
        assertEquals("Original Language", words("panel title"))
        assertFocused("language: All Languages")
        // by English name: Dutch, then French
        press(Key.DirectionDown)
        assertFocused("language: Dutch")
        press(Key.DirectionCenter)
        rule.onAllNodesWithTag("language list").assertCountEquals(0)
        assertFocused("language")
        assertEquals("Dutch", words("language value"))
        // the services by TMDB's display priority: Netflix first
        press(Key.DirectionDown)
        assertFocused("filter providers")
        press(Key.DirectionCenter)
        await("provider 8")
        assertEquals("Streaming Services", words("panel title"))
        assertFocused("provider 8")
        assertEquals("Netflix", words("provider 8"))
        press(Key.DirectionCenter)
        on("provider 8")
        back()
        assertFocused("filter providers")
        assertEquals("Netflix", words("filter providers value"))
        back()
        assertFocused("filters")
        assertEquals("/api/v1/discover/movies?sortBy=popularity.desc&language=nl&watchProviders=8&watchRegion=US&page=1", browsed.last())

        // Clear Filters: the first row while any is set; focus to the first filter as it goes with them
        openFilters()
        press(Key.DirectionUp)
        assertFocused("clear filters")
        assertEquals("Clear Filters", words("clear filters"))
        press(Key.DirectionCenter)
        assertFocused("filter years")
        rule.onAllNodesWithTag("clear filters").assertCountEquals(0)
        assertEquals("Filters", words("panel title"))
        back()
        assertFocused("filters")
        assertEquals("Filters", words("filters"))
        assertEquals("/api/v1/discover/movies?sortBy=popularity.desc&page=1", browsed.last())
    }

    @Test
    fun aMoveAmongThePanelsRowsRecomposesOnlyTheTwoItTouches() {
        openMovies()
        openFilters()
        rule.waitForIdle()
        counts.clear()
        press(Key.DirectionDown)
        assertFocused("filter genres")
        rule.waitForIdle()
        val rows = counts.filterKeys { it.startsWith("row ") }
        assertEquals(setOf("row Release Date", "row Genres"), rows.keys)
        assertTrue("no card behind the panel recomposed", counts.keys.none { it.startsWith("card item") })
    }
}
