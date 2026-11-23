package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
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
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.rules.MediaPage
import com.tomdebres.hither.core.rules.afterRequest
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.media.MediaModel
import com.tomdebres.hither.ui.media.MediaScreen
import com.tomdebres.hither.ui.media.RequestSender
import com.tomdebres.hither.ui.theme.Tokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

/**
 * The media page and its request after the polish audit (items 15-21; Tom's calls on 15 and 20 in DECISIONS 80), as A5
 * redrew it (Seerr's modal and its table went for tvOS's question and the side panel), on a device:
 *
 * - a request's outcome shows in place, on the page's status line, and no toast says it (item 15, tvOS's D4 since A5);
 * - once the page has scrolled, no sliver of its header is left at the screen's top edge;
 * - the series' panel slides in from the right, rather than appearing (item 19's coming in);
 * - a ticked season is a tick on the row's own grey, never a fill (rule 3), and season names are at Body size.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MediaPolishTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var served: MediaPage? = null

    private fun show(page: MediaPage) {
        served = page
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember {
                MediaModel(
                    source = { _, _ -> served!! },
                    card = if (page.type.api == "tv") MediaPages.seriesCard(page.tmdbId) else MediaPages.card(page.tmdbId),
                    scope = scope,
                    requests = RequestSender { body ->
                        MediaRequest(id = 1001, status = RequestStatus.APPROVED, is4k = body.is4k).also { created ->
                            val now = served!!
                            served = now.withMedia(afterRequest(now.media, created, now.type, now.tmdbId))
                        }
                    },
                )
            }
            DpadHost {
                Box(Modifier.fillMaxSize().testTag("stage")) {
                    MediaScreen(model)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun await(tag: String, unmerged: Boolean = false) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "?" } ?: "?"
    }.getOrElse { "nothing" }

    private fun levels(c: Color) = Triple((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

    private fun apart(a: Color, b: Color): Int {
        val (r1, g1, b1) = levels(a)
        val (r2, g2, b2) = levels(b)
        return max(abs(r1 - r2), max(abs(g1 - g2), abs(b1 - b2)))
    }

    // ---- item 15 -------------------------------------------------------------------------------------

    @Test
    fun aRequestsOutcomeShowsInPlaceAndNoToastSaysIt() {
        show(MediaPages.film())
        await("action Request")
        press(Key.DirectionCenter)
        await("primary")
        press(Key.DirectionCenter)
        // the page's status line says it, where the request was made
        await("status Requested", unmerged = true)
        for (step in 0 until 8) {
            press(Key.DirectionLeft)
            assertEquals("a toast said it ($step)", 0, rule.onAllNodesWithTag("outcome", useUnmergedTree = true).fetchSemanticsNodes().size)
        }
    }

    // ---- item 16 -------------------------------------------------------------------------------------

    @Test
    fun aScrolledPageLeavesNoSliverOfItsHeaderAtTheTop() {
        show(MediaPages.film(castSize = 12))
        await("action Request")
        // the overview, then Cast & Crew (Information takes no focus since A4)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        assertTrue("focus is on ${focusedTag()}, not the cast", focusedTag().startsWith("person "))
        rule.mainClock.advanceTimeBy(1_000)
        val px = rule.onNodeWithTag("stage").captureToImage().toPixelMap()
        // the screen's top rows, where the poster and the buttons showed a 6 px foot
        for (y in 0..7) {
            val worst = (0 until px.width step 3).maxOf { x -> apart(px[x, y], Tokens.Background) }
            assertTrue("row $y at the top of the scrolled page is up to $worst levels off gray-900", worst <= 1)
        }
    }

    // ---- items 17-21: the request, since A5 the side panel ----------------------------------------------------

    private fun openSeries() {
        show(MediaPages.series())
        await("action Request More")
        press(Key.DirectionCenter)
        await("request modal")
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    @Test
    fun theSeriesPanelSlidesInFromTheRight() {
        show(MediaPages.series())
        await("action Request More")
        rule.mainClock.autoAdvance = false
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(80)
        val early = rule.onNodeWithTag("request modal").fetchSemanticsNode().boundsInRoot.left
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        val rest = rule.onNodeWithTag("request modal").fetchSemanticsNode().boundsInRoot.left
        assertTrue("the panel was already in place ($early, then $rest) as it came", early > rest + 10f)
    }

    @Test
    fun aTickedSeasonIsATickOnTheRowsOwnGreyNeverAFill() {
        openSeries()
        assertEquals("season 2", focusedTag())
        press(Key.DirectionCenter)
        // away from both rows, so each is drawn at rest
        press(Key.DirectionDown, 2)
        assertEquals("season 4", focusedTag())
        fun commonest(tag: String): Color {
            val px = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
            val counts = HashMap<Color, Int>()
            for (y in 0 until px.height step 2) for (x in 0 until px.width step 2) counts[px[x, y]] = (counts[px[x, y]] ?: 0) + 1
            return counts.maxBy { it.value }.key
        }
        val ticked = commonest("season 2")
        val plain = commonest("season 3")
        assertTrue("the ticked row is filled: ${levels(ticked)} against ${levels(plain)}", apart(ticked, plain) <= 2)
        assertTrue("the rows are a form row's grey", apart(plain, Tokens.Field) <= 2)
        rule.onNode(hasTestTag("tick") and hasAnyAncestor(hasTestTag("season 2")), useUnmergedTree = true).assertExists()
    }

    @Test
    fun seasonNamesAreAtBodySize() {
        openSeries()
        val name = rule.onNode(hasText("Season 2", substring = true) and hasAnyAncestor(hasTestTag("season 2")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        // Body's line is 20 sp, Caption's 16: a line of Caption measures under 16 sp, one of Body over it
        val caption = 16 * rule.density.density * rule.density.fontScale
        assertTrue("a season's name is ${name.height} px tall, a Caption line's height ($caption px or less)", name.height > caption)
    }
}
