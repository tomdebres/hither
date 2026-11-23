package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.User
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class LastSeenTest {
    private val account = accountKey("http://seerr.example.invalid:5055/", ME)

    /** One of the user's requests, as `GET /request` carries it. */
    private fun asked(
        id: Int,
        status: Int = RequestStatus.PENDING,
        copy: Int = MediaStatus.PENDING,
        is4k: Boolean = false,
        type: String = "movie",
        tmdbId: Int = 1000 + id,
        updatedAt: String? = null,
        addedAt: String? = null,
        by: Int = ME,
        approver: Int? = null,
    ) = MediaRequest(
        id = id,
        status = status,
        type = type,
        is4k = is4k,
        updatedAt = updatedAt,
        media = MediaInfo(
            mediaType = type,
            tmdbId = tmdbId,
            status = if (is4k) MediaStatus.UNKNOWN else copy,
            status4k = if (is4k) copy else MediaStatus.UNKNOWN,
            mediaAddedAt = addedAt,
        ),
        requestedBy = User(id = by),
        modifiedBy = approver?.let { User(id = it) },
    )

    private fun pending(id: Int, tmdbId: Int = 1000 + id, is4k: Boolean = false, type: String = "movie") =
        asked(id, RequestStatus.PENDING, MediaStatus.PENDING, is4k, type, tmdbId)

    private fun approved(id: Int, tmdbId: Int = 1000 + id, is4k: Boolean = false, type: String = "movie", approver: Int? = SOMEONE_ELSE) =
        asked(id, RequestStatus.APPROVED, MediaStatus.PROCESSING, is4k, type, tmdbId, approver = approver)

    private fun ready(id: Int, tmdbId: Int = 1000 + id, is4k: Boolean = false, type: String = "movie", at: String? = null) =
        asked(id, RequestStatus.COMPLETED, MediaStatus.AVAILABLE, is4k, type, tmdbId, updatedAt = at)

    private fun declined(id: Int, tmdbId: Int = 1000 + id) =
        asked(id, RequestStatus.DECLINED, MediaStatus.UNKNOWN, tmdbId = tmdbId, approver = SOMEONE_ELSE)

    private fun look(previous: RequestSnapshot?, vararg requests: MediaRequest) =
        sinceLastSeen(previous, account, ME, requests.toList())

    /** The snapshot a first look at [requests] leaves. */
    private fun seen(vararg requests: MediaRequest) = look(null, *requests).snapshot

    private fun changes(since: SinceLastSeen) = since.events.map { it.change to it.tmdbId }

    @Test
    fun `the first look records the snapshot and announces nothing`() {
        val since = look(null, ready(1), declined(2), approved(3), pending(4))
        assertEquals(emptyList<RequestEvent>(), since.events)
        assertEquals(
            listOf(RequestStage.READY, RequestStage.DECLINED, RequestStage.APPROVED, RequestStage.PENDING),
            since.snapshot.requests.map { it.stage },
        )
        assertEquals(account, since.snapshot.account)
    }

    @Test
    fun `another account's snapshot is a first look`() {
        val other = RequestSnapshot(accountKey("http://seerr.example.invalid:5055", 9), listOf(SeenRequest(1, RequestStage.PENDING)))
        val since = look(other, ready(1))
        assertEquals(emptyList<RequestEvent>(), since.events)
        assertEquals(account, since.snapshot.account)
        assertEquals(listOf(SeenRequest(1, RequestStage.READY)), since.snapshot.requests)
    }

    @Test
    fun `a pending request approved - declined or ready is announced once`() {
        val before = seen(pending(1), pending(2), pending(3))
        val since = look(before, approved(1), declined(2), ready(3))
        assertEquals(
            listOf(RequestChange.APPROVED to 1001, RequestChange.DECLINED to 1002, RequestChange.AVAILABLE to 1003),
            changes(since),
        )
        // Pending straight to ready says ready, not approved as well.
        assertEquals(1, since.events.count { it.tmdbId == 1003 })
        // The next look with nothing new says nothing.
        assertEquals(emptyList<RequestEvent>(), look(since.snapshot, approved(1), declined(2), ready(3)).events)
    }

    @Test
    fun `an approved request becoming ready is announced - in 4K for a 4K request`() {
        val before = seen(approved(1), approved(2, is4k = true))
        val since = look(before, ready(1), ready(2, is4k = true))
        assertEquals(listOf(RequestChange.AVAILABLE to 1001, RequestChange.AVAILABLE_4K to 1002), changes(since))
        assertEquals("movie:1001", since.events[0].titleKey)
        assertEquals(MediaType.MOVIE, since.events[0].type)
    }

    @Test
    fun `ready is the copy in the library - as Seerr completes and tells`() {
        // A series request completes when its seasons are in; the series may still be partly there.
        assertEquals(RequestStage.READY, requestStage(asked(1, RequestStatus.COMPLETED, MediaStatus.PARTIALLY_AVAILABLE, type = "tv")))
        // Seerr completes a request whose copy was deleted too: nothing to watch.
        assertEquals(RequestStage.OTHER, requestStage(asked(2, RequestStatus.COMPLETED, MediaStatus.DELETED)))
        // Approved with its copy already in (Seerr's own count calls it available).
        assertEquals(RequestStage.READY, requestStage(asked(3, RequestStatus.APPROVED, MediaStatus.AVAILABLE)))
        // Approved, with only part of the series in: still on its way.
        assertEquals(RequestStage.APPROVED, requestStage(asked(4, RequestStatus.APPROVED, MediaStatus.PARTIALLY_AVAILABLE, type = "tv")))
        // A 4K request reads the 4K copy, not the regular one.
        val regularIn = asked(5, RequestStatus.COMPLETED, MediaStatus.AVAILABLE, is4k = true).let {
            it.copy(media = it.media!!.copy(status = MediaStatus.AVAILABLE, status4k = MediaStatus.PROCESSING))
        }
        assertEquals(RequestStage.OTHER, requestStage(regularIn))
        assertEquals(RequestStage.OTHER, requestStage(asked(6, RequestStatus.FAILED, MediaStatus.PROCESSING)))

        val before = seen(approved(7), approved(8))
        val since = look(before, asked(7, RequestStatus.COMPLETED, MediaStatus.DELETED), asked(8, RequestStatus.FAILED, MediaStatus.PROCESSING))
        assertEquals(emptyList<RequestEvent>(), since.events)
    }

    @Test
    fun `a request first seen is news unless its own requester approved it`() {
        val before = seen(ready(1))
        val since = look(
            before,
            approved(10, approver = ME), // made on the TV with automatic approval
            approved(11, approver = null), // no approver recorded: taken as the same
            approved(12, approver = SOMEONE_ELSE), // made elsewhere, approved by the owner since
            pending(13),
            declined(14),
            ready(15),
            ready(1),
        )
        assertEquals(
            listOf(RequestChange.APPROVED to 1012, RequestChange.DECLINED to 1014, RequestChange.AVAILABLE to 1015),
            changes(since),
        )
    }

    @Test
    fun `one title's requests coming to the same thing are announced once`() {
        val before = seen(approved(1, tmdbId = 77, type = "tv"), approved(2, tmdbId = 77, type = "tv"))
        val since = look(before, ready(1, tmdbId = 77, type = "tv"), ready(2, tmdbId = 77, type = "tv"))
        assertEquals(listOf(RequestChange.AVAILABLE to 77), changes(since))
        assertEquals("tv:77", since.events.single().titleKey)
        // A 4K copy ready alongside is a different thing.
        val both = look(seen(approved(3, tmdbId = 88), approved(4, tmdbId = 88, is4k = true)), ready(3, tmdbId = 88), ready(4, tmdbId = 88, is4k = true))
        assertEquals(listOf(RequestChange.AVAILABLE to 88, RequestChange.AVAILABLE_4K to 88), changes(both))
    }

    @Test
    fun `requests this look did not read are remembered - so they are not announced again`() {
        val announced = look(seen(approved(1)), ready(1))
        assertEquals(1, announced.events.size)
        // Out of the read's window for a while...
        val away = look(announced.snapshot, pending(2))
        assertEquals(listOf(1, 2).sorted(), away.snapshot.requests.map { it.id }.sorted())
        assertEquals(listOf(2, 1), away.snapshot.requests.map { it.id })
        // ...and back: not news.
        assertEquals(emptyList<RequestEvent>(), look(away.snapshot, ready(1), pending(2)).events)
        // The snapshot is bounded, the latest read first.
        val many = (1..SNAPSHOT_LIMIT + 50).map { SeenRequest(10_000 + it, RequestStage.PENDING) }
        val bounded = look(RequestSnapshot(account, many), pending(3))
        assertEquals(SNAPSHOT_LIMIT, bounded.snapshot.requests.size)
        assertEquals(3, bounded.snapshot.requests.first().id)
    }

    @Test
    fun `other users' requests and requests with no title are left out`() {
        val before = seen()
        val since = look(
            before,
            asked(1, RequestStatus.COMPLETED, MediaStatus.AVAILABLE, by = SOMEONE_ELSE),
            asked(2, RequestStatus.COMPLETED, MediaStatus.AVAILABLE, type = "person"),
            asked(3, RequestStatus.COMPLETED, MediaStatus.AVAILABLE, tmdbId = 0),
            ready(4),
        )
        assertEquals(listOf(RequestChange.AVAILABLE to 1004), changes(since))
        assertEquals(listOf(4), since.snapshot.requests.map { it.id })
        assertEquals(listOf("movie:1004"), since.ready.map { it.key })
    }

    @Test
    fun `ready titles are the newest ten - one each`() {
        val requests = (1..12).map { ready(it, at = "2026-11-${it.toString().padStart(2, '0')}T10:00:00.000Z") } +
            ready(20, tmdbId = 1001, is4k = true, at = "2026-11-20T10:00:00.000Z") + // the 4K copy of title 1001, later
            ready(21, tmdbId = 2021, type = "tv", at = null) + // no time at all
            asked(22, RequestStatus.COMPLETED, MediaStatus.AVAILABLE, addedAt = "2026-11-30T10:00:00.000Z", updatedAt = "2026-11-01T00:00:00.000Z") +
            pending(23) + declined(24) + approved(25)
        val ready = readyTitles(requests)
        assertEquals(READY_LIMIT, ready.size)
        assertEquals(
            listOf("movie:1022", "movie:1001", "movie:1012", "movie:1011", "movie:1010", "movie:1009", "movie:1008", "movie:1007", "movie:1006", "movie:1005"),
            ready.map { it.key },
        )
        // The later of the request's change and the copy's arrival.
        assertEquals(Instant.parse("2026-11-30T10:00:00Z").toEpochMilliseconds(), ready[0].readyAt)
        // One with no time comes after those with one.
        assertEquals("tv:2021", readyTitles(listOf(ready(21, tmdbId = 2021, type = "tv"), ready(1, at = "2026-01-01T00:00:00.000Z"))).last().key)
    }

    private class MemoryStore(var kept: LastSeen? = null, val failSave: Boolean = false) : LastSeenStore {
        var saves = 0

        override suspend fun load(): LastSeen? = kept

        override suspend fun save(lastSeen: LastSeen) {
            if (failSave) throw IOException("disk full")
            saves++
            kept = lastSeen
        }
    }

    @Test
    fun `a failed read changes nothing`() = runTest {
        val store = MemoryStore(LastSeen(seen(pending(1)), unshown = listOf(RequestEvent(RequestChange.APPROVED, "movie", 9, 9))))
        val kept = store.kept
        val watch = LastSeenWatch(store)
        assertNull(watch.look(account, ME) { throw IOException("the server did not answer") })
        assertEquals(kept, store.kept)
        assertEquals(0, store.saves)
    }

    @Test
    fun `a look keeps the snapshot and queues the changes until they are shown`() = runTest {
        val store = MemoryStore()
        val watch = LastSeenWatch(store)
        // First look: recorded, nothing to show.
        val first = watch.look(account, ME) { listOf(pending(1), pending(2)) }!!
        assertEquals(emptyList<RequestEvent>(), first.events)
        assertEquals(emptyList<RequestEvent>(), store.kept!!.unshown)
        // Two later looks, nobody looking at the app in between: both changes wait, oldest first.
        watch.look(account, ME) { listOf(approved(1), pending(2)) }
        watch.look(account, ME) { listOf(approved(1), declined(2)) }
        assertEquals(listOf(RequestChange.APPROVED to 1001, RequestChange.DECLINED to 1002), store.kept!!.unshown.map { it.change to it.tmdbId })
        // A store that will not save: the look gives nothing, so the same changes are found next time.
        val stuck = MemoryStore(store.kept, failSave = true)
        assertNull(LastSeenWatch(stuck).look(account, ME) { listOf(ready(1), declined(2)) })
        // Another account signing in starts again, and drops what was waiting for the last one.
        watch.look(accountKey("http://other.example.invalid", ME), ME) { listOf(ready(1)) }
        assertEquals(emptyList<RequestEvent>(), store.kept!!.unshown)
    }

    @Test
    fun `changes since My Requests was opened are counted once per request and change`() = runTest {
        val store = MemoryStore()
        val watch = LastSeenWatch(store)
        watch.look(account, ME) { listOf(pending(1), pending(2), approved(3)) }
        watch.look(account, ME) { listOf(approved(1), pending(2), ready(3)) }
        watch.look(account, ME) { listOf(ready(1), declined(2), ready(3)) }
        assertEquals(4, store.kept!!.requestsCount)
        // The same change found twice (a request dropping out of the read and back) counts once.
        val twice = store.kept!!.copy(unopened = store.kept!!.unopened + store.kept!!.unopened.first())
        assertEquals(4, twice.requestsCount)
        // Shown in the app or not, the count is its own queue: emptying one leaves the other.
        store.kept = store.kept!!.copy(unshown = emptyList())
        assertEquals(4, store.kept!!.requestsCount)
        store.kept = store.kept!!.copy(unopened = emptyList())
        watch.look(account, ME) { listOf(ready(1), declined(2), ready(3), declined(4)) }
        assertEquals(1, store.kept!!.requestsCount)
    }

    @Test
    fun `what is kept reads back as written`() {
        val kept = LastSeen(
            look(seen(approved(1)), ready(1, is4k = true), declined(2)).snapshot,
            unshown = listOf(RequestEvent(RequestChange.AVAILABLE_4K, "movie", 1001, 1)),
        )
        val text = lastSeenJson.encodeToString(LastSeen.serializer(), kept)
        assertEquals(kept, lastSeenJson.decodeFromString(LastSeen.serializer(), text))
        // A later build's extra keys and an unknown stage do not lose the rest.
        val newer = """{"snapshot":{"account":"$account","requests":[{"id":1,"stage":"READY"},{"id":2,"stage":"LATER"}]},"unshown":[],"later":1}"""
        assertEquals(
            listOf(RequestStage.READY, RequestStage.OTHER),
            lastSeenJson.decodeFromString(LastSeen.serializer(), newer).snapshot.requests.map { it.stage },
        )
        assertTrue(text.contains("\"AVAILABLE_4K\""))
    }
}
