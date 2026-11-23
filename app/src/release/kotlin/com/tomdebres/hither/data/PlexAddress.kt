package com.tomdebres.hither.data

import android.content.Context
import android.content.Intent
import com.tomdebres.hither.core.PlexTv

/**
 * Where plex.tv is, in a **release** build (M8): plex.tv, always. The debug build's `PlexAddress` may be pointed at a
 * fake for tests; this one takes no address from anywhere (`scripts/release-plex-check` checks the built APK).
 */
object PlexAddress {
    @Suppress("UNUSED_PARAMETER")
    fun base(context: Context): String = PlexTv.PLEX_TV

    @Suppress("UNUSED_PARAMETER")
    fun take(context: Context, intent: Intent?) = Unit
}
