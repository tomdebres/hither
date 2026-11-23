package com.tomdebres.hither.ui.theme

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.ui.components.FocusOnArrival
import kotlinx.coroutines.launch

// Long text at ten feet (H2's X7; tvOS's `ReadingView.swift`): `ReadingView`, the text that turns a page at a time under
// the remote; `ReadingScreen`, the whole of a text over the page it came from; and `ReadingText`, what they read. The
// few lines that open them are `TextStop`. A4 brings them for the title page's overview and a person's biography; A5
// builds Privacy on them, so nothing here knows a title or a person.

/** A block of a [ReadingView]'s text. */
sealed interface ReadingBlock {
    data class Heading(val words: String) : ReadingBlock

    data class Paragraph(val words: AnnotatedString, val italic: Boolean) : ReadingBlock

    data class Bullet(val words: AnnotatedString) : ReadingBlock
}

/**
 * Text for a [ReadingView], built a block at a time (tvOS's `ReadingText`): a heading, a paragraph (italic where asked),
 * a bullet; a run bold where asked. Drawn in the theme's text styles only.
 */
class ReadingText private constructor(val blocks: List<ReadingBlock>) {
    constructor() : this(emptyList())

    /** A stretch of a block, bold or not. */
    data class Run(val text: String, val bold: Boolean = false)

    fun heading(words: String): ReadingText = adding(ReadingBlock.Heading(words))

    fun paragraph(words: String, italic: Boolean = false): ReadingText = paragraph(listOf(Run(words)), italic)

    fun paragraph(runs: List<Run>, italic: Boolean = false): ReadingText = adding(ReadingBlock.Paragraph(annotated(runs), italic))

    fun bullet(runs: List<Run>): ReadingText = adding(ReadingBlock.Bullet(annotated(runs)))

    /** The words alone, a block a line: what TalkBack reads, and what the tests compare. */
    val plain: String
        get() = blocks.joinToString("\n") { block ->
            when (block) {
                is ReadingBlock.Heading -> block.words
                is ReadingBlock.Paragraph -> block.words.text
                is ReadingBlock.Bullet -> "• " + block.words.text
            }
        }

    private fun adding(block: ReadingBlock) = ReadingText(blocks + block)

    override fun equals(other: Any?): Boolean = other is ReadingText && other.blocks == blocks

    override fun hashCode(): Int = blocks.hashCode()

    private companion object {
        fun annotated(runs: List<Run>): AnnotatedString = buildAnnotatedString {
            for (run in runs) {
                if (run.bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(run.text) } else append(run.text)
            }
        }
    }
}

/**
 * Long text on the TV, read as the Apple TV app reads its own (H2's X7; tvOS's `ReadingView`): one view that takes focus
 * and moves through the text under the remote, never a column of paragraphs that each take a press. Down and Up turn
 * the view's height less two lines, the last lines seen staying as the first of the next; at either end they do
 * nothing. It draws nothing round the words and no focus effect: the text is the page.
 *
 * **How a press turns it.** `DpadHost` takes a remote's arrows ahead of everything and moves focus with them (A2), so no
 * key listener here would ever hear one. The turn happens in the focus system instead, which a remote's press and a
 * test's both go through: Up and Down from the text are sent to a line's edge above and below it, outside its focus
 * group, and the group refuses to let focus out, turning the text instead. Left and Right are refused too: focus stays
 * on the text until Back closes what holds it.
 *
 * [focusRequester] is the text's own, for whoever opens it. Tagged [tag]; TalkBack reads the whole text there.
 */
@Composable
fun ReadingView(
    text: ReadingText,
    tag: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val above = remember { FocusRequester() }
    val below = remember { FocusRequester() }
    val turner = remember(scroll) { Turner(scroll) }
    // the lines kept from one view to the next: two of Body's
    val kept = with(LocalDensity.current) { HitherType.Body.lineHeight.toPx() * 2 }
    Column(modifier) {
        Edge(above, focusRequester)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusProperties {
                    onExit = {
                        when (requestedFocusDirection) {
                            FocusDirection.Down -> scope.launch { turner.turn(1, kept) }
                            FocusDirection.Up -> scope.launch { turner.turn(-1, kept) }
                        }
                        cancelFocusChange()
                    }
                }
                .focusGroup(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusProperties {
                        up = above
                        down = below
                    }
                    .focusable()
                    .semantics(mergeDescendants = true) { }
                    .testTag(tag)
                    .verticalScroll(scroll),
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    text.blocks.forEach { ReadingBlockView(it) }
                }
            }
        }
        Edge(below, focusRequester)
    }
}

/**
 * The line just above or below a [ReadingView]'s text that Up and Down are sent to, so that its group has a move to
 * refuse (a move with nowhere to go asks no one). Nothing lands here; if anything ever did, it hands focus back.
 */
@Composable
private fun Edge(requester: FocusRequester, text: FocusRequester) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) runCatching { text.requestFocus() } }
            .focusable(),
    )
}

/** Turns [scroll] a view at a time; presses that come while it moves add up from where the last was going. */
private class Turner(private val scroll: ScrollState) {
    private var aim = 0

    suspend fun turn(direction: Int, kept: Float) {
        val from = if (scroll.isScrollInProgress) aim else scroll.value
        val page = (scroll.viewportSize - kept).toInt().coerceAtLeast(kept.toInt())
        val to = (from + direction * page).coerceIn(0, scroll.maxValue)
        if (to == from) return
        aim = to
        scroll.animateScrollTo(to, tween(300, easing = FastOutSlowInEasing))
    }
}

@Composable
private fun ReadingBlockView(block: ReadingBlock) {
    when (block) {
        is ReadingBlock.Heading -> BasicText(
            block.words,
            style = HitherType.Subtitle.copy(color = Tokens.Text, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(top = 12.dp),
        )
        is ReadingBlock.Paragraph -> BasicText(
            block.words,
            style = HitherType.Body.copy(color = Tokens.Text, fontStyle = if (block.italic) FontStyle.Italic else FontStyle.Normal),
        )
        is ReadingBlock.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // the bullet's lines hang under its words
            BasicText("•", style = HitherType.Body.copy(color = Tokens.TextSecondary))
            BasicText(block.words, style = HitherType.Body.copy(color = Tokens.Text))
        }
    }
}

/**
 * The whole of a text over the page it came from, as the Apple TV app opens a description's "More" (tvOS's
 * `ReadingScreen`): a pushed page ([Page]: the [title] centred and grey at the top margin, the text under it at the
 * side margin, [Metrics.ReadingWidth] wide) holding a [ReadingView] that takes focus as it arrives. Opaque black, so the
 * page under it is not seen and costs nothing to show through. Back calls [onClose]; the caller puts focus back where it
 * opened. Tagged [tag]; the text [textTag].
 */
@Composable
fun ReadingScreen(title: String, text: ReadingText, onClose: () -> Unit, tag: String, textTag: String) {
    BackHandler(onBack = onClose)
    val entry = remember { FocusRequester() }
    FocusOnArrival(entry)
    Page(PageKind.Pushed(title, titleTag = "reading title"), modifier = Modifier.testTag(tag)) {
        ReadingView(
            text = text,
            tag = textTag,
            focusRequester = entry,
            modifier = Modifier
                .width(Metrics.ReadingWidth)
                .fillMaxHeight()
                .padding(bottom = Metrics.BottomMargin),
        )
    }
}
