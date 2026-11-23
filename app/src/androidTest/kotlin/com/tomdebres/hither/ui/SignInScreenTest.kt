package com.tomdebres.hither.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.SignInFailure
import com.tomdebres.hither.core.rules.SignInProblem
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.ui.signin.SignInScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The sign-in screen under a D-pad. Needs a device or emulator; CI compiles it only. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SignInScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private var state by mutableStateOf(Screen.SignIn())
    private var server by mutableStateOf("")
    private var email by mutableStateOf("")
    private var password by mutableStateOf("")
    private var submits = 0

    private fun show() = rule.setContent {
        SignInScreen(
            state = state,
            server = server,
            email = email,
            password = password,
            onServer = { server = it },
            onEmail = { email = it },
            onPassword = { password = it },
            onSubmit = { submits++ },
        )
    }

    private fun press(key: Key) = rule.onRoot().performKeyInput { pressKey(key) }

    @Test
    fun focusStartsOnServerAndDownWalksToTheButtonWithoutOpeningAKeyboard() {
        show()
        rule.onNodeWithTag("server").assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag("email").assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag("password").assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag("sign in").assertIsFocused()
        // the end of the column is fenced: Down again stays put
        press(Key.DirectionDown)
        rule.onNodeWithTag("sign in").assertIsFocused()
        press(Key.DirectionUp)
        rule.onNodeWithTag("password").assertIsFocused()
    }

    @Test
    fun selectOnTheButtonSignsInOncePerPress() {
        show()
        repeat(3) { press(Key.DirectionDown) }
        press(Key.DirectionCenter)
        assertEquals(1, submits)
    }

    @Test
    fun selectOnAFieldOpensItForTypingAndNextCarriesOn() {
        show()
        press(Key.DirectionCenter)
        rule.onNodeWithTag("server input").assertIsFocused()
        rule.onNodeWithTag("server input").performTextInput("seerr.test:5055")
        assertEquals("seerr.test:5055", server)
        // the keyboard's action key: on to the email field, already open for typing
        press(Key.Enter)
        rule.onNodeWithTag("email input").assertIsFocused()
        rule.onNodeWithTag("email input").performTextInput("tv@example.invalid")
        press(Key.Enter)
        rule.onNodeWithTag("password input").assertIsFocused()
        rule.onNodeWithTag("password input").performTextInput("secret")
        // Done lands on the button
        press(Key.Enter)
        rule.onNodeWithTag("sign in").assertIsFocused()
        assertEquals("tv@example.invalid", email)
        assertEquals("secret", password)
    }

    @Test
    fun thePasswordIsNeverDrawnInTheClear() {
        password = "secret"
        show()
        rule.onNodeWithTag("password input").assertTextEquals("••••••")
    }

    @Test
    fun errorsReadAsSeerrWordsThem() {
        state = Screen.SignIn(
            problems = setOf(SignInProblem.EMAIL, SignInProblem.PASSWORD),
            failure = SignInFailure.WRONG_CREDENTIALS,
            serverTitle = "Seerr",
        )
        show()
        rule.onNodeWithText("Sign in to Seerr").assertIsDisplayed()
        rule.onNodeWithTag("email error").assertTextEquals("Enter an email address.")
        rule.onNodeWithTag("password error").assertTextEquals("Enter a password.")
        rule.onNodeWithTag("sign-in error").assertTextEquals("The email address or password is incorrect.")
    }

    @Test
    fun whileSigningInTheButtonSaysSo() {
        state = Screen.SignIn(submitting = true)
        show()
        rule.onNodeWithText("Signing In…").assertIsDisplayed()
    }
}
