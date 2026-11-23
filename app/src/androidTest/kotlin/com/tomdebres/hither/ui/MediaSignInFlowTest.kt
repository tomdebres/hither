package com.tomdebres.hither.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.HitherApp
import com.tomdebres.hither.core.PlexTv
import com.tomdebres.hither.core.PollPace
import com.tomdebres.hither.data.Account
import com.tomdebres.hither.data.AccountMethod
import com.tomdebres.hither.data.AccountStorage
import com.tomdebres.hither.data.SavedCookie
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.SeerrSessionGateway
import com.tomdebres.hither.session.SessionViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * M8's ways in end to end on tv31: the app's own screens, view model and gateway, by D-pad, against a scripted Seerr
 * and a scripted plex.tv. Each signs in and keeps the right account; a refused one comes back with its words.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MediaSignInFlowTest {
    @get:Rule
    val rule = createComposeRule()

    private class Memory : AccountStorage {
        @Volatile var account: Account? = null
        override suspend fun account(): Account? = account
        override suspend fun save(account: Account) {
            this.account = account
        }
        override suspend fun cookies(): List<SavedCookie> = emptyList()
        override suspend fun saveCookies(cookies: List<SavedCookie>) = Unit
        override suspend fun clear() {
            account = null
        }
    }

    private val store = Memory()

    /** A made-up Quick Connect secret, built here so it never reads as a key. */
    private val qcSecret = "AB".repeat(8)
    private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile private var mediaServerType = 1
    @Volatile private var plexLooks = 0
    @Volatile private var qcChecks = 0
    @Volatile private var refuse = false
    @Volatile private var plexNeverLinks = false

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, cookie: String? = null) =
        respond(body, status, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.SetCookie to listOfNotNull(cookie)))

    private fun MockRequestHandleScope.seerr(call: HttpRequestData): HttpResponseData {
        val path = call.url.encodedPath.removePrefix("/api/v1")
        seen += "${call.method.value} $path"
        return when (path) {
            "/settings/public" -> json("""{"initialized":true,"applicationTitle":"Seerr","localLogin":true,"mediaServerLogin":true,"mediaServerType":$mediaServerType}""")
            "/status" -> json("""{"version":"3.5.2"}""")
            "/auth/plex", "/auth/jellyfin", "/auth/jellyfin/quickconnect/authenticate" ->
                if (refuse) json("""{"message":"Access denied."}""", HttpStatusCode.Forbidden)
                else json("""{"id":12}""", cookie = "connect.sid=s; Path=/")
            "/auth/jellyfin/quickconnect/initiate" -> json("""{"code":"525562","secret":"${qcSecret}"}""")
            "/auth/jellyfin/quickconnect/check" -> json("""{"authenticated":${++qcChecks >= 3}}""")
            "/auth/me" -> json("""{"id":12,"displayName":"Robin","permissions":32}""")
            else -> json("""{"page":1,"totalPages":1,"totalResults":0,"results":[]}""")
        }
    }

    private fun MockRequestHandleScope.plexTv(call: HttpRequestData): HttpResponseData =
        if (call.method == HttpMethod.Post) json("""{"id":77,"code":"X7K6","expiresIn":900}""", HttpStatusCode.Created)
        else (++plexLooks).let { n -> json("""{"id":77,"code":"X7K6","authToken":${if (!plexNeverLinks && n >= 3) "\"plex-token\"" else "null"}}""") }

    private lateinit var vm: SessionViewModel

    private fun app() {
        val gateway = SeerrSessionGateway(
            store,
            plexTv = { PlexTv(MockEngine { plexTv(it) }, "device-test", "https://plex.test") },
            pace = PollPace(everyMillis = 400, longestMillis = 800, giveUpAfter = 3),
        ) { MockEngine { seerr(it) } }
        vm = SessionViewModel(gateway)
        rule.setContent { HitherApp(vm, gateway) }
    }

    private fun press(key: Key) = rule.onRoot().performKeyInput { pressKey(key) }

    private fun focused(tag: String) = rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag(tag).assertIsFocused() }.isSuccess }

    /** The server typed and its keyboard's Next: the server is read, and focus goes on as before M8. */
    private fun typeServer() {
        focused("server")
        press(Key.DirectionCenter)
        rule.onNodeWithTag("server input").performTextInput("seerr.test:5055")
        press(Key.Enter)
        rule.waitUntil(5_000) { (vm.screen as? Screen.SignIn)?.offer != null }
    }

    @Test
    fun plexByDpad_theCodeOnScreen_signedInWithAPlexAccountKept() {
        app()
        typeServer()
        press(Key.Back) // the email field's keyboard closed again
        focused("email")
        repeat(3) { press(Key.DirectionDown) }
        focused("plex")
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("code").assertContentDescriptionEquals("Code X 7 K 6") }.isSuccess }
        rule.waitUntil(10_000) { vm.screen is Screen.Home }
        assertEquals(Account("http://seerr.test:5055", "", "", AccountMethod.PLEX), store.account)
        assertEquals(true, seen.contains("POST /auth/plex"))
    }

    @Test
    fun plexRefusedByTheServer_backOnTheFormWithItsWords_onThePlexButton() {
        refuse = true
        app()
        typeServer()
        press(Key.Back)
        repeat(3) { press(Key.DirectionDown) }
        focused("plex")
        press(Key.DirectionCenter)
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag("sign-in error").assertExists() }.isSuccess }
        rule.onNodeWithTag("sign-in error").assertTextEquals(
            "Seerr refused this Plex account: it is not one of Seerr's users, or it has no access to the server's Plex. Ask whoever runs Seerr.",
        )
        focused("plex")
        assertEquals(null, store.account)
    }

    @Test
    fun jellyfinByDpad_usernameAndPassword_aMediaServerAccountKept() {
        mediaServerType = 2
        app()
        typeServer()
        press(Key.Back)
        repeat(3) { press(Key.DirectionDown) }
        focused("media server")
        rule.onNodeWithTag("media server").assertTextEquals("Jellyfin")
        press(Key.DirectionCenter)
        focused("username")
        press(Key.DirectionCenter)
        rule.onNodeWithTag("username input").performTextInput("robin")
        press(Key.Enter)
        rule.onNodeWithTag("media password input").performTextInput("robin-password")
        press(Key.Enter)
        focused("media sign in")
        press(Key.DirectionCenter)
        rule.waitUntil(10_000) { vm.screen is Screen.Home }
        assertEquals(Account("http://seerr.test:5055", "robin", "robin-password", AccountMethod.MEDIA_SERVER), store.account)
    }

    @Test
    fun quickConnectByDpad_approvedElsewhere_aQuickConnectAccountKept() {
        mediaServerType = 2
        app()
        typeServer()
        press(Key.Back)
        repeat(3) { press(Key.DirectionDown) }
        focused("media server")
        press(Key.DirectionCenter)
        focused("username")
        repeat(3) { press(Key.DirectionDown) }
        focused("quick connect")
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("code").assertContentDescriptionEquals("Code 5 2 5 5 6 2") }.isSuccess }
        rule.waitUntil(10_000) { vm.screen is Screen.Home }
        assertEquals(Account("http://seerr.test:5055", "", "", AccountMethod.QUICK_CONNECT), store.account)
    }

    /** Jellyfin's form and a code are pages pushed over the form, titled with the server's name, as tvOS's (A7). */
    @Test
    fun jellyfinsFormAndQuickConnectsCodeAreTitledWithTheServersName() {
        mediaServerType = 2
        app()
        typeServer()
        press(Key.Back)
        repeat(3) { press(Key.DirectionDown) }
        focused("media server")
        press(Key.DirectionCenter)
        focused("username")
        rule.onNodeWithTag("page title", useUnmergedTree = true).assertTextEquals("Seerr")
        repeat(3) { press(Key.DirectionDown) }
        focused("quick connect")
        press(Key.DirectionCenter)
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("code").assertContentDescriptionEquals("Code 5 2 5 5 6 2") }.isSuccess }
        rule.onNodeWithTag("page title", useUnmergedTree = true).assertTextEquals("Seerr")
    }

    /**
     * The code page's Cancel has focus as the page arrives, and tv-material draws it (A7: it had focus, was scaled, and
     * tv-material drew it at rest, grey: the focus reached Hither's own collector and not tv-material's). Its commonest
     * colour is the focused white, not the resting grey.
     */
    @Test
    fun theCodesCancelDrawsItsFocusAsThePageArrives() {
        // plex.tv never links: the code page stays
        plexNeverLinks = true
        app()
        typeServer()
        press(Key.Back)
        repeat(3) { press(Key.DirectionDown) }
        focused("plex")
        press(Key.DirectionCenter)
        focused("cancel")
        Thread.sleep(500)
        rule.waitForIdle()
        val image = rule.onNodeWithTag("cancel").captureToImage().asAndroidBitmap()
        val seen = HashMap<Int, Int>()
        for (y in 0 until image.height) for (x in 0 until image.width) seen.merge(image.getPixel(x, y), 1, Int::plus)
        val fill = seen.maxByOrNull { it.value }!!.key
        assertTrue("Cancel's commonest colour is ${"#%08X".format(fill)}: the focused white expected", (0..2).all { ((fill ushr (it * 8)) and 0xFF) >= 0xF0 })
    }

    @Test
    fun cancelOnPlexsCode_backOnThePlexButton_plexTvAskedNoMore() {
        plexNeverLinks = true
        app()
        typeServer()
        press(Key.Back)
        repeat(3) { press(Key.DirectionDown) }
        focused("plex")
        press(Key.DirectionCenter)
        focused("cancel")
        press(Key.DirectionCenter)
        focused("plex")
        val looks = plexLooks
        Thread.sleep(1_500)
        assertEquals(looks, plexLooks)
        assertEquals(null, store.account)
    }
}
