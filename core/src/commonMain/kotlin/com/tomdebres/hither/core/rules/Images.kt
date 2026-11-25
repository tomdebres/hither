package com.tomdebres.hither.core.rules

/**
 * TMDB's image sizes, by the names Seerr's web UI asks for. TMDB scales and crops on its side;
 * the app decodes at the size it draws.
 */
enum class TmdbImage(val size: String) {
    /** The TitleCard's poster, 300 x 450 with faces kept in frame (Seerr's `TitleCard`). */
    POSTER("w300_and_h450_face"),

    /** The media page's backdrop, 1920 x 800 (Seerr's `MovieDetails` and `TvDetails`). */
    BACKDROP("w1920_and_h800_multi_faces"),

    /** The collection card's backdrop, 1440 x 320 (Seerr's `MovieDetails` collection block). */
    COLLECTION("w1440_and_h320_multi_faces"),

    /** A person's photo on a cast card (Seerr's `PersonCard`). */
    PROFILE("w600_and_h900_bestv2"),

    /** A backdrop at TMDB's own 16:9, 780 wide: a card on the TV's home screen (M4). */
    WIDE("w780"),

    /** A streaming service's logo, 92 wide (Seerr's web asks for 45; the TV draws it larger). */
    LOGO("w92"),
}

/**
 * A TMDB file path as the API gives it: one slash, a file name of letters, digits, `_` and
 * `-`, and an image extension. Anything else (a second slash, `..`, a scheme, a query) could
 * reach past the proxy's one folder, or past TMDB's sizes, so it is not an image path here.
 */
private val TMDB_FILE = Regex("^/[A-Za-z0-9][A-Za-z0-9_-]*\\.(jpg|jpeg|png|webp|svg)$", RegexOption.IGNORE_CASE)

/**
 * Where one server's TMDB pictures come from (DECISIONS 382, which supersedes 37): **the server's own `/imageproxy`
 * while its `cacheImages` is on, TMDB's image server while it is off**, as Seerr's own web page chooses
 * (`CachedImage`). Since Seerr 3.5.2 the proxy refuses every picture while the switch is off (404 "Image caching is
 * disabled.", Seerr's default), so a server that keeps no copies of its own is served by TMDB, which then sees the TV's
 * address and nothing else: the image loaders send no cookie, token or header of the server's.
 *
 * Every address is [base] then `/t/p/<size>/<file>`: Seerr's proxy mounts TMDB's own paths under `/imageproxy/tmdb`.
 * [serverBase] is the client's base URL ([com.tomdebres.hither.core.ServerAddress.normalise]); [tmdbImages] is
 * [TMDB_IMAGES] in every release build, and a debug build's test may name a stand-in (`scripts/fake-seerr`'s `/tmdb`).
 */
class ImageSource(serverBase: String, cacheImages: Boolean, tmdbImages: String) {
    /** True while the server keeps its own copies: pictures come through its `/imageproxy`. */
    val fromServer: Boolean = cacheImages

    /** What every picture's address starts with, before `/t/p/<size>/<file>`; no trailing slash. */
    val base: String = if (cacheImages) "${serverBase.trimEnd('/')}/imageproxy/tmdb" else tmdbImages.trimEnd('/')

    /**
     * Whether [url] is one of this source's addresses (a missing picture is): a title's pictures kept from before the
     * server's switch changed (the TV's home screen, the Top Shelf) are asked for again when it is not.
     */
    fun made(url: String?): Boolean = url == null || url.startsWith("$base/t/p/")

    override fun equals(other: Any?): Boolean = other is ImageSource && other.base == base

    override fun hashCode(): Int = base.hashCode()

    override fun toString(): String = if (fromServer) "ImageSource(server's /imageproxy)" else "ImageSource($base)"

    companion object {
        /** TMDB's image server, where Seerr's web page sends a browser while the server keeps no copies. */
        const val TMDB_IMAGES = "https://image.tmdb.org"

        /**
         * Before the server has said (no `settings/public` read yet): its own proxy, as Hither did up to 0.7.0, so nothing
         * goes to TMDB until the server says it keeps no copies. Both apps read the switch before any screen shows a
         * picture (at every start and sign-in), so this only stands until then.
         */
        fun unknown(serverBase: String): ImageSource = ImageSource(serverBase, true, TMDB_IMAGES)
    }
}

/**
 * Where the app fetches a TMDB image: from [images], the server's `/imageproxy` or TMDB's image server as the server's
 * `cacheImages` says ([ImageSource]). The proxy takes no session, and neither does TMDB: the loaders keep no cookies.
 *
 * Returns null for a missing path or one that is not a plain TMDB file path, from either source, and the card then
 * draws its placeholder.
 */
fun imageUrl(images: ImageSource, path: String?, image: TmdbImage): String? {
    if (path == null || !TMDB_FILE.matches(path)) return null
    return "${images.base}/t/p/${image.size}$path"
}
