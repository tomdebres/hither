package com.tomdebres.hither.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.SignInFailure
import com.tomdebres.hither.core.rules.MediaServerKind
import com.tomdebres.hither.core.rules.SignInMethods
import com.tomdebres.hither.core.rules.SignInProblem
import com.tomdebres.hither.session.CodeKind
import com.tomdebres.hither.session.CodeState
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.ServerOffer
import com.tomdebres.hither.session.SignInControl
import com.tomdebres.hither.session.SignInPage
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.signin.CodeSignInScreen
import com.tomdebres.hither.ui.signin.MediaServerSignInScreen
import com.tomdebres.hither.ui.signin.SignInScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M8's sign-in screens under a D-pad on tv31: the form offering what the server enables, Jellyfin's and Emby's form,
 * and the code pages, each with their errors in their words. Needs a device or emulator; CI compiles it only.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MediaSignInScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private fun press(key: Key) = rule.onRoot().performKeyInput { pressKey(key) }
    private fun offer(local: Boolean = true, plex: Boolean = false, password: Boolean = false, quickConnect: Boolean = false, kind: MediaServerKind = MediaServerKind.PLEX) =
        ServerOffer("http://seerr.test:5055", "Seerr", SignInMethods(local, plex, password, quickConnect, kind))

    // ---- the form ------------------------------------------------------------------------------------------------

    private var state by mutableStateOf(Screen.SignIn())
    private var plexes = 0
    private var mediaServers = 0
    private var serverDone = 0
    private var onServerDone: () -> Unit = {}

    private fun form() = rule.setContent {
        DpadHost {
            SignInScreen(
                state = state, server = "seerr.test:5055", email = "", password = "",
                onServer = {}, onEmail = {}, onPassword = {}, onSubmit = {},
                onServerDone = { serverDone++; onServerDone() }, onPlex = { plexes++ }, onMediaServer = { mediaServers++ },
            )
        }
    }

    @Test
    fun aPlexServerOffersPlexUnderThePasswordFormAndDownReachesIt() {
        state = Screen.SignIn(offer = offer(plex = true), serverTitle = "Seerr")
        form()
        rule.onNodeWithTag("or sign in with").assertTextEquals("Other ways to sign in")
        rule.onNodeWithTag("plex").assertIsDisplayed()
        repeat(4) { press(Key.DirectionDown) }
        rule.onNodeWithTag("plex").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, plexes)
        // leaving the server field read the server
        assertEquals(1, serverDone)
    }

    @Test
    fun jellyfinAndEmbyAreNamedOnTheirButton() {
        state = Screen.SignIn(offer = offer(password = true, kind = MediaServerKind.EMBY))
        form()
        rule.onNodeWithTag("media server").assertTextEquals("Emby")
        state = Screen.SignIn(offer = offer(password = true, kind = MediaServerKind.JELLYFIN))
        rule.onNodeWithTag("media server").assertTextEquals("Jellyfin")
    }

    @Test
    fun withPasswordSignInOffTheFormIsTheServerAndTheMediaServersButton() {
        state = Screen.SignIn(offer = offer(local = false, plex = true))
        form()
        rule.onNodeWithTag("email").assertDoesNotExist()
        rule.onNodeWithTag("sign in").assertDoesNotExist()
        rule.onNodeWithTag("or sign in with").assertDoesNotExist()
        press(Key.DirectionDown)
        rule.onNodeWithTag("plex").assertIsFocused()
    }

    @Test
    fun nextOnAServerWithPasswordSignInOffLandsOnItsButton_headedSignInToContinue() {
        // The server is read as Next is pressed; focus has gone on to the email field, which the offer then takes away.
        onServerDone = { state = Screen.SignIn(offer = offer(local = false, plex = true), serverTitle = "Seerr") }
        form()
        press(Key.DirectionCenter)
        press(Key.Enter)
        rule.waitUntil(3_000) { runCatching { rule.onNodeWithTag("plex").assertIsFocused() }.isSuccess }
        rule.onNodeWithTag("heading").assertTextEquals("Sign in to your server")
    }

    @Test
    fun beforeTheServerIsReadTheFormIsTheOneBeforeM8() {
        form()
        rule.onNodeWithTag("email").assertIsDisplayed()
        rule.onNodeWithTag("plex").assertDoesNotExist()
        rule.onNodeWithTag("media server").assertDoesNotExist()
    }

    @Test
    fun aClosedPageGivesFocusBackToItsButton() {
        state = Screen.SignIn(offer = offer(plex = true), returnTo = SignInControl.PLEX)
        form()
        rule.waitUntil(2_000) { runCatching { rule.onNodeWithTag("plex").assertIsFocused() }.isSuccess }
    }

    @Test
    fun theFormsErrorsAboutTheMediaServerAreInPlainWords() {
        val words = mapOf(
            SignInFailure.NOT_IMPORTED to "Seerr refused this Plex account: it is not one of Seerr's users, or it has no access to the server's Plex. Ask whoever runs Seerr.",
            SignInFailure.METHOD_OFF to "Signing in with Plex is switched off on this server.",
            SignInFailure.SESSION_ENDED to "Your session on this server has ended. Sign in again.",
        )
        state = Screen.SignIn(offer = offer(plex = true))
        form()
        for ((failure, said) in words) {
            state = Screen.SignIn(offer = offer(plex = true), failure = failure)
            rule.onNodeWithTag("sign-in error").assertTextEquals(said)
        }
        state = Screen.SignIn(checking = true)
        rule.onNodeWithTag("checking").assertTextEquals("Checking the server…")
    }

    @Test
    fun theTallestFormsWithTheMediaServersButtonAreReachedWhole() {
        // With the button shown, the server is a read address, so its own error cannot show (another address drops the
        // offer). The two tallest: an error under the email and the password, or the longest refusal.
        state = Screen.SignIn(offer = offer(plex = true), problems = setOf(SignInProblem.EMAIL, SignInProblem.PASSWORD))
        form()
        assertReached("password error", "the fields' errors")
        state = Screen.SignIn(offer = offer(plex = true), failure = SignInFailure.NOT_IMPORTED)
        rule.waitForIdle()
        assertReached("sign-in error", "the longest refusal")
        state = Screen.SignIn(offer = offer(plex = true), failure = SignInFailure.CSRF_NEEDS_HTTPS)
        rule.waitForIdle()
        assertReached("sign-in error", "the CSRF failure")
    }

    /**
     * The tallest forms can be taller than the screen: the column scrolls as focus moves down it, as tvOS's does (A7's
     * `EntryColumn`). So [message] is whole where it is, and each button, walked to by D-pad from the top, is whole and
     * on screen when it has focus, at its full height.
     */
    private fun assertReached(message: String, what: String) {
        val screen = rule.onRoot().fetchSemanticsNode().size
        val words = rule.onNodeWithTag(message).fetchSemanticsNode()
        assertEquals("$what: the message is not cut off", words.size.height.toFloat(), words.boundsInRoot.height, 1f)
        val full = with(rule.density) { 40.dp.toPx() } // tv-material's button height (A1; Seerr's was 32)
        repeat(6) { press(Key.DirectionUp) }
        for (tag in listOf("sign in", "plex")) {
            repeat(6) { if (runCatching { rule.onNodeWithTag(tag).assertIsFocused() }.isFailure) press(Key.DirectionDown) }
            rule.onNodeWithTag(tag).assertIsFocused()
            rule.waitForIdle()
            val button = rule.onNodeWithTag(tag).fetchSemanticsNode()
            assertEquals("$what: $tag keeps its full height", full, button.size.height.toFloat(), 1f)
            // drawn at the focus scale (1.1), never less than whole
            assertTrue("$what: $tag is not cut off (${button.boundsInRoot.height} of ${button.size.height})", button.boundsInRoot.height >= button.size.height - 1f)
            assertTrue("$what: $tag starts on screen (${button.boundsInRoot.top})", button.boundsInRoot.top >= 0f)
            assertTrue("$what: $tag ends on screen (${button.boundsInRoot.bottom} of ${screen.height})", button.boundsInRoot.bottom <= screen.height)
        }
    }

    // ---- Jellyfin and Emby ----------------------------------------------------------------------------------------

    private var page by mutableStateOf(SignInPage.MediaServer(MediaServerKind.JELLYFIN, quickConnect = true))
    private var backs = 0
    private var submits = 0
    private var quickConnects = 0

    private fun mediaServer() = rule.setContent {
        DpadHost {
            MediaServerSignInScreen(
                page = page, username = "", password = "", onUsername = {}, onPassword = {},
                onSubmit = { submits++ }, onQuickConnect = { quickConnects++ }, onBack = { backs++ },
            )
        }
    }

    @Test
    fun jellyfinsFormIsUsernamePasswordSignInAndQuickConnectInThatOrder() {
        mediaServer()
        rule.onNodeWithTag("heading").assertTextEquals("Sign in with Jellyfin")
        rule.onNodeWithTag("username").assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag("media password").assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag("media sign in").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, submits)
        press(Key.DirectionDown)
        rule.onNodeWithTag("quick connect").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, quickConnects)
        press(Key.DirectionDown)
        rule.onNodeWithTag("quick connect").assertIsFocused()
    }

    @Test
    fun embysFormHasNoQuickConnectAndBackLeavesIt() {
        page = SignInPage.MediaServer(MediaServerKind.EMBY, quickConnect = false)
        mediaServer()
        rule.onNodeWithTag("heading").assertTextEquals("Sign in with Emby")
        rule.onNodeWithTag("quick connect").assertDoesNotExist()
        Espresso.pressBack()
        rule.waitForIdle()
        assertEquals(1, backs)
    }

    @Test
    fun jellyfinsErrorsAreSeerrsWords() {
        page = SignInPage.MediaServer(MediaServerKind.JELLYFIN, quickConnect = true, problems = setOf(SignInProblem.EMAIL), failure = SignInFailure.WRONG_CREDENTIALS)
        mediaServer()
        rule.onNodeWithTag("username error").assertTextEquals("Enter a username.")
        rule.onNodeWithTag("media sign-in error").assertTextEquals("The server did not accept that username and password.")
        page = page.copy(problems = emptySet(), failure = SignInFailure.NOT_IMPORTED)
        rule.onNodeWithTag("media sign-in error").assertTextEquals("Seerr does not know this Jellyfin account yet. Ask whoever runs Seerr to import it as a user, then sign in again.")
        page = page.copy(failure = SignInFailure.MEDIA_SERVER_UNREACHABLE)
        rule.onNodeWithTag("media sign-in error").assertTextEquals("Seerr could not reach its Jellyfin server. Try again later.")
    }

    @Test
    fun comingBackFromQuickConnectLandsOnIt() {
        page = SignInPage.MediaServer(MediaServerKind.JELLYFIN, quickConnect = true, returnTo = SignInControl.QUICK_CONNECT)
        mediaServer()
        rule.waitUntil(2_000) { runCatching { rule.onNodeWithTag("quick connect").assertIsFocused() }.isSuccess }
    }

    // ---- the codes -------------------------------------------------------------------------------------------------

    private var code by mutableStateOf(SignInPage.Code(CodeKind.PLEX, code = "X7K6", state = CodeState.WAITING))
    private var cancels = 0
    private var newCodes = 0

    private fun codePage() = rule.setContent {
        DpadHost { CodeSignInScreen(code, onNewCode = { newCodes++ }, onCancel = { cancels++ }) }
    }

    @Test
    fun plexsCodeIsLargeWithWhereToGoAndCancelHoldsFocus() {
        // The waiting spinner never rests: the clock is driven by hand.
        rule.mainClock.autoAdvance = false
        codePage()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("code heading").assertTextEquals("Sign in with Plex")
        rule.onNodeWithTag("plex link").assertTextEquals("plex.tv/link")
        // spelt out for a screen reader, one character at a time
        rule.onNodeWithTag("code").assertContentDescriptionEquals("Code X 7 K 6")
        // 80 sp: its box at least a sixth of the TV's 540 dp height (97 dp measured on tv31), readable across the room
        val height = rule.onNodeWithTag("code").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue("the code is $height tall", height >= 90.dp)
        rule.onNodeWithTag("waiting").assertTextEquals("Waiting for the code to be entered…")
        rule.onNodeWithTag("cancel").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, cancels)
    }

    @Test
    fun backOnACodeIsCancel() {
        rule.mainClock.autoAdvance = false
        codePage()
        rule.mainClock.advanceTimeBy(500)
        Espresso.pressBack()
        rule.mainClock.advanceTimeBy(100)
        assertEquals(1, cancels)
    }

    @Test
    fun aCodeThatRanOutOffersANewOneFocused() {
        code = SignInPage.Code(CodeKind.PLEX, code = "X7K6", state = CodeState.EXPIRED, failure = SignInFailure.CODE_EXPIRED)
        codePage()
        rule.onNodeWithTag("code trouble").assertTextEquals("Code Expired")
        rule.onNodeWithTag("code failure").assertTextEquals("This code expired before it was entered at plex.tv/link.")
        rule.waitUntil(2_000) { runCatching { rule.onNodeWithTag("new code").assertIsFocused() }.isSuccess }
        rule.onNodeWithTag("new code").assertTextEquals("Get a New Code")
        press(Key.DirectionCenter)
        assertEquals(1, newCodes)
        press(Key.DirectionRight)
        rule.onNodeWithTag("cancel").assertIsFocused()
    }

    @Test
    fun plexTvUnreachableSaysSoWithTryAgain() {
        code = SignInPage.Code(CodeKind.PLEX, state = CodeState.FAILED, failure = SignInFailure.PLEX_UNREACHABLE)
        codePage()
        rule.onNodeWithTag("code failure").assertTextEquals("plex.tv could not be reached. Check the TV's internet connection, then try again.")
        rule.onNodeWithTag("new code").assertTextEquals("Try Again")
    }

    @Test
    fun quickConnectsCodeIsTwoThreesWithWhereToApproveIt() {
        rule.mainClock.autoAdvance = false
        code = SignInPage.Code(CodeKind.QUICK_CONNECT, code = "525562", state = CodeState.WAITING, approveAt = "jellyfin.example.invalid")
        codePage()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("code heading").assertTextEquals("Quick Connect")
        rule.onNodeWithTag("code").assertContentDescriptionEquals("Code 5 2 5 5 6 2")
        rule.onNodeWithTag("approve where").assertTextEquals(
            "Signed in to Jellyfin at jellyfin.example.invalid on a phone or computer: your profile, then Quick Connect.",
        )
        rule.onNodeWithTag("waiting").assertTextEquals("Waiting for the code to be approved…")
    }

    @Test
    fun quickConnectsExpiryAndFailureAreSeerrsWords() {
        code = SignInPage.Code(CodeKind.QUICK_CONNECT, code = "525562", state = CodeState.EXPIRED, failure = SignInFailure.CODE_EXPIRED)
        codePage()
        rule.onNodeWithTag("code failure").assertTextEquals("This code expired before it was approved in Jellyfin.")
        code = SignInPage.Code(CodeKind.QUICK_CONNECT, state = CodeState.FAILED, failure = SignInFailure.QUICK_CONNECT_UNAVAILABLE)
        rule.onNodeWithTag("code failure").assertTextEquals("Quick Connect did not start. It may be switched off in Jellyfin's dashboard.")
    }
}
