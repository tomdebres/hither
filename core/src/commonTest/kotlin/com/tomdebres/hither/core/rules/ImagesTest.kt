package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Fixtures
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.Page
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class ImagesTest {
    private val base = "http://seerr.test:5055"

    /** A server that keeps its own copies (`cacheImages` on, as Tom's): its proxy. */
    private val caching = ImageSource(base, cacheImages = true, tmdbImages = ImageSource.TMDB_IMAGES)

    /** A server that keeps none (`cacheImages` off, Seerr's default): TMDB's image server (DECISIONS 382). */
    private val notCaching = ImageSource(base, cacheImages = false, tmdbImages = ImageSource.TMDB_IMAGES)

    // ---- the server's proxy, while it keeps copies ----------------------------------------------------

    @Test
    fun `a poster comes through the server's image proxy at Seerr's card size while the server keeps copies`() {
        assertEquals(
            "http://seerr.test:5055/imageproxy/tmdb/t/p/w300_and_h450_face/bGB6bEbG4qL22C1SFupkdAxYC9i.jpg",
            imageUrl(caching, "/bGB6bEbG4qL22C1SFupkdAxYC9i.jpg", TmdbImage.POSTER),
        )
        assertTrue(caching.fromServer)
    }

    @Test
    fun `a backdrop uses the backdrop size`() {
        assertEquals(
            "http://seerr.test:5055/imageproxy/tmdb/t/p/w1920_and_h800_multi_faces/abc-D_1.png",
            imageUrl(caching, "/abc-D_1.png", TmdbImage.BACKDROP),
        )
    }

    @Test
    fun `a trailing slash on the base does not double up`() {
        assertEquals(
            "https://seerr.test/imageproxy/tmdb/t/p/w300_and_h450_face/a.jpg",
            imageUrl(ImageSource("https://seerr.test/", true, ImageSource.TMDB_IMAGES), "/a.jpg", TmdbImage.POSTER),
        )
    }

    // ---- TMDB's image server, while it keeps none -------------------------------------------------------

    @Test
    fun `a server that keeps no copies sends every picture to TMDB's image server as Seerr's web page does`() {
        assertEquals(
            "https://image.tmdb.org/t/p/w300_and_h450_face/bGB6bEbG4qL22C1SFupkdAxYC9i.jpg",
            imageUrl(notCaching, "/bGB6bEbG4qL22C1SFupkdAxYC9i.jpg", TmdbImage.POSTER),
        )
        assertFalse(notCaching.fromServer)
        for (image in TmdbImage.entries) {
            assertEquals("https://image.tmdb.org/t/p/${image.size}/a.jpg", imageUrl(notCaching, "/a.jpg", image), image.name)
        }
    }

    @Test
    fun `a debug build's stand-in for TMDB takes TMDB's paths with or without a trailing slash`() {
        val standIn = ImageSource(base, cacheImages = false, tmdbImages = "http://10.0.2.2:5056/tmdb/")
        assertEquals("http://10.0.2.2:5056/tmdb/t/p/w780/b.jpg", imageUrl(standIn, "/b.jpg", TmdbImage.WIDE))
    }

    @Test
    fun `before the server has said its pictures are its own proxy's and nothing goes to TMDB`() {
        val unknown = ImageSource.unknown(base)
        assertTrue(unknown.fromServer)
        assertEquals("$base/imageproxy/tmdb/t/p/w300_and_h450_face/a.jpg", imageUrl(unknown, "/a.jpg", TmdbImage.POSTER))
        assertEquals(caching, unknown)
    }

    // ---- the path check, on both -----------------------------------------------------------------------

    @Test
    fun `no path - no image from either source`() {
        for (images in listOf(caching, notCaching)) {
            assertNull(imageUrl(images, null, TmdbImage.POSTER))
            assertNull(imageUrl(images, "", TmdbImage.POSTER))
            assertNull(imageUrl(images, "/", TmdbImage.POSTER))
        }
    }

    @Test
    fun `a path that could leave the proxy's folder or TMDB's sizes or the host is refused by either source`() {
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
            "@evil.example/a.jpg",
        ).forEach { path ->
            assertNull(imageUrl(caching, path, TmdbImage.POSTER), "refused through the proxy: ${path.replace("\n", "\\n")}")
            assertNull(imageUrl(notCaching, path, TmdbImage.POSTER), "refused from TMDB: ${path.replace("\n", "\\n")}")
        }
    }

    @Test
    fun `every recorded poster and backdrop path makes a URL on the one source and nowhere else`() {
        val rows = listOf("discover-trending", "discover-movies", "discover-tv", "discover-movies-upcoming", "search")
        val results = rows.flatMap { Fixtures.read<Page<MediaResult>>(it).results }
        val paths = results.flatMap { listOfNotNull(it.posterPath, it.backdropPath) }
        assertTrue(paths.size > 40, "the fixtures carry image paths")
        for (path in paths) {
            for (image in TmdbImage.entries) {
                assertEquals("$base/imageproxy/tmdb/t/p/${image.size}$path", imageUrl(caching, path, image))
                assertEquals("https://image.tmdb.org/t/p/${image.size}$path", imageUrl(notCaching, path, image))
            }
        }
    }

    // ---- pictures kept from before the switch changed ---------------------------------------------------

    @Test
    fun `an address is made by its own source only so kept art is asked again after the switch changes`() {
        val throughProxy = imageUrl(caching, "/a.jpg", TmdbImage.POSTER)
        val fromTmdb = imageUrl(notCaching, "/a.jpg", TmdbImage.POSTER)
        assertTrue(caching.made(throughProxy))
        assertFalse(caching.made(fromTmdb))
        assertTrue(notCaching.made(fromTmdb))
        assertFalse(notCaching.made(throughProxy))
        assertTrue(notCaching.made(null), "no picture is never stale")
        assertFalse(notCaching.made("https://image.tmdb.org.evil.example/t/p/w300_and_h450_face/a.jpg"))
    }
}
