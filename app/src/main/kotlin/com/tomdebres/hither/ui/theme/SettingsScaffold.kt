package com.tomdebres.hither.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.R
import com.tomdebres.hither.ui.components.HitherIcons

/** What the left of a Settings page shows ([SettingsScaffold]). */
enum class SettingsMark {
    /** Hither's stacked logo: Settings, About, Diagnostics. */
    LOGO,

    /** The approver's person and shield, as the account reads while the admin is active: Admin Account kept. */
    APPROVER,
}

/**
 * Settings' pages as the Apple TV app draws its Settings (H2's X5; tvOS's `SettingsScaffold`): a picture centred in the
 * left of the page ([mark]), and on the right a list [Metrics.SettingsList] wide to the right safe edge: rows of words,
 * a value on the right where there is one, a chevron where a row opens a page ([HitherListItem], [SettingsValueRow]),
 * a destructive row in red words, notes as a section's footer ([SettingsSection]). Settings, About, Diagnostics and
 * Admin Account use it, inside their `Page`, which sets the edges and the title.
 *
 * The list is lazy and scrolls as focus moves down it (Diagnostics' fifty failures); Up and Down stay in it, and Left
 * and Right go nowhere (the picture takes no focus). [content] adds its sections as items.
 */
@Composable
fun SettingsScaffold(
    modifier: Modifier = Modifier,
    mark: SettingsMark = SettingsMark.LOGO,
    markTag: String = "settings mark",
    listTag: String = "settings list",
    content: LazyListScope.() -> Unit,
) {
    Row(modifier.fillMaxSize().testTag("settings scaffold")) {
        // The left of the page, what the list leaves of it ([Metrics.SettingsMarkArea] at most): the picture in its middle.
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            when (mark) {
                SettingsMark.LOGO -> Image(
                    painterResource(R.drawable.hither_logo_stacked),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.width(Metrics.SettingsMark).testTag(markTag),
                )
                SettingsMark.APPROVER -> ApproverMark(Modifier.size(Metrics.SettingsMark * 0.8f).testTag(markTag))
            }
        }
        Spacer(Modifier.width(Metrics.SettingsGap))
        LazyColumn(
            modifier = Modifier.width(Metrics.SettingsList).fillMaxHeight().testTag(listTag),
            contentPadding = PaddingValues(top = 4.dp, bottom = Metrics.BottomMargin),
            content = content,
        )
    }
}

/**
 * A group of rows: an optional [header] over them (Caption, grey), the rows [RowGap] apart, an optional [footer] under
 * them (a note: what the rows above it do, in grey). Sections are [SectionGap] apart.
 */
@Composable
fun SettingsSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    headerTag: String = "section header",
    footer: String? = null,
    footerTag: String = "section footer",
    rows: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(bottom = SectionGap)) {
        if (header != null) SettingsHeader(header, headerTag)
        Column(verticalArrangement = Arrangement.spacedBy(RowGap), content = rows)
        if (footer != null) SettingsFooter(footer, footerTag)
    }
}

/** A section's header: what the rows under it are about, small and grey. */
@Composable
fun SettingsHeader(text: String, tag: String = "section header") {
    BasicText(
        text,
        style = HitherType.Caption.copy(color = Tokens.TextSecondary),
        modifier = Modifier.padding(start = RowInset, end = RowInset, bottom = 8.dp).testTag(tag),
    )
}

/** A section's footer: a note on the rows above it (tvOS's section footer), grey, as wide as the list. */
@Composable
fun SettingsFooter(text: String, tag: String = "section footer") {
    BasicText(
        text,
        style = HitherType.Caption.copy(color = Tokens.TextSecondary),
        modifier = Modifier.fillMaxWidth().padding(start = RowInset, end = RowInset, top = 8.dp).testTag(tag),
    )
}

/**
 * A row that says something and does nothing (tvOS's `LabeledContent`): [label] on the left, [value] on the right with
 * [detail] under it (the server's address under its name), in a row's resting grey. It takes no focus, and a screen
 * reader reads its words one by one, as it read Settings' account lines before A7 ([tag] is on the row; [labelTag] and
 * [valueTag] on the words inside it, which tests and other pages' tests find as they found the account's name).
 */
@Composable
fun SettingsValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tag: String = label,
    detail: String? = null,
    labelTag: String? = null,
    valueTag: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RowShape)
            .background(Tokens.Field)
            .testTag(tag)
            .padding(horizontal = RowInset, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BasicText(
            label,
            style = HitherType.Body.copy(color = Tokens.Text),
            maxLines = 1,
            modifier = if (labelTag != null) Modifier.testTag(labelTag) else Modifier,
        )
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            BasicText(
                value,
                style = HitherType.Body.copy(color = Tokens.TextSecondary, textAlign = TextAlign.End),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (valueTag != null) Modifier.testTag(valueTag) else Modifier,
            )
            if (detail != null) {
                BasicText(
                    detail,
                    style = HitherType.Caption.copy(color = Tokens.TextSecondary, textAlign = TextAlign.End),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The approver's picture (tvOS's person with a shield): Hither's person, grey, a shield at its foot on the page's black. */
@Composable
private fun ApproverMark(modifier: Modifier) {
    Box(modifier) {
        Image(HitherIcons.User, contentDescription = null, modifier = Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(Tokens.TextSecondary))
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(Metrics.SettingsMark * 0.36f)
                .clip(CircleShape)
                .background(Tokens.Background)
                .padding(Metrics.SettingsMark * 0.04f),
        ) {
            Image(HitherIcons.Shield, contentDescription = null, modifier = Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(Tokens.TextSecondary))
        }
    }
}

/** Between a row's edge and its words: tv-material's list item's own inset. */
val RowInset = 16.dp

/** Between the rows of a section, and after a section. */
val RowGap = 8.dp
val SectionGap = 24.dp
