package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.RequestPage
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.RequestChange
import com.tomdebres.hither.core.rules.RequestEvent
import com.tomdebres.hither.ready.Front
import com.tomdebres.hither.ready.Notice
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.LocalRecompositionProbe
import com.tomdebres.hither.ui.home.HomeScreen
import com.tomdebres.hither.ui.home.HomeSide
import com.tomdebres.hither.ui.home.NOTICE_MS
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.roundToInt
import com.tomdebres.hither.ui.theme.Tokens
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import com.tomdebres.hither.ui.home.REQUESTS_OPENED_AFTER_MS
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The alerts in Hither (M4, DECISIONS 79): a quiet notice for each change, which takes no focus and
 * goes by itself; the count on the bar's Requests until My Requests is opened; "Waiting for
 * Approval" on My Requests for an approver only (A6); and none of it recomposing as focus moves.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AlertsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val session = Session(User(id = 8, displayName = "TV", permissions = 32), PublicSettings(applicationTitle = "Seerr", localLogin = true))
    private val counts = HashMap<String, Int>()
    private var opened = 0

    private val access = object : SeerrAccess {
        private val client = SeerrClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                val json = SeerrClient.json
                val body = when {
                    path.startsWith("/api/v1/movie/") -> json.encodeToString(MovieDetails.serializer(), MovieDetails(id = path.substringAfterLast('/').toInt(), title = "Film"))
                    path == "/api/v1/request" -> json.encodeToString(RequestPage.serializer(), RequestPage())
                    // Every row: twenty films, 1 to 20.
                    else -> json.encodeToString(
                        Page.serializer(MediaResult.serializer()),
                        Page(results = (1..20).map { MediaResult(id = it, mediaType = "movie", title = "Film $it") }),
                    )
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
            "http://seerr.test",
            AcceptAllCookiesStorage(),
        )

        override suspend fun <T> call(call: suspend (SeerrClient) -> T): T = call(client)
    }

    private fun event(change: RequestChange, id: Int) = RequestEvent(change, "movie", 500 + id, id)

    private val news = Front(
        notices = listOf(
            Notice(event(RequestChange.AVAILABLE, 1), "Ebenezer"),
            Notice(event(RequestChange.DECLINED, 2), "Godzilla Minus Zero"),
        ),
        requestsCount = 2,
        waiting = null,
    )

    private fun show(front: Front, probe: Boolean = false) {
        val side = HomeSide(front = { front }, onRequestsOpened = { opened++ })
        rule.setContent {
            CompositionLocalProvider(LocalRecompositionProbe provides if (probe) { name -> counts[name] = (counts[name] ?: 0) + 1 } else null) {
                DpadHost { HomeScreen(Screen.Home(session), access, onSignOut = {}, side = side) }
            }
        }
        await("card movie:1")
        // arrival's focus follows the page's top until a key is pressed: the top row's first card once the plan is in
        runCatching { rule.waitUntil(5_000) { rule.mainClock.advanceTimeBy(200); rule.waitForIdle(); runCatching { rule.onNode(androidx.compose.ui.test.isFocused()).fetchSemanticsNode().config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null } }.getOrNull() == "card movie:1" } }
    }

    private fun await(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    /** The text drawn inside the node tagged [tag] (a badge's words sit in a child). */
    private fun textIn(tag: String): String {
        fun collect(node: androidx.compose.ui.semantics.SemanticsNode): List<String> =
            node.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text } + node.children.flatMap(::collect)
        return collect(rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()).joinToString(" ")
    }

    private fun gone(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "a node without a tag" } ?: "a node without a tag"
    }.getOrElse { "nothing" }

    private fun assertFocused(tag: String) {
        runCatching { rule.waitUntil(3_000) { focusedTag() == tag } }
        val now = focusedTag()
        if (now != tag) throw AssertionError("expected focus on $tag, it is on $now")
    }

    @Test
    fun eachChangeIsANoticeInSeerrsWordsThatTakesNoFocusAndGoesByItself() {
        show(news)
        await("notice")
        assertEquals("Ready to Watch", textIn("notice word"))
        assertEquals("Ebenezer", textIn("notice title"))
        // Focus stays where Discover put it, and the remote moves it as ever while a notice shows.
        assertFocused("card movie:1")
        press(Key.DirectionRight)
        assertFocused("card movie:2")
        // No dismissing: the next follows by itself, then none.
        rule.mainClock.advanceTimeBy(NOTICE_MS + 1_000)
        rule.waitUntil(5_000) { runCatching { textIn("notice word") == "Declined" }.getOrDefault(false) }
        assertEquals("Godzilla Minus Zero", textIn("notice title"))
        assertFocused("card movie:2")
        rule.mainClock.advanceTimeBy(NOTICE_MS + 1_000)
        rule.waitUntil(5_000) { gone("notice") }
    }

    @Test
    fun theBarsRequestsCountsChangesUntilMyRequestsIsOpened() {
        show(news)
        await("requests count")
        assertEquals("2", textIn("requests count"))
        // Passing over Requests shows its page for a moment, and keeps the count (tvOS's 184, A2): Right onto it and Left
        // back to Discover at once. (Search and the account are icons: Requests' page stays under them, as it shows.)
        // The test's clock moves by hand here: waiting for idle would run the page's 2-second rest by itself.
        press(Key.DirectionUp)
        assertFocused("nav Discover")
        rule.mainClock.autoAdvance = false
        fun step(key: Key) {
            rule.onRoot().performKeyInput { pressKey(key) }
            rule.mainClock.advanceTimeBy(400)
        }
        step(Key.DirectionRight)
        assertEquals("on Requests' tab", "nav Requests", focusedTag())
        step(Key.DirectionLeft)
        assertEquals("back on Discover's tab", "nav Discover", focusedTag())
        rule.mainClock.advanceTimeBy(REQUESTS_OPENED_AFTER_MS + 500)
        assertEquals("passing over Requests opened it", 0, opened)
        assertEquals("2", textIn("requests count"))
        step(Key.DirectionRight)
        // Resting on its tab, its page stays: opened after the rest, and the count goes.
        assertEquals("back on Requests' tab", "nav Requests", focusedTag())
        rule.mainClock.advanceTimeBy(REQUESTS_OPENED_AFTER_MS / 2)
        assertEquals("opened before the rest was out", 0, opened)
        rule.mainClock.advanceTimeBy(REQUESTS_OPENED_AFTER_MS)
        assertEquals(1, opened)
        rule.mainClock.autoAdvance = true
        rule.waitUntil(3_000) { gone("requests count") }
        // No approver here: nothing waits.
        assertEquals(true, gone("waiting"))
    }

    @Test
    fun selectOnTheRequestsTabOpensItAtOnce() {
        show(news)
        await("requests count")
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        assertFocused("nav Requests")
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(1, opened)
        rule.waitUntil(3_000) { gone("requests count") }
        // and the rest that follows does not open it twice
        rule.mainClock.advanceTimeBy(REQUESTS_OPENED_AFTER_MS + 500)
        rule.waitForIdle()
        assertEquals(1, opened)
    }

    /**
     * A change is one capsule, top right in the safe area, sized to its words, on an opaque surface, as the Apple TV app's
     * banner (A2; tvOS's `NoticeBanner`), taking no focus.
     */
    @Test
    fun aNoticeIsOneCapsuleTopRightSizedToItsWordsOnAnOpaqueSurface() {
        show(news)
        await("notice")
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val box = rule.onNodeWithTag("notice").fetchSemanticsNode().boundsInRoot
        val d = rule.density.density
        assertEquals("its right edge on the safe area's, in dp", 48f, (root.right - box.right) / d, 1f)
        assertEquals("its top on the safe area's, in dp", 27f, box.top / d, 1f)
        assertTrue("sized to its words: ${box.width / d} dp wide", box.width / d < 200f)
        val words = rule.onNodeWithTag("notice word", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the words inside it", words.left >= box.left && words.right <= box.right)
        val map = rule.onRoot().captureToImage().toPixelMap()
        // opaque: inside its end padding, the surface's own colour, whatever is under it
        val inside = map[(box.right - 6 * d).roundToInt(), box.center.y.roundToInt()]
        assertEquals("the surface inside its padding", Tokens.Surface.red, inside.red, 0.02f)
        assertEquals("the surface inside its padding", Tokens.Surface.blue, inside.blue, 0.02f)
        // a capsule: its corner is the ground, the middle of its end the surface
        val corner = map[(box.right - 1.5f * d).roundToInt(), (box.top + 1.5f * d).roundToInt()]
        assertTrue("the capsule's corner is round, was $corner", corner.red < 0.1f)
        assertFocused("card movie:1")
    }

    /**
     * For an approver, how many wait: My Requests' "Waiting for Approval" row with its count (A6, as tvOS's), words only
     * (this account approves itself; the way into the queue is an admin kept on the TV's), never on the bar.
     */
    @Test
    fun anApproverSeesHowManyWaitForApprovalOnMyRequests() {
        show(Front(emptyList(), requestsCount = 0, waiting = 3))
        press(Key.DirectionUp)
        assertEquals("not on the bar over Discover", true, gone("waiting"))
        press(Key.DirectionRight)
        assertFocused("nav Requests")
        await("waiting")
        assertEquals("Waiting for Approval 3", textIn("waiting"))
        // words, not a stop: Down from My Requests stays on it
        press(Key.DirectionDown)
        assertFocused("side my requests")
        press(Key.DirectionDown)
        assertFocused("side my requests")
        assertEquals(true, gone("requests count"))
    }

    @Test
    fun aFocusMoveWithAlertsShowingRecomposesNeitherTheNoticeNorTheBarAndAFadeOnlyRedraws() {
        show(news.copy(waiting = 4), probe = true)
        await("notice")
        rule.runOnIdle { counts.clear() }
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        assertFocused("card movie:3")
        val moves = rule.runOnIdle { HashMap(counts) }
        val alerts = moves.filterKeys { it in setOf("notices", "TopBar", "requests count", "waiting") }
        if (alerts.isNotEmpty()) throw AssertionError("two Rights recomposed the alerts or the bar: $alerts (all: $moves)")
        // The first notice fades out and the next fades in: the queue changes once, the fades
        // only redraw the layer (a fade read in composition recomposes on every frame of it).
        rule.runOnIdle { counts.clear() }
        rule.mainClock.advanceTimeBy(NOTICE_MS + 1_000)
        rule.waitUntil(5_000) { runCatching { textIn("notice word") == "Declined" }.getOrDefault(false) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val fades = rule.runOnIdle { counts["notices"] ?: 0 }
        if (fades > 3) throw AssertionError("a notice going and the next coming recomposed the notices $fades times (all: $counts)")
    }
}
