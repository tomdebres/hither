package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.Issue
import com.tomdebres.hither.core.model.IssueBody
import com.tomdebres.hither.core.model.IssueStatus
import com.tomdebres.hither.core.model.IssueType
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MediaType
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

// Seerr's issues (M6, DECISIONS 106; audit item 16): its "Report an Issue" on a title in the library, and its
// Issues list, both only with the rights read live. From Seerr's `CreateIssueModal`, `IssueList` and
// `IssueItem` at 3f7d5c3.

/** Seerr's issue types, in its order and words (`IssueModal/constants.ts`). */
data class IssueKind(val type: Int, val label: String)

val ISSUE_KINDS = listOf(
    IssueKind(IssueType.VIDEO, "Video"),
    IssueKind(IssueType.AUDIO, "Audio"),
    IssueKind(IssueType.SUBTITLES, "Subtitle"),
    IssueKind(IssueType.OTHER, "Other"),
)

/** A type's word; Seerr's "Unknown" for a type it does not know. */
fun issueLabel(type: Int): String = ISSUE_KINDS.firstOrNull { it.type == type }?.label ?: "Unknown"

/**
 * Whether the title's page offers "Report an Issue" (Seerr's `MovieDetails`, `TvDetails`): the account holds
 * Create Issues or Manage Issues, and a copy is in the library (a series partly will do), the 4K copy where 4K
 * is on for the type and the account may ask for it. A record is needed: an issue is made on it.
 */
fun canReportIssue(page: MediaPage): Boolean {
    val media = page.media ?: return false
    if (media.id <= 0) return false
    if (!Permission.hasAny(page.user.permissions, Permission.CREATE_ISSUES, Permission.MANAGE_ISSUES)) return false
    val inLibrary = { status: Int -> status == MediaStatus.AVAILABLE || (page.type == MediaType.TV && status == MediaStatus.PARTIALLY_AVAILABLE) }
    return inLibrary(media.status) || (fourKAllowed(page) && inLibrary(media.status4k))
}

private fun fourKAllowed(page: MediaPage): Boolean {
    val enabled = if (page.type == MediaType.MOVIE) page.settings.movie4kEnabled else page.settings.series4kEnabled
    return enabled && Permission.hasAny(
        page.user.permissions,
        Permission.REQUEST_4K,
        if (page.type == MediaType.MOVIE) Permission.REQUEST_4K_MOVIE else Permission.REQUEST_4K_TV,
    )
}

/** The seasons an issue may name (Seerr's `availableSeasons`): those in the library, either copy as above. */
fun issueSeasons(page: MediaPage): List<Int> {
    if (page.type != MediaType.TV) return emptyList()
    val media = page.media ?: return emptyList()
    val inLibrary = { status: Int -> status == MediaStatus.AVAILABLE || status == MediaStatus.PARTIALLY_AVAILABLE }
    return media.seasons
        .filter { inLibrary(it.status) || (fourKAllowed(page) && inLibrary(it.status4k)) }
        .map { it.seasonNumber }
        .distinct()
        .sorted()
}

/** How many episodes [season] has, as TMDB counts them (Seerr's episode list); 0 when unknown. */
fun issueEpisodes(page: MediaPage, season: Int): Int = page.seasons.firstOrNull { it.seasonNumber == season }?.episodeCount ?: 0

/** What the issue form holds. [season] and [episode]: 0 for all. */
data class IssueDraft(val type: Int, val season: Int, val episode: Int = 0, val message: String = "")

/** A new form, as Seerr opens it: Video, and the one season when only one is in the library, else all. */
fun issueDraft(page: MediaPage): IssueDraft {
    val seasons = issueSeasons(page)
    return IssueDraft(type = ISSUE_KINDS.first().type, season = if (seasons.size == 1) seasons.single() else 0)
}

/**
 * The body to send, or null while the form cannot be sent: Seerr requires words ("You must provide a
 * description"). An episode is sent only with a season (Seerr's `problemSeason > 0 ? problemEpisode : 0`).
 */
fun issueBody(page: MediaPage, draft: IssueDraft): IssueBody? {
    val mediaId = page.media?.id?.takeIf { it > 0 } ?: return null
    val message = draft.message.trim().ifEmpty { return null }
    return IssueBody(
        issueType = draft.type,
        message = message,
        mediaId = mediaId,
        problemSeason = draft.season,
        problemEpisode = if (draft.season > 0) draft.episode else 0,
    )
}

/** Whether the account sees Seerr's Issues page (its sidebar): any issue right. */
fun seesIssues(permissions: Int?): Boolean =
    Permission.hasAny(permissions, Permission.MANAGE_ISSUES, Permission.VIEW_ISSUES, Permission.CREATE_ISSUES)

/** Seerr's issue list filters, its default first. */
enum class IssueFilter(val api: String, val label: String) {
    OPEN("open", "Open"),
    RESOLVED("resolved", "Resolved"),
    ALL("all", "All"),
}

/** One row of the Issues page (Seerr's `IssueItem`). */
data class IssueRow(
    val key: String,
    val id: Int,
    val type: MediaType?,
    val tmdbId: Int,
    /** "Video", "Audio", ... */
    val kind: String,
    /** "Open" (Seerr's warning badge) or "Resolved" (success). */
    val status: String,
    val open: Boolean,
    /**
     * For a series, Seerr's line under the report: "Season" and its number, or "Seasons" and "ALL", then
     * "Episode" and its number, or "Episodes" and "ALL", each value a default badge (the polish 2 audit's item
     * 16); empty for a film.
     */
    val problem: List<IssueProblem>,
    val by: String?,
    val createdAt: String?,
    /**
     * The reporter's words, the issue's first comment, where the list carries it. `reportText` in Swift,
     * where every object already has a `description`.
     */
    @OptIn(ExperimentalObjCName::class)
    @ObjCName("reportText")
    val description: String?,
) {
    val titleKey: String? get() = type?.let { "${it.api}:$tmdbId" }
}

/** A field of an issue's row: Seerr's name ("Season") and its badge's value ("1", "ALL"). */
data class IssueProblem(val name: String, val value: String)

fun issueRows(issues: List<Issue>): List<IssueRow> = issues.distinctBy { it.id }.map { issue ->
    val media = issue.media
    val type = when (media?.mediaType) {
        "movie" -> MediaType.MOVIE
        "tv" -> MediaType.TV
        else -> null
    }
    val problem = if (type == MediaType.TV) {
        listOfNotNull(
            if (issue.problemSeason > 0) IssueProblem("Season", "${issue.problemSeason}") else IssueProblem("Seasons", "ALL"),
            when {
                issue.problemSeason <= 0 -> null
                issue.problemEpisode > 0 -> IssueProblem("Episode", "${issue.problemEpisode}")
                else -> IssueProblem("Episodes", "ALL")
            },
        )
    } else {
        emptyList()
    }
    IssueRow(
        key = "issue:${issue.id}",
        id = issue.id,
        type = type,
        tmdbId = media?.tmdbId ?: 0,
        kind = issueLabel(issue.issueType),
        status = if (issue.status == IssueStatus.RESOLVED) "Resolved" else "Open",
        open = issue.status != IssueStatus.RESOLVED,
        problem = problem,
        by = issue.createdBy?.let { user -> user.displayName.takeIf { it.isNotBlank() } ?: user.email },
        createdAt = issue.createdAt,
        description = issue.comments.firstOrNull()?.message?.trim()?.ifEmpty { null },
    )
}
