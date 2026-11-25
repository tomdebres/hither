package com.tomdebres.hither.ui

import com.tomdebres.hither.core.rules.ImageSource
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
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
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.rules.MEDIA_SERVER_PLEX
import com.tomdebres.hither.core.rules.moviePage
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.TvDetails
import com.tomdebres.hither.core.rules.tvPage
import com.tomdebres.hither.core.rules.MediaPage
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.media.LocalPlexApp
import com.tomdebres.hither.ui.media.MediaModel
import com.tomdebres.hither.ui.media.MediaScreen
import com.tomdebres.hither.ui.media.PlexApp
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Play on Plex" on a device (DECISIONS 68). `tv31` has no Plex (no Google account to install it
 * with), so the app's way of finding Plex is given a Plex here, and Espresso-Intents takes the
 * intent at the activity's `startActivity`, checks it and launches nothing: the action, the link
 * Plex for Android TV opens, the Plex package, a new task. Without Plex, as on `tv31` itself, there
 * is no button; and a Plex that went away says so instead of doing nothing.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlexPlayTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val machine = "f00dfacecafe0000000000000000000000000000"
    private val plexServer = MediaPages.server.copy(mediaServerType = MEDIA_SERVER_PLEX)
    private fun seerrLink(key: String) = "plex://preplay/?metadataKey=%2Flibrary%2Fmetadata%2F$key&server=$machine"

    /** A film in Plex, regular and 4K: both play buttons, nothing left to request. */
    private val filmInPlex = moviePage(
        MovieDetails(id = 7, title = "Film 7", overview = "About it.", mediaInfo = MediaInfo(tmdbId = 7, status = 5, status4k = 5, iOSPlexUrl = seerrLink("8531"), iOSPlexUrl4k = seerrLink("8532"))),
        ImageSource.unknown(MediaPages.BASE),
        plexServer,
        MediaPages.account,
    )

    /** A series partly in Plex: "Play on Plex" first, then "Request More". */
    private val seriesInPlex = tvPage(
        TvDetails(id = 9, name = "Series 9", overview = "About it.", seasons = (1..3).map { com.tomdebres.hither.core.model.SeasonSummary(seasonNumber = it, episodeCount = 8) },
            mediaInfo = MediaInfo(tmdbId = 9, status = 4, seasons = listOf(com.tomdebres.hither.core.model.MediaSeason(seasonNumber = 1, status = 5)), iOSPlexUrl = seerrLink("8493"))),
        ImageSource.unknown(MediaPages.BASE),
        plexServer,
        MediaPages.account,
    )

    @Before
    fun intents() = Intents.init()

    @After
    fun release() = Intents.release()

    private fun show(page: MediaPage, card: com.tomdebres.hither.core.rules.TitleCard, plex: PlexApp?) {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { MediaModel(source = { _, _ -> page }, card = card, scope = scope) }
            val screen = @androidx.compose.runtime.Composable { DpadHost { MediaScreen(model) } }
            if (plex != null) CompositionLocalProvider(LocalPlexApp provides { plex }) { screen() } else screen()
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("media title").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
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

    private val plexInstalled = PlexApp { "com.plexapp.android" }

    @Test
    fun playOnPlexHasFocusAndOpensTheTitleInPlex() {
        intending(anyIntent()).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        show(filmInPlex, MediaPages.card(7), plexInstalled)
        assertFocused("action Play on Plex")
        press(Key.DirectionCenter)
        intended(
            allOf(
                hasAction(Intent.ACTION_VIEW),
                hasData(Uri.parse("plex://libraries/$machine/unmatched/%2Flibrary%2Fmetadata%2F8531")),
                hasPackage("com.plexapp.android"),
                hasFlag(Intent.FLAG_ACTIVITY_NEW_TASK),
            ),
        )
        assertEquals(1, Intents.getIntents().size)
        // the 4K copy's own button, its own link; the row is fenced at both ends
        press(Key.DirectionRight)
        assertFocused("action Play 4K on Plex")
        press(Key.DirectionRight)
        assertFocused("action Play 4K on Plex")
        press(Key.DirectionCenter)
        intended(hasData(Uri.parse("plex://libraries/$machine/unmatched/%2Flibrary%2Fmetadata%2F8532")))
        press(Key.DirectionLeft)
        press(Key.DirectionLeft)
        assertFocused("action Play on Plex")
    }

    @Test
    fun aSeriesPlaysItsOwnKeyAndThePlayButtonComesBeforeTheRequestButton() {
        intending(anyIntent()).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        show(seriesInPlex, MediaPages.seriesCard(9), plexInstalled)
        assertFocused("action Play on Plex")
        press(Key.DirectionRight)
        assertFocused("action Request More")
        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        intended(hasData(Uri.parse("plex://libraries/$machine/unmatched/tv/%2Flibrary%2Fmetadata%2F8493")))
    }

    @Test
    fun withoutPlexOnTheTvThereIsNoButton() {
        // The device's own Plex finder: tv31 has no Plex, so nothing opens a plex:// link.
        show(filmInPlex, MediaPages.card(7), plex = null)
        rule.onNodeWithTag("action Play on Plex").assertDoesNotExist()
        rule.onNodeWithTag("action Play 4K on Plex").assertDoesNotExist()
        // nothing to ask for and nothing to play: focus on the overview, never on nothing
        assertFocused("media overview")
        assertEquals(0, Intents.getIntents().size)
    }

    @Test
    fun aPlexThatWentAwaySaysSoInsteadOfDoingNothing() {
        // The finder says Plex is there; the package is not, so the real startActivity fails.
        show(filmInPlex, MediaPages.card(7), PlexApp { "com.plexapp.not.installed" })
        assertFocused("action Play on Plex")
        press(Key.DirectionCenter)
        // a dialog with OK in tvOS's words, once the press has settled (A5: no toast), focus back on the button after
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("problem").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        assertFocused("problem ok")
        rule.onNodeWithTag("dialog title", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString("Can't Open Plex"))))
        rule.onNodeWithTag("dialog message", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString("Plex could not be opened on this TV."))))
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("problem").fetchSemanticsNodes().isEmpty() }
        assertFocused("action Play on Plex")
    }
}
