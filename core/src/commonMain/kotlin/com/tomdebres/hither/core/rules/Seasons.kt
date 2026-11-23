package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.SeasonSummary
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.Permission

/**
 * The season numbers the request modal lists, in the series' own order: seasons with episodes,
 * and "Specials" (season 0) only where the server has them switched on. Seerr's web UI filters
 * the same way in `TvRequestModal` and `TvDetails`.
 */
fun requestableSeasons(seasons: List<SeasonSummary>, specialsEnabled: Boolean): List<Int> =
    seasons
        .filter { it.episodeCount != 0 && (specialsEnabled || it.seasonNumber != 0) }
        .map { it.seasonNumber }

/**
 * The seasons that cannot be asked for again, for the regular copy or the 4K one: those in a
 * request that is still live (not declined, not completed), and those the library already has
 * or is fetching. Seerr's `getAllRequestedSeasons`.
 */
fun takenSeasons(media: MediaInfo?, is4k: Boolean): Set<Int> {
    if (media == null) return emptySet()
    val requested = media.requests
        .filter { it.is4k == is4k && it.status != RequestStatus.DECLINED && it.status != RequestStatus.COMPLETED }
        .flatMap { request -> request.seasons.map { it.seasonNumber } }
    val inLibrary = media.seasons
        .filter {
            when (it.statusFor(is4k)) {
                MediaStatus.AVAILABLE, MediaStatus.PARTIALLY_AVAILABLE, MediaStatus.PROCESSING -> true
                else -> false
            }
        }
        .map { it.seasonNumber }
    return (requested + inLibrary).toSet()
}

/** Where one season stands, which is what its badge in the request modal says. */
enum class SeasonState { NOT_REQUESTED, PENDING, REQUESTED, PARTIALLY_AVAILABLE, AVAILABLE }

/**
 * A season's state as the request modal shows it. The library's word wins where it has one;
 * otherwise the season's entry in the first live request that names it decides between pending
 * and requested (Seerr's `getSeasonRequest`). A season that [takenSeasons] holds never reads
 * as "not requested", so the badge and the dimmed row cannot disagree.
 */
fun seasonState(seasonNumber: Int, media: MediaInfo?, is4k: Boolean): SeasonState {
    if (media == null) return SeasonState.NOT_REQUESTED
    when (media.seasons.firstOrNull { it.seasonNumber == seasonNumber }?.statusFor(is4k)) {
        MediaStatus.AVAILABLE -> return SeasonState.AVAILABLE
        MediaStatus.PARTIALLY_AVAILABLE -> return SeasonState.PARTIALLY_AVAILABLE
        MediaStatus.PROCESSING -> return SeasonState.REQUESTED
    }
    val entry = media.requests
        .filter { it.is4k == is4k && it.status != RequestStatus.DECLINED && it.status != RequestStatus.COMPLETED }
        .firstNotNullOfOrNull { request ->
            request.seasons.firstOrNull { it.seasonNumber == seasonNumber && it.status != RequestStatus.COMPLETED }
        }
    return when {
        entry?.status == RequestStatus.PENDING -> SeasonState.PENDING
        entry != null -> SeasonState.REQUESTED
        seasonNumber in takenSeasons(media, is4k) -> SeasonState.REQUESTED
        else -> SeasonState.NOT_REQUESTED
    }
}

/**
 * A season on a series' page (the polish 2 audit's item 13): Seerr's `TvDetails` season list, a row each, with
 * its number, how many episodes and its badges, without the episodes Seerr folds out under it.
 */
data class SeasonLine(val number: Int, val episodes: Int, val badges: List<StatusBadge>)

/**
 * Seerr's season list: newest first, the specials only where the server shows them, no season without
 * episodes; a badge for the regular copy, and one for the 4K copy where 4K is on for series and the account may
 * ask for it (Seerr's `show4k`). A season nobody has asked for has no badge.
 */
fun seasonLines(seasons: List<SeasonSummary>, media: MediaInfo?, settings: PublicSettings, permissions: Int?): List<SeasonLine> {
    val shows4k = settings.series4kEnabled &&
        Permission.hasAny(permissions, Permission.MANAGE_REQUESTS, Permission.REQUEST_4K, Permission.REQUEST_4K_TV)
    return seasons.asReversed()
        .filter { (settings.enableSpecialEpisodes || it.seasonNumber != 0) && it.episodeCount > 0 }
        .map { season ->
            val regular = seasonState(season.seasonNumber, media, is4k = false)
            val fourK = if (shows4k) seasonState(season.seasonNumber, media, is4k = true) else SeasonState.NOT_REQUESTED
            SeasonLine(
                number = season.seasonNumber,
                episodes = season.episodeCount,
                badges = listOfNotNull(
                    seasonBadge(regular).takeIf { regular != SeasonState.NOT_REQUESTED },
                    seasonBadge(fourK).copy(is4k = true).takeIf { fourK != SeasonState.NOT_REQUESTED },
                ),
            )
        }
}
