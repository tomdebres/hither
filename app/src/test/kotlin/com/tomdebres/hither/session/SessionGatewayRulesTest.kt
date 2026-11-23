package com.tomdebres.hither.session

import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.SignInFailure
import com.tomdebres.hither.core.model.RequestBody
import com.tomdebres.hither.core.rules.SignInRequest
import com.tomdebres.hither.data.Account
import com.tomdebres.hither.data.AccountStorage
import com.tomdebres.hither.data.SavedCookie
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * The gateway's rules about the stored account, on the JVM with a store in memory and a
 * scripted server, so CI runs them. (`SeerrSessionGatewayTest` runs the same gateway over the
 * real DataStore and Keystore on a device.)
 */
class SessionGatewayRulesTest {
    /** A store in memory that can be told to fail. */
    private class Memory : AccountStorage {
        var account: Account? = null
        var cookies: List<SavedCookie> = emptyList()
        var failOnClear = false
        var failOnRead = false

        override suspend fun account(): Account? = if (failOnRead) throw IOException("store will not open") else account
        override suspend fun save(account: Account) {
            this.account = account
        }
        override suspend fun cookies(): List<SavedCookie> = cookies
        override suspend fun saveCookies(cookies: List<SavedCookie>) {
            this.cookies = cookies
        }
        override suspend fun clear() {
            if (failOnClear) throw IOException("store will not open")
            account = null
            cookies = emptyList()
        }
    }

    private val store = Memory()
    private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val request = SignInRequest("http://seerr.test:5055", "tv@example.invalid", "secret")
    private val settings = """{"initialized":true,"applicationTitle":"Seerr","localLogin":true}"""
    private val me = """{"id":8,"displayName":"TV","permissions":32}"""

    /** Seerr's `isAuthenticated` for a cookie it has nobody behind (server/middleware/auth.ts). */
    private val sessionForgotten = """{"status":403,"error":"You do not have permission to access this endpoint"}"""

    /** What a reverse proxy in front of the server answers when it will not pass a call on. */
    private val proxyPage = "<html><head><title>403 Forbidden</title></head><body><h1>403 Forbidden</h1><hr>nginx</body></html>"

    private fun MockRequestHandleScope.html(body: String, status: HttpStatusCode) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "text/html"))

    private fun gateway(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        SeerrSessionGateway(store) { MockEngine { seen += it.url.encodedPath.removePrefix("/api/v1"); handler(it) } }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, cookie: String? = null) =
        respond(body, status, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.SetCookie to listOfNotNull(cookie)))

    private fun MockRequestHandleScope.healthy(it: HttpRequestData): HttpResponseData = when (it.url.encodedPath) {
        "/api/v1/settings/public" -> json(settings)
        "/api/v1/auth/local" -> json("""{"id":8,"permissions":0}""", cookie = "connect.sid=abc; Path=/; HttpOnly")
        "/api/v1/auth/me" ->
            if (it.headers[HttpHeaders.Cookie]?.contains("connect.sid=") == true) json(me)
            else json("""{"message":"cookie 'connect.sid' required"}""", HttpStatusCode.Unauthorized)
        else -> json("""{"status":"ok"}""")
    }

    private fun signedInBefore() {
        store.account = Account(request.baseUrl, request.email, request.password)
    }

    @Test
    fun `sign-in stores the account and the session, and the next launch uses the cookie`() = runTest {
        val session = gateway { healthy(it) }.signIn(request)
        assertEquals("TV", session.user.displayName)
        assertEquals(request.baseUrl, session.baseUrl)
        assertEquals(Account(request.baseUrl, request.email, request.password), store.account)
        assertEquals(listOf("connect.sid"), store.cookies.map { it.name })

        seen.clear()
        val restored = gateway { healthy(it) }.restore()
        assertEquals(request.baseUrl, (restored as Restored.SignedIn).session.baseUrl)
        assertEquals(listOf("/settings/public", "/auth/me"), seen)
    }

    @Test
    fun `a store that will not open when sign-in starts is a failed sign-in, not an exception`() = runTest {
        store.failOnClear = true
        try {
            gateway { healthy(it) }.signIn(request)
            fail("expected SignInFailed")
        } catch (e: SignInFailed) {
            assertEquals(SignInFailure.OTHER, e.failure)
        }
        // and nothing was sent anywhere: the password stays on the device
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a store that will not open at launch is the try-again screen, not an exception`() = runTest {
        store.failOnRead = true
        assertEquals(Restored.ServerUnavailable, gateway { healthy(it) }.restore())
    }

    /** Seerr's CSRF check, through its error handler: what every write gets over http with CSRF on. */
    private val csrfRefusal = """{"message":"invalid csrf token"}"""

    @Test
    fun `a first sign-in refused by the CSRF check over http says so, not that the password is wrong`() = runTest {
        val locked = gateway {
            when (it.url.encodedPath) {
                "/api/v1/settings/public" -> json(settings)
                else -> json(csrfRefusal, HttpStatusCode.Forbidden)
            }
        }
        try {
            locked.signIn(request)
            fail("expected SignInFailed")
        } catch (e: SignInFailed) {
            assertEquals(SignInFailure.CSRF_NEEDS_HTTPS, e.failure)
            assertEquals("Seerr", e.serverTitle)
        }
    }

    @Test
    fun `at launch, the CSRF check refusing to sign in again keeps the account and says why`() = runTest {
        signedInBefore()
        val locked = gateway {
            when (it.url.encodedPath) {
                "/api/v1/settings/public" -> json(settings)
                "/api/v1/auth/me" -> json("""{"message":"cookie 'connect.sid' required"}""", HttpStatusCode.Unauthorized)
                else -> json(csrfRefusal, HttpStatusCode.Forbidden)
            }
        }
        assertEquals(Restored.CsrfNeedsHttps, locked.restore())
        assertEquals("secret", store.account?.password)
    }

    @Test
    fun `the stored password being refused forgets the account`() = runTest {
        signedInBefore()
        val changed = gateway {
            when (it.url.encodedPath) {
                "/api/v1/settings/public" -> json(settings)
                "/api/v1/auth/me" -> json("""{"message":"cookie 'connect.sid' required"}""", HttpStatusCode.Unauthorized)
                else -> json("""{"message":"Access denied."}""", HttpStatusCode.Forbidden)
            }
        }
        assertEquals(Restored.Rejected(SignInFailure.WRONG_CREDENTIALS), changed.restore())
        assertNull(store.account)
    }

    @Test
    fun `a 403 after the server has taken the password again keeps the account`() = runTest {
        signedInBefore()
        var signedIn = false
        // The session has ended; the re-sign-in is accepted; then something in front of the
        // server answers 403 to the auth/me that follows. That is not a verdict on the password.
        val proxied = gateway {
            when (it.url.encodedPath) {
                "/api/v1/settings/public" -> json(settings)
                "/api/v1/auth/local" -> json("""{"id":8,"permissions":0}""", cookie = "connect.sid=abc; Path=/").also { signedIn = true }
                "/api/v1/auth/me" ->
                    if (signedIn) json("""{"message":"Forbidden"}""", HttpStatusCode.Forbidden)
                    else json("""{"message":"cookie 'connect.sid' required"}""", HttpStatusCode.Unauthorized)
                else -> error("unexpected ${it.url}")
            }
        }
        assertEquals(Restored.ServerUnavailable, proxied.restore())
        assertEquals("secret", store.account?.password)
        assertEquals(listOf("/settings/public", "/auth/me", "/auth/local", "/auth/me"), seen)
    }

    @Test
    fun `a 403 on a first sign-in's auth me is not called a wrong password`() = runTest {
        val proxied = gateway {
            when (it.url.encodedPath) {
                "/api/v1/settings/public" -> json(settings)
                "/api/v1/auth/local" -> json("""{"id":8,"permissions":0}""", cookie = "connect.sid=abc; Path=/")
                else -> json("""{"message":"Forbidden"}""", HttpStatusCode.Forbidden)
            }
        }
        try {
            proxied.signIn(request)
            fail("expected SignInFailed")
        } catch (e: SignInFailed) {
            assertEquals(SignInFailure.OTHER, e.failure)
        }
        assertNull(store.account)
        assertTrue(store.cookies.isEmpty())
    }

    // ---- launch: Seerr's own 403 is an ended session, a proxy's is not -------------------------

    /** A launch with the stored account and its cookie `old`, against [handler]. */
    private suspend fun launch(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): Restored {
        signedInBefore()
        store.cookies = listOf(SavedCookie("connect.sid", "old", path = "/"))
        return gateway(handler).restore()
    }

    @Test
    fun `at launch, Seerr's own 403 on auth me signs in again with the stored account`() = runTest {
        val restored = launch {
            when (it.url.encodedPath) {
                "/api/v1/settings/public" -> json(settings)
                "/api/v1/auth/local" -> json("""{"id":8,"permissions":0}""", cookie = "connect.sid=new; Path=/")
                "/api/v1/auth/me" ->
                    if (it.headers[HttpHeaders.Cookie].orEmpty().contains("connect.sid=new")) json(me)
                    else json(sessionForgotten, HttpStatusCode.Forbidden)
                else -> error("unexpected ${it.url}")
            }
        }
        assertEquals("TV", (restored as Restored.SignedIn).session.user.displayName)
        assertEquals(listOf("/settings/public", "/auth/me", "/auth/local", "/auth/me"), seen)
        assertEquals(listOf("new"), store.cookies.map { it.value })
        assertEquals("secret", store.account?.password)
    }

    @Test
    fun `at launch, Seerr's own 403 and then the stored password refused forgets the account`() = runTest {
        val restored = launch {
            when (it.url.encodedPath) {
                "/api/v1/settings/public" -> json(settings)
                "/api/v1/auth/me" -> json(sessionForgotten, HttpStatusCode.Forbidden)
                else -> json("""{"message":"Access denied."}""", HttpStatusCode.Forbidden)
            }
        }
        assertEquals(Restored.Rejected(SignInFailure.WRONG_CREDENTIALS), restored)
        assertNull(store.account)
        assertEquals(listOf("/settings/public", "/auth/me", "/auth/local"), seen)
    }

    @Test
    fun `at launch, Seerr's own 403 again straight after the re-sign-in keeps the account and stops there`() = runTest {
        val restored = launch {
            when (it.url.encodedPath) {
                "/api/v1/settings/public" -> json(settings)
                "/api/v1/auth/local" -> json("""{"id":8,"permissions":0}""", cookie = "connect.sid=new; Path=/")
                else -> json(sessionForgotten, HttpStatusCode.Forbidden)
            }
        }
        assertEquals(Restored.ServerUnavailable, restored)
        assertEquals("secret", store.account?.password)
        assertEquals(listOf("/settings/public", "/auth/me", "/auth/local", "/auth/me"), seen)
    }

    @Test
    fun `at launch, a proxy's 403 page on auth me keeps the account and signs nothing in`() = runTest {
        val restored = launch {
            when (it.url.encodedPath) {
                "/api/v1/settings/public" -> json(settings)
                else -> html(proxyPage, HttpStatusCode.Forbidden)
            }
        }
        assertEquals(Restored.ServerUnavailable, restored)
        assertEquals("secret", store.account?.password)
        assertEquals(listOf("/settings/public", "/auth/me"), seen)
    }

    // ---- calls from the screens: renewing an ended session ---------------------------------------

    /**
     * A server that knows the launch's cookie `old` until [ended] is set, and after that only
     * the cookie of a new sign-in. [beforeRefusing] runs before it answers a call 401.
     */
    private inner class Server {
        var ended = false

        /**
         * The server has lost its sessions while the device still holds the cookie `old`, so
         * `isAuthenticated` finds nobody behind it: Seerr's own 403, not a 401.
         */
        var forgot = false
        var signIns = 0
        var password = "secret"
        var beforeRefusing: suspend () -> Unit = {}

        private fun signedIn(it: HttpRequestData): Boolean {
            val cookie = it.headers[HttpHeaders.Cookie].orEmpty()
            return cookie.contains("connect.sid=new") || (!ended && !forgot && cookie.contains("connect.sid=old"))
        }

        private fun MockRequestHandleScope.notSignedIn(it: HttpRequestData): HttpResponseData =
            if (forgot && it.headers[HttpHeaders.Cookie].orEmpty().contains("connect.sid=old")) json(sessionForgotten, HttpStatusCode.Forbidden)
            else json("""{"message":"cookie 'connect.sid' required"}""", HttpStatusCode.Unauthorized)

        suspend fun MockRequestHandleScope.answer(
            it: HttpRequestData,
            page: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        ): HttpResponseData = when (it.url.encodedPath) {
            "/api/v1/settings/public" -> json(settings)
            "/api/v1/auth/local" ->
                if (password == "secret") json("""{"id":8,"permissions":0}""", cookie = "connect.sid=new${++signIns}; Path=/")
                else json("""{"message":"Access denied."}""", HttpStatusCode.Forbidden)
            "/api/v1/auth/me" -> if (signedIn(it)) json(me) else notSignedIn(it)
            else ->
                if (signedIn(it)) page(it)
                else {
                    beforeRefusing()
                    notSignedIn(it)
                }
        }
    }

    private val trending = """{"page":1,"totalPages":1,"totalResults":1,"results":[{"id":1,"mediaType":"movie","title":"A"}]}"""

    /** Launched with the stored account and its cookie `old`, which the server still knows. */
    private suspend fun launched(
        server: Server,
        failRenewal: Boolean = false,
        csrfOnRenewal: Boolean = false,
        page: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { json(trending) },
    ): SeerrSessionGateway {
        signedInBefore()
        store.cookies = listOf(SavedCookie("connect.sid", "old", path = "/"))
        val gateway = gateway {
            if (failRenewal && it.url.encodedPath == "/api/v1/auth/local") throw IOException("connection refused")
            if (csrfOnRenewal && it.url.encodedPath == "/api/v1/auth/local") return@gateway json(csrfRefusal, HttpStatusCode.Forbidden)
            with(server) { answer(it, page) }
        }
        assertTrue(gateway.restore() is Restored.SignedIn)
        seen.clear()
        return gateway
    }

    private suspend inline fun <reified E : Exception> failsWith(crossinline block: suspend () -> Unit): E {
        try {
            block()
        } catch (e: Exception) {
            if (e is E) return e
            fail("expected ${E::class.simpleName}, got $e")
        }
        fail("expected ${E::class.simpleName}, nothing was thrown")
        throw AssertionError()
    }

    @Test
    fun `a call that finds the session over signs in again with the stored account and is asked once more`() = runTest {
        val server = Server()
        val gateway = launched(server)
        server.ended = true

        val page = gateway.call { it.trending() }
        assertEquals(1, page.results.single().id)
        assertEquals(listOf("/discover/trending", "/auth/local", "/auth/me", "/discover/trending"), seen)
        // the new session is the one kept for the next launch
        assertEquals(listOf("new1"), store.cookies.map { it.value })
    }

    @Test
    fun `calls that find the session over at the same moment sign in again once between them`() = runTest {
        val rows = 4
        val refused = AtomicInteger()
        val allRefused = CompletableDeferred<Unit>()
        val server = Server()
        // No row may renew before every row has been refused: four 401s, then the renewals race.
        server.beforeRefusing = {
            if (refused.incrementAndGet() == rows) allRefused.complete(Unit)
            withTimeout(5_000) { allRefused.await() }
        }
        val gateway = launched(server)
        server.ended = true

        val pages = (1..rows).map { async(Dispatchers.Default) { gateway.call { it.trending() } } }.awaitAll()
        assertEquals(rows, pages.size)
        assertEquals(rows, refused.get())
        assertEquals(1, server.signIns)
        assertEquals(1, seen.count { it == "/auth/local" })
        assertEquals(rows * 2, seen.count { it == "/discover/trending" })
    }

    @Test
    fun `the stored password refused on renewal forgets the account and says so`() = runTest {
        val server = Server()
        val gateway = launched(server)
        server.ended = true
        server.password = "changed on the server"

        failsWith<SeerrException.SignedOut> { gateway.call { it.trending() } }
        assertNull(store.account)
        assertTrue(store.cookies.isEmpty())
        assertEquals(SignInFailure.WRONG_CREDENTIALS, withTimeout(1_000) { gateway.lost.first() })
        // and nothing more is sent: there is no client to send it
        seen.clear()
        failsWith<SeerrException.SignedOut> { gateway.call { it.trending() } }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a renewal the CSRF check refuses keeps the account and is not a lost sign-in`() = runTest {
        val server = Server()
        val gateway = launched(server, csrfOnRenewal = true)
        server.ended = true

        failsWith<SeerrException.CsrfRefused> { gateway.call { it.trending() } }
        assertEquals("secret", store.account?.password)
        assertNull(withTimeoutOrNull(100) { gateway.lost.first() })
    }

    @Test
    fun `a renewal the CSRF check refuses tells the app why`() = runTest {
        val server = Server()
        val gateway = launched(server, csrfOnRenewal = true)
        server.ended = true

        failsWith<SeerrException.CsrfRefused> { gateway.call { it.trending() } }
        assertEquals(SignInFailure.CSRF_NEEDS_HTTPS, withTimeout(1_000) { gateway.stuck.first() })
    }

    @Test
    fun `a server that does not answer the renewal keeps the account`() = runTest {
        val server = Server()
        val gateway = launched(server, failRenewal = true)
        server.ended = true

        failsWith<SeerrException.Unreachable> { gateway.call { it.trending() } }
        assertEquals("secret", store.account?.password)
        assertNull(withTimeoutOrNull(100) { gateway.lost.first() })
    }

    @Test
    fun `a call that finds its cookie forgotten (Seerr's own 403) signs in again and is asked once more`() = runTest {
        val server = Server()
        val gateway = launched(server)
        server.forgot = true

        val page = gateway.call { it.trending() }
        assertEquals(1, page.results.single().id)
        assertEquals(listOf("/discover/trending", "/auth/local", "/auth/me", "/discover/trending"), seen)
        assertEquals(listOf("new1"), store.cookies.map { it.value })
    }

    @Test
    fun `rows that find the cookie forgotten at the same moment sign in again once between them`() = runTest {
        val rows = 4
        val refused = AtomicInteger()
        val allRefused = CompletableDeferred<Unit>()
        val server = Server()
        server.beforeRefusing = {
            if (refused.incrementAndGet() == rows) allRefused.complete(Unit)
            withTimeout(5_000) { allRefused.await() }
        }
        val gateway = launched(server)
        server.forgot = true

        val pages = (1..rows).map { async(Dispatchers.Default) { gateway.call { it.trending() } } }.awaitAll()
        assertEquals(rows, pages.size)
        assertEquals(1, server.signIns)
        assertEquals(rows * 2, seen.count { it == "/discover/trending" })
    }

    @Test
    fun `a request answered Seerr's own 403 is sent again after the renewal, and only then`() = runTest {
        val server = Server()
        val posted = AtomicInteger()
        val gateway = launched(server) {
            posted.incrementAndGet()
            json("""{"id":1,"status":2,"is4k":false}""", HttpStatusCode.Created)
        }
        server.forgot = true

        gateway.call { it.request(RequestBody(mediaType = "movie", mediaId = 7)) }
        assertEquals(1, posted.get())
        assertEquals(listOf("/request", "/auth/local", "/auth/me", "/request"), seen)
    }

    @Test
    fun `a proxy's 403 page on a call is thrown as it came, with no sign-in`() = runTest {
        val gateway = launched(Server()) { html(proxyPage, HttpStatusCode.Forbidden) }

        failsWith<SeerrException.Refused> { gateway.call { it.trending() } }
        assertEquals(listOf("/discover/trending"), seen)
        assertEquals("secret", store.account?.password)
        assertNull(withTimeoutOrNull(100) { gateway.lost.first() })
    }

    @Test
    fun `a 403 in other JSON on a call is not a session end either`() = runTest {
        val gateway = launched(Server()) { json("""{"message":"You do not have permission to make 4K movie requests."}""", HttpStatusCode.Forbidden) }

        val e = failsWith<SeerrException.Refused> { gateway.call { it.request(RequestBody(mediaType = "movie", mediaId = 7, is4k = true)) } }
        assertEquals("You do not have permission to make 4K movie requests.", e.serverMessage)
        assertEquals(listOf("/request"), seen)
    }

    @Test
    fun `Seerr's own 403 straight after a renewal is thrown, not chased`() = runTest {
        val server = Server()
        val gateway = launched(server) { json(sessionForgotten, HttpStatusCode.Forbidden) }

        failsWith<SeerrException.SignedOut> { gateway.call { it.trending() } }
        assertEquals(listOf("/discover/trending", "/auth/local", "/auth/me", "/discover/trending"), seen)
        assertEquals("secret", store.account?.password)
    }

    @Test
    fun `a second 401 straight after a renewal is thrown, not chased`() = runTest {
        val server = Server()
        val gateway = launched(server) { json("""{"message":"Unauthorized"}""", HttpStatusCode.Unauthorized) }

        failsWith<SeerrException.SignedOut> { gateway.call { it.trending() } }
        assertEquals(listOf("/discover/trending", "/auth/local", "/auth/me", "/discover/trending"), seen)
        assertEquals("secret", store.account?.password)
    }

    @Test
    fun `a request answered 401 is sent again after the renewal, and only then`() = runTest {
        val server = Server()
        val posted = AtomicInteger()
        val gateway = launched(server) {
            posted.incrementAndGet()
            json("""{"id":1,"status":2,"is4k":false}""", HttpStatusCode.Created)
        }
        server.ended = true

        gateway.call { it.request(RequestBody(mediaType = "movie", mediaId = 7)) }
        assertEquals(1, posted.get())
        assertEquals(listOf("/request", "/auth/local", "/auth/me", "/request"), seen)
    }

    @Test
    fun `a request that times out is not sent again`() = runTest {
        val gateway = launched(Server()) { throw IOException("read timed out") }

        failsWith<SeerrException.Unreachable> { gateway.call { it.request(RequestBody(mediaType = "movie", mediaId = 7)) } }
        assertEquals(listOf("/request"), seen)
    }

    @Test
    fun `a server error is not asked twice either`() = runTest {
        val gateway = launched(Server()) { json("""{"message":"boom"}""", HttpStatusCode.InternalServerError) }

        val e = failsWith<SeerrException.Http> { gateway.call { it.trending() } }
        assertEquals(500, e.status)
        assertEquals(listOf("/discover/trending"), seen)
    }
}
