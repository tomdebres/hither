package com.tomdebres.hither.ui

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
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
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PageInfo
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.RequestBody
import com.tomdebres.hither.core.model.RequestPage
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.RequestTitle
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.bar.BarContent
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.LocalRecompositionProbe
import com.tomdebres.hither.ui.components.ShowsFocus
import com.tomdebres.hither.ui.home.HomeScreen
import com.tomdebres.hither.ui.requests.RequestsModel
import com.tomdebres.hither.ui.requests.RequestsScreen
import com.tomdebres.hither.ui.requests.RequestsSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * My Requests as tvOS's Library on a device (A6, DECISIONS 338-339; 66 and 101 before): a side list (My Requests,
 * ticked; Waiting for Approval with its count while the right is read) beside the requests as poster lockups four across.
 * Down from the bar lands on My Requests and Right goes into the grid, Left back; the grid is fenced at a line's end and
 * the focused line rests where the first did; Select opens the title's page and Back returns to the lockup left. A long
 * press of Select (or the Menu key) on a pending request opens its menu, Cancel Request, asked first, and fires no click,
 * on both of the key's paths; a short press opens the title. While the first page loads, the lockups' places stand in and
 * My Requests keeps focus; a failure's Try Again keeps focus until its answer; with no requests the page says so, with
 * the way back; a later page that fails ends the grid with Try Again. A move recomposes the two lockups it touches.
 * Through the whole home: the bar's Requests opens it, and a request made in the modal is there the next time it opens.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RequestsFocusTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val showsFocus = SemanticsMatcher.expectValue(ShowsFocus, true)
    private val counts = HashMap<String, Int>()
    private var left = 0
    private val opened = CopyOnWriteArrayList<String>()
    private val cancelled = CopyOnWriteArrayList<Int>()

    private fun request(id: Int, tmdbId: Int = id * 100, status: Int = RequestStatus.APPROVED) = MediaRequest(
        id = id,
        status = status,
        type = "movie",
        createdAt = "2026-11-10T09:00:00.000Z",
        media = MediaInfo(mediaType = "movie", tmdbId = tmdbId, status = if (status == RequestStatus.PENDING) 2 else 3),
    )

    /** Requests [ids] (newest first), [total] in all on the server; those in [pending] wait for approval. */
    private fun page(ids: Iterable<Int>, total: Int = ids.count(), pending: Set<Int> = emptySet()) =
        RequestPage(PageInfo(results = total), ids.map { request(it, status = if (it in pending) RequestStatus.PENDING else RequestStatus.APPROVED) })

    private open inner class Source(private val pages: suspend (skip: Int, take: Int) -> RequestPage) : RequestsSource {
        override suspend fun page(skip: Int, take: Int): RequestPage = pages(skip, take)

        override suspend fun title(type: MediaType, tmdbId: Int) = RequestTitle("Film $tmdbId", "2026", null, null)

        override suspend fun cancel(requestId: Int) {
            cancelled += requestId
        }
    }

    /** The requests page, and a stand-in title page that Select on a lockup opens and Back closes. */
    private fun show(source: RequestsSource, bar: BarContent = BarContent()) {
        rule.setContent {
            CompositionLocalProvider(LocalRecompositionProbe provides { name -> counts[name] = (counts[name] ?: 0) + 1 }) {
                val scope = rememberCoroutineScope()
                val model = remember { RequestsModel(source, scope) }
                var open by remember { mutableStateOf<TitleCard?>(null) }
                DpadHost {
                    val shown = open
                    if (shown == null) {
                        RequestsScreen(model, onOpen = { open = it; opened += it.key }, onBack = { left++ }, bar = bar)
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
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun shown(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    /** Select held for [ms] (a test's key input: its repeats come from 500 ms on, every 50 ms), then let go. */
    private fun hold(ms: Long) {
        rule.onRoot().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(ms)
            keyUp(Key.DirectionCenter)
        }
        rule.waitForIdle()
    }

    /**
     * One key through the real path, as a remote's comes (ahead of the on-screen keyboard): [repeat] and the times as
     * Android's input dispatcher sets them, its first repeat marked a long press. Times are counted from [t0].
     */
    private fun send(t0: Long, action: Int, at: Long, repeat: Int = 0, code: Int = KeyEvent.KEYCODE_DPAD_CENTER) {
        val flags = if (repeat == 1) KeyEvent.FLAG_LONG_PRESS else 0
        InstrumentationRegistry.getInstrumentation().sendKeySync(
            KeyEvent(t0, t0 + at, action, code, repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_DPAD),
        )
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "a node without a tag" } ?: "a node without a tag"
    }.getOrElse { "nothing (${it.message?.lineSequence()?.firstOrNull()})" }

    private fun assertFocused(tag: String, what: String = "") {
        runCatching { rule.waitUntil(3_000) { focusedTag() == tag } }
        val now = focusedTag()
        if (now != tag) throw AssertionError("${what}expected focus on $tag, it is on $now")
    }

    private fun row(id: Int) = "request request:$id"

    /** The words drawn under the node tagged [tag], every level down (tv-material's words sit deep). */
    private fun words(tag: String): String {
        fun collect(node: SemanticsNode): List<String> =
            node.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text } + node.children.flatMap(::collect)
        return collect(rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()).joinToString(" ")
    }

    /** An unfocused lockup's picture top, px (a focused one's bounds are its scaled size). */
    private fun topOf(tag: String): Float {
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        return rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
    }

    // ---- the split ---------------------------------------------------------------------------------------------------

    @Test
    fun downFromTheBarLandsOnMyRequestsAndRightGoesIntoTheGrid() {
        show(Source { _, _ -> page(12 downTo 3) })
        await(row(12))
        // shown on its own, the page's focus is on its side list's one category, ticked (rule 3: never a fill)
        assertFocused("side my requests")
        rule.onNode(hasTestTag("tick") and hasAnyAncestor(hasTestTag("side my requests")), useUnmergedTree = true).assertExists()
        rule.onAllNodesWithTag("requests heading").assertCountEquals(0)
        // Up from it is the bar, on Requests; Down from the bar lands on the chosen side row
        press(Key.DirectionUp)
        assertFocused("nav Requests")
        press(Key.DirectionDown)
        assertFocused("side my requests", "Down from the bar: ")
        // Right goes into the grid, on the newest; a line is four, fenced at its end
        press(Key.DirectionRight)
        assertFocused(row(12), "Right from the side list: ")
        rule.onNodeWithTag(row(12)).assert(showsFocus)
        val line = topOf(row(11))
        press(Key.DirectionRight, 3)
        assertFocused(row(9))
        press(Key.DirectionRight)
        assertFocused(row(9), "Right at a line's end: ")
        // Down a line: the focused line rests where the first one did, the grid moving under a still focus
        press(Key.DirectionDown)
        assertFocused(row(5))
        assertEquals("the second line came to the first line's place", line, topOf(row(6)), 2f)
        // Left along it, and Left from its first lockup back to the side list's chosen row
        press(Key.DirectionLeft, 3)
        assertFocused(row(8))
        press(Key.DirectionLeft)
        assertFocused("side my requests", "Left from the grid: ")
        // and Right comes back to the lockup left
        press(Key.DirectionRight)
        assertFocused(row(8), "Right again: ")
    }

    @Test
    fun selectOpensTheTitleAndBackReturnsToTheLockupLeft() {
        show(Source { _, _ -> page(12 downTo 3) })
        await(row(12))
        press(Key.DirectionRight, 2)
        press(Key.DirectionDown)
        assertFocused(row(7))
        press(Key.DirectionCenter)
        assertFocused("page movie:700")
        back()
        assertFocused(row(7))
        rule.onNodeWithTag(row(7)).assert(showsFocus)
        // Back from the page is its tab's (A2); Back on the bar, shown on its own, is the caller's
        back()
        assertFocused("nav Requests")
        assertEquals(0, left)
        back()
        assertEquals(1, left)
    }

    @Test
    fun whileTheFirstPageLoadsItsPlacesStandInAndMyRequestsKeepsFocus() {
        val first = CompletableDeferred<RequestPage>()
        show(Source { _, _ -> first.await() })
        await("loading")
        assertFocused("side my requests")
        // nothing to go into yet
        press(Key.DirectionRight)
        assertFocused("side my requests")
        first.complete(page(5 downTo 1))
        await(row(5))
        assertFocused("side my requests", "the lockups came: ")
        press(Key.DirectionRight)
        assertFocused(row(5))
    }

    @Test
    fun aFailureOffersTryAgainWhichKeepsFocusUntilItsAnswer() {
        var calls = 0
        val again = CompletableDeferred<RequestPage>()
        show(Source { _, _ -> if (++calls == 1) throw SeerrException.Http(500, null) else again.await() })
        await("try again")
        assertFocused("side my requests")
        press(Key.DirectionRight)
        assertFocused("try again")
        press(Key.DirectionCenter)
        rule.onNodeWithTag("try again").assertTextEquals("Trying…")
        assertFocused("try again")
        press(Key.DirectionCenter)
        assertEquals(2, calls)
        again.complete(page(3 downTo 1))
        await(row(3))
        assertFocused(row(3), "Try Again's answer: ")
    }

    @Test
    fun noRequestsSaysSoAndHoldsFocusOnTheWayBack() {
        show(Source { _, _ -> page(emptyList()) })
        await("requests empty")
        rule.onNodeWithTag("requests empty").assertTextEquals("No requests yet.")
        press(Key.DirectionRight)
        assertFocused("back to discover")
        press(Key.DirectionCenter)
        assertEquals(1, left)
    }

    @Test
    fun aLaterPageThatFailsEndsTheGridWithTryAgainThatHandsFocusOn() {
        val second = CompletableDeferred<RequestPage>()
        var failedOnce = false
        show(
            Source { skip, _ ->
                when {
                    skip == 0 -> page(20 downTo 11, total = 14)
                    !failedOnce -> {
                        failedOnce = true
                        throw SeerrException.Http(500, null)
                    }
                    else -> second.await()
                }
            },
        )
        await(row(20))
        // 20 19 18 17 / 16 15 14 13 / 12 11: focus on the sixth asks for the next page, which fails
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        assertFocused(row(15))
        await("retry more")
        press(Key.DirectionDown)
        assertFocused(row(11))
        press(Key.DirectionRight)
        Log.i("RequestsFocusTest", "after Right from 11: ${focusedTag()}")
        assertFocused("retry more")
        press(Key.DirectionCenter)
        rule.onNodeWithTag("retry more").assertExists()
        assertFocused("retry more")
        second.complete(page(10 downTo 7, total = 14))
        await(row(10))
        assertFocused(row(10), "the next page came: ")
    }

    @Test
    fun aMoveRecomposesTheTwoLockupsItTouches() {
        show(Source { _, _ -> page(12 downTo 3) })
        await(row(12))
        press(Key.DirectionRight)
        assertFocused(row(12))
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        for (from in 12 downTo 10) {
            counts.clear()
            press(Key.DirectionRight)
            rule.mainClock.advanceTimeBy(1_000)
            rule.waitForIdle()
            // the lockup's grid item, the lockup and its caption, by request id (a card's key is its title's: movie:<id>00)
            val probes = counts.keys.filter { it.startsWith("request item ") || it.startsWith("card view ") || it.startsWith("card caption ") }
            val ids = probes.map { name ->
                when {
                    name.startsWith("request item request:") -> name.removePrefix("request item request:").toInt()
                    else -> name.substringAfterLast("movie:").toInt() / 100
                }
            }.toSet()
            Log.i("RequestsFocusTest", "Right from request:$from recomposed $counts")
            val expected = setOf(from, from - 1)
            if (!expected.containsAll(ids)) throw AssertionError("Right from request:$from recomposed $ids (expected at most $expected): $counts")
            probes.forEach { name ->
                val n = counts.getValue(name)
                if (n > 4) throw AssertionError("$name recomposed $n times for one move: $counts")
            }
        }
    }

    // ---- the long press ------------------------------------------------------------------------------------------

    /** Twelve requests to 3; 12 and 10 wait for approval (Cancel Request is theirs), the rest are approved. */
    private fun withPending() = Source { _, _ -> page(12 downTo 3, pending = setOf(12, 10)) }

    @Test
    fun aLongPressOffersCancelRequestAndAShortPressDoesNot() {
        show(withPending())
        await(row(12))
        press(Key.DirectionRight)
        assertFocused(row(12))
        // a short press opens the title, and no menu
        press(Key.DirectionCenter)
        assertFocused("page movie:1200")
        assertTrue("a short press opened a menu", !shown("request menu"))
        back()
        assertFocused(row(12))
        // a long press opens the lockup's menu: Cancel Request, focused, in red words; nothing opened
        hold(1_000)
        assertEquals("the long press opened the title", listOf("movie:1200"), opened.toList())
        assertTrue("the long press opened no menu", runCatching { await("request menu") }.isSuccess)
        assertFocused("cancel request:12")
        // asked first, Keep Request focused; Cancel Request over it
        press(Key.DirectionCenter)
        await("confirm cancel request")
        assertFocused("keep request")
        assertTrue("nothing cancelled on asking", cancelled.isEmpty())
        press(Key.DirectionUp)
        assertFocused("confirm")
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { !shown(row(12)) }
        assertEquals(listOf(12), cancelled.toList())
        // the lockup went, focus moved first to the one that took its place
        assertFocused(row(11), "after cancelling: ")
        // an approved request has no menu: held, it opens its title once, on release
        hold(1_000)
        assertFocused("page movie:1100")
        assertEquals(listOf("movie:1200", "movie:1100"), opened.toList())
    }

    @Test
    fun aLongPressFiresNoClickAndAHeldSelectsRepeatsFireNothingMore() {
        show(withPending())
        await(row(12))
        press(Key.DirectionRight)
        assertFocused(row(12))
        // held two seconds through a test's key input: the menu at the first repeat; the forty repeats after it and the
        // release do nothing more (no title, no question)
        hold(2_000)
        assertTrue("the held key opened the title: $opened", opened.isEmpty())
        assertTrue("the held key opened no menu", runCatching { await("request menu") }.isSuccess)
        assertFocused("cancel request:12")
        assertTrue("the held key chose Cancel Request", !shown("confirm cancel request"))
        back()
        assertTrue("Back closed the menu", !shown("request menu"))
        assertFocused(row(12), "after the menu: ")
        // the same through the real path, as a remote's key comes: the menu opens as the long press repeats, and the
        // repeats and the release that follow reach its Cancel Request, which takes none of them
        val t0 = SystemClock.uptimeMillis() - 2_000
        send(t0, KeyEvent.ACTION_DOWN, at = 0)
        send(t0, KeyEvent.ACTION_DOWN, at = 500, repeat = 1)
        assertTrue("the real path's long press opened no menu", runCatching { await("request menu") }.isSuccess)
        assertFocused("cancel request:12", "the real path's long press: ")
        for (n in 2..20) send(t0, KeyEvent.ACTION_DOWN, at = 500L + (n - 1) * 50, repeat = n)
        send(t0, KeyEvent.ACTION_UP, at = 1_500)
        assertTrue("the real path's long press opened the title: $opened", opened.isEmpty())
        assertTrue("the held key chose Cancel Request", !shown("confirm cancel request"))
        assertFocused("cancel request:12")
        back()
        assertFocused(row(12))
        // a short press on the real path opens the title, once
        val t1 = SystemClock.uptimeMillis() - 200
        send(t1, KeyEvent.ACTION_DOWN, at = 0)
        send(t1, KeyEvent.ACTION_UP, at = 100)
        assertFocused("page movie:1200")
        assertEquals(listOf("movie:1200"), opened.toList())
        back()
        assertFocused(row(12))
        // the remote's Menu key (Fire TV's) opens the same menu
        press(Key.Menu)
        await("request menu")
        assertFocused("cancel request:12", "the Menu key: ")
        back()
        assertFocused(row(12))
        val t2 = SystemClock.uptimeMillis() - 200
        send(t2, KeyEvent.ACTION_DOWN, at = 0, code = KeyEvent.KEYCODE_MENU)
        send(t2, KeyEvent.ACTION_UP, at = 100, code = KeyEvent.KEYCODE_MENU)
        await("request menu")
        assertFocused("cancel request:12", "the real path's Menu key: ")
        assertTrue("nothing cancelled", cancelled.isEmpty())
    }

    // ---- Waiting for Approval ----------------------------------------------------------------------------------------

    @Test
    fun waitingForApprovalIsTheSideListsRowWithItsCountNotTheBars() {
        var opens = 0
        show(Source { _, _ -> page(5 downTo 1) }, bar = BarContent(waiting = { 3 }, onWaiting = { opens++ }))
        await(row(5))
        // a row of the side list, under My Requests, its count at its right; never under the bar's Requests
        rule.onNode(hasTestTag("waiting") and hasAnyAncestor(hasTestTag("side list")), useUnmergedTree = true).assertExists()
        rule.onAllNodes(hasTestTag("waiting") and hasAnyAncestor(hasTestTag("bar")), useUnmergedTree = true).assertCountEquals(0)
        assertEquals("Waiting for Approval 3", words("waiting"))
        assertFocused("side my requests")
        press(Key.DirectionDown)
        assertFocused("waiting")
        press(Key.DirectionDown)
        assertFocused("waiting", "the side list's end: ")
        press(Key.DirectionCenter)
        assertEquals("Select opens everyone's requests", 1, opens)
        press(Key.DirectionUp)
        assertFocused("side my requests")
    }

    @Test
    fun forAnAccountThatApprovesItselfTheWaitingCountIsWordsOnly() {
        show(Source { _, _ -> page(5 downTo 1) }, bar = BarContent(waiting = { 2 }))
        await(row(5))
        await("waiting")
        assertEquals("Waiting for Approval 2", words("waiting"))
        assertFocused("side my requests")
        press(Key.DirectionDown)
        assertFocused("side my requests", "Down to words only: ")
    }

    // ---- through the whole home --------------------------------------------------------------------

    private val session = Session(User(id = 8, displayName = "TV", permissions = 67744), PublicSettings(applicationTitle = "Seerr", localLogin = true, movie4kEnabled = true))

    /**
     * A server in memory: one Discover row of films 1..8, their pages, the requests list (the
     * user's own, newest first, as asked with `requestedBy`), and `POST /request`, which adds to it.
     */
    private inner class Server {
        val made = CopyOnWriteArrayList<MediaRequest>(listOf(request(2, tmdbId = 2), request(1, tmdbId = 1)))
        val askedBy = CopyOnWriteArrayList<String?>()

        private fun reply(data: HttpRequestData): String {
            val json = SeerrClient.json
            val path = data.url.encodedPath
            return when {
                path == "/api/v1/settings/public" -> json.encodeToString(PublicSettings.serializer(), session.settings)
                path == "/api/v1/auth/me" -> json.encodeToString(User.serializer(), session.user)
                path == "/api/v1/request" && data.method == HttpMethod.Post -> {
                    val body = json.decodeFromString(RequestBody.serializer(), (data.body as TextContent).text)
                    val created = request(made.maxOf { it.id } + 1, tmdbId = body.mediaId)
                    made.add(0, created)
                    json.encodeToString(MediaRequest.serializer(), created)
                }
                path == "/api/v1/request" -> {
                    askedBy += data.url.parameters["requestedBy"]
                    json.encodeToString(RequestPage.serializer(), RequestPage(PageInfo(results = made.size), made.toList()))
                }
                path.startsWith("/api/v1/movie/") -> {
                    val id = path.substringAfterLast('/').toInt()
                    val record = made.firstOrNull { it.media?.tmdbId == id }?.media
                    json.encodeToString(MovieDetails.serializer(), MovieDetails(id = id, title = "Film $id", overview = "About it.", mediaInfo = record))
                }
                else -> json.encodeToString(
                    Page.serializer(MediaResult.serializer()),
                    Page(results = (1..8).map { MediaResult(id = it, mediaType = "movie", title = "Film $it") }),
                )
            }
        }

        val client = SeerrClient(
            MockEngine { data ->
                val status = if (data.method == HttpMethod.Post) HttpStatusCode.Created else HttpStatusCode.OK
                respond(reply(data), status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
            "http://seerr.test",
            AcceptAllCookiesStorage(),
        )
    }

    @Test
    fun theBarOpensRequestsAndARequestMadeInTheModalIsThereNextTime() {
        val server = Server()
        val access = object : SeerrAccess {
            override suspend fun <T> call(call: suspend (SeerrClient) -> T): T = call(server.client)
        }
        rule.setContent { DpadHost { HomeScreen(Screen.Home(session), access, onSignOut = {}) } }
        await("card movie:1")
        // Recent Requests heads Discover with the user's requests, and has focus (DECISIONS 84)
        await("recent request:2")
        assertFocused("recent request:2")
        // focus on the bar's Requests shows the page (A2); Down lands on My Requests, Right on the newest request
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        assertFocused("nav Requests")
        await(row(2))
        press(Key.DirectionDown)
        assertFocused("side my requests")
        press(Key.DirectionRight)
        assertFocused(row(2))
        // Recent Requests asked for no one, as Seerr's slider (the server's rights decide, DECISIONS 118); the
        // page asked for the user's own, whatever the account may see
        assertEquals("who each read asked for, in order", listOf(null, "8"), server.askedBy.toList())
        // Back to its tab, then Back to Discover's (A2)
        back()
        assertFocused("nav Requests")
        back()
        assertFocused("nav Discover")
        // a request for film 5 from its page: down past Recent Requests to Trending
        press(Key.DirectionDown)
        assertFocused("recent request:2")
        press(Key.DirectionDown)
        press(Key.DirectionRight, 4)
        assertFocused("card movie:5")
        press(Key.DirectionCenter)
        await("action Request")
        assertFocused("action Request")
        press(Key.DirectionCenter)
        await("primary")
        assertFocused("primary")
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { server.made.size == 3 }
        back()
        assertFocused("card movie:5")
        // no restart: the next time the page opens, the new request is first (Up from Trending reaches Recent
        // Requests, and Up from there the bar's Discover)
        press(Key.DirectionUp)
        press(Key.DirectionUp)
        press(Key.DirectionLeft, 3)
        press(Key.DirectionRight)
        assertFocused("nav Requests")
        await(row(3))
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        assertFocused(row(3))
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("card title", useUnmergedTree = true).fetchSemanticsNodes().size >= 3 }
        // Select opens the title; Back comes back to the lockup
        press(Key.DirectionCenter)
        await("media title")
        rule.onNodeWithTag("media title").assertExists()
        back()
        assertFocused(row(3))
    }
}
