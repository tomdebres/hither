package com.tomdebres.hither.core

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Plex's link code and Quick Connect (M8) against scripted plex.tv and Seerr answers, on the test's virtual clock:
 * what plex.tv is sent, the pace of the looks, the backoff and giving up, the code running out, and Cancel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CodeSignInTest {
    private val plexSeen = mutableListOf<HttpRequestData>()
    private val seerrSeen = mutableListOf<Pair<HttpRequestData, String>>()
    private val plexLooks = mutableListOf<Long>()

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, vararg setCookie: String) =
        respond(
            content = body,
            status = status,
            headers = headersOf(
                HttpHeaders.ContentType to listOf("application/json; charset=utf-8"),
                HttpHeaders.SetCookie to setCookie.toList(),
            ),
        )

    private fun TestScope.plex(answer: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        PlexTv(
            MockEngine { request ->
                // A wait that never ends (no deadline) fails here rather than filling the heap.
                check(plexSeen.size < LOOKS_AT_MOST) { "plex.tv asked $LOOKS_AT_MOST times: the wait does not end" }
                plexSeen += request
                if (request.method == HttpMethod.Get) plexLooks += currentTime
                answer(request)
            },
            clientIdentifier = "client-1",
            baseUrl = "https://plex.test",
        )

    private fun seerr(answer: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        SeerrClient(
            MockEngine { request ->
                check(seerrSeen.size < LOOKS_AT_MOST) { "Seerr asked $LOOKS_AT_MOST times: the wait does not end" }
                seerrSeen += request to request.body.toByteArray().decodeToString()
                answer(request)
            },
            "http://seerr.test:5055",
            AcceptAllCookiesStorage(),
        )

    private companion object {
        /** More looks than any test here needs (the 10-minute wait makes 299). */
        const val LOOKS_AT_MOST = 1_000
    }

    private val me = """{"id":14,"email":"plex-member@example.invalid","displayName":"Plex Member","permissions":32}"""

    private fun pin(token: String? = null, expiresIn: Int = 900) =
        """{"id":4000000001,"code":"X7K6","expiresIn":$expiresIn,"authToken":${token?.let { "\"$it\"" } ?: "null"}}"""

    private inline fun <reified E : SeerrException> failsWith(block: () -> Unit): E {
        try {
            block()
        } catch (e: SeerrException) {
            if (e is E) return e
            fail("expected ${E::class.simpleName}, got ${e::class.simpleName}: ${e.message}")
        }
        fail("expected ${E::class.simpleName}, nothing was thrown")
    }

    // ---- Plex -----------------------------------------------------------------------------------------------

    @Test
    fun `a new code asks plex-tv with the product and the client id - and nothing else of Hither's`() = runTest {
        val link = PlexSignIn(plex { json(pin(), HttpStatusCode.Created) }, PollPace.STANDARD, testTimeSource)
        val code = link.newCode()
        assertEquals("X7K6", code.code)
        assertEquals(900, code.lifeSeconds)
        val request = plexSeen.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://plex.test/api/v2/pins", request.url.toString())
        assertEquals("Hither", request.headers["X-Plex-Product"])
        assertEquals("client-1", request.headers["X-Plex-Client-Identifier"])
        // No other X-Plex header (no device, platform or version), no cookie, no Seerr session.
        assertEquals(listOf("x-plex-client-identifier", "x-plex-product"), request.headers.names().map { it.lowercase() }.filter { it.startsWith("x-plex") }.sorted())
        assertFalse(request.headers.contains(HttpHeaders.Cookie))
        assertFalse(request.headers.contains(HttpHeaders.Authorization))
    }

    @Test
    fun `the link waits every 2 s until the code is entered - then hands the token to Seerr's auth plex`() = runTest {
        var looks = 0
        val plexTv = plex { request ->
            if (request.method == HttpMethod.Post) json(pin(), HttpStatusCode.Created)
            else json(pin(token = if (++looks >= 3) "plex-token-1" else null))
        }
        val seerr = seerr { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/plex" -> json("""{"id":14}""", setCookie = arrayOf("connect.sid=s%3Aplex; Path=/; HttpOnly"))
                "/api/v1/auth/me" -> json(me)
                else -> error("unexpected ${request.url}")
            }
        }
        val link = PlexSignIn(plexTv, PollPace.STANDARD, testTimeSource)
        val code = link.newCode()
        val user = link.signIn(code, seerr)
        assertEquals(14, user.id)
        assertEquals(listOf(2_000L, 4_000L, 6_000L), plexLooks)
        assertEquals("https://plex.test/api/v2/pins/4000000001", plexSeen.last().url.toString())
        assertEquals("client-1", plexSeen.last().headers["X-Plex-Client-Identifier"])
        val (handOver, body) = seerrSeen.first()
        assertEquals("/api/v1/auth/plex", handOver.url.encodedPath)
        assertEquals("""{"authToken":"plex-token-1"}""", body)
        assertEquals("/api/v1/auth/me", seerrSeen.last().first.url.encodedPath)
    }

    @Test
    fun `failed looks back off 4 - 8 - 16 - 30 s - and the fifth in a row gives up as plex-tv unreachable`() = runTest {
        val plexTv = plex { request ->
            if (request.method == HttpMethod.Post) json(pin(), HttpStatusCode.Created) else throw IOException("no route")
        }
        val link = PlexSignIn(plexTv, PollPace.STANDARD, testTimeSource)
        val code = link.newCode()
        failsWith<SeerrException.PlexUnreachable> { link.token(code) }
        // the first look at 2 s, then 4, 8, 16 and 30 s after each failure
        assertEquals(listOf(2_000L, 6_000L, 14_000L, 30_000L, 60_000L), plexLooks)
    }

    @Test
    fun `an answer puts the pace back - so failures must come in a row to give up`() = runTest {
        var looks = 0
        val plexTv = plex { request ->
            when {
                request.method == HttpMethod.Post -> json(pin(), HttpStatusCode.Created)
                ++looks == 5 -> json(pin())
                looks == 9 -> json(pin(token = "late-token"))
                looks < 9 -> json("""<html>busy</html>""", HttpStatusCode.ServiceUnavailable)
                else -> error("looked again after the token")
            }
        }
        val link = PlexSignIn(plexTv, PollPace.STANDARD, testTimeSource)
        assertEquals("late-token", link.token(link.newCode()))
        // 4 failures (2, 4, 8, 16 s waits), an answer (30 s wait), then 2 s again and 4 more failures' waits
        assertEquals(listOf(2_000L, 6_000L, 14_000L, 30_000L, 60_000L, 62_000L, 66_000L, 74_000L, 90_000L), plexLooks)
    }

    @Test
    fun `the code runs out after plex-tv's expiresIn - and no look is made after it`() = runTest {
        val plexTv = plex { request ->
            if (request.method == HttpMethod.Post) json(pin(expiresIn = 10), HttpStatusCode.Created) else json(pin())
        }
        val link = PlexSignIn(plexTv, PollPace.STANDARD, testTimeSource)
        val code = link.newCode()
        assertEquals(10, code.lifeSeconds)
        failsWith<SeerrException.CodeExpired> { link.token(code) }
        assertEquals(listOf(2_000L, 4_000L, 6_000L, 8_000L), plexLooks)
    }

    @Test
    fun `plex-tv forgetting the code is its expiry at once`() = runTest {
        val plexTv = plex { request ->
            if (request.method == HttpMethod.Post) json(pin(), HttpStatusCode.Created)
            else json("""{"errors":[{"code":1020,"message":"Code not found or expired"}]}""", HttpStatusCode.NotFound)
        }
        val link = PlexSignIn(plexTv, PollPace.STANDARD, testTimeSource)
        failsWith<SeerrException.CodeExpired> { link.token(link.newCode()) }
        assertEquals(1, plexLooks.size)
    }

    @Test
    fun `Cancel stops the looks - nothing more is asked of plex-tv`() = runTest {
        val third = CompletableDeferred<Unit>()
        val plexTv = plex { request ->
            if (request.method == HttpMethod.Post) json(pin(), HttpStatusCode.Created)
            else json(pin()).also { if (plexLooks.size == 3) third.complete(Unit) }
        }
        val link = PlexSignIn(plexTv, PollPace.STANDARD, testTimeSource)
        val code = link.newCode()
        val waiting = async { link.token(code) }
        third.await()
        waiting.cancel()
        // two minutes on: without the cancel that is many more looks
        delay(120_000)
        assertTrue(waiting.isCancelled)
        assertEquals(3, plexLooks.size)
    }

    @Test
    fun `no answer for a new code - or not plex-tv's - is plex-tv unreachable`() = runTest {
        failsWith<SeerrException.PlexUnreachable> { PlexSignIn(plex { throw IOException("down") }, PollPace.STANDARD, testTimeSource).newCode() }
        failsWith<SeerrException.PlexUnreachable> { PlexSignIn(plex { json("<html>portal</html>") }, PollPace.STANDARD, testTimeSource).newCode() }
        failsWith<SeerrException.PlexUnreachable> {
            PlexSignIn(plex { json("""{"error":"x"}""", HttpStatusCode.TooManyRequests) }, PollPace.STANDARD, testTimeSource).newCode()
        }
    }

    @Test
    fun `a PIN never shows its token when printed`() {
        val text = com.tomdebres.hither.core.model.PlexPin(1, "ABCD", 900, authToken = "secret-token").toString()
        assertFalse("secret-token" in text)
    }

    // ---- Quick Connect ----------------------------------------------------------------------------------------

    private val secret = "7F".repeat(32) // made up, built so it never reads as a key

    @Test
    fun `Quick Connect shows Seerr's code - waits every 2 s for approval - then signs in with the secret`() = runTest {
        var checks = 0
        val seerr = seerr { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/jellyfin/quickconnect/initiate" -> json("""{"code":"525562","secret":"$secret"}""")
                "/api/v1/auth/jellyfin/quickconnect/check" -> json("""{"authenticated":${++checks >= 2}}""")
                "/api/v1/auth/jellyfin/quickconnect/authenticate" -> json("""{"id":12}""", setCookie = arrayOf("connect.sid=s%3Aqc; Path=/"))
                "/api/v1/auth/me" -> json("""{"id":12,"displayName":"Robin","permissions":32}""")
                else -> error("unexpected ${request.url}")
            }
        }
        val qc = QuickConnectSignIn(seerr, PollPace.STANDARD, testTimeSource, 600_000)
        val code = qc.newCode()
        assertEquals("525562", code.code)
        assertEquals(600, code.lifeSeconds)
        assertFalse(secret in code.toString())
        val user = qc.signIn(code)
        assertEquals("Robin", user.displayName)
        assertEquals(4_000L, currentTime)
        val check = seerrSeen.first { it.first.url.encodedPath.endsWith("/check") }.first
        assertEquals(secret, check.url.parameters["secret"])
        val (authenticate, body) = seerrSeen.first { it.first.url.encodedPath.endsWith("/authenticate") }
        assertEquals(HttpMethod.Post, authenticate.method)
        assertEquals("""{"secret":"$secret"}""", body)
    }

    @Test
    fun `Seerr's 404 on the check is the code expired - Jellyfin dropped it`() = runTest {
        val seerr = seerr { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/jellyfin/quickconnect/initiate" -> json("""{"code":"525562","secret":"$secret"}""")
                else -> json("""{"message":"Failed to check Quick Connect status"}""", HttpStatusCode.NotFound)
            }
        }
        val qc = QuickConnectSignIn(seerr, PollPace.STANDARD, testTimeSource, 600_000)
        failsWith<SeerrException.CodeExpired> { qc.signIn(qc.newCode()) }
        assertEquals(1, seerrSeen.count { it.first.url.encodedPath.endsWith("/check") })
    }

    @Test
    fun `a code nobody approves runs out after its 10 minutes`() = runTest {
        val seerr = seerr { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/jellyfin/quickconnect/initiate" -> json("""{"code":"525562","secret":"$secret"}""")
                else -> json("""{"authenticated":false}""")
            }
        }
        val qc = QuickConnectSignIn(seerr)
        val timed = QuickConnectSignIn(seerr, PollPace.STANDARD, testTimeSource, 600_000)
        assertEquals(600, qc.newCode().lifeSeconds)
        failsWith<SeerrException.CodeExpired> { timed.signIn(timed.newCode()) }
        assertEquals(600_000L, currentTime)
        assertEquals(299, seerrSeen.count { it.first.url.encodedPath.endsWith("/check") })
    }

    @Test
    fun `Seerr not answering the check five times in a row gives up with its failure`() = runTest {
        val seerr = seerr { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/jellyfin/quickconnect/initiate" -> json("""{"code":"525562","secret":"$secret"}""")
                else -> throw IOException("gone")
            }
        }
        val qc = QuickConnectSignIn(seerr, PollPace.STANDARD, testTimeSource, 600_000)
        failsWith<SeerrException.Unreachable> { qc.signIn(qc.newCode()) }
        assertEquals(60_000L, currentTime)
    }

    @Test
    fun `the pace doubles to its longest and no further`() {
        val pace = PollPace.STANDARD
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (0..5).map { pace.waitMillis(it) })
    }
}
