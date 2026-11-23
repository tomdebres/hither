package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.model.CrewMember
import com.tomdebres.hither.core.model.Keyword
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.Ratings
import com.tomdebres.hither.core.model.TvDetails
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.model.Video
import com.tomdebres.hither.core.model.WatchProviders

// What M6 adds to the media page (DECISIONS 98), each as Seerr's MovieDetails and TvDetails work it out
// (src/components at 3f7d5c3): the trailer, the crew under the overview, the facts, the ratings line and
// "Currently Streaming On".

/** A YouTube video id: eleven of letters, digits, `-` and `_`. Nothing else goes into a link the TV opens. */
private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")

/**
 * The title's trailer, as Seerr's web picks it: the largest video of type "Trailer", and only a YouTube
 * one (Hither hands it to a YouTube app, never fetching anything itself). Its YouTube id, or null when
 * there is none or the id is not one.
 */
fun trailerKey(videos: List<Video>): String? {
    val trailer = videos.filter { it.type == "Trailer" }.maxByOrNull { it.size } ?: return null
    if (!trailer.site.equals("YouTube", ignoreCase = true)) return null
    return trailer.key.takeIf { YOUTUBE_ID.matches(it) }
}

/** The link a trailer's id opens in a YouTube app: the full watch link, which plays it (SeerrTV found). */
fun youtubeWatchLink(key: String): String? = if (YOUTUBE_ID.matches(key)) "https://www.youtube.com/watch?v=$key" else null

/** One of the crew under the overview: the job, the name, and the person (for their page). */
data class CrewLine(val job: String, val name: String, val personId: Int)

/** Seerr's `priorityJobs` (`src/utils/creditHelpers.ts`), in its order. */
private val PRIORITY_JOBS = listOf(
    "Director", "Creator", "Screenplay", "Writer", "Composer", "Editor", "Producer", "Co-Producer",
    "Executive Producer", "Animation",
)

/** The crew lines Seerr's web shows under the overview, at most. */
const val CREW_LIMIT = 6

/**
 * Seerr's `sortCrewPriority`, then its first six: only the priority jobs, in their order (a job ranks by
 * the first priority job that contains it, as Seerr's `includes` does), ties in TMDB's order.
 */
fun crewLines(crew: List<CrewMember>): List<CrewLine> = crew
    .filter { it.job in PRIORITY_JOBS }
    .sortedBy { member -> PRIORITY_JOBS.indexOfFirst { it.contains(member.job!!) } }
    .take(CREW_LIMIT)
    .map { CrewLine(it.job!!, it.name, it.id) }

/** How a fact's value is to be written: the app formats dates, money and language names. */
enum class FactKind { TEXT, DATE, MONEY, LANGUAGE }

/** The keyword TMDB marks anime with; Seerr's series facts say "Series Type: Anime" for it. */
private const val ANIME_KEYWORD = 210024

/**
 * A film's facts, Seerr's panel in its order: Original Title (for a film not in English, the app's
 * language), Status, the region's theatrical, digital and physical releases (else the release date),
 * Revenue, Budget, Original Language, Production Countries, Studios.
 */
fun movieFacts(details: MovieDetails, settings: PublicSettings): List<Fact> {
    val releases = details.releases.results.firstOrNull { it.country == region(settings) }?.releaseDates.orEmpty()
        .filter { it.type in 3..5 && !it.releaseDate.isNullOrBlank() }
        .distinctBy { it.type }
    val dated = releases.map { release ->
        val name = when (release.type) {
            3 -> "Theatrical Release"
            4 -> "Digital Release"
            else -> "Physical Release"
        }
        Fact(name, release.releaseDate!!.take(10), FactKind.DATE)
    }
    return listOfNotNull(
        originalTitle(details.originalTitle, details.originalLanguage),
        details.status.text()?.let { Fact("Status", it) },
    ) + dated.ifEmpty { listOfNotNull(details.releaseDate.text()?.let { Fact("Release Date", it, FactKind.DATE) }) } + listOfNotNull(
        details.revenue.takeIf { it > 0 }?.let { Fact("Revenue", it.toString(), FactKind.MONEY) },
        details.budget.takeIf { it > 0 }?.let { Fact("Budget", it.toString(), FactKind.MONEY) },
        details.originalLanguage.text()?.let { Fact("Original Language", it, FactKind.LANGUAGE) },
        names(details.productionCountries.map { it.name }, "Production Country", "Production Countries"),
        names(details.productionCompanies.map { it.name }, "Studio", "Studios"),
    )
}

/**
 * A series' facts, Seerr's panel in its order: Original Title, Series Type (Anime), Status, First Air
 * Date, Next Air Date, Episode Runtime, Original Language, Production Countries, Networks.
 */
fun tvFacts(details: TvDetails): List<Fact> = listOfNotNull(
    originalTitle(details.originalName, details.originalLanguage),
    if (details.keywords.any { it.id == ANIME_KEYWORD }) Fact("Series Type", "Anime") else null,
    details.status.text()?.let { Fact("Status", it) },
    details.firstAirDate.text()?.let { Fact("First Air Date", it, FactKind.DATE) },
    details.nextEpisodeToAir?.airDate.text()?.takeIf { it != details.firstAirDate }?.let { Fact("Next Air Date", it, FactKind.DATE) },
    details.episodeRunTime.firstOrNull()?.takeIf { it > 0 }?.let { Fact("Episode Runtime", "$it minutes") },
    details.originalLanguage.text()?.let { Fact("Original Language", it, FactKind.LANGUAGE) },
    names(details.productionCountries.map { it.name }, "Production Country", "Production Countries"),
    names(details.networks.map { it.name }, "Network", "Networks"),
)

/** The original title, only for a title not in English (Seerr: not in the viewer's language). */
private fun originalTitle(title: String?, language: String?): Fact? =
    title.text()?.takeIf { language != null && language != "en" }?.let { Fact("Original Title", it) }

private fun names(values: List<String>, one: String, many: String): Fact? {
    val shown = values.map { it.trim() }.filter { it.isNotEmpty() }
    if (shown.isEmpty()) return null
    return Fact(if (shown.size == 1) one else many, shown.joinToString(", "))
}

/** Which mark a rating is drawn with: Rotten Tomatoes' four, IMDb's and TMDB's. */
enum class RatingKind { RT_FRESH, RT_ROTTEN, RT_UPRIGHT, RT_SPILLED, IMDB, TMDB }

/** One rating on the line: its mark and its value ("87%", "7.2"). */
data class RatingScore(val kind: RatingKind, val value: String)

/**
 * Seerr's ratings line, in its order: the Tomatometer (the tomato, or the splat when "Rotten"), the
 * audience score (the popcorn, or spilled), IMDb's score, then TMDB's user score (its vote average as a
 * percentage) where anyone voted. [ratings] arrives after the page; until then TMDB's stands alone.
 */
fun ratingScores(ratings: Ratings?, voteAverage: Double, voteCount: Int): List<RatingScore> {
    val rt = ratings?.rt
    val imdb = ratings?.imdb
    return listOfNotNull(
        rt?.criticsScore?.takeIf { rt.criticsRating != null }?.let {
            RatingScore(if (rt.criticsRating == "Rotten") RatingKind.RT_ROTTEN else RatingKind.RT_FRESH, "$it%")
        },
        rt?.audienceScore?.takeIf { it != 0 && rt.audienceRating != null }?.let {
            RatingScore(if (rt.audienceRating == "Spilled") RatingKind.RT_SPILLED else RatingKind.RT_UPRIGHT, "$it%")
        },
        imdb?.criticsScore?.takeIf { it != 0.0 }?.let { RatingScore(RatingKind.IMDB, oneDecimal(it)) },
        if (voteCount > 0) RatingScore(RatingKind.TMDB, "${kotlin.math.round(voteAverage * 10).toInt()}%") else null,
    )
}

/** IMDb's score as Seerr's web writes the number: one decimal at most, none when whole ("7.2", "8"). */
private fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.floor(value * 10 + 0.5).toLong()
    return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}

/** A streaming service the title is on: its name and its logo through the server's proxy. */
data class StreamingService(val name: String, val logo: String?)

/**
 * "Currently Streaming On": the services that stream the title (flat rate, not to buy) in the account's
 * streaming region, else the server's, else the US, as Seerr's web chooses, in TMDB's order.
 */
fun streamingOn(providers: List<WatchProviders>, settings: PublicSettings, user: User, baseUrl: String): List<StreamingService> {
    val region = user.settings?.streamingRegion?.takeIf { it.isNotBlank() }
        ?: settings.streamingRegion.takeIf { it.isNotBlank() }
        ?: "US"
    return providers.firstOrNull { it.country == region }?.flatrate.orEmpty()
        .map { StreamingService(it.name, imageUrl(baseUrl, it.logoPath, TmdbImage.LOGO)) }
}

/** The keywords to draw as tags, as the title lists them, each once. */
fun keywordTags(keywords: List<Keyword>): List<Keyword> = keywords.filter { it.name.isNotBlank() }.distinctBy { it.id }

private fun String?.text(): String? = this?.trim()?.ifEmpty { null }
