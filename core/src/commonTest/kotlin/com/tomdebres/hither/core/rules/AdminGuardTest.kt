package com.tomdebres.hither.core.rules

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/** The guard on the TV's admin account (DECISIONS 93): the PIN's tries and waits, the idle clock, closing. */
class AdminGuardTest {
    private val t0 = 1_000_000_000_000L
    private val right = "5632"
    private val matches = { pin: String -> pin == right }

    /** [n] wrong PINs in a row, each as soon as it may be tried; the tries and the time after. */
    private fun wrongs(n: Int, start: PinTries = PinTries()): Pair<PinTries, Long> {
        var tries = start
        var now = t0
        repeat(n) {
            now += tries.waitMs(now)
            tries = checkPin(tries, now, "0000", matches).second
        }
        return tries to now
    }

    @Test
    fun theRightPinPassesAndClearsTheWrongOnes() {
        val (tries, now) = wrongs(3)
        val (check, after) = checkPin(tries, now, right, matches)
        assertEquals(PinCheck.Right, check)
        assertEquals(PinTries(), after)
    }

    @Test
    fun theFirstFourWrongPinsCostNothingButTheTry() {
        var tries = PinTries()
        for (n in 1..4) {
            val (check, after) = checkPin(tries, t0, "0000", matches)
            assertEquals(PinCheck.Wrong(left = 10 - n, waitMs = 0), check)
            tries = after
        }
        assertEquals(0L, tries.waitMs(t0))
    }

    @Test
    fun fromTheFifthEachWrongPinMakesALongerWait() {
        val (four, now) = wrongs(4)
        var tries = four
        var at = now
        for ((n, wait) in listOf(5 to 30_000L, 6 to 60_000L, 7 to 300_000L, 8 to 900_000L, 9 to 3_600_000L)) {
            val (check, after) = checkPin(tries, at, "0000", matches)
            assertEquals(PinCheck.Wrong(left = 10 - n, waitMs = wait), check, "the ${n}th wrong PIN")
            tries = after
            at += wait
        }
        assertEquals(30_000L + 60_000 + 300_000 + 900_000 + 3_600_000, at - now, "81 and a half minutes of waits in all")
    }

    @Test
    fun theTenthWrongPinRemovesTheAccount() {
        val (nine, now) = wrongs(9)
        val (check, after) = checkPin(nine, now + nine.waitMs(now), "0000", matches)
        assertEquals(PinCheck.Removed, check)
        assertTrue(after.exhausted)
    }

    @Test
    fun duringAWaitNothingIsCheckedNotEvenTheRightPin() {
        val (five, now) = wrongs(5)
        val (check, after) = checkPin(five, now + 10_000, right, matches)
        assertEquals(PinCheck.Wait(20_000), check)
        assertEquals(five, after, "a wait cannot be raced")
        assertEquals(PinCheck.Right, checkPin(five, now + 30_000, right, matches).first)
    }

    @Test
    fun theWaitSurvivesAsKeptAndAClockSetBackCannotShortenIt() {
        val (five, now) = wrongs(5)
        val kept = five.copy()
        assertEquals(30_000L, kept.waitMs(now))
        assertEquals(30_000L, kept.waitMs(now - 3_600_000), "the clock set back an hour: the whole wait")
        assertEquals(0L, kept.waitMs(now + 30_000))
    }

    @Test
    fun aMalformedPinIsAWrongOne() {
        val (check, _) = checkPin(PinTries(), t0, "56", { true })
        assertEquals(PinCheck.Wrong(left = 9, waitMs = 0), check)
    }

    @Test
    fun theGuardStartsOnTheFamilysAccount() {
        val guard = AdminGuard()
        assertFalse(guard.active, "a new process is the family's")
        assertNull(guard.dueAtMs())
        assertFalse(guard.idle(t0))
    }

    @Test
    fun tenMinutesWithoutAKeyGoBackToTheFamily() {
        val guard = AdminGuard()
        guard.switchedIn(t0)
        assertEquals(t0 + 600_000, guard.dueAtMs())
        assertFalse(guard.idle(t0 + 599_999))
        assertTrue(guard.idle(t0 + 600_000))
    }

    @Test
    fun aKeyPutsTheIdleClockBack() {
        val guard = AdminGuard()
        guard.switchedIn(t0)
        guard.key(t0 + 500_000)
        assertFalse(guard.idle(t0 + 600_000))
        assertTrue(guard.idle(t0 + 1_100_000))
        assertEquals(t0 + 1_100_000, guard.dueAtMs())
    }

    @Test
    fun aClockGoneBackCountsAsIdle() {
        val guard = AdminGuard()
        guard.switchedIn(t0)
        assertTrue(guard.idle(t0 - 1))
    }

    @Test
    fun closingAndSwitchingBackEndIt() {
        val guard = AdminGuard()
        guard.switchedIn(t0)
        guard.closed()
        assertFalse(guard.active)
        guard.key(t0 + 1)
        assertFalse(guard.active, "a key does not bring it back")
        guard.switchedIn(t0 + 2)
        guard.back()
        assertFalse(guard.active)
        assertNull(guard.dueAtMs())
    }
}
