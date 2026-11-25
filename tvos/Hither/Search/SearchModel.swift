import Foundation
import HitherCore
import Observation

/// What the search page shows under the field (Android's `SearchBody`).
enum SearchBody: Equatable {
    /// Nothing typed yet.
    case empty
    /// The first answer for these words is on its way, and there is nothing older to show.
    case loading
    /// It did not come: the reason and Try Again.
    case failed
    case results
    /// The server had nothing for these words: "No results."
    case noResults
}

/// Search (Android's `SearchModel`, M6, DECISIONS 100): typing asks the server once the words have rested for 300 ms
/// (Seerr's own search waits the same), and the keyboard's Search asks at once; the results of the words before stay
/// until the new ones come, so the grid does not blink at each letter; answers to older words are dropped. Films, series
/// and people, in the server's order, each once (core's `appendResults`); pages are asked for as focus nears the end.
/// Kept above the tab's pages, so the words, the results and the card left are there after a title's page.
@MainActor
@Observable
final class SearchModel: TakesBadges {
    /// The field's text, as typed.
    var typed = "" {
        didSet { if typed != oldValue { type(typed) } }
    }

    /// The words the results are for (trimmed); nil while nothing has been asked.
    private(set) var query: String?
    private(set) var cards: [any GridCard] = []
    private(set) var loading = false
    private(set) var failed = false
    /// Try Again is out: its button stays ("Trying…") and keeps focus until the answer.
    private(set) var retrying = false
    /// Try Again at the results' end is out: it stays ("Trying…") and keeps focus until the answer (DECISIONS 65's rule;
    /// before, it went at the press and focus with it).
    private(set) var retryingMore = false

    /// Where a page of results comes from: the signed-in server, or a test's script.
    typealias Source = @MainActor (_ words: String, _ page: Int32) async throws -> Page<MediaResult>

    @ObservationIgnored private let source: Source
    /// Where the server's pictures come from (DECISIONS 382).
    @ObservationIgnored private let images: ImageSource
    @ObservationIgnored private let debounce: Duration
    @ObservationIgnored private var job: Task<Void, Never>?
    /// The words [job] is waiting for or asking.
    @ObservationIgnored private var asking: String?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var page: Int32 = 0
    @ObservationIgnored private var totalPages: Int32 = 1
    @ObservationIgnored private var focusedIndex = -1

    init(source: @escaping Source, images: ImageSource, debounce: Duration = .milliseconds(300)) {
        self.source = source
        self.images = images
        self.debounce = debounce
    }

    /// Search on the signed-in server (`GET /search`, the words percent-encoded by core's client).
    convenience init(gateway: SessionGateway, images: ImageSource) {
        self.init(source: { words, page in
            try await gateway.call { client in try await client.search(query: words, page: page) }
        }, images: images)
    }

    var body: SearchBody {
        if query == nil && !loading { return .empty }
        if !cards.isEmpty { return .results }
        if retrying { return .failed }
        if loading { return .loading }
        return failed ? .failed : .noResults
    }

    /// A later page failed: the results stay, and Try Again ends the grid until its page comes.
    var moreFailed: Bool { !cards.isEmpty && (failed || retryingMore) }

    /// What tvOS offers beside its keyboard: the names among the results that are not the words typed, in the server's
    /// order, at most six (Seerr has no suggestions of its own; its results are what the words find).
    var suggestions: [String] {
        guard let query, !cards.isEmpty else { return [] }
        var seen = Set<String>()
        var names: [String] = []
        for card in cards {
            let name = (card as? TitleCard)?.title ?? (card as? PersonCard)?.name ?? ""
            guard !name.isEmpty, name.caseInsensitiveCompare(query) != .orderedSame, seen.insert(name.lowercased()).inserted else { continue }
            names.append(name)
            if names.count == 6 { break }
        }
        return names
    }

    /// The field changed: ask once the words have rested; nothing typed clears the page.
    private func type(_ text: String) {
        let words = DiscoverKt.searchQuery(typed: text)
        // Already waiting for or asking these words (the field reports more than text changes).
        if let words, words == asking, job != nil { return }
        job?.cancel()
        job = nil
        asking = nil
        guard let words else {
            generation += 1
            query = nil
            cards = []
            loading = false
            failed = false
            retrying = false
            retryingMore = false
            return
        }
        if words == query && !failed && !loading { return }
        asking = words
        let debounce = debounce
        job = Task { [weak self] in
            try? await Task.sleep(for: debounce)
            guard !Task.isCancelled else { return }
            await self?.search(words)
        }
    }

    /// The keyboard's Search: ask now, unless these words are already shown or being asked.
    func submit() {
        guard let words = DiscoverKt.searchQuery(typed: typed) else { return }
        if words == query && (loading || !failed) { return }
        job?.cancel()
        asking = words
        job = Task { [weak self] in await self?.search(words) }
    }

    /// Try Again: the first page again if that failed, else the page after the cards.
    func retry() {
        guard let words = query, failed, !loading else { return }
        if cards.isEmpty {
            job?.cancel()
            asking = words
            failed = false
            loading = true
            retrying = true
            job = Task { [weak self] in await self?.search(words, retrying: true) }
        } else {
            retryingMore = true
            nextPage()
        }
    }

    /// A card has focus: remember it, and ask for more two rows before the end.
    func focused(_ index: Int) {
        focusedIndex = index
        if !failed, DiscoverKt.wantsNextPage(count: Int32(cards.count), focused: Int32(index), page: page, totalPages: totalPages, busy: loading, fills: TitleGridModel.fills, lookahead: TitleGridModel.lookahead) {
            nextPage()
        }
    }

    /// A title's page has a new status for it: its card shows it, in place.
    func setBadge(_ key: String, _ badge: StatusBadge?) {
        let updated = DiscoverKt.withGridBadge(cards: cards, key: key, badge: badge)
        if !updated.elementsEqual(cards, by: { $0 === $1 }) { cards = updated }
    }

    private func search(_ words: String, retrying: Bool = false) async {
        generation += 1
        let generation = generation
        self.retrying = retrying
        retryingMore = false
        query = words
        page = 0
        totalPages = 1
        failed = false
        loading = true
        focusedIndex = -1
        await take(generation, words, page: 1)
        // A cancelled search's job has been replaced already: only the current one clears itself.
        if !Task.isCancelled { job = nil }
    }

    private func nextPage() {
        guard let words = query, !loading, page < totalPages else { return }
        failed = false
        loading = true
        let generation = generation
        let next = page + 1
        Task { await take(generation, words, page: next) }
    }

    private func take(_ generation: Int, _ words: String, page wanted: Int32) async {
        let answer: Page<MediaResult>
        do {
            answer = try await source(words, wanted)
        } catch {
            // Newer words cancelled this search: not a failure, and the last results stay until theirs come (Android
            // rethrows the cancellation; the reviewer's round 1 on #62).
            if error is CancellationError || Task.isCancelled { return }
            guard self.generation == generation else { return }
            loading = false
            retrying = false
            retryingMore = false
            failed = true
            // Results of other words would sit under these ones: gone with the failure.
            if wanted == 1 { cards = [] }
            return
        }
        guard self.generation == generation else { return }
        let results = answer.results.compactMap { $0 as? MediaResult }
        cards = DiscoverKt.appendResults(cards: wanted == 1 ? [] : cards, page: results, images: images)
        page = wanted
        totalPages = answer.totalPages
        loading = false
        retrying = false
        retryingMore = false
        if DiscoverKt.wantsNextPage(count: Int32(cards.count), focused: Int32(focusedIndex), page: page, totalPages: totalPages, busy: false, fills: TitleGridModel.fills, lookahead: TitleGridModel.lookahead) {
            nextPage()
        }
    }
}
