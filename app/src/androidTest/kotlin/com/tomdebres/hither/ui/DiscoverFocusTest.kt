package com.tomdebres.hither.ui

import com.tomdebres.hither.core.model.RequestPage
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.DiscoverRow
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.ShowsFocus
import com.tomdebres.hither.ui.discover.DiscoverModel
import com.tomdebres.hither.ui.discover.DiscoverPage
import com.tomdebres.hither.ui.discover.DiscoverScreen
import com.tomdebres.hither.ui.discover.DiscoverSource
import com.tomdebres.hither.ui.home.HomeScreen
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Discover's focus rules (`docs/design/seerr-reference.md`, 2.3 and 2.6; REVIEW.md, "a D-pad
 * trap"), on a device: the D-pad walks real rows of cards from a scripted server. Each row has
 * 20 cards and no further page, so its end is reachable.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DiscoverFocusTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val base = "http://seerr.test"
    private val session = Session(User(id = 8, displayName = "TV", permissions = 32), PublicSettings(applicationTitle = "Seerr", localLogin = true))
    private val showsFocus = SemanticsMatcher.expectValue(ShowsFocus, true)

    /** Row n's cards are films n*100+1 .. n*100+[count], with no posters (nothing to fetch). */
    private fun page(row: DiscoverRow, count: Int = 20) = DiscoverPage(
        base,
        Page(
            page = 1,
            totalPages = 1,
            results = (1..count).map { MediaResult(id = row.ordinal * 100 + it, mediaType = "movie", title = "Film ${row.ordinal * 100 + it}") },
        ),
    )

    private fun card(row: DiscoverRow, n: Int) = "card movie:${row.ordinal * 100 + n}"

    private fun show(source: DiscoverSource = DiscoverSource { row, _ -> page(row) }) {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { DiscoverModel(source, session, scope) }
            DpadHost { DiscoverScreen(model, onOpen = {}) }
        }
        rule.waitForIdle()
    }

    /** The two rows on screen at first; the others are composed as the page scrolls to them. */
    private fun awaitAllRows() = listOf(DiscoverRow.TRENDING, DiscoverRow.POPULAR_MOVIES).forEach { awaitCard(card(it, 1)) }

    /** The rows' pages are read off the main thread, which waitForIdle does not wait for. */
    private fun awaitCard(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    /** Which node has focus, by its tag: a failure says where focus went instead. */
    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "a node without a tag" } ?: "a node without a tag"
    }.getOrElse { "nothing (${it.message?.lineSequence()?.firstOrNull()})" }

    private fun assertFocused(tag: String) {
        val now = focusedTag()
        if (now != tag) throw AssertionError("expected focus on $tag, it is on $now")
    }

    /** Lets the focus animation and any scroll finish, so positions are compared at rest. */
    private fun settle() {
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    /** Back, as the remote's Back reaches the activity. */
    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    /** The focused node's place on the screen, in dp, once it is at rest. */
    private fun focusedBounds(): Rect = settle().let { rule.onNode(isFocused()).getBoundsInRoot() }.let {
        Rect(it.left.value, it.top.value, it.right.value, it.bottom.value)
    }

    @Test
    fun focusArrivesOnTheFirstCardOfTrendingAndShowsIt() {
        show()
        awaitCard(card(DiscoverRow.TRENDING, 1))
        assertFocused(card(DiscoverRow.TRENDING, 1))
        rule.onNodeWithTag(card(DiscoverRow.TRENDING, 1)).assert(showsFocus)
    }

    @Test
    fun leftAtTheFirstCardAndRightAtTheLastStayInTheRow() {
        show()
        awaitAllRows()
        press(Key.DirectionLeft)
        assertFocused(card(DiscoverRow.TRENDING, 1))
        // Trending is a linked row: its 20 end with See More, the row's last stop (polish 2, DECISIONS 131)
        press(Key.DirectionRight, 25)
        assertFocused("see more TRENDING")
        press(Key.DirectionRight)
        assertFocused("see more TRENDING")
    }

    @Test
    fun theFocusedCardKeepsToTheFirstColumnAsTheRowMoves() {
        show()
        awaitAllRows()
        val first = focusedBounds()
        press(Key.DirectionRight, 6)
        assertFocused(card(DiscoverRow.TRENDING, 7))
        val seventh = focusedBounds()
        assertEquals("the row moved under a still focus", first.left, seventh.left, 2f)
    }

    @Test
    fun downBringsTheNextRowUpToTheSameLine() {
        show()
        awaitAllRows()
        val line = focusedBounds().top
        press(Key.DirectionDown)
        assertFocused(card(DiscoverRow.POPULAR_MOVIES, 1))
        assertEquals(line, focusedBounds().top, 2f)
        press(Key.DirectionDown)
        assertFocused(card(DiscoverRow.POPULAR_SERIES, 1))
        assertEquals(line, focusedBounds().top, 2f)
        press(Key.DirectionDown)
        assertFocused(card(DiscoverRow.UPCOMING_MOVIES, 1))
        assertEquals(line, focusedBounds().top, 2f)
        // the last row too (Upcoming Series, DECISIONS 84)
        press(Key.DirectionDown)
        assertFocused(card(DiscoverRow.UPCOMING_SERIES, 1))
        assertEquals(line, focusedBounds().top, 2f)
        press(Key.DirectionDown)
        assertFocused(card(DiscoverRow.UPCOMING_SERIES, 1))
    }

    @Test
    fun upAndDownReturnToTheCardLeftInEachRow() {
        show()
        awaitAllRows()
        press(Key.DirectionRight, 4) // Trending, fifth card
        press(Key.DirectionDown)
        press(Key.DirectionRight, 9) // Popular Movies, tenth card
        press(Key.DirectionUp)
        assertFocused(card(DiscoverRow.TRENDING, 5))
        press(Key.DirectionDown)
        assertFocused(card(DiscoverRow.POPULAR_MOVIES, 10))
    }

    @Test
    fun aRowLeftAtItsEndIsComeBackToAtItsEnd() {
        // At its end a row cannot keep the card in the first column, so the way back is the
        // remembered card, not the one nearest on the screen. Popular Movies ends with See More
        // (DECISIONS 112), which is its end.
        show()
        awaitAllRows()
        press(Key.DirectionDown)
        press(Key.DirectionRight, 25)
        assertFocused("see more POPULAR_MOVIES")
        press(Key.DirectionDown)
        assertFocused(card(DiscoverRow.POPULAR_SERIES, 1))
        press(Key.DirectionUp)
        assertFocused("see more POPULAR_MOVIES")
    }

    @Test
    fun upFromTheFirstRowReachesTheBarAndDownComesBackToTheCard() {
        show()
        awaitAllRows()
        press(Key.DirectionRight, 3)
        press(Key.DirectionUp)
        assertFocused("nav Discover")
        rule.onNode(hasTestTag("nav Discover")).assert(showsFocus)
        press(Key.DirectionDown)
        assertFocused(card(DiscoverRow.TRENDING, 4))
    }

    @Test
    fun rightAtTheEndOfAShortRowDoesNotDropToTheRowBelow() {
        // Three cards end mid-screen; the row below has cards further right, which a search
        // to the right would otherwise take.
        show { row, _ -> page(row, count = if (row == DiscoverRow.TRENDING) 3 else 20) }
        awaitAllRows()
        press(Key.DirectionRight, 3)
        assertFocused(card(DiscoverRow.TRENDING, 3))
        press(Key.DirectionRight)
        assertFocused(card(DiscoverRow.TRENDING, 3))
    }

    @Test
    fun theBarIsFencedAtBothEnds() {
        show()
        awaitAllRows()
        press(Key.DirectionUp)
        press(Key.DirectionLeft)
        assertFocused("nav Discover")
        press(Key.DirectionRight, 6)
        assertFocused("avatar")
        rule.onNode(hasTestTag("avatar")).assert(showsFocus)
    }

    @Test
    fun aRowStillLoadingHoldsFocusAndPassesItToItsFirstCard() {
        val trending = CompletableDeferred<DiscoverPage>()
        show { row, _ -> if (row == DiscoverRow.TRENDING) trending.await() else page(row) }
        assertFocused("notice TRENDING")
        rule.onNodeWithTag("notice TRENDING").assert(showsFocus)
        // the other rows did not wait for it
        awaitCard(card(DiscoverRow.POPULAR_MOVIES, 1))

        trending.complete(page(DiscoverRow.TRENDING))
        awaitCard(card(DiscoverRow.TRENDING, 1))
        assertFocused(card(DiscoverRow.TRENDING, 1))
        rule.onNodeWithTag(card(DiscoverRow.TRENDING, 1)).assert(showsFocus)
    }

    @Test
    fun anEmptyRowIsNotAStop() {
        show { row, _ -> page(row, count = if (row == DiscoverRow.POPULAR_MOVIES) 0 else 20) }
        awaitCard(card(DiscoverRow.TRENDING, 1))
        rule.onNodeWithTag("notice POPULAR_MOVIES").assertExists()
        press(Key.DirectionDown)
        assertFocused(card(DiscoverRow.POPULAR_SERIES, 1))
        press(Key.DirectionUp)
        assertFocused(card(DiscoverRow.TRENDING, 1))
    }

    @Test
    fun aFailedRowOffersTryAgainAndSelectAsksOnce() {
        val asked = AtomicInteger()
        show { row, _ ->
            if (row == DiscoverRow.TRENDING && asked.incrementAndGet() == 1) throw SeerrException.Http(500, null)
            page(row)
        }
        assertFocused("notice TRENDING")
        rule.onNodeWithTag("try again TRENDING", useUnmergedTree = true).assertExists()

        press(Key.DirectionCenter)
        awaitCard(card(DiscoverRow.TRENDING, 1))
        assertFocused(card(DiscoverRow.TRENDING, 1))
        assertEquals(2, asked.get())
    }

    @Test
    fun aLaterPageThatFailsEndsTheRowWithTryAgainThatKeepsFocusAndHandsItOn() {
        val second = AtomicInteger()
        val retried = CompletableDeferred<DiscoverPage>()
        show { row, number ->
            when {
                row != DiscoverRow.TRENDING -> page(row)
                number == 1 -> DiscoverPage(base, Page(page = 1, totalPages = 2, results = (1..8).map { MediaResult(id = it, mediaType = "movie", title = "Film $it") }))
                second.incrementAndGet() == 1 -> throw SeerrException.Unreachable(RuntimeException("down"))
                else -> retried.await()
            }
        }
        awaitAllRows()
        // along the row: page 2 is asked for near the end, and fails; Try Again ends the row
        press(Key.DirectionRight, 7)
        awaitCard("retry more")
        press(Key.DirectionRight)
        rule.waitUntil(3_000) { focusedTag() == "retry more" }
        assertFocused("retry more")
        press(Key.DirectionCenter)
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { second.get() == 2 }
        // out: the card stays, reading "Trying…", and keeps focus; the repeated Select asked nothing
        rule.onNodeWithTag("retry more", useUnmergedTree = true).assertExists()
        assertFocused("retry more")
        retried.complete(DiscoverPage(base, Page(page = 2, totalPages = 2, results = (9..12).map { MediaResult(id = it, mediaType = "movie", title = "Film $it") })))
        awaitCard(card(DiscoverRow.TRENDING, 9))
        // the page has come: focus is on its first card, where Try Again was
        rule.waitUntil(3_000) { focusedTag() == card(DiscoverRow.TRENDING, 9) }
        assertFocused(card(DiscoverRow.TRENDING, 9))
        rule.onNodeWithTag(card(DiscoverRow.TRENDING, 9)).assert(showsFocus)
        assertEquals(2, second.get())
    }

    /** The whole home, through the real client against a scripted server. */
    @Test
    fun selectOnACardOpensItsPageAndBackReturnsToThatCard() {
        val client = SeerrClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                val json = SeerrClient.json
                val body = when {
                    // the card's page: the title, the server's switches and the user, read together
                    path.startsWith("/api/v1/movie/") -> json.encodeToString(
                        MovieDetails.serializer(),
                        MovieDetails(id = path.substringAfterLast('/').toInt(), title = "Film", overview = "About it."),
                    )
                    path == "/api/v1/settings/public" -> json.encodeToString(PublicSettings.serializer(), session.settings)
                    path == "/api/v1/auth/me" -> json.encodeToString(User.serializer(), session.user)
                    // My Requests' list, read as Home starts for Recent Requests: none here
                    path == "/api/v1/request" -> json.encodeToString(RequestPage.serializer(), RequestPage())
                    else -> {
                        val row = when (path) {
                            "/api/v1/discover/trending" -> DiscoverRow.TRENDING
                            "/api/v1/discover/movies" -> DiscoverRow.POPULAR_MOVIES
                            "/api/v1/discover/tv" -> DiscoverRow.POPULAR_SERIES
                            "/api/v1/discover/tv/upcoming" -> DiscoverRow.UPCOMING_SERIES
                            else -> DiscoverRow.UPCOMING_MOVIES
                        }
                        json.encodeToString(Page.serializer(MediaResult.serializer()), page(row).page)
                    }
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
            base,
            AcceptAllCookiesStorage(),
        )
        val access = object : SeerrAccess {
            override suspend fun <T> call(call: suspend (SeerrClient) -> T): T = call(client)
        }
        rule.setContent { DpadHost { HomeScreen(Screen.Home(session), access, onSignOut = {}) } }
        rule.waitForIdle()
        awaitAllRows()
        assertFocused(card(DiscoverRow.TRENDING, 1))

        press(Key.DirectionDown)
        press(Key.DirectionRight, 2)
        assertFocused(card(DiscoverRow.POPULAR_MOVIES, 3))
        press(Key.DirectionCenter)
        rule.onNodeWithTag("media title").assertExists()
        awaitCard("action Request")
        runCatching { rule.waitUntil(3_000) { focusedTag() == "action Request" } }
        assertFocused("action Request")

        back()
        assertFocused(card(DiscoverRow.POPULAR_MOVIES, 3))
        rule.onNodeWithTag(card(DiscoverRow.POPULAR_MOVIES, 3)).assert(showsFocus)
    }

    @Test
    fun backFromTheRowsGoesToTheBarAndTheTopOfThePage() {
        show()
        awaitAllRows()
        val line = focusedBounds().top
        press(Key.DirectionDown, 2)
        back()
        assertFocused("nav Discover")
        press(Key.DirectionDown)
        // the page is back at the top: the first row is under the bar again
        assertFocused(card(DiscoverRow.TRENDING, 1))
        assertEquals(line, focusedBounds().top, 2f)
    }

    private fun assertEquals(expected: Float, actual: Float, delta: Float) = assertEquals(null, expected, actual, delta)

    private fun assertEquals(message: String?, expected: Float, actual: Float, delta: Float) {
        if (abs(expected - actual) > delta) throw AssertionError("${message ?: ""} expected $expected, was $actual")
    }
}
