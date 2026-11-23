package com.tomdebres.hither.ui

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.FocusOnArrival
import com.tomdebres.hither.ui.components.ShowsFocus
import com.tomdebres.hither.ui.theme.ConfirmDialog
import com.tomdebres.hither.ui.theme.PlainButton
import com.tomdebres.hither.ui.theme.ProblemDialog
import com.tomdebres.hither.ui.theme.ProminentButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A1's shared components on a device (DECISIONS 294, 297): tv-material's buttons kept to Hither's D-pad rules, and the
 * dialogs tv-material lacks.
 *
 * - **Select fires once per press**, through the TV's own key path (before the keyboard): held, it repeats and clicks
 *   once; a press that began on another control clicks nothing (tv-material alone would click on the key-up).
 * - **Busy stays focusable and inert.**
 * - **`ConfirmDialog` and `ProblemDialog`**: the safe button focused and showing it; focus cannot leave; Back is
 *   Cancel (or OK); focus goes back to what opened it; Confirm calls once.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ThemeComponentsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val showsFocus = SemanticsMatcher.expectValue(ShowsFocus, true)

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.TestTag) ?: "?"
    }.getOrElse { "nothing" }

    private fun await(tag: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun awaitFocus(tag: String) {
        rule.waitUntil(5_000) { focusedTag() == tag }
        rule.waitForIdle()
    }

    private fun gone(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()

    /** A D-pad move, as the tests elsewhere press one. */
    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    /** Back as the remote's: through the activity's dispatcher, where `BackHandler` listens. */
    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    /**
     * Select through the window's own key path, as a remote sends it (the pre-keyboard stage included, which the
     * compose test's key injection skips): one down, [repeats] repeats of it held, then up unless [release] is off.
     */
    private fun select(repeats: Int = 0, release: Boolean = true) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        for (repeat in 0..repeats) {
            instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, repeat))
        }
        if (release) selectUp(down)
        rule.waitForIdle()
    }

    private fun selectUp(down: Long = SystemClock.uptimeMillis()) {
        InstrumentationRegistry.getInstrumentation().sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        rule.waitForIdle()
    }

    // ---- buttons ---------------------------------------------------------------------------------------------------

    @Test
    fun selectHeldDownClicksOnce() {
        var clicks = 0
        rule.setContent {
            val first = remember { FocusRequester() }
            DpadHost { Box(Modifier.fillMaxSize().padding(80.dp)) { ProminentButton("Request", onClick = { clicks++ }, modifier = Modifier.focusRequester(first).testTag("request")) } }
            FocusOnArrival(first)
        }
        awaitFocus("request")
        select(repeats = 6)
        assertEquals("one press held down (six repeats): one click", 1, clicks)
        select()
        assertEquals("a second press: a second click", 2, clicks)
    }

    @Test
    fun aPressThatBeganOnAnotherControlClicksNothing() {
        var a = 0
        var b = 0
        val toB = FocusRequester()
        rule.setContent {
            val first = remember { FocusRequester() }
            DpadHost {
                Column(Modifier.fillMaxSize().padding(80.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PlainButton("A", onClick = { a++ }, modifier = Modifier.focusRequester(first).testTag("a"))
                    PlainButton("B", onClick = { b++ }, modifier = Modifier.focusRequester(toB).testTag("b"))
                }
            }
            FocusOnArrival(first)
        }
        awaitFocus("a")
        // Select goes down on A; focus moves to B while it is held (a dialog opening, a page arriving); it comes up on B.
        val down = SystemClock.uptimeMillis()
        select(release = false)
        rule.runOnIdle { toB.requestFocus() }
        awaitFocus("b")
        selectUp(down)
        assertEquals("A, whose press lost focus", 0, a)
        assertEquals("B, which saw only the key-up", 0, b)
    }

    @Test
    fun aBusyButtonKeepsFocusAndDoesNothing() {
        var clicks = 0
        rule.setContent {
            val first = remember { FocusRequester() }
            DpadHost {
                Column(Modifier.fillMaxSize().padding(80.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PlainButton("Above", onClick = {}, modifier = Modifier.focusRequester(first).testTag("above"))
                    ProminentButton("Requesting…", onClick = { clicks++ }, busy = true, modifier = Modifier.testTag("busy"))
                }
            }
            FocusOnArrival(first)
        }
        awaitFocus("above")
        press(Key.DirectionDown)
        awaitFocus("busy")
        rule.onNodeWithTag("busy").assert(showsFocus)
        select()
        assertEquals("a busy button does nothing", 0, clicks)
        assertEquals("and keeps focus", "busy", focusedTag())
    }

    // ---- ConfirmDialog ---------------------------------------------------------------------------------------------

    private fun showConfirm(onConfirm: () -> Unit) {
        rule.setContent {
            var open by remember { mutableStateOf(false) }
            val opener = remember { FocusRequester() }
            DpadHost {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(80.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        PlainButton("Before", onClick = {}, modifier = Modifier.testTag("before"))
                        PlainButton("Sign Out", onClick = { open = true }, destructive = true, modifier = Modifier.focusRequester(opener).testTag("opener"))
                    }
                    ConfirmDialog(
                        open = open,
                        title = "Sign Out?",
                        message = "Signing in again takes this account's password.",
                        confirm = "Sign Out",
                        onConfirm = {
                            open = false
                            onConfirm()
                        },
                        onCancel = { open = false },
                        opener = opener,
                        tag = "dialog",
                        cancelTag = "cancel",
                    )
                }
            }
            FocusOnArrival(opener)
        }
        awaitFocus("opener")
    }

    @Test
    fun aConfirmDialogFocusesCancelTrapsFocusAndBackGivesFocusBack() {
        var confirmed = 0
        showConfirm { confirmed++ }
        select()
        await("dialog")
        awaitFocus("cancel")
        rule.onNodeWithTag("cancel").assert(showsFocus)
        // inside: Up reaches the confirm button over it, and no move leaves the dialog
        press(Key.DirectionUp)
        assertEquals("Up from Cancel", "confirm", focusedTag())
        for (key in listOf(Key.DirectionUp, Key.DirectionLeft, Key.DirectionRight)) {
            press(key)
            assertEquals("$key from Sign Out stays in the dialog", "confirm", focusedTag())
        }
        press(Key.DirectionDown)
        for (key in listOf(Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)) {
            press(key)
            assertEquals("$key from Cancel stays in the dialog", "cancel", focusedTag())
        }
        // Back is Cancel, and focus goes back to the button that opened it
        back()
        rule.waitUntil(5_000) { gone("dialog") }
        awaitFocus("opener")
        assertEquals("Back confirmed nothing", 0, confirmed)
    }

    @Test
    fun aConfirmDialogConfirmsOnceAndCancelGivesFocusBack() {
        var confirmed = 0
        showConfirm { confirmed++ }
        select()
        awaitFocus("cancel")
        select()
        rule.waitUntil(5_000) { gone("dialog") }
        awaitFocus("opener")
        assertEquals("Cancel confirmed nothing", 0, confirmed)
        select()
        awaitFocus("cancel")
        press(Key.DirectionUp)
        select(repeats = 4)
        rule.waitUntil(5_000) { gone("dialog") }
        assertEquals("Sign Out, held: confirmed once", 1, confirmed)
    }

    // ---- ProblemDialog ---------------------------------------------------------------------------------------------

    @Test
    fun aProblemDialogFocusesOkTrapsFocusAndGivesFocusBack() {
        var dismissed by mutableIntStateOf(0)
        rule.setContent {
            var open by remember { mutableStateOf(false) }
            val opener = remember { FocusRequester() }
            DpadHost {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(80.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        PlainButton("Before", onClick = {}, modifier = Modifier.testTag("before"))
                        PlainButton("Request", onClick = { open = true }, modifier = Modifier.focusRequester(opener).testTag("opener"))
                    }
                    ProblemDialog(
                        open = open,
                        title = "The request did not go through",
                        message = "Try again.",
                        onDismiss = {
                            open = false
                            dismissed++
                        },
                        opener = opener,
                    )
                }
            }
            FocusOnArrival(opener)
        }
        awaitFocus("opener")
        select()
        await("problem")
        awaitFocus("problem ok")
        rule.onNodeWithTag("problem ok").assert(showsFocus)
        for (key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)) {
            press(key)
            assertEquals("$key from OK stays in the dialog", "problem ok", focusedTag())
        }
        back()
        rule.waitUntil(5_000) { gone("problem") }
        awaitFocus("opener")
        assertEquals("Back is OK", 1, dismissed)
        select()
        awaitFocus("problem ok")
        select()
        rule.waitUntil(5_000) { gone("problem") }
        awaitFocus("opener")
        assertEquals("OK", 2, dismissed)
    }
}
