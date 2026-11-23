package com.tomdebres.hither.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.tomdebres.hither.R
import com.tomdebres.hither.core.SignInFailure
import com.tomdebres.hither.core.rules.AdminVerdict
import com.tomdebres.hither.core.rules.PinSetting
import com.tomdebres.hither.core.rules.SignInProblem
import com.tomdebres.hither.ui.components.EntryField
import com.tomdebres.hither.ui.components.EntryLift
import com.tomdebres.hither.ui.components.FocusOnArrival
import com.tomdebres.hither.ui.components.PinEntry
import com.tomdebres.hither.ui.components.liftAboveKeyboard
import com.tomdebres.hither.ui.components.rememberEntryFieldState
import com.tomdebres.hither.ui.components.rememberEntryLift
import com.tomdebres.hither.ui.theme.ConfirmDialog
import com.tomdebres.hither.ui.theme.EntryColumn
import com.tomdebres.hither.ui.theme.EntryProblem
import com.tomdebres.hither.ui.theme.HitherListItem
import com.tomdebres.hither.ui.theme.Page
import com.tomdebres.hither.ui.theme.PageKind
import com.tomdebres.hither.ui.theme.PlainButton
import com.tomdebres.hither.ui.theme.ProminentButton
import com.tomdebres.hither.ui.theme.SettingsMark
import com.tomdebres.hither.ui.theme.SettingsScaffold
import com.tomdebres.hither.ui.theme.SettingsSection
import com.tomdebres.hither.ui.theme.SettingsValueRow

/**
 * Settings › Admin Account (M5, DECISIONS 92, 93), in the Apple TV app's shapes (H2's X4, X5, X8; tvOS's `AdminView`):
 * with none kept, a word on what it is for and the approver's sign-in as the family's is (`EntryColumn`: the server is
 * the family's; the same fields and keyboard); for an administrator, a word said once; then the PIN, chosen and entered
 * again, full screen (`PinEntry`); or the account kept, as Settings' rows (`SettingsScaffold`): who it is and its right,
 * Switch to {name} (Switch Back while active), and Remove Admin Account in red words, asking first. Back leaves at any
 * step, keeping nothing unless the PIN was set.
 */
@Composable
fun AdminScreen(setup: AdminSetup, onBack: () -> Unit) {
    LaunchedEffect(setup) { setup.open() }
    val leave = {
        setup.cancel()
        onBack()
    }
    var asking by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    val remove = remember { FocusRequester() }
    val active by setup.active.collectAsState()
    val switchScope = rememberCoroutineScope()
    // The open field kept above the keyboard, as on the family's sign-in (DECISIONS 83): the whole page lifts, its
    // title with it, so nothing is drawn over the title.
    val lift = rememberEntryLift()
    Box(Modifier.fillMaxSize()) {
        Page(
            PageKind.Pushed(stringResource(R.string.admin_account), titleTag = "admin heading"),
            modifier = Modifier.liftAboveKeyboard(lift),
        ) {
            when (val step = setup.step) {
                // The PIN is drawn over the whole screen, below.
                AdminStep.Loading, is AdminStep.Pin -> Unit
                is AdminStep.Form -> AdminForm(setup, step, lift, onBack = leave)
                is AdminStep.Administrator -> AdministratorNote(step, lift, onCarryOn = setup::carryOn, onBack = leave)
                is AdminStep.Kept -> Kept(
                    step,
                    active = active != null,
                    switching = switching,
                    onSwitch = { if (active != null) setup.switchBack() else switching = true },
                    onAsk = { asking = true },
                    onBack = onBack,
                    remove = remove,
                )
            }
        }
        (setup.step as? AdminStep.Pin)?.let { step -> ChoosePin(step, onPin = setup::pin, onBack = leave) }
        if (switching) {
            val switcher = remember { setup.switcher(switchScope, onIn = { switching = false }) }
            AdminPinDialog(switcher, adminName = (setup.step as? AdminStep.Kept)?.displayName.orEmpty(), onCancel = { switching = false })
            // The account went (the last wrong PIN, or its password refused): the PIN closes over the form.
            LaunchedEffect(setup.step) { if (setup.step !is AdminStep.Kept) switching = false }
        }
        // Removing it costs the account's password and a new PIN, typed with the remote: asked first, Cancel focused,
        // Remove in red words (rule 2); focus back on Remove after.
        ConfirmDialog(
            open = asking,
            title = stringResource(R.string.admin_remove_title),
            message = stringResource(R.string.admin_remove_body),
            confirm = stringResource(R.string.admin_remove),
            onConfirm = {
                asking = false
                setup.remove()
            },
            onCancel = { asking = false },
            opener = remove,
            tag = "confirm remove admin",
            cancelTag = "cancel remove admin",
        )
    }
}

/** None kept: what it is for, and the approver's sign-in, as the family's (`EntryColumn` without the mark). */
@Composable
private fun AdminForm(setup: AdminSetup, step: AdminStep.Form, lift: EntryLift, onBack: () -> Unit) {
    BackHandler { onBack() }
    val emailField = rememberEntryFieldState()
    val passwordField = rememberEntryFieldState()
    val button = remember { FocusRequester() }
    FocusOnArrival(emailField.focus)
    EntryColumn(
        title = stringResource(R.string.admin_heading_sign_in),
        lift = lift,
        mark = false,
        titleTag = "admin sign-in heading",
        detail = stringResource(R.string.admin_intro),
        detailTag = "admin intro",
        fields = {
            EntryField(
                value = setup.email,
                onValueChange = setup::onEmail,
                placeholder = stringResource(R.string.field_email),
                modifier = Modifier.fillMaxWidth(),
                state = emailField,
                keyboardType = KeyboardType.Email,
                enabled = !step.submitting,
                tag = "admin email",
                error = if (SignInProblem.EMAIL in step.problems) stringResource(R.string.error_email) else null,
                onImeAction = { passwordField.focus.requestFocus(); passwordField.edit() },
            )
            EntryField(
                value = setup.password,
                onValueChange = setup::onPassword,
                placeholder = stringResource(R.string.field_password),
                modifier = Modifier.fillMaxWidth(),
                state = passwordField,
                imeAction = ImeAction.Done,
                password = true,
                enabled = !step.submitting,
                tag = "admin password",
                error = if (SignInProblem.PASSWORD in step.problems) stringResource(R.string.error_password) else null,
                onImeAction = { button.requestFocus() },
            )
        },
    ) {
        ProminentButton(
            text = stringResource(if (step.submitting) R.string.signing_in else R.string.sign_in),
            onClick = setup::submit,
            busy = step.submitting,
            centred = true,
            modifier = Modifier.fillMaxWidth().focusRequester(button).testTag("admin sign in"),
        )
        step.failure?.let { EntryProblem(stringResource(it.words()), "admin sign-in error") }
        when (step.gone) {
            Gone.TRIES -> EntryProblem(stringResource(R.string.admin_gone_tries), "admin gone")
            Gone.PASSWORD -> EntryProblem(stringResource(R.string.admin_gone_password), "admin gone")
            null -> Unit
        }
        when (step.refused) {
            AdminVerdict.FAMILY -> EntryProblem(stringResource(R.string.admin_refused_family), "admin refused")
            AdminVerdict.NO_RIGHT -> EntryProblem(stringResource(R.string.admin_refused_no_right), "admin refused")
            else -> Unit
        }
    }
}

/** An administrator signed in: said once, Carry On (the page's one prominent button) to the PIN, or Cancel. */
@Composable
private fun AdministratorNote(step: AdminStep.Administrator, lift: EntryLift, onCarryOn: () -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    val carryOn = remember { FocusRequester() }
    FocusOnArrival(carryOn)
    EntryColumn(
        title = stringResource(R.string.admin_administrator_title),
        lift = lift,
        modifier = Modifier.testTag("admin administrator"),
        mark = false,
        titleTag = "admin administrator title",
        detail = stringResource(R.string.admin_administrator_body, step.displayName),
        detailTag = "admin administrator body",
    ) {
        ProminentButton(
            text = stringResource(R.string.admin_carry_on),
            onClick = onCarryOn,
            centred = true,
            modifier = Modifier.fillMaxWidth().focusRequester(carryOn).testTag("admin carry on"),
        )
        PlainButton(text = stringResource(R.string.cancel), onClick = onBack, centred = true, modifier = Modifier.fillMaxWidth().testTag("admin cancel"))
    }
}

/**
 * Choosing the PIN, then entering it again, full screen: "Choose a 4-digit PIN", then "Enter the PIN again", the line
 * under it saying what it is for, that the two were not the same, or that keeping the account failed. Each step starts
 * empty; Back on an empty pad leaves the page, keeping nothing.
 */
@Composable
private fun ChoosePin(step: AdminStep.Pin, onPin: (String) -> Unit, onBack: () -> Unit) {
    val confirming = step.setting is PinSetting.Confirm
    val mismatched = (step.setting as? PinSetting.Choose)?.mismatched == true
    PinEntry(
        title = stringResource(if (confirming) R.string.pin_again else R.string.pin_choose),
        line = when {
            step.saving -> stringResource(R.string.pin_saving)
            step.failed -> stringResource(R.string.pin_save_failed)
            mismatched -> stringResource(R.string.pin_mismatch)
            else -> stringResource(R.string.pin_choose_note, step.displayName)
        },
        trouble = step.failed || mismatched,
        onPin = onPin,
        onBack = onBack,
        entryKey = step.setting,
        enabled = !step.saving,
        tag = "pin entry",
        titleTag = if (confirming) "pin again" else "pin choose",
        lineTag = "pin note",
    )
}

/**
 * The account kept, as Settings' rows (tvOS's F22, F26, S30): the approver's person and shield on the left; who it is
 * with its right in plain words (Seerr's role pill is gone), Switch to {name} (Switch Back while active) with the note
 * as the section's footer; then Remove Admin Account in red words by its role, never a fill, which asks first.
 */
@Composable
private fun Kept(
    step: AdminStep.Kept,
    active: Boolean,
    switching: Boolean,
    onSwitch: () -> Unit,
    onAsk: () -> Unit,
    onBack: () -> Unit,
    remove: FocusRequester,
) {
    BackHandler { onBack() }
    val switch = remember { FocusRequester() }
    // Back from the PIN: focus on the switch again (the question gives focus back to Remove itself, `ConfirmDialog`).
    var switchedBefore by remember { mutableStateOf(false) }
    LaunchedEffect(switching) {
        if (!switching && switchedBefore) runCatching { switch.requestFocus() }
        if (switching) switchedBefore = true
    }
    FocusOnArrival(switch)
    SettingsScaffold(mark = SettingsMark.APPROVER, markTag = "admin mark", listTag = "admin list") {
        item(key = "account") {
            SettingsSection(
                footer = stringResource(if (active) R.string.admin_active_note else R.string.admin_kept_note),
                footerTag = "admin kept note",
            ) {
                SettingsValueRow(
                    label = step.displayName,
                    value = stringResource(R.string.admin_kept_role),
                    tag = "admin account row",
                    labelTag = "admin name",
                    valueTag = "admin role",
                )
                HitherListItem(
                    // the PIN it opens is "Switch to {name}": one action, one name (the polish 2 audit's item 31)
                    headline = if (active) stringResource(R.string.admin_switch_back) else stringResource(R.string.admin_switch_in_named, step.displayName),
                    onClick = onSwitch,
                    busy = step.removing,
                    modifier = Modifier.focusRequester(switch),
                    tag = if (active) "admin switch back" else "admin switch in",
                )
            }
        }
        item(key = "remove") {
            SettingsSection {
                HitherListItem(
                    headline = stringResource(if (step.removing) R.string.admin_removing else R.string.admin_remove),
                    onClick = onAsk,
                    busy = step.removing,
                    // destructive by role: red words, never a fill (rule 2)
                    destructive = true,
                    modifier = Modifier.focusRequester(remove),
                    tag = "admin remove",
                )
            }
        }
    }
}

private fun SignInFailure.words(): Int = when (this) {
    SignInFailure.WRONG_CREDENTIALS -> R.string.error_credentials
    SignInFailure.LOCAL_LOGIN_DISABLED -> R.string.error_local_login_off
    SignInFailure.UNREACHABLE -> R.string.error_unreachable
    SignInFailure.CSRF_NEEDS_HTTPS -> R.string.error_csrf
    SignInFailure.OTHER -> R.string.error_sign_in
    // The approver signs in with Seerr's own password only (91): none of M8's ways in, so none of their failures.
    SignInFailure.NOT_IMPORTED, SignInFailure.METHOD_OFF, SignInFailure.MEDIA_SERVER_UNREACHABLE, SignInFailure.CODE_EXPIRED,
    SignInFailure.PLEX_UNREACHABLE, SignInFailure.QUICK_CONNECT_UNAVAILABLE, SignInFailure.SESSION_ENDED -> R.string.error_sign_in
}
