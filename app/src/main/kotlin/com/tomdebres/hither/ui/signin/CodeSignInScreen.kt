package com.tomdebres.hither.ui.signin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.R
import com.tomdebres.hither.core.rules.MediaServerKind
import com.tomdebres.hither.session.CodeKind
import com.tomdebres.hither.session.CodeState
import com.tomdebres.hither.session.SignInPage
import com.tomdebres.hither.ui.theme.HitherType
import com.tomdebres.hither.ui.theme.Metrics
import com.tomdebres.hither.ui.theme.Page
import com.tomdebres.hither.ui.theme.PageKind
import com.tomdebres.hither.ui.theme.PlainButton
import com.tomdebres.hither.ui.theme.ProminentButton
import com.tomdebres.hither.ui.theme.Spinner
import com.tomdebres.hither.ui.theme.StateKind
import com.tomdebres.hither.ui.theme.StateView
import com.tomdebres.hither.ui.theme.Tokens

/**
 * A code to enter or approve elsewhere (M8), read from across the room: Plex's at plex.tv/link, or Jellyfin's Quick
 * Connect. A pushed page as the Apple TV app's (tvOS's `CodeSignInView`): the server's name ([serverTitle]) as its
 * title, then the way's heading ("Sign in with Plex", "Quick Connect"), where to go, the code (80 sp, M8's size for ten
 * feet) in a box of a form row's grey, Hither's spinner with "Waiting for the code to be entered…", and Cancel, which
 * has focus. A code that ran out or could not be had is a `StateView`: what happened, Get a New Code (or Try Again),
 * the page's one prominent button and focused, and Cancel. Cancel and Back cancel the wait.
 */
@Composable
fun CodeSignInScreen(
    page: SignInPage.Code,
    onNewCode: () -> Unit,
    onCancel: () -> Unit,
    /** The server's name, the page's title (tvOS's); none, no title. */
    serverTitle: String? = null,
) {
    val cancel = remember { FocusRequester() }
    val again = remember { FocusRequester() }
    val stuck = page.state == CodeState.EXPIRED || page.state == CodeState.FAILED
    val plex = page.kind == CodeKind.PLEX

    BackHandler { onCancel() }
    LaunchedEffect(stuck) {
        withFrameNanos { }
        (if (stuck) again else cancel).requestFocus()
    }

    Page(PageKind.Pushed(serverTitle, titleTag = "page title")) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = if (serverTitle == null) Metrics.TopMargin + Metrics.EntryTop else 0.dp, bottom = Metrics.BottomMargin),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                stringResource(if (plex) R.string.plex_heading else R.string.quick_connect_heading),
                modifier = Modifier.semantics { heading() }.testTag("code heading"),
                style = HitherType.Title.copy(color = Tokens.Text),
            )
            if (stuck) {
                val failure = page.failure?.message(if (plex) MediaServerKind.PLEX else MediaServerKind.JELLYFIN).orEmpty()
                val expired = page.state == CodeState.EXPIRED
                // In the middle of the room the heading leaves, as tvOS's state is.
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                StateView(
                    StateKind.FAILED,
                    words = if (expired) stringResource(R.string.code_expired) else failure,
                    detail = if (expired) failure else null,
                    wordsTag = if (expired) "code trouble" else "code failure",
                    detailTag = "code failure",
                    focusOnArrival = false,
                ) {
                    ProminentButton(
                        text = stringResource(if (expired) R.string.code_new else R.string.code_try_again),
                        onClick = onNewCode,
                        modifier = Modifier.focusRequester(again).testTag("new code"),
                    )
                    PlainButton(
                        text = stringResource(R.string.code_cancel),
                        onClick = onCancel,
                        modifier = Modifier.focusRequester(cancel).testTag("cancel"),
                    )
                }
                }
            } else {
                Waiting(page, cancel, onCancel)
            }
        }
    }
}

/** Where to go, the code, the waiting line and Cancel, 20 dp apart (tvOS's 40 points). */
@Composable
private fun Waiting(page: SignInPage.Code, cancel: FocusRequester, onCancel: () -> Unit) {
    val plex = page.kind == CodeKind.PLEX
    Column(
        Modifier.padding(top = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (plex) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText(stringResource(R.string.plex_go_to), style = HitherType.Body.copy(color = Tokens.TextSecondary))
                BasicText(stringResource(R.string.plex_link), modifier = Modifier.testTag("plex link"), style = HitherType.Display.copy(color = Tokens.Text))
                BasicText(stringResource(R.string.plex_enter), style = HitherType.Body.copy(color = Tokens.TextSecondary))
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(stringResource(R.string.quick_connect_enter), style = HitherType.Subtitle.copy(color = Tokens.Text))
                BasicText(
                    page.approveAt?.let { stringResource(R.string.quick_connect_where_at, it) } ?: stringResource(R.string.quick_connect_where),
                    modifier = Modifier.widthIn(max = Metrics.EntryWidth).testTag("approve where"),
                    style = HitherType.Body.copy(color = Tokens.TextSecondary, textAlign = TextAlign.Center),
                )
            }
        }
        CodeBox(page.code)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spinner(Modifier.size(16.dp).testTag("waiting spinner"))
            Spacer(Modifier.width(10.dp))
            BasicText(
                stringResource(
                    when {
                        page.code == null -> R.string.code_getting
                        plex -> R.string.plex_waiting
                        else -> R.string.quick_connect_waiting
                    },
                ),
                modifier = Modifier.testTag("waiting"),
                style = HitherType.Body.copy(color = Tokens.TextSecondary),
            )
        }
        PlainButton(
            text = stringResource(R.string.code_cancel),
            onClick = onCancel,
            modifier = Modifier.widthIn(min = 140.dp).focusRequester(cancel).testTag("cancel"),
        )
    }
}

/**
 * The code itself, for reading across a room: [HitherType.Code], in a box of a form row's grey (tvOS's `CodeBox`). A
 * Quick Connect code's six digits as two threes; Plex's four as they are. A screen reader spells it. The box keeps its
 * size while the code is on its way.
 */
@Composable
private fun CodeBox(code: String?) {
    val described = code?.let { stringResource(R.string.code_described, it.toList().joinToString(" ")) }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Tokens.Field)
            .padding(horizontal = 32.dp, vertical = 10.dp)
            .testTag("code")
            // Read out one character at a time, so a screen reader spells it.
            .clearAndSetSemantics { if (described != null) contentDescription = described },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = code?.let(::spaced) ?: "",
            style = HitherType.Code.copy(color = Tokens.Text),
            modifier = if (code == null) Modifier.width(240.dp) else Modifier,
        )
    }
}

/** A Quick Connect code's six digits as two threes, easier to read across a room; Plex's four as they are. */
private fun spaced(code: String): String = if (code.length == 6 && code.all(Char::isDigit)) code.take(3) + " " + code.drop(3) else code
