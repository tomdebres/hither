package com.tomdebres.hither.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The admin's PIN as kept (DECISIONS 92): a salted PBKDF2 hash, never the digits. */
class PinHashTest {
    @Test
    fun theHashMatchesItsPinAndNoOther() {
        val kept = PinHash.of("4821", iterations = 1_000)
        assertTrue(kept.matches("4821"))
        for (other in listOf("4820", "1284", "0000", "48210", "")) assertFalse(other, kept.matches(other))
    }

    @Test
    fun eachHashHasASaltOfItsOwn() {
        val one = PinHash.of("4821", iterations = 1_000)
        val two = PinHash.of("4821", iterations = 1_000)
        assertNotEquals(one.salt, two.salt)
        assertNotEquals("the same PIN never hashes the same twice", one.hash, two.hash)
        assertEquals(16, java.util.Base64.getDecoder().decode(one.salt).size)
    }

    @Test
    fun itIsSlowOnPurposeAndKeepsItsRounds() {
        assertTrue(PinHash.ITERATIONS >= 50_000)
        val kept = PinHash.of("4821")
        assertEquals(PinHash.ITERATIONS, kept.iterations)
        assertTrue("checked with the rounds it was made with", PinHash(kept.salt, kept.iterations, kept.hash).matches("4821"))
    }

    @Test
    fun neitherTheDigitsNorTheHashAreInItsText() {
        val kept = PinHash.of("4821", iterations = 1_000)
        assertFalse(kept.toString().contains("4821"))
        assertFalse(kept.toString().contains(kept.hash))
        assertFalse("not in the stored form either", kotlinx.serialization.json.Json.encodeToString(PinHash.serializer(), kept).contains("4821"))
    }
}
