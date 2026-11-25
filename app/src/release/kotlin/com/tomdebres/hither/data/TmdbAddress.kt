package com.tomdebres.hither.data

import android.content.Context
import android.content.Intent
import com.tomdebres.hither.core.rules.ImageSource

/**
 * Where TMDB's image server is, in a **release** build (0.7.1, DECISIONS 382): TMDB, always. The debug build's
 * `TmdbAddress` may be pointed at a stand-in for tests; this one takes no address from anywhere
 * (`scripts/release-tmdb-check` checks the built APK).
 */
object TmdbAddress {
    @Suppress("UNUSED_PARAMETER")
    fun base(context: Context): String = ImageSource.TMDB_IMAGES

    @Suppress("UNUSED_PARAMETER")
    fun take(context: Context, intent: Intent?) = Unit
}
