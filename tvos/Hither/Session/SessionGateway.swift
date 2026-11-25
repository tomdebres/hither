import Foundation
import HitherCore

/// A signed-in user on a server that has answered.
struct Session {
    let user: User
    let settings: PublicSettings
    let baseUrl: String
    /// Where the server's pictures come from (DECISIONS 382): its `/imageproxy` while its `cacheImages` is on, TMDB's image
    /// server while it is off, as the client read the switch at this sign-in or start. A test's session without one gets
    /// core's `unknown`, the server's own proxy.
    let images: ImageSource

    init(user: User, settings: PublicSettings, baseUrl: String, images: ImageSource? = nil) {
        self.user = user
        self.settings = settings
        self.baseUrl = baseUrl
        self.images = images ?? ImageSource.companion.unknown(serverBase: baseUrl)
    }
}

/// What starting the app with a stored account came to (Android's `Restored`).
enum Restored {
    /// First run, or signed out.
    case noAccount
    case signedIn(Session)
    /// The account is kept; the server did not answer just now. Its address, to name on the screen.
    case serverUnavailable(String?)
    /// The account is kept; the server's CSRF protection refuses signing in again over http (DECISIONS 70).
    case csrfNeedsHttps
    /// The server no longer takes the stored sign-in; the account has been cleared. [server] is its address where the
    /// form should offer it again: a session Hither could not renew by itself (Plex, Quick Connect: `sessionEnded`).
    case rejected(SignInFailure, server: String? = nil)
}

/// What a server offers for signing in (M8), read before any sign-in: its address as called, its title, the ways in
/// (core's `SignInMethods`: only those are offered) and, for Quick Connect, where its Jellyfin is for people (or nil).
struct ServerOffer {
    let baseUrl: String
    let title: String
    let methods: SignInMethods
    let approveAt: String?
}

/// A code on screen (M8), and what the gateway waits on with it: [code] is all a screen sees; Quick Connect's secret and
/// plex.tv's PIN stay inside core's `SignInCode`.
struct PendingCode {
    let code: String
    let inner: SignInCode
}

/// A sign-in that failed, with the server's title when it got far enough to learn it.
struct SignInFailed: Error {
    let failure: SignInFailure
    let serverTitle: String?
}

/// Reading what core threw: SKIE hands a Kotlin exception to Swift as an `NSError` carrying it.
enum Kotlin {
    static func throwable(_ error: Error) -> KotlinThrowable? {
        (error as NSError).kotlinException as? KotlinThrowable
    }

    /// A 401, or Seerr's own 403 for a cookie it no longer knows: refused before anything was done.
    static func isSignedOut(_ error: Error) -> Bool { throwable(error) is SeerrException.SignedOut }

    static func isRefused(_ error: Error) -> Bool { throwable(error) is SeerrException.Refused }

    /// Seerr's own word about the account when signing it in again (M8, DECISIONS 237): its password refused, an account
    /// it no longer imports, or the way in switched off. Only these forget a kept account.
    static func endsAccount(_ error: Error) -> Bool {
        let thrown = throwable(error)
        return thrown is SeerrException.Refused || thrown is SeerrException.NotImported || thrown is SeerrException.SignInOff
    }

    /// A Swift task's cancellation, as Swift or as Kotlin's `CancellationException` through SKIE.
    static func isCancellation(_ error: Error) -> Bool {
        error is CancellationError || throwable(error) is KotlinCancellationException
    }
}

/// The session, as Android's `SeerrSessionGateway` keeps it: one `SeerrClient` for the one server, its cookies in the
/// Keychain (core's `keychainCookies`), the account beside them (`AccountStore`). Sign-in asks `settings/public`
/// first (reachable? this way in on? the title), then the way in (`auth/local`, and since M8 `/auth/jellyfin`, Quick
/// Connect or `/auth/plex`), then `auth/me`, and stores the account only on success. Screens call the server through
/// [call], which signs in again once with the stored account when the session has ended and repeats the call once;
/// nothing else is repeated. A Plex or Quick Connect account keeps nothing to sign in with, so its session's end is the
/// account's (DECISIONS 235).
///
/// plex.tv is called from [newPlexCode] and [signInWithPlex] only, on a `PlexTv` made for each and closed after: only
/// while a Plex sign-in is on screen.
@MainActor
final class SessionGateway {
    private let store: AccountStore
    private(set) var client: SeerrClient?
    private var cookies: SessionCookies?
    /// Counts the sessions [client] has held, so calls that found the same one over renew it once.
    private var session = 0
    private var renewal: Task<SeerrClient, Error>?

    /// The stored account refused while signed in, or (Plex, Quick Connect) a session Hither cannot renew ended: the
    /// account has been cleared, the app goes to the form. The server's address comes with `sessionEnded`, for the
    /// form to offer it again.
    var onLost: ((SignInFailure, String?) -> Void)?

    private let identity: PlexIdentity
    /// Where plex.tv is: `PlexAddress` (plex.tv itself in a release build); a test names the fake plex.tv.
    private let plexAddress: () -> String
    /// Where TMDB's image server is, for a server that keeps no copies of its pictures (DECISIONS 382): `TmdbAddress`
    /// (TMDB itself in a release build); a test names fake-seerr's stand-in. Read each time a client is made.
    private let tmdbImages: () -> String

    init(
        store: AccountStore = AccountStore(), identity: PlexIdentity = PlexIdentity(),
        plexAddress: @escaping () -> String = { PlexAddress.base }, tmdbImages: @escaping () -> String = { TmdbAddress.base }
    ) {
        self.store = store
        self.identity = identity
        self.plexAddress = plexAddress
        self.tmdbImages = tmdbImages
    }

    struct NotSignedIn: Error {}

    // MARK: - Calls

    func call<T>(_ body: (SeerrClient) async throws -> T) async throws -> T {
        guard let seerr = client else { throw NotSignedIn() }
        let number = session
        do {
            return try await body(seerr)
        } catch where Kotlin.isSignedOut(error) {
            // Refused before any route ran, so nothing was done: renew the session and ask once more.
        }
        return try await body(try await renew(seerr, number))
    }

    /// Signs in again with the stored account, once for all the calls that found session [number] over together.
    private func renew(_ seen: SeerrClient, _ number: Int) async throws -> SeerrClient {
        if let renewal { return try await renewal.value }
        guard let current = client else { throw NotSignedIn() }
        if current !== seen || session != number { return current }
        let task = Task { @MainActor () throws -> SeerrClient in
            guard let account = try self.store.load() else { throw NotSignedIn() }
            do {
                _ = try await self.signInAgain(current, account)
            } catch let gone as AccountGone {
                // The password changed or the account went (or, M8, a session Hither cannot renew ended): only Seerr's
                // word about the account forgets it.
                self.drop()
                try? self.store.clear()
                self.onLost?(gone.failure, gone.failure == .sessionEnded ? account.baseUrl : nil)
                throw gone
            }
            self.session += 1
            return current
        }
        renewal = task
        defer { renewal = nil }
        return try await task.value
    }

    /// The stored account will not sign in again: Seerr refused it, or (Plex, Quick Connect) there is nothing to sign in
    /// with.
    struct AccountGone: Error {
        let failure: SignInFailure
    }

    /// Signs [account] in again on [seerr] when the session has ended, as it first signed in (Android's `signInAgain`).
    /// Only Seerr's own word about the account forgets it ([AccountGone]): its password refused (any way in), and since
    /// M8 a media-server account it no longer imports or a way in switched off. A Plex or Quick Connect account has
    /// nothing kept to sign in with, so its session's end is the account's. Anything else is thrown as it came.
    private func signInAgain(_ seerr: SeerrClient, _ account: Account) async throws -> User {
        guard account.method.renews else { throw AccountGone(failure: .sessionEnded) }
        do {
            if account.method == .mediaServer {
                return try await seerr.signInWithMediaServer(username: account.email, password: account.password)
            }
            return try await seerr.signIn(email: account.email, password: account.password)
        } catch where Kotlin.endsAccount(error) {
            throw AccountGone(failure: Kotlin.throwable(error).map { signInFailure(error: $0) } ?? .other)
        }
    }

    // MARK: - Starting, signing in and out

    func storedServer() -> String? { (try? store.load())?.baseUrl }
    /// How the stored account signed in (Seerr's password for a record from before M8); nil with none stored.
    func storedMethod() -> AccountMethod? { (try? store.load())?.method }

    func restore() async -> Restored {
        let account: Account?
        do {
            account = try store.load()
        } catch {
            return .serverUnavailable(nil)
        }
        guard let account else { return .noAccount }
        let seerr = open(account.baseUrl)
        do {
            let settings = try await seerr.publicSettings()
            let user: User
            do {
                user = try await seerr.me()
            } catch where Kotlin.isSignedOut(error) {
                // The session ended since last time (a 401), or the server forgot it (Seerr's own 403).
                do {
                    user = try await signInAgain(seerr, account)
                } catch let gone as AccountGone {
                    // Only Seerr's own refusal is about the account; a session Hither could not renew (Plex, Quick
                    // Connect) offers its server again in the form.
                    drop()
                    try? store.clear()
                    return .rejected(gone.failure, server: gone.failure == .sessionEnded ? account.baseUrl : nil)
                }
            }
            return .signedIn(Session(user: user, settings: settings, baseUrl: account.baseUrl, images: seerr.images))
        } catch {
            if let csrf = Kotlin.throwable(error) as? SeerrException.CsrfRefused, csrf.overHttp { return .csrfNeedsHttps }
            // Not answering as expected: the account is kept, the screen offers Try Again and Sign Out.
            return .serverUnavailable(account.baseUrl)
        }
    }

    func signIn(_ request: SignInRequest) async throws -> Session {
        try await signIn(request.baseUrl, Account(baseUrl: request.baseUrl, email: request.email, password: request.password), allowed: Self.localOff) {
            try await $0.signIn(email: request.email, password: request.password)
        }
    }

    /// One sign-in, whichever the way in (Android's `signInLocked`): the account and its session cleared first, the
    /// server's settings read (is this way in on?), [call] signs in on a new client, and [account] is stored only once it
    /// has. Whatever goes wrong, nothing half-stored is left, and the screen gets a [SignInFailed].
    private func signIn(
        _ baseUrl: String, _ account: Account, allowed: (PublicSettings) -> SignInFailure?, _ call: (SeerrClient) async throws -> User
    ) async throws -> Session {
        var title: String?
        do {
            try store.clear()
            let seerr = open(baseUrl)
            // A session kept for this address from before is not this sign-in's.
            try await cookies?.forget()
            let settings = try await seerr.publicSettings()
            title = settings.applicationTitle
            if let off = allowed(settings) { throw SignInFailed(failure: off, serverTitle: title) }
            let user = try await call(seerr)
            try store.save(account)
            return Session(user: user, settings: settings, baseUrl: baseUrl, images: seerr.images)
        } catch {
            // Whatever went wrong, nothing half-stored is left.
            drop()
            try? store.clear()
            if let failed = error as? SignInFailed { throw failed }
            if Kotlin.isCancellation(error) { throw CancellationError() }
            throw SignInFailed(failure: Kotlin.throwable(error).map { signInFailure(error: $0) } ?? .other, serverTitle: title)
        }
    }

    private static func localOff(_ settings: PublicSettings) -> SignInFailure? { settings.localLogin ? nil : .localLoginDisabled }
    private static func mediaServerOff(_ settings: PublicSettings) -> SignInFailure? { settings.mediaServerLogin ? nil : .methodOff }

    // MARK: - M8: the ways in a server offers, and those through its media server

    // Each throws [SignInFailed] and nothing else (cancellation apart); each sign-in stores the account only on success.

    /// A client of its own for reading a server before signing in, or for Quick Connect's waiting: no session kept, no
    /// store, nothing of the gateway's.
    private func probe(_ baseUrl: String) -> SeerrClient {
        DarwinKt.darwinSeerrClient(
            baseUrl: baseUrl, cookies: SessionCookies(baseUrl: baseUrl, vault: MemoryVault()), failures: Diagnostics.log, tmdbImages: tmdbImages()
        )
    }

    /// Runs [body], turning every failure but cancellation into a [SignInFailed].
    private func failing<T>(_ body: () async throws -> T) async throws -> T {
        do {
            return try await body()
        } catch let failed as SignInFailed {
            throw failed
        } catch {
            if Kotlin.isCancellation(error) { throw CancellationError() }
            throw SignInFailed(failure: Kotlin.throwable(error).map { signInFailure(error: $0) } ?? .other, serverTitle: nil)
        }
    }

    /// What [baseUrl]'s server offers: `settings/public`, and `status` for a Jellyfin server's Quick Connect.
    func offer(_ baseUrl: String) async throws -> ServerOffer {
        let seerr = probe(baseUrl)
        defer { seerr.close() }
        return try await failing {
            let settings = try await seerr.publicSettings()
            // Read only where it matters (Quick Connect); a server that will not say gets username and password.
            let version = wantsSeerrVersion(settings: settings) ? await versionOf(seerr) : nil
            return ServerOffer(
                baseUrl: baseUrl, title: settings.applicationTitle,
                methods: signInMethods(settings: settings, seerrVersion: version), approveAt: quickConnectPlace(settings: settings)
            )
        }
    }

    /// The server's Seerr version, or nil when it will not say: then Quick Connect is not offered (DECISIONS 234).
    private func versionOf(_ seerr: SeerrClient) async -> String? {
        try? await seerr.status().version
    }

    /// A Jellyfin or Emby username ([SignInRequest]'s email) and password, through Seerr's `/auth/jellyfin`. Kept, as
    /// Seerr's own password is, to sign in again with.
    func signInWithMediaServer(_ request: SignInRequest) async throws -> Session {
        let account = Account(baseUrl: request.baseUrl, email: request.email, password: request.password, method: .mediaServer)
        return try await signIn(request.baseUrl, account, allowed: Self.mediaServerOff) {
            try await $0.signInWithMediaServer(username: request.email, password: request.password)
        }
    }

    /// plex.tv, made for one step of a Plex sign-in and closed after it: the client identifier kept for this install,
    /// and plex.tv's address (plex.tv itself in a release build).
    private func plexTv() -> PlexTv {
        PlexTv(engine: DarwinKt.darwinEngine(), clientIdentifier: identity.clientIdentifier(), baseUrl: plexAddress())
    }

    /// A Plex link code from plex.tv: with [signInWithPlex], the only moment Hither calls plex.tv.
    func newPlexCode() async throws -> PendingCode {
        let plex = plexTv()
        defer { plex.close() }
        return try await failing {
            let code = try await PlexSignIn(plex: plex).doNewCode()
            return PendingCode(code: code.code, inner: code)
        }
    }

    /// Waits for [code] to be entered at plex.tv/link, then signs in to [baseUrl]'s Seerr with what plex.tv gave, which
    /// goes to Seerr once and is kept nowhere. Cancelling the task cancels the wait: nothing more is asked of plex.tv.
    func signInWithPlex(_ baseUrl: String, _ code: PendingCode) async throws -> Session {
        let linked: PlexLinked
        do {
            let plex = plexTv()
            defer { plex.close() }
            linked = try await failing { try await PlexSignIn(plex: plex).awaitLink(code: code.inner) }
        }
        let account = Account(baseUrl: baseUrl, email: "", password: "", method: .plex)
        return try await signIn(baseUrl, account, allowed: Self.mediaServerOff) { try await $0.signInWithPlex(linked: linked) }
    }

    /// A Quick Connect code from [baseUrl]'s Seerr (Jellyfin makes it).
    func newQuickConnectCode(_ baseUrl: String) async throws -> PendingCode {
        let seerr = probe(baseUrl)
        defer { seerr.close() }
        return try await failing {
            let code = try await QuickConnectSignIn(seerr: seerr).doNewCode()
            return PendingCode(code: code.code, inner: code)
        }
    }

    /// Waits for [code] to be approved in Jellyfin (on a client of its own), then signs in with it on the gateway's.
    /// Cancelling the task cancels the wait.
    func signInWithQuickConnect(_ baseUrl: String, _ code: PendingCode) async throws -> Session {
        do {
            let seerr = probe(baseUrl)
            defer { seerr.close() }
            try await failing { try await QuickConnectSignIn(seerr: seerr).awaitApproval(code: code.inner) }
        }
        let account = Account(baseUrl: baseUrl, email: "", password: "", method: .quickConnect)
        return try await signIn(baseUrl, account, allowed: Self.mediaServerOff) { try await $0.finishQuickConnect(code: code.inner) }
    }

    func signOut() async {
        // Best effort on the server; the device forgets the account either way.
        if let client { try? await client.signOut() }
        try? await cookies?.forget()
        drop()
        try? store.clear()
    }

    private func open(_ baseUrl: String) -> SeerrClient {
        client?.close()
        let jar = DarwinKt.keychainCookies(baseUrl: baseUrl, service: KeychainVault.companion.SERVICE)
        let seerr = DarwinKt.darwinSeerrClient(baseUrl: baseUrl, cookies: jar, failures: Diagnostics.log, tmdbImages: tmdbImages())
        cookies = jar
        client = seerr
        session += 1
        return seerr
    }

    private func drop() {
        client?.close()
        client = nil
        cookies = nil
    }
}
