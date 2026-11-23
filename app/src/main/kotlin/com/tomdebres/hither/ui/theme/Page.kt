package com.tomdebres.hither.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** What kind of page a screen is ([Page]). */
sealed interface PageKind {
    /**
     * A tab's own page (My Requests, Issues): the whole screen, under the bar. The home draws the bar once over its tab
     * pages (A2, `ui/bar/TopBar.kt`), at the top, and the bar scrolls away with the page: the page leaves it
     * [Metrics.BarFoot] at its top, in its list's top padding where the list runs under it, and tells it how far it has
     * scrolled (`TabPage`). Its content keeps Android TV's margins ([Metrics]) itself, as a list runs to the edges.
     */
    data object Tab : PageKind

    /** A tab's page of shelves (Discover): as [Tab]; each shelf runs to the screen's edges and keeps the margins inside. */
    data object Shelves : PageKind

    /**
     * A page opened from another. With a [title] (Settings, a grid, the Movies page), the title sits where the Apple TV
     * app puts a pushed page's own: centred, grey, Title bold, at the top margin, and the content under it at the side
     * margins (tvOS's F23). [titleTag] tags it. Without one (a title's page, a person's, search), the page has the whole
     * screen and lays out its own top.
     */
    data class Pushed(val title: String?, val titleTag: String = "page title") : PageKind

    /** A screen before the frame (starting, sign-in, the server not answering), inside the safe area. */
    data object Screen : PageKind
}

/**
 * The one way a screen meets the TV's edges (H2's X1; tvOS's `PageScaffold`). Every screen goes through it, so the
 * ground, the margins and a pushed page's title are set once, not file by file: eleven files had their own
 * `SideMargin` and `TopMargin`, and every heading was drawn its own way, left and in Seerr's gradient (DECISIONS 296).
 *
 * The black ground runs under everything to every edge. [modifier] goes on the page's content (title and all), so a
 * page that lifts itself above the keyboard lifts its title with it while the ground stays. [bleed] lets a pushed
 * page's content, or a screen's, run to the sides, keeping its own margins inside ([Metrics.SideMargin]): a shelf or a
 * grid, so a lifted card is never cut. [trailing] sits at the right of a pushed page's title line (the Movies page's
 * Sort and Filters). Nothing else pads a page from the screen's edges.
 */
@Composable
fun Page(
    kind: PageKind,
    modifier: Modifier = Modifier,
    bleed: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Tokens.Background)) {
        when (kind) {
            PageKind.Tab, PageKind.Shelves -> Box(modifier.fillMaxSize(), content = content)
            PageKind.Screen -> Box(
                modifier
                    .fillMaxSize()
                    .then(if (bleed) Modifier else Modifier.padding(horizontal = Metrics.SideMargin, vertical = Metrics.TopMargin)),
                content = content,
            )
            is PageKind.Pushed -> if (kind.title == null) {
                Box(modifier.fillMaxSize(), content = content)
            } else {
                Column(modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .padding(start = Metrics.SideMargin, end = Metrics.SideMargin, top = Metrics.TopMargin)
                            .height(Metrics.PageTitleHeight)
                            .fillMaxWidth(),
                    ) {
                        PageTitle(kind.title, Modifier.align(Alignment.Center).testTag(kind.titleTag))
                        if (trailing != null) {
                            Row(
                                Modifier.align(Alignment.CenterEnd),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                content = trailing,
                            )
                        }
                    }
                    Spacer(Modifier.height(Metrics.BelowPageTitle))
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .then(if (bleed) Modifier else Modifier.padding(horizontal = Metrics.SideMargin)),
                        content = content,
                    )
                }
            }
        }
    }
}

/** A pushed page's title, as the Apple TV app draws its own: Title bold, secondary, centred, one line; a heading. */
@Composable
private fun PageTitle(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text,
        style = HitherType.Title.copy(color = Tokens.TextSecondary, textAlign = TextAlign.Center),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { heading() },
    )
}
