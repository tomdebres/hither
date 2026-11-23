// Adapted from SeerrTV's model/CommonModels.kt (MIT, see THIRD-PARTY.md): the library record,
// seasons, paging and credits, cut down to the fields Hither reads and made tolerant of gaps.
package com.tomdebres.hither.core.model

import kotlinx.serialization.Serializable

/** A page of TMDB-backed results: discover rows and search. */
@Serializable
data class Page<T>(
    val page: Int = 1,
    val totalPages: Int = 1,
    val totalResults: Int = 0,
    val results: List<T> = emptyList(),
)

/** Paging as the requests list reports it. */
@Serializable
data class PageInfo(
    val pages: Int = 1,
    val pageSize: Int = 0,
    val results: Int = 0,
    val page: Int = 1,
)

/**
 * What the server knows about a title: whether it is requested, on its way or in the library,
 * for the regular copy ([status]) and the 4K copy ([status4k]). Absent on a title nobody has
 * asked for.
 */
@Serializable
data class MediaInfo(
    val id: Int = 0,
    val mediaType: String? = null,
    val tmdbId: Int = 0,
    val status: Int = MediaStatus.UNKNOWN,
    val status4k: Int = MediaStatus.UNKNOWN,
    val seasons: List<MediaSeason> = emptyList(),
    val requests: List<MediaRequest> = emptyList(),
    val downloadStatus: List<DownloadItem> = emptyList(),
    val downloadStatus4k: List<DownloadItem> = emptyList(),
    val mediaUrl: String? = null,
    val mediaUrl4k: String? = null,
    val iOSPlexUrl: String? = null,
    val iOSPlexUrl4k: String? = null,
    /** When the media server's scan first found the title (ISO 8601), or null. */
    val mediaAddedAt: String? = null,
    val updatedAt: String? = null,
    /** Newer than Seerr 3.3: a request for the title is still open (read with `hideRequested`). */
    val hasActiveRequest: Boolean = false,
    /**
     * The title's issues, on its page only, for an account with an issue right (Seerr's `Media.filter`:
     * without their comments); empty otherwise.
     */
    val issues: List<Issue> = emptyList(),
) {
    fun statusFor(is4k: Boolean): Int = if (is4k) status4k else status
    fun downloadsFor(is4k: Boolean): List<DownloadItem> = if (is4k) downloadStatus4k else downloadStatus
}

/** A season's state in the library. */
@Serializable
data class MediaSeason(
    val id: Int = 0,
    val seasonNumber: Int,
    val status: Int = MediaStatus.UNKNOWN,
    val status4k: Int = MediaStatus.UNKNOWN,
) {
    fun statusFor(is4k: Boolean): Int = if (is4k) status4k else status
}

/**
 * One item the download client is fetching (Seerr's download tracker, `DownloadingItem`); its presence is
 * what makes a badge "Processing".
 */
@Serializable
data class DownloadItem(
    val title: String? = null,
    val status: String? = null,
    val size: Double? = null,
    val sizeLeft: Double? = null,
    /** Radarr's or Sonarr's own words for the time left ("01:15:00"); [estimatedCompletionTime] is the instant. */
    val timeLeft: String? = null,
    /** When it should be done (ISO 8601), or null. */
    val estimatedCompletionTime: String? = null,
    val downloadId: String? = null,
    val episode: DownloadEpisode? = null,
)

/** The episode a series' download item is. */
@Serializable
data class DownloadEpisode(
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val title: String? = null,
)

@Serializable
data class Genre(val id: Int = 0, val name: String = "")

@Serializable
data class Credits(
    val cast: List<CastMember> = emptyList(),
    val crew: List<CrewMember> = emptyList(),
)

@Serializable
data class CastMember(
    val id: Int = 0,
    val name: String = "",
    val character: String? = null,
    val profilePath: String? = null,
)

@Serializable
data class CrewMember(
    val id: Int = 0,
    val name: String = "",
    val job: String? = null,
    val department: String? = null,
    val profilePath: String? = null,
)
