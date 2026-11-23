package com.tomdebres.hither.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardContainerDefaults
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.StandardCardContainer
import androidx.tv.material3.Text
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import com.tomdebres.hither.core.rules.BadgeKind
import com.tomdebres.hither.ui.components.HitherIcons
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.tomdebres.hither.core.rules.StatusBadge
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.core.rules.chip
import com.tomdebres.hither.ui.components.LocalDrawProbe
import com.tomdebres.hither.ui.components.ReportRecomposition
import com.tomdebres.hither.ui.components.focusScale

// tvOS's lockups (`Theme/Lockups.swift`; H2's X3, DECISIONS 320): a picture with its caption under it. The picture is
// tv-material's `Card` inside its `StandardCardContainer`, with tv-material's focus (a white edge and the 1.1 scale; no
// glow, which it draws only on API 28 and up, and no shadow); the caption is under it, never on it. Every lockup goes
// through `rememberTvClick`, which carries Hither's D-pad rules and the test hooks (`ShowsFocus`, the stand-in focus);
// each reports its recompositions (`ReportRecomposition`) and its picture's draws (`LocalDrawProbe`).
//
// The scale (DECISIONS 321): tv-material's own `CardDefaults.scale` reads its animation in composition and recomposes
// the card on every frame of it, 300 ms in and 500 ms out (H2, from its bytecode; measured in A3). The lockups hold it at
// 1 and scale in the layer phase instead (`rememberTvClick`'s `focusScale`, H2's escape hatch), as A1 did for buttons.

/**
 * A lockup's click: [rememberTvClick], scaling the picture to 1.1 in the layer phase; the card's own scale is held at 1
 * ([LockupCard]). A3 measured both (DECISIONS 321).
 */
@Composable
private fun rememberLockupClick(onSelect: () -> Unit, busy: Boolean = false, onLongClick: (() -> Unit)? = null): TvClick =
    rememberTvClick(onSelect, busy = busy, scale = Metrics.FocusScale, onLongClick = onLongClick)

/** A lockup picture's corners (tvOS's 12 points). */
private val ImageShape = RoundedCornerShape(Metrics.ImageRadius)

/** A poster lockup's whole height: the picture and its caption. A shelf of them is this tall. */
val PosterLockupHeight: Dp = Metrics.PosterHeight + Metrics.CaptionHeight

/** A backdrop lockup's whole height (Recent Requests). */
val BackdropLockupHeight: Dp = Metrics.BackdropHeight + Metrics.CaptionHeight

/** A portrait lockup's caption: the name on up to two lines and the part under it. */
private val PortraitCaption = Metrics.CaptionGap + 20.dp * 2 + 16.dp

/** A portrait lockup's whole height (Cast & Crew, a person in search). */
val PortraitLockupHeight: Dp = Metrics.Portrait + PortraitCaption

/** A portrait lockup's width: its caption is a little wider than its circle (tvOS's portrait + 40 points). */
val PortraitLockupWidth: Dp = Metrics.Portrait + 20.dp

/** Pictures are decoded at the size they are drawn, never larger: the Shield is a Tegra X1 (DECISIONS 48). */
private const val POSTER_WIDTH_PX = 240
private const val POSTER_HEIGHT_PX = 360
private const val BACKDROP_WIDTH_PX = 410
private const val BACKDROP_HEIGHT_PX = 230
private const val PORTRAIT_PX = 200

/**
 * A list's lockup caption (tvOS's `LockupStatus`, for A6's lists): the status's glyph and word in place of the year, and
 * a line under it (when, who). The poster then carries no glyph: it would say the title's library status beside the
 * request's. The status is a request's [badge], or an issue's Open or Resolved ([issue]). Where the whole [line] is wider
 * than the poster, its most useful part, [short], is drawn instead (tvOS's `ViewThatFits`: "33 seconds ago by User 1"
 * does not fit); TalkBack reads the whole.
 */
class LockupStatus private constructor(
    val badge: StatusBadge?,
    /** An issue's status, where [badge] is null: open (Seerr's warning yellow) or resolved (its green). */
    val open: Boolean,
    /** The status's words: the badge's, or Seerr's "Open" and "Resolved". */
    val words: String,
    val line: String?,
    val short: String?,
) {
    constructor(badge: StatusBadge, line: String? = null, short: String? = null) : this(badge, false, badge.text, line, short)

    companion object {
        /** An issue's status (tvOS's `.issue`): Open as a warning, Resolved as done, in Seerr's [words]. */
        fun issue(open: Boolean, words: String, line: String? = null, short: String? = null) = LockupStatus(null, open, words, line, short)
    }
}

/** What a poster lockup says under its title: the year, and the kind of title where its row mixes films and series. */
fun lockupSubtitle(card: TitleCard, mixed: Boolean): String? =
    listOfNotNull(card.year, if (mixed) card.type.chip else null).takeIf { it.isNotEmpty() }?.joinToString(Formats.Separator)

/** Whether [cards] mix films and series: their lockups then say which each is (Seerr's MOVIE and SERIES chips, as words). */
fun mixesKinds(cards: Iterable<TitleCard>): Boolean = cards.mapTo(HashSet()) { it.type }.size > 1

/**
 * The lockup's picture: tv-material's `Card` on [click]'s rules, in the placeholder grey until its image comes, the
 * picture clipped to [shape]. [modifier] is the caller's place for focus (its requester, its fences), on the card that
 * takes focus. Focus is tv-material's white edge; its scale is held at 1 and [click] scales in the layer phase.
 */
@Composable
private fun LockupCard(
    click: TvClick,
    tag: String,
    description: String,
    modifier: Modifier,
    width: Dp,
    height: Dp,
    shape: Shape,
    picture: Any?,
    onFocused: () -> Unit,
    state: String? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val probe = LocalDrawProbe.current
    Card(
        onClick = click.onClick,
        // pointers and TalkBack: the keys never reach tv-material's own (`rememberTvClick` takes them first)
        onLongClick = click.onLongClick,
        modifier = modifier
            // Outside the scale's layer: a row brings the lockup's place at rest into view, not its scaled picture
            // (DECISIONS 40: lined up scaled, every row came to rest low; tv-material's own scale sits inside its focus
            // target for the same reason, and Hither's cannot).
            .then(BringIntoViewAtRest)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .then(click.modifier)
            .semantics {
                contentDescription = description
                // TalkBack reads it after the name, as VoiceOver reads tvOS's hint (the overview, Tom's Q4).
                if (state != null) stateDescription = state
            }
            .testTag(tag),
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = Tokens.Placeholder, focusedContainerColor = Tokens.Placeholder, pressedContainerColor = Tokens.Placeholder),
        // tv-material's own scale reads its animation in composition: held at 1, and [click] scales in the layer phase.
        scale = CardDefaults.scale(focusedScale = 1f),
        border = CardDefaults.border(focusedBorder = Border(androidx.compose.foundation.BorderStroke(Metrics.LockupEdge, Tokens.Text), shape = shape)),
        glow = CardDefaults.glow(),
        interactionSource = click.interaction,
    ) {
        Box(
            Modifier
                .size(width, height)
                .drawWithContent {
                    probe?.invoke("lockup picture $tag", true)
                    drawContent()
                },
        ) {
            if (picture != null) {
                AsyncImage(model = picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            content()
        }
    }
}

/**
 * Answers a bring-into-view request from inside it with its own bounds: placed outside a focus scale's layer, a request
 * from the focusable inside (a lazy row's, as focus comes) brings the lockup's place at rest into view, where the layer's
 * scaled rect would put the focused row 9 dp low and 6 dp right of the line the pivot keeps (DECISIONS 40).
 */
private class AtRestNode : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        val own = requireLayoutCoordinates()
        bringIntoView { Rect(Offset.Zero, own.size.toSize()) }
    }
}

private object BringIntoViewAtRest : ModifierNodeElement<AtRestNode>() {
    override fun create() = AtRestNode()
    override fun update(node: AtRestNode) = Unit
    override fun hashCode() = 0x41747265
    override fun equals(other: Any?) = other === this
}

/**
 * The caption under a picture (tvOS's `LockupCaption`): the title in Body, secondary at rest and white with focus (the
 * container's colour, which changes at once, as tv-material's does), and a line under it in Caption. [gap] above it
 * leaves room for the lifted picture, so the two never touch.
 */
@Composable
private fun LockupCaption(
    title: String,
    width: Dp,
    probeName: String,
    titleTag: String? = null,
    centred: Boolean = false,
    titleLines: Int = 1,
    line: @Composable () -> Unit = {},
) {
    ReportRecomposition(probeName)
    Column(
        Modifier
            .width(width)
            .padding(top = Metrics.CaptionGap),
        horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Text(
            title,
            style = HitherType.Body.copy(textAlign = if (centred) TextAlign.Center else TextAlign.Start),
            maxLines = titleLines,
            overflow = TextOverflow.Ellipsis,
            modifier = if (titleTag != null) Modifier.testTag(titleTag) else Modifier,
        )
        line()
    }
}

/**
 * An issue's status as its glyph and words (tvOS's `IssueStatusLabel`): Seerr's warning yellow with an alert for Open,
 * its green with a tick for Resolved, drawn as every status glyph is (rule 4). Tagged `issue status <words>`.
 */
@Composable
private fun IssueStatusLabel(open: Boolean, words: String, modifier: Modifier = Modifier) {
    Row(
        modifier.testTag("issue status $words"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Glyph(if (open) HitherIcons.AlertCircle else HitherIcons.Check, if (open) BadgeKind.WARNING else BadgeKind.SUCCESS)
        BasicText(words, style = HitherType.Caption.copy(color = Tokens.Text), maxLines = 1, modifier = Modifier.padding(end = 2.dp))
    }
}

/**
 * A caption's line that falls back to its most useful part (tvOS's `ViewThatFits`): [text] where it fits the caption's
 * width, else [short] ("2 days ago" for "2 days ago by User 1"), cut where even that does not fit (a long issue kind on a
 * narrow poster): `ViewThatFits` takes its last choice when none fits. Measured, never recomposed for it.
 * Tagged [tag] with the whole line as its words, whichever is drawn, so TalkBack and the tests read the whole.
 */
@Composable
private fun FittingLine(text: String, short: String?, tag: String) {
    if (short == null || short == text) {
        CaptionLine(text, tag)
        return
    }
    val style = HitherType.Caption.copy(color = Tokens.TextSecondary)
    Layout(
        contents = listOf(
            { BasicText(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            { BasicText(short, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        ),
        modifier = Modifier.testTag(tag).clearAndSetSemantics { this.text = AnnotatedString(text) },
    ) { (whole, part), constraints ->
        val loose = constraints.copy(minWidth = 0)
        val wide = whole.first().measure(loose.copy(maxWidth = Constraints.Infinity))
        val drawn = if (wide.width <= constraints.maxWidth) wide else part.first().measure(loose)
        layout(drawn.width, drawn.height) { drawn.place(0, 0) }
    }
}

/** A caption's line under the title: Caption, secondary, one line. */
@Composable
private fun CaptionLine(text: String, tag: String, centred: Boolean = false) {
    BasicText(
        text,
        style = HitherType.Caption.copy(color = Tokens.TextSecondary, textAlign = if (centred) TextAlign.Center else TextAlign.Start),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag(tag),
    )
}

/** The caption's colours: secondary at rest, white with focus (tvOS's `.secondary` and `.primary`). */
@Composable
private fun captionColours() = CardContainerDefaults.contentColor(contentColor = Tokens.TextSecondary, focusedContentColor = Tokens.Text)

/**
 * A title as a poster lockup (tvOS's `TitleCardView`): the poster with the title's status glyph in its corner, the title
 * and the year under it, and the kind of title where the row mixes films and series ([mixed]). Select opens the title;
 * TalkBack reads the title, then the overview (the focused card's overview went, Tom's Q4). [subtitle] replaces the year
 * (a person's part in a credit). A list's lockup ([status], A6) has the status's glyph and word under the title instead,
 * and no glyph on the poster. Tagged `card <key>` on the card that takes focus ([tag] where the key is not unique).
 */
@Composable
fun PosterLockup(
    card: TitleCard,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    mixed: Boolean = false,
    subtitle: String? = null,
    status: LockupStatus? = null,
    tag: String = "card ${card.key}",
    /** A long press of Select, or the Menu key: the lockup's menu (A6's `LockupMenu`), where it has one. */
    onLongClick: (() -> Unit)? = null,
) {
    ReportRecomposition("card view ${card.key}")
    val click = rememberLockupClick(onSelect, onLongClick = onLongClick)
    val context = LocalContext.current
    val poster = remember(card.poster, context) {
        card.poster?.let { ImageRequest.Builder(context).data(it).size(POSTER_WIDTH_PX, POSTER_HEIGHT_PX).build() }
    }
    StandardCardContainer(
        imageCard = {
            LockupCard(
                click = click,
                tag = tag,
                description = card.title,
                state = card.overview,
                modifier = modifier,
                width = Metrics.PosterWidth,
                height = Metrics.PosterHeight,
                shape = ImageShape,
                picture = poster,
                onFocused = onFocused,
            ) {
                if (status == null) card.badge?.let { StatusGlyph(it, Modifier.align(Alignment.TopEnd).padding(GlyphInset)) }
            }
        },
        title = {
            LockupCaption(card.title, Metrics.PosterWidth, "card caption ${card.key}", titleTag = "card title") {
                when {
                    status != null -> {
                        val badge = status.badge
                        if (badge != null) StatusLabel(badge, Modifier.padding(top = 2.dp)) else IssueStatusLabel(status.open, status.words, Modifier.padding(top = 2.dp))
                        status.line?.let { FittingLine(it, status.short, "card line") }
                    }
                    else -> (subtitle ?: lockupSubtitle(card, mixed))?.let { CaptionLine(it, "card subtitle") }
                }
            }
        },
        contentColor = captionColours(),
        // the whole lockup, picture and caption, for the tests
        modifier = Modifier.testTag("lockup $tag"),
        interactionSource = click.interaction,
    )
}

/** A status glyph's inset from the poster's corner (tvOS's 12 points). */
private val GlyphInset = 6.dp

/**
 * A 16:9 lockup (tvOS's backdrop lockups: Recent Requests, a collection): the picture, the title under it, and [line]
 * under the title. [title] null draws a placeholder bar where the title will be (a list reads names after the list).
 */
@Composable
fun BackdropLockup(
    picture: String?,
    title: String?,
    description: String,
    onSelect: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    titleTag: String? = null,
    line: @Composable () -> Unit = {},
) {
    ReportRecomposition("backdrop view $tag")
    val click = rememberLockupClick(onSelect)
    val context = LocalContext.current
    val request = remember(picture, context) {
        picture?.let { ImageRequest.Builder(context).data(it).size(BACKDROP_WIDTH_PX, BACKDROP_HEIGHT_PX).build() }
    }
    StandardCardContainer(
        imageCard = {
            LockupCard(
                click = click,
                tag = tag,
                description = description,
                modifier = modifier,
                width = Metrics.BackdropWidth,
                height = Metrics.BackdropHeight,
                shape = ImageShape,
                picture = request,
                onFocused = onFocused,
            )
        },
        title = {
            if (title != null) {
                LockupCaption(title, Metrics.BackdropWidth, "backdrop caption $tag", titleTag = titleTag, line = line)
            } else {
                Column(Modifier.width(Metrics.BackdropWidth).padding(top = Metrics.CaptionGap)) {
                    Box(Modifier.size(Metrics.BackdropWidth * 0.6f, 14.dp).clip(CircleShape).background(Tokens.Placeholder))
                }
            }
        },
        contentColor = captionColours(),
        // the whole lockup, picture and caption, for the tests
        modifier = Modifier.testTag("lockup $tag"),
        interactionSource = click.interaction,
    )
}

/**
 * A person as a portrait lockup (tvOS's `PersonCardView`): the photo in a circle, or their initials where there is none,
 * the name and the part under it, centred. The circle takes tv-material's focus in its own shape. Tagged `person <key>`.
 */
@Composable
fun PortraitLockup(
    key: String,
    name: String,
    part: String?,
    photo: String?,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
) {
    ReportRecomposition("cast card $key")
    val click = rememberLockupClick(onSelect)
    val context = LocalContext.current
    val request = remember(photo, context) {
        photo?.let { ImageRequest.Builder(context).data(it).size(PORTRAIT_PX, PORTRAIT_PX).build() }
    }
    StandardCardContainer(
        imageCard = {
            Box(Modifier.width(PortraitLockupWidth), contentAlignment = Alignment.Center) {
                LockupCard(
                    click = click,
                    tag = "person $key",
                    description = name,
                    state = part,
                    modifier = modifier,
                    width = Metrics.Portrait,
                    height = Metrics.Portrait,
                    shape = CircleShape,
                    picture = request,
                    onFocused = onFocused,
                ) {
                    if (request == null) {
                        Text(
                            initials(name),
                            style = HitherType.Title.copy(color = Tokens.TextSecondary, fontWeight = FontWeight.Medium),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        },
        title = {
            LockupCaption(name, PortraitLockupWidth, "cast caption $key", titleTag = "person name", centred = true, titleLines = 2) {
                part?.let { CaptionLine(it, "person part", centred = true) }
            }
        },
        contentColor = captionColours(),
        // the whole lockup, picture and caption, for the tests
        modifier = Modifier.testTag("lockup person $key"),
        interactionSource = click.interaction,
    )
}

/** The first letters of the first and last names (tvOS's). */
private fun initials(name: String): String {
    val words = name.split(' ').filter { it.isNotBlank() }
    return listOfNotNull(words.firstOrNull(), words.drop(1).lastOrNull()).mapNotNull { it.firstOrNull() }.joinToString("").uppercase()
}

/**
 * A lockup's place while its shelf loads (tvOS's `PlaceholderCard`): the picture's shape in the placeholder grey and a
 * caption's bar. Still, so a loading page costs nothing per frame (the pulse went). A loading row is one focus target
 * (DECISIONS 42): [focused] draws its first place as a focused lockup is drawn, the white edge and the 1.1 scale, in the
 * layer phase.
 */
@Composable
fun PlaceholderLockup(
    modifier: Modifier = Modifier,
    width: Dp = Metrics.PosterWidth,
    height: Dp = Metrics.PosterHeight,
    focused: Boolean = false,
    shape: Shape = ImageShape,
) {
    Column(modifier.width(width)) {
        Box(
            Modifier
                .focusScale(focused, scale = Metrics.FocusScale)
                .size(width, height)
                .clip(shape)
                .background(Tokens.Placeholder)
                .then(if (focused) Modifier.border(Metrics.LockupEdge, Tokens.Text, shape) else Modifier),
        )
        Spacer(Modifier.height(Metrics.CaptionGap + 3.dp))
        Box(Modifier.size(width * 0.6f, 14.dp).clip(CircleShape).background(Tokens.Placeholder))
    }
}

/**
 * A shelf's header (tvOS's `ShelfTitle`): titleMedium, in the words Seerr gives the row, with [Metrics.AboveShelf] over it
 * and [Metrics.BelowShelfTitle] under it, so a lifted lockup clears it (tvOS's F31). [Metrics.ShelfHeader] tall.
 */
@Composable
fun ShelfTitle(title: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Spacer(Modifier.height(Metrics.AboveShelf))
        Text(
            title,
            style = HitherType.Subtitle,
            color = Tokens.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = Metrics.SideMargin)
                .height(Metrics.ShelfTitleLine)
                .testTag("row title"),
        )
        Spacer(Modifier.height(Metrics.BelowShelfTitle))
    }
}

/**
 * A shelf's lockups side by side (tvOS's `Shelf`): Compose's `LazyRow`, [Metrics.LockupGap] apart inside the side
 * margins, scrolling sideways as focus moves along them (the caller's `BringIntoViewSpec` keeps focus on the first
 * column, DECISIONS 40). Coming into it lands on [entry], the lockup it was left on. A lifted lockup is never clipped:
 * a lazy row clips only along its own axis, and the 1.1 scale grows a poster 9 dp up, within what it lets through.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Shelf(
    state: LazyListState,
    entry: FocusRequester,
    modifier: Modifier = Modifier,
    height: Dp = PosterLockupHeight,
    content: LazyListScope.() -> Unit,
) {
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = Metrics.SideMargin),
        horizontalArrangement = Arrangement.spacedBy(Metrics.LockupGap),
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .focusProperties { onEnter = { entry.requestFocus() } }
            .focusGroup(),
        content = content,
    )
}

/**
 * A lockup's place in a shelf: the shelf's [entry] when it is the one left ([isEntry]), and fenced at the shelf's ends
 * (REVIEW.md: a row's end does not jump to another row).
 */
fun Modifier.shelfPlace(isEntry: Boolean, entry: FocusRequester, first: Boolean, last: Boolean): Modifier =
    then(if (isEntry) Modifier.focusRequester(entry) else Modifier)
        .focusProperties {
            if (first) left = FocusRequester.Cancel
            if (last) right = FocusRequester.Cancel
        }

/**
 * A lockup-sized card that is not a title (a row's See More, a later page's Try Again): tv-material's `Card` on
 * [onSelect]'s rules, [face] drawn on it, [caption] under it. Focus as a lockup's.
 */
@Composable
fun LockupButton(
    onSelect: () -> Unit,
    tag: String,
    description: String,
    caption: String?,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    busy: Boolean = false,
    face: @Composable BoxScope.() -> Unit,
) {
    ReportRecomposition("lockup button $tag")
    val click = rememberLockupClick(onSelect, busy = busy)
    StandardCardContainer(
        imageCard = {
            LockupCard(
                click = click,
                tag = tag,
                description = description,
                modifier = modifier,
                width = Metrics.PosterWidth,
                height = Metrics.PosterHeight,
                shape = ImageShape,
                picture = null,
                onFocused = onFocused,
                content = face,
            )
        },
        title = { if (caption != null) LockupCaption(caption, Metrics.PosterWidth, "lockup caption $tag") },
        contentColor = captionColours(),
        // the whole lockup, picture and caption, for the tests
        modifier = Modifier.testTag("lockup $tag"),
        interactionSource = click.interaction,
    )
}

/**
 * A card of words rather than a picture (tvOS's card button: a series' season still to ask for, A4): tv-material's
 * `Card` on the lockups' rules, [width] by [height] in the placeholder grey, [content] drawn on it, no caption under it.
 * Focus as a lockup's (the white edge, the 1.1 in the layer phase, its place at rest brought into view). Tagged [tag];
 * TalkBack reads [description], then [state].
 */
@Composable
fun WordsCard(
    onSelect: () -> Unit,
    tag: String,
    description: String,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    state: String? = null,
    onFocused: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    ReportRecomposition("words card $tag")
    val click = rememberLockupClick(onSelect)
    LockupCard(
        click = click,
        tag = tag,
        description = description,
        modifier = modifier,
        width = width,
        height = height,
        shape = ImageShape,
        picture = null,
        onFocused = onFocused,
        state = state,
        content = content,
    )
}

/**
 * For a focusable of Hither's own scale in a pivoted list (A4's keyword chips): put it outside the scale's layer, and the
 * list brings the control's place at rest into view, not its scaled rect (DECISIONS 40's line; A3's lockups do the same).
 */
fun Modifier.bringIntoViewAtRest(): Modifier = then(BringIntoViewAtRest)

/** Who asked, under a request's title (tvOS's `Requester`): the person glyph and the display name, secondary. */
@Composable
fun RequesterLine(name: String, modifier: Modifier = Modifier) {
    Row(modifier.testTag("recent requester"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        com.tomdebres.hither.ui.components.UserGlyph(Modifier.size(12.dp))
        BasicText(name, style = HitherType.Caption.copy(color = Tokens.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
