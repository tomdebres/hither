package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.model.Issue
import com.tomdebres.hither.core.model.IssuePage
import com.tomdebres.hither.core.model.IssueType
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PageInfo
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.RequestPage
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.ready.Front
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.components.LocalRecompositionProbe
import com.tomdebres.hither.ui.components.ShowsFocus
import com.tomdebres.hither.ui.home.HomeScreen
import com.tomdebres.hither.ui.home.HomeSide
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
import kotlin.math.roundToInt

/**
 * The bar as the Apple TV app's (Tom's Q2, A2; DECISIONS 312-316), through the whole home against a scripted server:
 * centred tv-material tabs (Discover, Requests, Issues) and search and the account as icons; **selection follows focus**
 * (moving along the bar shows each page, focus staying on the bar); **Back's rules** (from a page's content to its tab,
 * from another tab to Discover's, from Discover's out); **Down from the bar** lands on the page's first item; **the bar
 * scrolls away** with the page after a Down from its top row and comes back on Up; the current tab said by its words,
 * **never a fill**; the order and the count's circle (the waiting count is My Requests' side row since A6); search and
 * Settings coming back to the page they were opened on.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TabsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var permissions = Permission.REQUEST or Permission.VIEW_ISSUES
    private val user get() = User(id = 8, displayName = "TV", permissions = permissions)
    private val session get() = Session(user, PublicSettings(applicationTitle = "Seerr", localLogin = true))
    private val counts = HashMap<String, Int>()

    private fun request(id: Int) = MediaRequest(
        id = id,
        status = RequestStatus.APPROVED,
        type = "movie",
        createdAt = "2026-11-10T09:00:00.000Z",
        media = MediaInfo(mediaType = "movie", tmdbId = id, status = 3),
    )

    private val access = object : SeerrAccess {
        private val client = SeerrClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                val json = SeerrClient.json
                val body = when {
                    path == "/api/v1/auth/me" -> json.encodeToString(User.serializer(), user)
                    // two open issues
                    path == "/api/v1/issue" -> json.encodeToString(
                        IssuePage.serializer(),
                        IssuePage(PageInfo(results = 2), (1..2).map { Issue(id = it, issueType = IssueType.AUDIO, media = MediaInfo(mediaType = "movie", tmdbId = it)) }),
                    )
                    // the user's six requests, newest first, on My Requests (two lines of lockups, A6); Recent Requests, which
                    // asks for no one, the newest two
                    path == "/api/v1/request" -> {
                        val mine = request.url.parameters["requestedBy"] != null
                        val ids = if (mine) (6 downTo 1).toList() else listOf(2, 1)
                        json.encodeToString(RequestPage.serializer(), RequestPage(PageInfo(results = ids.size), ids.map(::request)))
                    }
                    path.startsWith("/api/v1/movie/") -> json.encodeToString(MovieDetails.serializer(), MovieDetails(id = path.substringAfterLast('/').toIntOrNull() ?: 0, title = "Film"))
                    // Every row: twenty films, 1 to 20.
                    else -> json.encodeToString(
                        Page.serializer(MediaResult.serializer()),
                        Page(results = (1..20).map { MediaResult(id = it, mediaType = "movie", title = "Film $it") }),
                    )
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
            "http://seerr.test",
            AcceptAllCookiesStorage(),
        )

        override suspend fun <T> call(call: suspend (SeerrClient) -> T): T = call(client)
    }

    /** Where focus arrived when Discover first came up: the page's first item. */
    private var arrived = ""

    private fun show(count: Int = 0, waiting: Int? = null, probe: Boolean = false) {
        val side = HomeSide(front = { Front(emptyList(), requestsCount = count, waiting = waiting) })
        rule.setContent {
            CompositionLocalProvider(LocalRecompositionProbe provides if (probe) { name -> counts[name] = (counts[name] ?: 0) + 1 } else null) {
                DpadHost { HomeScreen(Screen.Home(session), access, onSignOut = {}, side = side) }
            }
        }
        await("card movie:1")
        // The page's top is Recent Requests. Arrival puts focus on its placeholder, and Discover's hand-off to its cards
        // sometimes misses (a race in Discover's own rows, before A2; HANDOVER, A3), leaving focus on Trending under it:
        // these tests start from the top row, so one Up takes it there.
        await("recent request:2")
        runCatching { rule.waitUntil(5_000) { settle(); focusedTag().startsWith("recent request") || focusedTag().startsWith("card ") } }
        if (focusedTag().startsWith("card ")) press(Key.DirectionUp)
        assertFocused("recent request:2", "the page's top row: ")
        if (count > 0) await("requests count")
        settle()
        arrived = focusedTag()
    }

    private fun await(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onRoot().performKeyInput { pressKey(key) }
        settle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "a node without a tag" } ?: "a node without a tag"
    }.getOrElse { "nothing" }

    private fun assertFocused(tag: String, what: String = "") {
        runCatching { rule.waitUntil(3_000) { focusedTag() == tag } }
        val now = focusedTag()
        if (now != tag) throw AssertionError("${what}expected focus on $tag, it is on $now")
    }

    private fun node(tag: String): SemanticsNode = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()

    private fun px(tag: String): Rect = node(tag).boundsInRoot

    private fun dp(px: Float) = px / rule.density.density

    private fun shows(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Each tab page's own marker. */
    private val pages = mapOf("Discover" to "rows", "Requests" to "requests page", "Issues" to "issues page")

    private fun assertShows(tab: String, what: String) {
        runCatching { rule.waitUntil(3_000) { shows(pages.getValue(tab)) } }
        for ((other, marker) in pages) {
            assertEquals("$what: ${other}'s page showing", other == tab, shows(marker))
        }
    }

    private fun onBar(tag: String = focusedTag()) = tag.startsWith("nav ") || tag == "search" || tag == "avatar"

    /** From a page, Up until focus is on the bar, at most four times. */
    private fun upToTheBar() {
        repeat(4) {
            if (onBar()) return
            press(Key.DirectionUp)
        }
    }

    /** From the page or the bar, along the bar to [tab]'s: its page shows, with no Select. */
    private fun toTab(tab: String) {
        upToTheBar()
        repeat(3) { if (focusedTag() == "search" || focusedTag() == "avatar") press(Key.DirectionLeft) }
        val order = listOf("nav Discover", "nav Requests", "nav Issues")
        val from = order.indexOf(focusedTag())
        check(from >= 0) { "not on a tab: ${focusedTag()}" }
        val to = order.indexOf("nav $tab")
        if (to > from) press(Key.DirectionRight, to - from) else if (to < from) press(Key.DirectionLeft, from - to)
        assertFocused("nav $tab", "walking the bar to $tab: ")
        assertShows(tab, "on $tab's tab")
    }

    /** The bar's top (its line of controls) on the screen, in dp: negative once it has scrolled away. */
    private fun barTop() = dp(node("bar").positionInRoot.y)

    private fun barBottom() = dp(node("bar").let { it.positionInRoot.y + it.size.height })

    @Test
    fun rightAlongTheBarShowsEachPage() {
        show()
        press(Key.DirectionUp)
        assertFocused("nav Discover", "Up from Discover's top row: ")
        assertShows("Discover", "on Discover's tab")
        press(Key.DirectionRight)
        assertFocused("nav Requests", "Right from Discover: ")
        assertShows("Requests", "Right to Requests")
        await("request request:2")
        // the page came up and left focus on the bar
        assertFocused("nav Requests", "Requests' page arrived: ")
        rule.onNodeWithTag("nav Requests").assertIsSelected()
        press(Key.DirectionRight)
        assertFocused("nav Issues", "Right from Requests: ")
        assertShows("Issues", "Right to Issues")
        await("issue:1")
        assertFocused("nav Issues", "the Issues page arrived: ")
        rule.onNodeWithTag("nav Issues").assertIsSelected()
        rule.onNodeWithTag("nav Requests").assertIsNotSelected()
        // search and the account open on Select only: walking onto them keeps the page
        press(Key.DirectionRight)
        assertFocused("search")
        assertShows("Issues", "on search")
        press(Key.DirectionRight)
        assertFocused("avatar")
        assertShows("Issues", "on the account")
        // and back along the bar
        press(Key.DirectionLeft, 3)
        assertFocused("nav Requests")
        assertShows("Requests", "Left to Requests")
        press(Key.DirectionLeft)
        assertFocused("nav Discover")
        assertShows("Discover", "Left to Discover")
    }

    @Test
    fun backFromContentLandsOnItsTab() {
        show()
        // Requests: from its second row, Back is its tab, the page still Requests'; Back again is Discover's tab
        toTab("Requests")
        press(Key.DirectionDown)
        assertFocused("side my requests")
        press(Key.DirectionRight)
        assertFocused("request request:6")
        press(Key.DirectionDown)
        assertFocused("request request:2")
        back()
        assertFocused("nav Requests", "Back from Requests' lockups: ")
        assertShows("Requests", "Back from Requests' lockups")
        back()
        assertFocused("nav Discover", "Back on Requests' tab: ")
        assertShows("Discover", "Back on Requests' tab")
        // Issues: from its first issue, Back is its tab; Back again is Discover's
        toTab("Issues")
        press(Key.DirectionDown)
        assertFocused("issue filter open")
        press(Key.DirectionRight)
        assertFocused("issue:1")
        back()
        assertFocused("nav Issues", "Back from the issues: ")
        assertShows("Issues", "Back from the issues")
        back()
        assertFocused("nav Discover", "Back on Issues' tab: ")
        assertShows("Discover", "Back on Issues' tab")
        // Discover: from a lower row, Back is its tab with the page at its top
        press(Key.DirectionDown, 2)
        assertTrue("two rows down, focus on ${focusedTag()}", focusedTag().startsWith("card "))
        back()
        assertFocused("nav Discover", "Back from Discover's rows: ")
        assertTrue("the bar is back at the top: ${barTop()} dp", barTop() >= 0f)
        // and on Discover's tab nothing takes Back: Hither leaves (Android TV's top navigation)
        val taken = rule.runOnIdle { rule.activity.onBackPressedDispatcher.hasEnabledCallbacks() }
        assertTrue("Back on Discover's tab is taken by the app", !taken)
    }

    @Test
    fun downFromTheBarLandsOnThePagesFirstItem() {
        show()
        assertTrue("arriving, focus is in the rows: $arrived", arrived.startsWith("card ") || arrived.startsWith("recent request"))
        press(Key.DirectionUp)
        assertFocused("nav Discover")
        press(Key.DirectionDown)
        assertFocused(arrived, "Down from Discover's tab: ")
        toTab("Requests")
        press(Key.DirectionDown)
        assertFocused("side my requests", "Down from Requests' tab: ")
        toTab("Issues")
        press(Key.DirectionDown)
        assertFocused("issue filter open", "Down from Issues' tab: ")
    }

    @Test
    fun theBarGoesAfterADownFromTheTopRowAndComesBackOnUp() {
        show()
        val top = barTop()
        assertEquals("the bar's line at the top margin, in dp", 27f, top, 1.5f)
        // Discover: Down from the top row takes the bar away with the page
        press(Key.DirectionDown)
        assertTrue("after a Down from Discover's top row, the bar ends at ${barBottom()} dp", barBottom() <= 0f)
        // and the band where it was shows the row above, drawn (DECISIONS 90's cull went with the solid bar)
        val map = rule.onRoot().captureToImage().toPixelMap()
        val band = (0 until (60 * rule.density.density).roundToInt()).sumOf { y -> (0 until map.width step 4).count { x -> lit(map, x, y) } }
        assertTrue("the band the bar left draws the row above: $band lit pixels", band > 50)
        press(Key.DirectionUp)
        assertEquals("Up to the top row brings the bar back, in dp", top, barTop(), 1.5f)
        press(Key.DirectionUp)
        assertFocused("nav Discover")
        // Requests: the same, a line of lockups at a time (A6: the side list stays)
        toTab("Requests")
        assertEquals("on Requests, the bar at the top", top, barTop(), 1.5f)
        press(Key.DirectionDown)
        assertFocused("side my requests")
        press(Key.DirectionRight)
        assertFocused("request request:6")
        assertEquals("on Requests' top line, the bar still there", top, barTop(), 1.5f)
        press(Key.DirectionDown)
        assertFocused("request request:2")
        assertTrue("after a Down from Requests' top line, the bar ends at ${barBottom()} dp", barBottom() <= 0f)
        press(Key.DirectionUp)
        assertFocused("request request:6")
        assertEquals("Up to Requests' top line brings the bar back", top, barTop(), 1.5f)
    }

    private fun lit(map: PixelMap, x: Int, y: Int): Boolean = map[x, y].let { it.red + it.green + it.blue > 0.3f }

    /**
     * Rule 3: the current page's tab is said by its words, never a fill. tv-material's `TabRow` draws a pill behind the
     * selected tab, which `ThemeRulesTest` cannot see in the sources; here, with focus in each page, nothing but the
     * words is drawn in the current tab's box. With focus on the tab, the pill is white (focus fills, nothing else).
     */
    @Test
    fun theCurrentTabIsNotAFill() {
        show()
        for (tab in listOf("Discover", "Requests", "Issues")) {
            if (tab != "Discover") {
                toTab(tab)
                // focus on the tab: the white pill, black words
                val map = rule.onRoot().captureToImage().toPixelMap()
                val box = px("nav $tab")
                val ground = map[(box.left + 3 * rule.density.density).roundToInt(), box.center.y.roundToInt()]
                assertTrue("$tab focused: the pill is white, was $ground", ground.red > 0.9f && ground.green > 0.9f && ground.blue > 0.9f)
                press(Key.DirectionDown)
            }
            assertTrue("$tab: focus left the bar (on ${focusedTag()})", !onBar())
            rule.onNodeWithTag("nav $tab").assertIsSelected()
            val map = rule.onRoot().captureToImage().toPixelMap()
            val box = px("nav $tab")
            val words = rule.onNode(hasText(tab) and hasAnyAncestor(hasTestTag("nav $tab")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            var filled = 0
            var white = 0
            for (x in box.left.roundToInt() until box.right.roundToInt()) for (y in box.top.roundToInt() until box.bottom.roundToInt()) {
                val c = map[x, y]
                val inWords = x >= words.left - 1 && x <= words.right + 1 && y >= words.top - 1 && y <= words.bottom + 1
                if (inWords) {
                    if (c.red > 0.9f && c.green > 0.9f && c.blue > 0.9f) white++
                } else if (c.red + c.green + c.blue > 0.06f) {
                    filled++
                }
            }
            assertTrue("$tab, current, focus in the page: $filled pixels drawn round its words (a fill)", filled == 0)
            assertTrue("$tab, current: its words are white ($white pixels)", white > 20)
            // the other tabs' words grey, not white
            for (other in pages.keys - tab) {
                if (!shows("nav $other")) continue
                val o = rule.onNode(hasText(other) and hasAnyAncestor(hasTestTag("nav $other")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val whiteThere = (o.left.roundToInt() until o.right.roundToInt()).sumOf { x -> (o.top.roundToInt() until o.bottom.roundToInt()).count { y -> map[x, y].let { it.red > 0.9f && it.green > 0.9f && it.blue > 0.9f } } }
                assertTrue("$tab current: $other's words are white ($whiteThere pixels)", whiteThere <= 2)
            }
            upToTheBar()
        }
    }

    /** Switching tabs builds no history: Requests, Issues, Requests, Issues, then Back twice comes to Discover's tab. */
    @Test
    fun switchingBetweenRequestsAndIssuesBuildsNoHistory() {
        show()
        toTab("Requests")
        toTab("Issues")
        toTab("Requests")
        toTab("Issues")
        press(Key.DirectionDown)
        assertFocused("issue filter open")
        back()
        assertFocused("nav Issues")
        back()
        assertFocused("nav Discover", "Back on Issues' tab: ")
        assertShows("Discover", "Back on Issues' tab, not Requests")
    }

    @Test
    fun upFromAPagesTopReachesTheCurrentTab() {
        show()
        press(Key.DirectionUp)
        assertFocused("nav Discover", "Up from Discover's top row: ")
        toTab("Requests")
        press(Key.DirectionDown)
        assertFocused("side my requests")
        press(Key.DirectionUp)
        assertFocused("nav Requests", "Up from My Requests: ")
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        assertFocused("request request:6")
        // from a lower line, Up walks the lines first
        press(Key.DirectionDown)
        assertFocused("request request:2")
        press(Key.DirectionUp, 2)
        assertFocused("nav Requests", "Up from Requests' second line, twice: ")
        toTab("Issues")
        // Issues: the chosen filter, the first issue, then the bar from either
        press(Key.DirectionDown)
        assertFocused("issue filter open")
        press(Key.DirectionRight)
        assertFocused("issue:1")
        press(Key.DirectionUp)
        assertFocused("nav Issues", "Up from Issues' top line: ")
        press(Key.DirectionDown)
        assertFocused("issue filter open")
        press(Key.DirectionUp)
        assertFocused("nav Issues", "Up from Issues' filters: ")
    }

    /** Centred, in Tom's order (151), 8 dp apart; Left and Right walk it from end to end. */
    @Test
    fun theBarIsCentredAndReadsDiscoverRequestsIssuesSearchAccountOnEveryTabPage() {
        show()
        val order = listOf("nav Discover", "nav Requests", "nav Issues", "search", "avatar")
        for (tab in listOf("Discover", "Requests", "Issues")) {
            if (tab != "Discover") toTab(tab)
            val box = order.associateWith { px(it) }
            assertEquals("$tab: the bar, left to right", order, order.sortedBy { box.getValue(it).left })
            val left = box.getValue("nav Discover").left
            val right = box.getValue("avatar").right
            val width = rule.onRoot().fetchSemanticsNode().size.width
            assertEquals("$tab: the bar centred, its middle in dp", dp(width / 2f), dp((left + right) / 2f), 1.5f)
            assertEquals("$tab: from Issues to search, in dp", 8f, dp(box.getValue("search").left - box.getValue("nav Issues").right), 1f)
            assertEquals("$tab: from search to the account, in dp", 8f, dp(box.getValue("avatar").left - box.getValue("search").right), 1f)
        }
        upToTheBar()
        assertFocused("nav Issues")
        press(Key.DirectionLeft, 3)
        assertFocused("nav Discover")
        for (tag in order.drop(1)) {
            press(Key.DirectionRight)
            assertFocused(tag)
        }
        press(Key.DirectionRight)
        assertFocused("avatar")
    }

    /** Without an issue right there is no Issues tab and no gap where it was (106, 151). */
    @Test
    fun withoutAnIssueRightTheBarReadsDiscoverRequestsSearchAccount() {
        permissions = Permission.REQUEST
        show()
        toTab("Requests")
        assertTrue("an Issues tab without the right", !shows("nav Issues"))
        val order = listOf("nav Discover", "nav Requests", "search", "avatar")
        val box = order.associateWith { px(it) }
        assertEquals("the bar, left to right", order, order.sortedBy { box.getValue(it).left })
        assertEquals("from Requests to search, in dp", 8f, dp(box.getValue("search").left - box.getValue("nav Requests").right), 1f)
    }

    /** No icon in any tab, words only (Tom, 2026-11-14, DECISIONS 150), starting 16 dp in; search and the account are icons. */
    @Test
    fun theTabsAreWordsAndSearchAndTheAccountAreIcons() {
        show()
        for (page in listOf("Discover", "Requests", "Issues")) {
            if (page != "Discover") toTab(page)
            for (tab in listOf("Discover", "Requests", "Issues")) {
                val box = px("nav $tab")
                val words = rule.onNode(hasText(tab) and hasAnyAncestor(hasTestTag("nav $tab")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertEquals("$page: from $tab's start to its words, in dp", 16f, dp(words.left - box.left), 0.5f)
            }
            for (icon in listOf("search", "avatar")) {
                assertEquals("$page: $icon has words", 0, rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and hasAnyAncestor(hasTestTag(icon)), useUnmergedTree = true).fetchSemanticsNodes().size)
                assertEquals("$page: $icon is a circle, in dp", dp(px(icon).height), dp(px(icon).width), 0.5f)
            }
        }
        rule.onNode(hasTestTag("user glyph") and hasAnyAncestor(hasTestTag("avatar")), useUnmergedTree = true).fetchSemanticsNode()
    }

    /**
     * The count keeps its circle (146) on the Requests tab, white and dark on the focused tab's white pill (tvOS's 184).
     * "N waiting for approval" is no longer hung under Requests (A6): it is My Requests' side row, on its page only. (My
     * Requests opened clears the count, so its own page shows none for long.)
     */
    @Test
    fun theCountsCircleIsOnTheBarAndTheWaitingCountOnMyRequests() {
        show(count = 2, waiting = 3)
        toTab("Issues")
        await("requests count")
        var map = rule.onRoot().captureToImage().toPixelMap()
        val b = drawn(map, px("requests count"), white = true)
        assertEquals("one digit, the count's drawn width equals its height, in dp", dp(b.height), dp(b.width), 0.5f)
        assertEquals("the count's drawn height, in dp", 18f, dp(b.height), 0.5f)
        val inset = 1.5f * rule.density.density
        assertTrue("the count's top-left corner is the badge's, not round", !badge(map, b.left + inset - 0.5f, b.top + inset - 0.5f, white = true))
        // nothing hangs under the bar's Requests on the Issues page
        assertTrue("the waiting count on the Issues page", !shows("waiting"))
        // on the focused Requests tab, the circle is dark on the white pill
        press(Key.DirectionLeft)
        assertFocused("nav Requests")
        map = rule.onRoot().captureToImage().toPixelMap()
        val c = px("requests count")
        val middle = map[(c.center.x - 4 * rule.density.density).roundToInt(), c.center.y.roundToInt()]
        assertTrue("the count on the focused tab is dark, was $middle", middle.red < 0.2f && middle.green < 0.2f && middle.blue < 0.2f)
        // on My Requests, the side list's second row: the count in words (this account approves itself), no stop
        await("waiting")
        assertTrue("the waiting row in the side list", rule.onAllNodes(hasTestTag("waiting") and hasAnyAncestor(hasTestTag("side list")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("the waiting count under the bar", rule.onAllNodes(hasTestTag("waiting") and hasAnyAncestor(hasTestTag("bar")), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    /** Whether the pixel is the count's fill (white at rest, black on the focused tab), not the ground round it. */
    private fun badge(map: PixelMap, x: Float, y: Float, white: Boolean): Boolean =
        map[x.roundToInt(), y.roundToInt()].green.let { if (white) it * 255 > 150 else it * 255 < 60 }

    /** The count's badge as drawn, from the middle of its node out to its last pixels, stepping over the numerals. */
    private fun drawn(map: PixelMap, inner: Rect, white: Boolean): Rect {
        val gap = 3f * rule.density.density
        fun reach(from: Float, step: Float, at: (Float) -> Boolean): Float {
            var last = from
            var p = from
            while (kotlin.math.abs(p - last) <= gap) {
                p += step
                if (at(p)) last = p
            }
            return last
        }
        val cx = inner.center.x
        val cy = inner.center.y
        val l = reach(cx, -1f) { badge(map, it, cy, white) }
        val r = reach(cx, 1f) { badge(map, it, cy, white) }
        val t = reach(cy, -1f) { badge(map, cx, it, white) }
        val b = reach(cy, 1f) { badge(map, cx, it, white) }
        return Rect(l.roundToInt().toFloat(), t.roundToInt().toFloat(), r.roundToInt() + 1f, b.roundToInt() + 1f)
    }

    /** Back from the search page or Settings, opened on the Issues page, comes back to it, on its control (129, 149). */
    @Test
    fun searchAndSettingsComeBackToTheTabPageTheyWereOpenedOn() {
        show()
        toTab("Issues")
        press(Key.DirectionRight)
        assertFocused("search")
        press(Key.DirectionCenter)
        await("search field")
        // search opens with its field editing: the first Back closes the keyboard, the next leaves (64)
        back()
        if (shows("search field")) back()
        await("issues page")
        assertFocused("search", "Back from search opened on Issues: ")
        // the bar arrived with focus already given: it draws it (the stand-in focus, rememberTvClick), white behind the glass
        rule.onNodeWithTag("search").assert(SemanticsMatcher.expectValue(ShowsFocus, true))
        assertDrawnFocused("search")
        press(Key.DirectionRight)
        assertFocused("avatar")
        press(Key.DirectionCenter)
        await("account name")
        back()
        await("issues page")
        assertFocused("avatar", "Back from Settings opened on Issues: ")
        rule.onNodeWithTag("avatar").assert(SemanticsMatcher.expectValue(ShowsFocus, true))
        assertDrawnFocused("avatar")
    }

    /** [tag], an icon on the bar, drawn focused: tv-material's white container, not the black ground at rest. */
    private fun assertDrawnFocused(tag: String) {
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        val map = rule.onRoot().captureToImage().toPixelMap()
        val box = px(tag)
        val edge = map[(box.left + 3 * rule.density.density).roundToInt(), box.center.y.roundToInt()]
        assertTrue("$tag, focused, is drawn at rest: $edge", edge.red > 0.9f && edge.green > 0.9f && edge.blue > 0.9f)
    }

    /**
     * A move along the bar changes the page, but recomposes only the tabs, never the bar around them; a move in the page
     * recomposes neither (57).
     */
    @Test
    fun aMoveAlongTheBarRecomposesTheTabsNotTheBar() {
        show(probe = true)
        press(Key.DirectionUp)
        assertFocused("nav Discover")
        rule.runOnIdle { counts.clear() }
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        assertFocused("nav Issues")
        var moves = rule.runOnIdle { HashMap(counts) }
        assertEquals("moves along the bar recomposed the bar: $moves", 0, moves["TopBar"] ?: 0)
        press(Key.DirectionDown)
        assertFocused("issue filter open")
        press(Key.DirectionRight)
        assertFocused("issue:1")
        rule.runOnIdle { counts.clear() }
        press(Key.DirectionRight)
        assertFocused("issue:2")
        moves = rule.runOnIdle { HashMap(counts) }
        assertEquals("a move in the page recomposed the bar: $moves", 0, moves["TopBar"] ?: 0)
        assertEquals("a move in the page recomposed the count: $moves", 0, moves["requests count"] ?: 0)
    }
}
