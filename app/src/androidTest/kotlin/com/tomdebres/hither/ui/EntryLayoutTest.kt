package com.tomdebres.hither.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.SignInFailure
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.MediaServerKind
import com.tomdebres.hither.core.rules.SignInMethods
import com.tomdebres.hither.core.rules.SignInProblem
import com.tomdebres.hither.data.AdminStorage
import com.tomdebres.hither.data.KeptAdmin
import com.tomdebres.hither.data.PinHash
import com.tomdebres.hither.session.AdminGateway
import com.tomdebres.hither.session.CodeKind
import com.tomdebres.hither.session.CodeState
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.ServerOffer
import com.tomdebres.hither.session.SignInPage
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.settings.AdminScreen
import com.tomdebres.hither.ui.settings.AdminSetup
import com.tomdebres.hither.ui.signin.CodeSignInScreen
import com.tomdebres.hither.ui.signin.MediaServerSignInScreen
import com.tomdebres.hither.ui.signin.SignInScreen
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Where something is typed, as the Apple TV app's (H2's X4; A7): one centred column 400 dp wide, Hither's mark 88 dp
 * wide over it, fields the column's width; each field's error in red words just under it, with nothing behind them
 * (never a row, never inside a card); and one prominent button per screen: every indigo pixel on the screen is the one
 * prominent button's. On the family's sign-in (with the other ways in), Jellyfin's form, a code that ran out, and the
 * approver's sign-in. Needs a device or emulator; CI compiles it only.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EntryLayoutTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(content: @Composable () -> Unit) {
        rule.setContent { DpadHost { content() } }
        rule.waitForIdle()
    }

    private fun await(tag: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun px(dp: Dp) = with(rule.density) { dp.toPx() }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private val screen get() = rule.onRoot().fetchSemanticsNode().size

    private val plexOffer = ServerOffer("http://seerr.test:5055", "Seerr", SignInMethods(true, true, false, false, MediaServerKind.PLEX))

    private fun signIn(state: Screen.SignIn) = show {
        SignInScreen(state, "", "", "", onServer = {}, onEmail = {}, onPassword = {}, onSubmit = {})
    }

    // ---- the column -----------------------------------------------------------------------------------------------

    /** The fields (and the column holding them) [width] wide and centred on the screen; the mark 88 dp wide over them. */
    private fun assertColumn(fields: List<String>, mark: Boolean, what: String) {
        val middle = screen.width / 2f
        for (field in fields) {
            val box = bounds(field)
            assertEquals("$what: $field is the column's 400 dp (${box.width} px)", px(400.dp), box.width, 1.5f)
            assertEquals("$what: $field is centred (${box.left}..${box.right} of ${screen.width})", middle, box.center.x, 1.5f)
        }
        if (mark) {
            val logo = bounds("wordmark")
            assertEquals("$what: the mark is 88 dp wide (${logo.width} px)", px(88.dp), logo.width, 1.5f)
            assertEquals("$what: the mark is centred", middle, logo.center.x, 1.5f)
            assertTrue("$what: the mark is over the fields", logo.bottom <= bounds(fields.first()).top)
        }
    }

    @Test
    fun theSignInIsACentredColumn400dpWideUnderASmallMark() {
        signIn(Screen.SignIn(offer = plexOffer, serverTitle = "Seerr"))
        await("plex")
        assertColumn(listOf("server", "email", "password", "sign in"), mark = true, what = "the sign-in")
    }

    @Test
    fun jellyfinsFormIsTheSameColumn() {
        show {
            MediaServerSignInScreen(
                SignInPage.MediaServer(MediaServerKind.JELLYFIN, quickConnect = true),
                "", "", onUsername = {}, onPassword = {}, onSubmit = {}, onQuickConnect = {}, onBack = {},
            )
        }
        await("quick connect")
        assertColumn(listOf("username", "media password", "media sign in", "quick connect"), mark = false, what = "Jellyfin's form")
    }

    // ---- errors as words under their fields -------------------------------------------------------------------------

    /**
     * [error] just under [field] (no more than 12 dp below its foot, starting within its width), and nothing behind the
     * words: the commonest colour of the error's box is the page's black, not a row's or a card's grey.
     */
    private fun assertUnder(error: String, field: String, image: Bitmap) {
        val words = bounds(error)
        val above = bounds(field)
        assertTrue("$error starts under $field (${words.top} under ${above.bottom})", words.top >= above.bottom - 0.5f)
        assertTrue("$error is just under $field (${words.top - above.bottom} px)", words.top - above.bottom <= px(12.dp))
        assertTrue("$error starts within $field's width", words.left >= above.left && words.left < above.right)
        val ground = commonest(image, words)
        assertEquals("behind $error: ${hex(ground)}, the page's black expected", 0xFF000000.toInt(), ground)
    }

    @Test
    fun everyErrorIsRedWordsJustUnderItsFieldWithNothingBehindThem() {
        signIn(Screen.SignIn(problems = setOf(SignInProblem.SERVER, SignInProblem.EMAIL, SignInProblem.PASSWORD)))
        await("password error")
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        assertUnder("server error", "server", image)
        assertUnder("email error", "email", image)
        assertUnder("password error", "password", image)
        // and the words are red: some of the error's pixels are red-400 (Tokens.ErrorText)
        assertTrue("the error's words are red", count(image, bounds("password error")) { near(it, 0xFFF87171.toInt(), 40) } > 0)
    }

    @Test
    fun aFailureIsRedWordsUnderSignInWithNothingBehindThem() {
        signIn(Screen.SignIn(failure = SignInFailure.WRONG_CREDENTIALS, serverTitle = "Seerr"))
        await("sign-in error")
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        assertUnder("sign-in error", "sign in", image)
    }

    // ---- one prominent button per screen ---------------------------------------------------------------------------

    /**
     * Every indigo pixel on the screen lies inside [button] (its box grown by the focus scale's 10%), and there are
     * some: the screen's one prominent button. Seerr's indigo-600, `Tokens.Accent`, within a little for blending.
     */
    private fun assertOneProminent(button: String, what: String) {
        rule.waitForIdle()
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val box = bounds(button).let { Rect(it.left - it.width * 0.05f - 2, it.top - it.height * 0.05f - 2, it.right + it.width * 0.05f + 2, it.bottom + it.height * 0.05f + 2) }
        var inside = 0
        val outside = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until image.height step 2) for (x in 0 until image.width step 2) {
            if (!near(image.getPixel(x, y), INDIGO, 10)) continue
            if (box.contains(androidx.compose.ui.geometry.Offset(x.toFloat(), y.toFloat()))) inside++ else outside += x to y
        }
        assertTrue("$what: $button is indigo ($inside pixels)", inside > 50)
        assertTrue("$what: indigo outside $button, at ${outside.take(5)} (${outside.size} pixels)", outside.isEmpty())
    }

    @Test
    fun oneProminentButtonOnTheSignInWithTheOtherWaysIn() {
        signIn(Screen.SignIn(offer = plexOffer, serverTitle = "Seerr", problems = setOf(SignInProblem.EMAIL)))
        await("plex")
        assertOneProminent("sign in", "the sign-in")
    }

    @Test
    fun oneProminentButtonOnJellyfinsForm() {
        show {
            MediaServerSignInScreen(
                SignInPage.MediaServer(MediaServerKind.JELLYFIN, quickConnect = true, failure = SignInFailure.WRONG_CREDENTIALS),
                "", "", onUsername = {}, onPassword = {}, onSubmit = {}, onQuickConnect = {}, onBack = {},
            )
        }
        await("quick connect")
        assertOneProminent("media sign in", "Jellyfin's form")
    }

    @Test
    fun oneProminentButtonOnACodeThatRanOut() {
        show {
            CodeSignInScreen(
                SignInPage.Code(CodeKind.PLEX, code = "X7K6", state = CodeState.EXPIRED, failure = SignInFailure.CODE_EXPIRED),
                onNewCode = {}, onCancel = {},
            )
        }
        await("cancel")
        assertOneProminent("new code", "a code that ran out")
    }

    // ---- the approver's sign-in -------------------------------------------------------------------------------------

    private class Memory : AdminStorage {
        @Volatile var admin: KeptAdmin? = null
        override suspend fun admin() = admin
        override suspend fun save(admin: KeptAdmin) {
            this.admin = admin
        }
        override suspend fun clear() {
            admin = null
        }
    }

    private val gateway = AdminGateway(Memory(), hash = { PinHash.of(it, iterations = 1_000) }, engine = {
        MockEngine { respond("""{"message":"Access denied."}""", HttpStatusCode.Forbidden, headersOf("Content-Type", "application/json")) }
    })

    @Test
    fun theApproversSignInIsTheSameColumnWithOneProminentButton() {
        show {
            val scope = rememberCoroutineScope()
            val setup = remember { AdminSetup(gateway, User(id = 8, displayName = "Living room TV", permissions = 32), "http://seerr.test:5055", scope) }
            AdminScreen(setup, onBack = {})
        }
        await("admin sign in")
        rule.waitUntil(5_000) { runCatching { rule.onNode(isFocused()).fetchSemanticsNode().config[SemanticsProperties.TestTag] == "admin email" }.getOrDefault(false) }
        assertColumn(listOf("admin email", "admin password", "admin sign in"), mark = false, what = "the approver's sign-in")
        assertOneProminent("admin sign in", "the approver's sign-in")
    }

    // ---- pixels -----------------------------------------------------------------------------------------------------

    private fun count(image: Bitmap, box: Rect, test: (Int) -> Boolean): Int {
        var n = 0
        for (y in box.top.toInt().coerceAtLeast(0) until box.bottom.toInt().coerceAtMost(image.height))
            for (x in box.left.toInt().coerceAtLeast(0) until box.right.toInt().coerceAtMost(image.width))
                if (test(image.getPixel(x, y))) n++
        return n
    }

    private fun commonest(image: Bitmap, box: Rect): Int {
        val seen = HashMap<Int, Int>()
        for (y in box.top.toInt().coerceAtLeast(0) until box.bottom.toInt().coerceAtMost(image.height))
            for (x in box.left.toInt().coerceAtLeast(0) until box.right.toInt().coerceAtMost(image.width))
                seen.merge(image.getPixel(x, y), 1, Int::plus)
        return seen.maxByOrNull { it.value }?.key ?: 0
    }

    private fun near(c: Int, to: Int, within: Int) = (0..2).all { abs(((c ushr (it * 8)) and 0xFF) - ((to ushr (it * 8)) and 0xFF)) <= within }

    private fun hex(c: Int) = "#%08X".format(c)

    private companion object {
        const val INDIGO = 0xFF4F46E5.toInt()
    }
}
