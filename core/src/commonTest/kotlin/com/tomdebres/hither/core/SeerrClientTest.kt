package com.tomdebres.hither.core

import com.tomdebres.hither.core.model.RequestBody
import com.tomdebres.hither.core.rules.ImageSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.test.Test
import kotlinx.io.IOException

/** The client against a scripted server: what it sends, and how it reads each kind of answer. */
class SeerrClientTest {
    private val base = "http://seerr.test:5055"
    private val seen = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()
    private val cookies = AcceptAllCookiesStorage()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): SeerrClient {
        val engine = MockEngine { request ->
            seen += request
            bodies += request.body.toByteArray().decodeToString()
            handler(request)
        }
        return SeerrClient(engine, base, cookies)
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, vararg setCookie: String) =
        respond(
            content = body,
            status = status,
            headers = headersOf(
                HttpHeaders.ContentType to listOf("application/json; charset=utf-8"),
                HttpHeaders.SetCookie to setCookie.toList(),
            ),
        )

    private inline fun <reified E : SeerrException> failsWith(block: () -> Unit): E {
        try {
            block()
        } catch (e: SeerrException) {
            if (e is E) return e
            fail("expected ${E::class.simpleName}, got ${e::class.simpleName}")
        }
        fail("expected ${E::class.simpleName}, nothing was thrown")
        throw AssertionError()
    }

    // ---- where the pictures come from (DECISIONS 382) ------------------------------------------------

    @Test
    fun `the pictures follow each settings read's cacheImages - the server's proxy until the first`() = runTest {
        var caching: Boolean? = false
        val engine = MockEngine { request ->
            assertEquals("/api/v1/settings/public", request.url.encodedPath)
            json(if (caching == null) """{"initialized":true}""" else """{"cacheImages":$caching}""")
        }
        val seerr = SeerrClient(engine, base, cookies, tmdbImages = "http://10.0.2.2:5056/tmdb")
        assertEquals("$base/imageproxy/tmdb", seerr.images.base, "before the server has said: its own proxy")
        assertTrue(seerr.images.fromServer)

        seerr.publicSettings()
        assertEquals("http://10.0.2.2:5056/tmdb", seerr.images.base, "caching off: the TMDB address the client was given")
        assertEquals(false, seerr.images.fromServer)

        caching = true
        seerr.publicSettings()
        assertEquals("$base/imageproxy/tmdb", seerr.images.base, "the owner turned caching on: the proxy again")

        caching = null
        seerr.publicSettings()
        assertEquals("http://10.0.2.2:5056/tmdb", seerr.images.base, "a server that does not say keeps no copies, as Seerr's web reads it")
    }

    @Test
    fun `a failed settings read leaves the pictures where they were`() = runTest {
        var answer = """{"cacheImages":false}"""
        val seerr = client { json(answer) }
        seerr.publicSettings()
        assertEquals(ImageSource.TMDB_IMAGES, seerr.images.base)
        answer = "<html>not Seerr</html>"
        failsWith<SeerrException.NotSeerr> { seerr.publicSettings() }
        assertEquals(ImageSource.TMDB_IMAGES, seerr.images.base)
    }

    // ---- approve and decline (M5, DECISIONS 94) -----------------------------------------------------

    private val noUser = """{"status":403,"error":"You do not have permission to access this endpoint"}"""

    @Test
    fun `approve and decline post to Seerr's route and return the request as saved`() = runTest {
        val seerr = client { request ->
            when (request.url.encodedPath) {
                "/api/v1/request/31/approve" -> json("""{"id":31,"status":2,"modifiedBy":{"id":9,"displayName":"Approver"}}""")
                "/api/v1/request/32/decline" -> json("""{"id":32,"status":3}""")
                else -> error("unexpected ${request.url}")
            }
        }
        val approved = seerr.decide(31, approve = true)
        assertEquals(2, approved.status)
        assertEquals(9, approved.modifiedBy?.id)
        assertEquals(HttpMethod.Post, seen[0].method)
        assertEquals(3, seerr.decide(32, approve = false).status)
        assertEquals(listOf("/api/v1/request/31/approve", "/api/v1/request/32/decline"), seen.map { it.url.encodedPath })
    }

    @Test
    fun `the route's 403 with the session alive is a refusal - not an ended session`() = runTest {
        val seerr = client { request ->
            when (request.url.encodedPath) {
                "/api/v1/request/31/approve" -> json(noUser, HttpStatusCode.Forbidden)
                "/api/v1/auth/me" -> json(Fixtures.text("auth-me"))
                else -> error("unexpected ${request.url}")
            }
        }
        failsWith<SeerrException.Refused> { seerr.decide(31, approve = true) }
        assertEquals(listOf("/api/v1/request/31/approve", "/api/v1/auth/me"), seen.map { it.url.encodedPath }, "asked who it is to tell the two apart")
    }

    @Test
    fun `the route's 403 with the session gone is an ended session - nothing done`() = runTest {
        val seerr = client { request -> json(noUser, HttpStatusCode.Forbidden) }
        failsWith<SeerrException.SignedOut> { seerr.decide(31, approve = false) }
    }

    @Test
    fun `a request no longer pending is NotPending - and one not there is a 404`() = runTest {
        val seerr = client { request ->
            when (request.url.encodedPath) {
                "/api/v1/request/31/approve" -> json("""{"message":"Only pending requests can be approved or declined."}""", HttpStatusCode.Conflict)
                else -> json("""{"message":"Request not found."}""", HttpStatusCode.NotFound)
            }
        }
        failsWith<SeerrException.NotPending> { seerr.decide(31, approve = true) }
        val missing = failsWith<SeerrException.Http> { seerr.decide(99, approve = true) }
        assertEquals(404, missing.status)
    }

    // ---- sign-in and the session ---------------------------------------------------------------

    @Test
    fun `sign-in posts the credentials - keeps the cookie and returns auth me's user`() = runTest {
        val seerr = client { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/local" -> json(Fixtures.text("auth-local"), setCookie = arrayOf("connect.sid=s%3Aabc; Path=/; HttpOnly"))
                "/api/v1/auth/me" -> json(Fixtures.text("auth-me"))
                else -> error("unexpected ${request.url}")
            }
        }
        val user = seerr.signIn("someone@example.invalid", "p\"a\\ss wörd")

        assertEquals(HttpMethod.Post, seen[0].method)
        assertEquals("$base/api/v1/auth/local", seen[0].url.toString())
        assertEquals("""{"email":"someone@example.invalid","password":"p\"a\\ss wörd"}""", bodies[0])
        assertEquals("application/json", seen[0].body.contentType?.toString())
        // the user is auth/me's, with the real permissions, not the sign-in response's zero
        assertEquals("$base/api/v1/auth/me", seen[1].url.toString())
        assertEquals(67744, user.permissions)
        // and the session cookie went back with that second call
        assertEquals("connect.sid=s%3Aabc", seen[1].headers[HttpHeaders.Cookie])
        assertEquals(listOf("connect.sid"), cookies.get(Url(base)).map { it.name })
    }

    @Test
    fun `a wrong password is Refused with the server's words - and reads as wrong credentials`() = runTest {
        val seerr = client { json(Fixtures.text("error-403-sign-in"), HttpStatusCode.Forbidden) }
        val error = failsWith<SeerrException.Refused> { seerr.signIn("someone@example.invalid", "nope") }
        assertEquals("Access denied.", error.serverMessage)
        assertEquals(SignInFailure.WRONG_CREDENTIALS, signInFailure(error))
        assertEquals(1, seen.size) // auth/me is not asked after a refusal
        assertFalse(error.message!!.contains("nope"))
    }

    @Test
    fun `a 403 after an accepted sign-in is not a refusal of the credentials`() = runTest {
        val seerr = client { request ->
            if (request.url.encodedPath == "/api/v1/auth/local") json(Fixtures.text("auth-local"), setCookie = arrayOf("connect.sid=abc; Path=/"))
            else json("""{"message":"Forbidden"}""", HttpStatusCode.Forbidden)
        }
        val error = failsWith<SeerrException.Http> { seerr.signIn("someone@example.invalid", "right") }
        assertEquals(403, error.status)
        assertEquals(SignInFailure.OTHER, signInFailure(error))
    }

    @Test
    fun `no session is SignedOut`() = runTest {
        val seerr = client { json(Fixtures.text("error-401"), HttpStatusCode.Unauthorized) }
        val error = failsWith<SeerrException.SignedOut> { seerr.me() }
        assertEquals("cookie 'connect.sid' required", error.message)
    }

    // ---- Seerr's own 403: the server no longer knows the session cookie ------------------------

    /**
     * What Seerr's `isAuthenticated` answers when no user is behind the cookie sent
     * (`server/middleware/auth.ts` at 3f7d5c3): its sessions were lost, or the user removed.
     */
    private val sessionForgotten = """{"status":403,"error":"You do not have permission to access this endpoint"}"""

    @Test
    fun `Seerr's own 403 is SignedOut on every call that needs the session - and nothing is retried`() = runTest {
        val seerr = client { json(sessionForgotten, HttpStatusCode.Forbidden) }
        failsWith<SeerrException.SignedOut> { seerr.me() }
        failsWith<SeerrException.SignedOut> { seerr.trending() }
        failsWith<SeerrException.SignedOut> { seerr.popularMovies() }
        failsWith<SeerrException.SignedOut> { seerr.popularSeries() }
        failsWith<SeerrException.SignedOut> { seerr.upcomingMovies() }
        failsWith<SeerrException.SignedOut> { seerr.search("a") }
        failsWith<SeerrException.SignedOut> { seerr.movie(603) }
        failsWith<SeerrException.SignedOut> { seerr.tv(1399) }
        failsWith<SeerrException.SignedOut> { seerr.requests() }
        val error = failsWith<SeerrException.SignedOut> { seerr.request(RequestBody("movie", 603)) }
        assertEquals("You do not have permission to access this endpoint", error.message)
        assertEquals(10, seen.size)
    }

    @Test
    fun `any other 403 is still Refused - a proxy's page - other JSON - the same words in another shape`() = runTest {
        val others = listOf(
            "text/html" to "<html><head><title>403 Forbidden</title></head><body><center><h1>403 Forbidden</h1></center><hr><center>nginx</center></body></html>",
            "text/plain" to "You do not have permission to access this endpoint",
            "application/json" to """{"message":"Forbidden"}""",
            "application/json" to """{"message":"You do not have permission to access this endpoint"}""",
            "application/json" to """{"error":"You do not have permission to access this endpoint"}""",
            "application/json" to """{"status":"403","error":"You do not have permission to access this endpoint"}""",
            "application/json" to """{"status":403,"error":"You do not have permission to access this endpoint."}""",
            "application/json" to """{"status":403,"error":"Forbidden"}""",
            "application/json" to """[{"status":403,"error":"You do not have permission to access this endpoint"}]""",
            // POST /request's own refusals, as Seerr's error handler words them (its CSRF check is
            // read on its own: CsrfRefused, DECISIONS 70)
            "application/json" to """{"message":"You do not have permission to make 4K movie requests."}""",
            "application/json" to """{"error":"invalid csrf token"}""",
            "text/plain" to "invalid csrf token",
            "application/json" to "",
        )
        for ((type, body) in others) {
            val seerr = client { respond(body, HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, type)) }
            try {
                seerr.trending()
                fail("expected Refused for $body")
            } catch (e: SeerrException) {
                assertTrue(e is SeerrException.Refused, "$body gave ${e::class.simpleName}")
            }
        }
    }

    @Test
    fun `Seerr's CSRF refusal is not a refused password - at sign-in or on a request`() = runTest {
        val csrf = """{"message":"invalid csrf token"}"""
        val seerr = client { json(csrf, HttpStatusCode.Forbidden) }
        // over http: the CSRF check, and why
        assertTrue(failsWith<SeerrException.CsrfRefused> { seerr.signIn("someone@example.invalid", "right") }.overHttp)
        assertTrue(failsWith<SeerrException.CsrfRefused> { seerr.request(RequestBody("movie", 1)) }.overHttp)
        assertEquals(SignInFailure.CSRF_NEEDS_HTTPS, signInFailure(SeerrException.CsrfRefused(overHttp = true)))
        // over https the same refusal is something else, and not said to be about https
        val secure = SeerrClient(MockEngine { respond(csrf, HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "application/json")) }, "https://seerr.test", AcceptAllCookiesStorage())
        assertFalse(failsWith<SeerrException.CsrfRefused> { secure.signIn("someone@example.invalid", "right") }.overHttp)
        assertEquals(SignInFailure.OTHER, signInFailure(SeerrException.CsrfRefused(overHttp = false)))
    }

    @Test
    fun `a 403 on auth local is a refused password - even in the session's words`() = runTest {
        val seerr = client { json(sessionForgotten, HttpStatusCode.Forbidden) }
        val error = failsWith<SeerrException.Refused> { seerr.signIn("someone@example.invalid", "nope") }
        assertEquals(SignInFailure.WRONG_CREDENTIALS, signInFailure(error))
        assertEquals(1, seen.size)
    }

    @Test
    fun `Seerr's own 403 on the auth me after an accepted sign-in is the session not taking - not a refused password`() = runTest {
        val seerr = client { request ->
            if (request.url.encodedPath == "/api/v1/auth/local") json(Fixtures.text("auth-local"), setCookie = arrayOf("connect.sid=abc; Path=/"))
            else json(sessionForgotten, HttpStatusCode.Forbidden)
        }
        val error = failsWith<SeerrException.SignedOut> { seerr.signIn("someone@example.invalid", "right") }
        assertEquals(SignInFailure.OTHER, signInFailure(error))
        assertEquals(2, seen.size)
    }

    @Test
    fun `password sign-in switched off on the server is told apart`() = runTest {
        val seerr = client { json("""{"error":"Password sign-in is disabled."}""", HttpStatusCode.InternalServerError) }
        val error = failsWith<SeerrException.Http> { seerr.signIn("a@example.invalid", "b") }
        assertEquals(SignInFailure.LOCAL_LOGIN_DISABLED, signInFailure(error))
        assertEquals(SignInFailure.OTHER, signInFailure(SeerrException.Http(500, "Unable to authenticate.")))
    }

    @Test
    fun `with CSRF protection on - the token cookie is echoed on writes and not on reads`() = runTest {
        cookies.addCookie(Url(base), Cookie("XSRF-TOKEN", "tok123", path = "/"))
        val seerr = client { request ->
            if (request.method == HttpMethod.Get) json(Fixtures.text("auth-me")) else json(Fixtures.text("auth-local"))
        }
        seerr.signIn("a@example.invalid", "b")
        assertEquals("tok123", seen[0].headers["X-XSRF-TOKEN"])
        assertNull(seen[1].headers["X-XSRF-TOKEN"])
    }

    // ---- reading ---------------------------------------------------------------------------------

    @Test
    fun `each read goes to its own path under the one base URL`() = runTest {
        val page = Fixtures.text("discover-trending")
        val seerr = client { request ->
            when (request.url.encodedPath) {
                "/api/v1/settings/public" -> json(Fixtures.text("settings-public"))
                "/api/v1/movie/603" -> json(Fixtures.text("movie"))
                "/api/v1/tv/1399" -> json(Fixtures.text("tv"))
                "/api/v1/request" -> json(Fixtures.text("request-list"))
                else -> json(page)
            }
        }
        assertTrue(seerr.publicSettings().localLogin)
        seerr.trending()
        seerr.popularMovies(page = 2)
        seerr.popularSeries()
        seerr.upcomingMovies()
        seerr.movie(603)
        seerr.tv(1399)
        assertEquals(3, seerr.requests(take = 10, skip = 20, requestedBy = 8).results.size)

        assertEquals(
            listOf(
                "/api/v1/settings/public",
                "/api/v1/discover/trending?page=1",
                "/api/v1/discover/movies?page=2",
                "/api/v1/discover/tv?page=1",
                "/api/v1/discover/movies/upcoming?page=1",
                "/api/v1/movie/603",
                "/api/v1/tv/1399",
                "/api/v1/request?filter=all&take=10&skip=20&sort=added&requestedBy=8",
            ),
            seen.map { it.url.toString().removePrefix(base) },
        )
        assertTrue(seen.all { it.method == HttpMethod.Get && it.url.host == "seerr.test" && it.url.port == 5055 })
    }

    @Test
    fun `a search query is percent-encoded in full`() = runTest {
        val seerr = client { json(Fixtures.text("search")) }
        seerr.search("Tom & Jerry + friends?")
        assertEquals(
            "/api/v1/search?query=Tom%20%26%20Jerry%20%2B%20friends%3F&page=1",
            seen.single().url.toString().removePrefix(base),
        )
    }

    // ---- requesting --------------------------------------------------------------------------------

    @Test
    fun `a request is posted once - as built - and the created request comes back`() = runTest {
        val created = Fixtures.read<com.tomdebres.hither.core.model.RequestPage>("request-list").results.first()
        val seerr = client {
            json(SeerrClient.json.encodeToString(com.tomdebres.hither.core.model.MediaRequest.serializer(), created), HttpStatusCode.Created)
        }
        val result = seerr.request(RequestBody("tv", 1399, is4k = false, seasons = listOf(2, 3)))

        assertEquals(1, seen.size)
        assertEquals(HttpMethod.Post, seen[0].method)
        assertEquals("$base/api/v1/request", seen[0].url.toString())
        assertEquals("""{"mediaType":"tv","mediaId":1399,"is4k":false,"seasons":[2,3]}""", bodies[0])
        assertEquals(created.id, result.id)
        assertEquals(created.media!!.status, result.media!!.status)
    }

    @Test
    fun `202 is not a request - nothing was left to ask for`() = runTest {
        val seerr = client { json("""{"message":"No seasons available to request"}""", HttpStatusCode.Accepted) }
        val error = failsWith<SeerrException.NothingToRequest> { seerr.request(RequestBody("tv", 1399, seasons = listOf(1))) }
        assertEquals("No seasons available to request", error.message)
    }

    @Test
    fun `409 is AlreadyRequested - a quota 403 is Refused with the server's reason`() = runTest {
        var status = HttpStatusCode.Conflict
        val seerr = client { json("""{"message":"Request for this media already exists."}""", status) }
        failsWith<SeerrException.AlreadyRequested> { seerr.request(RequestBody("movie", 603)) }
        status = HttpStatusCode.Forbidden
        val refused = failsWith<SeerrException.Refused> { seerr.request(RequestBody("movie", 603)) }
        assertEquals("Request for this media already exists.", refused.serverMessage)
        assertEquals(2, seen.size) // one send per call: the client never retries a write
    }

    // ---- things that are not Seerr -----------------------------------------------------------------

    @Test
    fun `a page that is not Seerr's JSON is NotSeerr`() = runTest {
        val seerr = client { respond("<html><title>Sign in</title></html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) }
        val error = failsWith<SeerrException.NotSeerr> { seerr.publicSettings() }
        assertEquals(SignInFailure.UNREACHABLE, signInFailure(error))
    }

    @Test
    fun `a redirect is reported - not followed to another host`() = runTest {
        val seerr = client { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://login.elsewhere.test/")) }
        failsWith<SeerrException.NotSeerr> { seerr.publicSettings() }
        assertEquals(1, seen.size)
    }

    @Test
    fun `no answer is Unreachable - and other statuses keep their number`() = runTest {
        val down = client { throw IOException("connection refused") }
        val unreachable = failsWith<SeerrException.Unreachable> { down.publicSettings() }
        assertEquals(SignInFailure.UNREACHABLE, signInFailure(unreachable))

        val broken = client { json("""{"message":"boom"}""", HttpStatusCode.BadGateway) }
        val http = failsWith<SeerrException.Http> { broken.trending() }
        assertEquals(502, http.status)
        assertEquals("boom", http.serverMessage)
    }

    @Test
    fun `a connection that drops while the body is read is Unreachable - not a raw exception`() = runTest {
        val seerr = client {
            respond(
                content = ByteReadChannel(ByteArray(0)).also { it.cancel(IOException("connection reset")) },
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        failsWith<SeerrException.Unreachable> { seerr.trending() }
    }

    @Test
    fun `a base URL with a path keeps it`() = runTest {
        val engine = MockEngine { request -> seen += request; json(Fixtures.text("settings-public")) }
        SeerrClient(engine, "https://example.test/seerr/", cookies).publicSettings()
        assertEquals("https://example.test/seerr/api/v1/settings/public", seen.single().url.toString())
    }

    // ---- M8: signing in through the media server (Seerr's answers as probed at v3.5.2) ------------------------

    private fun mediaSignIn(status: HttpStatusCode, body: String) = client { request ->
        when (request.url.encodedPath) {
            "/api/v1/auth/jellyfin", "/api/v1/auth/plex", "/api/v1/auth/jellyfin/quickconnect/authenticate",
            "/api/v1/auth/jellyfin/quickconnect/initiate" -> json(body, status)
            else -> error("asked ${request.url} after a refusal")
        }
    }

    @Test
    fun `Jellyfin and Emby sign-in posts the username as Seerr's form does - and returns auth me's user`() = runTest {
        val seerr = client { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/jellyfin" -> json("""{"id":12}""", setCookie = arrayOf("connect.sid=s%3Ajf; Path=/; HttpOnly"))
                "/api/v1/auth/me" -> json("""{"id":12,"displayName":"robin","permissions":32}""")
                else -> error("unexpected ${request.url}")
            }
        }
        val user = seerr.signInWithMediaServer("robin", "p\"w")
        assertEquals("$base/api/v1/auth/jellyfin", seen[0].url.toString())
        assertEquals("""{"username":"robin","password":"p\"w","email":"robin"}""", bodies[0])
        assertEquals("connect.sid=s%3Ajf", seen[1].headers[HttpHeaders.Cookie])
        assertEquals(32, user.permissions)
    }

    @Test
    fun `a wrong Jellyfin password is Seerr's 401 INVALID_CREDENTIALS - wrong credentials - never an ended session`() = runTest {
        val error = failsWith<SeerrException.Refused> { mediaSignIn(HttpStatusCode.Unauthorized, """{"message":"INVALID_CREDENTIALS"}""").signInWithMediaServer("robin", "nope") }
        assertEquals(SignInFailure.WRONG_CREDENTIALS, signInFailure(error))
        assertEquals(1, seen.size)
        // any other 401 on these routes is not "signed out" either: they need no session
        val other = failsWith<SeerrException.Http> { mediaSignIn(HttpStatusCode.Unauthorized, """{"message":"Unauthorized"}""").signInWithMediaServer("robin", "x") }
        assertEquals(401, other.status)
    }

    @Test
    fun `Seerr's Access denied to a media-server account is not imported - for Jellyfin - Plex and Quick Connect`() = runTest {
        val denied = """{"message":"Access denied."}"""
        val jellyfin = failsWith<SeerrException.NotImported> { mediaSignIn(HttpStatusCode.Forbidden, denied).signInWithMediaServer("sasha", "right") }
        assertEquals(SignInFailure.NOT_IMPORTED, signInFailure(jellyfin))
        val plex = failsWith<SeerrException.NotImported> { mediaSignIn(HttpStatusCode.Forbidden, denied).signInWithPlex("token") }
        assertEquals(SignInFailure.NOT_IMPORTED, signInFailure(plex))
        failsWith<SeerrException.NotImported> { mediaSignIn(HttpStatusCode.Forbidden, denied).finishQuickConnect("ABCDEF0123") }
        // while on auth/local the same words stay a wrong password
        val local = failsWith<SeerrException.Refused> { client { json(denied, HttpStatusCode.Forbidden) }.signIn("a@b.c", "x") }
        assertEquals(SignInFailure.WRONG_CREDENTIALS, signInFailure(local))
    }

    @Test
    fun `a way in switched off is said as such`() = runTest {
        val plexOff = failsWith<SeerrException.SignInOff> { mediaSignIn(HttpStatusCode.InternalServerError, """{"error":"Plex login is disabled"}""").signInWithPlex("t") }
        assertEquals(SignInFailure.METHOD_OFF, signInFailure(plexOff))
        failsWith<SeerrException.SignInOff> { mediaSignIn(HttpStatusCode.InternalServerError, """{"error":"Jellyfin login is disabled"}""").signInWithMediaServer("robin", "x") }
        val emby = failsWith<SeerrException.SignInOff> {
            mediaSignIn(HttpStatusCode.Forbidden, """{"message":"Quick Connect is only supported by Jellyfin."}""").startQuickConnect()
        }
        assertEquals(SignInFailure.METHOD_OFF, signInFailure(emby))
    }

    @Test
    fun `Seerr not reaching Jellyfin is the media server unreachable - whichever status it came with`() = runTest {
        val down = failsWith<SeerrException.MediaServerUnreachable> {
            mediaSignIn(HttpStatusCode.BadGateway, """{"message":"CONNECTION_ERROR"}""").signInWithMediaServer("robin", "x")
        }
        assertEquals(SignInFailure.MEDIA_SERVER_UNREACHABLE, signInFailure(down))
        failsWith<SeerrException.MediaServerUnreachable> { mediaSignIn(HttpStatusCode.NotFound, """{"message":"INVALID_URL"}""").signInWithMediaServer("robin", "x") }
    }

    @Test
    fun `Quick Connect that will not start - and Plex's token refused by plex-tv - each have their words`() = runTest {
        val qc = failsWith<SeerrException.Http> {
            mediaSignIn(HttpStatusCode.InternalServerError, """{"message":"Failed to initiate quick connect."}""").startQuickConnect()
        }
        assertEquals(SignInFailure.QUICK_CONNECT_UNAVAILABLE, signInFailure(qc))
        val plex = failsWith<SeerrException.Http> { mediaSignIn(HttpStatusCode.InternalServerError, """{"message":"Unable to authenticate."}""").signInWithPlex("t") }
        assertEquals(SignInFailure.OTHER, signInFailure(plex))
    }

    @Test
    fun `the expired and plex-tv failures read as their own`() {
        assertEquals(SignInFailure.CODE_EXPIRED, signInFailure(SeerrException.CodeExpired()))
        assertEquals(SignInFailure.PLEX_UNREACHABLE, signInFailure(SeerrException.PlexUnreachable()))
    }

    @Test
    fun `the server's version is read from status without a session`() = runTest {
        val seerr = client { json("""{"version":"3.5.2","commitTag":"18a90b4","updateAvailable":false}""") }
        assertEquals("3.5.2", seerr.status().version)
        assertEquals("$base/api/v1/status", seen.single().url.toString())
    }
}
