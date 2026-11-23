package com.tomdebres.hither.core

import com.tomdebres.hither.core.model.PlexPin
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * plex.tv's PIN routes, for Plex's link-code sign-in (M8): a 4-character code the person types at plex.tv/link,
 * and the token plex.tv gives once they have. A real third party: Hither calls it only while a Plex sign-in is on
 * screen, and sends it exactly two things besides the routes' own ids, the product name ([PRODUCT]) and
 * [clientIdentifier] (`docs/privacy.md`). Nothing is logged here, and no cookie is kept or sent.
 *
 * [baseUrl] is [PLEX_TV] in every release build; debug and test builds may point it at the fake plex.tv that
 * `scripts/fake-seerr` serves (`/plextv`). A redirect is reported, never followed.
 */
class PlexTv(
    engine: HttpClientEngine,
    /** Made once per install and kept (`newPlexClientIdentifier`): plex.tv lists the device by it. */
    val clientIdentifier: String,
    val baseUrl: String,
) {
    private val api = baseUrl.trimEnd('/') + "/api/v2"

    private val http = HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
        }
    }

    /**
     * A new link code (`POST /api/v2/pins`, without `strong`: the short code plex.tv/link takes).
     * [SeerrException.PlexUnreachable] when plex.tv does not answer, or not as plex.tv does.
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun newPin(): PlexPin = read(exchange(HttpMethod.Post, "/pins"))

    /**
     * The PIN [id] as it stands (`GET /api/v2/pins/{id}`): its `authToken` once the person has entered the code.
     * [SeerrException.CodeExpired] when plex.tv no longer knows it (its 404); [SeerrException.PlexUnreachable] for
     * no answer or any other.
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun pin(id: Long): PlexPin {
        val response = exchange(HttpMethod.Get, "/pins/$id")
        if (response.status.value == 404) throw SeerrException.CodeExpired()
        return read(response)
    }

    fun close() = http.close()

    private suspend fun exchange(method: HttpMethod, path: String): HttpResponse = try {
        http.request(api + path) {
            this.method = method
            accept(ContentType.Application.Json)
            header("X-Plex-Product", PRODUCT)
            header("X-Plex-Client-Identifier", clientIdentifier)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SeerrException.PlexUnreachable(e)
    }

    private suspend fun read(response: HttpResponse): PlexPin {
        if (response.status.value !in 200..299) throw SeerrException.PlexUnreachable()
        return try {
            SeerrClient.json.decodeFromString(PlexPin.serializer(), response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SeerrException.PlexUnreachable(e)
        }
    }

    companion object {
        /** The real plex.tv: the only address a release build uses. */
        const val PLEX_TV = "https://plex.tv"

        /** The product name plex.tv shows the person ("Hither" under their authorised devices). */
        const val PRODUCT = "Hither"
    }
}

/**
 * A new client identifier for plex.tv: random, made once per install and kept by the app (Android keeps it with the
 * app's own data; it goes with "clear data" and an uninstall). Not a secret: plex.tv lists the device by it.
 */
@OptIn(ExperimentalUuidApi::class)
fun newPlexClientIdentifier(): String = Uuid.random().toString()
