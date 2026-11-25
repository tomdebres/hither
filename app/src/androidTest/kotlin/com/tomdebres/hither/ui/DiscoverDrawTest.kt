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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
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
import com.tomdebres.hither.core.rules.DiscoverRow
import com.tomdebres.hither.core.rules.RequestTitle
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.discover.DiscoverModel
import com.tomdebres.hither.ui.discover.DiscoverPage
import com.tomdebres.hither.ui.discover.DiscoverScreen
import com.tomdebres.hither.ui.discover.DiscoverSource
import com.tomdebres.hither.ui.requests.RequestsModel
import com.tomdebres.hither.ui.requests.RequestsSource
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What replaced DECISIONS 90's cull (A2, 314): the bar scrolls away with Discover's rows, so nothing is ever under it and
 * nothing is left undrawn there. A row scrolled up into the band the bar left is on screen and drawn; started on
 * Trending (`scripts/measure-discover`'s launch extra), focus arrives on Trending's first card with the bar away and
 * Recent Requests drawn above; Up to Recent Requests brings the bar back. Read from the pixels of the band.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DiscoverDrawTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val session = Session(User(id = 8, displayName = "TV", permissions = 32), PublicSettings(applicationTitle = "Seerr", localLogin = true))

    private fun request(id: Int) = MediaRequest(
        id = id,
        status = RequestStatus.APPROVED,
        type = "movie",
        createdAt = "2026-11-10T09:00:00.000Z",
        media = MediaInfo(mediaType = "movie", tmdbId = id * 100, status = 3),
    )

    private class Requests(private val page: RequestPage) : RequestsSource {
        override suspend fun page(skip: Int, take: Int): RequestPage = if (skip == 0) page else RequestPage()
        override suspend fun title(type: MediaType, tmdbId: Int) = RequestTitle("Film $tmdbId", "2026", null, null)
    }

    private fun requests(vararg ids: Int) = Requests(RequestPage(PageInfo(results = ids.size), ids.map { request(it) }))

    private val rows = DiscoverSource { row, _ ->
        DiscoverPage(ImageSource.unknown("http://seerr.test"), Page(results = (1..8).map { MediaResult(id = row.ordinal * 100 + it, mediaType = "movie", title = "Film") }))
    }

    private fun show(requests: RequestsSource?, startOnRows: Boolean = false) {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { DiscoverModel(rows, session, scope).also { if (startOnRows) it.startOnRows() } }
            val recent = remember { requests?.let { RequestsModel(it, scope) } }
            if (recent != null) LaunchedEffect(recent) { recent.open() }
            DpadHost {
                Box(Modifier.fillMaxSize().testTag("stage")) {
                    DiscoverScreen(model, onOpen = {}, recent = recent)
                }
            }
        }
    }

    private fun await(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    /** Longer than the page's scroll. */
    private fun settle() {
        rule.mainClock.advanceTimeBy(800)
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
        settle()
    }

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "?" } ?: "?"
    }.getOrElse { "nothing" }

    private fun awaitFocus(tag: String) {
        val held = runCatching { rule.waitUntil(5_000) { settle(); focusedTag() == tag } }.isSuccess
        assertTrue("focus is on ${focusedTag()}, not $tag", held)
    }

    private fun card(row: DiscoverRow, n: Int) = "card movie:${row.ordinal * 100 + n}"

    /** Where the bar's line ends, in dp: at or above 0 once it has scrolled away. */
    private fun barBottom(): Float = rule.onNodeWithTag("bar", useUnmergedTree = true).fetchSemanticsNode().let { (it.positionInRoot.y + it.size.height) / rule.density.density }

    /** Pixels drawn (not the black ground) across the top 60 dp, every fourth column: the band the bar leaves. */
    private fun bandLit(): Int {
        val map = rule.onRoot().captureToImage().toPixelMap()
        val rowsPx = (60 * rule.density.density).toInt()
        return (0 until rowsPx).sumOf { y -> (0 until map.width step 4).count { x -> map[x, y].let { it.red + it.green + it.blue > 0.3f } } }
    }

    @Test
    fun aRowScrolledIntoTheBandTheBarLeftIsDrawn() {
        show(requests(3, 2, 1))
        await("recent request:3")
        awaitFocus("recent request:3")
        val atTop = bandLit()
        assertTrue("at the top the bar is there: it ends at ${barBottom()} dp", barBottom() > 0f)
        press(Key.DirectionDown)
        awaitFocus(card(DiscoverRow.TRENDING, 1))
        assertTrue("Trending focused, the bar has gone: it ends at ${barBottom()} dp", barBottom() <= 0f)
        val scrolled = bandLit()
        assertTrue("Trending focused, Recent Requests drawn in the band: $scrolled lit pixels (at the top, $atTop)", scrolled > atTop + 50)
        press(Key.DirectionUp)
        awaitFocus("recent request:3")
        assertTrue("back on the top row, the bar is back: it ends at ${barBottom()} dp", barBottom() > 0f)
    }

    @Test
    fun startedOnTrendingTheBarIsAwayAndRecentRequestsIsDrawnAbove() {
        show(requests(3, 2, 1), startOnRows = true)
        await("recent request:3")
        awaitFocus(card(DiscoverRow.TRENDING, 1))
        assertTrue("started on Trending, the bar has gone: it ends at ${barBottom()} dp", barBottom() <= 0f)
        assertTrue("Recent Requests drawn above Trending", bandLit() > 50)
    }
}
