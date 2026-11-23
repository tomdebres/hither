import HitherCore
import XCTest
@testable import Hither

/// The seasons Information lists at rest (Tom's D2): neighbours with the same status as one run, "Seasons 1–3".
final class SeasonRunTests: XCTestCase {
    private func season(_ number: Int32, _ states: [SeasonState], extra: [StatusBadge] = []) -> SeasonLine {
        SeasonLine(number: number, episodes: 10, badges: states.map { StatusBadgesKt.seasonBadge(state: $0) } + extra)
    }

    private let pending4k = StatusBadge(word: .pending, kind: .warning, is4k: true, inProgress: false, progress: nil)

    /// Neighbours that share a status join; another status, a season with none between or a gap breaks the run.
    func testNeighboursWithOneStatusJoin() {
        let runs = SeasonRun.runs([
            season(1, [.available]), season(2, [.available]), season(3, [.available]),
            season(4, [.requested]),
            season(5, []),
            season(6, [.requested]),
            season(8, [.requested]),
        ])
        XCTAssertEqual(runs.map(\.name), ["Seasons 1–3", "Season 4", "Season 6", "Season 8"])
        XCTAssertEqual(runs.map { $0.badges.map(\.text) }, [["Available"], ["Requested"], ["Requested"], ["Requested"]])
    }

    /// The specials stand alone, never "Seasons 0–1"; a 4K status of its own breaks a run.
    func testSpecialsAndFourKApart() {
        let runs = SeasonRun.runs([
            season(0, [.available]), season(1, [.available]), season(2, [.available]),
            season(3, [.available], extra: [pending4k]),
        ])
        XCTAssertEqual(runs.map(\.name), ["Specials", "Seasons 1–2", "Season 3"])
        XCTAssertEqual(runs.last?.badges.map(\.text), ["Available", "4K Pending"])
    }
}
