package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.RequestStatus

/** Seerr's badge colours, by the name its `Badge` component gives them. */
enum class BadgeKind { SUCCESS, WARNING, DANGER, PRIMARY }

/** Seerr's status vocabulary, in its own English (`globalMessages`). */
enum class StatusWord(val english: String) {
    AVAILABLE("Available"),
    PARTIALLY_AVAILABLE("Partially Available"),
    REQUESTED("Requested"),
    PROCESSING("Processing"),
    PENDING("Pending"),
    DECLINED("Declined"),
    FAILED("Failed"),
    DELETED("Deleted"),
    BLOCKLISTED("Blocklisted"),
    NOT_REQUESTED("Not Requested"),
}

/** A status badge: the word, its colour, and whether it speaks for the 4K copy. */
data class StatusBadge(
    val word: StatusWord,
    val kind: BadgeKind,
    val is4k: Boolean = false,
    /** A download is running: the badge carries a spinner. */
    val inProgress: Boolean = false,
    /** How far it is, where the badge is Seerr's full one (the media page, My Requests; M6, DECISIONS 101). */
    val progress: DownloadProgress? = null,
) {
    /** The words on the badge: "Available", "4K Requested". */
    val text: String get() = if (is4k) "4K ${word.english}" else word.english
}

/**
 * The badge for a title's library status, or null where Seerr shows none (unknown, absent, or
 * a value this build does not know). [inProgress] is "the download client has items for it",
 * which turns the word into "Processing" and keeps the colour, as Seerr's `StatusBadge` does.
 */
fun mediaBadge(status: Int?, is4k: Boolean = false, inProgress: Boolean = false): StatusBadge? {
    val (word, kind) = when (status) {
        MediaStatus.AVAILABLE -> StatusWord.AVAILABLE to BadgeKind.SUCCESS
        MediaStatus.PARTIALLY_AVAILABLE -> StatusWord.PARTIALLY_AVAILABLE to BadgeKind.SUCCESS
        MediaStatus.PROCESSING -> StatusWord.REQUESTED to BadgeKind.PRIMARY
        MediaStatus.PENDING -> return StatusBadge(StatusWord.PENDING, BadgeKind.WARNING, is4k)
        MediaStatus.BLOCKLISTED -> return StatusBadge(StatusWord.BLOCKLISTED, BadgeKind.DANGER, is4k)
        MediaStatus.DELETED -> StatusWord.DELETED to BadgeKind.DANGER
        else -> return null
    }
    return StatusBadge(if (inProgress) StatusWord.PROCESSING else word, kind, is4k, inProgress)
}

/**
 * The badge on a request in the requests list. Declined and failed are the request's own
 * words; a pending request for a deleted title stays "Pending"; everything else is the title's
 * library badge for the copy that was asked for, so an approved request reads "Requested" and
 * a completed one "Available". Seerr's `RequestCard` and `RequestItem`, but for a series: its
 * request reads its own stage ("Pending", "Requested", "Available" once its seasons are in), not
 * the series' "Partially Available" (DECISIONS 80, 86).
 */
fun requestBadge(request: MediaRequest): StatusBadge? {
    val badge = requestBadgeOnly(request) ?: return null
    if (!badge.inProgress) return badge
    // Seerr fills the badge with the download's progress, and its tooltip says when it should be done (M6).
    val type = MediaType.fromApi(request.type) ?: MediaType.fromApi(request.media?.mediaType) ?: MediaType.MOVIE
    return badge.copy(progress = downloadProgress(request.media?.downloadsFor(request.is4k).orEmpty(), type))
}

private fun requestBadgeOnly(request: MediaRequest): StatusBadge? {
    val is4k = request.is4k
    val mediaStatus = request.media?.statusFor(is4k)
    val inProgress = request.media?.downloadsFor(is4k).orEmpty().isNotEmpty()
    val series = (MediaType.fromApi(request.type) ?: MediaType.fromApi(request.media?.mediaType)) == MediaType.TV
    return when {
        request.status == RequestStatus.DECLINED -> StatusBadge(StatusWord.DECLINED, BadgeKind.DANGER, is4k)
        request.status == RequestStatus.FAILED -> StatusBadge(StatusWord.FAILED, BadgeKind.DANGER, is4k)
        request.status == RequestStatus.PENDING && mediaStatus == MediaStatus.DELETED ->
            StatusBadge(StatusWord.PENDING, BadgeKind.WARNING, is4k)
        // A series request is about its own seasons, not the series: until they are in it reads its own
        // stage, in the media badge's words, rather than "Partially Available" for seasons someone else
        // had (Tom's call, DECISIONS 80; Seerr's web shows the series' status here).
        series && mediaStatus != MediaStatus.DELETED -> when (requestStage(request)) {
            RequestStage.PENDING -> mediaBadge(MediaStatus.PENDING, is4k)
            RequestStage.APPROVED -> mediaBadge(MediaStatus.PROCESSING, is4k, inProgress)
            RequestStage.READY -> mediaBadge(MediaStatus.AVAILABLE, is4k)
            else -> mediaBadge(mediaStatus, is4k, inProgress)
        }
        else -> mediaBadge(status = mediaStatus, is4k = is4k, inProgress = inProgress)
    }
}

/** The badge beside a season in the request modal. */
fun seasonBadge(state: SeasonState): StatusBadge = when (state) {
    SeasonState.NOT_REQUESTED -> StatusBadge(StatusWord.NOT_REQUESTED, BadgeKind.PRIMARY)
    SeasonState.PENDING -> StatusBadge(StatusWord.PENDING, BadgeKind.WARNING)
    SeasonState.REQUESTED -> StatusBadge(StatusWord.REQUESTED, BadgeKind.PRIMARY)
    SeasonState.PARTIALLY_AVAILABLE -> StatusBadge(StatusWord.PARTIALLY_AVAILABLE, BadgeKind.SUCCESS)
    SeasonState.AVAILABLE -> StatusBadge(StatusWord.AVAILABLE, BadgeKind.SUCCESS)
}
