package com.tomdebres.hither.data

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedCookiesTest {
    private val server = Url("http://seerr.test:5055")
    private val session = Cookie(
        name = "connect.sid",
        value = "s:abc.def+/=",
        encoding = CookieEncoding.URI_ENCODING,
        expires = GMTDate(4_102_444_800_000), // 2100: not expired while this test lives
        path = "/",
        httpOnly = true,
    )

    @Test
    fun `a cookie survives being saved and read back`() {
        val text = SavedCookie.encode(listOf(SavedCookie.of(session)))
        val back = SavedCookie.decode(text).single().toCookie()
        assertEquals(session.name, back.name)
        assertEquals(session.value, back.value)
        assertEquals(session.encoding, back.encoding)
        assertEquals(session.expires, back.expires)
        assertEquals(session.path, back.path)
        assertTrue(back.httpOnly)
    }

    @Test
    fun `text that is missing or not ours reads as no cookies`() {
        assertEquals(emptyList<SavedCookie>(), SavedCookie.decode(null))
        assertEquals(emptyList<SavedCookie>(), SavedCookie.decode(""))
        assertEquals(emptyList<SavedCookie>(), SavedCookie.decode("not json"))
        assertEquals(emptyList<SavedCookie>(), SavedCookie.decode("""{"name":"x"}"""))
    }

    @Test
    fun `what the server sets is saved, once per change`() = runTest {
        val saves = mutableListOf<List<SavedCookie>>()
        val cookies = PersistedCookies(server, initial = emptyList(), save = { saves += it })

        cookies.addCookie(server, session)
        assertEquals(1, saves.size)
        assertEquals("connect.sid", saves.last().single().name)

        // the same cookie again changes nothing, so nothing is written
        cookies.addCookie(server, session)
        assertEquals(1, saves.size)

        // a rolled session replaces the old one
        cookies.addCookie(server, session.copy(value = "s:new"))
        assertEquals(2, saves.size)
        assertEquals(listOf("s:new"), saves.last().map { it.value })
    }

    @Test
    fun `a saved session is offered to its server on the next launch, and to no other host`() = runTest {
        val saved = listOf(SavedCookie.of(session))
        val cookies = PersistedCookies(server, initial = saved, save = { error("nothing changed, nothing to save") })

        assertEquals(listOf("s:abc.def+/="), cookies.get(Url("http://seerr.test:5055/api/v1/auth/me")).map { it.value })
        assertEquals(emptyList<Cookie>(), cookies.get(Url("http://elsewhere.test/api/v1/auth/me")))
    }
}
