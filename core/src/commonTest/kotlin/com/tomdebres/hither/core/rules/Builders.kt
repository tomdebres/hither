package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaSeason
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.RequestSeason
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.SeasonSummary
import com.tomdebres.hither.core.model.User

/** Small builders the rule tests share, so each test states only what it is about. */
internal const val ME = 8
internal const val SOMEONE_ELSE = 1

internal fun user(permissions: Int? = Permission.REQUEST, id: Int = ME) = User(id = id, permissions = permissions)

internal fun settings(
    movie4k: Boolean = false,
    series4k: Boolean = false,
    partial: Boolean = true,
    specials: Boolean = false,
) = PublicSettings(
    movie4kEnabled = movie4k,
    series4kEnabled = series4k,
    partialRequestsEnabled = partial,
    enableSpecialEpisodes = specials,
)

/** Seasons 0 (specials) to [count], each with episodes, plus an unaired season with none. */
internal fun seasons(count: Int = 3) =
    (0..count).map { SeasonSummary(seasonNumber = it, episodeCount = 8) } +
        SeasonSummary(seasonNumber = count + 1, episodeCount = 0)

internal fun media(
    status: Int = MediaStatus.UNKNOWN,
    status4k: Int = MediaStatus.UNKNOWN,
    seasons: List<MediaSeason> = emptyList(),
    requests: List<MediaRequest> = emptyList(),
) = MediaInfo(status = status, status4k = status4k, seasons = seasons, requests = requests)

internal fun request(
    status: Int = RequestStatus.PENDING,
    seasons: List<Int> = emptyList(),
    is4k: Boolean = false,
    by: Int = ME,
    seasonStatus: Int = status,
) = MediaRequest(
    status = status,
    is4k = is4k,
    seasons = seasons.map { RequestSeason(seasonNumber = it, status = seasonStatus) },
    requestedBy = User(id = by),
)
