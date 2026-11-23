package com.tomdebres.hither.ui.discover

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.tomdebres.hither.R
import com.tomdebres.hither.core.rules.DiscoverRow
import com.tomdebres.hither.core.rules.RowSource
import com.tomdebres.hither.ui.components.HitherIcons
import com.tomdebres.hither.ui.theme.LockupButton
import com.tomdebres.hither.ui.theme.Metrics
import com.tomdebres.hither.ui.theme.Tokens

/** Seerr's card has `p-2` and `gap-2` round its posters: 4 dp here. */
private val PosterInset = 4.dp
private val PosterShape = RoundedCornerShape(3.dp)

/** A faint poster is decoded at the size it is drawn: half the card's width less the gaps, 2:3. */
private const val POSTER_WIDTH_PX = 108
private const val POSTER_HEIGHT_PX = 162

/**
 * "See More", the last stop of every row Seerr links (Tom, 2026-11-12, DECISIONS 112; every linked row since polish 2,
 * 120), as tvOS's `SeeMoreCard` (A3, DECISIONS 320): a poster lockup's size, the next four titles' posters two by two at
 * 30% on the placeholder grey, the arrow in a circle over them, and "See More" as its caption under it. Focus as a
 * lockup's (a `LockupButton`). Select opens [row]'s page.
 */
@Composable
internal fun SeeMoreCard(
    row: DiscoverRow,
    posters: List<String?>,
    onSelect: () -> Unit,
    onFocused: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    // TalkBack's words: "See More Movies" and "See More Series" for the two pages, else the row's title
    val label = when (row.source) {
        RowSource.PopularMovies -> stringResource(R.string.see_more_movies)
        RowSource.PopularSeries -> stringResource(R.string.see_more_series)
        else -> stringResource(R.string.see_more_row, row.title)
    }
    LockupButton(
        onSelect = onSelect,
        tag = "see more $tag",
        description = label,
        caption = stringResource(R.string.see_more),
        modifier = modifier,
        onFocused = onFocused,
    ) {
        Posters(posters)
        Image(
            HitherIcons.CircleArrowRight,
            contentDescription = null,
            modifier = Modifier.align(Alignment.Center).size(32.dp),
            colorFilter = ColorFilter.tint(Tokens.Text),
        )
    }
}

/** The next titles' posters, two by two at 30% (one layer's alpha, not four), as Seerr's card shows them. */
@Composable
private fun Posters(posters: List<String?>) {
    if (posters.isEmpty()) return
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(PosterInset)
            .graphicsLayer { alpha = 0.3f },
        verticalArrangement = Arrangement.spacedBy(PosterInset, Alignment.CenterVertically),
    ) {
        posters.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(PosterInset)) {
                pair.forEach { poster ->
                    val request = remember(poster, context) {
                        poster?.let { ImageRequest.Builder(context).data(it).size(POSTER_WIDTH_PX, POSTER_HEIGHT_PX).build() }
                    }
                    Box(Modifier.size(PosterWidth, PosterHeight).clip(PosterShape)) {
                        if (request != null) {
                            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

/** Half the card's width less its inset and the gap: 54 x 81 dp, 2:3. */
private val PosterWidth = (Metrics.PosterWidth - PosterInset * 3) / 2
private val PosterHeight = PosterWidth * 1.5f
