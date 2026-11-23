@file:OptIn(ExperimentalForeignApi::class)

package com.tomdebres.hither.core

import com.tomdebres.hither.core.model.User
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.getenv
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * The client on tvOS as the app will run it: Ktor's Darwin engine against `scripts/fake-seerr`
 * (started by the build on a free port, reached at localhost from the simulator), the session in
 * the Keychain. Each test uses a Keychain service of its own, so it never meets the app's items.
 */
class DarwinSessionTest {
    private val base = checkNotNull(getenv("HITHER_FAKE_SEERR")?.toKString()) {
        "HITHER_FAKE_SEERR is not set: run through :core:tvosSimulatorArm64Test, which starts scripts/fake-seerr"
    }
    private val service = "com.tomdebres.hither.test.${Random.nextLong().toULong()}"
    private val clients = mutableListOf<SeerrClient>()

    private fun client(cookies: io.ktor.client.plugins.cookies.CookiesStorage) = darwinSeerrClient(base, cookies).also { clients += it }

    private suspend fun resetFake() {
        HttpClient(darwinEngine()).use { assertEquals(HttpStatusCode.OK, it.post("$base/fake/reset").status) }
    }

    /** Signs in as the app does: the server's settings first (they carry the CSRF cookie). */
    private suspend fun SeerrClient.signedIn(): User {
        publicSettings()
        return signIn("tv@example.invalid", "right-password")
    }

    /**
     * Real time, not runTest's virtual clock: the calls go over a real socket, and the client's
     * request timeout must not fire the moment the test thread waits.
     */
    private fun live(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(60.seconds) { block() } }

    @AfterTest
    fun clean() {
        clients.forEach { it.close() }
        KeychainVault(account = base, service = service).delete()
    }

    @Test
    fun `a sign-in through the Darwin engine is kept in the Keychain - and a new client is signed in`() = live {
        resetFake()
        val user = client(keychainCookies(base, service)).signedIn()
        // A new client and a new storage, as after a relaunch: only the Keychain carries the session.
        val again = client(keychainCookies(base, service))
        assertEquals(user.id, again.me().id)
        assertEquals(user.displayName, again.me().displayName)
    }

    @Test
    fun `URLSession keeps no cookie of its own - a client without the stored session is signed out`() = live {
        resetFake()
        client(keychainCookies(base, service)).signedIn()
        val stranger = client(AcceptAllCookiesStorage())
        assertFailsWith<SeerrException.SignedOut> { stranger.me() }
    }

    @Test
    fun `forgetting the session signs the device out - in the Keychain too`() = live {
        resetFake()
        val cookies = keychainCookies(base, service)
        client(cookies).signedIn()
        cookies.forget()
        assertNull(KeychainVault(account = base, service = service).read())
        assertFailsWith<SeerrException.SignedOut> { client(keychainCookies(base, service)).me() }
    }

    @Test
    fun `the Keychain keeps one text per server - replaced on write and gone on delete`() {
        val one = KeychainVault(account = "http://one.example.invalid:5055", service = service)
        val two = KeychainVault(account = "http://two.example.invalid:5055", service = service)
        try {
            assertNull(one.read())
            one.write("first")
            one.write("second, with ünïcode")
            two.write("other")
            assertEquals("second, with ünïcode", one.read())
            assertEquals("other", two.read())
            one.delete()
            one.delete() // nothing there is no failure
            assertNull(one.read())
            assertEquals("other", two.read())
        } finally {
            one.delete()
            two.delete()
        }
    }
}
