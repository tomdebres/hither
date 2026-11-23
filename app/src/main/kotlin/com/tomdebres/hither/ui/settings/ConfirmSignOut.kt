package com.tomdebres.hither.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import com.tomdebres.hither.R
import com.tomdebres.hither.data.AccountMethod
import com.tomdebres.hither.ui.theme.ConfirmDialog

/**
 * Sign Out asks first, with Cancel focused (Tom's call, DECISIONS 80: the polish audit's item 29). Seerr signs out on
 * one click; on a TV a stray Select would cost the family the password, typed with a remote. A `ConfirmDialog` since
 * A1: what signing in again takes, Sign Out in red words over Cancel; Back is Cancel; focus goes back to [opener].
 */
@Composable
fun ConfirmSignOut(open: Boolean, method: AccountMethod, opener: FocusRequester, onConfirm: () -> Unit, onCancel: () -> Unit) = ConfirmDialog(
    open = open,
    title = stringResource(R.string.sign_out_title),
    message = stringResource(signOutBody(method)),
    confirm = stringResource(R.string.sign_out),
    onConfirm = onConfirm,
    onCancel = onCancel,
    opener = opener,
    tag = "confirm sign out",
    cancelTag = "cancel sign out",
)

/** What signing in again takes, by the account's way in, as the Apple TV app says it (H1; M8 part 2's report). */
fun signOutBody(method: AccountMethod): Int = when (method) {
    AccountMethod.LOCAL -> R.string.sign_out_body
    AccountMethod.MEDIA_SERVER -> R.string.sign_out_body_media_server
    AccountMethod.PLEX -> R.string.sign_out_body_plex
    AccountMethod.QUICK_CONNECT -> R.string.sign_out_body_quick_connect
}
