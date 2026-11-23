package com.tomdebres.hither.ui

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.model.DiscoverSlider
import com.tomdebres.hither.core.model.IssuePage
import com.tomdebres.hither.core.model.LibraryPage
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PageInfo
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.RequestPage
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.home.HomeScreen
import com.tomdebres.hither.ui.theme.Tokens
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Discover after polish 2 (DECISIONS 131), the whole home through the real client against a server with Seerr's
 * stock Discover settings and an account that may see Recently Added: the rows in the server's order (Recently
 * Added first, then Recent Requests, Tom's pick 119) with arrival on the page's top row; See More on every linked
 * row (120), Trending's opening a grid of its titles and Upcoming Movies' the Movies page from today; Recent
 * Requests' titles on two lines. The row titles are words only (Tom, 2026-11-13, DECISIONS 135: polish 2's circled
 * arrows went); the bar's pills carry Seerr's own icons again, tested in `BarLookTest` (145).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DiscoverLinksTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val rights = Permission.REQUEST or Permission.RECENT_VIEW or Permission.VIEW_ISSUES
    private val session = Session(User(id = 8, displayName = "TV", permissions = rights), PublicSettings(applicationTitle = "Seerr", localLogin = true))
    private val browsed = CopyOnWriteArrayList<String>()
    private val longTitle = "A Title Long Enough To Need Two Lines"

    /** Seerr's defaultSliders on a new server: types 1-12 in that order. */
    private val stock = (1..12).map { DiscoverSlider(id = it, type = it, order = it - 1, isBuiltIn = true, enabled = true) }

    private fun twenty(from: Int, tv: Boolean = false) = SeerrClient.json.encodeToString(
        Page.serializer(MediaResult.serializer()),
        Page(totalPages = 5, results = (from + 1..from + 20).map { if (tv) MediaResult(id = it, mediaType = "tv", name = "Series $it") else MediaResult(id = it, mediaType = "movie", title = "Film $it") }),
    )

    private fun pageOf(query: String) = (Regex("page=(\\d+)").find(query)?.groupValues?.get(1)?.toInt() ?: 1) - 1

    private val access = object : SeerrAccess {
        private val client = SeerrClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                val query = request.url.encodedQuery
                val json = SeerrClient.json
                val body = when {
                    path == "/api/v1/settings/public" -> json.encodeToString(PublicSettings.serializer(), session.settings)
                    path == "/api/v1/settings/discover" -> json.encodeToString(ListSerializer(DiscoverSlider.serializer()), stock)
                    path == "/api/v1/auth/me" -> json.encodeToString(User.serializer(), session.user)
                    path == "/api/v1/media" -> json.encodeToString(
                        LibraryPage.serializer(),
                        LibraryPage(PageInfo(results = 3), (1..3).map { MediaInfo(mediaType = "movie", tmdbId = 800 + it, status = 5) }),
                    )
                    path == "/api/v1/request" -> json.encodeToString(
                        RequestPage.serializer(),
                        RequestPage(
                            PageInfo(results = 2),
                            listOf(901, 902).map { id ->
                                MediaRequest(id = id, status = RequestStatus.PENDING, type = "movie", createdAt = "2026-11-12T20:00:00.000Z", media = MediaInfo(mediaType = "movie", tmdbId = id, status = 2), requestedBy = User(id = 8, displayName = "TV"))
                            },
                        ),
                    )
                    path == "/api/v1/issue" -> json.encodeToString(IssuePage.serializer(), IssuePage())
                    path.startsWith("/api/v1/movie/") -> {
                        val id = path.substringAfterLast('/').toInt()
                        json.encodeToString(MovieDetails.serializer(), MovieDetails(id = id, title = if (id == 901) longTitle else "Film $id", releaseDate = "2026-01-01"))
                    }
                    // the Movies page's own calls carry a sort
                    path == "/api/v1/discover/movies" && "sortBy=" in query -> {
                        browsed += "$path?$query"
                        twenty(1000)
                    }
                    path == "/api/v1/discover/trending" -> twenty(500 + pageOf(query) * 20)
                    path == "/api/v1/discover/movies/upcoming" -> twenty(600 + pageOf(query) * 20)
                    path == "/api/v1/discover/movies" -> twenty(300 + pageOf(query) * 20)
                    else -> twenty(400 + pageOf(query) * 20, tv = true)
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
            "http://seerr.test",
            AcceptAllCookiesStorage(),
        )

        override suspend fun <T> call(call: suspend (SeerrClient) -> T): T = call(client)
    }

    private fun show() {
        rule.setContent { DpadHost { HomeScreen(Screen.Home(session), access, onSignOut = {}) } }
        await("card movie:801")
        await("recent request:901")
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    private fun await(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
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

    private fun top(tag: String) = rule.onNodeWithTag(tag).getBoundsInRoot().top.value

    @Test
    fun recentlyAddedHeadsThePageInTheServersOrderAndArrivalIsOnIt() {
        show()
        // Trending, under Recent Requests, is below the screen with A3's taller rows (not composed): Down reaches it
        assertTrue("Recently Added above Recent Requests", top("row RECENTLY_ADDED") < top("recent requests"))
        assertFocused("card movie:801")
        press(Key.DirectionUp)
        assertFocused("nav Discover")
        press(Key.DirectionDown)
        assertFocused("card movie:801")
        press(Key.DirectionDown)
        assertFocused("recent request:901")
        press(Key.DirectionDown)
        assertTrue("Recent Requests above Trending", top("recent requests") < top("row TRENDING"))
        press(Key.DirectionUp)
        assertFocused("recent request:901")
        // Up from Recent Requests is the row above it now, not the bar
        press(Key.DirectionUp)
        assertFocused("card movie:801")
    }

    /**
     * Nothing is drawn on [row]'s title line after its words: in the band of the title's own height, from 2 dp past
     * its last letter to 64 dp on (where polish 2's 24 dp arrow sat, 8 dp after the words), no pixel is lighter
     * than the page. A shadow or the bar's fade is darker, never lighter.
     */
    private fun assertTitleIsWordsOnly(row: String, title: String) {
        val words = rule.onNode(hasText(title) and hasAnyAncestor(hasTestTag("row $row")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val px = rule.onRoot().captureToImage().toPixelMap()
        val dp = rule.density.density
        val from = (words.right + 2 * dp).toInt()
        val to = minOf((words.right + 64 * dp).toInt(), px.width)
        val lit = (from until to).sumOf { x -> (words.top.toInt() until words.bottom.toInt()).count { y -> lighter(px[x, y], Tokens.Background) >= 6 } }
        assertEquals("$title: pixels drawn after the title's words", 0, lit)
    }

    /** How much lighter [a] is than [b] on its least-lighter channel, in 0-255 levels. */
    private fun lighter(a: Color, b: Color): Int =
        minOf(((a.red - b.red) * 255).toInt(), ((a.green - b.green) * 255).toInt(), ((a.blue - b.blue) * 255).toInt())

    @Test
    fun theLinkedRowsTitlesAreWordsOnly() {
        show()
        // Trending, then Popular Movies, each on the focus line (the rows below the screen come up with focus)
        press(Key.DirectionDown, 2)
        assertFocused("card movie:501")
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertTitleIsWordsOnly("TRENDING", "Trending")
        press(Key.DirectionDown)
        assertFocused("card movie:301")
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertTitleIsWordsOnly("POPULAR_MOVIES", "Popular Movies")
    }

    @Test
    fun trendingsSeeMoreOpensItsGridAndBackReturnsToTheCard() {
        show()
        press(Key.DirectionDown, 2)
        assertFocused("card movie:501")
        press(Key.DirectionRight, 20)
        assertFocused("see more TRENDING")
        press(Key.DirectionCenter)
        await("grid heading")
        rule.onNodeWithTag("grid heading").assertTextEquals("Trending")
        await("card movie:501")
        back()
        assertFocused("see more TRENDING")
    }

    @Test
    fun upcomingMoviesSeeMoreOpensTheMoviesPageFromToday() {
        show()
        // Recently Added, Recent Requests, Trending, Popular Movies, then Upcoming Movies (Seerr's stock order)
        press(Key.DirectionDown, 4)
        assertFocused("card movie:601")
        press(Key.DirectionRight, 20)
        assertFocused("see more UPCOMING_MOVIES")
        press(Key.DirectionCenter)
        await("browse heading")
        rule.onNodeWithTag("browse heading").assertTextEquals("Upcoming Movies")
        rule.waitUntil(5_000) { browsed.isNotEmpty() }
        assertTrue("the page asks from today (${browsed.last()})", "primaryReleaseDateGte=${LocalDate.now()}" in browsed.last())
        back()
        assertFocused("see more UPCOMING_MOVIES")
    }

    /**
     * The bar reads Discover, Requests, Issues, search, the account (151; A2), and Right walks it in that order, each tab
     * showing its page as focus passes.
     */
    @Test
    fun theBarReadsDiscoverRequestsIssuesSearchAvatarAndRightWalksIt() {
        show()
        // Issues after Requests again (Tom, 2026-11-14, DECISIONS 151, undoing 144's order)
        val order = listOf("nav Discover", "nav Requests", "nav Issues", "search", "avatar")
        val box = order.associateWith { rule.onNodeWithTag(it).getBoundsInRoot() }
        assertEquals("the bar, left to right", order, order.sortedBy { box.getValue(it).left.value })
        val search = box.getValue("search")
        val issues = box.getValue("nav Issues")
        // the Apple TV app's bar since A2: centred, its controls 8 dp apart (TabsTest holds the centring)
        assertEquals("from Issues to search, in dp", 8f, (search.left - issues.right).value, 1f)
        assertEquals("from search to the account, in dp", 8f, (box.getValue("avatar").left - search.right).value, 1f)
        Log.i("HitherBar", "search pill ${(search.right - search.left).value} dp wide, Issues ${(issues.right - issues.left).value} dp")
        press(Key.DirectionUp)
        assertFocused("nav Discover")
        for (tag in order.drop(1)) {
            press(Key.DirectionRight)
            assertFocused(tag)
        }
        press(Key.DirectionRight)
        assertFocused("avatar")
    }

    @Test
    fun aRecentRequestsTitleIsOneLineUnderItsBackdrop() {
        // A3 (DECISIONS 325): the caption under a 205 dp backdrop holds about 28 characters a line, where the card's two
        // lines held about 16 each (polish 2's item 5); one line, as tvOS's, cut with an ellipsis past that
        show()
        // Recently Added heads the page here: Recent Requests comes up to the line under it
        press(Key.DirectionDown)
        assertFocused("recent request:901")
        val title = rule.onNode(hasTestTag("recent title") and hasAnyAncestor(hasTestTag("lockup recent request:901")), useUnmergedTree = true).fetchSemanticsNode()
        val layout = mutableListOf<TextLayoutResult>().also { title.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(it) }.single()
        assertEquals("the title's lines", 1, layout.lineCount)
        assertEquals("all of it, for TalkBack", longTitle, layout.layoutInput.text.text)
        val picture = rule.onNode(hasTestTag("recent request:901")).fetchSemanticsNode().boundsInRoot
        assertTrue("the title is under the backdrop (${title.boundsInRoot}, picture $picture)", title.boundsInRoot.top >= picture.bottom)
    }

}
