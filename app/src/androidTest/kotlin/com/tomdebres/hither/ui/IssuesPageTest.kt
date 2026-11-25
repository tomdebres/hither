package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.model.Issue
import com.tomdebres.hither.core.model.IssueBody
import com.tomdebres.hither.core.model.IssuePage
import com.tomdebres.hither.core.model.IssueStatus
import com.tomdebres.hither.core.model.IssueType
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MediaSeason
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PageInfo
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.SeasonSummary
import com.tomdebres.hither.core.model.TvDetails
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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getBoundsInRoot
import com.tomdebres.hither.core.model.IssueComment
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seerr's issues (M6, DECISIONS 106), the whole home through the real client against a scripted server:
 * "Report an Issue" on a title in the library opens its form in the side panel (A5), whose rows open their lists inside
 * it, which sends once, says "Issue Reported" on its button and hands focus back, and whose failure is a dialog with OK;
 * a series names its season and episode from lists; the bar's Issues opens tvOS's Library split on Open (A6: Down
 * from the bar on the chosen filter, Right into the lockups, Left back), a filter chosen reads it again and keeps focus,
 * a lockup opens its title and Back comes to the lockup; an account without the rights has neither.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class IssuesPageTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    // 4K on for films, and the account may ask for it: "Request in 4K" comes before Report an Issue, so focus
    // falling back to the page's first control is not where the form hands it back.
    private var permissions = Permission.REQUEST + Permission.REQUEST_4K_MOVIE + Permission.CREATE_ISSUES + Permission.VIEW_ISSUES
    private val session get() = Session(User(id = 8, displayName = "TV", permissions = permissions), PublicSettings(applicationTitle = "Seerr", localLogin = true, movie4kEnabled = true))

    private val reported = CopyOnWriteArrayList<IssueBody>()

    /** The server answers a report with a 500 (A5: the failure's dialog). */
    @Volatile private var reportFails = false

    /** Every report the page sent, whatever the server answered. */
    private val reportsSent = AtomicInteger()

    /** Calls through the page's access still out (they run on the models' work thread). */
    private val inFlight = AtomicInteger()

    /** The issues list answered by a proxy's HTML 403 (`/fake/forbid`'s kind), not Seerr's. */
    @Volatile private var issuesForbidden = false
    private val listed = CopyOnWriteArrayList<String>()

    private val access = object : SeerrAccess {
        private val client = SeerrClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                val json = SeerrClient.json
                if (path == "/api/v1/issue" && request.method == HttpMethod.Post) reportsSent.incrementAndGet()
                val body = when {
                    path == "/api/v1/settings/public" -> json.encodeToString(PublicSettings.serializer(), session.settings)
                    path == "/api/v1/auth/me" -> json.encodeToString(User.serializer(), session.user)
                    path == "/api/v1/issue" && request.method == HttpMethod.Post && reportFails ->
                        return@MockEngine respond("""{"message":"boom"}""", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json"))
                    path == "/api/v1/issue" && request.method == HttpMethod.Post -> {
                        reported += json.decodeFromString(IssueBody.serializer(), (request.body as TextContent).text)
                        return@MockEngine respond(json.encodeToString(Issue.serializer(), Issue(id = 77)), HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                    path == "/api/v1/issue" && issuesForbidden -> return@MockEngine respond("<html><body>403 Forbidden</body></html>", HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "text/html"))
                    path == "/api/v1/issue" -> {
                        val filter = request.url.parameters["filter"].orEmpty()
                        listed += filter
                        val all = listOf(
                            Issue(id = 1, issueType = IssueType.AUDIO, media = MediaInfo(mediaType = "movie", tmdbId = 1), createdBy = User(id = 2, displayName = "Someone"), comments = listOf(IssueComment(message = "The sound drops out."))),
                            Issue(id = 2, issueType = IssueType.VIDEO, status = IssueStatus.RESOLVED, media = MediaInfo(mediaType = "movie", tmdbId = 2)),
                            Issue(id = 3, issueType = IssueType.SUBTITLES, problemSeason = 1, problemEpisode = 2, media = MediaInfo(mediaType = "tv", tmdbId = 9)),
                        )
                        val shown = when (filter) {
                            "open" -> all.filter { it.status == IssueStatus.OPEN }
                            "resolved" -> all.filter { it.status == IssueStatus.RESOLVED }
                            else -> all
                        }
                        json.encodeToString(IssuePage.serializer(), IssuePage(PageInfo(results = shown.size), shown))
                    }
                    path.startsWith("/api/v1/movie/") && !path.endsWith("ratingscombined") && !path.contains("similar") && !path.contains("recommendations") -> {
                        val id = path.substringAfterLast('/').toInt()
                        json.encodeToString(MovieDetails.serializer(), MovieDetails(id = id, title = "Film $id", mediaInfo = MediaInfo(id = 40 + id, tmdbId = id, status = MediaStatus.AVAILABLE)))
                    }
                    path == "/api/v1/tv/9" -> json.encodeToString(
                        TvDetails.serializer(),
                        TvDetails(
                            id = 9,
                            name = "Series 9",
                            seasons = (1..3).map { SeasonSummary(seasonNumber = it, episodeCount = 6) },
                            mediaInfo = MediaInfo(id = 90, tmdbId = 9, status = MediaStatus.PARTIALLY_AVAILABLE, seasons = listOf(MediaSeason(seasonNumber = 1, status = MediaStatus.AVAILABLE), MediaSeason(seasonNumber = 2, status = MediaStatus.AVAILABLE))),
                        ),
                    )
                    path.endsWith("ratingscombined") || path.endsWith("/ratings") -> return@MockEngine respond("""{"message":"No ratings found."}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
                    // Discover's rows: two films and the series
                    else -> json.encodeToString(
                        Page.serializer(MediaResult.serializer()),
                        Page(results = listOf(MediaResult(id = 1, mediaType = "movie", title = "Film 1"), MediaResult(id = 2, mediaType = "movie", title = "Film 2"), MediaResult(id = 9, mediaType = "tv", name = "Series 9"))),
                    )
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
            "http://seerr.test",
            AcceptAllCookiesStorage(),
        )

        override suspend fun <T> call(call: suspend (SeerrClient) -> T): T {
            inFlight.incrementAndGet()
            try {
                return call(client)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun show() {
        rule.setContent { DpadHost { HomeScreen(Screen.Home(session), access, onSignOut = {}) } }
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

    private fun assertFocused(tag: String, what: String = "") {
        runCatching { rule.waitUntil(5_000) { focusedTag() == tag } }
        val now = focusedTag()
        if (now != tag) throw AssertionError("${what}expected focus on $tag, it is on $now")
    }

    private fun words(tag: String, under: String? = null): String =
        rule.onAllNodes(if (under == null) hasTestTag(tag) else hasTestTag(tag) and hasAnyAncestor(hasTestTag(under)), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { node -> listOf(node) + node.children }
            .flatMap { it.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty() }
            .joinToString("") { it.text }

    /** Right along the page's actions to Report an Issue. */
    private fun toReport() {
        repeat(4) {
            if (focusedTag() == "action report issue") return
            press(Key.DirectionRight)
        }
        assertFocused("action report issue")
    }

    /** The words typed, then down to Submit Issue and Select. */
    private fun typeAndSubmit(text: String) {
        rule.onNodeWithTag("issue message input", useUnmergedTree = true).performTextInput(text)
        rule.waitForIdle()
        repeat(10) {
            if (focusedTag() == "issue submit") return@repeat
            press(Key.DirectionDown)
        }
        assertFocused("issue submit")
        press(Key.DirectionCenter)
    }

    /** Film 1's page, Right to Report an Issue, its panel, the words typed and Down to Submit Issue: not yet pressed. */
    private fun toSubmit(text: String) {
        show()
        press(Key.DirectionCenter)
        await("action report issue")
        toReport()
        press(Key.DirectionCenter)
        await("issue modal")
        assertFocused("issue type")
        rule.onNodeWithTag("issue message input", useUnmergedTree = true).performTextInput(text)
        rule.waitForIdle()
        repeat(10) {
            if (focusedTag() == "issue submit") return@repeat
            press(Key.DirectionDown)
        }
        assertFocused("issue submit")
    }

    /**
     * Select on Submit Issue with the clock stopped, so the moment between the answer and its dialog (`ProblemAlert`'s
     * 600 ms) is the test's: the answer comes, the page takes it in, and two frames go by. The clock stays stopped.
     */
    private fun submitAndHoldTheClock() {
        val sent = reportsSent.get()
        rule.mainClock.autoAdvance = false
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        settle { reportsSent.get() == sent + 1 && inFlight.get() == 0 }
    }

    /**
     * With the clock stopped: [answered] first (nothing, by default), then [frames] frames. The scripted server answers on
     * the models' `work` thread (`Dispatchers.Default`) and Ktor's, which the test clock does not drive: `advanceTimeBy`
     * would move the page's clock on without the answer in, and `waitForIdle` does not wait for those threads. So the wait
     * for an answer is in real time, but for the answer itself (its call back), not a guess at how long one takes (A9's
     * three sleeps of 150 ms, which a slow host could outlast). After a press that sends nothing, the test clock alone.
     */
    private fun settle(frames: Int = 2, answered: () -> Boolean = { true }) {
        rule.waitUntil(5_000) { answered() }
        rule.waitForIdle()
        repeat(frames) { rule.mainClock.advanceTimeByFrame() }
        rule.waitForIdle()
    }

    private fun shown(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** A control's words as the merged tree reads them (a tv-material button's sit a few levels down, A1). */
    private fun said(tag: String): String = runCatching {
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().joinToString("") { it.text }
    }.getOrDefault("")

    /** What a row of the panel shows as its choice (its state, the words at its right). */
    private fun choice(tag: String): String? = rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.StateDescription) { null }

    /** The form is the side panel (A5): along the right edge of the screen, 420 dp wide, the screen's height. */
    private fun assertThePanel(tag: String) {
        val root = rule.onRoot().getBoundsInRoot()
        val panel = rule.onNodeWithTag(tag).getBoundsInRoot()
        assertEquals("$tag is 420 dp wide", 420f, (panel.right - panel.left).value, 1f)
        assertEquals("$tag runs to the right edge", root.right.value, panel.right.value, 1f)
        assertEquals("$tag is the screen's height", (root.bottom - root.top).value, (panel.bottom - panel.top).value, 1f)
    }

    @Test
    fun aFilmsReportIsAPanelWhoseTypeOpensItsListInsideItAndBackClosesItToTheButton() {
        show()
        assertFocused("card movie:1")
        press(Key.DirectionCenter)
        await("action report issue")
        assertFocused("action Request in 4K")
        toReport()
        press(Key.DirectionCenter)
        await("issue modal")
        // a film has no season to choose: focus arrives on the type, Video
        assertFocused("issue type")
        rule.waitForIdle()
        assertThePanel("issue modal")
        assertEquals("Video", choice("issue type"))
        // remote moves never leave the panel
        for (key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp)) {
            press(key)
            assertFocused("issue type", "$key from the type: ")
        }
        // the type's choices open as a list in the panel, the current one ticked and focused
        press(Key.DirectionCenter)
        await("issue type list")
        assertFocused("issue type: Video")
        assertEquals("on", choice("issue type: Video"))
        press(Key.DirectionDown)
        assertFocused("issue type: Audio")
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("issue type list").fetchSemanticsNodes().isEmpty() }
        assertFocused("issue type")
        assertEquals("Audio", choice("issue type"))
        // Back from an open list closes it onto its row, choosing nothing
        press(Key.DirectionCenter)
        await("issue type list")
        assertFocused("issue type: Audio")
        press(Key.DirectionDown)
        back()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("issue type list").fetchSemanticsNodes().isEmpty() }
        assertFocused("issue type")
        assertEquals("Audio", choice("issue type"))
        // "What's wrong?" over the field, Submit Issue last and off until there are words
        val label = rule.onNodeWithTag("label what's wrong", useUnmergedTree = true).getBoundsInRoot()
        val field = rule.onNodeWithTag("issue message").getBoundsInRoot()
        val submit = rule.onNodeWithTag("issue submit").getBoundsInRoot()
        assertTrue("the label is over the field", label.bottom <= field.top)
        assertTrue("Submit Issue is last", submit.top >= field.bottom)
        // Back closes the panel unsent, onto Report an Issue; it opens again new, as Seerr's does
        back()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("issue modal").fetchSemanticsNodes().isEmpty() }
        assertFocused("action report issue")
        press(Key.DirectionCenter)
        await("issue modal")
        assertFocused("issue type")
        assertEquals("Video", choice("issue type"))
        typeAndSubmit("Out of sync.")
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("issue modal").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(IssueBody(IssueType.VIDEO, "Out of sync.", mediaId = 41)), reported.toList())
        // what it did, on the button that did it (tvOS's D4), and no toast
        assertFocused("action report issue")
        rule.waitUntil(5_000) { said("action report issue") == "Issue Reported" }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals("no toast", 0, rule.onAllNodesWithTag("issue", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals("no dialog", 0, rule.onAllNodesWithTag("problem").fetchSemanticsNodes().size)
    }

    @Test
    fun aSeriesNamesItsSeasonAndEpisodeFromListsInsideThePanel() {
        show()
        press(Key.DirectionRight, 2)
        assertFocused("card tv:9")
        press(Key.DirectionCenter)
        await("action report issue")
        toReport()
        press(Key.DirectionCenter)
        await("issue modal")
        assertFocused("issue season")
        assertEquals("All Seasons", choice("issue season"))
        // no season, no episode to name: the episode row is off
        press(Key.DirectionDown)
        assertFocused("issue type")
        press(Key.DirectionUp)
        press(Key.DirectionCenter)
        await("issue season list")
        assertFocused("issue season: All Seasons")
        press(Key.DirectionDown)
        assertFocused("issue season: Season 1")
        press(Key.DirectionCenter)
        assertFocused("issue season")
        assertEquals("Season 1", choice("issue season"))
        press(Key.DirectionDown)
        assertFocused("issue episode")
        press(Key.DirectionCenter)
        await("issue episode list")
        assertFocused("issue episode: All Episodes")
        press(Key.DirectionDown, 2)
        assertFocused("issue episode: Episode 2")
        press(Key.DirectionCenter)
        assertFocused("issue episode")
        assertEquals("Episode 2", choice("issue episode"))
        typeAndSubmit("Black screen.")
        rule.waitUntil(5_000) { reported.isNotEmpty() }
        assertEquals(listOf(IssueBody(IssueType.VIDEO, "Black screen.", mediaId = 90, problemSeason = 1, problemEpisode = 2)), reported.toList())
    }

    @Test
    fun aReportThatFailsIsADialogWithOKOverThePanelAndFocusComesBackToSubmit() {
        reportFails = true
        show()
        press(Key.DirectionCenter)
        await("action report issue")
        toReport()
        press(Key.DirectionCenter)
        await("issue modal")
        assertFocused("issue type")
        typeAndSubmit("Out of sync.")
        // tvOS's words, after the wait for what was closing; OK has focus
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("problem").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        assertFocused("problem ok")
        assertEquals("Issue Not Sent", words("dialog title"))
        assertEquals("The issue did not go through. Try again.", words("dialog message"))
        // the form stays to try again, with its words
        rule.onNodeWithTag("issue modal").assertExists()
        press(Key.DirectionUp)
        assertFocused("problem ok", "Up from OK: ")
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("problem").fetchSemanticsNodes().isEmpty() }
        assertFocused("issue submit")
        assertEquals("no toast", 0, rule.onAllNodesWithTag("issue", useUnmergedTree = true).fetchSemanticsNodes().size)
        // it goes through on another try, and says so on the button
        reportFails = false
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("issue modal").fetchSemanticsNodes().isEmpty() }
        assertFocused("action report issue")
        rule.waitUntil(5_000) { said("action report issue") == "Issue Reported" }
    }

    /**
     * A9 (the review of A5's merged code): Back in the moment before a failed report's dialog does not close the form
     * under it, so OK has Submit Issue to give focus back to. Before, the form closed and OK sent focus to nothing.
     */
    @Test
    fun backInTheMomentBeforeAFailedReportsDialogLeavesTheFormForItsOK() {
        reportFails = true
        toSubmit("Out of sync.")
        submitAndHoldTheClock()
        assertTrue("the dialog is not up yet", !shown("problem"))
        back()
        settle(frames = 4)
        rule.mainClock.advanceTimeBy(700)
        rule.mainClock.autoAdvance = true
        rule.waitUntil(5_000) { shown("problem") }
        assertTrue("Back in the moment before the dialog closed the form under it", shown("issue modal"))
        assertFocused("problem ok")
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { !shown("problem") }
        assertFocused("issue submit", "OK after Back in the moment before the dialog: ")
        rule.waitUntil(5_000) { said("issue submit") == "Submit Issue" }
    }

    /**
     * A9: a second Submit in the moment before a failed report's dialog is not sent, so "Issue Not Sent" never shows
     * over a report that went through. After OK the form is there to try again.
     */
    @Test
    fun aSecondSubmitInTheMomentBeforeAFailedReportsDialogIsNotSent() {
        reportFails = true
        toSubmit("Out of sync.")
        submitAndHoldTheClock()
        reportFails = false
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        settle(frames = 4)
        rule.mainClock.advanceTimeBy(700)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        runCatching { rule.waitUntil(3_000) { shown("problem") } }
        assertTrue(
            "\"Issue Not Sent\" over a report that went through (${reported.size} sent, the form ${if (shown("issue modal")) "open" else "closed"})",
            reported.isEmpty() || !shown("problem"),
        )
        assertTrue("the failure's dialog", shown("problem"))
        assertFocused("problem ok")
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { !shown("problem") }
        assertFocused("issue submit")
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { !shown("issue modal") }
        assertEquals(1, reported.size)
        rule.waitUntil(5_000) { said("action report issue") == "Issue Reported" }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertTrue("no dialog for a report that went through", !shown("problem"))
    }

    @Test
    fun theBarsIssuesOpensTheSplitOnOpenAndALockupOpensItsTitle() {
        show()
        press(Key.DirectionUp)
        assertFocused("nav Discover")
        // Requests, Issues (Issues after Requests again, DECISIONS 151)
        press(Key.DirectionRight, 2)
        assertFocused("nav Issues")
        // focus on its tab shows the page (A2); Down lands on the chosen filter, ticked; Right goes into the grid
        await("issues page")
        await("issue:1")
        press(Key.DirectionDown)
        assertFocused("issue filter open", "Down from the bar: ")
        rule.onNode(hasTestTag("tick") and hasAnyAncestor(hasTestTag("issue filter open")), useUnmergedTree = true).assertExists()
        rule.onAllNodes(hasTestTag("tick") and hasAnyAncestor(hasTestTag("issue filter all")), useUnmergedTree = true).assertCountEquals(0)
        press(Key.DirectionRight)
        assertFocused("issue:1", "Right from the filters: ")
        assertEquals(listOf("open"), listed.toList())
        // each lockup: the title, Open as a glyph and its word, and what and when under it (tvOS's)
        rule.onAllNodesWithTag("issue status Open", useUnmergedTree = true).assertCountEquals(2)
        assertEquals("Film 1", words("card title", under = "lockup issue:1"))
        assertEquals("Audio", words("card line", under = "lockup issue:1"))
        assertEquals("Subtitle", words("card line", under = "lockup issue:3"))
        // the two lockups side by side, the caption under the picture
        val one = rule.onNode(hasTestTag("lockup issue:1"), useUnmergedTree = true).getBoundsInRoot()
        val three = rule.onNode(hasTestTag("lockup issue:3"), useUnmergedTree = true).getBoundsInRoot()
        assertEquals("one line: the same top", one.top.value, three.top.value, 1f)
        assertTrue("side by side", three.left >= one.right)
        // Left back to the chosen filter; Down to Resolved, Select chooses it and focus stays there
        press(Key.DirectionLeft)
        assertFocused("issue filter open", "Left from the grid: ")
        press(Key.DirectionDown)
        assertFocused("issue filter resolved")
        press(Key.DirectionCenter)
        await("issue:2")
        assertEquals("resolved", listed.last())
        assertFocused("issue filter resolved", "a filter chosen: ")
        rule.onNode(hasTestTag("tick") and hasAnyAncestor(hasTestTag("issue filter resolved")), useUnmergedTree = true).assertExists()
        rule.onAllNodesWithTag("issue status Resolved", useUnmergedTree = true).assertCountEquals(1)
        press(Key.DirectionRight)
        assertFocused("issue:2")
        press(Key.DirectionCenter)
        await("media title")
        assertEquals("Film 2", words("media title"))
        back()
        assertFocused("issue:2")
        back()
        assertFocused("nav Issues")
    }

    @Test
    fun anAccountWithoutTheRightsHasNeitherTheButtonNorTheList() {
        permissions = Permission.REQUEST
        show()
        rule.onAllNodesWithTag("nav Issues").assertCountEquals(0)
        press(Key.DirectionCenter)
        await("media title")
        rule.waitForIdle()
        rule.onAllNodesWithTag("action report issue").assertCountEquals(0)
    }

    /**
     * A proxy's refusal (an HTML 403, not Seerr's own) is a failure to try again in the words every page uses, not
     * a lost right (the polish 2 audit's item 17); when the server answers again, Try Again brings the list.
     */
    @Test
    fun aProxysRefusalIsAFailureWithTryAgainNotALostRight() {
        issuesForbidden = true
        show()
        press(Key.DirectionUp)
        press(Key.DirectionRight, 2)
        assertFocused("nav Issues")
        await("try again")
        // focus stays on the tab as its page comes (A2); Down to the chosen filter, Right to Try Again
        press(Key.DirectionDown)
        assertFocused("issue filter open")
        press(Key.DirectionRight)
        assertFocused("try again")
        rule.onAllNodesWithTag("issues no right").assertCountEquals(0)
        rule.onNode(hasText("The issues did not load."), useUnmergedTree = true).assertExists()
        issuesForbidden = false
        press(Key.DirectionCenter)
        await("issue:1")
        assertFocused("issue:1")
    }

}
