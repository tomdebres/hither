package com.tomdebres.hither

import com.tomdebres.hither.data.TmdbAddress
import com.tomdebres.hither.ui.components.keyboardAsInset
import com.tomdebres.hither.ui.signin.StartingScreen
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.core.rules.parseTitleLink
import com.tomdebres.hither.session.Screen
import com.tomdebres.hither.session.AdminAccess
import com.tomdebres.hither.session.AdminCalls
import com.tomdebres.hither.session.SeerrAccess
import com.tomdebres.hither.session.SessionViewModel
import com.tomdebres.hither.ui.components.DpadHost
import com.tomdebres.hither.ui.home.HomeScreen
import com.tomdebres.hither.ui.home.HomeSide
import com.tomdebres.hither.ui.home.UnavailableScreen
import com.tomdebres.hither.ui.signin.SignInScreen
import com.tomdebres.hither.ui.signin.MediaServerSignInScreen
import com.tomdebres.hither.ui.signin.CodeSignInScreen
import com.tomdebres.hither.session.SignInPage
import com.tomdebres.hither.data.PlexAddress
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * The single activity: LEANBACK_LAUNCHER. Sign-in on first run, then Discover. A card on the TV's
 * home screen starts it with a title's link (`hither://title/movie/550`, M4): the title's page opens,
 * at a cold start too once the session is up, and Back from it leaves to the home screen.
 */
class MainActivity : ComponentActivity() {
    private val session: SessionViewModel by viewModels {
        SessionViewModel.Factory((application as HitherApplication).gateway)
    }

    /** A title a home-screen card asked for, until the signed-in screen opens it. */
    private var link by mutableStateOf<TitleCard?>(null)

    /** Started by `scripts/measure-discover`: Discover's focus arrives on Trending (DECISIONS 90). */
    private var startOnTrending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keyboardAsInset()
        val app = application as HitherApplication
        // Debug builds only: a test's or a scripted run's fake plex.tv (M8) and TMDB stand-in (0.7.1). The release build's
        // PlexAddress and TmdbAddress ignore them.
        PlexAddress.take(this, intent)
        TmdbAddress.take(this, intent)
        // Not again when the activity is only being recreated: the page was opened the first time.
        if (savedInstanceState == null) take(intent)
        startOnTrending = intent?.getBooleanExtra(EXTRA_START_ON_TRENDING, false) == true
        val side = HomeSide(
            // On the app's scope, so leaving the screen mid-look does not cut the look short.
            front = { app.scope.async { app.ready.front() }.await() },
            onRequestsOpened = { app.scope.launch { app.ready.requestsOpened() } },
            onTitleOpened = { key -> app.scope.launch { app.ready.opened(key) } },
            onLeave = { moveTaskToBack(true) },
        )
        setContent { HitherApp(session, app.gateway, link, onLinkTaken = { link = null }, side = side, startOnTrending = startOnTrending, admin = app.admin, adminCalls = app.admin) }
    }

    /**
     * Every key press puts the admin's idle clock back to zero (DECISIONS 93). Android calls this for
     * each key, touch or trackball event dispatched to the activity, before the event goes on.
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        (application as HitherApplication).admin.key()
    }

    /**
     * Hither leaving the front is Hither closed, for the admin's guard (Tom, DECISIONS 74, read in 93):
     * Home and Back both close an app for the family, so the TV is the family's again.
     */
    override fun onStop() {
        super.onStop()
        (application as HitherApplication).admin.closed()
        // A sign-in code's wait (plex.tv's, or Quick Connect's) ends when Hither leaves the front (M8).
        session.leftFront()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        take(intent)
    }

    /** A home-screen card's link, with the title's name and poster as kept for the card standing in. */
    private fun take(intent: Intent?) {
        val title = parseTitleLink(intent?.dataString) ?: return
        val app = application as HitherApplication
        lifecycleScope.launch {
            val art = runCatching { app.ready.art(title.key) }.getOrNull()
            link = TitleCard(title.key, title.tmdbId, title.type, art?.title.orEmpty(), art?.year, art?.poster, badge = null)
        }
    }

    companion object {
        /**
         * A launch extra for `scripts/measure-discover`: Discover's focus arrives on Trending's first
         * card whatever rows lie above it (Recent Requests, when the account has requests), so every
         * build is measured over the same path (DECISIONS 90). It changes nothing else.
         */
        const val EXTRA_START_ON_TRENDING = "com.tomdebres.hither.START_ON_TRENDING"
    }
}

@Composable
fun HitherApp(
    session: SessionViewModel,
    access: SeerrAccess,
    link: TitleCard? = null,
    onLinkTaken: () -> Unit = {},
    side: HomeSide = HomeSide(),
    startOnTrending: Boolean = false,
    admin: AdminAccess? = null,
    adminCalls: AdminCalls? = null,
) = DpadHost {
    when (val screen = session.screen) {
        // The stacked logo, and a spinner once it has taken half a second (the polish audit's item 26).
        Screen.Starting -> StartingScreen()
        is Screen.SignIn -> when (val page = screen.page) {
            null -> SignInScreen(
                state = screen,
                server = session.server,
                email = session.email,
                password = session.password,
                onServer = session::onServer,
                onEmail = session::onEmail,
                onPassword = session::onPassword,
                onSubmit = session::submit,
                onServerDone = session::onServerDone,
                onPlex = session::openPlex,
                onMediaServer = session::openMediaServer,
            )
            // M8: the media server's own form, and the codes entered or approved elsewhere.
            is SignInPage.MediaServer -> MediaServerSignInScreen(
                page = page,
                username = session.username,
                password = session.mediaPassword,
                onUsername = session::onUsername,
                onPassword = session::onMediaPassword,
                onSubmit = session::submitMediaServer,
                onQuickConnect = session::openQuickConnect,
                onBack = session::closePage,
                serverTitle = screen.offer?.title ?: screen.serverTitle,
            )
            is SignInPage.Code -> CodeSignInScreen(page, onNewCode = session::newCode, onCancel = session::closePage, serverTitle = screen.offer?.title ?: screen.serverTitle)
        }
        is Screen.Home -> HomeScreen(screen, access, onSignOut = session::signOut, link = link, onLinkTaken = onLinkTaken, side = side, startOnTrending = startOnTrending, admin = admin, adminCalls = adminCalls)
        is Screen.Unavailable -> UnavailableScreen(screen, onRetry = session::retry, onSignOut = session::signOut)
    }
}
