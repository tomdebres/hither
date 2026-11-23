package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.model.MediaSeason
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.RequestStatus
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.Permission
import kotlin.test.Test

class SeasonsTest {
    @Test
    fun `seasons without episodes are not on offer - and specials only where enabled`() {
        assertEquals(listOf(1, 2, 3), requestableSeasons(seasons(3), specialsEnabled = false))
        assertEquals(listOf(0, 1, 2, 3), requestableSeasons(seasons(3), specialsEnabled = true))
    }

    @Test
    fun `taken seasons are those in a live request and those the library has or is fetching`() {
        val m = media(
            seasons = listOf(
                MediaSeason(seasonNumber = 1, status = MediaStatus.AVAILABLE),
                MediaSeason(seasonNumber = 2, status = MediaStatus.PROCESSING),
                MediaSeason(seasonNumber = 3, status = MediaStatus.UNKNOWN),
                MediaSeason(seasonNumber = 7, status = MediaStatus.DELETED),
            ),
            requests = listOf(
                request(status = RequestStatus.PENDING, seasons = listOf(4)),
                request(status = RequestStatus.DECLINED, seasons = listOf(5)),
                request(status = RequestStatus.COMPLETED, seasons = listOf(6)),
                request(status = RequestStatus.FAILED, seasons = listOf(8)),
            ),
        )
        assertEquals(setOf(1, 2, 4, 8), takenSeasons(m, is4k = false))
    }

    @Test
    fun `the regular and the 4K copy are counted apart`() {
        val m = media(
            seasons = listOf(MediaSeason(seasonNumber = 1, status = MediaStatus.AVAILABLE, status4k = MediaStatus.UNKNOWN)),
            requests = listOf(request(seasons = listOf(2), is4k = true)),
        )
        assertEquals(setOf(1), takenSeasons(m, is4k = false))
        assertEquals(setOf(2), takenSeasons(m, is4k = true))
        assertEquals(emptySet<Int>(), takenSeasons(null, is4k = false))
    }

    @Test
    fun `a season's state is the library's word first - then its request's`() {
        val m = media(
            seasons = listOf(
                MediaSeason(seasonNumber = 1, status = MediaStatus.AVAILABLE),
                MediaSeason(seasonNumber = 2, status = MediaStatus.PARTIALLY_AVAILABLE),
                MediaSeason(seasonNumber = 3, status = MediaStatus.PROCESSING),
            ),
            requests = listOf(
                request(status = RequestStatus.PENDING, seasons = listOf(4)),
                request(status = RequestStatus.APPROVED, seasons = listOf(5)),
                request(status = RequestStatus.DECLINED, seasons = listOf(6)),
                request(status = RequestStatus.COMPLETED, seasons = listOf(1)),
            ),
        )
        assertEquals(SeasonState.AVAILABLE, seasonState(1, m, is4k = false))
        assertEquals(SeasonState.PARTIALLY_AVAILABLE, seasonState(2, m, is4k = false))
        assertEquals(SeasonState.REQUESTED, seasonState(3, m, is4k = false))
        assertEquals(SeasonState.PENDING, seasonState(4, m, is4k = false))
        assertEquals(SeasonState.REQUESTED, seasonState(5, m, is4k = false))
        assertEquals(SeasonState.NOT_REQUESTED, seasonState(6, m, is4k = false))
        assertEquals(SeasonState.NOT_REQUESTED, seasonState(9, m, is4k = false))
        assertEquals(SeasonState.NOT_REQUESTED, seasonState(1, null, is4k = false))
        // none of it speaks for the 4K copy
        assertEquals(SeasonState.NOT_REQUESTED, seasonState(1, m, is4k = true))
    }

    @Test
    fun `every taken season has a state other than not requested`() {
        val m = media(
            seasons = listOf(MediaSeason(seasonNumber = 1, status = MediaStatus.PROCESSING)),
            requests = listOf(
                request(status = RequestStatus.FAILED, seasons = listOf(2)),
                request(status = RequestStatus.PENDING, seasons = listOf(3)),
            ),
        )
        for (season in takenSeasons(m, is4k = false)) {
            assertTrue(seasonState(season, m, is4k = false) != SeasonState.NOT_REQUESTED, "season $season")
        }
    }

    @Test
    fun `a series' page lists its seasons newest first - specials where shown - none without episodes`() {
        val settings = PublicSettings()
        // seasons(3): 0 to 3 with eight episodes each, then 4 with none
        assertEquals(listOf(3, 2, 1), seasonLines(seasons(3), null, settings, Permission.REQUEST).map { it.number })
        assertEquals(listOf(3, 2, 1, 0), seasonLines(seasons(3), null, settings.copy(enableSpecialEpisodes = true), Permission.REQUEST).map { it.number })
        assertEquals(8, seasonLines(seasons(3), null, settings, Permission.REQUEST).first().episodes)
    }

    @Test
    fun `each season's badge is its state - none where nobody asked - and the 4K copy's only where 4K is on and allowed`() {
        val m = media(
            seasons = listOf(MediaSeason(seasonNumber = 1, status = MediaStatus.AVAILABLE, status4k = MediaStatus.PROCESSING)),
            requests = listOf(request(status = RequestStatus.PENDING, seasons = listOf(2))),
        )
        val words = { settings: PublicSettings, rights: Int ->
            seasonLines(seasons(3), m, settings, rights).associate { it.number to it.badges.map { b -> b.text } }
        }
        assertEquals(mapOf(3 to emptyList(), 2 to listOf("Pending"), 1 to listOf("Available")), words(PublicSettings(), Permission.REQUEST))
        val fourK = PublicSettings(series4kEnabled = true)
        assertEquals(listOf("Available"), words(fourK, Permission.REQUEST)[1], "4K is on but not allowed")
        assertEquals(listOf("Available", "4K Requested"), words(fourK, Permission.REQUEST or Permission.REQUEST_4K_TV)[1])
        assertEquals(listOf("Available"), words(PublicSettings(), Permission.REQUEST or Permission.REQUEST_4K_TV)[1], "4K off for series")
    }

}
