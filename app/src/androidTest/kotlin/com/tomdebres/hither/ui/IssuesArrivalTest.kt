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
import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.model.Issue
import com.tomdebres.hither.core.model.IssuePage
import com.tomdebres.hither.core.model.IssueType
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PageInfo
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.home.HomeScreen
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Issues page's way into its lockups after a fresh arrival (A8, DECISIONS 350; DECISIONS 338's rule): the page is
 * read again from the top each time it comes up, so Right from the chosen side row lands on the first lockup, not on
 * the lockup left the time before. A class of its own: A5 changes `IssuesPageTest`'s report flow.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class IssuesArrivalTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val session = Session(
        User(id = 8, displayName = "TV", permissions = Permission.REQUEST + Permission.CREATE_ISSUES + Permission.VIEW_ISSUES),
        PublicSettings(applicationTitle = "Seerr", localLogin = true),
    )

    /** Twenty-four open issues, one a film each: six lines of four, more than the screen shows. */
    private val issues = (1..24).map { Issue(id = it, issueType = IssueType.AUDIO, media = MediaInfo(mediaType = "movie", tmdbId = it)) }

    /** A9: the first ask for a later page fails (a 500); every later ask answers. The asks for page 2, counted. */
    @Volatile private var laterPageFailsOnce = false
    private val secondPageAsks = java.util.concurrent.atomic.AtomicInteger()

    private val access = object : SeerrAccess {
        private val client = SeerrClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                val json = SeerrClient.json
                val body = when {
                    path == "/api/v1/settings/public" -> json.encodeToString(PublicSettings.serializer(), session.settings)
                    path == "/api/v1/auth/me" -> json.encodeToString(User.serializer(), session.user)
                    path == "/api/v1/issue" -> {
                        val skip = request.url.parameters["skip"]?.toInt() ?: 0
                        val take = request.url.parameters["take"]?.toInt() ?: 10
                        if (skip == 10 && secondPageAsks.incrementAndGet() == 1 && laterPageFailsOnce) {
                            return@MockEngine respond("""{"message":"boom"}""", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                        json.encodeToString(IssuePage.serializer(), IssuePage(PageInfo(results = issues.size), issues.drop(skip).take(take)))
                    }
                    path.startsWith("/api/v1/movie/") && path.count { it == '/' } == 4 -> {
                        val id = path.substringAfterLast('/').toInt()
                        json.encodeToString(MovieDetails.serializer(), MovieDetails(id = id, title = "Film $id", mediaInfo = MediaInfo(id = 40 + id, tmdbId = id, status = MediaStatus.AVAILABLE)))
                    }
                    // Discover's rows, and anything else the home asks: two films
                    else -> json.encodeToString(
                        Page.serializer(MediaResult.serializer()),
                        Page(results = listOf(MediaResult(id = 1, mediaType = "movie", title = "Film 1"), MediaResult(id = 2, mediaType = "movie", title = "Film 2"))),
                    )
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
            "http://seerr.test",
            AcceptAllCookiesStorage(),
        )

        override suspend fun <T> call(call: suspend (SeerrClient) -> T): T = call(client)
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

    private fun assertFocused(tag: String, what: String = "") {
        runCatching { rule.waitUntil(5_000) { focusedTag() == tag } }
        val now = focusedTag()
        if (now != tag) throw AssertionError("${what}expected focus on $tag, it is on $now")
    }

    /**
     * A9 (the review of A6's merged code): a later page that fails ends the grid with Try Again (My Requests' lockup,
     * DECISIONS 65's rule); moving along does not ask again; Select on it asks for that page once, and focus goes to the
     * first new lockup. Before, nothing ended the grid and the list stayed at ten until the filter changed.
     */
    @Test
    fun aLaterPageThatFailsEndsTheGridWithTryAgainWhichHandsFocusToTheFirstNewLockup() {
        laterPageFailsOnce = true
        rule.setContent { DpadHost { HomeScreen(Screen.Home(session), access, onSignOut = {}) } }
        await("card movie:1")
        press(Key.DirectionUp)
        press(Key.DirectionRight, 2)
        assertFocused("nav Issues")
        await("issue:1")
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        assertFocused("issue:1")
        // down to the third line: focus near the end asks for page 2, which fails
        press(Key.DirectionDown, 2)
        assertFocused("issue:9")
        rule.waitUntil(5_000) { secondPageAsks.get() == 1 }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        assertTrue("the ten issues stay", rule.onAllNodesWithTag("issue:10").fetchSemanticsNodes().isNotEmpty())
        assertEquals("a later page that failed ends the grid with Try Again", 1, rule.onAllNodesWithTag("retry more").fetchSemanticsNodes().size)
        // moving along does not ask again
        press(Key.DirectionRight)
        assertFocused("issue:10")
        press(Key.DirectionRight)
        assertFocused("retry more")
        assertEquals("moving along asked again", 1, secondPageAsks.get())
        // Select asks once; focus goes on to the first new lockup
        press(Key.DirectionCenter)
        assertFocused("issue:11", "after Try Again: ")
        assertEquals(2, secondPageAsks.get())
        assertEquals(0, rule.onAllNodesWithTag("retry more").fetchSemanticsNodes().size)
    }

    @Test
    fun afterAFreshArrivalRightFromTheSideRowLandsOnTheFirstLockup() {
        rule.setContent { DpadHost { HomeScreen(Screen.Home(session), access, onSignOut = {}) } }
        await("card movie:1")
        press(Key.DirectionUp)
        press(Key.DirectionRight, 2)
        assertFocused("nav Issues")
        await("issue:1")
        press(Key.DirectionDown)
        assertFocused("issue filter open")
        press(Key.DirectionRight)
        assertFocused("issue:1")
        // a lockup on the second line, on screen with the grid at its top: on main, a fresh arrival kept it as the
        // grid's entry, so Right from the side row went to it, not to the first. (One far down the list, not composed,
        // was missed by luck: the entry had no node, so Compose's own move found the first lockup.)
        press(Key.DirectionDown)
        press(Key.DirectionRight, 2)
        assertFocused("issue:7")
        // Back to the bar, along to Requests and back: Issues comes up afresh, read again from the top
        back()
        assertFocused("nav Issues")
        press(Key.DirectionLeft)
        assertFocused("nav Requests")
        press(Key.DirectionRight)
        assertFocused("nav Issues")
        await("issue:1")
        press(Key.DirectionDown)
        assertFocused("issue filter open", "Down from the bar after a fresh arrival: ")
        press(Key.DirectionRight)
        assertFocused("issue:1", "Right from the side row after a fresh arrival: ")
    }
}
