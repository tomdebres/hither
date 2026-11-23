import Foundation
import HitherCore
import Observation

/// Where the approval queue reads from: the server as the active admin, or a test's script (Android's `ApprovalsSource`).
struct ApprovalsSource {
    /// The admin's rights, read live on every open.
    var me: () async throws -> User
    /// Everyone's requests under [filter], newest first.
    var page: (_ filter: RequestFilter, _ skip: Int32, _ take: Int32) async throws -> RequestPage
    var title: (_ type: MediaType, _ tmdbId: Int32) async throws -> RequestTitle
    /// Approve ([approve]) or decline: the request as Seerr saved it.
    var decide: (_ requestId: Int32, _ approve: Bool) async throws -> MediaRequest

    /// The server as the active admin (DECISIONS 93, 94): every call through `AdminGateway.asAdmin` and nothing else.
    @MainActor
    static func admin(_ admin: AdminGateway, baseUrl: String) -> ApprovalsSource {
        ApprovalsSource(
            me: { try await admin.asAdmin { try await $0.me() } },
            page: { filter, skip, take in
                try await admin.asAdmin { try await $0.requests(filter: filter.api, take: take, skip: skip, requestedBy: nil, sort: "added") }
            },
            title: { type, tmdbId in
                try await admin.asAdmin { client -> RequestTitle in
                    if type == .movie { return requestTitle(details: try await client.movie(tmdbId: tmdbId), baseUrl: baseUrl) }
                    return requestTitle(details: try await client.tv(tmdbId: tmdbId), baseUrl: baseUrl)
                }
            },
            decide: { id, approve in try await admin.asAdmin { try await $0.decide(requestId: id, approve: approve) } }
        )
    }
}

/// What the queue shows under its filters (Android's `ApprovalsBody`).
enum ApprovalsBody {
    case loading, failed, empty, rows
}

/// What a decision came to (Android's `Decided`): for the waiting count, and when it did not go through, the queue's
/// alert.
enum Decided: Equatable {
    case done(title: String, approved: Bool)
    /// Someone decided it first (Seerr's 409): the list is read again.
    case notPending(title: String)
    /// The account's live rights no longer allow it: Select opens the title, no longer the dialog.
    case noRight
    /// Anything else: Seerr's own words.
    case failed
}

/// Where focus goes once a decision is in, before the row changes, so nothing is removed from under it.
enum FocusAfter: Equatable {
    /// The next request still to decide: its lockup.
    case row(key: String)
    /// None left: the current filter.
    case filters
}

/// Seerr's Requests page for an approver (Android's `ApprovalsModel`, M5, DECISIONS 94): everyone's requests under one of
/// Seerr's filters, opening on Pending; Approve and Decline on each pending one, for an account whose live rights allow
/// them ([approver], read on every open). One press, one decision. When one is in, focus moves first ([focusAfter], to
/// the next request to decide or the filters), then the row takes its new status at once ([focusMoved]), which is what
/// the decision did (Tom's D4: no toast); the waiting count follows. A decision that does not go through is [problem],
/// the queue's alert. Pages of [take] as focus nears the end.
@MainActor
@Observable
final class ApprovalsModel {
    private(set) var filter: RequestFilter = .pending
    /// The requests as Seerr gave them (and, after a decision, as it leaves them).
    private(set) var requests: [MediaRequest] = []
    private(set) var rows: [RequestRow] = []
    /// The admin's rights as last read; nil until read.
    private(set) var approver: User?
    /// The rights have been asked for on this open and answered (or failed): the page's focus waits for them, since they
    /// decide whether a row's Select opens the dialog.
    private(set) var rightsRead = false
    private(set) var loading = false
    private(set) var failed = false
    /// The request a decision is out for, and which (true: approve): nothing else is taken meanwhile.
    private(set) var deciding: (id: Int32, approve: Bool)?
    /// Set when a decision is in: the page moves focus there, then calls [focusMoved].
    private(set) var focusAfter: FocusAfter?
    /// A decision that did not go through, for the queue's alert, and the request it was for.
    private(set) var problem: AlertText?
    private(set) var problemRow: String?
    let names: TitleNames

    @ObservationIgnored private let source: ApprovalsSource
    @ObservationIgnored private let onDecided: (Decided) -> Void
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var pages: Int32 = 0
    @ObservationIgnored private var totalPages: Int32 = 1
    @ObservationIgnored private var waiting: (request: MediaRequest, approve: Bool)?
    @ObservationIgnored private var started = false

    /// Seerr's own page size.
    static let take: Int32 = 10
    /// Lockups on the screen at once, and how far ahead the next page is asked for: the grid's two lines of four.
    static let fillsScreen: Int32 = Metrics.listPageAhead
    static let lookahead: Int32 = Metrics.listPageAhead

    init(source: ApprovalsSource, onDecided: @escaping (Decided) -> Void = { _ in }) {
        self.source = source
        self.onDecided = onDecided
        names = TitleNames(fetch: source.title)
    }

    var mayDecide: Bool { AdminKt.mayDecide(user: approver) }

    var body: ApprovalsBody {
        if !rows.isEmpty { return .rows }
        if loading { return .loading }
        return failed ? .failed : .empty
    }

    func decisions(_ row: RequestRow) -> Bool {
        guard let request = requests.first(where: { $0.id == row.id }) else { return false }
        return ApprovalsKt.decisionsFor(request: request, approver: approver)
    }

    func title(_ row: RequestRow) -> RequestTitle? { names.title(row.titleKey) }

    /// The page has come up: the rights read again, and the list from the top. Once per opening of the queue: coming
    /// back to it from a title opened from a row keeps the list and the row (Android's `LaunchedEffect(model)`).
    func open() {
        guard !started else { return }
        started = true
        Task {
            approver = try? await source.me()
            rightsRead = true
        }
        load()
    }

    /// A filter chosen: the list under it, from the top.
    func choose(_ next: RequestFilter) {
        if next == filter, !rows.isEmpty { return }
        filter = next
        load()
    }

    func retry() {
        if !loading { load() }
    }

    /// A row has focus: ask for more as the end comes near.
    func focused(_ index: Int) {
        if !failed, DiscoverKt.wantsNextPage(
            count: Int32(rows.count), focused: Int32(index), page: pages, totalPages: totalPages,
            busy: loading, fills: Self.fillsScreen, lookahead: Self.lookahead
        ) {
            nextPage()
        }
    }

    /// Approve or decline [requestId]: once per press, nothing while one is out.
    func decide(_ requestId: Int32, approve: Bool) {
        guard deciding == nil, focusAfter == nil, let request = requests.first(where: { $0.id == requestId }),
              ApprovalsKt.decisionsFor(request: request, approver: approver) else { return }
        deciding = (requestId, approve)
        let name = rows.first { $0.id == requestId }.flatMap { title($0)?.title } ?? ""
        Task {
            do {
                _ = try await source.decide(requestId, approve)
            } catch {
                deciding = nil
                let thrown = Kotlin.throwable(error)
                let outcome: Decided
                if thrown is SeerrException.NotPending {
                    outcome = .notPending(title: name)
                    reread()
                } else if thrown is SeerrException.Refused {
                    approver = try? await source.me()
                    outcome = .noRight
                } else {
                    outcome = .failed
                }
                problemRow = "request:\(requestId)"
                problem = Self.problem(outcome, approve: approve)
                onDecided(outcome)
                return
            }
            waiting = (request, approve)
            let next = ApprovalsKt.nextToDecide(rows: requests, decided: requestId)?.int32Value
            focusAfter = next.map { .row(key: "request:\($0)") } ?? .filters
            onDecided(.done(title: name, approved: approve))
        }
    }

    /// The queue's alert has closed.
    func problemSeen() {
        problem = nil
        problemRow = nil
    }

    /// A decision that did not go through, in Android's words under the system alert's title.
    static func problem(_ outcome: Decided, approve: Bool) -> AlertText? {
        let title = approve ? Words.notApproved : Words.notDeclined
        switch outcome {
        case .done: return nil
        case .notPending(let name): return AlertText(title: title, message: Words.decisionNotPending(name))
        case .noRight: return AlertText(title: title, message: Words.decisionNoRight)
        case .failed: return AlertText(title: title, message: Words.modifyFailed)
        }
    }

    /// Focus has moved off the decided row: it takes its new status now.
    func focusMoved() {
        guard let (request, approve) = waiting else {
            focusAfter = nil
            return
        }
        requests = requests.map { $0.id == request.id ? ApprovalsKt.afterDecision(request: $0, approved: approve, by: approver) : $0 }
        rows = RequestsKt.requestRows(requests: requests)
        waiting = nil
        deciding = nil
        focusAfter = nil
    }

    private func load() {
        generation += 1
        let generation = generation
        requests = []
        rows = []
        pages = 0
        totalPages = 1
        failed = false
        loading = true
        Task { await take(generation, page: 1) }
    }

    /// The list again as far as it was read (someone else decided a request first). It supersedes a page that may be
    /// out, so that page's answer is dropped and it no longer counts as loading (Android's Nits on #39); a filter whose
    /// first page is out is left to finish, since that read is the fresh list.
    private func reread() {
        guard pages > 0 else { return }
        generation += 1
        let generation = generation
        let upTo = pages * Self.take
        loading = true
        Task {
            let fresh = try? await source.page(filter, 0, upTo)
            guard self.generation == generation else { return }
            loading = false
            guard let fresh else { return }
            requests = fresh.results
            rows = RequestsKt.requestRows(requests: requests)
            totalPages = Self.pages(of: fresh)
            names.want(rows.map(\.wanted))
        }
    }

    private func nextPage() {
        guard !loading, pages < totalPages else { return }
        loading = true
        let generation = generation
        let page = pages + 1
        Task { await take(generation, page: page) }
    }

    private func take(_ generation: Int, page: Int32) async {
        let answer: RequestPage
        do {
            answer = try await source.page(filter, (page - 1) * Self.take, Self.take)
        } catch {
            if self.generation == generation {
                loading = false
                failed = true
            }
            return
        }
        guard self.generation == generation else { return }
        if page == 1 {
            requests = answer.results
        } else {
            var known = Set(requests.map(\.id))
            requests += answer.results.filter { known.insert($0.id).inserted }
        }
        rows = RequestsKt.requestRows(requests: requests)
        pages = page
        totalPages = Self.pages(of: answer)
        loading = false
        names.want(rows.map(\.wanted))
    }

    private static func pages(of page: RequestPage) -> Int32 {
        (page.pageInfo.results + take - 1) / take
    }
}
