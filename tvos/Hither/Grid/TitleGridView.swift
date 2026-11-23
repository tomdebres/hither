import Foundation
import HitherCore
import Observation
import SwiftUI

/// What a titles grid shows (Android's `GridBody`).
enum GridBody {
    case loading, failed, results, noResults
}

/// A grid of titles from one query, paged as search is (Android's `TitleGridModel`): a row's own titles from its See
/// More, a collection's films, a keyword's titles, and the Movies and Series pages under their sort and filters. The
/// first page is asked for on [start]; later pages as focus nears the end; a failed first page offers Try Again in its
/// place, a failed later one at the grid's end.
@MainActor
@Observable
final class TitleGridModel: TakesBadges {
    typealias Source = (SessionGateway, Int32) async throws -> Page<MediaResult>

    private(set) var cards: [TitleCard] = []
    private(set) var loading = false
    private(set) var failed = false
    /// Try Again for the first page is out: its button stays ("Trying…") and keeps focus.
    private(set) var retrying = false
    private var started = false
    private var page: Int32 = 0
    private var totalPages: Int32 = 1
    private var focusedIndex = -1
    private let source: Source
    private let gateway: SessionGateway
    private let baseUrl: String

    /// Six across, two rows on the screen: a grid's screenful, and how far ahead it asks for more (Android's).
    static let columns = 6
    static let fills: Int32 = 12
    static let lookahead: Int32 = 12

    init(gateway: SessionGateway, baseUrl: String, hub: BadgeHub, source: @escaping Source) {
        self.gateway = gateway
        self.baseUrl = baseUrl
        self.source = source
        hub.join(self)
    }

    var body: GridBody {
        if !cards.isEmpty { return .results }
        if retrying { return .failed }
        if loading || !started { return .loading }
        return failed ? .failed : .noResults
    }

    /// A later page failed: the cards stay, and Try Again ends the grid until its page comes.
    var moreFailed: Bool { !cards.isEmpty && failed }

    func start() {
        guard !started else { return }
        started = true
        loading = true
        Task { await take(1) }
    }

    /// Try Again: the first page again if that failed, else the page after the cards.
    func retry() {
        guard failed, !loading else { return }
        if cards.isEmpty {
            failed = false
            loading = true
            retrying = true
            Task { await take(1) }
        } else {
            nextPage()
        }
    }

    func focused(_ index: Int) {
        focusedIndex = index
        if !failed, DiscoverKt.wantsNextPage(count: Int32(cards.count), focused: Int32(index), page: page, totalPages: totalPages, busy: loading, fills: Self.fills, lookahead: Self.lookahead) {
            nextPage()
        }
    }

    func setBadge(_ key: String, _ badge: StatusBadge?) {
        let updated = DiscoverKt.withBadge(cards: cards, key: key, badge: badge)
        if updated != cards { cards = updated }
    }

    private func nextPage() {
        guard !loading, page < totalPages else { return }
        failed = false
        loading = true
        let next = page + 1
        Task { await take(next) }
    }

    private func take(_ wanted: Int32) async {
        do {
            let answer = try await source(gateway, wanted)
            let results = answer.results.compactMap { $0 as? MediaResult }
            cards = DiscoverKt.appendSearchPage(cards: wanted == 1 ? [] : cards, page: results, baseUrl: baseUrl)
            page = wanted
            totalPages = answer.totalPages
            loading = false
            retrying = false
            if DiscoverKt.wantsNextPage(count: Int32(cards.count), focused: Int32(focusedIndex), page: page, totalPages: totalPages, busy: false, fills: Self.fills, lookahead: Self.lookahead) {
                nextPage()
            }
        } catch {
            loading = false
            retrying = false
            failed = true
        }
    }
}

/// The grids' sources (Android's `rowTitles`, `collectionTitles`, `keywordTitles`).
enum GridSources {
    /// A Discover row's own titles, page by page, as the row reads them: its See More grid (polish 2, DECISIONS 131).
    @MainActor
    static func row(_ row: DiscoverRow, discover: DiscoverModel) -> TitleGridModel.Source {
        { _, page in try await discover.fetch(row, page: page) }
    }

    /// A collection's films in Seerr's order (by release date, the server's): one page, as Seerr's page lists them all.
    static func collection(_ id: Int32) -> TitleGridModel.Source {
        { gateway, page in
            try await gateway.call { client in
                let parts = page == 1 ? try await client.collection(collectionId: id).parts : []
                return Page(page: page, totalPages: 1, totalResults: Int32(parts.count), results: parts)
            }
        }
    }

    /// A keyword's titles of one type (`discover/movies|tv?keywords=`), as Seerr's keyword link opens them.
    static func keyword(_ id: Int32, type: MediaType) -> TitleGridModel.Source {
        { gateway, page in
            try await gateway.call { client in
                try await client.discover(type: type, query: .empty.with(keywords: "\(id)"), page: page)
            }
        }
    }
}

/// A page of titles under its title (Android's `TitleGridScreen`, in tvOS's way: DECISIONS 193): a grid of poster
/// lockups.
struct TitleGridView: View {
    @State var model: TitleGridModel
    let heading: String
    let id: String
    var onTitle: (TitleCard) -> Void

    var body: some View {
        // The grid runs to the screen's sides (its lockups lift past the margins) under the system's title.
        PageScaffold(.pushed(heading), bleed: .horizontal, id: "page grid \(id)") {
            CardGrid(model: model, onTitle: onTitle)
        }
        .task { model.start() }
    }
}

/// The titles six across as poster lockups (Android's `CardGrid`, 1.5 and 2.5; tvOS's six-column grid): while the first
/// page loads, placeholders stand in; a failure offers Try Again; none is "No results.". The grid scrolls under the
/// page's title and is cut at its foot (its top padding is the lifted lockups' room).
struct CardGrid: View {
    let model: TitleGridModel
    var onTitle: (TitleCard) -> Void
    @FocusState private var focus: String?

    static let retryKey = "retry"
    /// Between the six columns: what the width leaves between the margins.
    static let gap = (1920 - 2 * Metrics.sideMargin - CGFloat(TitleGridModel.columns) * Metrics.posterWidth) / CGFloat(TitleGridModel.columns - 1)

    var body: some View {
        let gap = Self.gap
        let columns = Array(repeating: GridItem(.fixed(Metrics.posterWidth), spacing: gap, alignment: .top), count: TitleGridModel.columns)
        Group {
            switch model.body {
            case .loading:
                HStack(spacing: gap) {
                    ForEach(0..<TitleGridModel.columns, id: \.self) { _ in PlaceholderCard() }
                }
                .padding(.horizontal, Metrics.sideMargin)
                .padding(.top, 48)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("grid loading")
            case .noResults:
                StateView(.empty, Words.noResults, symbol: "magnifyingglass", id: "no results")
                    .padding(.top, 120)
            case .failed:
                StateView(.failed, Words.gridFailed, id: "grid failed") {
                    Button(model.retrying ? Words.trying : Words.tryAgain) { model.retry() }
                        .accessibilityIdentifier("try again")
                }
                .padding(.top, 120)
            case .results:
                let mixed = mixesKinds(model.cards)
                ScrollView(.vertical) {
                    LazyVGrid(columns: columns, alignment: .leading, spacing: Metrics.gridRowGap) {
                        ForEach(Array(model.cards.enumerated()), id: \.element.key) { index, card in
                            TitleCardView(card: card, mixed: mixed) { onTitle(card) }
                                .focused($focus, equals: card.key)
                        }
                        if model.moreFailed {
                            Button(Words.tryAgain) { model.retry() }
                                .frame(width: Metrics.posterWidth, height: Metrics.posterHeight)
                                .focused($focus, equals: Self.retryKey)
                                .accessibilityIdentifier("try again")
                        }
                    }
                    .padding(.horizontal, Metrics.sideMargin)
                    .padding(.top, 48)
                    .padding(.bottom, 1080 - 48 - Metrics.posterHeight - Metrics.captionHeight)
                }
                .scrollIndicators(.hidden)
                .onChange(of: focus) { _, key in
                    if let key, let index = model.cards.firstIndex(where: { $0.key == key }) { model.focused(index) }
                }
                .focusSection()
            }
        }
    }
}

extension DiscoverQuery {
    /// No query: Kotlin's `DiscoverQuery()`, which Swift sees with every field.
    static var empty: DiscoverQuery {
        DiscoverQuery(sortBy: nil, genre: nil, keywords: nil, language: nil, releasedFrom: nil, releasedTo: nil, watchProviders: nil, watchRegion: nil)
    }

    func with(keywords: String) -> DiscoverQuery {
        DiscoverQuery(sortBy: sortBy, genre: genre, keywords: keywords, language: language, releasedFrom: releasedFrom, releasedTo: releasedTo, watchProviders: watchProviders, watchRegion: watchRegion)
    }
}
