package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.SeerrClient
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
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What a press did shows in place, in the whole home (A5; tvOS's D4): a cancelled request's lockup goes and nothing says
 * so as a toast (until A5 the home said "Request for … cancelled." in its corner); a cancel that does not go through is a
 * dialog with OK, in tvOS's words, and focus comes back to the lockup. Through the real client, against a scripted
 * server.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OutcomesInPlaceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val session = Session(User(id = 8, displayName = "TV", permissions = 32), PublicSettings(applicationTitle = "Seerr", localLogin = true))
    private val cancelled = CopyOnWriteArrayList<Int>()

    /** The server answers a cancel with a 500. */
    @Volatile private var cancelFails = false

    /** The user's own requests: two pending films, 5 and 6, until cancelled. */
    private fun mine() = listOf(5, 6).filter { it !in cancelled }.map { id ->
        MediaRequest(id = id, status = RequestStatus.PENDING, type = "movie", createdAt = "2026-11-10T09:00:00.000Z", requestedBy = User(id = 8, displayName = "TV"), media = MediaInfo(mediaType = "movie", tmdbId = id))
    }

    private val access = object : SeerrAccess {
        private val client = SeerrClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                val json = SeerrClient.json
                val ok = headersOf(HttpHeaders.ContentType, "application/json")
                if (request.method == HttpMethod.Delete && path.startsWith("/api/v1/request/")) {
                    if (cancelFails) return@MockEngine respond("""{"message":"boom"}""", HttpStatusCode.InternalServerError, ok)
                    cancelled += path.substringAfterLast('/').toInt()
                    return@MockEngine respond("", HttpStatusCode.NoContent, ok)
                }
                val body = when {
                    path.startsWith("/api/v1/movie/") -> json.encodeToString(MovieDetails.serializer(), MovieDetails(id = path.substringAfterLast('/').toInt(), title = "Film ${path.substringAfterLast('/')}"))
                    // My Requests (by the user); Discover's Recent Requests (everyone's) stays empty
                    path == "/api/v1/request" && request.url.parameters["requestedBy"] != null ->
                        json.encodeToString(RequestPage.serializer(), RequestPage(PageInfo(results = mine().size), mine()))
                    path == "/api/v1/request" -> json.encodeToString(RequestPage.serializer(), RequestPage())
                    else -> json.encodeToString(
                        Page.serializer(MediaResult.serializer()),
                        Page(results = (1..20).map { MediaResult(id = it, mediaType = "movie", title = "Film $it") }),
                    )
                }
                respond(body, HttpStatusCode.OK, ok)
            },
            "http://seerr.test",
            AcceptAllCookiesStorage(),
        )

        override suspend fun <T> call(call: suspend (SeerrClient) -> T): T = call(client)
    }

    private fun await(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    /** Select held a second (a test's key input repeats it from 500 ms), then let go: the long press. */
    private fun hold() {
        rule.onRoot().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_000)
            keyUp(Key.DirectionCenter)
        }
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
        .flatMap { it.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty() }
        .joinToString("") { it.text }

    /** Discover, then the bar's Requests, Down to My Requests and Right into the lockups: Film 5's lockup, asked to cancel. */
    private fun askToCancelFilm5() {
        toConfirmFilm5()
        press(Key.DirectionCenter)
    }

    /** As [askToCancelFilm5], up to the question's Cancel Request focused, not yet pressed. */
    private fun toConfirmFilm5() {
        rule.setContent { DpadHost { HomeScreen(Screen.Home(session), access, onSignOut = {}) } }
        await("card movie:1")
        runCatching { rule.waitUntil(5_000) { rule.mainClock.advanceTimeBy(200); rule.waitForIdle(); focusedTag() == "card movie:1" } }
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        assertFocused("nav Requests")
        await("request request:5")
        press(Key.DirectionDown)
        assertFocused("side my requests")
        press(Key.DirectionRight)
        assertFocused("request request:5")
        hold()
        await("request menu")
        assertFocused("cancel request:5")
        press(Key.DirectionCenter)
        await("confirm cancel request")
        press(Key.DirectionUp)
        assertFocused("confirm")
    }

    /** With the clock stopped: whatever the server and the page have to do now, then [frames] frames. */
    private fun settle(frames: Int) {
        repeat(3) {
            Thread.sleep(150)
            rule.waitForIdle()
        }
        repeat(frames) { rule.mainClock.advanceTimeByFrame() }
        rule.waitForIdle()
    }

    private fun shown(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** What the corner of the screen says over the next few seconds: no toast, whatever its tag. */
    private fun assertNoToastFor(ms: Long) {
        var waited = 0L
        while (waited < ms) {
            rule.mainClock.advanceTimeBy(250)
            rule.waitForIdle()
            for (tag in listOf("cancel", "outcome", "approval", "issue")) {
                assertEquals("a toast tagged $tag: ${words(tag)}", 0, rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size)
            }
            waited += 250
        }
    }

    @Test
    fun aCancelledRequestsLockupGoesAndNoToastSaysSo() {
        askToCancelFilm5()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("request request:5").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(5), cancelled.toList())
        // focus moved on to the lockup that took its place: what the cancel did is that the lockup went
        assertFocused("request request:6")
        assertNoToastFor(2_000)
    }

    /**
     * A9 (the review of A5's merged code): a cancel's failure waits for another lockup's menu, opened in the moment
     * before its dialog shows, to go; its OK then gives focus back to the lockup it was about with no menu left open.
     * Before, the dialog came over the menu, and its OK moved focus to the lockup past the menu still drawn.
     */
    @Test
    fun aCancelsFailureWaitsForAnotherLockupsMenu() {
        cancelFails = true
        toConfirmFilm5()
        // the clock stopped: the DELETE fails at once, and the moment before its dialog is the test's
        rule.mainClock.autoAdvance = false
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        settle(frames = 6)
        assertEquals("focus back on the lockup as the question went", "request request:5", focusedTag())
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        settle(frames = 3)
        assertEquals("request request:6", focusedTag())
        // Film 6's menu before the failure's dialog: the remote's Menu key, the long press's action (a held key's repeats
        // come from the test's clock, stopped here)
        rule.onRoot().performKeyInput { pressKey(Key.Menu) }
        settle(frames = 4)
        assertTrue("Film 6's menu is open", shown("request menu"))
        assertTrue("the failure's dialog is not up yet", !shown("cancel problem"))
        rule.mainClock.advanceTimeBy(700)
        rule.waitForIdle()
        assertTrue("the failure's dialog came over Film 6's menu (focus on ${focusedTag()})", !shown("cancel problem"))
        assertEquals("Film 6's menu keeps focus", "cancel request:6", focusedTag())
        // Back closes the menu onto Film 6; then the dialog, and its OK goes back to Film 5 with nothing open
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        settle(frames = 6)
        assertEquals("request request:6", focusedTag())
        rule.mainClock.advanceTimeBy(700)
        rule.mainClock.autoAdvance = true
        await("cancel problem")
        assertFocused("problem ok")
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { !shown("cancel problem") }
        assertFocused("request request:5")
        assertTrue("no menu left open", !shown("request menu"))
        assertEquals(emptyList<Int>(), cancelled.toList())
    }

    @Test
    fun aCancelThatFailsIsADialogWithOKAndFocusComesBackToTheLockup() {
        cancelFails = true
        askToCancelFilm5()
        await("cancel problem")
        assertFocused("problem ok")
        assertEquals("Request Not Cancelled", words("dialog title"))
        assertEquals("The request could not be changed. Try again.", words("dialog message"))
        // the bar is not drawn over the dialog
        press(Key.DirectionUp)
        assertFocused("problem ok")
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("cancel problem").fetchSemanticsNodes().isEmpty() }
        assertFocused("request request:5")
        assertEquals(emptyList<Int>(), cancelled.toList())
        assertNoToastFor(1_000)
    }
}
