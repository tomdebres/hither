package com.tomdebres.hither.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tomdebres.hither.core.newPlexClientIdentifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.plexDataStore: DataStore<Preferences> by preferencesDataStore(name = "plex")

/**
 * The client identifier plex.tv knows this install by (M8): made the first time a Plex sign-in asks for a code, then
 * kept for the life of the install, signing out included, as Plex asks of an app (one device, one id). It is not a
 * secret and identifies nothing but this install; clearing the app's data or uninstalling it is the end of it.
 */
class PlexIdentity(context: Context) {
    private val store = context.applicationContext.plexDataStore
    private val lock = Mutex()

    suspend fun clientIdentifier(): String = lock.withLock {
        store.data.first()[CLIENT_ID] ?: newPlexClientIdentifier().also { made -> store.edit { it[CLIENT_ID] = made } }
    }

    private companion object {
        val CLIENT_ID = stringPreferencesKey("client_id")
    }
}
