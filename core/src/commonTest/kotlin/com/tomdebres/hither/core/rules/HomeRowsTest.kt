package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.TvDetails
import com.tomdebres.hither.core.model.User
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class HomeRowsTest {
    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val now = 1_800_000_000_000L

    private fun ready(id: Int, readyAt: Long?) = ReadyTitle(MediaType.MOVIE, id, readyAt)

    @Test
    fun `a look is due at most once a minute in the app and every 25 minutes in the background`() {
        assertTrue(lookDue(null, now, background = true))
        assertTrue(lookDue(null, now, background = false))
        assertFalse(lookDue(now - 59_000, now, background = false))
        assertTrue(lookDue(now - minute, now, background = false))
        assertFalse(lookDue(now - 24 * minute, now, background = true))
        assertTrue(lookDue(now - BACKGROUND_GAP_MINUTES * minute, now, background = true))
        // A look in the app ten minutes ago: the background one waits for its next period.
        assertFalse(lookDue(now - 10 * minute, now, background = true))
        // A clock set back: due.
        assertTrue(lookDue(now + day, now, background = true))
        assertEquals(30L, BACKGROUND_LOOK_MINUTES)
    }

    @Test
    fun `Watch Next holds what became ready in the last 14 days and was not opened since`() {
        val titles = listOf(
            ready(1, now - minute),
            ready(2, now - 13 * day),
            ready(3, now - 14 * day), // a fortnight: gone
            ready(4, null), // no time: cannot be new
            ready(5, now - 2 * day), // opened yesterday: gone
            ready(6, now - minute), // opened before it became ready again: back
        )
        val dismissed = mapOf("movie:5" to now - day, "movie:6" to now - 3 * day)
        assertEquals(listOf(1, 2, 6), watchNextTitles(titles, dismissed, now).map { it.tmdbId })
    }

    @Test
    fun `opening a title dismisses it even when the server's clock is ahead`() {
        assertEquals(now, dismissal(now - day, now))
        assertEquals(now + 5 * minute, dismissal(now + 5 * minute, now))
        assertEquals(now, dismissal(null, now))
        val title = ready(1, now + 5 * minute)
        assertEquals(emptyList<ReadyTitle>(), watchNextTitles(listOf(title), mapOf(title.key to dismissal(title.readyAt, now)), now))
        // Dismissals older than Watch Next keeps a title can go.
        assertEquals(mapOf("movie:1" to now - day), liveDismissals(mapOf("movie:1" to now - day, "movie:2" to now - 15 * day), now))
    }

    @Test
    fun `a home-screen link names exactly one title`() {
        val link = TitleLink(MediaType.TV, 1421)
        assertEquals("hither://title/tv/1421", link.uri)
        assertEquals("tv:1421", link.key)
        assertEquals(link, parseTitleLink(link.uri))
        assertEquals(TitleLink(MediaType.MOVIE, 550), parseTitleLink("hither://title/movie/550"))
        for (other in listOf(
            null, "", "hither://title/person/1", "hither://title/movie/", "hither://title/movie/0", "hither://title/movie/-5",
            "hither://title/movie/550/x", "hither://title/movie/550?x=1", "https://title/movie/550", "hither://other/movie/550",
            "hither://title/movie/99999999999", "hither://title/movie/055",
        )) {
            assertNull(parseTitleLink(other), other)
        }
    }

    @Test
    fun `waiting for approval is for an account that may approve`() {
        assertTrue(seesWaiting(User(id = 1, permissions = Permission.MANAGE_REQUESTS + Permission.REQUEST)))
        assertTrue(seesWaiting(User(id = 1, permissions = Permission.ADMIN)))
        assertFalse(seesWaiting(User(id = 8, permissions = 67744)))
        assertFalse(seesWaiting(User(id = 8, permissions = Permission.REQUEST_VIEW)))
        assertFalse(seesWaiting(User(id = 8, permissions = null)))
    }

    @Test
    fun `a card's art comes through the server's image proxy - the backdrop at 16 to 9`() {
        val base = "http://seerr.example.invalid:5055"
        val images = ImageSource(base, cacheImages = true, tmdbImages = ImageSource.TMDB_IMAGES)
        val film = titleArt(MovieDetails(id = 550, title = "Fight Club", posterPath = "/p.jpg", backdropPath = "/b.jpg", releaseDate = "1999-10-15"), images)
        assertEquals(TitleArt("Fight Club", "1999", "$base/imageproxy/tmdb/t/p/w300_and_h450_face/p.jpg", "$base/imageproxy/tmdb/t/p/w780/b.jpg"), film)
        val series = titleArt(TvDetails(id = 1421, name = "Modern Family", posterPath = "../x.jpg", firstAirDate = ""), images)
        assertEquals(TitleArt("Modern Family", null, null, null), series)
    }
}
