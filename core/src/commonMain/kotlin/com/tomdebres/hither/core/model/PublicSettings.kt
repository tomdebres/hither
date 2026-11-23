package com.tomdebres.hither.core.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/settings/public`: what a server says about itself before sign-in. Every field
 * has the cautious default, so a server that omits one gets the narrower behaviour (no 4K,
 * whole-series requests, no specials).
 */
@Serializable
data class PublicSettings(
    val initialized: Boolean = false,
    val applicationTitle: String = "Seerr",
    val localLogin: Boolean = false,
    /** Sign-in through the media server (Plex, Jellyfin or Emby, by [mediaServerType]) is on (M8). */
    val mediaServerLogin: Boolean = false,
    /** Seerr's MediaServerType: 1 Plex, 2 Jellyfin, 3 Emby, 4 not configured (`server/constants/server.ts`). */
    val mediaServerType: Int = 0,
    /**
     * A media-server account Seerr does not hold yet may sign in, and Seerr makes it a user (Seerr's "Enable New
     * Jellyfin/Emby/Plex Sign-In"). Off, such an account is refused with "Access denied." (M8).
     */
    val newPlexLogin: Boolean = false,
    /** The Jellyfin or Emby address Seerr's owner gave for people to use (Seerr's "External URL"); often empty. */
    val jellyfinExternalHost: String = "",
    val movie4kEnabled: Boolean = false,
    val series4kEnabled: Boolean = false,
    val partialRequestsEnabled: Boolean = false,
    val enableSpecialEpisodes: Boolean = false,
    val cacheImages: Boolean = false,
    val hideAvailable: Boolean = false,
    val hideBlocklisted: Boolean = false,
    /** Newer than Seerr 3.3: Discover leaves out titles with a request still open. */
    val hideRequested: Boolean = false,
    val locale: String = "",
    /** The country Discover and the media page read release information for; empty means the US. */
    val discoverRegion: String = "",
    /**
     * The country whose streaming services the media page lists ("Currently Streaming On"), unless the
     * account sets its own; empty means the US, as Seerr's web reads it.
     */
    val streamingRegion: String = "",
)
