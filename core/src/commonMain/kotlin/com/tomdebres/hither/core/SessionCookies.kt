package com.tomdebres.hither.core

import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Where a platform keeps a server's session cookies between launches, as one text: the Keychain on
 * tvOS (`KeychainVault`, M7). Android's app came first and keeps its own in DataStore (`PersistedCookies`
 * in `app`), by the same rules.
 */
interface CookieVault {
    /**
     * The text last written, or null when there is none. No @Throws: Objective-C cannot say both "nil" and
     * "failed", so a Swift vault that cannot read gives null (signed out, sign in again). [KeychainVault]
     * throws from Kotlin, which the client reports as unreachable.
     */
    fun read(): String?

    /** Keeps [text] in place of whatever was there. Throws when it cannot: a session must not vanish quietly. */
    @Throws(IllegalStateException::class)
    fun write(text: String)

    /** Forgets the text; nothing there is no failure. */
    @Throws(IllegalStateException::class)
    fun delete()
}

/**
 * The cookie storage a [SeerrClient] is given where cookies live in a [CookieVault]: Ktor's own
 * in-memory storage for one server, seeded with what the vault holds and written back whenever the
 * server sets something new. Only that server's cookies are ever kept, so a sign-in survives a new
 * client and a new launch, and [forget] signs the device out.
 */
class SessionCookies(baseUrl: String, private val vault: CookieVault) : CookiesStorage {
    private val server = Url(baseUrl)
    private val lock = Mutex()
    private var memory = AcceptAllCookiesStorage()

    /** What the vault holds, as last read or written; null until it is first read. */
    private var kept: List<StoredCookie>? = null

    private suspend fun restored(): AcceptAllCookiesStorage = lock.withLock {
        if (kept == null) {
            val stored = StoredCookie.decode(vault.read())
            stored.forEach { memory.addCookie(server, it.toCookie()) }
            kept = stored
        }
        memory
    }

    override suspend fun get(requestUrl: Url): List<Cookie> = restored().get(requestUrl)

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        restored()
        lock.withLock {
            memory.addCookie(requestUrl, cookie)
            val now = memory.get(server).map(StoredCookie::of)
            if (now != kept) {
                // Written before it counts as kept, so a write that failed is tried again next time.
                vault.write(StoredCookie.encode(now))
                kept = now
            }
        }
    }

    /** Signs the device out of this server: the vault's text and the cookies in memory are gone. */
    @Throws(IllegalStateException::class, CancellationException::class)
    suspend fun forget() = lock.withLock {
        vault.delete()
        memory.close()
        memory = AcceptAllCookiesStorage()
        kept = emptyList()
    }

    override fun close() = memory.close()
}

/** A cookie in the form a [CookieVault] keeps it: the same fields as the Android app's `SavedCookie`. */
@Serializable
internal data class StoredCookie(
    val name: String,
    val value: String,
    // Cookies parsed from Set-Cookie are RAW; re-encoding one would change the value the server sees.
    val encoding: String = CookieEncoding.URI_ENCODING.name,
    val domain: String? = null,
    val path: String? = null,
    val expiresAt: Long? = null,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
) {
    fun toCookie(): Cookie = Cookie(
        name = name,
        value = value,
        encoding = CookieEncoding.entries.firstOrNull { it.name == encoding } ?: CookieEncoding.URI_ENCODING,
        expires = expiresAt?.let { GMTDate(it) },
        domain = domain,
        path = path,
        secure = secure,
        httpOnly = httpOnly,
    )

    companion object {
        fun of(cookie: Cookie) = StoredCookie(
            name = cookie.name,
            value = cookie.value,
            encoding = cookie.encoding.name,
            domain = cookie.domain,
            path = cookie.path,
            expiresAt = cookie.expires?.timestamp,
            secure = cookie.secure,
            httpOnly = cookie.httpOnly,
        )

        private val json = Json { ignoreUnknownKeys = true }
        private val list = ListSerializer(serializer())

        fun encode(cookies: List<StoredCookie>): String = json.encodeToString(list, cookies)

        /** The stored cookies, or none when the text is missing or not what [encode] wrote. */
        fun decode(text: String?): List<StoredCookie> =
            if (text.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(list, text) }.getOrDefault(emptyList())
    }
}
