package com.tomdebres.hither.core

import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.DiscoverQuery
import com.tomdebres.hither.core.model.IssueBody
import com.tomdebres.hither.core.model.IssueStatus
import com.tomdebres.hither.core.model.IssueType
import com.tomdebres.hither.core.model.SliderType
import com.tomdebres.hither.core.rules.afterDecision
import com.tomdebres.hither.core.rules.adminVerdict
import com.tomdebres.hither.core.rules.AdminVerdict
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.RequestBody
import com.tomdebres.hither.core.model.RequestCount
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.RequestAction
import com.tomdebres.hither.core.rules.RequestChange
import com.tomdebres.hither.core.rules.RequestSnapshot
import com.tomdebres.hither.core.rules.SinceLastSeen
import com.tomdebres.hither.core.rules.accountKey
import com.tomdebres.hither.core.rules.seesWaiting
import com.tomdebres.hither.core.rules.sinceLastSeen
import com.tomdebres.hither.core.rules.afterRequest
import com.tomdebres.hither.core.rules.movieRequestBody
import com.tomdebres.hither.core.rules.requestBadge
import com.tomdebres.hither.core.rules.requestOptions
import com.tomdebres.hither.core.rules.takenSeasons
import com.tomdebres.hither.core.rules.titleCard
import com.tomdebres.hither.core.rules.tvRequestBody
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * The client against `scripts/fake-seerr`, the only server M2's requests are ever proved
 * against (DECISIONS 54), over the app's own HTTP engine: each answer the fake gives a
 * request, and that the title, the rows and the requests list show the new status after it.
 * Where a body can be built by Hither's own rules it is, so this is also what M2 will send.
 * The fake is a Python script; CI's runner and the Studio both have `python3`.
 */
class FakeSeerrTest {
    companion object {
        private lateinit var fake: Process
        private lateinit var base: String

        @BeforeClass
        @JvmStatic
        fun start() {
            val module = File(System.getProperty("user.dir"))
            val script = module.resolve("../scripts/fake-seerr").canonicalFile
            check(script.isFile) { "no fake at $script" }
            val log = module.resolve("build/fake-seerr-test.log").also { it.parentFile.mkdirs() }
            // Port 0: the system picks a free one and the fake's first line names it.
            fake = ProcessBuilder("python3", script.path, "0").redirectError(log).start()
            val line = fake.inputStream.bufferedReader().readLine() ?: error("fake-seerr did not start: see $log")
            val port = Regex("""127\.0\.0\.1:(\d+)""").find(line)?.groupValues?.get(1) ?: error("no port in \"$line\"")
            base = "http://127.0.0.1:$port"
        }

        @AfterClass
        @JvmStatic
        fun stop() {
            fake.destroy()
            fake.waitFor(5, TimeUnit.SECONDS)
        }
    }

    /** Titles in the recorded rows that nobody has asked for (no library record). */
    private val ebenezer = 1567605
    private val godzilla = 1381071
    private val ramayana = 656908
    private val modernFamily = 1421
    private val himym = 1100

    private val clients = mutableListOf<SeerrClient>()
    private lateinit var seerr: SeerrClient

    private fun client(cookies: CookiesStorage = AcceptAllCookiesStorage()) =
        SeerrClient(OkHttp.create(), base, cookies).also { clients += it }

    /** One of the fake's own switches, from the Mac's side: never something the app calls. */
    private fun fake(pathAndQuery: String): String {
        val c = URI(base + pathAndQuery).toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.outputStream.close()
        assertEquals("$pathAndQuery answered", 200, c.responseCode)
        return c.inputStream.bufferedReader().readText()
    }

    /** A switch that may refuse: its status. */
    private fun fakeStatus(pathAndQuery: String): Int {
        val c = URI(base + pathAndQuery).toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.outputStream.close()
        return c.responseCode
    }

    /** The cookie jar of [seerr], this test's own. */
    private lateinit var jar: AcceptAllCookiesStorage

    @Before
    fun fresh() {
        fake("/fake/reset")
        jar = AcceptAllCookiesStorage()
        seerr = client(jar)
    }

    @After
    fun close() = clients.forEach { it.close() }

    /** Signs in as the gateway does: the server's settings first (they carry the CSRF cookie). */
    private suspend fun SeerrClient.signedIn(): User {
        publicSettings()
        return signIn("tv@example.invalid", "right-password")
    }

    private fun badgeOn(page: Page<MediaResult>, tmdbId: Int): String? =
        titleCard(page.results.single { it.id == tmdbId }, base)!!.badge?.text

    private inline fun <reified E : SeerrException> failsWith(block: () -> Unit): E {
        try {
            block()
        } catch (e: SeerrException) {
            if (e is E) return e
            fail("expected ${E::class.simpleName}, got ${e::class.simpleName}: ${e.message}")
        }
        fail("expected ${E::class.simpleName}, nothing was thrown")
        throw AssertionError()
    }

    // ---- Discover's rows -----------------------------------------------------------------------------

    @Test
    fun `Upcoming Series is a row of series of its own, paged as the others are`() = runBlocking {
        seerr.signedIn()
        val first = seerr.upcomingSeries()
        assertTrue("Upcoming Series came back empty", first.results.isNotEmpty())
        assertTrue("Upcoming Series holds something other than series", first.results.all { it.mediaType == "tv" })
        assertTrue("Upcoming Series is Popular Series' first page as it is", first.results.map { it.id } != seerr.popularSeries().results.map { it.id })
        val second = seerr.upcomingSeries(page = 2)
        assertEquals(2, second.page)
        assertTrue("its second page repeats the first", second.results.map { it.id }.intersect(first.results.map { it.id }.toSet()).isEmpty())
    }

    @Test
    fun `the recorded film belongs to a made-up collection whose page lists it among its films`() = runBlocking {
        seerr.signedIn()
        val film = Fixtures.read<MovieDetails>("movie")
        val brief = seerr.movie(film.id).collection
        assertTrue("the recorded film carries no collection", brief != null && brief.id > 0 && brief.name.isNotBlank())
        val collection = seerr.collection(brief!!.id)
        assertEquals(brief.name, collection.name)
        assertTrue("the film is not among its collection's films", collection.parts.any { it.id == film.id })
        assertTrue("its films are not all films", collection.parts.all { it.mediaType == "movie" })
        assertTrue("a collection of one", collection.parts.size > 1)
        failsWith<SeerrException> { seerr.collection(1) }
        Unit
    }

    // ---- what a request is answered ----------------------------------------------------------------

    @Test
    fun `a film request is approved at once for this account, and the title reads Requested everywhere`() = runBlocking {
        val user = seerr.signedIn()
        val settings = seerr.publicSettings()
        val options = requestOptions(MediaType.MOVIE, settings, user, seerr.movie(ebenezer).mediaInfo)
        assertTrue(options.autoApproved)

        val created = seerr.request(movieRequestBody(ebenezer, is4k = false, options)!!)
        assertEquals(RequestStatus.APPROVED, created.status)
        assertEquals(ebenezer, created.media?.tmdbId)
        // the 201 carries the record as it was saved, before the approval moved it on
        assertEquals(MediaStatus.PENDING, created.media?.status)

        val movie = seerr.movie(ebenezer).mediaInfo!!
        assertEquals(MediaStatus.PROCESSING, movie.status)
        assertEquals(MediaStatus.UNKNOWN, movie.status4k)
        assertEquals(listOf(created.id), movie.requests.map { it.id })
        assertEquals("Requested", badgeOn(seerr.trending(), ebenezer))
        assertEquals("Requested", badgeOn(seerr.search("Ebenezer"), ebenezer))
        val newest = seerr.requests().results.first()
        assertEquals(created.id, newest.id)
        assertEquals("Requested", requestBadge(newest)?.text)
        assertEquals(RequestAction.NONE, requestOptions(MediaType.MOVIE, settings, user, movie).standard)
    }

    @Test
    fun `without automatic approval a request is Pending, and reads Pending at once`() = runBlocking {
        fake("/fake/account?autoapprove=0")
        val user = seerr.signedIn()
        assertEquals(Permission.REQUEST + Permission.REQUEST_4K_MOVIE, user.permissions)
        val options = requestOptions(MediaType.MOVIE, seerr.publicSettings(), user, null)
        assertFalse(options.autoApproved)

        val created = seerr.request(movieRequestBody(godzilla, is4k = false, options)!!)
        assertEquals(RequestStatus.PENDING, created.status)
        assertEquals(MediaStatus.PENDING, seerr.movie(godzilla).mediaInfo?.status)
        assertEquals("Pending", badgeOn(seerr.trending(), godzilla))
        assertEquals(listOf(created.id), seerr.requests(filter = "pending").results.map { it.id })
        assertTrue(seerr.requests(filter = "approved").results.none { it.id == created.id })
        assertEquals("Pending", requestBadge(seerr.requests().results.first())?.text)
    }

    @Test
    fun `a series request takes the seasons picked, and seasons already covered are the 202`() = runBlocking {
        val user = seerr.signedIn()
        val settings = seerr.publicSettings()
        val tv = seerr.tv(modernFamily)
        val options = requestOptions(MediaType.TV, settings, user, tv.mediaInfo, tv.seasons)
        val body = tvRequestBody(modernFamily, false, setOf(1, 2), tv.seasons, tv.mediaInfo, settings, options)!!

        val first = seerr.request(body)
        assertEquals(listOf(1, 2), first.seasons.map { it.seasonNumber })
        assertTrue(first.seasons.all { it.status == RequestStatus.APPROVED })
        val after = seerr.tv(modernFamily).mediaInfo
        assertEquals(MediaStatus.PROCESSING, after?.status)
        assertEquals(setOf(1, 2), takenSeasons(after, is4k = false))
        assertEquals("Requested", badgeOn(seerr.popularSeries(), modernFamily))

        // the same seasons again, as a page left open since would send them: Seerr's 202
        val again = failsWith<SeerrException.NothingToRequest> {
            seerr.request(RequestBody("tv", modernFamily, seasons = listOf(1, 2)))
        }
        assertEquals("No seasons available to request", again.message)
        // overlapping: only the season not yet covered is asked for
        val more = seerr.request(RequestBody("tv", modernFamily, seasons = listOf(2, 3)))
        assertEquals(listOf(3), more.seasons.map { it.seasonNumber })
        assertEquals(setOf(1, 2, 3), takenSeasons(seerr.tv(modernFamily).mediaInfo, is4k = false))
        assertEquals(2, seerr.requests().results.count { it.media?.tmdbId == modernFamily })
    }

    @Test
    fun `a film asked for twice is AlreadyRequested, and only one request exists`() = runBlocking {
        seerr.signedIn()
        seerr.request(RequestBody("movie", ramayana))
        val e = failsWith<SeerrException.AlreadyRequested> { seerr.request(RequestBody("movie", ramayana)) }
        assertEquals("Request for this media already exists.", e.message)
        assertEquals(1, seerr.requests().results.count { it.media?.tmdbId == ramayana })
    }

    @Test
    fun `4K follows the account's permission - taken with it, refused in Seerr's words without it`() = runBlocking {
        seerr.signedIn()
        val fourK = seerr.request(RequestBody("movie", ebenezer, is4k = true))
        assertTrue(fourK.is4k)
        val movie = seerr.movie(ebenezer).mediaInfo!!
        assertEquals(MediaStatus.PROCESSING, movie.status4k)
        assertEquals(MediaStatus.UNKNOWN, movie.status)
        // a card shows the regular copy's badge, and that one was not asked for
        assertNull(badgeOn(seerr.trending(), ebenezer))

        fake("/fake/account?fourk=0")
        val user = seerr.me()
        assertEquals(Permission.REQUEST + Permission.AUTO_APPROVE, user.permissions)
        assertEquals(RequestAction.NONE, requestOptions(MediaType.MOVIE, seerr.publicSettings(), user, null).fourK)
        val e = failsWith<SeerrException.Refused> { seerr.request(RequestBody("movie", godzilla, is4k = true)) }
        assertEquals("You do not have permission to make 4K movie requests.", e.serverMessage)
        assertNull(seerr.movie(godzilla).mediaInfo)
    }

    @Test
    fun `4K where the server has none is refused by the fake, though Seerr itself would take it`() = runBlocking {
        fake("/fake/account?fourk=all")
        seerr.signedIn()
        assertFalse(seerr.publicSettings().series4kEnabled)
        val series = failsWith<SeerrException.Http> { seerr.request(RequestBody("tv", himym, is4k = true, seasons = listOf(1))) }
        assertEquals(400, series.status)
        assertTrue(series.message!!, series.message!!.startsWith("fake-seerr"))
        assertNull(seerr.tv(himym).mediaInfo)

        fake("/fake/server?movie4k=0")
        assertFalse(seerr.publicSettings().movie4kEnabled)
        assertEquals(400, failsWith<SeerrException.Http> { seerr.request(RequestBody("movie", godzilla, is4k = true)) }.status)

        // told to answer exactly as Seerr does, it takes it
        fake("/fake/server?strict=0")
        assertTrue(seerr.request(RequestBody("tv", himym, is4k = true, seasons = listOf(1))).is4k)
    }

    @Test
    fun `seasons not on offer are refused, and so is a short pick with partial requests off`() = runBlocking {
        val user = seerr.signedIn()
        for (seasons in listOf(listOf(9), listOf(0), listOf(1, 9))) {
            val e = failsWith<SeerrException.Http> { seerr.request(RequestBody("tv", himym, seasons = seasons)) }
            assertEquals("$seasons", 400, e.status)
        }
        assertNull(seerr.tv(himym).mediaInfo)

        // specials switched on: season 0 is on offer
        fake("/fake/server?specials=1")
        assertTrue(seerr.publicSettings().enableSpecialEpisodes)
        assertEquals(listOf(0), seerr.request(RequestBody("tv", himym, seasons = listOf(0))).seasons.map { it.seasonNumber })

        fake("/fake/server?partial=0")
        val settings = seerr.publicSettings()
        assertFalse(settings.partialRequestsEnabled)
        assertEquals(400, failsWith<SeerrException.Http> { seerr.request(RequestBody("tv", himym, seasons = listOf(1))) }.status)
        // what Hither's rule sends with partial requests off: every season still open
        val tv = seerr.tv(himym)
        val body = tvRequestBody(himym, false, emptySet(), tv.seasons, tv.mediaInfo, settings,
            requestOptions(MediaType.TV, settings, user, tv.mediaInfo, tv.seasons))!!
        assertEquals(listOf(1, 2, 3, 4, 5, 6), body.seasons)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), seerr.request(body).seasons.map { it.seasonNumber })
    }

    @Test
    fun `a body outside Seerr's API spec is a 400`() = runBlocking {
        seerr.signedIn()
        assertEquals(400, failsWith<SeerrException.Http> { seerr.request(RequestBody("person", 1)) }.status)
    }

    @Test
    fun `with CSRF protection on, the client's echo passes and a write without it is refused`() = runBlocking {
        fake("/fake/server?csrf=1")
        val cookies = Hiding()
        val guarded = client(cookies)
        guarded.publicSettings()
        assertNotNull(cookies.get(Url(base)).firstOrNull { it.name == "XSRF-TOKEN" })
        guarded.signIn("tv@example.invalid", "right-password") // a write: passes only with the echo
        guarded.request(RequestBody("movie", ebenezer))

        cookies.hideXsrf = true
        // Seerr's CSRF check, read as itself (DECISIONS 70), and nothing was made
        assertTrue(failsWith<SeerrException.CsrfRefused> { guarded.request(RequestBody("movie", godzilla)) }.overHttp)
        assertNull(guarded.movie(godzilla).mediaInfo)
    }

    @Test
    fun `with CSRF protection on and its cookies Secure, as Seerr sets them, http is locked out by CSRF, not by the password`() = runBlocking {
        fake("/fake/server?csrf=1&secure=1")
        val overHttp = client()
        overHttp.publicSettings()
        // the token came, Secure, and is never sent back over http
        val e = failsWith<SeerrException.CsrfRefused> { overHttp.signIn("tv@example.invalid", "right-password") }
        assertTrue(e.overHttp)
        assertEquals(SignInFailure.CSRF_NEEDS_HTTPS, signInFailure(e))
        // the right password with CSRF off again: it was never the password
        fake("/fake/server?csrf=0")
        assertEquals(8, overHttp.signIn("tv@example.invalid", "right-password").id)
    }

    @Test
    fun `the requests list pages newest first`() = runBlocking {
        seerr.signedIn()
        val made = listOf(ebenezer, godzilla, ramayana).map { seerr.request(RequestBody("movie", it)).id }
        val first = seerr.requests(take = 2)
        assertEquals(made.reversed().take(2), first.results.map { it.id })
        assertEquals(6, first.pageInfo.results) // the three made and the fixture's three
        assertEquals(3, first.pageInfo.pages)
        val second = seerr.requests(take = 2, skip = 2)
        assertEquals(2, second.pageInfo.page)
        assertEquals(made.first(), second.results.first().id)
    }

    @Test
    fun `the requests list is the account's own unless it may see others', as Seerr gives it`() = runBlocking {
        val me = seerr.signedIn().id
        // Without REQUEST_VIEW: the account's own, asked for or not, and nobody else's on asking.
        assertEquals(listOf(me), seerr.requests().results.mapNotNull { it.requestedBy?.id }.distinct())
        assertEquals(3, seerr.requests(requestedBy = me).results.size)
        val refused = failsWith<SeerrException.Refused> { seerr.requests(requestedBy = 1) }
        assertEquals("You do not have permission to view this user's requests.", refused.serverMessage)
        // With it, everyone's unless one user is asked for: My Requests asks for its own (DECISIONS 66).
        fake("/fake/account?view=1")
        assertEquals(setOf(me, 1), seerr.requests().results.mapNotNull { it.requestedBy?.id }.toSet())
        assertEquals(listOf(me), seerr.requests(requestedBy = me).results.mapNotNull { it.requestedBy?.id }.distinct())
    }

    @Test
    fun `a title Plex has carries Seerr's Plex links, and Hither's play action opens it in Plex`() = runBlocking {
        val user = seerr.signedIn()
        val settings = seerr.publicSettings()
        val machine = "f00dfacecafe" + "0".repeat(28)
        // the recorded film in the library: its regular copy has a rating key, its 4K copy none
        val media = seerr.movie(1423191).mediaInfo!!
        assertEquals("plex://preplay/?metadataKey=%2Flibrary%2Fmetadata%2F1&server=$machine", media.iOSPlexUrl)
        assertNull(media.iOSPlexUrl4k)
        assertEquals(
            listOf("plex://libraries/$machine/unmatched/%2Flibrary%2Fmetadata%2F1"),
            com.tomdebres.hither.core.rules.playActions(MediaType.MOVIE, media, settings, user.permissions).map { it.link },
        )
        // a title nobody has asked for has no record, and no link
        assertNull(seerr.movie(ebenezer).mediaInfo)
        // the rows and the requests list carry the same links as the page
        assertTrue(seerr.requests().results.all { it.media?.iOSPlexUrl?.startsWith("plex://preplay/") ?: true })
        assertTrue(seerr.requests().results.any { it.media?.iOSPlexUrl != null })
    }

    // ---- the status shown at once after a 201 ----------------------------------------------------------

    @Test
    fun `the status worked out from a 201 is the one the server shows next, approved or pending`() = runBlocking {
        val user = seerr.signedIn()
        val settings = seerr.publicSettings()
        val phineas = 1877 // season 1 in the library, Partially Available
        val residentEvil = 1423191 // Available, 4K not asked for
        suspend fun film(id: Int) = seerr.movie(id).mediaInfo
        suspend fun series(id: Int) = seerr.tv(id).mediaInfo
        val cases = listOf<Pair<String, suspend () -> Unit>>(
            "film, approved" to {
                val before = film(ebenezer)
                val created = seerr.request(movieRequestBody(ebenezer, false, requestOptions(MediaType.MOVIE, settings, seerr.me(), before))!!)
                assertSame(afterRequest(before, created, MediaType.MOVIE, ebenezer), film(ebenezer)!!)
            },
            "film in 4K, approved" to {
                val before = film(residentEvil)
                val created = seerr.request(movieRequestBody(residentEvil, true, requestOptions(MediaType.MOVIE, settings, seerr.me(), before))!!)
                assertSame(afterRequest(before, created, MediaType.MOVIE, residentEvil), film(residentEvil)!!)
            },
            "series partly in, approved" to {
                val tv = seerr.tv(phineas)
                val options = requestOptions(MediaType.TV, settings, seerr.me(), tv.mediaInfo, tv.seasons)
                val created = seerr.request(tvRequestBody(phineas, false, setOf(2, 3), tv.seasons, tv.mediaInfo, settings, options)!!)
                assertSame(afterRequest(tv.mediaInfo, created, MediaType.TV, phineas), series(phineas)!!)
            },
        )
        for ((name, case) in cases) {
            try {
                case()
            } catch (e: AssertionError) {
                throw AssertionError("$name: ${e.message}", e)
            }
        }
        // and without automatic approval
        fake("/fake/account?autoapprove=0")
        val waits = seerr.me()
        val before = series(himym)
        val tv = seerr.tv(himym)
        val created = seerr.request(tvRequestBody(himym, false, setOf(1), tv.seasons, before, settings, requestOptions(MediaType.TV, settings, waits, before, tv.seasons))!!)
        assertEquals(RequestStatus.PENDING, created.status)
        assertSame(afterRequest(before, created, MediaType.TV, himym), series(himym)!!)
        assertEquals(user.id, created.requestedBy?.id)
    }

    /** What the page and the card read from a record: the two statuses and the seasons taken. */
    private fun assertSame(worked: MediaInfo, served: MediaInfo) {
        assertEquals("status", served.status, worked.status)
        assertEquals("status4k", served.status4k, worked.status4k)
        for (is4k in listOf(false, true)) {
            assertEquals("seasons taken (4K $is4k)", takenSeasons(served, is4k), takenSeasons(worked, is4k))
        }
        assertEquals("requests", served.requests.map { it.id }.toSet(), worked.requests.map { it.id }.toSet())
    }

    // ---- one library record per title ----------------------------------------------------------------

    @Test
    fun `a title has one library record - its rows, search and its own page agree`() = runBlocking {
        seerr.signedIn()
        // Resident Evil: as recorded, no record in Trending, Available in Popular Movies and on its page
        val residentEvil = 1423191
        assertEquals("Available", badgeOn(seerr.trending(), residentEvil))
        assertEquals("Available", badgeOn(seerr.popularMovies(), residentEvil))
        assertEquals(MediaStatus.AVAILABLE, seerr.movie(residentEvil).mediaInfo?.status)
        // Phineas and Ferb: Available in its row as recorded, one season in on its page
        val phineas = 1877
        assertEquals("Partially Available", badgeOn(seerr.popularSeries(), phineas))
        assertEquals(MediaStatus.PARTIALLY_AVAILABLE, seerr.tv(phineas).mediaInfo?.status)
        // a made-up page's copy is a title of its own with its original's record
        val copy = residentEvil + 10_000_000
        assertEquals("Available", badgeOn(seerr.popularMovies(page = 2), copy))
        val copyRecord = seerr.movie(copy).mediaInfo
        assertEquals(MediaStatus.AVAILABLE, copyRecord?.status)
        assertEquals(copy, copyRecord?.tmdbId)
        assertEquals(copy, seerr.popularMovies(page = 2).results.single { it.id == copy }.mediaInfo?.tmdbId)
        // a title without a page fixture of its own has its listed dates on its page
        val svu = seerr.popularSeries().results.single { it.id == 2734 }
        assertEquals(svu.firstAirDate, seerr.tv(2734).firstAirDate)
        assertEquals(seerr.trending().results.single { it.id == ebenezer }.releaseDate, seerr.movie(ebenezer).releaseDate)
    }

    @Test
    fun `each answer carries the record as Seerr's query loads it`() = runBlocking {
        val cookie = rawSignIn()
        // Media.getMedia: the title's page has the record's requests, seasons and (for this
        // account, none) issues, each request with the record's own columns
        val kimmel = 1489 // partly available as Trending records it; no page fixture of its own
        val page = rawGet("/api/v1/tv/$kimmel", cookie).getValue("mediaInfo").jsonObject
        for (key in listOf("requests", "seasons", "issues")) assertEquals(key, JsonArray(emptyList()), page[key])
        assertFalse("watchlists" in page)
        // Media.getRelatedMedia: a row's record has the user's watchlist entries and no relation
        val row = rawResult("/api/v1/discover/trending", kimmel, cookie)
        assertTrue("watchlists" in row)
        for (key in listOf("requests", "seasons", "issues")) assertFalse(key, key in row)
        // a title nobody asked for has no record at all
        assertFalse("mediaInfo" in rawGet("/api/v1/movie/$godzilla", cookie))

        // after a request, the same shapes for the record made here
        seerr.signedIn()
        seerr.request(RequestBody("tv", modernFamily, seasons = listOf(1)))
        val made = rawGet("/api/v1/tv/$modernFamily", cookie).getValue("mediaInfo").jsonObject
        val request = made.getValue("requests").jsonArray.single().jsonObject
        assertEquals(listOf(1), request.getValue("seasons").jsonArray.map { it.jsonObject.getValue("seasonNumber").jsonPrimitive.int })
        val nested = request.getValue("media").jsonObject
        for (key in listOf("requests", "seasons", "issues", "watchlists")) assertFalse("request's media has $key", key in nested)
        val madeRow = rawResult("/api/v1/discover/tv", modernFamily, cookie)
        for (key in listOf("requests", "seasons", "issues")) assertFalse("row has $key", key in madeRow)
        val listed = rawGet("/api/v1/request?take=1", cookie).getValue("results").jsonArray.single().jsonObject
        for (key in listOf("requests", "seasons", "issues", "watchlists")) {
            assertFalse("requests list's media has $key", key in listed.getValue("media").jsonObject)
        }
    }

    /** A session cookie of its own, for reading answers as the server writes them. */
    private fun rawSignIn(): String {
        val c = URI("$base/api/v1/auth/local").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write("""{"email":"tv@example.invalid","password":"right-password"}""".toByteArray()) }
        assertEquals(200, c.responseCode)
        return c.headerFields["Set-Cookie"].orEmpty().first { it.startsWith("connect.sid=") }.substringBefore(';')
    }

    private fun rawGet(pathAndQuery: String, cookie: String): JsonObject {
        val c = URI(base + pathAndQuery).toURL().openConnection() as HttpURLConnection
        c.setRequestProperty("Cookie", cookie)
        assertEquals("$pathAndQuery answered", 200, c.responseCode)
        return Json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject
    }

    private fun rawResult(path: String, tmdbId: Int, cookie: String): JsonObject =
        rawGet(path, cookie).getValue("results").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.int == tmdbId }.getValue("mediaInfo").jsonObject

    // ---- sessions and the switches -----------------------------------------------------------------

    @Test
    fun `the session switches answer as Seerr and a proxy do`() = runBlocking {
        failsWith<SeerrException.SignedOut> { client().me() } // no cookie: 401
        seerr.signedIn()

        fake("/fake/expire")
        val forgotten = failsWith<SeerrException.SignedOut> { seerr.me() }
        assertEquals("You do not have permission to access this endpoint", forgotten.message)
        failsWith<SeerrException.SignedOut> { seerr.request(RequestBody("movie", ebenezer)) }
        assertEquals(8, seerr.signIn("tv@example.invalid", "right-password").id)
        assertNull(seerr.movie(ebenezer).mediaInfo)

        fake("/fake/forbid?on=1")
        failsWith<SeerrException.Refused> { seerr.me() }
        assertTrue(seerr.publicSettings().localLogin)
        fake("/fake/forbid?on=0")
        assertEquals(8, seerr.me().id)
    }

    // ---- the server's owner moving things on (M4) ---------------------------------------------------

    /** One look at the user's own requests as Hither takes it: the most recently changed first. */
    private suspend fun look(user: User, previous: RequestSnapshot?): SinceLastSeen =
        sinceLastSeen(previous, accountKey(base, user.id), user.id, seerr.requests(take = 50, requestedBy = user.id, sort = "modified").results)

    private fun SinceLastSeen.said() = events.map { it.change to it.tmdbId }

    @Test
    fun `the owner approving or declining a pending request moves it as Seerr does, and is announced`() = runBlocking {
        fake("/fake/account?autoapprove=0")
        val user = seerr.signedIn()
        val approve = seerr.request(RequestBody("movie", ebenezer))
        val decline = seerr.request(RequestBody("movie", godzilla))
        val first = look(user, null)
        assertTrue(first.events.isEmpty())

        fake("/fake/move?title=movie:$godzilla&to=declined")
        fake("/fake/move?title=movie:$ebenezer&to=approved")
        val listed = seerr.requests(requestedBy = user.id, sort = "modified").results
        // The last moved first, though it was asked for first.
        assertEquals(listOf(approve.id, decline.id), listed.take(2).map { it.id })
        val approved = listed.single { it.id == approve.id }
        assertEquals(RequestStatus.APPROVED, approved.status)
        assertEquals(1, approved.modifiedBy?.id)
        assertEquals(MediaStatus.PROCESSING, approved.media?.status)
        assertEquals("Requested", requestBadge(approved)?.text)
        val declined = listed.single { it.id == decline.id }
        assertEquals(RequestStatus.DECLINED, declined.status)
        assertEquals("Declined", requestBadge(declined)?.text)
        // A declined film's copy goes back to unknown: the title can be asked for again.
        assertEquals(MediaStatus.UNKNOWN, seerr.movie(godzilla).mediaInfo?.status)

        val next = look(user, first.snapshot)
        assertEquals(setOf(RequestChange.APPROVED to ebenezer, RequestChange.DECLINED to godzilla), next.said().toSet())
        assertTrue(look(user, next.snapshot).events.isEmpty())
        // Seerr moves only a pending request.
        assertEquals(409, fakeStatus("/fake/move?title=movie:$ebenezer&to=declined"))
    }

    @Test
    fun `a film's copy arriving completes its request, regular and 4K apart, and is announced`() = runBlocking {
        val user = seerr.signedIn()
        val regular = seerr.request(RequestBody("movie", ebenezer))
        val fourK = seerr.request(RequestBody("movie", ebenezer, is4k = true))
        val first = look(user, null)

        fake("/fake/move?title=movie:$ebenezer&to=available")
        val movie = seerr.movie(ebenezer).mediaInfo!!
        assertEquals(MediaStatus.AVAILABLE, movie.status)
        assertEquals(MediaStatus.PROCESSING, movie.status4k)
        assertNotNull(movie.mediaAddedAt)
        // In the library now, so Seerr's Plex link follows (DECISIONS 68).
        assertNotNull(movie.iOSPlexUrl)
        assertEquals("Available", badgeOn(seerr.trending(), ebenezer))
        val listed = seerr.requests(requestedBy = user.id, sort = "modified").results
        assertEquals(RequestStatus.COMPLETED, listed.single { it.id == regular.id }.status)
        assertEquals(RequestStatus.APPROVED, listed.single { it.id == fourK.id }.status)

        val second = look(user, first.snapshot)
        assertEquals(listOf(RequestChange.AVAILABLE to ebenezer), second.said())
        assertEquals(listOf("movie:$ebenezer"), second.ready.map { it.key }.filter { it.endsWith(":$ebenezer") })
        assertEquals("movie:$ebenezer", second.ready.first().key)

        fake("/fake/move?title=movie:$ebenezer&to=available4k")
        assertEquals(MediaStatus.AVAILABLE, seerr.movie(ebenezer).mediaInfo?.status4k)
        assertNotNull(seerr.movie(ebenezer).mediaInfo?.iOSPlexUrl4k)
        assertEquals(listOf(RequestChange.AVAILABLE_4K to ebenezer), look(user, second.snapshot).said())
    }

    @Test
    fun `a pending request whose copy arrives is approved and completed by Seerr, and announced as ready only`() = runBlocking {
        fake("/fake/account?autoapprove=0")
        val user = seerr.signedIn()
        val pending = seerr.request(RequestBody("movie", ramayana))
        val first = look(user, null)
        fake("/fake/move?title=movie:$ramayana&to=available")
        val listed = seerr.requests(requestedBy = user.id, sort = "modified").results.single { it.id == pending.id }
        assertEquals(RequestStatus.COMPLETED, listed.status)
        assertEquals("Available", requestBadge(listed)?.text)
        assertEquals(listOf(RequestChange.AVAILABLE to ramayana), look(user, first.snapshot).said())
    }

    @Test
    fun `a series request is ready when every season it asked for is in`() = runBlocking {
        val user = seerr.signedIn()
        val asked = seerr.request(RequestBody("tv", modernFamily, seasons = listOf(1, 2)))
        val first = look(user, null)

        fake("/fake/move?title=tv:$modernFamily&to=available&seasons=1")
        val partly = seerr.tv(modernFamily).mediaInfo!!
        assertEquals(MediaStatus.PARTIALLY_AVAILABLE, partly.status)
        assertEquals(MediaStatus.AVAILABLE, partly.seasons.single { it.seasonNumber == 1 }.status)
        val waiting = seerr.requests(requestedBy = user.id).results.single { it.id == asked.id }
        assertEquals(RequestStatus.APPROVED, waiting.status)
        val second = look(user, first.snapshot)
        assertTrue(second.said().toString(), second.events.isEmpty())

        fake("/fake/move?title=tv:$modernFamily&to=available&seasons=2")
        val done = seerr.requests(requestedBy = user.id).results.single { it.id == asked.id }
        assertEquals(RequestStatus.COMPLETED, done.status)
        assertEquals(listOf(RequestStatus.COMPLETED, RequestStatus.COMPLETED), done.seasons.map { it.status })
        // Seasons 3 to 6 are not in: the series is still partly there, and the request is ready.
        assertEquals(MediaStatus.PARTIALLY_AVAILABLE, done.media?.status)
        assertEquals(listOf(RequestChange.AVAILABLE to modernFamily), look(user, second.snapshot).said())

        // Every season on offer: the whole series.
        fake("/fake/move?title=tv:$modernFamily&to=available")
        assertEquals(MediaStatus.AVAILABLE, seerr.tv(modernFamily).mediaInfo?.status)
    }

    @Test
    fun `the request count is the server's, everyone's, as Seerr counts it, and an approver is one switch away`() = runBlocking {
        val user = seerr.signedIn()
        assertFalse(seesWaiting(user))
        // The recorded three (completed) and another user's (completed).
        val recorded = seerr.requestCount()
        assertEquals(RequestCount(total = 4, movie = 4, tv = 0, pending = 0, approved = 0, declined = 0, processing = 0, available = 0, completed = 4), recorded)

        fake("/fake/requests?others=3")
        fake("/fake/account?autoapprove=0")
        seerr.request(RequestBody("movie", godzilla))
        seerr.request(RequestBody("movie", ebenezer))
        fake("/fake/move?title=movie:$ebenezer&to=approved")
        val counted = seerr.requestCount()
        assertEquals(4, counted.pending) // three of another user's, and this account's own Godzilla
        assertEquals(1, counted.approved)
        assertEquals(1, counted.processing)
        assertEquals(9, counted.total)
        // Not this account's to see in its list: the list is still its own.
        assertTrue(seerr.requests().results.all { it.requestedBy?.id == user.id })

        fake("/fake/account?manage=1")
        val approver = seerr.me()
        assertTrue(seesWaiting(approver))
        // An approver's own requests are approved at once (MANAGE_REQUESTS), as in Seerr.
        assertEquals(RequestStatus.APPROVED, seerr.request(RequestBody("movie", ramayana)).status)
        assertEquals(4, seerr.requestCount().pending)
        fake("/fake/reset")
        assertFalse(seesWaiting(seerr.me()))
        assertEquals(0, seerr.requestCount().pending)
    }

    @Test
    fun `the move switch refuses what it does not know, and reset forgets its moves`() = runBlocking {
        val user = seerr.signedIn()
        assertEquals(400, fakeStatus("/fake/move?title=person:1&to=available"))
        assertEquals(400, fakeStatus("/fake/move?title=movie:$ebenezer&to=watched"))
        assertEquals(409, fakeStatus("/fake/move?title=movie:$ebenezer&to=approved"))
        seerr.request(RequestBody("movie", ebenezer))
        fake("/fake/move?title=movie:$ebenezer&to=available")
        assertEquals(MediaStatus.AVAILABLE, seerr.movie(ebenezer).mediaInfo?.status)
        fake("/fake/reset")
        assertNull(seerr.movie(ebenezer).mediaInfo)
        assertTrue(seerr.requests(requestedBy = user.id).results.none { it.media?.tmdbId == ebenezer })
    }

    @Test
    fun `reset forgets the requests and puts every switch back, sessions kept`() = runBlocking {
        seerr.signedIn()
        seerr.request(RequestBody("movie", ebenezer))
        fake("/fake/account?autoapprove=0&fourk=0")
        fake("/fake/server?movie4k=0&partial=0&specials=1&strict=0")

        fake("/fake/reset")
        assertNull(seerr.movie(ebenezer).mediaInfo)
        assertEquals(3, seerr.requests().results.size)
        assertEquals(67744, seerr.me().permissions)
        val settings = seerr.publicSettings()
        assertTrue(settings.movie4kEnabled)
        assertTrue(settings.partialRequestsEnabled)
        assertFalse(settings.enableSpecialEpisodes)
    }

    // ---- the TV's admin account (M5, DECISIONS 91, 92) ----------------------------------------------

    @Test
    fun `the approver is a local account of its own, with Manage Requests only`() = runBlocking {
        val family = seerr.signedIn()
        val approverClient = client()
        approverClient.publicSettings()
        val approver = approverClient.signIn(" Approver@example.invalid".trim(), "approver-password")
        assertEquals(9, approver.id)
        assertEquals("Approver", approver.displayName)
        assertEquals(Permission.MANAGE_REQUESTS, approver.permissions)
        assertEquals(AdminVerdict.APPROVER, adminVerdict(approver, family))
        // two sessions, each its own account
        assertEquals(family.id, seerr.me().id)
        assertEquals(approver.id, approverClient.me().id)
        // the approver sees everyone's requests, the family only its own (server/routes/request.ts)
        assertEquals(setOf(1, family.id), approverClient.requests(take = 50).results.mapNotNull { it.requestedBy?.id }.toSet())
        assertEquals(setOf(family.id), seerr.requests(take = 50).results.mapNotNull { it.requestedBy?.id }.toSet())
        // signing out the approver leaves the family's session alone
        approverClient.signOut()
        assertEquals(family.id, seerr.me().id)
        failsWith<SeerrException.SignedOut> { runBlocking { approverClient.me() } }
        Unit
    }

    @Test
    fun `the approver's password is its own, and the family's password does not open it`() = runBlocking {
        val approverClient = client()
        approverClient.publicSettings()
        failsWith<SeerrException.Refused> { runBlocking { approverClient.signIn("approver@example.invalid", "right-password") } }
        failsWith<SeerrException.Refused> { runBlocking { approverClient.signIn("tv@example.invalid", "approver-password") } }
        Unit
    }

    @Test
    fun `the approver's rights are switched as the server's owner would set them`() = runBlocking {
        val family = seerr.signedIn()
        val approverClient = client()
        approverClient.publicSettings()
        approverClient.signIn("approver@example.invalid", "approver-password")
        fake("/fake/approver?rights=admin")
        assertEquals(AdminVerdict.ADMINISTRATOR, adminVerdict(approverClient.me(), family))
        fake("/fake/approver?rights=none")
        assertEquals("read live: the same session, the rights as they are now", AdminVerdict.NO_RIGHT, adminVerdict(approverClient.me(), family))
        assertEquals(400, fakeStatus("/fake/approver?rights=all"))
        fake("/fake/reset")
        assertEquals(Permission.MANAGE_REQUESTS, approverClient.me().permissions)
    }

    // ---- approve and decline (M5, DECISIONS 94) -----------------------------------------------------

    private suspend fun approverSignedIn(): SeerrClient = client().also {
        it.publicSettings()
        it.signIn("approver@example.invalid", "approver-password")
    }

    /** The family's pending request for [title] ("movie:<id>" or "tv:<id>"), made as the app would. */
    private suspend fun familyAsks(title: String, seasons: String = ""): MediaRequest {
        fake("/fake/account?autoapprove=0")
        val made = Json.parseToJsonElement(fake("/fake/ask?title=$title" + (if (seasons.isNotEmpty()) "&seasons=$seasons" else "")))
        val id = made.jsonObject["id"]!!.jsonPrimitive.int
        return seerr.requests(take = 50).results.single { it.id == id }
    }

    @Test
    fun `the approver approves a pending film as Seerr does, under its own name`() = runBlocking {
        val family = seerr.signedIn()
        val asked = familyAsks("movie:$ebenezer")
        assertEquals(RequestStatus.PENDING, asked.status)
        assertEquals(family.id, asked.requestedBy?.id)
        val approver = approverSignedIn()
        assertTrue("it is in the approver's Pending", approver.requests(filter = "pending", take = 50).results.any { it.id == asked.id })
        val approved = approver.decide(asked.id, approve = true)
        assertEquals(RequestStatus.APPROVED, approved.status)
        assertEquals("modifiedBy is the approver", 9, approved.modifiedBy?.id)
        assertEquals("Approver", approved.modifiedBy?.displayName)
        // as afterDecision worked it out, the next read agrees
        assertEquals(afterDecision(asked, true, approver.me()).let(::requestBadge), requestBadge(seerr.requests(take = 50).results.single { it.id == asked.id }))
        assertEquals(MediaStatus.PROCESSING, seerr.movie(ebenezer).mediaInfo?.status)
        assertFalse("it has left Pending", approver.requests(filter = "pending", take = 50).results.any { it.id == asked.id })
        failsWith<SeerrException.NotPending> { runBlocking { approver.decide(asked.id, approve = false) } }
        Unit
    }

    @Test
    fun `the approver declines a series request whole, its seasons with it`() = runBlocking {
        seerr.signedIn()
        val asked = familyAsks("tv:$modernFamily", seasons = "1,2")
        val approver = approverSignedIn()
        val declined = approver.decide(asked.id, approve = false)
        assertEquals(RequestStatus.DECLINED, declined.status)
        val listed = seerr.requests(take = 50).results.single { it.id == asked.id }
        assertEquals(listOf(RequestStatus.DECLINED, RequestStatus.DECLINED), listed.seasons.map { it.status })
        assertEquals(afterDecision(asked, false, approver.me()).let(::requestBadge), requestBadge(listed))
    }

    @Test
    fun `an account without Manage Requests gets the route's 403, read as a refusal`() = runBlocking {
        seerr.signedIn()
        val asked = familyAsks("movie:$godzilla")
        failsWith<SeerrException.Refused> { runBlocking { seerr.decide(asked.id, approve = true) } }
        fake("/fake/approver?rights=none")
        val approver = approverSignedIn()
        failsWith<SeerrException.Refused> { runBlocking { approver.decide(asked.id, approve = true) } }
        assertEquals("nothing was decided", RequestStatus.PENDING, seerr.requests(take = 50).results.single { it.id == asked.id }.status)
        Unit
    }

    @Test
    fun `a request the server does not hold is a 404`() = runBlocking {
        val approver = approverSignedIn()
        assertEquals(404, failsWith<SeerrException.Http> { runBlocking { approver.decide(424242, approve = true) } }.status)
        Unit
    }

    // ---- M6: what the parity items read (DECISIONS 96 on) ----------------------------------------------

    /** A GET by hand, with or without the session: status and body. */
    private fun raw(path: String): Pair<Int, String> {
        val c = URI(base + path).toURL().openConnection() as HttpURLConnection
        val code = c.responseCode
        return code to ((if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "")
    }

    @Test
    fun `the API root answers without a session, as Seerr's does`() {
        val (code, body) = raw("/api/v1")
        assertEquals(200, code)
        assertEquals("Seerr API", Json.parseToJsonElement(body).jsonObject["api"]!!.jsonPrimitive.content)
        assertEquals("other calls still want one", 401, raw("/api/v1/settings/discover").first)
    }

    @Test
    fun `Discover's settings are Seerr's default sliders, and a customised server reorders, hides and adds`() = runBlocking {
        seerr.signedIn()
        val stock = seerr.discoverSliders()
        assertEquals((1..12).toList(), stock.map { it.type })
        assertEquals((0..11).toList(), stock.map { it.order })
        assertTrue(stock.all { it.enabled && it.isBuiltIn && it.title == null })

        fake("/fake/discover?preset=custom")
        val custom = seerr.discoverSliders()
        assertEquals(SliderType.TRENDING, custom.first().type)
        assertFalse("Popular Series hidden", custom.single { it.type == SliderType.POPULAR_TV }.enabled)
        val keyword = custom.single { it.type == SliderType.TMDB_MOVIE_KEYWORD }
        assertFalse(keyword.isBuiltIn)
        assertEquals("Zombies" to "12377", keyword.title to keyword.data)
        assertEquals((custom.indices).toList(), custom.map { it.order })

        fake("/fake/discover?preset=default&hide=4&order=" + (12 downTo 1).joinToString(","))
        val turned = seerr.discoverSliders()
        assertEquals((12 downTo 1).toList(), turned.map { it.id })
        assertFalse(turned.single { it.id == 4 }.enabled)
        assertEquals(400, fakeStatus("/fake/discover?order=1,2"))
    }

    @Test
    fun `every custom slider of the preset has titles behind it`() = runBlocking {
        seerr.signedIn()
        fake("/fake/discover?preset=custom")
        for (slider in seerr.discoverSliders().filter { !it.isBuiltIn }) {
            val data = slider.data!!
            val page = when (slider.type) {
                SliderType.TMDB_MOVIE_KEYWORD -> seerr.discover(MediaType.MOVIE, DiscoverQuery(keywords = data))
                SliderType.TMDB_TV_KEYWORD -> seerr.discover(MediaType.TV, DiscoverQuery(keywords = data))
                SliderType.TMDB_MOVIE_GENRE -> seerr.discover(MediaType.MOVIE, DiscoverQuery(genre = data))
                SliderType.TMDB_TV_GENRE -> seerr.discover(MediaType.TV, DiscoverQuery(genre = data))
                SliderType.TMDB_SEARCH -> seerr.search(data)
                SliderType.TMDB_STUDIO -> seerr.studioMovies(data.toInt())
                SliderType.TMDB_NETWORK -> seerr.networkSeries(data.toInt())
                SliderType.TMDB_MOVIE_STREAMING_SERVICES, SliderType.TMDB_TV_STREAMING_SERVICES -> {
                    val (region, provider) = data.split(",")
                    val type = if (slider.type == SliderType.TMDB_MOVIE_STREAMING_SERVICES) MediaType.MOVIE else MediaType.TV
                    seerr.discover(type, DiscoverQuery(watchRegion = region, watchProviders = provider))
                }
                else -> error("no custom slider of type ${slider.type} in the preset")
            }
            assertTrue("${slider.title} has nothing behind it", page.results.isNotEmpty())
        }
    }

    @Test
    fun `Recently Added is the library newest first by arrival, and a title just added leads it`() = runBlocking {
        seerr.signedIn()
        val before = seerr.library()
        assertTrue(before.results.isNotEmpty())
        assertTrue(before.results.all { it.status in setOf(MediaStatus.AVAILABLE, MediaStatus.PARTIALLY_AVAILABLE) && it.mediaAddedAt != null })
        assertEquals(before.results.map { it.mediaAddedAt!! }.sortedDescending(), before.results.map { it.mediaAddedAt!! })
        assertTrue("a title nobody asked for is not in the library", before.results.none { it.tmdbId == ebenezer })

        seerr.request(RequestBody("movie", ebenezer))
        fake("/fake/move?title=movie:$ebenezer&to=available")
        val after = seerr.library(take = 3)
        assertEquals(ebenezer to "movie", after.results.first().tmdbId to after.results.first().mediaType)
        assertEquals(3, after.results.size)
        assertEquals(before.pageInfo.results + 1, after.pageInfo.results)
        // processing titles are not "added"
        assertTrue(seerr.library(filter = "processing", sort = "added").results.none { it.status != MediaStatus.PROCESSING })
    }

    @Test
    fun `the family's Recent View and issue rights are switched as the server's owner would set them`() = runBlocking {
        seerr.signedIn()
        val stock = seerr.me().permissions
        assertFalse(Permission.hasAny(stock, Permission.RECENT_VIEW, Permission.CREATE_ISSUES, Permission.VIEW_ISSUES))
        fake("/fake/account?recent=1&createissues=1&viewissues=1")
        val held = seerr.me().permissions!!
        assertEquals(stock!! + Permission.RECENT_VIEW + Permission.CREATE_ISSUES + Permission.VIEW_ISSUES, held)
        fake("/fake/account?createissues=0")
        assertEquals(0, seerr.me().permissions!! and Permission.CREATE_ISSUES)
    }

    @Test
    fun `a person is the cast's, with credits carrying their records, and search finds people after titles`() = runBlocking {
        seerr.signedIn()
        val cast = seerr.movie(ebenezer).credits.cast.first()
        val person = seerr.person(cast.id)
        assertEquals(cast.name, person.name)
        assertEquals(cast.profilePath, person.profilePath)
        assertTrue(person.biography!!.split("\n\n").size >= 2)
        val credits = seerr.personCredits(cast.id)
        assertTrue(credits.crew.isEmpty())
        val own = credits.cast.single { it.id == 1423191 }
        assertEquals(cast.character, own.character)
        assertTrue("credits carry library records", credits.cast.any { it.mediaInfo != null })
        assertTrue(credits.cast.all { it.mediaType in setOf("movie", "tv") })
        assertEquals(500, failsWith<SeerrException.Http> { runBlocking { seerr.person(1) } }.status)

        val director = seerr.movie(ebenezer).credits.crew.first { it.job == "Director" }
        assertTrue(seerr.personCredits(director.id).crew.any { it.job == "Director" })

        val found = seerr.search(cast.name.substringBefore(' '))
        val people = found.results.filter { it.isPerson }
        assertEquals(listOf(cast.id), people.map { it.id }.filter { it == cast.id })
        assertTrue(people.first().knownFor.isNotEmpty())
        assertTrue("people come after the titles", found.results.dropWhile { !it.isPerson }.all { it.isPerson })
    }

    @Test
    fun `a film's ratings are Rotten Tomatoes and IMDb, a series' Rotten Tomatoes, and none is null`() = runBlocking {
        seerr.signedIn()
        val film = seerr.ratings(MediaType.MOVIE, ebenezer)!!
        assertNotNull(film.rt?.criticsScore)
        assertTrue(film.rt!!.criticsRating in setOf("Certified Fresh", "Fresh", "Rotten"))
        assertNotNull(film.imdb?.criticsScore)
        val series = seerr.ratings(MediaType.TV, modernFamily)!!
        assertNotNull(series.rt?.audienceScore)
        assertNull(series.imdb)
        fake("/fake/ratings?imdb=0")
        assertNull(seerr.ratings(MediaType.MOVIE, ebenezer)!!.imdb)
        fake("/fake/ratings?rt=0")
        assertNull("a 404 is no ratings", seerr.ratings(MediaType.MOVIE, ebenezer))
        assertNull(seerr.ratings(MediaType.TV, modernFamily))
    }

    @Test
    fun `Similar and Recommendations are titles of the same type, the title itself left out, with records`() = runBlocking {
        seerr.signedIn()
        val similar = seerr.similar(MediaType.MOVIE, ebenezer)
        assertEquals(20, similar.results.size)
        assertTrue(similar.results.all { it.mediaType == "movie" && it.id != ebenezer })
        assertTrue(similar.results.any { it.mediaInfo != null })
        val second = seerr.similar(MediaType.MOVIE, ebenezer, page = 2)
        assertTrue(second.results.map { it.id }.intersect(similar.results.map { it.id }.toSet()).isEmpty())
        assertTrue(seerr.recommendations(MediaType.MOVIE, ebenezer).results.map { it.id } != similar.results.map { it.id })
        assertTrue(seerr.similar(MediaType.TV, modernFamily).results.all { it.mediaType == "tv" && it.id != modernFamily })
    }

    @Test
    fun `discover with a query filters and sorts the catalogue, and its paging holds`() = runBlocking {
        seerr.signedIn()
        val all = seerr.discover(MediaType.MOVIE, DiscoverQuery(sortBy = "popularity.desc"))
        assertTrue(all.totalPages > 1)
        assertEquals(20, all.results.size)
        val horror = seerr.discover(MediaType.MOVIE, DiscoverQuery(genre = "27"))
        assertTrue(horror.results.isNotEmpty() && horror.results.all { 27 in it.genreIds })
        val oldest = seerr.discover(MediaType.MOVIE, DiscoverQuery(sortBy = "primary_release_date.asc"))
        val dates = oldest.results.mapNotNull { it.releaseDate }
        assertEquals(dates.sorted(), dates)
        val named = seerr.discover(MediaType.TV, DiscoverQuery(sortBy = "original_name.asc", language = "en"))
        assertTrue(named.results.all { it.originalLanguage == "en" })
        val since = seerr.discover(MediaType.MOVIE, DiscoverQuery(releasedFrom = "2026-11-01"))
        assertTrue(since.results.isNotEmpty() && since.results.all { it.releaseDate!! >= "2026-11-01" })
        val zombies = seerr.discover(MediaType.MOVIE, DiscoverQuery(keywords = "12377"))
        assertTrue(zombies.totalResults in 1 until all.totalResults)
        assertEquals(zombies.results.map { it.id }, seerr.discover(MediaType.MOVIE, DiscoverQuery(keywords = "12377")).results.map { it.id })
        val (_, body) = rawSignedIn("/api/v1/discover/movies?keywords=12377")
        assertEquals("zombie", Json.parseToJsonElement(body).jsonObject["keywords"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        // without a query the rows are as they were
        assertEquals(seerr.popularMovies().results.map { it.id }, seerr.discover(MediaType.MOVIE, DiscoverQuery()).results.map { it.id })
    }

    /** A GET with this test's session cookie. */
    private suspend fun rawSignedIn(path: String): Pair<Int, String> {
        val cookie = jar.get(Url(base)).joinToString("; ") { "${it.name}=${it.value}" }
        val c = URI(base + path).toURL().openConnection() as HttpURLConnection
        c.setRequestProperty("Cookie", cookie)
        val code = c.responseCode
        return code to ((if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "")
    }

    @Test
    fun `genres, languages and streaming services are listed for the filters`() = runBlocking {
        seerr.signedIn()
        val genres = seerr.genres(MediaType.MOVIE)
        assertEquals(genres.map { it.name }.sorted(), genres.map { it.name })
        assertTrue(genres.any { it.id == 27 && it.name == "Horror" })
        assertTrue(seerr.genres(MediaType.TV).any { it.id == 10765 })
        assertTrue(seerr.languages().any { it.code == "nl" && it.englishName == "Dutch" })
        assertTrue(seerr.watchProviders(MediaType.MOVIE, "US").any { it.id == 8 && it.name == "Netflix" })
    }

    @Test
    fun `a download shows on the title's page, in its rows and in the requests list, and clears`() = runBlocking {
        seerr.signedIn()
        seerr.request(RequestBody("movie", ramayana))
        assertEquals(400, fakeStatus("/fake/download?title=movie:$godzilla&progress=10"))
        fake("/fake/download?title=movie:$ramayana&progress=42&left=75")
        val item = seerr.movie(ramayana).mediaInfo!!.downloadStatus.single()
        assertEquals(0.58, item.sizeLeft!! / item.size!!, 0.0001)
        assertEquals("01:15:00", item.timeLeft)
        assertNotNull(item.estimatedCompletionTime)
        assertTrue(seerr.movie(ramayana).mediaInfo!!.downloadStatus4k.isEmpty())
        assertEquals(1, seerr.trending().results.single { it.id == ramayana }.mediaInfo!!.downloadStatus.size)
        assertEquals(1, seerr.requests().results.first().media!!.downloadStatus.size)
        fake("/fake/download?title=movie:$ramayana")
        assertTrue(seerr.movie(ramayana).mediaInfo!!.downloadStatus.isEmpty())
    }

    @Test
    fun `the quota counts the account's own requests, and a request past it is refused in Seerr's words`() = runBlocking {
        val user = seerr.signedIn()
        assertNull("no limit as recorded", seerr.quota(user.id).movie.limit)
        fake("/fake/account?moviequota=1&moviedays=7&tvquota=2&tvdays=7")
        assertEquals(1, seerr.me().movieQuotaLimit)
        val fresh = seerr.quota(user.id)
        assertEquals(listOf(1, 0, 1), listOf(fresh.movie.limit, fresh.movie.used, fresh.movie.remaining))
        assertFalse(fresh.movie.restricted)

        seerr.request(RequestBody("movie", ebenezer))
        assertTrue(seerr.quota(user.id).movie.restricted)
        val refused = failsWith<SeerrException.Refused> { runBlocking { seerr.request(RequestBody("movie", godzilla)) } }
        assertEquals("Movie Quota exceeded.", refused.message)
        assertNull(seerr.movie(godzilla).mediaInfo)

        val tooMany = failsWith<SeerrException.Refused> { runBlocking { seerr.request(RequestBody("tv", modernFamily, seasons = listOf(1, 2, 3))) } }
        assertEquals("Series Quota exceeded.", tooMany.message)
        seerr.request(RequestBody("tv", modernFamily, seasons = listOf(1, 2)))
        assertEquals(listOf(2, 0), seerr.quota(user.id).tv.let { listOf(it.used, it.remaining) })
        failsWith<SeerrException.Refused> { runBlocking { seerr.quota(9) } }
        Unit
    }

    @Test
    fun `a pending request of one's own is cancelled, and the title goes back as Seerr moves it`() = runBlocking {
        seerr.signedIn()
        fake("/fake/account?autoapprove=0")
        val waiting = seerr.request(RequestBody("movie", godzilla))
        assertEquals(MediaStatus.PENDING, seerr.movie(godzilla).mediaInfo?.status)
        seerr.cancelRequest(waiting.id)
        val after = seerr.movie(godzilla).mediaInfo!!
        assertEquals(MediaStatus.UNKNOWN, after.status)
        assertTrue(after.requests.isEmpty())
        assertTrue(seerr.requests(take = 50).results.none { it.id == waiting.id })

        val series = seerr.request(RequestBody("tv", modernFamily, seasons = listOf(1, 2)))
        seerr.cancelRequest(series.id)
        assertTrue(takenSeasons(seerr.tv(modernFamily).mediaInfo, is4k = false).isEmpty())
        // it may be asked for again
        seerr.request(RequestBody("movie", godzilla))

        fake("/fake/account?autoapprove=1")
        val approved = seerr.request(RequestBody("movie", ebenezer))
        val refused = failsWith<SeerrException.Refused> { runBlocking { seerr.cancelRequest(approved.id) } }
        assertEquals("Seerr's 401 is a refusal, not a sign-out", "You do not have permission to delete this request.", refused.message)
        assertEquals(1, seerr.movie(ebenezer).mediaInfo!!.requests.size)
        assertEquals(404, failsWith<SeerrException.Http> { runBlocking { seerr.cancelRequest(424242) } }.status)
        // a plain 401 (no session) is still a sign-out
        failsWith<SeerrException.SignedOut> { runBlocking { client().cancelRequest(approved.id) } }
        Unit
    }

    @Test
    fun `issues are reported with Create Issues, listed as one's own, others' with View Issues, and refused without`() = runBlocking {
        val user = seerr.signedIn()
        val record = seerr.movie(1423191).mediaInfo!!
        val body = IssueBody(IssueType.SUBTITLES, "The subtitles are missing.", record.id)
        val noRight = failsWith<SeerrException.Refused> { runBlocking { seerr.reportIssue(body) } }
        assertNotNull("read by asking auth/me, not a sign-out", noRight)
        failsWith<SeerrException.Refused> { runBlocking { seerr.issues() } }
        assertTrue("no issues on a page without the right", seerr.movie(1423191).mediaInfo!!.issues.isEmpty())

        fake("/fake/account?createissues=1")
        val made = seerr.reportIssue(body)
        assertEquals(IssueType.SUBTITLES, made.issueType)
        assertEquals(IssueStatus.OPEN, made.status)
        assertEquals(user.id, made.createdBy?.id)
        assertEquals(listOf("The subtitles are missing."), made.comments.map { it.message })
        assertEquals(1423191, made.media?.tmdbId)
        assertEquals(listOf(made.id), seerr.issues().results.map { it.id })
        assertEquals(listOf(made.id), seerr.movie(1423191).mediaInfo!!.issues.map { it.id })
        assertTrue("a page's issues carry no comments", seerr.movie(1423191).mediaInfo!!.issues.single().comments.isEmpty())
        assertEquals("The subtitles are missing.", seerr.issue(made.id).comments.first().message)
        assertEquals(404, failsWith<SeerrException.Http> { runBlocking { seerr.reportIssue(body.copy(mediaId = 424242)) } }.status)

        fake("/fake/account?viewissues=1")
        val everyone = seerr.issues()
        assertEquals(2, everyone.results.size)
        assertEquals(setOf(user.id, 1), everyone.results.mapNotNull { it.createdBy?.id }.toSet())
        assertEquals(listOf(made.id), seerr.issues(filter = "open", take = 1).results.map { it.id })
        val others = everyone.results.single { it.createdBy?.id == 1 }
        fake("/fake/account?viewissues=0")
        failsWith<SeerrException.Refused> { runBlocking { seerr.issue(others.id) } }
        Unit
    }

    @Test
    fun `the streaming region is the server's, as recorded, and switched`() = runBlocking {
        assertEquals("US", seerr.publicSettings().streamingRegion)
        fake("/fake/server?region=ar")
        assertEquals("AR", seerr.publicSettings().streamingRegion)
        seerr.signedIn()
        assertEquals("", seerr.me().settings?.streamingRegion)
    }

    @Test
    fun `reset puts M6's switches back and forgets the issues`() = runBlocking {
        seerr.signedIn()
        fake("/fake/account?recent=1&createissues=1&moviequota=1&moviedays=7")
        seerr.reportIssue(IssueBody(IssueType.VIDEO, "x", seerr.movie(1423191).mediaInfo!!.id))
        fake("/fake/discover?preset=custom")
        fake("/fake/ratings?rt=0&imdb=0")
        fake("/fake/server?region=AR")
        fake("/fake/reset")
        assertEquals(67744, seerr.me().permissions)
        assertNull(seerr.me().movieQuotaLimit)
        assertEquals((1..12).toList(), seerr.discoverSliders().map { it.id })
        assertNotNull(seerr.ratings(MediaType.MOVIE, ebenezer))
        assertEquals("US", seerr.publicSettings().streamingRegion)
        fake("/fake/account?viewissues=1")
        assertEquals(listOf(1), seerr.issues().results.mapNotNull { it.createdBy?.id })
    }

    /** A cookie store that can stop showing the CSRF token, so a write goes out without the echo. */
    private class Hiding(private val inner: CookiesStorage = AcceptAllCookiesStorage()) : CookiesStorage by inner {
        var hideXsrf = false

        override suspend fun get(requestUrl: Url): List<Cookie> =
            inner.get(requestUrl).filter { !(hideXsrf && it.name == "XSRF-TOKEN") }
    }
}
