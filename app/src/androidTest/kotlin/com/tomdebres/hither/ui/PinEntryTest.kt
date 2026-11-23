package com.tomdebres.hither.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.PinTries
import com.tomdebres.hither.data.AdminStorage
import com.tomdebres.hither.data.KeptAdmin
import com.tomdebres.hither.data.PinHash
import com.tomdebres.hither.data.SavedCookie
import com.tomdebres.hither.session.AdminGateway
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.settings.AdminScreen
import com.tomdebres.hither.ui.settings.AdminSetup
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The PIN as the Apple TV app asks for it (H2's X8; A7): the whole screen, black, with the title, the line under it, the
 * dots and the pad; nothing of the page under it shows, and no box is drawn round the pad. Choosing the PIN and the
 * switch to the admin both. The rules are the same as before (`PinTries`): after the four free tries a wrong PIN starts
 * a wait, said with its countdown, and the pad takes nothing during it. Needs a device or emulator; CI compiles it only.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PinEntryTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val family = User(id = 8, displayName = "Living room TV", email = "tv@example.invalid", permissions = 32)

    private class Memory(@Volatile var admin: KeptAdmin?) : AdminStorage {
        override suspend fun admin() = admin
        override suspend fun save(admin: KeptAdmin) {
            this.admin = admin
        }
        override suspend fun clear() {
            admin = null
        }
    }

    private fun kept(tries: PinTries = PinTries()) = KeptAdmin(
        "http://seerr.test:5055", "approver@example.invalid", "approver-password", 9, "Approver",
        PinHash.of("5632", iterations = 1_000),
        cookies = listOf(SavedCookie("connect.sid", "s%3Akept", encoding = "RAW", path = "/")),
        signedInAt = System.currentTimeMillis(),
        tries = tries,
    )

    private fun gateway(store: Memory) = AdminGateway(store, hash = { PinHash.of(it, iterations = 1_000) }, engine = {
        MockEngine { call: HttpRequestData ->
            val json = headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.SetCookie to listOf("connect.sid=s%3Aapprover; Path=/; HttpOnly"))
            when (call.url.encodedPath.removePrefix("/api/v1")) {
                "/auth/local" ->
                    if ((call.body as? TextContent)?.text?.contains("\"password\":\"approver-password\"") == true) respond("""{"id":9,"permissions":0}""", HttpStatusCode.OK, json)
                    else respond("""{"message":"Access denied."}""", HttpStatusCode.Forbidden, json)
                "/auth/me" -> respond("""{"id":9,"displayName":"Approver","permissions":16}""", HttpStatusCode.OK, json)
                else -> respond("""{"status":"ok"}""", HttpStatusCode.OK, json)
            }
        }
    })

    private fun show(store: Memory) {
        val admin = gateway(store)
        rule.setContent {
            val scope = rememberCoroutineScope()
            val setup = remember { AdminSetup(admin, family, "http://seerr.test:5055", scope) }
            DpadHost { AdminScreen(setup, onBack = {}) }
        }
        rule.waitForIdle()
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
        val held = runCatching { rule.waitUntil(10_000) { focusedTag() == tag } }.isSuccess
        assertTrue("focus is on ${focusedTag()}, not $tag", held)
    }

    private fun await(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun words(tag: String): String =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun px(dp: Float) = with(rule.density) { dp.dp.toPx() }

    private fun dark(c: Int) = (0..2).all { ((c ushr (it * 8)) and 0xFF) <= 8 }

    /**
     * The PIN fills the screen on black: the ground round the pad and over the [line] is the page's black (no dialog's
     * box), and the page's own title under it does not show at all.
     */
    private fun assertWholeScreen(line: String, what: String) {
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val image: Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val pad = bounds("pin pad")
        val words = bounds(line)
        val points = listOf(
            "left of the pad" to (pad.left - px(24f) to pad.center.y),
            "right of the pad" to (pad.right + px(24f) to pad.center.y),
            "over the line" to (words.center.x to words.top - px(16f)),
            "under the pad" to (pad.center.x to pad.bottom + px(12f)),
        )
        for ((where, at) in points) {
            val c = image.getPixel(at.first.toInt(), at.second.toInt())
            assertTrue("$what: $where is ${"#%08X".format(c)}, the page's black expected", dark(c))
        }
        val title = bounds("admin heading")
        var lit = 0
        for (y in title.top.toInt() until title.bottom.toInt()) for (x in title.left.toInt() until title.right.toInt()) if (!dark(image.getPixel(x, y))) lit++
        assertEquals("$what: pixels of the page's title showing through", 0, lit)
    }

    @Test
    fun theSwitchsPinIsTheWholeScreen() {
        show(Memory(kept()))
        awaitFocus("admin switch in")
        press(Key.DirectionCenter)
        await("admin pin dialog")
        awaitFocus("pin 5")
        assertEquals("Enter the PIN.", words("switch note"))
        assertWholeScreen("switch note", "the switch")
    }

    @Test
    fun choosingThePinIsTheWholeScreen() {
        show(Memory(null))
        awaitFocus("admin email")
        press(Key.DirectionCenter)
        rule.onNodeWithTag("admin email input").performTextInput("approver@example.invalid")
        press(Key.Enter)
        rule.onNodeWithTag("admin password input").performTextInput("approver-password")
        press(Key.Enter)
        awaitFocus("admin sign in")
        press(Key.DirectionCenter)
        await("pin choose")
        awaitFocus("pin 5")
        assertWholeScreen("pin note", "choosing the PIN")
    }

    @Test
    fun theFifthWrongPinWaitsWithItsCountdownAndThePadTakesNothing() {
        val store = Memory(kept(PinTries(wrong = 4)))
        show(store)
        awaitFocus("admin switch in")
        press(Key.DirectionCenter)
        awaitFocus("pin 5")
        repeat(4) { press(Key.DirectionCenter) }
        rule.waitUntil(10_000) { words("switch note").startsWith("Too many wrong PINs. Try again in 0:") }
        assertEquals(5, store.admin!!.tries.wrong)
        // during the wait a press types nothing: no place fills, and nothing is checked
        press(Key.DirectionCenter)
        assertTrue("a digit typed during the wait", rule.onAllNodesWithTag("pin place filled", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertEquals("focus kept on the key", "pin 5", focusedTag())
        assertEquals(5, store.admin!!.tries.wrong)
        assertTrue("still asking", rule.onAllNodesWithTag("admin pin dialog").fetchSemanticsNodes().isNotEmpty())
    }
}
