package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.model.Keyword
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.home.HomeScreen
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A keyword's titles (M6, DECISIONS 98), the whole home through the real client against a scripted
 * server: a tag on a title's page opens Seerr's grid of that keyword's films under "<keyword> Movies";
 * a title from it opens its page; Back returns to the grid's card, then to the first page with focus on
 * the tag, the page as it was (not read again). A page Back has left is dropped: opened again, it is
 * read again.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class KeywordTitlesTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val session = Session(User(id = 8, displayName = "TV", permissions = 32), PublicSettings(applicationTitle = "Seerr", localLogin = true))

    /** Every path the scripted server was asked, with its query. */
    private val asked = CopyOnWriteArrayList<String>()

    /** Film 3's page waits here; whether its call was given up (its page's work stopped). */
    private val gate = CompletableDeferred<Unit>()
    @Volatile private var film3GivenUp = false

    private val access = object : SeerrAccess {
        private val client = SeerrClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                asked += path + (request.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
                val json = SeerrClient.json
                val keywords = request.url.parameters["keywords"]
                if (path == "/api/v1/movie/3") {
                    try {
                        gate.await()
                    } catch (e: CancellationException) {
                        film3GivenUp = true
                        throw e
                    }
                }
                val body = when {
                    path.endsWith("/ratingscombined") -> return@MockEngine respond("""{"message":"No ratings found."}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
                    path.startsWith("/api/v1/movie/") -> {
                        val id = path.substringAfterLast('/').toInt()
                        json.encodeToString(
                            MovieDetails.serializer(),
                            MovieDetails(id = id, title = "Film $id", overview = "About it.", status = "Released", keywords = listOf(Keyword(11, "heist"), Keyword(12, "robbery"))),
                        )
                    }
                    path == "/api/v1/settings/public" -> json.encodeToString(PublicSettings.serializer(), session.settings)
                    path == "/api/v1/auth/me" -> json.encodeToString(User.serializer(), session.user)
                    // A keyword's films: eight for "robbery", none for "heist".
                    keywords != null -> json.encodeToString(
                        Page.serializer(MediaResult.serializer()),
                        Page(results = if (keywords == "12") (101..108).map { MediaResult(id = it, mediaType = "movie", title = "Film $it") } else emptyList()),
                    )
                    // Every row: three films, 1 to 3.
                    else -> json.encodeToString(
                        Page.serializer(MediaResult.serializer()),
                        Page(results = (1..3).map { MediaResult(id = it, mediaType = "movie", title = "Film $it") }),
                    )
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
            "http://seerr.test",
            AcceptAllCookiesStorage(),
        )

        override suspend fun <T> call(call: suspend (SeerrClient) -> T): T = call(client)
    }

    private fun show() {
        rule.setContent { DpadHost { HomeScreen(Screen.Home(session), access, onSignOut = {}) } }
        await("card movie:1")
    }

    private fun await(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "a node without a tag" } ?: "a node without a tag"
    }.getOrElse { "nothing" }

    private fun assertFocused(tag: String) {
        runCatching { rule.waitUntil(5_000) { focusedTag() == tag } }
        val now = focusedTag()
        if (now != tag) throw AssertionError("expected focus on $tag, it is on $now")
    }

    private fun reads(id: Int) = asked.count { it == "/api/v1/movie/$id" }

    /** From Discover's first card to its page's "robbery" tag. */
    private fun toTheTag(name: String) {
        assertFocused("card movie:1")
        press(Key.DirectionCenter)
        await("action Request")
        assertFocused("action Request")
        // the overview, then the keywords' shelf (A4: Information, where the facts went, takes no focus; this server
        // gives no Recommendations or Similar: its details route takes their paths)
        press(Key.DirectionDown, 2)
        assertFocused("keyword heist")
        if (name == "robbery") press(Key.DirectionRight)
        assertFocused("keyword $name")
    }

    @Test
    fun aTagOpensItsTitlesAndBackReturnsToTheCardThenToTheTagOnThePageAsItWas() {
        show()
        toTheTag("robbery")
        press(Key.DirectionCenter)
        await("grid heading")
        rule.onNodeWithTag("grid heading").assertTextEquals("Robbery Movies")
        // the page's title in grey, as every pushed page's (A1, `Page`; Seerr's gradient went): no white glyphs and no hue
        val heading = rule.onNodeWithTag("grid heading").captureToImage().toPixelMap()
        var white = 0
        var hued = 0
        var grey = 0
        for (x in 0 until heading.width) for (y in 0 until heading.height) {
            val c = heading[x, y]
            if (c.red > 0.9f && c.green > 0.9f && c.blue > 0.9f) white++
            if (maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue) > 0.1f) hued++
            if (c.red > 0.5f && c.red < 0.75f && kotlin.math.abs(c.red - c.blue) < 0.05f) grey++
        }
        assertEquals("white pixels in the title", 0, white)
        assertEquals("pixels with a hue in the title", 0, hued)
        assertTrue("the title's grey ($grey pixels)", grey > 50)
        assertEquals(listOf("/api/v1/discover/movies?keywords=12&page=1"), asked.filter { "keywords" in it })
        assertFocused("card movie:101")
        press(Key.DirectionRight)
        assertFocused("card movie:102")
        press(Key.DirectionCenter)
        await("media title")
        rule.onNodeWithTag("media title").assertTextEquals("Film 102")

        back()
        assertFocused("card movie:102")
        rule.onNodeWithTag("grid heading").assertTextEquals("Robbery Movies")
        assertEquals("the grid was not asked for again", 1, asked.count { "keywords" in it })

        back()
        assertFocused("keyword robbery")
        rule.onNodeWithTag("media title").assertTextEquals("Film 1")
        assertEquals("the page was kept, not read again", 1, reads(1))

        back()
        assertFocused("card movie:1")
        // a page Back has left is dropped: opened again, it is read again
        press(Key.DirectionCenter)
        await("action Request")
        rule.waitUntil(5_000) { reads(1) == 2 }
        assertFocused("action Request")
    }

    @Test
    fun aTagWithNoTitlesSaysSoAndBackReturnsToTheTag() {
        show()
        toTheTag("heist")
        press(Key.DirectionCenter)
        await("no results")
        rule.onNodeWithTag("grid heading").assertTextEquals("Heist Movies")
        back()
        assertFocused("keyword heist")
    }

    @Test
    fun backFromAPageStillLoadingStopsItsLoading() {
        show()
        assertFocused("card movie:1")
        press(Key.DirectionRight, 2)
        assertFocused("card movie:3")
        press(Key.DirectionCenter)
        await("loading")
        rule.waitUntil(5_000) { asked.contains("/api/v1/movie/3") }
        back()
        assertFocused("card movie:3")
        // the page Back left is dropped and its scope cancelled: the call it was waiting on is given up
        rule.waitUntil(5_000) { film3GivenUp }
        gate.complete(Unit)
    }
}
