package com.tomdebres.hither.ui.signin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.R
import com.tomdebres.hither.core.SignInFailure
import com.tomdebres.hither.core.rules.SignInProblem
import com.tomdebres.hither.session.SignInControl
import com.tomdebres.hither.session.SignInPage
import com.tomdebres.hither.ui.components.EntryField
import com.tomdebres.hither.ui.components.FocusOnArrival
import com.tomdebres.hither.ui.components.liftAboveKeyboard
import com.tomdebres.hither.ui.components.rememberEntryFieldState
import com.tomdebres.hither.ui.components.rememberEntryLift
import com.tomdebres.hither.ui.theme.EntryColumn
import com.tomdebres.hither.ui.theme.EntryProblem
import com.tomdebres.hither.ui.theme.Metrics
import com.tomdebres.hither.ui.theme.Page
import com.tomdebres.hither.ui.theme.PageKind
import com.tomdebres.hither.ui.theme.PlainButton
import com.tomdebres.hither.ui.theme.ProminentButton

/**
 * Jellyfin's or Emby's sign-in (M8), Seerr's `JellyfinLogin` as a pushed page (tvOS's `MediaServerSignInView`): the
 * server's name ([serverTitle]) as the page's title, then an `EntryColumn` without the mark: "Sign in with Jellyfin",
 * Username and Password, Sign In (the page's one prominent button), what went wrong under it, and for Jellyfin on Seerr
 * 3.4.0+ Quick Connect, a plain button under it. The fields behave as the sign-in form's: Select opens the keyboard,
 * Next carries on, Done lands on Sign In, and the open field sits above the keyboard. Back (with no field open) returns
 * to the form, on the button that opened this; back from Quick Connect's code, focus is on Quick Connect.
 */
@Composable
fun MediaServerSignInScreen(
    page: SignInPage.MediaServer,
    username: String,
    password: String,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    onQuickConnect: () -> Unit,
    onBack: () -> Unit,
    /** The server's name, the page's title (tvOS's); none, no title. */
    serverTitle: String? = null,
) {
    val usernameField = rememberEntryFieldState()
    val passwordField = rememberEntryFieldState()
    val signIn = remember { FocusRequester() }
    val quickConnect = remember { FocusRequester() }
    val editable = !page.submitting
    val lift = rememberEntryLift()

    FocusOnArrival(if (page.returnTo == SignInControl.QUICK_CONNECT && page.quickConnect) quickConnect else usernameField.focus)
    BackHandler { onBack() }

    Page(PageKind.Pushed(serverTitle, titleTag = "page title"), modifier = Modifier.liftAboveKeyboard(lift), bleed = true) {
        EntryColumn(
            title = stringResource(R.string.sign_in_with_media_server, page.kind.title),
            lift = lift,
            mark = false,
            // Under the title; with none, where the sign-in's column starts.
            top = if (serverTitle == null) Metrics.TopMargin + Metrics.EntryTop else 0.dp,
            fields = {
                EntryField(
                    value = username,
                    onValueChange = onUsername,
                    placeholder = stringResource(R.string.field_username),
                    modifier = Modifier.fillMaxWidth(),
                    state = usernameField,
                    enabled = editable,
                    tag = "username",
                    error = if (SignInProblem.EMAIL in page.problems) stringResource(R.string.error_username) else null,
                    onImeAction = { passwordField.focus.requestFocus(); passwordField.edit() },
                )
                EntryField(
                    value = password,
                    onValueChange = onPassword,
                    placeholder = stringResource(R.string.field_password),
                    modifier = Modifier.fillMaxWidth(),
                    state = passwordField,
                    imeAction = ImeAction.Done,
                    password = true,
                    enabled = editable,
                    tag = "media password",
                    onImeAction = { signIn.requestFocus() },
                )
            },
        ) {
            ProminentButton(
                text = stringResource(if (page.submitting) R.string.signing_in else R.string.sign_in),
                onClick = onSubmit,
                centred = true,
                modifier = Modifier.fillMaxWidth().focusRequester(signIn).testTag("media sign in"),
            )
            page.failure?.let { EntryProblem(it.mediaServerMessage(page), "media sign-in error") }
            if (page.quickConnect) {
                // Seerr's quiet button under the form (JellyfinLogin, Seerr 3.4.0+): a plain one, the column's width.
                PlainButton(
                    text = stringResource(R.string.quick_connect),
                    onClick = onQuickConnect,
                    enabled = editable,
                    centred = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(quickConnect).testTag("quick connect"),
                )
            }
        }
    }
}

/** A failure on this form in its words: a wrong password is the username's, as Seerr's JellyfinLogin says it. */
@Composable
private fun SignInFailure.mediaServerMessage(page: SignInPage.MediaServer): String =
    if (this == SignInFailure.WRONG_CREDENTIALS) stringResource(R.string.error_media_credentials) else message(page.kind)
