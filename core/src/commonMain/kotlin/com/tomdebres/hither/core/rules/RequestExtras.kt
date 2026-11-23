package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.DownloadItem
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.Quota
import com.tomdebres.hither.core.model.QuotaStatus
import com.tomdebres.hither.core.model.RequestStatus
import kotlin.math.roundToInt

// M6's request parts (DECISIONS 101): download progress (audit item 13), cancelling one's own pending request
// (22) and the quota in the request modal (17), each as Seerr's web works it out at 3f7d5c3.

/**
 * How far a copy's download is, as Seerr's StatusBadge fills itself: the first item's share done
 * ([percent], 0-100), when it should finish ([estimatedAt], ISO 8601, or null), and for a series the
 * episode it is ("S1E2"), or the season when every item is one download of it ("S1").
 */
data class DownloadProgress(val percent: Int, val estimatedAt: String?, val episode: String?)

/** The progress of a copy's [items], or null when nothing is downloading. */
fun downloadProgress(items: List<DownloadItem>, type: MediaType): DownloadProgress? {
    val first = items.firstOrNull() ?: return null
    val size = first.size ?: 0.0
    val left = first.sizeLeft ?: 0.0
    val percent = if (size > 0) kotlin.math.round((size - left) / size * 100).toInt().coerceIn(0, 100) else 0
    val episode = first.episode?.takeIf { type == MediaType.TV }?.let { ep ->
        val oneDownload = items.size > 1 && first.downloadId != null && items.all { it.downloadId == first.downloadId }
        if (oneDownload) "S${ep.seasonNumber}" else "S${ep.seasonNumber}E${ep.episodeNumber}"
    }
    return DownloadProgress(percent, first.estimatedCompletionTime?.takeIf { it.isNotBlank() }, episode)
}

/**
 * Whether My Requests offers "Cancel Request" on [row]: still pending (Tom's pick, 96). My Requests holds
 * the account's own requests only, and Seerr removes a user's own pending request and refuses anything
 * else from an account without Manage Requests, which the family's never has.
 */
fun canCancel(row: RequestRow): Boolean = row.status == RequestStatus.PENDING

/** What a request's row reads once it is cancelled: gone, as Seerr removes it. */
fun withoutRequest(rows: List<RequestRow>, id: Int): List<RequestRow> = rows.filter { it.id != id }

/** The request modal's quota line, Seerr's `QuotaDisplay`: what is left of a limit, and the limit. */
data class QuotaLine(val remaining: Int, val limit: Int, val days: Int?, val seasons: Boolean) {
    /** "No movie requests left", "3 season requests left" (after Seerr's QuotaDisplay, in Hither's words). */
    val text: String
        get() {
            val type = if (seasons) "season" else "movie"
            val count = if (remaining == 0) "No" else remaining.toString()
            return "$count $type ${if (remaining == 1) "request" else "requests"} left"
        }

    /** "You may request 3 movies every 7 days." */
    val allowance: String
        get() {
            val type = when {
                seasons -> if (limit == 1) "season" else "seasons"
                else -> if (limit == 1) "movie" else "movies"
            }
            val every = when (days) {
                null, 0 -> ""
                1 -> " every day"
                else -> " every $days days"
            }
            return "You may request $limit $type$every."
        }

    /** What is left, out of 100, as Seerr's `ProgressCircle` fills it: `round(remaining / limit * 100)`. */
    val percentLeft: Int get() = if (limit <= 0) 0 else (remaining.coerceIn(0, limit) * 100f / limit).roundToInt()

    /**
     * The ring's colour, Seerr's `useHeatLevel`: green over half, yellow at half or less, red at a tenth or less;
     * with nothing left the empty ring is red too ([QuotaHeat.EMPTY]).
     */
    val heat: QuotaHeat
        get() = when {
            percentLeft == 0 -> QuotaHeat.EMPTY
            percentLeft <= 10 -> QuotaHeat.LOW
            percentLeft <= 50 -> QuotaHeat.HALF
            else -> QuotaHeat.PLENTY
        }
}

/** Seerr's heat levels for the quota's ring. */
enum class QuotaHeat { PLENTY, HALF, LOW, EMPTY }

/**
 * The quota line for a request of [type], or null where the account has no limit for it (Seerr shows
 * none then). An account with Manage Users has none, as Seerr's quota says.
 */
fun quotaLine(quota: Quota?, type: MediaType, permissions: Int?): QuotaLine? {
    if (Permission.hasAny(permissions, Permission.MANAGE_USERS)) return null
    val status: QuotaStatus = (if (type == MediaType.MOVIE) quota?.movie else quota?.tv) ?: return null
    val limit = status.limit?.takeIf { it > 0 } ?: return null
    return QuotaLine(remaining = (status.remaining ?: (limit - status.used)).coerceAtLeast(0), limit = limit, days = status.days, seasons = type == MediaType.TV)
}
