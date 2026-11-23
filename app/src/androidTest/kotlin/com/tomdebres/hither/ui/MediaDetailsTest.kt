package com.tomdebres.hither.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.espresso.intent.matcher.IntentMatchers.hasFlag
import androidx.test.espresso.intent.matcher.IntentMatchers.hasPackage
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.model.CastMember
import com.tomdebres.hither.core.model.Credits
import com.tomdebres.hither.core.model.CrewMember
import com.tomdebres.hither.core.model.ImdbRating
import com.tomdebres.hither.core.model.Keyword
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.Ratings
import com.tomdebres.hither.core.model.RtRating
import com.tomdebres.hither.core.model.Video
import com.tomdebres.hither.core.model.WatchProvider
import com.tomdebres.hither.core.model.WatchProviders
import com.tomdebres.hither.core.rules.MediaPage
import com.tomdebres.hither.core.rules.moviePage
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.LocalRecompositionProbe
import com.tomdebres.hither.ui.components.ShowsFocus
import com.tomdebres.hither.ui.media.LocalTrailerApp
import com.tomdebres.hither.ui.media.MediaModel
import com.tomdebres.hither.ui.media.MediaScreen
import com.tomdebres.hither.ui.media.RatingsSource
import com.tomdebres.hither.ui.media.TrailerApp
import com.tomdebres.hither.ui.media.YOUTUBE_TV
import kotlinx.coroutines.CompletableDeferred
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The media page's M6 parts on a device (DECISIONS 98), on A4's product page: Watch Trailer handed to the YouTube app by
 * intent, last among the actions and hidden where nothing plays it; the facts in Seerr's words in Information, the
 * ratings line once the ratings come, "Currently Streaming On"; and Down through the overview, Cast & Crew (the crew
 * with their jobs) and the keywords, each keyword a chip along a fenced shelf. `tv31` has no
 * YouTube, so the app's way of finding one is given one here, and Espresso-Intents takes the intent.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MediaDetailsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val showsFocus = SemanticsMatcher.expectValue(ShowsFocus, true)
    private val trailerKey = "abcdefghijk"

    private fun film(media: MediaInfo? = null, trailer: Boolean = true): MediaPage = moviePage(
        MovieDetails(
            id = 7,
            title = "Film 7",
            overview = "A film to read about.",
            status = "Released",
            releaseDate = "2026-09-16",
            originalTitle = "Le Film",
            originalLanguage = "fr",
            revenue = 1_000_000,
            voteAverage = 7.5,
            voteCount = 100,
            credits = Credits(
                cast = (1..10).map { CastMember(id = it, name = "Person $it", character = "Part $it") },
                crew = listOf(CrewMember(201, "Dee Rector", "Director"), CrewMember(202, "Wren Writer", "Writer")),
            ),
            relatedVideos = if (trailer) listOf(Video(site = "YouTube", key = trailerKey, name = "Trailer", size = 1080, type = "Trailer")) else emptyList(),
            keywords = listOf(Keyword(1, "heist"), Keyword(2, "robbery"), Keyword(3, "bank")),
            watchProviders = listOf(WatchProviders("US", flatrate = listOf(WatchProvider(8, "Netflix", "/n.png")))),
            mediaInfo = media,
        ),
        MediaPages.BASE,
        MediaPages.server,
        MediaPages.account,
    )

    private val counts = HashMap<String, Int>()

    @Before
    fun intents() = Intents.init()

    @After
    fun release() = Intents.release()

    private fun show(page: MediaPage, app: TrailerApp?, ratings: RatingsSource = RatingsSource { _, _ -> null }, first: String = "media title") {
        rule.setContent {
            CompositionLocalProvider(
                LocalRecompositionProbe provides { name -> counts[name] = (counts[name] ?: 0) + 1 },
                LocalTrailerApp provides { _ -> app ?: TrailerApp { null } },
            ) {
                val scope = rememberCoroutineScope()
                val model = remember { MediaModel(source = { _, _ -> page }, card = MediaPages.card(7), scope = scope, ratingsSource = ratings) }
                DpadHost { MediaScreen(model) }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(first).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "?" } ?: "?"
    }.getOrElse { "nothing" }

    private fun assertFocused(tag: String) {
        runCatching { rule.waitUntil(3_000) { focusedTag() == tag } }
        val now = focusedTag()
        if (now != tag) throw AssertionError("expected focus on $tag, it is on $now")
    }

    private val youTube = TrailerApp { YOUTUBE_TV }

    @Test
    fun watchTrailerIsTheLastActionAndHandsTheWatchLinkToTheYouTubeApp() {
        intending(anyIntent()).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        show(film(), youTube, first = "action watch trailer")
        assertFocused("action Request")
        press(Key.DirectionRight, 2)
        assertFocused("action watch trailer")
        rule.onNodeWithTag("action watch trailer").assert(showsFocus)
        // fenced at the row's end
        press(Key.DirectionRight)
        assertFocused("action watch trailer")
        press(Key.DirectionCenter)
        intended(
            allOf(
                hasAction(Intent.ACTION_VIEW),
                hasData(Uri.parse("https://www.youtube.com/watch?v=$trailerKey")),
                hasPackage(YOUTUBE_TV),
                hasFlag(Intent.FLAG_ACTIVITY_NEW_TASK),
            ),
        )
        assertEquals("one press, one hand-off", 1, Intents.getIntents().size)
    }

    @Test
    fun whereNothingPlaysItThereIsNoButton() {
        show(film(), app = null, first = "action Request")
        rule.onNodeWithTag("action watch trailer").assertDoesNotExist()
        assertEquals(0, Intents.getIntents().size)
    }

    @Test
    fun aTitleWithoutATrailerHasNoButton() {
        show(film(trailer = false), youTube, first = "action Request")
        rule.onNodeWithTag("action watch trailer").assertDoesNotExist()
    }

    @Test
    fun aTitleWithOnlyItsTrailerHasFocusThere() {
        show(film(media = MediaInfo(tmdbId = 7, status = 5, status4k = 5)), youTube, first = "action watch trailer")
        assertFocused("action watch trailer")
        press(Key.DirectionLeft)
        assertFocused("action watch trailer")
    }

    @Test
    fun downReadsTheOverviewCastAndCrewThenTheKeywords() {
        show(film(), app = null, first = "action Request")
        assertFocused("action Request")
        press(Key.DirectionDown)
        assertFocused("media overview")
        // Cast & Crew (A4): the cast's portraits, then the crew's with their jobs, one shelf
        press(Key.DirectionDown)
        assertFocused("person cast:0:1")
        press(Key.DirectionRight, 10)
        assertFocused("person crew:0:201")
        rule.onNode(hasText("Director") and hasAnyAncestor(hasTestTag("lockup person crew:0:201")), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("Dee Rector") and hasAnyAncestor(hasTestTag("lockup person crew:0:201")), useUnmergedTree = true).assertExists()
        press(Key.DirectionRight)
        assertFocused("person crew:1:202")
        press(Key.DirectionRight)
        assertFocused("person crew:1:202")
        // the keywords, one shelf of chips, fenced at both ends
        press(Key.DirectionDown)
        assertFocused("keyword heist")
        rule.onNodeWithTag("keyword heist").assert(showsFocus)
        press(Key.DirectionLeft)
        assertFocused("keyword heist")
        press(Key.DirectionRight, 2)
        assertFocused("keyword bank")
        press(Key.DirectionRight)
        assertFocused("keyword bank")
        // coming back into a shelf lands where it was left
        press(Key.DirectionUp)
        assertFocused("person crew:1:202")
        press(Key.DirectionDown)
        assertFocused("keyword bank")
        press(Key.DirectionUp, 2)
        assertFocused("media overview")
        press(Key.DirectionUp)
        assertFocused("action Request")
    }

    @Test
    fun theFactsAreSeerrsWordsInInformation() {
        show(film(), app = null, first = "media information")
        rule.onNodeWithText("Original Title").assertExists()
        rule.onNodeWithText("Le Film").assertExists()
        rule.onNodeWithText("Revenue").assertExists()
        rule.onNodeWithText("$1,000,000").assertExists() // whole dollars (Formats, A1)
        rule.onNodeWithText("Original Language").assertExists()
        rule.onNodeWithText("French").assertExists()
        rule.onNodeWithText("Currently Streaming On").assertExists()
        rule.onNodeWithText("Netflix").assertExists()
    }

    @Test
    fun theRatingsLineTakesRottenTomatoesAndImdbWhenTheyCome() {
        val answer = CompletableDeferred<Ratings?>()
        show(film(), app = null, ratings = { _, _ -> answer.await() }, first = "media information")
        // TMDB's score is the page's own: there at once
        rule.onNodeWithTag("rating TMDB").assertTextEquals("75%")
        rule.onNodeWithTag("rating RT_FRESH").assertDoesNotExist()
        answer.complete(Ratings(rt = RtRating(criticsRating = "Certified Fresh", criticsScore = 87, audienceRating = "Spilled", audienceScore = 41), imdb = ImdbRating(criticsScore = 7.2)))
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("rating RT_FRESH").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("rating RT_FRESH").assertTextEquals("87%")
        rule.onNodeWithTag("rating RT_SPILLED").assertTextEquals("41%")
        rule.onNodeWithTag("rating IMDB").assertTextEquals("7.2")
        rule.onNodeWithTag("rating TMDB").assertTextEquals("75%")
    }

    @Test
    fun aMoveAlongTheKeywordsRecomposesOnlyTheTwoTags() {
        show(film(), app = null, first = "action Request")
        assertFocused("action Request")
        press(Key.DirectionDown, 2)
        assertFocused("person cast:0:1")
        settle()
        take()
        press(Key.DirectionDown)
        settle()
        assertFocused("keyword heist")
        assertOnly("Down to the keywords", setOf("cast:0:1", "1"), take())
        press(Key.DirectionRight)
        settle()
        assertFocused("keyword robbery")
        assertOnly("Right along the keywords", setOf("1", "2"), take())
        press(Key.DirectionRight)
        settle()
        assertOnly("Right along the keywords", setOf("2", "3"), take())
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
    }

    private fun take(): Map<String, Int> = rule.runOnIdle { HashMap(counts).also { counts.clear() } }

    /** By control: a keyword chip named by its id, a portrait by its key (its item, lockup and caption as one). */
    private fun assertOnly(move: String, allowed: Set<String>, counts: Map<String, Int>) {
        val controls = counts.entries
            .groupBy({ it.key.removePrefix("keyword tag ").removePrefix("cast item ").removePrefix("cast card ").removePrefix("cast caption ") }, { it.value })
            .mapValues { it.value.sum() }
        Log.i("MediaDetails", "$move: $controls")
        val others = controls.keys - allowed
        if (others.isNotEmpty()) throw AssertionError("$move recomposed $others, not only $allowed: $controls")
        val tooOften = controls.filterValues { it > 4 }
        if (tooOften.isNotEmpty()) throw AssertionError("$move recomposed a control more than 4 times: $tooOften")
    }
}
