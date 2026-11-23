package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.model.IssuePage
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.RequestPage
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.ready.Front
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.LocalRecompositionProbe
import com.tomdebres.hither.ui.home.HomeScreen
import com.tomdebres.hither.ui.home.HomeSide
import com.tomdebres.hither.ui.theme.Tokens
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The bar's look (tv-material's tabs since A2): the current tab said by its white words, the others grey, the focused
 * one black on the white pill, and the bar never recomposed for a move along it (DECISIONS 57; words only since Tom's
 * 150: `TabsTest` holds that, and that the current tab is never a fill, on every tab page); and the Requests count, a
 * white circle for one digit and a pill with the same round ends for two (146).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class BarLookTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val user = User(id = 8, displayName = "TV", permissions = Permission.REQUEST or Permission.VIEW_ISSUES)
    private val session = Session(user, PublicSettings(applicationTitle = "Seerr", localLogin = true))
    private val counts = HashMap<String, Int>()

    private val access = object : SeerrAccess {
        private val client = SeerrClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                val json = SeerrClient.json
                val body = when {
                    path == "/api/v1/auth/me" -> json.encodeToString(User.serializer(), user)
                    path == "/api/v1/issue" -> json.encodeToString(IssuePage.serializer(), IssuePage())
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

    private fun show(count: Int = 2, probe: Boolean = false) {
        val side = HomeSide(front = { Front(emptyList(), requestsCount = count, waiting = null) })
        rule.setContent {
            CompositionLocalProvider(LocalRecompositionProbe provides if (probe) { name -> counts[name] = (counts[name] ?: 0) + 1 } else null) {
                DpadHost { HomeScreen(Screen.Home(session), access, onSignOut = {}, side = side) }
            }
        }
        await("card movie:1")
        // arrival settles on the top row's first card (Discover's own hand-offs as its rows come)
        runCatching { rule.waitUntil(5_000) { rule.mainClock.advanceTimeBy(200); rule.waitForIdle(); focusedTag() == "card movie:1" } }
        await("nav Issues")
        if (count > 0) await("requests count")
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    private fun await(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
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

    /** Bounds in px of the node tagged [tag], unclipped (the bar is on screen). */
    private fun px(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun wordsPx(pill: String): Rect =
        rule.onNode(hasText(pill) and hasAnyAncestor(hasTestTag("nav $pill")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun dp(px: Float) = px / rule.density.density

    /** The brightest pixel in [box]: a stroke's or a letter's own colour where it covers a whole pixel. */
    private fun brightest(map: PixelMap, box: Rect): Color {
        var best = Color.Black
        for (x in box.left.roundToInt() until box.right.roundToInt()) for (y in box.top.roundToInt() until box.bottom.roundToInt()) {
            val c = map[x, y]
            if (c.red + c.green + c.blue > best.red + best.green + best.blue) best = c
        }
        return best
    }

    private fun assertColour(what: String, expected: Color, actual: Color) {
        val off = maxOf(abs(expected.red - actual.red), abs(expected.green - actual.green), abs(expected.blue - actual.blue)) * 255
        assertTrue("$what: expected $expected, was $actual ($off levels off)", off <= 10f)
    }

    @Test
    fun theCurrentTabIsSaidByItsWords() {
        show()
        // At rest: Discover, the current page, in white words (no fill: rule 3); Requests and Issues grey.
        var map = rule.onRoot().captureToImage().toPixelMap()
        for ((pill, colour) in listOf("Discover" to Tokens.Text, "Requests" to Tokens.TextSecondary, "Issues" to Tokens.TextSecondary)) {
            assertColour("$pill's words at rest", colour, brightest(map, wordsPx(pill)))
        }
        // Focus on Requests shows its page (A2): its words black on tv-material's white pill, Discover's grey now.
        press(Key.DirectionUp)
        assertFocused("nav Discover")
        press(Key.DirectionRight)
        assertFocused("nav Requests")
        map = rule.onRoot().captureToImage().toPixelMap()
        val pill = px("nav Requests")
        assertColour("the focused tab's pill", Tokens.Text, map[(pill.left + 3 * rule.density.density).roundToInt(), pill.center.y.roundToInt()])
        assertColour("Requests' words with focus, on the pill", Color.Black, darkest(map, wordsPx("Requests")))
        assertColour("Discover's words, no longer current", Tokens.TextSecondary, brightest(map, wordsPx("Discover")))
        assertColour("Issues' words at rest", Tokens.TextSecondary, brightest(map, wordsPx("Issues")))
    }

    /** The darkest pixel in [box]: a letter's own colour on a light ground. */
    private fun darkest(map: PixelMap, box: Rect): Color {
        var best = Color.White
        for (x in box.left.roundToInt() until box.right.roundToInt()) for (y in box.top.roundToInt() until box.bottom.roundToInt()) {
            val c = map[x, y]
            if (c.red + c.green + c.blue < best.red + best.green + best.blue) best = c
        }
        return best
    }

    /** A focus move along the bar eases the tabs' colours in the draw phase (DECISIONS 57): the bar does not recompose. */
    @Test
    fun aFocusMoveAlongTheBarDoesNotRecomposeIt() {
        show(probe = true)
        press(Key.DirectionUp)
        assertFocused("nav Discover")
        rule.runOnIdle { counts.clear() }
        press(Key.DirectionRight)
        assertFocused("nav Requests")
        press(Key.DirectionRight)
        assertFocused("nav Issues")
        val moves = rule.runOnIdle { HashMap(counts) }
        assertEquals("the bar recomposed: $moves", 0, moves["TopBar"] ?: 0)
    }

    /** Whether the pixel at [x], [y] is the badge's white fill (A1: white, black numerals), not the page's black. */
    private fun badge(map: PixelMap, x: Float, y: Float): Boolean = map[x.roundToInt(), y.roundToInt()].green * 255 > 150

    /**
     * The badge as drawn, in px: from the middle of [inner] (the tagged node, inside the badge's padding) out along
     * its middle row and column to the last of the badge's pixels, stepping over the black numerals' strokes (a gap of
     * up to 3 dp) but not the 8 dp between the badge and Requests' words. Read from the pixels, not a node's bounds, so
     * the shape is measured wherever the tag sits.
     */
    private fun drawnBadge(map: PixelMap, inner: Rect): Rect {
        val gap = 3f * rule.density.density
        fun reach(from: Float, step: Float, at: (Float) -> Boolean): Float {
            var last = from
            var p = from
            while (abs(p - last) <= gap) {
                p += step
                if (at(p)) last = p
            }
            return last
        }
        val cx = inner.center.x
        val cy = inner.center.y
        val l = reach(cx, -1f) { badge(map, it, cy) }
        val r = reach(cx, 1f) { badge(map, it, cy) }
        val t = reach(cy, -1f) { badge(map, cx, it) }
        val b = reach(cy, 1f) { badge(map, cx, it) }
        return Rect(l.roundToInt().toFloat(), t.roundToInt().toFloat(), r.roundToInt() + 1f, b.roundToInt() + 1f)
    }

    /**
     * The count's corners are the page's, its sides' middles the badge's: round ends, not a rounded square. 1.5 dp in
     * from each corner along both edges lies outside a circle (or a pill's round end) of 18 dp, and inside the 4 dp
     * rounded square it was.
     */
    private fun assertRoundEnds(what: String, map: PixelMap, b: Rect) {
        val inset = 1.5f * rule.density.density
        val corners = listOf(b.left + inset to b.top + inset, b.right - inset to b.top + inset, b.left + inset to b.bottom - inset, b.right - inset to b.bottom - inset)
        for ((x, y) in corners) assertTrue("$what: the corner at ${dp(x - b.left)}, ${dp(y - b.top)} dp is the badge's, not round", !badge(map, x - 0.5f, y - 0.5f))
        val edge = 1f * rule.density.density
        val sides = listOf(b.left + edge to b.center.y, b.right - edge to b.center.y, b.center.x to b.top + edge, b.center.x to b.bottom - edge)
        for ((x, y) in sides) assertTrue("$what: the side's middle at ${dp(x - b.left)}, ${dp(y - b.top)} dp is not the badge's", badge(map, x - 0.5f, y - 0.5f))
    }

    @Test
    fun aOneDigitCountIsACircle() {
        show(count = 2)
        val map = rule.onRoot().captureToImage().toPixelMap()
        val b = drawnBadge(map, px("requests count"))
        assertEquals("one digit: the count's drawn width equals its height, in dp", dp(b.height), dp(b.width), 0.5f)
        assertEquals("the count's drawn height, in dp", 18f, dp(b.height), 0.5f)
        assertRoundEnds("one digit", map, b)
    }

    @Test
    fun aTwoDigitCountIsAPillWithTheSameRoundEnds() {
        show(count = 12)
        val map = rule.onRoot().captureToImage().toPixelMap()
        val b = drawnBadge(map, px("requests count"))
        assertEquals("the count's drawn height, in dp", 18f, dp(b.height), 0.5f)
        assertTrue("two digits: wider than high (${dp(b.width)} by ${dp(b.height)} dp)", b.width > b.height + rule.density.density)
        assertRoundEnds("two digits", map, b)
    }
}
