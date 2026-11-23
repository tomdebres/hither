package com.tomdebres.hither.ui.discover

import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.rules.requestRows
import com.tomdebres.hither.ready.seerrReadySource
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.ui.requests.seerrRecentRequestsSource
import com.tomdebres.hither.ui.requests.seerrRequestsSource
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Whose requests each list reads (Tom, 2026-11-13, DECISIONS 118), through the real client against
 * `scripts/fake-seerr`, with requests by two made-up, named users on the server. With View Requests,
 * Recent Requests is Seerr's slider: everyone's, newest first, and the plan names who asked. My Requests
 * and the home screen's look (since-last-seen, the Ready to watch channel and Watch Next all read it) stay
 * the account's own. Without the right, Recent Requests is the account's own too, and no one is named.
 */
class RequestScopeTest {
    companion object {
        private lateinit var fake: Process
        private lateinit var base: String

        @BeforeClass
        @JvmStatic
        fun start() {
            val module = File(System.getProperty("user.dir"))
            val script = module.resolve("../scripts/fake-seerr").canonicalFile
            check(script.isFile) { "no fake at $script" }
            val log = module.resolve("build/fake-seerr-scope-test.log").also { it.parentFile.mkdirs() }
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

    /** One of the fake's own switches, from the Mac's side: never something the app calls. */
    private fun fake(pathAndQuery: String) {
        val c = URI(base + pathAndQuery).toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.outputStream.close()
        assertEquals("$pathAndQuery answered", 200, c.responseCode)
    }

    /** The family's account (user 8), signed in as the app signs in. */
    private fun family(): SeerrAccess {
        val client = SeerrClient(OkHttp.create(), base, AcceptAllCookiesStorage())
        runBlocking { assertEquals(8, client.signIn("tv@example.invalid", "right-password").id) }
        return object : SeerrAccess {
            override suspend fun <T> call(call: suspend (SeerrClient) -> T): T = call(client)
        }
    }

    @Before
    fun reset() {
        fake("/fake/reset")
        fake("/fake/requests?household=1")
    }

    @Test
    fun `with View Requests, Recent Requests is everyone's and names them, and the account's own lists stay its own`() = runBlocking {
        fake("/fake/account?view=1")
        val access = family()

        val recent = requestRows(seerrRecentRequestsSource(access).page(skip = 0, take = RECENT_TAKE).results)
        assertEquals(
            "everyone's, newest first",
            listOf("Robin", "Sasha", "Robin", "User 8", "User 8", "User 8", "User 1"),
            recent.map { it.requester },
        )
        assertTrue("the plan names who asked", seerrPlan(access).plan().requesters)

        val own = seerrRequestsSource(access, userId = 8).page(skip = 0, take = 10).results
        assertEquals("My Requests: the account's own", listOf(8, 8, 8), own.map { it.requestedBy?.id })
        val look = seerrReadySource(access).requests(8)
        assertEquals("the home screen's look: the account's own", listOf(8, 8, 8), look.map { it.requestedBy?.id })
    }

    @Test
    fun `without View Requests, Recent Requests is the account's own and names no one`() = runBlocking {
        fake("/fake/account?view=0")
        val access = family()

        val recent = seerrRecentRequestsSource(access).page(skip = 0, take = RECENT_TAKE).results
        assertEquals(listOf(8, 8, 8), recent.map { it.requestedBy?.id })
        assertFalse("the plan names no one", seerrPlan(access).plan().requesters)
    }
}
