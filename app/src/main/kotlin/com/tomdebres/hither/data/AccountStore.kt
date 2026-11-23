package com.tomdebres.hither.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/** The one server and the sign-in for it. */
data class Account(
    val baseUrl: String,
    /** Seerr's email for [AccountMethod.LOCAL], the Jellyfin or Emby username for [AccountMethod.MEDIA_SERVER], else empty. */
    val email: String,
    /** Kept to sign in again when the session ends; empty for a way in that has none (Plex, Quick Connect). */
    val password: String,
    val method: AccountMethod = AccountMethod.LOCAL,
) {
    /** Never the password: an Account can end up in a log line or a crash report by accident. */
    override fun toString(): String = "Account(baseUrl=…, email=…, password=…, method=$method)"
}

/**
 * How the account signed in (M8), which is how it signs in again when Seerr ends the session. Only the first two can:
 * Plex's token and a Quick Connect code are not kept, so those sessions end in the sign-in form (DECISIONS 235).
 */
enum class AccountMethod {
    /** Seerr's own email and password (`/auth/local`). */
    LOCAL,

    /** A Jellyfin or Emby username and password (`/auth/jellyfin`). */
    MEDIA_SERVER,

    /** Plex's link code (`/auth/plex`). */
    PLEX,

    /** Jellyfin's Quick Connect. */
    QUICK_CONNECT,
    ;

    /** Whether Hither can sign this account in again by itself when the session ends. */
    val renews: Boolean get() = this == LOCAL || this == MEDIA_SERVER
}

private val Context.accountDataStore: DataStore<Preferences> by preferencesDataStore(name = "account")

/** Where the account and the session are kept. An interface so the gateway is tested without a device. */
interface AccountStorage {
    suspend fun account(): Account?
    suspend fun save(account: Account)
    suspend fun cookies(): List<SavedCookie>
    suspend fun saveCookies(cookies: List<SavedCookie>)

    /** Sign-out: nothing of the account or the session stays behind. */
    suspend fun clear()
}

/**
 * Everything Hither keeps on the device (`docs/privacy.md`): the server's address and the email
 * in the clear, the password and the session cookies sealed by [SecretBox]. Android backup is
 * off for the app, so none of it leaves the device.
 */
class AccountStore(context: Context, private val box: SecretBox) : AccountStorage {
    private val store = context.applicationContext.accountDataStore

    override suspend fun account(): Account? {
        val prefs = store.data.first()
        val baseUrl = prefs[SERVER] ?: return null
        val email = prefs[EMAIL] ?: return null
        val password = box.open(prefs[PASSWORD]) ?: return null
        // An account kept before M8 has no method: it signed in with Seerr's own password.
        val method = AccountMethod.entries.firstOrNull { it.name == prefs[METHOD] } ?: AccountMethod.LOCAL
        return Account(baseUrl, email, password, method)
    }

    override suspend fun save(account: Account) {
        val sealed = box.seal(account.password)
        store.edit {
            it[SERVER] = account.baseUrl
            it[EMAIL] = account.email
            it[PASSWORD] = sealed
            it[METHOD] = account.method.name
        }
    }

    override suspend fun cookies(): List<SavedCookie> = SavedCookie.decode(box.open(store.data.first()[COOKIES]))

    override suspend fun saveCookies(cookies: List<SavedCookie>) {
        val sealed = box.seal(SavedCookie.encode(cookies))
        store.edit { it[COOKIES] = sealed }
    }

    override suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val SERVER = stringPreferencesKey("server")
        val EMAIL = stringPreferencesKey("email")
        val PASSWORD = stringPreferencesKey("password")
        val COOKIES = stringPreferencesKey("cookies")
        val METHOD = stringPreferencesKey("method")
    }
}
