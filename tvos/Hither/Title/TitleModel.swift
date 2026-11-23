import Foundation
import HitherCore
import Observation

/// Where the title page is with its title (Android's `MediaState`).
enum TitleState {
    /// Asked for; the card's poster, title and badge stand in meanwhile.
    case loading
    /// The server did not give it: the reason and Try Again.
    case failed
    case ready(MediaPage)
}

/// The request modal while it is open: which copy, and the seasons switched on (Android's `RequestDraft`). [season] is
/// the season whose card on the page opened it (Tom's D2), chosen from the start.
struct RequestDraft: Equatable {
    let is4k: Bool
    var selected: Set<Int32> = []
    var season: Int32?
}

/// One title's page (Android's `MediaModel`), opened from [card]: the title, `settings/public`, `auth/me` and the quota
/// read together on arrival and on Try Again, so the buttons follow what the account may do now, not at sign-in
/// (DECISIONS 14). Ratings and Recommendations and Similar are read once the page is there and never hold it up.
///
/// It carries the request too (a film's alert, a series' sheet). One press is one request: while one is out nothing
/// opens or sends another, and nothing here repeats one (DECISIONS 21, 38). The request is not tied to the page: an
/// answer that comes after Menu still reaches the card through the badge hub. While it is out the request button says
/// so; after any answer the sheet closes, a 201 shows the new status at once (core's `afterRequest`) on the page's
/// status line and buttons, a failure is an alert ([problem]; Tom's D4: no toast either way), and the page is read
/// again quietly.
@MainActor
@Observable
final class TitleModel: TakesBadges {
    let card: TitleCard
    private(set) var state: TitleState = .loading
    private(set) var draft: RequestDraft?
    private(set) var sending = false
    /// Rotten Tomatoes' and IMDb's scores, read after the page; nil until they come, or when the server has none.
    private(set) var ratings: Ratings?
    /// Seerr's Recommendations and Similar under the cast: their first page each, Discover's filters applied.
    private(set) var related: [RelatedKind: [TitleCard]] = [:]
    /// Seerr's issue form while it is open (Android's `MediaModel.issue`, DECISIONS 106).
    private(set) var issue: IssueDraft?
    /// An issue report is out: one press, one report ("Submitting…").
    private(set) var reporting = false
    /// An issue was reported from this page: Report an Issue says so in place (Tom's D4), and still opens the form.
    private(set) var issueReported = false
    /// What went wrong, for the page's alert: a request, a report the form cannot keep, an app that would not open.
    private(set) var problem: AlertText?
    /// A report that went wrong while the form stays open to try again: the form's own alert.
    private(set) var issueProblem: AlertText?

    private let session: Session
    private let gateway: SessionGateway
    private let hub: BadgeHub
    private var started = false
    /// Bumped on every load, so an answer to an older one is dropped.
    private var generation = 0
    private var extrasAsked = false

    init(card: TitleCard, session: Session, gateway: SessionGateway, hub: BadgeHub) {
        self.card = card
        self.session = session
        self.gateway = gateway
        self.hub = hub
        hub.join(self)
    }

    var page: MediaPage? {
        if case .ready(let page) = state { return page }
        return nil
    }

    func start() {
        guard !started else { return }
        started = true
        load()
    }

    /// Try Again: only after a failure.
    func retry() {
        if case .failed = state { load() }
    }

    // MARK: - The request modal

    /// A request button: the modal for its copy, if that copy may still be asked for. From a season's card (Tom's D2),
    /// the modal for the copy that season is open in, with the season chosen where the sheet lets seasons be picked and
    /// the quota has room for it (with partial requests off, every open season goes, that one among them).
    func open(_ button: RequestButton, season: Int32? = nil) {
        guard let page, !sending, draft == nil, page.options.actionFor(is4k: button.is4k) != .none else { return }
        var draft = RequestDraft(is4k: button.is4k, season: season)
        if let season {
            let one = KotlinInt(int: season)
            let view = RequestFlowKt.requestModal(page: page, is4k: button.is4k, selected: [one], sending: false)
            if view.picker, view.picked.contains(one) { draft.selected = [season] }
        }
        self.draft = draft
    }

    /// The modal as it stands: core's `requestModal` for the copy and the pick.
    func modal(_ draft: RequestDraft, _ page: MediaPage) -> RequestModalView {
        RequestFlowKt.requestModal(page: page, is4k: draft.is4k, selected: Set(draft.selected.map { KotlinInt(int: $0) }), sending: sending)
    }

    /// A season row: on or off. Seasons already taken, and past the quota, do not flip on.
    func toggle(_ season: Int32) {
        guard var draft, let page, !sending else { return }
        let view = RequestFlowKt.requestModal(page: page, is4k: draft.is4k, selected: Set(draft.selected.map { KotlinInt(int: $0) }), sending: false)
        guard view.picker, view.rows.contains(where: { $0.seasonNumber == season && !$0.taken }) else { return }
        if !draft.selected.contains(season), view.rows.contains(where: { $0.seasonNumber == season && $0.overQuota }) { return }
        if draft.selected.contains(season) { draft.selected.remove(season) } else { draft.selected.insert(season) }
        self.draft = draft
    }

    /// The "all seasons" row: every open season on, or, when they all are, off. Seerr leaves it off when the open
    /// seasons are more than the quota has left.
    func toggleAll() {
        guard var draft, let page, !sending else { return }
        let view = RequestFlowKt.requestModal(page: page, is4k: draft.is4k, selected: Set(draft.selected.map { KotlinInt(int: $0) }), sending: false)
        guard view.picker else { return }
        let open = Set(view.rows.filter { !$0.taken }.map(\.seasonNumber))
        if let quota = view.quota, open.count > Int(quota.remaining), !open.isSubset(of: draft.selected) { return }
        draft.selected = (!open.isEmpty && open.isSubset(of: draft.selected)) ? [] : open
        self.draft = draft
    }

    /// Cancel or Menu: the modal closes. A request already out carries on, and the modal goes with its answer.
    func cancel() {
        if !sending { draft = nil }
    }

    /// The primary button (the film's alert's Request, the series sheet's last row): one request for what is picked,
    /// unless one is already out. [asked] is the draft the alert was showing, in case the alert's closing has already
    /// cleared it.
    func send(_ asked: RequestDraft? = nil) {
        guard let draft = asked ?? draft, let page, !sending else { return }
        self.draft = draft
        let selected = Set(draft.selected.map { KotlinInt(int: $0) })
        guard RequestFlowKt.requestModal(page: page, is4k: draft.is4k, selected: selected, sending: false).primaryEnabled,
              let body = RequestFlowKt.requestBody(page: page, is4k: draft.is4k, selected: selected) else { return }
        sending = true
        Task {
            do {
                let created = try await gateway.call { client in try await client.request(body: body) }
                sending = false
                self.draft = nil
                // The new status at once, from the answer: "Pending" or "Requested" (2.9).
                let now = self.page ?? page
                let updated = now.withMedia(media: RequestFlowKt.afterRequest(media: now.media, created: created, type: now.type, tmdbId: now.tmdbId))
                if case .ready = state { state = .ready(updated) }
                hub.set(card.key, updated.cardBadge)
            } catch {
                sending = false
                self.draft = nil
                problem = AlertText(title: Words.requestNotSent, message: Self.failureWords(error, title: page.title))
            }
            refresh()
        }
    }

    /// What a request that was not created came to, in Android's words; Seerr's own sentence where it sent one.
    private static func failureWords(_ error: Error, title: String) -> String {
        guard let throwable = Kotlin.throwable(error) else { return Words.requestError }
        switch RequestFlowKt.requestFailure(error: throwable) {
        case .nothingToRequest: return Words.requestNothingLeft
        case .alreadyRequested: return Words.requestAlready(title)
        case .refused: return (throwable as? SeerrException.Refused)?.serverMessage ?? Words.requestRefused
        case .unreachable: return Words.requestUnreachable
        case .csrfNeedsHttps: return Words.requestCsrf
        default: return Words.requestError
        }
    }

    /// The page again, quietly, while it shows a download: Seerr's download sync runs once a minute. Nothing while a
    /// request is out or a modal is open.
    func reread() {
        if case .ready = state, !sending, draft == nil, issue == nil { refresh() }
    }

    // MARK: - Report an Issue (Android's `MediaModel`, DECISIONS 106)

    /// Opens Seerr's issue form, only where the page offers it (core's `canReportIssue`: the rights as the page read
    /// them, a copy in the library).
    func openIssue() {
        guard let page, IssuesKt.canReportIssue(page: page), issue == nil, draft == nil else { return }
        issue = IssuesKt.issueDraft(page: page)
    }

    func editIssue(_ next: IssueDraft) {
        if issue != nil, !reporting { issue = next }
    }

    /// Cancel or Menu: the form closes, unless a report is out.
    func closeIssue() {
        if !reporting { issue = nil }
    }

    /// Sends the issue once: nothing without words (core's `issueBody`), nothing while one is out. Sent, the form
    /// closes and Report an Issue says it was reported ([issueReported]). Seerr's 403 (the right gone since the page was
    /// read) closes it too, says so in the page's alert and reads the page again, so the button goes. Any other failure
    /// keeps the form to try again, its alert saying why.
    func sendIssue() {
        guard let page, let draft = issue, !reporting, let body = IssuesKt.issueBody(page: page, draft: draft) else { return }
        reporting = true
        Task {
            do {
                _ = try await gateway.call { client in try await client.reportIssue(body: body) }
                reporting = false
                issue = nil
                issueReported = true
            } catch where Kotlin.isRefused(error) {
                reporting = false
                issue = nil
                problem = AlertText(title: Words.issueNotSent, message: Words.issueNoRight)
                refresh()
            } catch {
                reporting = false
                issueProblem = AlertText(title: Words.issueNotSent, message: Words.issueFailed)
            }
        }
    }

    // MARK: - What went wrong

    /// Something the page did went wrong away from the model (an app that would not open): the page's alert.
    func report(_ problem: AlertText) {
        self.problem = problem
    }

    /// The page's alert has closed.
    func problemSeen() {
        problem = nil
    }

    /// The form's alert has closed: the form is there to try again.
    func issueProblemSeen() {
        issueProblem = nil
    }

    // MARK: - Badges

    /// A title's new status (a request made from another page): its card in these rows shows it.
    func setBadge(_ key: String, _ badge: StatusBadge?) {
        for (kind, cards) in related {
            let updated = DiscoverKt.withBadge(cards: cards, key: key, badge: badge)
            if updated != cards { related[kind] = updated }
        }
    }

    // MARK: - Reading

    private func load() {
        generation += 1
        let generation = generation
        state = .loading
        Task {
            do {
                let page = try await read()
                if self.generation == generation { shown(page) }
            } catch {
                if self.generation == generation { state = .failed }
            }
        }
    }

    /// The page again, quietly: on success it replaces the one shown; a failure keeps it.
    private func refresh() {
        generation += 1
        let generation = generation
        Task {
            guard let page = try? await read() else { return }
            if self.generation == generation, case .ready = state { shown(page) }
        }
    }

    /// The title, `settings/public`, `auth/me` and the account's quota, asked for at once through the call that renews
    /// an ended session (all of them again after a renewal). A quota that will not come is none.
    private func read() async throws -> MediaPage {
        let type = card.type
        let tmdbId = card.tmdbId
        return try await gateway.call { client in
            async let settings = client.publicSettings()
            async let user = client.me()
            let me = try await user
            let quota: Quota?
            do {
                quota = try await client.quota(userId: me.id)
            } catch where Kotlin.isSignedOut(error) {
                throw error
            } catch {
                quota = nil
            }
            let page: MediaPage
            if type == .movie {
                let details = try await client.movie(tmdbId: tmdbId)
                page = MediaPageKt.moviePage(details: details, baseUrl: client.baseUrl, settings: try await settings, user: me)
                    .withRuntime(details.runtime?.intValue)
            } else {
                let details = try await client.tv(tmdbId: tmdbId)
                page = MediaPageKt.tvPage(details: details, baseUrl: client.baseUrl, settings: try await settings, user: me)
                    .withEpisodeRuntime(details.episodeRunTime.first?.intValue)
            }
            return page.withQuota(quota)
        }
    }

    private func shown(_ page: MediaPage) {
        state = .ready(page)
        if !extrasAsked {
            extrasAsked = true
            readExtras(page)
        }
        // Live status: the card the page came from takes what the page has just read.
        if page.cardBadge != card.badge { hub.set(card.key, page.cardBadge) }
    }

    /// Once per page: the ratings change slowly, and a failure leaves the line to TMDB's score.
    private func readExtras(_ page: MediaPage) {
        let type = page.type
        let tmdbId = page.tmdbId
        for kind in [RelatedKind.recommendations, RelatedKind.similar] {
            Task {
                guard let answer = try? await gateway.call({ client in
                    kind == .recommendations
                        ? try await client.recommendations(type: type, tmdbId: tmdbId, page: 1)
                        : try await client.similar(type: type, tmdbId: tmdbId, page: 1)
                }) else { return }
                let results = answer.results.compactMap { $0 as? MediaResult }
                related[kind] = DiscoverKt.appendPage(cards: [], page: results, baseUrl: session.baseUrl, settings: page.settings, permissions: page.user.permissions)
            }
        }
        Task {
            ratings = try? await gateway.call { client in try await client.ratings(type: type, tmdbId: tmdbId) }
        }
    }
}

extension MediaPage {
    /// The page with the account's quota, read beside it (Kotlin's `copy(quota = …)`, which Swift sees with every field).
    func withQuota(_ quota: Quota?) -> MediaPage {
        copying(quota: quota)
    }

    /// A film's page with its runtime as Hither writes it ("1 hr 34 min", `Formats.runtime`) from the details' minutes:
    /// `core` writes "94 minutes" first among the attributes (Android's words), and stays as it is (the review's F29).
    func withRuntime(_ minutes: Int?) -> MediaPage {
        guard let minutes, let written = Formats.runtime(minutes: minutes), attributes.first == "\(minutes) minutes" else { return self }
        return copying(attributes: [written] + attributes.dropFirst())
    }

    /// A series' page with its Episode Runtime fact as Hither writes a runtime, from the details' minutes.
    func withEpisodeRuntime(_ minutes: Int?) -> MediaPage {
        guard let minutes, let written = Formats.runtime(minutes: minutes) else { return self }
        return copying(facts: facts.map { fact in
            fact.name == "Episode Runtime" && fact.value == "\(minutes) minutes" ? Fact(name: fact.name, value: written, kind: fact.kind) : fact
        })
    }

    private func copying(attributes: [String]? = nil, facts: [Fact]? = nil, quota: Quota?? = .none) -> MediaPage {
        doCopy(
            type: type, tmdbId: tmdbId, title: title, year: year, poster: poster, backdrop: backdrop,
            certification: certification, attributes: attributes ?? self.attributes, tagline: tagline, overview: overview,
            facts: facts ?? self.facts, crew: crew, streaming: streaming, trailer: trailer, keywords: keywords,
            voteAverage: voteAverage, voteCount: voteCount, badges: badges, buttons: buttons, plays: plays, options: options,
            cast: cast, seasons: seasons, media: media, settings: settings, user: user, quota: quota ?? self.quota,
            collection: collection
        )
    }
}
