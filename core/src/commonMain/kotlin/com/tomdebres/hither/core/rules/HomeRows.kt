package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.TvDetails
import kotlinx.serialization.Serializable

/*
 * The TV's home screen (M4, DECISIONS 76): Hither's own channel, "Ready to Watch", and the same
 * titles in Android's Watch Next. What goes in each, and when the server is asked again, is
 * decided here; the app writes it to Android's TV provider.
 */

/** How long a ready title stays in Watch Next (Tom, 2026-11-11). */
const val WATCH_NEXT_DAYS = 14

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val MINUTE_MS = 60L * 1000

/** The background refresh's period (Tom, 2026-11-11: every 30 minutes, never more often). */
const val BACKGROUND_LOOK_MINUTES = 30L

/**
 * A background look is skipped when any look was made this recently: WorkManager runs the
 * periodic one anywhere in the last part of its period, so this leaves it room without letting
 * an arrival in the app and the background refresh both wake the server within minutes.
 */
const val BACKGROUND_GAP_MINUTES = 25L

/** Coming back to the app looks again at most once a minute, however often the remote toggles it. */
const val FOREGROUND_GAP_MS = MINUTE_MS

/**
 * Whether a look is due now, [lastLookMs] being the last one made (asked, whether it was answered
 * or not). In the background: not within [BACKGROUND_GAP_MINUTES] of the last. In the app (starting,
 * or coming back to the front): not within [FOREGROUND_GAP_MS]. A clock that went backwards counts
 * as due.
 */
fun lookDue(lastLookMs: Long?, nowMs: Long, background: Boolean): Boolean {
    val last = lastLookMs ?: return true
    val gap = if (background) BACKGROUND_GAP_MINUTES * MINUTE_MS else FOREGROUND_GAP_MS
    return nowMs < last || nowMs - last >= gap
}

/**
 * The ready titles Watch Next carries, in the order given: those that became ready within the last
 * [WATCH_NEXT_DAYS] days and have not been opened in Hither (or played through it) since. [dismissed]
 * holds, per title key, the time it was opened ([dismissal]); a title that becomes ready again after
 * that (its 4K copy, a later season's request) is back. A title with no time for when it became ready
 * cannot be said to be new, and is left out.
 */
fun watchNextTitles(ready: List<ReadyTitle>, dismissed: Map<String, Long>, nowMs: Long): List<ReadyTitle> =
    ready.filter { title ->
        val at = title.readyAt ?: return@filter false
        nowMs - at < WATCH_NEXT_DAYS * DAY_MS && at > (dismissed[title.key] ?: Long.MIN_VALUE)
    }

/**
 * When a title opened (or played) at [nowMs] counts as seen until: now, or when it became ready if
 * the server's clock is ahead of the TV's, so that opening it always takes it out of Watch Next.
 */
fun dismissal(readyAt: Long?, nowMs: Long): Long = maxOf(nowMs, readyAt ?: Long.MIN_VALUE)

/** Dismissals that can no longer hide anything: older than Watch Next keeps a title. */
fun liveDismissals(dismissed: Map<String, Long>, nowMs: Long): Map<String, Long> =
    dismissed.filterValues { nowMs - it < WATCH_NEXT_DAYS * DAY_MS }

/** What a home-screen card shows of a title, kept on the device so each title is asked for once. */
@Serializable
data class TitleArt(
    val title: String,
    val year: String? = null,
    /** 2:3, through the server's image proxy ([TmdbImage.POSTER]). */
    val poster: String? = null,
    /** 16:9, through the server's image proxy ([TmdbImage.WIDE]). */
    val backdrop: String? = null,
)

fun titleArt(details: MovieDetails, baseUrl: String): TitleArt = TitleArt(
    title = details.title,
    year = yearOf(details.releaseDate),
    poster = imageUrl(baseUrl, details.posterPath, TmdbImage.POSTER),
    backdrop = imageUrl(baseUrl, details.backdropPath, TmdbImage.WIDE),
)

fun titleArt(details: TvDetails, baseUrl: String): TitleArt = TitleArt(
    title = details.name,
    year = yearOf(details.firstAirDate),
    poster = imageUrl(baseUrl, details.posterPath, TmdbImage.POSTER),
    backdrop = imageUrl(baseUrl, details.backdropPath, TmdbImage.WIDE),
)

private fun yearOf(date: String?): String? = date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

/** A title, as a home-screen card's link names it. */
data class TitleLink(val type: MediaType, val tmdbId: Int) {
    val key: String get() = "${type.api}:$tmdbId"

    /** `hither://title/movie/550`. */
    val uri: String get() = "$LINK_SCHEME://$LINK_HOST/${type.api}/$tmdbId"
}

const val LINK_SCHEME = "hither"
private const val LINK_HOST = "title"
private val LINK = Regex("^$LINK_SCHEME://$LINK_HOST/(movie|tv)/([1-9][0-9]{0,9})$")

/**
 * The title a link names, or null for anything that is not exactly `hither://title/<movie|tv>/<id>`:
 * another app may send Hither any link, and only this form opens a page.
 */
fun parseTitleLink(uri: String?): TitleLink? {
    val match = LINK.matchEntire(uri ?: return null) ?: return null
    val type = MediaType.fromApi(match.groupValues[1]) ?: return null
    val id = match.groupValues[2].toLongOrNull()?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
    return TitleLink(type, id)
}

/**
 * Whether the account may see "N waiting for approval": it holds MANAGE_REQUESTS (or is an admin),
 * as `auth/me` says now (DECISIONS 14). Seerr's web shows its sidebar's pending count on that right.
 */
fun seesWaiting(user: User): Boolean = Permission.hasAny(user.permissions, Permission.MANAGE_REQUESTS)
