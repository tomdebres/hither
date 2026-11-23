// Adapted from SeerrTV's model/MediaDetailsModels.kt (MIT, see THIRD-PARTY.md): split into a
// film and a series model, cut down to what the media page shows, every field optional.
package com.tomdebres.hither.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/v1/movie/{id}`. */
@Serializable
data class MovieDetails(
    val id: Int,
    val title: String = "",
    val overview: String? = null,
    val tagline: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val releaseDate: String? = null,
    val runtime: Int? = null,
    val status: String? = null,
    val voteAverage: Double = 0.0,
    val voteCount: Int = 0,
    val genres: List<Genre> = emptyList(),
    val credits: Credits = Credits(),
    val releases: Releases = Releases(),
    val mediaInfo: MediaInfo? = null,
    /** The original title where it differs; Seerr's facts show it. */
    val originalTitle: String? = null,
    val originalLanguage: String? = null,
    val budget: Long = 0,
    val revenue: Long = 0,
    val imdbId: String? = null,
    val productionCompanies: List<Company> = emptyList(),
    val productionCountries: List<Country> = emptyList(),
    /** Trailers, teasers and clips (YouTube's), as Seerr's `mapVideos` gives them. */
    val relatedVideos: List<Video> = emptyList(),
    val keywords: List<Keyword> = emptyList(),
    /** Where the title streams or sells, by country. */
    val watchProviders: List<WatchProviders> = emptyList(),
    /** The collection the film belongs to, where TMDB names one (Seerr's `MovieDetails.collection`). */
    val collection: CollectionBrief? = null,
)

/** A film's collection as the film's details name it: TMDB's `belongs_to_collection`. */
@Serializable
data class CollectionBrief(
    val id: Int = 0,
    val name: String = "",
    val posterPath: String? = null,
    val backdropPath: String? = null,
)

/** `GET /api/v1/collection/{id}` (Seerr's `mapCollection`): the collection and its films. */
@Serializable
data class CollectionDetails(
    val id: Int = 0,
    val name: String = "",
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val parts: List<MediaResult> = emptyList(),
)

/** `GET /api/v1/tv/{id}`. */
@Serializable
data class TvDetails(
    val id: Int,
    val name: String = "",
    val overview: String? = null,
    val tagline: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val firstAirDate: String? = null,
    val status: String? = null,
    val voteAverage: Double = 0.0,
    val numberOfSeasons: Int? = null,
    val numberOfEpisodes: Int? = null,
    val voteCount: Int = 0,
    val genres: List<Genre> = emptyList(),
    val seasons: List<SeasonSummary> = emptyList(),
    val credits: Credits = Credits(),
    val contentRatings: ContentRatings = ContentRatings(),
    val mediaInfo: MediaInfo? = null,
    val originalName: String? = null,
    val originalLanguage: String? = null,
    val lastAirDate: String? = null,
    val networks: List<Company> = emptyList(),
    val productionCompanies: List<Company> = emptyList(),
    val productionCountries: List<Country> = emptyList(),
    val createdBy: List<Creator> = emptyList(),
    /** Minutes, as TMDB lists them; the first is Seerr's "Episode Runtime". */
    val episodeRunTime: List<Int> = emptyList(),
    val nextEpisodeToAir: Episode? = null,
    val relatedVideos: List<Video> = emptyList(),
    val keywords: List<Keyword> = emptyList(),
    val watchProviders: List<WatchProviders> = emptyList(),
)

/** A season as TMDB describes it: number 0 is "Specials". */
@Serializable
data class SeasonSummary(
    val id: Int = 0,
    val seasonNumber: Int,
    val name: String? = null,
    val episodeCount: Int = 0,
    val airDate: String? = null,
    val posterPath: String? = null,
)

/** A film's release dates by country, as TMDB gives them (Seerr passes them through). */
@Serializable
data class Releases(val results: List<CountryReleases> = emptyList())

@Serializable
data class CountryReleases(
    @SerialName("iso_3166_1") val country: String = "",
    @SerialName("release_dates") val releaseDates: List<ReleaseDate> = emptyList(),
)

/**
 * One release; its [certification] ("PG-13", "15") is empty where the country gave none. [type] is
 * TMDB's: 1 premiere, 2 limited theatrical, 3 theatrical, 4 digital, 5 physical, 6 TV.
 */
@Serializable
data class ReleaseDate(
    val certification: String = "",
    val type: Int = 0,
    @SerialName("release_date") val releaseDate: String? = null,
)

/** A series' content ratings by country ("TV-G" in the US). */
@Serializable
data class ContentRatings(val results: List<ContentRating> = emptyList())

@Serializable
data class ContentRating(
    @SerialName("iso_3166_1") val country: String = "",
    val rating: String = "",
)

/** A studio or a network (Seerr's `ProductionCompany`, `Network`). */
@Serializable
data class Company(
    val id: Int = 0,
    val name: String = "",
    val originCountry: String? = null,
    val logoPath: String? = null,
)

@Serializable
data class Country(
    @SerialName("iso_3166_1") val code: String = "",
    val name: String = "",
)

/** Who made a series. */
@Serializable
data class Creator(val id: Int = 0, val name: String = "")

/** A video Seerr lists for a title (`relatedVideos`): [site] "YouTube" and its [key]. */
@Serializable
data class Video(
    val site: String = "",
    val key: String = "",
    val name: String = "",
    val size: Int = 0,
    /** "Trailer", "Teaser", "Clip", "Featurette", "Opening Credits", "Behind the Scenes", "Bloopers". */
    val type: String = "",
    val url: String? = null,
)

/** A TMDB keyword: a tag on a title, and a way into a grid of titles that carry it. */
@Serializable
data class Keyword(val id: Int = 0, val name: String = "")

/** Where a title can be watched in one country (Seerr's `WatchProviders`). */
@Serializable
data class WatchProviders(
    @SerialName("iso_3166_1") val country: String = "",
    val link: String? = null,
    val buy: List<WatchProvider> = emptyList(),
    val flatrate: List<WatchProvider> = emptyList(),
)

/** A streaming service or shop (Seerr's `WatchProviderDetails`). */
@Serializable
data class WatchProvider(
    val id: Int = 0,
    val name: String = "",
    val logoPath: String? = null,
    val displayPriority: Int = 0,
)

/** An episode as the series' details name it: only its air date is read. */
@Serializable
data class Episode(val airDate: String? = null)
