package com.tomdebres.hither.ready

import com.tomdebres.hither.core.Permission
import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.RequestCount
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.RequestChange
import com.tomdebres.hither.core.rules.TitleArt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The home screen's look on the JVM: a scripted server, the device's state in memory, the cards in a list. */
class ReadyLookTest {
    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private var now = Instant.parse("2026-11-11T20:00:00Z").toEpochMilli()

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private inner class Server : ReadySource {
        var requests = listOf<MediaRequest>()
        var down = false
        var artDown = setOf<Int>()
        var permissions = Permission.REQUEST
        var pending = 3
        var countDown = false
        val asked = mutableListOf<String>()

        override suspend fun me(): Pair<User, String> {
            asked += "me"
            if (down) throw SeerrException.Unreachable(java.io.IOException("down"))
            return User(id = 8, permissions = permissions) to "http://seerr.example.invalid:5055"
        }

        override suspend fun requestCount(): RequestCount {
            asked += "count"
            if (countDown) throw SeerrException.Unreachable(java.io.IOException("down"))
            return RequestCount(pending = pending)
        }

        override suspend fun requests(userId: Int): List<MediaRequest> {
            asked += "requests"
            if (down) throw SeerrException.Unreachable(java.io.IOException("down"))
            return requests
        }

        override suspend fun art(type: MediaType, tmdbId: Int): TitleArt {
            asked += "art $tmdbId"
            if (tmdbId in artDown) throw SeerrException.Unreachable(java.io.IOException("down"))
            return TitleArt("Title $tmdbId", "2020", "poster $tmdbId", "backdrop $tmdbId")
        }
    }

    private class Memory : ReadyStorage {
        var state = ReadyState()

        override suspend fun read() = state

        override suspend fun update(change: (ReadyState) -> ReadyState) {
            state = change(state)
        }

        override suspend fun clear() {
            state = ReadyState()
        }
    }

    private class Screen : HomePublisher {
        var channel = listOf<String>()
        var watchNext = listOf<String>()
        var engaged = mapOf<String, Long?>()
        var publishes = 0
        var cleared = 0

        override suspend fun publish(channel: List<HomeItem>, watchNext: List<HomeItem>) {
            publishes++
            this.channel = channel.map { it.key }
            this.watchNext = watchNext.map { it.key }
            engaged = watchNext.associate { it.key to it.readyAt }
        }

        override suspend fun removeWatchNext(key: String) {
            watchNext = watchNext - key
        }

        override suspend fun clear() {
            cleared++
            channel = emptyList()
            watchNext = emptyList()
        }
    }

    private val server = Server()
    private val memory = Memory()
    private val screen = Screen()
    private val ready = ReadyLook(server, memory, screen) { now }

    private fun request(id: Int, status: Int, copy: Int, changed: Long, tmdbId: Int = 100 + id) = MediaRequest(
        id = id,
        status = status,
        type = "movie",
        updatedAt = iso(changed),
        media = MediaInfo(mediaType = "movie", tmdbId = tmdbId, status = copy),
        requestedBy = User(id = 8),
    )

    private fun readyAgo(id: Int, ago: Long) = request(id, RequestStatus.COMPLETED, MediaStatus.AVAILABLE, now - ago)

    private fun pending(id: Int) = request(id, RequestStatus.PENDING, MediaStatus.PENDING, now - day)

    @Test
    fun `the first look puts the ready titles on the home screen, newest first, and Watch Next the last fortnight's`() = runBlocking {
        server.requests = listOf(readyAgo(1, 20 * day), readyAgo(2, minute), readyAgo(3, 3 * day), pending(4)) +
            (10..20).map { readyAgo(it, 30 * day + it * minute) }
        val look = ready.look(background = false)
        assertNotNull(look)
        assertEquals(emptyList<Any>(), look!!.since.events)
        assertEquals(listOf("movie:102", "movie:103", "movie:101") + (10..16).map { "movie:${100 + it}" }, screen.channel)
        assertEquals(listOf("movie:102", "movie:103"), screen.watchNext)
        assertEquals(now - minute, screen.engaged["movie:102"])
        // Each card's title asked once, and kept.
        assertEquals(10, server.asked.count { it.startsWith("art") })
        now += 2 * minute
        ready.look(background = false)
        assertEquals(10, server.asked.count { it.startsWith("art") })
        assertEquals("Title 102", ready.art("movie:102")?.title)
    }

    @Test
    fun `a failed look leaves the home screen and the device as they were`() = runBlocking {
        server.requests = listOf(readyAgo(1, minute), pending(2))
        ready.look(background = false)
        val before = memory.state
        val shown = screen.channel to screen.watchNext
        val publishes = screen.publishes

        server.down = true
        now += 2 * minute
        assertNull(ready.look(background = false))
        assertEquals(before.copy(lastLookAt = now), memory.state)
        assertEquals(shown, screen.channel to screen.watchNext)
        assertEquals(publishes, screen.publishes)
    }

    @Test
    fun `a title whose name does not come is left off until a later look gets it`() = runBlocking {
        server.requests = listOf(readyAgo(1, minute), readyAgo(2, 2 * minute))
        server.artDown = setOf(101)
        ready.look(background = false)
        assertEquals(listOf("movie:102"), screen.channel)
        server.artDown = emptySet()
        now += 2 * minute
        ready.look(background = false)
        assertEquals(listOf("movie:101", "movie:102"), screen.channel)
    }

    @Test
    fun `the server is asked at most once a minute from the app and every 25 minutes from the background`() = runBlocking {
        server.requests = listOf(readyAgo(1, minute))
        ready.look(background = false)
        val asked = server.asked.size
        now += 30_000
        assertNull(ready.look(background = false))
        now += 10 * minute
        assertNull(ready.look(background = true))
        assertEquals(asked, server.asked.size)
        now += 15 * minute
        assertNotNull(ready.look(background = true))
        assertTrue(server.asked.size > asked)
    }

    @Test
    fun `a title opened in Hither leaves Watch Next, keeps its channel card, and is back when ready again`() = runBlocking {
        server.requests = listOf(readyAgo(1, minute), readyAgo(2, 2 * minute))
        ready.look(background = false)
        now += minute
        ready.opened("movie:101")
        assertEquals(listOf("movie:102"), screen.watchNext)
        now += 2 * minute
        ready.look(background = false)
        assertEquals(listOf("movie:102"), screen.watchNext)
        assertEquals(listOf("movie:101", "movie:102"), screen.channel)
        // Its 4K copy (or a later season) ready afterwards: back in Watch Next.
        server.requests = listOf(request(3, RequestStatus.COMPLETED, MediaStatus.AVAILABLE, now, tmdbId = 101), readyAgo(2, 2 * minute))
        now += 2 * minute
        ready.look(background = false)
        assertEquals(listOf("movie:101", "movie:102"), screen.watchNext)
    }

    @Test
    fun `changes found in the background wait for the app, with their titles kept`() = runBlocking {
        server.requests = listOf(pending(1), pending(2))
        ready.look(background = true)
        server.requests = listOf(request(1, RequestStatus.APPROVED, MediaStatus.PROCESSING, now), request(2, RequestStatus.DECLINED, MediaStatus.UNKNOWN, now))
        now += 30 * minute
        val look = ready.look(background = true)!!
        assertEquals(listOf(RequestChange.APPROVED, RequestChange.DECLINED), look.since.events.map { it.change })
        assertEquals(look.since.events, memory.state.lastSeen?.unshown)
        // Not ready, but named in a change still to be shown: their names are kept.
        assertEquals("Title 101", ready.art("movie:101")?.title)
        assertEquals("Title 102", ready.art("movie:102")?.title)
    }

    @Test
    fun `coming to the front gives each change once, with its title, and the bar's count until My Requests opens`() = runBlocking {
        server.requests = listOf(pending(1), pending(2), readyAgo(3, 30 * day))
        val first = ready.front()
        assertEquals(Front(emptyList(), requestsCount = 0, waiting = null), first)

        // Changes found in the background while Hither was away...
        server.requests = listOf(request(1, RequestStatus.APPROVED, MediaStatus.PROCESSING, now), request(2, RequestStatus.DECLINED, MediaStatus.UNKNOWN, now), readyAgo(3, 30 * day))
        now += 30 * minute
        ready.look(background = true)
        server.requests = listOf(request(1, RequestStatus.COMPLETED, MediaStatus.AVAILABLE, now), request(2, RequestStatus.DECLINED, MediaStatus.UNKNOWN, now), readyAgo(3, 30 * day))
        now += 30 * minute
        // ...and one more found as it comes back: all of them, oldest first, with their names.
        val front = ready.front()
        assertEquals(
            listOf(RequestChange.APPROVED to "Title 101", RequestChange.DECLINED to "Title 102", RequestChange.AVAILABLE to "Title 101"),
            front.notices.map { it.event.change to it.title },
        )
        assertEquals(3, front.requestsCount)
        // Shown once: coming back again shows nothing new, and the count stays until My Requests opens.
        now += 2 * minute
        val again = ready.front()
        assertEquals(emptyList<Notice>(), again.notices)
        assertEquals(3, again.requestsCount)
        ready.requestsOpened()
        now += 2 * minute
        assertEquals(0, ready.front().requestsCount)
    }

    @Test
    fun `waiting for approval is read only for an account that may approve, as it is now`() = runBlocking {
        server.requests = listOf(pending(1))
        assertEquals(null, ready.front().waiting)
        assertTrue("count asked without the right: ${server.asked}", "count" !in server.asked)

        server.permissions = Permission.REQUEST + Permission.MANAGE_REQUESTS
        now += 2 * minute
        assertEquals(3, ready.front().waiting)
        // A failed count keeps the last one.
        server.countDown = true
        server.pending = 5
        now += 2 * minute
        assertEquals(3, ready.front().waiting)
        // The right taken away: none.
        server.permissions = Permission.REQUEST
        server.countDown = false
        now += 2 * minute
        assertEquals(null, ready.front().waiting)
        // An admin holds it too.
        server.permissions = Permission.ADMIN
        now += 2 * minute
        assertEquals(5, ready.front().waiting)
    }

    @Test
    fun `forgetting clears the device and the home screen`() = runBlocking {
        server.requests = listOf(readyAgo(1, minute))
        ready.look(background = false)
        ready.forget()
        assertEquals(ReadyState(), memory.state)
        assertEquals(1, screen.cleared)
        assertEquals(emptyList<String>(), screen.channel)
    }
}
