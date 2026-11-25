import BackgroundTasks
import Foundation
import HitherCore
import TVServices

/// One look at the user's requests (Android's `ReadyLook`, DECISIONS 75, 76): core's `LastSeenWatch` reads the user's
/// own requests (`GET /request`, `requestedBy`, the 50 most recently modified) against the snapshot kept on the device,
/// keeps the changes not yet shown and those since My Requests was opened, and gives the titles that are ready to
/// watch, which go to the Top Shelf (`ReadyShelf`, standing in for Android's channel and Watch Next). The names of
/// those titles and of the changes still to show are read once and kept (`TitleArtStore`). Made on arriving, on coming
/// back to the front, and in the background when tvOS runs Hither's refresh, no more often than core's `lookDue`
/// allows. A failed look changes nothing.
@MainActor
final class ReadyLook {
    let store: DeviceLastSeen
    let art: TitleArtStore
    let shelf: ReadyShelf
    init(defaults: UserDefaults = .standard) {
        store = DeviceLastSeen(defaults: defaults)
        art = TitleArtStore(defaults: defaults)
        shelf = ReadyShelf()
    }

    /// One look, if one is due ([background]: tvOS's refresh; otherwise Hither arriving or coming to the front). Whether
    /// a look was made and answered.
    @discardableResult
    func look(baseUrl: String, userId: Int32, gateway: SessionGateway, background: Bool) async -> Bool {
        await look(baseUrl: baseUrl, userId: userId, background: background, server: .live(gateway, userId: userId))
    }

    /// One look against [server] (the signed-in server, or a test's script). A look that began before a sign-out keeps
    /// nothing: the snapshot core saves, the names, the waiting count and the Top Shelf are written only while
    /// [LookEpoch] is the one the look began in (the review's Nit on #64).
    @discardableResult
    func look(baseUrl: String, userId: Int32, background: Bool, server: LookServer) async -> Bool {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var due = HomeRowsKt.lookDue(lastLookMs: store.lastLookAt.map { KotlinLong(value: $0) }, nowMs: now, background: background)
        #if DEBUG
        // The UI tests relaunch within core's minute (`HITHER_LOOK_DUE=always`): every arrival looks. Debug builds only.
        if !background, ProcessInfo.processInfo.environment["HITHER_LOOK_DUE"] == "always" { due = true }
        #endif
        guard due else { return false }
        let epoch = LookEpoch.current
        store.lastLookAt = now
        // The account's rights, read live with the look (Android's `source.me()`): "N waiting for approval" only with
        // Manage Requests (DECISIONS 79).
        guard let user = try? await server.me() else { return false }
        let account = LastSeenKt.accountKey(baseUrl: baseUrl, userId: userId)
        let watch = LastSeenWatch(store: EpochStore(base: store, epoch: epoch))
        guard let since = try? await watch.look(account: account, userId: userId, read: server.requests), LookEpoch.current == epoch else {
            return false
        }
        if HomeRowsKt.seesWaiting(user: user) {
            // A failed count keeps the last one.
            if let count = try? await server.count() { LookEpoch.ifCurrent(epoch) { store.waiting = Int(count.pending) } }
        } else {
            LookEpoch.ifCurrent(epoch) { store.waiting = nil }
        }
        // The Top Shelf's titles, and those of every change still waiting to be shown (this look's and earlier ones').
        let unshown = store.kept()?.unshown ?? []
        let wanted = since.ready.map { TitleNames.Wanted(key: $0.key, type: $0.type, tmdbId: $0.tmdbId) }
            + unshown.compactMap { event in event.type.map { TitleNames.Wanted(key: event.titleKey, type: $0, tmdbId: event.tmdbId) } }
        await art.want(wanted, epoch: epoch, images: await server.images(), fetch: server.art)
        await shelf.publish(since.ready, art: art, epoch: epoch)
        return LookEpoch.current == epoch
    }

    /// The changes not yet shown, for [account]'s requests, taken: each is shown once (Android's `ReadyLook.front`).
    func takeUnshown(account: String) -> [RequestEvent] {
        guard let kept = store.kept(), kept.snapshot.account == account, !kept.unshown.isEmpty else { return [] }
        store.write(kept.doCopy(snapshot: kept.snapshot, unshown: [], unopened: kept.unopened))
        return kept.unshown
    }

    /// Signed out, or the account gone: nothing of the last one stays, here or on the Top Shelf; and a look still out
    /// for it keeps nothing when it comes back ([LookEpoch]).
    static func forget(defaults: UserDefaults = .standard) {
        LookEpoch.bump {
            DeviceLastSeen(defaults: defaults).forget()
            TitleArtStore(defaults: defaults).forget()
            ReadyShelf().clear()
        }
    }
}

/// Counts the forgets (sign-out, a refused password, the debug reset). A look notes the count it began in and writes
/// nothing once it has moved on: a look still out at sign-out wrote the old account's snapshot, names and Top Shelf back
/// after the forget (the review's Nit on #64). The forget and each write hold the one lock, so none lands between.
enum LookEpoch {
    private static let lock = NSRecursiveLock()
    nonisolated(unsafe) private static var value = 0

    static var current: Int { lock.withLock { value } }

    /// A forget: the count moves on, then [clear] runs, both under the lock.
    static func bump(_ clear: () -> Void) {
        lock.withLock {
            value += 1
            clear()
        }
    }

    /// Runs [write] only while the count is still [epoch]; whether it ran.
    @discardableResult
    static func ifCurrent(_ epoch: Int, _ write: () throws -> Void) rethrows -> Bool {
        try lock.withLock {
            guard value == epoch else { return false }
            try write()
            return true
        }
    }
}

/// The device's `LastSeen` as one look sees it: core's watch saves through it, and a save after a forget is refused, so
/// core's look gives nothing and keeps nothing (its store "will not save").
final class EpochStore: NSObject, LastSeenStore, @unchecked Sendable {
    private let base: DeviceLastSeen
    private let epoch: Int

    struct Forgotten: Error {}

    init(base: DeviceLastSeen, epoch: Int) {
        self.base = base
        self.epoch = epoch
    }

    func __load() async throws -> LastSeen? { base.kept() }

    func __save(lastSeen: LastSeen) async throws {
        guard LookEpoch.ifCurrent(epoch, { base.write(lastSeen) }) else { throw Forgotten() }
    }
}

/// What a look asks of the server (the signed-in one through the family's gateway, or a test's script).
struct LookServer {
    /// The account's rights, read live.
    var me: () async throws -> User
    /// The user's own requests as one look reads them, for core's `LastSeenWatch`.
    var requests: KotlinSuspendFunction0
    /// Seerr's request counts, everyone's (`GET /request/count`).
    var count: () async throws -> RequestCount
    /// A title's name, year and poster.
    var art: (TitleNames.Wanted) async throws -> ShelfArt
    /// Where the server's pictures come from now (DECISIONS 382); nil where it is not known. A title whose kept poster
    /// came from elsewhere is asked for again.
    var images: () async -> ImageSource? = { nil }

    @MainActor
    static func live(_ gateway: SessionGateway, userId: Int32) -> LookServer {
        LookServer(
            me: { try await gateway.call { try await $0.me() } },
            requests: RequestsReader(gateway: gateway, userId: userId),
            count: { try await gateway.call { try await $0.requestCount() } },
            art: { title in try await TitleArtStore.fetch(title, gateway: gateway) },
            images: { gateway.client?.images }
        )
    }
}

/// The user's own requests as one look reads them (Android's `ReadySource.requests`): the 50 most recently modified.
final class RequestsReader: KotlinSuspendFunction0 {
    private let gateway: SessionGateway
    private let userId: Int32

    init(gateway: SessionGateway, userId: Int32) {
        self.gateway = gateway
        self.userId = userId
    }

    /// SKIE's name for Kotlin's `invoke`, which core's `LastSeenWatch` calls as the look's read.
    func __invoke() async throws -> Any? {
        let userId = userId
        let page = try await gateway.call { client in
            try await client.requests(filter: "all", take: LastSeenKt.LOOK_TAKE, skip: 0, requestedBy: KotlinInt(value: userId), sort: "modified")
        }
        return page.results
    }
}

/// What a notice, the Top Shelf and a link's stand-in card show of a title: its name, year and poster (Android's
/// `TitleArt`), kept so each title is asked for once.
struct ShelfArt: Codable, Equatable {
    let title: String
    let year: String?
    let poster: String?
}

/// The titles' names and posters a look keeps, in the app's defaults: only those the Top Shelf and the notices still
/// name (Android's `artFor`). A title that will not load is left out until a later look gets it.
@MainActor
final class TitleArtStore {
    private let defaults: UserDefaults
    private static let key = "heerr.titleArt"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func get(_ key: String) -> ShelfArt? { all()[key] }

    /// Asks for each of [wanted] not yet kept, or kept with a poster from elsewhere than the server's pictures come from
    /// now, and keeps only [wanted].
    func want(_ wanted: [TitleNames.Wanted], gateway: SessionGateway) async {
        await want(wanted, epoch: LookEpoch.current, images: gateway.client?.images) { try await Self.fetch($0, gateway: gateway) }
    }

    /// The same with [fetch] for each title's names; kept only while [epoch] is current (nothing after a sign-out). A kept
    /// title whose poster did not come from [images] (the server's owner turned caching on or off, DECISIONS 382) is asked
    /// for again, and keeps what it had if that fails.
    func want(_ wanted: [TitleNames.Wanted], epoch: Int, images: ImageSource? = nil, fetch: (TitleNames.Wanted) async throws -> ShelfArt) async {
        var kept = all()
        for title in wanted where Self.wanted(kept[title.key], images) {
            guard let art = try? await fetch(title) else { continue }
            kept[title.key] = art
        }
        let keys = Set(wanted.map(\.key))
        LookEpoch.ifCurrent(epoch) { save(kept.filter { keys.contains($0.key) }) }
    }

    /// Whether a title kept as [art] is to be asked for: not kept yet, or its poster not from [images].
    private static func wanted(_ art: ShelfArt?, _ images: ImageSource?) -> Bool {
        guard let art else { return true }
        guard let images else { return false }
        return !images.made(url: art.poster)
    }

    /// A title's names through the family's gateway, which renews an ended session; its poster from where the server's
    /// pictures come from (DECISIONS 382).
    static func fetch(_ title: TitleNames.Wanted, gateway: SessionGateway) async throws -> ShelfArt {
        let tmdbId = title.tmdbId
        let type = title.type
        return try await gateway.call { client -> ShelfArt in
            if type == .movie {
                let found = HomeRowsKt.titleArt(details: try await client.movie(tmdbId: tmdbId), images: client.images)
                return ShelfArt(title: found.title, year: found.year, poster: found.poster)
            }
            let found = HomeRowsKt.titleArt(details: try await client.tv(tmdbId: tmdbId), images: client.images)
            return ShelfArt(title: found.title, year: found.year, poster: found.poster)
        }
    }

    func forget() {
        defaults.removeObject(forKey: Self.key)
    }

    private func all() -> [String: ShelfArt] {
        guard let data = defaults.data(forKey: Self.key) else { return [:] }
        return (try? JSONDecoder().decode([String: ShelfArt].self, from: data)) ?? [:]
    }

    private func save(_ art: [String: ShelfArt]) {
        defaults.set(try? JSONEncoder().encode(art), forKey: Self.key)
    }
}

/// The Top Shelf's titles (standing in for Android's "Ready to Watch" channel and Watch Next, DECISIONS 76): after each
/// look, the ready titles (newest first, at most 10, core's `readyTitles`) with their names, their posters fetched once
/// into the folder Hither shares with its Top Shelf extension (the App Group), and the `hither://title/<type>/<id>` link
/// each opens. tvOS is told when they change. Nothing of the session goes there: the extension reads this file only.
@MainActor
final class ReadyShelf {
    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.timeoutIntervalForRequest = 20
        return URLSession(configuration: configuration)
    }()

    /// Writes the shelf for [ready]; nothing once [epoch] is no longer current (a sign-out while the look was out).
    func publish(_ ready: [ReadyTitle], art: TitleArtStore, epoch: Int = LookEpoch.current) async {
        guard let folder = ShelfFile.folder, LookEpoch.current == epoch else { return }
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var items: [ShelfItem] = []
        for title in ready {
            guard let names = art.get(title.key) else { continue }
            let file = "\(title.type.api)-\(title.tmdbId).jpg"
            let path = folder.appendingPathComponent(file)
            if !FileManager.default.fileExists(atPath: path.path), let poster = names.poster, let url = URL(string: poster),
               let (data, response) = try? await Self.session.data(from: url),
               (response as? HTTPURLResponse)?.statusCode == 200 {
                LookEpoch.ifCurrent(epoch) { try? data.write(to: path, options: .atomic) }
            }
            let image = FileManager.default.fileExists(atPath: path.path) ? file : nil
            items.append(ShelfItem(key: title.key, title: names.title, link: TitleLink(type: title.type, tmdbId: title.tmdbId).uri, image: image))
        }
        // Pictures of titles no longer on the shelf go.
        let kept = Set(items.compactMap(\.image))
        for name in (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? [] where name.hasSuffix(".jpg") && !kept.contains(name) {
            try? FileManager.default.removeItem(at: folder.appendingPathComponent(name))
        }
        guard items != ShelfFile.read(from: folder) else { return }
        let written = LookEpoch.ifCurrent(epoch) {
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try? ShelfFile.write(items, to: folder)
        }
        if written { TVTopShelfContentProvider.topShelfContentDidChange() }
    }

    /// Everything of Hither's leaves the Top Shelf.
    func clear() {
        guard let folder = ShelfFile.folder else { return }
        try? FileManager.default.removeItem(at: folder)
        TVTopShelfContentProvider.topShelfContentDidChange()
    }
}

/// tvOS's background refresh, Android's `ReadyWorker` (every 30 minutes, DECISIONS 76) as tvOS allows it: asked for no
/// sooner than 30 minutes ahead whenever Hither leaves the front, and again after each run; tvOS decides when, or
/// whether, it runs. A run restores the session as a launch does and makes one look if one is due (core's background
/// gap); the changes it finds wait to be shown when Hither is next at the front, and the Top Shelf takes the ready titles.
enum BackgroundLook {
    /// Also in Info.plist's `BGTaskSchedulerPermittedIdentifiers`.
    static let id = "com.tomdebres.heerr.look"

    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: id)
        request.earliestBeginDate = Date(timeIntervalSinceNow: TimeInterval(HomeRowsKt.BACKGROUND_LOOK_MINUTES * 60))
        try? BGTaskScheduler.shared.submit(request)
    }

    @MainActor
    static func run() async {
        schedule()
        let gateway = SessionGateway()
        guard case .signedIn(let session) = await gateway.restore() else { return }
        await ReadyLook().look(baseUrl: session.baseUrl, userId: session.user.id, gateway: gateway, background: true)
    }
}
