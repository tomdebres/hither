package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.FailureKind
import com.tomdebres.hither.core.FailureLog
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.home.HomeScreen
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * About › Diagnostics (M6, DECISIONS 107; under About since A7, as tvOS's S41): the page lists the log newest first,
 * each failure a row to read by D-pad, with no address; none says so; Back returns to the row.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DiagnosticsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val session = Session(User(id = 8, displayName = "TV", permissions = 32), PublicSettings(applicationTitle = "Seerr", localLogin = true), baseUrl = "http://seerr.test:5055")
    private val log = FailureLog(now = { 1_762_970_000_000L })

    private val silent = object : SeerrAccess {
        override suspend fun <T> call(call: suspend (SeerrClient) -> T): T = awaitCancellation()
    }

    private fun show() {
        rule.setContent { DpadHost { HomeScreen(Screen.Home(session), silent, onSignOut = {}, failures = log) } }
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
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "a node without a tag" } ?: "a node without a tag"
    }.getOrElse { "nothing" }

    private fun assertFocused(tag: String) {
        runCatching { rule.waitUntil(5_000) { focusedTag() == tag } }
        val now = focusedTag()
        if (now != tag) throw AssertionError("expected focus on $tag, it is on $now")
    }

    /** The row's words, as a screen reader reads the row (tv-material's list item merges them). */
    private fun words(tag: String): String = rule.onAllNodesWithTag(tag).fetchSemanticsNodes()
        .flatMap { it.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty() }
        .joinToString(" ") { it.text }

    /** Discover → the avatar → Settings → About → down past Privacy to Diagnostics. */
    private fun toDiagnostics() {
        show()
        press(Key.DirectionUp)
        press(Key.DirectionRight, 3)
        assertFocused("avatar")
        press(Key.DirectionCenter)
        assertFocused("about")
        press(Key.DirectionCenter)
        assertFocused("privacy")
        press(Key.DirectionDown)
        assertFocused("diagnostics")
        press(Key.DirectionCenter)
    }

    @Test
    fun theLogNewestFirstEachALineToReadAndBackToTheRow() {
        log.record(FailureKind.HTTP, "GET", "/movie/1", status = 500)
        log.record(FailureKind.NETWORK, "GET", "/api/v1/search?query=secret", cause = "SocketTimeoutException")
        toDiagnostics()
        assertFocused("failure 0")
        val first = words("failure 0")
        assertTrue(first, "Network (SocketTimeoutException)" in first && "GET /search" in first)
        assertTrue("no query: $first", "secret" !in first)
        press(Key.DirectionDown)
        assertFocused("failure 1")
        assertTrue(words("failure 1"), "HTTP 500" in words("failure 1") && "GET /movie/1" in words("failure 1"))
        press(Key.DirectionDown)
        assertFocused("failure 1")
        back()
        assertFocused("diagnostics")
    }

    @Test
    fun anEmptyLogSaysSo() {
        toDiagnostics()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("diagnostics none").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, rule.onAllNodesWithTag("failure 0").fetchSemanticsNodes().size)
        // the line takes focus, as a row would: focus never lands on nothing (the polish 2 audit's item 34)
        assertFocused("diagnostics none")
        back()
        assertFocused("diagnostics")
    }
}
