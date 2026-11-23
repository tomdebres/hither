import Foundation
import Security

/// How the account signed in (M8, Android's `AccountMethod`), and so how it signs in again when Seerr ends the session.
enum AccountMethod: String, Codable {
    /// Seerr's own email and password (`auth/local`).
    case local
    /// A Jellyfin or Emby username and password, through Seerr's `/auth/jellyfin`.
    case mediaServer
    /// Plex's link code: nothing kept to sign in with (the token went to Seerr once).
    case plex
    /// Jellyfin's Quick Connect: nothing kept to sign in with.
    case quickConnect

    /// Whether Hither can sign this account in again by itself: only with a kept password (DECISIONS 235).
    var renews: Bool { self == .local || self == .mediaServer }
}

/// The signed-in account as typed: the server, the email (a Jellyfin or Emby account's username) and the password,
/// kept to sign in again when Seerr ends the session (its cookie lasts 30 days from sign-in, not rolling), and how it
/// signed in. A Plex or Quick Connect account keeps an empty email and password. Android seals the same in DataStore.
struct Account: Codable, Equatable {
    let baseUrl: String
    let email: String
    let password: String
    var method: AccountMethod = .local

    init(baseUrl: String, email: String, password: String, method: AccountMethod = .local) {
        self.baseUrl = baseUrl
        self.email = email
        self.password = password
        self.method = method
    }

    private enum CodingKeys: String, CodingKey { case baseUrl, email, password, method }

    /// An account kept before M8 has no method: Seerr's own password.
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        baseUrl = try values.decode(String.self, forKey: .baseUrl)
        email = try values.decode(String.self, forKey: .email)
        password = try values.decode(String.self, forKey: .password)
        method = try values.decodeIfPresent(AccountMethod.self, forKey: .method) ?? .local
    }
}

/// The account in the Keychain: one generic-password item, readable after the first unlock on this device only, never
/// synced. tvOS may purge an app's files but not its Keychain (`docs/app-store-tvos.md`), so a sign-in survives. The
/// session's cookie is core's own item beside it (`KeychainVault`, service `com.tomdebres.heerr.session`).
struct AccountStore {
    static let service = "com.tomdebres.heerr.account"
    private let account = "family"

    struct KeychainError: Error, CustomStringConvertible {
        let operation: String
        let status: OSStatus
        var description: String { "Keychain \(operation) failed: OSStatus \(status)" }
    }

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: Self.service, kSecAttrAccount as String: account]
    }

    func load() throws -> Account? {
        var query = self.query
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        switch status {
        case errSecSuccess:
            guard let data = result as? Data else { return nil }
            // A record this build cannot read is no account: the form asks again.
            return try? JSONDecoder().decode(Account.self, from: data)
        case errSecItemNotFound:
            return nil
        default:
            throw KeychainError(operation: "read", status: status)
        }
    }

    func save(_ value: Account) throws {
        let data = try JSONEncoder().encode(value)
        let update = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecSuccess { return }
        guard update == errSecItemNotFound else { throw KeychainError(operation: "update", status: update) }
        var add = query
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(add as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError(operation: "add", status: status) }
    }

    func clear() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw KeychainError(operation: "delete", status: status) }
    }
}
