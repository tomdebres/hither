package com.tomdebres.hither.session

import com.tomdebres.hither.core.SignInFailure
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.SignInProblem
import com.tomdebres.hither.core.rules.SignInRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val session = Session(User(id = 8, displayName = "TV"), PublicSettings(applicationTitle = "Seerr", localLogin = true))

    /** A gateway that records what it was asked and answers as each test sets it up. */
    private class Fake(var restored: Restored = Restored.NoAccount) : SessionGateway {
        val signIns = mutableListOf<SignInRequest>()
        var signOuts = 0
        var answer: CompletableDeferred<Session> = CompletableDeferred()
        val ended = Channel<SignInFailure>(Channel.UNLIMITED)
        override val lost: Flow<SignInFailure> = ended.receiveAsFlow()
        val stopped = Channel<SignInFailure>(Channel.UNLIMITED)
        override val stuck: Flow<SignInFailure> = stopped.receiveAsFlow()

        override suspend fun restore(): Restored = restored
        override suspend fun signIn(request: SignInRequest): Session {
            signIns += request
            return answer.await()
        }
        override suspend fun signOut() {
            signOuts++
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun SessionViewModel.fill(server: String = "seerr.test:5055", email: String = "tv@example.invalid", password: String = "secret") {
        onServer(server)
        onEmail(email)
        onPassword(password)
    }

    @Test
    fun `a server that is not answering is named on its screen`() = runTest(dispatcher) {
        // the polish audit's item 28: "The server is not answering." said nothing of which, or of the account
        val gateway = object : SessionGateway by Fake(Restored.ServerUnavailable) {
            override suspend fun storedServer(): String = "http://seerr.test:5055"
        }
        val vm = SessionViewModel(gateway)
        advanceUntilIdle()
        assertEquals(Screen.Unavailable(server = "http://seerr.test:5055"), vm.screen)
        // Try Again, and still not answering: still named
        vm.retry()
        advanceUntilIdle()
        assertEquals(Screen.Unavailable(server = "http://seerr.test:5055"), vm.screen)
    }

    @Test
    fun `first run opens on the sign-in form`() = runTest(dispatcher) {
        val vm = SessionViewModel(Fake())
        assertEquals(Screen.Starting, vm.screen)
        advanceUntilIdle()
        assertEquals(Screen.SignIn(), vm.screen)
    }

    @Test
    fun `a stored account that still works opens on home with the user's name and the server's title`() = runTest(dispatcher) {
        val vm = SessionViewModel(Fake(Restored.SignedIn(session)))
        advanceUntilIdle()
        assertEquals(Screen.Home(session), vm.screen)
        assertEquals("TV", (vm.screen as Screen.Home).displayName)
        assertEquals("Seerr", (vm.screen as Screen.Home).serverTitle)
    }

    @Test
    fun `an empty form names each problem and sends nothing`() = runTest(dispatcher) {
        val gateway = Fake()
        val vm = SessionViewModel(gateway)
        advanceUntilIdle()
        vm.submit()
        advanceUntilIdle()
        assertEquals(setOf(SignInProblem.SERVER, SignInProblem.EMAIL, SignInProblem.PASSWORD), (vm.screen as Screen.SignIn).problems)
        assertTrue(gateway.signIns.isEmpty())
    }

    @Test
    fun `typing in a field takes its own complaint away and leaves the others`() = runTest(dispatcher) {
        val vm = SessionViewModel(Fake())
        advanceUntilIdle()
        vm.submit()
        vm.onEmail("tv@example.invalid")
        assertEquals(setOf(SignInProblem.SERVER, SignInProblem.PASSWORD), (vm.screen as Screen.SignIn).problems)
    }

    @Test
    fun `sign-in sends the normalised form once, however often Select is pressed`() = runTest(dispatcher) {
        val gateway = Fake()
        val vm = SessionViewModel(gateway)
        advanceUntilIdle()
        vm.fill(email = " tv@example.invalid ")

        vm.submit()
        assertTrue((vm.screen as Screen.SignIn).submitting)
        vm.submit()
        vm.submit()
        advanceUntilIdle()
        assertEquals(listOf(SignInRequest("http://seerr.test:5055", "tv@example.invalid", "secret")), gateway.signIns)

        gateway.answer.complete(session)
        advanceUntilIdle()
        assertEquals(Screen.Home(session), vm.screen)
        // the form, password included, is gone from memory once it has been used
        assertEquals("", vm.password)
        assertEquals("", vm.email)
    }

    @Test
    fun `a refused sign-in comes back to the form with the reason and the server's title, ready to try again`() = runTest(dispatcher) {
        val gateway = Fake()
        val vm = SessionViewModel(gateway)
        advanceUntilIdle()
        vm.fill()
        vm.submit()
        gateway.answer.completeExceptionally(SignInFailed(SignInFailure.WRONG_CREDENTIALS, serverTitle = "Seerr"))
        advanceUntilIdle()

        assertEquals(Screen.SignIn(failure = SignInFailure.WRONG_CREDENTIALS, serverTitle = "Seerr"), vm.screen)
        // what was typed is still there to correct, and a second attempt goes out
        assertEquals("secret", vm.password)
        gateway.answer = CompletableDeferred(session)
        vm.submit()
        advanceUntilIdle()
        assertEquals(2, gateway.signIns.size)
        assertTrue(vm.screen is Screen.Home)
    }

    @Test
    fun `a stored account whose server is down waits on its own screen, and Try Again asks once`() = runTest(dispatcher) {
        val gateway = Fake(Restored.ServerUnavailable)
        val vm = SessionViewModel(gateway)
        advanceUntilIdle()
        assertEquals(Screen.Unavailable(), vm.screen)

        gateway.restored = Restored.SignedIn(session)
        vm.retry()
        assertEquals(Screen.Unavailable(retrying = true), vm.screen)
        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.screen is Screen.Home)
    }

    @Test
    fun `a stored account the server no longer takes lands on the form with the reason`() = runTest(dispatcher) {
        val vm = SessionViewModel(Fake(Restored.Rejected(SignInFailure.WRONG_CREDENTIALS)))
        advanceUntilIdle()
        assertEquals(Screen.SignIn(failure = SignInFailure.WRONG_CREDENTIALS), vm.screen)
    }

    @Test
    fun `sign out forgets the session and returns to an empty form`() = runTest(dispatcher) {
        val gateway = Fake(Restored.SignedIn(session))
        val vm = SessionViewModel(gateway)
        advanceUntilIdle()
        vm.signOut()
        advanceUntilIdle()
        assertEquals(1, gateway.signOuts)
        assertEquals(Screen.SignIn(), vm.screen)
    }

    @Test
    fun `the server refusing the stored password under a signed-in screen lands on the form with the reason`() = runTest(dispatcher) {
        val gateway = Fake(Restored.SignedIn(session))
        val vm = SessionViewModel(gateway)
        advanceUntilIdle()
        assertTrue(vm.screen is Screen.Home)

        gateway.ended.trySend(SignInFailure.WRONG_CREDENTIALS)
        advanceUntilIdle()
        assertEquals(Screen.SignIn(failure = SignInFailure.WRONG_CREDENTIALS), vm.screen)
    }

    @Test
    fun `a stored account the server's CSRF check will not sign in again says why, and Try Again keeps saying it`() = runTest(dispatcher) {
        val gateway = Fake(Restored.CsrfNeedsHttps)
        val vm = SessionViewModel(gateway)
        advanceUntilIdle()
        assertEquals(Screen.Unavailable(csrfNeedsHttps = true), vm.screen)
        vm.retry()
        assertEquals(Screen.Unavailable(retrying = true, csrfNeedsHttps = true), vm.screen)
        advanceUntilIdle()
        assertEquals(Screen.Unavailable(csrfNeedsHttps = true), vm.screen)
        assertEquals(0, gateway.signOuts)
    }

    @Test
    fun `a renewal the CSRF check refused under a signed-in screen says why, and the account is not dropped`() = runTest(dispatcher) {
        val gateway = Fake(Restored.SignedIn(session))
        val vm = SessionViewModel(gateway)
        advanceUntilIdle()
        gateway.stopped.send(SignInFailure.CSRF_NEEDS_HTTPS)
        advanceUntilIdle()
        assertEquals(Screen.Unavailable(csrfNeedsHttps = true), vm.screen)
        assertEquals(0, gateway.signOuts)
    }
}
