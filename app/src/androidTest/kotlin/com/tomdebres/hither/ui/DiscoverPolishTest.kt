package com.tomdebres.hither.ui

import com.tomdebres.hither.core.rules.ImageSource
import com.tomdebres.hither.ui.theme.PlaceholderLockup
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PageInfo
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.RequestPage
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.DiscoverRow
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.rules.RequestTitle
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.discover.DiscoverModel
import com.tomdebres.hither.ui.discover.DiscoverPage
import com.tomdebres.hither.ui.discover.DiscoverScreen
import com.tomdebres.hither.ui.discover.DiscoverSource
import com.tomdebres.hither.ui.requests.RequestsModel
import com.tomdebres.hither.ui.requests.RequestsSource
import com.tomdebres.hither.ui.theme.Tokens
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

/**
 * Discover after the polish audit (items 9-14; Tom's calls in DECISIONS 80), on a device:
 *
 * - Recent Requests heads the page when the user has requests: focus arrives on its newest card, Down
 *   and Up move between it and Trending, the bar's Down comes back to the card left, Select opens the
 *   title. With no requests there is no row and focus arrives on Trending; a row still loading holds
 *   focus and hands it on, to its first card or, when it turns out empty, to Trending;
 * - Upcoming Series ends the page;
 * - the focused poster's upper part is not darkened, its foot is; the focused card lifts 3 dp;
 * - an empty row and a failed row share one notice style, centred;
 * - a focused placeholder is drawn as the others are (no shadow through it);
 * - the bar over a scrolled page is solid gray-900.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DiscoverPolishTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val session = Session(User(id = 8, displayName = "TV", permissions = 32), PublicSettings(applicationTitle = "Seerr", localLogin = true))
    private var opened: String? = null

    private fun request(id: Int) = MediaRequest(
        id = id,
        status = RequestStatus.APPROVED,
        type = "movie",
        createdAt = "2026-11-10T09:00:00.000Z",
        media = MediaInfo(mediaType = "movie", tmdbId = id * 100, status = 3),
    )

    private class Requests(private val pages: suspend () -> RequestPage) : RequestsSource {
        override suspend fun page(skip: Int, take: Int): RequestPage = if (skip == 0) pages() else RequestPage()
        override suspend fun title(type: MediaType, tmdbId: Int) = RequestTitle("Film $tmdbId", "2026", null, null)
    }

    private fun requests(vararg ids: Int) = Requests { RequestPage(PageInfo(results = ids.size), ids.map { request(it) }) }

    private val rows = DiscoverSource { row, _ ->
        DiscoverPage(ImageSource.unknown("http://seerr.test"), Page(results = (1..8).map { MediaResult(id = row.ordinal * 100 + it, mediaType = "movie", title = "Film") }))
    }

    private lateinit var discover: DiscoverModel

    private fun show(requests: RequestsSource?, source: DiscoverSource = rows) {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { DiscoverModel(source, session, scope).also { discover = it } }
            val recent = remember { requests?.let { RequestsModel(it, scope) } }
            if (recent != null) LaunchedEffect(recent) { recent.open() }
            DpadHost {
                Box(Modifier.fillMaxSize().testTag("stage")) {
                    DiscoverScreen(model, onOpen = { opened = it.key }, recent = recent)
                }
            }
        }
    }

    private fun await(tag: String, unmerged: Boolean = false) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(600)
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

    private fun screen(): PixelMap = rule.onNodeWithTag("stage").captureToImage().toPixelMap()

    private fun levels(c: Color) = Triple((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

    private fun hex(c: Color) = levels(c).let { (r, g, b) -> "#%02X%02X%02X".format(r, g, b) }

    /** The largest difference, in 0-255 levels, between two colours on any channel. */
    private fun apart(a: Color, b: Color): Int {
        val (r1, g1, b1) = levels(a)
        val (r2, g2, b2) = levels(b)
        return max(abs(r1 - r2), max(abs(g1 - g2), abs(b1 - b2)))
    }

    /** How much lighter [a] is than [b] on its least-lighter channel, in levels (negative: darker somewhere). */
    private fun lighter(a: Color, b: Color): Int {
        val (r1, g1, b1) = levels(a)
        val (r2, g2, b2) = levels(b)
        return minOf(r1 - r2, g1 - g2, b1 - b2)
    }

    // ---- Recent Requests (item 11) -------------------------------------------------------------------

    @Test
    fun recentRequestsHeadsDiscoverWithFocusAndOpensATitle() {
        show(requests(3, 2, 1))
        await("recent request:3")
        awaitFocus("recent request:3")
        press(Key.DirectionDown)
        awaitFocus(card(DiscoverRow.TRENDING, 1))
        press(Key.DirectionUp)
        awaitFocus("recent request:3")
        press(Key.DirectionRight)
        awaitFocus("recent request:2")
        // up to the bar, and its Down comes back to the card left
        press(Key.DirectionUp)
        assertEquals("Up from Recent Requests", "nav Discover", focusedTag())
        press(Key.DirectionDown)
        awaitFocus("recent request:2")
        press(Key.DirectionCenter)
        assertEquals("movie:200", opened)
    }

    @Test
    fun withNoRequestsThereIsNoRowAndFocusArrivesOnTrending() {
        show(requests())
        await(card(DiscoverRow.TRENDING, 1))
        awaitFocus(card(DiscoverRow.TRENDING, 1))
        assertTrue("an empty Recent Requests row is shown", rule.onAllNodesWithTag("recent requests").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun recentRequestsHoldsFocusWhileLoadingAndHandsItToItsFirstCard() {
        val answer = CompletableDeferred<RequestPage>()
        show(Requests { answer.await() })
        await("recent loading")
        awaitFocus("recent loading")
        answer.complete(RequestPage(PageInfo(results = 2), listOf(request(5), request(4))))
        awaitFocus("recent request:5")
    }

    @Test
    fun recentRequestsThatTurnsOutEmptyHandsFocusToTrending() {
        val answer = CompletableDeferred<RequestPage>()
        show(Requests { answer.await() })
        await("recent loading")
        awaitFocus("recent loading")
        answer.complete(RequestPage())
        awaitFocus(card(DiscoverRow.TRENDING, 1))
        assertTrue("the row stayed", rule.onAllNodesWithTag("recent requests").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun upcomingSeriesEndsThePage() {
        show(null)
        await(card(DiscoverRow.TRENDING, 1))
        repeat(DiscoverRow.entries.size) { press(Key.DirectionDown) }
        awaitFocus(card(DiscoverRow.UPCOMING_SERIES, 1))
    }

    // ---- the lockup (A3, DECISIONS 320) ------------------------------------------------------------------------------

    /** Down from a point inside a picture, the first rows where nothing is drawn: its drawn top and bottom. */
    private fun drawnSpan(px: PixelMap, x: Int, y: Int): IntRange {
        val lit = { c: Color -> lighter(c, Tokens.Background) >= 6 }
        var top = y
        while (top > 0 && lit(px[x, top - 1])) top--
        var bottom = y
        while (bottom < px.height - 1 && lit(px[x, bottom + 1])) bottom++
        return top..bottom
    }

    @Test
    fun aFocusedLockupHasAWhiteEdgeAndNothingOverItsPicture() {
        show(null)
        await(card(DiscoverRow.TRENDING, 2))
        awaitFocus(card(DiscoverRow.TRENDING, 1))
        val rest = rule.onNodeWithTag(card(DiscoverRow.TRENDING, 1)).fetchSemanticsNode().boundsInRoot
        val px = screen()
        val x = rest.center.x.toInt()
        val span = drawnSpan(px, x, rest.center.y.toInt())
        // no poster here: the picture's placeholder grey, the same at its top and its foot (Seerr's overlay and words went)
        val upper = px[x, (span.first + (span.last - span.first) * 0.22f).toInt()]
        val foot = px[x, (span.first + (span.last - span.first) * 0.9f).toInt()]
        assertTrue("the focused picture's upper part is ${hex(upper)}, not ${hex(Tokens.Placeholder)}", apart(upper, Tokens.Placeholder) <= 2)
        assertTrue("the focused picture's foot is ${hex(foot)}: something is drawn over it", apart(foot, Tokens.Placeholder) <= 2)
        // tv-material's edge, white, at the drawn picture's top and foot
        val edgeTop = px[x, span.first + 1]
        val edgeFoot = px[x, span.last - 1]
        assertTrue("the focused lockup's edge is ${hex(edgeTop)} at its top, ${hex(edgeFoot)} at its foot, not white", apart(edgeTop, Color.White) <= 8 && apart(edgeFoot, Color.White) <= 8)
    }

    @Test
    fun aFocusedLockupScalesOnePointOneAboutItsCentreAndDoesNotLift() {
        show(null)
        await(card(DiscoverRow.TRENDING, 2))
        awaitFocus(card(DiscoverRow.TRENDING, 1))
        // the bounds are the picture's place at rest; it is drawn scaled in the layer phase, which they do not show
        val rest = rule.onNodeWithTag(card(DiscoverRow.TRENDING, 2)).fetchSemanticsNode().boundsInRoot
        val focused = rule.onNodeWithTag(card(DiscoverRow.TRENDING, 1)).fetchSemanticsNode().boundsInRoot
        val px = screen()
        val drawn = drawnSpan(px, focused.center.x.toInt(), focused.center.y.toInt())
        val atRest = drawnSpan(px, rest.center.x.toInt(), rest.center.y.toInt())
        // tv-material's 3 dp edge is centred on the picture's outline: 1.5 dp of it outside, scaled with the picture
        val edge = 3 * rule.density.density
        val height = (atRest.last - atRest.first + 1).toFloat()
        val scaled = (drawn.last - drawn.first + 1) / (height + edge)
        val middle = (drawn.first + drawn.last) / 2f - (atRest.first + atRest.last) / 2f
        assertEquals("the focused lockup's scale (drawn ${drawn.first}..${drawn.last}, at rest ${atRest.first}..${atRest.last})", 1.1f, scaled, 0.015f)
        assertEquals("the focused lockup's middle moved: it is lifted", 0f, middle, 2f)
    }

    // ---- row notices, placeholders, the bar (items 9, 13, 14) ----------------------------------------

    @Test
    fun anEmptyRowAndAFailedRowShareOneNoticeStyleCentred() {
        val source = DiscoverSource { row, _ ->
            when (row) {
                DiscoverRow.POPULAR_SERIES -> throw IllegalStateException("this row does not load")
                DiscoverRow.UPCOMING_MOVIES -> DiscoverPage(ImageSource.unknown("http://seerr.test"), Page(results = emptyList()))
                else -> DiscoverPage(ImageSource.unknown("http://seerr.test"), Page(results = (1..8).map { MediaResult(id = row.ordinal * 100 + it, mediaType = "movie", title = "Film") }))
            }
        }
        show(null, source)
        await(card(DiscoverRow.TRENDING, 1))
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        // the notices' words sit inside their row's one target, whose semantics merge them; the failed row on the line
        await("notice text POPULAR_SERIES", unmerged = true)
        val failed = rule.onNodeWithTag("notice text POPULAR_SERIES", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        // the empty row, which focus passes over, is below the screen under A3's taller rows: the list is put at it
        rule.runOnIdle { discover.columnState.requestScrollToItem(discover.rows.indexOfFirst { it.row == DiscoverRow.UPCOMING_MOVIES }) }
        settle()
        await("notice text UPCOMING_MOVIES", unmerged = true)
        val empty = rule.onNodeWithTag("notice text UPCOMING_MOVIES", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val width = rule.onRoot().fetchSemanticsNode().size.width
        assertEquals("the two notices' text differ in size", failed.height, empty.height, 1f)
        assertEquals("the empty row's notice is not centred", width / 2f, empty.center.x, 3f)
    }

    @Test
    fun aFocusedPlaceholderIsDrawnAsTheOthersAre() {
        // Still since A3 (tvOS's placeholders): the focused one has the lockup's white edge and the others' grey inside.
        rule.setContent {
            Box(Modifier.fillMaxSize().background(Tokens.Background).testTag("stage")) {
                Row(Modifier.padding(80.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PlaceholderLockup(Modifier.testTag("focused placeholder"), focused = true)
                    PlaceholderLockup(Modifier.testTag("placeholder"))
                }
            }
        }
        rule.waitForIdle()
        settle()
        val px = screen()
        val first = rule.onNodeWithTag("focused placeholder").fetchSemanticsNode().boundsInRoot.center
        val second = rule.onNodeWithTag("placeholder").fetchSemanticsNode().boundsInRoot.center
        val a = px[first.x.toInt(), first.y.toInt()]
        val b = px[second.x.toInt(), second.y.toInt()]
        assertTrue("the focused placeholder is ${hex(a)}, the next ${hex(b)}", apart(a, b) <= 2)
    }

    /**
     * A2 (DECISIONS 314): the bar scrolls away with the rows, so over a scrolled page there is no bar, solid or not: the
     * band where it was draws the row above (84's solid fill and 90's undrawn rows went with it).
     */
    @Test
    fun theBarScrollsAwayFromAScrolledPage() {
        show(null)
        await(card(DiscoverRow.TRENDING, 1))
        press(Key.DirectionDown)
        awaitFocus(card(DiscoverRow.POPULAR_MOVIES, 1))
        settle()
        val bar = rule.onNodeWithTag("bar", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("the bar is off the screen: it ends at ${bar.positionInRoot.y + bar.size.height} px", bar.positionInRoot.y + bar.size.height <= 0f)
        val px = screen()
        val lit = (0 until px.width step 4).count { x -> apart(px[x, 40], Tokens.Background) > 20 }
        assertTrue("the band the bar left draws the row above ($lit lit pixels on a line through it)", lit > 20)
    }
}
