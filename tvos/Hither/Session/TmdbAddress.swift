import Foundation
import HitherCore

/// Where TMDB's image server is (0.7.1, DECISIONS 382; Android's `TmdbAddress`): a server's pictures come from there while
/// it keeps no copies of its own (Seerr's Image Caching off). A release build always uses TMDB itself. A debug build may be
/// pointed at the stand-in that `scripts/fake-seerr` serves (`/tmdb`) by its environment, as the tests do:
/// `HITHER_TMDB_IMAGES=http://localhost:<port>/tmdb`. Without it a debug build asks the real TMDB too.
/// `scripts/tvos-testflight` checks that no release binary carries the switch.
enum TmdbAddress {
    static var base: String { base(ProcessInfo.processInfo.environment) }

    static func base(_ environment: [String: String]) -> String {
        #if DEBUG
        if let named = environment["HITHER_TMDB_IMAGES"]?.trimmingCharacters(in: .whitespaces), !named.isEmpty { return named }
        #endif
        return ImageSource.companion.TMDB_IMAGES
    }
}
