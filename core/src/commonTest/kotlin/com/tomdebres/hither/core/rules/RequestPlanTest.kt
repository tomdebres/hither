package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.SeerrClient
import com.tomdebres.hither.core.model.MediaSeason
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.RequestBody
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class RequestPlanTest {
    private val may = RequestOptions(RequestAction.REQUEST, RequestAction.NONE, false, false, true)
    private val may4k = may.copy(fourK = RequestAction.REQUEST)

    // ---- films -----------------------------------------------------------------------------

    @Test
    fun `a film request names the film and nothing else`() {
        assertEquals(RequestBody("movie", 603, is4k = false, seasons = null), movieRequestBody(603, is4k = false, may))
        assertEquals(RequestBody("movie", 603, is4k = true, seasons = null), movieRequestBody(603, is4k = true, may4k))
    }

    @Test
    fun `4K is not sent where there is no 4K button`() {
        assertNull(movieRequestBody(603, is4k = true, may))
        assertNull(movieRequestBody(603, is4k = false, RequestOptions.NONE))
    }

    @Test
    fun `on the wire a film request has no seasons key and always says is4k`() {
        val body = movieRequestBody(603, is4k = false, may)!!
        assertEquals(
            """{"mediaType":"movie","mediaId":603,"is4k":false}""",
            SeerrClient.json.encodeToString(RequestBody.serializer(), body),
        )
    }

    // ---- series, picking seasons -------------------------------------------------------------

    @Test
    fun `the seasons sent are the seasons picked - in ascending order`() {
        val body = tvRequestBody(1399, false, selected = setOf(3, 1), seasons(4), media = null, settings(partial = true), may)
        assertEquals(RequestBody("tv", 1399, is4k = false, seasons = listOf(1, 3)), body)
        assertEquals(
            """{"mediaType":"tv","mediaId":1399,"is4k":false,"seasons":[1,3]}""",
            SeerrClient.json.encodeToString(RequestBody.serializer(), body!!),
        )
    }

    @Test
    fun `picking nothing sends nothing`() {
        assertNull(tvRequestBody(1399, false, emptySet(), seasons(4), null, settings(partial = true), may))
    }

    @Test
    fun `a pick that is not on offer is dropped - not sent`() {
        val media = media(
            status = MediaStatus.PARTIALLY_AVAILABLE,
            seasons = listOf(MediaSeason(seasonNumber = 1, status = MediaStatus.AVAILABLE)),
            requests = listOf(request(seasons = listOf(2), by = SOMEONE_ELSE)),
        )
        // 1 is in the library, 2 is requested, 0 is specials (off), 5 has no episodes, 42 does not exist
        val body = tvRequestBody(1399, false, setOf(0, 1, 2, 3, 5, 42), seasons(4), media, settings(partial = true), may)
        assertEquals(listOf(3), body?.seasons)
        // and when every pick falls away, nothing is sent
        assertNull(tvRequestBody(1399, false, setOf(1, 2), seasons(4), media, settings(partial = true), may))
    }

    @Test
    fun `specials can be picked where the server has them on`() {
        val body = tvRequestBody(1399, false, setOf(0, 2), seasons(4), null, settings(partial = true, specials = true), may)
        assertEquals(listOf(0, 2), body?.seasons)
    }

    // ---- series, whole-series servers ----------------------------------------------------------

    @Test
    fun `with partial requests off - the request is for every season still to be had`() {
        val media = media(
            status = MediaStatus.PARTIALLY_AVAILABLE,
            seasons = listOf(MediaSeason(seasonNumber = 2, status = MediaStatus.AVAILABLE)),
        )
        val body = tvRequestBody(1399, false, selected = setOf(4), seasons(4), media, settings(partial = false), may)
        assertEquals(listOf(1, 3, 4), body?.seasons)
        assertNull(
            tvRequestBody(
                1399, false, emptySet(), seasons(1),
                media(seasons = listOf(MediaSeason(seasonNumber = 1, status = MediaStatus.AVAILABLE))),
                settings(partial = false), may,
            ),
        )
    }

    // ---- series, 4K --------------------------------------------------------------------------

    @Test
    fun `a 4K series request counts the 4K copy's seasons and needs the 4K button`() {
        val media = media(
            seasons = listOf(MediaSeason(seasonNumber = 1, status = MediaStatus.AVAILABLE, status4k = MediaStatus.UNKNOWN)),
        )
        assertNull(tvRequestBody(1399, is4k = true, setOf(1), seasons(2), media, settings(), may))
        val body = tvRequestBody(1399, is4k = true, setOf(1), seasons(2), media, settings(), may4k)
        assertEquals(RequestBody("tv", 1399, is4k = true, seasons = listOf(1)), body)
    }

    @Test
    fun `the options from the rules feed the plan end to end`() {
        val settings = settings(movie4k = true, series4k = false)
        val me = user(Permission.REQUEST or Permission.REQUEST_4K)
        val options = requestOptions(MediaType.TV, settings, me, media = null, seasons(2))
        // series 4K is off on the server, so even with the permission nothing 4K leaves
        assertNull(tvRequestBody(1399, is4k = true, setOf(1), seasons(2), null, settings, options))
        assertEquals(listOf(1), tvRequestBody(1399, is4k = false, setOf(1), seasons(2), null, settings, options)?.seasons)
    }
}
