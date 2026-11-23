package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.MediaSeason
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.RequestStatus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class RequestEligibilityTest {
    // ---- the regular copy ----------------------------------------------------------------

    @Test
    fun `REQUEST alone can request a film nobody has asked for`() {
        val options = requestOptions(MediaType.MOVIE, settings(), user(Permission.REQUEST), media = null)
        assertEquals(RequestAction.REQUEST, options.standard)
        assertEquals(RequestAction.NONE, options.fourK)
        assertFalse(options.autoApproved)
    }

    @Test
    fun `no request permission - no button`() {
        for (held in listOf(null, 0, Permission.REQUEST_VIEW, Permission.REQUEST_4K)) {
            val options = requestOptions(MediaType.MOVIE, settings(), user(held), media = null)
            assertEquals(RequestAction.NONE, options.standard, "held=$held")
        }
    }

    @Test
    fun `the per-type permission covers its own type only`() {
        val filmsOnly = user(Permission.REQUEST_MOVIE)
        assertEquals(RequestAction.REQUEST, requestOptions(MediaType.MOVIE, settings(), filmsOnly, null).standard)
        assertEquals(RequestAction.NONE, requestOptions(MediaType.TV, settings(), filmsOnly, null, seasons()).standard)
    }

    @Test
    fun `a film that is pending - on its way or in the library cannot be asked for again`() {
        for (status in listOf(MediaStatus.PENDING, MediaStatus.PROCESSING, MediaStatus.AVAILABLE, MediaStatus.BLOCKLISTED)) {
            val options = requestOptions(MediaType.MOVIE, settings(), user(), media(status = status))
            assertEquals(RequestAction.NONE, options.standard, "status=$status")
        }
    }

    @Test
    fun `a deleted film can be asked for again unless a request is already pending`() {
        val deleted = media(status = MediaStatus.DELETED)
        assertEquals(RequestAction.REQUEST, requestOptions(MediaType.MOVIE, settings(), user(), deleted).standard)
        val deletedAndPending = media(status = MediaStatus.DELETED, requests = listOf(request(by = SOMEONE_ELSE)))
        assertEquals(RequestAction.NONE, requestOptions(MediaType.MOVIE, settings(), user(), deletedAndPending).standard)
    }

    // ---- 4K ------------------------------------------------------------------------------

    @Test
    fun `4K needs both the server switch for that type and the permission`() {
        val may4kFilms = user(Permission.REQUEST or Permission.REQUEST_4K_MOVIE)
        assertEquals(RequestAction.REQUEST, requestOptions(MediaType.MOVIE, settings(movie4k = true), may4kFilms, null).fourK)
        // the switch is off
        assertEquals(RequestAction.NONE, requestOptions(MediaType.MOVIE, settings(movie4k = false), may4kFilms, null).fourK)
        // only the series switch is on
        assertEquals(RequestAction.NONE, requestOptions(MediaType.MOVIE, settings(series4k = true), may4kFilms, null).fourK)
        // the switch is on and the permission is missing
        assertEquals(RequestAction.NONE, requestOptions(MediaType.MOVIE, settings(movie4k = true), user(Permission.REQUEST), null).fourK)
        // the film permission does not reach series
        assertEquals(RequestAction.NONE, requestOptions(MediaType.TV, settings(series4k = true), may4kFilms, null, seasons()).fourK)
    }

    @Test
    fun `an available film can still be asked for in 4K - and not the other way round`() {
        val held = user(Permission.REQUEST or Permission.REQUEST_4K)
        val regularOnly = requestOptions(MediaType.MOVIE, settings(movie4k = true), held, media(status = MediaStatus.AVAILABLE))
        assertEquals(RequestAction.NONE, regularOnly.standard)
        assertEquals(RequestAction.REQUEST, regularOnly.fourK)
        val fourKOnly = requestOptions(MediaType.MOVIE, settings(movie4k = true), held, media(status4k = MediaStatus.AVAILABLE))
        assertEquals(RequestAction.REQUEST, fourKOnly.standard)
        assertEquals(RequestAction.NONE, fourKOnly.fourK)
    }

    // ---- automatic approval --------------------------------------------------------------

    @Test
    fun `automatic approval follows its own permission - per copy and per type`() {
        val s = settings(movie4k = true, series4k = true)
        val plain = requestOptions(MediaType.MOVIE, s, user(Permission.REQUEST or Permission.REQUEST_4K), null)
        assertFalse(plain.autoApproved)
        assertFalse(plain.autoApproved4k)

        val auto = requestOptions(
            MediaType.MOVIE, s,
            user(Permission.REQUEST or Permission.AUTO_APPROVE or Permission.REQUEST_4K_MOVIE or Permission.AUTO_APPROVE_4K_MOVIE),
            null,
        )
        assertTrue(auto.autoApproved)
        assertTrue(auto.autoApproved4k)

        val filmsOnly = user(Permission.REQUEST or Permission.AUTO_APPROVE_MOVIE)
        assertTrue(requestOptions(MediaType.MOVIE, s, filmsOnly, null).autoApproved)
        assertFalse(requestOptions(MediaType.TV, s, filmsOnly, null, seasons()).autoApproved)
    }

    @Test
    fun `nothing is auto-approved where nothing can be requested`() {
        val options = requestOptions(
            MediaType.MOVIE, settings(), user(Permission.REQUEST or Permission.AUTO_APPROVE), media(status = MediaStatus.AVAILABLE),
        )
        assertEquals(RequestAction.NONE, options.standard)
        assertFalse(options.autoApproved)
    }

    // ---- series ----------------------------------------------------------------------------

    @Test
    fun `the season picker is offered only where the server allows partial requests`() {
        assertTrue(requestOptions(MediaType.TV, settings(partial = true), user(), null, seasons()).seasonPicker)
        assertFalse(requestOptions(MediaType.TV, settings(partial = false), user(), null, seasons()).seasonPicker)
        assertFalse(requestOptions(MediaType.MOVIE, settings(partial = true), user(), null).seasonPicker)
    }

    @Test
    fun `a partly covered series gets Request More until every season is taken`() {
        val oneOfThree = media(
            status = MediaStatus.PARTIALLY_AVAILABLE,
            seasons = listOf(MediaSeason(seasonNumber = 1, status = MediaStatus.AVAILABLE)),
        )
        assertEquals(RequestAction.REQUEST_MORE, requestOptions(MediaType.TV, settings(), user(), oneOfThree, seasons(3)).standard)

        val allThree = media(
            status = MediaStatus.AVAILABLE,
            seasons = (1..3).map { MediaSeason(seasonNumber = it, status = MediaStatus.AVAILABLE) },
        )
        assertEquals(RequestAction.NONE, requestOptions(MediaType.TV, settings(), user(), allThree, seasons(3)).standard)
    }

    @Test
    fun `specials count towards complete only where the server has them on`() {
        val allButSpecials = media(
            status = MediaStatus.AVAILABLE,
            seasons = (1..3).map { MediaSeason(seasonNumber = it, status = MediaStatus.AVAILABLE) },
        )
        assertEquals(
            RequestAction.REQUEST_MORE,
            requestOptions(MediaType.TV, settings(specials = true), user(), allButSpecials, seasons(3)).standard,
        )
    }

    @Test
    fun `my own pending request blocks Request More - someone else's does not`() {
        fun partly(by: Int) = media(
            status = MediaStatus.PENDING,
            requests = listOf(request(status = RequestStatus.PENDING, seasons = listOf(1), by = by)),
        )
        assertEquals(RequestAction.NONE, requestOptions(MediaType.TV, settings(), user(), partly(ME), seasons(3)).standard)
        assertEquals(RequestAction.REQUEST_MORE, requestOptions(MediaType.TV, settings(), user(), partly(SOMEONE_ELSE), seasons(3)).standard)
    }

    @Test
    fun `a blocklisted series gets nothing`() {
        val options = requestOptions(MediaType.TV, settings(), user(), media(status = MediaStatus.BLOCKLISTED), seasons(3))
        assertEquals(RequestAction.NONE, options.standard)
    }
}
