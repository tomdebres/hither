package com.tomdebres.hither.session

import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.SignInFailure
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.AdminGuard
import com.tomdebres.hither.core.rules.AdminVerdict
import com.tomdebres.hither.core.rules.PinCheck
import com.tomdebres.hither.core.rules.SignInRequest
import com.tomdebres.hither.core.rules.adminSessionDue
import com.tomdebres.hither.core.rules.adminVerdict
import com.tomdebres.hither.core.rules.checkPin
import com.tomdebres.hither.core.rules.isPin
import com.tomdebres.hither.core.rules.mayDecide
import com.tomdebres.hither.core.signInFailure
import com.tomdebres.hither.data.AdminStorage
import com.tomdebres.hither.data.KeptAdmin
import com.tomdebres.hither.data.PersistedCookies
import com.tomdebres.hither.data.PinHash
import com.tomdebres.hither.data.SavedCookie
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What signing in as the would-be admin came to. Nothing is kept until the PIN is set. */
sealed interface AdminSignIn {
    /** Signed in and it may approve ([verdict] APPROVER or ADMINISTRATOR): the PIN comes next. */
    data class Accepted(val verdict: AdminVerdict, val displayName: String) : AdminSignIn

    /** Signed in, and it may not be the admin ([verdict] NO_RIGHT or FAMILY): nothing was kept. */
    data class Refused(val verdict: AdminVerdict) : AdminSignIn

    /** The sign-in itself failed, as the family's would (a wrong password, no answer). */
    data class Failed(val failure: SignInFailure) : AdminSignIn
}

/** The kept admin, as Settings names it. */
data class AdminSummary(val displayName: String)

/** The admin while it is active: who it is, for the mark on every screen. */
data class ActiveAdmin(val userId: Int, val displayName: String)

/** What switching to the admin came to (DECISIONS 93). */
sealed interface AdminSwitch {
    /** Active now. */
    data class In(val admin: ActiveAdmin) : AdminSwitch

    /** The PIN was not taken: wrong, a wait on, or the last wrong one (the account removed). */
    data class Pin(val check: PinCheck) : AdminSwitch

    /** The account's live rights no longer hold Manage Requests: it is kept, and not switched to. */
    data object NoRight : AdminSwitch

    /** The server refused the kept password on signing in again: the account has been removed. */
    data object PasswordRefused : AdminSwitch

    /** The server did not answer as expected; nothing changed, and the account is kept. */
    data object Failed : AdminSwitch

    /** No account is kept. */
    data object NoneKept : AdminSwitch

    /** Hither left the front (or Switch Back came) while the switch was out: it does not take. */
    data object Closed : AdminSwitch
}

/** The admin account, as the screens see it. An interface so the setup and switch are tested without Android. */
interface AdminAccess {
    /** The kept admin, or null. Never throws (cancellation apart). */
    suspend fun kept(): AdminSummary?

    /**
     * Signs in as the would-be admin and reads its rights live from `auth/me` (DECISIONS 92). An
     * account that may not be the admin is signed out again and nothing is kept; one that may is held
     * in memory until [setPin] or [cancel]. Never throws (cancellation apart).
     */
    suspend fun signIn(request: SignInRequest, family: User): AdminSignIn

    /** Keeps the admin signed in by [signIn], with [pin]'s hash; false when nothing was waiting or the store failed. */
    suspend fun setPin(pin: String): Boolean

    /** Drops an admin signed in but not yet kept, and signs it out on the server. */
    suspend fun cancel()

    /** Removes the kept admin: back to the family, its session signed out on the server, everything forgotten. */
    suspend fun remove()

    /** The admin while active, else null: the mark follows it. */
    val active: StateFlow<ActiveAdmin?>

    /**
     * Switches to the admin with [pin] (DECISIONS 93): the PIN checked against its kept hash and the
     * wrong tries; then its session, signed in again from the kept password if it has ended or is past
     * 25 days; then its rights read live. Never throws (cancellation apart).
     */
    suspend fun switchIn(pin: String): AdminSwitch

    /** Back to the family's account, the admin's session kept for next time. */
    fun back()
}

/** A server call made as the active admin. Not a [SeerrAccess]: nothing written for the family can be handed it. */
interface AdminCalls {
    /**
     * Runs [call] with the active admin's client; [SeerrException.SignedOut] when the admin is not
     * active. A session found over is signed in again once from the kept password and the call run
     * once more, as the family's gateway does (38): only after a refusal before any route ran.
     */
    suspend fun <T> asAdmin(call: suspend (SeerrClient) -> T): T
}

/**
 * The TV's admin account (M5): a second [SeerrClient] of its own, with a cookie jar of its own, never
 * the family's. Signing in reads the account's rights live and keeps nothing until the PIN is set
 * (92). Switching asks for the PIN and goes back to the family after [AdminGuard]'s idle minutes, when
 * Hither leaves the front ([closed]), or on [back] (93).
 */
class AdminGateway(
    private val store: AdminStorage,
    private val engine: () -> HttpClientEngine,
    private val clock: () -> Long = System::currentTimeMillis,
    private val hash: (String) -> PinHash = { PinHash.of(it) },
    /** Where the idle clock runs; null runs none (a test drives [idleCheck] itself). */
    private val scope: CoroutineScope? = null,
    private val guard: AdminGuard = AdminGuard(),
) : AdminAccess, AdminCalls {
    private val lock = Mutex()

    /** Signed in and accepted, the PIN not yet set: held in memory only. */
    private class Pending(
        val client: SeerrClient,
        val cookies: AcceptAllCookiesStorage,
        val request: SignInRequest,
        val user: User,
        val signedInAt: Long,
    )

    private var pending: Pending? = null

    /** The active admin's client, while active. */
    private var client: SeerrClient? = null
    private var sessions = 0
    private var idleJob: Job? = null

    /**
     * Counts the times Hither left the front or went back to the family. A switch whose session is still
     * being opened when one comes does not take (the reviewer's Nit on #38: Home pressed after the PIN
     * and before the sign-in answered left the admin active in a closed Hither).
     */
    @Volatile
    private var closings = 0

    private val activeFlow = MutableStateFlow<ActiveAdmin?>(null)
    override val active: StateFlow<ActiveAdmin?> = activeFlow.asStateFlow()

    override suspend fun kept(): AdminSummary? = try {
        store.admin()?.let { AdminSummary(it.displayName) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    override suspend fun signIn(request: SignInRequest, family: User): AdminSignIn = lock.withLock {
        dropPending()
        val cookies = AcceptAllCookiesStorage()
        val client = SeerrClient(engine(), request.baseUrl, cookies, Diagnostics.log)
        val user = try {
            client.signIn(request.email, request.password)
        } catch (e: CancellationException) {
            client.close()
            throw e
        } catch (e: Exception) {
            client.close()
            return@withLock AdminSignIn.Failed(signInFailure(e))
        }
        when (val verdict = adminVerdict(user, family)) {
            AdminVerdict.APPROVER, AdminVerdict.ADMINISTRATOR -> {
                pending = Pending(client, cookies, request, user, clock())
                AdminSignIn.Accepted(verdict, user.displayName.ifBlank { user.email.orEmpty() })
            }
            AdminVerdict.NO_RIGHT, AdminVerdict.FAMILY -> {
                // Nothing kept, and the session just made is ended on the server. For the family's own
                // account that is a session of its own, not the one Hither runs on.
                runCatching { client.signOut() }
                client.close()
                AdminSignIn.Refused(verdict)
            }
        }
    }

    override suspend fun setPin(pin: String): Boolean = lock.withLock {
        val waiting = pending ?: return@withLock false
        if (!isPin(pin)) return@withLock false
        try {
            val hashed = withContext(Dispatchers.Default) { hash(pin) }
            val cookies = waiting.cookies.get(Url(waiting.request.baseUrl)).map(SavedCookie::of)
            store.save(
                KeptAdmin(
                    baseUrl = waiting.request.baseUrl,
                    email = waiting.request.email,
                    password = waiting.request.password,
                    userId = waiting.user.id,
                    displayName = waiting.user.displayName.ifBlank { waiting.user.email.orEmpty() },
                    pin = hashed,
                    cookies = cookies,
                    signedInAt = waiting.signedInAt,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Kept nothing: a half-written record would be worse than asking again.
            runCatching { store.clear() }
            return@withLock false
        }
        pending = null
        waiting.client.close()
        true
    }

    override suspend fun cancel() = lock.withLock { dropPending() }

    override suspend fun remove() = lock.withLock { removeLocked() }

    private suspend fun removeLocked() {
        dropPending()
        endActive()
        val kept = runCatching { store.admin() }.getOrNull()
        if (kept != null && kept.cookies.isNotEmpty()) {
            // Best effort: the session ends on the server too. The TV forgets it either way.
            val cookies = AcceptAllCookiesStorage()
            kept.cookies.forEach { cookies.addCookie(Url(kept.baseUrl), it.toCookie()) }
            val client = SeerrClient(engine(), kept.baseUrl, cookies, Diagnostics.log)
            runCatching { client.signOut() }
            client.close()
        }
        runCatching { store.clear() }
    }

    private suspend fun dropPending() {
        val waiting = pending ?: return
        pending = null
        runCatching { waiting.client.signOut() }
        waiting.client.close()
    }

    override suspend fun switchIn(pin: String): AdminSwitch = lock.withLock {
        val closingsAtStart = closings
        val kept = try {
            store.admin()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@withLock AdminSwitch.NoneKept
        // The PIN first, against the kept tries; slow on purpose, so off the main thread.
        val (check, tries) = withContext(Dispatchers.Default) { checkPin(kept.tries, clock(), pin, kept.pin::matches) }
        if (tries != kept.tries) {
            if (check == PinCheck.Removed) {
                removeLocked()
                return@withLock AdminSwitch.Pin(check)
            }
            try {
                store.save(kept.copy(tries = tries))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A store that will not keep a wrong try must not let the PIN be guessed for free.
                if (check != PinCheck.Right) return@withLock AdminSwitch.Pin(check)
            }
        }
        if (check != PinCheck.Right) return@withLock AdminSwitch.Pin(check)
        // The session: opened from the kept cookies, signed in again from the password when it has ended
        // or is past 25 days (Seerr's last 30, and use does not extend them).
        val seerr = open(kept)
        val user = try {
            if (adminSessionDue(kept.signedInAt, clock())) {
                renewLocked(seerr)
            } else {
                try {
                    seerr.me()
                } catch (_: SeerrException.SignedOut) {
                    renewLocked(seerr)
                }
            }
        } catch (_: PasswordRefused) {
            return@withLock passwordRefused(seerr)
        } catch (e: CancellationException) {
            seerr.close()
            throw e
        } catch (_: Exception) {
            // Not answering, a proxy's refusal, the CSRF lock-out: nothing changed, the account is kept.
            seerr.close()
            return@withLock AdminSwitch.Failed
        }
        // Its rights, read live: an account that has lost Manage Requests is not switched to.
        if (!mayDecide(user)) {
            seerr.close()
            return@withLock AdminSwitch.NoRight
        }
        // Hither closed, or went back to the family, while the session was opened: it does not take.
        if (closings != closingsAtStart) {
            seerr.close()
            return@withLock AdminSwitch.Closed
        }
        client?.close()
        client = seerr
        sessions++
        val admin = ActiveAdmin(user.id, user.displayName.ifBlank { kept.displayName })
        guard.switchedIn(clock())
        activeFlow.value = admin
        startIdleClock()
        AdminSwitch.In(admin)
    }

    private suspend fun passwordRefused(seerr: SeerrClient): AdminSwitch {
        // Only this answer forgets the account, as for the family's (33): the password has been changed.
        seerr.close()
        removeLocked()
        return AdminSwitch.PasswordRefused
    }

    /** A client on the kept session, its cookies written back into the sealed record as the server sets them. */
    private suspend fun open(kept: KeptAdmin): SeerrClient {
        val url = Url(kept.baseUrl)
        return SeerrClient(
            engine(),
            kept.baseUrl,
            PersistedCookies(url, kept.cookies) { cookies ->
                runCatching { store.admin()?.let { store.save(it.copy(cookies = cookies)) } }
            },
            Diagnostics.log,
        )
    }

    /** The server refused the kept password: it has been changed, or the account removed on the server. */
    private class PasswordRefused : Exception()

    /**
     * Signs in again from the kept password; the user, from `auth/me`. [PasswordRefused] for the
     * password refused (only `auth/local`'s refusal means that); anything else as [SeerrClient.signIn] throws it.
     */
    private suspend fun renewLocked(seerr: SeerrClient): User {
        val kept = store.admin() ?: throw SeerrException.SignedOut(null)
        seerr.publicSettings()
        val user = try {
            seerr.signIn(kept.email, kept.password)
        } catch (_: SeerrException.Refused) {
            throw PasswordRefused()
        }
        runCatching { store.admin()?.let { store.save(it.copy(signedInAt = clock())) } }
        return user
    }

    override suspend fun <T> asAdmin(call: suspend (SeerrClient) -> T): T {
        val (seerr, number) = lock.withLock { (client ?: throw SeerrException.SignedOut(null)) to sessions }
        try {
            return call(seerr)
        } catch (_: SeerrException.SignedOut) {
            // Refused before any route ran: sign in again once, below, and ask once more.
        }
        val renewed = lock.withLock {
            val current = client ?: throw SeerrException.SignedOut(null)
            if (current !== seerr || sessions != number) return@withLock current
            try {
                renewLocked(current)
            } catch (_: PasswordRefused) {
                passwordRefused(current)
                throw SeerrException.SignedOut(null)
            }
            sessions++
            current
        }
        return call(renewed)
    }

    override fun back() {
        closings++
        endActive()
    }

    /** A key was pressed: the idle clock starts again. */
    fun key() {
        guard.key(clock())
    }

    /** Hither left the front (or its process is ending): back to the family's account. */
    fun closed() {
        closings++
        guard.closed()
        endActive()
    }

    /** Falls back when the idle clock has run out; true when it did. */
    fun idleCheck(): Boolean {
        if (!guard.idle(clock())) return false
        endActive()
        return true
    }

    private fun startIdleClock() {
        val runner = scope ?: return
        idleJob?.cancel()
        idleJob = runner.launch {
            while (true) {
                val due = guard.dueAtMs() ?: return@launch
                delay((due - clock()).coerceAtLeast(1_000L))
                if (idleCheck()) return@launch
            }
        }
    }

    private fun endActive() {
        guard.back()
        idleJob?.cancel()
        idleJob = null
        activeFlow.value = null
        client?.close()
        client = null
    }
}
