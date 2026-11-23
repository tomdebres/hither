package com.tomdebres.hither.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tomdebres.hither.core.SignInFailure
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.AdminVerdict
import com.tomdebres.hither.core.rules.PinSetting
import com.tomdebres.hither.core.rules.SignInProblem
import com.tomdebres.hither.core.rules.checkSignIn
import com.tomdebres.hither.core.rules.pinSetting
import com.tomdebres.hither.session.AdminAccess
import com.tomdebres.hither.session.AdminSignIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Why the guard removed the kept account: the last wrong PIN, or its password refused on signing in again. */
enum class Gone { TRIES, PASSWORD }

/** Where setting up the admin account is (DECISIONS 92). */
sealed interface AdminStep {
    /** Reading what is kept. */
    data object Loading : AdminStep

    /** None kept: the sign-in form, as the family's (email and password; the server is the family's). */
    data class Form(
        val problems: Set<SignInProblem> = emptySet(),
        val failure: SignInFailure? = null,
        val submitting: Boolean = false,
        /** The last account signed in could not be the admin, and why; nothing was kept. */
        val refused: AdminVerdict? = null,
        /** The kept account was just removed by the guard, and why (DECISIONS 93). */
        val gone: Gone? = null,
    ) : AdminStep

    /** The account is an administrator: said once, then on to the PIN. */
    data class Administrator(val displayName: String) : AdminStep

    /** Choosing the PIN ([PinSetting.Choose]) or entering it again ([PinSetting.Confirm]). */
    data class Pin(val setting: PinSetting, val displayName: String, val saving: Boolean = false, val failed: Boolean = false) : AdminStep

    /** An admin account is kept. */
    data class Kept(val displayName: String, val removing: Boolean = false) : AdminStep
}

/**
 * Setting up the TV's admin account, a page's worth of state: sign in once with the account Tom made
 * for approving (its rights read live), say so once if it is an administrator, choose the PIN and enter
 * it again; or see the account kept, and remove it. The password is dropped from memory once sent.
 */
class AdminSetup(
    private val admin: AdminAccess,
    private val family: User,
    private val baseUrl: String,
    private val scope: CoroutineScope,
) {
    var step: AdminStep by mutableStateOf(AdminStep.Loading)
        private set
    var email by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set

    fun open() {
        scope.launch { step = admin.kept()?.let { AdminStep.Kept(it.displayName) } ?: AdminStep.Form() }
    }

    fun onEmail(text: String) {
        email = text
        forget(SignInProblem.EMAIL)
    }

    fun onPassword(text: String) {
        password = text
        forget(SignInProblem.PASSWORD)
    }

    private fun forget(problem: SignInProblem) {
        val form = step as? AdminStep.Form ?: return
        if (problem in form.problems) step = form.copy(problems = form.problems - problem)
    }

    /** Sign In: once per press, nothing while one is out. */
    fun submit() {
        val form = step as? AdminStep.Form ?: return
        if (form.submitting) return
        val (request, problems) = checkSignIn(baseUrl, email, password)
        if (request == null) {
            step = AdminStep.Form(problems = problems - SignInProblem.SERVER)
            return
        }
        step = form.copy(problems = emptySet(), failure = null, submitting = true)
        scope.launch {
            val answer = admin.signIn(request, family)
            password = ""
            step = when (answer) {
                is AdminSignIn.Accepted -> when (answer.verdict) {
                    AdminVerdict.ADMINISTRATOR -> AdminStep.Administrator(answer.displayName)
                    else -> AdminStep.Pin(PinSetting.Choose(), answer.displayName)
                }
                is AdminSignIn.Refused -> AdminStep.Form(refused = answer.verdict)
                is AdminSignIn.Failed -> AdminStep.Form(failure = answer.failure)
            }
            if (answer !is AdminSignIn.Accepted) email = email.trim()
        }
    }

    /** Past the administrator's note, to the PIN. */
    fun carryOn() {
        val note = step as? AdminStep.Administrator ?: return
        step = AdminStep.Pin(PinSetting.Choose(), note.displayName)
    }

    /** A full PIN entered: the next step of setting it, and keeping the account when both entries match. */
    fun pin(digits: String) {
        val at = step as? AdminStep.Pin ?: return
        if (at.saving) return
        when (val next = pinSetting(at.setting, digits)) {
            is PinSetting.Set -> {
                step = at.copy(saving = true, failed = false)
                scope.launch {
                    step = if (admin.setPin(next.pin)) AdminStep.Kept(at.displayName) else at.copy(setting = PinSetting.Choose(), saving = false, failed = true)
                }
            }
            else -> step = at.copy(setting = next, failed = false)
        }
    }

    /** Back out of setting up: nothing is kept, and the session made is ended. */
    fun cancel() {
        val was = step
        if (was is AdminStep.Kept || was is AdminStep.Loading) return
        password = ""
        step = AdminStep.Form()
        scope.launch { admin.cancel() }
    }

    /** The admin while active (the page offers Switch Back then), else null. */
    val active get() = admin.active

    /** Switching to the admin from the page: its PIN dialog's model. */
    fun switcher(scope: CoroutineScope, onIn: () -> Unit) = AdminSwitchModel(
        admin,
        scope,
        onIn = { onIn() },
        onGone = { passwordRefused -> gone(if (passwordRefused) Gone.PASSWORD else Gone.TRIES) },
    )

    /** Back to the family's account, the admin kept. */
    fun switchBack() = admin.back()

    /** The guard removed the kept account: the form again, saying why. */
    fun gone(why: Gone) {
        email = ""
        password = ""
        step = AdminStep.Form(gone = why)
    }

    /** Remove the admin account (asked first on the page). */
    fun remove() {
        val kept = step as? AdminStep.Kept ?: return
        if (kept.removing) return
        step = kept.copy(removing = true)
        scope.launch {
            admin.remove()
            email = ""
            step = AdminStep.Form()
        }
    }
}
