package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
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
import com.tomdebres.hither.data.AdminStorage
import com.tomdebres.hither.data.KeptAdmin
import com.tomdebres.hither.data.PinHash
import com.tomdebres.hither.session.AdminGateway
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.home.HomeScreen
import com.tomdebres.hither.ui.settings.AdminScreen
import com.tomdebres.hither.ui.settings.AdminSetup
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Settings' pages in the shape of the Apple TV app's Settings (H2's X5; A7), on a device: the list 390 dp wide against
 * the right safe edge (48 dp in), the mark centred in what is left of the page; rows of words with no icon before them;
 * Sign Out red words on the row's own grey, never a red fill; About carrying Version, Privacy and Diagnostics as rows
 * (Diagnostics moved under it, tvOS's S41); and the approver's kept account as rows too. Needs a device or emulator; CI
 * compiles it only.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsLayoutTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val session = Session(
        User(id = 8, displayName = "Living room TV", email = "tv@example.invalid", permissions = 32),
        PublicSettings(applicationTitle = "Seerr", localLogin = true),
        baseUrl = "http://seerr.test:5055",
    )

    private val silent = object : SeerrAccess {
        override suspend fun <T> call(call: suspend (SeerrClient) -> T): T = awaitCancellation()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "?" } ?: "?"
    }.getOrElse { "nothing" }

    private fun awaitFocus(tag: String) {
        runCatching { rule.waitUntil(5_000) { focusedTag() == tag } }
        assertEquals("focus", tag, focusedTag())
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun px(dp: Float) = with(rule.density) { dp.dp.toPx() }

    /** Discover → the avatar → Settings. */
    private fun openSettings() {
        rule.setContent { DpadHost { HomeScreen(Screen.Home(session), silent, onSignOut = {}) } }
        rule.waitForIdle()
        press(Key.DirectionUp)
        press(Key.DirectionRight, 3)
        awaitFocus("avatar")
        press(Key.DirectionCenter)
        awaitFocus("about")
    }

    /**
     * [rows] each 390 dp wide with its right edge 48 dp from the screen's (measured at rest: focus is moved off a row
     * before it is measured, since the focused one is drawn a little larger), and no icon in any of them.
     */
    private fun assertRows(rows: List<String>, what: String) {
        val width = rule.onRoot().fetchSemanticsNode().size.width
        for (row in rows) {
            val box = bounds(row)
            assertEquals("$what: $row is 390 dp wide (${box.width} px)", px(390f), box.width, 2f)
            assertEquals("$what: $row ends at the right safe edge (${box.right} of $width)", width - px(48f), box.right, 2f)
            assertTrue(
                "$what: $row carries an icon",
                rule.onAllNodes(hasTestTag("icon") and hasAnyAncestor(hasTestTag(row)), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
            )
        }
    }

    /** [mark] centred in the room left of the list (to the left safe edge), at the page's middle height or near it. */
    private fun assertMarkLeft(mark: String, list: String, what: String) {
        val logo = bounds(mark)
        val row = bounds(list)
        val left = px(48f)
        val room = row.left - px(40f)
        assertEquals("$what: the mark is centred left of the list (${logo.center.x}, room $left..$room)", (left + room) / 2, logo.center.x, 3f)
        assertTrue("$what: the mark is left of the list", logo.right <= row.left)
    }

    @Test
    fun settingsIsTheMarkOnTheLeftAndRowsOfWordsOnTheRight() {
        openSettings()
        // each row measured at rest: Sign Out while About has focus, then About while Sign Out has it, one row down
        assertRows(listOf("sign out"), "Settings")
        assertMarkLeft("settings mark", "sign out", "Settings")
        // at rest, destructive by its words alone: the row's commonest colour is a row's grey, not a red fill (rule 2)
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val signOut = bounds("sign out")
        val seen = HashMap<Int, Int>()
        for (y in signOut.top.toInt() + 2 until signOut.bottom.toInt() - 2) for (x in signOut.left.toInt() + 8 until signOut.right.toInt() - 8) seen.merge(image.getPixel(x, y), 1, Int::plus)
        val ground = seen.maxByOrNull { it.value }!!.key
        val red = (ground ushr 16) and 0xFF
        val green = (ground ushr 8) and 0xFF
        assertTrue("Sign Out's row is ${"#%08X".format(ground)}: a grey, not a red fill", abs(red - green) <= 8 && red < 0x60)
        press(Key.DirectionDown)
        awaitFocus("sign out")
        assertRows(listOf("about"), "Settings")
    }

    @Test
    fun aboutCarriesVersionPrivacyAndDiagnosticsAsRows() {
        openSettings()
        press(Key.DirectionCenter)
        awaitFocus("privacy")
        rule.onNodeWithTag("version", useUnmergedTree = true).fetchSemanticsNode()
        press(Key.DirectionDown)
        awaitFocus("diagnostics")
        assertRows(listOf("privacy", "version"), "About")
        assertMarkLeft("about wordmark", "privacy", "About")
    }

    // ---- the approver's account, kept ---------------------------------------------------------------------------------

    private class Memory(@Volatile var admin: KeptAdmin?) : AdminStorage {
        override suspend fun admin() = admin
        override suspend fun save(admin: KeptAdmin) {
            this.admin = admin
        }
        override suspend fun clear() {
            admin = null
        }
    }

    @Test
    fun theKeptAccountIsRowsWithRemoveInRedWords() {
        val store = Memory(KeptAdmin("http://seerr.test:5055", "approver@example.invalid", "approver-password", 9, "Approver", PinHash.of("5632", iterations = 1_000)))
        val gateway = AdminGateway(store, engine = { MockEngine { respond("""{"status":"ok"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json")) } })
        rule.setContent {
            val scope = rememberCoroutineScope()
            val setup = remember { AdminSetup(gateway, User(id = 8, displayName = "Living room TV", permissions = 32), "http://seerr.test:5055", scope) }
            DpadHost { AdminScreen(setup, onBack = {}) }
        }
        awaitFocus("admin switch in")
        // Remove is the row under Switch, not a button beside it
        press(Key.DirectionDown)
        awaitFocus("admin remove")
        assertRows(listOf("admin switch in"), "Admin Account")
        press(Key.DirectionUp)
        awaitFocus("admin switch in")
        assertRows(listOf("admin remove"), "Admin Account")
        assertMarkLeft("admin mark", "admin remove", "Admin Account")
    }
}
