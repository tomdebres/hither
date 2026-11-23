package com.tomdebres.hither.session

import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.rules.PinCheck
import com.tomdebres.hither.core.rules.PinTries
import com.tomdebres.hither.data.AdminStorage
import com.tomdebres.hither.data.KeptAdmin
import com.tomdebres.hither.data.PinHash
import com.tomdebres.hither.data.SavedCookie
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections

/**
 * Switching to the TV's admin and back (DECISIONS 93) on the JVM, with a store in memory, a clock the
 * test sets and a scripted server: the PIN and its kept tries, the session opened, renewed from the
 * password when due or ended, the rights read live, the idle clock, closing, and calls as the admin.
 */
class AdminSwitchGatewayTest {
    private class Memory(var admin: KeptAdmin?) : AdminStorage {
        var clears = 0
        override suspend fun admin() = admin
        override suspend fun save(admin: KeptAdmin) {
            this.admin = admin
        }
        override suspend fun clear() {
            clears++
            admin = null
        }
    }

    private val day = 24L * 60 * 60 * 1000
    private var now = 1_000_000_000_000L
    private val pinHash = PinHash.of("5632", iterations = 1_000)
    private val store = Memory(
        KeptAdmin(
            "http://seerr.test:5055", "approver@example.invalid", "approver-password", 9, "Approver", pinHash,
            // as Ktor keeps a Set-Cookie it parsed: the value as sent, RAW
            cookies = listOf(SavedCookie("connect.sid", "s%3Akept", encoding = "RAW", path = "/")),
            signedInAt = now - day,
        ),
    )
    private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var me = """{"id":9,"displayName":"Approver","permissions":16}"""
    private var keptSessionLive = true
    private var password = "approver-password"
    private var down = false
    private var meGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, cookie: String? = null) =
        respond(body, status, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.SetCookie to listOfNotNull(cookie)))

    private val gateway = AdminGateway(store, clock = { now }, engine = {
        MockEngine { call: HttpRequestData ->
            val path = call.url.encodedPath.removePrefix("/api/v1")
            val cookie = call.headers[HttpHeaders.Cookie].orEmpty()
            seen += "${call.method.value} $path ${if ("s%3Akept" in cookie) "kept" else if ("s%3Afresh" in cookie) "fresh" else "none"}"
            if (down) throw java.io.IOException("no route")
            // a test can hold auth/me's answer, to act while the switch is out
            if (path == "/auth/me") meGate?.await()
            when (path) {
                "/settings/public" -> json("""{"initialized":true,"applicationTitle":"Seerr","localLogin":true}""")
                "/auth/local" ->
                    if ((call.body as? TextContent)?.text?.contains("\"password\":\"$password\"") == true) json("""{"id":9}""", cookie = "connect.sid=s%3Afresh; Path=/")
                    else json("""{"message":"Access denied."}""", HttpStatusCode.Forbidden)
                "/auth/me" -> when {
                    "s%3Afresh" in cookie || ("s%3Akept" in cookie && keptSessionLive) -> json(me)
                    else -> json("""{"status":403,"error":"You do not have permission to access this endpoint"}""", HttpStatusCode.Forbidden)
                }
                "/request/count" -> json("""{"pending":2}""")
                else -> json("""{"status":"ok"}""")
            }
        }
    })

    @Test
    fun theRightPinSwitchesInOnTheKeptSessionAndShowsWhoItIs() = runTest {
        val answer = gateway.switchIn("5632")
        assertEquals(AdminSwitch.In(ActiveAdmin(9, "Approver")), answer)
        assertEquals(ActiveAdmin(9, "Approver"), gateway.active.value)
        assertEquals("the kept session, its rights read live, no sign-in", listOf("GET /auth/me kept"), seen)
    }

    @Test
    fun aWrongPinTouchesNoServerAndIsKept() = runTest {
        assertEquals(AdminSwitch.Pin(PinCheck.Wrong(left = 9, waitMs = 0)), gateway.switchIn("0000"))
        assertEquals(1, store.admin!!.tries.wrong)
        assertNull(gateway.active.value)
        assertTrue("nothing asked of the server", seen.isEmpty())
        gateway.switchIn("5632")
        assertEquals("the right PIN clears the row", PinTries(), store.admin!!.tries)
    }

    @Test
    fun aWaitIsKeptAndNothingIsTriedDuringIt() = runTest {
        store.admin = store.admin!!.copy(tries = PinTries(wrong = 5, lockedFromMs = now, lockedUntilMs = now + 30_000))
        assertEquals(AdminSwitch.Pin(PinCheck.Wait(30_000)), gateway.switchIn("5632"))
        assertNull(gateway.active.value)
        now += 30_000
        assertTrue(gateway.switchIn("5632") is AdminSwitch.In)
    }

    @Test
    fun theTenthWrongPinRemovesTheAccountAndEndsItsSession() = runTest {
        store.admin = store.admin!!.copy(tries = PinTries(wrong = 9))
        assertEquals(AdminSwitch.Pin(PinCheck.Removed), gateway.switchIn("0000"))
        assertNull(store.admin)
        assertTrue(seen.contains("POST /auth/logout kept"))
    }

    @Test
    fun aSessionPast25DaysIsSignedInAgainFromThePasswordFirst() = runTest {
        store.admin = store.admin!!.copy(signedInAt = now - 26 * day)
        assertTrue(gateway.switchIn("5632") is AdminSwitch.In)
        assertTrue("signed in again", seen.any { it.startsWith("POST /auth/local") })
        assertEquals(now, store.admin!!.signedInAt)
        assertEquals("the new session kept", listOf("s%3Afresh"), store.admin!!.cookies.map { it.value })
    }

    @Test
    fun aSessionTheServerHasForgottenIsSignedInAgainOnce() = runTest {
        keptSessionLive = false
        assertTrue(gateway.switchIn("5632") is AdminSwitch.In)
        assertEquals(1, seen.count { it.startsWith("POST /auth/local") })
    }

    @Test
    fun aPasswordChangedOnTheServerRemovesTheAccount() = runTest {
        keptSessionLive = false
        password = "changed"
        assertEquals(AdminSwitch.PasswordRefused, gateway.switchIn("5632"))
        assertNull(store.admin)
        assertNull(gateway.active.value)
    }

    @Test
    fun anAccountThatHasLostManageRequestsIsKeptAndNotSwitchedTo() = runTest {
        me = """{"id":9,"displayName":"Approver","permissions":32}"""
        assertEquals(AdminSwitch.NoRight, gateway.switchIn("5632"))
        assertNull(gateway.active.value)
        assertTrue(store.admin != null)
    }

    @Test
    fun aServerNotAnsweringChangesNothing() = runTest {
        down = true
        assertEquals(AdminSwitch.Failed, gateway.switchIn("5632"))
        assertNull(gateway.active.value)
        assertTrue(store.admin != null)
    }

    @Test
    fun tenIdleMinutesGoBackAndAKeyPutsTheClockBack() = runTest {
        gateway.switchIn("5632")
        now += 9 * 60_000
        gateway.key()
        now += 9 * 60_000
        assertFalse("18 minutes in, 9 since the last key", gateway.idleCheck())
        assertTrue(gateway.active.value != null)
        now += 60_000
        assertTrue(gateway.idleCheck())
        assertNull(gateway.active.value)
        assertTrue("the session is kept for next time", store.admin!!.cookies.isNotEmpty())
    }

    @Test
    fun leavingTheFrontAndSwitchBackEndIt() = runTest {
        gateway.switchIn("5632")
        gateway.closed()
        assertNull(gateway.active.value)
        gateway.switchIn("5632")
        gateway.back()
        assertNull(gateway.active.value)
        assertTrue(store.admin != null)
    }

    @Test
    fun leavingTheFrontWhileTheSwitchIsOutKeepsTheFamily() = runTest {
        meGate = kotlinx.coroutines.CompletableDeferred()
        val switching = async { gateway.switchIn("5632") }
        // the PIN is right and the session is being opened when Home is pressed
        while (seen.none { it.startsWith("GET /auth/me") }) kotlinx.coroutines.yield()
        gateway.closed()
        meGate!!.complete(Unit)
        assertEquals(AdminSwitch.Closed, switching.await())
        assertNull("a closed Hither is the family's", gateway.active.value)
    }

    @Test
    fun callsAsTheAdminNeedItActiveAndCarryItsSession() = runTest {
        try {
            gateway.asAdmin { it.requestCount() }
            fail("called as the admin while not active")
        } catch (_: SeerrException.SignedOut) {
        }
        gateway.switchIn("5632")
        seen.clear()
        assertEquals(2, gateway.asAdmin { it.requestCount() }.pending)
        assertEquals(listOf("GET /request/count kept"), seen)
        gateway.back()
        try {
            gateway.asAdmin { it.requestCount() }
            fail("called as the admin after Switch Back")
        } catch (_: SeerrException.SignedOut) {
        }
    }
}
