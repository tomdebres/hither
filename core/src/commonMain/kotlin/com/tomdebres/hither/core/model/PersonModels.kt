package com.tomdebres.hither.core.model

import kotlinx.serialization.Serializable

/** `GET /person/{id}` (Seerr's `PersonDetails`). */
@Serializable
data class PersonDetails(
    val id: Int,
    val name: String = "",
    val birthday: String? = null,
    val deathday: String? = null,
    val knownForDepartment: String? = null,
    val alsoKnownAs: List<String> = emptyList(),
    val biography: String? = null,
    val placeOfBirth: String? = null,
    val profilePath: String? = null,
)

/** `GET /person/{id}/combined_credits`: what the person appeared in ([cast]) and worked on ([crew]). */
@Serializable
data class PersonCredits(
    val id: Int = 0,
    val cast: List<PersonCredit> = emptyList(),
    val crew: List<PersonCredit> = emptyList(),
)

/**
 * One credit (Seerr's `PersonCreditCast` / `PersonCreditCrew`): a film or series with the person's
 * [character] or [job], and the title's library record.
 */
@Serializable
data class PersonCredit(
    val id: Int,
    val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val overview: String? = null,
    val popularity: Double = 0.0,
    val voteCount: Int = 0,
    val voteAverage: Double = 0.0,
    val character: String? = null,
    val job: String? = null,
    val department: String? = null,
    val adult: Boolean = false,
    val mediaInfo: MediaInfo? = null,
) {
    /** The credit as a discover or search result, for a TitleCard. */
    fun asResult(): MediaResult = MediaResult(
        id = id, mediaType = mediaType, title = title, name = name, overview = overview, posterPath = posterPath,
        backdropPath = backdropPath, releaseDate = releaseDate, firstAirDate = firstAirDate, voteAverage = voteAverage,
        mediaInfo = mediaInfo,
    )
}
