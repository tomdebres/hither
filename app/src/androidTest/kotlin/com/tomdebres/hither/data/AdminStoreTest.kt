package com.tomdebres.hither.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * Where the TV's admin account is kept (DECISIONS 92), on a device's real DataStore and Keystore: one
 * sealed value, nothing of it in the clear on disk, read back whole, and Remove takes the record and
 * its key; a record whose key is gone reads as none. Made-up credentials only.
 */
@RunWith(AndroidJUnit4::class)
class AdminStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = AdminStore(context)
    private val kept = KeptAdmin(
        baseUrl = "http://seerr.test:5055",
        email = "approver@example.invalid",
        password = "approver-password-made-up",
        userId = 9,
        displayName = "Approver",
        pin = PinHash.of("5632", iterations = 1_000),
        cookies = listOf(SavedCookie("connect.sid", "s%3Afake-session-value")),
        signedInAt = 1_000L,
    )

    private fun onDisk(): String {
        val dir = File(context.filesDir, "datastore")
        return dir.listFiles().orEmpty().filter { it.name.startsWith("admin") }.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
    }

    private fun keyKept(): Boolean = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(AdminStore.KEY)

    @After
    fun clean() = runBlocking { store.clear() }

    @Test
    fun theRecordIsSealedOnDiskAndReadBackWhole() = runBlocking {
        store.save(kept)
        assertEquals(kept, store.admin())
        val disk = onDisk()
        assertTrue("written", disk.isNotEmpty())
        for (secret in listOf("approver-password-made-up", "approver@example.invalid", "fake-session-value", "Approver", kept.pin.hash, kept.pin.salt)) {
            assertFalse("\"$secret\" is in the clear on disk", disk.contains(secret))
        }
        assertTrue("its own Keystore key", keyKept())
    }

    @Test
    fun removeTakesTheRecordAndTheKey() = runBlocking {
        store.save(kept)
        store.clear()
        assertNull(store.admin())
        assertFalse("the key is gone", keyKept())
        assertFalse(onDisk().contains("approver"))
    }

    @Test
    fun aRecordWhoseKeyIsGoneReadsAsNone() = runBlocking {
        store.save(kept)
        SecretBox(AdminStore.KEY).forget()
        assertNull(store.admin())
    }

    @Test
    fun theFamilysAccountKeyIsNotTouched() = runBlocking {
        val family = SecretBox()
        val sealed = family.seal("family")
        store.save(kept)
        store.clear()
        assertEquals("the family's sealed values still open", "family", family.open(sealed))
    }
}
