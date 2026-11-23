package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.rules.MediaPage
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.ShowsFocus
import com.tomdebres.hither.ui.media.MediaModel
import com.tomdebres.hither.ui.media.MediaScreen
import com.tomdebres.hither.ui.media.MediaSource
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * The media page's focus rules (`docs/design/seerr-reference.md` 2.8, DECISIONS 62; A4's product page): the first
 * request button has focus on arrival and shows it; the buttons are fenced at both ends; Down reads the overview's stop,
 * then Cast & Crew, which moves as Discover's shelves do; Up comes back to the first button; a title with nothing to ask
 * for has its focus on the overview; while the title loads a placeholder holds focus and hands it on; after a failure
 * Try Again has it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MediaPageFocusTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val showsFocus = SemanticsMatcher.expectValue(ShowsFocus, true)

    private fun show(source: MediaSource) {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { MediaModel(source, MediaPages.card(), scope) }
            DpadHost { MediaScreen(model) }
        }
        rule.waitForIdle()
    }

    private fun showPage(page: MediaPage) = show { _, _ -> page }

    /** The page is read off the main thread, which waitForIdle does not wait for. */
    private fun await(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "a node without a tag" } ?: "a node without a tag"
    }.getOrElse { "nothing (${it.message?.lineSequence()?.firstOrNull()})" }

    private fun assertFocused(tag: String) {
        // Focus can take a frame or two to arrive (it waits for the target to be laid out).
        runCatching { rule.waitUntil(3_000) { focusedTag() == tag } }
        val now = focusedTag()
        if (now != tag) throw AssertionError("expected focus on $tag, it is on $now")
    }

    @Test
    fun theFirstRequestButtonHasFocusOnArrivalAndTheButtonsAreFenced() {
        showPage(MediaPages.film())
        await("action Request")
        assertFocused("action Request")
        rule.onNodeWithTag("action Request").assert(showsFocus)
        press(Key.DirectionLeft)
        assertFocused("action Request")
        press(Key.DirectionRight)
        assertFocused("action Request in 4K")
        press(Key.DirectionRight)
        assertFocused("action Request in 4K")
        // Up from the buttons: nothing above them takes focus, so it stays
        press(Key.DirectionUp)
        assertFocused("action Request in 4K")
    }

    @Test
    fun downReadsTheOverviewThenTheCastAndUpComesBackToTheFirstButton() {
        showPage(MediaPages.film())
        await("action Request")
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        assertFocused("media overview")
        rule.onNodeWithTag("media overview").assert(showsFocus)
        press(Key.DirectionLeft)
        assertFocused("media overview")
        // Information takes no focus (A4): Down from the overview is Cast & Crew's first portrait
        press(Key.DirectionDown)
        assertFocused("person cast:0:1")
        // fenced at the shelf's start; along it; coming back into the shelf lands where it was left
        press(Key.DirectionLeft)
        assertFocused("person cast:0:1")
        press(Key.DirectionRight, 3)
        assertFocused("person cast:3:4")
        press(Key.DirectionUp)
        assertFocused("media overview")
        press(Key.DirectionDown)
        assertFocused("person cast:3:4")
        // fenced at the shelf's end
        press(Key.DirectionRight, 9)
        assertFocused("person cast:9:10")
        // Up: the overview, then the first action (said outright: the overview is as wide as the actions)
        press(Key.DirectionUp, 2)
        assertFocused("action Request")
    }

    @Test
    fun aTitleWithNothingToAskForHasItsFocusOnTheOverview() {
        showPage(MediaPages.filmInLibrary())
        await("media overview")
        assertFocused("media overview")
        rule.onNodeWithTag("media overview").assert(showsFocus)
        assertEquals(0, rule.onAllNodesWithTag("media actions").fetchSemanticsNodes().size)
        rule.onNodeWithTag("status Available").assertExists()
        rule.onNodeWithTag("status 4K Available").assertExists()
    }

    @Test
    fun whileTheTitleLoadsAPlaceholderHoldsFocusAndHandsItToTheFirstButton() {
        val answer = CompletableDeferred<MediaPage>()
        show { _, _ -> answer.await() }
        await("loading")
        assertFocused("loading")
        // the card's title stands in meanwhile
        rule.onNodeWithTag("media title").assertExists()
        answer.complete(MediaPages.film())
        await("action Request")
        assertFocused("action Request")
        rule.onNodeWithTag("action Request").assert(showsFocus)
    }

    @Test
    fun afterAFailureTryAgainHasFocusAndLoadsThePage() {
        val calls = AtomicInteger()
        show { _, _ ->
            if (calls.incrementAndGet() == 1) throw SeerrException.Unreachable(RuntimeException("down"))
            MediaPages.film()
        }
        await("try again")
        assertFocused("try again")
        rule.onNodeWithTag("try again").assert(showsFocus)
        press(Key.DirectionCenter)
        await("action Request")
        assertFocused("action Request")
        assertEquals(2, calls.get())
    }

    @Test
    fun selectOnARequestButtonOpensTheModalForThatCopy() {
        showPage(MediaPages.film())
        await("action Request")
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        await("request modal")
        // a film's request asks about its copy (A5: tvOS's question, "Request Film 7 in 4K?")
        rule.onNodeWithTag("dialog title").assert(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("Request Film 7 in 4K?"))))
    }
}
