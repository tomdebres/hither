package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
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
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A card on the TV's home screen opens its title's page in Hither (M4, DECISIONS 76): the page and
 * its focus as from Discover, the title leaving Watch Next, and Back leaving to the home screen.
 * The whole home, through the real client against a scripted server.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HomeLinkTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val session = Session(User(id = 8, displayName = "TV", permissions = 32), PublicSettings(applicationTitle = "Seerr", localLogin = true))
    private val showsFocus = SemanticsMatcher.expectValue(ShowsFocus, true)

    private val access = object : SeerrAccess {
        private val client = SeerrClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                val json = SeerrClient.json
                val body = when {
                    path.startsWith("/api/v1/movie/") -> {
                        val id = path.substringAfterLast('/').toInt()
                        json.encodeToString(MovieDetails.serializer(), MovieDetails(id = id, title = "Film $id", overview = "About it."))
                    }
                    path == "/api/v1/settings/public" -> json.encodeToString(PublicSettings.serializer(), session.settings)
                    path == "/api/v1/auth/me" -> json.encodeToString(User.serializer(), session.user)
                    // Every row: three films, 1 to 3.
                    else -> json.encodeToString(
                        Page.serializer(MediaResult.serializer()),
                        Page(results = (1..3).map { MediaResult(id = it, mediaType = "movie", title = "Film $it") }),
                    )
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
            "http://seerr.test",
            AcceptAllCookiesStorage(),
        )

        override suspend fun <T> call(call: suspend (SeerrClient) -> T): T = call(client)
    }

    private var leaves = 0
    private var fronts = 0
    private var taken = 0
    private val opened = mutableListOf<String>()
    private val side = HomeSide(front = { fronts++; null }, onTitleOpened = { opened += it }, onLeave = { leaves++ })

    /** A card from the home screen: the name kept for it stands in while the page loads. */
    private val card = TitleCard("movie:550", 550, MediaType.MOVIE, "Film 550", null, poster = null, badge = null)

    private var link by mutableStateOf<TitleCard?>(null)

    private fun show(initial: TitleCard?) {
        link = initial
        rule.setContent {
            DpadHost { HomeScreen(Screen.Home(session), access, onSignOut = {}, link = link, onLinkTaken = { taken++; link = null }, side = side) }
        }
        rule.waitForIdle()
    }

    private fun await(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    /** Back as the remote sends it: through the activity's dispatcher, where BackHandler listens. */
    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "a node without a tag" } ?: "a node without a tag"
    }.getOrElse { "nothing" }

    private fun assertFocused(tag: String) {
        runCatching { rule.waitUntil(3_000) { focusedTag() == tag } }
        val now = focusedTag()
        if (now != tag) throw AssertionError("expected focus on $tag, it is on $now")
    }

    @Test
    fun aLinkAtStartOpensTheTitlesPageWithFocusAndBackLeavesTheApp() {
        show(card)
        await("action Request")
        rule.onNodeWithTag("media title").assertTextEquals("Film 550")
        assertFocused("action Request")
        rule.onNodeWithTag("action Request").assert(showsFocus)
        assertEquals(1, taken)
        assertEquals(listOf("movie:550"), opened)
        assertEquals(1, fronts)

        back()
        assertEquals(1, leaves)
        // Behind it, for the next time Hither is opened: Discover.
        await("card movie:1")
        rule.onAllNodesWithTag("media title").assertCountEquals(0)
    }

    @Test
    fun aLinkWhileHitherIsOpenOpensThePageOverItAndAPageFromDiscoverDoesNotLeave() {
        show(null)
        await("card movie:1")
        assertFocused("card movie:1")
        // From Discover: Back returns to the card and stays in Hither.
        press(Key.DirectionCenter)
        await("action Request")
        assertEquals(listOf("movie:1"), opened)
        back()
        assertFocused("card movie:1")
        assertEquals(0, leaves)

        // A card on the home screen chosen while Hither is open (the activity's onNewIntent).
        rule.runOnIdle { link = card }
        await("action Request")
        rule.onNodeWithTag("media title").assertTextEquals("Film 550")
        assertFocused("action Request")
        assertEquals(listOf("movie:1", "movie:550"), opened)
        back()
        assertEquals(1, leaves)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(n: Int) {
    val found = fetchSemanticsNodes().size
    if (found != n) throw AssertionError("expected $n nodes, found $found")
}
