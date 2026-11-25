package com.tomdebres.hither.ui

import com.tomdebres.hither.core.rules.ImageSource
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.RequestPage
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.rules.RequestTitle
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.bar.BarContent
import com.tomdebres.hither.ui.discover.DiscoverModel
import com.tomdebres.hither.ui.discover.DiscoverPage
import com.tomdebres.hither.ui.discover.DiscoverScreen
import com.tomdebres.hither.ui.discover.DiscoverSource
import com.tomdebres.hither.ui.home.UnavailableScreen
import com.tomdebres.hither.ui.requests.RequestsModel
import com.tomdebres.hither.ui.requests.RequestsScreen
import com.tomdebres.hither.ui.requests.RequestsSource
import com.tomdebres.hither.ui.search.SearchModel
import com.tomdebres.hither.ui.search.SearchScreen
import com.tomdebres.hither.ui.search.SearchSource
import com.tomdebres.hither.ui.settings.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The states, Settings and the bar after the polish audit (items 27-29, 32, 33; Tom's call on 29 in
 * DECISIONS 80), on a device:
 *
 * - a page that did not load says so centred, with its Try Again under the words (search, My Requests)
 *   and a Discover row's words above its Try Again;
 * - "the server is not answering" names the server and says the sign-in is kept;
 * - Sign Out asks first, with Cancel focused, in Settings and on "not answering"; Cancel signs nothing
 *   out and gives focus back, Sign Out (red words, over Cancel) signs out once;
 * - Settings' rows carry icons;
 * - "N waiting for approval" never comes on the bar (A6: My Requests' side row).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class StatesPolishTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var signOuts = 0

    private fun await(tag: String, unmerged: Boolean = false) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "?" } ?: "?"
    }.getOrElse { "nothing" }

    private fun bounds(tag: String, unmerged: Boolean = false): Rect =
        rule.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode().boundsInRoot

    private fun words(tag: String): String =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.Text) { null }
            .orEmpty().joinToString("") { it.text }

    /** The words sit above their Try Again, both centred across the screen. */
    private fun assertCentredAbove(words: Rect, button: Rect, what: String) {
        val middle = rule.onRoot().fetchSemanticsNode().size.width / 2f
        assertTrue("$what: the words (bottom ${words.bottom}) are not above Try Again (top ${button.top})", words.bottom <= button.top + 1f)
        assertEquals("$what: the words are not centred", middle, words.center.x, 3f)
        assertEquals("$what: Try Again is not centred", middle, button.center.x, 3f)
    }

    // ---- item 27 -------------------------------------------------------------------------------------

    @Test
    fun aSearchThatDidNotLoadSaysSoCentredAboveItsTryAgain() {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { SearchModel(SearchSource { _, _ -> throw IllegalStateException("no answer") }, scope) }
            DpadHost { SearchScreen(model, onOpen = {}, onBack = {}) }
        }
        await("search field input")
        rule.onNodeWithTag("search field input").performTextInput("film")
        press(Key.Enter)
        await("try again")
        assertCentredAbove(bounds("failure text", unmerged = true), bounds("try again"), "search")
    }

    @Test
    fun myRequestsThatDidNotLoadSayItCentredAboveTryAgain() {
        val failing = object : RequestsSource {
            override suspend fun page(skip: Int, take: Int): RequestPage = throw IllegalStateException("no answer")
            override suspend fun title(type: MediaType, tmdbId: Int) = RequestTitle("Film", null, null, null)
        }
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { RequestsModel(failing, scope) }
            DpadHost { RequestsScreen(model, onOpen = {}, onBack = {}) }
        }
        await("try again")
        // centred in the split's right side, where the lockups would be (A6), not across the screen
        val words = bounds("failure text", unmerged = true)
        val button = bounds("try again")
        val side = bounds("side list", unmerged = true)
        val width = rule.onRoot().fetchSemanticsNode().size.width
        val margin = 48 * rule.density.density
        val middle = (side.right + 40 * rule.density.density + width - margin) / 2f
        assertTrue("My Requests: the words (bottom ${words.bottom}) are not above Try Again (top ${button.top})", words.bottom <= button.top + 1f)
        assertEquals("My Requests: the words are not centred in the grid's place", middle, words.center.x, 3f)
        assertEquals("My Requests: Try Again is not centred in the grid's place", middle, button.center.x, 3f)
    }

    @Test
    fun aDiscoverRowThatDidNotLoadHasItsWordsAboveTryAgain() {
        val session = Session(User(id = 8, displayName = "TV", permissions = 32), PublicSettings(applicationTitle = "Seerr", localLogin = true))
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { DiscoverModel(DiscoverSource { _, _ -> throw IllegalStateException("no answer") }, session, scope) }
            DpadHost { DiscoverScreen(model, onOpen = {}) }
        }
        await("try again TRENDING", unmerged = true)
        val words = bounds("notice text TRENDING", unmerged = true)
        val button = bounds("try again TRENDING", unmerged = true)
        assertTrue("the row's words (bottom ${words.bottom}) are not above its Try Again (top ${button.top})", words.bottom <= button.top + 1f)
        assertEquals("the row's words and Try Again are not centred on each other", words.center.x, button.center.x, 3f)
    }

    // ---- items 28 and 29 -------------------------------------------------------------------------------

    @Test
    fun notAnsweringNamesTheServerSaysTheSignInIsKeptAndSignOutAsksFirst() {
        rule.setContent {
            DpadHost { UnavailableScreen(Screen.Unavailable(server = "http://seerr.test:5055"), onRetry = {}, onSignOut = { signOuts++ }) }
        }
        await("unavailable")
        val said = words("unavailable")
        assertTrue("\"$said\" does not name the server", "http://seerr.test:5055" in said)
        assertTrue("\"$said\" does not say the sign-in is kept", "sign-in is kept" in said)
        assertEquals("try again", focusedTag())
        press(Key.DirectionRight)
        assertEquals("sign out", focusedTag())
        press(Key.DirectionCenter)
        await("confirm sign out")
        assertEquals("Sign Out on \"not answering\" did not ask with Cancel focused", "cancel sign out", focusedTag())
        assertEquals(0, signOuts)
    }

    @Test
    fun signOutInSettingsAsksFirstWithCancelFocused() {
        rule.setContent {
            DpadHost { SettingsScreen("TV", "tv@example.invalid", "Seerr", "http://seerr.test", onAbout = {}, onSignOut = { signOuts++ }) }
        }
        await("about")
        // Sign Out last (polish 2, item 25), one row under About since Diagnostics moved to About (A7); Down again stays
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        assertEquals("sign out", focusedTag())
        press(Key.DirectionCenter)
        await("confirm sign out")
        assertEquals("cancel sign out", focusedTag())
        assertEquals("one press signed out", 0, signOuts)
        // Cancel: nothing happens, focus is back on Sign Out
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("confirm sign out").fetchSemanticsNodes().isEmpty() }
        rule.waitUntil(5_000) { focusedTag() == "sign out" }
        assertEquals(0, signOuts)
        // asked again, and confirmed with Sign Out: signed out, once
        press(Key.DirectionCenter)
        await("confirm sign out")
        // Sign Out sits over Cancel in the dialog (A1's ConfirmDialog)
        press(Key.DirectionUp)
        assertEquals("confirm", focusedTag())
        press(Key.DirectionCenter)
        assertEquals(1, signOuts)
    }

    // ---- items 32 and 33 -------------------------------------------------------------------------------

    /** Item 32 gave Settings' rows Seerr's icons; A7 takes them away again, as tvOS's F13 did (rows of words). */
    @Test
    fun settingsRowsCarryNoIcons() {
        rule.setContent {
            DpadHost { SettingsScreen("TV", "tv@example.invalid", "Seerr", "http://seerr.test", onAbout = {}, onSignOut = {}) }
        }
        await("about")
        for (row in listOf("about", "sign out")) {
            rule.onNode(hasTestTag("icon") and hasAnyAncestor(hasTestTag(row)), useUnmergedTree = true).assertDoesNotExist()
        }
    }

    /** "N waiting for approval" left the bar in A6: it is My Requests' side row, so nothing comes on the bar to move it. */
    @Test
    fun waitingForApprovalNeverComesOnTheBar() {
        val session = Session(User(id = 8, displayName = "TV", permissions = 32), PublicSettings(applicationTitle = "Seerr", localLogin = true))
        var waiting by mutableStateOf<Int?>(null)
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember {
                DiscoverModel(
                    DiscoverSource { row, _ -> DiscoverPage(ImageSource.unknown("http://seerr.test"), Page(results = (1..8).map { MediaResult(id = row.ordinal * 100 + it, mediaType = "movie", title = "Film") })) },
                    session,
                    scope,
                )
            }
            DpadHost { DiscoverScreen(model, onOpen = {}, bar = BarContent(waiting = { waiting })) }
        }
        await("search")
        val before = bounds("search")
        waiting = 3
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals("the waiting count came on Discover", 0, rule.onAllNodesWithTag("waiting", useUnmergedTree = true).fetchSemanticsNodes().size)
        val after = bounds("search")
        assertEquals("the search control moved", before.left, after.left, 1f)
    }
}
