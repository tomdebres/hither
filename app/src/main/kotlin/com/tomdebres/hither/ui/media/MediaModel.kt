package com.tomdebres.hither.ui.media

import com.tomdebres.hither.core.rules.issueDraft

import com.tomdebres.hither.core.rules.issueBody

import com.tomdebres.hither.core.rules.canReportIssue

import com.tomdebres.hither.core.rules.IssueDraft

import com.tomdebres.hither.core.model.IssueBody

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tomdebres.hither.core.SeerrException
import com.tomdebres.hither.core.model.MediaRequest
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.model.Ratings
import com.tomdebres.hither.core.rules.RelatedKind
import com.tomdebres.hither.core.rules.appendPage
import com.tomdebres.hither.core.rules.withBadge
import com.tomdebres.hither.ui.discover.DiscoverPage
import androidx.compose.runtime.mutableStateMapOf
import com.tomdebres.hither.core.model.RequestBody
import com.tomdebres.hither.core.model.RequestStatus
import com.tomdebres.hither.core.rules.MediaPage
import com.tomdebres.hither.core.rules.RequestAction
import com.tomdebres.hither.core.rules.RequestButton
import com.tomdebres.hither.core.rules.RequestFailure
import com.tomdebres.hither.core.rules.StatusBadge
import com.tomdebres.hither.core.rules.TitleCard
import com.tomdebres.hither.core.rules.afterRequest
import com.tomdebres.hither.core.rules.moviePage
import com.tomdebres.hither.core.rules.requestBody
import com.tomdebres.hither.core.rules.requestFailure
import com.tomdebres.hither.core.rules.requestModal
import com.tomdebres.hither.core.rules.tvPage
import com.tomdebres.hither.session.SeerrAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the media page is with its title. */
sealed interface MediaState {
    /** Asked for; the card's poster, title and badge stand in meanwhile. */
    data object Loading : MediaState

    /** The server did not give it: the reason and Try Again. */
    data object Failed : MediaState

    data class Ready(val page: MediaPage) : MediaState
}

/**
 * What had focus on the page when it was left for another (a keyword's titles; M6), so coming back
 * lands there: the page's place on the stack keeps its model (DECISIONS 98).
 */
sealed interface MediaFocus {
    /** A keyword tag, by its place in the row. */
    data class Keyword(val index: Int) : MediaFocus

    /** A cast card (its person's page, M6, DECISIONS 100). */
    data class Cast(val index: Int) : MediaFocus

    /** A card in Recommendations or Similar. */
    data class Related(val kind: RelatedKind, val index: Int) : MediaFocus

    /** The collection card (its films' grid, polish 2). */
    data object Collection : MediaFocus
}

/**
 * The request modal while it is open: which copy, and the seasons switched on. [season] is the season whose card on the
 * page opened it (A4, tvOS's D2), chosen from the start; null when a request button opened it.
 */
data class RequestDraft(val is4k: Boolean, val selected: Set<Int> = emptySet(), val season: Int? = null)

/** What the last request came to: a 201 shows on the page, anything else is a `ProblemDialog` (A5). */
sealed interface RequestOutcome {
    /** 201: the request exists; [approved] when the server approved it at once. */
    data class Created(val title: String, val approved: Boolean) : RequestOutcome

    /** Anything else, with the server's own sentence where it sent one (a 403's reason). */
    data class Failed(val failure: RequestFailure, val title: String, val serverMessage: String?) : RequestOutcome
}

/**
 * One title's page, opened from [card]: read once on arrival ([start]) and again on Try Again
 * ([retry]). The title, the user's permissions and the server's switches are read together
 * every time, so the buttons follow what the account may do now, not at sign-in (DECISIONS 14).
 *
 * It also carries the request modal ([open], [toggle], [toggleAll], [cancel], [send]). One press
 * is one request: while one is out ([sending]) nothing opens or sends another, and nothing here
 * ever repeats one (DECISIONS 21, 38). The request runs on [requestScope], which outlives the
 * page, so an answer that comes after Back still reaches the card ([onBadge]). After any answer
 * the modal closes, [outcome] says what happened, a 201 shows the new status at once (worked
 * out from the answer), and the page is read again quietly to take the server's own word.
 *
 * State is Compose state, changed on the scopes' thread; the answers are read on [work].
 */
class MediaModel(
    private val source: MediaSource,
    val card: TitleCard,
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher = Dispatchers.Default,
    private val requests: RequestSender = RequestSender { error("this page sends no requests") },
    /** The title's ratings, read once the page is there (M6); none from a page that has no source. */
    private val ratingsSource: RatingsSource = RatingsSource { _, _ -> null },
    /** Recommendations and Similar, read once the page is there (M6); none from a page that has no source. */
    private val relatedSource: RelatedSource? = null,
    private val requestScope: CoroutineScope = scope,
    /** A title's card badge has changed: the card it came from (and any other) shows the new one. */
    private val onBadge: (key: String, badge: StatusBadge?) -> Unit = { _, _ -> },
    /** Where an issue goes (M6, DECISIONS 106); none from a page that reports none. */
    private val issues: IssueReporter? = null,
) {
    var state: MediaState by mutableStateOf(MediaState.Loading)
        private set

    var draft: RequestDraft? by mutableStateOf(null)
        private set

    var sending: Boolean by mutableStateOf(false)
        private set

    var outcome: RequestOutcome? by mutableStateOf(null)
        private set

    /**
     * Rotten Tomatoes' and IMDb's scores, read after the page (Seerr's web asks for them apart, and a
     * page never waits on them); null until they come, or when the server has none.
     */
    var ratings: Ratings? by mutableStateOf(null)
        private set

    /** The page's scroll, kept with the model, so coming back to it finds it where it was. */
    val scroll = ScrollState(0)

    /** Where focus was when the page was left for another; taken (and cleared) on coming back. */
    var returnFocus: MediaFocus? = null

    /** The keyword tag the row was left on, so focus comes back to it. */
    var keywordIndex: Int by mutableIntStateOf(0)

    /** The keyword row's scroll, kept with the model as the page's is. */
    val keywordRow = LazyListState()

    /** The cast card the row was left on, and the row's scroll (M6: Back from a person's page). */
    var castIndex: Int by mutableIntStateOf(0)
    val castRow = LazyListState()

    /**
     * Seerr's Recommendations and Similar sliders under the cast (M6, DECISIONS 100): their first page each,
     * Discover's filters applied, read after the page and never holding it up; empty until they come, or
     * where the server has none.
     */
    var related: Map<RelatedKind, List<TitleCard>> by mutableStateOf(emptyMap())
        private set

    /** The card each related row was left on, and each row's scroll. */
    val relatedIndex = mutableStateMapOf<RelatedKind, Int>().apply { RelatedKind.entries.forEach { put(it, 0) } }
    val relatedRows: Map<RelatedKind, LazyListState> = RelatedKind.entries.associateWith { LazyListState() }

    /** A title's new status (a request made from another page): its card in these rows shows it. */
    fun setBadge(key: String, badge: StatusBadge?) {
        val updated = related.mapValues { (_, cards) -> withBadge(cards, key, badge) }
        if (updated.any { (kind, cards) -> cards !== related[kind] }) related = updated
    }

    /** Counts the outcomes, so the same words twice in a row still show twice. */
    var outcomes: Int by mutableIntStateOf(0)
        private set

    private var started = false

    /** Bumped on every load, so an answer to an older one is dropped. */
    private var generation = 0

    private val page: MediaPage? get() = (state as? MediaState.Ready)?.page

    fun start() {
        if (started) return
        started = true
        load()
    }

    /** Try Again: only after a failure, and never while a load is out. */
    fun retry() {
        if (state == MediaState.Failed) load()
    }

    /**
     * A request button: the modal for its copy, if that copy may still be asked for. From a season's card on the page
     * (A4, tvOS's D2), [season]: the modal for the copy that season is open in, with the season switched on where the
     * modal lets seasons be picked and the quota has room for it (with partial requests off, every open season goes,
     * that one among them). A button's own way in is as it was.
     */
    fun open(button: RequestButton, season: Int? = null) {
        val page = page ?: return
        if (sending || draft != null) return
        if (page.options.actionFor(button.is4k) == RequestAction.NONE) return
        val picked = season?.takeIf { requestModal(page, button.is4k, setOf(it), sending = false).let { view -> view.picker && it in view.picked } }
        draft = RequestDraft(button.is4k, selected = setOfNotNull(picked), season = season)
    }

    /** A season row: on or off. Seasons already taken do not flip. */
    fun toggle(seasonNumber: Int) {
        val draft = draft ?: return
        val page = page ?: return
        if (sending) return
        val view = requestModal(page, draft.is4k, draft.selected, sending = false)
        if (!view.picker || view.rows.none { it.seasonNumber == seasonNumber && !it.taken }) return
        // Past the quota a season is greyed and stays off (Seerr's TvRequestModal, M6).
        if (seasonNumber !in draft.selected && view.rows.any { it.seasonNumber == seasonNumber && it.overQuota }) return
        val selected = if (seasonNumber in draft.selected) draft.selected - seasonNumber else draft.selected + seasonNumber
        this.draft = draft.copy(selected = selected)
    }

    /** The "all seasons" row: every open season on, or, when they all are, off. */
    fun toggleAll() {
        val draft = draft ?: return
        val page = page ?: return
        if (sending) return
        val view = requestModal(page, draft.is4k, draft.selected, sending = false)
        if (!view.picker) return
        val open = view.rows.filter { !it.taken }.map { it.seasonNumber }.toSet()
        // Seerr leaves "all" off when the open seasons are more than the quota has left.
        val quota = view.quota
        if (quota != null && open.size > quota.remaining && !open.all { it in draft.selected }) return
        this.draft = draft.copy(selected = if (open.isNotEmpty() && open.all { it in draft.selected }) emptySet() else open)
    }

    /** Cancel or Back: the modal closes. A request already out carries on and is reported. */
    fun cancel() {
        draft = null
    }

    /** The primary button: one request for what is picked, unless one is already out. */
    fun send() {
        val draft = draft ?: return
        val page = page ?: return
        if (sending) return
        // Nothing past the quota, which Seerr would refuse (M6): the button is off, and so is this.
        if (!requestModal(page, draft.is4k, draft.selected, sending = false).primaryEnabled) return
        val body = requestBody(page, draft.is4k, draft.selected) ?: return
        sending = true
        requestScope.launch {
            val answer: Result<MediaRequest> = try {
                Result.success(withContext(work) { requests.request(body) })
            } catch (e: CancellationException) {
                sending = false
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            sending = false
            this@MediaModel.draft = null
            answer.fold(
                onSuccess = { created ->
                    // The new status at once, from the answer: "Pending" or "Requested" (2.9).
                    val now = this@MediaModel.page ?: page
                    val updated = now.withMedia(afterRequest(now.media, created, now.type, now.tmdbId))
                    if (state is MediaState.Ready) state = MediaState.Ready(updated)
                    onBadge(card.key, updated.cardBadge)
                    report(RequestOutcome.Created(page.title, approved = created.status == RequestStatus.APPROVED))
                },
                onFailure = { error ->
                    val message = (error as? SeerrException.Refused)?.serverMessage
                    report(RequestOutcome.Failed(requestFailure(error), page.title, message))
                },
            )
            refresh()
        }
    }

    private fun report(outcome: RequestOutcome) {
        this.outcome = outcome
        outcomes++
    }

    /**
     * The page has shown [outcome] (A5: a failure's `ProblemDialog`, once): it is not shown again when the page comes
     * back from one it opened.
     */
    fun outcomeSeen() {
        outcome = null
    }

    private fun load() {
        val generation = ++generation
        state = MediaState.Loading
        scope.launch {
            val page = try {
                withContext(work) { source.page(card.type, card.tmdbId) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (this@MediaModel.generation == generation) state = MediaState.Failed
                return@launch
            }
            if (this@MediaModel.generation == generation) shown(page)
        }
    }

    /**
     * The page again, quietly, while it shows a download (M6, DECISIONS 101): at most once a minute, as Seerr's
     * download sync runs. Nothing while a request is out or the modal is open.
     */
    fun reread() {
        if (state is MediaState.Ready && !sending && draft == null && issue == null) refresh()
    }

    // ---- M6: Report an Issue (DECISIONS 106) ------------------------------------------------------------

    /** Whether this page can send an issue at all (it was given somewhere to send one). */
    val reportsIssues: Boolean get() = issues != null

    /** The issue form while it is open. */
    var issue: IssueDraft? by mutableStateOf(null)
        private set

    /** The report is out: the form stays, Submit reads "Submitting…", and nothing else is taken. */
    var reporting: Boolean by mutableStateOf(false)
        private set

    /**
     * An issue has been reported from this page (A5, tvOS's): Report an Issue says "Issue Reported" in place, as long as
     * the page is kept, and still opens the form for another.
     */
    var issueReported: Boolean by mutableStateOf(false)
        private set

    /** Opens Seerr's issue form, only where the page offers it (the rights as the page read them). */
    fun openIssue() {
        val page = page ?: return
        if (issues == null || !canReportIssue(page) || issue != null) return
        issue = issueDraft(page)
    }

    fun editIssue(next: IssueDraft) {
        if (issue != null && !reporting) issue = next
    }

    fun closeIssue() {
        if (!reporting) issue = null
    }

    /**
     * Sends the issue once: nothing without words, nothing while one is out. Sent, the form closes. Refused
     * (the right has gone since the page was read), the form closes and the page is read again, so the
     * button goes. Failed, the form stays to try again.
     */
    fun sendIssue(done: (IssueOutcome) -> Unit) {
        val page = page ?: return
        val draft = issue ?: return
        val reporter = issues ?: return
        if (reporting) return
        val body = issueBody(page, draft) ?: return
        reporting = true
        scope.launch {
            val outcome = try {
                withContext(work) { reporter.report(body) }
                IssueOutcome.Sent(page.title)
            } catch (e: CancellationException) {
                reporting = false
                throw e
            } catch (_: SeerrException.Refused) {
                IssueOutcome.NoRight
            } catch (_: Exception) {
                IssueOutcome.Failed
            }
            reporting = false
            if (outcome is IssueOutcome.Sent) issueReported = true
            if (outcome != IssueOutcome.Failed) issue = null
            if (outcome == IssueOutcome.NoRight) refresh()
            done(outcome)
        }
    }

    /** The page again, quietly: on success it replaces the one shown; a failure keeps it. */
    private fun refresh() {
        val generation = ++generation
        scope.launch {
            val page = try {
                withContext(work) { source.page(card.type, card.tmdbId) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            if (this@MediaModel.generation == generation && state is MediaState.Ready) shown(page)
        }
    }

    private var ratingsAsked = false

    private fun shown(page: MediaPage) {
        state = MediaState.Ready(page)
        // Once per page: the ratings change slowly, and a failure leaves the line to TMDB's score.
        if (!ratingsAsked) {
            ratingsAsked = true
            val related = relatedSource
            if (related != null) {
                for (kind in RelatedKind.entries) {
                    scope.launch {
                        val answer = try {
                            withContext(work) { related.related(page.type, page.tmdbId, kind) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            return@launch
                        }
                        val cards = appendPage(emptyList(), answer.page.results, answer.images, page.settings, page.user.permissions)
                        this@MediaModel.related = this@MediaModel.related + (kind to cards)
                    }
                }
            }
            scope.launch {
                ratings = try {
                    withContext(work) { ratingsSource.ratings(page.type, page.tmdbId) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        }
        // Live status: the card the page came from takes what the page has just read.
        if (page.cardBadge != card.badge) onBadge(card.key, page.cardBadge)
    }
}

/** Where a request goes: the signed-in server, or a test's script. */
/** What reporting an issue came to (M6, DECISIONS 106): sent shows on the button, the rest are `ProblemDialog`s (A5). */
sealed interface IssueOutcome {
    data class Sent(val title: String) : IssueOutcome

    data object NoRight : IssueOutcome

    data object Failed : IssueOutcome
}

/** Sends an issue (`POST /issue`): the server, or a test's script. */
fun interface IssueReporter {
    suspend fun report(body: IssueBody)
}

/** Issues to the server: Seerr's 403 without the right is a refusal (the client's guarded call). */
fun seerrIssueReporter(access: SeerrAccess) = IssueReporter { body -> access.call { client -> client.reportIssue(body) } }

fun interface RequestSender {
    suspend fun request(body: RequestBody): MediaRequest
}

/**
 * Requests to the signed-in server, through the call that renews an ended session: a request
 * answered 401 or Seerr's own 403 was refused before any route ran, so it is sent once more
 * after the renewal; nothing else is ever repeated (DECISIONS 38, 55).
 */
fun seerrRequests(access: SeerrAccess) = RequestSender { body -> access.call { client -> client.request(body) } }

/** Where a title's Recommendations and Similar come from: the signed-in server, or a test's script. */
fun interface RelatedSource {
    suspend fun related(type: MediaType, tmdbId: Int, kind: RelatedKind): DiscoverPage
}

/** Seerr's two sliders from the signed-in server: their first page. */
fun seerrRelated(access: SeerrAccess) = RelatedSource { type, tmdbId, kind ->
    access.call { client ->
        DiscoverPage(client.images, if (kind == RelatedKind.RECOMMENDATIONS) client.recommendations(type, tmdbId) else client.similar(type, tmdbId))
    }
}

/** Where a title's ratings come from: the signed-in server, or a test's script. Null: none found. */
fun interface RatingsSource {
    suspend fun ratings(type: MediaType, tmdbId: Int): Ratings?
}

/** The ratings from the signed-in server (`ratingscombined`, `tv/{id}/ratings`; its 404 is none). */
fun seerrRatings(access: SeerrAccess) = RatingsSource { type, tmdbId -> access.call { client -> client.ratings(type, tmdbId) } }

/** Where a title's page comes from: the signed-in server, or a test's script. */
fun interface MediaSource {
    suspend fun page(type: MediaType, tmdbId: Int): MediaPage
}

/**
 * The page from the signed-in server: the title, `settings/public` and `auth/me` asked for at
 * once, through the call that renews an ended session (all three again after a renewal).
 */
fun seerrMediaSource(access: SeerrAccess) = MediaSource { type, tmdbId ->
    access.call { client ->
        coroutineScope {
            val settings = async { client.publicSettings() }
            val user = async { client.me() }
            // The account's limits for the request modal (M6): read with the page; none if they will not come.
            val quota = async {
                val id = user.await().id
                try {
                    client.quota(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SeerrException.SignedOut) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            val page = when (type) {
                MediaType.MOVIE -> moviePage(client.movie(tmdbId), client.images, settings.await(), user.await())
                MediaType.TV -> tvPage(client.tv(tmdbId), client.images, settings.await(), user.await())
            }
            page.copy(quota = quota.await())
        }
    }
}
