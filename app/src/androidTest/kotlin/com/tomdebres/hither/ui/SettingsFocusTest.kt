package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.ShowsFocus
import com.tomdebres.hither.ui.home.HomeScreen
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Behind the avatar: Settings, About with TMDB's notice and logo, and the privacy text inside
 * the app (`docs/app-store-tvos.md`, 5.1.1). Walked by D-pad, Back one step at a time.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsFocusTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val session = Session(
        User(id = 8, displayName = "Living room TV", email = "tv@example.invalid", permissions = 32),
        PublicSettings(applicationTitle = "Seerr", localLogin = true),
        baseUrl = "http://seerr.test:5055",
    )
    private val showsFocus = SemanticsMatcher.expectValue(ShowsFocus, true)
    private var signOuts = 0

    /** Rows that never answer: these tests are about the pages behind the avatar. */
    private val silent = object : SeerrAccess {
        override suspend fun <T> call(call: suspend (SeerrClient) -> T): T = awaitCancellation()
    }

    private fun show() {
        rule.setContent { DpadHost { HomeScreen(Screen.Home(session), silent, onSignOut = { signOuts++ }) } }
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

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { null } ?: "a node without a tag"
    }.getOrElse { "nothing" }

    private fun assertFocused(tag: String) {
        val now = focusedTag()
        if (now != tag) throw AssertionError("expected focus on $tag, it is on $now")
    }

    /** From Discover's first row to the avatar, and Select. */
    private fun openSettings() {
        show()
        press(Key.DirectionUp)
        // Requests, search, the avatar
        press(Key.DirectionRight, 3)
        assertFocused("avatar")
        press(Key.DirectionCenter)
    }

    @Test
    fun theAvatarIsAPictureNotTheInitialOnTheBarAndOnSettings() {
        show()
        // the bar's avatar: a person's head and shoulders, no "L" (Tom, 2026-11-12; DECISIONS 103)
        rule.onNode(hasTestTag("user glyph") and hasAnyAncestor(hasTestTag("avatar")), useUnmergedTree = true).assertExists()
        assertEquals(null, rule.onNodeWithTag("avatar").fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.Text) { null })
        assertEquals(listOf("Account, Living room TV"), rule.onNodeWithTag("avatar").fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        press(Key.DirectionUp)
        press(Key.DirectionRight, 3)
        assertFocused("avatar")
        press(Key.DirectionCenter)
        rule.onNodeWithTag("account name").assertIsDisplayed()
        // Settings' account is a row of words beside Hither's mark, as the Apple TV app's Settings (A7): no picture
        rule.onAllNodesWithTag("user glyph", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithText("L").assertCountEquals(0)
    }

    @Test
    fun theAvatarOpensSettingsWithTheAccountAndTheServerAndFocusOnAbout() {
        openSettings()
        rule.onNodeWithTag("account name").assertIsDisplayed()
        rule.onNodeWithText("tv@example.invalid").assertIsDisplayed()
        rule.onNodeWithTag("server").assertIsDisplayed()
        // the server by its name, its address under it without the scheme (tvOS's F37, A7)
        rule.onNodeWithText("Seerr").assertExists()
        rule.onNodeWithText("seerr.test:5055").assertExists()
        rule.onAllNodesWithText("http://seerr.test:5055", substring = true).assertCountEquals(0)
        assertFocused("about")
        rule.onNodeWithTag("about").assert(showsFocus)
    }

    @Test
    fun signOutIsOneRowDownAndSignsOutOncePerPress() {
        openSettings()
        // Sign Out last, as Seerr's menu ends (the polish 2 audit's item 25); Diagnostics is About's since A7
        press(Key.DirectionDown)
        assertFocused("sign out")
        press(Key.DirectionDown)
        assertFocused("sign out")
        // it asks first (DECISIONS 87), Cancel focused; Sign Out (red words) signs out, once
        press(Key.DirectionCenter)
        assertFocused("cancel sign out")
        assertEquals(0, signOuts)
        // Sign Out sits over Cancel in the dialog (A1's ConfirmDialog)
        press(Key.DirectionUp)
        assertFocused("confirm")
        press(Key.DirectionCenter)
        assertEquals(1, signOuts)
    }

    @Test
    fun aboutCarriesTmdbsNoticeAndLogoAndTheUnofficialLine() {
        openSettings()
        press(Key.DirectionCenter)
        rule.onNodeWithTag("about wordmark").assertIsDisplayed()
        rule.onNodeWithTag("tmdb logo").assertIsDisplayed()
        rule.onNodeWithText(
            "This application uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise approved by TMDB.",
        ).assertIsDisplayed()
        rule.onNodeWithTag("unofficial").assertIsDisplayed()
        // TMDB's logo is less prominent than Hither's own mark (docs/attribution.md)
        val logo = rule.onNodeWithTag("tmdb logo").fetchSemanticsNode().size
        val mark = rule.onNodeWithTag("about wordmark").fetchSemanticsNode().size
        assert(logo.height < mark.height) { "the TMDB logo (${logo.height} px) must be smaller than the wordmark (${mark.height} px)" }
        assertFocused("privacy")
        rule.onNodeWithTag("privacy").assert(showsFocus)
    }

    /**
     * Privacy is one `ReadingView` (A5, H2's X7; tvOS's): the whole policy is one stop that takes focus as the page comes,
     * Down turns it a view at a time until its end is on screen, and no press moves focus off it (it was a stop per
     * paragraph). Back leaves (below).
     */
    @Test
    fun privacyIsOneStopAndDownReachesItsEnd() {
        openSettings()
        press(Key.DirectionCenter)
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("privacy text").fetchSemanticsNodes().isNotEmpty() }
        assertFocused("privacy text")
        rule.onNodeWithText("What the app stores, on the device only", substring = true, useUnmergedTree = true).assertExists()
        val view = rule.onNodeWithTag("privacy text").fetchSemanticsNode().boundsInRoot
        val last = "This file's history in the repository is the record of changes."
        // where the last paragraph is laid out, scrolled, unclipped (`boundsInRoot` clips to the view)
        fun lastLine() = rule.onNodeWithText(last, substring = true, useUnmergedTree = true).fetchSemanticsNode()
            .let { androidx.compose.ui.geometry.Rect(it.positionInRoot, androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())) }
        assertTrue("the end is not on screen at first", lastLine().top > view.bottom)
        // Left, Right and Up at the top go nowhere: one stop
        for (key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp)) {
            press(key)
            assertFocused("privacy text")
        }
        var presses = 0
        while (lastLine().bottom > view.bottom + 1f && presses < 40) {
            press(Key.DirectionDown)
            rule.mainClock.advanceTimeBy(400)
            rule.waitForIdle()
            assertFocused("privacy text")
            presses++
        }
        val end = lastLine()
        assertTrue("Down reached the end in $presses presses (its foot at ${end.bottom}, the view's at ${view.bottom})", end.bottom <= view.bottom + 1f && end.top >= view.top)
        assertTrue("a page at a time, not a line: $presses presses", presses in 2..20)
        // and more Downs stay at the end, on the same stop
        press(Key.DirectionDown, 2)
        assertFocused("privacy text")
    }

    @Test
    fun backGoesOneStepAtATimeAndFocusReturnsToWhereItWas() {
        openSettings()
        press(Key.DirectionCenter) // About
        press(Key.DirectionCenter) // Privacy
        back()
        assertFocused("privacy")
        back()
        assertFocused("about")
        back()
        // Discover, with focus back on the avatar it was opened from
        assertFocused("avatar")
        rule.onNodeWithTag("avatar").assert(showsFocus)
    }
}
