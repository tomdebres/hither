package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.data.AdminStorage
import com.tomdebres.hither.data.KeptAdmin
import com.tomdebres.hither.data.PinHash
import com.tomdebres.hither.session.AdminGateway
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.settings.AdminScreen
import com.tomdebres.hither.ui.settings.AdminSetup
import com.tomdebres.hither.ui.settings.SettingsScreen
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * The TV's admin account (M5, DECISIONS 92), on a device, by D-pad through the real gateway against a
 * scripted server with made-up accounts: Settings' row; the sign-in form, as the family's; the PIN
 * chosen and entered again; the account kept with its PIN's hash only; Remove asking first; and the
 * accounts that may not be the admin refused with nothing kept.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AdminAccountTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val family = User(id = 8, displayName = "Living room TV", email = "tv@example.invalid", permissions = 32)
    private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var me = """{"id":9,"displayName":"Approver","permissions":16}"""

    private class Memory : AdminStorage {
        @Volatile var admin: KeptAdmin? = null
        override suspend fun admin() = admin
        override suspend fun save(admin: KeptAdmin) {
            this.admin = admin
        }
        override suspend fun clear() {
            admin = null
        }
    }

    private val store = Memory()

    private val gateway = AdminGateway(store, hash = { PinHash.of(it, iterations = 1_000) }, engine = {
        MockEngine { call: HttpRequestData ->
            val path = call.url.encodedPath.removePrefix("/api/v1")
            seen += "${call.method.value} $path"
            val json = headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.SetCookie to listOf("connect.sid=s%3Aapprover; Path=/; HttpOnly"))
            when (path) {
                "/auth/local" ->
                    if ((call.body as? TextContent)?.text?.contains("\"password\":\"approver-password\"") == true) respond("""{"id":9,"permissions":0}""", HttpStatusCode.OK, json)
                    else respond("""{"message":"Access denied."}""", HttpStatusCode.Forbidden, json)
                "/auth/me" -> respond(me, HttpStatusCode.OK, json)
                else -> respond("""{"status":"ok"}""", HttpStatusCode.OK, json)
            }
        }
    })

    private var left = 0

    private fun show() {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val setup = remember { AdminSetup(gateway, family, "http://seerr.test:5055", scope) }
            DpadHost { AdminScreen(setup, onBack = { left++ }) }
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
    }

    /** The form by D-pad and the keyboard's action key, as the family's sign-in is filled. */
    private fun signIn(password: String = "approver-password") {
        awaitFocus("admin email")
        press(Key.DirectionCenter)
        rule.onNodeWithTag("admin email input").performTextInput("approver@example.invalid")
        press(Key.Enter)
        rule.onNodeWithTag("admin password input").performTextInput(password)
        press(Key.Enter)
        awaitFocus("admin sign in")
        press(Key.DirectionCenter)
    }

    /** Four digits by D-pad from 5: 5, 6, 3, 2; then back to 5 for the next entry, unless it is the [last]. */
    private fun typePin(last: Boolean = false) {
        awaitFocus("pin 5")
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        press(Key.DirectionUp)
        press(Key.DirectionCenter)
        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        // back to 5 for the next entry (after the last, the page under it has the focus: Down would move it)
        if (!last) press(Key.DirectionDown)
    }

    @Test
    fun anApproverSignsInOnceChoosesThePinTwiceAndIsKeptWithItsHashOnly() {
        show()
        signIn()
        await("pin choose")
        typePin()
        await("pin again")
        typePin(last = true)
        await("admin name")
        rule.onNodeWithTag("admin name", useUnmergedTree = true).assertTextEquals("Approver")
        // kept: focus on Switch to Admin (part 2, DECISIONS 93), Remove under it; the row names the account, as
        // the dialog it opens does (the polish 2 audit's item 31), and the heading is in Title Case (item 26)
        awaitFocus("admin switch in")
        rule.onNodeWithTag("admin switch in").assertTextEquals("Switch to Approver")
        rule.onNodeWithTag("admin heading").assertTextEquals("Admin Account")
        val kept = store.admin!!
        assertEquals(9, kept.userId)
        assertTrue("the PIN's hash", kept.pin.matches("5632"))
        assertTrue("never the digits", !kept.toString().contains("5632") && !kept.pin.hash.contains("5632"))
        assertEquals(listOf("connect.sid"), kept.cookies.map { it.name })
        assertTrue("not signed out", "POST /auth/logout" !in seen)
    }

    @Test
    fun twoEntriesThatDifferAreSaidAndNothingIsKept() {
        show()
        signIn()
        typePin()
        await("pin again")
        // 5, 5, 5, 5
        repeat(4) { press(Key.DirectionCenter) }
        await("pin choose")
        rule.onNodeWithTag("pin note", useUnmergedTree = true).assertTextEquals("The two PINs were not the same. Choose one again.")
        assertNull(store.admin)
    }

    @Test
    fun backBeforeThePinIsSetKeepsNothingAndEndsTheSession() {
        show()
        signIn()
        await("pin choose")
        Espresso.pressBack()
        rule.waitForIdle()
        assertEquals(1, left)
        rule.waitUntil(5_000) { "POST /auth/logout" in seen }
        assertNull(store.admin)
    }

    @Test
    fun theFamilysOwnAccountIsRefusedAndNothingKept() {
        me = """{"id":8,"displayName":"Living room TV","permissions":16}"""
        show()
        signIn()
        await("admin refused")
        rule.onNodeWithTag("admin refused", useUnmergedTree = true)
            .assertTextEquals("That is this TV's own account. Sign in with the account made for approving. Nothing was kept.")
        assertNull(store.admin)
        assertTrue("POST /auth/logout" in seen)
    }

    @Test
    fun anAccountWithoutManageRequestsIsRefusedAndNothingKept() {
        me = """{"id":9,"displayName":"Approver","permissions":32}"""
        show()
        signIn()
        await("admin refused")
        rule.onNodeWithTag("admin refused", useUnmergedTree = true)
            .assertTextEquals("That account cannot approve requests on this server: it needs Manage Requests. Nothing was kept.")
        assertNull(store.admin)
    }

    @Test
    fun aWrongPasswordReadsAsSeerrWordsIt() {
        show()
        signIn(password = "wrong")
        await("admin sign-in error")
        rule.onNodeWithTag("admin sign-in error", useUnmergedTree = true).assertTextEquals("The email address or password is incorrect.")
        assertNull(store.admin)
    }

    @Test
    fun anAdministratorIsToldOnceAndCarriesOn() {
        me = """{"id":1,"displayName":"Owner","permissions":2}"""
        show()
        signIn()
        await("admin administrator")
        awaitFocus("admin carry on")
        press(Key.DirectionCenter)
        await("pin choose")
        awaitFocus("pin 5")
    }

    @Test
    fun removeAsksFirstWithCancelFocusedAndForgetsTheAccount() {
        store.admin = KeptAdmin("http://seerr.test:5055", "approver@example.invalid", "approver-password", 9, "Approver", PinHash.of("5632", iterations = 1_000))
        show()
        awaitFocus("admin switch in")
        // Remove is the row under Switch (A7: Settings' rows)
        press(Key.DirectionDown)
        awaitFocus("admin remove")
        press(Key.DirectionCenter)
        awaitFocus("cancel remove admin")
        Espresso.pressBack()
        rule.waitForIdle()
        // back from the question: focus on Remove again
        awaitFocus("admin remove")
        assertTrue("Cancel removes nothing", store.admin != null)
        press(Key.DirectionCenter)
        awaitFocus("cancel remove admin")
        // Remove sits over Cancel in the dialog (A1's ConfirmDialog)
        press(Key.DirectionUp)
        awaitFocus("confirm")
        press(Key.DirectionCenter)
        await("admin email")
        assertNull(store.admin)
    }

    @Test
    fun settingsHasTheRowAndComingBackFocusesIt() {
        var opened = 0
        rule.setContent {
            DpadHost {
                SettingsScreen("Living room TV", "tv@example.invalid", "Seerr", "http://seerr.test:5055", onAbout = {}, onSignOut = {}, onAdmin = { opened++ }, backFromAdmin = true)
            }
        }
        awaitFocus("admin account")
        press(Key.DirectionCenter)
        assertEquals(1, opened)
        press(Key.DirectionUp)
        awaitFocus("about")
        // the admin's row, then Sign Out last (polish 2, item 25; Diagnostics is About's since A7)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        awaitFocus("sign out")
    }
}
