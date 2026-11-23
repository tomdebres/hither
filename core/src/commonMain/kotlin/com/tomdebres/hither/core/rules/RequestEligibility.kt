package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.SeasonSummary
import com.tomdebres.hither.core.model.User

/** What a request button would do, if there is one. */
enum class RequestAction {
    /** No button: not permitted, switched off on the server, or nothing left to ask for. */
    NONE,

    /** "Request" (or "Request in 4K"): nothing of this title has been asked for yet. */
    REQUEST,

    /** "Request More": a series with seasons still to ask for. */
    REQUEST_MORE,
}

/**
 * What the signed-in user may ask for on one title. [standard] is the regular copy and [fourK]
 * the 4K one; the two are decided separately, as Seerr's `RequestButton` decides them.
 */
data class RequestOptions(
    val standard: RequestAction,
    val fourK: RequestAction,
    /** A request made now is approved by the server at once, so no "pending" is shown. */
    val autoApproved: Boolean,
    val autoApproved4k: Boolean,
    /** The modal offers seasons to choose from; otherwise a request is for every season left. */
    val seasonPicker: Boolean,
) {
    fun actionFor(is4k: Boolean): RequestAction = if (is4k) fourK else standard
    fun autoApprovedFor(is4k: Boolean): Boolean = if (is4k) autoApproved4k else autoApproved

    companion object {
        val NONE = RequestOptions(RequestAction.NONE, RequestAction.NONE, false, false, false)
    }
}

/**
 * The request buttons a title gets, from the server's public settings, the user's permissions
 * and the title's library record. [seasons] is the series' season list (empty for a film).
 *
 * The rules, each from Seerr's web UI:
 * - a regular request needs REQUEST or the per-type REQUEST_MOVIE / REQUEST_TV;
 * - a 4K request needs REQUEST_4K or the per-type bit, **and** 4K switched on for that type;
 * - "Request" shows while nothing of the title is requested (or it was deleted and nobody has
 *   a pending request); a series that is partly covered gets "Request More" until every
 *   requestable season is taken, unless this user's own request is still pending;
 * - a blocklisted title gets nothing.
 */
fun requestOptions(
    type: MediaType,
    settings: PublicSettings,
    user: User,
    media: MediaInfo?,
    seasons: List<SeasonSummary> = emptyList(),
): RequestOptions {
    val held = user.permissions
    val mayRequest = Permission.hasAny(
        held,
        Permission.REQUEST,
        if (type == MediaType.MOVIE) Permission.REQUEST_MOVIE else Permission.REQUEST_TV,
    )
    val enabled4k = if (type == MediaType.MOVIE) settings.movie4kEnabled else settings.series4kEnabled
    val mayRequest4k = enabled4k && Permission.hasAny(
        held,
        Permission.REQUEST_4K,
        if (type == MediaType.MOVIE) Permission.REQUEST_4K_MOVIE else Permission.REQUEST_4K_TV,
    )
    val requestable = requestableSeasons(seasons, settings.enableSpecialEpisodes)

    fun action(is4k: Boolean, permitted: Boolean): RequestAction {
        if (!permitted) return RequestAction.NONE
        val status = media?.statusFor(is4k) ?: MediaStatus.UNKNOWN
        val pending = pendingRequest(media, is4k, user.id)
        if (media == null || status == MediaStatus.UNKNOWN || (status == MediaStatus.DELETED && pending == null)) {
            return RequestAction.REQUEST
        }
        if (type != MediaType.TV || status == MediaStatus.BLOCKLISTED) return RequestAction.NONE
        if (pending != null && pending.requestedBy?.id == user.id) return RequestAction.NONE
        val complete = requestable.all { it in takenSeasons(media, is4k) }
        return if (complete) RequestAction.NONE else RequestAction.REQUEST_MORE
    }

    val standard = action(is4k = false, permitted = mayRequest)
    val fourK = action(is4k = true, permitted = mayRequest4k)
    return RequestOptions(
        standard = standard,
        fourK = fourK,
        autoApproved = standard != RequestAction.NONE && Permission.hasAny(
            held,
            Permission.MANAGE_REQUESTS,
            Permission.AUTO_APPROVE,
            if (type == MediaType.MOVIE) Permission.AUTO_APPROVE_MOVIE else Permission.AUTO_APPROVE_TV,
        ),
        autoApproved4k = fourK != RequestAction.NONE && Permission.hasAny(
            held,
            Permission.MANAGE_REQUESTS,
            Permission.AUTO_APPROVE_4K,
            if (type == MediaType.MOVIE) Permission.AUTO_APPROVE_4K_MOVIE else Permission.AUTO_APPROVE_4K_TV,
        ),
        seasonPicker = type == MediaType.TV && settings.partialRequestsEnabled,
    )
}

/** This user's pending request for the copy, or failing that anybody's: Seerr's `activeRequest`. */
private fun pendingRequest(media: MediaInfo?, is4k: Boolean, userId: Int): MediaRequest? {
    val pending = media?.requests.orEmpty().filter { it.status == RequestStatus.PENDING && it.is4k == is4k }
    return pending.firstOrNull { it.requestedBy?.id == userId } ?: pending.firstOrNull()
}
