package com.tomdebres.hither.core

import com.tomdebres.hither.core.rules.signInMethods
import com.tomdebres.hither.core.rules.wantsSeerrVersion
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.TimeSource

/**
 * M8's ways in, end to end over the app's own engine, against `scripts/fake-seerr` and the fake plex.tv it serves at
 * `/plextv`: what each method offers, each signs in and holds a session, and each failure is the one the screen words.
 * The made-up accounts are the fake's (its docstring).
 */
class FakeSignInTest {
    companion object {
        private lateinit var fake: Process
        private lateinit var base: String

        @BeforeClass
        @JvmStatic
        fun start() {
            val module = File(System.getProperty("user.dir"))
            val script = module.resolve("../scripts/fake-seerr").canonicalFile
            val log = module.resolve("build/fake-signin-test.log").also { it.parentFile.mkdirs() }
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

    /** Quick, so a test waits a fraction of a second, not 2 s a look. */
    private val quick = PollPace(everyMillis = 50, longestMillis = 200, giveUpAfter = 3)
    private val clients = mutableListOf<SeerrClient>()
    private val plexClients = mutableListOf<PlexTv>()

    private fun seerr() = SeerrClient(OkHttp.create(), base, AcceptAllCookiesStorage()).also { clients += it }
    private fun plexTv(at: String = "$base/plextv") = PlexTv(OkHttp.create(), "test-client-id", at).also { plexClients += it }

    private fun fake(pathAndQuery: String): String {
        val c = URI(base + pathAndQuery).toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.outputStream.close()
        assertEquals("$pathAndQuery answered", 200, c.responseCode)
        return c.inputStream.bufferedReader().readText()
    }

    /** Does [what] from another thread once [after] ms have passed: the person at plex.tv/link or in Jellyfin. */
    private fun later(after: Long, what: () -> Unit) = thread { Thread.sleep(after); what() }

    @Before
    fun fresh() {
        fake("/fake/reset")
    }

    @After
    fun close() {
        clients.forEach { it.close() }
        plexClients.forEach { it.close() }
    }

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

    private suspend fun methodsOf(client: SeerrClient) = client.publicSettings().let { settings ->
        signInMethods(settings, if (wantsSeerrVersion(settings)) client.status().version else null)
    }

    // ---- what a server offers ----------------------------------------------------------------------------------

    @Test
    fun `each server offers its own ways in - read from settings public and status`() = runBlocking<Unit> {
        val plex = methodsOf(seerr())
        assertTrue(plex.local && plex.plex && !plex.mediaServerPassword)
        fake("/fake/signin?type=jellyfin")
        val jellyfin = methodsOf(seerr())
        assertTrue(jellyfin.local && jellyfin.mediaServerPassword && jellyfin.quickConnect && !jellyfin.plex)
        fake("/fake/signin?version=3.3.0")
        assertFalse("Quick Connect offered on Seerr 3.3.0", methodsOf(seerr()).quickConnect)
        fake("/fake/signin?type=emby&version=3.5.2&local=0")
        val emby = methodsOf(seerr())
        assertTrue(!emby.local && emby.mediaServerPassword && !emby.quickConnect)
        fake("/fake/signin?mediaserver=0&local=1")
        assertFalse(methodsOf(seerr()).anyMediaServer)
    }

    // ---- Jellyfin and Emby: username and password -------------------------------------------------------------

    @Test
    fun `a Jellyfin member signs in with username and password and holds a session`() = runBlocking<Unit> {
        fake("/fake/signin?type=jellyfin")
        val client = seerr()
        val user = client.signInWithMediaServer("robin", "robin-password")
        assertEquals(12, user.id)
        assertEquals("Robin", user.displayName)
        assertEquals(12, client.me().id)
        assertTrue(client.trending().results.isNotEmpty())
    }

    @Test
    fun `Emby signs in the same way - its wrong password is wrong credentials`() = runBlocking<Unit> {
        fake("/fake/signin?type=emby")
        assertEquals(12, seerr().signInWithMediaServer("robin", "robin-password").id)
        val wrong = failsWith<SeerrException.Refused> { seerr().signInWithMediaServer("robin", "nope") }
        assertEquals(SignInFailure.WRONG_CREDENTIALS, signInFailure(wrong))
    }

    @Test
    fun `a member Seerr has not imported is refused as not imported - unless new sign-in is on`() = runBlocking<Unit> {
        fake("/fake/signin?type=jellyfin&newlogin=0")
        val refused = failsWith<SeerrException.NotImported> { seerr().signInWithMediaServer("sasha", "sasha-password") }
        assertEquals(SignInFailure.NOT_IMPORTED, signInFailure(refused))
        fake("/fake/signin?newlogin=1")
        assertEquals("Sasha", seerr().signInWithMediaServer("sasha", "sasha-password").displayName)
    }

    @Test
    fun `media-server sign-in switched off is said - on Jellyfin and for Quick Connect on Emby`() = runBlocking<Unit> {
        fake("/fake/signin?type=jellyfin&mediaserver=0")
        assertEquals(SignInFailure.METHOD_OFF, signInFailure(failsWith<SeerrException.SignInOff> { seerr().signInWithMediaServer("robin", "robin-password") }))
        fake("/fake/signin?type=emby&mediaserver=1")
        assertEquals(SignInFailure.METHOD_OFF, signInFailure(failsWith<SeerrException.SignInOff> { seerr().startQuickConnect() }))
    }

    // ---- Quick Connect ----------------------------------------------------------------------------------------

    @Test
    fun `Quick Connect - the code approved in Jellyfin signs the member in`() = runBlocking<Unit> {
        fake("/fake/signin?type=jellyfin")
        val client = seerr()
        val qc = QuickConnectSignIn(client, quick, TimeSource.Monotonic, 60_000)
        val code = qc.newCode()
        assertTrue("not six digits: ${code.code}", code.code.matches(Regex("\\d{6}")))
        later(300) { fake("/fake/quickconnect/approve?code=${code.code}&user=robin") }
        val user = qc.signIn(code)
        assertEquals("Robin", user.displayName)
        assertEquals(12, client.me().id)
    }

    @Test
    fun `Quick Connect - a code nobody approves in time is expired - one that will not start is unavailable`() = runBlocking<Unit> {
        fake("/fake/signin?type=jellyfin")
        fake("/fake/quickconnect?life=0.3")
        val qc = QuickConnectSignIn(seerr(), quick, TimeSource.Monotonic, 60_000)
        assertEquals(SignInFailure.CODE_EXPIRED, signInFailure(failsWith<SeerrException.CodeExpired> { qc.signIn(qc.newCode()) }))
        fake("/fake/quickconnect?off=1")
        assertEquals(SignInFailure.QUICK_CONNECT_UNAVAILABLE, signInFailure(failsWith<SeerrException.Http> { qc.newCode() }))
    }

    @Test
    fun `Quick Connect - approved by a member Seerr has not imported is not imported`() = runBlocking<Unit> {
        fake("/fake/signin?type=jellyfin&newlogin=0")
        val qc = QuickConnectSignIn(seerr(), quick, TimeSource.Monotonic, 60_000)
        val code = qc.newCode()
        later(200) { fake("/fake/quickconnect/approve?code=${code.code}&user=sasha") }
        assertEquals(SignInFailure.NOT_IMPORTED, signInFailure(failsWith<SeerrException.NotImported> { qc.signIn(code) }))
    }

    // ---- Plex: the link code ----------------------------------------------------------------------------------

    @Test
    fun `Plex - the code entered at plex-tv-link signs the member in - plex-tv saw the product and the client id only`() = runBlocking<Unit> {
        val client = seerr()
        val plex = PlexSignIn(plexTv(), quick, TimeSource.Monotonic)
        val code = plex.newCode()
        assertTrue("not four characters: ${code.code}", code.code.matches(Regex("[A-Z0-9]{4}")))
        assertTrue("plex.tv's 15 minutes: ${code.lifeSeconds}", code.lifeSeconds in 890..900)
        later(300) { fake("/fake/plex/link?code=${code.code}&account=member") }
        val user = plex.signIn(code, client)
        assertEquals(14, user.id)
        assertEquals(14, client.me().id)
        val seen = Json.parseToJsonElement(fake("/fake/plex")).jsonObject["seen"]!!.jsonArray
        assertTrue("plex.tv was not asked", seen.size >= 2)
        seen.forEach { call ->
            val headers = call.jsonObject["headers"]!!.jsonObject
            assertEquals("what plex.tv saw: $headers", setOf("x-plex-product", "x-plex-client-identifier"), headers.keys.map { it.lowercase() }.toSet())
            assertEquals("Hither", headers.entries.single { it.key.equals("X-Plex-Product", true) }.value.jsonPrimitive.content)
            assertEquals("test-client-id", headers.entries.single { it.key.equals("X-Plex-Client-Identifier", true) }.value.jsonPrimitive.content)
        }
    }

    @Test
    fun `Plex - an account without the server's Plex is refused - a newcomer only while new sign-in is off`() = runBlocking<Unit> {
        val plex = PlexSignIn(plexTv(), quick, TimeSource.Monotonic)
        val stranger = plex.newCode()
        later(150) { fake("/fake/plex/link?code=${stranger.code}&account=stranger") }
        assertEquals(SignInFailure.NOT_IMPORTED, signInFailure(failsWith<SeerrException.NotImported> { plex.signIn(stranger, seerr()) }))
        fake("/fake/signin?newlogin=0")
        val newcomer = plex.newCode()
        later(150) { fake("/fake/plex/link?code=${newcomer.code}&account=newcomer") }
        failsWith<SeerrException.NotImported> { plex.signIn(newcomer, seerr()) }
        fake("/fake/signin?newlogin=1")
        val again = plex.newCode()
        later(150) { fake("/fake/plex/link?code=${again.code}&account=newcomer") }
        assertEquals("Plex Newcomer", plex.signIn(again, seerr()).displayName)
    }

    @Test
    fun `Plex - a code nobody enters in time is expired`() = runBlocking<Unit> {
        fake("/fake/plex?life=0.3")
        val plex = PlexSignIn(plexTv(), quick, TimeSource.Monotonic)
        assertEquals(SignInFailure.CODE_EXPIRED, signInFailure(failsWith<SeerrException.CodeExpired> { plex.signIn(plex.newCode(), seerr()) }))
    }

    @Test
    fun `Plex - plex-tv not answering is plex-tv unreachable - down or no one there`() = runBlocking<Unit> {
        fake("/fake/plex?down=1")
        assertEquals(SignInFailure.PLEX_UNREACHABLE, signInFailure(failsWith<SeerrException.PlexUnreachable> { PlexSignIn(plexTv(), quick, TimeSource.Monotonic).newCode() }))
        val nobody = ServerSocket(0).use { it.localPort }
        failsWith<SeerrException.PlexUnreachable> { PlexSignIn(plexTv("http://127.0.0.1:$nobody"), quick, TimeSource.Monotonic).newCode() }
    }

    @Test
    fun `Plex - sign-in switched off on the server is said - after the code was entered`() = runBlocking<Unit> {
        fake("/fake/signin?mediaserver=0")
        val plex = PlexSignIn(plexTv(), quick, TimeSource.Monotonic)
        val code = plex.newCode()
        later(150) { fake("/fake/plex/link?code=${code.code}&account=member") }
        assertEquals(SignInFailure.METHOD_OFF, signInFailure(failsWith<SeerrException.SignInOff> { plex.signIn(code, seerr()) }))
    }
}
