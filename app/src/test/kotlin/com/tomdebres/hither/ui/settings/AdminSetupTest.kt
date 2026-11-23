package com.tomdebres.hither.ui.settings

import com.tomdebres.hither.core.SignInFailure
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.AdminVerdict
import com.tomdebres.hither.core.rules.PinSetting
import com.tomdebres.hither.core.rules.SignInProblem
import com.tomdebres.hither.core.rules.SignInRequest
import com.tomdebres.hither.session.AdminAccess
import com.tomdebres.hither.session.AdminSignIn
import com.tomdebres.hither.session.AdminSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Setting up the TV's admin account, as the page drives it (DECISIONS 92). */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminSetupTest {
    private class Fake : AdminAccess {
        var kept: AdminSummary? = null
        var answer: AdminSignIn = AdminSignIn.Accepted(AdminVerdict.APPROVER, "Approver")
        var gate: CompletableDeferred<Unit>? = null
        val signIns = mutableListOf<SignInRequest>()
        val pins = mutableListOf<String>()
        var cancels = 0
        var removes = 0
        var saves = true

        override suspend fun kept() = kept
        override suspend fun signIn(request: SignInRequest, family: User): AdminSignIn {
            signIns += request
            gate?.await()
            return answer
        }
        override suspend fun setPin(pin: String): Boolean {
            pins += pin
            if (saves) kept = AdminSummary("Approver")
            return saves
        }
        override suspend fun cancel() {
            cancels++
        }
        override suspend fun remove() {
            removes++
            kept = null
        }
        override val active = kotlinx.coroutines.flow.MutableStateFlow<com.tomdebres.hither.session.ActiveAdmin?>(null)
        override suspend fun switchIn(pin: String) = com.tomdebres.hither.session.AdminSwitch.NoneKept
        override fun back() {
            active.value = null
        }
    }

    private val fake = Fake()
    private val family = User(id = 8, displayName = "User 8", permissions = 32)

    private fun TestScope.setup() = AdminSetup(fake, family, "http://seerr.test:5055", backgroundScope).also { it.open() }

    private fun AdminSetup.signIn(email: String = "approver@example.invalid", password: String = "approver-password") {
        onEmail(email)
        onPassword(password)
        submit()
    }

    @Test
    fun noneKeptOpensOnTheFormAndAKeptOneOnItsName() = runTest(UnconfinedTestDispatcher()) {
        assertEquals(AdminStep.Form(), setup().step)
        fake.kept = AdminSummary("Approver")
        assertEquals(AdminStep.Kept("Approver"), setup().step)
    }

    @Test
    fun theFormAsksForBothFieldsAsSeerrsDoes() = runTest(UnconfinedTestDispatcher()) {
        val setup = setup()
        setup.signIn(email = " ", password = "")
        assertEquals(AdminStep.Form(problems = setOf(SignInProblem.EMAIL, SignInProblem.PASSWORD)), setup.step)
        assertTrue(fake.signIns.isEmpty())
        setup.onEmail("approver@example.invalid")
        assertEquals("typing takes its own complaint away", setOf(SignInProblem.PASSWORD), (setup.step as AdminStep.Form).problems)
    }

    @Test
    fun signingInGoesToTheFamilysServerOncePerPressAndDropsThePassword() = runTest(UnconfinedTestDispatcher()) {
        fake.gate = CompletableDeferred()
        val setup = setup()
        setup.signIn(email = " approver@example.invalid ")
        setup.submit()
        setup.submit()
        assertEquals(1, fake.signIns.size)
        assertEquals(SignInRequest("http://seerr.test:5055", "approver@example.invalid", "approver-password"), fake.signIns.single())
        assertTrue((setup.step as AdminStep.Form).submitting)
        fake.gate!!.complete(Unit)
        assertEquals("", setup.password)
        assertEquals(AdminStep.Pin(PinSetting.Choose(), "Approver"), setup.step)
    }

    @Test
    fun anAdministratorIsToldOnceThenChoosesThePin() = runTest(UnconfinedTestDispatcher()) {
        fake.answer = AdminSignIn.Accepted(AdminVerdict.ADMINISTRATOR, "Owner")
        val setup = setup()
        setup.signIn()
        assertEquals(AdminStep.Administrator("Owner"), setup.step)
        setup.carryOn()
        assertEquals(AdminStep.Pin(PinSetting.Choose(), "Owner"), setup.step)
    }

    @Test
    fun aRefusedAccountIsSaidAndTheFormComesBack() = runTest(UnconfinedTestDispatcher()) {
        for (verdict in listOf(AdminVerdict.FAMILY, AdminVerdict.NO_RIGHT)) {
            fake.answer = AdminSignIn.Refused(verdict)
            val setup = setup()
            setup.signIn()
            assertEquals(AdminStep.Form(refused = verdict), setup.step)
        }
        fake.answer = AdminSignIn.Failed(SignInFailure.WRONG_CREDENTIALS)
        val setup = setup()
        setup.signIn()
        assertEquals(AdminStep.Form(failure = SignInFailure.WRONG_CREDENTIALS), setup.step)
    }

    @Test
    fun thePinIsChosenEnteredAgainAndKept() = runTest(UnconfinedTestDispatcher()) {
        val setup = setup()
        setup.signIn()
        setup.pin("4821")
        assertTrue((setup.step as AdminStep.Pin).setting is PinSetting.Confirm)
        assertTrue("nothing kept on the first entry", fake.pins.isEmpty())
        setup.pin("4821")
        assertEquals(listOf("4821"), fake.pins)
        assertEquals(AdminStep.Kept("Approver"), setup.step)
    }

    @Test
    fun twoEntriesThatDifferStartAgainAndKeepNothing() = runTest(UnconfinedTestDispatcher()) {
        val setup = setup()
        setup.signIn()
        setup.pin("4821")
        setup.pin("4812")
        assertEquals(AdminStep.Pin(PinSetting.Choose(mismatched = true), "Approver"), setup.step)
        assertTrue(fake.pins.isEmpty())
    }

    @Test
    fun aPinThatCouldNotBeKeptAsksAgain() = runTest(UnconfinedTestDispatcher()) {
        fake.saves = false
        val setup = setup()
        setup.signIn()
        setup.pin("4821")
        setup.pin("4821")
        assertEquals(AdminStep.Pin(PinSetting.Choose(), "Approver", failed = true), setup.step)
    }

    @Test
    fun backingOutBeforeThePinKeepsNothing() = runTest(UnconfinedTestDispatcher()) {
        val setup = setup()
        setup.signIn()
        setup.cancel()
        assertEquals(1, fake.cancels)
        assertEquals(AdminStep.Form(), setup.step)
        assertEquals("", setup.password)
    }

    @Test
    fun removingForgetsTheAccountAndShowsTheFormAgain() = runTest(UnconfinedTestDispatcher()) {
        fake.kept = AdminSummary("Approver")
        val setup = setup()
        setup.cancel()
        assertEquals("Back from a kept account cancels nothing", AdminStep.Kept("Approver"), setup.step)
        assertEquals(0, fake.cancels)
        setup.remove()
        assertEquals(1, fake.removes)
        assertEquals(AdminStep.Form(), setup.step)
    }
}
