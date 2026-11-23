package com.tomdebres.hither.ui.media

import com.tomdebres.hither.core.rules.IssueDraft

import com.tomdebres.hither.core.model.IssueType

import com.tomdebres.hither.core.model.IssueBody

import com.tomdebres.hither.core.Permission

import com.tomdebres.hither.core.model.QuotaStatus

import com.tomdebres.hither.core.model.Quota

import com.tomdebres.hither.core.model.MediaResult

import com.tomdebres.hither.core.model.Page

import com.tomdebres.hither.ui.discover.DiscoverPage

import com.tomdebres.hither.core.rules.mediaBadge

import com.tomdebres.hither.core.rules.RelatedKind

import com.tomdebres.hither.core.model.RtRating

import com.tomdebres.hither.core.model.Ratings

import com.tomdebres.hither.core.SeerrException
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import com.tomdebres.hither.core.rules.tvPage
import com.tomdebres.hither.core.rules.moviePage
import com.tomdebres.hither.core.rules.RequestFailure
import com.tomdebres.hither.core.rules.RequestButton
import com.tomdebres.hither.core.rules.RequestAction
import com.tomdebres.hither.core.model.TvDetails
import com.tomdebres.hither.core.model.SeasonSummary
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.model.RequestBody
import com.tomdebres.hither.core.model.MovieDetails
import com.tomdebres.hither.core.model.MediaStatus
import com.tomdebres.hither.core.model.MediaSeason
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.PublicSettings
import com.tomdebres.hither.core.model.User
import com.tomdebres.hither.core.rules.MediaPage
import com.tomdebres.hither.core.rules.RequestOptions
import com.tomdebres.hither.core.rules.TitleCard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The media page's loading, failure and Try Again on the JVM, against a scripted source. */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaModelTest {
    private val card = TitleCard("movie:7", 7, MediaType.MOVIE, "Film 7", "2026", poster = null, badge = null)

    private fun page(title: String) = MediaPage(
        type = MediaType.MOVIE, tmdbId = 7, title = title, year = "2026", poster = null, backdrop = null,
        certification = null, attributes = emptyList(), tagline = null, overview = null, facts = emptyList(),
        badges = emptyList(), buttons = emptyList(), options = RequestOptions.NONE, cast = emptyList(),
        seasons = emptyList(), media = null, settings = PublicSettings(), user = User(id = 8),
    )

    /** Every load waits here until the test answers it. */
    private class Script : MediaSource {
        val asked = mutableListOf<Pair<MediaType, Int>>()
        val answers = ArrayDeque<CompletableDeferred<MediaPage>>()

        override suspend fun page(type: MediaType, tmdbId: Int): MediaPage {
            asked += type to tmdbId
            return CompletableDeferred<MediaPage>().also { answers.addLast(it) }.await()
        }
    }

    private fun TestScope.model(script: Script) =
        MediaModel(script, card, this, StandardTestDispatcher(testScheduler))

    @Test
    fun `the title is asked for once, by its type and id, and the page shows when it comes`() = runTest {
        val script = Script()
        val model = model(script)
        assertEquals(MediaState.Loading, model.state)
        model.start()
        model.start()
        advanceUntilIdle()
        assertEquals(listOf(MediaType.MOVIE to 7), script.asked)
        assertEquals(MediaState.Loading, model.state)
        script.answers.removeFirst().complete(page("Film 7"))
        advanceUntilIdle()
        assertEquals(MediaState.Ready(page("Film 7")), model.state)
    }

    @Test
    fun `a failure shows Try Again, which asks once more and only after a failure`() = runTest {
        val script = Script()
        val model = model(script)
        model.start()
        advanceUntilIdle()
        // Try Again while the first load is out does nothing
        model.retry()
        advanceUntilIdle()
        assertEquals(1, script.asked.size)
        script.answers.removeFirst().completeExceptionally(SeerrException.Unreachable(RuntimeException("down")))
        advanceUntilIdle()
        assertEquals(MediaState.Failed, model.state)

        model.retry()
        assertEquals(MediaState.Loading, model.state)
        model.retry()
        advanceUntilIdle()
        assertEquals(2, script.asked.size)
        script.answers.removeFirst().complete(page("Film 7"))
        advanceUntilIdle()
        assertEquals(MediaState.Ready(page("Film 7")), model.state)
        // and once the page is here, Try Again has nothing to do
        model.retry()
        advanceUntilIdle()
        assertEquals(2, script.asked.size)
    }

    // ---- the request modal and what follows ---------------------------------------------------

    /** A film nobody has asked for, on the recorded account: Request and Request in 4K. */
    private val film = moviePage(
        MovieDetails(id = 7, title = "Film 7"),
        "http://seerr.test",
        PublicSettings(movie4kEnabled = true, partialRequestsEnabled = true),
        User(id = 8, permissions = 67744),
    )

    private val series = tvPage(
        TvDetails(
            id = 9,
            name = "Series 9",
            seasons = (1..4).map { SeasonSummary(seasonNumber = it, episodeCount = 8) },
            mediaInfo = MediaInfo(tmdbId = 9, status = MediaStatus.PARTIALLY_AVAILABLE, seasons = listOf(MediaSeason(seasonNumber = 1, status = MediaStatus.AVAILABLE))),
        ),
        "http://seerr.test",
        PublicSettings(partialRequestsEnabled = true),
        User(id = 8, permissions = 67744),
    )

    /** Requests wait here until the test answers them; the page is read from [pages], in turn. */
    private inner class Server(vararg pages: MediaPage) : MediaSource, RequestSender {
        val reads = ArrayDeque(pages.toList())
        var read = 0
        val sent = mutableListOf<RequestBody>()
        val answers = ArrayDeque<CompletableDeferred<MediaRequest>>()

        override suspend fun page(type: MediaType, tmdbId: Int): MediaPage {
            read++
            if (reads.isEmpty()) throw SeerrException.Unreachable(RuntimeException("no answer"))
            return if (reads.size > 1) reads.removeFirst() else reads.first()
        }

        override suspend fun request(body: RequestBody): MediaRequest {
            sent += body
            return CompletableDeferred<MediaRequest>().also { answers.addLast(it) }.await()
        }
    }

    private val badges = mutableListOf<Pair<String, String?>>()

    private fun TestScope.model(server: Server, card: TitleCard = this@MediaModelTest.card) = MediaModel(
        source = server,
        card = card,
        scope = this,
        work = StandardTestDispatcher(testScheduler),
        requests = server,
        onBadge = { key, badge -> badges += key to badge?.text },
    )

    private fun approved(body: RequestBody) = MediaRequest(id = 1001, status = RequestStatus.APPROVED, is4k = body.is4k)

    @Test
    fun `one press is one request - while it is out nothing opens or sends another`() = runTest {
        val server = Server(film)
        val model = model(server)
        model.start()
        advanceUntilIdle()
        model.open(film.buttons.first())
        assertEquals(RequestDraft(is4k = false), model.draft)
        model.send()
        model.send()
        advanceUntilIdle()
        assertTrue(model.sending)
        model.send()
        model.open(film.buttons.last())
        advanceUntilIdle()
        assertEquals(listOf(RequestBody("movie", 7, is4k = false)), server.sent)
        assertEquals(RequestDraft(is4k = false), model.draft)

        server.answers.removeFirst().complete(approved(server.sent.single()))
        advanceUntilIdle()
        assertFalse(model.sending)
        assertNull(model.draft)
        assertEquals(1, server.sent.size)
    }

    @Test
    fun `a 201 shows the new status at once, on the page and the card, and the page is read again`() = runTest {
        val server = Server(film)
        val model = model(server)
        model.start()
        advanceUntilIdle()
        assertEquals(1, server.read)
        model.open(film.buttons.first())
        model.send()
        advanceUntilIdle()
        // the page read again does not answer: what shows comes from the 201 alone
        server.reads.clear()
        server.answers.removeFirst().complete(approved(server.sent.single()))
        advanceUntilIdle()
        assertEquals(2, server.read)
        val page = (model.state as MediaState.Ready).page
        assertEquals(listOf("Requested"), page.badges.map { it.text })
        assertEquals(listOf("Request in 4K"), page.buttons.map { it.text })
        assertEquals(listOf("movie:7" to "Requested"), badges)
        assertEquals(RequestOutcome.Created("Film 7", approved = true), model.outcome)
        assertEquals(1, model.outcomes)

        // the next request's page read again does answer, and the server's word replaces it
        model.open(page.buttons.single())
        model.send()
        advanceUntilIdle()
        server.reads.addLast(film.withMedia(MediaInfo(tmdbId = 7, status = MediaStatus.PROCESSING, status4k = MediaStatus.PENDING)))
        server.answers.removeFirst().complete(MediaRequest(id = 1002, status = RequestStatus.PENDING, is4k = true))
        advanceUntilIdle()
        assertEquals(listOf("Requested", "4K Pending"), (model.state as MediaState.Ready).page.badges.map { it.text })
        assertEquals(3, server.read)
    }

    @Test
    fun `each failure is reported as what it was, and the page is read again`() = runTest {
        val failures = listOf(
            SeerrException.NothingToRequest("No seasons available to request") to RequestFailure.NOTHING_TO_REQUEST,
            SeerrException.AlreadyRequested("Request for this media already exists.") to RequestFailure.ALREADY_REQUESTED,
            SeerrException.Refused("You do not have permission to make movie requests.") to RequestFailure.REFUSED,
            SeerrException.Unreachable(RuntimeException("timeout")) to RequestFailure.UNREACHABLE,
            SeerrException.Http(500, null) to RequestFailure.OTHER,
        )
        val server = Server(film)
        val model = model(server)
        model.start()
        advanceUntilIdle()
        for ((index, failure) in failures.withIndex()) {
            model.open(film.buttons.first())
            model.send()
            advanceUntilIdle()
            server.answers.removeFirst().completeExceptionally(failure.first)
            advanceUntilIdle()
            val outcome = model.outcome as RequestOutcome.Failed
            assertEquals(failure.second, outcome.failure)
            assertEquals("Film 7", outcome.title)
            // a 403 keeps Seerr's own sentence for the screen
            assertEquals((failure.first as? SeerrException.Refused)?.serverMessage, outcome.serverMessage)
            assertEquals(index + 1, model.outcomes)
            assertNull(model.draft)
            assertEquals(index + 2, server.read)
        }
        // nothing was sent twice
        assertEquals(failures.size, server.sent.size)
        // and no card was told of a status that did not change
        assertTrue(badges.isEmpty())
    }

    @Test
    fun `seasons - taken ones do not flip, All switches the open ones, and the body is the pick`() = runTest {
        val server = Server(series)
        val model = model(server, TitleCard("tv:9", 9, MediaType.TV, "Series 9", null, null, null))
        model.start()
        advanceUntilIdle()
        model.open(series.buttons.single())
        model.toggle(1)
        assertEquals(emptySet<Int>(), model.draft!!.selected)
        model.toggle(3)
        model.toggle(9)
        assertEquals(setOf(3), model.draft!!.selected)
        model.toggleAll()
        assertEquals(setOf(2, 3, 4), model.draft!!.selected)
        model.toggleAll()
        assertEquals(emptySet<Int>(), model.draft!!.selected)
        // nothing picked: nothing to send
        model.send()
        advanceUntilIdle()
        assertTrue(server.sent.isEmpty())
        model.toggle(4)
        model.toggle(2)
        model.send()
        advanceUntilIdle()
        assertEquals(listOf(RequestBody("tv", 9, is4k = false, seasons = listOf(2, 4))), server.sent)
        // the pick does not change while the request is out
        model.toggle(3)
        assertEquals(setOf(2, 4), model.draft!!.selected)
        server.answers.removeFirst().complete(approved(server.sent.single()))
        advanceUntilIdle()
    }

    @Test
    fun `a page that reads a fresher status than its card tells the card`() = runTest {
        val server = Server(film.withMedia(MediaInfo(tmdbId = 7, status = MediaStatus.AVAILABLE)))
        val model = model(server)
        model.start()
        advanceUntilIdle()
        assertEquals(listOf("movie:7" to "Available"), badges)
        // a copy with nothing left to ask for opens nothing
        model.open(RequestButton(RequestAction.REQUEST, is4k = false))
        assertNull(model.draft)
    }
    // ---- M6: the ratings, read after the page (DECISIONS 98) --------------------------------------

    /** Ratings that wait until the test answers them. */
    private class RatingsScript : RatingsSource {
        val asked = mutableListOf<Pair<MediaType, Int>>()
        val answers = ArrayDeque<CompletableDeferred<Ratings?>>()

        override suspend fun ratings(type: MediaType, tmdbId: Int): Ratings? {
            asked += type to tmdbId
            return CompletableDeferred<Ratings?>().also { answers.addLast(it) }.await()
        }
    }

    @Test
    fun `the ratings are asked for once the page is there, once, and the page never waits on them`() = runTest {
        val script = Script()
        val ratings = RatingsScript()
        val model = MediaModel(script, card, this, StandardTestDispatcher(testScheduler), ratingsSource = ratings)
        model.start()
        advanceUntilIdle()
        assertEquals("not before the page", emptyList<Pair<MediaType, Int>>(), ratings.asked)
        script.answers.removeFirst().complete(page("Film 7"))
        advanceUntilIdle()
        assertEquals(MediaState.Ready(page("Film 7")), model.state)
        assertEquals(listOf(MediaType.MOVIE to 7), ratings.asked)
        assertNull(model.ratings)
        val rt = Ratings(rt = RtRating(criticsRating = "Fresh", criticsScore = 80))
        ratings.answers.removeFirst().complete(rt)
        advanceUntilIdle()
        assertEquals(rt, model.ratings)
    }

    @Test
    fun `failed ratings leave none, and the page read again after a request does not ask again`() = runTest {
        val server = Server(film)
        val ratings = RatingsScript()
        val model = MediaModel(server, card, this, StandardTestDispatcher(testScheduler), requests = server, ratingsSource = ratings)
        model.start()
        advanceUntilIdle()
        ratings.answers.removeFirst().completeExceptionally(SeerrException.Unreachable(RuntimeException("down")))
        advanceUntilIdle()
        assertNull(model.ratings)
        assertTrue(model.state is MediaState.Ready)
        // a request: the page is read again quietly
        model.open(film.buttons.first())
        model.send()
        advanceUntilIdle()
        server.answers.removeFirst().complete(approved(server.sent.single()))
        advanceUntilIdle()
        assertEquals(2, server.read)
        assertEquals("the ratings once per page, not per read", 1, ratings.asked.size)
    }
    // ---- M6: Recommendations and Similar (DECISIONS 100) -------------------------------------------

    @Test
    fun `Recommendations and Similar are read once the page is there, Discover's filters applied, and take new badges`() = runTest {
        val server = Server(film)
        val asked = mutableListOf<RelatedKind>()
        val gate = CompletableDeferred<Unit>()
        val related = RelatedSource { _, _, kind ->
            asked += kind
            gate.await()
            DiscoverPage(
                "http://seerr.test",
                Page(results = listOf(MediaResult(id = 100 + kind.ordinal, mediaType = "movie", title = "Film"), MediaResult(id = 5, mediaType = "person", name = "Pat"))),
            )
        }
        val model = MediaModel(server, card, this, StandardTestDispatcher(testScheduler), relatedSource = related)
        model.start()
        advanceUntilIdle()
        assertEquals(setOf(RelatedKind.RECOMMENDATIONS, RelatedKind.SIMILAR), asked.toSet())
        assertTrue("the page never waits on them", model.state is MediaState.Ready)
        assertTrue(model.related.isEmpty())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("movie:100"), model.related[RelatedKind.RECOMMENDATIONS]?.map { it.key })
        assertEquals(listOf("movie:101"), model.related[RelatedKind.SIMILAR]?.map { it.key })
        model.setBadge("movie:101", mediaBadge(3))
        assertEquals("Requested", model.related[RelatedKind.SIMILAR]?.single()?.badge?.text)
        assertEquals(2, asked.size)
    }
    // ---- M6: the quota in the modal (DECISIONS 101) --------------------------------------------------

    @Test
    fun `a season's card opens the modal with that season ticked, where it can be (A4)`() = runTest {
        val server = Server(series)
        val model = model(server, TitleCard("tv:9", 9, MediaType.TV, "Series 9", null, null, null))
        model.start()
        advanceUntilIdle()
        val button = series.buttons.single()
        model.open(button, season = 3)
        assertEquals(RequestDraft(is4k = false, selected = setOf(3), season = 3), model.draft)
        model.send()
        advanceUntilIdle()
        assertEquals(listOf(RequestBody("tv", 9, is4k = false, seasons = listOf(3))), server.sent)
        server.answers.removeFirst().complete(approved(server.sent.single()))
        advanceUntilIdle()

        // a button's way in is as it was: nothing ticked, no season
        model.open(button)
        assertEquals(RequestDraft(is4k = false), model.draft)
        model.cancel()
        // a taken season is not ticked; with partial requests off nothing is picked (every open season goes)
        model.open(button, season = 1)
        assertEquals(RequestDraft(is4k = false, season = 1), model.draft)
        model.cancel()
        val whole = series.copy(settings = series.settings.copy(partialRequestsEnabled = false))
        val wholeModel = model(Server(whole))
        wholeModel.start()
        advanceUntilIdle()
        wholeModel.open(whole.buttons.single(), season = 3)
        assertEquals(RequestDraft(is4k = false, season = 3), wholeModel.draft)
        // past the quota, the season is not ticked either
        val limited = series.copy(quota = Quota(tv = QuotaStatus(limit = 2, used = 2, remaining = 0)))
        val limitedModel = model(Server(limited))
        limitedModel.start()
        advanceUntilIdle()
        limitedModel.open(limited.buttons.single(), season = 3)
        assertEquals(emptySet<Int>(), limitedModel.draft?.selected)
    }

    @Test
    fun `past the quota a season stays off, all does nothing, and nothing is sent`() = runTest {
        val limited = series.copy(quota = Quota(tv = QuotaStatus(limit = 2, used = 1, remaining = 1)))
        val server = Server(limited)
        val model = model(server)
        model.start()
        advanceUntilIdle()
        model.open(limited.buttons.single())
        model.toggle(2)
        model.toggle(3)
        assertEquals("one season left: the second stays off", setOf(2), model.draft?.selected)
        model.toggleAll()
        assertEquals("three open seasons are past one", setOf(2), model.draft?.selected)
        model.send()
        advanceUntilIdle()
        assertEquals(1, server.sent.size)
        assertEquals(listOf(2), server.sent.single().seasons)
        server.answers.removeFirst().complete(approved(server.sent.single()))
        advanceUntilIdle()

        val none = film.copy(quota = Quota(movie = QuotaStatus(limit = 1, used = 1, remaining = 0, restricted = true)))
        val held = Server(none)
        val filmModel = model(held)
        filmModel.start()
        advanceUntilIdle()
        filmModel.open(none.buttons.first())
        filmModel.send()
        advanceUntilIdle()
        assertEquals("none left: nothing sent", emptyList<RequestBody>(), held.sent)
    }

    @Test
    fun `the quiet re-read waits while the modal is open or a request is out`() = runTest {
        val server = Server(film)
        val model = model(server)
        model.start()
        advanceUntilIdle()
        model.reread()
        advanceUntilIdle()
        assertEquals(2, server.read)
        model.open(film.buttons.first())
        model.reread()
        advanceUntilIdle()
        assertEquals("not with the modal open", 2, server.read)
    }
    // ---- M6: Report an Issue (DECISIONS 106) -------------------------------------------------------------

    private val inLibrary = film.copy(
        media = MediaInfo(id = 41, tmdbId = 7, status = MediaStatus.AVAILABLE),
        user = User(id = 8, permissions = Permission.REQUEST + Permission.CREATE_ISSUES),
    )

    private class Reports : IssueReporter {
        val sent = mutableListOf<IssueBody>()
        val answers = ArrayDeque<CompletableDeferred<Unit>>()

        override suspend fun report(body: IssueBody) {
            sent += body
            CompletableDeferred<Unit>().also { answers.addLast(it) }.await()
        }
    }

    private fun TestScope.reporting(server: Server, reports: Reports) = MediaModel(
        source = server,
        card = card,
        scope = this,
        work = StandardTestDispatcher(testScheduler),
        requests = server,
        issues = reports,
    )

    @Test
    fun `the form opens only where the page offers it, sends once with words, and closes when sent`() = runTest {
        val reports = Reports()
        val plain = reporting(Server(film), reports)
        plain.start()
        advanceUntilIdle()
        plain.openIssue()
        assertEquals("not in the library, no right: no form", null, plain.issue)

        val model = reporting(Server(inLibrary), reports)
        model.start()
        advanceUntilIdle()
        model.openIssue()
        assertEquals(IssueDraft(IssueType.VIDEO, season = 0), model.issue)
        val outcomes = mutableListOf<IssueOutcome>()
        model.sendIssue { outcomes += it }
        advanceUntilIdle()
        assertEquals("no words, nothing sent", emptyList<IssueBody>(), reports.sent)
        model.editIssue(model.issue!!.copy(type = IssueType.AUDIO, message = "Out of sync."))
        model.sendIssue { outcomes += it }
        model.sendIssue { outcomes += it }
        advanceUntilIdle()
        assertEquals(listOf(IssueBody(IssueType.AUDIO, "Out of sync.", mediaId = 41)), reports.sent)
        assertTrue(model.reporting)
        model.closeIssue()
        model.editIssue(model.issue!!.copy(message = "changed"))
        assertEquals("nothing taken while it is out", "Out of sync.", model.issue?.message)
        reports.answers.removeFirst().complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf<IssueOutcome>(IssueOutcome.Sent("Film 7")), outcomes)
        assertEquals(null, model.issue)
        assertFalse(model.reporting)
        // said on the button from now on (A5, tvOS's), and the form still opens for another
        assertTrue("Issue Reported", model.issueReported)
        model.openIssue()
        assertEquals(IssueDraft(IssueType.VIDEO, season = 0), model.issue)
    }

    @Test
    fun `a failure keeps the form to try again, a refusal closes it and reads the page again`() = runTest {
        val failing = object : IssueReporter {
            var next: Exception = RuntimeException("down")
            override suspend fun report(body: IssueBody) = throw next
        }
        val server = Server(inLibrary)
        val model = MediaModel(server, card, this, StandardTestDispatcher(testScheduler), requests = server, issues = failing)
        model.start()
        advanceUntilIdle()
        model.openIssue()
        model.editIssue(model.issue!!.copy(message = "Black screen."))
        val outcomes = mutableListOf<IssueOutcome>()
        model.sendIssue { outcomes += it }
        advanceUntilIdle()
        assertEquals(IssueOutcome.Failed, outcomes.last())
        assertEquals("Black screen.", model.issue?.message)
        assertFalse("not reported: the button does not say so (A5)", model.issueReported)
        val reads = server.read
        failing.next = SeerrException.Refused("no right")
        model.sendIssue { outcomes += it }
        advanceUntilIdle()
        assertEquals(IssueOutcome.NoRight, outcomes.last())
        assertEquals(null, model.issue)
        assertEquals("read again", reads + 1, server.read)
        assertFalse("refused: not reported (A5)", model.issueReported)
    }

    @Test
    fun `a request's failure is shown once - seen, it is gone, and the next one is its own (A5)`() = runTest {
        val server = Server(film)
        val model = model(server)
        model.start()
        advanceUntilIdle()
        model.open(film.buttons.first())
        model.send()
        advanceUntilIdle()
        server.answers.removeFirst().completeExceptionally(SeerrException.Unreachable(RuntimeException("timeout")))
        advanceUntilIdle()
        assertEquals(RequestFailure.UNREACHABLE, (model.outcome as RequestOutcome.Failed).failure)
        // the page's dialog has been shown and closed: coming back to the page shows nothing again
        model.outcomeSeen()
        assertNull(model.outcome)
        assertEquals("the count stays: it only ever grows", 1, model.outcomes)
        model.open(film.buttons.first())
        model.send()
        advanceUntilIdle()
        server.answers.removeFirst().completeExceptionally(SeerrException.Http(500, null))
        advanceUntilIdle()
        assertEquals(RequestFailure.OTHER, (model.outcome as RequestOutcome.Failed).failure)
        assertEquals(2, model.outcomes)
    }
}
