package com.tomdebres.hither.core

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The cookie storage over a platform's vault (M7): what it keeps, when it writes, and forgetting. */
class SessionCookiesTest {
    private val base = "http://seerr.test:5055"

    /** A vault in memory, counting writes; [failWrites] refuses that many before taking one. */
    private class MemoryVault(var text: String? = null, var failWrites: Int = 0) : CookieVault {
        var writes = 0

        override fun read(): String? = text

        override fun write(text: String) {
            if (failWrites > 0) {
                failWrites--
                throw IllegalStateException("vault refused")
            }
            writes++
            this.text = text
        }

        override fun delete() {
            text = null
        }
    }

    /** A Seerr whose sign-in sets a session cookie as express-session does, and whose auth/me echoes the Cookie header. */
    private fun client(cookies: SessionCookies) = SeerrClient(
        MockEngine { request ->
            val json = HttpHeaders.ContentType to listOf("application/json")
            when (request.url.encodedPath) {
                "/api/v1/auth/local" -> respond(
                    "{}",
                    headers = headersOf(json, HttpHeaders.SetCookie to listOf("connect.sid=s%3Aabc.d%2Bef; Path=/; Expires=Wed, 01 Jan 2059 00:00:00 GMT; HttpOnly")),
                )
                "/api/v1/auth/me" -> {
                    val sent = request.headers[HttpHeaders.Cookie].orEmpty()
                    respond("""{"id":8,"displayName":"${sent.replace("\"", "")}"}""", headers = headersOf(json))
                }
                else -> respond("{}", headers = headersOf(json))
            }
        },
        base,
        cookies,
    )

    @Test
    fun `a sign-in is kept in the vault and sent again - byte for byte - by a new client`() = runTest {
        val vault = MemoryVault()
        client(SessionCookies(base, vault)).signIn("tv@example.invalid", "right-password")
        assertTrue(vault.text!!.contains("connect.sid"))
        // A new storage over the same vault, as after a relaunch: Seerr must see its own value, not a re-encoded one.
        assertEquals("connect.sid=s%3Aabc.d%2Bef", client(SessionCookies(base, vault)).me().displayName)
    }

    @Test
    fun `the vault is written only when the cookies change - and only with this server's`() = runTest {
        val vault = MemoryVault()
        val cookies = SessionCookies(base, vault)
        val session = Cookie("connect.sid", "one", CookieEncoding.RAW, path = "/")
        cookies.addCookie(Url("$base/api/v1/auth/local"), session)
        cookies.addCookie(Url("$base/api/v1/auth/local"), session)
        assertEquals(1, vault.writes)
        cookies.addCookie(Url("http://elsewhere.test/"), Cookie("tracker", "x", CookieEncoding.RAW, path = "/"))
        assertEquals(1, vault.writes)
        cookies.addCookie(Url("$base/api/v1/auth/local"), session.copy(value = "two"))
        assertEquals(2, vault.writes)
        assertEquals(listOf("connect.sid" to "two"), SessionCookies(base, vault).get(Url("$base/api/v1/auth/me")).map { it.name to it.value })
    }

    @Test
    fun `a refused write fails the call that set the cookie - and is tried again on the next`() = runTest {
        val vault = MemoryVault(failWrites = 1)
        val cookies = SessionCookies(base, vault)
        val session = Cookie("connect.sid", "one", CookieEncoding.RAW, path = "/")
        assertFailsWith<IllegalStateException> { cookies.addCookie(Url("$base/api/v1/auth/local"), session) }
        cookies.addCookie(Url("$base/api/v1/auth/local"), session)
        assertEquals(1, vault.writes)
        assertTrue(vault.text!!.contains("one"))
    }

    @Test
    fun `forgetting empties the vault and the memory`() = runTest {
        val vault = MemoryVault()
        val cookies = SessionCookies(base, vault)
        cookies.addCookie(Url("$base/api/v1/auth/local"), Cookie("connect.sid", "one", CookieEncoding.RAW, path = "/"))
        cookies.forget()
        assertNull(vault.text)
        assertEquals(emptyList(), cookies.get(Url("$base/api/v1/auth/me")))
        assertEquals(emptyList(), SessionCookies(base, vault).get(Url("$base/api/v1/auth/me")))
    }

    @Test
    fun `a vault holding something else gives no cookies`() = runTest {
        assertEquals(emptyList(), SessionCookies(base, MemoryVault("not json")).get(Url("$base/api/v1/auth/me")))
        assertEquals(emptyList(), SessionCookies(base, MemoryVault("""{"a":1}""")).get(Url("$base/api/v1/auth/me")))
    }
}
