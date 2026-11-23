package com.tomdebres.hither.ui.components

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat

/**
 * Hither's window: the on-screen keyboard is drawn over the page and reported to Compose as an inset
 * (`WindowInsets.ime`), instead of the window being panned up under it. Panned, the open field sat
 * half under the keyboard and a black band showed below the page beside it (DECISIONS 32, the polish
 * audit's item 6); now a screen that types moves its own open field above the keyboard (the sign-in
 * form does), and the page's own colour is everywhere. A TV has no system bars, so nothing else moves.
 */
fun ComponentActivity.keyboardAsInset() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
}
