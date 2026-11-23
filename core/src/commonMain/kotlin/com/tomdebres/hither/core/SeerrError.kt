package com.tomdebres.hither.core

/**
 * Why a call to the server did not give what was asked for. Messages are the server's own
 * words where it sent any; they never include a credential or a cookie.
 */
sealed class SeerrException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No answer: wrong address, server down, no route, TLS trouble, a timeout. */
    class Unreachable(cause: Throwable) : SeerrException("The server could not be reached.", cause)

    /** Something answered, but not with Seerr's JSON: a proxy's login page, another service. */
    class NotSeerr(cause: Throwable? = null) : SeerrException("The address did not answer as a Seerr server.", cause)

    /**
     * The session is over and nothing was done: a 401 (no session cookie sent: it expired on
     * the device), or Seerr's own 403 for a cookie it no longer knows (its sessions lost).
     * Sign in again.
     */
    class SignedOut(serverMessage: String?) : SeerrException(serverMessage ?: "Not signed in.")

    /**
     * 403, any but Seerr's own word that the session is unknown: the server, or something in
     * front of it, refused. On sign-in that is a wrong email or password.
     */
    class Refused(val serverMessage: String?) : SeerrException(serverMessage ?: "The server refused the request.") {
        /**
         * Seerr's own refusal of a right it guards, told apart through `auth/me` (the session is alive: DECISIONS 94,
         * 97); anything else refused (a proxy's page, other JSON) is not about the account's rights (polish 2, 132).
         */
        val lacksRight: Boolean get() = serverMessage == SEERR_NO_PERMISSION
    }

    /**
     * 403 from Seerr's CSRF check (csurf's `{"message":"invalid csrf token"}`): the server has CSRF
     * protection on and its token did not come back. Over plain http it never does: Seerr sets its
     * CSRF cookies Secure outside development, and a client (Ktor, like a browser) never sends a
     * Secure cookie over http. Not a refused password and not an ended session: nothing was done,
     * and nothing sent again will pass (DECISIONS 70). [overHttp]: the server is reached over http,
     * which is the cause; over https it is something else.
     */
    class CsrfRefused(val overHttp: Boolean) : SeerrException("The server's CSRF check refused the request.")

    /** 409 from approve or decline: the request is no longer pending (someone else decided it). */
    class NotPending(serverMessage: String?) : SeerrException(serverMessage ?: "The request is no longer pending.")

    /** 409: the title has already been requested. */
    class AlreadyRequested(serverMessage: String?) : SeerrException(serverMessage ?: "Already requested.")

    /**
     * 202 from `POST /request`: accepted and nothing done, because every season asked for is
     * already requested or available. Not an error on the wire, and not a request either.
     */
    class NothingToRequest(serverMessage: String?) : SeerrException(serverMessage ?: "Nothing left to request.")

    /** Any other status. */
    class Http(val status: Int, val serverMessage: String?) :
        SeerrException(serverMessage ?: "The server answered $status.")

    // ---- M8: signing in through the media server ---------------------------------------------------

    /**
     * Seerr's 403 "Access denied." to a media-server account (`/auth/plex`, `/auth/jellyfin`, Quick Connect): the
     * account is not one of Seerr's users and Seerr may not make it one (its "new sign-in" switch is off), or, for
     * Plex, it has no access to the server's Plex.
     */
    class NotImported(val serverMessage: String?) : SeerrException(serverMessage ?: "Access denied.")

    /**
     * The server has this way of signing in switched off: Seerr's "Plex login is disabled", "Jellyfin login is
     * disabled", or Quick Connect on a server that is not Jellyfin's.
     */
    class SignInOff(val serverMessage: String?) : SeerrException(serverMessage ?: "This sign-in is switched off.")

    /** Seerr could not reach its Jellyfin or Emby to check the account (its CONNECTION_ERROR or INVALID_URL). */
    class MediaServerUnreachable(val serverMessage: String?) :
        SeerrException(serverMessage ?: "The server could not reach its media server.")

    /** A Plex link code or a Quick Connect code ran out before it was entered or approved. */
    class CodeExpired : SeerrException("The code expired before it was used.")

    /** plex.tv did not answer, or not as plex.tv answers: nothing was signed in. */
    class PlexUnreachable(cause: Throwable? = null) : SeerrException("plex.tv could not be reached.", cause)
}

/** What went wrong at sign-in, for the screen to word as Seerr's own sign-in form does. */
enum class SignInFailure {
    /** 403: "The email address or password is incorrect." */
    WRONG_CREDENTIALS,

    /** The server has password sign-in switched off (`localLogin` false, or it said so). */
    LOCAL_LOGIN_DISABLED,

    /** No Seerr server answered at the address. */
    UNREACHABLE,

    /** The server's CSRF protection is on and it is reached over http, where it cannot work. */
    CSRF_NEEDS_HTTPS,

    /** Anything else: "Signing in did not work. Try again." */
    OTHER,

    // ---- M8 ----

    /** A media-server account Seerr refused: not imported (or, for Plex, no access to the server's Plex). */
    NOT_IMPORTED,

    /** The server has this way of signing in switched off. */
    METHOD_OFF,

    /** Seerr could not reach its Jellyfin or Emby. */
    MEDIA_SERVER_UNREACHABLE,

    /** The Plex or Quick Connect code ran out before it was entered or approved. */
    CODE_EXPIRED,

    /** plex.tv did not answer, or not as expected. */
    PLEX_UNREACHABLE,

    /** Quick Connect could not start: switched off in Jellyfin, or Seerr could not reach Jellyfin. */
    QUICK_CONNECT_UNAVAILABLE,

    /**
     * The session of an account Hither cannot sign in again by itself (Plex, Quick Connect: no password is kept)
     * has ended; the person signs in again.
     */
    SESSION_ENDED,
}

/** The sign-in screen's reading of a failure from [SeerrClient.signIn]. */
fun signInFailure(error: Throwable): SignInFailure = when (error) {
    is SeerrException.Refused -> SignInFailure.WRONG_CREDENTIALS
    is SeerrException.CsrfRefused -> if (error.overHttp) SignInFailure.CSRF_NEEDS_HTTPS else SignInFailure.OTHER
    is SeerrException.Unreachable, is SeerrException.NotSeerr -> SignInFailure.UNREACHABLE
    is SeerrException.NotImported -> SignInFailure.NOT_IMPORTED
    is SeerrException.SignInOff -> SignInFailure.METHOD_OFF
    is SeerrException.MediaServerUnreachable -> SignInFailure.MEDIA_SERVER_UNREACHABLE
    is SeerrException.CodeExpired -> SignInFailure.CODE_EXPIRED
    is SeerrException.PlexUnreachable -> SignInFailure.PLEX_UNREACHABLE
    is SeerrException.Http -> when {
        error.serverMessage == QUICK_CONNECT_NOT_STARTED -> SignInFailure.QUICK_CONNECT_UNAVAILABLE
        error.serverMessage?.contains("disabled", ignoreCase = true) == true -> SignInFailure.LOCAL_LOGIN_DISABLED
        else -> SignInFailure.OTHER
    }
    else -> SignInFailure.OTHER
}

/** Seerr's 500 when Jellyfin would not start a Quick Connect (switched off there, or not reached). */
internal const val QUICK_CONNECT_NOT_STARTED = "Failed to initiate quick connect."

/** The body of Seerr's 403 for a session it does not know and for a right the account lacks (`isAuthenticated`). */
internal const val SEERR_NO_PERMISSION = "You do not have permission to access this endpoint"
