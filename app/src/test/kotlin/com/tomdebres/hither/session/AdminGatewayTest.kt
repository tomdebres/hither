package com.tomdebres.hither.session

import com.tomdebres.hither.core.SignInFailure
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.AdminVerdict
import com.tomdebres.hither.core.rules.SignInRequest
import com.tomdebres.hither.data.AdminStorage
import com.tomdebres.hither.data.KeptAdmin
import com.tomdebres.hither.data.PinHash
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections

/**
 * The TV's admin account (DECISIONS 92) on the JVM, with a store in memory and a scripted server:
 * signed in, its rights read live, kept only once the PIN is set, and nothing kept otherwise.
 */
class AdminGatewayTest {
    private class Memory : AdminStorage {
        var admin: KeptAdmin? = null
        var cleared = 0
        var failOnSave = false
        override suspend fun admin(): KeptAdmin? = admin
        override suspend fun save(admin: KeptAdmin) {
            if (failOnSave) throw IOException("the Keystore will not seal")
            this.admin = admin
        }
        override suspend fun clear() {
            cleared++
            admin = null
        }
    }

    private val store = Memory()
    private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val family = User(id = 8, displayName = "User 8", permissions = 32)
    private val request = SignInRequest("http://seerr.test:5055", "approver@example.invalid", "approver-password")
    private var me = """{"id":9,"displayName":"Approver","permissions":16}"""
    private var password = "approver-password"

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, cookie: String? = null) =
        respond(body, status, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.SetCookie to listOfNotNull(cookie)))

    private fun gateway() = AdminGateway(store, clock = { 1_000L }, hash = { PinHash("c2FsdA==", 1, "hash-of-$it") }, engine = {
        MockEngine { call: HttpRequestData ->
            val path = call.url.encodedPath.removePrefix("/api/v1")
            seen += "${call.method.value} $path"
            when (path) {
                "/auth/local" ->
                    if (call.text().contains("\"password\":\"$password\"")) {
                        json("""{"id":9,"permissions":0}""", cookie = "connect.sid=s%3Aapprover; Path=/; HttpOnly")
                    } else {
                        json("""{"message":"Access denied."}""", HttpStatusCode.Forbidden)
                    }
                "/auth/me" -> json(me)
                "/auth/logout" -> json("""{"status":"ok"}""")
                else -> json("""{"message":"Not Found"}""", HttpStatusCode.NotFound)
            }
        }
    })

    private fun HttpRequestData.text(): String = when (val sent = body) {
        is io.ktor.http.content.TextContent -> sent.text
        is io.ktor.http.content.OutgoingContent.ByteArrayContent -> String(sent.bytes())
        else -> ""
    }

    @Test
    fun anApproverIsHeldUntilThePinIsSetAndThenKeptWithItsHashOnly() = runTest {
        val admin = gateway()
        assertEquals(AdminSignIn.Accepted(AdminVerdict.APPROVER, "Approver"), admin.signIn(request, family))
        assertNull("nothing kept before the PIN", store.admin)
        assertTrue(admin.setPin("4821"))
        val kept = store.admin!!
        assertEquals(9, kept.userId)
        assertEquals("Approver", kept.displayName)
        assertEquals("approver@example.invalid", kept.email)
        assertEquals("the password, so Tom types it once", "approver-password", kept.password)
        assertEquals("hash-of-4821", kept.pin.hash)
        assertEquals(listOf("connect.sid"), kept.cookies.map { it.name })
        assertEquals(1_000L, kept.signedInAt)
        assertFalse("never the credentials in its text", kept.toString().contains("approver-password") || kept.toString().contains("4821"))
        assertEquals(AdminSummary("Approver"), admin.kept())
        assertFalse("the session kept is not signed out", seen.contains("POST /auth/logout"))
    }

    @Test
    fun anAdministratorIsAcceptedAndSaidSo() = runTest {
        me = """{"id":1,"displayName":"Owner","permissions":2}"""
        assertEquals(AdminSignIn.Accepted(AdminVerdict.ADMINISTRATOR, "Owner"), gateway().signIn(request, family))
    }

    @Test
    fun anAccountThatCannotApproveIsSignedOutAndNothingKept() = runTest {
        me = """{"id":9,"displayName":"Approver","permissions":32}"""
        val admin = gateway()
        assertEquals(AdminSignIn.Refused(AdminVerdict.NO_RIGHT), admin.signIn(request, family))
        assertTrue(seen.contains("POST /auth/logout"))
        assertFalse("no PIN can keep it", admin.setPin("4821"))
        assertNull(store.admin)
    }

    @Test
    fun theFamilysOwnAccountIsSignedOutAndNothingKept() = runTest {
        me = """{"id":8,"displayName":"User 8","permissions":16}"""
        val admin = gateway()
        assertEquals(AdminSignIn.Refused(AdminVerdict.FAMILY), admin.signIn(request, family))
        assertTrue(seen.contains("POST /auth/logout"))
        assertFalse(admin.setPin("4821"))
        assertNull(store.admin)
    }

    @Test
    fun aRefusedPasswordReadsAsTheFamilysWouldAndNothingIsKept() = runTest {
        password = "another"
        val admin = gateway()
        assertEquals(AdminSignIn.Failed(SignInFailure.WRONG_CREDENTIALS), admin.signIn(request, family))
        assertFalse(admin.setPin("4821"))
        assertNull(store.admin)
    }

    @Test
    fun cancellingBeforeThePinSignsOutAndKeepsNothing() = runTest {
        val admin = gateway()
        admin.signIn(request, family)
        admin.cancel()
        assertTrue(seen.contains("POST /auth/logout"))
        assertFalse(admin.setPin("4821"))
        assertNull(store.admin)
    }

    @Test
    fun aPinThatIsNotFourDigitsKeepsNothing() = runTest {
        val admin = gateway()
        admin.signIn(request, family)
        assertFalse(admin.setPin("482"))
        assertNull(store.admin)
    }

    @Test
    fun aStoreThatWillNotSealKeepsNothing() = runTest {
        store.failOnSave = true
        val admin = gateway()
        admin.signIn(request, family)
        assertFalse(admin.setPin("4821"))
        assertNull(store.admin)
        assertEquals("anything half-written cleared", 1, store.cleared)
    }

    @Test
    fun removingSignsTheKeptSessionOutAndForgetsEverything() = runTest {
        val admin = gateway()
        admin.signIn(request, family)
        admin.setPin("4821")
        seen.clear()
        admin.remove()
        assertEquals(listOf("POST /auth/logout"), seen)
        assertNull(store.admin)
        assertEquals(1, store.cleared)
        assertNull(admin.kept())
    }
}
