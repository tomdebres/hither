package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.TvDetails

/**
 * One row of My Requests: Seerr's `RequestItem`, worked out from the request as the requests
 * list gives it (`docs/design/seerr-reference.md`, 1.11 and 2.10). The list carries the
 * request and the title's library record but not the title's name or pictures; those come
 * from the title's own details ([RequestTitle]), as Seerr's web asks for them row by row.
 */
data class RequestRow(
    /** Unique in the list: the request's id. */
    val key: String,
    val id: Int,
    val type: MediaType,
    val tmdbId: Int,
    /** Seerr's status chip for the request ([requestBadge]): "Requested", "4K Pending", "Declined". */
    val badge: StatusBadge?,
    val is4k: Boolean,
    /** A series request's seasons as the row shows them ([seasonLabels]); empty for a film. */
    val seasons: List<String>,
    /** When it was asked for, as the server wrote it (ISO 8601); null when it sent none. */
    val requestedAt: String?,
    /** The title's library record as the list carries it. */
    val media: MediaInfo?,
    /** Who asked for it: the all-users view names them (M5); My Requests does not (always this account). */
    val requester: String? = null,
    /** The request's own status ([com.tomdebres.hither.core.model.RequestStatus]). */
    val status: Int = com.tomdebres.hither.core.model.RequestStatus.PENDING,
    /**
     * Who last moved it on, for Seerr's "Modified {when} by {who}" (`RequestItem`, the polish 2 audit's items 23 and
     * 32): an approver, or the requester for a request their own automatic approval approved; null while nobody has.
     */
    val modifiedBy: String? = null,
    /** When, as the server wrote it ([MediaRequest.updatedAt]); null without [modifiedBy]. */
    val modifiedAt: String? = null,
) {
    /** The key of the title's details, shared by every request for the same title. */
    val titleKey: String get() = "${type.api}:$tmdbId"
}

/** What a row needs from the title's details: its name, year and pictures. */
data class RequestTitle(
    val title: String,
    val year: String?,
    val poster: String?,
    val backdrop: String?,
)

/**
 * The rows for a page of the requests list, in the server's order (newest first, as asked).
 * A request whose media type this build does not know, or that carries no library record to
 * say which title it is, has no row: there would be nothing to open.
 */
fun requestRows(requests: List<MediaRequest>): List<RequestRow> = requests.mapNotNull { request ->
    val type = MediaType.fromApi(request.type) ?: MediaType.fromApi(request.media?.mediaType) ?: return@mapNotNull null
    val media = request.media ?: return@mapNotNull null
    if (media.tmdbId <= 0) return@mapNotNull null
    RequestRow(
        key = "request:${request.id}",
        id = request.id,
        type = type,
        tmdbId = media.tmdbId,
        badge = requestBadge(request),
        is4k = request.is4k,
        seasons = if (type == MediaType.TV) seasonLabels(request.seasons.map { it.seasonNumber }) else emptyList(),
        requestedAt = request.createdAt?.takeIf { it.isNotBlank() },
        media = media,
        requester = request.requestedBy?.let { it.displayName.ifBlank { it.email.orEmpty() } }?.takeIf { it.isNotBlank() },
        status = request.status,
        modifiedBy = request.modifiedBy?.let { it.displayName.ifBlank { it.email.orEmpty() } }?.takeIf { it.isNotBlank() },
        modifiedAt = request.updatedAt?.takeIf { it.isNotBlank() && request.modifiedBy != null },
    )
}

/**
 * The list after a further page: its rows that are not already there, appended. The list can
 * move while it is read (a new request pushes every later one down a place), so a request can
 * come back on the next page; it is shown once.
 */
fun appendRequests(rows: List<RequestRow>, page: List<MediaRequest>): List<RequestRow> {
    val keys = rows.mapTo(HashSet()) { it.key }
    val added = requestRows(page).filter { keys.add(it.key) }
    return if (added.isEmpty()) rows else rows + added
}

/** Season badges a row shows before it says how many more there are (2.10). */
const val SEASON_BADGES = 6

/**
 * A series request's seasons, as the row's badges: in order, Seerr's "Specials" for season 0,
 * at most [SEASON_BADGES] of them and then "+n" for the rest.
 */
fun seasonLabels(seasons: List<Int>, max: Int = SEASON_BADGES): List<String> {
    val labels = seasons.distinct().sorted().map { if (it == 0) "Specials" else it.toString() }
    if (labels.size <= max) return labels
    return labels.take(max) + "+${labels.size - max}"
}

/** A film's name, year and pictures for its request rows. */
fun requestTitle(details: MovieDetails, images: ImageSource): RequestTitle = RequestTitle(
    title = details.title,
    year = year(details.releaseDate),
    poster = imageUrl(images, details.posterPath, TmdbImage.POSTER),
    backdrop = imageUrl(images, details.backdropPath, TmdbImage.BACKDROP),
)

/** A series' name, year and pictures for its request rows. */
fun requestTitle(details: TvDetails, images: ImageSource): RequestTitle = RequestTitle(
    title = details.name,
    year = year(details.firstAirDate),
    poster = imageUrl(images, details.posterPath, TmdbImage.POSTER),
    backdrop = imageUrl(images, details.backdropPath, TmdbImage.BACKDROP),
)

/**
 * The card a row opens the title's page with, as a Discover card would: its badge is the
 * regular copy's library status ([cardBadge]); the title stands in until the page has read its
 * own, and is empty while the row has not got one either.
 */
fun requestCard(row: RequestRow, title: RequestTitle?): TitleCard = TitleCard(
    key = row.titleKey,
    tmdbId = row.tmdbId,
    type = row.type,
    title = title?.title.orEmpty(),
    year = title?.year,
    poster = title?.poster,
    badge = cardBadge(row.media),
)

private fun year(date: String?): String? = date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
