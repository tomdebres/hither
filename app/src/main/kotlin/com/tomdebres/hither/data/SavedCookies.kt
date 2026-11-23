package com.tomdebres.hither.data

import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A cookie in the form it is stored between launches. */
@Serializable
data class SavedCookie(
    val name: String,
    val value: String,
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
        fun of(cookie: Cookie) = SavedCookie(
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

        fun encode(cookies: List<SavedCookie>): String = json.encodeToString(list, cookies)

        /** The stored cookies, or none when the text is missing or not what [encode] wrote. */
        fun decode(text: String?): List<SavedCookie> =
            if (text.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(list, text) }.getOrDefault(emptyList())
    }
}

/**
 * The cookie storage core's client is given: Ktor's own in-memory storage for one server,
 * seeded with what was saved last time and written back through [save] whenever the server
 * sets something new. Only that server's cookies are ever kept.
 */
class PersistedCookies(
    private val server: Url,
    private val initial: List<SavedCookie>,
    private val save: suspend (List<SavedCookie>) -> Unit,
) : CookiesStorage {
    private val memory = AcceptAllCookiesStorage()
    private var restored = false
    private var lastSaved = initial

    private suspend fun restore() {
        if (restored) return
        restored = true
        initial.forEach { memory.addCookie(server, it.toCookie()) }
    }

    override suspend fun get(requestUrl: Url): List<Cookie> {
        restore()
        return memory.get(requestUrl)
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        restore()
        memory.addCookie(requestUrl, cookie)
        val now = memory.get(server).map(SavedCookie::of)
        if (now != lastSaved) {
            lastSaved = now
            save(now)
        }
    }

    override fun close() = memory.close()
}
