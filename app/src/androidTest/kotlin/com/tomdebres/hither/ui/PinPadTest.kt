package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.LocalRecompositionProbe
import com.tomdebres.hither.ui.components.PinPad
import org.junit.Assert.assertEquals
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The PIN pad (M5, DECISIONS 92), on a device: focus arrives on 5 and the keys are fenced; Select
 * types and focus stays; the fourth digit hands the PIN over and clears; the digits are never drawn;
 * Back takes one off, then leaves; a remote's number keys type; and a focus move recomposes the two
 * keys it touches, a digit only the places.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PinPadTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val pins = mutableListOf<String>()
    private var backs = 0
    private val recomposed = mutableMapOf<String, Int>()
    private val enabled = androidx.compose.runtime.mutableStateOf(true)

    private fun show() {
        rule.setContent {
            CompositionLocalProvider(LocalRecompositionProbe provides { name -> recomposed[name] = (recomposed[name] ?: 0) + 1 }) {
                DpadHost {
                    // the page's gray-900, as the dialog the pad sits in (a white window would show through the keys)
                    Box(Modifier.fillMaxSize().background(com.tomdebres.hither.ui.theme.Tokens.Background)) {
                        PinPad(onPin = { pins += it }, onBack = { backs++ }, modifier = Modifier.align(Alignment.Center), enabled = enabled.value)
                    }
                }
            }
        }
        awaitFocus("pin 5")
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
    }

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "?" } ?: "?"
    }.getOrElse { "nothing" }

    private fun awaitFocus(tag: String) {
        val held = runCatching { rule.waitUntil(5_000) { focusedTag() == tag } }.isSuccess
        assertTrue("focus is on ${focusedTag()}, not $tag", held)
    }

    private fun filled(): Int = rule.onAllNodesWithTag("pin place filled", useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun focusArrivesOnFiveAndTheKeysAreFencedAsAPhonesPad() {
        show()
        press(Key.DirectionLeft)
        awaitFocus("pin 4")
        press(Key.DirectionLeft)
        assertEquals("the left column is fenced", "pin 4", focusedTag())
        press(Key.DirectionUp)
        awaitFocus("pin 1")
        press(Key.DirectionUp)
        assertEquals("the top row is the top", "pin 1", focusedTag())
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        awaitFocus("pin 3")
        press(Key.DirectionRight)
        assertEquals("the right column is fenced", "pin 3", focusedTag())
        repeat(3) { press(Key.DirectionDown) }
        // a phone's last row: 0 in the middle, delete on its right, the left empty (the polish 2 audit's item 28)
        awaitFocus("pin delete")
        press(Key.DirectionRight)
        assertEquals("delete ends the last row", "pin delete", focusedTag())
        press(Key.DirectionLeft)
        awaitFocus("pin 0")
        press(Key.DirectionLeft)
        assertEquals("nothing left of 0", "pin 0", focusedTag())
        val zero = rule.onNodeWithTag("pin 0").getBoundsInRoot()
        val eight = rule.onNodeWithTag("pin 8").getBoundsInRoot()
        val delete = rule.onNodeWithTag("pin delete").getBoundsInRoot()
        // centres: the focused key is drawn at 1.06
        assertEquals("0 under 8", (eight.left.value + eight.right.value) / 2, (zero.left.value + zero.right.value) / 2, 1f)
        assertTrue("delete right of 0", delete.left > zero.right)
    }

    @Test
    fun selectTypesFocusStaysAndTheFourthDigitHandsThePinOver() {
        show()
        press(Key.DirectionCenter)
        assertEquals(1, filled())
        assertEquals("focus stays on the key pressed", "pin 5", focusedTag())
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(3, filled())
        assertTrue("nothing handed over before the fourth", pins.isEmpty())
        press(Key.DirectionUp)
        press(Key.DirectionCenter)
        assertEquals(listOf("5563"), pins)
        assertEquals("the places clear for the next entry", 0, filled())
    }

    @Test
    fun theDigitsAreNeverShownOnlyHowMany() {
        show()
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(2, filled())
        assertTrue("the typed digits as text", rule.onAllNodes(hasText("56", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        val said = rule.onNodeWithTag("pin places", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        assertEquals(listOf("2 of 4 digits typed"), said)
    }

    @Test
    fun backTakesTheLastDigitOffThenLeaves() {
        show()
        press(Key.DirectionCenter)
        press(Key.DirectionCenter)
        Espresso.pressBack()
        rule.waitForIdle()
        assertEquals(1, filled())
        Espresso.pressBack()
        rule.waitForIdle()
        assertEquals(0, filled())
        assertEquals(0, backs)
        Espresso.pressBack()
        rule.waitForIdle()
        assertEquals(1, backs)
    }

    @Test
    fun aRemotesNumberKeysTypeAndItsDeleteKeyTakesOneOff() {
        show()
        press(Key.Four)
        press(Key.Eight)
        press(Key.Backspace)
        assertEquals(1, filled())
        press(Key.Eight)
        press(Key.Two)
        press(Key.One)
        assertEquals(listOf("4821"), pins)
        press(Key.DirectionDown)
        press(Key.DirectionLeft)
        awaitFocus("pin 7")
        press(Key.DirectionCenter)
        assertEquals(1, filled())
    }

    @Test
    fun aMoveRecomposesTheTwoKeysItTouchesAndADigitOnlyThePlaces() {
        show()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        recomposed.clear()
        press(Key.DirectionRight)
        awaitFocus("pin 6")
        assertEquals("a move: ${recomposed}", setOf("pin key 5", "pin key 6"), recomposed.keys)
        assertTrue("each once or twice: $recomposed", recomposed.values.all { it <= 2 })
        recomposed.clear()
        press(Key.DirectionCenter)
        assertEquals("a digit: the places and nothing else, the pad included: $recomposed", setOf("pin places"), recomposed.keys)
    }

    /**
     * During a wait the keys take nothing and look it: Seerr's disabled 50% (the polish 2 audit's item 29), with focus
     * kept where it was.
     */
    @Test
    fun duringAWaitTheKeysAreHalfStrengthTakeNothingAndKeepFocus() {
        show()
        val brightest = { tag: String ->
            val map = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
            var max = 0f
            for (x in 0 until map.width) for (y in 0 until map.height) max = maxOf(max, map[x, y].red)
            max
        }
        val before = brightest("pin 1")
        rule.runOnUiThread { enabled.value = false }
        rule.waitForIdle()
        val during = brightest("pin 1")
        assertTrue("the key's words at half strength ($before then $during)", during < before * 0.7f)
        assertEquals("focus kept", "pin 5", focusedTag())
        press(Key.DirectionCenter)
        assertTrue("no digit typed", rule.onAllNodesWithTag("pin place filled").fetchSemanticsNodes().isEmpty())
    }

}
