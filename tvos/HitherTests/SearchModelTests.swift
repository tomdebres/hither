import HitherCore
import XCTest
@testable import Hither

/// Search's model against a scripted source (Android's `SearchModelTest`, DECISIONS 185).
@MainActor
final class SearchModelTests: XCTestCase {
    private func page(_ titles: [String]) -> Page<MediaResult> {
        let results = titles.enumerated().map { index, title in
            MediaResult(id: Int32(100 + index), mediaType: "movie", title: title, name: nil, overview: nil, posterPath: nil,
                        backdropPath: nil, releaseDate: "2020-01-01", firstAirDate: nil, voteAverage: 0, genreIds: [],
                        originalLanguage: nil, mediaInfo: nil, profilePath: nil, knownFor: [])
        }
        return Page(page: 1, totalPages: 1, totalResults: Int32(results.count), results: results)
    }

    private func waitFor(_ what: String, _ done: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(5)
        while !done() {
            guard Date() < deadline else { return XCTFail("timed out waiting for \(what)") }
            try await Task.sleep(for: .milliseconds(10))
        }
    }

    /// Typing on while a search is out keeps the last results until the new words' answer: the search the new words
    /// cancel is not a failure (the reviewer's round 1 on #62; Android rethrows the cancellation).
    func testACancelledSearchIsNotAFailure() async throws {
        var asked: [String] = []
        let model = SearchModel(source: { words, _ in
            asked.append(words)
            // Still out when the next words are typed.
            if words == "ab" { try await Task.sleep(for: .seconds(30)) }
            return self.page(["\(words) one"])
        }, images: ImageSource.companion.unknown(serverBase: "http://localhost"), debounce: .milliseconds(50))

        model.typed = "a"
        try await waitFor("a's results") { model.body == .results }
        model.typed = "ab"
        try await waitFor("ab asked") { asked.contains("ab") }
        // "abc" cancels the search for "ab" and waits out the debounce.
        model.typed = "abc"
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(model.body, .results, "the last results stay while the next words wait")
        XCTAssertFalse(model.failed, "a cancelled search is not a failure")
        XCTAssertEqual((model.cards.first as? TitleCard)?.title, "a one")
        try await waitFor("abc's results") { (model.cards.first as? TitleCard)?.title == "abc one" }
        XCTAssertEqual(asked, ["a", "ab", "abc"])
    }
}
