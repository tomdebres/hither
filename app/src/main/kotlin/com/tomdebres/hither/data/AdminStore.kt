package com.tomdebres.hither.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tomdebres.hither.core.rules.PinTries
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The TV's admin account as it is kept (M5, DECISIONS 91, 92): a local Seerr account with Manage
 * Requests that Tom made for the TV, on the family's server. Its password, so that Tom types it once
 * and Hither signs in again when Seerr's 30-day session ends; the session's cookies and when it was
 * signed in; who it is, for the admin mark; and the PIN's hash, never its digits. All of it is sealed.
 */
@Serializable
data class KeptAdmin(
    val baseUrl: String,
    val email: String,
    val password: String,
    val userId: Int,
    val displayName: String,
    val pin: PinHash,
    val cookies: List<SavedCookie> = emptyList(),
    /** When the kept session was signed in (the device's clock); null for none kept. */
    val signedInAt: Long? = null,
    /** The PIN's wrong tries in a row and the wait they made (DECISIONS 93). */
    val tries: PinTries = PinTries(),
) {
    /** Never a credential: a KeptAdmin can end up in a log line or a crash report by accident. */
    override fun toString(): String = "KeptAdmin(userId=$userId, …)"
}

/** Where the admin account is kept. An interface so the gateway is tested without a device. */
interface AdminStorage {
    /** The kept admin, or null: none set up, or a record that will not open (its key gone). */
    suspend fun admin(): KeptAdmin?

    suspend fun save(admin: KeptAdmin)

    /** Removing the admin account: the record, and the key that sealed it. */
    suspend fun clear()
}

private val Context.adminDataStore: DataStore<Preferences> by preferencesDataStore(name = "admin")

/**
 * The admin's record in its own DataStore, as one value sealed by [box] (AES-256-GCM, a Keystore key
 * of its own: `hither.admin`). Nothing of it is in the clear, its email included. Android's backup is
 * off for the app and the data-extraction rules leave every file out of cloud backup and device
 * transfer; a copy that left the TV would not open anyway, since the key stays in the TV's Keystore.
 * [clear] deletes the record and the key.
 */
class AdminStore(context: Context, private val box: SecretBox = SecretBox(KEY)) : AdminStorage {
    private val store = context.applicationContext.adminDataStore

    override suspend fun admin(): KeptAdmin? {
        val opened = box.open(store.data.first()[RECORD]) ?: return null
        return runCatching { json.decodeFromString(KeptAdmin.serializer(), opened) }.getOrNull()
    }

    override suspend fun save(admin: KeptAdmin) {
        val sealed = box.seal(json.encodeToString(KeptAdmin.serializer(), admin))
        store.edit { it[RECORD] = sealed }
    }

    override suspend fun clear() {
        store.edit { it.clear() }
        box.forget()
    }

    companion object {
        /** The Keystore alias of the admin's own key, apart from the family account's. */
        const val KEY = "hither.admin"
        private val RECORD = stringPreferencesKey("admin")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
