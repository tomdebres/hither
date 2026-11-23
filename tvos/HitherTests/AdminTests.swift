import HitherCore
import XCTest
@testable import Hither

/// The admin's record in memory, for the gateway's tests.
final class MemoryAdminStore: AdminStorage, @unchecked Sendable {
    var kept: KeptAdmin?
    var saves = 0

    func admin() throws -> KeptAdmin? { kept }

    func save(_ admin: KeptAdmin) throws {
        kept = admin
        saves += 1
    }

    func clear() throws { kept = nil }
}

/// The PIN as it is kept (Android's `PinHashTest`, DECISIONS 92).
final class PinHashTests: XCTestCase {
    /// PBKDF2-HMAC-SHA256, 50,000 rounds, 32 bytes: the value Python's `hashlib.pbkdf2_hmac` (RFC 8018) gives for the same
    /// PIN and salt, which is what Java's PBKDF2WithHmacSHA256 on Android gives too.
    func testTheSameHashAsAndroids() {
        let salt = Data(0..<16)
        let derived = PinHash.derive("2580", salt: salt, iterations: 50_000)
        XCTAssertEqual(derived?.base64EncodedString(), "P/t0hNrMi7zJl5PDtKtwjXfstqJVbhLrqRL9Q5tvJOY=")
        XCTAssertEqual(PinHash.rounds, 50_000, "Android's rounds")
    }

    func testMatchesItsPinOnlyAndSaltsEachHash() throws {
        let one = try XCTUnwrap(PinHash.of("2580", iterations: 1_000))
        let two = try XCTUnwrap(PinHash.of("2580", iterations: 1_000))
        XCTAssertTrue(one.matches("2580"))
        XCTAssertFalse(one.matches("2581"))
        XCTAssertFalse(one.matches(""))
        XCTAssertNotEqual(one.salt, two.salt, "a fresh salt each time")
        XCTAssertNotEqual(one.hash, two.hash)
        XCTAssertEqual(Data(base64Encoded: one.salt)?.count, 16)
    }

    /// Never the digits, the hash or a credential in a description or a dump: either can reach a log by accident.
    func testNothingSecretInADescription() throws {
        let hash = try XCTUnwrap(PinHash.of("2580", iterations: 1_000))
        let kept = KeptAdmin(baseUrl: "http://seerr.invalid", email: "approver@example.invalid", password: "approver-password",
                             userId: 9, displayName: "Approver", pin: hash, session: "[{\"name\":\"connect.sid\"}]")
        var dumped = ""
        dump(kept, to: &dumped)
        for text in [String(describing: hash), String(describing: kept), String(reflecting: kept), dumped] {
            for secret in [hash.salt, hash.hash, "approver-password", "approver@example.invalid", "connect.sid"] {
                XCTAssertFalse(text.contains(secret), "\(text) shows a secret")
            }
        }
    }
}

/// The PIN's tries at the switch (Android's `AdminSwitchGatewayTest`, DECISIONS 93), with the TV's clock in the test's
/// hands. A wrong PIN never reaches the server, so these need none.
@MainActor
final class AdminGuardTests: XCTestCase {
    private var now: Int64 = 1_800_000_000_000
    private let store = MemoryAdminStore()

    private func gateway() -> AdminGateway {
        AdminGateway(store: store, clock: { [unowned self] in now }, hash: { PinHash.of($0, iterations: 1_000) })
    }

    private func keep(wrong: Int32 = 0) throws {
        store.kept = KeptAdmin(baseUrl: "http://seerr.invalid", email: "approver@example.invalid", password: "approver-password",
                               userId: 9, displayName: "Approver", pin: try XCTUnwrap(PinHash.of("2580", iterations: 1_000)),
                               tries: KeptTries(wrong: wrong))
    }

    /// The PIN check as left, wrong: how many are left and the wait.
    private func wrong(_ result: AdminSwitch, file: StaticString = #filePath, line: UInt = #line) -> (left: Int32, waitMs: Int64)? {
        guard case .pin(let check) = result, let wrong = check as? PinCheckWrong else {
            XCTFail("expected a wrong PIN, got \(result)", file: file, line: line)
            return nil
        }
        return (wrong.left, wrong.waitMs)
    }

    /// Four wrong PINs cost nothing but the try; from the fifth each makes a wait (30 s, then 1, 5, 15 and 60 minutes),
    /// during which nothing is checked, the right PIN included; the tenth removes the account.
    func testFourFreeThenWaitsThenTheTenthRemoves() async throws {
        try keep()
        let admin = gateway()
        for left in [9, 8, 7, 6] as [Int32] {
            let answer = wrong(await admin.switchIn("0000"))
            XCTAssertEqual(answer?.left, left)
            XCTAssertEqual(answer?.waitMs, 0, "free")
        }
        let waits: [Int64] = [30_000, 60_000, 300_000, 900_000, 3_600_000]
        for (index, wait) in waits.enumerated() {
            let answer = wrong(await admin.switchIn("0000"))
            XCTAssertEqual(answer?.left, Int32(5 - index))
            XCTAssertEqual(answer?.waitMs, wait)
            // During the wait: nothing checked, the right PIN included, and nothing counted.
            now += 1_000
            let during = await admin.switchIn("2580")
            guard case .pin(let check) = during, let held = check as? PinCheckWait else { return XCTFail("expected a wait, got \(during)") }
            XCTAssertEqual(held.waitMs, wait - 1_000)
            XCTAssertEqual(store.kept?.tries.wrong, Int32(5 + index), "a wait counts no try")
            now += wait
        }
        XCTAssertNotNil(store.kept, "kept through the ninth")
        let tenth = await admin.switchIn("0000")
        guard case .pin(let check) = tenth else { return XCTFail("expected the PIN's answer, got \(tenth)") }
        XCTAssertTrue(check is PinCheckRemoved, "the tenth wrong PIN removes the account")
        XCTAssertNil(store.kept, "the account is gone from the TV")
        XCTAssertNil(admin.keptName)
        XCTAssertNil(admin.active)
    }

    /// The tries are in the record, so a restart (a new gateway) carries on from them.
    func testTriesSurviveARestart() async throws {
        try keep()
        _ = await gateway().switchIn("1111")
        _ = await gateway().switchIn("1111")
        let third = await gateway().switchIn("1111")
        XCTAssertEqual(wrong(third)?.left, 7)
        XCTAssertEqual(store.kept?.tries.wrong, 3)
    }

    /// Setting the TV's clock back past a wait's start gives the whole wait again rather than none.
    func testAClockSetBackGivesTheWholeWait() async throws {
        try keep(wrong: 4)
        let fifth = await gateway().switchIn("1111")
        XCTAssertEqual(wrong(fifth)?.waitMs, 30_000)
        now -= 3_600_000
        let answer = await gateway().switchIn("2580")
        guard case .pin(let check) = answer, let held = check as? PinCheckWait else { return XCTFail("expected a wait, got \(answer)") }
        XCTAssertEqual(held.waitMs, 30_000)
    }

    /// Nothing kept: nothing to switch to, and calls as the admin refused.
    func testNothingKeptNothingActive() async throws {
        let admin = gateway()
        guard case .noneKept = await admin.switchIn("2580") else { return XCTFail("expected none kept") }
        do {
            _ = try await admin.asAdmin { try await $0.me() }
            XCTFail("a call as an admin that is not active")
        } catch {
            XCTAssertTrue(error is AdminGateway.NotActive, "\(error)")
        }
    }
}

/// The gateway against the run's fake-seerr (`scripts/tvos-app` gives its address): signing in, keeping nothing until
/// the PIN, the switch with its session, the idle clock and Switch Back.
@MainActor
final class AdminServerTests: XCTestCase {
    private var fake: String {
        get throws {
            try XCTUnwrap(ProcessInfo.processInfo.environment["HITHER_FAKE_SEERR"].flatMap { $0.isEmpty ? nil : $0 },
                          "HITHER_FAKE_SEERR is not set: run the tests through scripts/tvos-app")
        }
    }

    private let store = MemoryAdminStore()

    override func setUp() async throws {
        try await post("/fake/reset")
    }

    private func post(_ path: String) async throws {
        var request = URLRequest(url: URL(string: try fake + path)!)
        request.httpMethod = "POST"
        let (_, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200, path)
    }

    /// The family's own user, as Hither runs signed in.
    private func family() async throws -> User {
        let client = DarwinKt.darwinSeerrClient(baseUrl: try fake, cookies: SessionCookies(baseUrl: try fake, vault: MemoryVault()), failures: nil)
        defer { client.close() }
        return try await client.signIn(email: "family@example.invalid", password: "right-password")
    }

    private func gateway(idleMs: Int64 = AdminGuardKt.ADMIN_IDLE_MS) -> AdminGateway {
        AdminGateway(store: store, hash: { PinHash.of($0, iterations: 1_000) }, idleMs: idleMs)
    }

    private func approver(_ password: String = "approver-password") throws -> SignInRequest {
        SignInRequest(baseUrl: try fake, email: "approver@example.invalid", password: password)
    }

    /// Signed in, the account is held in memory only; the PIN set, its record holds the session and the PIN's hash.
    func testNothingKeptUntilThePinIsSet() async throws {
        let admin = gateway()
        let answer = await admin.signIn(try approver(), family: try await family())
        XCTAssertEqual(answer, .accepted(.approver, displayName: "Approver"))
        XCTAssertNil(store.kept, "nothing kept before the PIN")
        XCTAssertNil(admin.keptName)
        let set = await admin.setPin("2580")
        XCTAssertTrue(set)
        let kept = try XCTUnwrap(store.kept)
        XCTAssertEqual(kept.displayName, "Approver")
        XCTAssertTrue(kept.pin.matches("2580"))
        XCTAssertNotNil(kept.session, "the session kept with it")
        XCTAssertEqual(admin.keptName, "Approver")
    }

    /// The family's own account, or one without Manage Requests, is refused and nothing is kept.
    func testTheFamilyAndAnAccountWithoutTheRightAreRefused() async throws {
        let admin = gateway()
        let family = try await family()
        let own = await admin.signIn(SignInRequest(baseUrl: try fake, email: "family@example.invalid", password: "right-password"), family: family)
        XCTAssertEqual(own, .refused(.family))
        try await post("/fake/approver?rights=none")
        let none = await admin.signIn(try approver(), family: family)
        XCTAssertEqual(none, .refused(.noRight))
        let set = await admin.setPin("2580")
        XCTAssertFalse(set, "nothing waiting for a PIN")
        XCTAssertNil(store.kept)
    }

    /// The right PIN switches in on the kept session; Switch Back and the idle clock go back to the family, the account
    /// kept for next time; calls as the admin only while it is active.
    func testSwitchInSwitchBackAndTheIdleClock() async throws {
        let admin = gateway(idleMs: 1_500)
        _ = await admin.signIn(try approver(), family: try await family())
        _ = await admin.setPin("2580")
        guard case .switchedIn(let active) = await admin.switchIn("2580") else { return XCTFail("switched in") }
        XCTAssertEqual(active.displayName, "Approver")
        XCTAssertEqual(admin.active, active)
        let me = try await admin.asAdmin { try await $0.me() }
        XCTAssertEqual(me.id, 9, "calls go as the approver")
        admin.back()
        XCTAssertNil(admin.active, "Switch Back")
        XCTAssertNotNil(store.kept, "kept for next time")

        // The idle clock: a press puts it back to zero; without one it runs out.
        guard case .switchedIn = await admin.switchIn("2580") else { return XCTFail("switched in again") }
        try await Task.sleep(for: .milliseconds(1_000))
        admin.key()
        try await Task.sleep(for: .milliseconds(1_000))
        XCTAssertNotNil(admin.active, "a press put the idle clock back")
        try await Task.sleep(for: .milliseconds(2_500))
        XCTAssertNil(admin.active, "idle: back to the family")
        XCTAssertNotNil(store.kept)

        // Leaving the front.
        guard case .switchedIn = await admin.switchIn("2580") else { return XCTFail("switched in a third time") }
        admin.closed()
        XCTAssertNil(admin.active, "Hither left the front")
    }

    /// Lost rights: kept, not switched to. Removing signs the session out and forgets everything.
    func testLostRightsAndRemoval() async throws {
        let admin = gateway()
        _ = await admin.signIn(try approver(), family: try await family())
        _ = await admin.setPin("2580")
        try await post("/fake/approver?rights=none")
        guard case .noRight = await admin.switchIn("2580") else { return XCTFail("no right") }
        XCTAssertNotNil(store.kept, "kept")
        await admin.remove()
        XCTAssertNil(store.kept)
        XCTAssertNil(admin.keptName)
    }
}

/// The review's Nit on #64 (`ReadyLook.swift:47`): a look still out at sign-out wrote the old account's snapshot, names
/// and Top Shelf back after `ReadyLook.forget()`.
@MainActor
final class ReadyLookTests: XCTestCase {
    private let suite = "hither.tests.readyLook"
    private var defaults: UserDefaults!

    override func setUp() async throws {
        defaults = UserDefaults(suiteName: suite)
        defaults.removePersistentDomain(forName: suite)
        ReadyLook.forget(defaults: defaults)
    }

    override func tearDown() async throws {
        defaults.removePersistentDomain(forName: suite)
        ReadyLook.forget(defaults: defaults)
    }

    /// The look's read, held until the test lets it answer.
    final class HeldRead: NSObject, KotlinSuspendFunction0, @unchecked Sendable {
        private var answer: CheckedContinuation<Any?, Never>?
        private(set) var asked = false

        func __invoke() async throws -> Any? {
            await withCheckedContinuation { continuation in
                asked = true
                answer = continuation
            }
        }

        func give(_ requests: [MediaRequest]) { answer?.resume(returning: requests as Any?) }
    }

    /// A film of user 8's, available: a ready title for the Top Shelf.
    private var ready: MediaRequest {
        let json = """
        {"id":41,"status":2,"type":"movie","updatedAt":"2026-11-01T10:00:00.000Z","requestedBy":{"id":8,"displayName":"User 8"},
         "media":{"id":41,"tmdbId":550,"mediaType":"movie","status":5}}
        """
        return LastSeenKt.lastSeenJson.decodeFromString(deserializer: MediaRequest.companion.serializer(), string: json) as! MediaRequest
    }

    private func user(manage: Bool) -> User {
        User(id: 8, displayName: "User 8", email: nil, username: nil, permissions: KotlinInt(value: manage ? 16 : 32), userType: nil,
             avatar: nil, requestCount: nil, movieQuotaLimit: nil, movieQuotaDays: nil, tvQuotaLimit: nil, tvQuotaDays: nil, settings: nil)
    }

    private func server(_ read: HeldRead, manage: Bool = false) -> LookServer {
        let count = LastSeenKt.lastSeenJson.decodeFromString(deserializer: RequestCount.companion.serializer(), string: #"{"pending":3}"#) as! RequestCount
        return LookServer(
            me: { self.user(manage: manage) },
            requests: read,
            count: { count },
            art: { ShelfArt(title: "Title \($0.tmdbId)", year: "2026", poster: nil) }
        )
    }

    private func waitUntilAsked(_ read: HeldRead) async throws {
        let deadline = Date().addingTimeInterval(5)
        while !read.asked {
            guard Date() < deadline else { return XCTFail("the look never read") }
            try await Task.sleep(for: .milliseconds(10))
        }
    }

    /// Without a sign-out the look keeps its snapshot, the ready title's name and the Top Shelf: what the next test
    /// expects gone is there to see.
    func testALookKeepsWhatItFound() async throws {
        let read = HeldRead()
        let look = ReadyLook(defaults: defaults)
        let done = Task { await look.look(baseUrl: "http://seerr.invalid", userId: 8, background: false, server: server(read)) }
        try await waitUntilAsked(read)
        read.give([ready])
        let looked = await done.value
        XCTAssertTrue(looked)
        XCTAssertNotNil(DeviceLastSeen(defaults: defaults).kept(), "the snapshot")
        XCTAssertEqual(TitleArtStore(defaults: defaults).get("movie:550")?.title, "Title 550", "the ready title's name")
        let folder = try XCTUnwrap(ShelfFile.folder)
        XCTAssertEqual(ShelfFile.read(from: folder).map(\.key), ["movie:550"], "the Top Shelf")
    }

    /// Sign-out while the look is out: when it comes back it writes nothing of the account that went.
    func testALookOutAtSignOutKeepsNothing() async throws {
        let read = HeldRead()
        let look = ReadyLook(defaults: defaults)
        let done = Task { await look.look(baseUrl: "http://seerr.invalid", userId: 8, background: false, server: server(read, manage: true)) }
        try await waitUntilAsked(read)
        ReadyLook.forget(defaults: defaults)
        read.give([ready])
        let looked = await done.value
        XCTAssertFalse(looked, "the look counts for nothing")
        XCTAssertNil(DeviceLastSeen(defaults: defaults).kept(), "no snapshot of the account that went")
        XCTAssertNil(DeviceLastSeen(defaults: defaults).waiting, "no waiting count")
        XCTAssertNil(TitleArtStore(defaults: defaults).get("movie:550"), "no names")
        let folder = try XCTUnwrap(ShelfFile.folder)
        XCTAssertEqual(ShelfFile.read(from: folder).map(\.key), [], "nothing on the Top Shelf")
    }

    /// "N waiting for approval" is read only with Manage Requests, read live with the look (DECISIONS 79).
    func testTheWaitingCountOnlyWithTheRight() async throws {
        for manage in [true, false] {
            ReadyLook.forget(defaults: defaults)
            let read = HeldRead()
            let look = ReadyLook(defaults: defaults)
            let done = Task { await look.look(baseUrl: "http://seerr.invalid", userId: 8, background: false, server: server(read, manage: manage)) }
            try await waitUntilAsked(read)
            read.give([])
            _ = await done.value
            XCTAssertEqual(DeviceLastSeen(defaults: defaults).waiting, manage ? 3 : nil, "manage \(manage)")
        }
    }
}

/// The approval queue against a scripted source (Android's `ApprovalsModelTest`, DECISIONS 94).
@MainActor
final class ApprovalsModelTests: XCTestCase {
    private func page(_ ids: [Int]) -> RequestPage {
        let results = ids.map { id in
            """
            {"id":\(id),"status":1,"type":"movie","createdAt":"2026-11-01T10:00:00.000Z","requestedBy":{"id":8,"displayName":"User 8"},
             "media":{"id":\(id),"tmdbId":\(1000 + id),"mediaType":"movie","status":2}}
            """
        }.joined(separator: ",")
        let json = #"{"pageInfo":{"results":\#(ids.count)},"results":[\#(results)]}"#
        return LastSeenKt.lastSeenJson.decodeFromString(deserializer: RequestPage.companion.serializer(), string: json) as! RequestPage
    }

    private let approver = User(id: 9, displayName: "Approver", email: nil, username: nil, permissions: KotlinInt(value: 16), userType: nil,
                                avatar: nil, requestCount: nil, movieQuotaLimit: nil, movieQuotaDays: nil, tvQuotaLimit: nil, tvQuotaDays: nil, settings: nil)

    private func waitFor(_ what: String, _ done: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(5)
        while !done() {
            guard Date() < deadline else { return XCTFail("timed out waiting for \(what)") }
            try await Task.sleep(for: .milliseconds(10))
        }
    }

    /// One press, one decision: a second press while one is out sends nothing. When it is in, focus moves to the next
    /// request to decide first, and only then does the row take its new status.
    func testOneDecisionAtATimeAndFocusMovesFirst() async throws {
        var decided: [Int32] = []
        var outcomes: [Decided] = []
        let model = ApprovalsModel(source: ApprovalsSource(
            me: { self.approver },
            page: { _, _, _ in self.page([1, 2, 3]) },
            title: { _, id in RequestTitle(title: "Title \(id)", year: "2026", poster: nil, backdrop: nil) },
            decide: { id, _ in
                decided.append(id)
                try await Task.sleep(for: .milliseconds(200))
                return self.page([Int(id)]).results[0]
            }
        ), onDecided: { outcomes.append($0) })
        model.open()
        try await waitFor("the rows and the rights") { model.rows.count == 3 && model.approver != nil }
        XCTAssertTrue(model.decisions(model.rows[0]))
        model.decide(1, approve: true)
        model.decide(1, approve: true)
        model.decide(2, approve: false)
        try await waitFor("the decision") { model.focusAfter != nil }
        XCTAssertEqual(decided, [1], "one decision")
        XCTAssertEqual(model.focusAfter, FocusAfter.row(key: "request:2"), "focus to the next request to decide")
        XCTAssertTrue(model.decisions(model.rows[0]), "the row unchanged until focus has moved")
        model.focusMoved()
        XCTAssertFalse(model.decisions(model.rows[0]), "now decided: no buttons")
        XCTAssertEqual(model.rows[0].status, 2, "approved")
        XCTAssertEqual(outcomes, [.done(title: "Title 1001", approved: true)])
    }

    /// A decision that does not go through is the queue's alert (Tom's D4: no toast), said for the request it was for;
    /// the row keeps its status and stays to decide.
    func testADecisionThatFailsIsTheQueuesAlert() async throws {
        let model = ApprovalsModel(source: ApprovalsSource(
            me: { self.approver },
            page: { _, _, _ in self.page([1, 2]) },
            title: { _, id in RequestTitle(title: "Title \(id)", year: nil, poster: nil, backdrop: nil) },
            decide: { _, _ in throw CancellationError() }
        ))
        model.open()
        try await waitFor("the rows and the rights") { model.rows.count == 2 && model.approver != nil }
        model.decide(2, approve: false)
        try await waitFor("the outcome") { model.problem != nil }
        XCTAssertEqual(model.problem?.title, "Request Not Declined")
        XCTAssertEqual(model.problem?.message, "The request could not be changed. Try again.")
        XCTAssertEqual(model.problemRow, "request:2", "focus goes back to the request it was for")
        XCTAssertNil(model.focusAfter, "focus does not move on")
        XCTAssertTrue(model.decisions(model.rows[1]), "still to decide")
        model.problemSeen()
        XCTAssertNil(model.problem)
        let gone = ApprovalsModel.problem(.notPending(title: "Coco"), approve: true)
        XCTAssertEqual(gone?.title, "Request Not Approved")
        XCTAssertEqual(gone?.message, "Coco is no longer pending: someone has decided it.")
    }

    /// The queue is read once per opening: the page coming back (Menu from a title opened from a row) keeps its list.
    func testComingBackKeepsTheList() async throws {
        var reads = 0
        let model = ApprovalsModel(source: ApprovalsSource(
            me: { self.approver },
            page: { _, _, _ in
                reads += 1
                return self.page([1, 2])
            },
            title: { _, id in RequestTitle(title: "Title \(id)", year: nil, poster: nil, backdrop: nil) },
            decide: { _, _ in throw CancellationError() }
        ))
        model.open()
        try await waitFor("the rows") { model.rows.count == 2 }
        model.open()
        XCTAssertEqual(model.rows.count, 2, "the rows stay")
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertEqual(reads, 1, "read once")
    }

    /// Without Manage Requests, read live, no row offers a decision.
    func testNoButtonsWithoutTheRight() async throws {
        let plain = User(id: 9, displayName: "Approver", email: nil, username: nil, permissions: KotlinInt(value: 32), userType: nil,
                         avatar: nil, requestCount: nil, movieQuotaLimit: nil, movieQuotaDays: nil, tvQuotaLimit: nil, tvQuotaDays: nil, settings: nil)
        let model = ApprovalsModel(source: ApprovalsSource(
            me: { plain }, page: { _, _, _ in self.page([1]) },
            title: { _, id in RequestTitle(title: "Title \(id)", year: nil, poster: nil, backdrop: nil) },
            decide: { _, _ in throw CancellationError() }
        ))
        model.open()
        try await waitFor("the rows and the rights") { model.rows.count == 1 && model.approver != nil }
        XCTAssertFalse(model.mayDecide)
        XCTAssertFalse(model.decisions(model.rows[0]))
    }
}
