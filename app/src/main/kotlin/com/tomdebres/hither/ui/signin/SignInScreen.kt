package com.tomdebres.hither.ui.signin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import com.tomdebres.hither.R
import com.tomdebres.hither.core.SignInFailure
import com.tomdebres.hither.core.rules.MediaServerKind
import com.tomdebres.hither.core.rules.SignInProblem
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.ui.components.EntryField
import com.tomdebres.hither.ui.components.FocusOnArrival
import com.tomdebres.hither.ui.components.liftAboveKeyboard
import com.tomdebres.hither.ui.components.rememberEntryFieldState
import com.tomdebres.hither.ui.components.rememberEntryLift
import com.tomdebres.hither.ui.theme.EntryColumn
import com.tomdebres.hither.ui.theme.EntryNote
import com.tomdebres.hither.ui.theme.EntryProblem
import com.tomdebres.hither.ui.theme.Metrics
import com.tomdebres.hither.ui.theme.Page
import com.tomdebres.hither.ui.theme.PageKind
import com.tomdebres.hither.ui.theme.PlainButton
import com.tomdebres.hither.ui.theme.ProminentButton

/**
 * First run, as the Apple TV app's sign-in (H2's X4; tvOS's `SignInFormView`): one centred column (`EntryColumn`) of
 * Hither's mark, small, the heading, Server Address, Email Address and Password (Seerr's sign-in with a Server field
 * above its two, `docs/design/seerr-reference.md` 2.11), then Sign In, the screen's one prominent button. Focus starts
 * on Server; Select on a field opens the keyboard; the keyboard's Next carries on to the following field and Done lands
 * on Sign In. Errors are Seerr's own sentences, in red words under what they are about.
 *
 * Since M8 the server is read once its field is done with ([onServerDone]: Next, or focus leaving it), and the form
 * offers what it enables (`ServerOffer`): the password form while Seerr's own sign-in is on (or not known yet), then
 * under "Other ways to sign in" the media server's button, Plex ([onPlex]) or Jellyfin and Emby ([onMediaServer]), a
 * plain button. With Seerr's own sign-in off there is no password form and no Sign In: the button is the way in. A page
 * that closes brings focus back to its button (`returnTo`).
 */
@Composable
fun SignInScreen(
    state: Screen.SignIn,
    server: String,
    email: String,
    password: String,
    onServer: (String) -> Unit,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    onServerDone: () -> Unit = {},
    onPlex: () -> Unit = {},
    onMediaServer: () -> Unit = {},
) {
    val serverField = rememberEntryFieldState()
    val emailField = rememberEntryFieldState()
    val passwordField = rememberEntryFieldState()
    val button = remember { FocusRequester() }
    val viaServer = remember { FocusRequester() }
    val editable = !state.submitting
    val methods = state.offer?.methods
    // Seerr's own sign-in shows until the server says it is off.
    val local = methods?.local != false
    val mediaServerButton = methods?.let { if (it.plex) MediaServerKind.PLEX else if (it.mediaServerPassword) it.mediaServer else null }
    // Focus last in the password form (its fields or Sign In), as against the server field or the way in.
    var localHadFocus by remember { mutableStateOf(false) }
    // Next on the server field before its offer came: focus went on to the email field, which may yet go.
    var advanceOnOffer by remember { mutableStateOf(false) }

    FocusOnArrival(if (state.returnTo != null && mediaServerButton != null) viaServer else serverField.focus)

    // The password form going (the server has it off) while focus was in it, or on its way there from the server field:
    // focus to the way in that is offered, once the form has gone.
    LaunchedEffect(local, mediaServerButton) {
        if (!local && mediaServerButton != null && (localHadFocus || advanceOnOffer)) {
            withFrameNanos { }
            viaServer.requestFocus()
        }
        if (methods != null) advanceOnOffer = false
    }
    val inLocal = Modifier.onFocusChanged { if (it.hasFocus) localHadFocus = true }
    val outOfLocal = Modifier.onFocusChanged { if (it.hasFocus) localHadFocus = false }

    val lift = rememberEntryLift()
    Page(PageKind.Screen, modifier = Modifier.liftAboveKeyboard(lift), bleed = true) {
        EntryColumn(
            // With no form (Seerr's own sign-in off), "Sign in to your server" heads the buttons.
            title = state.serverTitle?.takeIf { local }?.let { stringResource(R.string.sign_in_heading_server, it) }
                ?: stringResource(R.string.sign_in_heading),
            lift = lift,
            top = Metrics.TopMargin + Metrics.EntryTop,
            methodsLead = if (local) stringResource(R.string.sign_in_or) else null,
            methods = mediaServerButton?.let { way ->
                {
                    PlainButton(
                        text = way.title,
                        onClick = if (way == MediaServerKind.PLEX) onPlex else onMediaServer,
                        enabled = editable,
                        modifier = outOfLocal
                            .widthIn(min = MethodWidth)
                            .focusRequester(viaServer)
                            .testTag(if (way == MediaServerKind.PLEX) "plex" else "media server"),
                    )
                }
            },
            fields = {
                var serverHadFocus by remember { mutableStateOf(false) }
                EntryField(
                    value = server,
                    onValueChange = onServer,
                    placeholder = stringResource(R.string.field_server),
                    modifier = outOfLocal
                        .fillMaxWidth()
                        // Leaving the field (Down, or the keyboard's Next) is when the server is read (M8).
                        .onFocusChanged {
                            if (serverHadFocus && !it.hasFocus) onServerDone()
                            serverHadFocus = it.hasFocus
                        },
                    state = serverField,
                    keyboardType = KeyboardType.Uri,
                    enabled = editable,
                    tag = "server",
                    error = if (SignInProblem.SERVER in state.problems) stringResource(R.string.error_server) else null,
                    onImeAction = {
                        onServerDone()
                        if (local) {
                            if (methods == null) advanceOnOffer = true
                            emailField.focus.requestFocus()
                            emailField.edit()
                        } else if (mediaServerButton != null) {
                            viaServer.requestFocus()
                        }
                    },
                )
                if (state.checking) EntryNote(stringResource(R.string.sign_in_checking), "checking")
                if (local) {
                    EntryField(
                        value = email,
                        onValueChange = onEmail,
                        placeholder = stringResource(R.string.field_email),
                        modifier = inLocal.fillMaxWidth(),
                        state = emailField,
                        keyboardType = KeyboardType.Email,
                        enabled = editable,
                        tag = "email",
                        error = if (SignInProblem.EMAIL in state.problems) stringResource(R.string.error_email) else null,
                        onImeAction = { passwordField.focus.requestFocus(); passwordField.edit() },
                    )
                    EntryField(
                        value = password,
                        onValueChange = onPassword,
                        placeholder = stringResource(R.string.field_password),
                        modifier = inLocal.fillMaxWidth(),
                        state = passwordField,
                        imeAction = ImeAction.Done,
                        password = true,
                        enabled = editable,
                        tag = "password",
                        error = if (SignInProblem.PASSWORD in state.problems) stringResource(R.string.error_password) else null,
                        onImeAction = { button.requestFocus() },
                    )
                }
            },
        ) {
            if (local) {
                ProminentButton(
                    text = stringResource(if (state.submitting) R.string.signing_in else R.string.sign_in),
                    onClick = onSubmit,
                    centred = true,
                    modifier = inLocal.fillMaxWidth().focusRequester(button).testTag("sign in"),
                )
            }
            state.failure?.let { EntryProblem(it.message(methods?.mediaServer), "sign-in error") }
        }
    }
}

/** The least width of a way in's button, so a short name ("Plex") is still a button to aim at. */
private val MethodWidth = 160.dp

/** The form's words for a failure; [server] names the media server in those about it. */
@Composable
internal fun SignInFailure.message(server: MediaServerKind?): String {
    val name = server?.title?.takeIf { it.isNotEmpty() } ?: "media server"
    return when (this) {
        SignInFailure.WRONG_CREDENTIALS -> stringResource(R.string.error_credentials)
        SignInFailure.LOCAL_LOGIN_DISABLED -> stringResource(R.string.error_local_login_off)
        SignInFailure.UNREACHABLE -> stringResource(R.string.error_unreachable)
        SignInFailure.CSRF_NEEDS_HTTPS -> stringResource(R.string.error_csrf)
        SignInFailure.OTHER -> stringResource(R.string.error_sign_in)
        SignInFailure.NOT_IMPORTED ->
            if (server == MediaServerKind.PLEX) stringResource(R.string.error_not_imported_plex) else stringResource(R.string.error_not_imported, name)
        SignInFailure.METHOD_OFF -> stringResource(R.string.error_method_off, name)
        SignInFailure.MEDIA_SERVER_UNREACHABLE -> stringResource(R.string.error_media_server_unreachable, name)
        SignInFailure.CODE_EXPIRED -> stringResource(if (server == MediaServerKind.PLEX) R.string.plex_expired else R.string.quick_connect_expired)
        SignInFailure.PLEX_UNREACHABLE -> stringResource(R.string.plex_unreachable)
        SignInFailure.QUICK_CONNECT_UNAVAILABLE -> stringResource(R.string.quick_connect_unavailable)
        SignInFailure.SESSION_ENDED -> stringResource(R.string.error_session_ended)
    }
}
