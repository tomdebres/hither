import Foundation
import HitherCore
import Observation

/// The alerts in Hither (M4, DECISIONS 79, 146; the tab's own symbol on tvOS, 184): the notices for each change to the
/// user's own requests, and the count on the Requests tab. The same source and rules as Android's `HomeAlerts`: coming
/// to the front, a look if one is due (`ReadyLook`), then each change not yet shown (core's `LastSeen.unshown`) as a
/// notice in the banner, taken so it is shown once, and the count of changes since My Requests was last opened
/// (`LastSeen.requestsCount`). Opening My Requests starts the count again.
@MainActor
@Observable
final class RequestAlerts {
    private(set) var count = 0
    /// "N waiting for approval" as the look read it, for an account whose live rights hold Manage Requests (DECISIONS 79);
    /// nil without the right.
    private(set) var waiting: Int?
    /// The same count read with the family's own session while an admin is kept on the TV (DECISIONS 94): Seerr's route
    /// asks no right, so the admin is never used for it. Nil with none kept.
    private(set) var adminWaiting: Int?

    /// What the Requests page says waits: the look's count, else the one read for the admin; nil for nothing to say.
    var waitingShown: Int? { (waiting ?? adminWaiting).flatMap { $0 > 0 ? $0 : nil } }

    /// How long Requests' page stays before it counts as opened (DECISIONS 184).
    static let openedAfter: Duration = .seconds(2)

    @ObservationIgnored private let session: Session
    @ObservationIgnored private let gateway: SessionGateway
    @ObservationIgnored private let notices: Notices
    @ObservationIgnored let ready: ReadyLook
    /// How many times My Requests has been opened: a count read before the latest opening is stale (Android's `opens`).
    @ObservationIgnored private var opens = 0
    @ObservationIgnored private var looking = false
    /// My Requests was opened while a look was out: its changes go once the look has saved (Android holds its lock).
    @ObservationIgnored private var openedDuringLook = false
    /// Seerr's pending count, read with the family's session (a test holds it open).
    @ObservationIgnored private let readPending: () async throws -> Int
    /// Counts the calls to [readAdminWaiting]: a read whose number is no longer the latest is stale.
    @ObservationIgnored private var adminReads = 0

    init(
        session: Session,
        gateway: SessionGateway,
        notices: Notices,
        ready: ReadyLook? = nil,
        pendingCount: (() async throws -> Int)? = nil
    ) {
        self.session = session
        self.gateway = gateway
        self.notices = notices
        readPending = pendingCount ?? { Int(try await gateway.call { try await $0.requestCount() }.pending) }
        let ready = ready ?? ReadyLook()
        self.ready = ready
        let kept = ready.store.kept()
        if kept?.snapshot.account == account {
            count = Int(kept?.requestsCount ?? 0)
            waiting = ready.store.waiting
        }
    }

    private var account: String { LastSeenKt.accountKey(baseUrl: session.baseUrl, userId: session.user.id) }

    /// Hither is at the front (arriving, or coming back): a look if one is due, then the changes not yet shown (each a
    /// notice, taken), and the count as kept.
    func front() {
        guard !looking else { return }
        looking = true
        let asked = opens
        Task {
            await ready.look(baseUrl: session.baseUrl, userId: session.user.id, gateway: gateway, background: false)
            looking = false
            if openedDuringLook {
                openedDuringLook = false
                ready.store.forgetUnopened()
            }
            await notify(ready.takeUnshown(account: account))
            let kept = ready.store.kept()
            let same = kept?.snapshot.account == account
            waiting = same ? ready.store.waiting : nil
            guard asked == opens else { return }
            count = same ? Int(kept?.requestsCount ?? 0) : 0
        }
    }

    /// The pending count for the admin's way in, read with the family's session while an admin is kept ([kept]); a
    /// failed read keeps the last (Android's `adminWaiting`). Read on arriving, coming back to the front, a change to the
    /// kept admin, and after each decision.
    /// Only the latest call's read applies its count: a read still out when the admin is removed, or overtaken by a newer
    /// read, sets nothing when it answers (#65's review Nit). Returns the read, for a test to wait on.
    @discardableResult
    func readAdminWaiting(kept: Bool) -> Task<Void, Never>? {
        adminReads += 1
        let mine = adminReads
        guard kept else {
            adminWaiting = nil
            return nil
        }
        return Task {
            guard let counted = try? await readPending(), mine == adminReads else { return }
            adminWaiting = counted
        }
    }

    /// A decision made on the TV (DECISIONS 94): the count drops by one at once, before it is read again.
    func decided(adminKept: Bool) {
        waiting = waiting.map { max(0, $0 - 1) }
        adminWaiting = adminWaiting.map { max(0, $0 - 1) }
        readAdminWaiting(kept: adminKept)
    }

    /// My Requests has been opened: the count goes, here and on the device.
    func opened() {
        opens += 1
        count = 0
        if looking { openedDuringLook = true } else { ready.store.forgetUnopened() }
    }

    /// Each change as a notice, oldest first, with the title's name where it is known (the look keeps the names of the
    /// changes it found; one it could not read is asked for once more here).
    private func notify(_ events: [RequestEvent]) async {
        guard !events.isEmpty else { return }
        let missing = events.compactMap { event in
            ready.art.get(event.titleKey) == nil ? event.type.map { TitleNames.Wanted(key: event.titleKey, type: $0, tmdbId: event.tmdbId) } : nil
        }
        if !missing.isEmpty {
            let wanted = events.compactMap { event in event.type.map { TitleNames.Wanted(key: event.titleKey, type: $0, tmdbId: event.tmdbId) } }
            await ready.art.want(wanted + readyKept(), gateway: gateway, baseUrl: session.baseUrl)
        }
        for event in events {
            notices.notice(event.change, title: ready.art.get(event.titleKey)?.title)
        }
    }

    /// The Top Shelf's titles, whose names stay kept when the notices' are read.
    private func readyKept() -> [TitleNames.Wanted] {
        guard let folder = ShelfFile.folder else { return [] }
        return ShelfFile.read(from: folder).compactMap { item in
            guard let link = HomeRowsKt.parseTitleLink(uri: item.link) else { return nil }
            return TitleNames.Wanted(key: link.key, type: link.type, tmdbId: link.tmdbId)
        }
    }
}

/// Where the look's `LastSeen` and the last look's time are kept: the app's defaults, in core's own JSON
/// (`lastSeenJson`, lenient, so a later build still reads it). A few kilobytes, inside the 500 KB tvOS keeps for an app;
/// if tvOS purges it, the next look is a first look, which announces nothing (core's rule).
final class DeviceLastSeen: NSObject, LastSeenStore, @unchecked Sendable {
    private let defaults: UserDefaults
    private static let lastSeenKey = "heerr.lastSeen"
    private static let lastLookKey = "heerr.lastLookAt"
    private static let waitingKey = "heerr.waiting"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var lastLookAt: Int64? {
        get { (defaults.object(forKey: Self.lastLookKey) as? NSNumber)?.int64Value }
        set { defaults.set(newValue.map { NSNumber(value: $0) }, forKey: Self.lastLookKey) }
    }

    /// Seerr's pending count as the last look read it, for an account with Manage Requests; nil without the right.
    var waiting: Int? {
        get { (defaults.object(forKey: Self.waitingKey) as? NSNumber)?.intValue }
        set { defaults.set(newValue.map { NSNumber(value: $0) }, forKey: Self.waitingKey) }
    }

    func kept() -> LastSeen? {
        guard let text = defaults.string(forKey: Self.lastSeenKey) else { return nil }
        return LastSeenKt.lastSeenJson.decodeFromString(deserializer: LastSeen.companion.serializer(), string: text) as? LastSeen
    }

    /// My Requests opened: the changes since go, the snapshot and the notices stay (Android's `requestsOpened`).
    func forgetUnopened() {
        guard let kept = kept() else { return }
        write(kept.doCopy(snapshot: kept.snapshot, unshown: kept.unshown, unopened: []))
    }

    /// Everything of the last account's (sign-out).
    func forget() {
        defaults.removeObject(forKey: Self.lastSeenKey)
        defaults.removeObject(forKey: Self.lastLookKey)
        defaults.removeObject(forKey: Self.waitingKey)
    }

    func write(_ lastSeen: LastSeen) {
        defaults.set(LastSeenKt.lastSeenJson.encodeToString(serializer: LastSeen.companion.serializer(), value: lastSeen), forKey: Self.lastSeenKey)
    }

    // SKIE's names for the store's `load` and `save`, which core's `LastSeenWatch` calls.
    func __load() async throws -> LastSeen? { kept() }

    func __save(lastSeen: LastSeen) async throws { write(lastSeen) }
}
