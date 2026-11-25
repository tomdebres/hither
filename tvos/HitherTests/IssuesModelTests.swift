import HitherCore
import XCTest
@testable import Hither

/// The Issues page's model against a scripted source (Android's `IssuesModelTest`, DECISIONS 106, 365, 376).
@MainActor
final class IssuesModelTests: XCTestCase {
    /// Open issues as the server sends them, newest first: each id an audio issue on a film of its own; [total] the
    /// server's count.
    nonisolated static func page(_ ids: [Int], total: Int) -> IssuePage {
        let results = ids.map { id in
            """
            {"id":\(id),"issueType":2,"status":1,"createdAt":"2026-11-01T10:00:00.000Z",
             "media":{"id":\(id),"tmdbId":\(1000 + id),"mediaType":"movie","status":5}}
            """
        }.joined(separator: ",")
        let json = #"{"pageInfo":{"results":\#(total)},"results":[\#(results)]}"#
        return LastSeenKt.lastSeenJson.decodeFromString(deserializer: IssuePage.companion.serializer(), string: json) as! IssuePage
    }

    private func names() -> TitleNames {
        TitleNames { _, id in RequestTitle(title: "Title \(id)", year: "2026", poster: nil, backdrop: nil) }
    }

    private func waitFor(_ what: String, _ done: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(5)
        while !done() {
            guard Date() < deadline else { return XCTFail("timed out waiting for \(what)") }
            try await Task.sleep(for: .milliseconds(10))
        }
    }

    /// Twelve open issues, ten a page; a later page fails while [failLater], and an ask waits while [held]. Each ask
    /// is kept as "<filter> <skip> <take>".
    private final class Script {
        var asked: [String] = []
        var failLater = false
        var held = false

        func source() -> IssuesSource {
            IssuesSource { filter, skip, take in
                self.asked.append("\(filter.api) \(skip) \(take)")
                while self.held { try await Task.sleep(for: .milliseconds(10)) }
                if skip > 0, self.failLater { throw URLError(.timedOut) }
                let ids = Array(1...12).dropFirst(Int(skip)).prefix(Int(take))
                return IssuesModelTests.page(Array(ids), total: 12)
            }
        }
    }

    /// DECISIONS 376 (Android's 365): a later page that fails keeps the rows, and Try Again asks for that page, once,
    /// not the list from its top; moving along does not ask again (DECISIONS 42, 65). Before, Try Again read the list
    /// from its top (and nothing offered it: the next case), so the list stayed short until the filter changed.
    func testTryAgainAfterAFailedLaterPageAsksForThatPageOnceAndKeepsTheRows() async throws {
        let script = Script()
        let model = IssuesModel(source: script.source(), names: names())
        model.open()
        try await waitFor("the first page") { model.rows.count == 10 }
        script.failLater = true
        model.focused(6)
        try await waitFor("the second page's failure") { !model.loading }
        XCTAssertEqual(script.asked, ["open 0 10", "open 10 10"])
        XCTAssertEqual(model.rows.count, 10, "the rows stay")
        XCTAssertEqual(model.body, .rows)
        // Moving along does not ask again: Try Again does, for the page after the rows, once however often pressed.
        model.focused(9)
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertEqual(script.asked.count, 2, "moving along asked again: \(script.asked)")
        script.failLater = false
        script.held = true
        model.retry()
        model.retry()
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(script.asked, ["open 0 10", "open 10 10", "open 10 10"], "Try Again asks for the page after the rows")
        XCTAssertEqual(model.rows.count, 10, "the rows stay while it is out")
        script.held = false
        try await waitFor("the page after the rows") { model.rows.count == 12 }
        XCTAssertEqual(model.rows.map(\.key), (1...12).map { "issue:\($0)" })
        XCTAssertEqual(model.body, .rows)
    }

    /// The list's end says so: after a later page failed, Try Again ends the rows (`ListEnd`); while it is out it
    /// stays and reads "Trying…" (so it keeps focus), with no still place beside it; when the page comes it goes.
    func testAFailedLaterPageEndsTheRowsInTryAgainUntilItsPageComes() async throws {
        let script = Script()
        let model = IssuesModel(source: script.source(), names: names())
        model.open()
        try await waitFor("the first page") { model.rows.count == 10 }
        XCTAssertFalse(model.moreFailed)
        script.failLater = true
        script.held = true
        model.focused(6)
        XCTAssertTrue(model.loadingMore, "a still place while the page is out")
        script.held = false
        try await waitFor("the second page's failure") { !model.loading }
        XCTAssertTrue(model.moreFailed, "Try Again ends the rows")
        XCTAssertFalse(model.loadingMore)
        XCTAssertFalse(model.retryingMore, "it reads Try Again")
        script.failLater = false
        script.held = true
        model.retry()
        XCTAssertTrue(model.moreFailed, "Try Again stays while its page is out")
        XCTAssertTrue(model.retryingMore, "and reads Trying…")
        XCTAssertFalse(model.loadingMore, "no still place beside it")
        script.held = false
        try await waitFor("the page after the rows") { model.rows.count == 12 }
        XCTAssertFalse(model.moreFailed, "gone with its page")
        XCTAssertFalse(model.retryingMore)
        // A Try Again whose page fails again stays, reading Try Again.
        model.choose(.all)
        try await waitFor("the first page under All") { model.rows.count == 10 }
        script.failLater = true
        model.focused(6)
        try await waitFor("the second page's failure") { model.moreFailed && !model.loading }
        model.retry()
        try await waitFor("the retry's failure") { !model.loading }
        XCTAssertTrue(model.moreFailed)
        XCTAssertFalse(model.retryingMore)
        XCTAssertEqual(model.rows.count, 10)
    }
}
