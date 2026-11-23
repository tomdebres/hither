package com.tomdebres.hither.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tomdebres.hither.core.ServerAddress
import com.tomdebres.hither.data.AccountMethod
import com.tomdebres.hither.core.SignInFailure
import com.tomdebres.hither.core.rules.MediaServerKind
import com.tomdebres.hither.core.rules.SignInProblem
import com.tomdebres.hither.core.rules.SignInRequest
import com.tomdebres.hither.core.rules.checkSignIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Which screen is up. */
sealed interface Screen {
    /** Reading the stored account and asking the server. */
    data object Starting : Screen

    data class SignIn(
        val problems: Set<SignInProblem> = emptySet(),
        val failure: SignInFailure? = null,
        val submitting: Boolean = false,
        /** The server's own title, once it has answered: "Sign in to Seerr". */
        val serverTitle: String? = null,
        /** What the typed server offers (M8), once read; null until then, and the form is the password form. */
        val offer: ServerOffer? = null,
        /** The typed server's offer is being read. */
        val checking: Boolean = false,
        /** A page in front of the form (M8): the Jellyfin or Emby form, or a code. */
        val page: SignInPage? = null,
        /** The control focus comes back to when a page closes (M8): the one that opened it. */
        val returnTo: SignInControl? = null,
    ) : Screen

    /** Signed in: Discover and the pages behind it. */
    data class Home(val session: Session) : Screen {
        /** The name `auth/me` gave, or the email when it gave none. */
        val displayName: String get() = session.user.displayName.ifBlank { session.user.email.orEmpty() }
        val serverTitle: String get() = session.settings.applicationTitle
    }

    /**
     * A stored account whose server is not answering, or ([csrfNeedsHttps]) refuses to sign it in
     * again over http because of its CSRF protection (DECISIONS 70).
     */
    /** A stored account whose server is not answering; [server] is its address, to name. */
    data class Unavailable(
        val retrying: Boolean = false,
        val csrfNeedsHttps: Boolean = false,
        val server: String? = null,
        /** The stored account's way in, for Sign Out's words. */
        val method: AccountMethod = AccountMethod.LOCAL,
    ) : Screen
}

/** The sign-in controls a page opens from (M8), for focus to come back to. */
enum class SignInControl { PLEX, MEDIA_SERVER, QUICK_CONNECT }

/** A page in front of the sign-in form (M8). */
sealed interface SignInPage {
    /** Jellyfin or Emby ([kind]): username and password, and Quick Connect where the server has it. */
    data class MediaServer(
        val kind: MediaServerKind,
        val quickConnect: Boolean,
        val problems: Set<SignInProblem> = emptySet(),
        val failure: SignInFailure? = null,
        val submitting: Boolean = false,
        val returnTo: SignInControl? = null,
    ) : SignInPage

    /**
     * A code to enter or approve elsewhere: Plex's at plex.tv/link, or Jellyfin's Quick Connect. [code] is null until
     * it has come; [approveAt] is where Jellyfin is for people, when the server says.
     */
    data class Code(
        val kind: CodeKind,
        val code: String? = null,
        val state: CodeState = CodeState.STARTING,
        val failure: SignInFailure? = null,
        val approveAt: String? = null,
    ) : SignInPage
}

enum class CodeKind { PLEX, QUICK_CONNECT }

enum class CodeState {
    /** Asking for a code. */
    STARTING,

    /** The code is on screen; waiting for it to be entered or approved. */
    WAITING,

    /** It ran out first: a new code is offered. */
    EXPIRED,

    /** No code could be had (plex.tv unreachable, Quick Connect off): Try Again is offered. */
    FAILED,
}

/**
 * Sign-in, the session and sign-out. The form's text lives here so a rotation or a keyboard
 * coming and going cannot lose it; the password is dropped from memory as soon as it has been
 * used.
 */
class SessionViewModel(private val gateway: SessionGateway) : ViewModel() {
    var screen: Screen by mutableStateOf(Screen.Starting)
        private set
    var server by mutableStateOf("")
        private set
    var email by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set

    /** The Jellyfin or Emby form's fields (M8), apart from Seerr's own. */
    var username by mutableStateOf("")
        private set
    var mediaPassword by mutableStateOf("")
        private set

    /** Reading the typed server's offer, and the address it is for. */
    private var offerJob: Job? = null
    private var offerFor: String? = null

    /** A code's wait: Cancel and Back cancel it. */
    private var codeJob: Job? = null

    /** Typing in a field takes its own complaint away; the others stay until the next attempt. */
    fun onServer(text: String) {
        server = text
        forget(SignInProblem.SERVER)
        // What another address offered no longer applies: its buttons go until the new one is read. A read still out
        // for the old address is dropped too, even when the new text is no address at all.
        val form = screen as? Screen.SignIn ?: return
        val typed = ServerAddress.normalise(text)
        if (form.checking && typed != offerFor) {
            offerJob?.cancel()
            offerFor = null
            screen = form.copy(checking = false)
        }
        val now = screen as Screen.SignIn
        if (now.offer != null && typed != now.offer.baseUrl) screen = now.copy(offer = null)
    }

    fun onUsername(text: String) {
        username = text
        val form = screen as? Screen.SignIn ?: return
        val page = form.page as? SignInPage.MediaServer ?: return
        if (SignInProblem.EMAIL in page.problems) screen = form.copy(page = page.copy(problems = page.problems - SignInProblem.EMAIL))
    }

    fun onMediaPassword(text: String) {
        mediaPassword = text
    }

    /**
     * The server field is done with (its keyboard's Next, or focus moving off it): read what the server offers, once
     * per address (M8). The password form stays as it is meanwhile; the offer adds the other ways in under it.
     */
    fun onServerDone() {
        val form = screen as? Screen.SignIn ?: return
        val baseUrl = ServerAddress.normalise(server) ?: return
        if (form.offer?.baseUrl == baseUrl || (form.checking && offerFor == baseUrl)) return
        offerFor = baseUrl
        offerJob?.cancel()
        screen = form.copy(checking = true)
        offerJob = viewModelScope.launch {
            val offer = try {
                gateway.offer(baseUrl)
            } catch (e: SignInFailed) {
                (screen as? Screen.SignIn)?.let { screen = it.copy(checking = false, offer = null, failure = e.failure) }
                return@launch
            }
            val now = screen as? Screen.SignIn ?: return@launch
            // A failed read's own complaint goes; any other reason (a session that ended) stays to be read. After a session
            // that ended (Plex, Quick Connect), focus goes to the media server's button: the way back in.
            val again = when {
                now.failure != SignInFailure.SESSION_ENDED -> now.returnTo
                offer.methods.plex -> SignInControl.PLEX
                offer.methods.mediaServerPassword -> SignInControl.MEDIA_SERVER
                else -> now.returnTo
            }
            screen = now.copy(
                checking = false, offer = offer, serverTitle = offer.title, returnTo = again,
                failure = now.failure.takeUnless { it == SignInFailure.UNREACHABLE },
            )
        }
    }

    /** Select on the Jellyfin or Emby button: its form. */
    fun openMediaServer() {
        val form = screen as? Screen.SignIn ?: return
        val methods = form.offer?.methods ?: return
        if (form.page != null || !methods.mediaServerPassword) return
        screen = form.copy(page = SignInPage.MediaServer(methods.mediaServer, methods.quickConnect), failure = null)
    }

    /** Select on Sign In on the Jellyfin or Emby form. A second press while the first is out does nothing. */
    fun submitMediaServer() {
        val form = screen as? Screen.SignIn ?: return
        val page = form.page as? SignInPage.MediaServer ?: return
        val offer = form.offer ?: return
        if (page.submitting) return
        // Seerr's own form asks for the username only: a Jellyfin account may have no password.
        if (username.isBlank()) {
            screen = form.copy(page = page.copy(problems = setOf(SignInProblem.EMAIL), failure = null))
            return
        }
        val request = SignInRequest(offer.baseUrl, username.trim(), mediaPassword)
        screen = form.copy(page = page.copy(problems = emptySet(), failure = null, submitting = true))
        viewModelScope.launch {
            try {
                signedIn(gateway.signInWithMediaServer(request))
            } catch (e: SignInFailed) {
                val now = screen as? Screen.SignIn ?: return@launch
                val open = now.page as? SignInPage.MediaServer ?: return@launch
                screen = now.copy(page = open.copy(submitting = false, failure = e.failure))
            }
        }
    }

    /** Select on the Plex button: a link code from plex.tv, and the wait for it to be entered. */
    fun openPlex() {
        val form = screen as? Screen.SignIn ?: return
        if (form.page != null || form.offer?.methods?.plex != true) return
        screen = form.copy(page = SignInPage.Code(CodeKind.PLEX), failure = null)
        startCode()
    }

    /** Select on Quick Connect on the Jellyfin form: a code from Seerr, and the wait for it to be approved. */
    fun openQuickConnect() {
        val form = screen as? Screen.SignIn ?: return
        val page = form.page as? SignInPage.MediaServer ?: return
        if (!page.quickConnect || page.submitting) return
        screen = form.copy(page = SignInPage.Code(CodeKind.QUICK_CONNECT, approveAt = form.offer?.approveAt))
        startCode()
    }

    /** Get a New Code, or Try Again, on a code's page. */
    fun newCode() {
        val page = (screen as? Screen.SignIn)?.page as? SignInPage.Code ?: return
        if (page.state == CodeState.STARTING || page.state == CodeState.WAITING) return
        startCode()
    }

    /**
     * Cancel or Back on a page: a code's wait is cancelled (nothing more is asked of plex.tv or Seerr) and the page
     * closes to where it was opened, focus on the control that opened it. Back on the Jellyfin form while it signs in
     * waits for the answer.
     */
    fun closePage() {
        val form = screen as? Screen.SignIn ?: return
        when (val page = form.page) {
            is SignInPage.Code -> {
                codeJob?.cancel()
                screen = if (page.kind == CodeKind.QUICK_CONNECT) {
                    form.copy(page = mediaServerPage(form, returnTo = SignInControl.QUICK_CONNECT))
                } else {
                    form.copy(page = null, returnTo = SignInControl.PLEX)
                }
            }
            is SignInPage.MediaServer -> if (!page.submitting) screen = form.copy(page = null, returnTo = SignInControl.MEDIA_SERVER)
            null -> Unit
        }
    }

    /**
     * Hither has left the front (Home, another app): a code's wait stops with it, so plex.tv is asked only while its code
     * is on screen (M8, DECISIONS 235). The page closes as Cancel closes it; coming back is a new code.
     */
    fun leftFront() {
        if ((screen as? Screen.SignIn)?.page is SignInPage.Code) closePage()
    }

    private fun mediaServerPage(form: Screen.SignIn, returnTo: SignInControl? = null, failure: SignInFailure? = null): SignInPage.MediaServer {
        val methods = form.offer?.methods
        return SignInPage.MediaServer(methods?.mediaServer ?: MediaServerKind.JELLYFIN, methods?.quickConnect == true, failure = failure, returnTo = returnTo)
    }

    private fun updateCode(change: (SignInPage.Code) -> SignInPage.Code) {
        val form = screen as? Screen.SignIn ?: return
        val page = form.page as? SignInPage.Code ?: return
        screen = form.copy(page = change(page))
    }

    private fun startCode() {
        val form = screen as? Screen.SignIn ?: return
        val page = form.page as? SignInPage.Code ?: return
        val baseUrl = form.offer?.baseUrl ?: return
        codeJob?.cancel()
        screen = form.copy(page = page.copy(code = null, state = CodeState.STARTING, failure = null))
        codeJob = viewModelScope.launch {
            try {
                val code = if (page.kind == CodeKind.PLEX) gateway.newPlexCode() else gateway.newQuickConnectCode(baseUrl)
                updateCode { it.copy(code = code.code, state = CodeState.WAITING) }
                val session = if (page.kind == CodeKind.PLEX) gateway.signInWithPlex(baseUrl, code) else gateway.signInWithQuickConnect(baseUrl, code)
                signedIn(session)
            } catch (e: SignInFailed) {
                when (e.failure) {
                    SignInFailure.CODE_EXPIRED -> updateCode { it.copy(state = CodeState.EXPIRED, failure = e.failure) }
                    SignInFailure.PLEX_UNREACHABLE, SignInFailure.QUICK_CONNECT_UNAVAILABLE, SignInFailure.UNREACHABLE ->
                        updateCode { it.copy(state = CodeState.FAILED, failure = e.failure) }
                    // Seerr's answer about the account (not imported, switched off, …): back where the page was opened,
                    // with the reason, as a refused password is.
                    else -> {
                        val now = screen as? Screen.SignIn ?: return@launch
                        screen = if (page.kind == CodeKind.QUICK_CONNECT) {
                            now.copy(page = mediaServerPage(now, returnTo = SignInControl.QUICK_CONNECT, failure = e.failure))
                        } else {
                            now.copy(page = null, failure = e.failure, returnTo = SignInControl.PLEX)
                        }
                    }
                }
            }
        }
    }

    /** Signed in, by any way: the form's text forgotten, home. */
    private fun signedIn(session: Session) {
        server = ""
        email = ""
        password = ""
        username = ""
        mediaPassword = ""
        screen = home(session)
    }

    fun onEmail(text: String) {
        email = text
        forget(SignInProblem.EMAIL)
    }

    fun onPassword(text: String) {
        password = text
        forget(SignInProblem.PASSWORD)
    }

    private fun forget(problem: SignInProblem) {
        val form = screen as? Screen.SignIn ?: return
        if (problem in form.problems) screen = form.copy(problems = form.problems - problem)
    }

    init {
        restore()
        viewModelScope.launch {
            // The server refused the stored password while a screen was renewing the session:
            // the account is gone, so back to the form with the reason.
            gateway.lost.collect { failure ->
                // A session Hither cannot renew by itself (Plex, Quick Connect) has ended: the form offers its server
                // again, read at once, so signing in again is a button away (M8).
                val was = (screen as? Screen.Home)?.session?.baseUrl
                screen = Screen.SignIn(failure = failure)
                if (failure == SignInFailure.SESSION_ENDED && !was.isNullOrBlank()) offerAgain(was)
            }
        }
        viewModelScope.launch {
            // A renewal the server's CSRF check refused: the account is kept, and the app says why.
            gateway.stuck.collect { screen = Screen.Unavailable(csrfNeedsHttps = true) }
        }
    }

    private fun restore() {
        viewModelScope.launch {
            val restored = gateway.restore()
            screen = when (restored) {
                Restored.NoAccount -> Screen.SignIn()
                is Restored.SignedIn -> home(restored.session)
                Restored.ServerUnavailable -> Screen.Unavailable(server = gateway.storedServer(), method = gateway.storedMethod() ?: AccountMethod.LOCAL)
                Restored.CsrfNeedsHttps -> Screen.Unavailable(csrfNeedsHttps = true)
                is Restored.Rejected -> Screen.SignIn(failure = restored.failure)
            }
            (restored as? Restored.Rejected)?.server?.let { offerAgain(it) }
        }
    }

    /** The form with [baseUrl] in the server field and its offer read, after a session that could not be renewed. */
    private fun offerAgain(baseUrl: String) {
        server = baseUrl
        onServerDone()
    }

    /** Select on "Sign In". A second press while the first is still out does nothing. */
    fun submit() {
        val form = screen as? Screen.SignIn ?: return
        if (form.submitting) return
        val (request, problems) = checkSignIn(server, email, password)
        if (request == null) {
            screen = form.copy(problems = problems, failure = null)
            return
        }
        screen = form.copy(problems = emptySet(), failure = null, submitting = true)
        viewModelScope.launch {
            try {
                signedIn(gateway.signIn(request))
            } catch (e: SignInFailed) {
                // The form as it is now (the server's offer kept), with the reason.
                val now = screen as? Screen.SignIn ?: form
                screen = now.copy(failure = e.failure, submitting = false, problems = emptySet(), serverTitle = e.serverTitle ?: now.serverTitle)
            }
        }
    }

    fun retry() {
        val current = screen as? Screen.Unavailable ?: return
        if (current.retrying) return
        screen = current.copy(retrying = true)
        restore()
    }

    fun signOut() {
        if (screen is Screen.Starting) return
        screen = Screen.Starting
        viewModelScope.launch {
            gateway.signOut()
            screen = Screen.SignIn()
        }
    }

    private fun home(session: Session) = Screen.Home(session)

    class Factory(private val gateway: SessionGateway) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SessionViewModel(gateway) as T
    }
}
