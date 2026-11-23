package com.tomdebres.hither.ui

import com.tomdebres.hither.ui.components.AdminBadgeRoom

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.PinTries
import com.tomdebres.hither.core.rules.SignInRequest
import com.tomdebres.hither.data.AdminStorage
import com.tomdebres.hither.data.KeptAdmin
import com.tomdebres.hither.data.PinHash
import com.tomdebres.hither.data.SavedCookie
import com.tomdebres.hither.session.ActiveAdmin
import com.tomdebres.hither.session.AdminAccess
import com.tomdebres.hither.session.AdminGateway
import com.tomdebres.hither.session.AdminSignIn
import com.tomdebres.hither.session.AdminSummary
import com.tomdebres.hither.session.AdminSwitch
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.LocalRecompositionProbe
import com.tomdebres.hither.ui.home.HomeScreen
import com.tomdebres.hither.ui.settings.AdminScreen
import com.tomdebres.hither.ui.settings.AdminSetup
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Switching to the TV's admin and back, and its badge (M5, DECISIONS 93; the badge, 109), on a device: the PIN asked
 * for over the admin page, a wrong one said, the right one switching in with focus on Switch Back; the
 * tenth wrong one forgetting the account; and while active, the mark on Discover's bar and over the
 * other pages, search's field leaving it room, Settings' Switch Back first and focused, and the bar not
 * recomposing for a move.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AdminSwitchTest {
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

    private val store = Memory(
        KeptAdmin(
            "http://seerr.test:5055", "approver@example.invalid", "approver-password", 9, "Approver",
            PinHash.of("5632", iterations = 1_000),
            cookies = listOf(SavedCookie("connect.sid", "s%3Akept", encoding = "RAW", path = "/")),
            signedInAt = System.currentTimeMillis(),
        ),
    )

    private val gateway = AdminGateway(store, engine = {
        MockEngine { call: HttpRequestData ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when (call.url.encodedPath.removePrefix("/api/v1")) {
                "/auth/me" -> respond("""{"id":9,"displayName":"Approver","permissions":16}""", HttpStatusCode.OK, json)
                else -> respond("""{"status":"ok"}""", HttpStatusCode.OK, json)
            }
        }
    })

    private fun showPage() {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val setup = remember { AdminSetup(gateway, family, "http://seerr.test:5055", scope) }
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
    }

    private fun shown(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** From 5: [digits] as presses on the pad (5 6 3 2 is Select, Right Select, Up Select, Left Select). */
    private fun type5632() {
        awaitFocus("pin 5")
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        press(Key.DirectionUp)
        press(Key.DirectionCenter)
        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
    }

    @Test
    fun aWrongPinIsSaidAndTheRightOneSwitchesInWithFocusOnSwitchBack() {
        showPage()
        awaitFocus("admin switch in")
        press(Key.DirectionCenter)
        await("admin pin dialog")
        rule.onNodeWithTag("switch note", useUnmergedTree = true).assertTextEquals("Enter the PIN.")
        awaitFocus("pin 5")
        repeat(4) { press(Key.DirectionCenter) }
        rule.waitUntil(10_000) { rule.onNodeWithTag("switch note", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString().contains("9 tries left") }
        assertNull(gateway.active.value)
        assertEquals(1, store.admin!!.tries.wrong)
        type5632()
        rule.waitUntil(10_000) { !shown("admin pin dialog") }
        assertEquals(ActiveAdmin(9, "Approver"), gateway.active.value)
        awaitFocus("admin switch back")
        assertEquals(PinTries(), store.admin!!.tries)
        press(Key.DirectionCenter)
        assertNull(gateway.active.value)
        awaitFocus("admin switch in")
    }

    @Test
    fun theTenthWrongPinForgetsTheAccountAndSaysSo() {
        store.admin = store.admin!!.copy(tries = PinTries(wrong = 9))
        showPage()
        awaitFocus("admin switch in")
        press(Key.DirectionCenter)
        awaitFocus("pin 5")
        repeat(4) { press(Key.DirectionCenter) }
        await("admin gone")
        rule.onNodeWithTag("admin gone", useUnmergedTree = true)
            .assertTextEquals("Too many wrong PINs: the TV forgot the admin account. Sign in again with its password to choose a new PIN.")
        assertNull(store.admin)
        assertTrue(!shown("admin pin dialog"))
    }

    @Test
    fun backOnAnEmptyPadClosesTheDialogAndSwitchesNothing() {
        showPage()
        awaitFocus("admin switch in")
        press(Key.DirectionCenter)
        awaitFocus("pin 5")
        press(Key.DirectionCenter)
        androidx.test.espresso.Espresso.pressBack()
        rule.waitForIdle()
        assertTrue("one digit off, still open", shown("admin pin dialog"))
        androidx.test.espresso.Espresso.pressBack()
        rule.waitForIdle()
        rule.waitUntil(5_000) { !shown("admin pin dialog") }
        awaitFocus("admin switch in")
        assertNull(gateway.active.value)
    }

    // ---- the mark, through the home -----------------------------------------------------------------

    /** An admin account that is active (or not) as the test says. */
    private class Active(name: String?) : AdminAccess {
        override val active = MutableStateFlow(name?.let { ActiveAdmin(9, it) })
        override suspend fun kept() = AdminSummary("Approver")
        override suspend fun signIn(request: SignInRequest, family: User) = AdminSignIn.Failed(com.tomdebres.hither.core.SignInFailure.OTHER)
        override suspend fun setPin(pin: String) = false
        override suspend fun cancel() = Unit
        override suspend fun remove() = Unit
        override suspend fun switchIn(pin: String) = AdminSwitch.NoneKept
        override fun back() {
            active.value = null
        }
    }

    private val silent = object : SeerrAccess {
        override suspend fun <T> call(call: suspend (SeerrClient) -> T): T = awaitCancellation()
    }

    private val session = Session(family, PublicSettings(applicationTitle = "Seerr", localLogin = true), baseUrl = "http://seerr.test:5055")
    private val recomposed = mutableMapOf<String, Int>()

    private fun showHome(admin: Active) {
        rule.setContent {
            CompositionLocalProvider(LocalRecompositionProbe provides { name -> recomposed[name] = (recomposed[name] ?: 0) + 1 }) {
                DpadHost { HomeScreen(Screen.Home(session), silent, onSignOut = {}, admin = admin) }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().let {
        with(rule.density) { Rect(it.left.toPx(), it.top.toPx(), it.right.toPx(), it.bottom.toPx()) }
    }

    @Test
    fun theBadgeShowsOnlyWhileTheAdminIsActiveAndThePillIsGone() {
        val admin = Active("Approver")
        showHome(admin)
        await("admin badge")
        // "Admin" with a shield, the account itself reading it (A2; tvOS's 183), not 93's pill with the initial and the name
        assertEquals(listOf("Admin"), rule.onNodeWithTag("admin badge", useUnmergedTree = true).fetchSemanticsNode().children.flatMap { it.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty() }.map { it.text })
        assertTrue("no pill", !shown("admin mark") && !shown("admin mark name"))
        // the account says so, and whose it is
        assertEquals(listOf("Admin mode: Approver"), rule.onNodeWithTag("avatar").fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        admin.back()
        rule.waitUntil(5_000) { !shown("admin badge") }
        assertEquals(listOf("Account, Living room TV"), rule.onNodeWithTag("avatar").fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
    }

    @Test
    fun theBadgeIsOnDiscoversBarAndOverEveryOtherPage() {
        val admin = Active("Approver")
        showHome(admin)
        await("admin badge")
        val bar = bounds("bar")
        val mark = bounds("admin badge")
        assertTrue("on the bar's line: $mark in $bar", mark.top >= bar.top && mark.bottom <= bar.bottom)
        val account = bounds("avatar")
        assertTrue("the account reads it (A2): $mark in $account", mark.left >= account.left && mark.right <= account.right)
        // up to the bar, then the avatar: Settings
        press(Key.DirectionUp)
        // Requests, search, the avatar
        repeat(3) { press(Key.DirectionRight) }
        awaitFocus("avatar")
        press(Key.DirectionCenter)
        await("switch back")
        assertTrue("the badge over Settings", shown("admin badge"))
        // Settings' account is the approver's while it is active
        rule.onNodeWithTag("account name", useUnmergedTree = true).assertTextEquals("Approver")
    }

    @Test
    fun settingsPutsSwitchBackFirstAndFocusedAndItTakesTheMarkAway() {
        val admin = Active("Approver")
        showHome(admin)
        press(Key.DirectionUp)
        // Requests, search, the avatar
        repeat(3) { press(Key.DirectionRight) }
        awaitFocus("avatar")
        press(Key.DirectionCenter)
        awaitFocus("switch back")
        press(Key.DirectionCenter)
        assertNull(admin.active.value)
        rule.waitUntil(5_000) { !shown("admin badge") }
        awaitFocus("about")
        assertTrue("the row went with the admin", !shown("switch back"))
    }

    @Test
    fun searchLeavesTheBadgeItsRoom() {
        val admin = Active("A very long approver name for the TV")
        showHome(admin)
        press(Key.DirectionUp)
        repeat(2) { press(Key.DirectionRight) }
        awaitFocus("search")
        press(Key.DirectionCenter)
        await("search field")
        press(Key.Back)
        rule.waitForIdle()
        val field = bounds("search field")
        val mark = bounds("admin badge")
        assertTrue("the field ($field) ends before the badge ($mark)", field.right <= mark.left)
        assertTrue("the badge keeps to its room", mark.width <= with(rule.density) { AdminBadgeRoom.toPx() })
    }

    @Test
    fun aMoveAlongTheBarRecomposesItsTwoPillsNotTheBar() {
        showHome(Active("Approver"))
        await("admin badge")
        press(Key.DirectionUp)
        awaitFocus("nav Discover")
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        recomposed.clear()
        press(Key.DirectionRight)
        awaitFocus("nav Requests")
        assertTrue("the bar recomposed: $recomposed", "TopBar" !in recomposed)
    }
}
