import HitherCore
import XCTest
@testable import Hither

/// "N waiting for approval" read for the kept admin (`RequestAlerts.readAdminWaiting`, DECISIONS 210), and #65's review
/// Nit: a count read still out when the admin was removed came back after the removal and brought the line back.
@MainActor
final class RequestAlertsTests: XCTestCase {
    private let suite = "hither.tests.requestAlerts"
    private var defaults: UserDefaults!

    override func setUp() async throws {
        defaults = UserDefaults(suiteName: suite)
        defaults.removePersistentDomain(forName: suite)
    }

    override func tearDown() async throws {
        defaults.removePersistentDomain(forName: suite)
    }

    /// The pending count, each read held until the test lets it answer (reads numbered from 1).
    final class HeldCount: @unchecked Sendable {
        private var answers: [Int: CheckedContinuation<Int, Never>] = [:]
        private(set) var asked = 0

        func read() async -> Int {
            await withCheckedContinuation { continuation in
                asked += 1
                answers[asked] = continuation
            }
        }

        func give(_ count: Int, to read: Int = 1) { answers.removeValue(forKey: read)?.resume(returning: count) }
    }

    private func alerts(_ held: HeldCount) -> RequestAlerts {
        let user = User(id: 8, displayName: "User 8", email: nil, username: nil, permissions: KotlinInt(value: 32), userType: nil,
                        avatar: nil, requestCount: nil, movieQuotaLimit: nil, movieQuotaDays: nil, tvQuotaLimit: nil, tvQuotaDays: nil,
                        settings: nil)
        let settings = LastSeenKt.lastSeenJson.decodeFromString(deserializer: PublicSettings.companion.serializer(), string: "{}") as! PublicSettings
        return RequestAlerts(
            session: Session(user: user, settings: settings, baseUrl: "http://seerr.invalid"),
            gateway: SessionGateway(),
            notices: Notices(),
            ready: ReadyLook(defaults: defaults),
            pendingCount: { await held.read() }
        )
    }

    private func waitUntilAsked(_ held: HeldCount, _ times: Int) async throws {
        let deadline = Date().addingTimeInterval(5)
        while held.asked < times {
            guard Date() < deadline else { return XCTFail("the count was never read") }
            try await Task.sleep(for: .milliseconds(10))
        }
    }

    /// While an admin is kept, the read's count is shown: the waiting line the next test expects gone is there to see.
    func testAKeptAdminsCountIsShown() async throws {
        let held = HeldCount()
        let alerts = alerts(held)
        let read = alerts.readAdminWaiting(kept: true)
        try await waitUntilAsked(held, 1)
        held.give(3)
        await read?.value
        XCTAssertEqual(alerts.adminWaiting, 3)
        XCTAssertEqual(alerts.waitingShown, 3)
    }

    /// The admin removed (Remove, or the tenth wrong PIN) while a read is out: when it answers, nothing comes back.
    func testARemovalWinsOverACountStillOut() async throws {
        let held = HeldCount()
        let alerts = alerts(held)
        let late = alerts.readAdminWaiting(kept: true)
        try await waitUntilAsked(held, 1)
        alerts.readAdminWaiting(kept: false)
        XCTAssertNil(alerts.adminWaiting)
        held.give(3)
        await late?.value
        XCTAssertNil(alerts.adminWaiting, "the late read brought the count back")
        XCTAssertNil(alerts.waitingShown, "\"N waiting for approval\" after the admin went")
    }

    /// Two reads out together (a decision, then coming back to the front): the later read's count stands, even when the
    /// earlier one answers last.
    func testTheLatestReadStands() async throws {
        let held = HeldCount()
        let alerts = alerts(held)
        let first = alerts.readAdminWaiting(kept: true)
        try await waitUntilAsked(held, 1)
        let second = alerts.readAdminWaiting(kept: true)
        try await waitUntilAsked(held, 2)
        held.give(4, to: 2)
        await second?.value
        held.give(5, to: 1)
        await first?.value
        XCTAssertEqual(alerts.adminWaiting, 4, "the earlier read's count came last and stood")
    }
}
