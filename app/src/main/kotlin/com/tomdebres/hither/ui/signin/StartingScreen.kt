package com.tomdebres.hither.ui.signin

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tomdebres.hither.R
import com.tomdebres.hither.ui.theme.Page
import com.tomdebres.hither.ui.theme.PageKind
import com.tomdebres.hither.ui.theme.Spinner
import kotlinx.coroutines.delay

/** How long the launch waits before it says it is working: most launches are done by then. */
const val SPINNER_AFTER_MS = 500L

/**
 * The launch, while the stored session is checked (`Screen.Starting`): the stacked logo in the
 * middle of the black page, as sign-in heads its card with it (DECISIONS 72, 290), and after
 * [SPINNER_AFTER_MS] Hither's spinner under it (`Spinner`, A1: tv-material has none). It was an empty page, which a
 * slow server left on the TV for seconds (the polish audit's item 26; Tom, DECISIONS 80). Nothing here takes focus: the
 * next screen brings its own.
 */
@Composable
fun StartingScreen() {
    var spinning by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SPINNER_AFTER_MS)
        spinning = true
    }
    Page(PageKind.Screen) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painterResource(R.drawable.hither_logo_stacked),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.height(128.dp).testTag("starting logo"),
            )
            Spacer(Modifier.height(24.dp))
            // The spinner's room is kept from the start, so the logo does not move when it comes.
            Box(Modifier.size(32.dp)) {
                if (spinning) Spinner(Modifier.fillMaxSize().testTag("starting spinner"))
            }
        }
    }
}
