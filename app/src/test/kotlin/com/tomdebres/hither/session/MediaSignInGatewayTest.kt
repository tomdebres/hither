package com.tomdebres.hither.session

import com.tomdebres.hither.core.PlexTv
import com.tomdebres.hither.core.PollPace
import com.tomdebres.hither.core.SignInFailure
import com.tomdebres.hither.core.rules.MediaServerKind
import com.tomdebres.hither.core.rules.SignInRequest
import com.tomdebres.hither.data.Account
import com.tomdebres.hither.data.AccountMethod
import com.tomdebres.hither.data.AccountStorage
import com.tomdebres.hither.data.SavedCookie
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections

/**
 * The gateway's M8 ways in, on the JVM with a store in memory, a scripted Seerr and a scripted plex.tv: what a server
 * offers, what each way in stores (never a Plex token, never a Quick Connect secret), and what happens when each one's
 * session ends.
 */
class MediaSignInGatewayTest {
    private class Memory : AccountStorage {
        var account: Account? = null
        var cookies: List<SavedCookie> = emptyList()
        override suspend fun account(): Account? = account
        override suspend fun save(account: Account) {
            this.account = account
        }
        override suspend fun cookies(): List<SavedCookie> = cookies
        override suspend fun saveCookies(cookies: List<SavedCookie>) {
            this.cookies = cookies
        }
        override suspend fun clear() {
            account = null
            cookies = emptyList()
        }
    }

    private val store = Memory()
    private val base = "http://seerr.test:5055"
    private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val bodies: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val plexSeen: MutableList<HttpRequestData> = Collections.synchronizedList(mutableListOf())
    private var gone = 0
    private val quick = PollPace(everyMillis = 10, longestMillis = 40, giveUpAfter = 3)

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, cookie: String? = null) =
        respond(body, status, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.SetCookie to listOfNotNull(cookie)))

    private fun settings(type: Int, local: Boolean = true, viaServer: Boolean = true) =
        """{"initialized":true,"applicationTitle":"Seerr","localLogin":$local,"mediaServerLogin":$viaServer,"mediaServerType":$type,"jellyfinExternalHost":"https://jf.example.invalid/"}"""

    private val me = """{"id":12,"displayName":"Robin","permissions":32}"""

    private fun gateway(
        plex: (MockRequestHandleScope.(HttpRequestData) -> HttpResponseData)? = null,
        seerr: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = SeerrSessionGateway(
        store,
        onAccountGone = { gone++ },
        plexTv = plex?.let { answer -> { PlexTv(MockEngine { plexSeen += it; answer(it) }, "cid-1", "https://plex.test") } },
        pace = quick,
    ) {
        MockEngine {
            seen += it.method.value + " " + it.url.encodedPath.removePrefix("/api/v1")
            bodies += it.body.toByteArray().decodeToString()
            seerr(it)
        }
    }

    private suspend inline fun failsWith(failure: SignInFailure, crossinline block: suspend () -> Unit) {
        try {
            block()
        } catch (e: SignInFailed) {
            assertEquals(failure, e.failure)
            return
        }
        fail("expected $failure, nothing was thrown")
    }

    // ---- what a server offers ----------------------------------------------------------------------------------

    @Test
    fun `a Jellyfin server's offer reads its version - a Plex server's does not`() = runTest {
        val jellyfin = gateway { if (it.url.encodedPath.endsWith("/status")) json("""{"version":"3.5.2"}""") else json(settings(type = 2)) }.offer(base)
        assertTrue(jellyfin.methods.mediaServerPassword && jellyfin.methods.quickConnect)
        assertEquals("jf.example.invalid", jellyfin.approveAt)
        assertEquals(listOf("GET /settings/public", "GET /status"), seen)
        seen.clear()
        val plex = gateway { json(settings(type = 1)) }.offer(base)
        assertTrue(plex.methods.plex)
        assertEquals(listOf("GET /settings/public"), seen)
    }

    @Test
    fun `a server that will not give its version is offered username and password - no Quick Connect`() = runTest {
        val offer = gateway { if (it.url.encodedPath.endsWith("/status")) json("{}", HttpStatusCode.NotFound) else json(settings(type = 2)) }.offer(base)
        assertTrue(offer.methods.mediaServerPassword)
        assertFalse(offer.methods.quickConnect)
    }

    @Test
    fun `a server not answering is unreachable - nothing stored`() = runTest {
        failsWith(SignInFailure.UNREACHABLE) { gateway { throw java.io.IOException("refused") }.offer(base) }
        assertNull(store.account)
    }

    // ---- Jellyfin and Emby -------------------------------------------------------------------------------------

    private fun MockRequestHandleScope.jellyfin(it: HttpRequestData, type: Int = 2): HttpResponseData = when (it.url.encodedPath) {
        "/api/v1/settings/public" -> json(settings(type))
        "/api/v1/auth/jellyfin" -> json("""{"id":12}""", cookie = "connect.sid=jf; Path=/; HttpOnly")
        "/api/v1/auth/me" -> if (it.headers[HttpHeaders.Cookie]?.contains("connect.sid=") == true) json(me) else json("{}", HttpStatusCode.Unauthorized)
        else -> json("""{"page":1,"totalPages":1,"totalResults":0,"results":[]}""")
    }

    @Test
    fun `Jellyfin sign-in stores the username and password to sign in again - as a media-server account`() = runTest {
        val session = gateway { jellyfin(it) }.signInWithMediaServer(SignInRequest(base, "robin", "robin-password"))
        assertEquals("Robin", session.user.displayName)
        assertEquals(Account(base, "robin", "robin-password", AccountMethod.MEDIA_SERVER), store.account)
        // The session says its way in, for Sign Out's words (H1, as the Apple TV app)
        assertEquals(AccountMethod.MEDIA_SERVER, session.method)
        assertEquals(listOf("connect.sid"), store.cookies.map { it.name })
        assertTrue(seen.contains("POST /auth/jellyfin"))
        assertFalse(seen.contains("POST /auth/local"))
    }

    @Test
    fun `media-server sign-in switched off is said before anything is sent`() = runTest {
        failsWith(SignInFailure.METHOD_OFF) {
            gateway { json(settings(type = 2, viaServer = false)) }.signInWithMediaServer(SignInRequest(base, "robin", "robin-password"))
        }
        assertEquals(listOf("GET /settings/public"), seen)
        assertNull(store.account)
    }

    @Test
    fun `a wrong Jellyfin password is wrong credentials - nothing stored`() = runTest {
        failsWith(SignInFailure.WRONG_CREDENTIALS) {
            gateway {
                if (it.url.encodedPath == "/api/v1/auth/jellyfin") json("""{"message":"INVALID_CREDENTIALS"}""", HttpStatusCode.Unauthorized)
                else jellyfin(it)
            }.signInWithMediaServer(SignInRequest(base, "robin", "nope"))
        }
        assertNull(store.account)
    }

    @Test
    fun `a media-server account whose session ended signs in again through auth jellyfin`() = runTest {
        store.account = Account(base, "robin", "robin-password", AccountMethod.MEDIA_SERVER)
        store.cookies = listOf(SavedCookie("connect.sid", "old", path = "/"))
        val restored = gateway {
            if (it.url.encodedPath == "/api/v1/auth/me" && it.headers[HttpHeaders.Cookie]?.contains("connect.sid=old") == true) json("{}", HttpStatusCode.Unauthorized)
            else jellyfin(it)
        }.restore()
        assertTrue(restored is Restored.SignedIn)
        assertEquals(AccountMethod.MEDIA_SERVER, (restored as Restored.SignedIn).session.method)
        assertTrue(seen.contains("POST /auth/jellyfin"))
        assertEquals("""{"username":"robin","password":"robin-password","email":"robin"}""", bodies[seen.indexOf("POST /auth/jellyfin")])
    }

    @Test
    fun `a media-server account Seerr no longer imports is forgotten when the session ends`() = runTest {
        store.account = Account(base, "robin", "robin-password", AccountMethod.MEDIA_SERVER)
        store.cookies = listOf(SavedCookie("connect.sid", "old", path = "/"))
        val restored = gateway {
            when (it.url.encodedPath) {
                "/api/v1/auth/me" -> json("{}", HttpStatusCode.Unauthorized)
                "/api/v1/auth/jellyfin" -> json("""{"message":"Access denied."}""", HttpStatusCode.Forbidden)
                else -> jellyfin(it)
            }
        }.restore()
        assertEquals(Restored.Rejected(SignInFailure.NOT_IMPORTED), restored)
        assertNull(store.account)
        assertEquals(1, gone)
    }

    // ---- Plex ----------------------------------------------------------------------------------------------------

    private var plexLooks = 0

    private fun MockRequestHandleScope.plexTv(it: HttpRequestData): HttpResponseData =
        if (it.method == HttpMethod.Post) json("""{"id":77,"code":"X7K6","expiresIn":900}""", HttpStatusCode.Created)
        else json("""{"id":77,"code":"X7K6","expiresIn":890,"authToken":${if (++plexLooks >= 2) "\"plex-token-1\"" else "null"}}""")

    private fun MockRequestHandleScope.plexSeerr(it: HttpRequestData): HttpResponseData = when (it.url.encodedPath) {
        "/api/v1/settings/public" -> json(settings(type = 1))
        "/api/v1/auth/plex" -> json("""{"id":14}""", cookie = "connect.sid=px; Path=/; HttpOnly")
        "/api/v1/auth/me" -> json("""{"id":14,"displayName":"Plex Member","permissions":32}""")
        else -> json("{}")
    }

    @Test
    fun `Plex - the code - the wait - and the account stored without any token or password`() = runTest {
        val gateway = gateway(plex = { plexTv(it) }) { plexSeerr(it) }
        val code = gateway.newPlexCode()
        assertEquals("X7K6", code.code)
        val session = gateway.signInWithPlex(base, code)
        assertEquals("Plex Member", session.user.displayName)
        assertEquals(Account(base, "", "", AccountMethod.PLEX), store.account)
        assertEquals(AccountMethod.PLEX, session.method)
        assertEquals(AccountMethod.PLEX, gateway.storedMethod())
        assertEquals("""{"authToken":"plex-token-1"}""", bodies[seen.indexOf("POST /auth/plex")])
        // plex.tv was asked for the code and looked at, with the install's id, and Seerr was never sent to plex.tv
        assertTrue(plexSeen.all { it.url.host == "plex.test" && it.headers["X-Plex-Client-Identifier"] == "cid-1" })
        assertFalse(store.toString().contains("plex-token-1"))
    }

    @Test
    fun `Plex - no plex-tv wired is plex-tv unreachable`() = runTest {
        failsWith(SignInFailure.PLEX_UNREACHABLE) { gateway { plexSeerr(it) }.newPlexCode() }
    }

    @Test
    fun `Plex - a code nobody enters runs out - nothing stored`() = runBlocking {
        // In real time: the code's life is counted on the monotonic clock, as on the TV.
        val gateway = gateway(plex = {
            if (it.method == HttpMethod.Post) json("""{"id":77,"code":"X7K6","expiresIn":1}""", HttpStatusCode.Created)
            else json("""{"id":77,"code":"X7K6","authToken":null}""")
        }) { plexSeerr(it) }
        val code = gateway.newPlexCode()
        withTimeout(10_000) { failsWith(SignInFailure.CODE_EXPIRED) { gateway.signInWithPlex(base, code) } }
        assertNull(store.account)
        assertFalse(seen.contains("POST /auth/plex"))
    }

    @Test
    fun `a Plex account's session ending at launch cannot be renewed - forgotten - its server offered again`() = runTest {
        store.account = Account(base, "", "", AccountMethod.PLEX)
        store.cookies = listOf(SavedCookie("connect.sid", "old", path = "/"))
        val restored = gateway { if (it.url.encodedPath == "/api/v1/auth/me") json("{}", HttpStatusCode.Unauthorized) else plexSeerr(it) }.restore()
        assertEquals(Restored.Rejected(SignInFailure.SESSION_ENDED, server = base), restored)
        assertNull(store.account)
        assertEquals(1, gone)
        assertFalse(seen.any { it.startsWith("POST /auth/") })
    }

    @Test
    fun `a Quick Connect account's session ending in use is lost as SESSION_ENDED`() = runTest {
        store.account = Account(base, "", "", AccountMethod.QUICK_CONNECT)
        store.cookies = listOf(SavedCookie("connect.sid", "old", path = "/"))
        var ended = false
        val gateway = gateway {
            when {
                it.url.encodedPath == "/api/v1/auth/me" -> json(me)
                ended -> json("{}", HttpStatusCode.Unauthorized)
                else -> json(settings(type = 2))
            }
        }
        assertTrue(gateway.restore() is Restored.SignedIn)
        ended = true
        try {
            gateway.call { it.trending() }
            fail("the call went through")
        } catch (_: com.tomdebres.hither.core.SeerrException.SignedOut) {
        }
        assertEquals(SignInFailure.SESSION_ENDED, withTimeout(5_000) { gateway.lost.first() })
        assertNull(store.account)
    }

    // ---- Quick Connect ---------------------------------------------------------------------------------------------

    @Test
    fun `Quick Connect - the code - the wait on a client of its own - the session kept - no secret stored`() = runTest {
        val secret = "AB".repeat(8) // made up, built so it never reads as a key
        var checks = 0
        val gateway = gateway {
            when (it.url.encodedPath) {
                "/api/v1/settings/public" -> json(settings(type = 2))
                "/api/v1/auth/jellyfin/quickconnect/initiate" -> json("""{"code":"525562","secret":"$secret"}""")
                "/api/v1/auth/jellyfin/quickconnect/check" -> json("""{"authenticated":${++checks >= 2}}""")
                "/api/v1/auth/jellyfin/quickconnect/authenticate" -> json("""{"id":12}""", cookie = "connect.sid=qc; Path=/; HttpOnly")
                "/api/v1/auth/me" -> json(me)
                else -> json("{}")
            }
        }
        val code = gateway.newQuickConnectCode(base)
        assertEquals("525562", code.code)
        assertFalse(code.toString().contains(secret))
        val session = gateway.signInWithQuickConnect(base, code)
        assertEquals("Robin", session.user.displayName)
        assertEquals(Account(base, "", "", AccountMethod.QUICK_CONNECT), store.account)
        assertEquals(AccountMethod.QUICK_CONNECT, session.method)
        assertEquals(listOf("connect.sid"), store.cookies.map { it.name })
        assertEquals("qc", store.cookies.single().value)
        assertEquals(MediaServerKind.JELLYFIN, MediaServerKind.of(2))
    }
}
