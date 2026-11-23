package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Fixtures
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.Page
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class ImagesTest {
    private val base = "http://seerr.test:5055"

    @Test
    fun `a poster comes through the server's image proxy at Seerr's card size`() {
        assertEquals(
            "http://seerr.test:5055/imageproxy/tmdb/t/p/w300_and_h450_face/bGB6bEbG4qL22C1SFupkdAxYC9i.jpg",
            imageUrl(base, "/bGB6bEbG4qL22C1SFupkdAxYC9i.jpg", TmdbImage.POSTER),
        )
    }

    @Test
    fun `a backdrop uses the backdrop size`() {
        assertEquals(
            "http://seerr.test:5055/imageproxy/tmdb/t/p/w1920_and_h800_multi_faces/abc-D_1.png",
            imageUrl(base, "/abc-D_1.png", TmdbImage.BACKDROP),
        )
    }

    @Test
    fun `a trailing slash on the base does not double up`() {
        assertEquals(
            "https://seerr.test/imageproxy/tmdb/t/p/w300_and_h450_face/a.jpg",
            imageUrl("https://seerr.test/", "/a.jpg", TmdbImage.POSTER),
        )
    }

    @Test
    fun `no path - no image`() {
        assertNull(imageUrl(base, null, TmdbImage.POSTER))
        assertNull(imageUrl(base, "", TmdbImage.POSTER))
        assertNull(imageUrl(base, "/", TmdbImage.POSTER))
    }

    @Test
    fun `a path that could leave the proxy's folder or the server is refused`() {
        listOf(
            "//evil.example/a.jpg",
            "https://image.tmdb.org/t/p/original/a.jpg",
            "/../../api/v1/auth/me.jpg",
            "/a/../../b.jpg",
            "/a.jpg?x=1",
            "/a.jpg#b",
            "/a%2F..%2Fb.jpg",
            "a.jpg",
            "/a.jpg/",
            "/a b.jpg",
            "/a.html",
            "/.jpg",
            "/a\n.jpg",
            "/a.jpg\n",
        ).forEach { path ->
            assertNull(imageUrl(base, path, TmdbImage.POSTER), "refused: ${path.replace("\n", "\\n")}")
        }
    }

    @Test
    fun `every recorded poster and backdrop path makes a URL on the named server and nowhere else`() {
        val rows = listOf("discover-trending", "discover-movies", "discover-tv", "discover-movies-upcoming", "search")
        val results = rows.flatMap { Fixtures.read<Page<MediaResult>>(it).results }
        val paths = results.flatMap { listOfNotNull(it.posterPath, it.backdropPath) }
        assertTrue(paths.size > 40, "the fixtures carry image paths")
        for (path in paths) {
            for (image in TmdbImage.entries) {
                val url = imageUrl(base, path, image)
                assertEquals("$base/imageproxy/tmdb/t/p/${image.size}$path", url)
            }
        }
    }
}
