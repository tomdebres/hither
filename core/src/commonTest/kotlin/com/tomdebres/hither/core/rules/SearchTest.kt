package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Fixtures
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.Page
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.Test

/** Search's own rules: what is sent for what was typed, and the cards a page of results makes. */
class SearchTest {
    private val base = "http://seerr.test:5055"
    private val images = ImageSource(base, cacheImages = true, tmdbImages = ImageSource.TMDB_IMAGES)

    @Test
    fun `the recorded search makes a card for each film and series - in the server's order`() {
        val page = Fixtures.read<Page<MediaResult>>("search")
        val cards = appendSearchPage(emptyList(), page.results, images)
        assertEquals(page.results.map { "${it.mediaType}:${it.id}" }, cards.map { it.key })
        assertEquals("Star Wars: The Clone Wars", cards.first().title)
    }

    @Test
    fun `people and unknown types are left out - repeats across pages too - and nothing is hidden as Discover hides it`() {
        val first = listOf(
            MediaResult(id = 1, mediaType = "movie", title = "A"),
            MediaResult(id = 2, mediaType = "person", name = "Someone"),
            // available: Discover with hideAvailable on would drop it; search shows it (Seerr's search page)
            MediaResult(id = 3, mediaType = "tv", name = "B", mediaInfo = MediaInfo(status = MediaStatus.AVAILABLE)),
            MediaResult(id = 4, mediaType = "collection", name = "C"),
        )
        val cards = appendSearchPage(emptyList(), first, images)
        assertEquals(listOf("movie:1", "tv:3"), cards.map { it.key })
        assertEquals("Available", cards[1].badge?.text)
        val more = appendSearchPage(cards, listOf(MediaResult(id = 1, mediaType = "movie", title = "A"), MediaResult(id = 1, mediaType = "tv", name = "D")), images)
        assertEquals(listOf("movie:1", "tv:3", "tv:1"), more.map { it.key })
        // a page that adds nothing gives the same list back
        assertSame(more, appendSearchPage(more, listOf(MediaResult(id = 2, mediaType = "person")), images))
    }

    @Test
    fun `what is typed is sent without the spaces around it - and blank is nothing to send`() {
        assertEquals("star wars", searchQuery("  star wars "))
        assertEquals("a&b c", searchQuery("a&b c"))
        assertNull(searchQuery(""))
        assertNull(searchQuery("   "))
    }

    @Test
    fun `a grid asks for more two rows ahead - and fills a screen first`() {
        // six across, two rows on screen: 12 fills it and 12 is the lookahead
        assertTrue(wantsNextPage(count = 8, focused = -1, page = 1, totalPages = 3, busy = false, fills = 12, lookahead = 12))
        assertFalse(wantsNextPage(count = 20, focused = 7, page = 1, totalPages = 3, busy = false, fills = 12, lookahead = 12))
        assertTrue(wantsNextPage(count = 20, focused = 8, page = 1, totalPages = 3, busy = false, fills = 12, lookahead = 12))
        assertFalse(wantsNextPage(count = 20, focused = 19, page = 3, totalPages = 3, busy = false, fills = 12, lookahead = 12))
    }
}
