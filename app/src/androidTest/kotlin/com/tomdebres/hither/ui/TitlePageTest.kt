package com.tomdebres.hither.ui

import com.tomdebres.hither.core.rules.ImageSource
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.model.CastMember
import com.tomdebres.hither.core.model.CollectionBrief
import com.tomdebres.hither.core.model.CollectionDetails
import com.tomdebres.hither.core.model.CountryReleases
import com.tomdebres.hither.core.model.Credits
import com.tomdebres.hither.core.model.Genre
import com.tomdebres.hither.core.model.Keyword
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.ReleaseDate
import com.tomdebres.hither.core.model.Releases
import com.tomdebres.hither.core.model.RequestSeason
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.model.WatchProvider
import com.tomdebres.hither.core.model.WatchProviders
import com.tomdebres.hither.core.rules.MediaPage
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.core.rules.afterRequest
import com.tomdebres.hither.core.rules.moviePage
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.ShowsFocus
import com.tomdebres.hither.ui.home.HomeScreen
import com.tomdebres.hither.ui.media.MediaModel
import com.tomdebres.hither.ui.media.MediaScreen
import com.tomdebres.hither.ui.media.RequestSender
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A title's page as the Apple TV app's product page (A4; tvOS's title page, H2's plan), on a device:
 *
 * - the first screen: the title, then "2026 · 1 hr 34 min · genres" with the certification boxed, no poster where there
 *   is a backdrop (tvOS has none), the next shelf's header showing under it;
 * - a series offers only the seasons still to ask for, as a shelf of cards, newest first, the rest folded into
 *   Information; a season's card opens the request with that season ticked and the primary focused (the review's F9: a
 *   season did nothing on Select), and once that season is asked for, focus goes on along the shelf;
 * - Information takes no focus: a walk down the whole page never lands in it, and the last shelf brings it on screen;
 * - Keywords is one shelf: every chip on one line, the shelf moving sideways under the focus;
 * - "More" opens the whole overview in the reading screen; Down there turns a page and keeps focus (a remote's keys,
 *   through the keyboard's stage, as tv31 sends them), Up turns back; Back returns to the overview;
 * - the collection opens its films and Back returns to it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TitlePageTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val showsFocus = SemanticsMatcher.expectValue(ShowsFocus, true)

    /** The page served now: a request made here changes it, as Seerr's would. */
    private var served: MediaPage? = null

    /** The seasons each request asked for. */
    private val asked = CopyOnWriteArrayList<List<Int>>()

    private fun show(page: MediaPage, card: TitleCard = if (page.type.api == "tv") MediaPages.seriesCard(page.tmdbId) else MediaPages.card(page.tmdbId)) {
        served = page
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember {
                MediaModel(
                    source = { _, _ -> served!! },
                    card = card,
                    scope = scope,
                    requests = RequestSender { body ->
                        asked += body.seasons.orEmpty()
                        MediaRequest(
                            id = 1001,
                            status = RequestStatus.APPROVED,
                            is4k = body.is4k,
                            seasons = body.seasons.orEmpty().map { RequestSeason(seasonNumber = it, status = RequestStatus.APPROVED) },
                        ).also { created ->
                            val now = served!!
                            served = now.withMedia(afterRequest(now.media, created, now.type, now.tmdbId))
                        }
                    },
                )
            }
            DpadHost { MediaScreen(model) }
        }
        await("media title")
    }

    private fun await(tag: String, unmerged: Boolean = false) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    /** A remote's press, as tv31 sends it: through the keyboard's stage, where `DpadHost` takes the arrows (A2). */
    private fun remote(code: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, code))
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, code))
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
        runCatching { rule.waitUntil(3_000) { focusedTag() == tag } }
        val now = focusedTag()
        if (now != tag) throw AssertionError("expected focus on $tag, it is on $now")
    }

    private val tag: (SemanticsNode) -> String? = { it.config.getOrElseNullable(SemanticsProperties.TestTag) { null } }

    /**
     * Focus on a season's card: asked for directly, and where its shelf hands focus to the card it was left on (a shelf
     * comes back to its entry, A3), on along the shelf to it.
     */
    private fun focusOnSeason(n: Int) {
        rule.onNodeWithTag("series season $n").requestFocus()
        rule.waitForIdle()
        repeat(6) { if (focusedTag() != "series season $n") press(Key.DirectionRight) }
        assertFocused("series season $n")
    }

    private fun tagged(prefix: String, unmerged: Boolean = false): List<SemanticsNode> =
        rule.onAllNodes(SemanticsMatcher("tagged $prefix") { tag(it)?.startsWith(prefix) == true }, useUnmergedTree = unmerged).fetchSemanticsNodes()

    private fun words(node: SemanticsNode): String = node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString { it.text }.orEmpty()

    private val density get() = rule.density.density

    // ---- the first screen ---------------------------------------------------------------------------------------------

    private fun film(backdrop: Boolean = true) = moviePage(
        MovieDetails(
            id = 7,
            title = "Film 7",
            tagline = "A tagline.",
            overview = "A film to read about.",
            posterPath = "/poster.jpg",
            backdropPath = if (backdrop) "/backdrop.jpg" else null,
            releaseDate = "2026-09-16",
            runtime = 94,
            status = "Released",
            genres = listOf(Genre(1, "Drama"), Genre(2, "Thriller")),
            releases = Releases(listOf(CountryReleases("US", listOf(ReleaseDate(certification = "PG-13", type = 3, releaseDate = "2026-09-16"))))),
            credits = Credits(cast = (1..3).map { CastMember(id = it, name = "Person $it", character = "Part $it") }),
        ),
        ImageSource.unknown(MediaPages.BASE),
        MediaPages.server,
        MediaPages.account,
    )

    @Test
    fun theFirstScreenIsTheProductPage() {
        show(film())
        await("action Request")
        rule.onNodeWithTag("media title").assertTextEquals("Film 7")
        // the year first, the runtime as the Apple TV app writes it, the genres, between middle dots
        rule.onNodeWithTag("media attributes").assertTextEquals("2026 · 1 hr 34 min · Drama, Thriller")
        rule.onNode(hasText("PG-13") and hasAnyAncestor(hasTestTag("media certification")), useUnmergedTree = true).assertExists()
        // no poster beside the words: the backdrop is the picture (tvOS has none)
        rule.onNodeWithTag("title poster").assertDoesNotExist()
        // the overview under the actions; the next shelf's header shows under the first screen
        val actions = rule.onNodeWithTag("media actions").fetchSemanticsNode()
        val overview = rule.onNodeWithTag("media overview").fetchSemanticsNode()
        assertTrue("the overview is under the actions", overview.positionInRoot.y >= actions.positionInRoot.y + actions.size.height)
        val cast = tagged("row title").map { it.positionInRoot.y / density }.minOrNull()
        val screen = rule.onRoot().fetchSemanticsNode().size.height / density
        assertTrue("the first shelf's header shows under the first screen (at ${cast} dp of $screen)", cast != null && cast < screen)
    }

    @Test
    fun aTitleWithNoBackdropShowsItsPosterAtTheRight() {
        show(film(backdrop = false))
        await("action Request")
        await("title poster")
        val poster = rule.onNodeWithTag("title poster").fetchSemanticsNode()
        val words = rule.onNodeWithTag("media title").fetchSemanticsNode()
        assertTrue("the poster is right of the words", poster.positionInRoot.x > words.positionInRoot.x + 600 * density)
    }

    // ---- the seasons ----------------------------------------------------------------------------------------------------

    @Test
    fun aSeriesOffersOnlyTheSeasonsStillToAskForAndTheRestFoldIntoInformation() {
        // six seasons, specials off; season 1 is in the library: 6 down to 2 can still be asked for
        show(MediaPages.series())
        await("action Request More")
        val offered = tagged("series season ").map { tag(it) }
        assertEquals("the seasons offered, newest first", (6 downTo 2).map { "series season $it" }, offered)
        rule.onNode(hasText("Available") and hasAnyAncestor(hasTestTag("fact Season 1")), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("12 Episodes") and hasAnyAncestor(hasTestTag("series season 2")), useUnmergedTree = true).assertExists()
        // the actions, the overview, then the shelf's newest season; fenced at its ends
        assertFocused("action Request More")
        press(Key.DirectionDown)
        assertFocused("media overview")
        press(Key.DirectionDown)
        assertFocused("series season 6")
        rule.onNodeWithTag("series season 6").assert(showsFocus)
        press(Key.DirectionLeft)
        assertFocused("series season 6")
        press(Key.DirectionRight, 4)
        assertFocused("series season 2")
        press(Key.DirectionRight)
        assertFocused("series season 2")
    }

    @Test
    fun aSeasonCardOpensTheRequestWithThatSeasonTicked() {
        show(MediaPages.series())
        await("series season 4")
        focusOnSeason(4)
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("request modal").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        // the season is ticked and the primary has focus, so a second Select asks for it (tvOS's polish 6)
        assertFocused("primary")
        // in the side panel along the right edge (A5), the ticked row in view
        val root = rule.onRoot().getBoundsInRoot()
        val panel = rule.onNodeWithTag("request modal").getBoundsInRoot()
        assertEquals("the panel is 420 dp wide", 420f, (panel.right - panel.left).value, 1f)
        assertEquals("the panel runs to the right edge", root.right.value, panel.right.value, 1f)
        rule.onNodeWithTag("season 4").assertIsDisplayed()
        // the rows the panel shows: season 4 ticked, season 1 on as it is in the library, no other
        val rows = rule.onAllNodes(SemanticsMatcher("a season row") { tag(it)?.matches(Regex("season [0-9]+")) == true }).fetchSemanticsNodes()
        val on = rows.filter { it.config.getOrElseNullable(SemanticsProperties.StateDescription) { null } == "on" }.map { tag(it) }.toSet()
        assertTrue("season 4's row is shown (${rows.map { tag(it) }})", rows.any { tag(it) == "season 4" })
        assertEquals("the rows on", setOf("season 1", "season 4"), on)
        rule.onNodeWithTag("primary").assert(SemanticsMatcher("says Request 1 Season") { node ->
            node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { it.text == "Request 1 Season" } == true
        })
        // asked for: the card leaves the shelf, the season is in Information, and focus goes on to the next season along
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("request modal").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(listOf(4)), asked.toList())
        assertFocused("series season 3")
        rule.onNodeWithTag("series season 4").assertDoesNotExist()
        await("fact Season 4", unmerged = true)
    }

    @Test
    fun backFromASeasonsRequestReturnsToItsCard() {
        show(MediaPages.series())
        await("series season 5")
        focusOnSeason(5)
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("request modal").fetchSemanticsNodes().isNotEmpty() }
        back()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("request modal").fetchSemanticsNodes().isEmpty() }
        assertFocused("series season 5")
        assertEquals("nothing was asked for", 0, asked.size)
    }

    // ---- Information ----------------------------------------------------------------------------------------------------

    private fun filmWithEverything(keywords: List<Keyword> = listOf(Keyword(1, "heist"), Keyword(2, "robbery"), Keyword(3, "bank")), cast: Int = 3) = moviePage(
        MovieDetails(
            id = 7,
            title = "Film 7",
            overview = "A film to read about.",
            status = "Released",
            releaseDate = "2026-09-16",
            originalTitle = "Le Film",
            originalLanguage = "fr",
            revenue = 1_000_000,
            budget = 250_000,
            voteAverage = 7.5,
            voteCount = 100,
            credits = Credits(cast = (1..cast).map { CastMember(id = it, name = "Person $it", character = "Part $it") }),
            keywords = keywords,
            watchProviders = listOf(WatchProviders("US", flatrate = listOf(WatchProvider(8, "Netflix", "/n.png")))),
        ),
        ImageSource.unknown(MediaPages.BASE),
        MediaPages.server,
        MediaPages.account,
    )

    @Test
    fun informationTakesNoFocusAndTheLastShelfBringsItOnScreen() {
        show(filmWithEverything())
        await("action Request")
        assertFocused("action Request")
        // what Information holds: Seerr's facts, whole dollars, the language's name, where it streams, the ratings
        for (words in listOf("Le Film", "$1,000,000", "$250,000", "French", "Netflix", "75%")) {
            rule.onNode(hasText(words), useUnmergedTree = true).assertExists()
        }
        val inInformation = SemanticsMatcher("one of Information's facts") { node -> tag(node)?.startsWith("fact ") == true }
        // down the whole page: focus never lands on Information or anything in it
        repeat(6) { n ->
            press(Key.DirectionDown)
            val focused = rule.onAllNodes(isFocused() and (hasAnyDescendant(inInformation) or hasAnyAncestor(inInformation) or inInformation), useUnmergedTree = true).fetchSemanticsNodes()
            if (focused.isNotEmpty()) throw AssertionError("Down ${n + 1} put focus on Information: ${focused.map { tag(it) }} (focus on ${focusedTag()})")
        }
        assertTrue("focus ended on the keywords, the last shelf (it is on ${focusedTag()})", focusedTag().startsWith("keyword "))
        val focusables = rule.onAllNodes(hasAnyAncestor(hasTestTag("media information")) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("focusable things in Information", emptyList<String?>(), focusables.map { tag(it) })
        // the last shelf brought the page's foot up: Information is on the screen, whole
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val information = rule.onNodeWithTag("media information").fetchSemanticsNode()
        val screen = rule.onRoot().fetchSemanticsNode().size.height
        val foot = information.positionInRoot.y + information.size.height
        assertTrue("Information ends at ${foot / density} dp, below the screen's ${screen / density}", foot <= screen + 1)
        assertTrue("Information starts at ${information.positionInRoot.y / density} dp, above the screen", information.positionInRoot.y >= 0)
    }

    // ---- Keywords -------------------------------------------------------------------------------------------------------

    @Test
    fun keywordsAreOneShelf() {
        val keywords = (1..14).map { Keyword(100 + it, "keyword number $it here") }
        show(filmWithEverything(keywords, cast = 0))
        await("action Request")
        val chip = SemanticsMatcher("a keyword") { node -> tag(node)?.let { it.startsWith("keyword ") && it != "keyword shelf" } == true }
        val tops = rule.onAllNodes(chip).fetchSemanticsNodes().map { Math.round(it.positionInRoot.y / density) }.distinct()
        assertEquals("the keywords lie on ${tops.size} lines ($tops dp)", 1, tops.size)
        // the actions, the overview, the keywords; along them to the last, the shelf moving under the focus
        press(Key.DirectionDown, 2)
        assertFocused("keyword keyword number 1 here")
        val line = rule.onNodeWithTag("keyword keyword number 2 here").fetchSemanticsNode().positionInRoot.y
        press(Key.DirectionRight, 13)
        assertFocused("keyword keyword number 14 here")
        press(Key.DirectionRight)
        assertFocused("keyword keyword number 14 here")
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val beforeLast = rule.onNodeWithTag("keyword keyword number 13 here").fetchSemanticsNode().positionInRoot.y
        assertEquals("the thirteenth keyword is on the first's line", line / density, beforeLast / density, 1f)
        val first = rule.onAllNodesWithTag("keyword keyword number 1 here").fetchSemanticsNodes()
        assertTrue("the shelf moved sideways: the first keyword has gone off the left", first.isEmpty() || first.single().positionInRoot.x < 0)
    }

    // ---- the overview's More and the reading screen ----------------------------------------------------------------------

    /** About 100 lines in the reading screen: several views, so a turn is never cut short by the text's end. */
    private val longOverview = (1..150).joinToString(" ") { "Sentence $it of an overview long enough to need many lines at ten feet." }

    @Test
    fun moreOpensTheWholeOverviewAndDownTurnsAPage() {
        show(
            moviePage(
                MovieDetails(id = 7, title = "Film 7", tagline = "A tagline.", overview = longOverview, releaseDate = "2026-09-16"),
                ImageSource.unknown(MediaPages.BASE), MediaPages.server, MediaPages.account,
            ),
        )
        await("action Request")
        press(Key.DirectionDown)
        assertFocused("media overview")
        rule.onNodeWithTag("media overview").assert(showsFocus)
        // four lines, and "More" beside the last
        await("media overview more", unmerged = true)
        press(Key.DirectionCenter)
        await("overview screen")
        assertFocused("overview whole")
        rule.onNodeWithTag("reading title", useUnmergedTree = true).assertTextEquals("Film 7")
        val paragraph = hasAnyAncestor(hasTestTag("overview whole")) and hasText("Sentence 1 ", substring = true)
        val top = { rule.onNode(paragraph, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y }
        val viewport = rule.onNodeWithTag("overview whole").fetchSemanticsNode().size.height
        val start = top()
        // a remote's Down, through the keyboard's stage: a page turned, focus kept
        remote(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val turned = start - top()
        assertTrue("Down turned the text ${turned / density} dp, not most of its ${viewport / density} dp view", turned > viewport * 0.6f && turned < viewport)
        assertFocused("overview whole")
        // and the test's own keys, which pass the keyboard's stage by
        press(Key.DirectionDown)
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertTrue("a second Down turned on", start - top() > turned * 1.5f)
        remote(KeyEvent.KEYCODE_DPAD_UP)
        remote(KeyEvent.KEYCODE_DPAD_UP)
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals("Up turned back to the start", start / density, top() / density, 1f)
        // Left and Right go nowhere: the text keeps focus until Back
        remote(KeyEvent.KEYCODE_DPAD_LEFT)
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocused("overview whole")
        back()
        rule.waitUntil(3_000) { rule.onAllNodesWithTag("overview screen").fetchSemanticsNodes().isEmpty() }
        assertFocused("media overview")
    }

    @Test
    fun aShortOverviewHasNoMore() {
        show(film())
        await("action Request")
        rule.onNodeWithTag("media overview more", useUnmergedTree = true).assertDoesNotExist()
    }

    // ---- the collection --------------------------------------------------------------------------------------------------

    @Test
    fun theCollectionOpensItsFilmsAndBackReturnsToIt() {
        val session = Session(User(id = 8, displayName = "TV", permissions = 32), PublicSettings(applicationTitle = "Seerr", localLogin = true))
        val paths = CopyOnWriteArrayList<String>()
        val client = SeerrClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                paths += path
                val json = SeerrClient.json
                val ok = { body: String -> respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
                when {
                    path.endsWith("/ratingscombined") -> respond("{}", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
                    path == "/api/v1/collection/86" -> ok(
                        json.encodeToString(
                            CollectionDetails.serializer(),
                            CollectionDetails(id = 86, name = "Film Collection", parts = (701..703).map { MediaResult(id = it, mediaType = "movie", title = "Part $it") }),
                        ),
                    )
                    path.startsWith("/api/v1/movie/") && !path.endsWith("recommendations") && !path.endsWith("similar") -> {
                        val id = path.substringAfterLast('/').toInt()
                        ok(json.encodeToString(MovieDetails.serializer(), MovieDetails(id = id, title = "Film $id", overview = "About it.", releaseDate = "2026-01-01", collection = CollectionBrief(id = 86, name = "Film Collection"))))
                    }
                    path == "/api/v1/settings/public" -> ok(json.encodeToString(PublicSettings.serializer(), session.settings))
                    path == "/api/v1/auth/me" -> ok(json.encodeToString(User.serializer(), session.user))
                    else -> ok(json.encodeToString(Page.serializer(MediaResult.serializer()), Page(results = (1..6).map { MediaResult(id = it, mediaType = "movie", title = "Film $it") })))
                }
            },
            MediaPages.BASE,
            AcceptAllCookiesStorage(),
        )
        val access = object : SeerrAccess {
            override suspend fun <T> call(call: suspend (SeerrClient) -> T): T = call(client)
        }
        rule.setContent { DpadHost { HomeScreen(Screen.Home(session), access, onSignOut = {}) } }
        await("card movie:1")
        assertFocused("card movie:1")
        press(Key.DirectionCenter)
        await("media collection")
        rule.onNode(hasText("Film Collection") and hasAnyAncestor(hasTestTag("lockup media collection")), useUnmergedTree = true).assertExists()
        // the actions, the overview, the collection (no cast on this page)
        press(Key.DirectionDown, 2)
        assertFocused("media collection")
        rule.onNodeWithTag("media collection").assert(showsFocus)
        press(Key.DirectionCenter)
        await("card movie:701")
        rule.onNodeWithTag("grid heading").assertTextEquals("Film Collection")
        assertEquals("the collection was asked for once", 1, paths.count { it == "/api/v1/collection/86" })
        back()
        assertFocused("media collection")
    }
}
