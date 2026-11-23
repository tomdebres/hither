package com.tomdebres.hither.data

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.tomdebres.hither.core.PlexTv

/**
 * Where plex.tv is, in a **debug** build (M8): plex.tv itself, unless a test or a scripted run named the fake plex.tv
 * that `scripts/fake-seerr` serves (`/plextv`), by `adb shell am start -n com.tomdebres.hither/.MainActivity --es
 * com.tomdebres.hither.PLEX_TV http://10.0.2.2:5056/plextv` (an empty value goes back to plex.tv). The release build's
 * own `PlexAddress` has no such switch: it is always plex.tv (`scripts/release-plex-check` checks the built APK).
 */
object PlexAddress {
    const val EXTRA = "com.tomdebres.hither.PLEX_TV"
    private const val PREFS = "hither-debug-switches"
    private const val KEY = "plex_tv"

    fun base(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { it.isNotBlank() } ?: PlexTv.PLEX_TV

    /** A launch's extra, kept for later launches. */
    fun take(context: Context, intent: Intent?) {
        val named = intent?.getStringExtra(EXTRA) ?: return
        set(context, named)
    }

    /** For device tests: the fake plex.tv's address, or empty for plex.tv again. */
    fun set(context: Context, address: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { putString(KEY, address) }
    }
}
