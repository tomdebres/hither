package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
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
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.model.DiscoverSlider
import com.tomdebres.hither.core.model.IssuePage
import com.tomdebres.hither.core.model.LibraryPage
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PageInfo
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.RequestPage
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.SliderType
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.home.HomeScreen
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Back from a page the bar opened comes back to the control that opened it (DECISIONS 64, 66, 106, 49; the polish 2
 * audit's item 1), and Back on another tab comes to Discover's (A2). Set up as the family's TV is: the server's Discover settings put Recently Added
 * first (so the plan changes the rows as Discover starts), and Recent Requests holds the household's requests,
 * read again, slowly, each time Discover is come back to. The whole home, through the real client.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class BarBackTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val base = "http://seerr.test"

    /** The family's rights on Tom's server: Recent View, View and Create Issues, View Requests (audit). */
    private val rights = 73484448
    private val session = Session(User(id = 8, displayName = "TV", permissions = rights), PublicSettings(applicationTitle = "Seerr", localLogin = true))
    private val requestReads = AtomicInteger()

    private fun request(id: Int, by: Int) = MediaRequest(
        id = id,
        status = RequestStatus.PENDING,
        type = "movie",
        createdAt = "2026-11-12T2$id:00:00.000Z",
        media = MediaInfo(mediaType = "movie", tmdbId = 900 + id, status = 2),
        requestedBy = User(id = by, displayName = "User $by"),
    )

    private fun show() {
        val json = SeerrClient.json
        val sliders = listOf(
            SliderType.RECENTLY_ADDED, SliderType.RECENT_REQUESTS, SliderType.TRENDING,
            SliderType.POPULAR_MOVIES, SliderType.POPULAR_TV,
        ).mapIndexed { order, type -> DiscoverSlider(id = order + 1, type = type, order = order, isBuiltIn = true) }
        val client = SeerrClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                val body = when {
                    path.startsWith("/api/v1/movie/") -> json.encodeToString(
                        MovieDetails.serializer(),
                        MovieDetails(id = path.substringAfterLast('/').toInt(), title = "Film ${path.substringAfterLast('/')}"),
                    )
                    path == "/api/v1/settings/public" -> json.encodeToString(PublicSettings.serializer(), session.settings)
                    path == "/api/v1/settings/discover" -> json.encodeToString(ListSerializer(DiscoverSlider.serializer()), sliders)
                    path == "/api/v1/auth/me" -> json.encodeToString(User.serializer(), session.user)
                    path == "/api/v1/media" -> json.encodeToString(
                        LibraryPage.serializer(),
                        LibraryPage(PageInfo(results = 3), (1..3).map { MediaInfo(mediaType = "movie", tmdbId = 800 + it, status = 5) }),
                    )
                    path == "/api/v1/request" -> {
                        // Recent Requests, and My Requests: the household's, each re-read slow (as on the Shield)
                        if (requestReads.getAndIncrement() > 0) delay(1_500)
                        json.encodeToString(RequestPage.serializer(), RequestPage(PageInfo(results = 3), listOf(request(3, 21), request(2, 22), request(1, 8))))
                    }
                    path == "/api/v1/issue" -> json.encodeToString(IssuePage.serializer(), IssuePage())
                    else -> json.encodeToString(
                        Page.serializer(MediaResult.serializer()),
                        Page(page = 1, totalPages = 1, results = (1..8).map { MediaResult(id = path.length * 10 + it, mediaType = "movie", title = "Film") }),
                    )
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
            base,
            AcceptAllCookiesStorage(),
        )
        val access = object : SeerrAccess {
            override suspend fun <T> call(call: suspend (SeerrClient) -> T): T = call(client)
        }
        rule.setContent { DpadHost { HomeScreen(Screen.Home(session), access, onSignOut = {}) } }
        // Recently Added (first, from the plan) and Recent Requests are in
        await("recent request:3")
        await("card movie:801")
        settle()
    }

    /** Waits for [tag]; a timeout names the cards there were instead. */
    private fun await(tag: String) {
        val there = { rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        runCatching { rule.waitUntil(15_000) { there() } }
        if (!there()) {
            val tags = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag), useUnmergedTree = true)
                .fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }.filter { it.startsWith("card") || it.startsWith("recent") || it.startsWith("row") }
            throw AssertionError("no $tag; ${requestReads.get()} request reads, focus on ${focusedTag()}; there were ${tags.take(12)}")
        }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun settle(ms: Long = 1_000) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "a node without a tag" } ?: "a node without a tag"
    }.getOrElse { "nothing" }

    /**
     * Back from the page [rightPresses] along the bar from Discover's tab ([page], opened by Select on [control]): focus is
     * on [control] on the tab page it was opened on, and stays there (search and Settings, DECISIONS 129).
     */
    private fun backFromThePageReturnsToItsControl(rightPresses: Int, control: String, page: String, under: String) {
        toDiscoversTab()
        press(Key.DirectionRight, rightPresses)
        check(focusedTag() == control) { "the walk reached ${focusedTag()}, not $control" }
        press(Key.DirectionCenter)
        await(page)
        settle()
        // search opens with its field editing: the first Back closes the keyboard, the next leaves (64)
        repeat(if (page == "search field") 2 else 1) { back() }
        settle()
        await(under)
        val first = focusedTag()
        Thread.sleep(1_800)
        settle(2_000)
        val after = focusedTag()
        if (first != control || after != control) {
            throw AssertionError("Back from $page: expected focus on $control on $under, it was on $first, then on $after")
        }
    }

    /** From the rows (or wherever focus is), to Discover's tab: Up from the top row, or Back on another tab. */
    private fun toDiscoversTab() {
        repeat(4) {
            when {
                focusedTag() == "nav Discover" -> return
                focusedTag().startsWith("nav ") || focusedTag() == "search" || focusedTag() == "avatar" -> back()
                else -> press(Key.DirectionUp)
            }
            settle()
        }
        check(focusedTag() == "nav Discover") { "not on Discover's tab: ${focusedTag()}" }
    }

    /**
     * Back on a tab page's bar (A2): to Discover's tab, and its page; focus stays there once Recent Requests' slow re-read
     * has come (the polish 2 audit's item 1: Discover's arrival must not take focus from the bar).
     */
    private fun backOnTheTabComesToDiscoversTab(rightPresses: Int, tab: String, page: String) {
        toDiscoversTab()
        press(Key.DirectionRight, rightPresses)
        check(focusedTag() == tab) { "the walk reached ${focusedTag()}, not $tab" }
        await(page)
        settle()
        val readsBefore = requestReads.get()
        back()
        settle()
        val first = focusedTag()
        runCatching { rule.waitUntil(5_000) { requestReads.get() > readsBefore } }
        val reread = requestReads.get() > readsBefore
        Thread.sleep(1_800)
        settle(2_000)
        val after = focusedTag()
        if (first != "nav Discover" || after != "nav Discover") {
            throw AssertionError("Back on $tab: expected focus on nav Discover, it was on $first, then on $after once Recent Requests was read again")
        }
        check(reread) { "Back on $tab: Recent Requests was not read again" }
    }

    /**
     * Every way off Discover's tab, one after the other in one home (as the family moves between them), in the bar's
     * order (DECISIONS 151): Requests' and Issues' tabs (focus shows their pages, A2), Back on each to Discover's tab; then
     * search and Settings, opened on the Issues page as the walk passes it, Back to their control there.
     */
    @Test
    fun backFromEachBarPageReturnsToItsControl() {
        show()
        backOnTheTabComesToDiscoversTab(1, "nav Requests", "requests page")
        backOnTheTabComesToDiscoversTab(2, "nav Issues", "issues page")
        backFromThePageReturnsToItsControl(3, "search", "search field", "issues page")
        backFromThePageReturnsToItsControl(4, "avatar", "account name", "issues page")
    }
}
