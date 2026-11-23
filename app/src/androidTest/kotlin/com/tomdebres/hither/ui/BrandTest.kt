package com.tomdebres.hither.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tomdebres.hither.R
import com.tomdebres.hither.core.SignInFailure
import com.tomdebres.hither.core.rules.SignInProblem
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.ui.settings.AboutScreen
import com.tomdebres.hither.ui.signin.SignInScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hither's brand as the TV draws it (DECISIONS 290): the launcher icon, the banner and the two logos in the app,
 * sampled at points whose colour the masters in docs/brand/ fix: the tile's gradient, the comet cut flat in its head
 * and its tail, the soft shadow on the head's bottom-right edge only, the gloss, the tile's rounded corner, and the
 * word, found as white ink where the masters put it (so a change of typeface needs no new points). Needs a device or
 * emulator; CI compiles it only.
 */
@RunWith(AndroidJUnit4::class)
class BrandTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theLauncherIconIsTheMarkOnTheGround() {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals("the app's icon", R.mipmap.ic_launcher, info.icon)
        val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        // 108 units at 4 px each; the mark's 96 units sit at (24, 24), scaled to 60
        val background = render(icon.background, 432, 432)
        for ((x, y) in listOf(2f to 2f, 54f to 54f, 105f to 105f)) {
            assertColour(background, x * 4, y * 4, BASE, exact = true, "the icon's background")
        }
        val foreground = render(icon.foreground, 432, 432)
        checkMark(foreground, scale = 4f * 0.625f, left = 24f * 4, top = 24f * 4, "the launcher icon")
        assertEquals("outside the tile, the foreground is clear", 0, foreground.getPixel(8, 8) ushr 24)
    }

    @Test
    fun theBannerIsTheFullLogoOnTheGround() {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals("the app's banner", R.drawable.banner, info.banner)
        val banner = render(context.getDrawable(R.drawable.banner)!!, 1280, 720)
        for ((x, y) in listOf(2f to 2f, 300f to 10f, 160f to 175f)) {
            assertColour(banner, x * 4, y * 4, BASE, exact = true, "the banner's ground")
        }
        // the full logo, 322 by 96, at 80% of the width: scaled by .8 and placed at (32, 51.84)
        val scale = 4f * 0.8f
        checkMark(banner, scale, left = 32f * 4, top = 51.84f * 4, "the banner")
        assertColour(banner, 32f * 4 + 5f * scale, 51.84f * 4 + 5f * scale, BASE, exact = false, "the banner: the ground round the tile's corner")
        checkWord(banner, scale, left = 32f * 4, top = 51.84f * 4, "the banner")
    }

    @Test
    fun signInHeadsItsFormWithTheStackedLogoAndEveryErrorStillFits() {
        // The two tallest forms: an error under every field, or the longest failure. They never
        // show together: a submit with field errors clears the failure, and a failure comes
        // only from a send, which field errors stop (SessionViewModel.submit).
        var state by mutableStateOf(
            Screen.SignIn(problems = setOf(SignInProblem.SERVER, SignInProblem.EMAIL, SignInProblem.PASSWORD)),
        )
        rule.setContent {
            SignInScreen(
                state = state,
                server = "", email = "", password = "",
                onServer = {}, onEmail = {}, onPassword = {}, onSubmit = {},
            )
        }
        val logo = rule.onNodeWithTag("wordmark").assertContentDescriptionEquals("Hither")
        assertWhole("password error", "every field's error")
        state = Screen.SignIn(failure = SignInFailure.CSRF_NEEDS_HTTPS, serverTitle = "Seerr")
        rule.waitForIdle()
        assertWhole("sign-in error", "the longest failure")

        // the stacked logo, 198 by 180: the mark at (51, 0), the word under it from y 100
        val image = logo.captureToImage().asAndroidBitmap()
        val scale = image.width / 198f
        assertEquals("the stacked logo keeps its proportions", 180f / 198f, image.height / image.width.toFloat(), 0.02f)
        checkMark(image, scale, left = 51f * scale, top = 0f, "the sign-in logo")
        assertWhite(image, 0f, 100f * scale, image.width.toFloat(), image.height.toFloat(), "the sign-in logo's word")
        assertNoWhite(image, 0f, 0f, 51f * scale, 96f * scale, "beside the sign-in logo's mark")
    }

    /**
     * The logo, then the card, with [error] (the last message before the button) and Sign In
     * whole and on screen. A logo too tall does not push the form off screen: the column
     * squeezes the card, which cuts off what no longer fits, so this checks sizes, not places.
     */
    private fun assertWhole(error: String, what: String) {
        val screen = rule.onRoot().fetchSemanticsNode().size
        val logo = rule.onNodeWithTag("wordmark").fetchSemanticsNode().boundsInRoot
        assertTrue("$what: the logo starts on screen (top ${logo.top})", logo.top >= 0f)
        assertTrue("$what: the logo is above the card", logo.bottom <= rule.onNodeWithTag("heading").fetchSemanticsNode().boundsInRoot.top)
        val message = rule.onNodeWithTag(error).fetchSemanticsNode()
        assertTrue("$what: the message is shown", message.size.height > 0)
        assertEquals("$what: the message is not cut off", message.size.height.toFloat(), message.boundsInRoot.height, 1f)
        val button = rule.onNodeWithTag("sign in").fetchSemanticsNode()
        val full = with(rule.density) { 40.dp.toPx() } // tv-material's button height (A1; Seerr's was 32)
        assertEquals("$what: Sign In keeps its full height", full, button.size.height.toFloat(), 1f)
        assertEquals("$what: Sign In is not cut off", button.size.height.toFloat(), button.boundsInRoot.height, 1f)
        assertTrue("$what: Sign In ends on screen (${button.boundsInRoot.bottom} of ${screen.height})", button.boundsInRoot.bottom <= screen.height)
    }

    /** About's mark is Settings' (A7's SettingsScaffold, as tvOS's About): the stacked logo, left of its rows. */
    @Test
    fun aboutCarriesTheStackedLogo() {
        rule.setContent { AboutScreen(onPrivacy = {}) }
        val logo = rule.onNodeWithTag("about wordmark").assertContentDescriptionEquals("Hither")
        val image = logo.captureToImage().asAndroidBitmap()
        val scale = image.width / 198f
        assertEquals("the stacked logo keeps its proportions", 180f / 198f, image.height / image.width.toFloat(), 0.02f)
        checkMark(image, scale, left = 51f * scale, top = 0f, "About's logo")
        assertWhite(image, 0f, 100f * scale, image.width.toFloat(), image.height.toFloat(), "About's logo's word")
        assertNoWhite(image, 0f, 0f, 51f * scale, 96f * scale, "beside About's logo's mark")
    }

    /**
     * The mark's points, in the masters' 96-unit coordinates: [scale] pixels a unit, its top left at ([left], [top]).
     * The shadow and the gloss, a few units wide, are checked where the mark is drawn large enough (2 px a unit) for
     * them to hold a clean sample.
     */
    private fun checkMark(image: Bitmap, scale: Float, left: Float, top: Float, what: String) {
        fun at(x: Float, y: Float, colour: Int, exact: Boolean, part: String) =
            assertColour(image, left + x * scale, top + y * scale, colour, exact, "$what: $part")
        // the head: r15 about (63.6, 65), where the master's arc puts it
        at(63.6f, 65f, BASE, exact = true, "the comet's head, flat")
        at(41.2f, 41.6f, BASE, exact = true, "the comet's tail, flat")
        at(80f, 30f, tile(80f, 30f), exact = false, "the tile, top right")
        at(20f, 80f, tile(20f, 80f), exact = false, "the tile, bottom left")
        // 1.5 units out from the head's bottom-right edge: inside the shadow (the cut moved 2.5 down and right at .2)
        if (scale >= 2f) at(75.3f, 76.7f, mix(tile(75.3f, 76.7f), BASE, 0.2f), exact = false, "the shadow on the bottom-right edge")
        // 1.5 units out from its top-right edge: no shadow there
        at(75.3f, 53.4f, tile(75.3f, 53.4f), exact = false, "no shadow on the top-right edge")
        if (scale >= 2f) {
            // the middle of the gloss band round the corner, 24 units from (36, 36) at 225 degrees: white from .4 at
            // y 12 to nothing at y 50
            val alpha = 0.4f * (1f - (19.03f - 12f) / 38f)
            at(19.03f, 19.03f, mix(tile(19.03f, 19.03f), WHITE, alpha), exact = false, "the gloss")
        }
    }

    /** The word, right of the mark in the full logo's coordinates: white ink there, and none over the mark. */
    private fun checkWord(image: Bitmap, scale: Float, left: Float, top: Float, what: String) {
        assertWhite(image, left + 110f * scale, top, left + 322f * scale, top + 96f * scale, "$what's word")
        assertNoWhite(image, left, top, left + 96f * scale, top + 96f * scale, "$what's mark")
    }

    /** At least 5% of the box is white ink: the word's letters, whatever their face. */
    private fun assertWhite(image: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float, what: String) {
        val (white, all) = whiteIn(image, x0, y0, x1, y1)
        assertTrue("$what: $white of $all pixels white", white * 100 >= all * 5)
    }

    private fun assertNoWhite(image: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float, what: String) {
        assertEquals("$what: no white ink", 0, whiteIn(image, x0, y0, x1, y1).first)
    }

    private fun whiteIn(image: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float): Pair<Int, Int> {
        var white = 0
        var all = 0
        for (y in y0.roundToInt() until y1.roundToInt().coerceAtMost(image.height)) {
            for (x in x0.roundToInt() until x1.roundToInt().coerceAtMost(image.width)) {
                all++
                val c = image.getPixel(x, y)
                if ((0..3).all { ((c ushr (it * 8)) and 0xFF) >= 0xF0 }) white++
            }
        }
        return white to all
    }

    private fun render(drawable: Drawable, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun assertColour(image: Bitmap, x: Float, y: Float, expected: Int, exact: Boolean, what: String) {
        val got = image.getPixel(x.roundToInt(), y.roundToInt())
        val off = (0..3).maxOf { abs(((got ushr (it * 8)) and 0xFF) - ((expected ushr (it * 8)) and 0xFF)) }
        // a flat fill is drawn exactly; a gradient within dithering and the expected's rounding
        assertTrue("$what at (${x.roundToInt()}, ${y.roundToInt()}): ${hex(got)}, expected ${hex(expected)}", off <= if (exact) 1 else 8)
    }

    private companion object {
        const val BASE = 0xFF131928.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val FROM = 0xFFC395FC.toInt()
        const val TO = 0xFF4F65F5.toInt()

        /** The tile's gradient, Seerr's icon's: (48, 0) to (117.5, 69.5) in the mark's units, at a point. */
        fun tile(x: Float, y: Float): Int = mix(FROM, TO, ((x - 48f + y) / 139f).coerceIn(0f, 1f))

        fun mix(a: Int, b: Int, t: Float): Int = (0..3).fold(0) { acc, i ->
            val ca = (a ushr (i * 8)) and 0xFF
            val cb = (b ushr (i * 8)) and 0xFF
            acc or ((ca + (cb - ca) * t).roundToInt() shl (i * 8))
        }

        fun hex(c: Int) = "#%08X".format(c)
    }
}
