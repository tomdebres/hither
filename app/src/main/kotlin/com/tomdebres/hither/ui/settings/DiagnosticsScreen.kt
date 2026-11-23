package com.tomdebres.hither.ui.settings

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.tomdebres.hither.R
import com.tomdebres.hither.core.DIAGNOSTICS_KEPT
import com.tomdebres.hither.core.Failure
import com.tomdebres.hither.core.FailureLog
import com.tomdebres.hither.ui.components.ReportRecomposition
import com.tomdebres.hither.ui.theme.Formats
import com.tomdebres.hither.ui.theme.HitherListItem
import com.tomdebres.hither.ui.theme.HitherType
import com.tomdebres.hither.ui.theme.Page
import com.tomdebres.hither.ui.theme.PageKind
import com.tomdebres.hither.ui.theme.RowGap
import com.tomdebres.hither.ui.theme.RowValue
import com.tomdebres.hither.ui.theme.SettingsHeader
import com.tomdebres.hither.ui.theme.SettingsScaffold

/**
 * About › Diagnostics (M6, DECISIONS 107; tvOS's `DiagnosticsView`, S41) as a Settings list (`SettingsScaffold`): the
 * failures Hither recovered from since it started, newest first, each a row to read by the remote: what ("HTTP 500")
 * and when, the call ("GET /movie/123") under it. For Tom, not the family; never the server's address, a query or a
 * body. The list as it was when the page opened, so a failure while it is open does not move the rows under focus; with
 * none, a row saying so takes focus (the polish 2 audit's item 34: focus never lands on nothing).
 */
@Composable
fun DiagnosticsScreen(log: FailureLog) {
    val shown by log.failures.collectAsState()
    val failures = remember { shown }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(5) {
            withFrameNanos { }
            if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    // Rows that do nothing on Select: a failure is read, not opened.
    val fenced = Modifier.focusProperties {
        left = FocusRequester.Cancel
        right = FocusRequester.Cancel
    }
    Page(PageKind.Pushed(stringResource(R.string.diagnostics), titleTag = "diagnostics heading")) {
        SettingsScaffold(listTag = "diagnostics") {
            item(key = "about") {
                // A sentence, not a label: the section's header.
                SettingsHeader(stringResource(R.string.diagnostics_about, DIAGNOSTICS_KEPT), tag = "diagnostics about")
            }
            if (failures.isEmpty()) {
                item(key = "none") {
                    HitherListItem(
                        stringResource(R.string.diagnostics_none),
                        onClick = {},
                        modifier = fenced.focusRequester(first),
                        tag = "diagnostics none",
                    )
                }
            }
            itemsIndexed(failures, key = { index, _ -> index }) { index, failure ->
                if (index > 0) Spacer(Modifier.height(RowGap))
                FailureRow(
                    failure = failure,
                    at = Formats.dateTime(failure.atMillis),
                    modifier = fenced.then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                    tag = "failure $index",
                )
            }
        }
    }
}

/** What and when on the row's line, the call under it. */
@Composable
private fun FailureRow(failure: Failure, at: String, modifier: Modifier, tag: String) {
    ReportRecomposition(tag)
    HitherListItem(
        headline = failure.what,
        onClick = {},
        modifier = modifier,
        tag = tag,
        headlineWeight = FontWeight.SemiBold,
        supporting = "${failure.method} ${failure.path}",
        trailing = { RowValue(at, style = HitherType.Caption) },
    )
}
