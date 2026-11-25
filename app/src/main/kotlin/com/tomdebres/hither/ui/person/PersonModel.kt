package com.tomdebres.hither.ui.person

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tomdebres.hither.core.rules.CreditKind
import com.tomdebres.hither.core.rules.PersonPage
import com.tomdebres.hither.core.rules.personPage
import com.tomdebres.hither.session.SeerrAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a person's page is. */
sealed interface PersonState {
    data object Loading : PersonState

    data object Failed : PersonState

    data class Ready(val page: PersonPage) : PersonState
}

/** Where focus was when the page was left for a title's, so Back lands there (DECISIONS 98, 100). */
data class CreditFocus(val kind: CreditKind, val index: Int)

/**
 * A person's page (M6, DECISIONS 100), opened from a cast card or a search result: their details and
 * credits read together on arrival ([start]) and on Try Again ([retry]). Kept on the page stack, so its
 * scroll, each row's and the card left are where they were when a title's page goes back to it.
 */
class PersonModel(
    private val source: PersonSource,
    val personId: Int,
    /** The name the card that opened the page carried, shown while the page loads. */
    val name: String,
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher = Dispatchers.Default,
) {
    var state: PersonState by mutableStateOf(PersonState.Loading)
        private set

    val scroll = ScrollState(0)

    /** Each credits row's scroll, by kind. */
    val rowStates: Map<CreditKind, LazyListState> = CreditKind.entries.associateWith { LazyListState() }

    /** The card each row was left on, so focus comes back to it; read by the cards through `derivedStateOf`. */
    val rowIndex = mutableStateMapOf<CreditKind, Int>().apply { CreditKind.entries.forEach { put(it, 0) } }

    /** Where focus was when a title's page was opened from here; taken (and cleared) on coming back. */
    var returnFocus: CreditFocus? = null

    private var started = false
    private var generation = 0

    fun start() {
        if (started) return
        started = true
        load()
    }

    fun retry() {
        if (state == PersonState.Failed) load()
    }

    private fun load() {
        val generation = ++generation
        state = PersonState.Loading
        scope.launch {
            val page = try {
                withContext(work) { source.page(personId) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (this@PersonModel.generation == generation) state = PersonState.Failed
                return@launch
            }
            if (this@PersonModel.generation == generation) state = PersonState.Ready(page)
        }
    }
}

/** Where a person's page comes from: the signed-in server, or a test's script. */
fun interface PersonSource {
    suspend fun page(personId: Int): PersonPage
}

/** The person's details and credits from the signed-in server, asked for together. */
fun seerrPersonSource(access: SeerrAccess) = PersonSource { personId ->
    access.call { client ->
        coroutineScope {
            val details = async { client.person(personId) }
            val credits = async { client.personCredits(personId) }
            personPage(details.await(), credits.await(), client.images)
        }
    }
}
