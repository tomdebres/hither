package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.RequestBody
import com.tomdebres.hither.core.model.SeasonSummary

/**
 * The body for a film request, or null when [options] has no such button: 4K is never sent
 * where the server has it off or the user may not ask for it.
 */
fun movieRequestBody(tmdbId: Int, is4k: Boolean, options: RequestOptions): RequestBody? {
    if (options.actionFor(is4k) == RequestAction.NONE) return null
    return RequestBody(mediaType = MediaType.MOVIE.api, mediaId = tmdbId, is4k = is4k)
}

/**
 * The body for a series request, or null when there is nothing that may be sent.
 *
 * With partial requests on, the seasons are exactly the ones [selected] in the picker, less
 * any that are not on offer (unknown numbers, specials where they are off, seasons already
 * taken), in ascending order; an empty choice sends nothing. With partial requests off the
 * picker has no say: the request is for every season still to be had, as Seerr's own modal
 * sends it.
 */
fun tvRequestBody(
    tmdbId: Int,
    is4k: Boolean,
    selected: Set<Int>,
    seasons: List<SeasonSummary>,
    media: MediaInfo?,
    settings: PublicSettings,
    options: RequestOptions,
): RequestBody? {
    if (options.actionFor(is4k) == RequestAction.NONE) return null
    val open = requestableSeasons(seasons, settings.enableSpecialEpisodes) - takenSeasons(media, is4k)
    val chosen = if (settings.partialRequestsEnabled) open.filter { it in selected } else open
    if (chosen.isEmpty()) return null
    return RequestBody(mediaType = MediaType.TV.api, mediaId = tmdbId, is4k = is4k, seasons = chosen.sorted())
}
