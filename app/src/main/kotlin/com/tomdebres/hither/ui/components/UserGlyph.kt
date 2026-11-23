package com.tomdebres.hither.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.ui.theme.Tokens

/**
 * The account's picture: a person's head and shoulders, white, in the avatar's 32 dp disc (Tom, 2026-11-12;
 * DECISIONS 103). Seerr gives a local user Gravatar's grey person ("mm") where the address has none; Hither
 * cannot fetch it (another host, 46), so it draws its own, the same on every account.
 */
@Composable
fun UserGlyph(modifier: Modifier = Modifier) {
    Image(
        HitherIcons.User,
        contentDescription = null,
        modifier = modifier.size(18.dp).testTag("user glyph"),
        colorFilter = ColorFilter.tint(Tokens.Text),
    )
}
