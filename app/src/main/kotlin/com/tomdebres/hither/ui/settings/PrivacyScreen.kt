package com.tomdebres.hither.ui.settings

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.tomdebres.hither.R
import com.tomdebres.hither.ui.theme.Metrics
import com.tomdebres.hither.ui.theme.Page
import com.tomdebres.hither.ui.theme.PageKind
import com.tomdebres.hither.ui.theme.ReadingText
import com.tomdebres.hither.ui.theme.ReadingView
import com.tomdebres.hither.ui.theme.settleFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The privacy text, `docs/privacy.md` as the build bundled it, as the Apple TV app reads a long text (A5, H2's X7; tvOS's
 * Privacy, one `ReadingView`): a pushed page titled Privacy, and under it the whole policy as one stop that takes focus
 * as it comes; Down and Up turn it a view at a time, to its end; Back leaves (the home's). It was a focus stop per
 * paragraph, each taking a press.
 */
@Composable
fun PrivacyScreen() {
    val context = LocalContext.current
    val text by produceState<ReadingText?>(null) {
        value = withContext(Dispatchers.IO) {
            privacyText(parsePrivacy(context.assets.open("privacy.md").bufferedReader().use { it.readText() }))
        }
    }
    val entry = remember { FocusRequester() }
    val loaded = text != null
    LaunchedEffect(loaded) { if (loaded) settleFocus { entry } }
    Page(PageKind.Pushed(stringResource(R.string.privacy), titleTag = "privacy heading")) {
        text?.let {
            ReadingView(
                text = it,
                tag = "privacy text",
                focusRequester = entry,
                modifier = Modifier
                    .width(Metrics.ReadingWidth)
                    .fillMaxHeight()
                    .padding(bottom = Metrics.BottomMargin),
            )
        }
    }
}

/**
 * The policy's blocks as a [ReadingView]'s text: its own title ("Privacy") is the page's; headings, paragraphs and
 * bullets as the file has them, bold where it is.
 */
fun privacyText(blocks: List<PrivacyBlock>): ReadingText = blocks.fold(ReadingText()) { text, block ->
    when (block) {
        is PrivacyBlock.Title -> text
        is PrivacyBlock.Heading -> text.heading(block.text)
        is PrivacyBlock.Paragraph -> text.paragraph(block.runs.reading())
        is PrivacyBlock.Bullet -> text.bullet(block.runs.reading())
    }
}

private fun List<Run>.reading(): List<ReadingText.Run> = map { ReadingText.Run(it.text, it.bold) }
