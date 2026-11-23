package com.tomdebres.hither.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import org.junit.Assert.assertTrue
import com.tomdebres.hither.core.rules.personPage
import com.tomdebres.hither.ui.person.PersonModel
import com.tomdebres.hither.ui.person.PersonScreen
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.model.CastMember
import com.tomdebres.hither.core.model.Credits
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PersonCredit
import com.tomdebres.hither.core.model.PersonCredits
import com.tomdebres.hither.core.model.PersonDetails
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.LocalRecompositionProbe
import com.tomdebres.hither.ui.home.HomeScreen
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.getBoundsInRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * People (M6, DECISIONS 100), the whole home through the real client against a scripted server: a cast
 * portrait opens the person's page, read once; its biography is one stop (A4); Down reaches their titles;
 * a title opens over it and Back returns to its lockup, then to the portrait; Recommendations and Similar
 * under the cast open their titles the same way; search lists people and opens their pages. And (A4) a
 * person's page opens at its top, its biography focused, "More" opening the whole of it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PersonPageTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val session = Session(User(id = 8, displayName = "TV", permissions = 32), PublicSettings(applicationTitle = "Seerr", localLogin = true))
    private val asked = CopyOnWriteArrayList<String>()
    private val counts = HashMap<String, Int>()

    private val access = object : SeerrAccess {
        private val client = SeerrClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                asked += path
                val json = SeerrClient.json
                val ok = { body: String -> respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
                val films = { from: Int -> json.encodeToString(Page.serializer(MediaResult.serializer()), Page(results = (from until from + 6).map { MediaResult(id = it, mediaType = "movie", title = "Film $it") })) }
                when {
                    path.endsWith("/ratingscombined") -> respond("""{"message":"No ratings found."}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
                    path.endsWith("/recommendations") -> ok(films(300))
                    path.endsWith("/similar") -> ok(films(400))
                    path == "/api/v1/person/77/combined_credits" -> ok(
                        json.encodeToString(
                            PersonCredits.serializer(),
                            PersonCredits(id = 77, cast = (201..206).map { PersonCredit(id = it, mediaType = "movie", title = "Film $it", voteCount = 300 - it, character = "Part $it", backdropPath = "/b$it.jpg".takeIf { _ -> it == 202 }) }),
                        ),
                    )
                    // Sam Second's page fails: the server refuses it
                    path == "/api/v1/person/78" -> respond("""{"message":"Unable to retrieve person."}""", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json"))
                    path == "/api/v1/person/77" -> ok(
                        json.encodeToString(
                            PersonDetails.serializer(),
                            PersonDetails(id = 77, name = "Pat Person", knownForDepartment = "Acting", birthday = "1980-02-03", placeOfBirth = "Example City", biography = "First paragraph.\n\nSecond paragraph."),
                        ),
                    )
                    path.startsWith("/api/v1/movie/") -> {
                        val id = path.substringAfterLast('/').toInt()
                        ok(json.encodeToString(MovieDetails.serializer(), MovieDetails(id = id, title = "Film $id", overview = "About it.", credits = Credits(cast = listOf(CastMember(id = 77, name = "Pat Person", character = "Lead"), CastMember(id = 78, name = "Sam Second", character = "Friend"))))))
                    }
                    path == "/api/v1/settings/public" -> ok(json.encodeToString(PublicSettings.serializer(), session.settings))
                    path == "/api/v1/auth/me" -> ok(json.encodeToString(User.serializer(), session.user))
                    path == "/api/v1/search" -> ok(
                        json.encodeToString(
                            Page.serializer(MediaResult.serializer()),
                            Page(results = listOf(MediaResult(id = 9, mediaType = "movie", title = "Film 9"), MediaResult(id = 77, mediaType = "person", name = "Pat Person"))),
                        ),
                    )
                    else -> ok(films(1))
                }
            },
            "http://seerr.test",
            AcceptAllCookiesStorage(),
        )

        override suspend fun <T> call(call: suspend (SeerrClient) -> T): T = call(client)
    }

    private fun show() {
        rule.setContent {
            CompositionLocalProvider(LocalRecompositionProbe provides { name -> counts[name] = (counts[name] ?: 0) + 1 }) {
                DpadHost { HomeScreen(Screen.Home(session), access, onSignOut = {}) }
            }
        }
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

    /** From Discover's first card down its page to the cast's [n]th card (from 0). */
    private fun toCast(n: Int = 0) {
        assertFocused("card movie:1")
        press(Key.DirectionCenter)
        await("action Request")
        assertFocused("action Request")
        // the overview, then the cast (this page has no facts and no keywords)
        press(Key.DirectionDown, 2)
        assertFocused("person cast:0:77")
        if (n > 0) press(Key.DirectionRight, n)
    }

    /**
     * A person's page that fails keeps its header at rest: the photo stays under the top margin, whole, with Try
     * Again focused below it (the polish 2 audit's item 20: the focus line carried the photo off the top).
     */
    @Test
    fun aPageThatFailsKeepsItsHeaderAtRestOverTryAgain() {
        show()
        toCast(1)
        assertFocused("person cast:1:78")
        press(Key.DirectionCenter)
        await("try again")
        assertFocused("try again")
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val photo = rule.onNodeWithTag("person photo").getBoundsInRoot()
        // the header at the page's top (A4: `Metrics.PersonTop`, tvOS's 140 points), the page not scrolled
        assertEquals("the photo's top (no scroll)", 70f, photo.top.value, 1f)
        rule.onNodeWithTag("person backdrop").assertDoesNotExist()
    }

    @Test
    fun aCastCardOpensThePersonAndBackWalksHomeCardByCard() {
        show()
        toCast()
        press(Key.DirectionCenter)
        await("person name")
        rule.onNodeWithTag("person name").assertTextEquals("Pat Person")
        rule.onNodeWithText("Born February 3, 1980 | Example City", substring = true, useUnmergedTree = true)
        // a title's backdrop across the top (the polish 2 audit's item 18): film 202's, the first with one
        rule.onNodeWithTag("person backdrop").assertExists()
        // the biography is one stop (A4's TextStop; it was a stop per paragraph), then the credits
        assertFocused("person biography")
        press(Key.DirectionDown)
        assertFocused("card movie:201")
        press(Key.DirectionRight, 2)
        assertFocused("card movie:203")
        press(Key.DirectionCenter)
        await("media title")
        rule.onNodeWithTag("media title").assertTextEquals("Film 203")
        back()
        assertFocused("card movie:203")
        rule.onNodeWithTag("person name").assertTextEquals("Pat Person")
        assertEquals("the person was read once", 1, asked.count { it == "/api/v1/person/77" })
        back()
        assertFocused("person cast:0:77")
        back()
        assertFocused("card movie:1")
    }

    @Test
    fun recommendationsAndSimilarOpenTheirTitlesAndBackReturnsToTheCard() {
        show()
        toCast()
        press(Key.DirectionDown)
        assertFocused("card movie:300")
        press(Key.DirectionDown)
        assertFocused("card movie:400")
        press(Key.DirectionRight)
        assertFocused("card movie:401")
        press(Key.DirectionCenter)
        await("media title")
        rule.onNodeWithTag("media title").assertTextEquals("Film 401")
        back()
        assertFocused("card movie:401")
        rule.onNodeWithTag("media title").assertTextEquals("Film 1")
    }

    @Test
    fun searchListsPeopleAndOpensTheirPages() {
        show()
        // the bar's search pill, as Discover's Up then Right reach it
        assertFocused("card movie:1")
        press(Key.DirectionUp)
        press(Key.DirectionRight, 2)
        assertFocused("search")
        press(Key.DirectionCenter)
        await("search field input")
        assertFocused("search field input")
        rule.onNodeWithTag("search field input").performTextInput("pat")
        press(Key.Enter)
        await("person person:77")
        press(Key.DirectionDown)
        assertFocused("card movie:9")
        press(Key.DirectionRight)
        assertFocused("person person:77")
        press(Key.DirectionCenter)
        await("person name")
        rule.onNodeWithTag("person name").assertTextEquals("Pat Person")
        back()
        assertFocused("person person:77")
    }
    /**
     * A person's page opens at its top (A4; tvOS's F5: arrival scrolled the header up into the top margin): with a header
     * taller than its photo (a long name, two lines of each fact), focus arrives on the biography and the page is not scrolled,
     * the photo whole under the top margin. "More" opens the whole biography in the reading screen, and Back returns to it.
     */
    @Test
    fun thePageOpensAtItsTopWithTheBiographyFocused() {
        val details = PersonDetails(
            id = 77,
            name = "Patricia Persephone Longname-Doubleworth, Third of Her Name",
            birthday = "1980-02-03",
            placeOfBirth = "Example City, Example County, Example Region, Example Country, Somewhere Further Away",
            // TMDB's lists run long: the name in several scripts
            alsoKnownAs = listOf("Pat", "Patty Longname", "P. P. Longname-Doubleworth", "Patricia the Third", "Patricia Persephone", "パトリシア", "Патриция Лонгнейм", "帕特里夏"),
            biography = (1..12).joinToString("\n\n") { "Paragraph $it of a biography long enough to run over several lines at ten feet, and then some more." },
        )
        val credits = PersonCredits(id = 77, cast = (201..206).map { PersonCredit(id = it, mediaType = "movie", title = "Film $it", character = "Part $it") })
        lateinit var model: PersonModel
        rule.setContent {
            val scope = rememberCoroutineScope()
            model = remember { PersonModel({ _ -> personPage(details, credits, "http://seerr.test") }, 77, "Pat Person", scope) }
            DpadHost { PersonScreen(model, onOpen = {}, onBack = {}) }
        }
        await("card movie:201")
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals("the page opened scrolled (px)", 0, rule.runOnIdle { model.scroll.value })
        val photo = rule.onNodeWithTag("person photo").fetchSemanticsNode()
        assertTrue("the photo is whole under the top margin (at ${photo.positionInRoot.y / rule.density.density} dp)", photo.positionInRoot.y >= 27 * rule.density.density)
        assertFocused("person biography")
        // four lines and "More"; Select opens the whole, Back returns to the stop with the page still at its top
        rule.onNodeWithTag("person biography more", useUnmergedTree = true).assertExists()
        press(Key.DirectionCenter)
        await("biography screen")
        assertFocused("biography whole")
        rule.onNode(hasText("Paragraph 12 ", substring = true) and hasAnyAncestor(hasTestTag("biography whole")), useUnmergedTree = true).assertExists()
        back()
        rule.waitUntil(3_000) { rule.onAllNodesWithTag("biography screen").fetchSemanticsNodes().isEmpty() }
        assertFocused("person biography")
        // Down to the credits and back up: the page returns to its top
        press(Key.DirectionDown)
        assertFocused("card movie:201")
        press(Key.DirectionUp)
        assertFocused("person biography")
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals("Up to the biography left the page scrolled (px)", 0, rule.runOnIdle { model.scroll.value })
    }

    /**
     * A person the server knows nothing more of (no biography, no credits) still has focus once the page is there (A5,
     * the review of A4: the arrival ended on nothing as the loading placeholder went): on the words that say so, which
     * draw no focus of their own, as a `ReadingView` draws none; no press moves it, and Back leaves.
     */
    @Test
    fun anEmptyPersonPageHasFocus() {
        var left = 0
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { PersonModel({ _ -> personPage(PersonDetails(id = 78, name = "Nobody Known"), PersonCredits(id = 78), "http://seerr.test") }, 78, "Nobody Known", scope) }
            DpadHost { PersonScreen(model, onOpen = {}, onBack = { left++ }) }
        }
        await("person empty")
        assertFocused("person empty")
        assertEquals("There is nothing more about this person.", rule.onNodeWithTag("person empty words", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text })
        for (key in listOf(Key.DirectionDown, Key.DirectionUp, Key.DirectionLeft, Key.DirectionRight)) {
            press(key)
            assertFocused("person empty")
        }
        back()
        assertEquals("Back leaves", 1, left)
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
    }

    /** What recomposed for one move, by card: a card's item, its view and the person card count as one. */
    private fun moved(key: Key): Map<String, Int> {
        settle()
        rule.runOnIdle { counts.clear() }
        press(key)
        settle()
        val taken = rule.runOnIdle { HashMap(counts).also { counts.clear() } }
        return taken.entries.groupBy({ it.key.substringAfterLast(' ') }, { it.value }).mapValues { it.value.sum() }
    }

    @Test
    fun aMoveAlongACreditsRowOrSimilarRecomposesOnlyTheTwoCards() {
        show()
        toCast()
        press(Key.DirectionDown, 2)
        assertFocused("card movie:400")
        val similar = moved(Key.DirectionRight)
        assertEquals("Right along Similar recomposed $similar", setOf("movie:400", "movie:401"), similar.keys)
        press(Key.DirectionUp, 2)
        assertFocused("person cast:0:77")
        press(Key.DirectionCenter)
        await("person name")
        press(Key.DirectionDown)
        assertFocused("card movie:201")
        val credits = moved(Key.DirectionRight)
        assertEquals("Right along Appearances recomposed $credits", setOf("movie:201", "movie:202"), credits.keys)
    }
}
