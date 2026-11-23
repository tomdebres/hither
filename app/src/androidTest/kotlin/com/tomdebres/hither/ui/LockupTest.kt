package com.tomdebres.hither.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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
import com.tomdebres.hither.core.rules.BadgeKind
import com.tomdebres.hither.core.rules.DiscoverRow
import com.tomdebres.hither.core.rules.GridCard
import com.tomdebres.hither.core.rules.PersonCard
import com.tomdebres.hither.core.rules.RequestTitle
import com.tomdebres.hither.core.rules.StatusBadge
import com.tomdebres.hither.core.rules.StatusWord
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.discover.DiscoverModel
import com.tomdebres.hither.ui.discover.DiscoverPage
import com.tomdebres.hither.ui.discover.DiscoverScreen
import com.tomdebres.hither.ui.discover.DiscoverSource
import com.tomdebres.hither.ui.grid.CardGrid
import com.tomdebres.hither.ui.grid.CardGridModel
import com.tomdebres.hither.ui.requests.RequestsModel
import com.tomdebres.hither.ui.requests.RequestsSource
import com.tomdebres.hither.ui.theme.Tokens
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The lockups (A3, DECISIONS 320; H2's X3, tvOS's `Lockups.swift`), on a device, through the screens that draw them:
 *
 * - the caption sits under the picture and never over it, at rest and lifted: a poster on Discover, a backdrop in Recent
 *   Requests, a portrait in a grid;
 * - a lifted lockup clears its shelf's header (tvOS's F31);
 * - a status glyph keeps its edge on a white poster, the worst case for the yellow and green discs.
 *
 * Read in pixels as well as bounds: a lockup's picture is drawn scaled in the layer phase, which its bounds do not show.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LockupTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val session = Session(User(id = 8, displayName = "TV", permissions = 32), PublicSettings(applicationTitle = "Seerr", localLogin = true))

    private val rows = DiscoverSource { row, _ ->
        DiscoverPage("http://seerr.test", Page(results = (1..8).map { MediaResult(id = row.ordinal * 100 + it, mediaType = "movie", title = "Film ${row.ordinal * 100 + it}", releaseDate = "2026-01-02") }))
    }

    private class Requests(private val ids: List<Int>) : RequestsSource {
        override suspend fun page(skip: Int, take: Int): RequestPage =
            if (skip == 0) RequestPage(PageInfo(results = ids.size), ids.map { request(it) }) else RequestPage()
        override suspend fun title(type: MediaType, tmdbId: Int) = RequestTitle("Request $tmdbId", "2026", null, null)

        private fun request(id: Int) = MediaRequest(
            id = id,
            status = RequestStatus.APPROVED,
            type = "movie",
            createdAt = "2026-11-10T09:00:00.000Z",
            media = MediaInfo(mediaType = "movie", tmdbId = id * 100, status = 3),
        )
    }

    private fun showDiscover(requests: List<Int>? = null) {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { DiscoverModel(rows, session, scope) }
            val recent = remember { requests?.let { RequestsModel(Requests(it), scope) } }
            if (recent != null) LaunchedEffect(recent) { recent.open() }
            DpadHost {
                Box(Modifier.fillMaxSize().background(Tokens.Background).testTag("stage")) {
                    DiscoverScreen(model, onOpen = {}, recent = recent)
                }
            }
        }
    }

    /** A grid of [cards] as search, a keyword's or a collection's titles draw them. */
    private class Grid(override val cards: List<GridCard>) : CardGridModel {
        override val gridState = LazyGridState()
        override var focusedIndex by mutableIntStateOf(0)
        override val moreFailed = false
        override val retryingMore = false
        override var returnToCard = false
        override fun focused(index: Int) {
            focusedIndex = index
        }

        override fun retry() = Unit
    }

    private fun showGrid(cards: List<GridCard>) {
        rule.setContent {
            val entry = remember { FocusRequester() }
            val model = remember { Grid(cards) }
            DpadHost {
                Box(Modifier.fillMaxSize().background(Tokens.Background).testTag("stage")) {
                    CardGrid(model, entry, onOpen = {}, modifier = Modifier)
                }
            }
            LaunchedEffect(Unit) {
                repeat(10) {
                    androidx.compose.runtime.withFrameNanos { }
                    if (runCatching { entry.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                }
            }
        }
    }

    private fun await(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun settle(ms: Long = 1_000) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        settle()
    }

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "?" } ?: "?"
    }.getOrElse { "nothing" }

    private fun awaitFocus(tag: String) {
        val held = runCatching { rule.waitUntil(5_000) { settle(200); focusedTag() == tag } }.isSuccess
        assertTrue("focus is on ${focusedTag()}, not $tag", held)
    }

    private fun screen(): PixelMap = rule.onNodeWithTag("stage").captureToImage().toPixelMap()

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private val density get() = rule.density.density

    /** Something is drawn here: not the page's black. */
    private fun lit(c: Color) = c.red + c.green + c.blue > 0.06f

    /**
     * Where the picture under [x], [y] is drawn: from a point inside it, up and down to the first rows where nothing is
     * (the picture's placeholder grey, its image or its white edge, against the page's black).
     */
    private fun drawnSpan(px: PixelMap, x: Int, y: Int): IntRange {
        var top = y
        while (top > 0 && lit(px[x, top - 1])) top--
        var bottom = y
        while (bottom < px.height - 1 && lit(px[x, bottom + 1])) bottom++
        return top..bottom
    }

    /** The words [text] as a text node of their own, or a failure saying the caption is not one (a card drew its words). */
    private fun captionOf(text: String, what: String): Rect {
        val nodes = rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("$what: \"$text\" is not a caption of its own (no text node: drawn on the picture)", nodes.isNotEmpty())
        return nodes.first().boundsInRoot
    }

    /** The caption's words start under the picture's lowest drawn row, with room between, never over it. */
    private fun assertCaptionUnder(what: String, picture: Rect, caption: Rect) {
        val px = screen()
        val span = drawnSpan(px, picture.center.x.toInt(), picture.center.y.toInt())
        val gap = (caption.top - span.last) / density
        Log.i("Lockups", "$what: picture drawn ${span.first}..${span.last} px, caption from ${caption.top} px: ${"%.1f".format(gap)} dp between")
        assertTrue("$what: the caption's words begin at ${caption.top} px, inside the picture drawn down to ${span.last} px", gap >= 2f)
    }

    @Test
    fun aPosterLockupsCaptionIsUnderItsPictureAtRestAndLifted() {
        showDiscover()
        val first = "card movie:${DiscoverRow.TRENDING.ordinal * 100 + 1}"
        val second = "card movie:${DiscoverRow.TRENDING.ordinal * 100 + 2}"
        await(second)
        awaitFocus(first)
        settle()
        // lifted: the focused lockup; at rest: its neighbour
        assertCaptionUnder("the focused poster", bounds(first), captionOf("Film ${DiscoverRow.TRENDING.ordinal * 100 + 1}", "the focused poster"))
        assertCaptionUnder("a poster at rest", bounds(second), captionOf("Film ${DiscoverRow.TRENDING.ordinal * 100 + 2}", "a poster at rest"))
    }

    @Test
    fun aBackdropLockupsCaptionIsUnderItsPictureAtRestAndLifted() {
        showDiscover(requests = listOf(3, 2, 1))
        await("recent request:2")
        awaitFocus("recent request:3")
        settle()
        assertCaptionUnder("the focused backdrop", bounds("recent request:3"), captionOf("Request 300", "the focused backdrop"))
        assertCaptionUnder("a backdrop at rest", bounds("recent request:2"), captionOf("Request 200", "a backdrop at rest"))
    }

    @Test
    fun aPortraitLockupsCaptionIsUnderItsCircleAtRestAndLifted() {
        showGrid(listOf(PersonCard(key = "person:1", personId = 1, name = "Person One", photo = null), PersonCard(key = "person:2", personId = 2, name = "Person Two", photo = null)))
        await("person person:2")
        awaitFocus("person person:1")
        settle()
        assertCaptionUnder("the focused portrait", bounds("person person:1"), captionOf("Person One", "the focused portrait"))
        assertCaptionUnder("a portrait at rest", bounds("person person:2"), captionOf("Person Two", "a portrait at rest"))
    }

    @Test
    fun aLiftedLockupClearsItsShelfsHeader() {
        showDiscover()
        await("card movie:${DiscoverRow.TRENDING.ordinal * 100 + 2}")
        awaitFocus("card movie:${DiscoverRow.TRENDING.ordinal * 100 + 1}")
        settle()
        for ((row, title) in listOf(DiscoverRow.TRENDING to "Trending", DiscoverRow.POPULAR_MOVIES to "Popular Movies")) {
            if (row != DiscoverRow.TRENDING) {
                press(Key.DirectionDown)
                awaitFocus("card movie:${row.ordinal * 100 + 1}")
                settle()
            }
            val picture = bounds("card movie:${row.ordinal * 100 + 1}")
            val header = rule.onAllNodesWithText(title, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
            val px = screen()
            val span = drawnSpan(px, picture.center.x.toInt(), picture.center.y.toInt())
            val clear = (span.first - header.bottom) / density
            Log.i("Lockups", "$title: header's line ends at ${header.bottom} px, the lifted picture is drawn from ${span.first} px: ${"%.1f".format(clear)} dp clear")
            assertTrue("$title: the lifted lockup comes within ${"%.1f".format(clear)} dp of its header (tvOS's F31: 8 at least)", clear >= 8f)
        }
    }

    // ---- a status glyph on a white poster -------------------------------------------------------------------------

    /** A white 2:3 poster on the device, for Coil to read as it reads the server's (as M6's overview test did). */
    private fun whitePoster(): String {
        val file = File(rule.activity.cacheDir, "white-poster.png")
        val bitmap = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "file://${file.absolutePath}"
    }

    /** WCAG's contrast ratio between two colours. */
    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /**
     * Each status glyph on a white poster keeps an edge WCAG's non-text rule can read (3:1 against the poster), and its
     * mark reads on its disc (3:1). The yellow (1.9:1) and green (2.3:1) discs cannot give that edge themselves: their
     * dark ring must (tvOS's F35).
     */
    @Test
    fun statusGlyphsKeepTheirEdgeAndMarkOnAWhitePoster() {
        val poster = whitePoster()
        val badges = listOf(
            StatusBadge(StatusWord.AVAILABLE, BadgeKind.SUCCESS),
            StatusBadge(StatusWord.PENDING, BadgeKind.WARNING),
            StatusBadge(StatusWord.REQUESTED, BadgeKind.PRIMARY),
            StatusBadge(StatusWord.DECLINED, BadgeKind.DANGER),
        )
        val cards = badges.mapIndexed { i, badge ->
            TitleCard(key = "movie:${i + 1}", tmdbId = i + 1, type = MediaType.MOVIE, title = "Film ${i + 1}", year = "2026", poster = poster, badge = badge)
        }
        showGrid(cards)
        await("card movie:4")
        // the posters have come (the second lockup, at rest, is white in its middle), then focus moves off them all
        val rest = bounds("card movie:2")
        rule.waitUntil(10_000) { contrast(screen()[rest.center.x.toInt(), rest.center.y.toInt()], Color.White) < 1.05f }
        settle()
        val px = screen()
        for ((i, badge) in badges.withIndex()) {
            val glyph = rule.onAllNodesWithTag("badge ${badge.text}", useUnmergedTree = true).fetchSemanticsNodes()
                .map { it.boundsInRoot }.first { bounds("card movie:${i + 1}").contains(it.center) }
            val c = glyph.center
            val disc = px[c.x.toInt(), (c.y - glyph.height * 0.38f).toInt()]
            // the ring: the circle between the disc's edge and the glyph's, sampled all the way round
            val r = glyph.width / 2f - 0.5f * density
            val ring = (0 until 36).map { k ->
                val a = Math.toRadians(k * 10.0)
                px[(c.x + r * Math.cos(a)).toInt().coerceIn(0, px.width - 1), (c.y + r * Math.sin(a)).toInt().coerceIn(0, px.height - 1)]
            }
            val edge = ring.maxOf { contrast(it, Color.White) }
            val ringWorst = ring.sortedBy { contrast(it, Color.White) }[ring.size / 2]
            // the mark: the pixel inside the disc furthest from the disc's colour
            val inside = (-6..6).flatMap { dx -> (-6..6).map { dy -> px[(c.x + dx * density).toInt(), (c.y + dy * density).toInt()] } }
            val mark = inside.maxBy { contrast(it, disc) }
            Log.i("Lockups", "${badge.text} on white: disc $disc, ring median ${"%.2f".format(contrast(ringWorst, Color.White))}:1 best ${"%.2f".format(edge)}:1, mark ${"%.2f".format(contrast(mark, disc))}:1")
            assertTrue("${badge.text}: its edge on a white poster is ${"%.2f".format(contrast(ringWorst, Color.White))}:1 round most of it, under 3:1", contrast(ringWorst, Color.White) >= 3f)
            assertTrue("${badge.text}: its mark on its disc is ${"%.2f".format(contrast(mark, disc))}:1, under 3:1", contrast(mark, disc) >= 3f)
        }
    }
}
