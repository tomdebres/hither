package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.RequestBody
import com.tomdebres.hither.core.model.RequestStatus

// The request modal (`docs/design/seerr-reference.md` 1.10 and 2.9) and what follows a request,
// as rules a test can hold without a device or a server.

/** A season's line in the modal's table. */
data class SeasonRow(
    val seasonNumber: Int,
    /** Seerr's words: "Specials" for season 0, else "Season 1". */
    val name: String,
    val episodes: Int,
    val badge: StatusBadge,
    /** Already requested or in the library for this copy: dimmed, on, and not to be flipped. */
    val taken: Boolean,
    /** Open, but the quota has no request left for it beside those picked: greyed and not to be switched on (M6). */
    val overQuota: Boolean = false,
)

/** Everything the request modal shows for one copy of one title, with a pick of [selected] seasons. */
data class RequestModalView(
    /** "Request Movie", "Request Series in 4K": Seerr's modal titles. */
    val title: String,
    /** The title of the film or series, under the modal's title. */
    val subtitle: String,
    /** "This request needs no approval.", only where the account's rights say so. */
    val approvedAutomatically: Boolean,
    /** The season table; empty for a film. */
    val rows: List<SeasonRow>,
    /** Whether the seasons can be picked (partial requests on); without it the rows are to read. */
    val picker: Boolean,
    /** The seasons picked that are still open, in the table's order. */
    val picked: List<Int>,
    /** The primary button's words and whether it can be pressed. */
    val primary: String,
    val primaryEnabled: Boolean,
    /** Seerr's quota line, where the account has a limit for this type (M6, DECISIONS 101). */
    val quota: QuotaLine? = null,
)

/** Seerr's season name for the table: `globalMessages.specials` and `seasonnumber`. */
fun seasonName(seasonNumber: Int): String = if (seasonNumber == 0) "Specials" else "Season $seasonNumber"

/**
 * The modal for [page]'s regular copy or, with [is4k], its 4K one: Seerr's `MovieRequestModal`
 * and `TvRequestModal`, without their quota and advanced parts. [selected] is what the user has
 * switched on; seasons already taken never count, whatever it says. While [sending], the
 * primary reads "Requesting…" and cannot be pressed (one press, one request).
 */
fun requestModal(page: MediaPage, is4k: Boolean, selected: Set<Int>, sending: Boolean): RequestModalView {
    val type = page.type
    val title = when (type) {
        MediaType.MOVIE -> if (is4k) "Request Movie in 4K" else "Request Movie"
        MediaType.TV -> if (is4k) "Request Series in 4K" else "Request Series"
    }
    val action = page.options.actionFor(is4k)
    val rows = if (type == MediaType.TV) seasonRows(page, is4k) else emptyList()
    val picker = type == MediaType.TV && page.settings.partialRequestsEnabled
    val open = rows.filter { !it.taken }.map { it.seasonNumber }
    val quota = quotaLine(page.quota, type, page.user.permissions)
    // Seasons a request may still ask for (Seerr's TvRequestModal): the quota's remaining, else every one.
    val room = quota?.remaining ?: Int.MAX_VALUE
    val picked = (if (picker) open.filter { it in selected } else open).let { if (picker) it.take(room) else it }
    val greyed = if (picker && picked.size >= room) open.filter { it !in picked }.toSet() else emptySet()
    val overQuota = quota != null && when (type) {
        MediaType.MOVIE -> quota.remaining == 0
        MediaType.TV -> quota.remaining == 0 || (!picker && open.size > quota.remaining)
    }
    val (primary, enabled) = when {
        sending -> "Requesting…" to false
        action == RequestAction.NONE -> (if (type == MediaType.TV) "Already Requested" else if (is4k) "Request in 4K" else "Request") to false
        // Past the quota Seerr would refuse it ("Movie Quota exceeded."): the button says so instead.
        overQuota -> (if (type == MediaType.TV) "Not Enough Season Requests Remaining" else "No Movie Requests Remaining") to false
        type == MediaType.MOVIE -> (if (is4k) "Request in 4K" else "Request") to true
        open.isEmpty() -> "Already Requested" to false
        !picker -> (if (is4k) "Request in 4K" else "Request") to true
        picked.isEmpty() -> "Select Season(s)" to false
        else -> {
            val count = if (picked.size == 1) "1 Season" else "${picked.size} Seasons"
            "Request $count${if (is4k) " in 4K" else ""}" to true
        }
    }
    return RequestModalView(
        title = title,
        subtitle = page.title,
        approvedAutomatically = action != RequestAction.NONE && page.options.autoApprovedFor(is4k),
        rows = rows.map { if (it.seasonNumber in greyed) it.copy(overQuota = true) else it },
        picker = picker,
        picked = picked,
        primary = primary,
        primaryEnabled = enabled,
        quota = quota,
    )
}

/**
 * The season table's rows: the seasons [requestableSeasons] lists (specials only where the
 * server has them on), each with its badge for this copy and whether it is already taken.
 */
fun seasonRows(page: MediaPage, is4k: Boolean): List<SeasonRow> {
    val taken = takenSeasons(page.media, is4k)
    val episodes = page.seasons.associate { it.seasonNumber to it.episodeCount }
    return requestableSeasons(page.seasons, page.settings.enableSpecialEpisodes).map { number ->
        SeasonRow(
            seasonNumber = number,
            name = seasonName(number),
            episodes = episodes[number] ?: 0,
            badge = seasonBadge(seasonState(number, page.media, is4k)),
            taken = number in taken,
        )
    }
}

/**
 * The body the modal sends, through the only two functions allowed to build one (REVIEW.md):
 * null when there is nothing that may be sent.
 */
fun requestBody(page: MediaPage, is4k: Boolean, selected: Set<Int>): RequestBody? = when (page.type) {
    MediaType.MOVIE -> movieRequestBody(page.tmdbId, is4k, page.options)
    MediaType.TV -> tvRequestBody(page.tmdbId, is4k, selected, page.seasons, page.media, page.settings, page.options)
}

/**
 * The title's library record once the server has taken [created], worked out from the 201
 * alone so the page and the card show the new status at once ("Pending at once", 2.9): as
 * Seerr's `MediaRequest.request` saves it (the copy asked for goes from unknown or deleted to
 * pending) and its `MediaRequestSubscriber` then moves it (an approved request takes it to
 * processing, "Requested", unless it is processing or partly or wholly available already). The
 * request joins the record's requests, so its seasons count as taken. The 201's own record is
 * not used: Seerr sends it as saved, before the approval moved it on.
 */
fun afterRequest(media: MediaInfo?, created: MediaRequest, type: MediaType, tmdbId: Int): MediaInfo {
    val before = media ?: MediaInfo(id = created.media?.id ?: 0, mediaType = type.api, tmdbId = tmdbId)
    val is4k = created.is4k
    var status = before.statusFor(is4k)
    if (status == MediaStatus.UNKNOWN || status == MediaStatus.DELETED) status = MediaStatus.PENDING
    if (created.status == RequestStatus.APPROVED &&
        status != MediaStatus.AVAILABLE && status != MediaStatus.PARTIALLY_AVAILABLE && status != MediaStatus.PROCESSING
    ) {
        status = MediaStatus.PROCESSING
    }
    val request = created.copy(media = null)
    return if (is4k) {
        before.copy(status4k = status, requests = before.requests + request)
    } else {
        before.copy(status = status, requests = before.requests + request)
    }
}

/** How a request that was not created went, each with its own words in the app. */
enum class RequestFailure {
    /** 202: every season asked for is already requested or available. */
    NOTHING_TO_REQUEST,

    /** 409: the film is already requested. */
    ALREADY_REQUESTED,

    /** 403 (not Seerr's word that the session is over): the server, or something before it, refused. */
    REFUSED,

    /** No answer: it may or may not have reached the server. */
    UNREACHABLE,

    /** The server's CSRF protection refused it over http: it was not made (DECISIONS 70). */
    CSRF_NEEDS_HTTPS,

    /** The session ended and could not be renewed: the app goes back to sign-in. */
    SIGNED_OUT,

    /** Anything else. */
    OTHER,
}

fun requestFailure(error: Throwable): RequestFailure = when (error) {
    is SeerrException.NothingToRequest -> RequestFailure.NOTHING_TO_REQUEST
    is SeerrException.AlreadyRequested -> RequestFailure.ALREADY_REQUESTED
    is SeerrException.Refused -> RequestFailure.REFUSED
    is SeerrException.CsrfRefused -> if (error.overHttp) RequestFailure.CSRF_NEEDS_HTTPS else RequestFailure.REFUSED
    is SeerrException.Unreachable -> RequestFailure.UNREACHABLE
    is SeerrException.SignedOut -> RequestFailure.SIGNED_OUT
    else -> RequestFailure.OTHER
}
