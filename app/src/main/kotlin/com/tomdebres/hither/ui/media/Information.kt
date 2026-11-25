package com.tomdebres.hither.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.tomdebres.hither.R
import com.tomdebres.hither.core.model.Ratings
import com.tomdebres.hither.core.rules.Fact
import com.tomdebres.hither.core.rules.FactKind
import com.tomdebres.hither.core.rules.MediaPage
import com.tomdebres.hither.core.rules.RatingKind
import com.tomdebres.hither.core.rules.RatingScore
import com.tomdebres.hither.core.rules.StreamingService
import com.tomdebres.hither.core.rules.ratingScores
import com.tomdebres.hither.ui.components.ReportRecomposition
import com.tomdebres.hither.ui.theme.Formats
import com.tomdebres.hither.ui.theme.HitherType
import com.tomdebres.hither.ui.theme.Metrics
import com.tomdebres.hither.ui.theme.ShelfTitle
import com.tomdebres.hither.ui.theme.StatusLabel
import com.tomdebres.hither.ui.theme.Tokens
import java.util.Locale

/** Information's columns across the page. */
private const val COLUMNS = 4

/** A streaming service's logo, decoded at the size it is drawn. */
private const val LOGO_PX = 44

/**
 * Information, laid out as the Apple TV app's, at a title page's foot and read at rest: **it takes no focus** (the review's
 * part 6 for tvOS; Seerr's facts box was a stop of its own). The ratings with their marks, then Seerr's facts
 * (`.media-facts`) in columns, each fact's name over its value, the seasons that cannot be asked for with their status
 * (tvOS's D2: the rest fold in here), and where the title streams. The page brings it into view with its last shelf
 * (`section`'s foot).
 */
@Composable
internal fun Information(page: MediaPage, ratings: Ratings?, modifier: Modifier = Modifier) {
    ReportRecomposition("media information")
    val scores = ratingScores(ratings, page.voteAverage, page.voteCount)
    val runs = remember(page) { seasonRuns(seasonsAtRest(page)) }
    val cells: List<@Composable () -> Unit> = buildList {
        page.facts.forEach { fact -> add { FactCell(fact.name, factValue(fact)) } }
        runs.forEach { run -> add { SeasonRunCell(run) } }
        if (page.streaming.isNotEmpty()) add { StreamingCell(page.streaming) }
    }
    if (scores.isEmpty() && cells.isEmpty()) return
    Column(modifier.testTag("media information")) {
        ShelfTitle(stringResource(R.string.information))
        Column(
            Modifier.padding(horizontal = Metrics.SideMargin),
            verticalArrangement = Arrangement.spacedBy(Metrics.InfoRowGap),
        ) {
            if (scores.isNotEmpty()) RatingsLine(scores)
            // Four columns, row by row; built whole (a lazy grid would leave an unseen part empty).
            cells.chunked(COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.InfoGap)) {
                    row.forEach { cell -> Box(Modifier.width(Metrics.InfoColumn)) { cell() } }
                }
            }
        }
    }
}

/** A fact's name, in Caption, secondary, over its value. */
@Composable
private fun FactCell(name: String, value: String) {
    Column(Modifier.fillMaxWidth().testTag("fact $name"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        BasicText(name, style = HitherType.Caption.copy(color = Tokens.TextSecondary, fontWeight = FontWeight.SemiBold))
        BasicText(value, style = HitherType.Body.copy(color = Tokens.Text))
    }
}

/**
 * A fact's value as Hither writes it (`Formats`, A1): a date as every date ("Sep 16, 2026"), money in whole dollars, a
 * runtime as "45 min", a language by its name; anything else, or a value that is not what its kind says, as given.
 */
internal fun factValue(fact: Fact): String = when (fact.kind) {
    FactKind.TEXT -> Formats.runtimeIn(fact.value)
    FactKind.DATE -> Formats.day(fact.value) ?: fact.value
    FactKind.MONEY -> fact.value.toLongOrNull()?.let(Formats::money) ?: fact.value
    FactKind.LANGUAGE -> Locale.forLanguageTag(fact.value).getDisplayLanguage(Locale.ENGLISH).ifBlank { fact.value }
}

/** Seasons at rest, as a fact: their name ("Season 1", "Seasons 2–4") over each copy's status, glyph and word. */
@Composable
private fun SeasonRunCell(run: SeasonRun) {
    val name = when {
        run.first == 0 -> stringResource(R.string.specials)
        run.first == run.last -> stringResource(R.string.season_n, run.first)
        else -> stringResource(R.string.seasons_range, run.first, run.last)
    }
    Column(Modifier.fillMaxWidth().testTag("fact $name"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(name, style = HitherType.Caption.copy(color = Tokens.TextSecondary, fontWeight = FontWeight.SemiBold))
        run.line.badges.forEach { StatusLabel(it, style = HitherType.Body) }
    }
}

/** "Currently Streaming On", a fact of its own: each service's logo (core's `imageUrl`), and its name. */
@Composable
private fun StreamingCell(services: List<StreamingService>) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().testTag("media streaming"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        BasicText(stringResource(R.string.streaming_on), style = HitherType.Caption.copy(color = Tokens.TextSecondary, fontWeight = FontWeight.SemiBold))
        services.forEach { service ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val logo = remember(service.logo, context) {
                    service.logo?.let { ImageRequest.Builder(context).data(it).size(LOGO_PX, LOGO_PX).build() }
                }
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(Tokens.Placeholder)) {
                    if (logo != null) AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                BasicText(service.name, style = HitherType.Body.copy(color = Tokens.Text))
            }
        }
    }
}

/** Seerr's ratings (`.media-ratings`), each mark and its score: Rotten Tomatoes' tomato or splat and popcorn, IMDb, TMDB. */
@Composable
private fun RatingsLine(scores: List<RatingScore>) {
    Row(
        Modifier.testTag("media ratings"),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        scores.forEach { score ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                val mark = when (score.kind) {
                    RatingKind.RT_FRESH -> R.drawable.rating_rt_fresh
                    RatingKind.RT_ROTTEN -> R.drawable.rating_rt_rotten
                    RatingKind.RT_UPRIGHT -> R.drawable.rating_rt_upright
                    RatingKind.RT_SPILLED -> R.drawable.rating_rt_spilled
                    RatingKind.IMDB -> R.drawable.rating_imdb
                    RatingKind.TMDB -> R.drawable.tmdb_logo
                }
                // Seerr's marks share a width; the wide ones (IMDb, TMDB) get more, to stay legible (tvOS's 44, 72, 92).
                val width = when (score.kind) {
                    RatingKind.IMDB -> 36.dp
                    RatingKind.TMDB -> 46.dp
                    else -> 22.dp
                }
                Image(
                    painterResource(mark),
                    contentDescription = null,
                    modifier = Modifier.width(width).height(22.dp).semantics { contentDescription = markName(score.kind) },
                    contentScale = ContentScale.Fit,
                )
                BasicText(score.value, style = HitherType.Body.copy(color = Tokens.Text), maxLines = 1, modifier = Modifier.testTag("rating ${score.kind}"))
            }
        }
    }
}

/** What TalkBack calls each mark (tvOS's). */
private fun markName(kind: RatingKind): String = when (kind) {
    RatingKind.RT_FRESH, RatingKind.RT_ROTTEN -> "Rotten Tomatoes"
    RatingKind.RT_UPRIGHT, RatingKind.RT_SPILLED -> "Rotten Tomatoes audience"
    RatingKind.IMDB -> "IMDb"
    RatingKind.TMDB -> "TMDB"
}
