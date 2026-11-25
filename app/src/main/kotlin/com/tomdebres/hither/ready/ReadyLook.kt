package com.tomdebres.hither.ready

import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.RequestCount
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.ImageSource
import com.tomdebres.hither.core.rules.LOOK_TAKE
import com.tomdebres.hither.core.rules.LastSeen
import com.tomdebres.hither.core.rules.LastSeenStore
import com.tomdebres.hither.core.rules.LastSeenWatch
import com.tomdebres.hither.core.rules.ReadyTitle
import com.tomdebres.hither.core.rules.RequestEvent
import com.tomdebres.hither.core.rules.SinceLastSeen
import com.tomdebres.hither.core.rules.TitleArt
import com.tomdebres.hither.core.rules.accountKey
import com.tomdebres.hither.core.rules.dismissal
import com.tomdebres.hither.core.rules.liveDismissals
import com.tomdebres.hither.core.rules.lookDue
import com.tomdebres.hither.core.rules.seesWaiting
import com.tomdebres.hither.core.rules.titleArt
import com.tomdebres.hither.core.rules.watchNextTitles
import com.tomdebres.hither.session.SeerrAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One card on the TV's home screen: a ready title with what it shows. */
data class HomeItem(
    val key: String,
    val type: MediaType,
    val tmdbId: Int,
    val art: TitleArt,
    /** When it became ready (ms since 1970, UTC): Watch Next's "last engagement". */
    val readyAt: Long?,
)

/** Where the home screen's cards go: Android's TV provider, or a test's list. */
interface HomePublisher {
    /** Hither's channel holds [channel], in order, and Watch Next holds [watchNext], nothing else of Hither's. */
    suspend fun publish(channel: List<HomeItem>, watchNext: List<HomeItem>)

    /** The title [key] leaves Watch Next now. */
    suspend fun removeWatchNext(key: String)

    /** Everything of Hither's leaves the home screen: the channel and Watch Next. */
    suspend fun clear()
}

/** What a look asks the server: who the user is, their requests, a title's name and pictures. */
interface ReadySource {
    /** The signed-in user (`auth/me`, read live) and the server's base URL. */
    suspend fun me(): Pair<User, String>

    /** The user's own requests, the most recently changed first. */
    suspend fun requests(userId: Int): List<MediaRequest>

    suspend fun art(type: MediaType, tmdbId: Int): TitleArt

    /**
     * Where the server's pictures come from now (DECISIONS 382): its proxy or TMDB's image server. A title whose kept
     * pictures came from the other is asked for again, so the cards follow the server's owner turning caching on or off.
     */
    suspend fun images(): ImageSource

    /** The server's requests counted by status (`GET /request/count`), everyone's. */
    suspend fun requestCount(): RequestCount
}

/** The signed-in server, through the gateway (a call renews an ended session once). */
fun seerrReadySource(access: SeerrAccess) = object : ReadySource {
    override suspend fun me(): Pair<User, String> = access.call { client -> client.me() to client.baseUrl }

    override suspend fun requests(userId: Int): List<MediaRequest> =
        access.call { client -> client.requests(filter = "all", take = LOOK_TAKE, skip = 0, requestedBy = userId, sort = "modified").results }

    override suspend fun art(type: MediaType, tmdbId: Int): TitleArt = access.call { client ->
        when (type) {
            MediaType.MOVIE -> titleArt(client.movie(tmdbId), client.images)
            MediaType.TV -> titleArt(client.tv(tmdbId), client.images)
        }
    }

    override suspend fun requestCount(): RequestCount = access.call { client -> client.requestCount() }

    override suspend fun images(): ImageSource = access.call { client -> client.images }
}

/** A change to show in Hither: what happened, and to which title (its name, when it is known). */
data class Notice(val event: RequestEvent, val title: String?)

/**
 * What Hither shows when it comes to the front (M4, DECISIONS 79): the changes since it last showed
 * any, the count on the bar's Requests, and for an approver how many requests wait for approval.
 */
data class Front(val notices: List<Notice>, val requestsCount: Int, val waiting: Int?)

/** What one look found: who looked, and the changes and ready titles (DECISIONS 75). */
data class Look(val user: User, val since: SinceLastSeen)

/**
 * Looks at the user's requests and puts what is ready on the TV's home screen (DECISIONS 75, 76):
 * Hither's channel holds the ready titles (newest first, at most 10), Watch Next those of the last 14
 * days not opened since. Called when Hither starts and comes to the front, and every 30 minutes in the
 * background ([ReadyWorker]); [look] asks the server no more often than [lookDue] allows.
 *
 * A failed look changes nothing on the home screen or on the device. Titles are asked for once and
 * kept; one that fails is left off the cards until a later look gets it. A kept title whose pictures came from
 * elsewhere than the server's pictures come from now (its owner turned caching on or off, DECISIONS 382) is asked for
 * again, and keeps what it had if that fails.
 */
class ReadyLook(
    private val source: ReadySource,
    private val storage: ReadyStorage,
    private val publisher: HomePublisher,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val watch = LastSeenWatch(object : LastSeenStore {
        override suspend fun load(): LastSeen? = storage.read().lastSeen

        override suspend fun save(lastSeen: LastSeen) = storage.update { it.copy(lastSeen = lastSeen) }
    })

    /**
     * One look, if one is due ([background]: the periodic refresh; otherwise Hither starting or coming
     * to the front). Null when none was due or it failed.
     */
    suspend fun look(background: Boolean): Look? = lock.withLock {
        if (!lookDue(storage.read().lastLookAt, now(), background)) return null
        storage.update { it.copy(lastLookAt = now()) }
        val (user, baseUrl) = try {
            source.me()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val since = watch.look(accountKey(baseUrl, user.id), user.id) { source.requests(user.id) } ?: return null
        // How many wait for approval, for an account allowed to approve (read live). A failed count
        // keeps the last one; an account without the right has none.
        val waiting = if (seesWaiting(user)) {
            try {
                source.requestCount().pending
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                storage.read().waiting
            }
        } else {
            null
        }
        // The cards' titles, and those of every change still waiting to be shown (this look's and earlier ones').
        val unshown = storage.read().lastSeen?.unshown.orEmpty()
        val art = artFor(since.ready.map { it.type to it.tmdbId } + unshown.mapNotNull { e -> e.type?.let { it to e.tmdbId } })
        storage.update { state ->
            state.copy(
                ready = since.ready.map { ReadyEntry(it.key, it.readyAt) },
                art = art,
                dismissed = liveDismissals(state.dismissed, now()),
                waiting = waiting,
            )
        }
        publish(since.ready)
        Look(user, since)
    }

    /**
     * The title [key] was opened in Hither, or played through it: it leaves Watch Next now, and stays
     * out until it becomes ready again.
     */
    suspend fun opened(key: String) = lock.withLock {
        val readyAt = storage.read().ready.firstOrNull { it.key == key }?.readyAt
        storage.update { it.copy(dismissed = it.dismissed + (key to dismissal(readyAt, now()))) }
        runCatching { publisher.removeWatchNext(key) }
    }

    /**
     * Hither has come to the front: a look if one is due, then the changes not yet shown (taken, so each
     * is shown once), the bar's count and the waiting count.
     */
    suspend fun front(): Front {
        look(background = false)
        return lock.withLock {
            val state = storage.read()
            val unshown = state.lastSeen?.unshown.orEmpty()
            if (unshown.isNotEmpty()) storage.update { it.copy(lastSeen = it.lastSeen?.copy(unshown = emptyList())) }
            Front(
                notices = unshown.map { Notice(it, state.art[it.titleKey]?.title) },
                requestsCount = state.lastSeen?.requestsCount ?: 0,
                waiting = state.waiting,
            )
        }
    }

    /** My Requests has been opened: the bar's count starts again from nothing. */
    suspend fun requestsOpened() = lock.withLock {
        storage.update { it.copy(lastSeen = it.lastSeen?.copy(unopened = emptyList())) }
    }

    /** A title's name and pictures as kept, for a card standing in while its page loads. */
    suspend fun art(key: String): TitleArt? = storage.read().art[key]

    /** Signed out, or another account signing in: nothing of the last one stays, here or on the home screen. */
    suspend fun forget() = lock.withLock {
        runCatching { storage.clear() }
        runCatching { publisher.clear() }
    }

    /** The cards for [ready] and the Watch Next entries among them. */
    private suspend fun publish(ready: List<ReadyTitle>) {
        val state = storage.read()
        val items = ready.mapNotNull { title ->
            state.art[title.key]?.let { HomeItem(title.key, title.type, title.tmdbId, it, title.readyAt) }
        }
        val next = watchNextTitles(ready, state.dismissed, now()).mapTo(HashSet()) { it.key }
        runCatching { publisher.publish(items, items.filter { it.key in next }) }
    }

    /**
     * The kept names and pictures, with those of [titles] not yet kept, or kept with pictures from elsewhere than the
     * server's now, asked for; a failure is asked again next time.
     */
    private suspend fun artFor(titles: List<Pair<MediaType, Int>>): Map<String, TitleArt> {
        val kept = storage.read().art
        val wanted = titles.distinct().map { (type, id) -> "${type.api}:$id" to (type to id) }
        val images = try {
            source.images()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val fresh = HashMap<String, TitleArt>()
        for ((key, title) in wanted) {
            val old = kept[key]
            if (old != null && (images == null || (images.made(old.poster) && images.made(old.backdrop)))) continue
            try {
                fresh[key] = source.art(title.first, title.second)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Left out of the cards this time.
            }
        }
        // Only what the cards and the alerts still name: a title no longer ready is dropped.
        val keys = wanted.mapTo(HashSet()) { it.first }
        return (kept + fresh).filterKeys { it in keys }
    }
}
