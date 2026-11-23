import HitherCore
import XCTest
@testable import Hither

/// My Requests' model against a scripted source (Android's `RequestsModelTest`, DECISIONS 66, 101).
@MainActor
final class RequestsModelTests: XCTestCase {
    /// A page of requests as the server sends them, newest first: [ids] each a pending film of its own.
    private func page(_ ids: [Int], total: Int? = nil) -> RequestPage {
        let results = ids.map { id in
            """
            {"id":\(id),"status":1,"type":"movie","is4k":false,"createdAt":"2026-11-01T10:00:00.000Z",
             "media":{"id":\(id),"tmdbId":\(1000 + id),"mediaType":"movie","status":2},"seasons":[]}
            """
        }.joined(separator: ",")
        let json = #"{"pageInfo":{"results":\#(total ?? ids.count)},"results":[\#(results)]}"#
        return LastSeenKt.lastSeenJson.decodeFromString(deserializer: RequestPage.companion.serializer(), string: json) as! RequestPage
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

    /// Coming back, the list is read again in place: focus stays on its row by key, wherever the row has moved.
    func testARefreshKeepsFocusOnItsRow() async throws {
        var answer = page([3, 2, 1])
        let model = RequestsModel(source: RequestsSource(page: { _, _ in answer }, cancel: { _ in }), names: names())
        model.open()
        try await waitFor("the first page") { model.rows.count == 3 }
        model.focused(1)
        answer = page([4, 3, 2, 1])
        model.open()
        try await waitFor("the refresh") { model.rows.count == 4 }
        XCTAssertEqual(model.rows.map(\.id), [4, 3, 2, 1], "the new request on top")
        XCTAssertEqual(model.focusedIndex, 2, "focus followed request 2")
        XCTAssertEqual(model.newTop, 0, "not moved to the top: focus was not there")
    }

    /// On the top row, a newer request takes focus: what was just asked for is in view.
    func testANewerRequestTakesTheTopRowsFocus() async throws {
        var answer = page([2, 1])
        let model = RequestsModel(source: RequestsSource(page: { _, _ in answer }, cancel: { _ in }), names: names())
        model.open()
        try await waitFor("the first page") { model.rows.count == 2 }
        model.focused(0)
        answer = page([3, 2, 1])
        model.refresh()
        try await waitFor("the refresh") { model.rows.count == 3 }
        XCTAssertEqual(model.newTop, 1)
        XCTAssertEqual(model.focusedIndex, 0)
    }

    /// One press, one DELETE: a second press while the first is out sends nothing.
    func testOneCancelAtATime() async throws {
        var cancels: [Int32] = []
        let model = RequestsModel(source: RequestsSource(page: { _, _ in self.page([2, 1]) }, cancel: { id in
            cancels.append(id)
            try await Task.sleep(for: .milliseconds(200))
        }), names: names())
        model.open()
        try await waitFor("the first page") { model.rows.count == 2 }
        try await waitFor("the names") { model.title(model.rows[0]) != nil }
        var outcomes: [CancelOutcome] = []
        model.cancel(model.rows[0]) { outcomes.append($0) }
        model.cancel(model.rows[0]) { outcomes.append($0) }
        model.cancel(model.rows[1]) { outcomes.append($0) }
        try await waitFor("the answer") { !outcomes.isEmpty }
        XCTAssertEqual(cancels, [2], "one DELETE")
        guard case .cancelled(let id, let title) = outcomes[0] else { return XCTFail("cancelled: \(outcomes)") }
        XCTAssertEqual(id, 2)
        XCTAssertEqual(title, "Title 1002", "the request's title, named")
        model.drop(id)
        XCTAssertEqual(model.rows.map(\.id), [1])
    }
}
