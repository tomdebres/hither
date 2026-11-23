package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.PageInfo
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.RequestPage
import com.tomdebres.hither.core.model.RequestSeason
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.RequestFilter
import com.tomdebres.hither.core.rules.RequestTitle
import com.tomdebres.hither.core.rules.SignInRequest
import com.tomdebres.hither.session.ActiveAdmin
import com.tomdebres.hither.session.AdminAccess
import com.tomdebres.hither.session.AdminCalls
import com.tomdebres.hither.session.AdminSignIn
import com.tomdebres.hither.session.AdminSummary
import com.tomdebres.hither.session.AdminSwitch
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.approvals.ApprovalsModel
import com.tomdebres.hither.ui.approvals.ApprovalsScreen
import com.tomdebres.hither.ui.approvals.ApprovalsSource
import com.tomdebres.hither.ui.approvals.Decided
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.LocalRecompositionProbe
import com.tomdebres.hither.ui.home.HomeScreen
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Everyone's requests as the TV's admin (M5, DECISIONS 94), as tvOS's Library (A6, DECISIONS 338), on a device: focus
 * on the first request to decide; Select on it opens the three-way dialog (Approve, Decline, Open Title); Approve deciding
 * once with focus moving on to the next request before the lockup reads its new status; Decline asking again; Tom's
 * three filters (D3), opening on Pending, a choice keeping focus; who asked; a move recomposing the two lockups it
 * touches; and My Requests' "Waiting for Approval" as the way in, asking for the PIN when the admin is not active.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ApprovalsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val family = User(id = 8, displayName = "User 8")
    private fun film(id: Int, status: Int = RequestStatus.PENDING) =
        MediaRequest(id = id, status = status, type = "movie", createdAt = "2026-11-10T09:00:00.000Z",
            media = MediaInfo(mediaType = "movie", tmdbId = id * 10, status = MediaStatus.PENDING), requestedBy = family)
    private fun series(id: Int) =
        MediaRequest(id = id, status = RequestStatus.PENDING, type = "tv", createdAt = "2026-11-10T09:00:00.000Z",
            media = MediaInfo(mediaType = "tv", tmdbId = id * 10, status = MediaStatus.PENDING), seasons = listOf(RequestSeason(1, 1), RequestSeason(2, 2)), requestedBy = family)

    private val decided: MutableList<Pair<Int, Boolean>> = Collections.synchronizedList(mutableListOf())
    private val outcomes: MutableList<Decided> = Collections.synchronizedList(mutableListOf())
    private val asked: MutableList<RequestFilter> = Collections.synchronizedList(mutableListOf())

    /** The server answers a decision with a 500 (A5: the failure's dialog). */
    @Volatile private var decideFails = false

    /** A9: the first ask for a later page fails; every later ask answers. The asks for page 2, counted. */
    @Volatile private var laterPageFailsOnce = false
    private val secondPageAsks = java.util.concurrent.atomic.AtomicInteger()

    private inner class Source(private val requests: List<MediaRequest>) : ApprovalsSource {
        override suspend fun me() = User(id = 9, displayName = "Approver", permissions = Permission.MANAGE_REQUESTS)
        override suspend fun page(filter: RequestFilter, skip: Int, take: Int): RequestPage {
            asked += filter
            if (skip == 10 && secondPageAsks.incrementAndGet() == 1 && laterPageFailsOnce) throw SeerrException.Http(500, "boom")
            val chosen = requests.filter { filter == RequestFilter.ALL || it.status == RequestStatus.PENDING }
            return RequestPage(PageInfo(results = chosen.size), chosen.drop(skip).take(take))
        }
        override suspend fun title(type: MediaType, tmdbId: Int) = RequestTitle("Title $tmdbId", "2026", null, null)
        override suspend fun decide(requestId: Int, approve: Boolean): MediaRequest {
            decided += requestId to approve
            if (decideFails) throw SeerrException.Http(500, "boom")
            return requests.single { it.id == requestId }
        }
    }

    private val recomposed = mutableMapOf<String, Int>()

    private fun show(requests: List<MediaRequest>) {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { ApprovalsModel(Source(requests), scope, onDecided = { outcomes += it }) }
            CompositionLocalProvider(LocalRecompositionProbe provides { name -> recomposed[name] = (recomposed[name] ?: 0) + 1 }) {
                DpadHost { ApprovalsScreen(model, onOpen = {}, onBack = {}) }
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
    }

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "?" } ?: "?"
    }.getOrElse { "nothing" }

    private fun awaitFocus(tag: String) {
        val held = runCatching { rule.waitUntil(10_000) { focusedTag() == tag } }.isSuccess
        assertTrue("focus is on ${focusedTag()}, not $tag", held)
    }

    private fun shown(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Whether [row]'s lockup reads [status] under its title (its glyph and word: `status <words>`). */
    private fun reads(row: String, status: String): Boolean =
        rule.onAllNodes(hasTestTag("status $status").and(hasAnyAncestor(hasTestTag("lockup approval $row"))), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** The status [row]'s lockup reads, by its glyph and word's tag. */
    private fun statusWords(row: String): List<String> =
        rule.onAllNodes(hasAnyAncestor(hasTestTag("lockup approval $row")), useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrElseNullable(SemanticsProperties.TestTag) { null } }.filter { it.startsWith("status ") }

    /** The words drawn under [tag] in [row]'s lockup, every level down. */
    private fun words(row: String, tag: String): String {
        fun collect(node: SemanticsNode): List<String> =
            node.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text } + node.children.flatMap(::collect)
        return rule.onAllNodes(hasTestTag(tag).and(hasAnyAncestor(hasTestTag("lockup approval $row"))), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap(::collect).joinToString(" ")
    }

    @Test
    fun itOpensOnPendingWithFocusOnTheFirstToDecideAndSaysWhoAsked() {
        show(listOf(film(1), series(2), film(3, RequestStatus.APPROVED)))
        awaitFocus("approval request:1")
        assertEquals(listOf(RequestFilter.PENDING), asked)
        assertTrue(shown("approval request:2"))
        assertTrue("an approved one is not in Pending", !shown("approval request:3"))
        // the status, then when and who asked under the title (Seerr's "Requested {when} by {who}", one line)
        assertTrue("request:1 reads Pending", reads("request:1", "Pending"))
        val line = words("request:1", "card line")
        assertTrue("one line: when, by, who ($line)", line.endsWith(" by User 8"))
        // the chosen filter ticked; the lockups side by side, fenced at the line's end
        rule.onNode(hasTestTag("tick") and hasAnyAncestor(hasTestTag("filter pending")), useUnmergedTree = true).assertExists()
        press(Key.DirectionRight)
        awaitFocus("approval request:2")
        press(Key.DirectionRight)
        assertEquals("fenced", "approval request:2", focusedTag())
        // Left back to the side list, on the chosen filter; Right comes back to the lockup left
        press(Key.DirectionLeft)
        press(Key.DirectionLeft)
        awaitFocus("filter pending")
        press(Key.DirectionRight)
        awaitFocus("approval request:1")
    }

    /**
     * A9 (the review of A6's merged code): a later page that fails ends the grid with Try Again (My Requests' lockup,
     * DECISIONS 65's rule); moving along does not ask again; Select on it asks for that page once, and focus goes to the
     * first new lockup. Before, nothing ended the grid and the queue stayed at ten until the filter changed.
     */
    @Test
    fun aLaterPageThatFailsEndsTheGridWithTryAgainWhichHandsFocusToTheFirstNewLockup() {
        laterPageFailsOnce = true
        show((1..12).map { film(it) })
        awaitFocus("approval request:1")
        // down to the third line: focus near the end asks for page 2, which fails
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        awaitFocus("approval request:9")
        rule.waitUntil(5_000) { secondPageAsks.get() == 1 }
        rule.waitForIdle()
        assertTrue("the ten requests stay", shown("approval request:10"))
        assertEquals("a later page that failed ends the grid with Try Again", 1, rule.onAllNodesWithTag("retry more").fetchSemanticsNodes().size)
        // moving along does not ask again
        press(Key.DirectionRight)
        awaitFocus("approval request:10")
        press(Key.DirectionRight)
        awaitFocus("retry more")
        assertEquals("moving along asked again", 1, secondPageAsks.get())
        // Select asks once; focus goes on to the first new lockup, and nothing was decided
        press(Key.DirectionCenter)
        awaitFocus("approval request:11")
        assertEquals(2, secondPageAsks.get())
        assertTrue(!shown("retry more"))
        assertTrue("nothing decided", decided.isEmpty())
    }

    @Test
    fun theQueuesSelectOpensTheThreeWayDialog() {
        show(listOf(film(1), series(2)))
        awaitFocus("approval request:1")
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { shown("decision") }
        // Approve, Decline, Open Title, then Cancel; Approve focused, as tvOS's dialog (Select on the lockup was the asking)
        awaitFocus("approve request:1")
        for (tag in listOf("decline request:1", "open title request:1", "close decision")) assertTrue(tag, shown(tag))
        assertEquals("the dialog's title is the title's", "Title 10", rule.onNodeWithTag("dialog title", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text })
        press(Key.DirectionDown)
        awaitFocus("decline request:1")
        press(Key.DirectionDown)
        awaitFocus("open title request:1")
        press(Key.DirectionDown)
        awaitFocus("close decision")
        press(Key.DirectionDown)
        assertEquals("the dialog keeps focus", "close decision", focusedTag())
        assertTrue("nothing decided by looking", decided.isEmpty())
        // Back closes it; focus is back on the lockup
        Espresso.pressBack()
        rule.waitForIdle()
        rule.waitUntil(5_000) { !shown("decision") }
        awaitFocus("approval request:1")
        // a series' dialog says its seasons
        press(Key.DirectionRight)
        awaitFocus("approval request:2")
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { shown("decision") }
        val message = rule.onNodeWithTag("dialog message", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        assertTrue("the message names the seasons: $message", message.endsWith("Seasons 1, 2"))
        assertTrue(decided.isEmpty())
    }

    @Test
    fun aDecisionThatFailsIsADialogWithOKAndFocusComesBackToItsLockup() {
        decideFails = true
        show(listOf(film(1), film(2)))
        awaitFocus("approval request:1")
        press(Key.DirectionCenter)
        awaitFocus("approve request:1")
        press(Key.DirectionCenter)
        // once the decision's dialog has gone: tvOS's title, Seerr's words, OK focused
        rule.waitUntil(5_000) { shown("decision problem") }
        awaitFocus("problem ok")
        val title = rule.onNodeWithTag("dialog title", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        val message = rule.onNodeWithTag("dialog message", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        assertEquals("Request Not Approved", title)
        assertEquals("The request could not be changed. Try again.", message)
        press(Key.DirectionUp)
        assertEquals("the dialog keeps focus", "problem ok", focusedTag())
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { !shown("decision problem") }
        // back on the lockup it was about, still Pending, still to decide
        awaitFocus("approval request:1")
        assertTrue(reads("request:1", "Pending"))
        assertEquals(listOf<Decided>(Decided.Failed), outcomes)
        // and nothing on the lockup or the corner said "approved"
        decideFails = false
        press(Key.DirectionCenter)
        awaitFocus("approve request:1")
        press(Key.DirectionCenter)
        awaitFocus("approval request:2")
        assertTrue("no dialog for a decision that went through", !shown("decision problem"))
    }

    /** With the clock stopped: whatever the source and the page have to do now, then [frames] frames. */
    private fun settle(frames: Int) {
        repeat(3) {
            Thread.sleep(150)
            rule.waitForIdle()
        }
        repeat(frames) { rule.mainClock.advanceTimeByFrame() }
        rule.waitForIdle()
    }

    /**
     * A9 (the review of A5's merged code): a decision's failure waits for another request's dialog, opened in the moment
     * before its own shows, to go; its OK then gives focus back to the request it was about with no dialog left open.
     */
    @Test
    fun aDecisionsFailureWaitsForAnotherRequestsDialog() {
        decideFails = true
        show(listOf(film(1), film(2)))
        awaitFocus("approval request:1")
        press(Key.DirectionCenter)
        awaitFocus("approve request:1")
        // the clock stopped: the decision fails at once, and the moment before its dialog is the test's
        rule.mainClock.autoAdvance = false
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        settle(frames = 6)
        assertEquals("focus back on the lockup as the dialog went", "approval request:1", focusedTag())
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        settle(frames = 3)
        assertEquals("approval request:2", focusedTag())
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        settle(frames = 4)
        assertTrue("request 2's dialog is open", shown("decision"))
        assertTrue("the failure's dialog is not up yet", !shown("decision problem"))
        rule.mainClock.advanceTimeBy(700)
        rule.waitForIdle()
        assertTrue("the failure's dialog came over request 2's dialog (focus on ${focusedTag()})", !shown("decision problem"))
        assertEquals("request 2's dialog keeps focus", "approve request:2", focusedTag())
        // Back closes it onto request 2; then the failure's dialog, and its OK goes back to request 1 with nothing open
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        settle(frames = 6)
        assertEquals("approval request:2", focusedTag())
        rule.mainClock.advanceTimeBy(700)
        rule.mainClock.autoAdvance = true
        rule.waitUntil(5_000) { shown("decision problem") }
        awaitFocus("problem ok")
        rule.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitUntil(5_000) { !shown("decision problem") }
        awaitFocus("approval request:1")
        assertTrue("no dialog left open", !shown("decision"))
        assertEquals(listOf(1 to true), decided.toList())
    }

    @Test
    fun approveDecidesOnceAndADecidedLockupShowsItsNewStatusOnceFocusHasMovedOn() {
        show(listOf(film(1), film(2)))
        awaitFocus("approval request:1")
        assertTrue(reads("request:1", "Pending"))
        press(Key.DirectionCenter)
        awaitFocus("approve request:1")
        press(Key.DirectionCenter)
        // focus moves on to the next to decide first, then the lockup takes its new status, in place
        awaitFocus("approval request:2")
        assertEquals(listOf(1 to true), decided)
        val read = runCatching { rule.waitUntil(5_000) { reads("request:1", "Requested") } }.isSuccess
        assertTrue("the decided lockup does not read Requested: ${words("request:1", "card title")} reads ${statusWords("request:1")}", read)
        assertTrue("the decided lockup stays", shown("lockup approval request:1"))
        assertEquals(listOf<Decided>(Decided.Done("Title 10", approved = true)), outcomes)
        // Select on a decided lockup has nothing to decide: no dialog
        press(Key.DirectionLeft)
        awaitFocus("approval request:1")
        press(Key.DirectionCenter)
        assertTrue("a decided lockup opened the dialog", !shown("decision"))
        assertEquals(listOf(1 to true), decided)
    }

    @Test
    fun declineAsksAgainWithCancelFocusedAndTheLastOneSendsFocusToTheFilters() {
        show(listOf(film(1)))
        awaitFocus("approval request:1")
        press(Key.DirectionCenter)
        awaitFocus("approve request:1")
        press(Key.DirectionDown)
        awaitFocus("decline request:1")
        press(Key.DirectionCenter)
        awaitFocus("cancel decline")
        assertTrue("nothing sent on asking", decided.isEmpty())
        Espresso.pressBack()
        rule.waitForIdle()
        awaitFocus("approval request:1")
        press(Key.DirectionCenter)
        awaitFocus("approve request:1")
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        awaitFocus("cancel decline")
        // Decline sits over Cancel in the question (A1's ConfirmDialog)
        press(Key.DirectionUp)
        awaitFocus("confirm")
        press(Key.DirectionCenter)
        awaitFocus("filter pending")
        assertEquals(listOf(1 to false), decided)
        val read = runCatching { rule.waitUntil(5_000) { reads("request:1", "Declined") } }.isSuccess
        assertTrue("the declined lockup does not read Declined: it reads ${statusWords("request:1")}", read)
    }

    @Test
    fun theFiltersAreTomsThreeAndChoosingOneReadsTheListUnderIt() {
        show(listOf(film(1), film(3, RequestStatus.APPROVED)))
        awaitFocus("approval request:1")
        press(Key.DirectionLeft)
        awaitFocus("filter pending")
        press(Key.DirectionDown)
        awaitFocus("filter approved")
        press(Key.DirectionDown)
        awaitFocus("filter all")
        press(Key.DirectionDown)
        assertEquals("the side list's end", "filter all", focusedTag())
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { shown("approval request:3") }
        assertEquals(listOf(RequestFilter.PENDING, RequestFilter.ALL), asked)
        // focus stays on the filter chosen, now ticked
        awaitFocus("filter all")
        rule.onNode(hasTestTag("tick") and hasAnyAncestor(hasTestTag("filter all")), useUnmergedTree = true).assertExists()
        // Tom's three of Seerr's nine (D3)
        for (filter in RequestFilter.entries) {
            assertEquals(filter.api, filter in listOf(RequestFilter.PENDING, RequestFilter.APPROVED, RequestFilter.ALL), shown("filter ${filter.api}"))
        }
    }

    @Test
    fun aNewFilterShowsItsListFromTheTop() {
        // newest first: the pending request is the oldest, and All puts more lockups above it than the screen holds
        show(listOf(12, 11, 10, 9, 8, 7, 6, 5).map { film(it, RequestStatus.APPROVED) } + film(1))
        awaitFocus("approval request:1")
        press(Key.DirectionLeft)
        awaitFocus("filter pending")
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        awaitFocus("filter all")
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { shown("approval request:12") }
        val top = rule.onNodeWithTag("approval request:12", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val list = rule.onNodeWithTag("approvals list", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the newest lockup is at the grid's top, in view: $top in $list", top.top >= list.top && top.top < list.top + 100f)
    }

    @Test
    fun aMoveRecomposesTheTwoLockupsItTouches() {
        show(listOf(film(1), film(2), film(3)))
        awaitFocus("approval request:1")
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        recomposed.clear()
        press(Key.DirectionRight)
        awaitFocus("approval request:2")
        // the lockup's grid item, the lockup and its caption (a card's key is its title's: movie:<id>0)
        val touched = recomposed.keys.filter { it.startsWith("approval item ") || it.startsWith("card view ") || it.startsWith("card caption ") }
        val ids = touched.map { if (it.startsWith("approval item request:")) it.removePrefix("approval item request:").toInt() else it.substringAfterLast("movie:").toInt() / 10 }.toSet()
        assertTrue("a move recomposed $ids: $recomposed", setOf(1, 2).containsAll(ids))
        assertTrue("each a few times: $recomposed", touched.all { recomposed.getValue(it) <= 4 })
    }

    // ---- the way in, through the home -----------------------------------------------------------

    private class Kept(active: Boolean) : AdminAccess {
        override val active = MutableStateFlow(if (active) ActiveAdmin(9, "Approver") else null)
        override suspend fun kept() = AdminSummary("Approver")
        override suspend fun signIn(request: SignInRequest, family: User) = AdminSignIn.Failed(com.tomdebres.hither.core.SignInFailure.OTHER)
        override suspend fun setPin(pin: String) = false
        override suspend fun cancel() = Unit
        override suspend fun remove() = Unit
        override suspend fun switchIn(pin: String) = AdminSwitch.NoneKept
        override fun back() {
            active.value = null
        }
    }

    /** A server whose requests count says 2 are waiting; anything else is not found. */
    private fun counting() = SeerrClient(
        MockEngine { call ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when (call.url.encodedPath) {
                "/api/v1/request/count" -> respond("""{"pending":2}""", HttpStatusCode.OK, json)
                "/api/v1/request" -> respond("""{"pageInfo":{"results":0},"results":[]}""", HttpStatusCode.OK, json)
                "/api/v1/auth/me" -> respond("""{"id":9,"displayName":"Approver","permissions":16}""", HttpStatusCode.OK, json)
                else -> respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, json)
            }
        },
        "http://seerr.test:5055",
        AcceptAllCookiesStorage(),
    )

    private fun showHome(admin: Kept) {
        val client = counting()
        val access = object : SeerrAccess {
            override suspend fun <T> call(call: suspend (SeerrClient) -> T): T = call(client)
        }
        val calls = object : AdminCalls {
            override suspend fun <T> asAdmin(call: suspend (SeerrClient) -> T): T = call(client)
        }
        val session = Session(family, PublicSettings(applicationTitle = "Seerr", localLogin = true), baseUrl = "http://seerr.test:5055")
        rule.setContent { DpadHost { HomeScreen(Screen.Home(session), access, onSignOut = {}, admin = admin, adminCalls = calls) } }
        rule.waitForIdle()
    }

    /** To My Requests' side list (A6: "Waiting for Approval" is its row, no longer words under the bar's Requests). */
    private fun toWaiting() {
        rule.waitUntil(10_000) { focusedTag() != "nothing" }
        // to the bar, against its left end, then one along: Requests
        press(Key.DirectionUp)
        repeat(4) { press(Key.DirectionLeft) }
        awaitFocus("nav Discover")
        press(Key.DirectionRight)
        awaitFocus("nav Requests")
        rule.waitUntil(10_000) { shown("waiting") }
        press(Key.DirectionDown)
        awaitFocus("side my requests")
        press(Key.DirectionDown)
        awaitFocus("waiting")
        assertEquals("Waiting for Approval 2", words("waiting"))
    }

    /** The words drawn under [tag], every level down. */
    private fun words(tag: String): String {
        fun collect(node: SemanticsNode): List<String> =
            node.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text } + node.children.flatMap(::collect)
        return collect(rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()).joinToString(" ")
    }

    @Test
    fun theWaitingCountAsksForThePinWhenTheAdminIsNotActive() {
        showHome(Kept(active = false))
        toWaiting()
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { shown("admin pin dialog") }
        awaitFocus("pin 5")
    }

    @Test
    fun theWaitingCountOpensEveryonesRequestsWhenTheAdminIsActive() {
        val admin = Kept(active = true)
        showHome(admin)
        toWaiting()
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { shown("approvals heading") }
        assertTrue("the badge over it", shown("admin badge"))
        // back to the family (the idle clock, Hither leaving the front): the admin's page goes with it
        admin.back()
        rule.waitUntil(5_000) { !shown("approvals heading") }
        assertTrue("Discover again", shown("bar"))
    }
}
