package com.tomdebres.hither.ui.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The Plex app on this TV, as far as opening a title in it goes (DECISIONS 68). The manifest's
 * `<queries>` lets the app see an installed Plex; nothing else about Plex is read or sent.
 */
fun interface PlexApp {
    /** The package of the installed Plex app that opens [link], or null when none would. */
    fun packageFor(link: String): String?
}

/** Plex's own apps (Plex for Android and for Android TV is `com.plexapp.android`). */
private const val PLEX_PACKAGES = "com.plexapp."

/** The Plex app installed on this device, asked of the package manager for the very link. */
fun installedPlex(context: Context): PlexApp = PlexApp { link ->
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse(link))
    context.packageManager.queryIntentActivities(probe, 0)
        .map { it.activityInfo.packageName }
        .firstOrNull { it.startsWith(PLEX_PACKAGES) }
}

/** How the media page finds Plex: the device's own, unless a test provides one (`tv31` has no Plex). */
val LocalPlexApp = staticCompositionLocalOf<(Context) -> PlexApp> { ::installedPlex }

/** The intent that opens [link] in the Plex app [packageName], and nothing else. */
fun plexIntent(link: String, packageName: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(link))
        .setPackage(packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
