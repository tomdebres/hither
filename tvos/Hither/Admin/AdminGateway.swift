import Foundation
import HitherCore
import Observation

/// What signing in as the would-be admin came to. Nothing is kept until the PIN is set (Android's `AdminSignIn`).
enum AdminSignIn: Equatable {
    /// Signed in and it may approve (`approver` or `administrator`): the PIN comes next.
    case accepted(AdminVerdict, displayName: String)
    /// Signed in, and it may not be the admin (`noRight` or `family`): nothing was kept.
    case refused(AdminVerdict)
    /// The sign-in itself failed, as the family's would (a wrong password, no answer).
    case failed(SignInFailure)
}

/// The admin while it is active: who it is, for the Admin badge and Settings (Android's `ActiveAdmin`).
struct ActiveAdmin: Equatable {
    let userId: Int32
    let displayName: String
}

/// What switching to the admin came to (Android's `AdminSwitch`, DECISIONS 93).
enum AdminSwitch {
    /// Active now.
    case switchedIn(ActiveAdmin)
    /// The PIN was not taken: wrong, a wait on, or the last wrong one (the account removed).
    case pin(PinCheck)
    /// The account's live rights no longer hold Manage Requests: it is kept, and not switched to.
    case noRight
    /// The server refused the kept password on signing in again: the account has been removed.
    case passwordRefused
    /// The server did not answer as expected; nothing changed, and the account is kept.
    case failed
    /// No account is kept.
    case noneKept
    /// Hither left the front (or Switch Back came) while the switch was out: it does not take.
    case closed
}

/// The TV's admin account (Android's `AdminGateway`, M5, DECISIONS 91-93): a second `SeerrClient` with a cookie jar of
/// its own, never the family's. Signing in reads the account's rights live and keeps nothing until the PIN is set.
/// Switching checks the PIN against core's `checkPin` and `PinTries` (four free, waits from the fifth, the tenth
/// removes the account), opens the kept session (signed in again from the password past 25 days or when Seerr ends it),
/// reads the rights live, and starts core's `AdminGuard` idle clock. Hither goes back to the family after 10 minutes
/// without a press, when it leaves the front ([closed]), or on [back]. The admin's calls go through [asAdmin] only;
/// everything else stays the family's `SessionGateway`. One of these for the app (`AppModel`), so the admin outlives a
/// page and goes with the family's account.
@MainActor
@Observable
final class AdminGateway {
    /// The admin while active, else nil: the Admin badge and Settings follow it.
    private(set) var active: ActiveAdmin?
    /// The kept admin's name, nil with none kept: Settings, the way in and the switch name it. Read again after each
    /// change here.
    private(set) var keptName: String?

    @ObservationIgnored private let store: AdminStorage
    @ObservationIgnored private let clock: () -> Int64
    @ObservationIgnored private let hash: (String) -> PinHash?
    @ObservationIgnored private let makeClient: (_ baseUrl: String, _ cookies: Ktor_client_coreCookiesStorage) -> SeerrClient
    @ObservationIgnored private let guardian: AdminGuard
    /// Signed in and accepted, the PIN not yet set: held in memory only.
    @ObservationIgnored private var pending: (client: SeerrClient, vault: MemoryVault, request: SignInRequest, user: User, signedInAt: Int64)?
    /// The active admin's client, while active.
    @ObservationIgnored private var client: SeerrClient?
    @ObservationIgnored private var sessions = 0
    @ObservationIgnored private var idleTask: Task<Void, Never>?
    /// Counts the times Hither left the front or went back to the family: a switch whose session is still being opened
    /// when one comes does not take (Android's `closings`, the reviewer's Nit on #38).
    @ObservationIgnored private var closings = 0
    /// One sign-in, setting, removal or switch at a time (Android's `Mutex`).
    @ObservationIgnored private var tail: Task<Void, Never>?

    struct NotActive: Error {}
    private struct PasswordRefused: Error {}

    init(
        store: AdminStorage = AdminStore(),
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        hash: @escaping (String) -> PinHash? = { PinHash.of($0) },
        idleMs: Int64 = AdminGuardKt.ADMIN_IDLE_MS,
        makeClient: @escaping (String, Ktor_client_coreCookiesStorage) -> SeerrClient = { baseUrl, cookies in
            DarwinKt.darwinSeerrClient(baseUrl: baseUrl, cookies: cookies, failures: Diagnostics.log, tmdbImages: TmdbAddress.base)
        }
    ) {
        self.store = store
        self.clock = clock
        self.hash = hash
        self.makeClient = makeClient
        guardian = AdminGuard(idleMs: idleMs)
        keptName = (try? store.admin())??.displayName
    }

    // MARK: - Setting up

    /// The kept admin's name, read from the Keychain now; nil with none (or a Keychain that will not answer).
    func kept() -> String? {
        let name = (try? store.admin())??.displayName
        keptName = name
        return name
    }

    /// Signs in as the would-be admin and reads its rights live (DECISIONS 92). An account that may not be the admin is
    /// signed out again on the server and nothing is kept; one that may is held in memory until [setPin] or [cancel].
    func signIn(_ request: SignInRequest, family: User) async -> AdminSignIn {
        await serial {
            await self.dropPending()
            let vault = MemoryVault()
            let seerr = self.makeClient(request.baseUrl, SessionCookies(baseUrl: request.baseUrl, vault: vault))
            let user: User
            do {
                user = try await seerr.signIn(email: request.email, password: request.password)
            } catch {
                seerr.close()
                return .failed(Kotlin.throwable(error).map { signInFailure(error: $0) } ?? .other)
            }
            let verdict = AdminKt.adminVerdict(candidate: user, family: family)
            switch verdict {
            case .approver, .administrator:
                self.pending = (seerr, vault, request, user, self.clock())
                return .accepted(verdict, displayName: Self.name(of: user))
            default:
                // Nothing kept, and the session just made is ended on the server: for the family's own account that is a
                // session of its own, not the one Hither runs on.
                try? await seerr.signOut()
                seerr.close()
                return .refused(verdict)
            }
        }
    }

    /// Keeps the admin signed in by [signIn] with [pin]'s hash; false when nothing was waiting or it could not be kept.
    func setPin(_ pin: String) async -> Bool {
        await serial {
            guard let waiting = self.pending, AdminKt.isPin(digits: pin) else { return false }
            let hash = self.hash
            guard let hashed = await Task.detached(priority: .userInitiated, operation: { hash(pin) }).value else { return false }
            var kept = KeptAdmin(
                baseUrl: waiting.request.baseUrl,
                email: waiting.request.email,
                password: waiting.request.password,
                userId: waiting.user.id,
                displayName: Self.name(of: waiting.user),
                pin: hashed,
                session: waiting.vault.current,
                signedInAt: waiting.signedInAt
            )
            #if DEBUG
            // A UI test of the tenth wrong PIN starts the account at nine wrong ones, no wait (`HITHER_ADMIN_TRIES=9`): the
            // waits before the tenth come to 81½ minutes. Debug builds only; the unit tests walk the whole row.
            if let wrong = ProcessInfo.processInfo.environment["HITHER_ADMIN_TRIES"].flatMap(Int32.init) { kept.tries = KeptTries(wrong: wrong) }
            #endif
            do {
                try self.store.save(kept)
            } catch {
                // Kept nothing: a half-written record would be worse than asking again.
                try? self.store.clear()
                return false
            }
            self.pending = nil
            waiting.client.close()
            self.keptName = kept.displayName
            return true
        }
    }

    /// Drops an admin signed in but not yet kept, and signs it out on the server.
    func cancel() async {
        await serial { await self.dropPending() }
    }

    /// Removes the kept admin: back to the family, its session signed out on the server (best effort), the item deleted.
    func remove() async {
        await serial { await self.removeLocked() }
    }

    private func removeLocked() async {
        await dropPending()
        endActive()
        if let kept = (try? store.admin()) ?? nil, kept.session != nil {
            let seerr = makeClient(kept.baseUrl, SessionCookies(baseUrl: kept.baseUrl, vault: AdminSessionVault(store: store)))
            try? await seerr.signOut()
            seerr.close()
        }
        try? store.clear()
        keptName = nil
    }

    private func dropPending() async {
        guard let waiting = pending else { return }
        pending = nil
        try? await waiting.client.signOut()
        waiting.client.close()
    }

    // MARK: - Switching

    /// Switches to the admin with [pin] (DECISIONS 93): the PIN against its kept hash and the wrong tries, then its
    /// session, signed in again from the kept password if it has ended or is past 25 days, then its rights read live.
    func switchIn(_ pin: String) async -> AdminSwitch {
        await serial { await self.switchLocked(pin) }
    }

    private func switchLocked(_ pin: String) async -> AdminSwitch {
        let closingsAtStart = closings
        guard let kept = (try? store.admin()) ?? nil else { return .noneKept }
        // The PIN first, against the kept tries; slow on purpose, so off the main thread. Nothing is checked during a wait.
        let tries = kept.tries.core
        let now = clock()
        let pinHash = kept.pin
        let pair = await Task.detached(priority: .userInitiated) {
            AdminGuardKt.checkPin(tries: tries, nowMs: now, pin: pin) { KotlinBoolean(bool: pinHash.matches($0)) }
        }.value
        guard let check = pair.first, let after = pair.second else { return .failed }
        if KeptTries(after) != kept.tries {
            if check is PinCheckRemoved {
                await removeLocked()
                return .pin(check)
            }
            do {
                try store.update { $0.tries = KeptTries(after) }
            } catch {
                // A store that will not keep a wrong try must not let the PIN be guessed for free.
                if !(check is PinCheckRight) { return .pin(check) }
            }
        }
        guard check is PinCheckRight else { return .pin(check) }
        // The session: from the kept cookies; signed in again when it has ended or is past 25 days (Seerr's last 30, and
        // use does not extend them).
        let seerr = open(kept)
        let user: User
        do {
            if AdminKt.adminSessionDue(signedInAtMs: kept.signedInAt.map { KotlinLong(value: $0) }, nowMs: clock()) {
                user = try await renew(seerr)
            } else {
                do {
                    user = try await seerr.me()
                } catch where Kotlin.isSignedOut(error) {
                    user = try await renew(seerr)
                }
            }
        } catch is PasswordRefused {
            return await passwordRefused(seerr)
        } catch {
            // Not answering, a proxy's refusal, the CSRF lock-out: nothing changed, the account is kept.
            seerr.close()
            return .failed
        }
        // Its rights, read live: an account that has lost Manage Requests is not switched to.
        guard AdminKt.mayDecide(user: user) else {
            seerr.close()
            return .noRight
        }
        // Hither closed, or went back to the family, while the session was opened: it does not take.
        guard closings == closingsAtStart else {
            seerr.close()
            return .closed
        }
        client?.close()
        client = seerr
        sessions += 1
        let admin = ActiveAdmin(userId: user.id, displayName: user.displayName.isEmpty ? kept.displayName : user.displayName)
        guardian.switchedIn(nowMs: clock())
        active = admin
        startIdleClock()
        return .switchedIn(admin)
    }

    private func passwordRefused(_ seerr: SeerrClient) async -> AdminSwitch {
        // Only this answer forgets the account, as for the family's (33): the password has been changed.
        seerr.close()
        await removeLocked()
        return .passwordRefused
    }

    /// A client on the kept session, its cookies written back into the admin's record as the server sets them.
    private func open(_ kept: KeptAdmin) -> SeerrClient {
        makeClient(kept.baseUrl, SessionCookies(baseUrl: kept.baseUrl, vault: AdminSessionVault(store: store)))
    }

    /// Signs in again from the kept password; the user. [PasswordRefused] for the password refused (only `auth/local`'s
    /// refusal means that); anything else as the client throws it.
    private func renew(_ seerr: SeerrClient) async throws -> User {
        guard let kept = (try? store.admin()) ?? nil else { throw NotActive() }
        _ = try await seerr.publicSettings()
        let user: User
        do {
            user = try await seerr.signIn(email: kept.email, password: kept.password)
        } catch where Kotlin.isRefused(error) {
            throw PasswordRefused()
        }
        let now = clock()
        try? store.update { $0.signedInAt = now }
        return user
    }

    /// Runs [body] with the active admin's client; [NotActive] when the admin is not active. A session found over is
    /// signed in again once from the kept password and the call run once more, as the family's gateway does (38): only
    /// after a refusal before any route ran. Not the family's `SessionGateway`: nothing written for it can be handed this.
    func asAdmin<T>(_ body: (SeerrClient) async throws -> T) async throws -> T {
        guard let seerr = client else { throw NotActive() }
        let number = sessions
        do {
            return try await body(seerr)
        } catch where Kotlin.isSignedOut(error) {
            // Refused before any route ran: sign in again once, below, and ask once more.
        }
        let renewed: Result<SeerrClient, Error> = await serial {
            guard let current = self.client else { return .failure(NotActive()) }
            if current !== seerr || self.sessions != number { return .success(current) }
            do {
                _ = try await self.renew(current)
            } catch is PasswordRefused {
                _ = await self.passwordRefused(current)
                return .failure(NotActive())
            } catch {
                return .failure(error)
            }
            self.sessions += 1
            return .success(current)
        }
        return try await body(try renewed.get())
    }

    /// Back to the family's account (Switch Back), the admin's session kept for next time.
    func back() {
        closings += 1
        endActive()
    }

    /// Hither left the front: back to the family's account (Tom's "or when Hither is closed", DECISIONS 93).
    func closed() {
        closings += 1
        guardian.closed()
        endActive()
    }

    /// A press on the remote: the idle clock starts again.
    func key() {
        guardian.key(nowMs: clock())
    }

    /// Falls back when the idle clock has run out; true when it did.
    @discardableResult
    func idleCheck() -> Bool {
        guard guardian.idle(nowMs: clock()) else { return false }
        endActive()
        return true
    }

    private func startIdleClock() {
        idleTask?.cancel()
        idleTask = Task { [weak self] in
            while let self, let due = self.guardian.dueAtMs()?.int64Value {
                try? await Task.sleep(for: .milliseconds(max(1_000, due - self.clock())))
                if Task.isCancelled || self.idleCheck() { return }
            }
        }
    }

    private func endActive() {
        guardian.back()
        idleTask?.cancel()
        idleTask = nil
        active = nil
        client?.close()
        client = nil
    }

    /// The account's name as Seerr shows it, its email when it has none.
    private static func name(of user: User) -> String {
        user.displayName.isEmpty ? (user.email ?? "") : user.displayName
    }

    /// Runs [body] after every earlier one has finished.
    private func serial<T>(_ body: @escaping @MainActor () async -> T) async -> T {
        let previous = tail
        let task = Task { @MainActor in
            await previous?.value
            return await body()
        }
        tail = Task { @MainActor in _ = await task.value }
        return await task.value
    }
}
