package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.TvDetails
import com.tomdebres.hither.core.model.User
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

/** "Play on Plex" (DECISIONS 68): the link Plex for Android TV opens, and when Seerr offers it. */
class PlexTest {
    private val machine = "f00dfacecafe0000000000000000000000000000"
    private fun seerrLink(key: String, server: String = machine) = "plex://preplay/?metadataKey=%2Flibrary%2Fmetadata%2F$key&server=$server"

    private val plex = PublicSettings(mediaServerType = MEDIA_SERVER_PLEX, movie4kEnabled = true, series4kEnabled = false)
    private val recorded = 67744 // REQUEST, AUTO_APPROVE, REQUEST_4K_MOVIE, AUTO_APPROVE_4K_MOVIE

    private fun inPlex(regular: String? = "8531", fourK: String? = null) = MediaInfo(
        status = 5,
        status4k = if (fourK != null) 5 else 1,
        iOSPlexUrl = regular?.let { seerrLink(it) },
        iOSPlexUrl4k = fourK?.let { seerrLink(it) },
    )

    @Test
    fun `Seerr's iOS link becomes the link Plex for Android TV opens - a series' with its tv segment`() {
        assertEquals("plex://libraries/$machine/unmatched/%2Flibrary%2Fmetadata%2F8531", plexAppLink(seerrLink("8531"), MediaType.MOVIE))
        assertEquals("plex://libraries/$machine/unmatched/tv/%2Flibrary%2Fmetadata%2F8493", plexAppLink(seerrLink("8493"), MediaType.TV))
        // the parameters in either order, and an unencoded key, as a server might write them
        assertEquals(
            "plex://libraries/abc123/unmatched/%2Flibrary%2Fmetadata%2F7",
            plexAppLink("plex://preplay/?server=abc123&metadataKey=/library/metadata/7", MediaType.MOVIE),
        )
    }

    @Test
    fun `anything not in Seerr's form gives no link - so nothing odd reaches the TV`() {
        for (bad in listOf(
            null,
            "",
            "https://media-server.example.invalid/item/1", // the scrubbed fixtures' placeholder
            "https://app.plex.tv/desktop#!/server/$machine/details?key=%2Flibrary%2Fmetadata%2F1", // the web link
            "plex://preplay/?metadataKey=%2Flibrary%2Fmetadata%2F1", // no server
            "plex://preplay/?server=$machine", // no key
            seerrLink("12a"), // a key that is not a number
            seerrLink("1%2F..%2F2"), // a key with more path in it
            seerrLink("1", server = "a%2Fb"), // a machine id with a slash
            seerrLink("1", server = "a b"),
            "plex://server://$machine/com.plexapp.plugins.library/library/metadata/1", // Plex's older form
        )) {
            assertNull(plexAppLink(bad, MediaType.MOVIE), "$bad")
        }
    }

    @Test
    fun `the regular copy plays for a user who may request the type - the 4K copy where 4K is on and allowed`() {
        val both = inPlex(regular = "1", fourK = "2")
        assertEquals(listOf("Play on Plex", "Play 4K on Plex"), playActions(MediaType.MOVIE, both, plex, recorded).map { it.text })
        assertEquals(listOf(false, true), playActions(MediaType.MOVIE, both, plex, recorded).map { it.is4k })
        // 4K off for films on the server, or no 4K right: the regular copy only
        assertEquals(listOf("Play on Plex"), playActions(MediaType.MOVIE, both, plex.copy(movie4kEnabled = false), recorded).map { it.text })
        assertEquals(listOf("Play on Plex"), playActions(MediaType.MOVIE, both, plex, Permission.REQUEST).map { it.text })
        // series: 4K off for series here, so the recorded account plays the regular copy
        assertEquals(listOf("Play on Plex"), playActions(MediaType.TV, both, plex, recorded).map { it.text })
        assertEquals(listOf("Play on Plex", "Play 4K on Plex"), playActions(MediaType.TV, both, plex.copy(series4kEnabled = true), Permission.REQUEST_TV or Permission.REQUEST_4K_TV).map { it.text })
        // per-type rights count as Seerr counts them; a film right is not a series right
        assertEquals(1, playActions(MediaType.MOVIE, both, plex, Permission.REQUEST_MOVIE).size)
        assertEquals(0, playActions(MediaType.TV, both, plex, Permission.REQUEST_MOVIE).size)
        // an account that may not request has no play button either (Seerr's rule)
        assertEquals(0, playActions(MediaType.MOVIE, both, plex, Permission.REQUEST_VIEW).size)
        assertEquals(2, playActions(MediaType.MOVIE, both, plex, Permission.ADMIN).size)
    }

    @Test
    fun `no Plex link - no Plex server or no record - nothing to play`() {
        assertEquals(0, playActions(MediaType.MOVIE, inPlex(regular = null), plex, recorded).size)
        assertEquals(0, playActions(MediaType.MOVIE, inPlex(), plex.copy(mediaServerType = 2), recorded).size)
        assertEquals(0, playActions(MediaType.MOVIE, null, plex, recorded).size)
        assertEquals(0, playActions(MediaType.MOVIE, inPlex().copy(iOSPlexUrl = "https://media-server.example.invalid/item/1"), plex, recorded).size)
    }

    @Test
    fun `the media page carries its play actions - and a new record works them out again`() {
        val user = User(id = 8, permissions = recorded)
        val page = moviePage(MovieDetails(id = 1, title = "Film", mediaInfo = inPlex(regular = "5")), ImageSource("http://seerr.test", cacheImages = true, tmdbImages = ImageSource.TMDB_IMAGES), plex, user)
        assertEquals(listOf("plex://libraries/$machine/unmatched/%2Flibrary%2Fmetadata%2F5"), page.plays.map { it.link })
        assertEquals(emptyList<PlayAction>(), page.withMedia(MediaInfo(status = 3)).plays)
        val series = tvPage(TvDetails(id = 2, name = "Series", mediaInfo = inPlex(regular = "9")), ImageSource("http://seerr.test", cacheImages = true, tmdbImages = ImageSource.TMDB_IMAGES), plex, user)
        assertEquals(listOf("plex://libraries/$machine/unmatched/tv/%2Flibrary%2Fmetadata%2F9"), series.plays.map { it.link })
    }
}
