import Foundation
import HitherCore
import Observation

/// Each title's name, year and pictures for lists that carry only its id (My Requests, Issues): asked for once per
/// title, four at a time, as Seerr's web asks for them row by row (Android's `fetchTitles` and `TITLES_AT_ONCE`). A
/// title whose details failed is asked for again the next time the list wants it.
@MainActor
@Observable
final class TitleNames {
    enum Load {
        case loading
        case ready(RequestTitle)
        case failed
    }

    /// What a list wants a name for: the title's key (`movie:550`), its type and its TMDB id.
    struct Wanted {
        let key: String
        let type: MediaType
        let tmdbId: Int32
    }

    private(set) var titles: [String: Load] = [:]

    /// Titles asked for at once: a page's worth would be ten calls in one go.
    static let atOnce = 4

    @ObservationIgnored private let fetch: (MediaType, Int32) async throws -> RequestTitle
    @ObservationIgnored private var queue: [Wanted] = []
    @ObservationIgnored private var running = 0

    init(fetch: @escaping (MediaType, Int32) async throws -> RequestTitle) {
        self.fetch = fetch
    }

    /// The server's titles through the call that renews an ended session.
    convenience init(gateway: SessionGateway, baseUrl: String) {
        self.init { type, tmdbId in
            try await gateway.call { client -> RequestTitle in
                if type == .movie { return requestTitle(details: try await client.movie(tmdbId: tmdbId), baseUrl: baseUrl) }
                return requestTitle(details: try await client.tv(tmdbId: tmdbId), baseUrl: baseUrl)
            }
        }
    }

    func title(_ key: String) -> RequestTitle? {
        if case .ready(let title) = titles[key] { return title }
        return nil
    }

    func failed(_ key: String) -> Bool {
        if case .failed = titles[key] { return true }
        return false
    }

    /// Asks for each title not yet here or on its way, or whose last answer failed.
    func want(_ wanted: [Wanted]) {
        for title in wanted {
            switch titles[title.key] {
            case .loading, .ready: continue
            case .failed, nil: break
            }
            titles[title.key] = .loading
            queue.append(title)
        }
        pump()
    }

    private func pump() {
        while running < Self.atOnce, !queue.isEmpty {
            let next = queue.removeFirst()
            running += 1
            Task {
                do {
                    titles[next.key] = .ready(try await fetch(next.type, next.tmdbId))
                } catch {
                    titles[next.key] = .failed
                }
                running -= 1
                pump()
            }
        }
    }
}

extension RequestRow {
    var wanted: TitleNames.Wanted { TitleNames.Wanted(key: titleKey, type: type, tmdbId: tmdbId) }
}
