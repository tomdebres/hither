package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.RequestBody
import com.tomdebres.hither.core.model.RequestSeason
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.rules.MediaPage
import com.tomdebres.hither.core.rules.StatusBadge
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.core.rules.afterRequest
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.ShowsFocus
import com.tomdebres.hither.ui.media.MediaModel
import com.tomdebres.hither.ui.media.MediaScreen
import com.tomdebres.hither.ui.media.RequestSender
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A title's request on a device (2.9, DECISIONS 63; A5): a film's asks in a dialog with Request focused, a series' opens
 * its seasons in the side panel as ticked rows (the first open season focused, taken seasons skipped and showing their
 * status, Down from the last reaching the primary); each gives focus back to the button that opened it. One Select is
 * one request: the button says "Requesting…" and keeps focus while it is out; the page and the card show the new status
 * at once, and no toast says it; "approved automatically" follows the account; every failure is a dialog with OK in its
 * own words, focus back on the button after.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RequestModalTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val showsFocus = SemanticsMatcher.expectValue(ShowsFocus, true)
    private val sent = CopyOnWriteArrayList<RequestBody>()
    private val badges = CopyOnWriteArrayList<Pair<String, StatusBadge?>>()

    /** The server's answer to each request, given by the test; a 201 approved unless it says. */
    private var answer: suspend (RequestBody) -> MediaRequest = { body -> created(body, RequestStatus.APPROVED) }

    private fun created(body: RequestBody, status: Int) = MediaRequest(
        id = 1001,
        status = status,
        is4k = body.is4k,
        seasons = body.seasons.orEmpty().map { RequestSeason(seasonNumber = it, status = status) },
    )

    /** What the test's server holds: the page, with the record each 201 made, as a server would. */
    @Volatile private var served: MediaPage? = null

    /** Every read after the first waits here, so the test can look at the page before it is read again. */
    private val reread = CompletableDeferred<Unit>()

    private fun show(page: MediaPage, card: TitleCard = MediaPages.card()) {
        served = page
        var reads = 0
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember {
                MediaModel(
                    source = { _, _ ->
                        if (reads++ > 0) reread.await()
                        served!!
                    },
                    card = card,
                    scope = scope,
                    requests = RequestSender { body ->
                        sent += body
                        answer(body).also { created ->
                            val now = served!!
                            served = now.withMedia(afterRequest(now.media, created, now.type, now.tmdbId))
                        }
                    },
                    onBadge = { key, badge -> badges += key to badge },
                )
            }
            DpadHost { MediaScreen(model) }
        }
        rule.waitForIdle()
    }

    private fun await(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun gone(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
        rule.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "a node without a tag" } ?: "a node without a tag"
    }.getOrElse { "nothing (${it.message?.lineSequence()?.firstOrNull()})" }

    private fun assertFocused(tag: String) {
        runCatching { rule.waitUntil(3_000) { focusedTag() == tag } }
        val now = focusedTag()
        if (now != tag) throw AssertionError("expected focus on $tag, it is on $now")
    }

    /** The words a button or the notice carries (a button's text is in a child). */
    private fun words(tag: String): String = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        // the node and everything under it: a tv-material button's words sit a few levels down (A1)
        .flatMap { node -> generateSequence(listOf(node)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }.flatten().toList() }
        .flatMap { it.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty() }
        .joinToString("") { it.text }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun openModal(button: String) {
        await(button)
        assertFocused(button)
        press(Key.DirectionCenter)
        await("request modal")
    }

    /** The form is the side panel (A5): along the right edge, 420 dp wide, the screen's height. */
    private fun assertThePanel(tag: String) {
        val root = rule.onRoot().getBoundsInRoot()
        val panel = rule.onNodeWithTag(tag).getBoundsInRoot()
        assertEquals("$tag is 420 dp wide", 420f, (panel.right - panel.left).value, 1f)
        assertEquals("$tag runs to the right edge", root.right.value, panel.right.value, 1f)
        assertEquals("$tag is the screen's height", (root.bottom - root.top).value, (panel.bottom - panel.top).value, 1f)
    }

    private fun state(tag: String): String? = rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.StateDescription) { null }

    /** A row's tick (rule 3: a tick, never a fill), under the row's node. */
    private fun ticked(tag: String): Boolean = rule.onAllNodes(hasTestTag("tick") and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** No toast says what a request came to (A5): the notices' banner is the only thing that comes and goes by itself. */
    private fun assertNoToast() {
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals("no toast", 0, rule.onAllNodesWithTag("outcome", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun aFilmsRequestAsksInADialogWithRequestFocusedAndGivesFocusBackOnBack() {
        show(MediaPages.film())
        openModal("action Request")
        assertFocused("primary")
        rule.onNodeWithTag("primary").assert(showsFocus)
        // tvOS's question: the title asked about, the notes as its message, Request and Cancel
        assertEquals("Request Film 7?", words("dialog title"))
        assertEquals("This request needs no approval.", words("dialog message"))
        assertEquals("Request", words("primary"))
        press(Key.DirectionDown)
        assertFocused("cancel")
        // nothing outside the dialog can be reached
        for (key in listOf(Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)) {
            press(key)
            assertFocused("cancel")
        }
        press(Key.DirectionUp)
        assertFocused("primary")
        press(Key.DirectionUp)
        assertFocused("primary")
        back()
        gone("request modal")
        assertFocused("action Request")
        // Cancel does the same, from the 4K copy's button, whose question names its copy
        press(Key.DirectionRight)
        openModal("action Request in 4K")
        assertEquals("Request Film 7 in 4K?", words("dialog title"))
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        gone("request modal")
        assertFocused("action Request in 4K")
        assertEquals(emptyList<RequestBody>(), sent)
    }

    @Test
    fun aSeriesRequestOpensInThePanelWithTicksOnTheFirstOpenSeasonAndDownReachesThePrimary() {
        show(MediaPages.series(), MediaPages.seriesCard())
        openModal("action Request More")
        assertThePanel("request modal")
        assertEquals("Request Series", words("panel title"))
        // season 1 is in the library: on, dimmed, its status in place of the tick, and passed over
        assertFocused("season 2")
        rule.onNodeWithTag("season 2").assert(showsFocus)
        assertEquals("on", state("season 1"))
        assertFalse("a taken season has no tick", ticked("season 1"))
        rule.onAllNodes(hasTestTag("status Available") and hasAnyAncestor(hasTestTag("season 1")), useUnmergedTree = true).assertCountEquals(1)
        // only a taken season says a status (tvOS's S6)
        rule.onAllNodes(hasTestTag("status Not Requested"), useUnmergedTree = true).assertCountEquals(0)
        assertEquals("Season 2 · 12 Episodes", words("season 2"))
        press(Key.DirectionUp)
        assertFocused("season all")
        press(Key.DirectionDown)
        assertFocused("season 2")
        assertEquals("Select Season(s)", words("primary"))
        assertEquals("off", state("season 2"))
        assertFalse(ticked("season 2"))
        press(Key.DirectionCenter)
        assertEquals("on", state("season 2"))
        assertTrue("a ticked row, never a fill", ticked("season 2"))
        assertEquals("Request 1 Season", words("primary"))
        press(Key.DirectionDown, 2)
        assertFocused("season 4")
        press(Key.DirectionCenter)
        assertEquals("Request 2 Seasons", words("primary"))
        press(Key.DirectionDown, 2)
        assertFocused("season 6")
        // nothing beside the rows: the panel keeps focus
        press(Key.DirectionLeft)
        assertFocused("season 6")
        press(Key.DirectionDown)
        assertFocused("primary")
        val primary = rule.onNodeWithTag("primary").getBoundsInRoot()
        val last = rule.onNodeWithTag("season 6").getBoundsInRoot()
        assertTrue("the primary is the last row", primary.top >= last.bottom)
        press(Key.DirectionCenter)
        gone("request modal")
        assertEquals(listOf(RequestBody("tv", 9, is4k = false, seasons = listOf(2, 4))), sent)
        // still more to ask for: focus is back on Request More
        assertFocused("action Request More")
        assertNoToast()
    }

    @Test
    fun backClosesTheSeriesPanelUnsentOntoItsButton() {
        show(MediaPages.series(), MediaPages.seriesCard())
        openModal("action Request More")
        assertFocused("season 2")
        press(Key.DirectionCenter)
        back()
        gone("request modal")
        assertFocused("action Request More")
        assertEquals(emptyList<RequestBody>(), sent)
    }

    @Test
    fun allSeasonsSwitchesEveryOpenSeasonOnAndOff() {
        show(MediaPages.series(), MediaPages.seriesCard())
        openModal("action Request More")
        press(Key.DirectionUp)
        assertFocused("season all")
        press(Key.DirectionCenter)
        assertEquals("Request 5 Seasons", words("primary"))
        assertTrue(ticked("season all"))
        press(Key.DirectionCenter)
        assertEquals("Select Season(s)", words("primary"))
    }

    @Test
    fun oneSelectIsOneRequestTheButtonSaysRequestingAndThePageAndTheCardReadRequestedWithNoToast() {
        val reply = CompletableDeferred<MediaRequest>()
        answer = { reply.await() }
        show(MediaPages.film())
        openModal("action Request")
        press(Key.DirectionCenter)
        // the question goes as it is answered; the page's button says so, keeps focus and does nothing
        gone("request modal")
        assertFocused("action Request")
        rule.waitUntil(5_000) { words("action Request") == "Requesting…" }
        press(Key.DirectionCenter)
        press(Key.DirectionCenter)
        assertFocused("action Request")
        assertEquals(0, rule.onAllNodesWithTag("request modal").fetchSemanticsNodes().size)
        assertEquals(1, sent.size)
        reply.complete(created(sent.single(), RequestStatus.APPROVED))
        // at once: the page has not been read again yet
        await("status Requested")
        reread.complete(Unit)
        assertEquals(listOf(RequestBody("movie", 7, is4k = false)), sent)
        // the regular copy is taken: focus goes to the 4K button left
        assertFocused("action Request in 4K")
        assertEquals("Requested", badges.last { it.first == "movie:7" }.second?.text)
        assertNoToast()
        assertEquals("no dialog", 0, rule.onAllNodesWithTag("request problem").fetchSemanticsNodes().size)
    }

    @Test
    fun withoutAutomaticApprovalTheQuestionSaysNothingMoreAndThePageReadsPending() {
        answer = { body -> created(body, RequestStatus.PENDING) }
        show(MediaPages.film(permissions = Permission.REQUEST + Permission.REQUEST_4K_MOVIE))
        openModal("action Request")
        assertEquals(0, rule.onAllNodesWithTag("dialog message").fetchSemanticsNodes().size)
        press(Key.DirectionCenter)
        gone("request modal")
        await("status Pending")
        assertEquals("Pending", badges.last().second?.text)
        // and the page read again agrees
        reread.complete(Unit)
        rule.waitForIdle()
        rule.onNodeWithTag("status Pending").assertExists()
    }

    @Test
    fun everyFailureIsADialogWithOKInItsOwnWordsAndFocusComesBackToTheButton() {
        val answers = listOf(
            SeerrException.NothingToRequest("No seasons available to request") to "All of the selected seasons are already requested or available.",
            SeerrException.AlreadyRequested("Request for this media already exists.") to "Film 7 has already been requested.",
            SeerrException.Refused("You do not have permission to make movie requests.") to "You do not have permission to make movie requests.",
            SeerrException.Refused(null) to "The server refused this request.",
            SeerrException.Unreachable(RuntimeException("timeout")) to "The server did not answer. Look at the status here before asking again.",
            SeerrException.Http(500, "boom") to "The request did not go through. Try again.",
        )
        var next = 0
        answer = { throw answers[next].first }
        show(MediaPages.film())
        for ((index, expected) in answers.map { it.second }.withIndex()) {
            next = index
            openModal("action Request")
            press(Key.DirectionCenter)
            gone("request modal")
            // once the question has gone (tvOS's 600 ms), a dialog: tvOS's title, the answer's words, OK focused
            await("request problem")
            assertFocused("problem ok")
            assertEquals("answer $index", "Request Not Sent", words("dialog title"))
            assertEquals("answer $index", expected, words("dialog message"))
            press(Key.DirectionCenter)
            gone("request problem")
            assertFocused("action Request")
            assertNoToast()
        }
        // none of them changed the page or a card
        assertEquals(answers.size, sent.size)
        assertEquals(0, rule.onAllNodesWithTag("status Requested").fetchSemanticsNodes().size)
    }

    @Test
    fun aSeriesFailureClosesThePanelAndItsDialogGivesFocusBackToTheButton() {
        answer = { throw SeerrException.Http(500, "boom") }
        show(MediaPages.series(), MediaPages.seriesCard())
        openModal("action Request More")
        press(Key.DirectionCenter)
        press(Key.DirectionDown, 5)
        assertFocused("primary")
        press(Key.DirectionCenter)
        gone("request modal")
        await("request problem")
        assertFocused("problem ok")
        back()
        gone("request problem")
        assertFocused("action Request More")
    }

    @Test
    fun withPartialRequestsOffTheRowsAreToReadAndRequestAsksForEveryOpenSeason() {
        show(MediaPages.series(settings = MediaPages.server.copy(partialRequestsEnabled = false)), MediaPages.seriesCard())
        openModal("action Request More")
        assertFocused("primary")
        assertEquals("Request", words("primary"))
        assertTrue("every open season goes: ticked", ticked("season 2") && ticked("season 6"))
        press(Key.DirectionUp)
        assertFocused("primary")
        press(Key.DirectionCenter)
        gone("request modal")
        assertEquals(listOf(RequestBody("tv", 9, is4k = false, seasons = listOf(2, 3, 4, 5, 6))), sent)
    }
}
