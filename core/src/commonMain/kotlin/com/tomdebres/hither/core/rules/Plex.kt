// The deep link's form is lifted from SeerrTV's util/PlexDeepLink.kt (MIT, see THIRD-PARTY.md),
// which found that Plex for Android TV opens a title's page for it; how Hither reads Seerr's own
// link and when it offers one are Hither's, from Seerr's MovieDetails and TvDetails.
package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.PublicSettings
import io.ktor.http.decodeURLQueryComponent

/** "Play on Plex" for one copy of a title: the link that opens it in the Plex app on the TV. */
data class PlayAction(val is4k: Boolean, val link: String) {
    /** Seerr's words, with Plex as the media server's name: "Play on Plex", "Play 4K on Plex". */
    val text: String get() = if (is4k) "Play 4K on Plex" else "Play on Plex"
}

/** Seerr's `MediaServerType.PLEX` in `settings/public`'s `mediaServerType`. */
const val MEDIA_SERVER_PLEX = 1

/**
 * The media page's "Play on Plex" actions, as Seerr's `MovieDetails` and `TvDetails` offer them:
 * the regular copy's when the server gives a Plex link for it (Seerr sets one only while Plex has
 * the title) and the user may request that type; the 4K copy's as well when 4K is on for the type
 * and the user may request it in 4K. Only on a Plex server. A link that does not read as Seerr's
 * gives no action, so there is never a button that cannot open anything.
 */
fun playActions(type: MediaType, media: MediaInfo?, settings: PublicSettings, permissions: Int?): List<PlayAction> {
    if (media == null || settings.mediaServerType != MEDIA_SERVER_PLEX) return emptyList()
    val movie = type == MediaType.MOVIE
    val regular = if (Permission.hasAny(permissions, Permission.REQUEST, if (movie) Permission.REQUEST_MOVIE else Permission.REQUEST_TV)) {
        plexAppLink(media.iOSPlexUrl, type)?.let { PlayAction(is4k = false, link = it) }
    } else {
        null
    }
    val enabled4k = if (movie) settings.movie4kEnabled else settings.series4kEnabled
    val fourK = if (enabled4k && Permission.hasAny(permissions, Permission.REQUEST_4K, if (movie) Permission.REQUEST_4K_MOVIE else Permission.REQUEST_4K_TV)) {
        plexAppLink(media.iOSPlexUrl4k, type)?.let { PlayAction(is4k = true, link = it) }
    } else {
        null
    }
    return listOfNotNull(regular, fourK)
}

/** Seerr's iOS link: `plex://preplay/?metadataKey=%2Flibrary%2Fmetadata%2F<ratingKey>&server=<machineId>`. */
private const val SEERR_LINK = "plex://preplay/?"
private val METADATA_KEY = Regex("^/library/metadata/([0-9]+)$")

/** A Plex server's machine identifier: letters and digits (40 hex characters on today's servers). */
private val MACHINE_ID = Regex("^[A-Za-z0-9]{1,64}$")

/**
 * The link that opens a title in the Plex app on Android TV, from the iOS link Seerr gives
 * (`mediaInfo.iOSPlexUrl`, set in Seerr's `Media.setPlexUrls`), or null when that is missing or
 * not in Seerr's form. SeerrTV found that today's Plex for Android TV ignores the older forms and
 * opens a title's page for this one, a film's or a series' (the series' own key):
 *
 * - film: `plex://libraries/<machineId>/unmatched/%2Flibrary%2Fmetadata%2F<ratingKey>`
 * - series: `plex://libraries/<machineId>/unmatched/tv/%2Flibrary%2Fmetadata%2F<ratingKey>`
 *
 * Both values come from the server, so they are taken only as Seerr writes them (a machine id of
 * letters and digits, a numeric rating key): nothing else is put into a link the TV will open.
 */
fun plexAppLink(iOSPlexUrl: String?, type: MediaType): String? {
    if (iOSPlexUrl == null || !iOSPlexUrl.startsWith(SEERR_LINK)) return null
    val query = iOSPlexUrl.removePrefix(SEERR_LINK).substringBefore('#').split('&').mapNotNull { pair ->
        val name = pair.substringBefore('=', missingDelimiterValue = "")
        if (name.isEmpty()) null else name to runCatching { pair.substringAfter('=').decodeURLQueryComponent() }.getOrNull()
    }.toMap()
    val key = query["metadataKey"]?.let { METADATA_KEY.find(it)?.groupValues?.get(1) } ?: return null
    val server = query["server"]?.takeIf { MACHINE_ID.matches(it) } ?: return null
    val series = if (type == MediaType.TV) "tv/" else ""
    return "plex://libraries/$server/unmatched/$series%2Flibrary%2Fmetadata%2F$key"
}
