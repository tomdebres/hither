package com.tomdebres.hither.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tomdebres.hither.core.SignInFailure
import com.tomdebres.hither.core.rules.SignInRequest
import com.tomdebres.hither.data.AccountStore
import com.tomdebres.hither.data.SecretBox
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import com.tomdebres.hither.data.Account
import com.tomdebres.hither.data.AccountMethod
import org.junit.runner.RunWith
import java.io.IOException

/**
 * The gateway with the real store (DataStore and the Keystore, so it needs a device) and a
 * scripted server: what is kept, what is sent again, and what is forgotten.
 */
@RunWith(AndroidJUnit4::class)
class SeerrSessionGatewayTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = AccountStore(context, SecretBox(alias = "hither.test.gateway"))
    private val seen = mutableListOf<HttpRequestData>()
    private val request = SignInRequest("http://seerr.test:5055", "tv@example.invalid", "secret")

    private val settings = """{"initialized":true,"applicationTitle":"Seerr","localLogin":true}"""
    private val me = """{"id":8,"displayName":"TV","permissions":32}"""

    private fun gateway(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        SeerrSessionGateway(store) { MockEngine { seen += it; handler(it) } }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, cookie: String? = null) =
        respond(body, status, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.SetCookie to listOfNotNull(cookie)))

    /** A server that takes the sign-in and then knows the session by its cookie. */
    private fun MockRequestHandleScope.healthy(it: HttpRequestData): HttpResponseData = when (it.url.encodedPath) {
        "/api/v1/settings/public" -> json(settings)
        "/api/v1/auth/local" -> json("""{"id":8,"permissions":0}""", cookie = "connect.sid=abc; Path=/; HttpOnly")
        "/api/v1/auth/me" ->
            if (it.headers[HttpHeaders.Cookie]?.contains("connect.sid=") == true) json(me)
            else json("""{"message":"cookie 'connect.sid' required"}""", HttpStatusCode.Unauthorized)
        "/api/v1/auth/logout" -> json("""{"status":"ok"}""")
        else -> error("unexpected ${it.url}")
    }

    @Before
    fun clean() = runTest { store.clear() }

    @After
    fun cleanUp() = runTest { store.clear() }

    @Test
    fun signInStoresTheAccountAndTheSessionAndTheNextLaunchUsesTheCookie() = runTest {
        val session = gateway { healthy(it) }.signIn(request)
        assertEquals("TV", session.user.displayName)
        assertEquals("Seerr", session.settings.applicationTitle)
        assertEquals(request.baseUrl, store.account()!!.baseUrl)
        assertEquals("secret", store.account()!!.password)
        assertEquals(listOf("connect.sid"), store.cookies().map { it.name })

        // a new process: a new gateway over the same store
        seen.clear()
        val restored = gateway { healthy(it) }.restore()
        assertTrue(restored is Restored.SignedIn)
        // settings, then auth/me with the stored cookie: no password was sent again
        assertEquals(listOf("/api/v1/settings/public", "/api/v1/auth/me"), seen.map { it.url.encodedPath })
        assertEquals("connect.sid=abc", seen[1].headers[HttpHeaders.Cookie])
    }

    @Test
    fun anEndedSessionIsRenewedWithTheStoredAccount() = runTest {
        gateway { healthy(it) }.signIn(request)
        store.saveCookies(emptyList()) // the server forgot us, or the cookie expired
        seen.clear()

        val restored = gateway { healthy(it) }.restore()
        assertTrue(restored is Restored.SignedIn)
        assertEquals(
            listOf("/api/v1/settings/public", "/api/v1/auth/me", "/api/v1/auth/local", "/api/v1/auth/me"),
            seen.map { it.url.encodedPath },
        )
        assertEquals(listOf("connect.sid"), store.cookies().map { it.name })
    }

    @Test
    fun aRefusedSignInStoresNothing() = runTest {
        val refusing = gateway {
            if (it.url.encodedPath == "/api/v1/settings/public") json(settings) else json("""{"message":"Access denied."}""", HttpStatusCode.Forbidden)
        }
        try {
            refusing.signIn(request)
            fail("expected SignInFailed")
        } catch (e: SignInFailed) {
            assertEquals(SignInFailure.WRONG_CREDENTIALS, e.failure)
            assertEquals("Seerr", e.serverTitle)
        }
        assertNull(store.account())
        assertTrue(store.cookies().isEmpty())
    }

    @Test
    fun aServerWithPasswordSignInOffIsNotSentThePassword() = runTest {
        val off = gateway { json("""{"initialized":true,"applicationTitle":"Seerr","localLogin":false}""") }
        try {
            off.signIn(request)
            fail("expected SignInFailed")
        } catch (e: SignInFailed) {
            assertEquals(SignInFailure.LOCAL_LOGIN_DISABLED, e.failure)
        }
        assertEquals(listOf("/api/v1/settings/public"), seen.map { it.url.encodedPath })
        assertNull(store.account())
    }

    @Test
    fun aServerThatIsDownKeepsTheAccountForLater() = runTest {
        gateway { healthy(it) }.signIn(request)
        val restored = gateway { throw IOException("no route to host") }.restore()
        assertEquals(Restored.ServerUnavailable, restored)
        assertEquals("secret", store.account()!!.password)
    }

    @Test
    fun aStoredAccountTheServerNoLongerTakesIsForgotten() = runTest {
        gateway { healthy(it) }.signIn(request)
        store.saveCookies(emptyList())
        val changed = gateway {
            when (it.url.encodedPath) {
                "/api/v1/settings/public" -> json(settings)
                "/api/v1/auth/me" -> json("""{"message":"cookie 'connect.sid' required"}""", HttpStatusCode.Unauthorized)
                else -> json("""{"message":"Access denied."}""", HttpStatusCode.Forbidden)
            }
        }
        assertEquals(Restored.Rejected(SignInFailure.WRONG_CREDENTIALS), changed.restore())
        assertNull(store.account())
    }

    @Test
    fun aRefusalThatIsNotAboutThePasswordKeepsTheAccount() = runTest {
        gateway { healthy(it) }.signIn(request)
        // a reverse proxy in front of the server answers 403 for everything for a while
        val proxied = gateway { json("""{"message":"Forbidden"}""", HttpStatusCode.Forbidden) }
        assertEquals(Restored.ServerUnavailable, proxied.restore())
        assertEquals("secret", store.account()!!.password)

        // and so does a 403 on auth/me while the session cookie is still there
        val odd = gateway {
            if (it.url.encodedPath == "/api/v1/settings/public") json(settings) else json("""{"message":"Forbidden"}""", HttpStatusCode.Forbidden)
        }
        assertEquals(Restored.ServerUnavailable, odd.restore())
        assertEquals("secret", store.account()!!.password)
    }

    @Test
    fun aStoreThatFailsWhileSigningInIsAFailedSignInNotACrash() = runTest {
        // the Keystore refuses to seal the password (it can: a locked or corrupted keystore)
        val failingBox = object : SecretBox(alias = "hither.test.gateway") {
            override fun seal(plain: String): String =
                if (plain == request.password) throw IllegalStateException("keystore unavailable") else super.seal(plain)
        }
        val failingStore = AccountStore(context, failingBox)
        val g = SeerrSessionGateway(failingStore) { MockEngine { seen += it; healthy(it) } }
        try {
            g.signIn(request)
            fail("expected SignInFailed")
        } catch (e: SignInFailed) {
            assertEquals(SignInFailure.OTHER, e.failure)
            assertEquals("Seerr", e.serverTitle)
        }
        // nothing half-stored: no account, and the session cookie that had been saved is gone
        assertNull(store.account())
        assertTrue(store.cookies().isEmpty())
    }

    @Test
    fun signOutTellsTheServerAndForgetsEverything() = runTest {
        val g = gateway { healthy(it) }
        g.signIn(request)
        seen.clear()
        g.signOut()
        assertEquals(listOf("/api/v1/auth/logout"), seen.map { it.url.encodedPath })
        assertNull(store.account())
        assertTrue(store.cookies().isEmpty())
        assertEquals(Restored.NoAccount, gateway { healthy(it) }.restore())
    }

    // ---- M8: the way in, kept --------------------------------------------------------------------------------

    @Test
    fun theWayInIsKeptWithTheAccount_noPasswordForPlex() = runTest {
        store.save(Account("http://seerr.test:5055", "robin", "robin-password", AccountMethod.MEDIA_SERVER))
        assertEquals(Account("http://seerr.test:5055", "robin", "robin-password", AccountMethod.MEDIA_SERVER), store.account())
        store.save(Account("http://seerr.test:5055", "", "", AccountMethod.PLEX))
        assertEquals(Account("http://seerr.test:5055", "", "", AccountMethod.PLEX), store.account())
        // a password account, as every account before M8 was
        store.save(Account("http://seerr.test:5055", "tv@example.invalid", "secret"))
        assertEquals(AccountMethod.LOCAL, store.account()!!.method)
    }
}
