package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.discover.DiscoverPage
import com.tomdebres.hither.ui.grid.GridSource
import com.tomdebres.hither.ui.grid.TitleGridModel
import com.tomdebres.hither.ui.grid.TitleGridScreen
import com.tomdebres.hither.ui.settings.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `Page` on a device (H2's X1, DECISIONS 296): a pushed page's title sits where the Apple TV app puts its own, centred
 * across the screen on the line at the top margin (27 dp, the line 40 dp tall), and the page's content starts at
 * Android TV's 48 dp side margin. Before A1 each page drew its own heading, left at 48 dp in Seerr's gradient.
 */
@RunWith(AndroidJUnit4::class)
class PageLayoutTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun await(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun dp(px: Float) = px / rule.density.density

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** The title [tag] centred across the screen, on the top margin's line, and [content] starting at 48 dp. */
    private fun assertPushedPage(tag: String, content: String) {
        val width = rule.onRoot().fetchSemanticsNode().size.width.toFloat()
        val title = bounds(tag)
        assertEquals("$tag: its middle against the screen's, in dp", dp(width / 2), dp(title.center.x), 2f)
        assertEquals("$tag: its middle on the title line (27 dp down, 40 dp tall), in dp", 27f + 40f / 2, dp(title.center.y), 2f)
        assertEquals("$content: the content's left edge, in dp", 48f, dp(bounds(content).left), 1f)
        assertEquals("$content: under the title line", true, dp(bounds(content).top) >= 27f + 40f)
    }

    @Test
    fun settingsTitleIsCentredAtTheTopAndItsPageStartsAt48() {
        rule.setContent {
            DpadHost { SettingsScreen("TV", "tv@example.invalid", "Seerr", "http://seerr.test", onAbout = {}, onSignOut = {}) }
        }
        await("about")
        // Settings' page, the mark's room on the left of its list (A7's SettingsScaffold)
        assertPushedPage("settings heading", "settings scaffold")
    }

    @Test
    fun aGridsTitleIsCentredAtTheTopAndItsFirstCardStartsAt48() {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember {
                TitleGridModel(GridSource { DiscoverPage("http://seerr.test", Page(results = (1..12).map { MediaResult(id = it, mediaType = "movie", title = "Film $it") })) }, scope)
            }
            DpadHost { TitleGridScreen(model, heading = "Heist Movies", onOpen = {}, onBack = {}) }
        }
        await("card movie:7")
        // the second row's first lockup: the first has focus, drawn 1.1 in the layer phase, which its bounds show (A3)
        assertPushedPage("grid heading", "card movie:7")
    }
}
