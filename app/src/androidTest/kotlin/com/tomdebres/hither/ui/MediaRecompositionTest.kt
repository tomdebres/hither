package com.tomdebres.hither.ui

import com.tomdebres.hither.core.rules.ImageSource
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.model.CastMember
import com.tomdebres.hither.core.model.Credits
import com.tomdebres.hither.core.model.Keyword
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.rules.MediaPage
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.core.rules.moviePage
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.LocalRecompositionProbe
import com.tomdebres.hither.ui.media.MediaModel
import com.tomdebres.hither.ui.media.MediaScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A3's bound for a move between two of tv-material's focusables (DECISIONS 321): about 32 composition passes along a
 * row, 41 between rows, with the scale in the layer phase; tv-material's z-index spring is in them.
 */
private const val PASSES_PER_MOVE = 48

/** A season's row in the request panel as tv-material's list item reports it: "row Season 2 · 12 Episodes". */
private val SeasonRowName = Regex("""row Season (\d+) · .*""")

/**
 * A D-pad move on the title page recomposes the controls it touches and nothing else, a few times each (DECISIONS 53, 57;
 * A4 on A3's way of counting, DECISIONS 321): between the actions, the two buttons; down to the overview, the button left
 * and the overview's stop; down into Cast & Crew and along it, the two controls; down to the keywords and along them, the
 * two. Never the first screen (the header), the page, a shelf as a whole, or anything while the page scrolls; and
 * nothing once focus rests.
 *
 * Counted two ways, and logged per move (logcat, tag MediaRecomposition, for the counts per move in DECISIONS):
 * - **by name:** [LocalRecompositionProbe], summed per control (a portrait's item, lockup and caption as one);
 * - **every scope:** [ScopeCounter], Compose's own observer, which also sees inside tv-material's `Surface` (its z-index
 *   spring, about ten frames a move) and catches anything that reads an animation in composition: a page that read its
 *   scroll in composition would run a pass on every frame of the scroll.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MediaRecompositionTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val counts = HashMap<String, Int>()
    private val scopes = ScopeCounter()
    private var started = false

    /** At most this many recompositions of one control for one move (A3's bound for a lockup). */
    private val perControl = 6

    /** Twelve in the cast and four keywords: a page that scrolls, with two shelves under its first screen. */
    private fun page(): MediaPage = moviePage(
        MovieDetails(
            id = 7,
            title = "Film 7",
            tagline = "A tagline.",
            overview = "A film to read about.",
            releaseDate = "2026-09-16",
            runtime = 94,
            credits = Credits(cast = (1..12).map { CastMember(id = it, name = "Person $it", character = "Part $it") }),
            keywords = (1..4).map { Keyword(it, "keyword $it") },
        ),
        ImageSource.unknown(MediaPages.BASE),
        MediaPages.server,
        MediaPages.account,
    )

    private fun show(page: MediaPage = page(), card: TitleCard = MediaPages.card(), first: String = "action Request") {
        rule.setContent {
            CompositionLocalProvider(LocalRecompositionProbe provides { name -> counts[name] = (counts[name] ?: 0) + 1 }) {
                SideEffect { if (!started) scopes.start().also { started = true } }
                val scope = rememberCoroutineScope()
                val model = remember { MediaModel({ _, _ -> page }, card, scope) }
                DpadHost { MediaScreen(model) }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(first).fetchSemanticsNodes().isNotEmpty() }
        settle()
        rule.waitUntil(3_000) { focusedTag() == first }
        settle()
    }

    /** Lets every animation (focus, the page's scroll, a shelf's) run to its end. */
    private fun settle() {
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.runOnIdle {
            counts.clear()
            scopes.take()
        }
        rule.onRoot().performKeyInput { pressKey(key) }
        settle()
    }

    /** What a move recomposed: by name, and every scope with the composition passes that ran. */
    private class Move(val named: Map<String, Int>, val scopes: Int, val passes: Int)

    private fun take(): Move = rule.runOnIdle {
        val (recomposed, passes) = scopes.take()
        Move(HashMap(counts), recomposed, passes).also { counts.clear() }
    }

    private fun focusedTag(): String? = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { null }
    }.getOrNull()

    /**
     * What recomposed, by control: a portrait's item, lockup and caption as one (its key), a keyword chip by its id, a
     * season's row in the request panel and its list item as one ("season 2": A5's rows are tv-material's `ListItem`s,
     * which report as "row Season 2 · 12 Episodes").
     */
    private fun controls(counts: Map<String, Int>): Map<String, Int> = counts.entries
        .groupBy({ name ->
            SeasonRowName.matchEntire(name.key)?.let { "season ${it.groupValues[1]}" }
                ?: name.key.removePrefix("cast item ").removePrefix("cast card ").removePrefix("cast caption ").removePrefix("keyword tag ")
        }, { it.value })
        .mapValues { it.value.sum() }

    private fun assertOnly(move: String, allowed: Set<String>, taken: Move) {
        val controls = controls(taken.named)
        Log.i("MediaRecomposition", "$move: controls $controls, ${taken.scopes} scopes in ${taken.passes} passes")
        val others = controls.keys - allowed
        if (others.isNotEmpty()) throw AssertionError("$move recomposed $others, not only $allowed: $controls")
        val tooOften = controls.filterValues { it > perControl }
        if (tooOften.isNotEmpty()) throw AssertionError("$move recomposed a control more than $perControl times: $tooOften")
        if (taken.passes > PASSES_PER_MOVE) {
            throw AssertionError("$move ran ${taken.passes} composition passes (${taken.scopes} scopes), more than $PASSES_PER_MOVE: something reads an animation in composition")
        }
    }

    private fun move(key: Key, expectFocus: String, allowed: Set<String>) {
        val from = focusedTag()
        press(key)
        if (focusedTag() != expectFocus) throw AssertionError("$key from $from went to ${focusedTag()}, not $expectFocus")
        assertOnly("$key from $from", allowed, take())
    }

    @Test
    fun aMoveBetweenTheButtonsRecomposesOnlyTheTwoButtons() {
        show()
        take()
        val both = setOf("button Request", "button Request in 4K")
        move(Key.DirectionRight, "action Request in 4K", both)
        move(Key.DirectionLeft, "action Request", both)
        move(Key.DirectionRight, "action Request in 4K", both)
    }

    @Test
    fun aWalkDownThePageRecomposesOnlyTheControlsItTouches() {
        show()
        take()
        // the first screen: the page stays at its top
        move(Key.DirectionDown, "media overview", setOf("button Request", "text stop media overview"))
        // into Cast & Crew: the page scrolls to the shelf, the header and the page do not recompose for it
        move(Key.DirectionDown, "person cast:0:1", setOf("text stop media overview", "cast:0:1"))
        // along the shelf: the first moves keep it still, the later ones scroll it under the focus
        for (n in 0 until 11) move(Key.DirectionRight, "person cast:${n + 1}:${n + 2}", setOf("cast:$n:${n + 1}", "cast:${n + 1}:${n + 2}"))
        // down to the keywords, the last shelf (it brings Information up with it), and along them
        move(Key.DirectionDown, "keyword keyword 1", setOf("cast:11:12", "1"))
        move(Key.DirectionRight, "keyword keyword 2", setOf("1", "2"))
        move(Key.DirectionRight, "keyword keyword 3", setOf("2", "3"))
        // and back up: the shelf left, the overview, the first action
        move(Key.DirectionUp, "person cast:11:12", setOf("3", "cast:11:12"))
        move(Key.DirectionUp, "media overview", setOf("cast:11:12", "text stop media overview"))
        move(Key.DirectionUp, "action Request", setOf("text stop media overview", "button Request"))
    }

    @Test
    fun focusAtRestRecomposesNothing() {
        // Once a move's own frames are done (the focus, the stand-in focus a frame later, tv-material's z-index spring:
        // well inside 250 ms; the page's scroll, 300 ms), focus resting on the page recomposes nothing.
        show()
        press(Key.DirectionDown)
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            counts.clear()
            scopes.take()
        }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        if (focusedTag() != "person cast:0:1") throw AssertionError("Down went to ${focusedTag()}")
        take()
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
        val rest = take()
        rule.mainClock.autoAdvance = true
        Log.i("MediaRecomposition", "the rest: ${rest.named}, ${rest.scopes} scopes in ${rest.passes} passes")
        if (rest.named.isNotEmpty() || rest.scopes > 0) {
            throw AssertionError("focus resting on the page recomposed ${rest.scopes} scopes in ${rest.passes} passes: ${rest.named}")
        }
    }

    @Test
    fun aMoveDownTheSeasonsRecomposesOnlyTheTwoRows() {
        show(MediaPages.series(), MediaPages.seriesCard(), first = "action Request More")
        press(Key.DirectionCenter)
        rule.waitUntil(3_000) { focusedTag() == "season 2" }
        settle()
        take()
        // in the side panel (A5): season 1 is taken and skipped; 2 to 6 are open
        for (n in 2 until 6) {
            move(Key.DirectionDown, "season ${n + 1}", setOf("season $n", "season ${n + 1}"))
        }
        // and one flip recomposes that row, the rows holding the pick and the primary, whose
        // words change; no other row
        press(Key.DirectionCenter)
        assertOnly("Select on season 6", setOf("season 6", "season table", "button Select Season(s)", "button Request 1 Season"), take())
    }
}
