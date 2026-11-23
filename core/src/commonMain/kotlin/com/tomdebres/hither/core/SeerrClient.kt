// Written for Hither. The edge cases it handles were learned from SeerrTV's
// data/SeerrApiService.kt (MIT, see THIRD-PARTY.md): the CSRF cookie pair, the 202 that is not
// a request, and never trusting the sign-in response for the user's permissions.
package com.tomdebres.hither.core

import com.tomdebres.hither.core.model.DiscoverQuery
import com.tomdebres.hither.core.model.DiscoverSlider
import com.tomdebres.hither.core.model.Genre
import com.tomdebres.hither.core.model.Issue
import com.tomdebres.hither.core.model.IssueBody
import com.tomdebres.hither.core.model.IssuePage
import com.tomdebres.hither.core.model.Language
import com.tomdebres.hither.core.model.LibraryPage
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.CollectionDetails
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PersonCredits
import com.tomdebres.hither.core.model.PersonDetails
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.QuickConnectCode
import com.tomdebres.hither.core.model.QuickConnectState
import com.tomdebres.hither.core.model.ServerStatus
import com.tomdebres.hither.core.model.Quota
import com.tomdebres.hither.core.model.Ratings
import com.tomdebres.hither.core.model.RequestBody
import com.tomdebres.hither.core.model.RequestCount
import com.tomdebres.hither.core.model.RequestPage
import com.tomdebres.hither.core.model.RtRating
import com.tomdebres.hither.core.model.TvDetails
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.model.WatchProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * A Seerr server's API, for one base URL (from [ServerAddress.normalise]).
 *
 * The app supplies the HTTP [engine] and the [cookies] storage, which holds the session
 * (`connect.sid`) between launches; this module names neither OkHttp nor Android. Every call
 * goes to [baseUrl] and nowhere else, redirects to other hosts included. Failures surface as
 * [SeerrException]; nothing here logs, so no credential or cookie can reach a log from it.
 *
 * Every call is `@Throws(SeerrException::class, CancellationException::class)`: in Swift (the
 * `HitherCore` framework, M7) that is what `try await` catches, and anything else thrown would end
 * the app. So each failure here becomes a [SeerrException]; `scripts/core-is-portable` checks that a
 * new call says so too.
 */
class SeerrClient(
    engine: HttpClientEngine,
    val baseUrl: String,
    private val cookies: CookiesStorage,
    /** Where failures are recorded for Settings › Diagnostics (M6, DECISIONS 107); none, nothing is kept. */
    private val failures: FailureLog? = null,
) {
    private val api = "${baseUrl.trimEnd('/')}/api/v1"

    private val http = HttpClient(engine) {
        expectSuccess = false
        // A redirect could carry the session cookie's request to another host; a Seerr API
        // call has no reason to be redirected, so one is reported instead of followed.
        followRedirects = false
        install(HttpCookies) { storage = cookies }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
        }
    }

    /** What the server says about itself; needs no session. The first call to any address. */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun publicSettings(): PublicSettings = get("/settings/public")

    /**
     * Signs in with a local account. The session cookie lands in the cookie storage; the user
     * returned is `auth/me`'s, because the sign-in response's own `permissions` is not the
     * account's real value.
     *
     * [SeerrException.Refused] from here means one thing: the server refused these
     * credentials, which is any 403 on `auth/local`, whatever its body. The `auth/me` that
     * follows an accepted sign-in can fail too, and that is never read as a refusal: the new
     * session not taking (a 401, or Seerr's own 403) is [SeerrException.SignedOut], and any
     * other 403 there (a proxy, a server in an odd state) is [SeerrException.Http], so that no
     * caller forgets a good password over it.
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun signIn(email: String, password: String): User {
        send(HttpMethod.Post, "/auth/local", exchange = Exchange.LOCAL_SIGN_IN) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SignInBody.serializer(), SignInBody(email, password)))
        }
        return meAfterSignIn()
    }

    // ---- M8: signing in through the media server -----------------------------------------------------

    /** The server's Seerr version (`GET /status`, no session needed): Quick Connect needs 3.4.0 or later. */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun status(): ServerStatus = get("/status")

    /**
     * Signs in with a Jellyfin or Emby username and password (`POST /auth/jellyfin`), as Seerr's own form sends them:
     * the username goes as the email too, which Seerr uses only when it makes the account a new user. Seerr checks
     * them with its media server and sets the session cookie; the user returned is `auth/me`'s.
     *
     * Failures: [SeerrException.Refused] the username or password is wrong (Seerr's 401 `INVALID_CREDENTIALS`);
     * [SeerrException.NotImported] Seerr does not hold the account and may not make it a user;
     * [SeerrException.SignInOff] the server has this sign-in off; [SeerrException.MediaServerUnreachable] Seerr
     * could not reach Jellyfin or Emby. A 401 here is never [SeerrException.SignedOut]: the route needs no session.
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun signInWithMediaServer(username: String, password: String): User {
        send(HttpMethod.Post, "/auth/jellyfin", exchange = Exchange.MEDIA_SIGN_IN) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(MediaServerBody.serializer(), MediaServerBody(username, password, email = username)))
        }
        return meAfterSignIn()
    }

    /**
     * Starts Jellyfin's Quick Connect through Seerr (`POST /auth/jellyfin/quickconnect/initiate`, Seerr 3.4.0+): the
     * code to show and the secret to ask with. Seerr's 500 "Failed to initiate quick connect." means Jellyfin would
     * not start one (Quick Connect switched off there, or not reached): [SeerrException.Http] with those words.
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun startQuickConnect(): QuickConnectCode =
        decode(send(HttpMethod.Post, "/auth/jellyfin/quickconnect/initiate", exchange = Exchange.MEDIA_SIGN_IN), QuickConnectCode.serializer())

    /**
     * Whether the person has approved [secret]'s code in Jellyfin yet (`GET /auth/jellyfin/quickconnect/check`).
     * [SeerrException.CodeExpired] once Jellyfin has dropped the code (Seerr passes on its 404), as Seerr's own page
     * reads it.
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun quickConnectApproved(secret: String): Boolean {
        val response = try {
            send(HttpMethod.Get, "/auth/jellyfin/quickconnect/check", exchange = Exchange.MEDIA_SIGN_IN, expected = NOT_FOUND) {
                parameter("secret", secret)
            }
        } catch (e: SeerrException.Http) {
            if (e.status == 404) throw SeerrException.CodeExpired()
            throw e
        }
        return decode(response, QuickConnectState.serializer()).authenticated
    }

    /**
     * Signs in with an approved Quick Connect code (`POST /auth/jellyfin/quickconnect/authenticate`): the session
     * cookie lands as with a password, and the user returned is `auth/me`'s. [SeerrException.NotImported] as for
     * [signInWithMediaServer].
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun finishQuickConnect(code: SignInCode): User = finishQuickConnect(code.secret)

    internal suspend fun finishQuickConnect(secret: String): User {
        send(HttpMethod.Post, "/auth/jellyfin/quickconnect/authenticate", exchange = Exchange.MEDIA_SIGN_IN) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SecretBody.serializer(), SecretBody(secret)))
        }
        return meAfterSignIn()
    }

    /**
     * Signs in with a Plex token from plex.tv's link code (`POST /auth/plex`): Seerr asks plex.tv whose token it is
     * and whether that account may use the server's Plex, keeps the token as the user's own, and sets the session.
     * Hither hands the token over once and keeps nothing of it. [SeerrException.NotImported] for Seerr's 403 "Access
     * denied." (not imported, or no access to the server's Plex); [SeerrException.SignInOff] for "Plex login is
     * disabled"; Seerr's 500 "Unable to authenticate." (plex.tv refused the token, or Seerr could not reach plex.tv)
     * is [SeerrException.Http].
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun signInWithPlex(linked: PlexLinked): User = signInWithPlex(linked.token)

    internal suspend fun signInWithPlex(authToken: String): User {
        send(HttpMethod.Post, "/auth/plex", exchange = Exchange.MEDIA_SIGN_IN) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(PlexBody.serializer(), PlexBody(authToken)))
        }
        return meAfterSignIn()
    }

    /**
     * `auth/me` after a sign-in the server accepted. A 403 there is never a refusal of the credentials: the new
     * session not taking is [SeerrException.SignedOut], and any other 403 is [SeerrException.Http], so that no
     * caller forgets a good password over it.
     */
    private suspend fun meAfterSignIn(): User = try {
        me()
    } catch (e: SeerrException.Refused) {
        throw SeerrException.Http(403, e.serverMessage)
    }

    /** The signed-in user. [SeerrException.SignedOut] when the session is missing or over. */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun me(): User = get("/auth/me")

    /** Ends the session on the server. The app clears its cookie storage as well. */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun signOut() {
        send(HttpMethod.Post, "/auth/logout")
    }

    @Throws(SeerrException::class, CancellationException::class)
    suspend fun trending(page: Int = 1): Page<MediaResult> = discover("/discover/trending", page)
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun popularMovies(page: Int = 1): Page<MediaResult> = discover("/discover/movies", page)
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun popularSeries(page: Int = 1): Page<MediaResult> = discover("/discover/tv", page)
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun upcomingMovies(page: Int = 1): Page<MediaResult> = discover("/discover/movies/upcoming", page)
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun upcomingSeries(page: Int = 1): Page<MediaResult> = discover("/discover/tv/upcoming", page)

    /** Films, series and people matching [query]; the caller drops the people. */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun search(query: String, page: Int = 1): Page<MediaResult> =
        get("/search") {
            // Seerr's request validator refuses reserved characters in the query value, and
            // Ktor would write a space as "+". So the value is percent-encoded here in full:
            // "%20" for a space, "%26" for "&".
            url.encodedParameters.append("query", query.encodeURLParameter())
            parameter("page", page)
        }

    @Throws(SeerrException::class, CancellationException::class)
    suspend fun movie(tmdbId: Int): MovieDetails = get("/movie/$tmdbId")
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun tv(tmdbId: Int): TvDetails = get("/tv/$tmdbId")

    /** A collection and its films (`GET /collection/{id}`), for the collection card's grid (polish 2). */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun collection(collectionId: Int): CollectionDetails = get("/collection/$collectionId")

    /**
     * Sends a request. Build [body] with `movieRequestBody` or `tvRequestBody`, which are what
     * keep a request to what was chosen and permitted. Throws
     * [SeerrException.NothingToRequest] for the server's 202 and
     * [SeerrException.AlreadyRequested] for its 409. The caller sends once per press.
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun request(body: RequestBody): MediaRequest {
        val response = send(HttpMethod.Post, "/request") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RequestBody.serializer(), body))
        }
        if (response.status == HttpStatusCode.Accepted) {
            throw SeerrException.NothingToRequest(serverMessage(text(response)))
        }
        return decode(response, MediaRequest.serializer())
    }

    /**
     * The requests list, newest first: by when each was asked for ([sort] "added"), or by when each
     * last changed ("modified"). A user without the right to see others' requests gets their own
     * whatever [requestedBy] says; pass their id to get the same on any account.
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun requests(
        filter: String = "all",
        take: Int = 20,
        skip: Int = 0,
        requestedBy: Int? = null,
        sort: String = "added",
    ): RequestPage = get("/request") {
        parameter("filter", filter)
        parameter("take", take)
        parameter("skip", skip)
        parameter("sort", sort)
        if (requestedBy != null) parameter("requestedBy", requestedBy)
    }

    /**
     * The server's requests counted by status (`GET /request/count`), everyone's. Seerr guards the
     * route with a session only, so any signed-in user may ask; Hither asks only for an account that
     * holds MANAGE_REQUESTS, and shows the pending count to it alone (M4).
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun requestCount(): RequestCount = get("/request/count")

    /**
     * Approves ([approve]) or declines a pending request (`POST /request/{id}/approve|decline`, Seerr's
     * route guarded by Manage Requests; M5, DECISIONS 94). The request as Seerr saved it.
     *
     * The one route Hither calls that is guarded by a permission, so its 403 needs reading (55's caveat):
     * Seerr answers the same body, "You do not have permission to access this endpoint", for a session
     * it no longer knows and for a signed-in account without Manage Requests. So that 403 is followed by
     * `auth/me`: if the session answers, the account lacks the right ([SeerrException.Refused], nothing to
     * renew); if not, the session is over ([SeerrException.SignedOut]: nothing was done, the check runs
     * before the route, so signing in again and asking once more is safe). A 409, "Only pending requests
     * can be approved or declined.", is [SeerrException.NotPending]; a 404 [SeerrException.Http].
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun decide(requestId: Int, approve: Boolean): MediaRequest {
        val word = if (approve) "approve" else "decline"
        val response = try {
            guarded { send(HttpMethod.Post, "/request/$requestId/$word") }
        } catch (e: SeerrException.AlreadyRequested) {
            throw SeerrException.NotPending(e.message)
        }
        return decode(response, MediaRequest.serializer())
    }

    // ---- M6: what the parity items read --------------------------------------------------------------

    /**
     * The server's Discover settings (`GET /settings/discover`, any signed-in user): every slider in its
     * order, hidden ones included (`enabled` false), built-in and custom.
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun discoverSliders(): List<DiscoverSlider> = get("/settings/discover")

    /**
     * Library records (`GET /media`), newest first by when the media server found them: Seerr's Recently
     * Added row asks for `filter=allavailable&take=20&sort=mediaAdded`. Each is a record only: the
     * title's name and pictures are its details (`movie`, `tv`).
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun library(filter: String = "allavailable", sort: String = "mediaAdded", take: Int = 20, skip: Int = 0): LibraryPage =
        get("/media") {
            parameter("filter", filter)
            parameter("take", take)
            parameter("skip", skip)
            parameter("sort", sort)
        }

    @Throws(SeerrException::class, CancellationException::class)
    suspend fun person(personId: Int): PersonDetails = get("/person/$personId")
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun personCredits(personId: Int): PersonCredits = get("/person/$personId/combined_credits")

    /**
     * A title's ratings: Rotten Tomatoes and IMDb for a film (`ratingscombined`), Rotten Tomatoes for a
     * series (`tv/{id}/ratings`). Null where the server found none, which it answers 404.
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun ratings(type: MediaType, tmdbId: Int): Ratings? = try {
        when (type) {
            // Seerr's 404 is "no ratings for this title": an answer, not a failure for Diagnostics.
            MediaType.MOVIE -> get<Ratings>("/movie/$tmdbId/ratingscombined", expected = NOT_FOUND)
            MediaType.TV -> Ratings(rt = get<RtRating>("/tv/$tmdbId/ratings", expected = NOT_FOUND))
        }
    } catch (e: SeerrException.Http) {
        if (e.status == 404) null else throw e
    }

    /** Seerr's "Similar" slider for a title: TMDB's similar titles of the same type, with their records. */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun similar(type: MediaType, tmdbId: Int, page: Int = 1): Page<MediaResult> =
        get("/${type.api}/$tmdbId/similar") { parameter("page", page) }

    /** Seerr's "Recommendations" slider for a title. */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun recommendations(type: MediaType, tmdbId: Int, page: Int = 1): Page<MediaResult> =
        get("/${type.api}/$tmdbId/recommendations") { parameter("page", page) }

    /**
     * Films or series as TMDB's discover gives them for [query] (`GET /discover/movies|tv`): the Movies
     * and Series pages, a keyword's titles, and the custom sliders' rows.
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun discover(type: MediaType, query: DiscoverQuery, page: Int = 1): Page<MediaResult> =
        get(if (type == MediaType.MOVIE) "/discover/movies" else "/discover/tv") {
            query.parameters(type == MediaType.MOVIE).forEach { (name, value) -> parameter(name, value) }
            parameter("page", page)
        }

    /** A studio's films (a custom studio slider). */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun studioMovies(studioId: Int, page: Int = 1): Page<MediaResult> =
        get("/discover/movies/studio/$studioId") { parameter("page", page) }

    /** A network's series (a custom network slider). */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun networkSeries(networkId: Int, page: Int = 1): Page<MediaResult> =
        get("/discover/tv/network/$networkId") { parameter("page", page) }

    @Throws(SeerrException::class, CancellationException::class)
    suspend fun genres(type: MediaType): List<Genre> = get("/genres/${type.api}")
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun languages(): List<Language> = get("/languages")

    /** The streaming services TMDB knows for a type in a country. */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun watchProviders(type: MediaType, region: String): List<WatchProvider> =
        get(if (type == MediaType.MOVIE) "/watchproviders/movies" else "/watchproviders/tv") { parameter("watchRegion", region) }

    /**
     * The account's request limits (`GET /user/{id}/quota`): an account may read its own, which is all
     * Hither asks for.
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun quota(userId: Int): Quota = get("/user/$userId/quota")

    /**
     * Cancels a request (`DELETE /request/{id}`): Seerr removes a user's own request while it is pending
     * and answers 204. It refuses anything else with a **401**, "You do not have permission to delete this
     * request.", which is not an ended session: that one 401 is [SeerrException.Refused], so nobody signs
     * in again over it. 404 is [SeerrException.Http].
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun cancelRequest(requestId: Int) {
        try {
            send(HttpMethod.Delete, "/request/$requestId")
        } catch (e: SeerrException.SignedOut) {
            if (e.message == NOT_YOURS_TO_DELETE) throw SeerrException.Refused(e.message)
            throw e
        }
    }

    /**
     * Issues (`GET /issue`), newest first: open, resolved or all ([filter]). Without View Issues an
     * account gets its own only. Guarded by an issue right, so its 403 is read as [decide]'s is.
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun issues(filter: String = "all", take: Int = 10, skip: Int = 0): IssuePage =
        decode(
            guarded {
                send(HttpMethod.Get, "/issue") {
                    parameter("take", take)
                    parameter("skip", skip)
                    parameter("filter", filter)
                    parameter("sort", "added")
                }
            },
            IssuePage.serializer(),
        )

    /** One issue with its comments (`GET /issue/{id}`), guarded as [issues]. */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun issue(issueId: Int): Issue = decode(guarded { send(HttpMethod.Get, "/issue/$issueId") }, Issue.serializer())

    /**
     * Reports an issue (`POST /issue`, Create Issues): Seerr's 201 with the issue. Guarded as [issues].
     * The caller sends once per press.
     */
    @Throws(SeerrException::class, CancellationException::class)
    suspend fun reportIssue(body: IssueBody): Issue = decode(
        guarded {
            send(HttpMethod.Post, "/issue") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(IssueBody.serializer(), body))
            }
        },
        Issue.serializer(),
    )

    fun close() = http.close()

    /**
     * A call to a route Seerr guards with a permission (`isAuthenticated(Permission.X)`), whose refusal is
     * the same 403 body as a session it no longer knows (55's caveat). That 403 is followed by `auth/me`:
     * if the session answers, the account lacks the right ([SeerrException.Refused], nothing to renew);
     * if not, the session is over ([SeerrException.SignedOut]: nothing was done, the check runs before the
     * route, so signing in again and asking once more is safe).
     */
    private suspend fun guarded(call: suspend () -> HttpResponse): HttpResponse = try {
        call()
    } catch (e: SeerrException.SignedOut) {
        if (e.message != NO_USER) throw e
        try {
            me()
        } catch (_: SeerrException.SignedOut) {
            throw e
        }
        throw SeerrException.Refused(NO_USER)
    }

    private suspend fun discover(path: String, page: Int): Page<MediaResult> =
        get(path) { parameter("page", page) }

    private suspend inline fun <reified T> get(
        path: String,
        expected: Set<Int> = emptySet(),
        noinline configure: HttpRequestBuilder.() -> Unit = {},
    ): T = decode(send(HttpMethod.Get, path, expected = expected, configure = configure), serializer<T>())

    /** What kind of exchange a call is, for reading its failures. */
    private enum class Exchange {
        /** Any call on a session. */
        CALL,

        /** `auth/local`, where a 403 is the credentials refused whatever its body says. */
        LOCAL_SIGN_IN,

        /** The media-server sign-in routes (M8), which need no session: their 401 and 403 are read by their words. */
        MEDIA_SIGN_IN,
    }

    /** One exchange with the server: the request, the CSRF header where needed, the status. */
    private suspend fun send(
        method: HttpMethod,
        path: String,
        exchange: Exchange = Exchange.CALL,
        /** Statuses the caller reads as an answer: not failures for Diagnostics. */
        expected: Set<Int> = emptySet(),
        // Not "build": HttpRequestBuilder has a member of that name, which would be called
        // instead and silently drop the body and the query.
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        val response = try {
            http.request(api + path) {
                this.method = method
                accept(ContentType.Application.Json)
                // With CSRF protection on, Seerr sets an XSRF-TOKEN cookie and wants it echoed
                // in a header on anything that changes state.
                if (method != HttpMethod.Get) {
                    cookies.get(Url(api)).firstOrNull { it.name == XSRF_COOKIE }?.let { header(XSRF_HEADER, it.value) }
                }
                configure(this)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures?.record(FailureKind.NETWORK, method.value, path, cause = e::class.simpleName)
            throw SeerrException.Unreachable(e)
        }
        val status = response.status.value
        if (status in 200..299) return response
        if (status !in expected) failures?.record(FailureKind.HTTP, method.value, path, status = status)
        val body = runCatching { response.bodyAsText() }.getOrNull()
        val message = serverMessage(body)
        if (exchange == Exchange.MEDIA_SIGN_IN) mediaSignInFailure(status, message)?.let { throw it }
        throw when (status) {
            401 -> SeerrException.SignedOut(message)
            403 -> when {
                exchange == Exchange.CALL && isSessionUnknown(body) -> SeerrException.SignedOut(message)
                // Before "refused": on auth/local a CSRF refusal is not a wrong password (DECISIONS 70).
                isCsrfRefusal(body) -> SeerrException.CsrfRefused(overHttp = baseUrl.startsWith("http://", ignoreCase = true))
                else -> SeerrException.Refused(message)
            }
            409 -> SeerrException.AlreadyRequested(message)
            in 300..399 -> SeerrException.NotSeerr()
            else -> SeerrException.Http(status, message)
        }
    }

    /** The body as text. A connection that drops while it is read is "unreachable" like any other. */
    private suspend fun text(response: HttpResponse): String = try {
        response.bodyAsText()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failures?.record(FailureKind.NETWORK, response.request.method.value, response.request.url.encodedPath, cause = e::class.simpleName)
        throw SeerrException.Unreachable(e)
    }

    private suspend fun <T> decode(response: HttpResponse, strategy: DeserializationStrategy<T>): T {
        val text = text(response)
        return try {
            json.decodeFromString(strategy, text)
        } catch (e: SerializationException) {
            unexpected(response)
            throw SeerrException.NotSeerr(e)
        } catch (e: IllegalArgumentException) {
            unexpected(response)
            throw SeerrException.NotSeerr(e)
        }
    }

    private fun unexpected(response: HttpResponse) =
        failures?.record(FailureKind.UNEXPECTED, response.request.method.value, response.request.url.encodedPath, status = response.status.value)

    @Serializable
    private data class SignInBody(val email: String, val password: String)

    @Serializable
    private data class MediaServerBody(val username: String, val password: String, val email: String)

    @Serializable
    private data class SecretBody(val secret: String)

    @Serializable
    private data class PlexBody(val authToken: String)

    companion object {
        private val NOT_FOUND = setOf(404)
        private const val XSRF_COOKIE = "XSRF-TOKEN"
        private const val XSRF_HEADER = "X-XSRF-TOKEN"

        /**
         * How every Seerr response is read: fields this build does not know are ignored, and
         * a null where a default exists takes the default, so a newer server still parses.
         * Requests are written with their defaults (`is4k: false` is sent, not implied) and
         * without nulls (a film request has no `seasons` key).
         */
        val json: Json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
            encodeDefaults = true
        }

        /** The sentence Seerr's `isAuthenticated` answers with; see [isSessionUnknown]. */
        private const val NO_USER = SEERR_NO_PERMISSION

        /** `DELETE /request/{id}`'s refusal, which Seerr sends as a 401 ([cancelRequest]). */
        private const val NOT_YOURS_TO_DELETE = "You do not have permission to delete this request."

        /**
         * Whether a 403's body is Seerr's own word that no user is behind the session cookie
         * sent: `{"status":403,"error":"You do not have permission to access this endpoint"}`,
         * from `isAuthenticated` in `server/middleware/auth.ts` (Seerr 3.x, read at 3f7d5c3).
         * That is how a cookie the server no longer knows is answered (its sessions lost, the
         * user removed): the session is over and nothing was done, because the check runs
         * before any route. Only that JSON with that number and sentence counts; a proxy's
         * page, other JSON and the same words in another shape do not (DECISIONS 33, 55).
         *
         * Seerr answers the same body to a signed-in user who lacks the permission a route is
         * guarded by (`isAuthenticated(Permission.X)`: approving, issues, managing users, settings).
         * Most routes this client calls are guarded by `isAuthenticated()` with no permission,
         * where any signed-in user passes, so there it can only mean the session. The
         * permission-guarded ones ([decide], the issue calls) go through `guarded`, which tells the
         * two apart; a new one must too.
         */
        internal fun isSessionUnknown(body: String?): Boolean {
            if (body.isNullOrBlank()) return false
            val obj = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return false
            val status = obj["status"] as? JsonPrimitive
            val error = obj["error"] as? JsonPrimitive
            return status != null && !status.isString && status.content == "403" &&
                error != null && error.isString && error.content == NO_USER
        }

        /** csurf's refusal, as Seerr's error handler words it: `{"message":"invalid csrf token"}`. */
        private const val CSRF_REFUSAL = "invalid csrf token"

        /**
         * Whether a 403's body is Seerr's CSRF check refusing a write (csurf's error, through Seerr's
         * error handler in `server/index.ts`): exactly that message in a JSON object.
         */
        internal fun isCsrfRefusal(body: String?): Boolean {
            if (body.isNullOrBlank()) return false
            val obj = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return false
            val message = obj["message"] as? JsonPrimitive ?: return false
            return message.isString && message.content.trim().equals(CSRF_REFUSAL, ignoreCase = true)
        }

        /**
         * How Seerr words a media-server sign-in's failure (`server/routes/auth.ts` at v3.5.2, `ApiErrorCode` in
         * `server/constants/error.ts`), whatever the status it came with: Jellyfin's own status is passed on, so a
         * wrong password is a 401 and an address Seerr cannot use a 404. Null for anything else, read as any call's.
         */
        private fun mediaSignInFailure(status: Int, message: String?): SeerrException? = when {
            message == "INVALID_CREDENTIALS" -> SeerrException.Refused(message)
            message == "INVALID_URL" || message == "CONNECTION_ERROR" -> SeerrException.MediaServerUnreachable(message)
            status == 403 && message == "Access denied." -> SeerrException.NotImported(message)
            message != null && (message.endsWith("login is disabled") || message.startsWith("Quick Connect is ")) ->
                SeerrException.SignInOff(message)
            // These routes need no session, so a 401 never means one has ended.
            status == 401 -> SeerrException.Http(401, message)
            else -> null
        }

        /** The server's error text: `{"message": …}` usually, `{"error": …}` from a few routes. */
        fun serverMessage(body: String?): String? {
            if (body.isNullOrBlank()) return null
            val obj = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
            for (key in listOf("message", "error")) {
                val value = obj[key] as? JsonPrimitive
                if (value != null && value.isString && value.content.isNotBlank()) return value.content
            }
            return null
        }
    }
}
