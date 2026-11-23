package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.RequestStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/*
 * What has happened to the user's requests since Hither last looked (M4): a request approved,
 * declined, or ready to watch (its copy, regular or 4K, in the library). Worked out from the
 * user's own requests (`GET /request` with `requestedBy`, as My Requests reads them) against a
 * snapshot kept on the device. Seerr's own rules, read at 3f7d5c3, decide what each status means:
 * `server/subscriber/MediaSubscriber.ts` completes an approved request when its copy (a film) or
 * every season it asked for (a series) is available or deleted, and `MediaRequestSubscriber`
 * tells the requester "Request Now Available" only when that copy, or those seasons, are
 * available; the approve and decline route (`server/routes/request.ts`) moves only a pending
 * request and records the approver in `modifiedBy`.
 */

/** Where a request stands, as far as the person who asked for it is concerned. */
@Serializable
enum class RequestStage {
    PENDING,
    APPROVED,
    DECLINED,

    /** Its copy is in the library: there is something to watch. */
    READY,

    /** Failed, completed with its copy deleted, or a status this build does not know: nothing to tell. */
    OTHER,
}

/**
 * The stage of [request], from its own status and its copy's library status. Completed means
 * ready only while the copy is there: Seerr also completes a request whose copy was deleted. An
 * approved request whose copy is already available is ready (Seerr counts it so too, as
 * "available", in `GET /request/count`); a pending one is still pending, since Seerr approves it
 * the moment its copy arrives.
 */
fun requestStage(request: MediaRequest): RequestStage {
    val copy = request.media?.statusFor(request.is4k)
    return when (request.status) {
        RequestStatus.PENDING -> RequestStage.PENDING
        RequestStatus.DECLINED -> RequestStage.DECLINED
        RequestStatus.APPROVED -> if (copy == MediaStatus.AVAILABLE) RequestStage.READY else RequestStage.APPROVED
        RequestStatus.COMPLETED ->
            if (copy == MediaStatus.AVAILABLE || copy == MediaStatus.PARTIALLY_AVAILABLE) RequestStage.READY else RequestStage.OTHER
        else -> RequestStage.OTHER
    }
}

/** What happened to a request, in the words the app shows for it. */
@Serializable
enum class RequestChange {
    APPROVED,
    DECLINED,

    /** The regular copy is ready to watch. */
    AVAILABLE,

    /** The 4K copy is ready to watch. */
    AVAILABLE_4K,
}

/** One thing that happened to one of the user's requests since the last look. */
@Serializable
data class RequestEvent(
    val change: RequestChange,
    /** `movie` or `tv`, as the server names it. */
    val mediaType: String,
    val tmdbId: Int,
    val requestId: Int,
) {
    val type: MediaType? get() = MediaType.fromApi(mediaType)

    /** The title it is about, as a card keys it (`movie:550`). */
    val titleKey: String get() = "$mediaType:$tmdbId"
}

/** A request as the last look left it. A stage a later build wrote and this one does not know reads as [RequestStage.OTHER]. */
@Serializable
data class SeenRequest(val id: Int, val stage: RequestStage = RequestStage.OTHER)

/** The requests as the last look left them, for one account on one server. */
@Serializable
data class RequestSnapshot(
    /** Whose requests ([accountKey]): another account's snapshot counts as no snapshot. */
    val account: String,
    /** The most recently seen first, at most [SNAPSHOT_LIMIT]. */
    val requests: List<SeenRequest> = emptyList(),
)

/** A title of the user's that is ready to watch, and since when as far as the server says. */
data class ReadyTitle(
    val type: MediaType,
    val tmdbId: Int,
    /** When it became ready (milliseconds since 1970, UTC), or null when the server gave no time. */
    val readyAt: Long?,
) {
    val key: String get() = "${type.api}:$tmdbId"
}

/** One look: the snapshot to keep, what changed since the last, and what is ready to watch. */
data class SinceLastSeen(
    val snapshot: RequestSnapshot,
    val events: List<RequestEvent>,
    val ready: List<ReadyTitle>,
)

/** Requests a snapshot remembers: comfortably more than one look reads ([LOOK_TAKE]). */
const val SNAPSHOT_LIMIT = 200

/** How many of the user's requests one look reads: the most recently changed. */
const val LOOK_TAKE = 50

/** Ready titles a home-screen row shows (Tom, 2026-11-11: at most 10). */
const val READY_LIMIT = 10

/** The snapshot's owner: one user on one server. */
fun accountKey(baseUrl: String, userId: Int): String = "${baseUrl.trimEnd('/')}#$userId"

/**
 * The user's requests ([requests], as one look read them, most recently changed first) against
 * the [previous] snapshot.
 *
 * - **The first look** (no snapshot, or another account's) records the snapshot and announces
 *   nothing: everything already approved or ready was there before Hither was looking.
 * - **A request seen before** announces the stage it has reached since, once: approved (only from
 *   pending), declined, or ready ("available", or "available in 4K" for a 4K request). A request
 *   that went from pending straight to ready announces only that it is ready.
 * - **A request seen for the first time** (made since the last look, on the TV or anywhere else)
 *   counts as having started pending, except one approved at once by its requester's own rights
 *   (`modifiedBy` is the requester, or absent): that was the user's own doing, not news. So a new
 *   request already declined, ready, or approved by someone else is announced.
 * - Two requests that come to the same thing for one title (two seasons' requests both ready)
 *   announce it once.
 * - The snapshot keeps the requests this look did not read, so one that comes back into the
 *   read later is not announced again; at most [SNAPSHOT_LIMIT], the most recent first.
 *
 * Requests by anyone but [userId] (a server that ignored `requestedBy`), of a type this build does
 * not know, or with no title are left out. A failed read must not reach here: [LastSeenWatch]
 * keeps the snapshot as it was.
 */
fun sinceLastSeen(previous: RequestSnapshot?, account: String, userId: Int, requests: List<MediaRequest>): SinceLastSeen {
    val mine = requests.filter { r ->
        r.id > 0 && (r.requestedBy == null || r.requestedBy.id == userId) &&
            typeOf(r) != null && (r.media?.tmdbId ?: 0) > 0
    }
    val first = previous == null || previous.account != account
    val before = if (first) emptyMap() else previous.requests.associate { it.id to it.stage }
    val events = if (first) {
        emptyList()
    } else {
        mine.mapNotNull { r -> change(before[r.id] ?: firstSight(r), r) }.distinctBy { it.change to it.titleKey }
    }
    val seen = mine.distinctBy { it.id }.map { SeenRequest(it.id, requestStage(it)) }
    val read = seen.mapTo(HashSet()) { it.id }
    val kept = if (first) emptyList() else previous.requests.filter { it.id !in read }
    return SinceLastSeen(
        snapshot = RequestSnapshot(account, (seen + kept).take(SNAPSHOT_LIMIT)),
        events = events,
        ready = readyTitles(mine),
    )
}

/**
 * The titles among [requests] that are ready to watch, newest first, one each (a regular and a 4K
 * request for one title are one title), at most [limit]. Newest by when each became ready: the
 * later of the request's last change (Seerr saves a request as it completes) and the copy's
 * arrival in the library; a title without either comes after those with one.
 */
fun readyTitles(requests: List<MediaRequest>, limit: Int = READY_LIMIT): List<ReadyTitle> =
    requests.asSequence()
        .filter { requestStage(it) == RequestStage.READY }
        .mapNotNull { r ->
            val type = typeOf(r) ?: return@mapNotNull null
            val tmdbId = r.media?.tmdbId?.takeIf { it > 0 } ?: return@mapNotNull null
            ReadyTitle(type, tmdbId, listOfNotNull(millis(r.updatedAt), millis(r.media?.mediaAddedAt)).maxOrNull())
        }
        .groupBy { it.key }
        .map { (_, same) -> same.maxBy { it.readyAt ?: Long.MIN_VALUE } }
        .sortedWith(compareByDescending<ReadyTitle> { it.readyAt ?: Long.MIN_VALUE })
        .take(limit)

/** What a request seen for the first time is taken to have been before: see [sinceLastSeen]. */
private fun firstSight(request: MediaRequest): RequestStage {
    val approver = request.modifiedBy?.id
    val byOwnRights = approver == null || approver == request.requestedBy?.id
    return if (requestStage(request) == RequestStage.APPROVED && byOwnRights) RequestStage.APPROVED else RequestStage.PENDING
}

private fun change(was: RequestStage, request: MediaRequest): RequestEvent? {
    val now = requestStage(request)
    if (now == was) return null
    val change = when (now) {
        RequestStage.READY -> if (request.is4k) RequestChange.AVAILABLE_4K else RequestChange.AVAILABLE
        RequestStage.DECLINED -> RequestChange.DECLINED
        RequestStage.APPROVED -> if (was == RequestStage.PENDING) RequestChange.APPROVED else return null
        RequestStage.PENDING, RequestStage.OTHER -> return null
    }
    val type = typeOf(request) ?: return null
    return RequestEvent(change, type.api, request.media?.tmdbId ?: return null, request.id)
}

private fun typeOf(request: MediaRequest): MediaType? =
    MediaType.fromApi(request.type) ?: MediaType.fromApi(request.media?.mediaType)

@OptIn(ExperimentalTime::class)
private fun millis(iso: String?): Long? =
    iso?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

/** What the device keeps between looks. */
@Serializable
data class LastSeen(
    val snapshot: RequestSnapshot,
    /** Changes the app has not shown yet, oldest first, at most [UNSHOWN_LIMIT]. */
    val unshown: List<RequestEvent> = emptyList(),
    /** Changes since My Requests was last opened, oldest first, at most [UNSHOWN_LIMIT]: the bar's count. */
    val unopened: List<RequestEvent> = emptyList(),
) {
    /** The count on the bar's Requests: one per request and change, however often it was found. */
    val requestsCount: Int get() = unopened.distinctBy { it.requestId to it.change }.size
}

/** Changes kept for the app to show: more than this many since it was last open is a backlog. */
const val UNSHOWN_LIMIT = 20

/** Where [LastSeen] is kept: the device's storage, or a test's memory. */
interface LastSeenStore {
    /** May fail any way: [LastSeenWatch.look] reads a failure as a look that changed nothing. */
    @Throws(Exception::class)
    suspend fun load(): LastSeen?

    @Throws(Exception::class)
    suspend fun save(lastSeen: LastSeen)
}

/** How [LastSeen] is written down: the client's lenient reading, so a later build still reads it. */
val lastSeenJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}

/**
 * Looks at the user's requests again, one look at a time however many callers ask at once (the
 * app's arrival and the background refresh share one), and keeps the result in [store].
 */
class LastSeenWatch(private val store: LastSeenStore) {
    private val lock = Mutex()

    /**
     * Reads the requests with [read] and compares them with the stored snapshot ([sinceLastSeen]).
     * The new snapshot is stored with the changes added to those not yet shown, and to those since
     * My Requests was last opened. A failed read, or
     * a store that will not open, changes nothing and gives null; so does a store that will not
     * save, so that the same changes are found again at the next look rather than lost.
     */
    @Throws(CancellationException::class)
    suspend fun look(account: String, userId: Int, read: suspend () -> List<MediaRequest>): SinceLastSeen? = lock.withLock {
        try {
            val requests = read()
            val stored = store.load()
            val since = sinceLastSeen(stored?.snapshot, account, userId, requests)
            val same = stored != null && stored.snapshot.account == account
            val waiting = if (same) stored.unshown else emptyList()
            val unopened = if (same) stored.unopened else emptyList()
            store.save(
                LastSeen(
                    since.snapshot,
                    unshown = (waiting + since.events).takeLast(UNSHOWN_LIMIT),
                    unopened = (unopened + since.events).takeLast(UNSHOWN_LIMIT),
                ),
            )
            since
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
