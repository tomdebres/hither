package com.tomdebres.hither.ui.settings

import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.PinCheck
import com.tomdebres.hither.core.rules.SignInRequest
import com.tomdebres.hither.session.ActiveAdmin
import com.tomdebres.hither.session.AdminAccess
import com.tomdebres.hither.session.AdminSignIn
import com.tomdebres.hither.session.AdminSummary
import com.tomdebres.hither.session.AdminSwitch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The switch's PIN dialog, as its model drives it (DECISIONS 93). */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminSwitchModelTest {
    private class Fake : AdminAccess {
        var answer: AdminSwitch = AdminSwitch.In(ActiveAdmin(9, "Approver"))
        var gate: CompletableDeferred<Unit>? = null
        val pins = mutableListOf<String>()
        override suspend fun kept() = AdminSummary("Approver")
        override suspend fun signIn(request: SignInRequest, family: User) = AdminSignIn.Failed(com.tomdebres.hither.core.SignInFailure.OTHER)
        override suspend fun setPin(pin: String) = false
        override suspend fun cancel() = Unit
        override suspend fun remove() = Unit
        override val active = MutableStateFlow<ActiveAdmin?>(null)
        override suspend fun switchIn(pin: String): AdminSwitch {
            pins += pin
            gate?.await()
            return answer
        }
        override fun back() = Unit
    }

    private val fake = Fake()
    private var now = 1_000L
    private val ins = mutableListOf<ActiveAdmin>()
    private val gones = mutableListOf<Boolean>()

    private fun TestScope.model() = AdminSwitchModel(fake, backgroundScope, clock = { now }, onIn = { ins += it }, onGone = { gones += it })

    @Test
    fun theRightPinSwitchesIn() = runTest(UnconfinedTestDispatcher()) {
        model().pin("5632")
        assertEquals(listOf(ActiveAdmin(9, "Approver")), ins)
    }

    @Test
    fun oneCheckAtATime() = runTest(UnconfinedTestDispatcher()) {
        fake.gate = CompletableDeferred()
        val model = model()
        model.pin("5632")
        assertEquals(SwitchNote.Checking, model.note)
        assertFalse(model.takesPin)
        model.pin("5632")
        assertEquals(1, fake.pins.size)
        fake.gate!!.complete(Unit)
    }

    @Test
    fun aWrongPinSaysHowManyAreLeftAndClearsThePad() = runTest(UnconfinedTestDispatcher()) {
        fake.answer = AdminSwitch.Pin(PinCheck.Wrong(left = 7, waitMs = 0))
        val model = model()
        val entry = model.entry
        model.pin("0000")
        assertEquals(SwitchNote.Wrong(7), model.note)
        assertTrue(model.takesPin)
        assertEquals(entry + 1, model.entry)
    }

    @Test
    fun aWaitTakesNothingUntilItIsOver() = runTest(UnconfinedTestDispatcher()) {
        fake.answer = AdminSwitch.Pin(PinCheck.Wrong(left = 5, waitMs = 30_000))
        val model = model()
        model.pin("0000")
        assertEquals(SwitchNote.Wait(31_000, 5), model.note)
        assertFalse(model.takesPin)
        model.pin("5632")
        assertEquals(1, fake.pins.size)
        now = 30_999
        model.waited()
        assertFalse("not yet", model.takesPin)
        now = 31_000
        model.waited()
        assertEquals(SwitchNote.Wrong(5), model.note)
        assertTrue(model.takesPin)
    }

    @Test
    fun theAccountGoneClosesTheDialogSayingWhy() = runTest(UnconfinedTestDispatcher()) {
        fake.answer = AdminSwitch.Pin(PinCheck.Removed)
        model().pin("0000")
        fake.answer = AdminSwitch.PasswordRefused
        model().pin("5632")
        assertEquals(listOf(false, true), gones)
    }

    @Test
    fun lostRightsAndAServerNotAnsweringAreSaid() = runTest(UnconfinedTestDispatcher()) {
        fake.answer = AdminSwitch.NoRight
        val model = model()
        model.pin("5632")
        assertEquals(SwitchNote.NoRight, model.note)
        fake.answer = AdminSwitch.Failed
        model.pin("5632")
        assertEquals(SwitchNote.Failed, model.note)
        assertTrue(ins.isEmpty())
    }
}
