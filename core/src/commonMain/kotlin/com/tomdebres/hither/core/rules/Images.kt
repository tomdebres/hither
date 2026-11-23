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
 * reach past the proxy's one folder, so it is not an image path here.
 */
private val TMDB_FILE = Regex("^/[A-Za-z0-9][A-Za-z0-9_-]*\\.(jpg|jpeg|png|webp|svg)$", RegexOption.IGNORE_CASE)

/**
 * Where the app fetches a TMDB image: **from the signed-in server's `/imageproxy`, always**,
 * never from TMDB itself, so that the app talks to the one server it was given (REVIEW.md).
 *
 * The proxy is Seerr's `server/routes/imageproxy.ts`, mounted at `/imageproxy` outside the
 * API: it takes no session (and clears cookies, so the image loader needs none) and it
 * answers whatever the server's `cacheImages` switch says. That switch only decides whether
 * Seerr's *web page* uses the proxy or TMDB's own address; the route itself does not read it.
 * So a server with caching off still serves Hither's posters, through its own disk cache.
 *
 * [baseUrl] is the client's base URL ([com.tomdebres.hither.core.ServerAddress.normalise]).
 * Returns null for a missing path or one that is not a plain TMDB file path, and the card
 * then draws its placeholder.
 */
fun imageUrl(baseUrl: String, path: String?, image: TmdbImage): String? {
    if (path == null || !TMDB_FILE.matches(path)) return null
    return "${baseUrl.trimEnd('/')}/imageproxy/tmdb/t/p/${image.size}$path"
}
