import Foundation
import HitherCore
import Security

/// The PIN's wrong tries in a row and the wait they made (core's `PinTries`), as the admin's record keeps them, so a
/// restart does not reset them (DECISIONS 93).
struct KeptTries: Codable, Equatable {
    var wrong: Int32 = 0
    var lockedFromMs: Int64?
    var lockedUntilMs: Int64?

    init(wrong: Int32 = 0, lockedFromMs: Int64? = nil, lockedUntilMs: Int64? = nil) {
        self.wrong = wrong
        self.lockedFromMs = lockedFromMs
        self.lockedUntilMs = lockedUntilMs
    }

    init(_ tries: PinTries) {
        self.init(wrong: tries.wrong, lockedFromMs: tries.lockedFromMs?.int64Value, lockedUntilMs: tries.lockedUntilMs?.int64Value)
    }

    var core: PinTries {
        PinTries(wrong: wrong, lockedFromMs: lockedFromMs.map { KotlinLong(value: $0) }, lockedUntilMs: lockedUntilMs.map { KotlinLong(value: $0) })
    }
}

/// The TV's admin account as it is kept (Android's `KeptAdmin`, DECISIONS 91, 92): a local Seerr account with Manage
/// Requests that Tom made for the TV, on the family's server. Its password, so Tom types it once and Hither signs in again
/// when Seerr's 30-day session ends; the session's cookies (core's `SessionCookies` text) and when it was signed in; who
/// it is; the PIN's hash, never its digits; and the wrong tries. All of it in one Keychain item of its own.
struct KeptAdmin: Codable, Equatable, CustomStringConvertible, CustomReflectable {
    let baseUrl: String
    let email: String
    let password: String
    let userId: Int32
    let displayName: String
    let pin: PinHash
    /// The session's cookies as core's `SessionCookies` writes them; nil for none kept.
    var session: String?
    /// When the kept session was signed in (the TV's clock, ms); nil for none kept.
    var signedInAt: Int64?
    var tries = KeptTries()

    /// Never a credential: a value can end up in a log line or a crash report by accident.
    var description: String { "KeptAdmin(userId: \(userId), …)" }
    var customMirror: Mirror { Mirror(self, children: []) }
}

/// Where the admin account is kept: the Keychain, or a test's memory.
protocol AdminStorage: AnyObject, Sendable {
    /// The kept admin, or nil: none set up, or a record this build cannot read. Throws when the Keychain will not answer.
    func admin() throws -> KeptAdmin?
    func save(_ admin: KeptAdmin) throws
    /// Removing the admin account: the item and everything in it.
    func clear() throws
}

extension AdminStorage {
    /// Changes the kept record in place, if there is one. The whole change holds [AdminStore.lock], so the session's
    /// cookies written from a call and the tries written by a switch never undo each other.
    func update(_ change: (inout KeptAdmin) -> Void) throws {
        try AdminStore.lock.withLock {
            guard var kept = try admin() else { return }
            change(&kept)
            try save(kept)
        }
    }
}

/// The admin's record in the Keychain (DECISIONS 205): one generic-password item of its own, service
/// `com.tomdebres.heerr.admin`, apart from the family's account (`AccountStore`) and session (`KeychainVault`).
/// Readable only while the device is unlocked, on this device only, never synced (`…WhenUnlockedThisDeviceOnly`): the
/// admin is never used in the background, so it needs nothing wider. Not in any file or `UserDefaults`; tvOS may purge
/// an app's files but not its Keychain. [clear] deletes it.
final class AdminStore: AdminStorage, @unchecked Sendable {
    static let service = "com.tomdebres.heerr.admin"
    private let account = "approver"
    /// One read-change-write of the record at a time (cookies arrive from the client's own threads).
    static let lock = NSRecursiveLock()

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: Self.service, kSecAttrAccount as String: account,
         kSecAttrSynchronizable as String: false]
    }

    func admin() throws -> KeptAdmin? {
        try Self.lock.withLock {
            var query = self.query
            query[kSecReturnData as String] = true
            query[kSecMatchLimit as String] = kSecMatchLimitOne
            var result: AnyObject?
            let status = SecItemCopyMatching(query as CFDictionary, &result)
            switch status {
            case errSecSuccess:
                guard let data = result as? Data else { return nil }
                // A record this build cannot read is no admin: Settings offers the sign-in again.
                return try? JSONDecoder().decode(KeptAdmin.self, from: data)
            case errSecItemNotFound:
                return nil
            default:
                throw AccountStore.KeychainError(operation: "read", status: status)
            }
        }
    }

    func save(_ admin: KeptAdmin) throws {
        try Self.lock.withLock {
            let data = try JSONEncoder().encode(admin)
            let attributes: [String: Any] = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
            let update = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
            if update == errSecSuccess { return }
            guard update == errSecItemNotFound else { throw AccountStore.KeychainError(operation: "update", status: update) }
            let status = SecItemAdd(query.merging(attributes) { $1 } as CFDictionary, nil)
            guard status == errSecSuccess else { throw AccountStore.KeychainError(operation: "add", status: status) }
        }
    }

    func clear() throws {
        try Self.lock.withLock {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else { throw AccountStore.KeychainError(operation: "delete", status: status) }
        }
    }
}

/// The kept admin's session for core's `SessionCookies`: the cookies' text inside the admin's own record, written back
/// as Seerr sets them (Android's `PersistedCookies` writing into the sealed record).
final class AdminSessionVault: NSObject, CookieVault, @unchecked Sendable {
    private let store: AdminStorage

    init(store: AdminStorage) {
        self.store = store
    }

    func read() -> String? { (try? store.admin())?.session }

    func write(text: String) throws {
        try store.update { $0.session = text }
    }

    func delete() throws {
        try store.update { $0.session = nil }
    }
}

/// A session held in memory only: the approver signed in but not yet kept (nothing is kept until the PIN is set,
/// DECISIONS 92). Its text goes into the record when the PIN is set, and nowhere else.
final class MemoryVault: NSObject, CookieVault, @unchecked Sendable {
    private let lock = NSLock()
    private var text: String?

    var current: String? { lock.withLock { text } }

    func read() -> String? { current }

    func write(text: String) throws {
        lock.withLock { self.text = text }
    }

    func delete() throws {
        lock.withLock { text = nil }
    }
}
