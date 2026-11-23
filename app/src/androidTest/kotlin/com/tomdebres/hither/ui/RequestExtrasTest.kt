package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.model.DownloadItem
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.PageInfo
import com.tomdebres.hither.core.model.Quota
import com.tomdebres.hither.core.model.QuotaStatus
import com.tomdebres.hither.core.model.RequestPage
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.rules.MediaPage
import com.tomdebres.hither.core.rules.RequestTitle
import com.tomdebres.hither.core.rules.moviePage
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.media.MediaModel
import com.tomdebres.hither.ui.media.MediaScreen
import com.tomdebres.hither.ui.requests.RequestsModel
import com.tomdebres.hither.ui.requests.RequestsScreen
import com.tomdebres.hither.ui.requests.RequestsSource
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import com.tomdebres.hither.core.model.User
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M6's request parts on a device (DECISIONS 101): Cancel Request on a pending request of My Requests (its
 * lockup's long-press menu since A6, 341), asking first (Keep Request focused, Back keeps it), one DELETE, the
 * lockup going with focus on the next; a download's progress inside its badge with the time left, on My Requests
 * and the media page; the request modal's quota line, a season past the quota greyed and staying off.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RequestExtrasTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val inAnHour = Instant.now().plusSeconds(3600 + 60).toString()
    private val download = DownloadItem(title = "x", status = "downloading", size = 100.0, sizeLeft = 58.0, estimatedCompletionTime = inAnHour, downloadId = "d")

    private fun request(id: Int, status: Int, downloads: List<DownloadItem> = emptyList()) = MediaRequest(
        id = id,
        status = status,
        type = "movie",
        createdAt = "2026-11-10T09:00:00.000Z",
        media = MediaInfo(mediaType = "movie", tmdbId = id * 100, status = 3, downloadStatus = downloads),
    )

    private val cancelled = CopyOnWriteArrayList<Int>()

    private inner class Source(private val requests: List<MediaRequest>) : RequestsSource {
        override suspend fun page(skip: Int, take: Int): RequestPage =
            if (skip == 0) RequestPage(PageInfo(results = requests.size), requests.filter { it.id !in cancelled }) else RequestPage()

        override suspend fun title(type: MediaType, tmdbId: Int) = RequestTitle("Film $tmdbId", "2026", null, null)

        override suspend fun cancel(requestId: Int) {
            cancelled += requestId
        }
    }

    private fun showRequests(requests: List<MediaRequest>) {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { RequestsModel(Source(requests), scope) }
            DpadHost { RequestsScreen(model, onOpen = {}, onBack = {}) }
        }
        await("request request:${requests.first().id}")
    }

    private fun showPage(page: MediaPage) {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { MediaModel(source = { _, _ -> page }, card = MediaPages.card(7), scope = scope) }
            DpadHost { MediaScreen(model) }
        }
        await("media title")
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

    private fun words(tag: String): String = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        // the node and everything under it: a tv-material button's words sit a few levels down (A1)
        .flatMap { node -> generateSequence(listOf(node)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }.flatten().toList() }
        .flatMap { it.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty() }
        .joinToString("") { it.text }

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "?" } ?: "?"
    }.getOrElse { "nothing" }

    private fun assertFocused(tag: String) {
        runCatching { rule.waitUntil(5_000) { focusedTag() == tag } }
        val now = focusedTag()
        if (now != tag) throw AssertionError("expected focus on $tag, it is on $now")
    }

    /** Select held [ms] (a test's key input repeats it from 500 ms), then let go. */
    private fun hold(ms: Long) {
        rule.onRoot().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(ms)
            keyUp(Key.DirectionCenter)
        }
        rule.waitForIdle()
    }

    /** The words of [request]'s lockup line ("2 days ago by User 1"), whole, whichever part is drawn. */
    private fun lineOf(request: Int): String =
        rule.onNode(hasTestTag("card line") and hasAnyAncestor(hasTestTag("lockup request request:$request")), useUnmergedTree = true)
            .fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().joinToString("") { it.text }

    /**
     * Seerr's "Modified {when} by {who}" on a request somebody has moved on, saying who approved or declined (Tom's pick,
     * the polish 2 audit's item 23): since A6 the line under the lockup's status (tvOS's); a request nobody has moved on
     * says when it was asked for.
     */
    @Test
    fun aLockupSomebodyMovedOnSaysWhoAndWhen() {
        val approved = request(1, RequestStatus.APPROVED).copy(modifiedBy = User(id = 9, displayName = "Approver"), updatedAt = "2026-11-12T09:00:00.000Z")
        showRequests(listOf(approved, request(2, RequestStatus.PENDING)))
        val moved = lineOf(1)
        assertTrue("when, by, who ($moved)", moved.endsWith(" by Approver") && moved.length > " by Approver".length)
        val asked = lineOf(2)
        assertTrue("nobody moved the pending one ($asked)", asked.isNotEmpty() && " by " !in asked)
    }

    @Test
    fun aPendingLockupOffersCancelRequestWhichAsksFirstAndBackKeepsIt() {
        showRequests(listOf(request(1, RequestStatus.PENDING), request(2, RequestStatus.APPROVED)))
        press(Key.DirectionRight)
        assertFocused("request request:1")
        // an approved request has no menu
        press(Key.DirectionRight)
        assertFocused("request request:2")
        hold(1_000)
        rule.onAllNodesWithTag("request menu").assertCountEquals(0)
        rule.onAllNodesWithTag("cancel request:2").assertCountEquals(0)
        press(Key.DirectionLeft)
        assertFocused("request request:1")
        hold(1_000)
        await("request menu")
        assertFocused("cancel request:1")
        press(Key.DirectionCenter)
        await("confirm cancel request")
        assertFocused("keep request")
        back()
        rule.onAllNodesWithTag("confirm cancel request").assertCountEquals(0)
        assertFocused("request request:1")
        assertEquals("nothing cancelled", emptyList<Int>(), cancelled.toList())
    }

    @Test
    fun confirmingCancelsOnceAndTheLockupGoesWithFocusOnTheNext() {
        // A lockup in the middle: focus goes to the one after it, not back to the first.
        showRequests(listOf(request(1, RequestStatus.APPROVED), request(2, RequestStatus.PENDING), request(3, RequestStatus.APPROVED)))
        press(Key.DirectionRight)
        assertFocused("request request:1")
        press(Key.DirectionRight)
        assertFocused("request request:2")
        hold(1_000)
        await("request menu")
        press(Key.DirectionCenter)
        await("confirm cancel request")
        // Cancel Request sits over Keep Request in the dialog (A1's ConfirmDialog)
        press(Key.DirectionUp)
        assertFocused("confirm")
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("request request:2").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(2), cancelled.toList())
        assertFocused("request request:3")
        assertEquals("no dialog: it went through", 0, rule.onAllNodesWithTag("cancel problem").fetchSemanticsNodes().size)
    }

    @Test
    fun aDownloadFillsItsBadgeWithTheTimeLeftOnMyRequests() {
        showRequests(listOf(request(1, RequestStatus.APPROVED, listOf(download))))
        rule.onNodeWithTag("status Processing", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "42%"))
        // the time left is the lockup's line (A6), where the row had Seerr's field
        val estimate = lineOf(1)
        assertTrue("the estimate reads \"$estimate\"", estimate.startsWith("Estimated in 1 hour") || estimate.startsWith("Estimated in 61 minutes"))
    }

    @Test
    fun theMediaPageShowsTheDownloadAndItsTimeLeft() {
        showPage(moviePage(MovieDetails(id = 7, title = "Film 7", mediaInfo = MediaInfo(tmdbId = 7, status = 3, downloadStatus = listOf(download))), MediaPages.BASE, MediaPages.server, MediaPages.account))
        rule.onNodeWithTag("status Processing", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "42%"))
        await("download estimate")
    }

    @Test
    fun thePanelSaysWhatIsLeftOfTheQuotaInWordsAndASeasonPastItStaysOff() {
        showPage(MediaPages.series().copy(quota = Quota(tv = QuotaStatus(days = 7, limit = 2, used = 1, remaining = 1))))
        press(Key.DirectionCenter)
        await("request modal")
        // the quota as a sentence under the rows (tvOS's S16: words, not Seerr's heat-coloured ring), after "approved
        // automatically" where the account's rights say so
        val notes = words("approved automatically").ifEmpty { words("request notes") }
        assertTrue("the notes read \"$notes\"", notes.endsWith("1 season request left. You may request 2 seasons every 7 days."))
        rule.onAllNodesWithTag("quota ring", useUnmergedTree = true).assertCountEquals(0)
        assertFocused("season 2")
        rule.onNodeWithTag("season 3").assertIsEnabled()
        press(Key.DirectionCenter)
        // the one season left is used: the rest are greyed
        rule.onNodeWithTag("season 3").assertIsNotEnabled()
        rule.onNodeWithTag("season 2").assertIsEnabled()
        // a greyed season is no stop (tvOS's: disabled): Down passes the rest to the primary, and season 3 stays off
        press(Key.DirectionDown)
        assertFocused("primary")
        rule.onNodeWithTag("season 3").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "off"))
        rule.onNodeWithTag("season 2").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "on"))
        assertEquals("Request 1 Season", words("primary"))
    }
}
