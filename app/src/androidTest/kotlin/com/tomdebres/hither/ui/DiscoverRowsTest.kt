package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.model.DiscoverQuery
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.DiscoverPlan
import com.tomdebres.hither.core.rules.DiscoverRow
import com.tomdebres.hither.core.rules.RowSource
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.discover.DiscoverModel
import com.tomdebres.hither.ui.discover.DiscoverPage
import com.tomdebres.hither.ui.discover.DiscoverScreen
import com.tomdebres.hither.ui.discover.DiscoverSource
import com.tomdebres.hither.ui.discover.PlanSource
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Discover after M6 (DECISIONS 99) on a device: the rows the server's Discover settings give, custom rows
 * under their titles; a row that had focus and goes hands it on; the bar ending with the avatar, SeerrTV's
 * clock gone (DECISIONS 116). A move recomposing only the two cards it touches is
 * `DiscoverRecompositionTest`'s. The overview strip went for the focused card's own overview (DECISIONS 111):
 * `CardOverviewTest`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DiscoverRowsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val session = Session(User(id = 8, displayName = "TV", permissions = 32), PublicSettings(applicationTitle = "Seerr", localLogin = true))
    private val zombies = DiscoverRow("CUSTOM_13", "Zombies", RowSource.Query(MediaType.MOVIE, DiscoverQuery(keywords = "12377")), 113)

    private val rows = DiscoverSource { row, _ ->
        DiscoverPage(
            "http://seerr.test",
            Page(results = (1..8).map { MediaResult(id = row.ordinal * 100 + it, mediaType = "movie", title = "Film ${row.ordinal * 100 + it}", overview = "About film ${row.ordinal * 100 + it}.") }),
        )
    }

    private fun show(plan: CompletableDeferred<DiscoverPlan>) {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { DiscoverModel(rows, session, scope, plans = PlanSource { plan.await() }) }
            DpadHost {
                Box(Modifier.fillMaxSize().testTag("stage")) {
                    DiscoverScreen(model, onOpen = {}, recent = null)
                }
            }
        }
    }

    private fun await(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun settle(ms: Long = 800) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "?" } ?: "?"
    }.getOrElse { "nothing" }

    private fun awaitFocus(tag: String) {
        val held = runCatching { rule.waitUntil(5_000) { settle(); focusedTag() == tag } }.isSuccess
        assertTrue("focus is on ${focusedTag()}, not $tag", held)
    }

    private fun card(row: DiscoverRow, n: Int) = "card movie:${row.ordinal * 100 + n}"

    @Test
    fun theRowsAreThePlansInItsOrderCustomRowsUnderTheirTitles() {
        show(CompletableDeferred(DiscoverPlan(recentRequests = true, rows = listOf(DiscoverRow.RECENTLY_ADDED, zombies, DiscoverRow.TRENDING, DiscoverRow.POPULAR_SERIES))))
        await(card(DiscoverRow.RECENTLY_ADDED, 1))
        awaitFocus(card(DiscoverRow.RECENTLY_ADDED, 1))
        rule.onNodeWithTag("row CUSTOM_13").assertExists()
        rule.onAllNodesWithTag("row POPULAR_MOVIES").assertCountEquals(0)
        press(Key.DirectionDown)
        awaitFocus(card(zombies, 1))
        press(Key.DirectionDown)
        awaitFocus(card(DiscoverRow.TRENDING, 1))
        press(Key.DirectionDown)
        awaitFocus(card(DiscoverRow.POPULAR_SERIES, 1))
        press(Key.DirectionDown)
        awaitFocus(card(DiscoverRow.POPULAR_SERIES, 1))
    }

    @Test
    fun aRowThatHadFocusAndGoesHandsItToTheFirstRowLeft() {
        val plan = CompletableDeferred<DiscoverPlan>()
        show(plan)
        // Hither's own rows stand in, their placeholders holding focus, until the plan comes
        await("row TRENDING")
        awaitFocus("notice TRENDING")
        plan.complete(DiscoverPlan(recentRequests = true, rows = listOf(DiscoverRow.POPULAR_MOVIES, DiscoverRow.UPCOMING_SERIES)))
        await(card(DiscoverRow.POPULAR_MOVIES, 1))
        rule.onAllNodesWithTag("row TRENDING").assertCountEquals(0)
        awaitFocus(card(DiscoverRow.POPULAR_MOVIES, 1))
    }

    @Test
    fun theBarEndsWithTheAvatarAndHasNoClock() {
        show(CompletableDeferred(DiscoverPlan(recentRequests = true, rows = DiscoverRow.entries)))
        await("avatar")
        settle()
        val bar = rule.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot
        val avatar = rule.onNodeWithTag("avatar").fetchSemanticsNode().boundsInRoot
        val search = rule.onNodeWithTag("search").fetchSemanticsNode().boundsInRoot
        // Nothing right of the avatar, named or not: it ends the bar, as on Seerr.
        val short = with(rule.density) { (bar.right - avatar.right).toDp() }
        assertTrue("the avatar ends the bar, not $short short of its end", short.value < 1f)
        rule.onAllNodesWithTag("clock").assertCountEquals(0)
        // Search and the account are icons beside each other, the bar's 8 dp apart (A2).
        val gap = with(rule.density) { (avatar.left - search.right).toDp() }
        assertEquals("the gap from search to the account, in dp", 8f, gap.value, 1f)
    }
}
