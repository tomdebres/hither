package com.tomdebres.hither.data

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.tomdebres.hither.core.rules.ImageSource

/**
 * Where TMDB's image server is, in a **debug** build (0.7.1, DECISIONS 382): pictures come from there while the server
 * keeps no copies (Image Caching off). TMDB itself, unless a test or a scripted run named the stand-in that
 * `scripts/fake-seerr` serves (`/tmdb`), by `adb shell am start -n com.tomdebres.hither/.MainActivity --es
 * com.tomdebres.hither.TMDB_IMAGES http://10.0.2.2:5056/tmdb` (an empty value goes back to TMDB). Kept, as
 * [PlexAddress]'s switch is, in the debug-only preferences file. The release build's own `TmdbAddress` has no such
 * switch: it is always TMDB (`scripts/release-tmdb-check` checks the built APK).
 */
object TmdbAddress {
    const val EXTRA = "com.tomdebres.hither.TMDB_IMAGES"
    private const val PREFS = "hither-debug-switches"
    private const val KEY = "tmdb_images"

    fun base(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { it.isNotBlank() }
            ?: ImageSource.TMDB_IMAGES

    /** A launch's extra, kept for later launches. */
    fun take(context: Context, intent: Intent?) {
        val named = intent?.getStringExtra(EXTRA) ?: return
        set(context, named)
    }

    /** For device tests: the stand-in's address, or empty for TMDB again. */
    fun set(context: Context, address: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { putString(KEY, address) }
    }
}
