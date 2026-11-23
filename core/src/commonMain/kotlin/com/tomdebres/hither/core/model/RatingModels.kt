package com.tomdebres.hither.core.model

import kotlinx.serialization.Serializable

/**
 * Rotten Tomatoes' scores as Seerr finds them (`RTRating`, `server/api/rating/rottentomatoes.ts`): the
 * critics' (Tomatometer) and the audience's, each with Rotten Tomatoes' word for it.
 */
@Serializable
data class RtRating(
    val title: String = "",
    val year: Int? = null,
    /** "Certified Fresh", "Fresh" or "Rotten". */
    val criticsRating: String? = null,
    val criticsScore: Int? = null,
    /** "Upright" or "Spilled". */
    val audienceRating: String? = null,
    val audienceScore: Int? = null,
    val url: String? = null,
)

/** IMDb's score as Seerr finds it through Radarr's proxy (`IMDBRating`): [criticsScore] is out of 10. */
@Serializable
data class ImdbRating(
    val title: String = "",
    val url: String? = null,
    val criticsScore: Double? = null,
    val criticsScoreCount: Int? = null,
)

/** `GET /movie/{id}/ratingscombined`: either may be missing. A series has Rotten Tomatoes' only. */
@Serializable
data class Ratings(
    val rt: RtRating? = null,
    val imdb: ImdbRating? = null,
)
