package com.tomdebres.hither.core.model

import kotlinx.serialization.Serializable

/**
 * A problem reported on a title in the library (Seerr's `Issue`, `server/entity/Issue.ts`). On a title's
 * page it comes without [comments]; from `GET /issue` and `GET /issue/{id}` with them, the first being
 * what the reporter wrote.
 */
@Serializable
data class Issue(
    val id: Int = 0,
    val issueType: Int = IssueType.OTHER,
    val status: Int = IssueStatus.OPEN,
    /** 0: every season (or a film). */
    val problemSeason: Int = 0,
    /** 0: every episode. */
    val problemEpisode: Int = 0,
    val media: MediaInfo? = null,
    val createdBy: User? = null,
    val comments: List<IssueComment> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class IssueComment(
    val id: Int = 0,
    val message: String = "",
    val user: User? = null,
    val createdAt: String? = null,
)

/** `GET /issue`. */
@Serializable
data class IssuePage(
    val pageInfo: PageInfo = PageInfo(),
    val results: List<Issue> = emptyList(),
)

/**
 * The body of `POST /issue`: the type, the title by its library record's id ([mediaId] is `MediaInfo.id`,
 * not TMDB's), the words, and for a series the season and episode (0 for all).
 */
@Serializable
data class IssueBody(
    val issueType: Int,
    val message: String,
    val mediaId: Int,
    val problemSeason: Int = 0,
    val problemEpisode: Int = 0,
)

/** Seerr's `IssueType` (`server/constants/issue.ts`). */
object IssueType {
    const val VIDEO = 1
    const val AUDIO = 2
    const val SUBTITLES = 3
    const val OTHER = 4
}

/** Seerr's `IssueStatus`. */
object IssueStatus {
    const val OPEN = 1
    const val RESOLVED = 2
}
