import Foundation
import HitherCore

/// The client identifier plex.tv knows this install by (M8, DECISIONS 235; Android's `PlexIdentity`): made the first
/// time a Plex sign-in asks for a code (core's random UUID), then kept for the life of the install, signing out
/// included, as Plex asks of an app (one device, one id).
///
/// It lives in `UserDefaults`, not the Keychain: it is not a secret and identifies nothing but this install. tvOS
/// keeps an app's defaults (its 500 KB of persistent storage) and deletes them with the app, so the id ends with an
/// uninstall, as Android's ends with its data; a Keychain item would outlive the app. Were tvOS ever to lose it, the
/// cost is a second "Hither" among the account's devices at plex.tv.
struct PlexIdentity {
    static let key = "plexClientIdentifier"
    var defaults: UserDefaults = .standard

    func clientIdentifier() -> String {
        if let kept = defaults.string(forKey: Self.key), !kept.isEmpty { return kept }
        let made = doNewPlexClientIdentifier()
        defaults.set(made, forKey: Self.key)
        return made
    }
}

/// Where plex.tv is (M8; Android's `PlexAddress`). A release build always uses plex.tv itself. A debug build may be
/// pointed at the fake plex.tv that `scripts/fake-seerr` serves (`/plextv`) by its environment, as the UI tests do:
/// `HITHER_PLEX_TV=http://localhost:<port>/plextv`. Without it a debug build asks the real plex.tv too.
/// `scripts/tvos-testflight` checks that no release binary carries the switch.
enum PlexAddress {
    static var base: String { base(ProcessInfo.processInfo.environment) }

    static func base(_ environment: [String: String]) -> String {
        #if DEBUG
        if let named = environment["HITHER_PLEX_TV"]?.trimmingCharacters(in: .whitespaces), !named.isEmpty { return named }
        #endif
        return PlexTv.companion.PLEX_TV
    }
}
