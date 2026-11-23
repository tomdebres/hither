package com.tomdebres.hither.ui

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
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.DiscoverRow
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.LocalRecompositionProbe
import com.tomdebres.hither.ui.discover.DiscoverModel
import com.tomdebres.hither.ui.discover.DiscoverPage
import com.tomdebres.hither.ui.discover.DiscoverScreen
import com.tomdebres.hither.ui.discover.DiscoverSource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A3's bound (DECISIONS 321), from its counts on tv31: 32 or 33 passes a move along a row and 41 a move between rows with
 * the scale in the layer phase; 63 and 73 with tv-material's own scale, read in composition.
 */
private const val PASSES_PER_MOVE = 48

/**
 * A D-pad move on Discover recomposes the lockup losing focus and the lockup gaining it, no other, and only a few times
 * each (DECISIONS 53, 57; A3, DECISIONS 321). 0.1.0-m1 stuttered on the Shield because every card in a row read the
 * row's focused index to place its focus requester, so each move recomposed the row; and the card losing focus read its
 * fade in composition, so it recomposed on every frame of it.
 *
 * Counted two ways, and logged per move (logcat, tag DiscoverRecomposition, for the counts per move in DECISIONS):
 * - **by name:** [LocalRecompositionProbe] (`ReportRecomposition` in the lockup, its caption and its row item), summed
 *   per title's key: which lockups recomposed, and how often;
 * - **every scope:** [ScopeCounter], Compose's own observer, which also sees the scopes inside tv-material's `Card`. Its
 *   `Surface` reads its focus scale (300 ms in, 500 ms out) and its z-index's spring in composition: on tv-material's
 *   own scale a move recomposed both cards on every frame for half a second (A3's measurement). The lockups hold that
 *   scale at 1 and scale in the layer phase; the z-index's spring is still tv-material's, about ten frames.
 */
@RunWith(AndroidJUnit4::class)
class DiscoverRecompositionTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val session = Session(User(id = 8, displayName = "TV", permissions = 32), PublicSettings(applicationTitle = "Seerr", localLogin = true))

    /** What recomposed since the last [take], by the name each part reports. */
    private val counts = HashMap<String, Int>()

    private val scopes = ScopeCounter()

    /**
     * At most this many recompositions of one lockup (its item, its view and its caption together) for one move. The
     * lockup gaining focus recomposes as its focus, its caption's colour and the row's entry move to it; the one losing
     * focus the same. A lockup that recomposes on each frame of an animation goes far past it.
     */
    private val perLockup = 6

    /**
     * At most this many composition passes for one move (a lockup's row item is a composition of its own, recomposed once
     * a frame while anything in it changes). The move itself, the row's entry, the caption's colour, the stand-in focus a
     * frame later, a lockup coming in at the row's end, and tv-material's z-index spring (about ten frames) for the two
     * lockups. Its 500 ms scale, read in composition, runs a pass every frame for half a second.
     */
    private val passesPerMove = PASSES_PER_MOVE

    /** Row n's cards are films n*100+1 .. n*100+20, one page, no posters (nothing to fetch). */
    private fun page(row: DiscoverRow) = DiscoverPage(
        "http://seerr.test",
        Page(
            page = 1,
            totalPages = 1,
            results = (1..20).map { MediaResult(id = row.ordinal * 100 + it, mediaType = "movie", title = "Film ${row.ordinal * 100 + it}") },
        ),
    )

    private fun key(row: DiscoverRow, n: Int) = "movie:${row.ordinal * 100 + n}"

    private fun show() {
        rule.setContent {
            CompositionLocalProvider(LocalRecompositionProbe provides { name -> counts[name] = (counts[name] ?: 0) + 1 }) {
                SideEffect { if (!started) scopes.start().also { started = true } }
                val scope = rememberCoroutineScope()
                val model = remember { DiscoverModel(DiscoverSource { row, _ -> page(row) }, session, scope) }
                DpadHost { DiscoverScreen(model, onOpen = {}) }
            }
        }
        // The rows' pages are read off the main thread, which waitForIdle does not wait for.
        for (row in listOf(DiscoverRow.TRENDING, DiscoverRow.POPULAR_MOVIES)) {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("card ${key(row, 1)}").fetchSemanticsNodes().isNotEmpty() }
        }
        settle()
    }

    private var started = false

    /** Lets every animation (focus, scroll, the bar) run to its end. */
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

    /** What the move recomposed: by name, and every scope with the frames that recomposed. */
    private class Move(val named: Map<String, Int>, val scopes: Int, val passes: Int)

    private fun take(): Move = rule.runOnIdle {
        val (recomposed, passes) = scopes.take()
        Move(HashMap(counts), recomposed, passes).also { counts.clear() }
    }

    /** Recompositions per lockup key, from the row item's, the lockup's and its caption's reports. */
    private fun lockups(counts: Map<String, Int>): Map<String, Int> = counts.entries
        .filter { it.key.startsWith("card ") }
        .groupBy({ it.key.substringAfterLast(' ') }, { it.value })
        .mapValues { it.value.sum() }

    private fun focusedTag(): String? = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { null }
    }.getOrNull()

    private fun assertOnlyTheTwoLockups(move: String, from: String, to: String, taken: Move) {
        val lockups = lockups(taken.named)
        // The counts of a passing run are the numbers DECISIONS quotes (logcat, tag DiscoverRecomposition).
        Log.i("DiscoverRecomposition", "$move: lockups $lockups, ${taken.scopes} scopes in ${taken.passes} passes; named ${taken.named}")
        val others = lockups.keys - setOf(from, to)
        if (others.isNotEmpty()) {
            throw AssertionError("$move recomposed ${lockups.size} lockups, not just $from and $to: $lockups")
        }
        val tooOften = lockups.filterValues { it > perLockup }
        if (tooOften.isNotEmpty()) {
            throw AssertionError("$move recomposed a lockup more than $perLockup times: $tooOften (all: ${taken.named})")
        }
        if (taken.passes > passesPerMove) {
            throw AssertionError("$move ran ${taken.passes} composition passes (${taken.scopes} scopes), more than $passesPerMove: something reads an animation in composition")
        }
    }

    @Test
    fun aMoveAlongARowRecomposesOnlyTheLockupLeftAndTheLockupReached() {
        show()
        take()
        // Along Trending: the row moves under a still focus (DECISIONS 40), a lockup coming in at its right each move.
        for (n in 1..10) {
            val from = key(DiscoverRow.TRENDING, n)
            val to = key(DiscoverRow.TRENDING, n + 1)
            press(Key.DirectionRight)
            if (focusedTag() != "card $to") throw AssertionError("Right from $from went to ${focusedTag()}, not $to")
            assertOnlyTheTwoLockups("Right from $from", from, to, take())
        }
    }

    @Test
    fun aMoveBetweenRowsRecomposesOnlyTheTwoLockupsAndNotTheBar() {
        show()
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        take()
        val from = key(DiscoverRow.TRENDING, 3)
        val to = key(DiscoverRow.POPULAR_MOVIES, 1)
        press(Key.DirectionDown)
        if (focusedTag() != "card $to") throw AssertionError("Down from $from went to ${focusedTag()}, not $to")
        val taken = take()
        assertOnlyTheTwoLockups("Down from $from", from, to, taken)
        // The bar shows the same thing whichever row has focus.
        val bar = taken.named["TopBar"] ?: 0
        if (bar > 0) throw AssertionError("Down recomposed the top bar $bar times: ${taken.named}")
    }

    @Test
    fun aLockupAtRestRecomposesNothing() {
        // Once a move's own frames are done (the focus, the caption, the stand-in focus, the z-index's spring: well
        // inside 250 ms), focus resting on a lockup recomposes nothing: no rest animation (Tom's Q4 took the overview
        // out of the focused card, DECISIONS 111's rest) and no scale read in composition (tv-material's took 500 ms
        // to leave the lockup left).
        show()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle {
            counts.clear()
            scopes.take()
        }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.mainClock.advanceTimeBy(250)
        rule.waitForIdle()
        if (focusedTag() != "card ${key(DiscoverRow.TRENDING, 2)}") throw AssertionError("Right went to ${focusedTag()}")
        take()
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
        val rest = take()
        rule.mainClock.autoAdvance = true
        Log.i("DiscoverRecomposition", "the rest: ${rest.named}, ${rest.scopes} scopes in ${rest.passes} passes")
        if (rest.named.isNotEmpty() || rest.scopes > 0) {
            throw AssertionError("focus resting on a lockup recomposed ${rest.scopes} scopes in ${rest.passes} passes: ${rest.named}")
        }
    }

    @Test
    fun aMoveOntoSeeMoreRecomposesOnlyTheLockupLeftAndSeeMore() {
        // Popular Movies' 20 cards end with See More (DECISIONS 112).
        show()
        press(Key.DirectionDown)
        repeat(19) { press(Key.DirectionRight) }
        val from = key(DiscoverRow.POPULAR_MOVIES, 20)
        if (focusedTag() != "card $from") throw AssertionError("expected the 20th card, focus is on ${focusedTag()}")
        take()
        press(Key.DirectionRight)
        if (focusedTag() != "see more POPULAR_MOVIES") throw AssertionError("Right from $from went to ${focusedTag()}, not See More")
        val taken = take()
        Log.i("DiscoverRecomposition", "onto See More: ${taken.named}, ${taken.scopes} scopes in ${taken.passes} passes")
        val others = lockups(taken.named).keys - from
        if (others.isNotEmpty()) throw AssertionError("the move onto See More recomposed other lockups: ${taken.named}")
        val seeMore = taken.named.filterKeys { it.endsWith("see more POPULAR_MOVIES") }.values.sum()
        if (seeMore > perLockup) throw AssertionError("See More recomposed $seeMore times: ${taken.named}")
        if (taken.passes > passesPerMove) throw AssertionError("the move onto See More ran ${taken.passes} composition passes")
    }
}
