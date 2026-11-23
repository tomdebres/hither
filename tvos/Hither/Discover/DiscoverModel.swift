import Foundation
import HitherCore
import Observation

/// What a row shows where its cards would be.
enum RowBody {
    case cards, loading, failed, empty
}

/// One row of Discover: its cards, how far it has paged, and the card it was left on (Android's `RowState`).
@MainActor
@Observable
final class RowModel: Identifiable {
    let row: DiscoverRow
    fileprivate(set) var cards: [TitleCard] = []
    fileprivate(set) var loading = true
    fileprivate(set) var failed = false
    /// The card focus was last on in this row (its key, or `seeMoreKey`), so focus comes back to it.
    var lastFocused: String?
    fileprivate var page: Int32 = 0
    fileprivate var totalPages: Int32 = 1
    /// Bumped on every reload, so an answer to an older load is dropped.
    fileprivate var generation = 0

    /// The row's name: a built-in's own, `CUSTOM_<slider id>` for a custom one.
    nonisolated let id: String

    init(_ row: DiscoverRow) {
        self.row = row
        id = row.name
    }

    var body: RowBody {
        if !cards.isEmpty { return .cards }
        if loading { return .loading }
        return failed ? .failed : .empty
    }

    /// The cards the row shows: all, or for a row with See More its first 20 (core's `shownCards`).
    var shown: ArraySlice<TitleCard> { cards.prefix(Int(DiscoverKt.shownCards(row: row, count: Int32(cards.count)))) }

    /// Whether the row ends with See More: a linked row once it has its 20 (core's `showsSeeMore`).
    var showsSeeMore: Bool { DiscoverKt.showsSeeMore(row: row, count: Int32(cards.count)) }

    /// The four titles after those shown, whose posters the See More card carries faint.
    var seeMorePosters: [String?] { Array(cards.dropFirst(shown.count).prefix(Int(DiscoverKt.SEE_MORE_POSTERS)).map(\.poster)) }

    static let seeMoreKey = "see more"
}

/// Recent Requests: the ten newest requests as Seerr's slider asks for them, everyone's where the account may see them,
/// with each title's name and pictures read once (Android's `RequestsModel` behind `RecentRow`).
@MainActor
@Observable
final class RecentModel {
    fileprivate(set) var rows: [RequestRow] = []
    fileprivate(set) var titles: [String: RequestTitle] = [:]
    fileprivate(set) var failedTitles: Set<String> = []
    fileprivate(set) var loading = true
    var lastFocused: String?

    /// How many requests the row shows: Seerr's `take=10`.
    static let take: Int32 = 10
}

/// Discover's rows, each loading on its own, so one failing or slow row never holds up the others (Android's
/// `DiscoverModel`). Which rows, in what order, is the server's Discover settings and the account's live rights
/// (core's `discoverPlan`, DECISIONS 99, 119): Hither's own rows stand in while that is read, kept by name.
@MainActor
@Observable
final class DiscoverModel: TakesBadges {
    private let session: Session
    private let gateway: SessionGateway

    private(set) var rows: [RowModel] = DiscoverRow.companion.entries.map(RowModel.init)
    /// Whether Recent Requests is on the page, and how many rows come before it (the server's order).
    private(set) var recentShown = true
    private(set) var recentAt = 0
    /// The bar's Issues, and whether Recent Requests' cards name who asked: as signed in, then as the plan reads them.
    private(set) var seesIssues: Bool
    private(set) var seesRequesters: Bool
    let recent = RecentModel()
    /// The card focus was last on, on any row: where arriving at Discover again lands.
    var lastFocus: CardFocus?
    /// Whether the plan has been read (or given up on): the rows are the server's from then on.
    private(set) var planned = false

    private var started = false
    private var recentReading = false
    /// Recently Added's titles' details, read once a session (the records are asked for again, not the details).
    private var details: [String: (MovieDetails?, TvDetails?)] = [:]

    init(session: Session, gateway: SessionGateway) {
        self.session = session
        self.gateway = gateway
        seesIssues = IssuesKt.seesIssues(permissions: session.user.permissions)
        seesRequesters = DiscoverPlanKt.seesRequesters(permissions: session.user.permissions)
    }

    /// Reads the plan, then asks for every row's first page, once.
    func start() {
        guard !started else { return }
        started = true
        Task {
            let plan: DiscoverPlan
            do {
                plan = try await gateway.call { client in try await Self.plan(client) }
            } catch {
                // Neither the settings nor the rights could be read: Hither's own rows, the rights as signed in.
                plan = DiscoverPlanKt.discoverPlan(sliders: nil, permissions: session.user.permissions)
            }
            follow(plan)
            for row in rows where row.generation == 0 { load(row) }
            if recentShown { loadRecent() }
        }
    }

    /// `settings/discover` and `auth/me` together: the rights read live (Tom's pick, 96). Settings the server will not
    /// give leave Hither's own rows; an ended session is renewed as for any call.
    private static func plan(_ client: SeerrClient) async throws -> DiscoverPlan {
        let user = try await client.me()
        var sliders: [DiscoverSlider]?
        do {
            sliders = try await client.discoverSliders()
        } catch where Kotlin.isSignedOut(error) {
            throw error
        } catch {
            sliders = nil
        }
        return DiscoverPlanKt.discoverPlan(sliders: sliders, permissions: user.permissions)
    }

    private func follow(_ plan: DiscoverPlan) {
        let kept = Dictionary(rows.map { ($0.row.name, $0) }, uniquingKeysWith: { first, _ in first })
        recentShown = plan.recentRequests
        rows = plan.rows.map { kept[$0.name] ?? RowModel($0) }
        recentAt = min(max(0, Int(plan.recentAt)), rows.count)
        seesIssues = plan.issues
        seesRequesters = plan.requesters
        planned = true
    }

    /// Try Again on a row: the first page again if that failed, the next if a later one did.
    func retry(_ row: RowModel) {
        guard row.failed, !row.loading else { return }
        if row.cards.isEmpty { load(row) } else { nextPage(row) }
    }

    /// A card in [row] has focus (or its See More card, at the index after the cards it shows): remember it, and ask
    /// for more if the end is near.
    func focused(_ row: RowModel, key: String, index: Int) {
        row.lastFocused = key
        if !row.failed,
           DiscoverKt.rowWantsNextPage(row: row.row, count: Int32(row.cards.count), focused: Int32(index), page: row.page, totalPages: row.totalPages, busy: row.loading) {
            nextPage(row)
        }
    }

    private func load(_ row: RowModel) {
        row.generation += 1
        row.cards = []
        row.page = 0
        row.totalPages = 1
        row.failed = false
        row.loading = true
        let generation = row.generation
        Task { await take(row, generation: generation, page: 1) }
    }

    private func nextPage(_ row: RowModel) {
        guard !row.loading, row.page < row.totalPages else { return }
        row.failed = false
        row.loading = true
        let generation = row.generation
        let page = row.page + 1
        Task { await take(row, generation: generation, page: page) }
    }

    private func take(_ row: RowModel, generation: Int, page: Int32) async {
        let answer: Page<MediaResult>
        do {
            answer = try await gateway.call { client in try await self.page(client, row.row, page) }
        } catch {
            if row.generation == generation {
                row.loading = false
                row.failed = true
            }
            return
        }
        guard row.generation == generation else { return }
        let results = answer.results.compactMap { $0 as? MediaResult }
        // Seerr's web leaves out an empty Recently Added (RecentlyAddedSlider).
        if row.row.source is RowSourceRecentlyAdded, page == 1, results.isEmpty {
            rows.removeAll { $0 === row }
            return
        }
        row.cards = DiscoverKt.appendPage(cards: row.cards, page: results, baseUrl: session.baseUrl, settings: session.settings, permissions: session.user.permissions)
        row.page = page
        row.totalPages = answer.totalPages
        row.loading = false
        // A row that the filters left short fills itself.
        if DiscoverKt.rowWantsNextPage(row: row.row, count: Int32(row.cards.count), focused: -1, page: row.page, totalPages: row.totalPages, busy: false) {
            nextPage(row)
        }
    }

    /// One page of [row], through the call that renews an ended session: for See More's list of a row's own titles.
    func fetch(_ row: DiscoverRow, page: Int32) async throws -> Page<MediaResult> {
        try await gateway.call { client in try await self.page(client, row, page) }
    }

    /// One page of [row] from the server: the route each of Seerr's sliders reads.
    private func page(_ client: SeerrClient, _ row: DiscoverRow, _ page: Int32) async throws -> Page<MediaResult> {
        switch onEnum(of: row.source) {
        case .trending: return try await client.trending(page: page)
        case .popularMovies: return try await client.popularMovies(page: page)
        case .popularSeries: return try await client.popularSeries(page: page)
        case .upcomingMovies: return try await client.upcomingMovies(page: page)
        case .upcomingSeries: return try await client.upcomingSeries(page: page)
        case .recentlyAdded: return try await recentlyAdded(client)
        case .query(let source): return try await client.discover(type: source.type, query: source.query, page: page)
        case .search(let source): return try await client.search(query: source.query, page: page)
        case .studio(let source): return try await client.studioMovies(studioId: source.id, page: page)
        case .network(let source): return try await client.networkSeries(networkId: source.id, page: page)
        }
    }

    /// Seerr's Recently Added: the library's 20 newest, each record's title read from its details (one page). A title
    /// whose details will not come is left out, as Seerr's card shows nothing for it.
    private func recentlyAdded(_ client: SeerrClient) async throws -> Page<MediaResult> {
        let records = try await client.library(filter: "allavailable", sort: "mediaAdded", take: 20, skip: 0).results
            .filter { $0.tmdbId > 0 && ($0.mediaType == "movie" || $0.mediaType == "tv") }
        var results: [MediaResult] = []
        for record in records {
            let key = "\(record.mediaType ?? ""):\(record.tmdbId)"
            if details[key] == nil {
                do {
                    let movie = record.mediaType == "movie" ? try await client.movie(tmdbId: record.tmdbId) : nil
                    let tv = record.mediaType == "tv" ? try await client.tv(tmdbId: record.tmdbId) : nil
                    details[key] = (movie, tv)
                } catch where Kotlin.isSignedOut(error) {
                    throw error
                } catch {
                    continue
                }
            }
            if let (movie, tv) = details[key], let result = DiscoverPlanKt.recentlyAddedResult(record: record, movie: movie, tv: tv) {
                results.append(result)
            }
        }
        return Page(page: 1, totalPages: 1, totalResults: Int32(results.count), results: results)
    }

    /// Recent Requests' page and its titles. With none (or a failed first read) the row goes, as Seerr's slider returns
    /// nothing for an empty list; a failed read again keeps the cards there were.
    private func loadRecent() {
        Task { await readRecent(again: false) }
    }

    /// Recent Requests again (Discover coming up, Hither coming back to the front: Seerr's slider reads it whenever
    /// Discover mounts, DECISIONS 118): the newest requests and their statuses, in place, focus kept by key.
    func refreshRecent() {
        guard planned, recentShown, !recent.loading, !recentReading else { return }
        Task { await readRecent(again: true) }
    }

    private func readRecent(again: Bool) async {
        recentReading = true
        defer { recentReading = false }
        let baseUrl = session.baseUrl
        do {
            let page = try await gateway.call { client in
                try await client.requests(filter: "all", take: RecentModel.take, skip: 0, requestedBy: nil, sort: "added")
            }
            let rows = RequestsKt.requestRows(requests: page.results)
            if rows != recent.rows { recent.rows = rows }
        } catch {
            if !again { recent.rows = [] }
        }
        recent.loading = false
        for row in recent.rows where recent.titles[row.titleKey] == nil && !recent.failedTitles.contains(row.titleKey) {
            do {
                let title = try await gateway.call { client -> RequestTitle in
                    if row.type == .movie {
                        return requestTitle(details: try await client.movie(tmdbId: row.tmdbId), baseUrl: baseUrl)
                    }
                    return requestTitle(details: try await client.tv(tmdbId: row.tmdbId), baseUrl: baseUrl)
                }
                recent.titles[row.titleKey] = title
            } catch {
                recent.failedTitles.insert(row.titleKey)
            }
        }
    }

    /// The statuses again, for the cards already shown, in place (Android's `refreshBadges`): the pages each row has
    /// loaded are asked for again and each card takes its fresh badge. Nothing moves and nothing is added, so the
    /// user's place is kept; a failure keeps the badges there were.
    func refreshBadges() {
        for row in rows where !row.cards.isEmpty && !row.loading {
            let generation = row.generation
            let pages = row.page
            Task {
                var fresh: [TitleCard] = []
                do {
                    for page in 1...max(1, pages) {
                        let answer = try await gateway.call { client in try await self.page(client, row.row, page) }
                        let results = answer.results.compactMap { $0 as? MediaResult }
                        fresh = DiscoverKt.appendPage(cards: fresh, page: results, baseUrl: session.baseUrl, settings: session.settings, permissions: session.user.permissions)
                    }
                } catch {
                    return
                }
                guard row.generation == generation else { return }
                let updated = DiscoverKt.withFreshBadges(cards: row.cards, fresh: fresh)
                if updated != row.cards { row.cards = updated }
            }
        }
    }

    func recentFocused(_ key: String) {
        recent.lastFocused = key
    }

    func row(named name: String) -> RowModel? { rows.first { $0.row.name == name } }

    /// A title's new status (its page read it, or a request made it): every card of the title on the rows shows it,
    /// in place, so Menu from the page finds the card it left with its new badge (Android's `DiscoverModel.setBadge`).
    func setBadge(_ key: String, _ badge: StatusBadge?) {
        for row in rows {
            let updated = DiscoverKt.withBadge(cards: row.cards, key: key, badge: badge)
            if updated != row.cards { row.cards = updated }
        }
    }
}
