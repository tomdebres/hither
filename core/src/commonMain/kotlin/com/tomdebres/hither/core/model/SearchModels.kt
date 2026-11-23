// Adapted from SeerrTV's model/SearchResponse.kt (MIT, see THIRD-PARTY.md): its Movie, TV and
// Person results folded into one tolerant shape, since discover rows and search share it.
package com.tomdebres.hither.core.model

import kotlinx.serialization.Serializable

/**
 * One result in a discover row or a search page. Films carry [title] and [releaseDate],
 * series [name] and [firstAirDate]; search also returns people ([mediaType] "person"), which
 * have neither a poster nor a request and are for the caller to drop.
 */
@Serializable
data class MediaResult(
    val id: Int,
    val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val voteAverage: Double = 0.0,
    val genreIds: List<Int> = emptyList(),
    val originalLanguage: String? = null,
    val mediaInfo: MediaInfo? = null,
    /** A person's picture (people only). */
    val profilePath: String? = null,
    /** What a person is known for (people only): a few of their titles. */
    val knownFor: List<KnownFor> = emptyList(),
) {
    /** A person in search results, rather than a title. */
    val isPerson: Boolean get() = mediaType == "person"

    /** Film or series, or null for a person or anything newer than this build. */
    val type: MediaType? get() = MediaType.fromApi(mediaType)

    val displayTitle: String get() = title ?: name ?: ""

    /** The four-digit year the card shows, or null when the date is missing or malformed. */
    val year: String?
        get() = (releaseDate ?: firstAirDate)?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
}

/** One of the titles a person in search results is known for. */
@Serializable
data class KnownFor(
    val id: Int = 0,
    val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
) {
    val displayTitle: String get() = title ?: name ?: ""
}
