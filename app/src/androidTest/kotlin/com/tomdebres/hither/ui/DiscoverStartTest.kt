package com.tomdebres.hither.ui

import com.tomdebres.hither.core.rules.ImageSource
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PageInfo
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.RequestPage
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.DiscoverPlan
import com.tomdebres.hither.core.rules.DiscoverRow
import com.tomdebres.hither.core.rules.RequestTitle
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.discover.DiscoverModel
import com.tomdebres.hither.ui.discover.DiscoverPage
import com.tomdebres.hither.ui.discover.DiscoverScreen
import com.tomdebres.hither.ui.discover.DiscoverSource
import com.tomdebres.hither.ui.discover.PlanSource
import com.tomdebres.hither.ui.requests.RequestsModel
import com.tomdebres.hither.ui.requests.RequestsSource
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Discover's start (DECISIONS 317). As placeholders give way to cards, Compose takes focus again by itself and can put
 * it a row down; a card that takes focus then is not the user's use of its row. So, until the first key press, focus put
 * on another row comes back to the top row's first card; after it, the user's moves stay. And while focus is on the top
 * row, a page left scrolled when its scroll stops goes back to its top, where the top row rests (40), so the bar is back
 * over it (A2). The stray focus and the cut-short scroll are asked for outright here, so the test does not wait on the
 * race that makes them.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DiscoverStartTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val session = Session(User(id = 8, displayName = "TV", permissions = 32), PublicSettings(applicationTitle = "Seerr", localLogin = true))

    private val rows = DiscoverSource { row, _ ->
        DiscoverPage(ImageSource.unknown("http://seerr.test"), Page(results = (1..8).map { MediaResult(id = row.ordinal * 100 + it, mediaType = "movie", title = "Film") }))
    }

    private fun show(plan: CompletableDeferred<DiscoverPlan>) {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { DiscoverModel(rows, session, scope, plans = PlanSource { plan.await() }) }
            DpadHost {
                Box(Modifier.fillMaxSize().testTag("stage")) {
                    DiscoverScreen(model, onOpen = {}, recent = null)
                }
            }
        }
    }

    private fun await(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun settle(ms: Long = 800) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "?" } ?: "?"
    }.getOrElse { "nothing" }

    private fun awaitFocus(tag: String, what: String = "") {
        val held = runCatching { rule.waitUntil(5_000) { settle(); focusedTag() == tag } }.isSuccess
        assertTrue("${what}focus is on ${focusedTag()}, not $tag", held)
    }

    private fun card(row: DiscoverRow, n: Int) = "card movie:${row.ordinal * 100 + n}"

    /** The top of [tag]'s card on the screen, in dp. */
    private fun topOf(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y / rule.density.density

    @Test
    fun beforeAnyKeyFocusPutOnAnotherRowComesBackToTheTopRow() {
        show(CompletableDeferred(DiscoverPlan(recentRequests = false, rows = listOf(DiscoverRow.TRENDING, DiscoverRow.POPULAR_MOVIES, DiscoverRow.POPULAR_SERIES))))
        await(card(DiscoverRow.TRENDING, 1))
        awaitFocus(card(DiscoverRow.TRENDING, 1), "arrival: ")
        val line = topOf(card(DiscoverRow.TRENDING, 1))
        // focus put a row down, as Compose puts it when a focused placeholder goes
        await(card(DiscoverRow.POPULAR_MOVIES, 1))
        rule.onNodeWithTag(card(DiscoverRow.POPULAR_MOVIES, 1)).performSemanticsAction(SemanticsActions.RequestFocus)
        awaitFocus(card(DiscoverRow.TRENDING, 1), "before any key, focus put on Popular Movies: ")
        assertEquals("the top row back on its line, in dp", line, topOf(card(DiscoverRow.TRENDING, 1)), 2f)
        // a key press ends the start: the user's move stays where it went
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        awaitFocus(card(DiscoverRow.POPULAR_MOVIES, 1), "after a Down: ")
        settle(1_500)
        assertEquals("the user's move stays", card(DiscoverRow.POPULAR_MOVIES, 1), focusedTag())
    }

    @Test
    fun withFocusOnTheTopRowAPageLeftScrolledGoesBackToItsTop() {
        show(CompletableDeferred(DiscoverPlan(recentRequests = false, rows = listOf(DiscoverRow.TRENDING, DiscoverRow.POPULAR_MOVIES, DiscoverRow.POPULAR_SERIES))))
        await(card(DiscoverRow.TRENDING, 1))
        awaitFocus(card(DiscoverRow.TRENDING, 1))
        val line = topOf(card(DiscoverRow.TRENDING, 1))
        // a scroll cut short, as a row coming or going above leaves it: the page 60 dp down
        rule.onNodeWithTag("rows").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 60f * rule.density.density) }
        settle(1_500)
        assertEquals("the top row, focused, back on its line once the scroll stopped, in dp", line, topOf(card(DiscoverRow.TRENDING, 1)), 2f)
        assertEquals("focus where it was", card(DiscoverRow.TRENDING, 1), focusedTag())
    }

    // ---- a placeholder hands focus straight on (A3, DECISIONS 326) ---------------------------------------------------

    /** The requests list, its first page waiting on [answer]. */
    private class Requests(private val answer: CompletableDeferred<RequestPage>) : RequestsSource {
        override suspend fun page(skip: Int, take: Int): RequestPage = if (skip == 0) answer.await() else RequestPage()
        override suspend fun title(type: MediaType, tmdbId: Int) = RequestTitle("Film $tmdbId", "2026", null, null)
    }

    private fun request(id: Int) = MediaRequest(
        id = id,
        status = RequestStatus.APPROVED,
        type = "movie",
        createdAt = "2026-11-10T09:00:00.000Z",
        media = MediaInfo(mediaType = "movie", tmdbId = id * 100, status = 3),
    )

    /** Hither's own rows (no plan), [source] for them, and Recent Requests from [requests] where there is one. */
    private fun showWithout(source: DiscoverSource, requests: CompletableDeferred<RequestPage>?) {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { DiscoverModel(source, session, scope) }
            val recent = remember { requests?.let { RequestsModel(Requests(it), scope) } }
            if (recent != null) LaunchedEffect(recent) { recent.open() }
            DpadHost {
                Box(Modifier.fillMaxSize().testTag("stage")) {
                    DiscoverScreen(model, onOpen = {}, recent = recent)
                }
            }
        }
    }

    /** Every tag focus is on, frame by frame, from [then] until [frames] frames on. */
    private fun focusFrameByFrame(frames: Int, then: () -> Unit): List<String> {
        rule.mainClock.autoAdvance = false
        val seen = mutableListOf(focusedTag())
        then()
        repeat(frames) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            seen += focusedTag()
        }
        rule.mainClock.autoAdvance = true
        return seen
    }

    @Test
    fun recentRequestsPlaceholdersHandFocusStraightToTheFirstLockup() {
        // As Discover starts on an account with requests: the placeholders hold focus, the rows under them are in.
        val answer = CompletableDeferred<RequestPage>()
        showWithout(rows, answer)
        await("recent loading")
        await(card(DiscoverRow.TRENDING, 1))
        awaitFocus("recent loading")
        val seen = focusFrameByFrame(30) { answer.complete(RequestPage(PageInfo(results = 2), listOf(request(5), request(4)))) }
        // Removed as the lockups came, the placeholders took focus with them, and Compose put it on Trending's first
        // lockup (down from the screen's top) until the hand-off brought it back a frame or more later.
        val stray = seen.filter { it != "recent loading" && it != "recent request:5" }
        assertTrue("focus went elsewhere on the way to the first lockup: $seen", stray.isEmpty())
        assertEquals("focus ends on the first lockup ($seen)", "recent request:5", seen.last())
    }

    @Test
    fun aRowsPlaceholdersHandFocusStraightToItsFirstLockup() {
        // As Discover starts without requests: Trending's placeholders hold focus, the rows under it are in.
        val trending = CompletableDeferred<DiscoverPage>()
        showWithout(DiscoverSource { row, page -> if (row == DiscoverRow.TRENDING) trending.await() else rows.page(row, page) }, null)
        await("notice TRENDING")
        await(card(DiscoverRow.POPULAR_MOVIES, 1))
        awaitFocus("notice TRENDING")
        val seen = focusFrameByFrame(30) { trending.complete(kotlinx.coroutines.runBlocking { rows.page(DiscoverRow.TRENDING, 1) }) }
        val stray = seen.filter { it != "notice TRENDING" && it != card(DiscoverRow.TRENDING, 1) }
        assertTrue("focus went elsewhere on the way to the first lockup: $seen", stray.isEmpty())
        assertEquals("focus ends on the first lockup ($seen)", card(DiscoverRow.TRENDING, 1), seen.last())
    }
}
