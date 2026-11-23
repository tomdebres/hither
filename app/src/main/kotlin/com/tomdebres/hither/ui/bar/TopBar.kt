package com.tomdebres.hither.ui.bar

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.Tab as TvTab
import androidx.tv.material3.TabDefaults
import androidx.tv.material3.TabRow
import androidx.tv.material3.TabRowDefaults
import androidx.tv.material3.TabRowScope
import androidx.tv.material3.Text
import com.tomdebres.hither.R
import com.tomdebres.hither.ui.components.HitherIcons
import com.tomdebres.hither.ui.components.ReportRecomposition
import com.tomdebres.hither.ui.theme.HitherType
import com.tomdebres.hither.ui.theme.Metrics
import com.tomdebres.hither.ui.theme.Tokens
import com.tomdebres.hither.ui.theme.rememberTvClick

/**
 * The bar's tabs (Tom, 2026-11-14, DECISIONS 149; the Apple TV app's since A2): each is a page under the bar. Issues
 * shows only for an account with an issue right, read live (106).
 */
enum class Tab { DISCOVER, REQUESTS, ISSUES }

/** The bar's controls that focus comes back to (129): each tab, search, the account (`avatar` in the code and tests). */
enum class BarSpot {
    DISCOVER, REQUESTS, ISSUES, SEARCH, AVATAR;

    companion object {
        fun of(tab: Tab): BarSpot = when (tab) {
            Tab.DISCOVER -> DISCOVER
            Tab.REQUESTS -> REQUESTS
            Tab.ISSUES -> ISSUES
        }
    }
}

/**
 * Where focus goes on the next arrival at a tab page, set by the way back from a page the bar opened, or by focus moving
 * along the bar (a page shown because focus came to its tab leaves focus there), and taken by the arrival (once). Not
 * state: it is read when a page arrives, never in composition.
 */
class BarReturn {
    private var spot: BarSpot? = null

    fun set(to: BarSpot) {
        spot = to
    }

    fun take(): BarSpot? = spot.also { spot = null }
}

/** One focus target per control on the bar. */
class BarFocus {
    val discover = FocusRequester()
    val requests = FocusRequester()
    val issues = FocusRequester()
    val search = FocusRequester()
    val avatar = FocusRequester()

    fun of(tab: Tab): FocusRequester = when (tab) {
        Tab.DISCOVER -> discover
        Tab.REQUESTS -> requests
        Tab.ISSUES -> issues
    }

    fun of(spot: BarSpot): FocusRequester = when (spot) {
        BarSpot.DISCOVER -> discover
        BarSpot.REQUESTS -> requests
        BarSpot.ISSUES -> issues
        BarSpot.SEARCH -> search
        BarSpot.AVATAR -> avatar
    }
}

/**
 * The frame between the bar and the tab page under it (A2). The home draws the bar once over its three tab pages, so it
 * stays put (and keeps focus) while focus moving along it changes the page; the page under it says what the bar needs
 * of it through [TabPage]: where Down from the bar lands, how far its content has scrolled (the bar goes up with it),
 * and how to go back to its top. Plain fields where they are read at a key press, state where the bar draws from them.
 */
@Stable
class BarFrame {
    /** The bar's focus targets: the page hands focus to its tab with them (Back, Up from its top row). */
    val focus = BarFocus()

    /** Focus is on the bar (a tab, search, the account). */
    var barHasFocus by mutableStateOf(false)
        internal set

    /** A page's dialog is open over it (Cancel Request's question): the bar draws nothing, as the page is dimmed under it. */
    var covered by mutableStateOf(false)

    internal var down: () -> FocusRequester = { FocusRequester.Default }
    internal var scrolled by mutableStateOf<() -> Int>({ 0 })
}

/**
 * The tab page under the bar tells the frame what it needs (A2): [down], the page's own entry for Down from the bar (its
 * first item, or the one it was left on), read when the bar is left; [scrolled], how far the page's content has
 * scrolled from its top in px, read in the bar's placement only (so a scroll moves the bar and recomposes nothing). The
 * page takes itself to its top where it hands focus to its tab (Back); Up reaches the bar from the top row only.
 */
@Composable
fun TabPage(frame: BarFrame, down: () -> FocusRequester, scrolled: () -> Int) {
    val currentDown by rememberUpdatedState(down)
    val currentScrolled by rememberUpdatedState(scrolled)
    DisposableEffect(frame) {
        val ownDown: () -> FocusRequester = { currentDown() }
        val ownScrolled: () -> Int = { currentScrolled() }
        frame.down = ownDown
        frame.scrolled = ownScrolled
        onDispose {
            // the page that replaced this one may have registered first
            if (frame.scrolled === ownScrolled) {
                frame.down = { FocusRequester.Default }
                frame.scrolled = { 0 }
            }
        }
    }
}

/**
 * How far a list running under the bar has scrolled from its top, px, for [TabPage]: the first item's offset while it is
 * first, else more than the bar is tall (the bar's placement clamps it).
 */
fun LazyListState.scrolledFromTop(): Int = if (firstVisibleItemIndex > 0) Int.MAX_VALUE else firstVisibleItemScrollOffset

/**
 * What the bar shows and does, the same on every tab page: the home makes one (DECISIONS 149). The defaults are a bar
 * that goes nowhere, for a page shown on its own (a test's), which then draws the bar over itself ([hosted] false).
 */
@Stable
class BarContent(
    /** The account's name: its spoken label (whoever is signed in, the approver while the admin is active, 109). */
    val displayName: String = "",
    /** Focus came to another tab: its page, in place of this one (switching tabs builds no history). */
    val onTab: (Tab) -> Unit = {},
    /** Select on a tab: nothing more than focus did (the Apple TV app's), except that it opens My Requests at once. */
    val onTabSelected: (Tab) -> Unit = {},
    /** Whether the account may see issues, read live (106): the Issues tab shows only then. */
    val issues: () -> Boolean = { false },
    val onSearch: () -> Unit = {},
    /** The account: Settings. */
    val onAccount: () -> Unit = {},
    /** Changes to the user's requests since My Requests was last opened: a count on the Requests tab (79, 146). */
    val requestsCount: () -> Int = { 0 },
    /**
     * For an approver, how many requests wait for approval; null for an account that may not approve (79). Not drawn on
     * the bar since A6: My Requests' side list has it as its "Waiting for Approval" row, as tvOS's (DECISIONS 339).
     */
    val waiting: () -> Int? = { null },
    /** The TV's admin's name while it is active: the account reads "Admin" with a shield (93, 109; tvOS's 183). */
    val adminMark: String? = null,
    /** "Waiting for Approval" as the way into everyone's requests (94), on My Requests' side list; null: words only. */
    val onWaiting: (() -> Unit)? = null,
    val returnTo: BarReturn = BarReturn(),
    /** The frame with the page under the bar (A2). */
    val frame: BarFrame = BarFrame(),
    /** The home draws this bar over its tab pages; a page shown on its own draws it itself. */
    val hosted: Boolean = false,
)

/**
 * The bar content a page uses: the home's as given; a page shown on its own keeps the first it was given, since a default
 * `BarContent()` is a new one (and a new frame, its focus state with it) at every call.
 */
@Composable
fun rememberBar(bar: BarContent): BarContent = if (bar.hosted) bar else remember { bar }

/**
 * The bar, as the Apple TV app's (Tom's Q2 for Hither, DECISIONS 312): centred at the top, tv-material's `TabRow` with
 * words for Discover, Requests and Issues, then search and the account as icons (`IconButton`s); the account reads
 * "Admin" with a shield while the admin is active, and Requests carries its count in a small circle. **Selection
 * follows focus:** focus coming to a tab shows its page ([BarContent.onTab]); Select does nothing more. The current
 * page's tab is said by its white words, never a fill (rule 3): `TabRow`'s pill is white for focus alone and
 * transparent otherwise. **It scrolls away with the page:** it goes up by however far the page's content has scrolled
 * from its top, to its own height, read in placement ([TabPage]), and comes back as the page goes back to its top.
 * Up into it from the page lands on the current tab; Down leaves it for the page's own entry.
 *
 * [current] is read where the tabs are drawn, so a page change recomposes the tabs and not the bar.
 */
@Composable
fun TopBar(current: () -> Tab, content: BarContent, modifier: Modifier = Modifier) {
    ReportRecomposition("TopBar")
    val frame = content.frame
    val focus = frame.focus
    val down: () -> FocusRequester = { frame.down() }
    Box(
        modifier
            .fillMaxWidth()
            .height(Metrics.BarFoot)
            // up by however far the page has scrolled, to the bar's own height: it goes with the content; and wherever the
            // page is, the bar shows while it has focus (focus Compose moves there when a focused control goes, before the
            // page hands it on), so focus is never on something off the screen
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.place(0, if (frame.barHasFocus) 0 else -frame.scrolled().coerceIn(0, placeable.height))
                }
            }
            // a page's dialog over it: nothing of the bar shows (the dialog holds focus)
            .graphicsLayer { alpha = if (frame.covered) 0f else 1f }
            .onFocusChanged { frame.barHasFocus = it.hasFocus }
            // Up from the page's top lands on the current tab, not on whatever is nearest above (search, the account).
            .focusProperties { onEnter = { if (requestedFocusDirection == FocusDirection.Up) runCatching { focus.of(current()).requestFocus() } } }
            .focusGroup(),
    ) {
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = Metrics.TopMargin)
                .height(Metrics.BarHeight)
                .testTag("bar"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BarGap),
        ) {
            BarTabs(current = current, content = content, down = down)
            SearchButton(content.onSearch, Modifier.focusRequester(focus.search).focusProperties { this.down = down() })
            AccountButton(
                displayName = content.displayName,
                admin = content.adminMark,
                onSelect = content.onAccount,
                modifier = Modifier.focusRequester(focus.avatar).focusProperties {
                    this.down = down()
                    right = FocusRequester.Cancel
                },
            )
        }
    }
}

/** Between the bar's controls: tv-material's own gap between tabs. */
private val BarGap = 8.dp

/** A control on the bar: a tab's height, and an icon's circle. */
private val BarControl = 32.dp

/**
 * The tabs: tv-material's `TabRow`, words only (Tom's 150), its pill white for focus and transparent otherwise (rule 3:
 * the current tab is said by its white, bold words). Focus coming to a tab shows its page.
 */
@Composable
private fun BarTabs(
    current: () -> Tab,
    content: BarContent,
    down: () -> FocusRequester,
) {
    val now = current()
    // Issues for an account with an issue right; its own page keeps it, so the current tab never goes from under focus.
    val tabs = if (now == Tab.ISSUES || content.issues()) Tab.entries else listOf(Tab.DISCOVER, Tab.REQUESTS)
    val selected = tabs.indexOf(now)
    val focus = content.frame.focus
    // The pill is focus's alone (rule 3), so it goes to the focused tab: the selected one in the app, where selection
    // follows focus, but not on a page shown on its own, where focus can be on a tab that is not the page's.
    var focusedTab by remember { mutableIntStateOf(-1) }
    TabRow(
        selectedTabIndex = selected,
        containerColor = Color.Transparent,
        separator = { Spacer(Modifier.width(BarGap)) },
        indicator = { positions, rowHasFocus ->
            positions.getOrNull(if (rowHasFocus && focusedTab >= 0) focusedTab else selected)?.let {
                // focus, and nothing else, fills (rule 3): white while the bar has focus, nothing while it does not
                TabRowDefaults.PillIndicator(currentTabPosition = it, doesTabRowHaveFocus = rowHasFocus, activeColor = Tokens.Text, inactiveColor = Color.Transparent)
            }
        },
        modifier = Modifier.testTag("tabs"),
    ) {
        // whether focus was on a tab when these were last composed: a tab's focus then came by a move along the row
        val rowHadFocus = hasFocus
        for ((index, tab) in tabs.withIndex()) {
            val requests = tab == Tab.REQUESTS
            BarTab(
                text = stringResource(
                    when (tab) {
                        Tab.DISCOVER -> R.string.nav_discover
                        Tab.REQUESTS -> R.string.nav_requests
                        Tab.ISSUES -> R.string.nav_issues
                    },
                ),
                selected = tab == now,
                // a move along the bar shows the tab's page; focus coming from outside the tabs never switches it (Compose
                // putting focus on the first tab when a focused control goes, Up and Back landing on the current one)
                onFocus = { if (tab != current() && rowHadFocus) content.onTab(tab) },
                onSelect = { content.onTabSelected(tab) },
                count = if (requests) content.requestsCount else null,
                modifier = Modifier
                    .focusRequester(focus.of(tab))
                    .onFocusChanged { if (it.isFocused) focusedTab = index }
                    .focusProperties {
                        if (tab == Tab.DISCOVER) left = FocusRequester.Cancel
                        this.down = down()
                    },
            )
        }
    }
}

/**
 * One tab: tv-material's `Tab` under Hither's D-pad rules ([rememberTvClick]: Select once per press, the stand-in focus,
 * `ShowsFocus`; held at 1, as tv-material's tabs and the Apple TV app's do not grow). Grey words, white and bold for the current page's, black on the white pill with focus. [count], where given,
 * follows the words in a small circle (79, 146).
 */
@Composable
private fun TabRowScope.BarTab(
    text: String,
    selected: Boolean,
    onFocus: () -> Unit,
    onSelect: () -> Unit,
    count: (() -> Int)?,
    modifier: Modifier = Modifier,
) {
    val click = rememberTvClick(onSelect, scale = 1f)
    // the count's colours read focus in the draw phase
    val shown by click.interaction.collectIsFocusedAsState()
    TvTab(
        selected = selected,
        onFocus = onFocus,
        onClick = click.onClick,
        modifier = modifier.then(click.modifier).testTag("nav $text"),
        colors = TabDefaults.pillIndicatorTabColors(
            contentColor = Tokens.TextSecondary,
            inactiveContentColor = Tokens.TextSecondary,
            selectedContentColor = Tokens.Text,
            focusedContentColor = Color.Black,
            focusedSelectedContentColor = Color.Black,
        ),
        interactionSource = click.interaction,
    ) {
        Row(
            Modifier.padding(horizontal = TabInset, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text, style = HitherType.Body.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium), maxLines = 1)
            if (count != null) CountBadge(count, focused = { shown })
        }
    }
}

/** From a tab's edge to its words. */
private val TabInset = 16.dp

/**
 * The count on the Requests tab: changes to the user's requests since My Requests was last opened. A circle for one
 * digit, its width its height, and a pill with the same round ends for two (Tom, 2026-11-13, DECISIONS 146): white with
 * black numerals, and black with white ones on the focused tab's white pill, as tvOS's numbered circle goes dark with
 * focus (184). The colours follow focus in the draw phase; a new count redraws the badge and not the bar.
 */
@Composable
private fun CountBadge(count: () -> Int, focused: () -> Boolean) {
    ReportRecomposition("requests count")
    val n = count()
    if (n <= 0) return
    val words = stringResource(R.string.requests_changes, n)
    Box(
        Modifier
            .height(CountHeight)
            // one digit and its padding fit inside the height, so it stays a circle; two widen it to a pill
            .widthIn(min = CountHeight)
            .drawBehind {
                val r = size.height / 2
                drawRoundRect(if (focused()) Color.Black else Tokens.Text, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
            }
            .padding(horizontal = 5.dp)
            .semantics { contentDescription = words }
            .testTag("requests count"),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            n.coerceAtMost(99).toString(),
            style = HitherType.Label.copy(fontWeight = FontWeight.Bold),
            color = ColorProducer { if (focused()) Tokens.Text else Tokens.Background },
        )
    }
}

/** The count's height, and its width at one digit: 18 dp beside the 14 sp words. */
private val CountHeight = 18.dp

/** The search and account icons' colours: grey at rest, as the other tabs' words; black on white with focus. */
@Composable
private fun iconColors() = IconButtonDefaults.colors(
    containerColor = Color.Transparent,
    contentColor = Tokens.TextSecondary,
    focusedContainerColor = Tokens.Text,
    focusedContentColor = Color.Black,
    pressedContainerColor = Tokens.Text,
    pressedContentColor = Color.Black,
)

/**
 * Search: the magnifying glass alone, as the Apple TV app's bar ends (tvOS's 283), tv-material's `IconButton`. Select
 * opens the search page with its keyboard up; focus alone does not (the keyboard would cover the page as focus passed).
 */
@Composable
private fun SearchButton(onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val click = rememberTvClick(onSelect)
    val label = stringResource(R.string.search)
    IconButton(
        onClick = click.onClick,
        modifier = modifier
            .then(click.modifier)
            .size(BarControl)
            .semantics { contentDescription = label }
            .testTag("search"),
        scale = IconButtonDefaults.scale(focusedScale = 1f),
        colors = iconColors(),
        interactionSource = click.interaction,
    ) {
        Icon(HitherIcons.Search, contentDescription = null, modifier = Modifier.size(BarIcon))
    }
}

/** An icon on the bar. */
private val BarIcon = 18.dp

/**
 * The account, last on the bar: the person alone (tv-material's `IconButton`), its spoken label "Account, {name}"; Select
 * opens Settings. While the TV's admin is active it reads "Admin" with a shield, tv-material's `Button` (an icon button
 * is a fixed circle), the one worded state at the bar's end so the switch can't be missed (tvOS's 183 and 283); TalkBack
 * says "Admin mode: {name}" as it comes.
 */
@Composable
private fun AccountButton(displayName: String, admin: String?, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val click = rememberTvClick(onSelect)
    val label = stringResource(R.string.account)
    if (admin == null) {
        IconButton(
            onClick = click.onClick,
            modifier = modifier
                .then(click.modifier)
                .size(BarControl)
                .semantics { contentDescription = if (displayName.isBlank()) label else "$label, $displayName" }
                .testTag("avatar"),
            scale = IconButtonDefaults.scale(focusedScale = 1f),
            colors = iconColors(),
            interactionSource = click.interaction,
        ) {
            Icon(HitherIcons.User, contentDescription = null, modifier = Modifier.size(BarIcon).testTag("user glyph"))
        }
    } else {
        val said = stringResource(R.string.admin_mark_said, admin)
        Button(
            onClick = click.onClick,
            modifier = modifier
                .then(click.modifier)
                .height(BarControl)
                .semantics {
                    contentDescription = said
                    liveRegion = LiveRegionMode.Polite
                }
                .testTag("avatar"),
            scale = ButtonDefaults.scale(focusedScale = 1f),
            colors = ButtonDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = Tokens.Text,
                focusedContainerColor = Tokens.Text,
                focusedContentColor = Color.Black,
                pressedContainerColor = Tokens.Text,
                pressedContentColor = Color.Black,
            ),
            contentPadding = PaddingValues(horizontal = 12.dp),
            interactionSource = click.interaction,
        ) {
            Row(Modifier.testTag("admin badge"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(HitherIcons.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.admin_badge), style = HitherType.Body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            }
        }
    }
}

