import XCTest
@testable import Hither

/// "Requested …" and "Opened …" as Android words them (`DateUtils.getRelativeTimeSpanString`): relative within a week,
/// the date beyond it, written as every date on the TV (`Formats.date`).
final class RelativeTimeTests: XCTestCase {
    private let now = ISO8601DateFormatter().date(from: "2026-11-15T12:00:00Z")!

    func testWithinAWeekIsRelative() {
        XCTAssertEqual(Formats.relative("2026-11-15T11:59:11.000Z", now: now), "49 seconds ago")
        XCTAssertEqual(Formats.relative("2026-11-15T09:00:00.000Z", now: now), "3 hours ago")
        XCTAssertEqual(Formats.relative("2026-11-12T12:00:00.000Z", now: now), "3 days ago")
    }

    func testBeyondAWeekIsTheDate() {
        XCTAssertEqual(Formats.relative("2026-01-01T10:00:00.000Z", now: now), "Jan 1, 2026")
        XCTAssertEqual(Formats.relative("2026-11-07T12:00:00.000Z", now: now), "Nov 7, 2026", "eight days ago")
    }

    /// A clock behind the server's: never "in 5 seconds".
    func testAFutureTimeIsNow() {
        XCTAssertFalse(Formats.relative("2026-11-15T12:00:05.000Z", now: now)!.hasPrefix("in "))
    }
}
