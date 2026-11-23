package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.model.PublicSettings

/** The media server a Seerr is set up with: its `mediaServerType` (`server/constants/server.ts`). */
enum class MediaServerKind {
    PLEX,
    JELLYFIN,
    EMBY,

    /** Not configured yet, or a number this build does not know. */
    NONE,
    ;

    /** The media server's name, as the form names it ("Sign in with Jellyfin"); empty for [NONE]. */
    val title: String
        get() = when (this) {
            PLEX -> "Plex"
            JELLYFIN -> "Jellyfin"
            EMBY -> "Emby"
            NONE -> ""
        }

    companion object {
        fun of(mediaServerType: Int): MediaServerKind = when (mediaServerType) {
            1 -> PLEX
            2 -> JELLYFIN
            3 -> EMBY
            else -> NONE
        }
    }
}

/**
 * The ways a server lets people sign in (M8), from `settings/public` and, for Quick Connect, the server's
 * Seerr version. A screen offers these and nothing else.
 *
 * - [local]: Seerr's own email and password (`localLogin`).
 * - [plex]: Plex's link code, then `/auth/plex` (`mediaServerLogin` on a Plex server).
 * - [mediaServerPassword]: a Jellyfin or Emby username and password, through `/auth/jellyfin` (`mediaServerLogin` on
 *   a Jellyfin or Emby server).
 * - [quickConnect]: Jellyfin's Quick Connect through Seerr, which Seerr 3.4.0 added (#2212) for Jellyfin only.
 */
data class SignInMethods(
    val local: Boolean,
    val plex: Boolean,
    val mediaServerPassword: Boolean,
    val quickConnect: Boolean,
    /** The server's media server, to name on its button ("Jellyfin") and in its errors. */
    val mediaServer: MediaServerKind,
) {
    /** Any way through the media server at all: the screen's "Or sign in with" row. */
    val anyMediaServer: Boolean get() = plex || mediaServerPassword

    /** No way in at all: a server with every sign-in switched off (or not set up yet). */
    val none: Boolean get() = !local && !anyMediaServer
}

/**
 * What [settings] offers. [seerrVersion] is `GET /status`'s `version`, read only for a Jellyfin server; null when it
 * was not read or not answered, and then Quick Connect is not offered: a button that can only fail is worse than
 * none, and username and password still work on any Seerr (DECISIONS 234).
 */
fun signInMethods(settings: PublicSettings, seerrVersion: String?): SignInMethods {
    val kind = MediaServerKind.of(settings.mediaServerType)
    val viaServer = settings.mediaServerLogin
    val password = viaServer && (kind == MediaServerKind.JELLYFIN || kind == MediaServerKind.EMBY)
    return SignInMethods(
        local = settings.localLogin,
        plex = viaServer && kind == MediaServerKind.PLEX,
        mediaServerPassword = password,
        quickConnect = password && kind == MediaServerKind.JELLYFIN && seerrVersion != null && hasQuickConnect(seerrVersion),
        mediaServer = kind,
    )
}

/** Whether Seerr's methods want its version read first: only a Jellyfin server's, for Quick Connect. */
fun wantsSeerrVersion(settings: PublicSettings): Boolean =
    settings.mediaServerLogin && MediaServerKind.of(settings.mediaServerType) == MediaServerKind.JELLYFIN

/**
 * Whether a Seerr of [version] has Quick Connect: 3.4.0 and later. A development build ("develop-<sha>") is newer
 * than every release, so yes; anything that does not read as a version, no.
 */
fun hasQuickConnect(version: String): Boolean {
    val text = version.trim().removePrefix("v")
    if (text.startsWith("develop")) return true
    val parts = text.substringBefore('-').split('.')
    if (parts.size < 2) return false
    val numbers = parts.take(3).map { it.toIntOrNull() ?: return false }
    val (major, minor) = numbers[0] to numbers[1]
    return major > 3 || (major == 3 && minor >= 4)
}

/**
 * Where a Jellyfin user approves a Quick Connect code, for the code's page: the address Seerr's owner gave for
 * Jellyfin (its "External URL"), without the scheme, or null when there is none and the page says it in words.
 */
fun quickConnectPlace(settings: PublicSettings): String? =
    settings.jellyfinExternalHost.trim().takeIf { it.isNotEmpty() }
        ?.substringAfter("://")?.trimEnd('/')?.takeIf { it.isNotEmpty() }
