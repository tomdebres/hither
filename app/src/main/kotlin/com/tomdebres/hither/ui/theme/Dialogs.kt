package com.tomdebres.hither.ui.theme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.R
import com.tomdebres.hither.ui.components.FocusOnArrival
import kotlinx.coroutines.delay

/**
 * A question before something that costs typing with the remote to undo (H2's X6; tvOS's alert): Sign Out, Remove Admin
 * Account, Cancel Request, Decline. tv-material has no dialog, so Hither draws it in the window, as tvOS draws its
 * alert: the page darkened under it, the [title], the [message], and the buttons stacked, [confirm] first in red words
 * when [destructive] (rule 2), [dismiss] under it with focus, so a stray Select never confirms (DECISIONS 80).
 *
 * Focus stays inside while it is [open]; Back is Cancel; and when it closes, focus goes back to [opener], the control
 * that opened it, when that is still there (DECISIONS 297). Kept composed while closed, so it can hand focus back.
 */
@Composable
fun ConfirmDialog(
    open: Boolean,
    title: String,
    message: String?,
    confirm: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    opener: FocusRequester?,
    tag: String,
    cancelTag: String,
    confirmTag: String = "confirm",
    destructive: Boolean = true,
    /** The way out's words: Cancel, unless "Cancel" is what is being asked (Cancel Request's "Keep Request"). */
    dismiss: String = stringResource(R.string.cancel),
    /**
     * Focus on [confirm] rather than the way out: where Select on the page was already the asking and nothing is lost
     * (a film's request, A5; tvOS's alert focuses Request). Never with [destructive].
     */
    focusConfirm: Boolean = false,
) {
    ReturnFocus(open, opener)
    if (!open) return
    val cancel = remember { FocusRequester() }
    val ask = remember { FocusRequester() }
    FocusOnArrival(if (focusConfirm && !destructive) ask else cancel)
    BackHandler { onCancel() }
    DialogColumn(title, message, tag) {
        PlainButton(
            text = confirm,
            onClick = onConfirm,
            destructive = destructive,
            modifier = Modifier.fillMaxWidth().focusRequester(ask).focusProperties { up = FocusRequester.Cancel }.testTag(confirmTag),
        )
        PlainButton(
            text = dismiss,
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().focusRequester(cancel).focusProperties { down = FocusRequester.Cancel }.testTag(cancelTag),
        )
    }
}

/**
 * Something went wrong that the person should know (H2's X6; tvOS's `problemAlert`): the [title], the [message], and OK,
 * focused. Back is OK; focus stays inside while it is [open] and goes back to [opener] after. tv-material has none, so
 * it is drawn as [ConfirmDialog] is. A1 brought it; since A5 every failure is one ([ProblemAlert]): a request, an issue,
 * a cancel, a decision, an app that would not open. What went through shows in place, never as a toast.
 */
@Composable
fun ProblemDialog(
    open: Boolean,
    title: String,
    message: String?,
    onDismiss: () -> Unit,
    opener: FocusRequester?,
    tag: String = "problem",
) {
    ReturnFocus(open, opener)
    if (!open) return
    val ok = remember { FocusRequester() }
    FocusOnArrival(ok)
    BackHandler { onDismiss() }
    DialogColumn(title, message, tag) {
        PlainButton(
            text = stringResource(R.string.ok),
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(ok)
                .focusProperties {
                    up = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                }
                .testTag("problem ok"),
        )
    }
}

/** What went wrong, for a [ProblemAlert]: the title and the sentence. Each is its own, however alike two are. */
class Problem(val title: String, val message: String)

/** How long a [ProblemAlert] waits for what was closing to have gone (tvOS's `problemAlert`, DECISIONS 273's 600 ms). */
const val PROBLEM_AFTER_MS = 600L

/**
 * [problem] as a [ProblemDialog], once whatever was on screen as it went wrong (a panel, a dialog, the request's own
 * question) has had time to go, as tvOS's `problemAlert` waits [PROBLEM_AFTER_MS]. OK or Back calls [onDone]; focus goes
 * back to [opener], the control that had it. A new [problem] while one shows replaces it. [onShowing] says when the
 * dialog is up (a tab's page hides the bar, which is drawn over the page).
 */
@Composable
fun ProblemAlert(problem: Problem?, onDone: () -> Unit, opener: FocusRequester?, tag: String = "problem", onShowing: (Boolean) -> Unit = {}) {
    var shown by remember { mutableStateOf<Problem?>(null) }
    LaunchedEffect(problem) {
        shown = null
        if (problem == null) return@LaunchedEffect
        delay(PROBLEM_AFTER_MS)
        shown = problem
    }
    val now = shown
    val up = now != null
    LaunchedEffect(up) { onShowing(up) }
    ProblemDialog(
        open = now != null,
        title = now?.title.orEmpty(),
        message = now?.message,
        onDismiss = {
            shown = null
            onDone()
        },
        opener = opener,
        tag = tag,
    )
}

/**
 * The page darkened, and over it in the middle a column of [Metrics.DialogWidth]: the title, the message, the
 * buttons. Focus cannot leave the column while it is shown (Compose's `onExit` refused, the pattern Hither's modals
 * have used since M2).
 */
@Composable
internal fun DialogColumn(title: String, message: String?, tag: String, buttons: @Composable () -> Unit) {
    Overlay(Modifier.testTag(tag), dim = 0.92f) {
        Column(
            Modifier
                .align(Alignment.Center)
                .width(Metrics.DialogWidth)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(title, style = HitherType.Title.copy(color = Tokens.Text, textAlign = TextAlign.Center), modifier = Modifier.testTag("dialog title"))
            if (message != null) {
                Spacer(Modifier.height(10.dp))
                BasicText(message, style = HitherType.Body.copy(color = Tokens.TextSecondary, textAlign = TextAlign.Center), modifier = Modifier.testTag("dialog message"))
            }
            Spacer(Modifier.height(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { buttons() }
        }
    }
}

/**
 * The page darkened under something drawn over it: a dialog, a panel, a picker. The one place a full-screen fill is
 * drawn outside a [Page] (`ThemeRulesTest`), so every overlay dims the same way: black at [dim]. [modifier] comes
 * before the fill, so a layer it adds (a fade in) takes the dimming with it.
 */
@Composable
fun Overlay(modifier: Modifier = Modifier, dim: Float = 0.7f, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Tokens.Scrim.copy(alpha = dim)), content = content)
}

/**
 * After a dialog that [open] shows has gone, focus goes back to [opener] a frame later, once the dialog's own controls
 * have left (when a focused control goes, Compose puts focus on the page's first focusable, DECISIONS 149).
 * Nothing happens if the opener has gone too (Sign Out leaves the screen).
 */
@Composable
private fun ReturnFocus(open: Boolean, opener: FocusRequester?) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) {
            shown = true
        } else if (shown) {
            shown = false
            withFrameNanos { }
            opener?.let { runCatching { it.requestFocus() } }
        }
    }
}
