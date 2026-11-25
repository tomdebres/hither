package com.tomdebres.hither.session

import com.tomdebres.hither.core.rules.ImageSource
import com.tomdebres.hither.core.PlexSignIn
import com.tomdebres.hither.core.PlexTv
import com.tomdebres.hither.core.PollPace
import com.tomdebres.hither.core.QuickConnectSignIn
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.SignInCode
import com.tomdebres.hither.core.SignInFailure
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.SignInMethods
import com.tomdebres.hither.core.rules.SignInRequest
import com.tomdebres.hither.core.rules.quickConnectPlace
import com.tomdebres.hither.core.rules.signInMethods
import com.tomdebres.hither.core.rules.wantsSeerrVersion
import com.tomdebres.hither.core.signInFailure
import com.tomdebres.hither.data.Account
import com.tomdebres.hither.data.AccountMethod
import com.tomdebres.hither.data.AccountStorage
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import kotlin.time.TimeSource
import com.tomdebres.hither.data.PersistedCookies
import com.tomdebres.hither.data.SavedCookie
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A signed-in user on a server that has answered, at [baseUrl]. */
data class Session(
    val user: User,
    val settings: PublicSettings,
    val baseUrl: String = "",
    /** How the account signed in: what signing in again would take, as Sign Out says (H1, as the Apple TV app). */
    val method: AccountMethod = AccountMethod.LOCAL,
)

/** What starting the app with a stored account came to. */
sealed interface Restored {
    /** First run, or signed out. */
    data object NoAccount : Restored

    data class SignedIn(val session: Session) : Restored

    /** The account is kept; the server did not answer just now. */
    data object ServerUnavailable : Restored

    /**
     * The account is kept; the server's CSRF protection refused signing in again over http, which
     * it always will there (DECISIONS 70). Not a refused password.
     */
    data object CsrfNeedsHttps : Restored

    /**
     * The server no longer takes the stored sign-in; the account has been cleared. [server] is its address where the
     * form should offer it again: a session Hither could not renew by itself (Plex, Quick Connect: SESSION_ENDED).
     */
    data class Rejected(val failure: SignInFailure, val server: String? = null) : Restored
}

/** A sign-in that failed, with the server's title when it got far enough to learn it. */
class SignInFailed(val failure: SignInFailure, val serverTitle: String?) : Exception(failure.name)

/**
 * What a server offers for signing in (M8), read before any sign-in: its address as called, its title, the ways in
 * ([SignInMethods]: only those are offered) and, for Quick Connect, where its Jellyfin is for people (or null).
 */
data class ServerOffer(val baseUrl: String, val title: String, val methods: SignInMethods, val approveAt: String? = null)

/** A code on screen (M8), and what the gateway waits on with it; [code] is all a screen sees. */
class PendingCode(val code: String, internal val inner: SignInCode? = null) {
    override fun toString(): String = "PendingCode($code)"
}

/** The session, as the screens see it. An interface so the view model is tested without Android. */
interface SessionGateway {
    /** What the stored account comes to. Never throws (cancellation apart). */
    suspend fun restore(): Restored

    /**
     * Signs in and stores the account, or throws [SignInFailed] and stores nothing. It throws
     * nothing else (cancellation apart), so a caller that handles [SignInFailed] has handled it.
     */
    suspend fun signIn(request: SignInRequest): Session

    suspend fun signOut()

    /**
     * The session ending under a signed-in screen: the server refused the stored password when
     * a call found the session over and the account has been cleared. The reason, once.
     */
    val lost: Flow<SignInFailure>

    /**
     * The session ended under a signed-in screen and could not be renewed for a reason that is
     * not the password: the server's CSRF protection refuses sign-in over http. The account is
     * kept; the app says why (DECISIONS 70).
     */
    val stuck: Flow<SignInFailure>

    /**
     * The stored account's server address, to name on "the server is not answering" (the polish
     * audit's item 28); null with no account or a store that will not open. Never the password.
     */
    suspend fun storedServer(): String? = null

    /** The stored account's way in, for Sign Out's words on the same screen; null with no account. */
    suspend fun storedMethod(): AccountMethod? = null

    // ---- M8: the ways in a server offers, and those through its media server ---------------------------------
    // Each throws [SignInFailed] and nothing else (cancellation apart); each sign-in stores the account only on success,
    // as [signIn] does. The defaults refuse, for gateways that predate them.

    /** What [baseUrl]'s server offers: `settings/public`, and `status` for a Jellyfin server's Quick Connect. */
    suspend fun offer(baseUrl: String): ServerOffer = throw SignInFailed(SignInFailure.OTHER, null)

    /** A Jellyfin or Emby username ([SignInRequest.email]) and password, through Seerr's `/auth/jellyfin`. */
    suspend fun signInWithMediaServer(request: SignInRequest): Session = throw SignInFailed(SignInFailure.OTHER, null)

    /** A Plex link code from plex.tv: the only moment, with [signInWithPlex], that Hither calls plex.tv. */
    suspend fun newPlexCode(): PendingCode = throw SignInFailed(SignInFailure.PLEX_UNREACHABLE, null)

    /** Waits for [code] to be entered at plex.tv/link, then signs in to [baseUrl]'s Seerr with Plex. Cancel by cancelling. */
    suspend fun signInWithPlex(baseUrl: String, code: PendingCode): Session = throw SignInFailed(SignInFailure.OTHER, null)

    /** A Quick Connect code from [baseUrl]'s Seerr. */
    suspend fun newQuickConnectCode(baseUrl: String): PendingCode = throw SignInFailed(SignInFailure.QUICK_CONNECT_UNAVAILABLE, null)

    /** Waits for [code] to be approved in Jellyfin, then signs in with it. Cancel by cancelling. */
    suspend fun signInWithQuickConnect(baseUrl: String, code: PendingCode): Session = throw SignInFailed(SignInFailure.OTHER, null)
}

/** How a screen asks the server something, through the one signed-in client. */
interface SeerrAccess {
    /**
     * Runs [call] with the signed-in server's client. When the server answers
     * [SeerrException.SignedOut] (a 401, or Seerr's own 403 for a cookie it no longer knows:
     * the session has ended, and the call was refused before anything was done), signs in
     * again once with the stored account and runs [call] one more time. Every other failure,
     * any other 403 included, is thrown as it came, without a second try: a call that timed
     * out may have been carried out, and a request must never be sent twice (REVIEW.md).
     */
    suspend fun <T> call(call: suspend (SeerrClient) -> T): T
}

/**
 * The real gateway: one [SeerrClient] for the one server in Settings, on the engine [engine]
 * makes, with its cookies kept in [store]. [onAccountGone] runs whenever the stored account is
 * cleared (sign-out, a refused password, a new sign-in), so nothing of it outlives it on the device
 * or the TV's home screen (M4, DECISIONS 76).
 */
class SeerrSessionGateway(
    private val store: AccountStorage,
    private val onAccountGone: suspend () -> Unit = {},
    /** plex.tv for a Plex sign-in (M8): made when a code is asked for, closed after; null where Plex is not wired. */
    private val plexTv: (suspend () -> PlexTv)? = null,
    /** How often a code is asked after (tests pass a quicker one). */
    private val pace: PollPace = PollPace.STANDARD,
    /**
     * TMDB's image server, for a server that keeps no copies of its pictures (DECISIONS 382): TMDB itself in a release
     * build (`TmdbAddress`); a debug build's test may name fake-seerr's stand-in. Read each time a client is opened.
     */
    private val tmdbImages: () -> String = { ImageSource.TMDB_IMAGES },
    private val engine: () -> HttpClientEngine,
) : SessionGateway, SeerrAccess {
    private val lock = Mutex()
    private var client: SeerrClient? = null

    /** Counts the sessions [client] has held, so calls that found the same one over renew it once. */
    private var session = 0
    private val lostChannel = Channel<SignInFailure>(Channel.CONFLATED)
    override val lost: Flow<SignInFailure> = lostChannel.receiveAsFlow()
    private val stuckChannel = Channel<SignInFailure>(Channel.CONFLATED)
    override val stuck: Flow<SignInFailure> = stuckChannel.receiveAsFlow()

    override suspend fun <T> call(call: suspend (SeerrClient) -> T): T {
        val (seerr, number) = lock.withLock { (client ?: throw SeerrException.SignedOut(null)) to session }
        try {
            return call(seerr)
        } catch (_: SeerrException.SignedOut) {
            // A 401 or Seerr's own 403: refused before any route ran, so nothing was done.
            // Renew the session and ask once more, below.
        }
        return call(renew(seerr, number))
    }

    /**
     * Signs in again with the stored account, once for all the calls that found session
     * [number] over at the same moment (a screen of four rows sends four at once): the first
     * one in signs in, the rest find the session already renewed and go straight on.
     */
    private suspend fun renew(seen: SeerrClient, number: Int): SeerrClient = lock.withLock {
        val current = client ?: throw SeerrException.SignedOut(null)
        // Signed in afresh, or renewed by another call, while this one was out: go on with that.
        if (current !== seen || session != number) return@withLock current
        val account = store.account() ?: throw SeerrException.SignedOut(null)
        try {
            signInAgain(current, account)
        } catch (e: AccountGone) {
            // The password has been changed or the account removed (or, M8, a session Hither cannot renew has ended):
            // the same rule as at launch, and the app goes to sign-in.
            drop()
            runCatching { store.clear() }
            runCatching { onAccountGone() }
            lostChannel.trySend(e.failure)
            throw SeerrException.SignedOut(e.message)
        } catch (e: SeerrException.CsrfRefused) {
            // Not a refused password: the account stays (DECISIONS 70). The app says why.
            if (e.overHttp) stuckChannel.trySend(SignInFailure.CSRF_NEEDS_HTTPS)
            throw e
        }
        session++
        current
    }

    /** The stored account will not sign in again: Seerr refused it, or (Plex, Quick Connect) there is nothing to sign in with. */
    private class AccountGone(val failure: SignInFailure, message: String?) : Exception(message)

    /**
     * Signs [account] in again on [seerr] when the session has ended, as it first signed in. Only Seerr's own word
     * about the account forgets it ([AccountGone]): its password refused (any way in), and since M8 a media-server
     * account it no longer imports or a way in switched off. A Plex or Quick Connect account has nothing kept to sign
     * in with, so its session's end is the account's (DECISIONS 235). Anything else is thrown as it came.
     */
    private suspend fun signInAgain(seerr: SeerrClient, account: Account): User {
        if (!account.method.renews) throw AccountGone(SignInFailure.SESSION_ENDED, null)
        return try {
            if (account.method == AccountMethod.MEDIA_SERVER) seerr.signInWithMediaServer(account.email, account.password)
            else seerr.signIn(account.email, account.password)
        } catch (e: SeerrException.Refused) {
            throw AccountGone(signInFailure(e), e.serverMessage)
        } catch (e: SeerrException.NotImported) {
            throw AccountGone(signInFailure(e), e.serverMessage)
        } catch (e: SeerrException.SignInOff) {
            throw AccountGone(signInFailure(e), e.serverMessage)
        }
    }

    private fun open(baseUrl: String, cookies: List<SavedCookie>): SeerrClient {
        client?.close()
        return SeerrClient(engine(), baseUrl, PersistedCookies(Url(baseUrl), cookies, store::saveCookies), Diagnostics.log, tmdbImages())
            .also { client = it }
    }

    private fun drop() {
        client?.close()
        client = null
    }

    override suspend fun restore(): Restored = lock.withLock { restoreLocked() }

    override suspend fun storedServer(): String? = try {
        store.account()?.baseUrl
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    override suspend fun storedMethod(): AccountMethod? = try {
        store.account()?.method
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * For the background refresh: true when there is a signed-in client to call, opening the stored
     * account's session first if the app has not (a process started for the refresh alone). A client
     * the app already holds is used as it is, never replaced under a screen that is using it.
     */
    suspend fun resume(): Boolean = lock.withLock {
        client != null || restoreLocked() is Restored.SignedIn
    }

    private suspend fun restoreLocked(): Restored {
        return try {
            val account = store.account() ?: return Restored.NoAccount
            val seerr = open(account.baseUrl, store.cookies())
            val settings = seerr.publicSettings()
            val user = try {
                seerr.me()
            } catch (_: SeerrException.SignedOut) {
                // The session has ended since last time (a 401), or the server has forgotten it
                // (Seerr's own 403): sign in again with the stored account. Any other 403 here
                // is not this, and goes to the try-again screen below with the account kept.
                try {
                    signInAgain(seerr, account)
                } catch (e: AccountGone) {
                    // Only Seerr's own refusal is about the account (SeerrClient.signIn reports a
                    // 403 on its own follow-up call as something else). The account is
                    // forgotten for it and for nothing else: a 403 from a proxy in front of
                    // the server must not cost the user their typed-in sign-in. A session Hither
                    // could not renew (Plex, Quick Connect) offers the server again in the form.
                    drop()
                    store.clear()
                    runCatching { onAccountGone() }
                    return Restored.Rejected(e.failure, server = account.baseUrl.takeIf { e.failure == SignInFailure.SESSION_ENDED })
                }
            }
            Restored.SignedIn(Session(user, settings, account.baseUrl, account.method))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SeerrException.CsrfRefused) {
            // Signing in again was refused by the server's CSRF check: the account is kept, and the
            // screen says why rather than "not answering" (DECISIONS 70).
            if (e.overHttp) Restored.CsrfNeedsHttps else Restored.ServerUnavailable
        } catch (_: Exception) {
            // The server not answering as expected, or the store not opening: the account, if
            // there is one, is kept, and the screen offers Try Again and Sign Out.
            Restored.ServerUnavailable
        }
    }

    override suspend fun signIn(request: SignInRequest): Session = lock.withLock {
        signInLocked(request.baseUrl, Account(request.baseUrl, request.email, request.password), localOff) {
            it.signIn(request.email, request.password)
        }
    }

    /**
     * One sign-in, whichever the way in: the account and everything of it cleared first, the server's settings read
     * (is [allowed] on?), [call] signs in on a new client, and [account] is stored only once it has. Whatever goes
     * wrong, the server's refusal or the device's own store failing after the server said yes, nothing half-stored is
     * left, and the screen gets a [SignInFailed] rather than an exception nobody catches.
     */
    private suspend fun signInLocked(
        baseUrl: String,
        account: Account,
        allowed: (PublicSettings) -> SignInFailure?,
        call: suspend (SeerrClient) -> User,
    ): Session {
        var title: String? = null
        try {
            // Inside the try with everything else: a store that will not open is a failed
            // sign-in too, and it fails before anything has been sent.
            store.clear()
            runCatching { onAccountGone() }
            val seerr = open(baseUrl, emptyList())
            val settings = seerr.publicSettings()
            title = settings.applicationTitle
            allowed(settings)?.let { throw SignInFailed(it, title) }
            val user = call(seerr)
            store.save(account)
            return Session(user, settings, baseUrl, account.method)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            drop()
            runCatching { store.clear() }
            throw e as? SignInFailed ?: SignInFailed(signInFailure(e), title)
        }
    }

    private val localOff: (PublicSettings) -> SignInFailure? = { if (it.localLogin) null else SignInFailure.LOCAL_LOGIN_DISABLED }
    private val mediaServerOff: (PublicSettings) -> SignInFailure? = { if (it.mediaServerLogin) null else SignInFailure.METHOD_OFF }

    // ---- M8 ----------------------------------------------------------------------------------------------------

    /** A client of its own for reading a server before signing in, or for Quick Connect's waiting: no session, no store. */
    private fun probe(baseUrl: String) = SeerrClient(engine(), baseUrl, AcceptAllCookiesStorage(), Diagnostics.log)

    /** Runs [block], turning every failure but cancellation into a [SignInFailed]. */
    private suspend fun <T> failing(title: String? = null, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SignInFailed) {
        throw e
    } catch (e: Exception) {
        throw SignInFailed(signInFailure(e), title)
    }

    override suspend fun offer(baseUrl: String): ServerOffer {
        val seerr = probe(baseUrl)
        try {
            return failing {
                val settings = seerr.publicSettings()
                // Read only where it matters (Quick Connect); a server that will not say gets username and password.
                val version = if (wantsSeerrVersion(settings)) versionOf(seerr) else null
                ServerOffer(baseUrl, settings.applicationTitle, signInMethods(settings, version), quickConnectPlace(settings))
            }
        } finally {
            seerr.close()
        }
    }

    /** The server's Seerr version, or null when it will not say: then Quick Connect is not offered (DECISIONS 234). */
    private suspend fun versionOf(seerr: SeerrClient): String? = try {
        seerr.status().version
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    override suspend fun signInWithMediaServer(request: SignInRequest): Session = lock.withLock {
        signInLocked(request.baseUrl, Account(request.baseUrl, request.email, request.password, AccountMethod.MEDIA_SERVER), mediaServerOff) {
            it.signInWithMediaServer(request.email, request.password)
        }
    }

    private suspend fun <T> withPlex(block: suspend (PlexSignIn) -> T): T {
        val plex = failing { plexTv?.invoke() } ?: throw SignInFailed(SignInFailure.PLEX_UNREACHABLE, null)
        try {
            return failing { block(PlexSignIn(plex, pace, TimeSource.Monotonic)) }
        } finally {
            plex.close()
        }
    }

    override suspend fun newPlexCode(): PendingCode = withPlex { plex -> plex.newCode().let { PendingCode(it.code, it) } }

    override suspend fun signInWithPlex(baseUrl: String, code: PendingCode): Session {
        val inner = code.inner ?: throw SignInFailed(SignInFailure.OTHER, null)
        // The wait is outside the lock: it may last the code's 15 minutes, and nothing else needs to wait on it.
        val linked = withPlex { it.awaitLink(inner) }
        return lock.withLock {
            signInLocked(baseUrl, Account(baseUrl, email = "", password = "", method = AccountMethod.PLEX), mediaServerOff) {
                it.signInWithPlex(linked)
            }
        }
    }

    override suspend fun newQuickConnectCode(baseUrl: String): PendingCode {
        val seerr = probe(baseUrl)
        try {
            return failing {
                QuickConnectSignIn(seerr, pace, TimeSource.Monotonic, QuickConnectSignIn.JELLYFIN_LIFE_MILLIS).newCode().let { PendingCode(it.code, it) }
            }
        } finally {
            seerr.close()
        }
    }

    override suspend fun signInWithQuickConnect(baseUrl: String, code: PendingCode): Session {
        val inner = code.inner ?: throw SignInFailed(SignInFailure.OTHER, null)
        // The wait (up to Jellyfin's 10 minutes) on a client of its own, outside the lock; the session on the gateway's.
        val seerr = probe(baseUrl)
        try {
            failing { QuickConnectSignIn(seerr, pace, TimeSource.Monotonic, QuickConnectSignIn.JELLYFIN_LIFE_MILLIS).awaitApproval(inner) }
        } finally {
            seerr.close()
        }
        return lock.withLock {
            signInLocked(baseUrl, Account(baseUrl, email = "", password = "", method = AccountMethod.QUICK_CONNECT), mediaServerOff) {
                it.finishQuickConnect(inner)
            }
        }
    }

    override suspend fun signOut() = lock.withLock {
        // Best effort on the server; the device forgets the account either way.
        runCatching { client?.signOut() }
        drop()
        store.clear()
        runCatching { onAccountGone() }
        Unit
    }
}
