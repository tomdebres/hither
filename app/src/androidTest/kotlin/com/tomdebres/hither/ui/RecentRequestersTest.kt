package com.tomdebres.hither.ui

import com.tomdebres.hither.core.rules.ImageSource
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PageInfo
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.RequestPage
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.RequestTitle
import com.tomdebres.hither.core.rules.discoverPlan
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.discover.DiscoverModel
import com.tomdebres.hither.ui.discover.DiscoverPage
import com.tomdebres.hither.ui.discover.DiscoverScreen
import com.tomdebres.hither.ui.discover.DiscoverSource
import com.tomdebres.hither.ui.discover.PlanSource
import com.tomdebres.hither.ui.requests.RequestsModel
import com.tomdebres.hither.ui.requests.RequestsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Recent Requests' cards name who asked, as Seerr's RequestCard does (Tom, 2026-11-13, DECISIONS 118), on a
 * device. With View Requests, every card shows its requester's name, others' among them, under the title and
 * above "Status", inside the card. Without it, no card names anyone. Each test signs in with the opposite
 * right to the one the plan then reads, so the rule must follow the live rights, not the sign-in's.
 * Whose requests the row holds is the server's to decide (`RequestScopeTest`).
 */
@RunWith(AndroidJUnit4::class)
class RecentRequestersTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val tv = User(id = 8, displayName = "TV")
    private val robin = User(id = 21, displayName = "Robin")
    private val sasha = User(id = 22, displayName = "Sasha")

    private fun request(id: Int, by: User) = MediaRequest(
        id = id,
        status = RequestStatus.PENDING,
        type = "movie",
        createdAt = "2026-11-12T2$id:00:00.000Z",
        media = MediaInfo(mediaType = "movie", tmdbId = id * 100, status = 2),
        requestedBy = by,
    )

    private class Requests(private val list: List<MediaRequest>) : RequestsSource {
        override suspend fun page(skip: Int, take: Int): RequestPage =
            if (skip == 0) RequestPage(PageInfo(results = list.size), list) else RequestPage()

        override suspend fun title(type: MediaType, tmdbId: Int) = RequestTitle("Film $tmdbId", "2026", null, null)
    }

    private val rows = DiscoverSource { row, _ ->
        DiscoverPage(ImageSource.unknown("http://seerr.test"), Page(results = (1..8).map { MediaResult(id = row.ordinal * 100 + it, mediaType = "movie", title = "Film") }))
    }

    /** Discover signed in with [signedIn]'s rights, the plan reading [live] (auth/me, as Seerr's web reads them). */
    private fun show(signedIn: Int, live: Int, requests: List<MediaRequest>) {
        val session = Session(User(id = 8, displayName = "TV", permissions = signedIn), PublicSettings(applicationTitle = "Seerr", localLogin = true))
        rule.setContent {
            val scope = rememberCoroutineScope()
            val model = remember { DiscoverModel(rows, session, scope, plans = PlanSource { discoverPlan(null, live) }) }
            val recent = remember { RequestsModel(Requests(requests), scope) }
            LaunchedEffect(recent) { recent.open() }
            DpadHost {
                Box(Modifier.fillMaxSize().testTag("stage")) {
                    DiscoverScreen(model, onOpen = {}, recent = recent)
                }
            }
        }
    }

    /** The lockups are in and their titles read (the requester line sits under the title). */
    private fun awaitCards(vararg ids: Int) {
        rule.waitUntil(10_000) {
            ids.all { id ->
                rule.onAllNodes(hasText("Film ${id * 100}") and hasAnyAncestor(hasTestTag("lockup recent request:$id")), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
    }

    @Test
    fun withViewRequestsEachCardNamesWhoAskedUnderItsTitle() {
        show(signedIn = Permission.REQUEST, live = Permission.REQUEST or Permission.REQUEST_VIEW, requests = listOf(request(3, robin), request(2, sasha), request(1, tv)))
        awaitCards(3, 2, 1)
        // the titles are in (above): every card's name line follows with it
        val named = { rule.onAllNodesWithTag("recent requester", useUnmergedTree = true).fetchSemanticsNodes().size }
        runCatching { rule.waitUntil(3_000) { named() == 3 } }
        assertEquals("cards naming who asked", 3, named())
        for ((id, name) in listOf(3 to "Robin", 2 to "Sasha", 1 to "TV")) {
            val card = rule.onNode(hasTestTag("recent request:$id")).fetchSemanticsNode().boundsInRoot
            // since A3 the name is in the lockup's caption, under its backdrop (DECISIONS 320)
            val title = rule.onNode(hasTestTag("recent title") and hasAnyAncestor(hasTestTag("lockup recent request:$id")), useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val line = rule.onNode(hasTestTag("recent requester") and hasAnyAncestor(hasTestTag("lockup recent request:$id")), useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            rule.onNode(hasText(name) and hasAnyAncestor(hasTestTag("recent requester")) and hasAnyAncestor(hasTestTag("lockup recent request:$id")), useUnmergedTree = true)
                .assertExists("card $id names $name")
            assertTrue("card $id: the name is under the title ($line, title $title)", line.top >= title.bottom - 1f)
            assertTrue("card $id: the name is under the backdrop, at its left ($line, picture $card)", line.top >= card.bottom && line.left >= card.left - 1f)
        }
    }

    @Test
    fun withoutTheRightNoCardNamesAnyone() {
        show(signedIn = Permission.REQUEST or Permission.REQUEST_VIEW, live = Permission.REQUEST, requests = listOf(request(2, tv), request(1, tv)))
        awaitCards(2, 1)
        rule.onAllNodesWithTag("recent requester", useUnmergedTree = true).assertCountEquals(0)
    }
}
