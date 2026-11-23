package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.User

// The all-users Requests view and its Approve and Decline (M5, DECISIONS 94), read in Seerr at
// `3f7d5c3`: `RequestList` (its filters and words), `RequestItem` (its buttons), and the approve and
// decline route with `MediaRequestSubscriber.updateParentStatus`.

/**
 * Seerr's status filter on its Requests page, in its order and words, with the API value each asks
 * for. Seerr's page opens on [PENDING]: an approver works through a queue.
 */
enum class RequestFilter(val api: String, val words: String) {
    ALL("all", "All"),
    PENDING("pending", "Pending"),
    APPROVED("approved", "Approved"),
    COMPLETED("completed", "Completed"),
    PROCESSING("processing", "Processing"),
    FAILED("failed", "Failed"),
    AVAILABLE("available", "Available"),
    UNAVAILABLE("unavailable", "Unavailable"),
    DELETED("deleted", "Deleted"),
}

/** What a request row offers an account: Approve and Decline for a pending request, to one whose live rights allow them. */
fun decisionsFor(request: MediaRequest, approver: User?): Boolean =
    request.status == RequestStatus.PENDING && mayDecide(approver)

/**
 * The request as Seerr leaves it after [approved] (approve, else decline) by [by], worked out from the
 * row Hither already has so the row shows its new status at once (as 63 did for "Pending"); the list's
 * next read puts the server's word in its place. The route moves only a pending request; then
 * `updateParentStatus`: an approved copy goes to processing unless it is processing or partly or wholly
 * available; a declined film's copy goes back to unknown (not a deleted one), a declined series' too
 * where it was pending (Seerr also asks that no other request for it is pending, which a row cannot
 * know: the re-read settles it); the request's seasons take its status.
 */
fun afterDecision(request: MediaRequest, approved: Boolean, by: User?): MediaRequest {
    if (request.status != RequestStatus.PENDING) return request
    val status = if (approved) RequestStatus.APPROVED else RequestStatus.DECLINED
    val media = request.media?.let { media ->
        val copy = media.statusFor(request.is4k)
        val next = when {
            approved && copy !in setOf(MediaStatus.AVAILABLE, MediaStatus.PARTIALLY_AVAILABLE, MediaStatus.PROCESSING) -> MediaStatus.PROCESSING
            !approved && isFilm(request) && copy != MediaStatus.DELETED -> MediaStatus.UNKNOWN
            !approved && !isFilm(request) && copy == MediaStatus.PENDING -> MediaStatus.UNKNOWN
            else -> copy
        }
        if (request.is4k) media.copy(status4k = next) else media.copy(status = next)
    }
    return request.copy(
        status = status,
        modifiedBy = by ?: request.modifiedBy,
        media = media,
        seasons = request.seasons.map { it.copy(status = status) },
    )
}

private fun isFilm(request: MediaRequest): Boolean =
    (MediaType.fromApi(request.type) ?: MediaType.fromApi(request.media?.mediaType)) == MediaType.MOVIE

/**
 * Whether a request belongs under [filter], as Seerr's list route filters (`server/routes/request.ts`):
 * the request's status, and for some filters its copy's media status. Every filter, All included,
 * leaves out a blocklisted copy.
 */
fun inFilter(request: MediaRequest, filter: RequestFilter): Boolean {
    val media = request.media?.statusFor(request.is4k) ?: MediaStatus.UNKNOWN
    if (media == MediaStatus.BLOCKLISTED) return false
    val notYet = setOf(MediaStatus.UNKNOWN, MediaStatus.PENDING, MediaStatus.PROCESSING, MediaStatus.PARTIALLY_AVAILABLE)
    return when (filter) {
        RequestFilter.ALL -> true
        RequestFilter.PENDING -> request.status == RequestStatus.PENDING
        RequestFilter.APPROVED -> request.status == RequestStatus.APPROVED
        RequestFilter.PROCESSING -> request.status == RequestStatus.APPROVED && media in notYet
        RequestFilter.UNAVAILABLE -> request.status in setOf(RequestStatus.PENDING, RequestStatus.APPROVED) && media in notYet
        RequestFilter.FAILED -> request.status == RequestStatus.FAILED
        RequestFilter.COMPLETED -> request.status == RequestStatus.COMPLETED
        RequestFilter.AVAILABLE -> request.status == RequestStatus.COMPLETED && media == MediaStatus.AVAILABLE
        RequestFilter.DELETED -> request.status == RequestStatus.COMPLETED && media == MediaStatus.DELETED
    }
}

/**
 * Where focus goes after a decision on row [decided] of [rows] (by request id): the next row still to
 * decide below it, else the nearest above it, else none (the row itself keeps it, now with no
 * buttons). Tom works down a queue; the decided row stays in place with its new status until the list
 * is read again, so nothing is removed from under focus.
 */
fun nextToDecide(rows: List<MediaRequest>, decided: Int): Int? {
    val at = rows.indexOfFirst { it.id == decided }
    if (at < 0) return null
    val open = { r: MediaRequest -> r.status == RequestStatus.PENDING && r.id != decided }
    return rows.drop(at + 1).firstOrNull(open)?.id ?: rows.take(at).lastOrNull(open)?.id
}
