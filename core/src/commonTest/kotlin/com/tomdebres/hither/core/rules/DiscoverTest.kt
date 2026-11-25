package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Fixtures
import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.DownloadItem
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.DiscoverQuery
import com.tomdebres.hither.core.model.Page
import com.tomdebres.hither.core.model.PublicSettings
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.Test

class DiscoverTest {
    private val base = "http://seerr.test:5055"
    private val images = ImageSource(base, cacheImages = true, tmdbImages = ImageSource.TMDB_IMAGES)
    private val open = PublicSettings()

    private fun result(
        id: Int,
        type: String? = "movie",
        status: Int? = null,
        downloading: Boolean = false,
        activeRequest: Boolean = false,
    ) = MediaResult(
        id = id,
        mediaType = type,
        title = if (type == "movie") "Film $id" else null,
        name = if (type != "movie") "Series $id" else null,
        posterPath = "/p$id.jpg",
        releaseDate = "2026-05-01",
        mediaInfo = status?.let {
            MediaInfo(
                tmdbId = id,
                status = it,
                downloadStatus = if (downloading) listOf(DownloadItem(title = "x")) else emptyList(),
                hasActiveRequest = activeRequest,
            )
        },
    )

    private fun cards(vararg results: MediaResult) = appendPage(emptyList(), results.toList(), images, open, Permission.REQUEST)

    // ---- the card --------------------------------------------------------------------------------

    @Test
    fun `a film's card carries what the card draws - the poster through the proxy`() {
        val card = titleCard(result(7, status = MediaStatus.AVAILABLE), images)!!
        assertEquals("movie:7", card.key)
        assertEquals(MediaType.MOVIE, card.type)
        assertEquals("Movie", card.type.chip)
        assertEquals("Film 7", card.title)
        assertEquals("2026", card.year)
        assertEquals("$base/imageproxy/tmdb/t/p/w300_and_h450_face/p7.jpg", card.poster)
        assertEquals(StatusWord.AVAILABLE, card.badge?.word)
    }

    @Test
    fun `a series is Series on its chip and keyed apart from a film with the same id`() {
        val series = titleCard(result(7, type = "tv"), images)!!
        assertEquals("Series", series.type.chip)
        assertEquals("tv:7", series.key)
        assertEquals(2, cards(result(7), result(7, type = "tv")).size)
    }

    @Test
    fun `people and types this build does not know get no card`() {
        assertNull(titleCard(result(1, type = "person"), images))
        assertNull(titleCard(result(2, type = "collection"), images))
        assertNull(titleCard(result(3, type = null), images))
    }

    @Test
    fun `the card's badge is the regular copy's status - Processing while downloading - none when unknown`() {
        assertNull(titleCard(result(1), images)!!.badge)
        assertNull(titleCard(result(1, status = MediaStatus.UNKNOWN), images)!!.badge)
        assertEquals(StatusWord.REQUESTED, titleCard(result(1, status = MediaStatus.PROCESSING), images)!!.badge?.word)
        val downloading = titleCard(result(1, status = MediaStatus.PROCESSING, downloading = true), images)!!.badge!!
        assertEquals(StatusWord.PROCESSING, downloading.word)
        assertTrue(downloading.inProgress)
        // the 4K copy's status is the media page's to show, not the card's
        val only4k = result(1).copy(mediaInfo = MediaInfo(tmdbId = 1, status4k = MediaStatus.AVAILABLE))
        assertNull(titleCard(only4k, images)!!.badge)
    }

    @Test
    fun `every recorded row and search result that is a film or series makes a card`() {
        for (name in listOf("discover-trending", "discover-movies", "discover-tv", "discover-movies-upcoming", "search")) {
            val page = Fixtures.read<Page<MediaResult>>(name)
            val made = page.results.mapNotNull { titleCard(it, images) }
            assertEquals(page.results.count { it.type != null }, made.size, name)
            assertTrue(made.all { it.title.isNotBlank() && it.poster!!.startsWith("$base/imageproxy/") }, name)
        }
    }

    // ---- what Discover shows -------------------------------------------------------------------------

    @Test
    fun `hide available leaves out available and partly available titles - and only with the switch on`() {
        val hide = PublicSettings(hideAvailable = true)
        assertFalse(shownOnDiscover(result(1, status = MediaStatus.AVAILABLE), hide, Permission.REQUEST))
        assertFalse(shownOnDiscover(result(1, status = MediaStatus.PARTIALLY_AVAILABLE), hide, Permission.REQUEST))
        assertTrue(shownOnDiscover(result(1, status = MediaStatus.PROCESSING), hide, Permission.REQUEST))
        assertTrue(shownOnDiscover(result(1), hide, Permission.REQUEST))
        assertTrue(shownOnDiscover(result(1, status = MediaStatus.AVAILABLE), open, Permission.REQUEST))
    }

    @Test
    fun `hide blocklisted applies only to a user who manages the blocklist - as on the web`() {
        val hide = PublicSettings(hideBlocklisted = true)
        val blocked = result(1, status = MediaStatus.BLOCKLISTED)
        assertTrue(shownOnDiscover(blocked, hide, Permission.REQUEST))
        assertFalse(shownOnDiscover(blocked, hide, Permission.MANAGE_BLOCKLIST))
        assertFalse(shownOnDiscover(blocked, hide, Permission.ADMIN))
        assertTrue(shownOnDiscover(blocked, open, Permission.ADMIN))
    }

    @Test
    fun `hide requested leaves out titles with an open request`() {
        val hide = PublicSettings(hideRequested = true)
        assertFalse(shownOnDiscover(result(1, status = MediaStatus.PENDING, activeRequest = true), hide, Permission.REQUEST))
        assertTrue(shownOnDiscover(result(1, status = MediaStatus.AVAILABLE), hide, Permission.REQUEST))
        assertTrue(shownOnDiscover(result(1, status = MediaStatus.PENDING, activeRequest = true), open, Permission.REQUEST))
    }

    // ---- pages ---------------------------------------------------------------------------------------

    @Test
    fun `a further page appends what is new and drops what the row already has`() {
        val first = cards(result(1), result(2), result(3))
        val more = appendPage(first, listOf(result(3), result(4), result(1), result(5)), images, open, Permission.REQUEST)
        assertEquals(listOf("movie:1", "movie:2", "movie:3", "movie:4", "movie:5"), more.map { it.key })
    }

    @Test
    fun `a page that repeats itself inside is cut to one card per title`() {
        assertEquals(listOf("movie:1", "movie:2"), cards(result(1), result(2), result(1)).map { it.key })
    }

    @Test
    fun `a page with nothing new leaves the row as it was`() {
        val first = cards(result(1), result(2))
        assertSame(first, appendPage(first, listOf(result(2), result(9, type = "person")), images, open, Permission.REQUEST))
    }

    @Test
    fun `the next page is asked for near the end - or while the row does not fill the screen`() {
        // 20 cards, focus well back: nothing yet
        assertFalse(wantsNextPage(count = 20, focused = 5, page = 1, totalPages = 9, busy = false))
        // focus within a screen's worth of the end
        assertTrue(wantsNextPage(count = 20, focused = 20 - LOOKAHEAD, page = 1, totalPages = 9, busy = false))
        assertTrue(wantsNextPage(count = 20, focused = 19, page = 1, totalPages = 9, busy = false))
        // a short row (filters took most of a page) fills itself before anyone gets there
        assertTrue(wantsNextPage(count = FILLS_SCREEN - 1, focused = -1, page = 1, totalPages = 9, busy = false))
        assertFalse(wantsNextPage(count = FILLS_SCREEN, focused = -1, page = 1, totalPages = 9, busy = false))
        // never two at once, never past the last page
        assertFalse(wantsNextPage(count = 20, focused = 19, page = 1, totalPages = 9, busy = true))
        assertFalse(wantsNextPage(count = 20, focused = 19, page = 9, totalPages = 9, busy = false))
        assertFalse(wantsNextPage(count = 3, focused = -1, page = 1, totalPages = 1, busy = false))
    }

    // ---- "See More" (Tom, 2026-11-12) ----------------------------------------------------------------

    @Test
    fun `every row Seerr links ends with See More - opening the Movies or Series page with its query - or a grid`() {
        val today = "2026-11-13"
        assertEquals(SeeMore.Browse(MediaType.MOVIE), seeMoreTarget(DiscoverRow.POPULAR_MOVIES, today))
        assertEquals(SeeMore.Browse(MediaType.TV), seeMoreTarget(DiscoverRow.POPULAR_SERIES, today))
        // Seerr's Upcoming links: the page from today (primaryReleaseDateGte, firstAirDateGte)
        assertEquals(SeeMore.Browse(MediaType.MOVIE, DiscoverQuery(releasedFrom = today)), seeMoreTarget(DiscoverRow.UPCOMING_MOVIES, today))
        assertEquals(SeeMore.Browse(MediaType.TV, DiscoverQuery(releasedFrom = today)), seeMoreTarget(DiscoverRow.UPCOMING_SERIES, today))
        // a server's custom slider is linked too (112 said otherwise): keyword, genre, streaming to the page with its query
        val keyword = DiscoverQuery(keywords = "9715")
        assertEquals(SeeMore.Browse(MediaType.TV, keyword), seeMoreTarget(DiscoverRow("CUSTOM_3", "Superheroes", RowSource.Query(MediaType.TV, keyword), 7), today))
        val streaming = DiscoverQuery(watchRegion = "GB", watchProviders = "8")
        assertEquals(SeeMore.Browse(MediaType.MOVIE, streaming), seeMoreTarget(DiscoverRow("CUSTOM_4", "On Netflix", RowSource.Query(MediaType.MOVIE, streaming), 8), today))
        // Trending, a search, a studio and a network: a grid of the row's own titles
        for (source in listOf(RowSource.Trending, RowSource.Search("popular"), RowSource.Studio(2), RowSource.Network(49))) {
            assertEquals(SeeMore.Grid, seeMoreTarget(DiscoverRow("R", "Row", source, 9), today), source.toString())
        }
        // Recently Added has no link, as on Seerr
        assertNull(seeMoreTarget(DiscoverRow.RECENTLY_ADDED, today))
        assertFalse(DiscoverRow.RECENTLY_ADDED.linked)
        assertTrue(DiscoverRow.TRENDING.linked)
    }

    @Test
    fun `a page opened from a row keeps the row's query - its own filters narrowing it`() {
        val base = DiscoverQuery(genre = "16", keywords = "9715", releasedFrom = "2026-11-13", watchRegion = "GB", watchProviders = "8")
        val plain = browseQuery(MediaType.MOVIE, "popularity.desc", BrowseFilters(), "US", base)
        assertEquals("16", plain.genre)
        assertEquals("9715", plain.keywords)
        assertEquals("2026-11-13", plain.releasedFrom)
        assertEquals("8", plain.watchProviders)
        assertEquals("GB", plain.watchRegion)
        val narrowed = browseQuery(MediaType.MOVIE, "popularity.desc", BrowseFilters(genres = setOf(28), years = Years.TWENTY_TENS, providers = setOf(337)), "US", base)
        assertEquals("16,28", narrowed.genre)
        assertEquals("2026-11-13", narrowed.releasedFrom, "the later start")
        assertEquals("2019-12-31", narrowed.releasedTo, "the earlier end (an empty page here: the row's query and the filters together)")
        assertEquals("337", narrowed.watchProviders, "the page's services win")
        assertEquals("US", narrowed.watchRegion)
        // without a base, as before
        assertEquals(DiscoverQuery(sortBy = "popularity.desc"), browseQuery(MediaType.MOVIE, "popularity.desc", BrowseFilters(), "US"))
    }

    @Test
    fun `a row with See More shows its first 20 cards and then the card - as Seerr's slider`() {
        val popular = DiscoverRow.POPULAR_MOVIES
        assertEquals(12, shownCards(popular, 12))
        assertEquals(SEE_MORE_AFTER, shownCards(popular, 40))
        assertFalse(showsSeeMore(popular, SEE_MORE_AFTER - 1), "before it has its 20")
        assertTrue(showsSeeMore(popular, SEE_MORE_AFTER))
        assertTrue(showsSeeMore(DiscoverRow.POPULAR_SERIES, 40))
        // Trending too now (polish 2); Recently Added shows all it has, and no card
        assertEquals(SEE_MORE_AFTER, shownCards(DiscoverRow.TRENDING, 40))
        assertTrue(showsSeeMore(DiscoverRow.TRENDING, 40))
        assertEquals(40, shownCards(DiscoverRow.RECENTLY_ADDED, 40))
        assertFalse(showsSeeMore(DiscoverRow.RECENTLY_ADDED, 40))
    }

    @Test
    fun `a row with See More reads no further than its 20 and the card's four posters`() {
        val popular = DiscoverRow.POPULAR_MOVIES
        // the 20 shown, focus near their end: the next page, for the card's posters
        assertTrue(rowWantsNextPage(popular, count = 20, focused = 15, page = 1, totalPages = 9, busy = false))
        // the 20 and the four: nothing more, wherever focus is
        assertFalse(rowWantsNextPage(popular, count = SEE_MORE_AFTER + SEE_MORE_POSTERS, focused = 20, page = 2, totalPages = 9, busy = false))
        assertFalse(rowWantsNextPage(popular, count = 40, focused = 19, page = 2, totalPages = 9, busy = false))
        // filters left it short: it fills itself as any row does
        assertTrue(rowWantsNextPage(popular, count = 3, focused = -1, page = 1, totalPages = 9, busy = false))
        // a row with no link carries on
        assertTrue(rowWantsNextPage(DiscoverRow.RECENTLY_ADDED, count = 40, focused = 39, page = 2, totalPages = 9, busy = false))
    }

    // ---- the focused card's overview (Tom, 2026-11-12) ----------------------------------------------

    @Test
    fun `the overview has five lines under a one-line title and one fewer for each further title line`() {
        assertEquals(5, overviewLines(1))
        assertEquals(4, overviewLines(2))
        assertEquals(3, overviewLines(3))
        // the block keeps one height: title and overview lines together never pass six
        for (title in 1..3) assertEquals(6, title + overviewLines(title))
        // a title is never fewer than one line, nor more than three
        assertEquals(5, overviewLines(0))
        assertEquals(3, overviewLines(5))
    }

    // ---- live status ---------------------------------------------------------------------------------

    @Test
    fun `fresh badges replace old ones in place - without moving or dropping a card`() {
        val row = cards(result(1), result(2, status = MediaStatus.PENDING), result(3))
        val fresh = cards(result(3, status = MediaStatus.AVAILABLE), result(2, status = MediaStatus.PROCESSING), result(9))
        val updated = withFreshBadges(row, fresh)
        assertEquals(listOf("movie:1", "movie:2", "movie:3"), updated.map { it.key })
        assertNull(updated[0].badge)
        assertEquals(StatusWord.REQUESTED, updated[1].badge?.word)
        assertEquals(StatusWord.AVAILABLE, updated[2].badge?.word)
    }

    @Test
    fun `a title no longer requested loses its badge - and no change keeps the same list`() {
        val row = cards(result(1, status = MediaStatus.PENDING))
        assertNull(withFreshBadges(row, cards(result(1)))[0].badge)
        assertSame(row, withFreshBadges(row, cards(result(1, status = MediaStatus.PENDING))))
        assertSame(row, withFreshBadges(row, emptyList()))
    }
}
