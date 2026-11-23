package com.tomdebres.hither.core.model

import kotlinx.serialization.Serializable

/** `GET /api/v1/status`, which needs no session: the server's Seerr version ("3.5.2", or "develop-<sha>"). */
@Serializable
data class ServerStatus(
    val version: String = "",
    val commitTag: String = "",
)

/**
 * `POST /auth/jellyfin/quickconnect/initiate` (Seerr 3.4.0+): the code to show, which the person approves in
 * Jellyfin, and the secret Hither asks about it with. The secret is never shown or kept.
 */
@Serializable
data class QuickConnectCode(val code: String, val secret: String) {
    /** Never the secret: a code can end up in a log line by accident. */
    override fun toString(): String = "QuickConnectCode(code=$code, secret=…)"
}

/** `GET /auth/jellyfin/quickconnect/check`: whether the code has been approved in Jellyfin yet. */
@Serializable
internal data class QuickConnectState(val authenticated: Boolean = false)

/**
 * A plex.tv PIN (`POST /api/v2/pins`, `GET /api/v2/pins/{id}`): [code] is what the person types at
 * plex.tv/link, [authToken] is there once they have. [expiresIn] is in seconds, from when it was made.
 */
@Serializable
data class PlexPin(
    val id: Long,
    val code: String,
    val expiresIn: Int = 0,
    val authToken: String? = null,
) {
    /** Never the token: a PIN can end up in a log line by accident. */
    override fun toString(): String = "PlexPin(id=$id, code=$code, expiresIn=$expiresIn, authToken=${if (authToken == null) "none" else "…"})"
}
