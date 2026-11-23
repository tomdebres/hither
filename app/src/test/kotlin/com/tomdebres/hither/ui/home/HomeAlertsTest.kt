package com.tomdebres.hither.ui.home

import com.tomdebres.hither.core.rules.RequestChange
import com.tomdebres.hither.core.rules.RequestEvent
import com.tomdebres.hither.ready.Front
import com.tomdebres.hither.ready.Notice
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeAlertsTest {
    private val notice = Notice(RequestEvent(RequestChange.AVAILABLE, "movie", 550, 1), "Film")

    @Test
    fun `a count read before My Requests was opened does not come back after it`() {
        val alerts = HomeAlerts()
        // Coming to the front: the answer is out...
        val asked = alerts.asking()
        // ...the user opens My Requests meanwhile (right after launch, say)...
        alerts.requestsOpened()
        // ...and the answer, read before the opening, arrives.
        alerts.take(Front(listOf(notice), requestsCount = 3, waiting = 2), asked)
        assertEquals(0, alerts.requestsCount)
        // The notices and the waiting count are still taken.
        assertEquals(listOf(Toast(notice)), alerts.queue.toList())
        assertEquals(2, alerts.waiting)
        // The next answer, asked after the opening, counts.
        alerts.take(Front(emptyList(), requestsCount = 1, waiting = 2), alerts.asking())
        assertEquals(1, alerts.requestsCount)
    }
}
