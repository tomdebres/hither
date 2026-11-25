package com.tomdebres.hither

import com.tomdebres.hither.data.TmdbAddress
import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.tomdebres.hither.core.PlexTv
import com.tomdebres.hither.data.AccountStore
import com.tomdebres.hither.data.PlexAddress
import com.tomdebres.hither.data.PlexIdentity
import com.tomdebres.hither.data.AdminStore
import com.tomdebres.hither.data.SecretBox
import com.tomdebres.hither.ready.ReadyLook
import com.tomdebres.hither.ready.ReadyStore
import com.tomdebres.hither.ready.ReadyWorker
import com.tomdebres.hither.ready.TvHomePublisher
import com.tomdebres.hither.ready.seerrReadySource
import com.tomdebres.hither.session.AdminGateway
import com.tomdebres.hither.session.SeerrSessionGateway
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Holds the app's few long-lived objects. No injection framework: there are a handful of them. */
class HitherApplication : Application(), SingletonImageLoader.Factory {
    /** Work that outlives a screen: the home screen's refresh, and clearing it at sign-out. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val gateway: SeerrSessionGateway by lazy {
        SeerrSessionGateway(
            store = AccountStore(this, SecretBox()),
            // plex.tv, only while a Plex sign-in is on screen (M8): the install's client id and the product name are
            // all it is sent; a release build always uses plex.tv itself (PlexAddress).
            plexTv = { PlexTv(OkHttp.create(), plexIdentity.clientIdentifier(), PlexAddress.base(this)) },
            // TMDB's image server, for a server with Image Caching off (DECISIONS 382): TMDB itself in a release build.
            tmdbImages = { TmdbAddress.base(this) },
            // OkHttp, with its defaults: no logging interceptor, so no request, header or
            // cookie is ever written to logcat.
            engine = { OkHttp.create() },
            // Not awaited: the gateway holds its lock here, and a look in progress may be waiting
            // on that lock while holding its own (ReadyLook takes its turn after it, in order).
            // The TV's admin account belongs to the family's account and its server: it goes with them
            // (DECISIONS 92).
            onAccountGone = {
                scope.launch { ready.forget() }
                scope.launch { admin.remove() }
            },
        )
    }

    /** The client identifier plex.tv knows this install by (M8): made at the first Plex sign-in, kept for the install. */
    val plexIdentity: PlexIdentity by lazy { PlexIdentity(this) }

    /** The TV's admin account (M5, DECISIONS 91, 92): its own client, cookie jar, store and Keystore key. */
    val admin: AdminGateway by lazy { AdminGateway(AdminStore(this), engine = { OkHttp.create() }, scope = scope) }

    /** What is ready to watch, on the TV's home screen (M4, DECISIONS 76). */
    val ready: ReadyLook by lazy { ReadyLook(seerrReadySource(gateway), ReadyStore(this), TvHomePublisher(this)) }

    override fun onCreate() {
        super.onCreate()
        ReadyWorker.schedule(this)
    }

    /**
     * Posters: every address comes from `imageUrl`: the named server's image proxy, or TMDB's image server while the
     * server keeps no copies (DECISIONS 382). The loader neither keeps nor sends cookies (it has no cookie jar; neither
     * the proxy nor TMDB needs a session), sends no header of the server's, and follows no redirect, as the API client
     * does not (REVIEW.md).
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build() },
                    ),
                )
            }
            .build()
}
