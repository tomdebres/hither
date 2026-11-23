package com.tomdebres.hither.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.R
import com.tomdebres.hither.ui.components.EntryLift
import com.tomdebres.hither.ui.components.LocalEntryLift

/**
 * Where something is typed (H2's X4; tvOS's `EntryColumn`): one centred column [Metrics.EntryWidth] wide, as the Apple
 * TV app's sign-in screens are. Hither's mark small ([mark], [Metrics.EntryMark] wide), the [title], a line of help
 * ([detail]); the [fields] (`EntryField`s, each with its error as red words under it); then the [action]: the screen's
 * one prominent button and what went wrong with it under it ([EntryProblem]). The family's sign-in, Jellyfin's and
 * Emby's, and the approver's use it.
 *
 * - Pinned at its top ([top] from the content's top) and growing down: an error appearing under a field moves what is
 *   under it, never what is above it (the polish audit's item 7). It scrolls as focus moves when it is taller than the
 *   screen (an error under every field and the other ways in), so focus always reaches its foot.
 * - [methods]: the other ways in, under the action (M8: Plex, Jellyfin or Emby), led by [methodsLead] ("Other ways to
 *   sign in"; none when they are the only way in), in a row of plain buttons.
 * - [lift]: the keyboard lift the page is on (`liftAboveKeyboard`), handed to the fields so the open one stays above the
 *   keyboard.
 */
@Composable
fun EntryColumn(
    title: String,
    lift: EntryLift,
    modifier: Modifier = Modifier,
    mark: Boolean = true,
    top: Dp = 0.dp,
    titleTag: String = "heading",
    detail: String? = null,
    detailTag: String = "$titleTag detail",
    methodsLead: String? = null,
    methods: (@Composable RowScope.() -> Unit)? = null,
    fields: (@Composable ColumnScope.() -> Unit)? = null,
    action: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalEntryLift provides lift) {
        Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.padding(top = top, bottom = Metrics.BottomMargin).width(Metrics.EntryWidth).testTag("entry column"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (mark) {
                    Image(
                        painterResource(R.drawable.hither_logo_stacked),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.width(Metrics.EntryMark).testTag("wordmark"),
                    )
                    Spacer(Modifier.height(20.dp))
                }
                BasicText(
                    title,
                    style = HitherType.Title.copy(color = Tokens.Text, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().semantics { heading() }.testTag(titleTag),
                )
                if (detail != null) {
                    Spacer(Modifier.height(8.dp))
                    BasicText(
                        detail,
                        style = HitherType.Body.copy(color = Tokens.TextSecondary, textAlign = TextAlign.Center),
                        modifier = Modifier.fillMaxWidth().testTag(detailTag),
                    )
                }
                if (fields != null) {
                    Spacer(Modifier.height(20.dp))
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = fields)
                }
                Spacer(Modifier.height(20.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = action)
                if (methods != null) {
                    Spacer(Modifier.height(24.dp))
                    if (methodsLead != null) {
                        BasicText(
                            methodsLead,
                            style = HitherType.Caption.copy(color = Tokens.TextSecondary),
                            modifier = Modifier.testTag("or sign in with"),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), content = methods)
                }
            }
        }
    }
}

/**
 * What went wrong, in words, under what it is about (tvOS's `EntryProblem`; the review's F8: errors drawn as form rows
 * looked like controls): red ([Tokens.ErrorText], 4.6:1), nothing behind them, at the start of the column.
 */
@Composable
fun EntryProblem(text: String, tag: String, modifier: Modifier = Modifier) {
    BasicText(
        text,
        style = HitherType.Body.copy(color = Tokens.ErrorText),
        modifier = modifier.fillMaxWidth().padding(horizontal = Metrics.EntryFieldInset).testTag(tag),
    )
}

/** A quiet line among the fields ("Checking the server…"): grey, at the field's words' inset. */
@Composable
fun EntryNote(text: String, tag: String) {
    BasicText(
        text,
        style = HitherType.Body.copy(color = Tokens.TextSecondary),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Metrics.EntryFieldInset).testTag(tag),
    )
}
