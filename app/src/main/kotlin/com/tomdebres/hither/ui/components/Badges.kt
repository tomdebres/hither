package com.tomdebres.hither.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.tomdebres.hither.R
import com.tomdebres.hither.core.rules.DownloadProgress
import com.tomdebres.hither.ui.theme.Formats
import com.tomdebres.hither.ui.theme.HitherType
import com.tomdebres.hither.ui.theme.Tokens

/**
 * Seerr's DownloadBlock's line under a running download: "Estimated in 42 minutes", relative to now ([Formats.due]), in
 * the words the lists' lockups use (`download_estimate`, A8); nothing when the download client gave no time. [nowMs] is
 * read where it is shown.
 */
@Composable
fun DownloadEstimate(progress: DownloadProgress, nowMs: Long, modifier: Modifier = Modifier) {
    val relative = Formats.due(progress.estimatedAt, nowMs) ?: return
    BasicText(
        stringResource(R.string.download_estimate, relative.replaceFirstChar { it.lowercase() }),
        style = HitherType.Caption.copy(color = Tokens.TextSecondary),
        maxLines = 1,
        modifier = modifier.testTag("download estimate"),
    )
}
