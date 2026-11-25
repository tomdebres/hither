import HitherCore
import XCTest
@testable import Hither

/// Search's results and the titles grids end in Try Again after a later page fails, and it stays, reading "Trying…",
/// while its page is out (DECISIONS 65's rule, 376). Before, `nextPage` cleared the failure at the press, so Try Again
/// went at once and focus with it.
@MainActor
final class ListEndTests: XCTestCase {
    /// Page [page] of two, twenty films each (ids 100 + n on the first, 200 + n on the second).
    nonisolated static func page(_ page: Int32) -> Page<MediaResult> {
        let results = (0..<20).map { index in
            MediaResult(id: page * 100 + Int32(index), mediaType: "movie", title: "Film \(page)-\(index)", name: nil, overview: nil,
                        posterPath: nil, backdropPath: nil, releaseDate: "2020-01-01", firstAirDate: nil, voteAverage: 0,
                        genreIds: [], originalLanguage: nil, mediaInfo: nil, profilePath: nil, knownFor: [])
        }
        return Page(page: page, totalPages: 2, totalResults: 40, results: results)
    }

    /// The pages asked for; a later page fails while [failLater], and an ask waits while [held].
    final class Script {
        var asked: [Int32] = []
        var failLater = false
        var held = false

        func answer(_ page: Int32) async throws -> Page<MediaResult> {
            asked.append(page)
            while held { try await Task.sleep(for: .milliseconds(10)) }
            if page > 1, failLater { throw URLError(.timedOut) }
            return ListEndTests.page(page)
        }
    }

    private func waitFor(_ what: String, _ done: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(5)
        while !done() {
            guard Date() < deadline else { return XCTFail("timed out waiting for \(what)") }
            try await Task.sleep(for: .milliseconds(10))
        }
    }

    private func search(_ script: Script) -> SearchModel {
        SearchModel(source: { _, page in try await script.answer(page) }, images: ImageSource.companion.unknown(serverBase: "http://localhost"), debounce: .milliseconds(10))
    }

    private func grid(_ script: Script) -> TitleGridModel {
        TitleGridModel(gateway: SessionGateway(), images: ImageSource.companion.unknown(serverBase: "http://localhost"), hub: BadgeHub()) { _, page in try await script.answer(page) }
    }

    /// Search: Try Again ends the results after a later page fails; moving along does not ask again; Try Again asks for
    /// that page once and stays while it is out; when the page comes it goes.
    func testSearchsTryAgainStaysWhileItsPageIsOut() async throws {
        let script = Script()
        let model = search(script)
        model.typed = "film"
        try await waitFor("the first page") { model.cards.count == 20 }
        script.failLater = true
        model.focused(10)
        try await waitFor("the second page's failure") { model.failed && !model.loading }
        XCTAssertTrue(model.moreFailed, "Try Again ends the results")
        XCTAssertEqual(model.cards.count, 20, "the results stay")
        model.focused(15)
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertEqual(script.asked, [1, 2], "moving along asked again")
        script.failLater = false
        script.held = true
        model.retry()
        model.retry()
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(script.asked, [1, 2, 2], "Try Again asks for that page, once")
        XCTAssertTrue(model.moreFailed, "Try Again stays while its page is out (it went at the press, and focus with it)")
        script.held = false
        try await waitFor("the second page") { model.cards.count == 40 }
        XCTAssertFalse(model.moreFailed, "gone with its page")
    }

    /// A titles grid (a row's See More, a collection, a keyword, Movies and Series): the same.
    func testAGridsTryAgainStaysWhileItsPageIsOut() async throws {
        let script = Script()
        let model = grid(script)
        model.start()
        try await waitFor("the first page") { model.cards.count == 20 }
        script.failLater = true
        model.focused(10)
        try await waitFor("the second page's failure") { model.failed && !model.loading }
        XCTAssertTrue(model.moreFailed, "Try Again ends the grid")
        XCTAssertEqual(model.cards.count, 20, "the cards stay")
        model.focused(15)
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertEqual(script.asked, [1, 2], "moving along asked again")
        script.failLater = false
        script.held = true
        model.retry()
        model.retry()
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(script.asked, [1, 2, 2], "Try Again asks for that page, once")
        XCTAssertTrue(model.moreFailed, "Try Again stays while its page is out (it went at the press, and focus with it)")
        script.held = false
        try await waitFor("the second page") { model.cards.count == 40 }
        XCTAssertFalse(model.moreFailed, "gone with its page")
    }

    /// While it is out it reads "Trying…" (`retryingMore`); a page that fails again leaves it reading Try Again.
    func testTryAgainReadsTryingWhileOutAndTryAgainAfterAnotherFailure() async throws {
        for kind in ["search", "grid"] {
            let script = Script()
            let searchModel = kind == "search" ? search(script) : nil
            let gridModel = kind == "grid" ? grid(script) : nil
            searchModel?.typed = "film"
            gridModel?.start()
            let cards = { searchModel?.cards.count ?? gridModel?.cards.count ?? 0 }
            let moreFailed = { searchModel?.moreFailed ?? gridModel?.moreFailed ?? false }
            let retryingMore = { searchModel?.retryingMore ?? gridModel?.retryingMore ?? false }
            let loading = { searchModel?.loading ?? gridModel?.loading ?? false }
            try await waitFor("\(kind): the first page") { cards() == 20 }
            script.failLater = true
            searchModel?.focused(10)
            gridModel?.focused(10)
            try await waitFor("\(kind): the second page's failure") { moreFailed() && !loading() }
            XCTAssertFalse(retryingMore(), "\(kind): it reads Try Again")
            script.held = true
            searchModel?.retry()
            gridModel?.retry()
            XCTAssertTrue(retryingMore(), "\(kind): Trying… while it is out")
            script.held = false
            try await waitFor("\(kind): the second failure") { !loading() }
            XCTAssertTrue(moreFailed(), "\(kind): Try Again stays after another failure")
            XCTAssertFalse(retryingMore(), "\(kind): reading Try Again")
            XCTAssertEqual(cards(), 20, kind)
        }
    }
}
