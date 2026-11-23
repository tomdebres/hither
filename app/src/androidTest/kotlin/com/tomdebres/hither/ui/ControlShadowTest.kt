package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.shadow.ShadowContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalGraphicsContext
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
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.Session
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.discover.DiscoverModel
import com.tomdebres.hither.ui.discover.DiscoverPage
import com.tomdebres.hither.ui.discover.DiscoverScreen
import com.tomdebres.hither.ui.discover.DiscoverSource
import com.tomdebres.hither.ui.media.MediaModel
import com.tomdebres.hither.ui.media.MediaScreen
import com.tomdebres.hither.ui.settings.SettingsScreen
import com.tomdebres.hither.ui.signin.SignInScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Nothing draws a shadow (A3, DECISIONS 322): no control (Tom, 2026-11-11; DECISIONS 67), and since A3 no card either,
 * the lockups, the title page's poster and My Requests' rows included.
 *
 * A shadow drawn through Compose's shadow context is blurred into its cache the first time it is drawn, so anything
 * that drew one would leave an entry there. The count is read by reflection as in [FocusShadowTest]. Each test moves
 * focus across a screen and asks that the cache stayed empty: the sign-in form, Discover's bar over its lockups, the
 * media page and its request modal, and Settings.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ControlShadowTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var shadows: ShadowContext

    private fun ShadowContext.blurred(): Int {
        val field = runCatching { javaClass.getDeclaredField("dropShadowCache") }.getOrElse {
            throw AssertionError("${javaClass.name} has no dropShadowCache any more: check how this Compose caches drop shadows", it)
        }
        field.isAccessible = true
        val cache = field.get(this) ?: return 0
        return cache.javaClass.getMethod("getSize").invoke(cache) as Int
    }

    /** Lets a focus animation finish and the frame be drawn, then counts the cache. */
    private fun settledCount(): Int {
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        return rule.runOnIdle { shadows.blurred() }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
    }

    private fun await(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun focusedTag(): String = runCatching {
        rule.onNode(isFocused()).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.TestTag) { "?" } ?: "?"
    }.getOrElse { "nothing" }

    /** Moves with [keys], noting where focus went, and fails if any control drew a shadow. */
    private fun assertNoShadowAcross(screen: String, before: Int, keys: List<Key>) {
        val visited = mutableListOf(focusedTag())
        for (key in keys) {
            press(key)
            visited += focusedTag()
        }
        val after = settledCount()
        if (after != before) {
            throw AssertionError("$screen: focus on $visited drew ${after - before} shadow bitmap(s) ($before before, $after after)")
        }
    }

    @Test
    fun theSignInFormsFieldsAndButtonDrawNoShadow() {
        rule.setContent {
            shadows = LocalGraphicsContext.current.shadowContext
            var server by remember { mutableStateOf("") }
            var email by remember { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            DpadHost {
                SignInScreen(Screen.SignIn(), server, email, password, { server = it }, { email = it }, { password = it }, onSubmit = {})
            }
        }
        await("sign in")
        // nothing on this page is a card: no shadow at all, before or after
        assertNoShadowAcross("sign-in", before = 0, listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionDown))
    }

    @Test
    fun discoversLockupsAndTheBarDrawNoShadow() {
        val session = Session(User(id = 8, displayName = "TV", permissions = 32), PublicSettings(applicationTitle = "Seerr", localLogin = true))
        rule.setContent {
            shadows = LocalGraphicsContext.current.shadowContext
            val scope = rememberCoroutineScope()
            val model = remember {
                DiscoverModel(
                    DiscoverSource { row, _ ->
                        DiscoverPage("http://seerr.test", Page(results = (1..8).map { MediaResult(id = row.ordinal * 100 + it, mediaType = "movie", title = "Film") }))
                    },
                    session,
                    scope,
                )
            }
            DpadHost { DiscoverScreen(model, onOpen = {}) }
        }
        await("card movie:1")
        // the focused lockup draws none (A3), and the bar's controls add none
        assertNoShadowAcross("a lockup", before = 0, listOf())
        press(Key.DirectionUp)
        assertNoShadowAcross("the bar", before = 0, listOf(Key.DirectionRight, Key.DirectionRight, Key.DirectionRight, Key.DirectionLeft, Key.DirectionLeft, Key.DirectionLeft))
    }

    @Test
    fun theMediaPagesButtonsOverviewAndRequestPanelDrawNoShadow() {
        rule.setContent {
            shadows = LocalGraphicsContext.current.shadowContext
            val scope = rememberCoroutineScope()
            val model = remember { MediaModel(source = { _, _ -> MediaPages.series() }, card = MediaPages.seriesCard(), scope = scope) }
            DpadHost { MediaScreen(model) }
        }
        await("action Request More")
        // the poster draws none since A3 (its fixed shadow went with the cards'), nor the buttons and the overview
        val page = 0
        assertNoShadowAcross("the media page", before = page, listOf(Key.DirectionRight, Key.DirectionLeft, Key.DirectionDown, Key.DirectionUp))
        // the request's side panel (A5): its season rows and the primary
        press(Key.DirectionCenter)
        await("request modal")
        assertNoShadowAcross("the request panel", before = page, listOf(Key.DirectionDown, Key.DirectionDown, Key.DirectionUp))
    }

    @Test
    fun settingsRowsDrawNoShadow() {
        rule.setContent {
            shadows = LocalGraphicsContext.current.shadowContext
            DpadHost { SettingsScreen("TV", "tv@example.invalid", "Seerr", "http://seerr.test", onAbout = {}, onSignOut = {}) }
        }
        await("about")
        assertNoShadowAcross("Settings", before = 0, listOf(Key.DirectionDown, Key.DirectionUp))
    }
}
