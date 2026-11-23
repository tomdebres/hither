package com.tomdebres.hither.core.rules

import kotlinx.serialization.Serializable

// The guard on the TV's admin account (M5, DECISIONS 74, 91, 93): the PIN's wrong tries and their
// lock-out, and the switch back to the family's account after 10 idle minutes or when Hither closes.

/** Wrong PINs in a row that cost nothing but the try: the first four. From the fifth, each makes a wait. */
const val PIN_FREE_TRIES = 4

/**
 * Wrong PINs in a row after which the admin account is removed from the TV: the PIN can then only be
 * set again by signing in with the approver's password (a forgotten PIN is reset that way too). Ten
 * tries out of 10,000 PINs: a 1-in-1,000 chance for someone guessing, after 81½ minutes of waits.
 */
const val PIN_MAX_WRONG = 10

/** The waits after the 5th to the 9th wrong PIN in a row: 30 seconds, 1, 5, 15 and 60 minutes. */
val PIN_WAITS_MS: List<Long> = listOf(30_000L, 60_000L, 5 * 60_000L, 15 * 60_000L, 60 * 60_000L)

/**
 * The wrong PINs in a row and the wait they have made, as the TV keeps them (sealed with the account,
 * so they survive a restart). [lockedFromMs] is when the wait began, [lockedUntilMs] when it ends.
 */
@Serializable
data class PinTries(val wrong: Int = 0, val lockedFromMs: Long? = null, val lockedUntilMs: Long? = null) {
    /**
     * How long is left to wait before a PIN may be tried at [nowMs], 0 for none. A clock that has gone
     * back past the wait's start gives the whole wait again: setting the TV's clock cannot shorten it.
     */
    fun waitMs(nowMs: Long): Long {
        val from = lockedFromMs ?: return 0
        val until = lockedUntilMs ?: return 0
        return if (nowMs < from) until - from else (until - nowMs).coerceAtLeast(0)
    }

    /** A wrong PIN at [nowMs]: one more in the row, and after the first [PIN_FREE_TRIES] a wait. */
    fun afterWrong(nowMs: Long): PinTries {
        val count = wrong + 1
        val wait = PIN_WAITS_MS.getOrNull(count - PIN_FREE_TRIES - 1)
        return if (wait == null) PinTries(count) else PinTries(count, nowMs, nowMs + wait)
    }

    /** Wrong PINs that may still be tried before the account goes. */
    val left: Int get() = (PIN_MAX_WRONG - wrong).coerceAtLeast(0)

    /** Too many wrong PINs: the account is to be removed. */
    val exhausted: Boolean get() = wrong >= PIN_MAX_WRONG
}

/** What a PIN tried at the switch came to. */
sealed interface PinCheck {
    /** The PIN is right; the wrong tries start again from none. */
    data object Right : PinCheck

    /** Wrong: [left] more may be tried before the account goes; [waitMs] before the next, 0 for at once. */
    data class Wrong(val left: Int, val waitMs: Long) : PinCheck

    /** A wait is on: nothing was tried. */
    data class Wait(val waitMs: Long) : PinCheck

    /** The last wrong PIN: the account has been removed from the TV. */
    data object Removed : PinCheck
}

/**
 * The PIN [pin] tried at [nowMs] against [tries], with [matches] checking it against the kept hash:
 * the check and the tries to keep. While a wait is on nothing is checked, so a wait cannot be raced.
 */
fun checkPin(tries: PinTries, nowMs: Long, pin: String, matches: (String) -> Boolean): Pair<PinCheck, PinTries> {
    val waiting = tries.waitMs(nowMs)
    if (waiting > 0) return PinCheck.Wait(waiting) to tries
    if (isPin(pin) && matches(pin)) return PinCheck.Right to PinTries()
    val after = tries.afterWrong(nowMs)
    return if (after.exhausted) PinCheck.Removed to after else PinCheck.Wrong(after.left, after.waitMs(nowMs)) to after
}

/** Idle minutes after which Hither goes back to the family's account (Tom's, DECISIONS 74). */
const val ADMIN_IDLE_MS: Long = 10 * 60_000L

/**
 * Whether the admin account is active, and when it falls back. The switch makes it active; a key press
 * puts the idle clock back to zero; [ADMIN_IDLE_MS] without one, Hither leaving the front ([closed]) or
 * Switch Back ends it. It lives in memory only, so a process that ends takes it with it: a new one
 * starts on the family's account.
 */
class AdminGuard(private val idleMs: Long = ADMIN_IDLE_MS) {
    var active: Boolean = false
        private set
    private var lastKeyMs: Long = 0

    fun switchedIn(nowMs: Long) {
        active = true
        lastKeyMs = nowMs
    }

    /** A key was pressed at [nowMs]. */
    fun key(nowMs: Long) {
        if (active) lastKeyMs = nowMs
    }

    /** When the idle clock runs out, for a timer; null while not active. */
    fun dueAtMs(): Long? = if (active) lastKeyMs + idleMs else null

    /** Whether the idle clock has run out at [nowMs]: then [back] is due. A clock gone back counts as idle. */
    fun idle(nowMs: Long): Boolean = active && (nowMs - lastKeyMs >= idleMs || nowMs < lastKeyMs)

    /** Hither left the front, or its process is ending. */
    fun closed() {
        active = false
    }

    /** Back to the family's account: Switch Back, the idle clock, or the account removed. */
    fun back() {
        active = false
    }
}
