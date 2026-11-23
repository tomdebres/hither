package com.tomdebres.hither.ui.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The app on this TV that plays a trailer (DECISIONS 96, 98): Hither hands it the YouTube watch link
 * and fetches nothing from YouTube itself, as Play on Plex hands its link to Plex (68). The manifest's
 * `<queries>` lets the app see them; nothing else about them is read or sent.
 */
fun interface TrailerApp {
    /** The package that opens [link]: the YouTube app for Android TV if it does, else any app that does; null for none. */
    fun packageFor(link: String): String?
}

/** The YouTube app for Android TV (Google TV and Android TV alike). */
const val YOUTUBE_TV = "com.google.android.youtube.tv"

/** The apps on this device that open [link], asked of the package manager for the very link. */
fun installedTrailerApps(context: Context): TrailerApp = TrailerApp { link ->
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse(link))
    val packages = context.packageManager.queryIntentActivities(probe, 0).map { it.activityInfo.packageName }
    packages.firstOrNull { it == YOUTUBE_TV } ?: packages.firstOrNull()
}

/** How the media page finds a trailer's app: the device's own, unless a test provides one (`tv31` has no YouTube). */
val LocalTrailerApp = staticCompositionLocalOf<(Context) -> TrailerApp> { ::installedTrailerApps }

/** The intent that opens [link] in the app [packageName], and nothing else. */
fun trailerIntent(link: String, packageName: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(link))
        .setPackage(packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
