package com.tomdebres.hither.ui

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
import com.tomdebres.hither.ui.theme.HitherListItem
import com.tomdebres.hither.ui.theme.Tokens
import com.tomdebres.hither.ui.theme.SettingsValueRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.graphics.toArgb

/**
 * tv-material's `ListItem` kept to Hither's rules (`HitherListItem`, A7; REVIEW.md, from A1's buttons): Select once per
 * press through the TV's own key path, held or begun on another row; busy focusable and inert; focus shown when it is
 * given in the frame the row arrives; and a chosen row said by a tick, never by a fill (rule 3). Needs a device or
 * emulator; CI compiles it only.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ListRowTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val showsFocus = SemanticsMatcher.expectValue(ShowsFocus, true)

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.TestTag) ?: "?"
    }.getOrElse { "nothing" }

    private fun awaitFocus(tag: String) {
        rule.waitUntil(5_000) { focusedTag() == tag }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    /** Select as a remote sends it, through the window's key path: one down, [repeats] held repeats, then up. */
    private fun select(repeats: Int = 0, release: Boolean = true): Long {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        for (repeat in 0..repeats) {
            instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, repeat))
        }
        if (release) selectUp(down)
        rule.waitForIdle()
        return down
    }

    private fun selectUp(down: Long) {
        InstrumentationRegistry.getInstrumentation().sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        rule.waitForIdle()
    }

    private var a = 0
    private var b = 0
    private var busy = 0
    private val toB = FocusRequester()

    private fun rows(selected: Boolean = false) {
        rule.setContent {
            val first = remember { FocusRequester() }
            DpadHost {
                Column(Modifier.fillMaxSize().padding(80.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HitherListItem("About", onClick = { a++ }, modifier = Modifier.width(390.dp).focusRequester(first), tag = "a", chevron = true)
                    HitherListItem("Privacy", onClick = { b++ }, modifier = Modifier.width(390.dp).focusRequester(toB), tag = "b", selected = selected)
                    HitherListItem("Removing…", onClick = { busy++ }, modifier = Modifier.width(390.dp), tag = "busy", busy = true, destructive = true)
                    SettingsValueRow("Version", "0.6.5", Modifier.width(390.dp), tag = "value")
                }
            }
            FocusOnArrival(first)
        }
        awaitFocus("a")
    }

    @Test
    fun selectHeldDownOpensOnce() {
        rows()
        select(repeats = 6)
        assertEquals("one press held down (six repeats): one click", 1, a)
        select()
        assertEquals("a second press: a second click", 2, a)
    }

    @Test
    fun aPressThatBeganOnAnotherRowClicksNothing() {
        rows()
        val down = select(release = false)
        rule.runOnIdle { toB.requestFocus() }
        awaitFocus("b")
        selectUp(down)
        assertEquals("the row whose press lost focus", 0, a)
        assertEquals("the row that saw only the key-up", 0, b)
    }

    @Test
    fun aBusyRowKeepsFocusAndDoesNothingAndAValueRowTakesNoFocus() {
        rows()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        awaitFocus("busy")
        rule.onNodeWithTag("busy").assert(showsFocus)
        select()
        assertEquals("a busy row does nothing", 0, busy)
        press(Key.DirectionDown)
        assertEquals("the value row under it takes no focus", "busy", focusedTag())
    }

    @Test
    fun focusGivenAsTheRowArrivesIsShown() {
        rows()
        // FocusOnArrival gives the first row focus a frame after it is composed: before tv-material collects
        rule.onNodeWithTag("a").assert(showsFocus)
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val box = rule.onNodeWithTag("a").fetchSemanticsNode().boundsInRoot
        assertEquals("the focused row's fill is the focused white", Tokens.FieldFocused.toArgb(), image.getPixel(box.left.toInt() + 6, box.center.y.toInt()))
    }

    @Test
    fun aChosenRowIsTickedNotFilled() {
        rows(selected = true)
        rule.onNode(hasTestTag("tick") and hasAnyAncestor(hasTestTag("b")), useUnmergedTree = true).assertExists("no tick on the chosen row")
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val chosen = rule.onNodeWithTag("b").fetchSemanticsNode().boundsInRoot
        val value = rule.onNodeWithTag("value").fetchSemanticsNode().boundsInRoot
        val fill = image.getPixel(chosen.left.toInt() + 6, chosen.center.y.toInt())
        // the chosen row at rest is drawn as any row at rest: the value row's grey beside it
        assertEquals("the chosen row's fill", image.getPixel(value.left.toInt() + 6, value.center.y.toInt()), fill)
        assertEquals("a row at rest is a form row's grey", Tokens.Field.toArgb(), fill)
        assertTrue("focus stayed on the first row", focusedTag() == "a")
    }
}
