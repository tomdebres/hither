package com.tomdebres.hither.ui

import com.tomdebres.hither.core.rules.ImageSource
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.LocalRecompositionProbe
import com.tomdebres.hither.ui.components.ShowsFocus
import com.tomdebres.hither.ui.discover.DiscoverPage
import com.tomdebres.hither.ui.search.SearchModel
import com.tomdebres.hither.ui.search.SearchScreen
import com.tomdebres.hither.ui.search.SearchSource
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Search on a device (2.4, DECISIONS 64): the keyboard opens on the field on arrival; the
 * results are a six-across grid, Down from the field reaches it and Up comes back; the grid is
 * fenced at its row ends and keeps the column on Up and Down; a card opens its page and coming
 * back lands on it, words and results as they were; Back from the grid goes to the field and
 * from the field leaves; the field is A7's capsule (A8); "No Results for “…”" a `StateView` in
 * tvOS's words, and a failure whose Try Again holds focus until its answer and then hands it on.
 * A move along the grid recomposes the two cards it touches.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SearchFocusTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val showsFocus = SemanticsMatcher.expectValue(ShowsFocus, true)
    private val counts = HashMap<String, Int>()
    private var left = 0

    /** [count] films for any words, unless the test says otherwise. */
    private fun results(count: Int) = DiscoverPage(
        ImageSource.unknown("http://seerr.test"),
        Page(results = (1..count).map { MediaResult(id = it, mediaType = "movie", title = "Film $it") }),
    )

    /** The search page, and a stand-in title page that Select on a card opens and Back closes. */
    private fun show(source: SearchSource = SearchSource { _, _ -> results(14) }) {
        rule.setContent {
            CompositionLocalProvider(LocalRecompositionProbe provides { name -> counts[name] = (counts[name] ?: 0) + 1 }) {
                val scope = rememberCoroutineScope()
                val model = remember { SearchModel(source, scope) }
                var open by remember { mutableStateOf<TitleCard?>(null) }
                DpadHost {
                    val shown = open
                    if (shown == null) {
                        SearchScreen(model, onOpen = { open = it as? TitleCard }, onBack = { left++ })
                    } else {
                        BackHandler { open = null }
                        val page = remember { FocusRequester() }
                        LaunchedEffect(Unit) { page.requestFocus() }
                        Box(Modifier.size(100.dp).focusRequester(page).focusable().testTag("page ${shown.key}"))
                    }
                }
            }
        }
        rule.waitForIdle()
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
    }.getOrElse { "nothing (${it.message?.lineSequence()?.firstOrNull()})" }

    private fun assertFocused(tag: String) {
        runCatching { rule.waitUntil(3_000) { focusedTag() == tag } }
        val now = focusedTag()
        if (now != tag) throw AssertionError("expected focus on $tag, it is on $now")
    }

    /** Types [words] with the keyboard open and presses its search key. */
    private fun search(words: String) {
        assertFocused("search field input")
        rule.onNodeWithTag("search field input").performTextInput(words)
        press(Key.Enter)
    }

    private fun card(n: Int) = "card movie:$n"

    private fun words(tag: String): String = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .flatMap { it.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty() }
        .joinToString("") { it.text }

    @Test
    fun theGridIsAsWideAsTheFieldAboveIt() {
        // the polish audit's item 23: six cards ended 128 px short of the field's right edge
        show()
        search("film")
        await(card(6))
        // focus into the grid, so the field is measured at rest, not grown by its focus
        press(Key.DirectionDown)
        assertFocused(card(1))
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        val field = rule.onNodeWithTag("search field").fetchSemanticsNode().boundsInRoot
        // the first column measured on the second row: the first lockup has focus, drawn 1.1, which its bounds show (A3)
        await(card(7))
        val first = rule.onNodeWithTag(card(7)).fetchSemanticsNode().boundsInRoot
        val sixth = rule.onNodeWithTag(card(6)).fetchSemanticsNode().boundsInRoot
        assertEquals("the first column's left edge against the field's", field.left, first.left, 2f)
        assertEquals("the sixth card's right edge against the field's", field.right, sixth.right, 2f)
    }

    @Test
    fun theKeyboardIsOpenOnArrivalAndDownFromTheFieldReachesTheFirstResult() {
        show()
        assertFocused("search field input")
        rule.onNodeWithTag("search notice").assertExists()
        search("star wars")
        await(card(1))
        // the keyboard closed on its search key: the field has focus, not its text box
        assertFocused("search field")
        press(Key.DirectionDown)
        assertFocused(card(1))
        rule.onNodeWithTag(card(1)).assert(showsFocus)
        press(Key.DirectionUp)
        assertFocused("search field")
    }

    @Test
    fun theGridIsFencedAtItsRowEndsAndKeepsTheColumnOnUpAndDown() {
        show()
        search("film")
        await(card(1))
        press(Key.DirectionDown)
        press(Key.DirectionLeft)
        assertFocused(card(1))
        press(Key.DirectionRight, 5)
        assertFocused(card(6))
        // the row's end: Right does not wrap to the next row
        press(Key.DirectionRight)
        assertFocused(card(6))
        press(Key.DirectionDown)
        assertFocused(card(12))
        press(Key.DirectionUp)
        assertFocused(card(6))
        // up to the field and back down: the card left
        press(Key.DirectionUp)
        assertFocused("search field")
        press(Key.DirectionDown)
        assertFocused(card(6))
    }

    @Test
    fun aCardOpensItsPageAndBackLandsOnItWithTheWordsAndResultsKept() {
        val asked = AtomicInteger()
        show { _, _ ->
            asked.incrementAndGet()
            results(14)
        }
        search("film")
        await(card(1))
        press(Key.DirectionDown)
        press(Key.DirectionRight, 2)
        press(Key.DirectionCenter)
        await("page movie:3")
        back()
        await(card(3))
        assertFocused(card(3))
        rule.onNodeWithTag("search field input").assertTextEquals("film")
        rule.onNodeWithTag(card(1)).assertExists()
        // the results were kept, not asked for again
        assertEquals(1, asked.get())
    }

    @Test
    fun backFromTheGridGoesToTheFieldAndBackFromTheFieldLeaves() {
        show()
        search("film")
        await(card(1))
        press(Key.DirectionDown, 2)
        assertFocused(card(7))
        back()
        assertFocused("search field")
        assertEquals(0, left)
        back()
        assertEquals(1, left)
    }

    @Test
    fun nothingForTheWordsIsAStateViewInTheAppleTvAppsWordsAndDownStaysOnTheField() {
        show { _, _ -> results(0) }
        search("zzqx ")
        await("no results")
        // tvOS's ContentUnavailableView.search: the words searched for, and the line under them
        assertEquals("No Results for \u201Czzqx\u201D", words("no results"))
        assertEquals("Check the spelling or try a new search.", words("no results hint"))
        // a StateView: the words over the line, centred on the page under the field
        val field = rule.onNodeWithTag("search field").fetchSemanticsNode().boundsInRoot
        val title = rule.onNodeWithTag("no results").fetchSemanticsNode().boundsInRoot
        val hint = rule.onNodeWithTag("no results hint").fetchSemanticsNode().boundsInRoot
        assertTrue("under the field", title.top > field.bottom)
        assertTrue("the line under the words", hint.top >= title.bottom)
        assertEquals("centred", field.center.x, title.center.x, 2f)
        assertFocused("search field")
        press(Key.DirectionDown)
        assertFocused("search field")
    }

    @Test
    fun theFieldIsTheEntryFieldsCapsuleAcrossTheTopAndFocusDoesNotGrowIt() {
        show()
        search("film")
        await(card(1))
        assertFocused("search field")
        val dp = rule.density.density
        val focused = rule.onNodeWithTag("search field").fetchSemanticsNode().boundsInRoot
        press(Key.DirectionDown)
        assertFocused(card(1))
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        val rest = rule.onNodeWithTag("search field").fetchSemanticsNode().boundsInRoot
        val width = rule.onRoot().fetchSemanticsNode().boundsInRoot.width
        // A7's EntryField: 36 dp high, at the side margins, the white its only focus (Seerr's pill was 32 dp and grew 3%)
        assertEquals("the capsule's height", 36 * dp, rest.height, 1f)
        assertEquals("from the left margin", 48 * dp, rest.left, 1f)
        assertEquals("to the right margin", width - 48 * dp, rest.right, 1f)
        assertEquals("focus does not grow it", rest, focused)
    }

    @Test
    fun aFailureOffersTryAgainWhichHoldsFocusUntilItsAnswerAndHandsItToTheFirstResult() {
        val calls = AtomicInteger()
        val retried = CompletableDeferred<DiscoverPage>()
        show { _, _ ->
            if (calls.incrementAndGet() == 1) throw SeerrException.Unreachable(RuntimeException("down"))
            retried.await()
        }
        search("coco")
        await("try again")
        assertFocused("search field")
        press(Key.DirectionDown)
        assertFocused("try again")
        press(Key.DirectionCenter)
        press(Key.DirectionCenter)
        // out: "Trying…", and the button keeps focus
        assertFocused("try again")
        assertEquals(2, calls.get())
        retried.complete(results(3))
        await(card(1))
        assertFocused(card(1))
        rule.onNodeWithTag(card(1)).assert(showsFocus)
    }

    @Test
    fun aLaterPageThatFailsEndsTheGridWithTryAgainWhichAsksForItOnce() {
        val second = AtomicInteger()
        val retried = CompletableDeferred<DiscoverPage>()
        show { _, page ->
            when {
                page == 1 -> DiscoverPage(
                    ImageSource.unknown("http://seerr.test"),
                    Page(page = 1, totalPages = 2, results = (1..14).map { MediaResult(id = it, mediaType = "movie", title = "Film $it") }),
                )
                second.incrementAndGet() == 1 -> throw SeerrException.Unreachable(RuntimeException("down"))
                else -> retried.await()
            }
        }
        search("film")
        await(card(1))
        // into the grid and along: two rows from the end, page 2 is asked for, and fails
        press(Key.DirectionDown)
        press(Key.DirectionRight, 2)
        rule.waitUntil(5_000) { second.get() == 1 }
        // the third row: 13, 14, and Try Again under the third column (below the screen until the grid scrolls, A3's
        // taller lockups)
        press(Key.DirectionDown, 2)
        await("retry more")
        assertEquals(1, second.get())
        assertFocused("retry more")
        rule.onNodeWithTag("retry more").assert(showsFocus)
        press(Key.DirectionRight)
        assertFocused("retry more")
        press(Key.DirectionCenter)
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { second.get() == 2 }
        // out: the card stays, reading "Trying…", and keeps focus, so a repeated Select does nothing
        assertFocused("retry more")
        retried.complete(
            DiscoverPage(ImageSource.unknown("http://seerr.test"), Page(page = 2, totalPages = 2, results = (15..16).map { MediaResult(id = it, mediaType = "movie", title = "Film $it") })),
        )
        await(card(15))
        // the page has come: focus is on its first card, where Try Again was
        assertFocused(card(15))
        assertEquals(2, second.get())
        assertEquals(0, rule.onAllNodesWithTag("retry more").fetchSemanticsNodes().size)
    }

    @Test
    fun aMoveAlongTheGridRecomposesOnlyTheCardLeftAndTheCardReached() {
        show()
        search("film")
        await(card(1))
        press(Key.DirectionDown)
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        val moves = listOf(Key.DirectionRight to 2, Key.DirectionRight to 3, Key.DirectionDown to 9, Key.DirectionLeft to 8, Key.DirectionUp to 2)
        var from = 1
        for ((key, to) in moves) {
            rule.runOnIdle { counts.clear() }
            press(key)
            rule.mainClock.advanceTimeBy(2_000)
            rule.waitForIdle()
            assertFocused(card(to))
            val cards = rule.runOnIdle { HashMap(counts) }.entries
                .groupBy({ it.key.substringAfterLast(' ') }, { it.value }).mapValues { it.value.sum() }
            Log.i("SearchRecomposition", "$key from $from: $cards")
            val others = cards.keys - setOf("movie:$from", "movie:$to")
            if (others.isNotEmpty()) throw AssertionError("$key from movie:$from recomposed $others too: $cards")
            if (cards.values.any { it > 4 }) throw AssertionError("$key from movie:$from recomposed a card more than 4 times: $cards")
            from = to
        }
    }
}
