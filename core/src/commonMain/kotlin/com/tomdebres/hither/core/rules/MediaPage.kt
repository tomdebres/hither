package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.CastMember
import com.tomdebres.hither.core.model.Keyword
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.Quota
import com.tomdebres.hither.core.model.SeasonSummary
import com.tomdebres.hither.core.model.TvDetails
import com.tomdebres.hither.core.model.User

/**
 * One of the media page's request buttons: Seerr's `RequestButton` words for [action] on the
 * regular copy or, with [is4k], the 4K one.
 */
data class RequestButton(val action: RequestAction, val is4k: Boolean) {
    val text: String
        get() = when (action) {
            RequestAction.REQUEST -> if (is4k) "Request in 4K" else "Request"
            RequestAction.REQUEST_MORE -> if (is4k) "Request More in 4K" else "Request More"
            RequestAction.NONE -> ""
        }
}

/** The buttons [options] gives, the regular copy's first (2.8: Request first, focused on arrival). */
fun requestButtons(options: RequestOptions): List<RequestButton> =
    listOf(RequestButton(options.standard, is4k = false), RequestButton(options.fourK, is4k = true))
        .filter { it.action != RequestAction.NONE }

/** A person on the cast row: Seerr's `PersonCard`. [key] is unique within the row; [personId] opens their page (M6). */
data class CastCard(val key: String, val name: String, val character: String?, val photo: String?, val personId: Int = 0)

/**
 * The collection a film belongs to, as Seerr's collection block at the top of the facts column draws it: its
 * backdrop, its name, and "View", opening the collection's films (the polish 2 audit's item 12).
 */
data class CollectionCard(val id: Int, val name: String, val backdrop: String?)

/** A fact under the overview: Seerr's facts panel, name and value; [kind] says how the app writes the value. */
data class Fact(val name: String, val value: String, val kind: FactKind = FactKind.TEXT)

/**
 * Everything the media page draws for one title, worked out from its details, the server's
 * switches and the user's permissions (`docs/design/seerr-reference.md`, 2.8). Built by
 * [moviePage] or [tvPage].
 */
data class MediaPage(
    val type: MediaType,
    val tmdbId: Int,
    val title: String,
    /** The four-digit year beside the title, or null. */
    val year: String?,
    val poster: String?,
    val backdrop: String?,
    /** The rating in its outlined box ("PG-13", "TV-G"), for the server's region; null for none. */
    val certification: String?,
    /** After the certification: the runtime or the season count, then the genres. */
    val attributes: List<String>,
    val tagline: String?,
    val overview: String?,
    /** Seerr's facts panel, in its order ([movieFacts], [tvFacts]); dates ISO, `yyyy-mm-dd`. */
    val facts: List<Fact>,
    /** Up to six of the crew under the overview (Seerr's order). */
    val crew: List<CrewLine> = emptyList(),
    /** "Currently Streaming On", for the account's region. */
    val streaming: List<StreamingService> = emptyList(),
    /** The trailer's YouTube id, for "Watch Trailer"; null for none. */
    val trailer: String? = null,
    /** The title's keywords, as tags that open their titles. */
    val keywords: List<Keyword> = emptyList(),
    /** TMDB's user score: the average (out of 10) and how many voted. */
    val voteAverage: Double = 0.0,
    val voteCount: Int = 0,
    /** Above the title: the regular copy's badge, and the 4K copy's where Seerr shows it. */
    val badges: List<StatusBadge>,
    val buttons: List<RequestButton>,
    /** "Play on Plex" for each copy Plex has and the user may play (Seerr's play button), before the request buttons. */
    val plays: List<PlayAction> = emptyList(),
    val options: RequestOptions,
    val cast: List<CastCard>,
    /** The series' seasons as TMDB lists them, for the request modal; empty for a film. */
    val seasons: List<SeasonSummary>,
    /** The title's library record, as the page was read; null when nobody has asked for it. */
    val media: MediaInfo?,
    /** The server's switches and the user the page was worked out for. */
    val settings: PublicSettings,
    val user: User,
    /** The account's request limits, read with the page (M6, DECISIONS 101); null where they could not be read. */
    val quota: Quota? = null,
    /** A film's collection, for the card at the top of the facts column; null for none, and for a series. */
    val collection: CollectionCard? = null,
) {
    /** The series' seasons as its page lists them (the polish 2 audit's item 13), from the record as it is now. */
    val seasonLines: List<SeasonLine> get() = seasonLines(seasons, media, settings, user.permissions)

    /** The badge a card of this title draws: the regular copy's, as on Discover. */
    val cardBadge: StatusBadge? get() = cardBadge(media)

    /**
     * The page around another library record (the one a request has just made): the badges,
     * the request options and the buttons worked out again; everything else as it was.
     */
    fun withMedia(media: MediaInfo?): MediaPage {
        val options = requestOptions(type, settings, user, media, seasons)
        return copy(
            media = media,
            badges = mediaBadges(type, media, settings, user.permissions),
            options = options,
            buttons = requestButtons(options),
            plays = playActions(type, media, settings, user.permissions),
        )
    }
}

/** Cast cards on the page, at most: Seerr's cast slider stops at 20. */
const val CAST_LIMIT = 20

/** The media page for a film. */
fun moviePage(details: MovieDetails, images: ImageSource, settings: PublicSettings, user: User): MediaPage {
    val type = MediaType.MOVIE
    val region = region(settings)
    val certification = details.releases.results.firstOrNull { it.country == region }
        ?.releaseDates?.firstOrNull { it.certification.isNotBlank() }?.certification
    val runtime = details.runtime?.takeIf { it > 0 }?.let { "$it minutes" }
    val options = requestOptions(type, settings, user, details.mediaInfo)
    return MediaPage(
        type = type,
        tmdbId = details.id,
        title = details.title,
        year = year(details.releaseDate),
        poster = imageUrl(images, details.posterPath, TmdbImage.POSTER),
        backdrop = imageUrl(images, details.backdropPath, TmdbImage.BACKDROP),
        certification = certification,
        attributes = listOfNotNull(runtime, genres(details.genres.map { it.name })),
        tagline = details.tagline.text(),
        overview = details.overview.text(),
        facts = movieFacts(details, settings),
        crew = crewLines(details.credits.crew),
        streaming = streamingOn(details.watchProviders, settings, user, images),
        trailer = trailerKey(details.relatedVideos),
        keywords = keywordTags(details.keywords),
        voteAverage = details.voteAverage,
        voteCount = details.voteCount,
        badges = mediaBadges(type, details.mediaInfo, settings, user.permissions),
        buttons = requestButtons(options),
        plays = playActions(type, details.mediaInfo, settings, user.permissions),
        options = options,
        cast = castCards(details.credits.cast, images),
        seasons = emptyList(),
        media = details.mediaInfo,
        settings = settings,
        user = user,
        collection = details.collection?.takeIf { it.id > 0 && it.name.isNotBlank() }
            ?.let { CollectionCard(it.id, it.name.trim(), imageUrl(images, it.backdropPath, TmdbImage.COLLECTION)) },
    )
}

/** The media page for a series. */
fun tvPage(details: TvDetails, images: ImageSource, settings: PublicSettings, user: User): MediaPage {
    val type = MediaType.TV
    val region = region(settings)
    val certification = details.contentRatings.results.firstOrNull { it.country == region }?.rating.text()
    // Seerr's count leaves out the specials and seasons with no episodes yet.
    val count = details.seasons.count { it.seasonNumber != 0 && it.episodeCount != 0 }
    val seasons = when {
        count == 1 -> "1 Season"
        count > 1 -> "$count Seasons"
        else -> null
    }
    val options = requestOptions(type, settings, user, details.mediaInfo, details.seasons)
    return MediaPage(
        type = type,
        tmdbId = details.id,
        title = details.name,
        year = year(details.firstAirDate),
        poster = imageUrl(images, details.posterPath, TmdbImage.POSTER),
        backdrop = imageUrl(images, details.backdropPath, TmdbImage.BACKDROP),
        certification = certification,
        attributes = listOfNotNull(seasons, genres(details.genres.map { it.name })),
        tagline = details.tagline.text(),
        overview = details.overview.text(),
        facts = tvFacts(details),
        crew = crewLines(details.credits.crew),
        streaming = streamingOn(details.watchProviders, settings, user, images),
        trailer = trailerKey(details.relatedVideos),
        keywords = keywordTags(details.keywords),
        voteAverage = details.voteAverage,
        voteCount = details.voteCount,
        badges = mediaBadges(type, details.mediaInfo, settings, user.permissions),
        buttons = requestButtons(options),
        plays = playActions(type, details.mediaInfo, settings, user.permissions),
        options = options,
        cast = castCards(details.credits.cast, images),
        seasons = details.seasons,
        media = details.mediaInfo,
        settings = settings,
        user = user,
    )
}

/**
 * The status badges above the title: the regular copy's, and the 4K copy's when 4K is on for
 * the type and the user may ask for it (Seerr's `MovieDetails` and `TvDetails`). Either is left
 * out while its copy's status is unknown.
 */
fun mediaBadges(type: MediaType, media: MediaInfo?, settings: PublicSettings, permissions: Int?): List<StatusBadge> {
    val regular = mediaBadge(media?.status, inProgress = media?.downloadStatus.orEmpty().isNotEmpty())
        ?.let { badge -> if (badge.inProgress) badge.copy(progress = downloadProgress(media?.downloadStatus.orEmpty(), type)) else badge }
    val enabled4k = if (type == MediaType.MOVIE) settings.movie4kEnabled else settings.series4kEnabled
    val shows4k = enabled4k && Permission.hasAny(
        permissions,
        Permission.MANAGE_REQUESTS,
        Permission.REQUEST_4K,
        if (type == MediaType.MOVIE) Permission.REQUEST_4K_MOVIE else Permission.REQUEST_4K_TV,
    )
    val fourK = if (shows4k) {
        mediaBadge(media?.status4k, is4k = true, inProgress = media?.downloadStatus4k.orEmpty().isNotEmpty())
            ?.let { badge -> if (badge.inProgress) badge.copy(progress = downloadProgress(media?.downloadStatus4k.orEmpty(), type)) else badge }
    } else {
        null
    }
    return listOfNotNull(regular, fourK)
}

/**
 * The badge on a title's card: the regular copy's status, never the 4K one, and "in
 * progress" when the download client has items for it (Seerr's `TitleCard`).
 */
fun cardBadge(media: MediaInfo?): StatusBadge? =
    mediaBadge(media?.status, inProgress = media?.downloadStatus.orEmpty().isNotEmpty())

/** The country release information is read for: the server's Discover region, else the US (Seerr's default). */
internal fun region(settings: PublicSettings): String = settings.discoverRegion.ifBlank { "US" }

private fun year(date: String?): String? = date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

private fun genres(names: List<String>): String? = names.filter { it.isNotBlank() }.joinToString(", ").ifEmpty { null }

private fun String?.text(): String? = this?.trim()?.ifEmpty { null }

private fun castCards(cast: List<CastMember>, images: ImageSource): List<CastCard> =
    cast.take(CAST_LIMIT).mapIndexed { index, person ->
        // One person can play two parts; the row keys by place as well as by person.
        CastCard(
            key = "cast:$index:${person.id}",
            name = person.name,
            character = person.character.text(),
            photo = imageUrl(images, person.profilePath, TmdbImage.PROFILE),
            personId = person.id,
        )
    }
