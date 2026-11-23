package com.tomdebres.hither.ready

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tomdebres.hither.core.rules.LastSeen
import com.tomdebres.hither.core.rules.TitleArt
import com.tomdebres.hither.core.rules.lastSeenJson
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/** A ready title as the last look left it: its key and when it became ready. */
@Serializable
data class ReadyEntry(val key: String, val readyAt: Long? = null)

/**
 * What Hither keeps on the device for the home screen and the alerts (`docs/privacy.md`): the last
 * look at the user's requests ([lastSeen]: the snapshot and the changes not yet shown), the titles
 * that were ready, each title's name and pictures (so it is asked for once), when a title was last
 * opened (Watch Next leaves it out after that), when the server was last asked, and for an approver
 * how many requests wait for approval.
 */
@Serializable
data class ReadyState(
    val lastSeen: LastSeen? = null,
    val ready: List<ReadyEntry> = emptyList(),
    val art: Map<String, TitleArt> = emptyMap(),
    val dismissed: Map<String, Long> = emptyMap(),
    val lastLookAt: Long? = null,
    /** Requests waiting for approval, server-wide, for an account that may approve; null for one that may not. */
    val waiting: Int? = null,
)

/** Where [ReadyState] is kept: the device, or a test's memory. */
interface ReadyStorage {
    suspend fun read(): ReadyState

    /** Changes the state in one step: [change] sees the latest, and nothing else writes in between. */
    suspend fun update(change: (ReadyState) -> ReadyState)

    /** Sign-out, or another account: nothing of the last account stays. */
    suspend fun clear()
}

private val Context.readyDataStore: DataStore<Preferences> by preferencesDataStore(name = "ready")

/** [ReadyState] as JSON in a Preferences DataStore. A value that will not read is a fresh start. */
class ReadyStore(context: Context) : ReadyStorage {
    private val store = context.applicationContext.readyDataStore

    override suspend fun read(): ReadyState = decode(store.data.first()[STATE])

    override suspend fun update(change: (ReadyState) -> ReadyState) {
        store.edit { prefs -> prefs[STATE] = lastSeenJson.encodeToString(ReadyState.serializer(), change(decode(prefs[STATE]))) }
    }

    override suspend fun clear() {
        store.edit { it.clear() }
    }

    private fun decode(text: String?): ReadyState =
        text?.let { runCatching { lastSeenJson.decodeFromString(ReadyState.serializer(), it) }.getOrNull() } ?: ReadyState()

    private companion object {
        val STATE = stringPreferencesKey("state")
    }
}
