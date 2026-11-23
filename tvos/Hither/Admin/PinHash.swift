import CommonCrypto
import Foundation
import Security

/// The admin's PIN as it is kept (Android's `PinHash`, DECISIONS 92): never the digits, only PBKDF2-HMAC-SHA256 of them
/// over a random 16-byte salt, slow on purpose, with the rounds kept beside each hash so a later build can change them.
/// The same algorithm, rounds and fields as Android's, so the two read alike. It is kept inside the admin's own
/// Keychain item besides (`AdminStore`, this device only), and core's limit on wrong tries (`checkPin`) is what keeps
/// 10,000 PINs from a guesser: no number of rounds does that for someone who holds the hash.
struct PinHash: Codable, Equatable, CustomStringConvertible, CustomReflectable {
    /// Base64 of the salt.
    let salt: String
    let iterations: Int
    /// Base64 of the derived 32 bytes.
    let hash: String

    /// Android's 50,000 rounds (DECISIONS 92): about 140 ms a check on the Shield. CommonCrypto on an Apple TV HD's A8
    /// takes less; the check runs off the main thread either way.
    static let rounds = 50_000

    /// A new hash of [pin] over a fresh random salt; nil if the system gave no random bytes or no hash.
    static func of(_ pin: String, iterations: Int = rounds) -> PinHash? {
        var salt = Data(count: 16)
        let random = salt.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        guard random == errSecSuccess, let derived = derive(pin, salt: salt, iterations: iterations) else { return nil }
        return PinHash(salt: salt.base64EncodedString(), iterations: iterations, hash: derived.base64EncodedString())
    }

    /// Whether [pin] is the PIN this was made from, compared in constant time.
    func matches(_ pin: String) -> Bool {
        guard let salt = Data(base64Encoded: salt), let kept = Data(base64Encoded: hash),
              let again = Self.derive(pin, salt: salt, iterations: iterations), again.count == kept.count else { return false }
        var difference: UInt8 = 0
        for (a, b) in zip(again, kept) { difference |= a ^ b }
        return difference == 0
    }

    /// PBKDF2-HMAC-SHA256 of [pin]'s UTF-8 bytes (Java's PBKDF2WithHmacSHA256 reads a PIN's chars the same way), 32 bytes.
    static func derive(_ pin: String, salt: Data, iterations: Int) -> Data? {
        guard iterations > 0 else { return nil }
        var out = Data(count: 32)
        let status = out.withUnsafeMutableBytes { outBytes in
            salt.withUnsafeBytes { saltBytes in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2), pin, pin.utf8.count,
                    saltBytes.bindMemory(to: UInt8.self).baseAddress, salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), UInt32(iterations),
                    outBytes.bindMemory(to: UInt8.self).baseAddress, 32
                )
            }
        }
        return status == kCCSuccess ? out : nil
    }

    /// Never the salt or the hash: a value can end up in a log line or a crash report by accident.
    var description: String { "PinHash(…)" }
    var customMirror: Mirror { Mirror(self, children: []) }
}
