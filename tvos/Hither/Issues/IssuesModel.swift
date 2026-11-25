import Foundation
import HitherCore
import Observation

/// What the Issues page shows under its filters (Android's `IssuesBody`).
enum IssuesBody {
    case loading, failed, noRight, empty, rows
}

/// Where the Issues page reads from: the signed-in server, or a test's script.
struct IssuesSource {
    /// Issues under [filter], newest first. Seerr's 403 without a right is `SeerrException.Refused` with `lacksRight`.
    var page: (_ filter: IssueFilter, _ skip: Int32, _ take: Int32) async throws -> IssuePage

    static func server(_ gateway: SessionGateway) -> IssuesSource {
        IssuesSource { filter, skip, take in
            try await gateway.call { client in try await client.issues(filter: filter.api, take: take, skip: skip) }
        }
    }
}

/// Seerr's Issues page (Android's `IssuesModel`, DECISIONS 106): issues under one of Seerr's filters, opening on Open,
/// newest first; each title's name read once ([names]); pages of [take] as focus nears the end. Seerr's 403 (the right
/// gone since Discover read it) is said, not retried; any other failure offers Try Again: the list's, or after a later
/// page failed, the one that ends the rows ([moreFailed], DECISIONS 65's rule; Android's since A9, 365). The list is
/// read again each time the page opens, except coming back from a title to its row.
@MainActor
@Observable
final class IssuesModel {
    private(set) var filter: IssueFilter = .open
    private(set) var rows: [IssueRow] = []
    let names: TitleNames
    private(set) var loading = false
    private(set) var failed = false
    private(set) var refused = false
    /// Try Again at the list's end is out: it stays ("Trying…") and keeps focus until the answer.
    private(set) var retryingMore = false

    /// Coming back from a title's page: the rows stay as they were.
    @ObservationIgnored var returnToRow = false
    @ObservationIgnored private let source: IssuesSource
    @ObservationIgnored private var issues: [Issue] = []
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var pages: Int32 = 0
    @ObservationIgnored private var totalPages: Int32 = 1

    /// Seerr's own page size.
    static let take: Int32 = 10

    init(source: IssuesSource, names: TitleNames) {
        self.source = source
        self.names = names
    }

    var body: IssuesBody {
        if !rows.isEmpty { return .rows }
        if refused { return .noRight }
        if loading { return .loading }
        return failed ? .failed : .empty
    }

    /// More issues are on their way after the ones shown.
    var loadingMore: Bool { !rows.isEmpty && loading && !failed && !retryingMore }

    /// A later page failed: the rows stay and a Try Again ends the list until its page comes. Before, nothing did, and
    /// moving along never asked again: the list stayed short until the filter changed (Android's fault before A9).
    var moreFailed: Bool { !rows.isEmpty && (failed || retryingMore) }

    /// The page has come up: from the top, under the filter it had, unless coming back to a row.
    func open() {
        if returnToRow, !rows.isEmpty {
            returnToRow = false
            return
        }
        returnToRow = false
        load()
    }

    func choose(_ next: IssueFilter) {
        if next == filter, !rows.isEmpty { return }
        filter = next
        load()
    }

    /// Try Again: the list from its top if its first page failed, the page after the rows if a later one did, once
    /// however often it is pressed.
    func retry() {
        guard !loading else { return }
        if !rows.isEmpty, failed {
            retryingMore = true
            nextPage()
        } else {
            load()
        }
    }

    /// A row has focus: ask for more as the end comes near; not after a failure (Try Again asks).
    func focused(_ index: Int) {
        if !failed, DiscoverKt.wantsNextPage(count: Int32(rows.count), focused: Int32(index), page: pages, totalPages: totalPages, busy: loading, fills: Metrics.listPageAhead, lookahead: Metrics.listPageAhead) {
            nextPage()
        }
    }

    private func load() {
        generation += 1
        let generation = generation
        issues = []
        rows = []
        pages = 0
        totalPages = 1
        failed = false
        refused = false
        retryingMore = false
        loading = true
        Task { await take(generation, page: 1) }
    }

    private func nextPage() {
        guard !loading, pages < totalPages else { return }
        failed = false
        loading = true
        let generation = generation
        let page = pages + 1
        Task { await take(generation, page: page) }
    }

    private func take(_ generation: Int, page: Int32) async {
        let answer: IssuePage
        do {
            answer = try await source.page(filter, (page - 1) * Self.take, Self.take)
        } catch {
            guard self.generation == generation else { return }
            loading = false
            retryingMore = false
            // Only Seerr's own refusal of the right means "no right"; a proxy's or any other 403 is a failure to try
            // again (the polish 2 audit's item 17).
            if let refusal = Kotlin.throwable(error) as? SeerrException.Refused, refusal.lacksRight {
                refused = true
            } else {
                failed = true
            }
            return
        }
        guard self.generation == generation else { return }
        retryingMore = false
        let known = Set(issues.map(\.id))
        issues = page == 1 ? answer.results : issues + answer.results.filter { !known.contains($0.id) }
        rows = IssuesKt.issueRows(issues: issues)
        pages = page
        totalPages = (answer.pageInfo.results + Self.take - 1) / Self.take
        loading = false
        names.want(rows.compactMap { row in
            guard let key = row.titleKey, let type = row.type else { return nil }
            return TitleNames.Wanted(key: key, type: type, tmdbId: row.tmdbId)
        })
    }
}
