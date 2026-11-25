package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Fixtures
import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.Issue
import com.tomdebres.hither.core.model.IssueBody
import com.tomdebres.hither.core.model.IssueComment
import com.tomdebres.hither.core.model.IssueStatus
import com.tomdebres.hither.core.model.IssueType
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaSeason
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.TvDetails
import com.tomdebres.hither.core.model.User
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/** Seerr's issues (M6, DECISIONS 106), against Seerr at 3f7d5c3. */
class IssuesTest {
    private val base = "http://seerr.test:5055"
    private val images = ImageSource(base, cacheImages = true, tmdbImages = ImageSource.TMDB_IMAGES)
    private val recorded = Fixtures.read<PublicSettings>("settings-public")
    private val reporter = User(id = 8, permissions = Permission.REQUEST + Permission.CREATE_ISSUES)
    private val family = User(id = 8, permissions = 67744)

    private fun film(status: Int, status4k: Int = MediaStatus.UNKNOWN, user: User = reporter, settings: PublicSettings = recorded) =
        moviePage(Fixtures.read<MovieDetails>("movie").copy(mediaInfo = MediaInfo(id = 41, tmdbId = 1, status = status, status4k = status4k)), images, settings, user)

    private val series = tvPage(Fixtures.read<TvDetails>("tv-in-library"), images, recorded, reporter)

    @Test
    fun `Report an Issue shows for a title in the library - with Create or Manage Issues only`() {
        assertTrue(canReportIssue(film(MediaStatus.AVAILABLE)))
        assertFalse(canReportIssue(film(MediaStatus.PROCESSING)), "not in the library")
        assertFalse(canReportIssue(film(MediaStatus.PARTIALLY_AVAILABLE)), "a film partly in is not in")
        assertFalse(canReportIssue(film(MediaStatus.AVAILABLE, user = family)), "the TV account holds no issue right")
        assertTrue(canReportIssue(film(MediaStatus.AVAILABLE, user = User(id = 8, permissions = Permission.MANAGE_ISSUES))))
        assertFalse(canReportIssue(moviePage(Fixtures.read<MovieDetails>("movie"), images, recorded, reporter)), "no record, nothing to report on")
        // the 4K copy counts where 4K is on for films and the account may ask for it
        val fourK = User(id = 8, permissions = Permission.CREATE_ISSUES + Permission.REQUEST_4K_MOVIE)
        assertTrue(canReportIssue(film(MediaStatus.UNKNOWN, MediaStatus.AVAILABLE, user = fourK)))
        assertFalse(canReportIssue(film(MediaStatus.UNKNOWN, MediaStatus.AVAILABLE)))
        assertTrue(canReportIssue(series), "a series partly in")
    }

    @Test
    fun `a series names its seasons in the library - the one season when it is alone - and their episodes`() {
        assertEquals(listOf(1), issueSeasons(series))
        assertEquals(IssueDraft(IssueType.VIDEO, season = 1), issueDraft(series))
        val two = series.copy(media = series.media!!.copy(seasons = listOf(MediaSeason(seasonNumber = 2, status = MediaStatus.AVAILABLE), MediaSeason(seasonNumber = 1, status = MediaStatus.PARTIALLY_AVAILABLE), MediaSeason(seasonNumber = 3, status = MediaStatus.PROCESSING))))
        assertEquals(listOf(1, 2), issueSeasons(two))
        assertEquals(0, issueDraft(two).season, "two seasons: all, as Seerr opens")
        assertEquals(series.seasons.first { it.seasonNumber == 1 }.episodeCount, issueEpisodes(series, 1))
        assertEquals(0, issueEpisodes(series, 99))
        assertEquals(emptyList<Int>(), issueSeasons(film(MediaStatus.AVAILABLE)))
        assertEquals(listOf("Video", "Audio", "Subtitle", "Other"), ISSUE_KINDS.map { it.label })
        assertEquals("Unknown", issueLabel(9))
    }

    @Test
    fun `the body needs words and a record - and sends an episode only with a season`() {
        val page = film(MediaStatus.AVAILABLE)
        assertNull(issueBody(page, IssueDraft(IssueType.AUDIO, 0, message = "  ")))
        assertEquals(IssueBody(IssueType.AUDIO, "Out of sync.", mediaId = 41), issueBody(page, IssueDraft(IssueType.AUDIO, 0, 3, " Out of sync. ")))
        assertEquals(IssueBody(IssueType.VIDEO, "Black.", 41, problemSeason = 1, problemEpisode = 2), issueBody(page, IssueDraft(IssueType.VIDEO, 1, 2, "Black.")))
        assertNull(issueBody(moviePage(Fixtures.read<MovieDetails>("movie"), images, recorded, reporter), IssueDraft(IssueType.VIDEO, 0, message = "x")))
    }

    @Test
    fun `the Issues page shows for any issue right - its rows in Seerr's words`() {
        assertTrue(seesIssues(Permission.VIEW_ISSUES))
        assertTrue(seesIssues(Permission.CREATE_ISSUES))
        assertTrue(seesIssues(Permission.MANAGE_ISSUES))
        assertFalse(seesIssues(67744))
        assertEquals(listOf("open", "resolved", "all"), IssueFilter.entries.map { it.api })
        val rows = issueRows(
            listOf(
                Issue(id = 3, issueType = IssueType.SUBTITLES, problemSeason = 1, problemEpisode = 2, media = MediaInfo(mediaType = "tv", tmdbId = 1877), createdBy = User(id = 2, displayName = "Someone"), comments = listOf(IssueComment(message = " Out of sync. "))),
                Issue(id = 4, issueType = IssueType.VIDEO, status = IssueStatus.RESOLVED, media = MediaInfo(mediaType = "movie", tmdbId = 7), createdBy = User(id = 3, displayName = "", email = "x@example.invalid")),
                Issue(id = 5, media = MediaInfo(mediaType = "tv", tmdbId = 9)),
                Issue(id = 3),
            ),
        )
        assertEquals(listOf("issue:3", "issue:4", "issue:5"), rows.map { it.key })
        assertEquals(listOf("Subtitle", "Video", "Other"), rows.map { it.kind })
        assertEquals(listOf("Open", "Resolved", "Open"), rows.map { it.status })
        // Seerr's IssueItem: "Season" 1 and "Episode" 2; a film none; "Seasons" ALL (the polish 2 audit's item 16)
        assertEquals(
            listOf(
                listOf(IssueProblem("Season", "1"), IssueProblem("Episode", "2")),
                emptyList(),
                listOf(IssueProblem("Seasons", "ALL")),
            ),
            rows.map { it.problem },
        )
        assertEquals(
            listOf(IssueProblem("Season", "3"), IssueProblem("Episodes", "ALL")),
            issueRows(listOf(Issue(id = 6, problemSeason = 3, media = MediaInfo(mediaType = "tv", tmdbId = 9)))).single().problem,
        )
        assertEquals(listOf("Someone", "x@example.invalid", null), rows.map { it.by })
        assertEquals("Out of sync.", rows[0].description)
        assertEquals("tv:1877", rows[0].titleKey)
    }
}
