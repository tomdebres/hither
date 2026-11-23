package com.tomdebres.hither.ui.person

import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.rules.PersonPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** A person's page (M6, DECISIONS 100) on the JVM, against a scripted source. */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonModelTest {
    private class Script : PersonSource {
        val asked = mutableListOf<Int>()
        val answers = ArrayDeque<CompletableDeferred<PersonPage>>()

        override suspend fun page(personId: Int): PersonPage {
            asked += personId
            return CompletableDeferred<PersonPage>().also { answers.addLast(it) }.await()
        }
    }

    private fun page() = PersonPage(7, "Pat", null, null, null, null, emptyList(), listOf("About Pat."), emptyList())

    @Test
    fun `the person is asked for once on arrival, and shows when it comes`() = runTest {
        val script = Script()
        val model = PersonModel(script, 7, "Pat", this, StandardTestDispatcher(testScheduler))
        assertEquals(PersonState.Loading, model.state)
        model.start()
        model.start()
        advanceUntilIdle()
        assertEquals(listOf(7), script.asked)
        script.answers.removeFirst().complete(page())
        advanceUntilIdle()
        assertEquals(PersonState.Ready(page()), model.state)
    }

    @Test
    fun `a failure offers Try Again, which asks once more and only after a failure`() = runTest {
        val script = Script()
        val model = PersonModel(script, 7, "Pat", this, StandardTestDispatcher(testScheduler))
        model.start()
        advanceUntilIdle()
        model.retry()
        advanceUntilIdle()
        assertEquals("not while the first is out", 1, script.asked.size)
        script.answers.removeFirst().completeExceptionally(SeerrException.Unreachable(RuntimeException("down")))
        advanceUntilIdle()
        assertEquals(PersonState.Failed, model.state)
        model.retry()
        advanceUntilIdle()
        assertEquals(2, script.asked.size)
        script.answers.removeFirst().complete(page())
        advanceUntilIdle()
        assertEquals(PersonState.Ready(page()), model.state)
    }
}
