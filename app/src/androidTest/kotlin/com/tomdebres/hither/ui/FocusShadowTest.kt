package com.tomdebres.hither.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.shadow.ShadowContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.test.ExperimentalTestApi
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
import com.tomdebres.hither.ui.discover.DiscoverModel
import com.tomdebres.hither.ui.discover.DiscoverPage
import com.tomdebres.hither.ui.discover.DiscoverScreen
import com.tomdebres.hither.ui.discover.DiscoverSource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * No shadow is drawn on Discover, at rest or as focus moves across the lockups (A3, DECISIONS 322: tv-material's focus
 * is a scale and a white edge; the cards' shadows of DECISIONS 47 and 67 ended with them).
 *
 * Before A3 the focus shadow was blurred once, not once a frame (DECISIONS 57): Compose's shadow cache keys a blurred
 * bitmap by the shadow's alpha, and 0.1.0-m1 animated that alpha, so on the Shield, whose frames came late, nearly every
 * frame blurred a new bitmap (3,097 image textures after 35 s of browsing). With no shadow at all that trap cannot come
 * back. The test still moves focus while the clock advances in uneven steps, as on a device that misses frames, and
 * counts the cache's entries: none.
 *
 * The count is read from Compose's own cache by reflection (`AndroidShadowContext`'s `dropShadowCache`): if a Compose
 * upgrade renames it, this test fails and says so.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FocusShadowTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val session = Session(User(id = 8, displayName = "TV", permissions = 32), PublicSettings(applicationTitle = "Seerr", localLogin = true))

    private fun page(row: DiscoverRow) = DiscoverPage(
        "http://seerr.test",
        Page(page = 1, totalPages = 1, results = (1..20).map { MediaResult(id = row.ordinal * 100 + it, mediaType = "movie", title = "Film ${row.ordinal * 100 + it}") }),
    )

    private fun ShadowContext.blurredShadows(): Int {
        val field = runCatching { javaClass.getDeclaredField("dropShadowCache") }.getOrElse {
            throw AssertionError("${javaClass.name} has no dropShadowCache any more: check how this Compose caches drop shadows", it)
        }
        field.isAccessible = true
        val cache = field.get(this) ?: return 0
        return cache.javaClass.getMethod("getSize").invoke(cache) as Int
    }

    @Test
    fun movingFocusAcrossTheLockupsOnASlowDeviceDrawsNoShadow() {
        lateinit var shadows: ShadowContext
        rule.setContent {
            shadows = LocalGraphicsContext.current.shadowContext
            val scope = rememberCoroutineScope()
            val model = remember { DiscoverModel(DiscoverSource { row, _ -> page(row) }, session, scope) }
            DpadHost { DiscoverScreen(model, onOpen = {}) }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("card movie:1").fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        val atRest = rule.runOnIdle { shadows.blurredShadows() }

        rule.mainClock.autoAdvance = false
        // Frame gaps as a busy Tegra X1 gave them (DECISIONS 53: p50 26 ms, p90 40 ms).
        val gaps = listOf(17L, 26L, 23L, 40L, 19L, 33L, 26L, 44L, 21L, 29L)
        repeat(5) {
            rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            for (gap in gaps) {
                rule.mainClock.advanceTimeBy(gap)
                // Let the device draw this frame: the cache fills as frames are drawn.
                rule.waitForIdle()
                Thread.sleep(25)
            }
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val after = rule.runOnIdle { shadows.blurredShadows() }

        if (atRest != 0 || after != 0) {
            throw AssertionError("Discover drew shadows: $atRest blurred at rest, $after after five focus moves")
        }
    }
}
