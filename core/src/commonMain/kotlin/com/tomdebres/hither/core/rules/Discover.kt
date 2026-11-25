package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.model.DiscoverQuery

import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.PublicSettings

/**
 * One of Discover's rows: Seerr's built-in sliders in its wording (its `Discover/constants.ts`), Recently
 * Added, or an admin's custom slider under its own title (M6, DECISIONS 99). [name] keys the row (a
 * built-in's own name, `CUSTOM_<slider id>` for a custom one); [ordinal] numbers it, the built-ins in
 * Hither's order as before M6.
 */
data class DiscoverRow(val name: String, val title: String, val source: RowSource, val ordinal: Int) {
    companion object {
        val TRENDING = DiscoverRow("TRENDING", "Trending", RowSource.Trending, 0)
        val POPULAR_MOVIES = DiscoverRow("POPULAR_MOVIES", "Popular Movies", RowSource.PopularMovies, 1)
        val POPULAR_SERIES = DiscoverRow("POPULAR_SERIES", "Popular Series", RowSource.PopularSeries, 2)
        val UPCOMING_MOVIES = DiscoverRow("UPCOMING_MOVIES", "Upcoming Movies", RowSource.UpcomingMovies, 3)
        val UPCOMING_SERIES = DiscoverRow("UPCOMING_SERIES", "Upcoming Series", RowSource.UpcomingSeries, 4)

        /** Seerr's "Recently Added": the library's newest, for an account with Recent View or Manage Requests. */
        val RECENTLY_ADDED = DiscoverRow("RECENTLY_ADDED", "Recently Added", RowSource.RecentlyAdded, 5)

        /** The built-in rows in Hither's own order: Discover when the server's settings cannot be read. */
        val entries: List<DiscoverRow> = listOf(TRENDING, POPULAR_MOVIES, POPULAR_SERIES, UPCOMING_MOVIES, UPCOMING_SERIES)
    }
}

/** Where a row's titles come from: the server route each of Seerr's sliders reads. */
sealed interface RowSource {
    data object Trending : RowSource
    data object PopularMovies : RowSource
    data object PopularSeries : RowSource
    data object UpcomingMovies : RowSource
    data object UpcomingSeries : RowSource

    /** `GET /media?filter=allavailable&sort=mediaAdded`, each record's details read for its card. */
    data object RecentlyAdded : RowSource

    /** `discover/movies|tv` with a query: keywords, a genre or streaming services. */
    data class Query(val type: MediaType, val query: DiscoverQuery) : RowSource

    /** `GET /search?query=`: films and series (people are left out, as everywhere on Discover). */
    data class Search(val query: String) : RowSource

    /** `discover/movies/studio/{id}`. */
    data class Studio(val id: Int) : RowSource

    /** `discover/tv/network/{id}`. */
    data class Network(val id: Int) : RowSource
}

/**
 * One card in a row or a grid, everything it draws worked out: Seerr's `TitleCard` for a
 * film or a series. [key] is unique within a row (a film and a series can share a TMDB id).
 */
/** A card in a grid of results: a title's, or (in search) a person's (M6). [key] is unique within the grid. */
sealed interface GridCard {
    val key: String
}

data class TitleCard(
    override val key: String,
    val tmdbId: Int,
    val type: MediaType,
    val title: String,
    val year: String?,
    /** From the server's image proxy or TMDB's, as the server says ([imageUrl]); null draws the placeholder. */
    val poster: String?,
    /** The mini badge top-right, from the title's library status; null draws none. */
    val badge: StatusBadge?,
    /** TMDB's overview, drawn on the focused card once focus has rested (DECISIONS 111); null where there is none. */
    val overview: String? = null,
) : GridCard

/** The chip's word, top-left of the card: Seerr's `globalMessages.movie` and `.tvshow`. */
val MediaType.chip: String
    get() = when (this) {
        MediaType.MOVIE -> "Movie"
        MediaType.TV -> "Series"
    }

/**
 * The card for [result], or null for what Discover does not show: people (search and
 * trending carry them), and a media type this build does not know.
 */
fun titleCard(result: MediaResult, images: ImageSource): TitleCard? {
    val type = result.type ?: return null
    val info = result.mediaInfo
    return TitleCard(
        key = "${type.api}:${result.id}",
        tmdbId = result.id,
        type = type,
        title = result.displayTitle,
        year = result.year,
        poster = imageUrl(images, result.posterPath, TmdbImage.POSTER),
        badge = cardBadge(info),
        overview = result.overview?.trim()?.ifEmpty { null },
    )
}

/**
 * Whether Discover shows [result] on this server for this user: Seerr's `useDiscover` filters.
 * With `hideAvailable` on, titles that are available or partly so are left out; with
 * `hideBlocklisted` on, blocklisted titles are, but only for a user who manages the blocklist
 * (everyone else sees them, as on the web); with `hideRequested` on, titles with a request
 * still open.
 */
fun shownOnDiscover(result: MediaResult, settings: PublicSettings, permissions: Int?): Boolean {
    val info = result.mediaInfo ?: return true
    if (settings.hideAvailable && (info.status == MediaStatus.AVAILABLE || info.status == MediaStatus.PARTIALLY_AVAILABLE)) return false
    if (settings.hideBlocklisted && info.status == MediaStatus.BLOCKLISTED &&
        Permission.hasAny(permissions, Permission.MANAGE_BLOCKLIST)
    ) return false
    if (settings.hideRequested && info.hasActiveRequest) return false
    return true
}

/**
 * A row's cards after a further page: the new page's cards that are shown and not already in
 * the row, appended. A title can come back on a later page (TMDB's lists move while the user
 * reads them); Seerr drops the repeat, and a row keyed by card cannot hold it twice.
 */
fun appendPage(
    cards: List<TitleCard>,
    page: List<MediaResult>,
    images: ImageSource,
    settings: PublicSettings,
    permissions: Int?,
): List<TitleCard> {
    val keys = cards.mapTo(HashSet()) { it.key }
    val added = page.asSequence()
        .filter { shownOnDiscover(it, settings, permissions) }
        .mapNotNull { titleCard(it, images) }
        .filter { keys.add(it.key) }
        .toList()
    return if (added.isEmpty()) cards else cards + added
}

/**
 * Whether a row should ask for its next page now: there is one, nothing is on its way, and
 * either the row does not yet fill the screen (filters can leave a page nearly empty) or
 * focus has come within [LOOKAHEAD] cards of the end, so the next cards are there before the
 * user reaches them. [focused] is the focused card's index, or -1 when the row has no focus.
 */
fun wantsNextPage(
    count: Int,
    focused: Int,
    page: Int,
    totalPages: Int,
    busy: Boolean,
    fills: Int = FILLS_SCREEN,
    lookahead: Int = LOOKAHEAD,
): Boolean {
    if (busy || page >= totalPages) return false
    return count < fills || (focused >= 0 && focused >= count - lookahead)
}

/** Cards that fill a row on the screen (six whole and most of a seventh). */
const val FILLS_SCREEN = 7

// ---- "See More" (Tom, 2026-11-12, DECISIONS 112) --------------------------------------------------

/**
 * Whether a row ends with "See More": every row Seerr links (`linkUrl`, `Discover/index.tsx` 224-395), which is
 * every row but Recently Added (Tom's pick, polish 2, DECISIONS 120; 112 had Popular Movies and Series only).
 */
val DiscoverRow.linked: Boolean get() = source != RowSource.RecentlyAdded

/** Where a row's "See More" goes, as Seerr's `linkUrl` does. */
sealed interface SeeMore {
    /**
     * The Movies or Series page ([type]), its own sort and filters over [base]: the row's query, fixed for the
     * page (`/discover/movies?keywords=…`, `?genre=`, `?watchProviders=`, the Upcoming rows' release date).
     */
    data class Browse(val type: MediaType, val base: DiscoverQuery = DiscoverQuery()) : SeeMore

    /** A grid of the row's own titles under its title (Trending, a studio, a network, a search), as a keyword's is. */
    data object Grid : SeeMore
}

/**
 * The target of [row]'s "See More", [today] (`yyyy-mm-dd`, the device's date) being the Upcoming rows' first
 * date, as Seerr's `upcomingDate`; null for a row with none.
 */
fun seeMoreTarget(row: DiscoverRow, today: String): SeeMore? = when (val source = row.source) {
    RowSource.PopularMovies -> SeeMore.Browse(MediaType.MOVIE)
    RowSource.PopularSeries -> SeeMore.Browse(MediaType.TV)
    RowSource.UpcomingMovies -> SeeMore.Browse(MediaType.MOVIE, DiscoverQuery(releasedFrom = today))
    RowSource.UpcomingSeries -> SeeMore.Browse(MediaType.TV, DiscoverQuery(releasedFrom = today))
    is RowSource.Query -> SeeMore.Browse(source.type, source.query)
    RowSource.Trending, is RowSource.Search, is RowSource.Studio, is RowSource.Network -> SeeMore.Grid
    RowSource.RecentlyAdded -> null
}

/** The cards a row with "See More" shows before it: Seerr's slider stops at 20 (`MediaSlider`). */
const val SEE_MORE_AFTER = 20

/** The titles after those shown whose posters the card carries, faint: Seerr's `ShowMoreCard` has four. */
const val SEE_MORE_POSTERS = 4

/** How many of a row's [count] titles it shows as cards: all, or for a row with "See More" the first 20. */
fun shownCards(row: DiscoverRow, count: Int): Int = if (row.linked) minOf(count, SEE_MORE_AFTER) else count

/**
 * Whether the row ends with "See More": a row that has one, once it has its 20. Seerr's needs more than 20
 * titles; here the 20 are enough, since the page it opens has its own sort and filters.
 */
fun showsSeeMore(row: DiscoverRow, count: Int): Boolean = row.linked && count >= SEE_MORE_AFTER

/**
 * [wantsNextPage] for a row of Discover: a row with "See More" stops once it has the 20 it shows and the
 * four its card carries, as Seerr's slider reads no further; any other row carries on.
 */
fun rowWantsNextPage(row: DiscoverRow, count: Int, focused: Int, page: Int, totalPages: Int, busy: Boolean): Boolean {
    if (row.linked && count >= SEE_MORE_AFTER + SEE_MORE_POSTERS) return false
    return wantsNextPage(count, focused, page, totalPages, busy)
}

// ---- the focused card's overview (Tom, 2026-11-12, DECISIONS 111) ---------------------------------

/**
 * The overview's lines on a focused card once focus has rested: five under a one-line title, one fewer
 * for each further line of the title, so the block of text keeps one height and the top of the poster
 * stays bright. Seerr's card gives its summary three or five lines and its title three.
 */
fun overviewLines(titleLines: Int): Int = (6 - titleLines).coerceIn(3, 5)

/** How near the end focus comes before the next page is asked for: a screen's worth. */
const val LOOKAHEAD = 7

/**
 * A row's cards with each status brought up to date from [fresh] (the same row asked again),
 * in the order and with the cards the row already has, so nothing moves under the user's
 * focus: "live status" without a reshuffle. A card that [fresh] does not carry keeps its badge.
 */
fun withFreshBadges(cards: List<TitleCard>, fresh: List<TitleCard>): List<TitleCard> {
    if (fresh.isEmpty()) return cards
    val badges = fresh.associate { it.key to it.badge }
    var changed = false
    val updated = cards.map { card ->
        if (card.key in badges && badges[card.key] != card.badge) {
            changed = true
            card.copy(badge = badges[card.key])
        } else {
            card
        }
    }
    return if (changed) updated else cards
}

/**
 * A row's cards with the card keyed [key] showing [badge] (its page has just read or made a
 * new status), in place: nothing moves, nothing is added, and the same list comes back when no
 * card changed, so the row is not redrawn for nothing.
 */
/** [withBadge] over a grid that may hold people: only a title's card carries a badge. */
fun withGridBadge(cards: List<GridCard>, key: String, badge: StatusBadge?): List<GridCard> {
    val index = cards.indexOfFirst { it.key == key }
    val card = cards.getOrNull(index) as? TitleCard ?: return cards
    if (card.badge == badge) return cards
    return cards.toMutableList().also { it[index] = card.copy(badge = badge) }
}

fun withBadge(cards: List<TitleCard>, key: String, badge: StatusBadge?): List<TitleCard> {
    val index = cards.indexOfFirst { it.key == key }
    if (index < 0 || cards[index].badge == badge) return cards
    return cards.toMutableList().also { it[index] = it[index].copy(badge = badge) }
}

/**
 * Search results after a further page: Seerr's search page shows every film and series it is
 * given (none of Discover's hide filters, `Search/index.tsx`), so only people, media types this
 * build does not know and repeats across pages are left out.
 */
fun appendSearchPage(cards: List<TitleCard>, page: List<MediaResult>, images: ImageSource): List<TitleCard> {
    val keys = cards.mapTo(HashSet()) { it.key }
    val added = page.mapNotNull { titleCard(it, images) }.filter { keys.add(it.key) }
    return if (added.isEmpty()) cards else cards + added
}

/**
 * Search's grid after a further page (M6, DECISIONS 100): films, series and, as Seerr's search shows
 * them, people, in the server's order, each once.
 */
fun appendResults(cards: List<GridCard>, page: List<MediaResult>, images: ImageSource): List<GridCard> {
    val keys = cards.mapTo(HashSet()) { it.key }
    val added = page.mapNotNull { titleCard(it, images) ?: personCard(it, images) }.filter { keys.add(it.key) }
    return if (added.isEmpty()) cards else cards + added
}

/** What is sent for what was typed: the words without the spaces around them; null for none. */
fun searchQuery(typed: String): String? = typed.trim().ifEmpty { null }
