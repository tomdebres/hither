package com.tomdebres.hither.ui.home

import com.tomdebres.hither.ui.components.HitherIcons
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.R
import com.tomdebres.hither.core.rules.BadgeKind
import com.tomdebres.hither.core.rules.RequestChange
import com.tomdebres.hither.ready.Front
import com.tomdebres.hither.ready.Notice
import com.tomdebres.hither.ui.theme.Glyph
import com.tomdebres.hither.ui.components.ReportRecomposition
import com.tomdebres.hither.ui.theme.HitherType
import com.tomdebres.hither.ui.theme.Metrics
import com.tomdebres.hither.ui.theme.Tokens
import kotlinx.coroutines.delay

/**
 * The alerts in Hither (M4, DECISIONS 79): the notices waiting to be shown, the count on the bar's
 * Requests, and for an approver how many requests wait for approval. Compose state, kept by
 * `HomeScreen`; each value is read only where it is drawn, so a change redraws that and nothing else.
 */
/**
 * A change found since last seen (M4), for the notices' banner. Notices only: what Hither has just been answered (a
 * request, a cancel, a decision) shows in place, and what went wrong is a `ProblemDialog` (A5, tvOS's D4; it was a toast
 * here, the polish audit's item 15).
 */
data class Toast(val notice: Notice)

@Stable
class HomeAlerts {
    /** Notices still to show, the one showing first. */
    val queue = mutableStateListOf<Toast>()

    var requestsCount by mutableIntStateOf(0)
        private set

    var waiting by mutableStateOf<Int?>(null)
        private set

    /** A decision made on the TV (M5, DECISIONS 94): the waiting count follows at once, before it is read again. */
    fun decided() {
        waiting = waiting?.minus(1)?.coerceAtLeast(0)
    }

    /** How many times My Requests has been opened: a count read before the latest opening is stale. */
    private var opens = 0

    /** Hither is asking what changed: the token to hand back to [take] with the answer. */
    fun asking(): Int = opens

    /**
     * What Hither found coming to the front, asked for at [asked] ([asking]). If My Requests was opened
     * while the answer was out, its count was read before the opening cleared it, and is not taken
     * (the reviewer's Nit on #28); the notices and the waiting count are.
     */
    fun take(front: Front, asked: Int) {
        queue += front.notices.map { Toast(it) }
        if (asked == opens) requestsCount = front.requestsCount
        waiting = front.waiting
    }

    /** My Requests opened: the count goes. */
    fun requestsOpened() {
        opens++
        requestsCount = 0
    }

    /** [toast] has been shown. */
    fun shown(toast: Toast) {
        queue.remove(toast)
    }
}

/** How long a notice stays, fades apart. */
const val NOTICE_MS = 4_000L
private const val FADE_MS = 300

/**
 * The notices, one at a time, as the [Banner] at [banner] (A2, tvOS's `NoticeBanner`). Nothing here takes focus or needs
 * dismissing: each fades in, stays [NOTICE_MS] and fades out, by the layer's alpha (no shadow to re-blur), and the next
 * follows. A screen reader reads each as it comes (a polite live region). Notices only (A5): no outcome is a toast.
 */
@Composable
fun Notices(alerts: HomeAlerts, banner: Modifier = Modifier) {
    ReportRecomposition("notices")
    val notice = alerts.queue.firstOrNull() ?: return
    key(notice) {
        val alpha = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            alpha.animateTo(1f, tween(FADE_MS))
            delay(NOTICE_MS)
            alpha.animateTo(0f, tween(FADE_MS))
            alerts.shown(notice)
        }
        Banner(notice.notice, banner.graphicsLayer { this.alpha = alpha.value })
    }
}

/**
 * A change since last seen, as the Apple TV app's banner (tvOS's `NoticeBanner`; Tom's D4 there): one capsule, top right
 * in the safe area, sized to its words and no wider than the corner beside the bar ([Metrics.BannerWidest]), on an opaque
 * surface (Android has no glass; DECISIONS 291). The change's glyph in Seerr's colour (rule 4), its word ("Ready to
 * Watch", "Approved", "Declined") and the title under it.
 */
@Composable
private fun Banner(notice: Notice, modifier: Modifier) {
    val (word, kind) = when (notice.event.change) {
        RequestChange.AVAILABLE -> stringResource(R.string.ready_to_watch) to BadgeKind.SUCCESS
        RequestChange.AVAILABLE_4K -> stringResource(R.string.ready_to_watch_4k) to BadgeKind.SUCCESS
        RequestChange.APPROVED -> stringResource(R.string.notice_approved) to BadgeKind.PRIMARY
        RequestChange.DECLINED -> stringResource(R.string.notice_declined) to BadgeKind.DANGER
    }
    Row(
        modifier
            .widthIn(max = Metrics.BannerWidest)
            .background(Tokens.Surface, CircleShape)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            // the whole capsule: a test measures what is drawn
            .testTag("notice")
            .padding(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Glyph(if (kind == BadgeKind.DANGER) HitherIcons.Cross else HitherIcons.Check, kind, size = 20.dp)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BasicText(
                word,
                style = HitherType.Body.copy(color = Tokens.Text, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("notice word"),
            )
            notice.title?.takeIf { it.isNotBlank() }?.let {
                BasicText(
                    it,
                    style = HitherType.Caption.copy(color = Tokens.Text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("notice title"),
                )
            }
        }
    }
}
