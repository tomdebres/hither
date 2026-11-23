package com.tomdebres.hither.core

import com.tomdebres.hither.core.model.MediaType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlinx.io.IOException

/** Settings › Diagnostics' log (M6, DECISIONS 107): what the client records, and what it never keeps. */
class DiagnosticsTest {
    private var clock = 1_000L
    private val log = FailureLog(now = { clock++ })
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val client = SeerrClient(
        MockEngine { request ->
            when (request.url.encodedPath) {
                "/seerr/api/v1/movie/1" -> respond("""{"message":"Boom."}""", HttpStatusCode.InternalServerError, json)
                "/seerr/api/v1/search" -> respond("<html>a proxy page</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                "/seerr/api/v1/movie/2" -> throw IOException("Failed to connect to seerr.test/10.0.0.9:5055")
                "/seerr/api/v1/movie/3/ratingscombined" -> respond("""{"message":"No ratings found."}""", HttpStatusCode.NotFound, json)
                else -> respond("""{"message":"Not found."}""", HttpStatusCode.NotFound, json)
            }
        },
        "http://seerr.test:5055/seerr",
        AcceptAllCookiesStorage(),
        log,
    )

    @Test
    fun `an error status - an unreadable answer and no answer are kept - newest first - by method and API path`() = runTest {
        runCatching { client.movie(1) }
        runCatching { client.search("my secret words") }
        runCatching { client.movie(2) }
        val kept = log.failures.value
        assertEquals(listOf("Network (IOException)", "Unexpected answer", "HTTP 500"), kept.map { it.what })
        assertEquals(listOf("GET /movie/2", "GET /search", "GET /movie/1"), kept.map { "${it.method} ${it.path}" })
        assertEquals(listOf(1002L, 1001L, 1000L), kept.map { it.atMillis })
        val everything = kept.joinToString { it.toString() }
        assertTrue(listOf("seerr.test", "10.0.0.9", "secret", "Boom", "proxy").none { it in everything }, "no address, no query, no message: $everything")
    }

    @Test
    fun `an answer the caller expects is no failure - and fifty are kept`() = runTest {
        assertEquals(null, client.ratings(MediaType.MOVIE, 3))
        assertEquals(emptyList<Failure>(), log.failures.value)
        repeat(DIAGNOSTICS_KEPT + 5) { runCatching { client.movie(1) } }
        assertEquals(DIAGNOSTICS_KEPT, log.failures.value.size)
        assertEquals(1000L + DIAGNOSTICS_KEPT + 4, log.failures.value.first().atMillis)
        log.clear()
        assertEquals(emptyList<Failure>(), log.failures.value)
    }

    @Test
    fun `a path is logged from after the API's root - without its query`() {
        assertEquals("/movie/1", loggedPath("/movie/1"))
        assertEquals("/search", loggedPath("/base/api/v1/search?query=x&page=1"))
        assertEquals("/", loggedPath("/api/v1"))
        assertEquals("/settings/public", loggedPath("/api/v1/settings/public#x"))
    }
}
