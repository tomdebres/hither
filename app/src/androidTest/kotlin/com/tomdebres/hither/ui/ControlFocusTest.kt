package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.FocusOnArrival
import com.tomdebres.hither.ui.components.LocalRecompositionProbe
import com.tomdebres.hither.ui.components.EntryField
import com.tomdebres.hither.ui.discover.DiscoverModel
import com.tomdebres.hither.ui.discover.DiscoverPage
import com.tomdebres.hither.ui.discover.DiscoverScreen
import com.tomdebres.hither.ui.discover.DiscoverSource
import com.tomdebres.hither.ui.theme.PlainButton
import com.tomdebres.hither.ui.theme.ProminentButton
import com.tomdebres.hither.ui.theme.Tokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

/**
 * Focus shows on every control at ten feet (the polish audit, items 1-5 and 22; Tom, 2026-11-11), in A1's look (DECISIONS
 * 292-294): tv-material's buttons, no shadow, Seerr's palette gone. Read from the pixels the device draws:
 *
 * - the one prominent button is indigo, and with focus keeps it and gains a white edge; a plain button is grey at rest
 *   and white with focus (tv-material's own);
 * - a focus move between two buttons scales them in the layer phase, and recomposes each at most once each way;
 * - the bar's current tab says so by its words, never a fill: at rest there is nothing behind it;
 * - the bar's search pill and the search page's field are a field's dark grey at rest and the focused white with
 *   focus, and grow 1.03 (a wide thing, 2.3); the avatar fills a lighter grey with a light ring.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ControlFocusTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val counts = HashMap<String, Int>()

    /** A plain button at rest: tv-material's container, the theme's surface at 80%, over the page. */
    private val restingPlain = Tokens.Surface.copy(alpha = 0.8f).compositeOver(Tokens.Background)

    private fun settle(ms: Long = 1_000) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun await(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** The whole screen as drawn (the stage fills it), so a control's scaled edge is never cut off. */
    private fun screen(): PixelMap = rule.onNodeWithTag("stage").captureToImage().toPixelMap()

    private fun hex(c: Color) = "#%02X%02X%02X".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

    private fun distance(a: Color, b: Color) = max(abs(a.red - b.red), max(abs(a.green - b.green), abs(a.blue - b.blue)))

    private fun assertNear(what: String, expected: Color, actual: Color, tolerance: Float = 0.035f) =
        assertTrue("$what: expected about ${hex(expected)}, drew ${hex(actual)}", distance(expected, actual) <= tolerance)

    private fun light(c: Color, at: Float) = c.red >= at && c.green >= at && c.blue >= at

    /** Whether a near-white (or [at]-light) pixel lies on the horizontal line [y] between [from] and [to]. */
    private fun PixelMap.lightBetween(from: Float, to: Float, y: Float, at: Float = 0.82f): Boolean =
        (from.toInt().coerceAtLeast(0)..to.toInt().coerceAtMost(width - 1)).any { x -> light(this[x, y.toInt()], at) }

    private fun showButtons() {
        rule.setContent {
            CompositionLocalProvider(LocalRecompositionProbe provides { name -> counts[name] = (counts[name] ?: 0) + 1 }) {
                val first = remember { FocusRequester() }
                DpadHost {
                    Box(Modifier.fillMaxSize().background(Tokens.Background).testTag("stage")) {
                        Row(Modifier.padding(80.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ProminentButton("Request", onClick = {}, modifier = Modifier.focusRequester(first).testTag("a"))
                            PlainButton("Request in 4K", onClick = {}, modifier = Modifier.testTag("b"))
                        }
                    }
                }
                FocusOnArrival(first)
            }
        }
        await("a")
        settle()
    }

    @Test
    fun theProminentButtonIsIndigoWithAWhiteEdgeWhenFocusedAndAPlainOneTurnsWhite() {
        showButtons()
        val a = bounds("a")
        val b = bounds("b")
        val px = screen()
        val y = a.center.y
        assertNear("the focused prominent button's fill", Tokens.Accent, px[(a.left + 14).toInt(), y.toInt()])
        assertTrue("the focused prominent button has a white edge", px.lightBetween(a.left - 2, a.left + 6, y))
        assertNear("the plain button at rest", restingPlain, px[(b.left + 14).toInt(), b.center.y.toInt()])

        press(Key.DirectionRight)
        settle()
        val a2 = bounds("a")
        val b2 = bounds("b")
        val moved = screen()
        assertNear("the prominent button at rest keeps its indigo", Tokens.Accent, moved[(a2.left + 14).toInt(), a2.center.y.toInt()])
        assertTrue("the prominent button at rest has no white edge", !moved.lightBetween(a2.left - 4, a2.left + 3, a2.center.y))
        assertNear("the focused plain button is white", Color.White, moved[(b2.left + 14).toInt(), b2.center.y.toInt()], tolerance = 0.06f)
    }

    @Test
    fun aFocusMoveScalesInTheLayerPhaseAndRecomposesEachButtonOnceEachWay() {
        showButtons()
        val rest = bounds("b").width
        rule.mainClock.autoAdvance = false
        counts.clear()
        press(Key.DirectionRight)
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(80)
        val halfway = bounds("b").width / rest
        assertTrue("halfway through the scale the button is between 1 and 1.1 ($halfway)", halfway > 1.005f && halfway < 1.095f)
        rule.mainClock.advanceTimeBy(600)
        val grew = bounds("b").width / rest
        assertTrue("the focused button grows 1.1, grew $grew", grew in 1.09f..1.11f)
        val gained = counts["button Request in 4K"] ?: 0
        val lost = counts["button Request"] ?: 0
        assertTrue("the move recomposed the buttons more than once each way (gained $gained, lost $lost; $counts)", gained <= 2 && lost <= 2)
    }

    private fun showBar() {
        val session = Session(User(id = 8, displayName = "TV", permissions = 32), PublicSettings(applicationTitle = "Seerr", localLogin = true))
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember {
                DiscoverModel(
                    DiscoverSource { row, _ ->
                        DiscoverPage("http://seerr.test", Page(results = (1..8).map { MediaResult(id = row.ordinal * 100 + it, mediaType = "movie", title = "Film") }))
                    },
                    session,
                    scope,
                )
            }
            DpadHost { Box(Modifier.fillMaxSize().testTag("stage")) { DiscoverScreen(model, onOpen = {}) } }
        }
        await("card movie:1")
        press(Key.DirectionUp)
        settle()
    }

    @Test
    fun theCurrentTabSaysSoByItsWordsNotAFill() {
        showBar()
        val tab = bounds("nav Discover")
        val at = { px: PixelMap -> px[(tab.left + 4).toInt(), tab.center.y.toInt()] }
        val focused = at(screen())
        press(Key.DirectionRight)
        settle()
        val rest = at(screen())
        assertNear("the current tab at rest, focus on the next tab: nothing behind it (rule 3)", Tokens.Background, rest, tolerance = 0.03f)
        assertNear("the current tab with focus: tv-material's white pill behind it (A2)", Tokens.Text, focused, tolerance = 0.05f)
    }

    /** Search and the account are tv-material icon buttons (A2): nothing behind the glyph at rest, white with focus, 1.1. */
    @Test
    fun theSearchAndAccountIconsShowFocus() {
        showBar()
        // to Requests (on a page shown on its own the bar's tabs change nothing)
        press(Key.DirectionRight)
        settle()
        val searchRest = bounds("search")
        val pxRest = screen()
        assertNear("the search icon at rest: nothing behind it", Tokens.Background, pxRest[(searchRest.left + 3).toInt(), searchRest.center.y.toInt()], tolerance = 0.03f)
        val avatarRest = bounds("avatar")
        assertNear("the account icon at rest: nothing behind it", Tokens.Background, pxRest[(avatarRest.left + 3).toInt(), avatarRest.center.y.toInt()], tolerance = 0.03f)

        press(Key.DirectionRight)
        settle()
        val search = bounds("search")
        val px = screen()
        assertNear("the focused search icon: white behind the glass", Tokens.Text, px[(search.left + 4).toInt(), search.center.y.toInt()], tolerance = 0.05f)
        val grew = search.width / searchRest.width
        assertTrue("the search icon grows 1.1 with focus, grew $grew", grew in 1.08f..1.12f)

        press(Key.DirectionRight)
        settle()
        val avatar = bounds("avatar")
        val pxAvatar = screen()
        assertNear("the focused account: white behind the person", Tokens.Text, pxAvatar[(avatar.left + 4).toInt(), avatar.center.y.toInt()], tolerance = 0.05f)
    }

    /** Search's field is A7's capsule since A8 (Seerr's pill grew 3% with focus): the white is its focus, it does not grow. */
    @Test
    fun theSearchFieldsWhiteIsItsFocusAndItDoesNotGrow() {
        rule.setContent {
            val above = remember { FocusRequester() }
            DpadHost {
                // The stage is tagged before its padding: it sits at the root's origin, so its pixels
                // and the controls' bounds share one origin.
                Column(Modifier.fillMaxSize().background(Tokens.Background).testTag("stage").padding(horizontal = 48.dp, vertical = 40.dp)) {
                    PlainButton("Above", onClick = {}, modifier = Modifier.focusRequester(above).testTag("above"))
                    Box(Modifier.padding(top = 40.dp)) {
                        EntryField("", onValueChange = {}, placeholder = "Movies, Series and People", search = true, modifier = Modifier.fillMaxWidth(), tag = "field")
                    }
                }
            }
            FocusOnArrival(above)
        }
        await("field input")
        settle()
        val rest = bounds("field input").width
        val field = bounds("field")
        val probe = { px: PixelMap, f: Rect -> px[(f.left + f.width * 0.6f).toInt(), (f.top + 4).toInt()] }
        assertNear("the field at rest: a field's grey", Tokens.Field, probe(screen(), field))
        press(Key.DirectionDown)
        settle()
        val grew = bounds("field input").width / rest
        assertEquals("the search field keeps its size with focus, grew $grew", 1f, grew, 0.001f)
        assertNear("the focused field: the focused white", Tokens.FieldFocused, probe(screen(), bounds("field")))
    }
}
