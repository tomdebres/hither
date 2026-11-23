package com.tomdebres.hither.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.Session
import kotlinx.coroutines.awaitCancellation
import com.tomdebres.hither.ui.components.ShowsFocus
import com.tomdebres.hither.ui.home.HomeScreen
import com.tomdebres.hither.ui.home.UnavailableScreen
import com.tomdebres.hither.ui.signin.SignInScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Focus never lands on nothing (REVIEW.md): when one screen replaces another while a control
 * on the old one holds focus, the new screen's first control has it. Found on tv31: after a
 * real sign-in the page arrived with nothing focused, and Select did nothing.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ScreenChangeFocusTest {
    @get:Rule
    val rule = createComposeRule()

    private var screen: Screen by mutableStateOf(Screen.SignIn())
    private val home = Screen.Home(Session(User(id = 8, displayName = "TV"), PublicSettings(applicationTitle = "Seerr", localLogin = true)))

    /**
     * A server that never answers: Discover's rows stay loading. Its first row is Recent Requests
     * (DECISIONS 84), whose placeholders hold focus while the user's requests are on their way.
     */
    private val silent = object : SeerrAccess {
        override suspend fun <T> call(call: suspend (SeerrClient) -> T): T = awaitCancellation()
    }

    private fun show() = rule.setContent {
        when (val current = screen) {
            is Screen.SignIn -> SignInScreen(current, "", "", "", {}, {}, {}, onSubmit = {})
            is Screen.Home -> HomeScreen(current, silent, onSignOut = {})
            is Screen.Unavailable -> UnavailableScreen(current, onRetry = {}, onSignOut = {})
            Screen.Starting -> Unit
        }
    }

    private fun press(key: Key) = rule.onRoot().performKeyInput { pressKey(key) }
    private val showsFocus = SemanticsMatcher.expectValue(ShowsFocus, true)

    @Test
    fun afterSignInDiscoverHasFocusOnItsFirstRow() {
        show()
        repeat(3) { press(Key.DirectionDown) }
        rule.onNodeWithTag("sign in").assertIsFocused()

        screen = home
        rule.waitForIdle()
        // the first row is still loading: its placeholders hold focus until the cards come
        rule.onNodeWithTag("recent loading").assertIsFocused()
        // and it is drawn that way: on tv31 a control once had focus and looked as if it had not
        rule.onNodeWithTag("recent loading").assert(showsFocus)
    }

    @Test
    fun whenTheServerGoesAwayTryAgainHasFocusAndComingBackGivesItToDiscover() {
        screen = home
        show()
        rule.onNodeWithTag("recent loading").assertIsFocused()

        screen = Screen.Unavailable()
        rule.waitForIdle()
        rule.onNodeWithTag("try again").assertIsFocused()
        rule.onNodeWithTag("try again").assert(showsFocus)

        screen = home
        rule.waitForIdle()
        rule.onNodeWithTag("recent loading").assertIsFocused()
    }

    @Test
    fun aRefusedSignInKeepsFocusOnTheForm() {
        show()
        repeat(3) { press(Key.DirectionDown) }
        // the form is redrawn with an error; the button the user pressed still has focus
        screen = Screen.SignIn(failure = com.tomdebres.hither.core.SignInFailure.WRONG_CREDENTIALS)
        rule.waitForIdle()
        rule.onNodeWithTag("sign in").assertIsFocused()
    }
}
