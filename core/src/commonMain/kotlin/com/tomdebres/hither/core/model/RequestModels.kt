// Adapted from SeerrTV's model/RequestModels.kt (MIT, see THIRD-PARTY.md): the request record
// and the body of POST /request, cut down to what a request-only client sends and reads.
package com.tomdebres.hither.core.model

import kotlinx.serialization.Serializable

/** `GET /api/v1/request`. */
@Serializable
data class RequestPage(
    val pageInfo: PageInfo = PageInfo(),
    val results: List<MediaRequest> = emptyList(),
)

/**
 * `GET /api/v1/request/count`: the server's requests counted by status, everyone's (Seerr's route
 * counts every request; its web shows [pending] on the sidebar's Requests to an approver).
 */
@Serializable
data class RequestCount(
    val total: Int = 0,
    val movie: Int = 0,
    val tv: Int = 0,
    val pending: Int = 0,
    val approved: Int = 0,
    val declined: Int = 0,
    val processing: Int = 0,
    val available: Int = 0,
    val completed: Int = 0,
)

/** One request, in the requests list and inside a title's [MediaInfo]. */
@Serializable
data class MediaRequest(
    val id: Int = 0,
    val status: Int = RequestStatus.PENDING,
    val type: String? = null,
    val is4k: Boolean = false,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val media: MediaInfo? = null,
    val seasons: List<RequestSeason> = emptyList(),
    val requestedBy: User? = null,
    /**
     * Who last moved it on: an approver, or for a request approved at once by the requester's own
     * automatic approval, the requester (Seerr's `MediaRequest.request`).
     */
    val modifiedBy: User? = null,
)

/** A season named in a request. */
@Serializable
data class RequestSeason(
    val id: Int = 0,
    val seasonNumber: Int,
    val status: Int = RequestStatus.PENDING,
)

/**
 * The body of `POST /api/v1/request`. Built only by the functions in `rules/RequestPlan.kt`,
 * which are where "what was chosen is what is sent" is tested. [seasons] is left out for a
 * film; no server, profile, folder or user override is ever sent, so the server's defaults
 * and the signed-in user apply.
 */
@Serializable
data class RequestBody(
    val mediaType: String,
    val mediaId: Int,
    val is4k: Boolean = false,
    val seasons: List<Int>? = null,
)
