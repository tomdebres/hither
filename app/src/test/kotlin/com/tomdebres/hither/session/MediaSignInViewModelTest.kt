package com.tomdebres.hither.session

import com.tomdebres.hither.core.SignInFailure
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.MediaServerKind
import com.tomdebres.hither.core.rules.SignInMethods
import com.tomdebres.hither.core.rules.SignInProblem
import com.tomdebres.hither.core.rules.SignInRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** M8's sign-in in the view model: what a server offers, the Jellyfin and Emby form, and the code pages. */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaSignInViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val base = "http://seerr.test:5055"
    private val session = Session(User(id = 14, displayName = "Plex Member"), PublicSettings(applicationTitle = "Seerr"), base)

    private fun offer(local: Boolean = true, plex: Boolean = false, password: Boolean = false, quickConnect: Boolean = false, kind: MediaServerKind = MediaServerKind.PLEX) =
        ServerOffer(base, "Seerr", SignInMethods(local, plex, password, quickConnect, kind), approveAt = null)

    /** A gateway whose every answer a test hands over by hand. */
    private class Fake(var restored: Restored = Restored.NoAccount) : SessionGateway {
        val offers = mutableListOf<String>()
        var offerAnswer = CompletableDeferred<ServerOffer>()
        val codes = mutableListOf<String>()
        var codeAnswer = CompletableDeferred<PendingCode>()
        var waitAnswer = CompletableDeferred<Session>()
        var waitsCancelled = 0
        val mediaSignIns = mutableListOf<SignInRequest>()
        var mediaAnswer = CompletableDeferred<Session>()
        val ended = Channel<SignInFailure>(Channel.UNLIMITED)
        override val lost: Flow<SignInFailure> = ended.receiveAsFlow()
        override val stuck: Flow<SignInFailure> = Channel<SignInFailure>().receiveAsFlow()

        override suspend fun restore(): Restored = restored
        override suspend fun signIn(request: SignInRequest): Session = error("not here")
        override suspend fun signOut() = Unit
        override suspend fun offer(baseUrl: String): ServerOffer {
            offers += baseUrl
            return offerAnswer.await()
        }
        override suspend fun signInWithMediaServer(request: SignInRequest): Session {
            mediaSignIns += request
            return mediaAnswer.await()
        }
        override suspend fun newPlexCode(): PendingCode {
            codes += "plex"
            return codeAnswer.await()
        }
        override suspend fun newQuickConnectCode(baseUrl: String): PendingCode {
            codes += "quick connect $baseUrl"
            return codeAnswer.await()
        }
        private suspend fun waiting(): Session = try {
            waitAnswer.await()
        } catch (e: CancellationException) {
            waitsCancelled++
            throw e
        }
        override suspend fun signInWithPlex(baseUrl: String, code: PendingCode): Session = waiting()
        override suspend fun signInWithQuickConnect(baseUrl: String, code: PendingCode): Session = waiting()
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val SessionViewModel.form get() = screen as Screen.SignIn
    private val SessionViewModel.code get() = form.page as SignInPage.Code
    private val SessionViewModel.mediaPage get() = form.page as SignInPage.MediaServer

    /** At the form with [offer] read for the typed server. */
    private suspend fun kotlinx.coroutines.test.TestScope.offered(gateway: Fake, offer: ServerOffer): SessionViewModel {
        val vm = SessionViewModel(gateway)
        advanceUntilIdle()
        vm.onServer("seerr.test:5055")
        vm.onServerDone()
        gateway.offerAnswer.complete(offer)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `the server is read once its field is done with - once per address - and the form keeps its fields`() = runTest(dispatcher) {
        val gateway = Fake()
        val vm = SessionViewModel(gateway)
        advanceUntilIdle()
        vm.onServer("seerr.test:5055")
        vm.onServerDone()
        assertTrue(vm.form.checking)
        vm.onServerDone()
        gateway.offerAnswer.complete(offer(plex = true))
        advanceUntilIdle()
        assertEquals(listOf(base), gateway.offers)
        assertEquals(offer(plex = true), vm.form.offer)
        assertFalse(vm.form.checking)
        assertEquals("Seerr", vm.form.serverTitle)
        vm.onServerDone()
        advanceUntilIdle()
        assertEquals(1, gateway.offers.size)
    }

    @Test
    fun `nothing is read for a server field that holds no address`() = runTest(dispatcher) {
        val gateway = Fake()
        val vm = SessionViewModel(gateway)
        advanceUntilIdle()
        vm.onServerDone()
        vm.onServer("   ")
        vm.onServerDone()
        advanceUntilIdle()
        assertTrue(gateway.offers.isEmpty())
    }

    @Test
    fun `another address drops the last one's offer - its buttons cannot act on the wrong server`() = runTest(dispatcher) {
        val gateway = Fake()
        val vm = offered(gateway, offer(plex = true))
        vm.onServer("other.test:5055")
        assertNull(vm.form.offer)
        vm.openPlex()
        assertNull(vm.form.page)
    }

    @Test
    fun `an address changed while its offer is read drops that read - even to text that is no address`() = runTest(dispatcher) {
        val gateway = Fake()
        val vm = SessionViewModel(gateway)
        advanceUntilIdle()
        vm.onServer("seerr.test:5055")
        vm.onServerDone()
        advanceUntilIdle()
        assertEquals(listOf(base), gateway.offers)
        assertTrue(vm.form.checking)
        vm.onServer("")
        assertFalse(vm.form.checking)
        gateway.offerAnswer.complete(offer(plex = true))
        advanceUntilIdle()
        assertNull(vm.form.offer)
        vm.openPlex()
        assertNull(vm.form.page)
        // The same address again reads it again.
        vm.onServer("seerr.test:5055")
        vm.onServerDone()
        advanceUntilIdle()
        assertEquals(listOf(base, base), gateway.offers)
        assertEquals(offer(plex = true), vm.form.offer)
    }

    @Test
    fun `a server that cannot be read says so - and the password form stays`() = runTest(dispatcher) {
        val gateway = Fake()
        val vm = SessionViewModel(gateway)
        advanceUntilIdle()
        vm.onServer("seerr.test:5055")
        vm.onServerDone()
        gateway.offerAnswer.completeExceptionally(SignInFailed(SignInFailure.UNREACHABLE, null))
        advanceUntilIdle()
        assertEquals(SignInFailure.UNREACHABLE, vm.form.failure)
        assertNull(vm.form.offer)
        assertFalse(vm.form.checking)
    }

    // ---- Plex -----------------------------------------------------------------------------------------------

    @Test
    fun `Plex - the code shows once plex-tv gives it - and the wait ends home with the form forgotten`() = runTest(dispatcher) {
        val gateway = Fake()
        val vm = offered(gateway, offer(plex = true))
        vm.openPlex()
        assertEquals(CodeState.STARTING, vm.code.state)
        assertNull(vm.code.code)
        gateway.codeAnswer.complete(PendingCode("X7K6"))
        advanceUntilIdle()
        assertEquals(SignInPage.Code(CodeKind.PLEX, code = "X7K6", state = CodeState.WAITING), vm.form.page)
        gateway.waitAnswer.complete(session)
        advanceUntilIdle()
        assertEquals(Screen.Home(session), vm.screen)
        assertEquals("", vm.server)
    }

    @Test
    fun `Plex - not offered - no page`() = runTest(dispatcher) {
        val gateway = Fake()
        val vm = offered(gateway, offer(password = true, kind = MediaServerKind.JELLYFIN))
        vm.openPlex()
        assertNull(vm.form.page)
        assertTrue(gateway.codes.isEmpty())
    }

    @Test
    fun `Plex - Cancel stops the wait and returns to the form on the Plex button`() = runTest(dispatcher) {
        val gateway = Fake()
        val vm = offered(gateway, offer(plex = true))
        vm.openPlex()
        gateway.codeAnswer.complete(PendingCode("X7K6"))
        advanceUntilIdle()
        vm.closePage()
        advanceUntilIdle()
        assertEquals(1, gateway.waitsCancelled)
        assertNull(vm.form.page)
        assertEquals(SignInControl.PLEX, vm.form.returnTo)
        assertEquals(offer(plex = true), vm.form.offer)
    }

    @Test
    fun `Plex - leaving the front ends the wait - plex-tv is asked only while the code is on screen`() = runTest(dispatcher) {
        val gateway = Fake()
        val vm = offered(gateway, offer(plex = true))
        vm.leftFront()
        assertNull(vm.form.page)
        vm.openPlex()
        gateway.codeAnswer.complete(PendingCode("X7K6"))
        advanceUntilIdle()
        vm.leftFront()
        advanceUntilIdle()
        assertEquals(1, gateway.waitsCancelled)
        assertNull(vm.form.page)
        assertEquals(SignInControl.PLEX, vm.form.returnTo)
    }

    @Test
    fun `Plex - a code that ran out offers a new one - which is asked for afresh`() = runTest(dispatcher) {
        val gateway = Fake()
        val vm = offered(gateway, offer(plex = true))
        vm.openPlex()
        gateway.codeAnswer.complete(PendingCode("X7K6"))
        advanceUntilIdle()
        gateway.waitAnswer.completeExceptionally(SignInFailed(SignInFailure.CODE_EXPIRED, null))
        advanceUntilIdle()
        assertEquals(CodeState.EXPIRED, vm.code.state)
        assertEquals(SignInFailure.CODE_EXPIRED, vm.code.failure)
        gateway.codeAnswer = CompletableDeferred()
        gateway.waitAnswer = CompletableDeferred()
        vm.newCode()
        assertEquals(CodeState.STARTING, vm.code.state)
        gateway.codeAnswer.complete(PendingCode("QR22"))
        advanceUntilIdle()
        assertEquals("QR22", vm.code.code)
        assertEquals(listOf("plex", "plex"), gateway.codes)
    }

    @Test
    fun `Plex - plex-tv unreachable is said on the page with Try Again`() = runTest(dispatcher) {
        val gateway = Fake()
        val vm = offered(gateway, offer(plex = true))
        vm.openPlex()
        gateway.codeAnswer.completeExceptionally(SignInFailed(SignInFailure.PLEX_UNREACHABLE, null))
        advanceUntilIdle()
        assertEquals(CodeState.FAILED, vm.code.state)
        assertEquals(SignInFailure.PLEX_UNREACHABLE, vm.code.failure)
    }

    @Test
    fun `Plex - Seerr refusing the account closes the page - the reason on the form`() = runTest(dispatcher) {
        val gateway = Fake()
        val vm = offered(gateway, offer(plex = true))
        vm.openPlex()
        gateway.codeAnswer.complete(PendingCode("X7K6"))
        advanceUntilIdle()
        gateway.waitAnswer.completeExceptionally(SignInFailed(SignInFailure.NOT_IMPORTED, "Seerr"))
        advanceUntilIdle()
        assertNull(vm.form.page)
        assertEquals(SignInFailure.NOT_IMPORTED, vm.form.failure)
        assertEquals(SignInControl.PLEX, vm.form.returnTo)
    }

    // ---- Jellyfin and Emby ----------------------------------------------------------------------------------------

    @Test
    fun `Jellyfin - its form opens - asks for the username - and signs in with it trimmed`() = runTest(dispatcher) {
        val gateway = Fake()
        val vm = offered(gateway, offer(password = true, quickConnect = true, kind = MediaServerKind.JELLYFIN))
        vm.openMediaServer()
        assertEquals(SignInPage.MediaServer(MediaServerKind.JELLYFIN, quickConnect = true), vm.form.page)
        vm.submitMediaServer()
        assertEquals(setOf(SignInProblem.EMAIL), vm.mediaPage.problems)
        assertTrue(gateway.mediaSignIns.isEmpty())
        vm.onUsername(" robin ")
        assertTrue(vm.mediaPage.problems.isEmpty())
        vm.onMediaPassword("robin-password")
        vm.submitMediaServer()
        vm.submitMediaServer()
        assertTrue(vm.mediaPage.submitting)
        advanceUntilIdle()
        assertEquals(listOf(SignInRequest(base, "robin", "robin-password")), gateway.mediaSignIns)
        gateway.mediaAnswer.complete(session)
        advanceUntilIdle()
        assertEquals(Screen.Home(session), vm.screen)
        assertEquals("", vm.mediaPassword)
        assertEquals("", vm.username)
    }

    @Test
    fun `Jellyfin - a refusal stays on its form with the reason - Back returns to the form on its button`() = runTest(dispatcher) {
        val gateway = Fake()
        val vm = offered(gateway, offer(password = true, kind = MediaServerKind.EMBY))
        vm.openMediaServer()
        vm.onUsername("ash")
        vm.submitMediaServer()
        gateway.mediaAnswer.completeExceptionally(SignInFailed(SignInFailure.NOT_IMPORTED, "Seerr"))
        advanceUntilIdle()
        assertEquals(SignInFailure.NOT_IMPORTED, vm.mediaPage.failure)
        assertFalse(vm.mediaPage.submitting)
        assertEquals(MediaServerKind.EMBY, vm.mediaPage.kind)
        vm.closePage()
        assertNull(vm.form.page)
        assertEquals(SignInControl.MEDIA_SERVER, vm.form.returnTo)
    }

    @Test
    fun `Quick Connect - from the Jellyfin form - Cancel comes back to it on Quick Connect`() = runTest(dispatcher) {
        val gateway = Fake()
        val vm = offered(gateway, offer(password = true, quickConnect = true, kind = MediaServerKind.JELLYFIN))
        vm.openMediaServer()
        vm.openQuickConnect()
        assertEquals(CodeKind.QUICK_CONNECT, vm.code.kind)
        gateway.codeAnswer.complete(PendingCode("525562"))
        advanceUntilIdle()
        assertEquals(listOf("quick connect $base"), gateway.codes)
        assertEquals("525562", vm.code.code)
        vm.closePage()
        advanceUntilIdle()
        assertEquals(1, gateway.waitsCancelled)
        assertEquals(SignInControl.QUICK_CONNECT, vm.mediaPage.returnTo)
    }

    @Test
    fun `Quick Connect - not offered - no page - and Seerr's refusal comes back on the Jellyfin form`() = runTest(dispatcher) {
        val gateway = Fake()
        val vm = offered(gateway, offer(password = true, quickConnect = false, kind = MediaServerKind.EMBY))
        vm.openMediaServer()
        vm.openQuickConnect()
        assertTrue(vm.form.page is SignInPage.MediaServer)

        val jellyfin = Fake()
        val vm2 = offered(jellyfin, offer(password = true, quickConnect = true, kind = MediaServerKind.JELLYFIN))
        vm2.openMediaServer()
        vm2.openQuickConnect()
        jellyfin.codeAnswer.complete(PendingCode("525562"))
        advanceUntilIdle()
        jellyfin.waitAnswer.completeExceptionally(SignInFailed(SignInFailure.NOT_IMPORTED, null))
        advanceUntilIdle()
        assertEquals(SignInFailure.NOT_IMPORTED, vm2.mediaPage.failure)
        assertEquals(SignInControl.QUICK_CONNECT, vm2.mediaPage.returnTo)
    }

    @Test
    fun `Quick Connect unavailable is said on its page with Try Again`() = runTest(dispatcher) {
        val gateway = Fake()
        val vm = offered(gateway, offer(password = true, quickConnect = true, kind = MediaServerKind.JELLYFIN))
        vm.openMediaServer()
        vm.openQuickConnect()
        gateway.codeAnswer.completeExceptionally(SignInFailed(SignInFailure.QUICK_CONNECT_UNAVAILABLE, null))
        advanceUntilIdle()
        assertEquals(CodeState.FAILED, vm.code.state)
    }

    // ---- sessions that cannot be renewed --------------------------------------------------------------------------

    @Test
    fun `a Plex session ending at launch offers its server again - read - with the reason`() = runTest(dispatcher) {
        val gateway = Fake(Restored.Rejected(SignInFailure.SESSION_ENDED, server = base))
        val vm = SessionViewModel(gateway)
        advanceUntilIdle()
        assertEquals(base, vm.server)
        assertEquals(listOf(base), gateway.offers)
        gateway.offerAnswer.complete(offer(plex = true))
        advanceUntilIdle()
        assertEquals(SignInFailure.SESSION_ENDED, vm.form.failure)
        assertEquals(offer(plex = true), vm.form.offer)
        // focus to the way back in
        assertEquals(SignInControl.PLEX, vm.form.returnTo)
    }

    @Test
    fun `a Plex session ending while in use goes to the form with its server and the reason`() = runTest(dispatcher) {
        val gateway = Fake(Restored.SignedIn(session))
        val vm = SessionViewModel(gateway)
        advanceUntilIdle()
        gateway.ended.trySend(SignInFailure.SESSION_ENDED)
        advanceUntilIdle()
        assertEquals(base, vm.server)
        assertEquals(SignInFailure.SESSION_ENDED, vm.form.failure)
        assertEquals(listOf(base), gateway.offers)
    }

    @Test
    fun `a refused password at launch leaves the server field empty as before`() = runTest(dispatcher) {
        val gateway = Fake(Restored.Rejected(SignInFailure.WRONG_CREDENTIALS))
        val vm = SessionViewModel(gateway)
        advanceUntilIdle()
        assertEquals("", vm.server)
        assertEquals(Screen.SignIn(failure = SignInFailure.WRONG_CREDENTIALS), vm.screen)
        assertTrue(gateway.offers.isEmpty())
    }
}
