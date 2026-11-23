package com.tomdebres.hither.core

import com.tomdebres.hither.core.model.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How often a code is asked after (M8): every [everyMillis] while all goes well; after a failed look the wait doubles
 * for each failure in a row, up to [longestMillis], and [giveUpAfter] failures in a row end the wait with the last
 * failure. A look that answers puts the pace back.
 */
class PollPace(val everyMillis: Long, val longestMillis: Long, val giveUpAfter: Int) {
    /** The wait before the next look, after [failures] failed looks in a row. */
    fun waitMillis(failures: Int): Long {
        var wait = everyMillis
        repeat(failures.coerceAtLeast(0)) { wait = (wait * 2).coerceAtMost(longestMillis) }
        return wait
    }

    companion object {
        /**
         * Every 2 s, as Seerr's own Quick Connect page asks; then 4, 8, 16 and 30 s after failures, and the fifth
         * failure in a row (about a minute without an answer) gives up, as Seerr's page does after five.
         */
        val STANDARD = PollPace(everyMillis = 2_000, longestMillis = 30_000, giveUpAfter = 5)
    }
}

/**
 * A code on screen for a sign-in that someone completes elsewhere: Plex's link code (plex.tv/link) or Jellyfin's Quick
 * Connect code. [code] is what is shown; [lifeSeconds] how long it lasts from when it was made. What Hither asks
 * with (the PIN's id, Quick Connect's secret) stays inside.
 */
class SignInCode internal constructor(
    val code: String,
    val lifeSeconds: Int,
    internal val pinId: Long,
    internal val secret: String,
    internal val deadline: TimeMark,
) {
    /** Never the secret. */
    override fun toString(): String = "SignInCode(code=$code, lifeSeconds=$lifeSeconds)"
}

/**
 * Plex's link-code sign-in (M8): [newCode] asks plex.tv for a code to show with "plex.tv/link", [signIn] waits for the
 * person to enter it there and signs in to Seerr with the token plex.tv then gives (`/auth/plex`). The token goes
 * straight to Seerr and is never returned or kept. Cancelling [signIn]'s coroutine (a Swift task) is Cancel: nothing
 * more is asked of plex.tv.
 *
 * Failures: [SeerrException.PlexUnreachable] (no answer from plex.tv, [PollPace.giveUpAfter] times in a row),
 * [SeerrException.CodeExpired] (the code's life ran out, or plex.tv dropped it), then Seerr's own, as
 * [SeerrClient.signInWithPlex] gives them.
 */
class PlexSignIn(private val plex: PlexTv, private val pace: PollPace, private val time: TimeSource) {
    constructor(plex: PlexTv) : this(plex, PollPace.STANDARD, TimeSource.Monotonic)

    /** A new link code from plex.tv. */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun newCode(): SignInCode {
        val pin = plex.newPin()
        val life = if (pin.expiresIn > 0) pin.expiresIn.seconds else PLEX_CODE_LIFE
        return SignInCode(pin.code, life.inWholeSeconds.toInt(), pin.id, secret = "", deadline = time.markNow() + life)
    }

    /** Waits for [code] to be entered at plex.tv/link, then signs in to [seerr] with what plex.tv gives. */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun signIn(code: SignInCode, seerr: SeerrClient): User = seerr.signInWithPlex(awaitLink(code))

    /**
     * Waits for [code] to be entered at plex.tv/link: what plex.tv then gives, for [SeerrClient.signInWithPlex]. Apart
     * from [signIn] so an app can wait without holding anything else; the token inside never leaves the engine.
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun awaitLink(code: SignInCode): PlexLinked = PlexLinked(token(code))

    /** The Plex token once [code] has been entered; internal so that it never leaves the engine. */
    internal suspend fun token(code: SignInCode): String =
        poll(code, pace) { plex.pin(code.pinId).authToken?.takeIf { it.isNotEmpty() } }

    private companion object {
        /** plex.tv says how long its codes last (`expiresIn`); this is for an answer that does not. */
        val PLEX_CODE_LIFE: Duration = 900.seconds
    }
}

/** A Plex link code entered at plex.tv/link: the token plex.tv gave, held for [SeerrClient.signInWithPlex] and never shown. */
class PlexLinked internal constructor(internal val token: String) {
    override fun toString(): String = "PlexLinked(…)"
}

/**
 * Jellyfin's Quick Connect through Seerr (M8, Seerr 3.4.0+): [newCode] asks Seerr for a code to show, [signIn] waits
 * for the person to approve it in Jellyfin (on a phone or computer, their profile's Quick Connect) and signs in with
 * it. Cancelling [signIn]'s coroutine is Cancel.
 *
 * Failures: [SeerrException.CodeExpired] (Jellyfin dropped the code, or its 10 minutes ran out), the last failure after
 * [PollPace.giveUpAfter] in a row (Seerr not answering), then Seerr's own, as [SeerrClient.finishQuickConnect] gives
 * them.
 */
class QuickConnectSignIn(
    private val seerr: SeerrClient,
    private val pace: PollPace,
    private val time: TimeSource,
    private val lifeMillis: Long,
) {
    constructor(seerr: SeerrClient) : this(seerr, PollPace.STANDARD, TimeSource.Monotonic, JELLYFIN_LIFE_MILLIS)

    /** A new Quick Connect code from Seerr (Jellyfin makes it). */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun newCode(): SignInCode {
        val started = seerr.startQuickConnect()
        return SignInCode(started.code, (lifeMillis / 1000).toInt(), pinId = 0, secret = started.secret, deadline = time.markNow() + lifeMillis.milliseconds)
    }

    /** Waits for [code] to be approved in Jellyfin, then signs in with it: `auth/me`'s user. */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun signIn(code: SignInCode): User {
        awaitApproval(code)
        return seerr.finishQuickConnect(code)
    }

    /**
     * Waits for [code] to be approved in Jellyfin, without signing in: then [SeerrClient.finishQuickConnect] on the
     * client that is to hold the session. Apart from [signIn] so an app can wait without holding anything else.
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun awaitApproval(code: SignInCode) {
        poll(code, pace) { if (seerr.quickConnectApproved(code.secret)) true else null }
    }

    companion object {
        /** Jellyfin forgets a Quick Connect request 10 minutes after it was made (`QuickConnectManager`). */
        const val JELLYFIN_LIFE_MILLIS: Long = 600_000
    }
}

/**
 * Asks [look] after [code] at [pace] until it answers something, the code's life runs out, or the failures give up.
 * [SeerrException.CodeExpired] from [look] ends it at once; any other [SeerrException] counts as a failed look.
 */
private suspend fun <T : Any> poll(code: SignInCode, pace: PollPace, look: suspend () -> T?): T {
    var failures = 0
    while (true) {
        delay(pace.waitMillis(failures))
        if (code.deadline.hasPassedNow()) throw SeerrException.CodeExpired()
        val answer = try {
            look()
        } catch (e: SeerrException.CodeExpired) {
            throw e
        } catch (e: SeerrException) {
            failures++
            if (failures >= pace.giveUpAfter) throw e
            continue
        }
        failures = 0
        if (answer != null) return answer
    }
}
