package com.tomdebres.hither.ui.discover

import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.model.LibraryPage
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.TvDetails
import com.tomdebres.hither.core.rules.DiscoverRow
import com.tomdebres.hither.session.SeerrAccess
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Recently Added through the real client against a scripted server (M6, DECISIONS 99): the library's
 * records, each title's details read for its card and kept for the session, so a second read asks for
 * the records only; a title whose details fail is left out.
 */
class RecentlyAddedSourceTest {
    private val asked = CopyOnWriteArrayList<String>()
    private var library = listOf(
        MediaInfo(id = 1, mediaType = "movie", tmdbId = 11, status = 5, mediaAddedAt = "2026-11-02T00:00:00.000Z"),
        MediaInfo(id = 2, mediaType = "tv", tmdbId = 22, status = 4, mediaAddedAt = "2026-11-01T00:00:00.000Z"),
        MediaInfo(id = 3, mediaType = "movie", tmdbId = 33, status = 5, mediaAddedAt = "2026-10-01T00:00:00.000Z"),
    )

    private val access = object : SeerrAccess {
        private val client = SeerrClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                asked += path
                val json = SeerrClient.json
                when {
                    path == "/api/v1/media" -> respond(json.encodeToString(LibraryPage.serializer(), LibraryPage(results = library)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    path == "/api/v1/movie/33" -> respond("""{"message":"Unable to retrieve movie."}""", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json"))
                    path.startsWith("/api/v1/movie/") -> {
                        val id = path.substringAfterLast('/').toInt()
                        respond(json.encodeToString(MovieDetails.serializer(), MovieDetails(id = id, title = "Film $id", overview = "About $id.")), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                    path.startsWith("/api/v1/tv/") -> {
                        val id = path.substringAfterLast('/').toInt()
                        respond(json.encodeToString(TvDetails.serializer(), TvDetails(id = id, name = "Show $id")), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                    else -> respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            },
            "http://seerr.test",
            AcceptAllCookiesStorage(),
        )

        override suspend fun <T> call(call: suspend (SeerrClient) -> T): T = call(client)
    }

    @Test
    fun `records become cards under their titles' names, a failed title left out, details read once a session`() = runBlocking {
        val source = seerrSource(access)
        val first = source.page(DiscoverRow.RECENTLY_ADDED, 1)
        assertEquals(listOf("Film 11", "Show 22"), first.cards().map { it.title })
        assertEquals(listOf("Available", "Partially Available"), first.cards().map { it.badge?.text })
        assertEquals("About 11.", first.cards().first().overview)
        assertEquals("/api/v1/media", asked.first())
        assertEquals(setOf("/api/v1/media", "/api/v1/movie/11", "/api/v1/tv/22", "/api/v1/movie/33"), asked.toSet())
        assertEquals(4, asked.size)

        // the series came into the library whole: the next read takes its new status, asking only for the records
        asked.clear()
        library = library.map { if (it.tmdbId == 22) it.copy(status = 5) else it }
        val second = source.page(DiscoverRow.RECENTLY_ADDED, 1)
        assertEquals(listOf("Available", "Available"), second.cards().map { it.badge?.text })
        assertEquals("details kept; the failed one asked again", listOf("/api/v1/media", "/api/v1/movie/33"), asked.toList())
    }
}
