package com.tomdebres.hither.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.R
import com.tomdebres.hither.ui.theme.HitherType
import com.tomdebres.hither.ui.theme.Tokens

/** The room the badge takes top right, beside the avatar: pages that put something there leave this much. */
val AdminBadgeRoom = 84.dp

/**
 * While the TV's admin account is active (M5, DECISIONS 93), "Admin" with a shield, left of the avatar on the bar and
 * top right over every other page (DECISIONS 109). Seerr's indigo role badge went with A1 (rule 1: indigo is the
 * prominent button's alone): a grey capsule, white words, as tvOS's account tab reads "Admin" with a shield. A2 moves
 * it into the bar's account tab. It takes no focus; a screen reader says "Admin mode: {name}" once as it comes.
 */
@Composable
fun AdminBadge(name: String, modifier: Modifier = Modifier) {
    val said = stringResource(R.string.admin_mark_said, name)
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(Tokens.Surface)
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .testTag("admin badge")
            .semantics(mergeDescendants = true) {
                contentDescription = said
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Image(HitherIcons.Shield, contentDescription = null, modifier = Modifier.size(12.dp), colorFilter = ColorFilter.tint(Tokens.Text))
        BasicText(stringResource(R.string.admin_badge), style = HitherType.Caption.copy(color = Tokens.Text, fontWeight = FontWeight.SemiBold))
    }
}
