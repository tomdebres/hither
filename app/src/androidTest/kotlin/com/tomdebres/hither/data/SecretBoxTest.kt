package com.tomdebres.hither.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The Keystore-backed box, on a device: CI compiles this, tv31 runs it. */
@RunWith(AndroidJUnit4::class)
class SecretBoxTest {
    private val box = SecretBox(alias = "hither.test")

    @Test
    fun whatIsSealedOpensAgain() {
        val secret = "p\"a\\ss wörd ☂"
        assertEquals(secret, box.open(box.seal(secret)))
    }

    @Test
    fun theSealedFormIsNotTheSecretAndDiffersEachTime() {
        val a = box.seal("hunter2")
        val b = box.seal("hunter2")
        assertFalse(a.contains("hunter2"))
        assertNotEquals(a, b)
    }

    @Test
    fun whatWillNotOpenReadsAsAbsent() {
        assertNull(box.open(null))
        assertNull(box.open(""))
        assertNull(box.open("not base64 at all !!"))
        val sealed = box.seal("hunter2")
        val tampered = sealed.dropLast(4) + (if (sealed.endsWith("AAAA")) "BBBB" else "AAAA")
        assertNull(box.open(tampered))
        // another key cannot open it either
        assertNull(SecretBox(alias = "hither.test.other").open(sealed))
    }
}
