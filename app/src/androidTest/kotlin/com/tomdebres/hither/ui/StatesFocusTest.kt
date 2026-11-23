package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.PageInfo
import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.RequestPage
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.RequestFilter
import com.tomdebres.hither.core.rules.RequestTitle
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.ui.approvals.ApprovalsModel
import com.tomdebres.hither.ui.approvals.ApprovalsScreen
import com.tomdebres.hither.ui.approvals.ApprovalsSource
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.ShowsFocus
import com.tomdebres.hither.ui.grid.GridSource
import com.tomdebres.hither.ui.grid.TitleGridModel
import com.tomdebres.hither.ui.grid.TitleGridScreen
import com.tomdebres.hither.ui.home.UnavailableScreen
import com.tomdebres.hither.ui.requests.RequestsModel
import com.tomdebres.hither.ui.requests.RequestsScreen
import com.tomdebres.hither.ui.requests.RequestsSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every state's way on has focus when the state arrives, and shows it (H2's X2; REVIEW.md: focus never lands on
 * nothing), now that every state is a `StateView` (DECISIONS 295). The pages whose own tests already hold it (a title's
 * and a person's failure, Issues' failure, My Requests' failure) are listed there; these are the rest, in one place:
 * everyone's requests failed (its Try Again had no focus before A1: it stayed on a filter above it), a grid failed, My
 * Requests empty, and the server not answering. Since A6 the list pages are tvOS's Library split: their states arrive
 * with focus on the side list's chosen row, as tvOS's do, and the way on is one Right away (DECISIONS 338).
 */
@RunWith(AndroidJUnit4::class)
class StatesFocusTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val showsFocus = SemanticsMatcher.expectValue(ShowsFocus, true)

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.TestTag) ?: "?"
    }.getOrElse { "nothing" }

    /** [action] arrives, takes focus and shows it. */
    private fun assertFocusArrivesOn(action: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(action).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals("focus when the state arrived", action, focusedTag())
        rule.onNodeWithTag(action).assert(showsFocus)
    }

    /** A list page's state arrives with focus on the side list's chosen [row]; Right reaches [action], which shows it. */
    private fun assertSideThenAction(row: String, action: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(action).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals("focus when the state arrived", row, focusedTag())
        rule.onNodeWithTag(row).assert(showsFocus)
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals("focus after Right from the side list", action, focusedTag())
        rule.onNodeWithTag(action).assert(showsFocus)
    }

    private val noAnswer = IllegalStateException("no answer")

    @Test
    fun everyonesRequestsFailedFocusesTryAgain() {
        val failing = object : ApprovalsSource {
            override suspend fun me() = User(id = 9, displayName = "Approver", permissions = Permission.MANAGE_REQUESTS)
            override suspend fun page(filter: RequestFilter, skip: Int, take: Int): RequestPage = throw noAnswer
            override suspend fun title(type: MediaType, tmdbId: Int) = RequestTitle("Film", null, null, null)
            override suspend fun decide(requestId: Int, approve: Boolean) = throw noAnswer
        }
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { ApprovalsModel(failing, scope, onDecided = {}) }
            DpadHost { ApprovalsScreen(model, onOpen = {}, onBack = {}) }
        }
        assertSideThenAction("filter pending", "try again")
    }

    @Test
    fun aGridThatFailedFocusesTryAgain() {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { TitleGridModel(GridSource { throw noAnswer }, scope) }
            DpadHost { TitleGridScreen(model, heading = "Heist Movies", onOpen = {}, onBack = {}) }
        }
        assertFocusArrivesOn("try again")
    }

    @Test
    fun myRequestsEmptyFocusesTheWayBack() {
        val empty = object : RequestsSource {
            override suspend fun page(skip: Int, take: Int) = RequestPage(PageInfo(results = 0), emptyList())
            override suspend fun title(type: MediaType, tmdbId: Int) = RequestTitle("Film", null, null, null)
        }
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { RequestsModel(empty, scope) }
            DpadHost { RequestsScreen(model, onOpen = {}, onBack = {}) }
        }
        assertSideThenAction("side my requests", "back to discover")
    }

    @Test
    fun myRequestsFailedFocusesTryAgain() {
        val failing = object : RequestsSource {
            override suspend fun page(skip: Int, take: Int): RequestPage = throw noAnswer
            override suspend fun title(type: MediaType, tmdbId: Int) = RequestTitle("Film", null, null, null)
        }
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { RequestsModel(failing, scope) }
            DpadHost { RequestsScreen(model, onOpen = {}, onBack = {}) }
        }
        assertSideThenAction("side my requests", "try again")
    }

    @Test
    fun theServerNotAnsweringFocusesTryAgain() {
        rule.setContent {
            DpadHost { UnavailableScreen(Screen.Unavailable(server = "http://seerr.test:5055"), onRetry = {}, onSignOut = {}) }
        }
        assertFocusArrivesOn("try again")
    }
}
