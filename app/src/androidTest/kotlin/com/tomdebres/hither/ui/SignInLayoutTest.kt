package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.core.rules.SignInProblem
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.keyboardAsInset
import com.tomdebres.hither.ui.signin.SPINNER_AFTER_MS
import com.tomdebres.hither.ui.signin.SignInScreen
import com.tomdebres.hither.ui.signin.StartingScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sign-in form and the launch, from the polish audit (items 6-8 and 26; Tom, DECISIONS 80):
 *
 * - the form stays where it is when the error lines appear under its fields (it used to move up by
 *   half their height);
 * - Sign In is words alone, as the Apple TV app's (A7: Seerr's arrow-into-a-box, item 8, went with its look);
 * - with the window as Hither's activity sets it ([keyboardAsInset]), the field open for typing sits
 *   whole above the real on-screen keyboard, and the page fills the screen: no black band beside the
 *   keyboard, which a panned window showed;
 * - the launch shows the stacked logo at once, and a spinner only after half a second.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SignInLayoutTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var state by mutableStateOf(Screen.SignIn())

    private fun show() {
        rule.setContent {
            DpadHost {
                SignInScreen(state, "", "", "", onServer = {}, onEmail = {}, onPassword = {}, onSubmit = {})
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("sign in").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun top(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
    }

    @Test
    fun errorsAppearingUnderTheFieldsDoNotMoveTheForm() {
        show()
        val logo = top("wordmark")
        val server = top("server")
        state = Screen.SignIn(problems = setOf(SignInProblem.SERVER, SignInProblem.EMAIL, SignInProblem.PASSWORD))
        rule.waitForIdle()
        rule.onNodeWithTag("password error").fetchSemanticsNode()
        assertEquals("the logo moved when the errors came", logo, top("wordmark"), 0.5f)
        assertEquals("the server field moved when the errors came", server, top("server"), 0.5f)
    }

    @Test
    fun signInIsWordsAlone() {
        show()
        rule.onNode(hasTestTag("icon") and hasAnyAncestor(hasTestTag("sign in")), useUnmergedTree = true).assertDoesNotExist()
    }

    private fun keyboardHeight(): Int {
        val insets = ViewCompat.getRootWindowInsets(rule.activity.window.decorView) ?: return 0
        return insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
    }

    @Test
    fun theOpenFieldSitsAboveTheKeyboardAndThePageFillsTheScreen() {
        rule.runOnUiThread { rule.activity.keyboardAsInset() }
        show()
        // the password field, the lowest, open for typing: the real keyboard comes up
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        assertTrue("the keyboard's insets never reached the window, in $OPENS opens of the field", openTheKeyboard())
        val screen = rule.onRoot().fetchSemanticsNode().size.height
        var foot = 0f
        var keyboardTop = 0
        val placed = runCatching {
            rule.waitUntil(5_000) {
                rule.mainClock.advanceTimeBy(100)
                foot = rule.onNodeWithTag("password").fetchSemanticsNode().boundsInRoot.bottom
                keyboardTop = screen - keyboardHeight()
                foot <= keyboardTop
            }
        }.isSuccess
        assertTrue("the open field's foot is at $foot px, under the keyboard's top at $keyboardTop px", placed)

        // The page fills the screen with the keyboard up, never a panned window: the window's top stays at the
        // screen's, and the page is as tall as the screen. (Before A1 this read Seerr's gray-900 in the corners beside
        // the keyboard; the page is black since, as a panned window's edge is, so it reads the place instead.)
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val root = rule.onRoot().fetchSemanticsNode()
        assertEquals("the window's top on the screen, keyboard up, in px", 0f, root.positionOnScreen.y, 1f)
        assertEquals("the page's height against the screen's, keyboard up, in px", shot.height.toFloat(), root.size.height.toFloat(), 1f)
        // Back closes the field; the form comes back down
        press(Key.Back)
        rule.waitUntil(5_000) { keyboardHeight() == 0 }
    }

    /**
     * Opens the focused field and waits for the keyboard's insets to reach the window, closing the field and opening it
     * again if they do not come: on tv31, in the middle of a whole suite, the keyboard sometimes comes up (its window
     * shown, `show(ime(), fromIme=true)`) and its insets never reach the window, on `main` as on a branch (A1: 2 of 4
     * suites; A7: on both; the cause is not proved, DECISIONS 311). Up to [OPENS] opens; false if none brought them. What
     * the test proves is unchanged: the keyboard's insets must arrive, and the open field must then sit above it.
     */
    private fun openTheKeyboard(): Boolean {
        repeat(OPENS) { open ->
            if (open > 0) {
                // close the field (Back closes it and stays), and let any keyboard go
                press(Key.Back)
                runCatching { rule.waitUntil(3_000) { keyboardHeight() == 0 } }
            }
            press(Key.DirectionCenter)
            if (runCatching { rule.waitUntil(10_000) { keyboardHeight() > 0 } }.isSuccess) return true
        }
        return false
    }

    private companion object {
        /** How many times the field is opened before the keyboard's insets are given up on. */
        const val OPENS = 3
    }

    @Test
    fun theLaunchShowsTheLogoAtOnceAndASpinnerOnlyAfterHalfASecond() {
        rule.mainClock.autoAdvance = false
        rule.setContent { StartingScreen() }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.onNodeWithTag("starting logo").assertExists("the launch shows no logo")
        rule.mainClock.advanceTimeBy(SPINNER_AFTER_MS - 100)
        assertTrue(
            "a spinner shows before half a second has passed",
            rule.onAllNodesWithTag("starting spinner").fetchSemanticsNodes().isEmpty(),
        )
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithTag("starting spinner").assertExists("no spinner after half a second")
    }
}
