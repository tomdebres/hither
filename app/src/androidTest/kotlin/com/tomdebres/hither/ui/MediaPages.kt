package com.tomdebres.hither.ui

import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.CastMember
import com.tomdebres.hither.core.model.Credits
import com.tomdebres.hither.core.model.Genre
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaSeason
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.SeasonSummary
import com.tomdebres.hither.core.model.TvDetails
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.rules.MediaPage
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.core.rules.moviePage
import com.tomdebres.hither.core.rules.tvPage

/** Pages the media page's device tests show: no pictures (nothing to fetch), a cast of [castSize]. */
internal object MediaPages {
    const val BASE = "http://seerr.test"

    /** The recorded account: REQUEST and 4K for films, approved automatically. */
    val account = User(id = 8, displayName = "TV", permissions = 67744)

    /** 4K on for films, partial requests on: as the recorded server. */
    val server = PublicSettings(applicationTitle = "Seerr", localLogin = true, movie4kEnabled = true, partialRequestsEnabled = true)

    fun film(id: Int = 7, castSize: Int = 10, media: MediaInfo? = null, permissions: Int = account.permissions!!): MediaPage = moviePage(
        MovieDetails(
            id = id,
            title = "Film $id",
            overview = "A film to read about.",
            tagline = "A tagline.",
            releaseDate = "2026-09-16",
            runtime = 94,
            status = "Released",
            genres = listOf(Genre(1, "Drama")),
            credits = Credits(cast = (1..castSize).map { CastMember(id = it, name = "Person $it", character = "Part $it") }),
            mediaInfo = media,
        ),
        BASE,
        server,
        account.copy(permissions = permissions),
    )

    /**
     * A series of six seasons (and specials, off on this server) whose season 1 is in the
     * library: "Request More", season 1 taken in the modal. [settings] can switch partial
     * requests off.
     */
    fun series(id: Int = 9, settings: PublicSettings = server, permissions: Int = account.permissions!!): MediaPage = tvPage(
        TvDetails(
            id = id,
            name = "Series $id",
            overview = "A series to read about.",
            firstAirDate = "2007-08-17",
            seasons = (0..6).map { SeasonSummary(seasonNumber = it, episodeCount = 10 + it) },
            mediaInfo = MediaInfo(
                tmdbId = id,
                status = 4,
                seasons = listOf(MediaSeason(seasonNumber = 1, status = 5)),
            ),
        ),
        BASE,
        settings,
        account.copy(permissions = permissions),
    )

    /** A film that is in the library, regular and 4K: nothing to ask for. */
    fun filmInLibrary(id: Int = 7) = film(id, media = MediaInfo(tmdbId = id, status = 5, status4k = 5))

    /** An account that may not request at all. */
    const val NO_REQUEST = Permission.REQUEST_VIEW

    fun card(id: Int = 7) = TitleCard("movie:$id", id, MediaType.MOVIE, "Film $id", "2026", poster = null, badge = null)

    fun seriesCard(id: Int = 9) = TitleCard("tv:$id", id, MediaType.TV, "Series $id", "2007", poster = null, badge = null)
}
