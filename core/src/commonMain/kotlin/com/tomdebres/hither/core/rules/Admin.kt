package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.User

// The TV's admin account (M5, Tom's decision of 2026-11-12, DECISIONS 91): a local Seerr user that
// Tom makes in Seerr's Users page with Manage Requests and nothing else, signed in once with its
// email and password, and switched to behind a 4-digit PIN. The family's own account stays what
// Hither runs as; the admin is only for approving and declining.

/** What an account signed in as the admin comes to, read live from its `auth/me`. */
enum class AdminVerdict {
    /** Manage Requests, and not an administrator: what the admin is meant to be. */
    APPROVER,

    /**
     * Manage Requests through ADMIN (which holds every right). It works, and Hither says once that an
     * account with Manage Requests only is what is intended, then carries on.
     */
    ADMINISTRATOR,

    /** No Manage Requests: it cannot approve, so nothing is kept. */
    NO_RIGHT,

    /** The family's own account (the same user): nothing is kept. */
    FAMILY,
}

/**
 * The verdict on [candidate] as this TV's admin, beside the [family] account Hither runs as. The
 * family check comes first: the family's account is refused even if it could approve.
 */
fun adminVerdict(candidate: User, family: User): AdminVerdict = when {
    candidate.id == family.id -> AdminVerdict.FAMILY
    !Permission.hasAny(candidate.permissions, Permission.MANAGE_REQUESTS) -> AdminVerdict.NO_RIGHT
    (candidate.permissions ?: 0) and Permission.ADMIN != 0 -> AdminVerdict.ADMINISTRATOR
    else -> AdminVerdict.APPROVER
}

/** The kept account may approve and decline now: Manage Requests (or ADMIN), read live, every time. */
fun mayDecide(user: User?): Boolean = user != null && Permission.hasAny(user.permissions, Permission.MANAGE_REQUESTS)

/** How long Seerr's session cookie lasts from sign-in (express-session's `maxAge`; use does not extend it). */
const val SEERR_SESSION_MS: Long = 30L * 24 * 60 * 60 * 1000

/**
 * When the admin's session is signed in again from the kept password before it is used, ahead of
 * Seerr's 30 days: at 25, so a session never runs out in the middle of approving.
 */
const val ADMIN_RENEW_AFTER_MS: Long = 25L * 24 * 60 * 60 * 1000

/**
 * Whether the admin's session, signed in at [signedInAtMs], is to be signed in again before use at
 * [nowMs]: after [ADMIN_RENEW_AFTER_MS], never signed in, or a clock that has gone back past the
 * sign-in (the age can then not be known).
 */
fun adminSessionDue(signedInAtMs: Long?, nowMs: Long): Boolean =
    signedInAtMs == null || nowMs < signedInAtMs || nowMs - signedInAtMs >= ADMIN_RENEW_AFTER_MS

/** The PIN: [PIN_LENGTH] digits, 0 to 9. */
const val PIN_LENGTH = 4

fun isPin(digits: String): Boolean = digits.length == PIN_LENGTH && digits.all { it in '0'..'9' }

/** The digits typed into a PIN pad so far: a digit adds one (up to [PIN_LENGTH]), delete takes the last off. */
data class PinEntry(val digits: String = "") {
    init {
        require(digits.length <= PIN_LENGTH && digits.all { it in '0'..'9' })
    }

    val full: Boolean get() = digits.length == PIN_LENGTH

    fun type(digit: Int): PinEntry = if (full || digit !in 0..9) this else PinEntry(digits + digit)

    fun delete(): PinEntry = PinEntry(digits.dropLast(1))

    /** Never the digits: a PinEntry can end up in a log line or a crash report by accident. */
    override fun toString(): String = "PinEntry(${digits.length} of $PIN_LENGTH)"
}

/** Setting the PIN: chosen, then entered again; the two must match. */
sealed interface PinSetting {
    /** The first entry. [mismatched]: the last two entries differed, and this is the start again. */
    data class Choose(val mismatched: Boolean = false) : PinSetting

    /** The second entry, against the first. */
    class Confirm(internal val first: String) : PinSetting {
        override fun toString(): String = "Confirm"
    }

    /** Both entries matched: [pin] is to be hashed and kept, and then forgotten. */
    class Set(val pin: String) : PinSetting {
        override fun toString(): String = "Set"
    }
}

/** The next step of setting the PIN after a full [entry] at [state]. */
fun pinSetting(state: PinSetting, entry: String): PinSetting {
    require(isPin(entry))
    return when (state) {
        is PinSetting.Choose -> PinSetting.Confirm(entry)
        is PinSetting.Confirm -> if (state.first == entry) PinSetting.Set(entry) else PinSetting.Choose(mismatched = true)
        is PinSetting.Set -> state
    }
}
