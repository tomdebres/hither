import XCTest
@testable import Hither

/// How Hither writes a value on the TV (`Formats`, the review's X9): one way each. The words assume the test devices'
/// English, as the other tests do.
final class FormatsTests: XCTestCase {
    /// A runtime as the Apple TV app writes it, not core's "94 minutes" (and not `Duration`'s "1 hr, 34 min").
    func testRuntime() {
        XCTAssertEqual(Formats.runtime(minutes: 94), "1 hr 34 min")
        XCTAssertEqual(Formats.runtime(minutes: 45), "45 min")
        XCTAssertEqual(Formats.runtime(minutes: 120), "2 hr")
        XCTAssertNil(Formats.runtime(minutes: 0), "none for no runtime")
    }

    /// Facts on one line between middle dots, the missing ones left out.
    func testJoin() {
        XCTAssertEqual(Formats.join(["2026", nil, "1 hr 34 min", "", "Horror"]), "2026 · 1 hr 34 min · Horror")
        XCTAssertNil(Formats.join([nil, ""]))
    }

    /// A server's day, as every date; what is not a day comes back nil.
    func testDay() {
        let day = DateComponents(calendar: Calendar(identifier: .gregorian), year: 1992, month: 1, day: 5).date!
        XCTAssertEqual(Formats.day("1992-01-05"), day.formatted(date: .abbreviated, time: .omitted))
        XCTAssertNil(Formats.day("05/01/1992"))
    }

    /// Diagnostics' moment: the day as every date, then the time to the second.
    func testDateTime() {
        let moment = DateComponents(calendar: Calendar(identifier: .gregorian), year: 2026, month: 11, day: 15, hour: 18, minute: 43, second: 27).date!
        let written = Formats.dateTime(moment)
        XCTAssertTrue(written.hasPrefix(Formats.date(moment)), written)
        XCTAssertTrue(written.contains("43:27"), written)
    }

    /// Money in whole dollars (polish part 1's, moved here).
    func testMoney() {
        XCTAssertEqual(Formats.money(400_290_973), "$400,290,973")
    }
}
