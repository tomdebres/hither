import Foundation
import HitherCore
import Observation

/// What My Requests shows under its heading (Android's `RequestsBody`).
enum RequestsBody {
    /// The first page is on its way: placeholders.
    case loading
    /// It did not come: the reason and Try Again.
    case failed
    /// The user has asked for nothing yet.
    case empty
    case rows
}

/// What cancelling a request came to (Android's `CancelOutcome`, DECISIONS 101).
enum CancelOutcome {
    /// Gone from the server; the row goes once focus has moved off it.
    case cancelled(id: Int32, title: String?)
    /// Seerr refused: it is no longer pending (approved or declined meanwhile). The list is read again.
    case notPending
    case failed
}

/// Where My Requests reads from: the signed-in server, or a test's script.
struct RequestsSource {
    /// The user's own requests, newest first, every status.
    var page: (_ skip: Int32, _ take: Int32) async throws -> RequestPage
    /// Cancels (removes) one of the user's own pending requests.
    var cancel: (_ requestId: Int32) async throws -> Void

    /// The signed-in user's own requests: `requestedBy` is always this user, so the page is the account's own whatever
    /// it may see, as Seerr's profile page asks for it (Android's `seerrRequestsSource`). A cancel refused before the
    /// route ran (an ended session) is renewed and sent once more, as a request is.
    static func server(_ gateway: SessionGateway, userId: Int32) -> RequestsSource {
        RequestsSource(
            page: { skip, take in
                try await gateway.call { client in
                    try await client.requests(filter: "all", take: take, skip: skip, requestedBy: KotlinInt(value: userId), sort: "added")
                }
            },
            cancel: { id in
                try await gateway.call { client in try await client.cancelRequest(requestId: id) }
            }
        )
    }
}

/// My Requests (Android's `RequestsModel`, DECISIONS 66, 101): the signed-in user's own requests, newest first, every
/// status, no filters. Pages of [take] are asked for as focus nears the end. The list does not carry the titles' names
/// and pictures, so each title's details are asked for once ([names]).
///
/// Every arrival on the page after the first reads the list again ([open]), and so does Hither coming back to the front
/// ([refresh]): statuses change in place, a request made meanwhile appears at the top, and focus stays on its row (on
/// the top row, the new top takes it: [newTop]). A failed refresh keeps what is shown. Kept by the home, so a title's
/// page and Menu find the row.
@MainActor
@Observable
final class RequestsModel {
    private(set) var rows: [RequestRow] = []
    let names: TitleNames
    private(set) var loading = false
    private(set) var failed = false
    /// Try Again on the first page is out: it stays ("Trying…") until the answer.
    private(set) var retrying = false
    /// Try Again at the list's end is out.
    private(set) var retryingMore = false
    /// The request whose cancelling is out: its button reads "Cancelling…" and nothing else is taken.
    private(set) var cancelling: Int32?
    /// Bumped when a refresh has put newer requests above the top row while focus was on it.
    private(set) var newTop = 0

    /// The row focus is on (or was last on), so a refresh keeps it.
    @ObservationIgnored private(set) var focusedIndex = 0
    @ObservationIgnored private var listHasFocus = false
    @ObservationIgnored private let source: RequestsSource
    @ObservationIgnored private var started = false
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var pages: Int32 = 0
    @ObservationIgnored private var totalPages: Int32 = 1
    @ObservationIgnored private var rereading = false

    /// Seerr's own page size for the requests list.
    static let take: Int32 = 10
    /// Lockups on the screen at once, and how far ahead the next page is asked for: the grid's two lines of four.
    static let fillsScreen: Int32 = Metrics.listPageAhead
    static let lookahead: Int32 = Metrics.listPageAhead

    init(source: RequestsSource, names: TitleNames) {
        self.source = source
        self.names = names
    }

    var body: RequestsBody {
        if !rows.isEmpty { return .rows }
        if retrying { return .failed }
        if loading { return .loading }
        return failed ? .failed : .empty
    }

    /// More rows are on their way after the ones shown.
    var loadingMore: Bool { !rows.isEmpty && loading && !retryingMore }

    /// A later page failed: the rows stay and a Try Again row ends the list until its page comes.
    var moreFailed: Bool { !rows.isEmpty && (failed || retryingMore) }

    /// Whether any row offers Cancel Request: every row then keeps its room, so the fields stay in one column.
    var anyCancellable: Bool { rows.contains { RequestExtrasKt.canCancel(row: $0) } }

    func title(_ row: RequestRow) -> RequestTitle? { names.title(row.titleKey) }

    /// The page has come up: the first time, the first page; after that, the list read again.
    func open() {
        if !started {
            started = true
            load()
        } else {
            refresh()
        }
    }

    /// The list again, as far as it has been read: each row takes its fresh status and requests made since appear in
    /// their place. Nothing happens while a page is out; a failure keeps the rows there were; a list with no rows is
    /// simply asked for again.
    func refresh() {
        guard started, !loading, !rereading else { return }
        if rows.isEmpty {
            load()
            return
        }
        let generation = generation
        let take = pages * Self.take
        rereading = true
        Task {
            defer { rereading = false }
            guard let fresh = try? await source.page(0, take) else { return }
            guard self.generation == generation else { return }
            let updated = RequestsKt.requestRows(requests: fresh.results)
            // Where focus is now, not when the list was asked for: the user may have moved.
            let focusedKey = rows.indices.contains(focusedIndex) ? rows[focusedIndex].key : nil
            let onTop = focusedIndex == 0
            rows = updated
            totalPages = Self.pages(of: fresh)
            if onTop {
                if updated.first?.key != focusedKey { newTop += 1 }
                focusedIndex = 0
            } else {
                focusedIndex = updated.firstIndex { $0.key == focusedKey } ?? min(focusedIndex, max(0, updated.count - 1))
            }
            fetchTitles()
        }
    }

    /// Try Again: the first page again if that failed, else the page after the rows. Not while a refresh is out.
    func retry() {
        guard failed, !loading, !rereading else { return }
        if rows.isEmpty {
            retrying = true
            load()
        } else {
            retryingMore = true
            nextPage()
        }
    }

    /// A row has focus: remember it, and ask for more as the end comes near.
    func focused(_ index: Int) {
        focusedIndex = index
        listHasFocus = true
        if !failed, DiscoverKt.wantsNextPage(
            count: Int32(rows.count), focused: Int32(index), page: pages, totalPages: totalPages,
            busy: loading || rereading, fills: Self.fillsScreen, lookahead: Self.lookahead
        ) {
            nextPage()
        }
    }

    /// Focus has left the list.
    func left() {
        listHasFocus = false
    }

    /// Cancels [row] once (asking first is the page's): one press, one DELETE, nothing while one is out. The row stays
    /// until [drop], which the page calls on `.cancelled`.
    func cancel(_ row: RequestRow, done: @escaping (CancelOutcome) -> Void) {
        guard cancelling == nil, RequestExtrasKt.canCancel(row: row) else { return }
        cancelling = row.id
        let name = title(row)?.title
        Task {
            let outcome: CancelOutcome
            do {
                try await source.cancel(row.id)
                outcome = .cancelled(id: row.id, title: name)
            } catch where Kotlin.isRefused(error) {
                outcome = .notPending
            } catch {
                outcome = .failed
            }
            cancelling = nil
            if case .notPending = outcome { refresh() }
            done(outcome)
        }
    }

    /// The cancelled request's row goes; the row that came up under focus's place (the one above, for the last) is the
    /// list's entry.
    func drop(_ id: Int32) {
        guard let index = rows.firstIndex(where: { $0.id == id }) else { return }
        rows = RequestExtrasKt.withoutRequest(rows: rows, id: id)
        if focusedIndex > index { focusedIndex -= 1 }
        focusedIndex = min(max(0, focusedIndex), max(0, rows.count - 1))
    }

    private func load() {
        generation += 1
        let generation = generation
        rows = []
        pages = 0
        totalPages = 1
        focusedIndex = 0
        failed = false
        loading = true
        Task { await take(generation, page: 1) }
    }

    private func nextPage() {
        guard !loading, !rereading, pages < totalPages else { return }
        failed = false
        loading = true
        let generation = generation
        let page = pages + 1
        Task { await take(generation, page: page) }
    }

    private func take(_ generation: Int, page: Int32) async {
        let answer: RequestPage
        do {
            answer = try await source.page((page - 1) * Self.take, Self.take)
        } catch {
            if self.generation == generation {
                loading = false
                retrying = false
                retryingMore = false
                failed = true
            }
            return
        }
        guard self.generation == generation else { return }
        rows = page == 1 ? RequestsKt.requestRows(requests: answer.results) : RequestsKt.appendRequests(rows: rows, page: answer.results)
        pages = page
        totalPages = Self.pages(of: answer)
        loading = false
        retrying = false
        retryingMore = false
        fetchTitles()
        let focus = listHasFocus ? Int32(focusedIndex) : -1
        if DiscoverKt.wantsNextPage(count: Int32(rows.count), focused: focus, page: pages, totalPages: totalPages, busy: false, fills: Self.fillsScreen, lookahead: Self.lookahead) {
            nextPage()
        }
    }

    private func fetchTitles() {
        names.want(rows.map(\.wanted))
    }

    /// How many pages of [take] the server's count makes; a refresh reads them all at once.
    private static func pages(of page: RequestPage) -> Int32 {
        (page.pageInfo.results + take - 1) / take
    }
}
