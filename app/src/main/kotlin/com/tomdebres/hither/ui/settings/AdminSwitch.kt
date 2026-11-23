package com.tomdebres.hither.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.tomdebres.hither.R
import com.tomdebres.hither.core.rules.PinCheck
import com.tomdebres.hither.session.ActiveAdmin
import com.tomdebres.hither.session.AdminAccess
import com.tomdebres.hither.session.AdminSwitch
import com.tomdebres.hither.ui.components.PinEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the switch's PIN dialog says under its title. */
sealed interface SwitchNote {
    data object Ask : SwitchNote

    /** Checking the PIN and opening the session. */
    data object Checking : SwitchNote

    data class Wrong(val left: Int) : SwitchNote

    /** A wait is on until [untilMs] (the device's clock); the pad takes nothing meanwhile. */
    data class Wait(val untilMs: Long, val left: Int) : SwitchNote

    data object NoRight : SwitchNote

    data object Failed : SwitchNote
}

/**
 * Switching to the TV's admin (DECISIONS 93), a dialog's worth of state: the PIN taken once per entry,
 * nothing while a check is out or a wait is on; [onIn] once the admin is active, [onGone] when the
 * account was removed (the last wrong PIN, or its password refused) with the reason to show.
 */
class AdminSwitchModel(
    private val admin: AdminAccess,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onIn: (ActiveAdmin) -> Unit,
    private val onGone: (passwordRefused: Boolean) -> Unit,
) {
    var note: SwitchNote by mutableStateOf(SwitchNote.Ask)
        private set

    /** A new entry clears the pad (wrong tries and waits). */
    var entry by mutableStateOf(0)
        private set

    val takesPin: Boolean get() = note !is SwitchNote.Checking && note !is SwitchNote.Wait

    fun pin(digits: String) {
        if (!takesPin) return
        note = SwitchNote.Checking
        scope.launch {
            when (val answer = admin.switchIn(digits)) {
                is AdminSwitch.In -> onIn(answer.admin)
                is AdminSwitch.Pin -> when (val check = answer.check) {
                    PinCheck.Removed -> onGone(false)
                    is PinCheck.Wrong -> note = if (check.waitMs > 0) SwitchNote.Wait(clock() + check.waitMs, check.left) else SwitchNote.Wrong(check.left)
                    is PinCheck.Wait -> note = SwitchNote.Wait(clock() + check.waitMs, -1)
                    PinCheck.Right -> Unit
                }
                AdminSwitch.NoRight -> note = SwitchNote.NoRight
                AdminSwitch.PasswordRefused -> onGone(true)
                AdminSwitch.Failed -> note = SwitchNote.Failed
                AdminSwitch.NoneKept -> onGone(false)
                // Hither left the front meanwhile: nothing switched; the pad is there again.
                AdminSwitch.Closed -> note = SwitchNote.Ask
            }
            entry++
        }
    }

    /** The wait is over: the pad takes a PIN again. */
    fun waited() {
        val wait = note as? SwitchNote.Wait ?: return
        if (clock() >= wait.untilMs) note = if (wait.left >= 0) SwitchNote.Wrong(wait.left) else SwitchNote.Ask
    }
}

/**
 * The switch's PIN (DECISIONS 93), full screen as the Apple TV app's digit entry is (H2's X8; tvOS's `SwitchPin`):
 * "Switch to {name}", and under it what happened last. A wrong PIN says how many tries are left (and, with one left,
 * that the next wrong one makes the TV forget the account); a wait counts down where it is said and the pad takes
 * nothing meanwhile. Back takes a digit off, then closes ([onCancel]).
 */
@Composable
fun AdminPinDialog(model: AdminSwitchModel, adminName: String, onCancel: () -> Unit) {
    val note = model.note
    // A wait counts down where it is said, and gives the pad back when it is over.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(note) {
        if (note is SwitchNote.Wait) {
            while (true) {
                now = System.currentTimeMillis()
                if (now >= note.untilMs) {
                    model.waited()
                    break
                }
                delay(250)
            }
        }
    }
    val words = when (note) {
        SwitchNote.Ask -> stringResource(R.string.switch_ask)
        SwitchNote.Checking -> stringResource(R.string.switch_checking)
        is SwitchNote.Wrong -> pluralStringResource(R.plurals.switch_wrong, note.left, note.left)
        is SwitchNote.Wait -> {
            val seconds = ((note.untilMs - now).coerceAtLeast(0) + 999) / 1000
            stringResource(R.string.switch_wait, seconds / 60, seconds % 60)
        }
        SwitchNote.NoRight -> stringResource(R.string.switch_no_right, adminName)
        SwitchNote.Failed -> stringResource(R.string.switch_failed)
    }
    PinEntry(
        title = stringResource(R.string.switch_title, adminName),
        line = words,
        trouble = note is SwitchNote.Wrong || note is SwitchNote.Wait || note == SwitchNote.NoRight || note == SwitchNote.Failed,
        onPin = model::pin,
        onBack = onCancel,
        entryKey = model.entry,
        enabled = model.takesPin,
        tag = "admin pin dialog",
        titleTag = "switch title",
        lineTag = "switch note",
    )
}
