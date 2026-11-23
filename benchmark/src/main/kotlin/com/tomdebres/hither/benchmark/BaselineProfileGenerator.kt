package com.tomdebres.hither.benchmark

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "com.tomdebres.hither"

/** fake-seerr's titles the walk opens by their links: a film nothing has been asked for, with a collection and an
 * overview long enough for "More"; a series partly in the library, with seasons to ask for. */
private const val FILM = "hither://title/movie/1381071"
private const val SERIES = "hither://title/tv/1877"

/**
 * Generates Hither's baseline profile: a cold start to Discover, then browsing the rows; a lockup's title page and
 * back; a film's page by its link (its actions, the request question closed with Back, the overview's "More", its
 * shelves); a series' page (its request panel, closed unsent); search; My Requests' split; the Movies page (from
 * Popular Movies' See More) with its sort list and the filters' panel; and Settings, About, Privacy and Diagnostics,
 * as a remote does (DECISIONS 58, 64, 66, 102, 112; every path since the redesign, A9).
 * Shield-launcher's pattern.
 *
 * Run with scripts/fake-seerr on the host and only an API 33+ device connected (the Shield is
 * API 30 and tv31 a user build; bp35 can):
 *
 *     scripts/fake-seerr &        # port 5056
 *     ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateBaselineProfile
 *
 * The profile lands in app/src/main/generated/baselineProfiles. The server is the fake one at
 * the emulator's address for the host, port 5056, with its made-up account: run a fake there for
 * the profile's window. `-Pandroid.testInstrumentationRunnerArguments.server=...` does not reach
 * this module's run through `:app:generateBaselineProfile` (A3 found the walk signing in to 5056
 * whatever it was given). Never a real server: nothing personal may reach a profile that is
 * committed.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        // Each part from a fresh start on Discover (A9): an iteration that began where the last had left the app (the
        // process kept, on the bar's avatar) walked its keys from the wrong place.
        pressHome()
        killProcess()
        startActivityAndWait()
        device.signInIfAsked(server())
        device.awaitDiscover()
        device.browseDiscover()
        device.openALockup()
        // A title by its link opens over Hither, and Back from it leaves the app.
        openLink(FILM)
        device.browseFilm()
        openLink(SERIES)
        device.browseSeries()
        freshDiscover()
        device.search()
        freshDiscover()
        device.myRequests()
        device.browseMovies()
        device.settings()
    }
}

/** Hither started afresh on Discover, its rows in: wherever the last part left it, the next walks from here. */
private fun MacrobenchmarkScope.freshDiscover() {
    killProcess()
    startActivityAndWait()
    device.awaitDiscover()
}

/** A title's page by its link, as a home-screen card opens it (M4): Hither's activity, named. */
private fun MacrobenchmarkScope.openLink(link: String) {
    startActivityAndWait(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClassName(PACKAGE, "$PACKAGE.MainActivity"))
}

private fun server(): String =
    InstrumentationRegistry.getArguments().getString("server") ?: "http://10.0.2.2:5056"

/**
 * The sign-in form, on the first run only (the account is kept between runs), as a remote and its keyboard fill it
 * (A3: since A1 and A7 the fields' words set through accessibility, and a touch on tv-material's Sign In, no longer
 * signed in): Select opens the server field's keyboard; each value typed, Enter goes on to the next field, Done on the
 * password to Sign In; then Select. scripts/fake-seerr takes any email with the password "right-password": made up,
 * never a real account.
 */
private fun UiDevice.signInIfAsked(server: String) {
    if (!wait(Until.hasObject(By.clazz("android.widget.EditText")), 10_000)) return
    Thread.sleep(1_500)
    presses(1, 1_200) { pressDPadCenter() }
    for (value in listOf(server, "tv@example.invalid", "right-password")) {
        executeShellCommand("input text $value")
        Thread.sleep(800)
        presses(1, 1_200) { pressEnter() }
    }
    // Done on the password leaves focus on Sign In.
    check(wait(Until.hasObject(focusedOn(By.text("Sign In"))), 5_000)) { "focus is not on Sign In after the form" }
    presses(1, 800) { pressDPadCenter() }
}

/**
 * The focused control that carries [what] itself or a few levels under it: a tv-material control's words sit on an
 * unfocused node of the same bounds under the focused one, and as a screen arrives the whole Compose view says it is
 * focused, with everything far under it (A3).
 */
private fun focusedOn(what: BySelector): BySelector = By.focused(true).hasDescendant(what, 3)

/** Focus is on the control described [desc], on its own node or a few levels under it. */
private fun UiDevice.focusIsOn(desc: String): Boolean = hasObject(By.focused(true).desc(desc)) || hasObject(focusedOn(By.desc(desc)))

private fun UiDevice.awaitDiscover() {
    // Trending is on screen whether Recent Requests heads the page or not (DECISIONS 84); Popular Movies
    // can be below it, not yet composed.
    check(wait(Until.hasObject(By.text("Trending")), 30_000)) { "Discover did not show its rows" }
    // The first row's cards; focus has landed on Recent Requests' newest (the fake's account has three
    // requests) or on Trending's first card, and the walk below goes down through both.
    Thread.sleep(2_000)
}

/**
 * What a user does on Discover with a remote: along a row (which pages in more as the end
 * nears), down a row at a time to the last, back along and up, to the bar and down again.
 */
private fun UiDevice.browseDiscover() {
    presses(12) { pressDPadRight() }
    presses(1, 800) { pressDPadDown() }
    presses(10) { pressDPadRight() }
    presses(1, 800) { pressDPadDown() }
    presses(8) { pressDPadRight() }
    presses(1, 800) { pressDPadDown() }
    presses(8) { pressDPadRight() }
    // Held keys: the remote repeats about every 50 ms.
    presses(8, 60) { pressDPadLeft() }
    presses(3, 800) { pressDPadUp() }
    presses(1, 800) { pressDPadUp() } // the bar
    presses(4, 300) { pressDPadRight() }
    presses(4, 300) { pressDPadLeft() }
    presses(1, 800) { pressDPadDown() }
    presses(6, 60) { pressDPadRight() }
    Thread.sleep(1_000)
}

private fun UiDevice.presses(times: Int, gap: Long = 400, press: UiDevice.() -> Boolean) = repeat(times) {
    press()
    Thread.sleep(gap)
}

/** Select on the lockup the walk ended on (Discover's start-up hand-off and a page pushed from a row), and Back. */
private fun UiDevice.openALockup() {
    presses(1, 2_500) { pressDPadCenter() }
    presses(2, 500) { pressDPadRight() }
    presses(1, 1_500) { pressBack() }
}

/**
 * A film's page as a remote reads it (A4, A5): along its actions; the request question opened and closed with Back
 * (Request has focus, Down is Cancel: nothing is ever requested here); down to the overview and its "More", the whole
 * overview turned a page and closed; down through Cast & Crew, the collection, Recommendations, Similar and the
 * keywords, along each; back up; then Back, which leaves the app (a linked page).
 */
private fun UiDevice.browseFilm() {
    check(wait(Until.hasObject(By.text("Godzilla Minus Zero")), 15_000)) { "the film's page did not open from its link" }
    check(wait(Until.hasObject(focusedOn(By.text("Request"))), 10_000)) { "focus is not on the film's Request" }
    presses(2, 500) { pressDPadRight() }
    presses(2, 500) { pressDPadLeft() }
    check(wait(Until.hasObject(focusedOn(By.text("Request"))), 5_000)) { "focus did not come back to the film's Request" }
    presses(1, 1_500) { pressDPadCenter() } // the question, Request focused
    check(wait(Until.hasObject(By.textStartsWith("Request Godzilla")), 5_000)) { "the film's request question did not open" }
    presses(1, 600) { pressDPadDown() } // Cancel
    presses(1, 1_200) { pressBack() } // closed, nothing requested
    presses(1, 800) { pressDPadDown() } // the overview
    if (hasObject(By.text("More"))) {
        presses(1, 1_500) { pressDPadCenter() } // the whole overview
        presses(2, 800) { pressDPadDown() }
        presses(1, 1_200) { pressBack() }
    }
    for (shelf in 0 until 5) {
        presses(1, 900) { pressDPadDown() }
        presses(4, 350) { pressDPadRight() }
        presses(4, 60) { pressDPadLeft() }
    }
    presses(6, 400) { pressDPadUp() }
    presses(1, 1_500) { pressBack() }
}

/**
 * A series' page (A4, A5): the request panel from its button, Down along its season rows and Back (closed unsent:
 * nothing is ticked or sent here); down to the seasons' shelf and along it; Back, which leaves the app.
 */
private fun UiDevice.browseSeries() {
    check(wait(Until.hasObject(By.text("Phineas and Ferb")), 15_000)) { "the series' page did not open from its link" }
    check(wait(Until.hasObject(focusedOn(By.textStartsWith("Request"))), 10_000)) { "focus is not on the series' request button" }
    presses(1, 1_800) { pressDPadCenter() } // the panel
    check(wait(Until.hasObject(By.text("Request Series")), 5_000)) { "the series' request panel did not open" }
    presses(4, 500) { pressDPadDown() }
    presses(2, 400) { pressDPadUp() }
    presses(1, 1_500) { pressBack() } // closed unsent
    presses(2, 900) { pressDPadDown() } // the overview, then the seasons' shelf
    presses(3, 400) { pressDPadRight() }
    presses(1, 900) { pressDPadDown() } // Cast & Crew
    presses(3, 400) { pressDPadRight() }
    presses(3, 600) { pressDPadUp() }
    presses(1, 1_500) { pressBack() }
}

/**
 * Search from the bar (M2, DECISIONS 64; its icon since A2, 312): the field's words set through accessibility as the
 * sign-in's are, the results, down into the grid and along it, a title's page and back, back to the field and to
 * Discover.
 */
private fun UiDevice.search() {
    // Up to the bar, Left to Discover's tab (fenced there), then Right to the search icon and Select: the bar's
    // controls are tv-material's (A2), which answer the D-pad, not a touch (A3).
    presses(5, 600) { pressDPadUp() } // to the bar from whichever row (Up on the bar stays there)
    presses(4, 300) { pressDPadLeft() }
    // Discover, Requests, then search (the fake's account has no Issues tab); each tab's page shows as focus passes
    presses(2, 1_000) { pressDPadRight() }
    presses(1, 2_000) { pressDPadCenter() }
    val field = wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000) ?: error("no search field: the walk did not open search from the bar")
    field.text = "star wars"
    check(wait(Until.hasObject(By.desc("Star Wars: Visions")), 10_000)) { "no search results" }
    presses(1, 800) { pressBack() } // the keyboard
    presses(1, 800) { pressDPadDown() }
    presses(4, 400) { pressDPadRight() }
    presses(1, 800) { pressDPadDown() }
    presses(1, 2_000) { pressDPadCenter() }
    presses(1, 1_500) { pressBack() }
    presses(1, 800) { pressBack() } // to the field
    presses(1, 1_500) { pressBack() } // to Discover
}

/**
 * My Requests from the bar (M3, DECISIONS 66; the Library split since A6, 338): to the bar's Requests; down to the side
 * list's My Requests, right into the lockups and along them, up to the page's own bar and back (149); a request's title
 * and back to its lockup; back to the bar's Requests. The fake's account has its three recorded requests, one line of
 * lockups; nothing is requested here.
 */
private fun UiDevice.myRequests() {
    // To the bar from wherever Discover is (a row's card, or the search pill), then along it to
    // Requests: it is fenced at Discover, so five Lefts and one Right land there either way (Movies and
    // Series left the bar for their rows' See More, DECISIONS 112).
    presses(1, 800) { pressDPadUp() }
    presses(5, 300) { pressDPadLeft() }
    presses(1, 500) { pressDPadRight() }
    presses(1, 2_500) { pressDPadCenter() }
    check(wait(Until.hasObject(By.desc("The Good Lawyer")), 10_000)) { "My Requests did not show its rows" }
    presses(1, 600) { pressDPadDown() } // My Requests, the side list's chosen row (A6)
    presses(1, 600) { pressDPadRight() } // into the lockups, on the newest
    presses(2, 600) { pressDPadRight() }
    presses(2, 600) { pressDPadLeft() }
    presses(1, 600) { pressDPadUp() } // the page's bar, on Requests (DECISIONS 149)
    presses(1, 600) { pressDPadDown() } // My Requests
    presses(1, 600) { pressDPadRight() } // the lockup left
    presses(1, 2_000) { pressDPadCenter() }
    presses(1, 1_500) { pressBack() } // to the lockup
    presses(1, 1_500) { pressBack() } // to the bar
}

/**
 * The Movies page from Popular Movies' See More (M6, DECISIONS 102, 112): along that row to its end, the
 * page's grid along and down, up to the sort and its list opened and closed with Back (nothing is changed),
 * the filters' panel and the language's list in it opened and closed (A8), and back to the card.
 */
private fun UiDevice.browseMovies() {
    // On the bar's Requests after My Requests: Back to Discover's tab and its page (A2, 313), down through
    // Recent Requests and Trending to Popular Movies (the fake's Discover settings, its account without
    // Recent View), and along it to See More.
    presses(1, 1_500) { pressBack() }
    presses(3, 800) { pressDPadDown() }
    // Along the row until See More has focus: since A3 the focused node is the lockup's card, which carries the
    // description (before, the description was a child's).
    var presses = 0
    while (!focusIsOn("See More Movies")) {
        check(presses++ < 30) { "the walk did not reach Popular Movies' See More" }
        pressDPadRight()
        Thread.sleep(400)
    }
    presses(1, 2_500) { pressDPadCenter() }
    check(wait(Until.hasObject(By.text("Most Popular")), 10_000)) { "the Movies page did not show its sort" }
    Thread.sleep(1_500)
    presses(5, 400) { pressDPadRight() }
    presses(2, 800) { pressDPadDown() }
    presses(5, 60) { pressDPadLeft() }
    presses(3, 800) { pressDPadUp() }
    presses(1, 1_500) { pressDPadCenter() } // the sort list
    presses(2, 400) { pressDPadDown() }
    presses(1, 1_000) { pressBack() }
    // The filters in the side panel (A8): its rows, the language's list opened and closed, then the panel closed, nothing
    // set (so nothing is read again).
    presses(1, 800) { pressDPadRight() }
    presses(1, 2_000) { pressDPadCenter() }
    presses(2, 600) { pressDPadDown() }
    presses(1, 1_500) { pressDPadCenter() }
    presses(1, 1_000) { pressBack() }
    presses(1, 1_000) { pressBack() }
    presses(1, 1_500) { pressBack() } // to Discover, on See More
}

/**
 * Settings from the bar's avatar (A7): its rows; About (focus on Privacy), Privacy's one reading view turned a page at a
 * time and closed, Diagnostics and back; back to the bar. Sign Out is never pressed.
 */
private fun UiDevice.settings() {
    presses(5, 600) { pressDPadUp() } // to the bar
    presses(4, 300) { pressDPadLeft() } // Discover's tab, fenced there
    presses(3, 600) { pressDPadRight() } // Requests, search, the avatar
    presses(1, 2_000) { pressDPadCenter() }
    check(wait(Until.hasObject(By.text("About")), 10_000)) { "Settings did not open from the avatar" }
    presses(3, 500) { pressDPadDown() }
    presses(3, 500) { pressDPadUp() }
    // About: focus arrives on Privacy
    while (!hasObject(focusedOn(By.text("About")))) {
        if (!hasObject(By.text("About"))) error("About is not on Settings")
        pressDPadDown()
        Thread.sleep(400)
        if (hasObject(focusedOn(By.text("Sign Out")))) error("the walk reached Sign Out looking for About")
    }
    presses(1, 2_000) { pressDPadCenter() }
    check(wait(Until.hasObject(focusedOn(By.text("Privacy"))), 10_000)) { "About did not arrive on Privacy" }
    presses(1, 2_000) { pressDPadCenter() }
    presses(3, 900) { pressDPadDown() } // Privacy's pages
    presses(1, 1_200) { pressBack() }
    presses(1, 600) { pressDPadDown() } // Diagnostics
    presses(1, 2_000) { pressDPadCenter() }
    presses(1, 1_200) { pressBack() }
    presses(1, 1_200) { pressBack() } // Settings
    presses(1, 1_500) { pressBack() } // the bar
}
